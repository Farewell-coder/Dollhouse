package com.dollhouse.app.keepalive

import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 【职责】执行一次保活周期：探测**本应用进程**的常驻服务 → 不在就拉起 → 失败则按退避排下一次重试。
 *
 * 【保活目标】本模块保的是「Dollhouse 这个软件」，不是人偶悬浮窗：
 *   判据是 [KeepAliveService.alive]（同进程内的一个 volatile 布尔），
 *   拉起的目标也是 [KeepAliveService]。人偶在不在、是否被用户关掉，都不影响本模块的判定。
 *
 * 【入口】
 *   - 周期通道：[KeepAliveJobService.onStartJob]（15 分钟一次）；
 *   - 兜底通道：[KeepAliveAlarmReceiver.onReceive]（6 小时周期 + 退避重试，共用同一接收器）；
 *   - 触发通道：[KeepAliveFacade.setEnabled] 开开关那一刻，以及 [DollhouseApp] 冷启动恢复。
 *
 * 【交互】读 [KeepAlivePolicy] 做判定，拉起前先过 [KeepAliveForegroundPolicy] 的
 *   「后台能不能起前台服务」闸门，重试由 [KeepAliveScheduler.scheduleAlarmAt] 排定。
 *
 * 【线程】所有工作跑在 [Dispatchers.Main.immediate]：服务与探测同进程，
 *   直接读一个布尔即可，重试标记也因此不需要加锁。
 *
 * 【坑】拉起后不能立即断言成功（服务启动是异步的），因此本轮只负责发起，
 *   成功与否由下一轮探测回收（避免乐观写入 lastAlive）。
 */
@Singleton
class KeepAliveRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: KeepAliveStateStore,
    private val policy: KeepAlivePolicy,
    private val scheduler: KeepAliveScheduler,
    private val foregroundPolicy: KeepAliveForegroundPolicy
) {

    /** 保活模块的结构化并发作用域。应用级，进程退出即随 Application 一并取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 退避重试的合并阀。
     * 【为什么需要】Job 通道与 Alarm 通道在同一时刻触发时，会各自排一次重试，
     *   导致退避步进被加倍消耗；有标记在等就不再叠加。
     * 【为什么不用原子类型】全部读写都在 Main.immediate 上，不存在并发。
     */
    private var retryScheduled = false

    /**
     * 跑一轮保活。
     * @param reason 触发来源，仅用于日志区分（job / alarm / toggle / boot / network）。
     */
    fun runOnce(reason: String) {
        scope.launch {
            try {
                // 本轮已开始执行，重置合并阀，允许本轮失败后再排下一次。
                retryScheduled = false
                if (isAppAlive()) {
                    policy.onSuccess()
                    KeepAliveLog.d("$reason: alive")
                    return@launch
                }
                if (!policy.shouldRevive()) {
                    // 开关关闭：红线，不拉起。
                    KeepAliveLog.d("$reason: not allowed (enabled=${policy.shouldSchedule()})")
                    return@launch
                }
                val issued = startKeepAliveService()
                policy.onFailure()
                KeepAliveLog.d("$reason: revive " + (if (issued) "issued" else "blocked"))
                scheduleRetry()
            } catch (t: Throwable) {
                KeepAliveLog.w("$reason: runOnce failed", t)
            }
        }
    }

    /**
     * 按退避序列排一次一次性重试闹钟。
     *
     * 【为什么必须有这一步】只依赖 JobScheduler 的 15 分钟周期的话，被系统回收后
     *   最长要等一刻钟才回来；退避重试把恢复延迟压到秒级起步。
     */
    private fun scheduleRetry() {
        if (retryScheduled) {
            return
        }
        if (policy.isBackoffExhausted()) {
            // 连续失败到达上限：停掉自动重连，只保留周期探测，避免无谓耗电。
            KeepAliveLog.d("retry exhausted")
            return
        }
        val delayMs = policy.delayForStep(store.backoffStep)
        if (scheduler.scheduleAlarmAt(delayMs)) {
            retryScheduled = true
            KeepAliveLog.d("retry scheduled in " + delayMs + "ms")
        }
    }

    /**
     * 探测本应用的常驻保活服务是否活着。
     *
     * 【为什么不用 bindService】同进程内的服务，直接读 [KeepAliveService.alive] 即可：
     *   零 IPC、零超时、无 BIND_AUTO_CREATE 副作用。跨进程才需要 bind，
     *   而本服务唯一的目的是拖住本进程，不存在「跨进程存活」这种场景。
     * 【进程都没了怎么办】进程没了本方法根本不会被调用，那种情况由
     *   Job / Alarm / 开机广播从外部把进程重新拉起来。两者是不同层级的兜底。
     */
    fun isAppAlive(): Boolean = KeepAliveService.alive

    /**
     * 发起拉起常驻保活服务。
     *
     * 【先过闸门再动手】API 31 起后台启动前台服务默认抛
     *   ForegroundServiceStartNotAllowedException，官方豁免里有一条正是
     *   「持有 SYSTEM_ALERT_WINDOW」。没豁免时去撞异常只会白刷日志，
     *   这里直接返回 false，由调用方转入退避重试。
     *
     * @return true = 启动指令已发出（不代表服务已起来，异步的）。
     */
    private fun startKeepAliveService(): Boolean {
        if (!foregroundPolicy.canStartForegroundFromBackground()) {
            KeepAliveLog.d("startKeepAliveService denied by foreground policy")
            return false
        }
        return try {
            val intent = Intent(context, KeepAliveService::class.java)
            intent.action = KeepAliveService.ACTION_START
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (t: Throwable) {
            KeepAliveLog.w("startKeepAliveService failed", t)
            false
        }
    }

    /** 停掉常驻保活服务（关开关时调用）。幂等：没在跑就什么都不做。 */
    fun stopService() {
        try {
            context.stopService(Intent(context, KeepAliveService::class.java))
        } catch (t: Throwable) {
            KeepAliveLog.w("stopKeepAliveService failed", t)
        }
    }

    /**
     * 立即确保常驻服务在跑（开开关 / 冷启动恢复时调用）。
     *
     * 【与 runOnce 的差别】runOnce 是周期巡检：探测不到就拉起并**计入退避步进**（这次没成是失败）。
     *   本方法是用户刚开开关 / 应用刚冷启，属于「正常启动」而非「失败重试」，
     *   因此不碰退避步进，只做「不在就拉起来」。
     */
    fun startNow() {
        scope.launch {
            try {
                if (!isAppAlive()) {
                    startKeepAliveService()
                }
            } catch (t: Throwable) {
                KeepAliveLog.w("startNow failed", t)
            }
        }
    }
}