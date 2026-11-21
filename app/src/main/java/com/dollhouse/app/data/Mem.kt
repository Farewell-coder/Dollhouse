package com.dollhouse.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】新版记忆库（对齐 Operit 的记忆空间模型）。
 *
 * 【与旧 MemDb 的区别】
 *   · 旧版：只有 title / text 两个字段，扁平列表，无检索、无关联、无归档资料。
 *   · 新版：完整字段（来源 / 重要性 / 可信度 / 文件夹 / 标签 / 时间）+ 记忆连接（图谱边）
 *     + 记忆空间资料（一份 Markdown 用户画像，每轮随对话自动更新）。
 *
 * 【人偶隔离】所有方法都必须传 dollId，底层键名分别是
 *   `mem2_list_<dollId>` / `mem2_links_<dollId>` / `mem2_profile_<dollId>`。
 *   每只人偶一份独立记忆库，互不可见 —— 这是本模块的第一原则。
 *
 * 【为什么不用数据库】工程零 ORM 依赖，且单只人偶条目量级在数百条以内，
 *   全量 JSON 重写的成本可忽略；引入 Room/ObjectBox 属于负收益。
 *
 * 【坑】条目用 JSONArray 全量存偏好，任何写操作都要先 list → 改 → save；
 *   注入 system 提示时有字数上限，否则记忆一多会把上下文吃光（见 [injectText]）。
 */
object Mem {
    /** 注入 system 提示时的最大字符数。 */
    const val MAX_INJECT_CHARS = 1200

    /** 单只人偶的记忆条数上限：超出丢最旧的（保留最近写入的）。 */
    const val MAX_ENTRIES = 200

    /** 记忆连接的条数上限。 */
    const val MAX_LINKS = 400

    // ============================ 记忆条目 ============================

    /** 某只人偶的全部记忆条目（磁盘原序，最旧的在前）。 */
    @JvmStatic
    fun list(ctx: Context, dollId: String?): JSONArray {
        return try {
            JSONArray(PetPrefs.mem2List(ctx, dollId))
        } catch (unused: Throwable) {
            JSONArray()
        }
    }

    private fun save(ctx: Context, dollId: String?, arr: JSONArray) {
        PetPrefs.setMem2List(ctx, dollId, arr.toString())
    }

    /**
     * 新建一条记忆。title 与 content 至少一个非空才写入。
     *
     * ## 【坑·返回值语义】`true` 只表示「没抛异常」，**不代表用户能看到这条记忆**
     * 追加后若超过 [MAX_ENTRIES]，本方法会**砍掉最旧的那批**并仍然返回 `true`。
     * 日常使用下这是对的（新记忆更重要），但任何**需要知道"这条到底还在不在"**的场景
     * 都不能拿返回值当证据 —— 典型反例是备份恢复：备份 300 条、上限 200 条时，
     * 第 201 条起每条都「写进去就被砍」却照样返回 true，于是摘要报「新增 300 条」，
     * 盘上只有 200 条，而且丢的恰是最早那批（时间最久、情感价值最高）。
     *
     * 所以 `AllInOneArchive` 恢复记忆时是**写入前先查条数**（`Mem.list().length() >= MAX_ENTRIES`
     * 就不写并单独计数），不依赖本方法的返回值。见该文件「记忆」段注释。
     *
     * 【为什么不改成"被砍就返回 false"】本方法被多处调用，改语义会让那些地方
     * 把「正常淘汰旧记忆」误判成失败。契约保持不变，把判断责任交给调用方。
     */
    @JvmStatic
    fun add(ctx: Context, dollId: String?, m: Memory): Boolean {
        if (m.title.trim().isEmpty() && m.content.trim().isEmpty()) {
            return false
        }
        // 【空 id 直接拒】id 是 UI 列表的 LazyColumn key（MemPage 的 items(key = { it.id })），
        //   空 id 会让任意两条撞成同一个 key → 整页崩。
        //
        //   【现在还有谁能走到这里】`Memory.fromJson` 已经把「存档里 id 为空串」兜成
        //   确定性的派生 id（Memory.idFrom），所以反序列化路径不会再产生空 id。
        //   这条判断现在是**纯防御**：挡的是「外部直接构造 Memory 又把 id 清空」这类
        //   未来可能出现的调用（Memory() 的字段初始值是 newId()，正常用法不会为空）。
        //   保留它是因为代价是一次字符串判空，而代价的另一侧是整页白屏。
        if (m.id.isEmpty()) {
            return false
        }
        return try {
            var arr = list(ctx, dollId)
            // 【同 id 覆盖而不是追加】id 在本工程里语义就是主键：
            //   `Mem.update`（:91）按 id 改、`Mem.remove`（:147）按 id 删，
            //   两条同 id 的记录会让这两个操作只命中第一条，另一条从此改不掉也删不掉。
            //   何况 UI 的 LazyColumn key 就是 id —— 两条同 id 必崩。
            //
            //   【为什么选"覆盖"而不是"重新发号"】与 update 的语义一致（一 id 一条），
            //   条数不变 → 不会触发 MAX_ENTRIES 淘汰，也不会让写一条挤掉另一条。
            //   若选"重新发号"，就会出现两条内容相同、只是 id 不同的记忆 —— 对用户是纯噪音。
            //
            //   【谁会真的用到覆盖分支】备份恢复（AllInOneArchive）在调用本方法**之前**
            //   已经按标题与 id 双重跳过（它的既有策略是"本机已有就不动"，见该文件），
            //   所以正常恢复走不到这里。覆盖分支真正保护的是**两条路径**：
            //   ① 任何绕过预检的写入（将来的导入、批量工具、第三方接入）；
            //   ② 并发/交错写入把同一 id 写了两遍的存档 —— 再写一次时收敛成一条，而不是变三条。
            //   换句话说：覆盖是"数据层兜底"，恢复侧的跳过是"业务层策略"，两者不冲突。
            //
            //   【为什么不会误伤正常新增】其余 8 处调用点（MemPage 三处手写、createFolder、
            //   导入文件、ChatPanel、MemoryTool、MemExtractor 两处）都是 `Memory()` 现造对象，
            //   id 由 newId() 生成，不会命中已有记录 —— 覆盖分支对它们零影响。
            for (i in 0 until arr.length()) {
                // 【必须用 Memory.idFrom 而不是 optString("id")】空串 id 的条目会被
                //   UI 读成派生 id（见 Memory.idFrom）；这里若仍按存档原值比较，
                //   那条记忆就"能看不能改"—— 覆盖分支永远命中不了它。
                if (m.id == Memory.idFrom(arr.optJSONObject(i))) {
                    arr.put(i, m.toJson())
                    save(ctx, dollId, arr)
                    return true
                }
            }
            arr.put(m.toJson())
            if (arr.length() > MAX_ENTRIES) {
                val cut = JSONArray()
                for (i in arr.length() - MAX_ENTRIES until arr.length()) {
                    cut.put(arr.opt(i))
                }
                arr = cut
            }
            save(ctx, dollId, arr)
            true
        } catch (unused: Throwable) {
            false
        }
    }

    /** 按 id 覆盖一条（保留原 id 与 createdAt）。返回是否命中。 */
    @JvmStatic
    fun update(ctx: Context, dollId: String?, id: String?, mut: Memory.() -> Unit): Boolean {
        if (id.isNullOrEmpty()) {
            return false
        }
        return try {
            val arr = list(ctx, dollId)
            var hit = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // 【统一走 idFrom】UI 传进来的 id 来自 Memory.idFrom（列表/详情）；
                //   这里若比存档原值，空 id 条目会永远匹配不上（改不动）。
                if (id != Memory.idFrom(o)) {
                    continue
                }
                val m = Memory.fromJson(o)
                m.mut()
                m.updatedAt = System.currentTimeMillis()
                arr.put(i, m.toJson())
                hit = true
                break
            }
            if (hit) {
                save(ctx, dollId, arr)
            }
            hit
        } catch (unused: Throwable) {
            false
        }
    }

    /**
     * 【r13】整表回写（供「文件夹改名 / 删除」这类**批量**改动使用）。
     *
     * 【为什么需要它】[update] 一次只改一条，而「文件夹改名」要同时改它下面所有条目 ——
     *   逐条 update 会写 N 次盘（每次都是全量 JSON 序列化），100 条就是 100 次重写。
     *   这里一次性落盘，成本从 O(N²) 降到 O(N)。
     *
     * 【边界】调用方负责保证数组结构合法（从 [list] 拿出、改字段、再交回来）。
     *   条数上限同样在这里兜一次，避免调用方绕过 [add] 的限制。
     */
    @JvmStatic
    fun replaceAll(ctx: Context, dollId: String?, arr: JSONArray?) {
        try {
            var out = arr ?: JSONArray()
            if (out.length() > MAX_ENTRIES) {
                val cut = JSONArray()
                for (i in out.length() - MAX_ENTRIES until out.length()) {
                    cut.put(out.opt(i))
                }
                out = cut
            }
            save(ctx, dollId, out)
        } catch (unused: Throwable) {
        }
    }

    /** 按 id 删除。 */
    @JvmStatic
    fun remove(ctx: Context, dollId: String?, id: String?) {
        if (id.isNullOrEmpty()) {
            return
        }
        try {
            val arr = list(ctx, dollId)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // 同 update：按 idFrom 比较，保证空 id 条目也删得掉。
                if (id != Memory.idFrom(o)) {
                    out.put(o)
                }
            }
            save(ctx, dollId, out)
            // 连带清掉指向它的边，避免图谱里出现悬空节点。
            removeLinksOfTitle(ctx, dollId, titleOf(arr, id))
        } catch (unused: Throwable) {
        }
    }

    /** 批量删除（多选勾选后使用）。 */
    @JvmStatic
    fun removeMany(ctx: Context, dollId: String?, ids: Collection<String>) {
        if (ids.isEmpty()) {
            return
        }
        try {
            val set = ids.toHashSet()
            val arr = list(ctx, dollId)
            val out = JSONArray()
            val gone = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = Memory.idFrom(o)
                if (set.contains(id)) {
                    gone.add(o.optString("title"))
                } else {
                    out.put(o)
                }
            }
            save(ctx, dollId, out)
            for (t in gone) {
                removeLinksOfTitle(ctx, dollId, t)
            }
        } catch (unused: Throwable) {
        }
    }

    /** 清空某只人偶的记忆（条目 + 连接；资料文档一并清）。 */
    @JvmStatic
    fun clear(ctx: Context, dollId: String?) {
        save(ctx, dollId, JSONArray())
        saveLinks(ctx, dollId, JSONArray())
        setProfile(ctx, dollId, "")
    }

    private fun titleOf(arr: JSONArray, id: String?): String {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (id == Memory.idFrom(o)) {
                return o.optString("title")
            }
        }
        return ""
    }

    /** 按标题精确查找（AI 抽取时定位 update 目标）。 */
    @JvmStatic
    fun findByTitle(ctx: Context, dollId: String?, title: String?): Memory? {
        if (title.isNullOrEmpty()) {
            return null
        }
        val arr = list(ctx, dollId)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (title == o.optString("title")) {
                return Memory.fromJson(o)
            }
        }
        return null
    }

    /** 按 id 精确查找。id 是主键（update/remove 都按它定位），查找语义与 findByTitle 并列。 */
    @JvmStatic
    fun findById(ctx: Context, dollId: String?, id: String?): Memory? {
        if (id.isNullOrEmpty()) {
            return null
        }
        val arr = list(ctx, dollId)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (id == Memory.idFrom(o)) {
                return Memory.fromJson(o)
            }
        }
        return null
    }

    /** 全部文件夹路径（去重、排序）。 */
    @JvmStatic
    fun allFolders(ctx: Context, dollId: String?): List<String> {
        val arr = list(ctx, dollId)
        val set = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val f = o.optString("folderPath", "")
            if (f.isNotEmpty()) {
                set.add(f)
            }
        }
        val out = ArrayList(set)
        out.sort()
        return out
    }

    // ============================ 记忆连接（图谱边） ============================

    @JvmStatic
    fun links(ctx: Context, dollId: String?): JSONArray {
        return try {
            JSONArray(PetPrefs.mem2Links(ctx, dollId))
        } catch (unused: Throwable) {
            JSONArray()
        }
    }

    private fun saveLinks(ctx: Context, dollId: String?, arr: JSONArray) {
        PetPrefs.setMem2Links(ctx, dollId, arr.toString())
    }

    /** 加一条连接；source / target 相同或重复时跳过。 */
    @JvmStatic
    /**
     * 加一条记忆连接。同源同目标同类型已存在时返回 false（去重）。
     *
     * 【坑·同 [add]】超过 [MAX_LINKS] 时同样会砍最旧的并返回 `true`。
     * 恢复侧因此也是**写入前先查条数**，不拿返回值当"这条还在"的证据。
     */
    fun addLink(ctx: Context, dollId: String?, link: MemLink): Boolean {
        if (link.source.isEmpty() || link.target.isEmpty() || link.source == link.target) {
            return false
        }
        return try {
            var arr = links(ctx, dollId)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("source") == link.source
                    && o.optString("target") == link.target
                    && o.optString("type") == link.type
                ) {
                    return false
                }
            }
            arr.put(link.toJson())
            if (arr.length() > MAX_LINKS) {
                val cut = JSONArray()
                for (i in arr.length() - MAX_LINKS until arr.length()) {
                    cut.put(arr.opt(i))
                }
                arr = cut
            }
            saveLinks(ctx, dollId, arr)
            true
        } catch (unused: Throwable) {
            false
        }
    }

    /** 删掉所有以该标题为端点的边。 */
    @JvmStatic
    fun removeLinksOfTitle(ctx: Context, dollId: String?, title: String?) {
        if (title.isNullOrEmpty()) {
            return
        }
        try {
            val arr = links(ctx, dollId)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (title != o.optString("source") && title != o.optString("target")) {
                    out.put(o)
                }
            }
            saveLinks(ctx, dollId, out)
        } catch (unused: Throwable) {
        }
    }

    // ============================ 记忆空间资料（用户画像） ============================

    /** 该球记忆空间的 Markdown 资料文档；没有时返回空串。 */
    @JvmStatic
    fun profile(ctx: Context, dollId: String?): String {
        return PetPrefs.mem2Profile(ctx, dollId)
    }

    @JvmStatic
    fun setProfile(ctx: Context, dollId: String?, markdown: String?) {
        PetPrefs.setMem2Profile(ctx, dollId, markdown ?: "")
    }

    // ============================ 注入 ============================

    /**
     * 拼出注入用正文：资料文档 + 最近的若干条记忆。
     *
     * 【顺序】资料在前（它是稳定画像，优先级高），条目在后（按时间倒序）。
     * 【上限】总数不超过 maxChars；资料本身就超限时只截资料。
     */
    @JvmStatic
    fun injectText(ctx: Context, dollId: String?, maxChars: Int): String {
        val sb = StringBuilder()
        val doc = profile(ctx, dollId)
        if (doc.isNotEmpty()) {
            sb.append("【关于主人的印象】\n")
            sb.append(doc)
            sb.append('\n')
        }
        val arr = list(ctx, dollId)
        if (arr.length() > 0) {
            val head = sb.length
            val body = StringBuilder()
            for (i in arr.length() - 1 downTo 0) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title", "")
                val text = o.optString("content", "")
                val line = if (title.isEmpty()) text else title + "：" + text
                if (sb.length + body.length + line.length + 1 > maxChars) {
                    break
                }
                if (body.length > 0) {
                    body.append('\n')
                }
                body.append(line)
            }
            if (body.length > 0) {
                sb.append("【记下的事】\n")
                sb.append(body)
            }
            if (sb.length <= head) {
                return ""
            }
        }
        return sb.toString()
    }
}
