package com.dollhouse.app.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 后台生成、校验并分享真实诊断包；不宣称系统分享后的保存结果。 */
object DiagnosticsExporter {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dollhouse-diagnostic-export").apply { isDaemon = true } }
    data class ExportResult(val uri: Uri, val entries: Int, val warnings: List<String>)

    fun export(context: Context, single: DiagnosticsStore.DiagnosticEvent? = null, callback: (Result<ExportResult>) -> Unit) {
        val app = context.applicationContext; val main = android.os.Handler(android.os.Looper.getMainLooper())
        worker.execute {
            val result = runCatching { create(app, single) }
            main.post { callback(result) }
        }
    }

    private fun create(app: Context, single: DiagnosticsStore.DiagnosticEvent?): ExportResult {
        val summary = DiagnosticsStore.summary(app)
        val events = single?.let { listOf(it) } ?: summary.groups
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(app.cacheDir, "crash-export").apply { if (!exists()) mkdirs() }
        val out = File(dir, "Dollhouse_Diagnostics_${versionName(app)}_$stamp.zip")
        val tmp = File(dir, out.name + ".tmp")
        val manifest = JSONArray()
        val warnings = ArrayList<String>()
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            fun add(path: String, text: String) {
                zip.putNextEntry(ZipEntry("Dollhouse_Diagnostics/$path"))
                zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                manifest.put(JSONObject().put("path", path).put("bytes", text.toByteArray(Charsets.UTF_8).size))
            }
            fun addFile(path: String, file: File) {
                zip.putNextEntry(ZipEntry("Dollhouse_Diagnostics/$path"))
                file.inputStream().buffered().use { it.copyTo(zip) }; zip.closeEntry()
                manifest.put(JSONObject().put("path", path).put("bytes", file.length()))
            }
            add("README.txt", "Dollhouse 本地诊断包\n\nmanifest.json：格式、版本与文件清单。\nsummary.json：按错误分组统计。\nlogs/：结构化事件。\nerrors/：错误详情、堆栈与关联上下文。\nsystem/：设备与系统退出信息。\n\n所有数据来自本机实际记录。缺失信息保持为空，不推断故障原因。默认不包含聊天正文、密钥或原始用户资源。导出仅代表压缩包生成成功，最终保存位置由系统界面决定。\n")
            val summaryJson = JSONObject().put("schemaVersion", DiagnosticsStore.SCHEMA)
                .put("errorGroups", summary.errors).put("warningGroups", summary.warnings)
                .put("crashGroups", summary.crashes).put("corruptedRecords", summary.corrupted)
                .put("selectedSingleError", single != null)
            add("summary.json", summaryJson.toString(2))
            events.forEachIndexed { i, e ->
                add("logs/event_%03d.json".format(Locale.US, i + 1), e.json().toString(2))
                add("errors/error_%03d.txt".format(Locale.US, i + 1), e.readable())
            }
            if (single == null) DiagnosticsStore.rawLogFiles(app).forEachIndexed { i, file ->
                runCatching { addFile("logs/raw_%03d.jsonl".format(Locale.US, i + 1), file) }
                    .onFailure { warnings += "${file.name}: ${it.javaClass.simpleName}" }
            }
            if (events.isEmpty() && single != null) add("logs/README.txt", "当前没有可导出的错误记录。\n")
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            val metrics = app.resources.displayMetrics
            val config = app.resources.configuration
            val memory = android.app.ActivityManager.MemoryInfo().also {
                (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
            }
            val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val system = JSONObject().put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND).put("model", Build.MODEL)
                .put("device", Build.DEVICE).put("product", Build.PRODUCT)
                .put("android", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT)
                .put("buildId", Build.ID).put("buildDisplay", Build.DISPLAY).put("buildType", Build.TYPE)
                .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("appVersion", versionName(app)).put("versionCode", packageInfo.longVersionCode)
                .put("appProcess", android.os.Process.myPid()).put("elapsedRealtimeMs", android.os.SystemClock.elapsedRealtime())
                .put("exportTime", System.currentTimeMillis()).put("timeZone", java.util.TimeZone.getDefault().id)
                .put("locale", config.locales.toString()).put("fontScale", config.fontScale)
                .put("nightMode", (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                .put("screenWidthPx", metrics.widthPixels).put("screenHeightPx", metrics.heightPixels)
                .put("densityDpi", metrics.densityDpi).put("density", metrics.density.toDouble())
                .put("availableMemoryBytes", memory.availMem).put("totalMemoryBytes", memory.totalMem).put("lowMemory", memory.lowMemory)
                .put("dataAvailableBytes", android.os.StatFs(app.filesDir.absolutePath).availableBytes)
                .put("dataTotalBytes", android.os.StatFs(app.filesDir.absolutePath).totalBytes)
                .put("overlayPermission", android.provider.Settings.canDrawOverlays(app))
                .put("notificationsEnabled", notificationManager.areNotificationsEnabled())
                .put("detailedLogs", Logs.isOn())
                .put("petServiceRunning", com.dollhouse.app.pet.PetToggle.isRunning(app))
                .put("shizukuBinderAvailable", runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false))
                .put("shizukuPermission", runCatching { rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false))
            add("system/environment.json", system.toString(2))
            val exits = JSONArray()
            if (single == null) DiagnosticsStore.exits(app).forEach { exits.put(JSONObject().put("timestamp", it.timestamp).put("reason", it.reason).put("description", it.description).put("trace", it.trace)) }
            add("system/exits.json", exits.toString(2))
            add("manifest.json", JSONObject().put("schemaVersion", DiagnosticsStore.SCHEMA).put("appVersion", versionName(app))
                .put("exportedAt", System.currentTimeMillis()).put("files", manifest).put("warnings", JSONArray(warnings)).toString(2))
        }
        ZipFile(tmp).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            require(names.contains("Dollhouse_Diagnostics/manifest.json")) { "ZIP 清单缺失" }
            require(zip.size() >= 6) { "ZIP 内容不完整" }
            zip.entries().asSequence().forEach { entry -> zip.getInputStream(entry).use { it.copyTo(java.io.OutputStream.nullOutputStream()) } }
        }
        if (out.exists() && !out.delete()) throw IllegalStateException("无法替换旧导出文件")
        if (!tmp.renameTo(out)) throw IllegalStateException("无法提交诊断包")
        return ExportResult(FileProvider.getUriForFile(app, app.packageName + ".fileprovider", out), ZipFile(out).use { it.size() }, warnings)
    }

    fun shareIntent(context: Context, uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Dollhouse 诊断包")
            clipData = ClipData.newRawUri("Dollhouse 诊断包", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "导出诊断包").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun versionName(app: Context): String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown")
}