package com.dollhouse.app.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 本地诊断唯一数据源。严重错误始终落盘，详细日志由 Logs 开关控制。 */
object DiagnosticsStore {
    const val SCHEMA = 1
    private const val DIR = "diagnostics"
    private const val FILE = "events.jsonl"
    private const val MAX_FILE = 1024L * 1024L
    private const val MAX_TOTAL = 8L * 1024L * 1024L
    private const val RETENTION = 7L * 24L * 3600L * 1000L
    private const val CONTEXT_MAX = 120
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dollhouse-diagnostics").apply { isDaemon = true } }
    private val contextEvents = ArrayDeque<DiagnosticEvent>()
    private val contextLock = Any()
    private val lastFailure = AtomicBoolean(false)

    enum class Severity(val wire: String) { INFO("info"), WARNING("warning"), ERROR("error"), CRASH("crash") }

    data class DiagnosticEvent(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val severity: Severity,
        val category: String = "runtime",
        val module: String,
        val message: String,
        val thread: String = Thread.currentThread().name,
        val exceptionType: String = "",
        val stackTrace: String = "",
        val operationId: String = "",
        val appVersion: String = "",
        val context: String = "",
        val count: Int = 1,
        val firstTimestamp: Long = timestamp,
        val legacyFile: String = ""
    ) {
        val code: String get() = when (severity) {
            Severity.CRASH -> "CRASH-" + stableHash(module + exceptionType + message)
            Severity.WARNING -> "WARN-" + stableHash(module + exceptionType + message)
            else -> "ERR-" + stableHash(module + exceptionType + message)
        }
        fun json(): JSONObject = JSONObject().apply {
            put("schemaVersion", SCHEMA); put("eventId", eventId); put("timestamp", timestamp)
            put("severity", severity.wire); put("category", category); put("module", module)
            put("message", message); put("thread", thread); put("exceptionType", exceptionType)
            put("stackTrace", stackTrace); put("operationId", operationId); put("appVersion", appVersion)
            put("context", context)
        }
        fun readable(): String = buildString {
            append("错误编号：").append(code).append("\n事件 ID：").append(eventId)
            append("\n级别：").append(severity.wire).append("\n模块：").append(module)
            append("\n时间：").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(timestamp)))
            append("\n线程：").append(thread).append("\n摘要：").append(message)
            if (exceptionType.isNotEmpty()) append("\n异常：").append(exceptionType)
            if (operationId.isNotEmpty()) append("\n关联操作：").append(operationId)
            append("\n发生次数：").append(count)
            if (appVersion.isNotEmpty()) append("\n应用版本：").append(appVersion)
            if (stackTrace.isNotEmpty()) append("\n\n完整堆栈：\n").append(stackTrace)
            if (context.isNotEmpty()) append("\n\n关联日志：\n").append(context)
        }
        companion object {
            fun parse(line: String): DiagnosticEvent? = runCatching {
                val o = JSONObject(line)
                DiagnosticEvent(
                    eventId = o.optString("eventId"), timestamp = o.optLong("timestamp"),
                    severity = Severity.values().firstOrNull { it.wire == o.optString("severity") } ?: return null,
                    category = o.optString("category"), module = o.optString("module"),
                    message = o.optString("message"), thread = o.optString("thread"),
                    exceptionType = o.optString("exceptionType"), stackTrace = o.optString("stackTrace"),
                    operationId = o.optString("operationId"), appVersion = o.optString("appVersion"),
                    context = o.optString("context")
                ).takeIf { it.eventId.isNotEmpty() && it.module.isNotEmpty() && it.message.isNotEmpty() }
            }.getOrNull()
        }
    }

    data class Summary(val errors: Int, val warnings: Int, val crashes: Int, val groups: List<DiagnosticEvent>, val corrupted: Int, val bytes: Long)
    data class ExitRecord(val timestamp: Long, val reason: String, val description: String, val trace: String = "")
    data class SelfCheck(val ok: Boolean, val items: List<Pair<String, String>>, val checkedAt: Long = System.currentTimeMillis())

    fun record(ctx: Context?, severity: Severity, module: String, message: String, throwable: Throwable? = null,
               category: String = "runtime", operationId: String = "") {
        val app = ctx?.applicationContext ?: return
        val event = DiagnosticEvent(severity = severity, category = category, module = sanitize(module).take(80),
            message = sanitize(message).take(4000), exceptionType = throwable?.javaClass?.name ?: "",
            stackTrace = throwable?.let { stack(it) }?.let { sanitize(it).take(65536) } ?: "",
            operationId = sanitize(operationId).take(100), appVersion = version(app),
            context = if (severity == Severity.ERROR || severity == Severity.CRASH) recentContext() else "")
        synchronized(contextLock) {
            contextEvents.addLast(event.copy(stackTrace = "", context = ""))
            while (contextEvents.size > CONTEXT_MAX) contextEvents.removeFirst()
        }
        if (severity == Severity.INFO && !Logs.isOn()) return
        if (severity == Severity.CRASH) writeSync(app, event) else worker.execute { runCatching { write(app, event) }.onFailure { lastFailure.set(true) } }
    }

    /** 崩溃处理器使用：不排队，直接完成关键记录，避免进程退出丢失。 */
    fun crash(ctx: Context, thread: Thread, throwable: Throwable) {
        record(ctx, Severity.INFO, "CrashGuard", "检测到未捕获异常")
        val event = DiagnosticEvent(severity = Severity.CRASH, category = "uncaught", module = "CrashGuard",
            message = throwable.message ?: "未捕获异常", thread = thread.name,
            exceptionType = throwable.javaClass.name, stackTrace = sanitize(stack(throwable)).take(65536),
            appVersion = version(ctx), context = recentContext())
        runCatching { writeSync(ctx.applicationContext, event) }
    }

    private fun writeSync(ctx: Context, event: DiagnosticEvent) {
        val latch = CountDownLatch(1)
        worker.execute { try { write(ctx, event) } catch (_: Throwable) { lastFailure.set(true) } finally { latch.countDown() } }
        latch.await(2, TimeUnit.SECONDS)
    }

    @Synchronized private fun write(ctx: Context, event: DiagnosticEvent) {
        val dir = dir(ctx); if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建诊断目录")
        rotateIfNeeded(dir, FILE, event.json().toString().length + 1)
        File(dir, FILE).appendText(event.json().toString() + "\n", Charsets.UTF_8)
        prune(dir); lastFailure.set(false)
    }

    private fun rotateIfNeeded(dir: File, name: String, incoming: Int) {
        val f = File(dir, name)
        if (f.exists() && f.length() + incoming > MAX_FILE) {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
            val dst = File(dir, "events_$stamp.jsonl")
            if (!f.renameTo(dst)) throw IllegalStateException("日志轮转失败")
        }
    }

    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - RETENTION
        val files = logs(dir).sortedBy { it.lastModified() }
        files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        var total = logs(dir).sumOf { it.length() }
        logs(dir).sortedBy { it.lastModified() }.filter { it.name != FILE }.forEach {
            if (total <= MAX_TOTAL) return@forEach
            val size = it.length(); if (it.delete()) total -= size
        }
    }

    fun summary(ctx: Context): Summary {
        val app = ctx.applicationContext; val all = ArrayList<DiagnosticEvent>(); var corrupt = 0; var bytes = 0L
        logs(dir(app)).forEach { f ->
            bytes += f.length()
            runCatching { f.forEachLine(Charsets.UTF_8) { val e = DiagnosticEvent.parse(it); if (e == null) corrupt++ else all += e } }
                .onFailure { corrupt++ }
        }
        CrashGuard.listFiles(app).forEach { f ->
            bytes += f.length()
            runCatching {
                val text = f.readText(Charsets.UTF_8)
                all += DiagnosticEvent(severity = Severity.CRASH, category = "legacy", module = "历史崩溃记录",
                    message = text.lineSequence().firstOrNull { it.startsWith("信息:") }?.substringAfter("信息:")?.trim() ?: "未捕获异常",
                    timestamp = f.lastModified(), thread = text.lineSequence().firstOrNull { it.startsWith("线程:") } ?: "未记录",
                    exceptionType = text.lineSequence().firstOrNull { it.startsWith("异常:") }?.substringAfter("异常:")?.trim() ?: "",
                    stackTrace = text.substringAfter("---------- 堆栈 ----------\n", ""), legacyFile = f.absolutePath,
                    appVersion = text.lineSequence().firstOrNull { it.startsWith("版本:") }?.substringAfter("版本:")?.trim() ?: "")
            }.onFailure { corrupt++ }
        }
        val groups = all.filter { it.severity != Severity.INFO }.groupBy { it.severity to (it.module + it.exceptionType + it.message) }
            .map { (_, v) ->
                val latest = v.maxByOrNull { it.timestamp }!!
                val contextual = v.filter { it.context.isNotEmpty() || it.stackTrace.isNotEmpty() }.maxByOrNull { it.timestamp }
                latest.copy(count = v.size, firstTimestamp = v.minOf { it.timestamp },
                    context = contextual?.context ?: latest.context, stackTrace = contextual?.stackTrace ?: latest.stackTrace)
            }
            .sortedByDescending { it.timestamp }
        return Summary(groups.count { it.severity == Severity.ERROR }, groups.count { it.severity == Severity.WARNING },
            groups.count { it.severity == Severity.CRASH }, groups, corrupt, bytes)
    }

    /** 原始结构化日志仅供后台导出流式读取，避免把普通日志丢弃。 */
    internal fun rawLogFiles(ctx: Context): List<File> = logs(dir(ctx)).sortedBy { it.lastModified() }

    fun query(ctx: Context, severity: Severity? = null, text: String = "", offset: Int = 0, limit: Int = 60): List<DiagnosticEvent> {
        val q = text.trim().lowercase()
        return summary(ctx).groups.asSequence().filter { severity == null || it.severity == severity }
            .filter { q.isEmpty() || listOf(it.message, it.module, it.code, it.exceptionType, it.category).joinToString(" ").lowercase().contains(q) }
            .drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 200)).toList()
    }

    fun exits(ctx: Context): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 20).map {
                ExitRecord(it.timestamp, exitReason(it.reason), it.description ?: "系统未提供说明", "")
            }
        }.getOrDefault(emptyList())
    }

    fun selfCheck(ctx: Context): SelfCheck {
        val app = ctx.applicationContext; val s = summary(app)
        val items = arrayListOf<Pair<String, String>>()
        items += "全局异常处理器" to if (CrashGuard.isInstalled()) "已配置；仅覆盖未捕获异常" else "未配置"
        items += "业务错误记录" to "已接入统一日志出口；覆盖率不等同于所有异常"
        items += "日志文件完整性" to if (s.corrupted == 0) "可读，未发现损坏行" else "发现 ${s.corrupted} 条损坏或不可读记录"
        items += "日志写入状态" to if (lastFailure.get()) "最近一次写入失败" else "未发现最近写入失败"
        items += "存储空间与轮转" to "${s.bytes / 1024} KB / 8192 KB；单文件 1024 KB；保留 7 天"
        items += "系统退出原因" to if (Build.VERSION.SDK_INT >= 30) "可查询；结果取决于系统保留情况" else "当前 Android 版本不支持"
        items += "诊断包导出" to "可生成并验证 ZIP；实际保存由系统分享界面完成"
        return SelfCheck(!lastFailure.get() && s.corrupted == 0 && CrashGuard.isInstalled(), items)
    }

    fun clear(ctx: Context): Boolean {
        val latch = CountDownLatch(1); var ok = false
        worker.execute {
            ok = runCatching { logs(dir(ctx)).all { !it.exists() || it.delete() } && CrashGuard.clear(ctx) }.getOrDefault(false)
            synchronized(contextLock) { contextEvents.clear() }; latch.countDown()
        }
        return runCatching { latch.await(5, TimeUnit.SECONDS); ok }.getOrDefault(false)
    }

    internal fun recentContext(): String = synchronized(contextLock) {
        contextEvents.toList().takeLast(30).joinToString("\n") {
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(it.timestamp)) + " [${it.severity.wire}] ${it.module}: ${it.message}"
        }
    }

    internal fun sanitize(input: String): String = DiagnosticsFormat.redact(input)
    private fun stack(t: Throwable): String { val sw = java.io.StringWriter(); t.printStackTrace(java.io.PrintWriter(sw)); return sw.toString() }
    private fun stableHash(s: String): String = (s.hashCode().toLong() and 0xffffffffL).toString(16).padStart(8, '0').uppercase()
    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)
    internal fun logs(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") } ?: emptyList()
    private fun version(ctx: Context): String = runCatching { val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0); "${p.versionName} (${p.longVersionCode})" }.getOrDefault("未知")
    private fun exitReason(reason: Int): String = when (reason) {
        1 -> "应用主动退出"; 2 -> "被信号终止"; 3 -> "低内存回收"; 4 -> "应用未捕获异常"; 5 -> "原生代码崩溃"
        6 -> "ANR"; 7 -> "初始化失败"; 8 -> "权限变化"; 9 -> "资源耗尽"; 10 -> "用户主动结束"; 11 -> "用户停止应用"
        12 -> "依赖组件退出"; 13 -> "其他系统终止"; 14 -> "应用冻结"; 15 -> "包状态变化"; 16 -> "应用更新"
        else -> "无法确定（系统原因码 $reason）"
    }
}
