package com.dollhouse.app.ui.theme

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.dollhouse.app.data.ImageStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.chat.ChatBgDrawable
import java.util.WeakHashMap

/**
 * 【职责】全 App 唯一的「用户背景图」来源：解码、缓存、缩放、可读性遮罩，一处分发。
 *
 * 【统一来源】图片路径与透明度都复用 [PetPrefs.chatBackground] / [PetPrefs.chatBgAlpha]
 *   （首页、聊天页、聊天面板、抽屉共用同一张图），不再各页各自解码。
 *
 * 【异步 + 缓存】解码壁纸级大图绝不能在主线程做。本类在后台线程把图缩到长边上限后
 *   缓存成一张 [Bitmap]，并在同一后台线程采样出「明暗极值」用于推导遮罩。
 *   因此任何 onDraw / 绘制回调里都不会触发解码。
 *
 * 【不变形】绘制走 [ChatBgDrawable] 的 center-crop：等比放大到刚好覆盖容器、多余居中裁掉，
 *   宁可裁边也绝不拉伸。
 *
 * 【不重复叠图】谁挂背景谁负责：首页把图挂在两页共用的宿主 FrameLayout 上（页面自身透明），
 *   覆盖页由 [installPage] 在页根铺一次并垫不透明底（遮住下层同一张图）；抽屉另起一层且底部垫不透明底色。
 *
 * 【可读性】遮罩不取平均色，而是对采样像素里的「最亮 / 最暗区域」求各自所需遮罩，
 *   取更严者，保证任意一张图上压在正文 / 副标题都能满足 WCAG 4.5:1（可读性优先，遮罩可到全不透明）。
 */
object GlobalBackground {

    private const val THREAD_NAME = "dh-bg"
    /** 折中清晰度与内存的长边上限（px）。 */
    private const val MAX_DIM = 1440
    /** 代表色采样小图的边长（px）。 */
    private const val SAMPLE = 24
    /** 采样失败时的兜底平均色（中性灰）。 */
    private const val DEFAULT_AVG = 0xFF808080.toInt()

    /** 已挂背景的视图 → 复现参数（弱键：视图被回收后条目自动消失，不会泄漏）。 */
    private class Entry(val fallback: () -> Int, val opaqueBase: Boolean)

    private val registry = WeakHashMap<View, Entry>()

    private var cachedPath: String? = null
    private var cachedBmp: Bitmap? = null
    private var cachedPixels: IntArray? = null
    /** 模糊后的绘制用图；半径 0 时为 null，绘制直接复用 [cachedBmp]。 */
    private var cachedBlurBmp: Bitmap? = null
    /** 当前缓存对应的毛玻璃半径（-1 = 未加载）。 */
    private var cachedRadius = -1
    private var cachedAvg: Int = DEFAULT_AVG
    private var loading = false
    /** 上一次解码是否失败。失败不永久缓存，下次 install 会重试。 */
    private var loadFailed = false
    /** 遮罩透明度缓存：随（底色 / 正文 / 副标题）变化重算。 */
    private var scrimKey = 0
    private var scrimAlpha = -1

    /** 当前背景图相对路径；未设置返回空串。 */
    fun path(c: Context): String {
        return try {
            PetPrefs.chatBackground(c) ?: ""
        } catch (ignored: Throwable) {
            ""
        }
    }

    /** 背景图不透明度百分比（0..100，越大图越实）。 */
    fun alphaPercent(c: Context): Int {
        return try {
            PetPrefs.chatBgAlpha(c)
        } catch (ignored: Throwable) {
            30
        }
    }

    /** 是否已设置且文件存在。 */
    fun hasImage(c: Context): Boolean {
        val p = path(c)
        if (p.isEmpty()) {
            return false
        }
        return try {
            ImageStore.exists(c, p)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * 可读性遮罩色：对采样像素里的最亮 / 最暗区域分别求所需遮罩，取更严的那个。
     * 不用平均色——平均色会把「大面积白 + 一条黑」洗成中灰，漏掉真正读不清的极值区。
     * 【消除双重压暗】图片在 UI 里按 [imgAlpha] 半透明叠在底色上，遮罩必须按「有效色」
     *   （原图 × 透明度 叠底色）推导，否则图会被自身衰减 + 遮罩再压一遍，发灰发闷。
     */
    fun scrimColor(c: Context, imgAlpha: Int): Int {
        val base = UiKit.BG
        val cap = scrimCap(imgAlpha)
        // 【遮罩强度】把 WCAG 推导出的「必要遮罩」按用户强度缩放：100 = 原样，0 = 完全不加。
        //   只缩最终 alpha，不动推导本身，所以「图太亮必须压暗」的判定依旧成立。
        val k = scrimStrength(c)
        val pixels = cachedPixels
        if (pixels == null || pixels.isEmpty()) {
            val a = Math.max(
                ContrastCore.minOverlayAlphaEffective(cachedAvg, base, imgAlpha, UiKit.BG, UiKit.TITLE, ContrastCore.AA_NORMAL),
                ContrastCore.minOverlayAlphaEffective(cachedAvg, base, imgAlpha, UiKit.BG, UiKit.SUB, ContrastCore.AA_NORMAL))
            return ContrastCore.withAlpha(UiKit.BG, Math.max(0, Math.min(a * k / 100, cap)))
        }
        val key = (UiKit.BG * 131) xor (UiKit.TITLE * 17) xor (UiKit.SUB * 7) xor imgAlpha xor (cap shl 24) xor (k shl 16)
        if (key != scrimKey) {
            var worst = 0
            for (px in pixels) {
                if (ContrastCore.alphaOf(px) < 16) {
                    continue
                }
                val t = ContrastCore.minOverlayAlphaEffective(px, base, imgAlpha, UiKit.BG, UiKit.TITLE, ContrastCore.AA_NORMAL)
                if (t > worst) {
                    worst = t
                }
                val s = ContrastCore.minOverlayAlphaEffective(px, base, imgAlpha, UiKit.BG, UiKit.SUB, ContrastCore.AA_NORMAL)
                if (s > worst) {
                    worst = s
                }
            }
            scrimAlpha = Math.max(0, Math.min(worst * k / 100, cap))
            scrimKey = key
        }
        return ContrastCore.withAlpha(UiKit.BG, scrimAlpha)
    }

    /**
     * 遮罩不透明度的上限（0..255）：让「滑条拉到多实」真正决定图有多清晰。
     *
     * 【解决什么】原实现无论滑条多高都按 WCAG 反推遮罩，浅色照片上会推出接近全不透明的压暗层，
     *   于是「透明度 100% 了图还是灰的」——用户看到的是遮罩，不是图片本身的透明度。
     * 【口径】不透明度 100% 时不再叠任何压暗层（原图直出）；往下每降 1% 上限放宽 1/100，
     *   默认 60% 时上限 ≈40%（约 0x66），图能如实透出来，同时仍有基础压暗兜住正文可读性。
     * 【代价】透明度拉满且背景是浅色照片时，直接压在整页上的文字对比会降低；
     *   本 App 的正文/副标题本就压在 CARD 底上，另有描边与字色夹紧兜底，故按用户定案（方案 A）执行。
     */
    /** 遮罩强度百分比（0~100），读偏好；任何异常一律回退 100（原样生效）。 */
    private fun scrimStrength(c: Context): Int {
        return try {
            PetPrefs.scrimStrength(c)
        } catch (ignored: Throwable) {
            100
        }
    }
    /** 背景毛玻璃半径（0~24 dp），读偏好；任何异常一律回退 0（不模糊）。 */
    private fun blurRadius(c: Context): Int {
        return try {
            PetPrefs.blurRadius(c)
        } catch (ignored: Throwable) {
            0
        }
    }
    private fun scrimCap(imgAlpha: Int): Int {
        val a = Math.max(0, Math.min(100, imgAlpha))
        return (100 - a) * 255 / 100
    }

    /**
     * 给一个 View 挂上全局背景（幂等）。图片尚未就绪时先铺 [fallbackColor] 兜底，
     * 解码完成后自动刷新（[refreshAll] 会遍历所有登记视图）。
     *
     * @param fallbackColor 无图 / 解码中时的纯色底；alpha=0 表示「不铺任何底」（置空背景）。
     *                      传入 [UiKit.BG] / [UiKit.CARD] 等主题色时跟随主题，换主题后自动取新底色。
     * @param opaqueBase    true 时在图片下固定垫 [UiKit.BG] 不透明底，避免透出下层同一张图（覆盖页 / 抽屉用）。
     */
    fun install(v: View?, fallbackColor: Int = UiKit.BG, opaqueBase: Boolean = false) {
        if (v == null) {
            return
        }
        val provider = fallbackProvider(fallbackColor)
        synchronized(registry) { registry[v] = Entry(provider, opaqueBase) }
        applyNow(v, provider, opaqueBase)
        ensureLoaded(v.context)
    }

    /** 覆盖页 / 二级页专用：页根铺一次全局背景（带不透明底遮住下层同一张图，避免叠影），
     * 并把页内「页面级不透明底色容器」统一置透明，让图只由页根画一次；卡片自身底色保留。 */
    fun installPage(root: View?) {
        if (root == null) {
            return
        }
        install(root, UiKit.BG, true)
        clearOpaquePageBgs(root)
    }

    /** 只重刷一个已登记视图（未登记则按默认参数挂一次）。 */
    fun refresh(v: View?) {
        if (v == null) {
            return
        }
        val e = synchronized(registry) { registry[v] }
        if (e == null) {
            install(v)
        } else {
            applyNow(v, e.fallback, e.opaqueBase)
        }
    }

    /** 主题 / 图片变化后遍历所有登记视图重建背景（弱键，无需手动注销）。 */
    fun refreshAll() {
        val keys = synchronized(registry) { registry.keys.toTypedArray() }
        for (v in keys) {
            val e = synchronized(registry) { registry[v] } ?: continue
            applyNow(v, e.fallback, e.opaqueBase)
            // 【毛玻璃 / 填充模式变化】重建 drawable 只换了画法，模糊图与解码缓存还得对齐一次：
            //   半径变了要重算模糊，路径变了要重解。已经对齐时 ensureLoaded 会直接返回，无额外开销。
            ensureLoaded(v.context)
        }
    }

    /**
     * 释放缓存引用（例如用户清空背景后可由调用方触发）。
     *
     * 【不主动 recycle】缓存位图仍可能被现存 [ChatBgDrawable] 引用，回收会直接导致绘制崩溃。
     *   这里只断开本类的引用，交给 GC 处理。
     */
    fun release() {
        synchronized(this) {
            cachedPath = null
            cachedBmp = null
            cachedBlurBmp = null
            cachedRadius = -1
            cachedPixels = null
            cachedAvg = DEFAULT_AVG
            loading = false
            loadFailed = false
            scrimKey = 0
            scrimAlpha = -1
        }
    }

    /**
     * 首页专用：把背景铺在「两页共用宿主」上，并把两页滚动区自身置透明，
     * 使图片只在宿主绘制一次（不重复叠图）。
     * 宿主的定位对 HomeUi 的装配顺序无侵入：content 只有一个子层，HomeUi 已把两页塞进它。
     */
    fun installHome(act: Activity?) {
        if (act == null) {
            return
        }
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) {
            install(content, UiKit.BG, false)
            return
        }
        val first = content.getChildAt(0)
        if (first is ScrollView) {
            // HomeUi 未接管（结构不符）：直接铺在唯一的滚动区上。
            install(first, UiKit.BG, false)
            return
        }
        if (first is ViewGroup) {
            install(first, UiKit.BG, false)
            for (i in 0 until first.childCount) {
                val c = first.getChildAt(i)
                if (c is ScrollView) {
                    c.setBackgroundColor(Color.TRANSPARENT)
                }
            }
            return
        }
        install(content, UiKit.BG, false)
    }

    /* ------------------------- 内部实现 ------------------------- */

    /** 主题色跟随主题重取值，普通色固定不变。 */
    private fun fallbackProvider(color: Int): () -> Int {
        if (color == UiKit.BG) {
            return { UiKit.BG }
        }
        if (color == UiKit.CARD) {
            return { UiKit.CARD }
        }
        return { color }
    }

    private fun applyNow(v: View, fallback: () -> Int, opaqueBase: Boolean) {
        try {
            val d = buildDrawable(v.context, fallback(), opaqueBase)
            v.background = d
        } catch (ignored: Throwable) {
            // 背景是装饰层：任何异常都不该影响功能。
        }
    }

    /** 把页内「页面级不透明底色容器」统一置透明，卡片（CARD 等）不动。 */
    private fun clearOpaquePageBgs(v: View) {
        if (v !is ViewGroup) {
            return
        }
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (isOpaqueBg(c, UiKit.BG)) {
                c.setBackgroundColor(Color.TRANSPARENT)
            }
            clearOpaquePageBgs(c)
        }
    }

    private fun isOpaqueBg(v: View, color: Int): Boolean {
        val d = v.background ?: return false
        val solid = when (d) {
            is ColorDrawable -> d.color
            is GradientDrawable -> try {
                d.color?.defaultColor
            } catch (ignored: Throwable) {
                null
            }
            else -> null
        }
        return solid != null && solid == color
    }

    private fun buildDrawable(c: Context, fallbackColor: Int, opaqueBase: Boolean): Drawable? {
        val p = path(c)
        if (p.isEmpty()) {
            return if (ContrastCore.alphaOf(fallbackColor) == 0) null else ColorDrawable(fallbackColor)
        }
        val bmp = if (p == cachedPath) cachedBmp else null
        if (bmp == null || bmp.isRecycled) {
            // 解码中 / 失败：先用纯色兜底，解码完成后 refreshAll 会换成图。
            return if (ContrastCore.alphaOf(fallbackColor) == 0) null else ColorDrawable(fallbackColor)
        }
        // 有图时必须垫一层不透明纯色底：否则图片透明度会透出下层 window 或第二张同图，形成叠影。
        val base = if (opaqueBase || ContrastCore.alphaOf(fallbackColor) == 0) UiKit.BG else fallbackColor
        val layers = ArrayList<Drawable>(3)
        layers.add(ColorDrawable(ContrastCore.withAlpha(base, 255)))
        val img = ChatBgDrawable(cachedBlurBmp ?: bmp, PetPrefs.bgMode(c))
        img.setAlpha((alphaPercent(c) * 255) / 100)
        layers.add(img)
        layers.add(ColorDrawable(scrimColor(c, alphaPercent(c))))
        return LayerDrawable(layers.toTypedArray())
    }

    /**
     * 确保图片已缓存。同一路径只解一次；解码失败不「永久记住失败」，下次 install 会重试；
     * 解码途中用户换了路径时，丢掉旧结果并立即去解当前路径（不漏新路径）。
     */
    private fun ensureLoaded(c: Context) {
        val p = path(c)
        if (p.isEmpty()) {
            synchronized(this) {
                cachedPath = ""
                cachedBmp = null
                cachedBlurBmp = null
                cachedRadius = -1
                cachedPixels = null
                cachedAvg = DEFAULT_AVG
                loadFailed = false
            }
            return
        }
        val r = blurRadius(c)
        if (p == cachedPath && r == cachedRadius && !loadFailed) {
            return
        }
        synchronized(this) {
            if (loading) {
                // 已有解码在跑：不重复起线程，解码结束的回调里会再对齐一次当前路径。
                return
            }
            loading = true
        }
        startLoad(c.applicationContext, p, r)
    }

    private fun startLoad(app: Context, p: String, r: Int) {
        synchronized(this) {
            cachedPath = p
            loadFailed = false
        }
        Thread({
            var bmp: Bitmap? = null
            var px: IntArray? = null
            var blur: Bitmap? = null
            try {
                // 背景图恒为 JPEG 不透明图（见 ImageStore.saveCropFrom），按 RGB_565 解码省约一半常驻内存。
                bmp = ImageStore.loadScaled(app, p, MAX_DIM, true)
                if (bmp != null && !bmp.isRecycled) {
                    // 采样必须基于原图：遮罩要按「用户真实看到的那张图」的明暗极值推导。
                    px = sample(bmp)
                    if (r > 0) {
                        // 【为什么在这里模糊】模糊是 O(像素) 的重活，放进 onDraw 会掉帧；
                        //   本线程本来就在解码，顺带算完一次缓存住，后续绘制直接复用。
                        val f = BgBlur.blur(bmp, r, app.resources.displayMetrics.density)
                        if (f !== bmp) {
                            blur = f
                        }
                    }
                }
            } catch (ignored: Throwable) {
                bmp = null
                px = null
                blur = null
            }
            val fBmp = bmp
            val fPx = px
            val fBlur = blur
            Handler(Looper.getMainLooper()).post {
                var stale: Boolean
                synchronized(this) {
                    loading = false
                    stale = path(app) != p
                    if (stale) {
                        // 期间换了路径：丢弃这次结果（还没被任何 Drawable 引用，可安全回收），
                        // 置空缓存路径以便立刻去解当前路径。
                        cachedPath = null
                        cachedBlurBmp = null
                        cachedRadius = -1
                        loadFailed = true
                    } else {
                        cachedBmp = fBmp
                        cachedBlurBmp = fBlur
                        cachedRadius = r
                        cachedPixels = fPx
                        cachedAvg = if (fPx != null) ContrastCore.averageColor(fPx, fPx.size) else DEFAULT_AVG
                        scrimKey = 0
                        // 失败（bmp 为空 / 采样为空）不永久缓存：清空路径，下次 install 重试。
                        loadFailed = fBmp == null || fPx == null
                        if (loadFailed) {
                            cachedPath = null
                        }
                    }
                }
                if (stale && fBmp != null && !fBmp.isRecycled) {
                    fBmp.recycle()
                }
                refreshAll()
                if (stale) {
                    // 立即去解当前路径，保证不漏新路径。
                    ensureLoaded(app)
                }
            }
        }, THREAD_NAME).start()
    }

    /** 把位图缩到 24×24 采样，返回过滤透明后的像素（alpha<16 的会在统计时跳过）。 */
    private fun sample(bmp: Bitmap): IntArray? {
        return try {
            val small = Bitmap.createScaledBitmap(bmp, SAMPLE, SAMPLE, true)
            val n = SAMPLE * SAMPLE
            val px = IntArray(n)
            small.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
            if (small !== bmp) {
                small.recycle()
            }
            px
        } catch (ignored: Throwable) {
            null
        }
    }
}
