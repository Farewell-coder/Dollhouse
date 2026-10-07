package com.dollhouse.app;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】lamda（FIRERPA）设备服务的唯一入口：状态探测、安装 / 启动 / 停止，以及 MCP 工具转发。
 *
 * 【lamda 是什么】开源的 Android 设备自动化服务（github.com/firerpa/lamda），以 shell 身份常驻，
 *        在 65000 端口暴露 HTTP，自带一个 MCP 服务器（/mcp/），把「点按 / 滑动 / 输入 / 开应用 / 读UI树」
 *        这些设备操作包装成标准工具。本类只借用它的服务，不改它的代码，也不依赖它的 Python 客户端。
 *
 * 【为什么走 Shizuku】本机无 root。lamda 官方支持以 shell 身份安装（tar 解到 /data/local/tmp），
 *        而本 App 拿到 shell 身份的唯一通道就是 Shizuku —— 复用既有 ShizukuBridge，不新开权限面。
 *
 * 【包从哪来】服务器包 204MB，不随 APK 分发（会把它从 20MB 撑到 230MB+）。
 *        改为首次使用时下载到 App 自己的外部私有目录 /sdcard/Android/data/<pkg>/files/lamda/，
 *        该目录 App 免权限可写，且 shell 身份可读（shell 持有 ext_data_rw），解包命令能直接吃这个路径。
 *
 * 【坑 1·主线程】状态探测与 MCP 调用都是网络 IO，绝不能在主线程跑；对外只提供异步入口。
 * 【坑 2·服务未起】连不上时任何调用都要快速失败并给可读文案，不能抛异常打断聊天。
 * 【坑 3·无状态 MCP】lamda 的 /mcp/ 不需要 initialize 握手、不需要 session id，一次 POST 即一次调用。
 */
final class LamdaManager {

    /** 服务监听地址（仅本机回环，不对外暴露）。 */
    static final String BASE = "http://127.0.0.1:65000";
    private static final String MCP_URL = BASE + "/mcp/";
    /** 服务器安装根目录：shell 身份走 /data/local/tmp。 */
    static final String SERVER_DIR = "/data/local/tmp/server";
    private static final String LAUNCH = SERVER_DIR + "/bin/launch.sh";
    private static final String PID_FILE = "/data/local/tmp/usr/lamda.pid";
    /** 下载来的包在本 App 外部私有目录下的文件名。 */
    private static final String PKG_NAME = "lamda-server-arm64-v8a.tar.gz";
    private static final String PKG_URL =
            "https://github.com/firerpa/lamda/releases/download/v10.9/lamda-server-arm64-v8a.tar.gz";
    private static final String PKG_SHA =
            "https://github.com/firerpa/lamda/releases/download/v10.9/lamda-server-arm64-v8a.tar.gz.sha256sum";
    /** 官方包体积，用于校验下载是否完整（字节）。 */
    private static final long PKG_SIZE = 213963463L;

    static final int S_ABSENT = 0;
    static final int S_STOPPED = 1;
    static final int S_RUNNING = 2;

    // 【为什么缓存】alive() 是一次 HTTP 探测，而 buildSchema() 在主线程构建 tools schema，
    //   每次发消息都会问一次。服务没起时连接被拒会立即返回，但服务在跑时就是一次真实往返；
    //   2s 缓存把开销压到可忽略，与 ShizukuBridge 的缓存策略一致。
    private static final long CACHE_TTL_MS = 2000L;
    private static boolean CACHE_ALIVE = false;
    private static long CACHE_ALIVE_AT = 0L;

    private LamdaManager() {
    }

    // 【为什么另开一个刷新标记】主线程只准读缓存，绝不准发网络请求。
    private static volatile boolean CACHE_REFRESHING = false;

    /**
     * 带缓存的探活：给主线程构建 schema / 拼提示词用。
     * 【坑】buildSchema 与 devicePrompt 都在主线程跑。服务运行中时一次真实往返要几十毫秒到秒级，
     *   缓存一过期就同步探测 = 每隔 2 秒冻一次界面。策略：主线程只读缓存，
     *   过期就踢一个后台线程去刷新，本次沿用上次结论（后台刷新会在几十毫秒内补正）。
     */
    static boolean aliveCached() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - CACHE_ALIVE_AT < CACHE_TTL_MS) {
            return CACHE_ALIVE;
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            refreshAsync();
            return CACHE_ALIVE;
        }
        return probe();
    }

    /** 同步探活并刷新缓存。只允许在工作线程调用。 */
    static boolean probe() {
        boolean a = alive();
        CACHE_ALIVE = a;
        CACHE_ALIVE_AT = android.os.SystemClock.elapsedRealtime();
        return a;
    }

    /** 后台刷新缓存，同一时间只跑一个。 */
    private static void refreshAsync() {
        if (CACHE_REFRESHING) {
            return;
        }
        CACHE_REFRESHING = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    probe();
                } catch (Throwable ignored) {
                } finally {
                    CACHE_REFRESHING = false;
                }
            }
        }, "lamda-ping").start();
    }

    /** 直接写入探活结论：启停动作在工作线程跑完后调用，让下一次判定立刻正确。 */
    static void markAlive(boolean a) {
        CACHE_ALIVE = a;
        CACHE_ALIVE_AT = android.os.SystemClock.elapsedRealtime();
    }

    /** 作废探活缓存。 */
    static void invalidate() {
        CACHE_ALIVE_AT = 0L;
    }

    /**
     * 设备操作能力的提示词片段，交给 ChatHistoryStore 拼进 system 消息。
     * 【落在哪】不能拼进 PetPrefs.SYSTEM_PROMPT（角色人设原文，属保留内容、一个字不动），
     *   也不该挂在 PetPrefs.localSystemPrompt 上 —— ChatHistoryStore 里那处是常量死分支，永远走不到。
     *   真正生效的注入点是 ChatHistoryStore.buildRequest，本方法只负责产出这段动态文案。
     * 【门控】服务没起时返回空串：提示词里写着会用工具、tools 里却没有，模型只会瞎编。
     */
    static String devicePrompt() {
        if (!aliveCached()) {
            return "";
        }
        return "【手机操作】你手上还有一个 device 工具，可以替主人真的操作这台手机：点按、长按、滑动、"
                + "返回、回主页、唤醒屏幕、打开或关闭应用、输入文字、读写剪贴板，以及读取当前界面结构。"
                + "需要点某个按钮但不知道坐标时，先 observe 读一次界面再决定点哪里，不要凭空猜坐标。"
                + "涉及发消息、付款、删除这类动作，先说清楚你要做什么再动手。";
    }

    /* ------------------------------ 路径 ------------------------------ */

    /** 服务器包在本 App 外部私有目录的落点（免存储权限）。 */
    static File pkgFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            return null;
        }
        File sub = new File(dir, "lamda");
        if (!sub.exists()) {
            sub.mkdirs();
        }
        return new File(sub, PKG_NAME);
    }

    /** 包是否已就绪（存在且体积对得上）。 */
    static boolean pkgReady(Context ctx) {
        File f = pkgFile(ctx);
        return f != null && f.isFile() && f.length() >= PKG_SIZE;
    }

    /**
     * 本地是否已经存在下载产物（完整包、残缺包、或半截 .part 都算）。
     *
     * 【为什么要单独暴露】下载采用「有文件就拒绝」的策略（见 [download]），
     *   UI 必须能在点之前就知道「现在会不会被拒」，把按钮置灰并说明原因，
     *   而不是让用户点一下吃个拒绝再去找原因。
     */
    static boolean hasFile(Context ctx) {
        File f = pkgFile(ctx);
        if (f == null) {
            return false;
        }
        File part = new File(f.getAbsolutePath() + ".part");
        return f.exists() || part.exists();
    }

    /**
     * 「已装过哪个包」的指纹文件（体积 + 修改时间）。
     *
     * 【为什么单独放一个文件】这是本模块自己的临时状态，塞进 feiyu_pet 会污染用户的设置域
     *   （那个 prefs 名与键都是保留内容）；放在包同目录下，删除包时顺手一起清掉。
     */
    private static File stampFile(Context ctx) {
        File f = pkgFile(ctx);
        if (f == null) {
            return null;
        }
        return new File(f.getParentFile(), "installed.stamp");
    }

    private static String stampOf(Context ctx) {
        File f = pkgFile(ctx);
        return f == null ? "" : f.length() + ":" + f.lastModified();
    }

    /** 记下当前包已成功解包。 */
    private static void markInstalled(Context ctx) {
        File s = stampFile(ctx);
        if (s == null) {
            return;
        }
        try {
            FileOutputStream fos = new FileOutputStream(s);
            fos.write(stampOf(ctx).getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** 忘了「装过」这件事（删除时调用）。 */
    private static void forgetInstalled(Context ctx) {
        File s = stampFile(ctx);
        if (s != null && s.exists()) {
            s.delete();
        }
    }

    /**
     * 当前这个包跟上次解包的是不是同一个。
     * 【为什么要它】旧版本没有指纹，只能靠体积+时间戳「像不像同一个」来判断；
     *   指纹缺失（升级上来的用户）时返回 false，即当作「变了，允许重装」，宁可多解一次也不错拦。
     */
    private static boolean pkgChanged(Context ctx) {
        File s = stampFile(ctx);
        if (s == null || !s.isFile()) {
            return true;
        }
        try {
            FileInputStream fis = new FileInputStream(s);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            fis.close();
            String old = new String(bos.toByteArray(), "UTF-8").trim();
            return !old.equals(stampOf(ctx));
        } catch (Throwable t) {
            return true;
        }
    }

    /* ------------------------------ 状态 ------------------------------ */

    /** 服务是否活着（HTTP 探活，1.5s 上限）。任何异常一律当没起。 */
    static boolean alive() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + "/").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            int code = conn.getResponseCode();
            return code > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 服务器文件是否已解包（只查 launch.sh 在不在，不跑 shell）。 */
    static boolean installed(Context ctx) {
        String out = ShizukuBridge.exec("test -f " + LAUNCH + " && echo Y || echo N", 8000L);
        return out != null && out.contains("Y");
    }

    static String stateText(int state) {
        switch (state) {
            case S_RUNNING:
                return "运行中";
            case S_STOPPED:
                return "已安装，未运行";
            default:
                return "未安装";
        }
    }

    /* ------------------------------ 动作互斥 ------------------------------ */

    /**
     * 当前正在跑的耗时动作名；null = 空闲。
     *
     * 【防的是什么】下载 / 解包 / 启停都是「起线程 + 长时间跑」的动作，用户连点两下就会起两份：
     *   - 两个线程同时写同一个 .part → 文件交错，写出一个既有头又有尾的坏包；
     *   - 两次 tar 同时解到同一个目录 → 解出半截文件，服务起不来且难排查；
     *   - 启动与停止同时跑 → 各自探活一次，界面显示与实际状态相反。
     *   这些都不是「多点一次没事」的小问题，是会留下脏状态的 bug，必须从入口拦住。
     *
     * 【为什么用标记而不是 synchronized】动作本身跑在各自的工作线程里，锁要跨线程可见；
     *   而且被拒的一方需要知道「在忙什么」好给用户一句人话，标记能直接表达这件事。
     */
    private static String BUSY_ACTION = null;

    /**
     * 尝试独占一个动作。
     * @return null = 占到了，可以干活；非 null = 当前在跑的动作名（调用方据此提示）。
     */
    private static String tryBegin(String action) {
        synchronized (LamdaManager.class) {
            if (BUSY_ACTION != null) {
                return BUSY_ACTION;
            }
            BUSY_ACTION = action;
            return null;
        }
    }

    /** 释放独占。放在 finally 里，保证异常路径也能解锁，否则会永久卡住所有动作。 */
    private static void endAction() {
        synchronized (LamdaManager.class) {
            BUSY_ACTION = null;
        }
    }

    /** 当前是否有动作在跑（页面用来把按钮置灰、以及给出「正在下载」这类提示）。 */
    static String busyAction() {
        return BUSY_ACTION;
    }

    /** 动作名的中文说法。 */
    private static String actionLabel(String action) {
        if (action == null) {
            return "";
        }
        if ("download".equals(action)) {
            return "下载";
        }
        if ("install".equals(action)) {
            return "解包";
        }
        if ("start".equals(action)) {
            return "启动";
        }
        if ("stop".equals(action)) {
            return "停止";
        }
        if ("delete".equals(action)) {
            return "删除";
        }
        return action;
    }

    /** 「在忙」的统一回复文案。 */
    private static String busyText(String busy) {
        return "（正在" + actionLabel(busy) + "，请等它跑完再操作）";
    }

    /* ------------------------------ 动作（都走 Shizuku，同步返回文案） ------------------------------ */

    /** 解包安装：把外部私有目录里的包解到 /data/local/tmp。 */
    static String install(Context ctx) {
        String busy = tryBegin("install");
        if (busy != null) {
            return busyText(busy);
        }
        try {
            File f = pkgFile(ctx);
            if (f == null || !f.isFile()) {
                return "（还没下载服务器包）";
            }
            if (!ShizukuBridge.isReady()) {
                return "（需要先开启 Shizuku 授权）";
            }
            // 【为什么要拦重复解包】tar 覆盖解包本身是幂等的，不会解坏；但一次解包要写两百兆、
            //   耗时几十秒，重复点只是白等。已装且包没变过就直接回执，省掉这一次无用功；
            //   真的想重装，页面上有「删除」能把它清掉再来。
            if (installed(ctx) && !pkgChanged(ctx)) {
                return "已经安装过了（要重装请先用下面的「删除」清掉）";
            }
            // tar 用绝对路径，避免依赖工作目录。
            String cmd = "tar -C /data/local/tmp -xzf '" + f.getAbsolutePath() + "' && test -f "
                    + LAUNCH + " && echo INSTALL_OK";
            String out = ShizukuBridge.exec(cmd, 300000L);
            if (out != null && out.contains("INSTALL_OK")) {
                // 记住这次装的是哪个包，供上面那条重复判定使用。
                markInstalled(ctx);
                return "安装完成";
            }
            return "安装失败：" + out;
        } finally {
            endAction();
        }
    }

    /** 启动服务。launch.sh 会把进程转入后台并立刻返回。 */
    static String start() {
        String busy = tryBegin("start");
        if (busy != null) {
            return busyText(busy);
        }
        try {
            if (!ShizukuBridge.isReady()) {
                return "（需要先开启 Shizuku 授权）";
            }
            // 已经在跑就别再拉一次：再跑一遍 launch.sh 会得到一个「已经在运行」的分支，
            // 白等 3 秒不说，还可能让确认流程误判。
            if (alive()) {
                markAlive(true);
                return "服务已经在运行";
            }
            String out = ShizukuBridge.exec("sh " + LAUNCH + " 2>&1 | tail -3", 60000L);
            // 【为什么还要等 3 秒】launch.sh 是 `exec python3 -m lamda --launch`，进程转后台后脚本立刻返回，
            //   但服务把 65000 端口监听起来还要几秒。不等就探活必然报「启动失败」，属于假失败。
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            boolean up = alive();
            markAlive(up);
            return up ? "已启动" : "启动失败：" + out;
        } finally {
            endAction();
        }
    }

    /** 停止服务：读 pid 文件后 kill，杀不到就退回按进程名找。 */
    static String stop() {
        String busy = tryBegin("stop");
        if (busy != null) {
            return busyText(busy);
        }
        try {
            if (!ShizukuBridge.isReady()) {
                return "（需要先开启 Shizuku 授权）";
            }
            String cmd = "P=$(cat " + PID_FILE + " 2>/dev/null); "
                    + "[ -n \"$P\" ] && kill $P 2>/dev/null; "
                    + "sleep 1; "
                    // 【为什么写 [l]amda 而不是 lamda】pkill -f 会匹配到本命令自身所在的 sh 命令行，
                    //   用字符类打断字面量即可让自己不被匹配，同时仍能杀掉 lamda 的进程树（含被收养的子进程）。
                    + "pkill -f '[l]amda' 2>/dev/null; "
                    + "sleep 1; echo DONE";
            ShizukuBridge.exec(cmd, 30000L);
            // 进程退出到端口释放有一两秒的窗口，早探一次会把「已停止」误报成「停止失败」。
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            boolean up = alive();
            markAlive(up);
            return up ? "停止失败（服务仍在响应）" : "已停止";
        } finally {
            endAction();
        }
    }

    /**
     * 彻底删除：停服务 → 删掉解包出来的东西 → 删掉下载的包。
     *
     * 【为什么三样一起删】用户点「删除」的意图是「把这个功能清干净」。
     *   只删包会留下几万个文件在 /data/local/tmp 里占着地方；只删解包结果则白占 204MB 存储。
     *
     * 【顺序不能反】必须先把进程停掉再删目录 —— 对正在被执行的目录做 rm -rf，
     *   在 Linux 上会让那个进程后续的任何文件访问直接 ENOENT，日志刷屏且未必收得到信号。
     */
    static String deleteAll(Context ctx) {
        String busy = tryBegin("delete");
        if (busy != null) {
            return busyText(busy);
        }
        try {
            StringBuilder sb = new StringBuilder();
            // 1) 先停：无论有没有 Shizuku 都值得试一次（能连上才杀得掉）。
            if (ShizukuBridge.isReady()) {
                String cmd = "P=$(cat " + PID_FILE + " 2>/dev/null); "
                        + "[ -n \"$P\" ] && kill $P 2>/dev/null; "
                        + "sleep 1; "
                        + "pkill -f '[l]amda' 2>/dev/null; "
                        + "sleep 1; "
                        + "rm -rf " + SERVER_DIR + " /data/local/tmp/usr /data/local/tmp/server.part; "
                        + "test -e " + LAUNCH + " && echo STILL_THERE || echo CLEAN_OK";
                String out = ShizukuBridge.exec(cmd, 60000L);
                if (out != null && out.contains("CLEAN_OK")) {
                    sb.append("已移除安装的服务");
                } else {
                    sb.append("服务文件清理未确认");
                }
            } else {
                sb.append("未连接 Shizuku，只删了下载的包");
            }
            // 2) 停完再删下载的包（含可能的半截 .part）。
            File f = pkgFile(ctx);
            if (f != null) {
                File part = new File(f.getAbsolutePath() + ".part");
                if (part.exists()) {
                    part.delete();
                }
                if (f.exists()) {
                    if (f.delete()) {
                        sb.append("；服务器包已删除");
                    } else {
                        sb.append("；服务器包删除失败");
                    }
                }
            }
            // 3) 清掉「装过哪个包」的指纹，避免下次下载后误判成「已安装过」。
            forgetInstalled(ctx);
            // 4) 缓存直接置否，让工具门控与状态行立刻反映「没了」。
            markAlive(false);
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (alive()) {
                sb.append("；但服务仍在响应，可能有多份实例");
            }
            return sb.toString();
        } finally {
            endAction();
        }
    }

    /* ------------------------------ 自动保活 ------------------------------ */

    /** 两次自动拉起之间的最小间隔：探活是 60s 一次，拉起失手后不必每次都去撞。 */
    private static final long GUARD_MIN_GAP_MS = 120000L;
    /** 用户手动「停止服务」后的静默期：这期间无论开关怎么开都不自动拉起。 */
    private static final long GUARD_SUPPRESS_MS = 600000L;
    private static volatile long GUARD_LAST_TRY = 0L;
    private static volatile long GUARD_SUPPRESS_UNTIL = 0L;
    private static volatile boolean GUARD_RUNNING = false;

    /** 用户手动停服务：进入静默期，别再把它拽回来。 */
    static void suppressGuard() {
        GUARD_SUPPRESS_UNTIL = android.os.SystemClock.elapsedRealtime() + GUARD_SUPPRESS_MS;
    }

    /** 用户手动启动：清掉静默期，恢复自动保活。 */
    static void resumeGuard() {
        GUARD_SUPPRESS_UNTIL = 0L;
        GUARD_LAST_TRY = 0L;
    }

    /**
     * 自动保活的一次巡检（由 PetService 每 60 秒调一次）。
     *
     * 【为什么挂在 PetService 上】lamda 只是个 shell 进程，系统随时可以回收它，
     *   单靠它自己没有任何自愈能力；而桌宠本身就是前台服务、必然活着。
     *   借它的心跳做巡检，不用再新开 Service / 新注册常驻组件，这是最小改动。
     *
     * 【分层】人偶掉了由 keepalive 那套负责拉回来；人偶活着时由本方法负责把 lamda 拉回来。
     *
     * 【线程】本方法只做廉价判定（读缓存 / 读标记），真正的探活与拉起丢到工作线程，
     *   绝不阻塞主线程（PetService 的 ui Handler 在上面）。
     */
    static void guardTick(Context ctx) {
        if (ctx == null || GUARD_RUNNING) {
            return;
        }
        if (!PetPrefs.lamdaAutoStart(ctx)) {
            return;
        }
        if (aliveCached()) {
            return;
        }
        // 【为什么先查本地文件】installed() 要走一次 Shizuku 执行 shell。用户没下过包时
        //   那必然没装，先在本地排掉，未使用的用户就完全不会被周期性 shell 调用打扰。
        if (!pkgReady(ctx)) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < GUARD_SUPPRESS_UNTIL) {
            return;
        }
        if (now - GUARD_LAST_TRY < GUARD_MIN_GAP_MS) {
            return;
        }
        GUARD_LAST_TRY = now;
        GUARD_RUNNING = true;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 再确认一次：缓存可能是陈旧的，别为一次误判就去重启一个活着的服务。
                    if (probe()) {
                        return;
                    }
                    // 【为什么在这里才查 Shizuku】刚开机那几秒 Shizuku 往往还没就绪，
                    //   直接算一次失败会白等 2 分钟。就绪检查很便宜，不成就把间隔重置，
                    //   让下一轮巡检（60s 后）立刻重试。
                    if (!ShizukuBridge.isReady()) {
                        GUARD_LAST_TRY = 0L;
                        return;
                    }
                    // 没解包过就别乱拉，跑起 launch.sh 只会得到一句报错。
                    // 包在但没解包是「等用户动手」的状态，压 5 分钟再试，别每轮都去打一次 Shizuku。
                    if (!installed(app)) {
                        GUARD_SUPPRESS_UNTIL = android.os.SystemClock.elapsedRealtime()
                                + GUARD_SUPPRESS_MS / 2;
                        return;
                    }
                    start();
                } catch (Throwable ignored) {
                } finally {
                    GUARD_RUNNING = false;
                }
            }
        }, "lamda-guard").start();
    }

    /* ------------------------------ MCP 转发 ------------------------------ */

    /**
     * 调用 lamda 内置 MCP 服务器的一个工具。
     * 【契约】任何失败都返回「（...）」文案，绝不抛异常 —— 调用方是模型工具，受不了异常。
     */
    static String mcpCall(String tool, JSONObject args, long timeoutMs) {
        HttpURLConnection conn = null;
        try {
            JSONObject params = new JSONObject();
            params.put("name", tool);
            params.put("arguments", args == null ? new JSONObject() : args);
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", "tools/call");
            req.put("params", params);

            conn = (HttpURLConnection) new URL(MCP_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout((int) Math.max(3000L, timeoutMs));
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            byte[] body = req.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return "（lamda 返回 HTTP " + code + "）";
            }
            String text = readAll(conn.getInputStream());
            String out = textOf(text);
            return out == null ? "（lamda 没有返回内容）" : out;
        } catch (Throwable t) {
            return "（连不上 lamda 服务，先在「lamda 设备控制」页启动它）";
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 从 MCP 回包里取出 result.content[0].text；取不到返 null。 */
    static String textOf(String body) {
        if (body == null || body.length() == 0) {
            return null;
        }
        String json = body.trim();
        // 兼容 SSE 形态（data: {...}）：取最后一行 data 负载。
        if (!json.startsWith("{")) {
            int at = json.lastIndexOf("data:");
            if (at < 0) {
                return null;
            }
            json = json.substring(at + 5).trim();
            int nl = json.indexOf('\n');
            if (nl > 0) {
                json = json.substring(0, nl).trim();
            }
        }
        try {
            JSONObject obj = new JSONObject(json);
            JSONObject result = obj.optJSONObject("result");
            if (result == null) {
                return null;
            }
            JSONArray content = result.optJSONArray("content");
            if (content == null || content.length() == 0) {
                return null;
            }
            JSONObject first = content.optJSONObject(0);
            if (first == null) {
                return null;
            }
            return first.optString("text", "");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
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

    /* ------------------------------ 下载 ------------------------------ */

    /** 下载进度回调。 */
    interface Progress {
        void onProgress(long done, long total);

        void onDone(boolean ok, String detail);
    }

    /**
     * 「有文件就拒绝下载」的说明文案。
     *
     * 【为什么要分两种说法】同样是拒绝，原因不同、用户该做的事也不同：
     *   已经下全了 → 直接去解包就行；只下了半截 → 得先删掉再来。
     *   给一句「已拒绝」而不说清是哪种，用户只会反复点。
     */
    static String downloadRefusedText(Context ctx) {
        if (pkgReady(ctx)) {
            return "已经下载过了，直接点「解包安装」即可";
        }
        return "本地已有未完成的下载文件，请先点「删除」清掉再下载";
    }

    /**
     * 下载服务器包到外部私有目录。
     * 【为什么不用 DownloadManager】它落点是公共下载目录，拿路径还得查库；
     *        这里只需要一个 shell 可读的绝对路径，自己流式写更直接。
     *
     * 【策略·有文件就拒绝】本地只要存在服务器包或它的半截 .part，就一律不下。
     *   理由：覆盖写一个 204MB 的文件既费流量又费时间，而两种残留态各有更合适的出路
     *   （下全了 → 去解包；下残了 → 先删）。不静默覆盖、不静默续传，
     *   让「该干什么」始终由用户明确决定，避免出现「明明下过却提示在下载」这类含糊状态。
     */
    static void download(final Context ctx, final Progress cb) {
        final File out = pkgFile(ctx);
        if (out == null) {
            cb.onDone(false, "取不到应用存储目录");
            return;
        }
        // 【第一重·起线程前就拒】这里拒掉能省一次白起的线程，也让拒绝更快返回。
        if (hasFile(ctx)) {
            cb.onDone(false, downloadRefusedText(ctx));
            return;
        }
        // 【第二重·互斥】连点两下时，第二下会被这里挡住，不会起第二个下载线程。
        final String busy = tryBegin("download");
        if (busy != null) {
            cb.onDone(false, busyText(busy));
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                File tmp = new File(out.getAbsolutePath() + ".part");
                try {
                    // 【第三重·线程内再拒】「查过」到「真正开始写」之间有一小段窗口，
                    //   期间上一轮的线程可能刚好落下文件、或用户在别处又触发了一次。
                    //   三道门都过去才允许写盘，任一道不过就立刻退出，绝不覆盖既有文件。
                    if (hasFile(ctx)) {
                        cb.onDone(false, downloadRefusedText(ctx));
                        return;
                    }
                    conn = (HttpURLConnection) new URL(PKG_URL).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        cb.onDone(false, "下载失败：HTTP " + code);
                        return;
                    }
                    long total = conn.getContentLengthLong();
                    InputStream in = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(tmp);
                    byte[] buf = new byte[65536];
                    long done = 0;
                    int n;
                    long last = 0;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 500L) {
                            last = now;
                            cb.onProgress(done, total > 0 ? total : PKG_SIZE);
                        }
                    }
                    fos.flush();
                    fos.close();
                    in.close();
                    if (tmp.length() < PKG_SIZE) {
                        tmp.delete();
                        cb.onDone(false, "下载不完整（" + tmp.length() + " 字节）");
                        return;
                    }
                    if (out.exists()) {
                        out.delete();
                    }
                    if (!tmp.renameTo(out)) {
                        cb.onDone(false, "写入失败");
                        return;
                    }
                    cb.onDone(true, "下载完成");
                } catch (Throwable t) {
                    try {
                        if (tmp.exists()) {
                            tmp.delete();
                        }
                    } catch (Throwable ignored) {
                    }
                    cb.onDone(false, "下载失败：" + brief(t));
                } finally {
                    // 无论成功失败都必须解锁，否则一次网络中断会让这页所有动作永久卡住。
                    endAction();
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "lamda-dl").start();
    }

    /** 官方校验文件地址（UI 上作为备用说明展示，不做自动校验以免多一次网络依赖）。 */
    static String checksumUrl() {
        return PKG_SHA;
    }

    private static String brief(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.isEmpty() ? String.valueOf(t) : m;
    }
}
