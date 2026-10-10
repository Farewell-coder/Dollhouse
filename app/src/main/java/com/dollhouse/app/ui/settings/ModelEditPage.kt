package com.dollhouse.app.ui.settings

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】添加 / 编辑模型页：名称 + 类型 + 输入输出模态 + 能力开关 + 启用。
 *
 * 【规则】类型 / 模态 / 能力三者的互相约束全部走 [ModelEditKit]（纯函数），本页只负责摆控件与回读。
 *        每点一格就 normalize + 重绘，用户不可能停在非法组合上。
 *
 * 【交互】「从供应商拉取」直接跳到模型列表页（那里有搜索与全选），不在本页重复做一遍。
 *        保存走 [ModelStore]，成功后一步返回上一层。
 *
 * 【隐私】本页不碰密钥。
 *
 * 【迁移】r7 起正文改为 Jetpack Compose。**对外契约一字未动**：
 *   仍是 `object` + `@JvmStatic buildNew / buildEdit(act, id): View`，仍由 [ProviderNav] 分派。
 */
object ModelEditPage {

    @JvmStatic
    fun buildNew(act: Activity, providerId: String?): View {
        return build(act, providerId, null)
    }

    @JvmStatic
    fun buildEdit(act: Activity, modelId: String?): View {
        val m = ModelStore.findModel(act, modelId)
        return build(act, if (m == null) "" else m.providerId, m)
    }

    private fun build(act: Activity, providerId: String?, src: AiModel?): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { EditContent(act, providerId, src) }
    }

    /**
     * 页面正文（Compose）。
     *
     * 【状态怎么摆】View 版把选择状态存在 `ModelEditKit.Sel` 里、靠一堆 `TextView` 句柄重绘；
     *   Compose 侧改成「`Sel` 每次点击产生新实例 + `remember` 持有」，重绘交给重组，
     *   不再需要 `paintChip` 手动刷。规则仍全走 [ModelEditKit]，口径与 View 版一字不差。
     */
    @Composable
    private fun EditContent(act: Activity, providerId: String?, src: AiModel?) {
        val c = DhTokens.colors
        val pid = providerId ?: ""
        val pv: Provider? = remember(pid) { ProviderStore.findProvider(act, pid) }
        val created = src != null

        var name by remember(src?.id) { mutableStateOf(src?.displayName ?: "") }
        // 【为什么每次点击都新建 Sel】Sel 的字段是可变 var，若原地改就不会触发重组；
        //   这里统一走 `sel = copyOf(sel).also { ... }` 的写法，保证状态变化一定被 Compose 看见。
        var sel by remember(src?.id) { mutableStateOf(ModelEditKit.of(src)) }
        var enabled by remember(src?.id) { mutableStateOf(src == null || src.enabled) }
        var advOpen by remember { mutableStateOf(false) }
        var hintText by remember { mutableStateOf<String?>(null) }
        var hintOk by remember { mutableStateOf(false) }

        DhKit.Page(
            title = if (created) "编辑模型" else "添加模型",
            sub = if (pv == null) "供应商不存在" else pv.name,
            onBack = { ProviderNav.back(act) }
        ) {
            // —— 从供应商拉取（跳到模型列表页，那里有搜索 / 全选） ——
            DhKit.Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable {
                            if (pv == null) {
                                hintOk = false
                                hintText = "供应商不存在，无法拉取"
                            } else {
                                ProviderNav.openModels(act, pid)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "从供应商拉取模型",
                        modifier = Modifier.weight(1f),
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts
                    )
                    DhKit.Icon(Icons.IC_CHEVRON_RIGHT, 18f, c.sub)
                }
                DhForm.Note("拉回来的模型按「文本聊天 + 流式」建默认记录，之后可回这里逐条调能力。")
            }

            // —— 基本信息 ——
            DhKit.Card {
                DhForm.Input(name, { name = it }, label = "模型名称", hint = "例如 deepseek-v4.1-flash-api")
            }

            // —— 模型类型 ——
            DhKit.Card {
                DhKit.SectionLabel("模型类型")
                ChipRow(
                    labels = Array(AiModel.KINDS.size) { i -> AiModel.kindLabel(AiModel.KINDS[i]) },
                    selected = Array(AiModel.KINDS.size) { i -> AiModel.KINDS[i] == sel.kind }
                ) { i ->
                    sel = normalize(copySel(sel).also { it.kind = AiModel.KINDS[i] })
                }
            }

            // —— 输入 / 输出模态 ——
            DhKit.Card {
                val emb = AiModel.KIND_EMBEDDING == sel.kind
                DhKit.SectionLabel("输入模态")
                ChipRow(
                    labels = Array(ModelEditKit.MODS.size) { i -> AiModel.modLabel(ModelEditKit.MODS[i]) },
                    selected = Array(ModelEditKit.MODS.size) { i -> sel.`in`[i] },
                    dimmed = Array(ModelEditKit.MODS.size) { i -> emb && AiModel.MOD_TEXT != ModelEditKit.MODS[i] }
                ) { i ->
                    val next = copySel(sel)
                    next.`in`[i] = !next.`in`[i]
                    sel = normalize(next)
                }
                Spacer(Modifier.height(10.dp))
                DhKit.SectionLabel("输出模态")
                ChipRow(
                    labels = Array(ModelEditKit.MODS.size) { i -> AiModel.modLabel(ModelEditKit.MODS[i]) },
                    selected = Array(ModelEditKit.MODS.size) { i -> sel.out[i] },
                    dimmed = Array(ModelEditKit.MODS.size) { i -> emb && AiModel.MOD_TEXT != ModelEditKit.MODS[i] }
                ) { i ->
                    val next = copySel(sel)
                    next.out[i] = !next.out[i]
                    sel = normalize(next)
                }
                DhForm.Note("勾「视觉」会自动保证输入含图片；类型为「图片」时必须能输出图片。")
            }

            // —— 能力开关 ——
            DhKit.Card {
                val emb = AiModel.KIND_EMBEDDING == sel.kind
                DhKit.SectionLabel("能力")
                CapRow("视觉（VISION）", "能理解图片输入", sel.vision, emb) {
                    sel = normalize(copySel(sel).also { s -> s.vision = !s.vision })
                }
                CapRow("工具调用（TOOL_CALL）", "支持函数调用", sel.tool, emb) {
                    sel = normalize(copySel(sel).also { s -> s.tool = !s.tool })
                }
                CapRow("推理（REASONING）", "支持思考过程字段", sel.reasoning, emb) {
                    sel = normalize(copySel(sel).also { s -> s.reasoning = !s.reasoning })
                }
                CapRow("启用", "关掉后不出现在聊天页选择器里", enabled, false) { v ->
                    enabled = v
                    // 【响应性】启用态一变，主页「打开聊天」的可用性随之改变，必须立刻重算，
                    //   不能等用户返回主页触发 onResume（否则表现为「关掉全部模型后按钮还赖着不走」）。
                    if (created) {
                        ModelStore.setModelEnabled(act, src?.id ?: "", v)
                    }
                    HomeUi.syncChatEntryNow(act)
                }
            }

            // —— 可折叠：生成参数 ——
            DhKit.Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable { advOpen = !advOpen },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "生成参数",
                        modifier = Modifier.weight(1f),
                        color = c.sub,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts
                    )
                    DhKit.Icon(
                        if (advOpen) Icons.IC_CHEVRON_UP else Icons.IC_CHEVRON_DOWN,
                        18f,
                        c.sub
                    )
                }
                if (advOpen) {
                    CapRow("流式输出", "边生成边渲染，长回答不必等整段", sel.stream, false) { v ->
                        sel = copySel(sel).also { it.stream = v }
                    }
                }
            }

            if (hintText != null) {
                DhForm.Note(hintText!!, color = if (hintOk) c.ok else c.err)
            }

            // —— 底栏 ——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DhForm.OutlineChip("取消") { ProviderNav.back(act) }
                Spacer(Modifier.weight(1f))
                DhForm.PrimaryChip("保存") {
                    val r = save(act, src, pid, name, sel, enabled)
                    if (r == null) {
                        hintOk = true
                        hintText = null
                    } else {
                        hintOk = false
                        hintText = r
                    }
                }
            }
        }
    }

    /** 一排等宽可点格子（类型 / 模态）。 */
    @Composable
    private fun ChipRow(
        labels: Array<String>,
        selected: Array<Boolean>,
        dimmed: Array<Boolean>? = null,
        onPick: (Int) -> Unit
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            for (i in labels.indices) {
                DhForm.SelectChip(
                    text = labels[i],
                    selected = selected[i],
                    dimmed = dimmed != null && dimmed[i],
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(i) }
                )
                if (i < labels.size - 1) {
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }

    /** 一行能力开关（EMBEDDING 时整行置灰，与 View 版 `alpha = 0.45f` 同口径）。 */
    @Composable
    private fun CapRow(name: String, desc: String?, on: Boolean, dimmed: Boolean, onToggle: (Boolean) -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
                if (desc != null && desc.isNotEmpty()) {
                    Text(
                        text = desc,
                        modifier = Modifier.padding(top = 2.dp),
                        color = c.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
            DhKit.Switch(checked = on, onCheckedChange = onToggle)
        }
    }

    /* ------------------------------ 规则层桥接 ------------------------------ */

    /** 深拷贝一份选择状态（Sel 的字段是可变 var，原地改不会触发重组）。 */
    private fun copySel(s: ModelEditKit.Sel): ModelEditKit.Sel {
        val d = ModelEditKit.Sel()
        d.kind = s.kind
        System.arraycopy(s.`in`, 0, d.`in`, 0, d.`in`.size)
        System.arraycopy(s.out, 0, d.out, 0, d.out.size)
        d.vision = s.vision
        d.tool = s.tool
        d.reasoning = s.reasoning
        d.stream = s.stream
        return d
    }

    /** 钳制成合法组合（与 View 版 `refresh` 里那一次 normalize 同义）。 */
    private fun normalize(s: ModelEditKit.Sel): ModelEditKit.Sel {
        ModelEditKit.normalize(s)
        return s
    }

    /** 保存。返回 null = 成功；非 null = 要显示的错误文案。 */
    private fun save(
        act: Activity,
        src: AiModel?,
        pid: String,
        name: String,
        sel: ModelEditKit.Sel,
        enabled: Boolean
    ): String? {
        val n = name.trim()
        if (n.isEmpty()) {
            return "请填写模型名称"
        }
        if (pid.isEmpty() || ProviderStore.findProvider(act, pid) == null) {
            return "这个模型没有归属的供应商，请从供应商的模型列表进入"
        }
        if (ModelStore.modelExists(act, pid, n, src?.id ?: "")) {
            return "本供应商下已有同名模型，换一个名字"
        }
        val m = ModelEditKit.toModel(sel, src?.id ?: "", pid, n, enabled)
        ModelStore.saveModel(act, m)
        ProviderNav.back(act)
        return null
    }
}