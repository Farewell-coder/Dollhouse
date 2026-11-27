package com.dollhouse.app.ui.qzx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 【职责】**声明式列表** —— 新系统的基底。
 *
 * ## 这个文件要解决的问题
 * 旧写法是命令式手拼：
 * ```kotlin
 * DhGroup {
 *     ActionRow(title = "选择排序方式", value = "按编辑日期", onClick = …)
 *     RowDivider()
 *     ActionRow(title = "笔记列表布局", value = "2列", onClick = …)
 * }
 * ```
 * 每一行都要自己写、每条分隔线都要自己插、每处内边距都要自己对 —— 于是"排版"这件事
 * 散落在 60 多个页面里，谁都能写出一种新的行距。
 *
 * 新写法只描述**有哪些行**：
 * ```kotlin
 * QzxList {
 *     section("main", title = "外观") {
 *         item("选择排序方式", "按编辑日期").arrow().onClick { showSortPicker() }
 *         item("笔记列表布局", "2列").arrow()
 *     }
 * }
 * ```
 * **行高、内边距、描边、分隔线缩进、卡片包裹全部由系统算** —— 页面里没有任何一个
 * `padding` / `height` / `border` 需要为"列表"出现。
 *
 * ## 三条设计约束（改这个文件前先读）
 * 1. **调用方不描述排版**。`QzxSectionScope` 上只有"加一行"的方法，没有"设间距"的方法。
 *    想调间距 = 改 [QzxTokens]，不是改调用点。
 * 2. **分隔线是系统的责任**。默认 `autoDivider = true`：相邻两行自动加线；显式写
 *    `divider()` 也允许（会被去重，见 [normalize]）。
 * 3. **行高是下限不是定值**（`heightIn(min = …)`）。用户把系统字号调大时行要能长高，
 *    否则文字被裁。旧系统有一处写死 `height(56.dp)`，就是这个病。
 */

/** 一个可选项（`select` 行用）。 */
data class QzxOption(val value: String, val label: String)

// ───────────────────────────── 模型 ─────────────────────────────

/** 列表里的一行（纯数据，不含排版）。 */
sealed interface QzxRow {
    data class Item(
        val title: String,
        val value: String?,
        val arrow: Boolean,
        val onClick: (() -> Unit)?,
        /** 设置项索引 id（见 [QzxItemBuilder.spot]）。*/
        val spotId: String? = null,
        /** 行前置图标（由调用方给 painter，新系统不持有任何图标资源）。*/
        val icon: Painter? = null,
        /** 副说明：有值时行高自动升到 `rowTwoLine`。*/
        val desc: String? = null
    ) : QzxRow

    data class Toggle(
        val title: String,
        val checked: Boolean,
        val onChecked: (Boolean) -> Unit,
        val spotId: String? = null,
        val icon: Painter? = null,
        val desc: String? = null
    ) : QzxRow

    data class Input(
        val title: String,
        val value: String,
        val placeholder: String?,
        val onValueChange: (String) -> Unit,
        val spotId: String? = null,
        val icon: Painter? = null,
        val desc: String? = null
    ) : QzxRow

    data class Select(
        val title: String,
        val value: String?,
        val options: List<QzxOption>,
        val onSelect: (QzxOption) -> Unit,
        val spotId: String? = null,
        val icon: Painter? = null,
        val desc: String? = null
    ) : QzxRow

    /**
     * 说明文字行（`note`）。
     *
     * 【为什么要有】设置页大量需要"一段解释"（旧系统走 `DhForm.Note`）。
     * 新系统自带这一行型：**不依赖旧 `DhForm`**，字号/行高/颜色由令牌决定，
     * 左右内边距与上下文一致。
     */
    data class Note(val text: String) : QzxRow

    /** 逃生口：调用方给一段任意内容，但**仍然**由系统负责左右内边距与分隔线。 */
    data class Custom(val content: @Composable () -> Unit) : QzxRow

    data object Divider : QzxRow
}

/** 建造期持有者：让行在被 `add` 之后还能继续被链式配置（`.arrow().onClick {}`）。 */
internal interface QzxRowHolder {
    fun snapshot(): QzxRow
}

// ───────────────────────────── 行建造器 ─────────────────────────────

/**
 * 普通行。
 *
 * 用法照抄 QZX：
 * `item("选择排序方式", "按编辑日期").arrow().onClick { … }`
 *
 * 【为什么方法都返回自身】链式配置。每一个 `.` 之后都还是同一行，不会意外新建一行 ——
 * 这是"声明式"能读起来像配置的关键。
 */
class QzxItemBuilder internal constructor(
    private val title: String,
    private var valueText: String?,
    private val icon: Painter? = null
) : QzxRowHolder {
    private var showArrow = false
    private var clickAction: (() -> Unit)? = null
    private var spotId: String? = null
    private var descText: String? = null

    /** 副说明（第二行小字）。有它时行高自动升到 `rowTwoLine`。 */
    fun desc(text: String): QzxItemBuilder = apply { descText = text }

    /** 右侧显示一个箭头（表示"点进去还有一层"）。 */
    fun arrow(show: Boolean = true): QzxItemBuilder = apply { showArrow = show }

    /** 覆盖右侧值。 */
    fun value(v: String?): QzxItemBuilder = apply { valueText = v }

    /** 点击回调。不设 = 不可点（此时不画箭头，也不会有按压反馈）。 */
    fun onClick(block: () -> Unit): QzxItemBuilder = apply { clickAction = block }

    /**
     * 绑定**设置项索引 id**（如 `"appearance.mode"`）。
     *
     * ## 它是"反向"的：标题从索引表取，而不是往表里登记
     * 旧系统 `SettingsSpots` 的语义是**索引表持有标题**：
     * · 表里有这条 → `spotTitle(spot, fallback)` 用**表里的标题**（页面的字面量只是兜底）；
     * · 表里没有 → 退回页面里的字面量（自纠错：漏登记不会显示空白，只是失去"唯一真源"）。
     * 所以 `.spot(id)` 不是"打个标记备查"，而是**声明"这一行的标题由索引表负责"**。
     * 搜索也是读那张表，因此登记一次 = 同时解决"标题一致"与"能被搜到"。
     *
     * ## 新系统为什么不直接依赖 `SettingsSpots`
     * 那个类在 `ui.settings` 包里、还牵着 `PageRegistry` / `ProviderNav`。新包不该反向依赖旧组件。
     * 所以这里只**存 id**，真正的查表由 [QzxList] 的 `spotTitle` 参数注入 ——
     * 依赖只出现在 App 外壳的一行里：`spotTitle = { SettingsSpots.find(it)?.title }`。
     */
    fun spot(id: String): QzxItemBuilder = apply { spotId = id }

    override fun snapshot(): QzxRow =
        QzxRow.Item(title, valueText, showArrow, clickAction, spotId, icon, descText)
}

/** 开关行。 */
class QzxSwitchBuilder internal constructor(
    private val title: String,
    private var checkedValue: Boolean,
    private val icon: Painter? = null
) : QzxRowHolder {
    private var changeAction: (Boolean) -> Unit = {}
    private var spotId: String? = null
    private var descText: String? = null

    /** 副说明。 */
    fun desc(text: String): QzxSwitchBuilder = apply { descText = text }

    /** 设置项索引 id（语义见 [QzxItemBuilder.spot]）。 */
    fun spot(id: String): QzxSwitchBuilder = apply { spotId = id }

    /** 值变化回调。 */
    fun onChecked(block: (Boolean) -> Unit): QzxSwitchBuilder = apply { changeAction = block }

    /** 覆盖当前值（一般不用 —— 值应由调用方状态驱动）。 */
    fun checked(v: Boolean): QzxSwitchBuilder = apply { checkedValue = v }

    override fun snapshot(): QzxRow =
        QzxRow.Toggle(title, checkedValue, changeAction, spotId, icon, descText)
}

/** 输入行。 */
class QzxInputBuilder internal constructor(
    private val title: String,
    private var valueText: String,
    private val icon: Painter? = null
) : QzxRowHolder {
    private var placeholderText: String? = null
    private var changeAction: (String) -> Unit = {}
    private var spotId: String? = null
    private var descText: String? = null

    /** 副说明。 */
    fun desc(text: String): QzxInputBuilder = apply { descText = text }

    /** 设置项索引 id（语义见 [QzxItemBuilder.spot]）。 */
    fun spot(id: String): QzxInputBuilder = apply { spotId = id }

    /** 空值时的提示。 */
    fun hint(text: String): QzxInputBuilder = apply { placeholderText = text }

    /** 值变化回调。 */
    fun onValueChange(block: (String) -> Unit): QzxInputBuilder = apply { changeAction = block }

    override fun snapshot(): QzxRow =
        QzxRow.Input(title, valueText, placeholderText, changeAction, spotId, icon, descText)
}

/** 选择行（点开一个下拉）。 */
class QzxSelectBuilder internal constructor(
    private val title: String,
    private var valueText: String?,
    private val options: List<QzxOption>,
    private val icon: Painter? = null
) : QzxRowHolder {
    private var selectAction: (QzxOption) -> Unit = {}
    private var spotId: String? = null
    private var descText: String? = null

    /** 副说明。 */
    fun desc(text: String): QzxSelectBuilder = apply { descText = text }

    /** 设置项索引 id（语义见 [QzxItemBuilder.spot]）。 */
    fun spot(id: String): QzxSelectBuilder = apply { spotId = id }

    /** 选中回调。 */
    fun onSelect(block: (QzxOption) -> Unit): QzxSelectBuilder = apply { selectAction = block }

    override fun snapshot(): QzxRow =
        QzxRow.Select(title, valueText, options, selectAction, spotId, icon, descText)
}

// ───────────────────────────── DSL 作用域 ─────────────────────────────

/** 一个分区（一张卡片）。 */
class QzxSectionScope internal constructor(
    internal val id: String,
    internal val title: String?,
    internal val footer: String?,
    internal val collapsible: Boolean = false,
    internal val expanded: Boolean = true,
    internal val onToggle: ((Boolean) -> Unit)? = null,
) {
    internal val holders = mutableListOf<QzxRowHolder>()

    /** 普通行：`item("标题", "右侧值").arrow().onClick { }` */
    fun item(
        title: String,
        value: String? = null,
        icon: Painter? = null
    ): QzxItemBuilder = QzxItemBuilder(title, value, icon).also { holders.add(it) }

    /** 开关行：`switch("自动更新", checked = true).onChecked { }` */
    fun switch(
        title: String,
        checked: Boolean,
        icon: Painter? = null
    ): QzxSwitchBuilder = QzxSwitchBuilder(title, checked, icon).also { holders.add(it) }

    /** 输入行：`input("API 地址", value = url).hint("https://…").onValueChange { }` */
    fun input(
        title: String,
        value: String,
        icon: Painter? = null
    ): QzxInputBuilder = QzxInputBuilder(title, value, icon).also { holders.add(it) }

    /** 选择行：`select("语言", value = lang, options = langs).onSelect { }` */
    fun select(
        title: String,
        value: String?,
        options: List<QzxOption>,
        icon: Painter? = null
    ): QzxSelectBuilder = QzxSelectBuilder(title, value, options, icon).also { holders.add(it) }

    /**
     * 显式分隔线。
     *
     * 【通常不需要】`autoDivider = true`（默认）会在相邻两行之间自动加线。
     * 这个方法用于关掉自动分隔后仍想在某处分段，或给 `custom { }` 之后补一条。
     * 与自动线相邻时会被[normalize]去重，不会出现两条。
     */
    fun divider() {
        holders.add(object : QzxRowHolder {
            override fun snapshot(): QzxRow = QzxRow.Divider
        })
    }

    /** 说明文字行（不依赖旧 `DhForm`）。 */
    fun note(text: String) {
        holders.add(object : QzxRowHolder {
            override fun snapshot(): QzxRow = QzxRow.Note(text)
        })
    }

    /** 逃生口：任意内容，仍然享系统的左右内边距与分隔线。 */
    fun custom(content: @Composable () -> Unit) {
        holders.add(object : QzxRowHolder {
            override fun snapshot(): QzxRow = QzxRow.Custom(content)
        })
    }
}

/** 整张列表。 */
class QzxListScope {
    internal val sections = mutableListOf<QzxSectionScope>()

    /**
     * 加一个分区。
     *
     * @param id 稳定标识（用于折叠态的持久化 / 测试定位）。
     * @param title 卡片**外面**上方的小标题（可为空）。
     * @param footer 卡片**外面**下方的说明文字（可为空）。
     */
    fun section(
        id: String,
        title: String? = null,
        footer: String? = null,
        /**
         * 是否可折叠。
         *
         * 【为什么必须有】旧系统的折叠卡有 **47 处**，而且折叠态写进
         * `PetPrefs.cardOpen` —— 用户已经习惯"进页面看到一列标题、点开才看内容"。
         * 没有它，迁完的设置页会**明显更难用**（这不是观感问题，是信息架构变化）。
         */
        collapsible: Boolean = false,
        /**
         * 当前是否展开。**只在给了 [onToggle] 时才被采纳**（状态由调用方持有）。
         * 不给 [onToggle] 时本组件内部 remember，[expanded] 只作初值。
         */
        expanded: Boolean = true,
        /** 折叠状态变化回调（通常写进 `PetPrefs.cardOpen`）。 */
        onToggle: ((Boolean) -> Unit)? = null,
        content: QzxSectionScope.() -> Unit
    ) {
        sections.add(
            QzxSectionScope(id, title, footer, collapsible, expanded, onToggle).apply(content)
        )
    }
}

// ───────────────────────────── 渲染 ─────────────────────────────

/**
 * 声明式列表。
 *
 * @param autoDivider 是否在相邻两行之间自动加分隔线（默认 true）。
 */
@Composable
fun QzxList(
    modifier: Modifier = Modifier,
    autoDivider: Boolean = true,
    /**
     * 设置项索引的**标题解析器**：`spotId → 标题`（查不到返回 null）。
     *
     * 【为什么是注入而不是直接读 `SettingsSpots`】那个类在 `ui.settings` 包里、还牵着
     * `PageRegistry` / `ProviderNav`；新系统反向依赖旧组件是不可接受的。
     * 依赖只出现在 App 外壳的**一行**里：
     * ```kotlin
     * QzxList(spotTitle = { SettingsSpots.find(it)?.title }) { … }
     * ```
     * 不传（默认 `{ null }`）= 全部用调用方写的字面量，行为与没有 `.spot()` 时完全一致。
     */
    spotTitle: (String) -> String? = { null },
    content: QzxListScope.() -> Unit,
) {
    // 每次重组重建模型：列表是**纯声明**，没有自己藏着的状态，
    // 重建成本只是一次小对象分配，换来"调用方状态变了就一定跟着变"。
    val scope = QzxListScope().apply(content)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        scope.sections.forEach { section ->
            QzxSection(section = section, autoDivider = autoDivider, spotTitle = spotTitle)
        }
    }
}

@Composable
private fun QzxSection(
    section: QzxSectionScope,
    autoDivider: Boolean,
    spotTitle: (String) -> String?,
) {
    val c = QzxColors
    // 【折叠状态归谁】调用方给了 onToggle ⇒ 状态在调用方（通常落盘到 PetPrefs）；
    //   没给 ⇒ 组件内部 remember（够用的默认值，且不会因为列表每帧重建模型而丢）。
    var innerExpanded by remember(section.id) { mutableStateOf(section.expanded) }
    val expanded = if (section.onToggle != null) section.expanded else innerExpanded
    fun toggle() {
        val next = !expanded
        if (section.onToggle != null) {
            section.onToggle(next)
        } else {
            innerExpanded = next
        }
    }

    // 卡外标签：可折叠时标题挪进卡内当页头，这里就不画了（否则一句话出现两次）。
    if (!section.collapsible && !section.title.isNullOrEmpty()) {
        Text(
            text = section.title,
            modifier = Modifier.padding(
                start = QzxTokens.Space.xs,
                bottom = QzxTokens.Space.sm
            ),
            color = c.sub,
            fontSize = QzxTokens.Type.SECTION_SP.sp,
            lineHeight = (QzxTokens.Type.SECTION_SP + QzxTokens.Type.LINE_GAP_SP).sp
        )
    }

    val rows = normalize(section.holders.map { it.snapshot() }, autoDivider)
    QzxSurface {
        if (section.collapsible) {
            // ── 卡内页头：整行可点，箭头随展开态翻转 ──────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = QzxTokens.Size.minTouch.dp)
                    .clickable(onClick = { toggle() })
                    .padding(
                        start = QzxTokens.Space.listH,
                        end = QzxTokens.Space.listH,
                        top = QzxTokens.Space.md,
                        bottom = QzxTokens.Space.md
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QzxRowTitle(section.title.orEmpty(), Modifier.weight(1f))
                Spacer(Modifier.width(QzxTokens.Space.xs))
                Box(
                    modifier = Modifier.graphicsLayer {
                        // 收起时朝下（"点我能展开"），展开时朝上
                        rotationZ = if (expanded) 180f else 0f
                    }
                ) {
                    QzxChevron(down = true)
                }
            }
        }
        // 【折叠时不占高度】用 AnimatedVisibility 而不是 alpha=0：
        //   后者仍参与 measure/layout，收起的分区会留一大块空白。
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(QzxTokens.Motion.SECTION_TOGGLE)
            ) + fadeIn(animationSpec = tween(QzxTokens.Motion.SECTION_TOGGLE)),
            exit = shrinkVertically(
                animationSpec = tween(QzxTokens.Motion.SECTION_TOGGLE)
            ) + fadeOut(animationSpec = tween(QzxTokens.Motion.SECTION_TOGGLE))
        ) {
            Column {
                rows.forEach { row ->
                    when (row) {
                        QzxRow.Divider -> QzxDivider()
                        else -> QzxRowView(row, spotTitle)
                    }
                }
            }
        }
    }

    if (!section.footer.isNullOrEmpty()) {
        Text(
            text = section.footer,
            modifier = Modifier.padding(
                start = QzxTokens.Space.xs,
                top = QzxTokens.Space.sm,
                end = QzxTokens.Space.xs
            ),
            color = c.sub,
            fontSize = QzxTokens.Type.FOOTER_SP.sp,
            lineHeight = (QzxTokens.Type.FOOTER_SP + QzxTokens.Type.LINE_GAP_SP).sp
        )
    }
    Spacer(Modifier.height(QzxTokens.Space.cardGap))
}

@Composable
private fun QzxDivider() {
    // 【缩进从 listH 开始，不横贯】分隔线如果顶到卡片边缘，会像"卡片被切成两半"；
    //   从内容缩进处起笔，视觉上它属于"行与行之间"而不是"卡片的分界线"。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = QzxTokens.Space.listH)
            .height(QzxTokens.Size.hairline)
            .background(QzxColors.line.copy(alpha = 0.45f))
    )
}

/** 行高由系统给：下限 = 单行/双行档，内容更长时自动长高。 */
private fun rowMinHeight(row: QzxRow): Int {
    val twoLine = QzxTokens.Size.rowTwoLine
    val oneLine = QzxTokens.Size.rowOneLine
    // 【有副说明就是双行】行高由"内容需要几行"决定，而不是由行型决定 ——
    //   这样同一个 item 加不加 desc 都能自适应，调用方不必知道 56/64 这两个数。
    fun pick(desc: String?, fallback: Int) = if (desc.isNullOrEmpty()) fallback else twoLine
    return when (row) {
        is QzxRow.Item -> pick(row.desc, oneLine)
        is QzxRow.Toggle -> pick(row.desc, oneLine)
        is QzxRow.Select -> pick(row.desc, oneLine)
        is QzxRow.Input -> twoLine
        else -> 0
    }
}

/** 行前置图标的边长。`Space.xl`(20dp) 是全系统唯一一档 20dp —— 借用它，避免再开一个令牌。 */
private val ROW_ICON_SIZE = QzxTokens.Space.xl

@Composable
private fun QzxRowView(row: QzxRow, spotTitle: (String) -> String?) {
    val c = QzxColors
    // 【反向取标题】索引表有这条就用表里的标题；没有就退回调用方写的字面量。
    //   与旧 `SettingsSpots.spotTitle(spot, fallback)` 同一条规则：
    //   漏登记不会让标题变空（那会让整页面目全非），只是失去"唯一真源"。
    fun titleOf(literal: String, id: String?): String =
        id?.let(spotTitle)?.takeIf { it.isNotBlank() } ?: literal
    val minH = rowMinHeight(row)
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = minH.dp)
        .padding(
            start = QzxTokens.Space.listH,
            end = QzxTokens.Space.listH,
            top = QzxTokens.Space.sm,
            bottom = QzxTokens.Space.sm
        )

    when (row) {
        is QzxRow.Item -> {
            Row(
                modifier = base.then(
                    if (row.onClick != null) {
                        Modifier
                            // 行本身的下限已经 ≥ rowOneLine，这里再声明一次命中区下限：
                            // 它保证"将来有人把行高调到 40"时仍然点得到。
                            .heightIn(min = QzxTokens.Size.minTouch.dp)
                            .clickable(onClick = row.onClick)
                    } else {
                        Modifier
                    }
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QzxRowLeading(
                    title = titleOf(row.title, row.spotId),
                    desc = row.desc,
                    icon = row.icon,
                    modifier = Modifier.weight(1f)
                )
                if (!row.value.isNullOrEmpty()) {
                    Text(
                        text = row.value,
                        modifier = Modifier.padding(start = QzxTokens.Space.rowTitleGap),
                        color = c.sub,
                        fontSize = QzxTokens.Type.ROW_VALUE_SP.sp,
                        lineHeight = (QzxTokens.Type.ROW_VALUE_SP + QzxTokens.Type.LINE_GAP_SP).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (row.arrow) {
                    Spacer(Modifier.width(QzxTokens.Space.xs))
                    QzxChevron()
                }
            }
        }

        is QzxRow.Toggle -> {
            Row(modifier = base, verticalAlignment = Alignment.CenterVertically) {
                QzxRowLeading(
                    title = titleOf(row.title, row.spotId),
                    desc = row.desc,
                    icon = row.icon,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = row.checked, onCheckedChange = row.onChecked)
            }
        }

        is QzxRow.Select -> {
            var expanded by remember { mutableStateOf(false) }
            val current = row.options.firstOrNull { it.value == row.value }?.label ?: row.value
            Box(modifier = base) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = QzxTokens.Size.minTouch.dp)
                        .clickable { expanded = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QzxRowLeading(
                        title = titleOf(row.title, row.spotId),
                        desc = row.desc,
                        icon = row.icon,
                        modifier = Modifier.weight(1f)
                    )
                    if (!current.isNullOrEmpty()) {
                        Text(
                            text = current,
                            modifier = Modifier.padding(start = QzxTokens.Space.rowTitleGap),
                            color = c.sub,
                            fontSize = QzxTokens.Type.ROW_VALUE_SP.sp,
                            lineHeight = (QzxTokens.Type.ROW_VALUE_SP +
                                QzxTokens.Type.LINE_GAP_SP).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(QzxTokens.Space.xs))
                    QzxChevron(down = true)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    row.options.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(text = opt.label) },
                            onClick = {
                                expanded = false
                                row.onSelect(opt)
                            }
                        )
                    }
                }
            }
        }

        is QzxRow.Input -> {
            var text by remember(row.value) { mutableStateOf(row.value) }
            Column(modifier = base) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QzxRowLeading(
                        title = titleOf(row.title, row.spotId),
                        desc = row.desc,
                        icon = row.icon,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(QzxTokens.Space.xs))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = QzxTokens.Size.field)
                        .clip(RoundedCornerShape(QzxTokens.Radius.inner))
                        .background(c.field)
                        .padding(horizontal = QzxTokens.Space.md),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            row.onValueChange(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = c.title,
                            fontSize = QzxTokens.Type.ROW_VALUE_SP.sp
                        ),
                        cursorBrush = SolidColor(c.accent)
                    )
                    if (text.isEmpty() && !row.placeholder.isNullOrEmpty()) {
                        Text(
                            text = row.placeholder,
                            color = c.sub.copy(alpha = 0.65f),
                            fontSize = QzxTokens.Type.ROW_VALUE_SP.sp,
                            lineHeight = (QzxTokens.Type.ROW_VALUE_SP +
                                QzxTokens.Type.LINE_GAP_SP).sp
                        )
                    }
                }
            }
        }

        is QzxRow.Note -> {
            // 说明文字：由系统排版（左右内边距与行一致、字号走令牌），
            // 不依赖旧 `DhForm.Note`。
            Text(
                text = row.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = QzxTokens.Space.listH,
                        end = QzxTokens.Space.listH,
                        top = QzxTokens.Space.sm,
                        bottom = QzxTokens.Space.sm
                    ),
                color = c.sub,
                fontSize = QzxTokens.Type.FOOTER_SP.sp,
                lineHeight = (QzxTokens.Type.FOOTER_SP + QzxTokens.Type.LINE_GAP_SP).sp
            )
        }

        is QzxRow.Custom -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = QzxTokens.Space.listH,
                        vertical = QzxTokens.Space.sm
                    )
            ) {
                row.content()
            }
        }

        QzxRow.Divider -> QzxDivider()
    }
}

/**
 * 行左侧的"图标 + 标题(+副说明)"组合。
 *
 * 【为什么图标是 `Painter` 而不是资源 id】新系统**不持有任何图标资源**：
 * 旧包 `ui.theme.Icons` 是上一代的 drawable，用户要求"原来的元素一个都不要了"。
 * 收 `Painter?` 意味着调用方想给什么就给什么（旧资源、`ImageVector`、将来 `QzxIcons`），
 * 而新系统对资源零依赖 —— 这是唯一"不碰资源又不挡路"的形态。
 */
@Composable
private fun QzxRowLeading(
    title: String,
    desc: String?,
    icon: Painter?,
    modifier: Modifier = Modifier,
) {
    if (icon != null) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(ROW_ICON_SIZE),
            tint = QzxColors.sub
        )
        Spacer(Modifier.width(QzxTokens.Space.md))
    }
    Column(modifier = modifier) {
        QzxRowTitle(title)
        if (!desc.isNullOrEmpty()) {
            Text(
                text = desc,
                color = QzxColors.sub,
                fontSize = QzxTokens.Type.FOOTER_SP.sp,
                lineHeight = (QzxTokens.Type.FOOTER_SP + QzxTokens.Type.LINE_GAP_SP).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QzxRowTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = QzxColors.title,
        fontSize = QzxTokens.Type.ROW_TITLE_SP.sp,
        lineHeight = (QzxTokens.Type.ROW_TITLE_SP + QzxTokens.Type.LINE_GAP_SP).sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 画一个箭头。
 *
 * 【为什么手画而不是用图标资源】新系统要能独立成立 —— 一个 12dp 的箭头不值得让它
 * 依赖旧包的 drawable 目录（那是旧系统的资产）。两条线，5 行代码。
 */
@Composable
private fun QzxChevron(down: Boolean = false) {
    val color = QzxColors.sub.copy(alpha = 0.75f)
    Canvas(modifier = Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        if (down) {
            drawLine(color, Offset(w * 0.2f, h * 0.35f), Offset(w * 0.5f, h * 0.65f),
                strokeWidth = w * 0.12f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.65f), Offset(w * 0.8f, h * 0.35f),
                strokeWidth = w * 0.12f, cap = StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * 0.35f, h * 0.2f), Offset(w * 0.65f, h * 0.5f),
                strokeWidth = w * 0.12f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.65f, h * 0.5f), Offset(w * 0.35f, h * 0.8f),
                strokeWidth = w * 0.12f, cap = StrokeCap.Round)
        }
    }
}

/**
 * 分隔线规范化的**唯一实现**。
 *
 * 做三件事：
 * 1. `autoDivider = true` 时，在**相邻两个都非空**的行之间插入分隔线；
 * 2. 去掉连续的重复线（显式 `divider()` + 自动线会撞在一起）；
 * 3. 去掉开头/结尾的线（卡片上下边缘不该有一条悬空的线）。
 *
 * 【为什么在这里做而不是渲染时躲】"有没有线"是**数据**问题，不是绘制问题；
 * 放在渲染里躲会让 `rows.forEach` 到处带下标记，而且没法为将来的动画（比如折叠时
 * 线淡出）提供稳定的 key。规范化一次，渲染就只管画。
 */
internal fun normalize(rows: List<QzxRow>, autoDivider: Boolean): List<QzxRow> {
    val out = mutableListOf<QzxRow>()
    rows.forEachIndexed { index, row ->
        if (index > 0 && autoDivider) {
            val prev = rows[index - 1]
            if (prev !is QzxRow.Divider && row !is QzxRow.Divider) {
                out.add(QzxRow.Divider)
            }
        }
        // 连续两条线只留一条
        if (row is QzxRow.Divider && out.lastOrNull() is QzxRow.Divider) {
            return@forEachIndexed
        }
        out.add(row)
    }
    while (out.firstOrNull() is QzxRow.Divider) {
        out.removeAt(0)
    }
    while (out.lastOrNull() is QzxRow.Divider) {
        out.removeAt(out.size - 1)
    }
    return out
}
