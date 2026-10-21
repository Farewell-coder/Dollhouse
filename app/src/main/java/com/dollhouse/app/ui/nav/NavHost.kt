package com.dollhouse.app.ui.nav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.PageRegistry
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】导航宿主：把工程的页面栈从自研实现换成 **Jetpack Navigation 3**。
 *
 * 【为什么要有这一层，而不是让调用方直接用 Nav3】
 *   全工程有 84 处按字符串 key 调 [PageStack]（`PageStack.push(act, "doll:edit:$id") { … }`）。
 *   一次性把这些调用点改成 `backStack.add(DollEditRoute(id))` 会把「导航迁移」变成
 *   「全工程重构」，风险与收益不成比例，且违背「不做无关重构」的要求。
 *   因此本类保持 [PageStack] 的对外签名不变（字符串 key + View 构造 lambda），
 *   内部把字符串经 [NavKeyCodec] 转成类型安全的 [NavKey] 后压进 Nav3 的 back stack。
 *   迁移是**替换地基**而不是改接口 —— 调用点零改动，路由与栈的真源已经换成 Nav3。
 *
 * 【构造器从哪来 —— 这是本次迁移最关键的一处设计】
 *   旧实现把「构造页面的 lambda」连同 key 一起交给 `PageStack.push`，页面在**入栈那一刻**
 *   就被建好并挂到树上。Nav3 是声明式的：`NavDisplay` 在 entry 成为栈顶时才调用构造。
 *   于是「入栈时捕获的构造 lambda」必须在 Nav3 需要时还能拿到。
 *   本类用 [BUILDERS] 按 key 记住入栈时传来的构造器：
 *   · 入栈时登记构造器（[push]）—— 参数（dollId / actionId）在闭包里，天然不丢；
 *   · NavDisplay 渲染时优先用它；**没有**（进程被杀后重建）才回落到
 *     [PageRegistry.build]（它从 key 字符串里解析参数）。
 *   【为什么不能只用 PageRegistry】工程里有页面把参数放在闭包里而不是 key 里 ——
 *   典型是 `MemPage.open(ctx, dollId)` 用 `push(ctx, TAG_PAGE) { buildPage(ctx, dollId) }`，
 *   而 `PageRegistry` 对 `TAG_PAGE` 只能调 `buildPage(act)`（无 dollId，回落默认人偶）。
 *   只走 PageRegistry 会让「记忆库」这类页面在渲染时**丢参数**，
 *   这正是用户报过的「返回数据丢失 / 页面恢复失败」的根因形态，必须从设计上杜绝。
 *
 * 【原地重建（[replaceTop] / [replaceTopSlide]）—— 兼容旧实现的关键一环】
 *   旧 [PageStack.replaceTop] 的语义是「**key 不变**，只把栈顶那一层的内容换成新的 View」。
 *   供应商模块（[com.dollhouse.app.ui.provider.ProviderNav]）依赖它实现
 *   「一层壳 + 内部四级路由」：列表 → 详情 → 模型列表 → 模型编辑，层深不变、内容替换。
 *   Nav3 按 key 找 entry，key 不变则内容不重建 —— 直接照搬会让供应商模块**内部导航全部失效**。
 *   故本类为每个宿主维护一个 [REVISIONS] 版本号：每次原地重建就 +1，并把它作为
 *   `NavEntry` 的 `contentKey`。contentKey 变化会让 NavDisplay 认定「这是不同的内容」而重建，
 *   从而精确复刻旧语义，且不引入第二份状态真源。
 *
 * 【为什么自己维护 [SnapshotStateList] 而不是用 rememberNavBackStack】
 *   `rememberNavBackStack` 走 kotlinx.serialization 的多态机制，要求把每个 NavKey 子类
 *   注册进 `SerializersModule`；漏注册只在**运行时**存档失败，编译期查不出来（本工程
 *   在「页面恢复失败」上已经吃过一次同类的亏）。
 *   本工程既有「Bundle 存字符串列表」的成熟存档通道（见 [save] / [restore]），
 *   字符串 key 本身也是稳定的对外契约。故：栈用普通 `SnapshotStateList`（NavDisplay 接受任意 List），
 *   存档继续走字符串 —— 两边各取所长，且恢复行为可预测。
 *
 * 【与旧 [PageStack] 的差异（都是改进）】
 *   · **点击穿透**：旧实现靠 `sealLayer` 给页根挂 `OnTouchListener{true}` 粗暴兜底；
 *     Nav3 每层是独立的 Compose 节点，非栈顶项根本不参与命中测试 → 穿透从根上不存在。
 *   · **幽灵层**：旧实现需要 `reconcile()` 对账「记账 vs 真实 View 树」；Nav3 只有一份真源
 *     （这个列表），不存在两份状态错位的可能 → 对账逻辑失去存在理由。
 *   · **页面生命周期**：旧实现靠 `removeView` 摘页，而 Compose 组合挂在 decorView 上不会随之销毁
 *     （这是「蓝色水滴」幽灵光标的成因）；Nav3 的 NavEntry 在离开栈时组合**真的会销毁**，
 *     `DisposableEffect` 可靠触发。
 *   · **返回行为**：旧实现手写优先级链；Nav3 的 [NavDisplay] 原生支持系统返回与预测性返回手势。
 *
 * 【栈底不变式】`HomeRoute` 恒在栈底、永不弹出。这样「弹到空」不会发生，
 *   NavDisplay 永远有内容可渲染（旧实现在栈空时要靠底座 View 兜住）。
 */
object NavHost {

    private const val TAG = "DollhouseNavHost"

    /**
     * 按宿主 Activity 分桶的页面栈。
     *
     * 【为什么要分桶】本工程有两个会挂页的宿主：[MainActivity]（首页 + 全部覆盖页）与
     *   [com.dollhouse.app.ChatActivity]（桌面图标 / 通知直接进聊天，再点「Token 统计」）。
     *   共用一条栈会互相弹错对象。分桶键用 `System.identityHashCode`：重建后是新实例、新桶，
     *   天然不会串（与旧 [PageStack] 同口径）。
     */
    private val STACKS = LinkedHashMap<Int, SnapshotStateList<NavKey>>()

    /**
     * 入栈时登记的页面构造器：key → 构造 lambda。
     *
     * 【为什么要按宿主分开存】同一个 key 在两个宿主里可能指向不同实例
     *   （理论上不会，但分桶能避免「ChatActivity 的 Token 页」被 MainActivity 复用）。
     *   【为什么不用 WeakReference】lambda 很小（只捕获 dollId 等短字符串），
     *   且栈本身就在 STACKS 里持有强引用；额外包一层 Weak 只会引入不确定性。
     *   宿主销毁时随 STACKS 一起清掉（见 [reset]）。
     */
    private val BUILDERS = LinkedHashMap<Int, HashMap<String, () -> View?>>()

    /**
     * 原地重建的版本号（栈顶 key 不变、只换内容时 +1）。
     *
     * 【为什么需要】见类头注释「原地重建」一节。把它作为 `NavEntry` 的 contentKey 传给 Nav3，
     *   contentKey 变化会让 NavDisplay 重建该 entry 的组合 —— 这正是旧 [PageStack.replaceTop] 的语义。
     *   【为什么用可变状态】它必须参与 Compose 的重组订阅：值一变，`NavDisplay` 的调用点要重跑，
     *   否则 contentKey 变了但没人重新读它。
     */
    private val REVISIONS = LinkedHashMap<Int, androidx.compose.runtime.MutableState<Int>>()

    /** 分桶上限（防极端情况下累积引用；宿主销毁时会显式回收）。 */
    private const val MAX_HOSTS = 3

    /* ============================ 安装 ============================ */

    /**
     * 给宿主 Activity 装上导航渲染层（幂等）。
     *
     * 【渲染位置】一个铺满 `android.R.id.content` 的 ComposeView，盖在底座（三 Tab + 底栏 + 抽屉）之上。
     *   【为什么底座不搬进 NavDisplay】底座是「三个常驻 Tab + 一条常驻底栏 + 一个常驻抽屉」，
     *   它们靠 `visibility` 互切、且底栏要跨 Tab 保持同一实例（见 `HomeUi.apply` 的注释）。
     *   把底座塞进 NavDisplay 的一层会让「切 Tab」变成「换目的地」，破坏底栏常驻语义。
     *   覆盖层才是真正的「页面栈」，也正是 Nav3 要接管的部分。
     *   【好处】栈空时 NavDisplay 渲染 0 个节点，对底座的命中测试零干扰。
     *
     * 【为什么参数是 Activity 而不是 MainActivity】[com.dollhouse.app.ChatActivity] 也要挂
     *   覆盖页（Token 统计页等）。旧实现按「找到 Activity」分桶，不区分具体类型；
     *   这里保持同口径，只要求宿主能提供 `android.R.id.content`。
     */
    fun install(act: Activity?) {
        if (act == null) {
            return
        }
        try {
            val content = act.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
            // 幂等：已装过就不再挂第二层。
            if (content.findViewWithTag<View>(TAG_NAV_VIEW) != null) {
                return
            }
            ComposeHost.installForActivity(act)
            val stack = stackOf(act)
            val builders = buildersOf(act)
            val revision = revisionOf(act)
            // 【r21·框架级交互闸门】覆盖页这层本身也是一个 [LayerShield]。
            //   它在 [LayerGate] 的判定里代表「NAV」态：没有覆盖页时整层屏蔽
            //   （于是它不再需要 isClickable/焦点标志，也不会和抽屉抢事件）；
            //   有覆盖页时整层放行，层内再由每个 entry 的闸门区分栈顶 / 非栈顶。
            //   【为什么外壳在组合之外创建】它要在 Composition 之前就存在并挂到 content 上，
            //   组合里的 SideEffect 只做登记，不负责创建。
            val navShield = com.dollhouse.app.ui.compose.LayerShield(act)
            val cv = ComposeHost.createView(act) {
                // 【订阅】读 isNotEmpty() 让本 Composable 订阅列表变化，从而自动重组。
                //   · revision.value 同样被读到：原地重建时它 +1，触发这里重跑并取到新的 contentKey。
                val rev = revision.value
                // 【r20·触摸栅栏】覆盖页必须**完全接管**触摸，不能漏到底下的抽屉。
                // 【问题】本 ComposeView 末尾设了 `isClickable=false`（为的是「栈空时不吃底座
                //   触摸」）。栈非空时若用户点到的位置恰好没有任何 Compose 可点节点（页面空白处），
                //   AndroidComposeView 的 dispatchTouchEvent 会返回 false，事件便继续落到它**下面
                //   的抽屉上** —— 表现为「隔着覆盖页点到抽屉，抽屉莫名关了」。
                // 【解法】栈非空期间把这个 View 临时置为 clickable：ViewGroup 自己在
                //   onTouchEvent 兜住落空的手势并返回 true。页面里的可点节点仍然在自己那一层
                //   先命中，行为零变化；栈空时立刻改回 false，底座恢复可点。
                SideEffect {
                    // 【框架级】覆盖页这层的开关交给 [LayerGate] 统一判定，不再各自改 isClickable。
                    //   这里只登记「本宿主有这一层」，之后任何状态变化由 gate 重算。
                    com.dollhouse.app.ui.compose.LayerGate.layersOf(act).nav = navShield
                    com.dollhouse.app.ui.compose.LayerGate.refresh(act)
                }
                // 【为什么是 size > 1 而不是 isNotEmpty】栈底恒有 [HomeRoute] 垫底（见 [push] 的
                //   栈底不变式）。若此处只判非空，则「没有任何覆盖层」时也会渲染 NavDisplay ——
                //   而 `SinglePaneSceneStrategy` 会把栈底那层（HomeRoute）当栈顶渲染出来。
                //   HomeRoute 在 `PageRegistry` 里没有对应页面（它代表底座），
                //   于是会得到一个铺满屏幕的空 View 盖住三个 Tab 与底栏 —— 界面直接失灵。
                //   故：**只有真正存在覆盖层时才渲染**。栈里只剩底座时这里什么都不画，
                //   ComposeView 不参与命中测试，底座零干扰。
                if (stack.size > 1) {
                    // 【必须提供 NavigationEventDispatcherOwner】`NavDisplay` 内部用
                    //   `NavigationBackHandler` 接管系统返回 / 预测性返回手势，而它读
                    //   `LocalNavigationEventDispatcherOwner`，**取不到就直接抛异常**
                    //   （已 javap 核实会抛 "No NavigationEventDispatcher was provided via
                    //   LocalNavigationEventDispatcherOwner"）。
                    //   本工程的 Activity 继承原生 `android.app.Activity`，Compose 那三个 owner
                    //   都是 [ComposeHost] 手工补的，这个第四个 owner 系统同样不会自动装，
//   故必须显式提供 —— 用 Compose 官方给的 `rememberNavigationEventDispatcherOwner()`。
                    //   【为什么不用自己实现接口】`NavigationEventDispatcherOwner` 在 Kotlin 侧
                    //   不可见（只有 JVM 符号，已实测编译报 "overrides nothing"）；
                    //   官方这个 helper 返回的正是可直接 provides 的实例。
                    //   【为什么不用 ViewTree】`ViewTreeNavigationEventDispatcherOwner.set`
                    //   同样在 Kotlin 侧不可见。CompositionLocal 本就是 Compose 的标准提供方式，
                    //   作用域也更精确：只有导航子树需要它。
                    val dispatcherOwner =
                        androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner(
                            // 【必须显式传 null】`parent` 的默认值是「读 `LocalNavigationEventDispatcherOwner`」，
                            //   而本工程的原生 Activity 不会提供它 —— 读不到时该 API **直接抛异常**：
                            //   `IllegalStateException: No NavigationEventDispatcherOwner provided in
                            //   LocalNavigationEventDispatcherOwner. If you intended to create a root
                            //   dispatcher, explicitly pass null as the parent.`
                            //   （已在真机复现并抓到该堆栈。）
                            //   传 null 即按官方指引创建 root dispatcher。
                            //   【副作用（是好事）】这个 root dispatcher 没有接任何输入源，
                            //   所以系统返回事件**不会**被 NavDisplay 抢走，仍然经
                            //   `Activity.onBackPressed` 走工程原有的优先级链
                            //   （供应商内部路由 → 页面栈 → Tab 返回），语义零变化。
                            parent = null
                        )
                    CompositionLocalProvider(
                        androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
                            provides dispatcherOwner
                    ) {
                        NavDisplay(
                        backStack = stack,
                        // 【返回键必须交回宿主的统一优先级链】
                        //   `NavDisplay` 内部会注册 `NavigationBackHandler` 抢占系统返回
                        //   （Android 13+ 走 `OnBackInvokedCallback`，优先级**高于**
                        //   `Activity.onBackPressed`）。若这里直接写 `{ pop(act) }`，
                        //   宿主 `MainActivity.onBackPressed` 里那条优先级链就被整条绕过：
                        //   供应商模块（`ProviderNav.handleBack`，一层壳 + 内部四级路由）
                        //   会**直接关掉整层**而不是先回退一格内部路由 —— 功能退化。
                        // 【返回键的实际路径】因为没有把 dispatcher 接到任何输入源
//   （`NavigationBackHandler` 注册的 handler 收不到系统返回事件），
//   Nav3 自带的返回拦截实际处于**不生效**状态 —— 这正是想要的结果：
//   系统返回仍经 `Activity.onBackPressed` 走工程原有的优先级链。
//   【所以这个 `onBack` 几乎不会被调用】保留它是为了：
//   ① 满足 Nav3 的 API 必需参数；
//   ② 万一将来把 dispatcher 接到输入源，行为仍是「走宿主统一入口」而非「直接 pop」，
//   不会绕过 `ProviderNav.handleInternalBack`。
                        onBack = { runCatching { act.onBackPressed() } },
                        modifier = Modifier.fillMaxSize(),
                        // 【丝滑转场】Nav3 默认转场是「淡入淡出」（`fadeIn togetherWith fadeOut`，
                        //   300ms 线性）。在本工程里它有两个问题：
                        //   ① 观感生硬：覆盖页是整屏不透明页，纯淡变看起来像「闪一下」，
                        //      没有旧实现那种「从右侧推进来」的方向感（用户报的「卡顿」）；
                        //   ② 淡变期间两层同时绘制，而每层都是 Compose→AndroidView 包裹的
                        //      重型 View 树，叠绘开销让掉帧更明显。
                        //   这里改成**推入式滑动**：入栈整层自右向左推进，出栈整层自左向右退回。
                        //   两层同速同向移动、始终拼满整屏，所以不会露出下层底座的缝。
                        //   【为什么滑动比淡变更顺】滑动的位移是渲染层合成（GPU 一次绘制即可），
                        //   不涉及两层 alpha 混合重绘，帧时间更稳。
                        //
                        //   【曲线与时长直接复用 UiKit 的既有动效词汇】单一真源，避免两套口径：
                        //   · 入场 = `D_PAGE`(300ms) + `LinearOutSlowInEasing`
                        //     —— 即 cubic-bezier(0,0,0.2,1)，与 `UiKit.EASE_DECEL` 逐位相同；
                        //   · 出场 = `D_LAYER`(260ms) + `FastOutLinearInEasing`
                        //     —— 即 cubic-bezier(0.4,0,1,1)，与 `UiKit.EASE_ACCEL` 逐位相同。
                        //   「离开比进入更快」正是旧实现刻意的视觉习惯（见 UiKit 注释）。
                        transitionSpec = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                tween(
                                    durationMillis = UiKit.D_PAGE,
                                    easing = LinearOutSlowInEasing
                                )
                            ) togetherWith slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                tween(
                                    durationMillis = UiKit.D_PAGE,
                                    easing = LinearOutSlowInEasing
                                )
                            )
                        },
                        // 【返回/出栈】方向镜像：整层自左向右退回，离开更快（D_LAYER）。
                        popTransitionSpec = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                tween(
                                    durationMillis = UiKit.D_LAYER,
                                    easing = FastOutLinearInEasing
                                )
                            ) togetherWith slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                tween(
                                    durationMillis = UiKit.D_LAYER,
                                    easing = FastOutLinearInEasing
                                )
                            )
                        },
                        // 【预测性返回】手势拖动过程中跟手预览退场。
                        //   【为什么本工程基本用不到】宿主是原生 `Activity`，未在 Manifest 里开启
                        //   `android:enableOnBackInvokedCallback`，系统不会下发预测性返回手势；
                        //   这里给出与 pop 一致的规格，是为「日后开启」预留一致观感，零额外成本。
                        predictivePopTransitionSpec = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                tween(
                                    durationMillis = UiKit.D_LAYER,
                                    easing = FastOutLinearInEasing
                                )
                            ) togetherWith slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                tween(
                                    durationMillis = UiKit.D_LAYER,
                                    easing = FastOutLinearInEasing
                                )
                            )
                        },
                        // 【不做尺寸动画】本工程的场景永远是整屏页，尺寸恒定；
                        //   关掉 `sizeTransform` 可以省去每帧的子项测量与布局动画开销 ——
                        //   这是「切页更顺」的另一半原因（帧时间少一段 layout 计算）。
                        sizeTransform = null,
                        entryProvider = entryProvider<NavKey> {
                            // 【逐层登记】Nav3 要求每个 key 在 entryProvider 里都有对应条目。
                            //   【为什么显式写 <NavKey>】`entryProvider` 的泛型 T 由内部 `entry<K>()`
                            //   反推；循环里 K 是宽类型 NavKey，编译器推不出 T 会报
                            //   "Cannot infer type for this parameter"。显式给出即可
                            //   （已用独立探针工程实测确认此写法编译通过）。
                            for (key in stack) {
                                // 【contentKey】把「本层的原地重建版本」编进去：
                                //   仅栈顶那层会因 replaceTop 而版本变化，其余层的 contentKey 稳定，
                                //   不会被无谓重建（页面状态得以保留）。
                                //   【为什么用位置参数】`entry` 是 Nav3 的扩展函数，参数名不是稳定契约
                                //   （官方只承诺签名与默认值）；位置传参更抗升级。
                                entry<NavKey>(key, contentKeyOf(act, key, rev)) { k ->
                                    RouteContent(act, k, builders, rev)
                                }
                            }
                        }
                        )
                    }
                }
            }
            cv.tag = TAG_NAV_VIEW
            // 【r21】把 ComposeView 放进 NAV 层的外壳里，再把外壳挂到 content。
            //   外壳就是 [LayerShield]：没有覆盖页时它整层屏蔽（dispatchTouchEvent 直接
            //   返回 false，不下发给 ComposeView），底座与抽屉照常工作。
            navShield.addView(
                cv,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            navShield.isClickable = false
            navShield.isFocusable = false
            navShield.blocking = true
            content.addView(
                navShield,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // 装配完成立刻按当前状态判定一次（冷启动时通常是 BASE）。
            com.dollhouse.app.ui.compose.LayerGate.refresh(act)
        } catch (ignored: Throwable) {
            Logs.w(TAG, "install failed", ignored)
        }
    }

    private const val TAG_NAV_VIEW = "dh_nav_host_view"

    /**
     * 计算某层用于 Nav3 的 `contentKey`。
     *
     * 【规则】只有**栈顶**那层带上「原地重建版本」，其余层恒用其 key 自身。
     *   【为什么只给栈顶】`replaceTop` 的语义就是「换栈顶」，被换的只可能是栈顶；
     *   给所有层都带版本号会让每次 replaceTop 都波及整条栈的 contentKey，
     *   导致底下各层被无谓重建（页面状态、滚动位置全丢）—— 那正是要避免的「功能退化」。
     */
    private fun contentKeyOf(act: Activity, key: NavKey, rev: Int): Any {
        val stack = STACKS[System.identityHashCode(act)]
        val isTop = stack != null && stack.isNotEmpty() && stack[stack.size - 1] == key
        return if (isTop) key.toString() + "#" + rev else key.toString()
    }

    /**
     * 渲染某个路由对应的页面。
     *
     * 【构造优先级】入栈时登记的 lambda → [PageRegistry] 回落。
     *   前者保留了闭包参数（如 `MemPage` 的 dollId），后者从 key 字符串解析参数。
     *   详见本类头注释「构造器从哪来」。
     *
     * 【桥接方式】既有页面构造器都返回 `View`（`DollEditScreen.buildPage(act, id)`），
     *   用 [AndroidView] 包进 Compose 树。`factory` 只在进入组合时调用一次，
     *   该 entry 在栈中期间不会被重建 —— 这正是「层被盖住时状态保留」的实现。
     */
    @Composable
    private fun RouteContent(act: Activity, navKey: NavKey,
                             builders: HashMap<String, () -> View?>, rev: Int) {
        val legacy = NavKeyCodec.toLegacy(navKey)
        // 【离开清理】Nav3 的 entry 离开栈时组合真的会销毁 ——
        //   这是旧实现做不到的（ComposeView 的组合挂在 decorView 上，removeView 不触发销毁，
        //   所以旧代码才把清理挂在 PageStack.onLeave 上）。
        //   这里仍然转调 PageStack.fireLeaveFor，保证既有登记（如预览悬浮窗）继续生效。
        //
        // 【为什么放在 key() 之外】原地重建（replaceTop）只换内容、**不换层**，
        //   旧实现 `swapInPlace` 也不会触发 leave 清理（层还在栈里，语义上没离开）。
        //   若把它放进下面那个 `key()` 里，每次原地重建都会误触发一次 leave 清理。
        DisposableEffect(navKey) {
            onDispose {
                PageStack.fireLeaveFor(navKey)
                // 该层已离开，其登记构造器也随之作废（防闭包持有旧 Activity / 旧参数）。
                legacy?.let { builders.remove(it) }
            }
        }
        // 【原地重建】用 Compose 的 `key()` 显式限定重建范围：
        //   · 栈顶层的 rev 变化 → key 变化 → 只有它这一块被销毁重建（旧页面换成新页面）；
        //   · 非栈顶层的 rev 恒传 0 → key 稳定 → 不会被无谓重建，滚动位置/输入内容得以保留。
        //   【为什么不能只靠 NavEntry.contentKey】contentKey 是 Nav3 的内部实现细节
        //   （经 AnimatedContent 的 targetState 参与比较），跨版本可能改变比较方式；
        //   用 Compose 自己的 `key()` 是语言级保证，语义明确、行为可预测。
        val stack = STACKS[System.identityHashCode(act)]
        val isTop = stack != null && stack.isNotEmpty() && stack[stack.size - 1] == navKey
        androidx.compose.runtime.key(if (isTop) rev else 0) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // ① 优先用入栈时登记的构造器（保留闭包参数）。
                    val registered = legacy?.let { builders[it] }
                    var v: View? = null
                    if (registered != null) {
                        v = try {
                            registered.invoke()
                        } catch (ignored: Throwable) {
                            Logs.w(TAG, "registered build failed: " + legacy, ignored)
                            null
                        }
                    }
                    // ② 回落：按 key 从 PageRegistry 重建（进程被杀后恢复走这条路）。
                    //   【为什么只对 MainActivity 生效】`PageRegistry.build` 的签名收 MainActivity
                    //   （页面构造器需要它的 `notifyPetService` 等宿主能力）；
                    //   ChatActivity 是独立窗口宿主、只挂 Token 统计页，且它**不做路由存档**
                    //   （见 ChatActivity.onDestroy 注释），故不需要恢复路径。
                    if (v == null && act is com.dollhouse.app.MainActivity) {
                        v = try {
                            PageRegistry.build(act, legacy)
                        } catch (ignored: Throwable) {
                            Logs.w(TAG, "registry build failed: " + legacy, ignored)
                            null
                        }
                    }
                    // 【兜底】构造失败时给一个空 View，避免 AndroidView 抛「factory returned null」。
                    //   这比整层崩掉好：用户看到的是空白页，返回键仍可用。
                    val page = v ?: View(ctx)
                    // 覆盖页自铺全局背景（与旧 PageStack.push 同口径），避免叠影。
                    if (v != null) {
                        GlobalBackground.installPage(page)
                    }
                    // 【框架级交互闸门】整层包进 [LayerShield]，页面 View 作为它的唯一子项。
                    //   【为什么必须包一层容器】要在**层根**的 dispatchTouchEvent 上做屏蔽；
                    //   直接给页面 View 挂 OnTouchListener 是无效做法（见下方 update 的说明）。
                    //   【为什么用 FrameLayout 而不是重新 addView 到别处】零迁移成本：
                    //   页面 View 的父子关系、tag、LayoutParams 全部保持原样，只是多了一个透明外壳。
                    val shield = com.dollhouse.app.ui.compose.LayerShield(ctx)
                    shield.addView(
                        page,
                        android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    shield
                },
                // 【栈顶独占交互·框架级修复】
                //
                // ## 穿透为什么发生（两道，缺一不可）
                // ① Nav3 的 `NavDisplay` 用 `AnimatedContent` 做转场：转场期间**两个 entry
                //    同时在组合树里**（新页滑入 + 旧页滑出）。旧那层仍在树上、仍参与命中测试。
                // ② 更根本的一层：`ViewGroup.dispatchTouchEvent` 是**先问子 View、再问自己**。
                //    所以「给页根改 isClickable / 挂 setOnTouchListener」在原理上就挡不住
                //    页内按钮 —— 子按钮先被问到并消费掉事件。上一版正是在这里失效。
                //
                // ## 为什么不靠 zIndex
                // 提高 zIndex 只改**绘制**顺序，不改**命中**顺序。抬 z 只能让画面在上面，
                // 挡不住下层被点到。
                //
                // ## 解法：整层用 [LayerShield] 包起来
                // 非栈顶时 `blocking = true` —— 它的 `dispatchTouchEvent` **不下发给子树**
                // 并返回 false。于是这一层在分发链上等于不存在，页内按钮 / 输入 / 滚动
                // 一并暂停（正是需求文档要求的「整个交互区域及其子组件均符合暂停规则」）。
                // 栈顶页完全不受影响：它内部的滚动 / 输入 / 长按 / 拖动一个都没改。
                //
                // `AndroidView` 的 update 每帧都跑，`isTop` 在新页**入栈的同一帧**就变 false
                // —— 等价于「新页面开始打开时立即禁掉旧页」，不等动画结束。
                // 【刻意不动 isEnabled】它是级联的，会让整棵子树进 disabled 态且不易恢复。
                update = { shield ->
                    shield.blocking = !isTop
                }
            )
        }
    }

    /* ============================ 栈访问 ============================ */

    /** 取（必要时创建）某宿主的栈。 */
    private fun stackOf(act: Activity): SnapshotStateList<NavKey> {
        val id = System.identityHashCode(act)
        var s = STACKS[id]
        if (s == null) {
            s = mutableStateListOf<NavKey>()
            STACKS[id] = s
            trim()
        }
        return s
    }

    /** 取（必要时创建）某宿主的构造器表。 */
    private fun buildersOf(act: Activity): HashMap<String, () -> View?> {
        val id = System.identityHashCode(act)
        var m = BUILDERS[id]
        if (m == null) {
            m = HashMap()
            BUILDERS[id] = m
        }
        return m
    }

    /** 取（必要时创建）某宿主的原地重建版本号。 */
    private fun revisionOf(act: Activity): androidx.compose.runtime.MutableState<Int> {
        val id = System.identityHashCode(act)
        var r = REVISIONS[id]
        if (r == null) {
            r = mutableStateOf(0)
            REVISIONS[id] = r
        }
        return r
    }

    /** 只读反查（不创建）：拿不到 Activity 或该宿主从未入过栈时返回 null。 */
    private fun stackOrNull(ctx: Context?): SnapshotStateList<NavKey>? {
        val act = findActivity(ctx) ?: return null
        return STACKS[System.identityHashCode(act)]
    }

    /**
     * 分桶超限时清理。
     *
     * 【B10 修复】原来是无条件 `iterator().next()` —— 也就是**按插入顺序**
     *   淘汰最早的那个宿主，完全不管它还活不活着：
     *   ```
     *   ① 用户在 MainActivity 里点进「动作详情」；
     *   ② 再从桌面图标进 ChatActivity（第二个桶）；
     *   ③ 某个悬浮入口又开了一次新 Activity（第三个桶）；
     *   ④ 此时再进来第四个宿主 → 触发 trim → **MainActivity 的栈被删掉**；
     *   ⑤ 用户按返回 → 本该回到「动作详情」，结果直接回到首页底座。
     *   ```
     *   而且 BUILDERS 一并删掉后，连页面内容都重建不出来。
     *
     * 【修复口径】优先淘汰「已经没有任何活引用」的桶：
     *   · 栈为空（用户已经退到底座）的桶先走；
     *   · 其次挑「最久没被访问」的桶（按插入顺序最靠前，作为兜底而非首选）。
     *   真正的强引用靠 `MainActivity.onDestroy → NavHost.reset(this)` 释放，
     *   trim 只是防极端累积的最后一道闸，不该反过来毁掉活跃宿主。
     */
    private fun trim() {
        while (STACKS.size > MAX_HOSTS) {
            // ① 先找空栈的桶（最安全：里面本来就没有页面可丢）。
            var victim: Int? = null
            for ((host, stack) in STACKS) {
                if (stack.isEmpty()) {
                    victim = host
                    break
                }
            }
            // ② 没有空栈桶时才按插入顺序兜底。
            if (victim == null) {
                val it = STACKS.keys.iterator()
                if (!it.hasNext()) {
                    return
                }
                victim = it.next()
            }
            STACKS.remove(victim)
            BUILDERS.remove(victim)
            REVISIONS.remove(victim)
        }
    }

    /* ============================ 命令式 API（供 PageStack 转发） ============================ */

    /**
     * 压入一层（字符串 key 版本，供 [PageStack] 兼容层调用）。
     *
     * 【与旧实现的语义对齐】旧 [PageStack.push] 的行为是：
     *   · 同 key 已在栈里 → 把它**上面的层**全部摘掉（它们本来就被遮住），然后**原地重建**该层；
     *   · 不在栈里 → 压到栈顶。
     *   这里保留「回到已有层」语义（去掉它上面的层）；「原地重建」由 [BUILDERS] 的覆盖写入
     *   + 版本号 +1 达成（见类头「原地重建」）。
     */
    fun push(ctx: Context?, legacy: String?, build: (() -> View?)?): Boolean {
        if (ctx == null || legacy.isNullOrEmpty() || build == null) {
            return false
        }
        val act = findActivity(ctx) ?: return false
        install(act)
        // 【诊断】key 认不出时必须留痕。
        //   【为什么必须打日志】认不出会返回 null，`push` 随即失败 —— 调用方（页面自己的
        //   `open()`）几乎都不检查返回值，于是表现成「点了按钮毫无反应」：
        //   不崩、不报错、什么都没发生。这是本次 `doll:anim:` 分隔符写错时最难查的一点
        //   （代码没问题、只是 key 转换静默失败）。留一条 W 级日志，下次一眼可见。
        val key = NavKeyCodec.toKey(legacy)
        if (key == null) {
            Logs.w(TAG, "push failed: unrecognized key = " + legacy)
            return false
        }
        val stack = stackOf(act)
        // 【栈底不变式】旧实现的「栈底」是底座 View，`stack` 列表本身可以为空；
        //   Nav3 的 NavDisplay 需要栈里始终有内容可渲染，故首次入栈时补上 HomeRoute 垫底。
        if (stack.isEmpty()) {
            stack.add(HomeRoute)
        }
        // 【登记构造器】必须在入栈前完成：NavDisplay 可能在下一帧就取用。
        buildersOf(act)[legacy] = build
        // 【同 key 视为回到该层】移除它之上的所有层（与旧 PageStack.push 同语义）。
        val exist = stack.indexOfFirst { it == key }
        if (exist >= 0) {
            while (stack.size > exist + 1) {
                val gone = stack.removeAt(stack.size - 1)
                val goneLegacy = NavKeyCodec.toLegacy(gone)
                PageStack.fireLeaveFor(gone)
                if (goneLegacy != null) {
                    buildersOf(act).remove(goneLegacy)
                }
            }
            // 【原地重建】旧实现「回到已有层」时会把该层内容重建一次（`swapInPlace`）。
            //   这里靠版本号 +1 触发 Nav3 重建该 entry 的组合，语义等价。
revisionOf(act).value = revisionOf(act).value + 1
            // 【同一帧生效】栈变了就立刻重算交互所有权：底座此刻必须已经被屏蔽，
            //   不等进场动画播完（需求文档：新页面开始打开时立即阻止底层响应）。
            com.dollhouse.app.ui.compose.LayerGate.refresh(act)
            return true
        }
        stack.add(key)
        // 【同一帧生效】见上。
        com.dollhouse.app.ui.compose.LayerGate.refresh(act)
        return true
    }
    /** 弹出栈顶一层。栈底 [HomeRoute] 永不弹出。 */
    fun pop(ctx: Context?, onDone: (() -> Unit)?): Boolean {
        val act = findActivity(ctx) ?: return false
        val stack = STACKS[System.identityHashCode(act)] ?: return false
        if (stack.size <= 1) {
            return false
        }
        val gone = stack.removeAt(stack.size - 1)
        // 【构造器随层回收】离开栈的层，其登记构造器一并作废。
        //   【为什么必须在这里清】`DisposableEffect` 的 onDispose 是权威路径，但它发生在
        //   组合提交之后；若此处不先清，同一 key 的旧 lambda 会短暂留在表里，
        //   下一次同 key 入栈前若发生重组，可能取到旧闭包（旧 dollId）。
        NavKeyCodec.toLegacy(gone)?.let { buildersOf(act).remove(it) }
        onDone?.invoke()
        // 【同一帧生效】返回后上一层要立刻拿回交互权，否则会「返回后点不动」。
        com.dollhouse.app.ui.compose.LayerGate.refresh(act)
        return true
    }

    fun pop(ctx: Context?): Boolean = pop(ctx, null)

    /**
     * 【r21】返回到指定页面（需求文档要求的原语之一）。
     *
     * 【语义】把 `key` 之上的所有层弹掉，`key` 本身留在栈顶。
     *   · `key` 不在栈里（或只命中栈底垫底的底座）→ 不动栈，返回 false；
     *   · `key` 已在栈顶 → 幂等返回 true。
     *
     * 【为什么需要它】改造前只有 push / pop / replaceTop，「回到某一层」只能靠连弹多次，
     *   调用方得自己算次数 —— 一旦中间某层被别处弹掉，次数就对不上，于是要么弹过头、
     *   要么停在错的地方。现在由栈自己算。
     */
    fun popTo(ctx: Context?, legacy: String?): Boolean {
        val act = findActivity(ctx) ?: return false
        val target = NavKeyCodec.toKey(legacy) ?: return false
        val stack = STACKS[System.identityHashCode(act)] ?: return false
        val idx = stack.indexOfFirst { it == target }
        // 找不到、或命中的是栈底垫底的底座 → 无效目标（底座不是「页面」）。
        if (idx <= 0) {
            return false
        }
        if (idx == stack.size - 1) {
            return true
        }
        // 自顶向下弹到目标之上，逐个清理离开登记与构造器。
        while (stack.size > idx + 1) {
            val gone = stack.removeAt(stack.size - 1)
            val goneLegacy = NavKeyCodec.toLegacy(gone)
            PageStack.fireLeaveFor(gone)
            if (goneLegacy != null) {
                buildersOf(act).remove(goneLegacy)
            }
        }
        // 【同一帧生效】一次弹掉多层后，交互权立刻回到目标那一层。
        com.dollhouse.app.ui.compose.LayerGate.refresh(act)
        return true
    }

    /**
     * 原地重建栈顶（key 不变、只换内容）—— 复刻旧 [PageStack.replaceTop] 语义。
     *
     * 【供应商模块依赖它】见类头「原地重建」一节。
     * 【实现】① 把新构造 lambda 登记到**当前栈顶 key** 上（覆盖旧闭包，参数得以更新）；
     *   ② 版本号 +1，`NavDisplay` 因栈顶 entry 的 contentKey 变化而重建该层内容。
     *   栈列表本身不动（深度与 key 都不变）。
     *
     * 【与旧实现的差别】旧 `swapTop` 立刻 `build.invoke()` 建新 View 并换进树；
     *   这里把「重建」交给 Compose 的重组 —— 时机在下一帧，但用户观感一致，
     *   且换来「不手写 View 交换」的可靠性。
     */
    fun reviseTop(ctx: Context?, build: (() -> View?)?): Boolean {
        if (build == null) {
            return false
        }
        val act = findActivity(ctx) ?: return false
        val stack = STACKS[System.identityHashCode(act)] ?: return false
        // 【栈底不变式】只有底座时没有「栈顶覆盖层」可换，与旧实现（空栈返回 false）一致。
        if (stack.size <= 1) {
            return false
        }
        val topLegacy = NavKeyCodec.toLegacy(stack[stack.size - 1]) ?: return false
        buildersOf(act)[topLegacy] = build
        revisionOf(act).value = revisionOf(act).value + 1
        // 【同一帧生效】原地重建后栈深不变，但栈顶那一层的内容已换，
        //   重算一次保证「新内容立即可交互、旧内容立即失效」。
        com.dollhouse.app.ui.compose.LayerGate.refresh(act)
        return true
    }

    /**
     * 替换栈顶为指定 key（改变 key，不改变深度）。
     *
     * 【用途】需要「栈顶 key 换成另一个」的场景（如 `ProviderNav.resetForTab` 之外的工具调用）。
     */
    fun replaceTop(ctx: Context?, legacy: String?): Boolean {
        val act = findActivity(ctx) ?: return false
        val key = NavKeyCodec.toKey(legacy) ?: return false
        val stack = STACKS[System.identityHashCode(act)] ?: return false
        if (stack.isEmpty()) {
            return false
        }
        stack[stack.size - 1] = key
        // 【同一帧生效】栈顶换人，交互权跟着换。
        com.dollhouse.app.ui.compose.LayerGate.refresh(act)
        return true
    }

    fun depth(ctx: Context?): Int {
        val s = stackOrNull(ctx) ?: return 0
        return if (s.isEmpty()) 0 else s.size - 1
    }

    fun isEmpty(ctx: Context?): Boolean = depth(ctx) == 0

    /**
     * 栈顶覆盖层的 key；**只有底座（栈深 0）时返回 null**。
     *
     * 【为什么减掉底座】旧实现的 `stack` 列表里只有覆盖层，栈空即 `null`。
     *   本实现为了满足 Nav3「栈里始终有内容可渲染」的要求，恒在栈底放一个 [HomeRoute]，
     *   因此对外口径必须把这一层扣掉 —— 否则 `PageStack.isTop(ctx, "feiyu_home_page")`
     *   会在没有任何覆盖层时意外为真，让依赖它的返回逻辑走错分支。
     */
    fun topKey(ctx: Context?): String? {
        val s = stackOrNull(ctx) ?: return null
        if (s.size <= 1) {
            return null
        }
        return NavKeyCodec.toLegacy(s[s.size - 1])
    }

    /** 覆盖层里是否含该 key（同样不含底座 —— 与旧 [PageStack.contains] 口径一致）。 */
    fun contains(ctx: Context?, legacy: String?): Boolean {
        val key = NavKeyCodec.toKey(legacy) ?: return false
        val s = stackOrNull(ctx) ?: return false
        for (i in 1 until s.size) {
            if (s[i] == key) {
                return true
            }
        }
        return false
    }

    /** 栈内全部 key（自底向上，**不含栈底的 HomeRoute** —— 与旧 PageStack.keys 口径一致）。 */
    fun keys(ctx: Context?): ArrayList<String> {
        val out = ArrayList<String>()
        val s = stackOrNull(ctx) ?: return out
        for (i in 1 until s.size) {
            NavKeyCodec.toLegacy(s[i])?.let { out.add(it) }
        }
        return out
    }

    /** 清空某宿主的栈（重建 / 销毁时调用）。 */
    fun reset(ctx: Context?) {
        val act = findActivity(ctx) ?: return
        val id = System.identityHashCode(act)
        STACKS.remove(id)
        BUILDERS.remove(id)
        REVISIONS.remove(id)
    }

    /**
     * 按历史字符串 key 逐层重建（存档恢复）。
     *
     * 【构造器从哪来】恢复时 [BUILDERS] 里没有登记（那些闭包随进程一起没了），
     *   因此 `RouteContent` 会回落到 [PageRegistry.build] —— 它按 key 字符串解析参数。
     *   这也是为什么 `MemPage` 这类「参数在闭包里」的页面需要在 [PageRegistry] 里
     *   补上带参数的入口（本次一并修掉）。
     *
     * 【栈底】恢复出来的栈同样以 [HomeRoute] 垫底，与 [push] 保持一致的不变式。
     */
    fun restore(ctx: Context?, legacyKeys: List<String>?) {
        val act = findActivity(ctx) ?: return
        val stack = stackOf(act)
        stack.clear()
        stack.add(HomeRoute)
        if (legacyKeys != null) {
            for (k in legacyKeys) {
                NavKeyCodec.toKey(k)?.let { stack.add(it) }
            }
        }
    }

    /* ============================ 工具 ============================ */

    private fun findActivity(ctx: Context?): Activity? {
        var c: Context? = ctx
        var i = 0
        while (i < 8 && c != null) {
            if (c is Activity) {
                return c
            }
            if (c is ContextWrapper) {
                c = c.baseContext
            } else {
                return null
            }
            i++
        }
        return null
    }
}