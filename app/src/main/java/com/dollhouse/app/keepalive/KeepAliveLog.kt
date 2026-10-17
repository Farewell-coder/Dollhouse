package com.dollhouse.app.keepalive

import com.dollhouse.app.core.Logs

/**
 * 【职责】保活模块的日志通道。
 *
 * 【r15·改为统一开关】原来这里是**独立的**编译期常量开关（`const val ON = false`），
 *   于是排障时有两个坑：
 *   ① 想同时看保活日志和主流程日志，得改两处常量、重编两次；
 *   ② 用户在真机上遇到「保活没生效」，我们连一次调度记录都看不到 ——
 *       而保活恰恰是最依赖现场信息的模块（系统调度、厂商杀后台、权限状态）。
 *   现在统一委托给 [Logs]（运行期开关，设置 → 实验 → 开发者选项 里打开），
 *   一处开关全模块生效、无需重编重装、默认仍然关闭。
 *
 * 【开销】不再是编译期常量后 R8 无法整段消除，所以 [d] / [w] 都先判开关再拼消息。
 *   保活日志只在**状态变化**时打印（调度/启停/异常），频次极低，可忽略。
 */
internal object KeepAliveLog {

    private const val TAG = "Dollhouse"

    /** 日志是否开启（转发给全局开关）。 */
    fun on(): Boolean {
        return Logs.isOn()
    }

    fun d(msg: String) {
        Logs.i(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        Logs.w(TAG, msg, t)
    }
}