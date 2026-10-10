package com.dollhouse.app.ui.home

import android.view.View
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.pet.GestureActions
import com.dollhouse.app.pet.GestureCore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.MemPage
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.ThemeRefresh
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】设置页（Compose）：权限 / 聊天 / 人偶 / 外观 / 操作 / 记忆 六张卡片。
 *
 * 【为什么整页重写】原设置页是「一棵长树 → SettingsPage 按标题切折叠卡片 →
 *   HomeUi.buildSettingsPage 再拼一次」的双重整理；Compose 侧直接声明式写出六张卡，
 *   `SettingsRegistry` / `SettingsCard` / 标题匹配 / 折叠状态全部不再需要。
 *
 * 【契约】对外只暴露 [build]；页面 tag 由调用方打上。
 */
object SettingsScreen {

    /** 搭出设置页根视图。 */
    @JvmStatic
    fun build(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { SettingsContent(act) }
    }

    @Composable
    private fun SettingsContent(act: MainActivity) {
        // 进页对一次真实状态（从系统设置页返回后立即刷新权限行）。
        LaunchedEffect(Unit) { HomeUi.syncAll(act) }

        DhKit.Page(
            title = "设置",
            onBack = { HomeUi.show(act, true) }
        ) {
            PermissionCard(act)
            ChatCard(act)
            DollCard(act)
            LookCard(act)
            OpCard(act)
            MemCard(act)
        }
    }

    /* ============================ 权限 ============================ */

    @Composable
    private fun PermissionCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "权限") {
            // Shizuku：四态（已授权 / 未授权 / 服务未运行 / 未安装）。
            val sh = HomeState.shizuku
            DhKit.PermRow(
                name = "Shizuku 授权",
                state = when (sh) {
                    com.dollhouse.app.device.ShizukuBridge.S_GRANTED -> 0
                    com.dollhouse.app.device.ShizukuBridge.S_NOT_RUNNING -> 2
                    com.dollhouse.app.device.ShizukuBridge.S_NOT_INSTALLED -> 2
                    else -> 1
                }
            ) { HomeUi.shizukuClicked(act) }
            DhKit.PermRow(name = "悬浮窗权限", state = if (HomeState.overlay) 0 else 1) {
                HomeUi.requestOverlay(act)
            }
            DhKit.PermRow(name = "通知权限", state = if (HomeState.notif) 0 else 1) {
                HomeUi.requestNotif(act)
            }
            // 保活分组：电池优化白名单 + 无感保活总开关。
            DhKit.PermRow(name = "电池优化白名单", state = if (HomeState.battery) 0 else 1) {
                HomeUi.requestIgnoreBattery(act)
            }
            DhKit.SwitchRow(name = "无感保活", checked = HomeState.keepAlive) { on ->
                HomeUi.onKeepAliveChanged(on, act)
            }
            // 开关通道：磁贴 + 隐藏后台卡片 + 开关指令（三者是同一条启停通道的不同入口）。
            DhKit.SwitchRow(name = "快捷设置磁贴", checked = HomeState.tile) { on ->
                HomeUi.onTileSwitchChanged(on, act)
            }
            DhKit.SwitchRow(name = "隐藏后台卡片", checked = HomeState.hideRecents) { on ->
                HomeUi.onHideRecentsChanged(on, act)
            }
            DhKit.ValueRow(label = "开关指令", value = "复制 ›") {
                HomeUi.copyToggleLink(act)
            }
        }
    }

    /* ============================ 聊天 ============================ */

    @Composable
    private fun ChatCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "聊天") {
            DhKit.ValueRow(label = "提供商", value = "›") {
                ProviderNav.open(act)
            }
            DhKit.ValueRow(label = "查看 Token", value = "›") {
                TokenStat.open(act)
            }
        }
    }

    /* ============================ 人偶 ============================ */

    @Composable
    private fun DollCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "人偶") {
            DhForm.Note("暂无可用的更多人偶")
            DhKit.StepperRow(
                name = "调整比例",
                value = HomeState.petScale.toString() + "%",
                canMinus = HomeState.petScale > 0,
                canPlus = HomeState.petScale < 100,
                onMinus = { HomeUi.stepPetScale(act, -1) },
                onPlus = { HomeUi.stepPetScale(act, 1) }
            )
            DhForm.Note("点加减号调整，范围 0% ~ 100%，每档 10%。")
        }
    }

    /* ============================ 外观 ============================ */

    @Composable
    private fun LookCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "外观") {
            // 全局背景：整行可点，跳裁剪页（选图 → 框选 → 按框落盘）。
            DhKit.ValueRow(
                label = "全局背景",
                value = if (HomeState.bgSet) "已设置" else "未设置",
                valueTint = if (HomeState.bgSet) DhTokens.colors.ok else DhTokens.colors.err
            ) { HomeUi.pickBackground(act) }
            DhKit.ValueRow(label = "清除背景", value = "›") {
                HomeUi.clearBackground(act)
            }
            // 背景填充：四档铺法，与「透明度」「毛玻璃」三个概念互相独立。
            DhKit.ValueRow(
                label = "背景填充",
                value = PetPrefs.BG_MODES[HomeState.bgMode]
            ) { HomeUi.pickBgMode(act) }
            DhKit.ValueRow(
                label = "主题模式",
                value = ThemeManager.MODE_NAMES[HomeState.themeMode]
            ) { HomeUi.pickThemeMode(act) }
            DhKit.SwitchRow(name = "莫奈主题色", checked = HomeState.monet) { on ->
                HomeUi.onMonetChanged(on, act)
            }
            // 背景图透明度：滑条保留原生 range（accent-color 染色），与其余页面一致。
            Spacer(Modifier.height(10.dp))
            Text(
                text = "背景图透明度",
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            Spacer(Modifier.height(4.dp))
            AlphaSlider(act)
            Text(
                text = HomeUi.alphaHint(HomeState.bgAlpha),
                modifier = Modifier.padding(top = 4.dp),
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            // 背景毛玻璃：只作用「背景图本身有多糊」，与卡片透明度、遮罩强度都独立。
            Spacer(Modifier.height(10.dp))
            Text(
                text = "背景毛玻璃",
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            Spacer(Modifier.height(4.dp))
            BlurSlider(act)
            Text(
                text = HomeUi.blurHint(HomeState.bgBlur),
                modifier = Modifier.padding(top = 4.dp),
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            // 卡片透明度：只作用「卡片自身底色有多实」，与背景图透明度完全独立。
            Spacer(Modifier.height(10.dp))
            Text(
                text = "卡片透明度",
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            Spacer(Modifier.height(4.dp))
            CardAlphaSlider(act)
            Text(
                text = HomeUi.cardAlphaHint(HomeState.bgCardAlpha),
                modifier = Modifier.padding(top = 4.dp),
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            // 遮罩强度：只作用「压在背景图上的可读性压暗层」有多重。
            Spacer(Modifier.height(10.dp))
            Text(
                text = "遮罩强度",
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            Spacer(Modifier.height(4.dp))
            ScrimSlider(act)
            Text(
                text = HomeUi.scrimHint(HomeState.bgScrim),
                modifier = Modifier.padding(top = 4.dp),
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }
    /**
     * 卡片透明度滑条。
     *
     * 【为什么拖动即生效】卡片底色的合成结果（`UiKit.card()`）是「取色那一刻」抄进各控件背景里的，
     *   改偏好不会让已建好的视图自动变。所以 fromUser 时立刻走 [ThemeRefresh.applyCardAlpha]：
     *   它把已建视图上旧的卡片底整值换成新色，并 bump 一次让 Compose 侧重新取色。
     * 【为什么不用 recreate】重建会丢掉当前滚动位置与输入内容，滑条拖动过程中尤其突兀。
     */
    @Composable
    private fun CardAlphaSlider(act: MainActivity) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                UiKit.Slider(it).apply {
                    setMax(100)
                    setProgress(PetPrefs.cardAlpha(act))
                    setOnChange(object : UiKit.Slider.OnChange {
                        override fun onChanged(value: Int, fromUser: Boolean) {
                            PetPrefs.setCardAlpha(act, value)
                            HomeState.bgCardAlpha = value
                            if (fromUser) {
                                ThemeRefresh.applyCardAlpha(act, value)
                                act.notifyPetService()
                            }
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    /**
     * 遮罩强度滑条。
     *
     * 【为什么拖动即生效】遮罩色由 [com.dollhouse.app.ui.theme.GlobalBackground] 现算，
     *   但算出来之后同样被写进了已建视图的背景 LayerDrawable 里；
     *   重刷一次全局背景（[com.dollhouse.app.ui.theme.GlobalBackground.refreshAll]）
     *   会按新强度重新合成，不必重建页面。
     */
    @Composable
    private fun ScrimSlider(act: MainActivity) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                UiKit.Slider(it).apply {
                    setMax(100)
                    setProgress(PetPrefs.scrimStrength(act))
                    setOnChange(object : UiKit.Slider.OnChange {
                        override fun onChanged(value: Int, fromUser: Boolean) {
                            PetPrefs.setScrimStrength(act, value)
                            HomeState.bgScrim = value
                            if (fromUser) {
                                com.dollhouse.app.ui.theme.GlobalBackground.refreshAll()
                            }
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    /**
     * 背景毛玻璃半径滑条（0~24 dp）。
     *
     * 【为什么拖动即生效】模糊图在后台线程按半径算好后缓存，改半径必须重新算一次，
     *   所以这里调 [com.dollhouse.app.ui.theme.GlobalBackground.refreshAll]——
     *   它会重新对齐解码缓存，半径变了就重跑一次模糊。
     */
    @Composable
    private fun BlurSlider(act: MainActivity) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                UiKit.Slider(it).apply {
                    setMax(24)
                    setProgress(PetPrefs.blurRadius(act))
                    setOnChange(object : UiKit.Slider.OnChange {
                        override fun onChanged(value: Int, fromUser: Boolean) {
                            PetPrefs.setBlurRadius(act, value)
                            HomeState.bgBlur = value
                            if (fromUser) {
                                com.dollhouse.app.ui.theme.GlobalBackground.refreshAll()
                            }
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    /**
     * 透明度滑条。
     *
     * 【为什么用 AndroidView 桥接】既有 `UiKit.Slider` 是自绘 View（胶囊轨道 + 刻度点 +
     *   终点标记），Compose 的 `Slider` 做不出同一形态；而「全局 UI 一致」是硬要求，
     *   换一个长得不一样的滑条正是要避免的突兀。故保留原控件、原位桥接。
     */
    @Composable
    private fun AlphaSlider(act: MainActivity) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                UiKit.Slider(it).apply {
                    setMax(100)
                    setProgress(PetPrefs.chatBgAlpha(act))
                    setOnChange(object : UiKit.Slider.OnChange {
                        override fun onChanged(value: Int, fromUser: Boolean) {
                            PetPrefs.setChatBgAlpha(act, value)
                            HomeState.bgAlpha = value
                            // 【修·透明度不生效】原来只写盘 + 通知桌宠，已挂好的背景 drawable
                            //   从不重建，于是滑条看着在动、图却一点不变；用户拖动时同步重刷。
                            if (fromUser) {
                                act.notifyPetService()
                                com.dollhouse.app.ui.theme.GlobalBackground.refreshAll()
                            }
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    /* ============================ 操作 ============================ */

    @Composable
    private fun OpCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "操作") {
            GestureRow(act, GestureCore.G_SINGLE, "单击")
            GestureRow(act, GestureCore.G_DOUBLE, "双击")
            GestureRow(act, GestureCore.G_TRIPLE, "三击")
            GestureRow(act, GestureCore.G_LONG, "长按")
            DhKit.ValueRow(label = "暂离", value = HomeState.awaySeconds.toString() + " 秒") {
                HomeUi.pickAwaySeconds(act)
            }
        }
    }

    @Composable
    private fun GestureRow(act: MainActivity, gesture: String, name: String) {
        DhKit.ValueRow(
            label = name,
            value = GestureActions.label(HomeState.gesture(gesture))
        ) { HomeUi.pickGesture(act, gesture) }
    }

    /* ============================ 记忆 ============================ */

    @Composable
    private fun MemCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "记忆") {
            DhKit.SwitchRow(name = "自动总结", checked = HomeState.memAuto) { on ->
                PetPrefs.setMemAuto(act, on)
                HomeUi.syncAll(act)
            }
            DhKit.SwitchRow(name = "自动保存记忆", checked = HomeState.memSave) { on ->
                PetPrefs.setMemAutoSave(act, on)
                HomeUi.syncAll(act)
            }
            DhKit.SwitchRow(name = "自动精简记忆", checked = HomeState.memMerge) { on ->
                PetPrefs.setMemAutoMerge(act, on)
                HomeUi.syncAll(act)
            }
            DhKit.StepperRow(
                name = "触发阈值",
                value = PetPrefs.MEM_THRESHOLDS[HomeState.memIndex].toString() + " 条",
                canMinus = HomeState.memIndex > 0,
                canPlus = HomeState.memIndex < PetPrefs.MEM_THRESHOLDS.size - 1,
                onMinus = {
                    PetPrefs.setMemThresholdIndex(act, HomeState.memIndex - 1)
                    HomeUi.syncAll(act)
                },
                onPlus = {
                    PetPrefs.setMemThresholdIndex(act, HomeState.memIndex + 1)
                    HomeUi.syncAll(act)
                }
            )
            DhKit.ValueRow(label = "打开记忆库", value = "›") {
                MemPage.open(act)
            }
        }
    }
}