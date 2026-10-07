package com.dollhouse.app.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.animation.Interpolator

/**
 * 弹簧预置与驱动器。
 *
 * 【来源】预置参数照搬 morphicons 的 SPRING_PRESETS（MIT），ζ = c / (2√k)：
 *   smooth  ζ=1.00  临界阻尼，无过冲
 *   snappy  ζ=0.73  快，轻微过冲
 *   bouncy  ζ=0.40  活泼，明显回弹
 *
 * 【时钟源】不新起线程、不加常驻回调：用 ValueAnimator 借 vsync，
 *   每帧从 SystemClock 取真实 dt 喂给 Spring。duration 只作安全上限，
 *   弹簧自收敛会提前 cancel，因此手感与设备帧率无关。
 *
 * 【可取消】drive() 返回 ValueAnimator，调用方可按工程既有约定在
 *   onDestroy / 重排前 cancel（参考 PetWindowController 对 snapAnim 的处理）。
 */
object Springs {

    /** 安全上限：预置里最慢的 bouncy 也在 1s 内收敛，2s 足够兜底。 */
    private const val MAX_MS = 2000L

    // ---- 预置 ----

    /** ζ=1.00，临界阻尼不过冲。适合高度、透明度这类「不能穿帮」的量。 */
    @JvmStatic
    fun smooth(): Spring {
        return Spring(170.0, 26.0)
    }

    /** ζ=0.73，快且带轻微过冲。默认手感，适合位移、缩放、旋转。 */
    @JvmStatic
    fun snappy(): Spring {
        return Spring(420.0, 30.0)
    }

    /** ζ=0.40，活泼回弹。仅用于按压松手这类「弹一下」的场合。 */
    @JvmStatic
    fun bouncy(): Spring {
        return Spring(300.0, 14.0)
    }

    /** 回调：progress 已经过弹簧整形，0 → 1。 */
    interface Listener {
        /** 每帧回调，progress ∈ [0, 1]（bouncy 时会短暂 > 1）。 */
        fun onUpdate(progress: Float)

        /** 收敛或被打断时回调一次，用于收尾（置终态、清理引用）。 */
        fun onEnd()
    }

    /**
     * 用弹簧驱动一次 0 → 1。
     *
     * @param spring   预置的弹簧实例（内部会先 start()）
     * @param listener 逐帧回调
     * @return 可取消的驱动句柄
     */
    @JvmStatic
    fun drive(spring: Spring?, listener: Listener?): ValueAnimator? {
        if (spring == null || listener == null) {
            return null
        }
        spring.start()
        val t0 = SystemClock.uptimeMillis()
        var last = t0
        var ended = false

        /** 保证 onEnd 只回调一次，并把进度钉到终态。 */
        fun finish() {
            if (ended) {
                return
            }
            ended = true
            spring.settle()
            listener.onUpdate(1.0f)
            listener.onEnd()
        }

        val va = ValueAnimator.ofFloat(0.0f, 1.0f)
        va.duration = MAX_MS
        // 不设 Interpolator：进度由 Spring 自己积分，ValueAnimator 只当时钟。
        va.interpolator = Interpolator { input -> input }
        va.addUpdateListener { an ->
            val now = SystemClock.uptimeMillis()
            val dt = (now - last) / 1000.0
            last = now
            val settled = spring.step(dt)
            listener.onUpdate(spring.x.toFloat())
            if (settled) {
                an.cancel()
            }
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(an: Animator) {
                finish()
            }

            override fun onAnimationEnd(an: Animator) {
                finish()
            }
        })
        va.start()
        return va
    }

    /**
     * 工具：线性映射 + 弹簧进度，得到当前值。
     * 例：from=0, to=100, progress=0.5 → 50。
     */
    @JvmStatic
    fun lerp(from: Float, to: Float, progress: Float): Float {
        return from + (to - from) * progress
    }

    /** 工具：int 版 lerp（带四舍五入，避免反复截断产生的抖动）。 */
    @JvmStatic
    fun lerpInt(from: Int, to: Int, progress: Float): Int {
        return Math.round(from + (to - from) * progress)
    }
}
