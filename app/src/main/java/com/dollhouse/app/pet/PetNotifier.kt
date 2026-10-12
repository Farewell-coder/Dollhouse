package com.dollhouse.app.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PetService
import com.dollhouse.app.R

/**
 * 【职责】桌宠前台服务的常驻通知：建频道、发通知、升前台、安全退出。
 *
 * 【入口】只由 PetService 调用（onStartCommand → startForeground，onDestroy/STOP → stopSafely）。
 *
 * 【交互】通知点进 MainActivity，动作按钮发 ACTION_STOP 回服务；不持有任何状态。
 *
 * 【扩展】改通知文案/图标/动作只改本类；想加第二个动作在 buildNotification 里 addAction。
 *
 * 【坑】CHANNEL_ID 必须与历史值 feiyu_pet 一致，否则会另建一个通知渠道；
 *        升前台必须走 startForegroundCompat（SDK34+ 要传 specialUse 类型），直接调 startForeground 会抛异常。
 */
object PetNotifier {
    private const val CHANNEL_ID = "feiyu_pet"
    private const val NOTIF_ID = 1001

    /** 【r12】「没选人偶」提示通知的 id（与常驻通知分开，互不覆盖）。 */
    private const val NOTIF_ID_NO_DOLL = 1002

    /** 构建常驻通知（点通知回主界面，动作按钮停服务）。 */
    private fun buildNotification(service: Service): Notification {
        val builder: Notification.Builder
        val notificationManager = service.getSystemService("notification") as NotificationManager?
        if (Build.VERSION.SDK_INT >= 26 && notificationManager != null) {
            val notificationChannel = NotificationChannel(CHANNEL_ID, "桌宠运行中", 2)
            notificationChannel.setShowBadge(false)
            notificationChannel.setDescription("Dollhouse 的常驻通知")
            notificationManager.createNotificationChannel(notificationChannel)
        }
        val intent = Intent(service, MainActivity::class.java)
        intent.setFlags(335544320)
        val activity = PendingIntent.getActivity(service, 0, intent, 201326592)
        val intent2 = Intent(service, PetService::class.java)
        intent2.setAction(PetService.ACTION_STOP)
        val service2 = PendingIntent.getService(service, 1, intent2, 201326592)
        builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(service, CHANNEL_ID)
        } else {
            Notification.Builder(service)
        }
        builder.setSmallIcon(R.drawable.ic_stat_pet).setContentTitle("小肥鱼在你屏幕上").setContentText("点这里可以关掉她").setContentIntent(activity).setOngoing(true).setShowWhen(false).addAction(Notification.Action.Builder(Icon.createWithResource(service, R.drawable.ic_stat_pet), "退出桌宠", service2).build())
        builder.setVisibility(1)
        return builder.build()
    }

    /**
     * 【r12 新增】「没选人偶」提示：一只都没启用时点启动，用一条通知说清楚原因。
     *
     * 【为什么用通知而不是 Toast】外部入口（磁贴 / 外部链接 / 开机广播）触发时，
     *   前台未必有 Activity，Toast 会静默丢掉；通知在哪儿都看得到。
     * 【为什么单独一个 id】与常驻通知分开，不会互相覆盖；用户划掉即消失，不驻留。
     */
    @JvmStatic
    fun notifyNoDoll(service: Service) {
        try {
            val nm = service.getSystemService("notification") as NotificationManager?
            if (nm == null) {
                return
            }
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(CHANNEL_ID, "桌宠运行中", 2)
                ch.setShowBadge(false)
                ch.setDescription("Dollhouse 的常驻通知")
                nm.createNotificationChannel(ch)
            }
            val intent = Intent(service, MainActivity::class.java)
            intent.setFlags(335544320)
            val activity = PendingIntent.getActivity(service, 2, intent, 201326592)
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(service, CHANNEL_ID)
            } else {
                Notification.Builder(service)
            }
            builder.setSmallIcon(R.drawable.ic_stat_pet)
                .setContentTitle("还没有选择人偶")
                .setContentText("先去「设置 → 自定义人偶」启用一只，她才会出现。")
                .setContentIntent(activity)
                .setAutoCancel(true)
                .setShowWhen(false)
            nm.notify(NOTIF_ID_NO_DOLL, builder.build())
        } catch (ignored: Throwable) {
        }
    }

    /** 兼容式升前台：高版本传 specialUse 类型，低版本走老签名。 */
    @JvmStatic
    fun startForegroundCompat(service: Service) {
        val notification = buildNotification(service)
        if (Build.VERSION.SDK_INT >= 34) {
            service.startForeground(NOTIF_ID, notification, 1073741824)
        } else {
            service.startForeground(NOTIF_ID, notification)
        }
    }

    /** 安全退出：先停前台再 stopSelf，避免 ANR 与通知残留。 */
    @JvmStatic
    fun stopSafely(service: Service) {
        service.stopForeground(1)
        service.stopSelf()
    }
}
