package com.dollhouse.app.ui.chat

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.ChatHistoryStore
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PersonaChatStateStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.doll.DollPersonaPage
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.doll.DollTalkPage
import com.dollhouse.app.ui.doll.ModelManagePage
import com.dollhouse.app.ui.doll.ReplaceModelPage
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.AppearancePage
import com.dollhouse.app.ui.settings.MemPage
import com.dollhouse.app.ui.settings.MemSettingsPage
import com.dollhouse.app.ui.settings.MyProfilePage
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ai.TokenStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 【职责】按人偶（personaId）聚合的「好友资料」页：身份区 + 六组真实入口。
 *
 * 【为什么入口页独立成页】聊天页顶栏的头像 / 名字原本只承担「显示身份」，
 *   而人格、模型、记忆、外观、列表状态、数据操作分散在六七个页面里。
 *   把它们按同一个 personaId 收敛到一页后，聊天里点一下头像是「看这个人」，
 *   而不是每次都要先退出、再去设置里找对应级别。
 *
 * 【不伪造任何数据】身份区来自 [DollStore] 与 [DollProfile]；
 *   每组入口都直接调用既有页面（[DollPersonaPage] / [MemPage] / [AppearancePage] …）；
 *   列表开关只读写 [PersonaChatStateStore]，不与聊天记录或角色记忆混存。
 *
 * 【列表状态为什么按 personaId 存】会话 id 是 `d_<dollId>` 的投影，
 *   而置顶 / 归档 / 免打扰是「这个人」的状态，不是「这条会话 id」的状态；
 *   换设备、重建会话时 personaId 不变，状态才不会丢。
 */
object FriendProfilePage {

    /** 页面 key 前缀：`chat:friend:<dollId>`。必须与 [com.dollhouse.app.ui.nav.NavKeyCodec] 同值。 */
    const val PREFIX = "chat:friend:"

    /** 头像解码目标边长（px）。 */
    private const val AVATAR_PX = 192

    @JvmStatic
    fun open(act: MainActivity, personaId: String) {
        if (personaId.isEmpty()) {
            return
        }
        // 人格页 / 记忆页改完设定后退回，本页的简介与入口状态要跟着重算。
        PageStack.onLeave(PREFIX + personaId) { DollState.bump() }
        PageStack.push(act, PREFIX + personaId) { buildPage(act, personaId) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity, personaId: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, personaId) }
    }

    @Composable
    private fun Content(act: MainActivity, personaId: String) {
        // 【重读信号】DollStore / DollProfile 不是 Compose 状态；
        //   子页改完数据会 bump（见 open 里的 onLeave），本页据此重算身份区。
        var tick by remember(personaId) { mutableStateOf(0) }
        val rev = DollState.rev

        val doll = remember(personaId, rev) { DollStore.byId(act, personaId) }
        val name = remember(personaId, rev) {
            doll?.name?.takeIf { it.isNotBlank() } ?: "未命名好友"
        }
        val bio = remember(personaId, rev, tick) { readBio(act, personaId) }

        var avatar by remember(personaId, rev) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(personaId, rev) {
            avatar = withContext(Dispatchers.IO) {
                runCatching { DollStore.coverOf(act, doll, AVATAR_PX) }.getOrNull()
            }
        }

        // 【列表行为】唯一真源是 PersonaChatStateStore；本地副本只负责即时回显开关。
        var chatState by remember(personaId) {
            mutableStateOf(PersonaChatStateStore.get(act, personaId))
        }
        fun saveState(next: PersonaChatStateStore.State) {
            chatState = next
            PersonaChatStateStore.set(act, personaId, next)
        }

        var confirmClear by remember(personaId) { mutableStateOf(false) }
        var confirmDelete by remember(personaId) { mutableStateOf(false) }
        var notice by remember(personaId) { mutableStateOf<String?>(null) }

        DhKit.Page(
            title = "好友资料",
            sub = name,
            onBack = { PageStack.pop(act) }
        ) {
            Header(name = name, bio = bio, avatar = avatar)

            /* ==================== 人格 ==================== */
            DhKit.Card(collapsibleTitle = "人格", titleIcon = Icons.IC_SIGNATURE) {
                DhForm.Note("她是谁、怎么说话、开场说什么，都在这里。")
                DhKit.ActionRow(
                    label = "角色设定",
                    iconRes = Icons.IC_BOOK_HEART,
                    desc = "性格 / 世界观 / 说话风格 / 开场白"
                ) { DollPersonaPage.open(act, personaId) }
                DhKit.ActionRow(
                    label = "交谈内容",
                    iconRes = Icons.IC_MESSAGE_SQUARE,
                    desc = "她随机冒出的台词，一条一句"
                ) { DollTalkPage.open(act, personaId) }
            }

            /* ==================== 模型 ==================== */
            DhKit.Card(collapsibleTitle = "模型", titleIcon = Icons.IC_BOT) {
                DhForm.Note("只影响这一个角色长什么样、用哪个模型回应。")
                DhKit.ActionRow(
                    label = "模型与生成参数",
                    iconRes = Icons.IC_BOT,
                    desc = "独立供应商、聊天模型、思考与采样参数"
                ) { FriendModelConfigPage.open(act, personaId) }
                DhKit.ActionRow(
                    label = "模型管理",
                    iconRes = Icons.IC_LAYERS,
                    desc = "查看当前模型、替换或导入素材"
                ) { ModelManagePage.open(act, personaId) }
                DhKit.ActionRow(
                    label = "更换模型",
                    iconRes = Icons.IC_REFRESH,
                    desc = "替换视觉模型；人格与记忆不变"
                ) { ReplaceModelPage.open(act, personaId) }
                DhKit.ActionRow(
                    label = "提供商与模型列表",
                    iconRes = Icons.IC_SERVER,
                    desc = "接口地址、密钥与可用模型"
                ) { ProviderNav.open(act) }
            }

            /* ==================== 记忆 ==================== */
            DhKit.Card(collapsibleTitle = "记忆", titleIcon = Icons.IC_BRAIN) {
                DhForm.Note("记忆按角色分库，与其它人偶完全隔离。")
                DhKit.ActionRow(
                    label = "记忆库",
                    iconRes = Icons.IC_BRAIN,
                    desc = "她记住的条目、来源与关联"
                ) { MemPage.open(act, personaId) }
                DhKit.ActionRow(
                    label = "记忆库设置",
                    iconRes = Icons.IC_SLIDERS_HORIZONTAL,
                    desc = "抽取与检索策略（全局）"
                ) { MemSettingsPage.open(act) }
            }

            /* ==================== 外观 ==================== */
            DhKit.Card(collapsibleTitle = "外观", titleIcon = Icons.IC_PALETTE) {
                DhKit.ActionRow(
                    label = "外观设置",
                    iconRes = Icons.IC_PALETTE,
                    desc = "主题 · 背景 · 聊天气泡 · 弹窗"
                ) { AppearancePage.open(act) }
                DhKit.ActionRow(
                    label = "我的资料",
                    iconRes = Icons.IC_CIRCLE_USER,
                    desc = "我自己的头像与昵称"
                ) { MyProfilePage.open(act) }
            }

            /* ==================== 列表行为 ==================== */
            DhKit.Card(collapsibleTitle = "列表行为", titleIcon = Icons.IC_LIST_CHECKS) {
                DhForm.Note("只影响聊天列表里的展示，不改聊天记录。")
                DhKit.SwitchRow(
                    name = "置顶聊天",
                    iconRes = Icons.IC_PIN,
                    desc = "让这条对话排在列表最前",
                    checked = chatState.pinned
                ) { on -> saveState(chatState.copy(pinned = on)) }
                DhKit.SwitchRow(
                    name = "归档",
                    iconRes = Icons.IC_LAYERS,
                    desc = "收进归档，不占用主列表",
                    checked = chatState.archived
                ) { on -> saveState(chatState.copy(archived = on)) }
                DhKit.SwitchRow(
                    name = "免打扰",
                    iconRes = Icons.IC_BELL,
                    desc = "新消息不再提醒，但仍计数",
                    checked = chatState.muted
                ) { on -> saveState(chatState.copy(muted = on)) }
                DhKit.ActionRow(
                    label = "标记为已读",
                    iconRes = Icons.IC_CHECK,
                    desc = "清掉列表上的未读角标",
                    trailing = if (chatState.unread > 0) chatState.unread.toString() + " 条" else "已读",
                    enabled = chatState.unread > 0
                ) { saveState(chatState.copy(unread = 0)) }
            }

            /* ==================== 数据管理 ==================== */
            DhKit.Card(collapsibleTitle = "数据管理", titleIcon = Icons.IC_LAYERS) {
                DhForm.Note("聊天与角色、记忆分开存放：清理聊天不会动她的人设和记忆。")
                DhKit.ActionRow(
                    label = "清空聊天记录",
                    iconRes = Icons.IC_ERASER,
                    desc = "消息归零，角色与记忆保留"
                ) { confirmClear = true }
                DhKit.ActionRow(
                    label = "删除聊天",
                    iconRes = Icons.IC_TRASH,
                    desc = "删除这条对话；不会删除角色记忆"
                ) { confirmDelete = true }
                DhKit.ActionRow(
                    label = "管理人偶",
                    iconRes = Icons.IC_USERS,
                    desc = "封面、动作、启停与删除角色"
                ) { DollListScreen.open(act) }
                DhKit.ActionRow(
                    label = "Token 统计",
                    iconRes = Icons.IC_GAUGE,
                    desc = "查看模型使用量"
                ) { TokenStat.open(act) }
                notice?.let { DhForm.Note(it) }
            }

            Spacer(Modifier.height(DhKit.GAP_CARD.dp))
        }

        if (confirmClear) {
            DhForm.Alert(
                title = "清空聊天记录",
                onDismiss = { confirmClear = false },
                posText = "清空",
                onPos = {
                    clearChat(act, personaId)
                    notice = "已清空聊天记录；角色人设与记忆未受影响。"
                    tick++
                },
                negText = "取消"
            ) {
                DhForm.Note("只清空这条对话的消息，角色设定、记忆库都不受影响。此操作不可撤销。")
            }
        }

        if (confirmDelete) {
            DhForm.Alert(
                title = "删除聊天",
                onDismiss = { confirmDelete = false },
                posText = "删除",
                onPos = {
                    deleteChat(act, personaId)
                    notice = "已删除这条聊天；角色与记忆仍在，重新进入即可开始新对话。"
                    tick++
                },
                negText = "取消"
            ) {
                DhForm.Note("会删除这条对话的聊天记录，但**不会**删除角色、人格设定与长期记忆。")
            }
        }
    }

    /** 身份区简介：优先用结构化设定的「一句话简介」，否则取性格描述首句。 */
    private fun readBio(ctx: android.content.Context, personaId: String): String {
        val brief = DollProfile.spec(ctx, personaId).brief.trim()
        if (brief.isNotEmpty()) {
            return brief
        }
        val firstLine = DollProfile.persona(ctx, personaId)
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return "还没有写简介，点「角色设定」补一句吧"
        return if (firstLine.length > 80) firstLine.take(80) + "…" else firstLine
    }

    /** 清空消息，保留会话清单与绑定关系（与聊天页「清空当前对话」同一条数据路径）。 */
    private fun clearChat(act: MainActivity, personaId: String) {
        val arr = ChatSessions.forDoll(act, personaId)
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { ChatSessions.clearMessages(act, it) }
        ChatHistoryStore.ChatListSignal.bump()
        ChatState.bumpList()
    }

    /**
     * 删除这条聊天：走会话层的删除（清清单 + 历史 + 预览），
     * 再把人偶绑定会话补回来 —— 角色数据与 [com.dollhouse.app.data.Memory] 全程不碰。
     */
    private fun deleteChat(act: MainActivity, personaId: String) {
        val arr = ChatSessions.forDoll(act, personaId)
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { ChatSessions.delete(act, it) }
        // 之前若被「从列表移除」隐藏过，删除后重新开始对话时不应继续隐身。
        PetPrefs.setConvHidden(act, ChatSessions.idForDoll(personaId), false)
        ChatSessions.ensureForDoll(act, personaId)
        ChatHistoryStore.ChatListSignal.bump()
        ChatState.bumpList()
    }

    /** 身份区：头像 + 昵称 + 简介。 */
    @Composable
    private fun Header(name: String, bio: String, avatar: Bitmap?) {
        val c = DhTokens.colors
        DhKit.Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(Design.SZ_XL.dp * 2)
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.soft),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatar != null && !avatar.isRecycled) {
                        Image(
                            bitmap = avatar.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        DhKit.Icon(Icons.IC_CIRCLE_USER, Design.SZ_XL, c.sub)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        color = c.title,
                        fontSize = 20.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = bio,
                        color = c.sub,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
