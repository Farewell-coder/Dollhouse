package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
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
 * 【职责】「初始位置」子页：在小人铺底上拖动一个中心点，确定这只人偶的起点。
 *
 * 【为什么用「归一化坐标」存】同一只人偶换了分辨率 / 转了屏之后，绝对像素会跑到屏幕外；
 *   存 0~1 的比例坐标能自动跟随屏幕尺寸（见 [DollProfile.setHomePivot]）。
 *   本页只在绘制时把比例换算成像素，落盘的一直是比例。
 *
 * 【与「回归时间」的关系】[DollReturnPage] 设置的秒数到点后，桌宠会回到这里定下的位置
 *   （[com.dollhouse.app.pet.PetWindow.applyHomePivot]）。没设过本页时，
 *   回归时间即便到了也没有目标点可回 —— 所以两页各自有一句说明互相指路。
 *
 * 【为什么没有「保存」以外的入口】拖动本身即时改的是本页的临时状态，
 *   只有点确认才写盘 —— 否则用户拖着玩两下就把原位置覆盖了，没法反悔。
 *
 * 【r11 形态】人偶改成「虚化低清晰度」铺底：它是参照物，不该抢中心点的视觉权重；
 *   中心点固定落在**人偶中心**，拖中心点时人偶跟着走 —— 用户看到的就是「桌宠会站哪儿」。
 */
object DollHomePage {
    /** 页面 key 前缀：`doll:home:<dollId>`。 */
    const val PREFIX = "doll:home:"

    /** 中心点直径。 */
    private val DOT = 26.dp

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
        val doll = remember(id) { DollStore.byId(act, id) }
        val sprite = remember(id) { DollStore.spriteOf(act, doll)?.asImageBitmap() }
        // 没设过时给一个「中下」的默认点：贴底是桌宠最常见的起点，比正中更符合直觉。
        val init = remember(id) { DollProfile.homePivot(act, id) }
        var rx by remember(id) { mutableStateOf(init?.get(0) ?: 0.5f) }
        var ry by remember(id) { mutableStateOf(init?.get(1) ?: 0.8f) }

        DhKit.Page(
            title = "初始位置",
            sub = if (doll == null) null else doll.name,
            onBack = { PageStack.pop(act) },
            scrollable = false
        ) {
            DhForm.Note("拖动中心点选位置（人偶会跟着走）；点「确认」才保存，点「取消」不记录。")
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
                val dotPx = with(density) { DOT.toPx() }

                // 人偶铺底：Fit 保证不裁切、不拉伸；r11 起再加一层「虚化 + 半透明」——
                //   它在这里只是参照物，清晰度压下去，中心点才醒目。
                // 【为什么用 graphicsLayer 平移】人偶按 Fit 居中画在容器中心，
                //   整体平移 (rx-0.5, ry-0.5) 个屏幕比例后，其视觉中心正好落在 (rx, ry)，
                //   与中心点重合 —— 于是「中心点在人偶中心」与「拖中心点人偶跟着走」同时成立。
                if (sprite != null) {
                    Image(
                        bitmap = sprite,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp)
                            .graphicsLayer {
                                translationX = (rx - 0.5f) * wPx
                                translationY = (ry - 0.5f) * hPx
                            }
                            .alpha(0.35f)
                            .blur(6.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                // 中心点：可拖动，夹在容器内（0~1）。用 offset{} 的 lambda 版，
                // 拖动时只改布局偏移、不触发重组里其他部分的重绘。
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (rx * wPx - dotPx / 2f).roundToInt(),
                                (ry * hPx - dotPx / 2f).roundToInt()
                            )
                        }
                        .size(DOT)
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.acc)
                        .border(2.dp, c.card, RoundedCornerShape(999.dp))
                        .pointerInput(wPx, hPx) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                if (wPx > 0f && hPx > 0f) {
                                    rx = (rx + drag.x / wPx).coerceIn(0f, 1f)
                                    ry = (ry + drag.y / hPx).coerceIn(0f, 1f)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    DhKit.Icon(Icons.IC_CROSSHAIR, Design.SZ_XS, c.onAcc)
                }
            }

            // 坐标回显：拖动时给个数字反馈，用户能判断「到底拖到哪了」。
            DhForm.Note("当前：人偶中心位于屏幕 " + pct(rx) + "% × " + pct(ry) + "%")

            // 按钮行：取消 / 确认。
            // 【r13 保存语义】rx / ry 只是本地草稿，点「确认」才写盘；
            //   「取消」与系统返回键都直接退出、不写任何键。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleButton(Icons.IC_CLOSE, c.sub) { PageStack.pop(act) }
                Spacer(Modifier.width(28.dp))
                CircleButton(Icons.IC_CHECK, c.acc) {
                    DollProfile.setHomePivot(act, id, rx, ry)
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
     * 圆形图标按钮：CARD 底 + 描边，保证铺在浅色小人底图上也能看清。
     * 【为什么不用 [DhKit.IconButton]】那个是透明底、为顶栏设计；这里是主操作按钮，
     *   需要实心底才不至于和背景糊在一起。
     */
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