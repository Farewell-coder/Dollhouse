package com.dollhouse.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 【职责】桌宠启停的统一出口：外部链接（PetLinkActivity）、快捷设置磁贴（PetTileService）、
 *         首页按钮三条路径共用同一套「是否在跑」判定与 Intent 组装，避免三处各写一份。
 *
 * 【入口】只被上面三处调用；不注册任何组件。
 *
 * 【交互】只向 PetService 发 ACTION_START / ACTION_STOP，不持有窗口、不碰偏好。
 *
 * 【坑】PetService 在清单里是 exported="false"，外部进程拿不到它；
 *        所以外部链接必须先落回本应用的 Activity（PetLinkActivity），再由那个 Activity 调这里。
 */
final class PetToggle {

    private static final String LOG_TAG = "Dollhouse";

    private PetToggle() {
    }

    /** 桌宠服务是否在运行。查的是本应用自己的服务，Android 8+ 仍允许。 */
    static boolean isRunning(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
                String cn = info.service == null ? null : info.service.getClassName();
                if (PetService.class.getName().equals(cn)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        return false;
    }

    /** 启动桌宠：高版本走 startForegroundService，避免「后台启动服务」被拦。 */
    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, PetService.class);
            i.setAction(PetService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    /** 关闭桌宠：置位 userStopped 由 PetService 自己在 STOP 分支完成，这里只发指令。 */
    static void stop(Context ctx) {
        try {
            Intent i = new Intent(ctx, PetService.class);
            i.setAction(PetService.ACTION_STOP);
            ctx.startService(i);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    /**
     * 【修 v0.0.1】开机后对账：只要用户没主动点过「关闭人偶」，就保证她出来。
     *
     * 【为什么】用户要求「这个指令启动要一直都有、默认一直开着」——
     *   原先只有 BootReceiver 在收开机广播时拉一次，而 ColorOS 的智能省电 / 后台活动限制
     *   会把那次 startForegroundService 拦掉，机器重启后她就不出来了。
     *   这里提供一个「进主页就对账」的幂等入口：没在跑且没被主动关，就补一次。
     * 【幂等】isRunning 为真立刻返回，不会反复启停；用户点了「关闭人偶」后会置位
     *   userStopped，本方法那时也不再拉起，不跟用户意愿打架。
     */
    static void aliveOnBoot(Context ctx) {
        if (isRunning(ctx) || PetPrefs.userStopped(ctx)) {
            return;
        }
        start(ctx);
    }
    /** 开 → 关、关 → 开。外部链接与磁贴共用这一条（用户要求：同一条指令兼管开与关）。 */
    static void toggle(Context ctx) {
        if (isRunning(ctx)) {
            stop(ctx);
        } else {
            start(ctx);
        }
    }
}
