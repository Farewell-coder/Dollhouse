package com.dollhouse.app.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.dollhouse.app.MainActivity

/**
 * 【职责】保活软件进程的载体：一个常驻前台服务，进程活着时始终挂着一个 1×1 透明悬浮窗。
 *
 * 【保活语义】本服务保的是「Dollhouse 这个应用进程」，不是人偶悬浮窗：
 *   - 前台服务本身 = 系统级可见的常驻组件，进程优先级高于普通后台进程；
 *   - 它同时持有 [KeepAliveOverlay] 的 1×1 透明窗，把进程提到可见进程级；
 *   - 它自己持有 SYSTEM_ALERT_WINDOW，因此**后续**从后台再拉起它时符合
 *     官方「后台启动前台服务」豁免（见 [KeepAliveForegroundPolicy]）。
 *   三者叠加＝「尽量不被回收 + 被回收后能回来」，与 GKD 的保活思路一致。
 *
 * 【与人偶的关系】人偶（[com.dollhouse.app.PetService]）也在本进程内，
 *   软件进程活着她自然一起活着；但本服务不负责显示她，也不读她的开关状态。
 *
 * 【为什么用 START_STICKY】被系统低内存杀掉后由系统尝试重建本服务；
 *   即便如此仍保留 Job / Alarm 通道作双保险（部分 ROM 不遵守 STICKY）。
 *
 * 【坑】前台服务必须在 startForegroundService 后 5 秒内 startForeground，
 *   否则系统直接抛 RemoteServiceException 杀进程；因此 onStartCommand 第一步就是它。
 */
class KeepAliveService : Service() {

    companion object {
        /** 进程内权威判据：服务是否已启动且未销毁。同进程内读写，无需 IPC。 */
        @Volatile
        var alive: Boolean = false
            private set

        /** 启动动作：显式 action 便于与其他服务的 Intent 区分、也便于日志排查。 */
        const val ACTION_START = "KEEPALIVE_START"

        private const val CHANNEL_ID = "feiyu_keepalive"
        private const val NOTIF_ID = 1002
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        alive = true
        try {
            startForegroundCompat()
        } catch (t: Throwable) {
            // 升不了前台就没有保活价值，直接退出，避免留下无通知的野进程。
            KeepAliveLog.w("startForeground failed", t)
            stopSelf()
            return START_NOT_STICKY
        }
        // 悬浮窗失败不回滚前台服务：前台服务这条保活通道本身仍然有效，只是降级。
        KeepAliveOverlay.attach(this)
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        KeepAliveOverlay.detach()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            KeepAliveLog.w("stopForeground failed", t)
        }
        super.onDestroy()
    }

    /** 建渠道 + 升前台。SDK34+ 必须带 specialUse 类型，否则抛异常。 */
    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            val channel = NotificationChannel(CHANNEL_ID, "应用运行中", NotificationManager.IMPORTANCE_MIN)
            channel.setShowBadge(false)
            channel.setDescription("让 Dollhouse 不被系统回收的常驻通知")
            nm.createNotificationChannel(channel)
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            // 1073741824 = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE（API 34 新增）。
            //   与 PetNotifier 保持同一写法：直接用字面量，避免编译期常量的可见性问题。
            startForeground(NOTIF_ID, notification, 1073741824)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** 常驻通知：点一下回主界面；不提供「关闭」动作（关闭走设置页的保活开关）。 */
    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(com.dollhouse.app.R.drawable.ic_stat_pet)
            .setContentTitle("Dollhouse 正在运行")
            .setContentText("开启无感保活后，应用会常驻后台")
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
