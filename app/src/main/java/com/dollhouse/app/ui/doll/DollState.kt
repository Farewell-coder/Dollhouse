package com.dollhouse.app.ui.doll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 【职责】人偶库的「变更信号」容器。
 *
 * 【为什么需要它】[DollStore] 是纯数据 + 文件，本身没有可观察性；而人偶列表页的
 *   View 长期驻留在页面栈里 —— 子页（编辑 / 添加 / 删除）完成后只是把子页摘掉，
 *   列表层的组合**不会**因此重建。列表若把数据存在 `remember` 里，就会永远停在
 *   第一次取到的快照上（改名后返回列表仍显示旧名）。
 *
 * 【为什么用版本号而不是直接存一份数据】列表的派生数据（封面位图等）要按需重算，
 *   在状态里再存一份人偶列表会与 [DollStore] 形成第二真源，两边迟早不一致。
 *   这里只广播「第几次变更」，读取方看到变化就重新向 [DollStore] 取值 ——
 *   单一真源始终在 [DollStore]。
 *
 * 【与重建整层的对比】把列表层整层重建也能刷新，但会丢滚动位置（用户改完第 N 个
 *   人偶返回时被弹回顶部）。改状态只触发重组，滚动位置保持不变。
 *
 * 【谁写】所有改了人偶集合 / 属性的写操作之后（[DollListScreen] / [DollEditScreen]）。
 * 【谁读】[DollListScreen] 的 Composable。
 * 【线程】只在主线程读写（与 View / Compose 状态同约束）。
 */
object DollState {
    /** 变更版本号：每次人偶数据被改动后自增，读取方据此重算派生数据。 */
    var rev by mutableStateOf(0)
        private set

    /** 广播一次「人偶数据已变更」。调用方负责确保写操作已经完成。 */
    fun bump() {
        rev++
    }
}
