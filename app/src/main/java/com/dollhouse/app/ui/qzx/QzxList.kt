package com.dollhouse.app.ui.qzx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
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

/**
 * 预览区块里的一个动作。
 *
 * 【为什么是数据类而不是 `@Composable () -> Unit`】数据类只有 `label` 与 `onClick` 两个自由度，
 * 调用方**无法**在这里塞 `padding` / `height` / 自定义 `Row`。布局（行高、图标位、危险色）
 * 全归系统。这是"声明式"能守住的关键。
 */
data class QzxPreviewAction(
    val label: String,
    /** 危险动作（删除 / 清空）：文字用错误色，且永远排在最后。 */
    val destructive: Boolean = false,
    /**
     * 【为什么 onClick 排在最后】Kotlin 的 trailing lambda 绑定到**最后一个函数类型参数**。
     *   若它排在 `destructive` 之前，`QzxPreviewAction("移除头像", destructive = true) { … }`
     *   里的 lambda 会绑到 `destructive`（Boolean）上报类型错误，而调用方只看到
     *   「No value passed for parameter 'onClick'」这种看不出原因的提示。
     *   排在最后后，`QzxPreviewAction("更换头像") { … }` 与
     *   `QzxPreviewAction("移除头像", destructive = true) { … }` **两种写法都成立**。
     */
    val onClick: () -> Unit
)

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
        /** 前置图标资源 id（`R.drawable.*`）。由调用方给 —— 新系统不持有图标资源。 */
        val iconRes: Int? = null,
        /**
         * 已解析的位图图标（如人偶封面 `BitmapPainter`）。
         *
         * 【为什么它与 `iconRes` 并存，而不是"两条路"】
         *   `iconRes` 是**编译期常量**（`Icons.IC_WHALE`），渲染期用 `painterResource` 解析；
         *   `iconPainter` 是**运行时位图**（用户上传的封面），根本没有资源 id 可给。
         *   两者对应两类本质不同的东西 —— 不是同一个能力的两种写法。
         *
         *   优先级：`iconRes` 非空时用资源，否则用 `iconPainter`。
         */
        val iconPainter: Painter? = null,
        /** 副说明：有值时行高自动升到 `rowTwoLine`。*/
        val desc: String? = null,
        /** 值文字的颜色覆盖（null = 默认副文本色）。*/
        val valueTint: Color? = null,
        /** 危险行：**整行标题**用错误色（删除类行必须与普通行一眼分开）。*/
        val danger: Boolean = false,
        /**
         * 长按动作（null = 不响应长按）。
         *
         * 【为什么需要它】记忆列表这类「点按编辑 + 长按删除」的交互，只在 `onClick` 里
         *   表达不了 —— 把删除挪到别的按钮上是**行为改变**，不是等价迁移。
         */
        val onLongClick: (() -> Unit)? = null,
        /** 是否可点。false = 整行变淡且不响应点击（"此刻不能做这件事"）。*/
        val enabled: Boolean = true,
        /** 标题颜色覆盖（状态色：连接成功/失败这类**数据驱动**的颜色，不是样式选择）。*/
        val titleTint: Color? = null
    ) : QzxRow

    data class Toggle(
        val title: String,
        val checked: Boolean,
        val onChecked: (Boolean) -> Unit,
        val spotId: String? = null,
        /** 前置图标资源 id（`R.drawable.*`）。由调用方给 —— 新系统不持有图标资源。 */
        val iconRes: Int? = null,
        /**
         * 已解析的位图图标（如人偶封面 `BitmapPainter`）。
         *
         * 【为什么它与 `iconRes` 并存，而不是"两条路"】
         *   `iconRes` 是**编译期常量**（`Icons.IC_WHALE`），渲染期用 `painterResource` 解析；
         *   `iconPainter` 是**运行时位图**（用户上传的封面），根本没有资源 id 可给。
         *   两者对应两类本质不同的东西 —— 不是同一个能力的两种写法。
         *
         *   优先级：`iconRes` 非空时用资源，否则用 `iconPainter`。
         */
        val iconPainter: Painter? = null,
        /** 可交互与否。false = 整行变淡（0.38）且开关不响应 —— 旧 CapRow 的 alpha = 0.45f 同语义。 */
        val enabled: Boolean = true,
        val desc: String? = null,
        val valueTint: Color? = null
    ) : QzxRow

    data class Input(
        val title: String,
        val value: String,
        val placeholder: String?,
        val onValueChange: (String) -> Unit,
        val spotId: String? = null,
        /** 前置图标资源 id（`R.drawable.*`）。由调用方给 —— 新系统不持有图标资源。 */
        val iconRes: Int? = null,
        /**
         * 已解析的位图图标（如人偶封面 `BitmapPainter`）。
         *
         * 【为什么它与 `iconRes` 并存，而不是"两条路"】
         *   `iconRes` 是**编译期常量**（`Icons.IC_WHALE`），渲染期用 `painterResource` 解析；
         *   `iconPainter` 是**运行时位图**（用户上传的封面），根本没有资源 id 可给。
         *   两者对应两类本质不同的东西 —— 不是同一个能力的两种写法。
         *
         *   优先级：`iconRes` 非空时用资源，否则用 `iconPainter`。
         */
        val iconPainter: Painter? = null,
        val desc: String? = null,
        val valueTint: Color? = null,
        /** 字数上限：非空时右侧显示「当前长度/上限」。 */
        val counterMax: Int? = null,
        /** 多行输入的最小高度（null = 单行）。 */
        val multilineMinHeight: Int? = null,
        /**
         * 输入框右端的尾置图标与动作（如搜索框的「关闭搜索」）。
         *
         * 【为什么图标由调用方给】与 `QzxTitleBar.backIcon` 同一条原则：
         *   新系统不持有任何图标资源，删旧资源时编译不过的地方就是还没迁完的地方。
         */
        val trailingIconRes: Int? = null,
        val trailingAction: (() -> Unit)? = null
    ) : QzxRow

    data class Select(
        val title: String,
        val value: String?,
        val options: List<QzxOption>,
        val onSelect: (QzxOption) -> Unit,
        val spotId: String? = null,
        /** 前置图标资源 id（`R.drawable.*`）。由调用方给 —— 新系统不持有图标资源。 */
        val iconRes: Int? = null,
        /**
         * 已解析的位图图标（如人偶封面 `BitmapPainter`）。
         *
         * 【为什么它与 `iconRes` 并存，而不是"两条路"】
         *   `iconRes` 是**编译期常量**（`Icons.IC_WHALE`），渲染期用 `painterResource` 解析；
         *   `iconPainter` 是**运行时位图**（用户上传的封面），根本没有资源 id 可给。
         *   两者对应两类本质不同的东西 —— 不是同一个能力的两种写法。
         *
         *   优先级：`iconRes` 非空时用资源，否则用 `iconPainter`。
         */
        val iconPainter: Painter? = null,
        val desc: String? = null,
        val valueTint: Color? = null
    ) : QzxRow

    /**
     * 说明文字行（`note`）。
     *
     * 【为什么要有】设置页大量需要"一段解释"（旧系统走 `DhForm.Note`）。
     * 新系统自带这一行型：**不依赖旧 `DhForm`**，字号/行高/颜色由令牌决定，
     * 左右内边距与上下文一致。
     */
    data class Note(
        val text: String,
        /** 文字颜色（null = 副文本色）。错误态传 `QzxColors.danger` —— 语义色不是装饰。 */
        val color: Color? = null
    ) : QzxRow

    /**
     * 预览区块（头像 / 大图 + 按钮组）。
     *
     * 【为什么是数据不是 lambda】见 [QzxSectionScope.preview] 的注释 ——
     * 一旦让调用方塞 lambda，这个"行型"就退化成一个逃生口，排版又散回页面里。
     */
    data class Preview(
        val image: Painter?,
        val fallbackIconRes: Int?,
        val title: String?,
        val subtitle: String?,
        val actions: List<QzxPreviewAction>,
        val hint: String?
    ) : QzxRow

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
    private val iconRes: Int? = null
) : QzxRowHolder {
    internal var iconPainterValue: Painter? = null
    private var showArrow = false
    private var clickAction: (() -> Unit)? = null
    private var longClickAction: (() -> Unit)? = null
    private var spotId: String? = null
    private var descText: String? = null
    private var valueTintColor: Color? = null
    private var dangerRow = false
    private var rowEnabled = true
    private var titleTintColor: Color? = null

    /**
     * 是否可点。`false` 时整行变淡、不挂点击。
     *
     * 【为什么需要】"此刻不能做这件事"是**状态**而不是"忘了写 onClick"：
     *   下载按钮在已有文件时必须置灰可读，而不是消失或点了没反应。
     */
    fun enabled(on: Boolean): QzxItemBuilder = apply { rowEnabled = on }

    /**
     * 标题用**数据色**（例如连接状态：绿=已连接 / 红=失败）。
     *
     * 【与 [danger] 的分工】`danger()` 是"这一类操作危险"（静态语义）；
     *   本方法是"这一行此刻处于什么状态"（动态数据）。两者都不让调用方挑选具体色值。
     */
    fun titleTint(color: Color?): QzxItemBuilder = apply { titleTintColor = color }

    /**
     * 危险行：整行标题用 [QzxColors.danger]。
     *
     * 【为什么是语义而不是让你传色值】"删除"这一类的行必须在**任何主题**下都明显不同于普通行；
     * 让调用方传 `Color` 就等于每页各挑一个红。这里只表达"这是危险操作"，具体红由系统给。
     * **不影响值文字**：值仍走 `valueTint` / 默认副文本色（除非你另外声明）。
     */
    fun danger(): QzxItemBuilder = apply { dangerRow = true }

    /** 副说明（第二行小字）。有它时行高自动升到 `rowTwoLine`。 */
    fun desc(text: String): QzxItemBuilder = apply { descText = text }

    /** 值文字用强调色（`null` = 默认副文本色；也可传 `QzxColors.danger` 等语义色）。 */
    fun valueTint(color: Color? = QzxColors.accent): QzxItemBuilder =
        apply { valueTintColor = color }

    /** 右侧显示一个箭头（表示"点进去还有一层"）。 */
    fun arrow(show: Boolean = true): QzxItemBuilder = apply { showArrow = show }

    /** 覆盖右侧值。 */
    fun value(v: String?): QzxItemBuilder = apply { valueText = v }

    /** 点击回调。不设 = 不可点（此时不画箭头，也不会有按压反馈）。 */
    fun onClick(block: () -> Unit): QzxItemBuilder = apply { clickAction = block }

    /** 长按动作（如记忆列表的「长按删除」）。与 [onClick] 可共存。 */
    fun onLongClick(block: () -> Unit): QzxItemBuilder = apply { longClickAction = block }

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
        QzxRow.Item(
            title = title,
            value = valueText,
            arrow = showArrow,
            onClick = if (rowEnabled) clickAction else null,
            spotId = spotId,
            iconRes = iconRes,
            iconPainter = iconPainterValue,
            desc = descText,
            valueTint = valueTintColor,
            danger = dangerRow,
            onLongClick = if (rowEnabled) longClickAction else null,
            enabled = rowEnabled,
            titleTint = titleTintColor
        )
}

/** 开关行。 */
class QzxSwitchBuilder internal constructor(
    private val title: String,
    private var checkedValue: Boolean,
    private val iconRes: Int? = null
) : QzxRowHolder {
    private var iconPainter: Painter? = null
    private var changeAction: (Boolean) -> Unit = {}
    private var enabledValue: Boolean = true
    private var iconPainterValue: Painter? = null
    private var spotId: String? = null
    private var descText: String? = null
    private var valueTintColor: Color? = null

    /** 副说明。 */
    fun desc(text: String): QzxSwitchBuilder = apply { descText = text }

    /** 值文字用强调色（开关行一般不需要，保留是为了四类行 API 一致）。 */
    fun valueTint(color: Color? = QzxColors.accent): QzxSwitchBuilder =
        apply { valueTintColor = color }

    /** 设置项索引 id（语义见 [QzxItemBuilder.spot]）。 */
    fun spot(id: String): QzxSwitchBuilder = apply { spotId = id }

    /** 可交互与否（false 时整行变淡、开关不响应）。 */
    fun enabled(v: Boolean): QzxSwitchBuilder = apply { enabledValue = v }

    /** 值变化回调。 */
    fun onChecked(block: (Boolean) -> Unit): QzxSwitchBuilder = apply { changeAction = block }

    /** 覆盖当前值（一般不用 —— 值应由调用方状态驱动）。 */
    fun checked(v: Boolean): QzxSwitchBuilder = apply { checkedValue = v }

    override fun snapshot(): QzxRow =
        QzxRow.Toggle(title = title, checked = checkedValue, onChecked = changeAction, spotId = spotId, iconRes = iconRes, iconPainter = iconPainterValue, desc = descText, valueTint = valueTintColor, enabled = enabledValue)
}

/** 输入行。 */
class QzxInputBuilder internal constructor(
    private val title: String,
    private var valueText: String,
    private val iconRes: Int? = null
) : QzxRowHolder {
    private var iconPainter: Painter? = null
    private var placeholderText: String? = null
    private var changeAction: (String) -> Unit = {}
    private var spotId: String? = null
    private var descText: String? = null
    private var valueTintColor: Color? = null
    private var counterLimit: Int? = null
    private var multilineHeight: Int? = null
    private var trailingIconResValue: Int? = null
    private var trailingActionValue: (() -> Unit)? = null

    /**
     * 多行输入：规则文本这类内容必须能看到好几行，单行输入会把它们藏在横向滚动里。
     * @param minHeight 最小高度（dp）
     */
    fun multiline(minHeight: Int = 80): QzxInputBuilder = apply { multilineHeight = minHeight }

    /** 副说明。 */
    fun desc(text: String): QzxInputBuilder = apply { descText = text }

    /**
     * 字数计数：系统自己算「当前长度/上限」并画在行右侧。
     *
     * 【为什么收 `max` 而不是收 lambda】收 lambda 等于把"怎么画"交回调用方 ——
     * 十个人会写出十种计数样式。这里只给"上限"这一个数字，样式归系统。
     */
    fun counter(max: Int): QzxInputBuilder = apply { counterLimit = max }

    /** 值文字用强调色。 */
    fun valueTint(color: Color? = QzxColors.accent): QzxInputBuilder =
        apply { valueTintColor = color }

    /** 设置项索引 id（语义见 [QzxItemBuilder.spot]）。 */
    fun spot(id: String): QzxInputBuilder = apply { spotId = id }

    /** 空值时的提示。 */
    fun hint(text: String): QzxInputBuilder = apply { placeholderText = text }

    /** 值变化回调。 */
    fun onValueChange(block: (String) -> Unit): QzxInputBuilder = apply { changeAction = block }

    /** 输入框右端的尾置图标与动作（如搜索框的「关闭搜索」）。 */
    fun trailing(iconRes: Int, onClick: () -> Unit): QzxInputBuilder = apply {
        trailingIconResValue = iconRes
        trailingActionValue = onClick
    }

    override fun snapshot(): QzxRow =
        QzxRow.Input(
            title = title,
            value = valueText,
            placeholder = placeholderText,
            onValueChange = changeAction,
            spotId = spotId,
            iconRes = iconRes,
            desc = descText,
            valueTint = valueTintColor,
            counterMax = counterLimit,
            multilineMinHeight = multilineHeight,
            trailingIconRes = trailingIconResValue,
            trailingAction = trailingActionValue
        )
}

/** 选择行（点开一个下拉）。 */
class QzxSelectBuilder internal constructor(
    private val title: String,
    private var valueText: String?,
    private val options: List<QzxOption>,
    private val iconRes: Int? = null
) : QzxRowHolder {
    private var iconPainter: Painter? = null
    private var selectAction: (QzxOption) -> Unit = {}
    private var spotId: String? = null
    private var descText: String? = null
    private var valueTintColor: Color? = null

    /** 副说明。 */
    fun desc(text: String): QzxSelectBuilder = apply { descText = text }

    /** 值文字用强调色。 */
    fun valueTint(color: Color? = QzxColors.accent): QzxSelectBuilder =
        apply { valueTintColor = color }

    /** 设置项索引 id（语义见 [QzxItemBuilder.spot]）。 */
    fun spot(id: String): QzxSelectBuilder = apply { spotId = id }

    /** 选中回调。 */
    fun onSelect(block: (QzxOption) -> Unit): QzxSelectBuilder = apply { selectAction = block }

    override fun snapshot(): QzxRow =
        QzxRow.Select(title = title, value = valueText, options = options, onSelect = selectAction, spotId = spotId, iconRes = iconRes, desc = descText, valueTint = valueTintColor)
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
    /** 分区标题左侧图标（旧 `Card(titleIcon=…)` 的等价物）。 */
    internal val titleIconRes: Int? = null,
    /** 嵌入形态：不画卡片底 / 圆角 / 边缘光（用于"已经在父卡片内部"的分区）。 */
    internal val flat: Boolean = false,
) {
    internal val holders = mutableListOf<QzxRowHolder>()

    /** 普通行：`item("标题", "右侧值").arrow().onClick { }` */
    fun item(
        title: String,
        value: String? = null,
        iconRes: Int? = null,
        iconPainter: Painter? = null
    ): QzxItemBuilder = QzxItemBuilder(title, value, iconRes)
        .also { it.iconPainterValue = iconPainter }
        .also { holders.add(it) }

    /** 开关行：`switch("自动更新", checked = true).onChecked { }` */
    fun switch(
        title: String,
        checked: Boolean,
        iconRes: Int? = null
    ): QzxSwitchBuilder = QzxSwitchBuilder(title, checked, iconRes).also { holders.add(it) }

    /** 输入行：`input("API 地址", value = url).hint("https://…").onValueChange { }` */
    fun input(
        title: String,
        value: String,
        iconRes: Int? = null
    ): QzxInputBuilder = QzxInputBuilder(title, value, iconRes).also { holders.add(it) }

    /** 选择行：`select("语言", value = lang, options = langs).onSelect { }` */
    fun select(
        title: String,
        value: String?,
        options: List<QzxOption>,
        iconRes: Int? = null
    ): QzxSelectBuilder = QzxSelectBuilder(title, value, options, iconRes).also { holders.add(it) }

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

    /**
     * 说明文字行（不依赖旧 `DhForm`）。
     *
     * @param color 文字颜色，默认副文本色。**错误 / 警告必须传语义色**
     *              （`QzxColors.danger` 等）—— 错误提示与普通说明同色，用户扫一眼分不出
     *              哪条是"出问题了"，那是语义丢失而不只是不好看。
     */
    fun note(text: String, color: Color? = null) {
        holders.add(object : QzxRowHolder {
            override fun snapshot(): QzxRow = QzxRow.Note(text, color)
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
        /** 分区标题左侧图标。 */
        titleIconRes: Int? = null,
        /**
         * 嵌入形态：**不画**卡片底 / 圆角 / 边缘光，直接把行铺在父容器上。
         *
         * 【什么时候用】这个分区已经在**另一张卡片内部**（例如「实验卡片展开后的正文」）。
         * 再套一层卡片会出现双层圆角与双层边缘光 —— 那不是设计，是穿模。
         * 分隔线仍然缩进，因为它属于"行与行之间"，与卡片形态无关。
         */
        flat: Boolean = false,
        content: QzxSectionScope.() -> Unit
    ) {
        sections.add(
            QzxSectionScope(
                id, title, footer, collapsible, expanded, onToggle, titleIconRes, flat
            ).apply(content)
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
        Row(
            modifier = Modifier.padding(start = QzxTokens.Space.xs, bottom = QzxTokens.Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            section.titleIconRes?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    modifier = Modifier.size(ROW_ICON_SIZE * 0.8f),
                    tint = c.sub
                )
                Spacer(Modifier.width(QzxTokens.Space.xs))
            }
            Text(
                text = section.title,
                color = c.sub,
                fontSize = QzxTokens.Type.SECTION_SP.sp,
                lineHeight = (QzxTokens.Type.SECTION_SP + QzxTokens.Type.LINE_GAP_SP).sp
            )
        }
    }

    val rows = normalize(section.holders.map { it.snapshot() }, autoDivider)
    // 【flat = 不套卡片】直接在父容器上排行；分隔线照旧缩进（它属于行间，不属于卡片）。
    // 显式 Column 包一层：`AnimatedVisibility` 的展开动画要用 ColumnScope 的重载，
    // 而 flat 形态没有 QzxSurface 提供的 ColumnScope。
    val body: @Composable () -> Unit = {
        Column {
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
                section.titleIconRes?.let {
                    Icon(
                        painter = painterResource(it),
                        contentDescription = null,
                        modifier = Modifier.size(ROW_ICON_SIZE),
                        tint = c.sub
                    )
                    Spacer(Modifier.width(QzxTokens.Space.md))
                }
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
    }
    if (section.flat) {
        body()
    } else {
        QzxSurface { body() }
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

/** 不可点行的整体透明度（QZX 的禁用态也是"变淡但仍可读"）。 */
private const val DISABLED_ROW_ALPHA = 0.38f

/** 预览区块的头像边长（72dp：足够看清脸，又不至于把行撑成"大卡片"）。 */
private val PREVIEW_SIZE = QzxTokens.Space.xxl * 3

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
                modifier = base
                    .alpha(if (row.enabled) 1f else DISABLED_ROW_ALPHA)
                    .then(
                    if (row.onClick == null && row.onLongClick == null) {
                        Modifier
                    } else {
                        Modifier
                            // 行本身的下限已经 ≥ rowOneLine，这里再声明一次命中区下限：
                            // 它保证"将来有人把行高调到 40"时仍然点得到。
                            .heightIn(min = QzxTokens.Size.minTouch.dp)
                            // 【为什么用 combinedClickable】长按要能和点击共存（点按编辑 /
                            //   长按删除）；`clickable` 不支持长按。
                            .combinedClickable(
                                onClick = row.onClick ?: {},
                                onLongClick = row.onLongClick
                            )
                    }
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QzxRowLeading(
                    title = titleOf(row.title, row.spotId),
                    desc = row.desc,
                    iconRes = row.iconRes,
                    iconPainter = row.iconPainter,
                    titleColor = row.titleTint ?: if (row.danger) c.danger else null,
                    modifier = Modifier.weight(1f)
                )
                if (!row.value.isNullOrEmpty()) {
                    Text(
                        text = row.value,
                        modifier = Modifier.padding(start = QzxTokens.Space.rowTitleGap),
                        color = row.valueTint ?: c.sub,
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
            Row(
                modifier = base.alpha(if (row.enabled) 1f else DISABLED_ROW_ALPHA),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QzxRowLeading(
                    title = titleOf(row.title, row.spotId),
                    desc = row.desc,
                    iconRes = row.iconRes,
                    iconPainter = row.iconPainter,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = row.checked,
                    // 禁用时传 null —— Compose 的约定是"没给回调就不响应"，比 alpha 更彻底。
                    onCheckedChange = if (row.enabled) row.onChecked else null
                )
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
                        iconRes = row.iconRes,
                    iconPainter = row.iconPainter,
                        modifier = Modifier.weight(1f)
                    )
                    if (!current.isNullOrEmpty()) {
                        Text(
                            text = current,
                            modifier = Modifier.padding(start = QzxTokens.Space.rowTitleGap),
                            color = row.valueTint ?: c.sub,
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
                        iconRes = row.iconRes,
                    iconPainter = row.iconPainter,
                        modifier = Modifier.weight(1f)
                    )
                    row.counterMax?.let { max ->
                        Text(
                            text = text.length.toString() + "/" + max,
                            modifier = Modifier.padding(start = QzxTokens.Space.sm),
                            color = c.sub,
                            fontSize = QzxTokens.Type.FOOTER_SP.sp,
                            lineHeight = (QzxTokens.Type.FOOTER_SP +
                                QzxTokens.Type.LINE_GAP_SP).sp
                        )
                    }
                }
                Spacer(Modifier.height(QzxTokens.Space.xs))
                // 【聚焦态】BasicTextField 必须显式接 interactionSource，否则聚焦时没有任何
                //   视觉反馈（用户不知道光标在哪）。这是 I6 门禁的要求，也是可用性要求。
                val fieldSource = remember { MutableInteractionSource() }
                val focused by fieldSource.collectIsFocusedAsState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = QzxTokens.Size.field)
                        .clip(RoundedCornerShape(QzxTokens.Radius.inner))
                        .background(c.field)
                        // 聚焦时边框用强调色 —— 与旧 UiKit 输入框同口径。
                        .border(
                            width = QzxTokens.Size.hairline,
                            color = if (focused) c.accent else Color.Transparent,
                            shape = RoundedCornerShape(QzxTokens.Radius.inner)
                        )
                        .padding(horizontal = QzxTokens.Space.md),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            row.onValueChange(it)
                        },
                        interactionSource = fieldSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (row.multilineMinHeight != null) {
                                    Modifier.heightIn(min = row.multilineMinHeight.dp)
                                } else {
                                    Modifier
                                }
                            ),
                        singleLine = row.multilineMinHeight == null,
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
                    // 【尾置动作】搜索框的「关闭」之类。图标由调用方给（新系统不持有图标资源）。
                    //   命中区仍是 minTouch —— 它只有图标大小，但必须点得到。
                    val tIcon = row.trailingIconRes
                    val tAct = row.trailingAction
                    if (tIcon != null && tAct != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .sizeIn(minWidth = QzxTokens.Size.minTouch.dp, minHeight = QzxTokens.Size.minTouch.dp)
                                .clickable(onClick = tAct),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(tIcon),
                                contentDescription = null,
                                tint = c.sub,
                                modifier = Modifier.size(QzxTokens.Space.xl)
                            )
                        }
                    }
                }
            }
        }

        is QzxRow.Preview -> {
            // 系统负责：圆形裁剪、预览尺寸、按钮行高、危险色、引导小字。
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
                    Box(
                        modifier = Modifier
                            .size(PREVIEW_SIZE)
                            .clip(CircleShape)
                            .background(c.soft),
                        contentAlignment = Alignment.Center
                    ) {
                        if (row.image != null) {
                            Image(
                                painter = row.image,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Crop
                            )
                        } else if (row.fallbackIconRes != null) {
                            Icon(
                                painter = painterResource(row.fallbackIconRes),
                                contentDescription = null,
                                modifier = Modifier.size(PREVIEW_SIZE * 0.45f),
                                tint = c.sub
                            )
                        }
                    }
                    Spacer(Modifier.width(QzxTokens.Space.lg))
                    Column(modifier = Modifier.weight(1f)) {
                        if (!row.title.isNullOrEmpty()) {
                            QzxRowTitle(row.title)
                        }
                        if (!row.subtitle.isNullOrEmpty()) {
                            Text(
                                text = row.subtitle,
                                color = c.sub,
                                fontSize = QzxTokens.Type.FOOTER_SP.sp,
                                lineHeight = (QzxTokens.Type.FOOTER_SP +
                                    QzxTokens.Type.LINE_GAP_SP).sp
                            )
                        }
                    }
                }
                row.actions.forEach { action ->
                    Text(
                        text = action.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = QzxTokens.Size.minTouch.dp)
                            .clickable(onClick = action.onClick)
                            .padding(vertical = QzxTokens.Space.md),
                        color = if (action.destructive) c.danger else c.accent,
                        fontSize = QzxTokens.Type.ROW_TITLE_SP.sp,
                        lineHeight = (QzxTokens.Type.ROW_TITLE_SP +
                                QzxTokens.Type.LINE_GAP_SP).sp
                    )
                }
                if (!row.hint.isNullOrEmpty()) {
                    Text(
                        text = row.hint,
                        color = c.sub,
                        fontSize = QzxTokens.Type.FOOTER_SP.sp,
                        lineHeight = (QzxTokens.Type.FOOTER_SP + QzxTokens.Type.LINE_GAP_SP).sp
                    )
                }
            }
        }

        is QzxStepperRow -> QzxStepperRowContent(row)

        is QzxChipsRow -> QzxChipsRowContent(row)

        is QzxSliderRow -> QzxSliderRowContent(row)

        is QzxBadgeRow -> QzxBadgeRowContent(row)

        is QzxButtonsRow -> QzxButtonsRowContent(row)

        is QzxLabelRow -> QzxLabelRowContent(row)

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
                color = row.color ?: c.sub,
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
 * 【为什么图标收 `Int` 资源 id 而不是 `Painter`】
 * 1. **必须是 Int**：`item(...)` / `switch(...)` 是在 `QzxSectionScope.() -> Unit` 里调用的，
 *    那个 lambda **不是 `@Composable`** —— 在里面调 `painterResource(...)` 会编译报
 *    「Composable invocations can only happen from the context of a @Composable function」。
 *    收 `Int` 则调用方只写 `Icons.IC_MOON`（它本身就是 `R.drawable.*`），零组合期调用。
 * 2. **仍然不持有资源**：`painterResource` 在**渲染期**（`@Composable` 的渲染函数里）解析，
 *    资源 id 始终由调用方给 —— 删旧资源时编译不过的地方，仍然就是还没迁完的地方。
 *
 * （`Preview.image` 例外：那是 `BitmapPainter`，不是资源 id，保留 `Painter`。）
 */
@Composable
private fun QzxRowLeading(
    title: String,
    desc: String?,
    iconRes: Int?,
    iconPainter: Painter? = null,
    titleColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val resolved: Painter? = if (iconRes != null) painterResource(iconRes) else iconPainter
    if (resolved != null) {
        Icon(
            painter = resolved,
            contentDescription = null,
            modifier = Modifier.size(ROW_ICON_SIZE),
            tint = QzxColors.sub
        )
        Spacer(Modifier.width(QzxTokens.Space.md))
    }
    Column(modifier = modifier) {
        QzxRowTitle(title, colorOverride = titleColor)
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
private fun QzxRowTitle(
    text: String,
    modifier: Modifier = Modifier,
    colorOverride: Color? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = colorOverride ?: QzxColors.title,
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
