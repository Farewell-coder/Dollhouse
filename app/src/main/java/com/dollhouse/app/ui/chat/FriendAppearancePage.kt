package com.dollhouse.app.ui.chat

import android.view.View
import androidx.compose.runtime.Composable
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.doll.DollLookTab

/** 好友级聊天外观，直接复用现有人偶背景与助手气泡配置，不写全局外观。 */
object FriendAppearancePage {
    const val PREFIX = "chat:appearance:"
    @JvmStatic fun open(act: MainActivity, personaId: String) = PageStack.push(act, PREFIX + personaId) { buildPage(act, personaId) }
    @JvmStatic fun buildPage(act: MainActivity, personaId: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, personaId) }
    }
    @Composable private fun Content(act: MainActivity, personaId: String) {
        DhKit.Page(title = "聊天外观", sub = "仅影响当前好友", onBack = { PageStack.pop(act) }) {
            DollLookTab.Content(act, personaId)
        }
    }
}