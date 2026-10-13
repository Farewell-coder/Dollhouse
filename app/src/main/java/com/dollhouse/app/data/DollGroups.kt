package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.core.Logs
import org.json.JSONArray

/**
 * 【职责】人偶的**手动分组**：分组清单的增删改，以及把「同组的人偶」归拢成列表。
 *
 * 【数据怎么放（关键设计）】
 *   - **分组清单**（有哪些组）存在 [PetPrefs.dollGroups] —— 全局一份；
 *   - **归属**（这只人偶在哪组）存在 [DollStore.Doll.group] —— 跟着人偶走。
 *
 *   【为什么不把两者放在一起】若清单里同时记着成员 id 列表，那么：
 *   ① 新建 / 删除人偶时要同步维护清单里的数组（漏一处就出现幽灵成员）；
 *   ② 用户删掉某只人偶后，清单里仍留着它的 id，得再写一次清理逻辑。
 *   分开之后：删组只动清单，成员自动「落到未分组」；删人偶只动它自己，清单不受影响。
 *   两个操作各自独立且永远不可能不一致。
 *
 * 【未分组的语义】[DollStore.Doll.group] 为空串、或指向一个已不存在的组名 → 一律归入「未分组」。
 *   这条兜底是本设计能成立的**前提**，绝不能去掉。
 *
 * 【线程】只在主线程调用（与 UI 同约束）；写盘走 SharedPreferences 的 apply。
 */
object DollGroups {

    private const val TAG = "DollhouseDollGroups"

    /** 分组名长度上限。 */
    const val NAME_MAX = 12

    /** 分组数量上限（防止建出几百个空组把列表撑爆）。 */
    const val MAX_GROUPS = 30

    /* ============================ 清单读写 ============================ */

    /**
     * 全部分组名（保持用户建立的先后顺序）。
     * 【容错】存档损坏 / 空 → 返回空列表，而不是抛异常。
     */
    fun all(ctx: Context?): List<String> {
        if (ctx == null) {
            return emptyList()
        }
        return try {
            val raw = PetPrefs.dollGroups(ctx)
            if (raw.isEmpty()) {
                return emptyList()
            }
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            // 【去重】历史存档可能因并发写出现重复组名，读端收敛一次。
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val n = arr.optString(i, "")
                if (n.isNotEmpty() && seen.add(n)) {
                    out.add(n)
                }
            }
            out
        } catch (ignored: Throwable) {
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, names: List<String>) {
        try {
            val arr = JSONArray()
            for (n in names) {
                arr.put(n)
            }
            PetPrefs.setDollGroups(ctx, arr.toString())
        } catch (ignored: Throwable) {
            Logs.w(TAG, "ignored", ignored)
        }
    }

    /**
     * 新建一个分组。
     *
     * @return 成功返回规范化的组名；重名 / 超限 / 空名返回 null（调用方据此提示）。
     */
    fun add(ctx: Context?, raw: String?): String? {
        if (ctx == null) {
            return null
        }
        val name = normalize(raw) ?: return null
        val list = all(ctx)
        if (list.contains(name) || list.size >= MAX_GROUPS) {
            return null
        }
        saveAll(ctx, list + name)
        return name
    }

    /**
     * 分组改名。
     *
     * 【为什么要连带改成员的 group】**归属存的是组名**（见类头注释），
     *   改名后若不动成员，它们原本的组名就「不存在了」，会被兜底逻辑判成「未分组」——
     *   表现是「改个名，组里的人全跑出来了」。所以这里必须把同名成员的 group 一起刷新。
     *
     * @return 成功返回 true；新名与别人重名、或原名不存在时返回 false。
     */
    fun rename(ctx: Context?, oldName: String?, newRaw: String?): Boolean {
        if (ctx == null || oldName.isNullOrEmpty()) {
            return false
        }
        val name = normalize(newRaw) ?: return false
        val list = all(ctx)
        val idx = list.indexOf(oldName)
        if (idx < 0) {
            return false
        }
        if (name != oldName && list.contains(name)) {
            return false
        }
        val next = ArrayList(list)
        next[idx] = name
        saveAll(ctx, next)
        // 【连带更新成员】把原先挂在 oldName 上的人偶全部改挂到新名。
        val dolls = DollStore.all(ctx)
        for (d in dolls) {
            if (d.group == oldName) {
                DollStore.update(ctx, d.copy(group = name))
            }
        }
        return true
    }

    /**
     * 删除分组。
     *
     * 【成员怎么办】不删成员，只把它们的 group 清成空串 → 自动落回「未分组」。
     *   这是「清单与归属分开存」换来的最大便利（见类头注释）。
     */
    fun remove(ctx: Context?, name: String?) {
        if (ctx == null || name.isNullOrEmpty()) {
            return
        }
        val list = all(ctx)
        if (!list.contains(name)) {
            return
        }
        saveAll(ctx, list.filter { it != name })
        val dolls = DollStore.all(ctx)
        for (d in dolls) {
            if (d.group == name) {
                DollStore.update(ctx, d.copy(group = ""))
            }
        }
    }

    /* ============================ 归属 ============================ */

    /**
     * 把一只人偶放进某个组。
     * @param group 空串 / null = 移出分组（回到「未分组」）。
     */
    fun assign(ctx: Context?, dollId: String?, group: String?) {
        if (ctx == null || dollId.isNullOrEmpty()) {
            return
        }
        val d = DollStore.byId(ctx, dollId) ?: return
        val g = group ?: ""
        // 只接受清单里真实存在的组名，避免写进一个「幽灵组」。
        val valid = if (g.isEmpty() || all(ctx).contains(g)) g else ""
        if (d.group == valid) {
            return
        }
        DollStore.update(ctx, d.copy(group = valid))
    }

    /**
     * 取某一组的人偶（未排序；列表页自己决定排序口径）。
     * @param group 传 [DollStore.GROUP_NONE] 或空串 = 取「未分组」那一堆。
     */
    fun dollsOf(ctx: Context?, group: String?): List<DollStore.Doll> {
        if (ctx == null) {
            return emptyList()
        }
        val want = group ?: ""
        val valid = all(ctx).toHashSet()
        return DollStore.all(ctx).filter { effectiveGroup(it, valid) == want }
    }

    /**
     * 把全部人偶按「组」归拢，顺序 = 自定义组的建立顺序 → 「未分组」垫底。
     *
     * 【为什么未分组垫底而不是置顶】用户建组就是为了把常用的人偶聚起来；
     *   让「未分组」这个兜底桶排在最前面，等于每次都要先划过一堆没归类的。
     *
     * @return 元素为 (组名, 成员)；**空组不会被丢掉**（用户要能看到并管理空组）。
     */
    fun grouped(ctx: Context?): List<Pair<String, List<DollStore.Doll>>> {
        if (ctx == null) {
            return emptyList()
        }
        val names = all(ctx)
        val valid = names.toHashSet()
        val dolls = DollStore.all(ctx)
        val out = ArrayList<Pair<String, List<DollStore.Doll>>>(names.size + 1)
        for (n in names) {
            out.add(n to dolls.filter { effectiveGroup(it, valid) == n })
        }
        val none = dolls.filter { effectiveGroup(it, valid) == "" }
        if (none.isNotEmpty() || names.isEmpty()) {
            out.add(DollStore.GROUP_NONE to none)
        }
        return out
    }

    /**
     * 一只人偶**实际生效**的组名。
     *
     * 【为什么必须经这道换算】[DollStore.Doll.group] 可能是空串，也可能指向一个被删掉的组。
     *   两者都该显示为「未分组」。所有读取路径统一走本方法，就不会出现
     *   「列表里看不到、但搜索又能搜到」这类不一致。
     *
     * @param validGroups 预取的分组清单（批量调用时传进来，避免每人偶都读一次盘）
     */
    fun effectiveGroup(d: DollStore.Doll, validGroups: Set<String>): String {
        val g = d.group
        if (g.isEmpty() || !validGroups.contains(g)) {
            return ""
        }
        return g
    }

    /**
     * 组名规范化：去首尾空白 + 截断到 [NAME_MAX]。
     * 空串 / 全空白 → null（调用方按「非法输入」处理）。
     */
    private fun normalize(raw: String?): String? {
        val v = (raw ?: "").trim()
        if (v.isEmpty()) {
            return null
        }
        return if (v.length > NAME_MAX) v.substring(0, NAME_MAX) else v
    }
}