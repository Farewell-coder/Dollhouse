package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】「交谈内容」子页：这只人偶在「交谈」机制下会冒出的台词，一条一句。
 *
 * 【规格要点】一条一句话、无字数限制；可编辑 / 增加 / 删除。
 *   所以每条都是一行输入框 + 一个删除键，底部一个「增加一条」长横条。
 *
 * 【为什么不做「保存」按钮】与编辑页其它子页同一约定：输入即时落盘。
 *   台词是碎片内容，用户加一条、改两个字就走，逼他再点一次保存纯属添堵。
 *   删除是破坏性操作，所以放在每行右侧、需要精确点到 —— 不会误触。
 *
 * 【为什么用 SnapshotStateList 而不是 mutableStateOf(MutableList)】后者对「原地增删」不敏感：
 *   `lines.add("")` 改的是同一个 list 实例，`mutableStateOf` 判定值没变、不触发重组，
 *   表现就是「点增加一条，什么也没发生」。`toMutableStateList()` 的每次增删改都会通知快照系统。
 *
 * 【存储】JSON 数组文本（见 [DollProfile.setTalkLines]）；空行自动丢弃，不占位。
 */
object DollTalkPage {
    /** 页面 key 前缀：`doll:talk:<dollId>`。 */
    const val PREFIX = "doll:talk:"

    @JvmStatic
    fun open(act: MainActivity, id: String) {
        PageStack.push(act, PREFIX + id) { buildPage(act, id) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity, id: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, id) }
    }

    @Composable
    private fun Content(act: MainActivity, id: String) {
        val c = DhTokens.colors
        // 【为什么要一份本地列表】直接每次读盘会让「正在输入的空行」在重组时被吞掉
        //   （空串不会被 setTalkLines 保存）。本地列表保住用户正在敲的那一行。
        val lines = remember(id) { DollProfile.talkLines(act, id).toMutableStateList() }
        // 删除后行数变化，需要一个信号让每行的 key 与「第 N 条」提示一起重算。
        var tick by remember(id) { mutableStateOf(0) }

        /** 把本地列表写回存储：过滤空行，保持顺序。 */
        fun flush() {
            DollProfile.setTalkLines(act, id, lines.filter { it.isNotEmpty() })
        }

        DhKit.Page(
            title = "交谈内容",
            sub = "台词列表",
            onBack = { PageStack.pop(act) }
        ) {
            DhForm.Note("一条一句话，没有字数限制；改动即时保存。空行不会被保存。")
            if (lines.isEmpty()) {
                DhForm.Note("还没有台词，点下面的「增加一条」开始写。")
            }
            for (i in 0 until lines.size) {
                // key 里带 tick：删掉中间一条后，后续输入框会重建而不是错位继承上一行的内容。
                key("talk-" + tick + "-" + i) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DhForm.Input(
                            value = lines[i],
                            onValueChange = {
                                lines[i] = it
                                flush()
                            },
                            modifier = Modifier.weight(1f),
                            hint = "第 " + (i + 1) + " 条台词"
                        )
                        Spacer(Modifier.width(6.dp))
                        DhKit.IconButton(Icons.IC_TRASH, Design.SZ_MD, c.sub, label = "删除") {
                            lines.removeAt(i)
                            flush()
                            tick++
                        }
                    }
                }
            }
            DhKit.ActionRow(
                label = "增加一条",
                iconRes = Icons.IC_PLUS
            ) {
                lines.add("")
            }
        }
    }
}