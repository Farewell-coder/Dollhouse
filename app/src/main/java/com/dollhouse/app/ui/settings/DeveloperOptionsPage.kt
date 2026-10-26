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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.dollhouse.app.ui.feedback.AppMessage
import com.dollhouse.app.ui.feedback.AppMessageQueue
import com.dollhouse.app.ui.feedback.AppToast
import com.dollhouse.app.ui.feedback.FeedbackBox
import com.dollhouse.app.ui.feedback.MessageKind
import kotlinx.coroutines.launch
import com.dollhouse.app.core.CrashGuard
import com.dollhouse.app.core.CrashLogExporter
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
        var tick by remember { mutableIntStateOf(0) }
        val logOn = remember(tick) { Logs.isOn() }
        var crashCount by remember { mutableIntStateOf(0) }
        var crashFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
        var exporting by remember { mutableStateOf(false) }
        var clearRequested by remember { mutableStateOf(false) }
        val snackbarHost = remember { SnackbarHostState() }
        val messages = remember { AppMessageQueue(snackbarHost) }
        val scope = rememberCoroutineScope()
        var mounted by remember { mutableStateOf(true) }
        DisposableEffect(Unit) {
            onDispose { mounted = false }
        }
        LaunchedEffect(tick) {
            CrashLogExporter.loadSummary(act) { summary ->
                if (!mounted) return@loadSummary
                crashCount = summary.count
                crashFiles = summary.latest
            }
        }
        fun exportCrashLogs() {
            if (exporting) return
            if (crashCount == 0) {
                AppToast.show(act, "没有可导出的崩溃记录")
                return
            }
            exporting = true
            CrashLogExporter.export(act) { result ->
                if (!mounted) return@export
                exporting = false
                result.onSuccess { uri ->
                    runCatching {
                        act.startActivity(CrashLogExporter.shareIntent(act, uri))
                    }.onFailure {
                        AppToast.show(act, "没有可用的分享方式，请安装文件管理或分享应用", long = true)
                    }
                }.onFailure {
                    val text = "导出失败：" + (it.message ?: it.javaClass.simpleName)
                    scope.launch {
                        messages.show(AppMessage(text, MessageKind.ERROR, actionLabel = "重试")) {
                            exportCrashLogs()
                        }
                    }
                }
            }
        }

        FeedbackBox(snackbarHost, Modifier.fillMaxSize()) {
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
                if (crashCount == 0) {
                    DhKit.ValueRow(label = "记录数", value = "0")
                    DhForm.Note("没有崩溃记录。")
                } else {
                    DhKit.ValueRow(label = "记录数", value = crashCount.toString())
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
                        label = if (exporting) "正在导出…" else "导出错误日志",
                        iconRes = Icons.IC_DOWNLOAD,
                        desc = if (exporting) "正在后台汇总，请稍候" else "汇总全部记录，通过系统分享发送或保存",
                        enabled = !exporting,
                        tooltip = "汇总所有崩溃记录，按住查看说明"
                    ) { exportCrashLogs() }
                    DhKit.ActionRow(
                        label = "清空记录",
                        iconRes = Icons.IC_TRASH,
                        desc = "删掉全部崩溃现场"
                    ) {
                        clearRequested = true
                    }
                }
            }

            DhKit.Card(collapsibleTitle = "说明") {
                DhForm.Note("这一页里的开关只影响排障信息的收集，不影响聊天、人偶和任何功能。")
                DhForm.Note("崩溃记录不会上传到任何地方，只存在你自己的手机里。")
            }
        }
        }
        if (clearRequested) {
            DhForm.Alert(
                title = "清空全部崩溃记录？",
                onDismiss = { clearRequested = false },
                posText = "清空",
                onPos = {
                    CrashGuard.clear(act)
                    clearRequested = false
                    tick++
                    AppToast.show(act, "崩溃记录已清空")
                },
                negText = "取消"
            ) {
                Text("将删除本机保存的 $crashCount 份崩溃现场，删除后无法恢复。")
            }
        }
    }
}