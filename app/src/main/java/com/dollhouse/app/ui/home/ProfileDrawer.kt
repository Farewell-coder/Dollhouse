package com.dollhouse.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.MyProfile
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.settings.AboutPage
import com.dollhouse.app.ui.settings.MyProfilePage
import com.dollhouse.app.ui.settings.AppearancePage
import com.dollhouse.app.ui.settings.MemSettingsPage
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】「我的资料」抽屉（r14·需求 3）。
 *
 * 【形态来源】用户给的参考图是 QQ：聊天列表**左上角的头像**点一下 → 从左侧滑出一个面板，
 *   上半部是「我」的头像 / 昵称 / 个性签名，下半部是一列功能入口。
 *
 * 【为什么是抽屉而不是整页】
 *   它承载的是「我是谁 + 几个高频入口」，属于**随时瞄一眼**的信息；
 *   做成整页会占用一次完整的进出场（还要处理返回键），比抽屉重得多。
 *   用户给的三张参考图里，QQ 用的也正是抽屉形态。
 *
 * 【内容编排（用户定案）】
 *   头像 / 昵称 / 个性签名   ← 「这几个是用户本身的 不是人偶的」
 *   ├ 我的资料              ← 二级页，编辑它们
 *   ├ 开关人偶              ← 原主页「启动 / 关闭人偶」按钮
 *   ├ 设置                  ← 原主页「设置」按钮
 *   └ 关于                  ← 原主页「关于」按钮
 *
 * 【为什么不用 ModalNavigationDrawer】
 *   本工程刻意不建 MaterialTheme（见 [DhTokens] 头注释），而该组件的外观全部来自 M3 主题；
 *   用它等于在自研配色之外再插一套默认色。这里用最朴素的「遮罩 Box + 可点关闭」实现，
 *   视觉完全走本工程令牌，零样式冲突。
 */
object ProfileDrawer {

    /** 面板宽度（dp）—— 与主流聊天软件的抽屉一致：约占屏宽 3/4，露出背后的内容以示「可关」。 */
    private const val PANEL_W = 300

    /** 头部头像边长（dp）。 */
    private const val AVATAR_DP = 66

    /**
     * 抽屉是否正处于打开状态。
     *
     * 【为什么由本对象持有】它由聊天列表页的左上角头像触发、却要覆盖整个主界面
     *   （底栏与三个 Tab 都在它下面）。状态放在触发点那一页里，就没法盖住别人。
     *   提到这里之后，任何页面都能打开它。
     *
     * 【为什么是 Compose 状态】宿主是 Compose 写的，要跟着重组。
     *   写入方（点头像 / 点遮罩）都在主线程，无需额外同步。
     */
    private val openedState = mutableStateOf(false)

    /** 当前是否打开（供宿主订阅）。 */
    val opened: Boolean
        get() = openedState.value

    /** 打开抽屉。 */
    fun open() {
        openedState.value = true
    }

    /** 关闭抽屉。 */
    fun close() {
        openedState.value = false
    }

    /** 切换（点头像时的行为）。 */
    fun toggle() {
        openedState.value = !openedState.value
    }

    /**
     * 抽屉本体。宿主（[HomeUi]）把它叠在**所有内容之上**（含底栏）。
     *
     * 【为什么宿主来调用而不是自带一层窗口】它需要同时盖住三个 Tab 与悬浮底栏 ——
     *   也就是整个 Activity 内容区。宿主正好持有那个容器，交给它摆放最直接。
     */
    @Composable
    fun Host(act: MainActivity) {
        if (!opened) {
            return
        }
        val c = DhTokens.colors
        Box(modifier = Modifier.fillMaxSize()) {
            // ---------- 遮罩：点击关闭 ----------
            // 【半透明黑】与全 App 弹窗遮罩同源（c.scrim），不另调一个颜色。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(c.scrim)
                    .pressable { close() }
            )
            // ---------- 面板 ----------
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(PANEL_W.dp)
                        .fillMaxHeight()
                        // 【不透明底】抽屉是「盖住下层」的层，必须垫实底，否则背后列表文字透上来叠成一片。
                        .background(c.sheet)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp)
                ) {
                    Spacer(Modifier.height(28.dp))
                    Header(act)
                    Spacer(Modifier.height(18.dp))
                    DhKit.Divider()
                    Spacer(Modifier.height(10.dp))
                    // ---------- 功能入口 ----------
                    // 【图标语义】每一行都配一枚**意思各不相同**的 Lucide 图标：
                    //   我的资料 → 用户头像；开关人偶 → 播放/停止；设置 → 齿轮；关于 → 信息。
                    DhKit.ActionRow(
                        label = "我的资料",
                        iconRes = Icons.IC_CIRCLE_USER,
                        desc = "头像、昵称、个性签名"
                    ) {
                        close()
                        MyProfilePage.open(act)
                    }
                    DhKit.ActionRow(
                        label = "外观",
                        iconRes = Icons.IC_PALETTE,
                        desc = "主题、背景、聊天气泡、弹窗"
                    ) {
                        close()
                        AppearancePage.open(act)
                    }
                    DhKit.ActionRow(
                        label = if (HomeState.running) "关闭人偶" else "启动人偶",
                        iconRes = if (HomeState.running) Icons.IC_SQUARE else Icons.IC_PLAY,
                        desc = "让她们出现在桌面上"
                    ) {
                        close()
                        HomeUi.togglePet(act, !HomeState.running)
                    }
                    DhKit.ActionRow(
                        label = "记忆库设置",
                        iconRes = Icons.IC_BRAIN,
                        desc = "抽取与检索策略"
                    ) {
                        close()
                        MemSettingsPage.open(act)
                    }
                    DhKit.ActionRow(
                        label = "人偶管理",
                        iconRes = Icons.IC_LAYERS,
                        desc = "编辑、启停、封面、动作"
                    ) {
                        close()
                        DollListScreen.open(act)
                    }
                    DhKit.ActionRow(
                        label = "设置",
                        iconRes = Icons.IC_SETTINGS
                    ) {
                        close()
                        SettingsScreen.open(act)
                    }
                    DhKit.ActionRow(
                        label = "关于",
                        iconRes = Icons.IC_INFO
                    ) {
                        close()
                        AboutPage.open(act)
                    }
                    Spacer(Modifier.height(28.dp))
                }
                // 【右侧空白】不铺任何东西，点击落到遮罩上 → 关闭（与 QQ 一致）。
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }

    /** 头部：头像 + 昵称 + 个性签名。 */
    @Composable
    private fun Header(act: MainActivity) {
        val c = DhTokens.colors
        // 【数据源】改动后要能刷新：用 [DollState.rev] 当重读信号不好（那是人偶的），
        //   这里用一个本页自己的 tick —— 编辑页保存后回来自增即可。
        val tick = ProfileState.tick
        val name = remember(tick) { MyProfile.name(act) }
        val bio = remember(tick) { MyProfile.bio(act) }
        val avatar = remember(tick) { MyProfile.avatarBitmap(act, 256) }

        Column(modifier = Modifier.fillMaxWidth()) {
            // 头像
            Box(
                modifier = Modifier
                    .size(AVATAR_DP.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.soft),
                contentAlignment = Alignment.Center
            ) {
                val bmp: android.graphics.Bitmap? = avatar
                if (bmp != null && !bmp.isRecycled) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // 【Cover】圆形裁切区用 Fit 会在圆内留黑边，与聊天列表头像同一口径。
                        contentScale = ContentScale.Crop
                    )
                } else {
                    DhKit.Icon(iconRes = Icons.IC_CIRCLE_USER, sizeDp = Design.SZ_XL, color = c.sub)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = name,
                color = c.title,
                fontSize = 19.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 【引号图标】让「签名」一眼可辨 —— 光一行灰字容易被当成状态文字。
                DhKit.Icon(iconRes = Icons.IC_QUOTE, sizeDp = Design.SZ_XS, color = c.line)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = bio.ifEmpty { "还没有个性签名" },
                    color = if (bio.isEmpty()) c.line else c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 【职责】抽屉头部数据的**重读信号**。
 *
 * 【为什么需要】`MyProfile` 读写的是 SharedPreferences，不是 Compose 状态 ——
 *   在「我的资料」页保存后返回，抽屉里的昵称 / 签名不会自己更新。
 *   这里用一个全局自增版本号当信号：保存方 bump 一次，读方 remember(tick) 重算。
 *
 * 【为什么不用页面重建】重建抽屉会连带整个主界面重组，代价与收益不成比例。
 */
object ProfileState {
    private val rev = mutableStateOf(0)

    /** 当前版本号；Composable 读到它即订阅变化。 */
    val tick: Int
        get() = rev.value

    /** 资料变更后调用。 */
    fun bump() {
        rev.value = rev.value + 1
    }
}