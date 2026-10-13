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

    /** 新建一条记忆。title 与 content 至少一个非空才写入。 */
    @JvmStatic
    fun add(ctx: Context, dollId: String?, m: Memory): Boolean {
        if (m.title.trim().isEmpty() && m.content.trim().isEmpty()) {
            return false
        }
        return try {
            var arr = list(ctx, dollId)
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
                if (id != o.optString("id")) {
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
                if (id != o.optString("id")) {
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
                val id = o.optString("id")
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
            if (id == o.optString("id")) {
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
