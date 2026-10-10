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
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default
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

    /** 小字备注。← `ApiPageKit.note`（12sp SUB，行距 +3）。[color] 非空时用于状态提示（OK / ERR）。 */
    @Composable
    fun Note(text: String, modifier: Modifier = Modifier, color: Color? = null) {
        Text(
            text = text,
            modifier = modifier.padding(start = 2.dp, top = 8.dp, end = 2.dp),
            color = color ?: DhTokens.colors.sub,
            fontSize = UiKit.FS_TINY.sp,
            fontFamily = DhTokens.fonts,
            lineHeight = (UiKit.FS_TINY + 5f).sp
        )
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
    fun OutlineChip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
        val c = DhTokens.colors
        Text(
            text = text,
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .background(c.card)
                .border(1.dp, c.line, RoundedCornerShape(999.dp))
                .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = c.title,
            fontSize = UiKit.FS_CHIP.sp,
            fontFamily = DhTokens.fonts
        )
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
        content: @Composable ColumnScope.() -> Unit
    ) {
        val c = DhTokens.colors
        ComposeDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.card)
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
                                onDismiss()
                            }
                        }
                        if (posText != null) {
                            Spacer(Modifier.width(10.dp))
                            DialogButton(text = posText, primary = true) {
                                onDismiss()
                                onPos?.invoke()
                            }
                        }
                    }
                }
            }
        }
    }

    /** 弹窗按钮：primary=true 实色主色，否则白底描边。← `UiKit.dialogButton`。 */
    @Composable
    private fun DialogButton(text: String, primary: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        Text(
            text = text,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .then(
                    if (primary) {
                        Modifier.background(c.acc)
                    } else {
                        Modifier
                            .background(c.card)
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
                    .background(c.card)
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
