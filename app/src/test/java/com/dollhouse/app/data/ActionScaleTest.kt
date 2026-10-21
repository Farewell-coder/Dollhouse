package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ActionScale] 的回归测试 —— 钉住「换动作不改变基础显示大小」这条验收线。
 *
 * 需求书第九条重点验收：
 *   「角色在切换动作时不能因素材边界差异突然改变基础显示大小」。
 * 这组用例就是为了让这条规则**在改代码时立刻报警**，而不是等真机看出来。
 */
class ActionScaleTest {

    /** 窗口：宽 300、高 400（模拟桌宠典型窗口）。 */
    private val cw = 300
    private val ch = 400

    /**
     * 同一只人偶的两套动作：
     * · 站立：画布 200×400，本体占 `[0.25,0.05,0.75,1.0]`（宽 50% 高 95%）
     * · 挥手：画布 400×400（画布更宽），本体占 `[0.05,0.05,0.95,1.0]`（宽 90% 高 95%）
     * 改造前按宽度算：站立 scale=300/100=3，挥手 scale=300/360≈0.83 —— 挥手的人偶**小 3.6 倍**。
     */
    @Test
    fun `挥手臂展变宽时人偶不会变小`() {
        val stand = ActionScale.place(cw, ch, 200, 400,
            floatArrayOf(0.25f, 0.05f, 0.75f, 1.0f))
        val wave = ActionScale.place(cw, ch, 400, 400,
            floatArrayOf(0.05f, 0.05f, 0.95f, 1.0f))
        assertNotNull(stand)
        assertNotNull(wave)

        // 本体在屏幕上的**高度**必须一致（视觉大小由身高决定）。
        val standBodyH = (1.0f - 0.05f) * (stand!![3] - stand[1])
        val waveBodyH = (1.0f - 0.05f) * (wave!![3] - wave[1])
        assertEquals(standBodyH, waveBodyH, 1.5f)
    }

    /** 跳跃动作：画布更高（为腾空留白），本体的屏幕高度仍与站立一致。 */
    @Test
    fun `跳跃画布更高时人偶不会变大`() {
        val stand = ActionScale.place(cw, ch, 200, 400,
            floatArrayOf(0.25f, 0.0f, 0.75f, 1.0f))!!
        val jump = ActionScale.place(cw, ch, 200, 800,
            floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f))!!
        val standH = (1.0f - 0.0f) * (stand[3] - stand[1])
        val jumpH = (1.0f - 0.5f) * (jump[3] - jump[1])
        assertEquals(standH, jumpH, 1.5f)
    }

    /** 本体底边贴窗口底（脚踩窗口底），水平居中。 */
    @Test
    fun `本体底边贴窗口底且水平居中`() {
        val box = ActionScale.place(cw, ch, 200, 400,
            floatArrayOf(0.3f, 0.1f, 0.7f, 1.0f))!!
        val dw = box[2] - box[0]
        val dh = box[3] - box[1]
        val bodyCx = (0.3f + 0.7f) / 2f * dw + box[0]
        val bodyBottom = 1.0f * dh + box[1]
        assertEquals(cw / 2f, bodyCx, 1.5f)
        assertEquals(ch.toFloat(), bodyBottom, 1.5f)
    }

    /** 无基准 / 非法基准 → 回退成「整画布 contain」，不崩、不越界。 */
    @Test
    fun `无基准退回整画布 contain`() {
        assertNull(ActionScale.place(0, ch, 200, 400, null))
        val box = ActionScale.place(cw, ch, 200, 400, null)
        assertNotNull(box)
        // contain：宽 200 高 400 在 300×400 里 → scale = min(1.5, 1.0) = 1.0
        assertEquals(200, box!![2] - box[0])
        assertEquals(400, box[3] - box[1])
    }

    /** 本体高度占比过小（坏数据）→ 判为非法，走 contain，绝不放大到离谱。 */
    @Test
    fun `本体占比过小视为坏数据`() {
        val tiny = floatArrayOf(0.49f, 0.49f, 0.51f, 0.51f)
        assertTrue(!ActionScale.valid(tiny))
        val box = ActionScale.place(cw, ch, 200, 400, tiny)!!
        // contain 的结果 = 整张画布 200×400
        assertEquals(200, box[2] - box[0])
    }

    /**
     * 极端宽图（本体宽 100%、高 20%）仍受上界约束，不会被放大到离谱。
     *
     * 【为什么上界是 2 倍而不是 1 倍】见 `ActionScale.WIDTH_OVERFLOW`：
     *   收成 1 倍会让「挥手」整体缩小，违反需求 5.9。这里只挡极端素材。
     */
    @Test
    fun `极端宽图仍受上界约束`() {
        val box = ActionScale.place(cw, ch, 100, 1000,
            floatArrayOf(0.0f, 0.4f, 1.0f, 0.6f))!!
        val dw = box[2] - box[0]
        assertTrue("画面宽 $dw 不应超过窗口宽 2 倍 " + (cw * 2), dw <= cw * 2 + 1)
    }

    /**
     * 收手动作（本体明显更窄）不会把人偶放大 —— 窄姿态只允许「不变」，不允许「变大」。
     *
     * 【为什么也要钉】主尺是高度，宽度不参与放大，所以窄姿态天然不会变大；
     *   这条用例防的是将来有人把宽度重新拉回主尺。
     */
    @Test
    fun `窄姿态不会把人偶放大`() {
        val wide = ActionScale.place(cw, ch, 300, 400,
            floatArrayOf(0.0f, 0.05f, 1.0f, 1.0f))!!
        val narrow = ActionScale.place(cw, ch, 300, 400,
            floatArrayOf(0.4f, 0.05f, 0.6f, 1.0f))!!
        val wideH = (1.0f - 0.05f) * (wide[3] - wide[1])
        val narrowH = (1.0f - 0.05f) * (narrow[3] - narrow[1])
        assertEquals(wideH, narrowH, 1.5f)
    }

    /** 基准格式校验：非 4 元、NaN、越界一律拒绝。 */
    @Test
    fun `基准校验拒绝坏数据`() {
        assertTrue(!ActionScale.valid(null))
        assertTrue(!ActionScale.valid(floatArrayOf(0f, 0f, 1f)))
        assertTrue(!ActionScale.valid(floatArrayOf(Float.NaN, 0f, 1f, 1f)))
        assertTrue(!ActionScale.valid(floatArrayOf(0.5f, 0.5f, 0.4f, 0.9f)))
        assertTrue(!ActionScale.valid(floatArrayOf(-0.5f, 0f, 0.5f, 1f)))
        assertTrue(ActionScale.valid(floatArrayOf(0.25f, 0f, 0.75f, 1f)))
    }

    /** 同一输入必须得到同一结果（纯函数，无缓存副作用）。 */
    @Test
    fun `同输入结果稳定`() {
        val bl = floatArrayOf(0.25f, 0.05f, 0.75f, 1f)
        val a = ActionScale.place(cw, ch, 200, 400, bl)!!
        val b = ActionScale.place(cw, ch, 200, 400, bl)!!
        assertEquals(a.toList(), b.toList())
    }
}