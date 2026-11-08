package com.dollhouse.app.ui.settings

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.data.ModelSelection
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】可用模型列表页：拉本供应商的模型清单 → 搜索筛选 → 「全选 (N)」批量导入 → 单条添加。
 *
 * 【口径】「全选」只作用于当前筛选结果，被搜索过滤掉的项不动（规格书明确要求）。
 *        已添加的模型不重复添加：「+」换成勾选态且不可点。
 *
 * 【迁移】r7 起正文改为 Jetpack Compose。**对外契约一字未动**：
 *   仍是 `object` + `@JvmStatic build(act, providerId): View`，仍由 [ProviderNav] 分派。
 *
 * 【为什么页根还是 View】`ProviderNav.render` 收的是 `View`，且
 *   [com.dollhouse.app.ui.theme.GlobalBackground] 要把背景图铺在**页根的 background** 上。
 *   这里用 [ComposeHost.createView] 产出 `ComposeView` 当「页」，内部 100% Compose。
 *
 * 【不做什么】本页只管「把服务端返回的名字变成模型记录」，能力字段一律按默认值创建
 *        （文本聊天模型），用户想改去模型编辑页逐个调。
 */
object ModelListPage {

    private const val LOG_TAG = "Dollhouse"

    @JvmStatic
    fun build(act: Activity, providerId: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { ModelListContent(act, providerId) }
    }

    /** 页面正文（Compose）。 */
    @Composable
    private fun ModelListContent(act: Activity, providerId: String) {
        val c = DhTokens.colors
        val pv = remember(providerId) { ProviderStore.findProvider(act, providerId) }
        if (pv == null) {
            DhKit.Page(
                title = "模型",
                sub = "供应商已不存在",
                onBack = { ProviderNav.back(act) }
            ) {
                DhForm.Note("这个供应商已经被删除了。")
            }
            return
        }

        // loaded / added 都放 remember：本页是「拉网络 → 展示 → 批量加」，不跨页共享。
        var loaded by remember { mutableStateOf<List<String>?>(null) }
        var added by remember { mutableStateOf<List<String>>(emptyList()) }
        var status by remember { mutableStateOf("正在拉取模型列表…") }
        var query by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var feedback by remember { mutableStateOf("") }

        // 视图挂好后再发网络：构造期就请求会让失败回调找不到宿主（与 View 版同口径）。
        LaunchedEffect(providerId) {
            busy = true
            ProviderNav.fetchModels(act, pv, object : ProviderNav.ModelsCallback {
                override fun onDone(models: MutableList<String>?, error: String?) {
                    busy = false
                    if (error != null) {
                        status = error
                        loaded = null
                        return
                    }
                    loaded = models ?: emptyList()
                    added = ModelStore.namesOf(act, pv.id)
                    status = "检测到 " + (models?.size ?: 0) + " 个可用模型"
                }
            })
        }

        val shown = remember(loaded, query) { ModelSelection.filter(loaded ?: emptyList(), query) }
        val pending = remember(shown, added) { ModelSelection.missing(shown, added) }

        DhKit.Page(
            title = pv.name,
            sub = "可用模型列表",
            onBack = { ProviderNav.back(act) },
            actions = {
                DhForm.SubmitButton(
                    text = "全部加入 (" + pending.size + ")",
                    state = if (busy) DhForm.SubmitState.LOADING else DhForm.SubmitState.IDLE,
                ) {
                    if (busy) return@SubmitButton
                    val batch = pending.map { newModel(pv.id, it) }
                    if (batch.isEmpty()) {
                        feedback = if (shown.isEmpty()) "没有匹配的模型" else "这些模型都已经加入"
                        return@SubmitButton
                    }
                    // 【批量写一次】原来是逐条 saveModel，每条都重写整份偏好；
                    //   这里沿用已有 saveModels 批量接口，避免长列表重复写盘。
                    ModelStore.saveModels(act, batch)
                    added = ModelStore.namesOf(act, pv.id)
                    feedback = "已加入 " + batch.size + " 个模型"
                }
            }
        ) {
            DhForm.Input(
                value = query,
                onValueChange = { query = it },
                hint = "按名称筛选"
            )
            Text(
                text = status,
                modifier = Modifier.padding(start = 2.dp, top = 8.dp),
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
            if (feedback.isNotEmpty()) {
                Text(
                    text = feedback,
                    modifier = Modifier.padding(start = 2.dp, top = DhDesign.Space.xs.dp),
                    color = c.acc,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fontsBody,
                    lineHeight = 18.sp,
                )
            }
            // 【惰性 + 限高】模型多的供应商一次返回几百个名字；forEach 会一次性
            //   组装全部卡片，首帧卡顿。这里只组合可见项，并让列表在页面内正常滚动。
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            ) {
                items(shown, key = { it }, contentType = { "model" }) { name ->
                    androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                        ModelRow(
                            name = name,
                            has = added.contains(name),
                            onAdd = {
                                if (added.contains(name)) return@ModelRow
                                ModelStore.saveModel(act, newModel(pv.id, name))
                                added = ModelStore.namesOf(act, pv.id)
                                feedback = "已加入 " + name
                            }
                        )
                    }
                }
            }
            DhForm.Note("点 + 立即加入本供应商；已加入的显示为勾选。")
        }
    }

    /** 一条：模型名 + 能力徽标行（默认值）+ 右侧 + / 勾选。← `ModelListPage.row`。 */
    @Composable
    private fun ModelRow(name: String, has: Boolean, onAdd: () -> Unit) {
        val c = DhTokens.colors
        DhKit.Card {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.padding(top = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        DhForm.Badge(
                            text = "文本 > 文本",
                            fg = c.chatChipFg,
                            bg = c.chatChipBg
                        )
                        Spacer(Modifier.width(DhDesign.Space.sm.dp))
                        DhForm.Badge(text = "流式", fg = c.sub, bg = c.soft)
                    }
                }
                if (has) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DhKit.Icon(iconRes = Icons.IC_CHECK, sizeDp = UiKit.FS_ICON, color = c.ok)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .pressable(onAdd),
                        contentAlignment = Alignment.Center
                    ) {
                        DhKit.Icon(iconRes = Icons.IC_PLUS, sizeDp = UiKit.FS_ICON, color = c.acc)
                    }
                }
            }
        }
    }

    /** 拉回来的模型一律按「文本聊天 + 流式」建成默认记录，用户可再逐个调。 */
    private fun newModel(providerId: String, name: String): AiModel {
        val m = AiModel()
        m.id = ProviderStore.newId()
        m.providerId = providerId
        m.displayName = name
        m.kind = AiModel.KIND_CHAT
        m.inputModalities = AiModel.MOD_TEXT
        m.outputModalities = AiModel.MOD_TEXT
        m.capStream = true
        m.enabled = true
        return m
    }
}