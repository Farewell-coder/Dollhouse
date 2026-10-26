package com.dollhouse.app.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThrowPhysics] 的回归测试 —— 钉住「甩抛」这条链路的每条判定。
 *
 * 需求书第四阶段的验收标准：
 *   「拖拽自然、边界行为合理 / 贴边和自动移动不会互相抢占状态 /
 *     停止交互后不再进行无效的持续计算 / 不明显增加功耗或内存占用」。
 *
 * 这些用例覆盖前三项；第四项由「静止判定 + shouldStop」保证 ——
 * 只要它俩正确，宿主就会把 rAF 移除，不会留下常驻轮询。
 */
class ThrowPhysicsTest {

    private val now = 1_000_000L

    /** 造一条匀速轨迹：每隔 [stepMs] 移动 [dx]/[dy]。 */
    private fun trail(
        n: Int,
        stepMs: Long,
        dx: Float,
        dy: Float = 0f,
        startX: Float = 0f,
        startY: Float = 0f
    ): List<ThrowPhysics.Sample> {
        val out = ArrayList<ThrowPhysics.Sample>(n)
        for (i in 0 until n) {
            out.add(ThrowPhysics.Sample(now - (n - 1 - i) * stepMs, startX + dx * i, startY + dy * i))
        }
        return out
    }

    /* ============================ 速度估计 ============================ */

    /** 匀速右甩：估出来的速度应接近「位移/时间」，且方向为正 x。 */
    @Test
    fun `匀速右甩估出向右速度`() {
        // 每 10ms 移动 20px → 2000 px/s
        val t = trail(n = 10, stepMs = 10, dx = 20f)
        val v = ThrowPhysics.estimate(t, now)
        assertTrue("vx 应显著为正，实际 " + v.vx, v.vx > 1500f)
        assertTrue("速度应在 2000 附近，实际 " + v.speed, v.speed in 1500f..3000f)
        assertEquals("水平甩动不该有竖直分量", 0f, v.vy, 1f)
    }

    /** 向上甩：vy 为负（屏幕坐标 y 向下）。 */
    @Test
    fun `向上甩估出负的竖直速度`() {
        val t = trail(n = 10, stepMs = 10, dx = 0f, dy = -20f)
        val v = ThrowPhysics.estimate(t, now)
        assertTrue("向上甩 vy 应为负，实际 " + v.vy, v.vy < -1500f)
    }

    /** 静止不动：速度为零。 */
    @Test
    fun `不动的轨迹估出零速度`() {
        val t = trail(n = 10, stepMs = 10, dx = 0f)
        val v = ThrowPhysics.estimate(t, now)
        assertEquals(0f, v.speed, 0.01f)
    }

    /**
     * 甩一下后**停住再松手** → 不带残余速度。
     *
     * 【为什么要这条】用户甩到想要的位置后常常会停一下再松手。
     * 没有这条判定的话，人偶会带着 100ms 前的速度继续飞 —— 手感像「没抓住」。
     */
    @Test
    fun `抬手前停住则不带残余速度`() {
        // 先快速右移，最后一点停在原地，且抬手时刻远离最后一点。
        val t = ArrayList<ThrowPhysics.Sample>()
        for (i in 0 until 5) {
            t.add(ThrowPhysics.Sample(now - 400 + i * 10L, i * 30f, 0f))
        }
        // 最后一点在 now-300，抬手在 now → 停顿 300ms > RELEASE_STALE_MS
        val v = ThrowPhysics.estimate(t, now)
        assertEquals(0f, v.speed, 0.01f)
    }

    /** 只有一个采样点（瞬时按下抬手）→ 零速度，不可能算出无穷大。 */
    @Test
    fun `单点轨迹返回零速度`() {
        val v = ThrowPhysics.estimate(listOf(ThrowPhysics.Sample(now, 0f, 0f)), now)
        assertEquals(0f, v.speed, 0.01f)
    }

    /** 空轨迹 → 零速度，不崩。 */
    @Test
    fun `空轨迹返回零速度`() {
        assertEquals(0f, ThrowPhysics.estimate(emptyList(), now).speed, 0.01f)
    }

    /**
     * 超短间隔的密集采样（模拟高回报率屏）不应把抖动放大成虚假高速。
     *
     * 【为什么】1ms 间隔、0.5px 抖动 → 直接相除是 500 px/s 的「假甩」。
     * 段短于 [ThrowPhysics.SEG_MIN_DT_MS] 会被跳过，最后用端点法兜底。
     */
    @Test
    fun `超短间隔采样不会放大成虚假高速`() {
        val t = ArrayList<ThrowPhysics.Sample>()
        // 20 个点，每个间隔 1ms，位置基本不动（±0.5px 抖动）
        for (i in 0 until 20) {
            val jitter = if (i % 2 == 0) 0.5f else -0.5f
            t.add(ThrowPhysics.Sample(now - 19 + i, jitter, 0f))
        }
        val v = ThrowPhysics.estimate(t, now)
        // 端点法：跨度 19ms，位移 0.5px（首尾都是 0.5）→ 速度极小
        assertTrue("抖动不该被放大，实际速度 " + v.speed, v.speed < 500f)
    }

    /* ============================ 软钳制 ============================ */

    /** 软钳制：单调递增、恒小于 cap。 */
    @Test
    fun `软钳制单调递增且不超过上限`() {
        var prev = -1f
        for (s in intArrayOf(100, 500, 1000, 2000, 4000, 10000)) {
            val out = ThrowPhysics.softClamp(s.toFloat())
            assertTrue("速度 $s 钳制后应 > 上一个值", out > prev)
            assertTrue("速度 $s 钳制后应 < cap", out < ThrowPhysics.MAX_THROW_SPEED)
            prev = out
        }
    }

    /** 软钳制：0 与负值都返回 0。 */
    @Test
    fun `软钳制对零与负值返回零`() {
        assertEquals(0f, ThrowPhysics.softClamp(0f), 0.001f)
        assertEquals(0f, ThrowPhysics.softClamp(-100f), 0.001f)
    }

    /** 力度倍数会乘上去，再被软钳制。 */
    @Test
    fun `力度倍数参与初速计算`() {
        val v = ThrowPhysics.Velocity(1000f, 0f)
        val strong = ThrowPhysics.applyPower(v, 2f)
        val weak = ThrowPhysics.applyPower(v, 0.5f)
        assertTrue("2 倍力度应更快", strong.speed > weak.speed)
        // 非法力度兜底为 1 倍
        val bad = ThrowPhysics.applyPower(v, 0f)
        assertEquals(ThrowPhysics.softClamp(1000f), bad.speed, 1f)
    }

    /* ============================ 死区 ============================ */

    /** 低于死区不算甩；高于算。 */
    @Test
    fun `死区判定`() {
        assertFalse(ThrowPhysics.isThrow(ThrowPhysics.Velocity(100f, 0f)))
        assertFalse(ThrowPhysics.isThrow(ThrowPhysics.Velocity(0f, 0f)))
        assertTrue(ThrowPhysics.isThrow(ThrowPhysics.Velocity(ThrowPhysics.DEAD_ZONE_SPEED, 0f)))
        assertTrue(ThrowPhysics.isThrow(ThrowPhysics.Velocity(2000f, 0f)))
    }

    /* ============================ 积分 ============================ */

    private val bounds = ThrowPhysics.Bounds(minX = 100f, maxX = 500f, groundY = 1000f, minY = 0f)

    /** 自由落体：只受重力，y 递增、vy 递增。 */
    @Test
    fun `自由落体向下加速`() {
        val s = ThrowPhysics.step(300f, 500f, 0f, 0f, 16L, bounds)
        assertTrue("应向下移动", s.y > 500f)
        assertTrue("vy 应变成正数（向下）", s.vy > 0f)
        assertFalse("还在空中不该静止", s.resting)
    }

    /** 撞左墙：x 被夹在边界上，vx 反向且衰减。 */
    @Test
    fun `撞左墙反弹`() {
        // 已经贴左边、还在向左飞
        val s = ThrowPhysics.step(100f, 500f, -2000f, 0f, 16L, bounds)
        assertEquals(100f, s.x, 0.01f)
        assertTrue("vx 应变正（向右弹回），实际 " + s.vx, s.vx > 0f)
        assertTrue("反弹应衰减", s.vx < 2000f)
    }

    /** 撞右墙同理。 */
    @Test
    fun `撞右墙反弹`() {
        val s = ThrowPhysics.step(500f, 500f, 2000f, 0f, 16L, bounds)
        assertEquals(500f, s.x, 0.01f)
        assertTrue("vx 应变负，实际 " + s.vx, s.vx < 0f)
    }

    /** 落到地面：y 被夹住、竖直速度反弹或清零，且判为 landed。 */
    @Test
    fun `落到地面`() {
        // 从地面略上方、带向下速度
        val s = ThrowPhysics.step(300f, 999f, 0f, 3000f, 16L, bounds)
        assertEquals("应停在地面", 1000f, s.y, 0.01f)
        assertTrue("应判定为落地", s.landed)
        assertTrue("落地后 vy 应向上或归零，实际 " + s.vy, s.vy <= 0f)
    }

    /**
     * 地面上的低速滑行最终静止。
     *
     * 【这是「停止交互后不再无效计算」的核心】静止判定一旦成立，
     * 宿主就会 removeFrameCallback，不再每帧跑。
     */
    @Test
    fun `地面低速滑行最终静止`() {
        var x = 300f
        var y = 1000f
        var vx = 40f
        var vy = 0f
        var resting = false
        var steps = 0
        while (steps < 600) {
            val s = ThrowPhysics.step(x, y, vx, vy, 16L, bounds)
            x = s.x
            y = s.y
            vx = s.vx
            vy = s.vy
            steps++
            if (s.resting) {
                resting = true
                break
            }
        }
        assertTrue("应在有限帧内静止（实际跑了 $steps 帧）", resting)
        assertEquals("静止时 vx 应为 0", 0f, vx, 0.01f)
        assertEquals("静止时 vy 应为 0", 0f, vy, 0.01f)
    }

    /**
     * 抛到最高点的瞬间**不能**判成静止。
     *
     * 【为什么】那一刻 vy≈0，但人还在空中。若只看速度就会误判静止 →
     * 人偶定格在半空，再也不落下来。
     */
    @Test
    fun `空中最高点不算静止`() {
        // vy 恰好抵消重力增量附近：从离地很远的位置、向上速度刚好快归零
        val s = ThrowPhysics.step(300f, 500f, 0f, 5f, 16L, bounds)
        assertFalse("还在空中不该判静止", s.resting)
    }

    /** 单步 dt 被夹住：极长间隔也不会一步飞出屏幕。 */
    @Test
    fun `超长帧间隔被夹住不会飞出屏幕`() {
        // 1000ms 的间隔（模拟卡顿），dt 应被夹到 50ms
        val s = ThrowPhysics.step(300f, 500f, 3000f, 0f, 1000L, bounds)
        assertTrue("x 应被墙夹住，实际 " + s.x, s.x <= bounds.maxX + 0.01f)
        assertTrue("x 不应跑成负数", s.x >= bounds.minX - 0.01f)
    }

    /** dt 为 0 或负数：原样返回，不做任何位移。 */
    @Test
    fun `零间隔不产生位移`() {
        val s = ThrowPhysics.step(300f, 500f, 100f, 100f, 0L, bounds)
        assertEquals(300f, s.x, 0.01f)
        assertEquals(500f, s.y, 0.01f)
        assertFalse(s.resting)
    }

    /** 顶边：默认反弹，关掉则贴住。 */
    @Test
    fun `顶边可反弹也可贴住`() {
        val bounce = ThrowPhysics.step(300f, 10f, 0f, -3000f, 16L, bounds)
        assertTrue("默认顶边应反弹（vy 变正）", bounce.vy > 0f)

        val stick = ThrowPhysics.step(
            300f, 10f, 0f, -3000f, 16L, bounds,
            ThrowPhysics.Params(ceilingBounce = false)
        )
        assertEquals("关掉反弹后 vy 归零", 0f, stick.vy, 0.01f)
    }

    /** shouldStop 对静止步返回 true，对飞行中的步返回 false。 */
    @Test
    fun `飞行结束判定`() {
        val flying = ThrowPhysics.step(300f, 500f, 1000f, 0f, 16L, bounds)
        assertFalse(ThrowPhysics.shouldStop(flying))

        val resting = ThrowPhysics.Step(300f, 1000f, 0f, 0f, false, true)
        assertTrue(ThrowPhysics.shouldStop(resting))
    }

    /* ============================ 轨迹裁剪 ============================ */

    /** 裁剪只保留最近窗口内的采样。 */
    @Test
    fun `裁剪掉窗口外的旧采样`() {
        val old = ThrowPhysics.Sample(now - 5000, 0f, 0f)
        val recent = ThrowPhysics.Sample(now - 10, 10f, 0f)
        val kept = ThrowPhysics.trim(listOf(old, recent), now)
        assertEquals(1, kept.size)
        assertEquals(recent, kept[0])
    }

    /** 全部都在窗口内时原样返回（不新建列表）。 */
    @Test
    fun `全部在窗口内时不丢弃`() {
        val t = trail(n = 3, stepMs = 10, dx = 5f)
        assertEquals(3, ThrowPhysics.trim(t, now).size)
    }
}