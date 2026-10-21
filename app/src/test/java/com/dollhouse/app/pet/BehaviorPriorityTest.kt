package com.dollhouse.app.pet

import com.dollhouse.app.pet.behavior.ActionSemantic
import com.dollhouse.app.pet.behavior.PetBehaviorController
import com.dollhouse.app.pet.behavior.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[PetBehaviorController] 的优先级仲裁与生命周期测试。
 *
 * ## 为什么这份测试是「成败判据」的直接护栏
 * 任务书 §1.4 把「桌宠能实际使用动作系统」定为整个任务算不算成功的判据；
 * 而这条链路的**全部风险**都集中在两个地方：
 * 1. **交互不能被待机顶掉**（点了人偶却在伸懒腰 = 用户认为「点了没反应」）；
 * 2. **交互结束必须回落**（不回落到待机 = 桌面人偶永远定格在最后一帧 = 「卡住了」）。
 *
 * 这两条恰好是纯逻辑，可以在 JVM 上钉死，不必等真机。
 *
 * ## 关于 [PetBehaviorController.ActionPlayer]
 * 它有两个方法（`play` / `stop`），所以是普通 interface 而不是 `fun interface`——
 * 早期写成 `fun interface` 导致编译报「Fun interfaces must have exactly one abstract method」。
 */
class BehaviorPriorityTest {

    /** 记录调用的假播放器。 */
    private class FakePlayer : PetBehaviorController.ActionPlayer {
        val played = ArrayList<String>()
        var stops = 0
        override fun play(actionId: String, semantic: String) {
            played.add(semantic + ":" + actionId)
        }

        override fun stop() {
            stops++
        }
    }

    /** 一个把语义原样当动作 id 返回的解析器（除了 tap 故意不配，用来测「没配就不顶」）。 */
    private fun resolver(skip: Set<String> = emptySet()) =
        PetBehaviorController.ActionResolver { semantic ->
            if (skip.contains(semantic)) null else "act-" + semantic
        }

    private fun controller(player: FakePlayer, skip: Set<String> = emptySet()): PetBehaviorController {
        val c = PetBehaviorController()
        c.bind(resolver(skip), player)
        return c
    }

    // ============================ 优先级 ============================

    /** 基本盘：待机能播默认动作。 */
    @Test
    fun idle_playsIdleAction() {
        val p = FakePlayer()
        val c = controller(p)
        c.onIdle()
        assertEquals(listOf("idle:act-idle"), p.played)
        assertEquals(PlaybackState.PLAYING, c.state)
    }

    /**
     * **交互 > 待机**：交互进行中来的待机请求必须被挂起，不能顶掉交互。
     *
     * 【模拟的真实场景】用户按住人偶拖动（`drag`），此时 `PetAnimator` 的
     *   待机定时器到点了（它不知道用户在拖）→ 若待机能顶掉，就会看到
     *   「手还按着、人偶却开始伸懒腰」。
     */
    @Test
    fun idle_doesNotPreemptInteraction() {
        val p = FakePlayer()
        val c = controller(p)
        c.play(ActionSemantic.DRAG)
        p.played.clear()
        c.onIdle()
        assertTrue("待机不该产生新的播放调用", p.played.isEmpty())
        assertTrue("待机应被标记为挂起", c.isIdleSuspended())
        assertEquals("当前语义仍是拖动", ActionSemantic.DRAG, c.currentSemantic)
    }

    /**
     * **低优先级不能顶掉高优先级**：拖动中来了一个 `idle` 语义的 `play` 请求，
     *   同样必须被拒。
     *
     * 【与上一条的区别】上一条测的是 `onIdle()` 这个入口，
     *   这条测的是「直接调 `play(idle)`」——两条路都得挡。
     */
    @Test
    fun play_rejectsLowerPriority() {
        val p = FakePlayer()
        val c = controller(p)
        c.play(ActionSemantic.TAP)
        p.played.clear()
        c.play(ActionSemantic.IDLE)
        assertTrue("低优先级请求应被静默拒绝", p.played.isEmpty())
        assertEquals(ActionSemantic.TAP, c.currentSemantic)
    }

    /** 同优先级允许接管（比如从 drag 切到 tap 后手势升级）。 */
    @Test
    fun play_allowsSamePriority() {
        val p = FakePlayer()
        val c = controller(p)
        c.play(ActionSemantic.TAP)
        c.play(ActionSemantic.LONG_PRESS)
        assertEquals(2, p.played.size)
        assertEquals(ActionSemantic.LONG_PRESS, c.currentSemantic)
    }

    /**
     * 该语义没配动作时**不改现状**。
     *
     * 【为什么重要】用户可能只配了「点击动作」，没配「长按动作」。
     *   长按发生时若把当前动作清空，人偶会突然回到待机 —— 用户觉得「长按把它弄没了」。
     */
    @Test
    fun play_withoutAction_leavesStateUntouched() {
        val p = FakePlayer()
        val c = controller(p, skip = setOf(ActionSemantic.LONG_PRESS))
        c.play(ActionSemantic.TAP)
        p.played.clear()
        c.play(ActionSemantic.LONG_PRESS)
        assertTrue(p.played.isEmpty())
        assertEquals(ActionSemantic.TAP, c.currentSemantic)
    }

    /** 非标准语义直接忽略（防外部传垃圾字符串把状态搞乱）。 */
    @Test
    fun play_ignoresUnknownSemantic() {
        val p = FakePlayer()
        val c = controller(p)
        c.play("nonsense")
        assertTrue(p.played.isEmpty())
        assertEquals(null, c.currentSemantic)
    }

    // ============================ 回落 ============================

    /**
     * **交互结束必须回落**：`onInteractionEnd()` 要停止当前动作并尝试回到待机档。
     *
     * 【不回落的后果】非循环动作（比如「跳跃」）播完停在最后一帧，
     *   桌面上的人偶就**永远定格**在那里 —— 用户看到的是「卡住了」。
     */
    @Test
    fun onInteractionEnd_fallsBackToIdle() {
        val p = FakePlayer()
        val c = controller(p)
        c.play(ActionSemantic.TAP)
        p.played.clear()
        p.stops = 0
        c.onInteractionEnd()
        assertEquals("应先停掉当前动作", 1, p.stops)
        assertEquals("再回到待机档", listOf("idle:act-idle"), p.played)
        assertEquals(PlaybackState.PLAYING, c.state)
    }

    /**
     * 本来就在待机档时，`onInteractionEnd()` 不该做任何事。
     *
     * 【为什么必须这样】`PetAnimator` 每帧都可能调它（交互结束是「状态」不是「事件」）。
     *   若每次调用都 stop + 重播，待机动作会被反复打断重头开始 ——
     *   用户看到的是待机动作永远播不完第一帧。
     */
    @Test
    fun onInteractionEnd_isNoopWhenAlreadyIdle() {
        val p = FakePlayer()
        val c = controller(p)
        c.onIdle()
        p.played.clear()
        p.stops = 0
        c.onInteractionEnd()
        assertTrue("不该重复播放", p.played.isEmpty())
        assertEquals("不该重复停止", 0, p.stops)
    }

    /** 交互结束后 `idleSuspended` 必须复位（否则下次待机也会被挂起，永久不播）。 */
    @Test
    fun onInteractionEnd_clearsSuspendedFlag() {
        val p = FakePlayer()
        val c = controller(p)
        c.play(ActionSemantic.DRAG)
        c.onIdle()
        assertTrue(c.isIdleSuspended())
        c.onInteractionEnd()
        assertFalse(c.isIdleSuspended())
    }

    // ============================ 生命周期 ============================

    /** `pause` / `resume` 只在对应状态下生效，且不改当前语义。 */
    @Test
    fun pauseAndResume_areStateGuarded() {
        val p = FakePlayer()
        val c = controller(p)
        // 未播放时 pause 无效。
        c.pause()
        assertEquals(PlaybackState.IDLE, c.state)
        c.onIdle()
        c.pause()
        assertEquals(PlaybackState.PAUSED, c.state)
        c.resume()
        assertEquals(PlaybackState.PLAYING, c.state)
        // 已在播放时 resume 无效（不会把状态弄成别的）。
        c.resume()
        assertEquals(PlaybackState.PLAYING, c.state)
    }

    /**
     * **`release()` 必须幂等**。
     *
     * 【为什么幂等是硬要求（任务书 §3.5）】人偶移除有三条路径
     *   （用户手动关闭 / 服务销毁 / 多实例重挂），任意两条先后到达就会重复调用。
     *   不幂等的话第二条会重复 stop、重复清引用（若中间有非空断言就是一次崩溃）。
     */
    @Test
    fun release_isIdempotent() {
        val p = FakePlayer()
        val c = controller(p)
        c.onIdle()
        c.release()
        assertEquals(PlaybackState.RELEASED, c.state)
        val stopsAfterFirst = p.stops
        c.release()
        c.release()
        assertEquals("重复 release 不该再触发 stop", stopsAfterFirst, p.stops)
        assertTrue(c.isReleased())
    }

    /** `release` 之后一切操作都是空操作（防止释放后回调还在跑）。 */
    @Test
    fun afterRelease_allCallsAreNoop() {
        val p = FakePlayer()
        val c = controller(p)
        c.release()
        p.played.clear()
        p.stops = 0
        c.onIdle()
        c.play(ActionSemantic.TAP)
        c.onInteractionEnd()
        c.pause()
        c.resume()
        c.onIdle()
        assertTrue(p.played.isEmpty())
        assertEquals(0, p.stops)
        assertEquals(PlaybackState.RELEASED, c.state)
    }

    // ============================ 语义表 ============================

    /**
     * 语义优先级表符合契约：**交互 > 出现/消失 > 待机**。
     *
     * 【为什么「出现/消失」在待机之上】人偶挂载/移除是生命周期事件，
     *   它们必须能抢过待机 —— 否则「出现动作」会被已经在跑的待机动作顶掉。
     */
    @Test
    fun semanticPriority_ordersInteractionAboveIdle() {
        val tap = ActionSemantic.priority(ActionSemantic.TAP)
        val appear = ActionSemantic.priority(ActionSemantic.APPEAR)
        val idle = ActionSemantic.priority(ActionSemantic.IDLE)
        assertTrue("交互 > 出现/消失", tap > appear)
        assertTrue("出现/消失 > 待机", appear > idle)
    }

    /**
     * 标准语义齐备且不重复（对外契约，少一个就会让模型包的一个动作无处落）。
     *
     * 【r19】7 个 → 【v0.1.0】10 个：新增 `edge`（贴边固定壳）、`random`（随机组）、
     *   `manual`（手势点名播的动作）。三者都是五组体系的落点，缺一个就有动作播不出来。
     *   【原有的 7 个一个都不能少】它们是已发布模型包的对外契约。
     */
    @Test
    fun semanticTable_hasTenStandardIds() {
        assertEquals(10, ActionSemantic.ALL.size)
        assertEquals(10, ActionSemantic.ALL.toSet().size)
        for (s in listOf(
            "idle", "tap", "drag", "longpress", "appear", "disappear", "sleep",
            "edge", "random", "manual"
        )) {
            assertTrue("缺少标准语义：" + s, ActionSemantic.ALL.contains(s))
            assertTrue(ActionSemantic.isStandard(s))
        }
        assertFalse(ActionSemantic.isStandard(null))
        assertFalse(ActionSemantic.isStandard("bogus"))
    }

    /**
     * 【v0.1.0】新语义的优先级位置必须守住两条判据：
     * · 手动（用户点名要播）**低于**点击反馈、**高于**生命周期；
     * · 随机（系统自发）**低于**出现/消失，且**高于**贴边/常驻这两个基础态。
     */
    @Test
    fun semanticPriority_v010Placement() {
        val tap = ActionSemantic.priority(ActionSemantic.TAP)
        val manual = ActionSemantic.priority(ActionSemantic.MANUAL)
        val appear = ActionSemantic.priority(ActionSemantic.APPEAR)
        val random = ActionSemantic.priority(ActionSemantic.RANDOM)
        val edge = ActionSemantic.priority(ActionSemantic.EDGE)
        val idle = ActionSemantic.priority(ActionSemantic.IDLE)
        assertTrue("点击 > 手动", tap > manual)
        assertTrue("手动 > 生命周期", manual > appear)
        assertTrue("生命周期 > 随机", appear > random)
        assertTrue("随机 > 贴边", random > edge)
        assertTrue("贴边 > 常驻", edge > idle)
    }
}