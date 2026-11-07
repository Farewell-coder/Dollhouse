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

/**
 * 【职责】搜索命中的**高亮下标**（M-修复断链）。
 *
 * 【为什么需要它】`ChatPanel` 的搜索功能一直会写 `highlightIndex = idx`，
 *   并注释说「滚动到该条并闪烁高亮（由 renderHistory 的 highlightIndex 控制）」——
 *   但 Compose 侧 `ChatUi` **从未读取过这个字段**，`ChatItem` 里也没有高亮属性。
 *   也就是说「搜索命中后高亮那条消息」这个功能实际是**断的**：
 *   用户搜到结果，只看到一句「找到第 N 条」，看不到是哪一条被找到。
 *
 * 【为什么用全局对象而不是给 ChatItem 加字段】
 *   给 `ChatItem` 加 `highlight: Boolean` 需要改 5 处构造点，且它是「当前搜索态」——
 *   属于**瞬时 UI 状态**，不是消息内容本身，塞进消息模型会让缓存/序列化语义变脏。
 *   与 [MultiSelect] 同型：跨气泡共享、由命令式代码写入、Compose 读取。
 *
 * 【怎么消除】高亮是短时的：写入后由 UI 侧在若干秒后自动清掉
 *   （见 ChatUi 里的 LaunchedEffect），避免它一直挂在某条消息上。
 */
object SearchHighlight {
    /** 当前高亮的消息下标（-1 = 无）。 */
    @JvmField
    var index: Int = -1

    /** 设置高亮；-1 表示清除。 */
    @JvmStatic
    fun set(i: Int) {
        index = i
    }

    /** 清除高亮。 */
    @JvmStatic
    fun clear() {
        index = -1
    }
}
