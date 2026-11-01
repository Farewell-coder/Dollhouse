package com.dollhouse.app.data

/**
 * 【职责】把「系统分享进来的文件」从接收入口 [com.dollhouse.app.AexReceiveActivity]
 *        暂存到内存，交给 [com.dollhouse.app.MainActivity] 去弹导入流程。
 *
 * 【为什么需要这一跳】接收入口是个无界面中转页：读完字节就必须 finish 掉（否则会在
 *   最近任务里留一个空白页，Android 14 的 NoDisplay 主题更是强制要求 onCreate 内结束）。
 *   而导入流程是多级弹窗（凭据 → 解密 → 预览 → 结果），Dialog 必须依附一个长期存在、
 *   已可见的 Activity 窗口，否则中转页一销毁，弹窗跟着消失或直接抛 WindowManager 异常。
 *   ⇒ 中转页只负责把字节搬进内存，MainActivity 负责展示。
 *
 * 【为什么是列表而不是单个】ACTION_SEND_MULTIPLE 一次可以带多个文件。
 *   v0.1.6 及以前只存了一个（后写覆盖前写），用户一次选 3 个文件分享进来，
 *   实际只有最后一个能导 —— 另外两个被静默丢弃，且用户完全看不出来。
 *   现在改成按批次存整组（字节 + 文件名按下标一一对应）。
 *
 * 【生命周期】纯内存、进程内、取出即清空：
 *   · 不落盘 —— 文件本身仍是敏感物，没必要写文件；
 *   · 不跨进程 —— 只有一个 App 进程在读；
 *   · 取出后立刻置空，重复调用不会二次弹窗（防止 Activity 重建时弹两遍）。
 */
object AexInbox {

    /** 一批待处理文件：字节与文件名按下标一一对应。 */
    private var pendingBytes: MutableList<ByteArray> = ArrayList()
    private var pendingNames: MutableList<String> = ArrayList()

    /**
     * 存一批待处理文件（覆盖旧的：同一时刻只处理一次分享）。
     *
     * @param bytes 各文件字节，顺序即导入顺序
     * @param names 与 [bytes] 等长的文件名；不足时回落默认名
     */
    @JvmStatic
    @Synchronized
    fun putAll(bytes: List<ByteArray>, names: List<String>) {
        pendingBytes = ArrayList(bytes)
        pendingNames = ArrayList()
        for (i in bytes.indices) {
            val n = names.getOrElse(i) { "" }
            pendingNames.add(if (n.isEmpty()) "shared.akcp" else n)
        }
    }

    /** 存单个文件（单文件分享 / 打开方式的便捷入口）。 */
    @JvmStatic
    @Synchronized
    fun put(bytes: ByteArray?, name: String?) {
        if (bytes == null) {
            clear()
            return
        }
        putAll(listOf(bytes), listOf(name ?: ""))
    }

    /** 是否有待处理的分享文件。 */
    @JvmStatic
    @Synchronized
    fun has(): Boolean = pendingBytes.isNotEmpty()

    /** 取出并清空全部字节；没有则返回空列表。 */
    @JvmStatic
    @Synchronized
    fun takeAll(): List<ByteArray> {
        val b = pendingBytes
        pendingBytes = ArrayList()
        return b
    }

    /** 取出并清空全部文件名；没有则返回空列表。 */
    @JvmStatic
    @Synchronized
    fun takeAllNames(): List<String> {
        val n = pendingNames
        pendingNames = ArrayList()
        return n
    }

    /** 取出并清空第一个文件的字节（兼容单文件调用点）。 */
    @JvmStatic
    @Synchronized
    fun take(): ByteArray? {
        val b = pendingBytes.firstOrNull()
        pendingBytes = ArrayList()
        return b
    }

    /** 取出并清空第一个文件名（与 [take] 成对调用）。 */
    @JvmStatic
    @Synchronized
    fun takeName(): String {
        val n = pendingNames.firstOrNull() ?: ""
        pendingNames = ArrayList()
        return if (n.isEmpty()) "shared.akcp" else n
    }

    /** 丢弃待处理项（用户取消、或解析前就发现文件不合法时调用）。 */
    @JvmStatic
    @Synchronized
    fun clear() {
        pendingBytes = ArrayList()
        pendingNames = ArrayList()
    }
}