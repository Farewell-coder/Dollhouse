package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】把「导入帧的尺寸判定」钉死。
 *
 * ## 这份测试挡的是什么
 *
 * 需求里的六项验收里，前三项全是尺寸判定：
 * · 画布不同但人物实际大小相同 → 必须算出「差不多大」的框；
 * · 画布相同但留白不同 → 必须算出「不一样大」的框（否则等于没适配）；
 * · 同动作不同帧轮廓变化 → 整组取并集，不能逐帧抖动。
 *
 * 这些都属于「看起来对但实际错」的类别，肉眼极难发现，所以用断言固定。
 *
 * ## 为什么测 BodyBaseline 而不测 FrameScope
 *
 * 后者要 `Bitmap`（Android 运行时才有）。判定规则全在前者，把它测透就够了。
 */
class BodyBaselineTest {

    /** 一帧的框，省得每处都写 4 个参数。 */
    private fun box(l: Float, t: Float, r: Float, b: Float) = BodyBaseline.Box(l, t, r, b)

    @Test
    fun box_widthAndHeight() {
        val b = box(0.1f, 0.2f, 0.6f, 0.9f)
        assertEquals(0.5f, b.width, 1e-6f)
        assertEquals(0.7f, b.height, 1e-6f)
    }

    /** 全透明（宽高为 0）不是有效框 —— 否则会把整组基准压成一个点。 */
    @Test
    fun zeroSizeBox_isInvalid() {
        assertFalse(box(0.5f, 0.5f, 0.5f, 0.5f).isValid())
        assertFalse(box(0f, 0f, 0f, 0f).isValid())
    }

    /** 超出 0..1 的框无效（图被改坏 / 计算结果越界）。 */
    @Test
    fun outOfRangeBox_isInvalid() {
        assertFalse(box(-0.5f, 0f, 0.5f, 1f).isValid())
        assertFalse(box(0f, 0f, 1.5f, 1f).isValid())
    }

    /**
     * **核心①：整组取并集**。
     *
     * 两帧轮廓不同（第二帧手臂外伸），并集必须覆盖两者。
     * 这是「不能逐帧算」的结构性保证 —— 逐帧算会让第二帧的尺寸基准变大，
     * 播放时人偶在那一帧突然缩小。
     */
    @Test
    fun merge_coversAllFrames() {
        val a = box(0.2f, 0.1f, 0.8f, 0.9f)
        val b = box(0.1f, 0.1f, 0.9f, 0.9f)   // 手臂外伸
        val m = BodyBaseline.merge(listOf(a, b))!!
        assertEquals(0.1f, m.left, 1e-6f)
        assertEquals(0.9f, m.right, 1e-6f)
        assertEquals(0.1f, m.top, 1e-6f)
        assertEquals(0.9f, m.bottom, 1e-6f)
    }

    /** 单帧全透明时不连累整组（淡出到空的帧是合法的）。 */
    @Test
    fun merge_ignoresInvalidMembers() {
        val good = box(0.2f, 0.2f, 0.8f, 0.8f)
        val empty = box(0f, 0f, 0f, 0f)
        val m = BodyBaseline.merge(listOf(good, empty, good))!!
        assertEquals(0.2f, m.left, 1e-6f)
        assertEquals(0.8f, m.right, 1e-6f)
    }

    /** 全部无效 → null（调用方据此放弃写入基准）。 */
    @Test
    fun merge_allInvalid_returnsNull() {
        assertNull(BodyBaseline.merge(listOf(box(0f, 0f, 0f, 0f), box(0.3f, 0.3f, 0.3f, 0.3f))))
        assertNull(BodyBaseline.merge(emptyList()))
    }

    /**
     * **核心②：画布相同但留白不同 → 必须算出不同的框**。
     *
     * 这正是需求的测试项之一。如果这里算出来一样，说明判定逻辑根本没看内容区，
     * 那整个适配就是假的。
     */
    @Test
    fun sameCanvasDifferentPadding_givesDifferentBox() {
        val tight = listOf(box(0.02f, 0.02f, 0.98f, 0.98f))     // 几乎没留白
        val loose = listOf(box(0.30f, 0.30f, 0.70f, 0.70f))     // 留白很宽
        val a = BodyBaseline.merge(tight)!!
        val b = BodyBaseline.merge(loose)!!
        assertTrue("留白宽的框必须更小", b.width < a.width * 0.6f)
        assertTrue("留白宽的框必须更小", b.height < a.height * 0.6f)
    }

    /**
     * **核心③：留白几乎没有 → 判定为不可靠**（写基准等于白写）。
     *
     * 这一条让「导出图本来就裁得很紧」的情况保持旧行为，
     * 避免往档里塞一个 ≈ 整画布的无效基准。
     */
    @Test
    fun noPadding_isUnreliable() {
        val full = listOf(box(0f, 0f, 1f, 1f))
        assertFalse(BodyBaseline.isReliable(full))
        assertTrue(BodyBaseline.isReliable(listOf(box(0.1f, 0.1f, 0.9f, 0.9f))))
    }

    /**
     * **核心④：混了尺寸差异过大的帧 → 判定为不可靠**。
     *
     * 典型场景：用户把「大立绘」和「小图标」丢进同一个动作。
     * 这时无论怎么取并集都会有一方不对，正确做法是放弃自动适配。
     */
    @Test
    fun mixedSizes_isUnreliable() {
        val big = box(0.1f, 0.1f, 0.9f, 0.9f)
        val tiny = box(0.40f, 0.40f, 0.55f, 0.55f)   // 只有大图的 1/5
        assertFalse(BodyBaseline.isReliable(listOf(big, tiny)))
    }

    /** 轮廓小幅波动是正常的（呼吸/摆动），不能被误判成「混合尺寸」。 */
    @Test
    fun normalOutlineVariation_isReliable() {
        val frames = listOf(
            box(0.10f, 0.10f, 0.90f, 0.90f),
            box(0.12f, 0.10f, 0.88f, 0.90f),
            box(0.10f, 0.12f, 0.90f, 0.88f)
        )
        assertTrue(BodyBaseline.isReliable(frames))
    }

    /** 编码/解码往返不丢精度，且格式不对时安全返回 null。 */
    @Test
    fun encodeDecode_roundTrip() {
        val a = box(0.1234f, 0.2345f, 0.8765f, 0.7654f)
        val b = BodyBaseline.Box.decode(a.encode())!!
        assertEquals(a.left, b.left, 1e-3f)
        assertEquals(a.top, b.top, 1e-3f)
        assertEquals(a.right, b.right, 1e-3f)
        assertEquals(a.bottom, b.bottom, 1e-3f)
    }

    @Test
    fun decode_garbageReturnsNull() {
        assertNull(BodyBaseline.Box.decode(null))
        assertNull(BodyBaseline.Box.decode(""))
        assertNull(BodyBaseline.Box.decode("1,2,3"))
        assertNull(BodyBaseline.Box.decode("a,b,c,d"))
        assertNull(BodyBaseline.Box.decode("0,0,0,0"))
    }

    /** 解码结果同样要过 isValid（防止档被手改成越界值）。 */
    @Test
    fun decode_rejectsOutOfRange() {
        assertNull(BodyBaseline.Box.decode("-1,0,0.5,1"))
        assertNull(BodyBaseline.Box.decode("0,0,2,1"))
    }

    /** 空列表不算可靠（新动作还没导帧时不该写基准）。 */
    @Test
    fun emptyFrames_isUnreliable() {
        assertFalse(BodyBaseline.isReliable(emptyList()))
    }
}