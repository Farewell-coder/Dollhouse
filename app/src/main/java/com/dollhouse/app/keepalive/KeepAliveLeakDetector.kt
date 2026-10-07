package com.dollhouse.app.keepalive

import android.app.job.JobScheduler

/**
 * 【职责】保活模块的自检断言：把「注册 / 注销必须对称」这条不变式写成可执行检查。
 *
 * 【为什么不是普通日志】开关是反复拨的：每开一次注册一次 Job、每关一次注销一次，
 *   一旦某条路径漏注销，就会在系统里堆出多条任务，症状是「关掉开关后依然被拉活」。
 *   这类问题只靠肉眼看代码很难发现，用断言直接查 JobScheduler 的真实状态最可靠。
 *
 * 【怎么保证 release 体积不涨】[ASSERT_ON] 是 const val（编译期常量）：
 *   release 下 R8 会把整个方法体判为不可达并连同调用点一起删掉；
 *   只有把本值改成 true 重编，才会得到带自检的包（排障用）。
 *
 * 【坑】JobScheduler.getPendingJob 在 API 24+ 可用（minSdk 24 恰好覆盖）。
 */
internal object KeepAliveLeakDetector {

    /** 自检总开关。release 保持 false：整段逻辑被 R8 剔除。 */
    private const val ASSERT_ON = false

    /**
     * 校验 Job 的注册状态与预期一致。
     * @param expectScheduled true = 应当存在；false = 应当已注销。
     */
    fun assertJobState(js: JobScheduler?, jobId: Int, expectScheduled: Boolean) {
        if (!ASSERT_ON) {
            return
        }
        val actual = try {
            js?.getPendingJob(jobId) != null
        } catch (t: Throwable) {
            return
        }
        if (actual != expectScheduled) {
            // 走 warn 而非抛异常：真机上宁可留证据，也不能因为自检把主流程打断。
            KeepAliveLog.w("assertJobState failed: job=$jobId expect=$expectScheduled actual=$actual")
        }
    }

    /** 校验网络回调的注册状态（[NetworkMonitor] 内部持有，这里只做计数语义检查）。 */
    fun assertCallbackSymmetry(registered: Boolean, hasCallback: Boolean) {
        if (!ASSERT_ON) {
            return
        }
        if (registered != hasCallback) {
            KeepAliveLog.w("network callback state mismatch: registered=$registered hasCallback=$hasCallback")
        }
    }
}