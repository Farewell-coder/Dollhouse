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

    /** 附件条：图片路径。 */
    var attachImage by mutableStateOf<String?>(null)

    /** 附件条：文本内容。 */
    var attachText by mutableStateOf<String?>(null)

    /** 附件条：说明文案（OCR 进行中优先）。 */
    var attachLabel by mutableStateOf<String?>(null)

    /** 输入框内容。 */
    var input by mutableStateOf("")

    /**
     * 【r13·流式】在途思考内容。
     *
     * 【为什么要单独一份】流式期间正文气泡会逐字更新，思考框也要同步长出来（用户点开就能看），
     *   但它此刻还没有对应的 [ChatItem]（消息尚未落盘）。把在途内容放这里，
     *   由 [ChatUi] 在「最后一条气泡上方」按需渲染。
     *   收尾后清空 —— 落盘的那条消息自带 `think`，重铺时不再依赖本字段。
     */
    var streamThink by mutableStateOf<String?>(null)

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

    /**
     * 【r13·聊天列表】会话集合的**变更信号**：每次会话被增删改 / 有新消息后自增，
     *   列表页据此重新拉取数据（见 [ChatListData.load]）。
     *
     * 【为什么与 [items] 分开】[items] 是「当前打开的会话」的消息快照，由 [ChatPanel]
     *   的重铺流程写入；而本字段是「会话清单」的变更信号，由**任何**改到会话的地方写
     *   （发送消息、改名、新建、删除）。
     *   两者生命周期不同：发消息时 [items] 会重建，但会话清单只有「标题首次自动生成」
     *   与「时间戳刷新」两处会变 —— 合并成一个字段会让列表在不该重算时也重算。
     *
     * 【为什么列表页不直接读 ChatSessions 的返回】那个对象是纯数据、没有可观察性
     *   （见 [com.dollhouse.app.ui.doll.DollState] 的同款说明）。用版本号广播
     *   「第几次变更」，读取方看到变化自己重新取值 —— 单一真源始终在数据层。
     */
    var listRev by mutableStateOf(0)
        private set

    /** 广播一次「会话集合已变更」。调用方负责确保写操作已经完成。 */
    fun bumpList() {
        listRev++
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
 * @param think     这条回复当时的推理内容（null / 空 = 没有或用户关掉了显示）
 * @param thinkMs   「思考了 X 毫秒」；0 = 未知（老数据 / 非流式估算）
 * @param cover     摘要条覆盖的消息条数（仅 `isSummary` 时有效；0 = 老数据没有这个字段）
 */
data class ChatItem(
    val role: String,
    val text: String,
    val image: String?,
    val index: Int,
    val isPrev: Boolean,
    val isSummary: Boolean,
    val think: String? = null,
    val thinkMs: Long = 0L,
    val cover: Int = 0
)

/**
 * 回复上方可折叠思考框的**渲染数据**（r13 起由 [ChatItem.think] 填充）。
 *
 * 【与改造前的区别】以前它是一个独立的界面状态（`ChatState.thinkingBox`），只在内存里过一手、
 *   重铺即丢。现在思考内容随消息落盘，本类退化为纯粹的「传参载体」——
 *   历史里每一条回复都能按自己的 think 渲染出思考框。
 */
data class ThinkingBox(val reasoning: String, val costMs: Long)