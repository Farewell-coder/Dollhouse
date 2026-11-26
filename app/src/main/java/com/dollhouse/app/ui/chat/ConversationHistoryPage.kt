package com.dollhouse.app.ui.chat

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.dollhouse.app.ui.design.DhDesign
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
                Triple(item.optString("id"), item.optString("title", "新的会话"), item.optLong("updated", 0L))
            }
        }
        var renaming by remember { mutableStateOf<String?>(null) }
        var draftName by remember { mutableStateOf("") }
        var deleting by remember { mutableStateOf<String?>(null) }
        // 【布局统一】原来这里手写「返回箭头 + 20sp 标题」的顶栏 + 自带 16dp 外距，
        //   与其它二级页（ProviderEdit / Mem / About …）的顶栏不是同一段代码，
        //   页与页之间会出现标题基线、左右边距的细微不同。
        //   改走 DhKit.Page：title/onBack 由脚手架统一绘制，水平边距由 PAD_CARD 统一。
        //   scrollable = false 保持原语义 —— 内部已有 LazyColumn 自己滚。
        // 【大标题栏·补】列表 state 必须在 Page 之前声明才能当参数传（同 contentScroll 的作用域问题）。
        val listState = rememberLazyListState()
        DhKit.Page(
            title = "历史会话",
            onBack = { PageStack.popIfTopStartsWith(act, PREFIX) },
            scrollable = false,
            // 【大标题栏·补】会话列表可能很长，滚动时标题收起。
            adaptiveTitle = true,
            externalLazyScroll = listState
        ) {
            DhForm.Button(text = "新建会话", primary = false, leadingIcon = Icons.IC_PLUS) {
                ChatSessions.createForDoll(act, personaId)
                rev++
            }
            LazyColumn(
                // 显式持有 state：标题栏进度与列表滚动必须是同一份。
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DhDesign.Space.sm.dp)
            ) {
                items(entries, key = { it.first }, contentType = { "history" }) { entry ->
                    /* 【v0.2.8·P2-e 条目动画】本页有三种会改动集合的操作：新建会话、
                       删除会话、重命名（都走 `rev++` 触发重算）。此前它们会整列瞬间跳一下 ——
                       尤其是"删除"后下面所有行整体上移一格，视觉上像闪断。
                       `animateItem()` 由 LazyColumn 托管位移/淡入淡出，零依赖、无需手动状态。
                       【为什么包 Box 而不是给 Column 直接加】`animateItem()` 是 LazyItemScope
                       的成员扩展，作用在 item 的**根**上；包一层 Box 让根节点明确、且不影响
                       内部原有的 padding 语义。 */
                    Box(Modifier.animateItem()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = DhDesign.Space.xs.dp)) {
                            DhKit.ActionRow(entry.second, modifier = Modifier, iconRes = Icons.IC_MESSAGE_SQUARE, onClick = {
                                ChatSessions.switchTo(act, entry.first)
                                ChatListScreen.openConv(act, entry.first)
                            }, desc = if (ChatDraftStore.load(act, personaId, entry.first).text.isEmpty()) "独立消息历史" else "存有草稿")
                            Row {
                                DhForm.Button("重命名", primary = false) { renaming = entry.first; draftName = entry.second }
                                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                                DhForm.Button("删除", primary = false) { deleting = entry.first }
                            }
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
        }, "取消") { Text("只删除此会话的消息，不删除好友、人格、记忆或其他会话。", color = c.sub, fontSize = DhTokens.type.sub.fontSize, lineHeight = DhTokens.type.sub.lineHeight) }
    }
}
