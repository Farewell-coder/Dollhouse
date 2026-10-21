package com.dollhouse.app.ui.compose

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】Compose 侧的**动画词汇表** —— 全 App 的 Compose 动画参数唯一真源。
 *
 * ## 为什么必须收口
 *
 * 改造前 Compose 动画参数散在 `DhKit` / `BottomNav` / `DhSwipe` / `NavHost` 四个文件，
 * 同一件事有三套数值：
 * · 开关位移 `ζ=0.73, k=700`；`DhSwipe` 回弹 `ζ=0.73, k=420`；`NavHost` 转场 `tween(300)`。
 * 数值本身都能跑，问题在于**「哪个是本次该用的」没有判据** ——
 * 新加一处动画时只能照抄一个邻近的，于是曲线族越抄越散，用户感知就是「各处手感不一样」。
 *
 * ## 与 View 侧的关系
 *
 * 时长与曲线直接引用 [UiKit] 的 `D_*` / `EASE_*` 常量，保证「同一个动作在 Compose 页
 * 与 View 页看起来是同一套动效」。弹簧的 `ζ` 与 View 侧 `Springs` 预置逐档对齐：
 *
 * | 语义 | View `Springs` | Compose 这里 |
 * |---|---|---|
 * | 临界阻尼、不过冲（高度 / 颜色 / 透明度） | `smooth()` ζ=1.00 | [SMOOTH] |
 * | 快、轻微过冲（位移 / 缩放 / 旋转） | `snappy()` ζ=0.73 | [SNAPPY] |
 * | 活泼回弹（按压松手） | `bouncy()` ζ=0.40 | [BOUNCY] |
 *
 * ## 硬性约束（写在这里，防止再走回头路）
 *
 * 1. **颜色动画一律用 ζ=1.0 的 [SMOOTH 族]**。Compose 的颜色 lerp 在进度 >1 时会越界，
 *    深色主题下会被夹成纯黑/纯白 —— 那就是「开关轨道闪一下」。
 * 2. **时长不写字面量**，一律 `UiKit.D_*`。
 * 3. **dt 驱动类**（跟手拖动、手势回弹）不用 tween，用弹簧。
 */
object DhAnim {

    /** 开关位移：ζ=0.73、k=700。与 `UiKit.Switch` 的 `Springs.snappy()` 同族。 */
    @JvmField
    val SW_SPRING: SpringSpec<Float> = spring(dampingRatio = 0.73f, stiffness = 700f)

    /** 开关轨道色 / 其它颜色过渡：ζ=1.0，**绝不越界**。 */
    @JvmField
    val COLOR_SPRING: SpringSpec<Color> = spring(dampingRatio = 1f, stiffness = 700f)

    /** 按压：按下 tween 跟手（[UiKit.D_PRESS]），松手 [BOUNCY] 弹回。 */
    @JvmField
    val PRESS_DOWN: TweenSpec<Float> = tween(durationMillis = UiKit.D_PRESS)

    /** 按压松手回弹：ζ=0.40、k=900，与 `Springs.bouncy()` 同族（k 更高是因为 Compose 侧量程更小）。 */
    @JvmField
    val BOUNCY: SpringSpec<Float> = spring(dampingRatio = 0.4f, stiffness = 900f)

    /** 单击放大（底栏图标）：ζ=1.0，避免图标在深色底上「抖边」。 */
    @JvmField
    val TAP_SCALE: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)

    /** 卡片展开 / 收起：时长对齐 `UiKit.D_LAYER`，曲线对齐 `UiKit.EASE_DECEL`。 */
    @JvmField
    val CARD_EXPAND: TweenSpec<IntSize> =
        tween(durationMillis = UiKit.D_LAYER, easing = LinearOutSlowInEasing)

    /** 卡片收起：用加速曲线（「离开比进入快」）。 */
    @JvmField
    val CARD_COLLAPSE: TweenSpec<IntSize> =
        tween(durationMillis = UiKit.D_MICRO, easing = FastOutLinearInEasing)

    /** 卡片展开时的透明度：与 [CARD_EXPAND] 同时长同曲线。 */
    @JvmField
    val CARD_FADE_IN: TweenSpec<Float> =
        tween(durationMillis = UiKit.D_LAYER, easing = LinearOutSlowInEasing)

    /** 卡片收起时的透明度：与 [CARD_COLLAPSE] 同时长同曲线。 */
    @JvmField
    val CARD_FADE_OUT: TweenSpec<Float> =
        tween(durationMillis = UiKit.D_MICRO, easing = FastOutLinearInEasing)

    /** 滑动行吸附：ζ=0.73、k=420 —— 与 `Springs.snappy()` **逐参数相同**。 */
    @JvmField
    val SWIPE_SETTLE: SpringSpec<Float> = spring(dampingRatio = 0.73f, stiffness = 420f)

    /** 通用淡入 / 淡出：时长对齐 `UiKit.D_MICRO`。 */
    @JvmField
    val FADE: TweenSpec<Float> = tween(durationMillis = UiKit.D_MICRO)
}
