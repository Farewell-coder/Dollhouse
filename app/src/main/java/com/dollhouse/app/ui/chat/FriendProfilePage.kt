package com.dollhouse.app.ui.chat

import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.painterResource
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.data.ChatHistoryStore
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PersonaChatStateStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.doll.DollPersonaPage
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.doll.DollTalkPage
import com.dollhouse.app.ui.doll.ModelManagePage
import com.dollhouse.app.ui.doll.ReplaceModelPage
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.qzx.QzxDialog
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
import com.dollhouse.app.ui.qzx.preview
import com.dollhouse.app.ui.settings.AppearancePage
import com.dollhouse.app.ui.settings.MemPage
import com.dollhouse.app.ui.settings.MemSettingsPage
import com.dollhouse.app.ui.settings.MyProfilePage
import com.dollhouse.app.ui.settings.SettingsSpots
import com.dollhouse.app.ui.theme.Icons
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
 *
 * 【2026-11·迁到 `ui.qzx`】整页由旧 `DhKit.Page` + 7 张 `DhKit.Card` + 15 行
 *   `ActionRow` + 3 行 `SwitchRow` 改为 `QzxPage` + `QzxList` 的声明式写法：
 *   · 行高 / 内边距 / 描边 / 分隔线缩进 / 卡间距全部由系统算，本页**零布局字面量**；
 *   · 身份区（头像 + 昵称 + 简介）从手绘 `Row` 改为数据式的 `preview(…)` 行型
 *     （圆形裁剪、预览边长、按钮行高由系统给）；
 *   · 确认弹窗从 `DhForm.Alert` 改为 `QzxDialog`（破坏性操作走 `confirmDestructive`）。
 *   入口数量、当前值、导航调用、条件渲染（未读数 → 值 + 可点性）逐条保持原样。
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
        // 【Painter 只建一次】`preview` 行型收的是 `Painter?`（新系统不持有位图解码），
        //   remember 住避免每次重组都包一层 BitmapPainter。
        val avatarPainter = remember(avatar) {
            val bmp = avatar
            if (bmp != null && !bmp.isRecycled) BitmapPainter(bmp.asImageBitmap()) else null
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

        QzxPage(
            title = "好友资料",
            // 【当前值原样保留】旧页 `DhKit.Page(sub = name)` 的副标题就是名字。
            subtitle = name,
            onBack = { PageStack.popIfTopStartsWith(act, PREFIX) },
            // 新系统的返回键由调用方给图标（系统不持有旧 drawable）；不给就**不画**返回键，
            // 而旧 `DhKit.Page` 自带返回箭头 —— 这里显式补上，避免「进得去出不来」。
            backIconRes = Icons.IC_ARROW_LEFT,
        ) {
            QzxList(spotTitle = { SettingsSpots.find(it)?.title }) {

                /* ==================== 身份区 ==================== */
                // 旧版是手绘 Row（Box + clip + background + 两段 Text）。
                // 新版只声明「图 + 标题 + 副标题」，圆形裁剪与尺寸归系统。
                section(id = "identity") {
                    preview(
                        image = avatarPainter,
                        fallbackIconRes = Icons.IC_CIRCLE_USER,
                        title = name,
                        subtitle = bio,
                    )
                }

                /* ==================== 人格 ==================== */
                section(
                    id = "persona",
                    title = "人格",
                    titleIconRes = Icons.IC_SIGNATURE,
                    collapsible = true
                ) {
                    note("她是谁、怎么说话、开场说什么，都在这里。")
                    item(
                        "角色设定",
                        iconRes = Icons.IC_BOOK_HEART
                    ).desc("性格 / 世界观 / 说话风格 / 开场白")
                        .arrow()
                        .onClick { DollPersonaPage.open(act, personaId) }
                    item(
                        "交谈内容",
                        iconRes = Icons.IC_MESSAGE_SQUARE
                    ).desc("她随机冒出的台词，一条一句")
                        .arrow()
                        .onClick { DollTalkPage.open(act, personaId) }
                }

                /* ==================== 模型 ==================== */
                section(
                    id = "model",
                    title = "模型",
                    titleIconRes = Icons.IC_BOT,
                    collapsible = true
                ) {
                    note("只影响这一个人偶长什么样、用哪个模型回应。")
                    item(
                        "模型与生成参数",
                        iconRes = Icons.IC_BOT
                    ).desc("独立供应商、聊天模型、思考与采样参数")
                        .arrow()
                        .onClick {
                            FriendModelConfigPage.open(act, personaId, ChatSessions.currentId(act))
                        }
                    item(
                        "模型管理",
                        iconRes = Icons.IC_LAYERS
                    ).desc("查看当前模型、替换或导入素材")
                        .arrow()
                        .onClick { ModelManagePage.open(act, personaId) }
                    item(
                        "更换模型",
                        iconRes = Icons.IC_REFRESH
                    ).desc("替换视觉模型；人格与记忆不变")
                        .arrow()
                        .onClick { ReplaceModelPage.open(act, personaId) }
                    item(
                        "提供商与模型列表",
                        iconRes = Icons.IC_SERVER
                    ).desc("接口地址、密钥与可用模型")
                        .arrow()
                        .onClick { ProviderNav.open(act) }
                }

                /* ==================== 记忆 ==================== */
                section(
                    id = "memory",
                    title = "记忆",
                    titleIconRes = Icons.IC_BRAIN,
                    collapsible = true
                ) {
                    note("记忆按人偶分库，与其它人偶完全隔离。")
                    item(
                        "记忆库",
                        iconRes = Icons.IC_BRAIN
                    ).desc("她记住的条目、来源与关联")
                        .arrow()
                        .onClick { MemPage.open(act, personaId) }
                    item(
                        "记忆库设置",
                        iconRes = Icons.IC_SLIDERS_HORIZONTAL
                    ).desc("抽取与检索策略（全局）")
                        .arrow()
                        .onClick { MemSettingsPage.open(act) }
                }

                /* ==================== 外观 ==================== */
                section(
                    id = "appearance",
                    title = "外观",
                    titleIconRes = Icons.IC_PALETTE,
                    collapsible = true
                ) {
                    item(
                        "外观设置",
                        iconRes = Icons.IC_PALETTE
                    ).desc("当前好友的聊天背景与她发出的气泡")
                        .arrow()
                        .onClick { FriendAppearancePage.open(act, personaId) }
                    item(
                        "我的资料",
                        iconRes = Icons.IC_CIRCLE_USER
                    ).desc("我自己的头像与昵称")
                        .arrow()
                        .onClick { MyProfilePage.open(act) }
                }

                /* ==================== 列表行为 ==================== */
                section(
                    id = "list",
                    title = "列表行为",
                    titleIconRes = Icons.IC_LIST_CHECKS,
                    collapsible = true
                ) {
                    note("只影响聊天列表里的展示，不改聊天记录。")
                    switch(
                        "置顶聊天",
                        checked = chatState.pinned,
                        iconRes = Icons.IC_PIN
                    ).desc("让这条对话排在列表最前")
                        .onChecked { on -> saveState(chatState.copy(pinned = on)) }
                    switch(
                        "归档",
                        checked = chatState.archived,
                        iconRes = Icons.IC_LAYERS
                    ).desc("收进归档，不占用主列表")
                        .onChecked { on -> saveState(chatState.copy(archived = on)) }
                    switch(
                        "免打扰",
                        checked = chatState.muted,
                        iconRes = Icons.IC_BELL
                    ).desc("新消息不再提醒，但仍计数")
                        .onChecked { on -> saveState(chatState.copy(muted = on)) }
                    // 【条件渲染保留】未读数 > 0 时值显示「N 条」且可点，否则显示「已读」并置灰。
                    item(
                        "标记为已读",
                        value = if (chatState.unread > 0) chatState.unread.toString() + " 条" else "已读",
                        iconRes = Icons.IC_CHECK
                    ).desc("清掉列表上的未读角标")
                        .enabled(chatState.unread > 0)
                        .onClick { saveState(chatState.copy(unread = 0)) }
                }

                /* ==================== 数据管理 ==================== */
                section(
                    id = "data",
                    title = "数据管理",
                    titleIconRes = Icons.IC_LAYERS,
                    collapsible = true
                ) {
                    note("聊天与人偶、记忆分开存放：清理聊天不会动她的人设和记忆。")
                    item(
                        "清空全部会话",
                        iconRes = Icons.IC_ERASER
                    ).desc("该好友全部会话的消息归零，人偶与记忆保留")
                        .onClick { confirmClear = true }
                    item(
                        "删除聊天",
                        iconRes = Icons.IC_TRASH
                    ).desc("删除这条对话；不会删除人偶记忆")
                        .onClick { confirmDelete = true }
                    item(
                        "管理人偶",
                        iconRes = Icons.IC_USERS
                        // 【文案统一】原来 label 叫「人偶」、desc 同一句里叫「角色」——
                        //   同一个控件里两个词，用户会以为是两种东西。
                    ).desc("封面、动作、启停与删除人偶")
                        .arrow()
                        .onClick { DollListScreen.open(act) }
                    item(
                        "Token 统计",
                        iconRes = Icons.IC_GAUGE
                    ).desc("查看模型使用量")
                        .arrow()
                        .onClick { TokenStat.open(act) }
                    // 【结果提示】清空 / 删除完成后的一句话反馈（旧版同一个位置）。
                    notice?.let { note(it) }
                }
            }
        }

        QzxDialog(
            visible = confirmClear,
            title = "清空全部会话",
            message = "会清空该好友所有会话的消息，角色设定、记忆库都不受影响。此操作不可撤销。",
            confirmText = "清空",
            dismissText = "取消",
            // 不可撤销的清空：确认键走危险色，且不默认聚焦（防误触）。
            confirmDestructive = true,
            onConfirm = {
                clearChat(act, personaId)
                notice = "已清空该好友全部会话；人偶的人设与记忆未受影响。"
                tick++
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )

        QzxDialog(
            visible = confirmDelete,
            title = "删除聊天",
            // 旧文案里的 `**` 是写给人看的强调记号（旧 `DhForm.Alert` 也原样画出来，
            // 屏幕上就是一对星号）。新系统只收纯文本，这里去掉记号、字句未改。
            message = "会删除这条对话的聊天记录，但不会删除人偶、人格设定与长期记忆。",
            confirmText = "删除",
            dismissText = "取消",
            confirmDestructive = true,
            onConfirm = {
                deleteChat(act, personaId)
                notice = "已删除这条聊天；人偶与记忆仍在，重新进入即可开始新对话。"
                tick++
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
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
}
