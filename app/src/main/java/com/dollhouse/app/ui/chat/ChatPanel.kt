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
import com.dollhouse.app.ai.MemSummarizer
import com.dollhouse.app.ai.ThinkLevel
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ChatHistoryStore
import com.dollhouse.app.data.ImageStore
import com.dollhouse.app.data.MemDb
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
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
 *        防止 AI 反复查资料不收敛。改这里要小心 token 统计（TokenStat）与好感度解析的联动。
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
    /** 抽屉层：包住整块面板，不改变 ChatPanel 的构造签名与父子结构。 */
    private var drawer: ChatDrawer? = null
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
        ChatHistoryStore.loadHistory(this)
        renderHistory()
        refreshInputRow()
        refreshCtxRing()
        refreshHint()
        applyBackground()
    }

    /* ------------------------- 状态 → 界面 ------------------------- */

    /** 往消息列表追加一条气泡；[index] >= 0 时说明它已落库（用于判定操作条位置）。 */
    private fun appendBubble(text: String, mine: Boolean, image: String?) {
        ChatState.items = ChatState.items + ChatItem(
            role = if (mine) "user" else "assistant",
            text = text,
            image = image,
            index = -1,
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
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = 335544320
        context.startActivity(intent)
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
    }

    /** 工具条：模型配置面板。 */
    internal fun showModels() {
        SheetPanel.showModels(context, findLayer())
    }

    /** 工具条：思考程度面板。 */
    internal fun showThink() {
        SheetPanel.showThink(context, findLayer(), this)
    }

    /** 工具条：功能（记忆 / 发图）面板。 */
    internal fun showMemory() {
        SheetPanel.showMemory(context, findLayer(), this)
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
        GlobalBackground.install(this, UiKit.OPTION, false)
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
    fun openDrawer() {
        if (drawer == null) {
            drawer = ChatDrawer(this)
        }
        val layer = findLayer() ?: return
        drawer?.open(layer)
    }

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

    /** 抽屉关着就关掉并返回 true（给返回键用）。 */
    fun closeDrawerIfOpen(): Boolean {
        val d = drawer
        if (d != null && d.isOpen()) {
            d.close()
            return true
        }
        return false
    }

    /** 会话切换 / 新建 / 删除后：重新载入历史并整屏重铺。 */
    fun reloadHistory() {
        // 【v2.8】展开态是「当前会话这份 history」的瞬态：切了会话/重建了视图一律作废，
        //         否则旧 count 会在新会话里误把正文当成补回批摘掉。
        prevCount = 0
        ChatHistoryStore.loadHistory(this)
        renderHistory()
        refreshCtxRing()
    }

    /** 手动总结：点了就总结，不设条数门槛（内容为空时才挡）。 */
    fun summarizeNow() {
        // 【v2.9.1】每个静默 return 都补一条可见提示：全工程禁用 Toast，
        // 不给反馈用户只会以为「点了没反应」，无法区分「真失败」与「本就没得总结」。
        Logs.i("DollhouseMemo", "[入口] 点按立即总结 histLen=" + history.length()
                + " running=" + MemSummarizer.isRunning())
        if (MemSummarizer.isRunning()) {
            Logs.i("DollhouseMemo", "[入口] 被挡: 上一次还在进行中")
            flashMemo("上一次总结还在进行中")
            return
        }
        if (history.length() < 1) {
            Logs.i("DollhouseMemo", "[入口] 被挡: 历史为空")
            flashMemo("当前对话是空的，没什么可总结的")
            return
        }
        // 【v2.9.5】总结前先收回展开态：补回批是「临时回看」，要点早已进过摘要，
        //  把它们留在 history 里会被 transcript 当成新内容重复总结一遍
        //  （用户报障「第一次总结的内容没有被销毁」）。
        //  收回 = 原样退回暂存位（数据一条不丢），之后本次只总结「摘要 + 之后的消息」。
        if (prevCount > 0) {
            val folded = ChatHistoryStore.collapsePrev(this, prevCount)
            Logs.i("DollhouseMemo", "[入口] 收回展开态 ok=" + folded
                    + " prevCount=" + prevCount)
            prevCount = 0
            // 立即重铺：无论后续总结成败，界面上都要与内存一致（不再显示那批临时回看的消息）。
            renderHistory()
        }
        MemSummarizer.summarizeNow(this, null)
    }

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

    /** 清空当前对话（先二次确认）。 */
    fun confirmClearChat() {
        val d = drawer ?: run {
            val created = ChatDrawer(this)
            drawer = created
            val layer = findLayer()
            if (layer != null) {
                created.open(layer)
            }
            created
        }
        d.confirm("清空当前对话", "这段对话会从本机彻底删掉，不能恢复。", "清空", Runnable {
            clearChat()
        })
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

    /** 本地估算的当前上下文占用字符数（人名提示词 + 记忆 + 历史正文）。 */
    fun ctxUsedChars(): Int {
        var n = PetPrefs.SYSTEM_PROMPT.length
        n += MemDb.recentText(context, MemDb.MAX_INJECT_CHARS).length
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i)
            if (o != null) {
                n += o.optString("content", "").length
            }
        }
        return n
    }

    /**
     * 刷新顶栏的进度环。
     *
     * 【口径 v2.10.0】当前聊天条数 ÷ 记忆触发阈值，与 MemSummarizer 的 byCount 触发条件同源：
     *  涨到 100% 就是自动总结该触发的那一刻（触发后保留 KEEP_TAIL 条，环会回落到 8/阈值）。
     *  ctxUsedChars() 仍保留：MemSummarizer.maybeAuto 的 byRatio 分支还在用它，不能删。
     */
    fun refreshCtxRing() {
        val threshold = Math.max(1, PetPrefs.memThreshold(context))
        ChatState.ctxRatio = (history.length() / threshold.toFloat()).coerceIn(0f, 1f)
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
        askModel(ChatHistoryStore.buildRequest(this), 0, true)
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
            val intent = Intent(context, MainActivity::class.java)
            intent.flags = 335544320
            context.startActivity(intent)
            return
        }
        if (str3 != null) {
            text = (if (text.isEmpty()) "" else text + "\n\n") + "【文件内容】\n" + str3
        }
        if (str2 != null && text.isEmpty()) {
            text = "看看这张图。"
        }
        ChatState.input = ""
        appendBubble(text, true, str2)
        ChatHistoryStore.push(this, "user", text, str2)
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
        OcrEngine.recognizePending(context, history, Runnable {
            if (!sendPending || !waiting) {
                return@Runnable
            }
            sendPending = false
            // 【OCR】扫完了，现在才真正收掉附件条（发送瞬间挂起，避免抢在 OCR 前清空）。
            clearAttachment()
            // 【OCR】扫完再组装请求：这时图片文字已在缓存里，正文才带得上。
            askModel(ChatHistoryStore.buildRequest(context, history), 0, true)
        })
    }

    fun askModel(jSONArray: JSONArray, i: Int, z: Boolean) {
        // 【交互】记忆工具始终可用（AI 自己决定记什么）；联网工具按用户开关决定。
        val tools = if (z) buildTools() else null
        // 【思考框】计时起点：这一刻算「模型开始思考」。
        // 【坑】非流式请求（stream=false）拿不到思考阶段的起止，只能量整段往返；
        //       工具轮里每次重发 askModel 都会重置，所以显示的是「最后一轮」的耗时。
        askStartMs = System.currentTimeMillis()
        task = DeepSeekClient.Task()
        // 【规格书】请求按「供应商 + 模型」组装：地址与协议头全走 ApiClient。
        //   取值用 PetPrefs 三件套：配了供应商时它们转发到 ProviderStore（拿的就是当前选中项），
        //   没配供应商时回退旧键，保证升级后老配置也照常能用（pv 为 null 时走裸 baseUrl + Bearer）。
        val pv = ProviderStore.activeProvider(context)
        val key = PetPrefs.apiKey(context)
        val baseUrl = PetPrefs.baseUrl(context)
        val modelId = PetPrefs.model(context)
        DeepSeekClient.chatRaw(pv, key, baseUrl, modelId, jSONArray, tools, samplingParams(false), task, object : DeepSeekClient.RawCallback {
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

    private fun samplingParams(z: Boolean): JSONObject {
        val jSONObject = JSONObject()
        try {
            if (z) {
                jSONObject.put("temperature", 0.7)
                jSONObject.put("top_p", 0.9)
                jSONObject.put("repeat_penalty", 1.15)
                jSONObject.put("max_tokens", 220)
                val jSONArray = JSONArray()
                jSONArray.put("\n用户：")
                jSONArray.put("\n主人：")
                jSONArray.put("\nUser:")
                jSONArray.put("\nAssistant:")
                jSONArray.put("\nassistant:")
                jSONObject.put("stop", jSONArray)
            } else {
                jSONObject.put("temperature", 1.2)
                jSONObject.put("max_tokens", 500)
            }
            // 【三件套】思考程度（灯泡）：按档位补请求体字段。
            // 【坑】只有 2~5 档（加深）才发一个 reasoning_effort；0/1 档一律不发 ——
            //       enable_thinking 之类非标准字段在非流式请求下会被 Qwen 硬 400。
            //       若仍被服务端拒（参数类 400），onMessage 里会剥掉参数重试一次。
            val thinkLevel = PetPrefs.thinkLevel(context)
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
        return ChatToolRegistry.execute("web_search", "{\"query\":\"" + str + "\"}")
    }

    private fun buildTools(): JSONArray {
        // 【三件套】两个开关都从加号面板来：联网工具 + 记忆工具。
        return ChatToolRegistry.buildSchema(PetPrefs.webSearchEnabled(context),
                PetPrefs.memAutoSave(context))
    }

    /** 工具分发的宿主侧入口：入参原样透传给注册表，由各工具自己解析（search 取 query、remember 取 title/text）。 */
    private fun runTool(str: String?, str2: String?): String {
        return ChatToolRegistry.execute(str!!, str2)
    }

    fun finishWithError(str: String?) {
        task = null
        if (isAttachedToWindow) {
            setWaiting(false)
            removeThinking()
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
        task = null
        if (!isAttachedToWindow) {
            return
        }
        setWaiting(false)
        removeThinking()
        var body = str ?: ""
        var think = reasoning?.trim() ?: ""
        val pet = ChatSessions.isPet(context)
        if (body.trim().isEmpty()) {
            if (think.isEmpty()) {
                finishWithError("模型返回了空内容")
                return
            }
            if (pet) {
                body = think
                think = ""
            } else {
                ChatState.thinkingBox = ThinkingBox(think, costMs)
                return
            }
        }
        val matcher = AFFECTION_HEAD.matcher(body)
        val i: Int
        val z: Boolean
        if (matcher.find()) {
            i = try {
                matcher.group(1).toInt()
            } catch (unused: Throwable) {
                0
            }
            z = true
        } else {
            i = 0
            z = false
        }
        var trim = AFFECTION_ANY.matcher(body).replaceAll("").trim()
        if (trim.isEmpty()) {
            trim = if (z) "……" else body.trim()
        }
        if (z) {
            PetBus.affection(PetPrefs.addAffection(context, i))
        }
        ChatHistoryStore.push(this, "assistant", trim)
        if (!pet && think.isNotEmpty()) {
            ChatState.thinkingBox = ThinkingBox(think, costMs)
        }
        appendBubble(trim, false, null)
        if (pet) {
            PetBus.say(ChatHistoryStore.bubbleVersion(trim), 5000L)
        }
    }

    /** 用户点「停止」：断掉在途连接，静默收尾（不报网络错误）。 */
    fun stopGenerating() {
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
        ChatState.hint = if (PetPrefs.hasKey(context)) {
            null
        } else {
            "还没填 API key，点这里去设置 →"
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
        ChatState.thinkingBox = null
        var tailIndex = -1
        val list = ArrayList<ChatItem>()
        // 【v2.8】入口放在「空历史」判断之前：自动收回后若一条正文都不剩，也得能从界面上点回来。
        ChatState.hasPrev = ChatHistoryStore.hasPrev(context)
        if (history.length() == 0) {
            list.add(ChatItem("assistant", "我是小肥鱼～ 有什么想跟我说的吗？", null, -1, false, false))
            ChatState.items = list
            ChatState.prevTailIndex = -1
            if (!ChatState.holdScroll) {
                ChatState.requestScrollBottom()
            }
            return
        }
        // 【v2.8·P3】补回批不再按下标推导（见 mergePrev 打的 _prev 标记），这里只判「有没有摘要头」。
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i) ?: continue
            // 【新增】头部摘要（kind=summary）原本被 !"system" 判据静默跳过，现在渲染成可展开的分割标题。
            if ("summary" == o.optString("kind")) {
                list.add(ChatItem("summary", o.optString("content"), null, i, false, true))
                summaryIndex = list.size - 1
                continue
            }
            val role = o.optString("role")
            if ("system" != role) {
                val isPrev = ChatHistoryStore.isPrevItem(o)
                list.add(ChatItem(role, o.optString("content"), o.optString("image", null), i, isPrev, false))
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

        @JvmField
        internal val AFFECTION_HEAD: Pattern = Pattern.compile("^\\s*[\\[【]\\s*好感度\\s*[:：]\\s*([+-]?\\d+)\\s*[\\]】]\\s*")

        @JvmField
        internal val AFFECTION_ANY: Pattern = Pattern.compile("[\\[【]\\s*好感度\\s*[:：]\\s*[+-]?\\d+\\s*[\\]】]")
    }
}