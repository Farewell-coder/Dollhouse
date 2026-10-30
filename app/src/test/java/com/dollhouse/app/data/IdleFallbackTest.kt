package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【测试对象】[IdleFallback.pickFirstUsable] —— v2.1·19.3 降级矩阵最后一行 + 补充规则 2/3 的落点。
 *
 * 这条规则的关键词是**确定性**：待机缺失时必须挑一个**确定的**回退动作并持久化，
 * 不能每次启动随机挑。所以这里断言的不是「能挑出一个」，而是「挑到的永远是同一个」。
 *
 * 【为什么只测纯函数】`resolve` / `recordAfterImport` 依赖 SharedPreferences，
 * 属于仪器测试范畴；而「挑哪一个」的全部判断都收敛在 `pickFirstUsable` 这一个纯函数里，
 * 把它钉死，剩下的读写只是搬运。
 */
class IdleFallbackTest {

    /** 造一个「有帧」的动作（帧路径只要是合法字符串即可，本测试不碰文件系统）。 */
    private fun action(id: String, group: String, frames: Int = 1): DollAnim.Action =
        DollAnim.Action(
            id = id,
            name = id,
            group = group,
            frames = (0 until frames).map { "/tmp/$id/$it.png" }
        )

    /** 造一个「空壳」动作（有 id、有分组、零帧）。 */
    private fun empty(id: String, group: String): DollAnim.Action =
        DollAnim.Action(id = id, name = id, group = group, frames = emptyList())

    @Test
    fun noActionsAtAll_returnsNull() {
        // 一个动作都没有 = 无候选。此时回退应当为 null（画面交回基础形变），
        // 绝不能返回一个假 id —— 那会让播放层以为「播上了」却画不出东西。
        assertNull(IdleFallback.pickFirstUsable(emptyList()))
    }

    @Test
    fun allEmptyShells_returnsNull() {
        // 只有空壳（新建角色刚建出来就是这个状态）：和「没有动作」等价。
        val list = listOf(
            empty(DollAnim.SLOT_ALWAYS_ID, DollAnim.GROUP_ALWAYS),
            empty(DollAnim.SLOT_DRAG_ID, DollAnim.GROUP_DRAG),
            empty(DollAnim.SLOT_EDGE_ID, DollAnim.GROUP_EDGE)
        )
        assertNull(IdleFallback.pickFirstUsable(list))
    }

    @Test
    fun idlePresent_wins() {
        // 常驻组在 GROUP_ORDER 里排最前，正常情况下本来就走不到回退这条路；
        // 但它若真有内容，回退仍然应该优先选它（保持与矩阵一致的直觉）。
        val list = listOf(
            action(DollAnim.SLOT_EDGE_ID, DollAnim.GROUP_EDGE),
            action(DollAnim.SLOT_ALWAYS_ID, DollAnim.GROUP_ALWAYS)
        )
        assertEquals(DollAnim.SLOT_ALWAYS_ID, IdleFallback.pickFirstUsable(list))
    }

    @Test
    fun dragBeatsEdge_whenIdleMissing() {
        // 19.3 常见场景：用户只给了拖拽图和贴边图。
        // 组的先后（拖拽 < 贴边）决定结果，与列表顺序无关 —— 所以这里故意把贴边放前面。
        val list = listOf(
            action(DollAnim.SLOT_EDGE_ID, DollAnim.GROUP_EDGE),
            action(DollAnim.SLOT_DRAG_ID, DollAnim.GROUP_DRAG)
        )
        assertEquals(DollAnim.SLOT_DRAG_ID, IdleFallback.pickFirstUsable(list))
    }

    @Test
    fun sameGroup_brokenByStableIdOrder() {
        // 同组多动作时按 id 字典序 —— 这是「不同启动挑到同一个」的关键：
        // 数组顺序会随「最近编辑过的动作」漂移，id 不会。
        val list = listOf(
            action("zeta", DollAnim.GROUP_MANUAL),
            action("alpha", DollAnim.GROUP_MANUAL),
            action("mike", DollAnim.GROUP_MANUAL)
        )
        assertEquals("alpha", IdleFallback.pickFirstUsable(list))
    }

    @Test
    fun sameGroupIdOrderIsIndependentOfInputOrder() {
        val a = listOf(
            action("b", DollAnim.GROUP_MANUAL),
            action("a", DollAnim.GROUP_MANUAL)
        )
        val b = listOf(
            action("a", DollAnim.GROUP_MANUAL),
            action("b", DollAnim.GROUP_MANUAL)
        )
        assertEquals(IdleFallback.pickFirstUsable(a), IdleFallback.pickFirstUsable(b))
        assertEquals("a", IdleFallback.pickFirstUsable(a))
    }

    @Test
    fun emptyShellInBestGroup_isSkippedForNextGroup() {
        // 拖拽壳是空的、贴边壳有内容：不能因为「拖拽组排名更前」就返回一个空壳 id，
        // 必须继续往下找到真正有内容的那个。
        val list = listOf(
            empty(DollAnim.SLOT_DRAG_ID, DollAnim.GROUP_DRAG),
            action(DollAnim.SLOT_EDGE_ID, DollAnim.GROUP_EDGE)
        )
        assertEquals(DollAnim.SLOT_EDGE_ID, IdleFallback.pickFirstUsable(list))
    }

    @Test
    fun unknownGroup_sortsLast() {
        // 档案被手工改成未知分组（旧版残留 / 未来新增）：排在已知组之后，
        // 但不能被直接丢弃 —— 丢弃会让「只有一套旧动作」的档案彻底没有回退。
        val list = listOf(
            action("legacy", "某个已经不存在的组"),
            action("drag", DollAnim.GROUP_DRAG)
        )
        assertEquals("drag", IdleFallback.pickFirstUsable(list))
        // 只有未知组时仍然能选中它。
        assertEquals("legacy", IdleFallback.pickFirstUsable(listOf(action("legacy", "未知组"))))
    }

    @Test
    fun nonFrameEngineCounts() {
        // Lottie / 动图这类「非帧序列」动作没有 frames，但有入口资源 —— 它们是**可播的**，
        // 不能因为 frames 空就当成空壳跳过（19.6 把这类格式列为 P0 支持）。
        val lottie = DollAnim.Action(
            id = "lottie_idle",
            name = "lottie",
            group = DollAnim.GROUP_EDGE,
            frames = emptyList(),
            assetPath = "/tmp/idle.json",
            engine = DollAnim.ENGINE_LOTTIE
        )
        assertEquals("lottie_idle", IdleFallback.pickFirstUsable(listOf(lottie)))
    }

    @Test
    fun sourceConstantsAreDistinct() {
        // 来源标识会落盘、会显示在模型管理页的说明里，三者必须是不同值，
        // 否则「用户指定」会被导入覆盖（那是不可接受的行为退化）。
        val set = setOf(IdleFallback.SRC_USER, IdleFallback.SRC_IMPORT, IdleFallback.SRC_FIRST)
        assertEquals(3, set.size)
        assertTrue(IdleFallback.SRC_USER.isNotEmpty())
    }

    @Test
    fun groupOrderMatchesDocumentedSequence() {
        // 回退选择依赖 GROUP_ORDER 的相对顺序。这里把「四态的真实播放顺序」钉住：
        // 常驻 → 拖拽 → 贴入瞬间 → 贴边。改这个顺序会静默改变回退结果，
        // 所以用测试而不是注释来守它。
        val order = DollAnim.GROUP_ORDER
        assertTrue(order.indexOf(DollAnim.GROUP_ALWAYS) < order.indexOf(DollAnim.GROUP_DRAG))
        assertTrue(order.indexOf(DollAnim.GROUP_DRAG) < order.indexOf(DollAnim.GROUP_EDGE_IN))
        assertTrue(order.indexOf(DollAnim.GROUP_EDGE_IN) < order.indexOf(DollAnim.GROUP_EDGE))
    }
}