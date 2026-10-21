package com.dollhouse.app.data

/**
 * 【职责】自动记忆抽取的**放行判据** —— 纯算术，可在 JVM 上单测。
 *
 * ## 为什么单独抽出来
 *
 * 记忆设置页有一项「保存间隔」（5/10/20/30/60/120 分钟），
 * 但改造前 `ChatHistoryStore.autoExtract` 是**每条 assistant 消息都抽一次**，
 * 根本没读这个设置 —— 界面上的设置项等于装饰。
 *
 * 修的时候不能只塞一个 `if` 进 `autoExtract`：那里依赖 `ChatPanel`（有 View、有 Context），
 * 在 JVM 上根本跑不起来，写了也验证不了。而「多久该抽一次」本身就是纯算术，
 * 抽到这里就能用 [MemExtractGateTest] 把边界钉死（首次立即抽、未到点不抽、到点抽、
 * 时钟回拨不卡死）。
 */
object MemExtractGate {

    /**
     * 是否应当执行一次自动抽取。
     *
     * 规则：
     * 1. **从未抽过**（[lastAtMs] <= 0）→ 立即抽一次。
     *    否则用户新装后要等满一个间隔才有记忆，体感像「自动保存坏了」。
     * 2. 距上次抽取 >= 间隔 → 抽。
     * 3. [nowMs] 比 [lastAtMs] 还小（用户改系统时间 / 时区跳变）→ 视为「时间轴失效」，
     *    清掉旧点重新计。不这么做的后果是：时钟往回拨一次，自动抽取就**永久停摆**
     *    （`now - last` 一直是负数，永远够不到间隔）。
     * 4. 间隔 <= 0 → 按 1 分钟兜底，绝不退化成「每条消息都抽」。
     *
     * @param nowMs    当前时刻（`System.currentTimeMillis()`）
     * @param lastAtMs 上次抽取时刻；0 = 从未
     * @param intervalMinutes 用户设定间隔（分钟）
     */
    fun allow(nowMs: Long, lastAtMs: Long, intervalMinutes: Int): Boolean {
        if (lastAtMs <= 0L) {
            return true
        }
        // 时钟回拨 / 脏数据：重新起算（allow 返回 true，由调用方把时间戳刷新成 nowMs）
        if (nowMs < lastAtMs) {
            return true
        }
        val minutes = if (intervalMinutes <= 0) 1 else intervalMinutes
        val gapMs = minutes.toLong() * 60_000L
        return nowMs - lastAtMs >= gapMs
    }
}