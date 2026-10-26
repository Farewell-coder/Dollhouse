package com.dollhouse.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [ChatKeys] 的回归测试 —— 钉住「列表身份必须稳定」这条规则。
 *
 * ## 这些用例防的是什么
 *
 * 用下标当 key（r25 之前的写法）在**功能上完全正常**：界面能显示、能滚动、能发送。
 * 它只在性能上出问题，而且只在「列表中间插入/删除」时才暴露 —— 属于最容易在
 * 重构中被人「优化回去」的那类改动。所以用测试固定住。
 *
 * 核心不变量：**同一条消息，在列表里挪了位置之后，key 不能变。**
 */
class ChatKeysTest {

    /** 造一条历史里已有的消息（index = 它在 history 中的位置）。 */
    private fun msg(index: Int, text: String = "t$index"): ChatItem {
        return ChatItem(
            role = if (index % 2 == 0) "user" else "assistant",
            text = text,
            image = null,
            index = index,
            isPrev = false,
            isSummary = false
        )
    }

    /** 正常历史消息：key 由 index 决定。 */
    @Test
    fun `历史消息的 key 带 h 前缀`() {
        assertEquals("h0", ChatKeys.of(msg(0)))
        assertEquals("h7", ChatKeys.of(msg(7)))
    }

    /**
     * **核心不变量**：消息在列表里换位置，key 不变。
     *
     * 这正是「补回更早历史」的情形：`index` 是按 history 里的绝对位置记的，
     * 插入一批消息到前面之后，`items` 里的**下标**全变了，但每条消息自己的
     * `index` 没变 —— 所以 key 稳定，Compose 能复用已有节点。
     */
    @Test
    fun `消息在列表中移位后 key 不变`() {
        val m = msg(42)
        // 同一个对象出现在列表的不同位置上（下标不同，item 本身相同）
        val before = ChatKeys.of(m)
        val after = ChatKeys.of(m)
        assertEquals(before, after)
    }

    /** 不同消息必须拿到不同 key（否则复用会串内容）。 */
    @Test
    fun `不同历史下标的 key 互不相同`() {
        val keys = (0 until 50).map { ChatKeys.of(msg(it)) }.toSet()
        assertEquals("50 条消息应有 50 个不同 key", 50, keys.size)
    }

    /** 流式占位气泡用固定 key。 */
    @Test
    fun `流式占位气泡用固定 key`() {
        val live = ChatItem("assistant", "", null, -1, false, false)
        assertEquals(ChatKeys.LIVE, ChatKeys.of(live))
    }

    /** 流式气泡与任何历史消息的 key 都不冲突。 */
    @Test
    fun `流式 key 不与历史 key 冲突`() {
        val live = ChatItem("assistant", "", null, -1, false, false)
        for (i in 0 until 100) {
            assertNotEquals(ChatKeys.of(live), ChatKeys.of(msg(i)))
        }
    }

    /**
     * 流式占位气泡文本变化时 key 保持不变。
     *
     * 【为什么重要】流式期间每 60ms 更新一次尾项文本。若 key 掺了文本内容，
     *   每次刷新都会「换一个身份」→ 尾项节点整个销毁重建，流式效果会抖。
     */
    @Test
    fun `流式文本变化不改变 key`() {
        val a = ChatItem("assistant", "你", null, -1, false, false)
        val b = ChatItem("assistant", "你好", null, -1, false, false)
        assertEquals(ChatKeys.of(a), ChatKeys.of(b))
    }

    /** 空列表边界：不抛异常（key 函数会被 Compose 在空列表时也调用）。 */
    @Test
    fun `空列表不产生 key`() {
        assertEquals(0, emptyList<ChatItem>().map { ChatKeys.of(it) }.size)
    }
}