package com.dollhouse.app.ui.compose

import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.theme.Design

/** 紧凑尾部图标的视觉尺寸（dp）：与正文行高相当，不会撑高输入框。 */
private const val TRAILING_VISUAL_DP = 22

/**
 * 单行输入框的最小高度（dp）—— 直接取 `DhDesign.Size.fieldH`（设计稿 40dp）。
 *
 * 【M-收口】上一版这里是文件内常量，因为当时 `DhDesign.Size` 还没有这个槽位。
 *   现已补 `fieldH`，并与规范源 `ui/kit/Components.kt` 共用同一个 token ——
 *   避免「改一处、另一处不动」导致同一输入框在两个页面高度不同。
 */
private val FIELD_H = com.dollhouse.app.ui.design.DhDesign.Size.fieldH

/**
 * 【职责】Compose 侧的表单 / 弹层构件：输入框、标签、徽标、主色药丸、分隔间距、确认弹窗。
 *
 * 【为什么必须单独一层】`DhKit` 只覆盖「展示类」构件（卡片 / 行 / 顶栏 / 开关 / 图标）。
 *   P2 的供应商与模型页面全是**表单**：输入框、保存/取消、删除二次确认。
 *   若每个页面自己 `BasicTextField(...)` 一遍，圆角 / 内边距 / 字号 / 提示色迟早分叉 ——
 *   规格书禁止的正是这种「同一 App 两种输入框」。故与 [DhKit] 同口径：尺寸只在这里出现一次。
 *
 * 【参数来源】不是拍的，逐条抄自既有 View 实现：
 *   - 输入框：FIELD 底 / 圆角 12 / 内边距 12 / 15sp / TITLE 字色 / SUB 提示色 ← `UiKit.field`
 *   - 标签：12sp SUB / 上 12 下 4 ← `ApiPageKit.labeledInput`
 *   - 徽标：12sp / 内边距 8×3 / 圆角 999 ← `UiKit.badge`
 *   - 主色药丸：12sp 粗 / ON_ACC / 内边距 16×9 / 圆角 999 ← `UiKit.primaryChip`
 *   - 弹窗：卡片底 / 圆角 16 / 内边距 18 / 标题 16sp 粗 / 正文 12sp SUB ← `UiKit.showDialog`
 */
object DhForm {

    /* ============================ 输入框 ============================ */

    /**
     * 单行输入框（自带标签 + 提示）。← `ApiPageKit.labeledInput`。
     *
     * @param label    左上角小标签；null / 空则不占位
     * @param hint     占位提示（输入为空时显示）
     * @param password true 时做密码遮罩（密钥字段用）
     * @param trailing 输入框右侧附加内容（如密钥框的「显示 / 隐藏」）；null 则不占位
     * @param imeAction 软键盘回车键的语义（默认「完成」；搜索框传 [ImeAction.Search]）
     */
    @Composable
    fun Input(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        label: String? = null,
        hint: String = "",
        password: Boolean = false,
        singleLine: Boolean = true,
        keyboard: KeyboardType = KeyboardType.Text,
        imeAction: ImeAction = ImeAction.Done,
        trailing: (@Composable () -> Unit)? = null,
        /**
         * 尾部图标**不参与行高**（v0.2.5）。
         *
         * 【为什么需要这个开关】`trailing` 里如果放的是通用 [DhKit.IconButton]，它的
         *   可点区域按 Material 无障碍要求被撑到 48dp。48dp 是**布局尺寸**，不是视觉尺寸：
         *   放进这个输入框后，Row 的高度由它决定，输入框从约 44dp 被拉到 48dp 以上，
         *   圆角框于是被拉成「偏长的椭圆」，而文字只有 20dp 高、默认贴顶，
         *   下方就空出一大截白 —— 用户看到的就是搜索框形状不对、字偏上。
         *   【本开关做什么】为 true 时，尾部内容用 `Modifier.size` 定死在视觉尺寸，
         *   不再要求 48dp 下限。代价是这一点点的点击区域变小，但它是「框内的清空叉」，
         *   周围全是输入框本身，误触风险低；这与 M3「优先扩大命中区」不冲突 ——
         *   该要求针对的是孤立的图标按钮。
         */
        compactTrailing: Boolean = false
    ) {
        val c = DhTokens.colors
        // 【聚焦态·本轮补】改造前本框**聚焦时什么都不变**：底恒为 field、无描边。
        //   于是多框表单里用户看不出「现在激活的是哪一个」，这是设计稿明令要求的一环。
        //   规范实现（聚焦 → 底 field→card、描边 Transparent→accent、160ms）本来只存在于
        //   `ui/kit` 的 DhField 里，而它库外 0 调用 —— 等于规范没落地。
        //   这里把同一套行为补进**真正被 53 处调用的** DhForm.Input，参数与签名零改动。
        //
        // 【token 口径】颜色取规范名 `DhTokens.palette`，与 DhField 完全同源
        //   （Palette.fromUiKit 与 DhColors.fromUiKit 读同一批 UiKit 字段，值相等）。
        //   时长取 `DhDesign.Motion.micro`（160），描边粗取 `DhDesign.Size.hairline`（1dp），
        //   圆角取 `DhDesign.Radius.md`（12）—— 不写裸值。
        val p = DhTokens.palette
        val focusSource = remember { MutableInteractionSource() }
        val focused by focusSource.collectIsFocusedAsState()
        val fill by animateColorAsState(
            targetValue = if (focused) p.card else p.field,
            animationSpec = tween(DhDesign.Motion.micro),
            label = "dhform-input-fill"
        )
        val stroke by animateColorAsState(
            targetValue = if (focused) p.accent else Color.Transparent,
            animationSpec = tween(DhDesign.Motion.micro),
            label = "dhform-input-stroke"
        )
        val fieldShape = RoundedCornerShape(DhDesign.Radius.md.dp)
        // 【高度对齐设计稿 40dp】原来高度全靠上下各 12dp 内边距把文字撑出来（≈44dp）。
        //   这里改成：单行且无尾部内容时**定高 40**；其余情况只给 40 的下限。
        //   【为什么不一律定高】带 trailing 的 6 处里，IconButton 的命中区是 48dp，
        //   定高 40 会把它裁掉（无障碍与可点性双退化）。用 defaultMinSize 让 48dp 的
        //   那几处维持原高度，其余 47 处收敛到 40 —— 既对齐设计稿，又不碰命中区。
        val fixedHeight = singleLine && trailing == null
        // 【定高时上下不留内边距】40dp 已经由 Modifier.height 定死，再叠 8dp 上下内边距
        //   会把文字挤进 24dp 的窄条里（大字号时可读性退化）。留 0、改由 Box 居中承担，
        //   与 DhField 的口径一致（它也是定高 + 只有水平内边距）。
        val verticalPad = if (fixedHeight) 0.dp else DhDesign.Space.md.dp
        Column(modifier = modifier.fillMaxWidth()) {
            if (!label.isNullOrEmpty()) {
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 2.dp, top = 12.dp, bottom = 4.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (fixedHeight) {
                            Modifier.height(FIELD_H)
                        } else {
                            Modifier.defaultMinSize(minHeight = FIELD_H)
                        }
                    )
                    .clip(fieldShape)
                    .background(fill)
                    // 【描边按需加，与 DhField 同口径】`Modifier.border` 不参与测量
                    //   （它在组件边界内绘制，不改变尺寸），所以这里不是「为了防止框宽跳动」。
                    //   条件加的真正理由是：维持与规范实现 DhField 完全一致的写法与绘制路径，
                    //   未聚焦时（颜色为 Transparent）根本不必挂一层描边节点。
                    .then(
                        if (stroke.alpha > 0f) {
                            Modifier.border(DhDesign.Size.hairline, stroke, fieldShape)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = DhDesign.Space.md.dp, vertical = verticalPad),
                // 【必须把 interactionSource 交给 BasicTextField】否则它永远收不到 Focus 事件，
                //   collectIsFocusedAsState 会恒为 false —— 聚焦态等于没加。
                interactionSource = focusSource,
                textStyle = TextStyle(
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                ),
                singleLine = singleLine,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboard,
                    imeAction = if (singleLine) imeAction else ImeAction.Default
                ),
                cursorBrush = SolidColor(c.acc),
                decorationBox = { inner ->
                    if (trailing == null) {
                        // 【必须显式垂直居中】定高 40dp + 上下零内边距之后，Box 的默认
                        //   TopStart 会让 15sp/24dp 高的文字贴住框顶，下方空出一条 —
                        //   与带 trailing 那条分支（曾出现同款「字偏上」）是同一个坑。
                        //   提示文字与输入内容同在 CenterStart，两者基线上才对得齐。
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && hint.isNotEmpty()) {
                                Text(
                                    text = hint,
                                    color = c.sub,
                                    fontSize = UiKit.FS_BTN.sp,
                                    fontFamily = DhTokens.fonts
                                )
                            }
                            inner()
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 【修「椭圆框偏长、文字在上面、下面空一大串白」】
                            //   【根因】带 trailing 图标时走这条 Row 分支，而 Row 高度由
                            //     图标决定 —— [DhKit.IconButton] 为了满足触摸目标，视觉尺寸虽小
                            //     但布局尺寸是 48dp。输入框文字只有约 20dp 高，
                            //     于是 Row 被撑到 48dp。
                            //   【为什么文字会跑到上面】这个 Box 原来没有 `contentAlignment`，
                            //     默认是 TopStart：文字贴着顶部，48dp 减 20dp 的差全部堆在下方，
                            //     就是用户看到的「下面偏了一大串空白」。
                            //   【修法】Box 内容改为垂直居中，文字与图标在同一条中线上。
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (value.isEmpty() && hint.isNotEmpty()) {
                                    Text(
                                        text = hint,
                                        color = c.sub,
                                        fontSize = UiKit.FS_BTN.sp,
                                        fontFamily = DhTokens.fonts
                                    )
                                }
                                inner()
                            }
                            Spacer(Modifier.width(8.dp))
                            // 【见 compactTrailing 说明】为 true 时把尾部内容压回视觉尺寸，
                            //   不让它的 48dp 命中区把输入框整体撑高成椭圆。
                            if (compactTrailing) {
                                Box(Modifier.size(TRAILING_VISUAL_DP.dp)) { trailing() }
                            } else {
                                trailing()
                            }
                        }
                    }
                }
            )
        }
    }

    /** 多行输入框（自动换行、不遮罩）。 */
    @Composable
    fun InputMultiline(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        label: String? = null,
        hint: String = "",
        minHeight: Int = 96
    ) {
        val c = DhTokens.colors
        Column(modifier = modifier.fillMaxWidth()) {
            if (!label.isNullOrEmpty()) {
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 2.dp, top = 12.dp, bottom = 4.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            // 【本轮·补聚焦态，与单行 Input 同口径】改造前多行输入框聚焦时同样什么都不变，
            //   而它承载的是「人偶人格 / 系统提示词 / 记忆内容」这类**要长期编辑的长文本** ——
            //   用户在多行框里打字时更需要「当前在编辑谁」的确认感。
            //   视觉规则与单行完全一致：底 field→card、描边 Transparent→accent(1dp)、160ms。
            val focusSource = remember { MutableInteractionSource() }
            val focused by focusSource.collectIsFocusedAsState()
            val fill by animateColorAsState(
                targetValue = if (focused) c.card else c.field,
                animationSpec = tween(DhDesign.Motion.micro),
                label = "multiline-fill"
            )
            val stroke by animateColorAsState(
                targetValue = if (focused) c.acc else Color.Transparent,
                animationSpec = tween(DhDesign.Motion.micro),
                label = "multiline-stroke"
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(minHeight.dp)
                    .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                    .background(fill)
                    .then(
                        if (stroke.alpha > 0f) {
                            Modifier.border(DhDesign.Size.hairline, stroke, RoundedCornerShape(DhDesign.Radius.md.dp))
                        } else {
                            Modifier
                        }
                    )
                    .padding(12.dp),
                textStyle = TextStyle(
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                ),
                // 同单行：不传 interactionSource 的话聚焦态恒为 false。
                interactionSource = focusSource,
                cursorBrush = SolidColor(c.acc),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && hint.isNotEmpty()) {
                            Text(
                                text = hint,
                                color = c.sub,
                                fontSize = UiKit.FS_BTN.sp,
                                fontFamily = DhTokens.fonts
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }

    /* ============================ 小件 ============================ */

    /**
     * 语义色的淡底（用于「启用 / 禁用」这类状态徽标）。
     * 【口径】与 `UiKit.okBg()` / `UiKit.errBg()` 一致：`(color and 0xFFFFFF) or 0x1E000000`，
     *   即 30/255 ≈ 0.118 的不透明度 —— 切主题 / 莫奈时跟着语义色一起变。
     */
    fun semanticBg(color: Color): Color = color.copy(alpha = 0x1E / 255f)

    /** 分组小标题 / 字段说明。← `ApiPageKit.sectionTitle`。 */
    @Composable
    fun Label(text: String, modifier: Modifier = Modifier) {
        Text(
            text = text,
            modifier = modifier,
            color = DhTokens.colors.sub,
            fontSize = UiKit.FS_SUB.sp,
            fontFamily = DhTokens.fonts
        )
    }

    /**
     * 小字备注。← `ApiPageKit.note`（12sp SUB，行距 +3）。[color] 非空时用于状态提示（OK / ERR）。
     *
     * 【本轮·修「界面上出现星号」】全工程有 6 处备注写成 `"把这只人偶恢复成**刚装好软件时的样子**"`，
     *   作者是按 Markdown 写的；但 Compose 的 [Text] **不解析 Markdown**，
     *   `**` 会原样显示成两个星号，且粗体完全不生效 —— 用户看到的就是「**刚装好软件时的样子**」。
     *   这里补一层**极小的**粗体解析：把 `**xxx**` 切成「粗体段 + 普通段」的 [AnnotatedString]，
     *   其余标记（`*斜体*`、`#标题`）不做 —— 本工程文案里没人用，多解析一分就多一分误伤风险。
     *   未配对的 `**` 原样保留（宁可显示星号，也不吞掉用户文案）。
     */
    @Composable
    fun Note(text: String, modifier: Modifier = Modifier, color: Color? = null) {
        Text(
            text = boldMarkup(text),
            modifier = modifier.padding(start = 2.dp, top = 8.dp, end = 2.dp),
            color = color ?: DhTokens.colors.sub,
            fontSize = UiKit.FS_TINY.sp,
            fontFamily = DhTokens.fonts,
            lineHeight = (UiKit.FS_TINY + 5f).sp
        )
    }

    /**
     * 把 `**粗体**` 转成 [AnnotatedString]（[SpanStyle] + FontWeight.Bold）。
     *
     * 【边界】只认成对的 `**`；未配对时把剩余文本原样追加，不丢字。
     * 【为什么不用 Markdown 库】只为一处加粗引入一个解析器（含正则、链接、列表等）得不偿失，
     *   且第三方库对中文标点的边界处理与本工程惯用文案不一定吻合。
     */
    fun boldMarkup(text: String): AnnotatedString {
        if (!text.contains("**")) {
            return AnnotatedString(text)
        }
        return buildAnnotatedString {
            var i = 0
            var bold = false
            while (i < text.length) {
                val at = text.indexOf("**", i)
                if (at < 0) {
                    append(text.substring(i))
                    break
                }
                append(text.substring(i, at))
                bold = !bold
                if (bold) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                } else {
                    pop()
                }
                i = at + 2
            }
            // 未配对：push 过但没 pop，补一次，避免样式泄漏到后续段落。
            if (bold) {
                pop()
            }
        }
    }

    /** 紧凑状态标签。← `UiKit.badge`。 */
    @Composable
    fun Badge(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
        Text(
            text = text,
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            color = fg,
            fontSize = UiKit.FS_TINY.sp,
            fontFamily = DhTokens.fonts
        )
    }

    /** 小药丸（比 [OutlineChip] 更紧凑）。← `UiKit.chip`：圆角 10 / 内边距 10×6 / 12sp。 */
    @Composable
    fun Chip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
        val c = DhTokens.colors
        Text(
            text = text,
            modifier = modifier
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .background(c.chatChipBg)
                .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            color = c.chatChipFg,
            fontSize = UiKit.FS_CHIP.sp,
            fontFamily = DhTokens.fonts
        )
    }

    /** 可选中的等宽格子（模型类型 / 模态用）。← `ModelEditKit.paintChip`：选中实色主色 + ON_ACC 字，未选描边。 */
    @Composable
    fun SelectChip(
        text: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        dimmed: Boolean = false,
        onClick: () -> Unit
    ) {
        val c = DhTokens.colors
        Text(
            text = text,
            modifier = modifier
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .then(
                    if (selected) {
                        Modifier.background(c.chatChipFg)
                    } else {
                        Modifier
                            .background(c.card)
                            .border(1.dp, c.stroke, RoundedCornerShape(DhKit.R_ROW.dp))
                    }
                )
                .then(if (dimmed) Modifier.alpha(0.45f) else Modifier)
                .pressable(onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            color = if (selected) c.onAcc else c.title,
            fontSize = UiKit.FS_CHIP.sp,
            fontFamily = DhTokens.fonts
        )
    }

    /** 描边药丸（行内次操作）。← `UiKit.outlineChip`。 */
    @Composable
    fun OutlineChip(
        text: String,
        modifier: Modifier = Modifier,
        // 【r16·图标】可选前缀图标（Icons.IC_*）。默认 0 = 与改造前逐像素一致。
        iconRes: Int = 0,
        onClick: (() -> Unit)? = null
    ) {
        val c = DhTokens.colors
        val base = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
        if (iconRes == 0) {
            Text(
                text = text,
                modifier = base,
                color = c.title,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = DhTokens.fonts
            )
        } else {
            Row(modifier = base, verticalAlignment = Alignment.CenterVertically) {
                DhKit.Icon(iconRes = iconRes, sizeDp = Design.SZ_XS, color = c.title)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = text,
                    color = c.title,
                    fontSize = UiKit.FS_CHIP.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /** 主色药丸（行内主操作）。← `UiKit.primaryChip`（实色 acc + ON_ACC 字）。 */
    @Composable
    fun PrimaryChip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
        val c = DhTokens.colors
        Text(
            text = text,
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .background(c.acc)
                .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            color = c.onAcc,
            fontSize = UiKit.FS_CHIP.sp,
            fontFamily = DhTokens.fontsBold,
            fontWeight = FontWeight.Bold
        )
    }

    /* ============================ 四态提交按钮（v0.2.4） ============================ */

    /** 提交按钮的四种状态。 */
    enum class SubmitState { IDLE, LOADING, SUCCESS, ERROR }

    /**
     * 带状态的提交按钮：`idle → loading → success → idle`，失败转 [SubmitState.ERROR]。
     *
     * 【为什么需要】此前所有提交按钮都只是「点一下、立刻变成另一段文字」：
     *   用户在按下的那一刻拿不到任何「点到了没 / 在处理 / 成了没」的信号，
     *   于是会连点好几下（导入、导出这类操作尤其明显）。
     *   这里把三种反馈收进同一个按钮：转圈、对勾、警告，全部在**原位**变化，
     *   不弹 toast、不跳页，用户视线不用离开手指的位置。
     *
     * 【为什么不改业务逻辑】状态由调用方通过 [state] 传入 —— 按钮只负责画，
     *   什么时候算成功仍由各自的写入逻辑判定。这样不会出现「按钮说成功了但数据没写」。
     *
     * 【防重复触发】[SubmitState.LOADING] 时按钮不接受点击（不是禁用变灰，而是直接忽略），
     *   避免「点了没反应所以再点一次」造成的重复提交。
     */
    @Composable
    fun SubmitButton(
        text: String,
        state: SubmitState,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        val c = DhTokens.colors
        val bg = when (state) {
            SubmitState.ERROR -> c.err
            SubmitState.SUCCESS -> c.acc
            else -> c.acc
        }
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .then(
                    if (state == SubmitState.LOADING) Modifier
                    else Modifier.pressable(onClick)
                )
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                SubmitState.LOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = c.onAcc,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(7.dp))
                }
                SubmitState.SUCCESS -> {
                    Icon(
                        painter = painterResource(com.dollhouse.app.R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = c.onAcc,
                    )
                    Spacer(Modifier.width(7.dp))
                }
                SubmitState.ERROR -> {
                    Icon(
                        painter = painterResource(com.dollhouse.app.R.drawable.ic_warning),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = c.onAcc,
                    )
                    Spacer(Modifier.width(7.dp))
                }
                SubmitState.IDLE -> Unit
            }
            Text(
                text = when (state) {
                    SubmitState.LOADING -> "处理中…"
                    SubmitState.SUCCESS -> "完成"
                    SubmitState.ERROR -> "重试"
                    SubmitState.IDLE -> text
                },
                color = c.onAcc,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold,
                // 【不加行高】药丸高度由 padding 定，单行文字加行高会顶偏。
                maxLines = 1,
            )
        }
    }

    /** 分组间距（卡片之间 / 区块之间）。 */
    @Composable
    fun Gap(dpValue: Int = DhKit.GAP_CARD) {
        Spacer(Modifier.height(dpValue.dp))
    }

    /* ============================ 弹窗 ============================ */

    /**
     * 统一弹窗：卡片底 + 标题 + 内容 + 右下按钮行。← `UiKit.showDialog`。
     *
     * 【为什么不用 AlertDialog】各家 ROM 的原生弹窗是白底 Material 外观，与全局配色割裂；
     *   自绘才能保证暗色 / 纯黑 / 莫奈主题下都不刺眼 —— 与既有 `UiKit.showDialog` 同一理由。
     * 【为什么还要套一层 android Dialog】与 View 页保持完全一致的遮罩与进出场，
     *   并让 `Dialog` 的生命周期自己管（Activity 销毁时自动消失，不需要页面手动收尾）。
     */
    @Composable
    fun Alert(
        title: String,
        onDismiss: () -> Unit,
        posText: String? = null,
        onPos: (() -> Unit)? = null,
        negText: String? = null,
        // 【r15·补·可自定义的负向按钮】原先 negText 只有「取消」一种语义（点了就关）。
        //   但有些弹窗需要「负向按钮做别的事」——例如「编辑记忆」里把负向位让给「删除」
        //   （点了要先弹二次确认，而不是直接关窗）。
        //   【为什么加可选参数而不是改语义】全工程有几十处 Alert 调用，加可选参数
        //   （默认 null → 与原行为逐字节一致）零影响；改语义会波及所有既有弹窗。
        onNeg: (() -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val c = DhTokens.colors
        val confirmGuard = com.dollhouse.app.ui.feedback.rememberConfirmGuard()
        ComposeDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    // 【r13 修 bug·弹窗文字与背后重叠】弹窗底走 c.sheet：**不透明**，
                    //   质感由「外观 → 磨砂程度」控制。原来走 c.card，会跟着卡片透明度
                    //   一起变半透明，用户把卡片调透之后弹窗与背后正文叠成一片。
                    .background(c.sheet)
                    .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 14.dp)
            ) {
                Text(
                    text = title,
                    color = c.title,
                    fontSize = 16.sp, lineHeight = 24.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    content()
                }
                if (posText != null || negText != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.weight(1f))
                        if (negText != null) {
                            DialogButton(text = negText, primary = false) {
                                // 【r15】带 onNeg 时由调用方决定行为（例如「删除」要先二次确认，
                                //   不能顺手关窗）；不带则沿用老语义「点了就关」。
                                if (onNeg != null) {
                                    onNeg.invoke()
                                } else {
                                    onDismiss()
                                }
                            }
                        }
                        if (posText != null) {
                            Spacer(Modifier.width(10.dp))
                            DialogButton(text = posText, primary = true) {
                                confirmGuard.runOnce {
                                    onDismiss()
                                    onPos?.invoke()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 弹窗按钮：primary=true 实色主色，否则白底描边。← `UiKit.dialogButton`。
     *
     * 【为什么公开】原来只给 [Alert] 用，于是 [com.dollhouse.app.ui.doll.DollNumDialog]
     *   另外自造了三个「图标圆球」—— 同一个弹窗家族出现两套按钮语言。
     *   公开后所有弹窗共用一种按钮，新增弹窗不会再有第二种样式。
     */
    @Composable
    fun DialogButton(text: String, primary: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        Text(
            text = text,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .then(
                    if (primary) {
                        Modifier.background(c.acc)
                    } else {
                        // 【r13】弹窗底已改成不透明的 sheet，次按钮跟着走 sheetUp：
                        //   同色系但更实，任何磨砂档位下都浮得出来（用 cardUp 会在卡片
                        //   透明度 100% 时与面板同色 → 按钮看不见）。
                        Modifier
                            .background(c.sheetUp)
                            .border(1.dp, c.line, RoundedCornerShape(999.dp))
                    }
                )
                .pressable(onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            color = if (primary) c.onAcc else c.title,
            fontSize = UiKit.FS_CHIP.sp,
            fontFamily = if (primary) DhTokens.fontsBold else DhTokens.fonts,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal
        )
    }

    /**
     * 用「纯 Compose 弹窗」替代 View 版 `UiKit.showDialog` 的落地助手。
     *
     * 【为什么留这个函数】有些调用点（如 `ProviderNav` 的跨页确认）不在 Composable 作用域里，
     *   需要一个「拿 Activity + 内容就弹」的入口。这里返回一个可 dismiss 的句柄。
     * 【实现口径】与 `UiKit.showDialog` 一样用原生 `Dialog` 作壳、内容换成 `ComposeView`，
     *   这样调用方不必改写成 state 驱动，迁移可以一页一页来。
     */
    fun show(act: android.app.Activity?, title: String?, content: @Composable ColumnScope.() -> Unit): Dialog? {
        return show(act, title, null, content)
    }

    /**
     * 带「关闭回调」的版本。
     *
     * 【为什么需要】有些调用方必须在弹窗被关掉（无论点按钮还是点外部/返回键）之后才继续下一步，
     *   典型是「临时授权的内容 URI 何时可以释放」—— 提前释放会让紧接着的读取失败。
     *   Dialog.setOnDismissListener 恰好覆盖全部关闭路径，比自己给每个按钮挂回调更可靠。
     */
    fun show(
        act: android.app.Activity?,
        title: String?,
        onDismiss: (() -> Unit)?,
        content: @Composable ColumnScope.() -> Unit
    ): Dialog? {
        if (act == null) {
            return null
        }
        val dlg = Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        // 【必须在 setContentView 之前】Dialog 是独立 Window，它的 ViewTree 上没有
        //   LifecycleOwner（挂在 Activity decorView 上的那个沿树找不到）—— 不先装 owner
        //   的话 ComposeView attach 时直接崩（ViewTreeLifecycleOwner not found / Cannot locate windowRecomposer）。
        val owner = ComposeHost.installForDialog(dlg)
        dlg.setOnDismissListener {
            owner?.onDestroy()
            // 【授权时序】回调排在 owner 之后：调用方可能在这里释放临时 URI 授权，
            //   与 Compose 销毁互不依赖，谁先谁后都安全。
            try {
                onDismiss?.invoke()
            } catch (ignored: Throwable) {
            }
        }
        val view = ComposeHost.createView(act) {
            val c = DhTokens.colors
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    // 【r13】弹窗底走 sheet：不透明 + 磨砂质感（见 UiKit.sheet()）。
                    .background(c.sheet)
                    .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 14.dp)
            ) {
                if (!title.isNullOrEmpty()) {
                    Text(
                        text = title,
                        color = c.title,
                        fontSize = 16.sp, lineHeight = 24.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold
                    )
                }
                content()
            }
        }
        dlg.setContentView(view)
        dlg.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        dlg.show()
        return dlg
    }
}
