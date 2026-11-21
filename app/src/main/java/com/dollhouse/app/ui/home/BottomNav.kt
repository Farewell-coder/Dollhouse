package com.dollhouse.app.ui.home

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.compose.DhAnim
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】主界面底部的**三 Tab 悬浮导航栏**（聊天 / 人偶 / 主页）。
 *
 * 【形态为什么是「悬浮胶囊」而不是通栏底栏】
 *   本工程自 r13 起已把「底栏 = 半透明胶囊」定为统一口径
 *   （见 [com.dollhouse.app.ui.compose.DhKit.NavBar] 与 `GlassCapsule`，两处读同一组令牌）。
 *   主界面底栏跟随同一套令牌，好处是：
 *   ① 视觉上全 App 的底栏是同一个东西，切页不跳；
 *   ② 胶囊不占满整行，左右留出的空隙正好露出背景图，首页 / 聊天页的壁纸连续不断开。
 *
 * 【为什么不用 Material3 的 NavigationBar】
 *   本工程刻意**不建 MaterialTheme**（见 [DhTokens] 头注释：主题由自研 UiKit 驱动，
 *   建 M3 主题会引入第二套配色真源，深浅 / 莫奈切换时两边必然分叉）。
 *   `NavigationBar` 的底色 / 指示器全部取自 M3 主题，用它会直接落回默认紫。
 *   这里用与本工程其它底栏完全同构的自绘胶囊，零新增依赖、零样式冲突。
 *
 * 【未读徽标】聊天 Tab 上挂一个小红点数字：数据来自 [ChatBadge.unread]，
 *   由聊天列表页写入、发消息后刷新。它是**跨页面通信**的最小载体，
 *   避免「底栏去轮询数据库」这种反向依赖。
 *
 * 【安全区】整条底栏 `navigationBarsPadding()`：手势导航条 / 三键导航栏的高度
 *   在 ColorOS / OneUI / HyperOS 上各不相同，交给系统 insets 处理，
 *   比自己判断「有没有手势条」稳。
 */
object BottomNav {

    /**
     * 底栏整体高度（不含系统导航栏留白）—— 页面内容要按它避让，故对外公开。
     *
     * 【M3 为什么从 62 改成 64】设计稿 §2.2 把 bottom nav 定为 64dp，与顶栏 56dp 组成
     *   「56 + 64」的固定节奏。62 这个值既不来自任何刻度，也让「内容区可用高度」
     *   在不同 Tab 之间差 2dp —— 用户能感觉到列表底部留白有点不一样。
     */
    const val HEIGHT_DP = 64

    /**
     * 底栏本体。
     *
     * @param act    宿主（用来读 Context 切页；Compose 里拿不到 Activity 时传 null 也能渲染）。
     * @param onPick 选中回调（除了切页，宿主还要做「回退到栈底」等收尾）。
     */
    @Composable
    fun Bar(act: Activity?, onPick: (Int) -> Unit) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = DhDesign.Space.sm.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(DhDesign.Radius.pill.dp))
                    // 【与全 App 底栏同源】半透明主题底 + 描边，见 UiKit.capsuleFill()（`ui/theme/UiKit.kt`）。
                    .background(c.capsule)
                    .border(1.dp, c.stroke, RoundedCornerShape(DhDesign.Radius.pill.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp), // 【不收敛·D4】与 999 圆角+1dp 描边配套，改动会改变胶囊总高
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (tab in AppNav.ORDER) {
                    TabItem(
                        tab = tab,
                        selected = AppNav.current == tab,
                        badge = if (tab == AppNav.TAB_CHAT) ChatBadge.unread else 0
                    ) {
                        // 【同步状态】先落状态（底栏高亮立刻跟手），再让宿主做页面切换与收尾。
                        AppNav.select(act, tab)
                        onPick(tab)
                    }
                }
            }
        }
    }

    /** 单个 Tab：图标 + 文字竖排，选中时图标与文字一起换成强调色并轻微放大。 */
    @Composable
    private fun TabItem(tab: Int, selected: Boolean, badge: Int, onClick: () -> Unit) {
        val c = DhTokens.colors
        // 【M3·单一强调色】选中态只用 accent 图标 + 加粗文字 + 弱底，
        //   不再给整块 `option` 实心底 —— 那会在底栏里再出现一块「第二个高亮色」，
        //   与设计稿「一个强调色」冲突，也让底栏比页面内容更抢眼。
        val tint = if (selected) c.acc else c.sub
        // 【轻放大】选中态图标放大到 1.08 —— 幅度刻意压得很小：
        //   底栏是常驻元素，动效过大在深色背景下会显得晃眼。
        val scale by animateFloatAsState(
            targetValue = if (selected) 1.06f else 1.0f,
            animationSpec = DhAnim.TAP_SCALE,
            label = "tabScale"
        )
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(DhDesign.Radius.pill.dp))
                .background(
                    if (selected) {
                        c.capsuleSel
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    }
                )
                /*
                 * 【P0-3·无障碍：补 selected / Tab 语义】
                 *   原来 `selected` 只参与视觉（`tint` 换色 / `capsuleSel` 弱底 /
                 *   图标 1.06 放大 / 文字加粗），**完全没有进语义树** ——
                 *   读屏把这三个 Tab 念成三个普通按钮，用户**不知道自己当前在哪一页**。
                 *   底栏是常驻导航，这是「导航基础信息缺失」，不是打磨项。
                 *
                 *   【为什么用 `selected` 属性而不是把「已选中」拼进名字】
                 *     `SemanticsPropertyReceiver.selected` 是**状态属性**：读屏念
                 *     「已选中 / 未选中」，TalkBack 还会在选中项上给提示音。
                 *     若拼进 contentDescription，它就成了名字的一部分 ——
                 *     每读一次都念，而且状态变了名字不跟着变（两者会脱钩，自相矛盾）。
                 *
                 *   【为什么配 `role = Role.Tab`】Compose 没有 `Role.TabButton`；
                 *     用 `Role.Tab` 让读屏把它认作标签页、归入 tab 组，
                 *     从而支持 TalkBack 的「切换标签」手势浏览。
                 *
                 *   【为什么不用 `selectable()`】它会自动带 selected + Role.Tab，写法更规范，
                 *     但它**会替换掉 `pressable` 的 0.98 按压反馈** —— 本工程的手感统一
                 *     靠 pressable（`DhKit.pressable`），换掉会让底栏点起来"发干"。
                 *     所以保留 pressable、手动补语义，视觉与手感一字不变。
                 *
                 *   【⚠️ 为什么必须 mergeDescendants = true —— 这一条与 DhKit.NavItem 不同】
                 *     本 Tab 内部有**三个**子语义节点：`Text`（Tab 名）、`Badge` 里的
                 *     `Text`（未读数），以及可能存在的图标图形。若只给父节点加
                 *     `contentDescription` 而不合并，Compose **不会**自动把子节点并进来
                 *     （合并只在 `mergeDescendants = true` 时发生）⇒ 读屏会把同一个 Tab
                 *     拆成「聊天」「3」「聊天，标签页，已选中」**三个可聚焦节点**，
                 *     用户要划三次才能过一个 Tab，且第三次才是完整的。
                 *     `Badge` 那个裸数字尤其糟：只念「3」，既不说是什么也不说属于谁。
                 *     合并之后整条 Tab 念一次：「聊天，3，标签页，已选中」（数字来自 Badge，
                 *     名字与状态来自本节点），信息完整且只需一次聚焦。
                 *
                 *   【理解代价】合并会让子 `Text` 不再单独可聚焦 —— 但底栏 Tab 本来就是
                 *     「一个可点整体」，子文字没有独立交互语义，这是正确的粒度。
                 *
                 *   【位置】语义排在 `.pressable` **之前**：它描述的是「这个可点区域是什么」
                 *     这个节点属性，与内部如何处理手势无关（与 `DhKit.IconButton` 一致）。
                 */
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    role = Role.Tab
                    // 名字：图标是装饰（无 contentDescription），名字必须显式给，
                    //   否则合并后只剩 Badge 的裸数字。
                    contentDescription = labelOf(tab)
                }
                .pressable(onClick)
                .padding(horizontal = 18.dp, vertical = 6.dp), // 【不收敛·D3】决定单项命中区与底栏 64dp 口径
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(Design.SZ_MD.dp), contentAlignment = Alignment.Center) {
                DhKit.Icon(
                    iconRes = iconOf(tab),
                    sizeDp = Design.SZ_MD,
                    color = tint,
                    modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
                )
                // 徽标挂在图标右上角，不占布局尺寸（用 offset 叠出去）。
                if (badge > 0) {
                    Badge(count = badge, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = labelOf(tab),
                color = tint,
                // 【M3·层级】底栏文字走 label 档（11sp / 600）。
                //   改造前用 FS_TINY=13sp 且选中加粗到 Bold —— 底栏文字和正文一样大，
                //   三个 Tab 的标签比页面标题还显眼，视觉重量压过了内容。
                fontSize = DhTokens.type.label.fontSize,
                lineHeight = 16.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
                letterSpacing = 0.44.sp,
                maxLines = 1
            )
        }
    }

    /** 未读小圆点：1~99 显示数字，超出显示 `99+`。 */
    @Composable
    private fun Badge(count: Int, modifier: Modifier = Modifier) {
        val c = DhTokens.colors
        val text = if (count > 99) "99+" else count.toString()
        Box(
            modifier = modifier
                .graphicsLayer { translationX = 6f; translationY = -6f }
                .clip(RoundedCornerShape(DhDesign.Radius.pill.dp))
                .background(c.err)
                .padding(horizontal = DhDesign.Space.xs.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                // 同 ChatListScreen：白字压浅红底在暗色档只有 2.48:1，改用派生色。
                color = DhTokens.palette.onErr,
                fontSize = DhTokens.type.label.fontSize, lineHeight = DhTokens.type.label.lineHeight,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Normal,
                // 【等宽数字】未读数会 1 → 10 → 99 地变，比例数字的「1」比「9」窄，
                //   每加一位徽标就横向抖一下。等宽（tnum）让所有数字同宽，宽度只随位数变一次。
                //   【必须走 style】`fontFeatureSettings` 不是 Text 的参数，是 TextStyle 的属性。
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
            )
        }
    }

    /** Tab 的图标（全部取自 Lucide，见 [Icons]）。 */
    private fun iconOf(tab: Int): Int {
        return when (tab) {
            AppNav.TAB_CHAT -> Icons.IC_MESSAGE_SQUARE
            AppNav.TAB_CONTACT -> Icons.IC_USERS
            // 【r14·改名】第三项由「主页」变「说说」，图标同步从「房子」换成「喇叭」——
            //   房子指「首页」，而这一页现在是「动态 / 说说」，语义完全变了。
            //   继续用房子会让用户以为点进去是主页（那个页面已经不存在了）。
            else -> Icons.IC_MEGAPHONE
        }
    }

    /** Tab 的展示名。 */
    fun labelOf(tab: Int): String {
        return when (tab) {
            AppNav.TAB_CHAT -> "聊天"
            // 【r13·改名】原「联系人」→「人偶」。
            //   需求原文：「联系人按钮改成 人偶 下面的也改掉了 我要换描述了 不要叫联系人了」。
            //   【为什么这个改名是对的】本 App 没有通讯录（不申请 READ_CONTACTS），
            //   这一页列的自始至终都是**用户自己创建的人偶**。
            //   叫「联系人」是借喻（借通讯录的交互形态），但它同时带来一个误导：
            //   用户会以为这里能看到手机通讯录里的真人。「人偶」才是这一页的真名。
            AppNav.TAB_CONTACT -> "人偶"
            // 【r14·改名】「主页」→「说说」。
            //   需求原文：「那个底边栏 变成说说 然后那个界面暂时 不可进入 那个说说 标注一下未开放就好」。
            //   原主页的三个按钮已搬进「我的资料」抽屉（见 ProfileDrawer），
            //   这一格现在只承载「说说」这个尚未开放的功能位。
            else -> "说说"
        }
    }
}

/**
 * 【职责】底栏未读徽标的**跨页面通信载体**。
 *
 * 【为什么单独一个对象】底栏在主界面底座里常驻，而「未读总数」来自聊天列表页的数据聚合
 *   （[com.dollhouse.app.ui.chat.ChatListData]）。让底栏自己去读会话清单会形成
 *   「底座依赖业务模块」的反向依赖，且每次重组都要读盘。
 *   改成：**谁算出来谁写进来**（聊天列表页算完写一次），底栏只读这个数。
 *
 * 【线程】只在主线程读写（Compose 状态）。
 */
object ChatBadge {
    /** 内部状态：全部会话的未读总和。 */
    private val _unread = androidx.compose.runtime.mutableStateOf(0)

    /**
     * 全部会话的未读总和；0 = 不显示徽标。
     *
     * 【为什么手写 getter 而不是属性委托】`by mutableStateOf(0)` 需要导入
     *   `getValue` / `setValue` 两个扩展函数；本对象是**跨包被读**的公开 API，
     *   委托写法一旦漏导入就是编译错（本项目已踩过一次）。
     *   这里显式暴露成只读 getter：读方拿到的是快照值，且 Compose 依然会订阅
     *   （`mutableStateOf` 的读操作在组合作用域里照样被记录为依赖）。
     */
    val unread: Int
        get() = _unread.value

    /** 写入未读总数（聊天列表页算完后调用；负数按 0 处理）。 */
    fun set(n: Int) {
        _unread.value = if (n < 0) 0 else n
    }
}
