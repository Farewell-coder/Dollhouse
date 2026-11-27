package com.dollhouse.app.ui.home

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
import com.dollhouse.app.ui.qzx.QzxPageScroll
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.ChatSettingsPage
import com.dollhouse.app.ui.settings.AppearancePage
import com.dollhouse.app.ui.settings.MemSettingsPage
import com.dollhouse.app.ui.settings.MyProfilePage
import com.dollhouse.app.ui.settings.SleepSettingsPage
import com.dollhouse.app.ui.settings.VisionModelPage
import com.dollhouse.app.ui.settings.WanderSettingsPage
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.UiKit
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
// 【v0.2.8·修 P2】恢复前概览弹窗可能到 10~14 行，必须可滚动，否则尾部
//   「API Key 需要重新填写」在小屏 / 大字体下会被裁掉且无法查看。
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.MyProfile
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.design.LocalExpandedWidth
import com.dollhouse.app.ui.kit.DhLoadingState
import com.dollhouse.app.ui.kit.LoadingState
import com.dollhouse.app.ui.theme.Design

/**
 * 【职责】设置页（Compose）：权限 / 聊天 / 人偶 / 外观 四张卡片。
 * 【r11】「记忆」整张卡片已搬到人偶编辑页的「人格」页（手风琴展开）——
 *   记忆从这一版起按人偶分库，放在全局设置里已经没有意义。
 *
 * 【为什么整页重写】原设置页是「一棵长树 → SettingsPage 按标题切折叠卡片 →
 *   HomeUi.buildSettingsPage 再拼一次」的双重整理；Compose 侧直接声明式写出六张卡，
 *   `SettingsRegistry` / `SettingsCard` / 标题匹配 / 折叠状态全部不再需要。
 *
 * 【契约】对外只暴露 [build]；页面 tag 由调用方打上。
 */
/**
 * 卡片内行分隔线的左缩进（dp）。
 *
 * 【为什么是 28】行首图标 20dp + 图标与文字间距 8dp = 28：分隔线从**文字左缘**起，
 *   与主流设置页一致（线不穿过图标区）。行没有图标时传 0。
 */
private const val ICON_ROW_INSET = 28

/**
 * 恢复备份时的内存峰值倍数：**文件字节数 × 本值 ≈ 恢复过程需要的堆**。
 *
 * 【怎么来的】不是拍脑袋，是在 JVM 上用二分法压 `-Xmx` 求「刚好不 OOM 的最小堆」，
 *   用与真实信封同形状的样件量出来的（详见 [memoryGateMessage] 的注释）：
 *   · 图片主导（真实备份几乎都是这种）：10 MB→6.00、20 MB→5.81、
 *     39 MB→5.64、50 MB→5.62、98 MB→5.36（倍）；
 *   · 纯中文长文本：4.1 倍。
 *   取 6 覆盖图片主导的上界。为什么峰值会是文件体积的六倍：读盘后的 `byte[]`（1×）
 *   在解析期间不能释放，解码出的 `String` 是 UTF-16（2×），`JSONObject` 里的每个
 *   字符串值又各存一份（2×），再加上 base64 解码出的图片字节（1×）。
 *
 * 【为什么不用更小的倍数】这个闸门的作用是"提前告诉用户原因"，不是精确预测。
 *   估小了等于没拦住（放行然后闪退），估大了只是偶尔多劝一次 —— 两种错法的代价不对称。
 */
private const val PEAK_FACTOR = 6L

/**
 * 备份卡结果提示的**三态**（此前是 Boolean `okMsg`，只有"成功 / 失败"两档）。
 *
 * 【为什么必须三档】v0.2.8 的导出与恢复都存在"**部分成功**"这个真实状态：
 *   · 导出：包已生成、已分享，但有 N 张图片因体积预算被跳过（`lastSkippedImageCount()`）；
 *   · 恢复：绝大部分写回成功，但有个别图片写盘失败。
 *   用布尔值时这两种状态只能二选一，于是出现两种说谎：
 *   把"部分成功"染成**绿色**（用户以为全好了），或染成**红色**（文案却写着"已生成"）。
 *   两者都比"多说一句实话"更糟 —— 用户要么漏备份，要么白白重做一遍。
 *
 * 【为什么 PARTIAL 用中性色而不是新增一个 warn 色】`DhTokens.colors` 目前只有
 *   `ok` / `err` 两档语义色（见 `DhTheme.kt` 的 `DhColors` 字段表），加一个 warn
 *   要同时改动配色方案与全部主题分支，波及面远大于本卡片的收益。
 *   用 `sub`（正文次要色，中性）恰好是诚实的表达：**不是好消息，也不是失败**。
 */
private enum class BackupMsgKind {
    /** 还没操作过，不显示提示。 */
    NONE,

    /** 全部成功。 */
    OK,

    /** 做成了，但有东西没跟上（图片被跳过 / 写盘失败）。 */
    PARTIAL,

    /** 失败，没有任何可用产出。 */
    ERR
}

object SettingsScreen {

    /** 【r13】设置页作为**覆盖层**时的页面 key（改造后它不再是底座之一，见 [open]）。 */
    const val TAG_PAGE = "feiyu_settings_page"

    /**
     * 【r13】把设置页压到页面栈上。
     *
     * 【为什么从底座改成覆盖层】改造前三 Tab 之外还有一个「设置页」作为底座之一，
     *   于是底座的语义变成「主页 ↹ 设置页」的两个面孔 —— 而设置本质上是
     *   **从主页点进去**的二级页面（它有自己的返回按钮、自己的滚动位置）。
     *   收进页面栈后，返回键、层序存档、跨重建恢复全部走既有统一机制，
     *   不需要再为它单开一套「底座互切」的逻辑。
     *
     * 【与其它覆盖层的关系】它现在是页面栈里普普通通的一层，
     *   [PageRegistry] 里也登记了它，所以重建后能原样恢复。
     */
    @JvmStatic
    fun open(act: MainActivity) {
        ComposeHost.installForActivity(act)
        com.dollhouse.app.ui.compose.PageStack.push(act, TAG_PAGE) { build(act) }
    }

    /** 搭出设置页根视图。 */
    @JvmStatic
    fun build(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { SettingsContent(act) }
    }

    /**
     * 顶栏里的「搜索设置」入口（单栏与双栏共用同一个组合件）。
     *
     * 【为什么用顶栏图标而不是常驻搜索框】设置页是「卡片流 + 折叠」，顶部常驻一个
     *   56dp 搜索框会永久挤掉一屏高度、且与卡片的圆角/间距节奏打架；图标点一下进
     *   独立的搜索页，输入框能自动聚焦、结果区能占满全屏，两件事各自纯粹。
     *   与 012 方案 §4「设置首页搜索胶囊入口」同一取向，只是落点选在顶栏 ——
     *   这样双栏布局（`SettingsTwoPane` 自带 TopBar）也能复用同一处入口。
     *
     * 【图标为什么用 IC_ZOOM_IN】工程里没有 ic_search 矢量图，`IC_ZOOM_IN` 是现成的
     *   放大镜字形 —— 视觉上就是「搜索」。新增 ic_search 属于 res 层改动，留给后续。
     *
     * 【无障碍】挂 `a11yName`，读屏念「搜索设置」而不是"按钮"。
     */
    @Composable
    private fun SearchAction() {
        DhKit.IconButton(
            iconRes = Icons.IC_ZOOM_IN,
            sizeDp = Design.SZ_MD,
            color = DhTokens.colors.title,
            a11yName = "搜索设置"
        ) {
            com.dollhouse.app.ui.settings.SettingsSearchState.open = true
        }
    }

    @Composable
    private fun SettingsContent(act: MainActivity) {
        // 进页对一次真实状态（从系统设置页返回后立即刷新权限行）。
        LaunchedEffect(Unit) { HomeUi.syncAll(act) }

        // 【P1-e·设置搜索】搜索覆盖层：展开时**整页替换**为搜索页。
        //   放在双栏判断之前 —— 手机/平板两种布局都要能被搜索覆盖，
        //   否则平板上点搜索会「搜索页只在右栏里出现」。
        //   为什么不做成独立 PageStack 页：那要动路由白名单（NavKeyCodec / PageRegistry /
        //   新 Route 三处联动），而搜索页没有「被系统重建后要还原」的需求。详见 SettingsSearchPage 头注释。
        if (com.dollhouse.app.ui.settings.SettingsSearchState.open) {
            com.dollhouse.app.ui.settings.SettingsSearchPage(act) {
                com.dollhouse.app.ui.settings.SettingsSearchState.open = false
            }
            return
        }

        // 【P2-b·平板双栏】Expanded（≥840dp）时改成「左分组导航 + 右内容」。
        //   Compact / Medium 完全走原来的单栏路径（见下方 early return），
        //   所以现有手机用户**逐像素不变**。
        if (LocalExpandedWidth) {
            SettingsTwoPane(act)
            return
        }

        // 【新 UI 系统·第一页】骨架换成 `QzxPage`：它自带**新**大标题栏（QZX 规格）
        //   与越界回弹，且滚动状态由它统一持有并分发给标题栏/回弹（旧系统靠人手传）。
        //   `scroll = Scroll` 是因为本页内容是 6 个既有 Composable（普通 Column），
        //   不是懒加载列表 —— 由 QzxPage 负责包裹 verticalScroll。
        QzxPage(
            title = "设置",
            onBack = {
                // 【返回】设置页是覆盖层，退层即可露出它下面的主页 ——
                //   不再需要「切回首页」那种底座级操作。
                com.dollhouse.app.ui.compose.PageStack.pop(act)
            },
            actions = { SearchAction() },
            scroll = QzxPageScroll.Scroll,
            // 【图标由调用方给】新系统不持有旧 drawable（删旧资源时这里会编译不过，
            //   正好是"没迁完"的信号，而不是静默退化）。
            backIcon = androidx.compose.ui.res.painterResource(Icons.IC_ARROW_LEFT),
            // 【有没有背景图是 App 的行为，不是 UI 系统的职责】传一个 Boolean 进来。
            hasPhotoBackdrop = DhKit.hasPhotoBackdrop()
        ) {
            // 【本轮·补设计稿要求的资料卡】设计稿 §4.6 的设置页是「资料卡 + 分组导航行」结构：
            //   顶部一张 Profile card（头像 56 + 我的资料 + 说明 + 箭头），下面是分组入口。
            //   现状是 6 张折叠卡，完全没有这张资料卡 —— 而「我的资料」的唯一入口
            //   藏在聊天列表左上角的头像抽屉里。设置页里找不到「我自己」是反直觉的：
            //   用户想改昵称/头像时，第一反应是进设置。
            //   【为什么这只是一张入口卡，不是把编辑搬进来】编辑仍走 MyProfilePage，
            //   这里只提供「看得见、点得到」的入口，避免一个页面承担两种职责。
            ProfileCard(act)
            PermissionRow(act)
            ChatSection(act, showTitle = true)
            // 一份定义、三处调用：单栏这里用 showTitle = true；
            //   两栏右栏用 showTitle = false（左栏选中项已经是标题，卡内再画一遍是重复）。
            DollSection(act, showTitle = true)
            LookSection(act, showTitle = true)
            BackupCard(act)
            // 【r13】「实验」卡片：收纳尚未正式归入设置卡片、但已经可用的实验性功能。
            //   用户定案：**排在最后一张**，且是**展开式**（卡片内直接展开，不跳页）——
            //   与上面四张卡片的展开方式一致。
            ExperimentSection(act, showTitle = true)
        }
    }

    /* ==================== P2-b·平板双栏（Expanded） ==================== */

    /**
     * 设置页的分组清单。
     *
     * 【为什么用枚举而不是字符串】左栏要按固定顺序渲染、右栏要按选中项渲染，
     *   两边必须是**同一份有序定义**。用字符串 Key 拼两边，早晚会出现
     *   「左栏有这一组、右栏 when 里漏了」——那种 bug 的表现是右栏一片空白。
     *
     * 【为什么 6 组而不是 5 组】[ProfileCard] 与 [PermissionRow] 在单栏版里排在
     *   所有卡片之前，双栏版把它们各自归成一组，顺序与单栏版**完全一致**
     *   （资料 → 权限 → 聊天 → 人偶 → 外观 → 备份 → 实验）。分组数变了没关系，
     *   顺序与内容不变才是重点 —— 否则同一批设置在两个形态下"位置感"不同。
     */
    private enum class SettingsGroup(val label: String, val iconRes: Int) {
        PROFILE("我的资料", Icons.IC_CIRCLE_USER),
        PERMISSION("权限与保活", Icons.IC_SHIELD_CHECK),
        CHAT("聊天", Icons.IC_MESSAGE_SQUARE_HEART),
        DOLL("人偶", Icons.IC_USERS),
        LOOK("外观", Icons.IC_PALETTE),
        BACKUP("备份与恢复", Icons.IC_DOWNLOAD),
        EXPERIMENT("实验", Icons.IC_WAND_SPARKLES)
    }

    /**
     * 左栏分组的固定顺序。
     *
     * 【为什么单独一个 List 而不靠 `values()`】枚举的 `values()` 顺序由声明顺序决定，
     *   而声明顺序一旦有人为了「把实验放最后」而重排，UI 顺序就跟着变。
     *   显式列一份顺序表，把「声明顺序」与「展示顺序」解耦。
     */
    private val SETTINGS_GROUPS = listOf(
        SettingsGroup.PROFILE,
        SettingsGroup.PERMISSION,
        SettingsGroup.CHAT,
        SettingsGroup.DOLL,
        SettingsGroup.LOOK,
        SettingsGroup.BACKUP,
        SettingsGroup.EXPERIMENT
    )

    /** 左栏宽度（dp）。取 dsh-Go 的 rail/sidebar 口径：320 与手机抽屉同宽，「这样列表读起来一样」。 */
    private val SETTINGS_RAIL_W = 320

    /**
     * 双栏版设置页：左分组导航 + 右选中组内容。
     *
     * 【为什么右栏只渲染选中组，而不是把全部卡片堆在右栏滚动】
     *   方案 B（右栏滚动全量）只是把「纵向一列卡」换成「横向并排两列」，宽度并没有被利用 ——
     *   用户仍然要滚完 7 组才能找到想要的，且左右两栏同时滚动会让人失去位置感。
     *   方案 A 把「找」这件事交给左栏（一屏看完 7 个分组），右栏只负责「读/改」，
     *   这才是平板宽度的收益：**一屏内同时看到结构与内容**。
     *
     * 【为什么左栏不用 DhKit.NavItem】它是为**底部栏**设计的（72dp 宽、图标在上文字在下、
     *   选中态用 option 底 + 999 药丸）。左栏是 320dp 宽的列表，需要「图标在左、文字在右」
     *   的行式布局；硬套 NavItem 会让 320dp 里只有一个 72dp 的小方块，四周全是空白。
     *
     * 【为什么左栏自己滚动、右栏也自己滚动】两栏内容都可能超过一屏（左栏 7 组通常不会，
     *   但右栏的「备份」/「实验」卡很长）。各滚各的互不干扰，也避免出现
     *   「外层滚内层」的嵌套滚动冲突。
     */
    @Composable
    private fun SettingsTwoPane(act: MainActivity) {
        var group by remember { mutableStateOf(SettingsGroup.PROFILE) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DhTokens.colors.bg)
        ) {
            // 顶栏：与单栏版同一个 DhKit.Page 的标题口径（20sp / 600 / 返回键）。
            DhKit.TopBar(
                title = "设置",
                onBack = { com.dollhouse.app.ui.compose.PageStack.pop(act) },
                actions = { SearchAction() }
            )
            Row(modifier = Modifier.fillMaxSize()) {
                SettingsRail(
                    current = group,
                    onPick = { group = it }
                )
                // 竖分隔发丝线：两栏之间需要一条明确的边界，否则右栏内容会
                //   一直顶到左栏边缘，看不出「这是两个可独立滚动的区域」。
                Box(
                    modifier = Modifier
                        .width(DhDesign.Size.hairline)
                        .fillMaxHeight()
                        .background(DhTokens.colors.line)
                )
                // 右栏内容：`weight(1f)` 吃掉剩余宽度；底部留 pageBottom 让
                //   最后一行不被屏幕边缘贴住（与单栏版 DhKit.Page 的口径一致）。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DhDesign.Space.lg.dp)
                        .padding(top = DhDesign.Space.sm.dp, bottom = DhDesign.Space.xxl.dp)
                ) {
                    SettingsGroupBody(act, group)
                }
            }
        }
    }

    /**
     * 左栏分组导航。
     *
     * 【为什么选中态用 `option` 底 + `acc` 字】与 DhKit.NavItem 的选中口径一致
     *   （`NavItem`：`tint = if (selected) c.acc else c.sub`、底 `option`）——
     *   同一 App 里「选中」应该只有一种视觉语言。
     *
     * 【为什么补 selected 语义】读屏需要知道当前在哪一组。`Role.Tab` + `selected`
     *   的组合与 NavItem 的做法相同（见 DhKit.kt NavItem 的 P0-3 注释）。
     */
    @Composable
    private fun SettingsRail(current: SettingsGroup, onPick: (SettingsGroup) -> Unit) {
        val c = DhTokens.colors
        Column(
            modifier = Modifier
                .width(SETTINGS_RAIL_W.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(DhDesign.Space.md.dp)
        ) {
            SETTINGS_GROUPS.forEach { g ->
                val selected = g == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = DhDesign.Space.xs.dp)
                        .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                        .background(if (selected) c.option else androidx.compose.ui.graphics.Color.Transparent)
                        .semantics { this.selected = selected; role = Role.Tab }
                        .pressable { onPick(g) }
                        .padding(horizontal = DhDesign.Space.md.dp, vertical = DhDesign.Space.md.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DhKit.Icon(
                        iconRes = g.iconRes,
                        sizeDp = Design.SZ_MD,
                        color = if (selected) c.acc else c.sub
                    )
                    Spacer(Modifier.width(DhDesign.Space.sm.dp))
                    Text(
                        text = g.label,
                        color = if (selected) c.acc else c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    /**
     * 右栏：按选中分组渲染对应内容。
     *
     * 【为什么直接复用单栏版的那几个 Card 函数】它们是**无状态**的（只读 `act` 与
     *   全局状态），不依赖自己在页面里的位置。复用而不是复制，双栏与单栏看到的内容
     *   永远是同一份代码 —— 单栏版改了一行，双栏版自动跟上。这是本实现最重要的性质：
     *   如果复制一份，两边的设置项会在两周内漂移。
     */
    @Composable
    private fun SettingsGroupBody(act: MainActivity, group: SettingsGroup) {
        when (group) {
            SettingsGroup.PROFILE -> ProfileCard(act)
            SettingsGroup.PERMISSION -> PermissionRow(act)
            SettingsGroup.CHAT -> ChatSection(act, showTitle = false)
            SettingsGroup.DOLL -> DollSection(act, showTitle = false)
            SettingsGroup.LOOK -> LookSection(act, showTitle = false)
            SettingsGroup.BACKUP -> BackupCard(act)
            SettingsGroup.EXPERIMENT -> ExperimentSection(act, showTitle = false)
        }
    }

    /* ============================ 我的资料 ============================ */

    /**
     * 资料卡：头像 56 + 昵称 + 签名摘要 + 箭头，点击进「我的资料」页。
     *
     * 【为什么需要它】设计稿 §4.6 把它列为设置页的第一块。本 App 此前把「我的资料」
     *   只放在聊天列表左上角的头像抽屉里 —— 用户想改昵称/头像时第一反应是进设置，
     *   而设置页里找不到「我自己」，只能靠试。
     *
     * 【为什么头像 56】设计稿的 avatar-lg 档；它是本页唯一的「人」的元素，
     *   比行图标（20）明显大，才能在一屏设置项里被认出来。
     *
     * 【为什么读 ProfileState.tick】昵称/头像存在 SharedPreferences 里，
     *   在「我的资料」页改完返回时本卡不会自己更新；tick 是既有的重读信号。
     */
    @Composable
    private fun ProfileCard(act: MainActivity) {
        val c = DhTokens.colors
        val tick = ProfileState.tick
        val name = remember(tick) { MyProfile.name(act).ifBlank { "还没有取名字" } }
        val bio = remember(tick) { MyProfile.bio(act) }
        val bmp = remember(tick) { MyProfile.avatarBitmap(act, 128) }
        val showPlaceholder = bio.isBlank()

        DhKit.Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable { MyProfilePage.open(act) }
                    .padding(vertical = DhDesign.Space.xs.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(DhDesign.Size.avatarLg)
                        .clip(RoundedCornerShape(DhDesign.Radius.pill.dp))
                        .background(c.soft),
                    contentAlignment = Alignment.Center
                ) {
                    val avatar = bmp
                    if (avatar != null && !avatar.isRecycled) {
                        Image(
                            bitmap = avatar.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        DhKit.Icon(
                            Icons.IC_CIRCLE_USER,
                            DhDesign.Size.iconXl.value,
                            c.sub
                        )
                    }
                }
                Spacer(Modifier.width(DhDesign.Space.md.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        color = c.title,
                        style = DhTokens.type.section,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        // 没填签名时给一句用途说明，而不是留空 —— 空行会让卡片显得没长好。
                        text = if (showPlaceholder) "昵称、头像与签名" else bio,
                        modifier = Modifier.padding(top = 2.dp),
                        color = c.sub,
                        style = DhTokens.type.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                DhKit.Icon(
                    Icons.IC_CHEVRON_RIGHT,
                    DhDesign.Size.iconSm.value,
                    c.sub.copy(alpha = 0.62f)
                )
            }
        }
    }

    /* ============================ 备份 ============================ */

    /**
     * 【v0.2.8·一键全量备份】把整台手机的 Dollhouse 打成一个文件：人偶、人设、
     *   全部聊天记录、长期记忆、配置与图片。
     *
     * 【为什么只剩这一条通道】改造前这张卡外面还有两个平级「导出」入口：
     *   「导出配置」（供应商页顶栏，只导供应商与模型，给 Aivora 互通用）与
     *   「导出角色卡」（人偶编辑页，只导单只人偶的卡）。三者名字里都有「导出」，
     *   **用户无法从措辞判断哪个才是换机要用的那个** —— 而换机恰恰是备份功能唯一的
     *   真实场景。它俩能导的东西已经逐字段包含在全量存档里（见 [com.dollhouse.app.data.AllInOneArchive]），
     *   留着就是两份永远可能与全量存档分叉的平行实现，故本轮删除，
     *   只保留「导出 / 恢复」这一对，且做成**同一张卡片的上下两行**，语义自明。
     *   【注意】角色卡「导入」保留：用户仍要从网上下载酒馆卡，那不是备份场景。
     *
     * 【为什么不再写「不含图片」】v2 起封面 / 形象 / 聊天背景 / 头像 / 自建动作帧
     *   都会打包。文案说错范围与丢数据等价 —— 用户会以为图片没进包，
     *   于是另找办法搬图，或者干脆不敢用备份。
     *
     * 【不含 API Key 是**有意**的】provider 的密钥是 KeyVault 密文，与来源机 Keystore
     *   绑定，搬过去也解不开；写进包里只是白白外泄一份密文。代价是换机后必须重填，
     *   所以恢复前后两处都要明确告知（见下面弹窗与恢复摘要）。
     */
    @Composable
    private fun BackupCard(act: MainActivity) {
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        // 【为什么是三态而不是 Boolean】见 [BackupMsgKind] 的说明：
        //   「部分成功」既不能染绿（会让用户漏备份），也不能染红（文案写着"已生成"）。
        //   改造前这里是 `message.startsWith("已")` 判色，则恢复成功的摘要
        //   「恢复完成。」会显示成红色错误样式 —— 文案是会改的，判色不能依赖文案。
        var msgKind by remember { mutableStateOf(BackupMsgKind.NONE) }
        var pending by remember { mutableStateOf<org.json.JSONObject?>(null) }
        var pendingSummary by remember { mutableStateOf<List<String>>(emptyList()) }

        DhKit.Card(collapsibleTitle = "备份与恢复", titleIcon = Icons.IC_DOWNLOAD) {
            DhForm.Note(
                "一个文件装下整台手机的 Dollhouse：人偶与设定、全部聊天记录、长期记忆、" +
                    "供应商与模型、外观与开关，以及封面 / 形象 / 头像 / 自建动作帧等图片。\n" +
                    "**不含**：API 密钥（绑定本机，换机后请重填）、全局背景图、" +
                    "Live2D / 模型包（体积过大，需在新机重新导入）。"
            )

            if (message.isNotEmpty()) {
                // 【一屏一色相·2026-11】成功态不再染绿：文案已经写明「备份完成」，
                //   颜色是装饰。沿用 PARTIAL 的中性先例，**只有失败才染色**——
                //   这样设置页的色相从 3（蓝/绿/红）降到 2（蓝 + 失败红）。
                DhForm.Note(message, color = when (msgKind) {
                    BackupMsgKind.OK -> DhTokens.colors.sub
                    BackupMsgKind.PARTIAL -> DhTokens.colors.sub
                    // NONE 时 message 必为空（不会走到这里）；兜底按错误色，宁可显眼。
                    BackupMsgKind.ERR, BackupMsgKind.NONE -> DhTokens.colors.err
                })
            }

            // 【B4·接入加载态】备份是本 App 里**单次等待最长**的操作 ——
            //   下方注释原话「要遍历全部会话与图片，可能十来秒」。
            //   改造前这十来秒里，页面上唯一的信号是这一行的 `trailing = "处理中…"`
            //   （16sp 都不到的一小截灰字，且还挂在行尾）。用户很容易以为没点上。
            //
            //   【为什么是 Refreshing 而不是 FirstLoad】本页是设置列表，
            //   其余开关此刻全部可读可用 —— 只有这个动作在跑。
            //   按 MAA 硬规则 1 不许遮已有内容，故只加一行细圈（带文字说明在等什么）。
            DhLoadingState(
                state = if (busy) LoadingState.Refreshing else LoadingState.Idle,
                label = "正在打包全部数据，图片多时需要十几秒…"
            )
            DhKit.ActionRow(
                label = "一键全量备份",
                iconRes = Icons.IC_UPLOAD,
                // 【P1-e】从 desc 移到 info：说明「产物是什么形式」属可选了解；
                //   真正必读的是恢复行的「恢复前给你看会还原什么」，那条保持 desc。
                info = "生成一个文件，通过系统分享保存或直接发到新手机",
                trailing = if (busy) "处理中…" else null,
                showChevron = !busy
            ) {
                // 【重入闸门】备份要遍历全部会话与图片，可能十来秒；期间重复点击
                //   会并发组装两份包，还各写一个文件。busy 在这里是必须的，不是装饰。
                if (busy) return@ActionRow
                busy = true
                msgKind = BackupMsgKind.NONE
                Thread {
                    var f: java.io.File? = null
                    var err = ""
                    var skipped = 0
                    try {
                        // 【appVersion 传真实版本号，不传包名】改造前这里传的是
                        //   `act.packageName`（值形如 com.dollhouse.app），被当作「来源版本」
                        //   写进信封 —— 排查用户备份时这一行永远是无用信息。
                        val json = com.dollhouse.app.data.BackupService.export(act, appVersionOf(act))
                        // 【必须紧接着读】它是伴随 export 的静态计数（签名不能改，
                        //   见 AllInOneArchive.lastSkippedImageCount 的说明），
                        //   下一次 export 会覆盖它。所以同一线程内立刻取走。
                        skipped = com.dollhouse.app.data.AllInOneArchive.lastSkippedImageCount()
                        // 【v0.2.8·修 P1·主线程写盘】改造前 `write` 与 `prune` 在
                        //   `runOnUiThread { }` 里 —— 组装放在后台做对了，**写文件却又挪回主线程**。
                        //   全量存档常是 40~70 MB（图片 base64 后更大），`writeText` 要对
                        //   60 MB 的 String 做一次完整 UTF-8 编码再落盘，中低端机可达数秒，
                        //   足以触发 ANR —— 而这条是本次发布头号功能的**成功路径**，不是边界。
                        //   现在两步都留在本后台线程：`share` 才必须回主线程（要 startActivity）。
                        f = com.dollhouse.app.data.BackupFile.write(act, json.toString())
                        // 【prune 也在后台】它要 listFiles + 排序整个 cache 目录，
                        //   放在主线程同样是无谓的卡顿。
                        com.dollhouse.app.data.BackupFile.prune(act)
                    } catch (t: Throwable) {
                        err = t.message ?: "导出失败"
                    }
                    val file = f
                    act.runOnUiThread {
                        busy = false
                        if (file == null) {
                            msgKind = BackupMsgKind.ERR
                            message = if (err.isEmpty()) "写文件失败，请检查存储空间" else err
                        } else if (!com.dollhouse.app.data.BackupFile.share(act, file)) {
                            msgKind = BackupMsgKind.ERR
                            message = "没有可用的分享目标"
                        } else if (skipped > 0) {
                            // 【v0.2.8·修 P2·「已生成」配红字】改造前这里置 okMsg=false，
                            //   于是文案明明写着「备份已生成」，整段却是**红色错误色**——
                            //   色与文互相打架，用户会怀疑「到底要不要重做」。
                            //   这是"做成了但有残缺"，属 PARTIAL（中性色），不是失败。
                            msgKind = BackupMsgKind.PARTIAL
                            message = "部分完成：备份已生成，但有 " + skipped +
                                " 张图片因体积过大未打包（其余内容完整）。"
                        } else {
                            msgKind = BackupMsgKind.OK
                            message = "已生成备份文件，选择保存位置即可"
                        }
                    }
                }.start()
            }

            DhKit.ActionRow(
                label = "从备份恢复",
                iconRes = Icons.IC_DOWNLOAD,
                desc = "新版与旧版备份都能导入，恢复前先给你看会还原什么",
                // 导出在跑的时候不让选文件：两条流程共用同一组状态（busy / message），
                // 并发起来会把对方的结果覆盖掉。
                enabled = !busy,
                showChevron = !busy
            ) {
                com.dollhouse.app.PickFileActivity.setFileListener { paths, failed ->
                    com.dollhouse.app.PickFileActivity.setFileListener(null)
                    if (failed || paths.isEmpty()) {
                        // 【v0.2.8·修 P2·取消不该报错】`failed=false && paths.isEmpty()` 是
                        //   用户点了选择器又按返回 —— 什么都没出错，属于主动取消。
                        //   改造前一律置红「没有读到文件」并把上一次的恢复摘要整段顶掉，
                        //   于是那条「API Key 需要重新填写」的提醒就此消失，用户下次点发送
                        //   吃 401 时已无处可查。现在：取消**静默**，只有真失败才报。
                        if (failed) {
                            msgKind = BackupMsgKind.ERR
                            message = "没能读取选中的文件，请重试"
                        }
                        return@setFileListener
                    }
                    val f = java.io.File(paths[0])
                    // 【先判体积，再判内存】顺序不能反：一个把 2 GB 视频改名成 .json 的文件，
                    //   若先过内存闸门，报出来的是「内存不够」—— 用户会去关掉所有应用、
                    //   回旧机重导一遍备份，全都白费。「文件过大，不像是有效备份」才是真正的原因。
                    //   256 MiB 的文件读进 byte[] 就是 256 MB 常驻，所以在读出之前先挡掉。
                    if (f.length() > com.dollhouse.app.data.BackupEnvelope.MAX_BYTES) {
                        msgKind = BackupMsgKind.ERR
                        message = "文件过大（超过 " +
                            com.dollhouse.app.data.BackupEnvelope.maxLabel() + "），不像是有效备份"
                        return@setFileListener
                    }
                    // 【v0.2.8·恢复前先看内存】本工程 JSON 走 org.json，没有流式解析：
                    //   整份文本要读成一个 String（Android 的 String 恒为 UTF-16），
                    //   JSONObject 解析后其中的字符串还会各占一份，而原始 byte[] 在这一步
                    //   仍未释放 —— 三者叠加才是恢复的峰值。
                    //   实测（JVM 上以二分法压 -Xmx 求最小可行堆，样件形状与真实备份一致）：
                    //   · 图片主导的备份 5.4~6.0 倍（10/20/39/50/98 MB 五档：6.00/5.81/5.64/5.62/5.36）；
                    //   · 纯中文长文本的备份 4.1 倍。
                    //   真实备份的体积几乎都由图片占据，所以统一按 6 倍估算 ——
                    //   这是「防崩溃」用的上界，宁可偶尔多劝一次，也不要赌一次 OOM。
                    //
                    // 【为什么不能只看系统可用内存】恢复时真正撞的是**本进程的 Java 堆上限**
                    //   （无 largeHeap 时中端机常在 192~256 MB），而不是整机还剩多少 RAM。
                    //   实测一份 50 MB 的备份在 -Xmx256m 下当场 OOM，而此刻整机 availMem
                    //   仍有 3 GB —— 只比 availMem 的闸门会"放行然后闪退"，等于没拦。
                    //   所以两个上限都要看，谁小以谁为准。
                    val needMessage = memoryGateMessage(act, f.length())
                    if (needMessage != null) {
                        msgKind = BackupMsgKind.ERR
                        message = needMessage
                        return@setFileListener
                    }
                    val bytes = try {
                        f.readBytes()
                    } catch (t: Throwable) {
                        msgKind = BackupMsgKind.ERR
                        message = "读取失败：" + (t.message ?: "")
                        return@setFileListener
                    }
                    val parsed = com.dollhouse.app.data.BackupEnvelope.parse(bytes)
                    if (!parsed.ok()) {
                        msgKind = BackupMsgKind.ERR
                        // 【反向指路】用户手上刚导出过角色卡时，最容易把那张卡
                        //   拖进「从备份恢复」。只说「这不是完整备份」会让人以为
                        //   文件坏了、回头重导一遍。这里直接告诉他该去哪个入口。
                        //
                        // 【v0.2.8·修 P1·指路必须能用】改造前写的是
                        //   「人偶 → 编辑 → 提示词与角色卡 → 导入角色卡」，**漏了两层**：
                        //   编辑页默认落在「控制」页签（DollEditScreen 的 `tab = TAB_CTRL`），
                        //   用户进去根本看不到「提示词与角色卡」—— 它在本页的「角色设定」
                        //   卡片里。照着原话操作只会在四个页签里乱点。
                        //   下面按真实层级逐层写全，并点出「默认不在人格页签」这个坑。
                        message = if (looksLikePersonaCard(bytes)) {
                            "这是一张角色卡，不是全量备份。\n\n" +
                                "导入路径：「设置 → 人偶 → 管理人偶」→ 点某只人偶的编辑（铅笔）" +
                                "→ **先切到顶部的「人格」页签** → 点「角色设定」" +
                                "→ 在「提示词与角色卡」里点「导入角色卡」。\n" +
                                "（编辑页默认停在「控制」页签，所以要点一下「人格」。）"
                        } else {
                            parsed.message()
                        }
                        return@setFileListener
                    }
                    // 【自动识别版本，用户不必知道格式】v2 全量存档与 v1 旧备份都放行
                    //   （v1 的判定与兼容在 BackupEnvelope.parse 里，缺失段回落成空）。
                    //   这里只把「你手上这份是全的还是旧的」摆在弹窗第一行 ——
                    //   否则用户导入一份 v1 旧包，看到「恢复完成」就以为聊天也回来了。
                    val ver = parsed.root?.optInt("ver", 1) ?: 1
                    val lines = ArrayList<String>()
                    lines.add(
                        if (ver >= 2) {
                            "格式：全量存档 v" + ver + "（人设 / 聊天 / 图片齐全）"
                        } else {
                            "格式：旧版备份 v" + ver
                        }
                    )
                    lines.add("文件大小：" + humanSize(f.length()))
                    // 【v0.2.8·修 P2·同一件事说了两遍】`summary()` 自己在 v1 时也会输出
                    //   「这是旧版备份（v1），不含聊天记录 / 人设 / 图片」。改造前我另加了一行
                    //   「格式：旧版备份 v1（能导入，但没有聊天 / 人设 / 图片）」，
                    //   于是弹窗里同一句话以两种措辞出现两次 —— 用户会怀疑是不是两种不同的问题。
                    //   现在 v1 只留格式标识（不含受影响范围的重复描述），
                    //   受影响范围统一以 `summary()` 那一句为准（它更完整，带 ver 号）。
                    lines.addAll(com.dollhouse.app.data.BackupEnvelope.summary(parsed.root))
                    // 【去重】parse() 的 warnings 与 summary() 都会说「这是旧版备份…」，
                    //   两处并列会在弹窗里出现两遍同一句话。以 summary 的口径为准，
                    //   这里只补 summary 没提到的（如「备份没有记录来源版本」）。
                    lines.addAll(parsed.warnings.filterNot { it.contains("旧版备份") })
                    pendingSummary = lines
                    pending = parsed.root
                }
                com.dollhouse.app.PickFileActivity.startFiles(act, false)
            }
        }

        // 恢复确认：先摆概要，用户点「确认恢复」才写盘。
        val toRestore = pending
        if (toRestore != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pending = null },
                title = { androidx.compose.material3.Text("确认恢复") },
                text = {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            // 【v0.2.8·修 P2·概览可能看不全】行数会到 10~14 行
                            //   （我加的「格式」「文件大小」2 行 + summary() 最多 9 行 +
                            //   warnings），13sp/18sp 行高约 200~260dp，加上标题与按钮，
                            //   在小屏或**系统字体放大**时会被裁掉尾部且无法滚动 ——
                            //   被裁的恰恰是最需要看到的那句「API Key 需要重新填写」
                            //   （BackupEnvelope.kt 末尾）。M3 的 AlertDialog 内容区默认
                            //   不滚动，所以这里必须自己加。
                            .verticalScroll(rememberScrollState())
                    ) {
                        pendingSummary.forEach { line ->
                            androidx.compose.material3.Text(
                                text = "· " + line,
                                color = DhTokens.colors.sub,
                                fontSize = UiKit.FS_TINY.sp,
                                lineHeight = 18.sp,
                                fontFamily = DhTokens.fonts
                            )
                        }
                        // 【不再自己拼提示】上面这段逐行文案全部来自
                        //   `BackupEnvelope.summary()`（数据层契约，UI 原样显示）。
                        //   改造前这里没有概览；本次刻意**不**在 UI 里另写一套
                        //   「会还原什么 / 不含密钥 / 只新增不删除」——两套文案迟早会分叉，
                        //   而 summary() 已经把这三件事逐行说清了（含
                        //   「API Key 需要重新填写」与「这是旧版备份…」）。
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton({
                        pending = null
                        busy = true
                        Thread {
                            var result = ""
                            var ok = true
                            try {
                                result = com.dollhouse.app.data.BackupService.restore(act, toRestore)
                            } catch (t: Throwable) {
                                ok = false
                                result = "恢复失败：" + (t.message ?: "")
                            }
                            val r = result
                            val o = ok
                            act.runOnUiThread {
                                busy = false
                                // 【成功与否看异常，不看文案主体】`ok` 只在 restore()
                                //   整体抛异常时才是 false —— 那种情况一个字都没写进去，是 ERR。
                                //
                                // 【但"没抛异常"不等于"全好了"】data 层会用 `continue` 跳过
                                //   单张图片的写盘失败（存储满 / mkdirs 失败 / base64 损坏），
                                //   整次恢复仍算成功返回。此刻若一律报 OK，用户看到的就是
                                //   绿色「恢复完成」，然后自己发现封面是空白的 —— 正是本轮
                                //   要消灭的失败态。所以这里再按**摘要内容**降一级。
                                //
                                // 【为什么按摘要文字判，而不是让 restore() 返回结构化结果】
                                //   `BackupService.restore` 的契约是「一句能直接念给用户听的中文
                                //   摘要」（见该文件的 KDoc，且被 UI 与历史调用点共同依赖）。
                                //   改返回值会波及数据层契约；而摘要里那行「N 张图片写回失败」
                                //   本就是 data 层**为 UI 升级状态**而特意单独成行的
                                //   （AllInOneArchive 的注释写明「UI 也据此升级为『部分成功』」）。
                                //   用 `contains` 匹配这一句，是双方已经约定好的信号。
                                //
                                // 【稳健性】只匹配「图片写回失败」这个稳定的语义短语，
                                //   不依赖行号、不带前面的数字（那个数字会变）。
                                //   data 层若将来改措辞，最坏结果是退化成 OK（不再谎报"有失败"），
                                //   不会误报成错误 —— 这个降级方向是安全的。
                                msgKind = when {
                                    !o -> BackupMsgKind.ERR
                                    r.contains("图片写回失败") -> BackupMsgKind.PARTIAL
                                    else -> BackupMsgKind.OK
                                }
                                message = r
                                // 【恢复后必须整页对一次状态】供应商数量、主题、背景、
                                //   人偶列表、是否有常驻角色都可能被这次恢复改变；
                                //   不刷新的话用户看到的是恢复前的旧值，会以为没生效。
                                HomeUi.syncAll(act)
                                ProfileState.bump()
                            }
                        }.start()
                    }) { androidx.compose.material3.Text("确认恢复") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton({ pending = null }) {
                        androidx.compose.material3.Text("取消")
                    }
                }
            )
        }
    }

    /**
     * 当前 App 版本名，写进备份信封的 `appVersion`（只用于排查，不参与恢复决策）。
     *
     * 【为什么不写成 BuildConfig】版本号只在 build.gradle 一处维护；从 PackageManager
     *   现取与 AboutPage 的口径一致，升级时不会因为忘改某个常量而写出错误的版本标记。
     */
    private fun appVersionOf(act: MainActivity): String {
        return try {
            act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 恢复这份备份会不会把进程撑爆；会则返回**一句能直接给用户看的话**，不会返回 null。
     *
     * 【两个上限，谁小以谁为准】这是本闸门最容易被写错的地方：
     *   · `availMem` 是**整机**可用 RAM —— 它决定"系统会不会因为内存紧张来杀我"；
     *   · `maxMemory()` 是**本进程**的 Java 堆上限 —— 它决定"我这次分配会不会直接 OOM"。
     *   恢复撞的其实是后者：中端机无 largeHeap 时堆上限常在 192~256 MB，而整机
     *   可能有 3 GB 可用。实测一份 50 MB 的备份在 256 MB 堆上限下必定 OOM，
     *   此时只看 availMem 的闸门会**放行然后闪退**，用户连"备份太大"都看不到。
     *   所以这里取两者的最小值当作可用额度。
     *
     * 【堆的"已用"要扣掉】`maxMemory() - (totalMemory() - freeMemory())` 才是当前
     *   还能再分配多少。只看 maxMemory 会低估已占用的部分（App 本身 + 已加载的界面
     *   已经吃掉几十 MB）。
     *
     * 【0 的语义】查不到就当额度未知，**放行**（返回 null）—— 宁可少拦一次，
     *   也不能因为读不到内存数就把用户的恢复操作挡在门外。
     *
     * @param fileBytes 备份文件字节数（调用方在**读文件之前**就拿得到）
     */
    private fun memoryGateMessage(act: MainActivity, fileBytes: Long): String? {
        val needBytes = fileBytes * PEAK_FACTOR
        val availBytes = availableMemoryBytes(act)
        val heapLeft = heapHeadroomBytes()
        // 两个额度里取更紧的那个；查不到的（0）不参与比较。
        val limit = when {
            availBytes <= 0L -> heapLeft
            heapLeft <= 0L -> availBytes
            else -> Math.min(availBytes, heapLeft)
        }
        if (limit <= 0L || limit >= needBytes) {
            return null
        }
        val mb = 1024L * 1024L
        // 【为什么把「谁不够」分开说】两种不足的最优应对不同：
        //   整机内存不足 → 关掉其它应用就能救回来；
        //   本进程堆不够 → 关应用没用，只能减少图片后重新导出。
        //   混成一句会让用户按错误的办法白试一遍。
        // 【为什么要把 availBytes 未知（0）算作"堆这一路生效"】否则报出的是
        //   「系统当前可用内存（0 MB）不够」—— 一个查不到的数字被当成结论，纯误导。
        val heapBinding = heapLeft > 0L && (availBytes <= 0L || heapLeft <= availBytes)
        val reason = if (heapBinding) {
            "本应用可用内存（" + (heapLeft / mb) + " MB）不够"
        } else {
            "系统当前可用内存（" + (availBytes / mb) + " MB）不够"
        }
        return "内存不够：这份备份约 " + (fileBytes / mb) + " MB，" +
            "恢复预计需要 " + (needBytes / mb) + " MB，但" + reason + "。\n" +
            "请先关掉其它应用再试；若仍不行，这份备份里的图片较多，" +
            "可在旧机上重新导出一次并减少图片后再恢复（文字数据不受影响）。"
    }

    /**
     * 当前进程还能再分配多少 Java 堆字节；查不到返回 0。
     *
     * 【为什么用 Runtime 而不是 ActivityManager】见 [memoryGateMessage]：
     *   这里问的是"我这个进程的堆还剩多少"，`Runtime.getRuntime().maxMemory()`
     *   是唯一的答案来源，ActivityManager 给的是整机口径。
     */
    private fun heapHeadroomBytes(): Long {
        return try {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            val left = rt.maxMemory() - used
            if (left > 0L) left else 0L
        } catch (t: Throwable) {
            0L
        }
    }

    /**
     * 系统当前可用内存（字节）；查询失败返回 0。
     *
     * 【为什么用 availMem 而不是 totalMem】恢复备份撞的是「此刻是否装得下」这个限制，
     *   而 totalMem 是物理总量，和当前能不能分配无关。
     * 【0 的语义】调用方把 0 当作「查不到」，据此**跳过**这一路判断 ——
     *   宁可少拦一次，也不能因为查不到内存就把用户的恢复操作挡在门外。
     */
    private fun availableMemoryBytes(act: MainActivity): Long {
        return try {
            val am = act.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager ?: return 0L
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        } catch (t: Throwable) {
            0L
        }
    }

    /**
     * 文件大小的可读形式。
     *
     * 【为什么要显示它】全量存档常是几十 MB，用户看到「12.4 MB」才知道这个文件
     *   是真的带上了图片（旧版备份只有几十 KB）。这也是「是否选错了文件」的最快判据。
     */
    private fun humanSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        // 【显式 Locale.US】默认 Locale 会把小数点写成逗号（如德语 "12,4"），
        //   与同页其它数值文案（DeveloperOptionsPage / ChatUi）口径不一致。
        return if (mb >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        } else {
            (bytes / 1024).toString() + " KB"
        }
    }

    /**
     * 这份文件是不是一张角色卡（而不是全量备份）。
     *
     * 【为什么要判】全量存档与角色卡都是 `.json`，系统文件选择器里长得一模一样。
     *   选错时只说「这不是完整备份」，用户会以为文件损坏 → 回头重新导出 → 再选错。
     *   直接把正确的入口指出来，一次就能走对。
     *
     * 【为什么只查开头 4 KiB】两种格式的标识字段都在信封最前面
     *   （角色卡是 `dollhouse_card` / `chara_card`，全量存档是 `dollhouse.backup`）。
     *   角色卡本身很小，这里仍按前缀查，避免对大文件做全文扫描。
     */
    private fun looksLikePersonaCard(bytes: ByteArray): Boolean {
        val probeLen = Math.min(bytes.size, 4096)
        val head = String(bytes, 0, probeLen, Charsets.UTF_8)
        // 【三种都算】本 App 自有格式、酒馆 v2（spec=chara_card_v2）、酒馆 v1。
        return head.contains("dollhouse_card") ||
            head.contains("chara_card") ||
            head.contains("\"tavern\"")
    }

    /* ============================ 实验 ============================ */

    /**
     * 【r13】「实验」卡片（展开式）。
     *
     * 【需求原文】「设置界面有个实验的按钮不见了 恢复一下」→ 用户定案：加**第 5 张卡片**、
     *   **排在最后**、**展开式**（卡片内直接展开内容，不跳页）。
     *
     * 【r13 追加修正·展开后只放一个入口按钮】用户原话：「这个不是展示全部数据啊
     *   展开之后应该是一个按钮 点进去是一个界面 这个界面里才是这些」。
     *   ⇒ 卡片展开后**只显示一个入口行**；真正的正文（服务状态 / 安装 / 运行 / 删除 /
     *     AI 能力）在点进去之后的独立页面里。这样设置页不会被一大段技术信息淹没。
     */
    /**
     * 「实验」分组（3 行，含两个子分组：对外连接 / 人偶行为）。
     * 单栏与两栏共用这一份定义，理由见 [DollSection]。
     *
     * 【迁移时逐行核对过的两件事】
     * 1. `随机走位` 原来的 `valueTint = null`（刻意不染绿）——QzxList 的 `item` 默认就是副文本色，
     *    所以不写 `.valueTint(...)` 即等价，语义没丢。
     * 2. `睡觉模式` 原来**刻意没有值**（睡觉模式没有独立开关，见 SleepSettingsPage 注释；
     *    唯一能读的「安静时段」是同页另一件事，当它的值会误导）。这里同样不传 `value`。
     */
    @Composable
    private fun ExperimentSection(act: MainActivity, showTitle: Boolean) {
        QzxList(
            spotTitle = { com.dollhouse.app.ui.settings.SettingsSpots.find(it)?.title }
        ) {
            section("experiment.connect", title = if (showTitle) "对外连接" else null) {
                item(
                    title = "lamda 设备控制",
                    // 只报「开机自启」这一个本页读得到的状态；其余配置在 LamdaPage 内部。
                    value = if (PetPrefs.lamdaAutoStart(act)) "自启开启" else "自启关闭",
                    icon = painterResource(Icons.IC_GAMEPAD_2),
                )
                    .desc("用手机控制另一台设备")
                    .arrow()
                    .onClick { ProviderNav.openAt(act, ProviderNav.R_EXPERIMENT) }
            }
            section("experiment.doll", title = if (showTitle) "人偶行为" else null) {
                item(
                    title = "随机走位",
                    value = if (PetPrefs.wanderEnabled(act)) "已开启" else "已关闭",
                    icon = painterResource(Icons.IC_WAYPOINTS),
                )
                    .desc("人偶自己在屏幕上缓慢游走")
                    .arrow()
                    .onClick { WanderSettingsPage.open(act) }

                item(
                    title = "睡觉模式",
                    // 【刻意无值】睡觉模式没有独立开关（由快捷菜单触发），
                    //   唯一可读的「安静时段」是同页另一件事，当它的值会误导。
                    icon = painterResource(Icons.IC_MOON),
                )
                    .desc("人偶低功耗休眠，点击唤醒")
                    .arrow()
                    .onClick { SleepSettingsPage.open(act) }
            }
            // 【第三组·必须保留】原 `ExperimentCard` 有 **6 行 / 3 个子组**，
            //   我第一版只复现了前 5 行就删了旧函数 —— 这三行是**补回来的**。
            //   教训：删旧实现前必须先 grep 它内部所有 SectionLabel，不能按"以为有 3 行"来写。
            section("experiment.model", title = if (showTitle) "对话与模型" else null) {
                item(
                    title = "对话显示",
                    // 【两档开关合成一句】"流式 · 思考" 比 "已开启/已关闭" 信息量大，
                    //   用户能一眼看出**具体是哪两项**；6 字 + 分隔符不挤标题。
                    value = (if (PetPrefs.streamOutput(act)) "流式" else "非流式") + " · " +
                        (if (PetPrefs.showThinking(act)) "思考" else "无思考"),
                    icon = painterResource(Icons.IC_MESSAGE_SQUARE_HEART),
                )
                    .desc("流式输出、思考过程")
                    .arrow()
                    .onClick { ChatSettingsPage.open(act) }

                item(
                    title = "识图模型",
                    // 【未设置时给动作暗示】空串 = 跟随当前模型能力，
                    //   报"跟随当前模型"而不是"未设置"——后者听起来像缺配置，其实是默认行为。
                    value = PetPrefs.visionModel(act).ifBlank { "跟随当前模型" },
                    icon = painterResource(Icons.IC_BOT),
                )
                    .desc("用哪个模型来看图片")
                    .arrow()
                    .onClick { VisionModelPage.open(act) }

                item(
                    title = "记忆库设置",
                    // 【自动开关 + 间隔】关掉时只说"自动关闭"，因为间隔此时无意义。
                    value = if (PetPrefs.mem2Auto(act)) {
                        "自动 · " + PetPrefs.mem2IntervalMinutes(act) + " 分"
                    } else {
                        "自动关闭"
                    },
                    icon = painterResource(Icons.IC_BOOK_OPEN),
                )
                    .desc("抽取与检索策略")
                    .arrow()
                    .onClick { MemSettingsPage.open(act) }
            }
        }
    }


    /* ============================ 权限 ============================ */

    /**
     * 权限入口行（M-结构收编）。
     *
     * 【为什么不再是一整张 8 行的折叠卡】设计稿 §4.6 明确要求
     *   「Toggles live on their own sub-screen, never on the hub」——
     *   开关必须有自己的子页，设置首页只做分组导航。
     *   改造前这 8 行（4 个权限 + 3 个开关 + 1 个指令）占着首页最显眼的位置，
     *   而它们是「设完就走」的低频项，还要靠展开折叠卡才能看到。
     *
     * 【为什么保留一行摘要】导航行不该是空壳：右侧显示当前最关键的状态
     *   （有几项未授权），用户不点进去也能知道「这里有没有事要处理」。
     *   这是设计稿「trailing values 是状态摘要而非名词」的落地。
     */
    @Composable
    private fun PermissionRow(act: MainActivity) {
        // 未授权的项数：Shizuku 未授权 / 悬浮窗 / 通知 / 电池白名单，各自 0 或 1。
        val missing = listOf(
            HomeState.overlay,
            HomeState.notif,
            HomeState.battery,
            HomeState.shizuku == com.dollhouse.app.device.ShizukuBridge.S_GRANTED
        ).count { !it }
        // 【为什么用 DhGroup + DhRow 而不是 DhKit.Card + ActionRow】
        //   ActionRow 自带 soft 底 + 圆角（旧式"每行一个小卡片"），与本轮统一的
        //   "卡片内发丝线行"规范冲突；而 ui/kit 的 DhGroup/DhRow 就是那套规范本身。
        //   这也是让组件库真正被页面使用的又一处（此前 DhRow/DhGroup 库外调用为 0）。
        com.dollhouse.app.ui.kit.DhGroup {
            com.dollhouse.app.ui.kit.DhRow(
                title = "权限与保活",
                desc = "系统授权、后台存活与启停通道",
                iconRes = Icons.IC_SHIELD_CHECK,
                // 右侧是「状态摘要」而非名词（设计稿 §4.6）。
                value = if (missing == 0) "已就绪" else "$missing 项待处理",
                showChevron = true,
                onClick = { com.dollhouse.app.ui.settings.PermissionPage.open(act) }
            )
        }
    }

    /* ============================ 聊天 ============================ */

    /**
     * 「聊天」分组（**2 行**：供应商 / 查看 Token）。单栏与两栏共用这一份定义。
     *
     * 【行数核对】你消息里写的是 3 行，但实际 `ChatCard` 只有 2 行 ——
     *   第 3 行「对话显示」不在这个卡里，它在 `ExperimentCard` 的「对话与模型」子组
     *   （已在上一批迁进 `ExperimentSection`）。此处按**代码实际**为 2 行。
     *
     * 【两处 `remember` 缓存必须保留（key 仍是 Unit）】
     *   `ProviderStore.providers()` 每次会 `purgeLegacy` + 解析整份 JSON；
     *   `TokenStat.todayTotal()` 要解析最多 31 条日记录 —— 放进组合体每帧读，
     *   滚动设置页会明显卡。按「进入本页」缓存一次即可（与 `PermissionRow` 同口径）。
     *   ⚠️ 这里**不能**改成 `remember(tick)` —— 本页没有 tick，改了就每帧读。
     */
    @Composable
    private fun ChatSection(act: MainActivity, showTitle: Boolean) {
        val providerCount = remember { ProviderStore.providers(act).size }
        val todayTokens = remember { TokenStat.todayTotal(act) }
        QzxList(
            spotTitle = { com.dollhouse.app.ui.settings.SettingsSpots.find(it)?.title }
        ) {
            section("chat", title = if (showTitle) "聊天" else null) {
                item(
                    title = "供应商",
                    // 【宁可说"未配置"也不报 0】0 个供应商是"还没开始"，不是"有 0 个东西"。
                    value = if (providerCount == 0) "未配置" else providerCount.toString() + " 个",
                    icon = painterResource(Icons.IC_SERVER),
                )
                    .desc("接口地址、密钥与模型列表")
                    // 未配置时才染错色（原 `valueTint = if (providerCount == 0) err else null`）
                    .valueTint(if (providerCount == 0) com.dollhouse.app.ui.qzx.QzxColors.danger else null)
                    .arrow()
                    .onClick { ProviderNav.open(act) }

                item(
                    title = "查看 Token",
                    // 【今日 in+out，与「当日总览」同一口径】没有数据时显示 `—` 而不是 `0`：
                    //   `0` 读作"今天真的没用过"，`—` 读作"还没有任何记录"；后者不会让人以为统计坏了。
                    value = if (todayTokens <= 0L) "—" else fmtTokenShort(todayTokens),
                    icon = painterResource(Icons.IC_GAUGE),
                )
                    .desc("查看模型使用统计")
                    .arrow()
                    .onClick { TokenStat.open(act) }
            }
        }
    }

    @Composable
    private fun ChatCard(act: MainActivity) {
        // 【P1-e·入口行补当前值】「供应商」原来只有名词 + 静态 desc。
        //   【为什么用 remember 而不是直接调】`ProviderStore.providers()` 每次都会
        //   `purgeLegacy` + 解析整份 JSON —— 放进组合体每帧读，滚动设置页会明显卡。
        //   这里按「进入设置页」缓存一次。
        //   【为什么 key 是 Unit】本页每次进都是新组合（`PageStack` 压栈），
        //   进页读一次即是最新；这与既有 `PermissionRow` 的口径一致（它也是进页算一次）。
        //   若将来要「配置完返回立即更新」，把 key 换成对应信号即可。
        //
        //   【「查看 Token」这一行】原先 `TokenStat` 的读取全是 private，
        //   本轮已新增 `TokenStat.todayTotal(ctx)`（只暴露今日 in+out 一个数，
        //   与页面「当日总览」同一口径）。同样用 `remember` 缓存一次：
        //   它要解析最多 31 条日记录，滚动时每帧读纯属浪费。
        val providerCount = remember { ProviderStore.providers(act).size }
        val todayTokens = remember { TokenStat.todayTotal(act) }
        DhKit.Card(collapsibleTitle = "聊天", titleIcon = Icons.IC_MESSAGE_SQUARE_HEART) {
            DhKit.ValueRow(
                label = "供应商",
                iconRes = Icons.IC_SERVER,
                desc = "接口地址、密钥与模型列表",
                // 【值：宁可说"未配置"也不报 0】0 个供应商对用户是"还没开始"，
                //   不是"有 0 个东西"，措辞要给出下一步。
                value = if (providerCount == 0) "未配置" else providerCount.toString() + " 个",
                valueTint = if (providerCount == 0) DhTokens.colors.err else null,
                showChevron = true
            ) {
                ProviderNav.open(act)
            }
            DhKit.ValueRow(
                label = "查看 Token",
                iconRes = Icons.IC_GAUGE,
                desc = "查看模型使用统计",
                // 【值：今日 in+out】与页面「当日总览」同一口径（`TokenStat` 的
                //   `fmt(inTok + outTok)`），点进去看到的第一个大数字就是它。
                //   【为什么是"今日"而不是"累计"】入口行的用途是「今天花了多少」——
                //   累计数只会一直涨，没有"今天"那么有行动含义。
                //   【没有数据时显示 `—`】不用 "0"：0 是「今天真的没用过」，
                //   而 `—` 是「还没有任何记录」。本 App 新装时两者都成立，
                //   但 `—` 不会让用户误以为统计坏了（0 容易被读成"没在计数"）。
                value = if (todayTokens <= 0L) "—" else fmtTokenShort(todayTokens),
                showChevron = true
            ) {
                TokenStat.open(act)
            }
            // 【r13·记忆系统重构】原「自动总结 / 触发阈值」两项已删。
            //
            // 【为什么删】它们管的是旧记忆系统的**归档**：条数到阈值就把最老一批消息
            //   压成摘要、删掉原文（那套实现已随旧系统整体删除）。用户定案引入 Operit 式
            //   记忆系统后，归档这套机制整体下线 —— 新系统是「每轮自动抽取成结构化条目」，
            //   没有阈值这个概念，原文一条不删。
            //   新的全局策略（自动保存开关 / 间隔 / 提取规则 / 检索权重）统一收到
            //   「实验 → 记忆库设置」页里；对话显示相关（流式 / 思考过程）在「实验 → 对话显示」。
        }
    }

    /**
     * 把 token 数压成入口行能塞下的一小段：`1.2k` / `3.4M`。
     *
     * 【为什么放在页面侧而不是 `TokenStat`】
     *   `TokenStat` 的职责是**统计与存储**，它的 `fmt()` 是「1,234,567」那种完整千分位
     *   （页面大数字用）。入口行要的是**另一种格式**（短、带单位）。
     *   把短格式放进 `TokenStat` 会让它同时背两种展示口径；
     *   而放在这里，它只服务这一个入口行，将来别处要另一种格式也不会互相牵扯。
     *
     * 【阈值口径】
     *   · < 1000        → 原样数字（"823"）
     *   · < 1_000_000   → "x.yk"（一位小数，"12.3k"）
     *   · 其余          → "x.yM"
     *   【为什么保留一位小数】token 数是几万量级时，"12k" 与 "12.3k" 的信息量差别
     *   对用户没意义，但一位小数能让**相邻两次查看**的差异可见（"12.3k"→"13.1k"）。
     *
     * 【为什么用整数运算而不是 String.format】入口行每次进设置页算一次，
     *   但格式化仍走整数除法可避免 Locale 差异导致某些地区出现「12,3k」这种逗号小数。
     */
    private fun fmtTokenShort(n: Long): String {
        if (n < 1000L) {
            return n.toString()
        }
        if (n < 1_000_000L) {
            // 12345 → "12.3k"：先除 100 得 123，再拆成整数部分 12 与小数位 3。
            val tenths = n / 100L
            return (tenths / 10L).toString() + "." + (tenths % 10L).toString() + "k"
        }
        // 3456789 → "3.4M"：同理先除 100000 得 34，再拆成 3 与 4。
        val tenths = n / 100_000L
        return (tenths / 10L).toString() + "." + (tenths % 10L).toString() + "M"
    }

    /* ============================ 人偶 ============================ */

    /* ============================ 声明式分组（单栏 / 两栏共用的唯一一份定义） ============================ */

    /**
     * 「人偶」分组。**这是该分组唯一的一份定义** —— 单栏与宽屏两栏都调它。
     *
     * 【为什么必须抽出来】两栏是「按 `SettingsGroup` 单选一张卡渲染」的分发结构，
     *   如果单栏写一份、两栏再写一份，就必然出现「手机上是新样子、平板上还是旧样子」的漂移
     *   —— 那种问题单栏永远看不出来，只有用户拿平板才会发现。
     *
     * @param showTitle 单栏需要分组标题；两栏右栏**不需要**（左栏选中的那项就是标题），
     *                  卡内再画一遍是重复信息。
     */
    @Composable
    private fun DollSection(act: MainActivity, showTitle: Boolean) {
        val dollCount = remember(DollState.rev) { DollStore.all(act).size }
        QzxList(
            // 标题唯一真源：索引表登记过的行用表里的标题，没登记的回退到字面量。
            spotTitle = { com.dollhouse.app.ui.settings.SettingsSpots.find(it)?.title }
        ) {
            section("doll", title = if (showTitle) "人偶" else null) {
                item(
                    title = "管理人偶",
                    // 【措辞】0 只时说"还没有"，给出"这里可以建"的暗示；不报 "0 只"。
                    value = if (dollCount == 0) "还没有" else dollCount.toString() + " 只",
                    icon = painterResource(Icons.IC_LAYERS),
                )
                    .desc("封面、动作、启停、删除")
                    .arrow()
                    .onClick { DollListScreen.open(act) }
            }
        }
    }

    /**
     * 「外观」分组（3 行）。同为单栏 / 两栏唯一的一份定义，理由见 [DollSection]。
     *
     * @param showTitle 同 [DollSection]：两栏右栏传 false。
     */
    @Composable
    private fun LookSection(act: MainActivity, showTitle: Boolean) {
        QzxList(
            spotTitle = { com.dollhouse.app.ui.settings.SettingsSpots.find(it)?.title }
        ) {
            section("appearance", title = if (showTitle) "外观" else null) {
                item(
                    title = "外观设置",
                    value = when {
                        !HomeState.bgSet -> "无背景"
                        PetPrefs.bgEnabled(act) -> "有背景"
                        else -> "背景已关"
                    },
                    icon = painterResource(Icons.IC_PALETTE),
                )
                    .desc("主题 · 背景 · 聊天气泡 · 弹窗")
                    .arrow()
                    .onClick { AppearancePage.open(act) }

                item(
                    title = "当前主题",
                    value = ThemeManager.MODE_NAMES[HomeState.themeMode],
                    icon = painterResource(Icons.IC_SUN),
                )
                    // 索引表里有 `appearance.mode` → 标题以表为准
                    .spot("appearance.mode")
                    .arrow()
                    .onClick { AppearancePage.open(act) }

                item(
                    title = "全局背景",
                    value = when {
                        !HomeState.bgSet -> "未设置"
                        PetPrefs.bgEnabled(act) -> "已开启"
                        else -> "已关闭"
                    },
                    icon = painterResource(Icons.IC_IMAGE),
                )
                    // 索引表里有 `appearance.bgEnable`（"使用自定义背景"）
                    .spot("appearance.bgEnable")
                    .arrow()
                    .onClick { AppearancePage.open(act) }
            }
        }
    }


    /* ============================ 外观 ============================ */


    /* 【r11】原「记忆」卡片（MemCard）已整体移除，见本文件顶部 KDoc。 */
}