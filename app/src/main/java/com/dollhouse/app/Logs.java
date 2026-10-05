package com.dollhouse.app;

/**
 * 【职责】日志出口的唯一收口点。
 *
 * 【为何收口】改造前工程里散落 106 处 android.util.Log 直接调用：
 *   - 约 60 处是 catch 块里的 Log.w(tag, "ignored", t)，纯噪声，release 包也在打印；
 *   - 约 40 处是带字符串拼接的调试日志，未开启 R8 时拼接每次都会真实执行，白烧 CPU。
 *   收口到本类后，ON 为编译期常量 false，R8 会把调用连同参数拼接一起消掉，
 *   release 包既不打日志也不做拼接；排障时把 ON 改 true 重编即可恢复全部日志。
 *
 * 【边界】只做转发，不引入任何依赖；异常永远不外抛。
 */
final class Logs {

    /** release 恒为 false；排障时临时改 true 重编。 */
    static final boolean ON = false;

    private Logs() {
    }

    static void i(String tag, String msg) {
        if (ON) {
            android.util.Log.i(tag, msg);
        }
    }

    static void w(String tag, String msg) {
        if (ON) {
            android.util.Log.w(tag, msg);
        }
    }

    static void w(String tag, String msg, Throwable t) {
        if (ON) {
            android.util.Log.w(tag, msg, t);
        }
    }

    static void e(String tag, String msg, Throwable t) {
        if (ON) {
            android.util.Log.e(tag, msg, t);
        }
    }
}