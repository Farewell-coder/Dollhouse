package com.dollhouse.app.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】弹簧内核的**数值行为**回归。
 *
 * 本类只测纯数学，不需要 Looper / Robolectric，因此能在 `gradle test` 里秒过。
 * 改造前这里只测了 `lerp`，**积分器本身零覆盖** —— 而本次修的两个 bug
 * （子步长越界发散、NaN 流出）恰好都在积分器里，所以这里补上：
 *
 * 1. 大步长不发散（后台回前台的 2 秒 dt）；
 * 2. NaN dt 不污染状态；
 * 3. 三条预置的收敛时长与阻尼比落在设计区间；
 * 4. 收敛后不再漂移（幂等）；
 * 5. retarget 不归零。
 */
class SpringTest {

    /** 用 1/60 s 的固定步长推进，返回收敛所需帧数（超过 maxFrames 视为不收敛）。 */
    private fun framesToSettle(s: Spring, maxFrames: Int = 600): Int {
        s.start()
        for (i in 1..maxFrames) {
            if (s.step(1.0 / 60.0)) {
                return i
            }
        }
        return -1
    }

    // ---------------- ① 稳定性：子步长越界 ----------------

    @Test
    fun `hugeDt does not diverge`() {
        // 模拟「切后台 2 秒后回前台」：改造前 s = 2.0/16 = 0.125s，
        // ω·h ≈ 2.56 越过半隐式欧拉的稳定边界，位移会指数放大。
        for (spring in listOf(Springs.snappy(), Springs.smooth(), Springs.bouncy())) {
            spring.start()
            spring.step(2.0)
            assertTrue("x 必须是有限值，实际=${spring.x}", spring.x.isFinite())
            assertTrue("x 必须被夹在合理区间，实际=${spring.x}", spring.x > -1.0 && spring.x < 3.0)
            assertTrue("v 必须是有限值，实际=${spring.v}", spring.v.isFinite())
        }
    }

    @Test
    fun `hugeDt stays monotone enough to settle`() {
        // 吃一次 2 秒的 dt 之后仍然必须能收敛（而不是卡在发散态）。
        val s = Springs.snappy()
        s.start()
        s.step(2.0)
        var settled = false
        for (i in 0 until 600) {
            if (s.step(1.0 / 60.0)) {
                settled = true
                break
            }
        }
        assertTrue("大步长之后应能恢复收敛", settled)
    }

    // ---------------- ② 有限性：NaN / Inf 不流出 ----------------

    @Test
    fun `nan dt does not poison state`() {
        val s = Springs.snappy()
        s.start()
        s.step(Double.NaN)
        assertTrue("x 不能是 NaN", s.x.isFinite())
        assertTrue("v 不能是 NaN", s.v.isFinite())
        assertEquals("NaN 之后应落终态", 1.0, s.x, 1e-6)
        assertEquals("终态速度应为 0", 0.0, s.v, 1e-6)
    }

    @Test
    fun `infinite dt does not poison state`() {
        val s = Springs.snappy()
        s.start()
        s.step(Double.POSITIVE_INFINITY)
        assertTrue("x 必须是有限值", s.x.isFinite())
        assertTrue("v 必须是有限值", s.v.isFinite())
    }

    @Test
    fun `zero and negative dt are short circuited`() {
        val s = Springs.snappy()
        s.start()
        val x0 = s.x
        val v0 = s.v
        s.step(0.0)
        s.step(-0.5)
        assertEquals("dt<=0 不应改动 x", x0, s.x, 1e-12)
        assertEquals("dt<=0 不应改动 v", v0, s.v, 1e-12)
    }

    // ---------------- ③ 预置：阻尼比与收敛时长 ----------------

    @Test
    fun `preset damping ratios match design`() {
        val zetaSmooth = Springs.smooth().c / (2.0 * Math.sqrt(Springs.smooth().k))
        val zetaSnappy = Springs.snappy().c / (2.0 * Math.sqrt(Springs.snappy().k))
        val zetaBouncy = Springs.bouncy().c / (2.0 * Math.sqrt(Springs.bouncy().k))
        assertEquals("smooth 应临界阻尼", 1.0, zetaSmooth, 0.02)
        assertEquals("snappy 应轻微过冲", 0.73, zetaSnappy, 0.02)
        assertEquals("bouncy 应明显回弹", 0.40, zetaBouncy, 0.02)
    }

    @Test
    fun `all presets settle within one second`() {
        for ((name, s) in listOf(
            "smooth" to Springs.smooth(),
            "snappy" to Springs.snappy(),
            "bouncy" to Springs.bouncy()
        )) {
            val f = framesToSettle(s)
            assertTrue("$name 未在 600 帧内收敛", f > 0)
            assertTrue("$name 收敛应 < 60 帧（1s），实际 $f 帧", f < 60)
        }
    }

    @Test
    fun `smooth never overshoots`() {
        // 临界阻尼的定义就是不过冲：x 单调递增且不超 1。
        val s = Springs.smooth()
        s.start()
        var prev = s.x
        var maxX = 0.0
        for (i in 0 until 600) {
            val done = s.step(1.0 / 60.0)
            assertTrue("smooth 不应回退（prev=$prev, now=${s.x}）", s.x >= prev - 1e-6)
            maxX = Math.max(maxX, s.x)
            prev = s.x
            if (done) break
        }
        assertTrue("smooth 过冲到 $maxX", maxX <= 1.0 + 1e-6)
    }

    @Test
    fun `snappy overshoots slightly`() {
        val s = Springs.snappy()
        s.start()
        var maxX = 0.0
        for (i in 0 until 600) {
            val done = s.step(1.0 / 60.0)
            maxX = Math.max(maxX, s.x)
            if (done) break
        }
        assertTrue("snappy 应有可感知的过冲，实际峰值 $maxX", maxX > 1.005)
        assertTrue("snappy 过冲不应过大，实际峰值 $maxX", maxX < 1.30)
    }

    // ---------------- ④ 收敛幂等 ----------------

    @Test
    fun `settled spring does not drift`() {
        // 【为什么不能断言「完全不变」】收敛判定是 `|1−x| < 1e-3`，
        //   即 x=0.99956 时就已经算「收敛」并让驱动层停止。此时再喂几帧，
        //   积分器仍会朝 1.0 收敛（这是对的：它还没到 1）。
        //   真正要保证的是「不会再动一下、又掉回半路」这种可见抖动，
        //   以及**驱动层在收敛时会调 settle() 钉到精确终态**。
        val s = Springs.snappy()
        framesToSettle(s)
        val afterSettle = s.x
        assertTrue("收敛时应已非常接近 1，实际=$afterSettle", Math.abs(1.0 - afterSettle) < 1e-3)
        for (i in 0 until 100) {
            s.step(1.0 / 60.0)
        }
        assertTrue("收敛后不应掉回半路（实际=${s.x}）", s.x >= afterSettle - 1e-6)
        assertTrue("收敛后不应越过 1 太多（实际=${s.x}）", s.x <= 1.0 + 1e-3)
        assertTrue("收敛态 isSettled 应为 true", s.isSettled())
    }

    @Test
    fun `settle pins exact terminal state after convergence`() {
        // 驱动层（Springs.drive）在弹簧自报收敛时会调用 settle()，
        // 于是视图拿到的一定是精确的 1.0f —— 不会停在 0.9995 这种「差一点点」的位置。
        val s = Springs.snappy()
        framesToSettle(s)
        s.settle()
        assertEquals("settle 必须钉到精确 1.0", 1.0, s.x, 0.0)
        assertEquals("settle 必须把速度清零", 0.0, s.v, 0.0)
    }

    @Test
    fun `settle writes exact terminal state`() {
        val s = Springs.bouncy()
        s.start()
        s.step(1.0 / 60.0)
        s.settle()
        assertEquals(1.0, s.x, 0.0)
        assertEquals(0.0, s.v, 0.0)
        assertTrue(s.isSettled())
    }

    // ---------------- ⑤ 可中断语义 ----------------

    @Test
    fun `start resets progress but keeps velocity clamped`() {
        val s = Springs.snappy()
        s.start()
        // 让速度升到一个较大的值。
        for (i in 0 until 6) {
            s.step(1.0 / 60.0)
        }
        assertTrue("此时应有正速度", s.v > 0.0)
        s.start()
        assertEquals("start 应把进度归零", 0.0, s.x, 0.0)
        assertTrue("速度应被夹到 ±14 内，实际=${s.v}", s.v <= 14.0 && s.v >= -14.0)
    }

    @Test
    fun `retarget keeps current progress`() {
        val s = Springs.snappy()
        s.start()
        s.step(1.0 / 60.0)
        val x = s.x
        s.retarget()
        assertEquals("retarget 不应改动进度", x, s.x, 1e-12)
    }

    @Test
    fun `settled spring is not running forever`() {
        val s = Springs.smooth()
        s.settle()
        assertTrue("已就位的弹簧 isSettled 应为 true", s.isSettled())
        assertFalse("刚 settle 完不应是「未收敛」", !s.isSettled())
    }

    // ---------------- 工具函数 ----------------

    @Test
    fun `lerp maps endpoints and midpoint`() {
        assertEquals(0f, Springs.lerp(0f, 100f, 0f), 1e-6f)
        assertEquals(100f, Springs.lerp(0f, 100f, 1f), 1e-6f)
        assertEquals(50f, Springs.lerp(0f, 100f, 0.5f), 1e-6f)
        assertEquals(15f, Springs.lerp(10f, 20f, 0.5f), 1e-6f)
    }

    @Test
    fun `lerpInt rounds instead of truncating`() {
        assertEquals(0, Springs.lerpInt(0, 100, 0f))
        assertEquals(100, Springs.lerpInt(0, 100, 1f))
        // 0.005 * 100 = 0.5 → 应进到 1（截断会得 0）。
        assertEquals(1, Springs.lerpInt(0, 100, 0.005f))
        // 单调不减：反复截断会在同值上抖动，round 不会。
        var prev = 0
        for (i in 0..20) {
            val v = Springs.lerpInt(0, 100, i / 20f)
            assertTrue("lerpInt 应单调不减", v >= prev)
            prev = v
        }
    }
}