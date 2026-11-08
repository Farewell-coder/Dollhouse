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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ImageStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.GlobalBackground
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

    /**
     * 【修·点「添加背景图」闪退】原实现是 `PageTransitions.finish(this)`，
     *   而 PageTransitions.finish 内部又调 `activity.finish()` —— 虚方法分派回到本覆盖实现，
     *   形成无限递归，栈里叠了 7000+ 帧后抛 StackOverflowError，页面直接闪退。
     *   【正确写法】必须调 `super.finish()` 走父类真正的收尾，动画自己套上。
     */
    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !android.animation.ValueAnimator.areAnimatorsEnabled()
        ) {
            overridePendingTransition(0, 0)
        } else {
            overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out)
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
            UiKit.applyEdgeToEdge(this)
            return
        }
        window.statusBarColor = Color.BLACK
        setContentView(buildUi())
        UiKit.applyEdgeToEdge(this)
        // 【修·「裁剪页一弹出来就消失」】原来在这里直接 `pick()`，
        //   而此刻窗口还没 attach 完成（本页是 Theme.Translucent，attach 更靠后）。
        //   部分 ROM / 系统版本上，此时 `startActivityForResult` 会**立刻以 CANCELED 回调**，
        //   于是 onActivityResult 走「没选图」分支 → finish() → 用户看到的就是「一闪就没了」。
        //   改到 decorView.post：等第一帧消息队列跑完（窗口已 attach）再拉起相册。
        //   `post` 在 View 未 attach 时仍会排队执行，故不会因为窗口没就绪而丢失。
        window.decorView.post { pick() }
    }

    /* ------------------------ 外壳（Compose·本轮新增） ------------------------ */
    //
    // 【改造说明】改造前整页是手写 View（FrameLayout + LinearLayout + TextView）：
    //   按钮字号写死 `FS_TINY + 2`、字色写死 `Color.WHITE`、描边用 `roundStroke(0x1AFFFFFF…)`，
    //   既与全 App 的 Compose 组件不是一套长相，也不会跟主题 / 莫奈 / 纯黑模式。
    //   现在外壳只有一棵 Compose 树：
    //     · 顶栏「取消」、底部「复位 / − / + / 保存背景」→ [DhForm.Button]（次 / 主两档）
    //     · 中间裁剪区 → [AndroidView] 包住**原封不动**的 [CropView]
    //   引擎一行未动：选图、解码、拖拽、双指、边界夹紧、落盘坐标全部仍是原实现。
    //
    // 【为什么用 AndroidView 而不是重写 CropView】CropView 是位图变换引擎
    //   （Matrix + clampOffset + 输出坐标换算），重写它等于重写整个裁剪语义，
    //   风险远高于收益。项目里已有同模式先例（ProviderDetailPage 用 AndroidView 装整页 View）。
    //
    // 【坑·一个 View 只能有一个 parent】`AndroidView` 的 factory 返回一个仍有 parent 的 View 时，
    //   会抛 `IllegalStateException: The specified child already has a parent`。
    //   Activity 重建路径下 `crop` 可能还挂在旧树上，故先 removeView 兜一道。

    private fun buildUi(): View {
        return ComposeHost.createView(this) { CropShell() }
    }

    /**
     * 裁剪页外壳。
     *
     * 【insetTop / insetBottom 的时序·最容易出错的地方】
     *   改造前靠 `addOnLayoutChangeListener` 量出顶栏底边、底栏顶边再回填给 CropView。
     *   Compose 里换成 [onSizeChanged]，并把「同一个公式」逐项对上：
     *     · 顶栏 → `onSizeChanged` 写在 `padding` **之前**（在链上更外层），
     *       于是量到的是「18dp 上边距 + 按钮高」，正好等于原实现里 `b`（顶栏底边 y）；
     *     · 底栏 → 把 26dp 底部留白做成 Column **内部**的 Spacer，
     *       于是 Column 自身高度 = 原实现里的 `parent.height - t`（底栏高 + 底边距），
     *       不需要任何常数补偿；
     *     · 两处再各自 +10dp 余量，与原实现的 `+ UiKit.dp(10f)` 一致。
     *   两个 inset 先后到达是安全的：[CropView.setInsets] 只在值变化时才重排，
     *   且它内部对「还没量到」有保守下限（`layout()` 里的 96dp / 150dp），
     *   首帧不会把裁剪框排到按钮底下。
     */
    @Composable
    private fun CropShell() {
        // 引擎实例：每次搭页面建一个，并登记到 `crop` 字段 —— saveCrop / onDestroy 仍走它。
        val engine = remember { CropView(this).also { crop = it } }
        val insetGap = UiKit.dp(this, 10.0f)

        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            // 裁剪引擎：原封不动的 CropView
            AndroidView(
                factory = { _ ->
                    (engine.parent as? ViewGroup)?.removeView(engine)
                    engine
                },
                modifier = Modifier.fillMaxSize()
            )

            // ---------- 顶栏：左上「取消」 ----------
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged {
                        insetTop = it.height + insetGap
                        applyInsets()
                    }
                    .padding(top = 18.dp, start = 24.dp, end = 24.dp)
            ) {
                DhForm.Button(text = "取消", primary = false, onClick = { finish() })
            }

            // ---------- 底栏：复位 / 缩小 / 放大 + 保存背景 ----------
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged {
                        insetBottom = it.height + insetGap
                        applyInsets()
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DhForm.Button(
                        text = "复位",
                        primary = false,
                        modifier = Modifier.widthIn(min = 84.dp),
                        onClick = { engine.reset() }
                    )
                    DhForm.Button(
                        text = "−",
                        primary = false,
                        modifier = Modifier.widthIn(min = 64.dp),
                        onClick = { engine.zoomBy(1 / 1.25f) }
                    )
                    DhForm.Button(
                        text = "+",
                        primary = false,
                        modifier = Modifier.widthIn(min = 64.dp),
                        onClick = { engine.zoomBy(1.25f) }
                    )
                }
                DhForm.Button(
                    text = "保存背景",
                    primary = true,
                    modifier = Modifier.padding(top = 14.dp).width(200.dp),
                    onClick = { saveCrop() }
                )
                // 26dp 底部留白放在 Column 内部：Column 实测高度即「底栏 + 底边距」。
                Spacer(Modifier.height(26.dp))
            }
        }
    }

    /* ------------------------------- 选图与落盘 ------------------------------- */

    /** 拉起相册的时刻（uptimeMillis），用于区分「用户取消」与「系统秒回 CANCELED」。 */
    private var openedAt = 0L

    private fun pick() {
        try {
            openedAt = android.os.SystemClock.uptimeMillis()
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
        intent.type = IMAGE_TYPE
        // 【修·读不到选中的图】Photo Picker 返回的是 content:// URI，
        //   没有这个 flag 时 openInputStream 会抛 SecurityException，
        //   被 catch 成 null → 用户看到「这张图解不开」。
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    private fun compatImageIntent(): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = IMAGE_TYPE
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req != REQ_PICK) {
            return
        }
        if (result != RESULT_OK || data == null || data.data == null) {
            // 【区分两种情况】用户主动取消 vs 系统异常返回 CANCELED。
            //   后者会在「裁剪页刚弹出」时发生 —— 那时用户根本没来得及操作，
            //   静默退出会让人以为是「功能坏了」。给一句可见提示，至少能自证发生了什么。
            val askedAt = openedAt
            val elapsed = android.os.SystemClock.uptimeMillis() - askedAt
            Logs.i(TAG, "[取消] result=$result 耗时=${elapsed}ms")
            if (elapsed < 800L) {
                android.widget.Toast.makeText(
                    this, "没打开相册，请重试或检查系统相册是否可用",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
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
    /**
     * 解码成位图。
     *
     * 【为什么有两层】`contentResolver.openInputStream(uri)` 对某些来源会失败：
     *   · Photo Picker / 云盘 / 某些 ROM 的文件管理器返回的 URI，
     *     即使带了 `FLAG_GRANT_READ_URI_PERMISSION`，直读也可能抛 SecurityException；
     *   · 用户看到的就是「这张图解不开」，但相册和图片本身都没问题。
     *   第二层降级：先把内容 copy 到应用缓存目录（走同一套 resolver，但用 FileOutputStream，
     *   绕开 provider 的流式读取限制），再从**本地文件**解码 —— 这条路几乎必成。
     */
    private fun decode(uri: Uri): Bitmap? {
        directDecode(uri)?.let { return it }
        return cachedDecode(uri)
    }

    /** 第二层：先落一份到缓存，再从文件解码。 */
    private fun cachedDecode(uri: Uri): Bitmap? {
        return try {
            val tmp = java.io.File(cacheDir, "crop_src.tmp")
            val ok = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                    true
                } ?: false
            }.getOrDefault(false)
            if (!ok || !tmp.exists() || tmp.length() == 0L) {
                Logs.w(TAG, "降级解码：无法读取内容 uri=$uri")
                return null
            }
            val opts = BitmapFactory.Options()
            val b = BitmapFactory.Options()
            b.inJustDecodeBounds = true
            BitmapFactory.decodeFile(tmp.absolutePath, b)
            if (b.outWidth <= 0 || b.outHeight <= 0) return null
            var sample = 1
            while (Math.max(b.outWidth, b.outHeight) / sample > 2048) sample *= 2
            opts.inSampleSize = sample
            val bmp = BitmapFactory.decodeFile(tmp.absolutePath, opts)
            Logs.i(TAG, "降级解码成功：" + (bmp?.width ?: 0) + "x" + (bmp?.height ?: 0))
            bmp
        } catch (t: Throwable) {
            Logs.w(TAG, "降级解码也失败", t)
            null
        }
    }

    private fun directDecode(uri: Uri): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            // 【r22·修流泄漏】两处都是「open → decodeStream → close」直线写法：
            //   decodeStream 抛异常（损坏图 / Uri 授权失效 / OOM）时 close 被跳过，
            //   每次失败漏一个 fd。裁剪页会反复重解码（旋转、切图），泄漏会累积。
            //   改为 use{}，异常路径也关。另外「尺寸探测返回 null」那条分支原来也没关流。
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return null
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
            return contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
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
            // 【即时生效】路径变了以后，必须让全局背景重解码并重刷所有已挂载的宿主视图，
            //   否则各页仍画着旧图（旧实现只发 REFRESH 通知，而该通知依赖桌宠服务在跑，
            //   且不经过 refreshAll，所以表现为「选完背景要等回前台才变」）。
            //   先 release 让缓存按新路径重建，再 refreshAll 遍历全部登记视图。
            GlobalBackground.release()
            GlobalBackground.refreshAll()
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
        // 【必须给可见反馈】改造前这里只 log 然后 finish —— 用户看到的是
        // 「裁剪页一闪就没了」，完全不知道是相册打不开。这类静默失败最难排查，
        // 也最容易被当成功失效。
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
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
            // 【安全区】横屏时刘海在左右侧：左右留白各自叠加物理安全区（API28 走 view insets），
            //   否则裁剪框会压到侧边刘海被系统裁掉。圆角不再按整边扣除，避免留白过大。
            val safe = UiKit.viewCutoutInsets(this)
            val padL = pad + safe[0]
            val padR = pad + safe[2]
            val availW = Math.max(1.0f, width - padL - padR)
            // 【留白】上下都要让开顶栏与底栏：它们的高度是实测回填的，
            //   没量到之前先用一组保守下限，避免首帧把框排到按钮底下。
            val top0 = Math.max(
                Math.max(UiKit.dpf(context, 96.0f), this.insetTop.toFloat()), safe[1].toFloat()
            )
            val bot0 = Math.max(
                Math.max(UiKit.dpf(context, 150.0f), this.insetBottom.toFloat()), safe[3].toFloat()
            )
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
            val left = padL + (availW - w) / 2.0f
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

        /**
         * 相册的 MIME 过滤。
         * 【为什么抽成常量】KDoc 与字符串里直接写那个星号斜杠组合会**提前闭合注释块**，
         *   本工程已因此踩过一次编译事故（整页报「Expecting member declaration」）。
         *   集中成一个常量后，注释里只出现常量名，物理上不可能再写错。
         */
        private const val IMAGE_TYPE = "image/*"
    }
}
