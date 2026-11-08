package com.dollhouse.app.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.dollhouse.app.ui.compose.DhTokens
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.kit.IconSeat
import com.dollhouse.app.ui.kit.IconSeatShape
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.UiKit
import kotlinx.coroutines.flow.collect
import com.dollhouse.app.ui.design.DhDesign

/**
 * 【职责】Compose 侧通用构件，视觉参数逐一对齐既有 View 版（[UiKit] / [com.dollhouse.app.ui.theme.Design]）。
 *
 * 【为什么不让页面各写各的】规格书要求「新增卡片与现有 UI 一致（尺寸/间距/圆角/图标/标题/说明/反馈）」。
 *   若每个 Compose 页自己写 `RoundedCornerShape(16.dp)`，第一批迁移就会出现「有的 16、有的 12」，
 *   全局统一立刻破产。故所有尺寸只在本文件出现一次，页面只认构件名。
 *
 * 【参数来源】不是拍的，全部抄自既有 View 实现：
 *   - 卡片：圆角 16dp / 描边 1dp(STROKE) / 卡片间距 10dp / 内边距 16dp ← `UiKit.card` + `HomeCards.buildCard`
 *   - 行：圆角 10dp / SOFT 底 / 内边距 12dp / 行距 8dp ← `HomeCards.valueRow`
 *   - 顶栏：标题 20sp 粗 / 副标题 12sp / 命中区 40dp ← `UiKit.topBar` + `UiKit.FS_*` + `UiKit.HIT_DP`
 *   - 开关：44×24dp / 滑块内缩 3dp ← `UiKit.Switch` 的 `W_DP/H_DP/PAD_DP`
 *   - 药丸：圆角 999 / 内边距 12×8 / 12sp ← `UiKit.outlineChip`
 */
object DhKit {

    /* ============================ 尺寸令牌（M1 起统一委托 DhDesign） ============================
     * 【M1·统一】改造前本节与 `ui/theme/Design.kt`、`ui/theme/UiKit.kt` 各有一套刻度，
     *   同一语义有两个值（例如卡片间距 10 与 12、图标间距 10 与 8），页面之间因此「不像同一套」。
     *   现在**唯一真源**是 `ui/design/DesignSystem.kt` 的 [DhDesign]；本节只保留旧常量名做别名，
     *   避免一次性改掉数百处调用点，M9 再逐步删别名、让页面直接引用 DhDesign。
     */

    /** 卡片圆角（dp）← [DhDesign.Radius.lg]。 */
    const val R_CARD = com.dollhouse.app.ui.design.DhDesign.Radius.lg
    /** 行 / 小方块圆角（dp）← [DhDesign.Radius.md]。改造前是 10（非 4 的倍数、无设计依据）。 */
    const val R_ROW = com.dollhouse.app.ui.design.DhDesign.Radius.md
    /** 卡片间距（dp）← [DhDesign.Space.cardGap]。改造前 10 与 12 并存，统一取 12。 */
    const val GAP_CARD = com.dollhouse.app.ui.design.DhDesign.Space.cardGap
    /** 卡片内边距（dp）← [DhDesign.Space.cardPad]。 */
    const val PAD_CARD = com.dollhouse.app.ui.design.DhDesign.Space.cardPad

    /* ============================ 文本样式（v0.2.3·统一行高） ============================
     * 【为什么新增这一组】全工程 36 处内联 `fontSize = X.sp`，**没有一处带 lineHeight**
     *   —— Compose 的 Text 在不给行高时会用「字体的 lineHeight」，而本工程用的是自带
     *   单字重字体派生的粗体，行高比系统字体更紧。结果就是中文多行文案上下贴在一起，
     *   用户反馈的「文字错位、阅读性低」里很大一部分来自这里。
     * 【M1·统一】行高改为跟随 [DhDesign.Type] 阶梯（title 20/28、body 15/24、sub 13/20）。
     */
    /** 页面标题行的行高（20sp → 28）。 */
    const val LH_TITLE = 28
    /** 正文 / 按钮文案的行高（15sp → 24）。 */
    const val LH_BTN = 24
    /** 说明 / 副标题的行高（13sp → 20）。 */
    const val LH_SUB = 20
    /** 行内边距（dp）← [DhDesign.Space.md]。 */
    const val PAD_ROW = com.dollhouse.app.ui.design.DhDesign.Space.md
    /** 行间距（dp）← [DhDesign.Space.sm]。 */
    const val GAP_ROW = com.dollhouse.app.ui.design.DhDesign.Space.sm
/** 图标命中区（dp）← [DhDesign.Size.minTouch]。 */
    const val HIT = 48

    /**
     * 【v0.1.5·K-03】最小可点击边长（dp）—— 需求书 R-091 / N-010 / 19.9.3。
     *
     * 【为什么不直接把 [HIT] 改成 48】[HIT] 同时被用作**视觉尺寸**（图标所在的正方形，
     *   以及一排按钮的排布间距）。直接改大会让顶栏按钮、卡片底部三枚按钮挤在一起，
     *   甚至在 360dp 宽度下溢出（N-011 不允许）。
     *
     * 【正确做法】视觉保持 [HIT]，**命中区**用 [minTouchTarget] 补足到 48dp：
     *   加一层透明的最小尺寸约束，点在图标外的 4dp 边距上也算点中。
     */
    const val MIN_TOUCH = 48

    /**
     * 【v0.1.4·UI 统一 · M1 修订】所有设置行的左侧图标尺寸（dp）。
     *
     * 【为什么必须只有一个值】改造前同一页里有 14 / 16 / 18 / 20 四种：
     *   SectionLabel 走 SZ_XS、ValueRow 走 SZ_SM、ActionRow 走 SZ_MD，并排时视觉重量参差。
     * 【M1 为什么从 18 改成 20】设计稿 §2.2 把行图标定为 20dp：
     *   与 15sp 正文（字面高度约 20px）等高、远小于 24dp 状态图标，
     *   三者比例最协调；18 与 16 的差距（2dp）在 3x 屏上几乎看不出层级，
     *   反而让「行图标」与「行内小图标」混在一起。
     */
    const val ICON_ROW_DP = 20f
    /**
     * 行首图标**圆底**的直径（dp）。
     *
     * 【为什么是 28 而不是 IconSeatSize.SM(32)】见 [RowIcon] 的注释：
     *   32dp 正好顶满 56dp 单行行高的内容区（56 - 12×2 = 32），
     *   而本图标被 169 处转发行共用 —— 顶满会让任何 +1dp 的行内内边距把行撑高。
     *   28dp 留 4dp 余量（28 + 24 = 52 < 56），两行形态更宽松。
     *
     * 【不与 IconSeatSize 三档对齐是有意的】三档是"独立图标座"的尺寸（列表外、空态、Hero），
     *   行首图标是"嵌在行里的小座"，预算受行高约束，属另一套几何。
     */
    const val ROW_ICON_SEAT_DP = 28

    /** 图标与文字的间距（dp）← [DhDesign.Space.iconGap]。改造前 8 与 10 并存，统一取 8。 */
    const val GAP_ICON = com.dollhouse.app.ui.design.DhDesign.Space.iconGap

    /** 行内说明文字字号（sp）。M1 起跟随 [DhTokens.type.sub]（13sp），不再用 12。 */
    const val FS_ROW_DESC = 13f

    /** 行最小高度（dp）：保证无说明项与有说明项的可点区域都不低于 Android 建议的 48dp。 */
    const val MIN_ROW_H = 48

    /** 页面水平内边距（dp）← [DhDesign.Space.pageX]。 */
    const val PAD_PAGE = com.dollhouse.app.ui.design.DhDesign.Space.pageX

    /**
     * Tab 页大标题字号（sp）—— 聊天 / 人偶 / 说说 三格统一。
     *
     * 【M1 为什么从 26 降到 20】改造前 Tab 页标题 26sp、二级页标题 20sp（FS_TITLE），
     *   同一个 App 里「页面标题」有两个尺寸，切换 Tab 与进入子页时标题会跳一下。
     *   设计稿 §2.1 只给了一档 title（20/600/28）用于**每一个**屏的标题。
     *   26sp 的层级需求由 TopBar 上方的 kicker（Dollhouse 品牌字）与更大的留白承担。
     */
    const val TAB_TITLE_SP = 20f

    /** Tab 页顶栏上内边距（dp）。 */
    const val TAB_BAR_TOP = 14

    /** Tab 页顶栏下内边距（dp）。 */
    const val TAB_BAR_BOTTOM = 10

    /**
     * 二级页顶栏的最小上留白（dp）。
     *
     * 【为什么需要】本工程状态栏常隐（`ImmersiveStatus.hideStatusBars`），
     *   状态栏 inset 恒为 0，仅靠 inset 会让返回键与标题贴到屏幕最上沿。
     *   取「inset 与 24dp 的较大值」：隐藏时给固定呼吸，唤出时自动让开。
     */
    const val TOP_MIN_GAP = 24

    /* ============================ 页面脚手架 ============================ */
    /**
     * 标准覆盖页外壳：BG 底 + 刘海安全区 + 统一顶栏 + 可滚动内容。
     *
     * 【为什么要统一】迁移期同一屏里会同时存在 View 页与 Compose 页。若每个 Compose 页自己
     *   设 padding / 顶栏，就会出现「有的页顶部留 12、有的留 0，标题位置跳一下」的突兀感。
     *   这里把 `UiKit.topBar` 的口径（水平 16 / 顶部 12 + cutout）一次固定，页面只管内容。
     *
     * 【顶栏内边距口径】← `UiKit.topBar`：`bindCutoutPadding(bar, 16, 12, 16, 0)`，
     *   外加 `minimumHeight = 40 + 12 + cutout.top`。这里用 [WindowInsets.displayCutout] 等价实现。
     */
    @Composable
    fun Page(
        title: String,
        sub: String? = null,
        onBack: (() -> Unit)? = null,
        scrollable: Boolean = true,
        actions: (@Composable RowScope.() -> Unit)? = null,
        bottomPadDp: Int = 0,
        onScroll: ((y: Int) -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 【r14·需求 1 根因修复】这里原先有一行
                //     `.background(DhTokens.colors.bg)`
                //   它是 r13 为修「二级页串页」加的兜底 —— 作用是「内容高度算错时也别漏出下层」。
                //   副作用是**把全局背景图整个盖死**：用户设置了背景却在任何二级页都看不到
                //   （Compose 画的这层不透明底，View 树的 clearOpaquePageBgs 够不到）。
                //
                //   【现在由谁兜底】不透明底统一改由 `GlobalBackground.installPage` 铺在
                //   **页根 View** 上（见 PageStack.push 的调用点）：
                //   · 它同样能挡住下层页面（防串页，职责不变）；
                //   · 它是 View background，能被清理函数管到，不会再出现同类盲区；
                //   · 背景图由它一并画出，Compose 侧只需保持透明即可透出。
                //   【为什么更稳】页根 View 的尺寸由页面栈给定（match_parent），
                //   不受 Compose 内部「内容高度算成 0」的影响，兜底能力反而比原来更强。
                .windowInsetsPadding(WindowInsets.displayCutout)
        ) {
            Box(modifier = Modifier.padding(horizontal = PAD_CARD.dp)) {
                TopBar(title = title, sub = sub, onBack = onBack, actions = actions)
            }
            val inner: @Composable ColumnScope.() -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAD_CARD.dp)
                        // 【修·上下留白不对称】改造前这里是「顶部 0 + 底部 24」：
                        //   TopBar 自带 bottom=4，于是标题到第一块内容只有 4dp，
                        //   而最后一块内容到底部有 24dp —— 整页看起来"头重脚轻地挤在标题下面"。
                        //   顶部补 8dp（标题与内容的呼吸），与底部 24dp（列表末端留白）形成
                        //   合理的主次：内容区自带呼吸、末端再多留一档。
                        .padding(top = DhDesign.Space.sm.dp)
                        .padding(bottom = 24.dp + bottomPadDp.dp),
                    content = content
                )
            }
            if (scrollable) {
                val scroll = rememberScrollState()
                // 【为什么把滚动上报出去】页面被「悬浮玻璃胶囊」壳层包着时（ProviderDetailPage），
                //   壳层靠滚动位置决定底栏淡出 / 淡回；而壳层是 View 树、拿不到 Compose 的
                //   scrollState。这里把每次滚动回调出去，壳层不必再去 View 树里找 ScrollView。
                val cb = rememberUpdatedState(onScroll)
                LaunchedEffect(scroll, onScroll) {
                    if (onScroll != null) {
                        snapshotFlow { scroll.value }.collect { cb.value?.invoke(it) }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scroll)
                ) {
                    inner()
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    inner()
                }
            }
        }
    }

    /* ============================ 卡片 ============================ */

    /**
     * 标准卡片：CARD 底 + 圆角 16 + 发丝描边，下方留组间距。
     *
     * @param spacingBottom 是否在卡片下方留 [GAP_CARD] 间距；连排最后一张可传 false。
     * @param flat 平铺形态：不画卡片底 / 描边（用于"已在一张卡片内部的子分区"）。
     */
    @Composable
    fun Card(
        modifier: Modifier = Modifier,
        spacingBottom: Boolean = true,
        collapsibleTitle: String? = null,
        // 【r16·图标】卡片折叠标题左侧的可选语义图标（Icons.IC_*）。
        //   默认 0 = 不画图标，与改造前**逐像素一致** —— 全工程 40 多处 Card 调用零影响，
        //   只有显式传图标的那几处（设置页 5 张主卡片）才多一枚。
        titleIcon: Int = 0,
        flat: Boolean = false,
        content: @Composable ColumnScope.() -> Unit
    ) {
        // 【M-收口·转发到规范实现】本函数有 53 处调用点，其中 47 处是折叠卡。
        //   改造它等于一次性让全部卡片对齐设计规范（弱卡：card 底 + 1dp line 描边 + 圆角 16）。
        //   【为什么用转发而不是复制样式】`ui/kit.DhGroup` 是规范源。
        //
        //   【唯一必须走旧实现的路径】flat = true（5 处，全在 LamdaPage 的
        //   "嵌入卡片内"子分区）：DhGroup 没有"不画卡片底/描边"的形态，直转会
        //   出现**卡片套卡片**的双层圆角。
        //   该参数并非如任务描述所说"0 处调用" —— 实测 5 处，且是 Embedded 路径的真实入参。
        //
        //   【折叠卡为什么现在可以转发了】`DhGroup` 已补 AnimatedVisibility
        //   （CARD_EXPAND / CARD_COLLAPSE / CARD_FADE_IN / CARD_FADE_OUT，
        //   expandFrom / shrinkTowards 均为 Top），与旧实现逐参数一致。
        //   47 处折叠卡的展开/收起手感因此不变。
        //
        //   【持久化】展开态仍由本类读写 `PetPrefs.cardOpen / setCardOpen`：
        //   读到的值经 `expandState` 传入，点击经 `onToggle` 回写 ——
        //   "用户收起后下次进来仍收起"的行为原样保住。
        if (flat) {
            LegacyCard(modifier, spacingBottom, collapsibleTitle, titleIcon, flat, content)
            return
        }
        if (collapsibleTitle != null) {
            // 折叠卡：持久化展开态 → 外部状态驱动 DhGroup。
            val ctx = LocalContext.current
            var open by remember(collapsibleTitle) {
                mutableStateOf(
                    if (PetPrefs.hasCardOpen(ctx, collapsibleTitle)) {
                        PetPrefs.cardOpen(ctx, collapsibleTitle)
                    } else {
                        true
                    }
                )
            }
            com.dollhouse.app.ui.kit.DhGroup(
                modifier = modifier,
                spacingBottom = spacingBottom,
                collapsibleTitle = collapsibleTitle,
                titleIcon = titleIcon,
                expandState = open,
                onToggle = { next ->
                    open = next
                    PetPrefs.setCardOpen(ctx, collapsibleTitle, next)
                },
                content = content
            )
            return
        }
        com.dollhouse.app.ui.kit.DhGroup(
            modifier = modifier,
            spacingBottom = spacingBottom
        ) {
            // 【内边距对齐】旧 Card 的非折叠内容用 `padding(PAD_CARD)`（四边 16dp），
            //   而 DhGroup 给的是「左右 lg(16) + 上下 xs(4)」—— 横向已经一致，
            //   纵向少了 12dp。不补的话唯一走转发的调用点（ProviderModelsPage 的模型卡）
            //   会明显变挤。这里补回差值，让转发前后的视觉逐像素一致。
            Column(
                modifier = Modifier.padding(
                    vertical = (PAD_CARD - com.dollhouse.app.ui.design.DhDesign.Space.xs).dp
                )
            ) {
                content()
            }
        }
    }

    /**
     * 旧版 Card 实现：**只剩 `flat = true` 这一种形态**在用。
     *
     * 【谁在用】`flat = true`（5 处，全在 `LamdaPage` 的"嵌入卡片内"子分区，
     *   经 `Embedded()` → `LamdaCards(act, state, true)` 真实可达）。
     *   DhGroup 没有"不画卡片底 / 描边"的形态，直接转发会出现**卡片套卡片**的双层圆角，
     *   故这类调用继续走这里。
     *
     * 【折叠卡已经转发走了】`collapsibleTitle != null` 的 47 处现在转发到
     *   `ui/kit.DhGroup`（它已补 AnimatedVisibility + 外部展开态）。
     *   **不要在注释里把折叠卡算成本函数的调用方** —— 那是上一版的状态，已过期。
     *
     * 【所以本函数的语义】"平铺卡"。它与 DhGroup 的差别只有两点：
     *   ① 不画卡片底 / 描边（由调用方保证自己已经在一张卡里）；
     *   ② 仍支持折叠（`LamdaPage` 的 flat 卡传了 `collapsibleTitle`），
     *      折叠态同样读写 `PetPrefs.cardOpen / setCardOpen`。
     *   【若将来要删掉这个分支】需要先让 DhGroup 支持"无卡片壳"形态（例如加一个
     *   `flat: Boolean`），否则这 5 处会层级错乱。
     */
    @Composable
    private fun LegacyCard(
        modifier: Modifier,
        spacingBottom: Boolean,
        collapsibleTitle: String?,
        titleIcon: Int,
        flat: Boolean,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val c = DhTokens.colors
        val ctx = LocalContext.current
        val title = collapsibleTitle
        // 【折叠态从哪来】用户手动调过就以记录为准；没调过默认展开。
        //   为什么默认展开：收起态把内容高度压成 0，用户第一次进设置页会以为「卡片是空的」，
        //   这比「多看到几行」糟得多。
        var open by remember(title) {
            mutableStateOf(
                title == null ||
                    (if (PetPrefs.hasCardOpen(ctx, title)) PetPrefs.cardOpen(ctx, title) else true)
            )
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = if (spacingBottom) GAP_CARD.dp else 0.dp)
                // 【r13·flat】平铺形态：只保留间距与分组标题，不画卡片底 / 描边。
                //   用于「已在一张卡片内部的子分区」—— 否则会出现卡片套卡片的两层圆角。
                .then(
                    if (flat) {
                        Modifier
                    } else {
                        Modifier
                            // 【e1 轻浮起】这是全 App 唯一「卡片有没有立体感」的地方 ——
                            //   15 个文件约 50 张卡片都走这里，改这一行就是全局生效。
                            //   【为什么 2dp 而不是更大】用户取向是「安静居所」：层级主要由
                            //   留白与发丝线承担，阴影只做「几乎看不出、但大脑能感到」的纵深。
                            //   2dp 在浅色档几乎不可见，只在暗色档边缘有极淡的一圈 —— 这正是要的效果。
                            //   【clip = false】阴影必须画在形状**外侧**；先 clip 再 shadow 会被裁掉。
                            // 【染色是轻拟物的关键】Compose 默认阴影是纯黑，
                            //   在浅蓝底上会显脏、显硬。把 ambient（环境光）与 spot（投影）
                            //   都染成蓝灰，阴影就有了「柔和」的观感而不是「一圈灰」。
                            //   ambientColor/spotColor 需要 API 28+，低版本自动退回黑色，不会崩。
                            .shadow(
                                elevation = com.dollhouse.app.ui.design.DhDesign.Elevation.e1,
                                shape = RoundedCornerShape(R_CARD.dp),
                                clip = false,
                                ambientColor = DhTokens.palette.shadowTint,
                                spotColor = DhTokens.palette.shadowTint
                            )
                            .clip(RoundedCornerShape(R_CARD.dp))
                            .background(c.card)
                            // 【M3】描边改 `line`（1dp 发丝线）。改造前用 `stroke`（一种更实的
                            //   半透明深色）画 1dp —— 5 张卡叠起来页面像被框线切碎。
                            //   设计稿：层级由留白与发丝线承担，卡片只"微微浮起"。
                            .border(1.dp, c.line, RoundedCornerShape(R_CARD.dp))
                    }
                )
        ) {
            if (title != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable {
                            val now = !open
                            open = now
                            PetPrefs.setCardOpen(ctx, title, now)
                        }
                        .padding(horizontal = PAD_CARD.dp)
                        .padding(top = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 【r16·卡片标题图标】传了 titleIcon 才画：图标在左、标题紧随其后。
                    //   间距 10dp 与 ActionRow 的图标-文字间距同口径，视觉上连成一体。
                    if (titleIcon != 0) {
                        // 【M3·强调色节制】卡片标题图标原用主色 `acc` —— 一页 5 张卡就是 5 个
                        //   紫图标，主色被当成"装饰色"，真正的可操作元素（按钮/选中态）反而看不出来。
                        //   设计稿要求「一个屏只有一个强调色，用在主操作上」，故标题图标改中性 `sub`。
                        Icon(
                            iconRes = titleIcon,
                            sizeDp = Design.SZ_MD,
                            color = c.sub
                        )
                        Spacer(Modifier.width(GAP_ICON.dp))
                    }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        color = c.title,
                        // 【M3·层级】卡片标题走 section 档（16sp/600），与行标题（15sp/500）形成
                        //   明确的一级差异；改造前标题 15sp/Bold、行标题 15sp，几乎分不出谁是分组。
                        fontSize = DhTokens.type.section.fontSize,
                        lineHeight = 22.sp,
                        fontFamily = DhTokens.fonts,
                        fontWeight = FontWeight.SemiBold
                    )
                    // 【箭头】收起指下、展开指上，与旧版 `UiKit.arrow` 的 0°/90° 同语义。
                    Icon(
                        iconRes = Icons.IC_CHEVRON_DOWN,
                        sizeDp = Design.SZ_SM,
                        color = c.sub,
                        modifier = Modifier.rotate(if (open) 180f else 0f)
                    )
                }
            }
            if (title == null) {
                // 【非折叠卡片】保持与改造前逐像素一致：不套 AnimatedVisibility。
                //   否则列表项（ProviderModelsPage 的模型卡）每次复用都会播一次入场动画，滚动时反复闪。
                Column(modifier = Modifier.fillMaxWidth().padding(PAD_CARD.dp)) {
                    content()
                }
            } else {
                // 【本次修复·展开无动效】改造前这里只有 `AnimatedVisibility(visible = open)`，
                //   没给 `enter` / `exit` —— Compose 的默认值对 ExpandVertically/ShrinkVertically
                //   用的是 expandFrom = Alignment.Top + 默认 spring，收起方向还会残留一帧高度。
                //   现在显式给出与 View 侧 `UiKit.expand` 同源的时长与曲线：
                //   展开 D_LAYER + 减速、收起 D_MICRO + 加速（「离开比进入快」）。
                AnimatedVisibility(
                    visible = open,
                    enter = expandVertically(
                        animationSpec = DhAnim.CARD_EXPAND,
                        expandFrom = Alignment.Top
                    ) + fadeIn(animationSpec = DhAnim.CARD_FADE_IN),
                    exit = shrinkVertically(
                        animationSpec = DhAnim.CARD_COLLAPSE,
                        shrinkTowards = Alignment.Top
                    ) + fadeOut(animationSpec = DhAnim.CARD_FADE_OUT)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PAD_CARD.dp)
                            .padding(bottom = PAD_CARD.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }

    /** 卡片内标题（与 `HomeCards.buildCard` 的 head 文案同规格：15sp 粗、TITLE）。 */
    @Composable
    fun CardTitle(text: String, modifier: Modifier = Modifier, iconRes: Int = 0) {
        val c = DhTokens.colors
        if (iconRes == 0) {
            Text(
                text = text,
                modifier = modifier,
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fontsBold,
            )
        } else {
            // 【r16·图标】卡片内小标题也可带图标；默认 0 = 与改造前逐像素一致。
            // 【r16·修·防溢出】与 SectionLabel 同口径：文字拿 weight 占满剩余宽，
            //   避免日后有人传入较长标题时把 Row 撑破（当前三处标题都很短，属预防性加固）。
            Row(
                modifier = modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 【v0.1.0·修·图标偏小】挨着 15sp 粗体标题却用 14dp 图标，视觉上是「标题旁边一颗小点」。
                //   按 [Design] 自己的口径，行首主图标是 SZ_SM(16)，SZ_XS 只留给备注/次级标记。
                Icon(iconRes = iconRes, sizeDp = Design.SZ_SM, color = c.sub)
                Spacer(Modifier.width(DhDesign.Space.iconGap.dp))
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                )
            }
        }
    }

    /* ============================ 顶栏 ============================ */

    /**
     * 顶栏「可读性带」的雾白不透明度（0~1）。
     *
     * 【88% 是怎么定的】按最坏情况（照片在标题后面是纯黑）反算：带底合成后 TITLE 7.6:1、
     *   subOnPhoto 4.95:1，两个都过 AA 且留有余量；再往上加，照片就越看不出是照片了。
     */
    private const val TOP_BAND_ALPHA = 0.88f

    /** 顶栏带底边渐隐的高度（dp）。让带子下沿与照片自然接上，不出现一条硬边。 */
    const val TOP_BAND_FADE_DP = 12

    /**
     * 当前是否有「用户背景图」。顶栏带的开关就是它。
     *
     * 【为什么要 remember 到 DhTheme.revision】`GlobalBackground.hasImage` 要读偏好 + 判断文件存在，
     *   每次重组都做是白白 I/O。挂版本号是因为换图 / 改参数那条路径会走 ThemeRefresh.bumpAll。
     * 【没图时为什么完全不加带】带子的填充色就是页面底色 [UiKit.BG]，
     *   在纯主题页面上「BG 叠 BG」等于 BG 本身，加不加逐像素一样 ——
     *   但那样会白白多出 12dp 底部留白，把没设背景的用户整体下移。故按有无图分流。
     *
     * 【为什么是 public】聊天页 [com.dollhouse.app.ui.chat.ChatUi] 的顶栏是手搓的第三套，
     *   不走 [TopBar] / [TabTopBar]；它同样压在背景图上，必须能拿到同一个判断，
     *   否则「标题能读了、聊天页的人偶名还是糊的」。
     */
    @Composable
    fun hasPhotoBackdrop(): Boolean {
        val ctx = LocalContext.current
        val rev = DhTheme.revision
        return remember(rev) { GlobalBackground.hasImage(ctx) }
    }

    /**
     * 顶栏「可读性带」的公开入口：给不走 [TopBar] / [TabTopBar] 的自绘顶栏用。
     *
     * @param onPhoto  传 [hasPhotoBackdrop] 的结果。
     * @param cornerDp 圆角。默认与卡片同一档；**贴屏幕边缘的满宽顶栏传 0**
     *                 （例如聊天页），否则四个圆角会漏出照片的角。
     */
    fun Modifier.photoTopBand(
        onPhoto: Boolean,
        cornerDp: Int = DhDesign.Radius.btn
    ): Modifier {
        return readableTopBand(onPhoto, cornerDp)
    }

    /**
     * 顶栏「可读性带」：有背景图时，在顶栏范围铺一层 88% 的雾白底，底边 12dp 渐隐。
     *
     * 【为什么文字必须自带底，而不是靠整页遮罩】整页那层遮罩的透明度由用户的「遮罩强度」
     *   滑条决定，可以拖到 0；顶栏标题（TITLE）压在一张深色插画上只有 1.03~1.9:1。
     *   文字能不能看清不该取决于「用户把背景图调多实」。所以这里给它一层不随用户设置变化的底。
     *
     * 【为什么是 88% 而不是不透明】保留一点照片透出来，带子读作「雾面玻璃」而不是「一条灰条」；
     *   88% 时底色的实际色相仍由主题决定，切浅色/深色主题自动跟随。
     *
     * 【为什么底边要渐隐】不带渐隐时，带子下沿是一条硬边，滚动内容从下面钻出来时很突兀。
     *   渐隐占的 12dp 由调用方补成底部留白，所以文字永远不会落在半透明的渐隐区里。
     */
    private fun Modifier.readableTopBand(onPhoto: Boolean, cornerDp: Int): Modifier {
        if (!onPhoto) {
            return this
        }
        val band = Color(UiKit.BG).copy(alpha = TOP_BAND_ALPHA)
        return this
            .clip(RoundedCornerShape(cornerDp.dp))
            .drawBehind {
                val fade = TOP_BAND_FADE_DP.dp.toPx().coerceAtMost(size.height)
                val solid = size.height - fade
                if (solid > 0f) {
                    drawRect(color = band, topLeft = Offset.Zero, size = Size(size.width, solid))
                }
                if (fade > 0f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(band, band.copy(alpha = 0f)),
                            startY = solid,
                            endY = size.height
                        ),
                        topLeft = Offset(0f, solid),
                        size = Size(size.width, fade)
                    )
                }
            }
    }

    /**
     * 正文区「可读性面板」：有背景图时，在整块列表 / 加载区下面铺一层雾面。
     *
     * 【和 [photoTopBand] 的分工】那个是**顶栏带**：实底在上、底边渐隐，让带子下沿
     *   与照片自然接上。本函数铺的是正文区，与照片相接的那条边变成**上沿**，
     *   所以渐隐在顶部 —— 内容从面板里滚出来时不会撞上一条硬边。
     *
     * 【为什么必须整块铺、不逐行加卡片】聊天列表与说说页的形态都是「连续的一条流」
     *   （行直接铺在页面底色上、行间用发丝线分隔）。逐行加底会把它变回
     *   「一堆互不相干的小卡片」，而那正是这些页面专门改掉的形态。
     *   整块铺 = 观感不变、可读性回来。
     *
     * 【为什么没图时一个像素都不改】无图时页面底色就是 BG，再叠一层雾面会把整块列表
     *   染色 —— 没设背景图的老用户会看到「页面变灰了」。所以 onPhoto=false 时原样返回 this：
     *   零绘制、零布局变化。
     *
     * 【参数为什么是这些】
     *   · [onPhoto]  传 [hasPhotoBackdrop] 的结果。别在这里自己读偏好：那一层已经按
     *     `DhTheme.revision` 做了 remember 缓存，换图 / 改参数才会重算。
     *   · [fill]     面板底色由调用方给：顶栏带用 BG；正文区用调色板 SOFT（比 BG 柔一档）。
     *   · [cornerDp] **只圆上面两角**。面板一般都铺到屏幕底部、被悬浮底栏盖住，
     *     底角看不见；圆了反而会在左右下缘漏出照片的三角。
     *   · [fadeDp] / [alpha] 默认与顶栏带同款（12dp / 88%），保证两块雾面的
     *     浓淡与渐隐长度一致，滚动时看不出第二条分界线。
     *
     * 【调用方必须做的一件事】渐隐那 [fadeDp] 要自己补进 `contentPadding`（通常是 top），
     *   否则首行文字会落在半透明区里 —— 与顶栏带「留白由调用方补」是同一条约定。
     */
    fun Modifier.readableSheet(
        onPhoto: Boolean,
        fill: Color,
        cornerDp: Int,
        fadeDp: Int = TOP_BAND_FADE_DP,
        alpha: Float = TOP_BAND_ALPHA
    ): Modifier {
        if (!onPhoto) {
            return this
        }
        val shape = RoundedCornerShape(topStart = cornerDp.dp, topEnd = cornerDp.dp)
        val solid = fill.copy(alpha = alpha)
        return this.clip(shape).drawBehind {
            val fade = fadeDp.dp.toPx().coerceAtMost(size.height)
            if (fade > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(solid.copy(alpha = 0f), solid),
                        startY = 0f,
                        endY = fade
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, fade)
                )
            }
            if (size.height > fade) {
                drawRect(
                    color = solid,
                    topLeft = Offset(0f, fade),
                    size = Size(size.width, size.height - fade)
                )
            }
        }
    }

    /**
     * 统一顶栏：可选返回键 + 主标题 + 可选副标题。
     * 【为什么不用 Material3 TopAppBar】它的高度 / 内边距 / 字号与既有 `UiKit.topBar` 对不上，
     *   混用会让「Compose 页的顶栏比 View 页高一点」，正是规格书禁止的突兀。
     */
    @Composable
    fun TopBar(
        title: String,
        sub: String? = null,
        onBack: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        actions: (@Composable RowScope.() -> Unit)? = null
    ) {
        // 【r21·顶部留白】改造前只有 `padding(top = 12.dp)`。本工程是 edge-to-edge +
        //   状态栏常隐（见 `ImmersiveStatus.hideStatusBars`），二级页的返回键与标题
        //   会直接贴到屏幕最上沿 —— 用户报「这上面的贴得太近了，预留一点距离」。
        //   【为什么取 max 而不是直接 statusBarsPadding】状态栏在这套 UI 里是**隐藏**的，
        //   它的 inset 恒为 0，单用它等于什么都没加；而写死一个大 dp 又会在
        //   「系统临时唤出状态栏 / 三键导航」的机型上叠成双倍留白。
        //   取二者较大值：隐藏时给固定呼吸，唤出时自动让开，两种情况都正确。
        val sbTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topGap = if (sbTop > TOP_MIN_GAP.dp) sbTop else TOP_MIN_GAP.dp
        // 【可读性带】有背景图时给顶栏铺一层雾白底：标题的可读性不再由「遮罩强度」滑条决定。
        val onPhoto = hasPhotoBackdrop()
        Row(
            modifier = modifier
                .fillMaxWidth()
                .photoTopBand(onPhoto)
                // 有带子时底部多留出渐隐那 12dp：保证文字不会落在半透明的渐隐区里。
                .padding(top = topGap, bottom = if (onPhoto) TOP_BAND_FADE_DP.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                // 【无障碍·一处覆盖全 App】这是**所有二级页**的返回键（[Page] 走这里），
                //   `IconButton` 内部只有一枚箭头图标、没有任何文字 —— 不给名字，
                //   读屏在每一个二级页上念的都是「按钮」，用户无法判断它是返回。
                //   名字只写「返回」：位置固定在最左，读屏用户靠位置与动作即可理解语义；
                //   写「返回上一页」会让本已冗长的顶栏在朗读里再多三个字。
                IconButton(
                    iconRes = Icons.IC_ARROW_LEFT,
                    sizeDp = 20f,
                    color = DhTokens.colors.title,
                    a11yName = "返回",
                    onClick = onBack
                )
                Spacer(Modifier.width(DhDesign.Space.md.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = DhTokens.colors.title,
                    fontSize = UiKit.FS_TITLE.sp,
                    fontFamily = DhTokens.fontsBold,
                )
                if (!sub.isNullOrEmpty()) {
                    Text(
                        text = sub,
                        modifier = Modifier.padding(top = 2.dp),
                        // 【压在图上要换一档更深的色】SUB 在页面底色上只有 4.66:1（AA 要 4.5），
                        //  照片一旦渗进带子就跌破线；subOnPhoto 是「夹到 6.5」的那一档。
                        color = if (onPhoto) Color(UiKit.subOnPhoto()) else DhTokens.colors.sub,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
            if (actions != null) {
                Spacer(Modifier.width(8.dp))
                actions()
            }
        }
    }

    /**
     * 【统一·Tab 页顶栏】三个底部 Tab（聊天 / 人偶 / 说说）共用的顶栏。
     *
     * 【为什么单独开一个而不是复用 [TopBar]】两者职责不同：
     *   · [TopBar] 是**二级页**的顶栏 —— 左返回键 + 20sp 标题 + 可选副标题；
     *   · Tab 页没有返回键，标题是 26sp 大标题（与 QQ / 微信一致），且左右都可能有入口。
     *   改造前三个 Tab 各自手搓 Row：聊天与人偶是 26sp、说说走 [TopBar] 的 20sp，
     *   top padding 也分别是 14 / 14 / 12 —— 切换 Tab 时标题会「跳一下」。
     *   收口到这里后，三格的标题字号、内边距、右端对齐由**同一段代码**决定。
     *
     * @param left    左侧插槽（聊天页的头像入口）；null = 不留位
     * @param title   大标题
     * @param actions 右侧插槽（图标按钮组）；null = 不留位
     */
    @Composable
    fun TabTopBar(
        title: String,
        modifier: Modifier = Modifier,
        left: (@Composable () -> Unit)? = null,
        actions: (@Composable RowScope.() -> Unit)? = null
    ) {
        // 【可读性带】与 [TopBar] 同一套：有背景图时铺一层雾白底，标题不再「压在图案上」。
        //   横向先内缩再画带，色带与页面左右边距、底部胶囊对齐（都是 pageX=16dp）。
        val onPhoto = hasPhotoBackdrop()
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = PAD_PAGE.dp)
                .photoTopBand(onPhoto)
                // 有带子时把渐隐那 12dp 补进底部留白，标题不会落在半透明的渐隐区里。
                .padding(
                    top = TAB_BAR_TOP.dp,
                    bottom = if (onPhoto) (TAB_BAR_BOTTOM + TOP_BAND_FADE_DP).dp else TAB_BAR_BOTTOM.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (left != null) {
                left()
                Spacer(Modifier.width(DhDesign.Space.md.dp))
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = DhTokens.colors.title,
                fontSize = TAB_TITLE_SP.sp,
                fontFamily = DhTokens.fontsBold,
            )
            if (actions != null) {
                actions()
            }
        }
    }

    /* ============================ 行 ============================ */

    /**
     * 值行：左标签 + 右状态/值，SOFT 底 + 圆角 10。
     * 视觉与 `HomeCards.valueRow` 一致；点击反馈由 [pressable] 统一提供。
     *
     * @param value    右侧文字（null 或空串则不占位）
     * @param valueTint 右侧文字色；默认 SUB
     */
    @Composable
    fun ValueRow(
        label: String,
        value: String? = null,
        valueTint: Color? = null,
        modifier: Modifier = Modifier,
        iconRes: Int = 0,
        desc: String? = null,
        /** 【v3.1】画真箭头，替代用字符 `›` 冒充图标的写法。 */
        showChevron: Boolean = false,
        onClick: (() -> Unit)? = null
    ) {
        // 【M-收口·转发到规范实现】本函数有 38 处调用点，改造它等于一次性让
        //   38 个页面的"信息行"对齐设计规范（圆角/行高/字号/图标尺寸/可点反馈）。
        //   【为什么用转发而不是复制样式】`ui/kit.DhRow` 是规范源，复制一份就又回到
        //   "同一语义两处实现、改一处漏一处"的老问题。这里只做参数适配：
        //     · valueTint：DhRow 的右值固定用 sub 色；需要强调色的调用点（状态色）
        //       保留旧路径，避免语义丢失
        //     · 旧调用不带箭头（showChevron=false），保持原观感
        if (valueTint != null) {
            // 需要自定义右值颜色 → 走旧实现（少量调用点，观感不变）
            LegacyValueRow(label, value, valueTint, modifier, iconRes, desc, onClick)
            return
        }
        com.dollhouse.app.ui.kit.DhRow(
            title = label,
            modifier = modifier,
            desc = desc,
            value = value,
            iconRes = iconRes,
            showChevron = showChevron,
            onClick = onClick
        )
    }

    /** 旧版 ValueRow 实现：仅保留给需要自定义右值颜色的少数调用点。 */
    @Composable
    private fun LegacyValueRow(
        label: String,
        value: String?,
        valueTint: Color?,
        modifier: Modifier,
        iconRes: Int,
        desc: String?,
        onClick: (() -> Unit)?
    ) {
        val c = DhTokens.colors
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
                .padding(
                    horizontal = PAD_ROW.dp,
                    vertical = if (desc.isNullOrEmpty()) PAD_ROW.dp else DhDesign.Space.sm.dp
                )
                .defaultMinSize(minHeight = MIN_ROW_H.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconRes != 0) {
                RowIcon(iconRes)
                Spacer(Modifier.width(GAP_ICON.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
                if (!desc.isNullOrEmpty()) {
                    Text(
                        text = desc,
                        modifier = Modifier.padding(top = 2.dp),
                        color = c.sub,
                        fontSize = FS_ROW_DESC.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
            if (!value.isNullOrEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = value,
                    modifier = Modifier.weight(1.35f),
                    color = valueTint ?: c.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    textAlign = TextAlign.End
                )
            }
        }
    }

    /**
     * 【v0.1.4·UI 统一】行首图标的唯一画法。
     *
     * 【为什么要收口】改造前 ValueRow 用 SZ_SM、ActionRow 用 SZ_MD、SectionLabel 用 SZ_XS，
     *   同一个列表里三种图标大小并排，视觉上像三个不同层级的控件。
     *   现在统一 18dp + SUB 色，与 15sp 正文的视觉重量匹配（既不像小点、也不压过文字）。
     */
    @Composable
    private fun RowIcon(iconRes: Int, tint: Color = DhTokens.colors.sub) {
        /*
         * 【v3·清透轻拟物·行首图标配浅色圆底】
         *
         * 用户定案「图标统一使用精致的线性图标，合理搭配浅色圆形背景」。
         * 改造前这里只有一个**裸图标**（20dp、无底），与页面上其它"图标 + 浅圆"不是一套语言。
         *
         * 【为什么圆底是 28dp 而不是 IconSeatSize.SM(32)】
         *   本函数的图标被 **169 处转发行**（ActionRow 87 / ValueRow 38 / SwitchRow 34 /
         *   PermRow 4 / StepperRow 5，外加 Card 内的行）共用，而这些行的高是
         *   `DhDesign.Size.rowOneLine = 56`、两行 64，且行内已经带了垂直内边距
         *   （单行 PAD_ROW=12 上下各 12、两行 Space.sm=8 上下各 8）。
         *   实测预算：
         *     单行 56 - 12×2 = **32**（留给图标的最大高度）
         *     两行 64 -  8×2 = **48**
         *   所以：
         *     · 32dp 圆底 = 56 - 24，**正好顶满单行**，任何一处行内 padding 调整（哪怕 +1dp）
         *       都会把行撑高、同页行距突然不一致 —— 这是 169 处共用的地雷，不能踩。
         *     · **28dp 圆底 + 12×2 = 52 < 56**，留 4dp 余量；两行形态更宽松。
         *   故取 28（比图标 20 大 8，视觉上是"图标四周留了一圈浅底"，正是要的效果）。
         *
         * 【图标本体仍是 20dp】`ICON_ROW_DP` 不变 —— 圆底放大的是"座"，不是图标，
         *   所以行的信息密度与改造前完全一致。
         *
         * 【浅底怎么做】直接复用 IconSeat 的圆形配方（圆形 / cardUp 比卡片亮一档 /
         *   e1 柔和阴影 / 顶部内高光 / 深色自适应），保证与空态、AboutPage、模型面板
         *   的图标底是**同一个配方**，不在这里再维护第二份。
         */
        IconSeat(
            iconRes = iconRes,
            sizeDp = ROW_ICON_SEAT_DP,
            shape = IconSeatShape.CIRCLE,
            iconSizeDp = ICON_ROW_DP.toInt(),
            tint = tint
        )
    }

    /**
     * 卡片内行与行之间的发丝线（1dp）。
     *
     * 【何时用】同一张卡里有多行时，行之间放一条。**不需要**每行自带：由调用方在
     *   `Column { 行A(); RowDivider(); 行B() }` 里显式摆放，这样最后一行下面不会多一条。
     *   【为什么不做成"每行自动带下边框"】Compose 的行是独立 Composable，无法知道
     *   自己是不是最后一个，自动加会在列表末尾留下一条悬空的线。
     *
     * @param insetStart 左侧缩进（dp）：有行首图标时传「图标宽 + 间距」，让线从文字开始处起。
     */
    @Composable
    fun RowDivider(insetStart: Int = 0, modifier: Modifier = Modifier) {
        val c = DhTokens.colors
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = insetStart.dp)
                .height(1.dp)
                .background(c.line)
        )
    }
    /* ============================ 开关行 / 权限行 / 步进器 ============================
     * 【转发链现状：6 条，全部由本类转发出去】
     *   Card → DhGroup、ValueRow → DhRow、ActionRow → DhRow、
     *   SwitchRow → DhRow、PermRow → DhRow、StepperRow → DhRow。
     *
     *   【记一段纠错】此前有一个说法是「SwitchRow / PermRow 各转发 2 次
     *   （注释里的路径算一次、真调用算一次）」—— 那是把注释里提到的路径
     *   `ui/kit.DhRow` 当成了调用点。**实测两个都是 0 次**：转发前这两个函数体里
     *   没有任何 `ui/kit.` 调用，行、标题、开关 / 状态图标全是自己画的。
     *   这就是「同一张卡里开关行比信息行矮 2~3dp」那条观感问题的来源。
     *   本轮已把两者真正转发，38 处调用点（SwitchRow 34 + PermRow 4）一次性对齐。
     *
     *   【[StepperRow] 也曾被我判断成"转发不了"】理由是「右尾是减号 / 数值 / 加号
     *   三件套 + 两个方向各自的回调，与 DhRow 单个 onClick + 单个 trailing 的语义对不上」。
     *   这个前提是**错的**：`DhRow.trailing` 的形参类型本来就是
     *   `@Composable RowScope.() -> Unit`，接收器是 RowScope，天然可放任意多件。
     *   （PermRow 早把「状态文字 + 图标」两件放进同一槽，就是先例。）
     *   所以三件套原样进槽即可，**不需要给 DhRow 加任何参数** ——
     *   加了反而给 6 个转发方多一个可被误用的开关。
     *   教训：判断"某组件表达不了"之前，先复核它的形参类型，别凭印象断言。
     */

    /**
     * 开关行：左名称 + 右开关 ← `HomeCards.switchRow`。
     * 点整行与点开关等价（与 View 版一致）。
     *
     * 【M-收口·转发到规范实现】本函数有 34 处调用点，内部已改为调用 `ui/kit` 的 `DhRow`，
     *   `Switch` 放进它的 `trailing` 插槽。
     *   【为什么必须转发】转发前这里自带 `padding(horizontal = PAD_ROW, vertical = 10)`，
     *   而 ValueRow / ActionRow 早已转发、行高与内边距由 `DhRow` 决定 ——
     *   同一张卡里开关行比相邻的信息行矮 2~3dp，看上去"缩了一号"。
     *   转发后 38 处（34 + 4）行与其余行同口径，这条差异从结构上消失。
     *   【两处随之对齐的观感差异，按规范取值、不再手写数字】
     *     · 行首图标与标题的间距：旧值 GAP_ICON(8) → `DhRow` 的 Space.md(12)；
     *     · 行左右内边距：旧值 PAD_ROW(12) → `DhRow` 自己不加横向内边距，由所在卡片给
     *       Space.lg(16)，与已转发的 ValueRow / ActionRow 同口径。
     */
    @Composable
    fun SwitchRow(
        name: String,
        checked: Boolean,
        modifier: Modifier = Modifier,
        /** 【v0.1.4】第二行说明；null / 空 = 单行。 */
        desc: String? = null,
        /** 【v0.1.4】行首图标（Icons.IC_*）；0 = 不占位。 */
        iconRes: Int = 0,
        /** 【v0.1.4】false 时整行降透明度且不接收点击（与 ActionRow 的禁用口径一致）。 */
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit
    ) {
        // 【参数适配】name→title、desc→desc、iconRes→iconRes（旧实现的 0 与 `DhRow` 的
        //   "不占位"是同一个约定，不需要归一）。
        //
        // 【enabled = false 如何原样保住】旧实现是
        //   `if (enabled) pressable { toggle } else Modifier.alpha(0.35f)`，
        //   即「降透明度 + 修饰符层面压根没有点击」。`DhRow` 把 modifier 应用在 pressable
        //   **之前**，所以这里能完全等价地表达：
        //     · onClick 传 null → `DhRow` 内部不挂 pressable（不是挂了再吞，而是根本不存在）；
        //     · modifier 叠 alpha(0.35f) → 与旧值同值。
        //
        // 【整行点按 = 切开关】onClick 里传 `!checked`，取反语义与旧实现逐字一致。
        //
        // 【点开关不会"切两次"】`Switch` 自带 clickable，会消费掉按下事件，
        //   外层行的 pressable 收不到这次点击 —— 旧实现同样是"外层可点 + 内层可点"两层结构，
        //   这里只是把外层换成了 `DhRow` 的 pressable，防双触发机制不变。
        //   内层仍保留 `if (enabled)` 判断：禁用时开关点击也必须无效（旧实现就是这样）。
        val rowModifier = if (enabled) modifier else modifier.alpha(0.35f)
        // 先把取反动作存成有类型的局部值，再按 enabled 挑 null —— 不用
        // `if (…) { { … } }` 这种"块里套 lambda"的写法，避免可空函数类型的推断歧义。
        val toggle: () -> Unit = { onCheckedChange(!checked) }
        val onRowClick: (() -> Unit)? = if (enabled) toggle else null
        com.dollhouse.app.ui.kit.DhRow(
            title = name,
            modifier = rowModifier,
            desc = desc,
            iconRes = iconRes,
            showChevron = false,
            onClick = onRowClick,
            trailing = {
                Switch(
                    checked = checked,
                    onCheckedChange = { if (enabled) onCheckedChange(it) }
                )
            }
        )
    }

    /**
     * 权限行：左名称 + 右状态（带勾 / 叉 / 警告图标 + 语义色）← `HomeCards.permRow`。
     * 已授权时整行不可点。
     *
     * 【M-收口·转发到规范实现】本函数有 4 处调用点，内部已改为调用 `ui/kit` 的 `DhRow`，
     *   状态文字 + 状态图标一起放进它的 `trailing` 插槽。
     *   【为什么必须转发】转发前这里自带 `padding(horizontal = PAD_ROW, vertical = …)`，
     *   与已转发的 ValueRow / ActionRow 在行高与内边距上不是同一个来源 ——
     *   同一张卡里（PermissionPage 就是混排的）权限行会比相邻行矮一点。
     *
     * @param state  0=已授权（绿勾）1=未授权（红叉）2=待处理（红警告）
     */
    @Composable
    fun PermRow(
        name: String,
        state: Int,
        modifier: Modifier = Modifier,
        /** 【v0.1.4】行首图标（Icons.IC_*）；0 = 不占位。 */
        iconRes: Int = 0,
        /** 【v0.1.4】第二行说明（例如「申请后会跳到系统设置」）。 */
        desc: String? = null,
        onClick: () -> Unit
    ) {
        val c = DhTokens.colors
        val granted = state == 0
        val tint = if (granted) c.ok else c.err
        val icon = when (state) {
            0 -> Icons.IC_CHECK_CIRCLE
            2 -> Icons.IC_WARNING
            else -> Icons.IC_X_CIRCLE
        }
        // 【"已授权不可点"如何原样保住】旧实现是
        //   `if (granted) Modifier else Modifier.pressable(onClick)` —— 已授权时压根不挂点击。
        //   `DhRow` 的 onClick 传 null 就是"不挂 pressable"（不是挂了再吞掉），
        //   所以 onClick = if (granted) null else onClick 与旧语义逐字等价。
        //   注意这里只降"可点性"，**不降透明度**：旧实现也没有 alpha，
        //   已授权的行仍要能正常读出"已授权"三个字。
        com.dollhouse.app.ui.kit.DhRow(
            title = name,
            modifier = modifier,
            desc = desc,
            iconRes = iconRes,
            showChevron = false,
            onClick = if (granted) null else onClick,
            trailing = {
                // 状态文字：旧实现的字号 / 字重 / 颜色 / 单行截断全部保留在这里，
                // 不借用 DhRow 的 value 参数 —— value 固定用 sub 色，会丢掉语义色。
                Text(
                    text = if (granted) "已授权" else if (state == 2) "待处理" else "未授权",
                    color = tint,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    maxLines = 1
                )
                Spacer(Modifier.width(4.dp))
                // 【r12】13f 不在 [Design] 的图标五档内 → 归到 SZ_XS(14)。
                Icon(iconRes = icon, sizeDp = Design.SZ_XS, color = tint)
            }
        )
    }

    /**
     * 步进器行：左名称 + 减号 / 数值 / 加号 ← `HomeCards.stepperRow`。
     * 到达两端时对应按钮降透明度且不可点（与 View 版 `syncMem` 的 0.35f 一致）。
     */
    @Composable
    fun StepperRow(
        name: String,
        value: String,
        canMinus: Boolean,
        canPlus: Boolean,
        modifier: Modifier = Modifier,
        onMinus: () -> Unit,
        onPlus: () -> Unit
    ) {
        val c = DhTokens.colors
        // 【M-收口·转发到规范实现·结论是"不必给 DhRow 加参数"】
        //   本函数此前是**最后一行自带 soft 底 + 圆角 12 的行**（其余行早已转发走）。
        //   它被塞在已转发的 SwitchRow / ActionRow / 普通行之间，同页里只有它长成一个小卡片，
        //   行高与内边距也不是同一个来源 —— 这正是设计稿要收敛掉的"逐行卡片"。
        //
        //   【曾评估的 A 方案：给 DhRow 加 weightedTrailing / trailingSlot 参数】
        //   **实测不需要**：`DhRow` 的 `trailing` 形参类型本来就是
        //   `@Composable RowScope.() -> Unit` —— 接收器是 `RowScope`，
        //   天然可以放任意多件、并按 RowScope 的规则排版（含 Modifier.width 定宽居中）。
        //   上一轮的 PermRow 已经把「状态文字 + 图标」两件放进同一个槽，即是先例。
        //   所以这里**没有改动 Components.kt 的任何一行** ——
        //   加参数反而会给 6 个转发方（Card→DhGroup、ValueRow、ActionRow、SwitchRow、
        //   PermRow、本函数）多一个可被误用的开关，风险大于收益。
        //
        //   【右尾三件套怎么放】把「减号 / 数值 / 加号」原样放进 trailing 槽：
        //   两枚 StepButton 各有自己的回调与可用性，数值保持 62dp 定宽居中（避免
        //   数字位数变化时按钮跳动）。这不是"假装成一件东西"，而是槽本来就是多件的。
        //
        //   【行为原样保住】旧实现的**整行不可点**（只有两个 StepButton 可点），
        //   故这里 onClick 传 null —— DhRow 因此不挂 pressable，可点面只剩两枚按钮。
        //   StepButton 自己的 enabled / alpha(0.35f) / 34dp 圆形实底描边全部不动。
        com.dollhouse.app.ui.kit.DhRow(
            title = name,
            modifier = modifier,
            showChevron = false,
            onClick = null,
            trailing = {
                StepButton(iconRes = Icons.IC_MINUS, enabled = canMinus, onClick = onMinus)
                Text(
                    text = value,
                    modifier = Modifier.width(62.dp),
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    textAlign = TextAlign.Center
                )
                StepButton(iconRes = Icons.IC_PLUS, enabled = canPlus, onClick = onPlus)
            }
        )
    }

    /**
     * 步进器的圆形按钮：直径 34dp，实底描边 + 描边图标 ← `HomeCards.stepButton`。
     *
     * 【r13】底色由 `card` 换成 `cardUp`：它嵌在卡片里，卡片半透明时同色就看不见形状了。
     */
    @Composable
    private fun StepButton(iconRes: Int, enabled: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.cardUp)
                .border(1.dp, c.line, RoundedCornerShape(999.dp))
                .then(if (enabled) Modifier.pressable(onClick) else Modifier)
                .then(if (enabled) Modifier else Modifier.alpha(0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(iconRes = iconRes, sizeDp = 16f, color = c.title)
        }
    }

    /** 分组小标题（比正文小一号、SUB 色，用于卡片内分区）。 */
    @Composable
    fun SectionLabel(
        text: String,
        modifier: Modifier = Modifier,
        // 【r16·图标】可选前缀图标（Icons.IC_*）。默认 0 = 与改造前逐像素一致。
        iconRes: Int = 0
    ) {
        val c = DhTokens.colors
        if (iconRes == 0) {
            Text(
                text = text,
                modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
                color = c.sub,
                // 【M3】分组小标题走 label 档（11sp/600 + 0.44 字距）。
                //   改造前用 FS_SUB（13sp 常规字重），与行内说明文字同字号同字重 ——
                //   「分组名」和「说明文字」长得一样，用户分不清哪一行是标题。
                fontSize = DhTokens.type.label.fontSize,
                lineHeight = 16.sp,
                fontFamily = DhTokens.fonts,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.44.sp
            )
        } else {
            // 有图标时「图标 + 文字」横排；图标用 SUB 色与文字同色，视觉是一个整体。
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 【r16·修·防溢出】图标必须 shrink 不出去，文字拿 weight 占满剩余宽并允许换行。
                //   不加 weight 时，长标签（如「常用端点（点一下填进上面的 API Base Url）」）
                //   会把 Row 撑破 —— Compose 的 Row 默认不裁剪子项，文字会画到卡片外面去。
                Icon(iconRes = iconRes, sizeDp = Design.SZ_XS, color = c.sub)
                Spacer(Modifier.width(GAP_ICON.dp))
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    color = c.sub,
                    fontSize = DhTokens.type.label.fontSize,
                    lineHeight = 16.sp,
                    fontFamily = DhTokens.fonts,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.44.sp
                )
            }
        }
    }

    /* ============================ 滑条（r14 新增公共构件） ============================ */

    /**
     * 「图标 + 标题 + 滑条 + 提示」标准块。
     *
     * 【为什么提到公共层】r14 之前，设置页里四个滑条各自把「标题 Text / 间距 / 提示 Text」
     *   抄了一遍 —— 抄到第四个时行距已经和第一个不一致（12 / 10 / 4 混用）。
     *   现在外观页（4 条）与人偶外观栏（3 条）都用本构件，视觉天然同族。
     *
     * 【为什么桥接 View 版滑条】`UiKit.Slider` 是胶囊轨道 + 刻度点 + 终点标记的自绘 View，
     *   Compose 的 `Slider` 做不出同一形态；「全局 UI 一致」是硬要求，
     *   换一个长得不一样的滑条正是要避免的突兀。故保留原控件、原位桥接。
     *
     * 【为什么不缓存 AndroidView】`factory` 只在首次组合时跑一次，之后 Compose 不会
     *   重新调用它 —— 所以 [value] 变化**不会**自动反映到滑条上。
     *   调用方若需要「外部改动后滑条跟着走」，必须换 key 强制重建（见 `remember(value)` 用法）。
     *   本构件按「滑条是用户输入源、不是显示控件」的口径设计：只读初值，之后由用户拖动驱动。
     *
     * @param iconRes 前置小图标（无合适图标时传 0）
     */
    @Composable
    fun SliderRow(
        title: String,
        hint: String,
        max: Int,
        value: Int,
        iconRes: Int = 0,
        modifier: Modifier = Modifier,
        onChange: (Int, Boolean) -> Unit
    ) {
        val c = DhTokens.colors
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconRes != 0) {
                    Icon(iconRes = iconRes, sizeDp = Design.SZ_XS, color = c.sub)
                    Spacer(Modifier.width(DhDesign.Space.iconGap.dp))
                }
                Text(
                    text = title,
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            Spacer(Modifier.height(4.dp))
            androidx.compose.ui.viewinterop.AndroidView(
                factory = {
                    UiKit.Slider(it).apply {
                        setMax(max)
                        setProgress(value)
                        setOnChange(object : UiKit.Slider.OnChange {
                            override fun onChanged(v: Int, fromUser: Boolean) {
                                onChange(v, fromUser)
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = hint,
                modifier = Modifier.padding(top = 4.dp),
                color = c.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /**
     * 【r14】「模型 / 信息行」：左枚图标 + 标题 + 右侧说明文字（不可点）。
     * 【用途】外观页里「当前主题跟随 xxx」这类只读提示行，与 [ValueRow] 的区别是带图标。
     */
    @Composable
    fun InfoRow(iconRes: Int, text: String, modifier: Modifier = Modifier) {
        val c = DhTokens.colors
        Row(
            modifier = modifier.fillMaxWidth().padding(top = DhDesign.Space.md.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(iconRes = iconRes, sizeDp = Design.SZ_XS, color = c.line)
            Spacer(Modifier.width(DhDesign.Space.iconGap.dp))
            Text(
                text = text,
                color = c.line,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /** 分隔线（1px LINE）← AboutPage.divider。 */
    @Composable
    fun Divider(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DhTokens.colors.line)
        )
    }

    /* ============================ 图标 ============================ */

    /**
     * 图标（自动按当前主题上色）。
     * 【为什么用 painterResource 而不是 material-icons】规格书要求沿用既有 Lucide 图标体系，
     *   引 material-icons 会混入风格不一致的图标（填充式 vs 描边式）。
     *
     * 【v0.2.0·Material 3 无障碍】新增 [contentDescription]。
     *   【问题】原实现写死 `contentDescription = null`，TalkBack 读不到任何图标。
     *     对纯装饰图标（排版点缀、行首点缀）传 null 是**正确**的 —— 强行给非空值
     *     只会让读屏用户听到一串无意义朗读。但承载信息语义的图标（「已启用」「出错」）
     *     传 null 就等于这条信息对读屏用户直接消失。
     *   【所以】保持默认 null，由调用点按「这个图标是不是信息载体」自行决定。
     *
     * @param sizeDp 只允许 [com.dollhouse.app.ui.theme.Design.SZ_*] 五档
     * @param contentDescription 读屏朗读文本；null = 纯装饰，不朗读
     */
    @Composable
    fun Icon(
        iconRes: Int,
        sizeDp: Float,
        color: Color,
        modifier: Modifier = Modifier,
        contentDescription: String? = null
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = modifier.size(sizeDp.dp),
            colorFilter = ColorFilter.tint(color)
        )
    }

    /**
     * 可点击图标（命中区固定 [HIT]dp，与 `UiKit.iconView` 一致）。
     *
     * 【r15·图标+文字】新增可选 [label]：位置够宽时传入文字，按钮变成「图标 + 文字」横排。
     *   默认 null = 纯图标，与改造前逐像素一致（现有 45 处调用零影响）。
     *
     * 【为什么不做成自动判断】Compose 里拿不到「位置够不够」这种布局后信息
     *   （测量发生在组合之后，且会触发重组）。所以由调用点按页面已知的信息决定 ——
     *   顶栏 / 工具条这类宽裕处显式传 label，卡片内三等分这类窄处保持纯图标。
     *
     * @param label 可选文字；null / 空 = 纯图标
     *
     * 【v0.2.0·Material 3 无障碍：给可点图标补语义】
     *   【问题】纯图标按钮（无 [label]）此前对 TalkBack 是「无名称按钮」——
     *     读屏只会念「按钮」，用户完全不知道点下去会发生什么。[tooltip] 是视觉提示，
     *     不是无障碍名称（它只在长按/悬停时出现，读屏读不到）。
     *   【修法】把 [tooltip] 与 [label] 一起作为无障碍名称的唯一来源：
     *     · 有 label → 用 label（界面上已经写着，读屏念一样的字）
     *     · 只有 tooltip → 用 tooltip（这正是「这个按钮干什么」的一句话说明）
     *     · 都没有 → 空串，保持原状（这类调用点通常是装饰性间距，不该被朗读）
     *   【为什么用 semantics 而不是再开一个参数】名称的语义位置是固定的，
     *     再开参数会让 45 个既有调用点逐个判断该传哪个 —— 反而更容易漏。
     */
    @Composable
    fun IconButton(
        iconRes: Int,
        sizeDp: Float,
        color: Color,
        modifier: Modifier = Modifier,
        label: String? = null,
        tooltip: String? = null,
        /**
         * 无障碍名：**只挂语义，不渲染任何东西**。
         *
         * 【为什么必须有这个参数】本函数原先只有两条能产生名字的路子，而它们都带副作用：
         *   · `label`  → 会**渲染可见文字**，紧凑排布放不下（4dp 间距的四方向键、36dp 窄槽）
         *   · `tooltip`→ 会被 `HintTooltip` 包一层，**长按弹提示**，是交互行为
         *   于是「给按钮起个名字」这件事在没有副作用的情况下**无写法可写** ——
         *   这不是「忘了写」，是能力缺口。补上本参数即为根因修复。
         *
         * 【优先级】`a11yName` > `label` > `tooltip`：
         *   显式指定的名字最可信；其次才是那个恰好也是可见文字 / 提示的字符串。
         *
         * 【不影响既有行为】`label` 的渲染逻辑一行未动：传了 label 仍会画文字，
         *   只是此时若同时传 a11yName，读屏念 a11yName 而不是 label。
         */
        a11yName: String? = null,
        onClick: () -> Unit
    ) {
        val hasLabel = !label.isNullOrEmpty()
        // 图标座位的高光强度：深色下明暗差小，需要更强的顶部锚点（与按钮同一套取值）。
        val iconSeatLight = if (ThemeManager.isDark(LocalContext.current)) 0.16f else 0.10f
        // 本函数没有 `c` 局部变量（参数叫 `color`），故显式取调色板。
        val seat = DhTokens.colors.soft
        // 无障碍名称：显式 a11yName 优先，其次 label（可见文字），最后 tooltip（长按提示）；
        //   三者都没有则为空 = 不挂语义（与改造前一致）。
        val a11yText = if (!a11yName.isNullOrEmpty()) {
            a11yName!!
        } else if (hasLabel) {
            label!!
        } else {
            tooltip ?: ""
        }
        val button: @Composable () -> Unit = {
        Row(
            modifier = modifier
                // **【v0.1.5·K-03】命中区补到 48dp（视觉尺寸不变）。**
                //   【顺序很关键】必须先 minTouchTarget 再 size：
                //   `size` 是**强制**尺寸（会覆盖外层约束），如果先 size(40) 再 sizeIn(48)，
                //   内层 40dp 会把外层 48dp 的可用空间占满而图标仍居中 —— 但点击区仍是 40。
                //   反过来（sizeIn 在前、size 在后）size 定死 40，外层 minTouchTarget 的
                //   48dp 下限依然生效，于是 40dp 的图标被包在 48dp 的透明可点区域里。
                .minTouchTarget()
                .then(
                    if (hasLabel) Modifier.height(HIT.dp)
                    else Modifier.size(HIT.dp)
                )
                .clip(RoundedCornerShape(999.dp))
                // 【v3·清透轻拟物】图标按钮加"圆形浅底 + 柔和体积"。
                //   【为什么现在加底】旧形态是**完全透明**的命中区 —— 在纯色顶栏上还行，
                //   但在新方向的大圆角卡片 + 柔和阴影语境里，图标会"浮在空处"、没有归属。
                //   加一层比背景略亮的圆底，图标就有了"坐在一块浅色圆片上"的物理感。
                //   【颜色为什么用 soft】它本就是"比 bg 略亮一档"的既有语义槽；
                //     深色下它同样比 bg 亮（调色板里 soft 已按深浅色分别定义），无需分支。
                //   【不加 elevation 阴影】遵守「芯片/图标座不加阴影」的硬规则，体积靠渐变。
                .background(
                    Brush.verticalGradient(
                        listOf(
                            lighten(seat, iconSeatLight),
                            seat,
                            darken(seat, iconSeatLight * 0.8f)
                        )
                    )
                )
                // 【v0.2.0·Material 3 无障碍】把名称挂到节点上，读屏才会念出
                // 「分享」「返回」这类动词，而不是干巴巴一句「按钮」。
                // 放在 pressable 之前：语义描述的是「这个可点区域是什么」，
                // 与它内部如何处理手势无关。
                .then(
                    if (a11yText.isEmpty()) Modifier
                    else Modifier.semantics { contentDescription = a11yText }
                )
                .pressable(onClick)
                .then(if (hasLabel) Modifier.padding(horizontal = 8.dp) else Modifier),
            // 【修·图标不在圆心】改造前只有 `verticalAlignment`，而 `Row` 的
            //   `horizontalArrangement` 默认是 **Start** —— 于是 40dp 圆底里 20dp 的图标
            //   贴在左侧，右侧空出 20dp。用户看到的就是「返回键不在最中心」。
            //   `Row` 的默认值在「一行几个元素」时没问题，但这里容器是固定尺寸的圆、
            //   内容只有一枚图标，**必须显式居中**。
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(iconRes = iconRes, sizeDp = sizeDp, color = color)
            if (hasLabel) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = label!!,
                    color = color,
                    fontSize = UiKit.FS_CHIP.sp,
                    fontFamily = DhTokens.fonts,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
        }
        if (tooltip.isNullOrBlank()) {
            button()
        } else {
            com.dollhouse.app.ui.feedback.HintTooltip(tooltip) { button() }
        }
    }

    /* ============================ 长横条按钮 / 底部胶囊导航（r10） ============================ */

    /**
     * 占满一行的长横条按钮。视觉与 [ValueRow] 逐参数一致（SOFT 底 / 圆角 10 / 内边距 12 / 8dp 行距），
     * 因此和设置页既有各行天然同族，不会出现「新按钮比旧行矮一点」的突兀。
     *
     * 【为什么单列一个构件】r10 的人偶编辑页要在卡片外直接摆按钮，若各页自己拼 Row，
     *   圆角 / 内边距 / 禁用透明度迟早分叉。尺寸令牌只在本文件出现一次。
     *
     * @param iconRes  前置图标；null 表示不占位（规格：只有点名要图标的按钮才配图标）
     * @param desc     第二行小字说明（r13 新增）；null / 空 = 单行行高，与改造前逐像素一致
     * @param trailing 右侧文字（如 "›"）；null 表示不占位
     * @param enabled  false 时降透明度且不接收点击（空壳按钮的既定表现）
     */
    @Composable
    fun ActionRow(
        label: String,
        modifier: Modifier = Modifier,
        iconRes: Int? = null,
        desc: String? = null,
        trailing: String? = null,
        enabled: Boolean = true,
        tooltip: String? = null,
        /**
         * 【v3.1·真箭头】在 [trailing]/[value] 之后画一枚线性右箭头（`IC_CHEVRON_RIGHT`）。
         *
         * 【为什么要这个参数】改造前 22 处用**字符 `›`**（U+203A，是「单右引号」不是箭头）
         * 冒充 chevron：它的字形贴基线上部、粗细偏细，和旁边的图标不是一套；
         * 再叠上文楷，每一行的宽度与粗细都不一致 —— 这正是用户说的「符号错位」。
         */
        showChevron: Boolean = false,
        onClick: (() -> Unit)? = null
    ) {
        // 【M-收口·转发到规范实现】本函数有 87 处调用点（全 App 用得最多的行组件），
        //   改造它等于一次性让 87 个页面的"可点行"对齐设计规范。
        //   【为什么用转发而不是复制样式】`ui/kit.DhRow` 是规范源。改造前这里自带
        //   soft 底 + 圆角 10，于是每一行都是独立小卡片 —— 一页十行就是十个圆角矩形，
        //   正是设计稿要收敛掉的"逐行卡片"。转发后行直接铺在卡片面上。
        //   【参数适配】label→title、trailing→value（都是"右侧文字"，语义一致）、
        //     iconRes 的 Int? → DhRow 的 Int（null 归一为 0 = 不占位）、
        //     enabled→modifier（见下）。
        //   【为什么 showChevron = false】旧调用从不画箭头，转发后若默认画上会平白多一个符号。
        //
        //   【行距】旧实现给每行 padding(top = GAP_ROW)；但那 8dp 是为了把"小卡片"彼此
        //     分开。现在行铺在同一张卡上，行与行已由 DhRow 的行高（64/56dp）+ 垂直内边距
        //     自然分隔，再叠 8dp 会让同一页的行距忽大忽小。故转发时不再加 GAP_ROW ——
        //     与 ValueRow 转发后的口径一致（两处必须同口径，否则同页两种行距）。
        //
        //   【enabled 用 modifier 表达，不必走旧实现】DhRow 把 modifier 应用在 pressable
        //     **之前**，所以这里能同时做到「降透明度」+「吞掉点击」：
        //     传 onClick = null 让它不挂点击，再叠 alpha(0.35)。两者都与旧实现同值。
        //
        //   【唯一走旧实现的情况：tooltip】DhRow 里没有悬浮提示的位置，直接转发会让
        //     用户再也看不到那句说明。当前 87 处调用里**没有任何一处**传 tooltip，
        //     但签名保留着，所以分支必须留着，否则将来有人用就是静默丢功能。
        val rowModifier = if (enabled) {
            modifier
        } else {
            modifier.alpha(0.35f)
        }
        if (!tooltip.isNullOrBlank()) {
            LegacyActionRow(label, modifier, iconRes, desc, trailing, enabled, tooltip, onClick)
            return
        }
        com.dollhouse.app.ui.kit.DhRow(
            title = label,
            modifier = rowModifier,
            desc = desc,
            value = trailing,
            iconRes = iconRes ?: 0,
            // 【v3.1】把调用方要的箭头透传下去；原来是写死 false，
            //   于是调用方只能拿字符 `›` 冒充图标。
            showChevron = showChevron,
            onClick = if (enabled) onClick else null
        )
    }

    /**
     * 旧版 ActionRow 实现：仅保留给 [DhRow] 表达不了的参数组合。
     *
     * 【谁在用】只有 `tooltip != null` 的调用点（当前全仓 0 处，分支为兼容而留）。
     *   tooltip 是"悬浮才出现的说明文字"，DhRow 只描述静态的一行，没有位置安放它。
     *
     * 【为什么 enabled 不在这里】上一版把这个分支用于 `enabled = false`，但那 6 处
     *   忙态闸门因此仍显示成 soft 底小卡片，与同一页里已转发的行观感分叉。
     *   改由 [DhRow] 的 modifier（alpha + onClick=null）表达后，禁用行也统一为规范行。
     *
     * 【为什么整段样式原样保留】一旦把这里的样式也改成规范行，tooltip 行就与普通行
     *   长得一样、只差一个悬浮提示 —— 那时更该做的是给 DhRow 加 tooltip 参数，
     *   而不是在 DhKit 里维护第二份样式。等 DhRow 支持 tooltip 后，这个分支应整个删除。
     */
    @Composable
    private fun LegacyActionRow(
        label: String,
        modifier: Modifier,
        iconRes: Int?,
        desc: String?,
        trailing: String?,
        enabled: Boolean,
        tooltip: String?,
        onClick: (() -> Unit)?
    ) {
        val c = DhTokens.colors
        val clickable = enabled && onClick != null
        val hasDesc = !desc.isNullOrEmpty()
        val row: @Composable () -> Unit = {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = GAP_ROW.dp)
                .clip(RoundedCornerShape(R_ROW.dp))
                .background(c.soft)
                .then(if (clickable) Modifier.pressable(onClick!!) else Modifier)
                .then(if (enabled) Modifier else Modifier.alpha(0.35f))
                .padding(horizontal = PAD_ROW.dp, vertical = if (hasDesc) DhDesign.Space.sm.dp else PAD_ROW.dp)
                .defaultMinSize(minHeight = MIN_ROW_H.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconRes != null) {
                // 【v0.1.4·UI 统一】原先是 SZ_MD(20dp)，与本页其他行的图标大小不一致；
                //   统一走 ICON_ROW_DP，颜色也从 TITLE 改成 SUB（图标不该和标题一样重）。
                RowIcon(iconRes)
                Spacer(Modifier.width(GAP_ICON.dp))
            }
            // 【为什么用 Column 而不是再加一个 Text】两行时行高不该由调用方拼装 ——
            //   拼装会让「同一页两行行」与「单行行」的基线差几像素，看起来像两种控件。
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
                if (hasDesc) {
                    Text(
                        text = desc!!,
                        modifier = Modifier.padding(top = 2.dp),
                        color = c.sub,
                        fontSize = FS_ROW_DESC.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
            if (!trailing.isNullOrEmpty()) {
                Text(
                    text = trailing,
                    color = c.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                )
            }
        }
        }
        if (tooltip.isNullOrBlank()) {
            row()
        } else {
            com.dollhouse.app.ui.feedback.HintTooltip(tooltip) { row() }
        }
    }

    /* ---------------- 【r20】底部胶囊导航的尺寸令牌 ---------------- */
    /**
     * 导航项高度（dp）。
     *
     * 【为什么是「固定」高度】用户定案「固定高度 + 横向滚动 + 图标在上文字在下」：
     *   所有项等高才能排成一条整齐的胶囊；高度不统一时选中底块会高一块低一块。
     *   实现用 `heightIn(min = ...)` 而非 `height(...)`：正常字号下它就是 54dp（等值固定），
     *   而系统字体放大到极端档位时允许长高 —— 换成硬 `height` 会**裁切文字**，
     *   那正好违反同一条需求里的「不裁切文字」。两者取后者，视觉等值、极端情况不坏。
     */
    const val NAV_ITEM_H = 54

    /**
     * 导航项最小宽度（dp）。
     *
     * 【为什么要下限】中文两字标签自然宽度约 24dp，不加下限会挤成一条细长胶囊、
     *   点击区太小。下限保证「统一规则」：所有项至少这么宽，等长标签自然等宽。
     * 【为什么不用固定宽度】字体放大到 200% 时固定宽会把标签裁成「…」；
     *   用 `widthIn(min)` 让宽度跟着文字长，规则仍然统一（同样是 min + 同内边距）。
     */
    const val NAV_ITEM_W = 72

    /** 导航项左右内边距（dp）。所有项同一值 —— 这是「排列整齐」的另一半。 */
    const val NAV_ITEM_PAD_H = 12

    /** 导航项图标边长（dp）。比正文图标（20dp）略小，给文字留出呼吸空间。 */
    const val NAV_ICON = 19

    /**
     * 底部胶囊导航项：**图标在上、文字在下**（用户定案，不得改回横向）。
     *
     * 选中态用 OPTION 底 + ACC 字图（与设置页多选项「淡紫底 + 加粗」同一套语义色），
     * 未选中用 SUB 字图 —— 保证换主题 / 莫奈 / 纯黑下都随主题走。
     *
     * 【为什么纵向】横向时「图标 + 文字」会让每项宽到 70dp 以上，四项就占满屏；
     *   改纵向后同样的宽度能塞下更多项，也符合用户给的参考形态。
     */
    @Composable
    fun NavItem(
        iconRes: Int,
        label: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val c = DhTokens.colors
        val tint = if (selected) c.acc else c.sub
        Column(
            modifier = modifier
                .heightIn(min = NAV_ITEM_H.dp)
                .widthIn(min = NAV_ITEM_W.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (selected) c.option else Color.Transparent)
                .pressable(onClick)
                .padding(horizontal = NAV_ITEM_PAD_H.dp, vertical = 6.dp /* 不收敛·D4：与 NAV_ITEM_H(54) 配套，决定底栏总高 */),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(iconRes = iconRes, sizeDp = NAV_ICON.toFloat(), color = tint)
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                // 【不换行 + 不省略】两项都设，才能保证「不压缩字体、不裁切文字」：
                //   softWrap=false 让文字不折行（否则窄项里会变成两行竖排），
                //   maxLines=1 上限一行；宽度由 widthIn(min) 的 min + 内边距兜开。
                maxLines = 1,
                softWrap = false,
                color = tint,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
                textAlign = TextAlign.Center
            )
        }
    }

    /**
     * 【r20】底部导航的一项（数据描述）。
     *
     * 【为什么要有它】用户要求「采用数据驱动的可扩展组件，未来新增任意数量的导航项时
     *   自动复用相同布局规则，不需要逐个修改 UI」。于是调用方只提供一份 `List<NavItemSpec>`，
     *   布局 / 滚动 / 自动定位 / 选中态全部由 [NavBar] 统一处理。
     */
    class NavItemSpec(val iconRes: Int, val label: String)

    /**
     * 页面底部的**透明胶囊**导航条（r20 起为「数据驱动 + 横向滚动 + 纵向项」）。
     *
     * 【r13 统一口径】用户报「人偶编辑页的底栏是透明的但没胶囊；配置/模型页是胶囊但底色偏实」，
     *   要求两处**统一成透明胶囊**。本函数即「透明胶囊」的 Compose 侧唯一实现：
     *   胶囊外形（999 圆角）+ 半透明主题底（[UiKit.capsuleFill]）+ 描边 + 选中项高亮。
     *   View 侧的 [com.dollhouse.app.ui.widget.GlassCapsule] 读同一组令牌，两边不会分叉。
     *
     * 【r20 为什么改成收一份数据表】用户定案：「采用数据驱动的可扩展组件，未来新增任意数量的
     *   导航项时自动复用相同布局规则，不需要逐个修改 UI」。调用方只交 `items`（图标 + 文字）
     *   加 `selected` / `onSelect`，**布局、等高、等距、滚动、选中自动定位全在这里**。
     *   旧签名收的是 `RowScope` 内容槽 —— 那样每加一项都要在调用处手写一个 NavItem，
     *   也就必然出现「这一项忘了设 selected」「那项少写了 modifier」这类分叉。
     *
     * 【为什么是 Row + horizontalScroll，而不是 LazyRow】
     *   LazyRow 在**有界宽度**的父容器里一定填满父宽（LazyList 的测量策略如此，
     *   `wrapContentWidth` 对它无效）。那样四项也会被撑成整条屏宽，胶囊外形就丢了。
     *   Row + horizontalScroll 的内容区是自然宽度 —— 项少时胶囊自然收缩、居中；
     *   项多时等于父宽并出现横向滚动，两种形态都对。
     *   代价是「索引 → 偏移」要自己记：用 `onGloballyPositioned` 把「相对根的位置
     *   + 当前滚动量」换算成**内容坐标**（滚动时内容坐标不变，位置会变，故要加回滚动量）。
     *
     * @param items    导航项（顺序即展示顺序）
     * @param selected 当前选中项下标（越界自动夹紧，不会崩）
     * @param onSelect 点击某项的回调
     */
    @Composable
    fun NavBar(
        items: List<NavItemSpec>,
        selected: Int,
        onSelect: (Int) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val c = DhTokens.colors
        val scroll = rememberScrollState()
        val safeSelected = if (items.isEmpty()) -1 else selected.coerceIn(0, items.lastIndex)
        // 每项在**内容坐标系**里的起点与宽度（px）。滚动不改内容坐标，故可缓存复用。
        val itemX = remember { mutableStateMapOf<Int, Pair<Float, Int>>() }
        // 胶囊可见宽度（px）—— 由外层 Row 实测，用来判断「选中项是否完整可见」。
        var viewport by remember { mutableStateOf(0) }

        // 【选中自动定位】只在「选中项不完整可见」时才滚。
        //   每次切换都无条件滚一次的话，用户手动滑到别处看一眼、再点回原来那项，
        //   导航条会自己跳一下 —— 属于无谓的动效噪音。
        //   读 scroll.maxValue 作 key：内容宽度变化（如字体缩放）后需要重算。
        LaunchedEffect(safeSelected, items.size, viewport, scroll.maxValue) {
            if (safeSelected < 0 || viewport <= 0) {
                return@LaunchedEffect
            }
            val box = itemX[safeSelected] ?: return@LaunchedEffect
            val (x, w) = box
            val left = x - scroll.value
            // 完整可见：左边界不越出视口左沿，右边界不越出视口右沿。
            if (left >= 0f && left + w <= viewport) {
                return@LaunchedEffect
            }
            // 【尽量居中】目标滚动量 = 项起点 - (视口 - 项宽) / 2；
            //   coerceIn 到 [0, maxValue]，首尾项不会滚出空白。
            val target = (x - (viewport - w) / 2f).toInt().coerceIn(0, scroll.maxValue)
            scroll.animateScrollTo(target)
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                // 【高度固定】项本身是 heightIn(NAV_ITEM_H)，胶囊内边距是常量，
                //   两者相加是定值 —— 切页时底栏不会上下抖。
                .padding(top = 6.dp, bottom = 10.dp) /* 不收敛·D4：胶囊上下留白，与项高配套成定值 */
                .onSizeChanged { viewport = it.width },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    // 【wrapContentWidth】项少时胶囊收缩到内容宽（居中）；
                    //   项多时被父宽夹住并出现横向滚动。
                    .wrapContentWidth()
                    .clip(RoundedCornerShape(999.dp))
                    // 【必须不透明以外还要能透】胶囊底是半透明主题色，背后内容能透出轮廓；
                    //   这里不再叠任何实色底板，否则又退回「实心色块」。
                    .background(c.capsule)
                    .border(1.dp, c.stroke, RoundedCornerShape(999.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp) /* 不收敛·D4：胶囊内边距，决定胶囊总高 */
                    // 【滚动只作用于导航条】滚的是这个 Row 自己的内容，页面内容不受影响。
                    .horizontalScroll(scroll),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { idx, spec ->
                    NavItem(
                        iconRes = spec.iconRes,
                        label = spec.label,
                        selected = idx == safeSelected,
                        modifier = Modifier.onGloballyPositioned { coords ->
                            // 【换算内容坐标】滚动时 positionInRoot 会变小，加回 scroll.value
                            //   即得到「不受滚动影响」的内容坐标，可安全缓存。
                            val x = coords.positionInRoot().x + scroll.value
                            val w = coords.size.width
                            val old = itemX[idx]
                            if (old == null || old.first != x || old.second != w) {
                                itemX[idx] = x to w
                            }
                        }
                    ) { onSelect(idx) }
                }
            }
        }
    }

    /**
     * 多选行：选中 = OPTION 底 + 加粗 + 右侧勾，未选 = SOFT 底。
     * 【口径来源】与设置页原「手势响应」弹窗里的 `HomeUi.optionRow` 逐项一致
     *   （22dp 占位、12dp 内边距、8dp 行距），换到 Compose 后视觉不跳。
     * 【为什么未选也要占位】勾只在选中时出现；不预留位置会让文字在点选瞬间左右横跳。
     */
    @Composable
    fun CheckRow(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = GAP_ROW.dp)
                .clip(RoundedCornerShape(R_ROW.dp))
                .background(if (selected) c.option else c.soft)
                .pressable(onClick)
                .padding(horizontal = PAD_ROW.dp, vertical = PAD_ROW.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
            )
            if (selected) {
                Icon(iconRes = Icons.IC_CHECK, sizeDp = 16f, color = c.acc)
            } else {
                Spacer(Modifier.width(22.dp))
            }
        }
    }

    /**
     * 小尺寸等宽按钮（比例大小的 ±1 / ±10 四并排用）。
     * 【为什么再开一个】[ActionRow] 是整行宽、[StepButton] 是私有 34dp 圆钮；
     *   四并排需要「可被 weight 拉宽、文字居中」的方钮，与既有药丸口径不同。
     *   底 / 描边 / 圆角沿用 CARD + LINE + [R_ROW]，与卡片内的行天然同族。
     */
    @Composable
    fun MiniChip(
        text: String,
        modifier: Modifier = Modifier,
        // 【r16·图标】可选前缀图标（Icons.IC_*）。默认 0 = 与改造前逐像素一致。
        iconRes: Int = 0,
        /**
         * 【R-112 六.4】是否可点击。
         *   false = 整颗 chip 半透明 + 不响应点击 —— 用于「站着的她未选时主按钮置灰」。
         *   默认 true = 与改造前逐像素一致（既有调用点零影响）。
         */
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        val c = DhTokens.colors
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(R_ROW.dp))
                .alpha(if (enabled) 1f else 0.4f)
                // 【r13】卡片内嵌按钮 → cardUp（同色会看不见形状）。
                .background(c.cardUp)
                // 【本轮·修「非默认配置下看不清」】原来描边用 c.line —— 它在**非默认配色**
                //   （用户自定义主题色 / 卡片透明度调低）下几乎与 cardUp 同色，按钮整个化掉。
                //   改为按时序取更稳的一档：c.line 与 c.sub 混合后整体压深，
                //   保证「默认配色更清楚、非默认配色也不至于消失」。
                //   不用 c.acc（强调色）：一排四个 chip 全染色会抢走正文的视觉重心。
                .border(1.dp, c.sub.copy(alpha = 0.45f), RoundedCornerShape(R_ROW.dp))
                .pressable { if (enabled) onClick() }
                .padding(vertical = 9.dp)
                // 【本轮·修「点了没反应」】改造前这里没有水平 padding，且下方文字
                //   `weight(1f, fill = false)` 会把内容压到 0 宽 —— 视觉上按钮还在，
                //   实际可点区域被挤成一条，用户点中间也命中不到。
                //   这里补最小水平内边距，保证即使槽位很窄也留出可点面。
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // 【r16·修·防溢出】MiniChip 常用于「四并排等分槽」（动作详情页的帧操作）：
            //   每槽仅约 246px，而「图标 + 5 个中文字」约需 258px —— 会顶出槽位。
            //   故这里让文字拿 weight 占满剩余宽、单行截断（ellipsis），
            //   保证无论调用方给多窄的槽位，内容都被限制在自己的格子里。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (iconRes != 0) {
                    // 【v0.1.0·修·图标偏小】药丸文字是 15sp 粗体，14dp 图标明显偏轻。
                    //   按 [Design] 口径（SZ_XS 只给备注/次级标记），药丸图标属「行内小图标」→ SZ_SM。
                    Icon(iconRes = iconRes, sizeDp = Design.SZ_SM, color = c.title)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = text,
                    // 【本轮·修·两字按钮被截成省略号】原来 `weight(1f, fill = false)`：
                    //   宽度富余时它按内容自适应，但**窄槽 + 图标**时会把可用宽压到极小，
                    //   「导出」二字直接被省略成「…」，看起来像按钮坏了。
                    //   这里改成 `weight(1f, fill = true)` + 居中：始终占满剩余宽，
                    //   文字居中显示，短词永远不会被压成省略号。
                    modifier = Modifier.weight(1f),
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    /**
     * 悬浮窗底栏上的「图标 + 文字」按钮（[com.dollhouse.app.pet.DollRangeOverlay] /
     * [com.dollhouse.app.pet.DollHomeOverlay] 共用）。
     *
     * 【为什么提到这里】r12 两个悬浮窗各写了一份**完全相同**的私有实现，
     *   且字号写成 `13.sp` —— 那是全仓唯一的非标按钮字号（本工程只有
     *   [UiKit.FS_CHIP]=12 与 [UiKit.FS_BTN]=15 两档）。同一层级的按钮视觉重量不一致，
     *   正是「看着毛」的来源；收口到一处后不会再分叉。
     *
     * 【尺寸】图标走 [Design.SZ_MD]，文字走 [UiKit.FS_CHIP]，与既有药丸同族。
     */
    @Composable
    fun BarButton(iconRes: Int, tint: Color, label: String, onClick: () -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .pressable(onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp), /* 不收敛·D4：BarButton 自身几何 */
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(iconRes = iconRes, sizeDp = Design.SZ_MD, color = tint)
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = c.title,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = DhTokens.fonts,
                fontWeight = FontWeight.Medium
            )
        }
    }

    /* ============================ 药丸 ============================ */

    /** 把 [c] 向白色提亮 [t]（0=原色, 1=纯白）。轻拟物顶部高光用。 */
    private fun lighten(c: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = c.red + (1f - c.red) * k,
            green = c.green + (1f - c.green) * k,
            blue = c.blue + (1f - c.blue) * k,
            alpha = c.alpha
        )
    }

    /** 把 [c] 向黑色压暗 [t]（0=原色, 1=纯黑）。轻拟物下沿厚度用。 */
    private fun darken(c: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = c.red * (1f - k),
            green = c.green * (1f - k),
            blue = c.blue * (1f - k),
            alpha = c.alpha
        )
    }

    /** 描边药丸（实底 + LINE 描边）← `UiKit.outlineChip`。 */
    @Composable
    fun OutlineChip(
        text: String,
        modifier: Modifier = Modifier,
        // 【r16·图标】可选前缀图标（Icons.IC_*）。默认 0 = 与改造前逐像素一致。
        iconRes: Int = 0,
        /**
         * 【v3.1·选中态】默认 false 保持原样（不影响任何既有调用点）。
         * 【为什么需要】聊天列表的筛选条原本用「在文案后面加一个 `·`」表达选中
         *   （`if (filter == 0) "全部 ·" else "全部"`）—— 那个孤立的小点看起来
         *   像"未读数角标没渲染出来"，是典型的粗糙做法。选中态应该靠**底色与字色**表达。
         */
        selected: Boolean = false,
        onClick: (() -> Unit)? = null
    ) {
        val c = DhTokens.colors
        val chipBase = if (selected) c.acc else c.cardUp
        // 【v3·清透轻拟物】去硬描边。
        //   旧形态是 `cardUp 底 + 1dp line 描边` —— 描边在柔和明暗体系里会显"薄、硬"，
        //   与新方向相反。轮廓改由**上亮下暗的渐变**承担：
        //     顶部 = cardUp 提亮一档（光从左上来）
        //     底部 = cardUp 压暗一档（下沿厚度）
        //   【仍然不加 elevation 阴影】遵守 Elevation 文档「芯片永不加阴影」的硬规则。
        val chipLight = if (ThemeManager.isDark(LocalContext.current)) 0.16f else 0.10f
        Box(
            // 【修·内容不在中心】`Box` 的 `contentAlignment` 默认是 `TopStart`。
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            lighten(chipBase, chipLight),
                            chipBase,
                            darken(chipBase, chipLight * 0.8f)
                        )
                    )
                )
                // 【v0.2.5·修「椭圆形背景框偏长」】
                //   【根因】原来这里直接用 `pressable(onClick)`，而 `pressable` 从 v0.2.0 起
                //     内置了 `minTouchTarget()`（48dp 下限，为满足 M3 无障碍要求）。
                //     药丸本身只有「12sp 文字 + 上下 8dp 内边距 ≈ 35dp」高，
                //     被这个下限**拉高到 48dp**，圆角 999 就把 48dp 的框画成细长的椭圆；
                //     文字仍按 35dp 的高度贴着上方排，于是「下面偏了一大串白的」。
                //   【修法】这个药丸外面**已经**是整条可点的容器（本身就是按钮，不是孤立图标），
                //     不需要再靠透明区域补命中区：改用不带 48dp 下限的点击，
                //     并保留同一套按压缩放（复用 pressable 的手感，但跳过尺寸下限）。
                .then(if (onClick != null) Modifier.pressScaleClick(onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (iconRes != 0) {
                    Icon(iconRes = iconRes, sizeDp = Design.SZ_XS, color = c.title)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = text,
                    // 选中态用反白色（压在主色实底上）；未选中沿用原来的 title。
                    color = if (selected) c.onAcc else c.title,
                    fontSize = UiKit.FS_CHIP.sp,
                    fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts
                )
            }
        }
    }

    /* ============================ 分段控件 ============================ */
    /**
     * 一段可选项（[DhKit.Segmented] 的数据单元）。
     *
     * @param key     业务键（切组 / 切页签用，别用下标：分组顺序将来会变）
     * @param label   显示文案
     * @param iconRes 可选前缀图标（`Icons.IC_*`；0 = 不带图标）
     * @param badge   可选右上角计数（0 = 不显示）
     */
    class SegItem(
        val key: String,
        val label: String,
        val iconRes: Int = 0,
        val badge: Int = 0
    )

    /**
     * 分段控件 —— 少量互斥选项的切换（页面内的分组 / 页签）。
     *
     * 【为什么要收进 DhKit】动作管理页要按「常驻 / 随机 / 手动」切组，
     *   外观页已经有同一形态的页签条。两处各写一套的结果是：圆角、内边距、
     *   选中底色、字号字重会各偏一点，用户在同一 App 里能看到两种「分段」。
     *   收成一份之后，改样式只改这里。
     *
     * 【与 [Card] 的区别】[Card] 是折叠容器（有标题、能展开收起）；
     *   本控件是**互斥选择**，永远展开、不带折叠语义，别混用。
     *
     * 【动效】选中态的底色用 [DhAnim.COLOR_SPRING] 过渡（与开关同一条曲线）：
     *   点下去是「渐变过去」而不是「硬跳」，在快速连点时也不会闪白。
     */
    @Composable
    fun Segmented(
        items: List<SegItem>,
        selectedKey: String,
        modifier: Modifier = Modifier,
        onPick: (String) -> Unit
    ) {
        val c = DhTokens.colors
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.soft)
                .padding(4.dp)
                // 选项多时横向滚动而不是挤压文字（长中文标签被压会折行、很难看）。
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (it in items) {
                SegCell(
                    item = it,
                    selected = it.key == selectedKey,
                    onClick = { onPick(it.key) }
                )
            }
        }
    }

    @Composable
    private fun SegCell(item: SegItem, selected: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        // 【选中态】用 option（淡强调底）而不是实心强调色：与设置页多选项、
        //   底栏选中态同一套语义色，全 App 一致。
        val bg by animateColorAsState(
            targetValue = if (selected) c.option else Color.Transparent,
            animationSpec = DhAnim.COLOR_SPRING,
            label = "segBg"
        )
        val tint by animateColorAsState(
            targetValue = if (selected) c.acc else c.sub,
            animationSpec = DhAnim.COLOR_SPRING,
            label = "segFg"
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(R_ROW.dp))
                .background(bg)
                .pressable(onClick)
                .padding(horizontal = DhDesign.Space.md.dp, vertical = DhDesign.Space.sm.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.iconRes != 0) {
                Icon(iconRes = item.iconRes, sizeDp = Design.SZ_SM, color = tint)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = item.label,
                color = tint,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
            )
            // 计数：用「· N」而不是纯数字，避免和标签粘成一个词读不通。
            if (item.badge > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "· " + item.badge,
                    color = tint.copy(alpha = 0.75f),
                    fontSize = UiKit.FS_CHIP.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /* ============================ 开关 ============================ */

    /**
     * 自绘滑动开关（44×24dp，滑块内缩 3dp）← `UiKit.Switch`。
     *
     * 【为什么不用 Material3 Switch】它的尺寸（52×32dp）、轨道色、动画曲线都与既有自绘开关不同；
     *   同一页面里 View 开关与 Compose 开关并排会出现「两个开关长得不一样」。
     */
    @Composable
    fun Switch(
        checked: Boolean,
        modifier: Modifier = Modifier,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val c = DhTokens.colors
        val w = 44
        val h = 24
        val pad = 3
        val travel = (w - h).toFloat()
        // 【本次修复·颜色过冲】颜色与位移**不能共用同一组弹簧参数**。
        //   · 位移用 ζ=0.73（snappy）：允许轻微过冲，视觉上是「弹到位」，好看且与 View 侧一致；
        //   · 颜色必须 ζ=1.0（临界阻尼）：Compose 把 >1 的插值进度交给颜色 lerp 时会**越界**，
        //     深色主题下（底色接近 0）越界结果会被夹到纯黑/纯白，肉眼就是「轨道闪一下」。
        //   改造前两者共用 ζ=0.73，正是「开关切换时轨道颜色闪」的根因。
        //   两条曲线参数取自 DhAnim，与 View 侧同源。
        val progress by animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec = DhAnim.SW_SPRING,
            label = "sw"
        )
        val trackColor by animateColorAsState(
            targetValue = if (checked) c.switchOn else c.switchOff,
            animationSpec = DhAnim.COLOR_SPRING,
            label = "swc"
        )

        // 【r15·修·白球与开关不同步】
        //   旧写法：`.background(Color.White).scale(1f).offsetX(travel * progress)`
        //   —— 位移排在 background **内层**，而背景绘制用的是本节点（offset 外层）的尺寸与位置，
        //      于是轨道照常变色、白球却钉在原地不动。用户看到的正是「圆圈和开关不同步」。
        //   还有第二处隐性错误：`travel` 是 dp 数值（44-24=20），却被直接当 px 传给 IntOffset，
        //      在 3x 密度屏上只走了 20px≈6.7dp，即便位置对了也走不满行程。
        //   现在改为：位移用 dp 版 `offset(x = Dp)`，且**排在 background 之前**（外层），
        //   白球的绘制区间随位移一起走；行程与轨道两端内缩 [pad] 对齐，闭合/开启都严丝合缝。
        val slide = (pad + travel * progress).dp

        // **【v0.1.5·K-04】命中区 48×48，视觉仍是 44×24。**
        //
        // 【改造前的毛病】`clickable` 直接挂在 44×24 的 Box 上 —— 用户点轨道上下 1dp
        //   之外就没反应，手指略偏就"点了没反应"（需求书 K-04 / R-091 / N-010）。
        //
        // 【修法】外面包一层 48×48 的透明 Box，把 `clickable` 提到它上面：
        //   · 视觉（轨道 + 白球）尺寸一点不变，只是居中放在更大的可点区域里；
        //   · 点在轨道上下左右各 12dp / 2dp 以内的空白处也算点中；
        //   · `contentAlignment = Center` 保证视觉位置与改造前完全一致。
        //
        // 【为什么不直接 `size(48.dp, 48.dp)` 给轨道】那会把轨道撑成 48 高，
        //   与设计稿（24dp 轨道）不符，且开关行的行高会跟着变。
        Box(
            modifier = modifier
                .size(MIN_TOUCH.dp, MIN_TOUCH.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onCheckedChange(!checked) },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(w.dp, h.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(trackColor),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        // 顺序关键：offset → size → clip → background。
                        //   offset 必须在 background 外层，否则移动的只是「更内层的空内容」。
                        .offset(x = slide)
                        .size((h - pad * 2).dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White)
                )
            }
        }
    }

}

/* ============================ 反馈 ============================ */

/**
 * **【v0.1.5·K-03】把命中区补到至少 48dp，视觉尺寸不变。**
 *
 * 【为什么用 sizeIn 而不是 size】`sizeIn(minWidth, minHeight)` 只保证**下限**：
 *   内容更大的按钮（带文字的 IconButton）仍按内容宽度排布，不会被压成方形。
 *   这正是需求书 19.9.3「优先扩大透明命中区」的落地方式。
 *
 * 【为什么不直接用 Compose 官方的 minimumInteractiveComponentSize】它作用于 Material
 *   组件内部，对我们的自定义 `pressable` 不生效；且会把布局占位也撑到 48dp
 *   （= 视觉尺寸被迫变大），与本需求「视觉不变」的要求相反。
 */
fun Modifier.minTouchTarget(): Modifier =
    this.sizeIn(minWidth = DhKit.MIN_TOUCH.dp, minHeight = DhKit.MIN_TOUCH.dp)

/**
 * 统一按压缩放比例。
 *
 * 【M2 为什么从 0.96 改成 0.98】设计稿 §3「Press: scale 0.98 over 90ms, no ripple beyond that」。
 *   0.96 在 44dp 按钮上是 1.8dp 的收缩，视觉上"抖一下"；0.98 只有 0.9dp，
 *   更接近系统级反馈的克制感。全 App 只有这一个值 —— 页面不许自己写缩放。
 */
// 【为什么是 internal 而不是 private】`DhForm` 的按钮轻拟物需要同一个按压缩放值
//   （它要在按下时同时收小阴影）。再定义一份就会变成"两个 0.98f"，
//   那正是 A06 门禁在防的事 —— 复用同一个常量比复制数字正确。
internal const val PRESS_SCALE = 0.98f

/**
 * 统一按压反馈：按下缩到 [PRESS_SCALE]，松手弹回（ζ=0.40）—— 与 `UiKit.press` + `Springs.bouncy()` 同口径。
 * 全 App 的可点元素都走它，保证「点哪都有同一种手感」。
 *
 * 【为什么放顶层】页面（如 `MemPage`）要给自定义行加按压反馈。若留在 `object DhKit` 内
 *   就是成员扩展，外部文件必须 `with(DhKit) { ... }` 才能调，每迁移一页踩一次。
 *   提到顶层后只需 `import com.dollhouse.app.ui.compose.pressable`，全 App 一种写法。
 */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val callback = { com.dollhouse.app.core.OperationRecorder.event("Action", "Compose click"); onClick() }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // 【本次修复·按下别过冲】按下缩到 [PRESS_SCALE] 用 tween（跟手、不过冲），
    //   松手才用 ζ=0.4 弹簧弹回 —— 与 View 侧 `UiKit.press` + `Springs.bouncy()` 完全同口径。
    //   改造前按下/松手共用一个 ζ=0.4 弹簧：手指刚触屏元素会先缩过头再回弹到 0.96，
    //   短按时能看到「先弹一下」，与 View 侧手感也对不上。
    //
    // 【v0.2.0·Material 3 无障碍：把触摸目标下限收进 pressable 本身】
    //   【问题】M3 的《Accessibility》要求所有可交互元素的命中区不小于 48×48dp。
    //     本工程此前只有 2 处显式调了 `minTouchTarget()`，而 `pressable` 有 48 处调用——
    //     也就是说「勾选框」「关闭叉」「小图标按钮」这类视觉上只有 12~20dp 的元素，
    //     命中区同样只有那么点大，单手操作时经常点空。
    //   【为什么收在这里而不是逐处补】48 个调用点逐个加，早晚会漏；而且新写的按钮
    //     天然不会想起来加。收进 `pressable` 之后，「只要可点」就自动合规，
    //     调用点零改动，后续新增的按钮也自动继承。
    //   【为什么不会把界面撑变形】`minTouchTarget` 用的是 `sizeIn`（只约束下限），
    //     且它作用在 `clickable` **之前** —— 扩大的是可点击区域，视觉尺寸由内容决定，
    //     文字 / 图标不会被拉伸。这与 M3「优先扩大透明命中区」的要求一致。
    val spec = if (pressed) DhAnim.PRESS_DOWN else DhAnim.BOUNCY
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = spec,
        label = "press"
    )
    return this
        // 【v0.2.0·Material 3 无障碍：触摸目标】见下方注释。
        .minTouchTarget()
        .scale(scale)
        .clickable(interactionSource = source, indication = null, onClick = callback)
}

/**
 * 与 [pressable] 同一套按压手感，但**不施加 48dp 触摸目标下限**（v0.2.5）。
 *
 * 【为什么需要它】[pressable] 从 v0.2.0 起内置 `minTouchTarget()`，为的是让孤立的
 *   小图标（叉、勾、点）有足够命中区。但有些元素本身就是一个完整按钮
 *   （例如 [OutlineChip] 这种「文字 + 药丸底」），它们的高度本来就由内容决定 ——
 *   再套一个 48dp 下限，只会把 **布局高度** 撑大，视觉上圆角 999 就变成细长椭圆，
 *   文字贴顶、下方空白。
 *
 * 【用在哪些地方】只用于「自带可见轮廓、尺寸足够」的按钮；孤立图标仍走 [pressable]，
 *   保持无障碍命中区。
 */
@Composable
fun Modifier.pressScaleClick(onClick: () -> Unit): Modifier {
    val callback = { com.dollhouse.app.core.OperationRecorder.event("Action", "Compose click"); onClick() }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val spec = if (pressed) DhAnim.PRESS_DOWN else DhAnim.BOUNCY
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = spec,
        label = "press"
    )
    return this
        .scale(scale)
        .clickable(interactionSource = source, indication = null, onClick = callback)
}

/**
 * 【r13·聊天列表】带长按的按下效果。
 *
 * 【为什么新开一个重载而不是改原函数】`pressable(onClick)` 全工程有几十处调用，
 *   给它加默认参数（`onLongClick: (() -> Unit)? = null`）虽然源码兼容，
 *   但 `clickable` 在带 `onLongClick` 时会走**另一条手势路径**（先等长按超时、
 *   抬手才算点击），会让所有既有按钮的点击响应多出一点点判定延迟 —— 对
 *   一个「按压即缩放」的按钮来说这种微妙延迟是可见的。
 *   所以保持原函数**逐字节不变**，另开一个明确的重载，只有真正需要长按的调用点用它。
 *
 * 【为什么用 combinedClickable】Compose 里「同一个区域既响应点击又响应长按」的
 *   标准做法就是它；手写 `pointerInput` 要自己处理「长按后抬手不能触发点击」
 *   这条规则，容易在边缘时序上出错。这个重载内部才用 combinedClickable，
 *   不影响其它调用点。
 *
 * 【坑·kapt】这里的注解必须用**短名**（`@OptIn(ExperimentalFoundationApi::class)`），
 *   不能用全限定名（`@androidx.compose.foundation.ExperimentalFoundationApi::class`）——
 *   kapt 生成 Java 桩时解析不了全限定注解，会报
 *   `NonExistentClass cannot be converted to Annotation` 让整个 kapt 阶段失败。
 *   这是本项目引入 kapt 后踩过的第一个坑，见 ChatListScreen 同款修正。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // 与单参重载同口径：按下 tween 跟手，松手弹簧回弹。
    val spec = if (pressed) DhAnim.PRESS_DOWN else DhAnim.BOUNCY
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = spec,
        label = "press"
    )
    return this
        // 【v0.2.0·与单参重载同口径】触摸目标下限一并收进来，见上方注释。
        .minTouchTarget()
        .scale(scale)
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
}

/** [Modifier.pressable] 的横向偏移辅助（开关滑块用）。 */
private fun Modifier.offsetX(x: Float): Modifier = this.offset { IntOffset(x.toInt(), 0) }