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

/** 卡内小标题：把一张卡内部再分小节（旧 `DhKit.SectionLabel` 的等价物）。 */
fun QzxSectionScope.label(title: String, icon: Painter? = null) {
    holders.add(object : QzxRowHolder {
        override fun snapshot(): QzxRow = QzxLabelRow(title, icon)
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
