package com.dollhouse.app.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 【职责】交互所有权判定的单元测试。
 *
 * 【为什么它必须存在】这是整套界面框架里**唯一**的判断逻辑。上一轮修触摸穿透失败，
 *   根因就是判断散落在三个地方、没有一处能单独验证；只能在真机上手点，
 *   点错了也说不清是哪一步错。现在把它抽成纯函数，覆盖所有组合，
 *   任何一次改动只要破坏语义，构建就会红。
 */
class LayerGateTest {

    /**
     * 【核心不变式】覆盖页 > 抽屉 > 底座，逐级降级。
     *
     * 三者互斥：任一时刻只有一个层能收触摸。这是需求文档
     * 「确保任何时刻只有当前最顶层且允许交互的页面能够接收用户操作」的直接断言。
     */
    @Test
    fun `只有一层获得交互权`() {
        // 全关 → 底座
        assertEquals(LayerGate.Owner.BASE, LayerGate.ownerOf(navDepth = 0, drawerOpened = false))
        // 只开抽屉 → 抽屉
        assertEquals(LayerGate.Owner.DRAWER, LayerGate.ownerOf(navDepth = 0, drawerOpened = true))
        // 有覆盖页 → 覆盖页（无论抽屉是否开着）
        assertEquals(LayerGate.Owner.NAV, LayerGate.ownerOf(navDepth = 1, drawerOpened = false))
        assertEquals(LayerGate.Owner.NAV, LayerGate.ownerOf(navDepth = 1, drawerOpened = true))
    }

    /**
     * 【抽屉与覆盖页可叠放】
     *
     * 用户定案的语义：抽屉是**一个界面**，只是从左边滑出。从抽屉里点进覆盖页时
     * 抽屉不关（状态与滚动位置保留），返回后原地恢复。
     * 因此「抽屉开着 + 有覆盖页」必须是覆盖页独占，而不是两者争抢。
     */
    @Test
    fun `覆盖页盖住抽屉时抽屉不抢交互`() {
        val owner = LayerGate.ownerOf(navDepth = 1, drawerOpened = true)
        assertEquals(LayerGate.Owner.NAV, owner)
        // 返回后（navDepth 归零）抽屉重新拿回交互权 —— 不需要用户重新滑一次。
        assertEquals(LayerGate.Owner.DRAWER, LayerGate.ownerOf(navDepth = 0, drawerOpened = true))
    }

    /**
     * 【深度不设上限】需求文档明确禁止把层级写死成一级 / 二级 / 三级 / 四级。
     *
     * 任意深度的覆盖页都由同一套规则判定：只要栈非空就是栈顶那一层说了算。
     * 这里枚举 1..8 层，确保没有任何隐藏在「层数」上的特殊分支。
     */
    @Test
    fun `任意深度都只认栈顶`() {
        for (depth in 1..8) {
            assertEquals(
                "depth=$depth 时必须是覆盖页独占",
                LayerGate.Owner.NAV,
                LayerGate.ownerOf(navDepth = depth, drawerOpened = false)
            )
            assertEquals(
                "depth=$depth 时抽屉也不能抢",
                LayerGate.Owner.NAV,
                LayerGate.ownerOf(navDepth = depth, drawerOpened = true)
            )
        }
    }

    /**
     * 【负深度的防御】栈深是派生量，理论上不会是负数。
     * 但若上游算错（例如减掉栈底时多减一次），必须安全退化成底座而不是抛异常或误判成 NAV。
     */
    @Test
    fun `非法栈深退化为底座`() {
        assertEquals(LayerGate.Owner.BASE, LayerGate.ownerOf(navDepth = -1, drawerOpened = false))
        assertEquals(LayerGate.Owner.DRAWER, LayerGate.ownerOf(navDepth = -1, drawerOpened = true))
    }
}