package com.dollhouse.app.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 【职责】保活周期的系统调度入口（JobScheduler）。
 *
 * 【入口】系统按 15 分钟周期绑定并调用 onStartJob。
 *
 * 【交互】把活交回 [KeepAliveRunner]；无论结果如何都返回 jobFinished(false)（不需要重试，
 *   下一轮周期会再跑），不在本方法内做任何 IO。
 *
 * 【坑】onStartJob 运行在主线程，系统给整个 job 只有 10 分钟；本类内部不阻塞、不等待，
 *   因此不存在 ANR 与超时风险。
 */
@AndroidEntryPoint
class KeepAliveJobService : JobService() {

    @Inject lateinit var runner: KeepAliveRunner

    override fun onStartJob(params: JobParameters?): Boolean {
        runner.runOnce("job")
        // false：工作已交给异步流程，且不需要系统重试。
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // 系统提前取消：不需要补跑（下一个周期会覆盖）。
        return false
    }
}
