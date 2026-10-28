package com.dollhouse.app.ui.provider

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.ApiPageKit
/**
 * 【职责】供应商详情页：内容区（按 tab 装整页）+ 悬浮胶囊底栏（配置 / 模型）。
 *
 * 【为什么用「装整页」而不是「重画内容」】配置 tab 就是原来的供应商编辑页、
 *        模型 tab 有自己的顶栏与列表，两者形态完全不同。直接把现成页塞进来，
 *        既复用零改动的样式，也不用把两套布局揉成一份条件分支。
 *
 * 【为什么底栏浮在内容之上】规格定的是「悬浮胶囊式」。用 FrameLayout 叠加，
 *        底栏不参与内容布局，内容照常整屏滚动，只在底部预留出它占的高度。
 *
 * 【底栏实现·r21 起统一】底栏直接用 [DhKit.NavBar]（主界面三 Tab 底栏的同一个
 *        Compose 组件），不再手写 View 版胶囊 —— 见 build() 里的说明。
 *        因此切 tab 时**不再有指示块滑动动画**（NavBar 的选中态是即时换底色，
 *        与主界面 / 人偶编辑页一致）。旧注释里「切 tab 保留指示器动画」的
 *        那段约束随之作废。
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


    internal fun build(act: Activity, providerId: String, tab: String): View {
        val ctx: Context = act
        // 【必须有不透明底】本页是叠加在设置页之上的整屏页，根若透明，
        //   底部预留区会透出下层设置页的行（曾出现「聊天背景 / 未设置」串进来）。
        val root = FrameLayout(ctx)
        root.setBackgroundColor(UiKit.BG)
        // 【状态栏高度不在这里留】本页内容层走的 UiKit.topBar 已经含状态栏留白
        //   （ProviderEditPage / ProviderModelsPage 都带 topBar），这里再留会变双倍。
        val pv = ProviderStore.findProvider(ctx, providerId)
        // 【新建态】providerId 传空 = 「加号」进来的新建供应商：没有 id 可查库，
        //   但要走**同一个壳**（底部「配置｜模型」胶囊栏），才能和点卡片进来的长相一致。
        val isNew = providerId.isEmpty()
        if (!isNew && pv == null) {
            val miss = ApiPageKit.pageRoot(ctx)
            miss.addView(UiKit.topBar(ctx, "供应商", "已不存在", View.OnClickListener {
                ProviderNav.back(act)
            }))
            miss.addView(ApiPageKit.note(ctx, "这个供应商已经被删除了。"))
            root.addView(miss, FrameLayout.LayoutParams(-1, -1))
            return root
        }

        val cur = if (ProviderNav.TAB_MDL == tab) ProviderNav.TAB_MDL else ProviderNav.TAB_CFG
        // 安全区：手势导航下为 0，三键导航下等于导航栏高度。底栏与内容预留都要算进去。
        val safe = UiKit.navBarPad(ctx)
        // 内容层：底部不留 padding。让位改由内容页内部垫底承担（见下方 reserveDp）。
        //   【为什么不能在 slot 上留底】留了之后，胶囊所在的那条高度带上是一片空白
        //   padding，毛玻璃采样只能采到纯底色，模糊等于白做 —— 那就退化成
        //   「一个实心 BottomBar 降低透明度」，正是本次要避免的。挪进内容页之后，
        //   页面铺到屏幕底，滚动时内容会从胶囊背后正常穿过。
        val slot = FrameLayout(ctx)
        slot.setPadding(0, 0, 0, 0)
        // 【让位 dp】内容页底部要留出胶囊高度，否则最后一行被盖住。
        //   迁移后内容页是 Compose，壳层无法再从 View 树里找到它的滚动容器
        //   （`verticalScroll` 不是 `android.widget.ScrollView`），故这个值必须由
        //   壳层算好、交给内容页自己去垫底（见 content() 的 bottomPadDp）。
        val safeDp = (safe / ctx.resources.displayMetrics.density).toInt()
        val reserveDp = RESERVE_DP + safeDp
        // 【滚动上报已停用】r21 统一底栏后，`DhKit.NavBar` 不做「滚动淡出 / 模糊联动」，
        //   故不再需要把内容页的滚动位置回传给壳层 —— 直接传 null。
        //   内容页侧的两个参数（bottomPadDp / onScroll）保持不变：让位仍由
        //   bottomPadDp 承担，onScroll 传 null 即「不上报」，两条链各自独立。
        val onScroll: ((Int) -> Unit)? = null
        // 【切 tab 为什么必须复用实例】切 tab 是 slot.removeAllViews() 再挂新的，
        //   被摘下的那一页成了孤儿、状态全丢 —— 用户填到一半的表单切一下「模型」
        //   再切回来就清空了。所以两个 tab 各留一份：配置页全程复用（它才是表单），
        //   模型页只在 id 没变时复用（新建态保存拿到真实 id 后必须重建，否则还是占位页）。
        var cfgPage: View? = null
        var mdlPage: View? = null
        var mdlPageId: String? = null
        fun pageFor(id: String, t: String): View {
            if (ProviderNav.TAB_MDL == t) {
                if (mdlPage == null || mdlPageId != id) {
                    mdlPage = content(act, id, t, reserveDp, onScroll)
                    mdlPageId = id
                }
                return mdlPage!!
            }
            if (cfgPage == null) {
                cfgPage = content(act, id, t, reserveDp, onScroll)
            }
            return cfgPage!!
        }

        /**
         * 【v0.1.4·修「保存后模型页仍显示尚未保存」】
         *
         * ## 问题
         * 新建供应商时壳层记的 `providerId` 是空串。用户在配置页点保存后，
         * [ProviderEditPage] 会把**路由栈顶**换成真实 id（[ProviderNav.replaceDetailNew]），
         * 但壳层这个 `providerId` 形参不会变 —— 它是页面创建时捕获的。
         * 于是用户**不切 tab、直接在原地**的情况下，内容层仍是旧的占位页；
         * 只有手动切一次 tab 才会走到 `pageFor(pid, want)` 用新 id 重建。
         *
         * 更常见的是「保存后立刻切模型」：切 tab 时读的是栈顶（这时已经有了真实 id），
         * 本应正常 —— 但如果保存前后的栈顶写法没变（例如上一版 replaceDetailNew 漏调），
         * 解析出来的仍是空串，模型页就一直是「尚未保存」。
         *
         * ## 修法
         * 提供 [refreshContentIfIdChanged]：把「壳层记的 id」与「栈顶真实 id」比一次，
         * 变了就丢弃旧缓存页、按新 id 重建。保存成功后被 [ProviderNav] 回调触发。
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
            mdlPage = null
            mdlPageId = null
            // 只有在用户正停在「模型」页时才需要立刻换内容。
            //   停在配置页时什么都不用做：等他点「模型」，pageFor 会按新 id 现建。
            if (ProviderNav.TAB_MDL == ProviderNav.currentDetailTab()) {
                slot.removeAllViews()
                slot.addView(pageFor(nowId, ProviderNav.TAB_MDL), FrameLayout.LayoutParams(-1, -1))
            }
        }
        val body = pageFor(providerId, cur)
        slot.addView(body, FrameLayout.LayoutParams(-1, -1))
        root.addView(slot, FrameLayout.LayoutParams(-1, -1))

        // 【v0.1.4】注册「新建态已保存」钩子：保存成功后由 [ProviderNav] 回调，
        //   把占位页换成真内容。页面被摘除时必须解绑，否则旧壳会一直挂在单槽上。
        ProviderNav.bindNewProviderSavedHook { refreshContentIfIdChanged() }
        root.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                ProviderNav.bindNewProviderSavedHook { refreshContentIfIdChanged() }
            }

            override fun onViewDetachedFromWindow(v: View) {
                ProviderNav.bindNewProviderSavedHook(null)
            }
        })

        // —— 悬浮胶囊底栏（【r21·统一口径】）——
        //
        // 【本次改动】用户定案：「第二幅图下面的跟第一幅统一，以后底栏都统一第一幅图的样子」。
        //   改造前这里是**手写的一整套 View 底栏**：`GlassCapsule` + 自绘选中块 +
        //   两个 TextView + `Springs` 驱动指示器滑动。它在形态上与主界面底栏不一致
        //   （尺寸、内边距、圆角、选中块形状都不一样），于是「同一个 App 两种底栏」。
        //
        //   现在改为直接复用 **`DhKit.NavBar`** —— 也就是主界面三 Tab 底栏用的同一个
        //   Compose 组件。好处是**从根上不可能再分叉**：以后调底栏只需改那一处，
        //   所有页面（主界面 / 供应商详情 / 人偶编辑）一起变。
        //
        // 【为什么用 ComposeView 包一层】本页其余部分仍是 View（`slot` 装的是 View 页面），
        //   而 `DhKit.NavBar` 是 Composable。`ComposeHost.createView` 会复用 Activity
        //   已有的 Compose 宿主生命周期（幂等），不会新起一套 recomposer。
        //
        // 【选中态与切换语义完全保留】
        //   · 当前 tab 由 `ProviderNav.currentDetailTab()` 决定，`selected` 传索引；
        //   · 点击仍先写回路由栈顶（`setDetailTab`）再换内容层 —— 返回再进来不会跳回配置页；
        //   · 内容层复用逻辑（cfgPage / mdlPage）一个字没动。
        val navBar = ComposeHost.createView(ctx) {
            DhKit.NavBar(
                items = PROVIDER_TABS,
                selected = if (ProviderNav.TAB_MDL == cur) 1 else 0,
                onSelect = { idx ->
                    val want = if (idx == 1) ProviderNav.TAB_MDL else ProviderNav.TAB_CFG
                    if (want != ProviderNav.currentDetailTab()) {
                        // 【新建态保存后要重新解析 id】新建时路由里没有 id，保存成功后
                        //   ProviderEditPage 会把栈顶换成真实 id 的详情路由；这里现解析一次，
                        //   否则会继续拿进页时那个空 id 去查库，切到「模型」栏就落到兜底页。
                        val pid = ProviderNav.currentDetailId(providerId)
                        // 先写回栈顶：之后无论从哪层返回，重新渲染都落在同一个 tab。
                        ProviderNav.setDetailTab(pid, want)
                        slot.removeAllViews()
                        val page = pageFor(pid, want)
                        slot.addView(page, FrameLayout.LayoutParams(-1, -1))
                    }
                }
            )
        }

        val plp = FrameLayout.LayoutParams(-1, -2)
        plp.gravity = Gravity.BOTTOM
        // 【安全区】`DhKit.NavBar` 自身不带 navigationBarsPadding（主界面那层由
        //   `BottomNav.Bar` 的外层 Box 负责）。本页是覆盖页，外壳直接贴 content，
        //   故这里补上底部让位，手势导航下为 0、三键导航下自动抬高。
        plp.bottomMargin = safe
        root.addView(navBar, plp)
        return root
    }

    /** 按 tab 产内容页。配置 = 供应商编辑页（嵌入式）；模型 = 已添加模型列表。 */
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
                val root = FrameLayout(act)
                val miss = ApiPageKit.pageRoot(act)
                miss.addView(UiKit.topBar(act, "模型", "尚未保存", View.OnClickListener {
                    ProviderNav.back(act)
                }))
                miss.addView(ApiPageKit.note(act, "这个供应商还没保存，保存之后就能在这里添加模型了。"))
                root.addView(miss, FrameLayout.LayoutParams(-1, -1))
                return root
            }
            return ProviderModelsPage.build(act, providerId, bottomPadDp, onScroll)
        }
        return ProviderEditPage.buildEmbedded(act, providerId, bottomPadDp, onScroll)
    }
}
