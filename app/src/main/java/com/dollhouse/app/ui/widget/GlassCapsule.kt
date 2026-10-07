package com.dollhouse.app.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.widget.FrameLayout
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】底栏胶囊的「毛玻璃」底：把身后内容模糊后当自己的背景，再叠半透明染色 + 顶高光 + 描边。
 *
 * 【为什么要自己画】已用 javap 实测 android.jar：
 *   View 层只有 setRenderEffect —— 它模糊的是「自己画出来的内容」，不是身后；
 *   能模糊「窗口背后」的只有 Window.setBackgroundBlurRadius，而本页是叠在设置页之上的
 *   内嵌整屏页，同一窗口内没有任何系统 API 能模糊兄弟 View。真毛玻璃只能自己采样 + 自己模糊。
 *
 * 【性能】模糊不需要高分辨率：先按 1/SCALE 降采样（像素量降到 1/16），再跑三次 box blur
 *   （三次 box ≈ 一次高斯）。bitmap / Canvas / 像素缓冲全部按尺寸缓存复用；底栏滚动淡出
 *   期间直接关掉模糊（见 setBlurOn），既省算力又不会采到过期内容。
 *
 * 【坑】
 *   ① 偏移必须走「共同祖先」两段换算，直接拿 getLeft() 只对同父兄弟成立；
 *   ② 画布变换顺序必须是先 scale 再 translate，反了采样原点会整体错位；
 *   ③ 采样源绝不能包含本 View 自己（否则无限递归）—— 这里采的是内容层，它是本 View 的
 *      兄弟而非祖先，天然安全；
 *   ④ 采样前必须先填一层不透明底色，否则内容层的透明区域在模糊后会把 alpha 拉平均，
 *      叠到画布上会出现「越靠边越透」的脏边。
 */
internal class GlassCapsule(
    ctx: Context,
    private val fillColor: Int,
    private val hairlineColor: Int,
    private val baseColor: Int
) : FrameLayout(ctx) {

    private var source: View? = null
    private var blurOn = true

    private var bmp: Bitmap? = null
    private var bmpCanvas: Canvas? = null
    private var bufA: IntArray? = null
    private var bufB: IntArray? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private val inner = RectF()
    private val dstRect = Rect()
    private val clip = Path()

    init {
        // 【必须显式关掉 willNotDraw】ViewGroup 默认不调用 onDraw（WILL_NOT_DRAW 标志），
        //   而本类的全部视觉效果都在 onDraw 里 —— 不关掉就是一颗完全透明的胶囊。
        setWillNotDraw(false)
        hairPaint.style = Paint.Style.STROKE
        hairPaint.strokeWidth = Math.max(1f, UiKit.dpf(ctx, 1f))
        // 胶囊外轮廓交给系统做投影：本 View 是全底栏唯一挂 elevation 的一层，形状即胶囊。
        setOutlineProvider(object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                val w = v.width
                val h = v.height
                if (w > 0 && h > 0) {
                    o.setRoundRect(0, 0, w, h, h / 2f)
                }
            }
        })
    }

    /** 采样源：需要被「透过来」的内容层。 */
    fun setSource(v: View) {
        source = v
    }

    /** 模糊开关。滚动淡出期间关掉：既省算力，也避免拿一帧过期内容去模糊。 */
    fun setBlurOn(on: Boolean) {
        if (blurOn != on) {
            blurOn = on
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            glossPaint.shader = null
            return
        }
        // 顶部高光：上亮下透。浅色主题下几乎看不出，深色主题下自然成为玻璃的高光边。
        glossPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.55f,
            0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            return
        }
        val r = h / 2f

        box.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(box, r, r, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clip)
        drawBlur(canvas, w, h)
        fillPaint.color = fillColor
        canvas.drawRect(box, fillPaint)
        canvas.drawRect(box, glossPaint)
        canvas.restoreToCount(save)

        // 描边画在裁剪之外，否则会被自己切掉一半、只剩半透明的一条浅痕。
        if (hairlineColor != 0) {
            val sw = hairPaint.strokeWidth
            val ins = sw / 2f
            inner.set(ins, ins, w - ins, h - ins)
            hairPaint.color = hairlineColor
            canvas.drawRoundRect(inner, Math.max(0f, r - ins), Math.max(0f, r - ins), hairPaint)
        }
    }

    private fun drawBlur(canvas: Canvas, w: Int, h: Int) {
        val src = source
        if (src == null || !blurOn || src.width <= 0 || src.height <= 0) {
            return
        }
        val bw = Math.max(1, w / SCALE)
        val bh = Math.max(1, h / SCALE)
        var bmp = this.bmp
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            this.bmp = bmp
            this.bmpCanvas = Canvas(bmp)
            this.bufA = IntArray(bw * bh)
            this.bufB = IntArray(bw * bh)
        }
        val bmpCanvas = this.bmpCanvas!!
        val bufA = this.bufA!!
        val bufB = this.bufB!!
        val off = sampleOrigin(src) ?: return
        bmpCanvas.drawColor(baseColor)
        val save = bmpCanvas.save()
        // 【变换顺序 + 符号，两个都不能错】Canvas 的 scale/translate 都是 pre-concat
        //   （M' = M × T），点 p 的最终坐标是 (p + t) / SCALE。
        //   本 View 的原点在 src 坐标系里位于 off，要让 src 的 off 点正好落到降采样画布的
        //   原点，就得解 (off + t) / SCALE == 0，即 t = -off。
        //   （若写成 +off，采样区会整体平移 2×off，采到离胶囊很远的另一块内容。）
        bmpCanvas.scale(1f / SCALE, 1f / SCALE)
        bmpCanvas.translate(-off[0].toFloat(), -off[1].toFloat())
        try {
            src.draw(bmpCanvas)
        } catch (ignored: Throwable) {
            // 采样失败就退化成纯半透明玻璃，功能与观感不崩。
            bmpCanvas.restoreToCount(save)
            return
        }
        bmpCanvas.restoreToCount(save)
        blur(bmp, bufA, bufB)
        dstRect.set(0, 0, w, h)
        canvas.drawBitmap(bmp, null, dstRect, bmpPaint)
    }

    /** 本 View 的原点在 src 坐标系里的偏移；两者无共同祖先时返回 null。 */
    private fun sampleOrigin(src: View): IntArray? {
        var root: View? = null
        var v: View? = this
        while (v != null) {
            if (isAncestor(v, src)) {
                root = v
                break
            }
            val p = v.parent
            v = if (p is View) p else null
        }
        if (root == null) {
            return null
        }
        val g = offsetIn(root, this) ?: return null
        val s = offsetIn(root, src) ?: return null
        return intArrayOf(g[0] - s[0], g[1] - s[1])
    }

    companion object {
        /** 降采样倍率：最好取 2 的幂，像素量按平方降。 */
        private const val SCALE = 4
        /** box blur 半径（以降采样后的像素计）。 */
        private const val BLUR_RADIUS = 5
        /** 三次 box ≈ 一次高斯。 */
        private const val BLUR_PASSES = 3

        /** 后代原点相对祖先的偏移；ancestor 不是 descendant 的祖先时返回 null。 */
        private fun offsetIn(ancestor: View, descendant: View): IntArray? {
            var x = 0
            var y = 0
            var v: View? = descendant
            while (v != null && v != ancestor) {
                x += v.left
                y += v.top
                val p: ViewParent? = v.parent
                v = if (p is View) p else null
            }
            return if (v == ancestor) intArrayOf(x, y) else null
        }

        private fun isAncestor(a: View, b: View): Boolean {
            var v: View? = b
            while (v != null) {
                if (v == a) {
                    return true
                }
                val p: ViewParent? = v.parent
                v = if (p is View) p else null
            }
            return false
        }

        // ---------- 三次 box blur（滑动窗口累加，复杂度与半径无关） ----------

        private fun blur(b: Bitmap, a: IntArray, c: IntArray) {
            val w = b.width
            val h = b.height
            if (a.size < w * h || c.size < w * h || w < 3 || h < 3) {
                return
            }
            b.getPixels(a, 0, w, 0, 0, w, h)
            for (i in 0 until BLUR_PASSES) {
                blurH(a, c, w, h, BLUR_RADIUS)
                blurV(c, a, w, h, BLUR_RADIUS)
            }
            b.setPixels(a, 0, w, 0, 0, w, h)
        }

        private fun blurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val div = r * 2 + 1
            for (y in 0 until h) {
                val row = y * w
                var a = 0
                var rr = 0
                var gg = 0
                var bb = 0
                for (i in -r..r) {
                    val c = src[row + clamp(i, 0, w - 1)]
                    a += c ushr 24
                    rr += (c shr 16) and 0xFF
                    gg += (c shr 8) and 0xFF
                    bb += c and 0xFF
                }
                for (x in 0 until w) {
                    dst[row + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)
                    val out = src[row + clamp(x - r, 0, w - 1)]
                    val ins = src[row + clamp(x + r + 1, 0, w - 1)]
                    a += (ins ushr 24) - (out ushr 24)
                    rr += ((ins shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    gg += ((ins shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    bb += (ins and 0xFF) - (out and 0xFF)
                }
            }
        }

        private fun blurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val div = r * 2 + 1
            for (x in 0 until w) {
                var a = 0
                var rr = 0
                var gg = 0
                var bb = 0
                for (i in -r..r) {
                    val c = src[clamp(i, 0, h - 1) * w + x]
                    a += c ushr 24
                    rr += (c shr 16) and 0xFF
                    gg += (c shr 8) and 0xFF
                    bb += c and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)
                    val out = src[clamp(y - r, 0, h - 1) * w + x]
                    val ins = src[clamp(y + r + 1, 0, h - 1) * w + x]
                    a += (ins ushr 24) - (out ushr 24)
                    rr += ((ins shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    gg += ((ins shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    bb += (ins and 0xFF) - (out and 0xFF)
                }
            }
        }

        private fun clamp(v: Int, lo: Int, hi: Int): Int {
            return if (v < lo) lo else if (v > hi) hi else v
        }
    }
}
