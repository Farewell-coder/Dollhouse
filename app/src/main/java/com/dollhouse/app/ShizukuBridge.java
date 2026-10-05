package com.dollhouse.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import rikka.shizuku.Shizuku;

/**
 * 【职责】Shizuku 权限的唯一入口：状态查询、授权申请、管理器引导、以 shell 身份执行命令。
 *
 * 【入口】设置页「Shizuku 授权」行（HomeUi）与 AI 的 shell 工具（ShellTool）都只经本类。
 *
 * 【交互】不持有任何状态、不碰 View；listener 由调用方注册/注销。
 *
 * 【为什么单独一个类】Shizuku 的调用面（binder 就绪判定、权限码、反射进程）细节多，
 *   散在 UI 与工具两处必然分叉。这里收口成一处，改只改这里。
 *
 * 【坑 1·随机包名】管理器可能被「隐身/随机包名」改成 moe.shizuku.privileged.api.xxxx，
 *   所以绝不写死包名：本类用 queryIntentActivities 反查 REQUEST_PERMISSION 的持有者。
 *   注意 SDK 30+ 包可见性限制：必须由 Manifest 的 <queries> 声明该 action，
 *   否则 queryIntentActivities 返回空（本工程已在 Manifest 声明）。
 *
 * 【坑 2·binder 就绪】Shizuku 的 service 由 server（adb/root）推送 binder 而来，
 *   未运行 / 未推送时 pingBinder() 为 false，此时任何 checkSelfPermission/getVersion 都可能抛。
 *   所有对外方法一律先判 pingBinder 并整体 try/catch，绝不让异常冒到 UI 线程。
 *
 * 【坑 3·执行命令】13.x 的 Shizuku.newProcess 是 private（官方只把它暴露给 UserService 场景），
 *   社区通行做法是反射调用，拿到 ShizukuRemoteProcess（Process 子类）后按标准流读写。
 *   反射失败一律降级为错误文案，不抛异常。
 */
final class ShizukuBridge {

    private static final String TAG = "DollhouseShizuku";
    /** 管理器用来响应权限请求的 action，同时用它的持有者反查真实包名（随机包名环境下唯一稳定锚点）。 */
    private static final String ACTION_REQUEST_PERMISSION =
            "moe.shizuku.privileged.api.intent.action.REQUEST_PERMISSION";
    /** 未隐身时的固定包名，只作反查失败后的第二候选。 */
    private static final String MANAGER_PKG_PLAIN = "moe.shizuku.privileged.api";
    /** 回填给模型的输出上限：防止一条命令刷爆上下文。 */
    private static final int MAX_OUTPUT_CHARS = 8000;

    // ---------------- 状态缓存 ----------------
    // 【为什么缓存】pingBinder / checkSelfPermission 都是 binder IPC，而调用点全在主线程：
    //   首页每秒刷一次权限行、每次请求构建 tools 也会问一次。服务无响应时高频 IPC 会拖出 ANR。
    //   2s TTL 下用户可感延迟极小（授权结果回调里会主动作废缓存，见 invalidate）。
    private static final long CACHE_TTL_MS = 2000L;
    private static boolean CACHE_RUN = false;
    private static long CACHE_RUN_AT = 0L;
    private static boolean CACHE_GRANTED = false;
    private static long CACHE_AT = 0L;
    /** 反查到的管理器包名（null = 未安装 / 尚未反查）。 */
    private static String CACHE_PKG = null;
    private static long CACHE_PKG_AT = 0L;

    /** 作废状态缓存：授权结果 / binder 到达这类「刚发生变化」的时刻由回调方调用。 */
    static void invalidate() {
        CACHE_RUN_AT = 0L;
        CACHE_AT = 0L;
        CACHE_PKG_AT = 0L;
    }

    /** 未安装管理器。 */
    static final int S_NOT_INSTALLED = 0;
    /** 已安装但服务没跑（server 未启动 / binder 未推送）。 */
    static final int S_NOT_RUNNING = 1;
    /** 服务在跑，但本应用没被授权。 */
    static final int S_NOT_GRANTED = 2;
    /** 已授权，能力可用。 */
    static final int S_GRANTED = 3;

    private ShizukuBridge() {
    }

    // ---------------- 状态 ----------------

    /** 综合状态：未安装 / 服务未运行 / 未授权 / 已授权。 */
    static int state(Context ctx) {
        if (!isInstalled(ctx)) {
            return S_NOT_INSTALLED;
        }
        if (!isServiceRunning()) {
            return S_NOT_RUNNING;
        }
        return isGranted() ? S_GRANTED : S_NOT_GRANTED;
    }

    /** 状态文案（权限行右侧显示）。 */
    static String stateText(int state) {
        switch (state) {
            case S_GRANTED:
                return "\u5df2\u6388\u6743";
            case S_NOT_GRANTED:
                return "\u672a\u6388\u6743";
            case S_NOT_RUNNING:
                return "\u670d\u52a1\u672a\u8fd0\u884c";
            default:
                return "\u672a\u5b89\u88c5";
        }
    }

    /** 行是否可点：只有「已授权」之外的三态才需要用户操作。 */
    static boolean needAction(int state) {
        return state != S_GRANTED;
    }

    /** 是否需要在 pre-v11 场景下引导用户去管理器手动授权。 */
    static boolean needManagerForGrant() {
        try {
            return cachedRun() && Shizuku.isPreV11();
        } catch (Throwable t) {
            return false;
        }
    }
    /** 管理器是否已安装：反查权限请求 action 的持有者，兼顾随机包名。 */
    static boolean isInstalled(Context ctx) {
        // 反查要走 PackageManager（IPC），而权限行每秒刷一次，同样加缓存。
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - CACHE_PKG_AT < CACHE_TTL_MS) {
            return CACHE_PKG != null;
        }
        CACHE_PKG = resolveManagerPackage(ctx);
        CACHE_PKG_AT = android.os.SystemClock.elapsedRealtime();
        return CACHE_PKG != null;
    }

    /**
     * 反查管理器的真实包名。
     * 【顺序·兼容老版】先按固定包名精确查 —— 未隐身的老版 Shizuku 就叫这个名，
     *   而且老版的 Activity 未必声明 REQUEST_PERMISSION 那个 intent-filter，
     *   只靠 action 反查会把「装了」误判成「没装」。
     *   固定包名查不到再看 action 反查 —— 新版「隐身 / 随机包名」把 applicationId
     *   改成了 moe.shizuku.privileged.api.xxxx，但 action 名不会被随机化，用它的持有者即可。
     * 【前提】两条路都受 SDK 30+ 包可见性限制，Manifest 的 <queries> 里两种形态都必须声明。
     */
    static String resolveManagerPackage(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            ctx.getPackageManager().getPackageInfo(MANAGER_PKG_PLAIN, 0);
            return MANAGER_PKG_PLAIN;
        } catch (Throwable ignored) {
            // 固定包名不存在（新版随机包名 / 没装），继续走 action 反查。
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent probe = new Intent(ACTION_REQUEST_PERMISSION);
            List<ResolveInfo> list = pm.queryIntentActivities(probe, 0);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    ResolveInfo ri = list.get(i);
                    if (ri != null && ri.activityInfo != null
                            && ri.activityInfo.packageName != null) {
                        return ri.activityInfo.packageName;
                    }
                }
            }
        } catch (Throwable t) {
            Logs.w(TAG, "resolve by action failed", t);
        }
        return null;
    }

    /** 服务是否在运行（binder 已就绪且可用）。 */
    static boolean isServiceRunning() {
        return cachedRun();
    }

    /**
     * 本应用是否已获 Shizuku 授权。
     * 【缓存】pingBinder + isPreV11 + checkSelfPermission 是 2~3 次 binder IPC，
     *   调用点（权限行刷新、每次请求构建 tools）都在主线程，服务无响应时有 ANR 风险。
     * 【兼容老版·关键】server < v11 时 Shizuku.isPreV11() 为 true，而 checkSelfPermission()
     *   在 pre-v11 下没有权限模型（老版是装了就默认放行），不能用它判定。
     *   官方语义：pre-v11 且未授权时 getUid() 会因 SecurityException 返回 -1，
     *   授权后返回 server 的真实 uid（adb 为 2000，root 为 0）—— 所以判 -1 != uid。
     *   旧实现一遇 isPreV11() 就 return false，导致老版永远显示「未授权」、无法授权。
     */
    static boolean isGranted() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - CACHE_AT < CACHE_TTL_MS) {
            return CACHE_GRANTED;
        }
        boolean granted;
        try {
            if (!cachedRun()) {
                granted = false;
            } else if (Shizuku.isPreV11()) {
                // 【注意】不能写成 getUid() >= 0：root 身份 server 的 uid 就是 0。
                granted = Shizuku.getUid() != -1;
            } else {
                granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable t) {
            granted = false;
        }
        CACHE_GRANTED = granted;
        CACHE_AT = android.os.SystemClock.elapsedRealtime();
        return granted;
    }

    /** 服务在跑（1s 缓存，见 isGranted 注释）。 */
    private static boolean cachedRun() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - CACHE_RUN_AT < CACHE_TTL_MS) {
            return CACHE_RUN;
        }
        boolean run;
        try {
            run = Shizuku.pingBinder();
        } catch (Throwable t) {
            run = false;
        }
        CACHE_RUN = run;
        CACHE_RUN_AT = android.os.SystemClock.elapsedRealtime();
        return run;
    }

    /** 能力是否就绪（服务在跑 + 已授权）：AI 工具与 shell 执行的前置条件。 */
    static boolean isReady() {
        return isServiceRunning() && isGranted();
    }

    // ---------------- 授权 ----------------

    /** 发起授权请求（v11+ 由 server 弹系统确认框，结果经 listener 回来）。 */
    static void requestPermission(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable t) {
            Logs.w(TAG, "requestPermission failed", t);
        }
    }

    /**
     * 打开管理器主界面（用于「服务未运行」时引导用户去启动服务）。
     * 用反查到的真实包名的 launcher intent；取不到时退回该包的详情页。
     */
    static boolean openManager(Context ctx) {
        if (ctx == null) {
            return false;
        }
        String pkg = resolveManagerPackage(ctx);
        if (pkg == null) {
            return false;
        }
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                return true;
            }
        } catch (Throwable t) {
            Logs.w(TAG, "launch manager failed", t);
        }
        try {
            Intent details = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            details.setData(android.net.Uri.parse("package:" + pkg));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(details);
            return true;
        } catch (Throwable t) {
            Logs.w(TAG, "open details failed", t);
            return false;
        }
    }

    /** 注册授权结果监听（调用方负责在页面销毁时注销）。 */
    static void addPermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener);
        } catch (Throwable t) {
            Logs.w(TAG, "add listener failed", t);
        }
    }

    /** 注销授权结果监听。 */
    static void removePermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener);
        } catch (Throwable t) {
            Logs.w(TAG, "remove listener failed", t);
        }
    }

    /** 注册 binder 就绪监听（服务后来才起来时用来刷新状态）。 */
    static void addBinderListener(Shizuku.OnBinderReceivedListener listener) {
        try {
            Shizuku.addBinderReceivedListenerSticky(listener);
        } catch (Throwable t) {
            Logs.w(TAG, "add binder listener failed", t);
        }
    }

    /** 注销 binder 就绪监听。 */
    static void removeBinderListener(Shizuku.OnBinderReceivedListener listener) {
        try {
            Shizuku.removeBinderReceivedListener(listener);
        } catch (Throwable t) {
            Logs.w(TAG, "remove binder listener failed", t);
        }
    }

    // ---------------- 执行 ----------------

    /**
     * 以 adb shell（uid 2000）身份执行一条命令，返回合并后的输出。
     * 【契约】任何失败都返回带括号的说明文案，绝不抛异常 —— 调用方是模型工具与 UI，都受不了异常。
     */
    static String exec(String command, long timeoutMs) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) {
            return "\uff08\u7a7a\u547d\u4ee4\uff09";
        }
        if (!isServiceRunning()) {
            return "\uff08Shizuku \u670d\u52a1\u672a\u8fd0\u884c\uff0c\u8bf7\u5148\u542f\u52a8 Shizuku\uff09";
        }
        if (!isGranted()) {
            return "\uff08\u672a\u83b7\u5f97 Shizuku \u6388\u6743\uff09";
        }
        long timeout = timeoutMs > 0 ? timeoutMs : 15000L;
        Process proc;
        try {
            Method m = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            proc = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
        } catch (Throwable t) {
            Logs.w(TAG, "newProcess failed", t);
            return "\uff08\u65e0\u6cd5\u542f\u52a8 shell \u8fdb\u7a0b\uff1a" + brief(t) + "\uff09";
        }
        if (proc == null) {
            return "\uff08\u65e0\u6cd5\u542f\u52a8 shell \u8fdb\u7a0b\uff09";
        }
        final StringBuilder outBuf = new StringBuilder();
        final StringBuilder errBuf = new StringBuilder();
        Thread tOut = new Thread(new Runnable() {
            @Override
            public void run() {
                outBuf.append(readAll(proc.getInputStream()));
            }
        }, "shizuku-out");
        Thread tErr = new Thread(new Runnable() {
            @Override
            public void run() {
                errBuf.append(readAll(proc.getErrorStream()));
            }
        }, "shizuku-err");
        // 【坑】读线程必须是 daemon：进程被 destroy 后若管道仍被子进程持有，
        //   read() 会永久阻塞；非 daemon 线程会拖住 JVM 且每次超时泄漏两个线程。
        tOut.setDaemon(true);
        tErr.setDaemon(true);
        try {
            tOut.start();
            tErr.start();
            // 关掉 stdin：否则读 stdin 的命令（如 cat）会一直挂着不退出。
            try {
                OutputStream os = proc.getOutputStream();
                if (os != null) {
                    os.close();
                }
            } catch (Throwable ignored) {
            }
            boolean finished = waitFor(proc, timeout);
            if (!finished) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
            // 进程结束后流会自然 EOF；给读线程一点收尾时间，避免输出被截掉。
            tOut.join(1200L);
            tErr.join(1200L);
            return format(outBuf.toString(), errBuf.toString(), finished);
        } catch (Throwable t) {
            Logs.w(TAG, "exec failed", t);
            return "\uff08\u6267\u884c\u5931\u8d25\uff1a" + brief(t) + "\uff09";
        } finally {
            try {
                proc.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 等待进程结束；优先用 ShizukuRemoteProcess 自带的超时等待，退路是限时 join。 */
    private static boolean waitFor(final Process proc, final long timeoutMs) {
        try {
            Method m = proc.getClass().getMethod("waitForTimeout", long.class, java.util.concurrent.TimeUnit.class);
            Object r = m.invoke(proc, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            // 退路：独立线程 join。
        }
        final boolean[] done = new boolean[1];
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    proc.waitFor();
                    done[0] = true;
                } catch (Throwable ignored) {
                }
            }
        }, "shizuku-wait");
        // 同上：退路 waiter 也必须是 daemon，超时后不能拖住进程。
        waiter.setDaemon(true);
        waiter.start();
        try {
            waiter.join(timeoutMs);
        } catch (Throwable ignored) {
        }
        // 【坑】超时后腰断这个线程，否则它会永久挂在 proc.waitFor() 上。
        if (!done[0]) {
            try {
                waiter.interrupt();
            } catch (Throwable ignored) {
            }
        }
        return done[0];
    }

    /** 合并 stdout / stderr 并限长。 */
    private static String format(String out, String err, boolean finished) {
        StringBuilder sb = new StringBuilder();
        if (out != null && !out.isEmpty()) {
            sb.append(out);
        }
        if (err != null && !err.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("[\u9519\u8bef] ").append(err);
        }
        if (!finished) {
            sb.append("\n\uff08\u8d85\u65f6\u5df2\u4e2d\u65ad\uff09");
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            text = "\uff08\u547d\u4ee4\u65e0\u8f93\u51fa\uff09";
        }
        if (text.length() > MAX_OUTPUT_CHARS) {
            text = text.substring(0, MAX_OUTPUT_CHARS) + "\n…\uff08\u8f93\u51fa\u5df2\u622a\u65ad\uff09";
        }
        return text;
    }

    /** 读流到字符串；异常一律吞掉（读流失败不该让整条命令失败）。 */
    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 异常压成一行短文案（不打印堆栈到用户可见处）。 */
    private static String brief(Throwable t) {
        String msg = t == null ? null : t.getMessage();
        return msg == null || msg.isEmpty() ? String.valueOf(t) : msg;
    }
}
