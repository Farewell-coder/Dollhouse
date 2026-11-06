package com.dollhouse.app.ui.chat

/**
 * 【职责】消息**多选**态的跨气泡状态（r13）。
 *
 * 【为什么单独一个文件（M8）】它原来住在 `MessageMenu.kt` 文件末尾。那次迁移把
 *   `MessageMenu`（原生 Dialog 菜单，257 行 View）整体删掉后，**同文件里的这个对象
 *   也被一并删掉了** —— 而 `ChatUi` 有 9 处引用它，于是整轮 CI 报
 *   `Unresolved reference 'MultiSelect'`。这是「按文件删代码」时的典型误伤：
 *   删除依据是「MessageMenu 这个 symbol 有没有人用」，没有覆盖「文件里还有别的公开符号」。
 *   教训写在这里：**按文件删除前必须枚举文件内的全部公开声明，逐个查引用**。
 *   现在它独立成文件，与弹层实现解耦，今后改哪个都不会牵连另一个。
 *
 * 【为什么需要共享状态】多选是「列表级」行为：进入多选后，点**任意**气泡都是勾选，
 *   而每个气泡是各自独立的 Composable，互相看不到对方的状态。
 *   放在这里当全局状态，气泡只需读 [on] / 调 [toggle]。
 *
 * 【为什么不用 Compose 状态】多选由**命令式**的长按菜单触发（见 `ui/kit/DhDialogHost`），
 *   不是 Composable 内部事件；用普通对象最直接。
 *   Compose 侧读它不会自动重组，但多选的视觉反馈只在「选中项」上体现，
 *   而选中项变化必然伴随列表重组（`ChatState.items` 是 Compose 状态），够用。
 *
 * 【怎么退出】再次长按任意消息 → 菜单里再点「多选」= 取消；
 *   或发消息 / 切会话时由调用方 [reset]。当前实现刻意做得最简：不额外加 UI 元素。
 */
object MultiSelect {
    /** 是否处于多选态。 */
    @JvmField
    var on: Boolean = false

    /** 已勾选的消息下标（对应 `ChatPanel.history` 的下标）。 */
    private val picked = LinkedHashSet<Int>()

    /** 勾选 / 取消勾选一条。 */
    @JvmStatic
    fun toggle(index: Int) {
        if (index < 0) {
            return
        }
        if (!picked.add(index)) {
            picked.remove(index)
        }
    }

    /** 当前已勾选的下标快照（升序）。 */
    @JvmStatic
    fun picked(): List<Int> {
        val out = ArrayList<Int>(picked)
        out.sort()
        return out
    }

    /** 退出多选并清空勾选。 */
    @JvmStatic
    fun reset() {
        on = false
        picked.clear()
    }
}