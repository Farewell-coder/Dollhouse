package com.dollhouse.app.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import com.dollhouse.app.core.Logs
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

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
object ShizukuBridge {

    private const val TAG = "DollhouseShizuku"

    /** 管理器用来响应权限请求的 action，同时用它的持有者反查真实包名（随机包名环境下唯一稳定锚点）。 */
    private const val ACTION_REQUEST_PERMISSION =
        "moe.shizuku.privileged.api.intent.action.REQUEST_PERMISSION"

    /** 未隐身时的固定包名，只作反查失败后的第二候选。 */
    private const val MANAGER_PKG_PLAIN = "moe.shizuku.privileged.api"

    /** 回填给模型的输出上限：防止一条命令刷爆上下文。 */
    private const val MAX_OUTPUT_CHARS = 8000

    // ---------------- 状态缓存 ----------------
    // 【为什么缓存】pingBinder / checkSelfPermission 都是 binder IPC，而调用点全在主线程：
    //   首页每秒刷一次权限行、每次请求构建 tools 也会问一次。服务无响应时高频 IPC 会拖出 ANR。
    //   2s TTL 下用户可感延迟极小（授权结果回调里会主动作废缓存，见 invalidate）。
    private const val CACHE_TTL_MS = 2000L
    private var CACHE_RUN = false
    private var CACHE_RUN_AT = 0L
    private var CACHE_GRANTED = false
    private var CACHE_AT = 0L

    /** 反查到的管理器包名（null = 未安装 / 尚未反查）。 */
    private var CACHE_PKG: String? = null
    private var CACHE_PKG_AT = 0L

    /** 作废状态缓存：授权结果 / binder 到达这类「刚发生变化」的时刻由回调方调用。 */
    @JvmStatic
    fun invalidate() {
        CACHE_RUN_AT = 0L
        CACHE_AT = 0L
        CACHE_PKG_AT = 0L
    }

    /** 未安装管理器。 */
    const val S_NOT_INSTALLED = 0

    /** 已安装但服务没跑（server 未启动 / binder 未推送）。 */
    const val S_NOT_RUNNING = 1

    /** 服务在跑，但本应用没被授权。 */
    const val S_NOT_GRANTED = 2

    /** 已授权，能力可用。 */
    const val S_GRANTED = 3

    // ---------------- 状态 ----------------

    /** 综合状态：未安装 / 服务未运行 / 未授权 / 已授权。 */
    @JvmStatic
    fun state(ctx: Context): Int {
        if (!isInstalled(ctx)) {
            return S_NOT_INSTALLED
        }
        if (!isServiceRunning()) {
            return S_NOT_RUNNING
        }
        return if (isGranted()) S_GRANTED else S_NOT_GRANTED
    }

    /** 状态文案（权限行右侧显示）。 */
    @JvmStatic
    fun stateText(state: Int): String {
        return when (state) {
            S_GRANTED -> "已授权"
            S_NOT_GRANTED -> "未授权"
            S_NOT_RUNNING -> "服务未运行"
            else -> "未安装"
        }
    }

    /** 是否需要在 pre-v11 场景下引导用户去管理器手动授权。 */
    @JvmStatic
    fun needManagerForGrant(): Boolean {
        return try {
            cachedRun() && Shizuku.isPreV11()
        } catch (t: Throwable) {
            false
        }
    }

    /** 管理器是否已安装：反查权限请求 action 的持有者，兼顾随机包名。 */
    @JvmStatic
    fun isInstalled(ctx: Context): Boolean {
        // 反查要走 PackageManager（IPC），而权限行每秒刷一次，同样加缓存。
        val now = SystemClock.elapsedRealtime()
        if (now - CACHE_PKG_AT < CACHE_TTL_MS) {
            return CACHE_PKG != null
        }
        CACHE_PKG = resolveManagerPackage(ctx)
        CACHE_PKG_AT = SystemClock.elapsedRealtime()
        return CACHE_PKG != null
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
    @JvmStatic
    fun resolveManagerPackage(ctx: Context?): String? {
        if (ctx == null) {
            return null
        }
        try {
            ctx.packageManager.getPackageInfo(MANAGER_PKG_PLAIN, 0)
            return MANAGER_PKG_PLAIN
        } catch (ignored: Throwable) {
            // 固定包名不存在（新版随机包名 / 没装），继续走 action 反查。
        }
        try {
            val pm = ctx.packageManager
            val probe = Intent(ACTION_REQUEST_PERMISSION)
            val list = pm.queryIntentActivities(probe, 0)
            if (list != null) {
                for (i in list.indices) {
                    val ri = list[i]
                    if (ri != null && ri.activityInfo != null
                        && ri.activityInfo.packageName != null
                    ) {
                        return ri.activityInfo.packageName
                    }
                }
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "resolve by action failed", t)
        }
        return null
    }

    /** 服务是否在运行（binder 已就绪且可用）。 */
    @JvmStatic
    fun isServiceRunning(): Boolean {
        return cachedRun()
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
    @JvmStatic
    fun isGranted(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - CACHE_AT < CACHE_TTL_MS) {
            return CACHE_GRANTED
        }
        val granted: Boolean = try {
            if (!cachedRun()) {
                false
            } else if (Shizuku.isPreV11()) {
                // 【注意】不能写成 getUid() >= 0：root 身份 server 的 uid 就是 0。
                Shizuku.getUid() != -1
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (t: Throwable) {
            false
        }
        CACHE_GRANTED = granted
        CACHE_AT = SystemClock.elapsedRealtime()
        return granted
    }

    /** 服务在跑（1s 缓存，见 isGranted 注释）。 */
    private fun cachedRun(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - CACHE_RUN_AT < CACHE_TTL_MS) {
            return CACHE_RUN
        }
        val run: Boolean = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        CACHE_RUN = run
        CACHE_RUN_AT = SystemClock.elapsedRealtime()
        return run
    }

    /** 能力是否就绪（服务在跑 + 已授权）：AI 工具与 shell 执行的前置条件。 */
    @JvmStatic
    fun isReady(): Boolean {
        return isServiceRunning() && isGranted()
    }

    // ---------------- 授权 ----------------

    /** 发起授权请求（v11+ 由 server 弹系统确认框，结果经 listener 回来）。 */
    @JvmStatic
    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            Logs.w(TAG, "requestPermission failed", t)
        }
    }

    /**
     * 打开管理器主界面（用于「服务未运行」时引导用户去启动服务）。
     * 用反查到的真实包名的 launcher intent；取不到时退回该包的详情页。
     */
    @JvmStatic
    fun openManager(ctx: Context?): Boolean {
        if (ctx == null) {
            return false
        }
        val pkg = resolveManagerPackage(ctx) ?: return false
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
                return true
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "launch manager failed", t)
        }
        try {
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            details.data = Uri.parse("package:" + pkg)
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(details)
            return true
        } catch (t: Throwable) {
            Logs.w(TAG, "open details failed", t)
            return false
        }
    }

    /** 注册授权结果监听（调用方负责在页面销毁时注销）。 */
    @JvmStatic
    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (t: Throwable) {
            Logs.w(TAG, "add listener failed", t)
        }
    }

    /** 注销授权结果监听。 */
    @JvmStatic
    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (t: Throwable) {
            Logs.w(TAG, "remove listener failed", t)
        }
    }

    /** 注册 binder 就绪监听（服务后来才起来时用来刷新状态）。 */
    @JvmStatic
    fun addBinderListener(listener: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.addBinderReceivedListenerSticky(listener)
        } catch (t: Throwable) {
            Logs.w(TAG, "add binder listener failed", t)
        }
    }

    /** 注销 binder 就绪监听。 */
    @JvmStatic
    fun removeBinderListener(listener: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.removeBinderReceivedListener(listener)
        } catch (t: Throwable) {
            Logs.w(TAG, "remove binder listener failed", t)
        }
    }

    // ---------------- 执行 ----------------

    /**
     * 以 adb shell（uid 2000）身份执行一条命令，返回合并后的输出。
     * 【契约】任何失败都返回带括号的说明文案，绝不抛异常 —— 调用方是模型工具与 UI，都受不了异常。
     */
    @JvmStatic
    fun exec(command: String?, timeoutMs: Long): String {
        val cmd = if (command == null) "" else command.trim()
        if (cmd.isEmpty()) {
            return "（空命令）"
        }
        if (!isServiceRunning()) {
            return "（Shizuku 服务未运行，请先启动 Shizuku）"
        }
        if (!isGranted()) {
            return "（未获得 Shizuku 授权）"
        }
        val timeout = if (timeoutMs > 0) timeoutMs else 15000L
        val proc: Process?
        try {
            val m = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process?
        } catch (t: Throwable) {
            Logs.w(TAG, "newProcess failed", t)
            return "（无法启动 shell 进程：" + brief(t) + "）"
        }
        val p = proc ?: return "（无法启动 shell 进程）"
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val tOut = Thread(Runnable {
            outBuf.append(readAll(p.inputStream))
        }, "shizuku-out")
        val tErr = Thread(Runnable {
            errBuf.append(readAll(p.errorStream))
        }, "shizuku-err")
        // 【坑】读线程必须是 daemon：进程被 destroy 后若管道仍被子进程持有，
        //   read() 会永久阻塞；非 daemon 线程会拖住 JVM 且每次超时泄漏两个线程。
        tOut.isDaemon = true
        tErr.isDaemon = true
        try {
            tOut.start()
            tErr.start()
            // 关掉 stdin：否则读 stdin 的命令（如 cat）会一直挂着不退出。
            try {
                val os = p.outputStream
                if (os != null) {
                    os.close()
                }
            } catch (ignored: Throwable) {
            }
            val finished = waitFor(p, timeout)
            if (!finished) {
                try {
                    p.destroy()
                } catch (ignored: Throwable) {
                }
            }
            // 进程结束后流会自然 EOF；给读线程一点收尾时间，避免输出被截掉。
            tOut.join(1200L)
            tErr.join(1200L)
            return format(outBuf.toString(), errBuf.toString(), finished)
        } catch (t: Throwable) {
            Logs.w(TAG, "exec failed", t)
            return "（执行失败：" + brief(t) + "）"
        } finally {
            try {
                p.destroy()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 等待进程结束；优先用 ShizukuRemoteProcess 自带的超时等待，退路是限时 join。 */
    private fun waitFor(proc: Process, timeoutMs: Long): Boolean {
        try {
            val m = proc.javaClass.getMethod(
                "waitForTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java
            )
            val r = m.invoke(proc, timeoutMs, TimeUnit.MILLISECONDS)
            return java.lang.Boolean.TRUE == r
        } catch (ignored: Throwable) {
            // 退路：独立线程 join。
        }
        val done = booleanArrayOf(false)
        val waiter = Thread(Runnable {
            try {
                proc.waitFor()
                done[0] = true
            } catch (ignored: Throwable) {
            }
        }, "shizuku-wait")
        // 同上：退路 waiter 也必须是 daemon，超时后不能拖住进程。
        waiter.isDaemon = true
        waiter.start()
        try {
            waiter.join(timeoutMs)
        } catch (ignored: Throwable) {
        }
        // 【坑】超时后腰断这个线程，否则它会永久挂在 proc.waitFor() 上。
        if (!done[0]) {
            try {
                waiter.interrupt()
            } catch (ignored: Throwable) {
            }
        }
        return done[0]
    }

    /** 合并 stdout / stderr 并限长。 */
    private fun format(out: String?, err: String?, finished: Boolean): String {
        val sb = StringBuilder()
        if (out != null && out.isNotEmpty()) {
            sb.append(out)
        }
        if (err != null && err.isNotEmpty()) {
            if (sb.length > 0) {
                sb.append('\n')
            }
            sb.append("[错误] ").append(err)
        }
        if (!finished) {
            sb.append("\n（超时已中断）")
        }
        var text = sb.toString().trim()
        if (text.isEmpty()) {
            text = "（命令无输出）"
        }
        if (text.length > MAX_OUTPUT_CHARS) {
            text = text.substring(0, MAX_OUTPUT_CHARS) + "\n…（输出已截断）"
        }
        return text
    }

    /** 读流到字符串；异常一律吞掉（读流失败不该让整条命令失败）。 */
    private fun readAll(input: InputStream?): String {
        if (input == null) {
            return ""
        }
        try {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var n = input.read(buf)
            while (n > 0) {
                bos.write(buf, 0, n)
                n = input.read(buf)
            }
            return String(bos.toByteArray(), Charsets.UTF_8)
        } catch (t: Throwable) {
            return ""
        } finally {
            try {
                input.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 异常压成一行短文案（不打印堆栈到用户可见处）。 */
    private fun brief(t: Throwable?): String {
        val msg = t?.message
        return if (msg == null || msg.isEmpty()) t.toString() else msg
    }
}
