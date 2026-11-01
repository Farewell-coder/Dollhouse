package com.dollhouse.app.data

/**
 * 【职责】把「系统分享进来的 AEX 文件」从接收入口 [com.dollhouse.app.AexReceiveActivity]
 *        暂存到内存，交给 [com.dollhouse.app.MainActivity] 去弹导入流程。
 *
 * 【为什么需要这一跳】接收入口是个无界面中转页：读完字节就必须 finish 掉（否则会在
 *   最近任务里留一个空白页，Android 14 的 NoDisplay 主题更是强制要求 onCreate 内结束）。
 *   而导入流程是多级弹窗（凭据 → 解密 → 预览 → 结果），Dialog 必须依附一个长期存在、
 *   已可见的 Activity 窗口，否则中转页一销毁，弹窗跟着消失或直接抛 WindowManager 异常。
 *   ⇒ 中转页只负责把字节搬进内存，MainActivity 负责展示。
 *
 * 【生命周期】纯内存、进程内、取出即清空：
 *   · 不落盘 —— 文件本身仍是敏感物，没必要写文件；
 *   · 不跨进程 —— 只有一个 App 进程在读；
 *   · 取出后立刻置空，重复调用不会二次弹窗（防止 Activity 重建时弹两遍）。
 */
object AexInbox {

    private var pendingBytes: ByteArray? = null
    private var pendingName: String = ""

    /** 存一份待处理文件（覆盖旧的：同一时刻只处理一个分享意图）。 */
    @JvmStatic
    @Synchronized
    fun put(bytes: ByteArray?, name: String?) {
        pendingBytes = bytes
        pendingName = if (name.isNullOrEmpty()) "shared.akcp" else name
    }

    /** 是否有待处理的分享文件。 */
    @JvmStatic
    @Synchronized
    fun has(): Boolean = pendingBytes != null

    /** 取出并清空字节；没有则返回 null。 */
    @JvmStatic
    @Synchronized
    fun take(): ByteArray? {
        val b = pendingBytes
        pendingBytes = null
        return b
    }

    /** 取出并清空文件名（与 [take] 成对调用；取不到时回落默认名）。 */
    @JvmStatic
    @Synchronized
    fun takeName(): String {
        val n = pendingName
        pendingName = ""
        return if (n.isEmpty()) "shared.akcp" else n
    }

    /** 丢弃待处理项（用户取消、或解析前就发现文件不合法时调用）。 */
    @JvmStatic
    @Synchronized
    fun clear() {
        pendingBytes = null
        pendingName = ""
    }
}