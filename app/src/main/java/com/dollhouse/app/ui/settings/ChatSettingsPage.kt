package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】「对话显示」设置页：流式输出 + 思考过程（r13 新增）。
 *
 * 【入口】设置页「实验」卡片下级的一行 —— 与「记忆库设置」并列
 *   （用户定案：多出来的全局设置都放实验卡片下级）。
 *
 * 【为什么与记忆库设置分成两页而不是塞一页】两者毫无关系（一个是记忆策略，
 *   一个是聊天渲染），塞一页会让用户找不到东西。分成两个入口、各一句话说明，
 *   与设置页既有的「一类事一张卡」一致。
 *
 * 【为什么流式要留开关】大部分中转支持 SSE，但确实有服务端回一半就断的情况。
 *   那种场景下用户需要能退回非流式 —— 没有开关就等于把这类用户直接挡在门外。
 */
object ChatSettingsPage {
    const val TAG_PAGE = "feiyu_chat_settings_page"

    /** 返回键用：本页在栈顶就退一层并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return PageStack.popIfTop(ctx, TAG_PAGE)
    }

    @JvmStatic
    fun buildPage(ctx: Context): View? {
        val act = findActivity(ctx) ?: return null
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @JvmStatic
    fun open(ctx: Context) {
        try {
            PageStack.push(ctx, TAG_PAGE) { buildPage(ctx) }
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        var i = 0
        while (i < 8 && c != null) {
            if (c is Activity) {
                return c
            }
            if (c is ContextWrapper) {
                c = c.baseContext
            } else {
                break
            }
        }
        return null
    }

    @Composable
    private fun Content(act: Activity) {
        // 每次改动都 bump 一次，让控件立刻反映到界面（prefs 是同步读的）。
        var tick by remember { mutableStateOf(0) }

        DhKit.Page(
            title = "对话显示",
            sub = "流式与思考过程",
            onBack = { closeIfOpen(act) }
        ) {
            // ---------------- 流式 ----------------
            DhKit.Card(collapsibleTitle = "流式输出") {
                DhForm.Note("开着时她的回复会一个字一个字冒出来，不用等整段生成完。")
                DhKit.SwitchRow(
                    name = "流式输出",
                    iconRes = Icons.IC_ACTIVITY,
                    checked = remember(tick) { PetPrefs.streamOutput(act) }
                ) { on ->
                    PetPrefs.setStreamOutput(act, on)
                    tick++
                }
                DhForm.Note("关掉就退回「等整段回来再显示」。遇到中转服务流式回一半就断的情况，"
                        + "可以关掉它应急 —— 否则会自动降级重试一次。")
            }

            // ---------------- 思考过程 ----------------
            DhKit.Card(collapsibleTitle = "思考过程") {
                DhForm.Note("推理型模型思考时说的「心里话」，会单独存在回复上方，点一下展开。")
                DhKit.SwitchRow(
                    name = "显示思考过程",
                    iconRes = Icons.IC_BRAIN,
                    checked = remember(tick) { PetPrefs.showThinking(act) }
                ) { on ->
                    PetPrefs.setShowThinking(act, on)
                    tick++
                }
                DhForm.Note("关掉只是不显示；思考内容仍会随消息存下来，以后想看了随时打开。")
                DhForm.Note("她回复的正文里不会混进思考内容 —— 两者一直是分开的。")
            }

            // ---------------- 学习示例 ----------------
            // 【v0.1.0·新增】原工程只有 `learnEnabled/setLearnEnabled` 与读取注入逻辑，
            //   但既没有界面上这个开关、也没有任何写入学习示例的入口 ——
            //   等于整条功能从未生效。现在：开关在这里，写入口在消息菜单的「赞 · 学这个风格」。
            DhKit.Card(collapsibleTitle = "学习示例") {
                DhForm.Note("长按她的回复选「赞 · 学这个风格」，这一问一答就会成为示范；"
                        + "开着下面这个开关时，最近 3 条示范会随每次对话一起发出去，让她更贴近你喜欢的说法。")
                DhKit.SwitchRow(
                    name = "把示范带进对话",
                    iconRes = Icons.IC_BOOK_HEART,
                    checked = remember(tick) { PetPrefs.learnEnabled(act) }
                ) { on ->
                    PetPrefs.setLearnEnabled(act, on)
                    tick++
                }
                DhForm.Note("关掉只是不再带示范，已经记住的示范会保留，重新打开即可继续用。")
            }
            // ---------------- 说明 ----------------
            DhForm.Note("以上各项对所有对话生效。想让人偶「想得更深」，去聊天页的灯泡图标调思考程度。")
        }
    }
}