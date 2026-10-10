package com.dollhouse.app.ui.theme

/**
 * 【职责】WCAG 可读性原语：相对亮度、对比度、前景自动夹紧、遮罩透明度推导。
 *
 * 【为何是纯 Kotlin】本类刻意不依赖任何 Android 类型（连 android.graphics.Color 都不用），
 *   于是可以被普通 JVM 单元测试直接覆盖；颜色一律按 0xAARRGGBB 的 Int 处理。
 *
 * 【为什么收口】全 App 的正文 / 副标题 / 提示 / 药丸文字都要满足 WCAG：
 *   普通文字 ≥ 4.5:1，大号文字 ≥ 3:1。与其在各页面散写“压暗一点 / 提亮一点”的魔法色，
 *   不如把“给定底色，把前景调到达标”收成一个可复用的纯函数。
 *
 * 【背景图上的文字】背景图 + 可读性遮罩这条链路也走本类：见 [minOverlayAlpha]，
 *   对图片平均色求「最少的遮罩不透明度，使文字达标」，避免用死值 0x80000000 这种魔法数。
 */
object ContrastCore {

    /** WCAG AA：普通文字最小对比度。 */
    const val AA_NORMAL = 4.5

    /** WCAG AA：大号文字（≥18.66sp 粗体或 ≥24sp）最小对比度。 */
    const val AA_LARGE = 3.0

    /** WCAG AAA：普通文字增强对比度。 */
    const val AAA_NORMAL = 7.0

    /** 不透明黑 / 白，夹紧时的方向端点。 */
    private const val OPAQUE_BLACK = 0xFF000000.toInt()
    private const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()

    /**
     * 黑白两个极点对比度相等的亮度临界点：解 (L+0.05)² = 0.05·1.05 得 L ≈ 0.179。
     * 底色亮度低于它时白色对比更高，高于它时黑色对比更高。
     */
    private const val POLE_SPLIT = 0.179

    /** ARGB 的 alpha 分量（0..255）。 */
    fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF

    /** ARGB 的红色分量（0..255）。 */
    fun redOf(argb: Int): Int = (argb ushr 16) and 0xFF

    /** ARGB 的绿色分量（0..255）。 */
    fun greenOf(argb: Int): Int = (argb ushr 8) and 0xFF

    /** ARGB 的蓝色分量（0..255）。 */
    fun blueOf(argb: Int): Int = argb and 0xFF

    /** 给 RGB 换上一个新的 alpha（0..255）。 */
    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** 只保留 RGB，忽略 alpha。 */
    fun withoutAlpha(argb: Int): Int = argb and 0x00FFFFFF

    /**
     * 把 [fg] 以自身 alpha 叠到不透明的 [bg] 上，返回不透明结果。
     * fg 完全不透明时直接返回 fg（避免无谓的浮点运算）。
     */
    fun composite(fg: Int, bg: Int): Int {
        val a = alphaOf(fg)
        if (a == 255) {
            return fg
        }
        if (a == 0) {
            return bg or 0xFF000000.toInt()
        }
        val f = a / 255.0
        val inv = 1.0 - f
        val r = Math.round(redOf(fg) * f + redOf(bg) * inv).toInt()
        val g = Math.round(greenOf(fg) * f + greenOf(bg) * inv).toInt()
        val b = Math.round(blueOf(fg) * f + blueOf(bg) * inv).toInt()
        return OPAQUE_BLACK or (r shl 16) or (g shl 8) or b
    }

    /** sRGB 单通道线性化。 */
    private fun linearize(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /** WCAG 相对亮度（0..1）；按不透明处理。 */
    fun relativeLuminance(argb: Int): Double {
        return (0.2126 * linearize(redOf(argb)) +
                0.7152 * linearize(greenOf(argb)) +
                0.0722 * linearize(blueOf(argb)))
    }

    /**
     * WCAG 对比度（1.0 ~ 21.0）。
     * 前景带 alpha 时先叠到背景上再算，保证“半透明文字压在底上”的口径正确。
     */
    fun contrastRatio(fg: Int, bg: Int): Double {
        val opaqueBg = bg or 0xFF000000.toInt()
        val solidFg = composite(fg, opaqueBg)
        val lf = relativeLuminance(solidFg)
        val lb = relativeLuminance(opaqueBg)
        val hi = Math.max(lf, lb)
        val lo = Math.min(lf, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** 是否满足给定目标对比度。 */
    fun meets(fg: Int, bg: Int, target: Double): Boolean = contrastRatio(fg, bg) >= target

    /** 在 RGB 空间按比例 [t]（0..1）从 [from] 混向 [to]，保留 from 的 alpha。 */
    fun blend(from: Int, to: Int, t: Double): Int {
        val f = Math.max(0.0, Math.min(1.0, t))
        val r = Math.round(redOf(from) + (redOf(to) - redOf(from)) * f).toInt()
        val g = Math.round(greenOf(from) + (greenOf(to) - greenOf(from)) * f).toInt()
        val b = Math.round(blueOf(from) + (blueOf(to) - blueOf(from)) * f).toInt()
        return ((alphaOf(from) and 0xFF) shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * 给定底色，选「对比度更高」的黑或白极点。
     *
     * 【为什么不是 0.5】黑白与同一底色的对比度曲线在亮度 0.179 处相交：
     *   低于该点白色更亮、对比更高；高于该点黑色更暗、对比更高。
     *   旧实现以 0.5 为界，遇到中间灰（亮度 0.2~0.4）会挑错方向，越夹越糊。
     */
    private fun higherContrastPole(bg: Int): Int {
        val lb = relativeLuminance(bg or OPAQUE_BLACK)
        return if (lb >= POLE_SPLIT) OPAQUE_BLACK else OPAQUE_WHITE
    }

    /**
     * 把前景 [fg] 沿“远离背景亮度”的方向逐步推向黑或白，直到对 [bg] 的对比度 ≥ [target]。
     *
     * 【为什么不是直接换黑白】直接换会丢掉品牌的紫 / 蓝等语义色，观感突兀。
     *   这里只做“刚好达标”的最小位移：达标即返回，颜色变化尽量小。
     * 【方向判定】见 [higherContrastPole]：按黑白对比度曲线的交点 0.179 选方向，
     *   而不是 0.5 —— 中灰底选错方向会越夹越糊。
     * 【alpha】优先保留入参前景的 alpha；若前景半透明、推到极点仍不达标，
     *   则合理提高不透明度直到达标（半透明文字压底永远到不了满对比度）。
     */
    fun ensureContrast(fg: Int, bg: Int, target: Double): Int {
        if (contrastRatio(fg, bg) >= target) {
            return fg
        }
        val pole = higherContrastPole(bg)
        val a = alphaOf(fg)
        // 步长 1/48：兼顾“够细”与“最多 48 次”的开销上限。
        for (i in 1..48) {
            val cand = blend(fg, pole, i / 48.0)
            if (contrastRatio(cand, bg) >= target) {
                return withAlpha(cand, a)
            }
        }
        // 保留原 alpha 推到极点仍不达标：多半是前景半透明。此时提高不透明度直到达标。
        if (contrastRatio(withAlpha(pole, a), bg) >= target) {
            return withAlpha(pole, a)
        }
        var oa = a + 1
        while (oa <= 255) {
            val cand = withAlpha(pole, oa)
            if (contrastRatio(cand, bg) >= target) {
                return cand
            }
            oa++
        }
        return withAlpha(pole, 255)
    }

    /**
     * 从候选前景里挑一个达标的：优先返回用户偏好的首选色 [preferred]，
     * 不达标则依次尝试 [fallbacks]，仍不行就把首选色夹紧到 [target]。
     */
    fun pickReadable(preferred: Int, bg: Int, target: Double, vararg fallbacks: Int): Int {
        if (contrastRatio(preferred, bg) >= target) {
            return preferred
        }
        for (f in fallbacks) {
            if (contrastRatio(f, bg) >= target) {
                return f
            }
        }
        return ensureContrast(preferred, bg, target)
    }

    /**
     * 求「在图片区域色 [imageColor] 上盖一层 [overlayColor]，最少需要多少不透明度（0..255），
     * 才能让文字 [text] 达到 [target] 对比度」。
     * 【用途】全局背景图的可读性遮罩：不给 0x80000000 这类魔法值，
     *   而是按图片实际明暗推导；浅色图遮罩浅、深色图遮罩深。
     * 【步长 5】遮罩差 5/255 肉眼几乎不可辨，却把搜索压到 ≤52 次。
     */
    fun minOverlayAlpha(imageColor: Int, overlayColor: Int, text: Int, target: Double): Int {
        val img = imageColor or 0xFF000000.toInt()
        for (a in 0..255 step 5) {
            val bg = composite(withAlpha(overlayColor, a), img)
            if (contrastRatio(text, bg) >= target) {
                return a
            }
        }
        return 255
    }

    /**
     * 【消除双重压暗】图片在 UI 里是按 [imageAlphaPercent] 半透明叠在底色 [base] 上的，
     *   遮罩若仍按「原图色」推导就会把已衰减的图再压一遍（双重压暗、图发灰发闷）。
     *   这里先把「原图色 × 自身透明度」叠到底色上得到有效色，再据此推遮罩，
     *   让遮罩只补「有效色到对比度达标」的差额，图就能按滑条的实/虚如实显出来。
     */
    fun minOverlayAlphaEffective(
        imageColor: Int, base: Int, imageAlphaPercent: Int,
        overlayColor: Int, text: Int, target: Double
    ): Int {
        val pct = Math.max(0, Math.min(100, imageAlphaPercent))
        val effective = composite(withAlpha(imageColor, pct * 255 / 100), base)
        return minOverlayAlpha(effective, overlayColor, text, target)
    }

    /**
     * 求「前景 [fg] 叠在 [bg] 上时，需要多少不透明度（0..255）才能达标」。
     * 用于把某块半透明内容垫到能读清。
     */
    fun minForegroundAlpha(fg: Int, bg: Int, target: Double): Int {
        val opaqueBg = bg or 0xFF000000.toInt()
        for (a in 0..255 step 5) {
            if (contrastRatio(withAlpha(fg, a), opaqueBg) >= target) {
                return a
            }
        }
        return 255
    }

    /** 快速估算一张位图的代表色（按透明过滤的 RGB 平均），供遮罩推导使用。 */
    fun averageColor(pixels: IntArray, count: Int): Int {
        if (count <= 0) {
            return 0xFF808080.toInt()
        }
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (i in 0 until count) {
            val p = pixels[i]
            if (alphaOf(p) < 16) {
                continue
            }
            r += redOf(p)
            g += greenOf(p)
            b += blueOf(p)
            n++
        }
        if (n == 0) {
            return 0xFF808080.toInt()
        }
        return OPAQUE_BLACK or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }
}
