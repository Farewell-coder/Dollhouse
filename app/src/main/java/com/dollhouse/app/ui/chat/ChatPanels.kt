package com.dollhouse.app.ui.chat

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ai.ModelRules
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.kit.DhDialogHost
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】聊天页的两个「选择面板」：模型配置、思考深度。
 *
 * 【M8·从 521 行手写 View 迁到 Compose】
 *   改造前由 `ui/widget/SheetPanel.kt` 实现：自建遮罩 FrameLayout、手写 `LinearLayout` 行、
 *   用 `OpenRow` 句柄手工管理「哪个供应商展开」、用 `ThinkSheet.rebuild()` 整块重画刻度。
 *   它必须如此啰嗦的原因**只是**「调用点在命令式的 `ChatPanel.showModels()` 里，
 *   不能直接写 @Composable」——现在这个限制被 `ui/kit/DhDialogHost` 解决了。
 *
 *   迁移后消失的复杂度：
 *     · 遮罩 / 面板 / 关闭逻辑 —— 交给 Dialog 窗口
 *     · 打开哪一组的手工句柄 —— 改为 `var expanded by remember`，一次声明
 *     · 整块重画 —— Compose 按状态重组，不需要 removeAllViews + 重建
 *     · 三套文字样式（title / sub / row) —— 用统一的 `t.title` / `t.sub` / `t.body`
 *
 *   行为保持不变（逐项核对过原实现）：
 *     · 点供应商展开/收起它的模型；同时只允许一组展开
 *     · 点模型 → `ProviderStore.setCurrent(providerId, modelId)` 并关窗
 *     · 当前选中的模型带标记
 *     · 思考档位写入 `PetPrefs.setThinkLevel`，同时刷新档位名与刻度
 */
object ChatPanels {

    /**
     * 模型配置面板。
     *
     * @param act 宿主（用于 Dialog 与读库）
     */
    fun showModels(act: Activity?) {
        if (act == null) {
            return
        }
        // 【数据在组合外读一次】供应商与模型清单不在流式期间变化，没必要每次重组都读盘；
        //   原实现在打开时读一次，这里保持同一时效。
        val groups = ModelRules.enabledGroups(ProviderStore.providers(act), ModelStore.models(act))
        val current = ProviderStore.current(act)
        DhDialogHost.show(act, widthInsetDp = 20) { dismiss ->
            ModelsPanel(
                groups = groups,
                // Selection 的字段是 providerId / modelId（不是我最初写的 provider / model）：
                //   provider 是解析出的 Provider 对象，comparison 用的是它的 id。
                selProviderId = current.provider?.id ?: current.providerId,
                selModelId = current.modelId,
                onPick = { pid, mid ->
                    ProviderStore.setCurrent(act, pid, mid)
                    dismiss()
                },
                onDismiss = { dismiss() }
            )
        }
    }

    /**
     * 思考深度面板。
     */
    fun showThink(act: Activity?) {
        if (act == null) {
            return
        }
        DhDialogHost.show(act, widthInsetDp = 24) { dismiss ->
            ThinkPanel(
                initial = PetPrefs.thinkLevel(act),
                onPick = { level ->
                    PetPrefs.setThinkLevel(act, level)
                    dismiss()
                },
                onDismiss = { dismiss() }
            )
        }
    }

    /* ================================================================
     * 模型配置面板
     * ================================================================ */

    @Composable
    private fun ModelsPanel(
        groups: List<ModelRules.ModelGroup>,
        selProviderId: String?,
        selModelId: String?,
        onPick: (providerId: String, modelId: String) -> Unit,
        onDismiss: () -> Unit
    ) {
        val p = DhTokens.palette
        val t = DhTokens.type
        // 【当前展开的供应商】同一时刻只允许一组展开 —— 原实现用 OpenRow 句柄 + 手工收起，
        //   这里一个状态变量就够了：点另一组时它自然变成新值，旧组随之收起。
        var expanded by remember {
            mutableStateOf(
                groups.firstOrNull { it.providerId == selProviderId }?.providerId
                    ?: groups.firstOrNull()?.providerId
            )
        }
        PanelPaper(onDismiss = onDismiss) {
            Text(text = "模型配置", color = p.title, style = t.title)
            Spacer(Modifier.height(DhDesign.Space.xs.dp))
            Text(
                text = "点供应商展开它的模型，再点一个模型就切过去。" +
                    "共 " + groups.size + " 个启用中的供应商。\n" +
                    "供应商或模型被禁用时不在这里出现，要增删改去设置页的「提供商」里操作。",
                color = p.sub,
                style = t.sub
            )
            Spacer(Modifier.height(DhDesign.Space.md.dp))
            if (groups.isEmpty()) {
                Text(
                    text = "还没有启用中的供应商。先去设置 →「提供商」里添加一个。",
                    color = p.sub,
                    style = t.body
                )
                Spacer(Modifier.height(DhDesign.Space.md.dp))
                DhButtonRow(text = "知道了", onClick = onDismiss)
                return@PanelPaper
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                groups.forEachIndexed { gi, g ->
                    if (gi > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = DhDesign.Space.xs.dp)
                                .height(DhDesign.Size.hairline)
                                .background(p.line)
                        )
                    }
                    val open = g.providerId == expanded
                    val activeGroup = g.providerId == selProviderId
                    ProviderRow(
                        name = g.providerName,
                        count = g.models.size,
                        open = open,
                        active = activeGroup,
                        onClick = { expanded = if (open) null else g.providerId }
                    )
                    if (open) {
                        g.models.forEach { m ->
                            ModelRow(
                                name = m.displayName,
                                selected = m.id == selModelId,
                                onClick = { onPick(g.providerId, m.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    /** 供应商行：名字 + 「N 个模型」+ 展开箭头。 */
    @Composable
    private fun ProviderRow(
        name: String,
        count: Int,
        open: Boolean,
        active: Boolean,
        onClick: () -> Unit
    ) {
        val p = DhTokens.palette
        val t = DhTokens.type
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                .pressable(onClick)
                .padding(vertical = DhDesign.Space.md.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        color = if (active) p.accent else p.title,
                        style = t.rowTitle,
                        maxLines = 1
                    )
                    if (active) {
                        Spacer(Modifier.width(DhDesign.Space.sm.dp))
                        Text(text = "使用中", color = p.accent, style = t.label)
                    }
                }
                Text(
                    text = count.toString() + " 个模型",
                    color = p.sub,
                    style = t.caption
                )
            }
            // 【箭头】与原实现同语义：收起指下、展开指上。
            Box(
                modifier = Modifier.size(DhDesign.Size.iconLg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (open) "︿" else "﹀",
                    color = p.sub,
                    style = t.body
                )
            }
        }
    }

    /** 模型行：缩进一级，选中带对勾。 */
    @Composable
    private fun ModelRow(name: String, selected: Boolean, onClick: () -> Unit) {
        val p = DhTokens.palette
        val t = DhTokens.type
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                .pressable(onClick)
                .padding(
                    start = DhDesign.Space.xl.dp,
                    end = DhDesign.Space.md.dp,
                    top = DhDesign.Space.sm.dp + 2.dp,
                    bottom = DhDesign.Space.sm.dp + 2.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                color = if (selected) p.accent else p.title,
                style = t.body,
                maxLines = 1
            )
            if (selected) {
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                DhKit.Icon(Icons.IC_CHECK, DhDesign.Size.iconSm.value, p.accent)
            }
        }
    }

    /* ================================================================
     * 思考深度面板
     * ================================================================ */

    @Composable
    private fun ThinkPanel(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
        val p = DhTokens.palette
        val t = DhTokens.type
        // 【本地状态】点哪档就立即显示哪档；原实现是 rebuild() 整块重画，
        //   这里一次声明即可，且用户点完会关窗，不需要把状态写回页面。
        var level by remember { mutableStateOf(initial) }
        val names = PetPrefs.THINK_NAMES
        PanelPaper(onDismiss = onDismiss) {
            Text(text = "调整模型思考深度", color = p.title, style = t.title)
            Spacer(Modifier.height(DhDesign.Space.xs.dp))
            Text(
                text = "并不是所有模型都支持深度调整。服务端不认识这些参数时会忽略，不影响正常对话。",
                color = p.sub,
                style = t.sub
            )
            Spacer(Modifier.height(DhDesign.Space.lg.dp))

            // 灯泡 + 当前档位名
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                        .background(p.capsuleSel),
                    contentAlignment = Alignment.Center
                ) {
                    DhKit.Icon(Icons.IC_BRAIN, DhDesign.Size.iconLg.value, p.accent)
                }
                Spacer(Modifier.width(DhDesign.Space.md.dp))
                Text(
                    text = names.getOrElse(level) { "" },
                    color = p.title,
                    style = t.section
                )
            }
            Spacer(Modifier.height(DhDesign.Space.md.dp))

            // 档位刻度：6 段
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DhDesign.Space.xs.dp + 1.dp)
            ) {
                names.forEachIndexed { i, _ ->
                    val on = i == level
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(DhDesign.Radius.sm.dp))
                            .background(if (i <= level) p.accent else p.line)
                            .pressable { level = i }
                    )
                }
            }
            Spacer(Modifier.height(DhDesign.Space.md.dp))

            // 档位名：一行一个，点即生效
            names.forEachIndexed { i, n ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                        .pressable { onPick(i) }
                        .padding(vertical = DhDesign.Space.sm.dp + 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = n,
                        modifier = Modifier.weight(1f),
                        color = if (i == level) p.accent else p.title,
                        style = if (i == level) t.rowTitle else t.body
                    )
                    if (i == level) {
                        DhKit.Icon(Icons.IC_CHECK, DhDesign.Size.iconSm.value, p.accent)
                    }
                }
            }
            Spacer(Modifier.height(DhDesign.Space.sm.dp))
            Text(
                text = "「不思考」= 明确要求她别绕弯子；「自动」= 交给服务端自己决定；再往后依次加深。",
                color = p.sub,
                style = t.caption
            )
        }
    }

    /* ================================================================
     * 公共纸张
     * ================================================================ */

    /**
     * 面板纸张：`sheet` 不透明底 + 16 圆角 + 统一内边距。
     *
     * 【为什么必须不透明】面板的职责是盖住下层；改造前 `UiKit.sheet()` 会按用户
     *   「磨砂程度」往背景色混色但**恒为 255 alpha**，这里保持同一语义。
     */
    @Composable
    private fun PanelPaper(
        onDismiss: () -> Unit,
        content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
    ) {
        val p = DhTokens.palette
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DhDesign.Radius.lg.dp))
                .background(p.sheet)
                .border(
                    DhDesign.Size.hairline,
                    p.line,
                    RoundedCornerShape(DhDesign.Radius.lg.dp)
                )
                .padding(DhDesign.Space.lg.dp),
            content = content
        )
    }

    /** 面板底部的主按钮（「知道了」这类唯一动作）。 */
    @Composable
    private fun DhButtonRow(text: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                    .pressable(onClick)
                    .padding(horizontal = DhDesign.Space.lg.dp, vertical = DhDesign.Space.sm.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = DhTokens.palette.accent,
                    style = DhTokens.type.rowTitle,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}