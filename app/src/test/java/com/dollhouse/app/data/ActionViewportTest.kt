package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionViewportTest {
    private val spec = ActionViewport.Spec(300, 450, 450)
    private val baseline = floatArrayOf(.2f, 0f, .8f, 1f)

    @Test
    fun bodyHeightIsStableAcrossActions() {
        val idle = ActionViewport.measure(spec, 500, 500, baseline)!!
        val wave = ActionViewport.measure(spec, 900, 500, baseline)!!
        assertEquals(spec.bodyHeight, idle.bottom - idle.top)
        assertEquals(idle.bottom - idle.top, wave.bottom - wave.top)
        assertTrue(wave.requiredWidth > spec.baseWidth)
    }

    @Test
    fun bodyHeightNotFrameHeightWithBaseline() {
        val p = ActionViewport.measure(spec, 500, 500, baseline)!!
        // 基准本体占图高 100%，实际显示高度就是统一人物高度。
        assertEquals(450, p.bottom - p.top)
    }

    @Test
    fun wideActionDoesNotShrinkBody() {
        val p = ActionViewport.measure(spec, 1600, 500, baseline)!!
        assertEquals(450, p.bottom - p.top)
        assertTrue(p.requiredWidth > spec.baseWidth * 2)
    }

    @Test
    fun canvasIsExpandedSymmetrically() {
        val p = ActionViewport.measure(spec, 900, 500, baseline)!!
        val offset = ActionViewport.contentOffsetX(spec.copy(requiredWidth = p.requiredWidth))
        val bodyCenter = p.left + offset + (p.right - p.left) * (baseline[0] + baseline[2]) / 2f
        assertEquals(p.requiredWidth / 2f, bodyCenter, 0.01f)
        assertTrue(p.left + offset >= 0)
        assertTrue(p.right + offset <= p.requiredWidth)
    }

    @Test
    fun missingBaselineAlignsByWidthToMatchStaticImage() {
        // 【为什么改成断言宽度】缺 baseline 时的基准规则已在 v0.1.4 定案为「按宽度对齐」：
        //   静态形象是 `petWidth × 高宽比`，所以动作画布也必须让**人物宽度**落在 petWidth 上，
        //   否则同一只人偶换到一个画布更宽 / 更窄的动作时会整体放大缩小（真机表现为「一大一小」）。
        //
        //   旧断言（两者显示高都等于 450）对应的是「按高度对齐」：
        //   那样 500×500 显示宽 450、250×500 显示宽 225 —— 正是要被修掉的症状本身。
        val idle = ActionViewport.measure(spec, 500, 500, null)!!
        val narrow = ActionViewport.measure(spec, 250, 500, null)!!
        // 宽度恒定：这是「同一把尺子」的直接体现。
        assertEquals(spec.baseWidth, idle.right - idle.left)
        assertEquals(spec.baseWidth, narrow.right - narrow.left)
        // 高度随画布宽高比走：500×500 → 1:1 → 300；250×500 → 1:2 → 600。
        assertEquals(300, idle.bottom - idle.top)
        assertEquals(600, narrow.bottom - narrow.top)
        assertEquals(spec.baseWidth, narrow.requiredWidth)
    }

    @Test
    fun highResolutionScaleKeepsBodyProportional() {
        val large = ActionViewport.Spec(600, 900, 900)
        val p = ActionViewport.measure(large, 1000, 1000, baseline)!!
        assertEquals(900, p.bottom - p.top)
        assertEquals(2 * spec.bodyHeight, large.bodyHeight)
    }

    @Test
    fun expandedTransparentAreaDoesNotInterceptTouch() {
        assertTrue(ActionViewport.isInBaseWindow(150f, 300, 300))
        assertFalse(ActionViewport.isInBaseWindow(50f, 600, 300))
        assertTrue(ActionViewport.isInBaseWindow(150f, 600, 300))
        assertTrue(ActionViewport.isInBaseWindow(449f, 600, 300))
        assertFalse(ActionViewport.isInBaseWindow(450f, 600, 300))
        assertFalse(ActionViewport.isInBaseWindow(Float.NaN, 600, 300))
    }

    @Test
    fun corruptInputIsRejected() {
        assertEquals(null, ActionViewport.measure(spec, 0, 100, baseline))
        assertEquals(null, ActionViewport.measure(spec, 100, -1, null))
        assertEquals(null, ActionViewport.measure(spec, 100, 100, floatArrayOf(0f, 0f, 0f, 0f)))
    }
}