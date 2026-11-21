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

    /* ================================================================
     * 回归：LazyColumn 重复 key 崩溃（h59）
     * ================================================================ */

    /**
     * 模拟 [com.dollhouse.app.data.ChatHistoryStore.trimForSave] 的裁剪结果。
     *
     * 只建模与本崩溃相关的那一步：**没有**头部摘要、**没有**补回批时，
     * `tail = max(0, len - keep)`，输出 `history[tail until len]`。
     * 也就是「挤掉最前面的若干条，其余整体前移」。
     */
    private fun trimmed(history: List<ChatItem>, keep: Int): List<ChatItem> {
        val tail = maxOf(0, history.size - keep)
        return history.subList(tail, history.size)
    }

    /**
     * 模拟 [com.dollhouse.app.ui.chat.ChatPanel.renderHistory] 的重铺：
     * 它**按数组位置**重新赋 index（`ChatItem(..., i, ...)`），
     * 所以无论裁剪前各条原来的 index 是多少，重铺后一律是 `0 until size`。
     */
    private fun renumbered(history: List<ChatItem>): List<ChatItem> {
        return history.mapIndexed { i, item -> item.copy(index = i) }
    }

    private fun keysOf(items: List<ChatItem>): List<String> = items.map { ChatKeys.of(it) }

    /**
     * **这条用例钉住的是 r0.2.7 的真机崩溃**（诊断包 `logs/event_038.json`）：
     * `IllegalArgumentException: Key "h59" was already used`。
     *
     * 崩溃形状：会话已有 `keep` 条（`trimForSave` 的保留上限），此时再发一条 ——
     *
     * | 步骤 | history 长度 | 列表 items 的 key |
     * |---|---|---|
     * | 发送前 | 60 | `h0 … h59` |
     * | 写入第 61 条 | 61 | — |
     * | 落盘裁剪回 | 60（挤掉第 0 条） | 旧快照仍是 `h0 … h59` |
     * | 若用 `length() - 1` 当新项下标 | — | 新项 = **`h59`** ← **与旧第 60 条撞车** |
     *
     * 断言分两半，缺一不可：
     *   ① **反例**：把「只换 index 来源」的做法算出来，它必须**确实重复** ——
     *      这一半是防止将来有人以为「下标换成裁剪后的长度就够了」而删掉重铺分支。
     *   ② **正例**：`push` 返回 -1 → `renderHistory()` 整列重铺 → key 两两不同。
     *
     * @see com.dollhouse.app.data.ChatHistoryStore.KEEP_FOR_SAVE
     */
    @Test
    fun `历史满上限后追加新消息不产生重复 key`() {
        val keep = com.dollhouse.app.data.ChatHistoryStore.KEEP_FOR_SAVE

        // 发送前的满员快照（恰好 keep 条，index = 0 until keep）。
        val full = (0 until keep).map { msg(it) }
        assertEquals("前提：满员快照的 key 应两两不同", keep, keysOf(full).toSet().size)
        assertEquals("前提：最后一个 key 就是崩溃里那个", "h${keep - 1}", ChatKeys.of(full.last()))

        // 写入第 keep + 1 条 → 落盘裁剪回 keep 条。
        val written = full + msg(keep)                    // 新写入的那条（尚未重铺）
        val afterTrim = trimmed(written, keep)
        assertEquals("前提：裁剪后应回到 keep 条", keep, afterTrim.size)

        // ---- ① 反例：沿用旧快照的下标 ----
        // 「只把 index 来源换掉」的做法 = 仍然复用旧 items（各自带着旧的 index），
        // 新项用裁剪后长度 - 1 当下标。这正是崩溃的写法。
        val staleSnapshot = full                            // 界面上的旧列表，未重铺
        val newItemOldWay = msg(afterTrim.size - 1)         // ← length-1 = keep-1 = 59
        val buggyKeys = keysOf(staleSnapshot + newItemOldWay)
        assertEquals(
            "反例自检：这条路径必须**确实**产生重复 key，否则说明建模没对上崩溃形状",
            buggyKeys.size - 1,
            buggyKeys.toSet().size
        )
        assertEquals(
            "反例自检：重复的必须正是崩溃里那个 h59",
            "h${keep - 1}",
            ChatKeys.of(newItemOldWay)
        )

        // ---- ② 正例：push 返回 -1 → 整列重铺 ----
        // renderHistory() 收到的就是裁剪后的数组，按位置重新编号后建 items。
        val rebuilt = renumbered(afterTrim)
        val fixedKeys = keysOf(rebuilt)
        assertEquals(
            "修复后：新列表的 key 必须两两不同（这是 LazyColumn 的硬性要求）",
            fixedKeys.size,
            fixedKeys.toSet().size
        )
        assertEquals(
            "修复后：重铺的 key 应是连续的 h0..h${keep - 1}",
            "h0",
            fixedKeys.first()
        )
        assertEquals(
            "修复后：末条 key 是 h${keep - 1}，但它对应的是**新消息**（旧的已被挤出）",
            "h${keep - 1}",
            fixedKeys.last()
        )
    }

    /**
     * 边界：尚未触顶时（L &lt; keep）**不应**触发重铺 —— 走增量追加。
     *
     * 这条与上一条配对：`push` 的返回值判据是 `length != before + 1`，
     *   · L &lt; keep：写入后 length = L+1，不等于 before+1 为假 → 返回 L → 增量
     *   · L = keep：裁剪后 length 仍 = keep = before → 不等于 before+1 为真 → 返回 -1 → 重铺
     * 若判据写错（例如写成 `length &gt;= before + 1` 或恒返回下标），
     * 触顶时就会静默走增量，崩溃复现。所以这里把「未触顶 = 增量安全」也钉住。
     */
    @Test
    fun `未触顶时增量追加的 key 也不重复`() {
        val keep = com.dollhouse.app.data.ChatHistoryStore.KEEP_FOR_SAVE
        // 取上限前一条：写入后正好触顶，但**不裁剪**（长度 keep，恰好等于 keep 上限）。
        val before = keep - 1
        val old = (0 until before).map { msg(it) }
        val written = old + msg(before)          // 长度 = keep，未超过上限
        val afterTrim = trimmed(written, keep)
        assertEquals("未触顶时不应被裁掉任何一条", written.size, afterTrim.size)

        // 增量追加：新项下标 = 裁剪后长度 - 1 = 最后一条的位置。
        val appended = msg(afterTrim.size - 1)
        val keys = keysOf(old + appended)
        assertEquals(
            "未触顶时增量追加的 key 必须两两不同",
            keys.size,
            keys.toSet().size
        )
    }
}