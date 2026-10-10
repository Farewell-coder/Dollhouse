package com.dollhouse.app.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * 【职责】背景图模糊：把 [Bitmap] 按半径做一次降采样 box blur，返回新图。
 *
 * 【为什么不引 haze】本工程 Kotlin 1.9.24 + Compose 1.6.8，而 haze 1.7.3 要求
 *   Compose 1.12 / Kotlin 2.3.20，2.0.1 要求更高；强行引入要么编译不过，要么得把
 *   Kotlin 升到 2.x（Compose 编译器接法全变）。且 haze 只作用于 Compose 内容，
 *   本工程大量页面仍是 View（供应商详情 / 聊天抽屉 / 底栏），会出现「有的糊有的不糊」。
 *   故自研：无新依赖、View / Compose 通吃、可离线构建。
 *
 * 【为什么在后台线程一次算完再缓存】模糊是 O(像素) 的重活，放在 onDraw 里做会掉帧。
 *   背景图解码本来就跑在后台线程（见 GlobalBackground.startLoad），在那里顺带模糊一次，
 *   结果缓存在 GlobalBackground，后续绘制直接复用。
 *
 * 【为什么三次 box blur】三次盒式模糊的卷积结果已非常接近高斯模糊，
 *   而 box blur 可用「前缀和」做到每像素 O(1)，比直接算高斯核快一个量级。
 *
 * 【降采样】先按 1/[down] 缩小再模糊，像素量降到 1/down²，半径也等比缩小；
 *   模糊本身就是低通滤波，降采样带来的细节损失在模糊结果里看不出来。
 */
object BgBlur {

    /**
     * 按 [radiusDp] 模糊 [src]，返回新位图（[src] 不被修改、也不被回收）。
     *
     * @param radiusDp 模糊半径（dp）；<=0 时直接返回原图引用，调用方不必特判。
     * @param density  屏幕密度，用于把 dp 换成像素。
     * @return 模糊后的新位图；失败时回退返回 [src]。
     */
    @JvmStatic
    fun blur(src: Bitmap, radiusDp: Int, density: Float): Bitmap {
        if (radiusDp <= 0 || src.isRecycled) {
            return src
        }
        val w = src.width
        val h = src.height
        if (w < 2 || h < 2) {
            return src
        }
        // 半径换算成像素，再按降采样比例缩小；下限 1 保证「选了就有效果」。
        val rPx = Math.max(1, Math.round(radiusDp * density))
        val down = downSampleFor(rPx)
        val sw = Math.max(2, w / down)
        val sh = Math.max(2, h / down)
        val small = try {
            Bitmap.createScaledBitmap(src, sw, sh, true)
        } catch (ignored: Throwable) {
            return src
        }
        val r = Math.max(1, rPx / down)
        val px = IntArray(sw * sh)
        try {
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            boxBlur(px, sw, sh, r)
            boxBlur(px, sw, sh, r)
            boxBlur(px, sw, sh, r)
        } catch (ignored: Throwable) {
            if (small !== src) {
                small.recycle()
            }
            return src
        }
        if (small !== src) {
            small.recycle()
        }
        // 放大回原尺寸：用双线性过滤，避免模糊结果出现块状边界。
        val scaled = try {
            val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, sw, 0, 0, sw, sh)
            val up = Bitmap.createScaledBitmap(out, w, h, true)
            if (up !== out) {
                out.recycle()
            }
            up
        } catch (ignored: Throwable) {
            return src
        }
        return scaled
    }

    /**
     * 半径越大降采样越狠：大半径下细节本来就被抹平，多留像素纯属浪费。
     * 上限 4（即最多降到 1/16 像素量），下限 1（小半径时保持精度）。
     */
    private fun downSampleFor(radiusPx: Int): Int {
        return when {
            radiusPx >= 24 -> 4
            radiusPx >= 12 -> 3
            radiusPx >= 6 -> 2
            else -> 1
        }
    }

    /**
     * 原地三次盒式模糊（前缀和实现，每像素 O(1)）。
     *
     * 【为什么横竖分开做】二维卷积可分离，先横后竖的代价是 O(w·h)，直接做二维是 O(w·h·r²)。
     * 【边界处理】不做钳制，用「累加和 - 减去出窗」的滑动窗口自然外扩，
     *   边缘会向内侧取色，视觉上比硬钳制更平滑（模糊本来就该往外糊）。
     */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        // 横向
        blurLine(px, tmp, w, h, r, true)
        // 纵向
        blurLine(tmp, px, w, h, r, false)
    }

    /**
     * 沿一个方向做一次盒式模糊。
     *
     * @param horizontal true 时按行扫（横），false 时按列扫（竖）。
     */
    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        val win = 2 * r + 1
        val sumA = IntArray(4)
        val cur = IntArray(4)
        for (o in 0 until outer) {
            java.util.Arrays.fill(sumA, 0)
            // 预填充窗口：左右各外扩 r，越界时取边界像素。
            for (k in -r..r) {
                val idx = clamp(k, inner)
                unpack(src, at(o, idx, w, horizontal), cur)
                for (ch in 0 until 4) {
                    sumA[ch] += cur[ch]
                }
            }
            for (i in 0 until inner) {
                // 写当前窗口平均
                val o1 = at(o, i, w, horizontal)
                dst[o1] = pack(sumA, win)
                // 滑动：进右、出左
                val inIdx = clamp(i + r + 1, inner)
                val outIdx = clamp(i - r, inner)
                unpack(src, at(o, inIdx, w, horizontal), cur)
                for (ch in 0 until 4) {
                    sumA[ch] += cur[ch]
                }
                unpack(src, at(o, outIdx, w, horizontal), cur)
                for (ch in 0 until 4) {
                    sumA[ch] -= cur[ch]
                }
            }
        }
    }

    private fun at(o: Int, i: Int, w: Int, horizontal: Boolean): Int {
        return if (horizontal) o * w + i else i * w + o
    }

    private fun clamp(i: Int, n: Int): Int {
        return if (i < 0) 0 else if (i >= n) n - 1 else i
    }

    private fun unpack(px: IntArray, idx: Int, out: IntArray) {
        val c = px[idx]
        out[0] = (c ushr 24) and 0xFF
        out[1] = (c ushr 16) and 0xFF
        out[2] = (c ushr 8) and 0xFF
        out[3] = c and 0xFF
    }

    private fun pack(sum: IntArray, win: Int): Int {
        val a = sum[0] / win
        val r = sum[1] / win
        val g = sum[2] / win
        val b = sum[3] / win
        return ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    /**
     * 缩放一张已经画好的位图（给「平铺」模式做瓦片预处理，或给旧接口兜底）。
     * 【为什么在这里】与模糊同属「背景位图的一次性加工」，收在一处便于统一线程约束。
     */
    @JvmStatic
    fun scale(src: Bitmap, w: Int, h: Int): Bitmap? {
        if (src.isRecycled || w <= 0 || h <= 0) {
            return null
        }
        return try {
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 供调用方复用的空画布构造（避免各处在 onDraw 里 new）。 */
    @JvmStatic
    fun canvasOf(bmp: Bitmap): Canvas {
        return Canvas(bmp)
    }

    /** 供调用方复用的过滤画笔。 */
    @JvmStatic
    fun filterPaint(): Paint {
        return Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    }
}
