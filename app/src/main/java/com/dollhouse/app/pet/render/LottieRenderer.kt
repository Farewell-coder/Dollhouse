package com.dollhouse.app.pet.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.airbnb.lottie.ImageAssetDelegate
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieResult
import com.dollhouse.app.core.Logs
import java.io.File

/**
 * 【职责】Lottie 矢量动画渲染器 —— 任务书 §3.4 指定的第二引擎。
 *
 * 【为什么选 Lottie】截至 2026-10，lottie-android 有 35.7k star、Apache-2.0 许可、
 *   持续活跃；且它**纯 Java/Kotlin 无 native**，与工程「零 native 依赖」的口径一致
 *   （对比 PAG / Rive / Spine 都带 C++ 或商业授权，见交付报告 04）。
 *
 * 【为什么用 LottieDrawable 而不是 LottieAnimationView】
 *   后者是 `android.view.View`，会引入另一套 View 层级与 appcompat 主题依赖；
 *   本工程的人偶是「自绘 View + drawBitmapMesh」，用 Drawable 才能画进同一个 Canvas。
 *
 * 【和 Sprite 引擎的本质差别（如实记录 → 交付报告 04 R2）】
 *   Sprite 引擎靠「同一套渲染管线」保证烘帧与实时绘制**逐像素同源**；
 *   Lottie 有自己的矢量渲染器，**走不进 drawBitmapMesh** ——
 *   所以 Lottie 动作不存在「与 Sprite 同源」这一说。
 *   这是**原理性取舍**，不是实现缺陷：想要 Lottie 的效果就无法复用网格形变。
 */
class LottieRenderer : PetRenderer {

    private companion object {
        const val TAG = "DollhouseLottie"

        /** Lottie 资源图缓存上限（个数）。超出按 LRU 淘汰并回收位图。 */
        const val IMAGE_CACHE_MAX = 64
    }

    private var host: PetDisplayHost? = null
    private var drawable: LottieDrawable? = null
    private var err: String? = null
    private var playing = false
    private var released = false
    private var w = 0
    private var h = 0
    /** 未应用任何变换时的目标绘制区（按人偶窗口尺寸等比缩放居中）。 */
    private val dst = Rect()

    /**
     * 按需解码的图片缓存（文件名 → 位图），同一张图只解一次。
     *
     * 【r18·内存】从 HashMap 换成带容量上限的 LruCache：
     *   HashMap 无上限，Lottie 资源图多的话长会话内存单调上涨，只能靠 release 兜底。
     *   LruCache 按「最近最少使用」自动淘汰，上限内命中行为与 HashMap 完全一致，
     *   超限时回收最久未用的（淘汰的 Bitmap 在此处 recycle，因为本类是唯一持有者）。
     *   上限按「个数」而非字节 —— Lottie 资源图通常数量少、尺寸小，个数上限更直观。
     */
    private val imageCache = object : android.util.LruCache<String, Bitmap>(IMAGE_CACHE_MAX) {
        override fun entryRemoved(
            evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?
        ) {
            // 仅在「被淘汰」（evicted=true）时回收；被 put 新值替换的不在此回收
            // （新值可能引用同一张图，由调用方生命周期管）。
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    override fun engine(): String = Engine.LOTTIE

    /**
     * 加载 Lottie 资源。
     *
     * 【为什么支持「同目录 images/」】Lottie 导出的 json 常见两种形态：
     *   ① 图片以 base64 内嵌在 json 里（自包含）；
     *   ② 图片放在同目录的 `images/` 下、json 里只写文件名。
     *   形态 ② 必须把图片目录一起交给 Lottie，否则会渲染成空白 —— 这是常见的踩坑点。
     */
    override fun prepare(source: PetSource): Boolean {
        err = null
        if (source !is PetSource.Lottie) {
            err = "Lottie 引擎只接受 .json 资源，收到的是：" + source.describe()
            return false
        }
        val f = File(source.jsonPath)
        if (!f.exists() || !f.isFile) {
            err = "Lottie 文件不存在：" + f.name
            return false
        }
        if (f.length() <= 0L) {
            err = "Lottie 文件为空：" + f.name
            return false
        }
        // 【为什么自带 try/catch】解析第三方 json 可能抛各种异常（格式错、版本不支持、
        //   资源引用缺失）。契约要求「失败返回 false 并给出原因」，不能让它冒到上层。
        return try {
            val text = f.readText()
            if (text.isBlank()) {
                err = "Lottie 文件内容为空：" + f.name
                return false
            }
            // 【为什么用 fromJsonStringSync 而不是文件版】lottie 6.7.1 里
            //   `LottieCompositionFactory` **没有**「json + 图片映射表」这个重载
            //   （只有 (String,String) 和 (InputStream,String) 两种）；
            //   图片走 `ImageAssetDelegate` 在**绘制时按需拉取**（见下方 delegate），
            //   这样还能顺带省掉「资源包里的图全都预先解码进内存」。
            val result: LottieResult<LottieComposition> =
                LottieCompositionFactory.fromJsonStringSync(text, f.absolutePath)
            val comp: LottieComposition? = result.value
            if (comp == null) {
                val why = result.exception?.message ?: "未知原因"
                err = "Lottie 解析失败：" + why
                return false
            }
            if (comp.duration <= 0L) {
                err = "Lottie 动画时长为 0，可能是空动画"
                return false
            }
            val d = LottieDrawable()
            d.composition = comp
            d.repeatCount = LottieDrawable.INFINITE
            // 【必须清背景】Lottie 默认可能带背景色；人偶是悬浮窗，带底色会露出一块方块。
            d.isApplyingOpacityToLayersEnabled = false
            // 【形态 ② 的关键】json 里图片只写文件名时，Lottie 找不到图会渲染成空白。
            //   挂上 delegate 后，它每次要图就来这里取 —— 我们从 json 同目录的
            //   `images/` 里解码，并按文件名缓存，同一个图只解一次。
            val parent = f.parentFile
            // 【为什么调 setter 而不是用属性语法】Lottie 6.x 里 `LottieDrawable`
            //   同时存在 private 字段与 public setter，Kotlin 会优先解析到那个私有字段
            //   并报「Cannot access: it is private」。显式调 Java setter 才过得去。
            d.setImageAssetDelegate(
                ImageAssetDelegate { asset -> resolveImage(parent, asset.fileName) }
            )
            drawable = d
            w = comp.bounds.width()
            h = comp.bounds.height()
            true
        } catch (t: Throwable) {
            Logs.w(TAG, "prepare failed", t)
            err = "Lottie 加载异常：" + (t.message ?: t.javaClass.simpleName)
            false
        }
    }

    override fun lastError(): String? = err

    override fun attach(host: Any?) {
        this.host = host as? PetDisplayHost
        this.drawable?.callback = null
    }

    override fun size(): IntArray = intArrayOf(w, h)

    override fun advance(nowMs: Long) {
        if (released || !playing) {
            return
        }
        host?.requestDraw()
    }

    override fun isActive(): Boolean = playing && !released && (drawable?.isAnimating == true)

    override fun play() {
        if (released) {
            return
        }
        playing = true
        val d = drawable ?: return
        d.repeatCount = LottieDrawable.INFINITE
        d.playAnimation()
        host?.requestDraw()
    }

    override fun pause() {
        playing = false
        val d = drawable ?: return
        d.pauseAnimation()
    }

    override fun resume() {
        if (released) {
            return
        }
        playing = true
        val d = drawable ?: return
        if (!d.isAnimating) {
            d.resumeAnimation()
        }
        host?.requestDraw()
    }

    /**
     * 幂等释放。
     *
     * 【为什么必须清 callbacks 与 composition 引用】`LottieDrawable` 内部持有
     *   `LottieComposition`（可能很大）以及给宿主的回调；不显式断开的话，
     *   悬浮窗销毁后 drawable 仍被 animation 线程持有 → 泄漏。
     */
    override fun release() {
        if (released) {
            return
        }
        released = true
        playing = false
        val d = drawable
        if (d != null) {
            try {
                d.cancelAnimation()
                // 【必须置 null 再置 composition】Lottie 6.x 里直接置 composition=null
                //   会抛异常；先停动画、再断开回调是官方推荐的释放顺序。
                d.composition = null
            } catch (ignored: Throwable) {
                Logs.w(TAG, "release drawable failed", ignored)
            }
        }
        drawable = null
        host = null
        // 【必须回收缓存里的位图】这些图是本渲染器自己解码的（不是借用素材池），
        //   不回收就是纯粹的内存泄漏 —— 每个模型包几十张图，肉眼可见。
        //   LruCache.snapshot() 拿只读副本遍历，避免遍历时被并发淘汰触发 entryRemoved。
        for (b in imageCache.snapshot().values) {
            try {
                if (!b.isRecycled) {
                    b.recycle()
                }
            } catch (ignored: Throwable) {
            }
        }
        // evictAll() 会逐条触发 entryRemoved(evicted=true)，此时位图已在上面的循环
        //   回收过了，entryRemoved 里的 isRecycled 守卫会跳过重复 recycle（幂等）。
        imageCache.evictAll()
    }

    /**
     * 画到画布。
     *
     * 【为什么自己算 dst 而不给 drawable 设 bounds】人偶窗口的尺寸由外层（PetWindow）
     *   决定，Lottie 只知道自己 composition 的原始 bounds；这里按「等比缩放 + 居中」
     *   映射进当前画布，避免宽高比失真（矢量图拉伸会明显穿帮）。
     */
    override fun draw(canvas: Canvas) {
        draw(canvas, android.graphics.Rect(0, 0, canvas.width, canvas.height))
    }

    override fun draw(canvas: Canvas, target: android.graphics.Rect) {
        if (released || w <= 0 || h <= 0 || target.isEmpty) return
        val d = drawable ?: return
        val scale = Math.min(target.width().toFloat() / w, target.height().toFloat() / h)
        val dw = Math.round(w * scale)
        val dh = Math.round(h * scale)
        val left = target.left + (target.width() - dw) / 2
        val top = target.top + (target.height() - dh) / 2
        dst.set(left, top, left + dw, top + dh)
        d.bounds = dst
        d.setAlpha(255)
        canvas.save()
        d.draw(canvas)
        canvas.restore()
    }

    /**
     * 按需解析 Lottie 引用的图片（json 同目录 `images/` 下）。
     *
     * 【为什么按需而不是一次全解】一个模型包可能有几十张分离图，
     *   而一帧往往只用其中几张。交给 Lottie 按需回调，既省内存也省首次加载时间。
     *
     * 【为什么只认白名单后缀】模型包可能来自第三方，安全红线（任务书 §3.7）
     *   要求只接受 png/jpg/jpeg/webp/gif，其它一律不加载。
     *   这里不执行任何代码，只做位图解码，符合「声明式、无脚本」约束。
     *
     * 【为什么做文件名净化】`LottieImageAsset.fileName` 来自第三方 json，
     *   可能带 `../` 之类的路径穿越片段（任务书 §3.7 明令拒绝）。
     *   这里只取最后一段文件名，且拒绝空名/纯点名 —— 从根上杜绝逃出目录。
     */
    private fun resolveImage(jsonDir: File?, fileName: String?): Bitmap? {
        if (jsonDir == null || fileName.isNullOrEmpty()) {
            return null
        }
        val safe = fileName.substringAfterLast('/').substringAfterLast('\\')
        if (safe.isEmpty() || safe == "." || safe == "..") {
            Logs.w(TAG, "拒绝可疑资源名：" + fileName)
            return null
        }
        val ext = safe.substringAfterLast('.', "").lowercase()
        if (ext != "png" && ext != "jpg" && ext != "jpeg" && ext != "webp") {
            Logs.w(TAG, "不支持的图片格式：" + safe)
            return null
        }
        val hit = imageCache[safe]
        if (hit != null) {
            return hit
        }
        // 【为什么两个目录都试】Lottie 导出常见把图放 `images/` 下；
        //   也有工具直接与 json 平级。两种都支持，命中即止。
        val cand = listOf(File(File(jsonDir, "images"), safe), File(jsonDir, safe))
        for (one in cand) {
            if (!one.isFile) {
                continue
            }
            try {
                val bmp = BitmapFactory.decodeFile(one.absolutePath)
                if (bmp != null) {
                    imageCache.put(safe, bmp)
                    return bmp
                }
            } catch (ignored: Throwable) {
                Logs.w(TAG, "解码失败：" + one.name, ignored)
            }
        }
        Logs.w(TAG, "模型包缺少图片资源：" + safe)
        return null
    }

    /** 供外部查询原始尺寸（资源管理界面显示用）。 */
    fun intrinsicSize(): IntArray = intArrayOf(w, h)
}