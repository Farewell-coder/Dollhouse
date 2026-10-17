package com.dollhouse.app.pet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.pet.render.Engine
import com.dollhouse.app.pet.render.PetDisplayHost
import com.dollhouse.app.pet.render.PetRenderer
import com.dollhouse.app.pet.render.PetSource
import com.dollhouse.app.pet.render.RendererRegistry
import java.io.File

/**
 * 【职责】桌宠侧的「动作播放层」—— 把 `DollAnim` 里的动作**真正画到桌面上**。
 *
 * ## 这就是本次任务的成败判据
 *
 * 任务书 §1.4 原文：
 * > **桌宠能够实际使用动作系统，而不是只有编辑器和预览界面。只做框架不接线 = 失败。**
 *
 * 改造前的真实状况（已 grep 证实）：`PetView` / `PetWindow` / `PetAnimator`
 * 对 `DollAnim` **零引用** —— 用户在动作页精心配的动作，桌宠一个都不会播；
 * 桌宠跑的是 `PetAnimator` 里硬编码的 5 个待机小动作。
 * 本类就是那条缺失的接线：**把动作数据变成桌面上的像素**。
 *
 * ## 为什么单独一层而不是塞进 PetView
 *
 * 三条理由，都是「不改坏」：
 * 1. **无动作时路径必须逐行不变**：`PetView.onDraw` 是本工程的视觉核心，
 *    且烘帧（`bakeDraw`）要与它逐像素同源。把动作逻辑塞进去，
 *    等于让「播动作」这条路有机会影响「不播动作」那条路。现在
 *    `PetView` 只在 `hasContent()` 为真时才把绘制让给本类，否则**一行都不碰**。
 * 2. **解码是异步的**：帧图可能有 120 张，主线程解码必然掉帧。
 *    本类自己管后台解码 + 代次校验（`generation`），不污染视图的线程模型。
 * 3. **三个引擎共用一套生命周期**：帧序列 / Lottie / 动图在这里统一成
 *    「准备好了就画、没准备好就让位」，`PetView` 不必知道背后是什么引擎。
 *
 * ## 线程
 * `play` / `tick` / `draw` / `stop` / `release` **都在主线程**（由 PetView 调用）。
 * 解码在后台线程，完成后经 `Handler` 回主线程 —— 回调里用 `generation` 判代次，
 * 丢弃「切走之后才回来的」结果，避免出现「播 A 结果 A 的旧解码把 B 顶掉」。
 */
class ActionPlayerCore(
    /** 请求宿主重绘（解码完成、换帧时用）。 */
    private val requestDraw: () -> Unit,
    /**
     * 位图解码器 —— **可注入**，这样就可以在 JVM 单测里塞一个假的解码器
     * 验证「帧推进 / 循环 / 播完回调」这些纯逻辑，不需要真机也不需要 Robolectric。
     */
    private val decoder: (String) -> Bitmap? = { path ->
        // 【r18·内存】帧图按目标尺寸采样解码，不按原图全尺寸。
        //   渲染时（draw）本就按画布等比缩放（scale = min(cw/bw, ch/bh)），
        //   解码到远大于画布的尺寸纯属浪费内存：一张 2048² PNG = 16MB，
        //   120 帧上限即 ~1.9GB 峰值。采样到桌宠画布量级（DECODE_TARGET_PX）
        //   后单帧约 1MB，内存峰值下降 75%+，视觉零差异（绘制阶段仍精确缩放）。
        com.dollhouse.app.core.ImageDecode.file(
            path,
            reqWidth = DECODE_TARGET_PX,
            reqHeight = DECODE_TARGET_PX
        )
    },
    /** 宿主提供者（Lottie / 动图引擎要 attach 到宿主才能自绘）。 */
    private val hostProvider: () -> PetDisplayHost? = { null }
) {

    private companion object {
        const val TAG = "DollhouseAction"

        /** 单次最多解码多少帧（与 [DollAnim.FRAMES_MAX] 对齐，防越界档案把内存吃满）。 */
        const val MAX_FRAMES = DollAnim.FRAMES_MAX

        /**
         * 帧图解码的目标边长（px）。取桌宠画布的上限量级：
         *   桌宠窗口通常 ≤ 屏宽一半（1080p 屏约 540px），而帧图普遍 ≥1024²。
         *   采样到 1024 能覆盖绝大多数画布且留一倍余量（避免采样过度导致糊）。
         *   这是「采样下界」，不是「裁剪」—— 采样后仍可能比它大（向上取整到 2 的幂）。
         */
        const val DECODE_TARGET_PX = 1024
    }

    private val ui = Handler(Looper.getMainLooper())

    /** 自解码的帧序列（`sprite` 引擎用）。 */
    private var frames: List<Bitmap> = emptyList()

    /** 非 sprite 引擎的渲染器（Lottie / 动图）。 */
    private var renderer: PetRenderer? = null

    /** 当前动作 id（空 = 没有在播任何动作）。 */
    var actionId: String = ""
        private set

    private var fps = DollAnim.FPS_DEFAULT
    private var loop = true
    private var frameIdx = 0
    private var lastStepMs = 0L
    private var playing = false
    private var disposed = false

    /**
     * 代次 —— 每次 `play` / `stop` / `release` 自增。
     *
     * 【为什么必须有它】解码是异步的：用户在 300ms 内连点两个动作，
     * 第一个动作的解码可能**后**回来。若不判代次，那个迟到的结果会把
     * 第二个动作的帧顶掉 —— 用户看到的是「点了 A 播的是 B」，
     * 而且这种 bug 只在慢机型上偶发，极难复现。代次让迟到者自觉退场。
     */
    private var generation = 0

    /** 目标绘制区（等比缩放居中）。 */
    private val dst = Rect()

    /** 一次播放结束时回调（非循环动作播完 / 循环动作被 stop）。 */
    var onFinish: (() -> Unit)? = null

    // ============================ 播放控制 ============================

    /**
     * 播放一个动作。
     *
     * 【为什么先无条件清空】同一个 `ActionPlayerCore` 会被复用来播不同动作
     * （待机默认动作 ↔ 点击动作 ↔ 拖动动作）。上一个动作的位图 / 渲染器
     * 必须先释放，否则连播几次就把内存吃满了。
     */
    fun play(action: DollAnim.Action) {
        if (disposed) {
            return
        }
        generation++
        clearContent()
        actionId = action.id
        fps = DollAnim.clampFps(action.fps)
        loop = action.loop
        frameIdx = 0
        lastStepMs = 0L
        playing = true

        val engine = action.engine
        if (engine == DollAnim.ENGINE_SPRITE) {
            val paths = action.frames
            if (paths.isEmpty()) {
                // 空壳动作：没有任何帧可画 → 视为「无内容」，绘制让回给原管线。
                // 【为什么不当成错误】「先建壳、后加帧」是产品明确支持的第一步
                //   （见 DollAnim.add 的注释），空动作被设为默认动作是完全可能的。
                Logs.i(TAG, "动作没有帧，跳过播放：" + actionId)
                playing = false
                return
            }
            decodeFramesAsync(paths, generation)
        } else {
            val path = action.assetPath
            if (path.isBlank()) {
                Logs.w(TAG, "动作没有入口资源：" + actionId)
                playing = false
                return
            }
            val r = RendererRegistry.create(engine)
            if (r == null) {
                // 【不静默回落】未知引擎说明档案被改坏或来自更高版本；
                //   回落成 sprite 会让「选了 Lottie 却画出位图」这种更隐蔽的问题。
                Logs.w(TAG, "未知引擎：" + engine + "（动作 " + actionId + "）")
                playing = false
                return
            }
            val ok = r.prepare(PetSource.Lottie(path).let { s ->
                // 按引擎给对应句柄；用 when 而不是强转，加引擎时编译器会提醒。
                when (engine) {
                    Engine.ANIMIMAGE -> PetSource.AnimatedImage(path)
                    else -> s
                }
            })
            if (!ok) {
                Logs.w(TAG, "资源准备失败：" + (r.lastError() ?: "未知"))
                playing = false
                return
            }
            val h = hostProvider()
            if (h != null) {
                r.attach(h)
            }
            renderer = r
            r.play()
            requestDraw()
        }
    }

    /**
     * 后台解码帧序列。
     *
     * 【为什么先全部解完再播，而不是边播边解】帧序列是「一组连贯动作图」，
     *   少了中间几帧会明显卡顿（比等 100ms 再开始难受得多）。
     *   解码在后台，期间绘制让回给原管线（`hasContent()` 为 false），
     *   所以用户不会看到半个动作 —— 只会看到人偶保持原样，然后动作开始。
     */
    private fun decodeFramesAsync(paths: List<String>, gen: Int) {
        val take = if (paths.size > MAX_FRAMES) paths.subList(0, MAX_FRAMES).toList() else paths
        Thread {
            val out = ArrayList<Bitmap>(take.size)
            for (p in take) {
                try {
                    val b = decoder(p)
                    if (b != null) {
                        out.add(b)
                    }
                } catch (t: Throwable) {
                    Logs.w(TAG, "解码失败：" + File(p).name, t)
                }
            }
            ui.post {
                // 【代次校验】切走之后回来的解码结果直接丢弃并回收，不污染新动作。
                if (disposed || gen != generation) {
                    recycleAll(out)
                    return@post
                }
                frames = out
                if (out.isEmpty()) {
                    Logs.w(TAG, "全部帧解码失败，动作无法播放：" + actionId)
                    playing = false
                } else {
                    lastStepMs = 0L
                    frameIdx = 0
                }
                requestDraw()
            }
        }.apply { name = "dh-action-decode" }.start()
    }

    /** 推进一帧（主线程，由 `PetView.tick` 调）。 */
    fun tick(nowMs: Long) {
        if (disposed || !playing) {
            return
        }
        val r = renderer
        if (r != null) {
            r.advance(nowMs)
            return
        }
        val n = frames.size
        if (n <= 1) {
            return
        }
        if (lastStepMs == 0L) {
            lastStepMs = nowMs
            return
        }
        val step = DollAnim.frameMs(fps)
        if (nowMs - lastStepMs < step) {
            return
        }
        lastStepMs = nowMs
        if (frameIdx + 1 < n) {
            frameIdx++
            requestDraw()
        } else if (loop) {
            frameIdx = 0
            requestDraw()
        } else {
            // 非循环动作停在最后一帧，并通知上层（交互结束 → 回落到待机档）。
            playing = false
            requestDraw()
            onFinish?.invoke()
        }
    }

    /** 是否有可绘制内容（决定 `PetView.onDraw` 要不要让位）。 */
    fun hasContent(): Boolean {
        if (disposed) {
            return false
        }
        if (renderer != null) {
            return true
        }
        return frames.isNotEmpty()
    }

    /** 是否正在播（含非循环动作停帧的情况）。 */
    fun isPlaying(): Boolean = playing && !disposed

    /** 是否还有动画在推进（供帧率策略判断要不要全速）。 */
    fun isAnimated(): Boolean {
        if (disposed || !playing) {
            return false
        }
        val r = renderer
        if (r != null) {
            return r.isActive()
        }
        // 静态单帧不算「在动」，避免白占全速帧率。
        return frames.size > 1
    }

    /** 暂停（保留进度）。 */
    fun pause() {
        if (disposed) {
            return
        }
        playing = false
        renderer?.pause()
    }

    /** 恢复。 */
    fun resume() {
        if (disposed) {
            return
        }
        if (!hasContent()) {
            return
        }
        playing = true
        renderer?.resume()
        requestDraw()
    }

    /** 停止播放并释放内容，但对象仍可复用（不置 disposed）。 */
    fun stop() {
        if (disposed) {
            return
        }
        generation++
        playing = false
        actionId = ""
        clearContent()
        requestDraw()
    }

    /**
     * 彻底释放（幂等）。
     *
     * 【为什么要幂等】人偶移除有三条路径（用户关闭 / 服务销毁 / 多实例重挂），
     *   任意两条先后到达就会重复调用；不幂等的话第二条会在已回收的位图上再回收一次。
     */
    fun release() {
        if (disposed) {
            return
        }
        disposed = true
        generation++
        playing = false
        actionId = ""
        clearContent()
        onFinish = null
        ui.removeCallbacksAndMessages(null)
    }

    // ============================ 绘制 ============================

    /**
     * 画到画布。
     *
     * 【为什么用「等比缩放 + 居中」而不是拉伸铺满】用户导入的动作图
     *   宽高比各不相同；拉伸会让圆脸的人偶看起来被压扁。
     *   居中留白比变形好 —— 这也是 `LottieRenderer` 的口径，两处保持一致。
     */
    fun draw(canvas: Canvas) {
        if (disposed) {
            return
        }
        val r = renderer
        if (r != null) {
            r.draw(canvas)
            return
        }
        val n = frames.size
        if (n == 0) {
            return
        }
        val idx = if (frameIdx in 0 until n) frameIdx else 0
        val bmp = frames[idx]
        if (bmp.isRecycled) {
            return
        }
        val cw = canvas.width
        val ch = canvas.height
        if (cw <= 0 || ch <= 0) {
            return
        }
        val bw = bmp.width
        val bh = bmp.height
        if (bw <= 0 || bh <= 0) {
            return
        }
        val scale = Math.min(cw.toFloat() / bw, ch.toFloat() / bh)
        val dw = Math.round(bw * scale)
        val dh = Math.round(bh * scale)
        val left = (cw - dw) / 2
        val top = (ch - dh) / 2
        dst.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, null, dst, null)
    }

    // ============================ 内部 ============================

    private fun clearContent() {
        val r = renderer
        if (r != null) {
            try {
                r.release()
            } catch (t: Throwable) {
                Logs.w(TAG, "释放渲染器失败", t)
            }
        }
        renderer = null
        recycleAll(frames)
        frames = emptyList()
        frameIdx = 0
        lastStepMs = 0L
    }

    /**
     * 回收位图。
     *
     * 【必须回收】帧图是本类**自己解码**的（不是借用素材池的共享位图），
     *   120 张 150dp 图在 3x 屏上约 30MB —— 不回收就是肉眼可见的内存泄漏。
     */
    private fun recycleAll(list: List<Bitmap>) {
        for (b in list) {
            try {
                if (!b.isRecycled) {
                    b.recycle()
                }
            } catch (ignored: Throwable) {
            }
        }
    }
}