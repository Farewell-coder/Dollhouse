package com.dollhouse.app.pet

import com.dollhouse.app.data.DollAnim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】把「内置动作每一帧的时间线参数」钉死，重点是**贴边动作不含姿态倾斜**。
 *
 * ## 这份测试要挡的具体事故
 *
 * 贴边动作（`KIND_PEEK`）最早的实现是 `v[6] = EDGE_LEAN * sin(πt)` ——
 * 把「探出去再缩回来」画进了帧里。问题在于正弦包络首末帧为 0：
 *
 * ```
 * 帧0: 直立  →  帧16: 歪 25°  →  帧31: 直立
 * 循环播放 = 站直 → 歪下 → 站直 → 歪下 …
 * ```
 *
 * 用户看到的就是「贴边动作在反复进出贴边」。而它的语义应当是
 * **「已经贴在边上、一直悬在那里」的循环动效**，姿态必须**恒定**。
 *
 * 更麻烦的是：`PetView.onDraw` 在有动作时是 `act.draw(canvas); return`
 * （在 `PetTransform` 之前），所以 `lean` 从不作用到动作画面 ——
 * 「帧里不画，系统会帮忙歪」这个直觉是错的。姿态现在由
 * `ActionPlayerCore.leanProvider` 按 `lean` 旋转整幅画面。
 *
 * 这两条只要被谁改回去，画面都会错，而且**不会崩、不会报错**。
 * 所以用断言写成契约。
 */
class BakeTimelineTest {

    /** 造一个长度正确的参数数组（与 `AnimBaker` 里的用法一致）。 */
    private fun params(): FloatArray = FloatArray(BakeParams.SIZE)

    @Test
    fun size_matchesContract() {
        assertEquals(12, BakeParams.SIZE)
    }

    /**
     * **核心契约：贴边动作的每一帧都不许带倾斜。**
     *
     * 只要有人再写一次 `v[6] = ...`，这条就会红。
     */
    @Test
    fun peek_neverCarriesLean() {
        val n = 32
        val v = params()
        for (i in 0 until n) {
            BakeTimeline.fill(DollAnim.KIND_PEEK, i, n, v)
            assertEquals("第 $i 帧不该有贴边倾斜", 0f, v[BakeParams.IDX_LEAN], 0f)
        }
    }

    /**
     * 贴边动作的姿态由播放层负责，这里**不能**顺便带别的横向位移：
     * `IDX_TILT`（待机/拖拽倾斜）同样必须为 0，否则会与播放层的旋转叠加。
     */
    @Test
    fun peek_neverCarriesTilt() {
        val n = 32
        val v = params()
        for (i in 0 until n) {
            BakeTimeline.fill(DollAnim.KIND_PEEK, i, n, v)
            assertEquals("第 $i 帧不该有待机倾斜", 0f, v[BakeParams.IDX_TILT], 0f)
        }
    }

    /**
     * 贴边是**循环**动作：首帧与末帧的呼吸相位必须相同。
     *
     * 用 `sin(πt)` 这类首末为 0 的包络做不到「整轮」，会表现为循环处一顿。
     */
    @Test
    fun peek_loopsSeamlessly_phaseWrapsToSameValue() {
        val n = 32
        val a = params()
        val b = params()
        BakeTimeline.fill(DollAnim.KIND_PEEK, 0, n, a)
        BakeTimeline.fill(DollAnim.KIND_PEEK, n - 1, n, b)
        val fullTurn = (2 * Math.PI).toFloat()
        assertEquals("首帧相位应为 0", 0f, a[BakeParams.IDX_PHASE], 1e-4f)
        assertEquals("末帧相位应为整轮", fullTurn, b[BakeParams.IDX_PHASE], 1e-4f)
        // 相位差为 2π ⇒ sin 相同，循环处不跳。这里直接比较正弦值更贴近实际观感。
        assertEquals(
            "首末帧的 sin(phase) 必须相同",
            Math.sin(a[BakeParams.IDX_PHASE].toDouble()),
            Math.sin(b[BakeParams.IDX_PHASE].toDouble()),
            1e-4
        )
    }

    /**
     * 贴边动作里应当**有**一次眨眼，且只发生在中段 ——
     * 否则 32 帧就是「一模一样」，看不出还在呼吸。
     */
    @Test
    fun peek_blinksOnceAroundTheMiddle() {
        val n = 32
        val v = params()
        var blinked = 0
        var first = -1
        var last = -1
        for (i in 0 until n) {
            BakeTimeline.fill(DollAnim.KIND_PEEK, i, n, v)
            if (v[BakeParams.IDX_BLINK] >= 0f) {
                blinked++
                if (first < 0) first = i
                last = i
            }
        }
        assertTrue("贴边动作应当有一小段眨眼帧，实际 $blinked 帧", blinked in 1..(n / 2))
        assertTrue("眨眼应落在中段，实际 $first..$last", first > 0 && last < n - 1)
    }

    /**
     * 贴边动作的眼睛必须是「睁着」的正常值：`IDX_EYE_SCALE` 中性值为 1。
     *
     * 这条防的是另一类历史事故：漏赋 `IDX_EYE_SCALE` 会让
     * `buildMesh` 把眼睛纵向压扁到极值（每一帧都是扁眼）。
     */
    @Test
    fun peek_keepsEyesOpen() {
        val n = 32
        val v = params()
        for (i in 0 until n) {
            BakeTimeline.fill(DollAnim.KIND_PEEK, i, n, v)
            assertEquals("第 $i 帧的眼睛缩放应保持中性值", 1f, v[BakeParams.IDX_EYE_SCALE], 1e-4f)
        }
    }

    /**
     * 趴姿（`KIND_PERCH`）**相反**：它走 `enterPeek` 分支，`lean` 为 0，
     * 所以倾斜必须画进帧里。这里断言它确实带倾斜，防止「一刀切把两处都改掉」。
     */
    @Test
    fun perch_doesCarryLean() {
        val n = 12
        val v = params()
        var maxLean = 0f
        for (i in 0 until n) {
            BakeTimeline.fill(DollAnim.KIND_PERCH, i, n, v)
            maxLean = Math.max(maxLean, Math.abs(v[BakeParams.IDX_LEAN]))
        }
        assertTrue("趴姿应当自带倾斜（与贴边不同），实际最大 $maxLean", maxLean > 1f)
    }

    /**
     * 每一帧都必须从**中性值**起步。
     *
     * 判据只看「**本条 kind 不会主动写**的那两位」：
     * `IDX_IDLE_SCALE_Y` 与 `IDX_EYE_SCALE` 恒为 1。
     * 只要有人绕过 `BakeParams.reset` 直接写数组，就会露出 0。
     *
     * 【为什么不断言 jumpT / landT 恒为 −1】`KIND_JUMP` 正常会写 `v[1] = t`，
     *   而第 0 帧的 `t` 恰好是 0 —— 那是「刚起跳」，是**合法值**，不是漏赋。
     *   断言写成「恒为 −1」会在 jump / land 上假红（这条测试第一版就踩了）。
     *   要挡的是「该是 1 却成了 0」的那种漏赋，所以只断言两个缩放位。
     */
    @Test
    fun everyKind_startsFromNeutralValues() {
        val kinds = listOf(
            DollAnim.KIND_BREATH, DollAnim.KIND_BLINK, DollAnim.KIND_GAZE,
            DollAnim.KIND_TAIL, DollAnim.KIND_AHOGE, DollAnim.KIND_HAIR,
            DollAnim.KIND_STRETCH, DollAnim.KIND_LOOK, DollAnim.KIND_SQUINT,
            DollAnim.KIND_NOD, DollAnim.KIND_HOP, DollAnim.KIND_JUMP,
            DollAnim.KIND_DRAG, DollAnim.KIND_LAND, DollAnim.KIND_PEEK,
            DollAnim.KIND_PERCH, DollAnim.KIND_SURPRISED, DollAnim.KIND_HAPPY,
            DollAnim.KIND_DIZZY
        )
        val v = params()
        for (kind in kinds) {
            BakeTimeline.fill(kind, 0, 10, v)
            assertEquals("$kind 的 idleScaleY 中性值应为 1", 1f, v[BakeParams.IDX_IDLE_SCALE_Y], 1e-5f)
            assertEquals("$kind 的 eyeScale 中性值应为 1", 1f, v[BakeParams.IDX_EYE_SCALE], 1e-5f)
        }
    }

    /** 单帧动作（n = 1）不能除零，且必须给出可用的中性参数。 */
    @Test
    fun singleFrame_doesNotDivideByZero() {
        val v = params()
        BakeTimeline.fill(DollAnim.KIND_BREATH, 0, 1, v)
        assertEquals(0f, v[BakeParams.IDX_PHASE], 1e-6f)
        assertEquals(1f, v[BakeParams.IDX_EYE_SCALE], 1e-6f)
    }

    /**
     * 未知 kind 必须安静地退化成「中性参数」，而不是抛异常 ——
     * `DollAnim.PLANS` 里将来加了新类型但忘了写分支时，宁可播一个静止帧，
     * 也不能让烘帧整体崩掉（会连带整个动作列表变空）。
     */
    @Test
    fun unknownKind_fallsBackToNeutral() {
        val v = params()
        BakeTimeline.fill("no_such_kind", 3, 10, v)
        assertEquals(0f, v[BakeParams.IDX_PHASE], 1e-6f)
        assertEquals(0f, v[BakeParams.IDX_LEAN], 1e-6f)
        assertEquals(1f, v[BakeParams.IDX_EYE_SCALE], 1e-6f)
    }
}