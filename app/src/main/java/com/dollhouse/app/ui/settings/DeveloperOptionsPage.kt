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
import com.dollhouse.app.core.CrashGuard
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】「开发者选项」页（r15 新增）：排障用的两个开关。
 *
 * 【入口】设置页「实验」卡片下级的一行 —— 与「识图模型」「对话显示」「记忆库设置」并列。
 *
 * 【为什么要有这一页】改造前 release 包**永远打不出日志**
 *   （`Logs.ON` 是编译期常量 false），用户报「人偶突然没了」时开发者手上一条线索都没有，
 *   只能靠 dumpsys / 网络统计旁敲侧击。而现在日志开关改成了运行期读 prefs，
 *   就必须给用户一个能打开它的地方 —— 这个页面就是那个地方。
 *
 * 【默认关】日志开关默认关闭：release 包默认行为与改造前完全一致（不打扰、不费性能）。
 *   只有需要排障时才手动打开，打开后立刻生效、无需重编重装。
 */
object DeveloperOptionsPage {

    const val TAG_PAGE = "feiyu_dev_options_page"

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
        var tick by remember { mutableStateOf(0) }
        val logOn = remember(tick) { Logs.isOn() }
        val crashFiles = remember(tick) { CrashGuard.listFiles(act) }

        DhKit.Page(
            title = "开发者选项",
            sub = "排障用，平时不用动",
            onBack = { closeIfOpen(act) }
        ) {
            DhKit.Card(collapsibleTitle = "日志") {
                DhKit.SwitchRow(name = "记录运行日志", checked = logOn) { on ->
                    Logs.setOn(act, on)
                    tick++
                }
                DhForm.Note(if (logOn) {
                    "已打开。日志写到系统日志里，用「开发者工具 → 日志」或电脑上的 adb logcat 就能看。"
                } else {
                    "关闭时什么都不记录，和以前一样。要看日志再打开。"
                })
                DhForm.Note("默认关闭：不打开就不会有任何额外开销。")
            }

            DhKit.Card(collapsibleTitle = "崩溃记录") {
                if (crashFiles.isEmpty()) {
                    DhKit.ValueRow(label = "记录数", value = "0")
                    DhForm.Note("没有崩溃记录。")
                } else {
                    DhKit.ValueRow(label = "记录数", value = crashFiles.size.toString())
                    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
                    for (f in crashFiles.take(5)) {
                        DhKit.ValueRow(
                            label = fmt.format(Date(f.lastModified())),
                            value = (f.length() / 1024).toString() + " KB"
                        )
                    }
                    DhForm.Note("存放在应用缓存目录（cache/crash）。小肥鱼如果曾经意外退出，"
                            + "这里会留下当时的现场，方便查原因。")
                    DhKit.ActionRow(
                        label = "清空记录",
                        iconRes = Icons.IC_TRASH,
                        desc = "删掉全部崩溃现场"
                    ) {
                        CrashGuard.clear(act)
                        tick++
                    }
                }
            }

            DhKit.Card(collapsibleTitle = "说明") {
                DhForm.Note("这一页里的开关只影响排障信息的收集，不影响聊天、人偶和任何功能。")
                DhForm.Note("崩溃记录不会上传到任何地方，只存在你自己的手机里。")
            }
        }
    }
}