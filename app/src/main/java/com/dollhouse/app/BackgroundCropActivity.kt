package com.dollhouse.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ImageStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】聊天背景的「选图 → 裁剪 → 预览 → 保存」一站式页面。
 *
 * 【为什么单独做一页】原先选完图直接按原图比例铺满聊天页，宽高比一不对就被拉伸/压扁；
 *   这里让用户先把想要的那一块框出来，落盘时就按框把它裁好，聊天页拿到的一定是不变形的图。
 *
 * 【交互】深色底 + 左上「取消」；中间是裁剪框（框外压暗），单指拖动、双指缩放，图片永远按比例
 *   铺满框、不会有空白边；底部「复位 / − / + / 保存背景」。
 *
 * 【坑】选区坐标全部由「图片矩阵 + 框位置」现算，不要缓存下来 —— 框会随屏幕尺寸变化，
 *   缓存的像素坐标一旦对不上就会裁歪。
 *
 * 【边界】本页不做旋转：聊天背景是静态壁纸，旋转语义弱且会多出一层坐标变换的出错面。
 *   若以后要加，只需在 save 前对 full 先旋转再把矩阵重算一遍。
 */
class BackgroundCropActivity : Activity() {
    private var crop: CropView? = null

    /** 顶栏 / 底栏实测占用高度（像素），由页面量出来后回填给 CropView。 */
    private var insetTop = 0
    private var insetBottom = 0

    /** 把两侧实测留白同步给裁剪视图：裁剪框只允许落在两者之间。 */
    private fun applyInsets() {
        if (crop != null) {
            crop!!.setInsets(insetTop, insetBottom)
        }
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        // 【修·选图回调丢失】与 PickFileActivity 同一个坑：重建时不能再弹一次相册，
        //   Manifest 也没挂 noHistory，本页要活到系统把结果投递回来。
        if (bundle != null) {
            Logs.i(TAG, "[重建] 不再重弹相册，等待结果")
            // 【坑】重建路径下 saveCrop() 照样可能被点到，crop 不能是空引用；
            //   照常搭出同一套界面（只是不重新拉相册），等系统把选图结果投回 onActivityResult。
            window.statusBarColor = Color.BLACK
            setContentView(buildUi())
            return
        }
        window.statusBarColor = Color.BLACK
        setContentView(buildUi())
        pick()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val c = CropView(this)
        crop = c
        root.addView(c, FrameLayout.LayoutParams(-1, -1))

        buildTopBar(root)
        buildBottomBar(root)
        return root
    }

    /** 顶栏：左上「取消」胶囊，量出实际占高回填 insetTop 给裁剪框让位。 */
    private fun buildTopBar(root: FrameLayout) {
        // 顶部：左上「取消」描边小胶囊。
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        val cancel = pill("取消")
        cancel.setOnClickListener { finish() }
        topBar.addView(cancel)
        val topLp = FrameLayout.LayoutParams(-1, -2)
        topLp.topMargin = UiKit.dp(this, 18.0f)
        topLp.leftMargin = UiKit.dp(this, PAD_DP)
        topLp.rightMargin = UiKit.dp(this, PAD_DP)
        root.addView(topBar, topLp)
        // 【坑】顶栏高度随字号变化，写死会把裁剪框压到「取消」下面；实测后回填。
        topBar.addOnLayoutChangeListener { _, _, _, _, b, _, _, _, _ ->
            insetTop = b + UiKit.dp(this, 10.0f)
            applyInsets()
        }
    }

    /** 底栏：复位 / 缩小 / 放大 + 主操作「保存背景」，量出实际占高回填 insetBottom。 */
    private fun buildBottomBar(root: FrameLayout) {
        // 底部：复位 / 缩小 / 放大 + 主操作「保存背景」。
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.gravity = Gravity.CENTER_HORIZONTAL

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        tools.gravity = Gravity.CENTER
        tools.addView(tool("复位", View.OnClickListener { crop!!.reset() }))
        tools.addView(tool("−", View.OnClickListener { crop!!.zoomBy(1 / 1.25f) }))
        tools.addView(tool("+", View.OnClickListener { crop!!.zoomBy(1.25f) }))
        bottom.addView(tools)

        val save = TextView(this)
        save.text = "保存背景"
        save.setTextSize(UiKit.FS_BTN)
        save.setTextColor(Color.WHITE)
        save.gravity = Gravity.CENTER
        save.background = UiKit.round(UiKit.ACC, this, 12f)
        UiKit.press(save)
        save.setOnClickListener { saveCrop() }
        val saveLp = LinearLayout.LayoutParams(
                UiKit.dp(this, 200.0f), UiKit.dp(this, 44.0f))
        saveLp.topMargin = UiKit.dp(this, 14.0f)
        bottom.addView(save, saveLp)

        val botLp = FrameLayout.LayoutParams(-1, -2)
        botLp.gravity = Gravity.BOTTOM
        botLp.bottomMargin = UiKit.dp(this, 26.0f)
        root.addView(bottom, botLp)

        // 【坑】底栏高度随字号 / 边距变化，不能写死，否则裁剪框会被「保存背景」压在下面。
        //   这里量出底栏实际占用的高度回填给 CropView，让它把可用高度避开这一段。
        bottom.addOnLayoutChangeListener { v, _, t, _, _, _, _, _, _ ->
            // 【为什么不用写死的高度】从父容器（满屏 FrameLayout）总高减去底栏顶边，
            // 得到的就是「底栏 + 底边距」实际吃掉的高度，不依赖任何常数。
            val parent = v.parent as View
            insetBottom = Math.max(0, parent.height - t) + UiKit.dp(this, 10.0f)
            applyInsets()
        }
    }

    /** 深色底上的描边小胶囊（与截图里的「取消」同款观感）。 */
    private fun pill(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(UiKit.FS_TINY + 2.0f)
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.background = UiKit.roundStroke(0x1AFFFFFF, 0x66FFFFFF, this, 999f)
        t.setPadding(UiKit.dp(this, 16.0f), UiKit.dp(this, 7.0f),
                UiKit.dp(this, 16.0f), UiKit.dp(this, 7.0f))
        UiKit.press(t)
        return t
    }

    /** 底部工具按钮：透明底 + 白描边 + 白字。 */
    private fun tool(text: String, click: View.OnClickListener): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.background = UiKit.roundStroke(0x14FFFFFF, 0x59FFFFFF, this, 10f)
        t.setOnClickListener(click)
        UiKit.press(t)
        val lp = LinearLayout.LayoutParams(
                UiKit.dp(this, 84.0f), UiKit.dp(this, 40.0f))
        lp.leftMargin = UiKit.dp(this, 5.0f)
        lp.rightMargin = UiKit.dp(this, 5.0f)
        t.layoutParams = lp
        return t
    }

    /* ------------------------------- 选图与落盘 ------------------------------- */

    private fun pick() {
        try {
            startActivityForResult(pickImageIntent(), REQ_PICK)
        } catch (anf: ActivityNotFoundException) {
            Logs.w(TAG, "照片选择器不可用，降级", anf)
            try {
                startActivityForResult(compatImageIntent(), REQ_PICK)
            } catch (th: Throwable) {
                fail("打不开相册：" + th.message)
            }
        } catch (th: Throwable) {
            fail("打不开相册：" + th.message)
        }
    }

    private fun pickImageIntent(): Intent {
        val intent = Intent("android.provider.action.PICK_IMAGES")
        intent.type = "image/*"
        return intent
    }

    private fun compatImageIntent(): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        return intent
    }

    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req != REQ_PICK) {
            return
        }
        if (result != RESULT_OK || data == null || data.data == null) {
            Logs.i(TAG, "[取消] 没选图，直接收尾")
            finish()
            return
        }
        val uri: Uri = data.data!!
        Logs.i(TAG, "[回传] 进入裁剪 " + uri)
        // 解码这张图可能要几秒（大图），放后台线程，避免卡住首帧。
        Thread({
            val bmp = decode(uri)
            runOnUiThread {
                if (bmp == null) {
                    fail("这张图解不开")
                    return@runOnUiThread
                }
                // 【坑】重建路径下界面已搭好、crop 不为空；这里再兜一道，兼容极端时序。
                val c = crop
                if (c == null) {
                    return@runOnUiThread
                }
                c.setImage(bmp)
            }
        }, "dollhouse-crop-decode").start()
    }

    /** 解码成位图：先读尺寸再按需降采样，原始尺寸供裁剪切图用。 */
    private fun decode(uri: Uri): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            val in1 = contentResolver.openInputStream(uri) ?: return null
            BitmapFactory.decodeStream(in1, null, bounds)
            in1.close()
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }
            // 上限 2048：裁剪框最多占屏宽，2K 已远超所需，再大只是白吃内存。
            var sample = 1
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 2048) {
                sample *= 2
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val in2 = contentResolver.openInputStream(uri) ?: return null
            val bmp = BitmapFactory.decodeStream(in2, null, opts)
            in2.close()
            return bmp
        } catch (t: Throwable) {
            Logs.w(TAG, "解码失败", t)
            return null
        }
    }

    /** 把当前选区裁好落盘，写进聊天背景，然后收工。 */
    private fun saveCrop() {
        val c = crop ?: return
        val bmp = c.getBitmap()
        if (bmp == null || !c.hasFrame()) {
            return
        }
        val box = c.selection()
        val ratio = c.frameRatio()
        try {
            val path = ImageStore.saveCropFrom(this, bmp, box[0], box[1], box[2], box[3],
                    ratio, OUT_WIDTH)
            PetPrefs.setChatBackground(this, path)
            // 【为什么走 HomeUi】那里已经带「服务在跑才发 REFRESH」的判断：
            //   桌宠被用户关掉时不会因为这次保存把服务又拉起来，中间态也不会多一个悬浮窗。
            HomeUi.notifyPetRefresh(this)
            Logs.i(TAG, "[保存] 背景已写入 " + path)
            setResult(RESULT_OK)
        } catch (t: Throwable) {
            Logs.w(TAG, "保存失败", t)
        }
        finish()
    }

    private fun fail(msg: String) {
        Logs.w(TAG, msg)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (crop != null) {
            crop!!.release()
        }
    }

    /**
     * 裁剪视图：自己算「图片相对裁剪框」的位置与缩放。
     *
     * 【坐标】图片用 Matrix 映射到屏幕：图片左上角 (ox, oy)、缩放 S。
     *   拖拽改 (ox, oy) 并永远把框扣在图片内部；缩放围绕框中心、同样夹紧，所以框内永不留白。
     */
    private class CropView(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private val matrix = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val scrim = Paint()
        private val frame = Paint()
        private val grid = Paint()
        private val box = RectF()
        private var baseScale = 1.0f
        private var userScale = 1.0f

        /** 顶栏与底栏各自占用的高度（像素）：由页面量出来后回填，裁剪框必须落在两者之间。 */
        private var insetTop = 0
        private var insetBottom = 0

        /** 回填实测留白；变化时重算裁剪框（用 keepCenter 保住用户已经框好的构图）。 */
        fun setInsets(top: Int, bottom: Int) {
            if (this.insetTop == top && this.insetBottom == bottom) {
                return
            }
            this.insetTop = top
            this.insetBottom = bottom
            layout(box.width() > 0.0f)
            invalidate()
        }

        private var ox = 0.0f
        private var oy = 0.0f
        private var lastX = 0.0f
        private var lastY = 0.0f
        private var lastSpan = 0.0f
        private var multi = false

        init {
            paint.isAntiAlias = true
            scrim.color = 0xA6000000.toInt()
            frame.color = Color.WHITE
            frame.style = Paint.Style.STROKE
            frame.strokeWidth = UiKit.dpf(ctx, 1.5f)
            grid.color = 0x4DFFFFFF
            grid.style = Paint.Style.STROKE
            grid.strokeWidth = UiKit.dpf(ctx, 0.8f)
        }

        fun setImage(b: Bitmap?) {
            release()
            this.bmp = b
            this.userScale = 1.0f
            layout(false)
            invalidate()
        }

        fun getBitmap(): Bitmap? {
            return this.bmp
        }

        fun hasFrame(): Boolean {
            return this.bmp != null && box.width() > 1.0f && box.height() > 1.0f
        }

        fun frameRatio(): Float {
            return if (box.height() <= 0.0f) 1.0f else box.width() / box.height()
        }

        fun release() {
            val b = this.bmp
            if (b != null && !b.isRecycled) {
                b.recycle()
            }
            this.bmp = null
        }

        /** 复位：缩放回到「刚好铺满框」的状态，并把图片摆回居中。 */
        fun reset() {
            this.userScale = 1.0f
            layout(false)
            invalidate()
        }

        /**
         * 以裁剪框中心为锚点缩放。
         * 【为何不在手指中心缩放】按钮触发时没有手指位置；框中心缩放最直观，也不会把图甩出框。
         */
        fun zoomBy(f: Float) {
            if (this.bmp == null) {
                return
            }
            val cx = box.centerX()
            val cy = box.centerY()
            // 缩放前框中心对应的源图点，缩放后要让它仍落在框中心。
            val sx = (cx - this.ox) / scale()
            val sy = (cy - this.oy) / scale()
            this.userScale = clamp(this.userScale * f, 1.0f, 6.0f)
            this.ox = cx - sx * scale()
            this.oy = cy - sy * scale()
            clampOffset()
            invalidate()
        }

        private fun scale(): Float {
            return this.baseScale * this.userScale
        }

        private fun clamp(v: Float, lo: Float, hi: Float): Float {
            return if (v < lo) lo else (if (v > hi) hi else v)
        }

        /**
         * 裁出后要铺到的目标宽高比（宽 / 高）。
         * 【取法】优先用聊天区实测比例（ChatPanel 首帧写进 PetPrefs）；没有就退回整屏比例。
         * 【限制】统一 clamp 到 0.45~1.6，避免极端比例把框压成一条线。
         */
        private fun cropRatioTarget(i: Int, i2: Int): Float {
            var f = PetPrefs.chatBgRatio(context)
            if (f <= 0.0f || f.isNaN() || f.isInfinite()) {
                f = if (width <= 0 || height <= 0) i.toFloat() / i2.toFloat() else width.toFloat() / height.toFloat()
            }
            return Math.min(1.6f, Math.max(0.45f, f))
        }

        /**
         * 依据当前控件尺寸算出裁剪框，并让图片「按比例铺满框」。
         * 【keepCenter】true 时保持框中心对应的源图点不动（尺寸变化时用，避免图片跳动）。
         */
        private fun layout(keepCenter: Boolean) {
            val b = this.bmp ?: return
            val pad = UiKit.dpf(context, PAD_DP)
            val availW = Math.max(1.0f, width - pad * 2.0f)
            // 【留白】上下都要让开顶栏与底栏：它们的高度是实测回填的，
            //   没量到之前先用一组保守下限，避免首帧把框排到按钮底下。
            val top0 = Math.max(UiKit.dpf(context, 96.0f), this.insetTop.toFloat())
            val bot0 = Math.max(UiKit.dpf(context, 150.0f), this.insetBottom.toFloat())
            val availH = Math.max(1.0f, height - top0 - bot0)
            val bw = b.width
            val bh = b.height
            // 框的比例优先取聊天区实测宽高比（ChatPanel 首帧回填到 PetPrefs）。
            // 【为什么】聊天区上下被顶栏 / 工具条 / 附件条 / 输入行吃掉一部分，
            //   比例与整屏并不相同；按整屏比例裁出来，铺到聊天区仍会被裁掉一截。
            // 【兜底】偏好里是 0（还没量到）或屏幕尺寸未知时，退回整屏比例。
            val ratio = cropRatioTarget(bw, bh)
            var w = availW
            var h = w / ratio
            if (h > availH) {
                h = availH
                w = h * ratio
            }
            val left = (width - w) / 2.0f
            val top = top0 + (availH - h) / 2.0f
            val oldCx = box.centerX()
            val oldCy = box.centerY()
            var sx0 = 0.0f
            var sy0 = 0.0f
            val had = box.width() > 0.0f
            if (keepCenter && had) {
                sx0 = (oldCx - this.ox) / scale()
                sy0 = (oldCy - this.oy) / scale()
            }
            box.set(left, top, left + w, top + h)
            // cover：取两个方向的较大倍率，保证框被图片完全覆盖（框内不会露出空底）。
            this.baseScale = Math.max(box.width() / bw, box.height() / bh)
            if (keepCenter && had) {
                this.ox = box.centerX() - sx0 * scale()
                this.oy = box.centerY() - sy0 * scale()
            } else {
                // 初始：图片居中（居中时两侧溢出的部分被框裁掉）。
                this.ox = box.centerX() - bw * scale() / 2.0f
                this.oy = box.centerY() - bh * scale() / 2.0f
            }
            clampOffset()
        }

        /** 把图片扣在框内：任何情况下框都不能越出图片边界。 */
        private fun clampOffset() {
            val b = this.bmp ?: return
            val w = b.width * scale()
            val h = b.height * scale()
            this.ox = clamp(this.ox, box.right - w, box.left)
            this.oy = clamp(this.oy, box.bottom - h, box.top)
        }

        /** 当前选区（源图像素）：[left, top, width, height]。 */
        fun selection(): IntArray {
            val b = this.bmp ?: return intArrayOf(0, 0, 0, 0)
            val s = scale()
            var l = Math.round((box.left - this.ox) / s)
            var t = Math.round((box.top - this.oy) / s)
            var w = Math.round(box.width() / s)
            var h = Math.round(box.height() / s)
            l = Math.max(0, Math.min(l, b.width - 1))
            t = Math.max(0, Math.min(t, b.height - 1))
            w = Math.max(1, Math.min(w, b.width - l))
            h = Math.max(1, Math.min(h, b.height - t))
            return intArrayOf(l, t, w, h)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            layout(ow > 0 && oh > 0)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = this.bmp ?: return
            this.matrix.reset()
            this.matrix.postScale(scale(), scale())
            this.matrix.postTranslate(this.ox, this.oy)
            // 框内画图（裁到框），框外压暗 —— 这就是实时预览。
            canvas.save()
            canvas.clipRect(this.box)
            canvas.drawBitmap(b, this.matrix, this.paint)
            canvas.restore()
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, this.box.top, this.scrim)
            canvas.drawRect(0f, this.box.bottom, w, h, this.scrim)
            canvas.drawRect(0f, this.box.top, this.box.left, this.box.bottom, this.scrim)
            canvas.drawRect(this.box.right, this.box.top, w, this.box.bottom, this.scrim)
            canvas.drawRect(this.box, this.frame)
            for (i in 1 until GRID) {
                val x = this.box.left + this.box.width() * i / GRID
                val y = this.box.top + this.box.height() * i / GRID
                canvas.drawLine(x, this.box.top, x, this.box.bottom, this.grid)
                canvas.drawLine(this.box.left, y, this.box.right, y, this.grid)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (this.bmp == null) {
                return true
            }
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                this.lastX = ev.x
                this.lastY = ev.y
                this.multi = false
                return true
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                this.multi = true
                this.lastSpan = span(ev)
                return true
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                // 抬起一指：另一指重新定位，避免跳一下。
                val keep = if (ev.actionIndex == 0) 1 else 0
                this.lastX = ev.getX(keep)
                this.lastY = ev.getY(keep)
                this.multi = false
                return true
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (this.multi && ev.pointerCount >= 2) {
                    val s = span(ev)
                    if (this.lastSpan > 1.0f && s > 1.0f) {
                        zoomAt(midX(ev), midY(ev), s / this.lastSpan)
                    }
                    this.lastSpan = s
                    return true
                }
                val dx = ev.x - this.lastX
                val dy = ev.y - this.lastY
                this.lastX = ev.x
                this.lastY = ev.y
                this.ox += dx
                this.oy += dy
                clampOffset()
                invalidate()
                return true
            }
            return true
        }

        private fun span(ev: MotionEvent): Float {
            if (ev.pointerCount < 2) {
                return 1.0f
            }
            val dx = ev.getX(0) - ev.getX(1)
            val dy = ev.getY(0) - ev.getY(1)
            return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }

        private fun midX(ev: MotionEvent): Float {
            return if (ev.pointerCount < 2) ev.x else (ev.getX(0) + ev.getX(1)) / 2.0f
        }

        private fun midY(ev: MotionEvent): Float {
            return if (ev.pointerCount < 2) ev.y else (ev.getY(0) + ev.getY(1)) / 2.0f
        }

        /** 双指缩放：以两指中点对应的源图点为锚，缩完把锚点送回中点。 */
        private fun zoomAt(px: Float, py: Float, f: Float) {
            val sx = (px - this.ox) / scale()
            val sy = (py - this.oy) / scale()
            val next = clamp(this.userScale * f, 1.0f, 6.0f)
            if (next == this.userScale) {
                return
            }
            this.userScale = next
            this.ox = px - sx * scale()
            this.oy = py - sy * scale()
            clampOffset()
            invalidate()
        }
    }

    companion object {
        private const val TAG = "DollhouseCrop"
        private const val REQ_PICK = 92

        /** 裁剪框相对屏幕的左右留白（dp）。 */
        private const val PAD_DP = 24.0f

        /** 落盘宽度上限：聊天背景按屏幕宽取用，1080 已足够，再大只是白占地方。 */
        private const val OUT_WIDTH = 1080

        /** 裁剪框框内网格：三分线，帮着对齐构图。 */
        private const val GRID = 3
    }
}
