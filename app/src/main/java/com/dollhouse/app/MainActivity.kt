package com.dollhouse.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.device.ShizukuBridge
import com.dollhouse.app.pet.PetToggle
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.AboutPage
import com.dollhouse.app.ui.settings.MemPage
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.ThemeRefresh
import com.dollhouse.app.ui.theme.UiKit
import rikka.shizuku.Shizuku

/**
 * 【职责】主界面宿主（Manifest 用相对名 .MainActivity 引用，类名不可改）。
 *          只留生命周期、桌宠启停、路由状态保存这些「宿主职责」。
 * 【入口】桌面图标 / 通知栏启动（长按桌宠进面板已下线）。
 * 【交互】首页与设置页由 [HomeUi.apply] 装配成两棵独立 ComposeView；
 *         本类只驱动生命周期与状态同步，界面内容全部由 `HomeState` 驱动。
 * 【为什么不再持有控件字段】原实现用 10 个 `internal var` 把控件句柄回传给
 *         `HomeScreenBuilder` / `ChatSettingsSection` / `HomeUi` 三方直接读写；
 *         Compose 页没有「控件句柄」可找，这套通道整体作废，字段一并清理。
 */
class MainActivity : Activity() {

    /** 每秒刷新一次权限与状态（Compose 页靠它实时反映系统设置里的授权变化）。 */
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
            HomeUi.syncTick(this@MainActivity)
            ticker.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先按当前主题档位把配色刷进 UiKit，再建界面；否则首帧会用上一轮的旧配色。
        // apply() 只读取色缓存，不在主线程解码壁纸，冷启动不会被壁纸拖慢。
        ThemeManager.apply(this)
        // 【接收处夹紧】apply 之后立刻把正文/副标题/提示夹到 WCAG 4.5:1；
        // 并记下这份调色板作为基线，供就地换主题时做旧色→新色重映射。
        UiKit.clampPaletteContrast()
        ThemeRefresh.rememberPalette()
        // 开了莫奈但还没缓存（首次启用 / 换过壁纸）时，后台补一次取色，回来再刷新界面。
        if (ThemeManager.monet(this) && !ThemeManager.hasMonetColor(this)) {
            ThemeManager.monetHueAsync(this, object : ThemeManager.HueCallback {
                override fun onHue(ok: Boolean) {
                    if (ok && !isFinishing) {
                        // 【就地刷新】取到色相后原地换色：不 recreate、不跳回首页、不丢输入与滚动位置。
                        ThemeRefresh.applyInPlace(this@MainActivity)
                        notifyPetService()
                    }
                }
            })
        }
        // 【装配】首页 + 设置页两棵 ComposeView 由 HomeUi 一次性建好并挂进 content。
        //   不再 setContentView：Compose 页是「页面」而不是「内容视图」，走同一条挂载通道。
        HomeUi.apply(this)
        // 【状态栏嵌入】状态栏透明 + 铺满，消除顶部那条系统灰。
        //   放在页面挂载之后：需要 content 里已有子项才好按 insets 让位。
        UiKit.applyEdgeToEdge(this)
        // 换主题重建的话，把滚动位置滚回原处（冷启动时这个标志是关的，不会乱跳）。
        // 【一次性】先读后消费：HomeUi.restoreScroll 内部会把该标志清掉，故 themeFade 只可能触发一次。
        val silkRestored = PetPrefs.themeRestore(this)
        HomeUi.restoreScroll(this)
        // 【全局背景】首页与设置页共用同一张背景图：铺在两页共用的宿主上，
        // 两页滚动区自身置透明，保证同一张图只画一次（不重复叠图）。
        GlobalBackground.installHome(this)
        // 【路由恢复 → 状态恢复】系统日夜切换 / 配置变更重建本页时：HomeUi 已把两页搭好，
        //   先按稳定 tag 把「之前停在哪一页、哪个覆盖页」还原出来（不 recreate、不加过场），
        //   再回填滚动位置。顺序不能反：覆盖页（提供商栈 / 关于 / 记忆 / Token）尚未创建时，
        //   ScrollView 的遍历序对不上，saveState 存下的滚动序号会整体错位。
        //   两者都用 post 排在页面装配之后，保证在最终视图树上定位；
        //   首次冷启动（savedInstanceState 为空）完全不动，不跳回上次位置。
        // 【纯内存草稿】系统配置变更重建时，把在途输入（含 API Key、地址、模型名、聊天正文）
        //   从上一实例的 onRetainNonConfigurationInstance 取回：只走内存，不进 Bundle / 文件 / 日志，
        //   也不走 markRestorable 登记；最终路由恢复完成后再回填同一棵树，回填即清空。
        //   getLastNonConfigurationInstance 仅在 onCreate 期间有效，故此处先取出交给后续 post。
        val inputDraft = lastNonConfigurationInstance as? ThemeRefresh.InputDraft
        if (savedInstanceState != null) {
            val decor = window?.decorView
            decor?.post {
                restoreRouteState(savedInstanceState)
                ThemeRefresh.restoreState(decor, savedInstanceState)
                ThemeRefresh.restoreInputs(decor, inputDraft)
            }
        }
        // 【丝滑】换主题重建会重跑 onCreate，新界面首帧直出；盖一层底色淡出，消除闪白。
        if (silkRestored) {
            UiKit.themeFade(this)
        }
    }

    /**
     * 【重建保命·纯内存】配置变更重建时，把当前树上全部在途输入采集为纯内存草稿跨实例传递。
     *   刻意不用 Bundle：Key / 地址 / 模型名 / 聊天正文等隐私内容绝不落盘、不进日志。
     *   进程死亡不保留（临时草稿可接受）；[ThemeRefresh.restoreInputs] 回填后即刻清空。
     */
    override fun onRetainNonConfigurationInstance(): Any? {
        return ThemeRefresh.captureInputs(window?.decorView)
    }

    /**
     * 【重建保命】未加 configChanges，系统日夜切换 / 配置变更仍会重建本 Activity。
     * 这里按遍历序存下所有输入框文本与滚动区位置，重建后由 [ThemeRefresh.restoreState] 回填，
     * 使重建在观感上「位置与输入都没丢」。
     * 【路由】另存首页 / 设置页可见性、独立覆盖页与提供商栈，交给 [restoreRouteState] 还原。
     * 【隐私】只写稳定标识与已登记白名单输入：密钥这类文本绝不进 Bundle。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            outState.putAll(ThemeRefresh.saveState(window?.decorView))
        } catch (ignored: Throwable) {
        }
        try {
            saveRouteState(outState)
        } catch (ignored: Throwable) {
        }
    }

    // ---- 路由状态：页面 tag（与 HomeUi / 各覆盖页内常量逐字一致，跨类按 tag 定位） ----
    private val TAG_HOME_PAGE = "feiyu_home_page"
    private val TAG_SETTINGS_PAGE = "feiyu_settings_page"
    private val TAG_ABOUT_PAGE = "feiyu_about_page"
    private val TAG_MEM_PAGE = "feiyu_mem_page"
    private val TAG_TOKEN_PAGE = "feiyu_token_page"
    private val OVERLAY_ABOUT = "about"
    private val OVERLAY_MEM = "mem"
    private val OVERLAY_TOKEN = "token"
    private val KEY_SETTINGS_SHOWN = "dh_home_settings_shown"
    private val KEY_PROVIDER_ROUTES = "dh_provider_routes"
    private val KEY_OVERLAY_PAGE = "dh_overlay_page"

    /** 采集当前路由：设置页是否在前、独立覆盖页、提供商栈（全为稳定标识，不含任何输入内容）。 */
    private fun saveRouteState(outState: Bundle) {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        outState.putBoolean(KEY_SETTINGS_SHOWN,
                content?.findViewWithTag<android.view.View>(TAG_SETTINGS_PAGE)?.visibility
                        == android.view.View.VISIBLE)
        val overlay = when {
            content?.findViewWithTag<android.view.View>(TAG_TOKEN_PAGE) != null -> OVERLAY_TOKEN
            content?.findViewWithTag<android.view.View>(TAG_MEM_PAGE) != null -> OVERLAY_MEM
            content?.findViewWithTag<android.view.View>(TAG_ABOUT_PAGE) != null -> OVERLAY_ABOUT
            else -> null
        }
        if (overlay != null) {
            outState.putString(KEY_OVERLAY_PAGE, overlay)
        }
        if (ProviderNav.isOpen(this)) {
            outState.putStringArrayList(KEY_PROVIDER_ROUTES, ProviderNav.saveRoutes())
        }
    }

    /**
     * 还原 [saveRouteState] 存下的路由：直接改两页可见性（不走动画、不加过场），再按稳定栈重建覆盖页。
     * 【顺序】先落两页可见性，再恢复提供商栈，最后把独立覆盖页压在最上层，层级与重建前一致。
     */
    private fun restoreRouteState(state: Bundle) {
        if (isFinishing) {
            return
        }
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val home = content.findViewWithTag<android.view.View>(TAG_HOME_PAGE)
        val settings = content.findViewWithTag<android.view.View>(TAG_SETTINGS_PAGE)
        val settingsShown = state.getBoolean(KEY_SETTINGS_SHOWN, false)
        val shown = if (settingsShown) settings else home
        val hidden = if (settingsShown) home else settings
        // 【过场清理】HomeUi.show 建界面时的交叉淡入可能把某页 alpha 留在中间态，
        //   这里取消动画并复位：目标页完整可见、另一页彻底收起，不出现半透明残留。
        if (shown != null) {
            shown.animate().cancel()
            shown.visibility = android.view.View.VISIBLE
            shown.alpha = 1f
            shown.translationX = 0f
        }
        if (hidden != null) {
            hidden.animate().cancel()
            hidden.visibility = android.view.View.GONE
        }
        if (!ProviderNav.isOpen(this)) {
            ProviderNav.restoreRoutes(this, state.getStringArrayList(KEY_PROVIDER_ROUTES))
        }
        when (state.getString(KEY_OVERLAY_PAGE)) {
            OVERLAY_TOKEN -> TokenStat.open(this)
            OVERLAY_MEM -> MemPage.open(this)
            OVERLAY_ABOUT -> AboutPage.open(this)
        }
        // 【静置】以上覆盖页都走 UiKit.openPage 的 300ms 自右滑入；
        //   就地取消并复位，让恢复后的界面停在最终位置——不残留半透明与位移，
        //   也不会因为首页入场动画把视线带回主页。
        ThemeRefresh.settleAfterRestore(content)
    }

    override fun onResume() {
        super.onResume()
        // 【修 v0.0.1】「默认一直开着」：用户没主动关过就保证人偶常驻。
        //   开机广播在 ColorOS 上可能被拦，这里以「每次回主页对账」作为第二道保险（幂等）。
        PetToggle.aliveOnBoot(this)
        // 状态全量同步：权限 / 开关 / 外观 / 记忆 / 操作 / 首页按钮一次写进 HomeState。
        HomeUi.sync(this)
        // 【全局背景】回前台重挂一次：用户去选了新背景图后回来，路径变了会自动重解码并刷全部挂载点。
        GlobalBackground.installHome(this)
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

    /** 通知桌宠服务刷新贴图与背景（背景透明度拖动、换主题后都要重读）。 */
    internal fun notifyPetService() {
        try {
            val intent = Intent(this, PetService::class.java)
            intent.action = PetService.ACTION_REFRESH
            startService(intent)
        } catch (ignored: Throwable) {
        }
    }
}
