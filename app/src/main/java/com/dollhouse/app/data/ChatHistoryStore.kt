package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.ai.MemSummarizer
import com.dollhouse.app.ai.ThinkLevel
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
            PetPrefs.setConvHistory(ctx, id, byPool(ctx, id, history)!!.toString())
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
            PetPrefs.setConvHistory(ctx, id, byPool(ctx, id, history)!!.toString())
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
            PetPrefs.setConvHistory(ctx, id, byPool(ctx, id, host.history)!!.toString())
            return
        }
        host.history = trimForSave(ctx, id, host.history)
        if (before != host.history.length()) {
            Logs.i("DollhouseMemo", "[save] 裁剪 before=" + before + " after=" + host.history.length())
        }
        PetPrefs.setConvHistory(ctx, id, byPool(ctx, id, host.history)!!.toString())
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
        PetPrefs.setConvHistory(ctx, id, byPool(ctx, id, trimForDisplay(host.history))!!.toString())
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
        PetPrefs.setConvHistory(ctx, id, byPool(ctx, id, host.history)!!.toString())
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
        PetPrefs.setConvHistory(ctx, id, byPool(ctx, id, trimForSave(ctx, id, history))!!.toString())
    }
    // 向历史数组追加一条消息。
    @JvmStatic
    fun push(host: ChatPanel, str: String, str2: String) {
        push(host, str, str2, null)
    }
    @JvmStatic
    fun push(host: ChatPanel, str: String, str2: String, str3: String?) {
        try {
            val jSONObject = JSONObject()
            jSONObject.put("role", str)
            jSONObject.put("content", str2)
            if ("assistant" == str) {
                jSONObject.put("rating", 0)
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
        if ("user" == str) {
            ChatSessions.autoTitleIfNeeded(ctx, id, str2)
        }
        // 上下文总结：开了自动总结且条数到阈值时，异步把最老的一批压成摘要。
        MemSummarizer.maybeAuto(host)
    }
    /**
     * 【独立版】给 ChatPanel 之外的使用者（迷你聊天框）追加一条消息并落盘。
     * 【共用点】saveHistory 内部走 PetPrefs.convHistory(ctx, ChatSessions.currentId(ctx))，
     *   与全屏聊天页同一个键，所以两边的消息会汇入同一份对话。
     * 【取舍】本方法不调 MemSummarizer.maybeAuto：人偶池的迷你框（PetTalk）在
     *   落盘完成后自行调用无 UI 版（PetTalk.maybeAutoSummarize），避免同一次 push 触发两遍。
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
        if ("user" == role) {
            ChatSessions.autoTitleIfNeeded(ctx, id, content)
        }
    }
    /** 纯函数：向历史数组追加一条消息，不落盘。 */
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
        return str.replaceAll("[\\s\\u3000\\u00A0]+", "").trim()
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
                }
                out.put(c)
            } catch (_: Throwable) {
                out.put(o)
            }
        }
        return out
    }
    @JvmStatic
    fun buildRequest(host: ChatPanel): JSONArray {
        // 兼容入口：保留给仍按 host 调用的旧位置。
        return buildRequest(host.getContext(), host.history)
    }
    /**
     * 【通用版】组装一次请求的全部 system 消息（人设 / 好感度 / 思考档位 / 长期记忆 / 学习示例）
     *   + 最近 12 条对话。参数与开关全部读自 PetPrefs，两个入口（全屏页 / 迷你聊天框）行为不会分叉。
     * 【OCR】图片不再以 image_url 上送，改成本地扫成文字拼进正文 —— 于是这里不再需要
     *   「本次请求是否带图」这类回传参数，模型也一律用同一个（普通文本模型即可）。
     */
    @JvmStatic
    fun buildRequest(ctx: Context, history: JSONArray): JSONArray {
        val jSONArray = JSONArray()
        try {
            val affection = PetPrefs.affection(ctx)
            val jSONObject = JSONObject()
            jSONObject.put("role", "system")
            jSONObject.put("content", if (0 != 0) PetPrefs.localSystemPrompt(ctx, affection, PetPrefs.webSearchEnabled(ctx)) else PetPrefs.SYSTEM_PROMPT)
            jSONArray.put(jSONObject)
            // 【设备操作】lamda 服务在跑时，追加一条独立 system 消息，告诉模型她手上还有 device 工具。
            // 【为什么独立一条】人设原文 SYSTEM_PROMPT 属保留内容、一个字不动（与下面「回复格式」同款做法）；
            //   而设备能力是「服务在不在」决定的运行时状态，没法写死在原文里。
            // 【门控】服务没起就不加这段 —— 提示词里写着会用工具、tools 里却没有，模型会瞎编。
            val deviceHint = LamdaManager.devicePrompt()
            if (!deviceHint.isEmpty()) {
                val devObj = JSONObject()
                devObj.put("role", "system")
                devObj.put("content", deviceHint)
                jSONArray.put(devObj)
            }
            if (0 == 0) {
                val jSONObject2 = JSONObject()
                jSONObject2.put("role", "system")
                jSONObject2.put("content", "当前好感度是 " + affection + "（范围 -100~100）。此时此刻的状态：" + PetPrefs.affectionLabel(affection) + "。请以这个状态说话，并在回复第一行输出本次的变动量。")
                jSONArray.put(jSONObject2)
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
            // 【为什么独立一条】人设原文（SYSTEM_PROMPT）属保留内容，一个字不动；
            // 追加独立消息与上面「思考程度」同款做法，两边入口（全屏页 / 迷你框）共用本方法，一并受约束。
            val fmtObj = JSONObject()
            fmtObj.put("role", "system")
            fmtObj.put("content", "【回复格式】你对主人说的话必须写在正文里，正文不能为空；"
                    + "推理、分析、自我检查的过程请放在思考区，既不要混进正文，也不要用思考区代替正文。")
            jSONArray.put(fmtObj)
            appendMemory(ctx, jSONArray)
            appendSummaries(jSONArray, history)
            appendLearnExamples(ctx, jSONArray)
            appendTailMessages(ctx, jSONArray, history)
        } catch (_: Throwable) {
        }
        return jSONArray
    }
    /** 长期记忆注入：AI 自主写入的记忆库正文（上限见 MemDb.MAX_INJECT_CHARS），可在加号面板关闭。 */
    @Throws(Exception::class)
    private fun appendMemory(ctx: Context, arr: JSONArray) {
        // 总记忆库：AI 自主写入的长期记忆，作为额外 system 提示注入（上限 1200 字）。
        // 【开关】加号面板里可以关掉注入：她照旧往库里写，但不再随时「记得」这些事。
        val memory = if (PetPrefs.memInject(ctx)) MemDb.recentText(ctx, MemDb.MAX_INJECT_CHARS) else ""
        if (!memory.isEmpty()) {
            val memObj = JSONObject()
            memObj.put("role", "system")
            memObj.put("content", "【关于主人的长期记忆】\n" + memory + "\n（这些是之前记下的事，回答时可以自然地用到，不要生硬复述。）")
            arr.put(memObj)
        }
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
     * 【OCR】图片不再以 image_url 上送，改成本地扫成文字拼进正文；带图消息有配额（i 初值 2）。
     */
    @Throws(Exception::class)
    private fun appendTailMessages(ctx: Context, arr: JSONArray, history: JSONArray) {
        var i = 2
        var z = false
        var max = Math.max(0, history.length() - (if (0 != 0) 6 else 12))
        while (max < history.length()) {
            val optJSONObject2 = history.optJSONObject(max)
            if (optJSONObject2 != null && "summary" != optJSONObject2.optString("kind")) {
                val optString = optJSONObject2.optString("role")
                val optString2 = optJSONObject2.optString("content")
                val optString3 = optJSONObject2.optString("image", null)
                if (if (optString3 == null || optString3.isEmpty() || "user" != optString || i <= 0 || !ImageStore.exists(ctx, optString3)) z else true) {
                    // 【OCR】图片不再以 image_url 原样上送，改成本地扫成文字拼进正文。
                    //   原链路碰上不支持图片的模型会整条请求失败；换成 text 后任何模型都能看懂。
                    val jSONObject8 = JSONObject()
                    jSONObject8.put("role", optString)
                    jSONObject8.put("content", withOcrCaption(ctx, optString2, optString3))
                    arr.put(jSONObject8)
                    i--
                } else {
                    val jSONObject9 = JSONObject()
                    jSONObject9.put("role", optString)
                    jSONObject9.put("content", optString2)
                    arr.put(jSONObject9)
                }
            }
            max++
            z = false
        }
    }

    /**
     * 【OCR】把图片扫出来的文字拼进要发给模型的正文。
     * 【为什么】图片不再以 image_url 原样上送（不少模型不认这个字段，整条请求会失败），
     *   改为本地 OCR 出文字、当正文发出去后，任何纯文本模型都能看懂图里写了什么。
     * 【还没扫过时】不添乱，原样返回用户写的话（发请求前 OcrEngine 会先补扫）。
     */
    private fun withOcrCaption(ctx: Context, text: String?, imagePath: String): String? {
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
