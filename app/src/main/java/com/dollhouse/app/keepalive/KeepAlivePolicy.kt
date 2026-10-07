package com.dollhouse.app.keepalive

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 【职责】保活的纯规则层：决定「要不要拉起」「拉不起时退避多久」。
 *
 * 【为什么单独一层】把判定从 Android 框架里拿出来，使得红线（用户显式停止后不得复活）
 *   成为一个可纯 JVM 验证的布尔表达式，而不是散落在 Service / Receiver 里的 if。
 *
 * 【交互】只依赖 [KeepAliveStateStore]；不持有 Context，不做 IO，不碰框架。
 *
 * 【坑】本类不做任何副作用（不拉服务、不写状态），调用方拿到 true 后才去执行动作。
 */
@Singleton
class KeepAlivePolicy @Inject constructor(
    private val store: KeepAliveStateStore
) {

    companion object {
        /** 连续失败到达该次数后停掉自动重连，仅保留周期探测。 */
        const val MAX_BACKOFF_STEP = 8

        /** 退避基准序列（毫秒），步进超出后取末位。 */
        private val BACKOFF_STEPS_MS = longArrayOf(
            15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L, 300_000L, 300_000L
        )
    }

    /**
     * 是否允许自动拉起常驻保活服务。
     *
     * 【两条红线，缺一不可】
     *   1. 开关必须开着（用户没开就不保活）；
     *   2. 强行停止（应用信息页「强行停止」）后系统不会派发 BOOT_COMPLETED / JobScheduler，
     *      天然走不到这里，因此无需额外判断。
     *
     * 【为什么不再看 user_stopped】这是「保活软件」与「保活人偶」的分水岭：
     *   用户点「关闭人偶」只意味着他不想要那个人偶悬浮窗，
     *   不代表他不希望这个软件本身常驻（AI 对话、lamda 服务都还依赖进程活着）。
     *   故保活判定与人偶的启停状态完全解耦，不再读 feiyu_pet 的任何字段。
     */
    fun shouldRevive(): Boolean = store.enabled

    /** 是否应当注册周期调度（与 shouldRevive 分开：开关是唯一判据）。 */
    fun shouldSchedule(): Boolean = store.enabled

    /** 当前步进是否已超出自动重连上限。 */
    fun isBackoffExhausted(): Boolean = store.backoffStep >= MAX_BACKOFF_STEP

    /**
     * 按下一次退避步进取延迟时长（毫秒）。
     *
     * 【为什么起点是 15 秒而不是 1 秒】重试通道用 AlarmManager.set()（inexact），
     *   秒级闹钟会被系统批处理推迟十几秒才派发，排了等于没排；15 秒是实测可用的下限。
     * 超出序列长度时取末位（300s），保证 24 小时内重连次数不超过约 300 次。
     */
    fun delayForStep(step: Int): Long {
        val idx = step.coerceIn(0, BACKOFF_STEPS_MS.size - 1)
        return BACKOFF_STEPS_MS[idx]
    }

    /** 记录一次失败：步进 +1。 */
    fun onFailure(): Int {
        val next = (store.backoffStep + 1).coerceAtMost(MAX_BACKOFF_STEP)
        store.backoffStep = next
        return next
    }

    /** 记录一次成功：步进归零 + 刷新存活时间。 */
    fun onSuccess() {
        store.markAlive()
    }
}
