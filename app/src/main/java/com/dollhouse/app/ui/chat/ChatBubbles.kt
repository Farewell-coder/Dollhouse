package com.dollhouse.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.data.ImageStore
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】聊天气泡的构造与呈现：气泡本体、图片附件、重新生成操作条、思考中占位。
 *
 * 【入口】只由 ChatPanel 调用（ChatPanel 保留同名薄壳，内部调用点不用改）。
 *
 * 【交互】点击重新生成时回调回 host.regenerate()；
 *        消息列表与滚动容器由 host 持有（messages / scroller），本类不缓存状态。
 *
 * 【扩展】新增一种气泡类型或操作按钮 = 在本类加一个静态工厂，不动 ChatPanel。
 *
 * 【坑】host.history 只读；本类绝不修改历史（写历史一律走 ChatPanel.push / saveHistory）。
 */
object ChatBubbles {
    @JvmStatic
    fun addBubble(host: ChatPanel, str: String?, z: Boolean) {
        ChatBubbles.addBubble(host, str, z, -1, null)
    }

    @JvmStatic
    fun addBubble(host: ChatPanel, str: String?, z: Boolean, i: Int) {
        ChatBubbles.addBubble(host, str, z, i, null)
    }

    @JvmStatic
    fun addBubble(host: ChatPanel, str: String?, z: Boolean, i: Int, str2: String?) {
        val linearLayout = LinearLayout(host.context)
        linearLayout.orientation = LinearLayout.HORIZONTAL
        linearLayout.gravity = if (z) Gravity.END else Gravity.START
        val linearLayout2 = LinearLayout(host.context)
        linearLayout2.orientation = LinearLayout.VERTICAL
        linearLayout2.setPadding(UiKit.dp(host.context, 12.0f), UiKit.dp(host.context, 8.0f), UiKit.dp(host.context, 12.0f), UiKit.dp(host.context, 8.0f))
        linearLayout2.background = UiKit.round(if (z) UiKit.CHAT_BUBBLE_USER else UiKit.CARD,
            host.context, 15f)
        val i2 = (host.resources.displayMetrics.widthPixels * 0.72).toInt()
        if (str2 != null && ImageStore.exists(host.context, str2)) {
            val imageView = ImageView(host.context)
            imageView.adjustViewBounds = true
            imageView.maxWidth = i2
            imageView.maxHeight = (host.resources.displayMetrics.heightPixels * 0.32f).toInt()
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            // 【圆角】附件图原来是不裁剪的矩形，与气泡圆角对不上；按 12dp 圆角裁剪。
            imageView.clipToOutline = true
            imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height,
                        UiKit.dp(host.context, 12.0f).toFloat())
                }
            }
            imageView.setImageBitmap(ImageStore.loadScaled(host.context, str2, 720))
            val layoutParams = LinearLayout.LayoutParams(-2, -2)
            layoutParams.bottomMargin = UiKit.dp(host.context, 6.0f)
            linearLayout2.addView(imageView, layoutParams)
        }
        if (str != null && !str.trim().isEmpty()) {
            val textView = TextView(host.context)
            textView.text = str
            textView.setTextSize(UiKit.FS_BTN)
            textView.setTextIsSelectable(true)
            textView.maxWidth = i2
            textView.setTextColor(if (z) UiKit.CARD else UiKit.TITLE)
            linearLayout2.addView(textView)
        }
        val layoutParams2 = LinearLayout.LayoutParams(-2, -2)
        layoutParams2.topMargin = UiKit.dp(host.context, 3.0f)
        layoutParams2.bottomMargin = UiKit.dp(host.context, 3.0f)
        linearLayout.addView(linearLayout2, layoutParams2)
        host.messages.addView(linearLayout, LinearLayout.LayoutParams(-1, -2))
        if (!z && i >= 0 && i == host.history.length() - 1) {
            host.messages.addView(ChatBubbles.buildActions(host), LinearLayout.LayoutParams(-1, -2))
        }
        // 【v2.8】补回「更早的历史」的那次重铺压住自动滚底：那批挂在顶部，一滚到底会立刻满足
        //        「已经到列表底部」的收回判据，展开态刚建立就被收回去（功能等于失效）。
        if (!host.holdScroll) {
            ChatBubbles.scrollToBottom(host)
        }
    }

    @JvmStatic
    fun buildActions(host: ChatPanel): LinearLayout {
        val linearLayout = LinearLayout(host.context)
        linearLayout.orientation = LinearLayout.HORIZONTAL
        linearLayout.gravity = Gravity.START
        linearLayout.setPadding(UiKit.dp(host.context, 2.0f), 0, 0, UiKit.dp(host.context, 6.0f))
        linearLayout.addView(actionChip(host, "重新生成", Icons.IC_REFRESH, false, View.OnClickListener {            host.regenerate() }))
        // 【交互】复制看上一条助手回复：取 history 里最后一条 assistant 正文。
        linearLayout.addView(actionChip(host, "复制", Icons.IC_COPY, false, View.OnClickListener {            host.copyLastReply() }))
        return linearLayout
    }

    /** 把文本塞进系统剪贴板（失败只提示，不抛）。 */
    @JvmStatic
    fun copyText(host: ChatPanel, text: String?) {
        if (text == null || text.isEmpty()) {
            return
        }
        try {
            val cm = host.context.getSystemService("clipboard") as? ClipboardManager
            if (cm == null) {
                return
            }
            cm.setPrimaryClip(ClipData.newPlainText("Dollhouse", text))
        } catch (unused: Throwable) {
        }
    }

    @JvmStatic
    fun actionChip(host: ChatPanel, str: String?, z: Boolean, onClickListener: View.OnClickListener): TextView {
        return actionChip(host, str, 0, z, onClickListener)
    }

    /**
     * 带图标的操作药丸。
     *
     * 【为什么用复合 drawable 而不是并排两个 View】药丸已由 UiKit.round 描出圆角背景，
     *   再套一层 LinearLayout 会让按压缩放（UiKit.press）只作用到容器、文字背景不跟着缩，
     *   看着像「两层壳在错位」。复合 drawable 直接长在文字上，缩放是整体一体的。
     *
     * @param iconRes Icons.IC_* 常量；传 0 = 纯文字（兼容旧调用）。
     */
    @JvmStatic
    fun actionChip(host: ChatPanel, str: String?, iconRes: Int, z: Boolean, onClickListener: View.OnClickListener): TextView {
        val ctx = host.context
        val textView = TextView(ctx)
        textView.text = str
        textView.setTextSize(UiKit.FS_CHIP)
        textView.setPadding(UiKit.dp(ctx, 10.0f), UiKit.dp(ctx, 5.0f), UiKit.dp(ctx, 10.0f), UiKit.dp(ctx, 5.0f))
        textView.background = UiKit.round(if (z) UiKit.CHAT_CHIP_ON else UiKit.CHAT_CHIP_OFF,
            ctx, UiKit.RADIUS_CHIP.toFloat())
        textView.setTextColor(if (z) UiKit.CHAT_CHIP_FG else UiKit.CHAT_CHIP_MUTE)
        if (iconRes != 0) {
            // 图标与文字同色：药丸的语义色一变，图标跟着走，不会留一枚「没换色」的旧图标。
            Icons.stateIcon(textView, iconRes, textView.currentTextColor, 13.0f, 4)
        }
        val layoutParams = LinearLayout.LayoutParams(-2, -2)
        layoutParams.rightMargin = UiKit.dp(ctx, 6.0f)
        textView.layoutParams = layoutParams
        textView.setOnClickListener(onClickListener)
        UiKit.press(textView)
        return textView
    }

    // 滚到消息区底部。
    @JvmStatic
    fun scrollToBottom(host: ChatPanel) {
        host.scroller.post {            // 【丝滑】原来 fullScroll 瞬跳到底，与 ChatPanel 的 smoothScrollTo 手感不一致。
            host.scroller.smoothScrollTo(0, host.messages.height)
        }
    }

    /**
     * 【思考框】可折叠的「思考了 X 秒」条：折叠态一行摘要，点开显示完整推理过程。
     * 【位置】铺在对应回复气泡的正上方（调用方先加本节点、再加气泡）。
     * 【数据】思考内容只在内存里过一手，不落盘（重开 / 切会话即消失）。
     * 【时长】由调用方传入的整段往返耗时近似（非流式请求无法只量思考段）。
     * 【视觉】底色沿用「思考中…」占位气泡的 CHAT_ACTION_BG，收起与展开之间视觉连续。
     */
    @JvmStatic
    fun addThinkingBox(host: ChatPanel, reasoning: String?, costMs: Long): View {
        val ctx = host.context
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.background = UiKit.round(UiKit.CHAT_ACTION_BG, ctx, 15f)
        val padH = UiKit.dp(ctx, 12.0f)
        val padV = UiKit.dp(ctx, 8.0f)
        box.setPadding(padH, padV, padH, padV)
        val maxW = (host.resources.displayMetrics.widthPixels * 0.72).toInt()

        // 标题行：脑图标 + 「思考了 X 秒」+ 展开箭头。
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Icons.view(ctx, Icons.IC_BRAIN, 13.0f, UiKit.CHAT_CHIP_MUTE),
            LinearLayout.LayoutParams(-2, -2))
        val label = TextView(ctx)
        label.text = thinkCostText(costMs)
        label.setTextSize(UiKit.FS_CHIP)
        label.setTextColor(UiKit.CHAT_CHIP_MUTE)
        val llp = LinearLayout.LayoutParams(-2, -2)
        llp.leftMargin = UiKit.dp(ctx, 6.0f)
        head.addView(label, llp)
        // 中间用 Space 吃掉剩余宽度：标题贴左、箭头贴右。
        head.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1.0f))
        val arrow = Icons.view(ctx, Icons.IC_CHEVRON_DOWN, 13.0f, UiKit.CHAT_CHIP_MUTE)
        head.addView(arrow, LinearLayout.LayoutParams(-2, -2))
        box.addView(head, LinearLayout.LayoutParams(-1, -2))

        // 正文：默认收起。
        val body = TextView(ctx)
        body.text = reasoning ?: ""
        body.setTextSize(UiKit.FS_SUB)
        body.setTextColor(UiKit.SUB)
        body.setTextIsSelectable(true)
        body.maxWidth = maxW
        body.setLineSpacing(UiKit.dp(ctx, 3.0f).toFloat(), 1.0f)
        val tlp = LinearLayout.LayoutParams(-1, -2)
        tlp.topMargin = UiKit.dp(ctx, 6.0f)
        body.layoutParams = tlp
        body.visibility = View.GONE
        box.addView(body)

        // 【交互】整行可点：展开 / 收起推理正文，箭头同步换向。
        head.isClickable = true
        UiKit.press(head)
        head.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            if (show) {
                UiKit.reveal(body)
            } else {
                UiKit.collapse(body)
            }
            arrow.setImageResource(if (show) Icons.IC_CHEVRON_UP else Icons.IC_CHEVRON_DOWN)
            Icons.tint(arrow, UiKit.CHAT_CHIP_MUTE)
        }

        val blp = LinearLayout.LayoutParams(-2, -2)
        blp.bottomMargin = UiKit.dp(ctx, 4.0f)
        host.messages.addView(box, blp)
        // 【v2.8】与 addBubble 同一道门：展开重铺期间不许把视图推到底。
        if (!host.holdScroll) {
            ChatBubbles.scrollToBottom(host)
        }
        return box
    }

    /** 「思考了 X 秒」文案：不足 0.1 秒按 0.1 秒显示，避免出现「思考了 0.0 秒」。 */
    private fun thinkCostText(costMs: Long): String {
        var sec = costMs / 1000.0
        if (sec < 0.1) {
            sec = 0.1
        }
        return String.format(java.util.Locale.CHINA, "思考了 %.1f 秒", sec)
    }

    // 插入「思考中…」占位气泡。
    @JvmStatic
    fun addThinking(host: ChatPanel, str: String?) {
        val linearLayout = LinearLayout(host.context)
        linearLayout.gravity = Gravity.START
        val textView = TextView(host.context)
        textView.text = str
        textView.setTextSize(UiKit.FS_BTN)
        textView.setTextColor(UiKit.CHAT_CHIP_MUTE)
        textView.setPadding(UiKit.dp(host.context, 12.0f), UiKit.dp(host.context, 8.0f), UiKit.dp(host.context, 12.0f), UiKit.dp(host.context, 8.0f))
        textView.background = UiKit.round(UiKit.CHAT_ACTION_BG, host.context, 15f)
        linearLayout.addView(textView)
        host.messages.addView(linearLayout)
        host.thinkingView = linearLayout
        host.thinkingLabel = textView
        // 【v2.8·N4】与 addBubble 同一道门：展开重铺期间不许把视图推到底，
        //        否则补回批一挂上去就满足「已到列表底部」的收回判据。
        if (!host.holdScroll) {
            ChatBubbles.scrollToBottom(host)
        }
    }

    // 更新占位气泡的文字。
    @JvmStatic
    fun setThinkingLabel(host: ChatPanel, str: String?) {
        val textView = host.thinkingLabel
        if (textView != null) {
            textView.text = str
            if (!host.holdScroll) {
                ChatBubbles.scrollToBottom(host)
            }
        }
    }

    // 移除占位气泡。
    @JvmStatic
    fun removeThinking(host: ChatPanel) {
        val view = host.thinkingView
        if (view != null) {
            host.messages.removeView(view)
            host.thinkingView = null
            host.thinkingLabel = null
        }
    }

    /**
     * 「ⓘ 历史对话摘要」分割标题：两侧细线 + 居中标签，点一下展开/收起摘要全文。
     * 【位置】铺在聊天记录里早期对话被压缩的位置（历史头部 kind=summary 那条的位置）。
     */
    @JvmStatic
    fun addSummaryDivider(host: ChatPanel, text: String?): View {
        val ctx = host.context
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val blp = LinearLayout.LayoutParams(-1, -2)
        blp.topMargin = UiKit.dp(ctx, 10.0f)
        blp.bottomMargin = UiKit.dp(ctx, 2.0f)
        box.layoutParams = blp

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(sumLine(ctx), LinearLayout.LayoutParams(0, Math.max(1, UiKit.dp(ctx, 1.0f)), 1.0f))
        val label = TextView(ctx)
        label.text = "历史对话摘要"
        label.setTextSize(UiKit.FS_TINY)
        label.setTextColor(UiKit.SUB)
        label.gravity = Gravity.CENTER
        label.setPadding(UiKit.dp(ctx, 10.0f), UiKit.dp(ctx, 3.0f), UiKit.dp(ctx, 10.0f), UiKit.dp(ctx, 3.0f))
        head.addView(Icons.view(ctx, Icons.IC_CHAT, 13.0f, UiKit.SUB))
        head.addView(label, LinearLayout.LayoutParams(-2, -2))
        head.addView(sumLine(ctx), LinearLayout.LayoutParams(0, Math.max(1, UiKit.dp(ctx, 1.0f)), 1.0f))
        box.addView(head, LinearLayout.LayoutParams(-1, -2))

        val body = TextView(ctx)
        body.text = text ?: ""
        body.setTextSize(UiKit.FS_SUB)
        body.setTextColor(UiKit.SUB)
        body.setTextIsSelectable(true)
        body.setLineSpacing(UiKit.dp(ctx, 3.0f).toFloat(), 1.0f)
        val tlp = LinearLayout.LayoutParams(-1, -2)
        tlp.topMargin = UiKit.dp(ctx, 4.0f)
        tlp.leftMargin = UiKit.dp(ctx, 6.0f)
        tlp.rightMargin = UiKit.dp(ctx, 6.0f)
        body.layoutParams = tlp
        body.visibility = View.GONE
        box.addView(body)

        // 【交互】默认只露分割标题一行，点它才展开摘要全文。
        if (!body.text.toString().trim().isEmpty()) {
            head.isClickable = true
            UiKit.press(head)
            head.setOnClickListener {
                val show = body.visibility != View.VISIBLE
                // 【丝滑】摘要展开/收起改为淡入淡出，不再一下冒出来/一下消失。
                if (show) {
                    UiKit.reveal(body)
                } else {
                    UiKit.collapse(body)
                }
                label.text = if (show) "历史对话摘要（点击收起）" else "历史对话摘要"
            }
        }
        host.messages.addView(box, LinearLayout.LayoutParams(-1, -2))
        // 【交互】返回节点本身：总结完成时宿主据此把视图滚到这条分割线（见 ChatPanel.scrollToSummary）。
        return box
    }

    /** 分割标题两侧的细线。 */
    private fun sumLine(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(UiKit.LINE)
        return v
    }

    /**
     * 聊天记录最顶部的「点击加载更早的历史记录」。
     * 【语义】暂存批只能补一次，补回后 ChatHistoryStore 会清空暂存位，本入口随之消失。
     */
    @JvmStatic
    fun addLoadEarlier(host: ChatPanel, onClickListener: View.OnClickListener) {
        val row = LinearLayout(host.context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val rlp = LinearLayout.LayoutParams(-1, -2)
        rlp.bottomMargin = UiKit.dp(host.context, 4.0f)
        row.layoutParams = rlp
        val t = TextView(host.context)
        t.text = "点击加载更早的历史记录"
        t.setTextSize(UiKit.FS_CHIP)
        t.setTextColor(UiKit.CHAT_CHIP_FG)
        t.setPadding(UiKit.dp(host.context, 12.0f), UiKit.dp(host.context, 6.0f),
            UiKit.dp(host.context, 12.0f), UiKit.dp(host.context, 6.0f))
        t.background = UiKit.round(UiKit.CHAT_CHIP_BG, host.context, UiKit.RADIUS_CHIP.toFloat())
        t.isClickable = true
        UiKit.press(t)
        t.setOnClickListener(onClickListener)
        row.addView(t)
        host.messages.addView(row)
    }
}
