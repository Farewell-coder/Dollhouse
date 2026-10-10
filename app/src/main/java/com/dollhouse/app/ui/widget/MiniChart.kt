package com.dollhouse.app.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】统计页用的迷你折线图（Compose 版）：自绘网格 + 折线 + 首尾点 + 首尾标签，
 *   空数据显示「暂无消耗」。
 *
 * 【入口】只由 [com.dollhouse.app.ai.TokenStat] 的周期总览卡调用。
 *
 * 【交互】不持有业务数据；点按最近的数据点后回调 [onPick]，由调用方决定怎么呈现
 *   （工程硬约束不允许浮层短提示，所以调用方把它写进图表下已有的提示行里）。
 *
 * 【迁移】r7 起由 `class MiniChart : View` 改为 `@Composable`。原实现只被 TokenStat 引用，
 *   故不保留 View 形态；对外只暴露这一个函数。
 *
 * 【坑】① 只有一个数据点时步长要兜底 1，否则 x 坐标除零；
 *   ② 全 0 数据时最大值用 1 兜底，同样是为了不除零；
 *   ③ 只画首尾两个标签，中间省略 —— 120dp 高度里塞 24 个「0 时」会糊成一片。
 *   ④ 文字不再走 Canvas.drawText（要引 TextMeasurer），改用 Compose 的 Text 叠层定位，
 *     字形 / 字体族与全局一致，且省掉一层测量依赖。
 */
@Composable
fun MiniChart(
    values: FloatArray?,
    labels: Array<String>?,
    modifier: Modifier = Modifier,
    onPick: ((index: Int, value: Float, label: String?) -> Unit)? = null
) {
    val c = DhTokens.colors
    val vals = values

    Box(modifier = modifier.fillMaxWidth().height(120.dp)) {
        if (vals == null || vals.isEmpty()) {
            Text(
                text = "暂无消耗",
                modifier = Modifier.align(Alignment.Center),
                color = c.sub,
                fontSize = 13.sp,
                fontFamily = DhTokens.fonts
            )
            return@Box
        }

        val n = vals.size
        // 全 0 时用 1 兜底，避免除零。
        var max = 1f
        for (i in 0 until n) {
            val v = valueOf(vals, i)
            if (v > max) {
                max = v
            }
        }
        val last = valueOf(vals, n - 1)
        val first = valueOf(vals, 0)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(vals, labels) {
                    detectTapGestures { off: Offset ->
                        val pick = onPick ?: return@detectTapGestures
                        val padL = 8.dp.toPx()
                        val padR = 8.dp.toPx()
                        val left = padL
                        val right = size.width - padR
                        var step = if (n > 1) (right - left) / (n - 1) else 1f
                        if (step <= 0f) {
                            step = 1f
                        }
                        var idx = Math.round((off.x - left) / step)
                        if (idx < 0) {
                            idx = 0
                        }
                        if (idx > n - 1) {
                            idx = n - 1
                        }
                        val ls = labels
                        val label = if (ls != null && ls.size == n) ls[idx] else null
                        pick(idx, valueOf(vals, idx), label)
                    }
                }
        ) {
            val padL = 8.dp.toPx()
            val padR = 8.dp.toPx()
            val padT = 10.dp.toPx()
            val padB = 18.dp.toPx()
            val left = padL
            val right = size.width - padR
            val top = padT
            val bottom = size.height - padB

            // 4 条横向网格线（含顶与底）。
            for (i in 0 until 4) {
                val y = top + (bottom - top) * i / 3f
                drawLine(color = c.line, start = Offset(left, y), end = Offset(right, y), strokeWidth = 1f)
            }

            val step = if (n > 1) (right - left) / (n - 1) else 0f
            val path = Path()
            val area = Path()
            for (i in 0 until n) {
                val x = if (n > 1) left + step * i else (left + right) / 2f
                val y = bottom - (bottom - top) * (valueOf(vals, i) / max)
                if (i == 0) {
                    path.moveTo(x, y)
                    area.moveTo(x, bottom)
                    area.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            if (n > 1) {
                area.lineTo(right, bottom)
                area.close()
                drawPath(path = area, color = c.acc.copy(alpha = 0.13f))
                drawPath(path = path, color = c.acc, style = Stroke(width = 2.5.dp.toPx()))
            }
            val rr = 2.5.dp.toPx()
            val firstX = if (n > 1) left else (left + right) / 2f
            val lastX = if (n > 1) right else (left + right) / 2f
            drawCircle(color = c.acc, radius = rr, center = Offset(firstX, bottom - (bottom - top) * (first / max)))
            drawCircle(color = c.acc, radius = rr, center = Offset(lastX, bottom - (bottom - top) * (last / max)))
        }

        // 峰值小结（左上）。
        Text(
            text = "峰值 " + max.toLong(),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp),
            color = c.sub,
            fontSize = UiKit.FS_TINY.sp,
            fontFamily = DhTokens.fonts
        )
        // 首尾标签。
        val ls = labels
        if (ls != null && ls.size == n) {
            if (ls[0].isNotEmpty()) {
                Text(
                    text = ls[0],
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 3.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            if (n > 1 && ls[n - 1].isNotEmpty()) {
                Text(
                    text = ls[n - 1],
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 3.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }
}

/** 取值：越界 / NaN / 负数一律当 0。 */
private fun valueOf(v: FloatArray, i: Int): Float {
    if (i < 0 || i >= v.size) {
        return 0f
    }
    val x = v[i]
    if (x.isNaN() || x < 0f) {
        return 0f
    }
    return x
}
