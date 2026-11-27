package com.dollhouse.app.ui.qzx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 【职责】卡片表面（QZX `QZXGeoCardView` 的等价物）。
 *
 * 三件东西：
 * 1. **边缘光**（不是描边）—— 见 [qzxRimLight]；
 * 2. **按压反馈** —— 背景 HSV 的 V 通道压到 0.92 + 缩放到 0.96，90ms 下去 / 220ms 回来；
 * 3. **深/浅主题分流** —— 深色只画光；浅色保留 1dp 描边再叠光。
 *
 * ## 为什么浅色必须留描边（这是唯一一处"不完全照抄 QZX"的地方）
 * QZX 默认关掉描边、只用边缘光。但它没考虑**浅色卡**：`BlendMode.Plus` 在 `#FAFCFE` 上
 * 会把整圈加法钳到纯白（实测对比度只有 1.028，且顶/底同值 —— 连"上亮下暗"的方向都没了）。
 * 也就是说浅色下照抄 = 卡片**彻底没有边**。所以浅色主题走"描边定边 + 光给起伏"。
 *
 * ## 圆角
 * 默认 [QzxTokens.Radius.card]（16dp）。**边缘光的路径必须用同一个圆角**，
 * 否则光会浮在圆角外侧 —— 那看起来就是"描边贴歪了"。
 */
@Composable
fun QzxSurface(
    modifier: Modifier = Modifier,
    corner: Dp = QzxTokens.Radius.card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = QzxColors.isDark()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val interactive = onClick != null

    val base = QzxColors.card
    val pressedBg = remember(base) { darkenValue(base, QzxTokens.Button.PRESS_VALUE) }
    val bg by animateColorAsState(
        targetValue = if (pressed && interactive) pressedBg else base,
        animationSpec = tween(
            if (pressed) QzxTokens.Motion.PRESS else QzxTokens.Motion.PRESS_RELEASE
        ),
        label = "qzxSurfaceBg"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && interactive) QzxTokens.Motion.PRESS_SCALE else 1f,
        animationSpec = tween(
            if (pressed) QzxTokens.Motion.PRESS else QzxTokens.Motion.PRESS_RELEASE
        ),
        label = "qzxSurfaceScale"
    )

    val shape = RoundedCornerShape(corner)
    val rim = Modifier.qzxRimLight(corner = corner, enabled = bg.alpha > 0f)
    Column(
        modifier = modifier
            // 缩放挂在最外层：连边缘光一起缩，才像"整张卡被按下去"
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(bg)
            .then(
                if (dark || !QzxTokens.RimLight.KEEP_STROKE_ON_LIGHT) {
                    // 深色：只画光。再叠描边会让边缘比卡面亮一档。
                    rim
                } else {
                    // 浅色：描边定边 + 光给"一点点起伏"（加法在浅底上几乎不可见，但不为零）
                    Modifier
                        .border(QzxTokens.Size.hairline, QzxColors.stroke, shape)
                        .then(rim)
                }
            )
            .then(
                if (interactive) {
                    Modifier
                        // 可点的卡片也要有命中区下限：一张 32dp 高的卡片是点不准的。
                        .heightIn(min = QzxTokens.Size.minTouch.dp)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick!!
                        )
                } else {
                    Modifier
                }
            ),
        content = content
    )
}

/**
 * 边缘光：一圈**加法混合**的淡白光，取代 1dp 描边。
 *
 * ## 光与颜料的区别
 * 描边是颜料：占像素、盖底色。边缘光是光：`BlendMode.Plus` 只往亮里加、**从不压暗** ——
 * 深色卡上读作镀光，浅色卡上只剩一点点起伏，永远不像贴上去一条胶带。
 *
 * ## 为什么用「垂直渐变描边」近似 dualPeak
 * 亮度其实只取决于该点是顶边还是底边：顶边法线朝上（0.09）、底边朝下（0.036）、
 * 左右边法线水平（≈0）。一条垂直渐变描边正好是这个分布，零逐像素计算、无缓存。
 * 失真是圆角：这里给的是"该点 Y 的渐变色"，真值是"该点法线的光照" —— 1dp 宽的光上看不出。
 *
 * ## 两个必要的实现细节
 * 1. **路径向内缩半个线宽**：`Stroke` 以路径为中心两侧各画一半，不缩的话有一半落在卡片外，
 *    被卡片圆角裁掉 —— 只剩 0.5dp 且边缘变硬，又变回描边了。
 * 2. **内晕是第二条更宽的描边**（宽度 `2×glow`、alpha ×0.35）：只有硬线会"锋利"，
 *    而真实玻璃的反射是有衰减的。
 */
fun Modifier.qzxRimLight(
    corner: Dp,
    width: Dp = QzxTokens.RimLight.WIDTH_DP.dp,
    topAlpha: Float = QzxTokens.RimLight.TOP_ALPHA,
    bottomAlpha: Float = QzxTokens.RimLight.BOTTOM_ALPHA,
    glow: Dp = QzxTokens.RimLight.GLOW_DP.dp,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) {
        return this
    }
    return this.drawWithContent {
        drawContent()

        val radiusPx = corner.toPx()
        val wPx = width.toPx()
        val gPx = glow.toPx()
        if (wPx <= 0f) {
            return@drawWithContent
        }

        val brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = topAlpha),
            0.5f to Color.White.copy(alpha = 0f),
            1f to Color.White.copy(alpha = bottomAlpha)
        )

        fun ring(inset: Float): Path {
            val r = (radiusPx - inset).coerceAtLeast(0f)
            return Path().apply {
                addRoundRect(
                    RoundRect(
                        left = inset,
                        top = inset,
                        right = size.width - inset,
                        bottom = size.height - inset,
                        cornerRadius = CornerRadius(r)
                    )
                )
            }
        }

        // ① 内晕：宽而淡
        if (gPx > 0f && size.width > gPx * 2f && size.height > gPx * 2f) {
            drawPath(
                path = ring(gPx),
                brush = brush,
                alpha = QzxTokens.RimLight.GLOW_ALPHA_SCALE,
                style = Stroke(width = gPx * 2f),
                blendMode = BlendMode.Plus
            )
        }
        // ② 硬线：全亮
        drawPath(
            path = ring(wPx / 2f),
            brush = brush,
            style = Stroke(width = wPx),
            blendMode = BlendMode.Plus
        )
    }
}
