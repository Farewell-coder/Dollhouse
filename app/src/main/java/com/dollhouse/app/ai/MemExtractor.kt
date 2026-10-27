package com.dollhouse.app.ai

import android.content.Context
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.Mem
import com.dollhouse.app.data.MemLink
import com.dollhouse.app.data.Memory
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】从对话里抽取长期记忆（对齐 Operit 的知识图谱抽取协议）。
 *
 * 【与旧 MemSummarizer 的区别】
 *   · 旧的 = 归档：把最老一批消息压成摘要、**删掉原文**、摘要挂历史头部 —— 一次动几十条，很重。
 *   · 新的 = 图谱：AI 输出 `main/new/update/merge/links/profile_markdown` 六类结构化动作，
 *     **不动任何一条聊天记录**，只往记忆库里增量写条目与边。
 *
 * 【协议来源】Operit `FunctionalPrompts.buildKnowledgeGraphExtractionPrompt`，
 *   本类搬的是它的**语义**（写入前筛选 / 优先 update·merge / 事件式标题 / 严格 JSON），
 *   去掉向量嵌入与文档节点两块（Dollhouse 不做）。
 *
 * 【隔离】所有读写都带 dollId —— 抽取结果只写进「当前会话绑定的那只人偶」的库。
 *   这是本模块的第一原则：A 人偶的经历绝不出现在 B 人偶的记忆里。
 *
 * 【坑】模型偶尔会带 ```json 围栏或前置思考文本，解析前必须清洗；
 *   JSON 里任何字段都可能是 null 或缺失，全部走 opt*。
 */
object MemExtractor {
    private const val TAG = "DollhouseMemExtract"

    /** 单次请求里最多喂多少条历史消息。 */
    private const val HISTORY_LIMIT = 12

    /** 候选记忆最多带几条给模型做去重参考。 */
    private const val CANDIDATE_LIMIT = 12

    /** 抽取结果上限：单轮最多新建几条。 */
    private const val MAX_NEW = 5

    /** 同时在跑的任务标记（防重复抽取）。 */
    private val RUNNING = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 摘要回调：成功给文本，失败给 null（调用方据此给可见提示）。 */
    fun interface Done {
        fun onDone(text: String?)
    }

    @JvmStatic
    fun isRunning(): Boolean {
        return RUNNING.get()
    }

    /**
     * 【插入总结】把一段对话交给模型压成摘要文本（不落库，结果交回调）。
     *
     * 【与 [extract] 的区别】extract 是「结构化抽取」——写记忆库；
     *   本方法是「纯文本摘要」——只产出一段话，由调用方（长按菜单的「插入总结」）
     *   插进对话流当作分割条。两者共用同一个模型与同一个 ProviderStore 三件套，
     *   所以「聊天换了模型，总结也跟着换」。
     *
     * 【为什么要单独一条 prompt】抽取那套要严格 JSON、要筛选门槛；
     *   摘要只需要「说清这段聊了什么」，用 JSON 反而增加解析失败面。
     */
    @JvmStatic
    fun summarize(ctx: Context, dollId: String?, turns: List<Pair<String, String>>, done: Done?) {
        val app = ctx.applicationContext
        if (turns.isEmpty()) {
            done?.onDone(null)
            return
        }
        if (!PetPrefs.hasKey(app)) {
            Logs.i(TAG, "[summarize] 中止：未填 API Key")
            done?.onDone(null)
            return
        }
        val messages = JSONArray()
        try {
            val sys = JSONObject()
            sys.put("role", "system")
            sys.put("content",
                "你要把一段对话压缩成简短摘要，供之后接着聊时回看。\n"
                + "要求：\n"
                + "· 用中文，不超过 200 字；\n"
                + "· 写清「聊了什么、约定了什么、情绪与关系有什么变化」；\n"
                + "· 不要称呼自己为 AI，不要列条目符号，不要写 Markdown 标题；\n"
                + "· 直接输出摘要正文，不要任何前后缀说明。")
            messages.put(sys)
            val usr = JSONObject()
            usr.put("role", "user")
            usr.put("content", transcript(turns))
            messages.put(usr)
        } catch (unused: Throwable) {
        }
        try {
            DeepSeekClient.chat(
                ProviderStore.activeProvider(app),
                PetPrefs.apiKey(app),
                PetPrefs.baseUrl(app),
                PetPrefs.model(app),
                messages,
                1024,
                DeepSeekClient.Callback { str, _ ->
                    if (str == null || str.trim().isEmpty()) {
                        Logs.i(TAG, "[summarize] 模型返回空")
                        done?.onDone(null)
                    } else {
                        done?.onDone(str.trim())
                    }
                })
        } catch (t: Throwable) {
            Logs.w(TAG, "[summarize] 同步异常", t)
            done?.onDone(null)
        }
    }

    /**
     * 从一段对话抽出记忆并落库。
     *
     * @param dollId  目标人偶（记忆只写它的库）。
     * @param turns   本轮对话，元素为 (role, content)，role 为 user / assistant。
     * @param done    结束回调（无论成功失败都会在主线程调一次）。
     */
    @JvmStatic
    fun extract(ctx: Context, dollId: String?, turns: List<Pair<String, String>>, done: Runnable?) {
        val app = ctx.applicationContext
        if (turns.isEmpty()) {
            if (done != null) {
                done.run()
            }
            return
        }
        if (!PetPrefs.hasKey(app)) {
            Logs.i(TAG, "[extract] 中止：未填 API Key")
            if (done != null) {
                done.run()
            }
            return
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Logs.i(TAG, "[extract] 中止：上一次抽取还在跑")
            if (done != null) {
                done.run()
            }
            return
        }
        try {
            val messages = buildMessages(app, dollId, turns)
            DeepSeekClient.chat(
                ProviderStore.activeProvider(app),
                PetPrefs.apiKey(app),
                PetPrefs.baseUrl(app),
                PetPrefs.model(app),
                messages,
                2048,
                DeepSeekClient.Callback { str, _ ->
                    try {
                        if (str == null || str.trim().isEmpty()) {
                            Logs.i(TAG, "[extract] 模型返回空")
                        } else {
                            val applied = apply(app, dollId, str)
                            Logs.i(TAG, "[extract] 完成 doll=$dollId 应用动作数=$applied")
                        }
                    } catch (t: Throwable) {
                        Logs.w(TAG, "[extract] 落库失败", t)
                    } finally {
                        RUNNING.set(false)
                        if (done != null) {
                            done.run()
                        }
                    }
                })
        } catch (t: Throwable) {
            Logs.w(TAG, "[extract] 同步异常", t)
            RUNNING.set(false)
            if (done != null) {
                done.run()
            }
        }
    }

    // ============================ prompt ============================

    private fun buildMessages(ctx: Context, dollId: String?, turns: List<Pair<String, String>>): JSONArray {
        val arr = JSONArray()
        try {
            val sys = JSONObject()
            sys.put("role", "system")
            sys.put("content", systemPrompt(ctx, dollId))
            arr.put(sys)

            val usr = JSONObject()
            usr.put("role", "user")
            usr.put("content", transcript(turns))
            arr.put(usr)
        } catch (unused: Throwable) {
        }
        return arr
    }

    /** 把最近若干轮拼成「主人：…／（人偶名）：…」的纯文本。 */
    private fun transcript(turns: List<Pair<String, String>>): String {
        val recent = if (turns.size > HISTORY_LIMIT) turns.subList(turns.size - HISTORY_LIMIT, turns.size) else turns
        val sb = StringBuilder()
        sb.append("对话：\n")
        for ((role, content) in recent) {
            val who = if ("user" == role) "主人" else "人偶"
            sb.append(who).append("：").append(content.replace('\n', ' ').take(3000)).append('\n')
        }
        return sb.toString()
    }

    /**
     * 系统提示词：移植 Operit 的抽取协议。
     *
     * 【为什么这么多约束】没有筛选门槛的记忆库会变成垃圾桶 ——
     *   常识问答、TODO、寒暄全进去，之后检索全是噪声。
     */
    private fun systemPrompt(ctx: Context, dollId: String?): String {
        val sb = StringBuilder()
        sb.append("你要从对话中构建一只人偶的长期记忆图谱。\n\n")

        // 已有条目（给模型做去重参考）。
        val exist = Mem.list(ctx, dollId)
        if (exist.length() > 0) {
            sb.append("【已有记忆】\n")
            var n = 0
            for (i in exist.length() - 1 downTo 0) {
                if (n >= CANDIDATE_LIMIT) {
                    break
                }
                val o = exist.optJSONObject(i) ?: continue
                sb.append("- \"").append(o.optString("title"))
                    .append("\": ").append(o.optString("content").replace('\n', ' ').take(150))
                    .append("\n")
                n++
            }
        } else {
            sb.append("【已有记忆】暂无。\n")
        }
        sb.append('\n')

        // 已有文件夹（让模型归类到既有目录，而不是每次造新目录）。
        val folders = Mem.allFolders(ctx, dollId)
        if (folders.isNotEmpty()) {
            sb.append("【已有文件夹】").append(folders.joinToString("、")).append("\n\n")
        }

        sb.append(
            """
【写入前先过筛】
- 只记录"用户特异且可复用"的信息：稳定偏好、约束、已确认决策、反复错误、项目事实、长期设定。
- 不记录常识/公开定义（如"TS 是什么""Node 是什么"）。
- 不记录未来推测项：下一步建议、TODO、暂定计划。
- 若没有长期价值信号，直接返回 {}。
【抽取策略】
- 提供的已有记忆只是检索线索，不是事实证据；只有对话明确证明主体和事实相同时才可 update、merge 或连边。
- 优先 update / merge，其次才是 new。
- new 仅在确实新增概念时使用（最多 5 条）。
- 若核心是"更新旧概念"，main 必须为 null，只用 update。
- 如果只是对已有记忆的改写（同主体 + 同动作 + 同结果），按重复处理。
- 涉及主人本人的稳定事实（称呼、身份、偏好、家人与宠物、长期约定）优先用 update 更新资料文档。
【标题与内容写法】
- main 标题优先写事件，不写定义。
- 推荐标题模板：事件：`[领域] 动作 + 结果`；实体：`实体：名称（身份/类型）`。
- 不推荐标题：`X是什么`、`X的定义`、百科式泛标题。
- 内容只写"已发生事实 + 当前已确认状态"，禁止写未来动作、TODO、推测性计划。
【连接关系规则】
- 只有当对话里有明确证据时才建边。
- 推荐关系类型：事件流程 FOLLOWS、CORRECTS、UPDATES；参与上下文 INVOLVES、HAPPENS_AT；结构 PART_OF、ALLIED_WITH、OPPOSES。
- 不能仅凭"同段提到过"就连边。拿不准就不连。
【输出格式 - 严格 JSON，不要任何多余文字】
- 除 {} 外，必须包含 main、new、update、merge、links、profile_markdown 六个键。
- main: null 或 {"title":"...","content":"...","tags":["..."],"folder_path":"..."}
- new: [{"title":"...","content":"...","tags":["..."],"folder_path":"...","importance":0.5,"credibility":0.5}]
- update: [{"title":"要更新的已有记忆标题","content":"新的完整内容","reason":"...","importance":null,"credibility":null}]
- merge: [{"source_titles":["A","B"],"title":"合并后标题","content":"合并后内容","tags":["..."],"folder_path":"...","reason":"..."}]
- links: [{"source":"标题A","target":"标题B","type":"UPPER_SNAKE_CASE","description":"...","weight":0.5}]
- profile_markdown: 关于主人的画像文档（Markdown），没有更新时用 null。要求：只写稳定事实，保留已有内容，返回完整替换文档。
- importance / credibility / weight 是 0.0~1.0 的数字；没有就给 null 或省略。
请只返回一个合法的 JSON 对象。
""".trimIndent()
        )

        // 用户自定义规则（设置页可写）。
        val rule = PetPrefs.memExtractRule(ctx).trim()
        if (rule.isNotEmpty()) {
            sb.append("\n【用户指定的记忆提取附加规则】\n")
            sb.append(rule)
            sb.append("\n使用这些规则细化记忆领域、入库重点、文件夹、标签或写法。写入前筛选与严格 JSON 输出协议仍然必须遵守。\n")
        }
        return sb.toString()
    }

    // ============================ 解析与应用 ============================

    /**
     * 把模型输出落库。返回应用成功的动作数（0 表示没有值得记的内容）。
     *
     * 【为什么返回计数】调用方（调试日志 / 手动整理）需要知道「是真的没内容」还是「解析失败」。
     */
    @JvmStatic
    fun apply(ctx: Context, dollId: String?, raw: String?): Int {
        val json = cleanJson(raw)
        if (json.isEmpty()) {
            return 0
        }
        val root = try {
            JSONObject(json)
        } catch (unused: Throwable) {
            Logs.w(TAG, "[apply] JSON 解析失败，原文长度=" + (raw?.length ?: 0), null)
            return 0
        }
        var count = 0
        try {
            // ① merge：先合并，之后 update 才有正确的目标
            val merges = root.optJSONArray("merge")
            if (merges != null) {
                for (i in 0 until merges.length()) {
                    val o = merges.optJSONObject(i) ?: continue
                    if (applyMerge(ctx, dollId, o)) {
                        count++
                    }
                }
            }
            // ② update
            val updates = root.optJSONArray("update")
            if (updates != null) {
                for (i in 0 until updates.length()) {
                    val o = updates.optJSONObject(i) ?: continue
                    if (applyUpdate(ctx, dollId, o)) {
                        count++
                    }
                }
            }
            // ③ new
            val news = root.optJSONArray("new")
            if (news != null) {
                var added = 0
                for (i in 0 until news.length()) {
                    if (added >= MAX_NEW) {
                        break
                    }
                    val o = news.optJSONObject(i) ?: continue
                    if (applyNew(ctx, dollId, o, Memory.SOURCE_AUTO)) {
                        added++
                        count++
                    }
                }
            }
            // ④ main：单独一条新记忆
            val main = root.optJSONObject("main")
            if (main != null && applyNew(ctx, dollId, main, Memory.SOURCE_AUTO)) {
                count++
            }
            // ⑤ links
            val linksArr = root.optJSONArray("links")
            if (linksArr != null) {
                for (i in 0 until linksArr.length()) {
                    val o = linksArr.optJSONObject(i) ?: continue
                    val l = MemLink()
                    l.source = o.optString("source", "").trim()
                    l.target = o.optString("target", "").trim()
                    l.type = normalizeType(o.optString("type", "RELATED"))
                    l.weight = o.optDouble("weight", 0.5).toFloat().coerceIn(0f, 1f)
                    l.description = o.optString("description", "")
                    if (Mem.addLink(ctx, dollId, l)) {
                        count++
                    }
                }
            }
            // ⑥ profile_markdown：整份替换
            val profile = root.optString("profile_markdown", "")
            if (profile.isNotEmpty() && "null" != profile) {
                Mem.setProfile(ctx, dollId, profile)
                count++
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "[apply] 应用时异常", t)
        }
        return count
    }

    private fun applyNew(ctx: Context, dollId: String?, o: JSONObject, source: String): Boolean {
        val title = o.optString("title", "").trim()
        val content = o.optString("content", "").trim()
        if (title.isEmpty() && content.isEmpty()) {
            return false
        }
        // 同标题已存在时并入旧的，避免同一件事记两遍。
        val old = Mem.findByTitle(ctx, dollId, title)
        if (old != null && title.isNotEmpty()) {
            return Mem.update(ctx, dollId, old.id) { this.content = content }
        }
        val m = Memory()
        m.title = title
        m.content = content
        m.source = source
        m.folderPath = o.optString("folder_path", "").trim()
        m.importance = o.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f)
        m.credibility = o.optDouble("credibility", 0.5).toFloat().coerceIn(0f, 1f)
        m.originDoll = dollId ?: ""
        val tags = o.optJSONArray("tags")
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val s = tags.optString(i).trim()
                if (s.isNotEmpty()) {
                    m.tags.add(s)
                }
            }
        }
        return Mem.add(ctx, dollId, m)
    }

    private fun applyUpdate(ctx: Context, dollId: String?, o: JSONObject): Boolean {
        val title = o.optString("title", "").trim()
        if (title.isEmpty()) {
            return false
        }
        val target = Mem.findByTitle(ctx, dollId, title) ?: return false
        val content = o.optString("content", "").trim()
        val imp = o.optDouble("importance", -1.0)
        val cred = o.optDouble("credibility", -1.0)
        return Mem.update(ctx, dollId, target.id) {
            if (content.isNotEmpty()) {
                this.content = content
            }
            if (imp >= 0.0) {
                this.importance = imp.toFloat().coerceIn(0f, 1f)
            }
            if (cred >= 0.0) {
                this.credibility = cred.toFloat().coerceIn(0f, 1f)
            }
        }
    }

    private fun applyMerge(ctx: Context, dollId: String?, o: JSONObject): Boolean {
        val srcArr = o.optJSONArray("source_titles") ?: return false
        val sources = ArrayList<String>()
        for (i in 0 until srcArr.length()) {
            val s = srcArr.optString(i).trim()
            if (s.isNotEmpty()) {
                sources.add(s)
            }
        }
        if (sources.isEmpty()) {
            return false
        }
        val title = o.optString("title", "").trim()
        val content = o.optString("content", "").trim()
        if (title.isEmpty()) {
            return false
        }
        // 新建合并结果 → 删掉被合并的旧条目 → 迁走它们的边。
        val m = Memory()
        m.title = title
        m.content = content
        m.source = Memory.SOURCE_AUTO
        m.folderPath = o.optString("folder_path", "").trim()
        m.originDoll = dollId ?: ""
        val tags = o.optJSONArray("tags")
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val s = tags.optString(i).trim()
                if (s.isNotEmpty()) {
                    m.tags.add(s)
                }
            }
        }
        if (!Mem.add(ctx, dollId, m)) {
            return false
        }
        val links = Mem.links(ctx, dollId)
        for (src in sources) {
            val old = Mem.findByTitle(ctx, dollId, src) ?: continue
            Mem.remove(ctx, dollId, old.id)
            // 边重定向：把指向旧标题的端点改指新标题，避免合并后关系断掉。
            Mem.removeLinksOfTitle(ctx, dollId, src)
            redirectLinks(ctx, dollId, links, src, title)
        }
        return true
    }

    /** 把 [links] 里所有 [from] 端点改写成 [to]，然后整体写回。 */
    private fun redirectLinks(ctx: Context, dollId: String?, links: JSONArray, from: String, to: String) {
        try {
            val out = JSONArray()
            var changed = false
            for (i in 0 until links.length()) {
                val o = links.optJSONObject(i) ?: continue
                var s = o.optString("source")
                var t = o.optString("target")
                if (from == s) {
                    s = to
                    changed = true
                }
                if (from == t) {
                    t = to
                    changed = true
                }
                if (s == t || s.isEmpty() || t.isEmpty()) {
                    continue
                }
                val n = JSONObject(o.toString())
                n.put("source", s)
                n.put("target", t)
                out.put(n)
            }
            if (changed) {
                PetPrefs.setMem2Links(ctx, dollId, out.toString())
            }
        } catch (unused: Throwable) {
        }
    }

    private fun normalizeType(raw: String?): String {
        val t = (raw ?: "").trim().uppercase()
        return if (t.isEmpty()) "RELATED" else t.replace(' ', '_')
    }

    /** 剥掉 ``` 围栏与前后杂字，取出最外层 JSON 对象。 */
    private fun cleanJson(raw: String?): String {
        var s = (raw ?: "").trim()
        if (s.isEmpty()) {
            return ""
        }
        // 去围栏
        val fence = s.indexOf("```")
        if (fence >= 0) {
            val bodyStart = s.indexOf('\n', fence)
            if (bodyStart > 0) {
                val end = s.lastIndexOf("```")
                if (end > bodyStart) {
                    s = s.substring(bodyStart + 1, end).trim()
                }
            }
        }
        // 取第一个 { 到最后一个 }
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) {
            return ""
        }
        return s.substring(a, b + 1)
    }
}