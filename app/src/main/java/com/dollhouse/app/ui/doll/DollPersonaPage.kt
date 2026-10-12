package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack

/**
 * 【职责】「性格内容」子页：这只人偶的性格描述，绑定给模型用。
 *
 * 【为什么是大文本框而不是结构化表单】r10 规格原文：「先落成一个大文本框」。
 *   性格描述天然是一段自由文字（语气、口头禅、雷点、关系设定……），
 *   拆成「字段」反而逼用户把想说的话塞进不合适的格子里。
 *
 * 【r11 起就是人设本体】不再有「全局人设」这一层：本页写的内容就是这只人偶的
 *   性格本体，聊天时作为 system 人设整段下发给模型（见 ChatHistoryStore.buildRequest）。
 *   留空 = 不发人设消息（模型退化成通用助手），而不是「叠加在别的人设之上」。
 *
 * 【默认配置的初始值】老版本那份写死的人设文本，升级后由
 *   [com.dollhouse.app.data.DollProfile.seedDefault] 一次性写进「默认配置」的性格内容，
 *   于是老用户的聊天表现与升级前基本一致（少了已删除的好感度与联网两段）。
 *
 * 【落盘时机】输入即时落盘（与其它子页一致）。文本框失焦 / 返回都不影响已写入的内容。
 */
object DollPersonaPage {
    /** 页面 key 前缀：`doll:persona:<dollId>`。 */
    const val PREFIX = "doll:persona:"

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
        var text by remember(id) { mutableStateOf(DollProfile.persona(act, id)) }

        DhKit.Page(
            title = "性格内容",
            sub = "这只人偶的人设",
            onBack = { PageStack.pop(act) }
        ) {
            DhForm.Note("写这只人偶的性格、语气、说话习惯等；内容会随对话一起交给模型。")
            DhForm.InputMultiline(
                value = text,
                onValueChange = {
                    text = it
                    // 【为什么即时落盘】性格是长文本，用户可能写一大段后直接按返回键；
                    //   若等显式保存，忘记点一次就白写了 —— 这类丢失比多写一次盘严重得多。
                    DollProfile.setPersona(act, id, it)
                },
                label = "性格描述",
                hint = "例如：说话简短，爱用反问，对主人有轻微傲娇……",
                minHeight = 220
            )
            DhForm.Note("当前 " + text.length + " 字。")
        }
    }
}