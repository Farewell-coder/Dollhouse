package com.dollhouse.app.ui.doll

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.pet.DollHomeOverlay
import com.dollhouse.app.pet.DollPreviewOverlay
import com.dollhouse.app.pet.DollRangeOverlay
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.home.HomeState
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.settings.MemPage
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】人偶编辑页：底部胶囊导航（控制 / 活动 / 人格）+ 三页内容。
 *
 * 【r10 的核心变化】本页原先只是「三页签空壳」——只有名称输入框和一个占位文字。
 *   r10 把它变成「这只人偶的全部独立设置」的唯一入口：
 *   手势响应 / 暂离 / 比例 / 初始位置 / 屏幕范围 / 回归时间 / 交谈内容 / 性格内容，
 *   全部按 dollId 分开存（见 [DollProfile]），本页只负责读写与展示。
 *
 * 【为什么不再有「保存」按钮】页内的长横条按钮点开即写盘（手势 / 暂离 / 比例 / 初始位置 /
 *   屏幕范围 / 回归时间 / 交谈 / 性格都是即时落盘），不存在「改了没保存」的窗口。
 *
 * 【r11 起不支持改名】名称输入框已删除，人偶名统一由列表页管理；顶栏保留的「保存」
 *   退化为「完成编辑」，只负责广播变更并返回，不再承载任何写盘。
 *   顶栏同时新增「预览」（保存左边）：点开即进入「初始位置」页 —— 人偶虚化铺底 + 中心点。
 *
 * 【子页为什么走 PageStack 而不是弹窗】规格要求「点进去是新整页」。弹窗在深色主题下要另配
 *   一套配色与返回键语义，而 PageStack 已有统一的进出场与返回处理，直接用更省也更一致。
 */
object DollEditScreen {
    /** 页面 key 前缀：与列表页共用同一条全 App 页面栈。 */
    const val PREFIX = "doll:edit:"

    /** 页签下标。 */
    private const val TAB_CTRL = 0
    private const val TAB_ACT = 1
    private const val TAB_PERSONA = 2

    /**
     * 打开编辑页。
     *
     * 【r12 定案 Q29】先把正在跑的桌宠全关掉再进编辑页。
     *   【为什么】本轮新增了「预览」（真挂一只人偶到桌面）与「初始位置 / 屏幕范围」两个
     *   全屏悬浮窗。若桌宠还在跑，桌面上的人偶会和预览重叠、也会挡住虚线框的拖动，
     *   用户根本分不清看到的是哪一只。先进来清场，编辑体验才干净。
     *   【代价】退出编辑页不会自动把桌宠拉回来 —— 由用户在首页自己点「启动人偶」，
     *   符合「用户主动控制」的既有约定（与 PetPrefs.userStopped 的语义一致）。
     */
    @JvmStatic
    fun open(act: MainActivity, id: String) {
        closeRunningPet(act)
        // 【r13 用户定案】「只要在编辑界面 就无法打开了外面的人偶 只能通过预览打开当前卡片的人偶」：
        //   置一个「正在编辑哪只」的标记，编辑期间一切启动桌宠的入口都被拦下
        //   （首页按钮 + PetService 双保险）。
        PetPrefs.setEditingDoll(act, id)
        // 【r12】注册「离开本页」的清理：预览是独立的系统悬浮窗，不属于页面视图树，
        //   页面被摘掉时它不会跟着消失。不注册的话，用户按返回键离开后
        //   人偶会一直留在桌面上（原来只有「再点一次预览按钮」一条路能关）。
        // 【r13】离开时同时：① 清编辑标记（桌宠恢复可启动）② 广播列表刷新。
        //   【为什么广播要挪到这里】顶栏的「保存」按钮已删（本页是改了即存，
        //   不存在待提交项）。列表层 View 常驻页面栈，本页 pop 只是把它摘掉，
        //   列表组合不会重建 —— 不在离开时报一次，返回后列表仍是旧名字 / 旧封面。
        PageStack.onLeave(PREFIX + id) {
            DollPreviewOverlay.hide()
            PetPrefs.setEditingDoll(act, null)
            DollState.bump()
        }
        PageStack.push(act, PREFIX + id) { buildPage(act, id) }
    }

    /** 关掉正在运行的桌宠（幂等；没在跑就什么都不做）。 */
    @JvmStatic
    fun closeRunningPet(act: MainActivity) {
        try {
            if (com.dollhouse.app.pet.PetToggle.isRunning(act)) {
                com.dollhouse.app.pet.PetToggle.stop(act)
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity, id: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, id) }
    }

    @Composable
    private fun Content(act: MainActivity, id: String) {
        val original = DollStore.byId(act, id) ?: return
        var tab by remember(id) { mutableStateOf(TAB_CTRL) }
        val c = DhTokens.colors

        DhKit.Page(
            title = "编辑人偶",
            sub = original.name,
            onBack = { PageStack.pop(act) },
            scrollable = false,
            actions = {
                // 【r13 预览 = 这只人偶的独立小开关】用户定案：
                //   「那个预览也相当于一个小开关 但是只会开启那个卡片人偶 不会开启全部的人偶；
                //     点的第二下也算人偶消失 就是这个单个人偶的消失 也会触发回到位置」。
                //   ⇒ 第 1 下只让**这一只**出现（不启动桌宠服务、不动别的人偶）；
                //     第 2 下按「人偶消失」判据给它打点，下次再开就走回归判定回初始位置。
                //   图标两态：眼睛（未预览）/ 眼睛+斜线（预览中）。
                var previewing by remember(id) { mutableStateOf(DollPreviewOverlay.isShowing(id)) }
                DhKit.IconButton(
                    iconRes = if (previewing) Icons.IC_EYE_OFF else Icons.IC_EYE,
                    sizeDp = Design.SZ_MD,
                    color = c.title
                ) {
                    if (previewing) {
                        // 【必须走带 ctx 的重载】它会给这只人偶记下「消失时刻」——
                        //   这是「第 2 下也算人偶消失 → 下次触发回初始位置」的关键。
                        DollPreviewOverlay.hide(act)
                        previewing = false
                    } else {
                        val ok = DollPreviewOverlay.show(act, id)
                        previewing = ok
                        // 【r12】失败不再静默：最可能是没给「显示在其他应用上层」权限。
                        //   不说的话用户只会觉得「点了没反应」。走工程合法的弹窗通道（禁 Toast）。
                        if (!ok) {
                            HomeUi.showNotice(
                                act, "无法预览",
                                "预览需要「显示在其他应用上层」权限。请到 设置 → 权限 里开启后重试。"
                            )
                        }
                    }
                }
                // 【r13 用户定案】顶栏不再放「保存」——编辑页里所有设置都是**改了即存**，
                //   「保存」按钮会造成「还有东西没提交」的误解。
                //   列表的刷新改由「离开本页」时广播（见 open() 里的 PageStack.onLeave）。
            }
        ) {
            // 【为什么内容区自己滚】Page 传了 scrollable=false（要让底部导航钉在屏幕下沿、
            //   不跟着滚）。活动页的项目加起来会超过一屏，所以这里单独给内容区开滚动。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                when (tab) {
                    TAB_CTRL -> CtrlTab(act, id)
                    TAB_ACT -> ActTab(act, id)
                    else -> PersonaTab(act, id)
                }
            }
            Spacer(Modifier.height(8.dp))
            // 【底部胶囊导航】三项各带图标 + 文字；选中态用 OPTION 底 + ACC 字图（与设置页多选同套语义色）。
            DhKit.NavBar {
                DhKit.NavItem(
                    iconRes = Icons.IC_SLIDERS_HORIZONTAL,
                    label = "控制",
                    selected = tab == TAB_CTRL
                ) { tab = TAB_CTRL }
                DhKit.NavItem(
                    iconRes = Icons.IC_SPARKLES,
                    label = "活动",
                    selected = tab == TAB_ACT
                ) { tab = TAB_ACT }
                DhKit.NavItem(
                    iconRes = Icons.IC_USER_ROUND,
                    label = "人格",
                    selected = tab == TAB_PERSONA
                ) { tab = TAB_PERSONA }
            }
        }
    }

    /* ============================ 控制页 ============================ */

    @Composable
    private fun CtrlTab(act: MainActivity, id: String) {
        // 【r11】名称输入框已删除：本轮不支持改名（人偶名统一由列表页管理）。
        // 【四个长横条】动作 / 操作 / 暂离 / 轮盘。
        //   r12：动作已激活（点进去是动作清单页）；轮盘仍是空壳，继续置灰。
        DhKit.ActionRow(label = "动作") { DollActionPage.open(act, id) }
        DhKit.ActionRow(label = "操作") { DollGesturePage.open(act, id) }
        DhKit.ActionRow(label = "暂离") { DollNumDialog.show(act, id, DollNumDialog.KIND_AWAY, null) }
        DhKit.ActionRow(label = "轮盘", enabled = false)
    }

    /* ============================ 活动页 ============================ */

    @Composable
    private fun ActTab(act: MainActivity, id: String) {
        val c = DhTokens.colors
        // 【为什么用 key(id) 包一层】DollProfile 不是 Compose 状态，改完比例后必须手动触发重组；
        //   这里用本地的 tick 作为「重读信号」：点一次加/减就自增一次，下面的显示值随之重算。
        var tick by remember(id) { mutableStateOf(0) }
        val scale = remember(id, tick) { DollProfile.scale(act, id) }

        // 比例大小：一行内并排四个按钮 −10 / −1 / +1 / +10（用户定案的倾向方案：一行更省版面）。
        // 【为什么不置灰】r10 明确「不给端点置灰、加减一直可用」；区间放宽到 20~400 后，
        //   正常点几下根本碰不到端点，即便碰到也只是夹取，按钮仍可点（见 DollProfile.SCALE_*）。
        /**
         * 【r13】改完比例：写盘 + 重算显示 + **立刻同步到预览**。
         *
         * 【为什么必须主动 refresh】用户原话「打开预览人偶的时候 点那个放大和缩小
         *   它会实时变大和缩小 不然我看不见啊」——
         *   预览是独立悬浮窗，不会因为编辑页重组就自己跟着变；必须显式推一次。
         *   [DollPreviewOverlay.refresh] 内部幂等：没在预览时什么都不做。
         */
        fun applyScale(delta: Int) {
            DollProfile.setScale(act, id, scale + delta)
            tick++
            DollPreviewOverlay.refresh(act, id)
        }

        DhKit.StepperRow(
            name = "比例大小",
            value = scale.toString() + "%",
            canMinus = true,
            canPlus = true,
            onMinus = { applyScale(-1) },
            onPlus = { applyScale(1) }
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = DhKit.GAP_ROW.dp)) {
            DhKit.MiniChip("−10", modifier = Modifier.weight(1f)) { applyScale(-10) }
            Spacer(Modifier.width(DhKit.GAP_ROW.dp))
            DhKit.MiniChip("−1", modifier = Modifier.weight(1f)) { applyScale(-1) }
            Spacer(Modifier.width(DhKit.GAP_ROW.dp))
            DhKit.MiniChip("+1", modifier = Modifier.weight(1f)) { applyScale(1) }
            Spacer(Modifier.width(DhKit.GAP_ROW.dp))
            DhKit.MiniChip("+10", modifier = Modifier.weight(1f)) { applyScale(10) }
        }
        DhForm.Note("点加减号调整；开了预览的话，改动会立刻反映到桌面上那只人偶。")

        // 初始位置：r12 改为「盖在真实桌面上」的悬浮窗（与屏幕范围同形态）；
        //   没有悬浮窗权限时降级为 App 内页面（DollHomePage），不崩不白屏。
        var homeTick by remember(id) { mutableStateOf(0) }
        val hasHome = remember(id, homeTick) { DollProfile.hasHomePivot(act, id) }
        DhKit.ActionRow(
            label = "初始位置",
            iconRes = Icons.IC_CROSSHAIR,
            trailing = if (hasHome) "已设置" else "未设置"
        ) {
            val ok = DollHomeOverlay.show(act, id) { homeTick++ }
            if (!ok) {
                DollHomePage.open(act, id)
            }
        }

        // 屏幕范围：横屏 / 竖屏各存一套，随方向自动取用。
        // 【r11 形态】优先弹「盖在真实桌面上」的悬浮窗（虚线框 + 横向操作条）；
        //   没有悬浮窗权限时降级为 App 内页面（DollRangePage），不崩不白屏。
        val land = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var boundsTick by remember(id) { mutableStateOf(0) }
        val hasBounds = remember(id, boundsTick, land) {
            DollProfile.hasBounds(act, id, DollProfile.orientOf(land))
        }
        DhKit.ActionRow(
            label = "屏幕范围",
            trailing = if (hasBounds) "已设置" else "整屏"
        ) {
            val ok = DollRangeOverlay.show(act, id) { boundsTick++ }
            if (!ok) {
                DollRangePage.open(act, id)
            }
        }

        // 回归时间：r12 改为弹窗（不再单开整页）；0 = 每次出现都回初始位置。
        var retTick by remember(id) { mutableStateOf(0) }
        val ret = remember(id, retTick) { DollProfile.returnSeconds(act, id) }
        DhKit.ActionRow(
            label = "回归时间",
            trailing = if (ret <= 0) "每次回到初始位置" else ret.toString() + " 秒"
        ) {
            DollNumDialog.show(act, id, DollNumDialog.KIND_RETURN) { retTick++ }
        }

        // 【说明行】用一句话把「回归时间」讲清楚：这是本轮新增概念，用户第一次见未必懂。
        DhForm.Note("回归时间：关闭人偶超过该秒数后再启动，人偶回到初始位置；填 0 表示每次都回。")
    }

    /* ============================ 人格页 ============================ */

    @Composable
    private fun PersonaTab(act: MainActivity, id: String) {
        DhKit.ActionRow(label = "交谈内容") { DollTalkPage.open(act, id) }
        DhKit.ActionRow(label = "性格内容") { DollPersonaPage.open(act, id) }
        DhForm.Note("交谈内容：这只人偶在「交谈」机制下会出现的台词，一条一句。")
        DhForm.Note("性格内容：这只人偶的性格描述，会随对话一起交给模型。")
        // 【r13·记忆系统重构】原「记忆」手风琴（自动保存 / 自动精简 / 打开记忆库）整块拆除。
        //
        // 【为什么拆】那三项里，前两个是**全局策略**（与具体某只人偶无关），
        //   已统一收进「设置 → 实验 → 记忆库设置」；只剩「打开记忆库」是真属于本只人偶的入口。
        //   用户定案：人偶编辑页里只留一个干净的「记忆库」入口，点进去就是这只人偶独立的库。
        DhKit.Card(collapsibleTitle = "记忆") {
            DhKit.ActionRow(label = "记忆库", trailing = "›") {
                MemPage.open(act, id)
            }
            DhForm.Note("这只人偶自己的长期记忆，与其它人偶完全隔离。"
                    + "聊天时她会自动往里记，你也能进去新建、编辑、删除。")
        }
    }
}
