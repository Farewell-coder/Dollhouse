package com.dollhouse.app.ui.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.sign

/**
 * 【职责】QZX 风格的**统一越界回弹物理** + **卡片按压 / 拖动形变**。
 *
 * ## 出处与可信度
 *
 * 参数抄自 QZX 组件库文档（<https://qzxui.geometryos.cn/>）的实测值。**QZX 是 Java + Views
 * 库、源码不可得**，所以这里移植的是它的**行为契约**，不是逐行翻译：
 * 文档给了常量（`MAX_OVERSCROLL` / `DAMPING_FACTOR` / 速度系数），但**没给公式** ——
 * 下面凡是"公式"的地方都用注释写清「为什么要这么取」，方便日后按真机手感微调。
 *
 * ## 为什么值得移植
 *
 * QZX 把三种滚动宿主（`ScrollView` / `RecyclerView` / `NestedScrollView`）的越界物理
 * **收成同一套常量**，文档原话是「这是刻意的」。本工程的滚动容器散在十几处页面里，
 * 每处各写一套手感必然不一致 —— 所以这里的入口同样只开一个：`Modifier.bounceOverscroll`。
 *
 * ## 与现有动画体系的关系
 *
 * · **动画一律走 [DhAnim]**：回弹用 `DhAnim.BOUNCY`、冲出去用 `DhAnim.PRESS_DOWN`。
 *   两者都是 getter，在 `reduceMotion` 开启时返回 `snap()` —— 于是**自动降级**，
 *   本文件不需要自己判断一次。
 * · **压感与 `pressable` 并存，不替换**：`pressable` 的 `PRESS_SCALE = 0.98f` 有 125 处
 *   调用点，是"全 App 统一手感"的既有基线；QZX 的卡片是 **0.96**（更重）。
 *   本文件的 [DhBounce.CARD_TOUCH_SCALE] 供**卡片类**容器单独选用，两者互不干扰，
 *   是否统一是产品决定，不是这里能顺手改的。
 */
object DhBounce {

    // ── 越界回弹（QZX 文档实测值，逐位对齐） ──────────────────────────────────

    /** 越界位移的硬上限（px）。QZX: `MAX_OVERSCROLL = 2500`。 */
    const val MAX_OVERSCROLL = 2500f

    /**
     * 阻尼因子。QZX: `DAMPING_FACTOR = 1.5`。
     *
     * 【它在这里的含义】QZX 没给公式，本实现取「**阻尼后的实际可达上限 = MAX / DAMPING**」：
     *   起点仍然 1:1 跟手（不然一上手就觉得拖不动），越拉越难拉，最终渐近于 `2500/1.5 ≈ 1667px`。
     *   也就是说 `MAX_OVERSCROLL` 是"理论绝对值"，用户能拉到的是它的一半多。
     */
    const val DAMPING_FACTOR = 1.5f

    /**
     * 松手甩动时，速度换算成越界距离的系数。QZX: `ScrollView` 系 `0.2`、`RecyclerView` 系 `0.02`。
     *
     * 【为什么差 10 倍】RecyclerView 自带 fling 惯性，若再按 0.2 换算，一次猛甩会把内容
     *   甩出屏幕一半；ScrollView 没有惯性，越界就是它唯一的"甩动反馈"，所以给得大。
     *   本工程的 Compose 滚动**自带 fling** ⇒ 语义上更接近 RecyclerView。
     *   【但默认值仍取 0.2】因为我们要的是 QZX 的**观感**（拖到边还能再甩出去一点），
     *   而不是复刻它每个宿主的差异；0.02 在 120Hz 屏上几乎看不出来。
     *   需要更克制的页面可以显式传 [VELOCITY_COEFFICIENT_RV]。
     */
    const val VELOCITY_COEFFICIENT = 0.2f

    /** `RecyclerView` 系的速度系数（QZX 原值 0.02）。见 [VELOCITY_COEFFICIENT]。 */
    const val VELOCITY_COEFFICIENT_RV = 0.02f

    // ── 卡片形变（QZX 文档实测值） ───────────────────────────────────────────

    /**
     * 卡片按下时的缩放。QZX: `qzxCardTouchScale = 0.96`。
     *
     * 【当前**没有**调用方】QZX 的卡片是按 0.96 缩放的；但本工程的按压反馈由 `pressable`
     *   （`PRESS_SCALE = 0.98f`，125 处）统一负责，`cardDeform` 再缩一次就会叠成
     *   0.96 × 0.98 ≈ 0.94 —— 按一下陷进去太多。所以 0.96 **不参与叠加**：
     *   常量留在这里备查，等将来真的做"卡片专用按压"时单独用。
     */
    const val CARD_TOUCH_SCALE = 0.96f

    /** 拖动形变：沿拖动轴最多压缩到多少（0.12 = 12%）。 */
    const val CARD_MAX_SQUASH = 0.12f

    /** 拖动形变：横向拖满整张卡时达到最大压缩所需的「归一化位移」倍数（越小越敏感）。 */
    const val CARD_DEFORM_GAIN = 1.6f

    /** 拖动的最大倾角（度）—— 只做"被推了一下"的暗示，不做真实物理。 */
    const val CARD_MAX_TILT_DEG = 2.5f

    /**
     * 把「未阻尼的累积拖拽量」映射成「实际位移」。
     *
     * 【形状】双曲饱和：`f(x) = cap * x / (x + cap)`，其中 `cap = max / DAMPING_FACTOR`。
     *   · `f(0) = 0`，且 `f'(0) = 1` —— **起点 1:1 跟手**（这是手感的关键：
     *     若一开始就打折，用户会觉得"这块拖不动"而不是"拉到头了"）；
     *   · `x → ∞` 时 `f → cap` —— **永远越不过上限**，天然满足"不能无限拖"；
     *   · 单调递增、无回折 —— 不会出现"越拉反而回弹"的抖动。
     *
     * 【为什么不用弹簧去算这个映射】弹簧是**时间域**的（松手之后的事）；
     *   这里是**位移域**的（手指按住时的事），用弹簧会引入"按住不动却还在动"的怪相。
     */
    fun damped(raw: Float, max: Float = MAX_OVERSCROLL): Float {
        if (raw == 0f || max <= 0f) {
            return 0f
        }
        val cap = max / DAMPING_FACTOR
        val a = abs(raw)
        return cap * a / (a + cap) * sign(raw)
    }
}

/**
 * 给滚动容器加**越界回弹**（QZX `QZXGeometryXBounceScrollView` 的 Compose 等价物）。
 *
 * ## 用法
 * ```kotlin
 * val scroll = rememberScrollState()
 * Column(
 *     Modifier
 *         .bounceOverscroll(scroll)   // ← 必须在外层
 *         .verticalScroll(scroll)
 * ) { … }
 * ```
 * **顺序不能反**：`nestedScroll` 要挂在 `verticalScroll` **外面**，才收得到
 * "列表已经到边、还剩这些没滚掉"的那部分位移（`onPostScroll` 的 `available`）。
 *
 * ## 为什么用嵌套滚动而不是自己写手势
 * 用户手指在列表里拖，Dp 上与 `ScrollState` 共享同一套手势；自己再挂 `pointerInput`
 * 会和列表抢手势、还会破坏 fling。嵌套滚动是官方给"父容器要知道子容器滚到边了"留的口子。
 *
 * ## 行为契约（对应 QZX 的四个特征）
 * 1. **上限**：[DhBounce.damped] 渐近于 `maxOverscroll / DAMPING_FACTOR`，拖不出屏幕；
 * 2. **阻尼**：越接近上限能拖的距离越小（同上）；
 * 3. **松手回弹**：走 [DhAnim.BOUNCY]（ζ=0.40 的弹簧）弹回 0，不是线性归位；
 * 4. **速度→越界**：`onPreFling` 里把剩余甩动速度按 [DhBounce.VELOCITY_COEFFICIENT]
 *    换算成额外越界量，先用 [DhAnim.PRESS_DOWN] 冲出去再弹回来 —— 也就是"甩到头还会弹一下"。
 *
 * ## reduceMotion
 * 开启"移除动画"时，[DhAnim.BOUNCY] / [DhAnim.PRESS_DOWN] 自身退化成 `snap()`：
 * **位移仍然存在（用户拖出来的越界照样渲染），但松手是一帧归零，没有弹的过程。**
 * 这正是无障碍要求的"去掉动画、保留状态变化"，本文件不需要额外分支。
 *
 * @param state 宿主滚动状态。只用来判断"此刻是否正在拖"——越界量本身与页面尺寸无关
 *              （QZX 用的也是与屏幕无关的固定常量）。
 * @param maxOverscroll 越界上限（px）；一般不需要改。
 */
@Composable
fun Modifier.bounceOverscroll(
    state: ScrollState,
    maxOverscroll: Float = DhBounce.MAX_OVERSCROLL,
): Modifier = bounceOverscrollImpl(
    isScrollInProgress = { state.isScrollInProgress },
    maxOverscroll = maxOverscroll
)

/**
 * [bounceOverscroll] 的 **LazyListState** 版本 —— 一级列表页（`ChatListScreen` / `MemPage` 等）
 * 大多用 `LazyColumn`，没有它这些页面全接不了。
 *
 * 【越界量怎么算 —— 这里**不**依赖"总滚动像素"】这正是本重载能成立的关键：
 *   越界量**完全来自嵌套滚动的累积**（`onPreScroll` / `onPostScroll` 的 `available.y`），
 *   也就是"列表已经到边、还剩多少没滚掉"。`LazyListState` 没有 `value`（总像素）这个属性，
 *   但**我们根本不需要它** —— 需要总像素的只有"把滚动位置映射成某个比例"这类事
 *   （例如自适应标题栏），越界回弹要的是**增量**，`available` 天生就是增量。
 *   所以两个重载的物理完全一致，差别只在"用哪个类型判断是否正在拖"。
 *
 * 【为什么不把 `firstVisibleItemIndex × 行高 + offset` 当总像素用】行高是变量
 *   （折叠卡、多行说明都会变），反推出来的"总像素"在任何一屏都可能错，
 *   拿它去算越界量会把错误直接放大到手指位移上。
 */
@Composable
fun Modifier.bounceOverscroll(
    state: LazyListState,
    maxOverscroll: Float = DhBounce.MAX_OVERSCROLL,
): Modifier = bounceOverscrollImpl(
    isScrollInProgress = { state.isScrollInProgress },
    maxOverscroll = maxOverscroll
)

/**
 * 两个公开重载的**共同实现**。
 *
 * 【为什么只抽 `isScrollInProgress` 出来】两个类型的唯一共同需求就是它；
 *   越界量走嵌套滚动累积、与宿主类型无关（见 LazyListState 重载的注释）。
 *   把整个 state 传进来的话，私有实现里就会出现"为两种类型各写一次"的分支 —— 那是漂移的起点。
 *
 * 【参数是 lambda 而不是 Boolean】它必须在**滚动事件发生时**读最新值
 *   （组合期读到的会是拖动开始前的那一帧）。两个公开重载传的都是"读一个**稳定对象**的属性"，
 *   所以 `remember` 捕获第一个 lambda 也不会过期。
 */
@Composable
private fun Modifier.bounceOverscrollImpl(
    isScrollInProgress: () -> Boolean,
    maxOverscroll: Float,
): Modifier {
    // 显示用位移。用 floatState 而不是 Animatable：拖动期间每帧都要写，
    // Animatable 的每次 snapTo 都要进协程，滚动事件很密，那样会明显掉帧。
    val displayed = remember { mutableFloatStateOf(0f) }
    // 未阻尼的累积量。**与 displayed 分开存**是关键：阻尼是"累积量→位移"的有损映射，
    //   若直接对 displayed 累加，回拉时会被同一个非线性映射再压一次，手感变成"拉得回来但拉不满"。
    val raw = remember { floatArrayOf(0f) }

    val connection = remember(maxOverscroll) {
        object : NestedScrollConnection {

            /**
             * 反向拖：**先把越界收回去，再让列表滚**。
             *
             * 【为什么必须有这一步】只写 [onPostScroll] 会有一个经典卡死：越界时列表停在边沿，
             *   用户往回拖，列表**立刻**就能消费这段位移 ⇒ `available` 变 0 ⇒ 我们既没机会
             *   缩小越界位移，列表又已经滚走了 —— 结果屏幕上那块"拉出来"的内容**粘在原地**。
             *   所以回拉要在这里截：先吃位移收越界，收干净了再把剩下的还给列表（`return` 剩余）。
             *
             * 【只吃该吃的】跨过零点时只消费「收回越界所需」的那一段，剩余部分还给列表，
             *   否则回拉会丢掉一截手指位移，表现为"回拖手感发飘"。
             */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val before = raw[0]
                if (before == 0f || available.y * before >= 0f) {
                    // 没有越界，或者还在往外拉（往外拉不归这里管，交给 onPostScroll）
                    return Offset.Zero
                }
                raw[0] = before + available.y
                if (raw[0] * before <= 0f) {
                    // 跨过零点：吃掉的刚好是"把 before 收回到 0"的那一段
                    raw[0] = 0f
                    displayed.floatValue = 0f
                    return Offset(0f, -before)
                }
                displayed.floatValue = DhBounce.damped(raw[0], maxOverscroll)
                return Offset(0f, available.y)
            }

            /** 列表已经到边、还剩下 [available] 没滚掉 —— 这部分变成越界位移。 */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 【为什么要这道闸】`onPostScroll` 在**任何**导致滚动到边的动作后都会触发，
                //   包括程序性的 `animateScrollTo`（例如"跳到最新一条"）。那些不是用户手势，
                //   给它们加回弹会像"跳过去之后自己抖了一下"。只接管用户正在拖的场景。
                if (available.y == 0f || !isScrollInProgress()) {
                    return Offset.Zero
                }
                raw[0] += available.y
                displayed.floatValue = DhBounce.damped(raw[0], maxOverscroll)
                // 全部吃掉：不把剩余位移再往上传（否则外层滚动容器会跟着动）
                return Offset(0f, available.y)
            }

            /** 松手带速度：先按系数"甩出去一点"，再交给 [onPostFling] 弹回。 */
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (raw[0] == 0f) {
                    // 没有越界 → 不接管，让列表自己 fling
                    return Velocity.Zero
                }
                raw[0] += available.y * DhBounce.VELOCITY_COEFFICIENT
                val seed = DhBounce.damped(raw[0], maxOverscroll)
                // 冲出去这一下用短 tween（跟手），弹回才用弹簧 —— 两段的手感是不一样的
                animate(
                    initialValue = displayed.floatValue,
                    targetValue = seed,
                    animationSpec = DhAnim.PRESS_DOWN
                ) { v, _ -> displayed.floatValue = v }
                return available
            }

            /** fling 结束：把越界弹回 0。 */
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (raw[0] == 0f) {
                    return Velocity.Zero
                }
                settle(displayed, raw, DhAnim.BOUNCY)
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .graphicsLayer {
            // 【在 graphicsLayer 里读状态】读的是 State 而不是组合期快照 ——
            //   位移每帧变，靠这个 block 的延迟读取可以只重跑 layer，不触发重组。
            translationY = displayed.floatValue
        }
}

/**
 * 把越界位移弹回 0，并清空累积量。
 *
 * 【为什么清累积量要和动画一起做】`raw` 是"用户拉了多少"的账本；回弹结束后手指与内容
 *   已经重新对齐，账本必须归零，否则下一次拖动会从旧账本开始（表现为"一开始就拉不动"）。
 */
private suspend fun settle(
    displayed: MutableFloatState,
    raw: FloatArray,
    spec: AnimationSpec<Float>
) {
    animate(
        initialValue = displayed.floatValue,
        targetValue = 0f,
        animationSpec = spec
    ) { v, _ -> displayed.floatValue = v }
    raw[0] = 0f
    displayed.floatValue = 0f
}

/**
 * 卡片**拖动形变**（QZX `qzxCardDragDeformEnabled` 的等价物）。
 *
 * ## 这里为什么**没有**按压缩放
 * 按压反馈已经由 `pressable` 全权负责（125 处调用、`PRESS_SCALE = 0.98f`）。
 * 若本函数再缩一次，两者叠乘 —— 0.96 × 0.96 ≈ **0.92**，按一下"陷"进去 8%，
 * 明显过度。所以职责切开：
 * · **按** → `pressable`（缩放 + 触觉 + 命中区）
 * · **拖** → 本函数（形变 + 倾角）
 * 这样两者可以自由组合，不会互相放大。
 *
 * > `DhBounce.CARD_TOUCH_SCALE`（QZX 的 0.96）仍保留在常量区，供将来"卡片专用按压"
 * > 单独选用；**本函数不读它**。
 *
 * ## 用法
 * ```kotlin
 * var drag by remember { mutableStateOf(Offset.Zero) }
 * Box(
 *     Modifier
 *         .cardDeform(dragOffset = { drag })        // 只管拖动形变
 *         .pressable { … }                          // 只管按压 + 点击
 * ) { … }
 * ```
 * **拖动量由调用方喂进来**（[dragOffset]）：本工程的手势五花八门（`pressable` /
 * `draggable` / `detectDragGestures` / 左滑删除），组件不该假定一种再去抢手势。
 * 不做拖动的卡片不传即可，等于零成本。
 *
 * ## 形变怎么算的
 * · **沿拖动轴压缩**：`squash = |d| / 卡宽 × CARD_DEFORM_GAIN`，上限 [DhBounce.CARD_MAX_SQUASH]；
 * · **垂直轴轻微拉伸**：取 `squash × 0.5`，让"被推了一下"有体积感而不是单纯缩小；
 * · **小幅倾斜**：横向拖动时按比例给 `rotationZ`，上限 [DhBounce.CARD_MAX_TILT_DEG]。
 *
 * ## 为什么不需要 `@Composable`
 * 它只往 `graphicsLayer` 里挂一个 block，不读组合期状态（拖动量由调用方以 lambda 喂入、
 * 所有量都在 layer 块里算）。少一层 `@Composable` 意味着它也能在非组合语境里拼进 modifier 链。
 *
 * ## reduceMotion
 * **整体关闭**：形变本身就是"动画"，关动画的用户不该看到内容在他手指下变形。
 * 这里读的是 `DhAnim.reduceMotion()` 的进程级镜像 —— 因为它跑在 `graphicsLayer` 里（非组合期）。
 */
fun Modifier.cardDeform(
    dragOffset: () -> Offset = { Offset.Zero },
): Modifier {
    return this.graphicsLayer {
        val d = if (DhAnim.reduceMotion()) Offset.Zero else dragOffset()
        // 【用自身尺寸做归一化】这样同一张卡在手机 / 平板上"拖一半宽度"的形变程度一致；
        //   写死 px 常量会在不同 dpi 上表现完全不同。
        val w = if (size.width > 0f) size.width else 1f
        val h = if (size.height > 0f) size.height else 1f
        val nx = (d.x / w).coerceIn(-1f, 1f)
        val ny = (d.y / h).coerceIn(-1f, 1f)
        val squashX = (abs(nx) * DhBounce.CARD_DEFORM_GAIN).coerceIn(0f, DhBounce.CARD_MAX_SQUASH)
        val squashY = (abs(ny) * DhBounce.CARD_DEFORM_GAIN).coerceIn(0f, DhBounce.CARD_MAX_SQUASH)
        // 沿轴压缩、另一轴补回来一点（面积守恒的近似），合起来才像"被推变形"而不是"缩小"
        scaleX = 1f - squashX + squashY * 0.5f
        scaleY = 1f - squashY + squashX * 0.5f
        rotationZ = (nx * DhBounce.CARD_MAX_TILT_DEG).coerceIn(
            -DhBounce.CARD_MAX_TILT_DEG,
            DhBounce.CARD_MAX_TILT_DEG
        )
        // 【不写 translationY / translationX】位置的偏移由调用方的手势负责；
        //   这里只做形变。两边都写会互相打架（同一个位置被扣两次）。
    }
}
