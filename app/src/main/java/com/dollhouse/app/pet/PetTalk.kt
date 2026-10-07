package com.dollhouse.app.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dollhouse.app.agent.ChatToolRegistry
import com.dollhouse.app.agent.ChatToolRunner
import com.dollhouse.app.ai.DeepSeekClient
import com.dollhouse.app.ai.MemSummarizer
import com.dollhouse.app.ai.ThinkLevel
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ChatHistoryStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.device.OcrEngine
import com.dollhouse.app.ui.chat.ChatPanel
import com.dollhouse.app.ui.chat.ChatSessions
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】人偶迷你聊天的纯逻辑层：三击召唤后收下的这条消息怎么发、气泡怎么展示、点一下怎么翻页。
 *
 * 【入口】只由 PetService 调用（talk / onBubbleTap / isBusy / cancel / release）。
 *
 * 【交互】不持有任何 View：说话、清泡、切行数全部经 Host 回调转给 PetService；
 *          会话历史复用 ChatHistoryStore 的独立版（ctx + history），存档键与全屏聊天页完全一致。
 *
 * 【共用】请求组装走 ChatHistoryStore.buildRequest(ctx, history)，
 *          人设 / 好感度 / 思考档位 / 长期记忆 / 学习示例 / 图片策略与全屏页同源，不会分叉。
 *
 * 【取舍】不传 tools：气泡只有两行，联网结果没有展示位，且少一轮往返更跟手。
 *          完整工具能力仍在全屏聊天页（首页 →「打开聊天」）。
 *
 * 【扩展】改气泡翻页规则只改 onBubbleTap；改请求参数只改 request。
 *
 * 【坑】PetBus 的 listener 是单个静态引用，本类绝不能 register，否则会把 ChatPanel 顶掉。
 */
class PetTalk(private val ctx: Context, private val host: Host) {
    companion object {
        /** 气泡相位：空闲 / 思考中 / 两行截断 / 已展开。 */
        const val PHASE_IDLE = 0
        const val PHASE_LOADING = 1
        const val PHASE_TRUNC = 2
        const val PHASE_EXPANDED = 3

        /** 请求兜底超时：网络卡死时不能让人偶永远停在「思考中」。 */
        private const val REQUEST_TIMEOUT_MS = 60000L

        /** 工具循环最大轮数（与全屏页 ChatPanel 的 i<3 一致），防止模型来回调用烧钱。 */
        private const val MAX_TOOL_ROUNDS = 3

        private const val LOADING_TEXT = "思考中…"

        /** 工具执行期间的临时气泡文案（迷你框没有专门的工具提示位，借用常驻气泡）。 */
        private const val TOOL_TEXT = "我查一下…"

        /**
         * 气泡每页目标行数。
         * 【改粒度只改 PetBubble.PAGE_SENTENCES】这里只是引用，避免两处各写一份。
         */
        private const val BUBBLE_PAGE_SENTENCES = PetBubble.PAGE_SENTENCES

        /** 把追加消息逐条拼到请求体尾部（messages 数组）。 */
        private fun appendAll(body: JSONArray?, extra: JSONArray?) {
            if (body == null || extra == null) {
                return
            }
            for (i in 0 until extra.length()) {
                val item = extra.opt(i)
                if (item != null) {
                    try {
                        body.put(item)
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
    }

    interface Host {
        /** 显示常驻气泡（不自动消失），相位由本类管理。 */
        fun saySticky(str: String)

        /** 显示临时气泡，ms 毫秒后自动消失（报错、缺配置这类即时反馈）。 */
        fun sayTemp(str: String, ms: Long)

        /** 收掉气泡并复位窗口高度。 */
        fun clearBubble()

        /** 切换气泡最大行数（截断 / 展开）。 */
        fun setBubbleLines(i: Int)

        /** 请求进行中开关：输入框据此禁用，避免连点重复发。 */
        fun onBusy(z: Boolean)

        /** 当前气泡文案是否被截断（决定点一下是展开还是收掉）。 */
        fun bubbleHasOverflow(): Boolean

        /** 把一段长文本按标点分页展示（只显首屏），返回总页数。 */
        fun showBubblePaged(str: String, i: Int): Int

        /** 取第 i 页文本（翻页）。 */
        fun bubblePage(i: Int): String?
    }

    private val ui = Handler(Looper.getMainLooper())

    /** 与本对话共用的历史数组；懒加载自 PetPrefs 的当前会话档。 */
    private var history: JSONArray? = null

    /**
     * 本轮对话绑定的人偶会话 id。
     * 【为什么记住】原先读/写在两处各自 ensurePet()，若请求在途期间用户把该人偶会话删掉，
     *   落盘那次会拿到一个「刚补建的新 id」，把旧数组写到新会话名下（串档）。改成进轮次时
     *   定一次 id，整轮都认它。
     */
    private var petConvId: String? = null

    /** 本轮绑定的会话 id；没有则现取（不改变当前池）。 */
    private fun convId(): String {
        val id = this.petConvId
        if (id == null || id.isEmpty()) {
            this.petConvId = ChatSessions.ensurePet(this.ctx)
        }
        return this.petConvId!!
    }

    private var task: DeepSeekClient.Task? = null
    private var phase = PHASE_IDLE

    /** 本次回复的未切片全文（翻页源头）。 */
    private var bubbleFull: String? = null

    /** 当前显示到第几页（翻页游标）。 */
    private var pageIndex = 0

    /** 本次回复全文的总页数（切页上限）。 */
    private var totalPages = 0

    /** 兜底超时是否已触发（用于区分「用户主动停」与「等太久」）。 */
    private var timedOut = false

    /** 思考参数被服务端拒后是否已降级过（限一次，防止死循环）。 */
    private var thinkDegraded = false

    /**
     * 【v0.0.1·Shizuku】工具循环累积的追加消息（assistant(tool_calls) + 各条 role=tool）。
     * null 表示本轮首轮请求；每完成一轮工具执行就被整体替换。
     */
    private var toolFollowUp: JSONArray? = null

    /** 本轮已用掉的工具轮数；到 MAX_TOOL_ROUNDS 后不再下发 tools，强制收敛到文本回复。 */
    private var toolRound = 0

    /**
     * 【Shizuku】轮次代号：每次 talk / cancel / 工具超时都会 +1。
     * 后台工具线程回来时比对代号，不一致说明这轮已作废（用户取消或已判超时），直接丢弃结果。
     */
    private var generation = 0

    private val timeout = Runnable {
        // 工具执行阶段：task 已置空但 phase 仍是 LOADING，此时没有任何 Task 可取消，
        //   直接判超时收尾（否则气泡会永久停在「我查一下…」）。
        val t = this.task
        if (t == null) {
            if (this.phase != PHASE_LOADING) {
                return@Runnable
            }
            this.generation++
            this.phase = PHASE_IDLE
            this.host.onBusy(false)
            this.fail("等太久了…")
            return@Runnable
        }
        this.timedOut = true
        t.cancel()
    }

    /** 当前是否有在途请求。 */
    fun isBusy(): Boolean {
        return this.task != null
    }

    /**
     * 迷你输入框发出一条消息。
     * 【顺序】先落档（user）再请求，保证「发出去的消息」在切到全屏页时也已经看得到。
     */
    fun talk(str: String?) {
        val trim = if (str == null) "" else str.trim()
        if (trim.isEmpty() || this.task != null) {
            return
        }
        if (!PetPrefs.hasKey(this.ctx)) {
            this.host.sayTemp("还没配置 API Key（首页 → 聊天设置）", 3600L)
            return
        }
        // 【池跟随】每轮开始时绑定当前人偶池会话：抽屉里选了另一条人偶会话，这一轮就写到那条上；
        //   绑定后整轮（读 + 两次落盘）都认同一个 id。
        this.petConvId = ChatSessions.ensurePet(this.ctx)
        reloadHistory()
        ChatHistoryStore.push(this.ctx, this.history!!, "user", trim, convId())
        // 新一轮对话清掉上一轮的翻页状态，避免旧游标串到新回复上。
        this.bubbleFull = null
        this.pageIndex = 0
        this.totalPages = 0
        // 【Shizuku】工具循环状态一并复位，否则上一轮的 followUp 会串进新提问。
        this.toolFollowUp = null
        this.toolRound = 0
        // 新轮次代号：在途的旧工具线程回来时会因代号不符而作废。
        this.generation++
        this.phase = PHASE_LOADING
        this.host.setBubbleLines(PetBubble.LINES_TRUNC)
        this.host.saySticky(LOADING_TEXT)
        this.host.onBusy(true)
        // 【OCR】发请求前先把历史里还没扫过的图在后台扫成文字（扫完才真正发）；
        //   请求正文里的图片文字就来自这份缓存。扫描期间气泡停在「思考中…」。
        val mine = this.generation
        OcrEngine.recognizePending(this.ctx, this.history, Runnable {
            // 【守卫】扫描期间用户取消 / 又发了一条：代号变了就作废，不再发请求。
            if (this.generation != mine || this.task != null) {
                return@Runnable
            }
            this.request()
        })
    }

    /**
     * 点了一下气泡：按相位推进，返回 true 表示已消费这一下点击（不要再触发单击跳）。
     *
     * 【相位】思考中 → 不动（防误点打断）；还有下一片 → 翻页；最后一片 → 收掉。
     * 【变更 v2.5】旧行为是「两行截断 → 一次全展开 → 收掉」，只有一次翻页；
     *   用户要求「有消息没说完时点一下进下一片，说完了点一下取消」，故改成分页游标。
     * 【变更 v2.7】页边界改由 PetBubble 按「句」划分（每页两句完整的话，超三行退一句），
     *   游标从「行号」变为「页号」。
     */
    fun onBubbleTap(): Boolean {
        val i = this.phase
        if (i == PHASE_LOADING) {
            // 思考中：不打断（也不把「思考中…」换成台词）。
            return true
        }
        if (i == PHASE_TRUNC) {
            // 【v2.6】按「页」推进（不再是行号）；页边界由 PetBubble 按标点切好。
            val next = this.pageIndex + 1
            val str = if (this.bubbleFull == null) null else this.host.bubblePage(next)
            if (str != null && !str.trim().isEmpty()) {
                this.pageIndex = next
                this.host.saySticky(str)
                return true
            }
            // 已经最后一片（或取页失败）：收掉。
            //   【v2.7】不再用 totalPages 当上限：它是 showPaged 那一刻的快照，
            //   之后气泡宽度一变页数就会重算，拿旧上限判会早停 / 越界。
            //   直接看「下一页取不取得出来」，天然自洽。
            this.phase = PHASE_IDLE
            this.host.clearBubble()
            return true
        }
        if (i == PHASE_EXPANDED) {
            // 全文一页就放得下：点一下直接收掉。
            this.phase = PHASE_IDLE
            this.host.clearBubble()
            return true
        }
        return false
    }

    /** 主动取消在途请求（收起输入框 / 服务销毁）。 */
    fun cancel() {
        val task = this.task
        if (task != null) {
            task.cancel()
        }
        // 同步复位：即使回调被 identity 校验丢弃，状态也不会卡在「思考中」。
        this.ui.removeCallbacks(this.timeout)
        this.task = null
        this.phase = PHASE_IDLE
        this.bubbleFull = null
        this.pageIndex = 0
        this.totalPages = 0
        // 【Shizuku】工具循环状态一并复位，否则上一轮的 followUp 会串进新提问。
        this.toolFollowUp = null
        this.toolRound = 0
        // 取消即作废当前轮次：在途的工具线程回来时会被代号校验挡掉。
        this.generation++
        // 【OCR】扫描中的那一轮（task 还没建）同样靠 generation 作废，这里不用额外处理。
    }

    /** 服务销毁：取消请求 + 摘掉所有回调，避免 Handler 泄漏。 */
    fun release() {
        cancel()
        this.ui.removeCallbacksAndMessages(null)
        // 【坑】请求回调挂在 DeepSeekClient.MAIN 上，这里摘不掉；
        //   靠 onMessage 里的 identity 校验把迟到回调挡掉（见 request）。
        this.task = null
        this.phase = PHASE_IDLE
    }

    /**
     * 每次发消息前重读当前会话档。
     * 【为什么不能只读一次】全屏聊天页可能在这期间往同一个键里写了新消息；
     *   若本类还拿着旧数组整段回写，会把那边的新消息覆盖掉（丢消息）。
     */
    private fun reloadHistory() {
        // 【v0.0.1】人偶的读写固定落在专属会话，不再借当前会话的槽。
        val id = convId()
        try {
            this.history = JSONArray(PetPrefs.convHistory(this.ctx, id))
        } catch (unused: Throwable) {
            this.history = JSONArray()
        }
        // 【v2.8·P2-①】与全屏页同款载入收口：盘上若留着展开态（带 _prev 的补回批），
        //   就地收回暂存位并消费标记；否则这份带标记的数组会被当成普通历史，
        //   一旦超过 60 条就会在 trimForSave 里连中段一起丢掉。
        ChatHistoryStore.collapsePrevOnLoad(this.ctx, id, this.history)
    }

    private fun request() {
        try {
            doRequest()
        } catch (t: Throwable) {
            // 【兜底】组装请求体这一步理论上不会抛，但没有它就会永久停在「思考中」。
            this.ui.removeCallbacks(this.timeout)
            this.task = null
            fail(t.message.toString())
        }
    }

    private fun doRequest() {
        // 【OCR】图片已在 talk() 里先扫成文字，这里按纯文本组装即可，不再需要「本次是否带图」。
        val body = ChatHistoryStore.buildRequest(this.ctx, this.history!!)
        // 【Shizuku】上一轮工具执行产出的追加消息（assistant(tool_calls) + role=tool）挂回请求体，
        //   否则服务端会因为没有与 tool 消息配对的 tool_calls 而判非法。
        if (this.toolFollowUp != null) {
            appendAll(body, this.toolFollowUp)
        }
        val params = samplingParams()
        // 【OCR】不再有「带图就切视觉模型」这一步：图片已经变成文字，普通文本模型即可。
        val model = PetPrefs.model(this.ctx)
        // 【Shizuku】工具轮数未用尽才下发 tools；用尽后强制只走文本回复。
        //   buildSchema 内部已按 Shizuku 授权状态对 shell 工具做门控：未授权就不会出现。
        var tools: JSONArray? = null
        if (this.toolRound < MAX_TOOL_ROUNDS) {
            val schema = ChatToolRegistry.buildSchema(PetPrefs.webSearchEnabled(this.ctx),
                    PetPrefs.memAutoSave(this.ctx))
            if (schema.length() > 0) {
                tools = schema
            }
        }
        this.timedOut = false
        // 【坑·必须】上一轮的 timeout 可能还挂在 Handler 上（工具节点重挂的那次），
        //   不先摘掉就会同时存在两个同一 Runnable，旧的会在「上一轮 +60s」提前把新请求掐掉。
        this.ui.removeCallbacks(this.timeout)
        this.ui.postDelayed(this.timeout, REQUEST_TIMEOUT_MS)
        val mine = DeepSeekClient.Task()
        this.task = mine
        // 【坑·真 bug】chatRaw 的形参顺序是 (apiKey, baseUrl, model, ...)：
        //   写反了不报编译错，只会在运行时把 URL 拼成 https://<Key>/chat/completions，
        //   于是「全屏聊天正常、只有迷你框报服务器错误」。
        DeepSeekClient.chatRaw(ProviderStore.activeProvider(this.ctx), PetPrefs.apiKey(this.ctx),
                PetPrefs.baseUrl(this.ctx), model,
                body, tools, params, mine, DeepSeekClient.RawCallback { jSONObject, str ->
            // 【守卫】只认「当前在途的那一次」：release / 新请求已经换掉 task 时，
            //        迟到的旧回调直接丢弃，不能让它把新请求的状态清掉。
            if (this.task !== mine) {
                return@RawCallback
            }
            this.onReply(jSONObject, str)
        })
    }

    /** 采样参数：与全屏页聊天的默认档保持一致（不注入思考档位时只发温度与上限）。 */
    private fun samplingParams(): JSONObject {
        val jSONObject = JSONObject()
        try {
            jSONObject.put("temperature", 1.2d)
            jSONObject.put("max_tokens", 500)
            val thinkLevel = PetPrefs.thinkLevel(this.ctx)
            // 【三件套】与 ChatPanel 同款：思考参数被服务端拒（参数类 400）时剥掉重试一次，限一次。
            if (!this.thinkDegraded && ThinkLevel.shouldInject(thinkLevel)) {
                ThinkLevel.parameters(jSONObject, thinkLevel)
            }
        } catch (unused: Throwable) {
        }
        return jSONObject
    }

    private fun onReply(jSONObject: JSONObject?, str: String?) {
        this.ui.removeCallbacks(this.timeout)
        this.task = null
        if (DeepSeekClient.STOPPED == str) {
            finishStopped()
            return
        }
        if (str != null) {
            // 【三件套】参数类 400：剥掉思考参数重试一次，避免同一个错误永久复现。
            if (!this.thinkDegraded && str.indexOf("400") >= 0
                    && ThinkLevel.shouldInject(PetPrefs.thinkLevel(this.ctx))) {
                this.thinkDegraded = true
                request()
                return
            }
            this.phase = PHASE_IDLE
            this.host.onBusy(false)
            fail(str)
            return
        }
        val content = if (jSONObject == null) "" else jSONObject.optString("content", "")
        // 【Shizuku】工具调用优先：模型这一轮要调工具时，正文通常是空的，不能判成「空内容」直接报错。
        val calls = if (jSONObject == null) null else jSONObject.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) {
            // 【收敛·必须】轮数用尽后不再执行工具：部分模型/网关在不下发 tools 时仍会幻觉出
            //   tool_calls，若继续执行就形成「runTools → doRequest → tool_calls」的无限续请求，
            //   永不停止且持续烧 token（全屏页 ChatPanel 有 i<3 守卫，这里必须对齐）。
            if (this.toolRound >= MAX_TOOL_ROUNDS) {
                if (!content.isEmpty()) {
                    finish(content)
                } else {
                    fail("工具调用次数已达上限")
                }
                return
            }
            runTools(jSONObject!!, calls)
            return
        }
        if (content.isEmpty()) {
            // 【兜底】模型返回空内容时不要往历史里塞一条空 assistant。
            this.phase = PHASE_IDLE
            this.host.onBusy(false)
            fail("模型返回了空内容")
            return
        }
        finish(content)
    }

    /**
     * 【Shizuku】执行一轮工具调用，然后把结果拼回请求体再问一次模型。
     *
     * 【线程】本方法由主线程回调进来；工具执行（shell / 联网）会阻塞，故整体挪到后台线程，
     *   结果再 post 回主线程继续 onReply 那条链路之外的下一轮请求。
     * 【结构】请求体不能只追加 role=tool：必须先把带 tool_calls 的 assistant 中间消息放回去，
     *   否则服务端会因为「tool 消息找不到对应的 tool_calls」而判非法请求。
     */
    private fun runTools(reply: JSONObject, calls: JSONArray) {
        // 工具轮数 +1；用尽后 doRequest 不再下发 tools。
        this.toolRound++
        this.phase = PHASE_LOADING
        this.host.saySticky(TOOL_TEXT)
        // 重新计时：工具执行可能较久，但整体仍受同一个兜底超时保护（Task 已置空，不误伤）。
        //   【必须】先摘掉旧的那次，否则同一 Runnable 会排两次，旧那次会提前掐掉后续请求。
        this.ui.removeCallbacks(this.timeout)
        this.ui.postDelayed(this.timeout, REQUEST_TIMEOUT_MS)
        val self = this
        // 记下本轮代号：工具线程回来后若代号已变（用户取消 / 超时判死 / 开了新一轮），结果作废。
        val gen = this.generation
        Thread({
            val followUp = JSONArray()
            try {
                followUp.put(ChatToolRunner.assistantWithCalls(reply, calls))
            } catch (ignored: Throwable) {
            }
            val toolMsgs = ChatToolRunner.runAll(calls)
            appendAll(followUp, toolMsgs)
            val result = followUp
            self.ui.post {
                // 期间若被 cancel / 判超时 / 换过轮次，这轮结果作废，直接丢弃。
                if (self.generation != gen) {
                    return@post
                }
                // 【累积·必须】只保留最新一轮会让第 3 轮看不到第 1/2 轮的工具结果，
                //   模型会因「结果不明」反复重调同一条（也是无限续请求的诱因）。
                //   这里逐条追加，多轮上下文完整；总量受 MAX_TOOL_ROUNDS 与
                //   ShizukuBridge.MAX_OUTPUT_CHARS 双重约束，不会无限膨胀。
                if (self.toolFollowUp == null) {
                    self.toolFollowUp = JSONArray()
                }
                appendAll(self.toolFollowUp, result)
                try {
                    self.doRequest()
                } catch (t: Throwable) {
                    self.ui.removeCallbacks(self.timeout)
                    self.phase = PHASE_IDLE
                    self.host.onBusy(false)
                    self.fail(t.message.toString())
                }
            }
        }, "feiyu-mini-tools").start()
    }

    /** 用户主动停：静默收尾，不要当故障报出来；兜底超时则给一句人话。 */
    private fun finishStopped() {
        val wasTimeout = this.timedOut
        this.timedOut = false
        this.phase = PHASE_IDLE
        this.host.onBusy(false)
        if (wasTimeout) {
            fail("等太久了…")
        } else {
            this.host.clearBubble()
        }
    }

    /**
     * 【v0.0.1】自动压缩旁路：迷你框过去不触发（它没有 ChatPanel），现在走
     *   MemSummarizer 的无 UI 通道，与全屏页共用同一份压缩逻辑，不再分叉。
     * 【为什么不在本类直接判阈值】阈值/双触发/RUNNING 锁/守卫全在 MemSummarizer 里，
     *   这里只提供数据面（ctx + history 活引用 + 锚定 id），否则两处各写一份必然漂移。
     * 【主线程】与 ChatPanel 版一致：post 到 ui 线程再发起，避免在回调线程碰 prefs。
     * 【表现面】迷你框没有提示位与「总结中」指示，flash/busy 降级为只记日志 ——
     *   气泡此刻正被回复占用，任何提示都会把它顶掉。
     */
    private fun maybeAutoSummarize() {
        if (!PetPrefs.memAuto(this.ctx)) {
            return
        }
        val self = this
        this.ui.post {
            MemSummarizer.maybeAuto(object : MemSummarizer.Sink {
                override fun ctx(): Context {
                    return self.ctx
                }

                override fun convId(): String {
                    return self.convId()
                }

                override fun history(): JSONArray {
                    return self.history!!
                }

                override fun setHistory(h: JSONArray) {
                    self.history = h
                }

                override fun ctxUsedChars(): Int {
                    return MemSummarizer.ctxUsedChars(self.ctx, self.history)
                }

                /** 迷你框无「展开态」概念：载入时已由 collapsePrevOnLoad 收口。 */
                override fun foldPrev(): Boolean {
                    return false
                }

                override fun clearPrev() {
                }

                override fun flash(msg: String) {
                    Logs.i("DollhouseMemo", "[mini] " + msg)
                }

                override fun busy(busy: Boolean) {
                }

                override fun uiRefresh(after: Runnable?) {
                    if (after != null) {
                        after.run()
                    }
                }

                override fun post(r: Runnable) {
                    self.ui.post(r)
                }

                override fun postDelayed(r: Runnable, ms: Long) {
                    self.ui.postDelayed(r, ms)
                }

                override fun removeCallbacks(r: Runnable) {
                    self.ui.removeCallbacks(r)
                }
            })
        }
    }

    /**
     * 回复落地：剥掉首行好感度记账、写档、贴气泡。
     * 【坑】好感度首行契约（[好感度:+3]）与全屏页共用同一对正则，
     *        改这里等于同时改两个入口，别只改一边。
     */
    private fun finish(str: String?) {
        val raw = if (str == null) "" else str
        var delta = 0
        var hasDelta = false
        val matcher = ChatPanel.AFFECTION_HEAD.matcher(raw)
        if (matcher.find()) {
            try {
                delta = matcher.group(1).toInt()
                hasDelta = true
            } catch (unused: Throwable) {
                delta = 0
            }
        }
        // 【修 v0.0.1】两条路分开：
        //   storeText = 去掉好感度记账后的原文（保留换行）→ 写历史，聊天页与下一轮上下文都是原貌；
        //   text      = storeText 压成单行 → 只给人偶气泡用（模型自带换行会把句子拦腰断开）。
        var storeText = ChatPanel.AFFECTION_ANY.matcher(raw).replaceAll("").trim()
        if (storeText.isEmpty()) {
            storeText = if (hasDelta) "……" else raw.trim()
        }
        val text = ChatHistoryStore.oneLine(storeText)
        if (hasDelta) {
            PetBus.affection(PetPrefs.addAffection(this.ctx, delta))
        }
        ChatHistoryStore.push(this.ctx, this.history!!, "assistant", storeText, convId())
        // 【v0.0.1】一轮回复落盘完毕，走无 UI 通道触发自动压缩（原先迷你框这条旁路不触发）。
        maybeAutoSummarize()
        // 【分页 v2.7】首屏只显示第一页=前两句完整的话（按句末标点切，超三行退一句），
        //   全文留给翻页用；
        //   【坑】总页数必须由 PetView/PetBubble 按「全文」算，不能拿切片的长度算
        //   （越切越短，页数会漂）。
        this.bubbleFull = text
        this.pageIndex = 0
        this.phase = PHASE_TRUNC
        this.host.onBusy(false)
        // 【v2.7】一片最多 PAGE_MAX_LINES(3) 行（用户说的「格子」）：按此上限排版，
        //   气泡高度就等于一片的高度，不会再出现「排了 10 行、只露 3 行」的多余留白。
        this.host.setBubbleLines(PetBubble.PAGE_MAX_LINES)
        this.totalPages = this.host.showBubblePaged(text, BUBBLE_PAGE_SENTENCES)
        if (this.totalPages <= 1) {
            // 一页放得下：没有下一片，点一下直接收掉（避免空点击）。
            this.phase = PHASE_EXPANDED
        }
    }

    private fun fail(str: String?) {
        this.phase = PHASE_IDLE
        this.host.onBusy(false)
        this.host.sayTemp("（出错了：" + str + "）", 4200L)
    }
}
