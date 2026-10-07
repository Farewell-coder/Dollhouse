package com.dollhouse.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.device.ShizukuBridge
import com.dollhouse.app.pet.PetToggle
import com.dollhouse.app.ui.chat.ChatSettingsSection
import com.dollhouse.app.ui.home.HomeScreenBuilder
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.AboutPage
import com.dollhouse.app.ui.settings.MemPage
import com.dollhouse.app.ui.settings.SettingsPage
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.UiKit
import rikka.shizuku.Shizuku

/**
 * 【职责】主界面宿主（Manifest 用相对名 .MainActivity 引用，类名不可改）。
 *          只留生命周期、桌宠启停、权限申请、背景选择回调这些「宿主职责」。
 * 【入口】桌面图标 / 通知栏启动（长按桌宠进面板已下线）。
 * 【交互】首页控件由 HomeScreenBuilder 搭建、ChatSettingsSection 处理设置区行为、
 *         SettingsPage 与 HomeUi 在 onCreate 末尾做二次重排；桌宠启停用 Intent 发给 PetService。
 * 【坑】部分控件字段是包级可见，供 HomeScreenBuilder / ChatSettingsSection 直接读写；
 *       不要改回 private，否则那两个类编译不过。
 */
class MainActivity : Activity() {

    /** 通知权限请求码。 */
    private val REQ_NOTIF = 101

    // ---- 以下字段由 HomeScreenBuilder 赋值，ChatSettingsSection / HomeUi 读取 ----
    internal var status: TextView? = null
    internal var bgAlphaLabel: TextView? = null
    internal var testResult: TextView? = null
    internal var overlayBtn: Button? = null
    internal var startBtn: Button? = null
    internal var stopBtn: Button? = null
    internal var bgBtn: Button? = null
    internal var keyInput: EditText? = null
    internal var modelInput: EditText? = null
    internal var urlInput: EditText? = null
    /** 每秒刷新一次权限与状态。 */
    private val ticker = Handler(Looper.getMainLooper())
    /**
     * Shizuku 授权结果监听：server 那边确认/拒绝后回调，回来刷新「权限」卡片里的授权行。
     * 【为什么只刷新不提示】UiKit.java:30 硬约束禁止 Toast / Snackbar / 自实现浮层短提示，
     *   授权成功与否直接体现在那一行的绿字/红字上，不需要再弹一层。
     */
    private val shizukuPermListener: Shizuku.OnRequestPermissionResultListener =
            object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    // 授权结果刚到，先作废状态缓存再刷新，否则要等 TTL 过了才显示新状态。
                    ShizukuBridge.invalidate()
                    HomeUi.sync(this@MainActivity)
                }
            }
    /** binder 就绪监听：服务后启动（用户去管理器里开了服务）时自动把授权行刷成新状态。 */
    private val shizukuBinderListener: Shizuku.OnBinderReceivedListener =
            object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    ShizukuBridge.invalidate()
                    HomeUi.sync(this@MainActivity)
                }
            }
    /**
     * 【去重】监听器是否已挂上。
     * 【为什么需要】Shizuku.addRequestPermissionResultListener 内部不做去重，而 onResume
     *   有可能在没有配对 onPause 的情况下被再调一次（厂商 ROM 的多窗口/分屏重建路径），
     *   那就会把同一个 listener 挂多份，回调被重复触发。
     */
    private var shizukuListenersOn = false
    private val tick = object : Runnable {
        override fun run() {
            refreshLocalUi()
            ticker.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先按当前主题档位把配色刷进 UiKit，再建界面；否则首帧会用上一轮的旧配色。
        // apply() 只读取色缓存，不在主线程解码壁纸，冷启动不会被壁纸拖慢。
        ThemeManager.apply(this)
        // 开了莫奈但还没缓存（首次启用 / 换过壁纸）时，后台补一次取色，回来再重建界面。
        if (ThemeManager.monet(this) && !ThemeManager.hasMonetColor(this)) {
            ThemeManager.monetHueAsync(this, object : ThemeManager.HueCallback {
                override fun onHue(ok: Boolean) {
                    if (ok && !isFinishing) {
                        ThemeManager.apply(this@MainActivity)
                        recreate()
                    }
                }
            })
        }
        setContentView(HomeScreenBuilder.build(this))
        // 【状态栏嵌入】状态栏透明 + 铺满，消除顶部那条系统灰。
        //   放在 setContentView 之后：需要 decorView 已存在才好刷图标反色。
        UiKit.applyEdgeToEdge(this)
        SettingsPage.apply(this)
        HomeUi.apply(this)
        // 换主题重建的话，把滚动位置滚回原处（冷启动时这个标志是关的，不会乱跳）。
        val silkRestored = PetPrefs.themeRestore(this)
        HomeUi.restoreScroll(this)
        // 【丝滑】换主题重建会重跑 onCreate，新界面首帧直出；盖一层底色淡出，消除闪白。
        if (silkRestored) {
            UiKit.themeFade(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // 【修 v0.0.1】「默认一直开着」：用户没主动关过就保证人偶常驻。
        //   开机广播在 ColorOS 上可能被拦，这里以「每次回主页对账」作为第二道保险（幂等）。
        PetToggle.aliveOnBoot(this)
        refresh()
        refreshLocalUi()
        HomeUi.sync(this)
        // 【Shizuku】监听挂在这一对生命周期里：授权结果 / 服务后启动都要能自动刷回界面。
        if (!shizukuListenersOn) {
            shizukuListenersOn = true
            ShizukuBridge.addPermissionListener(shizukuPermListener)
            ShizukuBridge.addBinderListener(shizukuBinderListener)
        }
        ticker.postDelayed(tick, 800L)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        // 【Shizuku】摘掉监听，避免页面不在前台时还持有 Activity 引用。
        ShizukuBridge.removePermissionListener(shizukuPermListener)
        ShizukuBridge.removeBinderListener(shizukuBinderListener)
        shizukuListenersOn = false
        super.onPause()
    }

    override fun onBackPressed() {
        if (ProviderNav.handleBack(this) || TokenStat.closeIfOpen(this)
                || MemPage.closeIfOpen(this) || AboutPage.closeIfOpen(this)
                || HomeUi.handleBack(this)) {
            return
        }
        super.onBackPressed()
    }

    /** 尺寸换算：统一走 UiKit，保留小数精度（控件间距按 dp 计算）。 */
    internal fun dp(value: Float): Float {
        return UiKit.dpf(this, value)
    }
    /**
     * 滑块上方标题行文案：只给「这是什么」+ 区间可读性提示。
     *
     * 【为何不再带百分比】百分比现在由 UiKit.Slider 自己画在轨道右侧（跟随拖动实时变），
     *   两处都写数值会重复，而且标题行里的数字拖动时并不刷新，看着像卡住。
     */
    internal fun updateBgAlphaLabel(percent: Int) {
        val label = bgAlphaLabel
        if (label == null) {
            return
        }

        label.text = "\u80cc\u666f\u56fe\u900f\u660e\u5ea6\u00a0\u00a0" +
                (if (percent <= 8) "\uff08\u51e0\u4e4e\u770b\u4e0d\u89c1\u4e86\uff09"
                else if (percent <= 45) "\uff08\u63a8\u8350\uff0c\u5b57\u6700\u6e05\u695a\uff09"
                else if (percent <= 75) "\uff08\u56fe\u66f4\u660e\u663e\uff0c\u6ce8\u610f\u770b\u5b57\uff09"
                else "\uff08\u539f\u56fe\uff0c\u6c14\u6ce1\u53ef\u80fd\u548c\u80cc\u666f\u7cca\u5728\u4e00\u8d77\uff09")
    }

    /** 刷新与本地偏好相关的控件文案。 */
    internal fun refreshLocalUi() {
        bgBtn?.text = if (PetPrefs.chatBackground(this).isEmpty())
            "\u9009\u4e00\u5f20\u56fe\u5f53\u804a\u5929\u80cc\u666f"
        else
            "\u6362\u4e00\u5f20\u804a\u5929\u80cc\u666f\uff08\u5f53\u524d\u5df2\u8bbe\u7f6e\uff09"
    }

    /** 刷新权限卡片与状态行。 */
    internal fun refresh() {
        val granted = ChatSettingsSection.hasOverlay(this)
        overlayBtn?.let {
            it.isEnabled = !granted
            it.text = if (granted)
                "\u2713 \u60ac\u6d6e\u7a97\u6743\u9650\u5df2\u6388\u4e88"
            else
                "1. \u6388\u4e88\u300c\u663e\u793a\u5728\u5176\u4ed6\u5e94\u7528\u4e0a\u5c42\u300d"
        }
        status?.let {
            it.text = if (granted)
                "\u51c6\u5907\u5c31\u7eea\uff0c\u70b9\u4e0b\u9762\u7684\u6309\u94ae\u53eb\u5979\u51fa\u6765\u3002"
            else
                "\u8fd8\u5dee\u4e00\u6b65\uff1a\u9700\u8981\u5141\u8bb8\u672c\u5e94\u7528\u663e\u793a\u5728\u5176\u4ed6\u5e94\u7528\u4e0a\u5c42\u3002"
        }
    }

    /** 通知桌宠服务刷新贴图与背景。 */
    internal fun notifyPetService() {
        try {
            val intent = Intent(this, PetService::class.java)
            intent.action = PetService.ACTION_REFRESH
            startService(intent)
        } catch (ignored: Throwable) {
        }
    }


    /** 跳系统悬浮窗授权页；失败退回总列表，再失败提示手动开。 */
    internal fun requestOverlay() {
        try {
            try {
                startActivity(Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION",
                        Uri.parse("package:" + packageName)))
            } catch (ignored: Throwable) {
                startActivity(Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION"))
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 点「启动桌宠」：先校验权限，再按版本决定是否走前台服务。 */
    internal fun onStartClicked() {
        if (!ChatSettingsSection.hasOverlay(this)) {
            requestOverlay()
            return
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
        }
        val intent = Intent(this, PetService::class.java)
        intent.action = PetService.ACTION_START
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /* ------------------------- 类尾 ------------------------- */
    /*
     * 【为什么不再实现 PickFileActivity.Listener】聊天背景改走 BackgroundCropActivity
     *   （选图 → 裁剪预览 → 按框落盘），结果由那一页自己写进 PetPrefs 并刷新桌宠服务；
     *   发图走 ChatPanel 自己的回调。宿主这边已无任何「等选图结果」的入口。
     */
}
