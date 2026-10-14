package com.dollhouse.app.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dollhouse.app.core.Logs
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 【职责】更新检查的调度层：**什么时候查**（节流）、**在哪个线程查**、**结果给谁**。
 *
 * 【分层】本类不含任何 JSON / HTTP 细节（那在 [UpdateRepo]），也不含任何 UI
 *   （那在 `AboutPage`）。它只做三件事：
 *     1. 自动检查的 24h 节流与冷启动延迟；
 *     2. 手动检查的 60s 防抖；
 *     3. 把结果切回主线程回调。
 *
 * 【线程模型】单线程池串行执行 —— 同一时刻只可能有一次检查在跑，
 *   天然避免「冷启动自动检查」与「用户手点」并发打架。
 *   结果经 [Handler] 回主线程，调用方不必自己 post。
 *
 * 【为什么不用协程】工程里网络类任务（OcrEngine / DeepSeekClient / ShizukuBridge）
 *   统一是「Executor + Main Handler」这套；这里跟随既有惯例，不引入第二套并发风格。
 *
 * 【失败静默】自动检查失败**绝不弹窗**（只记日志）——需求要求「任何异常不影响
 *   应用启动与其他功能」。手动检查失败才提示，因为那是用户主动发起的。
 */
object UpdateChecker {

    private const val LOG_TAG = "Dollhouse"

    /** 冷启动延迟：主界面就绪后 3s 再查，不与启动耗时抢资源（需求 3–5s 区间）。 */
    private const val STARTUP_DELAY_MS = 3000L

    private val POOL = Executors.newSingleThreadExecutor(object : ThreadFactory {
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "dollhouse-update")
            t.isDaemon = true
            return t
        }
    })

    private val MAIN = Handler(Looper.getMainLooper())

    /** 有检查在跑：避免重复请求（自动 + 手动同时触发时只跑一次）。 */
    private val busy = AtomicBoolean(false)

    /** 本进程内是否已经做过冷启动自动检查。 */
    private val startupDone = AtomicBoolean(false)

    /** 连续点击手动检查的 UI 层拦截：记住上次手动发起时刻（单调时钟，不受系统时间调整影响）。 */
    private var lastManualUptime = 0L

    /* ==================== 自动检查 ==================== */

    /**
     * 冷启动自动检查。幂等：同一次进程生命周期内只会真正执行一次。
     *
     * 【调用时机】`MainActivity.onResume` 尾部 —— 界面已经可见，延迟 [STARTUP_DELAY_MS]
     *   后开始，不阻塞启动（需求 4.3「主界面就绪后 3–5s，不阻塞启动」）。
     *
     * 【结果处理】只有在「有新版本」时才回调 [onAvailable]（用于弹更新提示）；
     *   已最新与失败都静默 —— 自动检查打扰用户是负体验。
     */
    fun autoCheck(ctx: Context, onAvailable: (UpdateCheckResult.Available) -> Unit) {
        val app = ctx.applicationContext
        if (!UpdatePrefs.autoCheck(app)) {
            return
        }
        if (!startupDone.compareAndSet(false, true)) {
            return
        }
        if (!UpdatePrefs.autoCheckDue(app, System.currentTimeMillis())) {
            Logs.i(LOG_TAG, "update: 距上次检查不足 24h，跳过自动检查")
            return
        }
        MAIN.postDelayed({
            check(app, manual = false) { result ->
                if (result is UpdateCheckResult.Available) {
                    // 用户明确忽略过这个版本 → 不打扰（但换了更高版本仍会提醒）。
                    if (isSkipped(app, result)) {
                        Logs.i(LOG_TAG, "update: ${result.candidate.versionName} 已被忽略，跳过提示")
                        return@check
                    }
                    onAvailable(result)
                }
            }
        }, STARTUP_DELAY_MS)
    }

    /* ==================== 手动检查 ==================== */

    /**
     * 手动检查（关于页按钮）。绕过节流，但带 60s 防抖。
     *
     * @param onResult **无论成功失败**都会回调（含 [UpdateCheckResult.Failed]），
     *                 因为这是用户主动发起的操作，必须给反馈。
     *                 防抖拦截时会回调 `null`。
     */
    fun manualCheck(ctx: Context, onResult: (UpdateCheckResult?) -> Unit) {
        val app = ctx.applicationContext
        val now = SystemClock.uptimeMillis()
        if (lastManualUptime > 0L && now - lastManualUptime < UpdatePrefs.MANUAL_DEBOUNCE_MS) {
            Logs.i(LOG_TAG, "update: 手动检查防抖中")
            MAIN.post { onResult(null) }
            return
        }
        lastManualUptime = now
        check(app, manual = true, callback = onResult)
    }

    /* ==================== 核心 ==================== */

    /**
     * 真正执行一次检查。
     *
     * @param manual true = 手动（失败也回调）；false = 自动（失败静默）
     */
    private fun check(
        app: Context,
        manual: Boolean,
        callback: (UpdateCheckResult) -> Unit
    ) {
        if (!busy.compareAndSet(false, true)) {
            Logs.i(LOG_TAG, "update: 已有检查在进行，本次跳过")
            if (manual) {
                MAIN.post { callback(UpdateCheckResult.Failed(UpdateError.Network("正在检查中，请稍候"))) }
            }
            return
        }
        val channel = UpdatePrefs.channel(app)
        val current = currentVersion(app)
        POOL.execute {
            val result = try {
                UpdateRepo.fetch(channel, current)
            } catch (t: Throwable) {
                // fetch 内部已分类捕获，这里是最后一道防线。
                UpdateCheckResult.Failed(UpdateError.Network(t.message))
            }
            busy.set(false)
            // 【只在真正查过之后才记时间】失败也记 —— 否则断网时会 24h 内反复重试，
            //   既费电又可能触发 GitHub 限流。手动检查会在必要时覆盖这个时间。
            if (manual || result !is UpdateCheckResult.Failed) {
                UpdatePrefs.setLastCheckMs(app, System.currentTimeMillis())
            }
            logResult(channel, current, result, manual)
            MAIN.post { callback(result) }
        }
    }

    /** 当前版本号：从 PackageManager 现取，避免与 build.gradle 硬编码两处不同步。 */
    fun currentVersion(ctx: Context): String {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /** 该候选是否正好是用户忽略过的那个版本。 */
    private fun isSkipped(ctx: Context, available: UpdateCheckResult.Available): Boolean {
        val skipped = UpdatePrefs.skippedVersion(ctx)
        if (skipped.isEmpty()) {
            return false
        }
        // 精确比较版本号：用户忽略 0.0.7 之后，出现 0.0.8 仍要提醒。
        return SemVer.compare(available.candidate.versionName, skipped) == 0
    }

    private fun logResult(
        channel: UpdateChannel,
        current: String,
        result: UpdateCheckResult,
        manual: Boolean
    ) {
        val src = if (manual) "手动" else "自动"
        when (result) {
            is UpdateCheckResult.Available ->
                Logs.i(
                    LOG_TAG,
                    "update[$src/${channel.label}]: 发现新版本 ${result.candidate.versionName}（当前 $current）"
                )
            is UpdateCheckResult.UpToDate ->
                Logs.i(LOG_TAG, "update[$src/${channel.label}]: 已是最新（$current）")
            is UpdateCheckResult.Failed ->
                Logs.w(LOG_TAG, "update[$src/${channel.label}]: 检查失败 - ${result.error.message}")
        }
    }
}