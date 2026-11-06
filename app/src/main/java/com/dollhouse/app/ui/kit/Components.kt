package com.dollhouse.app.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import com.dollhouse.app.ui.theme.Icons

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
private val FieldHeight = 40.dp
private val ChipHeight = 26.dp
private val TagHeight = 20.dp
private val StateSeat = 56.dp
private val LoadingSpinner = 16.dp
private val LoadingStroke = 2.dp
private val DescGap = 2.dp
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
    val shape = RoundedCornerShape(DhDesign.Radius.md.dp)
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
    onDone: (() -> Unit)? = null
) {
    val p = DhTokens.palette
    val t = DhTokens.type
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fill by animateColorAsState(
        targetValue = if (focused) p.card else p.field,
        animationSpec = androidx.compose.animation.core.tween(DhDesign.Motion.micro),
        label = "field-fill"
    )
    val stroke by animateColorAsState(
        targetValue = if (focused) p.accent else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(DhDesign.Motion.micro),
        label = "field-stroke"
    )
    val shape = RoundedCornerShape(DhDesign.Radius.md.dp)
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
            .then(
                if (stroke.alpha > 0f) Modifier.border(DhDesign.Size.hairline, stroke, shape) else Modifier
            )
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
                modifier = Modifier.fillMaxWidth(),
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
            DhKit.Icon(iconRes, DhDesign.Size.rowIcon.value, iconTint ?: p.sub)
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
    content: @Composable ColumnScope.() -> Unit
) {
    val p = DhTokens.palette
    val t = DhTokens.type
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrEmpty()) {
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
                .clip(RoundedCornerShape(DhDesign.Radius.lg.dp))
                .background(p.card)
                .border(
                    DhDesign.Size.hairline,
                    p.line,
                    RoundedCornerShape(DhDesign.Radius.lg.dp)
                )
                .padding(horizontal = DhDesign.Space.lg.dp, vertical = DhDesign.Space.xs.dp),
            content = content
        )
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = DhDesign.Space.xl.dp,
                vertical = DhDesign.Space.xxl.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (iconRes != 0) {
            Box(
                modifier = Modifier
                    .size(StateSeat)
                    .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                    .background(p.soft),
                contentAlignment = Alignment.Center
            ) {
                DhKit.Icon(iconRes, DhDesign.Size.iconLg.value, tone ?: p.sub)
            }
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
                color = p.sub,
                style = t.sub,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!actionText.isNullOrEmpty() && onAction != null) {
            Spacer(Modifier.height(DhDesign.Space.lg.dp))
            DhButton(text = actionText, onClick = onAction, style = DhButtonStyle.SECONDARY)
        }
    }
}

/** 骨架行：加载态占位（不做扫光渐变，避免"炫技"观感）。 */
@Composable
fun DhSkeletonRow(modifier: Modifier = Modifier, lines: Int = 2) {
    val p = DhTokens.palette
    Column(modifier = modifier.fillMaxWidth().padding(vertical = RowPadV)) {
        Box(
            Modifier
                .fillMaxWidth(0.42f)
                .height(13.dp)
                .clip(RoundedCornerShape(DhDesign.Radius.sm.dp))
                .background(p.skeleton)
        )
        if (lines > 1) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.72f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(DhDesign.Radius.sm.dp))
                    .background(p.skeleton)
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