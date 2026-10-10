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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.BackgroundCropActivity
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PetService
import com.dollhouse.app.PetTileService
import com.dollhouse.app.R
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.device.ShizukuBridge
import com.dollhouse.app.keepalive.KeepAliveBridge
import com.dollhouse.app.pet.GestureActions
import com.dollhouse.app.pet.GestureCore
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
 */
object HomeUi {
    private const val LOG_TAG = "Dollhouse"

    /** 页面 tag：与 `MainActivity.TAG_HOME_PAGE` / `TAG_SETTINGS_PAGE` 逐字一致（路由存储用）。 */
    const val TAG_HOME = "feiyu_home_page"
    const val TAG_SET = "feiyu_settings_page"

    /** 通知权限的运行时申请回调码。 */
    private const val REQ_NOTIF = 102
    /** Shizuku 授权请求码（结果经 ShizukuBridge 的 listener 回来）。 */
    private const val REQ_SHIZUKU = 103

    /** 复制出去的外部链接：同一条链接兼管开与关（PetLinkActivity 里按运行状态自行判断）。 */
    private const val T_CMD_LINK = "dollhouse://pet/toggle"

    /* ============================ 装配 ============================ */

    /**
     * 搭出「首页 + 设置页」的宿主。
     *
     * 【为什么改成两棵独立 ComposeView】原实现把两页控件搭在同一棵 ScrollView 里再拆开，
     *   拆的过程依赖索引 / 文本 / tag 三套契约。现在两页各自独立，靠 [show] 切可见性即可。
     */
    @JvmStatic
    fun apply(activity: Activity?) {
        if (activity == null || activity !is MainActivity) {
            return
        }
        try {
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
            content.removeAllViews()
            val host = android.widget.FrameLayout(activity)
            val home = HomeScreen.build(activity)
            home.tag = TAG_HOME
            val settings = SettingsScreen.build(activity)
            settings.tag = TAG_SET
            host.addView(home)
            host.addView(settings)
            content.addView(host)
            sync(activity)
            // 【路由恢复】主题切换重建时回到切换前那一页；冷启动回首页。
            val toSettings = PetPrefs.themeOnSettings(activity)
            show(activity, !toSettings)
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
        HomeState.bgMode = PetPrefs.bgMode(ctx)
        HomeState.bgBlur = PetPrefs.blurRadius(ctx)
        // 人偶
        HomeState.petScale = PetPrefs.petScaleDisplay(PetPrefs.petScale(ctx))
        // 记忆
        HomeState.memAuto = PetPrefs.memAuto(ctx)
        HomeState.memSave = PetPrefs.memAutoSave(ctx)
        HomeState.memMerge = PetPrefs.memAutoMerge(ctx)
        HomeState.memIndex = PetPrefs.memThresholdIndex(ctx)
        // 操作
        HomeState.setGesture(GestureCore.G_SINGLE, PetPrefs.gestureActions(ctx, GestureCore.G_SINGLE))
        HomeState.setGesture(GestureCore.G_DOUBLE, PetPrefs.gestureActions(ctx, GestureCore.G_DOUBLE))
        HomeState.setGesture(GestureCore.G_TRIPLE, PetPrefs.gestureActions(ctx, GestureCore.G_TRIPLE))
        HomeState.setGesture(GestureCore.G_LONG, PetPrefs.gestureActions(ctx, GestureCore.G_LONG))
        HomeState.awaySeconds = PetPrefs.awaySeconds(ctx)
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
     * 启停桌宠：点下即切目标态，随后轮询真实状态纠偏。
     * 【不改服务语义】起停仍走原有 Intent 通道（启动还含悬浮窗权限校验）。
     */
    @JvmStatic
    fun togglePet(act: Activity, wantStart: Boolean) {
        if (wantStart) {
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

    /** 打开全屏聊天页（带淡入，不用系统硬切）。 */
    @JvmStatic
    fun openChat(act: Activity) {
        if (!hasChatTarget(act)) {
            HomeState.chatAvailable = false
            return
        }
        try {
            act.startActivity(Intent(act, com.dollhouse.app.ChatActivity::class.java))
            act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /** 人偶比例增减：delta 为 -1 / +1，每档 10%。 */
    @JvmStatic
    fun stepPetScale(act: Activity, delta: Int) {
        val cur = PetPrefs.petScale(act)
        PetPrefs.setPetScale(act, cur + delta * PetPrefs.PET_SCALE_STEP)
        HomeState.petScale = PetPrefs.petScaleDisplay(PetPrefs.petScale(act))
        notifyPetRefresh(act)
    }

    /* ============================ 页面切换 ============================ */

    /**
     * 首页 / 设置页二选一：只改可见性 + 交叉淡入，不重建任何视图。
     * 【为什么要交叉】两页同宿主，只淡入目标页会让旧页透过来糊一下。
     */
    @JvmStatic
    fun show(activity: Activity, home: Boolean) {
        try {
            val homeView = activity.findViewById<View>(android.R.id.content)?.findViewWithTag<View>(TAG_HOME)
            val setView = activity.findViewById<View>(android.R.id.content)?.findViewWithTag<View>(TAG_SET)
            if (homeView == null || setView == null) {
                return
            }
            // 【返回同步】切回首页时刷新「打开聊天」入口：供应商 / 模型增删启停后返回即生效。
            if (home) {
                HomeState.chatAvailable = hasChatTarget(activity)
            }
            if (PetPrefs.themeRestore(activity)) {
                // 换主题重建：直接落到目标页，不重播动画，免得又闪一次。
                homeView.visibility = if (home) View.VISIBLE else View.GONE
                setView.visibility = if (home) View.GONE else View.VISIBLE
                return
            }
            showDiff(
                if (home) homeView else setView,
                if (home) setView else homeView
            )
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /** 目标页淡入 + 旧页同步淡出。 */
    private fun showDiff(inView: View, out: View) {
        inView.visibility = View.VISIBLE
        inView.alpha = 0f
        out.alpha = 1f
        inView.animate().alpha(1f).setDuration(UiKit.D_LAYER.toLong())
            .setInterpolator(UiKit.EASE_DECEL)
            .withEndAction {
                if (out.alpha < 0.05f) {
                    out.visibility = View.GONE
                }
            }.start()
        out.animate().alpha(0f).setDuration(UiKit.D_MICRO.toLong())
            .setInterpolator(UiKit.EASE_ACCEL).start()
    }

    /** 返回键：设置页 -> 首页。返回 true 表示这次返回已被消费。 */
    @JvmStatic
    fun handleBack(activity: Activity): Boolean {
        try {
            val setView = activity.findViewById<View>(android.R.id.content)?.findViewWithTag<View>(TAG_SET)
                ?: return false
            if (setView.visibility != View.VISIBLE) {
                return false
            }
            show(activity, true)
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
            act.startActivity(Intent(act, BackgroundCropActivity::class.java))
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
        row.background = UiKit.rowBg(act, if (selected) UiKit.OPTION else UiKit.SOFT)
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
        row.background = UiKit.rowBg(act, if (selected) UiKit.OPTION else UiKit.SOFT)
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

    /* ============================ 操作卡片（手势 / 暂离） ============================ */

    /** 手势响应多选：无 / 交谈 / 聊天 / 继续 / 暂离；点一项立即保存并就地刷新选中态。 */
    @JvmStatic
    fun pickGesture(act: Activity, gesture: String) {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val keys = arrayOf(
            null, GestureActions.A_TALK, GestureActions.A_CHAT,
            GestureActions.A_CONTINUE, GestureActions.A_AWAY
        )
        val labels = arrayOf(
            GestureActions.NONE_LABEL, GestureActions.TALK_LABEL,
            GestureActions.CHAT_LABEL, GestureActions.CONTINUE_LABEL,
            GestureActions.AWAY_LABEL
        )
        fun render() {
            val set = PetPrefs.gestureActions(act, gesture)
            col.removeAllViews()
            for (i in keys.indices) {
                val key = keys[i]
                val selected = if (key == null) set.isEmpty() else set.contains(key)
                col.addView(optionRow(act, labels[i], selected) {
                    val now = PetPrefs.gestureActions(act, gesture)
                    if (key == null) {
                        now.clear()
                    } else if (now.contains(key)) {
                        now.remove(key)
                    } else {
                        now.add(key)
                    }
                    PetPrefs.setGestureActions(act, gesture, now)
                    HomeState.setGesture(gesture, now.toSet())
                    render()
                })
            }
        }
        render()
        UiKit.showDialog(act, GestureActions.gestureName(gesture), col, null, null, null, null)
    }

    /** 「暂离」时长行：点开数字输入小卡片（默认 60，范围 0~3600）。 */
    @JvmStatic
    fun pickAwaySeconds(act: Activity) {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val edit = EditText(act)
        edit.typeface = Fonts.ui(act)
        UiKit.field(edit, act)
        edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        edit.isSingleLine = true
        edit.hint = "建议 0～60 秒"
        edit.setText(PetPrefs.awaySeconds(act).toString())
        col.addView(edit, LinearLayout.LayoutParams(-1, -2))
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                edit.error = null
            }
        })

        val btn = UiKit.dialogButton(act, "保存", true)
        val blp = LinearLayout.LayoutParams(-1, -2)
        blp.topMargin = UiKit.dp(act, 12f)
        btn.layoutParams = blp
        col.addView(btn)

        var dlg: Dialog? = null
        btn.setOnClickListener {
            val n = edit.text.toString().trim().toIntOrNull()
            if (n == null || n < 0 || n > 3600) {
                edit.error = "请输入 0～3600 的整数"
                return@setOnClickListener
            }
            PetPrefs.setAwaySeconds(act, n)
            HomeState.awaySeconds = n
            dlg?.dismiss()
        }
        dlg = UiKit.showDialog(act, "暂离时长", col, null, null, null, null)
    }

    /** 弹窗里的一个多选项：选中项淡紫底 + 加粗 + 右侧勾，固定 22dp 占位。 */
    private fun optionRow(act: Activity, text: String, selected: Boolean, onPick: () -> Unit): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isClickable = true
        row.background = UiKit.rowBg(act, if (selected) UiKit.OPTION else UiKit.SOFT)
        val pad = UiKit.dp(act, 12f)
        row.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(act, 8f)
        row.layoutParams = lp

        val t = TextView(act)
        t.text = text
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
        row.setOnClickListener { onPick() }
        return row
    }

    /* ============================ 桌宠刷新 ============================ */

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