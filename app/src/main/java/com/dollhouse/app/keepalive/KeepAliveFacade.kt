package com.dollhouse.app.keepalive

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 【职责】保活模块对外的唯一门面：Java 侧、Application、Job、Receiver 全部只认这一个入口。
 *
 * 【保活目标】保的是「Dollhouse 这个软件（应用进程）」常驻，不是人偶悬浮窗：
 *   开关只决定「要不要让应用进程一直活着」，与人偶的启停完全无关
 *   （用户点「退出桌宠」不会、也不应该被本模块否决或复活）。
 *
 * 【为什么需要门面】保活内部有十几个类，若让 Java 侧直接碰 [KeepAliveScheduler] /
 *   [KeepAliveRunner]，存量 Java 就得跟着 Kotlin 的类结构走。门面把内部分层锁在包内，
 *   对外只暴露「开关 / 状态查询 / 打开厂商页」这几件用户能感知的事。
 *
 * 【开关语义（本模块的核心行为）】
 *   开 = ① 落盘 enabled=true
 *        ② 拉起常驻前台服务 [KeepAliveService]（它内部挂 1×1 透明悬浮窗，把进程提到可见进程级）
 *        ③ 注册 JobScheduler 周期巡检（15min ± 5min，setPersisted 重启后仍在）
 *        ④ 注册 6 小时 AlarmManager 兜底（仅厂商冻结 JobScheduler 时才用得上）
 *        ⑤ 注册网络回调：网络一恢复立刻补探测一次
 *   关 = ① 落盘 enabled=false
 *        ② 停掉常驻前台服务（悬浮窗随之卸下）
 *        ③ 注销 Job / Alarm / 网络回调（幂等）
 *   —— 也就是说：这个开关只管「软件进程要不要常驻 / 被回收后要不要自动回来」，
 *      不管人偶现在在不在（那是 PetService 自己的事）。
 *
 * 【幂等】setEnabled 与 restore 可重复调用，不会堆出多条 Job / 多个回调。
 */
@Singleton
class KeepAliveFacade @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: KeepAliveStateStore,
    private val scheduler: KeepAliveScheduler,
    private val runner: KeepAliveRunner,
    private val networkMonitor: NetworkMonitor,
    private val permissions: KeepAlivePermissions,
    private val navigator: VendorNavigator
) {

    /** 开关当前是否为开。UI 首帧回填用。 */
    val isEnabled: Boolean
        get() = store.enabled

    /**
     * 拨动开关。
     * @return true = 状态已落盘且调度已同步；false = 中途出错（已尽力回滚到「关」）。
     */
    fun setEnabled(on: Boolean): Boolean {
        return try {
            store.enabled = on
            if (on) {
                scheduler.schedule()
                networkMonitor.register()
                // 立刻把常驻服务拉起来：用户刚开开关时不能等 15 分钟。
                runner.startNow()
            } else {
                scheduler.cancel()
                networkMonitor.unregister()
                // 关掉就把常驻服务停掉，通知与 1×1 悬浮窗一并撤走。
                runner.stopService()
            }
            KeepAliveLeakDetector.assertJobState(jobScheduler(), KeepAliveScheduler.JOB_ID, on)
            true
        } catch (t: Throwable) {
            KeepAliveLog.w("setEnabled($on) failed", t)
            // 出错即视为关闭：宁可不保活，也不能留下「开关显示关但仍被拉活」的状态。
            store.enabled = false
            try {
                scheduler.cancel()
                networkMonitor.unregister()
                runner.stopService()
            } catch (ignored: Throwable) {
                // 已尽力清理，此处不再上抛。
            }
            false
        }
    }

    /** 常驻保活服务当前是否活着（同进程内读布尔，无 IPC、无阻塞）。 */
    fun isAppAlive(): Boolean = runner.isAppAlive()

    /**
     * 进程启动 / 开机 / 覆盖安装后恢复调度。幂等；开关关着则直接返回。
     *
     * 【为什么必须幂等且廉价】本方法在每次冷启动都会跑一次，必须尽量少做事：
     *   开关未开时只读一个 boolean 就返回。
     */
    fun restore() {
        if (!store.enabled) {
            return
        }
        try {
            scheduler.schedule()
            networkMonitor.register()
            // 刚开机 / 刚覆盖安装时进程是新的，常驻服务必然没起来，直接拉一轮。
            runner.startNow()
            KeepAliveLeakDetector.assertJobState(jobScheduler(), KeepAliveScheduler.JOB_ID, true)
        } catch (t: Throwable) {
            KeepAliveLog.w("restore failed", t)
        }
    }

    /** 是否已进电池优化白名单。仅查询，不申请、不跳转。 */
    fun isIgnoringBatteryOptimizations(): Boolean = permissions.isIgnoringBatteryOptimizations()

    /** 打开「允许自启动」类厂商页（三级降级链）。 */
    fun openAutostartPage(): Boolean = navigator.open(VendorIntents.Target.AUTOSTART)

    /** 打开「后台运行 / 耗电管理」类厂商页（三级降级链）。 */
    fun openBatteryPage(): Boolean = navigator.open(VendorIntents.Target.BATTERY)

    private fun jobScheduler(): android.app.job.JobScheduler? =
        context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? android.app.job.JobScheduler
}