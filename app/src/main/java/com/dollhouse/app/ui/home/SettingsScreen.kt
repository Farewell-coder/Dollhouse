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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.MemSettingsPage
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.ThemeRefresh
import com.dollhouse.app.ui.theme.UiKit

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

    @Composable
    private fun SettingsContent(act: MainActivity) {
        // 进页对一次真实状态（从系统设置页返回后立即刷新权限行）。
        LaunchedEffect(Unit) { HomeUi.syncAll(act) }

        DhKit.Page(
            title = "设置",
            onBack = {
                // 【返回】设置页是覆盖层，退层即可露出它下面的主页 ——
                //   不再需要「切回首页」那种底座级操作。
                com.dollhouse.app.ui.compose.PageStack.pop(act)
            }
        ) {
            PermissionCard(act)
            ChatCard(act)
            DollCard(act)
            LookCard(act)
            // 【r13】「实验」卡片：收纳尚未正式归入设置卡片、但已经可用的实验性功能。
            //   用户定案：**排在最后一张**，且是**展开式**（卡片内直接展开，不跳页）——
            //   与上面四张卡片的展开方式一致。
            ExperimentCard(act)
        }
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
    @Composable
    private fun ExperimentCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "实验") {
            DhForm.Note("这里收纳还没正式归入上面几类、但已经能用的功能。")
            DhKit.ActionRow(label = "lamda 设备控制") {
                ProviderNav.openAt(act, ProviderNav.R_EXPERIMENT)
            }
            // 【r13·新记忆系统】全局记忆策略入口。
            //   【为什么放这里】用户定案：「多出来的全局设置放在实验卡片的下级」。
            //   记忆数据本身按人偶分库（人偶编辑页里另有入口），这里只管抽取/检索策略。
            DhKit.ActionRow(label = "记忆库设置") {
                MemSettingsPage.open(act)
            }
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
            // 【r13·记忆系统重构】原「自动总结 / 触发阈值」两项已删。
            //
            // 【为什么删】它们管的是旧记忆系统的**归档**：条数到阈值就把最老一批消息
            //   压成摘要、删掉原文（见已删除的 MemSummarizer）。用户定案引入 Operit 式
            //   记忆系统后，归档这套机制整体下线 —— 新系统是「每轮自动抽取成结构化条目」，
            //   没有阈值这个概念，原文一条不删。
            //   新的全局策略（自动保存开关 / 间隔 / 提取规则 / 检索权重）统一收到
            //   「实验 → 记忆库设置」页里。
        }
    }

    /* ============================ 人偶 ============================ */

    @Composable
    private fun DollCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "人偶") {
            // 人偶管理入口：人偶列表 + 启停 + 编辑都在独立页里。
            DhKit.ValueRow(label = "自定义人偶", value = "›") {
                DollListScreen.open(act)
            }
            // 【r10】全局「调整比例」已移除：比例改到「每只人偶」的编辑页（活动 → 比例大小），
            //   现场调整只作用于当前启用的那几只，放在全局设置里既不准也容易误改。
        }
    }

    /* ============================ 外观 ============================ */

    @Composable
    private fun LookCard(act: MainActivity) {
        // 【r13】字体颜色弹窗的瞬态状态（开关 + 输入草稿）。
        var fontOpen by remember { mutableStateOf(false) }
        var fontDraft by remember { mutableStateOf("") }
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
            // 【r13】自定义字体颜色：有些背景图会让主题字色看不清，这里允许固定成指定色。
            DhKit.ValueRow(
                label = "字体颜色",
                value = if (HomeState.fontColor == 0) "跟随主题" else HomeUi.hex(HomeState.fontColor)
            ) {
                fontDraft = if (HomeState.fontColor == 0) "" else HomeUi.hex(HomeState.fontColor).substring(1)
                fontOpen = true
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
            // 【r13·外观】磨砂程度：只作用「弹窗 / 底部面板这类必须盖住下层的层」的质感。
            //   与背景图透明度、卡片透明度、遮罩强度三者完全独立（用户要求「脱离原来的控制方式」）。
            Spacer(Modifier.height(10.dp))
            Text(
                text = "磨砂程度",
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            Spacer(Modifier.height(4.dp))
            FrostSlider(act)
            Text(
                text = HomeUi.frostHint(HomeState.bgFrost),
                modifier = Modifier.padding(top = 4.dp),
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            Text(
                text = HomeUi.fontColorHint(HomeState.fontColor),
                modifier = Modifier.padding(top = 4.dp),
                color = DhTokens.colors.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
        }

        // 【r13】字体颜色弹窗：6 位十六进制 + 实时色块预览。
        // 【为什么只收 6 位】用户明确要求「按照 #FFFFFF 的格式」；放开 3 位 / 8 位
        //   （带透明度）只会让「字看不见」——这个功能本要解决的问题——更容易发生。
        if (fontOpen) {
            DhForm.Alert(
                title = "字体颜色",
                onDismiss = { fontOpen = false },
                posText = "确定",
                onPos = {
                    if (fontDraft.length == 6) {
                        HomeUi.applyFontColor(act, 0xFF000000.toInt() or fontDraft.toLong(16).toInt())
                    }
                },
                negText = "取消"
            ) {
                DhForm.Note(
                    "填 6 位十六进制：FFFFFF = 白，000000 = 黑。固定后正文与副标题都用它，" +
                        "不再随主题配色变化。"
                )
                DhForm.Input(
                    value = fontDraft,
                    onValueChange = { fontDraft = filterHex(it) },
                    label = "颜色值（#RRGGBB）",
                    hint = "FFFFFF",
                    trailing = {
                        // 实时色块：不足 6 位时按已输入前缀补 0 预览。
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(fontPreview(fontDraft))
                                .border(1.dp, DhTokens.colors.line, RoundedCornerShape(6.dp))
                        )
                    }
                )
                DhForm.Note(
                    if (fontDraft.length == 6) {
                        "将固定为 #" + fontDraft.uppercase()
                    } else {
                        "还差 " + (6 - fontDraft.length) + " 位"
                    }
                )
                // 【为什么用 OutlineChip 而不是 Alert 的 negText】Alert 的负按钮只负责「关窗」，
                //   不带动作 —— 拿它当「恢复默认」会变成白点一下。这里给一个动作明确的按钮。
                if (HomeState.fontColor != 0) {
                    Row(modifier = Modifier.padding(top = 10.dp)) {
                        DhKit.OutlineChip("恢复跟随主题") {
                            HomeUi.applyFontColor(act, 0)
                            fontOpen = false
                        }
                    }
                }
            }
        }
    }

    /** 【r13】只留 0-9 / A-F（大小写皆可），最长 6 位。 */
    private fun filterHex(raw: String): String {
        val sb = StringBuilder(6)
        for (ch in raw.uppercase().toCharArray()) {
            if ((ch in '0'..'9' || ch in 'A'..'F') && sb.length < 6) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 【r13】输入不足 6 位时按前缀补 0 预览；空串给透明块（露出底下的描边）。 */
    private fun fontPreview(text: String): Color {
        if (text.isEmpty()) {
            return Color.Transparent
        }
        val padded = (text + "000000").substring(0, 6)
        return Color(0xFF000000.toInt() or padded.toLong(16).toInt())
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
     * 【r13】磨砂程度滑条（0~100）。
     *
     * 【为什么拖动即生效】弹窗 / 面板底（`UiKit.sheet()`）是在「取色那一刻」抄进各控件背景
     *   Drawable 里的；改偏好不会让已建好的界面自己变。所以 fromUser 时立刻走
     *   [ThemeRefresh.applyFrostStrength]：把已建视图上旧的弹窗底色整值换成新色，
     *   并 bump 一次让 Compose 侧重新取色 —— 不重建 Activity。
     */
    @Composable
    private fun FrostSlider(act: MainActivity) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                UiKit.Slider(it).apply {
                    setMax(100)
                    setProgress(PetPrefs.frostStrength(act))
                    setOnChange(object : UiKit.Slider.OnChange {
                        override fun onChanged(value: Int, fromUser: Boolean) {
                            PetPrefs.setFrostStrength(act, value)
                            HomeState.bgFrost = value
                            if (fromUser) {
                                ThemeRefresh.applyFrostStrength(act, value)
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

    /* 【r11】原「记忆」卡片（MemCard）已整体移除，见本文件顶部 KDoc。 */
}