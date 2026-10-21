package com.dollhouse.app.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.animation.Interpolator

/**
 * 弹簧预置与驱动器。
 *
 * 【预置】按阻尼比 ζ = c / (2√k) 分三档：
 *   smooth  ζ=1.00  临界阻尼，无过冲
 *   snappy  ζ=0.73  快，轻微过冲
 *   bouncy  ζ=0.40  活泼，明显回弹
 *
 * 【时钟源】不新起线程、不加常驻回调：用 ValueAnimator 借 vsync，
 *   每帧从 SystemClock 取真实 dt 喂给 Spring。duration 只作安全上限，
 *   弹簧自收敛会提前 cancel，因此手感与设备帧率无关。
 *
 * 【可取消】[drive] 返回 [Handle]，调用方可按工程既有约定在
 *   onDestroy / 重排前 [Handle.cancel]。不取消也不会泄漏：驱动随视图走完即自行结束。
 *
 * 【本次修复的两个根因】改造前 [drive] 用 `AnimatorListenerAdapter.onAnimationCancel
 *   → finish(true)`，把「自然收敛时的 an.cancel()」和「外部主动取消」混成一件事：
 *   ① 被外部取消时会把进度**强行钉到终态 1**，于是「按下抬起再按下」这类连击
 *      会看到元素先跳到终点再跳回起点 —— 用户描述为「闪一下」「抖一下」；
 *   ② onEnd 被钉死的终态覆盖了调用方在 onEnd 里想写的目标值，出现「回弹方向错误」。
 *   现在用 [Handle] 明确区分「自然收敛」与「被取消」：
 *   · 自然收敛 → onUpdate(1f) + onEnd()，终态精确；
 *   · 被取消   → 只回调 onCancelled()，**不再替调用方写任何值**，由调用方自己决定归位。
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

        /** **自然收敛**时回调一次，用于收尾（置终态）。被取消时不会调用。 */
        fun onEnd()

        /**
         * **被外部取消**时回调一次。
         *
         * 默认实现什么都不做 —— 视图留在被取消那一刻的位置，等下一次动画接手。
         * 需要「取消即归位」的调用方（例如开关轨道色）自行覆写。
         */
        fun onCancelled() {}
    }

    /**
     * 驱动句柄 —— 比裸 [ValueAnimator] 安全，因为 cancel 时不会误触发 onEnd。
     *
     * 【为什么不用 ValueAnimator 直接返回】外部 cancel 会被 AnimatorListenerAdapter
     *   的 onAnimationCancel 收到，而自然收敛内部也是 an.cancel()，两者无法在
     *   Animator 层面区分。用本类包一层，由 [drive] 自己记录 ended 状态即可区分。
     */
    class Handle internal constructor() {
        internal var animator: ValueAnimator? = null
        internal var ended = false

        /**
         * 是否还在跑。
         *
         * 【为何保留这个名字】宿主的「动画中即全速帧」判定原本读
         *   `ValueAnimator.isRunning`；句柄换成 [Handle] 后保持同名属性，
         *   调用点零改动，语义也从「Animator 认为在跑」收紧成「本句柄未结束」。
         */
        val isRunning: Boolean
            get() {
                if (ended) {
                    return false
                }
                val a = animator ?: return false
                return a.isRunning
            }

        /** 是否已结束（自然收敛或被取消都算）。 */
        fun isEnded(): Boolean {
            return ended
        }

        /** 主动取消：不写终态，只通知 listener.onCancelled()。 */
        fun cancel() {
            animator?.cancel()
        }
    }

    /**
     * 用弹簧驱动一次 0 → 1。
     *
     * @param spring   预置的弹簧实例（内部会先 start()）
     * @param listener 逐帧回调
     * @return 可取消的驱动句柄；参数为空时返回 null（调用方无需判空即可链式忽略）
     */
    @JvmStatic
    fun drive(spring: Spring?, listener: Listener?): Handle? {
        if (spring == null || listener == null) {
            return null
        }
        spring.start()
        return driveInternal(spring, listener)
    }

    /**
     * 【新增】从当前进度接着推向终态，不归零、不清速度。
     *
     * 【用途】连击 / 连续改目标值：希望观感是「从这个位置继续走」而不是「回起点重来」。
     */
    @JvmStatic
    fun retarget(spring: Spring?, listener: Listener?): Handle? {
        if (spring == null || listener == null) {
            return null
        }
        spring.retarget()
        return driveInternal(spring, listener)
    }

    private fun driveInternal(spring: Spring, listener: Listener): Handle {
        val handle = Handle()
        var last = SystemClock.uptimeMillis()
        // 自然收敛的收尾：只在这里写终态并回调 onEnd。
        fun finish() {
            if (handle.ended) {
                return
            }
            handle.ended = true
            spring.settle()
            listener.onUpdate(1.0f)
            listener.onEnd()
        }
        // 被外部取消的收尾：绝不动 spring、绝不 onEnd —— 交给调用方。
        fun abort() {
            if (handle.ended) {
                return
            }
            handle.ended = true
            listener.onCancelled()
        }

        val va = ValueAnimator.ofFloat(0.0f, 1.0f)
        va.duration = MAX_MS
        // 不设 Interpolator：进度由 Spring 自己积分，ValueAnimator 只当时钟。
        va.interpolator = Interpolator { input -> input }
        va.addUpdateListener { an ->
            if (handle.ended) {
                return@addUpdateListener
            }
            val now = SystemClock.uptimeMillis()
            val dt = (now - last) / 1000.0
            last = now
            val settled = spring.step(dt)
            listener.onUpdate(spring.x.toFloat())
            if (settled) {
                // 【顺序】先置 ended 再 cancel：cancel 会同步进 onAnimationCancel，
                //   那里看到 ended 已为 true 就会静默返回，不会把自然收敛误判成取消。
                finish()
                an.cancel()
            }
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(an: Animator) {
                abort()
            }

            override fun onAnimationEnd(an: Animator) {
                // 跑到 MAX_MS 还没收敛（理论上不会发生）也要给出终态，否则视图会停在半路。
                finish()
            }
        })
        handle.animator = va
        va.start()
        return handle
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