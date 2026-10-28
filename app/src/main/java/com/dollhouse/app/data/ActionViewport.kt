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
        // 【v0.1.4·修「比例一大一小」】身体高度的取法必须与静态形象同源。
        //
        // 【改造前】baseline 缺失时 `bodyH = bitmapHeight`，即把**整张画布高度**当成身体高度，
        //   于是缩放比 = bodyHeight / 画布高 —— 这是「按高度对齐」。
        //   而静态形象是 `fullHeight = petWidth × 高宽比`（按宽度对齐，见 PetView.fullHeight）。
        //   两把尺子不一致，结果就是：换到一个画布更扁（或更瘦）的动作时，
        //   人物被整体放大 / 缩小，肉眼看到「一会儿大一会儿小」。
        //
        // 【改造后】缺基准时假定「身体高度与画布宽度的比例 = 静态形象的高宽比」，
        //   也就是退回**按宽度对齐** —— 与静态形象严格同一把尺子。
        //   有有效基准时行为完全不变（baseline 提供的才是真身体框，比任何推测都准）。
        val bodyH = if (bl == null) {
            bitmapWidth * (spec.bodyHeight.toFloat() / spec.baseWidth)
        } else {
            (bl[3] - bl[1]) * bitmapHeight
        }
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

    /**
     * 透明扩展区只用于显示，不扩大触摸拦截面。
     * 只在新一次按下时判断；拖动中扩窗不会中断当前手势。
     */
    fun isInBaseWindow(x: Float, actualWidth: Int, baseWidth: Int): Boolean {
        if (actualWidth <= 0 || baseWidth <= 0 || actualWidth <= baseWidth) return true
        if (!x.isFinite()) return false
        val left = (actualWidth - baseWidth) / 2f
        return x >= left && x < left + baseWidth
    }
}
