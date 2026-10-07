package com.dollhouse.app.keepalive

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 【职责】常驻一个 1×1 完全透明的悬浮窗，作为「本进程持有可见窗口」的系统级凭据。
 *
 * 【为什么这样能保活软件】Android 的进程优先级里，
 *   「持有可见窗口的进程」= 可见进程（perceptible），远高于被划掉后的缓存进程（cached）；
 *   系统回收内存时优先杀缓存进程。挂着一个哪怕 1×1、不显示、不可点的 overlay，
 *   就等于把本进程从「随时可杀」提到「尽量别杀」。
 *   —— 思路来自 GKD 的 KeepAliveOverlayCoordinator（https://github.com/gkd-kit/gkd）。
 *
 * 【和 JobScheduler 的关系】两者互补，不是替代：
 *   - overlay 让进程**不容易被回收**（主动常驻）；
 *   - Job / Alarm / 退避闹钟让进程**被回收后还能回来**（被动拉回）。
 *
 * 【为什么由 Service 持有而不是 Application】overlay 与 [KeepAliveService] 同生命周期：
 *   服务被系统停掉时立刻卸窗，不留「窗口还在但没人管」的悬挂状态；
 *   服务活着则窗口必然在，探测只需读一个布尔，无需 IPC。
 *
 * 【坑】没有 SYSTEM_ALERT_WINDOW 权限时 addView 会抛，且各 ROM 抛的异常类型不一致，
 *   因此全程 runCatching：挂不上就当作没挂，由上层退回「只靠 Job / Alarm 拉回」的降级路径。
 */
internal object KeepAliveOverlay {

    private var wm: WindowManager? = null
    private var view: View? = null

    /** 当前是否已挂上常驻悬浮窗（进程内权威判据）。 */
    val isAttached: Boolean
        get() = view != null

    /**
     * 挂上常驻悬浮窗。幂等：已挂则直接返回 true。
     * @return true = 已挂上；false = 无悬浮窗权限 / 系统拒绝。
     */
    @Synchronized
    fun attach(context: Context): Boolean {
        if (view != null) {
            return true
        }
        return try {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return false
            val v = View(context)
            manager.addView(v, layoutParams(context))
            wm = manager
            view = v
            KeepAliveLog.d("overlay attached")
            true
        } catch (t: Throwable) {
            KeepAliveLog.w("overlay attach failed", t)
            false
        }
    }

    /** 卸下常驻悬浮窗。幂等：未挂则不做任何事。 */
    @Synchronized
    fun detach() {
        val v = view ?: return
        view = null
        try {
            // removeViewImmediate：不等下一帧，保证服务销毁后窗口立刻消失。
            wm?.removeViewImmediate(v)
            KeepAliveLog.d("overlay detached")
        } catch (t: Throwable) {
            KeepAliveLog.w("overlay detach failed", t)
        } finally {
            wm = null
        }
    }

    private fun layoutParams(context: Context): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams()
        lp.type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        lp.format = PixelFormat.TRANSLUCENT
        // 不可点、不抢焦点：对用户完全无感，也不会截走任何人偶的触摸事件。
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.gravity = Gravity.START or Gravity.TOP
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        // packageName 让系统把窗口归属到本应用（部分 ROM 的权限校验会读它）。
        lp.packageName = context.packageName
        return lp
    }
}
