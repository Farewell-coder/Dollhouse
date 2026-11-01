package com.dollhouse.app.ui.chat

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.PersonaChatConfig
import com.dollhouse.app.data.PersonaChatConfigStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.Icons

/** 好友生成参数页：显式覆盖与全局继承分离，不复制 Provider 凭据。 */
object FriendModelConfigPage {
    const val PREFIX = "chat:model-config:"
    @JvmStatic fun open(act: MainActivity, personaId: String, conversationId: String = ChatSessions.latestForDoll(act, personaId) ?: ChatSessions.ensureForDoll(act, personaId)) = PageStack.push(act, PREFIX + personaId + ":" + conversationId) { buildPage(act, personaId, conversationId) }
    @JvmStatic fun buildPage(act: MainActivity, personaId: String, conversationId: String = ChatSessions.latestForDoll(act, personaId) ?: ChatSessions.ensureForDoll(act, personaId)): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, personaId, conversationId) }
    }
    @Composable private fun Content(act: MainActivity, personaId: String, conversationId: String) {
        val c = DhTokens.colors
        // conversationId 由入口显式冻结，避免编辑期间切会话后保存到另一会话。
        val initial = remember { PersonaChatConfigStore.load(act, personaId, conversationId) }
        var overrideModel by remember { mutableStateOf(!initial.providerId.isNullOrEmpty() || !initial.modelId.isNullOrEmpty()) }
        var providerId by remember { mutableStateOf(initial.providerId ?: ProviderStore.current(act).providerId) }
        var modelId by remember { mutableStateOf(initial.modelId ?: ProviderStore.current(act).modelId) }
        var think by remember { mutableStateOf((initial.thinkLevel ?: PetPrefs.thinkLevel(act)).toString()) }
        var temp by remember { mutableStateOf((initial.temperature ?: PersonaChatConfig.DEFAULT_TEMPERATURE).toString()) }
        var topP by remember { mutableStateOf((initial.topP ?: PersonaChatConfig.DEFAULT_TOP_P).toString()) }
        var maxTokens by remember { mutableStateOf((initial.maxTokens ?: 0).toString()) }
        var stream by remember { mutableStateOf(initial.streamOutput ?: PetPrefs.streamOutput(act)) }
        var visionModelId by remember { mutableStateOf(initial.visionModelId ?: PetPrefs.visionModel(act)) }
        val visionModels = remember { ModelStore.models(act).filter { it.enabled && it.capVision } }
        var error by remember { mutableStateOf<String?>(null) }
        val providers = remember { ProviderStore.providers(act).filter { it.enabled } }
        val models = remember(providerId) { ModelStore.models(act).filter { it.providerId == providerId && it.enabled } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DhKit.IconButton(Icons.IC_ARROW_LEFT, 20f, c.title) { PageStack.pop(act) }
                Text("模型与生成", color = c.title, fontSize = 20.sp)
            }
            DhForm.Note("未开启独立模型时继承全局；Provider 凭据只按引用使用。")
            DhKit.SwitchRow(name = "独立模型", desc = "只修改当前好友，不改变其他好友", checked = overrideModel) { overrideModel = it }
            if (overrideModel) {
                providers.forEach { p -> DhKit.ActionRow((if (p.id == providerId) "● " else "") + p.name, desc = "选择供应商") { providerId = p.id; modelId = "" } }
                models.forEach { m -> DhKit.ActionRow((if (m.id == modelId) "● " else "") + m.displayName, desc = "聊天模型") { modelId = m.id } }
            }
            DhKit.ActionRow("识图模型：" + (visionModels.firstOrNull { it.id == visionModelId }?.displayName ?: "跟随全局"), iconRes = Icons.IC_IMAGE, desc = "仅影响当前好友当前会话") { visionModelId = "" }
            visionModels.forEach { m -> DhKit.ActionRow((if (m.id == visionModelId) "● " else "") + m.displayName, desc = "设为当前会话识图模型") { visionModelId = m.id } }
            DhForm.Input(think, { think = it }, label = "思考档位（0–5）", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            DhForm.Input(temp, { temp = it }, label = "温度（0–2）", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            DhForm.Input(topP, { topP = it }, label = "Top P（0–1）", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            DhForm.Input(maxTokens, { maxTokens = it }, label = "最大输出 Token", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            DhKit.SwitchRow(name = "流式输出", desc = "逐段显示正文", checked = stream) { stream = it }
            error?.let { Text(it, color = c.err) }
            DhKit.ActionRow("保存当前好友配置", iconRes = Icons.IC_CHECK) {
                val cfg = PersonaChatConfig()
                if (overrideModel) { cfg.providerId = providerId; cfg.modelId = modelId }
                cfg.thinkLevel = think.toIntOrNull()?.coerceIn(0, 5)
                cfg.temperature = temp.toDoubleOrNull()?.coerceIn(0.0, 2.0)
                cfg.topP = topP.toDoubleOrNull()?.coerceIn(0.0, 1.0)
                cfg.maxTokens = maxTokens.toIntOrNull()?.takeIf { it > 0 }
                cfg.streamOutput = stream
                cfg.visionModelId = visionModelId
                if (cfg.thinkLevel == null || cfg.temperature == null || cfg.topP == null || cfg.maxTokens == null) error = "请填写有效的生成参数"
                else { PersonaChatConfigStore.save(act, personaId, conversationId, cfg); PageStack.pop(act) }
            }
            DhKit.ActionRow("全部恢复全局继承", iconRes = Icons.IC_REFRESH) {
                PersonaChatConfigStore.clear(act, personaId, conversationId); PageStack.pop(act)
            }
        }
    }
}