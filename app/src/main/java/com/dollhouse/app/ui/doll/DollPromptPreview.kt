package com.dollhouse.app.ui.doll

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PersonaBuilder
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.chat.ChatSessions
import com.dollhouse.app.ui.compose.DhForm

/**
 * 【职责】「查看实际提示词」弹窗（r13 新增）。
 *
 * 【为什么这个功能必须有】本轮把「角色设定」做成了 14 个字段 + 注入控制，
 *   用户写完根本不知道拼出来是什么样子。没有预览，高级功能就是个黑盒 ——
 *   用户只能靠反复聊天去猜哪一项生效了。有了预览，「所见即所得」才成立。
 *
 * 【为什么值得信】预览与实际请求走**同一条代码路径**（[PersonaBuilder.build]），
 *   不存在「预览一套、实发一套」的漂移。
 *
 * 【世界书为什么要标出来】命中与否是动态的（取决于最近聊了什么），
 *   用户看到「这条没生效」时得能立刻知道原因，而不是怀疑自己写错了关键词。
 */
object DollPromptPreview {

    @JvmStatic
    fun show(act: MainActivity, dollId: String) {
        var dlgRef: android.app.Dialog? = null
        dlgRef = DhForm.show(act, "实际提示词") {
            Body(act, dollId) { dlgRef?.dismiss() }
        }
    }

    @Composable
    private fun Body(act: MainActivity, dollId: String, onClose: () -> Unit) {
        val name = remember(dollId) { DollStore.byId(act, dollId)?.name ?: "" }
        // 【用"最近的真实历史"做上下文】这样世界书的命中判定才与实际聊天一致。
        //   还没有会话时退回空历史 —— 预览仍能看到人设与常驻注入。
        val history = remember(dollId) {
            try {
                val convId = ChatSessions.idForDoll(dollId)
                org.json.JSONArray(PetPrefs.convHistory(act, convId))
            } catch (ignored: Throwable) {
                org.json.JSONArray()
            }
        }
        val text = remember(dollId) {
            val t = PersonaBuilder.preview(act, dollId, name, history, null)
            if (t.isEmpty()) {
                "（这只人偶还没写任何角色设定）"
            } else {
                t
            }
        }
        // 只读展示：用多行框而非纯文本，既支持长内容滚动，也能长按选中复制。
        var shown by remember(dollId) { mutableStateOf(text) }

        DhForm.Note("这是这一轮真正发给模型的人设部分。")
        DhForm.InputMultiline(
            value = shown,
            onValueChange = { shown = it },
            label = "拼接结果",
            minHeight = 260
        )
        DhForm.Note("当前 " + text.length + " 字。内容较长时会挤占上下文，建议精简。")

        Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(Modifier.weight(1f))
                DhForm.DialogButton(text = "关闭", primary = true) { onClose() }
            }
        }
    }
}