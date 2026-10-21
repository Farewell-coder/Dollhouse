package com.dollhouse.app.ui.home

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollGroups
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.ui.chat.ChatListScreen
import com.dollhouse.app.ui.chat.ChatSessions
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.doll.DollGroupPage
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import kotlinx.coroutines.launch

/**
 * 【职责】人偶页（底部导航第 2 格）—— **QQ 联系人形态**（r14·需求 4）。
 *
 * 【形态来源】用户定案：「人偶的界面我也喜欢像 QQ 这样」，参考图是 QQ 的联系人页：
 *   可折叠分组 + 头像 / 昵称 / 签名 + 右侧 A-Z 拼音索引。
 *
 * 【与旧版的区别（改了什么）】
 *   | 项 | 旧 | 新 |
 *   |---|---|---|
 *   | 行内容 | 名字 + 「正在桌面上陪着你 / 未启用」 | **头像 + 名字 + 个性签名** |
 *   | 分组 | 无 | **手动分组**（可折叠，未分组垫底） |
 *   | 索引 | 无 | **右侧 A-Z 拼音首字母**，点字母跳转 |
 *   | 尾部 | 「管理人偶」入口 | **移除**（已搬到 设置 → 人偶） |
 *   | 点击 | 开聊 | 开聊（不变） |
 *
 * 【为什么把「正在桌面上陪着你」换成签名】那是**状态**不是**身份**。
 *   QQ 联系人页展示的是「这个人是谁」（昵称 + 签名），状态另用在线点表示。
 *   人偶的启用状态已经在底栏 / 首页按钮上有反馈，这里重复显示只会挤掉更有信息量的签名。
 *
 * 【本 App 没有通讯录】不申请 `READ_CONTACTS`，这一页列的自始至终都是用户自己创建的人偶。
 */
object ContactScreen {

    /** 搭出人偶页根视图（供 [HomeUi.apply] 装配进底座）。 */
    @JvmStatic
    fun buildPage(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @Composable
    private fun Content(act: MainActivity) {
        val c = DhTokens.colors
        // 【数据】人偶列表与分组都在 [DollState.rev] 变化时重算（与 DollListScreen 同一套机制）。
        val rev = DollState.rev
        // 分组是否存在也要跟着刷新（新建 / 删除组后会变）。
        var grpRev by remember { mutableStateOf(0) }

        // 【展开态】默认全部展开 —— 与 QQ 一致（首次点进去看到的是完整列表）。
        // 记住用户手动收起的组（用「收起集合」而不是「展开集合」：新组默认展开，符合直觉）。
        val collapsed = remember { mutableStateOf<Set<String>>(emptySet()) }

        val groups = remember(rev, grpRev) { DollGroups.grouped(act) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // 【A-Z 索引的数据】把「人偶名 → 首字母 → 在列表里的下标」算出来。
        //   索引跳转需要知道「某个字母的第一只人偶排在第几行」；
        //   带分组标题后行下标会偏移，所以下面按渲染顺序精确记账。
        val indexMap = remember(groups, rev, grpRev) {
            buildIndexMap(groups)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- 顶栏 ----------
            // 【统一】走 [DhKit.TabTopBar]，与聊天页 / 说说页同一段代码。
            DhKit.TabTopBar(
                title = "人偶",
                actions = {
                    // 【分组管理】用户定案：「手动分组」，入口在列表页顶部。
                    //   图标用 folder-tree（分组的层级语义），与「新建人偶」的 user-plus 区分开。
                    DhKit.IconButton(Icons.IC_FOLDER_TREE, Design.SZ_MD, c.title, label = "分组") {
                        DollGroupPage.open(act) { grpRev++ }
                    }
                    Spacer(Modifier.width(4.dp))
                    // 新手偶：建一个新角色。
                    DhKit.IconButton(Icons.IC_USER_PLUS, Design.SZ_MD, c.title, label = "新建") {
                        DollListScreen.open(act)
                    }
                }
            )

            if (groups.isEmpty() || groups.all { it.second.isEmpty() }) {
                EmptyState(act)
                return@Column
            }

            // ---------- 列表 + 右侧索引 ----------
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 34.dp,
                        bottom = BottomNav.HEIGHT_DP.dp + 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    for ((groupName, dolls) in groups) {
                        // ---------- 分组标题（可点击折叠）----------
                        item(key = "h_" + groupName) {
                            GroupHeader(
                                name = groupName,
                                count = dolls.size,
                                isNone = groupName == DollStore.GROUP_NONE,
                                folded = collapsed.value.contains(groupName)
                            ) {
                                collapsed.value = if (collapsed.value.contains(groupName)) {
                                    collapsed.value - groupName
                                } else {
                                    collapsed.value + groupName
                                }
                            }
                        }
                        if (!collapsed.value.contains(groupName)) {
                            items(dolls, key = { "d_" + it.id }) { doll ->
                                ContactRow(act, doll) { openChatWith(act, doll.id) }
                            }
                        }
                    }
                }

                // ---------- 右侧 A-Z 索引 ----------
                if (indexMap.isNotEmpty()) {
                    AlphaIndex(
                        letters = indexMap.keys.toList(),
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) { letter ->
                        val row = indexMap[letter] ?: return@AlphaIndex
                        // 【跳转用 scrollToItem】用动画版（animateScrollToItem）在跳得远时
                        //   会让用户等很久才到；索引是「直达」语义，瞬间到位更符合预期。
                        scope.launch { listState.scrollToItem(row) }
                    }
                }
            }
        }
    }

    /**
     * 分组标题：左箭头 + 组名 + 数量。
     *
     * 【为什么「未分组」不能被折叠成空】用户可能把所有人都归了组，
     *   此时「未分组」是空组 —— 仍然显示（并标 0），因为它是「这里可以放人」的锚点，
     *   藏起来会让用户以为「没分组这个概念」。
     */
    @Composable
    private fun GroupHeader(name: String, count: Int, isNone: Boolean, folded: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .pressable(onClick)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(
                iconRes = if (folded) Icons.IC_CHEVRON_RIGHT else Icons.IC_CHEVRON_DOWN,
                sizeDp = Design.SZ_XS,
                color = c.sub
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = name,
                color = c.sub,
                fontSize = 13.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                color = c.line,
                fontSize = 12.sp,
                fontFamily = DhTokens.fonts
            )
            if (isNone) {
                Spacer(Modifier.width(6.dp))
                // 【提示语】「未分组」是兜底桶，用户可能不知道能往里拖 —— 给一句极轻的说明。
                Text(
                    text = "· 没归组的人偶",
                    color = c.line,
                    fontSize = 11.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /** 一行联系人：头像 + 名字 + 个性签名。 */
    @Composable
    private fun ContactRow(act: MainActivity, doll: DollStore.Doll, onClick: () -> Unit) {
        val c = DhTokens.colors
        val shape = RoundedCornerShape(12.dp)
        // 【r24·修内存/主线程卡顿】46dp 头像却按 1024px 解码：
        //   一张 1024×1024 ARGB_8888 = 4MB，联系人列表几十行就是上百 MB，
        //   而且发生在组合阶段（主线程）。按「46dp × 3 ≈ 138px」取 160 足够清晰。
        val cover = remember(doll.id, doll.cover) {
            runCatching { DollStore.coverOf(act, doll, 160) }.getOrNull()
        }
        // 【签名取哪来】复用角色设定的「简介」（[DollProfile.spec].brief）——
        //   它本来就是「一句话介绍这只人偶」，正是签名该有的东西，无需新开字段。
        val brief = remember(doll.id, DollState.rev) {
            runCatching { DollProfile.spec(act, doll.id).brief }.getOrNull() ?: ""
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .pressable(onClick)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.soft),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null && !cover.isRecycled) {
                    androidx.compose.foundation.Image(
                        bitmap = cover.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    DhKit.Icon(
                        iconRes = if (doll.builtin) Icons.IC_WHALE else Icons.IC_USER_ROUND,
                        sizeDp = Design.SZ_LG,
                        color = c.sub
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = doll.name,
                        modifier = Modifier.weight(1f, fill = false),
                        color = c.title,
                        fontSize = 16.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (doll.enabled) {
                        Spacer(Modifier.width(6.dp))
                        // 【在线点】用「启用中」这一状态做一个小圆点，位置与 QQ 的在线标识一致。
                        //   它是**状态**，所以只用一个小点表示，不占一行文字。
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(c.ok)
                        )
                    }
                }
                if (brief.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = brief,
                        color = c.sub,
                        fontSize = 12.sp,
                        fontFamily = DhTokens.fonts,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    /** 空态：一只人偶都没有。 */
    @Composable
    private fun EmptyState(act: MainActivity) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DhKit.Icon(Icons.IC_USERS, Design.SZ_XL, c.line)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "还没有人偶",
                    color = c.sub,
                    fontSize = 15.sp,
                    fontFamily = DhTokens.fonts
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "建一只，她就会在这里出现",
                    color = c.line,
                    fontSize = 12.sp,
                    fontFamily = DhTokens.fonts
                )
                Spacer(Modifier.height(14.dp))
                DhKit.OutlineChip(text = "去添加人偶") {
                    DollListScreen.open(act)
                }
                Spacer(Modifier.height(BottomNav.HEIGHT_DP.dp))
            }
        }
    }

    /** A-Z 索引条的命中区宽度（dp）。视觉字母仍是 9sp，这里只放大可点面。 */
    private const val INDEX_HIT_W_DP = 24

    /** A-Z 索引条的最大总高（dp）—— 超出则压缩每行高度，避免顶出屏幕。 */
    private const val MAX_INDEX_HEIGHT_DP = 520

    /**
     * 右侧 A-Z 索引条。
     *
     * 【为什么放在列表右边缘、垂直居中】QQ / 微信 / 通讯录的统一位置；
     *   用户的手指记忆就在那里，换位置反而难找。
     * 【为什么字母很小】它是快速定位工具，不是内容；太大反而挡住列表。
     *
     * 【本轮·修「点不中」】改造前每个字母是一个 18dp 的 Box，且**它自己就是点击区**，
     *   手指很难命中（低于全 App 的 [DhKit.HIT]=40dp 口径）。
     *   现在视觉仍是 9sp 小字，但外层命中区放大到 24dp 宽 × 自适应行高。
     * 【本轮·修「字母多了顶出屏幕」】改造前用 `fillMaxHeight()`，
     *   字母一多（A-Z 再加 `#`）整列超出可用高度，首尾字母被裁掉且无从得知。
     *   现在整列限高 [MAX_INDEX_HEIGHT_DP]，按字母数分配行高（14~20dp），永不溢出。
     */
    @Composable
    private fun AlphaIndex(
        letters: List<String>,
        modifier: Modifier = Modifier,
        onPick: (String) -> Unit
    ) {
        val c = DhTokens.colors
        val rowH = if (letters.isEmpty()) 20f
        else (MAX_INDEX_HEIGHT_DP.toFloat() / letters.size).coerceIn(14f, 20f)
        Column(
            modifier = modifier
                .heightIn(max = MAX_INDEX_HEIGHT_DP.dp)
                .padding(end = 2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (l in letters) {
                Box(
                    modifier = Modifier
                        .height(rowH.dp)
                        .width(INDEX_HIT_W_DP.dp)
                        .pressable { onPick(l) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = l,
                        color = c.sub,
                        fontSize = 10.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    /**
     * 算命首字母 → 列表行下标 的映射。
     *
     * 【为什么必须与渲染顺序逐行对齐】索引跳转靠 `scrollToItem(index)`，
     *   而列表里掺着「分组标题」这一行（每组一行），行号不是「第几只人偶」而是「第几行」。
     *   所以这里必须**按渲染顺序**模拟一遍记账：每遇到一个分组先 +1（标题行），
     *   再逐只人偶 +1。算错一行的表现是「点 Z 跳到倒数第二只」，很难查。
     *
     * 【字母怎么来】中文取拼音首字母（见 [DollPinyin]），英文取首字母大写；
     *   取不到归 `#`（与 iOS / 微信一致）。
     */
    private fun buildIndexMap(groups: List<Pair<String, List<DollStore.Doll>>>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        var row = 0
        for ((_, dolls) in groups) {
            row++ // 分组标题占一行
            for (d in dolls) {
                val key = DollPinyin.initialOf(d.name)
                if (!out.containsKey(key)) {
                    out[key] = row
                }
                row++
            }
        }
        // 【排序】A-Z 在前，`#` 垫底（与通讯录惯例一致）。
        val sorted = ArrayList<Map.Entry<String, Int>>(out.entries)
        sorted.sortWith(Comparator { a, b ->
            val ha = a.key == "#"
            val hb = b.key == "#"
            when {
                ha && !hb -> 1
                !ha && hb -> -1
                else -> a.key.compareTo(b.key)
            }
        })
        val res = LinkedHashMap<String, Int>()
        for (e in sorted) {
            res[e.key] = e.value
        }
        return res
    }

    /**
     * 与某只人偶开聊：切到**她自己那条**会话，再进聊天页。
     *
     * 【为什么要「切池」】人偶池与自聊池是两个独立的历史库（见 [ChatSessions] 头注释）。
     *   从人偶页点进来，用户意图明确是「和她聊」，所以先把当前池切到 pet，
     *   再从聊天列表页的入口统一打开 —— 复用同一套打开逻辑，不在两处各写一遍。
     */
    private fun openChatWith(act: MainActivity, dollId: String) {
        try {
            ChatSessions.setPool(act, ChatSessions.POOL_PET)
            val id = ChatSessions.ensureForDoll(act, dollId)
            ChatListScreen.openConv(act, id)
        } catch (ignored: Throwable) {
        }
    }
}

/**
 * 【职责】取人偶名字的**拼音首字母**（A-Z / #）。
 *
 * 【为什么需要】QQ / 微信 / 通讯录的右侧索引都是按拼音排的 ——
 *   用户找「张三」会去 Z 那一段，而不是按汉字笔画。这是中文用户的既有习惯。
 *
 * 【为什么不引第三方库】本项目**零新增依赖**是硬约束（见工程基线）。
 *   全量拼音库（几百 KB）对一个索引功能来说太重。
 *   这里用**常用字表**方案：覆盖高频姓氏与常见名用字，查不到时归 `#`。
 *
 * 【为什么不按汉字节（Unicode 码位）划区间】那是个诱人但**错误**的做法：
 *   汉字码位是按部首 / 笔画排的（《康熙字典》顺序），与拼音毫无对应关系。
 *   按码位划「A 段 / B 段」会把「张」（U+5F20）分到与它拼音无关的桶里，
 *   表现是索引点了跳到八竿子打不着的地方，且很难查。
 *   与其给一个错的答案，不如如实归 `#` —— 与 iOS / 微信对生僻字的做法一致。
 */
object DollPinyin {

    /** 高频字直查表（覆盖常见姓氏与名字用字）。 */
    private val COMMON = HashMap<Char, Char>()

    private fun put(letter: Char, chars: String) {
        for (ch in chars) {
            COMMON[ch] = letter
        }
    }

    init {
        put('A', "阿爱安昂澳艾")
        put('B', "白百柏班包宝鲍悲毕边卞冰波伯卜步北贝斌宾彬秉波")
        put('C', "蔡曹岑查柴昌常畅超陈成程池迟赤初楚褚传春慈从崔存晨宸")
        put('D', "达大戴代单党邓狄翟丁东董都窦杜段盾多铎丹迪蝶冬")
        put('E', "恩尔耳二")
        put('F', "发范方房费丰封冯逢符伏扶甫傅富樊飞菲斐")
        put('G', "盖甘高郜戈葛耿弓公宫龚巩勾古谷顾关管桂郭国过刚")
        put('H', "哈海韩涵汉杭郝何和贺赫衡弘红洪侯胡花华怀槐黄惠霍辉")
        put('I', "伊")
        put('J', "姬基吉纪季济冀佳家贾简江姜蒋焦金靳京荆景敬居鞠君隽洁杰捷")
        put('K', "卡开凯康柯孔寇库匡邝葵坤阔可")
        put('L', "莱蓝郎劳乐雷冷梨黎李理厉利连廉练梁廖林蔺凌刘柳龙娄卢鲁陆路吕栾罗骆律亮琳林")
        put('M', "马麦满芒毛茅冒梅孟米苗缪闵明莫牟母木牧穆蒙梦敏")
        put('N', "那纳乃南倪聂宁牛农努诺女楠妮")
        put('O', "欧")
        put('P', "潘庞裴彭皮平蒲濮浦普朴培佩")
        put('Q', "齐祁钱强乔秦青卿清丘邱仇裘曲屈瞿权全阙琪琦")
        put('R', "冉饶任荣容阮芮瑞润若蓉")
        put('S', "萨赛桑沙山商邵佘申沈盛师施石时史士寿舒束帅双司宋苏宿隋孙索思思")
        put('T', "塔台太谈谭汤唐陶藤滕天田通佟童涂屠脱拓婷彤")
        put('W', "万汪王危韦卫魏温文闻翁邬巫吴伍武务悟伟薇雯")
        put('X', "西习席夏鲜项向肖萧谢辛邢幸熊修胥徐许宣薛荀寻逊欣鑫")
        put('Y', "雅焉鄢严言阎颜晏燕杨羊阳姚叶伊衣易羿殷尹应英鹰雍勇尤于余俞虞宇禹郁喻元袁原岳云运瑶莹颖")
        put('Z', "臧曾查翟詹湛章张赵甄郑支钟周朱诸祝庄卓宗邹祖左佐志智")
    }

    /**
     * 取名字的首字母。
     *
     * 【取第几个字】取**第一个字**。中文人名的索引惯例是按姓排（张 → Z、李 → L），
     *   取名字首字正好就是姓，符合直觉；取整串首字符也是同一个结果。
     *
     * @return A-Z 中的一个大写字母；查不到返回 `#`。
     */
    fun initialOf(name: String?): String {
        val s = name?.trim() ?: return "#"
        if (s.isEmpty()) {
            return "#"
        }
        val ch = s[0]
        // 英文 / 数字直接取（英文名按首字母索引，数字归 #，与通讯录一致）。
        if (ch in 'A'..'Z' || ch in 'a'..'z') {
            return ch.uppercaseChar().toString()
        }
        if (ch in '0'..'9') {
            return "#"
        }
        // 高频字表直查；查不到归 #（见类头注释：码位区间是错的，不做）。
        val hit = COMMON[ch]
        return if (hit == null) "#" else hit.toString()
    }
}