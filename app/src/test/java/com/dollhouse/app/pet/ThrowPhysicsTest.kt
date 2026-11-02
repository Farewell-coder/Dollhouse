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
    /**
     * 撞左墙反弹。
     *
     * 【附录C-6·断言口径已更新】原来断言 `x == minX`（位置被夹死在边界上）。
     *   那正是被修掉的旧行为：穿透掉的位移直接丢弃，于是 60Hz 与 120Hz 结果不同。
     *   现在剩余位移会镜像走完，所以：
     *     · x 必须仍在边界**内侧**（不能穿出去）；
     *     · x 不能恰好等于 minX（那说明剩余位移又被丢了）；
     *     · vx 反向且衰减。
     */
    @Test
    fun `撞左墙反弹`() {
        // 已经贴左边、还在向左飞
        val s = ThrowPhysics.step(100f, 500f, -2000f, 0f, 16L, bounds)
        assertTrue("不能穿出左边界，实际 " + s.x, s.x >= bounds.minX - 0.01f)
        assertTrue("应反弹离开边界而不是夹死在边界上，实际 " + s.x, s.x > bounds.minX)
        assertTrue("vx 应变正（向右弹回），实际 " + s.vx, s.vx > 0f)
        assertTrue("反弹应衰减", s.vx < 2000f)
    }

    /** 撞右墙同理（口径同 [撞左墙反弹]）。 */
    @Test
    fun `撞右墙反弹`() {
        val s = ThrowPhysics.step(500f, 500f, 2000f, 0f, 16L, bounds)
        assertTrue("不能穿出右边界，实际 " + s.x, s.x <= bounds.maxX + 0.01f)
        assertTrue("应反弹离开边界而不是夹死在边界上，实际 " + s.x, s.x < bounds.maxX)
        assertTrue("vx 应变负，实际 " + s.vx, s.vx < 0f)
    }

    /**
     * 从高处带向下速度，撞地后反弹离开。
     *
     * 【附录C-6·断言口径已更新】原来断言 `y == groundY`（夹死在地面）。
     *   现在反弹会把剩余位移镜像走完，所以断言「不穿地 + 判为落地 + vy 向上」。
     */
    @Test
    fun `落到地面`() {
        val s = ThrowPhysics.step(300f, 999f, 0f, 3000f, 16L, bounds)
        assertTrue("不能穿到地面以下，实际 " + s.y, s.y <= bounds.groundY + 0.01f)
        assertTrue("应判定为落地", s.landed)
        assertTrue("落地后 vy 应向上，实际 " + s.vy, s.vy < 0f)
    }

    /**
     * 已经贴在地面上、竖直速度很小时，必须稳稳吸住。
     *
     * 【这条钉的是「支撑状态」】不吸住的话，每个子步都会重新累加重力，
     *   末态会带 `g × 子步数 × 1/240` 的残余速度（实测 52.4 px/s），
     *   表现为人偶在地面上微微抖动。
     */
    @Test
    fun `贴地时竖直速度被吸住`() {
        // 起点就在地面上（y == groundY），竖直速度很小
        val s = ThrowPhysics.step(300f, 1000f, 0f, 10f, 16L, bounds)
        assertEquals("应贴住地面", bounds.groundY, s.y, 0.5f)
        assertEquals("竖直速度应被吸住为 0", 0f, s.vy, 0.01f)
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

    /* ============================ 帧率无关性（附录C-6） ============================ */

    /**
     * 同一个初速下，60Hz 与 120Hz 的落点必须基本一致。
     *
     * 【为什么需要这条】改造前是「一次显式欧拉 + 位置夹取」：撞边时把位置夹到边界，
     *   穿透掉的位移直接丢弃。60Hz 每帧的穿透量是 120Hz 的两倍，于是同一个甩动手势
     *   在不同刷新率的手机上抛出的距离不同。改成固定子步长 + 镜像反射后，
     *   「一帧走几个子步」只影响精度、不影响结果 —— 这条断言就是那个性质的回归保护。
     *
     * 【容差为什么给 2px】子步长 1/240s 下，60Hz 每帧 4 个子步、120Hz 每帧 2 个，
     *   残余差异只来自最后一步不满子步长。实测 x 差 0.43px、y 差 0.01px，
     *   给 2px 是为浮点累加留余量，仍足以拦住「按帧率漂移」这类回归。
     */
    @Test
    fun `甩抛落点与帧率无关`() {
        fun simulate(frameMs: Long): ThrowPhysics.Step {
            // 从中偏左向上右甩，保证生命周期内至少撞一次墙 + 落地。
            var s = ThrowPhysics.Step(300f, 800f, 1800f, -900f, landed = false, resting = false)
            var t = 0L
            // 2 秒足够走完整个生命周期。
            while (t < 2000L) {
                s = ThrowPhysics.step(s.x, s.y, s.vx, s.vy, frameMs, bounds)
                t += frameMs
                if (s.resting) {
                    break
                }
            }
            return s
        }
        val at120 = simulate(8L)    // 约 120Hz
        val at60 = simulate(16L)    // 约 60Hz
        assertEquals("60Hz 与 120Hz 的落点 x 应一致", at120.x, at60.x, 2f)
        assertEquals("60Hz 与 120Hz 的落点 y 应一致", at120.y, at60.y, 2f)
    }

    /**
     * 撞墙后剩余位移必须继续走完，而不是把穿透量丢掉。
     *
     * 【口径】从贴着右墙的位置以极大水平速度往外抛一帧：
     *   旧实现会把位置夹到 maxX 就结束（x 恰好等于 maxX）；
     *   正确实现会把剩余位移镜像后继续走，x 应明显小于 maxX。
     *
     * 【为什么断言「小于」而不是具体值】具体值依赖子步长，
     *   钉死数字会让这条用例变成「改子步长就红」的脆弱测试；性质才是稳定的。
     */
    @Test
    fun `撞墙后剩余位移继续走完`() {
        val s = ThrowPhysics.step(
            x = 499f, y = 500f,
            vx = 6000f, vy = 0f,
            dtMs = 16L, b = bounds
        )
        assertTrue(
            "撞右墙后应镜像走完剩余位移（x 应小于 maxX=500），实际 " + s.x,
            s.x < 500f
        )
        assertTrue("水平速度应已反向（向左），实际 " + s.vx, s.vx < 0f)
    }

    /** 静止判定不能在「抛到最高点」误判（那一刻 vy≈0 但还在空中）。 */
    @Test
    fun `最高点不被误判为静止`() {
        val s = ThrowPhysics.step(
            x = 300f, y = 200f,
            vx = 0f, vy = 1f,
            dtMs = 16L, b = bounds
        )
        assertFalse("在空中不该判定静止", s.resting)
    }
}