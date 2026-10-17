package com.dollhouse.app.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[Springs] 纯数学部分的单元测试（建议①：测试底座）。
 *
 * 【为什么只测 lerp】`Springs.drive` 依赖 Android 的 `ValueAnimator`（要 Looper），
 *   属 JVM 单测跑不了的部分；而 **lerp / lerpInt 是纯函数**，且是「动画插值的最后一步」——
 *   轨道颜色、滑块位移、贴边吸附全都经过它。它一旦写错（例如把 progress 当成 0..100），
 *   表现是全 App 动画一起错，属于高价值护栏点。
 */
class SpringsTest {

    @Test
    fun lerp_endpoints() {
        assertEquals(0f, Springs.lerp(0f, 100f, 0f), 1e-6f)
        assertEquals(100f, Springs.lerp(0f, 100f, 1f), 1e-6f)
    }

    @Test
    fun lerp_midpoint() {
        assertEquals(50f, Springs.lerp(0f, 100f, 0.5f), 1e-6f)
        assertEquals(15f, Springs.lerp(10f, 20f, 0.5f), 1e-6f)
    }

    @Test
    fun lerp_supportsReversedRange() {
        // 从大往小插值（关闭动效就是这条路径）。
        assertEquals(50f, Springs.lerp(100f, 0f, 0.5f), 1e-6f)
        assertEquals(0f, Springs.lerp(100f, 0f, 1f), 1e-6f)
    }

    @Test
    fun lerp_negativeValues() {
        assertEquals(-50f, Springs.lerp(-100f, 0f, 0.5f), 1e-6f)
    }

    @Test
    fun lerpInt_roundsAndHitsEndpoints() {
        assertEquals(0, Springs.lerpInt(0, 100, 0f))
        assertEquals(100, Springs.lerpInt(0, 100, 1f))
        // 中间值不应越界（贴边吸附每帧都调用它改窗口 x）。
        for (p in 0..20) {
            val v = Springs.lerpInt(0, 100, p / 20f)
            assertTrue("progress=$p -> $v 越界", v in 0..100)
        }
    }

    @Test
    fun lerpInt_handlesShrinkingRange() {
        for (p in 0..20) {
            val v = Springs.lerpInt(100, 0, p / 20f)
            assertTrue("progress=$p -> $v 越界", v in 0..100)
        }
    }

    @Test
    fun springPresets_haveExpectedCharacter() {
        // 三个预设的手感定义（见 Springs 的注释：ζ = c / (2·√k)）：
        //   smooth ζ=1.00（不过冲）→ snappy ζ=0.73 → bouncy ζ=0.40（最弹）。
        //   【为什么断言这个】预设参数被误改会让「全 App 动画手感」静默变化，
        //   而这在没有视觉回归的情况下几乎不可能发现 —— 用数值把它钉住。
        val zetaSmooth = Springs.smooth().c / (2.0 * Math.sqrt(Springs.smooth().k))
        val zetaSnappy = Springs.snappy().c / (2.0 * Math.sqrt(Springs.snappy().k))
        val zetaBouncy = Springs.bouncy().c / (2.0 * Math.sqrt(Springs.bouncy().k))
        assertTrue("smooth ζ=$zetaSmooth 应最高", zetaSmooth > zetaSnappy)
        assertTrue("bouncy ζ=$zetaBouncy 应最低", zetaSnappy > zetaBouncy)
        // 阻尼比必须为正，否则弹簧永不收敛（动画卡住不结束）。
        assertTrue(zetaBouncy > 0.0)
    }

    @Test
    fun spring_integratesAndSettles() {
        // 【纯数学类可直接单测】从 0 起跳，按 60fps 推进，应在 2 秒内收敛到就位。
        val s = Spring(420.0, 30.0)
        s.start()
        assertEquals(0.0, s.x, 1e-9)
        var settledAt = -1
        for (i in 1..120) {
            if (s.step(1.0 / 60.0)) {
                settledAt = i
                break
            }
        }
        assertTrue("应在前 120 帧内收敛，实际 settledAt=$settledAt", settledAt > 0)
    }

    @Test
    fun spring_restartKeepsVelocityWithinClamp() {
        // 连续触发不应把速度累积到发散（工程里「连点开关」就是这条路径）。
        val s = Spring(300.0, 14.0)
        repeat(50) {
            s.v = 100.0
            s.start()
            assertTrue("v 应被 clamp 到 ±14，实际 ${s.v}", s.v <= 14.0 && s.v >= -14.0)
        }
    }

    @Test
    fun spring_settleGoesToRest() {
        val s = Spring()
        s.settle()
        assertEquals(1.0, s.x, 1e-9)
        assertEquals(0.0, s.v, 1e-9)
        assertTrue(s.isSettled())
    }
}