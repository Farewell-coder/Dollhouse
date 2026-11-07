package com.dollhouse.app.ui.chat

import android.app.Activity
import android.content.Context

/**
 * 【职责】聊天界面（[ChatUi]）对宿主的**只读契约**。
 *
 * ## 为什么需要这个接口（而不是直接用 ChatPanel）
 * `ChatUi` 原先 13 个 Composable 形参全部写成 `ChatPanel`。这让「界面层」对
 * 「宿主的身份」产生了硬依赖 —— 而 [ChatPanel] 当前是 `LinearLayout` 子类。
 * 后果是：只要 ChatUi 还按 `ChatPanel` 取类型，**去 View 化就永远无法完成**，
 * 因为界面层始终要求一个 View 载体。
 *
 * 本接口把 ChatUi 真正需要的成员**逐一列清**（实测 31 个：3 个属性 + 28 个方法），
 * 界面层从此只认这个契约，不再认具体类。
 *
 * ## 与 [com.dollhouse.app.data.ChatHost] 的分工（**刻意分成两个接口，不要合并**）
 * | 接口 | 谁用 | 内容 |
 * |---|---|---|
 * | `data.ChatHost` | 数据层（ChatHistoryStore） | 只有数据：history / prevCount / ctx / boundDollId |
 * | `ChatHostUi`（本文件） | 界面层（ChatUi） | 只有交互：菜单、复制、重生成、滚动上报等 |
 *
 * 【为什么不合并成一个】`ChatHost` 是在 task-16 里专门抽出来切断
 * `data → ui` 反向依赖的：它刻意**只含数据**。若把 28 个 UI 动作方法塞进去，
 * 数据层就会认识「弹菜单」「复制」「重生成」这些 UI 语义 —— 那正是当初要切断的东西。
 * 两个接口各自服务一层，[ChatPanel] 同时实现两者。
 *
 * ## 成员清单是怎么来的（可复现）
 * 不是凭印象列的，而是从 ChatUi 的实际用法反推：
 * `grep -oE "host\.[a-zA-Z0-9_]+" app/src/main/java/com/dollhouse/app/ui/chat/ChatUi.kt | sed 's/host\.//' | sort -u`
 * → 31 项，全部收录在下方。**改 ChatUi 读的成员时，这里必须同步改**，否则编译失败（这是好事）。
 *
 * ## 关于可见性
 * 接口成员在 Kotlin 里一律 public，而**覆写不允许收窄可见性**。因此
 * [ChatPanel] 里原本是 `internal` 的那批方法/属性（如 `showModels` / `activity`）
 * 必须以 `public override` 声明。这与 [com.dollhouse.app.data.ChatHost] 的
 * `history` / `prevCount` 当初的处理完全一致。
 * 本项目只有 `:app` 一个模块，`internal` 与 `public` 在可访问范围上等价，
 * 放宽**不引入新的跨包依赖**。
 *
 * ## 【已知限制·留给阶段 2 注意】
 * [controller] 的类型是 `ChatPanel.Controller` —— 它是一个**嵌套在 ChatPanel 里**的
 * 接口。所以本契约在「成员类型」层面仍间接引用了 ChatPanel 这个类名。
 * 这不影响去 View 化（`Controller` 与 View 无关，纯回调三元组），但意味着
 * 「ChatUi 完全不提 ChatPanel 这个名字」要等到把 `Controller` 提升为顶层类型（如
 * `ChatController`）才成立。提升会波及 `ChatActivity.kt` 的匿名实现处，超出本阶段范围。
 */
interface ChatHostUi {

    /* ============================ 属性 ============================ */

    /**
     * 宿主回调：拖动顶栏、关闭页面、打开令牌统计。
     *
     * 【为什么在接口里】ChatUi 的顶栏要转发这三个动作；null 表示无宿主回调（静默忽略）。
     * 【类型来源】`ChatPanel.Controller` —— 见类注释「已知限制」。
     */
    val controller: ChatPanel.Controller?

    /**
     * 宿主 Activity（Compose 侧安装 owner 用）；非 Activity 宿主时为 null。
     *
     * 【为什么需要】`ComposeHost.createView` 之前要 `installForActivity`，
     * 而 ChatUi 里还有两处把人偶页的能力 `as? MainActivity` 判定。
     */
    val activity: Activity?

    /**
     * 取 Context（建 ComposeView / 找 Activity 用）。
     *
     * 【为什么叫 `ctx` 而不是 `context`】[ChatPanel] 继承自 `android.view.View`，
     * 而 `View.getContext()` 是 **final**，无法覆写。接口若声明 `val context`，
     * ChatPanel 就无法实现它。故统一用 `ctx` 这个名字 ——
     * 与 [com.dollhouse.app.data.ChatHost.ctx] 同名，[ChatPanel] 的一个
     * `override val ctx` 即可同时满足两个接口。
     *
     * 【阶段 2 提示】去 View 化之后 ChatPanel 不再是 View，这个命名约束随之消失；
     * 但那时**不建议**改名 — 改名的收益只是好看，代价是同时改两个契约与全部调用点。
     */
    val ctx: Context

    /* ============================ 交互动作 ============================ */

    /** 打开「模型配置」浮层。 */
    fun showModels()

    /** 在工具条右侧短暂显示一条提示（总结失败 / 被拒等），数秒后自动收起。 */
    fun flashMemo(msg: String?)

    /** 用户点「停止」：断掉在途连接，静默收尾。 */
    fun stopGenerating()

    /** 打开「思考程度」面板。 */
    fun showThink()

    /** 打开聊天内容搜索框。 */
    fun showSearchDialog()

    /** 查看某条消息的详情（时间、token 等）。 */
    fun showItemInfo(index: Int)

    /** 把某条消息存进长期记忆。 */
    fun saveToMemory(index: Int)

    /** 回退到某条消息（删掉其后的全部）。 */
    fun rollbackTo(index: Int)

    /** 出错的回复：走可重试路径。 */
    fun retryAfterError()

    /** 针对某条消息继续追问。 */
    fun replyTo(index: Int)

    /** 重新生成某条助手回复。 */
    fun regenerateAt(index: Int)

    /** 重新生成最后一条助手回复。 */
    fun regenerate()

    /** 打开图片选择器（挂附件）。 */
    fun openPicker()

    /** 打开主界面（人偶页）。 */
    fun openMain()

    /** 用户点发送。 */
    fun onSend()

    /**
     * 列表滚动上报。
     *
     * @param firstVisibleIndex 当前首个可见项的列表下标
     * @param atBottom 是否已贴底
     */
    fun onScrolled(firstVisibleIndex: Int, atBottom: Boolean)

    /** 输入框内容变化（启停发送按钮、更新提示等）。 */
    fun onInputChange(text: String)

    /** 「加载更早」：把暂存位里的一批老消息补回正文。 */
    fun loadEarlier()

    /**
     * 从某条消息学习示例（写进学习库）。
     *
     * @return 是否真的写入（界面据此提示）
     */
    fun learnFromAt(index: Int): Boolean

    /** 在某条消息处插入一条 AI 总结分隔条。 */
    fun insertSummaryAt(index: Int)

    /** 编辑并重发某条用户消息。 */
    fun editAndResend(index: Int)

    /** 删除某条消息。 */
    fun deleteAt(index: Int)

    /** 批量复制若干条消息。 */
    fun copyMany(indexes: List<Int>)

    /** 复制最后一条助手回复。 */
    fun copyLastReply()

    /** 复制某条消息。 */
    fun copyAt(index: Int)

    /** 清空本次对话（带二次确认）。 */
    fun confirmClearChat()

    /** 移除待发送的附件。 */
    fun clearAttachment()

    /** 在某条消息处分叉出一条新会话。 */
    fun branchAt(index: Int)
}