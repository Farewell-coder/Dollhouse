package com.dollhouse.app.ui.qzx

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ui.design.DhDesign

/**
 * 页面骨架 —— 新 UI 系统的「骨架腿」（内容腿是 002 的 `QzxList`）。
 *
 * ## 它解决的两个真问题
 *
 * 1. **同一个滚动状态要喂给两处**：标题栏要用它算收起进度，回弹要用它算越界。
 *    旧做法是每个页面自己 `rememberScrollState()` 再分别传，漏传一处就失效
 *    （我们已经在 3 个页面上踩过「state 声明在 content lambda 里、Page 拿不到」的坑）。
 *    这里由 [QzxPage] **创建一份、分发给两处**，从结构上不可能漏。
 * 2. **双层滚动**：`Page` 滚一层、内部列表再滚一层 ⇒ 「拉一下、弹两下」。
 *    这里用 [QzxPageScroll] 把「谁负责滚」显式化，且 **Lazy/Grid 模式下 QzxPage 不创建
 *    任何滚动容器**（容器归内容方 `QzxList` 所有），嵌套在结构上不可能发生。
 *
 * ## `content` 为什么是 `@Composable () -> Unit` 而不是 `ColumnScope.() -> Unit`
 *
 * 因为内容腿 `QzxList` 是一个**普通 `Column`**（不是 `LazyColumn`，`grep LazyColumn` 在
 * `QzxList.kt` 里是 0 处），它**不需要**也不该拿到外层 `ColumnScope`——
 * 有了 scope 反而容易写成 `Modifier.weight(1f)` 这种「只有 `ColumnScope` 才合法、
 * 但语义上不该由内容腿决定」的用法。保持无 scope，`QzxList` 就是一个普通 Composable，
 * 想放哪放哪。
 *
 * 【勘误·2026-11】这段原文写的是「`QzxList` 自带 `LazyColumn`」——**那是错的**，
 *   实现是 `Column`。这句话误导过一次迁移（有人据此认为 `QzxList` 不能放进
 *   `verticalScroll`，怕双层滚动，于是整页卡住一轮）。
 *   `Column` 放进 `verticalScroll` 是**正确用法**，不存在双层滚动。
 *
 * ## 和 `QzxList` 怎么配合（完整用例）
 *
 * ```kotlin
 * @Composable
 * fun SettingsScreen() {
 *     QzxPage(
 *         title = "设置",
 *         subtitle = "账号 · 外观 · 通知",
 *         onBack = { PageStack.pop(act) },
 *         scroll = QzxPageScroll.Lazy,        // 声明式列表 → 用 Lazy
 *     ) {
 *         QzxList(
 *             // 002 的 QzxList 从 CompositionLocal 取 state（见下方 LocalQzxScrollStates），
 *             // 也可以显式传参：state = QzxPageScrollStates.requireLazy(LocalQzxScrollStates.current)
 *             section("账号") {
 *                 item("昵称", trailing = "003")
 *                 switch("深色模式", checked = dark) { dark = it }
 *             }
 *             section("外观") {
 *                 select("主题色", options = listOf("蓝", "绿"), selected = 0)
 *             }
 *         )
 *     }
 * }
 * ```
 *
 * 关键点：**`QzxList` 内部不许再套 `verticalScroll`**。它的 `LazyColumn` 直接用
 * [LocalQzxScrollStates] 里那份 state —— 那份同时也是标题栏与回弹用的同一份。
 *
 * ## `scroll` 的四种取值
 *
 * | 值 | 谁创建滚动容器 | 标题栏 | 回弹 | 典型页面 |
 * |---|---|---|---|---|
 * | [QzxPageScroll.Auto] | **内容方**（默认按 Lazy 处理） | 自适应大标题 | 内容方自挂 | 绝大多数声明式页 |
 * | [QzxPageScroll.Lazy] | 内容方（`QzxList` 的 `LazyColumn`） | 自适应大标题 | 内容方自挂 | 列表页 |
 * | [QzxPageScroll.Grid] | 内容方（`LazyVerticalGrid`） | 自适应大标题 | 内容方自挂 | 网格页 |
 * | [QzxPageScroll.Scroll] | **QzxPage**（`verticalScroll`） | 自适应大标题 | **QzxPage 挂** | 普通 `Column` 表单 |
 * | [QzxPageScroll.None] | 无人（内容不滚） | **静态紧凑栏** | 无 | 画布 / 全屏控件 |
 *
 * ## ⚠️ `Auto` 的真相：做不到「按内容推断」
 *
 * Compose 里 `content` 是一个 `@Composable () -> Unit` **黑盒**：它还没被调用，
 * 无法知道里面会不会有 `LazyColumn`。用 `SubcomposeLayout` 也只能**事后**测量，
 * 而「用 `verticalScroll` 还是 `LazyColumn` 包裹」必须在**组合期**就决定 —— 时序上不可能。
 *
 * 所以 [QzxPageScroll.Auto] 的实现是：**等同于 `Lazy`，且不包裹任何容器**。
 * 为什么敢这么定：本系统的内容腿 `QzxList` 就是懒加载列表，这是**绝大多数页面的真实形态**；
 * 而不包裹容器意味着「即使推断错了也不会崩」——内容不滚就不滚，绝不会出现双层滚动。
 *
 * **若内容是不带 Lazy 的普通 `Column`**（想让它滚），请显式传 [QzxPageScroll.Scroll]。
 */
enum class QzxPageScroll {
    /** 默认：按 [Lazy] 处理（不包裹容器）。理由见 [QzxPage] 的「Auto 的真相」。 */
    Auto,

    /** 内容方用 `LazyColumn`（`QzxList`）。QzxPage 只创建 state，**不包裹**。 */
    Lazy,

    /** 内容方用 `LazyVerticalGrid`。QzxPage 只创建 state，**不包裹**。 */
    Grid,

    /** QzxPage 用 `verticalScroll` 包裹内容（适合普通 `Column` 表单），并自带回弹。 */
    Scroll,

    /** 不创建任何滚动状态；标题栏退化为静态紧凑栏（46dp）。 */
    None,
}

/**
 * 一页的滚动状态集合。
 *
 * 【为什么打包成一个对象而不是三个 CompositionLocal】三者互斥（一页只可能有一种滚动），
 *   打包后 `LocalQzxScrollStates` 只有一个，内容方读一次就知道该用哪个；
 *   也避免「三个 Local 各被提供一半」这种不可能正确配置的状态。
 *
 * 【为什么字段可空】与 [QzxPageScroll] 的取值一一对应：`Lazy` 模式下 `scrollState` 就是 null。
 *   用可空而不是 `Sealed class`，是为了让 `QzxList` 的取用代码保持一行。
 */
@Immutable
class QzxPageScrollStates(
    /** [QzxPageScroll.Scroll] 模式下非空。 */
    val scrollState: ScrollState? = null,
    /** [QzxPageScroll.Auto] / [QzxPageScroll.Lazy] 模式下非空。 */
    val lazyState: LazyListState? = null,
    /** [QzxPageScroll.Grid] 模式下非空。 */
    val gridState: LazyGridState? = null,
) {
    companion object {
        /** 空集合（[QzxPageScroll.None] 模式）。 */
        val None = QzxPageScrollStates()

        /** 取 `LazyListState`；当前页不是 Lazy 模式时给出可定位的错误。 */
        fun requireLazy(states: QzxPageScrollStates?): LazyListState =
            states?.lazyState ?: error(
                "QzxList 需要 LazyListState，但当前页不是 Lazy/Auto 模式。" +
                    "请给 QzxPage 传 scroll = QzxPageScroll.Lazy。"
            )

        /** 取 `LazyGridState`；当前页不是 Grid 模式时给出可定位的错误。 */
        fun requireGrid(states: QzxPageScrollStates?): LazyGridState =
            states?.gridState ?: error(
                "网格内容需要 LazyGridState，但当前页不是 Grid 模式。" +
                    "请给 QzxPage 传 scroll = QzxPageScroll.Grid。"
            )

        /** 取 `ScrollState`；当前页不是 Scroll 模式时给出可定位的错误。 */
        fun requireScroll(states: QzxPageScrollStates?): ScrollState =
            states?.scrollState ?: error(
                "内容需要 ScrollState，但当前页不是 Scroll 模式。" +
                    "请给 QzxPage 传 scroll = QzxPageScroll.Scroll。"
            )
    }
}

/**
 * 当前页的滚动状态。
 *
 * 【为什么用 `compositionLocalOf` 而不是 `staticCompositionLocalOf`】本值随页面
 *   `scroll` 参数变化（虽然实践中一页固定），但更要紧的是：内容方 `QzxList` 在
 *   **每次组合**读它，`compositionLocalOf` 的默认语义更安全（值变时只重组读取方）。
 *   用 static 版本会在值变时**重组整个内容子树**，代价更大。
 */
val LocalQzxScrollStates = compositionLocalOf<QzxPageScrollStates?> { null }

/**
 * 页面骨架。见文件头注释（含与 `QzxList` 的完整用例、`Auto` 的真相）。
 *
 * @param title 标题（大标题栏用；`None` 模式退化为 46dp 紧凑栏）
 * @param subtitle 副标题（仅展开态显示，随收起淡出）
 * @param onBack 返回回调；传 null 则不显示返回键（一级页）
 * @param actions 顶栏右侧操作区
 * @param scroll 谁负责滚动。默认 [QzxPageScroll.Auto]（= Lazy，不包裹容器）
 * @param contentPadding 内容区内边距。默认左右 `Space.cardPad`；
 *   全出血内容（如图片列表）可传 `PaddingValues(0.dp)`
 * @param content 页面内容，通常是 `QzxList`
 */
@Composable
fun QzxPage(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: QzxPageScroll = QzxPageScroll.Auto,
    contentPadding: PaddingValues = PaddingValues(horizontal = DhDesign.Space.cardPad.dp),
    /**
     * 返回键图标。**由调用方给**（新系统不持有旧 drawable）。
     * @param backIcon 为 null 时不画返回键 —— 一级页（无返回）本就该这么传。
     */
    backIconRes: Int? = null,
    /**
     * 是否有背景图。**由调用方给**（这是 App 的行为，不属于 UI 系统）。
     * 为 true 时标题栏栏底恒 88%（否则展开态近乎全透，大标题会直接压在照片上）。
     */
    hasPhotoBackdrop: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Auto 归一到 Lazy —— 理由见 enum 与文件头「Auto 的真相」。
    val mode = if (scroll == QzxPageScroll.Auto) QzxPageScroll.Lazy else scroll

    // 【state 在这里创建，且只有一份】标题栏、回弹、内容方拿到的都是同一个对象，
    //   所以三处的滚动进度天然一致（旧系统靠人手传，漏传就失效）。
    val scrollState = if (mode == QzxPageScroll.Scroll) rememberScrollState() else null
    val lazyState = if (mode == QzxPageScroll.Lazy) rememberLazyListState() else null
    val gridState = if (mode == QzxPageScroll.Grid) rememberLazyGridState() else null

    val states = QzxPageScrollStates(
        scrollState = scrollState,
        lazyState = lazyState,
        gridState = gridState,
    )

    Column(modifier = Modifier.fillMaxSize()) {

        // ── 标题栏：按模式选重载。三处状态都是上面那一份。 ──
        when {
            scrollState != null -> QzxTitleBar(
                title = title, scrollState = scrollState,
                onBack = onBack, subtitle = subtitle, actions = actions,
                backIconRes = backIconRes, hasPhotoBackdrop = hasPhotoBackdrop,
            )
            lazyState != null -> QzxTitleBar(
                title = title, scrollState = lazyState,
                onBack = onBack, subtitle = subtitle, actions = actions,
                backIconRes = backIconRes, hasPhotoBackdrop = hasPhotoBackdrop,
            )
            gridState != null -> QzxTitleBar(
                title = title, scrollState = gridState,
                onBack = onBack, subtitle = subtitle, actions = actions,
                backIconRes = backIconRes, hasPhotoBackdrop = hasPhotoBackdrop,
            )
            // None：静态紧凑栏（大标题永不收起，那它就不该占 120dp）
            else -> QzxTitleBar(
                title = title, onBack = onBack, subtitle = subtitle, actions = actions,
                backIconRes = backIconRes,
            )
        }

        // ── 内容区 ──
        CompositionLocalProvider(LocalQzxScrollStates provides states) {
            if (scrollState != null) {
                // 【只有 Scroll 模式由 QzxPage 包裹】因为普通 Column 自己不会滚。
                //   顺序：qzxBounce（nestedScroll）必须在 verticalScroll 外层，
                //   才收得到 onPostScroll 的 available。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .qzxBounce(scrollState)
                        .verticalScroll(scrollState)
                        .padding(contentPadding)
                ) {
                    content()
                }
            } else {
                // Lazy / Grid / None：**不包裹任何滚动容器**。
                //   容器归内容方（QzxList 的 LazyColumn）或根本不存在。
                //   这是"双层滚动"在结构上不可能发生的原因。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(contentPadding)
                ) {
                    content()
                }
            }
        }
    }
}
