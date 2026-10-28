package com.dollhouse.app.ui.chat

/**
 * 聊天列表项的 **LazyColumn key 规则**（纯函数，可 JVM 单测）。
 *
 * ## 为什么单独抽出来
 *
 * r25 之前 `ChatUi.Messages` 里写的是 `key = { i, _ -> "m$i" }` —— 用**下标**当身份。
 * 这是 Compose 列表最典型的错误用法，代价不是「看起来不优雅」，而是实实在在的卡顿：
 *
 * 1. **补回更早历史时整列重建**：`prependPrev()` 把一批消息插到列表前面，之后
 *    每条消息的下标都变了。Compose 靠 key 复用节点，key 一变就认为「这是全新的项」，
 *    于是几百个气泡全部重新组合 + 重新测量。用户感受是「点加载更早，界面顿一下」。
 * 2. **收起展开态**同理，反向再重建一次。
 * 3. **流式期间**：尾项文本每 60ms 变一次，key 虽不变，但列表身份每次都是新 List，
 *    整列仍需逐项比对；配合下面这条更糟：旧实现每次刷新都重建列表对象。
 *
 * ## 为什么用「历史下标」当身份
 *
 * `ChatItem.index` 是这条消息在 `history`（JSONArray）里的位置，由 `renderHistory()`
 * 在重铺时写入，**只在重铺时变**。它天然稳定且唯一 —— 正好可以当身份用。
 *
 * ## 例外：流式占位气泡
 *
 * 流式正文的气泡 `index = -1`（还没落库，历史里没有它）。它在任意时刻至多一条，
 * 且永远排在列表末尾，所以给一个固定 key 就够；不需要（也不能）用下标区分。
 */
object ChatKeys {

    /** 流式占位气泡（尚未落库）的固定 key。 */
    const val LIVE = "live"

    /**
     * 计算一条消息的列表 key。
     *
     * @param item 待渲染的消息项
     * @return 历史里已有的消息 → `"h<index>"`；流式占位气泡 → [LIVE]
     */
    fun of(item: ChatItem): String {
        return if (item.index >= 0) "h" + item.index else if (item.index == -1) LIVE else "local" + (-item.index.toLong())
    }
}