package com.dollhouse.app.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[ActionFrameClock] 的行为契约 —— 把「动画节奏」钉死，防复发。
 *
 * ## 为什么这份测试值得写
 *
 * 用户报的现象是「动作看着有点拖 / 不丝滑」，属于**感受类**缺陷，靠看代码看不出来。
 * 但它背后的根因是纯算术：帧循环按固定 16ms 排队，而帧间隔 `1000/fps` 不是 16 的整数倍，
 * 旧实现每帧 `lastStepMs = nowMs` 就把余数丢了，误差单向累积。
 *
 * 这类缺陷只有「长跑 + 定频采样」才暴露，所以这里用**模拟 16ms 帧循环**的方式
 * 跑足够长的时间，用总耗时对齐率来断言 —— 而不是在真机上凭肉眼反复验收。
 */
class ActionFrameClockTest {

    /** 模拟一次「按 16ms 排队的帧循环」，返回经过 [totalMs] 后实际走过的帧数。 */
    private fun runLoop(
        clock: ActionFrameClock,
        fps: Int,
        frames: Int,
        totalMs: Long,
        stepMs: Long = 16L
    ): Int {
        var changes = 0
        var last = clock.frame
        var t = 0L
        // 先起表（第一次 advance 只建立基准）
        clock.advance(0L, frames)
        while (t <= totalMs) {
            t += stepMs
            clock.advance(t, frames)
            if (clock.frame != last) {
                changes++
                last = clock.frame
            }
        }
        return changes
    }

    @Test
    fun firstAdvance_onlyStartsTheClock() {
        val c = ActionFrameClock(fpsProvider = { 12 }, loopProvider = { true })
        assertFalse("首帧不该推进", c.advance(1000L, 10))
        assertEquals(0, c.frame)
    }

    @Test
    fun singleFrame_neverAdvances() {
        val c = ActionFrameClock(fpsProvider = { 12 }, loopProvider = { true })
        c.advance(0L, 1)
        assertFalse(c.advance(10_000L, 1))
        assertEquals(0, c.frame)
    }

    /**
     * **核心契约：长跑不漂移。**
     *
     * fps=12 → 每帧 83ms。按 16ms 采样跑 6000ms，理论应走过 6000/83 ≈ 72 帧。
     * 旧实现（每帧重置基准）会稳定少走约 1/6（因为每帧最多丢 15ms）。
     * 这里给 ±3 帧的容差，够容纳采样边界，但足以把「单向累积」的旧行为挡在外面。
     */
    @Test
    fun longRun_doesNotDrift() {
        val c = ActionFrameClock(fpsProvider = { 12 }, loopProvider = { true })
        val changes = runLoop(c, fps = 12, frames = 1000, totalMs = 6000L)
        val expect = 6000 / 83
        assertTrue(
            "理论约 $expect 帧，实际 $changes —— 偏离超过 3 帧说明帧时钟又在累积误差",
            Math.abs(changes - expect) <= 3
        )
    }

    /** 30fps（step=33ms）同样不能漂：这是「动作最大帧率」档。 */
    @Test
    fun longRun_thirtyFps_doesNotDrift() {
        val c = ActionFrameClock(fpsProvider = { 30 }, loopProvider = { true })
        val changes = runLoop(c, fps = 30, frames = 1000, totalMs = 6000L)
        val expect = 6000 / 33
        assertTrue(
            "理论约 $expect 帧，实际 $changes",
            Math.abs(changes - expect) <= 3
        )
    }

    /** 掉帧能追上：一次 500ms 的大卡顿后，帧号应直接跳到「该到的那一帧」。 */
    @Test
    fun bigStall_catchesUp() {
        val c = ActionFrameClock(fpsProvider = { 10 }, loopProvider = { false })
        c.advance(0L, 100)       // 起表
        c.advance(100L, 100)     // +100ms = 1 帧（step=100）
        assertEquals(1, c.frame)
        c.advance(600L, 100)     // 又过 500ms = 5 帧，应直接到第 6 帧
        assertEquals(6, c.frame)
    }

    /** 非循环动作停在末帧，反复 advance 不会再越界。 */
    @Test
    fun nonLoop_clampsAtLastFrame() {
        val c = ActionFrameClock(fpsProvider = { 10 }, loopProvider = { false })
        c.advance(0L, 3)
        for (t in 1..50) {
            c.advance(t * 100L, 3)
        }
        assertEquals(2, c.frame)
        assertTrue(c.isAtEnd(3))
    }

    /** 循环动作按取模绕回，且 isAtEnd 永远为 false。 */
    @Test
    fun loop_wrapsAround_andNeverEnds() {
        val c = ActionFrameClock(fpsProvider = { 10 }, loopProvider = { true })
        c.advance(0L, 3)
        c.advance(100L, 3)
        c.advance(200L, 3)
        assertEquals(2, c.frame)
        c.advance(300L, 3)
        assertEquals("循环应回到第 0 帧", 0, c.frame)
        assertFalse(c.isAtEnd(3))
    }

    /** reset() 回到第 0 帧并重新起表（换动作 / 帧图就绪时用）。 */
    @Test
    fun reset_startsOverFromFrameZero() {
        val c = ActionFrameClock(fpsProvider = { 10 }, loopProvider = { true })
        c.advance(0L, 10)
        c.advance(500L, 10)
        assertTrue(c.frame > 0)
        c.reset()
        assertEquals(0, c.frame)
        // 重置后第一次 advance 只起表，不该立刻跳到第 5 帧
        assertFalse(c.advance(10_000L, 10))
        assertEquals(0, c.frame)
    }

    /** rebase() 保住当前帧号，但清掉时间基准（暂停恢复时用）。 */
    @Test
    fun rebase_keepsFrame_butRestartsTiming() {
        val c = ActionFrameClock(fpsProvider = { 10 }, loopProvider = { true })
        c.advance(0L, 10)
        c.advance(300L, 10)
        val held = c.frame
        assertEquals(3, held)
        c.rebase()
        assertEquals("rebase 不该改帧号", held, c.frame)
        // 暂停 10 秒后恢复：不该瞬间跳到第 100 帧
        assertFalse(c.advance(10_000L, 10))
        assertEquals(held, c.frame)
    }

    /** seek() 夹在合法区间内（手动步进时用）。 */
    @Test
    fun seek_clampsIntoRange() {
        val c = ActionFrameClock(fpsProvider = { 10 }, loopProvider = { false })
        c.seek(-5, 4)
        assertEquals(0, c.frame)
        c.seek(99, 4)
        assertEquals(3, c.frame)
        c.seek(2, 4)
        assertEquals(2, c.frame)
    }

    /** 运行中调速要立刻生效（用户拖 fps 滑条时的表现）。 */
    @Test
    fun fpsChange_takesEffectImmediately() {
        var fps = 5
        val c = ActionFrameClock(fpsProvider = { fps }, loopProvider = { true })
        c.advance(0L, 100)
        // 5fps → step=200ms
        c.advance(200L, 100)
        assertEquals(1, c.frame)
        // 改成 20fps → step=50ms；再过 200ms 应走 4 帧
        fps = 20
        c.advance(400L, 100)
        assertEquals("调速后应按新帧率推进", 5, c.frame)
    }

    /**
     * 【附录C-11】帧间隔必须是精确的 1000/fps，不能被整数除法量化。
     *
     * 【为什么用 fps=24 做断言】`1000L / 24` = 41ms，而精确值是 41.667ms。
     *   跑满 60 帧：精确需要 2500ms，按 41ms 只要 2460ms。
     *   取 2470ms 这个时刻，两种实现的帧号分别是 59 与 60 —— 刚好能区分开。
     *   （不用 60fps 测：20ms 的地板会把 60fps 钳到 50fps，那是另一条既有设计，见 stepMsExact 注释。）
     */
    @Test
    fun twentyFourFps_usesExactFrameInterval() {
        val c = ActionFrameClock(fpsProvider = { 24 }, loopProvider = { false })
        c.advance(0L, 10_000)
        c.advance(2_470L, 10_000)
        assertTrue(
            "24fps 下 2470ms 不该走满 60 帧（实际 " + c.frame + "）—— 帧间隔被整数除法量化了",
            c.frame < 60
        )
    }

    /** 精确间隔下，2 秒 @24fps 恰好 48 帧（整数除法会多跑 1 帧）。 */
    @Test
    fun twentyFourFps_noCumulativeDrift() {
        val c = ActionFrameClock(fpsProvider = { 24 }, loopProvider = { false })
        c.advance(0L, 10_000)
        c.advance(2_000L, 10_000)
        assertEquals("2 秒 @24fps 应恰好 48 帧", 48, c.frame)
    }

    /** 地板仍然生效：fps 填得再高，帧间隔也不低于 20ms。 */
    @Test
    fun extremeFps_stillClampedByFloor() {
        val c = ActionFrameClock(fpsProvider = { 1000 }, loopProvider = { false })
        c.advance(0L, 10_000)
        // 20ms 一帧 → 1000ms 恰好 50 帧；若没地板会走 1000 帧。
        c.advance(1_000L, 10_000)
        assertEquals("超高 fps 应被 20ms 地板钳住", 50, c.frame)
    }
}
