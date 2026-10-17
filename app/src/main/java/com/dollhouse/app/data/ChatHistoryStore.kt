package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.agent.ChatToolRegistry
import com.dollhouse.app.ai.MemExtractor
import com.dollhouse.app.ai.MemSearch
import com.dollhouse.app.ai.ThinkLevel
import com.dollhouse.app.ai.VisionAnalyzer
import com.dollhouse.app.core.ImageEncoder
import com.dollhouse.app.core.Logs
import com.dollhouse.app.device.LamdaManager
import com.dollhouse.app.device.OcrEngine
import com.dollhouse.app.ui.chat.ChatPanel
import com.dollhouse.app.ui.chat.ChatSessions
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】聊天历史的读写与请求体组装：本地档位读写、追加消息、构建上下文请求。
 *
 * 【入口】ChatPanel（构造、发送、重生成、点赞）与 PetTalk（迷你聊天的收发与落档）。
 *
 * 【交互】历史数组仍由 host 持有（host.history），本类只读写不持有；持久化全走 PetPrefs；图片附件由 OcrEngine 扫成文字后并入正文。
 *
 * 【扩展】新增请求字段（模型参数、图片策略）只改 buildRequest；历史上限在 saveHistory 收口。
 *
 * 【坑】history 每次请求都会整段带上，改这里会直接影响 token 消耗。
 */
object ChatHistoryStore {
    /**
     * 【历史残渣剥离】剥掉老记录里的好感度记账（[好感度:+3] / 【好感度：-2】）。
     * 【为什么还要它】好感度系统已整体删除，但磁盘上老对话的首行仍留着那一行；
     *   显示时剥掉即可、不回写磁盘 —— 老记录保持原貌，用户也不会再看到已废弃的记账。
     * 【谁用】ChatPanel.handleReply 与 PetTalk.finish（全屏页 / 迷你框两个入口共用这一份）。
     */
    private val LEGACY_AFFECTION: java.util.regex.Pattern =
        java.util.regex.Pattern.compile("[\\[【]\\s*好感度\\s*[:：]\\s*[+-]?\\d+\\s*[\\]】]")

    @JvmStatic
    fun stripLegacyAffection(s: String?): String {
        return if (s == null) "" else LEGACY_AFFECTION.matcher(s).replaceAll("").trim()
    }

    /**
     * 【r13·角色设定】按这只人偶的「输出替换」规则清洗文本。
     *
     * 【为什么放在数据层】清洗有两个时机、两处调用点：
     *   · **落盘前**（`visual=false`）：把「影响历史」的规则应用到写库的文本上，
     *     模型下一轮也就看不到被替换掉的内容；
     *   · **渲染时**（`visual=true`）：只改显示，历史原文保留。
     *   两条路必须用同一套实现，否则「同一句话在气泡里和存档里不一样」这种诡异差异会冒出来。
     *
     * 【性能】没配规则时直接返回原串（`spec.rules` 为空即短路），
     *   不给每条历史消息都做一次 JSON 解析 —— 长列表滚动时才不会卡。
     *
     * @param forHer true = 她说的话
     */
    @JvmStatic
    fun stripRules(ctx: Context, dollId: String?, text: String?, forHer: Boolean, visual: Boolean): String {
        val t = text ?: ""
        if (t.isEmpty()) {
            return t
        }
        try {
            val spec = DollProfile.spec(ctx, dollId)
            if (spec.rules.isEmpty()) {
                return t
            }
            return PersonaBuilder.applyRules(spec, t, visual, forHer, !forHer)
        } catch (ignored: Throwable) {
            // 清洗失败按原样返回：宁可多显示一点，也不能让她的话整段消失。
            return t
        }
    }

    // 从偏好恢复「当前会话」的对话历史。
    // 【坑】多会话改造后不再读 chat_history，改读 conv_<当前会话 id>；
    //        ChatSessions.ensure 负责旧档迁移与兜底建会话。
    @JvmStatic
    fun loadHistory(host: ChatPanel) {
        val ctx = host.getContext()
        val id = ChatSessions.ensure(ctx)
        try {
            host.history = JSONArray(PetPrefs.convHistory(ctx, id))
        } catch (_: Throwable) {
            host.history = JSONArray()
        }
        // 【v2.8】换会话（或任何从偏好重载）时清掉「更早历史」的展开态：
        // 展开态只对当前这份 history 有效，留着会在新会话里误判补回批区间。
        host.prevCount = 0
        // 【v2.8·N1】盘上若还留着展开态（带 _prev 标记的补回批，来自异常退出 / 旁路入口写入），
        // 在这里就地收回：批次退回暂存位（入口重新可用、数据一条不丢），标记同时被消费掉。
        // 这样「展开态」永远不会跨载入存活，标记也不可能残留到下一次展开去污染切点扫描。
        collapsePrevOnLoad(ctx, id, host.history)
    }

    /**
     * 【v2.8·N1】载入时就地收回盘上残留的展开态。
     * 【为什么不在落盘时剥标记】迷你框（PetTalk）那条路读的是 prefs 里的这份数组，
     *   它需要靠标记判断「现在正处于展开态、别把补回批重新回填进暂存位」；
     *   标记必须落盘，所以改在「载入」这个唯一的入口处消费它。
     * 【失败兜底】结构对不上就交给 fallbackOnLoad：剥标记 + 落盘，
     *   并在暂存位重复时去重。宁可这次展开退化成收缩态（入口仍可点），
     *   也不能让残留标记活到下一次展开、把 collapsePrev 的连续扫描带过界。
     */
    @JvmStatic
    fun collapsePrevOnLoad(ctx: Context, id: String, history: JSONArray?) {
        if (history == null) {
            return
        }
        var from = 0
        while (from < history.length() && isSummary(history.optJSONObject(from))) {
            from++
        }
        var to = from
        while (to < history.length() && isPrevItem(history.optJSONObject(to))) {
            to++
        }
        // 【前置】补回批之后必须还有正文，且暂存位是空的：
        //   非空说明结构已被别的路径改过，别硬插；摘空则入口会消失，宁可不动。
        if (to <= from || to >= history.length() || !convPrevEmpty(ctx, id)) {
            fallbackOnLoad(ctx, id, history, from, to)
            return
        }
        // 【v2.8·P3-⑥】先整批克隆、且要求一条不落地成功，才动手删正文。
        //  旧写法是「克隆失败的条跳过、但整段照删」，那一条会同时从正文和暂存位消失。
        //  现在改成原子式：凑不齐就整体放弃，交给 fallbackOnLoad 剥标记后原样保留。
        val batch = JSONArray()
        for (i in from until to) {
            val o = history.optJSONObject(i)
            if (o == null) {
                continue
            }
            try {
                val c = JSONObject(o.toString())
                c.remove("_prev")
                batch.put(c)
            } catch (_: Throwable) {
            }
        }
        if (batch.length() != to - from) {
            // 【v2.8·P3-④】兜底分支同样要落盘：只改内存会让盘上标记残留，
            //  下次载入又走一遍，等于每次都白跑一趟。
            fallbackOnLoad(ctx, id, history, from, to)
            return
        }
        // 【原地】从后往前删，避免下标漂移。JSONArray.remove(int) 需 API 19+。
        for (i in to - 1 downTo from) {
            history.remove(i)
        }
        try {
            PetPrefs.setConvPrev(ctx, id, batch.toString())
            PetPrefs.setConvHistory(ctx, id, byPoolSafe(ctx, id, history).toString())
        } catch (_: Throwable) {
        }
    }

    /**
     * 【v2.8·P2/P3】降级处理：结构对不上时不强收回，但也不能只改内存。
     *  · 必须落盘（P2）：否则盘上留着 _prev、内存已无标记，
     *    之后任何一次普通保存都会因 prevN 归零而 convPrev 非空 → 丢中段。
     *  · 崩溃窗口去重（P3）：collapsePrev 先写暂存位、后写正文，
     *    两次 apply 之间进程死亡会让同一批同时留在两处；
     *    逐条相同则正文是唯一副本，清掉暂存位。
     */
    private fun fallbackOnLoad(ctx: Context, id: String, history: JSONArray, from: Int, to: Int) {
        if (to > from && !convPrevEmpty(ctx, id)) {
            try {
                val onDisk = JSONArray(PetPrefs.convPrev(ctx, id))
                val n = to - from
                // 【v2.8·P2-②】不要求等长：崩溃窗口里暂存位可能是「批 + 中段」超集。
                //  只要正文里这批逐条与暂存位前 n 条相同，就以暂存位为唯一权威副本，
                //  把正文这批摘掉（数据一条不丢，点入口时会按原顺序一次性补回）。
                var prefixMatch = onDisk.length() >= n && n > 0
                var i = 0
                while (prefixMatch && i < n) {
                    val oa = history.optJSONObject(from + i)
                    val ob = onDisk.optJSONObject(i)
                    if (oa == null || ob == null || !sameBody(oa, ob)) {
                        prefixMatch = false
                    }
                    i++
                }
                if (prefixMatch) {
                    for (j in to - 1 downTo from) {
                        history.remove(j)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        stripPrevTags(history)
        try {
            PetPrefs.setConvHistory(ctx, id, byPoolSafe(ctx, id, history).toString())
        } catch (_: Throwable) {
        }
    }

    /** 【v2.8·P3】比对两条消息的业务字段是否一致（忽略 _prev 标记）。 */
    private fun sameBody(a: JSONObject?, b: JSONObject?): Boolean {
        if (a == null || b == null) {
            return false
        }
        try {
            // 【v2.8·P3-①】显式比业务字段，不依赖 JSONObject.toString() 的键序。
            if (a.optString("role") != b.optString("role")) {
                return false
            }
            if (a.optString("content") != b.optString("content")) {
                return false
            }
            if (a.optInt("rating", 0) != b.optInt("rating", 0)) {
                return false
            }
            val ia = a.optString("image", null)
            val ib = b.optString("image", null)
            if (if (ia == null) ib != null else ia != ib) {
                return false
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /** 【v2.8·N1】暂存位是否为空（读失败按空处理，与 trimForSave 的既有判据一致）。 */
    private fun convPrevEmpty(ctx: Context, id: String): Boolean {
        return try {
            JSONArray(PetPrefs.convPrev(ctx, id)).length() == 0
        } catch (_: Throwable) {
            true
        }
    }

    /** 【v2.8·N1】是否为历史头部那种摘要消息。 */
    private fun isSummary(o: JSONObject?): Boolean {
        return o != null && "summary" == o.optString("kind")
    }

    /**
     * 裁剪历史（头部摘要无条件保留 + 最近 60 条），返回新数组。
     * 【副作用】把本次被裁掉的那一批非摘要消息存进 conv_<id>_prev，
     *          供聊天记录顶部「点击加载更早的历史记录」一次性补回。
     *          【只填一次】已有暂存就不再覆盖，保证「只能加载到一次」的语义。
     * 【v2.8】展开态（带着 _prev 标记的补回批）与头部摘要一样无条件保留：
     *          这批此刻既不在暂存位（已被 prependPrev 清空）也不该被裁，
     *          一旦被裁就是真丢消息（内存里还有，落盘的没了）。
     */
    @JvmStatic
    fun trimForSave(ctx: Context, id: String, history: JSONArray): JSONArray {
        val keep = 60
        val len = history.length()
        // 【v2.8】先把头部摘要 + 紧随其后的「更早历史」补回批整体摘出来，再做裁剪：
        //  摘要被「只留最近 60 条」截掉等于压缩白做；补回批此刻既不在暂存位也不该被裁，
        //  一旦被裁就是真丢消息（内存里还有、落盘的没了）。
        val head = JSONArray()
        var bodyFrom = 0
        while (bodyFrom < len) {
            val o = history.optJSONObject(bodyFrom)
            if (o == null || "summary" != o.optString("kind")) {
                break
            }
            head.put(o)
            bodyFrom++
        }
        var prevN = 0
        while (bodyFrom + prevN < len && isPrevItem(history.optJSONObject(bodyFrom + prevN))) {
            prevN++
        }
        val bodyAt = bodyFrom + prevN
        val tail = Math.max(bodyAt, len - keep)
        // 【v2.8·P1】把「补回批（若有）+ 本次被裁的中段」一起退回暂存位。
        //  为什么合并：这两段在原时间轴上本就是连续的「更早历史」，合成一批后点一次
        //  「加载更早」会把它们按原顺序一次性补回，语义与「只加载一次」一致。
        //  为什么必须退回：迷你框（PetTalk）走独立 saveHistory，它不知道此刻处于展开态；
        //  若把补回批留在落盘结果里，下一次普通裁剪就会把它连同中段一起丢掉
        //  （既不进暂存位也不写盘）——那是真丢数据。
        //  【只填一次】暂存位已有内容就不覆盖：全屏页在展开态会直接短路不走这里，
        //  所以此处遇到非空只能是崩溃窗口残留，保守放弃写回，避免同一批在正文与暂存位各存一份。
        if (tail > bodyFrom) {
            try {
                // 【v2.8·P2-①】把本次被裁的整段（含补回批）**追加**进暂存位，绝不丢弃。
                //  旧写法是「暂存位非空就跳过写回」，但那次裁剪照常执行 ——
                //  被裁的段既不进暂存位也不写盘，等于每发一条消息就永久丢一条最早的
                //  （真机上表现为「之前能看回来的消息，过一会儿点开就只剩最近几条了」）。
                //  追加而不是覆盖：暂存位里都是「还没补回过的更早消息」，本来就排在
                //  本次被裁段之前，直接接在后面时间顺序依然正确，且「只加载一次」的
                //  语义不受影响（补回仍是整批一次性插回、随即清空暂存位）。
                var prev: JSONArray
                try {
                    prev = JSONArray(PetPrefs.convPrev(ctx, id))
                } catch (_: Throwable) {
                    prev = JSONArray()
                }
                for (i in bodyFrom until tail) {
                    val o = history.optJSONObject(i)
                    if (o == null || "summary" == o.optString("kind")) {
                        continue
                    }
                    try {
                        val c = JSONObject(o.toString())
                        c.remove("_prev")
                        prev.put(c)
                    } catch (_: Throwable) {
                    }
                }
                if (prev.length() > 0) {
                    PetPrefs.setConvPrev(ctx, id, prev.toString())
                }
            } catch (_: Throwable) {
            }
        }
        val out = JSONArray()
        for (i in 0 until head.length()) {
            out.put(head.opt(i))
        }
        for (i in tail until len) {
            out.put(history.opt(i))
        }
        return out
    }
    // 落盘当前会话历史（裁剪后写回）。
    @JvmStatic
    fun saveHistory(host: ChatPanel) {
        val ctx = host.getContext()
        val id = ChatSessions.currentId(ctx)
        // 【v2.8】展开态（prevCount>0）下不裁剪也不回填 conv_prev：
        //  · 若走 trimForSave，此时暂存位已被 prependPrev 清空，被裁的段会被重新写回 conv_prev，
        //    「只能加载一次」的语义被打破 → 切会话再回来点一次就把同一批插两遍，历史重复；
        //  · 展开态本就短暂（滑走即收回），全量暂存不会丢东西，收回时由 collapsePrev 统一收口。
        // 【v2.8·P2】代价是展开期间 conv_<id> 会暂时超过 60 条上限（用户在这段时间里发的消息量级有限，
        //          且一旦收回/发下一条消息就重新走 trimForSave 正常收口）。这里选择「暂时超限」而非
        //          「裁剪 + 不写暂存」：后者会真丢消息，与「数据全留」的定案冲突。
        val before = host.history.length()
        if (host.prevCount > 0) {
            Logs.i("DollhouseMemo", "[save] 展开态全量落盘 len=" + before)
            PetPrefs.setConvHistory(ctx, id, byPoolSafe(ctx, id, host.history).toString())
            return
        }
        host.history = trimForSave(ctx, id, host.history)
        if (before != host.history.length()) {
            Logs.i("DollhouseMemo", "[save] 裁剪 before=" + before + " after=" + host.history.length())
        }
        PetPrefs.setConvHistory(ctx, id, byPoolSafe(ctx, id, host.history).toString())
    }
    /**
     * 【v2.9】把 src[0, end) 里的非摘要消息**追加**进「更早历史」暂存位（不覆盖已有内容）。
     * 【为什么】上下文总结会真删除被压掉的原文（只留要点）。真机反馈：聊到后面
     *   早期原话就再也找不回来了，用户点顶部入口也只能看到更晚的一批。
     *   压掉前先把原文存进暂存位，摘要继续给要点（长期记忆），原文依然可回看 ——
     *   这就是「数据全留」在总结这条路上的落地。
     * 【顺序】暂存位里的旧内容排在时间轴更早处，本次追加接在其后，顺序天然正确。
     */
    @JvmStatic
    fun pushPrevTail(ctx: Context, id: String, src: JSONArray?, end: Int) {
        if (src == null || end <= 0) {
            return
        }
        try {
            // 【v2.9.6】覆盖写，不再追加：用户明确要求「第二次总结时，第一次已经被总结的
            //  内容就删掉」。旧批的要点此时已并入本次摘要（transcript 带上了【更早的归档】），
            //  再留着只会让聊天区一直挂着「加载更早」入口，看起来像「没被销毁」。
            //  代价：第一次总结后回看过的原文，在这次之后不再可取回 —— 这是用户点名的行为。
            val prev = JSONArray()
            for (i in 0 until end) {
                if (i >= src.length()) {
                    break
                }
                val o = src.optJSONObject(i)
                if (o == null || "summary" == o.optString("kind")) {
                    continue
                }
                try {
                    val c = JSONObject(o.toString())
                    c.remove("_prev")
                    prev.put(c)
                } catch (_: Throwable) {
                }
            }
            Logs.i("DollhouseMemo", "[pushPrev] srcLen=" + src.length()
                    + " end=" + end + " prevLen=" + prev.length() + " 覆盖写=true")
            // 【v2.9.6】本次没有可留的原文时也要落一次空值：把上一次的残留清干净，
            //  否则「加载更早」入口会指向一批早就被并进摘要的旧消息。
            PetPrefs.setConvPrev(ctx, id, prev.toString())
        } catch (_: Throwable) {
            Logs.i("DollhouseMemo", "[pushPrev] 异常")
        }
    }

    /**
     * 【r13·对齐 Operit】把一批消息**追加**进「更早历史」暂存位。返回是否写入成功。
     *
     * 【为什么必须追加，不能覆盖】[pushPrevTail] 是「覆盖写」（它服务的是自动归档：
     *   旧批的要点已并入新摘要，留着没意义）。但插入总结这条路上，本方法要挪走的原文
     *   属于**不同的时间段**，暂存位里可能还压着更早的一批 —— 覆盖会把那批直接抹掉，
     *   等于用户点「加载更早」时数据凭空少一段。数据只增不减是这里的硬约束。
     *
     * 【顺序】暂存位里已有的排在时间轴更早处，本批接着往后放 —— 顺序天然正确，
     *   [prependPrev] 补回时整批插到正文最前，时间轴依旧成立。
     *
     * 【失败即不删】调用方必须先用返回值判断：只有 true（已落盘）才允许把原文从正文里删掉；
     *   写盘失败还照删，就是真丢数据。
     */
    @JvmStatic
    fun stashToPrev(ctx: Context, id: String, msgs: JSONArray?): Boolean {
        if (msgs == null || msgs.length() == 0) {
            return true
        }
        return try {
            val prev = try {
                JSONArray(PetPrefs.convPrev(ctx, id))
            } catch (_: Throwable) {
                JSONArray()
            }
            for (i in 0 until msgs.length()) {
                val o = msgs.optJSONObject(i) ?: continue
                val c = JSONObject(o.toString())
                // 补回批标记不能带进暂存位（它描述的是"当前正在展开"，跨批次无意义）。
                c.remove("_prev")
                prev.put(c)
            }
            PetPrefs.setConvPrev(ctx, id, prev.toString())
            true
        } catch (t: Throwable) {
            Logs.w("DollhouseMemo", "stashToPrev 失败", t)
            false
        }
    }

    /** 是否还有「更早的历史」可补（供聊天记录顶部决定要不要显示入口）。 */
    @JvmStatic
    fun hasPrev(ctx: Context): Boolean {
        return try {
            JSONArray(PetPrefs.convPrev(ctx, ChatSessions.currentId(ctx))).length() > 0
        } catch (_: Throwable) {
            false
        }
    }
    /**
     * 把暂存的「更早的历史」补回当前会话（只成功一次）。
     * 【返回】补回的条数（0 = 没得补），供宿主记录展开态（见 collapsePrev）。
     */
    @JvmStatic
    fun prependPrev(host: ChatPanel): Int {
        val ctx = host.getContext()
        val id = ChatSessions.currentId(ctx)
        val prev: JSONArray
        try {
            prev = JSONArray(PetPrefs.convPrev(ctx, id))
        } catch (_: Throwable) {
            return 0
        }
        if (prev.length() == 0) {
            return 0
        }
        host.history = mergePrev(prev, host.history)
        PetPrefs.removeConvPrev(ctx, id)
        // 【坑】不能走 saveHistory：它会再把「刚补回来的这批」当成溢出批裁掉，白忙一场。
        // 【v2.8·N1】这里的 _prev 标记要跟着落盘：迷你框（PetTalk）那条旁路读的就是这份数组，
        //   它需要靠标记知道「现在处于展开态、别把补回批重新回填进暂存位」。
        //   残留风险已由 loadHistory → collapsePrevOnLoad 在载入时就地收回并消费掉标记来闭合。
        PetPrefs.setConvHistory(ctx, id, byPoolSafe(ctx, id, trimForDisplay(host.history)).toString())
        return prev.length()
    }

    /**
     * 【交互】把已补回的「更早的历史」收回去（滑过分割线即回到收缩态）。
     * 【数据不丢】这批原样写回暂存位，顶部入口重新出现，点一下还能补回来。
     * 【切点】展开态结构 = [头部摘要 ×n] + [补回批 ×count] + [正文]，
     *         期间新消息只会追加到尾部，不影响前面的下标。
     */
    @JvmStatic
    fun collapsePrev(host: ChatPanel, count: Int): Boolean {
        if (count <= 0) {
            return false
        }
        val ctx = host.getContext()
        var from = 0
        while (from < host.history.length()) {
            val o = host.history.optJSONObject(from)
            if (o == null || "summary" != o.optString("kind")) {
                break
            }
            from++
        }
        // 【v2.8·P3】按来源标记定位补回批，不再只靠「头部摘要数 + count」的下标推导：
        //  标记是 mergePrev 合并时现场打上的，结构漂移时不会指到别人身上。
        //  标记批必须紧跟头部摘要、条数正好等于 count，对不上就放弃这次收回 ——
        //  宁可把展开态留在原地（用户手动再滑一次即可），也不能摘错正文。
        var to = from
        while (to < host.history.length() && isPrevItem(host.history.optJSONObject(to))) {
            to++
        }
        if (to - from != count) {
            return false
        }
        val prev = JSONArray()
        for (i in from until to) {
            val o = host.history.optJSONObject(i)
            if (o == null || "summary" == o.optString("kind")) {
                return false
            }
            prev.put(o)
        }
        if (prev.length() != count) {
            return false
        }
        // 【坑】摘除后若一条正文都不剩，这个会话会变成空档：renderHistory 在 length==0 时
        //       不会铺「加载更早」入口，用户将无法从 UI 取回这批。此时放弃这次自动收回。
        if (to >= host.history.length()) {
            return false
        }
        // 【v2.8·N5】全部校验通过后才动标记：提前 remove 会在中途 return 时留下
        //   「内存已无标记、prefs 里还有」的不一致（数据不丢，但下次展开会立刻退化成 N1 的残留态）。
        for (i in from until to) {
            val o = host.history.optJSONObject(i)
            if (o != null) {
                o.remove("_prev")
            }
        }
        val out = JSONArray()
        for (i in 0 until from) {
            out.put(host.history.opt(i))
        }
        for (i in to until host.history.length()) {
            out.put(host.history.opt(i))
        }
        host.history = out
        val id = ChatSessions.currentId(ctx)
        PetPrefs.setConvPrev(ctx, id, prev.toString())
        // 【坑】同样不能走 saveHistory：那会把刚写回的暂存批又当成溢出批捞出来重填一遍。
        PetPrefs.setConvHistory(ctx, id, byPoolSafe(ctx, id, host.history).toString())
        return true
    }

    /**
     * 【v2.8·P3】是否为「更早历史」补回批的成员。
     * 【标记】由 mergePrev 合并时现场打上（_prev），收回时据此精确定位，不再单靠下标推导。
     * 【无害】请求体只取 role / content / image，多这一个键不会被发出去。
     */
    @JvmStatic
    fun isPrevItem(o: JSONObject?): Boolean {
        return o != null && o.optBoolean("_prev", false)
    }

    /**
     * 【v2.8·N1】载入历史时统一剥掉 _prev 残留标记。
     * 【兜底】旧版本写下的档、或任何绕过落盘口写入的标记，都在这里一次性清干净：
     *         标记只对「本次展开」有意义，跨载入无意义。
     */
    @JvmStatic
    fun stripPrevTags(history: JSONArray?) {
        if (history == null) {
            return
        }
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i)
            if (o != null && o.has("_prev")) {
                o.remove("_prev")
            }
        }
    }

    /** 把暂存批接到头部摘要之后、正文之前，然后整体去掉头部残留摘要（只保留现有历史自己的摘要）。 */
    private fun mergePrev(prev: JSONArray, history: JSONArray): JSONArray {
        val out = JSONArray()
        var from = 0
        while (from < history.length()) {
            val o = history.optJSONObject(from)
            if (o == null || "summary" != o.optString("kind")) {
                break
            }
            out.put(o)
            from++
        }
        for (i in 0 until prev.length()) {
            val o = prev.optJSONObject(i)
            if (o != null) {
                // 【v2.8·P3】给补回批打上来源标记：收回时按标记精确摘除，不再单靠下标对齐。
                // 【无害】请求体只取 role/content/image，多这一个键不会被发出去。
                // 【坑】JSONObject 的 put / putOpt 在 Android 上都声明抛 JSONException，
                //       而 mergePrev 的调用链在 prependPrev 里、不往外抛异常，故就地吞掉：
                //       标记打不上最多是这次收回被拒（collapsePrev 会返回 false），不会丢数据。
                try {
                    o.putOpt("_prev", true)
                } catch (_: Throwable) {
                }
            }
            out.put(prev.opt(i))
        }
        for (i in from until history.length()) {
            out.put(history.opt(i))
        }
        return out
    }
    /** 【v2.8】占位：当前直接返回原数组（补回后不马上再裁，否则等于没补），保留方法名供调用点语义清晰。 */
    private fun trimForDisplay(history: JSONArray): JSONArray {
        return history
    }
    // 独立版：给 ChatPanel 之外的使用者（迷你聊天框）落盘。
    // 【共用点】存档键 = PetPrefs.convHistory(ctx, ChatSessions.currentId(ctx))，与全屏聊天页同一个键，
    //   所以迷你框发的消息，打开全屏页能看到完整记录。
    @JvmStatic
    fun saveHistory(ctx: Context, history: JSONArray) {
        saveHistory(ctx, history, ChatSessions.currentId(ctx))
    }
    // 【v0.0.1】显式 id 版：给人偶专属会话（PetTalk）用，不再借当前会话的槽。
    @JvmStatic
    fun saveHistory(ctx: Context, history: JSONArray, id: String) {
        PetPrefs.setConvHistory(ctx, id, byPoolSafe(ctx, id, trimForSave(ctx, id, history)).toString())
    }
    // 向历史数组追加一条消息。
    @JvmStatic
    fun push(host: ChatPanel, str: String, str2: String) {
        push(host, str, str2, null)
    }
    @JvmStatic
    fun push(host: ChatPanel, str: String, str2: String, str3: String?) {
        push(host, str, str2, str3, null, 0L)
    }

    /**
     * 【r13·思考过程落盘】带推理内容的完整版。
     *
     * 【为什么思考要跟着消息一起存】用户要求「连内容连思考的回复我，并且思考是可以展开的」——
     *   只留在内存（[com.dollhouse.app.ui.chat.ThinkingBox]）的话，重开 / 切会话即消失，
     *   历史上每一条回复都无法再看它当时的推理。存进消息对象后，重铺时按条取用即可。
     *
     * 【为什么不污染请求体】组装请求时只取 `role` / `content` / `image` 三个键
     *   （见 [appendTailMessages]），`think` 多带一个键不会被发出去；
     *   推理回传与否仍由供应商的 resendHistoryReasoning 单独控制。
     *
     * 【长度上限】推理可能很长（推理型模型动辄数千字），单条存档做硬截断，
     *   避免一份历史把 SharedPreferences 撑爆（上限 4000 字，超出留头部）。
     *
     * @param think 思考内容；null / 空串则不写该字段（普通模型没有推理，历史里不留空键）
     * @param thinkMs 「思考了 X 毫秒」，流式下是真实思考段耗时；0 表示未知
     */
    @JvmStatic
    fun push(host: ChatPanel, str: String, str2: String, str3: String?, think: String?, thinkMs: Long) {
        try {
            val jSONObject = JSONObject()
            jSONObject.put("role", str)
            jSONObject.put("content", str2)
            if ("assistant" == str) {
                jSONObject.put("rating", 0)
                val t = think?.trim() ?: ""
                if (t.isNotEmpty()) {
                    jSONObject.put("think", clipThink(t))
                    if (thinkMs > 0) {
                        jSONObject.put("thinkMs", thinkMs)
                    }
                }
            }
            if (str3 != null) {
                jSONObject.put("image", str3)
            }
            host.history.put(jSONObject)
        } catch (_: Throwable) {
        }
        ChatHistoryStore.saveHistory(host)
        // 多会话：刷新最后活动时间；首条用户消息顺便给会话命名。
        val ctx = host.getContext()
        val id = ChatSessions.currentId(ctx)
        ChatSessions.touch(ctx, id)
        // 【r13·列表删除=隐藏】有新消息进来，条目就重新出现在列表里（QQ 语义）。
        PetPrefs.setConvHidden(ctx, id, false)
        if ("user" == str) {
            ChatSessions.autoTitleIfNeeded(ctx, id, str2)
        }
        // 【r13·新记忆系统】聊完一轮后自动抽取长期记忆（替代旧的「自动总结」归档）。
        //
        // 【为什么挂在这里】本方法（host 版）是全屏聊天页落盘的必经之路，
        //   落盘之后才抽取 —— 保证模型看到的是「已经写进历史的那一轮」。
        // 【为什么带 dollId】抽取结果只写进**当前会话绑定的那只人偶**的库；
        //   旧实现写死默认配置，导致所有人偶的记忆混在一个桶里（本轮修掉）。
        // 【开关】设置页「实验 → 记忆库设置 → 自动保存记忆」。
        autoExtract(host, str)
        // 【r13 修 bug·「在人偶页说句话，聊天列表不出现」】必须广播「会话已变」。
        //
        // 【曾经的 bug】全工程有两条 push 路径：
        //   · [push] (ctx, history, role, content, id) —— 人偶迷你框 PetTalk 走它，**有**广播；
        //   · 本方法（host 版）—— **全屏聊天页 ChatPanel 走它，没有广播**。
        //   于是用户「去人偶页 → 点开某人偶 → 说一句话 → 回聊天列表」时，
        //   会话虽已建、消息虽已落库，聊天列表页却收不到任何变更信号，
        //   仍显示旧数据（空态或旧预览），只能等下次冷启动 —— 用户报的正是这个。
        //   修法：把广播补到这条路径上，两条 push 的对外行为就此一致。
        ChatListSignal.bump()
    }

    /**
     * 【r13·新记忆系统】一轮对话结束后自动抽取长期记忆。
     *
     * 【触发时机】assistant 的消息落盘时（一轮问答的终点），而不是 user 落盘时 ——
     *   否则会把「只有提问、还没回答」的半轮喂给模型。
     *
     * 【人偶】取当前会话绑定的那只，并在调用前同步给 [ChatToolRegistry]，
     *   保证「手动 remember 工具」与「自动抽取」写的是同一个库。
     */
    @JvmStatic
    fun autoExtract(host: ChatPanel?, role: String?) {
        if (host == null || "assistant" != role) {
            return
        }
        try {
            val ctx = host.getContext()
            if (!PetPrefs.mem2Auto(ctx)) {
                return
            }
            val dollId = host.boundDollId()
            ChatToolRegistry.setDoll(dollId)
            // 只喂最后几轮，避免把整段历史再抽一遍（与抽取器内部的 HISTORY_LIMIT 呼应）。
            val turns = ArrayList<Pair<String, String>>()
            val h = host.history
            val from = if (h.length() > 6) h.length() - 6 else 0
            for (i in from until h.length()) {
                val o = h.optJSONObject(i) ?: continue
                if ("summary" == o.optString("kind")) {
                    continue
                }
                val r = o.optString("role")
                if ("user" != r && "assistant" != r) {
                    continue
                }
                turns.add(Pair(r, o.optString("content", "")))
            }
            if (turns.isEmpty()) {
                return
            }
            MemExtractor.extract(ctx, dollId, turns, null)
        } catch (t: Throwable) {
            Logs.w("DollhouseMem", "autoExtract 失败", t)
        }
    }
    /**
     * 【独立版】给 ChatPanel 之外的使用者（迷你聊天框）追加一条消息并落盘。
     * 【共用点】saveHistory 内部走 PetPrefs.convHistory(ctx, ChatSessions.currentId(ctx))，
     *   与全屏聊天页同一个键，所以两边的消息会汇入同一份对话。
     * 【取舍】自动抽取长期记忆挂在 host 版（[push] 的 ChatPanel 重载）上：
     *   迷你框（PetTalk）走本方法，由它在落盘完成后自行触发，避免同一次 push 跑两遍。
     */
    @JvmStatic
    fun push(ctx: Context, history: JSONArray, role: String, content: String) {
        push(ctx, history, role, content, ChatSessions.currentId(ctx))
    }
    // 【v0.0.1】显式 id 版：人偶专属会话走这里（读与写必须同一个 id）。
    @JvmStatic
    fun push(ctx: Context, history: JSONArray, role: String, content: String, id: String) {
        put(history, role, content, null)
        saveHistory(ctx, history, id)
        ChatSessions.touch(ctx, id)
        // 【r13·列表删除=隐藏】有新消息进来，条目就重新出现在列表里（QQ 语义）。
        //   覆盖全屏聊天页 / 迷你聊天框 / 通知栏回复三条路径（都经本方法落库）。
        PetPrefs.setConvHidden(ctx, id, false)
        if ("user" == role) {
            ChatSessions.autoTitleIfNeeded(ctx, id, content)
        }
        // 【r13·聊天列表】广播一次「会话已变」。
        //   【为什么挂在这里】本方法是**全工程唯一的消息落库入口**：
        //   全屏聊天页（ChatPanel）、人偶迷你框（PetTalk）、通知栏回复全都经它落盘。
        //   挂在最上层的一个点，胜过在每个调用方各写一遍（漏一处就会出现
        //   「发完消息回列表，预览还是旧的」）。
        //   【为什么两层都在这里】数据层不能依赖 UI 层（会形成反向依赖），
        //   所以这里只发一个「轻量信号」，由 UI 层自己去听 —— 见
        //   [com.dollhouse.app.ui.chat.ChatState.bumpList] 的同款说明。
        ChatListSignal.bump()
    }

    /**
     * 【r13·聊天列表】「会话数据已变更」的**跨层信号**。
     *
     * 【为什么不用 ChatState.bumpList】数据层（本包）不该 import UI 层（`ui.chat`）——
     *   那会让「换 UI 框架」变成「改数据层」，也会在单测数据层时被迫拉起 Compose 运行时。
     *   这里放一个极简的监听器容器：数据层只管 `bump()` 触发，
     *   谁想听（列表页）自己 `watch` 注册。**方向是单向的**：UI → 数据层注册回调，
     *   数据层不认识任何 UI 类型。
     *
     * 【线程】回调在**调用 push 的那个线程**上执行（可能是 IO 线程）。
     *   注册方（Compose）要负责切主线程。
     */
    object ChatListSignal {
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        /** 数据变了。 */
        @JvmStatic
        fun bump() {
            for (l in listeners) {
                try {
                    l.invoke()
                } catch (ignored: Throwable) {
                }
            }
        }

        /** 注册监听（幂等由调用方自己保证；本容器不去重）。 */
        @JvmStatic
        fun watch(l: () -> Unit) {
            listeners.add(l)
        }

        /** 注销（页面离开时可调；不调也只是多一个空转回调，不会泄漏 Activity 因为 lambda 只捕获全局状态）。 */
        @JvmStatic
        fun unwatch(l: () -> Unit) {
            listeners.remove(l)
        }
    }
    /** 纯函数：向历史数组追加一条消息，不落盘。 */
    /**
     * 【r13·思考过程】推理文本的落盘上限。
     *
     * 【为什么需要】推理型模型的 reasoning_content 动辄数千字，而整份历史是序列化成一个
     *   SharedPreferences 字符串存的（见 `conv_<id>`）——放开了存会让读写都变卡，
     *   极端情况下还会撞上 prefs 的单值上限。超长时留头部：推理的开头通常已经说清思路。
     */
    private const val THINK_MAX = 4000

    /** 按 [THINK_MAX] 截断推理文本（超出补省略号，让用户知道这里被截过）。 */
    @JvmStatic
    fun clipThink(s: String?): String {
        val t = s?.trim() ?: ""
        if (t.length <= THINK_MAX) {
            return t
        }
        return t.substring(0, THINK_MAX) + "…（推理过长，已截断）"
    }

    @JvmStatic
    fun put(history: JSONArray, role: String, content: String, image: String?) {
        try {
            val jSONObject = JSONObject()
            jSONObject.put("role", role)
            jSONObject.put("content", content)
            if ("assistant" == role) {
                jSONObject.put("rating", 0)
            }
            if (image != null) {
                jSONObject.put("image", image)
            }
            history.put(jSONObject)
        } catch (_: Throwable) {
        }
    }
    /**
     * 把一段文本压成单行：用正则删掉所有回车 / 换行 / 连续空白（含全角空格、不换行空格）。
     *
     * 【为什么】模型回复常自带换行，气泡按字符宽排版时会被硬生生断在句中
     *   （用户报的「不知 / 道就不知道嘛」）；写进历史时也一并压平，
     *   模型下一轮便不再照抄自己的换行格式。
     * 【入口】PetTalk.finish / ChatPanel.handleReply / bubbleVersion（两处 AI 回复共用）。
     */
    @JvmStatic
    fun oneLine(str: String?): String {
        if (str == null) {
            return ""
        }
        return str.replace(Regex("[\\s\\u3000\\u00A0]+"), "").trim()
    }
    @JvmStatic
    fun bubbleVersion(str: String): String {
        val trim = str.replace('\n', ' ').trim()
        if (trim.length <= 28) {
            return trim
        }
        return trim.substring(0, 28) + "…"
    }
    /**
     * 【池化】按会话所属池收口落盘内容：人偶池（pet）的 assistant 回复压成单行
     * （去回车 / 换行 / 连续空白），自聊池（self）原样保留 —— 用户定案「只有人偶的历史遵循正则，
     * 自己聊的历史要有空格或回车，让 AI 按自己喜好回答」。
     *
     * 【为什么在落盘侧】收口点若放在 ChatPanel.handleReply 的 trim 上，会同时改掉聊天页的
     *   显示与 PetBubble 的二次处理；放这里内存里的 host.history 不动（显示保持原貌），
     *   只有写进 conv_&lt;id&gt; 的那份被压平，下一轮上下文与下次载入自然跟着一致。
     * 【代价】每次落盘多一次浅拷贝 + 遍历（条数上限 60），可忽略。
     */
    @JvmStatic
    fun byPool(ctx: Context, id: String, history: JSONArray?): JSONArray? {
        if (history == null || ChatSessions.POOL_PET != ChatSessions.poolOf(ChatSessions.find(ctx, id))) {
            return history
        }
        val out = JSONArray()
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i)
            if (o == null) {
                out.put(history.opt(i))
                continue
            }
            try {
                val c = JSONObject(o.toString())
                if ("assistant" == c.optString("role")) {
                    c.put("content", oneLine(c.optString("content")))
                    // 【r13·思考过程】推理内容不跟着压平：它本来就是多行长文本，
                    //   压成单行会毁掉排版；它也不会进请求体，无需与 content 同口径。
                }
                out.put(c)
            } catch (_: Throwable) {
                out.put(o)
            }
        }
        return out
    }

    /**
     * 【r15·收敛 !!】byPool 的安全版：绝不返回 null，调用方不再需要 `!!`。
     *   旧代码 7 处写成 `byPool(ctx, id, X)!!.toString()`。byPool 只在
     *   history == null 时返回 null（其余分支一定给 JSONArray），虽然当前所有
     *   调用点传的都是非空数组，但这种「靠调用点保证」的契约一旦有人改调用就炸；
     *   收口成本几乎为零，直接加固。
     */
    @JvmStatic
    fun byPoolSafe(ctx: Context, id: String, history: JSONArray?): JSONArray {
        return byPool(ctx, id, history) ?: history ?: JSONArray()
    }
    @JvmStatic
    fun buildRequest(host: ChatPanel): JSONArray {
        // 兼容入口：保留给仍按 host 调用的旧位置。
        return buildRequest(host.getContext(), host.history)
    }

    /**
     * 【r13·一人偶一对话】按「这条会话绑定的人偶」组装请求。
     *
     * 【为什么要这个重载】本方法以前写死 `DollStore.DEFAULT_ID` 取人设与记忆 ——
     *   那在「一个 App 只聊一只人偶」的时代没问题，但改造后每条会话都绑定一只人偶，
     *   再写死默认那只，就会让用户和 A 人偶聊出 B 人偶的性格（串人设）。
     *
     * 【人偶从哪来】由调用方（[ChatPanel] / [PetTalk]）从当前会话反查（[ChatSessions.dollOfConv]）
     *   后传入 —— 数据层不认识「当前会话」这个概念，保持分层干净。
     *
     * @param dollId 这个人偶 id；空串 = 退回默认配置（老数据 / 未绑定的会话）。
     */
    @JvmStatic
    fun buildRequestFor(ctx: Context, history: JSONArray, dollId: String?): JSONArray {
        return buildRequest(ctx, history, dollId)
    }

    /**
     * 【通用版】组装一次请求的全部 system 消息（人设 / 思考档位 / 长期记忆 / 学习示例）
     *   + 最近 12 条对话。参数与开关全部读自 PetPrefs，两个入口（全屏页 / 迷你聊天框）行为不会分叉。
     * 【OCR】图片不再以 image_url 上送，改成本地扫成文字拼进正文 —— 于是这里不再需要
     *   「本次请求是否带图」这类回传参数，模型也一律用同一个（普通文本模型即可）。
     */
    @JvmStatic
    fun buildRequest(ctx: Context, history: JSONArray): JSONArray {
        return buildRequest(ctx, history, null)
    }

    /**
     * 【r13】带人偶的完整版：人设与长期记忆都按 [dollId] 取（空则退回默认配置）。
     * 这是「聊天对话框绑定人偶」在**请求侧**的落点 —— 会话绑定决定用谁的性格与记忆。
     */
    @JvmStatic
    fun buildRequest(ctx: Context, history: JSONArray, dollId: String?): JSONArray {
        val who = if (dollId.isNullOrEmpty()) DollStore.DEFAULT_ID else dollId
        val jSONArray = JSONArray()
        try {
            // 【r13·角色设定】人设不再只是一段文本：分段（性格 / 世界观 / 说话风格 / 称呼）
            //   + 常驻注入 + 命中的世界书，统一由 [PersonaBuilder] 按「位置 → 优先级」拼好。
            //   【共用一条路径】人格页的「提示词预览」调的是同一个方法，所以预览必然与实际一致。
            //   【兼容】没写过结构化设定时，[PersonaBuilder.composePersona] 的行为与改造前一致：
            //   只有那段自由文本 → 输出一条 system / content=persona 的消息。
            val dollName = DollStore.byId(ctx, who)?.name
            val built = PersonaBuilder.build(ctx, who, dollName, history, lastUserText(history))
            val personaMsgs = built.messages
            for (i in 0 until personaMsgs.length()) {
                jSONArray.put(personaMsgs.opt(i))
            }
            // 【对话示例】单独一条，紧跟人设之后（不与注入抢位置）。
            val spec = DollProfile.spec(ctx, who)
            val example = PersonaBuilder.exampleText(spec)
            if (example.isNotEmpty()) {
                val exObj = JSONObject()
                exObj.put("role", "system")
                exObj.put("content", example)
                jSONArray.put(exObj)
            }
            // 【设备操作】lamda 服务在跑时，追加一条独立 system 消息，告诉模型她手上还有 device 工具。
            // 【为什么独立一条】人设正文属用户可编辑内容，不该被运行时状态污染（与下面「回复格式」同款做法）；
            //   而设备能力是「服务在不在」决定的运行时状态，没法写死在人设里。
            // 【门控】服务没起就不加这段 —— 提示词里写着会用工具、tools 里却没有，模型会瞎编。
            val deviceHint = LamdaManager.devicePrompt()
            if (!deviceHint.isEmpty()) {
                val devObj = JSONObject()
                devObj.put("role", "system")
                devObj.put("content", deviceHint)
                jSONArray.put(devObj)
            }
            // 【三件套】思考程度（灯泡）：提示词兜底 —— 中转常把不认识的字段悄悄吃掉，
            // 只有这句还起作用。「自动」档返回空串，等于完全不干预。
            val thinkPrompt = ThinkLevel.prompt(PetPrefs.thinkLevel(ctx))
            if (!thinkPrompt.isEmpty()) {
                val thinkObj = JSONObject()
                thinkObj.put("role", "system")
                thinkObj.put("content", thinkPrompt)
                jSONArray.put(thinkObj)
            }
            // 【思考框】格式约束：推理型模型会把正文写进思考区而把 content 留空，
            // 上层就只能拿到空气泡。这里独立补一条 system 指令把「正文必须有内容」说死。
            // 【为什么独立一条】人设正文属用户可编辑内容，不该往里塞格式约束；
            // 追加独立消息与上面「思考程度」同款做法，两边入口（全屏页 / 迷你框）共用本方法，一并受约束。
            val fmtObj = JSONObject()
            fmtObj.put("role", "system")
            fmtObj.put("content", "【回复格式】你对主人说的话必须写在正文里，正文不能为空；"
                    + "推理、分析、自我检查的过程请放在思考区，既不要混进正文，也不要用思考区代替正文。")
            jSONArray.put(fmtObj)
            appendMemory(ctx, jSONArray, who, lastUserText(history))
            appendSummaries(jSONArray, history)
            appendLearnExamples(ctx, jSONArray)
            // 【r13·角色设定】「最新消息前」的注入：紧贴对话末尾，权重最高的一档。
            //   放在尾部消息**之前**，确保它比对话更靠近「当前这一问」。
            appendBottomInjections(ctx, jSONArray, who, history)
            appendTailMessages(ctx, jSONArray, history)
            // 【r13·角色设定】后置指令：整份请求的最后一条，模型最容易照做。
            //   【为什么放最末】它的用途就是「压过前面所有话」的硬约束（如「永远不要跳出角色」），
            //   放在中间等于自废武功。
            val post = DollProfile.spec(ctx, who).postscript.trim()
            if (post.isNotEmpty()) {
                val postObj = JSONObject()
                postObj.put("role", "system")
                postObj.put("content", post)
                jSONArray.put(postObj)
            }
        } catch (_: Throwable) {
        }
        return jSONArray
    }

    /**
     * 【r13·角色设定】把「最新消息前」的注入追加进请求。
     *
     * 【分工】「人设之前 / 之后」两种位置由 [PersonaBuilder.build] 处理（它们在头部），
     *   这里只补第三、四种位置 —— 它们必须**贴着对话末尾**才有意义。
     */
    private fun appendBottomInjections(ctx: Context, arr: JSONArray, dollId: String?, history: JSONArray) {
        try {
            val spec = DollProfile.spec(ctx, dollId)
            if (spec.injections.isEmpty() && spec.lore.isEmpty()) {
                return
            }
            val list = PersonaBuilder.collectInjections(spec, history, lastUserText(history), null)
            for (it in list) {
                if (InjectPos.TOP != it.position && InjectPos.BOTTOM != it.position) {
                    continue
                }
                val text = it.content.trim()
                if (text.isEmpty()) {
                    continue
                }
                val o = JSONObject()
                o.put("role", if ("user" == it.role) "user" else "system")
                o.put("content", text)
                arr.put(o)
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 长期记忆注入：新版记忆库（条目 + 资料文档），按当前问题做相关性检索。 */
    @Throws(Exception::class)
    private fun appendMemory(ctx: Context, arr: JSONArray) {
        appendMemory(ctx, arr, DollStore.DEFAULT_ID, null)
    }

    /**
     * 【r13】按人偶取长期记忆，并按相关性挑（对齐 Operit 的「检索→注入」两步）。
     *
     * 【为什么必须带 dollId】记忆库按人偶分库（`mem2_list_<dollId>`），
     *   这里写死默认那只会让 A 人偶「记得」B 人偶的事 —— 分库的意义就没了。
     *
     * 【两条路径】
     *   · 开了「记忆检索」：用**这一轮主人说的话**当查询词，按关键词/标签/关联打分取前 8 条；
     *   · 关了：退回「最近若干条」，保证仍能带上记忆（旧行为）。
     *
     * @param query 本轮用户输入；null / 空则按时间倒序取。
     */
    @Throws(Exception::class)
    private fun appendMemory(ctx: Context, arr: JSONArray, dollId: String?, query: String?) {
        val who = if (dollId.isNullOrEmpty()) DollStore.DEFAULT_ID else dollId
        // 【资料文档】它很短且永远是稳定画像，每轮都带上，不参与检索。
        val doc = Mem.profile(ctx, who)
        val items = if (PetPrefs.mem2Search(ctx)) {
            MemSearch.search(ctx, who, query)
        } else {
            MemSearch.search(ctx, who, null)
        }
        val body = MemSearch.joinForInject(items)
        if (doc.isEmpty() && body.isEmpty()) {
            return
        }
        val sb = StringBuilder()
        if (doc.isNotEmpty()) {
            sb.append("【关于主人的印象】\n").append(doc).append('\n')
        }
        if (body.isNotEmpty()) {
            sb.append("【记下的事】\n").append(body).append('\n')
        }
        val memObj = JSONObject()
        memObj.put("role", "system")
        memObj.put("content", sb.toString()
                + "（这些是你之前记下的事，回答时可以自然地用到，不要生硬复述。）")
        arr.put(memObj)
    }

    /** 从历史里倒着找最后一条「主人说的话」，作为检索的查询词。 */
    private fun lastUserText(history: JSONArray?): String? {
        if (history == null) {
            return null
        }
        for (i in history.length() - 1 downTo 0) {
            val o = history.optJSONObject(i) ?: continue
            if ("user" == o.optString("role")) {
                val c = o.optString("content", "").trim()
                if (c.isNotEmpty()) {
                    return c
                }
            }
        }
        return null
    }

    /**
     * 旧摘要（kind=summary）单独拎出来注入。
     * 【为什么】摘要挂在历史头部，而尾部窗口只取最近 12 条，对话一长它就永远进不了请求体，
     *   表面现象就是「AI 记不住历史的话」。
     */
    @Throws(Exception::class)
    private fun appendSummaries(arr: JSONArray, history: JSONArray) {
        // 【核心修复】旧摘要（kind=summary）挂在历史头部，而下面只遍历最后 12 条，
        //   对话一长它就永远进不了请求体 —— 表面现象就是「AI 记不住历史的话」。
        //   这里把摘要从历史里单独拎出来显式注入，不再依赖「恰好落在 12 条窗口里」。
        val summaries = JSONArray()
        for (k in 0 until history.length()) {
            val o = history.optJSONObject(k)
            if (o != null && "summary" == o.optString("kind")) {
                val c = o.optString("content", "")
                if (!c.trim().isEmpty()) {
                    summaries.put(c)
                }
            }
        }
        if (summaries.length() > 0) {
            val sb = StringBuilder("【历史对话摘要】\n")
            for (k in 0 until summaries.length()) {
                sb.append(summaries.optString(k))
                sb.append('\n')
            }
            sb.append("（以上是你和主人早期对话的归档要点，接着聊时可以自然地用到，不要生硬复述。）")
            val sumObj = JSONObject()
            sumObj.put("role", "system")
            sumObj.put("content", sb.toString())
            arr.put(sumObj)
        }
    }

    /** 学习示例注入：取最近几条「点赞 → 示范」，成对拼成 user/assistant。 */
    @Throws(Exception::class)
    private fun appendLearnExamples(ctx: Context, arr: JSONArray) {
        if (PetPrefs.learnEnabled(ctx)) {
            val learnExamples = PetPrefs.learnExamples(ctx)
            var length = learnExamples.length() - Math.min(if (0 != 0) 2 else 3, learnExamples.length())
            while (length < learnExamples.length()) {
                val optJSONObject = learnExamples.optJSONObject(length)
                if (optJSONObject != null) {
                    val jSONObject3 = JSONObject()
                    jSONObject3.put("role", "user")
                    jSONObject3.put("content", optJSONObject.optString("u"))
                    arr.put(jSONObject3)
                    val jSONObject4 = JSONObject()
                    jSONObject4.put("role", "assistant")
                    jSONObject4.put("content", optJSONObject.optString("a"))
                    arr.put(jSONObject4)
                }
                length++
            }
        }
    }

    /**
     * 尾部窗口：最近 12 条对话。
     *
     * 【r15·视觉直送】图片的处理按模型能力分两条路：
     *   · 模型 `capVision = true`（或配了专门的识图模型）→ **原图直送**：
     *     `{"role":"user","content":[{"type":"text",...},{"type":"image_url",...}]}`
     *     模型自己看图，能识别画面内容（不只是文字）。
     *   · 否则 → **本地 OCR 兜底**：把图扫成文字拼进正文，任何纯文本模型都能看懂。
     * 判定与编码在 [visionPlan] / [ImageEncoder] 里，这里只负责按计划组装。
     *
     * 【发图配额】历史窗口内最多带 [VISION_HISTORY_IMAGES] 张图（用户定案 2B：
     *   当轮新图 + 最近 2~3 轮历史图），防止多图长聊时 token 爆炸。
     */
    @Throws(Exception::class)
    private fun appendTailMessages(ctx: Context, arr: JSONArray, history: JSONArray) {
        // 【r15】先算本次请求的图片策略（用哪个模型看图、能不能直送）。
        val plan = visionPlan(ctx)
        var i = 0
        var max = Math.max(0, history.length() - (if (0 != 0) 6 else 12))
        while (max < history.length()) {
            val optJSONObject2 = history.optJSONObject(max)
            if (optJSONObject2 != null && "summary" != optJSONObject2.optString("kind")) {
                val optString = optJSONObject2.optString("role")
                val optString2 = optJSONObject2.optString("content")
                val optString3 = optJSONObject2.optString("image", null)
                val hasImg = optString3 != null && !optString3.isEmpty()
                        && "user" == optString && ImageStore.exists(ctx, optString3)
                if (hasImg && MODE_DIRECT == plan && i < VISION_HISTORY_IMAGES) {
                    // 【视觉直送】content 用数组承载「文字 + 图片」，与 OpenAI 多模态格式一致。
                    val dataUri = ImageEncoder.toDataUri(ctx, optString3)
                    if (dataUri != null) {
                        arr.put(visionMessage(optString, optString2, dataUri))
                        i++
                    } else {
                        // 编码失败 → 这一条退回 OCR，不能让请求少一条消息。
                        arr.put(textMessage(ctx, optString, optString2, optString3))
                    }
                } else if (hasImg && MODE_VISION_MODEL == plan && i < VISION_HISTORY_IMAGES) {
                    // 【3C·识图模型】当前模型不会看图，但用户配了识图模型 →
                    //   用那个模型把图分析成文字注入正文（保留 current 模型继续聊）。
                    val msg = JSONObject()
                    msg.put("role", optString)
                    msg.put("content", withVisionCaption(ctx, optString2, optString3))
                    arr.put(msg)
                    i++
                } else if (hasImg) {
                    // 【OCR 兜底】既没有视觉能力、也没配识图模型，或已超发图配额。
                    arr.put(textMessage(ctx, optString, optString2, optString3))
                } else {
                    val jSONObject9 = JSONObject()
                    jSONObject9.put("role", optString)
                    jSONObject9.put("content", optString2)
                    arr.put(jSONObject9)
                }
            }
            max++
        }
    }

    /** 发图配额：整个历史窗口最多带几张原图（含当轮）。 */
    private const val VISION_HISTORY_IMAGES = 4

    /** 纯文本消息（不带图 / OCR 已并入正文）。 */
    @Throws(Exception::class)
    private fun textMessage(ctx: Context, role: String, text: String?, imagePath: String?): JSONObject {
        val o = JSONObject()
        o.put("role", role)
        o.put("content", withOcrCaption(ctx, text, imagePath))
        return o
    }

    /**
     * 多模态消息：content 为数组，文字与图片各占一段。
     *
     * 【格式依据】OpenAI Chat Completions 的多模态写法，也是 Operit
     *   （参考源码 OpenAIProvider.kt:983）采用的形态：
     *   `content: [{"type":"text","text":...}, {"type":"image_url","image_url":{"url":"data:..."}}]`
     *
     * 【为什么文字段放最前】多数中转站按顺序解析，文字在前更稳；且模型先拿到
     *   用户的问句再看到图，回答更贴题。
     */
    @Throws(Exception::class)
    private fun visionMessage(role: String, text: String?, dataUri: String): JSONObject {
        val content = JSONArray()
        val body = if (text == null) "" else text.trim()
        if (body.isNotEmpty()) {
            val t = JSONObject()
            t.put("type", "text")
            t.put("text", body)
            content.put(t)
        }
        val img = JSONObject()
        img.put("type", "image_url")
        val url = JSONObject()
        url.put("url", dataUri)
        img.put("image_url", url)
        content.put(img)

        val o = JSONObject()
        o.put("role", role)
        o.put("content", content)
        return o
    }

    /**
     * 本次请求的图片处理模式：
     *   · [MODE_DIRECT] —— 当前模型自己会看图，原图直送；
     *   · [MODE_VISION_MODEL] —— 当前模型不会看图、但用户配了识图模型，
     *     用识图模型把图分析成文字后注入正文（用户定案 3C 的「自动改用识图模型」）；
     *   · null —— 都不行，退回本地 OCR。
     */
    private const val MODE_DIRECT = "direct"
    private const val MODE_VISION_MODEL = "vision_model"

    /**
     * 【r15】当前是否走「原图直送」——供发送前的 OCR 预扫判断要不要跳过。
     *
     * 【为什么要跳过】视觉直送时图片以 base64 原样上送，本地 OCR 的结果根本用不上；
     *   照旧扫一遍等于白白花几秒、白耗一次电，还会让界面多停在「正在看图…」。
     *   识图模型通道（[MODE_VISION_MODEL]）同样不需要本地 OCR，但**需要**等网络分析，
     *   所以那边由 [withVisionCaption] 自己处理。
     */
    @JvmStatic
    fun usesDirectVision(ctx: Context?): Boolean {
        if (ctx == null) {
            return false
        }
        return MODE_DIRECT == visionPlan(ctx)
    }

    private fun visionPlan(ctx: Context): String? {
        val cur = ProviderStore.activeModelRecord(ctx)
        if (cur != null && cur.capVision) {
            return MODE_DIRECT
        }
        // 【3C】当前模型不会看图 → 若配了可用的识图模型，走「识图模型分析」通道。
        if (PetPrefs.visionModel(ctx).isNotEmpty()) {
            return MODE_VISION_MODEL
        }
        return null
    }

    /**
     * 【3C】用识图模型把图「翻译」成文字，拼进正文。
     *
     * 【为什么同步等】请求组装本身在后台线程（ChatPanel 的发送链路），
     *   而这里必须拿到文字才能把消息拼进 JSON —— 所以用 CountDownLatch 同步等待。
     *   超时设 20 秒：识图比 OCR 慢（要走一趟网络），但也不能让用户无限等。
     *
     * 【失败怎么办】拿不到描述就退回本地 OCR —— 用户至少还能看到图上的文字。
     */
    private fun withVisionCaption(ctx: Context, text: String?, imagePath: String?): String? {
        var desc = ""
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            VisionAnalyzer.analyze(ctx, imagePath, null, VisionAnalyzer.Callback { d ->
                desc = d
                latch.countDown()
            })
            latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Logs.w("Dollhouse", "识图模型分析失败，退回 OCR", t)
        }
        if (desc.trim().isEmpty()) {
            // 识图没结果 → 退回本地 OCR（原有路径，保留不删）。
            return withOcrCaption(ctx, text, imagePath)
        }
        val body = if (text == null) "" else text.trim()
        val head = if (body.isEmpty()) "【图片内容】" else body + "\n\n【图片内容】"
        return head + "\n" + desc.trim()
    }

    /**
     * 本次请求是否能把原图直接发给模型；能则返回 null 表示不走视觉、退回 OCR。
     *
     * 【判定顺序（用户定案 3C：优先当前模型；当前模型不会看图时，自动用指定的识图模型）】
     *   ① 当前对话模型自己 `capVision = true` → 用它，直送原图（[MODE_DIRECT]）；
     *   ② 否则若设置了「识图模型」→ 走 [MODE_VISION_MODEL]：用那个模型把图分析成文字，
     *      结果注入正文；聊天本身仍由当前模型负责（不换模型、不打断上下文）；
     *   ③ 都没有 → 退回本地 OCR（[withOcrCaption]，ML Kit 离线识别）。
     */

    /**
     * 【OCR】把图片扫出来的文字拼进要发给模型的正文。
     * 【为什么】图片不再以 image_url 原样上送（不少模型不认这个字段，整条请求会失败），
     *   改为本地 OCR 出文字、当正文发出去后，任何纯文本模型都能看懂图里写了什么。
     * 【还没扫过时】不添乱，原样返回用户写的话（发请求前 OcrEngine 会先补扫）。
     */
    private fun withOcrCaption(ctx: Context, text: String?, imagePath: String?): String? {
        val ocr = OcrEngine.text(ctx, imagePath)
        if (ocr == null) {
            return text
        }
        val body = if (text == null) "" else text.trim()
        if (ocr.trim().isEmpty()) {
            val note = "【图片】这张图里没有可识别的文字。"
            return if (body.isEmpty()) note else body + "\n\n" + note
        }
        val sb = StringBuilder(if (body.isEmpty()) "【图片里的文字】" else body + "\n\n【图片里的文字】")
        sb.append('\n').append(ocr)
        return sb.toString()
    }
}
