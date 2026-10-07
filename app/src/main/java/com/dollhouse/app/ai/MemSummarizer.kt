package com.dollhouse.app.ai

import android.content.Context
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ChatHistoryStore
import com.dollhouse.app.data.MemDb
import com.dollhouse.app.data.MemStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.chat.ChatPanel
import com.dollhouse.app.ui.chat.ChatSessions
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】上下文总结与压缩：把最老的一批消息交给模型压成要点，原文真删除。
 *
 * 【入口】ChatHistoryStore.push 末尾的 maybeAuto（自动档）；加号面板「记忆」页的「立即总结」（手动档）。
 *
 * 【交互】摘要写入 MemStore（键 mem_list），会话历史由持有者（ChatPanel / PetTalk）的 history 字段承载；
 *        压缩后在历史头部插入一条 role=system / kind=summary 的消息，发送时随上下文一起带上。
 *
 * 【扩展】想改成「只压请求不删原文」只需在 apply() 里保留原件、另存一份摘要。
 *
 * 【坑】① 真删除不可恢复，所以只在模型成功返回摘要后才动原文；
 *        ② 必须有全局重入锁，否则连发两条消息会并发触发两次压缩；
 *        ③ 头部那条 system 摘要不能被 saveHistory 的「只留最近 60 条」截掉，见 ChatHistoryStore.saveHistory。
 *
 * 【v0.0.1】流程不再直连 ChatPanel，改为面向最小宿主面 Sink：
 *         · 全屏聊天页 → of(host)，逐条等价于改造前的 host 直连；
 *         · 人偶迷你框 → PetTalk 提供纯数据面、UI 面一律 no-op，
 *           于是「迷你框不触发自动压缩」这条限制被消掉，两条入口共用同一份压缩逻辑（不分叉）。
 */
object MemSummarizer {
    /** 全局重入锁：同一时间只允许一次压缩。 */
    private val RUNNING = AtomicBoolean(false)

    /** 压缩后保留的最近消息条数。 */
    private const val KEEP_TAIL = 8

    /** 单次最多压缩的消息条数（控制总结请求本身的长度）。 */
    private const val MAX_BATCH = 40

    /** 上下文占用超过该比例也触发自动总结（与条数阈值二者取一）。 */
    private const val CTX_TRIGGER_RATIO = 0.6f

    /**
     * 【v2.9.5】看门狗超时（毫秒）：比网络层的读超时（120s）再宽一点。
     * 【为什么需要】ColorOS 会冻结后台进程（logcat 实测 OplusHansManager freeze com.dollhouse.app），
     *   被冻结时工作线程连同网络请求一起挂起，既不返回也不抛异常，回调永远不到；
     *   而 RUNNING 锁与「记忆总结中」只在回调里复位 → 界面永久卡在「记忆总结中」。
     *   到点主动兜底复位，让用户至少能再次点击，而不是被一个幽灵锁永久挡住。
     */
    private const val WATCHDOG_MS = 150000L

    /** 本次在途请求的看门狗；新请求发起前先撤旧的，避免误复位新一轮。 */
    private var watchdog: Runnable? = null

    /**
     * 归档提示词。
     * 【结构】固定四段，避免模型每次自由发挥导致要点丢失。
     * 【合并】材料里可能带上一次的归档（见 transcript），必须并进去而不是覆盖。
     */
    private const val SUMMARY_PROMPT = "你在帮一段 GalGame 对话做归档。把下面这段对话（可能包含【更早的归档】）" +
            "合并压缩成一份要点，分段写清：①主人对鲸鱼娘的称呼与偏好；②两人之间的约定；" +
            "③重要事件；④关系与情绪的变化。" +
            "【合并】若材料里有【更早的归档】，必须把其中仍然有效的要点并进新摘要，不要让旧信息消失。" +
            "用中文，不超过 300 字，不要 Markdown 标题符号，不要列条目符号，不要评论，直接输出要点正文。"

    /**
     * 【v0.0.1】总结流程的最小宿主面。
     * 【为什么】两池架构下「迷你框（PetTalk）」没有 ChatPanel，无法直接复用全屏页那条链；
     *   把依赖收敛成这一层（少量数据面 + 可降级的表现面），两条入口就能共用同一份压缩逻辑。
     * 【活引用】history()/setHistory() 必须读写持有者**当前的**字段，不能在调用时把 JSONArray
     *   捕获成值 —— 迷你框每轮 reloadHistory 会重新 new 数组，捕获的旧引用会把结果写进孤儿数组。
     */
    interface Sink {
        fun ctx(): Context

        /** 本次压缩锚定的会话 id（回调到达时要核对它仍是该会话所属池的当前会话）。 */
        fun convId(): String
        fun history(): JSONArray
        fun setHistory(h: JSONArray)
        fun ctxUsedChars(): Int

        /** 收回展开态并刷新；无展开态概念的入口直接返回 false。 */
        fun foldPrev(): Boolean

        /** 丢弃展开态切点（成功替换历史前调用）。 */
        fun clearPrev()
        fun flash(msg: String)
        fun busy(busy: Boolean)

        /** 重铺视图并收尾；after 允许为 null。 */
        fun uiRefresh(after: Runnable?)
        fun post(r: Runnable)
        fun postDelayed(r: Runnable, ms: Long)
        fun removeCallbacks(r: Runnable)
    }

    /** 全屏聊天页适配器：逐条等价于改造前的 host 直连。 */
    @JvmStatic
    fun of(host: ChatPanel): Sink {
        return object : Sink {
            override fun ctx(): Context {
                return host.context
            }

            override fun convId(): String {
                return ChatSessions.currentId(host.context)
            }

            override fun history(): JSONArray {
                return host.history
            }

            override fun setHistory(h: JSONArray) {
                host.history = h
            }

            override fun ctxUsedChars(): Int {
                return host.ctxUsedChars()
            }

            override fun foldPrev(): Boolean {
                if (host.prevCount <= 0) {
                    return false
                }
                // 【v2.9.5】自动档同样先收回展开态：补回批是临时回看，不该被算作待压缩的正文。
                val folded = ChatHistoryStore.collapsePrev(host, host.prevCount)
                Logs.i("DollhouseMemo", "[maybeAuto] 收回展开态 ok=" + folded
                        + " prevCount=" + host.prevCount)
                host.prevCount = 0
                if (folded) {
                    host.reloadHistory()
                }
                return folded
            }

            override fun clearPrev() {
                host.prevCount = 0
            }

            override fun flash(msg: String) {
                host.flashMemo(msg)
            }

            override fun busy(busy: Boolean) {
                host.setMemoBusy(busy)
            }

            override fun uiRefresh(after: Runnable?) {
                host.post {
                    host.reloadHistory()
                    host.refreshCtxRing()
                    // 【v2.8】重铺完把视图落到「ⓘ 历史对话摘要」那条分割线，让「已收起」一眼可见。
                    host.scrollToSummary()
                    if (after != null) {
                        after.run()
                    }
                }
            }

            override fun post(r: Runnable) {
                host.post(r)
            }

            override fun postDelayed(r: Runnable, ms: Long) {
                host.postDelayed(r, ms)
            }

            override fun removeCallbacks(r: Runnable) {
                host.removeCallbacks(r)
            }
        }
    }

    /** 【v0.0.1】与 ChatPanel.ctxUsedChars 同口径的无 UI 版（迷你框旁路的 byRatio 触发要用）。 */
    @JvmStatic
    fun ctxUsedChars(ctx: Context, history: JSONArray?): Int {
        var n = PetPrefs.SYSTEM_PROMPT.length
        n += MemDb.recentText(ctx, MemDb.MAX_INJECT_CHARS).length
        if (history != null) {
            for (i in 0 until history.length()) {
                val o = history.optJSONObject(i)
                if (o != null) {
                    n += o.optString("content", "").length
                }
            }
        }
        return n
    }

    /** 【v2.9.1】深拷贝一份历史作快照：之后持有者的 history 即使被整段替换，快照也不受影响。 */
    private fun copyOf(src: JSONArray?): JSONArray {
        val out = JSONArray()
        if (src == null) {
            return out
        }
        for (i in 0 until src.length()) {
            val o = src.optJSONObject(i) ?: continue
            try {
                out.put(JSONObject(o.toString()))
            } catch (unused: Throwable) {
            }
        }
        return out
    }

    /**
     * 【v2.9.1】当前历史开头那 count 条是否仍与快照逐条相同（只比 role / content）。
     * 【为什么不用引用比较】请求在途时用户常会再发消息，history 会被整段替换；
     *   引用比较会把这当成「历史变了」而丢弃整次总结，表现就是「点了没反应」。
     *   逐条比对则能识别出「前面那批没动、只是尾部多了新消息」，从而正常完成总结。
     */
    private fun prefixMatches(cur: JSONArray?, snap: JSONArray?, count: Int): Boolean {
        if (cur == null || snap == null || count <= 0
                || cur.length() < count || snap.length() < count) {
            return false
        }
        for (i in 0 until count) {
            val a = cur.optJSONObject(i)
            val b = snap.optJSONObject(i)
            if (a == null || b == null) {
                return false
            }
            if (a.optString("role") != b.optString("role")
                    || a.optString("content") != b.optString("content")) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun isRunning(): Boolean {
        return RUNNING.get()
    }

    /**
     * 【兼容】全屏聊天页入口：旧签名保持不变，ChatHistoryStore.push 与 ChatPanel 无需改动。
     * 【坑】由 ChatHistoryStore.push 调用，即在主线程、且已落盘之后。
     */
    @JvmStatic
    fun maybeAuto(host: ChatPanel?) {
        if (host == null) {
            return
        }
        maybeAuto(of(host))
    }

    /**
     * 自动档：开了自动总结且当前会话条数到达阈值时，异步压缩最老的一批。
     * 【v0.0.1】无 UI 版入口：迷你框（PetTalk）旁路用它，于是「迷你框不触发自动压缩」被消掉。
     */
    @JvmStatic
    fun maybeAuto(sink: Sink?) {
        if (sink == null) {
            return
        }
        val ctx = sink.ctx()
        if (!PetPrefs.memAuto(ctx)) {
            return
        }
        // 【v2.9.5】自动档同样先收回展开态：补回批是临时回看，不该被算作待压缩的正文。
        //  无 UI 入口无展开态概念，由该入口自行 no-op。
        sink.foldPrev()
        val len = sink.history().length()
        val count = Math.min(MAX_BATCH, len - KEEP_TAIL)
        // 【双触发】条数到档位，或上下文占用到线，任一命中就先一步压缩。
        // 占用触发时门槛放宽到 2 条：占用高不一定条数多（单条超长消息也算）。
        val byCount = len >= PetPrefs.memThreshold(ctx)
        val byRatio = sink.ctxUsedChars() >= (PetPrefs.ctxWindow(ctx) * CTX_TRIGGER_RATIO).toInt()
        if (!byCount && !byRatio) {
            return
        }
        val minCount = if (byRatio) 2 else 6
        if (count < minCount) {
            return
        }
        summarize(sink, count, null)
    }

    /** 【兼容】手动档旧签名：全屏聊天页「立即总结」用。 */
    @JvmStatic
    fun summarizeNow(host: ChatPanel?, done: Runnable?) {
        if (host == null) {
            return
        }
        summarizeNow(of(host), done)
    }

    /**
     * 手动档：点了就总结，不设条数门槛。
     * 【v2.9.3】改「全折」：用户点「立即总结」时，当前会话里主人发的与小肥鱼回的
     *          **全部**内容都并进摘要（原先只折最老的一批、把最近 KEEP_TAIL 条留在聊天里）。
     *          原文全部进 conv_prev 暂存位，仍可点顶部「更早的历史」入口回看，一条不丢。
     * 【上限】手动档不再套 MAX_BATCH：用户要的是「都算」，就不该有静默截断。
     */
    @JvmStatic
    fun summarizeNow(sink: Sink?, done: Runnable?) {
        if (sink == null) {
            return
        }
        val len = sink.history().length()
        val count = len
        Logs.i("DollhouseMemo", "[summarizeNow] len=" + len + " count=" + count)
        if (count < 1) {
            Logs.i("DollhouseMemo", "[summarizeNow] 中止: 历史为空")
            // 【v2.9.1】不再静默：全工程禁用 Toast，不给反馈用户只会以为点了没反应。
            sink.flash("对话太短，暂时没什么可总结的")
            if (done != null) {
                done.run()
            }
            return
        }
        summarize(sink, count, done)
    }

    private fun summarize(sink: Sink, count: Int, done: Runnable?) {
        val ctx = sink.ctx().applicationContext
        Logs.i("DollhouseMemo", "[summarize] 进入 count=" + count
                + " histLen=" + sink.history().length() + " hasKey=" + PetPrefs.hasKey(ctx))
        if (!PetPrefs.hasKey(ctx)) {
            Logs.i("DollhouseMemo", "[summarize] 中止: 未填 API Key")
            // 【v2.9.1】原先这里静默返回，用户完全不知道是「没填 API Key」还是「功能坏了」。
            sink.flash("还没填 API Key")
            if (done != null) {
                done.run()
            }
            return
        }
        // 【快照】发起请求前把「这次压的是哪个会话、压的是哪份数组」定下来。
        // 回调是异步的，期间用户完全可能切了会话或清空历史；到那时再读
        // ChatSessions.currentId / 持有者的 history 已经不是同一份数据了。
        val convId = sink.convId()
        // 【v2.9.1】快照改成深拷贝：请求在途期间 history 会被整段替换
        //（发消息 → push → saveHistory → trimForSave 重新赋值），
        // 若快照就是那个引用，之后连对比基准都被改掉了。
        val snapshot = copyOf(sink.history())
        val transcriptText = transcript(snapshot, count)
        Logs.i("DollhouseMemo", "[summarize] snapshotLen=" + snapshot.length()
                + " transcriptLen=" + transcriptText.length + " convId=" + convId)
        if (transcriptText.trim().isEmpty()) {
            Logs.i("DollhouseMemo", "[summarize] 中止: transcript 为空")
            sink.flash("没有可总结的内容")
            if (done != null) {
                done.run()
            }
            return
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Logs.i("DollhouseMemo", "[summarize] 中止: RUNNING 锁被占用")
            sink.flash("上一次总结还在进行中")
            if (done != null) {
                done.run()
            }
            return
        }
        // 【v2.8】真开始跑了才亮「记忆总结中」（上面的提前退出分支都不亮，避免闪现）。
        // 【v2.9.1·P2】这一步必须自己兜异常：它夹在 CAS 与内层 try 之间，
        //  一旦抛（面板已 detach 等），RUNNING 会永久卡 true，
        //  之后每次手动/自动总结都被 isRunning 静默挡掉 —— 等于总结功能永久失效。
        try {
            sink.busy(true)
            Logs.i("DollhouseMemo", "[summarize] 已进入总结中状态，发起请求")
        } catch (unused: Throwable) {
            Logs.i("DollhouseMemo", "[summarize] 中止: setMemoBusy 抛异常")
            RUNNING.set(false)
            if (done != null) {
                done.run()
            }
            return
        }
        val messages = buildSummaryMessages(transcriptText)
        try {
            // 【v2.9.6】总结的输出预算：显式给 4096，效果等同「不限制」。
            //  【为什么不再传 -1】不下发 max_tokens 时，部分服务端/模型会走异常分支
            //    直接吐出空 content，用户那边看到的还是老报错「模型返回了空内容」。
            //    给一个远超 300 字中文摘要所需的显式值，既够用又不依赖服务端默认行为。
            DeepSeekClient.chat(ProviderStore.activeProvider(ctx), PetPrefs.apiKey(ctx),
                    PetPrefs.baseUrl(ctx), PetPrefs.model(ctx), messages, 4096, DeepSeekClient.Callback { str, str2 ->
                        handleSummarizeResult(sink, ctx, convId, snapshot, count, done, str, str2)
                    })
            // 【v2.9.5】请求已发出，挂看门狗：进程被系统冻结导致回调永不到达时，
            //  到点主动复位，避免「记忆总结中」永久卡在工具条上（真机报障）。
            armWatchdog(sink)
        } catch (t: Throwable) {
            Logs.i("DollhouseMemo", "[summarize] 同步异常: " + t.javaClass.simpleName)
            // 【坑】RUNNING 一旦置位，只有回调里的 finally 会清。若 chat() 在启动线程前
            // 就同步抛了（参数求值、start() 失败），这个锁会永久卡住，自动总结从此静默失效。
            // 【v2.8】同步抛异常时回调不会来，「记忆总结中」必须在这里撤掉（放在 done 之前，防回调抛异常漏撤）。
            // 【v2.9.2·P2-3】先给可见提示再撤忙碌态：否则 chat() 在启动线程前同步抛时用户零反馈。
            RUNNING.set(false)
            sink.flash("总结启动失败，稍后再试")
            sink.busy(false)
            if (done != null) {
                done.run()
            }
        }
    }

    /** 构造总结请求的 messages：system 放归档提示词，user 放待归档的对话转录。 */
    private fun buildSummaryMessages(transcript: String): JSONArray {
        val messages = JSONArray()
        try {
            val sys = JSONObject()
            sys.put("role", "system")
            sys.put("content", SUMMARY_PROMPT)
            messages.put(sys)
            val usr = JSONObject()
            usr.put("role", "user")
            usr.put("content", transcript)
            messages.put(usr)
        } catch (unused: Throwable) {
        }
        return messages
    }

    /**
     * 总结请求的回调处理：成功校验、会话/前缀两道守卫、落盘与收尾。
     * 拆出来只是让 summarize 的「同步发起」与「异步回调」分开读，
     * 判定顺序、提示文案、副作用与原先的匿名 Callback 完全一致。
     */
    private fun handleSummarizeResult(sink: Sink, ctx: Context, convId: String,
                                      snapshot: JSONArray, count: Int, done: Runnable?,
                                      str: String?, str2: String?) {
        Logs.i("DollhouseMemo", "[callback] 到达 ok=" + (str != null && !str.trim().isEmpty())
                + " okLen=" + (if (str == null) -1 else str.length)
                + " err=" + (if (str2 == null || str2.trim().isEmpty()) "无" else "有"))
        try {
            if (str == null || str.trim().isEmpty()) {
                // 【v2.9.1·P0】失败必须可见：原先这里直接 return，把 str2 的错误原因
                //  整个丢掉（覆盖所有网络/HTTP 错误与「HTTP 200 但 content 为空」），
                //  而全工程禁用 Toast，用户只看到「记忆总结中」闪一下 —— 就是「点了没反应」。
                Logs.i("DollhouseMemo", "[callback] 失败分支: 无有效内容")
                sink.flash(if (str2 == null || str2.trim().isEmpty())
                    "总结没成功，稍后再试" else "总结没成功：" + str2)
                return
            }
            // 【守卫①·v0.0.1 修正】两池拆分后 currentId(ctx) 只代表「当前池」的当前会话：
            //  人偶池的会话在「当前池是 self」时会被误判成「已切换」；反过来若用
            //  currentId 当锚点，人偶池压缩还会把摘要/暂存写到 self 池名下（静默串池）。
            //  改为「锚定会话是否仍是它所属池的当前会话」，且不触发 ensure（无副作用）；
            //  锚定会话被删时 currentOfPool 返回 null，比较不等 → 仍然放弃。
            if (convId != ChatSessions.currentOfPool(ctx, convId)) {
                // 【v2.9.2】换会话也要说一声，否则整条链路又一次「点了没反应」。
                Logs.i("DollhouseMemo", "[callback] 放弃: 会话已切换")
                sink.flash("已切换对话，这次总结跳过")
                return
            }
            // 【守卫②·v2.9.1 修正】不再用「引用相等」判快照失效。
            //  请求在途期间用户很可能又发了一条消息，saveHistory → trimForSave 会把
            //  history 换成新数组（内容 = 原来那批 + 新增），引用比较必然不等，
            //  整次总结被静默丢弃 —— 真机上就是「点了没反应」。
            //  改成「前缀逐条比对」：只要当前历史开头那 count 条仍是当初那批，
            //  就在当前历史上做替换，期间新增的消息原样留在尾部。
            if (!prefixMatches(sink.history(), snapshot, count)) {
                Logs.i("DollhouseMemo", "[callback] 放弃: 前缀不匹配 curLen="
                        + sink.history().length() + " snapLen=" + snapshot.length() + " count=" + count)
                sink.flash("对话已变化，这次总结跳过")
                return
            }
            applySummary(ctx, sink, convId, str.trim(), count, snapshot, done)
        } catch (unused: Throwable) {
            Logs.i("DollhouseMemo", "[callback] 成功块内异常: " + unused.javaClass.simpleName)
            // 【v2.9.3·P1】成功块内的落盘/构造异常也必须说一声：否则「记忆总结中」亮一下就没，
            //  用户看到的仍是「点了没反应」。flashHold 保证随后的 finally 不会同帧隐藏它。
            sink.flash("总结写入失败，稍后再试")
            if (done != null) {
                done.run()
            }
        } finally {
            // 【v2.9.5】回调真正到达：撤掉看门狗，正常收尾。
            cancelWatchdog(sink)
            RUNNING.set(false)
            // 【v2.8】无论成败都要撤掉「记忆总结中」，否则提示会永久挂在工具条上。
            sink.busy(false)
        }
    }

    /** 把一段转录压成摘要：写记忆库、暂存原文、按当前历史重建并落盘、给出可见提示。 */
    private fun applySummary(ctx: Context, sink: Sink, convId: String,
                             summary: String, count: Int, snapshot: JSONArray,
                             done: Runnable?) {
        Logs.i("DollhouseMemo", "[callback] 成功: summaryLen=" + summary.length
                + " count=" + count + " curLen=" + sink.history().length())
        MemStore.add(ctx, convId, summary, count)
        // 【v2.8】总结会整体替换 history，展开态的切点（头部摘要数 + count）随之失效：
        //         撤销展开态即可，补回的消息该被压缩的进摘要、剩下的仍在尾部，一条不丢。
        sink.clearPrev()
        // 【v2.9】真删除前先把原文存进「更早历史」暂存位：
        //   摘要给的是要点（长期记忆），原文仍可点顶部入口回看，
        //   避免「聊着聊着早期原话就再也找不回来」（真机反馈）。
        ChatHistoryStore.pushPrevTail(ctx, convId, snapshot, count)
        // 真删除：摘掉被压掉的那批，头部换成一条摘要消息。
        val arr = JSONArray()
        val head = JSONObject()
        head.put("role", "system")
        head.put("kind", "summary")
        head.put("content", "【早期对话要点】" + summary)
        arr.put(head)
        // 【v2.9.1】按**当前**历史重建（而非快照）：期间新增的消息必须留下。
        val cur = sink.history()
        for (i in count until cur.length()) {
            arr.put(cur.opt(i))
        }
        sink.setHistory(arr)
        // 【坑】落盘必须用显式 id 版：守卫①已确认 convId 仍是它所属池的当前会话，
        //   与 saveHistory(host) 内部取 currentId 的结果一致，但这里不再依赖全局指针。
        ChatHistoryStore.saveHistory(ctx, arr, convId)
        // 【v2.9.4】成功路径必须可见：原先成功时聊天区只是无声地重铺，
        //  用户无法区分「压好了」与「什么都没发生」，正是「总结没成功」报障的来源。
        sink.flash("已把 " + count + " 条压成摘要")
        Logs.i("DollhouseMemo", "[callback] 已落盘 newHistLen=" + sink.history().length())
        val after = done
        sink.uiRefresh(Runnable {
            after?.run()
        })
    }

    /** 【v2.9.5】挂看门狗：到点仍未见回调就主动复位状态并给可见提示。 */
    private fun armWatchdog(sink: Sink) {
        cancelWatchdog(sink)
        val w = Runnable {
            watchdog = null
            // 只有仍处于「在途」才兜底：正常回调早已把 RUNNING 清掉。
            if (!RUNNING.compareAndSet(true, false)) {
                return@Runnable
            }
            Logs.i("DollhouseMemo", "[watchdog] 超时兜底: 回调未到达，主动复位")
            sink.post(Runnable {
                sink.flash("总结超时了，稍后再试")
                sink.busy(false)
            })
        }
        watchdog = w
        sink.postDelayed(w, WATCHDOG_MS)
    }

    /** 【v2.9.5】撤掉看门狗（回调到达或新一轮开始时调用）。 */
    private fun cancelWatchdog(sink: Sink?) {
        val w = watchdog
        if (w != null) {
            watchdog = null
            if (sink != null) {
                sink.removeCallbacks(w)
            }
        }
    }

    /**
     * 把最老的前 count 条拼成一段可读的对话记录。
     * 【坑】头部那条 kind=summary 是上一次压缩的产物，必须把它的正文带进来：
     *      它和这段消息一起被「真删除」，跳过就等于把这批归档整段丢掉，
     *      而新的摘要只覆盖后面那批 —— 摘要会一路变短，最后什么都没有。
     *      其余 role=system 的消息是提示词/记忆注入，不属于对话，照旧跳过。
     */
    @JvmStatic
    fun transcript(history: JSONArray, count: Int): String {
        val sb = StringBuilder()
        for (i in 0 until Math.min(count, history.length())) {
            val o = history.optJSONObject(i) ?: continue
            if ("summary" == o.optString("kind")) {
                sb.append("【更早的归档】")
                sb.append(o.optString("content", ""))
                sb.append('\n')
                continue
            }
            // 【v2.9.5·第二道防线】展开态的补回批（带 _prev 标记）不属于要总结的内容：
            //  它们的要点早已进过摘要，重复喂给模型等于把旧内容再总结一遍。
            //  正常情况下 summarize 之前已收回展开态，这里只兜底结构异常的场景。
            if (ChatHistoryStore.isPrevItem(o)) {
                continue
            }
            val role = o.optString("role")
            if ("system" == role) {
                continue
            }
            sb.append(if ("user" == role) "主人：" else "小肥鱼：")
            sb.append(o.optString("content", ""))
            sb.append('\n')
        }
        return sb.toString()
    }
}
