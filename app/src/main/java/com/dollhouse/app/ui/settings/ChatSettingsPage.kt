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
import androidx.compose.ui.res.painterResource
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
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

    /**
     * 【P1-e·设置索引接线】按 rowId 取索引条目，供各行的 `spot =` 使用。
     * 【自纠错】rowId 写错 / 忘了传 spot → 标题退回空串（肉眼可见）。不要加 `?: "兜底"`。
     */
    private fun spot(rowId: String) = SettingsSpots.find(rowId)

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
        // 【状态必须在组合期读】QzxList 的 DSL lambda 不是 @Composable，
        //   在里面调 remember 会编译不过 —— 所以在这里先读好，lambda 里只引用变量。
        val streamOn = remember(tick) { PetPrefs.streamOutput(act) }
        val thinkingOn = remember(tick) { PetPrefs.showThinking(act) }
        val learnOn = remember(tick) { PetPrefs.learnEnabled(act) }

        QzxPage(
            title = "对话显示",

            backIconRes = Icons.IC_ARROW_LEFT,
            subtitle = "流式与思考过程",
            onBack = { closeIfOpen(act) },
        ) {
            QzxList(spotTitle = { spot(it)?.title }) {
            // ---------------- 流式 ----------------
            section(id = "stream", title = "流式输出", collapsible = true) {
                note("开着时她的回复会一个字一个字冒出来，不用等整段生成完。")
                switch(
                    "流式输出",
                    checked = streamOn,
                    iconRes = Icons.IC_ACTIVITY
                ).spot("chat.stream").onChecked { on ->
                    PetPrefs.setStreamOutput(act, on)
                    tick++
                }
                note("关掉就退回「等整段回来再显示」。遇到中转服务流式回一半就断的情况，"
                        + "可以关掉它应急 —— 否则会自动降级重试一次。")
            }

            // ---------------- 思考过程 ----------------
            section(id = "think", title = "思考过程", collapsible = true) {
                note("推理型模型思考时说的「心里话」，会单独存在回复上方，点一下展开。")
                switch(
                    "思考过程",
                    checked = thinkingOn,
                    iconRes = Icons.IC_BRAIN
                ).spot("chat.thinking").onChecked { on ->
                    PetPrefs.setShowThinking(act, on)
                    tick++
                }
                note("关掉只是不显示；思考内容仍会随消息存下来，以后想看了随时打开。")
                note("她回复的正文里不会混进思考内容 —— 两者一直是分开的。")
            }

            // ---------------- 学习示例 ----------------
            // 【v0.1.0·新增】原工程只有 `learnEnabled/setLearnEnabled` 与读取注入逻辑，
            //   但既没有界面上这个开关、也没有任何写入学习示例的入口 ——
            //   等于整条功能从未生效。现在：开关在这里，写入口在消息菜单的「赞 · 学这个风格」。
            section(id = "learn", title = "学习示例", collapsible = true) {
                note("长按她的回复选「赞 · 学这个风格」，这一问一答就会成为示范；"
                        + "开着下面这个开关时，最近 3 条示范会随每次对话一起发出去，让她更贴近你喜欢的说法。")
                switch(
                    "学习示例",
                    checked = learnOn,
                    iconRes = Icons.IC_BOOK_HEART
                ).spot("chat.learnExamples").onChecked { on ->
                    PetPrefs.setLearnEnabled(act, on)
                    tick++
                }
                note("关掉只是不再带示范，已经记住的示范会保留，重新打开即可继续用。")
            }
            // ---------------- 说明 ----------------
            // 【页面级说明也要有归属】`note` 属于某个分区（它要享分区的内边距与卡片），
            //   所以给这条全局说明一个无标题的分区，而不是让它悬在列表根上。
            section(id = "help") {
                note("以上各项对所有对话生效。想让人偶「想得更深」，去聊天页的灯泡图标调思考程度。")
            }
            }
        }
    }
}