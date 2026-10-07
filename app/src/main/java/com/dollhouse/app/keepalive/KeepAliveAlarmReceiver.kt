package com.dollhouse.app.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 【职责】保活周期的兜底通道（AlarmManager）。
 *
 * 【入口】每 6 小时一次系统广播。
 *
 * 【为什么需要】部分厂商 ROM 会冻结 JobScheduler，Job 长期不调度；
 *   AlarmManager 走的是另一条系统通道，两者互为保险。
 *
 * 【交互】只把活交回 [KeepAliveRunner]，不直接操作服务。
 *
 * 【坑】onReceive 必须在 10 秒内返回，因此不得在此做同步探测；
 *   拉起动作全部在 Runner 的协程里异步完成。
 */
@AndroidEntryPoint
class KeepAliveAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var runner: KeepAliveRunner

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            runner.runOnce("alarm")
        } catch (t: Throwable) {
            KeepAliveLog.w("alarm receiver failed", t)
        }
    }
}
