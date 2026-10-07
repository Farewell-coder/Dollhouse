package com.dollhouse.app.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 【职责】注册 / 注销保活周期的两条通道（JobScheduler 主 + AlarmManager 兜底）。
 *
 * 【入口】[KeepAliveFacade.setEnabled] 与 [DollhouseApp] 启动时各调一次；
 *   本类所有方法幂等，重复调用不会堆叠出多条任务。
 *
 * 【交互】Job 落到 [KeepAliveJobService]，Alarm 落到 [KeepAliveAlarmReceiver]；
 *   两者最终都调 [KeepAliveRunner.runOnce]。
 *
 * 【数值依据】
 *   - Job 周期 15 分钟：系统允许的最小周期，与 Doze maintenance window 同频，不额外拉高唤醒频次；
 *   - flex 5 分钟：留出与其他 job 合并的机会，降低唤醒次数；
 *   - Alarm 6 小时：仅当厂商冻结了 JobScheduler 时才作为兜底，每天最多 4 次；
 *   - 不使用 setExactAndAllowWhileIdle：它有 9 分钟频次上限、耗电高，且需 SCHEDULE_EXACT_ALARM 权限。
 *
 * 【坑】Job 的 setPersisted(true) 要求清单里已声明 RECEIVE_BOOT_COMPLETED（已声明）。
 * 【坑】PendingIntent 必须显式 FLAG_IMMUTABLE（API 31+ 强制），否则直接抛异常。
 */
@Singleton
class KeepAliveScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val JOB_ID = 0x0D0111
        private const val ALARM_REQUEST = 0x0D0112
        // 【为什么必须另开一个 request code】AlarmManager 对同一个 PendingIntent
        //   只能保留最后一次排定；复用 ALARM_REQUEST 会把 6 小时周期闹钟顶掉。
        private const val BACKOFF_REQUEST = 0x0D0113
        private const val JOB_PERIOD_MS = 15L * 60L * 1000L
        private const val JOB_FLEX_MS = 5L * 60L * 1000L
        private const val ALARM_PERIOD_MS = 6L * 60L * 60L * 1000L
    }

    private val jobScheduler: JobScheduler?
        get() = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** 注册两条通道。已注册则先覆盖（幂等）。 */
    fun schedule() {
        scheduleJob()
        scheduleAlarm()
    }

    /** 注销两条通道，并清掉可能残留的 PendingIntent。 */
    fun cancel() {
        try {
            jobScheduler?.cancel(JOB_ID)
        } catch (t: Throwable) {
            KeepAliveLog.w("cancel job failed", t)
        }
        try {
            // FLAG_NO_CREATE：只取已存在的那个，取不到说明本来就没注册。
            // 【坑】这里绝不能用 UPDATE_CURRENT —— 那会在注销时反过来新建一个 PendingIntent。
            alarmPendingIntent(ALARM_REQUEST, false)?.let { pi -> alarmManager?.cancel(pi) }
            // 退避重试闹钟也要一并清掉，否则「关开关」后还可能被它捞起来。
            alarmPendingIntent(BACKOFF_REQUEST, false)?.let { pi -> alarmManager?.cancel(pi) }
        } catch (t: Throwable) {
            KeepAliveLog.w("cancel alarm failed", t)
        }
    }

    private fun scheduleJob() {
        try {
            val js = jobScheduler ?: return
            val component = ComponentName(context, KeepAliveJobService::class.java)
            val builder = JobInfo.Builder(JOB_ID, component)
                .setPersisted(true)
                // NONE：存活判定不依赖网络，避免无网时 job 被无限推迟到有网才跑。
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPeriodic(JOB_PERIOD_MS, JOB_FLEX_MS)
            // 已注册同 ID 时先取消再注册，保证参数被更新，同时不产生重复项。
            js.cancel(JOB_ID)
            js.schedule(builder.build())
        } catch (t: Throwable) {
            KeepAliveLog.w("schedule job failed", t)
        }
    }

    private fun scheduleAlarm() {
        try {
            val am = alarmManager ?: return
            // 统一用 inexact：不申请 SCHEDULE_EXACT_ALARM，避免审核摩擦与高耗电。
            // 先取出非空 PendingIntent；拿不到（系统异常）就放弃本次注册，由 Job 通道兜底。
            val pi = alarmPendingIntent(ALARM_REQUEST, true) ?: return
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + ALARM_PERIOD_MS,
                ALARM_PERIOD_MS,
                pi
            )
        } catch (t: Throwable) {
            KeepAliveLog.w("schedule alarm failed", t)
        }
    }

    /**
     * 构造 Alarm 的 PendingIntent。
     *
     * @param create true  = 注册用：FLAG_UPDATE_CURRENT，已存在则更新触发时间；
     *               false = 注销用：FLAG_NO_CREATE，不存在则返回 null，**绝不新建**。
     *
     * 【为什么用布尔而不是 extraFlags】两种场景的 flags 语义相反（更新 vs 只取），
     *   用同一个「extraFlags 传 0」入参无法表达 NO_CREATE，会让注销反而创建出新实例。
     */
    private fun alarmPendingIntent(requestCode: Int, create: Boolean): PendingIntent? {
        val intent = Intent(context, KeepAliveAlarmReceiver::class.java)
        var flags = PendingIntent.FLAG_IMMUTABLE
        flags = if (create) {
            flags or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            flags or PendingIntent.FLAG_NO_CREATE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /**
     * 排一次「到点探测并拉起」的一次性闹钟，用于存活探测失败后的退避重试。
     *
     * 【为什么用 set 而不是 setInexactRepeating】退避间隔是变化的（15s→300s），
     *   且每次失败都要重新决定下一次间隔，周期闹钟表达不了。
     * 【为什么不用 setExact】那需要 SCHEDULE_EXACT_ALARM（精确闹钟）权限，
     *   为一个「尽量快些回来」的次要目标去申请敏感权限不值得；inexact 的十几秒误差可接受。
     *
     * @return true 表示已排定；false 表示系统服务或 PendingIntent 不可用（调用方按放弃处理）。
     */
    fun scheduleAlarmAt(delayMs: Long): Boolean {
        return try {
            val am = alarmManager ?: return false
            val pi = alarmPendingIntent(BACKOFF_REQUEST, true) ?: return false
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
            true
        } catch (t: Throwable) {
            KeepAliveLog.w("scheduleAlarmAt failed", t)
            false
        }
    }
}
