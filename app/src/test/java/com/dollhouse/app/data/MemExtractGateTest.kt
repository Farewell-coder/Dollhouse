package com.dollhouse.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[MemExtractGate] 的行为契约 —— 钉住「保存间隔」这条设置真的生效。
 *
 * ## 这条测试挡的是什么
 *
 * 记忆设置页有「自动保存」开关和「保存间隔（5/10/20/30/60/120 分钟）」两件 UI。
 * 改造前 `autoExtract` 只检查了开关，**完全没看间隔**：每收到一条 assistant 回复
 * 就发起一次抽取请求。用户把间隔调到 120 分钟也没有任何效果。
 *
 * 这类缺陷的隐蔽之处在于：功能「看起来在工作」—— 记忆确实在增长，只是增长得过于频繁；
 * 而且它只在长期使用后表现为「记忆库又碎又乱 + token 账单变高」，
 * 单次操作根本看不出异常。所以必须用测试把边界钉死。
 */
class MemExtractGateTest {

    private val min = 60_000L

    /** 从未抽过 → 立即放行（否则新用户要等满一个间隔才有记忆，像"坏了"）。 */
    @Test
    fun neverExtracted_allowsImmediately() {
        assertTrue(MemExtractGate.allow(nowMs = 1_000_000L, lastAtMs = 0L, intervalMinutes = 30))
    }

    /** 距上次不到间隔 → 不放行。这是修掉「每条消息都抽」的核心断言。 */
    @Test
    fun withinInterval_blocks() {
        val last = 1_000_000L
        // 29 分钟，间隔 30 分钟
        assertFalse(MemExtractGate.allow(last + 29 * min, last, 30))
        // 1 分钟，间隔 30 分钟
        assertFalse(MemExtractGate.allow(last + min, last, 30))
        // 同一时刻（同一毫秒内连发两条）
        assertFalse(MemExtractGate.allow(last, last, 30))
    }

    /** 到点（>= 间隔）→ 放行。 */
    @Test
    fun atOrPastInterval_allows() {
        val last = 1_000_000L
        assertTrue(MemExtractGate.allow(last + 30 * min, last, 30))
        assertTrue(MemExtractGate.allow(last + 31 * min, last, 30))
        assertTrue(MemExtractGate.allow(last + 10 * 60 * min, last, 30))
    }

    /** 间隔按用户设定走：设 5 分钟就 5 分钟放行，不是硬编码。 */
    @Test
    fun honoursConfiguredInterval() {
        val last = 1_000_000L
        assertTrue("5 分钟档到点应放行", MemExtractGate.allow(last + 5 * min, last, 5))
        assertFalse("5 分钟档在 4 分钟时不该放行", MemExtractGate.allow(last + 4 * min, last, 5))
        // 同一时刻，换成 120 分钟档就该挡住
        assertFalse(MemExtractGate.allow(last + 30 * min, last, 120))
    }

    /**
     * **时钟回拨不能把自动抽取永久卡死。**
     *
     * 用户手动改系统时间、或跨时区跳变后，`nowMs < lastAtMs`。
     * 若直接算差值会得到负数，永远够不到间隔 —— 表现就是「自动保存从此再也没动静」，
     * 而且重启 App 也不会恢复（时间戳还在盘上）。这里约定：视为时间轴失效，重新起算。
     */
    @Test
    fun clockRollback_doesNotDeadlock() {
        val last = 10_000_000L
        val nowEarlier = 9_000_000L
        assertTrue(
            "时钟回拨后必须重新起算，否则自动抽取永久停摆",
            MemExtractGate.allow(nowEarlier, last, 60)
        )
    }

    /** 间隔配成 0 / 负数时按 1 分钟兜底，绝不退化成「每条消息都抽」。 */
    @Test
    fun zeroOrNegativeInterval_fallsBackToOneMinute() {
        val last = 1_000_000L
        assertFalse(MemExtractGate.allow(last + 30_000L, last, 0))
        assertFalse(MemExtractGate.allow(last + 30_000L, last, -5))
        assertTrue(MemExtractGate.allow(last + min, last, 0))
    }

    /** 间隔极大值（120 分钟档）边界。 */
    @Test
    fun maxIntervalBoundary() {
        val last = 1_000_000L
        assertFalse(MemExtractGate.allow(last + 119 * min, last, 120))
        assertTrue(MemExtractGate.allow(last + 120 * min, last, 120))
    }
}