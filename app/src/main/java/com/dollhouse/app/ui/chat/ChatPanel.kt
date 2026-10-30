package com.dollhouse.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.agent.ChatToolRegistry
import com.dollhouse.app.ai.DeepSeekClient
import com.dollhouse.app.ai.MemExtractor
import com.dollhouse.app.ai.ThinkLevel
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ChatHistoryStore
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.ImageStore
import com.dollhouse.app.data.Mem
import com.dollhouse.app.data.Memory
import com.dollhouse.app.data.PersonaBuilder
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.device.OcrEngine
import com.dollhouse.app.pet.PetBus
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.SheetPanel
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】聊天气泡面板本体：消息列表、输入行、附件条、AI 请求与工具回填。
 *
 * 【交互】对外通过 Controller 回调把「拖动 / 关闭」交给宿主（全屏聊天页 ChatActivity）；
 *        模型请求走 DeepSeekClient，联网工具走 ChatToolRegistry，图片附件走 ImageStore。
 *
 * 【架构】r7 全量 Compose 迁移：本类只保留「逻辑 + 对外契约」，界面整体交给 [ChatUi]，
 *        可渲染状态集中在 [ChatState]。构造签名与成员契约一字未改，
 *        故 ChatActivity / ChatHistoryStore / MemSummarizer / SheetPanel / ChatDrawer 全部零改动。
 *
 * 【为什么基类仍是 LinearLayout】[findLayer] 取「父链里第一个 FrameLayout」作为抽屉 / 确认框的
 *        挂载层；若把本类改成 FrameLayout，它会命中自身，抽屉就挂到面板内部而不是页面层。
 *
 * 【坑】history 是整段对话上下文，每次请求都会带上；MAX_TOOL_ROUNDS 限制联网工具的最大轮次，
 *        防止 AI 反复查资料不收敛。改这里要小心 token 统计（TokenStat）的联动。
 */
class ChatPanel(
        context: Context,
        internal val controller: Controller?,
        z: Boolean
) : LinearLayout(context), PickFileActivity.Listener {

    /** 整段对话上下文；由 [ChatHistoryStore] 读写（跨包契约，勿改可见性）。 */
    internal var history: JSONArray = JSONArray()

    /** 【v2.8】最近一次补回的「更早历史」条数；0 = 当前不是展开态。跨包契约。 */
    internal var prevCount = 0

    private var browsingArchives = false
    private var pendingImage: String? = null
    private var pendingText: String? = null
    /** 【OCR】「等 OCR 扫完再发」的闸门是否仍然有效（用户中途停止 / 新一轮发送会置假）。 */
    private var sendPending = false
    /** 【裁剪】上次回填的聊天区宽高比：用来避免每次 applyBackground 都写盘。 */
    private var lastBgRatio = 0f
    /** 【v2.8】收回过程中的重入闸：程序化滚动同样会触发滚动回调。 */
    private var collapsing = false
    /** 【v2.8】「记忆总结中」的期望状态：工具条被归档页整体隐藏时用它重放。 */
    private var memoBusyOn = false
    /** 【v2.9.2】失败/中止提示显示中：期间 setMemoBusy(false) 不得隐藏它。 */
    private var flashHold = false
    /** 【v2.9.2】在途的 flash 收起计时器：新 flash 前先撤旧的，避免互踩。 */
    private var pendingFlash: Runnable? = null
    /** 【三件套】思考参数被服务端拒（参数类 400）后置位：只降级重试一次，避免死循环。 */
    private var thinkDegraded = false
    private var waiting = false
    /** 当前在途请求的取消句柄；null 表示没有正在跑的请求。 */
    private var task: DeepSeekClient.Task? = null
    /** 【思考框】本次请求发出的时刻（毫秒），用于算「思考了 X 秒」。 */
    private var askStartMs = 0L

    /**
     * 【r13·流式】本次请求是否走 SSE。
     * 【为什么按次判定而不是一个全局开关】降级是「这一次请求」的事：某家中转偶尔不支持流式，
     *   不能因此把用户的选择永久改成非流式。本次失败 → 只降级这一次。
     */
    private var useStream = true

    /**
     * 【r13·流式】流式增量落到界面上的节流闸。
     * 【为什么要节流】一次回复可能几百个 chunk，每个都改 Compose 状态会让重组压过渲染帧率
     *   （用户会觉得「打字打得很抖」）。按时间窗合并，观感反而更顺。
     */
    private var lastFlushMs = 0L
    /** 【r13·流式】在途的流式正文累积（未落盘的正文）。 */
    private var streamText: StringBuilder? = null
    /** 【r13·流式】在途的流式思考累积。 */
    private var streamThink: StringBuilder? = null
    private var activeGeneration: com.dollhouse.app.data.GenerationTask? = null
    // 【r13·入口收拢】原 `drawer: ChatDrawer?` 字段已移除（抽屉没了，见 openDrawer 处的说明）。
    /** 摘要分割标题在 [ChatState.items] 里的下标（-1 = 本会话无摘要头）。 */
    private var summaryIndex = -1

    interface Controller {
        fun onClose()
        fun onDrag(f: Float, f2: Float)
        /** 点顶栏的上下文环：打开「令牌消耗统计」页。 */
        fun onOpenTokenStat()
    }

    /** 宿主 Activity（Compose 侧安装 owner 用）；非 Activity 宿主时为 null。 */
    internal val activity: android.app.Activity?
        get() = context as? android.app.Activity

    // 构造：铺 Compose 面板 → 载入历史 → 刷新输入行 → 套用聊天背景。
    init {
        this.waiting = false
        this.browsingArchives = false
        this.thinkDegraded = false
        orientation = LinearLayout.VERTICAL
        // 【圆角】面板整体 16dp 圆角：背景（含背景图）由 GlobalBackground 画在本视图上，
        //   故必须由本视图自己把绘制裁进圆角，否则四角会溢出。
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, UiKit.dp(context, 16f).toFloat())
            }
        }
        addView(ChatUi.build(this), LinearLayout.LayoutParams(-1, -1))
        // 【交互】记忆工具需要 Context，在这里登记一次（幂等）；联网工具是否下发看用户开关。
        ChatToolRegistry.ensure(context.applicationContext)
        // 【r13·角色设定】开场白：这条会话一条消息都没有时，把她写的第一句铺进去。
        //   【为什么挂在 loadHistory 之前】铺完再载入，渲染就自然带上它，不用额外重铺。
        //   【为什么必须判空】否则每进一次聊天页就多一句开场白（历史会越滚越长）。
        seedGreetingIfEmpty()
        ChatHistoryStore.loadHistory(this)
        ChatState.input = com.dollhouse.app.data.ChatDraftStore.load(context, boundDollId(), ChatSessions.currentId(context)).text
        renderHistory()
        refreshInputRow()
        refreshCtxRing()
        refreshHint()
        applyBackground()
    }

    /* ------------------------- 状态 → 界面 ------------------------- */

    /**
     * 往消息列表追加一条气泡。
     *
     * @param index 这条消息在 [history] 里的下标；-1 表示「不在历史里」（如错误提示条）。
     *   【r13 修 bug·「重新生成之后长按没反应」】原来这里**写死 -1**，而长按菜单
     *   （见 [ChatUi.showMessageMenu]）需要 index 才能定位到历史里的那条消息，
     *   于是「刚发/刚生成、还没经过 renderHistory 重铺」的那条长按一律无反应 ——
     *   历史里已有的消息因为重铺时拿到了正确 index，反倒正常。
     *   修法：由调用方在**落库之后**传入 `history.length() - 1`。
     */
    private fun appendBubble(text: String, mine: Boolean, image: String?, index: Int = -1) {
        // 每条本地提示拥有独立身份，不能与流式占位共用 "live" key。
        val itemIndex = if (index < 0) -System.identityHashCode(Any()) else index
        ChatState.items = ChatState.items + ChatItem(
            role = if (mine) "user" else "assistant",
            text = text,
            image = image,
            index = itemIndex,
            isPrev = false,
            isSummary = false
        )
        if (!ChatState.holdScroll) {
            ChatState.requestScrollBottom()
        }
    }

    /** 【交互】气泡渲染已整体迁到 ChatUi / ChatState；以下为薄壳，保持内部调用点不变。 */
    fun addBubble(str: String?, z: Boolean) {
        appendBubble(str ?: "", z, null)
    }

    private fun addThinking(str: String?) {
        ChatState.thinking = str
        if (!ChatState.holdScroll) {
            ChatState.requestScrollBottom()
        }
    }

    private fun setThinkingLabel(str: String?) {
        ChatState.thinking = str
    }

    private fun removeThinking() {
        ChatState.thinking = null
    }

    /* ------------------------- 顶栏 / 工具条回调 ------------------------- */

    /** 点提示条：跳主界面。 */
    internal fun openMain() {
        val act = context as? MainActivity
        if (act != null) {
            // 已在主界面内的聊天 Tab：直接打开供应商，不重复启动主界面。
            com.dollhouse.app.ui.provider.ProviderNav.open(act)
            return
        }
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        (context as? android.app.Activity)?.let {
            com.dollhouse.app.PageTransitions.start(it, intent)
        } ?: context.startActivity(intent)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            (context as? android.app.Activity)?.let { com.dollhouse.app.ui.provider.ProviderNav.open(it) }
        }, 300L)
    }

    /** 点「加载更早的历史记录」。 */
    internal fun loadEarlier() {
        // 【v2.8】记下补回的条数：这是「展开态」的唯一凭据，滑过分割线时按它收回去。
        val n = ChatHistoryStore.prependPrev(this)
        if (n <= 0) {
            return
        }
        prevCount = n
        // 【坑】不能走 reloadHistory：它会把刚设好的 prevCount 清零。
        //        prependPrev 已把合并结果写进 prefs 与 host.history，直接重铺即可。
        // 【v2.8·N3】用 try/finally 兜底：renderHistory 万一抛异常，holdScroll 必须复位，
        //   否则自动滚底与自动收回会永久失效。
        ChatState.holdScroll = true
        try {
            renderHistory()
            ChatState.requestScrollTo(0)
        } finally {
            ChatState.holdScroll = false
        }
        post {
            // 【坑】队列里可能还排着更早的「滚到底」请求，等它跑完再拉回补回批顶部，
            //       用户看到的才是「刚展开的那批」。
            ChatState.requestScrollTo(0)
        }
    }

    /** 输入框内容变化（Compose 侧单向数据流）。 */
    internal fun onInputChange(text: String) {
        ChatState.input = text
        val d = com.dollhouse.app.data.ChatDraft()
        d.text = text
        com.dollhouse.app.data.ChatDraftStore.save(context, boundDollId(), ChatSessions.currentId(context), d)
    }

    /** 工具条：模型配置面板。 */
    internal fun showModels() {
        SheetPanel.showModels(context, findLayer())
    }

    /** 工具条：思考程度面板。 */
    internal fun showThink() {
        SheetPanel.showThink(context, findLayer(), this)
    }

    /**
     * 【r13·需求改造】原「功能面板（记忆 / 发图）」已随加号面板一起删除。
     * 【现在怎么发图】工具条上的图片按钮直接调 [openPicker]（用户定案：甲 —— 整页删掉，发图挪到别处）。
     */
    internal fun showMemory() {
        openPicker()
    }

    /* ------------------------- 对外方法（契约不变） ------------------------- */

    /**
     * 【v2.8】总结在途时把工具条右侧的「记忆总结中」亮起来，结束后收起。
     * 【坑】调用方（DeepSeekClient 回调）本就在主线程，所以直接改状态；
     *       若换成 View.post，面板 detached 时会把任务挂到重新 attach 才执行，提示可能永久残留。
     */
    internal fun setMemoBusy(busy: Boolean) {
        memoBusyOn = busy
        // 【v2.9.2】新的总结开始了：让上一次的失败提示让位，并把文字复位成进行中文案
        //（原来只改可见性不复位文字，会导致「总结中」显示的还是上次的失败文案）。
        if (busy) {
            flashHold = false
            val pending = pendingFlash
            if (pending != null) {
                removeCallbacks(pending)
                pendingFlash = null
            }
            ChatState.memoText = "记忆总结中"
            return
        }
        // 【v2.9.2·P0】总结结束时必须避开正在显示的 flash 提示：
        //  回调 finally 里必调 setMemoBusy(false)，若在此收起，flashMemo 的显示
        //  会被同一帧改回隐藏，不绘制中间态 —— 用户依然「点了没反应」。
        if (!flashHold) {
            ChatState.memoText = null
        }
    }

    /** 【v2.9.1】在工具条右侧短暂显示一条提示（总结失败 / 被拒等），3 秒后自动收起。 */
    internal fun flashMemo(msg: String?) {
        if (msg == null || msg.trim().isEmpty()) {
            return
        }
        // 【v2.9.2·P2-1】同一时间只允许一个计时器：旧的先撤，否则连续两次失败点击时，
        //  较旧的计时器会在 3 秒时把较新的提示提前抹掉。
        val pending = pendingFlash
        if (pending != null) {
            removeCallbacks(pending)
            pendingFlash = null
        }
        // 【v2.9.2·P0】置 flashHold：让紧随其后的 setMemoBusy(false) 别把它同帧收起。
        //  回调 finally 必调 setMemoBusy(false)，不设这个标志的话提示会被立刻抹掉，
        //  同一帧完成、不绘制中间态 —— 用户什么都看不到。
        flashHold = true
        Logs.i("DollhouseMemo", "[flash] 显示提示 len=" + msg.length)
        ChatState.memoText = msg
        val r = Runnable {
            pendingFlash = null
            flashHold = false
            // 期间若另一次总结开始了，就别把它的「记忆总结中」收掉。
            if (!memoBusyOn) {
                ChatState.memoText = null
            }
        }
        pendingFlash = r
        postDelayed(r, 3000L)
    }

    /**
     * 【v2.8】总结完成后把视图滚到「ⓘ 历史对话摘要」那条分割线，让「已收起」一眼可见。
     * 【Compose】原实现要等布局完成才能算坐标（getTop 会拿到 0），现在直接按下标滚动，
     *   列表自己会在布局就绪后落到目标项，重入与「节点已摘」两类崩溃一并消失。
     */
    internal fun scrollToSummary() {
        if (summaryIndex < 0) {
            return
        }
        ChatState.requestScrollTo(summaryIndex)
    }

    fun refreshInputRow() {
        ChatState.browsingArchives = browsingArchives
        refreshAttachStrip()
    }

    /**
     * 套用用户选的聊天背景图与透明度。
     * 【异步 + 共享】解码 / 缩放 / 缓存 / 可读性遮罩统一交给 GlobalBackground：
     *   主线程不再同步解码整张大图（原实现 loadScaled 会卡首帧），并与首页共用同一张图。
     */
    fun applyBackground() {
        recordChatBgRatio()
        // fallback=OPTION：未设置背景时铺面板原底色；有图时铺「不透明底 + 图 + 可读性遮罩」。
        GlobalBackground.install(this, UiKit.option(), false)
    }

    /**
     * 【裁剪】把聊天区实际宽高比回填到偏好，供裁剪页据此出框（所见即所得）。
     * 【坑】构造期还没量到尺寸，首帧要 post 一次；比例没变就不再写盘。
     */
    private fun recordChatBgRatio() {
        if (width > 0 && height > 0) {
            writeChatBgRatio(width, height)
            return
        }
        post {
            if (width > 0 && height > 0) {
                writeChatBgRatio(width, height)
            }
        }
    }

    private fun writeChatBgRatio(i: Int, i2: Int) {
        if (i <= 0 || i2 <= 0) {
            return
        }
        val f = i.toFloat() / i2.toFloat()
        if (Math.abs(f - lastBgRatio) < 0.001f) {
            return
        }
        lastBgRatio = f
        PetPrefs.setChatBgRatio(context, f)
    }

    fun openPicker() {
        if (browsingArchives) {
            return
        }
        PickFileActivity.setListener(this)
        PickFileActivity.setPurpose(ImageStore.DIR)
        PickFileActivity.start(context)
    }

    override fun onPicked(str: String?, str2: String?) {
        if (str != null) {
            pendingImage = str
            pendingText = null
        } else if (str2 != null) {
            pendingText = str2
            pendingImage = null
        }
        refreshAttachStrip()
    }

    override fun onFailed(str: String?) {
        // 读文件失败静默：不产生任何浮层反馈。
    }

    fun clearAttachment() {
        pendingImage = null
        pendingText = null
        refreshAttachStrip()
    }

    // 刷新附件条显示（缩略图 + 文件名 + 清除按钮）。
    private fun refreshAttachStrip() {
        ChatState.attachImage = pendingImage
        ChatState.attachText = pendingText
        if (pendingImage == null && pendingText == null) {
            ChatState.attachLabel = null
            return
        }
        val snapImg = pendingImage
        if (snapImg != null) {
            // 【OCR】已发出、正在后台扫字：给她一句进度，避免用户以为卡死了。
            ChatState.attachLabel = if (sendPending && waiting) {
                "正在识别图片文字…（后台处理，稍等）"
            } else {
                "已选图片，会一起发给她"
            }
            return
        }
        val str = pendingText
        val max = Math.max(1, if (str != null) str.length / 1024 else 0)
        ChatState.attachLabel = "已选文本内容（约 " + max + " KB），会拼在她看到的消息里"
    }

    fun showChat() {
        browsingArchives = false
        refreshInputRow()
        renderHistory()
        refreshHint()
    }

    /* ------------------------- 多会话 / 上下文环 / 抽屉 ------------------------- */

    /**
     * 打开左侧抽屉。
     * 【坑】挂载层是「父链里第一个 FrameLayout」——ChatActivity 里是 android.R.id.content。
     *       找不到就退回自己的父容器，至少不会崩。
     */
    // 【r13·入口收拢】原 openDrawer() 已移除 —— 聊天页不再有左侧会话抽屉。
    //   需求原文：「第二三幅图这两个也不需要了」。
    //   会话切换统一由**聊天列表页**（默认首页）承担：两处并存会出现「同一操作两种入口」，
    //   而抽屉那份列表是命令式 View 拼的、没有头像 / 预览 / 未读，信息密度也低于列表页。
    //   【不是删功能】改名 / 删除已由列表页长按菜单承接；「清空当前对话」保留在本页工具区。
    /** 抽屉 / 确认框共用的挂载层。 */
    internal fun findLayer(): ViewGroup? {
        var p: ViewParent? = parent
        while (p is View) {
            if (p is FrameLayout) {
                return p
            }
            p = (p as View).parent
        }
        return if (p is ViewGroup) p else null
    }

    // 【r13·入口收拢】原 closeDrawerIfOpen() 已移除（抽屉没了，返回键无需先关它）。
    //   聊天页的返回键现在直接走 PageStack / 系统返回，语义更简单。

    /** 会话切换 / 新建 / 删除后：重新载入历史并整屏重铺。 */
    fun reloadHistory() {
        // 【v2.8】展开态是「当前会话这份 history」的瞬态：切了会话/重建了视图一律作废，
        //         否则旧 count 会在新会话里误把正文当成补回批摘掉。
        prevCount = 0
        ChatHistoryStore.loadHistory(this)
        renderHistory()
        refreshCtxRing()
    }

    /**
     * 【r13·记忆系统重构】原「立即总结（归档）」入口已随旧系统整体移除。
     *
     * 【为什么删】它做的是「把最老一批消息压成摘要、删掉原文、摘要挂历史头部」——
     *   一次动几十条、不可逆，且与新的「插入总结」语义重叠（后者只插一条分割条、不动原文）。
     *   归档能力已由 [com.dollhouse.app.ai.MemExtractor] 的结构化抽取（new/update/merge）
     *   承担：它同样不删原文，但产出的是可检索、可编辑、可单独删除的记忆条目。
     */

    /** 复制最后一条助手回复（气泡操作条的「⧉ 复制」）。 */
    fun copyLastReply() {
        for (i in history.length() - 1 downTo 0) {
            val o = history.optJSONObject(i)
            if (o != null && "assistant" == o.optString("role")) {
                copyText(o.optString("content", ""))
                return
            }
        }
    }

    /** 把文本塞进系统剪贴板（失败只忽略，不抛）。 */
    private fun copyText(text: String) {
        if (text.isEmpty()) {
            return
        }
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Dollhouse", text))
        } catch (unused: Throwable) {
        }
    }

    /* ------------------ 【r13】消息长按操作（菜单在 ChatUi 里弹） ------------------ */

    /**
     * 复制指定下标的消息（长按菜单「复制」）。
     * @param index [history] 里的下标；越界/非消息条目直接忽略。
     */
    fun copyAt(index: Int) {
        val o = itemAt(index) ?: return
        val text = o.optString("content", "")
        if (text.isEmpty()) {
            return
        }
        copyText(text)
        flashMemo("已复制")
    }

    /**
     * 多选批量复制（长按菜单「多选」→ 选中若干 → 复制）。
     * 【为什么不直接复制** 多选的价值在于一次拿多条，所以拼接后一次入剪贴板。
     */
    fun copyMany(indexes: List<Int>) {
        if (indexes.isEmpty()) {
            return
        }
        val sb = StringBuilder()
        for (i in indexes) {
            val o = itemAt(i) ?: continue
            val t = o.optString("content", "")
            if (t.isEmpty()) {
                continue
            }
            if (sb.isNotEmpty()) {
                sb.append("\n\n")
            }
            sb.append(t)
        }
        if (sb.isEmpty()) {
            return
        }
        copyText(sb.toString())
        flashMemo("已复制 " + indexes.size + " 条")
    }

    /**
     * 【回滚到此处】把历史截断到指定下标（含）为止，之后的全部丢弃。
     *
     * 【语义】相当于「从这里重新开始」：该条及之前保留，之后的消息不再存在。
     *   与「编辑并重发」的区别是它**不改内容、不自动重发**，只把上下文收短。
     */
    fun rollbackTo(index: Int) {
        val o = itemAt(index) ?: return
        if (index < 0 || index >= history.length()) {
            return
        }
        // 【护栏】请求在途时不允许改历史：回来时会把截断点之后的内容又写回去。
        if (waiting) {
            flashMemo("等这轮回复完了再操作")
            return
        }
        try {
            val arr = JSONArray()
            for (i in 0..index) {
                arr.put(history.opt(i))
            }
            history = arr
            prevCount = 0
            ChatHistoryStore.saveHistory(this)
            renderHistory()
            refreshCtxRing()
            flashMemo("已回滚到这条")
        } catch (unused: Throwable) {
        }
    }

    /**
     * 【r19·对话分支】把当前会话「前 index 条」复制成一个新会话，从那里继续聊。
     *
     * 【与回滚的区别】回滚会**删掉**后面的消息（不可逆）；分支是**复制**
     *   （原会话保留，新会话独立），相当于「从这一段历史岔出一条平行对话」。
     *
     * 【落盘】新会话走 `ChatSessions.createIn`（当前池 + 时间戳前缀），
     *   历史用 `ChatHistoryStore.saveHistory(ctx, arr, newId)` 写入，然后切过去。
     *   原会话不动 —— 用户随时可以切回去。
     */
    fun branchAt(index: Int) {
        if (index < 0 || index >= history.length()) {
            return
        }
        if (waiting) {
            flashMemo("等这轮回复完了再操作")
            return
        }
        try {
            val arr = JSONArray()
            for (i in 0..index) {
                arr.put(history.opt(i))
            }
            val pool = ChatSessions.pool(context)
            val title = "分支 · " + (itemAt(index)?.optString("content", "")?.take(8) ?: "对话")
            val newId = ChatSessions.createIn(context, pool, if ("pet" == pool) "p" else "c", title)
            ChatHistoryStore.saveHistory(context, arr, newId)
            ChatSessions.switchTo(context, newId)
            flashMemo("已分支到新会话")
        } catch (unused: Throwable) {
        }
    }

    /**
     * 【r19·聊天搜索】弹出搜索框，在当前会话历史里查关键词并高亮定位。
     *
     * 【为什么用原生 Dialog 而不是 Compose】这里是命令式调用（长按标题触发），
     *   且要带输入框 + 结果列表 —— 原生 Dialog 与既有确认框同一套壳，零额外依赖。
     * 【命中后】滚动到该条并闪烁高亮（由 renderHistory 的 highlightIndex 控制）。
     */
    fun showSearchDialog() {
        val act = UiKit.findActivity(context) ?: return
        val input = android.widget.EditText(act)
        input.hint = "搜索聊天内容"
        input.setPadding(24, 16, 24, 16)
        UiKit.showDialog(act, "搜索", input, "搜索", View.OnClickListener {
            val kw = input.text.toString().trim()
            if (kw.isEmpty()) {
                return@OnClickListener
            }
            val idx = searchHistory(kw)
            if (idx >= 0) {
                highlightIndex = idx
                renderHistory()
                flashMemo("找到第 " + (idx + 1) + " 条")
            } else {
                flashMemo("没找到「" + kw + "」")
            }
        }, "取消", null)
    }

    /** 在历史里搜关键词（从最新往回找），返回下标；没找到返回 -1。 */
    private fun searchHistory(kw: String): Int {
        for (i in history.length() - 1 downTo 0) {
            val o = history.optJSONObject(i) ?: continue
            val content = o.optString("content", "")
            if (content.contains(kw, ignoreCase = true)) {
                return i
            }
        }
        return -1
    }

    /** 搜索命中后要高亮的下标（-1 = 无）。 */
    internal var highlightIndex = -1

    /**
     * 【编辑并重发】把指定消息的正文回填到输入框，并截断它**之前**的历史。
     *
     * 【为什么截断到它之前】重发等价于「重新说一遍」：原消息本身会由发送动作重新产生，
     *   若把它留在历史里就会重复（模型会看到同一句话两遍）。
     */
    fun editAndResend(index: Int) {
        val o = itemAt(index) ?: return
        if (waiting) {
            flashMemo("等这轮回复完了再操作")
            return
        }
        val text = o.optString("content", "")
        try {
            val arr = JSONArray()
            for (i in 0 until index) {
                arr.put(history.opt(i))
            }
            history = arr
            prevCount = 0
            ChatHistoryStore.saveHistory(this)
            renderHistory()
            refreshCtxRing()
        } catch (unused: Throwable) {
        }
        // 回填输入框，用户可改完再发（不自动发 —— 避免误触直接产生一条新消息）。
        ChatState.input = text
        refreshInputRow()
        flashMemo("已回填，改完点发送")
    }

    /**
     * 【单条重新生成】删掉这条**助手**回复，并立刻重新请求一次。
     *
     * 【为什么不复用 regenerate()】那个只处理「最后一条」，长按的是中间某条时用不了。
     *   这里先截断到该条之前，再走同一套发送链路，等价于「让她重说这条」。
     */
    fun regenerateAt(index: Int) {
        val o = itemAt(index) ?: return
        if (waiting) {
            flashMemo("等这轮回复完了再操作")
            return
        }
        if ("assistant" != o.optString("role")) {
            flashMemo("只能重新生成她的回复")
            return
        }
        // 前一条必须是 user（否则没有可重答的问题）。
        if (index <= 0) {
            return
        }
        val prev = history.optJSONObject(index - 1)
        if (prev == null || "user" != prev.optString("role")) {
            flashMemo("这条前面没有可重答的问题")
            return
        }
        try {
            val arr = JSONArray()
            for (i in 0 until index) {
                arr.put(history.opt(i))
            }
            history = arr
            prevCount = 0
            ChatHistoryStore.saveHistory(this)
            renderHistory()
            refreshCtxRing()
        } catch (unused: Throwable) {
        }
        if (!canChat()) {
            flashMemo("还没配可用的模型")
            return
        }
        setWaiting(true)
        addThinking("正在思考…")
        askModel(ChatHistoryStore.buildRequest(context, history, boundDollId()), 0, true)
    }

    /**
     * 【插入总结】把「从最近一条摘要到当前这条」之间的对话，**用 AI 压成摘要**，
     *   作为一条分割条（kind=summary）插在这条消息之后。**不动任何原文**。
     *
     * 【与「立即总结（归档）」的区别】
     *   · 归档 = 把最老一批压成要点、**删掉原文**、摘要挂历史头部（已随旧记忆系统下线）；
     *   · 插入总结 = 只往对话流里插一条分割条，原文一条不删、可点开看、可再删掉。
     *
     * 【摘要从哪来】AI 生成，用**与聊天同一个模型**（ProviderStore.activeProvider + 三件套），
     *   不再让用户手打 —— 手打等于把「总结」变成了「发一条普通消息」，没有意义。
     *
     * 【范围口径】从「本段最后一处 kind=summary」之后开始，到 [index] 这条为止，
     *   中间的所有 user / assistant 消息都喂给模型。这样反复插入多次总结时，
     *   每次只总结「上次总结之后的新内容」，不会把旧结论重复压一遍。
     *
     * @param index 长按的那条消息在 [history] 里的下标。
     */
    fun insertSummaryAt(index: Int) {
        if (waiting) {
            flashMemo("等这轮回复完了再操作")
            return
        }
        val at = Math.max(0, Math.min(history.length(), index + 1))
        // ① 目标那条本身不能是摘要（Operit 同款判据）：对摘要再总结一次毫无意义。
        val target = history.optJSONObject(Math.max(0, at - 1))
        if (target != null && "summary" == target.optString("kind")) {
            flashMemo("这条已经是总结了")
            return
        }
        // ② 【r13·对齐 Operit 的搜索范围】本段起点 = 长按这条之前**最近的一条总结**。
        //
        // 【为什么要往前找】Operit 的做法是「总结只压「上一条总结 → 这一条」之间的对话」，
        //   于是多次总结可以层层嵌套、互不重复：
        //       摘要1 【覆盖 A..B】  摘要2 【覆盖 C..D】
        //   若每次都从对话开头重新压，后面的摘要会把前面的内容再压一遍，越压越重。
        //
        // 【为什么不能只看内存】本工程的存档有裁剪（只留最近 60 条，更早的移进「暂存位」）。
        //   旧实现只在 `history` 里找起点 —— 一旦上一条摘要已被裁走，就会误判成
        //   「从对话开头开始」，把早已被压过的老消息重新压一遍。
        val convId = ChatSessions.currentId(context)
        val inMemory = findLastSummaryIndex(history, at - 1)
        // ③ 内存里找不到摘要边界 → 去暂存位里找（这就是「搜索前面的消息」）。
        //   暂存位存的是「已被 60 条裁剪挪出去的老消息」，顺序与正文一致（更早的在前）。
        //   【两种情形都要捞】
        //     a. 暂存位里有摘要 → 它之后的原文还没被压过，要压；
        //     b. 暂存位里没有摘要 → 那些老消息从未被任何摘要覆盖（用户从没总结过），
        //        不捞就等于「总结永远只覆盖最近 60 条」，这正是用户点出的缺口。
        val prevArr = readPrev(convId)
        val usePrev = inMemory < 0 && prevArr.length() > 0
        // ④ 收集范围内的真实消息（内存里的这一段）。
        val turns = ArrayList<Pair<String, String>>()
        val from = if (inMemory < 0) 0 else inMemory + 1
        for (i in from until at) {
            val o = history.optJSONObject(i) ?: continue
            if ("summary" == o.optString("kind")) {
                continue
            }
            val r = o.optString("role", "")
            if ("user" != r && "assistant" != r) {
                continue
            }
            val c = o.optString("content", "").trim()
            if (c.isEmpty()) {
                continue
            }
            turns.add(Pair(r, c))
        }
        // ⑤ 暂存位里「上一条摘要之后」的原文也要一起压进去 ——
        //   否则被裁掉的那一段永远进不了任何摘要，等于从记忆里消失。
        if (usePrev) {
            appendPrevTurns(prevArr, turns)
        }
        if (turns.isEmpty()) {
            flashMemo("这一段还没有可总结的内容")
            return
        }
        if (!PetPrefs.hasKey(context)) {
            flashMemo("还没填 API Key")
            return
        }
        // 【r13】记下覆盖条数：摘要条上会显示「覆盖 N 条」，用户才知道这段压了多少。
        summaryCover = turns.size
        setWaiting(true)
        addThinking("正在总结这一段…")
        MemExtractor.summarize(ctx = context, dollId = boundDollId(), turns = turns,
                done = object : MemExtractor.Done {
                    override fun onDone(text: String?) {
                        if (text == null || text.trim().isEmpty()) {
                            // 【状态收尾】失败也要收掉等待态与思考条，否则输入行一直禁用。
                            setWaiting(false)
                            removeThinking()
                            flashMemo("总结失败，稍后再试")
                            return
                        }
                        applySummary(text.trim(), at)
                    }
                })
    }

    /**
     * 【r13】本次总结覆盖的消息条数（由 [insertSummaryAt] 在发起前填写）。
     *
     * 【为什么用字段传递】摘要的生成是异步的（AI 回调），而「覆盖多少条」在发起那一刻
     *   就已经确定。用字段带过去，比回调里重新算一遍起始点更省也更准。
     */
    private var summaryCover = 0

    /** 从 [endInclusive] 往前找最近一条摘要的下标；找不到返回 -1。 */
    private fun findLastSummaryIndex(arr: JSONArray?, endInclusive: Int): Int {
        if (arr == null) {
            return -1
        }
        for (i in Math.min(endInclusive, arr.length() - 1) downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if ("summary" == o.optString("kind")) {
                return i
            }
        }
        return -1
    }

    /** 读「更早历史」暂存位（坏数据一律当空）。 */
    private fun readPrev(convId: String): JSONArray {
        return try {
            JSONArray(PetPrefs.convPrev(context, convId))
        } catch (ignored: Throwable) {
            JSONArray()
        }
    }

    /**
     * 把暂存位里「最后一条摘要之后」的原文追加进待总结列表。
     *
     * 【口径】暂存位里的消息在时间轴上**早于**正文，所以整体追加在 turns 之前更符合
     *   时间顺序。但摘要本身是「给模型读的正文」，顺序反了会让模型把因果读倒 ——
     *   所以这里转成一个新列表：先暂存段、再正文段。
     */
    private fun appendPrevTurns(prev: JSONArray, turns: ArrayList<Pair<String, String>>) {
        val lastSummary = findLastSummaryIndex(prev, prev.length() - 1)
        val head = ArrayList<Pair<String, String>>()
        for (i in (lastSummary + 1) until prev.length()) {
            val o = prev.optJSONObject(i) ?: continue
            if ("summary" == o.optString("kind")) {
                continue
            }
            val r = o.optString("role", "")
            if ("user" != r && "assistant" != r) {
                continue
            }
            val c = o.optString("content", "").trim()
            if (c.isEmpty()) {
                continue
            }
            head.add(Pair(r, c))
        }
        if (head.isEmpty()) {
            return
        }
        // 【上限】暂存位可能有上百条，全塞进一次总结会撑爆预算。
        //   只取最近的一段（对话越早越不重要），并保留时间顺序。
        val cap = 40
        val tail = if (head.size > cap) head.subList(head.size - cap, head.size) else head
        turns.addAll(0, tail)
    }

    /**
     * 把 [text] 作为 kind=summary 的分割条插在 [at] 位置，并重铺界面。
     *
     * 【r13·对齐 Operit 的「覆盖」语义】这是本轮的关键修正。
     *
     * 【改造前错在哪】摘要只是往对话流里**多插了一条线** —— 它前面那一整段原文
     *   仍然原样显示、仍然参与上下文。也就是说：「总结」这个动作除了多出一行字，
     *   什么作用都没有。用户看到的就是「完全不是一种覆盖」。
     *
     * 【Operit 是怎么做的】读源码可证，Operit 把摘要当成**边界**而不是标记：
     *   · `ChatDisplayWindowPaging.isDisplayPageTriggerMessage` —— 摘要被单列为**分页触发点**，
     *     遇到它就切一页（`user` 与 `summary` 才算触发点）；
     *   · `ChatHistoryManager.loadMessagesAfterLatestSummaryInRange` —— 载入消息时
     *     **从「最后一条摘要」之后开始**，前面的不加载；
     *   · `AIMessageManager` 多处 `indexOfLast { it.sender == "summary" }` —— 组装上下文时
     *     同样以最后一条摘要为起点。
     *   合起来：**摘要一旦生成，它前面那段就退出显示与上下文，由摘要代表**。
     *
     * 【本工程怎么落地】本工程已有现成的同构机制：「更早历史」暂存位 +顶部
     *   「点击加载更早的历史记录」。把被覆盖的原文**挪进暂存位并从正文移除**，
     *   就同时满足两点：
     *     · 界面上只剩「摘要 + 之后的对话」—— 这才是「覆盖」；
     *     · 原文一条不丢，点顶部入口就能翻回来（比 Operit 的手动翻页更省事）。
     *
     * 【失败即不删】暂存位写盘成功才移除正文（见 [ChatHistoryStore.stashToPrev] 的约定）；
     *   写盘失败就退回旧行为（只插摘要、原文留着），绝不为了"看起来对"而丢数据。
     */
    private fun applySummary(text: String, at: Int) {
        // 【状态收尾】无论插入成败都要收掉「正在总结这一段…」——
        //   否则等待态会一直挂着，输入行保持禁用。
        setWaiting(false)
        removeThinking()
        try {
            val pos = Math.max(0, Math.min(history.length(), at))
            // ---- ① 先把被覆盖的原文收进暂存位 ----
            val covered = collectCovered(pos)
            // 【坑】暂存位为空时 stashToPrev 返回 true（没什么可写的，也算成功）——
            //   所以不能拿它区分「没东西可挪」，直接看收集到的条数。
            val stashed = covered.length() == 0 || ChatHistoryStore.stashToPrev(
                context, ChatSessions.currentId(context), covered
            )
            // ---- ② 重组正文：摘要标记 + 新摘要 + 之后的消息 ----
            val arr = JSONArray()
            for (i in 0 until pos) {
                val o = history.optJSONObject(i) ?: continue
                if ("summary" == o.optString("kind")) {
                    // 摘要本身是「分界标记」，留在正文（Operit 同样从最后一条摘要起算上下文）。
                    arr.put(o)
                } else if (!stashed) {
                    // 没能收进暂存位 → 原文留在正文，绝不让它凭空消失。
                    arr.put(o)
                }
            }
            val head = JSONObject()
            head.put("role", "system")
            head.put("kind", "summary")
            head.put("content", text)
            // 【r13】记下这条摘要覆盖了多少条消息（对齐 Operit 的 slice 概念）。
            //   用户看分割条时才知道「这一段压了多少」，排查时也有据可依。
            head.put("cover", summaryCover)
            arr.put(head)
            for (i in pos until history.length()) {
                arr.put(history.opt(i))
            }
            history = arr
            prevCount = 0
            ChatHistoryStore.saveHistory(this)
            renderHistory()
            refreshCtxRing()
            flashMemo(
                if (stashed && covered.length() > 0) {
                    "已插入总结，" + covered.length() + " 条原文收进了「加载更早」"
                } else {
                    "已插入总结"
                }
            )
        } catch (t: Throwable) {
            flashMemo("插入失败")
        }
    }

    /**
     * 收集 **[0, pos)** 段里「将被摘要覆盖」的原文（用户 / 助手消息）。
     *
     * 【为什么排除摘要】已有摘要本身是浓缩后的内容，要留在正文继续参与上下文
     *   （对齐 Operit：上下文从最后一条摘要开始，而不是从最后一条摘要之后开始）。
     * 【为什么排除 system 等】它们不是对话内容，挪进「更早历史」会在补回时
     *   混进气泡流（补回批是按消息渲染的）。
     */
    private fun collectCovered(pos: Int): JSONArray {
        val out = JSONArray()
        for (i in 0 until pos) {
            val o = history.optJSONObject(i) ?: continue
            if ("summary" == o.optString("kind")) {
                continue
            }
            val r = o.optString("role", "")
            if ("user" != r && "assistant" != r) {
                continue
            }
            try {
                out.put(JSONObject(o.toString()))
            } catch (ignored: Throwable) {
            }
        }
        return out
    }

    /** 【信息】弹出这条消息的详情（角色 / 时间 / 字数 / 模型）。 */
    fun showItemInfo(index: Int) {
        val o = itemAt(index) ?: return
        val act = com.dollhouse.app.ui.theme.UiKit.findActivity(context) ?: return
        val role = o.optString("role", "")
        val who = if ("user" == role) "你" else "她"
        val content = o.optString("content", "")
        val ts = o.optLong("ts", 0L)
        val model = o.optString("model", "")
        val sb = StringBuilder()
        sb.append("来自：").append(who).append('\n')
        sb.append("字数：").append(content.length).append(" 字\n")
        if (ts > 0L) {
            sb.append("时间：").append(fmtTime(ts)).append('\n')
        }
        if (model.isNotEmpty()) {
            sb.append("模型：").append(model).append('\n')
        }
        if (o.has("image")) {
            sb.append("带图：是\n")
        }
        com.dollhouse.app.ui.theme.UiKit.showDialog(
            act,
            "这条消息",
            com.dollhouse.app.ui.theme.UiKit.dialogMessage(act, sb.toString().trim()),
            "知道了",
            null,
            null,
            null
        )
    }

    /**
     * 【回复】引用这条消息，把她的原话填进输入框。
     *
     * 【为什么是「填进输入框」而不是「存一条引用数据」】工程的消息结构里没有引用字段
     *   （见 [ChatHistoryStore.put]），加字段要动落盘 / 重铺 / 上下文三处。
     *   而用户的实际诉求是「接着她这句话说」，把原话前置成一个引用块写进输入框，
     *   语义达成、改动最小，也不影响历史数据的兼容性。
     */
    fun replyTo(index: Int) {
        val o = itemAt(index) ?: return
        val t = o.optString("content", "").trim()
        if (t.isEmpty()) {
            flashMemo("这条没有可引用的文字")
            return
        }
        val quoted = if (t.length > 60) t.substring(0, 60) + "…" else t
        ChatState.input = "> " + quoted + "\n" + ChatState.input
        refreshInputRow()
        flashMemo("已引用，接着说")
    }

    /** 【删除】删掉这条消息（只动这一条，不影响其它）。 */
    fun deleteAt(index: Int) {
        if (waiting) {
            flashMemo("等这轮回复完了再操作")
            return
        }
        if (index < 0 || index >= history.length()) {
            return
        }
        try {
            val arr = JSONArray()
            for (i in 0 until history.length()) {
                if (i != index) {
                    arr.put(history.opt(i))
                }
            }
            history = arr
            prevCount = 0
            ChatHistoryStore.saveHistory(this)
            renderHistory()
            refreshCtxRing()
            flashMemo("已删除")
        } catch (t: Throwable) {
            flashMemo("删除失败")
        }
    }

    /** 时间戳 → 「MM-dd HH:mm」。信息弹窗用。 */
    private fun fmtTime(ts: Long): String {
        return try {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ts))
        } catch (unused: Throwable) {
            ""
        }
    }

    /** 【修改记忆】把这条消息存进**当前人偶**的记忆库（供后续对话注入）。 */
    fun saveToMemory(index: Int) {
        val o = itemAt(index) ?: return
        val t = o.optString("content", "").trim()
        if (t.isEmpty()) {
            flashMemo("这条没有可保存的文字")
            return
        }
        val dollId = boundDollId()
        // 【r13·分库不串】写入当前会话绑定的那只人偶 —— 用 boundDollId 而非默认配置。
        val m = Memory()
        m.title = if (t.length > 18) t.substring(0, 18) else t
        m.content = t
        m.source = Memory.SOURCE_CHAT
        m.originDoll = dollId
        if (Mem.add(context, dollId, m)) {
            flashMemo("已存进记忆库")
        } else {
            flashMemo("保存失败")
        }
    }

    /**
     * **【v0.1.0·新增】点赞 / 点踩一条她的回复** —— 把这一问一答存成「学习示例」。
     *
     * ## 为什么必须补这个方法
     *
     * 记忆设置与聊天设置里一直有「学习示例」的读取代码
     * （`ChatHistoryStore.appendLearnExamples` 会把最近 3 条 `{u,a}` 拼进对话），
     * 但**全工程没有任何地方往 `learn_examples` 里写过东西**：
     * 没有写入点、没有 UI 开关（`setLearnEnabled` 零调用）。
     * 结果就是这个功能从上线起就没工作过 —— 读取端永远拿到空数组。
     *
     * 这里补上写入端：用户对某条回复表态「赞」，取它前面最近的用户提问，
     * 组成一对 `{u, a}` 追加进学习示例（最多保留 [LEARN_MAX] 条，先进先出）。
     * 「踩」只做提示、不入库 —— 反面样本需要另一套注入协议，未实现前不假装支持。
     *
     * @param index 该条 assistant 消息在 [history] 里的小标
     * @return 是否成功入库
     */
    fun learnFromAt(index: Int): Boolean {
        val o = itemAt(index) ?: return false
        if ("assistant" != o.optString("role")) {
            return false
        }
        val answer = o.optString("content", "").trim()
        if (answer.isEmpty()) {
            return false
        }
        // 往回找最近的一条用户提问，拼成「问 → 答」示范对。
        var question = ""
        var i = index - 1
        while (i >= 0) {
            val p = history.optJSONObject(i)
            if (p != null && "user" == p.optString("role")) {
                question = p.optString("content", "").trim()
                break
            }
            i--
        }
        if (question.isEmpty()) {
            return false
        }
        return try {
            val arr = PetPrefs.learnExamples(context)
            // 去重：同一对不重复累积（用户反复点同一条不该把示范池挤满）。
            for (k in 0 until arr.length()) {
                val e = arr.optJSONObject(k) ?: continue
                if (e.optString("u") == question && e.optString("a") == answer) {
                    return true
                }
            }
            val item = org.json.JSONObject()
            item.put("u", question)
            item.put("a", answer)
            arr.put(item)
            // 只留最近 LEARN_MAX 条：抽取注入时也只取尾部 3 条，
            //   池子无限增长没有意义，还会让 SharedPreferences 越来越臃肿。
            val keep = org.json.JSONArray()
            val from = if (arr.length() > LEARN_MAX) arr.length() - LEARN_MAX else 0
            for (k in from until arr.length()) {
                val e = arr.optJSONObject(k) ?: continue
                keep.put(e)
            }
            PetPrefs.setLearnExamples(context, keep)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 取 [history] 里下标处的**真实消息**对象；越界或非消息（摘要/system）返回 null。 */
    private fun itemAt(index: Int): JSONObject? {
        if (index < 0 || index >= history.length()) {
            return null
        }
        val o = history.optJSONObject(index) ?: return null
        if ("summary" == o.optString("kind")) {
            return null
        }
        val role = o.optString("role", "")
        return if ("user" == role || "assistant" == role) o else null
    }

    /**
     * 清空当前对话（先二次确认）。
     *
     * 【r13·入口收拢改造】原实现借 `ChatDrawer.confirm(...)` 叠确认框 —— 那是抽屉时代的
     *   命令式 View 弹窗。抽屉已移除，这里改用全 App 统一的弹窗通道 [UiKit.showDialog]
     *   （与「没配模型」「打不开相册」等提示同一套壳、同一磨砂质感），
     *   顺带彻底断开本页对 `ChatDrawer` 的依赖。
     *
     * 【为什么不用 Compose 的 DhForm.Alert】本方法是从「清空当前对话」按钮的命令式回调里
     *   调进来的（不是 Composable 作用域），而 `DhForm.Alert` 标了 `@Composable`，
     *   在这里调会编译不过。`UiKit.showDialog` 是原生 Dialog，任何线程/上下文都能调。
     */
    fun confirmClearChat() {
        val act = com.dollhouse.app.ui.theme.UiKit.findActivity(context) ?: return
        com.dollhouse.app.ui.theme.UiKit.showDialog(
            act,
            "清空当前对话",
            com.dollhouse.app.ui.theme.UiKit.dialogMessage(act, "这段对话会从本机彻底删掉，不能恢复。"),
            "清空",
            android.view.View.OnClickListener { clearChat() },
            "取消",
            null
        )
    }

    /** 真清空：历史归零（摘要与总记忆库不动，那是两份独立数据）。 */
    fun clearChat() {
        // 【v2.8】展开态一并作废：历史已归零，留着旧 count 会在后续滚动里误摘新正文。
        prevCount = 0
        history = JSONArray()
        ChatHistoryStore.saveHistory(this)
        // 【新增】连「更早的历史」暂存位一起清掉，否则清空后记录顶上还挂着「加载更早」。
        PetPrefs.removeConvPrev(context, ChatSessions.currentId(context))
        renderHistory()
        refreshCtxRing()
    }

    /** 本地估算的当前上下文占用字符数（人设 + 记忆 + 历史正文）。 */
    fun ctxUsedChars(): Int {
        // 【r13·一人偶一对话】人设与记忆都按**当前会话绑定的那只人偶**取 ——
        //   写死默认那只会让环里显示的占用与实际请求不一致（换了人偶后数字不变）。
        val who = boundDollId()
        var n = DollProfile.persona(context, who).length
        n += Mem.injectText(context, who, Mem.MAX_INJECT_CHARS).length
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i)
            if (o != null) {
                n += o.optString("content", "").length
            }
        }
        return n
    }

    /**
     * 【r13·一人偶一对话】当前会话绑定的人偶 id（拿不到就退回默认配置）。
     *
     * 【为什么每次现算而不缓存】用户在聊天页里可能切会话（抽屉切换 → [reloadHistory]），
     *   缓存会带着上一只人偶的人设去发消息。这里只是一次 SharedPreferences 读 + 字符串前缀，
     *   相对一次网络请求可忽略。
     */
    internal fun boundDollId(): String {
        return try {
            val id = ChatSessions.currentId(context)
            val d = ChatSessions.dollOfConv(context, id)
            if (d.isEmpty()) DollStore.DEFAULT_ID else d
        } catch (unused: Throwable) {
            DollStore.DEFAULT_ID
        }
    }

    /**
     * 【r13·角色设定】会话是空的就铺上开场白。
     *
     * 【只在「当前会话一条消息都没有」时动手】这是自动路径的唯一判据 ——
     *   不判空的话每进一次聊天页都会追加一句，历史会越滚越长。
     *   用户想再看一遍，去人格页点「在聊天里重放开场白」（那条路径不判空，是刻意的）。
     *
     * 【为什么不走 push(this, ...)】那一刻 `history` 字段还是上一轮的残留 / 空的
     *   （本方法刻意排在 loadHistory 之前），走面板版会把**错误的内容**存回去。
     *   这里直接对「刚从盘上读出来的那份数组」追加并回写，语义明确。
     *
     * 【为什么要 try 包住】开场白是锦上添花的功能：它失败绝不能让聊天页打不开。
     */
    private fun seedGreetingIfEmpty() {
        try {
            val id = ChatSessions.currentId(context)
            // 【r19·清空后不再铺】用户手动清空过会话，不再自动铺开场白。
            if (PetPrefs.isConvCleared(context, id)) {
                return
            }
            val hist = try {
                JSONArray(PetPrefs.convHistory(context, id))
            } catch (ignored: Throwable) {
                JSONArray()
            }
            if (hist.length() > 0) {
                return
            }
            val who = boundDollId()
            val greeting = DollProfile.spec(context, who).greeting.trim()
            if (greeting.isEmpty()) {
                return
            }
            ChatHistoryStore.push(context, hist, "assistant", greeting, id)
            Logs.i("DollhousePersona", "[greeting] 铺开场白 len=" + greeting.length + " conv=" + id)
        } catch (t: Throwable) {
            Logs.w("DollhousePersona", "开场白自动铺设失败", t)
        }
    }

    /**
     * 刷新顶栏的进度环。
     *
     * 【口径 v0.0.7】当前聊天条数 ÷ 记忆库容量，表示「这个库用到多满」。
     * 【为什么不再用阈值】旧系统按「条数到阈值触发归档」，阈值随旧系统一起删了；
     *   新系统是「每轮自动抽取」，没有阈值这个概念，环改为反映库容量占用。
     */
    fun refreshCtxRing() {
        val cap = Math.max(1, Mem.MAX_ENTRIES)
        ChatState.ctxRatio = (history.length() / cap.toFloat()).coerceIn(0f, 1f)
    }

    fun regenerate() {
        if (waiting) {
            return
        }
        if (history.length() == 0) {
            return
        }
        val last = history.optJSONObject(history.length() - 1)
        if (last == null || "assistant" != last.optString("role")) {
            return
        }
        history.remove(history.length() - 1)
        ChatHistoryStore.saveHistory(this)
        renderHistory()
        setWaiting(true)
        addThinking("重新想过…")
        // 【v0.0.1】只有人偶专属会话才让人偶头顶说话：其他对话框的回复不再上气泡。
        if (ChatSessions.isPet(context)) {
            PetBus.say("让我再想想…", 4000L)
        }
        // 【r13·一人偶一对话】重生成同样按当前会话绑定的人偶组装请求。
        askModel(ChatHistoryStore.buildRequest(context, history, boundDollId()), 0, true)
    }

    fun onSend() {
        if (waiting) {
            return
        }
        if (browsingArchives) {
            return
        }
        // 【坑】上一轮的「等 OCR」闸门可能还挂着（请求已回、闸门未清），先作废，避免串到这一轮。
        sendPending = false
        var text = ChatState.input.trim()
        val str2 = pendingImage
        val str3 = pendingText
        if (text.isEmpty() && str2 == null && str3 == null) {
            return
        }
        if (!canChat()) {
            com.dollhouse.app.ui.home.HomeUi.showNotice(context, "还没有可用模型", "请先到「设置 → 模型」添加模型，才能发送消息。")
            return
        }
        if (str3 != null) {
            text = (if (text.isEmpty()) "" else text + "\n\n") + "【文件内容】\n" + str3
        }
        if (str2 != null && text.isEmpty()) {
            text = "看看这张图。"
        }
        ChatState.input = ""
        com.dollhouse.app.data.ChatDraftStore.clear(context, boundDollId(), ChatSessions.currentId(context))
        // 【顺序】先落库、再挂气泡 —— 这样气泡能拿到它在 history 里的真实下标，
        //   长按菜单才定位得到（见 appendBubble 的说明）。
        ChatHistoryStore.push(this, "user", text, str2)
        appendBubble(text, true, str2, history.length() - 1)
        if (ChatSessions.isPet(context)) {
            PetBus.say(if (str2 != null) "让我看看…" else "让我想想…", 2500L)
        }
        setWaiting(true)
        addThinking(if (str2 != null) "正在看图…" else "正在思考…")
        // 【OCR】先把还没扫过的图在后台补扫成文字，扫完再真正发请求。
        ensureOcrThenSend()
    }

    /**
     * 【OCR】发请求前的闸门：历史里还没扫过的图先补扫成文字，扫完才发。
     * 【为什么要有这一步】请求正文里的图片文字是发请求那一刻从缓存取的，没扫过就会漏；
     *   这里保证「点发送 → 后台花几秒扫图 → 带着文字发出去」，界面上只看到「正在看图…」。
     * 【坑】OCR 期间用户可能删了消息、切到归档页或自己按了停止：回来先查等待态再发。
     */
    private fun ensureOcrThenSend() {
        sendPending = true
        // 立刻把附件条文案切成「正在识别」，让人知道后台在干活（不是卡住了）。
        refreshAttachStrip()
        // 【r15】当前模型会看图（原图直送）时跳过本地 OCR：
        //   图片将以 base64 直接送模型，本地扫出来的文字用不上，扫了纯属白等几秒。
        if (ChatHistoryStore.usesDirectVision(context)) {
            sendPending = false
            clearAttachment()
            askModel(ChatHistoryStore.buildRequest(context, history, boundDollId()), 0, true)
            return
        }
        OcrEngine.recognizePending(context, history, Runnable {
            if (!sendPending || !waiting) {
                return@Runnable
            }
            sendPending = false
            // 【OCR】扫完了，现在才真正收掉附件条（发送瞬间挂起，避免抢在 OCR 前清空）。
            clearAttachment()
            // 【OCR】扫完再组装请求：这时图片文字已在缓存里，正文才带得上。
            // 【r13·一人偶一对话】请求按**当前会话绑定的那只人偶**组装人设与记忆。
            askModel(ChatHistoryStore.buildRequest(context, history, boundDollId()), 0, true)
        })
    }

    fun askModel(jSONArray: JSONArray, i: Int, z: Boolean) {
        val personaId = boundDollId()
        val conversationId = ChatSessions.currentId(context)
        if (activeGeneration == null) {
            activeGeneration = com.dollhouse.app.data.GenerationTask(
                personaId = personaId,
                conversationId = conversationId,
                historySnapshot = JSONArray(jSONArray.toString())
            ).also { com.dollhouse.app.data.GenerationTasks.put(it) }
        }
        // 【r13·记忆分库】声明「这一轮属于哪只人偶」，供记忆工具写入时定位目标库。
        //   【为什么在这里】下面 runTools 会执行模型返回的 remember 调用，
        //   而工具执行时拿不到「正在跟谁聊」；不先声明就会写错库。
        ChatToolRegistry.setDoll(personaId)
        // 【交互】记忆工具始终可用（AI 自己决定记什么）；联网工具按用户开关决定。
        val tools = if (z) buildTools() else null
        // 【思考框】计时起点：这一刻算「模型开始思考」。
        // 【r13·流式】走流式时这个值只作兜底 —— 真实思考耗时由 SSE 侧量（首个思考增量 →
        //   首个正文增量），比「整段往返」准得多；非流式仍是估算（见 [handleReply]）。
        askStartMs = System.currentTimeMillis()
        task = DeepSeekClient.Task()
        // 【规格书】请求按「供应商 + 模型」组装：地址与协议头全走 ApiClient。
        //   取值用 PetPrefs 三件套：配了供应商时它们转发到 ProviderStore（拿的就是当前选中项），
        //   没配供应商时回退旧键，保证升级后老配置也照常能用（pv 为 null 时走裸 baseUrl + Bearer）。
        val pv = ProviderStore.activeProvider(context)
        val cfg = com.dollhouse.app.data.PersonaChatConfigStore.effective(context, personaId, conversationId)
        val selectedPv = if (cfg.providerId.isNotEmpty()) ProviderStore.findProvider(context, cfg.providerId)?.takeIf { it.enabled } else pv
        val key = if (cfg.providerId.isNotEmpty()) ProviderStore.keyOf(context, selectedPv) else PetPrefs.apiKey(context)
        val baseUrl = if (cfg.providerId.isNotEmpty()) selectedPv?.baseUrl else PetPrefs.baseUrl(context)
        val modelId = if (cfg.modelId.isNotEmpty()) {
            com.dollhouse.app.data.ModelStore.findModel(context, cfg.modelId)?.displayName ?: PetPrefs.model(context)
        } else PetPrefs.model(context)
        // 【r13·流式】用户可在设置里关掉；关掉即整条走原来的非流式路径（零行为差异）。
        if (useStream && cfg.streamOutput) {
            askModelStream(selectedPv, key, baseUrl, modelId, jSONArray, tools, i, z)
            return
        }
        DeepSeekClient.chatRaw(selectedPv, key, baseUrl, modelId, jSONArray, tools, samplingParams(false), task, object : DeepSeekClient.RawCallback {
            override fun onMessage(jSONObject: JSONObject?, str: String?) {
                // 用户主动停止：静默收尾，不要当故障报出来。
                if (DeepSeekClient.STOPPED == str) {
                    return
                }
                if (str != null) {
                    if (str.startsWith("TOOLS_UNSUPPORTED") && z) {
                        askModel(jSONArray, i, false)
                        return
                    }
                    // 【三件套】思考参数被服务端拒（参数类 400）时，剥掉参数只重试一次。
                    // 【坑】必须限一次，否则同一条错误会无限重试把对话卡死。
                    if (!thinkDegraded
                            && ThinkLevel.shouldInject(PetPrefs.thinkLevel(context))
                            && str.indexOf("400") >= 0) {
                        thinkDegraded = true
                        askModel(jSONArray, i, z)
                        return
                    }
                    finishWithError(str)
                    return
                }
                val msg = jSONObject
                if (msg != null) {
                    val optJSONArray = msg.optJSONArray("tool_calls")
                    if (optJSONArray != null && optJSONArray.length() > 0 && i < MAX_TOOL_ROUNDS) {
                        runTools(jSONArray, optJSONArray, msg, i)
                        return
                    }
                    TokenStat.recordFrom(context, msg)
                }
                // 【思考框】正文与思考内容分别取出：原来只取 content，reasoning_content 拿到就丢。
                // 【坑】耗时用「发起请求 → 回调回来」的整段近似（非流式无法只量思考段），
                //       夹在网络往返与正文生成之间，数值会偏长。
                val contentText = msg?.optString("content", "") ?: ""
                val reasoningText = msg?.optString("reasoning_content", "") ?: ""
                val costMs = System.currentTimeMillis() - askStartMs
                handleReply(contentText, reasoningText, costMs)
            }
        })
    }

    /**
     * 【r13·流式】SSE 路径：边收边刷思考框与气泡，收尾后再走既有 [handleReply] 落盘。
     *
     * 【为什么还要复用 handleReply】落盘 / 人偶气泡 / 空内容兜底 / 工具轮分流这些既有规则
     *   一条都不能少（用户要求「不要丢功能」）。流式只负责「把它做得更早、更细地显示」，
     *   收尾仍交给同一条收口路径 —— 行为差异被压到最小。
     *
     * 【降级】三处让路，全部只影响本次请求：
     *   ① 供应商开了 Responses 接口 → 客户端直接拒绝（事件格式不同，本版不做）；
     *   ② 「流式不支持」类错误 → 本次改走非流式重试一次；
     *   ③ 已经收到正文再出错 → 不重试（避免同一句话出现两遍），按错误收尾。
     */
    private fun askModelStream(pv: Provider?, key: String?, baseUrl: String?, modelId: String?,
                               body: JSONArray, tools: JSONArray?, i: Int, z: Boolean) {
        streamText = StringBuilder()
        streamThink = StringBuilder()
        lastFlushMs = 0L
        // 【在途气泡】先铺一个空气泡占位：用户能立刻看到「她开始打字了」。
        //   【为什么不用 thinking 占位】那是一条「正在思考…」的灰条，与流式正文并不冲突 ——
        //   思考阶段仍显示灰条，正文一到就撤掉并换空气泡。
        var liveIndex = -1
        DeepSeekClient.chatStream(pv, key, baseUrl, modelId, body, tools, samplingParams(false), task,
            object : DeepSeekClient.StreamCallback {
                override fun onDelta(kind: DeepSeekClient.StreamKind, text: String, toolIndex: Int,
                                     toolId: String?, toolName: String?, toolArgs: String?) {
                    when (kind) {
                        DeepSeekClient.StreamKind.THINK -> {
                            streamThink?.append(text)
                            // 思考阶段：把灰条文案换成实时推理（点开思考框前先给个「在动」的反馈）。
                            flushStream(force = true)
                        }
                        DeepSeekClient.StreamKind.TEXT -> {
                            streamText?.append(text)
                            if (liveIndex < 0) {
                                // 首段正文到达：撤掉「正在思考…」灰条，铺一条空气泡承载流式正文。
                                removeThinking()
                                liveIndex = ChatState.items.size
                                ChatState.items = ChatState.items + ChatItem(
                                    role = "assistant",
                                    text = "",
                                    image = null,
                                    index = -1,
                                    isPrev = false,
                                    isSummary = false
                                )
                            }
                            flushStream(force = false)
                        }
                        DeepSeekClient.StreamKind.TOOL -> {
                            // 工具分片本身不进气泡；标签提示一下就够（真正的执行在收尾后走 runTools）。
                            setThinkingLabel("我去查一下…")
                        }
                    }
                }
            },
            object : DeepSeekClient.StreamDone {
                override fun onDone(message: JSONObject?, error: String?, thinkMs: Long) {
                    val fullText = streamText?.toString() ?: ""
                    val fullThink = streamThink?.toString() ?: ""
                    // 【收尾】去掉流式占位气泡：最终内容统一由 handleReply / runTools 重新铺，
                    //   避免「流式气泡 + 落盘气泡」出现两条重复消息。
                    if (liveIndex >= 0) {
                        // 只按占位身份移除，不用可能已经过期的列表下标裁剪历史。
                        ChatState.items = ChatState.items.filterNot { it.index < 0 }
                        liveIndex = -1
                    }
                    streamText = null
                    streamThink = null
                    if (error != null) {
                        if (DeepSeekClient.STOPPED == error) {
                            // 用户主动停止：把已收到的部分照常落盘，不要丢。
                            if (fullText.trim().isNotEmpty()) {
                                handleReply(fullText, fullThink, thinkMs)
                            } else {
                                setWaiting(false)
                                removeThinking()
                            }
                            return
                        }
                        // 【降级】还没吐正文就失败 → 本次改走非流式重试一次。
                        //   RESPONSES_NO_STREAM 也算此类：那家接口本就不支持这种流。
                        if (fullText.isEmpty() && fullThink.isEmpty() && useStream) {
                            Logs.i("DollhouseMemo", "[stream] 降级非流式：" + error)
                            useStream = false
                            setWaiting(false)
                            removeThinking()
                            askModel(body, i, z)
                            return
                        }
                        // 【工具不支持】与流式无关，交给既有降级（剥掉 tools 重试）。
                        if (error.startsWith("TOOLS_UNSUPPORTED") && z) {
                            askModel(body, i, false)
                            return
                        }
                        // 【思考参数被拒】与流式无关，剥参数重试一次。
                        if (!thinkDegraded
                                && ThinkLevel.shouldInject(PetPrefs.thinkLevel(context))
                                && error.indexOf("400") >= 0) {
                            thinkDegraded = true
                            askModel(body, i, z)
                            return
                        }
                        finishWithError(error)
                        return
                    }
                    val msg = message
                    if (msg != null) {
                        val tc = msg.optJSONArray("tool_calls")
                        if (tc != null && tc.length() > 0 && i < MAX_TOOL_ROUNDS) {
                            // 工具轮：思考内容先挂上（这一轮到此为止，正文要等工具跑完的下一轮）。
                            TokenStat.recordFrom(context, msg)
                            runTools(body, tc, msg, i)
                            return
                        }
                        TokenStat.recordFrom(context, msg)
                    }
                    val contentText = msg?.optString("content", "") ?: ""
                    val reasoningText = msg?.optString("reasoning_content", "") ?: ""
                    // 【耗时】流式给了真实思考段耗时（thinkMs）；拿不到就退回整段近似。
                    val cost = if (thinkMs > 0) thinkMs else System.currentTimeMillis() - askStartMs
                    handleReply(contentText, reasoningText, cost)
                }
            })
    }

    /**
     * 【r13·流式】把累积中的正文刷到流式气泡上。
     *
     * 【节流】正文按 60ms 窗口合并；思考在「首个增量」时强制刷一次（让灰条立刻有内容），
     *   之后随正文一起走同一节奏 —— 思考内容变化不需要逐字可见。
     */
    private fun flushStream(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushMs < 60) {
            return
        }
        lastFlushMs = now
        val txt = streamText?.toString() ?: ""
        val think = streamThink?.toString() ?: ""
        if (think.isNotEmpty()) {
            ChatState.streamThink = think
        }
        if (txt.isEmpty()) {
            return
        }
        // 找到流式占位气泡（最后一条 index == -1 的 assistant）并原地更新文本。
        val cur = ChatState.items
        if (cur.isEmpty()) {
            return
        }
        val last = cur[cur.size - 1]
        if (last.role == "assistant" && last.index < 0 && !last.isSummary) {
            // 【r25·流式只换尾元素】旧写法 `cur.subList(0, n-1).toList() + last.copy(...)`
            //   每 60ms 都把「除尾项外的全部元素」重新拷进一个新 List，再拼一项 ——
            //   长会话（200 条）时是 200 次引用拷贝 + 2 个新 List，且每次都换掉列表身份，
            //   Compose 侧只能整体重新比对。现在只浅拷一层、替换最后一项：
            //   元素引用一个不变（下面 `itemsIndexed` 的 key 与内容比对才能短路），
            //   旧实现里「下标 key」会让整个尾部都重新组合。
            val next = ArrayList<ChatItem>(cur.size)
            next.addAll(cur)
            next[next.size - 1] = last.copy(text = txt, think = think.ifEmpty { null })
            ChatState.items = next
        } else {
            ChatState.items = cur + ChatItem("assistant", txt, null, -1, false, false,
                    think.ifEmpty { null }, 0L)
        }
        if (!ChatState.holdScroll) {
            ChatState.requestScrollBottom()
        }
    }

    private fun samplingParams(z: Boolean): JSONObject {
        val jSONObject = JSONObject()
        val cfg = com.dollhouse.app.data.PersonaChatConfigStore.effective(context, boundDollId(), ChatSessions.currentId(context))
        try {
            if (z) {
                jSONObject.put("temperature", if (z) 0.7 else cfg.temperature)
                jSONObject.put("top_p", cfg.topP)
                jSONObject.put("max_tokens", cfg.maxTokens)
                jSONObject.put("repeat_penalty", 1.15)
                val jSONArray = JSONArray()
                jSONArray.put("\n用户：")
                jSONArray.put("\n主人：")
                jSONArray.put("\nUser:")
                jSONArray.put("\nAssistant:")
                jSONArray.put("\nassistant:")
                jSONObject.put("stop", jSONArray)
            } else {
                jSONObject.put("temperature", cfg.temperature)
                jSONObject.put("max_tokens", cfg.maxTokens)
            }
            // 【三件套】思考程度（灯泡）：按档位补请求体字段。
            // 【坑】只有 2~5 档（加深）才发一个 reasoning_effort；0/1 档一律不发 ——
            //       enable_thinking 之类非标准字段在非流式请求下会被 Qwen 硬 400。
            //       若仍被服务端拒（参数类 400），onMessage 里会剥掉参数重试一次。
            val thinkLevel = cfg.thinkLevel
            if (!thinkDegraded && ThinkLevel.shouldInject(thinkLevel)) {
                ThinkLevel.parameters(jSONObject, thinkLevel)
            }
        } catch (unused: Throwable) {
        }
        return jSONObject
    }

    fun runTools(jSONArray: JSONArray, jSONArray2: JSONArray, jSONObject: JSONObject, i: Int) {
        setThinkingLabel("我去查一下…")
        if (ChatSessions.isPet(context)) {
            PetBus.say("我去查一下…", 12000L)
        }
        Thread(Runnable {
            try {
                val jSONObject2 = JSONObject()
                jSONObject2.put("role", "assistant")
                jSONObject2.put("content", jSONObject.optString("content", ""))
                jSONObject2.put("tool_calls", jSONArray2)
                jSONArray.put(jSONObject2)
            } catch (unused: Throwable) {
            }
            for (i2 in 0 until jSONArray2.length()) {
                val optJSONObject = jSONArray2.optJSONObject(i2)
                if (optJSONObject != null) {
                    val optJSONObject2 = optJSONObject.optJSONObject("function")
                    val optString = optJSONObject2?.optString("name", "") ?: ""
                    val optString2 = optJSONObject2?.optString("arguments", "{}") ?: "{}"
                    val doSearch = runTool(optString, optString2)
                    try {
                        val jSONObject3 = JSONObject()
                        jSONObject3.put("role", "tool")
                        jSONObject3.put("tool_call_id", optJSONObject.optString("id"))
                        jSONObject3.put("content", doSearch)
                        jSONArray.put(jSONObject3)
                    } catch (unused3: Throwable) {
                    }
                }
            }
            post {
                if (isAttachedToWindow) {
                    askModel(jSONArray, i + 1, true)
                }
            }
        }, "feiyu-tools").start()
    }

    /** 【交互】联网工具已收口到 ChatToolRegistry / SearchTool；以下两处为兼容薄壳。 */
    fun doSearch(str: String?): String {
        return ChatToolRegistry.execute("web_search", "{\"query\":\"" + str + "\"}",
                webSearch = true, remember = false)
    }

    private fun buildTools(): JSONArray {
        // 【三件套】两个开关：联网工具（设置页）+ 记忆工具（记忆库设置 → 自动保存记忆）。
        //   【r13·新记忆系统】记忆开关从旧的 memAutoSave 换成 mem2Auto —— 与新键一致。
        return ChatToolRegistry.buildSchema(PetPrefs.webSearchEnabled(context),
                PetPrefs.mem2Auto(context))
    }

    /** 工具分发的宿主侧入口：入参原样透传给注册表，由各工具自己解析（search 取 query、remember 取 title/text）。 */
    private fun runTool(str: String?, str2: String?): String {
        // 【门控】带上本轮的两个开关，让「关掉联网 / 关掉记忆」在执行侧也真正生效
        //   （见 ChatToolRegistry.isAllowed 的注释：只在下发侧门控挡不住「模型记得工具名」）。
        return ChatToolRegistry.execute(str!!, str2,
                PetPrefs.webSearchEnabled(context), PetPrefs.mem2Auto(context))
    }

    fun finishWithError(str: String?) {
        activeGeneration?.let { com.dollhouse.app.data.GenerationTasks.finish(it.generationId) }
        activeGeneration = null
        task = null
        // 【r13·不中断】等待态与思考框与视图无关，先收掉 ——
        //   否则用户离开期间出错，回来时输入框永远停在「停止」。
        setWaiting(false)
        removeThinking()
        if (isAttachedToWindow) {
            appendBubble("（出错了：" + str + "）", false, null)
        }
    }

    /** 兼容入口：不带思考内容的旧签名（外部契约不变）。 */
    fun handleReply(str: String?) {
        handleReply(str, "", 0L)
    }

    /**
     * 【思考框】带推理内容与耗时的版本。
     * 【分流】
     *   正文非空 → 自聊池先铺思考框再铺气泡（折叠条正好落在气泡正上方）；人偶池照旧只铺气泡。
     *   正文为空 → 人偶池拿思考内容顶上（她必须开口，不能只剩空气泡）；
     *              自聊池只铺思考框，既不铺空气泡也不写历史（思考内容不落盘）。
     *   两者都空 → 走既有错误气泡。
     */
    fun handleReply(str: String?, reasoning: String?, costMs: Long) {
        activeGeneration?.let { com.dollhouse.app.data.GenerationTasks.finish(it.generationId) }
        activeGeneration = null
        task = null
        var body = str ?: ""
        var think = reasoning?.trim() ?: ""
        val pet = ChatSessions.isPet(context)
        if (body.trim().isEmpty()) {
            if (think.isEmpty()) {
                if (isAttachedToWindow) {
                    setWaiting(false)
                    removeThinking()
                    finishWithError("模型返回了空内容")
                } else {
                    // 【r13·不中断】人已离开：错误没地方显示，但等待态必须收掉，
                    //   否则回来时输入框永远停在「停止」。
                    setWaiting(false)
                    removeThinking()
                }
                return
            }
            if (pet) {
                // 思考不是人偶的最终发言；保留思考记录，并明确提示本次没有正文。
                setWaiting(false)
                removeThinking()
                ChatHistoryStore.push(this, "assistant", "", null, think, costMs)
                if (isAttachedToWindow) appendBubble("", false, null, history.length() - 1)
                finishWithError("模型本次只返回了思考过程，没有生成回答，请重试")
                return
            } else {
                // 【r13·思考过程】纯思考内容（正文为空）：不再只挂一个「内存里的思考框」——
                //   改为「正文为空 + 带推理」落盘。重铺时那条气泡不渲染文字（[ChatUi.Bubble]
                //   对空正文不画 Text），只渲染它上方的思考框 —— 观感与改造前一致，
                //   但**切成历史后仍然看得到**，这正是本轮要的「思考可回看」。
                setWaiting(false)
                removeThinking()
                ChatHistoryStore.push(this, "assistant", "", null, think, costMs)
                if (isAttachedToWindow) {
                    appendBubble("", false, null, history.length() - 1)
                }
                return
            }
        }
        // 【r11】好感度已删除：只把老记录可能残留的记账行剥掉，不再解析、不再记账。
        var trim = ChatHistoryStore.stripLegacyAffection(body)
        if (trim.isEmpty()) {
            trim = body.trim()
        }
        // 【r13·角色设定】输出替换里「影响历史」的那一批：在写库前就应用 ——
        //   于是模型下一轮看到的也是清洗后的文本（这正是勾掉「只影响显示」的语义）。
        trim = ChatHistoryStore.stripRules(context, boundDollId(), trim, true, false)
        // 【r13 修 bug·「返回再进来就中断 AI 发话」】落盘**必须无条件执行**。
        //
        // 【曾经的 bug】原实现在本方法开头写了 `if (!isAttachedToWindow) return` ——
        //   用户发出消息后立刻返回列表 / 切到别页，等模型回来时视图已 detach，
        //   这一句直接把整条回复**丢弃**（不落盘、不进历史）。
        //   用户再进聊天页只能看到自己那句、AI 那边永远没回应，表现就是「中断了」。
        //
        // 【修法】落盘与视图无关，先落；界面更新才判 `isAttachedToWindow`。
        //   离屏期间照常写历史，回来时 [reloadHistory] 重铺即可看到完整一轮。
        ChatHistoryStore.push(this, "assistant", trim, null, if (think.isEmpty()) null else think, costMs)
        if (!isAttachedToWindow) {
            // 离屏：没有气泡可加，收掉等待态即可（回来由 reloadHistory 重铺）。
            setWaiting(false)
            removeThinking()
            return
        }
        setWaiting(false)
        removeThinking()
        // 【r13·思考过程】不再往「内存思考框」写：推理已随消息落盘（见上面的 push），
        //   本条气泡经 appendBubble 落到 [ChatState.items] 后，思考框由 [ChatUi] 按 think 渲染。
        appendBubble(trim, false, null, history.length() - 1)
        if (pet) {
            PetBus.say(ChatHistoryStore.bubbleVersion(trim), 5000L)
        }
    }

    /** 用户点「停止」：断掉在途连接，静默收尾（不报网络错误）。 */
    fun stopGenerating() {
        activeGeneration?.let { com.dollhouse.app.data.GenerationTasks.finish(it.generationId) }
        activeGeneration = null
        // 【OCR】若还在等 OCR 扫图，撕掉闸门，扫描回来直接作废（不再发请求）。
        sendPending = false
        val t = task
        t?.cancel()
        task = null
        setWaiting(false)
        removeThinking()
    }

    /** 按有无 API key 决定顶部提示条显隐。 */
    fun refreshHint() {
        ChatState.hint = if (com.dollhouse.app.ui.home.HomeUi.hasChatTarget(context)) {
            null
        } else {
            "还没有可用的供应商模型，点这里去设置 →"
        }
    }

    // 能否发消息：必须有配置。
    private fun canChat(): Boolean {
        return PetPrefs.hasKey(context)
    }

    /* ------------------------- 历史重铺 ------------------------- */

    // 把 history 重铺成可渲染快照（Compose 侧按状态重组）。
    private fun renderHistory() {
        summaryIndex = -1
        // 【瞬态作废】占位与思考框都只对「当前这一轮请求」有效：重铺即换了一份历史，
        //   旧引用一律作废（等价于旧 View 版 removeAllViews 把节点整批摘掉）。
        ChatState.thinking = null
        ChatState.streamThink = null
        var tailIndex = -1
        val list = ArrayList<ChatItem>()
        // 【r13·角色设定】输出替换里「只影响显示」的那一批：渲染时清洗，不动历史。
        //   【为什么在这里读一次】下面要逐条消息用规则；每条都去解析一次 spec JSON
        //   会让长会话的首帧明显变慢。读一次、传进循环，成本从 O(条数) 降到 O(1)。
        val outSpec = try {
            DollProfile.spec(context, boundDollId())
        } catch (ignored: Throwable) {
            null
        }
        // 【v2.8】入口放在「空历史」判断之前：自动收回后若一条正文都不剩，也得能从界面上点回来。
        ChatState.hasPrev = ChatHistoryStore.hasPrev(context)
        if (history.length() == 0) {
            // 【r19·彻底删掉硬编码占位气泡】原来这里会凭空塞一条：
            //   ChatItem("assistant", "我是小肥鱼～ 有什么想跟我说的吗？", ...)
            //   后果有两个，都是用户报过的：
            //   ① 「新建空白模板也有开场白」—— 数据层明明是空的（conv_xxx = []，persona_spec.greeting = ""），
            //      界面上却显示一条「她说的」消息。用户要的是「空白模板全空」。
            //   ② 「重新生成 / 复制按钮点了没反应」—— 这条假气泡 role=assistant 且是最后一条，
            //      正好命中操作条挂载条件；但真实 history 为空，regenerate() / copyLastReply()
            //      第一句判空就 return，于是按钮长得能点、点了毫无反馈。
            // 【现在的口径】空历史 = 空列表，什么都不渲染。
            //   开场白只走**用户自己设置的那份**（persona_spec.greeting，
            //   自动铺设见 seedGreetingIfEmpty，手动重放见 DollGreeting.insert）。
            ChatState.items = list
            ChatState.prevTailIndex = -1
            if (!ChatState.holdScroll) {
                ChatState.requestScrollBottom()
            }
            return
        }
        // 【r13·摘要条只显示最新一条】每插一次总结就多一条 kind=summary，全渲染出来会在
        //   聊天顶部堆成一摞分割条（用户截图里堆了 4 条）。这里只渲染**最后一条**。
        //   【为什么数据一条不删】旧摘要仍留在 history 里、仍由 appendSummaries 注入上下文 ——
        //     它代表「它之前那整段对话」，删掉就等于把更早的内容从模型记忆里抹掉。
        //     对界面它是噪音，对上下文它是必需，所以只藏显示、不删数据。
        //   【为什么最新的就够】摘要生成本就是「从最近一条摘要往后压」，越新覆盖越靠后、
        //     信息越全；界面上只需要一条「此前全部对话的当前概括」。
        val lastSummary = findLastSummaryIndex(history, history.length() - 1)
        // 【v2.8·P3】补回批不再按下标推导（见 mergePrev 打的 _prev 标记），这里只判「有没有摘要头」。
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i) ?: continue
            val role = o.optString("role")
            if ("summary" == o.optString("kind")) {
                // 【只渲染最新】更早的摘要跳过渲染（数据仍原样留在 history 里）。
                if (i != lastSummary) {
                    continue
                }
                // 【r13】带上覆盖条数，由分割条显示成「历史对话摘要 · 覆盖 N 条」。
                list.add(ChatItem("summary", o.optString("content"), null, i, false, true,
                        null, 0L, o.optInt("cover", 0)))
                summaryIndex = list.size - 1
                continue
            }
            if ("system" != role) {
                val isPrev = ChatHistoryStore.isPrevItem(o)
                // 【r13·思考过程】把这条消息当初的推理一并带出（老数据没有该键 → null，正常退化）。
                val think = o.optString("think", null)
                val thinkMs = o.optLong("thinkMs", 0L)
                // 【r13·角色设定】「只影响显示」的输出替换：在这里应用，历史原文不动。
                //   `forHer = "assistant" == role`，与写入侧的同名判据一致。
                var text = o.optString("content")
                if (outSpec != null && outSpec.rules.isNotEmpty()) {
                    text = PersonaBuilder.applyRules(outSpec, text, true, "assistant" == role, "user" == role)
                }
                list.add(ChatItem(role, text, o.optString("image", null), i, isPrev, false,
                        if (think.isNullOrEmpty()) null else think, thinkMs))
                // 【v2.8·P3】补回批的尾部节点按来源标记认。
                if (isPrev) {
                    tailIndex = list.size - 1
                }
            }
        }
        ChatState.items = list
        ChatState.prevTailIndex = tailIndex
        if (!ChatState.holdScroll) {
            ChatState.requestScrollBottom()
        }
    }

    /* ------------------------- 滚动 / 收回 ------------------------- */

    /**
     * 【v2.8】滚动回调：展开态下补回批整批滑出视口上沿（看不到上面那批消息了），就自动收回。
     * 【Compose】改由「首个可见项下标」判定：越过补回批尾节点即视为滑出；已滚到底也算。
     */
    internal fun onScrolled(firstVisibleIndex: Int, atBottom: Boolean) {
        if (collapsing || ChatState.holdScroll || prevCount <= 0) {
            return
        }
        val tail = ChatState.prevTailIndex
        // 【判据一】补回批最后一个节点已被推到视口上沿之上。
        val passed = tail >= 0 && firstVisibleIndex > tail
        // 【判据二】已滑到列表底部并继续拉：底部态同样算「看不到上面那批」。
        //  加 firstVisibleIndex > 0 是为了排除「内容很短、首帧就到底」的误判。
        val bottomed = atBottom && firstVisibleIndex > 0
        if (!passed && !bottomed) {
            return
        }
        val n = prevCount
        collapsing = true
        val ok = ChatHistoryStore.collapsePrev(this, n)
        // 【坑】不论成败都清掉展开态：失败说明 history 结构已经在展开期间被改过，
        //       留着 prevCount 会让每帧滚动回调都重试一次（白白构建 JSON），得不偿失；
        //       数据一条不动，用户仍在展开态里，只是不再自动收回。
        prevCount = 0
        if (ok) {
            reloadHistory()
            scrollToSummary()
        }
        post {
            collapsing = false
        }
    }

    private fun setWaiting(z: Boolean) {
        waiting = z
        ChatState.waiting = z
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 3
        /**
         * 学习示例池上限。
         *
         * 【为什么是 12】注入端每次只取尾部 3 条（见 `ChatHistoryStore.appendLearnExamples`），
         *   12 条意味着「用户表达过但这次没被用到」的近期偏好还能留 4 轮，
         *   再多只是让 SharedPreferences 里的一个 JSON 越来越长、每次读都要全量解析。
         */
        private const val LEARN_MAX = 12
    }
}