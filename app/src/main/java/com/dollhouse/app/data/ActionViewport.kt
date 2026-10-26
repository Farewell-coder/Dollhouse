package com.dollhouse.app.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 动作画面的统一视口换算。
 *
 * 人物基础大小由宿主的 [Spec.bodyHeight] 决定，动作自己的画布、透明留白和帧数
 * 不再参与人物大小决策。动作只可以要求更宽的透明窗口来容纳伸出的手臂等像素。
 * 本类不依赖 Android，窗口位置与尺寸回归由 JVM 测试覆盖。
 */
object ActionViewport {
    /** 防止损坏/恶意素材制造超大绘制区域；正常动作不会接近此上限。 */
    private const val MAX_FRAME_SIDE = 8192f
    private const val MAX_WINDOW_FACTOR = 4

    data class Spec(
        /** 静态形象使用的窗口宽度，不含动作横向扩展。 */
        val baseWidth: Int,
        /** 静态形象使用的窗口高度。 */
        val baseHeight: Int,
        /** 人偶本体在屏幕上的统一高度；切换动作时保持不变。 */
        val bodyHeight: Int,
        /** 当前实际窗口宽度。 */
        val requiredWidth: Int = baseWidth
    )

    /** 一帧的目标矩形及其所需窗口宽度。矩形以未扩展窗口的坐标系表示。 */
    data class Placement(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val requiredWidth: Int
    )

    fun measure(spec: Spec, bitmapWidth: Int, bitmapHeight: Int, baseline: FloatArray?): Placement? {
        if (spec.baseWidth <= 0 || spec.baseHeight <= 0 || spec.bodyHeight <= 0 ||
            bitmapWidth <= 0 || bitmapHeight <= 0) return null
        // 显式提供但损坏的基准不能静默回退到整帧，否则同一动作会突然换尺子。
        if (baseline != null && !ActionScale.valid(baseline)) return null
        val bl = baseline
        val bodyH = if (bl == null) bitmapHeight.toFloat() else (bl[3] - bl[1]) * bitmapHeight
        if (bodyH <= 0f) return null

        // 唯一尺子：人物高度。宽度只决定视口，不再反向缩小人物。
        val safeScale = min(MAX_FRAME_SIDE / bitmapWidth, MAX_FRAME_SIDE / bitmapHeight)
        val scale = min(spec.bodyHeight / bodyH, safeScale)
        if (!scale.isFinite() || scale <= 0f) return null
        val dw = (bitmapWidth * scale).roundToInt().coerceAtLeast(1)
        val dh = (bitmapHeight * scale).roundToInt().coerceAtLeast(1)
        val l = bl?.get(0) ?: 0f
        val r = bl?.get(2) ?: 1f
        val b = bl?.get(3) ?: 1f
        val left = (spec.baseWidth * 0.5f - (l + r) * 0.5f * dw).roundToInt()
        val top = (spec.baseHeight - b * dh).roundToInt()
        return Placement(left, top, left + dw, top + dh,
            requiredWidth(spec, left, left + dw))
    }

    /** 以人物中心为轴对称扩展，避免扩窗本身造成视觉位置漂移。 */
    fun requiredWidth(spec: Spec, left: Int, right: Int): Int {
        if (spec.baseWidth <= 0) return 0
        val center = spec.baseWidth * 0.5f
        val half = ceil(max(center - left, right - center).toDouble()).toInt()
        return (half * 2).coerceIn(spec.baseWidth, spec.baseWidth * MAX_WINDOW_FACTOR)
    }

    /** 扩展窗口后，旧坐标只需整体平移这个差值。 */
    fun contentOffsetX(spec: Spec): Int = (spec.requiredWidth - spec.baseWidth) / 2
}
