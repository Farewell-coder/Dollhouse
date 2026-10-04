package com.dollhouse.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

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
final class PetNotifier {
    private static final String CHANNEL_ID = "feiyu_pet";
    private static final int NOTIF_ID = 1001;
    private PetNotifier() {
    }
    /** 构建常驻通知（点通知回主界面，动作按钮停服务）。 */
    private static Notification buildNotification(Service service) {
        Notification.Builder builder;
        NotificationManager notificationManager = (NotificationManager) service.getSystemService("notification");
        if (Build.VERSION.SDK_INT >= 26 && notificationManager != null) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "桌宠运行中", 2);
            notificationChannel.setShowBadge(false);
            notificationChannel.setDescription("Dollhouse 的常驻通知");
            notificationManager.createNotificationChannel(notificationChannel);
        }
        Intent intent = new Intent(service, (Class<?>) MainActivity.class);
        intent.setFlags(335544320);
        PendingIntent activity = PendingIntent.getActivity(service, 0, intent, 201326592);
        Intent intent2 = new Intent(service, (Class<?>) PetService.class);
        intent2.setAction(PetService.ACTION_STOP);
        PendingIntent service2 = PendingIntent.getService(service, 1, intent2, 201326592);
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(service, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(service);
        }
        builder.setSmallIcon(R.drawable.ic_stat_pet).setContentTitle("小肥鱼在你屏幕上").setContentText("点这里可以关掉她").setContentIntent(activity).setOngoing(true).setShowWhen(false).addAction(new Notification.Action.Builder(Icon.createWithResource(service, R.drawable.ic_stat_pet), "退出桌宠", service2).build());
        builder.setVisibility(1);
        return builder.build();
    }
    /** 兼容式升前台：高版本传 specialUse 类型，低版本走老签名。 */
    static void startForegroundCompat(Service service) {
        Notification buildNotification = buildNotification(service);
        if (Build.VERSION.SDK_INT >= 34) {
            service.startForeground(NOTIF_ID, buildNotification, 1073741824);
        } else {
            service.startForeground(NOTIF_ID, buildNotification);
        }
    }
    /** 安全退出：先停前台再 stopSelf，避免 ANR 与通知残留。 */
    static void stopSafely(Service service) {
        service.stopForeground(1);
        service.stopSelf();
    }
}
