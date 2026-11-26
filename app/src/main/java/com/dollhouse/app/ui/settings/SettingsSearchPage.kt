package com.dollhouse.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.dollhouse.app.ui.compose.clearFocusOnBlankTap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.PageRegistry
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】设置项搜索页 —— 索引表（[SettingsSpots]）的**唯一消费者**。
 *
 * ## 为什么是「设置页内的一个全屏状态」而不是一个独立的 `PageStack` 页
 *
 * 做成独立页需要往**路由白名单**加一个新 key，那是三处联动：
 * `NavKeyCodec`（`toKey` / `toLegacy`）+ `PageRegistry`（`isPageKey` / `build`）+ 一个新 Route 类。
 * 002 刚在那一带踩过坑（少登记一处 → 进程回收后该层被静默跳过），
 * 而搜索页**没有**「被系统重建后要还原」的需求（它只是设置页的一个临时视图）。
 * 所以选择「设置页内的全屏覆盖状态」：不动任何路由代码，风险为零。
 *
 * ## 为什么把状态放在单例而不是 `remember`
 *
 * Nav3 下**非栈顶 entry 的组合会被销毁**。用户「搜索 → 点结果进页面 → 返回」时，
 * 设置页会重新组合一次；若查询串存在 `remember` 里，回到搜索页就变空了（等于白搜一次）。
 * 提到 [SettingsSearchState] 后，返回时查询与结果原样还在。
 */
object SettingsSearchState {
    /** 搜索覆盖层是否展开。 */
    var open by mutableStateOf(false)

    /** 当前查询串。返回时保留。 */
    var query by mutableStateOf("")
}


/**
 * 「搜索命中后定位到某一行」的**请求通道**（移植自 MAA-Meow `SettingSearchNavigator`）。
 *
 * ## 为什么需要它
 * 搜索结果的落点是**另一个页面**（10 个设置子页之一）。要让目标页「滚到那行 + 闪两下」，
 * 目标页必须在组合时**知道**"我这次被谁点名了"。MAA 的做法是一个单例 navigator +
 * 一个带 TTL 的 request，目标行用 `SettingSearchTarget(anchor)` 订阅。
 *
 * ## 本轮到哪一步（重要）
 * 通道与消费端（[searchTarget]）都已就位，**但目标页还没接线** —— 因为那 10 个子页
 * 不在本轮的改动授权内，而 `DhKit.ActionRow` 又正被别人改。所以现在点结果的效果与改前
 * **一致**（只是多记了一条 request，5 秒后自己过期）。
 *
 * ## 下一轮怎么接（每个页面 3 行）
 * ```kotlin
 * DhKit.ValueRow(label = "莫奈取色", …)          // 原来就有
 * // 改成：把 rowId 对应的那一行包一层即可
 * Box(Modifier.searchTarget("appearance.monet")) { DhKit.ValueRow(...) }
 * ```
 * `rowId` 取自 [SettingsSpot.rowId]，全仓唯一且跨版本不变（见 `SettingsSpots` 类注释）。
 * 10 个页面各包几行，就完成了「跳页 → 滚到行 → 闪两下」的闭环。
 *
 * ## 为什么带 5 秒 TTL
 * MAA 的原注释：「锚点迟迟未出现就作废，免得过后突然闪」。若目标页因权限/空态根本没渲染
 * 那一行，request 不能一直挂着 —— 否则用户两分钟后点进那页会莫名其妙闪一下。
 */
object SettingsSearchAnchor {
    private const val TTL_MS = 5_000L

    /** 当前挂起的 rowId；改它会让订阅方重组。 */
    internal var pendingRowId by mutableStateOf<String?>(null)
        private set

    private var requestedAt = 0L

    /** 点搜索结果时登记（由 [openSpot] 调用）。 */
    fun request(rowId: String) {
        requestedAt = android.os.SystemClock.uptimeMillis()
        pendingRowId = rowId
    }

    /** 这一行是不是"刚被点名且还没过期"。 */
    internal fun isFresh(rowId: String): Boolean =
        pendingRowId == rowId &&
            android.os.SystemClock.uptimeMillis() - requestedAt <= TTL_MS

    /** 闪完就消费掉，避免再进这一页时重放。 */
    internal fun consume() {
        pendingRowId = null
    }
}

/**
 * 让这一行成为「搜索可定位目标」：被点名时滚进视野并**闪两下**。
 *
 * 参数与节奏照抄 MAA（`SettingSearchUi.kt:81-101` + `BringIntoViewOnExpand.kt`）：
 *   · 闪两下：`animateTo(1f, 180ms)` → `animateTo(0f, 420ms)`，共 2 轮；
 *   · 高亮 = 主色 alpha 0.18 × 闪值，画在 `drawBehind`（不占布局、不影响点击）；
 *   · 滚动要**等两帧**再 `bringIntoView()` —— 分组展开是动画，第一帧时布局还没稳定，
 *     直接滚会滚到旧位置。MAA 还会在 280ms 后再拉一次，这里因为不做「展开」省掉了。
 */
@Composable
fun Modifier.searchTarget(rowId: String): Modifier {
    val fresh = SettingsSearchAnchor.isFresh(rowId)
    val flash = remember { Animatable(0f) }
    val requester = remember { BringIntoViewRequester() }
    // 颜色在组合期取好再进 drawBehind —— drawBehind 的 lambda 不是 @Composable，读不到 DhTokens
    val tint = DhTokens.colors.acc
    if (fresh) {
        LaunchedEffect(rowId, SettingsSearchAnchor.pendingRowId) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { requester.bringIntoView() }
            repeat(2) {
                flash.animateTo(1f, tween(180))
                flash.animateTo(0f, tween(420))
            }
            SettingsSearchAnchor.consume()
        }
    }
    return this
        .bringIntoViewRequester(requester)
        .drawBehind {
            if (flash.value <= 0f) return@drawBehind
            drawRect(tint.copy(alpha = 0.18f * flash.value))
        }
}

/**
 * 打开某个搜索结果的落地页。
 *
 * 【A 类页（10 个设置子页）】`pageKey` 就是 `PageStack` 的 key，且已全部登记在
 *   `PageRegistry` / `NavKeyCodec` 里 → 直接 `PageStack.push(act, key) { PageRegistry.build(act, key) }`。
 *   **不需要**改任何白名单 —— 这些 key 本来就由各自页面的 `open()` 在用。
 *
 * 【B 类（`ProviderNav` 内部路由，例如 lamda 设备控制）】它外层只占一层（key 恒为
 *   `ProviderNav.TAG_PAGE`），真正的落点由**内部路由栈**决定（`R_LAMDA` 等）。
 *   直接 push `TAG_PAGE` 只会落到默认的「供应商列表」，所以必须走 `ProviderNav.openAt(act, route)`，
 *   由它先把内部栈设成目标路由、再压外层那一层。
 */
private fun openSpot(act: MainActivity, spot: SettingsSpot) {
    // 先登记「我要跳到哪一行」。目标页只要用 `Modifier.searchTarget(spot.rowId)` 包住
    //   那一行，就会自动滚进视野 + 闪两下；没接线的页面上这条请求 5 秒后自行过期。
    SettingsSearchAnchor.request(spot.rowId)
    val route = spot.route
    if (!route.isNullOrEmpty()) {
        ProviderNav.openAt(act, route)
        return
    }
    val key = spot.pageKey
    PageStack.push(act, key) { PageRegistry.build(act, key) }
}

/**
 * 搜索页（全屏覆盖在设置页之上）。
 *
 * @param onClose 关闭搜索、回到设置卡片流。
 */
@Composable
fun SettingsSearchPage(act: MainActivity, onClose: () -> Unit) {
    val c = DhTokens.colors
    val t = DhTokens.type
    // 【必须是「读属性」而不是 `by SettingsSearchState`】局部变量委托需要一个提供
    //   getValue/setValue 的**委托对象**；`SettingsSearchState` 是个单例容器、不是委托器，
    //   写 `by` 编译不过。这里直接读它的 Compose State 属性 —— 读即订阅，写法与
    //   `ChatState.items` 在界面层的用法一致。
    val query: String = SettingsSearchState.query
    val focus = remember { FocusRequester() }
    // 【聚焦态】input_audit 的 I6 判据：绕过规范入口自建 BasicTextField 时**必须**传
    //   interactionSource —— 不传时 BasicTextField 会自己 new 一个内部源，外面永远观察不到
    //   聚焦事件，于是「点了/进来了却看不出现在在打字」。
    //   这里顺手把它变成可见反馈：聚焦时底色从 soft 提亮到 card、描边由透明变强调色。
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 【自动聚焦】进页即弹键盘：搜索页的全部意义就是"马上打字"，
    //   让用户再点一次输入框是多余的一步。
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }
    val results = remember(query) { SettingsSpots.search(query) }

    // 【系统返回键】搜索是设置页的**覆盖状态**、不是栈里的一层，
    //   所以 `handleBack` 会直接弹掉整个设置页。这里登记返回拦截器：
    //   key 必须是「宿主页」的 key（`MainActivity` 只在 `isTop(key)` 成立时才询问），
    //   拦截后只收起搜索、不退页。与 DeveloperOptionsPage / AboutPage 的子屏返回同一套机制。
    PageStack.backInterceptor = PageStack.BackInterceptor(
        com.dollhouse.app.ui.home.SettingsScreen.TAG_PAGE
    ) {
        onClose()
        true
    }
    DisposableEffect(Unit) {
        onDispose {
            // 【必须自清】单槽拦截器留着不清，会在下一次压栈后巧合命中 isTop 吃掉别人的返回。
            if (PageStack.backInterceptor?.key == com.dollhouse.app.ui.home.SettingsScreen.TAG_PAGE) {
                PageStack.backInterceptor = null
            }
        }
    }

    // 【大标题栏·补】state 从内容 lambda 上移到 Page 之前（原来是 :331，作用域不够）。
    val listState = rememberLazyListState()
    DhKit.Page(
        title = "搜索设置",
        sub = "输入名称、旧叫法或英文词",
        onBack = onClose,
        scrollable = false,
        // 【大标题栏·补】搜索结果列表滚动时标题收起；
        //   未搜索时结果为空、index 恒为 0 → 标题保持展开（无害）。
        adaptiveTitle = true,
        externalLazyScroll = listState
    ) {
        // 【点空白收键盘】挂在内容根上：输入框 / 结果行会先消费自己的点击，
        //   只有落在空白处的点击才走到这里。与回车收键盘（keyboardActions.onSearch）
        //   是同一语义的另一条触发路径。
        // 【为什么是 Column 不是 Box】下方 LazyColumn 用 Modifier.weight(1f) 占满剩余高度，
        //   而 weight 只在 ColumnScope / RowScope 里存在 —— Box 是层叠布局，没有 weight。
        Column(modifier = Modifier
            .fillMaxSize()
            .clearFocusOnBlankTap()
        ) {
        // ---------- 输入框 ----------
        Row(            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DhDesign.Space.xs.dp)
                .clip(RoundedCornerShape(DhDesign.Radius.pill.dp))
                .background(if (focused) c.card else c.soft)
                .border(
                    width = DhDesign.Size.hairline,
                    color = if (focused) c.acc else Color.Transparent,
                    shape = RoundedCornerShape(DhDesign.Radius.pill.dp)
                )
                .padding(horizontal = DhDesign.Space.md.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(
                iconRes = Icons.IC_ZOOM_IN,
                sizeDp = DhDesign.Size.rowIcon.value,
                color = c.sub
            )
            Spacer(Modifier.width(DhDesign.Space.iconGap.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    // 占位符用单独的 Text：BasicTextField 的 placeholder 参数在
                    //   1.10 里仍需自己画，直接用一层叠字更直观。
                    Text(
                        text = "搜索设置项",
                        color = c.sub,
                        fontSize = t.body.fontSize,
                        lineHeight = t.body.lineHeight,
                        fontFamily = DhTokens.fontsBody
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { SettingsSearchState.query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    singleLine = true,
                    interactionSource = interaction,
                    textStyle = TextStyle(
                        color = c.title,
                        fontSize = t.body.fontSize,
                        lineHeight = t.body.lineHeight,
                        fontFamily = DhTokens.fontsBody
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.acc),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // 【回车 = 收起键盘】结果本来就是实时刷新的，回车不需要"提交"，
                    //   只把键盘让开，让用户直接看到结果列表。
                    keyboardActions = KeyboardActions(onSearch = {
                        runCatching { act.currentFocus?.clearFocus() }
                    })
                )
            }
            if (query.isNotEmpty()) {
                DhKit.IconButton(
                    iconRes = Icons.IC_CLOSE,
                    sizeDp = DhDesign.Size.rowIcon.value,
                    color = c.sub,
                    a11yName = "清空搜索"
                ) { SettingsSearchState.query = "" }
            }
        }

        Spacer(Modifier.height(DhDesign.Space.md.dp))

        // 【滚动】查询一改就把结果列表拉回顶部。
        //   没有这一步时，用户输入第二个词后列表停在上一轮的滚动位置，
        //   最相关的那条可能正好在屏幕外 —— 等于"搜到了但看不见"。
        LaunchedEffect(query) {
            runCatching { listState.scrollToItem(0) }
        }

        // ---------- 三态：未输入 / 无结果 / 有结果 ----------
        when {
            query.isBlank() -> Hint(
                "输入关键词开始搜索",
                "可以搜中文（「莫奈」）、旧名字（「自动总结」）或英文（「monet」「overlay」）。"
            )

            results.isEmpty() -> Hint(
                "没有找到「$query」",
                "换个说法试试：比如「背景」而不是「壁纸」，「保活」而不是「常驻」。"
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = DhDesign.Space.xl.dp),
                verticalArrangement = Arrangement.spacedBy(DhDesign.Space.xs.dp)
            ) {
                items(results, key = { it.rowId }) { spot ->
                    ResultRow(act, spot)
                }
            }
        }
        }
    }
}

/** 一条搜索结果：标题 + 面包屑 + 右侧「当前值 / 不可用原因」。 */
@Composable
private fun ResultRow(act: MainActivity, spot: SettingsSpot) {
    val c = DhTokens.colors
    val t = DhTokens.type
    val usable = spot.available()
    val value = if (usable) spot.value() else null
    val trailing = when {
        !usable -> spot.unavailableHint.ifEmpty { "当前不可用" }
        !value.isNullOrEmpty() -> value
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
            // 【不可用项不可点】它点了也没有意义（进页也只能看到同一个"不可用"提示），
            //   但**仍然列出**：用户能确认"这个功能存在，只是条件没满足"，
            //   而不是搜不到 → 以为没有这个功能（dsh-Go 的"不让人扑空"原则）。
            .then(if (usable) Modifier.pressable { openSpot(act, spot) } else Modifier)
            .padding(horizontal = DhDesign.Space.sm.dp, vertical = DhDesign.Space.sm.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = spot.title,
                modifier = Modifier.weight(1f),
                color = if (usable) c.title else c.sub,
                fontSize = t.body.fontSize,
                lineHeight = t.body.lineHeight,
                fontFamily = DhTokens.fontsBold,
                maxLines = 1
            )
            if (trailing != null) {
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                Text(
                    text = trailing,
                    color = if (usable) c.sub else c.err,
                    fontSize = t.caption.fontSize,
                    lineHeight = t.caption.lineHeight,
                    fontFamily = DhTokens.fonts,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = spot.path,
            color = c.sub,
            fontSize = t.caption.fontSize,
            lineHeight = t.caption.lineHeight,
            fontFamily = DhTokens.fonts,
            maxLines = 1
        )
    }
}

/** 空态 / 无结果态：一行标题 + 一行说明。 */
@Composable
private fun Hint(title: String, detail: String) {
    val c = DhTokens.colors
    val t = DhTokens.type
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DhDesign.Space.xxl.dp, start = DhDesign.Space.sm.dp, end = DhDesign.Space.sm.dp)
    ) {
        Text(
            text = title,
            color = c.sub,
            fontSize = t.body.fontSize,
            lineHeight = t.body.lineHeight,
            fontFamily = DhTokens.fontsBold
        )
        Spacer(Modifier.height(DhDesign.Space.xs.dp))
        Text(
            text = detail,
            color = c.sub,
            fontSize = t.sub.fontSize,
            lineHeight = t.sub.lineHeight,
            fontFamily = DhTokens.fontsBody
        )
    }
}
