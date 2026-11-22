package com.dollhouse.app.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ui.design.DhDesign

/**
 * 竖向排列 + **自动在相邻非空子项之间画发丝线**。
 *
 * ## 它解决什么问题
 * 现在的写法是「行 + 手写 `DhKit.RowDivider()` + 行 + …」，问题有三：
 *   ① 最后一行下面容易多一条线（要靠人记得别写）；
 *   ② 某一行因为条件渲染变成 0dp 时，会出现**两条线贴在一起**；
 *   ③ 每一行都要记着写分割线，漏一条就是一处视觉缺口。
 * 把「记账」交给布局阶段，调用方就只剩 `DhDividedColumn { 行A(); 行B() }` 这一种写法。
 *
 * ## 实现要点（移植自 MAA-Meow `SettingsSegments.kt:97-137` 的 `SettingsDividedColumn`）
 *   · **布局阶段写、绘制阶段读**：`lines` 在 `layout {}` 里填、在 `drawWithContent` 里画。
 *     绘制永远发生在布局之后，所以不存在读到上一帧的脏数据。
 *   · **`height > 0` 才画**：0dp 的子项既不画它上面的线，也不因为占位而多出一条。
 *   · **第一条上面不画**：`y > 0` 保证列表顶部没有多余横线。
 *   · **线不横贯**：从 `insetStart` 起画，右边到边 —— 与现有 `RowDivider(insetStart = …)` 同款。
 *
 * ## 与 MAA 的差异（本地化）
 *   · inset：MAA 用 `Separator.inset`(16)；本工程的行是**图标开头**的，
 *     现有分割线一律 `insetStart = 28`（图标宽 + 间隙），本组件默认取同一个 28，
 *     否则试点页的线会突然比别的页短一截。
 *   · 颜色：MAA 用 `outlineVariant.copy(alpha = 0.5f)`；本工程的 `line` 已经是
 *     「发丝线」令牌（对比度 1.50 / 1.32，`palette_contrast.py` 把底线钉在 1.3），
 *     再乘 0.5 会跌破可辨识下限，故直接用 `DhTokens.colors.line`。
 *   · 厚度：`DhDesign.Size.hairline`(1dp)，与 `RowDivider` 一致。
 *
 * ## 用法
 * ```kotlin
 * DhKit.Card(...) {
 *     DhDividedColumn {
 *         DhKit.PermRow(...)   // 行之间不要再写 RowDivider
 *         DhKit.PermRow(...)
 *     }
 * }
 * ```
 */
@Composable
fun DhDividedColumn(
    modifier: Modifier = Modifier,
    /** 线从左边多远处开始。默认 28 = 图标宽 + 图标文字间隙，与既有 `RowDivider` 对齐。 */
    insetStart: Dp = 28.dp,
    /** null = 用 `DhTokens.colors.line`。写成可空是为了在函数体内取色（组合期读取更稳）。 */
    color: Color? = null,
    content: @Composable () -> Unit,
) {
    // 布局阶段写、绘制阶段读（绘制总在布局之后）
    val lines = remember { ArrayList<Int>() }
    val thickness = DhDesign.Size.hairline
    val lineColor = color ?: DhTokens.colors.line
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val inset = insetStart.toPx()
                val t = thickness.toPx()
                lines.forEach { y ->
                    drawRect(
                        color = lineColor,
                        topLeft = Offset(inset, y.toFloat()),
                        size = Size((size.width - inset).coerceAtLeast(0f), t),
                    )
                }
            },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val heights = placeables.map { it.height }
        // 【关键】用同一个纯函数算「线画在哪」，它被单测覆盖 ——
        //   这样「0dp 不画线 / 首条上面不画」这两条规则不是靠肉眼验的。
        val ys = dividerYs(heights)
        layout(constraints.maxWidth, heights.sum()) {
            lines.clear()
            lines.addAll(ys)
            var y = 0
            placeables.forEach { p ->
                p.place(0, y)
                y += p.height
            }
        }
    }
}

/**
 * 给定各子项高度，返回**分割线的 y 坐标**（相对本组件顶部）。
 *
 * 抽成纯函数是为了可测：`DhDividedColumn` 里那条「0dp 项不产生双线」的规则
 * 无法在无渲染的 JVM 测试里观察，但可以完整地在这里验证。
 *
 * 规则：
 *   · 第一项上方不画（`y > 0`）
 *   · 高度为 0 的项上方不画（`h > 0`）—— 它连"占位"都没有，画了就是双线
 */
internal fun dividerYs(heights: List<Int>): List<Int> {
    val out = ArrayList<Int>(heights.size)
    var y = 0
    for (h in heights) {
        if (y > 0 && h > 0) out += y
        y += h
    }
    return out
}
