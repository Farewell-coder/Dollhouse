package com.dollhouse.app.data

import android.content.Context
import org.json.JSONObject

/**
 * 【职责】**待机缺失时的确定性回退动作**（v2.1·19.3 降级矩阵最后一行 + 补充规则 2/3）。
 *
 * ## 要解决的问题
 *
 * 降级矩阵规定：待机（常驻壳）没有内容时，必须「**从有效素材里选一个确定的回退动作**作为待机」，
 * 并且**把选择结果持久化**。如果不持久化，每次启动都可能按不同顺序挑到不同动作 ——
 * 用户看到的是「昨天她还站着，今天开机变成趴着的」，而且这个变化还会随档案增删动作而漂移。
 *
 * ## 选择规则（补充规则 3，顺序固定，不得打乱）
 *
 * ```
 * ① 用户明确指定        → 直接采用（[setUser]）
 * ② 识别结果 / 导入推断 → 导入提交时按「有效素材」的固定顺序挑一个（[recordAfterImport]）
 * ③ 自然排序第一个有效   → 档案里已有动作时按 [DollAnim.GROUP_ORDER] + id 字典序挑第一个
 * ```
 *
 * 关键在于**每一层的结果都写进档**，第 ②③ 层只在「还没有记录 / 记录已失效」时执行一次。
 *
 * ## 什么算「失效」
 *
 * 记录里那个动作被用户删了、或者被清空成 0 帧 —— 这两种情况下继续拿着旧 id 等于没有回退，
 * 所以必须重算。重算同样会落盘，因此仍然满足「不同启动不随机挑选」。
 *
 * ## 边界
 *
 * - 本类**不产生任何 UI**：缺动作是正常降级状态（19.3.7），不是错误。
 * - 本类**不改动作数据**：只记「缺待机时该播谁」，不往常驻壳里塞帧（那会假装用户有素材）。
 * - [resolve] 可以在渲染线程反复调用：命中缓存后只读一次 SharedPreferences。
 */
object IdleFallback {

    /** 回退来源：用户明确指定。 */
    const val SRC_USER = "user"

    /** 回退来源：导入时按有效素材推断。 */
    const val SRC_IMPORT = "import"

    /** 回退来源：档案既有动作里按固定顺序挑的第一个。 */
    const val SRC_FIRST = "first"

    private const val KEY_PREFIX = "doll_idle_fb_"

    /** 「上次算出来的有效结论」缓存：动作集合变化时必须失效，见 [invalidate]。 */
    @Volatile
    private var cacheDollId: String? = null

    @Volatile
    private var cacheId: String? = null

    @Volatile
    private var generation: Int = 0

    /* ============================ 读取 ============================ */

    /**
     * 取「待机缺失时该播的动作 id」。
     *
     * 返回值保证**此刻真实可用**（存在且有帧 / 有入口资源）；一个都挑不出来时返回 null，
     * 调用方按「没有待机」处理（画面交回基础形变）——这仍然不是错误状态。
     */
    @JvmStatic
    @Synchronized
    fun resolve(ctx: Context, dollId: String?): String? {
        if (dollId.isNullOrEmpty()) {
            return null
        }
        if (cacheDollId == dollId) {
            val c = cacheId
            // 缓存命中也要校验：动作可能刚被删掉（同一进程内不会走 bumpGeneration 的路径）。
            if (c != null && usable(ctx, dollId, c)) {
                return c
            }
        }
        val rec = read(ctx, dollId)
        if (rec != null && usable(ctx, dollId, rec.first)) {
            remember(dollId, rec.first)
            return rec.first
        }
        // 没有记录 / 记录失效 → 按固定规则重算并落盘（19.3 补充规则 3）。
        val picked = pickFirstUsable(DollAnim.all(ctx, dollId))
        if (picked == null) {
            forget(ctx, dollId)
            return null
        }
        write(ctx, dollId, picked, SRC_FIRST)
        remember(dollId, picked)
        return picked
    }

    /** 回退来源（`user` / `import` / `first`）；没有记录时返回空串。 */
    @JvmStatic
    fun sourceOf(ctx: Context, dollId: String?): String {
        if (dollId.isNullOrEmpty()) {
            return ""
        }
        return read(ctx, dollId)?.second ?: ""
    }

    /* ============================ 写入 ============================ */

    /** 用户明确指定某个动作「就当待机用」。传 null = 清掉这个指定。 */
    @JvmStatic
    fun setUser(ctx: Context, dollId: String?, actionId: String?) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        if (actionId.isNullOrEmpty()) {
            forget(ctx, dollId)
            return
        }
        write(ctx, dollId, actionId, SRC_USER)
        remember(dollId, actionId)
    }

    /**
     * 导入提交后调用：常驻壳有内容 → 清掉回退（不再需要）；没有内容 → 按素材推断一个并落盘。
     *
     * 【为什么在导入时做而不是等第一次渲染】
     *   导入是「素材刚刚变化」的唯一时机。此刻推断出来的候选是**贴合这次素材**的
     *   （比如这次只给了拖拽图，回退就应该是拖拽那套）；等到渲染时再算，看到的可能已经是
     *   后续别的操作改过的档案，推断依据就丢了。
     *
     * @param preferIds 导入时按优先级排好的候选 id（由调用方按槽位重要度给出）。
     */
    @JvmStatic
    @Synchronized
    fun recordAfterImport(ctx: Context, dollId: String?, preferIds: List<String> = emptyList()) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        // 常驻壳里有内容 = 有待机，回退概念不存在。
        val idle = DollAnim.byId(ctx, dollId, DollAnim.SLOT_ALWAYS_ID)
        if (idle != null && (idle.frames.isNotEmpty() || idle.assetPath.isNotEmpty())) {
            forget(ctx, dollId)
            return
        }
        // ① 用户明确指定过且仍可用 → 保持不动（用户意愿最高，导入不得覆盖）。
        val rec = read(ctx, dollId)
        if (rec != null && rec.second == SRC_USER && usable(ctx, dollId, rec.first)) {
            remember(dollId, rec.first)
            return
        }
        // ② 本次导入推断出来的候选。
        for (id in preferIds) {
            if (usable(ctx, dollId, id)) {
                write(ctx, dollId, id, SRC_IMPORT)
                remember(dollId, id)
                return
            }
        }
        // ③ 兜底：档案里按固定顺序第一个有效动作。
        val picked = pickFirstUsable(DollAnim.all(ctx, dollId))
        if (picked == null) {
            forget(ctx, dollId)
            return
        }
        write(ctx, dollId, picked, SRC_FIRST)
        remember(dollId, picked)
    }

    /**
     * 动作集合发生结构性变化（删除动作 / 换模型 / 清空某组）后调用，让缓存下次重新校验。
     *
     * 【为什么不直接清记录】记录本身可能仍然有效，清掉会让「用户指定的」回退丢失；
     *   这里只让缓存失效，[resolve] 会重新校验并按需重算。
     */
    @JvmStatic
    fun invalidate() {
        generation++
        cacheDollId = null
        cacheId = null
    }

    /**
     * 从一组动作里按固定规则挑「第一个有效动作」。
     *
     * 规则（**纯函数，可单测**）：
     * 1. 优先 [DollAnim.GROUP_ORDER] 里的组顺序（常驻排最前，但它缺失时本就会跳过）；
     * 2. 同组内按 id 字典序（= 稳定可重复，不受档案里数组顺序影响）；
     * 3. 只认「有帧或有入口资源」的动作（空壳不能当回退，否则照样是无画面）。
     *
     * 【为什么同组按 id 而不是按数组顺序】数组顺序会随「最近编辑过的动作」漂移，
     *   而 id 是导入瞬间定下的、此后不变 —— 只有 id 能满足「不同启动挑到同一个」。
     */
    @JvmStatic
    fun pickFirstUsable(list: List<DollAnim.Action>): String? {
        val usable = list.filter { it.frames.isNotEmpty() || it.assetPath.isNotEmpty() }
        if (usable.isEmpty()) {
            return null
        }
        var bestGroupRank = Int.MAX_VALUE
        var bestId: String? = null
        for (a in usable) {
            val rank = groupRank(a.group)
            if (bestId == null || rank < bestGroupRank) {
                bestGroupRank = rank
                bestId = a.id
                continue
            }
            if (rank == bestGroupRank && a.id < bestId!!) {
                bestId = a.id
            }
        }
        return bestId
    }

    /** 组在 [DollAnim.GROUP_ORDER] 里的位置；不认识的组排在最后。 */
    private fun groupRank(group: String): Int {
        val i = DollAnim.GROUP_ORDER.indexOf(group)
        return if (i >= 0) i else DollAnim.GROUP_ORDER.size
    }

    /* ============================ 内部 ============================ */

    private fun usable(ctx: Context, dollId: String, actionId: String): Boolean {
        val a = DollAnim.byId(ctx, dollId, actionId) ?: return false
        return a.frames.isNotEmpty() || a.assetPath.isNotEmpty()
    }

    private fun remember(dollId: String, id: String) {
        cacheDollId = dollId
        cacheId = id
    }

    private fun key(dollId: String): String = KEY_PREFIX + dollId

    /** 记录格式：`{"id":"<动作 id>","src":"<来源>","g":<generation>}`。 */
    private fun read(ctx: Context, dollId: String): Pair<String, String>? {
        val raw = try {
            PetPrefs.get(ctx).getString(key(dollId), "")
        } catch (t: Throwable) {
            null
        }
        if (raw.isNullOrEmpty()) {
            return null
        }
        return try {
            val o = JSONObject(raw)
            val id = o.optString("id", "")
            if (id.isEmpty()) {
                null
            } else {
                Pair(id, o.optString("src", SRC_FIRST))
            }
        } catch (t: Throwable) {
            // 档被改坏：当作没有记录，走重算（比崩溃或静默不动都安全）。
            null
        }
    }

    private fun write(ctx: Context, dollId: String, actionId: String, src: String) {
        try {
            val o = JSONObject()
            o.put("id", actionId)
            o.put("src", src)
            o.put("g", generation)
            PetPrefs.get(ctx).edit().putString(key(dollId), o.toString()).apply()
        } catch (t: Throwable) {
            // 落盘失败不改变本次行为：内存里已经生效，下次启动会重算。
        }
    }

    private fun forget(ctx: Context, dollId: String) {
        try {
            PetPrefs.get(ctx).edit().remove(key(dollId)).apply()
        } catch (t: Throwable) {
        }
        if (cacheDollId == dollId) {
            cacheDollId = null
            cacheId = null
        }
    }

    /** 清掉某只人偶的回退记录（删角色时调用，避免残留键）。 */
    @JvmStatic
    fun clear(ctx: Context, dollId: String?) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        forget(ctx, dollId)
    }
}
