package com.dollhouse.app;

import android.content.Context;

import com.dollhouse.app.keepalive.KeepAliveFacade;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;

/**
 * 【职责】存量 Java 与 Kotlin 保活模块之间唯一的窄桥。
 *
 * 【为什么需要它】保活模块全部是 Kotlin + Hilt（@Singleton + 构造注入），Java 侧拿不到
 *   那些注入实例。Hilt 官方为这种场景提供了 @EntryPoint：在 ApplicationComponent 上开一个
 *   门，用 EntryPointAccessors.fromApplication() 取出来。这样 Java 侧只需要认识本类的几个
 *   静态方法，不需要认识 KeepAliveScheduler / Runner / NetworkMonitor 这些内部类。
 *
 * 【为什么用一个 boolean 缓存入口而不是每次缓存实例】EntryPointAccessors 每次调用都会做一次
 *   容器查找，属于轻量操作；但设置页会反复刷新开关状态，缓存入口对象可以省下重复查找。
 *   Application 生命周期内不会变，缓存是安全的（不持有 Activity / View）。
 *
 * 【硬约束】本类不写任何与保活无关的状态，不改 PetPrefs —— 保活状态归 feiyu_keepalive 文件。
 *
 * 【坑】不要在这里实现与 KeepAliveFacade 同签名的方法并互相调用，那会变成无限自递归；
 *   本类只做「取容器 → 转发」。
 */
final class KeepAliveBridge {

    /** 日志 tag：与 Logs 其它调用点保持一致。 */
    private static final String LOG_TAG = "Dollhouse";

    /** Hilt 入口：把 KeepAliveFacade 暴露给非注入代码。 */
    @EntryPoint
    @InstallIn(SingletonComponent.class)
    interface KeepAliveEntryPoint {
        KeepAliveFacade keepAliveFacade();
    }

    private static KeepAliveEntryPoint entry;

    private KeepAliveBridge() {
    }

    /** 取门面；容器未就绪（极早期调用）时返回 null，由调用方兜底。 */
    private static KeepAliveFacade facade(Context ctx) {
        try {
            if (entry == null) {
                entry = EntryPointAccessors.fromApplication(
                        ctx.getApplicationContext(), KeepAliveEntryPoint.class);
            }
            return entry == null ? null : entry.keepAliveFacade();
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "keepalive facade unavailable", ignored);
            return null;
        }
    }

    /** 开关是否开启。取不到时按「关」处理，UI 显示关。 */
    static boolean isEnabled(Context ctx) {
        KeepAliveFacade f = facade(ctx);
        return f != null && f.isEnabled();
    }

    /** 拨动开关。返回实际生效状态。 */
    static boolean setEnabled(Context ctx, boolean on) {
        KeepAliveFacade f = facade(ctx);
        if (f == null) {
            return false;
        }
        f.setEnabled(on);
        return f.isEnabled();
    }

    /**
     * 常驻保活服务当前是否活着（应用进程是否被保活挂住）。
     * 【用途】保活分组的说明行展示「运行中 / 未运行」用；
     *   直接读同进程内的一个布尔，零阻塞、零 IPC，可在主线程调。
     */
    static boolean isAppAlive(Context ctx) {
        KeepAliveFacade f = facade(ctx);
        return f != null && f.isAppAlive();
    }

    /**
     * 是否已进电池优化白名单。
     * 【为什么由这一侧提供】原 Java 侧（HomeUi.ignoringBattery）与 Kotlin 侧
     *   （KeepAlivePermissions）各写了一份同样的判断；统一到 Kotlin 一份，
     *   Java 只做转发，避免两处行为日后漂移。
     */
    static boolean isIgnoringBatteryOptimizations(Context ctx) {
        KeepAliveFacade f = facade(ctx);
        return f != null && f.isIgnoringBatteryOptimizations();
    }

    /** 打开厂商「允许自启动」页（三级降级链）。 */
    static boolean openAutostartPage(Context ctx) {
        KeepAliveFacade f = facade(ctx);
        return f != null && f.openAutostartPage();
    }

    /** 打开厂商「后台运行 / 耗电管理」页（三级降级链）。 */
    static boolean openBatteryPage(Context ctx) {
        KeepAliveFacade f = facade(ctx);
        return f != null && f.openBatteryPage();
    }
}