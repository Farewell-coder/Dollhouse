package com.dollhouse.app.ui.chat

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.ChatDraftStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.Icons
import org.json.JSONObject

/** 同一好友的会话管理；好友身份、人格、记忆不随会话复制。 */
object ConversationHistoryPage {
    const val PREFIX = "chat:conversations:"
    @JvmStatic fun open(act: MainActivity, personaId: String) {
        if (personaId.isEmpty()) return
        PageStack.push(act, PREFIX + personaId) { buildPage(act, personaId) }
    }
    @JvmStatic fun buildPage(act: MainActivity, personaId: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, personaId) }
    }
    @Composable private fun Content(act: MainActivity, personaId: String) {
        val c = DhTokens.colors
        var rev by remember { mutableStateOf(0) }
        val entries: List<Triple<String, String, Long>> = remember(rev) {
            val arr = ChatSessions.forDoll(act, personaId)
            (0 until arr.length()).mapNotNull { index ->
                val item = arr.optJSONObject(index) ?: return@mapNotNull null
                Triple(item.optString("id"), item.optString("title", "新的对话"), item.optLong("updated", 0L))
            }
        }
        var renaming by remember { mutableStateOf<String?>(null) }
        var draftName by remember { mutableStateOf("") }
        var deleting by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                DhKit.IconButton(Icons.IC_ARROW_LEFT, 20f, c.title) { PageStack.pop(act) }
                Text("历史会话", color = c.title, fontSize = 20.sp)
            }
            DhKit.OutlineChip(text = "新建会话", iconRes = Icons.IC_PLUS) {
                ChatSessions.createForDoll(act, personaId)
                rev++
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.first }) { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        DhKit.ActionRow(entry.second, modifier = Modifier, iconRes = Icons.IC_MESSAGE_SQUARE, onClick = {
                            ChatSessions.switchTo(act, entry.first)
                            ChatListScreen.openConv(act, entry.first)
                        }, desc = if (ChatDraftStore.load(act, personaId, entry.first).text.isEmpty()) "独立消息历史" else "存有草稿")
                        Row {
                            DhKit.OutlineChip("重命名") { renaming = entry.first; draftName = entry.second }
                            Spacer(Modifier.width(8.dp))
                            DhKit.OutlineChip("删除") { deleting = entry.first }
                        }
                    }
                }
            }
        }
        if (renaming != null) DhForm.Alert("重命名会话", { renaming = null }, "保存", {
            ChatSessions.rename(act, renaming!!, draftName); renaming = null; rev++
        }, "取消") { DhForm.Input(draftName, { draftName = it }, hint = "会话名称", keyboard = androidx.compose.ui.text.input.KeyboardType.Text) }
        if (deleting != null) DhForm.Alert("删除这段会话？", { deleting = null }, "删除", {
            ChatSessions.delete(act, deleting!!); ChatDraftStore.clear(act, personaId, deleting!!); deleting = null; rev++
        }, "取消") { Text("只删除此会话的消息，不删除好友、人格、记忆或其他会话。", color = c.sub, fontSize = 14.sp) }
    }
}
