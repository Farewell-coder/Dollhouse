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
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.BackgroundCropActivity
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PetService
import com.dollhouse.app.PetTileService
import com.dollhouse.app.R
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.device.ShizukuBridge
import com.dollhouse.app.keepalive.KeepAliveBridge
import com.dollhouse.app.ui.settings.AboutPage
import com.dollhouse.app.ui.settings.MemPage
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】首页与设置页的装配与状态同步：把 buildUi() 产出的长列表拆成首页 / 设置页两页。
 *
 * 【入口】MainActivity 装配主界面时调用；页内按钮回调触发状态刷新。
 *
 * 【交互】卡片与控件工厂走 HomeCards（详见 HomeCards）；折叠与卡片清单走 SettingsPage / SettingsRegistry；
 *         启动 / 停止经 Intent 发给 PetService；权限与开关状态读 PetPrefs。
 *
 * 【坑】不新建 Activity、不改清单；结构不符合预期就整体放弃，界面退回原样
 *       （TAG_HOME / TAG_SET 找不到就当没这回事）——改页面结构时务必保证这两个 tag 还在。
 *
 * 首页   = 标题 + 副标题 + 人偶 + 状态 + 启动/关闭 + 打开聊天 + 设置
 * 设置页 = [← 设置] 头部 + 卡片(权限 / 聊天与Token / 外观 / 记忆 / 操作 / 关于)
 */
object HomeUi {
    private const val LOG_TAG = "Dollhouse"
    private const val TAG_HOME = "feiyu_home_page"
    private const val TAG_SET = "feiyu_settings_page"

    /** 滚动位置的存储键（首页 / 设置页各一份）。 */
    private const val KEY_HOME = "home"
    private const val KEY_SET = "set"
    private const val TAG_TOGGLE = "feiyu_home_toggle"
    private const val TAG_PETS = "feiyu_home_pets"
    private const val TAG_PERM_NOTIF = "feiyu_perm_notif"

    /** Shizuku 授权行的 View tag：四态文案由 ShizukuBridge 提供，不走 bindPermRow 的两态常量。 */
    private const val TAG_PERM_SHIZUKU = HomeCards.TAG_PERM_SHIZUKU

    /** 第一级开关：隐藏后台（最近任务）卡片。 */
    private const val TAG_HIDE_RECENTS = "feiyu_hide_recents"

    /** 通知权限的运行时申请回调码。 */
    private const val REQ_NOTIF = 102

    /** Shizuku 授权请求码（结果经 ShizukuBridge 的 listener 回来）。 */
    private const val REQ_SHIZUKU = 103

    /** 卡片标题：旧分组。 */
    private const val T_WEB = "\u8054\u7f51\u641c\u7d22"
    private const val T_LEARN = "\u5b66\u4e60\uff08\u70b9\u8d5e \u2192 \u793a\u8303\uff09"
    private const val T_ICON = "\u5e94\u7528\u56fe\u6807"
    private const val T_CHAT_OLD = "\u804a\u5929\u8bbe\u7f6e\uff08\u4e91\u7aef API\uff09"
    private const val T_OP_OLD = "\u64cd\u4f5c\u65b9\u5f0f"
    private const val T_BG = "\u804a\u5929\u80cc\u666f"
    private const val T_FOOTER = "\u8bf4\u660e\uff1a"

    /** 卡片标题：新框架。 */
    private const val T_PERM = "\u6743\u9650"

    /** 权限卡片内两行子项的左侧名称。 */
    private const val T_PERM_OVERLAY_NAME = "\u60ac\u6d6e\u7a97\u6743\u9650"
    private const val T_PERM_NOTIF_NAME = "\u901a\u77e5\u6743\u9650"

    /** Shizuku 授权行（权限卡片下级第一项）：让本应用与 AI 拿到 adb shell 级能力。 */
    private const val T_PERM_SHIZUKU_NAME = "Shizuku \u6388\u6743"

    /** 权限卡片下级：保活分组的说明行与子项名称（@需求：保活权限挂「权限」卡片下级）。 */
    private const val T_PERM_BATTERY_NAME = "\u7535\u6c60\u4f18\u5316\u767d\u540d\u5355"

    /** 【v2.9.6】后台耗电管理：ColorOS 冻结后台应用的开关页（OplusHansManager freeze）。 */
    private const val T_GUIDE_BG_POWER_NAME = "\u540e\u53f0\u8017\u7535\u7ba1\u7406"
    private const val T_GUIDE_AUTOSTART_NAME = "\u5141\u8bb8\u81ea\u542f\u52a8"
    private const val T_GUIDE_BG_ACTIVITY_NAME = "\u5141\u8bb8\u540e\u53f0\u6d3b\u52a8"

    /** 【无感保活】总开关行的 View tag（保活分组首行）。状态存 feiyu_keepalive 文件。 */
    private const val TAG_KEEPALIVE = "feiyu_keepalive"

    /** 【无感保活】开关标题。 */
    private const val T_KEEPALIVE_NAME = "无感保活"

    /** 保活子项的 View tag，供 syncPerm 定位与点击分发。 */
    private const val TAG_PERM_BATTERY = "feiyu_perm_battery"
    private const val TAG_GUIDE_BG_POWER = "feiyu_guide_bg_power"
    private const val TAG_GUIDE_AUTOSTART = "feiyu_guide_autostart"
    private const val TAG_GUIDE_BG_ACTIVITY = "feiyu_guide_bg"

    /** 第一级开关标题。 */
    private const val T_HIDE_RECENTS = "\u9690\u85cf\u540e\u53f0\u5361\u7247"
    private const val T_CHAT_NEW = "\u804a\u5929"
    private const val T_DOLL_NEW = "\u4eba\u5076"
    private const val T_LOOK_NEW = "\u5916\u89c2"

    /** 「外观」卡片下级的两个聊天背景入口（整行样式，与「主题模式」一致）。 */
    private const val T_LOOK_BG = "\u804a\u5929\u80cc\u666f"
    private const val T_LOOK_BG_CLEAR = "\u6e05\u9664\u80cc\u666f"
    private const val T_LOOK_BG_OFF = "\u672a\u8bbe\u7f6e"
    private const val T_LOOK_BG_ON = "\u5df2\u8bbe\u7f6e"
    private const val T_LOOK_BG_GO = "\u203a"
    private const val TAG_LOOK_BG = "feiyu_look_bg"
    private const val TAG_LOOK_BG_CLEAR = "feiyu_look_bg_clear"
    private const val T_EMPTY_HINT = "\u6682\u65e0\u66f4\u591a\u7684\u4eba\u5076"
    private const val T_SCALE = "调整比例"
    private const val T_SCALE_HINT = "点加减号调整，范围 0% ~ 100%，每档 10%。"
    private const val TAG_DOLL_SCALE = "feiyu_doll_scale"
    private const val T_OP_NEW = "\u64cd\u4f5c"
    private val T_EMPTY = arrayOf(
        "\u5916\u89c2", "\u8bb0\u5fc6", "\u5173\u4e8e"
    )

    /** 「记忆」卡片下级：自动总结开关 / 触发阈值 / 记忆库入口。 */
    private const val T_MEM_AUTO = "\u81ea\u52a8\u603b\u7ed3"
    private const val T_MEM_THRESHOLD = "\u89e6\u53d1\u9608\u503c"
    private const val T_MEM_LIB = "\u6253\u5f00\u8bb0\u5fc6\u5e93"
    private const val TAG_MEM_AUTO = "feiyu_mem_auto"
    private const val TAG_MEM_THRESHOLD = "feiyu_mem_threshold"
    private const val TAG_MEM_LIB = "feiyu_mem_lib"

    /** 【d19】从加号面板移植来的两个开关：自动保存记忆 / 自动精简记忆。 */
    private const val T_MEM_SAVE = "\u81ea\u52a8\u4fdd\u5b58\u8bb0\u5fc6"
    private const val T_MEM_MERGE = "\u81ea\u52a8\u7cbe\u7b80\u8bb0\u5fc6"
    private const val TAG_MEM_SAVE = "feiyu_mem_save"
    private const val TAG_MEM_MERGE = "feiyu_mem_merge"

    /** 卡片右侧状态文案。 */
    /** 主题相关文案（「外观」卡片下级）。 */
    private const val T_THEME_MODE = "\u4e3b\u9898\u6a21\u5f0f"
    private const val T_THEME_MONET = "\u83ab\u5948\u4e3b\u9898\u8272"

    /** 主题行的 View tag，供 syncTheme 定位与点击分发。 */
    private const val TAG_THEME_MODE = "feiyu_theme_mode"
    private const val TAG_THEME_MONET = "feiyu_theme_monet"

    /** 【v2.10.0】「关于」卡片里那一行入口按钮的 tag。 */
    private const val TAG_ABOUT_ENTRY = "feiyu_about_entry"

    /** 【需求】权限卡片下级的磁贴开关与开关指令（两者是同一条「启停」通道的两种入口）。 */
    private const val T_TILE = "快捷设置磁贴"
    private const val T_CMD = "开关指令"
    private const val T_CMD_COPY = "复制 \u203a"
    private const val T_CMD_COPIED = "已复制 \u2713"

    /** 复制出去的外部链接：同一条链接兼管开与关（PetLinkActivity 里按运行状态自行判断）。 */
    private const val T_CMD_LINK = "dollhouse://pet/toggle"
    private const val TAG_TILE_SWITCH = "feiyu_tile_switch"
    private const val TAG_CMD_ROW = "feiyu_cmd_row"

    /** 人偶当前是否处于「已启动」状态，仅用于切换首页那颗按钮的文案。 */
    private var running = false

    // 工具类：只提供静态方法，禁止实例化。（object 天然不可实例化，原 private 构造器已省略。）

    /** smali 侧唯一入口，在 SettingsFold.apply 之后调用一次。 */
    @JvmStatic
    fun apply(activity: Activity?) {
        try {
            if (activity == null) {
                return
            }
            val decor = if (activity.window == null) null else activity.window.decorView
            val sv = findScrollView(decor)
            if (sv == null || sv.childCount == 0) {
                return
            }
            val child = sv.getChildAt(0)
            if (child !is LinearLayout) {
                return
            }
            val box = child
            if (box.orientation != LinearLayout.VERTICAL || box.childCount < 9) {
                return
            }
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            if (content == null) {
                return
            }
            val ctx: Context = activity

            val vTitle = box.getChildAt(0)
            val vSub = box.getChildAt(1)
            val vPet = box.getChildAt(2)
            val vStatus = box.getChildAt(3)
            val vOverlay = box.getChildAt(4)
            if (vTitle !is TextView || vSub !is TextView
                || vPet !is ImageView || vStatus !is TextView
            ) {
                return
            }

            val legacy = findLegacyButtons(box)
            if (legacy == null) {
                // 结构不符，整体放弃，保持原样可用。
                return
            }
            val bStart = legacy[0]
            val bStop = legacy[1]
            val bChat = legacy[2]
            // 两个图片功能的入口按钮：留在「聊天背景」卡片的正文里即可。
            // 【改】不再把它们搬进「外观」——改为在「外观」里新建两行 valueRow 做入口，
            //   原按钮控件随卡片壳一起弃用（跳过搬运即可，不必摘除，控件实例仍留在原 body）。
            // 按钮从设置页摘掉（控件实例仍有效，首页通过 performClick 复用其逻辑）。
            box.removeView(bStart)
            box.removeView(bStop)
            box.removeView(bChat)
            if (vOverlay != null) {
                box.removeView(vOverlay)
            }

            val refs = scanCards(box)
            val cardChat = refs.chat
            val cardOp = refs.op
            val cardBg = refs.bg
            val homeScroll = buildHomePage(
                activity, ctx, box,
                vTitle, vSub, vPet, vStatus, bStart, bStop, bChat
            )
            buildSettingsPage(activity, ctx, box, cardChat, cardOp, cardBg)
            // 两页共用同一底色，切页不再跳色。
            sv.setBackgroundColor(UiKit.BG)
            sv.tag = TAG_SET

            // ---- 组装两页，替换掉原来的单页内容 ----
            // 关键：设置页那棵 sv 此刻仍挂在 content 上，直接 addView 到别的父容器会抛
            // IllegalStateException。必须先 detach，且摘掉首页四件套之后再执行，
            // 否则异常会被外层 catch 吞掉、界面停在「已摘控件但首页未接上」的半拆状态。
            content.removeAllViews()
            val host = FrameLayout(ctx)
            host.addView(homeScroll)
            host.addView(sv)
            content.addView(host)
            // 主题切换会重建界面：重建后回到切换前那一页。
            // 这两个标志不在这里清，交给 HomeUi.restoreScroll 统一消费——
            // 它既要判断回哪一页，也要据此决定滚哪个页面的位置。
            val toSettings = PetPrefs.themeOnSettings(activity)
            show(activity, !toSettings)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            // 呈现层调整，任何意外都不允许影响原有功能。
        }
    }

    /** 每次回到前台时调用：刷新首页按钮文案与「权限」卡片两行状态。 */
    @JvmStatic
    fun sync(ctx: Context) {
        try {
            syncToggle(ctx)
            syncPerm(ctx)
            syncHideRecents(ctx)
            syncTheme(ctx)
            syncLookBg(ctx)
            syncMem(ctx)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    // 刷新权限卡片两行状态：已授权 / 未授权；未授权时整行可点去授权。
    private fun syncPerm(ctx: Context) {
        if (ctx !is Activity) {
            return
        }
        val act = ctx
        bindPermRow(find(act, HomeCards.TAG_PERM_OVERLAY), hasOverlay(ctx))
        bindPermRow(find(act, TAG_PERM_NOTIF), hasNotif(ctx))
        // 电池优化白名单：进了白名单才算「已授权」（isIgnoringBatteryOptimizations=true）。
        bindPermRow(find(act, TAG_PERM_BATTERY), ignoringBattery(ctx))
        // Shizuku 授权行：四态（已授权 / 未授权 / 服务未运行 / 未安装），只有「已授权」不可点。
        bindShizukuRow(find(act, TAG_PERM_SHIZUKU), ShizukuBridge.state(ctx))
        // 磁贴开关：系统没有查询接口，只能按 TileService 回调回写的值显示。
        val tileRow = find(act, TAG_TILE_SWITCH)
        if (tileRow is UiKit.Switch) {
            tileRow.setOn(PetPrefs.tileAdded(ctx), false)
        }
        // 无感保活开关：按落盘状态回填（状态存 feiyu_keepalive，与桌宠偏好解耦）。
        val keepAliveRow = find(act, TAG_KEEPALIVE)
        if (keepAliveRow is UiKit.Switch) {
            keepAliveRow.setOn(KeepAliveBridge.isEnabled(ctx), false)
        }
    }

    /**
     * 【无感保活】开关落地：写盘 + 注册 / 注销调度 + 起停常驻服务，全部交给保活门面。
     *
     * 【为什么不在 UI 层自己注册 Job】注册与注销必须严格对称，且要被 Application 启动、
     *   开机、覆盖安装等多条路径复用；逻辑集中在 KeepAliveFacade 一处，UI 只转发意图。
     * 【失败回滚】门面出错时会把状态落回「关」，这里同步把开关视觉拨回去，
     *   避免出现「界面显示开、实际没生效」。
     */
    @JvmStatic
    fun onKeepAliveChanged(on: Boolean, ctx: Context) {
        val actual = KeepAliveBridge.setEnabled(ctx, on)
        if (ctx is Activity) {
            if (actual != on) {
                val row = find(ctx, TAG_KEEPALIVE)
                if (row is UiKit.Switch) {
                    row.setOn(actual, true)
                }
            }
        }
    }

    /**
     * 磁贴开关被拨动：开 = 请求系统把磁贴加进快捷面板；关 = 打开快捷面板让你长按移除。
     *
     * 【为什么「关」不能直接移除】Android 没有 requestRemoveTileService 这种 API，
     *   移除磁贴只能由用户在面板里长按拖走；能做的只有把面板打开、把话说明白。
     * 【为什么开关状态不在这里写死】系统会异步回 onTileAdded / onTileRemoved，
     *   由那两个回调回写 PetPrefs.tileAdded 才是最准的；这里只做乐观预置，失败再回滚。
     */
    @JvmStatic
    fun onTileSwitchChanged(on: Boolean, ctx: Context) {
        if (on) {
            val ok = requestAddTile(ctx)
            PetPrefs.setTileAdded(ctx, if (ok) PetPrefs.tileAdded(ctx) else false)
            if (!ok) {
                openQuickSettings(ctx)
            }
        } else {
            PetPrefs.setTileAdded(ctx, false)
            openQuickSettings(ctx)
        }
    }

    /**
     * 请求系统把本应用的磁贴放进快捷面板（Android 13+ 才有这个接口）。
     * 低版本 / 失败返回 false，由调用方退回「打开面板手动添加」。
     */
    private fun requestAddTile(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return false
        }
        try {
            val sbm = ctx.getSystemService("statusbar") as? StatusBarManager ?: return false
            sbm.requestAddTileService(
                ComponentName(ctx, PetTileService::class.java),
                ctx.getString(R.string.app_name),
                Icon.createWithResource(ctx, Icons.IC_TILE_PET),
                ctx.mainExecutor,
                // 这个回调系统要求非 null：传 null 会在系统回结果时 NPE，必须给空实现。
                java.util.function.Consumer<Int> { }
            )
            return true
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            return false
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

    /**
     * 「开关指令」行被点：把 dollhouse://pet/toggle 复制进系统剪贴板。
     * 反馈用本行右侧文字变化（复制 ›  →  已复制 ✓），符合「不许用浮层短提示」的硬约束。
     */
    @JvmStatic
    fun copyToggleLink(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                return
            }
            cm.setPrimaryClip(ClipData.newPlainText("Dollhouse", T_CMD_LINK))
            if (ctx is Activity) {
                val row = find(ctx, TAG_CMD_ROW)
                if (row != null) {
                    HomeCards.setRowValue(row, T_CMD_COPIED)
                }
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    // 是否已加入电池优化白名单（无需权限即可查询）。查询异常一律当作「未加入」。
    //  【无感保活】判定统一由 KeepAlivePermissions 提供，此处只转发（原实现是同一套
    //   逻辑的第二份拷贝；两份并存迟早会漂移）。语义完全一致：<23 视为已豁免。
    private fun ignoringBattery(ctx: Context): Boolean {
        return KeepAliveBridge.isIgnoringBatteryOptimizations(ctx)
    }

    /**
     * 权限行点击分发（由 HomeCards.permRow 统一回调）：按 tag 决定去哪申请。
     * 未知 tag 一律不动作。
     */
    @JvmStatic
    fun permClicked(tag: String?, ctx: Context) {
        if (HomeCards.TAG_PERM_OVERLAY == tag) {
            requestOverlay(ctx)
        } else if (TAG_PERM_NOTIF == tag) {
            requestNotif(ctx)
        } else if (TAG_PERM_BATTERY == tag) {
            requestIgnoreBattery(ctx)
        } else if (TAG_PERM_SHIZUKU == tag) {
            shizukuClicked(ctx)
        }
    }

    /**
     * Shizuku 授权行点击：按四态分别落地。
     * 已授权时该行不可点（bindShizukuRow 已关掉 clickable），走不到这里。
     */
    @JvmStatic
    fun shizukuClicked(ctx: Context) {
        when (ShizukuBridge.state(ctx)) {
            ShizukuBridge.S_NOT_INSTALLED ->
                // 没装管理器：没有可跳转的目标，只能用自绘弹窗把话说清楚（禁止 Toast/Snackbar）。
                showShizukuMissing(ctx)

            ShizukuBridge.S_NOT_RUNNING ->
                // 管理器在但服务没跑：拉起管理器，用户在里面启动服务。
                // 【兜底】拿不到启动 Intent 时不能「点了没反应」——给一句说明（禁止 Toast）。
                if (!ShizukuBridge.openManager(ctx)) {
                    showShizukuDialog(
                        ctx, "\u65e0\u6cd5\u81ea\u52a8\u6253\u5f00 Shizuku \u7ba1\u7406\u5668\u3002\n\n"
                            + "\u8bf7\u624b\u52a8\u6253\u5f00\u5b83\u5e76\u542f\u52a8\u670d\u52a1\uff0c\u518d\u56de\u5230\u8fd9\u91cc\u3002"
                    )
                }

            else -> {
                // 【老版兼容】server < v11 的 Shizuku 没有「运行时授权」这套机制，
                //   requestPermission 在老版上不弹框、不会有结果回调 —— 点了等于没反应。
                //   老版的做法是：在管理器界面里手动勾选本应用（勾上即视为已授权）。
                if (ShizukuBridge.needManagerForGrant()) {
                    if (!ShizukuBridge.openManager(ctx)) {
                        showShizukuDialog(
                            ctx, "\u65e0\u6cd5\u81ea\u52a8\u6253\u5f00 Shizuku \u7ba1\u7406\u5668\u3002\n\n"
                                + "\u4f60\u7684 Shizuku \u7248\u672c\u8f83\u65e7\uff0c\u9700\u8981\u5728\u7ba1\u7406\u5668\u91cc\u624b\u52a8\u52fe\u9009\u672c\u5e94\u7528\u3002"
                        )
                    } else {
                        showShizukuDialog(
                            ctx, "\u4f60\u7684 Shizuku \u7248\u672c\u8f83\u65e7\u3002\n\n"
                                + "\u8bf7\u5728\u7ba1\u7406\u5668\u91cc\u628a\u672c\u5e94\u7528\u52fe\u9009\u4e3a\u300c\u5df2\u6388\u6743\u300d\uff0c"
                                + "\u7136\u540e\u56de\u5230\u8fd9\u91cc\u3002"
                        )
                    }
                    return
                }
                // 未授权：由 server 弹系统确认框，结果经 listener 回来再刷新这一行。
                ShizukuBridge.requestPermission(REQ_SHIZUKU)
            }
        }
    }

    /** 「未安装 Shizuku 管理器」说明弹窗：无 Toast 约束下的唯一合法反馈通道。 */
    private fun showShizukuMissing(ctx: Context) {
        showShizukuDialog(
            ctx, "\u672a\u68c0\u6d4b\u5230 Shizuku \u7ba1\u7406\u5668\u3002\n\n"
                + "\u5148\u5b89\u88c5 Shizuku \u5e76\u542f\u52a8\u5176\u670d\u52a1\uff08\u9700\u914d\u5408 adb / \u65e0\u7ebf\u8c03\u8bd5\uff09\uff0c"
                + "\u518d\u56de\u5230\u8fd9\u91cc\u6388\u6743\u3002\u6388\u6743\u540e\u672c\u5e94\u7528\u4e0e AI \u90fd\u80fd\u4f7f\u7528\u7cfb\u7edf\u7ea7\u80fd\u529b\u3002"
        )
    }

    /** Shizuku 授权行的通用说明弹窗（无 Toast 约束下的唯一合法反馈通道）。 */
    private fun showShizukuDialog(ctx: Context, message: String) {
        val act = UiKit.findActivity(ctx)
        if (act == null) {
            return
        }
        UiKit.showDialog(
            act, T_PERM_SHIZUKU_NAME,
            UiKit.dialogMessage(act, message), "\u77e5\u9053\u4e86", null, null, null
        )
    }

    /**
     * 保活引导行点击分发（由 HomeCards.guideRow 回调）：只能拉起系统页面，由用户手动开。
     * 硬约束：绝不使用 am start 抢用户前台，全部走标准 Settings Intent。
     */
    @JvmStatic
    fun guideClicked(tag: String?, ctx: Context) {
        // 【无感保活】改走厂商候选链：VendorNavigator 三级降级
        //   （直达私有页 -> 厂商备用页 -> 应用详情页，每级先 resolveActivity 探测再启动）。
        //  【为什么必须换掉硬编码】原实现只认 ColorOS 的单一组件名，换到小米 / 华为 / 荣耀 /
        //   一加 / 三星上「点了没反应」；候选表 + 逐个探测是唯一稳健路径。
        //  【ColorOS 行为不变】候选表里 OPPO 的首条就是原来硬编码的
        //   com.oplus.powermanager.fuelgaue.PowerAppsBgSetting，解析失败则与本机原来的
        //   兜底一致，退到应用详情页。
        if (TAG_GUIDE_AUTOSTART == tag) {
            if (KeepAliveBridge.openAutostartPage(ctx)) {
                return
            }
            requestAppDetails(ctx)
            return
        }
        if (TAG_GUIDE_BG_POWER == tag) {
            if (KeepAliveBridge.openBatteryPage(ctx)) {
                return
            }
            requestAppDetails(ctx)
            return
        }
        // 后台活动：厂商候选表里没有对应目标（ColorOS 该开关只在应用详情页内），
        //  保持原有兜底路径不动。
        requestAppDetails(ctx)
    }

    /**
     * 跳系统「电池优化」列表：SDK>=23 带包名直达本应用的豁免页；
     * 厂商改过该页会失败，退回不带包名的不受限列表。任何失败都静默吞掉。
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
        // 【v2.9.6】两层都失败时兜底到应用详情页，绝不静默什么都不发生。
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

    /**
     * 【v2.9.6】尝试打开指定组件；组件不存在或权限不足时返回 false（不抛）。
     * 用 ComponentName 显式指定，比 action 更精确：ColorOS 上同一设置项常被包一层
     * 自定义壳，用 action 会落到笼统的列表页，用户还得自己再找一层。
     */
    private fun openComponent(ctx: Context, pkg: String, cls: String): Boolean {
        try {
            val i = Intent()
            i.component = ComponentName(pkg, cls)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return true
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            return false
        }
    }

    /** 【v2.9.6】打开本应用在系统设置里的指定页；失败返回 false。 */
    private fun openAppSetting(ctx: Context, cls: String): Boolean {
        return openComponent(ctx, "com.android.settings", cls)
    }

    /**
     * 权限行按状态双向绑定：
     * 已授权 —— 绿字「已授权」、不可点；
     * 未授权 —— 红字「未授权」、整行可点（点行触发行内存的 onClick）。
     */
    private fun bindPermRow(row: View?, granted: Boolean) {
        if (row !is LinearLayout) {
            return
        }
        val r = row
        if (r.childCount < 2) {
            return
        }
        val last = r.getChildAt(1)
        if (last is TextView) {
            last.text = if (granted) HomeCards.S_OK else HomeCards.S_NO
            UiKit.setTextColorAnimated(last, if (granted) UiKit.OK else UiKit.ERR)
            // 【图标语义】已授权打勾、未授权打叉 —— 除了红绿字，再给一层形状区分，
            //   色盲 / 强光下也能一眼分清（用户 #8 需求）。
            Icons.stateIcon(
                last,
                if (granted) Icons.IC_CHECK_CIRCLE else Icons.IC_X_CIRCLE,
                if (granted) UiKit.OK else UiKit.ERR, 13.0f, 4
            )
        }
        r.isClickable = !granted
    }

    /**
     * Shizuku 授权行按四态绑定：右侧文案由 ShizukuBridge 给（已授权 / 未授权 / 服务未运行 / 未安装）；
     * 只有「已授权」是绿字不可点，其余三态都红字可点（分别去授权 / 去启动服务 / 去装管理器）。
     */
    private fun bindShizukuRow(row: View?, state: Int) {
        if (row !is LinearLayout) {
            return
        }
        val r = row
        if (r.childCount < 2) {
            return
        }
        val last = r.getChildAt(1)
        val granted = state == ShizukuBridge.S_GRANTED
        if (last is TextView) {
            last.text = ShizukuBridge.stateText(state)
            UiKit.setTextColorAnimated(last, if (granted) UiKit.OK else UiKit.ERR)
            // 【图标语义】已授权 = 盾牌（特权已到手）；其余三态都是待处理，用警示三角。
            Icons.stateIcon(
                last,
                if (granted) Icons.IC_SHIELD else Icons.IC_WARNING,
                if (granted) UiKit.OK else UiKit.ERR, 13.0f, 4
            )
        }
        r.isClickable = !granted
    }

    // 通知是否可用：API>=33 查运行时权限，24~32 查 areNotificationsEnabled（API 24 起有）。
    private fun hasNotif(ctx: Context): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            return nm == null || nm.areNotificationsEnabled()
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            return false
        }
    }

    // SDK>=23 走 Settings.canDrawOverlays；低版本一律视为已授予。
    private fun hasOverlay(ctx: Context): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                return Settings.canDrawOverlays(ctx)
            }
            return true
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            return false
        }
    }

    // 刷新首页那颗「启动人偶 / 关闭人偶」按钮的文案。
    private fun syncToggle(ctx: Context) {
        running = isServiceRunning(ctx)
        if (ctx !is Activity) {
            return
        }
        val t = find(ctx, TAG_TOGGLE)
        if (t is Button) {
            t.text = if (running) "\u5173\u95ed\u4eba\u5076" else "\u542f\u52a8\u4eba\u5076"
        }
    }

    // 用 ActivityManager 代查 PetService 是否活着；查不到时沿用上次结果。
    private fun isServiceRunning(ctx: Context): Boolean {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am == null) {
                return running
            }
            for (info in am.getRunningServices(Int.MAX_VALUE)) {
                val cn = info.service?.className
                if (PetService::class.java.name == cn) {
                    return true
                }
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        return false
    }

    /** 返回键：设置页 -> 首页。返回 true 表示这次返回已被消费。 */
    @JvmStatic
    fun handleBack(activity: Activity): Boolean {
        try {
            val settings = find(activity, TAG_SET)
            val home = find(activity, TAG_HOME)
            if (settings == null || home == null || settings.visibility != View.VISIBLE) {
                return false
            }
            // 与前进路径保持同一种过渡：交叉淡入，而非硬切。
            showDiff(home.parent as? ViewGroup, home, settings)
            return true
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            return false
        }
    }

    /* ------------------------------ 设置页构件 ------------------------------ */
    /**
     * 刷新「记忆」卡片：开关状态 + 阈值档位值。
     * 【坑】步进器的中间文本每档显示的是「条数」，但点击改的是档位下标，
     *       两者必须都走 PetPrefs 的同一份数据，否则重进页面会回跳。
     */
    private fun syncMem(ctx: Context) {
        try {
            if (ctx !is Activity) {
                return
            }
            val activity = ctx
            val sw = find(activity, TAG_MEM_AUTO)
            if (sw is UiKit.Switch) {
                sw.setOn(PetPrefs.memAuto(ctx), false)
            }
            // 【d19】移植来的两个开关同样按落盘值回填（它们也可能被外部入口改，例如「对话行为」页）。
            val swSave = find(activity, TAG_MEM_SAVE)
            if (swSave is UiKit.Switch) {
                swSave.setOn(PetPrefs.memAutoSave(ctx), false)
            }
            val swMerge = find(activity, TAG_MEM_MERGE)
            if (swMerge is UiKit.Switch) {
                swMerge.setOn(PetPrefs.memAutoMerge(ctx), false)
            }
            val row = find(activity, TAG_MEM_THRESHOLD)
            if (row != null) {
                val idx = PetPrefs.memThresholdIndex(ctx)
                HomeCards.setStepperValue(row, PetPrefs.MEM_THRESHOLDS[idx].toString() + " \u6761")
                val minus = row.findViewWithTag<View>(TAG_MEM_THRESHOLD + "_minus")
                if (minus != null) {
                    minus.isEnabled = idx > 0
                    minus.alpha = if (idx > 0) 1.0f else 0.35f
                }
                val plus = row.findViewWithTag<View>(TAG_MEM_THRESHOLD + "_plus")
                if (plus != null) {
                    val last = PetPrefs.MEM_THRESHOLDS.size - 1
                    plus.isEnabled = idx < last
                    plus.alpha = if (idx < last) 1.0f else 0.35f
                }
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /**
     * 开关落地：先写偏好，再把本应用在「最近任务」里的任务卡片刻成排除 / 恢复。
     * AppTask 只作用于本应用自己的任务，无需额外运行时权限；任何异常都静默吞掉，
     * 不能让一个可选装饰性开关影响主功能。切换后需下次进入前台才完全生效 —— 这是
     * 系统 recents 缓存的固有行为，不是缺陷。
     */
    @JvmStatic
    fun onHideRecentsChanged(hide: Boolean, ctx: Context) {
        PetPrefs.setHideRecents(ctx, hide)
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

    /** 每次回到前台时按偏好重新落一次（系统可能已重建任务记录）。 */
    private fun syncHideRecents(ctx: Context) {
        var row: View? = null
        if (ctx is Activity) {
            row = find(ctx, TAG_HIDE_RECENTS)
        }
        if (row is UiKit.Switch) {
            row.setOn(PetPrefs.hideRecents(ctx), false)
        }
        applyHideRecents(ctx)
    }

    // 刷新「外观」卡片里的主题行：主题模式显示当前档位名，莫奈开关按偏好回填。
    private fun syncTheme(ctx: Context) {
        if (ctx !is Activity) {
            return
        }
        val act = ctx
        val modeRow = find(act, TAG_THEME_MODE)
        if (modeRow != null) {
            HomeCards.setRowValue(modeRow, ThemeManager.MODE_NAMES[ThemeManager.mode(ctx)])
        }
        val monetRow = find(act, TAG_THEME_MONET)
        if (monetRow is UiKit.Switch) {
            monetRow.setOn(ThemeManager.monet(ctx), false)
        }
    }

    // 刷新「外观」里的聊天背景行：右侧显示「未设置 / 已设置」。
    private fun syncLookBg(ctx: Context) {
        if (ctx !is Activity) {
            return
        }
        val row = find(ctx, TAG_LOOK_BG)
        if (row != null) {
            val bgOn = PetPrefs.chatBackground(ctx).isNotEmpty()
            // 【状态色】「已设置 / 未设置」是状态而非值，右侧文本按语义上绿 / 红。
            HomeCards.setRowValue(
                row, if (bgOn) T_LOOK_BG_ON else T_LOOK_BG_OFF,
                if (bgOn) UiKit.OK else UiKit.ERR
            )
        }
    }

    // 主题模式选择面板：四档单选，选完写偏好并立即重建当前界面。
    private fun pickThemeMode(act: Activity?) {
        if (act == null) {
            return
        }
        val cur = ThemeManager.mode(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val holder = arrayOfNulls<Dialog>(1)
        for (i in ThemeManager.MODE_NAMES.indices) {
            col.addView(modeOption(act, i, i == cur, cur, holder))
        }
        holder[0] = UiKit.showDialog(act, T_THEME_MODE, col, null, null, null, null)
    }

    // 单个主题模式选项：选中项淡紫底 + 加粗 + 右侧勾，点选后关窗并立即应用。
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
        t.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))

        // 主题选中态：描边对勾图标（无选中则占位保持行高一致）。
        // 【坑·必看】占位必须用固定尺寸 View，不能用 `new View(act)` + WRAP_CONTENT：
        //   裸 View 没有内容，onMeasure 走 View.getDefaultSize(AT_MOST) —— 直接取满父给的
        //   可用高度，于是每一行"未选中"的档位都被撑到近整屏。表现就是弹窗里只有选中项一行
        //   正常，其余选项被拉成整屏高、根本点不到（「主题模式点开没法正常选择」的真根因）。
        //   这里把高宽都定死成与对勾图标行一致，四行等高。
        val mark: View = if (selected) {
            Icons.view(act, Icons.IC_CHECK, 16.0f, UiKit.ACC)
        } else {
            View(act)
        }
        row.addView(mark, LinearLayout.LayoutParams(UiKit.dp(act, 22f), UiKit.dp(act, 22f)))

        UiKit.press(row)
        row.setOnClickListener { _ ->
            holder[0]?.dismiss()
            if (idx == cur) {
                return@setOnClickListener
            }
            ThemeManager.setMode(act, idx)
            applyThemeNow(act)
        }
        return row
    }

    // 莫奈主题色开关：先在后台取色，拿到颜色再写偏好并重建界面。
    // 取色绝不能在主线程做（壁纸是整屏大图），否则切开关会卡住界面。
    private fun onMonetChanged(on: Boolean, ctx: Context) {
        if (!on) {
            ThemeManager.setMonet(ctx, false)
            if (ctx is Activity) {
                applyThemeNow(ctx)
            }
            return
        }
        // 打开：先看缓存，有就立刻生效；没有就后台解一次，回来再生效。
        if (ThemeManager.hasMonetColor(ctx)) {
            ThemeManager.setMonet(ctx, true)
            if (ctx is Activity) {
                applyThemeNow(ctx)
            }
            return
        }
        val app = ctx.applicationContext
        val act = if (ctx is Activity) ctx else null
        ThemeManager.monetHueAsync(ctx, ThemeManager.HueCallback { ok ->
            if (!ok) {
                ThemeManager.setMonet(app, false)
                syncTheme(app)
                return@HueCallback
            }
            ThemeManager.setMonet(app, true)
            if (act != null) {
                applyThemeNow(act)
            }
        })
    }

    // 主题改动后立即生效：刷 UiKit 配色 -> 通知桌宠重读 -> 重建当前界面。
    private fun applyThemeNow(act: Activity?) {
        if (act == null) {
            return
        }
        ThemeManager.apply(act)
        notifyPetRefresh(act)
        // 记住当前停在哪一页 + 滚到哪：recreate 会重跑 onCreate，
        // 不记的话会被弹回首页、滚动位置也会丢。
        val toSettings = !isHomeVisible(act)
        PetPrefs.setThemeOnSettings(act, toSettings)
        saveScroll(act, toSettings)
        // 一次性标志：只有「换主题触发的重建」才还原滚动位置，冷启动不还原。
        PetPrefs.setThemeRestore(act, true)
        try {
            act.recreate()
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    // 记下当前页的滚动位置（首页 / 设置页各记一份）。
    private fun saveScroll(act: Activity, settingsPage: Boolean) {
        val page = find(act, if (settingsPage) TAG_SET else TAG_HOME)
        if (page is ScrollView) {
            val y = page.scrollY
            PetPrefs.setScrollY(act, if (settingsPage) KEY_SET else KEY_HOME, y)
        }
    }

    // 换主题重建后把滚动位置滚回去（消费一次性标志，冷启动不受影响）。
    @JvmStatic
    fun restoreScroll(act: Activity) {
        try {
            if (!PetPrefs.themeRestore(act)) {
                return
            }
            // 消费两个一次性标志：换主题重建才还原，冷启动不受影响。
            PetPrefs.setThemeRestore(act, false)
            val settingsPage = PetPrefs.themeOnSettings(act)
            PetPrefs.setThemeOnSettings(act, false)
            val page = find(act, if (settingsPage) TAG_SET else TAG_HOME)
            if (page !is ScrollView) {
                return
            }
            val sv = page
            val y = PetPrefs.scrollY(act, if (settingsPage) KEY_SET else KEY_HOME)
            if (y <= 0) {
                return
            }
            // 布局还没量完时 scrollTo 会被后续 measure 覆盖。先试一次，
            // 没生效就再等一拍重试——否则位置会掉回顶部。
            sv.post {
                sv.scrollTo(0, y)
                if (sv.scrollY != y) {
                    sv.postDelayed({
                        sv.scrollTo(0, y)
                    }, 80L)
                }
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /** 调整人偶比例：delta 为 -1 / +1，每档 10%（内部与显示同步走 10 档）。 */
    private fun stepPetScale(act: Activity, delta: Int) {
        val cur = PetPrefs.petScale(act)
        PetPrefs.setPetScale(act, cur + (delta * PetPrefs.PET_SCALE_STEP))
        syncDollScale(act)
        notifyPetRefresh(act)
    }

    /** 把当前比例刷进步进器显示（界面已装配时才找得到控件）。 */
    private fun syncDollScale(act: Activity) {
        val row = find(act, TAG_DOLL_SCALE)
        if (row != null) {
            HomeCards.setStepperValue(row, PetPrefs.petScaleDisplay(PetPrefs.petScale(act)).toString() + "%")
        }
    }

    // 当前是否停在首页（首页可见即算首页）。
    private fun isHomeVisible(act: Activity): Boolean {
        val home = find(act, TAG_HOME)
        return home != null && home.visibility == View.VISIBLE
    }

    // 主题切换后让常驻桌宠重读颜色（服务没在跑时静默忽略）。
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

    // 去系统设置开悬浮窗：SDK>=23 可带 package 直接定位到本应用那一项。
    // 厂商可能改过这个页面，带包名失败就退回不带包名的通用页。
    @JvmStatic
    fun requestOverlay(ctx: Context) {
        try {
            val i: Intent = if (Build.VERSION.SDK_INT >= 23) {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.packageName)
                )
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

    // 通知权限：33+ 走标准运行时申请；24~32 没有这个运行时权限，只能跳到通知设置页。
    @JvmStatic
    fun requestNotif(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ctx is Activity) {
                ctx.requestPermissions(
                    arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF
                )
                return
            }
            // 【v2.9.6】先直达本应用的通知详情页（进去就是本 App 的开关列表）；
            //  失败再退回通用 action 形式。
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

    // 首页 / 设置页二选一切换：只改可见性 + 淡入位移，不重建任何视图。

    /**
     * 首页页：标题 + 人偶 + 三按钮（启动/打开聊天/设置）。
     * 【v2.10.0】副标题与状态行整行移除，控件本身与 HomeScreenBuilder 的索引顺序不动。
     */
    /** 首页/设置页共用的三张卡片引用。 */
    private class CardRefs {
        var chat: View? = null
        var op: View? = null
        var bg: LinearLayout? = null
    }

    /**
     * 在设置页根容器里找出「启动桌宠 / 停止桌宠 / 打开聊天」三个旧按钮。
     * 任一缺失返回 null，调用方整体放弃。
     */
    private fun findLegacyButtons(box: LinearLayout): Array<Button>? {
        var bStart: Button? = null
        var bStop: Button? = null
        var bChat: Button? = null
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            if (v !is Button) {
                continue
            }
            val t = UiKit.textOf(v)
            if (t == null) {
                continue
            }
            // 注意：按钮文本带序号前缀（如「2. 启动桌宠」），只能用 contains 匹配。
            if (t.contains("\u542f\u52a8\u684c\u5ba0")) {
                bStart = v
            } else if (t.contains("\u505c\u6b62\u684c\u5ba0")) {
                bStop = v
            } else if (t.contains("\u6253\u5f00\u804a\u5929")) {
                bChat = v
            }
        }
        if (bStart == null || bStop == null || bChat == null) {
            // 结构不符，整体放弃，保持原样可用。
            return null
        }
        return arrayOf(bStart, bStop, bChat)
    }

    /**
     * 扫一遍设置页根容器：摘掉废弃卡片，并把「聊天设置 / 操作方式 / 聊天背景」三张卡片接出来。
     * 聊天背景卡片保留：卡内的背景透明度滑条稍后整体并进「外观」。
     */
    private fun scanCards(box: LinearLayout): CardRefs {
        val out = CardRefs()
        var cardChat: View? = null
        var cardOp: View? = null
        var cardBg: LinearLayout? = null
        val kill = ArrayList<View>()
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            val t = HomeCards.cardTitle(v)
            if (t == null) {
                if (v is TextView) {
                    val s = UiKit.textOf(v)
                    if (s != null && s.startsWith(T_FOOTER)) {
                        kill.add(v)
                    }
                }
                continue
            }
            if (T_CHAT_OLD == t) {
                cardChat = v
            } else if (T_OP_OLD == t) {
                cardOp = v
            } else if (T_BG == t) {
                // 【修】原来整张「聊天背景」卡片都被摘掉，可卡里除了两个已搬到「外观」的
                //   按钮，还留着「背景透明度」的说明、标签与滑条 —— 摘卡片等于把透明度
                //   调节整个弄没了（全工程没有第二处重建滑条的代码）。改成保留这张卡片，
                //   稍后把它的正文整体并进「外观」。
                if (v is LinearLayout) {
                    cardBg = v
                }
            } else if (T_WEB == t || T_LEARN == t || T_ICON == t) {
                // 联网搜索 / 学习 = 功能取消；应用图标 = 功能入口已提到「外观」里
                // 做成按钮，卡片本体（只剩说明文字）不再需要。
                kill.add(v)
            }
        }
        for (i in kill.indices) {
            box.removeView(kill[i])
        }
        cardChat?.let { HomeCards.setCardTitle(it, T_CHAT_NEW) }
        cardOp?.let { HomeCards.setCardTitle(it, T_OP_NEW) }

        out.chat = cardChat
        out.op = cardOp
        out.bg = cardBg
        return out
    }

    private fun buildHomePage(
        activity: Activity, ctx: Context,
        box: LinearLayout, vTitle: View, vSub: View,
        vPet: View, vStatus: View,
        bStart: Button, bStop: Button, bChat: Button
    ): ScrollView {
        // ---- 首页 ----
        // 【v2.10.0】副标题（标题下的引导小字）与状态行（人偶下的小字）整行移除：
        //   只是不挂进新容器，控件本身与 HomeScreenBuilder 里的索引顺序都不动，
        //   HomeUi.apply 开头基于 childCount / 索引的结构契约因此依旧成立。
        box.removeView(vTitle)
        box.removeView(vSub)
        box.removeView(vPet)
        box.removeView(vStatus)

        val petHolder = LinearLayout(ctx)
        petHolder.orientation = LinearLayout.VERTICAL
        // 【v2.10.1】人偶贴上半区底部：整组垂直居中会让人偶与下方的按钮隔出大片空白，
        //   改成横向居中 + 纵向靠底，再用底部 padding 垫出与按钮区的呼吸距离。
        petHolder.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        petHolder.setPadding(0, 0, 0, UiKit.dp(ctx, 26f))
        petHolder.tag = TAG_PETS
        petHolder.addView(vPet, LinearLayout.LayoutParams(-2, -2))

        val toggle = HomeCards.mkButton(ctx, "\u542f\u52a8\u4eba\u5076", true)
        val open = HomeCards.mkButton(ctx, "\u6253\u5f00\u804a\u5929", false)
        val setting = HomeCards.mkButton(ctx, "\u8bbe\u7f6e", false)

        // 【v2.10.1】按钮组贴下半区顶部（同理：居中会在人偶与按钮间留出大片空白）。
        //   顶部 padding 与 petHolder 的底部 padding 相加，就是人偶脚底到第一颗按钮的净间距。
        val buttonsBox = LinearLayout(ctx)
        buttonsBox.orientation = LinearLayout.VERTICAL
        buttonsBox.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        buttonsBox.setPadding(0, UiKit.dp(ctx, 26f), 0, 0)
        buttonsBox.addView(toggle)
        buttonsBox.addView(open)
        buttonsBox.addView(setting)

        val homeBox = LinearLayout(ctx)
        homeBox.orientation = LinearLayout.VERTICAL
        val pad = UiKit.dp(ctx, 20f)
        // 【状态栏嵌入】首页是新容器（原 box 被改作设置页），状态栏留白要在这里补。
        homeBox.setPadding(pad, pad + UiKit.statusBarPad(ctx), pad, pad)
        homeBox.addView(vTitle)
        // 【v2.10.0】人偶区与按钮区各占剩余空间的一半：人偶自然落在上半区、
        //   三按钮落在下半区，中间留白由 weight 撑满（ScrollView 已开 fillViewport）。
        homeBox.addView(petHolder, LinearLayout.LayoutParams(-1, 0, 1.0f))
        homeBox.addView(buttonsBox, LinearLayout.LayoutParams(-1, 0, 1.0f))
        // 首页入场：标题→人偶→按钮依次淡入上移。
        // 换主题触发的重建跳过入场动画：否则整页会重新淡入一次，
        // 看起来就像按钮集体消失、再一个个冒出来。用户手动进页面时照旧播动画。
        if (!PetPrefs.themeRestore(ctx)) {
            UiKit.enter(vTitle, 0)
            UiKit.enter(petHolder, 80)
            UiKit.enter(toggle, 160)
            UiKit.enter(open, 200)
            UiKit.enter(setting, 240)
        }
        toggle.tag = TAG_TOGGLE

        val homeScroll = ScrollView(ctx)
        homeScroll.setBackgroundColor(UiKit.BG)
        // 【v2.10.0】让内容不足一屏时也撑满：上面的 weight 才会真的生效。
        homeScroll.isFillViewport = true
        // 关掉滑动到头部的拉伸辉光：那是系统默认装饰，与本 App 的卡片质感不搭。
        homeScroll.overScrollMode = View.OVER_SCROLL_NEVER
        homeScroll.addView(homeBox)
        homeScroll.tag = TAG_HOME

        syncToggle(ctx)
        val fStart = bStart
        val fStop = bStop
        val fChat = bChat
        toggle.setOnClickListener { v ->
            val now = !running
            syncToggle(v.context)
            if (now) {
                fStart.performClick()
            } else {
                fStop.performClick()
            }
            toggle.postDelayed({
                syncToggle(v.context)
                toggle.text = if (running) "\u5173\u95ed\u4eba\u5076" else "\u542f\u52a8\u4eba\u5076"
            }, 600L)
        }
        open.setOnClickListener {
            fChat.performClick()
        }
        setting.setOnClickListener {
            show(activity, false)
        }
        // 【改动】人偶本身不再点进全屏聊天页；聊天的合法入口只剩上方「打开聊天」。
        //   人偶的三击行为在桌宠悬浮窗里（PetService.triple_tap），与本页无关。

        return homeScroll
    }

    /**
     * 设置页重排：权限 / 外观 / 人偶 / 记忆 / 关于 五张卡片按新顺序装进原 box。
     * cardBg 为原「聊天背景」卡片：保留卡片、把正文（说明/标签/滑条）并进「外观」。
     */
    private fun buildSettingsPage(
        activity: Activity, ctx: Context,
        box: LinearLayout, cardChat: View?, cardOp: View?,
        cardBg: LinearLayout?
    ) {
        // ---- 设置页重排 ----
        val header = HomeCards.buildHeader(activity, ctx)

        val cardPerm = HomeCards.buildCard(ctx, T_PERM)
        val permBody = cardPerm.getChildAt(1) as LinearLayout
        // ---- 权限卡片下级第一项：Shizuku 授权（四态：已授权 / 未授权 / 服务未运行 / 未安装）----
        // 【为什么放最前】它是本应用与 AI 拿到 shell 级能力的总开关，其余权限只影响人偶本身。
        //   文案与可点性由 ShizukuBridge.state 决定，走 bindShizukuRow 绑定（不是两态 bindPermRow）。
        permBody.addView(HomeCards.permRow(ctx, T_PERM_SHIZUKU_NAME, TAG_PERM_SHIZUKU))
        // 【需求】权限子项重排：两颗开关（快捷设置磁贴 / 隐藏后台卡片）集中到卡片末尾相邻两行，
        //   「开关指令」作为复制入口独占最后一行；其余行照旧「左名称 + 右状态」。
        permBody.addView(HomeCards.permRow(ctx, T_PERM_OVERLAY_NAME, HomeCards.TAG_PERM_OVERLAY))
        permBody.addView(HomeCards.permRow(ctx, T_PERM_NOTIF_NAME, TAG_PERM_NOTIF))
        // ---- 保活分组（「权限」卡片的下级）：让本应用进程被划掉 / 冻结 / 重启后还能自己回来 ----
        // 【无感保活】总开关：保活分组首行。拨开即拉起常驻前台服务（1×1 透明窗常驻）
        //   + 注册系统级周期自检与恢复调度。语义只覆盖「软件进程要不要常驻」，
        //   不启停人偶（关掉开关她仍在，关人偶也不影响保活）。
        val keepAliveSw = UiKit.Switch(ctx)
        keepAliveSw.tag = TAG_KEEPALIVE
        keepAliveSw.setOn(KeepAliveBridge.isEnabled(ctx), false)
        permBody.addView(
            HomeCards.switchRow(
                ctx, T_KEEPALIVE_NAME, keepAliveSw,
                HomeCards.OnChanged { on, c ->
                    onKeepAliveChanged(on, c)
                }
            )
        )
        // 电池优化白名单：可查状态（isIgnoringBatteryOptimizations），红绿字 + 可点。
        permBody.addView(HomeCards.permRow(ctx, T_PERM_BATTERY_NAME, TAG_PERM_BATTERY))
        // 【v2.10.0】以下三项系统查不到授权状态：一律「去设置 ›」，
        //  点击各自直达对应系统页（分发逻辑见 guideClicked）；
        //  其中「后台耗电管理」就是 ColorOS 冻结本 App 的开关页。
        permBody.addView(HomeCards.guideRow(ctx, T_GUIDE_BG_POWER_NAME, TAG_GUIDE_BG_POWER))
        permBody.addView(HomeCards.guideRow(ctx, T_GUIDE_AUTOSTART_NAME, TAG_GUIDE_AUTOSTART))
        permBody.addView(HomeCards.guideRow(ctx, T_GUIDE_BG_ACTIVITY_NAME, TAG_GUIDE_BG_ACTIVITY))
        // ---- 【需求】开关通道（「权限」卡片下级）：磁贴开关 + 开关指令，两者是同一条启停通道的两种入口 ----
        // 磁贴开关：右侧是自绘开关，开=请系统把磁贴放进快捷面板，关=打开面板让你长按移除；
        //  真实状态由 TileService 的 onTileAdded / onTileRemoved 回写 PetPrefs.tileAdded。
        val tileSw = UiKit.Switch(ctx)
        tileSw.tag = TAG_TILE_SWITCH
        tileSw.setOn(PetPrefs.tileAdded(ctx), false)
        permBody.addView(
            HomeCards.switchRow(ctx, T_TILE, tileSw, HomeCards.OnChanged { on, c ->
                onTileSwitchChanged(on, c)
            })
        )
        // 隐藏后台卡片：与「快捷设置磁贴」紧邻，两颗开关并排落在卡片倒数第二、倒数第三行。
        val hideRecents = UiKit.Switch(ctx)
        hideRecents.tag = TAG_HIDE_RECENTS
        hideRecents.setOn(PetPrefs.hideRecents(ctx))
        permBody.addView(HomeCards.switchRow(ctx, T_HIDE_RECENTS, hideRecents))
        // 开关指令：整行可点，右侧是「复制 ›」；点一下把同一条链接复制走（开与关共用）。
        val cmdRow = HomeCards.valueRow(ctx, T_CMD, TAG_CMD_ROW)
        HomeCards.setRowValue(cmdRow, T_CMD_COPY)
        cmdRow.setOnClickListener { v ->
            copyToggleLink(v.context)
        }
        permBody.addView(cmdRow)
        permBody.visibility = View.VISIBLE
        // 首帧就按真实授权状态渲染两行，不等 onResume。
        syncPerm(activity)
        syncHideRecents(activity)

        // 「外观」：聊天背景（选图 / 清除）+ 主题模式 + 莫奈主题色。
        //   原先是两颗整宽大按钮，与卡片里其余行样式割裂；用户已定案统一成行样式。
        val cardLook = HomeCards.buildCard(ctx, T_LOOK_NEW)
        val lookBody = cardLook.getChildAt(1) as LinearLayout
        // 聊天背景：整行可点，跳去裁剪页（选图 → 裁剪预览 → 保存）。
        //   【为什么不再走 PickFileActivity】选完直接把原图铺满聊天页，比例不对就被拉伸；
        //   现在先让用户框出想要的那一块，落盘时按框裁好，聊天页拿到的永远不变形。
        val bgRow = HomeCards.valueRow(ctx, T_LOOK_BG, TAG_LOOK_BG)
        bgRow.setOnClickListener { v ->
            val c = v.context
            if (c !is MainActivity) {
                return@setOnClickListener
            }
            val m = c
            try {
                m.startActivity(Intent(m, BackgroundCropActivity::class.java))
            } catch (t: Throwable) {
                Logs.w(LOG_TAG, "ignored", t)
            }
        }
        lookBody.addView(bgRow)
        // 清除背景：整行可点，抹掉已设的聊天背景图。
        val bgClearRow = HomeCards.valueRow(ctx, T_LOOK_BG_CLEAR, TAG_LOOK_BG_CLEAR)
        HomeCards.setRowValue(bgClearRow, T_LOOK_BG_GO)
        bgClearRow.setOnClickListener { v ->
            val c = v.context
            if (c !is MainActivity) {
                return@setOnClickListener
            }
            val m = c
            PetPrefs.setChatBackground(m, "")
            m.notifyPetService()
            m.refreshLocalUi()
            syncLookBg(m)
        }
        lookBody.addView(bgClearRow)
        // 主题模式：整行可点，右侧显示当前档位名；点击弹单选面板，选完立即重建界面。
        val themeRow = HomeCards.valueRow(ctx, T_THEME_MODE, TAG_THEME_MODE)
        themeRow.setOnClickListener { v ->
            if (v.context is Activity) {
                pickThemeMode(v.context as Activity)
            }
        }
        lookBody.addView(themeRow)
        // 莫奈主题色：跟随壁纸主色派生整套配色；取不到壁纸时提示并自动关掉。
        val monetSw = UiKit.Switch(ctx)
        monetSw.tag = TAG_THEME_MONET
        monetSw.setOn(ThemeManager.monet(ctx), false)
        lookBody.addView(
            HomeCards.switchRow(
                ctx, T_THEME_MONET, monetSw,
                HomeCards.OnChanged { on, c ->
                    onMonetChanged(on, c)
                }
            )
        )
        // 「聊天背景」卡片正文（透明度说明 / 标签 / 滑条）并进「外观」。
        //   【修】这张卡片原来被整张 kill 掉，透明度调节就此消失；本轮两个入口已改成
        //   「外观」里的行，卡片正文里那两颗废弃大按钮跳过不搬，其余（说明 / 标签 / 滑条）整体搬过来。
        if (cardBg != null) {
            val bgBody = cardBg.getChildAt(1) as LinearLayout
            val bgChildren = ArrayList<View>()
            for (i in 0 until bgBody.childCount) {
                bgChildren.add(bgBody.getChildAt(i))
            }
            for (i in bgChildren.indices) {
                val c = bgChildren[i]
                // 跳过原两个按钮：入口已在「外观」里改用行样式，这两颗控件连同卡片壳一起弃用。
                val tag = c.tag
                if (tag != null && (HomeCards.TAG_BG_PICK == tag
                            || HomeCards.TAG_BG_CLEAR == tag)
                ) {
                    continue
                }
                bgBody.removeView(c)
                // 顶部间距由「外观」卡片内既有行给出，这里抹掉原有的 10dp 上边距。
                val clp = LinearLayout.LayoutParams(-1, -2)
                clp.topMargin = 0
                lookBody.addView(c, clp)
            }
        }
        syncTheme(activity)
        syncLookBg(activity)

        // 「人偶」：目前为空位，后续加人偶时往这里塞。
        val cardDoll = HomeCards.buildCard(ctx, T_DOLL_NEW)
        val dollBody = cardDoll.getChildAt(1) as LinearLayout
        HomeCards.addHint(ctx, dollBody, T_EMPTY_HINT)
        val dollAct = activity
        val scaleRow = HomeCards.stepperRow(
            ctx, T_SCALE, TAG_DOLL_SCALE,
            Runnable { stepPetScale(dollAct, -1) },
            Runnable { stepPetScale(dollAct, 1) }
        )
        dollBody.addView(scaleRow)
        HomeCards.setStepperValue(scaleRow, PetPrefs.petScaleDisplay(PetPrefs.petScale(activity)).toString() + "%")
        HomeCards.addHint(ctx, dollBody, T_SCALE_HINT)

        box.removeAllViews()
        box.addView(header)
        box.addView(cardPerm)
        if (cardChat != null) {
            box.addView(cardChat)
        }
        box.addView(cardDoll)
        box.addView(cardLook)
        if (cardOp != null) {
            box.addView(cardOp)
        }
        // 「记忆」：自动总结开关 + 触发阈值 + 记忆库入口。
        // 开关与阈值是「上下文总结 / 压缩」的两个旋钮；记忆库是 AI 自主写下的长期记忆，独立于压缩。
        val cardMem = HomeCards.buildCard(ctx, T_EMPTY[1])
        val memBody = cardMem.getChildAt(1) as LinearLayout
        val memCtx = ctx
        val memSw = UiKit.Switch(ctx)
        memSw.tag = TAG_MEM_AUTO
        memSw.setOn(PetPrefs.memAuto(ctx), false)
        memBody.addView(
            HomeCards.switchRow(ctx, T_MEM_AUTO, memSw, HomeCards.OnChanged { on, c ->
                PetPrefs.setMemAuto(c, on)
                syncMem(c)
            })
        )
        // 【d19 移植】原加号面板里的两个开关搬到本卡片下级，与「自动总结」同样式、同间距；
        //   不带副标题小字（用户要求），三行开关因此在视觉上连成一片。
        val memSaveSw = UiKit.Switch(ctx)
        memSaveSw.tag = TAG_MEM_SAVE
        memSaveSw.setOn(PetPrefs.memAutoSave(ctx), false)
        memBody.addView(
            HomeCards.switchRow(ctx, T_MEM_SAVE, memSaveSw, HomeCards.OnChanged { on, c ->
                PetPrefs.setMemAutoSave(c, on)
                syncMem(c)
            })
        )
        val memMergeSw = UiKit.Switch(ctx)
        memMergeSw.tag = TAG_MEM_MERGE
        memMergeSw.setOn(PetPrefs.memAutoMerge(ctx), false)
        memBody.addView(
            HomeCards.switchRow(ctx, T_MEM_MERGE, memMergeSw, HomeCards.OnChanged { on, c ->
                PetPrefs.setMemAutoMerge(c, on)
                syncMem(c)
            })
        )
        val memBodyRef = memBody
        val thresholdRow = HomeCards.stepperRow(
            ctx, T_MEM_THRESHOLD, TAG_MEM_THRESHOLD,
            Runnable {
                PetPrefs.setMemThresholdIndex(memCtx, PetPrefs.memThresholdIndex(memCtx) - 1)
                syncMem(memCtx)
            },
            Runnable {
                PetPrefs.setMemThresholdIndex(memCtx, PetPrefs.memThresholdIndex(memCtx) + 1)
                syncMem(memCtx)
            }
        )
        memBodyRef.addView(thresholdRow)
        // 【需求】「打开记忆库」由整宽白底描边按钮改成卡片内既有行样式（与「主题模式」一致）。
        val memLibRow = HomeCards.valueRow(ctx, T_MEM_LIB, TAG_MEM_LIB)
        HomeCards.setRowValue(memLibRow, T_LOOK_BG_GO)
        memLibRow.setOnClickListener { v ->
            MemPage.open(v.context)
        }
        memBodyRef.addView(memLibRow)
        syncMem(activity)
        box.addView(cardMem)
        // 「关于」：原来是展开式卡片，v2.10.0 改成一行入口，点击进整页（AboutPage）。
        //   卡片外壳与标题「关于」保留不动 —— SettingsPage 按标题文本分组，改了会整体错位。
        val cardAbout = HomeCards.buildCard(ctx, T_EMPTY[2], true)
        val aboutBody = cardAbout.getChildAt(1) as LinearLayout
        // 【需求】「关于本软件」同样由整宽按钮改成行样式，与卡片内其余行观感统一。
        val aboutRow = HomeCards.valueRow(ctx, "关于本软件", TAG_ABOUT_ENTRY)
        HomeCards.setRowValue(aboutRow, T_LOOK_BG_GO)
        aboutRow.setOnClickListener { v ->
            AboutPage.open(v.context)
        }
        aboutBody.addView(aboutRow)
        box.addView(cardAbout)

        // 【d17】整页错峰入场：此前设置页是「啪」地一次性全出现，而首页是逐项入场，两页手感不一致。
        //   这里对设置页外层容器统一错峰（在所有 addView 完成之后调用，卡片顺序即入场顺序）。
        if (!PetPrefs.themeRestore(ctx)) {
            UiKit.enterList(box, 6)
        }
    }

    @JvmStatic
    fun show(activity: Activity, home: Boolean) {
        try {
            val settings = find(activity, TAG_SET)
            val homeView = find(activity, TAG_HOME)
            if (settings == null || homeView == null) {
                return
            }
            if (PetPrefs.themeRestore(activity)) {
                // 换主题重建：直接落到目标页，不重播动画，免得又闪一次。
                homeView.visibility = if (home) View.VISIBLE else View.GONE
                settings.visibility = if (home) View.GONE else View.VISIBLE
                return
            }
            // 切页：目标页淡入、旧页同步淡出（两页同宿主，必须交叉，否则会糊在一起）。
            showDiff(
                homeView.parent as? ViewGroup,
                if (home) homeView else settings, if (home) settings else homeView
            )
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /**
     * 同一宿主内的「两页二选一」：目标页淡入 + 旧页同步淡出。
     * 【为何不用 UiKit.enter】enter 只管入场；旧页若仍可见，两页会同时叠着糊一下。
     */
    private fun showDiff(host: ViewGroup?, inView: View?, out: View?) {
        if (host == null || inView == null || out == null) {
            return
        }
        inView.visibility = View.VISIBLE
        inView.alpha = 0f
        out.alpha = 1f
        inView.animate().alpha(1f).setDuration(UiKit.D_LAYER.toLong()).setInterpolator(UiKit.EASE_DECEL)
            .withEndAction {
                if (out.alpha < 0.05f) {
                    out.visibility = View.GONE
                }
            }.start()
        out.animate().alpha(0f).setDuration(UiKit.D_MICRO.toLong()).setInterpolator(UiKit.EASE_ACCEL).start()
    }

    // 按 tag 在 decorView 里找控件（本轮改造的通用定位手段）。
    private fun find(activity: Activity?, tag: String): View? {
        val decor = if (activity == null || activity.window == null) {
            null
        } else {
            activity.window.decorView
        }
        if (decor !is ViewGroup) {
            return null
        }
        return decor.findViewWithTag<View>(tag)
    }

    /**
     * 【已删·findButtonByTag / detachFromParent】原用于把「聊天背景」卡片里两颗大按钮摘出来
     *   搬进「外观」。本轮两入口已改成行样式（bgRow / bgClearRow），这两个私有方法不再有调用方。
     */
    // 深度优先找第一个 ScrollView：首页整页都挂在它下面。
    private fun findScrollView(v: View?): ScrollView? {
        if (v == null) {
            return null
        }
        if (v is ScrollView) {
            return v
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findScrollView(v.getChildAt(i))
                if (r != null) {
                    return r
                }
            }
        }
        return null
    }
}
