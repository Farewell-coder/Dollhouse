package com.dollhouse.app.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.awaitEachGesture
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * [LayerShield] 的 Compose 等价实现。
 *
 * 【为什么需要它】`LayerShield` 是 `FrameLayout` 子类，靠 `dispatchTouchEvent` 决定
 * 「这一层要不要吃掉整段手势」。它承担两件事：
 *   ① **独占判定**：覆盖页 / 抽屉 / 底栏各自该不该接收触摸（防「看得见点不动」）；
 *   ② **边缘右滑**：只有底座层启用，从屏幕左缘起手打开抽屉。
 *
 * 只要主界面还是「5 个 ComposeView 套 FrameLayout + 4 层 shield」的装配，
 * 它就永远是个 View 实例化文件。把这个组件做出来，主界面才能收成**一个** ComposeView。
 *
 * 【三条语义如何复刻】原 View 实现的核心只有三条：
 *
 *  1. **ACTION_DOWN 时按 blocking 决定是否接管**，接管则吞掉整段手势、不向下层透传。
 *     → `pointerInput(blocking)` + 首帧 `down.consume()`。被消费后子节点的
 *        `awaitFirstDown(requireUnconsumed = true)` 不会触发，等价于父 View 切断分发。
 *
 *  2. **所有权锁定到序列结束**（中途不换层）。
 *     → `awaitEachGesture` 的一次循环就是一段序列；块内不重读 `blocking`，
 *        序列进行中即使 blocking 变了也不会中途换主。
 *
 *  3. **屏蔽瞬间向子树补发 CANCEL**（避免按钮停在按下态）。
 *     → `pointerInput` 的 key 含 `blocking`：值一变，协程作用域被取消，
 *        正在进行的手势随之作废。比手工构造 `MotionEvent.ACTION_CANCEL` 更可靠。
 *
 * 【与原实现的已知差异】原 `cancelPendingTouch()` 是**事件注入**，这里靠**协程取消**。
 *   对 Compose 内部组件效果相同（都是「不再收到后续事件」）；若将来某个
 *   `AndroidView` 里自绘的控件依赖显式 CANCEL 才复位，需要在该处局部补一次注入。
 *   这是有意的取舍，不是遗漏。
 *
 * 【用法】只包需要闸门的那一层，不要把整个屏幕包起来。
 *
 * @param blocking `true` = 本层被屏蔽（吃掉全部触摸）；`false` = 正常放行。
 * @param edgeSwipeEnabled 是否启用左缘右滑。**只有底座层该开** ——
 *   覆盖页有自己的左缘返回手势，两处同时开会互相抢。
 * @param edgeWidth 起手区宽度（从「系统手势区右缘 + edgeMargin」再往右这么宽）。
 * @param edgeMargin 与系统手势区之间的呼吸间距，避免和系统「返回」抢。
 * @param drawerWidth 拖动换算用的抽屉宽度，需与抽屉自己的 progress 口径一致。
 */
@Composable
fun ComposeLayerShield(
    blocking: Boolean,
    modifier: Modifier = Modifier,
    edgeSwipeEnabled: Boolean = false,
    edgeWidth: Dp = 32.dp,
    edgeMargin: Dp = 8.dp,
    drawerWidth: Dp = 300.dp,
    onSwipeStart: (() -> Unit)? = null,
    onSwipeMove: ((Float) -> Unit)? = null,
    onSwipeEnd: ((Float, Float) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val viewConfig = LocalViewConfiguration.current

    // 【为什么用 rememberUpdatedState】pointerInput 的 key 里不该放下 lambda
    //   （每次重组都换新实例会导致作用域反复重启），但不更新的捕获又会一直用旧回调。
    //   rememberUpdatedState 让「key 稳定」与「回调最新」同时成立。
    val swipeStart by rememberUpdatedState(onSwipeStart)
    val swipeMove by rememberUpdatedState(onSwipeMove)
    val swipeEnd by rememberUpdatedState(onSwipeEnd)

    // 系统手势区左缘：手势导航机型约 20~30px，三键导航为 0。
    //   【为什么动态算不写死】写死会在某些机型上完全失效，或抢走系统返回手势。
    val systemGestureLeftPx =
        WindowInsets.systemGestures.getLeft(density, layoutDirection).toFloat()
    val minXPx = systemGestureLeftPx + with(density) { edgeMargin.toPx() }
    val maxXPx = minXPx + with(density) { edgeWidth.toPx() }
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    val touchSlop = viewConfig.touchSlop

    Box(
        modifier = modifier.pointerInput(blocking, edgeSwipeEnabled, minXPx, maxXPx, drawerWidthPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (blocking) {
                    consumeWholeGesture(down)
                    return@awaitEachGesture
                }
                if (!edgeSwipeEnabled) {
                    return@awaitEachGesture
                }
                runEdgeSwipe(
                    down = down,
                    minXPx = minXPx,
                    maxXPx = maxXPx,
                    touchSlop = touchSlop,
                    drawerWidthPx = drawerWidthPx,
                    onStart = { swipeStart?.invoke() },
                    onMove = { swipeMove?.invoke(it) },
                    onEnd = { dx, p -> swipeEnd?.invoke(dx, p) }
                )
            }
        }
    ) {
        content()
    }
}

/**
 * 屏蔽态：吃掉当前这一下，并把整段手势吞完，直到手指全部抬起。
 *
 * 【为什么首帧之后还要继续消费】子节点可能通过多点触控「换主」拿到后续事件；
 *   只在 DOWN 消费一次不足以保证整段序列都被本层独占（原实现里
 *   `gestureOwner = false` 之后同样会持续收到 MOVE 并返回 false）。
 */
private suspend fun AwaitPointerEventScope.consumeWholeGesture(
    down: androidx.compose.ui.input.pointer.PointerInputChange
) {
    down.consume()
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) {
            return
        }
    }
}

/**
 * 边缘右滑：只有落在起手区内的手势才认领；竖滑超过阈值则判定为滚动并放弃。
 *
 * 【起手区为什么这么算】`edgeMargin` 已经把系统手势区排除掉 —— 从系统区起手的手势
 *   根本到不了这里（系统先吃掉），即便到了也不接管，避免两边打架。
 */
private suspend fun AwaitPointerEventScope.runEdgeSwipe(
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    minXPx: Float,
    maxXPx: Float,
    touchSlop: Float,
    drawerWidthPx: Float,
    onStart: () -> Unit,
    onMove: (Float) -> Unit,
    onEnd: (Float, Float) -> Unit
) {
    val startX = down.position.x
    val inSystemEdge = startX < minXPx
    val maybeSwipe = !inSystemEdge && startX <= maxXPx
    if (!maybeSwipe) {
        return
    }

    val startPos: Offset = down.position
    var tracking = false
    var gaveUp = false
    var totalDx = 0f

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) {
            break
        }

        val dx = change.position.x - startPos.x
        val dy = change.position.y - startPos.y

        if (!tracking && !gaveUp) {
            if (abs(dx) < touchSlop && abs(dy) < touchSlop) {
                continue
            }
            // 竖向位移更大 → 这是页面滚动，不是右滑。放弃认领，让下层照常处理。
            if (abs(dy) > abs(dx)) {
                gaveUp = true
                continue
            }
            tracking = true
            onStart()
        }

        if (tracking) {
            // 认领后必须消费，否则下层页面会同时横向滚动。
            change.consume()
            totalDx = dx
            onMove(dx / drawerWidthPx)
        }
    }

    if (tracking) {
        onEnd(totalDx, totalDx / drawerWidthPx)
    }
}
