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
    fun missingBaselineUsesWholeFrameAsStableRuler() {
        val idle = ActionViewport.measure(spec, 500, 500, null)!!
        val narrow = ActionViewport.measure(spec, 250, 500, null)!!
        assertEquals(450, idle.bottom - idle.top)
        assertEquals(450, narrow.bottom - narrow.top)
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