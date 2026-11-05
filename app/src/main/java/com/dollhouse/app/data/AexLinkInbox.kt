package com.dollhouse.app.data

/**
 * 【职责】把深链接收结果从无界面中转页 [com.dollhouse.app.AexReceiveActivity]
 *        暂存到内存，交给 [com.dollhouse.app.MainActivity] 弹确认框。
 *
 * 【为什么与 AexInbox 分开】文件通道存的是 `List<ByteArray>`（读文件后才有的密文），
 *   深链通道存的是已经解析好的 [AexDeepLink.Result]（明文载荷或错误原因）。
 *   两者的生命周期相同，但类型不同；分开可以避免「深链结果被当成文件字节」的误用。
 *
 * 【生命周期】纯内存、取出即清空；不落盘、不写日志。
 */
object AexLinkInbox {

    private var pending: AexDeepLink.Result? = null

    @JvmStatic
    @Synchronized
    fun put(result: AexDeepLink.Result?) {
        pending = result
    }

    @JvmStatic
    @Synchronized
    fun has(): Boolean = pending != null

    /** 取出并清空；没有则返回 null。 */
    @JvmStatic
    @Synchronized
    fun take(): AexDeepLink.Result? {
        val r = pending
        pending = null
        return r
    }

    @JvmStatic
    @Synchronized
    fun clear() {
        pending = null
    }
}
