package com.dollhouse.app.ui.compose

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * 按压跟手高光 —— QZX `QZXGeoButton` 的 ② 层反馈。
 *
 * ## 它补的是我们主动留空的位置
 *
 * 本工程的 `pressable` 是 `indication = null`（见 `DhKit.pressable` 的注释：
 * 设计稿 §3「no ripple beyond that」）—— **刻意没有涟漪**。
 * 于是「按下时除了缩放之外没有任何内容反馈」是我们**自己选择的空缺**，
 * 而不是"缺个动画"。本组件填的是这个空缺。
 *
 * 与 Material ripple 的本质区别：ripple 是从触点半径扩散的**圆圈**；
 * 本组件是**跟随手指、带惯性预测的光斑**，快速滑动时它会"甩"向手指前进方向。
 *
 * ## 参数全部取自 QZX 规格（`docs/QZX_SPEC_BUTTON_GLASS.md`）
 *
 * | 项 | QZX 值 | 本实现 |
 * |---|---|---|
 * | 光斑半径 | `200dp`（**比按钮还大**，是有意的） | [radiusDp] |
 * | 高光颜色 | `#7CFFFFFF`（49% 白） | [color] |
 * | 强度 | 默认 1.0 但 **clamp 上限 0.5** → 实际生效 0.5 | [intensity] |
 * | 软边 | `0.9`（几乎无硬边） | [softness] |
 * | 跟随速度 | `0.6`（clamp 0.1~1.0） | [followSpeed] |
 * | 方向敏感度 | `0.5` | [directionSensitivity] |
 * | 速度采样点 | **最近 5 个** | [SAMPLE_COUNT] |
 *
 * ## ⚠️ 为什么 `interactionSource` 是可选的，而且通常不传
 *
 * QZX 是 `View`，按压状态天然从 `onTouchEvent` 拿。我们这边**主要按钮走 `pressable`**
 * （`DhKit.kt`，**125 处调用点**），而 `pressable(onClick)` **没有 `interactionSource` 参数**
 * —— 想传也拿不到。
 *
 * 因此本组件**自己在 `pointerInput` 里跟踪按压与位置**，不依赖外部状态。
 * [interactionSource] 只作为「调用方恰好有」时的增强（比如用
 * `clickable(interactionSource = src, indication = null)` 的按钮）；
 * 传了会与内部跟踪取**与**（两者都认为按下才画）。**不传完全正常。**
 *
 * ## 性能约定（照 lead 的三条要求）
 *
 * 1. **不每帧重组**：位置与按压都存 [mutableFloatStateOf] / [mutableStateOf]，
 *    **只在 draw 阶段读**（`drawWithContent` 里读 state 只触发重绘，不触发组合）。
 * 2. **固定大小环形数组**：5 个采样点用 `FloatArray` / `LongArray` 原地覆盖，
 *    **不建 `List`、不每帧分配**。
 * 3. 不做 `graphicsLayer` 平移 —— 高光只在原地重画一笔，不移动节点、不触发测量布局。
 *
 * ## 用法（顺序有讲究）
 *
 * ```kotlin
 * Box(
 *     Modifier
 *         .pressable(onClick)
 *         .clip(shape)                 // ① 在前 = 外层：把高光裁进按钮形状
 *         .background(c.acc)           // ② 底色
 *         .pressHighlight()            // ③ 在底色之后 = 画在底色之上；在 clip 之内 = 不溢出
 *         .padding(...)
 * )
 * ```
 *
 * ### 为什么要放在 `background` **之后**
 * 本组件用 `drawWithContent { 画高光; drawContent() }` —— 高光在**内容之下**、
 * 但在**它之前的所有修饰符之上**。若写成 `.background()` 在它后面，
 * 不透明的底色会把高光整片盖住（等于没接）。**接到不透明底色上时必须排在 background 之后。**
 *
 * ### 为什么要放在 `clip` **之后**
 * Compose 修饰符链里**靠前的是外层**，`clip` 会裁它后面的所有绘制。
 * 写在 `clip` 之前，200dp 的光斑会糊到按钮外面一圈，
 * 看起来像发光故障而不是表面反光。
 */
@Composable
fun Modifier.pressHighlight(
    interactionSource: MutableInteractionSource? = null,
    radiusDp: Float = DEFAULT_RADIUS_DP,
    intensity: Float = DEFAULT_INTENSITY,
    color: Color = DEFAULT_HIGHLIGHT_COLOR,
    softness: Float = DEFAULT_SOFTNESS,
    followSpeed: Float = DEFAULT_FOLLOW_SPEED,
    directionSensitivity: Float = DEFAULT_DIRECTION_SENSITIVITY,
    scrollThresholdRatio: Float = DEFAULT_SCROLL_THRESHOLD_RATIO,
    horizontalThresholdRatio: Float = DEFAULT_HORIZONTAL_THRESHOLD_RATIO,
): Modifier {
    val highlight = remember { PressHighlightState() }
    val radiusPx = with(LocalDensity.current) { radiusDp.dp.toPx() }

    // 外部按压态（可选）。传了才收集；不传时 collectIsPressedAsState 仍可调用，
    //   但它永远返回 false —— 所以下面用 `if (interactionSource != null)` 短路，
    //   避免为「没有 source」的 125 处 pressable 白建一个 InteractionSource 记录点。
    val externalPressed = if (interactionSource != null) {
        interactionSource.collectIsPressedAsState().value
    } else {
        true // 无外部源时完全交给内部跟踪
    }

    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                // 【不 consume】绝不能吃掉事件，否则同链上的 pressable/clickable 收不到点击。
                //   requireUnconsumed = false：即使被别的修饰符先处理，也要读到位置。
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                highlight.onDown(down.position, down.uptimeMillis)

                // 【滚动判定·QZX 的 `qzxButtonScrollThresholdRatio` / `HorizontalThresholdRatio`】
                //   本组件自己跟踪按压（`pressable` 不暴露 interactionSource），所以拿不到
                //   `clickable` 那套「被滚动抢走就 Cancel」的现成机制 —— 必须自己判。
                //
                //   QZX 用的就是「位移超过控件尺寸的一定比例 → 认为用户在滚动而非按压」：
                //     · 纵向阈值 = 高度 × 0.2
                //     · 横向阈值 = 宽度 × 0.15
                //   超过就收掉高光，效果与 `clickable` 取消按压一致。
                //
                //   【为什么这对大控件反而更好】阈值按尺寸缩放：328dp 宽的卡片横向可走 49dp，
                //   足够撑起「甩」的观感；而 48dp 的图标按钮横向只走 7dp 就取消 ——
                //   正好印证「小控件上做跟手高光没意义」。
                val maxDx = size.width * horizontalThresholdRatio
                val maxDy = size.height * scrollThresholdRatio

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        // 【必须释放】指针被取消/移出事件流时，changes 里已经没有它了。
                        //   若这里直接 break 而不 onUp，`pressed` 会永远为 true
                        //   —— 高光卡在按钮上不消失，且每次点击都再压一个。
                        highlight.onUp()
                        break
                    }
                    if (!change.pressed) {
                        highlight.onUp()
                        break
                    }
                    // 位移越过阈值 → 判定为滚动/拖动，收掉高光（不 consume，滚动照常）。
                    if (kotlin.math.abs(change.position.x - down.position.x) > maxDx ||
                        kotlin.math.abs(change.position.y - down.position.y) > maxDy
                    ) {
                        highlight.onUp()
                        break
                    }
                    highlight.onMove(change.position, change.uptimeMillis, followSpeed, directionSensitivity)
                }
            }
        }
        .drawWithContent {
            if (highlight.pressed && externalPressed) {
                val peak = (intensity * color.alpha).coerceIn(0f, 1f)
                if (peak > 0f) {
                    // 软边：中心一段保持满强度，再平滑降到 0。
                    //   `softness = 0.9` → 只有最内 10% 是满强度，其余 90% 全是渐变，
                    //   这正是 QZX「几乎无硬边」的观感。
                    val inner = (1f - softness).coerceIn(0.02f, 1f)
                    val brush = Brush.radialGradient(
                        0.0f to color.copy(alpha = peak),
                        inner to color.copy(alpha = peak),
                        1.0f to color.copy(alpha = 0f),
                        center = Offset(highlight.x, highlight.y),
                        radius = radiusPx,
                    )
                    // 加法混合：与 QZX 的「径向渐变光斑叠在按钮上」同一手感。
                    //   不用 SrcOver —— 那只是盖一层白漆，不会让底下颜色变亮。
                    drawRect(brush = brush, blendMode = BlendMode.Plus)
                }
            }
            // 内容（文字/图标）画在高光之上。
            drawContent()
        }
}

/**
 * 跟手高光的运行态。
 *
 * 【为什么自己是个类而不是几个 `remember`】5 点采样需要环形数组 + 头指针 + 计数
 * 三者**同步推进**，拆成多个 `remember` 容易出现某次 `onMove` 只更了一半。
 * 打包成一个对象，`remember { }` 一次持有，内部字段原地覆盖（零分配）。
 *
 * 【显式标 `@Stable`】字段全是 `mutableStateOf` / `mutableFloatStateOf`，
 * Compose 能正确观测；标 `@Stable` 让编译器不再为它插多余的相等性检查。
 */
@Stable
class PressHighlightState {
    /** 是否处于按下态。**只在 draw 阶段读**，不引起重组。 */
    var pressed by mutableStateOf(false)
        private set

    /** 当前光斑中心（已做指数平滑）。同上，只在 draw 阶段读。 */
    var x by mutableFloatStateOf(0f)
        private set
    var y by mutableFloatStateOf(0f)
        private set

    // ---- 5 点环形缓冲：原地覆盖，不分配 ----
    private val sx = FloatArray(SAMPLE_COUNT)
    private val sy = FloatArray(SAMPLE_COUNT)
    private val st = LongArray(SAMPLE_COUNT)

    /** 下一个写入位置。 */
    private var head = 0

    /** 有效样本数（最多 [SAMPLE_COUNT]）。 */
    private var count = 0

    internal fun onDown(pos: Offset, timeMs: Long) {
        pressed = true
        // 按下瞬间：光斑直接落在触点，不做平滑 —— 否则第一帧会从上次位置"飞"过来。
        x = pos.x
        y = pos.y
        head = 0
        count = 0
        push(pos, timeMs)
    }

    internal fun onUp() {
        pressed = false
        count = 0
        head = 0
    }

    /**
     * 拖动/移动时更新。
     *
     * ## 速度预测公式（lead 问的就是这条）
     *
     * 设环形缓冲里有效样本为 `n`（最多 5），`oldest` = 最早一个，`newest` = 最新一个：
     *
     * ```
     * dt  = max(newest.t - oldest.t, 1ms)
     * vx  = (newest.x - oldest.x) / dt        // px/ms
     * vy  = (newest.y - oldest.y) / dt
     * ```
     *
     * 注意：**首末位移 ÷ 首末时间** 在数学上等于
     * `Σ(每段位移) / Σ(每段时间)`，也就是**各段速度按时间加权的平均值** ——
     * 这正是 QZX 说的「用最近 5 个触摸点算平均速度」，只是不必逐段求平均再加权
     * （两种写法结果相同，本写法少 4 次除法）。
     *
     * 预测落点（把速度沿前进方向再外推 [PREDICT_LOOKAHEAD_MS] 毫秒）：
     *
     * ```
     * targetX = newest.x + vx * PREDICT_LOOKAHEAD_MS * directionSensitivity
     * targetY = newest.y + vy * PREDICT_LOOKAHEAD_MS * directionSensitivity
     * ```
     *
     * 再做指数平滑（`followSpeed` = 0.6 表示每次事件向目标靠 60%）：
     *
     * ```
     * x += (targetX - x) * followSpeed
     * y += (targetY - y) * followSpeed
     * ```
     *
     * **手指停下时** `vx/vy → 0` → `target` = 手指实际位置 → 平滑把光斑收到手指上。
     * 所以「甩」只在拖动中出现，松手前会自动贴合 —— 与 QZX 描述一致。
     */
    internal fun onMove(pos: Offset, timeMs: Long, followSpeed: Float, directionSensitivity: Float) {
        push(pos, timeMs)

        val n = count
        val newestX = sx[prevIndex(head)]
        val newestY = sy[prevIndex(head)]
        if (n < 2) {
            // 只有一个点，无速度可言，直接贴合。
            x = newestX
            y = newestY
            return
        }
        val oldest = oldestIndex()
        val dt = (st[prevIndex(head)] - st[oldest]).coerceAtLeast(1L)
        val vx = (newestX - sx[oldest]) / dt
        val vy = (newestY - sy[oldest]) / dt

        val targetX = newestX + vx * PREDICT_LOOKAHEAD_MS * directionSensitivity
        val targetY = newestY + vy * PREDICT_LOOKAHEAD_MS * directionSensitivity

        val k = followSpeed.coerceIn(0.1f, 1f)
        x += (targetX - x) * k
        y += (targetY - y) * k
    }

    private fun push(pos: Offset, timeMs: Long) {
        sx[head] = pos.x
        sy[head] = pos.y
        st[head] = timeMs
        head = (head + 1) % SAMPLE_COUNT
        count = min(count + 1, SAMPLE_COUNT)
    }

    /** `head` 指向**下一个**写入位，所以最新样本在它前一位。 */
    private fun prevIndex(i: Int) = if (i == 0) SAMPLE_COUNT - 1 else i - 1

    /** 最旧的样本：从最新往回退 `count - 1` 位。 */
    private fun oldestIndex(): Int {
        val newest = prevIndex(head)
        val back = count - 1
        return ((newest - back) % SAMPLE_COUNT + SAMPLE_COUNT) % SAMPLE_COUNT
    }
}

/* ============================ 常量（全部取自 QZX 规格） ============================ */

/**
 * 速度采样点数：**5**。QZX 明文「用最近 5 个触摸点算平均速度」。
 *
 * 【为什么是常量而不是参数】改这个值等于改手感曲线，属于要重新调参的事，
 * 不该暴露成可调项让别人随手改。
 */
private const val SAMPLE_COUNT = 5

/**
 * 速度外推时长（ms）：**60**。
 *
 * QZX 没有公开这个值 —— 它的 `qzxButtonHighlightFollowSpeed`(0.6) 与
 * `qzxButtonHighlightDirectionSensitivity`(0.5) 是**效果强度**，不是外推时长。
 * 这里取 60ms 的量级依据：60Hz 屏幕上约 3.6 帧、120Hz 上约 7 帧，
 * 配合 sensitivity 0.5 后相当于「外推约 30ms 的行程」——
 * 肉眼能看出"甩"，但不会甩到手指之外太远。
 *
 * ⚠️ **这是本实现唯一没有 QZX 依据的数值**，真机上若觉得甩得过/不够，调它。
 */
private const val PREDICT_LOOKAHEAD_MS = 60f

/** 光斑半径：**200dp**（QZX 默认值，比按钮本身大，是有意的）。 */
private const val DEFAULT_RADIUS_DP = 200f

/**
 * 强度：**0.5**。
 *
 * 【为什么不是 QZX 写的默认值 1.0】QZX 文档自相矛盾：默认 `1.0`，但 clamp 区间是
 * `0.1~0.5` —— **默认值超出自己的上限**，实际生效值被夹到 0.5。
 * 抄 1.0 会过曝。详见 `docs/QZX_SPEC_BUTTON_GLASS.md` §1.1 那条警告。
 */
private const val DEFAULT_INTENSITY = 0.5f

/**
 * 高光颜色：**`#7CFFFFFF`**（alpha `0x7C` = 124/255 ≈ **49% 白**）。
 *
 * 【为什么不用不透明 White】峰值透明度算的是 `intensity × color.alpha`：
 *   · QZX：`0.5 × 0.49 ≈ **0.245**`（≈24.5% 的提亮）
 *   · 若用不透明白：`0.5 × 1.0 = 0.5`，**比 QZX 亮一倍**
 * 抄颜色要连 alpha 一起抄，否则"照着参数做"反而更刺眼。
 */
private val DEFAULT_HIGHLIGHT_COLOR = Color(0x7CFFFFFF)

/** 软边：**0.9**（QZX 默认）。只有最内 10% 满强度，其余 90% 全渐变。 */
private const val DEFAULT_SOFTNESS = 0.9f

/** 跟随速度：**0.6**（QZX 默认，clamp 0.1~1.0）。每次事件向预测目标靠 60%。 */
private const val DEFAULT_FOLLOW_SPEED = 0.6f

/** 方向敏感度：**0.5**（QZX 默认）。乘在外推时长上，0 = 完全不甩、纯贴合。 */
private const val DEFAULT_DIRECTION_SENSITIVITY = 0.5f

/**
 * 纵向滚动判定阈值比例：**0.2** —— 位移超过 `高度 × 0.2` 即认为用户在滚动，收掉高光。
 * QZX `qzxButtonScrollThresholdRatio` 默认值。
 */
private const val DEFAULT_SCROLL_THRESHOLD_RATIO = 0.2f

/**
 * 横向滚动判定阈值比例：**0.15** —— 位移超过 `宽度 × 0.15` 即收掉高光。
 * QZX `qzxButtonHorizontalThresholdRatio` 默认值。
 */
private const val DEFAULT_HORIZONTAL_THRESHOLD_RATIO = 0.15f
