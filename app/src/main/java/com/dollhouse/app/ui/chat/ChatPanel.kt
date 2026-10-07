package com.dollhouse.app.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.dollhouse.app.ui.theme.CtxRing
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.SheetPanel
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】聊天气泡面板本体：消息列表、输入行、附件条、AI 请求与流式回填。
 *
 * 【交互】对外通过 Controller 回调把「拖动 / 关闭」交给宿主（全屏聊天页 ChatActivity）；模型请求走 DeepSeekClient，联网工具走 WebSearch，图片附件走 ImageStore。
 *
 * 【坑】history 是整段对话上下文，每次请求都会带上；MAX_TOOL_ROUNDS 限制联网工具的最大轮次，防止 AI 反复查资料不收敛。改这里要小心 token 统计（TokenStat）与好感度解析的联动。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
class ChatPanel(
        context: Context,
        private val controller: Controller?,
        z: Boolean
) : LinearLayout(context), PickFileActivity.Listener {
    // 【交互】气泡构造已拆到 ChatBubbles；messages / scroller / history 因此改为包级可见。
    private lateinit var attachLabel: TextView
    private var attachStrip: LinearLayout? = null
    private lateinit var attachThumb: ImageView
    private var browsingArchives = false
    private lateinit var hint: TextView
    internal var history: JSONArray = JSONArray()
    private lateinit var input: EditText
    private var inputRow: LinearLayout? = null
    internal lateinit var messages: LinearLayout
    private var pendingImage: String? = null
    private var pendingText: String? = null
    private var pendingTextName: String? = null
    /** 【OCR】「等 OCR 扫完再发」的闸门是否仍然有效（用户中途停止 / 新一轮发送会置假）。 */
    private var sendPending = false
    internal lateinit var scroller: ScrollView
    /** 【裁剪】上次回填的聊天区宽高比：用来避免每次 applyBackground 都写盘。 */
    private var lastBgRatio = 0f
    private var sendBtn: ImageView? = null
    /** 【v2.8】工具条右侧的「记忆总结中」：仅在总结在途时可见，结束后消失。 */
    private var memoBusy: TextView? = null
    /** 【v2.8】聊天记录里「ⓘ 历史对话摘要」那条节点，总结完成后据此滚过去。 */
    private var summaryNode: View? = null
    /** 【v2.8】最近一次补回的「更早历史」条数；0 = 当前不是展开态。 */
    internal var prevCount = 0
    /** 【v2.8】展开态下补回批的最后一个节点：它滑出视口上沿就收回去。 */
    private var prevTailView: View? = null
    /** 【v2.8】收回过程中的重入闸：程序化滚动同样会触发滚动回调。 */
    private var collapsing = false
    /** 【v2.8】补回「更早的历史」时的一次性重铺：期间不自动滚底，否则展开态会被判据二立刻收回。 */
    internal var holdScroll = false
    /** 【v2.8】在途的「滚到摘要分割线」预绘制回调：重铺前必须摘掉，否则会落到已移除的节点上。 */
    private var pendingScroll: ViewTreeObserver.OnPreDrawListener? = null
    /** 【v2.8】「记忆总结中」的期望状态：工具条被归档页整体隐藏时用它重放。 */
    private var memoBusyOn = false
    /** 【v2.9.2】失败/中止提示显示中：期间 setMemoBusy(false) 不得隐藏它。 */
    private var flashHold = false
    /** 【v2.9.2】在途的 flash 收起计时器：新 flash 前先撤旧的，避免互踩。 */
    private var pendingFlash: Runnable? = null
    /** 输入行上方的工具条：模型配置 / 思考程度 / 记忆。 */
    private var toolRow: LinearLayout? = null
    /** 【三件套】思考参数被服务端拒（参数类 400）后置位：只降级重试一次，避免死循环。 */
    private var thinkDegraded = false
    internal var thinkingLabel: TextView? = null
    internal var thinkingView: View? = null
    private var waiting = false
    /** 上下文占用环（顶栏右侧），按本地估算字符数刷新。 */
    private var ctxRing: CtxRing? = null
    /** 当前在途请求的取消句柄；null 表示没有正在跑的请求。 */
    private var task: DeepSeekClient.Task? = null
    /** 【思考框】本次请求发出的时刻（毫秒），用于算「思考了 X 秒」。 */
    private var askStartMs = 0L
    /** 抽屉层：包住整块面板，不改变 ChatPanel 的构造签名与父子结构。 */
    private var drawer: ChatDrawer? = null

    interface Controller {
        fun onClose()
        fun onDrag(f: Float, f2: Float)
        /** 点顶栏的上下文环：打开「令牌消耗统计」页。 */
        fun onOpenTokenStat()
    }

    // 构造：搭骨架 → 载入历史 → 刷新输入行 → 套用聊天背景。
    init {
        this.waiting = false
        this.browsingArchives = false
        this.thinkDegraded = false
        orientation = LinearLayout.VERTICAL
        setBackground(UiKit.roundStroke(UiKit.OPTION, UiKit.CHAT_BORDER, context, 16f))
        build(z)
        // 【交互】记忆工具需要 Context，在这里登记一次（幂等）；联网工具是否下发看用户开关。
        ChatToolRegistry.ensure(context.applicationContext)
        ChatHistoryStore.loadHistory(this)
        renderHistory()
        refreshInputRow()
        refreshCtxRing()
        applyBackground()
    }

    // 尺寸换算：统一走 UiKit，避免多处重复实现。
    private fun dp(f: Float): Int {
        return UiKit.dp(context, f)
    }

    /** 【交互】气泡与占位控件的实际实现在 ChatBubbles；以下为薄壳，保持内部调用点不变。 */
    fun addBubble(str: String?, z: Boolean) {
        ChatBubbles.addBubble(this, str, z)
    }

    private fun addBubble(str: String?, z: Boolean, i: Int) {
        ChatBubbles.addBubble(this, str, z, i)
    }

    private fun addBubble(str: String?, z: Boolean, i: Int, str2: String?) {
        ChatBubbles.addBubble(this, str, z, i, str2)
    }

    private fun scrollToBottom() {
        ChatBubbles.scrollToBottom(this)
    }

    private fun addThinking(str: String?) {
        ChatBubbles.addThinking(this, str)
    }

    private fun setThinkingLabel(str: String?) {
        ChatBubbles.setThinkingLabel(this, str)
    }

    private fun removeThinking() {
        ChatBubbles.removeThinking(this)
    }

    // 一次性搭出消息区 / 输入行 / 附件条三层结构。
    private fun build(z: Boolean) {
        buildTopBar()
        buildScroller()
        buildToolBar()
        buildInputRow()
    }

    /** 顶栏：返回 / 抽屉 / 标题 / 上下文占用环 + 提示条（整条面板可拖动）。 */
    private fun buildTopBar() {
        val barRow = buildTopBarRow()
        buildCtxRing(barRow)
        buildHintBar()
    }

    /** 顶栏行：拖动区 + 返回键 + 抽屉键 + 标题（标题 weight=1 吃剩余宽度）。 */
    private fun buildTopBarRow(): LinearLayout {
        val linearLayout = LinearLayout(context)
        linearLayout.orientation = LinearLayout.HORIZONTAL
        linearLayout.gravity = Gravity.CENTER_VERTICAL
        // 【观感】左内边距收到 6dp：图标自带 38dp 命中区，视觉重心本就已经贴角，
        // 再留 14dp 整条顶栏会显得往右下偏移。
        // 【状态栏嵌入】本面板只在 ChatActivity（铺满 + 状态栏透明）里使用，
        // 顶部额外让出状态栏高度，否则标题会被状态栏文字压住。
        linearLayout.setPadding(dp(6.0f), UiKit.statusBarPad(context) + dp(8.0f),
                dp(6.0f), dp(8.0f))
        addView(linearLayout, LinearLayout.LayoutParams(-1, -2))
        val textView = TextView(context)
        textView.text = "Dollhouse"
        textView.textSize = 16.0f
        textView.setTextColor(UiKit.TITLE)
        textView.setPadding(0, dp(4.0f), 0, dp(4.0f))
        // 【顺序】标题的 addView 挪到返回键之后（见下方）：标题 weight=1 会吃掉剩余宽度，
        // 若先加标题，返回键会被挤到头部最右侧，箭头朝左「指向标题」，观感就是方向不对。
        val onTouchListener = object : View.OnTouchListener {
            private var lastX = 0f
            private var lastY = 0f
            override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
                val actionMasked = motionEvent.actionMasked
                if (actionMasked == MotionEvent.ACTION_DOWN) {
                    lastX = motionEvent.rawX
                    lastY = motionEvent.rawY
                    return true
                }
                if (actionMasked != MotionEvent.ACTION_MOVE) {
                    return false
                }
                val rawX = motionEvent.rawX
                val rawY = motionEvent.rawY
                controller?.onDrag(rawX - lastX, rawY - lastY)
                lastX = rawX
                lastY = rawY
                return true
            }
        }
        textView.setOnTouchListener(onTouchListener)
        linearLayout.setOnTouchListener(onTouchListener)
        // 【观感】返回键钉在左上角：改成无底扁平图标 + 38dp 命中区，靠 UiKit.press 的
        // 缩放反馈表达可点。原先是白底描边胶囊，在顶栏里比标题还抢眼，所以显得突兀。
        val backIcon = UiKit.iconView(context, Icons.IC_ARROW_LEFT, UiKit.FS_ICON, UiKit.TITLE)
        backIcon.setOnClickListener {
            controller?.onClose()
        }
        linearLayout.addView(backIcon, iconLp(0))
        // 抽屉排第二：同样扁平，字号更小、色阶更淡，层级低于返回键。
        val drawerIcon = UiKit.iconView(context, Icons.IC_MENU, UiKit.FS_ICON, UiKit.SUB)
        drawerIcon.setOnClickListener {
            openDrawer()
        }
        linearLayout.addView(drawerIcon, iconLp(dp(2.0f)))
        // 标题放在返回键之后：先加两个图标保证返回键贴在最左侧。
        linearLayout.addView(textView, LinearLayout.LayoutParams(0, -2, 1.0f))
        return linearLayout
    }

    /** 上下文占用环：环形 = 已用/剩余，中心数字 = 占用百分比；点开令牌消耗统计页。 */
    private fun buildCtxRing(linearLayout: LinearLayout) {
        val ring = CtxRing(context)
        ctxRing = ring
        ring.setOnClickListener {
            controller?.onOpenTokenStat()
        }
        val ringLp = LinearLayout.LayoutParams(dp(30.0f), dp(30.0f))
        ringLp.leftMargin = dp(2.0f)
        ringLp.rightMargin = dp(2.0f)
        linearLayout.addView(ring, ringLp)
    }

    /** 顶部提示条：圆角色块、左右留边，点击跳主界面。 */
    private fun buildHintBar() {
        val textView2 = TextView(context)
        hint = textView2
        textView2.textSize = UiKit.FS_SUB
        hint.setTextColor(UiKit.HINT_FG)
        // 【圆角】提示条原为满宽直角背景，会把面板圆角盖成直角；改圆角色块并左右留边。
        hint.setBackground(UiKit.round(UiKit.HINT_BG, context, 10f))
        hint.setPadding(dp(14.0f), dp(7.0f), dp(14.0f), dp(7.0f))
        UiKit.collapse(hint)
        hint.setOnClickListener {
            val intent = Intent(context, MainActivity::class.java)
            intent.flags = 335544320
            context.startActivity(intent)
        }
        val hintLp = LinearLayout.LayoutParams(-1, -2)
        hintLp.leftMargin = dp(10.0f)
        hintLp.rightMargin = dp(10.0f)
        hintLp.topMargin = dp(6.0f)
        addView(hint, hintLp)
    }

    /** 消息滚动区：消息容器挂进 ScrollView，并接上「滚远自动收回展开态」。 */
    private fun buildScroller() {
        val scrollView = ScrollView(context)
        scroller = scrollView
        scrollView.isFillViewport = true
        scrollView.overScrollMode = View.OVER_SCROLL_NEVER
        // 【v2.8】展开「更早的历史」后，滑到看不见那批消息时自动收回（数据不丢，入口重现）。
        scrollView.setOnScrollChangeListener { _: View, _: Int, scrollY: Int, _: Int, _: Int ->
            onScrolled(scrollY)
        }
        val linearLayout2 = LinearLayout(context)
        messages = linearLayout2
        linearLayout2.orientation = LinearLayout.VERTICAL
        messages.setPadding(dp(12.0f), dp(8.0f), dp(12.0f), dp(8.0f))
        scroller.addView(messages, ViewGroup.LayoutParams(-1, -2))
        addView(scroller, LinearLayout.LayoutParams(-1, 0, 1.0f))
    }

    /** 输入行上方的工具条（模型 / 思考 / 加号）+ 附件预览条。 */
    private fun buildToolBar() {
        buildToolRow()
        buildAttachStrip()
        val toolLp = LinearLayout.LayoutParams(-1, -2)
        toolLp.leftMargin = dp(10.0f)
        toolLp.rightMargin = dp(10.0f)
        toolLp.bottomMargin = dp(2.0f)
        addView(attachStrip)
        addView(toolRow, toolLp)
    }

    /** 工具条主体：模型配置 / 思考程度 / 记忆 三图标 + 右侧「记忆总结中」提示。 */
    private fun buildToolRow() {
        // 【三件套】输入行上方的工具条：🐳 模型配置 / 💡 思考程度 / ＋ 记忆。
        // 【坑】三个图标要自己撑出 38dp 命中区（flatIcon 只画字，不量宽高），
        //       与顶栏返回键同一套尺寸，点起来才不会漏。
        val toolWrap = LinearLayout(context)
        toolRow = toolWrap
        toolWrap.orientation = LinearLayout.HORIZONTAL
        toolWrap.gravity = Gravity.CENTER_VERTICAL
        val modelIcon = UiKit.iconView(context, Icons.IC_SETTINGS, UiKit.FS_ICON, UiKit.TITLE)
        modelIcon.setOnClickListener {
            SheetPanel.showModels(context, findLayer())
        }
        toolWrap.addView(modelIcon, iconLp(0))
        val thinkIcon = UiKit.iconView(context, Icons.IC_BRAIN, UiKit.FS_ICON, UiKit.SUB)
        thinkIcon.setOnClickListener {
            SheetPanel.showThink(context, findLayer(), this)
        }
        toolWrap.addView(thinkIcon, iconLp(dp(2.0f)))
        val memIcon = UiKit.iconView(context, Icons.IC_PLUS, UiKit.FS_ICON, UiKit.SUB)
        memIcon.setOnClickListener {
            SheetPanel.showMemory(context, findLayer(), this)
        }
        toolWrap.addView(memIcon, iconLp(dp(2.0f)))
        // 【需求】工具条上的独立「图片」按钮撤掉：加号同时承担「功能面板」入口并落到最右，
        //   发图项并入加号展开的面板（SheetPanel.showMemory 顶部新增「发图」行）。
        // 【v2.8】右侧的「记忆总结中」：weight=1 吃掉三个图标之后的全部留白，文字贴右。
        // 【坑】只在总结在途时可见（GONE 不参与布局），所以平时三个图标的位置与之前完全一致。
        val busy = TextView(context)
        memoBusy = busy
        busy.text = "记忆总结中"
        busy.textSize = UiKit.FS_TINY
        busy.setTextColor(UiKit.CHAT_CHIP_MUTE)
        busy.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        // 【观感】面板极窄时不许换行，否则会把整条工具条撑高。
        busy.isSingleLine = true
        busy.ellipsize = TextUtils.TruncateAt.END
        busy.setPadding(dp(6.0f), 0, dp(2.0f), 0)
        UiKit.collapse(busy)
        toolWrap.addView(busy, LinearLayout.LayoutParams(0, -2, 1.0f))
    }

    /** 附件预览条：缩略图 + 说明 + 移除按钮，初始收起。 */
    private fun buildAttachStrip() {
        // 【修·附件断链】附件预览条：缩略图 + 说明 + 移除按钮。
        //   原先 attachStrip / attachThumb / attachLabel 三个字段只有读取方，从来没有创建代码，
        //   refreshAttachStrip() 里 `attachStrip == null` 直接 return —— 选了图也看不到、去不掉。
        val strip = LinearLayout(context)
        attachStrip = strip
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.setBackground(UiKit.round(UiKit.CARD, context, 16f))
        strip.setPadding(dp(8.0f), dp(6.0f), dp(6.0f), dp(6.0f))
        val thumb = ImageView(context)
        attachThumb = thumb
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        val thumbLp = LinearLayout.LayoutParams(dp(40.0f), dp(40.0f))
        thumbLp.rightMargin = dp(8.0f)
        strip.addView(thumb, thumbLp)
        val label = TextView(context)
        attachLabel = label
        label.textSize = UiKit.FS_TINY
        label.setTextColor(UiKit.SUB)
        label.isSingleLine = true
        label.ellipsize = TextUtils.TruncateAt.END
        strip.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))
        val attachClear = UiKit.iconView(context, Icons.IC_CLOSE, 14.0f, UiKit.SUB)
        attachClear.setOnClickListener {
            clearAttachment()
        }
        strip.addView(attachClear, LinearLayout.LayoutParams(-2, -2))
        val attachLp = LinearLayout.LayoutParams(-1, -2)
        attachLp.leftMargin = dp(10.0f)
        attachLp.rightMargin = dp(10.0f)
        attachLp.bottomMargin = dp(2.0f)
        strip.layoutParams = attachLp
        // 初始收起（仍占据布局，由 alpha/显隐控制）。
        strip.visibility = View.GONE
        strip.alpha = 0f
    }

    /** 底部输入行：输入框 + 圆形发送键（等待回复时同一按钮变「停止」）。 */
    private fun buildInputRow() {
        val linearLayout3 = LinearLayout(context)
        inputRow = linearLayout3
        linearLayout3.orientation = LinearLayout.HORIZONTAL
        linearLayout3.gravity = Gravity.CENTER_VERTICAL
        // 【圆角】输入行贴在最底部：它若用满宽直角背景，会把面板根布局的 16dp 圆角盖成直角。
        // 改走 round() 圆角，并给底边留出与父级一致的 16dp，避免出现「外圆内方」的接缝。
        linearLayout3.setBackground(UiKit.round(UiKit.CARD, context, 16f))
        linearLayout3.setPadding(dp(10.0f), dp(8.0f), dp(6.0f), dp(8.0f))
        val inputLp = LinearLayout.LayoutParams(-1, -2)
        inputLp.leftMargin = dp(10.0f)
        inputLp.rightMargin = dp(10.0f)
        inputLp.bottomMargin = dp(10.0f)
        addView(linearLayout3, inputLp)
        val editText = EditText(context)
        input = editText
        editText.hint = "跟她说点什么…"
        input.maxLines = 4
        input.inputType = 147457
        // 【圆角】原实现没给输入框设背景，用的是系统默认直角下划线；统一走 UiKit.field（8dp 圆角）。
        UiKit.field(input, context)
        // 【顺序】field() 内部会 setTextSize(14)；要保住聊天框的 15sp 必须放在它之后。
        input.setTextSize(UiKit.FS_BTN)
        linearLayout3.addView(input, LinearLayout.LayoutParams(0, -2, 1.0f))
        // 【观感】发送键改成 38dp 圆形图标：原来的长条「发送」白字紫底在输入行里像块招牌，
        // 视觉上比输入框还重。改成圆形 + 单字图标后与输入行的圆角胶囊同一套语汇。
        val button = ImageView(context)
        sendBtn = button
        UiKit.sendButton(button, context)
        button.setOnClickListener {
            // 【交互】同一个按钮两副面孔：空闲时「发送」，等待回复时「停止」。
            if (waiting) {
                stopGenerating()
            } else {
                onSend()
            }
        }
        linearLayout3.addView(button, sendLp())
    }

    /** 输入行右侧的圆形发送键：固定 38dp 方形，靠 sendButton 的 999dp 圆角成圆。 */
    private fun sendLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(dp(UiKit.HIT_DP.toFloat()), dp(UiKit.HIT_DP.toFloat()))
        lp.leftMargin = dp(8.0f)
        return lp
    }

    /** 顶栏 / 工具条图标的命中区：固定边长，左外边距由调用方给。 */
    private fun iconLp(leftMargin: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(dp(UiKit.HIT_DP.toFloat()), dp(UiKit.HIT_DP.toFloat()))
        lp.leftMargin = leftMargin
        return lp
    }

    /** 请求在途时把发送键切成「停止」，回来再切回「发送」。 */
    private fun setWaiting(z: Boolean) {
        waiting = z
        val button = sendBtn ?: return
        button.animate().cancel()
        button.animate().alpha(0f).setDuration(UiKit.D_MICRO.toLong()).setInterpolator(UiKit.EASE_STD)
                .withEndAction {
                    button.setImageResource(if (z) Icons.IC_CLOSE else Icons.IC_SEND)
                    Icons.tint(button, UiKit.ON_ACC)
                    button.animate().alpha(1f).setDuration(UiKit.D_MICRO.toLong()).setInterpolator(UiKit.EASE_STD).start()
                }.start()
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

    /** 请求在途时把发送键切成「停止」，回来再切回「发送」。 */
    fun refreshHint() {
        if (PetPrefs.hasKey(context)) {
            UiKit.collapse(hint)
        } else {
            hint.text = "还没填 API key，点这里去设置 →"
            UiKit.reveal(hint)
        }
    }

    // 能否发消息：必须有配置且当前不在等待回复。
    private fun canChat(): Boolean {
        return PetPrefs.hasKey(context)
    }

    // 弹起键盘并聚焦输入框。
    fun focusInput() {
        input.requestFocus()
    }

    // 把历史逐条铺成气泡。
    private fun renderHistory() {
        // 【v2.8·P0 防护】上一次总结留下的「滚到分割线」回调必须在重铺前摘掉：
        //        它捕获的是旧的 summaryNode，removeAllViews 后该节点已脱离 messages，
        //        预绘制阶段再做 offsetDescendantRectToMyCoords 会直接抛异常崩进程。
        cancelPendingScroll()
        messages.removeAllViews()
        // 【v2.8】重铺时旧节点引用一律作废，先清空再按当前历史重建。
        summaryNode = null
        prevTailView = null
        // 【v2.8】入口放在「空历史」判断之前：自动收回后若一条正文都不剩，也得能从界面上点回来。
        if (ChatHistoryStore.hasPrev(context)) {
            addLoadEarlierEntry()
        }
        if (history.length() == 0) {
            addBubble("我是小肥鱼～ 有什么想跟我说的吗？", false)
            UiKit.staggerCapped(messages, 12)
            return
        }
        // 【v2.8·P3】补回批不再按下标推导（见 mergePrev 打的 _prev 标记），这里只判「有没有摘要头」。
        for (i in 0 until history.length()) {
            val optJSONObject = history.optJSONObject(i) ?: continue
            // 【新增】头部摘要（kind=summary）原本被 !"system" 判据静默跳过，现在渲染成可展开的分割标题。
            if ("summary" == optJSONObject.optString("kind")) {
                summaryNode = ChatBubbles.addSummaryDivider(this, optJSONObject.optString("content"))
                continue
            }
            val optString = optJSONObject.optString("role")
            if ("system" != optString) {
                addBubble(optJSONObject.optString("content"), "user" == optString, i, optJSONObject.optString("image", null))
                // 【v2.8·P3】补回批的尾部节点按来源标记认（不再靠「头部摘要数 + prevCount」的下标推导）。
                if (ChatHistoryStore.isPrevItem(optJSONObject)) {
                    prevTailView = messages.getChildAt(messages.childCount - 1)
                }
            }
        }
        UiKit.staggerCapped(messages, 12)
    }

    /** 【v2.8】聊天记录顶部的「点击加载更早的历史记录」入口（补回即清空暂存，入口随之消失）。 */
    private fun addLoadEarlierEntry() {
        ChatBubbles.addLoadEarlier(this, View.OnClickListener {
            // 【v2.8】记下补回的条数：这是「展开态」的唯一凭据，滑过分割线时按它收回去。
            val n = ChatHistoryStore.prependPrev(this)
            if (n > 0) {
                prevCount = n
                // 【坑】不能走 reloadHistory：它会把刚设好的 prevCount 清零。
                //        prependPrev 已把合并结果写进 prefs 与 host.history，直接重铺即可。
                // 【v2.8·P1】这趟重铺压住自动滚底，并暂缓一帧收回判据：
                //   补回批挂在顶部，一旦被排队的「滚到底」推到底部，就会立刻命中判据二，
                //   展开态刚建立就被收回去（功能等于失效）。
                // 【v2.8·N3】用 try/finally 兜底：renderHistory 万一抛异常，
                //   holdScroll 必须复位，否则自动滚底与自动收回会永久失效。
                holdScroll = true
                try {
                    renderHistory()
                    UiKit.scrollToTop(scroller)
                } finally {
                    holdScroll = false
                }
                post {
                    // 【坑】队列里可能还排着更早 addBubble 时 post 的 fullScroll，
                    //       等它跑完再拉回补回批顶部，用户看到的才是「刚展开的那批」。
                    UiKit.scrollToTop(scroller)
                }
                refreshCtxRing()
            }
        })
    }

    /**
     * 【v2.8】总结在途时把工具条右侧的「记忆总结中」亮起来，结束后收起。
     * 【坑】调用方（DeepSeekClient 回调）本就在主线程，所以直接设可见性；
     *       若换成 View.post，面板 detached 时会把任务挂到重新 attach 才执行，提示可能永久残留。
     */
    internal fun setMemoBusy(busy: Boolean) {
        memoBusyOn = busy
        val t = memoBusy ?: return
        // 【v2.9.2】新的总结开始了：让上一次的失败提示让位，并把文字复位成进行中文案
        //（原来只改可见性不复位文字，会导致「总结中」显示的还是上次的失败文案）。
        if (busy) {
            flashHold = false
            val pending = pendingFlash
            if (pending != null) {
                removeCallbacks(pending)
                pendingFlash = null
            }
        }
        // 【v2.9.2·P0】总结结束时必须避开正在显示的 flash 提示：
        //  回调 finally 里必调 setMemoBusy(false)，若在此隐藏，flashMemo 的 setVisibility(0)
        //  会被同一帧改回 8，不绘制中间态 —— 用户依然「点了没反应」。
        val keepVisible = !busy && flashHold
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (busy) {
                t.text = "记忆总结中"
            }
            if (!keepVisible) {
                UiKit.showHide(t, busy)
            }
            return
        }
        post {
            if (busy) {
                t.text = "记忆总结中"
            }
            if (!keepVisible) {
                UiKit.showHide(t, busy)
            }
        }
    }

    /**
     * 【v2.8】总结完成后把视图滚到「ⓘ 历史对话摘要」那条分割线，让「已收起」一眼可见。
     * 【坑】必须等新视图量好再算位置：直接 post 会跑在布局之前，getTop() 拿到 0 → 滚到顶部。
     *       跨 messages 这层包装取坐标要用 offsetDescendantRectToMyCoords，不能只信 getTop()。
     */
    internal fun scrollToSummary() {
        val box = summaryNode ?: return
        // 【v2.8·P0】重入保护：同一时刻只留一个在途回调，新的直接顶掉旧的。
        cancelPendingScroll()
        val l = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // 【v2.8·P0 防护】必须在自摘之前再验一次祖先关系：
                // 若这期间又发生过一次重铺（reloadHistory / showChat / clearChat / 另一次收回），
                // box 的 mParent 已被置空，它不再是 messages 的后代，
                // 此时 offsetDescendantRectToMyCoords 会抛 IllegalArgumentException 直接崩进程。
                // renderHistory() 已主动摘监听，这里只是兜底的第二道闸。
                if (box.parent !== messages) {
                    cancelPendingScroll()
                    return true
                }
                cancelPendingScroll()
                try {
                    val r = Rect()
                    box.getDrawingRect(r)
                    messages.offsetDescendantRectToMyCoords(box, r)
                    scroller.smoothScrollTo(0, Math.max(0, r.top - dp(8.0f)))
                } catch (unused: Throwable) {
                    // 【兜底】节点在预绘制与绘制之间被摘掉时同样会走到这里，静默放弃定位，
                    //         不滚动总好过崩掉进程。
                }
                return true
            }
        }
        pendingScroll = l
        box.viewTreeObserver.addOnPreDrawListener(l)
    }

    /**
     * 【v2.8】摘掉在途的「滚到摘要分割线」回调。
     * 【实现】box 与 messages 同在一棵 window 视图树上，View.getViewTreeObserver() 返回的
     *       就是同一个 mAttachInfo.mTreeObserver，所以直接对 messages 的 VTO 摘除即可。
     *       两条登记路径（MemSummarizer 的 host.post、onScrolled 内）都在 attach 之后，
     *       不会出现「各自 lazy 建 floating observer」对不上的情况。
     * 【兜底】万一真摘不掉，onPreDraw 里的祖先关系检查也会挡住坐标换算，不会崩。
     */
    private fun cancelPendingScroll() {
        val l = pendingScroll ?: return
        pendingScroll = null
        try {
            messages.viewTreeObserver.removeOnPreDrawListener(l)
        } catch (unused: Throwable) {
        }
    }

    /**
     * 【v2.8】滚动回调：展开态下补回批整批滑出视口上沿（看不到上面那批消息了），就自动收回。
     * 【坑】程序化滚动也会触发本回调，所以收回过程中用 collapsing 挡住重入。
     */
    private fun onScrolled(scrollY: Int) {
        if (collapsing || holdScroll || prevCount <= 0) {
            return
        }
        val v = prevTailView ?: return
        // 【v2.8】两种收口：批次整批被推到视口上沿之上；或已经滑到列表底部且滚过了头。
        val contentH = messages.height
        val viewH = scroller.height
        val atBottom = scrollY > 0 && viewH > 0 && scrollY + viewH >= contentH - 1
        if (!shouldCollapse(v.bottom, scrollY, atBottom)) {
            return
        }
        val n = prevCount
        collapsing = true
        val ok = ChatHistoryStore.collapsePrev(this, n)
        // 【坑】不论成败都清掉展开态：失败说明 history 结构已经在展开期间被改过，
        //       留着 prevCount 会让每帧滚动回调都重试一次（白白构建 JSON），得不偿失；
        //       数据一条不动，用户仍在展开态里，只是不再自动收回。
        prevCount = 0
        prevTailView = null
        if (ok) {
            reloadHistory()
            scrollToSummary()
        }
        post {
            collapsing = false
        }
    }

    companion object {
        // 【交互】气泡构造已拆到 ChatBubbles；messages / scroller / history 因此改为包级可见。
        private const val MAX_TOOL_ROUNDS = 3

        @JvmField
        internal val AFFECTION_HEAD: Pattern = Pattern.compile("^\\s*[\\[【]\\s*好感度\\s*[:：]\\s*([+-]?\\d+)\\s*[\\]】]\\s*")

        @JvmField
        internal val AFFECTION_ANY: Pattern = Pattern.compile("[\\[【]\\s*好感度\\s*[:：]\\s*[+-]?\\d+\\s*[\\]】]")

        /**
         * 【v2.8】是否该把补回的历史收回去（纯函数，便于离线复算）。
         * 【判据一】补回批最后一个节点的底边已经滚到视口上沿之上（tailBottom - scrollY <= 0），
         *          即「上面那批消息已经看不见了」。
         * 【判据二】已经滚到列表底部并继续拉：底部态同样算「看不到上面那批」，一并收回。
         */
        internal fun shouldCollapse(tailBottom: Int, scrollY: Int, atBottom: Boolean): Boolean {
            return (tailBottom - scrollY <= 0) || atBottom
        }
    }

    fun refreshInputRow() {
        val linearLayout2 = inputRow
        if (linearLayout2 != null) {
            UiKit.showHide(linearLayout2, !browsingArchives)
        }
        // 【三件套】工具条与输入行同进同退：翻看归档时一起收起，否则点了弹不出面板。
        val toolWrap = toolRow
        if (toolWrap != null) {
            UiKit.showHide(toolWrap, !browsingArchives)
            // 【v2.8】工具条被整体隐藏时子视图状态会保留，但这里显式重放一次，
            //         避免将来改动 refreshInputRow 时把「记忆总结中」弄丢。
            val busy = memoBusy
            if (busy != null) {
                // 【v2.9.2】重放时也要认 flash：否则切归档/回聊天会把失败提示提前抹掉。
                UiKit.showHide(busy, memoBusyOn || flashHold)
            }
        }
        val linearLayout = attachStrip
        if (!browsingArchives || linearLayout == null) {
            refreshAttachStrip()
        } else {
            UiKit.collapse(linearLayout)
        }
    }

    // 套用用户选的聊天背景图与透明度。
    // 【裁剪】改走 ChatBgDrawable 做 center-crop：等比铺满、超出裁边，绝不拉伸变形。
    fun applyBackground() {
        if (!this::scroller.isInitialized) {
            return
        }
        recordChatBgRatio()
        val chatBackground = PetPrefs.chatBackground(context)
        val loadScaled = if (chatBackground.isNullOrEmpty()) null else ImageStore.loadScaled(context, chatBackground, 1080)
        if (loadScaled == null) {
            scroller.setBackground(null)
            return
        }
        val chatBgDrawable = ChatBgDrawable(loadScaled)
        chatBgDrawable.setAlpha((PetPrefs.chatBgAlpha(context) * 255) / 100)
        scroller.setBackground(chatBgDrawable)
        scroller.alpha = 0.6f
        scroller.animate().alpha(1f).setDuration(UiKit.D_MICRO.toLong()).setInterpolator(UiKit.EASE_DECEL).start()
    }

    /**
     * 【裁剪】把聊天区实际宽高比回填到偏好，供裁剪页据此出框（所见即所得）。
     * 【坑】构造期 scroller 还没量到尺寸，首帧要 post 一次；比例没变就不再写盘。
     */
    private fun recordChatBgRatio() {
        if (scroller.width > 0 && scroller.height > 0) {
            writeChatBgRatio(scroller.width, scroller.height)
            return
        }
        scroller.post {
            if (!this::scroller.isInitialized) {
                return@post
            }
            if (scroller.width > 0 && scroller.height > 0) {
                writeChatBgRatio(scroller.width, scroller.height)
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
        val linearLayout = attachStrip ?: return
        if (pendingImage == null && pendingText == null) {
            UiKit.collapse(linearLayout)
            attachThumb.setImageDrawable(null)
            return
        }
        UiKit.reveal(linearLayout)
        if (pendingImage != null) {
            UiKit.reveal(attachThumb)
            attachThumb.setImageBitmap(ImageStore.loadScaled(context, pendingImage, 160))
            // 【OCR】已发出、正在后台扫字：给她一句进度，避免用户以为卡死了。
            attachLabel.text = if (sendPending && waiting)
                "正在识别图片文字…（后台处理，稍等）"
            else
                "已选图片，会一起发给她"
            return
        }
        UiKit.collapse(attachThumb)
        attachThumb.setImageDrawable(null)
        val str = pendingText
        val max = Math.max(1, if (str != null) str.length / 1024 else 0)
        attachLabel.text = "已选文本内容（约 " + max + " KB），会拼在她看到的消息里"
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
            p = p.parent
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
        prevTailView = null
        ChatHistoryStore.loadHistory(this)
        renderHistory()
        refreshCtxRing()
    }

    /** 手动触发一次上下文总结 + 压缩（真删除）。 */
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

    /** 【v2.9.1】在工具条右侧短暂显示一条提示（总结失败 / 被拒等），3 秒后自动收起。 */
    internal fun flashMemo(msg: String?) {
        val t = memoBusy
        if (t == null || msg == null || msg.trim().isEmpty()) {
            Logs.i("DollhouseMemo", "[flash] 丢弃 t=" + (t != null)
                    + " msgEmpty=" + (msg == null || msg.trim().isEmpty()))
            return
        }
        // 【v2.9.2·P2-1】同一时间只允许一个计时器：旧的先撤，否则连续两次失败点击时，
        //  较旧的计时器会在 3 秒时把较新的提示提前抹掉。
        val pending = pendingFlash
        if (pending != null) {
            removeCallbacks(pending)
            pendingFlash = null
        }
        // 【v2.9.2·P0】置 flashHold：让紧随其后的 setMemoBusy(false) 别把它同帧隐藏掉。
        //  回调 finally 必调 setMemoBusy(false)，不设这个标志的话 setVisibility(0)
        //  会被立刻改回 8，同一帧完成、不绘制中间态 —— 用户什么都看不到。
        flashHold = true
        Logs.i("DollhouseMemo", "[flash] 显示提示 len=" + msg.length)
        t.text = msg
        UiKit.reveal(t)
        val r = Runnable {
            pendingFlash = null
            flashHold = false
            // 期间若另一次总结开始了，就别把它的「记忆总结中」收掉。
            if (!memoBusyOn) {
                t.text = "记忆总结中"
                UiKit.collapse(t)
            }
        }
        pendingFlash = r
        postDelayed(r, 3000L)
    }

    /** 复制最后一条助手回复（气泡操作条的「⧉ 复制」）。 */
    fun copyLastReply() {
        for (i in history.length() - 1 downTo 0) {
            val o = history.optJSONObject(i)
            if (o != null && "assistant" == o.optString("role")) {
                ChatBubbles.copyText(this, o.optString("content", ""))
                return
            }
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
        prevTailView = null
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
        val ring = ctxRing ?: return
        val threshold = Math.max(1, PetPrefs.memThreshold(context))
        ring.setRatio(history.length() / threshold.toFloat())
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
        var text = input.text.toString().trim()
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
        input.setText("")
        addBubble(text, true, -1, str2)
        ChatHistoryStore.push(this, "user", text, str2)
        if (ChatSessions.isPet(context)) {
            PetBus.say(if (str2 != null) "让我看看…" else "让我想想…", if (0 != 0) 6000L else 2500L)
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
                    if (optJSONArray != null && optJSONArray.length() > 0 && i < 3) {
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
        return ChatToolRegistry.execute(str, str2)
    }

    fun finishWithError(str: String?) {
        task = null
        if (isAttachedToWindow) {
            setWaiting(false)
            removeThinking()
            addBubble("（出错了：" + str + "）", false)
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
                ChatBubbles.addThinkingBox(this, think, costMs)
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
            ChatBubbles.addThinkingBox(this, think, costMs)
        }
        addBubble(trim, false, history.length() - 1)
        if (pet) {
            PetBus.say(ChatHistoryStore.bubbleVersion(trim), 5000L)
        }
    }
}
