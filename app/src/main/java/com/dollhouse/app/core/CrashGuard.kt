package com.dollhouse.app.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 【职责】进程级崩溃兜底：逮住未捕获异常，落一份现场记录，然后交还给系统默认处理。
 *
 * 【为什么必须有】改造前工程**没有任何** UncaughtExceptionHandler：一旦某个后台线程
 *   （帧循环 / OCR 线程池 / 网络回调 / 保活 Job）抛出未捕获异常，整个进程静默死亡 ——
 *   用户看到的现象就是「人偶突然不见了」，而开发者这边**一条线索都没有**
 *   （release 包 Logs.ON=false，logcat 也是空的）。这是排障能力的最大缺口。
 *
 * 【为什么不依赖 Logs.ON】崩溃记录**必须无条件写入**：
 *   · 崩溃是低频事件，写一次文件的开销可忽略；
 *   · 恰恰是「出了事才需要」，跟着调试开关走等于把唯一的现场也一并关掉。
 *   所以本类直接写文件，不走 [Logs]。
 *
 * 【为什么不自己弹界面 / 重启进程】按最小惊讶原则：记录完就交回系统默认流程
 *   （系统会弹崩溃对话框、按自身策略处理）。本类只负责「留下证据」，不改变崩溃语义。
 *
 * 【留存策略】最多保留 [MAX_FILES] 份，新的覆盖最旧的；位置在应用私有 cacheDir，
 *   不占用户可见空间，也随「清除缓存」一并清掉。
 */
object CrashGuard {
    @JvmStatic
    fun isInstalled(): Boolean = installed

    private const val TAG = "DollhouseCrash"

    /** 崩溃记录目录（应用私有 cache 下）。 */
    private const val DIR = "crash"

    /** 最多保留几份现场。 */
    private const val MAX_FILES = 10

    /** 单份现场上限（超长堆栈截断，避免极端情况下写出巨型文件）。 */
    private const val MAX_CHARS = 64 * 1024

    @Volatile
    private var installed = false

    /** 上一次崩溃的简要摘要（供界面提示用）；没有崩溃过则为 null。 */
    @Volatile
    private var lastSummary: String? = null

    /**
     * 装上兜底处理器。幂等 —— 重复调用只生效一次。
     * 【必须在 Application.onCreate 里调用】越早越好，否则早期崩溃逮不到。
     */
    @JvmStatic
    fun install(ctx: Context?) {
        if (ctx == null || installed) {
            return
        }
        val app = ctx.applicationContext
        synchronized(CrashGuard) {
            if (installed) {
                return
            }
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    DiagnosticsStore.crash(app, thread, throwable)
                    record(app, thread, throwable)
                } catch (ignored: Throwable) {
                    // 兜底里的兜底：记录失败绝不能再抛，否则会盖掉原始崩溃。
                }
                // 【交还】按原样转给系统默认处理器，保持崩溃行为不变。
                if (prev != null) {
                    prev.uncaughtException(thread, throwable)
                }
            }
            installed = true
        }
    }

    /** 是否有崩溃记录（供「上次异常退出」提示用）。 */
    @JvmStatic
    fun hasRecord(ctx: Context?): Boolean {
        return listFiles(ctx).isNotEmpty()
    }

    /** 最近一次崩溃的摘要文字；没有则返回 null。 */
    @JvmStatic
    fun lastRecordSummary(ctx: Context?): String? {
        lastSummary?.let { return it }
        val f = listFiles(ctx).maxByOrNull { it.lastModified() } ?: return null
        return try {
            f.readText().lineSequence().take(6).joinToString("\n")
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 清掉全部崩溃记录。返回是否真的没有留下任何文件，调用方不得盲报成功。 */
    @JvmStatic
    fun clear(ctx: Context?): Boolean {
        for (f in listFiles(ctx)) {
            try {
                if (f.exists() && !f.delete()) {
                    // 保留 lastSummary，因为至少还有一份现场没有删掉。
                    return false
                }
            } catch (ignored: Throwable) {
                return false
            }
        }
        val remaining = listFiles(ctx).isNotEmpty()
        if (!remaining) lastSummary = null
        return !remaining
    }

    /** 崩溃记录文件列表（按修改时间倒序）。 */
    @JvmStatic
    fun listFiles(ctx: Context?): List<File> {
        val app = ctx?.applicationContext ?: return emptyList()
        val dir = File(app.cacheDir, DIR)
        val arr = dir.listFiles() ?: return emptyList()
        return arr.filter { it.isFile }.sortedByDescending { it.lastModified() }
    }

    // ============================ 内部 ============================

    private fun record(app: Context, thread: Thread, t: Throwable) {
        val dir = File(app.cacheDir, DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val text = buildText(app, thread, t)
        lastSummary = text.lineSequence().take(6).joinToString("\n")

        val out = File(dir, "crash_$stamp.txt")
        try {
            out.writeText(text)
        } catch (ignored: Throwable) {
            return
        }
        prune(dir)
    }

    private fun buildText(app: Context, thread: Thread, t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stack = sw.toString()
        val head = StringBuilder()
        head.append("时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        head.append("线程: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
        head.append("异常: ").append(t.javaClass.name).append('\n')
        head.append("信息: ").append(t.message ?: "(无)").append('\n')
        head.append("版本: ").append(appVersion(app)).append('\n')
        head.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        head.append("---------- 堆栈 ----------\n")
        head.append(stack)
        val all = head.toString()
        return if (all.length > MAX_CHARS) all.substring(0, MAX_CHARS) + "\n…(已截断)" else all
    }

    private fun appVersion(app: Context): String {
        return try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            pi.versionName + " (" + pi.longVersionCode + ")"
        } catch (ignored: Throwable) {
            "未知"
        }
    }

    /** 只留最近 [MAX_FILES] 份。 */
    private fun prune(dir: File) {
        val all = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        if (all.size <= MAX_FILES) {
            return
        }
        for (i in MAX_FILES until all.size) {
            try {
                all[i].delete()
            } catch (ignored: Throwable) {
            }
        }
    }
}