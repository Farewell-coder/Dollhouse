package com.dollhouse.app.ui.compose

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 【职责】把 Compose 内容挂进本工程「手写 View + 自建页面栈」的既有世界，并补齐 Compose 运行所需的三个 owner。
 *
 * 【为什么必须自己补 owner】本工程所有 Activity 都继承**原生 `android.app.Activity`**
 *   （`MainActivity` / `ChatActivity` / `PickFileActivity` / `BackgroundCropActivity` / `PetLinkActivity`），
 *   而 `ViewTreeLifecycleOwner` / `ViewTreeSavedStateRegistryOwner` / `ViewTreeViewModelStoreOwner`
 *   只有 `androidx.activity.ComponentActivity` 才会自动装。原生 Activity 上直接塞 `ComposeView`，
 *   会抛 `ViewTreeLifecycleOwner not found from DecorView` 或 `Cannot locate windowRecomposer` —— 必崩。
 *
 * 【为什么 MainActivity 不直接改继承 ComponentActivity】`ComponentActivity` 把
 *   `onRetainNonConfigurationInstance()` 标成了 **final**，而 `MainActivity` 恰好重写了它
 *   （用于「配置变更重建时把在途输入走纯内存草稿传递」）。改基类 = 编译失败 + 丢失该保命能力。
 *   故保持原生 `Activity`，在本类里手工补齐 owner，行为等价。
 *
 * 【两条使用路径】
 *   1. Activity 常规页：[installForActivity] 把 owner 装到 decorView 上，之后整棵树里的
 *      任何 `ComposeView` 都能沿 ViewTree 找到 owner，无需逐个安装。
 *   2. Service 悬浮窗（桌宠）：[attachOverlayOwner] 把 owner 装到那个根 `View` 上；
 *      悬浮窗不在任何 Activity 的 ViewTree 里，必须自己带 owner。
 *
 * 【与既有页面栈的关系】`UiKit.openPage(content, page, tag)` 收的是 `View`，
 *   所以每个 Compose 页对外就是一个 [createView] 产出的 `ComposeView` —— 自建栈的
 *   路由语义、`ThemeRefresh` 的状态保存恢复、`GlobalBackground.installPage` 全部照旧工作。
 */
object ComposeHost {

    /**
     * 给一个 Activity 安装 Compose 运行所需的 owner（幂等）。
     * 装在 `decorView` 上，覆盖整棵视图树；重复调用不会重复创建。
     *
     * @return 本次真正安装的 owner；已装过则返回既有实例。
     */
    @JvmStatic
    fun installForActivity(activity: Activity?): HostOwner? {
        if (activity == null) {
            return null
        }
        val decor = activity.window?.decorView ?: return null
        val existing = decor.findViewTreeLifecycleOwner()
        if (existing is HostOwner && !existing.isDestroyed()) {
            return existing
        }
        val owner = HostOwner()
        attachOwners(decor, owner)
        owner.onCreate()
        // 【必须立刻进 RESUMED】Compose 的帧时钟由 `PausableMonotonicFrameClock` 控制，
        //   而它**只在 Lifecycle.Event.ON_START 时 resume()**（已 javap 核实
        //   `WindowRecomposer_androidKt$createLifecycleAwareWindowRecomposer$2.onStateChanged`：
        //   1=ON_CREATE 起协程、2=ON_START 恢复帧时钟、3=ON_STOP 暂停、4=ON_DESTROY 取消）。
        //   停在 CREATED 的话重组协程会挂在 awaitFrame 上永不恢复 —— 表现就是整页白屏。
        //   本工程页面是在 Activity 已在前台时被塞进来的，所以此处直接对齐到 RESUMED；
        //   后续的退后台 / 回前台由 [install] 注册的 Activity 回调继续驱动。
        owner.onStart()
        return owner
    }

    /**
     * 给一个 `Dialog` 装 owner。
     *
     * 【为什么必须单独一个入口】`Dialog` 是独立 Window，它的 ViewTree 与 Activity 的
     *   decorView 完全不相干 —— 挂在 Activity 上的 owner 沿 ViewTree 找不到，
     *   `ComposeView` attach 时会抛 `ViewTreeLifecycleOwner not found from DecorView`
     *   （或 `Cannot locate windowRecomposer`）。
     * 【调用时机】必须在 `setContentView` **之前**调，否则 ComposeView 先 attach 就已经崩了。
     *   本方法内部会先取 `window.decorView`（触发 installDecor）再装 owner。
     * 【销毁】调用方应在 `setOnDismissListener` 里调 [HostOwner.onDestroy]，避免 store 泄漏。
     */
    @JvmStatic
    fun installForDialog(dialog: android.app.Dialog?): HostOwner? {
        if (dialog == null) {
            return null
        }
        val decor = dialog.window?.decorView ?: return null
        val existing = decor.findViewTreeLifecycleOwner()
        if (existing is HostOwner && !existing.isDestroyed()) {
            return existing
        }
        val owner = HostOwner()
        attachOwners(decor, owner)
        owner.onCreate()
        owner.onStart()
        return owner
    }

    /**
     * 注册进程级的 Activity 生命周期回调，用来驱动 [HostOwner] 的 RESUMED / CREATED 切换。
     *
     * 【为什么不用逐个 Activity 里手写 onResume/onPause】本工程有 5 个 Activity 都可能承载
     *   Compose 页，逐个改既侵入又容易漏。挂 Application 级回调是官方做法，一处生效。
     *
     * 【为什么不在 installForActivity 里直接上 onStart 就完事】owner 会一直挂在 decorView 上，
     *   用户退到后台再回来时如果没人重新 resume，第二次进页面仍是白屏。所以必须持续驱动。
     */
    @JvmStatic
    fun install(app: android.app.Application) {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
                // 页面尚未挂载，installForActivity 会在真正需要时处理。
            }

            override fun onActivityStarted(activity: Activity) {
                ownerOf(activity)?.onStart()
            }

            override fun onActivityResumed(activity: Activity) {
                ownerOf(activity)?.onStart()
            }

            override fun onActivityPaused(activity: Activity) {
                // 【不在这里停】Pause 常由弹窗 / 权限框引起，停掉会让弹窗后面的页面白屏。
            }

            override fun onActivityStopped(activity: Activity) {
                ownerOf(activity)?.onStop()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {
            }

            override fun onActivityDestroyed(activity: Activity) {
                ownerOf(activity)?.onDestroy()
            }
        })
    }

    /** 取某个 Activity 的 decorView 上已安装的 owner（未安装则 null）。 */
    private fun ownerOf(activity: Activity?): HostOwner? {
        val decor = activity?.window?.decorView ?: return null
        val existing = decor.findViewTreeLifecycleOwner()
        return if (existing is HostOwner) existing else null
    }

    /**
     * 把 owner 装到一个游离的根 View 上（桌宠悬浮窗 / 任何不在 Activity 树里的 Compose 容器）。
     *
     * 【必须用同一个 owner 贯穿 detach → attach】桌宠的「暂离」是 `removeView` → 稍后 `addView`
     *   同一个实例。owner 若每次重挂都新建，Compose 组合会被销毁重建、状态全丢。
     *   故调用方应持有返回的 owner，重挂时复用。
     */
    @JvmStatic
    fun attachOverlayOwner(root: View): HostOwner {
        val existing = root.findViewTreeLifecycleOwner()
        if (existing is HostOwner && !existing.isDestroyed()) {
            return existing
        }
        val owner = HostOwner()
        attachOwners(root, owner)
        owner.onCreate()
        // 【同 installForActivity 的口径】悬浮窗不在任何 Activity 的 ViewTree 里，
        //   拿不到 Activity 的前后台信号，只能由调用方决定存活期；这里直接进 RESUMED，
        //   否则帧时钟不恢复、组合挂在 awaitFrame 上，表现同样是白屏。
        //   移除窗口时调用方需自行调 [HostOwner.onDestroy]。
        owner.onStart()
        return owner
    }

    /**
     * 建一个可直接塞进既有 View 体系的 Compose 视图。
     *
     * @param ctx     上下文（Activity 或 Service 均可）。
     * @param content Compose 内容；会自动包上 [DollhouseTheme]。
     */
    @JvmStatic
    fun createView(ctx: Context, content: @Composable () -> Unit): ComposeView {
        val cv = ComposeView(ctx)
        // 【策略】默认策略在「脱离窗口」时销毁组合 —— 而本工程覆盖页是 removeView/addView 复用的，
        //   默认策略会导致每次重挂都重建组合、状态丢失。改为「跟随 owner 销毁」，
        //   与 owner 的生命周期对齐，重挂不重建。
        cv.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        cv.setContent {
            DollhouseTheme {
                content()
            }
        }
        return cv
    }

    /**
     * 把 Compose 视图作为一个「页面」放进既有容器（等价于 `UiKit.openPage` 的入参）。
     * 只负责挂载与铺满，不参与转场动画 —— 动画仍由 `UiKit.openPage` / `swapPage` 统一驱动。
     */
    @JvmStatic
    fun addPage(content: ViewGroup?, page: View?) {
        if (content == null || page == null) {
            return
        }
        content.addView(page, ViewGroup.LayoutParams(-1, -1))
    }

    /** 安装三个 owner。顺序无关，但必须在任何 `ComposeView` attach 之前完成。 */
    private fun attachOwners(view: View, owner: HostOwner) {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
    }

    /**
     * 供 Activity / Service 复用的最小 owner 组合。
     *
     * 【三个接口缺一不可】
     *   - [LifecycleOwner]：Compose 靠它驱动 `windowRecomposer` 与 `LaunchedEffect` / `DisposableEffect`；
     *   - [ViewModelStoreOwner]：`viewModel()` / `rememberSaveable` 需要它；
     *   - [SavedStateRegistryOwner]：`rememberSaveable` 落盘恢复需要它。
     *
     * 【生命周期口径】owner 必须走到 RESUMED，Compose 才会开始绘制。
     *   调用方按真实时机调 [onStart] / [onStop]；[onCreate] 只做一次性初始化。
     */
    class HostOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val controller = SavedStateRegistryController.create(this)
        private var created = false

        override val lifecycle: Lifecycle
            get() = registry

        override val viewModelStore: ViewModelStore
            get() = store

        override val savedStateRegistry: SavedStateRegistry
            get() = controller.savedStateRegistry

        /** 一次性初始化：恢复 saved state 并进入 CREATED。重复调用安全。 */
        fun onCreate() {
            if (created) {
                return
            }
            created = true
            // performRestore 必须在生命周期离开 INITIALIZED 之前调用，否则抛异常。
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        /** 进入前台：Compose 开始组合与绘制。 */
        fun onStart() {
            onCreate()
            registry.currentState = Lifecycle.State.RESUMED
        }

        /** 退到后台：暂停重组（不销毁组合，状态保留）。 */
        fun onStop() {
            if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                registry.currentState = Lifecycle.State.CREATED
            }
        }

        /** 是否已走到 DESTROYED。用来判定挂在 decorView 上的 owner 是否已作废。 */
        fun isDestroyed(): Boolean {
            return registry.currentState == Lifecycle.State.DESTROYED
        }

        /** 真正销毁：清空 ViewModelStore，释放 Compose 侧资源。 */
        fun onDestroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }
}