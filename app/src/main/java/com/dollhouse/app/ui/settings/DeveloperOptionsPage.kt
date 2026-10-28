package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
    private enum class Screen { HOME, LOGS, DETAIL, SETTINGS }
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
        // 【v0.1.4·导出不跳页】导出现在直接在首页原地执行，进度与结果只在本页回显。
        var exporting by remember { mutableStateOf(false) }
        var tick by remember { mutableIntStateOf(0) }
        fun refresh() { tick++ }
        // 【v0.1.4·修「系统返回直接退到关于」】本页是「一层 PageStack + 内部五个子屏」的结构，
        //   与 ProviderNav 同构。原先只处理了顶栏返回箭头，系统返回键走
        //   MainActivity.onBackPressed → PageStack.handleBack，会把整层弹掉 ——
        //   用户在「日志记录」按返回，直接回到「关于」，而不是回到诊断中心首页。
        // 这里把当前子屏暴露给 [PageStack.backInterceptor]（由 MainActivity 在 pop 之前询问）。
        //   带上本页栈 key：只有它确实位于最上层时才会被询问，避免压栈后误拦别人的返回。
        PageStack.backInterceptor = PageStack.BackInterceptor(TAG_PAGE) {
            if (screen == Screen.HOME) {
                false
            } else {
                screen = if (screen == Screen.DETAIL) Screen.LOGS else Screen.HOME
                true
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                if (PageStack.backInterceptor?.key == TAG_PAGE) {
                    PageStack.backInterceptor = null
                }
            }
        }
        LaunchedEffect(tick, screen) { summary = withContext(Dispatchers.IO) { runCatching { DiagnosticsStore.summary(act) }.getOrNull() } }
        val title = when (screen) { Screen.HOME -> "诊断中心"; Screen.LOGS -> "日志记录"; Screen.DETAIL -> "错误详情"; else -> "诊断设置" }
        val sub = when (screen) { Screen.HOME -> "本地日志与故障现场"; Screen.LOGS -> "搜索、筛选与查看"; Screen.DETAIL -> "错误上下文与异常堆栈"; else -> "采集策略与存储管理" }
        DhKit.Page(title = title, sub = sub, onBack = { when (screen) { Screen.HOME -> closeIfOpen(act); Screen.DETAIL -> screen = Screen.LOGS; else -> screen = Screen.HOME } }, actions = if (screen == Screen.HOME) {{ DhKit.IconButton(Icons.IC_SETTINGS, Design.SZ_MD, DhTokens.colors.title, tooltip = "诊断设置") { screen = Screen.SETTINGS } }} else null) {
            when (screen) {
                Screen.HOME -> Home(
                    s = summary,
                    all = { screen = Screen.LOGS },
                    open = { selected = it; screen = Screen.DETAIL },
                    navigate = { screen = it },
                    // 【v0.1.4·导出不跳二级页】用户要求「点一下就直接导出」。
                    //   原来点了先跳「导出诊断包」页、还要再点一次「生成 ZIP」——
                    //   一个只有一次真实动作的页面没有存在价值，还多一次返回操作。
                    exporting = exporting,
                    onExport = { runExport(act) { busy -> exporting = busy } }
                )
                Screen.LOGS -> LogsPage(act, tick) { selected = it; screen = Screen.DETAIL }
                Screen.DETAIL -> selected?.let { DetailPage(act, it) } ?: Empty("未选择错误记录")
                Screen.SETTINGS -> SettingsPage(act) { refresh() }
            }
        }
    }

    @Composable private fun Home(
        s: DiagnosticsStore.Summary?,
        all: () -> Unit,
        open: (DiagnosticsStore.DiagnosticEvent) -> Unit,
        navigate: (Screen) -> Unit,
        exporting: Boolean,
        onExport: () -> Unit
    ) {
        DhKit.Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DhKit.Icon(Icons.IC_SHIELD_CHECK, Design.SZ_MD, if (s?.corrupted == 0) DhTokens.colors.ok else DhTokens.colors.err)
                Spacer(Modifier.width(8.dp)); Text(if (s == null) "正在读取诊断数据" else if (s.corrupted == 0) "日志采集已就绪" else "部分日志需要检查", color = DhTokens.colors.title, fontFamily = DhTokens.fontsBold)
            }
            if (s == null) DhForm.Note("请稍候")
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
        // 查看全部统一由下方诊断工具进入。
        DhKit.SectionLabel("诊断工具", Modifier.padding(top = 10.dp, bottom = 6.dp))
        DhKit.Card {
            DhKit.ActionRow("查看全部日志", iconRes = Icons.IC_TERMINAL, desc = "搜索、筛选与错误详情") { navigate(Screen.LOGS) }
            // 【v0.1.4·导出不跳二级页】用户要求「点一下就直接导出」。
            //   原先进「导出诊断包」页后还要再点一次「生成 ZIP」，中间那页只是一个按钮。
            //   现在直接在这里触发，进度就地回显（文案随 exporting 变化、期间不可再点）。
            DhKit.ActionRow(
                if (exporting) "正在生成并校验…" else "导出诊断包",
                iconRes = Icons.IC_DOWNLOAD,
                desc = "ZIP · 完整诊断资料，点一下直接生成",
                enabled = !exporting,
                onClick = onExport
            )
            // 仅保留日志查看与导出两个入口。
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
        var module by remember { mutableStateOf("") }
        var modules by remember { mutableStateOf(emptyList<String>()) }
        var usage by remember { mutableStateOf("") }
        var records by remember { mutableStateOf<List<DiagnosticsStore.DiagnosticEvent>?>(null) }
        var hasMore by remember { mutableStateOf(false) }
        var loadingMore by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val s = DiagnosticsStore.summary(act)
                modules = s.groups.map { it.module }.distinct().sorted().take(50)
                usage = "%.1f MB / 8 MB".format(Locale.US, s.bytes / 1048576.0)
            }
        }
        LaunchedEffect(query, severity, module, tick) {
            records = null; failed = false
            val result = withContext(Dispatchers.IO) { runCatching { DiagnosticsStore.queryPage(act, severity, module, query) } }
            result.onSuccess { records = it.items; hasMore = it.hasMore }.onFailure { failed = true }
        }
        DhForm.Input(query, { query = it }, hint = "搜索错误、模块或异常类型…", imeAction = androidx.compose.ui.text.input.ImeAction.Search)
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 10.dp)) {
            FilterChip("全部", severity == null) { severity = null }
            FilterChip("错误", severity == DiagnosticsStore.Severity.ERROR) { severity = DiagnosticsStore.Severity.ERROR }
            FilterChip("警告", severity == DiagnosticsStore.Severity.WARNING) { severity = DiagnosticsStore.Severity.WARNING }
            FilterChip("崩溃", severity == DiagnosticsStore.Severity.CRASH) { severity = DiagnosticsStore.Severity.CRASH }
        }
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 10.dp)) {
            FilterChip("全部模块", module.isEmpty()) { module = "" }
            modules.forEach { FilterChip(it, module == it) { module = it } }
        }
        DhForm.Note("本地诊断占用 $usage · 每次加载 30 组")
        if (failed) Empty("日志读取失败，请运行日志自检。") else records?.let { current ->
            if (current.isEmpty()) Empty("没有符合条件的记录") else current.forEach { e -> EventCard(e) { open(e) } }
            if (hasMore) DhKit.ActionRow(if (loadingMore) "正在加载…" else "加载更多", iconRes = Icons.IC_CLOCK_3, desc = "读取后续错误分组", enabled = !loadingMore) {
                scope.launch {
                    loadingMore = true
                    val page = withContext(Dispatchers.IO) { DiagnosticsStore.queryPage(act, severity, module, query, current.size) }
                    records = current + page.items; hasMore = page.hasMore; loadingMore = false
                }
            }
        } ?: Empty("正在加载…")
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
        var detailsOpen by remember(e.eventId) { mutableStateOf(true) }
        var stackOpen by remember(e.eventId) { mutableStateOf(false) }
        var contextOpen by remember(e.eventId) { mutableStateOf(false) }
        DhKit.Card {
            DhForm.Badge(level(e.severity), DhTokens.colors.err, DhForm.semanticBg(DhTokens.colors.err))
            Text(e.message, Modifier.padding(top = 10.dp), color = DhTokens.colors.title, fontSize = 17.sp, fontFamily = DhTokens.fontsBold)
            DhKit.ValueRow("错误编号", e.code, iconRes = Icons.IC_CIRCLE_ALERT); DhKit.ValueRow("事件 ID", e.eventId, iconRes = Icons.IC_TAGS)
            DhKit.ValueRow("所属模块", e.module, iconRes = Icons.IC_BLOCKS); DhKit.ValueRow("发生时间", time(e.timestamp), iconRes = Icons.IC_CLOCK_3)
            DhKit.ValueRow("线程", e.thread.ifEmpty { "未采集" }, iconRes = Icons.IC_ACTIVITY); DhKit.ValueRow("异常类型", e.exceptionType.ifEmpty { "未采集" }, iconRes = Icons.IC_WARNING)
            DhKit.ValueRow("发生次数", "${e.count} 次", iconRes = Icons.IC_REFRESH); DhKit.ValueRow("首次发生", time(e.firstTimestamp), iconRes = Icons.IC_CLOCK_3)
            DhKit.ValueRow("关联操作 ID", e.operationId.ifEmpty { "未采集" }, iconRes = Icons.IC_LINK_2); DhKit.ValueRow("应用版本", e.appVersion.ifEmpty { "未采集" }, iconRes = Icons.IC_PACKAGE)
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticPanel("详情", e.readable(), detailsOpen, enabled = true) { detailsOpen = !detailsOpen }
                DiagnosticPanel("堆栈", e.stackTrace, stackOpen, enabled = e.stackTrace.isNotBlank()) { stackOpen = !stackOpen }
                DiagnosticPanel("关联日志", e.context, contextOpen, enabled = e.context.isNotBlank()) { contextOpen = !contextOpen }
            }
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

    @Composable private fun DiagnosticPanel(title: String, content: String, open: Boolean, enabled: Boolean, onClick: () -> Unit) {
        val visible = enabled && open && content.isNotBlank()
        val arrowRotation by animateFloatAsState(if (visible) 180f else 0f, label = "diagnosticArrow")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DhTokens.colors.soft).border(1.dp, DhTokens.colors.stroke, RoundedCornerShape(12.dp))) {
            Row(Modifier.fillMaxWidth().then(if (enabled && content.isNotBlank()) Modifier.pressable(onClick) else Modifier).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = if (enabled && content.isNotBlank()) DhTokens.colors.title else DhTokens.colors.sub, fontFamily = DhTokens.fontsBold)
                if (enabled && content.isNotBlank()) DhKit.Icon(Icons.IC_CHEVRON_DOWN, Design.SZ_SM, DhTokens.colors.sub, Modifier.rotate(arrowRotation))
            }
            AnimatedVisibility(visible = visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(content, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), color = DhTokens.colors.sub, fontSize = UiKit.FS_TINY.sp, lineHeight = 18.sp)
            }
        }
    }

    /**
     * 【v0.1.4】直接导出诊断包：从首页点击即开始，不跳二级页面。
     *
     * 【为什么抽成函数】原来这段逻辑埋在 ExportPage 的按钮里。现在首页直接调用，
     *   保留同一套行为（后台打包 → Toast 告知 → 拉起系统分享），只是少了一层页面。
     *   `onBusy` 回传忙碌状态给首页做「正在生成并校验…」的就地回显与防重复点击。
     */
    private fun runExport(act: Activity, onBusy: (Boolean) -> Unit) {
        onBusy(true)
        runCatching {
            DiagnosticsExporter.export(act, null) { result ->
                onBusy(false)
                result.onSuccess { r ->
                    AppToast.show(act, "诊断包已生成（${r.entries} 个文件），请选择保存或分享目标", long = true)
                    runCatching { act.startActivity(DiagnosticsExporter.shareIntent(act, r.uri)) }
                        .onFailure { t -> AppToast.show(act, "无法打开分享：" + t.message) }
                }.onFailure { t ->
                    AppToast.show(act, "导出失败：" + (t.message ?: t.javaClass.simpleName), long = true)
                }
            }
        }.onFailure { t ->
            onBusy(false)
            AppToast.show(act, "导出失败：" + (t.message ?: t.javaClass.simpleName), long = true)
        }
    }

    // 自检与系统退出详情不再作为独立用户页面；退出信息仍随 ZIP 导出。

    @Composable private fun SettingsPage(act: Activity, changed: () -> Unit) {
        var detailed by remember { mutableStateOf(Logs.isOn()) }
        var clear by remember { mutableStateOf(false) }
        var clearing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        DhKit.Card {
            DhKit.SwitchRow("记录详细运行日志", detailed, iconRes = Icons.IC_TERMINAL) { detailed = it; Logs.setOn(act, it); changed() }
            DhForm.Note("关闭时停止可选普通日志的持久化；警告、错误和未捕获崩溃仍保留基础记录。")
            DhKit.ValueRow("严重错误基础记录", "始终开启", iconRes = Icons.IC_SHIELD_CHECK); DhKit.ValueRow("错误前后关键事件", "有界内存缓冲，随错误保存", iconRes = Icons.IC_ACTIVITY)
            DhKit.ValueRow("日志保留策略", "7 天；单文件 1 MB；总量 8 MB", iconRes = Icons.IC_CLOCK_3)
            DhKit.ActionRow("清理 15 天前的诊断记录", iconRes = Icons.IC_TRASH, desc = "最近 15 天的日志与崩溃记录保持不变") { clear = true }
        }
        if (clear) DhForm.Alert("清理 15 天前的诊断记录？", { if (!clearing) clear = false }, "清理", {
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