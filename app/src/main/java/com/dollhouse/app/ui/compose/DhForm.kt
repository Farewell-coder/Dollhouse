package com.dollhouse.app.ui.compose

import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.theme.Design

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
        trailing: (@Composable () -> Unit)? = null
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
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.field)
                    .padding(12.dp),
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
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
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
                            trailing()
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
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(minHeight.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.field)
                    .padding(12.dp),
                textStyle = TextStyle(
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                ),
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
                    fontSize = 16.sp,
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
        if (act == null) {
            return null
        }
        val dlg = Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        // 【必须在 setContentView 之前】Dialog 是独立 Window，它的 ViewTree 上没有
        //   LifecycleOwner（挂在 Activity decorView 上的那个沿树找不到）—— 不先装 owner
        //   的话 ComposeView attach 时直接崩（ViewTreeLifecycleOwner not found / Cannot locate windowRecomposer）。
        val owner = ComposeHost.installForDialog(dlg)
        dlg.setOnDismissListener { owner?.onDestroy() }
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
                        fontSize = 16.sp,
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
