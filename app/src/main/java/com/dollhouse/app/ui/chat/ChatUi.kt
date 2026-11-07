package com.dollhouse.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.MyProfile
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.data.ImageStore
import kotlinx.coroutines.withContext
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState

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

    // 【阶段 2/3·已删除 [build]】原 `fun build(host): View` 是「给我一个 View」的入口，
    //   由 ChatPanel 的 init 里 `addView(ChatUi.build(this), ...)` 调用。
    //   去 View 化后 ChatPanel 自己持有 [ChatPanel.root]（ComposeView）并直接调 [ChatScreen]，
    //   该入口**降到 0 引用**（实测 `grep -rn "ChatUi.build" app/src/main/java` 无代码调用）。
    //   保留一个返回 View 的入口会让 ChatUi 继续 import `android.view.View`，
    //   与「界面层不认识 View」的目标相悖，故一并删除。

    /**
     * 【阶段 2/3·去 View 化】聊天面板的**组合入口**。宿主直接在自己建立的 ComposeView 里调用它。
     *
     * 【与 [build] 的分工】[build] 是「给我一个 View」的旧入口（仍保留，供仍需 View 的地方用）；
     *   本函数是「我已在组合里，直接画」的新入口 —— 去 View 化之后 ChatPanel 不再是 View，
     *   它通过 `root` 自己建 ComposeView 并调用本函数（见 ChatPanel.root）。
     *
     * 【16dp 圆角】改造前由宿主的 `clipToOutline` + `outlineProvider` 裁切。
     *   现在改为这里的 `Modifier.clip(RoundedCornerShape(16.dp))` —— 圆角值与原实现
     *   完全一致（`UiKit.dp(context, 16f)`），只是改用 Compose 画，不再需要 View 的 outline。
     *
     * 【attached 状态】用 `DisposableEffect` 在进入 / 离开组合时同步给宿主。
     *   这是 `isAttachedToWindow` 的替代：`onDispose` 在页面销毁与进程重建时都必定执行，
     *   比原 View 状态更可靠（原实现要等 `removeView` 之后才翻 false，中间有一帧窗口期）。
     */
    @Composable
    fun ChatScreen(
        host: ChatHostUi,
        /**
         * 组合进入 / 离开窗口时的通知（用于替代 `isAttachedToWindow`）。
         *
         * 【为什么不放进 [ChatHostUi]】attach 是**生命周期信号**，不是交互动作；
         *   塞进接口会逼着任何 UI 宿主都实现一个它可能不需要的回调。
         *   作为可选参数，只有真正需要「离屏就不更新气泡」的宿主（ChatPanel）才传。
         */
        onAttachChanged: ((Boolean) -> Unit)? = null
    ) {
        // 【attached 同步】只在组合进入 / 离开时各改一次，不参与重组。
        DisposableEffect(host, onAttachChanged) {
            onAttachChanged?.invoke(true)
            onDispose { onAttachChanged?.invoke(false) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 【圆角】与原 clipToOutline 同为 16dp；裁的是整幅界面（含背景图）。
                .clip(RoundedCornerShape(CHAT_CORNER_DP.dp))
        ) {
            ChatContent(host)
        }
    }

    /**
     * 聊天面板圆角（dp）。
     *
     * 【为什么是常量而不是读 UiKit】原实现是 `UiKit.dp(context, 16f)` —— 一个**固定值 16**，
     *   不是主题令牌，也不会随主题变化。这里保持同一个字面值，避免「迁移顺手改观感」。
     */
    private const val CHAT_CORNER_DP = 16

    @Composable
    private fun ChatContent(host: ChatHostUi) {
        val c = DhTokens.colors
        // 【r21·去掉整屏 1dp 描边】改造前这里是：
        //     Column(... .clip(RoundedCornerShape(16.dp)).border(1.dp, c.chatBorder, shape))
        //   宿主 `ChatPanel` 用 `clipToOutline` 把整块裁成 16dp 圆角，这圈描边又用同样的
        //   圆角画在最外沿 —— 两者严丝合缝，于是屏幕上表现为「整块界面四周有一道细线」。
        //   深色主题下 `CHAT_BORDER`(0xFF3A3F52) 比正文底色亮得多，肉眼就是一道淡淡的白边
        //   （真机截图实测：四边各 3px、RGB≈(57,60,67)，而内容区是 (19,20,22)）。
        //   【为什么直接去掉而不是换暗色】这圈描边原本是给「浮在桌面上的卡片」用的，
        //   而这里是**全屏**聊天页 —— 没有卡片浮起的语境，任何颜色的一条边都只是多余。
        //   卡片类控件的描边（如 `ChatListScreen` 的条目）不受影响，那一处语义正确。
        Column(modifier = Modifier.fillMaxSize()) {
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
     * 顶栏：标题区可拖动 + 返回 / 抽屉 / 标题 / 上下文环。
     * 【顺序】返回键必须排在标题之前：标题 weight=1 会吃掉剩余宽度，先加标题会把返回键挤到最右。
     *
     * **【v0.1.5·K-01】拖拽只绑标题区，不再绑整行。**
     * 【改造前的毛病】`drag` 挂在整条 Row 上 —— 拖动窗口与「点返回」共用同一个手势区域，
     *   手指在返回键附近有 1~2dp 抖动时，`detectDragGestures` 先吃掉事件、
     *   抬手时 click 被判定为「拖拽后抬手」而不触发 ⇒ 用户报的「返回键有时候点不动」。
     * 【修法】把 `drag` 从 Row 移到**标题那一列**（身份区）上：
     *   · 返回按钮、右侧操作按钮各自独占自己的手势区域，绝不被拖拽抢；
     *   · 标题区（头像 + 名字那一块）仍然整块可拖，这是用户拖窗口最自然的落点。
     * 【为什么不干脆去掉拖拽】拖动聊天窗口是本工程既有能力（无边框浮窗场景），
     *   需求书 19.9 只要求「不得覆盖返回按钮」，不是要取消拖拽。
     */
    @Composable
    private fun TopBarRow(host: ChatHostUi) {
        val c = DhTokens.colors
        val topCtx = androidx.compose.ui.platform.LocalContext.current
        // 【v0.1.5·K-01】拖拽手势——只交给下面的「标题区」，不交给整条 Row。
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
                .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.IconButton(iconRes = Icons.IC_ARROW_LEFT, sizeDp = 20f, color = c.title) {
                host.controller?.onClose()
            }
            Spacer(Modifier.width(2.dp))
            // 【r13·入口收拢】原「☰ 菜单」按钮（打开左侧会话抽屉）已移除。
            //   需求原文：「第二三幅图这两个也不需要了」。
            //   理由：抽屉是「在聊天页里再切会话」的第二套入口，而会话切换现在统一由
            //   **聊天列表页**（默认首页）承担 —— 两处并存会出现「同一个操作两种入口」，
            //   且抽屉里的列表是命令式 View 拼的、信息密度也低于列表页（无头像/预览/未读）。
            //   收拢到列表页后：切会话只需「返回 → 点另一条」，路径唯一、样式一致。
            //   【不是删功能】改名 / 删除 / 清空当前对话都在；前两者已由列表页长按菜单承接，
            //   「清空当前对话」保留在聊天页右下角的工具区（见 ChatUi 的 ActionRow）。
            // ---------- 人偶身份区：头像 + 名字 + 状态 ----------
            // 【本轮·修「顶栏写死 Dollhouse」】改造前这里是一行固定文案 "Dollhouse"，
            //   完全看不出「正在跟谁聊」。需求书要求顶栏展示**人偶头像、名称、状态**，
            //   而数据层（ChatSessions.dollOfConv → DollStore.byId）本来就齐备，只是没接。
            //   【保留原有能力】长按这块区域仍然唤出聊天搜索（与原长按标题同一入口）。
            val convId = remember { ChatSessions.currentId(topCtx) }
            val dollId = remember(convId) { ChatSessions.dollOfConv(topCtx, convId) }
            val doll = remember(dollId) {
                if (dollId.isEmpty()) null else DollStore.byId(topCtx, dollId)
            }
            var headAva by remember(convId, dollId) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(convId, dollId) {
                headAva = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val custom = PetPrefs.convAvatar(topCtx, convId)
                        if (custom.isNotEmpty()) {
                            com.dollhouse.app.core.ImageDecode.file(custom, 64, 64)
                        } else if (doll == null) null else DollStore.coverOf(topCtx, doll, 64)
                    }.getOrNull()
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    // 【r19·聊天搜索】长按标题唤出搜索框（不占布局空间）。
                    .pressable(
                        onClick = {
                            val a = host.activity as? com.dollhouse.app.MainActivity
                            if (a != null) com.dollhouse.app.ui.chat.FriendProfilePage.open(a, dollId)
                        },
                        onLongClick = { host.showSearchDialog() }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 头像：与气泡 / 列表同一取图口径；没有图就退回内置形象。
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.soft),
                    contentAlignment = Alignment.Center
                ) {
                    val headerBitmap = headAva
                    if (headerBitmap != null && !headerBitmap.isRecycled) {
                        Image(
                            bitmap = headerBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        DhKit.Icon(Icons.IC_CIRCLE_USER, Design.SZ_SM, c.sub)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    // 人偶名：会话绑定的人偶名；未绑定（自由会话）时退回产品名，不显示空白。
                    val name = doll?.name?.takeIf { it.isNotEmpty() } ?: "Dollhouse"
                    Text(
                        text = name,
                        color = c.title,
                        fontSize = DhTokens.type.section.fontSize, lineHeight = DhTokens.type.section.lineHeight,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 状态行：请求在途显示「正在回复」，否则「在线」。
                    //   【不用假数据】两者都由真实状态派生 —— ChatState.waiting
                    //   正是发送键切「停止」的同一个标志。
                    Text(
                        text = if (ChatState.waiting) "正在回复…" else "在线",
                        color = if (ChatState.waiting) c.acc else c.sub,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts,
                        maxLines = 1
                    )
                }
            }
            CtxRingView(host)
            DhKit.IconButton(Icons.IC_MORE, Design.SZ_MD, c.title) {
                val a = host.activity as? com.dollhouse.app.MainActivity
                if (a != null && dollId.isNotEmpty()) ConversationHistoryPage.open(a, dollId)
            }
        }
    }

    /**
     * 上下文占用环：环形 = 已用 / 阈值，中心 = 整数百分比；点开令牌消耗统计页。
     * 【为什么用 Compose 重绘】原先是 `ui/theme/CtxRing.kt` 里的自绘 View，
     *   在 Compose 树里要靠 AndroidView 桥接；环本身只是两条弧 + 一行字，直接画更省一层。
     *   那个 View 类已于 M9 死代码清理中删除（本函数是它唯一的替代实现）。
     */
    @Composable
    private fun CtxRingView(host: ChatHostUi) {
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
                fontSize = DhTokens.type.label.fontSize, lineHeight = DhTokens.type.label.lineHeight,
                fontFamily = DhTokens.fontsBold,
                // 【等宽数字】环里的百分比会随上下文实时变化（0→100），
                //   比例数字下「1」比「0」窄，每跳一次环心数字就左右晃一下。
                //   等宽后数字位置稳定，环看起来才「稳」。
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
            )
        }
    }

    /** 顶部提示条：圆角色块、左右留边，点击跳主界面。 */
    @Composable
    private fun HintBar(host: ChatHostUi) {
        val c = DhTokens.colors
        val text = ChatState.hint ?: return
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(top = DhDesign.Space.xs.dp)
        ) {
            Box(
                modifier = Modifier
                    // 【圆角收敛】原 10dp -> Radius.md(12)：提示条与卡片同族。
                    .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
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
    private fun Messages(host: ChatHostUi, modifier: Modifier) {
        val listState = rememberLazyListState()
        val items = ChatState.items
        val tick = ChatState.scrollTick
        // 【r13·思考过程】是否渲染思考框（设置里的开关）。
        // 【r25】`PetPrefs.showThinking` 每次调用都要 `getSharedPreferences` + getBoolean；
        //   放在组合体里等于「每次滚动 / 每次流式刷新都重读一次」。用 ctx 作 key 记住即可 ——
        //   开关变化会触发主题刷新（ThemeRefresh）导致本页重组，且本函数不依赖它的实时性。
        val ctx0 = LocalContext.current
        val showThink = remember(ctx0) { PetPrefs.showThinking(ctx0) }
        // 【下标补偿】「加载更早」入口与「思考中」占位都不是 [ChatState.items] 的成员，
        //   却各自占着 LazyColumn 的一个位置：列表下标 = items 下标 + offset，
        //   滚动目标与滚动回调必须按同一口径平移，否则收回判据会整体错位一格。
        val offset = if (ChatState.hasPrev) 1 else 0
        val tailExtra = if (ChatState.thinking != null) 1 else 0

        // 【M-修断链】搜索高亮的自动消除。
        //   高亮是「瞬时提示」：用户看到哪一条被找到就够了，不该一直挂着。
        //   这里在设置高亮后 2.4 秒自动清掉（足够读完一句、又不至于让它变成常驻装饰）。
        //   【为什么 key 用 index】同一位置重复搜索（搜同一个词两次）也能重置计时。
        val hi = com.dollhouse.app.ui.chat.SearchHighlight.index
        LaunchedEffect(hi) {
            if (hi >= 0) {
                kotlinx.coroutines.delay(2400)
                com.dollhouse.app.ui.chat.SearchHighlight.clear()
            }
        }

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

        // 【消息间距·R7 两个不等式】留白节奏集中在**这一处**决定，item 内部不再叠第二份。
        //   设计原则（SPACING_MAP R7）：组间距必须明显大于行间距，否则一屏消息糊成一片，
        //   看不出「哪几条是一个人在接连说话」。
        //   实测口径（绝对值）：
        //     · 同一角色连发 → sm(8)
        //     · 换人说话     → md(12)（ spacedBy 8 + 差额 xs 4 ）
        //     · 气泡内部     → xs(4)（图片↔正文）
        //   ⇒ 4 < 8 < 12，组间距 > 行间距成立。
        //   改造前的叠加方式是「spacedBy(3) + item.padding(top=6) [+ 换人 5]」，
        //   即同角色 9 / 换人 14 —— 两者只差 5，而且间距被拆在两个地方，
        //   调一处漏一处就会前功尽弃。现在统一收到 spacedBy 一处。
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(DhDesign.Space.sm.dp)
        ) {
            if (ChatState.hasPrev) {
                item(key = "loadEarlier") { LoadEarlierRow(host) }
            }
            // 【r25·key 必须是稳定标识，不能用下标】
            //   旧写法 `key = { i, _ -> "m$i" }` 把「位置」当身份：
            //   · 「加载更早」把一批消息插到前面 → 每条的下标全变 → Compose 认为
            //     **所有项都是新的**，整列表重建（长会话就是几百个气泡重新组合 + 重测量）；
            //   · 收起展开态（删掉补回批）同理，反向再来一次；
            //   · 流式期间尾项文本每 60ms 变一次，虽然下标没变，但整列仍要复检。
            //   改用「历史下标」作身份：历史里的消息 index 是稳定且唯一的；
            //   仅流式占位气泡 index = -1，它永远只有一条且永远在尾部，单独给固定 key。
            //   规则抽在 [ChatKeys]（纯函数），由 `ChatKeysTest` 钉住。
            itemsIndexed(
                items,
                key = { _, it -> ChatKeys.of(it) },
                // 【contentType：滚动复用槽位的关键】
                //   只有 key 而没有 contentType 时，Compose 不知道「这一项和那一项是同一种布局」，
                //   滚动复用时无法把回收的槽位交给同类型项 —— 长会话来回滚动会反复重建
                //   （用户体感就是"滑动有点涩"）。
                //   这里按三种真实形态分：摘要分割线 / 我方气泡 / 她方气泡。
                //   参考：Compose 性能规范「Mandatory contentType and Primitive key」。
                contentType = { _, it ->
                    when {
                        it.isSummary -> "summary"
                        it.role == "user" -> "user"
                        else -> "assistant"
                    }
                }
            ) { idx, item ->
                // 【消息间距】本 item **不再**承担气泡之间的距离（已由 LazyColumn 的 spacedBy 统一）；
                //   这里只负责「换人说话」时的额外留白，见下方 prevRole 判断。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 【间距收归一处】消息之间的距离统一由 LazyColumn 的 spacedBy(sm) 提供，
                        //   这里不再叠第二份 —— 否则调间距要同时改两处，漏一处就前功尽弃。
                        .padding(top = 0.dp)
                ) {
                    // 【换人说话才拉开】上一 item 是不同角色时才补差额，使总数达到 md(12)。
                    //   层级关系：气泡内 4 < 同角色 8 < 换角色 12 ——
                    //   这正是 R7 要的两个不等式，分组感靠它成立。
                    val prevRole = if (idx > 0) items[idx - 1].role else null
                    if (prevRole != null && prevRole != item.role) {
                        // 【绝对值对齐】目标：换角色 = md(12)。spacedBy 已给 sm(8)，
                        //   故这里只补差额 xs(4) —— 8 + 4 = 12。
                        Spacer(Modifier.height(DhDesign.Space.xs.dp))
                    }
                    // 【r13·思考过程】每条助手回复都在自己的气泡上方挂思考框 ——
                    //   不再只限最后一条（本轮改造前，历史里的回复一律看不到思考）。
                    //   【在途】流式期间正文还没落盘，那条占位气泡的 think 由 ChatState.streamThink 提供。
                    //   【门控】「设置 → 实验 → 对话显示 → 显示思考过程」关掉即整块不渲染
                    //   （思考内容仍在历史里，随时可以打开回看）。
                    if (!item.isSummary && "assistant" == item.role && showThink) {
                        val liveThink = if (idx == items.lastIndex) ChatState.streamThink else null
                        val thinkText = liveThink ?: item.think
                        if (!thinkText.isNullOrEmpty()) {
                            ThinkingBoxView(ThinkingBox(thinkText, item.thinkMs))
                        }
                    }
                    if (item.isSummary) {
                        SummaryDivider(item.text, item.cover)
                    } else {
                        Bubble(host, item)
                    }
                    // 操作条只挂在最后一条助手回复下面（与旧口径一致）。
                    if (item.role == "assistant" && idx == items.lastIndex) {
                        ActionRow(host)
                    }
                }
            }
            // 【空状态】历史为空时不再像改造前那样「什么都不画」—— 一片空白会被读成
            //   「界面坏了 / 没加载出来」。这里给一句轻提示，且**不伪造任何消息**：
            //   开场白仍只走用户自己配置的那份（见 ChatPanel.seedGreetingIfEmpty）。
            if (items.isEmpty() && ChatState.thinking == null) {
                item(key = "empty") { EmptyHint() }
            }
            ChatState.thinking?.let { label ->
                item(key = "thinking") { ThinkingPlaceholder(label) }
            }
        }
    }

    /**
     * 空会话提示：一枚低对比图标 + 一行说明。
     *
     * 【为什么做得这么轻】聊天区是高频界面，空态只出现一瞬（发第一条消息就没了），
     *   做成大插图或彩色卡片反而每次都晃眼。与「说说」占位页同一克制口径。
     */
    @Composable
    private fun EmptyHint() {
        val c = DhTokens.colors
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DhDesign.Space.xxl.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DhKit.Icon(Icons.IC_MESSAGE_SQUARE_HEART, Design.SZ_XL, c.line)
            Spacer(Modifier.height(DhDesign.Space.md.dp))
            Text(
                text = "还没有聊过，说点什么吧",
                color = c.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /** 气泡旁头像边长（dp）← 统一到设计系统阶梯（原为自写的 34）。 */
    private val BUBBLE_AVATAR_DP = com.dollhouse.app.ui.design.DhDesign.Size.avatarBubble.value.toInt()

    /**
     * 气泡旁的小头像。
     *
     * 【为什么这次才加】设置页「显示头像」开关（[PetPrefs.showAvatar]，默认 **开启**）
     *   早就存在，文案也写得很明确：「打开后：人偶发的消息显示**她**的头像，
     *   你发的消息显示**你**的头像」。但聊天界面的气泡渲染从来只画了文字 ——
     *   开关拨来拨去没有任何视觉变化，属于「设置项没有被实现」。
     *
     * 【取图口径与列表页一致】人偶侧走 `ChatListData` 用的同一条链
     *   （自定义会话头像 → 人偶封面 → null），用户侧走 [MyProfile.avatarBitmap]。
     *   这里只做**一次**降采样（64px），不做缓存：气泡可见数量远小于列表，
     *   且 Compose 会按 key 复用节点，重复解码的代价可忽略。
     */
    @Composable
    private fun BubbleAvatar(mine: Boolean) {
        val ctx = LocalContext.current
        val c = DhTokens.colors
        var bmp by remember(mine) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(mine) {
            bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    if (mine) MyProfile.avatarBitmap(ctx, 64) else {
                        val convId = ChatSessions.currentId(ctx)
                        if (convId.isNullOrEmpty()) null else {
                            val custom = PetPrefs.convAvatar(ctx, convId)
                            if (custom.isNotEmpty()) com.dollhouse.app.core.ImageDecode.file(custom, 64, 64) else {
                                val dollId = PetPrefs.convDoll(ctx, convId)
                                val doll = if (dollId.isEmpty()) null else DollStore.byId(ctx, dollId)
                                if (doll == null) null else DollStore.coverOf(ctx, doll, 64)
                            }
                        }
                    }
                }.getOrNull()
            }
        }
        Box(
            modifier = Modifier
                .size(BUBBLE_AVATAR_DP.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.soft),
            contentAlignment = Alignment.Center
        ) {
            val avatarBitmap = bmp
            if (avatarBitmap != null && !avatarBitmap.isRecycled) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                DhKit.Icon(
                    iconRes = if (mine) Icons.IC_CIRCLE_USER else Icons.IC_MESSAGE_SQUARE_HEART,
                    sizeDp = Design.SZ_SM,
                    color = c.sub
                )
            }
        }
    }

    /** 一条气泡：用户靠右（用户色底 + 反白字），助手靠左（卡片底 + 正文色）。 */
    @Composable
    private fun Bubble(host: ChatHostUi, item: ChatItem) {
        val c = DhTokens.colors
        val mine = item.role == "user"
        val ctx = LocalContext.current
        val screenW = LocalConfiguration.current.screenWidthDp
        // 【气泡宽度档位】设置里「聊天 → 气泡宽度」的窄/适中/宽三档，
        //   改造前这里写死 0.72 恒等于「适中」，另两档拨了没反应。
        // 【r25】这两项每个气泡都要读，而流式回复期间所有可见气泡每 60ms 重组一次 ——
        //   等于每帧几十次 SharedPreferences 读（内部也会持锁）。设置项不会在流式期间变，
        //   用 remember 固定住；用户改设置会走 ThemeRefresh → 整页重组，届时重新读。
        val ratio = remember(ctx) {
            when (PetPrefs.bubbleWidth(ctx)) {
                0 -> 0.60f
                2 -> 0.84f
                else -> 0.72f
            }
        }
        val maxW = (screenW * ratio).dp
        val showAva = remember(ctx) { PetPrefs.showAvatar(ctx) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
        ) {
            if (showAva && !mine) {
                BubbleAvatar(false)
                Spacer(Modifier.width(6.dp))
            }
            Column(
                modifier = Modifier
                    .widthIn(max = maxW)
                    // 【M4·按设计稿 §4.2 定气泡几何】
                    //   圆角 16，但**朝向发出者的那个下角收成 6**：气泡的"尾巴"由圆角差承担，
                    //   不加三角、不加描边，是"克制"取向里最省视觉噪音的做法。
                    //   改造前四角一律 16，一列气泡看着像一排相同的圆角矩形，方向感靠对齐硬撑。
                    .clip(
                        if (mine) {
                            RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp)
                        } else {
                            RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp)
                        }
                    )
                    // 【M-修断链】搜索命中时给这条气泡加一圈强调色描边（2dp），
                    //   让用户一眼看到「找到的是哪一条」。
                    //   改造前 ChatPanel 会写 highlightIndex 但 Compose 侧从不读，
                    //   所以这个功能一直是断的。
                    //   【为什么用描边而不是改底色】底色承载「我/对方」的语义（暖灰/卡片白），
                    //   改底色会让这两条语义混淆；描边是叠加层，不破坏原有编码。
                    .then(
                        if (com.dollhouse.app.ui.chat.SearchHighlight.index == item.index) {
                            Modifier.border(
                                2.dp,
                                c.acc,
                                if (mine) {
                                    RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp)
                                } else {
                                    RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp)
                                }
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(if (mine) c.chatBubbleUser else c.card)
                    // 【r13·消息长按】长按弹操作菜单。
                    //   需求：长按 AI 的消息 / 长按自己的消息，各弹出一组操作。
                    //   用 [pressable] 的带长按重载（内部 combinedClickable），
                    //   与单击不冲突 —— 单击仍是「无操作」，符合聊天区习惯。
                    .pressable(
                        onClick = {
                            // 【多选态】多选期间单击 = 勾选/取消勾选（复用同一个手势，不额外加 UI）。
                            if (MultiSelect.on) {
                                MultiSelect.toggle(item.index)
                            }
                        },
                        onLongClick = { showMessageMenu(ctx, host, item) }
                    )
                    // 设计稿 §4.2：气泡内边距 10 垂直 / 14 水平。改造前 8/12 偏紧，
                    //   长中文在窄气泡里换行密集，读起来"顶"。
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                val img = item.image
                if (!img.isNullOrEmpty() && ImageStore.exists(ctx, img)) {
                    BubbleImage(ctx, img, maxW)
                    Spacer(Modifier.height(DhDesign.Space.xs.dp))
                }
                if (item.text.trim().isNotEmpty()) {
                    // 【可选文本】消息正文支持长按选中复制 —— 与长按弹菜单不冲突：
                    //   选中手势在文字自身，菜单在气泡空白与整块长按，两者各管一段。
                    SelectionContainer {
                        if (!mine && item.text.contains(":::card")) {
                            BlockRenderer(item.text, true)
                        } else Text(
                            text = item.text,
                            // 【M4·修真实缺陷：白字压浅灰底】
                            //   我方气泡原来用 `onAcc`（"主色上的文字"，浅色档是纯白）。
                            //   改造前气泡底是蓝色（#4C6FDE），白字没问题；
                            //   换成方向 A 的暖灰 `chatBubbleUser`（#EDE7DC）之后，
                            //   白字对比度只有 **1.23:1** —— 几乎完全看不见（WCAG 要求 4.5）。
                            //   现在两侧统一用 `title`：气泡底色本身已经把"我/对方"区分开了
                            //   （我=暖灰、对方=卡片白），不需要再靠字色区分。
                            //   实测 title/chatBubbleUser = 12.22（浅）/ 12.17（深）/ 15.44（纯黑）。
                            color = c.title,
                            fontSize = UiKit.FS_BTN.sp,
                            fontFamily = DhTokens.fonts,
                            lineHeight = (UiKit.FS_BTN + 6f).sp
                        )
                    }
                }
            }
            if (showAva && mine) {
                Spacer(Modifier.width(6.dp))
                BubbleAvatar(true)
            }
        }
    }

    /**
     * 【r13】弹出某条消息的长按操作菜单。
     *
     * 【为什么用原生弹窗而不是 Compose 的 DhForm.Alert】
     *   `DhForm.Alert` 是 `@Composable`，只能在 Compose 作用域里声明；
     *   本方法是从**长按回调**里调进来的（命令式），调它会编译不过。
     *   `UiKit.showDialog` 是原生 Dialog，任何上下文都能调，且与本 App
     *   其它确认框（清空对话 / 没配模型提示）同一套壳、同一磨砂质感。
     *
     * 【菜单项与显示条件】对齐主流聊天软件的口径 ——
     *   · 双方都有：复制 / 多选 / 插入总结 / 信息；
     *   · **仅自己的消息（user）**：编辑并重发 / 回滚到此处 / 删除；
     *   · **仅她的消息（assistant）**：单条重新生成 / 修改记忆 / 回复 / 删除。
     *
     * 【为什么这样分】「编辑并重发」「回滚到此处」操作的是**自己说过的话**，
     *   长按她的消息时给这两项语义上讲不通（改的是她的发言？删掉她说过的再重发？）；
     *   「单条重新生成」「回复」则是针对**她说的话**才成立的动作。
     */
    private fun showMessageMenu(ctx: android.content.Context, host: ChatHostUi, item: ChatItem) {
        val act = com.dollhouse.app.ui.theme.UiKit.findActivity(ctx) ?: return
        // 消息在 history 里的下标；摘要 / 占位条（index < 0）不参与。
        val idx = item.index
        if (idx < 0) {
            return
        }
        val isAi = "assistant" == item.role
        val isUser = "user" == item.role
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        // ---- 双方都有；纯附件/空消息不提供文本专用操作 ----
        val hasText = item.text.trim().isNotEmpty()
        if (hasText) {
            labels.add("复制")
            actions.add { host.copyAt(idx) }
            labels.add("多选")
            actions.add {
                MultiSelect.on = true
                MultiSelect.toggle(idx)
            }
            labels.add("插入总结")
            actions.add { host.insertSummaryAt(idx) }
        }
        labels.add("信息")
        actions.add { host.showItemInfo(idx) }

        // ---- 仅自己的消息 ----
        if (isUser) {
            labels.add("编辑并重发")
            actions.add { host.editAndResend(idx) }
            labels.add("回滚到此处")
            actions.add { host.rollbackTo(idx) }
        }

        // ---- 仅她的消息 ----
        if (isAi) {
            // 【v0.2.6·失败重试】错误提示本身也存成一条 assistant 气泡（见 finishWithError），
            //   但它不是模型的真实回复：对它用「单条重新生成」会先删掉这条错误提示，
            //   再请求一次 —— 语义正确且不会残留错误文案。这里单独把它提到菜单最前，
            //   名字直说「重试这次」，比让用户在「单条重新生成」里猜更清楚。
            if (item.text.contains("（出错了：")) {
                labels.add("重试这次")
                actions.add { host.retryAfterError() }
            }
            labels.add("单条重新生成")
            actions.add { host.regenerateAt(idx) }
            // 【v0.1.0·新增】赞 / 学样式：把这一问一答存成学习示例。
            //   原来全工程没有任何入口能写 learn_examples，导致「学习示例」
            //   这条功能从上线起就空转（读取端永远拿到空数组）。见 ChatPanel.learnFromAt。
            labels.add("赞 · 学这个风格")
            actions.add {
                if (host.learnFromAt(idx)) {
                    host.flashMemo("已记住这个回答风格")
                } else {
                    host.flashMemo("存不了，请确认之前有你发的提问")
                }
            }
            labels.add("修改记忆")
            actions.add { host.saveToMemory(idx) }
            labels.add("回复")
            actions.add { host.replyTo(idx) }
        }

        // ---- 【r19】对话分支（双方都有）----
        // 把当前会话「前 idx 条」复制成一个新会话，从那里继续聊 ——
        //   与「回滚到此处」的区别：回滚会**删掉**后面的消息（不可逆），
        //   分支是**复制**（原会话保留，新会话独立）。
        labels.add("从这里分支")
        actions.add { host.branchAt(idx) }

        // ---- 删除（双方都有）----
        labels.add("删除")
        actions.add { host.deleteAt(idx) }

        // 标题用消息摘要本身，让用户确认「操作的是哪一条」。
        val head = item.text.trim().replace('\n', ' ')
        val title = if (head.length > 14) head.substring(0, 14) + "…" else head
        // 【r17·图标】「删除」配垃圾桶图标（与列表页的删除按钮同一语义图标）。
        val icons = IntArray(labels.size)
        icons[labels.size - 1] = Icons.IC_TRASH
        // 【M8·改走统一弹窗宿主】MessageMenu 是改造前为「从回调里弹菜单」而手写的
        //   原生 Dialog 实现；现在有 ui/kit/DhDialogHost 承接同一职责，
        //   视觉与全站其余 Compose 弹窗同源（sheet 底 / 16 圆角 / 令牌内边距）。
        //   dangerIndex 指向「删除」：危险项用 err 色，让用户在点之前就先看到它不一样。
        com.dollhouse.app.ui.kit.DhDialogHost.showMenu(
            act,
            title.ifEmpty { "消息" },
            labels,
            actions,
            icons,
            dangerIndex = labels.size - 1
        )
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

    /**
     * 「ⓘ 历史对话摘要」分割标题。
     *
     * 【r13·对齐 Operit】抄的是 Operit `SummaryMessageComposable` 的口径：
     *   左右两条细线 + 中间一枚「ⓘ 历史对话摘要」小徽标（淡色底 + 主色字），**点开弹窗看全文**。
     *
     * 【为什么去掉「覆盖 N 条」】上一版是自创的：Operit 的摘要条上**没有这个信息**，
     *   只写「历史对话摘要」五个字（见 `strings.xml` 的 `history_dialog_summary`）。
     *   加出来既对不上参考，也把一条本该极简的分割线撑成了信息条。
     *   覆盖条数仍存在消息里（`cover` 字段），改在**详情弹窗**里展示 —— 需要的人看得到，不打扰主界面。
     */
    @Composable
    private fun SummaryDivider(text: String, cover: Int) {
        val c = DhTokens.colors
        // 【弹窗还是内联展开】Operit 用的是 Dialog（见 SummaryMessageComposable）。
        //   本工程内联展开会影响 LazyColumn 的项高（展开时下面所有消息整体下移），
        //   观感更接近参考，所以这里也走弹窗。
        var open by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DhDesign.Space.md.dp, bottom = DhDesign.Space.xs.dp)
                .then(if (text.trim().isNotEmpty()) Modifier.pressable { open = true } else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左线：主色 50% 透明，1dp（对齐 Operit 的 primary.copy(alpha=0.5f) / thickness 1.dp）。
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(c.acc.copy(alpha = 0.5f))
            )
            // 中间徽标：主色 10% 淡底 + 12dp 圆角 + 图标 + 主色小字。
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.acc.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DhKit.Icon(iconRes = Icons.IC_INFO, sizeDp = Design.SZ_XS, color = c.acc)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "历史对话摘要",
                    color = c.acc,
                    fontSize = UiKit.FS_CHIP.sp,
                    fontFamily = DhTokens.fonts,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(c.acc.copy(alpha = 0.5f))
            )
        }
        if (open) {
            SummaryDialog(text, cover, onDismiss = { open = false })
        }
    }

    /** 摘要详情弹窗：标题 + 分隔线 + 可滚动正文（对齐 Operit 的 Dialog 结构）。 */
    @Composable
    private fun SummaryDialog(text: String, cover: Int, onDismiss: () -> Unit) {
        val c = DhTokens.colors
        DhForm.Alert(
            title = "历史对话摘要",
            onDismiss = onDismiss,
            posText = "关闭"
        ) {
            Text(
                text = text,
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts,
                lineHeight = (UiKit.FS_BTN + 6f).sp
            )
            // 【覆盖条数放这里】主界面保持极简，需要细节的人点开才看到。
            if (cover > 0) {
                Spacer(Modifier.height(DhDesign.Space.md.dp))
                Text(
                    text = "这条摘要覆盖了 " + cover + " 条消息。",
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /** 聊天记录顶部的「点击加载更早的历史记录」入口。 */
    @Composable
    private fun LoadEarlierRow(host: ChatHostUi) {
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
                    .clip(RoundedCornerShape(DhKit.R_CARD.dp))
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
                    .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                    .background(c.chatActionBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable { open = !open },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DhKit.Icon(iconRes = Icons.IC_BRAIN, sizeDp = Design.SZ_XS, color = c.chatChipMute)
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
                        sizeDp = Design.SZ_XS,
                        color = c.chatChipMute
                    )
                }
                if (open) {
                    Text(
                        text = box.reasoning,
                        modifier = Modifier.padding(top = DhDesign.Space.xs.dp),
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
    private fun ActionRow(host: ChatHostUi) {
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
            // 【本轮·把丢掉的功能补回来】ChatUi 的注释一直写着「清空当前对话保留在
            //   聊天页右下角的工具区（见 ActionRow）」，但 ActionRow 实际只实现了
            //   重新生成与复制 —— 承诺的入口从来没落地。
            //   连带后果：ChatPanel.confirmClearChat()（唯一调用点）成了 0 调用被清理，
            //   于是「清空当前对话」在整个 App 里彻底不可达，而 clearChat() 的逻辑一直完好。
            //   这里按注释原本的设计补回入口，并且**用 Compose 弹窗**（原实现是原生 Dialog）：
            //   破坏性操作用 Alert 是正确形态（与「从列表移除」区分开，那条是可逆的）。
            ActionChip(text = "清空", iconRes = Icons.IC_TRASH) {
                host.confirmClearChat()
            }
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
                // 【r19·修复点击无反应】pressable 的 clickable(interactionSource, indication=null)
                //   在 ChatPanel 边界下可能吞事件；改为显式 clickable（与发送按钮同款修复）。
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(iconRes = iconRes, sizeDp = Design.SZ_XS, color = c.chatChipMute)
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
    private fun AttachStrip(host: ChatHostUi) {
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
            // 【v0.1.0·修·图标偏小】全工程唯一还写着字面量 14f 的按钮图标（旁侧文字 12sp，
            //   但它是**按钮**而不是行内标记 —— 按钮图标最小档是 SZ_SM，14dp 的叉在 40dp 命中区里像个针尖）。
            DhKit.IconButton(iconRes = Icons.IC_CLOSE, sizeDp = Design.SZ_SM, color = c.sub) {
                host.clearAttachment()
            }
        }
    }

    /* ============================ 工具条 ============================ */

    /** 工具条：模型配置 / 思考程度 / 记忆 三图标 + 右侧「记忆总结中」提示。 */
    @Composable
    private fun ToolRow(host: ChatHostUi) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 【r15·图标+文字】工具条右侧有 weight(1f) 弹性空间 → 三枚按钮都加文字。
            // 【同时修正语义错配】原用齿轮图标（IC_SETTINGS）但功能是 host.showModels()
            //   （打开模型选择），图标与功能对不上；改用 IC_LAYERS
            //   （Design 里登记的语义正是「模型集合」，详情页「模型」tab 同款）。
            DhKit.IconButton(iconRes = Icons.IC_LAYERS, sizeDp = 20f, color = c.title, label = "模型") {
                host.showModels()
            }
            Spacer(Modifier.width(2.dp))
            DhKit.IconButton(iconRes = Icons.IC_BRAIN, sizeDp = 20f, color = c.sub, label = "思考") {
                host.showThink()
            }
            Spacer(Modifier.width(2.dp))
            // 【r13·需求改造】原加号（弹功能面板）改为直接的「发图」按钮。
            //   用户定案「甲」：功能面板整页删掉，「发图」挪出来。图标即功能，少一次点击。
            DhKit.IconButton(iconRes = Icons.IC_IMAGE_PLUS, sizeDp = 20f, color = c.sub, label = "图片") {
                host.openPicker()
            }
            Spacer(Modifier.weight(1f))
            // 【r13·多选】多选态下才出现的「复制选中 / 退出」两个动作。
            //   入口由「长按消息 → 多选」开启（见 [MessageMenu] / [MultiSelect]）。
            if (MultiSelect.on) {
                val n = MultiSelect.picked().size
                Text(
                    text = "已选 " + n,
                    modifier = Modifier.padding(end = 4.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts,
                    maxLines = 1
                )
                DhKit.IconButton(
                    iconRes = Icons.IC_COPY,
                    sizeDp = 20f,
                    color = c.title,
                    label = "复制"
                ) {
                    host.copyMany(MultiSelect.picked())
                    MultiSelect.reset()
                }
                Spacer(Modifier.width(2.dp))
                DhKit.IconButton(
                    iconRes = Icons.IC_CLOSE,
                    sizeDp = 20f,
                    color = c.sub,
                    label = "退出"
                ) {
                    MultiSelect.reset()
                }
                Spacer(Modifier.width(4.dp))
            }
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
    private fun InputRow(host: ChatHostUi) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.card)
                .padding(start = 10.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 【本轮·补聚焦态】这是聊天页的主输入框 —— 全 App 最常用的输入位置，
            //   却绕过了 DhForm.Input / DhField 两个入口、自己建 BasicTextField，
            //   于是它是全工程**唯一**没有聚焦反馈的输入框（tools/input_audit.py 的 I6 项抓到）。
            //   聚焦时内框底色 field→card + 1dp accent 描边，与其余 53 处输入框同口径。
            val inputFocus = remember { MutableInteractionSource() }
            val inputFocused by inputFocus.collectIsFocusedAsState()
            val inputFill by androidx.compose.animation.animateColorAsState(
                targetValue = if (inputFocused) c.card else c.field,
                animationSpec = androidx.compose.animation.core.tween(
                    com.dollhouse.app.ui.design.DhDesign.Motion.micro
                ),
                label = "chatInputFill"
            )
            val inputStroke by androidx.compose.animation.animateColorAsState(
                targetValue = if (inputFocused) c.acc else androidx.compose.ui.graphics.Color.Transparent,
                animationSpec = androidx.compose.animation.core.tween(
                    com.dollhouse.app.ui.design.DhDesign.Motion.micro
                ),
                label = "chatInputStroke"
            )
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
                // 【关键】不传 interactionSource 的话，上面的 focused 恒为 false，
                //   聚焦态等于没加 —— 这个坑本轮已经踩过一次。
                interactionSource = inputFocus,
                cursorBrush = SolidColor(c.acc),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(inputFill)
                            .then(
                                if (inputStroke.alpha > 0f) {
                                    Modifier.border(
                                        1.dp,
                                        inputStroke,
                                        RoundedCornerShape(8.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
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
            // 【r19·修点击】发送按钮从 IconButton 换成显式 Box + clickable。
            //   原写法 IconButton 内部走 pressable → clickable(interactionSource, indication=null)，
            //   在 ChatPanel（LinearLayout 嵌 ComposeView）的边界下点击事件可能被吞。
            //   显式 Box 的 clickable 区域就是整个圆形背景，不依赖 interactionSource 的命中判定。
            Box(
                modifier = Modifier
                    .size(UiKit.HIT_DP.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.acc)
                    .clickable {
                        if (ChatState.waiting) {
                            host.stopGenerating()
                        } else {
                            host.onSend()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                DhKit.Icon(
                    iconRes = if (ChatState.waiting) Icons.IC_CLOSE else Icons.IC_SEND,
                    sizeDp = 20f,
                    color = c.onAcc
                )
            }
        }
    }
}
