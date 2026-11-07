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
import com.dollhouse.app.ui.compose.DhTokens
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import com.dollhouse.app.ui.theme.ThemeManager
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
import com.dollhouse.app.ui.kit.DhField
import com.dollhouse.app.ui.kit.dhFieldInset
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
     * 【本轮·转发到规范实现】本函数现在只是**分派器**：不带尾部内容时直接委托
     *   `ui/kit` 的 [DhField]（规范源），框体样式一律不再自己画 ——
     *   聚焦态、40dp 高度、12 圆角、field 底、1dp accent 描边全部由 DhField 一处决定。
     *   这样「改规范」只需改 DhField，53 个调用点跟着变，不会再有第二套输入框。
     *
     * 【为什么带 trailing 的 6 处仍走旧实现】DhField 对 `singleLine = true` **强制**
     *   `Modifier.height(40.dp)`，而这几处的尾部是 [DhKit.IconButton]，命中区 48dp
     *   （见 [DhKit.HIT] 与 `minTouchTarget`）。若也走 DhField，48dp 会被裁到 40dp，
     *   无障碍可点区退化成不达标 —— 这是「对齐不变量」与「不破坏无障碍」的冲突，
     *   取后者。故这 6 处保留 [LegacyInput]（它只给 40dp 下限，不定高）。
     *   待 DhField 支持「下限而非定高」后，这 6 处可一并并入，届时删掉 LegacyInput。
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
        compactTrailing: Boolean = false
    ) {
        // 【分派条件】只有「带尾部内容」这一种情况必须走旧实现（理由见 KDoc）。
        //   其余 47 处一律委托 DhField —— 这正是本任务要的「规范真正落地」。
        //   `compactTrailing` 只影响 trailing 那条分支，无 trailing 时传什么都不参与渲染，
        //   故它不参与分派判定（与旧实现的分支语义一致）。
        if (trailing != null) {
            LegacyInput(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier,
                label = label,
                hint = hint,
                password = password,
                singleLine = singleLine,
                keyboard = keyboard,
                imeAction = imeAction,
                trailing = trailing,
                compactTrailing = compactTrailing
            )
            return
        }
        Column(modifier = modifier.fillMaxWidth()) {
            // 【标签留在这一层】DhField 没有 label 参数，规范库也没有独立的标签构件
            //   （见 Components.kt 的导出清单）。40 处调用点靠 label 表达字段名，
            //   删掉就等于丢功能，故由本层原样补上，措辞与字号沿用既有口径。
            if (!label.isNullOrEmpty()) {
                FieldLabel(label)
            }
            DhField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = hint,
                singleLine = singleLine,
                keyboard = keyboard,
                imeAction = imeAction,
                password = password
            )
        }
    }

    /**
     * 输入框左上角的小标签。
     *
     * 【为什么抽出来】分派器（委托 DhField）与 [LegacyInput]（带 trailing）都要画它，
     *   两处必须逐像素一致 —— 否则同一个表单里「有尾部图标的框」与「没有的框」
     *   标签位置会差几 dp，正是本轮要消除的分叉。
     */
    @Composable
    private fun FieldLabel(label: String) {
        Text(
            text = label,
            modifier = Modifier.padding(start = 2.dp, top = 12.dp, bottom = 4.dp),
            color = DhTokens.colors.sub,
            fontSize = UiKit.FS_SUB.sp,
            fontFamily = DhTokens.fonts
        )
    }

    /**
     * 输入框旧实现（仅 `trailing != null` 的 6 处使用）。
     *
     * 【为什么保留整套而不是删掉】它的高度是「40dp 下限、不定高」，正好容纳
     *   [DhKit.IconButton] 的 48dp 命中区；DhField 定高 40，用不了。详见 [Input] 的 KDoc。
     *   【聚焦态】与本轮上一版完全一致（底 field→card、描边 accent、160ms），
     *   不因为改成分派器而退化。
     */
    @Composable
    private fun LegacyInput(
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
        // 【聚焦态·必须保住】这一版不走 DhField，所以聚焦态得自己画 ——
        //   与本轮上一版**逐值相同**（底 field→card、描边 Transparent→accent、160ms），
        //   不因为本函数改名为 LegacyInput 而退化。
        //
        // 【token 口径】颜色取 `DhTokens.palette`，与 DhField 同源
        //   （Palette.fromUiKit 与 DhColors.fromUiKit 读同一批 UiKit 字段，值相等）。
        //   时长 `DhDesign.Motion.micro`（160）、描边 `DhDesign.Size.hairline`（1dp）、
        //   圆角 `DhDesign.Radius.md`（12）—— 不写裸值。
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
        // 【定高规则·本分支只有一个值】本函数只服务「带 trailing」的调用点，
        //   所以这里**恒为 false**：一律只给 40dp 下限、不定高，让
        //   [DhKit.IconButton] 的 48dp 命中区完整保留。
        //   原先那句 `singleLine && trailing == null` 在此分支恒假，已简化为字面 false，
        //   避免读代码的人以为这里还有定高路径。
        val fixedHeight = false
        // 【不定高时按 12dp 垂直内边距】由内边距把行撑到约 44dp，仍然容纳 48dp 的尾部图标。
        val verticalPad = DhDesign.Space.md.dp
        Column(modifier = modifier.fillMaxWidth()) {
            if (!label.isNullOrEmpty()) {
                // 与分派器共用同一个标签构件，保证「有 / 无尾部图标」两种框的标签位置一致。
                FieldLabel(label)
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
                    // 【v3·清透轻拟物·内凹】与规范实现 `DhField` 用**同一个** modifier。
                    //   【改造前是什么】原件在这里挂 `Modifier.border(1dp, stroke, shape)` ——
                    //   聚焦时画一圈 accent 描边。那是**扁平风格**的语言（一条明确的线）；
                    //   轻拟物里"被激活"应表现为**内部发光 + 坑更深**，不是加粗描边。
                    //   现在改为内阴影（上左暗、下右亮）+ 聚焦时的 accent 光晕，见
                    //   `ui/kit/FieldInset.kt` 的完整配方说明（含深色档的差异处理）。
                    //   【为什么与 DhField 用同一个函数】两处若各写一套，很快就分叉 ——
                    //   这正是上一轮「同一表单里两种输入框」的成因。
                    .dhFieldInset(shape = fieldShape, focused = focused, glowColor = stroke)
                    .padding(horizontal = DhDesign.Space.md.dp, vertical = verticalPad),
                // 【必须把 interactionSource 交给 BasicTextField】否则它永远收不到 Focus 事件，
                //   collectIsFocusedAsState 会恒为 false —— 聚焦态等于没加。
                interactionSource = focusSource,
                // 【字体口径·改为与 DhField 同源】原来这里写 `DhTokens.fonts`（文楷品牌字，
                //   FS_BTN 15sp）。委托给 DhField 的那 47 处用的是 `DhTokens.type.body`
                //   （系统无衬线）。若这里不改，同一个表单里会出现「有尾部图标的框是文楷、
                //   没有的是黑体」—— 这正是本轮要消除的分叉。
                //   设计稿 §1 明确：品牌字只用于 App 名 / 人偶名 / 头像首字，输入内容属正文。
                //   取 `type.body` 后字号仍是 15sp（body 与 FS_BTN 同档），视觉只变字族。
                textStyle = DhTokens.type.body.copy(color = c.title),
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
            val multiShape = RoundedCornerShape(DhDesign.Radius.md.dp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(minHeight.dp)
                    .clip(multiShape)
                    .background(fill)
                    // 【v3·清透轻拟物·内凹】与单行输入框同一套配方（同一函数、同一圆角），
                    //   保证「单行 / 多行」并排时质感一致 —— 否则多行框会显得是另一个控件。
                    //   【注意】多行框高度由 minHeight 撑开，内阴影按实际绘制尺寸算，
                    //   所以框变高时内凹会自然拉长（`drawBehind` 用的是实时 size，不是常量）。
                    .dhFieldInset(shape = multiShape, focused = focused, glowColor = stroke)
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
        // 【形态对齐设计稿】设计稿 §3「Buttons — one height, 44dp」+ 12 圆角。
        //   改造前这里是「内边距撑高 + 999 全圆角」——那是 **chip** 的形态
        //   （设计稿把 999 明确留给 chip/switch/badge/avatar），而不是按钮。
        //   【为什么四态逻辑一行没动】它的价值在「同位置反馈」：转圈/对勾/警告原位变化，
        //   避免用户连点。这是行为设计，不该被形态调整牵连。
        //   【高度为什么定死 44】与 DhButton 同档；文字单行 + 图标 14dp 都放得下。
        val btnShape = RoundedCornerShape(com.dollhouse.app.ui.design.DhDesign.Radius.md.dp)
        Row(
            modifier = modifier
                .height(44.dp)
                .clip(btnShape)
                .background(bg)
                .then(
                    if (state == SubmitState.LOADING) Modifier
                    else Modifier.pressable(onClick)
                )
                .padding(horizontal = 20.dp),
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
                // 【字号对齐设计稿】原 12sp/Bold（chip 档）→ 15sp/SemiBold（按钮正文档）。
                //   按钮文字比 chip 大一档是应有的层级；12sp 在高 44 的按钮里显得空。
                fontSize = com.dollhouse.app.ui.design.DhDesign.Type.fallback().body.fontSize,
                fontFamily = DhTokens.fonts,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }

    /* ============================ 语义化按钮（44dp / 12 圆角） ============================ */

    /**
     * 主按钮 —— 设计稿 §3「Buttons — one height, 44dp」的唯一落地入口。
     *
     * 【为什么需要这个工厂】改造前全 App 的「按钮」都是由 [PrimaryChip] /
     *   [OutlineChip] / [DhKit.OutlineChip] 这几个 **chip 工厂**兼职画出来的：
     *   999 全圆角 + 内边距撑高 + 12sp，那是设计稿明确留给 chip 的形态。
     *   设计稿把 999 的用途写死为「chip, switch, badge, avatar-round」，
     *   而按钮= 44dp 定高 + 12 圆角 —— 两者不是同一种东西。
     *
     * 【为什么不在原来那几个工厂上改】它们的函数体内部**同时**装着真 chip 调用点
     *   （页面筛选器、分段选择器、纯状态标签），而签名里没有任何语义参数，
     *   工厂内部无法区分「这次是筛选还是提交」。在那几个工厂上改成 44dp，
     *   会把真 chip 一起误伤成按钮，反而违反设计稿「Chip (26dp, pill)」。
     *   所以正确做法是**新开一个语义明确的按钮工厂**，让调用点自己说明意图。
     *
     * 【为什么用 pressScaleClick 而不是 pressable】[pressable] 从 v0.2.0 起内置
     *   `minTouchTarget()`（48dp 下限，为孤立小图标准备）。它会用 `sizeIn` 把
     *   `.height(44.dp)` 的**布局高度撑到 48** —— 于是「统一 44dp」名存实亡。
     *   这正是 [DhKit.OutlineChip] 注释里记过的同一个坑（v0.2.5 修过一次）。
     *   `kit.DhButton` 用的也是 `pressScaleClick`，本工厂与它保持完全一致。
     *
     * 【形态与 [DialogButton] / [SubmitButton] 同源】三者都是 44dp 定高 +
     *   [com.dollhouse.app.ui.design.DhDesign.Radius.md] + 15sp SemiBold，
     *   区别只在各自的额外职责（弹窗主次、四态反馈、无状态）。
     *
     * @param text    按钮文案（单行；过长时按 maxLines=1 省略，不会撑破 44dp）
     * @param enabled false 时视觉降到 38% 且不接收点击（与 kit.DhButton 同口径）
     * @param primary true = 主按钮（accent 实心），false = 次按钮（card 底 + line 描边）。
     *   设计稿 §3 明确两档：「Primary = accent fill + onAcc text」，
     *   「Secondary = card fill + line border」，且**一屏只许一个主按钮**。
     *   所以迁调用点时不能一律用主按钮 —— 弹窗里的「取消 / 留在此页」必须走次按钮，
     *   否则同一个弹窗会出现两个实心主按钮，反而违反设计稿。
     */
    /**
     * 【v3·清透轻拟物】按钮/芯片的共用形态层。
     *
     * ## 轻拟物 vs 扁平：差在哪
     * 扁平按钮是「一块纯色贴纸上」，轻拟物是「一块**有厚度**的东西放在面上」。
     * 三件事同时做才有体积感：
     *   ① **柔和外投影** —— 让它离页面有距离（不是描边，描边只会显薄）；
     *   ② **极淡顶部内高光** —— 光从左上来，上沿比下述底更亮一档；
     *   ③ **按下时阴影收小 + 已有 0.98 缩放** —— 视觉上被"压进"页面。
     *
     * ## 为什么不用 `.shadow()` 的 ambientColor 染色
     * Compose 的 `Modifier.shadow` 在 API<28 上只有黑色阴影；本工程 minSdk=24。
     * 要用蓝调染色（#8FA8C4）就得走 `drawBehind` + `Paint.setShadowLayer`，
     * 那会与主题切换的重组时机耦合、且 API24 上还得额外做软件层。
     * 这里改用**「上亮下暗」的渐变内阴影**模拟体积 —— 在任何 API 上都成立，
     * 也不违反 `Elevation` 文档里「列表行/芯片/顶栏永不加阴影」的既有硬规则
     * （我们加的是**渐变**，不是 elevation 阴影；芯片仍然"平"在面上）。
     *
     * ## 深色模式的差别（必须分开处理）
     * 浅色：底**比背景亮**（card 在白底上自然浮起）+ 顶部高光。
     * 深色：card 往往比 bg 更暗或接近，「比背景亮」不成立 —— 因此
     *       底抬到 `cardUp`（"比 [card] 实两档"，本工程既有语义），
     *       并且**顶部高光加强**（深色下明暗差更小，需要更强的锚点才看得出层次）。
     *
     * @param base   底色（主按钮=acc，次按钮=card）
     * @param shape  圆角（与调用方共用同一个 shape，保证渐变不露出直角）
     * @param pressed 是否按下（由 [pressScaleClick] 的交互源提供）
     */
    @Composable
    private fun Modifier.softRaised(
        base: Color,
        shape: androidx.compose.ui.graphics.Shape,
        pressed: Boolean,
        alpha: Float = 1f,
        dark: Boolean = false
    ): Modifier {
        val c = DhTokens.colors
        // 高光强度：浅色淡、深色强（深色下明暗差小，需要更强的顶部锚点）。
        val hlAlpha = (if (dark) 0.16f else 0.10f) * alpha
        // 底部加深：模拟"底部陷入"的自然阴影方向（上亮下暗 = 凸起）。
        val shadeAlpha = (if (dark) 0.22f else 0.08f) * alpha
        // 按下时把高光/阴影都收小 —— 与 pressScaleClick 的 0.98 一起构成"压入"。
        val k = if (pressed) 0.45f else 1f
        return this
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        // 顶部：比基色亮一档（白/黑叠加，与深浅色无关地"提亮"）
                        blend(base, Color.White, hlAlpha * k),
                        // 中段：基色（留出较大的"平面"区，避免整块像玻璃球）
                        base,
                        // 底部：略暗，制造下沿厚度
                        blend(base, Color.Black, shadeAlpha * k)
                    ),
                    // 【startY/endY 省略】verticalGradient 默认 0 → Float.POSITIVE_INFINITY，
                    //   即"按可用高度铺满"。原先我写了 `Offset.Infinite.y` 取这个值，
                    //   虽等价但绕了一圈，还多引一个 Offset 类型；省略更清楚。
                )
            )
    }

    /**
     * 【v3·轻拟物】[com.dollhouse.app.ui.compose.pressScaleClick] 的可注入 source 版本。
     *
     * 【与原件逐参数一致】缩放目标 0.98、按下 `DhAnim.PRESS_DOWN`、松手 `DhAnim.BOUNCY`、
     *   `clickable(indication = null)` —— 手感完全相同，只是把 interactionSource
     *   交给调用方持有，这样按钮才能同时拿到 `pressed` 去收小阴影。
     *
     * 【为什么不去改 DhKit.pressScaleClick】它全工程有几十处调用、且当前不在本次
     *   改动范围内。加一个默认为 null 的参数虽然源码兼容，但会让那条被广泛使用的
     *   路径多一个分支判断；在 DhForm 内部私有化，影响面最小。
     */
    @Composable
    private fun Modifier.pressScaleClickWith(
        source: MutableInteractionSource,
        onClick: () -> Unit
    ): Modifier {
        val callback = {
            com.dollhouse.app.core.OperationRecorder.event("Action", "Compose click")
            onClick()
        }
        val pressed by source.collectIsPressedAsState()
        val spec = if (pressed) DhAnim.PRESS_DOWN else DhAnim.BOUNCY
        val scale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (pressed) 0.98f else 1f,
            animationSpec = spec,
            label = "press"
        )
        return this
            .androidx.compose.ui.draw.scale(scale)
            .clickable(interactionSource = source, indication = null, onClick = callback)
    }

    /** 把 [overlay] 按 [t] 混到 [base] 上（t=0 返回 base，t=1 返回 overlay）。 */
    private fun blend(base: Color, overlay: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = base.red + (overlay.red - base.red) * k,
            green = base.green + (overlay.green - base.green) * k,
            blue = base.blue + (overlay.blue - base.blue) * k,
            alpha = base.alpha
        )
    }

    @Composable
    fun Button(
        text: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        primary: Boolean = true,
        // 【可选前缀图标】Icons.IC_*；默认 0 = 不带图标，与只有文字时逐像素一致。
        //   有它才能接住原来 OutlineChip(iconRes = ...) 的调用点（如「测试连接」）——
        //   否则迁移会静默丢掉图标，那是删功能，不是换形态。
        //   尺寸走 DhDesign.Size.iconSm(16dp)，与 kit.DhButton 同档；
        //   比原来 chip 档的 SZ_XS(14dp) 大一档，因为按钮文字已经是 15sp。
        leadingIcon: Int = 0,
        onClick: () -> Unit
    ) {
        val c = DhTokens.colors
        val shape = RoundedCornerShape(com.dollhouse.app.ui.design.DhDesign.Radius.md.dp)
        // 【禁用态为什么用 alpha 而不是换色】与 kit.DhButton 一致：整体降透明度
        //   能同时作用到底色与文字，不必维护第二套「禁用配色」。
        val alpha = if (enabled) 1f else 0.38f
        // 【轻拟物·按压态】按下时阴影/高光收小，与 0.98 缩放叠加成"压入"。
        //   【为什么这里自己实现按压而不复用 DhKit.pressScaleClick】
        //   那个函数把 `MutableInteractionSource` 藏在内部，外部拿不到 `pressed` 状态，
        //   于是"按下时阴影收小"无法实现（只能缩、不能变阴影）。
        //   本工程 DhKit.kt 当前不在我的改动范围内，故在 DhForm 内做一个**行为等价**
        //   的私有重载：同一份 PRESS_SCALE(0.98) / DhAnim.PRESS_DOWN / DhAnim.BOUNCY 口径，
        //   唯一区别是把 source 暴露给调用方自己持有。
        val ctx = LocalContext.current
        val dark = ThemeManager.isDark(ctx)
        val pressSource = remember { MutableInteractionSource() }
        val pressed by pressSource.collectIsPressedAsState()
        Box(
            modifier = modifier
                // 【命中区 48 / 视觉 44】用 defaultMinSize 给下限而不是再叠一层 height：
                //   视觉高度由内层 44dp 决定，外层只保证「可点区域不小于 48dp」
                //   （设计稿 §Icons「40dp hit area (48dp minimum)」+ Android 无障碍基线）。
                //   这与旧 pressable 的做法不同 —— 那个是把布局也撑到 48，
                //   于是圆角 999 被拉成细长椭圆（详见 DhKit.OutlineChip 的注释）。
                //   这里视觉与命中分开，两边都不牺牲。
                .defaultMinSize(minHeight = 48.dp)
                .then(
                    if (enabled) {
                        Modifier.pressScaleClickWith(pressSource, onClick)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .height(44.dp)
                    // 【v3·清透轻拟物】主/次两档都改成「有厚度的一块」：
                    //   浅色下主按钮=acc 底+顶部高光，次按钮=card 底（比 bg 亮）+顶部高光；
                    //   深色下次按钮底抬到 cardUp 并加强高光。
                    //   原先主按钮是纯色实心、次按钮是 card+1dp 硬描边 —— 那是扁平形态。
                    .softRaised(
                        base = when {
                            primary -> c.acc
                            dark -> c.cardUp
                            else -> c.card
                        }.copy(alpha = alpha),
                        shape = shape,
                        pressed = pressed,
                        alpha = alpha,
                        dark = dark
                    )
                    // 【次按钮的硬描边已去掉】旧形态是 `card fill + 1dp line border`（扁平、显薄）。
                    //   轻拟物下轮廓不靠描边、靠**上亮下暗的体积**承担 —— 见 softRaised。
                    //   若保留描边，它会把渐变盖掉、又退回"贴纸"观感。
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leadingIcon != 0) {
                        DhKit.Icon(
                            iconRes = leadingIcon,
                            sizeDp = com.dollhouse.app.ui.design.DhDesign.Size.iconSm.value,
                            color = if (primary) c.onAcc.copy(alpha = alpha) else c.title.copy(alpha = alpha)
                        )
                        Spacer(Modifier.width(com.dollhouse.app.ui.design.DhDesign.Space.sm.dp))
                    }
                    Text(
                        text = text,
                        color = if (primary) c.onAcc.copy(alpha = alpha) else c.title.copy(alpha = alpha),
                        fontSize = com.dollhouse.app.ui.design.DhDesign.Type.fallback().body.fontSize,
                        fontFamily = DhTokens.fonts,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
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
                    fontSize = DhTokens.type.section.fontSize, lineHeight = DhTokens.type.section.lineHeight,
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
        // 【形态对齐设计稿】设计稿 §3「Buttons — one height, 44dp」+ 12 圆角，
        //   并把 999 明确留给 chip/switch/badge/avatar。改造前这里是
        //   「999 全圆角 + 内边距撑高 + 12sp」—— 那是 **chip** 的形态，不是按钮。
        //   【为什么本工厂可以整体改】它 13 处调用点**全部**是弹窗主/次操作
        //   （取消 / 保存 / 删除 / 恢复 / 关闭），没有一处是筛选、选择或状态标签。
        //   与 PrimaryChip / OutlineChip 不同：那两个工厂内部**同时**装着
        //   chip 调用点（筛选、分段选择），不能从工厂层面统一，只能逐处判断。
        //   【为什么不改调用点】签名与 13 处调用点零改动，改造只在工厂内部。
        //   【高度为什么定死 44】与 kit.DhButton 同档；单行文字 + 左右 20dp 内边距放得下。
        //   【为什么从 pressable 换成 pressScaleClick】pressable 内置 minTouchTarget()
        //   （48dp 下限，为孤立小图标准备）。它会把 height(44) 的布局**撑到 48**，
        //   于是「44dp 统一高度」名存实亡 —— 这正是 DhKit.OutlineChip 注释里记过的同一个坑。
        //   kit.DhButton 的做法也是 pressScaleClick；这里与它保持完全一致。
        //   弹窗按钮自带可见轮廓且高 44dp，命中区足够，不需要额外的 48dp 透明补丁。
        val shape = RoundedCornerShape(com.dollhouse.app.ui.design.DhDesign.Radius.md.dp)
        Box(
            modifier = Modifier
                .height(44.dp)
                .clip(shape)
                .then(
                    if (primary) {
                        Modifier.background(c.acc)
                    } else {
                        // 【r13】弹窗底已改成不透明的 sheet，次按钮跟着走 sheetUp：
                        //   同色系但更实，任何磨砂档位下都浮得出来（用 cardUp 会在卡片
                        //   透明度 100% 时与面板同色 → 按钮看不见）。
                        Modifier
                            .background(c.sheetUp)
                            .border(1.dp, c.line, shape)
                    }
                )
                .pressScaleClick(onClick)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (primary) c.onAcc else c.title,
                // 【字号对齐设计稿】原 12sp（chip 档）→ 15sp（body 档，按钮正文）。
                //   按钮文字比 chip 大一档是应有的层级；12sp 在高 44 的按钮里显得空。
                fontSize = com.dollhouse.app.ui.design.DhDesign.Type.fallback().body.fontSize,
                fontFamily = if (primary) DhTokens.fontsBold else DhTokens.fonts,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
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
                        fontSize = DhTokens.type.section.fontSize, lineHeight = DhTokens.type.section.lineHeight,
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
