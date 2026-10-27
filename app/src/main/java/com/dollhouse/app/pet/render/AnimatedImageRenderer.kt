package com.dollhouse.app.pet.render

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.dollhouse.app.core.Logs
import java.io.File

/**
 * 【职责】系统原生动图渲染器（GIF / WebP / APNG）—— 任务书 §3.4 的第三引擎。
 *
 * 【为什么选「系统原生」而不是第三方】任务书 §2.9 的结论：**零新增依赖、零 native**。
 *   Android 平台自带两套解码：
 *   · API 28+：`AnimatedImageDrawable`（支持 GIF / WebP / APNG，硬件加速友好）；
 *   · API 24–27：`Movie`（只支持 GIF，但工程 minSdk 24 必须兜住这段）。
 *   两级降级都在本类内完成，调用方无感。
 *
 * 【与 Lottie 的定位差别】Lottie 是**矢量**（随便放大不糊），本引擎是**位图序列**
 *   （放大就糊）。两者的取舍写进交付报告 04，用户按素材形态选。
 */
class AnimatedImageRenderer : PetRenderer {

    private companion object {
        const val TAG = "DollhouseAnimImage"
    }

    private var host: PetDisplayHost? = null
    private var err: String? = null
    private var playing = false
    private var released = false
    private var w = 0
    private var h = 0

    /** API 28+ 的动图 drawable。 */
    private var animDrawable: Drawable? = null
    /** API 24–27 的 Movie 解码器（GIF 兜底）。 */
    private var movie: android.graphics.Movie? = null
    /** Movie 路径的当前时间基准（毫秒）。 */
    private var movieStartMs = 0L
    /** 动图播放一次的时长（用于循环取模）。 */
    private var movieDurationMs = 0
    private val dst = Rect()
    private val fullCanvasDst = Rect()
    private val sizeBuffer = IntArray(2)

    override fun engine(): String = Engine.ANIMIMAGE

    override fun prepare(source: PetSource): Boolean {
        err = null
        if (source !is PetSource.AnimatedImage) {
            err = "动图引擎只接受动图文件，收到的是：" + source.describe()
            return false
        }
        val f = File(source.filePath)
        if (!f.exists() || !f.isFile) {
            err = "动图文件不存在：" + f.name
            return false
        }
        if (f.length() <= 0L) {
            err = "动图文件为空：" + f.name
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                prepareAnimatedDrawable(f)
            } else {
                prepareMovie(f)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "prepare failed", t)
            err = "动图加载异常：" + (t.message ?: t.javaClass.simpleName)
            false
        }
    }

    /** API 28+：AnimatedImageDrawable（最完整，支持 APNG/WebP）。 */
    private fun prepareAnimatedDrawable(f: File): Boolean {
        val d = Drawable.createFromPath(f.absolutePath)
        if (d == null) {
            err = "系统无法解码这个动图（格式不支持或文件损坏）"
            return false
        }
        if (d !is AnimatedImageDrawable) {
            // 静态图（PNG/JPG）会落到这里 —— 对「动图」这个入口来说属于格式不符。
            err = "这个文件不是动图（系统把它当静态图打开了）"
            return false
        }
        if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) {
            err = "动图尺寸非法：" + d.intrinsicWidth + "×" + d.intrinsicHeight
            return false
        }
        // 无限循环：人偶的动作应当是持续播放的。
        d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        animDrawable = d
        w = d.intrinsicWidth
        h = d.intrinsicHeight
        return true
    }

    /** API 24–27：Movie（只支持 GIF，够用且零依赖）。 */
    private fun prepareMovie(f: File): Boolean {
        val m = android.graphics.Movie.decodeFile(f.absolutePath)
        if (m == null) {
            err = "系统无法解码这个 GIF（可能是 WebP/APNG，需要 Android 9 及以上）"
            return false
        }
        if (m.width() <= 0 || m.height() <= 0) {
            err = "GIF 尺寸非法：" + m.width() + "×" + m.height()
            return false
        }
        movie = m
        movieDurationMs = m.duration().coerceAtLeast(1)
        w = m.width()
        h = m.height()
        return true
    }

    override fun lastError(): String? = err

    override fun attach(host: Any?) {
        this.host = host as? PetDisplayHost
    }

    override fun size(): IntArray = intArrayOf(w, h)

    override fun advance(nowMs: Long) {
        if (released || !playing) {
            return
        }
        // Movie 路径要自己按时间推进（Movie 不像 AnimatedImageDrawable 自带时钟）。
        if (movie != null) {
            if (movieStartMs == 0L) {
                movieStartMs = nowMs
            }
            val elapsed = ((nowMs - movieStartMs) % movieDurationMs).toInt()
            movie?.setTime(elapsed)
        }
        host?.requestDraw()
    }

    override fun isActive(): Boolean = playing && !released

    override fun play() {
        if (released) {
            return
        }
        playing = true
        movieStartMs = 0L
        val d = animDrawable
        if (d is android.graphics.drawable.Animatable) {
            (d as Animatable).start()
        }
        host?.requestDraw()
    }

    override fun pause() {
        playing = false
        val d = animDrawable
        if (d is android.graphics.drawable.Animatable) {
            (d as Animatable).stop()
        }
    }

    override fun resume() {
        if (released) {
            return
        }
        play()
    }

    /** 幂等释放。**只放开自己的引用**，不 recycle 任何位图（系统 drawable 自管）。 */
    override fun release() {
        if (released) {
            return
        }
        released = true
        playing = false
        val d = animDrawable
        if (d is android.graphics.drawable.Animatable) {
            try {
                (d as Animatable).stop()
            } catch (ignored: Throwable) {
                Logs.w(TAG, "stop failed", ignored)
            }
        }
        animDrawable = null
        movie = null
        host = null
    }

    override fun draw(canvas: Canvas) {
        fullCanvasDst.set(0, 0, canvas.width, canvas.height)
        draw(canvas, fullCanvasDst)
    }

    override fun draw(canvas: Canvas, target: android.graphics.Rect) {
        if (released || w <= 0 || h <= 0 || target.isEmpty) return
        val scale = Math.min(target.width().toFloat() / w, target.height().toFloat() / h)
        val dw = Math.round(w * scale)
        val dh = Math.round(h * scale)
        val left = target.left + (target.width() - dw) / 2
        val top = target.top + (target.height() - dh) / 2
        dst.set(left, top, left + dw, top + dh)

        val d = animDrawable
        if (d != null) {
            d.bounds = dst
            d.draw(canvas)
            return
        }
        val m = movie
        if (m != null) {
            canvas.save()
            canvas.scale(scale, scale)
            m.draw(canvas, left / scale, top / scale)
            canvas.restore()
        }
    }

    /** 供外部查询原始尺寸（资源管理界面显示用）。 */
    fun intrinsicSize(): IntArray = size()

    /**
     * 占位保留：`Animatable2` 在 API 28+ 用于注册动画回调。
     * 本类当前不需要回调（无限循环 + 由帧循环驱动重绘），故意不注册 —— 少一个引用少一处泄漏。
     */
    @Suppress("unused")
    private val animatable2Marker: Class<Animatable2>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Animatable2::class.java
    } else {
        null
    }
}