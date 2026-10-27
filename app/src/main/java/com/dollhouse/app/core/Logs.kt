package com.dollhouse.app.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 【职责】日志出口的唯一收口点。
 *
 * 【为何收口】改造前工程里散落 106 处 android.util.Log 直接调用：
 *   - 约 60 处是 catch 块里的 Log.w(tag, "ignored", t)，纯噪声，release 包也在打印；
 *   - 约 40 处是带字符串拼接的调试日志，未开启 R8 时拼接每次都会真实执行，白烧 CPU。
 *   收口到本类后统一走这一处开关。
 *
 * 【r15·改为运行期开关】原来是 `const val ON = false`（编译期常量），代价是
 *   **release 包永远打不出日志** —— 用户报「人偶突然没了」时一条线索都没有，
 *   只能靠 dumpsys / 网络统计旁敲侧击，排障极其被动（工程里踩过这个坑）。
 *   现在改成「读 prefs 的运行期开关」：
 *   · 默认仍为**关闭**，release 包默认行为与改造前一致（不打扰用户、不费性能）；
 *   · 需要排障时在「设置 → 实验 → 开发者日志」里打开，立刻生效、无需重编重装；
 *   · 读值带 1 秒内存缓存，避免热路径（帧循环 / 网络回调）每次都读 prefs。
 *
 * 【与 R8 的关系】开关不再是编译期常量，R8 无法再消掉调用与参数拼接 ——
 *   所以 [msg] 提供了惰性求值重载，关闭时**不构造字符串**，
 *   把「不能消掉」的那点开销压到最低。
 *
 * 【边界】只做转发，不引入任何依赖；异常永远不外抛。
 */
object Logs {

    private const val TAG_PREFS = "feiyu_logs"
    private const val KEY_ON = "log_on"

    /** 缓存有效期（毫秒）：热路径下避免每次都读 prefs。 */
    private const val CACHE_MS = 1000L

    @Volatile
    private var cached = false

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var app: Context? = null

    /** 由 Application.onCreate 尽早调用，装上下文以便读开关。 */
    @JvmStatic
    fun install(ctx: Context?) {
        app = ctx?.applicationContext
    }

    /** 当前开关状态（带缓存）。未 install 时按「关」处理，不抛异常。 */
    @JvmStatic
    fun isOn(): Boolean {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) {
            return cached
        }
        val a = app
        val v = if (a == null) {
            false
        } else {
            try {
                prefs(a).getBoolean(KEY_ON, false)
            } catch (ignored: Throwable) {
                false
            }
        }
        cached = v
        cachedAt = now
        return v
    }

    /** 设置开关（界面调用）；立刻刷新缓存，无需等过期。 */
    @JvmStatic
    fun setOn(ctx: Context?, on: Boolean) {
        val a = ctx?.applicationContext ?: return
        try {
            prefs(a).edit().putBoolean(KEY_ON, on).apply()
        } catch (ignored: Throwable) {
        }
        cached = on
        cachedAt = System.currentTimeMillis()
    }

    private fun prefs(ctx: Context): SharedPreferences {
        // 独立 prefs，不挤进 feiyu_pet 的键空间（与 feiyu_ocr 同款做法）。
        return ctx.getSharedPreferences(TAG_PREFS, 0)
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        if (isOn()) {
            Log.i(tag, msg)
        }
        DiagnosticsStore.record(app, DiagnosticsStore.Severity.INFO, tag, msg)
    }

    /** 惰性版：关闭时不构造字符串（热路径优先用这个）。 */
    @JvmStatic
    fun i(tag: String, msg: () -> String) {
        if (isOn()) {
            val text = msg()
            Log.i(tag, text)
            DiagnosticsStore.record(app, DiagnosticsStore.Severity.INFO, tag, text)
        }
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        if (isOn()) {
            Log.w(tag, msg)
        }
        DiagnosticsStore.record(app, DiagnosticsStore.Severity.WARNING, tag, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String, t: Throwable?) {
        if (isOn()) {
            Log.w(tag, msg, t)
        }
        DiagnosticsStore.record(app, DiagnosticsStore.Severity.WARNING, tag, msg, t)
    }

    @JvmStatic
    fun e(tag: String, msg: String, t: Throwable?) {
        if (isOn()) {
            Log.e(tag, msg, t)
        }
        DiagnosticsStore.record(app, DiagnosticsStore.Severity.ERROR, tag, msg, t)
    }
}