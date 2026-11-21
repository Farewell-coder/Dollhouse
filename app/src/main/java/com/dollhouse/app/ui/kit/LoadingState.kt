package com.dollhouse.app.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.design.DhDesign

/**
 * 【职责】全 App 唯一的「加载中」语义分类。
 *
 * ## 为什么必须先有这个枚举
 *
 * 改造前 28 个页面里只有 6 个有加载态，且**每一页的判据都不同**：
 *   · `ChatListScreen.kt:139` 用 `var loaded by remember { mutableStateOf(false) }`
 *   · `ModelListPage.kt:89` 用 `var loaded by remember { mutableStateOf<List<String>?>(null) }`
 *   · `SocialPage.kt:162` 用 `var snap by remember { mutableStateOf<Snapshot?>(null) }`
 *   · `DeveloperOptionsPage.kt` 没有独立标志，直接把可空的 `summary` 传给子组件
 *
 * 三种写法（Boolean / 可空容器 / 无标志）意味着「加一个加载态」每次都要重新想一遍
 * 判据该怎么写，于是每页长出来的形态都不一样。这个枚举把**语义**先钉死，
 * 判据退化成「把某个可空状态映射成三个值之一」，28 个页面才算在做同一件事。
 *
 * ## 三档的分界依据（来自 MAA 两条硬规则，`004-maa-meow.md` §4.6）
 *
 * MAA 的 `LogHistoryView` 分支写法是 `isLoading && list.isEmpty()` 才全屏圈 ——
 * 即「**确实没内容**」才允许占满整屏。这条不是审美偏好，是行为约束：
 * 用户已经有内容在看的时候，全屏 loading 会把它遮掉，等于拿手上一屏数据换一个转圈。
 *
 * 所以：
 *
 * | 档位 | 触发条件 | 视觉形态 | 会不会遮掉已有内容 |
 * |---|---|---|---|
 * | [FirstLoad] | 还没拿到**任何**数据（首次进入 / 冷启动 / 数据源切换） | 骨架行占满内容区 | 无内容可遮 |
 * | [Refreshing] | 已有旧数据，正在拿新的 | 一行细指示器，内容照常可见 | **不遮** |
 * | [Idle] | 没有进行中的请求 | 不渲染任何东西 | —— |
 *
 * 【为什么不用 Boolean】`isLoading = true` 无法区分「首次」与「刷新」，
 *   于是要么首屏白屏、要么刷新时把列表清空 —— 两个都是现实里出现过的缺陷。
 *   三档强制调用方回答「我现在有没有内容可以给用户看」，这正是判据该问的问题。
 */
enum class LoadingState {
    /** 还没有任何数据可展示：允许占满内容区。 */
    FirstLoad,

    /** 已有旧数据，正在后台拿新的：**不许**遮挡已有内容。 */
    Refreshing,

    /** 没有进行中的加载。 */
    Idle
}

/**
 * 首屏骨架：N 条占位行。
 *
 * 【为什么复用 [DhSkeletonRow] 而不是另画一套】全 App 已有唯一骨架实现
 *   （`ChatListScreen.kt:324` 在用），并且它内部处理了「有背景图时骨架条换色」
 *   这一档 —— 铺在 SOFT@88% 的可读面板上时 SOFT 条等于隐形（见 `Components.kt:865` 的注释）。
 *   另画一套必然把这层处理漏掉，于是有背景图的用户看到的是"一块空面板"而不是"正在加载"。
 *
 * 【为什么不加行间距】`DhSkeletonRow` 自己已带 `padding(vertical = RowPadV = 10.dp)`
 *   （`Components.kt:866`）。再叠 `Arrangement.spacedBy` 会得到双倍行距，
 *   3 条骨架比 3 行真实内容高出一截，数据到达时产生明显跳动 —— 那正是骨架要避免的事。
 *
 * @param count 占位行数（与真实列表的首屏可见行数同量级）
 * @param lines 每行几条占位条（1 = 单行文本行，2 = 标题 + 副标题）
 */
@Composable
fun DhLoadingRows(
    modifier: Modifier = Modifier,
    count: Int = 3,
    lines: Int = 2
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Top
    ) {
        repeat(count) {
            DhSkeletonRow(lines = lines)
        }
    }
}

/**
 * 局部加载指示器（用于 [LoadingState.Refreshing]，以及需要"正在读"提示的行内位置）。
 *
 * 【为什么是细圈而不是整块遮罩】这一档的前提是「用户手上已经有内容」。
 *   遮罩会把那份内容一起压暗，等于用可用信息换一个动画 —— 违反 MAA 硬规则 1。
 *   16dp 圈 + 可选文字只占一行，不改变内容区的布局尺寸。
 *
 * 【为什么圈用 acc 色而不是 sub】它是**状态**而不是次要文本：用户需要一眼看到
 *   「App 在动」。sub 色在页面底上只有 4.66:1，作为 16dp 的细线太轻。
 *
 * @param label 可选说明（例如「正在刷新」）。为 null 时只有圈。
 */
@Composable
fun DhLoadingSpinner(
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val c = DhTokens.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(LOADING_SPINNER_DP.dp),
            color = c.acc,
            strokeWidth = LOADING_STROKE_DP.dp
        )
        if (!label.isNullOrEmpty()) {
            Spacer(Modifier.width(DhDesign.Space.sm.dp))
            Text(
                text = label,
                color = c.sub,
                style = DhTokens.type.sub
            )
        }
    }
}

/**
 * 按 [LoadingState] 渲染加载形态的唯一入口。
 *
 * ## 为什么要收成一个函数而不是让每页自己 `when`
 *
 * `when (state) { FirstLoad -> 骨架; Refreshing -> 细条; Idle -> Unit }` 这三行
 * 写 28 遍，就必然出现「某页 FirstLoad 用了 spinner 而不是骨架」「某页 Refreshing
 * 忘了不遮挡」这类漂移。收在这里之后，页面只负责**回答状态**，不负责**决定形态**。
 *
 * ## 用法
 *
 * ```kotlin
 * // 1) 把「可空数据」映射成三档：有数据 = Idle 或 Refreshing，没数据 = FirstLoad
 * val state = when {
 *     data == null -> LoadingState.FirstLoad
 *     busy -> LoadingState.Refreshing
 *     else -> LoadingState.Idle
 * }
 *
 * // 2) 首屏档：渲染骨架后直接 return，不要再往下画内容
 * if (data == null) {
 *     DhLoadingState(state = state, modifier = Modifier.fillMaxSize())
 *     return@Column
 * }
 *
 * // 3) 刷新档：只画指示器，内容照常往下走
 * DhLoadingState(state = if (busy) LoadingState.Refreshing else LoadingState.Idle)
 * ```
 *
 * @param state 当前加载语义。
 * @param modifier 作用于**整个加载区**。首屏档通常传 `Modifier.fillMaxSize()`
 *   以便骨架占满内容区；刷新档一般不传（指示器自带一行高度）。
 * @param rows 首屏档的骨架行数。
 * @param lines 首屏档每行的占位条数。
 * @param label 刷新档的可选说明文字。
 */
@Composable
fun DhLoadingState(
    state: LoadingState,
    modifier: Modifier = Modifier,
    rows: Int = 3,
    lines: Int = 2,
    label: String? = null
) {
    when (state) {
        // 【首屏】内容区没有任何数据可看：骨架占住它，避免「闪一下空态」再变出内容。
        LoadingState.FirstLoad -> Box(
            modifier = modifier.padding(
                horizontal = DhDesign.Space.pageX.dp,
                vertical = DhDesign.Space.md.dp
            )
        ) {
            DhLoadingRows(count = rows, lines = lines)
        }

        // 【刷新】旧内容必须保持可见：只在自身一行里画圈，不铺底、不加高、不遮挡。
        LoadingState.Refreshing -> Box(
            modifier = modifier.padding(
                horizontal = DhDesign.Space.pageX.dp,
                vertical = DhDesign.Space.xs.dp
            )
        ) {
            DhLoadingSpinner(label = label)
        }

        // 【空闲】什么都不画 —— 连 0dp 的 Box 都不留，避免影响父容器的间距。
        LoadingState.Idle -> Unit
    }
}

/**
 * 局部指示器直径：16dp。
 *
 * 与 `kit.DhButton` 的忙态圈同尺寸（`Components.kt:203` 的 `size(16.dp)`）。
 * 【为什么不跟 `DhForm.SubmitButton` 统一】那个是 14dp（`DhForm.kt:763`）——
 *   它在 44dp 定高按钮里、旁边还有 15sp 文字，圈不能比字高；
 *   本指示器是独立一行，16dp 更易辨认。两者尺寸不同是**有理由的**，不是漂移。
 */
private const val LOADING_SPINNER_DP = 16

/** 圈线宽：2dp。与 `Components.kt:128` 的 `LoadingStroke` 同值。 */
private const val LOADING_STROKE_DP = 2
