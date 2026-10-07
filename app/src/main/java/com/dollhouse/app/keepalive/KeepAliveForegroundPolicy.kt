package com.dollhouse.app.keepalive

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 【职责】判断「此刻从后台拉起前台服务」是否真的可行，避免明知会被系统拦下还去撞一遍。
 *
 * 【背景】API 31（Android 12）起，后台启动前台服务默认抛
 *   ForegroundServiceStartNotAllowedException。官方豁免清单里有一条正是
 *   「持有 SYSTEM_ALERT_WINDOW 权限」，本应用（人偶悬浮窗 + 保活用的 1×1 透明窗）
 *   恰好长期持有。所以这里的判据不是「有没有悬浮窗权限」，而是「这条官方豁免现在成不成立」。
 *
 * 【为什么值得单独一层】拉起动作由 [KeepAliveRunner] 在 Job / Alarm / 网络恢复三个
 *   时机发起；把「能不能起」的判断抽出来，判定逻辑可单独验证，且失败原因集中在一处。
 *
 * 【坑】没有悬浮窗权限时，1×1 透明窗挂不上（[KeepAliveOverlay.attach] 返回 false），
 *   进程优先级提升这条通道失效；此时只靠 Job / Alarm 做「被回收后拉回」，
 *   属于降级运行而非故障，故不阻塞用户开开关。
 */
@Singleton
class KeepAliveForegroundPolicy @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 现在是否允许从后台启动前台服务。
     *
     * - API < 31：无该限制，恒为 true；
     * - API >= 31：以 SYSTEM_ALERT_WINDOW 豁免为准；未授权时返回 false，
     *   由 UI 引导用户先给人偶悬浮窗权限。
     */
    fun canStartForegroundFromBackground(): Boolean {
        if (Build.VERSION.SDK_INT < 31) {
            return true
        }
        return try {
            Settings.canDrawOverlays(context)
        } catch (t: Throwable) {
            // 取不到就当作不可用：宁可漏拉一轮，也不留异常噪音。
            false
        }
    }

    /**
     * 通知渠道是否被用户关闭（API 26+）。
     *
     * 【为什么要这个】常驻通知是前台服务的可见性凭据；渠道被关时服务仍能运行，
     *   但用户会以为「保活没生效」。此处只做判断，不改用户设置、不弹提示。
     */
    fun isNotificationChannelEnabled(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < 26) {
            return true
        }
        return try {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return true
            nm.getNotificationChannel(channelId)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
        } catch (t: Throwable) {
            true
        }
    }
}