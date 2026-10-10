package com.dollhouse.app.ui.provider

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.View
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.SwipeToDelete
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】供应商详情页的「模型」tab：列出本供应商**已经添加**的模型。
 *
 * 【与「可用模型清单」页的分工】本页只管 ModelStore 里真实存在的记录；
 *        联网拉清单、把名字变成记录的那件事归 [ModelListPage]（「添加模型」按钮的落点）。
 *        两者不重叠，各自单一职责。
 *
 * 【交互】点一行 → 模型编辑页（改名称 / 类型 / 模态 / 能力开关）；
 *        左滑该行 → 露出垃圾桶 → 点它删除（二次确认）。
 *
 * 【为什么删除走左滑而不是行内常驻按钮】行内按钮会让每一行看起来都是危险操作；
 *        左滑是明确的意图表达，平时不留视觉噪音。
 *
 * 【本版与 View 版的差异】
 *   ① 数据流从「手写 st 结构 + removeAllViews 重绘」改成 `revision` 驱动的重组；
 *   ② 删除确认从 `UiKit.showDialog`（View 弹窗）换成 [DhForm.Alert]（Compose 弹窗）；
 *   ③ 左滑删除仍用既有 `SwipeRow`（View 侧构件，见下）。
 */
object ProviderModelsPage {

    @JvmStatic
    fun build(act: Activity, providerId: String?): View {
        return build(act, providerId, 0, null)
    }

    /**
     * 嵌入式形态（带让位与滚动上报），供 [ProviderDetailPage] 的「模型」tab 使用。
     *
     * 【为什么参数在这一层】壳层是 View 树，既拿不到 Compose 的 `scrollState`，也无法
     *   从 View 树里找到 Compose 的滚动容器（`verticalScroll` 不是 `android.widget.ScrollView`）。
     *   故让位与滚动上报由本页交给 [DhKit.Page]，桥接不泄漏到壳层。
     */
    @JvmStatic
    fun build(act: Activity, providerId: String?, bottomPadDp: Int, onScroll: ((y: Int) -> Unit)?): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { ModelsContent(act, providerId, bottomPadDp, onScroll) }
    }

    @Composable
    private fun ModelsContent(
        act: Activity,
        providerId: String?,
        bottomPadDp: Int = 0,
        onScroll: ((y: Int) -> Unit)? = null
    ) {
        // 【用 revision 触发重读】删除后只改这个计数，重组时会重新查 ModelStore，
        //   不整页重建 —— 与 View 版的 ProviderNav.refresh 效果一致但不闪。
        var revision by remember { mutableStateOf(0) }
        val pv = remember(providerId) { ProviderStore.findProvider(act, providerId) }
        if (pv == null) {
            DhKit.Page(title = "模型", sub = "供应商已不存在", onBack = { ProviderNav.back(act) }) {
                DhForm.Note("这个供应商已经被删除了。")
            }
            return
        }
        val models = remember(revision, pv.id) { ModelStore.modelsOf(act, pv.id) }
        var pending by remember { mutableStateOf<AiModel?>(null) }

        DhKit.Page(
            title = pv.name,
            sub = "共 " + models.size + " 个模型",
            onBack = { ProviderNav.back(act) },
            bottomPadDp = bottomPadDp,
            onScroll = onScroll
        ) {
            if (models.isEmpty()) {
                DhKit.Card {
                    Text(
                        text = "这个供应商下还没有模型。",
                        color = DhTokens.colors.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fontsBold
                    )
                    DhForm.Note("点下面的「添加模型」从供应商拉取清单，选中的模型会出现在这里，也会出现在聊天页的模型选择器里。")
                }
            } else {
                models.forEach { m ->
                    DhSwipeHost(
                        onOpen = { ProviderNav.openModel(act, m.id) },
                        onDelete = { pending = m }
                    ) {
                        ModelRow(m)
                    }
                }
            }
            // 底部留白：悬浮「添加模型」按钮高 44dp + 上下各 12dp 呼吸。
            Spacer(Modifier.size(68.dp))
            AddModelButton { ProviderNav.openModels(act, pv.id) }
        }

        val victim = pending
        if (victim != null) {
            DhForm.Alert(
                title = "删除模型",
                onDismiss = { pending = null },
                posText = "删除",
                onPos = {
                    ModelStore.deleteModel(act, victim.id)
                    pending = null
                    revision++
                },
                negText = "取消"
            ) {
                DhForm.Note("「" + victim.displayName + "」将从本供应商删除，删除后无法恢复。")
            }
        }
    }

    /** 一行模型卡片。 */
    @Composable
    private fun ModelRow(m: AiModel) {
        val c = DhTokens.colors
        DhKit.Card(spacingBottom = false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = m.displayName,
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fontsBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DhForm.Badge(AiModel.kindLabel(m.kind), c.chatChipFg, c.chatChipBg)
                        Spacer(Modifier.width(6.dp))
                        DhForm.Badge(
                            text = if (m.enabled) "启用" else "禁用",
                            fg = if (m.enabled) c.ok else c.err,
                            bg = if (m.enabled) DhForm.semanticBg(c.ok) else DhForm.semanticBg(c.err)
                        )
                    }
                }
                // 右侧箭头提示「点进去能编辑」，与其它列表页的语义保持一致。
                DhKit.Icon(Icons.IC_CHEVRON_RIGHT, 18f, c.sub)
            }
        }
    }

    /**
     * 一行「可左滑删除 + 可点进编辑」的容器。
     *
     * 【点击语义】滑开状态下点内容区先收回，否则才当作「点进编辑」——
     *   与 View 版 `SwipeRow` 的口径一致：用户想关掉删除区时不该被带进另一个页面。
     */
    @Composable
    private fun DhSwipeHost(onOpen: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
        SwipeToDelete(
            onDelete = onDelete,
            modifier = Modifier.padding(top = 8.dp),
            onContentClick = onOpen
        ) {
            content()
        }
    }

    /** 悬浮的「添加模型」胶囊：落在底栏正上方居中。 */
    @Composable
    private fun AddModelButton(onClick: () -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier
                    .pressable(onClick)
                    .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                    .background(c.acc)
                    .padding(start = 20.dp, top = 12.dp, end = 22.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DhKit.Icon(Icons.IC_PLUS, 18f, c.onAcc)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "添加模型",
                    color = c.onAcc,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold
                )
            }
        }
    }
}
