package com.dollhouse.app.ui.chat

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.data.ImageStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import kotlin.math.roundToInt

/**
 * 【职责】聊天面板的 Compose 渲染层：顶栏 / 提示条 / 消息列表 / 附件条 / 工具条 / 输入行。
 *
 * 【为什么整页重写】原实现是 `ChatPanel`（View 树）+ `ChatBubbles`（View 工厂）两层手写控件；
 *   气泡的每一次增删都要 `messages.addView` / `removeAllViews` 并手工维护滚动位置。
 *   Compose 侧改为「[ChatState] 是唯一真源、界面按状态重组」，`renderHistory` 只重建快照。
 *
 * 【契约】对外只暴露 [build]；逻辑侧（网络 / 工具 / OCR / 历史）一行未动，仍在 [ChatPanel]。
 */
object ChatUi {

    /** 搭出聊天面板根视图。 */
    @JvmStatic
    fun build(host: ChatPanel): View {
        ComposeHost.installForActivity(host.activity)
        return ComposeHost.createView(host.context) { ChatContent(host) }
    }

    @Composable
    private fun ChatContent(host: ChatPanel) {
        val c = DhTokens.colors
        val shape = RoundedCornerShape(16.dp)
        // 【圆角】面板根由宿主 ChatPanel 做圆角裁切（clipToOutline），这里只补描边。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .border(1.dp, c.chatBorder, shape)
        ) {
            TopBarRow(host)
            HintBar(host)
            Messages(host, Modifier.weight(1f))
            AttachStrip(host)
            ToolRow(host)
            InputRow(host)
        }
    }

    /* ============================ 顶栏 ============================ */

    /**
     * 顶栏：整条可拖动 + 返回 / 抽屉 / 标题 / 上下文环。
     * 【顺序】返回键必须排在标题之前：标题 weight=1 会吃掉剩余宽度，先加标题会把返回键挤到最右。
     */
    @Composable
    private fun TopBarRow(host: ChatPanel) {
        val c = DhTokens.colors
        val drag = Modifier.pointerInput(host) {
            var lastX = 0f
            var lastY = 0f
            detectDragGestures(
                onDragStart = { off ->
                    lastX = off.x
                    lastY = off.y
                },
                onDrag = { change, _ ->
                    val dx = change.position.x - lastX
                    val dy = change.position.y - lastY
                    host.controller?.onDrag(dx, dy)
                    lastX = change.position.x
                    lastY = change.position.y
                    change.consume()
                }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)
                .then(drag),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.IconButton(iconRes = Icons.IC_ARROW_LEFT, sizeDp = 20f, color = c.title) {
                host.controller?.onClose()
            }
            Spacer(Modifier.width(2.dp))
            DhKit.IconButton(iconRes = Icons.IC_MENU, sizeDp = 20f, color = c.sub) {
                host.openDrawer()
            }
            Text(
                text = "Dollhouse",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                color = c.title,
                fontSize = 16.sp,
                fontFamily = DhTokens.fonts
            )
            CtxRingView(host)
        }
    }

    /**
     * 上下文占用环：环形 = 已用 / 阈值，中心 = 整数百分比；点开令牌消耗统计页。
     * 【为什么用 Compose 重绘】原 [com.dollhouse.app.ui.theme.CtxRing] 是自绘 View，
     *   在 Compose 树里要靠 AndroidView 桥接；环本身只是两条弧 + 一行字，直接画更省一层。
     */
    @Composable
    private fun CtxRingView(host: ChatPanel) {
        val c = DhTokens.colors
        val ratio = ChatState.ctxRatio.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .size(30.dp)
                .pressable { host.controller?.onOpenTokenStat() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.4.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = c.chatBorder,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (ratio > 0f) {
                    drawArc(
                        color = c.chatBubbleUser,
                        startAngle = -90f,
                        sweepAngle = 360f * ratio,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Text(
                text = "${(ratio * 100f).roundToInt()}",
                color = c.title,
                fontSize = 10.sp,
                fontFamily = DhTokens.fontsBold
            )
        }
    }

    /** 顶部提示条：圆角色块、左右留边，点击跳主界面。 */
    @Composable
    private fun HintBar(host: ChatPanel) {
        val c = DhTokens.colors
        val text = ChatState.hint ?: return
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(top = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.hintBg)
                    .pressable { host.openMain() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = text,
                    color = c.hintFg,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /* ============================ 消息列表 ============================ */

    /**
     * 消息列表。
     *
     * 【滚动口径】原实现靠 `ScrollView.smoothScrollTo` + `OnScrollChangeListener`；
     *   Compose 侧改为「状态里排一次滚动请求 → 列表消费一次」与「首个可见项变化 → 回调收回判据」。
     */
    @Composable
    private fun Messages(host: ChatPanel, modifier: Modifier) {
        val listState = rememberLazyListState()
        val items = ChatState.items
        val tick = ChatState.scrollTick
        // 【下标补偿】「加载更早」入口与「思考中」占位都不是 [ChatState.items] 的成员，
        //   却各自占着 LazyColumn 的一个位置：列表下标 = items 下标 + offset，
        //   滚动目标与滚动回调必须按同一口径平移，否则收回判据会整体错位一格。
        val offset = if (ChatState.hasPrev) 1 else 0
        val tailExtra = if (ChatState.thinking != null) 1 else 0

        // 滚动请求：每自增一次消费一次。
        LaunchedEffect(tick) {
            if (tick == 0 || items.isEmpty()) {
                return@LaunchedEffect
            }
            val target = ChatState.scrollTarget
            if (target < 0) {
                listState.animateScrollToItem(items.size - 1 + offset + tailExtra)
            } else if (target < items.size) {
                listState.animateScrollToItem(target + offset)
            }
        }

        // 【v2.8】展开态自动收回：补回批整批滑出视口上沿（首个可见项已越过尾部）即收回。
        //   atBottom 取「已不能继续向后滑」，等价于旧 View 版 OnScrollChangeListener 的到底判据。
        LaunchedEffect(listState, offset) {
            snapshotFlow { listState.firstVisibleItemIndex to !listState.canScrollForward }
                .collect { (idx, atBottom) -> host.onScrolled(idx - offset, atBottom) }
        }

        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (ChatState.hasPrev) {
                item(key = "loadEarlier") { LoadEarlierRow(host) }
            }
            itemsIndexed(items, key = { i, _ -> "m$i" }) { idx, item ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 【位置】思考框铺在「它对应的那条回复」正上方：先渲染框、再渲染气泡。
                    if (idx == items.lastIndex) {
                        ChatState.thinkingBox?.let { ThinkingBoxView(it) }
                    }
                    if (item.isSummary) {
                        SummaryDivider(item.text)
                    } else {
                        Bubble(host, item)
                    }
                    // 操作条只挂在最后一条助手回复下面（与旧口径一致）。
                    if (item.role == "assistant" && idx == items.lastIndex) {
                        ActionRow(host)
                    }
                }
            }
            ChatState.thinking?.let { label ->
                item(key = "thinking") { ThinkingPlaceholder(label) }
            }
        }
    }

    /** 一条气泡：用户靠右（用户色底 + 反白字），助手靠左（卡片底 + 正文色）。 */
    @Composable
    private fun Bubble(host: ChatPanel, item: ChatItem) {
        val c = DhTokens.colors
        val mine = item.role == "user"
        val ctx = LocalContext.current
        val screenW = LocalConfiguration.current.screenWidthDp
        val maxW = (screenW * 0.72f).dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxW)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (mine) c.chatBubbleUser else c.card)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                val img = item.image
                if (!img.isNullOrEmpty() && ImageStore.exists(ctx, img)) {
                    BubbleImage(ctx, img, maxW)
                    Spacer(Modifier.height(6.dp))
                }
                if (item.text.trim().isNotEmpty()) {
                    Text(
                        text = item.text,
                        color = if (mine) c.card else c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
        }
    }

    /** 气泡里的图片附件：异步解码（不卡首帧），12dp 圆角裁切。 */
    @Composable
    private fun BubbleImage(ctx: android.content.Context, path: String, maxW: androidx.compose.ui.unit.Dp) {
        val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
            value = try {
                ImageStore.loadScaled(ctx, path, 720, true)
            } catch (unused: Throwable) {
                null
            }
        }
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .widthIn(max = maxW)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit
            )
        }
    }

    /** 「ⓘ 历史对话摘要」分割标题：两侧细线 + 居中标签，点一下展开/收起摘要全文。 */
    @Composable
    private fun SummaryDivider(text: String) {
        val c = DhTokens.colors
        var open by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (text.trim().isNotEmpty()) Modifier.pressable { open = !open } else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(c.line)
                )
                DhKit.Icon(iconRes = Icons.IC_CHAT, sizeDp = 13f, color = c.sub)
                Text(
                    text = if (open) "历史对话摘要（点击收起）" else "历史对话摘要",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(c.line)
                )
            }
            if (open) {
                Text(
                    text = text,
                    modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /** 聊天记录顶部的「点击加载更早的历史记录」入口。 */
    @Composable
    private fun LoadEarlierRow(host: ChatPanel) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(UiKit.RADIUS_CHIP.dp))
                    .background(c.chatChipBg)
                    .pressable { host.loadEarlier() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "点击加载更早的历史记录",
                    color = c.chatChipFg,
                    fontSize = UiKit.FS_CHIP.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /** 「思考中…」占位气泡（人偶在思考时她头顶的那条）。 */
    @Composable
    private fun ThinkingPlaceholder(label: String) {
        val c = DhTokens.colors
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(c.chatActionBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    color = c.chatChipMute,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /**
     * 【思考框】可折叠的「思考了 X 秒」条：折叠态一行摘要，点开显示完整推理过程。
     * 【数据】思考内容只在内存里过一手，不落盘（重开 / 切会话即消失）。
     */
    @Composable
    private fun ThinkingBoxView(box: ThinkingBox) {
        val c = DhTokens.colors
        var open by remember(box) { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(c.chatActionBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable { open = !open },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DhKit.Icon(iconRes = Icons.IC_BRAIN, sizeDp = 13f, color = c.chatChipMute)
                    Text(
                        text = thinkCostText(box.costMs),
                        modifier = Modifier.padding(start = 6.dp),
                        color = c.chatChipMute,
                        fontSize = UiKit.FS_CHIP.sp,
                        fontFamily = DhTokens.fonts
                    )
                    Spacer(Modifier.weight(1f))
                    DhKit.Icon(
                        iconRes = if (open) Icons.IC_CHEVRON_UP else Icons.IC_CHEVRON_DOWN,
                        sizeDp = 13f,
                        color = c.chatChipMute
                    )
                }
                if (open) {
                    Text(
                        text = box.reasoning,
                        modifier = Modifier.padding(top = 6.dp),
                        color = c.sub,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
        }
    }

    /** 「思考了 X 秒」文案：不足 0.1 秒按 0.1 秒显示，避免出现「思考了 0.0 秒」。 */
    private fun thinkCostText(costMs: Long): String {
        var sec = costMs / 1000.0
        if (sec < 0.1) {
            sec = 0.1
        }
        return String.format(java.util.Locale.CHINA, "思考了 %.1f 秒", sec)
    }

    /** 气泡操作条：重新生成 / 复制（药丸样式 ← `ChatBubbles.actionChip`）。 */
    @Composable
    private fun ActionRow(host: ChatPanel) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionChip(text = "重新生成", iconRes = Icons.IC_REFRESH) { host.regenerate() }
            ActionChip(text = "复制", iconRes = Icons.IC_COPY) { host.copyLastReply() }
        }
    }

    /** 带图标的操作药丸（图标与文字同色）。 */
    @Composable
    private fun ActionChip(text: String, iconRes: Int, onClick: () -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(RoundedCornerShape(UiKit.RADIUS_CHIP.dp))
                .background(c.chatChipOff)
                .pressable(onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(iconRes = iconRes, sizeDp = 13f, color = c.chatChipMute)
            Spacer(Modifier.width(4.dp))
            Text(
                text = text,
                color = c.chatChipMute,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /* ============================ 附件条 ============================ */

    /** 附件预览条：缩略图 + 说明 + 移除按钮；无附件时整条不占位。 */
    @Composable
    private fun AttachStrip(host: ChatPanel) {
        val c = DhTokens.colors
        val img = ChatState.attachImage
        val txt = ChatState.attachText
        if (img == null && txt == null) {
            return
        }
        val ctx = LocalContext.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 2.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.card)
                .padding(start = 8.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (img != null) {
                val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, img) {
                    value = try {
                        ImageStore.loadScaled(ctx, img, 160)
                    } catch (unused: Throwable) {
                        null
                    }
                }
                val b = bmp
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(
                text = ChatState.attachLabel ?: "",
                modifier = Modifier.weight(1f),
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts,
                maxLines = 1
            )
            DhKit.IconButton(iconRes = Icons.IC_CLOSE, sizeDp = 14f, color = c.sub) {
                host.clearAttachment()
            }
        }
    }

    /* ============================ 工具条 ============================ */

    /** 工具条：模型配置 / 思考程度 / 记忆 三图标 + 右侧「记忆总结中」提示。 */
    @Composable
    private fun ToolRow(host: ChatPanel) {
        val c = DhTokens.colors
        if (ChatState.browsingArchives) {
            return
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.IconButton(iconRes = Icons.IC_SETTINGS, sizeDp = 20f, color = c.title) {
                host.showModels()
            }
            Spacer(Modifier.width(2.dp))
            DhKit.IconButton(iconRes = Icons.IC_BRAIN, sizeDp = 20f, color = c.sub) {
                host.showThink()
            }
            Spacer(Modifier.width(2.dp))
            DhKit.IconButton(iconRes = Icons.IC_PLUS, sizeDp = 20f, color = c.sub) {
                host.showMemory()
            }
            Spacer(Modifier.weight(1f))
            ChatState.memoText?.let { msg ->
                Text(
                    text = msg,
                    modifier = Modifier.padding(start = 6.dp, end = 2.dp),
                    color = c.chatChipMute,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts,
                    maxLines = 1
                )
            }
        }
    }

    /* ============================ 输入行 ============================ */

    /** 输入行：输入框 + 圆形发送键（等待回复时同一按钮变「停止」）。 */
    @Composable
    private fun InputRow(host: ChatPanel) {
        val c = DhTokens.colors
        if (ChatState.browsingArchives) {
            return
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.card)
                .padding(start = 10.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            BasicTextField(
                value = ChatState.input,
                onValueChange = { host.onInputChange(it) },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                ),
                maxLines = 4,
                cursorBrush = SolidColor(c.acc),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.field)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (ChatState.input.isEmpty()) {
                            Text(
                                text = "跟她说点什么…",
                                color = c.sub,
                                fontSize = UiKit.FS_BTN.sp,
                                fontFamily = DhTokens.fonts
                            )
                        }
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            DhKit.IconButton(
                iconRes = if (ChatState.waiting) Icons.IC_CLOSE else Icons.IC_SEND,
                sizeDp = 20f,
                color = c.onAcc,
                modifier = Modifier
                    .size(UiKit.HIT_DP.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.acc)
            ) {
                // 【交互】同一个按钮两副面孔：空闲时「发送」，等待回复时「停止」。
                if (ChatState.waiting) {
                    host.stopGenerating()
                } else {
                    host.onSend()
                }
            }
        }
    }
}
