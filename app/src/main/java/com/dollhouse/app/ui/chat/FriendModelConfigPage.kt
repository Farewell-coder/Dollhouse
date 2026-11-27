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
import androidx.compose.ui.res.painterResource
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.qzx.QzxColors
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
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
        // 【QZX 迁移】旧版这一页**没有卡片**：行直接铺在页面底上。所以用
        //   `section(flat = true)`（不画卡片底）+ `autoDivider = false`（旧版也没有分隔线），
        //   保持原来的"裸行"观感，而不是给它凭空加一张卡片。
        QzxPage(
            title = "模型与生成",
            backIcon = painterResource(Icons.IC_ARROW_LEFT),
            onBack = { PageStack.popIfTopStartsWith(act, PREFIX) },
        ) {
            QzxList(autoDivider = false, spotTitle = { null }) {
                section(id = "main", flat = true) {
                    note("未开启独立模型时继承全局；Provider 凭据只按引用使用。")

                    switch(
                        "独立模型",
                        checked = overrideModel,
                        iconRes = Icons.IC_BOT
                    ).desc("只修改当前好友，不改变其他好友")
                        .onChecked { overrideModel = it }

                    if (overrideModel) {
                        providers.forEach { p ->
                            item(
                                (if (p.id == providerId) "● " else "") + p.name,
                                iconRes = Icons.IC_SERVER
                            ).desc("选择供应商")
                                .arrow()
                                .onClick {
                                    providerId = p.id
                                    modelId = ""
                                }
                        }
                        models.forEach { m ->
                            item(
                                (if (m.id == modelId) "● " else "") + m.displayName,
                                iconRes = Icons.IC_BOT
                            ).desc("聊天模型")
                                .arrow()
                                .onClick { modelId = m.id }
                        }
                    }

                    item(
                        "识图模型：" + (visionModels.firstOrNull { it.id == visionModelId }
                            ?.displayName ?: "跟随全局"),
                        iconRes = Icons.IC_IMAGE
                    ).desc("仅影响当前好友当前会话")
                        .arrow()
                        .onClick { visionModelId = "" }

                    visionModels.forEach { m ->
                        item(
                            (if (m.id == visionModelId) "● " else "") + m.displayName,
                            iconRes = Icons.IC_IMAGE
                        ).desc("设为当前会话识图模型")
                            .arrow()
                            .onClick { visionModelId = m.id }
                    }

                    input("思考档位（0–5）", think, iconRes = Icons.IC_BOT)
                        .onValueChange { think = it }
                    input("温度（0–2）", temp, iconRes = Icons.IC_BOT)
                        .onValueChange { temp = it }
                    input("Top P（0–1）", topP, iconRes = Icons.IC_BOT)
                        .onValueChange { topP = it }
                    input("最大输出 Token", maxTokens, iconRes = Icons.IC_BOT)
                        .onValueChange { maxTokens = it }

                    switch(
                        "流式输出",
                        checked = stream,
                        iconRes = Icons.IC_BOT
                    ).desc("逐段显示正文")
                        .onChecked { stream = it }

                    // 【压图可读性】错误提示走 `note(color = danger)` —— 与旧 `DhForm.Note(…, c.err)`
                    //   同一个语义（语义色），且新系统的 note 自带系统内边距，压在背景图上仍可读。
                    error?.let { note(it, color = QzxColors.danger) }

                    item("保存当前好友配置", iconRes = Icons.IC_CHECK)
                        .arrow()
                        .onClick {
                            val cfg = PersonaChatConfig()
                            if (overrideModel) {
                                cfg.providerId = providerId
                                cfg.modelId = modelId
                            }
                            cfg.thinkLevel = think.toIntOrNull()?.coerceIn(0, 5)
                            cfg.temperature = temp.toDoubleOrNull()?.coerceIn(0.0, 2.0)
                            cfg.topP = topP.toDoubleOrNull()?.coerceIn(0.0, 1.0)
                            cfg.maxTokens = maxTokens.toIntOrNull()?.takeIf { it > 0 }
                            cfg.streamOutput = stream
                            cfg.visionModelId = visionModelId
                            if (cfg.thinkLevel == null || cfg.temperature == null ||
                                cfg.topP == null || cfg.maxTokens == null
                            ) {
                                error = "请填写有效的生成参数"
                            } else {
                                PersonaChatConfigStore.save(
                                    act, personaId, conversationId, cfg
                                )
                                PageStack.popIfTopStartsWith(act, PREFIX)
                            }
                        }

                    item("全部恢复全局继承", iconRes = Icons.IC_REFRESH)
                        .arrow()
                        .onClick {
                            PersonaChatConfigStore.clear(act, personaId, conversationId)
                            PageStack.popIfTopStartsWith(act, PREFIX)
                        }
                }
            }
        }
    }
}