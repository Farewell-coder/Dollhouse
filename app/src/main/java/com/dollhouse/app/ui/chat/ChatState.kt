package com.dollhouse.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 【职责】聊天页的 Compose 状态容器。
 *
 * 【为什么需要它】原 View 版的状态长在控件上：`ChatPanel.renderHistory()` 往 `messages`
 *   里 addView、`setMemoBusy` 直接改 TextView 文字。Compose 侧没有「控件句柄」可找，
 *   改为「逻辑层写 state、界面层读 state」——本对象就是那条唯一通道。
 *
 * 【谁写】[ChatPanel] 的全部 UI 相关方法（纯逻辑侧，一行 View 都不碰）。
 * 【谁读】[ChatUi] 的 Composable。
 * 【线程】只在主线程读写（与旧 `addView` / `setText` 同约束）。
 */
object ChatState {

    /** 消息快照：`history` 的可渲染视图，由 [ChatPanel.renderHistory] 每次重建。 */
    var items by mutableStateOf<List<ChatItem>>(emptyList())

    /** 是否有「点击加载更早的历史记录」入口。 */
    var hasPrev by mutableStateOf(false)

    /** 补回批最后一条在 [items] 中的下标（-1 = 非展开态）。 */
    var prevTailIndex by mutableStateOf(-1)

    /** 请求在途：发送键切「停止」。 */
    var waiting by mutableStateOf(false)

    /** 浏览归档：收起输入行与工具条。 */
    var browsingArchives by mutableStateOf(false)

    /** 顶栏上下文环比例（0~1）。 */
    var ctxRatio by mutableStateOf(0f)

    /** 顶部提示条文案（null = 收起）。 */
    var hint by mutableStateOf<String?>(null)

    /** 工具条右侧提示：「记忆总结中」/ 失败文案（null = 收起）。 */
    var memoText by mutableStateOf<String?>(null)

    /** 在途「思考中…」占位文案（null = 无）。 */
    var thinking by mutableStateOf<String?>(null)

    /** 回复上方的可折叠思考框（null = 无）。 */
    var thinkingBox by mutableStateOf<ThinkingBox?>(null)

    /** 附件条：图片路径。 */
    var attachImage by mutableStateOf<String?>(null)

    /** 附件条：文本内容。 */
    var attachText by mutableStateOf<String?>(null)

    /** 附件条：说明文案（OCR 进行中优先）。 */
    var attachLabel by mutableStateOf<String?>(null)

    /** 输入框内容。 */
    var input by mutableStateOf("")

    /** 滚动请求序号：每次自增触发一次滚动（与 [scrollTarget] 配对）。 */
    var scrollTick by mutableStateOf(0)

    /** 滚动目标：-1 = 滚到底；>=0 = 滚到该 [items] 下标。 */
    var scrollTarget by mutableStateOf(-1)

    /** 展开重铺期间压住自动滚底与自动收回。 */
    var holdScroll by mutableStateOf(false)

    /** 请求滚到列表底部。 */
    fun requestScrollBottom() {
        scrollTarget = -1
        scrollTick++
    }

    /** 请求滚到指定 [items] 下标。 */
    fun requestScrollTo(index: Int) {
        scrollTarget = index
        scrollTick++
    }
}

/**
 * 一条可渲染的消息。
 *
 * @param role      `user` / `assistant` / `summary`
 * @param text      正文（summary 时为摘要全文）
 * @param image     图片附件路径（可为 null）
 * @param index     在 `history` 中的下标（-1 = 不落库的本地提示气泡）
 * @param isPrev    是否属于「补回批」（更早的历史）
 * @param isSummary 是否为摘要分割标题
 */
data class ChatItem(
    val role: String,
    val text: String,
    val image: String?,
    val index: Int,
    val isPrev: Boolean,
    val isSummary: Boolean
)

/** 回复上方「思考了 X 秒」折叠框的数据（只在内存里过一手，不落盘）。 */
data class ThinkingBox(val reasoning: String, val costMs: Long)