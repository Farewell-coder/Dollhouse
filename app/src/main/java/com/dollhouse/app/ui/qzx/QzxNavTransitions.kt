package com.dollhouse.app.ui.qzx

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * 【职责】新系统的**分层转场**定义（QZX LAYERED）。
 *
 * 与旧 `ui/nav/NavHost.kt` 里那套是**同一份语义**，这里是它的可复用形态：
 * 旧 NavHost 切过来时把 `pageEnter/pageExit/pagePopEnter/pagePopExit` 换成本对象的四个函数即可
 * （本批不改 NavHost —— 那是旧系统的文件）。
 *
 * ## 三件事同时发生，缺一件就不是"分层"
 * 1. **新页从右侧推入**（`slideInHorizontally { it }` + 淡入，220ms）；
 * 2. **旧页缩小**到 [QzxTokens.Motion.PAGE_BACK_SCALE]（0.92）—— 这一下才让它读作
 *    "被抽出来的卡片"，而不是原地溶解；
 * 3. **旧页只左移 1/8 屏宽** —— 走满屏就变回"普通横向滑动"，旧页被整个推出视野、后面什么也不剩。
 *
 * 叠上 `fadeOut` 就是"变暗"：缩小的同时透明度下降，读作退到后层。
 *
 * ## 返回必须**镜像**
 * 分层转场的语义是"谁在前面谁大"。前进时新页从右推入、旧页缩小退后；
 * 返回时必须反过来 —— **后面那页从 0.92 长回 1**（它正回到前台），当前页往**右**退出。
 * 若 pop 复用前进那条，返回时会看到"上一页从右边滑进来"：方向与用户刚才推入时一致，
 * 读起来像**又往前走了一步**。
 *
 * ## 时长：退出(180) < 进入(220)
 * 两层都以半透明共存的时间越短，重影越少。这不是随手取的值，是旧系统踩过的坑
 * （详见 NavHost 里那段注释），新系统沿用同一节奏。
 *
 * ## 为什么这些函数都收 `animOn`
 * 系统关掉动画时（`ValueAnimator.areAnimatorsEnabled() == false`），三者必须**同时**变成
 * `None`。把开关做成参数而不是在函数内部读，是为了让调用方能在**一次组合里只读一次**，
 * 四个转场不会出现"进场关、退场开"这种半开状态。
 */
object QzxNavTransitions {

    /**
     * 系统当前是否允许播放动画。
     *
     * 读的是 `ValueAnimator` 而不是 Compose 的 `LocalMotionDurationScale`：后者只影响
     * `tween` 的**时长缩放**，而这里要的是"要不要播"这个二值判断（关动画时应当**完全不动**，
     * 而不是"动 0.1 毫秒"）。
     */
    fun animatorsEnabled(): Boolean = android.animation.ValueAnimator.areAnimatorsEnabled()

    /** 前进：新页从右推入。 */
    fun enter(animOn: Boolean = animatorsEnabled()): EnterTransition =
        if (!animOn) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = tween(QzxTokens.Motion.ENTER)) +
                slideInHorizontally(
                    animationSpec = tween(QzxTokens.Motion.ENTER)
                ) { it }
        }

    /** 前进：旧页缩小 + 变暗 + 轻微左移。 */
    fun exit(animOn: Boolean = animatorsEnabled()): ExitTransition =
        if (!animOn) {
            ExitTransition.None
        } else {
            scaleOut(
                targetScale = QzxTokens.Motion.PAGE_BACK_SCALE,
                animationSpec = tween(QzxTokens.Motion.EXIT)
            ) +
                fadeOut(animationSpec = tween(QzxTokens.Motion.EXIT)) +
                slideOutHorizontally(
                    animationSpec = tween(QzxTokens.Motion.EXIT)
                ) { -it / QzxTokens.Motion.PAGE_EXIT_SHIFT_DENOMINATOR }
        }

    /** 返回：后面那页从 0.92 长回 1。 */
    fun popEnter(animOn: Boolean = animatorsEnabled()): EnterTransition =
        if (!animOn) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = tween(QzxTokens.Motion.ENTER)) +
                scaleIn(
                    initialScale = QzxTokens.Motion.PAGE_BACK_SCALE,
                    animationSpec = tween(QzxTokens.Motion.ENTER)
                )
        }

    /** 返回：当前页往右整屏退出。 */
    fun popExit(animOn: Boolean = animatorsEnabled()): ExitTransition =
        if (!animOn) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = tween(QzxTokens.Motion.EXIT)) +
                slideOutHorizontally(
                    animationSpec = tween(QzxTokens.Motion.EXIT)
                ) { it }
        }

    /** 前进方向的 `ContentTransform`，可直接给 `transitionSpec`。 */
    fun forward(animOn: Boolean = animatorsEnabled()): ContentTransform =
        enter(animOn) togetherWith exit(animOn)

    /** 返回方向的 `ContentTransform`，给 `popTransitionSpec` / `predictivePopTransitionSpec`。 */
    fun back(animOn: Boolean = animatorsEnabled()): ContentTransform =
        popEnter(animOn) togetherWith popExit(animOn)
}
