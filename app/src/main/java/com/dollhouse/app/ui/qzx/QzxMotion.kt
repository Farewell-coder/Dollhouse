package com.dollhouse.app.ui.qzx

import android.animation.ValueAnimator
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 新 UI 系统自己的动画令牌（替代旧系统 Compose 层的那份动画令牌）。
 *
 * ## 为什么新系统必须自带一份
 *
 * 用户要求「把与现在 UI 有关的代码全部删掉」。旧 `DhAnim` 属于旧系统；
 * 新包若 import 它，**删旧系统时新系统会跟着一起死**。所以这里是**独立实现**，
 * 不引用旧包任何一个符号。
 *
 * ## 数值必须与旧实现逐值相同
 *
 * 手感是用户已经验收过的东西，换实现不能换手感。三个值都照抄旧 `DhAnim`：
 *
 * | 令牌 | 值 | 旧实现出处 |
 * |---|---|---|
 * | 按下跟手 | `tween(90ms)` | `PRESS_DOWN_FULL = tween(UiKit.D_PRESS)`，`D_PRESS = 90` |
 * | 松手回弹 | `spring(ζ=0.40, k=900)` | `BOUNCY_FULL = spring(0.4f, 900f)` |
 * | 关动画时的退化 | `snap()` | 两处都是 `if (reduceMotion()) snap()` |
 *
 * ## 为什么 `reduceMotion()` 要读 `ValueAnimator.areAnimatorsEnabled()`
 *
 * 因为它**不是**组合期状态：`Modifier.graphicsLayer { }` / `NestedScrollConnection`
 * 的协程里都要用（那些地方读不到 `CompositionLocal`）。
 * 系统「移除动画」对应的就是 `areAnimatorsEnabled() == false` —— 这是进程级镜像，
 * 任何线程、任何阶段读都安全。
 *
 * 【为什么不用 `Settings.Global.ANIMATOR_DURATION_SCALE`】那需要读 Settings，
 *   还要开权限风险；`areAnimatorsEnabled()` 是系统给的官方等价物，且随开发者选项实时变化。
 */
object QzxMotion {

    /** 系统「移除动画」是否开启。进程级，非组合期也能读（`graphicsLayer` 里要用）。 */
    fun reduceMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

    /**
     * 按下跟手：**90ms tween**（[QzxTokens.Motion.PRESS]）。
     *
     * 【为什么按下用 tween 而松手用弹簧】按下要"立刻跟手"，任何回弹都会让手指觉得
     *   元素在躲；松手才允许有回弹（那一下是"释放"的语义）。旧系统同口径。
     */
    private val PRESS_DOWN_FULL: TweenSpec<Float> =
        tween(durationMillis = QzxTokens.Motion.PRESS)

    /** 见 [PRESS_DOWN_FULL]；关动画时 [snap]（一帧到位，不占帧、不跑插值器）。 */
    val PRESS_DOWN: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else PRESS_DOWN_FULL

    /**
     * 松手回弹：**ζ = 0.40、k = 900**。
     *
     * 【为什么 k 取 900 这么高】Compose 侧的位移量程比 View 侧小
     *   （越界回弹最多 ~1667px 但实际可见段很短），同样的刚度在 Compose 上看着更"软"，
     *   所以旧系统把 k 提到 900 与 View 侧的 `Springs.bouncy()` 对齐观感。照抄，不要"优化"。
     */
    private val BOUNCY_FULL: SpringSpec<Float> = spring(dampingRatio = 0.4f, stiffness = 900f)

    /** 见 [BOUNCY_FULL]；关动画时 [snap]。 */
    val BOUNCY: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else BOUNCY_FULL
}
