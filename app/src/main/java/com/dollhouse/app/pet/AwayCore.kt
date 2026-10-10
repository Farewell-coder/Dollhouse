package com.dollhouse.app.pet

/**
 * 【职责】暂离（away）生命周期的纯逻辑：把配置秒数夹到合法区间，并维护「单一截止」。
 *
 * 【为什么独立】截止算术与 Android / Handler / 系统时钟无关，抽成纯 Kotlin 后可单测；
 *   PetService 只负责把 reset() 返回的延迟接到同一个 ui Handler 的 postDelayed 上（一次，不轮询）。
 *
 * 【约定】
 * - 秒数夹到 [0, MAX_SECONDS]；0 秒表示「摘窗后立即恢复」，不是永久关闭。
 * - 重复触发只重置这一个截止（reset 覆盖 deadline / scheduled），不叠加计时。
 * - cancel() 清掉截止；调用方仍需自行 removeCallbacks 对应的 Runnable。
 */
class AwayCore {
    private var deadlineMs = 0L
    private var scheduled = false

    /**
     * 重置唯一截止：返回应 postDelayed 的毫秒数（0 = 立即恢复）。
     * nowMs 由调用方传入（System.currentTimeMillis），便于测试注入。
     */
    fun reset(seconds: Int, nowMs: Long): Long {
        val delay = clampSeconds(seconds).toLong() * 1000L
        deadlineMs = nowMs + delay
        scheduled = true
        return delay
    }

    /** 取消截止（手动停止 / 销毁 / 恢复完成）。 */
    fun cancel() {
        scheduled = false
        deadlineMs = 0L
    }

    /** 是否处于暂离计时中。 */
    fun isScheduled(): Boolean = scheduled

    /** 距截止还剩多少毫秒（未计时返回 0）。 */
    fun remainingMs(nowMs: Long): Long = if (scheduled) (deadlineMs - nowMs).coerceAtLeast(0L) else 0L

    /** 截止是否已到（未计时恒为 false）。 */
    fun isExpired(nowMs: Long): Boolean = scheduled && nowMs >= deadlineMs

    companion object {
        const val MAX_SECONDS = 3600

        /** 把配置秒数夹到 [0, MAX_SECONDS]。 */
        fun clampSeconds(seconds: Int): Int = seconds.coerceIn(0, MAX_SECONDS)
    }
}
