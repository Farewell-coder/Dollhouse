package com.dollhouse.app.ui.home

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.ChatSettingsPage
import com.dollhouse.app.ui.settings.AppearancePage
import com.dollhouse.app.ui.settings.MemSettingsPage
import com.dollhouse.app.ui.settings.SleepSettingsPage
import com.dollhouse.app.ui.settings.VisionModelPage
import com.dollhouse.app.ui.settings.WanderSettingsPage
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager

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
        DhKit.Card(collapsibleTitle = "实验", titleIcon = Icons.IC_WAND_SPARKLES) {
            DhForm.Note("这里收纳还没正式归入上面几类、但已经能用的功能。")
            DhKit.ActionRow(
                label = "lamda 设备控制",
                iconRes = Icons.IC_GAMEPAD_2,
                desc = "用手机控制另一台设备"
            ) {
                ProviderNav.openAt(act, ProviderNav.R_EXPERIMENT)
            }
            // 【r13·新记忆系统】全局记忆策略入口。
            //   【为什么放这里】用户定案：「多出来的全局设置放在实验卡片的下级」。
            //   记忆数据本身按人偶分库（人偶编辑页里另有入口），这里只管抽取/检索策略。
            DhKit.ActionRow(
                label = "记忆库设置",
                iconRes = Icons.IC_BOOK_OPEN,
                desc = "抽取与检索策略"
            ) {
                MemSettingsPage.open(act)
            }
            // 【r13·流式 + 思考过程】对话显示策略入口。
            //   【为什么也放这里】同样是「全局、与具体人偶无关」的设置，
            //   与记忆库设置并列，符合用户定案「多出来的全局设置放实验卡片下级」。
            DhKit.ActionRow(
                label = "对话显示",
                iconRes = Icons.IC_MESSAGE_SQUARE_HEART,
                desc = "流式输出、思考过程"
            ) {
                ChatSettingsPage.open(act)
            }
            // 【r15·识图模型】图片处理策略入口。
            //   【为什么放这里】同上：全局设置、与具体人偶无关。
            //   用户定案 3C 要求「当前模型不会看图时自动用指定识图模型」，
            //   这个「指定」的落点就是本页。
            DhKit.ActionRow(
                label = "识图模型",
                iconRes = Icons.IC_BOT,
                desc = "用哪个模型来看图片"
            ) {
                VisionModelPage.open(act)
            }
            // 诊断中心入口已迁至「关于」页最后一项。
            // 【r19·随机走位】人偶在屏幕范围内缓慢游走（每 8 秒走一步）。
            //   【为什么放实验】新功能、默认关闭，由用户决定是否启用。
            //   【功能说明】开启后人偶会自己在框内缓慢移动，像活物一样；
            //     睡觉 / 拖动 / 有气泡时自动暂停，摘窗后停止。
            DhKit.ActionRow(
                label = "随机走位",
                iconRes = Icons.IC_SPARKLES,
                desc = "人偶自己在屏幕上缓慢游走"
            ) {
                WanderSettingsPage.open(act)
            }
            // 【r19·睡觉模式】人偶进入低功耗休眠（帧率降到最低）。
            //   【为什么放实验】新功能、默认关闭，由用户决定是否启用。
            //   【功能说明】开启后人偶播 sleep 循环动作（或闭眼表情兜底），
            //     任何点击 / 拖动 / 长按都会唤醒；适合长时间不用时省电。
            DhKit.ActionRow(
                label = "睡觉模式",
                iconRes = Icons.IC_SPARKLES,
                desc = "人偶低功耗休眠，点击唤醒"
            ) {
                SleepSettingsPage.open(act)
            }
        }
    }

    /* ============================ 权限 ============================ */

    @Composable
    private fun PermissionCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "权限", titleIcon = Icons.IC_SHIELD_CHECK) {
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
        DhKit.Card(collapsibleTitle = "聊天", titleIcon = Icons.IC_MESSAGE_SQUARE_HEART) {
            DhKit.ValueRow(label = "提供商", value = "›") {
                ProviderNav.open(act)
            }
            DhKit.ValueRow(label = "查看 Token", value = "›") {
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

    /* ============================ 人偶 ============================ */

    @Composable
    private fun DollCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "人偶", titleIcon = Icons.IC_USERS) {
            // 【本轮·删重复入口】原「自定义人偶」（ValueRow，值显示 "›"）与下方
            //   「管理人偶」（ActionRow，带图标与说明）**指向同一个页面** `DollListScreen.open`，
            //   两个入口功能完全重复，用户要求保留第二个、删掉第一个。
            //   【为什么留 ActionRow 那个】它带图标、带副标题（封面、动作、启停、删除），
            //   信息量更高，且与设置页其它卡片的行样式一致（ValueRow 只有「标签 + ›」）。
            //   【为什么不连带删别的东西】`DollListScreen.open` 还有别的调用方
            //   （首页人偶页、通知入口等），所以只删这一行 UI，不碰目标页面。
            //
            // 【r14·需求 4b】「管理人偶」从人偶页底部**搬到这里**。
            //   用户定案原文：「不要了 从设置的人偶下级进」——
            //   人偶页每一行都要用来展示角色（QQ 联系人形态），
            //   在列表尾部再挂一条「管理人偶」会破坏那个信息结构。
            DhKit.ActionRow(
                label = "管理人偶",
                iconRes = Icons.IC_LAYERS,
                desc = "封面、动作、启停、删除"
            ) { DollListScreen.open(act) }
            // 【r10】全局「调整比例」已移除：比例改到「每只人偶」的编辑页（活动 → 比例大小），
            //   现场调整只作用于当前启用的那几只，放在全局设置里既不准也容易误改。
        }
    }

    /* ============================ 外观 ============================ */

    /**
     * 【r14·重构】外观卡：从「平铺一堆滑条」改为**一个入口**，正文全部搬进 [AppearancePage]。
     *
     * 【为什么搬】用户定案：外观要「进入一个界面，那几个总的选项作为顶栏，可以切换」。
     *   一张折叠卡片塞不下四个分类（原来的外观卡里已经有 4 条滑条 + 7 个值行），
     *   而且顶栏 Tab 这种形态在卡片内部做不出来。
     *
     * 【旧内容去哪了】四项滑条（背景图透明度 / 背景毛玻璃 / 卡片透明度 / 遮罩强度）
     *   与「清除背景」「字体颜色」弹窗全部原样迁进 [AppearancePage] 的对应 Tab，
     *   功能一个没删 —— 这里是**搬家**，不是删功能。
     */
    @Composable
    private fun LookCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "外观", titleIcon = Icons.IC_PALETTE) {
            // 【状态摘要】让用户不用点进去就知道当前是什么状态。
            DhKit.ActionRow(
                label = "外观设置",
                iconRes = Icons.IC_PALETTE,
                desc = "主题 · 背景 · 聊天气泡 · 弹窗",
                trailing = "›"
            ) { AppearancePage.open(act) }
            DhKit.ValueRow(
                label = "当前主题",
                value = ThemeManager.MODE_NAMES[HomeState.themeMode]
            ) { AppearancePage.open(act) }
            DhKit.ValueRow(
                label = "全局背景",
                value = when {
                    !HomeState.bgSet -> "未设置"
                    PetPrefs.bgEnabled(act) -> "已开启"
                    else -> "已关闭"
                },
                valueTint = when {
                    !HomeState.bgSet -> DhTokens.colors.err
                    PetPrefs.bgEnabled(act) -> DhTokens.colors.ok
                    else -> DhTokens.colors.sub
                }
            ) { AppearancePage.open(act) }
            DhForm.Note("点「外观设置」进去，四个分类用顶栏切换。")
        }
    }

    /* 【r11】原「记忆」卡片（MemCard）已整体移除，见本文件顶部 KDoc。 */
}