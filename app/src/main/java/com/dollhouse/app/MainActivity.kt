package com.dollhouse.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.device.ShizukuBridge
import com.dollhouse.app.pet.PetToggle
import com.dollhouse.app.ui.compose.PageRegistry
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.provider.ProviderNav
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
        // 【r13·三 Tab】解锁 Tab 恢复闸门：Activity 重建后要让 AppNav 重新读一次偏好，
        //   否则会停在内存里的默认值（聊天），把用户重建前停留的 Tab 丢掉。
        com.dollhouse.app.ui.home.AppNav.unlock()
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
        // 【r11 种子】默认配置的交谈 / 性格内容继承改造前的内置值；幂等，只写一次。
        //   放在首页装配之前：编辑页 / 聊天页首帧就要能读到这份种子。
        com.dollhouse.app.data.DollProfile.seedDefault(this)
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
                restorePageStack(savedInstanceState)
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
     * 【单实例唤回】manifest 里本页是 `singleTask`：从最近任务 / 通知再进来时**不重建实例**，
     *   而是走这个方法。此前没有重写它，等于「被唤回时什么都不做」——
     *   若上次离开时层序已清过账、View 却还挂在树上（退场动画被打断，或厂商 ROM 清理后台
     *   只摘任务不销毁进程），回来就会看到「视觉停在主页、触摸仍落二级页」。
     *   这里补上：接住新 Intent，并立刻做一次层级对账，把记账与真实 View 树拉回一致。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // AOSP 不会自动更新 getIntent()，须手动 set，否则后续读到的还是首次启动的那个。
        setIntent(intent)
        PageStack.reconcile(this)
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
     * 【路由】整条页面层序（Home 上的覆盖层）交给 [savePageStack] / [restorePageStack] 存取。
     * 【隐私】只写稳定标识与已登记白名单输入：密钥这类文本绝不进 Bundle。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            outState.putAll(ThemeRefresh.saveState(window?.decorView))
        } catch (ignored: Throwable) {
        }
        try {
            savePageStack(outState)
        } catch (ignored: Throwable) {
        }
    }

    // ---- 路由状态：页面 key（与各页面自己的 key 常量逐字一致，跨类按 key 定位） ----
    // 【迁 PageStack】原先记「设置页可见性 + 三个覆盖页互斥挑一个 + 提供商栈」，
    //   是「同一时刻只记得住一层」的老模型；现在整条层序交给 PageStack.keys()。
    private val KEY_PAGE_STACK = "dh_page_stack"

    /** 提供商模块内部业务路由栈的存档键（沿用历史键名，兼容旧存档读取逻辑）。 */
    private val KEY_PROVIDER_ROUTES = "dh_provider_routes"

    /** 采集当前整条页面层序（全为稳定 key，不含任何输入内容）。 */
    private fun savePageStack(outState: Bundle) {
        val keys = PageStack.keys(this)
        if (!keys.isEmpty()) {
            outState.putStringArrayList(KEY_PAGE_STACK, keys)
        }
        // 提供商模块的内部业务路由（列表 / 新建 / 编辑 / 模型 …）一并存下：
        //   它只占 PageStack 的一层，层序无法表达其内部深度，故单独记一份。
        if (keys.contains(ProviderNav.TAG_PAGE)) {
            outState.putStringArrayList(KEY_PROVIDER_ROUTES, ProviderNav.saveRoutes())
        }
    }

    /**
     * 按 [savePageStack] 存下的 key 逐层重建。
     * 【顺序】自底向上重建，底层先入栈，上层后压 —— 与重建前的层序完全一致。
     * 【供应商模块】它一层壳里还有自己的业务路由栈，先回填其记账再建页，否则会退化成列表页。
     * 【不播动画】重建应「静止在终点」，全程用 reset 之外的静默路径（push 会滑入，故随后静置）。
     */
    private fun restorePageStack(state: Bundle) {
        if (isFinishing) {
            return
        }
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        PageStack.reset(this)
        val keys = state.getStringArrayList(KEY_PAGE_STACK)
        if (keys.isNullOrEmpty()) {
            // 没有层：只把首页 / 设置页之间的过场状态静置（与旧逻辑的兜底一致）。
            ThemeRefresh.settleAfterRestore(content)
            return
        }
        for (key in keys) {
            if (ProviderNav.TAG_PAGE == key) {
                ProviderNav.setRoutes(state.getStringArrayList(KEY_PROVIDER_ROUTES))
            }
            PageStack.push(this, key) { PageRegistry.build(this, key) }
        }
        // 【静置】以上入栈都带 300ms 滑入；就地取消并复位，让恢复后停在最终位置。
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
        // 【层级对账】回前台无条件对一次账：把「记账里的层」与「content 上真实挂的 View」拉齐。
        //   覆盖三条会把两者搞错位的路径：singleTask 唤回不重建 View 树、退场动画被切后台打断、
        //   厂商 ROM 清理后台只摘任务不销毁进程。幂等，无错位时零副作用。
        PageStack.reconcile(this)
        // 【r13·修 bug·三页同时可见】把三个 Tab 页强行收成「只有当前 Tab 可见」。
        //   【为什么必须在这里做】`PageStack.reconcile` 只管**覆盖层**，管不到底座这三个 Tab 页；
        //   而「切页动画被切后台打断」会让旧页停在「alpha≈0 但 VISIBLE」——
        //   回前台时多页同时可见，视觉上就是三个页面叠在一起（用户截图报的那个）。
        //   本方法只做状态归位（零动画、幂等），回前台「静止在终点」，正好符合预期。
        HomeUi.settleTabs(this)
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

    /**
     * 【层级回收】宿主销毁时把本实例的页面层序分桶一并丢掉。
     *   层里存的是整页 View，不回收会让旧 Activity 的 View 树跟着分桶表常驻。
     *   配置变更时本方法同样会走到：此时存档已在 [onSaveInstanceState] 里存好，
     *   新实例会在 onCreate 用新身份建新桶并逐层还原，两边不会互串。
     */
    override fun onDestroy() {
        PageStack.reset(this)
        super.onDestroy()
    }

    /**
     * 【返回键统一入口】顺序即优先级，四条语义互斥、逐层降级：
     *   ① [ProviderNav.handleBack] —— 提供商模块「一层壳 + 内部业务路由」：只在自己那层位于
     *      最上层时接管，回退一格内部路由（层数不变）；不是最上层就交出去。
     *   ② [PageStack.handleBack] —— 层级系统的统一兜底：Token / 记忆 / 关于 / 人偶列表 /
     *      设置页 / 人偶编辑全都只是栈里的一层，弹掉栈顶即可。
     *   ③ [HomeUi.handleBack] —— 底座（三个 Tab）不属于覆盖层，不进 [PageStack]：
     *      在非「聊天」Tab 时回到「聊天」，在「聊天」时返回 false 交给系统。
     */
    override fun onBackPressed() {
        if (ProviderNav.handleBack(this) || PageStack.handleBack(this)
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
