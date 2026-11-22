package com.dollhouse.app.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [dividerYs] 的单元测试 —— 也就是 `DhDividedColumn` 那条「自动分割线」规则。
 *
 * 【为什么要抽纯函数来测】这条规则的三条分支（首条不画 / 0dp 不画 / 正常画）都发生在
 *   `Layout` 的 measure 阶段，输出是**屏幕上的一条 1dp 矩形**。没有渲染就观察不到，
 *   而渲染测试要拉 Robolectric + 截图比对，成本高且脆弱。
 *   把「算线位置」抽成 `List<Int> -> List<Int>` 的纯函数后，规则本身就完全可测了 ——
 *   而 `DhDividedColumn` 里调的**就是**这个函数，不是复制一份逻辑。
 *
 * 【最容易被忽略的一条】中间夹一个 0dp 子项时，若条件写成 `y > 0` 而不带 `h > 0`，
 *   就会在这个 0dp 项的上方**再画一条线**，与上一项下方的线贴在一起成为双线。
 *   这正是手写 `RowDivider` 时最容易出的错，也是本组件存在的理由之一。
 */
class DhDividedColumnTest {

    @Test
    fun `三行等高时画两条线，首条上方不画`() {
        // 高度 50/60/70 → 线在 y=50 与 y=110；顶部 y=0 处没有线
        assertEquals(listOf(50, 110), dividerYs(listOf(50, 60, 70)))
    }

    @Test
    fun `中间夹 0dp 子项时只画一条，不产生双线`() {
        // 50 / 0 / 70：0dp 项上方不画线，因此 y=50 处只有一条
        assertEquals(listOf(50), dividerYs(listOf(50, 0, 70)))
    }

    @Test
    fun `首项为 0dp 时顶部仍然没有线`() {
        // 0 / 60 → y 仍是 0，第二项上方不画；整列一条线都没有
        assertEquals(emptyList<Int>(), dividerYs(listOf(0, 60)))
    }

    @Test
    fun `末项为 0dp 时底部不会多出一条`() {
        assertEquals(listOf(50), dividerYs(listOf(50, 60, 0)))
    }

    @Test
    fun `全 0 高度时一条线都不画`() {
        assertEquals(emptyList<Int>(), dividerYs(listOf(0, 0, 0)))
    }

    @Test
    fun `空列不画线`() {
        assertEquals(emptyList<Int>(), dividerYs(emptyList()))
    }

    @Test
    fun `单行不画线`() {
        assertEquals(emptyList<Int>(), dividerYs(listOf(48)))
    }

    @Test
    fun `连续 0dp 不会累积出多余线`() {
        // 50 / 0 / 0 / 70：两个 0dp 都不画，只在 y=50 处一条
        assertEquals(listOf(50), dividerYs(listOf(50, 0, 0, 70)))
    }
}
