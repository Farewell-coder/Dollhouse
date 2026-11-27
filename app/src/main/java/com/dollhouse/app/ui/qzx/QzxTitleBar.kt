package com.dollhouse.app.ui.qzx

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import com.dollhouse.app.ui.design.DhDesign

/**
 * QZX 的 iOS 自适应大标题栏 —— 对应 `QZXGeometryXLargeTitleBar` 的 `IOS_STYLE` 档。
 *
 * ## 参数全部来自 QZX 文档实测值
 *
 * | 项 | 值 | 来源 |
 * |---|---|---|
 * | 展开态标题 | **32sp** | `setMaxTitleTextSize(32f)` |
 * | 收起态标题 | **19sp** | `setMinTitleTextSize(19f)` |
 * | 展开栏高 | **120dp** | `setMaxHeight(120)` |
 * | 收起栏高 | **46dp** | `setMinHeight(46)` |
 * | 收起量程 | **370px** | `setMaxScrollRange(370)` |
 *
 * ## 「三个绑定点全程零重组」是怎么做到的
 *
 * 收起进度是一个 `() -> Float`。**它只允许在这三个阶段被调用**：
 *
 * | 绑定点 | 阶段 | 修饰符 | 失效范围 |
 * |---|---|---|---|
 * | 栏高 120→46 | **layout** | `Modifier.layout { }` | 只失效 layout |
 * | 栏底 alpha | **draw** | `Modifier.drawBehind { }` | 只失效 draw |
 * | 标题 32→19 + 交叉淡化 | **layer** | `Modifier.graphicsLayer { }` | 只失效 layer |
 *
 * 三个阶段读快照状态，都**不会触发重组**。若把 `progress()` 写在组合体里（例如
 * `val p = progress()` 然后传给子组件），滚动每一帧都会重组整条栏 —— 这是本组件
 * 唯一需要守住的红线。
 *
 * ## 为什么是「交叉淡化」而不是「连续缩一个字」
 *
 * 大标题（32sp）与小标题（19sp）是**两个独立 Text**：
 * - 大标题 `alpha = 1 - p`、`scale = 19/32 → 1`（左中为原点，像被"吸"进紧凑行）
 * - 小标题 `alpha = p`
 *
 * 若只用一个 Text 连续改 fontSize，字距与换行会在中途重算，肉眼看到的是"字在抖"；
 * 交叉淡化两个固定字号的 Text，各自排版稳定，观感才是 iOS 那种"交接"。
 *
 * ## 有背景图时栏底恒 88%
 *
 * iOS 展开态栏底全透（内容从栏下穿过），但它的背后是**纯色内容底**。
 * 本工程页面底透明、背后可能是**用户照片** —— 栏底 0 会让大标题直接压在照片上，
 * 这正是「加了背景字看不清」的来源。所以：
 * - **有图**：整条栏恒定 [QzxTitleBarSpec.BAR_ALPHA]（88%，仍透出图）
 * - **无图**：走 iOS 语义，展开时近乎全透 → 收起时 88%
 *
 * ## reduceMotion
 *
 * 开「移除动画」时进度**二值跳变**（滚过量程一半即视为收起），不做连续插值。
 * 二值化规则**只在私有实现的 `progress` 里做一次** —— 三个公开重载都只负责
 * 「怎么算原始像素」，规则不会漂移。
 */
object QzxTitleBarSpec {
    /** 收起态标题字号（sp）。QZX: `setMinTitleTextSize(19f)`。 */
    const val MIN_TITLE_SP = 19f

    /** 展开态标题字号（sp）。QZX: `setMaxTitleTextSize(32f)`。 */
    const val MAX_TITLE_SP = 32f

    /** 收起态栏高（dp，不含状态栏让位）。QZX: `setMinHeight(46)`。 */
    const val MIN_HEIGHT_DP = 46

    /** 展开态栏高（dp，不含状态栏让位）。QZX: `setMaxHeight(120)`。 */
    const val MAX_HEIGHT_DP = 120

    /** 收起完成所需的滚动距离（px）。QZX: `setMaxScrollRange(370)`。 */
    const val MAX_SCROLL_RANGE = 370f

    /** 收起态栏底不透明度。 */
    const val BAR_ALPHA = 0.88f

    /** 状态栏让位的最小值（dp）：状态栏常隐时也要留出呼吸空间。 */
    const val TOP_MIN_GAP_DP = 24
}

/* ============================ 三个公开重载 ============================ */

/** `ScrollState` 版（`Modifier.verticalScroll` 页面）：进度直接用真实像素。 */
@Composable
fun QzxTitleBar(
    title: String,
    scrollState: ScrollState,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backIconRes: Int? = null,
    hasPhotoBackdrop: Boolean = false,
) {
    QzxTitleBarImpl(
        title = title,
        scrollPx = { scrollState.value.toFloat() },
        onBack = onBack,
        subtitle = subtitle,
        actions = actions,
        backIconRes = backIconRes,
        hasPhotoBackdrop = hasPhotoBackdrop,
    )
}

/**
 * `LazyListState` 版。
 *
 * 【为什么用「第一条目的滚动量」近似总像素】`LazyListState` **不暴露总滚动像素**，
 * 能拿到的只有 `firstVisibleItemScrollOffset`（当前第一条被滚掉多少 px）。
 * 约定：标题栏只为**列表头部**服务 —— 一旦滚过第一条（`index > 0`）即视为收起到位。
 * 这样避免"按 item 高度反推像素"那种脆弱换算。
 *
 * ⚠️ 由此带来一个已知特性：**第一条目越矮，标题收得越快**。
 * 若某页第一条目只有 40dp，滚一行标题就收完了 —— 那种页面应改用 `QzxPage(scroll = None)`
 * 的静态栏，或不接大标题。
 */
@Composable
fun QzxTitleBar(
    title: String,
    scrollState: LazyListState,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backIconRes: Int? = null,
    hasPhotoBackdrop: Boolean = false,
) {
    QzxTitleBarImpl(
        title = title,
        scrollPx = {
            if (scrollState.firstVisibleItemIndex > 0) {
                QzxTitleBarSpec.MAX_SCROLL_RANGE
            } else {
                scrollState.firstVisibleItemScrollOffset.toFloat()
            }
        },
        onBack = onBack,
        subtitle = subtitle,
        actions = actions,
        backIconRes = backIconRes,
        hasPhotoBackdrop = hasPhotoBackdrop,
    )
}

/**
 * `LazyGridState` 版。
 *
 * 【进度按**行**算，不按 item】这是网格与列表唯一的不同：`firstVisibleItemIndex` 数的是
 * **单个 item**，而网格一行有 N 个 —— 照抄列表的「`index > 0` 即收起」，会导致
 * **滚过第一行的第一个 item 就收完**，等于滚了 1/N 行就到位，明显偏早。
 * 所以改读 `layoutInfo.visibleItemsInfo.firstOrNull()?.row`：只有最上面那个可见 item
 * 已属于第 1 行或更后（即第 0 行整行离开视口）才算滚完量程。
 * 行内位移仍用 `firstVisibleItemScrollOffset`（px）线性映射，与列表版手感一致。
 */
@Composable
fun QzxTitleBar(
    title: String,
    scrollState: LazyGridState,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backIconRes: Int? = null,
    hasPhotoBackdrop: Boolean = false,
) {
    QzxTitleBarImpl(
        title = title,
        scrollPx = {
            val topRow = scrollState.layoutInfo.visibleItemsInfo.firstOrNull()?.row ?: 0
            if (topRow > 0) {
                QzxTitleBarSpec.MAX_SCROLL_RANGE
            } else {
                scrollState.firstVisibleItemScrollOffset.toFloat()
            }
        },
        onBack = onBack,
        subtitle = subtitle,
        actions = actions,
        backIconRes = backIconRes,
        hasPhotoBackdrop = hasPhotoBackdrop,
    )
}

/**
 * 静态紧凑栏（不接滚动）：仅 46dp 一行，含返回 / 标题 / 操作区。
 *
 * 【什么时候用】`QzxPage(scroll = QzxPageScroll.None)` —— 内容不滚动（画布、全屏控件），
 * 大标题永远不会收起，那就不该占 120dp。
 */
@Composable
fun QzxTitleBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backIconRes: Int? = null,
    hasPhotoBackdrop: Boolean = false,
) {
    QzxCompactBar(
        title = title,
        onBack = onBack,
        actions = actions,
        backIconRes = backIconRes,
    )
}

/* ============================ 共同实现 ============================ */

/**
 * 三个滚动重载的共同实现。
 *
 * @param scrollPx 只读的原始滚动量（px）。**只在 layout / draw / layer 三个阶段调用**，
 *                 因此不会引起重组。参数是原始像素而不是"进度"，是为了让
 *                 reduceMotion 的二值化规则只存在于本函数一处。
 */
@Composable
private fun QzxTitleBarImpl(
    title: String,
    scrollPx: () -> Float,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backIconRes: Int? = null,
    hasPhotoBackdrop: Boolean = false,
) {
    val c = QzxColors

    // 状态栏让位：本工程状态栏常隐（inset 恒 0），只靠 inset 会让标题贴到最上沿；
    //   取「inset 与 TOP_MIN_GAP_DP 的较大值」两种情况都正确。
    val sbTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topGap: Dp = if (sbTop > QzxTitleBarSpec.TOP_MIN_GAP_DP.dp) sbTop else QzxTitleBarSpec.TOP_MIN_GAP_DP.dp
    val minH: Dp = topGap + QzxTitleBarSpec.MIN_HEIGHT_DP.dp
    val maxH: Dp = topGap + QzxTitleBarSpec.MAX_HEIGHT_DP.dp

    // 必须实色：有背景图时主题里的 `bg` 会被改成 Transparent，用它当栏底 = 栏完全看不见。
    //   `QzxColors.bg` 是页面底色的唯一真源（它本身就是不透明的 `UiKit.BG`，
    //   **没有** `toGlass` 分流），这里只叠"按收起进度变化"的 alpha。
    val barFill = QzxColors.bg

    // 【有图与否由调用方传入】本函数**不读**任何持久化、也不认识"背景图"这个概念 ——
    //   那属于 App 的行为，不属于 UI 系统的职责。调用方传一个 Boolean 即可
    //   （如 `hasPhotoBackdrop = MyApp.hasPhotoBackdrop()`）。
    //   这样新系统对旧包的行为零依赖：删旧系统时这里不需要改一行。
    val hasPhoto = hasPhotoBackdrop

    /**
     * 收起进度：0 = 完全展开，1 = 完全收起。
     * **只允许在 layout / draw / graphicsLayer 调用**（那三处读快照状态只失效对应阶段）。
     */
    val progress: () -> Float = {
        val raw = scrollPx()
        if (QzxMotion.reduceMotion()) {
            // 关动画：二值跳变（滚过量程一半即收起），不做连续插值。
            if (raw >= QzxTitleBarSpec.MAX_SCROLL_RANGE * 0.5f) 1f else 0f
        } else {
            (raw / QzxTitleBarSpec.MAX_SCROLL_RANGE).coerceIn(0f, 1f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 收起时大标题会超出 46dp 栏高 —— 必须裁掉，否则压到下面的内容上
            .clipToBounds()
            // ① 高度：layout 阶段读进度（只失效 layout）
            .layout { measurable, constraints ->
                val collapsed = progress()
                val h = qzxLerpDp(minH, maxH, 1f - collapsed).roundToPx()
                    // 夹进父约束：layout 里自定尺寸若越过父 max，Compose 会判"违反约束"
                    //   （矮屏 / 高度受限的槽里会直接崩）。夹一次既保观感也不炸。
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(
                    constraints.copy(minHeight = h, maxHeight = h)
                )
                layout(placeable.width, h) { placeable.place(0, 0) }
            }
            // ② 背景：draw 阶段读进度（只失效 draw）
            .drawBehind {
                val alphaAtTop = if (hasPhoto) QzxTitleBarSpec.BAR_ALPHA else 0f
                val a = qzxLerpF(alphaAtTop, QzxTitleBarSpec.BAR_ALPHA, progress())
                if (a > 0f) {
                    drawRect(color = barFill.copy(alpha = a))
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── 紧凑行：返回 + 小标题 + 操作区（正好铺满收起态的 46dp）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(QzxTitleBarSpec.MIN_HEIGHT_DP.dp)
                    .padding(horizontal = DhDesign.Space.cardPad.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 【只在「有回调 + 有图标」时画】没给 backIcon 就**不画返回键**，
                //   而不是偷偷回退到旧 drawable —— 否则删掉旧资源时这里会静默失效/编译不过。
                if (onBack != null && backIconRes != null) {
                    QzxIconAction(
                        painter = painterResource(backIconRes),
                        sizeDp = DhDesign.Size.iconMd.value,
                        tint = c.title,
                        a11yName = "返回",
                        onClick = onBack,
                    )
                    Spacer(Modifier.width(DhDesign.Space.xs.dp))
                }
                // 小标题：收起后才出现。展开态大标题已写着同一字，两个都显示会重影。
                Text(
                    text = title,
                    color = c.title,
                    fontSize = QzxTitleBarSpec.MIN_TITLE_SP.sp,
                    lineHeight = (QzxTitleBarSpec.MIN_TITLE_SP + 5f).sp,
                    fontFamily = QzxFonts.bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        // ③ 标题淡入：layer 阶段读进度（只失效 layer）
                        .graphicsLayer { alpha = progress() },
                )
                actions()
            }

            // ── 大标题：贴底，随收起进度缩小并淡出 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(
                        start = DhDesign.Space.cardPad.dp,
                        end = DhDesign.Space.cardPad.dp,
                        bottom = DhDesign.Space.sm.dp,
                    ),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column {
                    Text(
                        text = title,
                        color = c.title,
                        // 字号与行高必须**成对**给：只给 fontSize 会留下与该字号矛盾的
                        //   lineHeight，32sp 的字被按更小的行高排版会上下被裁。
                        fontSize = QzxTitleBarSpec.MAX_TITLE_SP.sp,
                        lineHeight = (QzxTitleBarSpec.MAX_TITLE_SP + 6f).sp,
                        fontFamily = QzxFonts.bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            val p = progress()
                            alpha = 1f - p
                            // 32sp → 19sp：缩放比 19/32。原点取左中，文字向左上方收，
                            //   像被"吸"进紧凑行 —— 这是 iOS 大标题的观感来源。
                            val s = qzxLerpF(
                                QzxTitleBarSpec.MIN_TITLE_SP / QzxTitleBarSpec.MAX_TITLE_SP,
                                1f,
                                1f - p,
                            )
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            text = subtitle,
                            color = c.sub,
                            // 用显式字号/行高代替旧 TextStyle：新系统不持有整套 Type 对象，
                            //   而副标题只需要「比正文略小、单行」这一档。
                            fontSize = QzxTokens.Type.SECTION_SP.sp,
                            lineHeight = (QzxTokens.Type.SECTION_SP + QzxTokens.Type.LINE_GAP_SP).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer { alpha = 1f - progress() },
                        )
                    }
                }
            }
        }
    }
}

/** 静态紧凑栏（不接滚动，无大标题）。 */
@Composable
private fun QzxCompactBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backIconRes: Int? = null,
) {
    val c = QzxColors
    val sbTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topGap: Dp = if (sbTop > QzxTitleBarSpec.TOP_MIN_GAP_DP.dp) sbTop else QzxTitleBarSpec.TOP_MIN_GAP_DP.dp
    val barFill = QzxColors.bg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topGap)
            .height(QzxTitleBarSpec.MIN_HEIGHT_DP.dp)
            .drawBehind { drawRect(color = barFill.copy(alpha = QzxTitleBarSpec.BAR_ALPHA)) }
            .padding(horizontal = DhDesign.Space.cardPad.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null && backIconRes != null) {
            QzxIconAction(
                painter = painterResource(backIconRes),
                sizeDp = DhDesign.Size.iconMd.value,
                tint = c.title,
                a11yName = "返回",
                onClick = onBack,
            )
            Spacer(Modifier.width(DhDesign.Space.xs.dp))
        }
        Text(
            text = title,
            color = c.title,
            fontSize = QzxTitleBarSpec.MIN_TITLE_SP.sp,
            lineHeight = (QzxTitleBarSpec.MIN_TITLE_SP + 5f).sp,
            fontFamily = QzxFonts.bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * 最小图标按钮（本包自带，**不引用旧 `DhKit.IconButton`**）。
 *
 * 【为什么自己写一个】新系统要能在旧 UI 包整体删除后独立存活。依赖 `DhKit` 会让
 *   「删掉旧 UI」变成不可能的清理动作。这里只用到调用方传入的 `Painter` + `ColorFilter.tint`，
 *   够用且零耦合。
 *
 * 【为什么 `indication = null`】与旧系统一致：本工程刻意不用涟漪（见设计稿 §3），
 *   按压反馈由 `QzxPage` / 内容组件自己的动效承担。
 */
@Composable
private fun QzxIconAction(
    painter: Painter,
    sizeDp: Float,
    tint: Color,
    a11yName: String,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // 【按压反馈】新系统不引涟漪（设计稿 §3「no ripple beyond that」），
    //   用 0.96 缩放替代 —— 与旧系统 `pressable` 同一手感口径。
    val scale by animateFloatAsState(
        targetValue = if (pressed) QZX_ICON_PRESS_SCALE else 1f,
        animationSpec = if (pressed) QzxMotion.PRESS_DOWN else QzxMotion.BOUNCY,
        label = "qzxIconPress",
    )

    // 【命中区 48dp / 图标 20dp】视觉尺寸与可点尺寸是两回事：
    //   图标按设计稿取 20dp（太大了不像顶栏按钮）；命中区按无障碍下限 48dp，
    //   否则 20dp 的方块点不准。旧系统把这层封在 `pressable` 里，
    //   新系统自己给，并且**显式写出 48dp** —— 既是真需求，也让
    //   「裸 clickable 无命中区保障」的检查规则自然通过。
    Box(
        modifier = Modifier
            // 【顺序·把 clickable 提到 graphicsLayer 之前】两者都作用于同一个节点，
            //   但对**门禁取证**不一样：E12 只沿"以 `.` 开头的相邻行"往上找 48dp 证据，
            //   中间夹一个多行 lambda（`graphicsLayer { }`）就把链截断了 —— 于是明明有
            //   `.size(QZX_HIT_DP.dp)` 也会被判成"裸 clickable"。
            //   放在 size 之后、其余修饰符之前：命中区判定不变（同一个 48dp 盒子），
            //   而证据就在紧邻上一行，规则看得见、也不会有人靠豁免注释糊过去。
            .size(QZX_HIT_DP.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = a11yName }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(sizeDp.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/** 图标按钮的命中区下限（dp）：无障碍 48，与视觉尺寸无关。 */
private const val QZX_HIT_DP = 48

/** 图标按钮按压缩放（与旧系统 `PRESS_SCALE` 同值：换系统不改手感）。 */
private const val QZX_ICON_PRESS_SCALE = 0.96f

/* ============================ 局部工具（避免与 Compose 的 lerp 重载混淆） ============================ */

/** `Float` 线性插值。 */
private fun qzxLerpF(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/** `Dp` 线性插值。 */
private fun qzxLerpDp(start: Dp, stop: Dp, fraction: Float): Dp =
    Dp(qzxLerpF(start.value, stop.value, fraction))
