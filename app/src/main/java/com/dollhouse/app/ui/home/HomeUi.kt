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
import android.view.Gravity
import android.view.View
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
import com.dollhouse.app.ui.theme.Fonts
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.ThemeRefresh
import com.dollhouse.app.ui.theme.UiKit

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
            // 【r21·框架级】底座三层（Tab）+ 底栏 + 抽屉全部包进 [LayerShield]。
            //   【为什么必须在层根包一层】触摸屏蔽只能在 ViewGroup 的 dispatchTouchEvent 上做；
            //   给层根改 isClickable / 挂 OnTouchListener 是无效做法
            //   （ViewGroup 先把事件发给子 View，子按钮命中就消费了，根本轮不到自己）。
            //   详见 [com.dollhouse.app.ui.compose.LayerShield] 的类注释。
            //   【为什么这层是「三 Tab 的公共外壳」】三个 Tab 同属 BASE 态，
            //   共用一个闸门即可，不必各包一层。
            val host = com.dollhouse.app.ui.compose.LayerShield(activity)
            // 【顺序即层级】聊天 → 人偶 → 主页 → 底栏：后加的盖在上面。
            val chat = ChatListScreen.buildPage(activity)
            chat.tag = TAG_CHAT
            val contact = ContactScreen.buildPage(activity)
            contact.tag = TAG_CONTACT
            val home = HomeScreen.build(activity)
            home.tag = TAG_HOME
            host.addView(chat)
            host.addView(contact)
            host.addView(home)
            // 【底座层的边缘右滑】只在这一层启用：主页右滑打开抽屉。
            //   · 起手区左边界 = 系统手势区右缘 + 呼吸间距 → 不与系统「返回」手势抢；
            //   · 起手区右边界 = 左边界 + 32dp → 只在屏幕左侧起手才算，页面中间横滑不受影响。
            //   【为什么在底座而不是整个 content】覆盖页有自己的左边缘返回手势，
            //   两处同时开必然互相抢。
            host.edgeSwipeEnabled = true
            host.edgeMargin = dp(activity, 8f)
            host.edgeWidth = dp(activity, 32f)
            host.onSwipeStart = {
                // 手势认主的瞬间就让抽屉参与渲染（progress 从 0 起，跟手推进）。
                ProfileDrawer.beginDrag()
            }
            host.onSwipeMove = { dx ->
                ProfileDrawer.dragTo(dx / dp(activity, 300f))
            }
            host.onSwipeEnd = { dx, _ ->
                // 位移超过面板 1/3 就打开，否则收起（与点遮罩 / 点头像同一收尾逻辑）。
                ProfileDrawer.dragTo(dx / dp(activity, 300f))
                ProfileDrawer.settleByPosition()
            }
            content.addView(host)
            // 【底栏】必须最后加：它是 content 的直接子（不放进 host），
            //   这样它盖在整块页面区之上，且自身的 navigationBarsPadding 能正确取到 insets。
            //   【为什么不放进 host】host 里三个页面会做 translateX 转场，底栏跟着位移会很怪。
            val bar = buildBar(activity)
            bar.tag = TAG_BAR
            // 【底栏也包一层闸门】它属于 BASE 态：抽屉/覆盖页出来时必须一起暂停，
            //   否则「点抽屉空白处」会落到它身上（旧 bug 的另一半）。
            //   【为什么闸门高度用 wrap_content + 底对齐】底栏是「浮在内容之上且不占布局高度」的，
            //   只有这样才保持「只有那条胶囊挡触摸、其余区域让给下面页面」的原有手感。
            val barShield = com.dollhouse.app.ui.compose.LayerShield(activity)
            barShield.addView(bar, android.widget.FrameLayout.LayoutParams(-1, -2))
            val barLp = android.widget.FrameLayout.LayoutParams(-1, -2)
            barLp.gravity = android.view.Gravity.BOTTOM
            content.addView(barShield, barLp)
            // 【r14·我的资料抽屉】必须**最后**加：它要盖住三页与底栏。
            //   为什么常驻：抽屉自身 `if (!opened) return` 空转，代价只是一个空 ComposeView；
            //   换来的好处是点头像时零挂载延迟（现挂会有一帧空白）。
            val drawer = com.dollhouse.app.ui.compose.ComposeHost.createView(activity) {
                ProfileDrawer.Host(activity)
            }
            drawer.tag = TAG_PROFILE
            // 【r21·框架级】抽屉这层也包一层闸门。
            //   改造前这里是「把宿主 View 的 isClickable 交给 ProfileDrawer 自己镜像」，
            //   那是派生状态，重挂或某条路径漏同步就会脱节（用户报过的「看得见点不动」）。
            //   现在由 [LayerGate] 单点判定：抽屉关闭时整层屏蔽、打开时整层放行。
            val drawerShield = com.dollhouse.app.ui.compose.LayerShield(activity)
            drawerShield.addView(
                drawer,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            drawerShield.isClickable = false
            drawerShield.isFocusable = false
            drawerShield.blocking = true
            // 登记宿主，供 [ProfileDrawer] 开合后通知 gate 重算。
            ProfileDrawer.bindHost(activity)
            content.addView(
                drawerShield,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // 【r17·导航迁移】装上 Navigation 3 渲染层（覆盖层页面栈）。
            //   【层级】必须放在底座与抽屉**之后** —— 后加的盖在上面，故覆盖层在最顶。
            //   这符合预期语义「覆盖页独占屏幕」：栈非空时它盖住三个 Tab、底栏与抽屉。
            //   【栈空时零影响】`NavHost` 内部在「只有底座」时什么都不画
            //   （见 `NavHost.install` 的 `stack.size > 1` 守卫），
            //   此时它是一层不消费触摸的空 ComposeView，抽屉与底栏照常工作。
            //   【抽屉不需要盖在覆盖层之上】抽屉的唤出入口是聊天列表页左上角头像，
            //   而聊天列表页属于底座；覆盖页开着时用户根本看不到那个入口，
            //   两者不会同时需要交互，无需把抽屉提到最顶。
            com.dollhouse.app.ui.nav.NavHost.install(activity)
            // 【r21】登记四层到 [LayerGate] 并立刻判定一次。
            //   必须在 NavHost.install 之后：它自己会创建 NAV 层的外壳并登记。
            val layers = com.dollhouse.app.ui.compose.LayerGate.layersOf(activity)
            layers.base = host
            layers.bar = barShield
            layers.drawer = drawerShield
            com.dollhouse.app.ui.compose.LayerGate.refresh(activity)
            sync(activity)
            // 【路由恢复】冷启动进「上次停留的 Tab」；第一次启动时偏好里没有记录，
            //   [AppNav] 会落到默认值（聊天），即需求要求的「默认页面改为聊天列表」。
            AppNav.restore(activity)
            showTab(activity, AppNav.current)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /**
     * 造底栏。
     *
     * 【为什么用 FrameLayout 包一层 ComposeView】底栏要「浮在内容之上且不占布局高度」，
     *   所以外层必须是内容高度包裹（wrap_content）+ 底对齐，
     *   这样只有真正的那一条胶囊挡触摸，其余区域点击穿透到下面的页面。
     */
    private fun buildBar(act: MainActivity): View {
        val wrap = android.widget.FrameLayout(act)
        val lp = android.widget.FrameLayout.LayoutParams(-1, -2)
        lp.gravity = android.view.Gravity.BOTTOM
        wrap.layoutParams = lp
        wrap.isClickable = false
        val cv = com.dollhouse.app.ui.compose.ComposeHost.createView(act) {
            BottomNav.Bar(act) { tab ->
                // 【切页收尾】切 Tab 时把覆盖层全弹掉。
                //   理由：覆盖层（人偶列表 / 编辑页 / 设置页）是从某个 Tab 里点进去的，
                //   用户切到另一个 Tab 说明他换了意图，把旧页留在栈里会让返回键
                //   把他送回「上一个 Tab 的子页」，与直觉相反（主流 App 都是切 Tab 即回栈底）。
                clearLayers(act)
                showTab(act, tab)
            }
        }
        wrap.addView(cv, android.widget.FrameLayout.LayoutParams(-1, -2))
        return wrap
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
     *   这里走 [showShizukuDialog] 的同一条合法通道：原生 Dialog + 标题 + 正文 + 「知道了」。
     */
    @JvmStatic
    fun showNotice(ctx: Context, title: String, message: String) {
        val act = UiKit.findActivity(ctx) ?: return
        // 同类提示只保留一个，连续点击不会叠出一串对话框。
        if (noticeDialog?.isShowing == true) return
        noticeDialog = UiKit.showDialog(act, title, UiKit.dialogMessage(act, message), "知道了", null, null, null)
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
            val content = activity.findViewById<View>(android.R.id.content) ?: return
            val chat = content.findViewWithTag<View>(TAG_CHAT)
            val contact = content.findViewWithTag<View>(TAG_CONTACT)
            val home = content.findViewWithTag<View>(TAG_HOME)
            // 【兜底】三页缺任何一个就直接按可见性硬切（不播动画）。
            if (chat == null || contact == null || home == null) {
                return
            }
            // 【返回同步】切到主页（或聊天）时刷新「打开聊天」入口：
            //   供应商 / 模型增删启停后返回即生效。
            if (tab == AppNav.TAB_HOME || tab == AppNav.TAB_CHAT) {
                HomeState.chatAvailable = hasChatTarget(activity)
            }
            val target = when (tab) {
                AppNav.TAB_CONTACT -> contact
                AppNav.TAB_HOME -> home
                else -> chat
            }
            // 【短路径】换主题重建 / 冷启动：直接落到目标页，不重播动画，免得又闪一次。
            if (PetPrefs.themeRestore(activity) || !barReady(activity)) {
                for (v in arrayOf(chat, contact, home)) {
                    v.visibility = if (v === target) View.VISIBLE else View.GONE
                    v.alpha = 1f
                }
                return
            }
            // 【修 bug·三页同时可见】必须**逐个**处理全部非目标页，不能找到一个就收工。
            //
            // 【曾经的 bug】原实现是：
            //   ```
            //   for (v in 三页) {
            //       if (v !== target && v.visibility == VISIBLE) {
            //           showDiff(target, v); return      ← 只处理了第一个就返回
            //       }
            //   }
            //   ```
            //   于是「聊天 + 主页」同时可见时切到人偶，只会把聊天淡出，
            //   **主页还留在屏幕上** —— 三页叠在一起（用户截图里那个样子）。
            //   触发条件正是「切后台再回来」：`onResume` 的 `reconcile` 会把曾被打断的
            //   淡出动画补回终态，导致多于一个页面处于 VISIBLE。
            //
            // 【正确做法】淡出用「第一个找到的非目标页」（它承载交叉动画），
            //   其余非目标页**立即 GONE**（它们本来就该是不可见的，没有动画可言）。
            var fading: View? = null
            for (v in arrayOf(chat, contact, home)) {
                if (v === target) {
                    continue
                }
                if (v.visibility != View.VISIBLE) {
                    continue
                }
                if (fading == null) {
                    fading = v
                } else {
                    // 【兜底硬收】多于一个非目标页可见 = 上一次切页的淡出被打断。
                    //   直接置 GONE，不留动画 —— 否则又会出现「看不见却还挡着」的幽灵页。
                    v.animate().cancel()
                    v.visibility = View.GONE
                    v.alpha = 1f
                }
            }
            if (fading != null) {
                showDiff(target, fading)
            } else {
                // 目标页本来就是唯一可见的（重复点同一个 Tab）：什么都不做。
                target.visibility = View.VISIBLE
                target.alpha = 1f
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /**
     * 【r13·修 bug·三页同时可见】把三个 Tab 页强行收成「只有目标页可见」。
     *
     * 【为什么单独抽一个方法】`showTab` 依赖「当前只有一个页面可见」这个前提来决定
     *   该淡出谁；而这个前提会被下面几条路径打破：
     *   ① 切页动画正播到一半被切到后台（`withEndAction` 不执行，旧页停在 alpha≈0 但 VISIBLE）；
     *   ② 厂商 ROM「清理后台」只摘任务不销毁进程，回来时 View 树原样保留；
     *   `PageStack.reconcile` 的规则 C 会把「栈顶那层」补回可见终态，
     *   但它只管**覆盖层**，管不到底座这三个 Tab 页 —— 于是多页同时可见。
     *
     * 【为什么不直接调 showTab】那会播一次交叉淡入动画；回前台应该「静止在终点」。
     *   这里只做**状态归位**，零动画、零副作用，可在 `onResume` 无条件调用（幂等）。
     */
    @JvmStatic
    fun settleTabs(activity: Activity) {
        try {
            val content = activity.findViewById<View>(android.R.id.content) ?: return
            val chat = content.findViewWithTag<View>(TAG_CHAT) ?: return
            val contact = content.findViewWithTag<View>(TAG_CONTACT) ?: return
            val home = content.findViewWithTag<View>(TAG_HOME) ?: return
            val target = when (AppNav.current) {
                AppNav.TAB_CONTACT -> contact
                AppNav.TAB_HOME -> home
                else -> chat
            }
            for (v in arrayOf(chat, contact, home)) {
                v.animate().cancel()
                if (v === target) {
                    v.visibility = View.VISIBLE
                    v.alpha = 1f
                } else {
                    v.visibility = View.GONE
                    v.alpha = 1f
                }
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /**
     * 底栏是否已经装配好。
     *
     * 【用途】冷启动首帧里 [apply] 会调 [showTab]，那时三个页面刚 addView 完、还没布局；
     *   此判据让首帧走「直接设可见性」的快路径，不播动画（首帧播动画会闪一下）。
     */
    private fun barReady(activity: Activity): Boolean {
        val content = activity.findViewById<View>(android.R.id.content) ?: return false
        return content.findViewWithTag<View>(TAG_BAR) != null
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
     * 目标页淡入 + 旧页同步淡出。
     *
     * 【r10 加固·旧页必须真的收掉】原实现把「置 GONE」挂在入页动画的 `withEndAction` 上，
     *   而 `withEndAction` 在动画被 `cancel()`（切后台、被系统掐帧、又切了一次页）时
     *   **不会执行**：旧页就停在 `alpha ≈ 0` 却仍 `VISIBLE` 的状态，看不见却照旧拦触摸 ——
     *   正是「视觉停在主页、触摸仍落二级页」的成因之一。
     *   现在改为：旧页自己的动画结束回调 + 一个延时兜底，两条路都调 [hideOut]。
     */
    private fun showDiff(inView: View, out: View) {
        inView.visibility = View.VISIBLE
        inView.alpha = 0f
        out.alpha = 1f
        val duration = UiKit.pageTransitionDuration()
        inView.animate().alpha(1f).setDuration(duration)
            .setInterpolator(UiKit.EASE_STD).start()
        out.animate().alpha(0f).setDuration(duration)
            .setInterpolator(UiKit.EASE_STD)
            .withEndAction { hideOut(out) }.start()
        // 动画被取消时也要收掉旧页；重复隐藏由 hideOut 幂等处理。
        out.postDelayed({ hideOut(out) }, duration + 120L)
    }

    /**
     * 把淡出的旧页真正收起来。
     * 【幂等】已经 GONE 直接返回。
     * 【防误伤】只收「已经淡到看不见」的页：若这期间它又被切回来（`alpha` 被重新拉到 1），
     *   说明它现在是当前页，绝不能收。收掉时把 `alpha` 归位成 1，
     *   免得它下次作为入页时带着 0 显示不出来。
     */
    private fun hideOut(out: View) {
        if (out.visibility == View.GONE) {
            return
        }
        if (out.alpha > 0.05f) {
            return
        }
        out.visibility = View.GONE
        out.alpha = 1f
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

    /** Shizuku 说明弹窗（无 Toast 约束下的唯一合法反馈通道）。 */
    private fun showShizukuDialog(ctx: Context, message: String) {
        val act = UiKit.findActivity(ctx) ?: return
        UiKit.showDialog(act, "Shizuku 授权", UiKit.dialogMessage(act, message), "知道了", null, null, null)
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
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
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

    /** 卡片透明度说明文案。 */
    @JvmStatic
    fun cardAlphaHint(percent: Int): String {
        return "卡片透明度\u00a0\u00a0" + (if (percent >= 95) {
            "（默认，卡片最实）"
        } else if (percent >= 60) {
            "（略透，能看见背景）"
        } else if (percent >= 25) {
            "（很透，背景明显）"
        } else {
            "（几乎全透，字可能看不清）"
        })
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
        return "遮罩强度\u00a0\u00a0" + (if (percent >= 95) {
            "（默认，按可读性自动压暗）"
        } else if (percent >= 55) {
            "（略淡，图更清楚）"
        } else if (percent >= 20) {
            "（很淡，注意看字）"
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

    /** 背景透明度说明文案（原 `MainActivity.updateBgAlphaLabel` 的口径）。 */
    @JvmStatic
    fun alphaHint(percent: Int): String {
        return "背景图透明度\u00a0\u00a0" + (if (percent <= 8) {
            "（几乎看不见了）"
        } else if (percent <= 45) {
            "（推荐，字最清楚）"
        } else if (percent <= 75) {
            "（图更明显，注意看字）"
        } else {
            "（原图，气泡可能和背景糊在一起）"
        })
    }

    /**
     * 主题模式选择面板：四档单选，选完写偏好并立即就地换色。
     * 【为什么弹窗仍用 View】`UiKit.showDialog` 是全 App 统一的弹窗通道，
     *   单为一个单选面板再造一套 Compose 弹窗会破坏「弹窗长得一样」。
     */
    @JvmStatic
    fun pickThemeMode(act: Activity) {
        val cur = ThemeManager.mode(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val holder = arrayOfNulls<Dialog>(1)
        for (i in ThemeManager.MODE_NAMES.indices) {
            col.addView(modeOption(act, i, i == cur, cur, holder))
        }
        holder[0] = UiKit.showDialog(act, "主题模式", col, null, null, null, null)
    }

    /**
     * 背景填充模式选择面板：四档单选，选完写偏好并立即重刷背景。
     * 【为什么弹窗仍用 View】与 [pickThemeMode] 同一条理由：`UiKit.showDialog` 是全 App
     *   统一的弹窗通道，单为一个单选面板再造一套 Compose 弹窗会破坏「弹窗长得一样」。
     */
    @JvmStatic
    fun pickBgMode(act: Activity) {
        val cur = PetPrefs.bgMode(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val holder = arrayOfNulls<Dialog>(1)
        for (i in PetPrefs.BG_MODES.indices) {
            col.addView(bgModeOption(act, i, i == cur, cur, holder))
        }
        holder[0] = UiKit.showDialog(act, "背景填充", col, null, null, null, null)
    }

    /** 单个背景填充选项：选中项淡紫底 + 加粗 + 右侧勾，点选后关窗并立即重刷背景。 */
    private fun bgModeOption(
        act: Activity, idx: Int, selected: Boolean,
        cur: Int, holder: Array<Dialog?>
    ): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isClickable = true
        row.background = UiKit.rowBg(act, if (selected) UiKit.option() else UiKit.soft())
        val pad = UiKit.dp(act, 12f)
        row.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(act, 8f)
        row.layoutParams = lp

        val t = TextView(act)
        t.text = PetPrefs.BG_MODES[idx]
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        t.typeface = if (selected) Fonts.uiBold(act) else Fonts.ui(act)
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))

        // 【坑·必看】占位必须用固定尺寸 View：裸 View 没有内容，onMeasure 会取满父给的
        //   可用高度，未选中项被撑到近整屏。
        val mark: View = if (selected) {
            Icons.view(act, Icons.IC_CHECK, 16.0f, UiKit.ACC)
        } else {
            View(act)
        }
        row.addView(mark, LinearLayout.LayoutParams(UiKit.dp(act, 22f), UiKit.dp(act, 22f)))

        UiKit.press(row)
        row.setOnClickListener {
            holder[0]?.dismiss()
            if (idx == cur) {
                return@setOnClickListener
            }
            PetPrefs.setBgMode(act, idx)
            HomeState.bgMode = idx
            GlobalBackground.refreshAll()
        }
        return row
    }

    /** 单个主题模式选项：选中项淡紫底 + 加粗 + 右侧勾，点选后关窗并立即应用。 */
    private fun modeOption(
        act: Activity, idx: Int, selected: Boolean,
        cur: Int, holder: Array<Dialog?>
    ): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isClickable = true
        row.background = UiKit.rowBg(act, if (selected) UiKit.option() else UiKit.soft())
        val pad = UiKit.dp(act, 12f)
        row.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(act, 8f)
        row.layoutParams = lp

        val t = TextView(act)
        t.text = ThemeManager.MODE_NAMES[idx]
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        t.typeface = if (selected) Fonts.uiBold(act) else Fonts.ui(act)
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))

        // 【坑·必看】占位必须用固定尺寸 View：裸 View 没有内容，onMeasure 会取满父给的
        //   可用高度，未选中项被撑到近整屏（「主题模式点开没法正常选择」的真根因）。
        val mark: View = if (selected) {
            Icons.view(act, Icons.IC_CHECK, 16.0f, UiKit.ACC)
        } else {
            View(act)
        }
        row.addView(mark, LinearLayout.LayoutParams(UiKit.dp(act, 22f), UiKit.dp(act, 22f)))

        UiKit.press(row)
        row.setOnClickListener {
            holder[0]?.dismiss()
            if (idx == cur) {
                return@setOnClickListener
            }
            ThemeManager.setMode(act, idx)
            applyThemeNow(act)
        }
        return row
    }

    /**
     * 【r14·外观 Tab①】「从壁纸取色」：四档取色来源的单选面板。
     *
     * 【与「莫奈主题色」的区别】莫奈是根据**当前色系**生成一整套柔和配色；
     *   本项是「照抄壁纸主色」——由系统壁纸服务提取，取到后写进 [PetPrefs.themeMonetWall]。
     *
     * 【为什么保留弹窗形态】与 [pickThemeMode] / [pickBgMode] 同一条理由：
     *   `UiKit.showDialog` 是全 App 统一的弹窗通道，单为这个面板再造一套会破坏「弹窗长得一样」。
     */
    @JvmStatic
    fun pickMonetWall(act: Activity) {
        val names = ThemeManager.WALL_NAMES
        val cur = PetPrefs.themeMonetWall(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val holder = arrayOfNulls<Dialog>(1)
        for (i in names.indices) {
            col.addView(monetWallOption(act, i, i == cur, cur, holder))
        }
        holder[0] = UiKit.showDialog(act, "从壁纸取色", col, null, null, null, null)
    }

    /** 单个取色来源选项：选中项淡紫底 + 加粗 + 右侧勾，点选后关窗并重取色。 */
    private fun monetWallOption(
        act: Activity, idx: Int, selected: Boolean,
        cur: Int, holder: Array<Dialog?>
    ): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isClickable = true
        row.background = UiKit.rowBg(act, if (selected) UiKit.option() else UiKit.soft())
        val pad = UiKit.dp(act, 12f)
        row.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(act, 8f)
        row.layoutParams = lp

        val t = TextView(act)
        t.text = ThemeManager.WALL_NAMES[idx]
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        t.typeface = if (selected) Fonts.uiBold(act) else Fonts.ui(act)
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))

        // 【坑·必看】占位必须用固定尺寸 View：裸 View 没有内容，onMeasure 会取满父给的
        //   可用高度，未选中项被撑到近整屏（与 modeOption 同一处坑）。
        val mark: View = if (selected) {
            Icons.view(act, Icons.IC_CHECK, 16.0f, UiKit.ACC)
        } else {
            View(act)
        }
        row.addView(mark, LinearLayout.LayoutParams(UiKit.dp(act, 22f), UiKit.dp(act, 22f)))

        UiKit.press(row)
        row.setOnClickListener {
            holder[0]?.dismiss()
            if (idx == cur) {
                return@setOnClickListener
            }
            // 壁纸取色是后台任务（要解一张整屏大图），交给 ThemeManager 的异步入口。
            ThemeManager.setMonetWall(act, idx)
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
        return row
    }

    /**
     * 【r14·外观 Tab③】气泡宽度三档单选（窄 / 适中 / 宽）。
     *
     * 【为什么存档位而不是像素】见 [PetPrefs.bubbleWidth] 的注释：
     *   存像素会在不同分辨率 / 字号下撑爆屏幕或小得看不见。
     */
    @JvmStatic
    fun pickBubbleWidth(act: Activity) {
        val names = com.dollhouse.app.ui.settings.AppearancePage.BUBBLE_WIDTH_NAMES
        val cur = PetPrefs.bubbleWidth(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val holder = arrayOfNulls<Dialog>(1)
        for (i in names.indices) {
            col.addView(bubbleWidthOption(act, i, i == cur, cur, holder))
        }
        holder[0] = UiKit.showDialog(act, "气泡宽度", col, null, null, null, null)
    }

    private fun bubbleWidthOption(
        act: Activity, idx: Int, selected: Boolean,
        cur: Int, holder: Array<Dialog?>
    ): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isClickable = true
        row.background = UiKit.rowBg(act, if (selected) UiKit.option() else UiKit.soft())
        val pad = UiKit.dp(act, 12f)
        row.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(act, 8f)
        row.layoutParams = lp

        val t = TextView(act)
        t.text = com.dollhouse.app.ui.settings.AppearancePage.BUBBLE_WIDTH_NAMES[idx]
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        t.typeface = if (selected) Fonts.uiBold(act) else Fonts.ui(act)
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))

        val mark: View = if (selected) {
            Icons.view(act, Icons.IC_CHECK, 16.0f, UiKit.ACC)
        } else {
            View(act)
        }
        row.addView(mark, LinearLayout.LayoutParams(UiKit.dp(act, 22f), UiKit.dp(act, 22f)))

        UiKit.press(row)
        row.setOnClickListener {
            holder[0]?.dismiss()
            if (idx == cur) {
                return@setOnClickListener
            }
            PetPrefs.setBubbleWidth(act, idx)
            HomeState.bubbleWidth = idx
            // 【必须 bump】气泡宽度是 Compose 侧在读；它是普通字段赋值，不会触发重组。
            ThemeRefresh.bumpAll()
        }
        return row
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