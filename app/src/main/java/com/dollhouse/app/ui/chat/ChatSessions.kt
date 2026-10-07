package com.dollhouse.app.ui.chat

import android.content.Context
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】多会话管理：两个会话池（self 自聊 / pet 人偶）的增删改查、当前会话与当前池的切换。
 *
 * 【入口】ChatHistoryStore.loadHistory / saveHistory（取当前会话 id）、ChatDrawer（列表与操作）、
 *        PetTalk（人偶池落点）。
 *
 * 【交互】清单统一存 conv_index，每条带 pool 字段（"self" / "pet"）区分池；
 *        每个会话的历史存 conv_&lt;id&gt;；当前会话 id 分池存：self → conv_current、pet → conv_current_pet；
 *        当前处于哪个池存 conv_pool。历史内容本身仍由 ChatPanel.history 持有，本类只搬 id 与清单。
 *
 * 【池的作用】人偶池的 assistant 回复落库时压成单行（见 ChatHistoryStore 的 byPool），自聊池原样保留；
 *        两池共用同一个记忆库（MemStore），自动压缩按各自会话 id 归档。
 *
 * 【坑】旧版单会话数据放在 chat_history 键里，首次调用 ensure() 时会迁移一次；
 *        迁完不再写回 chat_history，避免两份数据打架。
 *        第一版的人偶会话 id 固定为 "pet" 且没有 pool 字段，ensurePet() 里就地改记为 pet 池，
 *        否则它会被当成自聊池的会话（老的 pet 会话会跑到自聊列表里）。
 */
object ChatSessions {
    /** 自聊池：用户自己聊的，回复不套正则。 */
    const val POOL_SELF = "self"

    /** 人偶池：与木偶聊的，回复压成单行。 */
    const val POOL_PET = "pet"

    /** 【v0.0.1】人偶池的种子会话 id：池里一条都没有时补建，作为 PetTalk 的兜底落点。 */
    const val PET_ID = "pet"

    // ==================== 池标记 ====================

    /** 当前处于哪个池（默认 self）。 */
    @JvmStatic
    fun pool(ctx: Context): String {
        return if (POOL_PET == PetPrefs.convPool(ctx)) POOL_PET else POOL_SELF
    }

    /** 切换当前池（各自池里的当前会话 id 不受影响）。 */
    @JvmStatic
    fun setPool(ctx: Context, p: String) {
        PetPrefs.setConvPool(ctx, if (POOL_PET == p) POOL_PET else POOL_SELF)
    }

    /** 当前是否处于人偶池（供 ChatPanel 决定要不要让人偶头顶说话）。 */
    @JvmStatic
    fun isPet(ctx: Context): Boolean {
        return POOL_PET == pool(ctx)
    }

    /** 清单项属于哪个池（没有 pool 字段的历史数据一律视为 self）。 */
    @JvmStatic
    fun poolOf(o: JSONObject?): String {
        return if (o != null && POOL_PET == o.optString("pool", POOL_SELF)) POOL_PET else POOL_SELF
    }


    // ==================== 清单 ====================

    /** 完整清单（两个池都在）。 */
    @JvmStatic
    fun listAll(ctx: Context): JSONArray {
        return try {
            JSONArray(PetPrefs.convIndex(ctx))
        } catch (unused: Throwable) {
            JSONArray()
        }
    }

    private fun saveAll(ctx: Context, arr: JSONArray?) {
        PetPrefs.setConvIndex(ctx, if (arr == null) "[]" else arr.toString())
    }

    /** 当前池的清单（抽屉列表只显示这一个池）。 */
    @JvmStatic
    fun list(ctx: Context): JSONArray {
        return listOf(listAll(ctx), pool(ctx))
    }

    /** 从完整清单里过滤出某个池的子集。 */
    @JvmStatic
    fun listOf(all: JSONArray?, p: String): JSONArray {
        val out = JSONArray()
        if (all == null) {
            return out
        }
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i)
            if (o != null && p == poolOf(o)) {
                out.put(o)
            }
        }
        return out
    }

    /** 用 arr 替换「当前池」的清单项，另一个池的项原样保留。 */
    @JvmStatic
    fun save(ctx: Context, arr: JSONArray?) {
        val out = JSONArray()
        val all = listAll(ctx)
        val p = pool(ctx)
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i)
            if (o != null && p != poolOf(o)) {
                out.put(o)
            }
        }
        var i = 0
        while (arr != null && i < arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null) {
                try {
                    o.put("pool", p)
                } catch (unused: Throwable) {
                }
                out.put(o)
            }
            i++
        }
        saveAll(ctx, out)
    }

    /** 跨池按 id 找清单项。 */
    @JvmStatic
    fun find(ctx: Context, id: String?): JSONObject? {
        val arr = listAll(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null && id != null && id == o.optString("id")) {
                return o
            }
        }
        return null
    }

    private fun firstOf(arr: JSONArray?): JSONObject? {
        var i = 0
        while (arr != null && i < arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null) {
                return o
            }
            i++
        }
        return null
    }

    // ==================== 当前会话（分池） ====================

    private fun getCurrent(ctx: Context, p: String): String {
        return if (POOL_PET == p) PetPrefs.convCurrentPet(ctx) else PetPrefs.convCurrent(ctx)
    }

    private fun setCurrent(ctx: Context, p: String, id: String) {
        if (POOL_PET == p) {
            PetPrefs.setConvCurrentPet(ctx, id)
        } else {
            PetPrefs.setConvCurrent(ctx, id)
        }
    }

    /** 当前池的当前会话 id（保证可用）。 */
    @JvmStatic
    fun currentId(ctx: Context): String {
        return ensure(ctx)
    }

    /**
     * 【v0.0.1】id 所属池的当前会话 id；无副作用（不触发 ensure / 不写回任何键）。
     * 【为什么需要】两池拆分后 currentId(ctx) 只代表「当前池」：人偶池的会话在
     *   当前池为 self 时会被误判成「已切换」；反过来若拿 currentId 当锚点，
     *   人偶池压缩就会把摘要/暂存写到 self 池名下（静默串池）。
     *   MemSummarizer 的守卫①用它核对锚定会话。
     * 【返回 null】id 不存在（被删）或池内一条都没有时，调用方应放弃。
     */
    @JvmStatic
    fun currentOfPool(ctx: Context, id: String?): String? {
        val o = find(ctx, id)
        if (o == null) {
            return null
        }
        val p = poolOf(o)
        val cur = getCurrent(ctx, p)
        if (cur != null && !cur.isEmpty()) {
            val co = find(ctx, cur)
            if (co != null && p == poolOf(co)) {
                return cur
            }
        }
        val f = firstOf(listOf(listAll(ctx), p))
        return if (f == null) null else f.optString("id")
    }

    /** 切到指定会话；若它属于另一个池，连同池标记一起切。 */
    @JvmStatic
    fun switchTo(ctx: Context, id: String) {
        val o = find(ctx, id)
        if (o == null) {
            return
        }
        val p = poolOf(o)
        setPool(ctx, p)
        setCurrent(ctx, p, id)
    }

    // ==================== 新建 ====================

    /** 在指定池里新建会话（不动当前池与当前会话）。 */
    @JvmStatic
    fun createIn(ctx: Context, p: String, prefix: String, title: String): String {
        val base = prefix + System.currentTimeMillis()
        var id = base
        var n = 1
        while (find(ctx, id) != null && n < 50) {
            id = base + "_" + n
            n++
        }
        try {
            val all = listAll(ctx)
            val o = JSONObject()
            o.put("id", id)
            o.put("title", title)
            o.put("pool", if (POOL_PET == p) POOL_PET else POOL_SELF)
            o.put("updated", System.currentTimeMillis())
            all.put(o)
            saveAll(ctx, all)
            PetPrefs.setConvHistory(ctx, id, "[]")
        } catch (unused: Throwable) {
        }
        return id
    }

    /** 在当前池新建会话并切过去，返回新 id。 */
    @JvmStatic
    fun create(ctx: Context): String {
        val p = pool(ctx)
        val id = createIn(ctx, p, if (POOL_PET == p) "p" else "c", "新的对话")
        setCurrent(ctx, p, id)
        return id
    }

    // ==================== 人偶池种子 ====================

    /**
     * 保证人偶池里至少有一条会话，返回人偶池的当前会话 id；不改变当前池。
     * 【迁移】第一版的人偶会话 id 固定为 "pet" 且没有 pool 字段，这里就地把它改记为 pet 池。
     */
    @JvmStatic
    fun ensurePet(ctx: Context): String {
        val all = listAll(ctx)
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i)
            if (o != null && PET_ID == o.optString("id")
                && POOL_PET != o.optString("pool")) {
                try {
                    o.put("pool", POOL_PET)
                    saveAll(ctx, all)
                    Logs.i("DollhouseMemo", "[ensurePet] 旧 pet 会话已迁入人偶池")
                } catch (unused: Throwable) {
                }
                break
            }
        }
        val first = firstOf(listOf(listAll(ctx), POOL_PET))
        if (first == null) {
            val id: String
            if (find(ctx, PET_ID) != null) {
                // 该 id 已被别的池占用（理论上不该发生）：换个带池前缀的新 id。
                id = createIn(ctx, POOL_PET, "p", "人偶")
            } else {
                id = PET_ID
                try {
                    val arr = listAll(ctx)
                    val o = JSONObject()
                    o.put("id", id)
                    o.put("title", "人偶")
                    o.put("pool", POOL_PET)
                    o.put("updated", System.currentTimeMillis())
                    arr.put(o)
                    saveAll(ctx, arr)
                } catch (unused: Throwable) {
                }
            }
            PetPrefs.setConvCurrentPet(ctx, id)
            return id
        }
        val cur = PetPrefs.convCurrentPet(ctx)
        if (cur != null && !cur.isEmpty() && find(ctx, cur) != null
            && POOL_PET == poolOf(find(ctx, cur))) {
            return cur
        }
        val id2 = first.optString("id")
        PetPrefs.setConvCurrentPet(ctx, id2)
        return id2
    }

    // ==================== 兜底 ====================

    /**
     * 保证当前池有可用会话：为空时新建；旧版单会话数据存在时迁移过来。
     * 返回当前会话 id。
     */
    @JvmStatic
    fun ensure(ctx: Context): String {
        // 人偶池的种子槽先确保存在（PetTalk 的兜底落点）。
        ensurePet(ctx)
        val p = pool(ctx)
        val all = listAll(ctx)
        if (firstOf(listOf(all, p)) == null) {
            if (POOL_PET == p) {
                return ensurePet(ctx)
            }
            // 旧版单会话数据：chat_history 非空则迁成第一个自聊会话。
            var legacy = "[]"
            try {
                legacy = PetPrefs.history(ctx)
            } catch (unused: Throwable) {
            }
            val id = "c" + System.currentTimeMillis()
            var migrated = false
            try {
                val old = JSONArray(legacy)
                if (old.length() > 0) {
                    PetPrefs.setConvHistory(ctx, id, legacy)
                    migrated = true
                }
            } catch (unused: Throwable) {
            }
            if (migrated) {
                // 【为什么必须清源】否则 self 池被删空后，下一次 ensure 又会读到这份旧数据
                //   把它当成「新会话」重建出来 —— 升级用户会看到已删掉的旧单会话历史复活。
                PetPrefs.setHistory(ctx, "[]")
            }
            try {
                val arr = listAll(ctx)
                val o = JSONObject()
                o.put("id", id)
                o.put("title", if (migrated) autoTitleFrom(legacy) else "新的对话")
                o.put("pool", POOL_SELF)
                o.put("updated", System.currentTimeMillis())
                arr.put(o)
                saveAll(ctx, arr)
                if (!migrated) {
                    PetPrefs.setConvHistory(ctx, id, "[]")
                }
                PetPrefs.setConvCurrent(ctx, id)
            } catch (unused: Throwable) {
            }
            return id
        }
        var cur = getCurrent(ctx, p)
        val co = if (cur == null || cur.isEmpty()) null else find(ctx, cur)
        if (co == null || p != poolOf(co)) {
            val f = firstOf(listOf(listAll(ctx), p))
            cur = if (f == null) "" else f.optString("id")
            setCurrent(ctx, p, cur)
        }
        return cur
    }

    // ==================== 会话操作 ====================

    @JvmStatic
    fun rename(ctx: Context, id: String, title: String?) {
        val o = find(ctx, id)
        if (o == null) {
            return
        }
        try {
            o.put("title", if (title == null || title.trim().isEmpty()) "未命名" else title.trim())
        } catch (unused: Throwable) {
            return
        }
        val arr = listAll(ctx)
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i)
            if (it != null && id == it.optString("id")) {
                try {
                    arr.put(i, o)
                } catch (unused: Throwable) {
                }
            }
        }
        saveAll(ctx, arr)
    }

    /** 删除会话；若删的是某个池的当前会话，只在同一个池里兜底，不串池。 */
    @JvmStatic
    fun delete(ctx: Context, id: String) {
        val target = find(ctx, id)
        if (target == null) {
            return
        }
        val p = poolOf(target)
        try {
            val arr = listAll(ctx)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null && id != o.optString("id")) {
                    out.put(o)
                }
            }
            saveAll(ctx, out)
            PetPrefs.removeConvHistory(ctx, id)
            PetPrefs.removeConvPrev(ctx, id)
            if (id == getCurrent(ctx, p)) {
                val f = firstOf(listOf(out, p))
                setCurrent(ctx, p, if (f == null) "" else f.optString("id"))
            }
        } catch (unused: Throwable) {
        }
        // 删的是当前显示的池 → 兜底补一条可用会话（人偶池删空了会补回「人偶」）。
        if (p == pool(ctx)) {
            ensure(ctx)
        }
    }

    /** 有新消息时刷新会话的 updated 时间戳。 */
    @JvmStatic
    fun touch(ctx: Context, id: String) {
        val o = find(ctx, id)
        if (o == null) {
            return
        }
        try {
            o.put("updated", System.currentTimeMillis())
        } catch (unused: Throwable) {
            return
        }
        val arr = listAll(ctx)
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i)
            if (it != null && id == it.optString("id")) {
                try {
                    arr.put(i, o)
                } catch (unused: Throwable) {
                }
            }
        }
        saveAll(ctx, arr)
    }

    /** 会话标题为空/默认时，用第一条用户消息自动命名。 */
    @JvmStatic
    fun autoTitleIfNeeded(ctx: Context, id: String, firstUserText: String?) {
        val o = find(ctx, id)
        if (o == null) {
            return
        }
        val title = o.optString("title", "")
        if (!title.isEmpty() && "新的对话" != title) {
            return
        }
        rename(ctx, id, autoTitle(firstUserText))
    }

    /** 取首条用户消息前 12 字作标题。 */
    @JvmStatic
    fun autoTitle(text: String?): String {
        if (text == null) {
            return "新的对话"
        }
        val t = text.replace('\n', ' ').trim()
        if (t.isEmpty()) {
            return "新的对话"
        }
        return if (t.length <= 12) t else t.substring(0, 12) + "…"
    }

    private fun autoTitleFrom(historyJson: String): String {
        try {
            val arr = JSONArray(historyJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null && "user" == o.optString("role")) {
                    return autoTitle(o.optString("content"))
                }
            }
        } catch (unused: Throwable) {
        }
        return "旧的对话"
    }
}
