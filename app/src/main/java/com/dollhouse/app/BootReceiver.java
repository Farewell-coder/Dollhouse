package com.dollhouse.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 【职责】开机自启：系统启动完成后把桌宠服务拉回来。
 *
 * 【入口】AndroidManifest 注册 RECEIVE_BOOT_COMPLETED 广播，认 BOOT_COMPLETED 与 MY_PACKAGE_REPLACED。
 *
 * 【交互】只做一件事——在用户没有主动关闭人偶时，向 PetService 发 ACTION_START；
 *         不建任务栈、不跳 Activity，绝不抢用户前台。
 *
 * 【坑】Android 12+ 对「接收广播后启动前台服务」有限制，某些机型 / service type 会被拦；
 *       因此全程 try/catch 静默，失败也不崩、不弹窗——开机自启是否真生效取决于系统
 *       对应用的自启动 / 后台活动策略（ColorOS 需在应用详情里手动允许）。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "Dollhouse";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null) {
                return;
            }
            // 【无感保活】除开机外，覆盖安装（MY_PACKAGE_REPLACED）同样要恢复：
            //   JobScheduler 的 persisted 任务在包被替换时会被系统清掉，而该广播不会
            //   派发给旧进程，只能靠新进程冷启时收到这一次。
            final String action = intent.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                return;
            }
            // 用户主动关过就不打扰；下次他自己点「启动人偶」会清掉这个标志。
            if (PetPrefs.userStopped(context)) {
                return;
            }
            Intent start = new Intent(context, PetService.class);
            start.setAction(PetService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(start);
            } else {
                context.startService(start);
            }
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }
}
