package com.dollhouse.app.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 崩溃日志的异步汇总与系统分享。所有磁盘访问均离开主线程。 */
object CrashLogExporter {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dollhouse-crash-export").apply { isDaemon = true }
    }

    data class Summary(val count: Int, val latest: List<File>)

    fun loadSummary(context: Context, callback: (Summary) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching {
                val files = CrashGuard.listFiles(app)
                Summary(files.size, files.take(5))
            }.getOrElse { Summary(0, emptyList()) }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
        }
    }

    /** 返回值仅用于立即拒绝空记录；具体失败在回调里报告。 */
    fun export(context: Context, callback: (Result<Uri>) -> Unit) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        worker.execute {
            val result = runCatching {
                val files = CrashGuard.listFiles(app)
                require(files.isNotEmpty()) { "没有可导出的崩溃记录" }
                val dir = File(app.cacheDir, "crash-export").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val out = File(dir, "Dollhouse_crash_$stamp.txt")
                out.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write("Dollhouse 崩溃日志汇总\n导出时间: ")
                    writer.write(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    writer.write("\n机型: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ")
                    writer.write("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    writer.write("版本: ${version(app)}\n记录数: ${files.size}\n")
                    files.forEach { file ->
                        writer.write("\n\n========== ${file.name} ==========\n")
                        runCatching { file.bufferedReader(Charsets.UTF_8).use { it.copyTo(writer) } }
                            .onFailure { writer.write("[此记录读取失败：${it.javaClass.simpleName}]\n") }
                    }
                }
                FileProvider.getUriForFile(app, app.packageName + ".fileprovider", out)
            }
            main.post { callback(result) }
        }
    }

    /** FileProvider + ClipData + grant 缺一不可，兼容小米/HyperOS 的系统分享。 */
    fun shareIntent(context: Context, uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Dollhouse 崩溃日志")
            clipData = ClipData.newRawUri("Dollhouse 崩溃日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "导出错误日志").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun version(app: Context): String = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrElse { "未知" }
}