package com.dollhouse.app.ui.settings

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.home.HomeState
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.ThemeRefresh
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】外观页（r14·需求 5）：**顶栏四 Tab** + 分组卡片。
 *
 * 【形态来源】用户定案：「我要的是进入一个界面 然后那几个总的选项作为顶栏 可以切换」。
 *   即：**只有外观页**用顶栏 Tab 形态，其它设置页维持原来的折叠卡片（用户明确「我只要外观界面这样」）。
 *
 * 【四个 Tab 的划分依据】按「作用对象」分，而不是按「控件类型」分 ——
 *   同一类东西的开关放在一起，用户调的时候不用来回切：
 *   | Tab | 管什么 |
 *   |---|---|
 *   | 主题       | 全局配色（主题模式 / 莫奈取色 / 字体颜色） |
 *   | 全局背景   | App 统一背景（所有界面，含二级页） |
 *   | 聊天界面   | 聊天气泡与头像 |
 *   | 弹窗与卡片 | 弹窗 / 面板的透明度与模糊 |
 *
 * 【与「三层配置」的关系】本页管的是**全局层**与**用户侧**；
 *   人偶层的背景 / 气泡在「编辑人偶 → 外观」栏（见 `DollLookTab`），不在这里。
 *   理由：人偶层的配置必须跟着人偶走，混进本页会让用户以为「改一次影响全部」。
 *
 * 【为什么不用 Material3 TabRow】它与本工程的顶栏尺寸 / 字号 / 指示器都对不上
 *   （见 [DhKit.TopBar] 的注释）；用自绘胶囊分段控件，视觉与底栏同族。
 */
object AppearancePage {

    /** 页面 key（进页面栈用）。 */
    const val KEY = "settings:appearance"

    /** Tab 下标。 */
    private const val TAB_THEME = 0
    private const val TAB_BG = 1
    private const val TAB_CHAT = 2
    private const val TAB_DIALOG = 3

    /** 打开外观页。 */
    fun open(act: MainActivity) {
        ComposeHost.installForActivity(act)
        PageStack.push(act, KEY) { build(act) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    fun build(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @Composable
    private fun Content(act: MainActivity) {
        var tab by remember { mutableStateOf(TAB_THEME) }

        DhKit.Page(
            title = "外观",
            sub = "主题 · 背景 · 聊天气泡 · 弹窗",
            onBack = { PageStack.pop(act) },
            // 【自带滚动】四个 Tab 各自是独立滚动区（内容长度不同），
            //   外层再滚一层会导致「滚到底才切 Tab」的错位感。
            scrollable = false
        ) {
            // ---------- 顶栏 Tab ----------
            TabBar(tab) { tab = it }
            Spacer(Modifier.height(10.dp))

            // ---------- 内容 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                when (tab) {
                    TAB_THEME -> ThemeTab(act)
                    TAB_BG -> BackgroundTab(act)
                    TAB_CHAT -> ChatTab(act)
                    else -> DialogTab(act)
                }
                // 【底部留白】最后一行卡片贴着屏幕下沿会显得局促，也与其它页不一致。
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    /* ============================ 顶栏 Tab ============================ */

    /**
     * 自绘分段 Tab（胶囊 + 选中高亮 + **横向可滑动**）。
     *
     * 【r19·可滑动】用户反馈「别人都是横着的 就他是竖着的」，要求「可以滑动的 点击就是去哪一个 滑动就是往后滑」。
     *   四格标签在窄屏上可能挤不下，改成 `horizontalScroll` 后：
     *   · 点击某格 → 立即切到该 Tab；
     *   · 左右滑动 → 浏览全部 Tab（不切换内容，只浏览标签）。
     *   等宽均分改为「内容自适应宽度」，避免长标签被迫换行或缩字。
     */
    @Composable
    private fun TabBar(current: Int, onPick: (Int) -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.soft)
                .padding(4.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabCell("主题", Icons.IC_PALETTE, current == TAB_THEME, Modifier) { onPick(TAB_THEME) }
            TabCell("背景", Icons.IC_IMAGE, current == TAB_BG, Modifier) { onPick(TAB_BG) }
            TabCell("聊天", Icons.IC_MESSAGES_SQUARE, current == TAB_CHAT, Modifier) { onPick(TAB_CHAT) }
            TabCell("弹窗", Icons.IC_SQUARE_STACK, current == TAB_DIALOG, Modifier) { onPick(TAB_DIALOG) }
        }
    }

    @Composable
    private fun TabCell(
        label: String,
        iconRes: Int,
        selected: Boolean,
        modifier: Modifier,
        onClick: () -> Unit
    ) {
        val c = DhTokens.colors
        val tint = if (selected) c.acc else c.sub
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                // 【选中态】用 option（淡强调底）而不是实心强调色 ——
                //   与设置页多选项 / 底栏选中态同一套语义色，全 App 一致。
                .background(if (selected) c.option else Color.Transparent)
                .pressable(onClick)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 【图标】四格是四个不同的功能面，用四枚不同图标把差异做出来。
            DhKit.Icon(iconRes = iconRes, sizeDp = Design.SZ_SM, color = tint)
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = tint,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    /* ============================ ① 主题 ============================ */

    @Composable
    private fun ThemeTab(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "主题模式", titleIcon = Icons.IC_SUN) {
            DhKit.ValueRow(
                label = "当前模式",
                value = ThemeManager.MODE_NAMES[HomeState.themeMode]
            ) { HomeUi.pickThemeMode(act) }
            DhKit.SwitchRow(name = "莫奈主题色", checked = HomeState.monet) { on ->
                HomeUi.onMonetChanged(on, act)
            }
            // 【从壁纸取色】用户定案要保留的能力：把系统壁纸提取的主题色用起来。
            //   与「莫奈主题色」的区别：莫奈是**按当前主题色系**生成，取色是**照抄壁纸**。
            DhKit.ActionRow(
                label = "从壁纸取色",
                iconRes = Icons.IC_DROPLET,
                desc = "把系统壁纸的主色用到界面上"
            ) { HomeUi.pickMonetWall(act) }
            DhForm.Note("「从壁纸取色」需要系统允许读取壁纸；取不到时会保持当前配色不变。")
        }
        DhKit.Card(collapsibleTitle = "字体颜色", titleIcon = Icons.IC_EDIT) {
            FontColorRow(act)
        }
    }

    /* ============================ ② 全局背景 ============================ */

    @Composable
    private fun BackgroundTab(act: MainActivity) {
        val c = DhTokens.colors
        // 【重读信号】本页的滑条 / 开关改完要就地刷新显示，用一个本页 tick 即可。
        var tick by remember { mutableStateOf(0) }
        val enabled = remember(tick) { PetPrefs.bgEnabled(act) }
        val has = remember(tick) { PetPrefs.chatBackground(act).isNotEmpty() }

        DhKit.Card(collapsibleTitle = "全局背景", titleIcon = Icons.IC_IMAGE) {
            // ---------- 开关（关键：关闭时保留全部配置）----------
            DhKit.SwitchRow(name = "使用自定义背景", checked = enabled) { on ->
                PetPrefs.setBgEnabled(act, on)
                tick++
                // 【必须重刷】背景由 [GlobalBackground] 合成后抄进各视图背景里，
                //   改开关不会让已建视图自己变 —— 必须显式重刷一次。
                GlobalBackground.refreshAll()
                ThemeRefresh.bumpAll()
            }
            DhForm.Note(
                if (!has) {
                    "还没有选图片。先「更换图片」，选完会自动打开开关。"
                } else if (enabled) {
                    "全局背景已开启：所有页面（含二级页）都会铺这张图。"
                } else {
                    // 【必须加括号】Kotlin 里 `else { "a" + "b" }` 的分支体若写成
                    //   `"a"\n + "b"`，解析器会把 `+` 当成「对 if 表达式取正」而报错。
                    ("已关闭。图片与所有参数都保留着，重新打开即原样恢复；"
                        + "想彻底清空请用下面的「移除背景」。")
                }
            )

            // ---------- 换图 / 移除 ----------
            DhKit.ActionRow(
                label = "更换图片",
                iconRes = Icons.IC_IMAGE_PLUS,
                trailing = if (has) "已设置" else "未设置"
            ) { HomeUi.pickBackground(act) }
            // 【开关与移除是两个动作】用户定案：
            //   开关关闭 = 只是不显示（配置保留）；「移除背景」= 真清空。
            //   两者混成一个按钮会让用户不敢关（怕丢配置）。
            DhKit.ActionRow(
                label = "移除背景",
                iconRes = Icons.IC_TRASH,
                desc = "删掉图片并重置全部背景参数",
                enabled = has,
                trailing = if (has) "›" else null
            ) {
                PetPrefs.clearBackground(act)
                tick++
                GlobalBackground.release()
                GlobalBackground.refreshAll()
                ThemeRefresh.bumpAll()
            }

            // ---------- 铺法 ----------
            DhKit.ValueRow(
                label = "背景填充",
                value = PetPrefs.BG_MODES[HomeState.bgMode]
            ) { HomeUi.pickBgMode(act) }
        }

        // ---------- 四个滑条 ----------
        DhKit.Card(collapsibleTitle = "背景参数", titleIcon = Icons.IC_APERTURE) {
            SliderBlock(
                title = "背景图透明度",
                hint = HomeUi.alphaHint(HomeState.bgAlpha),
                max = 100,
                value = HomeState.bgAlpha,
                iconRes = Icons.IC_DROPLET
            ) { v, fromUser ->
                PetPrefs.setChatBgAlpha(act, v)
                HomeState.bgAlpha = v
                if (fromUser) {
                    act.notifyPetService()
                    GlobalBackground.refreshAll()
                }
            }
            SliderBlock(
                title = "背景毛玻璃",
                hint = HomeUi.blurHint(HomeState.bgBlur),
                max = 24,
                value = HomeState.bgBlur,
                iconRes = Icons.IC_APERTURE
            ) { v, fromUser ->
                PetPrefs.setBlurRadius(act, v)
                HomeState.bgBlur = v
                if (fromUser) {
                    GlobalBackground.refreshAll()
                }
            }
            SliderBlock(
                title = "遮罩强度",
                hint = HomeUi.scrimHint(HomeState.bgScrim),
                max = 100,
                value = HomeState.bgScrim,
                iconRes = Icons.IC_CONTRAST
            ) { v, fromUser ->
                PetPrefs.setScrimStrength(act, v)
                HomeState.bgScrim = v
                if (fromUser) {
                    GlobalBackground.refreshAll()
                }
            }
            SliderBlock(
                title = "卡片透明度",
                hint = HomeUi.cardAlphaHint(HomeState.bgCardAlpha),
                max = 100,
                value = HomeState.bgCardAlpha,
                iconRes = Icons.IC_SQUARE_STACK
            ) { v, fromUser ->
                PetPrefs.setCardAlpha(act, v)
                HomeState.bgCardAlpha = v
                if (fromUser) {
                    ThemeRefresh.applyCardAlpha(act, v)
                    act.notifyPetService()
                }
            }
            DhForm.Note("这四项互相独立：透明度管图片、毛玻璃管图片有多糊、遮罩管压暗层、卡片管卡片底。")
            Spacer(Modifier.height(2.dp))
            // 【颜色提示】滑条下方的小色块让用户一眼看到「当前主题下这些层的实际颜色」。
            Row(verticalAlignment = Alignment.CenterVertically) {
                DhKit.Icon(iconRes = Icons.IC_PALETTE, sizeDp = Design.SZ_XS, color = c.line)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "主题跟随：" + ThemeManager.MODE_NAMES[HomeState.themeMode],
                    color = c.line,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /* ============================ ③ 聊天界面 ============================ */

    @Composable
    private fun ChatTab(act: MainActivity) {
        var tick by remember { mutableStateOf(0) }
        val showAvatar = remember(tick) { PetPrefs.showAvatar(act) }
        val frost = remember(tick) { PetPrefs.bubbleUserFrost(act) }
        val width = remember(tick) { PetPrefs.bubbleWidth(act) }

        DhKit.Card(collapsibleTitle = "你（用户侧）的气泡", titleIcon = Icons.IC_MESSAGE_SQUARE) {
            DhForm.Note("「你」的气泡全局统一一套 —— 所有会话里长得一样。人偶那边可以各自不同。")
            DhKit.SwitchRow(name = "显示头像", checked = showAvatar) { on ->
                PetPrefs.setShowAvatar(act, on)
                tick++
                ThemeRefresh.bumpAll()
            }
            // 【语义说清楚】用户定案：「人偶发的就显示人偶头像 用户发的就显示用户的头像」。
            //   这里必须把那句话翻给用户看，否则「显示头像」会被理解成「一个头像管两边」。
            DhForm.Note("打开后：人偶发的消息显示**她**的头像，你发的消息显示**你**的头像。")
            DhKit.SwitchRow(name = "气泡磨砂", checked = frost) { on ->
                PetPrefs.setBubbleUserFrost(act, on)
                tick++
                ThemeRefresh.bumpAll()
            }
            DhKit.ValueRow(
                label = "气泡宽度",
                value = BUBBLE_WIDTH_NAMES[width]
            ) { HomeUi.pickBubbleWidth(act) }
        }

        // ---------- 人偶侧默认值 ----------
        // 【为什么要这一组】三层配置里「人偶层」可以各自覆盖；这里给的是**新建人偶的默认值**。
        //   用户定案：「新建人偶自动继承全局默认，想个性化再单独改」。
        DhKit.Card(collapsibleTitle = "人偶侧默认值", titleIcon = Icons.IC_USERS) {
            DhForm.Note(
                "这里设置的是**新建人偶**的初始样式。"
                    + "想改某一只的外观，去「人偶 → 编辑 → 外观」。"
            )
            DhKit.ActionRow(
                label = "逐个人偶外观",
                iconRes = Icons.IC_USERS,
                desc = "聊天背景与人偶气泡在每只人偶的编辑页里改"
            ) { com.dollhouse.app.ui.doll.DollListScreen.open(act) }
        }
    }

    /* ============================ ④ 弹窗与卡片 ============================ */

    @Composable
    private fun DialogTab(act: MainActivity) {
        val c = DhTokens.colors
        DhKit.Card(collapsibleTitle = "弹窗", titleIcon = Icons.IC_SQUARE_STACK) {
            SliderBlock(
                title = "弹窗透明度",
                hint = dialogAlphaHint(HomeState.dlgAlpha),
                max = 100,
                value = HomeState.dlgAlpha,
                iconRes = Icons.IC_SQUARE_STACK
            ) { v, fromUser ->
                PetPrefs.setDialogAlpha(act, v)
                HomeState.dlgAlpha = v
                if (fromUser) {
                    ThemeRefresh.applyDialogAlpha(act, v)
                }
            }
            SliderBlock(
                title = "弹窗模糊",
                hint = dialogBlurHint(HomeState.dlgBlur),
                max = 25,
                value = HomeState.dlgBlur,
                iconRes = Icons.IC_APERTURE
            ) { v, fromUser ->
                PetPrefs.setDialogBlur(act, v)
                HomeState.dlgBlur = v
                if (fromUser) {
                    ThemeRefresh.applyDialogBlur(act, v)
                }
            }
            // 【如实说明平台限制】这是本页唯一一个"可能没反应"的滑条，必须提前讲清楚，
            //   否则用户会当成 bug（Android 12 以下没有 Window 背景模糊这个能力）。
            if (android.os.Build.VERSION.SDK_INT < 31) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DhKit.Icon(iconRes = Icons.IC_WARNING, sizeDp = Design.SZ_XS, color = c.err)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "本机 Android 版本低，系统不支持弹窗背后的模糊，" +
                            "这一项不会有效果（弹窗本身仍然正常）。",
                        color = c.err,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
        }

        DhKit.Card(collapsibleTitle = "图标与交互", titleIcon = Icons.IC_SPARKLES) {
            DhKit.SwitchRow(
                name = "点击穿透（桌宠）",
                checked = PetPrefs.touchThrough(act)
            ) { on ->
                HomeUi.setTouchThrough(act, on)
            }
            DhForm.Note("打开后，桌面上人偶身体之外的区域不会被挡住，可以直接点到下面的图标。")
        }
    }

    /* ============================ 复用块 ============================ */

    /**
     * 「标签 + 图标 + 滑条 + 提示」的标准块。
     *
     * 【为什么收口】原先设置页里四个滑条各自把「标题 Text / 间距 / 提示 Text」抄了一遍，
     *   抄到第四个时行距已经和第一个不一致了（12 / 10 / 4 混用）。
     *   收成一个块之后，以后加滑条只需一行调用。
     */
    @Composable
    private fun SliderBlock(
        title: String,
        hint: String,
        max: Int,
        value: Int,
        iconRes: Int,
        onChange: (Int, Boolean) -> Unit
    ) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(iconRes = iconRes, sizeDp = Design.SZ_XS, color = c.sub)
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = c.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
        }
        Spacer(Modifier.height(4.dp))
        // 【桥接既有自绘滑条】`UiKit.Slider` 是胶囊轨道 + 刻度点 + 终点标记的自绘 View，
        //   Compose 的 Slider 做不出同一形态；「全局 UI 一致」是硬要求，故保留原控件。
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                UiKit.Slider(it).apply {
                    setMax(max)
                    setProgress(value)
                    setOnChange(object : UiKit.Slider.OnChange {
                        override fun onChanged(v: Int, fromUser: Boolean) {
                            onChange(v, fromUser)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = hint,
            modifier = Modifier.padding(top = 4.dp),
            color = c.sub,
            fontSize = UiKit.FS_SUB.sp,
            fontFamily = DhTokens.fonts
        )
    }

    /** 【r13 保留】字体颜色行：6 位十六进制 + 实时色块预览。 */
    @Composable
    private fun FontColorRow(act: MainActivity) {
        var open by remember { mutableStateOf(false) }
        var draft by remember { mutableStateOf("") }
        DhKit.ValueRow(
            label = "字体颜色",
            value = if (HomeState.fontColor == 0) "跟随主题" else HomeUi.hex(HomeState.fontColor)
        ) {
            draft = if (HomeState.fontColor == 0) "" else HomeUi.hex(HomeState.fontColor).substring(1)
            open = true
        }
        Text(
            text = HomeUi.fontColorHint(HomeState.fontColor),
            modifier = Modifier.padding(top = 4.dp),
            color = DhTokens.colors.sub,
            fontSize = UiKit.FS_SUB.sp,
            fontFamily = DhTokens.fonts
        )
        if (open) {
            DhForm.Alert(
                title = "字体颜色",
                onDismiss = { open = false },
                posText = "确定",
                onPos = {
                    if (draft.length == 6) {
                        HomeUi.applyFontColor(act, 0xFF000000.toInt() or draft.toLong(16).toInt())
                    }
                },
                negText = "取消"
            ) {
                DhForm.Note("填 6 位十六进制：FFFFFF = 白，000000 = 黑。固定后正文与副标题都用它。")
                DhForm.Input(
                    value = draft,
                    onValueChange = { raw ->
                        val sb = StringBuilder(6)
                        for (ch in raw.uppercase().toCharArray()) {
                            if ((ch in '0'..'9' || ch in 'A'..'F') && sb.length < 6) {
                                sb.append(ch)
                            }
                        }
                        draft = sb.toString()
                    },
                    label = "颜色值（#RRGGBB）",
                    hint = "FFFFFF",
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                                .background(fontPreview(draft))
                                .border(1.dp, DhTokens.colors.line, RoundedCornerShape(DhKit.R_ROW.dp))
                        )
                    }
                )
                if (HomeState.fontColor != 0) {
                    Row(modifier = Modifier.padding(top = 10.dp)) {
                        DhKit.OutlineChip("恢复跟随主题") {
                            HomeUi.applyFontColor(act, 0)
                            open = false
                        }
                    }
                }
            }
        }
    }

    private fun fontPreview(text: String): Color {
        if (text.isEmpty()) {
            return Color.Transparent
        }
        val padded = (text + "000000").substring(0, 6)
        return Color(0xFF000000.toInt() or padded.toLong(16).toInt())
    }

    /* ============================ 文案 ============================ */

    /** 气泡宽度档位名（下标与 [PetPrefs.bubbleWidth] 对应）。 */
    @JvmField
    val BUBBLE_WIDTH_NAMES = arrayOf("窄", "适中", "宽")

    private fun dialogAlphaHint(percent: Int): String {
        return when {
            percent >= 100 -> "完全不透明（默认）：弹窗盖住背后的一切"
            percent >= 70 -> "略微透：能隐约感到背后有内容"
            percent >= 40 -> "半透：看得见背后的轮廓"
            else -> "很透：能看清背后内容，注意可能影响弹窗文字可读性"
        }
    }

    private fun dialogBlurHint(dp: Int): String {
        if (dp <= 0) {
            return "关闭（默认）：弹窗背后不做模糊"
        }
        return "模糊 " + dp + " dp；越大越糊，最大 25"
    }
}