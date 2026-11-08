package com.dollhouse.app.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dollhouse.app.ui.compose.DhAnim
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressScaleClick
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.design.DhDesign
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 【职责】全 App **唯一**的 Compose 组件库入口。
 *
 * 【为什么新建 ui/kit 而不是继续往 DhKit（1875 行）里塞】
 *   改造前"组件"散在四处：
 *     · `DhKit`      —— 39 个 Composable，卡片/行/开关/顶栏/底栏…
 *     · `DhForm`     —— 17 个，输入框/芯片/提交按钮/弹窗
 *     · `ui/theme/UiKit`  —— View 侧工厂（card/chip/dialogTitle/dialogButton…）
 *     · `ui/widget/`       —— SheetPanel / SwipeRow / GlassCapsule / ApiPageKit（纯 View）
 *   同一个"按钮"因此有五种写法、四种尺寸（44 / 40 / 34 / 由内容决定）、
 *   三种圆角（10 / 12 / 999）—— 这正是"相同功能的按钮大小不一致"的来源。
 *   本文件不是又加一层，而是把**规范钉在一处**：新增构件一律在这里，
 *   既有构件通过 [DhButton] 等入口逐步收编（M4–M7 逐页替换）。
 *
 * 【设计依据】`design-studio/dollhouse-refresh/design_prompts_forcoders.md` §3 组件规范：
 *   · 按钮：统一高度 44dp（sm 34dp），主按钮实心 accent + onAcc，次按钮 card + line 描边，
 *           ghost 透明 + accent 文字，danger 透明 + err 描边/文字；按压缩放 0.98 / 90ms。
 *           **每屏只允许一个主按钮**。
 *   · 输入框：高 40dp、圆角 12、field 底、**聚焦前无描边**；聚焦时改 card 底 + 1dp accent 描边（160ms）。
 *   · Chip（26dp 药丸，可点、用于筛选）与 Tag（20dp 圆角 8，**不可点**、只标注状态）不可互换。
 *   · 列表行：两行 64dp / 单行 56dp，行与行之间 1dp hairline，直接铺在 bg 上（不是每行一个卡片）。
 *   · 空/加载/错误：56dp 图标座（soft 底、12 圆角）→ section 标题 → sub 正文最多 2 行 → 恰好一个动作。
 *   · 加载骨架只在等待超过 1.2s 后出现（否则直接出内容，避免闪一下）。
 *
 * 【硬约束】本文件不新增颜色常量，一切取 [DhTokens.palette]；
 *   不新增尺寸字面量，一切取 [DhDesign]。
 */
object DhButtonStyle {
    /** 主按钮：实心 accent。每屏只允许一个。 */
    const val PRIMARY = 0
    /** 次按钮：card 底 + line 描边。 */
    const val SECONDARY = 1
    /** 幽灵按钮：透明底 + accent 文字。 */
    const val GHOST = 2
    /** 危险按钮：透明底 + err 描边与文字。 */
    const val DANGER = 3
}

/**
 * 组件尺寸具名常量（dp）。
 *
 * 【为什么集中在这里】设计稿 §2.2 / §3 给出的是「固定指标」（the "same team made it" contract）：
 *   按钮 44（sm 34）、输入框 40、chip 26、tag 20、状态图标座 56、骨架行高等。
 *   这些值一旦散落成字面量，就会重演「这一页按钮比上一页矮一点」的老问题。
 *   页面**禁止**覆盖这些值，需要新尺寸时先来这里加一档并写清用途。
 */
private val ButtonHeight = 44.dp
private val ButtonHeightSm = 34.dp
private val FieldHeight = DhDesign.Size.fieldH
private val ChipHeight = 26.dp
private val TagHeight = 20.dp
// 【已删除 StateSeat】空态图标座改用 IconSeat(sizeDp = IconSeatSize.LG, shape = CIRCLE)，
//   尺寸 56dp 由 IconSeatSize.LG 承担，不再需要本文件里的第二份常量。
private val LoadingSpinner = 16.dp
private val LoadingStroke = 2.dp
private val DescGap = 2.dp
// 【不收敛·D6】下面三个是**控件自身几何**（决定 chip/tag 宽度与 DhRow 两行行高），
//   不是元素之间的间距；改动会放大到 169 处转发调用点。按 §7.4 须单独立项验证。
private val ChipPadH = 10.dp
private val TagPadH = 6.dp
private val RowPadV = 10.dp

/**
 * 统一按钮。
 *
 * 【为什么必须有这一个入口】改造前按钮由 6 个工厂生成，尺寸 44/40/34/自适应混用。
 *   用户最容易察觉的不一致就是「这一页的按钮比上一页矮一点、圆一点」。
 *
 * 【按压反馈】0.98 缩放 + 90ms（[DhDesign.Motion.press]），不做涟漪 ——
 *   涟漪在自绘卡片上会溢出圆角，视觉噪点比反馈价值大。
 *
 * @param style 见 [DhButtonStyle]
 * @param small 小尺寸（34dp）；默认 false（44dp）
 * @param loading 加载中：替换文字为转圈且**自动禁用**（防重复提交）
 * @param enabled false 时整颗按钮压到 38% 不透明度并拦截点击
 */
@Composable
fun DhButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: Int = DhButtonStyle.PRIMARY,
    small: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: Int = 0
) {
    val p = DhTokens.palette
    val t = DhTokens.type
    val active = enabled && !loading
    val height = if (small) ButtonHeightSm else ButtonHeight
    val fill = when (style) {
        DhButtonStyle.PRIMARY -> p.accent
        DhButtonStyle.SECONDARY -> p.card
        else -> Color.Transparent
    }
    val fg = when (style) {
        DhButtonStyle.PRIMARY -> p.onAcc
        DhButtonStyle.DANGER -> p.err
        DhButtonStyle.GHOST -> p.accent
        else -> p.title
    }
    val strokeColor = when (style) {
        DhButtonStyle.SECONDARY -> p.line
        DhButtonStyle.DANGER -> p.err
        else -> Color.Transparent
    }
    val padH = if (small) 12.dp else 20.dp
    val alpha = if (active) 1f else 0.38f
    val shape = RoundedCornerShape(DhDesign.Radius.btn.dp)
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(fill.copy(alpha = fill.alpha * alpha))
            .then(
                if (strokeColor.alpha > 0f) {
                    Modifier.border(DhDesign.Size.hairline, strokeColor.copy(alpha = alpha), shape)
                } else {
                    Modifier
                }
            )
            .then(if (active) Modifier.pressScaleClick(onClick) else Modifier)
            .padding(horizontal = padH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = fg.copy(alpha = alpha),
                strokeWidth = LoadingStroke
            )
        } else {
            if (leadingIcon != 0) {
                DhKit.Icon(leadingIcon, DhDesign.Size.iconSm.value, fg.copy(alpha = alpha))
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
            }
            Text(
                text = text,
                color = fg.copy(alpha = alpha),
                style = t.rowTitle.copy(fontSize = if (small) t.sub.fontSize else t.body.fontSize),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 统一输入框。
 *
 * 【与既有 `DhForm.Input` 的差别（也是必须替换它的理由）】
 *   旧实现：圆角 12 + `field` 底 + padding 12 + 无描边，聚焦时**什么都不变**。
 *   于是用户不知道「现在这个框是激活的」—— 多框表单里尤其明显。
 *   本实现按设计稿：聚焦前无描边；聚焦时底换成 `card`、加 1dp accent 描边，160ms 过渡。
 *
 * 【高度为什么是 40dp 而不是让内容撑】40dp 让「单行输入框」与「行尾图标的 48dp 命中区」
 *   分离开：视觉 40、命中 48，既有稳定的行高节奏，又不违反无障碍。
 *   多行模式不受此限（按行数生长）。
 */
@Composable
fun DhField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    password: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: Int = 0,
    trailing: (@Composable () -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    /**
     * 输入框的**无障碍名**（读屏与 `uiautomator dump` 读到的名字）。
     *
     * 【为什么是 contentDescription 而不是 hint】Compose 1.10.2 的 `ui-android` 只暴露
     *   `setContentDescription`，**没有** `setHintText` —— 没有「提示文本」这条语义通道。
     *   所以想让 dump 里那个 EditText 节点带名字，contentDescription 是唯一入口。
     *
     * 【取舍·写在这里免得下一个人以为乱写】官方建议「别给可编辑控件塞 content-desc」，
     *   因为部分读屏会先念名字再念输入值。本工程没有 hint 通道，两者只能二选一：
     *   要么 dump / 读屏完全无名（就是本次要修的 bug），要么接受「名字 + 值」的念法。
     *   选后者。
     *
     * 【为什么必须挂在 BasicTextField 自己的 modifier 上】挂在外层 Row 只会多一个兄弟节点，
     *   dump 里 EditText 节点仍然是空 desc（实测两个节点不会合并）。
     */
    a11yName: String? = null
) {
    val a11y = a11yName
    val p = DhTokens.palette
    val t = DhTokens.type
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    // 【v3·清透轻拟物】聚焦态不再靠「加粗描边」，而是靠**内凹加深 + accent 光晕**。
    //   扁平风格用描边表示激活（一条明确的线）；轻拟物里"被点亮"表现为内部发光 ——
    //   所以这里的 fill 与光晕都由 [dhFieldInset] 一并处理，不再单独画 border。
    //
    //   【底色语义保持不变】未聚焦 = `field`（本来就比 `card` 暗一档 → 天然内凹），
    //   聚焦 = `card`（略微抬起，配合加深的内阴影与光晕 → "被激活"）。
    //   这两个色值都没改，只是"聚焦时加不加描边"这件事被换成了内阴影 + 光晕。
    val fill by animateColorAsState(
        targetValue = if (focused) p.card else p.field,
        animationSpec = androidx.compose.animation.core.tween(DhDesign.Motion.micro),
        label = "field-fill"
    )
    // 【保留 stroke 变量】它仍被用于「聚焦时的 accent 光晕」着色基础。
    //   【为什么还要 animateColorAsState】光晕随聚焦渐变（160ms），与 fill 同步；
    //   若直接读 p.accent，聚焦瞬间会出现"光晕突然亮起"的跳变。
    val stroke by animateColorAsState(
        targetValue = if (focused) p.accent else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(DhDesign.Motion.micro),
        label = "field-stroke"
    )
    val shape = RoundedCornerShape(DhDesign.Radius.btn.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (singleLine) {
                    Modifier.height(FieldHeight)
                } else {
                    Modifier.defaultMinSize(minHeight = FieldHeight)
                }
            )
            .clip(shape)
            .background(fill)
            // 【内凹】必须排在 background 之后（画在底色之上），且已被上一步 clip 裁进圆角。
            //   它是替代原件里 `Modifier.border(...)` 的那一环：不再是外描边，而是内阴影。
            .dhFieldInset(shape = shape, focused = focused, glowColor = stroke)
            .padding(horizontal = DhDesign.Space.md.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != 0) {
            DhKit.Icon(leadingIcon, DhDesign.Size.iconSm.value, p.sub)
            Spacer(Modifier.width(DhDesign.Space.sm.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    color = p.sub,
                    style = t.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                // 【只加一个属性】这里只写 contentDescription；text field 自己的
                //   editableText / SetText / imeAction 由它内部语义提供，本修饰符不覆盖它们。
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (a11y != null) {
                            Modifier.semantics { contentDescription = a11y }
                        } else {
                            Modifier
                        }
                    ),
                enabled = enabled,
                textStyle = t.body.copy(color = p.title),
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                cursorBrush = SolidColor(p.accent),
                interactionSource = source,
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboard,
                    imeAction = imeAction
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { onDone?.invoke() },
                    onSearch = { onDone?.invoke() },
                    onGo = { onDone?.invoke() }
                )
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(DhDesign.Space.sm.dp))
            trailing()
        }
    }
}

/**
 * 统一列表行。
 *
 * 【设计要点】行直接铺在 `bg` 上，行与行之间 1dp hairline；**不是**每行一个卡片。
 *   改造前的做法是每行一个 `soft` 底 + 圆角 12 的"小卡片"，一页 10 行就有 10 个圆角矩形，
 *   用户反馈的"每个区域都使用独立卡片"指的就是这个。现在卡片只用来**分组**整块内容。
 *
 * @param desc 第二行说明；null = 单行（行高 56），非 null = 两行（行高 64，自动增高）
 * @param value 右尾状态文字（选中值、当前档位）
 * @param showChevron 是否显示右箭头（有下级页面时为 true）
 * @param danger 危险项：文字用 err 色
 */
@Composable
fun DhRow(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    value: String? = null,
    iconRes: Int = 0,
    iconTint: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    showChevron: Boolean = false,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val p = DhTokens.palette
    val t = DhTokens.type
    val twoLine = !desc.isNullOrEmpty()
    val fg = if (danger) p.err else p.title
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (twoLine) DhDesign.Size.rowTwoLine else DhDesign.Size.rowOneLine)
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .padding(vertical = if (twoLine) RowPadV else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != 0) {
            /*
             * 【v3·清透轻拟物】行首图标：裸图标 → **圆形浅底**。
             *
             * 用户定案「图标统一使用精致的线性图标，合理搭配浅色圆形背景」。
             * 本行是全 App 服务面最广的一处图标位（169 处转发调用点最终都落到这里）。
             *
             * 【为什么 32dp 安全】本行的几何是：
             *   · 单行：`defaultMinSize(minHeight = 56)`、**无垂直 padding** → 内容区整整 56
             *   · 两行：`padding(vertical = RowPadV = 10)` → 64 - 20 = 44 给内容
             *   32dp 圆底在单行占 56 里的 32，两行占 44 里的 32 —— 都不撑高。
             *   （对比 `DhKit.RowIcon`：那里调用的行自带 12dp 垂直内边距，
             *   内容区只有 32，所以那边必须用 28dp。两处的座尺寸**故意不同**，
             *   因为约束不同；共用同一个值会撑破其中一边。）
             *
             * 【图标本体不变】仍取 `DhDesign.Size.rowIcon`(20dp)，只是多了一圈浅底 ——
             * 行的信息密度、文字基线、图标视觉重量都不变。
             */
            IconSeat(
                iconRes = iconRes,
                sizeDp = IconSeatSize.SM,
                shape = IconSeatShape.CIRCLE,
                iconSizeDp = DhDesign.Size.rowIcon.value.toInt(),
                tint = iconTint ?: p.sub
            )
            Spacer(Modifier.width(DhDesign.Space.md.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = fg,
                style = t.rowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (twoLine) {
                Text(
                    text = desc!!,
                    modifier = Modifier.padding(top = DescGap),
                    color = p.sub,
                    style = t.sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!value.isNullOrEmpty()) {
            Spacer(Modifier.width(DhDesign.Space.sm.dp))
            Text(
                text = value,
                color = p.sub,
                style = t.sub,
                maxLines = 1,
                textAlign = TextAlign.End
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(DhDesign.Space.sm.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(DhDesign.Space.xs.dp))
            DhKit.Icon(
                Icons.IC_CHEVRON_RIGHT,
                DhDesign.Size.iconSm.value,
                p.sub.copy(alpha = 0.62f)
            )
        }
    }
}

/** 行与行之间的发丝分隔线（左对齐内容缩进由调用方决定）。 */
@Composable
fun DhDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DhDesign.Size.hairline)
            .background(DhTokens.palette.line)
    )
}

/**
 * 分组容器：一个卡片装一整组行。
 *
 * 【为什么需要】「每行一个卡片」与「完全没有分组」是两种常见错误。
 *   设计要求是：**组**用卡片（弱卡：card 底 + 1dp line 描边 + 圆角 16，无阴影），
 *   组内用发丝线分隔。这样一页里卡片数量是 3–5 个，而不是 15 个。
 */
@Composable
fun DhGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    /**
     * 标题下方是否显示一条发丝线（组内标题与内容之间）。
     *
     * 【为什么需要】设置页的「折叠组」把标题做成可点的展开箭头，
     *   标题与内容之间需要一条线把两者分开；而普通分组（标题在卡片外）不需要。
     */
    modifierTitleDivider: Boolean = false,
    /**
     * 【折叠能力】非 null 时标题变成可点区域（带展开箭头），内容随状态显示/隐藏。
     *
     * 【为什么补在 DhGroup 而不是新建组件】本 App 的 `DhKit.Card` 有 53 处调用、
     *   其中 **47 处用了折叠标题**（collapsibleTitle）。要让 Card 转发到规范实现，
     *   规范实现就必须具备折叠能力 —— 否则「统一」只能停在 56% 的调用点上，
     *   而剩余 47 处会继续用旧样式，等于把「同一语义两处实现」原样保留。
     *
     * @param collapsibleTitle 折叠时显示的标题；null = 不折叠
     * @param titleIcon        标题左侧图标（0 = 不显示）
     * @param expandState      外部提供的「当前是否展开」；null = 组件内部自管状态
     * @param onToggle         用户点标题时回调（宿主据此落盘）
     *
     * 【为什么展开态要能从外部传入】`DhKit.Card` 有一个既有能力：
     *   用户的展开/收起会写进 `PetPrefs.setCardOpen`，下次进页面保持原样。
     *   要让 Card 转发到本组件而**不丢这个行为**，展开态就不能只由内部 remember 掌握 ——
     *   必须允许宿主（Card）把读到的持久化值传进来、把变化回调出去。
     */
    collapsibleTitle: String? = null,
    titleIcon: Int = 0,
    expandState: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    /**
     * 卡片下方是否留组间距（默认 true）。
     *
     * 【为什么需要】`DhKit.Card` 有 6 处传 `spacingBottom = false`（连排的最后一张不要留白，
     *   否则底部会多出一条空隙）。要让它转发到本组件，这个能力必须存在。
     */
    spacingBottom: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = DhTokens.palette
    val t = DhTokens.type
    val collapseName = collapsibleTitle ?: title
    // 内部状态：宿主没传 expandState 时自己管；传了就以宿主的为准。
    var innerOpen by remember(collapseName) { mutableStateOf(true) }
    val open = expandState ?: innerOpen
    fun setOpen(v: Boolean) {
        if (expandState == null) innerOpen = v
        onToggle?.invoke(v)
    }
    val shape = RoundedCornerShape(DhDesign.Radius.lg.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (spacingBottom) DhDesign.Space.cardGap.dp else 0.dp)
    ) {
        // 【两种标题形态】
        //   ① 普通分组（collapsibleTitle == null）：标题在卡片**外面**，label 档小字；
        //   ② 折叠分组（collapsibleTitle != null）：标题在卡片**里面**、可点、带箭头，
        //      与内容之间加一条发丝线 —— 这是 Card 的既有形态，语义上"标题属于这张卡"。
        val collapsible = collapsibleTitle != null
        if (!collapsible && !title.isNullOrEmpty()) {
            Text(
                text = title,
                modifier = Modifier.padding(
                    start = DhDesign.Space.xs.dp,
                    bottom = DhDesign.Space.sm.dp
                ),
                color = p.sub,
                style = t.label
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(p.card)
                .border(DhDesign.Size.hairline, p.line, shape)
        ) {
            if (collapsible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable { setOpen(!open) }
                        .padding(
                            horizontal = DhDesign.Space.lg.dp,
                            vertical = DhDesign.Space.md.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (titleIcon != 0) {
                        DhKit.Icon(titleIcon, DhDesign.Size.iconMd.value, p.sub)
                        Spacer(Modifier.width(DhDesign.Space.iconGap.dp))
                    }
                    Text(
                        text = collapseName ?: "",
                        modifier = Modifier.weight(1f),
                        color = p.title,
                        style = t.section
                    )
                    // 箭头：收起指下、展开指上（与旧版语义一致）
                    // 【与旧 DhKit.Card 一致】原先是文字字形「︿ / ﹀」，与 Card 的矢量
                    //   IC_CHEVRON_DOWN + 180° 旋转不是同一种视觉。Card 转发到本组件后，
                    //   47 处折叠卡的箭头会一起换形状 —— 统一改成矢量箭头 + 旋转，
                    //   保证转发前后逐像素一致（DhGroup 此前无外部调用方，改动无副作用）。
                    DhKit.Icon(
                        iconRes = Icons.IC_CHEVRON_DOWN,
                        sizeDp = DhDesign.Size.iconSm.value,
                        color = p.sub,
                        modifier = Modifier.rotate(if (open) 180f else 0f)
                    )
                }
                if (modifierTitleDivider) {
                    DhDivider(
                        modifier = Modifier.padding(horizontal = DhDesign.Space.lg.dp)
                    )
                }
            }
            if (!collapsible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = DhDesign.Space.lg.dp,
                            vertical = DhDesign.Space.xs.dp
                        ),
                    content = content
                )
            } else {
                // 【展开/收起动画】与旧 `DhKit.Card` 逐参数一致：
                //   展开 `DhAnim.CARD_EXPAND`（enter 220ms，减速）+ `CARD_FADE_IN`；
                //   收起 `DhAnim.CARD_COLLAPSE`（exit 180ms，加速）+ `CARD_FADE_OUT` ——
                //   「离开比进入快」是 View 侧 `UiKit.expand` 的既有手感，这里保持。
                //   【expandFrom / shrinkTowards 必须写死 Top】Compose 的默认值虽然是 Top，
                //   但收起方向若不写，实测会残留一帧高度，表现为"抖一下"（旧的 Card 注释
                //   记录过这个坑）。
                //
                //   【为什么这一步是 47 处折叠卡能否转发的关键】DhGroup 原先直接显隐，
                //   转发过去会让设置页最常点的交互"啪"一下跳变。补上这段之后，
                //   `DhKit.Card` 的折叠分支才可以安全转发到本组件。
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
                    // 【内边距沿用旧 DhKit.Card 的折叠形态】旧实现是
                    //   `padding(horizontal = PAD_CARD).padding(bottom = PAD_CARD)`，
                    //   即「左右 16 / 下 16 / 上 0」—— 上方不留白，是因为标题行自己带了
                    //   `bottom = 12dp` 的内边距，两者相接刚好。
                    //   DhGroup 原先给的是 `vertical = xs(4)`（上下各 4），转发后内容会
                    //   上移 4、下缩 12，47 处折叠卡的观感会变。这里显式对齐旧值，
                    //   保证转发前后逐像素一致。
                    //   【注意】这是继承自旧实现的排版，不是"理想值"；若将来要把
                    //   `vertical = xs` 这套规范真正推行到折叠卡，应作为独立的设计决策，
                    //   而不是夹在转发里顺手改掉。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DhDesign.Space.lg.dp)
                            .padding(bottom = DhDesign.Space.lg.dp),
                        content = content
                    )
                }
            }
        }
    }
}

/**
 * 可点 Chip（26dp 药丸，用于筛选/选择）。
 *
 * 【与 Tag 的区别（必须分开）】Chip 可点、表达"我在筛什么"；Tag 不可点、表达"这条数据是什么"。
 *   改造前两者共用 `DhForm.Chip`，尺寸随内容变，导致「看起来一样的两个东西，一个能点一个不能点」。
 */
@Composable
fun DhChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val p = DhTokens.palette
    val t = DhTokens.type
    val bg = if (selected) p.chatChipOn else p.chatChipOff
    val fg = if (selected) p.accent else p.chatChipFg
    Box(
        modifier = modifier
            .height(ChipHeight)
            .clip(RoundedCornerShape(DhDesign.Radius.pill.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.pressScaleClick(onClick) else Modifier)
            .padding(horizontal = ChipPadH),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = fg, style = t.caption, maxLines = 1)
    }
}

/** 不可点的状态标签（20dp，圆角 8）。 */
@Composable
fun DhTag(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    val p = DhTokens.palette
    val t = DhTokens.type
    Box(
        modifier = modifier
            .height(TagHeight)
            .clip(RoundedCornerShape(DhDesign.Radius.sm.dp))
            .background(p.soft)
            .padding(horizontal = TagPadH),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = tint ?: p.sub, style = t.label, maxLines = 1)
    }
}

/**
 * 空态「压图铺底」的不透明度。
 *
 * 【为什么是 0.88，而不是随手取一个】与顶栏可读性带 [DhKit.hasPhotoBackdrop] 同一口径：
 *   那个 0.88 是按最坏情况（照片在文字背后是纯黑）反算出来的，带底合成后
 *   TITLE 7.6:1、`UiKit.subOnPhoto()` 4.95:1，两项都过 AA 且留有余量。
 *   空态只是把同一条带子铺成一块板，取同一个数才与顶栏观感一致。
 */
private const val STATE_PLATE_ALPHA = 0.88f

/** 铺底板的左右视觉内缩（dp）。12dp 让板边与文字之间留出呼吸，不与文字贴着。 */
private const val STATE_PLATE_INSET_DP = 12

/** 铺底板的圆角（dp）。与卡片同档，读作"一块板"而不是"一条色带"。 */
private const val STATE_PLATE_CORNER_DP = 22

/**
 * 给空态内容块铺一层「可读底板」——**只在有用户背景图时生效**。
 *
 * 【为什么用 drawBehind 而不是套一层 Card / Box 背景】这是本函数唯一的硬约束：
 *   **没设背景图时一个像素都不许变**。若改成包一层面板，会引入内边距变化、
 *   圆角裁切、阴影，进而让所有空态的落点和换行都可能变；而 drawBehind 只画不改布局，
 *   无图时直接 return this（原样返回），有图时才多一层绘制 —— 逐像素零风险。
 *
 * 【为什么色底用 [UiKit.BG] 而不是调色板 SOFT】SOFT 只比页面底色亮一档，
 *   压在深色插画上仍是"灰得发虚"；BG@88% 是顶栏带已验证过的那块雾面玻璃，
 *   同一块底色、同一个透明度，两个位置的观感才连得起来。
 */
private fun Modifier.stateReadablePlate(onPhoto: Boolean): Modifier {
    if (!onPhoto) {
        return this
    }
    val plate = Color(UiKit.BG).copy(alpha = STATE_PLATE_ALPHA)
    val insetPx = STATE_PLATE_INSET_DP.dp
    val cornerPx = STATE_PLATE_CORNER_DP.dp
    return this.drawBehind {
        val inset = insetPx.toPx()
        val w = size.width - inset * 2.0f
        if (w <= 0.0f || size.height <= 0.0f) {
            return@drawBehind
        }
        drawRoundRect(
            color = plate,
            topLeft = Offset(inset, 0.0f),
            size = Size(w, size.height),
            cornerRadius = CornerRadius(cornerPx.toPx())
        )
    }
}

/**
 * 状态块：空 / 错误 / 无权限 / 加载**共用同一个骨架**。
 *
 * 【为什么必须统一】改造前每个页面自己拼空状态：有的只有一行灰字、有的有大图标 + 三个按钮。
 *   同一个 App 里"没东西"的样子有 6 种，用户每次都要重新判断"这是空的还是出错了"。
 *
 * 骨架：56dp 图标座（soft 底、12 圆角）→ section 标题 → sub 正文（最多 2 行）→ 恰好一个动作。
 */
@Composable
fun DhState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    iconRes: Int = 0,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    tone: Color? = null
) {
    val p = DhTokens.palette
    val t = DhTokens.type
    // 【压图分流】有用户背景图时：内容块铺一层同顶栏口径的雾面板，
    //   副文本换成 subOnPhoto（SUB 在 BG 上只有 4.66:1，被照片染一点就跌破 AA）。
    //   无图时 onPhoto=false：铺底不画、副文本仍是 p.sub —— 与改造前逐像素一致。
    val onPhoto = DhKit.hasPhotoBackdrop()
    val subTint = if (onPhoto) Color(UiKit.subOnPhoto()) else p.sub
    Column(
        modifier = modifier
            .fillMaxWidth()
            .stateReadablePlate(onPhoto)
            .padding(
                horizontal = DhDesign.Space.xl.dp,
                vertical = DhDesign.Space.xxl.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (iconRes != 0) {
            // 【v3·清透轻拟物】空态图标座改为 **IconSeat 的圆形浅底**：
            //   与页面里其它"图标 + 浅色圆"同一配方（圆形 / 比卡片亮一档 / e1 柔和阴影 / 顶部高光）。
            //   改造前是手拼的 `Box + 圆角 lg/16 + soft 底`，形状是方块 —— 与用户定案的
            //   "图标配浅色圆形背景"不一致，且空态是全 App 最显眼的一处图标。
            //   56dp 对应 IconSeatSize.LG，图标走 iconLg(24)，尺寸与原实现一致。
            IconSeat(
                iconRes = iconRes,
                sizeDp = IconSeatSize.LG,
                shape = IconSeatShape.CIRCLE,
                tint = tone ?: subTint
            )
            Spacer(Modifier.height(DhDesign.Space.md.dp))
        }
        Text(
            text = title,
            color = p.title,
            style = t.section,
            textAlign = TextAlign.Center
        )
        if (!body.isNullOrEmpty()) {
            Spacer(Modifier.height(DhDesign.Space.xs.dp))
            Text(
                text = body,
                color = subTint,
                style = t.sub,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!actionText.isNullOrEmpty() && onAction != null) {
            Spacer(Modifier.height(DhDesign.Space.lg.dp))
            // 【本轮·改为 PRIMARY】设计稿 §3：空/错/无权限态「恰好一个动作」，
            //   而「每屏只允许一个主按钮」的前提是那个唯一动作就该是主按钮。
            //   改造前这里用 SECONDARY（card 底 + 描边），在整屏只有它一个可点元素的情况下，
            //   它看起来像"次要选项"，用户不确定该不该点 —— 这是空态最常被抱怨的地方。
            DhButton(text = actionText, onClick = onAction, style = DhButtonStyle.PRIMARY)
        }
    }
}

/**
 * 骨架行：加载态占位（不做扫光渐变，避免"炫技"观感）。
 *
 * 【压图分流（r23）】有用户背景图时，占位条从 `skeleton`（= 调色板 SOFT）换成 `line` 一档。
 *   【为什么必须换】骨架块通常铺在同为 SOFT@88% 的可读面板上（见
 *   `DhKit.Modifier.readableSheet`）；SOFT 画在 SOFT 上等于隐形，
 *   用户只看到一块空面板、读不出"这边在加载"。而 `line` 是发丝线的颜色，
 *   在雾面上刚好读得出「这里将来有字」，又不会亮得像真内容。
 *   【没图时为什么不变】无图时骨架铺在页面底色 BG 上，SOFT 与 BG 本来就有一档差，
 *   换成 line 反而会让所有老用户的骨架变重。故只在 onPhoto 时切换。
 */
@Composable
fun DhSkeletonRow(modifier: Modifier = Modifier, lines: Int = 2) {
    val p = DhTokens.palette
    val bar = if (DhKit.hasPhotoBackdrop()) p.line else p.skeleton
    Column(modifier = modifier.fillMaxWidth().padding(vertical = RowPadV)) {
        Box(
            Modifier
                .fillMaxWidth(0.42f)
                .height(13.dp)
                .clip(RoundedCornerShape(DhDesign.Radius.sm.dp))
                .background(bar)
        )
        if (lines > 1) {
            Spacer(Modifier.height(DhDesign.Space.xs.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.72f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(DhDesign.Radius.sm.dp))
                    .background(bar)
            )
        }
    }
}

/**
 * 行内图标按钮（命中区 48dp、视觉 40dp）。
 *
 * 【为什么再包一层】`DhKit.IconButton` 的命中区固定 40dp（HIT），
 *   与设计稿「图标 40dp 命中区 + 48dp 最小触摸目标」只差在**透明扩展**这一层；
 *   这里显式给出 48dp，并且把无障碍名称做成必需参数（漏掉就读屏念"按钮"）。
 */
@Composable
fun DhIconButton(
    iconRes: Int,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    val p = DhTokens.palette
    Box(
        modifier = modifier
            .size(DhDesign.Size.minTouch)
            .clip(RoundedCornerShape(DhDesign.Radius.pill.dp))
            .semantics { contentDescription = name }
            .pressable(onClick),
        contentAlignment = Alignment.Center
    ) {
        DhKit.Icon(iconRes, DhDesign.Size.iconMd.value, tint ?: p.title)
    }
}
/**
 * 圆角/圆形「图标座」：一块柔色底 + 居中图标。
 *
 * 【为什么需要这个构件】审计（docs/VISUAL_AUDIT.md 第七节）发现：同一个
 * 「柔色方块 + 居中图标」的视觉，在全仓有 **13 种组合、15 处**各自手写，
 * 尺寸从 20dp 到 72dp 毫无规律（20/26/32/34/38/40/46/48/52/72），
 * 形状在「圆角 8 / 圆角 12 / 药丸」之间随意切换。
 * 后果不是"某处难看"，而是**同一类元素在不同页面看起来不是一套东西** ——
 * 空状态的图标座、权限行的图标座、联系人卡片的小头像座，本该是同一个语汇。
 *
 * 【分档依据·来自实测用量】尺寸频次（15 处）：
 *   34×2、46×2、52×2、其余 20/26/32/38/40/48/72 各 1。
 * 由此定三档，覆盖「列表行内 / 卡片内 / 空状态」三种典型场景：
 *   · [SM] 32dp —— 列表行内、小卡片内（原 26/32/34 归此档）
 *   · [MD] 40dp —— 卡片内的主要图标座、设置项（原 38/40/46 归此档）
 *   · [LG] 56dp —— 空状态 / Hero 区（原 48/52 归此档）
 * 超过 56 的（如 72dp 人偶大图标）**不归此构件**，属 Hero 特例，保留原值。
 *
 * 【形状两选一】[Shape.ROUNDED]（12dp 圆角，默认）/ [Shape.CIRCLE]（药丸/正圆）。
 * 审计里 9 处用药丸、4 处用 8~12dp 圆角 —— 两者语义不同：
 *   圆角 = 「一块方形底板」；正圆 = 「一个头像/指示点」。都由调用方按语义选。
 *
 * 【底色与图标色】默认 `palette.soft` + `palette.sub`，与既有 15 处的一致；
 * 需要强调时由调用方覆写 [bg] / [tint]（例如人偶头像用 emote 色）。
 *
 * 【不做什么】不自带点击。图标座在多数场景是纯装饰（如空状态），
 * 需要可点时由调用方在外面套 `pressable` —— 这样「点哪里」由页面决定，
 * 构件本身不预设命中区，避免又造出一个"看起来能点但其实不响应"的元素。
 */
object IconSeatShape {
    /** 圆角矩形（12dp）。用于「一块底板」语义。 */
    const val ROUNDED = 0
    /** 正圆 / 药丸。用于「头像、指示点」语义。 */
    const val CIRCLE = 1
}

/** [IconSeat] 的三档尺寸（dp）。取值见该构件的 KDoc「分档依据」。 */
object IconSeatSize {
    /** 32dp：列表行内 / 小卡片。 */
    const val SM = 32
    /** 40dp：卡片内主要图标座。 */
    const val MD = 40
    /** 56dp：空状态 / Hero。 */
    const val LG = 56
}

/**
 * 图标座。见 [IconSeatSize] / [IconSeatShape]。
 *
 * @param iconRes 图标资源（`Icons.IC_*`）；0 表示只画底板不画图标（少见）
 * @param sizeDp  见 [IconSeatSize]；也可传特例值，但应先确认不属于三档
 * @param shape   见 [IconSeatShape]
 * @param iconSizeDp 图标本体尺寸；默认按座大小自动取（32→16 / 40→20 / 56→24）
 * @param bg      底板色，默认 `palette.soft`
 * @param tint    图标色，默认 `palette.sub`
 */
@Composable
fun IconSeat(
    iconRes: Int,
    modifier: Modifier = Modifier,
    sizeDp: Int = IconSeatSize.MD,
    shape: Int = IconSeatShape.ROUNDED,
    iconSizeDp: Int = 0,
    bg: Color? = null,
    tint: Color? = null
) {
    val p = DhTokens.palette
    val circle = shape == IconSeatShape.CIRCLE
    val sh = if (circle) {
        RoundedCornerShape(DhDesign.Radius.pill.dp)
    } else {
        RoundedCornerShape(DhDesign.Radius.md.dp)
    }
    // 【图标尺寸随座大小走】三档对应 16/20/24，正好落在既有图标阶梯上。
    //   调用方显式传 iconSizeDp 时以传入为准（少数特例需要）。
    // 【必须显式 Float】DhKit.Icon 的 sizeDp 是 Float，而三个分支的 `.value`
    //   被推断成 `Number & Comparable<*>`（Dp.value 的泛型形态），
    //   不统一成 Float 会报 "Argument type mismatch"。CI 抓到过。
    val icoDp: Float = if (iconSizeDp > 0) {
        iconSizeDp.toFloat()
    } else {
        when {
            sizeDp <= IconSeatSize.SM -> DhDesign.Size.iconSm.value.toFloat()
            sizeDp <= IconSeatSize.MD -> DhDesign.Size.iconMd.value.toFloat()
            else -> DhDesign.Size.iconLg.value.toFloat()
        }
    }

    /* ================= 【v3·清透轻拟物】圆形浅底配方 =================
     *
     * 用户定案：「图标统一使用精致的线性图标，合理搭配浅色圆形背景」。
     *
     * 【配方四层，缺一层就变成"扁色块"】
     *   ① **圆**：`Radius.pill`（999），不是圆角方块 —— 这是本轮语义改变的核心。
     *   ② **底**：比所在卡片**亮一档**。浅色下 `cardUp` 比 `card` 亮；
     *      深色下 `cardUp` 同样比 `card` 亮（`UiKit.cardUp()` 是 CARD 叠白透明度）。
     *      调用方显式传 `bg` 时尊重调用方（用于 accent 底等特例）。
     *   ③ **柔和阴影**：`e1(6dp)`。轻拟物的"浮起小球"感靠它，不是靠描边。
     *      阴影必须先于 clip —— 先裁后投影会把阴影一起裁没（与 DhDialogHost 同一坑）。
     *   ④ **顶部内高光**：一道极淡的白色垂直渐变（顶部 34% 不透明度），
     *      模拟光源在上方；**深色模式下更强**，因为深色底自身对比低，
     *      不加高光就只是一个"亮一点的圆"，没有体积。
     *
     * 【为什么用亮度自适应而不是读 ThemeManager】组件层不应依赖 Context / 主题模式；
     *   而"底够不够亮"本身可以从底色亮度推断 —— 深色底必然亮度低。
     *   这样浅色 / 深色 / 纯黑 / 莫奈取色四种情况都自动成立，不需要各写一遍。
     */
    val base: Color = bg ?: if (circle) p.cardUp else p.soft
    // 判定"当前是深底"：用感知亮度（0.2126R + 0.7152G + 0.0722B），阈值 0.42。
    val lum = 0.2126f * base.red + 0.7152f * base.green + 0.0722f * base.blue
    val onDark = lum < 0.42f
    // 高光强度：浅色克制（0.34），深色加强（0.55）—— 深色没有这道光就失去体积。
    val hiAlpha = if (onDark) 0.55f else 0.34f
    // 深色下再叠一点极淡的白（compositeOver 是 Compose 官方的 alpha 合成，
    //   等价于"把 10% 白压在底色上"）。这样圆比卡片亮一档、"浮"起来而不是"糊"在卡片里。
    //   【为什么不用自定义混色函数】工程里没有这个辅助函数（我一开始凭空写了一个
    //   `blendWhite`，是错的）；直接用手上的官方 API 更稳。
    val lifted = if (onDark) Color.White.copy(alpha = 0.10f).compositeOver(base) else base

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            // ③ 阴影先于 clip（顺序不可换）
            .then(
                if (circle) {
                    Modifier.shadow(
                        elevation = DhDesign.Elevation.e1,
                        shape = sh,
                        clip = false,
                        ambientColor = p.line,
                        spotColor = p.line
                    )
                } else {
                    Modifier
                }
            )
            .clip(sh)
            .background(lifted)
            // ④ 顶部内高光：垂直渐变，只在圆形形态、且有底色时叠加
            .then(
                if (circle) {
                    Modifier.background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = hiAlpha),
                            0.34f to Color.White.copy(alpha = hiAlpha * 0.28f),
                            0.6f to Color.Transparent
                        )
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (iconRes != 0) {
            DhKit.Icon(iconRes, icoDp, tint ?: p.sub)
        }
    }
}
