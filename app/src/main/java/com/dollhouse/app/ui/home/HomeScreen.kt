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
import com.dollhouse.app.ui.settings.AboutPage
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
        val chatAvailable = HomeState.chatAvailable
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
                fontSize = 40.sp,
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
                text = if (running) "关闭人偶" else "启动人偶",
                iconRes = Icons.IC_PLAY
            ) {
                // 【乐观切换】点下即切目标态，真实状态由 HomeUi 的轮询纠偏（与 View 版语义一致）。
                HomeUi.togglePet(act, !running)
            }
            if (chatAvailable) {
                Spacer(Modifier.height(14.dp))
                HomeButton(text = "打开聊天", iconRes = Icons.IC_MESSAGE_CIRCLE) {
                    HomeUi.openChat(act)
                }
            }
            Spacer(Modifier.height(14.dp))
            HomeButton(text = "设置", iconRes = Icons.IC_SETTINGS) {
                HomeUi.show(act, false)
            }
            Spacer(Modifier.height(14.dp))
            HomeButton(text = "关于", iconRes = Icons.IC_INFO) {
                AboutPage.open(act)
            }
            Spacer(Modifier.height(24.dp))
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
                DhKit.Icon(iconRes = iconRes, sizeDp = 18f, color = c.title)
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