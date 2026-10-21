package com.dollhouse.app.ui.chat

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.ChatHistoryStore
import com.dollhouse.app.data.MyProfile
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.home.ChatBadge
import com.dollhouse.app.ui.home.ProfileDrawer
import com.dollhouse.app.ui.home.ProfileState
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.jvm.JvmStatic
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 【职责】聊天列表页 —— **App 的默认首页**（需求原文：「将软件启动后的默认页面改为聊天列表，
 *          每个卡片对应一个好友会话」）。
 *
 * 【为什么单独一页而不是复用 ChatDrawer】抽屉（`ChatDrawer`）是「聊天页里的一个浮层」，
 *   用命令式 View 拼装、只有名字与时间两行文字，且它渲染的是**当前池**的子集。
 *   本页是**主界面的一级页面**：要有头像、最近一条、未读红点、搜索、双池合并视图，
 *   还要能常驻后台并保持滚动位置。两者的生命周期与信息密度都不同，
 *   合成一个会立刻处处 `if (是抽屉)` —— 复用的代价大于重写。
 *
 * 【单池还是双池】本页默认显示**两个池合并**的视图。
 *   改造前「自聊」与「人偶」是两个互斥的池，用户要切池才能看到另一边的会话，
 *   认知负担重（「我的对话去哪了」）。列表页把两边都以卡片形式铺出来，
 *   用右上角的小标签区分来源；点进哪条就自动切到它所属的池（见 [openConv]）。
 *   这保留了底层数据模型（两池互不干扰、记忆分库），只把**展示**合起来。
 *
 * 【搜索】纯本地过滤（名字 + 预览文本），不查历史正文 —— 后者要把几十条会话的
 *   全文历史都解出来，代价与收益不成比例，且列表页的诉求是「找到那个人」而不是「找那句话」。
 *
 * 【线程】列表数据（读盘 + 解码头像）走 IO；结果回主线程写 Compose 状态。
 */
object ChatListScreen {

    /** 页面 key（进 [com.dollhouse.app.ui.compose.PageStack] 用；本页也可作为 Tab 直接渲染）。 */
    const val KEY = "chat:list"

    /** 头像边长（dp）。 */
    private const val AVATAR_DP = 52
    /** 【r14·需求 3】顶栏左上角「我」的头像边长（dp）—— 比列表头像小一圈，不抢标题视觉重心。 */
    private const val HEAD_AVATAR_DP = 36

    /** 列表顶部 / 底部内边距（底部要避开悬浮底栏，由宿主用 [contentPadding] 传进来）。 */
    private val PAD_H = 16.dp

    /** 供宿主把「当前 Tab」直接渲染成 View（与 [com.dollhouse.app.ui.home.HomeScreen] 同构）。 */
    @Composable
    fun Content(act: MainActivity) {
        Screen(act)
    }

    /** 独立成页（保留给「从别处打开会话列表」的入口，例如从人偶页跳过来）。 */
    @JvmStatic
    fun buildPage(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Screen(act) }
    }

    @Composable
    private fun Screen(act: MainActivity) {
        val c = DhTokens.colors
        // 【变更信号】会话的增删改 / 新消息都会让列表重算。
        //   【两道信号】① [ChatState.listRev] —— 本页自己做的操作（改名 / 新建 / 已读）；
        //   ② [ChatHistoryStore.ChatListSignal] —— **其它页面**发的消息（聊天页、人偶迷你框）。
        //   缺了 ②，用户从聊天页返回列表时预览与未读仍是旧的，只能等下次冷启动才刷新。
        var rev by remember { mutableStateOf(0) }
        // 【生命周期】注册一次、整页存活期间有效。用 DisposableEffect 在离开时注销，
        //   避免页面反复开关时监听器堆积。
        androidx.compose.runtime.DisposableEffect(Unit) {
            val listener: () -> Unit = {
                // 【必须回主线程】push 可能发生在 IO 线程（PetTalk 的落库路径），
                //   而 Compose 状态只能在主线程改。
                act.runOnUiThread { rev++ }
            }
            ChatHistoryStore.ChatListSignal.watch(listener)
            onDispose { ChatHistoryStore.ChatListSignal.unwatch(listener) }
        }
        var rows by remember { mutableStateOf<List<ChatListData.Row>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        // 改名弹窗的目标 id（null = 不显示）。
        var renaming by remember { mutableStateOf<String?>(null) }
        var renameDraft by remember { mutableStateOf("") }
        // 【r13】长按管理菜单的目标行（null = 不显示）。
        var menuFor by remember { mutableStateOf<ChatListData.Row?>(null) }

        // 【为什么要 LaunchedEffect】列表数据要读盘 + 解码头像，放主线程会卡首帧。
        //   两个 rev 任一变化即重新拉取：发完消息回到列表、删了会话、改了名，都能自动跟上。
        LaunchedEffect(rev, ChatState.listRev, DollState.rev) {
            val data = withContext(Dispatchers.IO) {
                // 【r13·一人偶一对话】先让会话清单对齐人偶库：每只人偶都有一条对话框。
                //   放在读列表之前，保证「新建人偶 → 切回聊天列表」就已经能看到它的对话。
                ChatSessions.syncWithDolls(act)
                ChatListData.load(act, null)
            }
            rows = data
            loaded = true
            // 【徽标同步】算完顺手把未读总和写给底栏 —— 谁算出来谁写，见 [ChatBadge]。
            ChatBadge.set(data.sumOf { it.unread })
        }

        /**
         * 过滤后的行。
         *
         * 【匹配维度】标题 / 预览 / **绑定人偶名** —— 三者任一命中即算。
         *   【为什么要人偶名】用户记得住的是「小肥鱼」这种角色名，未必记得住会话标题
         *   （标题可能是「新的对话」，或很久以前自动取的第一句话）。
         * 【大小写】统一转小写比较，英文名不会因为大小写而搜不到。
         * 【多词】按空格切开，**每个词都要命中**（AND 语义）——
         *   搜「小肥 鱼」能同时收窄，比整串匹配更好用。
         */
        val shown = remember(rows, query) {
            val q = query.trim()
            if (q.isEmpty()) {
                rows
            } else {
                val words = q.lowercase().split(' ', '\u3000').filter { it.isNotEmpty() }
                if (words.isEmpty()) {
                    rows
                } else {
                    rows.filter { row ->
                        val hay = (row.title + "\n" + row.preview + "\n" + row.dollName).lowercase()
                        words.all { hay.contains(it) }
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- 顶栏 ----------
            // 【统一】改走 [DhKit.TabTopBar]：字号 / 内边距 / 右端对齐由三格共用同一段代码，
            //   不再每个 Tab 手搓一套（改造前「说说」用 20sp、本页 26sp，切换时标题会跳）。
            DhKit.TabTopBar(
                title = "聊天",
                left = {
                    // ---------- 我的头像入口（r14·需求 3）----------
                    // 【位置】聊天列表**左上角** —— 用户给的 QQ 参考图正是这个位置。
                    // 【职责】点开「我的资料」抽屉（见 [ProfileDrawer.Host]，宿主在 HomeUi.apply）。
                    // 【为什么重读要挂 ProfileState.tick】头像存在 SharedPreferences 里，
                    //   在「我的资料」页换完头像返回后，本按钮不会自己更新 —— 需要一个重读信号。
                    val ptick = ProfileState.tick
                    val myAva = remember(ptick) { MyProfile.avatarBitmap(act, 128) }
                    Box(
                        modifier = Modifier
                            .size(HEAD_AVATAR_DP.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(c.soft)
                            .pressable { ProfileDrawer.toggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp: android.graphics.Bitmap? = myAva
                        if (bmp != null && !bmp.isRecycled) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                // 【Cover】圆形裁切区用 Fit 会在圆内留黑边，与列表头像同一口径。
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            DhKit.Icon(iconRes = Icons.IC_CIRCLE_USER, sizeDp = Design.SZ_LG, color = c.sub)
                        }
                    }
                },
                actions = {
                    // 【r13·一人偶一对话】原「新建会话」按钮已移除。
                    //   需求原文：「聊天界面不要无限添加，就像 QQ 好友一样，一个联系人对应的
                    //   一个对话框，不要无限增加对话框；以后聊天界面的对话框都得绑定人偶」。
                    //   会话是人偶的投影（见 ChatSessions.idForDoll），由人偶库决定有几条，
                    //   用户不再能凭空造出孤儿对话 —— 所以这个入口从语义上就该消失，
                    //   而不是留在那里点了报错。
                    //   想开新对话的正确路径：去「人偶」新建/导入一只人偶，它自带一个对话框。
                    DhKit.IconButton(Icons.IC_USERS, Design.SZ_MD, c.title, label = "人偶") {
                        com.dollhouse.app.ui.doll.DollListScreen.open(act)
                    }
                }
            )

            // ---------- 搜索框 ----------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAD_H)
                    .padding(bottom = 10.dp)
            ) {
                DhForm.Input(
                    value = query,
                    onValueChange = { query = it },
                    hint = "搜索会话或人偶名",
                    keyboard = androidx.compose.ui.text.input.KeyboardType.Text,
                    // 【搜索键】软键盘回车显示「搜索」而不是「完成」——
                    //   与本框的用途一致（虽然过滤是即时的，不依赖回车）。
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    // 【一键清空】右侧常驻「✕」（仅在输入非空时出现）：
                    //   搜索框在列表顶部，清空要比长按全选删更快。
                    trailing = if (query.isEmpty()) null else {
                        {
                            DhKit.IconButton(Icons.IC_CLOSE, Design.SZ_SM, c.sub) {
                                query = ""
                            }
                        }
                    }
                )
            }

            // ---------- 列表 / 空态 ----------
            when {
                !loaded -> {
                    // 首帧：数据还没回来。不显示「空」以免闪一下「没有会话」的文案。
                    Box(Modifier.fillMaxSize())
                }

                shown.isEmpty() -> {
                    EmptyState(
                        searching = query.trim().isNotEmpty(),
                        // 【r13·空态引导改造】切到「人偶」Tab（而不是打开人偶管理页）——
                        //   用户此刻要的是「找人聊」，不是「建角色」。
                        onAddDoll = {
                            com.dollhouse.app.ui.home.AppNav.select(act, com.dollhouse.app.ui.home.AppNav.TAB_CONTACT)
                            com.dollhouse.app.ui.home.HomeUi.showTab(act, com.dollhouse.app.ui.home.AppNav.TAB_CONTACT)
                        }
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = PAD_H,
                            end = PAD_H,
                            // 【底部避让】主界面底栏是悬浮的（不在布局流里），列表要自己留出高度，
                            //   否则最后一张卡片会被底栏压住点不到。
                            bottom = com.dollhouse.app.ui.home.BottomNav.HEIGHT_DP.dp + 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(shown, key = { it.id }) { row ->
                            Card(
                                row = row,
                                onClick = {
                                    ChatListData.markRead(act, row.id)
                                    ChatState.bumpList()
                                    openConv(act, row.id)
                                },
                                // 【长按管理】长按弹出「重命名 / 删除」菜单。
                                //   需求原文：「聊天界面的会话没有删除的方法 建议长按的时候给个删除」。
                                //   与列表的「点击进入」不冲突（见 pressable 的带长按重载）。
                                onLong = { menuFor = row }
                            )
                        }
                    }
                }
            }
        }

        // ---------- 长按管理菜单 ----------
        // 【交互】长按卡片 → 弹出「重命名 / 删除 / 取消」。
        //   与 QQ / 微信的列表长按一致：管理单条的操作集中在一个浮出菜单里，
        //   不占用卡片的视觉空间，也不会误触（长按本身就有确认意味）。
        val menuRow = menuFor
        if (menuRow != null) {
            DhForm.Alert(
                title = menuRow.title,
                onDismiss = { menuFor = null },
                negText = "取消"
            ) {
                // 【r13·列表删除=隐藏】这里的「删除」只把条目移出列表，
                //   **不动聊天记录**（见 ChatListData.delete 的说明）。
                //   用户去「人偶」跟这只人偶再说一句话，条目会自动回来。
                //   文案用「从列表移除」把这个语义说清楚，避免用户以为记录没了。
                //   【为什么不做二次确认】长按本身就是一道确认门槛，且本操作可逆
                //   （说句话就回来），没有不可挽回的后果。
                DhKit.ActionRow(
                    label = "从列表移除",
                    iconRes = Icons.IC_TRASH
                ) {
                    ChatListData.delete(act, menuRow.id)
                    menuFor = null
                    ChatState.bumpList()
                }
                // 重命名：复用原有的改名弹窗。
                DhKit.ActionRow(
                    label = "重命名",
                    iconRes = Icons.IC_SIGNATURE
                ) {
                    renaming = menuRow.id
                    renameDraft = menuRow.title
                    menuFor = null
                }
                // 【r19·置顶】把该会话顶到列表最前（ updated 时间戳改为当前，触发排序上浮）。
                DhKit.ActionRow(
                    label = "置顶",
                    iconRes = Icons.IC_CHEVRON_UP
                ) {
                    ChatListData.pinToTop(act, menuRow.id)
                    menuFor = null
                    ChatState.bumpList()
                }
            }
        }

        // ---------- 改名弹窗 ----------
        if (renaming != null) {
            DhForm.Alert(
                title = "重命名会话",
                onDismiss = { renaming = null },
                posText = "保存",
                onPos = {
                    val id = renaming
                    val name = renameDraft.trim()
                    if (id != null && name.isNotEmpty()) {
                        ChatListData.rename(act, id, name)
                        ChatState.bumpList()
                    }
                    renaming = null
                },
                negText = "取消"
            ) {
                DhForm.Input(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = "名字",
                    hint = "给这个会话起个名字",
                    keyboard = androidx.compose.ui.text.input.KeyboardType.Text
                )
            }
        }
    }

    /**
     * 打开一个会话（点列表卡片 → 直接进聊天页）。
     *
     * 【这就是需求里的「第 4 幅图」】点聊天列表里的某条对话，直接进入该对话的聊天界面 ——
     *   不再经过任何中间页、也不再需要主页上的「打开聊天」按钮。
     *
     * 【为什么先 switchTo】列表是把两个池合并展示的，而底层聊天页一次只显示一个池
     *   （`ChatSessions.pool`）。点某条会话时必须把「当前池 + 当前会话」一起切准，
     *   否则聊天页会打开成另一个池里的同名会话。
     *
     * 【人设怎么对上】会话 id 是 `d_<dollId>`（见 [ChatSessions.idForDoll]），
     *   聊天页通过 `ChatPanel.boundDollId()` 从当前会话反查绑定人偶，
     *   再按它取人设与记忆 —— 所以这里只要把「当前会话」切准，人设就自动跟着对。
     */
    fun openConv(act: MainActivity, convId: String) {
        ChatSessions.switchTo(act, convId)
        com.dollhouse.app.ui.home.HomeUi.openChat(act)
    }

    /** 单张会话卡片：头像 + 名字 + 最近一条 + 时间 + 未读。 */
    @Composable
    private fun Card(row: ChatListData.Row, onClick: () -> Unit, onLong: () -> Unit) {
        val c = DhTokens.colors
        // 【统一】会话卡片属于「一张卡片」，圆角取全 App 卡片口径 [DhKit.R_CARD]（16dp）。
        //   改造前写的是 14dp —— 与设置页卡片（16）、行（10）都不同族，切换页面时能看出差异。
        val shape = RoundedCornerShape(DhKit.R_CARD.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.card)
                .border(1.dp, c.line, shape)
                .pressable(
                    onClick = onClick,
                    onLongClick = onLong
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(row)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title,
                        modifier = Modifier.weight(1f, fill = false),
                        color = c.title,
                        fontSize = 16.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 【池标签】让用户一眼知道这条是「人偶」还是「自聊」。
                    if (row.isPet) {
                        Spacer(Modifier.width(6.dp))
                        Tag(text = "人偶")
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = row.preview,
                    color = c.sub,
                    fontSize = 13.sp,
                    fontFamily = DhTokens.fonts,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = timeText(row.time),
                    color = c.sub,
                    fontSize = 11.sp,
                    fontFamily = DhTokens.fonts
                )
                if (row.unread > 0) {
                    Spacer(Modifier.height(6.dp))
                    UnreadDot(row.unread)
                }
            }
        }
    }

    /** 头像：有图就画图，没有就给一枚圆底 + 用户图标（不用网络、不占位闪烁）。 */
    @Composable
    private fun Avatar(row: ChatListData.Row) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier
                .size(AVATAR_DP.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.soft),
            contentAlignment = Alignment.Center
        ) {
            val bmp = row.avatar
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // 【Cover 而不是 Fit】头像是圆形裁切区，用 Fit 会在圆内留出黑边；
                    //   Cover 铺满后由 clip 收成圆形，与主流聊天软件一致。
                    contentScale = ContentScale.Crop
                )
            } else {
                // 人偶池默认给人偶图标，自聊池给用户图标 —— 空头像也能分清来源。
                DhKit.Icon(
                    iconRes = if (row.isPet) Icons.IC_WHALE else Icons.IC_USER_ROUND,
                    sizeDp = Design.SZ_LG,
                    color = c.sub
                )
            }
        }
    }

    /** 未读红点。 */
    @Composable
    private fun UnreadDot(n: Int) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(c.err)
                .padding(horizontal = 6.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (n > 99) "99+" else n.toString(),
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
        }
    }

    /** 小标签（人偶 / 自聊）。 */
    @Composable
    private fun Tag(text: String) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(c.option)
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text(
                text = text,
                color = c.acc,
                fontSize = 10.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /**
     * 空状态。
     *
     * 【两种文案】搜索无结果 vs 一条会话都没有 —— 前者要提示「换个词」，
     *   后者要引导「去建一只人偶」（改造后会话由人偶派生，不能凭空新建）。
     */
    @Composable
    private fun EmptyState(searching: Boolean, onAddDoll: () -> Unit) {
        val c = DhTokens.colors
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DhKit.Icon(Icons.IC_MESSAGE_SQUARE, Design.SZ_XL, c.line)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (searching) "没有匹配的会话" else "还没有对话",
                    color = c.sub,
                    fontSize = 15.sp,
                    fontFamily = DhTokens.fonts
                )
                if (!searching) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "每一只人偶自带一个对话",
                        color = c.line,
                        fontSize = 12.sp,
                        fontFamily = DhTokens.fonts
                    )
                    Spacer(Modifier.height(14.dp))
                    // 【r13·空态引导改造】需求原文：
                    //   「同时这里也不应该是去添加人偶 而是建议用户去联系人上找 人偶聊天」。
                    //   旧按钮「去添加人偶」跳的是**人偶管理页**（建角色的地方），
                    //   但用户此刻的意图是「聊天」—— 正确动作是去「人偶」页挑一只开聊，
                    //   而不是去建新的。这里改成切到「人偶」Tab，语义与落点都对上。
                    DhKit.OutlineChip(
                        text = "去和人偶聊天",
                        onClick = onAddDoll
                    )
                }
            }
        }
    }

    /** 时间文案：今天给 `HH:mm`，昨天给「昨天」，同年给 `MM-dd`，跨年给 `yyyy-MM-dd`。 */
    private fun timeText(ts: Long): String {
        if (ts <= 0L) {
            return ""
        }
        return try {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = ts }
            val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
            val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            if (sameDay) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
            } else {
                val diffDay = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
                if (sameYear && diffDay == 1) {
                    "昨天"
                } else if (sameYear) {
                    SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
                } else {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
                }
            }
        } catch (unused: Throwable) {
            ""
        }
    }
}
