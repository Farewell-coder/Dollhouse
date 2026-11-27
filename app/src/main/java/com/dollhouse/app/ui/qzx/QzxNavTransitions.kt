package com.dollhouse.app.ui.qzx

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import com.dollhouse.app.ui.design.DhDesign

/**
 * QZX 的**分层**页面转场（`LAYERED`），对应 `QZXGeometryXNavigation` 的层级式切换。
 *
 * ## 为什么是「分层」而不是「平推」
 *
 * 平推（`slideIn` + `slideOut` 同时整屏移动）把两页当成**同一层**的图片在传送带上走。
 * 分层则是：**新页是"盖上去的一张纸"，旧页是被压到下面去的那张** ——
 * 旧页会缩小 + 变暗 + 稍微左移，读作"退到后层"。iOS 与 QZX 都用后者，
 * 因为有纵深，用户能感到「我现在在第几层」。
 *
 * ## 前进（forward）
 *
 * | 角色 | 效果 | 时长 |
 * |---|---|---|
 * | 新页 | 从**右侧整屏**滑入 + 淡入 | **220ms**（`Motion.enter`） |
 * | 旧页 | `scale 1 → 0.92` + `fadeOut` + **左移 1/8 屏宽** | **180ms**（`Motion.exit`） |
 *
 * ## 返回（back）—— **镜像，但绝不是复用前进的 spec**
 *
 * | 角色 | 效果 | 时长 |
 * |---|---|---|
 * | 被露出的后层页 | `scale 0.92 → 1`（长回来）+ 淡入 + **从左侧 1/8 归位** | 220ms |
 * | 当前页 | 往**右侧整屏**退出 + 淡出 | 180ms |
 *
 * 【为什么必须分开写】若返回也套前进的 spec，视觉上会变成「又往前走了一步」——
 * 旧页继续缩小、新页从右边推进来，用户会觉得点返回反而深入了一层。
 * 方向感是转场唯一的语义载体，这条不能省。
 *
 * ## `animOn = false`
 *
 * 四个工厂全部返回 `EnterTransition.None` / `ExitTransition.None`
 * （系统「移除动画」时页面直接切换，不做任何插值）。
 *
 * ## 用法
 *
 * 本文件**不碰** `NavHost`（旧导航仍在用旧转场）。接入新导航时：
 *
 * ```kotlin
 * AnimatedContent(
 *     targetState = backStack.last(),
 *     transitionSpec = {
 *         QzxNavTransitions.layered(
 *             animOn = DhAnim.reduceMotion().not(),   // 或系统 animator duration scale
 *             forward = isForward,                    // 由导航层判定本次是进还是退
 *         )
 *     },
 * ) { route -> /* 渲染 QzxPage */ }
 * ```
 */
object QzxNavTransitions {

    /** 进入时长（ms）。`DhDesign.Motion.enter` = 220。 */
    const val ENTER_MS = DhDesign.Motion.enter

    /** 退出时长（ms）。`DhDesign.Motion.exit` = 180 —— **退出比进入快**是既有口径。 */
    const val EXIT_MS = DhDesign.Motion.exit

    /** 前进时旧页缩小到的比例。 */
    const val PUSHED_BACK_SCALE = 0.92f

    /**
     * 旧页左移量占屏宽的比例：**1/8**。
     *
     * 【为什么是 1/8 而不是整屏】整屏移动是「平推」的语汇；分层转场里旧页只是被
     * 挤开一点，位移过大反而会露出背景、失去"还在桌上"的感觉。
     */
    const val PUSHED_BACK_SHIFT = 0.125f

    /**
     * 旧页缩小的原点。
     *
     * 【为什么取 Center】取 TopCenter 会让卡片顶部钉死、下半部收进去，像"卷帘"；
     *   Center 是均匀后退，符合"整张纸退到后层"的物理直觉。
     */
    private val SCALE_ORIGIN = TransformOrigin.Center

    /* ============================ 前进 ============================ */

    /** 前进：新页从右侧整屏滑入 + 淡入。 */
    fun enterForward(animOn: Boolean): EnterTransition =
        if (!animOn) {
            EnterTransition.None
        } else {
            slideInHorizontally(animationSpec = tween(ENTER_MS)) { fullWidth -> fullWidth } +
                fadeIn(animationSpec = tween(ENTER_MS))
        }

    /** 前进：旧页缩小到 [PUSHED_BACK_SCALE] + 淡出 + 左移 [PUSHED_BACK_SHIFT]。 */
    fun exitForward(animOn: Boolean): ExitTransition =
        if (!animOn) {
            ExitTransition.None
        } else {
            scaleOut(
                animationSpec = tween(EXIT_MS),
                targetScale = PUSHED_BACK_SCALE,
                transformOrigin = SCALE_ORIGIN,
            ) +
                fadeOut(animationSpec = tween(EXIT_MS)) +
                slideOutHorizontally(animationSpec = tween(EXIT_MS)) { fullWidth ->
                    // 左移 1/8 屏宽
                    -fullWidth * PUSHED_BACK_SHIFT
                }
        }

    /* ============================ 返回（镜像） ============================ */

    /** 返回：被露出的后层页从 [PUSHED_BACK_SCALE] 长回 1 + 淡入 + 从左侧 1/8 归位。 */
    fun enterBack(animOn: Boolean): EnterTransition =
        if (!animOn) {
            EnterTransition.None
        } else {
            scaleIn(
                animationSpec = tween(ENTER_MS),
                initialScale = PUSHED_BACK_SCALE,
                transformOrigin = SCALE_ORIGIN,
            ) +
                fadeIn(animationSpec = tween(ENTER_MS)) +
                slideInHorizontally(animationSpec = tween(ENTER_MS)) { fullWidth ->
                    // 从左边 1/8 处归位（与前进时旧页左移的量对称）
                    -fullWidth * PUSHED_BACK_SHIFT
                }
        }

    /** 返回：当前页往右侧**整屏**退出 + 淡出。 */
    fun exitBack(animOn: Boolean): ExitTransition =
        if (!animOn) {
            ExitTransition.None
        } else {
            slideOutHorizontally(animationSpec = tween(EXIT_MS)) { fullWidth -> fullWidth } +
                fadeOut(animationSpec = tween(EXIT_MS))
        }

    /* ============================ 组合成 ContentTransform ============================ */

    /**
     * 直接喂给 `AnimatedContent(transitionSpec = { ... })` 的成品。
     *
     * @param animOn false 时进出都是 None（系统关动画）
     * @param forward true = 前进（新页盖上来）；false = 返回（当前页退出去）
     */
    fun layered(animOn: Boolean, forward: Boolean): ContentTransform =
        if (forward) {
            enterForward(animOn) togetherWith exitForward(animOn)
        } else {
            enterBack(animOn) togetherWith exitBack(animOn)
        }
}
