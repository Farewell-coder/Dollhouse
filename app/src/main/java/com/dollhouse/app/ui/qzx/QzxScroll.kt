package com.dollhouse.app.ui.qzx

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
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

/**
 * QZX 的统一「越界回弹」—— 对应 `QZXGeometryXBounceScrollView`。
 *
 * ## 为什么单独成体系
 *
 * 本包是**新 UI 系统**（`ui/qzx`）。旧系统那份回弹实现与本包**并存但互不引用**：
 * 旧的是给旧界面用的，这里的是给声明式新界面用的。两者物理常量相同（都抄 QZX 文档），
 * 但**没有任何代码共享** —— 删掉旧包时不会牵连新包。
 *
 * ## 物理常量（QZX 文档）
 *
 * | 常量 | 值 | 含义 |
 * |---|---|---|
 * | [QzxBounce.MAX_OVERSCROLL] | **2500** | 越界累积量的理论上限（px） |
 * | [QzxBounce.DAMPING_FACTOR] | **1.5** | 阻尼因子；实际可拉到 `MAX / 1.5 ≈ 1667px` |
 * | [QzxBounce.VELOCITY_COEFFICIENT] | **0.2** | 甩动速度 → 额外越界量的系数 |
 *
 * ## 三个重载怎么共用（关键设计）
 *
 * 只抽出一个 lambda：`isScrollInProgress: () -> Boolean`。
 *
 * **为什么不把整个 `state` 传进私有实现**：`ScrollState` / `LazyListState` / `LazyGridState`
 * 三者没有共同父类。若把 state 传进去，私有实现里就会出现「按类型分支」——
 * 那正是两份实现开始漂移的起点。它们唯一的共同需求只有「此刻是不是用户在拖」，
 * 所以只把这一个能力传下去，越界量的累积与宿主类型完全无关。
 *
 * **为什么参数是 lambda 而不是 Boolean**：滚动事件发生时必须读**最新**值；
 * 组合期读到的会是拖动开始前那一帧。传 lambda 保证每次事件都现读。
 */
object QzxBounce {
    /** 越界累积量的硬上限（px）。QZX: `MAX_OVERSCROLL = 2500`。 */
    const val MAX_OVERSCROLL = 2500f

    /** 阻尼因子。QZX: `DAMPING_FACTOR = 1.5`。 */
    const val DAMPING_FACTOR = 1.5f

    /** 甩动速度 → 额外越界量的系数。QZX: `VELOCITY_COEFFICIENT = 0.2`。 */
    const val VELOCITY_COEFFICIENT = 0.2f

    /**
     * 把「用户拉了多少」（未阻尼累积量 [raw]）映射成「屏幕上移动多少」。
     *
     * ## 公式：双曲饱和
     *
     * ```
     * cap = max / DAMPING_FACTOR                 // 默认 2500 / 1.5 ≈ 1666.7 px
     * f(x) = cap * x / (x + cap)                 // 带符号：保留 raw 的正负
     * ```
     *
     * ## 这两个性质正是它被选中的理由
     *
     * - **`f'(0) = 1`** —— 起点 1:1 跟手。手指移 1px，内容就移 1px，没有"先空一段"的粘滞感。
     *   （线性映射 `f(x)=x` 也有这条，但没有上限；指数衰减 `f(x)=cap(1-e^{-x/cap})` 有上限，
     *   但 `f'(0)=1` 同样成立 —— 双曲与指数在这一项上等价，双曲胜在**没有 exp 调用**，
     *   每帧都要算，省一次 libm 调用在低端机上有意义。）
     * - **`f(∞) = cap`** —— 无论怎么拉都越不过上限，内容不会被拉出屏幕。
     *
     * 【为什么不直接对"显示位移"累加】阻尼是**有损映射**：若把每帧的 `available` 直接加到
     *   显示位移上，回拉时同一个非线性映射会再压一次，手感变成"拉得回来但拉不满"。
     *   所以本实现**分开存**：`raw`（账本，无阻尼累加）+ `displayed`（每帧由 raw 映射）。
     *
     * @param raw 未阻尼累积量（px），可正可负（正 = 往下拉 / 往上推，取决于手势方向）。
     * @param max 硬上限，一般不需要改。
     */
    fun damped(raw: Float, max: Float = MAX_OVERSCROLL): Float {
        val cap = (max / DAMPING_FACTOR).coerceAtLeast(1f)
        val x = abs(raw)
        val y = cap * x / (x + cap)
        return if (raw < 0f) -y else y
    }
}

/* ============================ 三个公开重载 ============================ */

/** [Modifier.qzxBounce] 的 `ScrollState` 版（`Modifier.verticalScroll` 页面）。 */
@Composable
fun Modifier.qzxBounce(
    state: ScrollState,
    maxOverscroll: Float = QzxBounce.MAX_OVERSCROLL,
): Modifier = qzxBounceImpl(
    isScrollInProgress = { state.isScrollInProgress },
    maxOverscroll = maxOverscroll,
)

/** [Modifier.qzxBounce] 的 `LazyListState` 版（`LazyColumn` / 声明式列表）。 */
@Composable
fun Modifier.qzxBounce(
    state: LazyListState,
    maxOverscroll: Float = QzxBounce.MAX_OVERSCROLL,
): Modifier = qzxBounceImpl(
    isScrollInProgress = { state.isScrollInProgress },
    maxOverscroll = maxOverscroll,
)

/** [Modifier.qzxBounce] 的 `LazyGridState` 版（`LazyVerticalGrid`）。 */
@Composable
fun Modifier.qzxBounce(
    state: LazyGridState,
    maxOverscroll: Float = QzxBounce.MAX_OVERSCROLL,
): Modifier = qzxBounceImpl(
    isScrollInProgress = { state.isScrollInProgress },
    maxOverscroll = maxOverscroll,
)

/* ============================ 共同实现 ============================ */

/**
 * 三个重载的共同实现。只依赖 [isScrollInProgress]，与宿主类型无关（理由见 [QzxBounce] 的类注释）。
 *
 * **挂在哪个节点上**：与 `verticalScroll` / `LazyColumn` **同一个** `Modifier` 上，
 * 且 `qzxBounce` 要在滚动修饰符**之前**（外层）—— `nestedScroll` 必须包住滚动节点，
 * 才收得到「列表已到边、还剩这么多没滚掉」的那部分位移（`onPostScroll` 的 `available`）。
 */
@Composable
private fun Modifier.qzxBounceImpl(
    isScrollInProgress: () -> Boolean,
    maxOverscroll: Float,
): Modifier {
    // 显示用位移。用 floatState 而不是 Animatable：拖动期每帧都写，
    // Animatable 的每次 snapTo 都要进协程，滚动事件很密，那样会明显掉帧。
    val displayed = remember { mutableFloatStateOf(0f) }
    // 未阻尼累积量（账本）。与 displayed 分开存 —— 见 QzxBounce.damped 的说明。
    val raw = remember { floatArrayOf(0f) }

    val connection = remember(maxOverscroll) {
        object : NestedScrollConnection {

            /**
             * **反向拖：先把越界收回去，再让列表滚。**
             *
             * 【为什么必须有这一步（本文件最重要的一段）】只写 [onPostScroll] 会有一个经典卡死：
             *   越界时列表停在边沿，用户往回拖，列表**立刻**就能消费这段位移 ⇒ `available` 变 0
             *   ⇒ 我们既没机会缩小越界位移，列表又已经滚走了 ——
             *   结果屏幕上那块"拉出来"的内容**粘在原地**，回拖毫无反应。
             *
             * 【只吃该吃的】跨过零点时，只消费「把越界收回 0」所需的那一段，剩余还给列表，
             *   否则回拉会丢掉一截手指位移，表现为"回拖手感发飘"。
             */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val before = raw[0]
                if (before == 0f || available.y * before >= 0f) {
                    // 没有越界，或还在往外拉（往外拉不归这里管，交给 onPostScroll）
                    return Offset.Zero
                }
                raw[0] = before + available.y
                if (raw[0] * before <= 0f) {
                    // 跨过零点：吃掉的刚好是把 before 收回到 0 的那一段
                    raw[0] = 0f
                    displayed.floatValue = 0f
                    return Offset(0f, -before)
                }
                displayed.floatValue = QzxBounce.damped(raw[0], maxOverscroll)
                return Offset(0f, available.y)
            }

            /** 列表已到边、还剩下 [available] 没滚掉 —— 这部分变成越界位移。 */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 只在用户真的在拖时接管。程序性滚动（animateScrollTo / scrollToItem）也会
                //   让列表到边并触发本回调，给它们加回弹会表现为"跳过去之后自己抖一下"。
                if (available.y == 0f || !isScrollInProgress()) {
                    return Offset.Zero
                }
                raw[0] += available.y
                displayed.floatValue = QzxBounce.damped(raw[0], maxOverscroll)
                // 全部吃掉，不再往上传（否则外层滚动容器会跟着动）
                return Offset(0f, available.y)
            }

            /** 松手带速度：先按系数"甩出去一点"，再由 [onPostFling] 弹回。 */
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (raw[0] == 0f) {
                    return Velocity.Zero
                }
                raw[0] += available.y * QzxBounce.VELOCITY_COEFFICIENT
                val seed = QzxBounce.damped(raw[0], maxOverscroll)
                // 冲出去这一下用短 tween（跟手）；弹回才用弹簧 —— 两段手感不同。
                animate(
                    initialValue = displayed.floatValue,
                    targetValue = seed,
                    animationSpec = QzxMotion.PRESS_DOWN,
                ) { v, _ -> displayed.floatValue = v }
                return available
            }

            /** fling 结束：把越界弹回 0。 */
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (raw[0] == 0f) {
                    return Velocity.Zero
                }
                qzxSettle(displayed, raw, QzxMotion.BOUNCY)
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .graphicsLayer {
            // 在 graphicsLayer 里读状态：位移每帧变，靠这个延迟读取只重跑 layer，不触发重组。
            translationY = displayed.floatValue
        }
}

/**
 * 把越界位移弹回 0，并清空账本。
 *
 * 【为什么清账本要和动画一起做】[raw] 记的是"用户拉了多少"。回弹结束后手指与内容已重新对齐，
 *   账本必须归零；否则下一次拖动会从旧账本开始，表现为"一开始就拉不动"。
 */
private suspend fun qzxSettle(
    displayed: MutableFloatState,
    raw: FloatArray,
    spec: AnimationSpec<Float>,
) {
    animate(
        initialValue = displayed.floatValue,
        targetValue = 0f,
        animationSpec = spec,
    ) { v, _ -> displayed.floatValue = v }
    raw[0] = 0f
    displayed.floatValue = 0f
}
