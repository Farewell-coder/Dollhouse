package com.dollhouse.app.ui.doll

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import kotlin.math.roundToInt

/**
 * 【职责】「屏幕范围」子页：限定这只人偶可以在屏幕的哪一块区域内活动。
 *
 * 【为什么横竖屏各存一套】竖屏下「整屏」是个竖长条，横屏下是个横长条；
 *   用同一套归一化边界会导致转屏后范围变得莫名其妙（比如横屏设的 0.2~0.8 竖屏里变成
 *   上下被砍掉一半）。分开存后，转屏自动取用对应那套，用户不必每次转屏都重设。
 *   方向键由 [DollProfile.orientOf] 统一给出。
 *
 * 【为什么是「四边各一个手柄」而不是画矩形】手机屏幕上手指比手柄粗，
 *   拖动「一条边」比拖动「一个角」更容易命中，也更不容易误改相邻的边。
 */
object DollRangePage {
    /** 页面 key 前缀：`doll:range:<dollId>`。 */
    const val PREFIX = "doll:range:"

    /** 边手柄直径。 */
    private val HANDLE = 28.dp

    @JvmStatic
    fun open(act: MainActivity, id: String) {
        PageStack.push(act, PREFIX + id) { buildPage(act, id) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity, id: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, id) }
    }

    @Composable
    private fun Content(act: MainActivity, id: String) {
        val c = DhTokens.colors
        val land = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val orient = remember(land) { DollProfile.orientOf(land) }
        val init = remember(id, orient) { DollProfile.bounds(act, id, orient) }
        var l by remember(id, orient) { mutableStateOf(init[0]) }
        var t by remember(id, orient) { mutableStateOf(init[1]) }
        var r by remember(id, orient) { mutableStateOf(init[2]) }
        var b by remember(id, orient) { mutableStateOf(init[3]) }

        DhKit.Page(
            title = "屏幕范围",
            sub = if (land) "横屏" else "竖屏",
            onBack = { PageStack.pop(act) },
            scrollable = false
        ) {
            DhForm.Note("拖动四条边，框内就是人偶可活动的范围。横屏与竖屏各存一套，转屏自动切换。")
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                    .background(c.soft)
            ) {
                val density = LocalDensity.current
                val wPx = with(density) { maxWidth.toPx() }
                val hPx = with(density) { maxHeight.toPx() }
                val hdPx = with(density) { HANDLE.toPx() }

                // 可活动区域：实线框 + 主色描边；框外即「人偶到不了的地方」。
                Box(
                    modifier = Modifier
                        .offset { IntOffset((l * wPx).roundToInt(), (t * hPx).roundToInt()) }
                        .size(
                            width = with(density) { ((r - l) * wPx).toDp() },
                            height = with(density) { ((b - t) * hPx).toDp() }
                        )
                        .border(1.dp, c.acc, RoundedCornerShape(4.dp))
                )

                // 上边手柄：拖动改 top（不能越过 bottom - 0.1）。
                EdgeHandle(
                    x = ((l + r) / 2f) * wPx - hdPx / 2f,
                    y = t * hPx - hdPx / 2f,
                    tint = c.acc,
                    onDrag = { _, dy ->
                        if (hPx > 0f) t = (t + dy / hPx).coerceIn(0f, b - 0.1f)
                    }
                )
                // 下边手柄。
                EdgeHandle(
                    x = ((l + r) / 2f) * wPx - hdPx / 2f,
                    y = b * hPx - hdPx / 2f,
                    tint = c.acc,
                    onDrag = { _, dy ->
                        if (hPx > 0f) b = (b + dy / hPx).coerceIn(t + 0.1f, 1f)
                    }
                )
                // 左边手柄。
                EdgeHandle(
                    x = l * wPx - hdPx / 2f,
                    y = ((t + b) / 2f) * hPx - hdPx / 2f,
                    tint = c.acc,
                    onDrag = { dx, _ ->
                        if (wPx > 0f) l = (l + dx / wPx).coerceIn(0f, r - 0.1f)
                    }
                )
                // 右边手柄。
                EdgeHandle(
                    x = r * wPx - hdPx / 2f,
                    y = ((t + b) / 2f) * hPx - hdPx / 2f,
                    tint = c.acc,
                    onDrag = { dx, _ ->
                        if (wPx > 0f) r = (r + dx / wPx).coerceIn(l + 0.1f, 1f)
                    }
                )
            }

            DhForm.Note(
                "当前：" + pct(l) + "% , " + pct(t) + "%  →  " + pct(r) + "% , " + pct(b) + "%"
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 取消 = 直接退出，不写任何键（与悬浮窗同一语义）。
                CircleButton(Icons.IC_CLOSE, c.sub) { PageStack.pop(act) }
                Spacer(Modifier.width(20.dp))
                // 重置：只把**本地草稿**拨回整屏（0,0,1,1），**不落盘** ——
                //   【r13 修 bug】原来这里立刻 setBounds，用户点取消时框已经被改掉了。
                CircleButton(Icons.IC_ROTATE_CCW, c.sub) {
                    l = 0f
                    t = 0f
                    r = 1f
                    b = 1f
                }
                Spacer(Modifier.width(20.dp))
                // 确认 = 把草稿写盘后退出。
                CircleButton(Icons.IC_CHECK, c.acc) {
                    DollProfile.setBounds(act, id, orient, l, t, r, b)
                    PageStack.pop(act)
                }
            }
        }
    }

    /** 0~1 → 百分数整数字符串（只用于回显，不参与存储）。 */
    private fun pct(v: Float): String {
        return (v * 100f).roundToInt().toString()
    }

    /**
     * 边手柄：固定尺寸圆钮，拖动回调给「像素增量」。
     * 【为什么用 pointerInput 而不是 draggable】需要同时拿到 dx 与 dy（上下手柄只用 dy、
     *   左右手柄只用 dx），`detectDragGestures` 一次给全，比拼两个 modifier 干净。
     */
    @Composable
    private fun EdgeHandle(
        x: Float,
        y: Float,
        tint: Color,
        onDrag: (dx: Float, dy: Float) -> Unit
    ) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(HANDLE)
                .clip(RoundedCornerShape(999.dp))
                .background(c.card)
                .border(2.dp, tint, RoundedCornerShape(999.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tint)
            )
        }
    }

    /** 圆形图标按钮（与初始位置页同一口径，实心底以免和底图糊在一起）。 */
    @Composable
    private fun CircleButton(iconRes: Int, tint: Color, onClick: () -> Unit) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.card)
                .border(1.dp, c.line, RoundedCornerShape(999.dp))
                .pressable(onClick),
            contentAlignment = Alignment.Center
        ) {
            DhKit.Icon(iconRes, Design.SZ_LG, tint)
        }
    }
}