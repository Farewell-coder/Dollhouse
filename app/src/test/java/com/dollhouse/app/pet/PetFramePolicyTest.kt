package com.dollhouse.app.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[PetFramePolicy] 的单元测试 —— 帧率决策的**行为等价护栏**。
 *
 * ## 为什么必须测这个
 * 任务书把这件事列为红线锚点 #1/#2：
 * > `frameBusy()`：12 字段 OR 逻辑 · `nextFrameDelay()`：5 档判定 + 250ms 双语义
 *
 * 抽离 `PetFramePolicy` 的目的**不是改行为**，而是让这套逻辑可测。
 * 所以这些测试的性质是「锁住改造前的行为」：
 * · 12 个字段**逐个**验证——少一个就会让某类动画掉帧（用户看到卡顿）；
 * · 5 档判定**顺序**也要锁——`lowPower` 必须优先于屏灭、屏灭优先于忙，
 *   顺序一变，熄屏省电策略就失效（v2.10.2 专门修过这个）；
 * · 两个 250ms **数值相同、语义不同**，必须都能取到。
 */
class PetFramePolicyTest {

    /** 一个「什么都不忙」的基准状态：纯站立呼吸态。 */
    private fun idle() = PetFramePolicy.State(
        touchBusy = false,
        snapActive = false,
        dragging = false,
        jumpT = -1f,
        landT = -1f,
        blinkT = -1f,
        idleAction = 0,
        idleT = -1f,
        expression = 0,
        dragLift = 0f,
        tilt = 0f,
        gaze = 0f,
        gazeTarget = 0f
    )

    // ============================ 12 字段 OR ============================

    @Test
    fun idleState_needsNoFullSpeed() {
        assertFalse(PetFramePolicy.needsFullSpeed(idle()))
    }

    @Test
    fun touchBusy_needsFullSpeed() {
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(touchBusy = true)))
    }

    @Test
    fun snapActive_needsFullSpeed() {
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(snapActive = true)))
    }

    @Test
    fun dragging_needsFullSpeed() {
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(dragging = true)))
    }

    @Test
    fun jumping_needsFullSpeed() {
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(jumpT = 0f)))
    }

    @Test
    fun landing_needsFullSpeed() {
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(landT = 0f)))
    }

    @Test
    fun blinking_needsFullSpeed() {
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(blinkT = 0f)))
    }

    @Test
    fun idleAction_needsFullSpeed() {
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(idleAction = 1)))
    }

    @Test
    fun idleT_needsFullSpeed() {
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(idleT = 0f)))
    }

    @Test
    fun expression_needsFullSpeed() {
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(expression = 2)))
    }

    /**
     * 拖拽抬升「收敛中」阈值：> 0.002 才算忙。
     *
     * 【为什么必须用边界值测】这个 0.002 决定了松手后动画还跑不跑最后几帧。
     *   写错（比如改成 0.02）会让动画在收尾时提前定格，看起来「弹到一半卡住」。
     */
    @Test
    fun dragLift_thresholdIsTwoThousandths() {
        assertFalse(PetFramePolicy.needsFullSpeed(idle().copy(dragLift = 0.002f)))
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(dragLift = 0.0021f)))
    }

    /** 倾斜「回正中」阈值：|tilt| > 0.05。 */
    @Test
    fun tilt_thresholdIsFiveHundredths() {
        assertFalse(PetFramePolicy.needsFullSpeed(idle().copy(tilt = 0.05f)))
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(tilt = -0.0501f)))
    }

    /** 视线「插值未收敛」阈值：|gaze - gazeTarget| > 0.004。 */
    @Test
    fun gazeDelta_thresholdIsFourThousandths() {
        assertFalse(PetFramePolicy.needsFullSpeed(idle().copy(gaze = 0f, gazeTarget = 0.004f)))
        assertTrue(PetFramePolicy.needsFullSpeed(idle().copy(gaze = 0f, gazeTarget = 0.005f)))
    }

    // ============================ 5 档判定 ============================

    /**
     * 档位 ①：熄屏低功耗取 1s 心跳。
     *
     * 【为什么不是 250ms】熄屏时既不推进动画也不重绘，250ms 纯属空转白耗电。
     */
    @Test
    fun lowPower_usesHeartbeat() {
        val ms = PetFramePolicy.delayMs(idle(), lowPower = true, shown = true, screenOn = true, lean = 0f)
        assertEquals(PetFramePolicy.MS_HEARTBEAT, ms)
    }

    /**
     * 档位 ②：屏灭兜底取 250ms。
     *
     * 【为什么这条最容易被改坏】`isShown` 只看「已 attach + visibility」，
     *   熄屏时它**仍为 true** —— 所以必须再问一次屏幕开关，
     *   否则屏灭后桌宠照旧 30fps 空转（用户感觉不到，但耗电明显）。
     */
    @Test
    fun screenOff_usesScreenOffConstant() {
        val ms = PetFramePolicy.delayMs(idle(), lowPower = false, shown = true, screenOn = false, lean = 0f)
        assertEquals(PetFramePolicy.MS_SCREEN_OFF, ms)
    }

    /** 不可见（未 attach）同样走屏灭档 —— 没人看的时候不该全速。 */
    @Test
    fun notShown_usesScreenOffConstant() {
        val ms = PetFramePolicy.delayMs(idle(), lowPower = false, shown = false, screenOn = true, lean = 0f)
        assertEquals(PetFramePolicy.MS_SCREEN_OFF, ms)
    }

    /** 档位 ③：有动作 → 全速 16ms。 */
    @Test
    fun busy_usesFullSpeed() {
        val s = idle().copy(dragging = true)
        val ms = PetFramePolicy.delayMs(s, lowPower = false, shown = true, screenOn = true, lean = 0f)
        assertEquals(PetFramePolicy.MS_TOUCH_BUSY, ms)
    }

    /** 档位 ④：贴边偷看且无动作 → 250ms（与屏灭同值、不同常量）。 */
    @Test
    fun edgeLean_usesEdgeLowPower() {
        val ms = PetFramePolicy.delayMs(idle(), lowPower = false, shown = true, screenOn = true, lean = 25f)
        assertEquals(PetFramePolicy.MS_EDGE_LOW_POWER, ms)
    }

    /** 档位 ⑤：纯站立呼吸 → 32ms。 */
    @Test
    fun breathing_usesIdleConstant() {
        val ms = PetFramePolicy.delayMs(idle(), lowPower = false, shown = true, screenOn = true, lean = 0f)
        assertEquals(PetFramePolicy.MS_IDLE, ms)
    }

    // ============================ 顺序 / 双语义 ============================

    /**
     * **判定顺序**：熄屏低功耗优先于「忙」。
     *
     * 【为什么这条最关键】若把 `lowPower` 判断挪到 `needsFullSpeed` 之后，
     *   熄屏时一旦有动画在跑（比如手指还按着就熄屏了），帧率会停在 16ms ——
     *   整晚全速空转。任务书红线锚点 #2 专门点了这个顺序。
     */
    @Test
    fun lowPower_winsOverBusy() {
        val busy = idle().copy(dragging = true, touchBusy = true)
        val ms = PetFramePolicy.delayMs(busy, lowPower = true, shown = true, screenOn = true, lean = 0f)
        assertEquals("熄屏必须压过忙", PetFramePolicy.MS_HEARTBEAT, ms)
    }

    /** 判定顺序：屏灭优先于忙（屏幕都黑了，没必要全速）。 */
    @Test
    fun screenOff_winsOverBusy() {
        val busy = idle().copy(dragging = true)
        val ms = PetFramePolicy.delayMs(busy, lowPower = false, shown = true, screenOn = false, lean = 0f)
        assertEquals(PetFramePolicy.MS_SCREEN_OFF, ms)
    }

    /** 判定顺序：忙优先于贴边低耗（贴边时用户点了它，必须立刻提帧）。 */
    @Test
    fun busy_winsOverEdgeLowPower() {
        val s = idle().copy(blinkT = 0f)
        val ms = PetFramePolicy.delayMs(s, lowPower = false, shown = true, screenOn = true, lean = 25f)
        assertEquals(PetFramePolicy.MS_TOUCH_BUSY, ms)
    }

    /**
     * **250ms 双语义**：两个常量数值相同但**是两个名字**。
     *
     * 【为什么写这条测试】把两个 250 合并成一个常量看起来很「干净」，
     *   但那样「改屏灭档顺带改了贴边档」——改造前正是这个隐患。
     *   测试在这里显式锁住「数值相同」这个现状，
     *   同时用两个名字说明它们可以**独立**变化。
     */
    @Test
    fun twoTwoFiftySemantics_haveSameValueButSeparateNames() {
        assertEquals(PetFramePolicy.MS_EDGE_LOW_POWER, PetFramePolicy.MS_SCREEN_OFF)
        assertTrue("两个常量都必须存在且可独立引用", PetFramePolicy.MS_EDGE_LOW_POWER == 250L)
    }

    /** 各档数值本身也是契约（改动会让省电策略失效）。 */
    @Test
    fun tierValues_areStable() {
        assertEquals(16L, PetFramePolicy.MS_TOUCH_BUSY)
        assertEquals(32L, PetFramePolicy.MS_IDLE)
        assertEquals(250L, PetFramePolicy.MS_EDGE_LOW_POWER)
        assertEquals(250L, PetFramePolicy.MS_SCREEN_OFF)
        assertEquals(1000L, PetFramePolicy.MS_HEARTBEAT)
    }
}