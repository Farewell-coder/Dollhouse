package com.dollhouse.app.ui.compose

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】Compose 侧的**系统「移除动画」开关**（reduceMotion）。
 *
 * ## 为什么必须有这一层
 *
 * 系统设置「开发者选项 → 动画程序时长缩放 = 关闭」，或用户开了无障碍里的
 * 「移除动画」之后，`Settings.Global.ANIMATOR_DURATION_SCALE` 会变成 0。
 * Compose 的 `tween` / `spring` **不会**自己遵守它 —— 只有 M3 内部少数组件会。
 * 于是改造前本工程 118 处 `animate*` 在该设置下照旧播放，无障碍设置等于失效。
 * 对照 `MAA-Meow` 的 `MaaMotion`（`LocalReduceMotion` 全树下发）与本工程的
 * `NavHost` / `PageTransitions`（已按 `ValueAnimator.areAnimatorsEnabled()` 判定），
 * 这里补齐同一道闸门。
 *
 * ## 两条读法，缺一不可
 *
 * 1. **Compose 组合期**：根组合 [DollhouseTheme] 读系统设置，经本 local 全树下发；
 *    子组件想直接判断可以 `LocalReduceMotion.current`。
 * 2. **非组合期**（协程、View 侧、`DhSwipe.settle` 里的 `animateTo`）：
 *    `DhAnim.*` 的 getter 自己也能判断，靠 [systemFlag] 这份进程级镜像
 *    （由根组合每次组合写入，`ContentObserver` 跟随系统开关刷新）。
 *
 * 【为什么 token 从 `@JvmField val` 改成「只读取值器」】
 *   闸门必须在**取用时**生效，而不是在类初始化时冻结。`@JvmField` 只能挂字段，
 *   字段一旦初始化就再不改变，改不动；所以改成 getter：关闭动画时返回 `snap()`。
 *   返回值统一放宽到 [FiniteAnimationSpec]（`snap` / `tween` / `spring` 的共同父类），
 *   调用点拿到的仍是 `AnimationSpec`，10 处调用**零改动**。
 *
 * 【与 View 侧的口径】
 *   View 侧 `UiKit.reduceMotion()` 读的是同一个 [reduceMotion]，
 *   保证「同一屏里 Compose 段与 View 段同时归零」，不会一半动一半不动。
 */
val LocalReduceMotion = staticCompositionLocalOf<Boolean> { false }

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
 * | 临界阻尼、不过冲（高度 / 颜色 / 透明度） | `smooth()` ζ=1.00 | [COLOR_SPRING] |
 * | 快、轻微过冲（位移 / 缩放 / 旋转） | `snappy()` ζ=0.73 | [SW_SPRING] / [SWIPE_SETTLE] |
 * | 活泼回弹（按压松手） | `bouncy()` ζ=0.40 | [BOUNCY] |
 *
 * ## 硬性约束（写在这里，防止再走回头路）
 *
 * 1. **颜色动画一律用 ζ=1.0 的 [COLOR_SPRING]（smooth 族）**。Compose 的颜色 lerp 在进度 >1 时会越界，
 *    深色主题下会被夹成纯黑/纯白 —— 那就是「开关轨道闪一下」。
 * 2. **时长不写字面量**，一律 `UiKit.D_*`。
 * 3. **dt 驱动类**（跟手拖动、手势回弹）不用 tween，用弹簧。
 * 4. **系统关闭动画时一律 `snap()`** —— 见文件头的 reduceMotion 说明。
 */
object DhAnim {

    /* ---------------- reduceMotion 闸门 ---------------- */

    /**
     * 进程级镜像：根组合每次组合写入这里。
     *
     * 【为什么还要一份静态镜像】`DhAnim` 的 getter 不是 composable，拿不到
     *   `LocalReduceMotion`；而 `DhSwipe.settle` 在协程里读 token。没有这份镜像，
     *   这些非组合调用点就会成为「同一屏里唯一还在动」的漏网之鱼。
     *   `@Volatile` 是因为 View 侧 / 协程侧可能从非主线程读（组合都在主线程写）。
     */
    @Volatile
    private var systemFlag: Boolean = false

    /** 根组合把当前系统开关同步进来（幂等，多次写入同一值无副作用）。 */
    @JvmStatic
    fun syncSystemReduceMotion(value: Boolean) {
        systemFlag = value
    }

    /**
     * 当前是否应该「全部归零」。
     *
     * 【为什么优先用 [ValueAnimator.areAnimatorsEnabled]】它是系统同一个开关的官方读法，
     *   无 Context、无 ContentProvider 查询，可以在每帧的 getter 里安全调用；
     *   API 26 以下没有这个 API（minSdk 24），退回静态镜像（由根组合读设置写入）。
     */
    @JvmStatic
    fun reduceMotion(): Boolean {
        // 先看镜像：它由根组合写入，**含** `Settings.Global` 判定（比 areAnimatorsEnabled 更严），
        //   这样 CompositionLocal 与各 getter 永远是同一个答案 —— 否则会出现
        //   「上下文说关、spec 却仍返回 tween」这种自相矛盾的分裂。
        if (systemFlag) {
            return true
        }
        if (Build.VERSION.SDK_INT >= 26) {
            return try {
                !ValueAnimator.areAnimatorsEnabled()
            } catch (t: Throwable) {
                // 个别 ROM 裁剪了该方法：按「未关闭」处理，绝不因此让动画崩掉。
                false
            }
        }
        return false
    }

    /**
     * 直接读系统设置判断（供**根组合**用，需要 Context）。
     *
     * `ANIMATOR_DURATION_SCALE < 0.01` 即视为关闭 —— 与系统「移除动画」、
     * 开发者选项里把缩放拉到 0（"off"）等价。
     * 与 [ValueAnimator.areAnimatorsEnabled] 取**或**：任一判定为关就算关，
     * 这样既覆盖 API 26 以下，也覆盖「设置已改、动画器状态尚未刷新」的瞬间。
     */
    @JvmStatic
    fun systemReduceMotion(ctx: Context?): Boolean {
        var off = false
        if (Build.VERSION.SDK_INT >= 26) {
            off = try {
                !ValueAnimator.areAnimatorsEnabled()
            } catch (t: Throwable) {
                false
            }
        }
        if (off || ctx == null) {
            return off
        }
        val scale = try {
            Settings.Global.getFloat(
                ctx.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        } catch (t: Throwable) {
            // 少数 ROM 上该设置不可读（SecurityException / 键缺失）：按未关闭处理。
            1f
        }
        return scale < 0.01f
    }

    /* ---------------- 预置 spec ---------------- */

    /** 开关位移：ζ=0.73、k=700。与 `UiKit.Switch` 的 `Springs.snappy()` 同族。 */
    private val SW_SPRING_FULL: SpringSpec<Float> = spring(dampingRatio = 0.73f, stiffness = 700f)

    /** 见 [SW_SPRING_FULL]；关闭动画时 [snap]。 */
    val SW_SPRING: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else SW_SPRING_FULL

    /** 开关轨道色 / 其它颜色过渡：ζ=1.0，**绝不越界**。 */
    private val COLOR_SPRING_FULL: SpringSpec<Color> = spring(dampingRatio = 1f, stiffness = 700f)

    /** 见 [COLOR_SPRING_FULL]；关闭动画时 [snap]。 */
    val COLOR_SPRING: FiniteAnimationSpec<Color>
        get() = if (reduceMotion()) snap<Color>() else COLOR_SPRING_FULL

    /** 按压：按下 tween 跟手（[UiKit.D_PRESS]），松手 [BOUNCY] 弹回。 */
    private val PRESS_DOWN_FULL: TweenSpec<Float> = tween(durationMillis = UiKit.D_PRESS)

    /**
     * 按压按下的跟手 tween。
     *
     * 【归零后为什么是 `snap` 而不是 0ms tween】两者视觉等价（都是一帧到位），
     *   但 `snap` 不占帧、不触发插值器计算，是官方给的「关闭动画」写法。
     */
    val PRESS_DOWN: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else PRESS_DOWN_FULL

    /** 按压松手回弹：ζ=0.40、k=900，与 `Springs.bouncy()` 同族（k 更高是因为 Compose 侧量程更小）。 */
    private val BOUNCY_FULL: SpringSpec<Float> = spring(dampingRatio = 0.4f, stiffness = 900f)

    /** 见 [BOUNCY_FULL]；关闭动画时 [snap]。 */
    val BOUNCY: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else BOUNCY_FULL

    /** 单击放大（底栏图标）：ζ=1.0，避免图标在深色底上「抖边」。 */
    private val TAP_SCALE_FULL: SpringSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)

    /** 见 [TAP_SCALE_FULL]；关闭动画时 [snap]。 */
    val TAP_SCALE: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else TAP_SCALE_FULL

    /**
     * 卡片展开 / 收起。
     *
     * 【时长来源·本批收敛】展开属于「进入」，改取 `DhDesign.Motion.enter`（220ms）；
     *   收起属于「退出」，取 `DhDesign.Motion.exit`（180ms）。
     *   收敛前这里是 `UiKit.D_LAYER`(260) / `UiKit.D_MICRO`(180)：260 不在设计稿阶梯上，
     *   于是「同一件事（一块内容从无到有）在设置页 260ms、在转场 220ms」，手感不一致。
     *   曲线保持原样（LinearOutSlowInEasing / FastOutLinearInEasing），本批不动曲线。
     */
    private val CARD_EXPAND_FULL: TweenSpec<IntSize> =
        tween(durationMillis = DhDesign.Motion.enter, easing = LinearOutSlowInEasing)

    /** 见 [CARD_EXPAND_FULL]；关闭动画时 [snap]（内容直接展开到位，不留中间高度）。 */
    val CARD_EXPAND: FiniteAnimationSpec<IntSize>
        get() = if (reduceMotion()) snap<IntSize>() else CARD_EXPAND_FULL

    /** 卡片收起：用加速曲线（「离开比进入快」）。 */
    private val CARD_COLLAPSE_FULL: TweenSpec<IntSize> =
        tween(durationMillis = DhDesign.Motion.exit, easing = FastOutLinearInEasing)

    /** 见 [CARD_COLLAPSE_FULL]；关闭动画时 [snap]。 */
    val CARD_COLLAPSE: FiniteAnimationSpec<IntSize>
        get() = if (reduceMotion()) snap<IntSize>() else CARD_COLLAPSE_FULL

    /** 卡片展开时的透明度：与 [CARD_EXPAND] 同时长同曲线。 */
    private val CARD_FADE_IN_FULL: TweenSpec<Float> =
        tween(durationMillis = DhDesign.Motion.enter, easing = LinearOutSlowInEasing)

    /** 见 [CARD_FADE_IN_FULL]；关闭动画时 [snap]。 */
    val CARD_FADE_IN: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else CARD_FADE_IN_FULL

    /** 卡片收起时的透明度：与 [CARD_COLLAPSE] 同时长同曲线。 */
    private val CARD_FADE_OUT_FULL: TweenSpec<Float> =
        tween(durationMillis = DhDesign.Motion.exit, easing = FastOutLinearInEasing)

    /** 见 [CARD_FADE_OUT_FULL]；关闭动画时 [snap]。 */
    val CARD_FADE_OUT: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else CARD_FADE_OUT_FULL

    /** 滑动行吸附：ζ=0.73、k=420 —— 与 `Springs.snappy()` **逐参数相同**。 */
    private val SWIPE_SETTLE_FULL: SpringSpec<Float> = spring(dampingRatio = 0.73f, stiffness = 420f)

    /** 见 [SWIPE_SETTLE_FULL]；关闭动画时 [snap]（滑开/复位一帧到位）。 */
    val SWIPE_SETTLE: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else SWIPE_SETTLE_FULL

    /**
     * 通用淡入 / 淡出。
     *
     * 【为什么取最轻的一档】淡入淡出不承载结构变化，只做「出现 / 消失」的过渡，
     *   用 `DhDesign.Motion.micro`（160ms）最短的一档；拉长会让界面显得迟钝。
     *   收敛前是 `UiKit.D_MICRO`(180)。
     */
    private val FADE_FULL: TweenSpec<Float> = tween(durationMillis = DhDesign.Motion.micro)

    /** 见 [FADE_FULL]；关闭动画时 [snap]。 */
    val FADE: FiniteAnimationSpec<Float>
        get() = if (reduceMotion()) snap<Float>() else FADE_FULL
}