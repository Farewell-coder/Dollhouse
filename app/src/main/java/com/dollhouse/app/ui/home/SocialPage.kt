package com.dollhouse.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.Mem
import com.dollhouse.app.data.Memory
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.chat.ChatListScreen
import com.dollhouse.app.ui.chat.ChatSessions
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.kit.DhState
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.MemPage
import com.dollhouse.app.ui.settings.MemSettingsPage
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 【职责】底栏第三格「说说」—— 人偶近况流。
 *
 * ## 这一页到底是什么
 * 一条**长期记忆**就是一屏上的一条「说说」。记忆是 AI 在聊天后自动整理的
 * 「她记住了什么」（如「[项目] 她说要陪你熬过这周」），每一条都带真实时间戳，
 * 所以这里天然是一条**跨人偶的时间线**：谁、什么时候、记住了什么。
 *
 * 它不是新造的数据，也不是让用户手写的动态 —— 只读地聚合 [Mem] 的既有条目
 * （[DollStore] 提供名字与头像）。**本页不写任何数据**，一个字都不落盘。
 *
 * ## 为什么不做「最近说过的话」和「金句收藏」
 * · 聊天消息没有**逐条时间戳**（只有会话级的 updated），拿它做时间线等于画一条
 *   假的时间轴 —— 假时间轴比不做更糟；
 * · 收藏功能根本不存在（历史里的 rating 字段只写 0，从未被任何入口改过），
 *   要做就得先去聊天页加写入路径，那是另一个任务。
 *
 * ## 为什么空态是本页的主要工作量
 * 记忆由「聊天 + 已填模型 Key + 到点抽取」三个条件共同决定，**新装用户第一次点进来
 * 必然是空的**。一句「暂无内容」会让人以为功能坏了。所以空态按**真实状态**分四级，
 * 每一级都回答两个问题：为什么空、怎么让它不空，且按钮真的能到那个页面。
 *
 * 判据一律读真实状态（[PetPrefs.hasKey] / [PetPrefs.mem2Auto] / [DollStore.all]），
 * **绝不**用「没有记忆」反推「没填 Key」—— 那是两件不同的事。
 *
 * ## 只读 + 上限
 * · 每人偶只解析最近 [TAIL_PER_DOLL] 条（磁盘序是旧到新，取尾部即可，不用全量解析）；
 * · 跨人偶合并后再截 [MAX_ITEMS] 条，其余更旧的本轮不读；
 * · 首屏只渲染 [PAGE] 条，点「加载更多」再放一页 —— 10 只人偶各 200 条也不会一次进内存。
 */
object SocialPage {

    /** 每人偶只解析最近多少条记忆（内存与解析量的第一道闸）。 */
    private const val TAIL_PER_DOLL = 12

    /** 跨人偶聚合后的总条数上限（第二道闸）。 */
    private const val MAX_ITEMS = 240

    /** 首屏条数；点「加载更多」再放一页。 */
    private const val PAGE = 30

    /** 筛选条里「全部」的哨兵 id（不能用空串：人偶 id 非空，但语义上要一个显式值）。 */
    private const val FILTER_ALL = "\u0000all"

    /**
     * 「说说」页正文（供 [HomeScreen] 调用）。
     *
     * 【为什么是 Composable 而不是 build(View)】第三格由 [HomeScreen] 的单一 Compose 根承载，
     *   这里只交出内容，视图生命周期仍归 [HomeScreen] —— 底栏的路由契约一个字都不用改。
     */
    @Composable
    fun Feed(act: MainActivity) {
        val p = DhTokens.palette
        val nav = AppNav.current
        // 人偶改名 / 新建 / 删除后要重算（与联系人页同一套信号）。
        val dollRev = DollState.rev

        var snap by remember { mutableStateOf<Snapshot?>(null) }
        var filter by remember { mutableStateOf(FILTER_ALL) }
        var shown by remember { mutableStateOf(PAGE) }

        // 【只在「这一格可见」时读盘】三个 Tab 常驻组合，若在组合阶段无条件读盘，
        //   用户留在聊天页时也会白读一遍所有人的记忆库。这里用导航状态做闸门。
        LaunchedEffect(nav, dollRev) {
            if (nav != AppNav.TAB_HOME) {
                return@LaunchedEffect
            }
            val loaded = withContext(Dispatchers.IO) { readAll(act) }
            snap = loaded
            shown = PAGE
            // 选中的那只已经没有条目时（被她删光了 / 记忆被清空），退回「全部」，
            // 否则用户会对着一个永远空的筛选条发愣。
            if (filter != FILTER_ALL && loaded.items.none { it.dollId == filter }) {
                filter = FILTER_ALL
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 【统一】与聊天页 / 人偶页同一段顶栏代码，切 Tab 时标题不会跳字号。
            DhKit.TabTopBar(
                title = "说说",
                actions = {
                    // 「记忆怎么来 / 多久记一次」的开关都在记忆库设置里，
                    // 放在顶栏是为了让「空态里的解释」随手可查证，而不是一处断言。
                    DhKit.IconButton(
                        iconRes = Icons.IC_BRAIN,
                        sizeDp = Design.SZ_MD,
                        color = p.title,
                        tooltip = "记忆库设置"
                    ) { MemSettingsPage.open(act) }
                }
            )

            val s = snap
            if (s == null) {
                // 读盘通常是毫秒级；宁可这一帧空着，也不要先闪一屏「暂无内容」再变出内容。
                return@Column
            }

            if (s.items.isEmpty()) {
                EmptyState(act = act, snap = s)
                return@Column
            }

            val chips = remember(s, dollRev) { chipList(s) }
            if (chips.size > 1) {
                FilterRow(chips = chips, selected = filter) { filter = it }
            }

            val visible = remember(s, filter) {
                if (filter == FILTER_ALL) s.items else s.items.filter { it.dollId == filter }
            }
            val rows = remember(visible, shown) {
                val base = buildRows(visible.take(shown))
                if (visible.size > shown) base + Entry.More() else base
            }
            val avatarOf = remember(s.items) { avatars(act, s.items) }
            val moreCount = visible.size - shown

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = DhDesign.Space.pageX.dp,
                    end = DhDesign.Space.pageX.dp,
                    top = DhDesign.Space.xs.dp,
                    // 底栏是悬浮的（不在布局流里），列表末端要自己让出高度。
                    bottom = BottomNav.HEIGHT_DP.dp + DhDesign.Space.lg.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(
                    items = rows,
                    // 【性能硬要求】key 必须跨人偶唯一：记忆 id 是「m+时间戳-随机」，
                    //   两只人偶理论上可能撞同一串，所以拼上 dollId 做前缀。
                    key = { row -> row.key },
                    // 【性能硬要求】三类条目布局不同（日期头 / 记忆卡 / 加载更多），
                    //   contentType 分开，Compose 才会各自复用各自的槽位。
                    contentType = { row -> row.contentType }
                ) { row ->
                    when (row) {
                        is Entry.Header -> DayHeader(row.label)
                        is Entry.Post -> PostCard(
                            item = row.item,
                            avatar = avatarOf[row.item.dollId],
                            onClick = { openDoll(act, row.item.dollId) }
                        )
                        is Entry.More -> MoreRow(moreCount) { shown += PAGE }
                    }
                }
            }
        }
    }

    /* ============================ 空态（本页的主路径） ============================ */

    /** 空态的四级。判据见 [emptyKind]。 */
    private enum class EmptyKind {
        /** 一只**自己的**人偶都没有（只剩内置默认模板）。 */
        NO_DOLL,

        /** 有自己的人偶，但没填模型 Key —— 抽取根本发不出去。 */
        NO_KEY,

        /** 有 Key，但「自动保存记忆」被关掉了。 */
        NO_AUTO,

        /** 条件齐了，只是还没聊够（或刚清空）。 */
        NO_MEM
    }

    /**
     * 由**真实状态**决定空在哪一级。
     *
     * 【顺序为什么是这样】从「最根本的缺失」往外走：没人偶 → 没 Key → 关了自动保存 →
     *   纯时间问题。这样用户看到的永远是「距能记事最近的那一步」，而不是笼统的「暂无内容」。
     */
    private fun emptyKind(s: Snapshot): EmptyKind {
        return when {
            !s.hasOwnDoll -> EmptyKind.NO_DOLL
            !s.hasKey -> EmptyKind.NO_KEY
            !s.autoOn -> EmptyKind.NO_AUTO
            else -> EmptyKind.NO_MEM
        }
    }

    @Composable
    private fun EmptyState(act: MainActivity, snap: Snapshot) {
        val kind = emptyKind(snap)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (kind) {
                    EmptyKind.NO_DOLL -> DhState(
                        title = "还没有属于你的人偶",
                        body = "说说记的是她的事 —— 先捏一只，她才会开始记事",
                        iconRes = Icons.IC_USERS,
                        actionText = "去添加人偶",
                        onAction = { DollListScreen.open(act) }
                    )

                    EmptyKind.NO_KEY -> DhState(
                        title = "她还不会记事",
                        body = "自动记事要调用模型：先填一个能用的模型 Key，聊完一轮她就会把要点记下来",
                        iconRes = Icons.IC_KEY_ROUND,
                        actionText = "去填模型 Key",
                        onAction = { ProviderNav.open(act) }
                    )

                    EmptyKind.NO_AUTO -> DhState(
                        title = "自动记事已关闭",
                        body = "打开它，她才会在聊天后自己整理要点（默认每 30 分钟整理一次）",
                        iconRes = Icons.IC_BRAIN,
                        actionText = "去打开",
                        onAction = { MemSettingsPage.open(act) }
                    )

                    else -> DhState(
                        title = "她还没记住什么",
                        body = "陪她说几句话。聊完一轮她会把要点记下来，出现在这里",
                        iconRes = Icons.IC_NEWSPAPER,
                        actionText = "去和她聊聊",
                        onAction = { openDoll(act, snap.firstDollId) }
                    )
                }

                // 【第二个动作】主按钮只给一条「最能让她不空」的路；第二个动作是「查证」。
                //   空态里给两个并列按钮反而让人犹豫，所以第二个做成极轻的轮廓芯片。
                val secondAction: Pair<String, () -> Unit>? = when (kind) {
                    EmptyKind.NO_KEY, EmptyKind.NO_AUTO -> Pair("记忆库设置") { MemSettingsPage.open(act) }
                    EmptyKind.NO_MEM -> Pair("看看她的记忆库") { MemPage.open(act, snap.firstDollId) }
                    EmptyKind.NO_DOLL -> null
                }
                if (secondAction != null) {
                    Spacer(Modifier.height(DhDesign.Space.sm.dp))
                    val label = secondAction.first
                    val action = secondAction.second
                    DhKit.OutlineChip(text = label, onClick = action)
                }

                if (kind == EmptyKind.NO_MEM || kind == EmptyKind.NO_KEY) {
                    Spacer(Modifier.height(DhDesign.Space.xl.dp))
                    HowItWorks()
                }

                // 底栏避让：这一块是 Column 的直接子节点，不在 LazyColumn 的 contentPadding 里。
                Spacer(Modifier.height(BottomNav.HEIGHT_DP.dp))
            }
        }
    }

    /**
     * 「记事怎么工作」的三行说明。
     *
     * 【为什么空态里要放它】用户停在空页面时最想知道的是「我该做什么、它什么时候生效」。
     *   把机制摊开讲比一句「敬请期待」有用得多；三条都可在界面上查证（设置里就有对应开关）。
     * 【为什么不定卡片】空态里再叠一张卡片会变成「两个色块互相抢焦点」；
     *   这里只用文字与间距，保持页面清透。
     */
    @Composable
    private fun HowItWorks() {
        val p = DhTokens.palette
        val t = DhTokens.type
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DhDesign.Space.xl.dp),
            horizontalAlignment = Alignment.Start
        ) {
            val lines = listOf(
                "聊完一轮，她会自己记下要点",
                "默认每 30 分钟整理一次，可在记忆库设置里调整或关掉",
                "记下的事只属于那只人偶，不会串到别人偶身上"
            )
            for (line in lines) {
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(p.line)
                    )
                    Spacer(Modifier.width(DhDesign.Space.sm.dp))
                    Text(
                        text = line,
                        color = p.sub,
                        fontSize = t.caption.fontSize,
                        lineHeight = t.caption.lineHeight,
                        fontFamily = DhTokens.fontsBody
                    )
                }
            }
        }
    }

    /* ============================ 列表部件 ============================ */

    /** 筛选条的选项：全部 + 有内容的人偶（按最新条目出现的顺序，与时间线一致）。 */
    private fun chipList(s: Snapshot): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add(FILTER_ALL to "全部")
        val seen = HashSet<String>()
        for (it in s.items) {
            if (seen.add(it.dollId)) {
                out.add(it.dollId to it.dollName)
            }
        }
        return out
    }

    @Composable
    private fun FilterRow(chips: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = DhDesign.Space.sm.dp),
            contentPadding = PaddingValues(horizontal = DhDesign.Space.pageX.dp),
            horizontalArrangement = Arrangement.spacedBy(DhDesign.Space.sm.dp)
        ) {
            items(
                items = chips,
                key = { it.first },
                contentType = { "chip" }
            ) { c ->
                // 【选中态走参数，不靠文案】旧写法「文字后加一个 ·」在本工程已被明令淘汰
                //   （看起来像没渲染出来的未读角标）。这里用 OutlineChip 的 selected：
                //   选中 = 主色实底 + 反白字。
                DhKit.OutlineChip(
                    text = c.second,
                    selected = c.first == selected,
                    onClick = { onSelect(c.first) }
                )
            }
        }
    }

    /** 日期分隔头：今天 / 昨天 / MM-dd / yyyy-MM-dd。 */
    @Composable
    private fun DayHeader(label: String) {
        val p = DhTokens.palette
        val t = DhTokens.type
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DhDesign.Space.lg.dp, bottom = DhDesign.Space.xs.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = p.sub,
                fontSize = t.label.fontSize,
                lineHeight = t.label.lineHeight,
                fontFamily = DhTokens.fonts,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.44.sp
            )
        }
    }

    /**
     * 一条说说（一条记忆）。
     *
     * 【表面为什么用「柔和高光卡」】条目是**独立内容实体**（一条记忆 = 一件事），
     *   与聊天列表那种「一条连续消息流」不同，用卡片分隔才是对的语义。
     *   材质沿用设计系统口径：顶部提亮一档的竖向渐变、无阴影、无硬描边 ——
     *   这是「清透柔和轻拟物」里卡片的标准画法（OutlineChip 也是同一套渐变思路）。
     */
    @Composable
    private fun PostCard(item: ShuoItem, avatar: android.graphics.Bitmap?, onClick: () -> Unit) {
        val p = DhTokens.palette
        val t = DhTokens.type
        val shape = RoundedCornerShape(DhDesign.Radius.lg.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = DhDesign.Space.cardGap.dp)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(p.cardUp, p.card)))
                .pressable(onClick)
                .padding(
                    horizontal = DhDesign.Space.cardPad.dp,
                    vertical = DhDesign.Space.md.dp
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 头像：32dp 小圆，与名字同一行 —— 时间线的「谁」要一眼可辨。
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(p.soft),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatar != null && !avatar.isRecycled) {
                        Image(
                            bitmap = avatar.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        DhKit.Icon(
                            iconRes = if (item.builtin) Icons.IC_WHALE else Icons.IC_USER_ROUND,
                            sizeDp = Design.SZ_SM,
                            color = p.sub
                        )
                    }
                }
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                Text(
                    text = item.dollName,
                    modifier = Modifier.weight(1f),
                    color = p.title,
                    fontSize = t.sub.fontSize,
                    lineHeight = t.sub.lineHeight,
                    fontFamily = DhTokens.fonts,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                Text(
                    text = clockText(item.ts),
                    color = p.line,
                    fontSize = t.caption.fontSize,
                    lineHeight = t.caption.lineHeight,
                    fontFamily = DhTokens.fontsBody
                )
            }

            if (item.title.isNotEmpty()) {
                Spacer(Modifier.height(DhDesign.Space.sm.dp))
                Text(
                    text = item.title,
                    color = p.title,
                    fontSize = t.body.fontSize,
                    lineHeight = t.body.lineHeight,
                    fontFamily = DhTokens.fonts,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.body.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.body,
                    color = p.sub,
                    fontSize = t.sub.fontSize,
                    lineHeight = t.sub.lineHeight,
                    fontFamily = DhTokens.fontsBody,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.tags.isNotEmpty()) {
                Spacer(Modifier.height(DhDesign.Space.sm.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(DhDesign.Space.xs.dp)) {
                    for (tag in item.tags) {
                        Text(
                            text = tag,
                            color = p.sub,
                            fontSize = t.caption.fontSize,
                            lineHeight = t.caption.lineHeight,
                            fontFamily = DhTokens.fontsBody,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(p.soft)
                                .padding(horizontal = DhDesign.Space.sm.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    /** 列表末端的「加载更多」。样式用轮廓芯片，不抢条目的视线。 */
    @Composable
    private fun MoreRow(remain: Int, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DhDesign.Space.xs.dp, bottom = DhDesign.Space.lg.dp),
            contentAlignment = Alignment.Center
        ) {
            DhKit.OutlineChip(text = "加载更多（还有 $remain 条）", onClick = onClick)
        }
    }

    /* ============================ 数据（只读） ============================ */

    /** 数据快照：条目 + 决定空态的那几个真实状态。 */
    private class Snapshot(
        val items: List<ShuoItem>,
        val hasOwnDoll: Boolean,
        val firstDollId: String,
        val hasKey: Boolean,
        val autoOn: Boolean
    )

    /** 一条说说 = 一条记忆 + 它属于谁。 */
    private class ShuoItem(
        val key: String,
        val dollId: String,
        val dollName: String,
        val builtin: Boolean,
        val title: String,
        val body: String,
        val tags: List<String>,
        val ts: Long
    )

    /** 行模型：日期头 / 条目 / 加载更多。三类 contentType 分开复用槽位。 */
    private sealed class Entry {
        abstract val key: String
        abstract val contentType: String

        class Header(val label: String) : Entry() {
            override val key: String get() = "h_" + label
            override val contentType: String get() = "date"
        }

        class Post(val item: ShuoItem) : Entry() {
            override val key: String get() = "post_" + item.key
            override val contentType: String get() = "mem"
        }

        class More : Entry() {
            override val key: String get() = "more"
            override val contentType: String get() = "more"
        }
    }

    /**
     * 读全部人偶的最近记忆并合并排序（**只读**：只调 [Mem.list]，不调任何写入）。
     *
     * 【线程】读盘 + JSON 解析，调用方在 IO 线程调它。
     * 【上限】每人偶只取尾部 [TAIL_PER_DOLL] 条，合并后截 [MAX_ITEMS] 条。
     */
    private fun readAll(act: MainActivity): Snapshot {
        val dolls = try {
            DollStore.all(act)
        } catch (ignored: Throwable) {
            return Snapshot(emptyList(), false, "", false, true)
        }
        val own = dolls.filter { !it.builtin }
        val out = ArrayList<ShuoItem>()
        for (d in dolls) {
            val arr = try {
                Mem.list(act, d.id)
            } catch (ignored: Throwable) {
                null
            }
            if (arr == null) {
                continue
            }
            var took = 0
            // 磁盘序是旧到新，从尾部倒着取就是「最近 N 条」。
            for (i in arr.length() - 1 downTo 0) {
                if (took >= TAIL_PER_DOLL) {
                    break
                }
                val o = arr.optJSONObject(i) ?: continue
                val m = try {
                    Memory.fromJson(o)
                } catch (ignored: Throwable) {
                    null
                } ?: continue
                val title = m.title.trim()
                val body = m.content.trim()
                if (title.isEmpty() && body.isEmpty()) {
                    continue
                }
                val ts = if (m.updatedAt > m.createdAt) m.updatedAt else m.createdAt
                out.add(
                    ShuoItem(
                        key = d.id + "|" + m.id,
                        dollId = d.id,
                        dollName = d.name,
                        builtin = d.builtin,
                        title = title,
                        body = body,
                        tags = m.tags.take(3),
                        ts = ts
                    )
                )
                took++
            }
        }
        val sorted = out.sortedByDescending { it.ts }.take(MAX_ITEMS)
        val first = own.firstOrNull()
        return Snapshot(
            items = sorted,
            hasOwnDoll = own.isNotEmpty(),
            firstDollId = first?.id ?: "",
            hasKey = PetPrefs.hasKey(act),
            autoOn = PetPrefs.mem2Auto(act)
        )
    }

    /** 头像只解一次、按 96px 采样（32dp 在 3x 屏上约 96px），避免每行解码原图。 */
    private fun avatars(act: MainActivity, items: List<ShuoItem>): Map<String, android.graphics.Bitmap> {
        val out = HashMap<String, android.graphics.Bitmap>()
        val seen = HashSet<String>()
        for (it in items) {
            if (!seen.add(it.dollId)) {
                continue
            }
            val doll = try {
                DollStore.byId(act, it.dollId)
            } catch (ignored: Throwable) {
                null
            }
            if (doll == null) {
                continue
            }
            val bm = try {
                DollStore.coverOf(act, doll, 96)
            } catch (ignored: Throwable) {
                null
            }
            if (bm != null && !bm.isRecycled) {
                out[it.dollId] = bm
            }
        }
        return out
    }

    /* ============================ 时间文案 ============================ */

    /** 分组的日 key（yyyy-MM-dd），用于把连续条目归到同一天。 */
    private fun dayKey(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
    }

    /**
     * 日期头文案：今天 / 昨天 / 同年 MM-dd / 跨年 yyyy-MM-dd。
     *
     * 【为什么把 today / yesterday / year 当参数传进来】这三串在一屏里是常量。
     *   若每条都新解一次 SimpleDateFormat 再比较，240 条就是近千次分配；
     *   而「今天 / 昨天 / 今年」在切行时算一次就够。
     */
    private fun dayLabel(day: String, ts: Long, today: String, yesterday: String, year: String): String {
        if (day == today) {
            return "今天"
        }
        if (day == yesterday) {
            return "昨天"
        }
        val sameYear = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(ts)) == year
        return SimpleDateFormat(if (sameYear) "MM-dd" else "yyyy-MM-dd", Locale.getDefault())
            .format(Date(ts))
    }

    /** 条目上的时刻（只到分钟 —— 时间线的精度需求就到这里）。 */
    private fun clockText(ts: Long): String {
        if (ts <= 0L) {
            return ""
        }
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    }

    /**
     * 把（已按时间倒序的）条目切成「日期头 + 条目」的行序列。
     *
     * 【为什么在数据侧切、不在 Composable 里切】行模型要拿去当 LazyColumn 的 key / contentType，
     *   必须在组合之外算好，且只在条目或分页变化时重算一次。
     */
    private fun buildRows(list: List<ShuoItem>): List<Entry> {
        val rows = ArrayList<Entry>(list.size + 8)
        if (list.isEmpty()) {
            return rows
        }
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val yesterday = dayKey(now - 86400000L)
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(now))
        var lastDay = ""
        for (it in list) {
            val day = dayKey(it.ts)
            if (day != lastDay) {
                rows.add(Entry.Header(dayLabel(day, it.ts, today, yesterday, year)))
                lastDay = day
            }
            rows.add(Entry.Post(it))
        }
        return rows
    }

    /**
     * 打开某只人偶的会话。
     *
     * 【为什么先切池再开】人偶池与自聊池是两个独立的历史库；从说说点进去，
     *   用户意图明确是「和她聊」，与联系人页同一套落地逻辑（[ChatSessions.ensureForDoll]）。
     */
    private fun openDoll(act: MainActivity, dollId: String) {
        if (dollId.isEmpty()) {
            DollListScreen.open(act)
            return
        }
        try {
            ChatSessions.setPool(act, ChatSessions.POOL_PET)
            val id = ChatSessions.ensureForDoll(act, dollId)
            ChatListScreen.openConv(act, id)
        } catch (ignored: Throwable) {
            // 打不开会话时退回聊天列表，不让点击变成「点了没反应」。
            HomeUi.openChat(act)
        }
    }
}
