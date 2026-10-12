package com.dollhouse.app.ui.home

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.settings.AboutPage
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】首页（Compose）：标题块 + 四按钮竖排（启动 / 打开聊天 / 设置 / 关于）。
 *
 * 【为什么整页重写】原首页走「HomeScreenBuilder 搭树 → SettingsPage 切卡片 →
 *   HomeUi.apply 按 child 索引拆页」三段式；Compose 侧不需要先搭一棵再拆，
 *   直接按语义写两页即可 —— 索引 / 文本 / tag 三套旧契约一并作废。
 *
 * 【契约】对外只暴露 [build]；页面 tag 由调用方（[HomeUi.apply]）打上，路由存储不受影响。
 */
object HomeScreen {

    /** 搭出首页根视图。 */
    @JvmStatic
    fun build(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { HomeContent(act) }
    }

    @Composable
    private fun HomeContent(act: MainActivity) {
        val c = DhTokens.colors
        // 【状态驱动】首帧即按真实状态渲染；起停 / 可用性变化由 HomeUi 推刷新。
        val running = HomeState.running
        // 【r13·入口收拢】原 `chatAvailable` 已不再需要 —— 它只服务于「打开聊天」按钮，
        //   而那个按钮已移除（聊天列表是默认首页，进聊天不再需要主页入口）。
        //   `HomeState.chatAvailable` 本身保留：openChat 的守卫还会写它。
        // 【r12】一只都没启用时，主按钮不再是「启动人偶」（点了才知道不行），
        //   而是直接写「选择人偶」并把人引到自定义人偶页。
        val hasDoll = HomeState.hasDoll
        // 进页对一次真实状态（供应商 / 模型增删启停后返回立即跟上）。
        LaunchedEffect(Unit) { HomeUi.syncAll(act) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 【为什么不用 weight 做弹性留白】本页是可滚动的 Column，主轴约束为无限大；
            //   Compose 的 weight 在无限主轴下拿不到可分配空间（targetSpace=0），
            //   留白会直接塌成 0。这里改用固定间距，滚动与矮屏都不会出问题。
            Spacer(Modifier.height(72.dp))
            Text(
                text = "Dollhouse",
                color = c.acc,
                // 【r12】字号走 [Design.FS_TITLE_BRAND] 令牌，不再硬编码 40.sp。
                fontSize = Design.FS_TITLE_BRAND.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "A Home for Every Character",
                modifier = Modifier.padding(top = 6.dp),
                color = c.sub,
                fontSize = 14.sp,
                fontFamily = DhTokens.fonts
            )
            Spacer(Modifier.height(84.dp))

            // 按钮区：四按钮竖排、等宽等距（间距 14dp ← 原 buildHomePage 的 btnGap）。
            HomeButton(
                text = when {
                    running -> "关闭人偶"
                    hasDoll -> "启动人偶"
                    else -> "选择人偶"
                },
                iconRes = Icons.IC_PLAY
            ) {
                // 【乐观切换】点下即切目标态，真实状态由 HomeUi 的轮询纠偏（与 View 版语义一致）。
                if (!running && !hasDoll) {
                    // 【r12】一只都没选：先去选，别让用户点了才发现不行。
                    DollListScreen.open(act)
                } else {
                    HomeUi.togglePet(act, !running)
                }
            }
            // 【r13·入口收拢】原「打开聊天」按钮已移除。
            //   需求原文：「第一幅图打开聊天 这个按钮和他这条路径都不需要了」。
            //   理由（也符合可持续性）：聊天列表已经是**默认首页**，用户一进 App 就在聊天页；
            //   从主页再放一个「打开聊天」等于「从聊天页回到主页、再点一次回聊天页」，
            //   是纯粹的绕路。入口只留一条，路径唯一，用户不用在两种进法之间做选择。
            //   【不是删功能】聊天页本身、ChatActivity、会话数据全在；
            //   点聊天列表里的卡片即可进入（见 ChatListScreen.openConv）。
            Spacer(Modifier.height(14.dp))
            HomeButton(text = "设置", iconRes = Icons.IC_SETTINGS) {
                HomeUi.show(act, false)
            }
            Spacer(Modifier.height(14.dp))
            HomeButton(text = "关于", iconRes = Icons.IC_INFO) {
                AboutPage.open(act)
            }
            // 【r13·底栏避让】主界面底栏是悬浮的（不在布局流里），页面自己要留出高度。
            //   【为什么是固定占位而不是 padding】本页是可滚动 Column，用 Spacer 占位能让
            //   最后一张按钮滚到底时正好完整露出在底栏之上，而不是被压住半截。
            Spacer(Modifier.height(BottomNav.HEIGHT_DP.dp + 24.dp))
        }
    }

    /** 首页按钮：白底描边、全宽、左图标 + 居中文字 ← `HomeCards.mkButton` + 复合 drawable。 */
    @Composable
    private fun HomeButton(text: String, iconRes: Int, onClick: () -> Unit) {
        val c = DhTokens.colors
        val shape = RoundedCornerShape(UiKit.RADIUS_BTN.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.card)
                .border(1.dp, c.line, shape)
                .pressable(onClick)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // 【r12】18f 不在 [Design] 的图标五档内 → 归到 SZ_MD(20)。
                DhKit.Icon(iconRes = iconRes, sizeDp = Design.SZ_MD, color = c.title)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}