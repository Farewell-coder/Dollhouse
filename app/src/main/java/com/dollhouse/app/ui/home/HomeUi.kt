package com.dollhouse.app.ui.home

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.BackgroundCropActivity
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PetService
import com.dollhouse.app.PetTileService
import com.dollhouse.app.R
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.device.ShizukuBridge
import com.dollhouse.app.keepalive.KeepAliveBridge
import com.dollhouse.app.ui.chat.ChatListScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ui.compose.AppDialog
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.ThemeRefresh
import com.dollhouse.app.ui.theme.UiKit
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dollhouse.app.ui.compose.Blockable
import com.dollhouse.app.ui.compose.ComposeLayerShield
import com.dollhouse.app.ui.compose.LayerGate

/**
 * 【职责】首页与设置页的**纯逻辑**：权限查询与申请、Intent 跳转、偏好读写、服务状态、
 *         状态同步。一行 View 都不碰 —— 界面由 [HomeScreen] / [SettingsScreen] 声明式渲染，
 *         两者靠 [HomeState] 通信。
 *
 * 【为什么这样分层】原实现把「逻辑」与「找控件改字」揉在一起（`find(tag)` + `setText`），
 *   Compose 侧没有控件句柄可找。现在逻辑层只写 [HomeState]，界面层只读 [HomeState]，
 *   同一份逻辑既能被 Compose 页驱动，也保持可测。
 *
 * 【入口】`MainActivity` 在 `onCreate` / `onResume` 调用 [apply] / [sync]。
 *
 * 【r13 扩展·三 Tab 底座】改造后主界面是三个 Tab（聊天 / 人偶 / 主页）共享一个底座，
 *   外加一条常驻的悬浮底栏（[BottomNav]）。本对象的 [apply] 负责把四样东西装配好：
 *   三个页面（各自一棵 ComposeView）+ 一条底栏；[show] 只切页面的可见性。
 *   设置页从「底座之一」降级为**主页里的一个覆盖层**（走 [PageStack]），
 *   理由见 [TAG_SET] 的注释。
 */
object HomeUi {
    private const val LOG_TAG = "Dollhouse"

    /**
     * 页面 tag：首页 / 设置页是「常驻底座」，由本对象自己用 tag 定位、互斥显隐。
     * 【为什么不进 PageStack】这两页是 Home 的底座（保性能与滚动位置），
     *   与覆盖层是两套语义：覆盖层「压在上面的层」，底座「Home 本身换了张脸」。
     */
    const val TAG_HOME = "feiyu_home_page"

    /**
     * 【r13·已降级】设置页的老 tag。
     *
     * 【保留原因】它与 [com.dollhouse.app.ui.home.SettingsScreen.TAG_PAGE] 是**同一个字符串值**
     *   （`feiyu_settings_page`），历史上这两个常量各指一处引用。设置页从底座改成覆盖层后，
     *   权威值在 `SettingsScreen.TAG_PAGE`（页面栈与重建都认它）。
     *   这里保留常量只是为了不改动 [PageStack] 存档里可能存在的旧值 —— 两者字符串相同，
     *   所以旧存档照样能被 `SettingsScreen` 认领，不需要迁移。
     */
    const val TAG_SET = "feiyu_settings_page"

    /** 【r13】聊天列表页的 tag（第 1 个 Tab 的宿主）。 */
    const val TAG_CHAT = "feiyu_chat_page"

    /** 【r13】人偶页的 tag（第 2 个 Tab 的宿主）。 */
    const val TAG_CONTACT = "feiyu_contact_page"

    /** 【r13】底部悬浮导航栏的 tag —— 它覆盖在三个页面之上，不参与页面显隐。 */
    const val TAG_BAR = "feiyu_bottom_bar"

    /**
     * 【r14】「我的资料」抽屉层的 tag。
     *
     * 【为什么单独一层】抽屉要盖住**整块内容区**（三个 Tab + 悬浮底栏）——
     *   底栏是 `content` 的直接子，抽屉必须比它更晚加入才能压住它。
     *   放进 `host` 里会被底栏盖住，所以和底栏一样挂在 `content` 上、且排在最后。
     *
     * 【为什么常驻而不按需挂载】[ProfileDrawer.Host] 自己用 `if (!opened) return` 做了
     *   空转短路；常驻只多一个空 ComposeView，但换来「打开时零挂载延迟」。
     */
    const val TAG_PROFILE = "feiyu_profile_drawer"

    /** 底座唯一 Compose 根的 tag（去 View 化后只剩这一个，供上层定位整块底座）。 */
    const val TAG_ROOT = "feiyu_home_root"

    /** 通知权限的运行时申请回调码。 */
    private const val REQ_NOTIF = 102
    /** Shizuku 授权请求码（结果经 ShizukuBridge 的 listener 回来）。 */
    private const val REQ_SHIZUKU = 103

    /** 复制出去的外部链接：同一条链接兼管开与关（PetLinkActivity 里按运行状态自行判断）。 */
    private const val T_CMD_LINK = "dollhouse://pet/toggle"

    /* ============================ 装配 ============================ */

    /**
     * 搭出「三个 Tab + 一条底栏」的主界面底座。
     *
     * 【结构】
     * ```
     * content
     *  └─ host (FrameLayout)
     *      ├─ chat     (TAG_CHAT)     ← 聊天列表，默认可见
     *      ├─ contact  (TAG_CONTACT)  ← 人偶
     *      ├─ home     (TAG_HOME)     ← 原首页
     *      └─ bar      (TAG_BAR)      ← 悬浮底栏（盖在最上，三页常驻）
     * ```
     *
     * 【为什么底栏独立成层而不是塞进每个页面】底栏要在三页之间**保持一致的外观与状态**
     *   （选中的 Tab 高亮、未读徽标）。若每页各挂一条，切页时是三条不同的 View 在交替
     *   出现，未读徽标要同步三份，选中态动画也会被切断重来。
     *   独立成层后它自始至终是同一个 View，切页时纹丝不动。
     *
     * 【为什么不 setContentView】Compose 页是「页面」而不是「内容视图」，
     *   走与既有页面栈同一条挂载通道（见 `ComposeHost` 头注释）。
     */
    @JvmStatic
    fun apply(activity: Activity?) {
        if (activity == null || activity !is MainActivity) {
            return
        }
        try {
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
            content.removeAllViews()
            // 【去 View 化·阶段 2】整个底座现在只有 **一个** ComposeView。
            //
            // 【改造前是什么样】1 个 FrameLayout 壳 + 5 个 ComposeView（三 Tab / 底栏 / 抽屉）
            //   + 4 层 LayerShield，靠 visibility 互切、靠 tag 查找（findViewWithTag）。
            // 【现在】三页 + 底栏 + 抽屉全部在**同一棵组合**里（见 [HomeUiContent]），
            //   叠放顺序由 Layout 的绘制顺序决定，显隐由 `AppNav.current` 驱动 ——
            //   不再有「用 tag 去 View 树里找回某一页」这种间接寻址。
            //
            // 【为什么这是安全的】Tag 只被本文件的 showTab / settleTabs / barReady 用到
            //   （实测 `grep -rn "TAG_CHAT|TAG_BAR"` 全仓仅本文件），它们已同步改成读状态。
            ComposeHost.installForActivity(activity)
            val root = ComposeHost.createView(activity) {
                // 【订阅当前 Tab】AppNav.current 是 mutableStateOf —— 切页即重组。
                val tab = AppNav.current
                HomeUiContent(
                    act = activity,
                    tab = tab,
                    onPickTab = { picked ->
                        // 【切页收尾】与原 buildBar 里的回调语义完全一致：
                        //   先弹掉覆盖层，再切 Tab（切 Tab 即回栈底，符合主流 App 直觉）。
                        clearLayers(activity)
                        showTab(activity, picked)
                    }
                )
            }
            root.tag = TAG_ROOT
            content.addView(
                root,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // 【导航覆盖层】仍由 NavHost 独立安装（它自己管一层 ComposeView + 闸门），
            //   层级在底座之上 —— 与原实现「后加的在上面」一致。
            com.dollhouse.app.ui.nav.NavHost.install(activity)
            // 【抽屉宿主绑定】ProfileDrawer.Host 现在是组合的一部分，仍需知道宿主 Activity。
            ProfileDrawer.bindHost(activity)
            sync(activity)
            // 【路由恢复】冷启动进「上次停留的 Tab」。
            AppNav.restore(activity)
            showTab(activity, AppNav.current)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /** dp → px 的局部换算（装配期用，避免每处都写 UiKit.dp）。 */
    private fun dp(act: Activity, v: Float): Float {
        return v * act.resources.displayMetrics.density
    }

    /** 把覆盖层全部弹掉（切 Tab 用）。 */
    private fun clearLayers(act: Activity) {
        try {
            while (com.dollhouse.app.ui.compose.PageStack.depth(act) > 0) {
                // 【先清账再摘页】pop 带退场动画；这里要的是「立刻消失」，
                //   所以传一个空回调并让它走无动画的快路径 —— 直接 reset 会连记账一起丢，
                //   而记账正是我们要保留的（下次还能正常入栈）。
                if (!com.dollhouse.app.ui.compose.PageStack.pop(act)) {
                    break
                }
            }
            com.dollhouse.app.ui.provider.ProviderNav.resetForTab(act)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /* ============================ 状态同步 ============================ */

    /** 每次回到前台时调用：把真实状态灌进 [HomeState]，界面自动重组。 */
    @JvmStatic
    fun sync(ctx: Context) {
        try {
            syncAll(ctx)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /**
     * 全量刷新：权限 / 开关 / 外观 / 记忆 / 操作 / 首页按钮。
     * 【为什么合成一个】原实现按卡片拆成 `syncPerm` / `syncTheme` / `syncMem` / `syncLookBg`
     *   等 6 个函数，各自 `find` 一批控件；现在只是往 [HomeState] 写一批值，合并更省事也更快。
     */
    @JvmStatic
    fun syncAll(ctx: Context) {
        HomeState.running = isServiceRunning(ctx)
        HomeState.chatAvailable = hasChatTarget(ctx)
        HomeState.hasDoll = hasEnabledDoll(ctx)
        // 权限
        HomeState.overlay = hasOverlay(ctx)
        HomeState.notif = hasNotif(ctx)
        HomeState.battery = KeepAliveBridge.isIgnoringBatteryOptimizations(ctx)
        HomeState.shizuku = ShizukuBridge.state(ctx)
        HomeState.keepAlive = KeepAliveBridge.isEnabled(ctx)
        HomeState.tile = PetPrefs.tileAdded(ctx)
        HomeState.hideRecents = PetPrefs.hideRecents(ctx)
        // 外观
        HomeState.themeMode = ThemeManager.mode(ctx)
        HomeState.monet = ThemeManager.monet(ctx)
        HomeState.bgSet = PetPrefs.chatBackground(ctx).isNotEmpty()
        HomeState.bgAlpha = PetPrefs.chatBgAlpha(ctx)
        HomeState.bgCardAlpha = PetPrefs.cardAlpha(ctx)
        HomeState.bgScrim = PetPrefs.scrimStrength(ctx)
        HomeState.bgFrost = PetPrefs.frostStrength(ctx)
        HomeState.fontColor = PetPrefs.fontColor(ctx)
        HomeState.bgMode = PetPrefs.bgMode(ctx)
        HomeState.bgBlur = PetPrefs.blurRadius(ctx)
        // 【r14】弹窗与卡片 / 聊天界面两组新状态。
        HomeState.dlgAlpha = PetPrefs.dialogAlpha(ctx)
        HomeState.dlgBlur = PetPrefs.dialogBlur(ctx)
        HomeState.showAvatar = PetPrefs.showAvatar(ctx)
        HomeState.bubbleUserFrost = PetPrefs.bubbleUserFrost(ctx)
        HomeState.bubbleWidth = PetPrefs.bubbleWidth(ctx)
        // 【r10】人偶比例 / 手势响应 / 暂离时长全部改为「按人偶」存在 DollProfile，
        //   设置页不再有对应行，这里也就不需要再同步进 HomeState。
        // 【r13·记忆系统重构】原「记忆」四项（memAuto / memSave / memMerge / memIndex）
        //   已随旧记忆系统一起删除 —— 新策略由「记忆库设置」页直读直写 PetPrefs，不再需要镜像。
    }

    /**
     * 【每秒心跳】只刷「会自己变」的那几项：服务状态、权限、开关。
     * 【为什么不复用 syncAll】`hasChatTarget` 要遍历供应商与模型表，代价明显高于其余项；
     *   而它只会在用户主动增删启停供应商 / 模型时变 —— 那些入口自己会调 [syncChatEntryNow]，
     *   进页时 [syncAll] 也兜一次，没必要每秒扫一遍。
     */
    @JvmStatic
    fun syncTick(ctx: Context) {
        HomeState.running = isServiceRunning(ctx)
        HomeState.overlay = hasOverlay(ctx)
        HomeState.notif = hasNotif(ctx)
        HomeState.battery = KeepAliveBridge.isIgnoringBatteryOptimizations(ctx)
        HomeState.shizuku = ShizukuBridge.state(ctx)
        HomeState.keepAlive = KeepAliveBridge.isEnabled(ctx)
        HomeState.tile = PetPrefs.tileAdded(ctx)
        HomeState.hideRecents = PetPrefs.hideRecents(ctx)
    }

    /**
     * 【响应性】供设置侧调用：模型 / 供应商的启用态一变就立即重算「打开聊天」入口。
     * 【线程】只写状态，必须回主线程执行；调用方可能在任意线程（含回调线程）。
     */
    @JvmStatic
    fun syncChatEntryNow(ctx: Context?) {
        val act = UiKit.findActivity(ctx ?: return) ?: return
        act.runOnUiThread { HomeState.chatAvailable = hasChatTarget(act) }
    }

    /** 服务是否在跑（供 [HomeScreen] 首帧渲染用）。 */
    @JvmStatic
    fun isServiceRunning(ctx: Context): Boolean {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            for (info in am.getRunningServices(Int.MAX_VALUE)) {
                if (PetService::class.java.name == info.service?.className) {
                    return true
                }
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        return false
    }

    /**
     * 是否具备打开聊天的条件：存在任意「已启用供应商」且其下有「启用的可用聊天模型」。
     * 【只读标记】只读 enabled / kind 与绑定关系，不触碰 KeyVault，也就不会读取任何凭据值。
     */
    @JvmStatic
    fun hasChatTarget(ctx: Context): Boolean {
        return try {
            val pvs = ProviderStore.providers(ctx)
            for (i in pvs.indices) {
                if (!pvs[i].enabled) {
                    continue
                }
                val ms = com.dollhouse.app.data.ModelStore.modelsOf(ctx, pvs[i].id)
                for (j in ms.indices) {
                    if (ms[j].enabled && com.dollhouse.app.ai.AiModel.KIND_CHAT == ms[j].kind) {
                        return true
                    }
                }
            }
            false
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            false
        }
    }

    /* ============================ 首页动作 ============================ */

    /**
     * 【r12】当前有没有「可展示的人偶」= 至少启用了一只。
     * 【为什么首页也要查】需求定案「没有选择使用人偶的情况下 就不要出现人偶」：
     *   一只都没启用时服务会自己停掉，但那样用户只看到「点了没反应」，
     *   不如在这里先提示清楚去哪儿选。
     */
    @JvmStatic
    fun hasEnabledDoll(ctx: Context): Boolean {
        return try {
            DollStore.enabled(ctx).isNotEmpty()
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * 【r12】一次性提示。
     *
     * 【为什么是弹窗不是 Toast】`UiKit` 顶部有工程硬约束：本 App **不允许任何形式的
     *   Toast / Snackbar / 自实现浮层短提示**（2026-10-03 已全量删除 55 处）。
     *   这里走 [AppDialog.note] 这条统一通道：标题 + 正文 + 「知道了」。
     *
     * 【迁移记录】原先调用 `UiKit.showDialog` + `UiKit.dialogMessage`（View 手搭正文）。
     *   行为逐项等价：同样的标题 / 同样的「知道了」单按钮 / 点外部与返回键都可关闭 /
     *   连点只保留一个弹窗（下面的 noticeDialog 判重原样保留）。
     *   观感上只多了一层「弹窗透明度」的补偿（见 AppDialog.sheetColor）。
     */
    @JvmStatic
    fun showNotice(ctx: Context, title: String, message: String) {
        val act = UiKit.findActivity(ctx) ?: return
        // 同类提示只保留一个，连续点击不会叠出一串对话框。
        if (noticeDialog?.isShowing == true) return
        noticeDialog = AppDialog.note(act, title, message)
    }
    private var noticeDialog: android.app.Dialog? = null

    /**
     * 启停桌宠：点下即切目标态，随后轮询真实状态纠偏。
     * 【不改服务语义】起停仍走原有 Intent 通道（启动还含悬浮窗权限校验）。
     */
    @JvmStatic
    fun togglePet(act: Activity, wantStart: Boolean) {
        if (wantStart) {
            // 【r13】编辑人偶期间不允许启动桌宠，提示清楚原因。
            if (PetPrefs.isEditing(act)) {
                showNotice(
                    act, "正在编辑人偶",
                    "编辑期间桌面上只能看到正在预览的那一只。请先退出编辑页，再启动人偶。"
                )
                return
            }
            // 【r12】先确认选过人偶：没有就不发启动指令，直接提示去选。
            if (!hasEnabledDoll(act)) {
                showNotice(act, "还没有选择人偶", "先去「设置 → 自定义人偶」启用一只，她才会出现。")
                return
            }
            if (!hasOverlay(act)) {
                requestOverlay(act)
                return
            }
            if (Build.VERSION.SDK_INT >= 33 && act is Activity
                && act.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
                act.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
            }
            val i = Intent(act, PetService::class.java)
            i.action = PetService.ACTION_START
            if (Build.VERSION.SDK_INT >= 26) {
                act.startForegroundService(i)
            } else {
                act.startService(i)
            }
        } else {
            val i = Intent(act, PetService::class.java)
            i.action = PetService.ACTION_STOP
            act.startService(i)
        }
        awaitToggle(act, wantStart, 0)
    }

    /**
     * 启停后轮询真实服务状态：命中期望值（或超时）即回填。
     * 【为什么轮询】startForegroundService / startService 是异步的，立即读取必然读到旧状态；
     *   固定单次延迟又可能过早或过慢，轮询是「即时反映」与「不误报」之间最稳的做法。
     */
    private fun awaitToggle(act: Activity, wantStart: Boolean, attempt: Int) {
        act.window?.decorView?.postDelayed({
            val actual = isServiceRunning(act)
            HomeState.running = actual
            if (actual != wantStart && attempt < 10) {
                awaitToggle(act, wantStart, attempt + 1)
            }
        }, 180L)
    }

    /**
     * 打开全屏聊天页（带淡入，不用系统硬切）。
     *
     * 【守卫】没配好「可用聊天模型」时不进页，只置一次状态位。
     *   【为什么要守卫】进去也发不出消息，用户只会看到「点了发送没反应」，
     *   不如拦在门外。
     * 【r13·入口收拢】原「打开聊天」按钮已从主页移除，本方法现在只由**聊天列表**调用
     *   （点某条对话卡片）。所以「没配模型」这件事必须**给出可读的提示** ——
     *   以前主页上那个按钮会因 `chatAvailable` 为假而整个不显示（用户看不见就不会点），
     *   现在列表卡片一直在，点了没反应会让人以为坏了。
     */
    @JvmStatic
    fun openChat(act: Activity) {
        // 对话界面始终允许进入；是否有可用模型只在真正发送消息时检查。
        try {
            com.dollhouse.app.PageTransitions.start(
                act, Intent(act, com.dollhouse.app.ChatActivity::class.java)
            )
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /* ============================ 页面切换 ============================ */

    /**
     * 【r13】切到某个 Tab：只改可见性 + 交叉淡入，不重建任何视图。
     *
     * 【为什么要交叉】三页同宿主，只淡入目标页会让旧页透过来糊一下。
     * 【为什么不用 translateX 滑动】本 App 的页面栈（[com.dollhouse.app.ui.compose.PageStack]）
     *   已经用了「自右滑入」表达「前进一层」。若 Tab 切换也用横向滑动，
     *   两种语义会在视觉上撞车（用户分不清「进了一层」还是「换了个 Tab」）。
     *   Tab 之间用**淡入淡出**区分，是主流 App 的通行做法。
     */
    @JvmStatic
    fun showTab(activity: Activity, tab: Int) {
        try {
            // 【去 View 化·阶段 2】切页现在只是**写一个状态**。
            //
            // 【改造前】按 tag 在 View 树里找回三页，再手工设 visibility / 播 alpha 交叉淡出，
            //   还要处理「三页同时可见」这种被中断动画留下的坏状态（那段几十行的修复逻辑
            //   见 git 历史）。**这些都不需要了**：现在三页在同一棵组合里、常驻参与组合，
            //   显隐由 `AppNav.current` 单一真源驱动，重组时不会出现「多于一个可见」的
            //   中间态 —— 状态只有一个，就不存在多份状态互相打架。
            //
            // 【保住的能力】`AppNav.select` 会写 PetPrefs（冷启动恢复上次 Tab），
            //   并且三页状态（搜索词、滚动位置）因常驻组合而完整保留。
            AppNav.select(activity, tab)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /**
     * 【回前台状态归位】把当前 Tab 落回唯一真源。
     *
     * 【去 View 化后的语义变化·重要】改造前它是「把三页 View 的 visibility 强行摆正」——
     *   因为旧实现用 visibility 手工切页，动画被打断（切后台 / 厂商清后台）会留下
     *   「多页同时可见」的坏状态，必须靠这个方法在 onResume 时纠正。
     *
     * 现在三页在同一棵组合里常驻、显隐由 `AppNav.current` 单一真源驱动，
     *   **不存在「多页可见」这种中间态** —— 单一状态推不出一致性以外的结果。
     *   因此本方法退化为「把 Tab 同步给偏好」的幂等动作（`AppNav.select` 内部对
     *   相同值直接 return），保留它只为不动 `MainActivity.onResume` 的调用点。
     *
     * 【为什么保留而不是删】`MainActivity.kt:388` 在 onResume 无条件调用它；
     *   删掉要么改调用点（引入无谓改动），要么让方法消失后编译失败。
     *   留一个语义清晰的幂等壳更稳。
     */
    @JvmStatic
    fun settleTabs(activity: Activity) {
        try {
            AppNav.select(activity, AppNav.current)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /**
     * 【兼容旧调用点】`show(act, home)` 的旧语义是「首页 ↹ 设置页」。
     *
     * 【映射】改造后设置页是主页里的一个覆盖层，所以：
     *   `home = true`  → 切到「主页」Tab；
     *   `home = false` → 切到「主页」Tab 并压上设置页（与改造前「点设置按钮」完全等价）。
     *
     * 【为什么保留这个函数】`HomeScreen` 的「设置」按钮与其它几处仍在调它；
     *   保留一个语义清晰的薄壳比改所有调用点更稳（且日后若再加设置入口，行为自动一致）。
     */
    @JvmStatic
    fun show(activity: Activity, home: Boolean) {
        AppNav.select(activity, AppNav.TAB_HOME)
        showTab(activity, AppNav.TAB_HOME)
        if (!home && activity is MainActivity) {
            SettingsScreen.open(activity)
        }
    }

    /**
     * 返回键：底座级的兜底。
     *
     * 【r13 语义变更】改造前是「设置页 → 首页」；现在是三个 Tab：
     *   - 已经在「聊天」（默认首页）→ 返回 false，交给系统（退出 App）；
     *   - 在其它 Tab → 回到「聊天」，返回 true。
     *   【为什么回聊天而不是回上一次的 Tab】聊天是默认首页，也是用户最常停留的地方；
     *   Android 的通行习惯是「返回逐层退到任务的主入口」，而主入口就是聊天。
     */
    @JvmStatic
    fun handleBack(activity: Activity): Boolean {
        try {
            // 【r14·抽屉优先】抽屉盖在最上层，返回键必须先关它；
            //   否则用户在抽屉里按返回会被直接弹回聊天 Tab，观感像「抽屉卡住了」。
            if (ProfileDrawer.opened) {
                ProfileDrawer.close()
                return true
            }
            if (AppNav.current == AppNav.TAB_CHAT) {
                return false
            }
            AppNav.select(activity, AppNav.TAB_CHAT)
            showTab(activity, AppNav.TAB_CHAT)
            return true
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            return false
        }
    }

    /**
     * 换主题重建后把滚动位置滚回去（消费一次性标志，冷启动不受影响）。
     * 【注】滚动位置由 `ThemeRefresh` 按遍历序记录，这里只负责「回到原来那一页」。
     */
    @JvmStatic
    fun restoreScroll(act: Activity) {
        try {
            if (!PetPrefs.themeRestore(act)) {
                return
            }
            PetPrefs.setThemeRestore(act, false)
            PetPrefs.setThemeOnSettings(act, false)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /* ============================ 权限查询 ============================ */

    private fun hasNotif(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0
            } else {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm == null || nm.areNotificationsEnabled()
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            false
        }
    }

    private fun hasOverlay(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(ctx) else true
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            false
        }
    }

    /* ============================ 权限申请 ============================ */

    /** 去系统设置开悬浮窗：SDK>=23 可带 package 直接定位到本应用那一项。 */
    @JvmStatic
    fun requestOverlay(ctx: Context) {
        try {
            val i: Intent = if (Build.VERSION.SDK_INT >= 23) {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            } else {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            try {
                val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (ignored2: Throwable) {
                Logs.w(LOG_TAG, "ignored", ignored2)
            }
        }
    }

    /** 通知权限：33+ 走标准运行时申请；24~32 没有这个运行时权限，只能跳到通知设置页。 */
    @JvmStatic
    fun requestNotif(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ctx is Activity) {
                ctx.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
                return
            }
            if (openAppSetting(ctx, "com.android.settings.Settings\$AppNotificationSettingsActivity")) {
                return
            }
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /**
     * 跳系统「电池优化」列表：SDK>=23 带包名直达本应用的豁免页；
     * 厂商改过该页会失败，退回不带包名的不受限列表，两层都失败兜底到应用详情页。
     */
    @JvmStatic
    fun requestIgnoreBattery(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                val i = Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
                i.data = Uri.parse("package:" + ctx.packageName)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        try {
            val i = Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        requestAppDetails(ctx)
    }

    /** 跳本应用详情页：自启动 / 后台活动 / 后台弹出界面这类私有开关都从此页进。 */
    @JvmStatic
    fun requestAppDetails(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + ctx.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /** 尝试打开指定组件；组件不存在或权限不足时返回 false（不抛）。 */
    private fun openComponent(ctx: Context, pkg: String, cls: String): Boolean {
        return try {
            val i = Intent()
            i.component = ComponentName(pkg, cls)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            false
        }
    }

    /** 打开本应用在系统设置里的指定页；失败返回 false。 */
    private fun openAppSetting(ctx: Context, cls: String): Boolean {
        return openComponent(ctx, "com.android.settings", cls)
    }

    /* ============================ Shizuku ============================ */

    /**
     * Shizuku 授权行点击：按四态分别落地。
     * 已授权时该行不可点（界面侧已禁用），走不到这里。
     */
    @JvmStatic
    fun shizukuClicked(ctx: Context) {
        when (ShizukuBridge.state(ctx)) {
            ShizukuBridge.S_NOT_INSTALLED ->
                // 没装管理器：没有可跳转的目标，只能用自绘弹窗把话说清楚（禁止 Toast）。
                showShizukuDialog(
                    ctx, "未检测到 Shizuku 管理器。\n\n"
                        + "先安装 Shizuku 并启动其服务（需配合 adb / 无线调试），"
                        + "再回到这里授权。授权后本应用与 AI 都能使用系统级能力。"
                )

            ShizukuBridge.S_NOT_RUNNING ->
                if (!ShizukuBridge.openManager(ctx)) {
                    showShizukuDialog(
                        ctx, "无法自动打开 Shizuku 管理器。\n\n"
                            + "请手动打开它并启动服务，再回到这里。"
                    )
                }

            else -> {
                // 【老版兼容】server < v11 的 Shizuku 没有「运行时授权」机制，
                //   requestPermission 不弹框、无回调 —— 只能在管理器界面里手动勾选本应用。
                if (ShizukuBridge.needManagerForGrant()) {
                    if (!ShizukuBridge.openManager(ctx)) {
                        showShizukuDialog(
                            ctx, "无法自动打开 Shizuku 管理器。\n\n"
                                + "你的 Shizuku 版本较旧，需要在管理器里手动勾选本应用。"
                        )
                    } else {
                        showShizukuDialog(
                            ctx, "你的 Shizuku 版本较旧。\n\n"
                                + "请在管理器里把本应用勾选为「已授权」，然后回到这里。"
                        )
                    }
                    return
                }
                // 未授权：由 server 弹系统确认框，结果经 listener 回来再刷新。
                ShizukuBridge.requestPermission(REQ_SHIZUKU)
            }
        }
    }

    /**
     * Shizuku 说明弹窗（无 Toast 约束下的唯一合法反馈通道）。
     *
     * 【迁移记录】原 `UiKit.showDialog(act, "Shizuku 授权", UiKit.dialogMessage(...), "知道了", null, null, null)`
     *   → [AppDialog.note]。标题、正文、「知道了」单按钮、点外部可关闭全部保持不变；
     *   与 [showNotice] 不同，这里**不做**判重（原来也没有），连续触发会叠窗，行为按原样保留。
     */
    private fun showShizukuDialog(ctx: Context, message: String) {
        val act = UiKit.findActivity(ctx) ?: return
        AppDialog.note(act, "Shizuku 授权", message)
    }

    /* ============================ 保活 / 磁贴 ============================ */

    /**
     * 无感保活开关落地：写盘 + 注册 / 注销调度 + 起停常驻服务，全部交给保活门面。
     * 【失败回滚】门面出错时会把状态落回「关」，这里同步把 state 拨回去，
     *   避免出现「界面显示开、实际没生效」。
     */
    @JvmStatic
    fun onKeepAliveChanged(on: Boolean, ctx: Context) {
        val actual = KeepAliveBridge.setEnabled(ctx, on)
        if (actual != on) {
            HomeState.keepAlive = actual
        } else {
            HomeState.keepAlive = on
        }
    }

    /**
     * 磁贴开关被拨动：开 = 请求系统把磁贴加进快捷面板；关 = 打开快捷面板让你长按移除。
     * 【为什么「关」不能直接移除】Android 没有 requestRemoveTileService 这种 API，
     *   移除磁贴只能由用户在面板里长按拖走。
     */
    @JvmStatic
    fun onTileSwitchChanged(on: Boolean, ctx: Context) {
        if (on) {
            val ok = requestAddTile(ctx)
            // 【修·开关永远弹回】原来是 `if (ok) PetPrefs.tileAdded(ctx) else false`：
            //   ok 时写回的仍是旧值，等于「点了开也没开」，下一次 sync 读回来还是 false，
            //   开关自己弹回去。请求已发出就按开记录，失败才落 false 并引导去快捷面板。
            PetPrefs.setTileAdded(ctx, ok)
            if (!ok) {
                openQuickSettings(ctx)
            }
        } else {
            PetPrefs.setTileAdded(ctx, false)
            openQuickSettings(ctx)
        }
        HomeState.tile = PetPrefs.tileAdded(ctx)
    }

    /** 请求系统把本应用的磁贴放进快捷面板（Android 13+ 才有这个接口）。 */
    private fun requestAddTile(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return false
        }
        return try {
            val sbm = ctx.getSystemService("statusbar") as? StatusBarManager ?: return false
            sbm.requestAddTileService(
                ComponentName(ctx, PetTileService::class.java),
                ctx.getString(R.string.app_name),
                Icon.createWithResource(ctx, Icons.IC_TILE_PET),
                ctx.mainExecutor,
                // 这个回调系统要求非 null：传 null 会在系统回结果时 NPE，必须给空实现。
                java.util.function.Consumer<Int> { }
            )
            true
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            false
        }
    }

    /** 打开快捷设置面板（用户在那里长按磁贴可移除或拖动排序）。 */
    private fun openQuickSettings(ctx: Context) {
        try {
            val i = Intent("android.settings.QUICK_SETTINGS")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /** 「开关指令」行被点：把 dollhouse://pet/toggle 复制进系统剪贴板。 */
    @JvmStatic
    fun copyToggleLink(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("Dollhouse", T_CMD_LINK))
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /* ============================ 隐藏后台卡片 ============================ */

    /**
     * 开关落地：先写偏好，再把本应用在「最近任务」里的任务卡片刻成排除 / 恢复。
     * AppTask 只作用于本应用自己的任务，无需额外运行时权限。
     */
    @JvmStatic
    fun onHideRecentsChanged(hide: Boolean, ctx: Context) {
        PetPrefs.setHideRecents(ctx, hide)
        HomeState.hideRecents = hide
        applyHideRecents(ctx)
    }

    private fun applyHideRecents(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am == null || Build.VERSION.SDK_INT < 21) {
                return
            }
            val hide = PetPrefs.hideRecents(ctx)
            for (task in am.appTasks) {
                task.setExcludeFromRecents(hide)
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /* ============================ 外观 ============================ */

    /** 选聊天背景：跳裁剪页（选图 → 框选预览 → 按框落盘，聊天页拿到的永远不变形）。 */
    @JvmStatic
    fun pickBackground(act: Activity) {
        try {
            com.dollhouse.app.PageTransitions.start(
                act, Intent(act, BackgroundCropActivity::class.java)
            )
        } catch (t: Throwable) {
            // 【改造前是静默 catch】用户点「更换图片」什么都不会发生，
            //   也不知道是崩了还是没响应 —— 这正是「功能失效」的体感来源。
            //   现在把原因显示出来，至少能自证是哪一环坏了。
            Logs.w(LOG_TAG, "启动背景裁剪页失败", t)
            android.widget.Toast.makeText(
                act,
                "打不开裁剪页：" + (t.message ?: t.javaClass.simpleName),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 清除背景：抹掉已设的聊天背景图并立刻刷新全局背景。 */
    @JvmStatic
    fun clearBackground(act: Activity) {
        PetPrefs.setChatBackground(act, "")
        HomeState.bgSet = false
        // 【不经过 MainActivity】宿主只保留「通知桌宠服务刷新」这一个入口，
        //   这里走同一条 Intent 通道，并带上「服务没在跑就不发」的判断。
        notifyPetRefresh(act)
        GlobalBackground.refreshAll()
        ThemeRefresh.applyInPlace(act)
    }

    /**
     * 卡片不透明度说明文案。
     *
     * @param onPhoto 当前是否真的看得到背景图（`DhKit.hasPhotoBackdrop()`）。
     *                **决定要不要挂那句事实警告**：没图时卡片压的是页面底色，
     *                任何不透明度下副标题都达标（实测 25% 时仍有 6.7:1），挂上去就是无中生有。
     * @param dark    当前是否深色 / 纯黑档（`ThemeManager.isDark`）。决定阈值与"哪种照片"：
     *                浅色档怕**深色照片**（暗底 + 暗字），深色档怕**浅色照片**（亮底 + 亮字）。
     */
    @JvmStatic
    @JvmOverloads
    fun cardAlphaHint(percent: Int, onPhoto: Boolean = false, dark: Boolean = false): String {
        val tier = if (percent >= 95) {
            "（默认，卡片最实）"
        } else if (percent >= 60) {
            "（略透，能看见背景）"
        } else if (percent >= 25) {
            "（很透，背景明显）"
        } else {
            "（几乎全透，卡片里的字会压在背景上）"
        }
        // 【r15·事实警告】副标题已换成 subOnPhoto（在页面底色上 6.5:1），但卡片半透明会把照片
        //   渗进卡底，副标题的对比度随即被卡底亮度牵着走。三档主题各算一遍临界点
        //   （条件：遮罩 0 + 照片完全不透明 + 该主题最不利的明暗）：
        //     浅色档：80% → 4.58 ✅，79% → 4.45 ❌（最坏是纯黑照片）
        //     深色档：91% → 4.63 ✅，90% → 4.49 ❌（最坏是纯白照片）
        //     纯黑档：87% → 4.57 ✅，86% → 4.42 ❌
        //   深色与纯黑都按 91% 取（保守）：纯黑档会在 88~90% 多报三档，方向是"宁可早提醒"。
        //   文案只陈述事实与阈值，不写"会影响阅读"这类规劝。
        val safeMin = if (dark) 91 else 80
        val photoWord = if (dark) "偏亮" else "偏暗"
        val warn = if (onPhoto && percent < safeMin) {
            "\n卡片里的副标题在" + photoWord + "的照片上会低于 4.5:1"
        } else {
            ""
        }
        return "卡片不透明度\u00a0\u00a0" + tier + warn
    }
    /** 遮罩强度说明文案。 */
    @JvmStatic
    /** 【r13】自定义字体颜色的回显文案。 */
    fun fontColorHint(argb: Int): String {
        return "字体颜色\u00a0\u00a0" + (if (argb == 0) {
            "（跟随主题，正文与副标题按主题配色）"
        } else {
            "（已固定为 " + hex(argb) + "，正文与副标题都用它）"
        })
    }

    /** ARGB → `#RRGGBB`（丢 alpha，与用户输入的格式对齐）。 */
    fun hex(argb: Int): String {
        return String.format("#%06X", argb and 0x00FFFFFF)
    }

    /**
     * 【r13】应用自定义字体颜色（0 = 恢复跟随主题）。
     *
     * 【为什么直接 applyInPlace】字色是「抄」进每个 TextView / 每个 Compose 节点的快照色，
     *   改偏好不会让已建好的界面自己变。applyInPlace 会做一轮「旧色 → 新色」重映射
     *   （View 侧遍历视图树，Compose 侧靠 DhTheme.bump 重新取色），不重建 Activity，
     *   因此不会丢滚动位置与输入内容。
     */
    fun applyFontColor(act: Activity, argb: Int) {
        PetPrefs.setFontColor(act, argb)
        HomeState.fontColor = if (argb == 0) 0 else (argb or 0xFF000000.toInt())
        ThemeRefresh.applyInPlace(act)
        // 桌宠气泡的字色也是从 UiKit 读的，但它活在自己的 Service 里，通知它重读一次。
        notifyPetReload(act)
    }

    fun scrimHint(percent: Int): String {
        // 【r15·重写】旧文案（「很淡，注意看字」/「按可读性自动压暗」）对应的是旧口径：
        //   那时本值只是「倍数」，实际渲染被 (100 - 图不透明度) 夹死，拖到 8% 以上就没反应，
        //   文案却让用户以为「再拖大一点就能看清」—— 这就是用户说的「参数比例有问题」。
        //   现在本值就是绝对不透明度，读数与观感一一对应，文案只描述它真的做了什么，不再规劝。
        return "遮罩强度\u00a0\u00a0" + (if (percent >= 70) {
            "（很实，背景图基本被雾白盖住）"
        } else if (percent >= 30) {
            "（明显雾白，图偏淡）"
        } else if (percent >= 10) {
            "（轻微雾白，图很清楚）"
        } else {
            "（几乎不加遮罩，原图直出）"
        })
    }

    /**
     * 【r13】磨砂程度说明文案。
     *
     * 【为什么单独一条滑条】弹窗 / 底部面板的「质感」与背景图透明度、卡片透明度、遮罩强度
     *   是三件不同的事：那三项分别管「背景图多淡」「普通卡片多透」「背景图压多暗」，
     *   本项只管**弹窗这类必须盖住下层的层看起来有多像磨砂玻璃**。
     */
    @JvmStatic
    fun frostHint(percent: Int): String {
        return "磨砂程度\u00a0\u00a0" + (if (percent <= 5) {
            "（无虚化，弹窗是纯实色）"
        } else if (percent <= 35) {
            "（轻微磨砂）"
        } else if (percent <= 75) {
            "（明显磨砂，推荐）"
        } else {
            "（磨砂最强，最接近毛玻璃）"
        })
    }
    /** 毛玻璃半径说明文案。 */
    @JvmStatic
    fun blurHint(dp: Int): String {
        return "背景毛玻璃\u00a0\u00a0" + (if (dp <= 0) {
            "（默认，原图清晰）"
        } else if (dp <= 8) {
            "（轻微柔化）"
        } else if (dp <= 16) {
            "（明显朦胧）"
        } else {
            "（重度模糊，只留色块）"
        })
    }

    /** 背景图不透明度说明文案（原 `MainActivity.updateBgAlphaLabel` 的口径）。 */
    @JvmStatic
    fun alphaHint(percent: Int): String {
        // 【r15·只改标签口径】本值的语义一直是「越大越实」，标签却叫「透明度」，
        //   与数值方向相反 —— 用户以为「越透」而把它推到 92%，结果图几乎全实、字压在图案上。
        //
        // 【文案口径】不再规劝（旧版「推荐，字最清楚」「注意看字」把可读性甩给用户），
        //   只描述这一步取舍换来了什么。
        //
        // 【>75 那档为什么现在才改】它原写作「（原图，气泡可能和背景糊在一起）」——
        //   在会话列表行 / 说说 / 空态都还没铺底之前，这句是事实，不能删。
        //   现在四类都铺完（ChatListScreen 与 SocialPage 的 listSheet、DhState 的
        //   stateReadablePlate、DhKit 的顶栏带），实测最坏情况（纯黑照片、遮罩 0）下：
        //   顶栏 subOnPhoto 4.95、列表 5.04、空态 4.95、默认不透明卡片 5.25，全部过 AA。
        //   所以这句可以改成「仍有底」—— 但仍然**不写「不会看不清」**：
        //   卡片不透明度被拉低时（79% 压在深色照片上，SUB 只剩 3.19）另说，那由卡片那根滑条管。
        return "背景图不透明度\u00a0\u00a0" + (if (percent <= 8) {
            "（几乎看不见图）"
        } else if (percent <= 45) {
            "（图偏淡，文字最稳）"
        } else if (percent <= 75) {
            "（图清楚，卡片里的正文有卡片底兜着）"
        } else {
            "（原图，顶栏和列表里的字仍有底色）"
        })
    }

    /**
     * 主题模式选择面板：四档单选，选完写偏好并立即就地换色。
     *
     * 【为什么从 View 弹窗改到 Compose】原先这里用 `UiKit.showDialog` + 手拼
     *   `LinearLayout` / `TextView`。那是系统 View 弹窗，圆角、底色、按压反馈
     *   都自成一派，与现在全 Compose 的界面语言割裂；而且它让 HomeUi 一直挂在
     *   「View 实例化文件」清单里。
     *   现在改走 [DhForm.show]（弹窗宿主，内部是 ComposeView）+ [DhKit.CheckRow]
     *   —— 与其它 Compose 弹窗同一套令牌，动效也一并统一。
     *
     * 【为什么用 DhForm.show 而不是 DhForm.Alert】调用方是普通函数、不是
     *   `@Composable` 作用域（它们由设置页的点击回调直接调用），拿不到 composable 上下文。
     *   [DhForm.show] 正是给这种场景准备的：吃 Activity + 内容 lambda，返回可 dismiss 的 Dialog。
     */
    @JvmStatic
    fun pickThemeMode(act: Activity) {
        val cur = ThemeManager.mode(act)
        val holder = arrayOfNulls<Dialog>(1)
        holder[0] = DhForm.show(act, "主题模式") {
            for (i in ThemeManager.MODE_NAMES.indices) {
                DhKit.CheckRow(ThemeManager.MODE_NAMES[i], i == cur) {
                    holder[0]?.dismiss()
                    if (i == cur) {
                        return@CheckRow
                    }
                    ThemeManager.setMode(act, i)
                    applyThemeNow(act)
                }
            }
        }
    }

    /**
     * 背景填充模式选择面板：四档单选，选完写偏好并立即重刷背景。
     * 【与 [pickThemeMode] 同一处改造】View 弹窗 → Compose 弹窗。
     */
    @JvmStatic
    fun pickBgMode(act: Activity) {
        val cur = PetPrefs.bgMode(act)
        val holder = arrayOfNulls<Dialog>(1)
        holder[0] = DhForm.show(act, "背景填充") {
            for (i in PetPrefs.BG_MODES.indices) {
                DhKit.CheckRow(PetPrefs.BG_MODES[i], i == cur) {
                    holder[0]?.dismiss()
                    if (i == cur) {
                        return@CheckRow
                    }
                    PetPrefs.setBgMode(act, i)
                    HomeState.bgMode = i
                    GlobalBackground.refreshAll()
                }
            }
        }
    }

    /**
     * 【r14·外观 Tab①】「从壁纸取色」：四档取色来源的单选面板。
     *
     * 【与「莫奈主题色」的区别】莫奈是根据**当前色系**生成一整套柔和配色；
     *   本项是「照抄壁纸主色」——由系统壁纸服务提取，取到后写进 [PetPrefs.themeMonetWall]。
     *
     * 【本轮的改造】与 [pickThemeMode] 同一条：`UiKit.showDialog` 手拼 View
     *   → [DhForm.show] + [DhKit.CheckRow]。取色逻辑一行未动。
     */
    @JvmStatic
    fun pickMonetWall(act: Activity) {
        val names = ThemeManager.WALL_NAMES
        val cur = PetPrefs.themeMonetWall(act)
        val holder = arrayOfNulls<Dialog>(1)
        holder[0] = DhForm.show(act, "从壁纸取色") {
            for (i in names.indices) {
                DhKit.CheckRow(names[i], i == cur) {
                    holder[0]?.dismiss()
                    if (i == cur) {
                        return@CheckRow
                    }
                    // 壁纸取色是后台任务（要解一张整屏大图），交给 ThemeManager 的异步入口。
                    ThemeManager.setMonetWall(act, i)
                    // 【必须开启莫奈开关】取色的产物只有走莫奈调色板才能作用到界面上；
                    //   不顺手打开，用户会以为「选了没反应」。
                    ThemeManager.setMonet(act, true)
                    // 【必须换一次壁纸 id 缓存】取色的缓存键是「壁纸 id」，而我们换了取色来源档位，
                    //   缓存里的旧色相仍会被判为有效。这里先把缓存清成无效值，再异步重取。
                    PetPrefs.setThemeMonetHue10(act, Int.MIN_VALUE)
                    ThemeManager.monetHueAsync(act) { ok ->
                        act.runOnUiThread {
                            HomeState.monet = ThemeManager.monet(act)
                            applyThemeNow(act)
                            if (!ok) {
                                showNotice(act, "取色失败", "没能从壁纸取到颜色，可能是系统限制了壁纸读取。当前配色保持不变。")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 【r14·外观 Tab③】气泡宽度三档单选（窄 / 适中 / 宽）。
     *
     * 【为什么存档位而不是像素】见 [PetPrefs.bubbleWidth] 的注释：
     *   存像素会在不同分辨率 / 字号下撑爆屏幕或小得看不见。
     *
     * 【本轮改造】View 弹窗 → [DhForm.show] + [DhKit.CheckRow]。
     */
    @JvmStatic
    fun pickBubbleWidth(act: Activity) {
        val names = com.dollhouse.app.ui.settings.AppearancePage.BUBBLE_WIDTH_NAMES
        val cur = PetPrefs.bubbleWidth(act)
        val holder = arrayOfNulls<Dialog>(1)
        holder[0] = DhForm.show(act, "气泡宽度") {
            for (i in names.indices) {
                DhKit.CheckRow(names[i], i == cur) {
                    holder[0]?.dismiss()
                    if (i == cur) {
                        return@CheckRow
                    }
                    PetPrefs.setBubbleWidth(act, i)
                    HomeState.bubbleWidth = i
                    // 【必须 bump】气泡宽度是 Compose 侧在读；它是普通字段赋值，不会触发重组。
                    ThemeRefresh.bumpAll()
                }
            }
        }
    }

    /**
     * 【r14】桌宠点击穿透开关。
     *
     * 【为什么放在「外观 → 弹窗与卡片」Tab 下】它不改外观，但同属「界面交互」这一类；
     *   单独开一个 Tab 不值当，放这里最省（且用户对它的位置没有特别要求）。
     *
     * 【它到底改什么】见 [PetPrefs.touchThrough]：控制桌宠窗口是否让触摸穿过身体外区域。
     *   改完必须通知桌宠服务重载窗口，否则要等下次启停才生效。
     */
    @JvmStatic
    fun setTouchThrough(ctx: Context, on: Boolean) {
        PetPrefs.setTouchThrough(ctx, on)
        notifyPetReload(ctx)
    }

    /**
     * 莫奈主题色开关：先在后台取色，拿到颜色再写偏好并就地换色。
     * 取色绝不能在主线程做（壁纸是整屏大图），否则切开关会卡住界面。
     */
    @JvmStatic
    fun onMonetChanged(on: Boolean, ctx: Context) {
        if (!on) {
            ThemeManager.setMonet(ctx, false)
            HomeState.monet = false
            applyThemeNow(UiKit.findActivity(ctx))
            return
        }
        if (ThemeManager.hasMonetColor(ctx)) {
            ThemeManager.setMonet(ctx, true)
            HomeState.monet = true
            applyThemeNow(UiKit.findActivity(ctx))
            return
        }
        val app = ctx.applicationContext
        val act = UiKit.findActivity(ctx)
        ThemeManager.monetHueAsync(ctx, ThemeManager.HueCallback { ok ->
            if (!ok) {
                ThemeManager.setMonet(app, false)
                HomeState.monet = false
                return@HueCallback
            }
            ThemeManager.setMonet(app, true)
            HomeState.monet = true
            if (act != null) {
                applyThemeNow(act)
            }
        })
    }

    /**
     * 主题改动后立即生效：就地重映射配色 → 重刷全局背景 → 通知桌宠重读。
     * 【不重建】不再 recreate()：窗口不重建、不倒回首页、滚动位置不丢。
     */
    @JvmStatic
    fun applyThemeNow(act: Activity?) {
        if (act == null) {
            return
        }
        ThemeRefresh.applyInPlace(act)
        GlobalBackground.installHome(act)
        notifyPetRefresh(act)
    }

    /* ============================ 桌宠刷新 ============================ */

    /** 人偶集合变化（启用 / 停用 / 新增 / 删除）后重建常驻桌宠窗口（服务没在跑时静默忽略）。 */
    @JvmStatic
    fun notifyPetReload(ctx: Context) {
        try {
            if (!isServiceRunning(ctx)) {
                return
            }
            val i = Intent(ctx, PetService::class.java)
            i.action = PetService.ACTION_RELOAD
            ctx.startService(i)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /* ================= 底座 Compose 版（阶段 1·纯新增，未接入） ================= */

    /**
     * 【HomeUi 去 View·阶段 1】底座（三 Tab + 底栏 + 抽屉）的**单一 Compose 实现**。
     *
     * ## 与 [apply] 的关系（现在）
     * 本函数**尚未被调用**，[apply] 仍走原来的 View 装配。这样引入它是零风险的：
     * 不改变任何现有行为，可以先独立编译、独立审阅，再决定何时切换。
     *
     * ## 为什么必须三个 Tab 常驻组合（这是本方案最关键的设计约束）
     * 原实现用 `visibility = GONE` 切页，被隐藏的页**仍活着**，输入框里的搜索词、
     * 列表滚动位置全部保留。若 Compose 版写成「只渲染当前 Tab」，其余两页会离开组合，
     * `remember` 里的状态（如 `ChatListScreen` 的 `query`）会被销毁 ——
     * 用户切到人偶页再切回来，搜索框内容就没了。这是**功能退化**，不可接受。
     *
     * 因此这里用 [key] 让三页各自独立成组、始终参与组合，只用 `alpha` + 最上层
     * 决定可见性：`alpha=0` 的页仍保留状态，只是看不见、且不接收触摸
     * （触摸由层叠顺序与 [ComposeLayerShield] 决定，不依赖 alpha）。
     *
     * ## 为什么用自定义 [Layout] 而不是 `Box`
     * `Box` 会按顺序绘制，但**无法保证「目标页在最上」** —— 而触摸命中按 z 序判定。
     * 这里用 [Layout] 显式按「当前 Tab 最后绘制」排序，等价于原实现里
     * `showTab` 把目标页 `visibility=VISIBLE` 抬到最前的语义。
     *
     * ## 尚未解决的问题（如实记录，见报告）
     * ① 底栏的「浮在内容之上且不占布局高度」需要 `Alignment.BottomCenter` + `wrapContentHeight`，
     *    本轮未接线；
     * ② [com.dollhouse.app.ui.compose.LayerGate] 的 `Layers` 四字段类型是
     *    View 版 `LayerShield`，Compose 版闸门无法登记进去，因此**交互闸门暂不生效**；
     * ③ 抽屉的 `progress` 目前只能由手势驱动，`open()/close()` 仍走 View 通道。
     *
     * @param act      宿主（MainActivity，需要它的 Compose owner 与资源）
     * @param tab      当前 Tab（[AppNav.TAB_CHAT] / [AppNav.TAB_CONTACT] / [AppNav.TAB_HOME]）
     * @param onPickTab 底栏点击回调；由调用方决定「是否先清覆盖层」
     */
    /**
     * Compose 侧的层闸门句柄。
     *
     * 【为什么需要它】[LayerGate] 原本只认 `LayerShield`（View 子类），因为旧的底座装配是
     *   `FrameLayout + 4 层 LayerShield`。现在底座收成一个 ComposeView，闸门也必须能在
     *   Compose 侧表达 —— 所以抽出 [Blockable]，这个类就是它在 Compose 侧的实现。
     *
     * 【为什么 blocking 是 state】`LayerGate.refresh()` 在任意线程/任意时机改它，
     *   而 Compose 只有在读到被观察的状态时才会重组。普通 `var` 改完界面不会变，
     *   闸门就等于没接。用 `mutableStateOf` 才能「改了就生效」。
     */
    private class ComposeBlockable : Blockable {
        override var blocking by mutableStateOf(false)
        // cancelPendingTouch 用接口默认实现（空）：
        //   View 侧要靠它给子树补发 ACTION_CANCEL；Compose 侧由 pointerInput 的
        //   key 变化取消协程作用域自动完成，无需额外动作。
    }

    @Composable
    fun HomeUiContent(act: MainActivity, tab: Int, onPickTab: (Int) -> Unit) {
        // 【抽屉进度】直接复用 ProfileDrawer 已有的状态机，不另建一份真源。
        val drawerProgress by ProfileDrawer.progress

        // ---------- 交互闸门（Compose 侧）----------
        // 【为什么必须有这一层】旧装配把三页 / 底栏 / 抽屉各包一个 LayerShield，
        //   由 LayerGate 统一判定「谁独占触摸」。收成单一 ComposeView 后，如果只是
        //   删掉那 4 层 shield，就会丢掉「抽屉打开时底座被屏蔽」这条 ——
        //   表现为点抽屉空白处会穿透到底下的页面（用户报过的「点错层」）。
        //   这里用 ComposeLayerShield + ComposeBlockable 等价替代。
        val baseGate = remember { ComposeBlockable() }
        val barGate = remember { ComposeBlockable() }
        val drawerGate = remember { ComposeBlockable() }
        DisposableEffect(act) {
            val layers = LayerGate.layersOf(act)
            layers.base = baseGate
            layers.bar = barGate
            layers.drawer = drawerGate
            // 立刻判定一次：新装配完时抽屉通常是关的，底座应放行。
            LayerGate.refresh(act)
            onDispose {
                // 【必须解绑】否则旧 Activity 的 holder 会一直挂在单例表里，
                //   下次 refresh 会去改组已经被销毁的界面。
                if (layers.base === baseGate) layers.base = null
                if (layers.bar === barGate) layers.bar = null
                if (layers.drawer === drawerGate) layers.drawer = null
            }
        }

        // 【可见性】只有当前 Tab 的页 alpha=1；其余保持 0（仍在组合内，状态不丢）。
        Box(modifier = Modifier.fillMaxSize()) {
            // 【三页常驻】用 Layout 显式控制绘制顺序：当前 Tab 最后画（压在最上）。
            //   【必须 alpha=0 隐藏非当前页】只调绘制顺序是不够的 —— 三页都是不透明铺满，
            //   叠在一起会互相盖住、且非当前页仍会响应自己的内部滚动与点击（Compose 的
            //   命中测试不看 alpha）。原实现用 visibility=GONE，这里等价地用 alpha。
            //   【为什么不用 if (tab == X) 直接条件渲染】那样非当前页会离开组合，
            //   `remember` 里的状态（如 ChatListScreen 的搜索词 query）被销毁 ——
            //   切回来搜索框就空了，属于功能退化。用 alpha 才能「不显示但状态仍在」。
            //
            // 【闸门包裹】三页属 BASE 态，共用一个闸门（与旧装配的 host 等价）。
            //   边缘右滑只在这一层启用：覆盖页有自己的左缘返回手势，两处同时开会互相抢。
            ComposeLayerShield(
                blocking = baseGate.blocking,
                modifier = Modifier.fillMaxSize(),
                edgeSwipeEnabled = true,
                edgeMargin = 8.dp,
                edgeWidth = 32.dp,
                onSwipeStart = { ProfileDrawer.beginDrag() },
                onSwipeMove = { p -> ProfileDrawer.dragTo(p) },
                onSwipeEnd = { _, p ->
                    ProfileDrawer.dragTo(p)
                    ProfileDrawer.settleByPosition()
                }
            ) {
            Layout(
                content = {
                    key(AppNav.TAB_CHAT) {
                        Box(modifier = Modifier.alpha(if (tab == AppNav.TAB_CHAT) 1f else 0f)) {
                            ChatListScreen.Content(act)
                        }
                    }
                    key(AppNav.TAB_CONTACT) {
                        Box(modifier = Modifier.alpha(if (tab == AppNav.TAB_CONTACT) 1f else 0f)) {
                            ContactScreen.ContentForHost(act)
                        }
                    }
                    key(AppNav.TAB_HOME) {
                        Box(modifier = Modifier.alpha(if (tab == AppNav.TAB_HOME) 1f else 0f)) {
                            HomeScreen.HomeContentForHost(act)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints) }
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                layout(w, h) {
                    // 【顺序】非当前页先放（在下面），当前页最后放（在最上面）。
                    val order = placeables.indices.sortedBy { if (it == tab) 1 else 0 }
                    for (i in order) {
                        placeables[i].place(0, 0)
                    }
                }
            }
            }
            // 【底栏】浮在最上、不占布局高度：底对齐 + 内容包裹。
            //   与旧装配（buildBar 里那个 FrameLayout + wrap_content）等价：
            //   只有那条胶囊挡触摸，其余区域让给下面的页面。
            // 【底栏也走闸门】它属 BASE 态：抽屉或覆盖页出来时必须一起暂停，
            //   否则「点抽屉空白处」会落到它身上。
            ComposeLayerShield(
                blocking = barGate.blocking,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(androidx.compose.ui.Alignment.BottomCenter)
            ) {
                BottomNav.Bar(act, onPickTab)
            }
            // 【抽屉】必须最后画：它要盖住三页与底栏。
            //   ProfileDrawer.Host 内部在未打开时 `if (!opened) return` 空转，
            //   常驻代价只是一个空组合；换来点头像时零挂载延迟。
            // 【抽屉闸门】关闭时整层屏蔽（不能点到底下的页），打开时放行。
            //   由 LayerGate 单点判定，不再让抽屉自己镜像 isClickable（那是派生状态，
            //   重挂或某条路径漏同步就会脱节 —— 用户报过的「看得见点不动」）。
            ComposeLayerShield(blocking = drawerGate.blocking) {
                ProfileDrawer.Host(act)
            }
        }
    }

    /** 主题切换 / 比例调整后让常驻桌宠重读颜色（服务没在跑时静默忽略）。 */
    @JvmStatic
    fun notifyPetRefresh(ctx: Context) {
        try {
            if (!isServiceRunning(ctx)) {
                return
            }
            val i = Intent(ctx, PetService::class.java)
            i.action = PetService.ACTION_REFRESH
            ctx.startService(i)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }
}