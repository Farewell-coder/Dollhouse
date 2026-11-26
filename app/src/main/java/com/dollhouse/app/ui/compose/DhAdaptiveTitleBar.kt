package com.dollhouse.app.ui.compose

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】iOS 风格**自适应大标题栏**（QZX `QZXAdaptiveTitleBar` 的 Compose 等价物）。
 *
 * ## 出处
 * 参数抄自 QZX 文档的 `IOS_STYLE` 实测值（`setMinTitleTextSize(19f)` / `setMaxTitleTextSize(32f)` /
 * `setMinHeight(46)` / `setMaxHeight(120)` / `setMaxScrollRange(370)`），**没有源码可参照**，
 * 所以下面凡涉及"怎么插值"的地方都写了理由。
 *
 * ## 与现有 [DhKit.TopBar] 的关系
 * 两者**并存**，不是替换关系：
 * · `TopBar` 是"固定高度 + 顶栏带"的常规二级页顶栏（全仓绝大多数页面）；
 * · 本组件是"随滚动收起"的大标题栏，只适合**首屏是滚动列表**的页面（设置类、资料类）。
 * 所以本文件不改 `TopBar` 一个字符；要用哪个由页面自己选。
 *
 * ## 为什么全程不重组
 * 三个量各自绑在不同阶段，滚动时**一帧都不重组**：
 * | 量 | 绑在哪个阶段 | 机制 |
 * |---|---|---|
 * | 容器高度（120 → 46dp） | **layout** | `Modifier.layout { }` 的 measure 里读滚动状态 |
 * | 背景透明度 | **draw** | `Modifier.drawBehind { }` 里读 |
 * | 标题字号 / 透明度 / 缩放 | **layer** | `Modifier.graphicsLayer { }` 里读 |
 * 三处都是"延迟读取快照状态"，只让对应阶段失效。若把收起进度当成普通 `val t = …` 写在
 * 组合体里，滚动时每帧都会重组整棵顶栏 —— 那正是 iOS 大标题在 Compose 上最常见的性能坑。
 *
 * ## 为什么标题用 `graphicsLayer` 缩放而不是每帧改 `fontSize`
 * 改 `fontSize` = 重新排文字，必然触发 measure + 重组。这里让大标题**始终保持 32sp 排一次版**，
 * 收起时用 `scaleX/scaleY = 19/32 → 1` 缩放（`transformOrigin` 取左中，向左上收），
 * 视觉上就是"32sp 线性收到 19sp"。**缩放是缩小光栅，不会糊**（放大才会）。
 *
 * ## reduceMotion
 * 开启"移除动画"时，收起进度**退化成二值**（滚过一半就跳到收起态），而不是连续插值 ——
 * 无障碍要的是"去掉连续运动、保留状态变化"，不是"把动画放慢"。见 [DhAdaptiveTitleBarSpec]。
 */

/** QZX `IOS_STYLE` 的原始参数（文档实测值，逐位对齐）。 */
object DhAdaptiveTitleBarSpec {

    /** 收起态标题字号（sp）。QZX: `setMinTitleTextSize(19f)`。 */
    const val MIN_TITLE_SP = 19f

    /** 展开态标题字号（sp）。QZX: `setMaxTitleTextSize(32f)`。 */
    const val MAX_TITLE_SP = 32f

    /** 收起态顶栏高度（dp，不含状态栏让位）。QZX: `setMinHeight(46)`。 */
    const val MIN_HEIGHT_DP = 46

    /** 展开态顶栏高度（dp，不含状态栏让位）。QZX: `setMaxHeight(120)`。 */
    const val MAX_HEIGHT_DP = 120

    /** 收起完成所需的滚动距离（px）。QZX: `setMaxScrollRange(370)`。 */
    const val MAX_SCROLL_RANGE = 370f

    /**
     * 收起态的背景不透明度。
     *
     * 【为什么 0.88】与 `DhKit.TOP_BAND_ALPHA` / `ui/kit.STATE_PLATE_ALPHA` / `readableSheet`
     *   同一档 —— 全 App 的"压图玻璃"只有一个浓淡，不另开第二套。
     */
    const val BAR_ALPHA = 0.88f
}

@Composable
fun DhAdaptiveTitleBar(
    title: String,
    scrollState: ScrollState,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // 【本重载更简单】`ScrollState.value` 直接就是"已经滚掉多少像素"，
    //   不需要 LazyListState 那套 `firstVisibleItemIndex × 行高 + offset` 的近似。
    DhAdaptiveTitleBarImpl(
        title = title,
        scrollPx = { scrollState.value.toFloat() },
        onBack = onBack,
        subtitle = subtitle,
        actions = actions
    )
}

@Composable
fun DhAdaptiveTitleBar(
    title: String,
    scrollState: LazyListState,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    DhAdaptiveTitleBarImpl(
        title = title,
        // 【为什么用"第一条目的滚动量"近似总像素】`LazyListState` 不暴露总滚动像素，
        //   能拿到的是 `firstVisibleItemScrollOffset`（当前第一条被滚掉多少）。
        //   约定：顶栏只为**列表头部**服务 —— 一旦滚过第一条就视为完全收起
        //   （`firstVisibleItemIndex > 0` 直接给满量程）。这样避免"按 item 高度反推像素"
        //   这种脆弱换算，观感也与 iOS 一致。
        scrollPx = {
            if (scrollState.firstVisibleItemIndex > 0) {
                DhAdaptiveTitleBarSpec.MAX_SCROLL_RANGE
            } else {
                scrollState.firstVisibleItemScrollOffset.toFloat()
            }
        },
        onBack = onBack,
        subtitle = subtitle,
        actions = actions
    )
}

/**
 * 两个公开重载的**共同实现**。
 *
 * 【为什么参数是"原始滚动像素"而不是"进度"】`reduceMotion` 的二值化规则只该有一处
 *   （见下面 [progress] 的实现）。若让两个重载各自算好进度再传进来，那条规则就会有两份，
 *   以后改一处漏一处 —— 正是这个函数要防的漂移。
 *
 * @param scrollPx 只读的原始滚动量（px）。**只在 layout / draw / graphicsLayer 三个阶段被调用**，
 *                 因此不会触发重组。
 */
@Composable
private fun DhAdaptiveTitleBarImpl(
    title: String,
    scrollPx: () -> Float,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = DhTokens.colors
    val t = DhTokens.type

    // 【状态栏让位】与 `DhKit.TopBar` 同口径：本工程状态栏常隐（inset 恒 0），
    //   只靠 inset 会让标题贴到屏幕最上沿；取「inset 与 24dp 的较大值」两种情况都正确。
    val sbTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topGap = if (sbTop > DhKit.TOP_MIN_GAP.dp) sbTop else DhKit.TOP_MIN_GAP.dp
    val minH: Dp = topGap + DhAdaptiveTitleBarSpec.MIN_HEIGHT_DP.dp
    val maxH: Dp = topGap + DhAdaptiveTitleBarSpec.MAX_HEIGHT_DP.dp

    // 【必须实色】有背景图时 `DhColors.bg` 被 toGlass 改成 `Color.Transparent`（见 DhTheme），
    //   用它当栏底 = 栏完全看不见。`UiKit.BG` 是页面底色的**唯一真源**，与 `photoTopBand`
    //   取的是同一个值 —— 这里只是再叠一层"按收起进度变化"的 alpha。
    val barFill = Color(UiKit.BG)

    // 【有图时不能「顶部全透」】iOS 的做法是展开态栏底全透（内容从栏下穿过），但它背后是
    //   纯色内容底；本工程页面底透明、背后是**用户照片** —— 栏底 0 ⇒ 大标题直接压在照片上，
    //   正是用户反复抱怨的「加了背景字看不清」。所以有图时整条栏恒定 88%（仍然透出图），
    //   无图时才走 iOS 的「顶部全透 → 收起 88%」。
    val hasPhoto = DhKit.hasPhotoBackdrop()

    /**
     * 收起进度：0 = 完全展开，1 = 完全收起。
     *
     * **本 lambda 只允许在 layout / draw / graphicsLayer 三个阶段里调用** —— 那些阶段读快照状态
     * 只让对应阶段失效，不会重组。写在组合体里会被每帧重组。
     *
     * 【reduceMotion 的二值化只在这里做一次】两个公开重载都只负责"怎么算原始像素"，
     *   规则收在一处，不会漂移。
     */
    val progress: () -> Float = {
        val raw = scrollPx()
        if (DhAnim.reduceMotion()) {
            // 关动画：二值跳变（滚过一半即收起），不做连续插值
            if (raw >= DhAdaptiveTitleBarSpec.MAX_SCROLL_RANGE * 0.5f) 1f else 0f
        } else {
            (raw / DhAdaptiveTitleBarSpec.MAX_SCROLL_RANGE).coerceIn(0f, 1f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 收起时大标题会超出 46dp 的栏高 —— 必须裁掉，否则会压到下面的内容上
            .clipToBounds()
            // ① 高度：layout 阶段读进度（只失效 layout）
            .layout { measurable, constraints ->
                val collapsed = progress()
                // 【夹进父约束】`layout` 里自己定的尺寸若越过父容器的 max，Compose 会把
                //   这棵子树判为"违反约束"（矮屏 + 被塞进高度受限的槽里时会直接崩）。
                //   这里夹一次，既能保住 120dp 的观感，也不会在异常父容器里炸。
                val h = lerpDp(minH, maxH, 1f - collapsed).roundToPx()
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(
                    constraints.copy(minHeight = h, maxHeight = h)
                )
                layout(placeable.width, h) { placeable.place(0, 0) }
            }
            // ② 背景：draw 阶段读进度（只失效 draw）。展开时几乎全透，收起时 88% 雾白
            .drawBehind {
                val alphaAtTop = if (hasPhoto) DhAdaptiveTitleBarSpec.BAR_ALPHA else 0f
                val a = lerpF(alphaAtTop, DhAdaptiveTitleBarSpec.BAR_ALPHA, progress())
                if (a > 0f) {
                    drawRect(color = barFill.copy(alpha = a))
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── 紧凑行：返回 + 小标题 + 操作区（铺满收起态的 46dp）──────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DhAdaptiveTitleBarSpec.MIN_HEIGHT_DP.dp)
                    .padding(horizontal = DhDesign.Space.cardPad.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    DhKit.IconButton(
                        iconRes = Icons.IC_ARROW_LEFT,
                        sizeDp = DhDesign.Size.iconMd.value,
                        color = c.title,
                        a11yName = "返回"
                    ) { onBack() }
                    Spacer(Modifier.width(DhDesign.Space.xs.dp))
                }
                // 收起后才出现的小标题：展开态下大标题已经写着同一个字，两个都显示会重影
                Text(
                    text = title,
                    color = c.title,
                    fontSize = DhAdaptiveTitleBarSpec.MIN_TITLE_SP.sp,
                    lineHeight = (DhAdaptiveTitleBarSpec.MIN_TITLE_SP + 5f).sp,
                    fontFamily = DhTokens.fontsBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = progress() }
                )
                actions()
            }

            // ── 大标题：贴底，随收起进度缩小并淡出 ────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(
                        start = DhDesign.Space.cardPad.dp,
                        end = DhDesign.Space.cardPad.dp,
                        bottom = DhDesign.Space.sm.dp
                    ),
                contentAlignment = Alignment.BottomStart
            ) {
                Column {
                    Text(
                        text = title,
                        color = c.title,
                        // 【为什么不套 `style = t.display`】那个 TextStyle 自带它自己那一档的
                        //   fontSize / lineHeight；再传 fontSize 会留下一个与之矛盾的 lineHeight，
                        //   32sp 的字被按 26sp 的行高排版 → 上下被裁。这里字号与行高成对给。
                        fontSize = DhAdaptiveTitleBarSpec.MAX_TITLE_SP.sp,
                        lineHeight = (DhAdaptiveTitleBarSpec.MAX_TITLE_SP + 6f).sp,
                        fontFamily = DhTokens.fontsBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            val p = progress()
                            alpha = 1f - p
                            // 32sp → 19sp：缩放比 19/32。transformOrigin 取左中，
                            // 于是文字向左上方收，像被"吸"进紧凑行里。
                            val s = lerpF(
                                DhAdaptiveTitleBarSpec.MIN_TITLE_SP / DhAdaptiveTitleBarSpec.MAX_TITLE_SP,
                                1f,
                                1f - p
                            )
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            text = subtitle,
                            color = c.sub,
                            style = t.sub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer { alpha = 1f - progress() }
                        )
                    }
                }
            }
        }
    }
}

/** `Float` 线性插值（本文件专用，避免与 Compose 的 `lerp` 重载混淆）。 */
private fun lerpF(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/** `Dp` 线性插值。 */
private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp =
    start + (stop - start) * fraction
