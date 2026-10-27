package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.core.*
import com.dollhouse.app.ui.compose.*
import com.dollhouse.app.ui.feedback.AppToast
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 诊断中心：首页统计、列表、详情与导出共用真实本地数据源。 */
object DeveloperOptionsPage {
    const val TAG_PAGE = "feiyu_dev_options_page"
    private enum class Screen { HOME, LOGS, DETAIL, EXPORT, HEALTH, EXIT, SETTINGS }
    @JvmStatic fun closeIfOpen(ctx: Context): Boolean = PageStack.popIfTop(ctx, TAG_PAGE)
    @JvmStatic fun buildPage(ctx: Context): View? {
        val act = findActivity(ctx) ?: return null
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }
    @JvmStatic fun open(ctx: Context) {
        runCatching { PageStack.push(ctx, TAG_PAGE) { buildPage(ctx) } }.onFailure { Logs.w("Diagnostics", "打开诊断中心失败", it) }
    }

    @Composable private fun Content(act: Activity) {
        var screen by remember { mutableStateOf(Screen.HOME) }
        var summary by remember { mutableStateOf<DiagnosticsStore.Summary?>(null) }
        var selected by remember { mutableStateOf<DiagnosticsStore.DiagnosticEvent?>(null) }
        var tick by remember { mutableIntStateOf(0) }
        fun refresh() { tick++ }
        LaunchedEffect(tick, screen) { summary = withContext(Dispatchers.IO) { runCatching { DiagnosticsStore.summary(act) }.getOrNull() } }
        val title = when (screen) { Screen.HOME -> "诊断中心"; Screen.LOGS -> "日志记录"; Screen.DETAIL -> "错误详情"; Screen.EXPORT -> "导出诊断包"; Screen.HEALTH -> "日志自检"; Screen.EXIT -> "异常退出"; Screen.SETTINGS -> "诊断设置" }
        val sub = when (screen) { Screen.HOME -> "本地日志与故障现场"; Screen.LOGS -> "搜索、筛选与查看"; Screen.DETAIL -> "错误上下文与异常堆栈"; Screen.EXPORT -> "真实数据 · ZIP 压缩包"; Screen.HEALTH -> "采集覆盖与存储状态"; Screen.EXIT -> "崩溃与系统退出原因"; else -> "采集策略与存储管理" }
        DhKit.Page(title = title, sub = sub, onBack = { when (screen) { Screen.HOME -> closeIfOpen(act); Screen.DETAIL -> screen = Screen.LOGS; else -> screen = Screen.HOME } }, actions = if (screen == Screen.HOME) {{ DhKit.IconButton(Icons.IC_SETTINGS, Design.SZ_MD, DhTokens.colors.title, tooltip = "诊断设置") { screen = Screen.SETTINGS } }} else null) {
            when (screen) {
                Screen.HOME -> Home(summary, { screen = Screen.LOGS }, { selected = it; screen = Screen.DETAIL }, { screen = it })
                Screen.LOGS -> LogsPage(act, tick) { selected = it; screen = Screen.DETAIL }
                Screen.DETAIL -> selected?.let { DetailPage(act, it) } ?: Empty("未选择错误记录")
                Screen.EXPORT -> ExportPage(act, null) { refresh() }
                Screen.HEALTH -> HealthPage(act)
                Screen.EXIT -> ExitPage(act)
                Screen.SETTINGS -> SettingsPage(act) { refresh() }
            }
        }
    }

    @Composable private fun Home(s: DiagnosticsStore.Summary?, all: () -> Unit, open: (DiagnosticsStore.DiagnosticEvent) -> Unit, navigate: (Screen) -> Unit) {
        DhKit.Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DhKit.Icon(Icons.IC_SHIELD_CHECK, Design.SZ_MD, if (s?.corrupted == 0) DhTokens.colors.ok else DhTokens.colors.err)
                Spacer(Modifier.width(8.dp)); Text(if (s == null) "正在读取诊断数据" else if (s.corrupted == 0) "日志采集已就绪" else "部分日志需要检查", color = DhTokens.colors.title, fontFamily = DhTokens.fontsBold)
            }
            DhForm.Note(if (s == null) "请稍候" else "严重错误独立保存；详细运行日志由诊断设置控制。统计单位为错误分组，不是重复发生总次数。")
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("错误", s?.errors, DhTokens.colors.err, Modifier.weight(1f), all)
                Stat("警告", s?.warnings, DhTokens.colors.sub, Modifier.weight(1f), all)
                Stat("崩溃", s?.crashes, DhTokens.colors.err, Modifier.weight(1f), all)
            }
        }
        DhKit.SectionLabel("最近错误", Modifier.padding(top = 10.dp, bottom = 6.dp))
        val recent = s?.groups?.take(3).orEmpty()
        if (s == null) Empty("正在加载…") else if (recent.isEmpty()) Empty("暂无错误记录。出现实际异常或警告后会显示在这里。")
        recent.forEach { EventCard(it) { open(it) } }
        if (recent.isNotEmpty()) DhKit.ActionRow("查看全部", iconRes = Icons.IC_TERMINAL, desc = "搜索、筛选与查看全部错误分组", onClick = all)
        DhKit.SectionLabel("诊断工具", Modifier.padding(top = 10.dp, bottom = 6.dp))
        DhKit.Card {
            DhKit.ActionRow("查看全部日志", iconRes = Icons.IC_TERMINAL, desc = "搜索、筛选与错误详情") { navigate(Screen.LOGS) }
            DhKit.ActionRow("导出诊断包", iconRes = Icons.IC_DOWNLOAD, desc = "ZIP · 完整诊断资料") { navigate(Screen.EXPORT) }
            DhKit.ActionRow("日志系统自检", iconRes = Icons.IC_SHIELD_CHECK, desc = "存储、覆盖与完整性") { navigate(Screen.HEALTH) }
            DhKit.ActionRow("异常退出记录", iconRes = Icons.IC_CLOCK_3, desc = "崩溃、ANR 与系统回收") { navigate(Screen.EXIT) }
        }
        DhForm.Note("记录只保存在本机。系统无法提供的信息会明确标记，不推测故障原因。")
    }

    @Composable private fun Stat(label: String, value: Int?, color: Color, modifier: Modifier, click: () -> Unit) {
        Column(modifier.clip(RoundedCornerShape(12.dp)).background(DhTokens.colors.soft).pressable(click).padding(12.dp)) {
            Text(value?.toString() ?: "—", color = color, fontSize = 24.sp, fontFamily = DhTokens.fontsBold)
            Text(label, color = DhTokens.colors.sub, fontSize = UiKit.FS_TINY.sp)
        }
    }

    @Composable private fun LogsPage(act: Activity, tick: Int, open: (DiagnosticsStore.DiagnosticEvent) -> Unit) {
        var query by remember { mutableStateOf("") }
        var severity by remember { mutableStateOf<DiagnosticsStore.Severity?>(null) }
        var records by remember { mutableStateOf<List<DiagnosticsStore.DiagnosticEvent>?>(null) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(query, severity, tick) {
            records = null; failed = false
            records = withContext(Dispatchers.IO) { runCatching { DiagnosticsStore.query(act, severity, query) }.onFailure { failed = true }.getOrDefault(emptyList()) }
        }
        DhForm.Input(query, { query = it }, hint = "搜索错误、模块或异常类型…", imeAction = androidx.compose.ui.text.input.ImeAction.Search)
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 10.dp)) {
            FilterChip("全部", severity == null) { severity = null }
            FilterChip("错误", severity == DiagnosticsStore.Severity.ERROR) { severity = DiagnosticsStore.Severity.ERROR }
            FilterChip("警告", severity == DiagnosticsStore.Severity.WARNING) { severity = DiagnosticsStore.Severity.WARNING }
            FilterChip("崩溃", severity == DiagnosticsStore.Severity.CRASH) { severity = DiagnosticsStore.Severity.CRASH }
        }
        if (failed) Empty("日志读取失败，请运行日志自检。") else records?.let { if (it.isEmpty()) Empty("没有符合条件的记录") else it.forEach { e -> EventCard(e) { open(e) } } } ?: Empty("正在加载…")
    }

    @Composable private fun FilterChip(text: String, selected: Boolean, click: () -> Unit) = DhForm.SelectChip(text, selected, Modifier.padding(end = 6.dp), onClick = click)

    @Composable private fun EventCard(e: DiagnosticsStore.DiagnosticEvent, click: () -> Unit) {
        DhKit.Card { Column(Modifier.fillMaxWidth().pressable(click)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DhForm.Badge(level(e.severity), if (e.severity == DiagnosticsStore.Severity.WARNING) DhTokens.colors.sub else DhTokens.colors.err, DhForm.semanticBg(if (e.severity == DiagnosticsStore.Severity.WARNING) DhTokens.colors.sub else DhTokens.colors.err))
                Spacer(Modifier.weight(1f)); Text(time(e.timestamp), color = DhTokens.colors.sub, fontSize = UiKit.FS_TINY.sp)
            }
            Text(e.message, Modifier.padding(top = 10.dp), color = DhTokens.colors.title, fontFamily = DhTokens.fontsBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(e.module, Modifier.padding(top = 5.dp), color = DhTokens.colors.sub, fontSize = UiKit.FS_TINY.sp)
            Text("重复 ${e.count} 次 · ${e.code}", Modifier.padding(top = 9.dp), color = DhTokens.colors.sub, fontSize = UiKit.FS_TINY.sp)
        } }
    }

    @Composable private fun DetailPage(act: Activity, e: DiagnosticsStore.DiagnosticEvent) {
        var tab by remember { mutableIntStateOf(0) }
        DhKit.Card {
            DhForm.Badge(level(e.severity), DhTokens.colors.err, DhForm.semanticBg(DhTokens.colors.err))
            Text(e.message, Modifier.padding(top = 10.dp), color = DhTokens.colors.title, fontSize = 17.sp, fontFamily = DhTokens.fontsBold)
            DhKit.ValueRow("错误编号", e.code); DhKit.ValueRow("事件 ID", e.eventId)
            DhKit.ValueRow("所属模块", e.module); DhKit.ValueRow("发生时间", time(e.timestamp))
            DhKit.ValueRow("线程", e.thread.ifEmpty { "未采集" }); DhKit.ValueRow("异常类型", e.exceptionType.ifEmpty { "未采集" })
            DhKit.ValueRow("发生次数", "${e.count} 次"); DhKit.ValueRow("首次发生", time(e.firstTimestamp))
            DhKit.ValueRow("关联操作 ID", e.operationId.ifEmpty { "未采集" }); DhKit.ValueRow("应用版本", e.appVersion.ifEmpty { "未采集" })
            Row(Modifier.padding(vertical = 8.dp)) {
                DhForm.SelectChip("详情", tab == 0, Modifier.weight(1f)) { tab = 0 }; Spacer(Modifier.width(5.dp))
                DhForm.SelectChip("堆栈", tab == 1, Modifier.weight(1f)) { tab = 1 }; Spacer(Modifier.width(5.dp))
                DhForm.SelectChip("关联日志", tab == 2, Modifier.weight(1f)) { tab = 2 }
            }
            val text = when (tab) { 0 -> e.readable(); 1 -> e.stackTrace.ifEmpty { "未采集异常堆栈" }; else -> e.context.ifEmpty { "未采集关联日志" } }
            Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DhTokens.colors.soft).padding(12.dp), color = DhTokens.colors.sub, fontSize = UiKit.FS_TINY.sp, lineHeight = 18.sp)
        }
        DhKit.ActionRow("复制错误详情", iconRes = Icons.IC_COPY, desc = "复制结构化摘要、堆栈与关联日志") {
            runCatching { act.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Dollhouse 错误详情", e.readable())); AppToast.show(act, "错误详情已复制") }.onFailure { AppToast.show(act, "复制失败：" + (it.message ?: it.javaClass.simpleName)) }
        }
        var exporting by remember { mutableStateOf(false) }
        DhKit.ActionRow(if (exporting) "正在导出…" else "导出此错误", iconRes = Icons.IC_DOWNLOAD, desc = "独立 ZIP · 真实诊断信息", enabled = !exporting) {
            exporting = true
            DiagnosticsExporter.export(act, e) { result ->
                exporting = false
                result.onSuccess { runCatching { act.startActivity(DiagnosticsExporter.shareIntent(act, it.uri)) }.onFailure { t -> AppToast.show(act, "无法打开分享：" + t.message) } }.onFailure { AppToast.show(act, "导出失败：" + (it.message ?: it.javaClass.simpleName), long = true) }
            }
        }
    }

    @Composable private fun ExportPage(act: Activity, single: DiagnosticsStore.DiagnosticEvent?, exported: () -> Unit) {
        var busy by remember { mutableStateOf(false) }
        DhKit.Card {
            DhKit.CardTitle("导出诊断包", iconRes = Icons.IC_DOWNLOAD)
            DhForm.Note("生成便于查看、分享或交给编程助手分析的 ZIP。打包和完整性检查均在后台执行。")
            DhKit.ValueRow("文件格式", "Dollhouse_Diagnostics_版本号_时间.zip"); DhKit.ValueRow("manifest.json", "格式版本、文件清单")
            DhKit.ValueRow("summary.json", "真实错误分组统计"); DhKit.ValueRow("logs/ + errors/", "结构化事件、详情与堆栈")
            DhKit.ValueRow("system/", "环境与系统退出信息"); DhKit.ValueRow("README.txt", "目录与阅读说明")
            DhForm.Note("隐私检查：日志入口已过滤常见密钥字段，不包含聊天正文或原始用户资源。异常消息中仍可能含有业务文本，分享前请自行检查。")
        }
        DhKit.ActionRow(if (busy) "正在生成并校验…" else "生成 ZIP 诊断包", iconRes = Icons.IC_PACKAGE, desc = "完成后打开系统分享界面", enabled = !busy) {
            busy = true
            DiagnosticsExporter.export(act, single) { result ->
                busy = false
                result.onSuccess { AppToast.show(act, "诊断包已生成（${it.entries} 个文件），请选择保存或分享目标", long = true); runCatching { act.startActivity(DiagnosticsExporter.shareIntent(act, it.uri)) }.onFailure { t -> AppToast.show(act, "无法打开分享：" + t.message) }; exported() }.onFailure { AppToast.show(act, "导出失败：" + (it.message ?: it.javaClass.simpleName), long = true) }
            }
        }
    }

    @Composable private fun HealthPage(act: Activity) {
        var check by remember { mutableStateOf<DiagnosticsStore.SelfCheck?>(null) }
        LaunchedEffect(Unit) { check = withContext(Dispatchers.IO) { DiagnosticsStore.selfCheck(act) } }
        DhKit.Card {
            DhKit.CardTitle("日志系统自检", iconRes = Icons.IC_SHIELD_CHECK)
            DhForm.Note(if (check == null) "正在检查真实存储与处理器状态…" else "检查时间：${time(check!!.checkedAt)}")
            check?.items?.forEach { DhKit.ValueRow(it.first, it.second) }
            DhForm.Note("全局异常处理器不能覆盖所有已捕获异常、ANR 或系统回收；业务覆盖状态不代表所有代码路径均已审计。")
        }
    }

    @Composable private fun ExitPage(act: Activity) {
        var exits by remember { mutableStateOf<List<DiagnosticsStore.ExitRecord>?>(null) }
        LaunchedEffect(Unit) { exits = withContext(Dispatchers.IO) { DiagnosticsStore.exits(act) } }
        DhKit.Card {
            DhKit.CardTitle("异常退出记录", iconRes = Icons.IC_CLOCK_3)
            exits?.let { if (it.isEmpty()) Empty("系统没有提供可读取的历史退出记录") else it.forEach { e -> DhKit.ValueRow(time(e.timestamp), e.reason); DhForm.Note(e.description + if (e.trace.isNotEmpty()) "\n${e.trace}" else "") } } ?: Empty("正在查询系统退出信息…")
            DhForm.Note("信息来自 Android 系统历史退出接口。系统未保留、版本不支持或原因未知时，不作推断。")
        }
    }

    @Composable private fun SettingsPage(act: Activity, changed: () -> Unit) {
        var detailed by remember { mutableStateOf(Logs.isOn()) }
        var clear by remember { mutableStateOf(false) }
        var clearing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        DhKit.Card {
            DhKit.SwitchRow("记录详细运行日志", detailed) { detailed = it; Logs.setOn(act, it); changed() }
            DhForm.Note("关闭时停止可选普通日志的持久化；警告、错误和未捕获崩溃仍保留基础记录。")
            DhKit.ValueRow("严重错误基础记录", "始终开启"); DhKit.ValueRow("错误前后关键事件", "有界内存缓冲，随错误保存")
            DhKit.ValueRow("日志保留策略", "7 天；单文件 1 MB；总量 8 MB")
            DhKit.ActionRow("清空全部诊断记录", iconRes = Icons.IC_TRASH, desc = "删除结构化日志与历史崩溃记录") { clear = true }
        }
        if (clear) DhForm.Alert("清空全部诊断记录？", { if (!clearing) clear = false }, "清空", {
            clearing = true
            scope.launch(Dispatchers.IO) {
                val ok = DiagnosticsStore.clear(act)
                withContext(Dispatchers.Main) { clearing = false; clear = false; changed(); AppToast.show(act, if (ok) "诊断记录已清空" else "部分记录未能删除", long = !ok) }
            }
        }, "取消") { Text("此操作无法撤销。统计、列表、详情与后续导出会同时更新。", color = DhTokens.colors.sub) }
    }

    @Composable private fun Empty(text: String) { DhKit.Card { Text(text, color = DhTokens.colors.sub, fontSize = UiKit.FS_BTN.sp) } }
    private fun level(s: DiagnosticsStore.Severity) = when (s) { DiagnosticsStore.Severity.INFO -> "信息"; DiagnosticsStore.Severity.WARNING -> "警告"; DiagnosticsStore.Severity.ERROR -> "错误"; DiagnosticsStore.Severity.CRASH -> "崩溃" }
    private fun time(t: Long): String = if (t <= 0) "未采集" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(t))
    private fun findActivity(ctx: Context): Activity? { var c: Context? = ctx; repeat(8) { if (c is Activity) return c; c = (c as? ContextWrapper)?.baseContext }; return null }
}