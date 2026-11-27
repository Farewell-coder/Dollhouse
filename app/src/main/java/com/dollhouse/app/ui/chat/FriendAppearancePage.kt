package com.dollhouse.app.ui.chat

import android.view.View
import androidx.compose.runtime.Composable
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.doll.DollLookTab
import com.dollhouse.app.ui.qzx.QzxPage
import com.dollhouse.app.ui.qzx.QzxPageScroll
import com.dollhouse.app.ui.theme.Icons

/** 好友级聊天外观，直接复用现有人偶背景与助手气泡配置，不写全局外观。 */
object FriendAppearancePage {
    const val PREFIX = "chat:appearance:"
    @JvmStatic fun open(act: MainActivity, personaId: String) = PageStack.push(act, PREFIX + personaId) { buildPage(act, personaId) }
    @JvmStatic fun buildPage(act: MainActivity, personaId: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, personaId) }
    }
    @Composable private fun Content(act: MainActivity, personaId: String) {
        // 与旧 DhKit.Page 等价：默认 scrollable = true ⇒ QzxPageScroll.Scroll（竖向滚动）。
        // 内容 DollLookTab.Content 自带什么滚动由它决定，本页只负责页面骨架。
        QzxPage(
            title = "聊天外观",
            subtitle = "仅影响当前好友",
            onBack = { PageStack.popIfTopStartsWith(act, PREFIX) },
            backIcon = Icons.IC_ARROW_LEFT,
            scroll = QzxPageScroll.Scroll,
        ) {
            DollLookTab.Content(act, personaId)
        }
    }
}