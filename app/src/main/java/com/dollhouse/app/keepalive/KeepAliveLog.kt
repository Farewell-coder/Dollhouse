package com.dollhouse.app.keepalive

import android.util.Log

/**
 * 【职责】保活模块的独立 debug 日志通道。
 *
 * 【为什么不用 Logs】存量 Logs 的 ON 是编译期常量，release 下方法体被 R8 清空；
 *   保活模块需要一个可单独开关的通道，真机排障时不必连带打开主流程日志。
 *
 * 【坑】ON 必须是 const val（编译期常量），R8 才能把调用点整体内联成空操作；
 *   写成普通 val 会在 release 包里留下日志字符串常量。
 */
internal object KeepAliveLog {
    const val ON = false
    private const val TAG = "Dollhouse"

    fun d(msg: String) {
        if (ON) {
            Log.d(TAG, msg)
        }
    }

    fun w(msg: String, t: Throwable? = null) {
        if (ON) {
            Log.w(TAG, msg, t)
        }
    }
}
