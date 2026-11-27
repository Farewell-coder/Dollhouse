package com.dollhouse.app.ui.qzx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 按钮的三种语义。 */
enum class QzxButtonStyle {
    /** 主按钮：主色底 + 主色上的字。 */
    Primary,

    /** 次按钮：软底 + 主文字。 */
    Secondary,

    /** 幽灵按钮：无底，只有主色字（用于行内轻操作）。 */
    Ghost,
}

/**
 * 【职责】按钮（QZX `QZXGeoButton` 的等价物，只保留三层反馈）。
 *
 * ## 做与不做（这是刻意的减法）
 * QZX 的按钮一次按下会触发 6 层反馈。这里只做 **3 层**：
 * | 层 | 本实现 | 理由 |
 * |---|---|---|
 * | ① 基色 + 缩放 | ✅ V 通道 ×0.92 + scale 0.96（90ms 下 / 220ms 回） | 最基本的"被按住" |
 * | ② 高光跟随 | ❌ **不做** | 用户明确说那个圆点突兀 |
 * | ③ 外发光 | ✅ 80dp、强度 0.1 | 氛围层，很淡 |
 * | ④ Loading | ✅ 转圈、隐藏文字、禁点 | 常规能力 |
 * | ⑤ 拖动形变 | ❌ **不做** | 按钮在列表里会和父滚动抢手势（QZX 自己也警告了） |
 * | ⑥ 释放扩散 | ✅ 300ms 从触点荡到 100dp | 收尾动作，观感回报最高 |
 *
 * ## 释放扩散：**每次从配置算半径，绝不回写字段**
 * QZX 的 `startReleaseDiffusion` 会把扩散半径解算成 `max(配置, 最大边 × 1.5)` 并**写回属性** ——
 * 于是一次扩散之后半径永久变大，按钮越用涟漪越大（文档 §1.3 坑 2）。
 * 本实现里扩散半径**只有一个来源**：[QzxTokens.Button.DIFFUSION_RADIUS_DP] × 进度。
 * 没有可写的字段，也就没有"越用越大"这种事 —— 这是从结构上杜绝，不是靠"记得别写"。
 *
 * ## 外发光画在 bounds 之外
 * `drawBehind` 画的圆会超出按钮矩形。Compose 默认不裁剪，所以祖先不 `clip` 时它能透出来
 * （[QzxSurface] 只对背景做圆角、不裁剪子树，因此卡片里的按钮也能发光）。
 */
@Composable
fun QzxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: QzxButtonStyle = QzxButtonStyle.Primary,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    val c = QzxColors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading

    val baseBg = when (style) {
        QzxButtonStyle.Primary -> c.accent
        QzxButtonStyle.Secondary -> c.soft
        QzxButtonStyle.Ghost -> Color.Transparent
    }
    val fg = when (style) {
        QzxButtonStyle.Primary -> c.onAccent
        QzxButtonStyle.Secondary -> c.title
        QzxButtonStyle.Ghost -> c.accent
    }
    val pressedBg = remember(baseBg) { darkenValue(baseBg, QzxTokens.Button.PRESS_VALUE) }
    val bg by animateColorAsState(
        targetValue = if (pressed && active) pressedBg else baseBg,
        animationSpec = tween(
            if (pressed) QzxTokens.Motion.PRESS else QzxTokens.Motion.PRESS_RELEASE
        ),
        label = "qzxButtonBg"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && active) QzxTokens.Motion.PRESS_SCALE else 1f,
        animationSpec = tween(
            if (pressed) QzxTokens.Motion.PRESS else QzxTokens.Motion.PRESS_RELEASE
        ),
        label = "qzxButtonScale"
    )

    // ── 释放扩散 ─────────────────────────────────────────────────────
    // progress: 0 = 刚松手（涟漪最小、最亮），1 = 结束（不可见）。
    // **它只是一个进度**，半径每次现算 —— 见类注释里关于 QZX 那个 bug 的说明。
    val diffusion = remember { Animatable(1f) }
    var releaseOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var wasPressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pressed) {
        if (pressed) {
            wasPressed = true
        } else if (wasPressed) {
            wasPressed = false
            scope.launch {
                diffusion.snapTo(0f)
                diffusion.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(QzxTokens.Motion.RELEASE_DIFFUSION)
                )
            }
        }
    }

    val shape = RoundedCornerShape(QzxTokens.Radius.button)
    val glowRadiusPx = QzxTokens.Button.GLOW_RADIUS_DP.dp
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else QzxTokens.Button.DISABLED_ALPHA
            }
            // 发光与涟漪都画在**背景之下、边界之外**：
            //   drawBehind 在 clip 之外，所以圆能超出按钮矩形（不被圆角裁掉）。
            .drawBehind {
                val r = glowRadiusPx.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = QzxTokens.Button.GLOW_INTENSITY),
                            Color.Transparent
                        ),
                        center = center,
                        radius = r
                    ),
                    radius = r,
                    center = center
                )

                val p = diffusion.value
                if (p < 1f && releaseOrigin != Offset.Unspecified) {
                    // 【唯一来源】半径 = 配置常量 × 进度。这里**没有**任何可写回的状态。
                    val rippleRadius =
                        QzxTokens.Button.DIFFUSION_RADIUS_DP.dp.toPx() * p
                    if (rippleRadius > 0.5f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(
                                        alpha = QzxTokens.Button.DIFFUSION_INTENSITY * (1f - p)
                                    ),
                                    Color.Transparent
                                ),
                                center = releaseOrigin,
                                radius = rippleRadius
                            ),
                            radius = rippleRadius,
                            center = releaseOrigin
                        )
                    }
                }
            }
            .clip(shape)
            .background(bg)
            .qzxRimLight(
                corner = QzxTokens.Radius.button,
                enabled = bg.alpha > 0f
            )
            // 记录最后触点（涟漪圆心）。Initial 阶段**只观察不消费** ——
            //   抢了手势按钮就点不动了。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            releaseOrigin = change.position
                        }
                    }
                }
            }
            // 【命中区下限】48dp 是可用性底线（QZX 的按钮是 44dp，低于底线，
            //   这里取令牌里的 minTouch）。紧挨着 clickable 写，是因为
            //   "命中区"和"可点"是同一件事的两面 —— 分开写迟早会有人只改一处。
            .defaultMinSize(minHeight = QzxTokens.Size.minTouch.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .padding(
                    horizontal = QzxTokens.Button.PAD_H_DP.dp,
                    vertical = QzxTokens.Space.sm
                ),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                QzxSpinner(color = fg)
            } else {
                Text(
                    text = text,
                    color = fg,
                    fontSize = QzxTokens.Type.ROW_TITLE_SP.sp,
                    lineHeight = (QzxTokens.Type.ROW_TITLE_SP + QzxTokens.Type.LINE_GAP_SP).sp,
                    maxLines = 1,
                    // loading 时文字被转圈取代 —— QZX 是临时 setText("") 再恢复，
                    // 这里直接不组合文字节点，语义更干净（不会有"空文本但有按钮宽度"的抖动）。
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 转圈。半径 10dp / 粗细 4dp（QZX 默认值）。 */
@Composable
private fun QzxSpinner(color: Color) {
    val transition = rememberInfiniteTransition(label = "qzxSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing)
        ),
        label = "qzxSpinnerAngle"
    )
    val radius = QzxTokens.Button.LOADING_RADIUS_DP.dp
    val stroke = QzxTokens.Button.LOADING_STROKE_DP.dp
    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(radius * 2 + stroke * 2)
    ) {
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
        )
    }
}
