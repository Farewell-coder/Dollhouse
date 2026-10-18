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
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Fonts
import com.dollhouse.app.ui.theme.UiKit
import java.io.File
import java.io.FileOutputStream

/**
 * 【职责】人偶封面的「选图 → 按三种卡片比例各裁一次 → 保存」一站式页面。
 *
 * 【需求原文】「还有这个选自己的图片 请定位到相册 并且得有编辑图片功能 像第二幅图一样
 *   但是 比例得重新调整 符合那个比例 同时 得选择三次比例 上面有明确的第几次 截取就是
 *   下一个比例返回就是上一个比例 因为三种排列方式大小不同」。
 *
 * 【为什么要裁三次】人偶列表支持 1 / 2 / 3 列三种排布（见 `DollListScreen` 的列数菜单），
 *   而卡片封面框的宽高比随列数变化很大（1 列 0.68 / 2 列 0.90 / 3 列 1.21，推导见
 *   [PetPrefs.DOLL_COVER_RATIOS]）。同一张方图铺到这三种框里，要么两侧留白、
 *   要么裁掉主体。所以让用户**按每种比例各框一次** —— 他虽然只上传一张图，
 *   但三种排布下看到的都是他亲自定的构图。
 *
 * 【三次怎么走】第 1 次裁完点「下一个比例」进第 2 次，第 3 次裁完点「保存」收工；
 *   顶部有明确的「第 N / 3 次」指示与当前比例名，随时可点「上一个比例」回退重裁
 *   （之前的框选结果会被记住，回来时原样恢复，不用重来）。
 *
 * 【与背景裁剪页的区别】`BackgroundCropActivity` 是「一次裁一张、写进聊天背景」；
 *   本页是「连裁三张、按列数落进人偶封面」。两者的交互骨架相似（拖动 / 双指缩放 / 网格），
 *   但**结果语义与落盘路径完全不同**，硬合成一个会立刻出现一堆 `if (是封面)` 分支。
 *   故另开一页，只共享「裁剪视图」这段几何（各自持有自己的实现，因为封面框是**固定比例**、
 *   背景框是**自适应比例**，几何口径本就不一样）。
 *
 * 【落盘】三张裁好的图分别存成 `cover1.png` / `cover2.png` / `cover3.png` 到人偶目录，
 *   并把「当前列数对应那一张」写进 [DollStore.Doll.cover]；切列数时由列表页按需换图
 *   （见 [DollStore.setCoverForCols]）。
 */
class CoverCropActivity : Activity() {

    private var crop: CropView? = null

    /** 目标人偶 id（从 Intent 带入）。 */
    private var dollId: String = ""

    /** 当前是第几次裁剪（0 基：0 = 一列 / 1 = 两列 / 2 = 三列）。 */
    private var step = 0

    /**
     * 每一步各自记住的框选状态。
     *
     * 【为什么必须分开存】用户在三种比例下来回切时，每次的构图偏好不一样
     *   （竖图在 1 列下要往上取，横图在 3 列下要往左取）。共用一个状态会让
     *   回退时看到「上一次比例留下的偏移」，非常困惑。
     *   索引与 [PetPrefs.DOLL_COVER_RATIOS] 一一对应。
     */
    private val savedMatrix = arrayOfNulls<FloatArray>(3)

    /** 顶部「第 N / 3 次」与比例名。 */
    private var stepLabel: TextView? = null

    /** 「上一个比例」按钮（第 1 次时置灰不可点）。 */
    private var prevBtn: TextView? = null

    /** 主操作按钮：前两次是「下一个比例」，最后一次是「保存」。 */
    private var nextBtn: TextView? = null

    /** 顶栏 / 底栏实测占用高度（像素），由页面量出来后回填给 CropView。 */
    private var insetTop = 0
    private var insetBottom = 0

    private fun applyInsets() {
        val c = crop
        if (c != null) {
            c.setInsets(insetTop, insetBottom)
        }
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        dollId = intent?.getStringExtra(EXTRA_DOLL) ?: ""
        if (dollId.isEmpty()) {
            Logs.w(TAG, "[无目标] 没带 dollId，直接收尾")
            finish()
            return
        }
        // 【重建】不重弹相册：本页要活到系统把选图结果投递回来。
        //   重建路径下界面照常搭出，等结果自己回来。
        window.statusBarColor = Color.BLACK
        setContentView(buildUi())
        UiKit.applyEdgeToEdge(this)
        if (bundle == null) {
            pick()
        } else {
            Logs.i(TAG, "[重建] 不再重弹相册，等待结果")
        }
    }

    /* ============================ 界面 ============================ */

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

    /** 顶栏：取消 + 「第 N / 3 次」+ 当前比例名。 */
    private fun buildTopBar(root: FrameLayout) {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        val cancel = pill("取消")
        cancel.setOnClickListener { finish() }
        bar.addView(cancel)

        // 步骤指示：居中、占满剩余宽度，右侧需要真居中就靠一层 Spacer 平衡。
        val label = TextView(this)
        label.typeface = Fonts.uiBold(this)
        label.setTextSize(UiKit.FS_TINY + 3.0f)
        label.setTextColor(Color.WHITE)
        label.gravity = Gravity.CENTER
        bar.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))
        stepLabel = label

        // 右侧放一个与「取消」等宽的占位，保证中间的指示文字是真居中（不是偏左）。
        val ghost = pill("取消")
        ghost.visibility = View.INVISIBLE
        bar.addView(ghost)

        val lp = FrameLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(this, 18.0f)
        lp.leftMargin = UiKit.dp(this, PAD_DP)
        lp.rightMargin = UiKit.dp(this, PAD_DP)
        root.addView(bar, lp)
        bar.addOnLayoutChangeListener { _, _, _, _, b, _, _, _, _ ->
            insetTop = b + UiKit.dp(this, 10.0f)
            applyInsets()
        }
    }

    /** 底栏：上一个比例 / 复位 / − / + / 下一个比例（或保存）。 */
    private fun buildBottomBar(root: FrameLayout) {
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.gravity = Gravity.CENTER_HORIZONTAL

        // 第一行：复位 / 缩小 / 放大（与背景裁剪页同款，用户已经熟悉这套操作）。
        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        tools.gravity = Gravity.CENTER
        tools.addView(tool("复位") { crop?.reset() })
        tools.addView(tool("−") { crop?.zoomBy(1 / 1.25f) })
        tools.addView(tool("+") { crop?.zoomBy(1.25f) })
        bottom.addView(tools)

        // 第二行：上一个比例（次操作）+ 主操作。
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER
        val prev = tool("上一个")
        prev.setOnClickListener { goStep(step - 1) }
        actions.addView(prev)
        prevBtn = prev

        val main = TextView(this)
        main.typeface = Fonts.uiBold(this)
        main.setTextSize(UiKit.FS_BTN)
        main.setTextColor(Color.WHITE)
        main.gravity = Gravity.CENTER
        main.background = UiKit.round(UiKit.ACC, this, 12f)
        UiKit.press(main)
        main.setOnClickListener { onMainAction() }
        val mLp = LinearLayout.LayoutParams(UiKit.dp(this, 150.0f), UiKit.dp(this, 40.0f))
        mLp.leftMargin = UiKit.dp(this, 10.0f)
        actions.addView(main, mLp)
        nextBtn = main

        val aLp = LinearLayout.LayoutParams(-2, -2)
        aLp.topMargin = UiKit.dp(this, 12.0f)
        bottom.addView(actions, aLp)

        val botLp = FrameLayout.LayoutParams(-1, -2)
        botLp.gravity = Gravity.BOTTOM
        botLp.bottomMargin = UiKit.dp(this, 22.0f)
        root.addView(bottom, botLp)

        bottom.addOnLayoutChangeListener { v, _, t, _, _, _, _, _, _ ->
            val parent = v.parent as View
            insetBottom = Math.max(0, parent.height - t) + UiKit.dp(this, 10.0f)
            applyInsets()
        }

        refreshStep()
    }

    /** 刷新步骤相关的界面文案与可按状态。 */
    private fun refreshStep() {
        val total = PetPrefs.DOLL_COVER_RATIOS.size
        val label = stepLabel ?: return
        val cols = step + 1
        label.text = "第 " + (step + 1) + " / " + total + " 次 · " + cols + " 列排布"
        val prev = prevBtn
        if (prev != null) {
            // 【可点判据】第 1 步没有上一步；置灰但仍然占位，避免按钮左右横跳。
            prev.alpha = if (step <= 0) 0.35f else 1.0f
            prev.isEnabled = step > 0
        }
        val main = nextBtn
        if (main != null) {
            main.text = if (step >= total - 1) "保存" else "下一个比例"
        }
        // 让裁剪框切到当前步的比例，并恢复这一步之前框好的构图。
        crop?.applyStep(step, savedMatrix[step])
    }

    /**
     * 切到第 [target] 步。
     *
     * 【先存后切】切之前把当前框的偏移记进 [savedMatrix]，回来时才能原样恢复。
     */
    private fun goStep(target: Int) {
        if (target < 0 || target >= PetPrefs.DOLL_COVER_RATIOS.size) {
            return
        }
        val c = crop ?: return
        savedMatrix[step] = c.snapshot()
        step = target
        refreshStep()
    }

    /** 主按钮：最后一次保存，否则进下一步。 */
    private fun onMainAction() {
        if (step >= PetPrefs.DOLL_COVER_RATIOS.size - 1) {
            saveAll()
        } else {
            goStep(step + 1)
        }
    }

    /* ============================ 选图 ============================ */

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

    /** 【必须显式请求相册】需求原文「选自己的图片 请定位到相册」。 */
    private fun pickImageIntent(): Intent {
        val intent = Intent("android.provider.action.PICK_IMAGES")
        intent.type = IMAGE_TYPE
        return intent
    }

    private fun compatImageIntent(): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = IMAGE_TYPE
        return intent
    }

    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req != REQ_PICK) {
            return
        }
        val uri: Uri? = data?.data
        if (result != RESULT_OK || uri == null) {
            Logs.i(TAG, "[取消] 没选图，直接收尾")
            finish()
            return
        }
        Thread({
            val bmp = decode(uri)
            runOnUiThread {
                if (bmp == null) {
                    fail("这张图解不开")
                    return@runOnUiThread
                }
                crop?.setImage(bmp)
            }
        }, "dollhouse-cover-decode").start()
    }

    /** 解码成位图：先读尺寸再按需降采样。 */
    private fun decode(uri: Uri): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            // 【r20·修流泄漏】两处 openInputStream 原来都是「open → decodeStream → close」：
            //   decodeStream 抛异常（损坏图 / OOM）时 close 被跳过 → fd 泄漏。
            //   封面裁剪页反复选图会反复走到这里。
            contentResolver.openInputStream(uri)?.use { in1 ->
                BitmapFactory.decodeStream(in1, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }
            // 上限 2048：封面最终最长边 1024，2K 输入足够精细且不吃内存。
            var sample = 1
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 2048) {
                sample *= 2
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            return contentResolver.openInputStream(uri)?.use { in2 ->
                BitmapFactory.decodeStream(in2, null, opts)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "解码失败", t)
            return null
        }
    }

    /* ============================ 落盘 ============================ */

    /**
     * 把三种比例各裁一张落盘，并把「当前列数那一张」设为封面。
     *
     * 【为什么不只存当前这一张】用户可能之后切换列数；若只存一张，
     *   切列数时就要拿不合比例的图去凑，等于白裁了这两次。三张一起存，切列数无缝。
     * 【落盘位置】人偶私有目录（`filesDir/doll/`），与其它封面资产同处一地，便于导出。
     */
    private fun saveAll() {
        val c = crop ?: return
        val bmp = c.getBitmap()
        if (bmp == null || !c.hasFrame()) {
            return
        }
        // 【先存当前步】用户可能直接点保存而没经过 goStep，这里补一次。
        savedMatrix[step] = c.snapshot()
        val paths = arrayOfNulls<String>(PetPrefs.DOLL_COVER_RATIOS.size)
        try {
            // 【原图需保留】裁每一张都要用原图；用第一张裁完后 bmp 不能被回收。
            for (i in paths.indices) {
                paths[i] = cropOne(bmp, i)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "裁剪落盘失败", t)
        }
        val okPaths = paths.filterNotNull()
        if (okPaths.isEmpty()) {
            HomeUi.showNotice(this, "保存失败", "没能写出封面图，请重试。")
            finish()
            return
        }
        // 只把「与当前列数匹配」的那张挂上去；其余留给切列数时用。
        val idx = Math.max(0, Math.min(paths.size - 1, PetPrefs.dollCols(this) - 1))
        val chosen = paths[idx] ?: okPaths[0]
        DollStore.setCoverFromCrop(this, dollId, chosen)
        DollStore.setCoverSet(this, dollId, paths.filterNotNull().toTypedArray())
        DollState.bump()
        HomeUi.notifyPetReload(this)
        Logs.i(TAG, "[保存] 封面已写入 " + chosen)
        setResult(RESULT_OK)
        finish()
    }

    /**
     * 按第 [i] 种比例裁一张。
     *
     * 【实现】借用 [CropView] 的几何：临时切到该步比例、应用该步的框选偏移、
     *   取选区、裁出、写文件，再切回原步。全程在调用方线程（保存按钮里），
     *   单帧操作，几十毫秒级，不会明显卡顿。
     */
    private fun cropOne(full: Bitmap, i: Int): String? {
        val c = crop ?: return null
        val backupStep = step
        val backupMatrix = c.snapshot()
        try {
            step = i
            c.applyStep(i, savedMatrix[i])
            val box = c.selection()
            if (box[2] <= 0 || box[3] <= 0) {
                return null
            }
            val cut = Bitmap.createBitmap(full, box[0], box[1], box[2], box[3])
            val f = File(DollStore.coversDir(this), "cover_" + (i + 1) + ".png")
            FileOutputStream(f).use { os ->
                cut.compress(Bitmap.CompressFormat.PNG, 100, os)
                os.flush()
            }
            if (cut !== full) {
                cut.recycle()
            }
            return f.absolutePath
        } finally {
            // 【必须还原】否则界面会停在临时比例上，用户看到的框和他点之前不一样。
            step = backupStep
            c.applyStep(backupStep, backupMatrix)
            refreshStep()
        }
    }

    private fun fail(msg: String) {
        Logs.w(TAG, msg)
        HomeUi.showNotice(this, "打开失败", msg)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        crop?.release()
    }

    /* ============================ 裁剪视图 ============================ */

    /**
     * 封面裁剪视图：固定宽高比（按步切换）、图片始终铺满框、单指拖动 / 双指缩放。
     *
     * 【与背景裁剪页的 CropView 的差别】那边框的比例来自「聊天区实测比例」（随屏幕变），
     *   这边是**常量比例**（随列表列数变）。所以这里的框尺寸是算出来的确定值，
     *   不需要读偏好、也不需要 clamp 极端值。
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
        private var insetTop = 0
        private var insetBottom = 0

        /** 当前目标宽高比（宽 / 高）。 */
        private var ratio = 1.0f

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

        fun setInsets(top: Int, bottom: Int) {
            if (insetTop == top && insetBottom == bottom) {
                return
            }
            insetTop = top
            insetBottom = bottom
            layout(true)
            invalidate()
        }

        /** 传 null 会被忽略（重建路径下的时序兜底）。 */
        fun setImage(b: Bitmap?) {
            if (b == null) {
                return
            }
            release()
            bmp = b
            userScale = 1.0f
            ox = 0.0f
            oy = 0.0f
            layout(false)
            invalidate()
        }

        fun getBitmap(): Bitmap? = bmp

        fun hasFrame(): Boolean = bmp != null && box.width() > 1f && box.height() > 1f

        fun release() {
            val b = bmp
            if (b != null && !b.isRecycled) {
                b.recycle()
            }
            bmp = null
        }

        fun reset() {
            userScale = 1.0f
            layout(false)
            invalidate()
        }

        fun zoomBy(f: Float) {
            if (bmp == null) {
                return
            }
            val cx = box.centerX()
            val cy = box.centerY()
            val sx = (cx - ox) / scale()
            val sy = (cy - oy) / scale()
            userScale = clamp(userScale * f, 1f, 6f)
            ox = cx - sx * scale()
            oy = cy - sy * scale()
            clampOffset()
            invalidate()
        }

        /** 当前框选状态的快照 —— 用于在三种比例之间来回恢复。
         *
         * 【存什么·关键设计】存「框中心对应源图中的相对位置 + 用户缩放」，**不存像素偏移**。
         *   原因：三种比例的框尺寸与位置都不一样（框宽从 376dp 变到 118dp），
         *   像素偏移 (ox, oy) 是相对某个具体框算出来的，换到另一个框上语义就废了。
         *   而「框中心落在源图的 38% 宽度处」这种**相对**描述在任何框尺寸下都成立 ——
         *   换算过去就是「我上次把主体放在画面中心，现在还在中心」，这正是用户期望的。
         *
         * @return [nx, ny, userScale]：nx/ny 为 0~1 的相对位置。
         */
        fun snapshot(): FloatArray {
            val b = bmp ?: return floatArrayOf(0.5f, 0.5f, userScale)
            val s = scale()
            val sx = if (b.width > 0) ((box.centerX() - ox) / s) / b.width else 0.5f
            val sy = if (b.height > 0) ((box.centerY() - oy) / s) / b.height else 0.5f
            return floatArrayOf(
                clamp(sx, 0f, 1f),
                clamp(sy, 0f, 1f),
                clamp(userScale, 1f, 6f)
            )
        }

        /**
         * 切到第 [i] 种比例。
         *
         * 【为什么框选状态可空】第一次进入某一步时没有历史状态 = 重新居中铺满；
         *   只有回退重进过的步才带着用户之前框好的构图。
         */
        fun applyStep(i: Int, state: FloatArray?) {
            val ratios = PetPrefs.DOLL_COVER_RATIOS
            if (i < 0 || i >= ratios.size) {
                return
            }
            // 【r13·用实测比例】第 i 步对应「i+1 列排布」。
            //   【为什么不用 ratios[i]】那是按 408dp 屏宽推导的**估算值**，与真实机型有偏差；
            //   列表页会把封面框的真实宽高比写进 `cover_ratio_<cols>`（见 DollListScreen 的
            //   onSizeChanged），这里优先取它 —— 裁出来的图才能与卡片严丝合缝地铺满。
            //   没实测过（首启直接改封面）才回落到估算值。
            val cols = i + 1
            val measured = PetPrefs.coverRatioMeasured(context, cols)
            ratio = if (measured > 0.01f) measured else ratios[i]
            // 先按新比例重排框（这一步会把 ox/oy 重置为居中且 baseScale 按新框重算）。
            layout(false)
            if (state != null) {
                restore(state)
            }
            invalidate()
        }

        /**
         * 按快照恢复构图。
         *
         * 【顺序不能反】必须先 [layout]（算出新框的 baseScale 与新框位置），
         *   再按新框换算 —— 否则用的是旧框的尺寸，恢复出来会偏。
         */
        private fun restore(state: FloatArray) {
            val b = bmp ?: return
            if (state.size < 3) {
                return
            }
            userScale = clamp(state[2], 1f, 6f)
            val s = scale()
            // 让「源图上的 (nx, ny) 那个点」重新落在新框的中心。
            ox = box.centerX() - (state[0] * b.width) * s
            oy = box.centerY() - (state[1] * b.height) * s
            clampOffset()
        }

        private fun scale(): Float = baseScale * userScale

        private fun clamp(v: Float, lo: Float, hi: Float): Float {
            return if (v < lo) lo else (if (v > hi) hi else v)
        }

        /**
         * 依据当前控件尺寸与目标比例，算出裁剪框，并让图片「按比例铺满框」。
         *
         * @param keepCenter true = 尺寸变化时保住框中心对应的源图点（避免图片跳动）。
         */
        private fun layout(keepCenter: Boolean) {
            val b = bmp ?: return
            val pad = UiKit.dpf(context, PAD_DP)
            val safe = UiKit.viewCutoutInsets(this)
            val padL = pad + safe[0]
            val padR = pad + safe[2]
            val availW = Math.max(1f, width - padL - padR)
            val top0 = Math.max(
                Math.max(UiKit.dpf(context, 96f), insetTop.toFloat()), safe[1].toFloat()
            )
            val bot0 = Math.max(
                Math.max(UiKit.dpf(context, 170f), insetBottom.toFloat()), safe[3].toFloat()
            )
            val availH = Math.max(1f, height - top0 - bot0)
            var w = availW
            var h = w / ratio
            if (h > availH) {
                h = availH
                w = h * ratio
            }
            val left = padL + (availW - w) / 2f
            val top = top0 + (availH - h) / 2f

            val oldCx = box.centerX()
            val oldCy = box.centerY()
            var sx0 = 0f
            var sy0 = 0f
            val had = box.width() > 0f
            if (keepCenter && had) {
                sx0 = (oldCx - ox) / scale()
                sy0 = (oldCy - oy) / scale()
            }
            box.set(left, top, left + w, top + h)
            baseScale = Math.max(box.width() / b.width, box.height() / b.height)
            if (keepCenter && had) {
                ox = box.centerX() - sx0 * scale()
                oy = box.centerY() - sy0 * scale()
            } else {
                ox = box.centerX() - b.width * scale() / 2f
                oy = box.centerY() - b.height * scale() / 2f
            }
            clampOffset()
        }

        private fun clampOffset() {
            val b = bmp ?: return
            val w = b.width * scale()
            val h = b.height * scale()
            ox = clamp(ox, box.right - w, box.left)
            oy = clamp(oy, box.bottom - h, box.top)
        }

        /** 当前选区（源图像素）：[left, top, width, height]。 */
        fun selection(): IntArray {
            val b = bmp ?: return intArrayOf(0, 0, 0, 0)
            val s = scale()
            var l = Math.round((box.left - ox) / s)
            var t = Math.round((box.top - oy) / s)
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
            val b = bmp ?: return
            matrix.reset()
            matrix.postScale(scale(), scale())
            matrix.postTranslate(ox, oy)
            canvas.save()
            canvas.clipRect(box)
            canvas.drawBitmap(b, matrix, paint)
            canvas.restore()
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, box.top, scrim)
            canvas.drawRect(0f, box.bottom, w, h, scrim)
            canvas.drawRect(0f, box.top, box.left, box.bottom, scrim)
            canvas.drawRect(box.right, box.top, w, box.bottom, scrim)
            canvas.drawRect(box, frame)
            for (i in 1 until GRID) {
                val x = box.left + box.width() * i / GRID
                val y = box.top + box.height() * i / GRID
                canvas.drawLine(x, box.top, x, box.bottom, grid)
                canvas.drawLine(box.left, y, box.right, y, grid)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (bmp == null) {
                return true
            }
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                lastX = ev.x
                lastY = ev.y
                multi = false
                return true
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                multi = true
                lastSpan = span(ev)
                return true
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                val keep = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(keep)
                lastY = ev.getY(keep)
                multi = false
                return true
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (multi && ev.pointerCount >= 2) {
                    val s = span(ev)
                    if (lastSpan > 1f && s > 1f) {
                        zoomAt(midX(ev), midY(ev), s / lastSpan)
                    }
                    lastSpan = s
                    return true
                }
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                lastX = ev.x
                lastY = ev.y
                ox += dx
                oy += dy
                clampOffset()
                invalidate()
                return true
            }
            return true
        }

        private fun span(ev: MotionEvent): Float {
            if (ev.pointerCount < 2) {
                return 1f
            }
            val dx = ev.getX(0) - ev.getX(1)
            val dy = ev.getY(0) - ev.getY(1)
            return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }

        private fun midX(ev: MotionEvent): Float {
            return if (ev.pointerCount < 2) ev.x else (ev.getX(0) + ev.getX(1)) / 2f
        }

        private fun midY(ev: MotionEvent): Float {
            return if (ev.pointerCount < 2) ev.y else (ev.getY(0) + ev.getY(1)) / 2f
        }

        private fun zoomAt(px: Float, py: Float, f: Float) {
            val sx = (px - ox) / scale()
            val sy = (py - oy) / scale()
            val next = clamp(userScale * f, 1f, 6f)
            if (next == userScale) {
                return
            }
            userScale = next
            ox = px - sx * scale()
            oy = py - sy * scale()
            clampOffset()
            invalidate()
        }
    }

    /* ============================ 小控件 ============================ */

    private fun pill(text: String): TextView {
        val t = TextView(this)
        t.typeface = Fonts.ui(this)
        t.text = text
        t.setTextSize(UiKit.FS_TINY + 2f)
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.background = UiKit.roundStroke(0x1AFFFFFF, 0x66FFFFFF, this, 999f)
        t.setPadding(UiKit.dp(this, 16f), UiKit.dp(this, 7f), UiKit.dp(this, 16f), UiKit.dp(this, 7f))
        UiKit.press(t)
        return t
    }

    /** 底部工具按钮：透明底 + 白描边 + 白字。 */
    private fun tool(text: String, click: (() -> Unit)? = null): TextView {
        val t = TextView(this)
        t.typeface = Fonts.ui(this)
        t.text = text
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.background = UiKit.roundStroke(0x14FFFFFF, 0x59FFFFFF, this, 10f)
        if (click != null) {
            t.setOnClickListener { click.invoke() }
        }
        UiKit.press(t)
        val lp = LinearLayout.LayoutParams(UiKit.dp(this, 84f), UiKit.dp(this, 40f))
        lp.leftMargin = UiKit.dp(this, 5f)
        lp.rightMargin = UiKit.dp(this, 5f)
        t.layoutParams = lp
        return t
    }

    companion object {
        private const val TAG = "DollhouseCoverCrop"

        /** 选图请求码（与背景裁剪页错开：两页可能同时存在于任务栈里）。 */
        private const val REQ_PICK = 93

        /** 裁剪框相对屏幕的左右留白（dp）。 */
        private const val PAD_DP = 24.0f

        /** 裁剪框框内网格：三分线，帮着对齐构图。 */
        private const val GRID = 3

        /** 相册的 MIME 过滤（同样抽成常量，避免注释里出现那个会闭合注释的组合）。 */
        private const val IMAGE_TYPE = "image/*"

        /** Intent 键：目标人偶 id。 */
        const val EXTRA_DOLL = "dh_cover_doll"

        /** 打开本页给某只人偶改封面。 */
        @JvmStatic
        fun open(act: Activity, dollId: String) {
            try {
                val i = Intent(act, CoverCropActivity::class.java)
                i.putExtra(EXTRA_DOLL, dollId)
                act.startActivity(i)
            } catch (t: Throwable) {
                Logs.w(TAG, "打不开封面裁剪页", t)
            }
        }
    }
}
