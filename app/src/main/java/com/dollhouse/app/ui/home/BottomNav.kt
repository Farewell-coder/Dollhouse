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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

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

    /** 底栏整体高度（不含系统导航栏留白）—— 页面内容要按它避让，故对外公开。 */
    const val HEIGHT_DP = 62

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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    // 【与全 App 底栏同源】半透明主题底 + 描边，见 UiKit.capsuleFill()。
                    .background(c.capsule)
                    .border(1.dp, c.stroke, RoundedCornerShape(999.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
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
        val tint = if (selected) c.acc else c.sub
        // 【轻放大】选中态图标放大到 1.08 —— 幅度刻意压得很小：
        //   底栏是常驻元素，动效过大在深色背景下会显得晃眼。
        val scale by animateFloatAsState(if (selected) 1.08f else 1.0f, label = "tabScale")
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (selected) c.option else androidx.compose.ui.graphics.Color.Transparent)
                .pressable(onClick)
                .padding(horizontal = 18.dp, vertical = 6.dp),
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
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
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
                .clip(RoundedCornerShape(999.dp))
                .background(c.err)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 9.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
        }
    }

    /** Tab 的图标（全部取自 Lucide，见 [Icons]）。 */
    private fun iconOf(tab: Int): Int {
        return when (tab) {
            AppNav.TAB_CHAT -> Icons.IC_MESSAGE_SQUARE
            AppNav.TAB_CONTACT -> Icons.IC_USERS
            else -> Icons.IC_HOUSE
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
            else -> "主页"
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
