package com.dollhouse.app.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import kotlinx.coroutines.flow.collect

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

    /* ============================ 尺寸令牌（与 View 版同源） ============================ */

    /** 卡片圆角（dp）← UiKit.card 的 16f。 */
    const val R_CARD = 16
    /** 行 / 小方块圆角（dp）← HomeCards.valueRow 的 10f。 */
    const val R_ROW = 10
    /** 卡片间距（dp）← HomeCards.buildCard 的 topMargin 10f。 */
    const val GAP_CARD = 10
    /** 卡片内边距（dp）← HomeCards.buildCard 的 16f。 */
    const val PAD_CARD = 16
    /** 行内边距（dp）← HomeCards.valueRow 的 12f。 */
    const val PAD_ROW = 12
    /** 行间距（dp）← HomeCards.valueRow 的 topMargin 8f。 */
    const val GAP_ROW = 8
    /** 图标命中区（dp）← UiKit.HIT_DP。 */
    const val HIT = 40

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
     * 标准卡片：CARD 底 + 圆角 16 + 描边 STROKE + 微阴影，下方留 10dp 间距。
     *
     * @param spacingBottom 是否在卡片下方留 [GAP_CARD] 间距；连排最后一张可传 false。
     */
    @Composable
    fun Card(
        modifier: Modifier = Modifier,
        spacingBottom: Boolean = true,
        collapsibleTitle: String? = null,
        flat: Boolean = false,
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
                            .clip(RoundedCornerShape(R_CARD.dp))
                            .background(c.card)
                            .border(1.dp, c.stroke, RoundedCornerShape(R_CARD.dp))
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
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold
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
                AnimatedVisibility(visible = open) {
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
    fun CardTitle(text: String, modifier: Modifier = Modifier) {
        Text(
            text = text,
            modifier = modifier,
            color = DhTokens.colors.title,
            fontSize = UiKit.FS_BTN.sp,
            fontFamily = DhTokens.fontsBold,
            fontWeight = FontWeight.Bold
        )
    }

    /* ============================ 顶栏 ============================ */

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
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(iconRes = Icons.IC_ARROW_LEFT, sizeDp = 20f, color = DhTokens.colors.title, onClick = onBack)
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = DhTokens.colors.title,
                    fontSize = UiKit.FS_TITLE.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
                if (!sub.isNullOrEmpty()) {
                    Text(
                        text = sub,
                        modifier = Modifier.padding(top = 2.dp),
                        color = DhTokens.colors.sub,
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
        onClick: (() -> Unit)? = null
    ) {
        val c = DhTokens.colors
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = GAP_ROW.dp)
                .clip(RoundedCornerShape(R_ROW.dp))
                .background(c.soft)
                .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
                .padding(horizontal = PAD_ROW.dp, vertical = PAD_ROW.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            if (!value.isNullOrEmpty()) {
                Text(
                    text = value,
                    color = valueTint ?: c.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    /* ============================ 开关行 / 权限行 / 步进器 ============================ */

    /**
     * 开关行：左名称 + 右开关，SOFT 底 + 圆角 10 ← `HomeCards.switchRow`。
     * 点整行与点开关等价（与 View 版一致）。
     */
    @Composable
    fun SwitchRow(
        name: String,
        checked: Boolean,
        modifier: Modifier = Modifier,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val c = DhTokens.colors
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = GAP_ROW.dp)
                .clip(RoundedCornerShape(R_ROW.dp))
                .background(c.soft)
                .pressable { onCheckedChange(!checked) }
                .padding(horizontal = PAD_ROW.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            // 【修·开关圆球不跟进】原来这里先用 Material3 的 Switch，再叠一个空 Box 想「吃掉点击」，
            //   结果空 Box 与开关同层，触摸落点被它拦走、开关自身的 onCheckedChange 收不到，
            //   于是点一下轨道闪一下、圆球不移动。
            //   现在改用本工程自绘的 Switch（与 UiKit.Switch 同尺寸同手感），点击只由它一处消费。
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    /**
     * 权限行：左名称 + 右状态（带勾 / 叉 / 警告图标 + 语义色）← `HomeCards.permRow`。
     * 已授权时整行不可点。
     *
     * @param state  0=已授权（绿勾）1=未授权（红叉）2=待处理（红警告）
     */
    @Composable
    fun PermRow(
        name: String,
        state: Int,
        modifier: Modifier = Modifier,
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
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = GAP_ROW.dp)
                .clip(RoundedCornerShape(R_ROW.dp))
                .background(c.soft)
                .then(if (granted) Modifier else Modifier.pressable(onClick))
                .padding(horizontal = PAD_ROW.dp, vertical = PAD_ROW.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            Text(
                text = if (granted) "已授权" else "未授权",
                color = tint,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            // 【r12】13f 不在 [Design] 的图标五档内 → 归到 SZ_XS(14)。
            Icon(iconRes = icon, sizeDp = Design.SZ_XS, color = tint)
        }
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
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = GAP_ROW.dp)
                .clip(RoundedCornerShape(R_ROW.dp))
                .background(c.soft)
                .padding(horizontal = PAD_ROW.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            StepButton(iconRes = Icons.IC_MINUS, enabled = canMinus, onClick = onMinus)
            Text(
                text = value,
                modifier = Modifier.width(62.dp),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            StepButton(iconRes = Icons.IC_PLUS, enabled = canPlus, onClick = onPlus)
        }
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
    fun SectionLabel(text: String, modifier: Modifier = Modifier) {
        Text(
            text = text,
            modifier = modifier.padding(top = 12.dp, bottom = 2.dp),
            color = DhTokens.colors.sub,
            fontSize = UiKit.FS_SUB.sp,
            fontFamily = DhTokens.fonts
        )
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
                    Spacer(Modifier.width(6.dp))
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
            modifier = modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(iconRes = iconRes, sizeDp = Design.SZ_XS, color = c.line)
            Spacer(Modifier.width(6.dp))
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
     * @param sizeDp 只允许 [com.dollhouse.app.ui.theme.Design.SZ_*] 五档
     */
    @Composable
    fun Icon(
        iconRes: Int,
        sizeDp: Float,
        color: Color,
        modifier: Modifier = Modifier
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = modifier.size(sizeDp.dp),
            colorFilter = ColorFilter.tint(color)
        )
    }

    /** 可点击图标（命中区固定 [HIT]dp，与 `UiKit.iconView` 一致）。 */
    @Composable
    fun IconButton(
        iconRes: Int,
        sizeDp: Float,
        color: Color,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Box(
            modifier = modifier
                .size(HIT.dp)
                .clip(RoundedCornerShape(999.dp))
                .pressable(onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(iconRes = iconRes, sizeDp = sizeDp, color = color)
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
        onClick: (() -> Unit)? = null
    ) {
        val c = DhTokens.colors
        val clickable = enabled && onClick != null
        val hasDesc = !desc.isNullOrEmpty()
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = GAP_ROW.dp)
                .clip(RoundedCornerShape(R_ROW.dp))
                .background(c.soft)
                .then(if (clickable) Modifier.pressable(onClick!!) else Modifier)
                .then(if (enabled) Modifier else Modifier.alpha(0.35f))
                .padding(horizontal = PAD_ROW.dp, vertical = PAD_ROW.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconRes != null) {
                Icon(iconRes = iconRes, sizeDp = Design.SZ_MD, color = c.title)
                Spacer(Modifier.width(10.dp))
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
                        fontSize = UiKit.FS_TINY.sp,
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
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    /**
     * 底部胶囊导航项：图标 + 文字。
     * 选中态用 OPTION 底 + ACC 字图（与设置页多选项「淡紫底 + 加粗」同一套语义色），
     * 未选中用 SUB 字图 —— 保证换主题 / 莫奈 / 纯黑下都随主题走。
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
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (selected) c.option else Color.Transparent)
                .pressable(onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(iconRes = iconRes, sizeDp = Design.SZ_MD, color = tint)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = tint,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    /**
     * 页面底部的**透明胶囊**导航条（内容为若干 [NavItem]）。
     *
     * 【r13 统一口径】用户报「人偶编辑页的底栏是透明的但没胶囊；配置/模型页是胶囊但底色偏实」，
     *   要求两处**统一成透明胶囊**。本函数即「透明胶囊」的 Compose 侧唯一实现：
     *   胶囊外形（999 圆角）+ 半透明主题底（[UiKit.capsuleFill]）+ 描边 + 选中项高亮。
     *   View 侧的 [com.dollhouse.app.ui.widget.GlassCapsule] 读同一组令牌，两边不会分叉。
     *
     * 【为什么外层还要一层 Row】胶囊本身只包住三个按钮（宽度自适应），
     *   而底栏需要「整屏宽 + 居中」，所以外面套一个透明、无内边距的定位层 ——
     *   与 `ProviderDetailPage` 里 `pill` 只当尺子、胶囊才是唯一背景层的做法一致。
     */
    @Composable
    fun NavBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    // 【必须不透明以外还要能透】胶囊底是半透明主题色，背后内容能透出轮廓；
                    //   这里不再叠任何实色底板，否则又退回「实心色块」。
                    .background(c.capsule)
                    .border(1.dp, c.stroke, RoundedCornerShape(999.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
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
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
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
    fun MiniChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val c = DhTokens.colors
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(R_ROW.dp))
                // 【r13】卡片内嵌按钮 → cardUp（同色会看不见形状）。
                .background(c.cardUp)
                .border(1.dp, c.line, RoundedCornerShape(R_ROW.dp))
                .pressable(onClick)
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
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
                .padding(horizontal = 6.dp, vertical = 4.dp),
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

    /** 描边药丸（实底 + LINE 描边）← `UiKit.outlineChip`。 */
    @Composable
    fun OutlineChip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
        val c = DhTokens.colors
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                // 【r13】卡片内嵌按钮 → cardUp。
                .background(c.cardUp)
                .border(1.dp, c.line, RoundedCornerShape(999.dp))
                .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                color = c.title,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = DhTokens.fonts
            )
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

        // 轨道色与滑块位移由同一条弹簧驱动，避免「轨道跳色、滑块慢跟」的脱节感。
        val spec = spring<Float>(dampingRatio = 0.73f, stiffness = 700f)
        val progress by animateFloatAsState(targetValue = if (checked) 1f else 0f, animationSpec = spec, label = "sw")
        val trackColor by animateColorAsState(
            targetValue = if (checked) c.acc else c.switchOff,
            animationSpec = spring(dampingRatio = 0.73f, stiffness = 700f),
            label = "swc"
        )

        Box(
            modifier = modifier
                .size(w.dp, h.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(trackColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onCheckedChange(!checked) },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = pad.dp)
                    .size((h - pad * 2).dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White)
                    .scale(1f)
                    .offsetX(travel * progress)
            )
        }
    }

}

/* ============================ 反馈 ============================ */

/**
 * 统一按压反馈：按下缩到 0.96，松手弹回（ζ=0.40）—— 与 `UiKit.press` + `Springs.bouncy()` 同口径。
 * 全 App 的可点元素都走它，保证「点哪都有同一种手感」。
 *
 * 【为什么放顶层】页面（如 `MemPage`）要给自定义行加按压反馈。若留在 `object DhKit` 内
 *   就是成员扩展，外部文件必须 `with(DhKit) { ... }` 才能调，每迁移一页踩一次。
 *   提到顶层后只需 `import com.dollhouse.app.ui.compose.pressable`，全 App 一种写法。
 */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f),
        label = "press"
    )
    return this
        .scale(scale)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
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
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f),
        label = "press"
    )
    return this
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