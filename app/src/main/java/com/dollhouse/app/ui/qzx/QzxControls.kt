package com.dollhouse.app.ui.qzx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.theme.UiKit
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 【职责】列表里的**复合控件行型**：步进器 / 多选标签组 / 按钮行 / 卡内小标题。
 *
 * ## 为什么单独一个文件
 * `QzxList.kt` 已经 1090 行，而且它管的是"列表结构"（分区、分隔线、行高、渲染顺序）；
 * 这里管的是"某一行的**内部交互**"。两者混在一起，改一个行型要在一个千行文件里翻。
 *
 * ## 共同原则：**给数据，不给布局权**
 * 四个行型的参数都只有"内容 + 行为"，没有一个尺寸 / 间距 / 颜色参数。
 * 行高、内边距、按钮对齐、chip 间距、禁用态透明度全由这里按令牌算。
 */

// ───────────────────────── 行模型 ─────────────────────────

/** 步进器行：名称 + 当前值 + 可独立禁用的 `−` / `+`。 */
data class QzxStepperRow(
    val title: String,
    val value: String,
    val hint: String?,
    val canMinus: Boolean,
    val canPlus: Boolean,
    val onMinus: () -> Unit,
    val onPlus: () -> Unit,
) : QzxRow

/** 一个可多选的标签。 */
data class QzxChip(val label: String, val id: String)

/** 多选标签组行。 */
data class QzxChipsRow(
    val title: String,
    val options: List<QzxChip>,
    val selected: Set<String>,
    val onToggle: (String, Boolean) -> Unit,
) : QzxRow

/** 按钮行里的一个按钮。 */
data class QzxButtonSpec(
    val text: String,
    val style: QzxButtonStyle = QzxButtonStyle.Primary,
    val onClick: () -> Unit,
)

/** 按钮行。 */
data class QzxButtonsRow(val buttons: List<QzxButtonSpec>) : QzxRow

/** 滑条行：标题 + 当前值 + 自绘滑条 + 可选说明。 */
data class QzxSliderRow(
    val title: String,
    val value: Int,
    val range: IntRange,
    val hint: String?,
    val onChange: (Int, Boolean) -> Unit,
) : QzxRow

/** 徽标语气。 */
enum class QzxBadgeTone { Neutral, Accent, Danger }

/** 徽标行：一小块带语气色的标签（旧 `DhForm.Badge` 的等价物）。 */
data class QzxBadgeRow(val text: String, val tone: QzxBadgeTone) : QzxRow

/** 卡内小标题（不折叠、不可点，只是把一张卡内部再分小节）。 */
data class QzxLabelRow(val title: String, val icon: Painter?) : QzxRow

// ───────────────────────── DSL ─────────────────────────

/**
 * 步进器行。
 *
 * 【为什么不是 `select`】步进器表达的是"连续档位，点一下变一档"（整理间隔、权重 0~10）；
 * `select` 表达的是"从离散枚举里挑一个，要展开下拉"。两者在不同场景下用户预期完全不同 ——
 * 把权重做成下拉，用户要展开 11 项才能改 1 档。
 */
fun QzxSectionScope.stepper(
    title: String,
    value: String,
    hint: String? = null,
    canMinus: Boolean = true,
    canPlus: Boolean = true,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    holders.add(object : QzxRowHolder {
        override fun snapshot(): QzxRow =
            QzxStepperRow(title, value, hint, canMinus, canPlus, onMinus, onPlus)
    })
}

/**
 * 多选标签组行。
 *
 * 【为什么不是 `select`】`select` 是**单选**枚举（点一个换一个）；这里是**集合**
 * （能力 / 模态可以同时开好几个）。用 `select` 表达会强迫用户一个一个切。
 */
fun QzxSectionScope.chips(
    title: String,
    options: List<QzxChip>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    holders.add(object : QzxRowHolder {
        override fun snapshot(): QzxRow = QzxChipsRow(title, options, selected, onToggle)
    })
}

/** 按钮行：`buttons(QzxButtonSpec("保存") { … }, …)`。对齐与间距归系统。 */
fun QzxSectionScope.buttons(vararg buttons: QzxButtonSpec) {
    val list = buttons.toList()
    holders.add(object : QzxRowHolder {
        override fun snapshot(): QzxRow = QzxButtonsRow(list)
    })
}

/**
 * 滑条行。
 *
 * `onChange(value, fromUser)` 与既有 `UiKit.Slider.OnChange.onChanged(v, fromUser)` **同签名** ——
 * 迁移时调用方的 lambda 可以原样搬过来，不需要在中间做参数适配。
 *
 * 【为什么内部桥接 `UiKit.Slider` 而不是 Compose 自绘】
 * 那个 View 是**胶囊轨道 + 刻度点 + 终点标记**的自绘形态，Compose 原生 `Slider` 做不出来。
 * 自绘还原它要真机逐帧调（轨道圆角、刻度间距随宽度变化、拖动跟手、无障碍语义），
 * 那是"第二个项目"；而这一轮的目标是**迁移**，不是重画控件。
 * 桥接一个**已存在的自绘控件**，和 `QzxColors` 从 `UiKit` 取色属于同一类依赖：
 * 复用底层实现，不引入旧 UI 体系。
 */
fun QzxSectionScope.slider(
    title: String,
    value: Int,
    range: IntRange,
    hint: String? = null,
    onChange: (Int, Boolean) -> Unit,
) {
    holders.add(object : QzxRowHolder {
        override fun snapshot(): QzxRow = QzxSliderRow(title, value, range, hint, onChange)
    })
}

/**
 * 徽标行：`badge("已过期", QzxBadgeTone.Danger)`。
 *
 * 【为什么是"语气"而不是颜色】和 `danger()` 同一条原则：调用方表达**含义**，
 * 具体色值由系统给 —— 否则每个页面都会挑一个自己的红。
 */
fun QzxSectionScope.badge(text: String, tone: QzxBadgeTone = QzxBadgeTone.Neutral) {
    holders.add(object : QzxRowHolder {
        override fun snapshot(): QzxRow = QzxBadgeRow(text, tone)
    })
}

/** 卡内小标题：把一张卡内部再分小节（旧 `DhKit.SectionLabel` 的等价物）。 */
fun QzxSectionScope.label(title: String, icon: Painter? = null) {
    holders.add(object : QzxRowHolder {
        override fun snapshot(): QzxRow = QzxLabelRow(title, icon)
    })
}

/**
 * 预览区块：圆形头像/大图 + 标题 + 副标题 + 动作组 + 引导小字。
 * （旧 `DhKit.Card` 里那种「头像 + 换头像/移除按钮」的块，`MyProfilePage` 与 `AboutPage` 在用。）
 *
 * 【为什么全是数据参数】`QzxPreviewAction` 只有 `label` / `onClick` / `destructive`
 *   三个自由度，调用方**没有地方**写 `padding` / `Row` / `size`。
 *   若收 `@Composable () -> Unit`，旧页面那套 `clip(pill)` + 限宽 180dp 会原样搬回来
 *   —— 那就是「把违规藏进逃生口」。
 *
 * 【图标由调用方给】与 `QzxTitleBar.backIcon` 同一条原则：新系统不持有任何图标资源，
 *   删旧资源时编译不过的地方就是还没迁完的地方。
 *
 * 【⚠️ 勘误记录】这个函数在 `QzxRow.Preview`（数据类）与它的渲染器都已经存在的情况下
 *   漏了十几天 —— 而 `AboutPage` / `MyProfilePage` 早已在调用它。
 *   这类「调用了不存在的函数」precheck 抓不到（它是正则门禁，不做符号解析），
 *   只有真实编译才会报 Unresolved reference。
 */
fun QzxSectionScope.preview(
    image: Painter?,
    fallbackIcon: Painter?,
    title: String? = null,
    subtitle: String? = null,
    actions: List<QzxPreviewAction> = emptyList(),
    hint: String? = null
) {
    holders.add(object : QzxRowHolder {
        override fun snapshot(): QzxRow =
            QzxRow.Preview(image, fallbackIcon, title, subtitle, actions, hint)
    })
}

// ───────────────────────── 渲染 ─────────────────────────

/** 步进器。`−` / `+` 各自按 `canMinus` / `canPlus` 独立禁用。 */
@Composable
internal fun QzxStepperRowContent(row: QzxStepperRow) {
    val c = QzxColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = QzxTokens.Space.listH,
                end = QzxTokens.Space.listH,
                top = QzxTokens.Space.sm,
                bottom = QzxTokens.Space.sm
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = QzxTokens.Size.minTouch.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = row.title,
                modifier = Modifier.weight(1f),
                color = c.title,
                fontSize = QzxTokens.Type.ROW_TITLE_SP.sp,
                lineHeight = (QzxTokens.Type.ROW_TITLE_SP + QzxTokens.Type.LINE_GAP_SP).sp
            )
            QzxStepButton(label = "−", enabled = row.canMinus, onClick = row.onMinus)
            Text(
                text = row.value,
                modifier = Modifier.padding(horizontal = QzxTokens.Space.md),
                color = c.title,
                fontSize = QzxTokens.Type.ROW_VALUE_SP.sp,
                lineHeight = (QzxTokens.Type.ROW_VALUE_SP + QzxTokens.Type.LINE_GAP_SP).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            QzxStepButton(label = "+", enabled = row.canPlus, onClick = row.onPlus)
        }
        if (!row.hint.isNullOrEmpty()) {
            Text(
                text = row.hint,
                modifier = Modifier.padding(top = QzxTokens.Space.xs),
                color = c.sub,
                fontSize = QzxTokens.Type.FOOTER_SP.sp,
                lineHeight = (QzxTokens.Type.FOOTER_SP + QzxTokens.Type.LINE_GAP_SP).sp
            )
        }
    }
}

/** 步进按钮：48dp 命中区，禁用时只变淡、仍占位（避免布局跳动）。 */
@Composable
private fun QzxStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = QzxColors
    Box(
        modifier = Modifier
            .size(QzxTokens.Size.minTouch.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(QzxTokens.Radius.inner))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) c.accent else c.sub,
            fontSize = QzxTokens.Type.ROW_TITLE_SP.sp,
            lineHeight = (QzxTokens.Type.ROW_TITLE_SP + QzxTokens.Type.LINE_GAP_SP).sp
        )
    }
}

/** 多选标签组：选中态用主色底，未选用软底。 */
@Composable
internal fun QzxChipsRowContent(row: QzxChipsRow) {
    val c = QzxColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = QzxTokens.Space.listH,
                end = QzxTokens.Space.listH,
                top = QzxTokens.Space.md,
                bottom = QzxTokens.Space.md
            )
    ) {
        Text(
            text = row.title,
            color = c.title,
            fontSize = QzxTokens.Type.ROW_TITLE_SP.sp,
            lineHeight = (QzxTokens.Type.ROW_TITLE_SP + QzxTokens.Type.LINE_GAP_SP).sp
        )
        Spacer(Modifier.height(QzxTokens.Space.sm))
        // 【不换行的横向排布】标签数量少（2~4 个），用 Row 而不是 FlowRow：
        //   FlowRow 是实验 API，而这里每个标签都很短，最坏情况也只是挤一点。
        Row(horizontalArrangement = Arrangement.spacedBy(QzxTokens.Space.sm)) {
            row.options.forEach { opt ->
                val on = opt.id in row.selected
                Box(
                    modifier = Modifier
                        .heightIn(min = QzxTokens.Size.minTouch.dp)
                        .clip(RoundedCornerShape(QzxTokens.Radius.pill))
                        .background(if (on) c.accent else c.soft)
                        .clickable { row.onToggle(opt.id, !on) }
                        .padding(horizontal = QzxTokens.Space.lg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = opt.label,
                        color = if (on) c.onAccent else c.title,
                        fontSize = QzxTokens.Type.ROW_VALUE_SP.sp,
                        lineHeight = (QzxTokens.Type.ROW_VALUE_SP + QzxTokens.Type.LINE_GAP_SP).sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 按钮行：等分宽度、间距由系统给。 */
@Composable
internal fun QzxButtonsRowContent(row: QzxButtonsRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = QzxTokens.Space.listH,
                end = QzxTokens.Space.listH,
                top = QzxTokens.Space.md,
                bottom = QzxTokens.Space.md
            ),
        horizontalArrangement = Arrangement.spacedBy(QzxTokens.Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        row.buttons.forEach { spec ->
            QzxButton(
                text = spec.text,
                onClick = spec.onClick,
                // 多个按钮等分：调用方不用写 weight，也就写不出布局字面量。
                modifier = Modifier.weight(1f),
                style = spec.style
            )
        }
    }
}

/** 滑条：标题 + 值一行，滑条一行，说明一行。 */
@Composable
internal fun QzxSliderRowContent(row: QzxSliderRow) {
    val c = QzxColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = QzxTokens.Space.listH,
                end = QzxTokens.Space.listH,
                top = QzxTokens.Space.md,
                bottom = QzxTokens.Space.sm
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title,
                modifier = Modifier.weight(1f),
                color = c.title,
                fontSize = QzxTokens.Type.ROW_TITLE_SP.sp,
                lineHeight = (QzxTokens.Type.ROW_TITLE_SP + QzxTokens.Type.LINE_GAP_SP).sp
            )
            Text(
                text = row.value.toString(),
                color = c.sub,
                fontSize = QzxTokens.Type.ROW_VALUE_SP.sp,
                lineHeight = (QzxTokens.Type.ROW_VALUE_SP + QzxTokens.Type.LINE_GAP_SP).sp
            )
        }
        // 【唯一的 View 桥接处】见 [slider] 的注释：复用既有的自绘滑条，
        //   `update` 里回写 progress，保证外部状态变化（重置、预设）能反映到滑条上。
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                UiKit.Slider(ctx).apply {
                    setMax(row.range.last)
                    setProgress(row.value)
                    setOnChange(object : UiKit.Slider.OnChange {
                        override fun onChanged(v: Int, fromUser: Boolean) {
                            row.onChange(v, fromUser)
                        }
                    })
                }
            },
            update = { it.setProgress(row.value) }
        )
        if (!row.hint.isNullOrEmpty()) {
            Text(
                text = row.hint,
                modifier = Modifier.padding(top = QzxTokens.Space.xs),
                color = c.sub,
                fontSize = QzxTokens.Type.FOOTER_SP.sp,
                lineHeight = (QzxTokens.Type.FOOTER_SP + QzxTokens.Type.LINE_GAP_SP).sp
            )
        }
    }
}

/** 徽标。语气 → 底色/字色全由系统映射。 */
@Composable
internal fun QzxBadgeRowContent(row: QzxBadgeRow) {
    val c = QzxColors
    val bg = when (row.tone) {
        QzxBadgeTone.Neutral -> c.soft
        QzxBadgeTone.Accent -> c.accent
        QzxBadgeTone.Danger -> c.danger
    }
    val fg = when (row.tone) {
        QzxBadgeTone.Neutral -> c.title
        QzxBadgeTone.Accent -> c.onAccent
        QzxBadgeTone.Danger -> c.onAccent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = QzxTokens.Space.listH,
                end = QzxTokens.Space.listH,
                top = QzxTokens.Space.xs,
                bottom = QzxTokens.Space.xs
            )
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(QzxTokens.Radius.pill))
                .background(bg)
                .padding(
                    horizontal = QzxTokens.Space.sm,
                    vertical = QzxTokens.Space.xxs
                )
        ) {
            Text(
                text = row.text,
                color = fg,
                fontSize = QzxTokens.Type.FOOTER_SP.sp,
                lineHeight = (QzxTokens.Type.FOOTER_SP + QzxTokens.Type.LINE_GAP_SP).sp,
                maxLines = 1
            )
        }
    }
}

/** 卡内小标题。 */
@Composable
internal fun QzxLabelRowContent(row: QzxLabelRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = QzxTokens.Space.listH,
                end = QzxTokens.Space.listH,
                top = QzxTokens.Space.md,
                bottom = QzxTokens.Space.xs
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        row.icon?.let {
            Icon(
                painter = it,
                contentDescription = null,
                modifier = Modifier.size(QzxTokens.Space.xl * 0.8f),
                tint = QzxColors.sub
            )
            Spacer(Modifier.width(QzxTokens.Space.xs))
        }
        Text(
            text = row.title,
            color = QzxColors.sub,
            fontSize = QzxTokens.Type.SECTION_SP.sp,
            lineHeight = (QzxTokens.Type.SECTION_SP + QzxTokens.Type.LINE_GAP_SP).sp
        )
    }
}
