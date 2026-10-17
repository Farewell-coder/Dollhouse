package com.dollhouse.app.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[BlinkTimeline] 的单元测试（建议①）：眨眼相位的纯逻辑回归护栏。
 *
 * 【为什么值得测】Issue #1 报的就是「眨眼过快」——相位曲线调过一轮
 *   （CLOSE 220→70、HOLD 80→50、OPEN 120→100）。这类参数一旦被谁改回去，
 *   在没有视觉回归的情况下肉眼很难判定，但相位曲线的**形状**是确定的：
 *   从 0 单调升到 0.5（闭合）→ 保持 → 再升到 1.0（睁开）。用测试把形状钉住。
 */
class BlinkTimelineTest {

    @Test
    fun outsideWindow_returnsMinusOne() {
        assertEquals(-1f, BlinkTimeline.phaseAt(-1f), 1e-6f)
        assertEquals(-1f, BlinkTimeline.phaseAt(BlinkTimeline.TOTAL_MS), 1e-6f)
        assertEquals(-1f, BlinkTimeline.phaseAt(BlinkTimeline.TOTAL_MS + 500f), 1e-6f)
    }

    @Test
    fun startsFullyOpen() {
        assertEquals(0f, BlinkTimeline.phaseAt(0f), 1e-6f)
    }

    @Test
    fun closesToHalfAndHolds() {
        // 闭合段末（刚进保持段）：相位应为 0.5（完全闭合）。
        assertEquals(0.5f, BlinkTimeline.phaseAt(BlinkTimeline.CLOSE_MS), 1e-6f)
        val mid = BlinkTimeline.CLOSE_MS + BlinkTimeline.HOLD_MS / 2f
        assertEquals(0.5f, BlinkTimeline.phaseAt(mid), 1e-6f)
    }

    @Test
    fun opensBackToFull() {
        // 睁开段末尾逼近 1.0（还没到 TOTAL_MS，故用靠近端点采样）。
        val near = BlinkTimeline.TOTAL_MS - 0.01f
        assertTrue("near=$near", BlinkTimeline.phaseAt(near) > 0.99f)
    }

    @Test
    fun phaseIsMonotonicWithinCloseSegment() {
        var prev = -1f
        var t = 0f
        while (t < BlinkTimeline.CLOSE_MS) {
            val p = BlinkTimeline.phaseAt(t)
            assertTrue("t=$t phase=$p 应不小于上一个 $prev", p >= prev)
            prev = p
            t += 5f
        }
    }

    @Test
    fun phaseIsMonotonicWithinOpenSegment() {
        var prev = -1f
        var t = BlinkTimeline.CLOSE_MS + BlinkTimeline.HOLD_MS
        while (t < BlinkTimeline.TOTAL_MS) {
            val p = BlinkTimeline.phaseAt(t)
            assertTrue("t=$t phase=$p 应不小于上一个 $prev", p >= prev)
            prev = p
            t += 5f
        }
    }

    @Test
    fun phaseNeverExceedsValidRange() {
        var t = 0f
        while (t < BlinkTimeline.TOTAL_MS) {
            val p = BlinkTimeline.phaseAt(t)
            assertTrue("t=$t phase=$p 越界", p >= 0f && p <= 1f)
            t += 1f
        }
    }

    @Test
    fun totalDuration_around220ms() {
        // 【防止有人把闭合段误设回 220】整轮（闭+保持+睁）应落在 200~240ms 区间。
        assertTrue("TOTAL=${BlinkTimeline.TOTAL_MS}", BlinkTimeline.TOTAL_MS in 200f..240f)
        assertEquals(BlinkTimeline.CLOSE_MS + BlinkTimeline.HOLD_MS + BlinkTimeline.OPEN_MS,
            BlinkTimeline.TOTAL_MS, 1e-6f)
    }
}