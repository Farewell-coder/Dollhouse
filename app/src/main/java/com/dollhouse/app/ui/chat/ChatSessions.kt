package com.dollhouse.app.ui.chat

import android.content.Context
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】多会话管理：两个会话池（self 自聊 / pet 人偶）的增删改查、当前会话与当前池的切换。
 *
 * 【入口】ChatHistoryStore.loadHistory / saveHistory（取当前会话 id）、聊天列表页（列表与操作）、
 *        PetTalk（人偶池落点）。
 *
 * 【交互】清单统一存 conv_index，每条带 pool 字段（"self" / "pet"）区分池；
 *        每个会话的历史存 conv_&lt;id&gt;；当前会话 id 分池存：self → conv_current、pet → conv_current_pet；
 *        当前处于哪个池存 conv_pool。历史内容本身仍由 ChatPanel.history 持有，本类只搬 id 与清单。
 *
 * 【池的作用】人偶池的 assistant 回复落库时压成单行（见 ChatHistoryStore 的 byPool），自聊池原样保留；
 *        长期记忆按**人偶**分库（见 [com.dollhouse.app.data.Mem]），与池的划分无关。
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

    /** 【r13】日志标签。 */
    private const val TAG = "DollhouseSessions"

    /**
     * 【r13·一人偶一对话】人偶绑定会话的 id 前缀。
     *
     * 【为什么用前缀而不是纯时间戳】`d_<dollId>` 是**确定性**的：给定人偶就能算出主会话，
     *   这正是「一个人偶对应一个好友」的技术基础（见 [idForDoll]）。
     *   同时它与历史遗留的 `pet` / `p*` / `c*` 一眼可分，排查数据问题时不会混淆。
     *
     * 【多会话】同一只人偶还可以有若干副会话，id 为 `d_<dollId>_s_<时间戳>`
     *   （见 [idForDollSession]）。主会话与副会话共用同一个 dollId，
     *   因而绑定的是同一个 persona，不需要在会话里复制人格。
     */
    const val D_PREFIX = "d_"

    /** 【多会话】人偶副会话的分隔段：`d_<dollId>_s_<时间戳>`。 */
    const val SUB_SEG = "_s_"

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
        val raw = try {
            JSONArray(PetPrefs.convIndex(ctx))
        } catch (unused: Throwable) {
            JSONArray()
        }
        // 【r13·修 bug·列表重复】按 id 去重。
        //   与 DollStore.load 同因：清单是「读-改-写」维护的 JSON 数组，
        //   两个写操作交错时会把同一条目追加两次，列表里就出现同名对话。
        //   在**唯一读取入口**收敛，无论历史存档被搞成什么样都只返回一份。
        val out = JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until raw.length()) {
            val o = raw.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty() || !seen.add(id)) {
                continue
            }
            out.put(o)
        }
        return out
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

    /** 在一次操作已读取的快照中查项，避免同一事务反复解析 conv_index。 */
    private fun findIn(all: JSONArray, id: String?): JSONObject? {
        if (id == null) return null
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i)
            if (o != null && id == o.optString("id")) return o
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
     *   需要「按 id 反查它属于哪个池」的那些调用点用它做锚点核对。
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

    /**
     * 在指定池里新建会话（不动当前池与当前会话）。
     *
     * 【多会话·分支复用】人偶池里新建时，如果当前会话已经绑定人偶
     *   （不论是主会话还是副会话），直接走 [createForDoll]：
     *   现有分支入口 `ChatPanel.branchAt → createIn` 无需改动，
     *   就会落到同一个人偶的副会话上，而不是造一条无归属的 `p*` 会话。
     *   没有绑定人偶（旧 `pet` / 自聊池）时保持原行为。
     */
    @JvmStatic
    fun createIn(ctx: Context, p: String, prefix: String, title: String): String {
        if (POOL_PET == p) {
            val bound = dollOfConv(ctx, getCurrent(ctx, POOL_PET))
            if (bound.isNotEmpty()) {
                val id = createForDoll(ctx, bound, title)
                if (id.isNotEmpty()) {
                    return id
                }
            }
        }
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

    /**
     * 【r13·已废弃，全工程零调用点】在当前池建一条新会话并切过去。
     *
     * 【为什么不删】它被旧档迁移路径间接依赖（`ensurePet` 内部的补建分支），
     *   删掉会牵连一片；而「不再被 UI 调用」这件事已经由「调用点为零」保证了。
     *   需求「不需要那个新的对话」针对的是**入口**，不是数据层能力。
     *
     * 【新代码不要调它】会话的唯一合法来源是 [ensureForDoll]（人偶的投影）。
     *   想给用户「新对话」的入口，正确做法是让他去新建一只人偶。
     */
    @JvmStatic
    fun create(ctx: Context): String {
        val p = pool(ctx)
        val id = createIn(ctx, p, if (POOL_PET == p) "p" else "c", "新的对话")
        setCurrent(ctx, p, id)
        return id
    }

    // ==================== 【r13·一人偶一对话】会话与人偶强绑定 ====================

    /**
     * 【r13】某只人偶对应的**确定性会话 id**。
     *
     * 【为什么要「确定性」】需求原文：「聊天界面不要无限添加，就像 QQ 好友一样，
     *   一个联系人对应的一个对话框，不要无限增加对话框；以后聊天界面的对话框
     *   都得绑定人偶」。
     *   所以会话不再是用户随手新建的孤儿，而是**人偶的投影**：
     *   给定 dollId 就能算出唯一 id，反复调也只会得到同一个会话。
     *   `d_` 前缀把它与历史遗留的 `p*` / `c*` / `pet` 会话区分开，便于迁移与排查。
     */
    @JvmStatic
    fun idForDoll(dollId: String?): String {
        return D_PREFIX + (dollId ?: "")
    }

    /** 会话 id 是不是「人偶绑定会话」（主会话或副会话都算）。 */
    @JvmStatic
    fun isDollConv(id: String?): Boolean {
        return id != null && id.startsWith(D_PREFIX)
    }

    /**
     * 【多会话】同一只人偶的副会话 id：`d_<dollId>_s_<时间戳>`。
     *
     * 主会话仍由 [idForDoll] 给出（`d_<dollId>`）；副会话只换 id，不换 persona ——
     * 归属仍由同一个 dollId 决定，人格永远从 DollProfile 按 dollId 现取。
     */
    @JvmStatic
    fun idForDollSession(dollId: String, at: Long): String {
        return D_PREFIX + dollId + SUB_SEG + at
    }

    /** 会话 id 是不是「人偶的副会话」（`d_<dollId>_s_<纯数字>`）。 */
    @JvmStatic
    fun isSecondaryConv(id: String?): Boolean {
        if (id == null || !id.startsWith(D_PREFIX)) {
            return false
        }
        return secondarySepIndex(id.substring(D_PREFIX.length)) >= 0
    }

    /**
     * 从会话 id 反推 dollId；不是人偶会话（`pet` / `c*` 等）返回空串。
     *
     * 主会话 `d_<dollId>` 与副会话 `d_<dollId>_s_<ts>` 都返回同一个 dollId。
     * 分隔符取**最后一个** `_s_` 且要求其后全是数字 —— 这样即使 dollId 里
     * 碰巧带下划线，也不会把归属解析错。
     */
    @JvmStatic
    fun dollIdOfConvId(id: String?): String {
        if (id == null || !id.startsWith(D_PREFIX)) {
            return ""
        }
        val rest = id.substring(D_PREFIX.length)
        val sep = secondarySepIndex(rest)
        return if (sep < 0) rest else rest.substring(0, sep)
    }

    /** 副会话分隔段在 id（去掉 `d_` 后）里的下标；不是副会话返回 -1。 */
    private fun secondarySepIndex(rest: String): Int {
        val i = rest.lastIndexOf(SUB_SEG)
        if (i <= 0) {
            return -1
        }
        val ts = rest.substring(i + SUB_SEG.length)
        if (ts.isEmpty()) {
            return -1
        }
        for (c in ts) {
            if (c < '0' || c > '9') {
                return -1
            }
        }
        return i
    }

    /**
     * 生成一个当前未被占用的副会话 id（纯函数，便于 JVM 单测）。
     *
     * 同一毫秒内连续新建时顺延时间戳，仍严格保持
     * `d_<dollId>_s_<纯数字>` 的形状 —— 不加 `_1` 之类后缀，
     * 否则 [dollIdOfConvId] 的「最后一段」解析就会失效。
     */
    @JvmStatic
    fun nextFreeSessionId(dollId: String, now: Long, taken: (String) -> Boolean): String {
        var ts = if (now > 0L) now else 0L
        var id = idForDollSession(dollId, ts)
        var guard = 0
        while (taken(id) && guard < 1000) {
            ts++
            id = idForDollSession(dollId, ts)
            guard++
        }
        return id
    }

    /**
     * 会话的「有效 updated」：清单字段优先；缺失/为 0 时回落到副会话 id 里的时间戳。
     * 主会话没有内嵌时间戳，缺失时只能是 0（排序时排最后，再由 id 稳定兜底）。
     */
    @JvmStatic
    fun effectiveUpdated(updated: Long, id: String?): Long {
        if (updated > 0L) {
            return updated
        }
        val v = id ?: return 0L
        val rest = if (v.startsWith(D_PREFIX)) v.substring(D_PREFIX.length) else v
        val sep = secondarySepIndex(rest)
        if (sep < 0) {
            return 0L
        }
        return rest.substring(sep + SUB_SEG.length).toLongOrNull() ?: 0L
    }

    /** 会话排序：有效 updated 新的在前；同一时间按 id 升序，保证顺序稳定。 */
    @JvmStatic
    fun compareForDoll(idA: String?, updatedA: Long, idB: String?, updatedB: Long): Int {
        val a = effectiveUpdated(updatedA, idA)
        val b = effectiveUpdated(updatedB, idB)
        if (a != b) {
            return if (a > b) -1 else 1
        }
        return (idA ?: "").compareTo(idB ?: "")
    }

    /**
     * 【多会话】某只人偶（persona）绑定的**全部**会话，按有效 updated 倒序。
     *
     * 主会话 `d_<dollId>` 与所有副会话 `d_<dollId>_s_<ts>` 都在结果里。
     * 归属以 `conv_doll` 为准、id 解析兜底；**不看隐藏标记** ——
     * 隐藏只是列表入口的展示层概念，数据层仍返回全部会话。
     */
    @JvmStatic
    fun forDoll(ctx: Context, dollId: String?): JSONArray {
        val target = dollId ?: ""
        val out = JSONArray()
        if (target.isEmpty()) {
            return out
        }
        val rows = ArrayList<JSONObject>()
        val all = listAll(ctx)
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            if (target != dollOfConv(ctx, o.optString("id"))) {
                continue
            }
            rows.add(o)
        }
        rows.sortWith { a, b ->
            compareForDoll(
                a.optString("id"),
                a.optLong("updated", 0L),
                b.optString("id"),
                b.optLong("updated", 0L)
            )
        }
        for (o in rows) {
            out.put(o)
        }
        return out
    }

    /** 某只人偶最近活跃的会话 id；一条都没有时返回 null（不隐式建会话）。 */
    @JvmStatic
    fun latestForDoll(ctx: Context, dollId: String?): String? {
        val arr = forDoll(ctx, dollId)
        if (arr.length() == 0) {
            return null
        }
        val first = arr.optJSONObject(0) ?: return null
        val id = first.optString("id")
        return if (id.isEmpty()) null else id
    }

    /**
     * 【多会话】取（必要时创建）某只人偶的**主会话** `d_<dollId>`。
     *
     * 【策略】
     *   ① 主会话已存在 → 直接返回（并补写一次绑定）；
     *   ② 主会话不存在，但这只人偶还有副会话 → 返回最近活跃的那条副会话，
     *      **不重建空主会话**（删掉主会话是用户的显式动作，不该在同步时复活）；
     *   ③ 一条都没有 → 新建空主会话（标题取人偶名）。
     *
     * 【旧无归属数据不迁移】这里绝不会把 `pet` / `p*` 之类没有 dollId 归属的
     *   老会话认领给某只人偶 —— 旧数据原样留在原处，新会话从空开始。
     */
    @JvmStatic
    fun ensureForDoll(ctx: Context, dollId: String): String {
        if (dollId.isEmpty()) {
            return ensurePet(ctx)
        }
        val want = idForDoll(dollId)
        // ① 已有主会话
        if (find(ctx, want) != null) {
            PetPrefs.setConvDoll(ctx, want, dollId)
            return want
        }
        // ② 主会话被删后，同 persona 的副会话优先（按有效 updated 取最新）。
        val existing = forDoll(ctx, dollId)
        for (i in 0 until existing.length()) {
            val o = existing.optJSONObject(i) ?: continue
            if (POOL_PET != poolOf(o)) {
                continue
            }
            val id = o.optString("id")
            if (id.isNotEmpty()) {
                PetPrefs.setConvDoll(ctx, id, dollId)
                return id
            }
        }
        // ③ 新建 —— 【必须用确定性 id】
        //   【曾经的 bug】这里原来调 createIn(ctx, POOL_PET, D_PREFIX, ...)，
        //   而 createIn 内部生成的是 `prefix + 时间戳`（`d_1759...`），**不是** `d_<dollId>`。
        //   于是下一轮 syncWithDolls 再查 `find("d_default")` 依然落空，**又建一条** ——
        //   表现就是列表里堆出一串同名的「默认配置」，而且删了还会再长出来（删不掉）。
        //   现在直接用算好的 [want] 建，保证主会话的幂等性。
        addConv(ctx, POOL_PET, want, dollTitle(ctx, dollId), dollId)
        PetPrefs.setConvDoll(ctx, want, dollId)
        return want
    }

    /**
     * 【多会话】给某只人偶新建一条**副会话**，返回新会话 id。
     *
     * 【id】严格 `d_<dollId>_s_<时间戳>`（见 [idForDollSession]）。
     * 【主会话】先走 [ensureForDoll]：主会话存在就原样保留，不会被这次新建挤掉。
     * 【人格】只写 `conv_doll = dollId`；人格仍由 DollProfile 按 dollId 现取，
     *   不在会话清单里复制任何 persona 字段 —— 改了人设，这只人偶的所有会话一起生效。
     * 【池】固定人偶池；切不切换当前会话由调用方决定。
     */
    @JvmStatic
    fun createForDoll(ctx: Context, dollId: String, title: String? = null): String {
        if (dollId.isEmpty()) {
            return ""
        }
        ensureForDoll(ctx, dollId)
        val name = title?.trim()
        val clean = if (name.isNullOrEmpty()) "新的对话" else name
        var attempt = 0
        while (attempt < 8) {
            val id = nextFreeSessionId(dollId, System.currentTimeMillis()) { find(ctx, it) != null }
            if (addConv(ctx, POOL_PET, id, clean, dollId)) {
                return id
            }
            attempt++
        }
        return ""
    }

    /**
     * 【r13】用**指定 id** 建一条会话（与 [createIn] 的区别：id 由调用方给，不是时间戳）。
     *
     * 【为什么需要它】人偶绑定会话的 id 必须是确定性的（`d_<dollId>`），
     *   而 [createIn] 的语义是「生成一个不重复的随机 id」，两者目的相反。
     *   混用正是上面那个「重复建会话」bug 的根源。
     *
     * 【幂等】id 已存在时直接返回 false，不重复建。
     *
     * 【dollId】人偶会话（主会话 / 副会话）传入时顺手写 `conv_doll` 绑定；
     *   只写绑定关系，不复制人格内容。
     */
    @JvmStatic
    fun addConv(ctx: Context, p: String, id: String, title: String, dollId: String? = null): Boolean {
        if (id.isEmpty() || find(ctx, id) != null) {
            return false
        }
        return try {
            val all = listAll(ctx)
            val o = JSONObject()
            o.put("id", id)
            o.put("title", title)
            o.put("pool", if (POOL_PET == p) POOL_PET else POOL_SELF)
            o.put("updated", System.currentTimeMillis())
            all.put(o)
            saveAll(ctx, all)
            PetPrefs.setConvHistory(ctx, id, "[]")
            if (!dollId.isNullOrEmpty()) {
                PetPrefs.setConvDoll(ctx, id, dollId)
            }
            true
        } catch (unused: Throwable) {
            false
        }
    }
// ==================== 同好友多会话 ====================

    /** 建会话时的默认标题：用人偶名（拿不到就退回固定文案）。 */
    private fun dollTitle(ctx: Context, dollId: String): String {
        return try {
            val d = com.dollhouse.app.data.DollStore.byId(ctx, dollId)
            val n = d?.name
            if (n.isNullOrEmpty()) "对话" else n
        } catch (unused: Throwable) {
            "对话"
        }
    }

    /** 这条会话的历史里有没有真正的对话消息。 */
    private fun hasMessages(ctx: Context, id: String): Boolean {
        return try {
            val hist = JSONArray(PetPrefs.convHistory(ctx, id))
            for (i in 0 until hist.length()) {
                val o = hist.optJSONObject(i) ?: continue
                if (o.optBoolean("_summary", false) || o.optBoolean("_prev", false)) {
                    continue
                }
                val role = o.optString("role", "")
                if ("user" == role || "assistant" == role) {
                    return true
                }
            }
            false
        } catch (unused: Throwable) {
            false
        }
    }

    /**
     * 把一条会话的 id 改成新值（连同它的历史 / 暂存位 / 展示层偏好一起搬）。
     *
     * 【为什么整体搬而不是新建】老用户的历史是资产，改 id 后必须让 `conv_<id>` 这一族键
     *   统统跟着走，否则历史、暂存、已读水位、头像绑定会散落在旧 id 上成为孤儿数据。
     */
    @JvmStatic
    fun renameConvId(ctx: Context, oldId: String, newId: String): Boolean {
        if (oldId.isEmpty() || newId.isEmpty() || oldId == newId) {
            return false
        }
        if (find(ctx, newId) != null) {
            return false
        }
        return try {
            // 清单里换 id
            val all = listAll(ctx)
            for (i in 0 until all.length()) {
                val o = all.optJSONObject(i) ?: continue
                if (oldId == o.optString("id")) {
                    o.put("id", newId)
                }
            }
            saveAll(ctx, all)
            // 历史 / 暂存位
            val hist = PetPrefs.convHistory(ctx, oldId)
            PetPrefs.setConvHistory(ctx, newId, hist)
            PetPrefs.removeConvHistory(ctx, oldId)
            val prev = PetPrefs.convPrev(ctx, oldId)
            if (prev.isNotEmpty()) {
                PetPrefs.setConvPrev(ctx, newId, prev)
                PetPrefs.removeConvPrev(ctx, oldId)
            }
            // 展示层偏好（已读水位 / 头像 / 绑定）
            PetPrefs.setConvRead(ctx, newId, PetPrefs.convRead(ctx, oldId))
            val av = PetPrefs.convAvatar(ctx, oldId)
            if (av.isNotEmpty()) {
                PetPrefs.setConvAvatar(ctx, newId, av)
            }
            val dl = PetPrefs.convDoll(ctx, oldId)
            if (dl.isNotEmpty()) {
                PetPrefs.setConvDoll(ctx, newId, dl)
            }
            PetPrefs.removeConvExtras(ctx, oldId)
            // 当前会话指向（分池）
            val p = poolOf(findIn(all, newId))
            val cur = getCurrent(ctx, p)
            if (oldId == cur) {
                setCurrent(ctx, p, newId)
            }
            true
        } catch (unused: Throwable) {
            false
        }
    }

    /**
     * 【r13】把会话清单**对齐到人偶库**：每只人偶都有自己的会话，且不再留孤儿会话。
     *
     * 【需求原文】「不需要那个新的对话 每个人偶对应一个对话 想添加新的对话就添加新的人偶」。
     *   所以本方法做三件事：
     *   ① 每只人偶补齐它自己的 `d_<dollId>` 会话；
     *   ② 清掉**垃圾会话**：`d_` 前缀但 id 不是任何现存人偶的（历史 bug 遗留的重复项）；
     *   ③ 清掉**空的孤儿会话**：既没绑定人偶、又一条消息都没有的（就是那些「新的对话」）。
     *
     * 【为什么序③要求「没有消息」】用户升级前可能真的聊过自聊会话，那是有价值的资产，
     *   不能因为「没绑人偶」就删。只有「空的 + 没绑的」才是纯粹的噪声。
     *
     * @return 本次是否改动了清单（改了就 bump 列表信号）。
     */
    @JvmStatic
    fun syncWithDolls(ctx: Context): Boolean {
        return try {
            val dolls = com.dollhouse.app.data.DollStore.all(ctx)
            var changed = false
            // ① 补齐每只人偶的会话（主会话被删但有副会话时，只返回副会话，不重建）。
            for (d in dolls) {
                val before = find(ctx, idForDoll(d.id)) != null
                ensureForDoll(ctx, d.id)
                if (!before && find(ctx, idForDoll(d.id)) != null) {
                    changed = true
                }
            }
            // ②③ 清理垃圾会话
            if (pruneJunk(ctx, dolls.map { it.id }.toHashSet())) {
                changed = true
            }
            changed
        } catch (unused: Throwable) {
            false
        }
    }

    /**
     * 清掉垃圾会话，返回是否真的删了东西。
     *
     * 【判据·以「id 是否规范」为准，不看 conv_doll】
     *   上一版按 `conv_doll` 反查「绑定的人偶还在不在」来判垃圾 —— 结果漏掉了一类：
     *   历史 bug 建的重复项 id 是 `d_<时间戳>`（不是 `d_<dollId>`），
     *   而它们**同时也被写上了 `conv_doll = 合法dollId`**，于是「绑定的人偶还在」成立、
     *   判据认为它们不是垃圾 → **永远删不掉**（用户截图里那一堆就是）。
     *
     *   正确口径：**人偶会话的 id 必须能被解析出一个现存 dollId**。
     *   主会话 `d_<dollId>` 与副会话 `d_<dollId>_s_<时间戳>` 都合法；
     *   解析不出现存人偶的（包括旧 bug 的 `d_<时间戳>`），无论 `conv_doll` 写了什么，都清掉。
     *
     * 【多会话不误杀】同一只人偶可以有一条主会话和任意多条副会话。
     *   它们共用同一个 dollId，但 id 互不相同 —— 不再按「一只人偶只有一条」去重。
     *
     * 【旧无归属数据】非 `d_` 前缀的旧会话不在这里迁移、也不认领给任何人偶；
     *   只清「没绑定人偶、又一条真实消息都没有」的空壳。
     */
    private fun pruneJunk(ctx: Context, aliveDolls: Set<String>): Boolean {
        val all = listAll(ctx)
        val keep = JSONArray()
        var removed = false
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) {
                continue
            }
            val junk: Boolean
            if (isDollConv(id)) {
                // 【人偶会话】主/副都算，归属统一由 id 解析出的 dollId 决定。
                val owner = dollIdOfConvId(id)
                junk = owner.isEmpty() || !aliveDolls.contains(owner)
            } else {
                // 【非绑定会话】没绑定人偶、又没有任何消息 → 纯噪声。
                junk = dollOfConv(ctx, id).isEmpty() && !hasMessages(ctx, id)
            }
            if (junk) {
                removed = true
                PetPrefs.removeConvHistory(ctx, id)
                PetPrefs.removeConvPrev(ctx, id)
                PetPrefs.removeConvExtras(ctx, id)
                Logs.i(TAG, "[清理] 垃圾会话 " + id)
            } else {
                keep.put(o)
            }
        }
        if (removed) {
            saveAll(ctx, keep)
            // 【当前会话若被清掉】指向第一条仍存在的，避免指向一个不存在的 id。
            for (p in arrayOf(POOL_SELF, POOL_PET)) {
                val cur = getCurrent(ctx, p)
                if (cur.isNotEmpty() && findIn(keep, cur) == null) {
                    val f = firstOf(listOf(keep, p))
                    setCurrent(ctx, p, if (f == null) "" else f.optString("id"))
                }
            }
        }
        return removed
    }

    /** 反查：这条会话绑定的人偶 id（没有则空串）。 */
    @JvmStatic
    fun dollOfConv(ctx: Context, convId: String?): String {
        if (convId == null || convId.isEmpty()) {
            return ""
        }
        val byPref = PetPrefs.convDoll(ctx, convId)
        if (byPref.isNotEmpty()) {
            return byPref
        }
        // 兜底：从确定性 id 前缀反推（偏好键被清掉时仍能恢复绑定）。
        return if (isDollConv(convId)) convId.substring(D_PREFIX.length).substringBefore("_s_") else ""
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
        // 同一个快照完成迁移后的查询；原来这里及当前项校验会重复读盘、解析 3~5 次。
        val first = firstOf(listOf(all, POOL_PET))
        if (first == null) {
            val id: String
            if (findIn(all, PET_ID) != null) {
                id = createIn(ctx, POOL_PET, "p", "人偶")
            } else {
                id = PET_ID
                try {
                    val o = JSONObject()
                    o.put("id", id)
                    o.put("title", "人偶")
                    o.put("pool", POOL_PET)
                    o.put("updated", System.currentTimeMillis())
                    all.put(o)
                    saveAll(ctx, all)
                } catch (unused: Throwable) {
                }
            }
            PetPrefs.setConvCurrentPet(ctx, id)
            return id
        }
        val cur = PetPrefs.convCurrentPet(ctx)
        val current = findIn(all, cur)
        if (!cur.isNullOrEmpty() && current != null && POOL_PET == poolOf(current)) {
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
        val co = findIn(all, cur)
        if (co == null || p != poolOf(co)) {
            val f = firstOf(listOf(all, p))
            cur = if (f == null) "" else f.optString("id")
            setCurrent(ctx, p, cur)
        }
        return cur
    }

    // ==================== 会话操作 ====================

    @JvmStatic
    fun rename(ctx: Context, id: String, title: String?) {
        val arr = listAll(ctx)
        val o = findIn(arr, id) ?: return
        try {
            o.put("title", if (title == null || title.trim().isEmpty()) "未命名" else title.trim())
        } catch (unused: Throwable) {
            return
        }
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

    /**
     * 【r13·列表删除=隐藏】把某个会话的**聊天记录清空**，但保留清单项。
     *
     * 【与 [delete] 的区别】[delete] 把清单项也一起删了（会话从列表消失，且会被
     *   [syncWithDolls] 重建）；本方法只清消息，清单项与绑定关系原样保留 ——
     *   对应「聊天页 → 清空当前对话」这个诉求。
     */
    @JvmStatic
    fun clearMessages(ctx: Context, id: String?) {
        if (id == null || id.isEmpty()) {
            return
        }
        try {
            PetPrefs.removeConvHistory(ctx, id)
            PetPrefs.removeConvPrev(ctx, id)
            PetPrefs.setConvRead(ctx, id, 0)
            // 【r19·清空后不再铺开场白】用户清空聊天记录后，seedGreetingIfEmpty 会检测到空会话又铺开场白。
            //   这里标记「已清空」，铺开场白时跳过。
            PetPrefs.setConvCleared(ctx, id, true)
        } catch (unused: Throwable) {
        }
    }

    /** 有新消息时刷新会话的 updated 时间戳。 */
    @JvmStatic
    fun touch(ctx: Context, id: String) {
        val arr = listAll(ctx)
        val o = findIn(arr, id) ?: return
        try {
            o.put("updated", System.currentTimeMillis())
        } catch (unused: Throwable) {
            return
        }
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
