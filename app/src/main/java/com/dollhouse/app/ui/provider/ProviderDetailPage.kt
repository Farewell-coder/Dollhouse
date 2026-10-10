package com.dollhouse.app.ui.provider

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.anim.Springs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.ApiPageKit
import com.dollhouse.app.ui.widget.GlassCapsule

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
 * 【为什么切 tab 不重建整页】重建会把指示器的滑动动画一起清掉（页面被换掉，
 *        动画没有宿主），看上去就是「颜色啪一下变了」—— 这正是要避免的突兀。
 *        所以切 tab 只换内容层 + 滑指示器，页面本身一直在树上。
 *
 * 【坑】① 指示器与按钮同宽是硬约束，否则滑动终点算不准；
 *        ② 内容层底部必须留出 RESERVE_DP，不然最后一行的可点区域被胶囊盖住；
 *        ③ 切 tab 要把「当前 tab」写回路由栈顶，否则返回再进来会跳回配置页。
 */
object ProviderDetailPage {

    /** 底栏吃掉的高度：胶囊 44 + 距底 14 + 底部呼吸 30。内容页按此值垫底。 */
    internal const val RESERVE_DP = 88
    /** 单个 tab 按钮的宽度，指示器滑动的基准。 */
    private const val TAB_W_DP = 88
    private const val PILL_DP = 52
    /** 胶囊距屏幕底部的边距（规格：14dp）。 */
    private const val SHELL_MARGIN_DP = 14
    /** 玻璃胶囊的投影高度：唯一挂 elevation 的一层就是它。 */
    private const val SHELL_ELEV_DP = 8
    /**
     * 毛玻璃染色层的不透明度（0~255）。
     *
     * 【为什么是这个量级】要求是「半透明 + 内容隐约透过」，同时「文字必须始终清楚」。
     *   太低（<0x40）时背后内容会与文字抢注意力，未选中项的标签开始糊；太高（>0xA0）
     *   就退回成实心色块，毛玻璃白做。0x7A 约等于 48%：背后内容能看见轮廓与色块，
     *   但被压暗成一层次要信息，文字对比度不受影响。
     */
    private const val GLASS_FILL = 0x7A
    /** 胶囊内选中指示块的染色不透明度：比玻璃底更实，保证「选中」一眼可辨。 */
    private const val SEL_FILL = 0xCC

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
        // 【滚动上报的接收端】内容页创建在 pill / glass 之前，这里先用持有者占位，
        //   等底栏装配完再把它们填进去 —— 回调是懒执行的，届时引用必然就绪。
        val hideHost = arrayOfNulls<Any>(2)
        val lastY = intArrayOf(0)
        val onScroll: (Int) -> Unit = { y ->
            val down = y > lastY[0] + 6
            val up = y < lastY[0] - 6
            lastY[0] = y
            val glassV = hideHost[1] as? GlassCapsule
            val pillV = hideHost[0] as? View
            // 【必须显式重绘】滚动是内容层在动、玻璃自己没动 —— 不主动重绘的话，
            //   模糊画面会定格在开始滚动的那一帧，看起来像贴了一张静态截图。
            //   降采样后只有约 130×33 像素要处理，每帧重算的代价远小于一次列表重绘。
            glassV?.invalidate()
            if (down) {
                pillV?.let { UiKit.fade(it, false) }
                glassV?.setBlurOn(false)
            } else if (up || y <= 0) {
                pillV?.let { UiKit.fade(it, true) }
                glassV?.setBlurOn(true)
            }
        }
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
        val body = pageFor(providerId, cur)
        slot.addView(body, FrameLayout.LayoutParams(-1, -1))
        root.addView(slot, FrameLayout.LayoutParams(-1, -1))

        // —— 悬浮玻璃胶囊底栏 ——
        // 【规格】一颗 176×44dp 的玻璃胶囊，距屏幕底 14dp 居中浮动，适配安全区。
        //   全底栏只有这一层挂投影 —— 它同时是背景层、模糊层、投影层。
        //   【d22】这里纠正了 d21 的一处回归：当时把背景换成透明、只留一颗实心蓝块，
        //   蓝块又挂了 8dp elevation，于是它被绘制到「配置 / 模型」文字之上，
        //   整块把左侧按钮盖成了一片空蓝色。现在背景与指示块分离：
        //   玻璃胶囊在最底层（有投影），选中指示块在它之上（无投影，只换色），
        //   文字与图标在最顶层，任何情况下都不会被背景吃掉。
        val pill = LinearLayout(ctx)
        pill.orientation = LinearLayout.HORIZONTAL
        // pill 只负责给胶囊算居中位置，自身全透明、无投影、无内边距。
        pill.gravity = Gravity.CENTER_HORIZONTAL
        pill.setPadding(0, 0, 0, 0)
        // 不裁子 View：胶囊自己的投影要越过 pill 边界向外散开。
        pill.clipChildren = false
        pill.background = null
        pill.elevation = 0f

        val tabW = ApiPageKit.dp(ctx, TAB_W_DP)
        val tabH = ApiPageKit.dp(ctx, PILL_DP - 8)
        val capsuleW = tabW * 2

        // 【主题自适应】玻璃底色取当前主题的卡片色 + 半透明：浅色主题是「半透明白」，
        //   暗色 / 纯黑主题自动变成「半透明深色」，彩色（莫奈）主题则跟着主色相偏。
        //   绝不写死成白色 —— 写死会让暗色主题下一片惨白。
        val darkTheme = Color.luminance(UiKit.CARD) < 0.5f
        val glassFill = withAlpha(UiKit.CARD, GLASS_FILL)
        // 边缘高光：用主题描边色（本就带透明度），不是白色实线。
        val hairline = UiKit.STROKE
        // 采样兜底色：内容层有透明区域时先铺一层主题底色，避免模糊后出现脏边。
        val sampleBase = (UiKit.BG and 0x00FFFFFF) or 0xFF000000.toInt()

        val glass = GlassCapsule(ctx, glassFill, hairline, sampleBase)
        glass.elevation = ApiPageKit.dp(ctx, SHELL_ELEV_DP).toFloat()
        glass.clipChildren = false

        // 选中指示块：只在玻璃之上换一块更实的主题色，不挂投影（有投影就会压住文字）。
        val sel = View(ctx)
        sel.background = UiKit.round(withAlpha(UiKit.ACC, SEL_FILL), ctx, 999f)
        glass.addView(sel, FrameLayout.LayoutParams(tabW, -1))

        val cfgBtn = tabButton(ctx, "配置", Icons.IC_SETTINGS, darkTheme)
        val mdlBtn = tabButton(ctx, "模型", Icons.IC_LAYERS, darkTheme)
        val clp = FrameLayout.LayoutParams(tabW, tabH)
        clp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        glass.addView(cfgBtn, clp)
        val mlp = FrameLayout.LayoutParams(tabW, tabH)
        mlp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        mlp.leftMargin = tabW
        glass.addView(mdlBtn, mlp)

        pill.addView(glass, LinearLayout.LayoutParams(capsuleW, tabH))

        val markerAnim = arrayOfNulls<ValueAnimator>(1)
        val travel = ApiPageKit.dp(ctx, TAB_W_DP).toFloat()
        val ev = ArgbEvaluator()
        val on = UiKit.ON_ACC
        val off = UiKit.SUB

        // 指示器先摆到位（不播动画），避免进页时从左侧闪一下。
        sel.translationX = if (ProviderNav.TAB_MDL == cur) travel else 0f
        paintTab(ProviderNav.TAB_CFG == cur, cfgBtn, on, off)
        paintTab(ProviderNav.TAB_MDL == cur, mdlBtn, on, off)

        val click = View.OnClickListener { v ->
            val want = if (v === cfgBtn) ProviderNav.TAB_CFG else ProviderNav.TAB_MDL
            if (want != ProviderNav.currentDetailTab()) {
                // 【新建态保存后要重新解析 id】新建时路由里没有 id，保存成功后
                //   ProviderEditPage 会把栈顶换成真实 id 的详情路由；这里现解析一次，
                //   否则会继续拿进页时那个空 id 去查库，切到「模型」栏就落到兜底页。
                val pid = ProviderNav.currentDetailId(providerId)
                // 先写回栈顶：之后无论从哪层返回，重新渲染都落在同一个 tab。
                ProviderNav.setDetailTab(pid, want)
                val toMdl = ProviderNav.TAB_MDL == want
                slot.removeAllViews()
                val page = pageFor(pid, want)
                slot.addView(page, FrameLayout.LayoutParams(-1, -1))
                // 【为什么不再重挂滚动监听】让位与滚动上报现在长在内容页自己身上
                //   （Compose 的 [DhKit.Page] 收 bottomPadDp / onScroll），换内容层
                //   不影响壳层这边的回调引用，故切 tab 无需再做任何补偿。
                markerAnim[0]?.cancel()
                val from = sel.translationX
                val to = if (toMdl) travel else 0f
                markerAnim[0] = Springs.drive(Springs.snappy(), object : Springs.Listener {
                    override fun onUpdate(p: Float) {
                        sel.translationX = Springs.lerp(from, to, p)
                        val c = ev.evaluate(p, if (toMdl) off else on, if (toMdl) on else off) as Int
                        cfgBtn.setTextColor(if (toMdl) c else on)
                        mdlBtn.setTextColor(if (toMdl) on else c)
                    }

                    override fun onEnd() {
                        sel.translationX = to
                        paintTab(!toMdl, cfgBtn, on, off)
                        paintTab(toMdl, mdlBtn, on, off)
                    }
                })
            }
        }
        cfgBtn.setOnClickListener(click)
        mdlBtn.setOnClickListener(click)
        UiKit.press(cfgBtn)
        UiKit.press(mdlBtn)

        // 【d22·要求二】pill 横跨整屏但完全透明、无内边距、无投影 —— 它只是给玻璃胶囊
        //   算居中位置的尺子。画面里唯一的背景层是那颗 176×44dp 的胶囊本身，
        //   胶囊左右两侧就是页面原样，没有矩形底板、没有通栏背景填充。
        val plp = FrameLayout.LayoutParams(-1, -2)
        plp.gravity = Gravity.BOTTOM
        val shellMargin = ApiPageKit.dp(ctx, SHELL_MARGIN_DP)
        plp.leftMargin = shellMargin
        plp.rightMargin = shellMargin
        // 适配安全区：距底再让开导航栏高度（手势导航下为 0）。
        plp.bottomMargin = shellMargin + safe
        root.addView(pill, plp)
        // 【把回调接收端填上】onScroll 在内容页创建时已经交出去，那时 pill / glass 还不存在，
        //   故用持有者占位；这里底栏装配完毕，正式接线。
        hideHost[0] = pill
        hideHost[1] = glass
        // 采样源 = 内容层：胶囊背后要有真实内容可透，模糊才有意义。
        glass.setSource(slot)
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

    /**
     * 一个 tab 按钮：图标 + 文字竖排，宽度固定以便指示器算终点。
     *
     * 【文字 / 图标为什么必须是主题派生色】底栏是半透明玻璃，背后内容会透过来，
     *   所以「未选中项的可读性」由「自身颜色 vs 玻璃+背后内容的混合色」的对比度决定。
     *   写死白或黑在某个主题下必然糊：这里一律从主题取值 ——
     *   未选中走 SUB（本就是「次要文字色」，已与卡片底做过对比度校核），
     *   选中走 ON_ACC（主题规定的「主色之上的反白色」，暗底主题里它是深色）。
     *   浅色玻璃上再补一道极轻的描边（见 setShadowLayer），保证墨色文字压在
     *   半透明底 + 花哨内容上时依然立得住，又不至于变成「加粗黑边」。
     */
    private fun tabButton(ctx: Context, text: String, iconRes: Int, darkGlass: Boolean): TextView {
        val t = TextView(ctx)
        t.tag = text
        t.text = text
        t.setTextSize(UiKit.FS_CHIP)
        t.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        t.gravity = Gravity.CENTER
        // 图标用复合 drawable 上下排：比嵌套 LinearLayout 少一层，命中区也更完整。
        // 【坑】复合 drawable 按资源的固有尺寸（24dp）绘制，图标 + 文字会撑破 44dp 的按钮，
        //   必须显式 setBounds 收到 17dp。
        val icon = ApiPageKit.dp(ctx, 17)
        val d = Icons.get(ctx, iconRes, UiKit.SUB)
        if (d != null) {
            d.setBounds(0, 0, icon, icon)
        }
        t.setCompoundDrawablePadding(ApiPageKit.dp(ctx, 3))
        t.setCompoundDrawables(null, d, null, null)
        // 极轻的文字描边：亮玻璃上给深色字加一圈与主题底同色的晕，暗玻璃上反之。
        //   radius 只有 0.6dp，作用是「从花哨背景里把字拔出来」，肉眼不会看成描边效果。
        applyInkShadow(t, darkGlass)
        return t
    }

    /**
     * 给底栏文字加一层与玻璃同色系的极轻阴影，用来在半透明底上稳住可读性。
     *
     * 【为什么必须按主题反过来】浅色玻璃（白 48%）上文字是深色，需要一圈「亮晕」把字
     *   从背后内容里拔出来；暗色玻璃上文字是浅色，需要的是「暗晕」。两者都取主题的
     *   BG 色，天然跟着主题走，不需要为每个主题写一份。
     */
    private fun applyInkShadow(t: TextView, darkGlass: Boolean) {
        try {
            val halo = (UiKit.BG and 0x00FFFFFF) or (if (darkGlass) 0x66000000 else 0x99FFFFFF.toInt())
            t.setShadowLayer(UiKit.dpf(t.context, 0.6f), 0f, 0f, halo)
        } catch (ignored: Throwable) {
            // 描边只是增强，失败不影响可读性基线。
        }
    }

    /** 在 32 位 ARGB 上换掉 alpha 通道，保留 RGB。 */
    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }

    /** 选中态：文字 + 图标一起换色，避免「文字白了图标还是灰的」。 */
    private fun paintTab(on: Boolean, t: TextView, onColor: Int, offColor: Int) {
        val color = if (on) onColor else offColor
        t.setTextColor(color)
        try {
            val ds = t.compoundDrawables
            if (ds != null && ds[1] != null) {
                ds[1].mutate().setTint(color)
            }
        } catch (ignored: Throwable) {
            // 上色失败不影响功能，图标保持原色。
        }
    }
}
