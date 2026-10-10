package com.dollhouse.app.ui.provider

import android.app.Activity
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ai.ModelRules
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】供应商列表页（一级页）。
 *
 * 【交互】搜索实时过滤；行内「更多」菜单提供 重命名 / 删除；右上角「+」新增、导出按钮导出 JSON。
 *
 * 【迁移】r7 起正文改为 Jetpack Compose。**对外契约一字未动**：
 *   仍是 `object` + `@JvmStatic build(act): View`，仍由 [ProviderNav] 分派。
 *
 * 【为什么页根还是 View】[ProviderNav.render] 收的是 `View`，且
 *   [com.dollhouse.app.ui.theme.GlobalBackground] 要把背景图铺在**页根的 background** 上。
 *   这里用 [ComposeHost.createView] 产出 `ComposeView` 当「页」，内部 100% Compose。
 *
 * 【坑】① 禁用中的供应商行整体降对比度但不隐藏 —— 用户要能看到自己禁用了什么；
 *        ② 删除必须二次确认，且文案要写清会连带删掉几个模型（级联删除不可逆）；
 *        ③ 所有列表操作落盘后立即重组本页，不用缓存行对象。
 */
object ProviderListPage {

    @JvmStatic
    fun build(act: Activity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { ListContent(act) }
    }

    /** 页面正文（Compose）。 */
    @Composable
    private fun ListContent(act: Activity) {
        val c = DhTokens.colors
        // 每次落盘后 revision 自增，触发重读 ProviderStore（与 View 版「重建本页」等价）。
        var revision by remember { mutableStateOf(0) }
        var query by remember { mutableStateOf("") }
        val all = remember(revision) { ProviderStore.providers(act) }

        // 弹窗状态：null 表示不显示。
        var menuFor by remember { mutableStateOf<Provider?>(null) }
        var renameFor by remember { mutableStateOf<Provider?>(null) }
        var warnText by remember { mutableStateOf<String?>(null) }
        var deleteFor by remember { mutableStateOf<Provider?>(null) }

        val shown = remember(all, query) {
            val q = query.trim().lowercase()
            all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        }

        DhKit.Page(
            title = "供应商",
            sub = "共 " + all.size + " 个供应商",
            onBack = { ProviderNav.handleBack(act) },
            actions = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .pressable { com.dollhouse.app.ui.widget.ExportSheet.show(act) },
                    contentAlignment = Alignment.Center
                ) {
                    DhKit.Icon(iconRes = Icons.IC_EXTERNAL, sizeDp = UiKit.FS_ICON, color = c.sub)
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .pressable { ProviderNav.openNewProvider(act) },
                    contentAlignment = Alignment.Center
                ) {
                    DhKit.Icon(iconRes = Icons.IC_PLUS, sizeDp = UiKit.FS_ICON, color = c.acc)
                }
            }
        ) {
            DhForm.Input(
                value = query,
                onValueChange = { query = it },
                hint = "搜索供应商名称"
            )
            if (shown.isEmpty()) {
                DhKit.Card {
                    Text(
                        text = if (all.isEmpty()) "还没有配置任何供应商。" else "没有匹配的供应商。",
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts
                    )
                    if (all.isEmpty()) {
                        Spacer(Modifier.width(0.dp))
                        Box(modifier = Modifier.padding(top = 12.dp)) {
                            DhForm.PrimaryChip(text = "添加供应商") {
                                ProviderNav.openNewProvider(act)
                            }
                        }
                    }
                }
            } else {
                shown.forEach { p ->
                    ProviderRow(
                        p = p,
                        count = ModelStore.modelsOf(act, p.id).size,
                        onOpen = { ProviderNav.openDetail(act, p.id) },
                        onMore = { menuFor = p }
                    )
                }
            }
        }

        // —— 行内「更多」菜单 ——
        menuFor?.let { p ->
            DhForm.Alert(
                title = p.name,
                onDismiss = { menuFor = null }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MenuItem(text = "重命名") {
                        menuFor = null
                        renameFor = p
                    }
                    MenuItem(text = "删除") {
                        menuFor = null
                        deleteFor = p
                    }
                }
            }
        }

        // —— 重命名 ——
        renameFor?.let { p ->
            var name by remember(p.id) { mutableStateOf(p.name) }
            DhForm.Alert(
                title = "重命名供应商",
                onDismiss = { renameFor = null },
                posText = "保存",
                negText = "取消",
                onPos = {
                    val n = name.trim()
                    when {
                        n.isEmpty() -> warnText = "名称不能为空"
                        n == p.name -> Unit
                        ModelRules.nameExists(ProviderStore.providers(act), n, p.id) ->
                            warnText = "已有同名供应商，换一个名字"
                        else -> {
                            p.name = n
                            ProviderStore.saveProvider(act, p)
                            revision++
                        }
                    }
                }
            ) {
                DhForm.Input(value = name, onValueChange = { name = it }, hint = "供应商名称")
            }
        }

        // —— 校验失败提示 ——
        warnText?.let { msg ->
            DhForm.Alert(
                title = "无法重命名",
                onDismiss = { warnText = null },
                posText = "知道了",
                onPos = { warnText = null }
            ) {
                Text(
                    text = msg,
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts,
                    lineHeight = (UiKit.FS_SUB + 5f).sp
                )
            }
        }

        // —— 删除二次确认 ——
        deleteFor?.let { p ->
            val count = remember(p.id) { ModelStore.modelsOf(act, p.id).size }
            DhForm.Alert(
                title = "删除供应商",
                onDismiss = { deleteFor = null },
                posText = "删除",
                negText = "取消",
                onPos = {
                    ProviderStore.deleteProvider(act, p.id)
                    deleteFor = null
                    revision++
                }
            ) {
                Text(
                    text = "「" + p.name + "」将被删除，它名下的 " + count + " 个模型会一并删除，删除后无法恢复。",
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts,
                    lineHeight = (UiKit.FS_SUB + 5f).sp
                )
            }
        }
    }

    /** 弹窗里的纯文字行。← `ApiPageKit.menuItem`。 */
    @Composable
    private fun MenuItem(text: String, onClick: () -> Unit) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick)
                .padding(horizontal = 8.dp, vertical = 14.dp),
            color = DhTokens.colors.title,
            fontSize = UiKit.FS_BTN.sp,
            fontFamily = DhTokens.fonts
        )
    }

    /** 一行：首字头像 + 名称 + 状态/模型数徽标 + 更多菜单。← `ProviderListPage.providerRow`。 */
    @Composable
    private fun ProviderRow(p: Provider, count: Int, onOpen: () -> Unit, onMore: () -> Unit) {
        val c = DhTokens.colors
        DhKit.Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 禁用态整体降对比度（不隐藏）。
                    .alpha(if (p.enabled) 1f else 0.55f)
                    .pressable(onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.field),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (p.name.isEmpty()) "?" else p.name.substring(0, 1),
                        color = c.acc,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = p.name,
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        DhForm.Badge(
                            text = if (p.enabled) "启用" else "禁用",
                            fg = if (p.enabled) c.ok else c.err,
                            bg = if (p.enabled) DhForm.semanticBg(c.ok) else DhForm.semanticBg(c.err)
                        )
                        Spacer(Modifier.width(6.dp))
                        DhForm.Badge(
                            text = count.toString() + " 个模型",
                            fg = c.chatChipFg,
                            bg = c.chatChipBg
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .pressable(onMore),
                    contentAlignment = Alignment.Center
                ) {
                    DhKit.Icon(iconRes = Icons.IC_MORE, sizeDp = UiKit.FS_ICON, color = c.sub)
                }
            }
        }
    }
}