package com.dollhouse.app.ui.provider

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】供应商详情页：内容区（按 tab 装整页）+ 悬浮胶囊底栏（配置 / 模型）。
 *
 * 【为什么用「装整页」而不是「重画内容」】配置 tab 就是原来的供应商编辑页、
 *        模型 tab 有自己的顶栏与列表，两者形态完全不同。直接把现成页塞进来，
 *        既复用零改动的样式，也不用把两套布局揉成一份条件分支。
 *
 * 【为什么底栏浮在内容之上】规格定的是「悬浮胶囊式」。壳层用 `Box` 让底栏
 *        不参与内容布局，内容照常整屏滚动，只在底部预留出它占的高度。
 *
 * 【底栏实现·r21 起统一】底栏直接用 [DhKit.NavBar]（主界面三 Tab 底栏的同一个
 *        Compose 组件）。因此切 tab 时**没有指示块滑动动画**（NavBar 的选中态是
 *        即时换底色，与主界面 / 人偶编辑页一致）。
 *
 * 【本文件已去 View 化·r22】改造前壳层是「FrameLayout 根 + FrameLayout 槽 + 浮层底栏」，
 *        三处 View 实例化。现在整个壳层只是一棵 Compose 树，**不再实例化任何 View**：
 *          · 根 → [ComposeHost.createView] 产出的 ComposeView（`Box` 布局）
 *          · 内容区 → [AndroidView] 承载既有页面 View（见下方风险说明）
 *          · 底栏 → [DhKit.NavBar]，`Alignment.BottomCenter` 对齐
 *        不透明底仍由这个 ComposeView 自己带（见 [build] 里的说明），语义不变。
 *
 * 【坑】① 内容层底部必须留出 RESERVE_DP，不然最后一行被胶囊盖住；
 *        ② 切 tab 要把「当前 tab」写回路由栈顶，否则返回再进来会跳回配置页。
 */
object ProviderDetailPage {

    /**
     * 底栏吃掉的高度：NavBar 的外边距（上 6 + 下 10）+ 项高 54 + 底部呼吸 12 ≈ 82，
     * 取 88 留 6dp 冗余，内容最后一行的可点区不会被胶囊盖住。
     */
    internal const val RESERVE_DP = 88

    /**
     * 底栏两项。
     *
     * 【为什么写在文件内而不是调用处】[DhKit.NavBar] 是数据驱动的（收 `List<NavItemSpec>`），
     *   本页只有这两项且顺序即 tab 语义（0=配置 / 1=模型），集中在这里
     *   避免「点击回调用 1 判模型、渲染用下标 0 判配置」两处各写一遍而走偏。
     */
    private val PROVIDER_TABS: List<DhKit.NavItemSpec> = listOf(
        DhKit.NavItemSpec(Icons.IC_SETTINGS, "配置"),
        DhKit.NavItemSpec(Icons.IC_LAYERS, "模型")
    )


    /**
     * 供应商模块的「不可用态」页：供应商已删除 / 尚未保存。
     *
     * 【为什么用 DhState 而不是一句 note】它和全站其它空态/错误态共用同一骨架
     *   （56dp 图标座 → section 标题 → sub 说明 → 一个动作），用户不必重新判断
     *   「这是空的、还是出错了、还是我做错了什么」。
     */
    @androidx.compose.runtime.Composable
    private fun MissingPage(
        title: String,
        sub: String,
        message: String,
        onBack: () -> Unit
    ) {
        DhKit.Page(title = title, sub = sub, onBack = onBack) {
            com.dollhouse.app.ui.kit.DhState(
                title = "这一页暂时是空的",
                body = message,
                iconRes = Icons.IC_CIRCLE_ALERT,
                actionText = "返回",
                onAction = onBack
            )
        }
    }

    internal fun build(act: Activity, providerId: String, tab: String): View {
        // 【必须有不透明底】本页是叠加在设置页之上的整屏页，根若透明，
        //   底部预留区会透出下层设置页的行（曾出现「聊天背景 / 未设置」串进来）。
        //   【为什么设在 ComposeView 上而不是 Modifier.background】原实现就是
        //   `root.setBackgroundColor(UiKit.BG)` 打在页根 View 上；这里保持同一落点，
        //   语义逐字不变（后续 `GlobalBackground.installPage` 覆盖它，顺序也一致）。
        val root = ComposeHost.createView(act) { DetailContent(act, providerId, tab) }
        root.setBackgroundColor(UiKit.BG)
        return root
    }

    /**
     * 壳层正文（Compose）。
     *
     * ## ⚠ 风险点（真机必须验证）
     * 内容区用 [AndroidView] 承载既有页面 View，并用 `key(curTab, revision)` 限定节点。
     * 切 tab 时 `key` 变化 → 旧 `AndroidView` 节点被销毁 → **它持有的那个页面 View
     * 会被从 View 树上摘下来**。这些页面 View 内部是 `ComposeView`，它们的组合之所以
     * 不会跟着销毁，**依赖 [ComposeHost.createView] 设置的
     * `ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed`**：
     * 组合只在 `ViewTreeLifecycleOwner` 进入 DESTROYED 时销毁，**单纯 detach 不销毁**。
     *
     * 也就是说：**表单内容能在切 tab 后存活，是一个「组合生命周期与 View 挂载解耦」的假设**。
     * 若该假设不成立（某天有人改了 CompositionStrategy、或页面内部用了
     * `DisposableEffect` 在 `onDispose` 里清状态），用户填到一半的表单就会在切 tab 时被清空。
     * 这一条已在报告的真机验证清单里列为**第一优先**。
     *
     * ## 为什么用 `key(tab)` 而不是单个 AndroidView 手工换子 View
     * 手工换子 View 需要在 Compose 里管理 View 父子关系（add/remove），
     * 等于把壳层又写回 View 世界。`key` 是 Compose 语言级保证，语义明确可预测
     * （先例：`NavHost.RouteContent` 用 `key(rev)` 做原地重建）。
     *
     * ## 为什么切 tab 要复用实例（沿用原实现的核心结论）
     * 切 tab 若每次都新建页面，被换下的那页状态全丢 —— 用户填到一半的表单切一下「模型」
     * 再切回来就清空了。所以两个 tab 各留一份：配置页全程复用（它才是表单），
     * 模型页只在 id 没变时复用（新建态保存拿到真实 id 后必须重建，否则还是占位页）。
     */
    @Composable
    private fun DetailContent(act: Activity, providerId: String, tab: String) {
        val pv = ProviderStore.findProvider(act, providerId)
        // 【新建态】providerId 传空 = 「加号」进来的新建供应商：没有 id 可查库，
        //   但要走**同一个壳**（底部「配置｜模型」胶囊栏），才能和点卡片进来的长相一致。
        val isNew = providerId.isEmpty()
        if (!isNew && pv == null) {
            // 【M8·兜底页改 Compose】与全站其它「缺失 / 空 / 错误」页共用同一套骨架
            //   （图标座 → section 标题 → sub 说明），不再是一个页面一种空态长相。
            //   【去 View 化后更直接】原来还要套一个 FrameLayout 才能塞进 View 壳，
            //   现在直接就是组合里的一棵树。
            MissingPage(
                title = "供应商",
                sub = "已不存在",
                message = "这个供应商已经被删除了。",
                onBack = { ProviderNav.back(act) }
            )
            return
        }

        // 安全区：手势导航下为 0，三键导航下等于导航栏高度。底栏与内容预留都要算进去。
        val safe = UiKit.navBarPad(act)
        val safeDp = (safe / act.resources.displayMetrics.density).toInt()
        // 【让位 dp】内容页底部要留出胶囊高度，否则最后一行被盖住。
        //   壳层无法从 View 树里找到内容页的滚动容器（`verticalScroll` 不是
        //   `android.widget.ScrollView`），故这个值必须由壳层算好、交给内容页自己垫底。
        val reserveDp = RESERVE_DP + safeDp
        // 【滚动上报已停用】r21 统一底栏后，`DhKit.NavBar` 不做「滚动淡出 / 模糊联动」，
        //   故不再需要把内容页的滚动位置回传给壳层 —— 直接传 null。
        val onScroll: ((Int) -> Unit)? = null

        // 【当前 tab】初始值来自入参；之后由底栏点击就地改写（与原来的 slot 换页等价）。
        var curTab by remember {
            mutableStateOf(if (ProviderNav.TAB_MDL == tab) ProviderNav.TAB_MDL else ProviderNav.TAB_CFG)
        }
        // 【缓存页】放在 remember 里，让它在整棵组合存活期间（跨 tab 切换、跨重组）保持同一实例。
        val cache = remember { PageCache() }
        // 【强制重建计数器】只用于「新建态保存成功后，模型页要按新 id 重建」这一条路径。
        //   它参与 `key(...)`，值一变就让 AndroidView 节点重建并重新取页面。
        var revision by remember { mutableIntStateOf(0) }

        /**
         * 按 tab 取内容页。[cache] 保证同一 tab 反复切换时拿到**同一个 View 实例**。
         */
        fun pageFor(id: String, t: String): View {
            if (ProviderNav.TAB_MDL == t) {
                if (cache.mdlPage == null || cache.mdlPageId != id) {
                    cache.mdlPage = content(act, id, t, reserveDp, onScroll)
                    cache.mdlPageId = id
                }
                return cache.mdlPage!!
            }
            if (cache.cfgPage == null) {
                cache.cfgPage = content(act, id, t, reserveDp, onScroll)
            }
            return cache.cfgPage!!
        }

        /**
         * 【v0.1.4·修「保存后模型页仍显示尚未保存」】
         *
         * ## 问题
         * 新建供应商时壳层记的 `providerId` 是空串。用户在配置页点保存后，
         * [ProviderEditPage] 会把**路由栈顶**换成真实 id（[ProviderNav.replaceDetailNew]），
         * 但壳层这个 `providerId` 形参不会变 —— 它是页面创建时捕获的。
         *
         * ## 修法
         * 把「壳层记的 id」与「栈顶真实 id」比一次，变了就丢弃旧缓存页、按新 id 重建。
         * 保存成功后被 [ProviderNav] 回调触发。
         */
        fun refreshContentIfIdChanged() {
            val nowId = ProviderNav.currentDetailId(providerId)
            // 两种情况必须什么都不做：
            //   ① 仍是新建态（栈顶没有 id）—— 没有新 id 可切；
            //   ② 栈顶 id 与进页时一致 —— 缓存页都有效。
            if (nowId.isEmpty() || nowId == providerId) {
                return
            }
            // id 真的换了（新建 → 已保存）。
            //   【只重建模型页，绝不重建配置页】配置页此刻正显示着用户刚填完的表单，
            //     重建成新页会把输入框内容整体清空 —— 那比「模型页显示尚未保存」更糟。
            //     它内部的 pid 已在保存回调里改成新 id（见 ProviderEditPage 的 pid/saved），
            //     后续切开关的即时写盘落在新 id 上，语义正确。
            cache.mdlPage = null
            cache.mdlPageId = null
            // 只有在用户正停在「模型」页时才需要立刻换内容。
            //   停在配置页时什么都不用做：等他点「模型」，pageFor 会按新 id 现建。
            if (ProviderNav.TAB_MDL == ProviderNav.currentDetailTab()) {
                revision++
            }
        }

        // 【v0.1.4】注册「新建态已保存」钩子：保存成功后由 [ProviderNav] 回调，
        //   把占位页换成真内容。页面离开组合时必须解绑，否则旧壳会一直挂在单槽上。
        //   【与原实现的对应】原来挂在 `addOnAttachStateChangeListener` 的
        //   onViewAttached/onViewDetached 上；Compose 侧等价物就是 DisposableEffect。
        DisposableEffect(act) {
            ProviderNav.bindNewProviderSavedHook { refreshContentIfIdChanged() }
            onDispose { ProviderNav.bindNewProviderSavedHook(null) }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // —— 内容区 ——
            // 【key 的两个分量】curTab：切 tab 时换节点；revision：保存后按新 id 重建模型页。
            //   ⚠ 见本函数头注释「风险点」：换节点会把旧页面 View 摘下来，
            //   其内部 ComposeView 的组合靠 DisposeOnViewTreeLifecycleDestroyed 存活。
            key(curTab, revision) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    // factory 只在该节点**首次组合**时调用一次；`key(...)` 变即换节点 → 会再调一次。
                    factory = {
                        val page = pageFor(providerId, curTab)
                        // 【必须先摘父】`key(...)` 换节点时，旧 AndroidView 的内部容器可能
                        //   还没把这个 View 从它的父上摘掉；此时直接 addView 会抛
                        //   `IllegalStateException: specified child already has a parent`。
                        //   先无条件 `removeView` 一次是标准写法（父为空时是空操作）。
                        //   【为什么这不影响表单状态】摘下来的只是 View 父子关系；
                        //   其内部 ComposeView 的组合由 `DisposeOnViewTreeLifecycleDestroyed`
                        //   守护 —— 该策略只在 lifecycle owner 进入 DESTROYED 时销毁组合，
                        //   **单纯 detach 不会**（见 ComposeHost.createView 的注释：
                        //   「重挂不重建」正是为这种场景设的）。
                        (page.parent as? android.view.ViewGroup)?.removeView(page)
                        page
                    }
                )
            }

            // —— 悬浮胶囊底栏（【r21·统一口径】）——
            //
            // 复用 `DhKit.NavBar` —— 也就是主界面三 Tab 底栏用的同一个 Compose 组件。
            // 好处是**从根上不可能再分叉**：以后调底栏只需改那一处，
            // 所有页面（主界面 / 供应商详情 / 人偶编辑）一起变。
            //
            // 【选中态与切换语义完全保留】
            //   · 当前 tab 由本地 `curTab` 决定（与路由栈顶保持一致），`selected` 传索引；
            //   · 点击仍先写回路由栈顶（`setDetailTab`）再换内容层 —— 返回再进来不会跳回配置页；
            //   · 内容层复用逻辑（cache.cfgPage / cache.mdlPage）一个字没动。
            //
            // 【安全区】`DhKit.NavBar` 自身不带 navigationBarsPadding（主界面那层由
            //   `BottomNav.Bar` 的外层 Box 负责）。本页是覆盖页，外壳直接贴 content，
            //   故这里补上底部让位（原实现是 FrameLayout.LayoutParams.bottomMargin = safe）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = safeDp.dp)
            ) {
                DhKit.NavBar(
                    items = PROVIDER_TABS,
                    selected = if (ProviderNav.TAB_MDL == curTab) 1 else 0,
                    onSelect = { idx ->
                        val want = if (idx == 1) ProviderNav.TAB_MDL else ProviderNav.TAB_CFG
                        if (want != ProviderNav.currentDetailTab()) {
                            // 【新建态保存后要重新解析 id】新建时路由里没有 id，保存成功后
                            //   ProviderEditPage 会把栈顶换成真实 id 的详情路由；这里现解析一次，
                            //   否则会继续拿进页时那个空 id 去查库，切到「模型」栏就落到兜底页。
                            val pid = ProviderNav.currentDetailId(providerId)
                            // 先写回栈顶：之后无论从哪层返回，重新渲染都落在同一个 tab。
                            ProviderNav.setDetailTab(pid, want)
                            // 换内容层：就是改这个状态，`key(curTab, ...)` 会让 AndroidView 取新页。
                            curTab = want
                        }
                    }
                )
            }
        }
    }

    /**
     * 内容页缓存。
     *
     * 【为什么单独一个类而不是两个局部变量】`pageFor` 要在 `remember` 与
     *   `refreshContentIfIdChanged` 两处共享同一份引用；用对象聚合比散落三个局部
     *   `var`（且其中一个要跨函数改）更不容易漏。
     *   【为什么可变字段不是 State】这些是「View 实例缓存」，不是界面状态 ——
     *   界面要不要重画由 `curTab` / `revision` 决定，这里不需要触发重组。
     */
    private class PageCache {
        var cfgPage: View? = null
        var mdlPage: View? = null
        var mdlPageId: String? = null
    }

    /**
     * 按 tab 产内容页。配置 = 供应商编辑页（嵌入式）；模型 = 已添加模型列表。
     *
     * 【保持返回 View 的理由】本文件的既有契约与 `NavHost` 的先例都是
     *   「页面构造器返回 View」；`AndroidView.factory` 正好需要 View。
     *   把这一层包成 Composable 会牵动 ProviderEditPage / ProviderModelsPage
     *   两个文件的对外形态，超出本任务范围。
     */
    private fun content(
        act: Activity,
        providerId: String,
        tab: String,
        bottomPadDp: Int,
        onScroll: ((y: Int) -> Unit)?
    ): View {
        if (ProviderNav.TAB_MDL == tab) {
            // 【新建态】还没保存就没有 id，查库必然查空，会落到「供应商已不存在」兜底页
            //   —— 那对刚点加号进来的人是无意义的惊吓。这里给一句说明性占位，保存后
            //   栈顶换成真实 id、本页会被重建（见 pageFor 的 mdlPageId 判定）成真列表。
            if (providerId.isEmpty()) {
                // 【去 View 化】原来还要套一个 FrameLayout 才能塞进 View 壳；
                //   现在直接返回 ComposeView 即可（它就是 AndroidView 要的 View）。
                return ComposeHost.createView(act) {
                    MissingPage(
                        title = "模型",
                        sub = "尚未保存",
                        message = "这个供应商还没保存，保存之后就能在这里添加模型了。",
                        onBack = { ProviderNav.back(act) }
                    )
                }
            }
            return ProviderModelsPage.build(act, providerId, bottomPadDp, onScroll)
        }
        return ProviderEditPage.buildEmbedded(act, providerId, bottomPadDp, onScroll)
    }

}
