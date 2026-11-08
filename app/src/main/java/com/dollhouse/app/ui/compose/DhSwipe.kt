package com.dollhouse.app.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ui.theme.Icons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 【职责】可左滑的行容器：左滑露出右侧的删除区，点它触发删除。Compose 版 `SwipeRow`。
 *
 * 【为什么不用 Material3 的 SwipeToDismiss】它的语义是「滑到底直接删」，
 *   而规格要的是「滑开露出垃圾桶、再点一次才删」—— 两步确认，避免误删。
 *   且它的指示区形状与卡片圆角对不上，会露出底色。
 *
 * 【手势口径】只在横向拖拽被识别为横向滑动时才接管；纵向滚动不受影响
 *   （`detectHorizontalDragGestures` 内部会先做方向判定，纵向手势不 consume）。
 *
 * 【回弹】spring(ζ=0.73, k=420)，与 `anim/Springs.kt` 的 `snappy()` 同参数，
 *   手感与全 App 其它动效一致。
 *
 * 【坑】① 滑开状态下点内容区应当先收回，而不是直接进编辑页；
 *   ② 删除区高度必须撑满，否则会塌成一条线；
 *   ③ 位移只由同一个 [Animatable] 持有，连滑两次不会有两个动画抢同一个值。
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onContentClick: (() -> Unit)? = null,
    /**
     * 【无障碍·等效入口】长按回调：给读屏用户一条**不依赖横向拖动**的删除路径。
     *
     * 【为什么必须由本组件提供】"左滑露出删除区"是纯手势交互：读屏用户要先探索到内容、
     *   再做一次横向拖动才能让删除区出现 —— 而 TalkBack 下横向拖动被用来切换焦点，
     *   这个动作基本做不出来。也就是说**名字补得再好，动作做不到就等于没有**
     *   （见 `docs/A11Y_FINDINGS.md` §9 遗留）。
     *
     * 【为什么用长按而不是常驻按钮】本工程的既定取舍是「行内不放常驻危险操作」
     *   （原生按钮会让每一行看起来都危险）；长按与既有 `pressable(onClick, onLongClick)`
     *   同口径，聊天消息、人偶动作行都是这么做的 —— 同一语义在全 App 只有一种手势。
     *
     * 【传给谁】调用方自己决定长按后做什么（通常是弹确认再调 [onDelete]），
     *   本组件**不重复实现删除**，只把事件交出去。
     */
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val c = DhTokens.colors
    val scope = rememberCoroutineScope()
    val maxPx = with(LocalDensity.current) { ACTION_DP.dp.toPx() }
    val openAt = maxPx * OPEN_RATIO
    val offset = remember { Animatable(0f) }
    // 位移是动画值、不是 state，这里用一个显式的布尔量记录「是否滑开」，
    // 让删除区只在滑开后接收点击 —— 否则未滑开时它被内容层完全盖住却仍可能吃到点击。
    var opened by remember { mutableStateOf(false) }

    /** 吸附到 [to]（0 或 -maxPx）。 */
    fun settle(to: Float) {
        opened = to < 0f
        scope.launch {
            // 【本次修复·参数同源】改造前这里内联 `spring(ζ=0.73, k=420)`，
            //   与文件头注释「与 Springs.snappy() 同参数」靠人工保持同步 —— 改一处漏一处。
            //   现在统一取 DhAnim.SWIPE_SETTLE（同一对象的单一真源）。
            offset.animateTo(to, DhAnim.SWIPE_SETTLE)
        }
    }

    Box(modifier = modifier) {
        // —— 右侧删除区 ——
        // 【r13 修 bug·垃圾桶不该常驻可见】用户原话：「它那个删除键是隐藏在底下的，
        //   我要的是往左滑的时候 删除键才出现」。
        //   原来这一层**一直**画在内容层右边（靠内容层盖住），于是内容层半透明时
        //   垃圾桶就透过来了 —— 看起来像「藏在卡片底下」。现在改成：
        //   只有滑开（opened）时才挂上去，没滑开时这一层根本不存在。
        if (opened) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(ACTION_DP.dp)
                    // 只把右侧两个角做圆：左侧直角与已滑开的卡片右边缘严丝合缝，
                    // 不再出现「两块圆角之间夹一道底色」的穿模感。
                    .clip(RoundedCornerShape(topEnd = DhKit.R_CARD.dp, bottomEnd = DhKit.R_CARD.dp))
                    .background(c.err)
                    // 【无障碍】这一块里只有一枚垃圾桶图标。不给名字，读屏只念「按钮」——
                    //   而它是个**不可撤销的删除**，名字必须说清动作。
                    //   【仍存在的缺口】读屏用户很难完成「横向滑开」这个手势去让它出现；
                    //   这里只保证「它出现时是可读的」，不解决可达性（见 A11Y_FINDINGS §3）。
                    .semantics { contentDescription = "删除此项" }
                    .pressable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                DhKit.Icon(Icons.IC_TRASH, 22f, c.onAcc)
            }
        }
        // —— 内容层（跟手位移） ——
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .then(
                    // 【点击语义】滑开状态下点内容区先收回，而不是执行 onContentClick ——
                    //   否则用户想关掉删除区，却被带进了另一个页面。
                    // 【长按】只有 `onLongPress` 非空时才挂双参重载（它没有 null 版本），
                    //   否则保持原来的单参重载，既有调用点逐字节不变。
                    if (onLongPress != null) {
                        Modifier.pressable(
                            onClick = {
                                if (opened) {
                                    settle(0f)
                                } else {
                                    onContentClick?.invoke()
                                }
                            },
                            onLongClick = onLongPress
                        )
                    } else if (onContentClick != null) {
                        Modifier.pressable {
                            if (opened) {
                                settle(0f)
                            } else {
                                onContentClick()
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = { settle(if (opened) -maxPx else 0f) },
                        onDragCancel = { settle(if (opened) -maxPx else 0f) }
                    ) { change, drag ->
                        change.consume()
                        val next = (offset.value + drag).coerceIn(-maxPx, 0f)
                        opened = next <= -openAt
                        scope.launch { offset.snapTo(next) }
                    }
                }
        ) {
            content()
        }
    }
}

/** 露出的删除区宽度：够放下一个 24dp 图标 + 左右呼吸。与 View 版 `SwipeRow.ACTION_DP` 一致。 */
private const val ACTION_DP = 68

/** 滑过删除区宽度的这个比例就算「要打开」，松手后吸附过去。 */
private const val OPEN_RATIO = 0.4f