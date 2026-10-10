package com.dollhouse.app.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.ai.ModelRules
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.chat.ChatPanel
import com.dollhouse.app.ui.settings.MemPage
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.theme.Fonts

/**
 * 【职责】输入行工具条上三个按钮共用的底部弹出面板基座 + 模型配置选择面板。
 *
 * 【入口】ChatPanel 的工具条按钮（鲸鱼 = 模型配置、灯泡 = 思考程度、加号 = 功能）。
 *
 * 【交互】遮罩与面板都叠在「父链里第一个 FrameLayout」（与 ChatDrawer 同一套挂载逻辑）；
 *         模型配置的读写全走 ProviderStore，点一下等于把当前供应商与模型切过去。
 *
 * 【扩展】再加一个工具条按钮 = 加一个 showXxx 静态方法，复用 open/close 两处即可。
 *
 * 【坑】本类不能用系统 Dialog —— 桌宠浮窗场景的 Context 链里没有 Activity，
 *       拿不到 window token；所以和 ChatDrawer 一样全部手搭 View。
 *       面板是「按需新建、关闭时整个摘掉」，不缓存复用，避免监听器累积。
 */
object SheetPanel {

    /** 模型配置面板的遮罩 tag，用于判断是否已打开。 */
    private const val TAG_MODEL = "feiyu_sheet_model"
    private const val TAG_THINK = "feiyu_sheet_think"
    private const val TAG_MEM = "feiyu_sheet_mem"

    /* ------------------------------ 基座 ------------------------------ */

    /**
     * 弹一个底部面板。
     * 【坑】遮罩是全屏 FrameLayout，点空白处关闭；面板本身 setClickable(true) 吃掉点击，
     *       否则点面板内部也会被遮罩的 onClick 当成点空白而关掉。
     */
    private fun open(layer: ViewGroup?, tag: String, ctx: Context, heightDp: Int): FrameLayout? {
        if (layer == null) {
            return null
        }
        // 同一个面板已经开着就先摘掉，避免连点叠好几层。
        val old = layer.findViewWithTag<View>(tag)
        if (old != null) {
            layer.removeView(old)
            return null
        }
        // 顺手关掉别的面板：三个工具条按钮是互斥的。
        closeAll(layer)

        val shade = FrameLayout(ctx)
        shade.tag = tag
        shade.setBackgroundColor(UiKit.SCRIM)
        shade.isClickable = true
        shade.setOnClickListener { UiKit.slideDownOut(shade) }

        val panel = LinearLayout(ctx)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = UiKit.round(UiKit.card(), ctx, 18f)
        panel.isClickable = true
        val pad = UiKit.dp(ctx, 16f)
        panel.setPadding(pad, UiKit.dp(ctx, 14f), pad, UiKit.dp(ctx, 18f))

        // 顶部拖拽条：纯装饰，用来表明「这是从底部弹上来的」。
        val grip = View(ctx)
        grip.background = UiKit.round(UiKit.LINE, ctx, 3f)
        val glp = LinearLayout.LayoutParams(UiKit.dp(ctx, 40f), UiKit.dp(ctx, 4f))
        glp.gravity = Gravity.CENTER_HORIZONTAL
        glp.bottomMargin = UiKit.dp(ctx, 10f)
        panel.addView(grip, glp)

        val plp = FrameLayout.LayoutParams(-1, if (heightDp <= 0) -2 else UiKit.dp(ctx, heightDp.toFloat()))
        plp.gravity = Gravity.BOTTOM
        // 面板左右留一点边，避免贴着屏幕边缘显得满。
        plp.leftMargin = UiKit.dp(ctx, 8f)
        plp.rightMargin = UiKit.dp(ctx, 8f)
        plp.bottomMargin = UiKit.dp(ctx, 8f)
        shade.addView(panel, plp)
        layer.addView(shade, FrameLayout.LayoutParams(-1, -1))

        panel.tag = tag + "_panel"
        // 底部面板：遮罩淡入 + 面板自底部滑入（原先直接 addView，瞬间弹出）。
        panel.post { UiKit.slideUpIn(shade, panel) }
        return shade
    }

    /** 取回 open() 建的面板容器；没开就返回 null。 */
    private fun body(shade: FrameLayout?): LinearLayout? {
        if (shade == null || shade.childCount == 0) {
            return null
        }
        val v = shade.getChildAt(0)
        return v as? LinearLayout
    }

    private fun closeAll(layer: ViewGroup?) {
        val tags = arrayOf(TAG_MODEL, TAG_THINK, TAG_MEM)
        val l = layer ?: return
        for (tag in tags) {
            val v = l.findViewWithTag<View>(tag)
            if (v != null) {
                UiKit.slideDownOut(v)
            }
        }
    }

    /** 面板顶部的小标题。 */
    private fun title(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(16.0f)
        t.setTextColor(UiKit.TITLE)
        t.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        t.setPadding(0, 0, 0, UiKit.dp(ctx, 4f))
        return t
    }

    private fun sub(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = text
        t.setTextSize(UiKit.FS_TINY)
        t.setTextColor(UiKit.SUB)
        t.setLineSpacing(UiKit.dp(ctx, 2f).toFloat(), 1.0f)
        t.setPadding(0, 0, 0, UiKit.dp(ctx, 8f))
        return t
    }

    /** 面板里的一行：可点，选中态给底色。 */
    private fun row(ctx: Context, name: String, right: String?, active: Boolean,
                    onClick: View.OnClickListener?): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = UiKit.round(if (active) UiKit.CHAT_CHIP_ON else UiKit.SOFT, ctx, 10f)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(ctx, 6f)
        r.layoutParams = lp
        val pad = UiKit.dp(ctx, 12f)
        r.setPadding(pad, UiKit.dp(ctx, 10f), pad, UiKit.dp(ctx, 10f))

        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = name
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.END
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))

        if (right != null && !right.isEmpty()) {
            val v = TextView(ctx)
            v.typeface = Fonts.ui(ctx)
            v.text = right
            v.setTextSize(UiKit.FS_SUB)
            v.setTextColor(UiKit.SUB)
            v.isSingleLine = true
            v.ellipsize = TextUtils.TruncateAt.END
            val vlp = LinearLayout.LayoutParams(0, -2, 1.0f)
            vlp.leftMargin = UiKit.dp(ctx, 8f)
            r.addView(v, vlp)
        }
        if (onClick != null) {
            r.setOnClickListener(onClick)
            UiKit.press(r)
        }
        return r
    }

    /* ------------------------------ 1. 模型配置 ------------------------------ */

    /**
     * 弹「模型配置」面板：供应商 → 模型两级分组。点第一级展开它启用的模型，点模型即选中。
     *
     * 【数据来源】ProviderStore + ModelRules.enabledGroups：供应商禁用则整组不出现，
     *        模型禁用则不列。这里不再维护任何「收藏」概念 —— 启用的模型本来就该可见。
     *
     * 【交互】同一时刻只展开一组，展开下一组时把上一组收干净（此前两组同开，谁是谁的下级看着就糊）。
     *
     * 【为什么不能懒加载】本方法在桌宠浮窗场景调用，Context 链里没有 Activity，
     *        弹系统对话框和起 Activity 都不可靠；所以列表在弹出时一次性建完。
     */
    @JvmStatic
    fun showModels(ctx: Context, layer: ViewGroup?) {
        val shade = open(layer, TAG_MODEL, ctx, 0)
        val panel = body(shade) ?: return
        panel.addView(title(ctx, "模型配置"))
        val groups = ModelRules.enabledGroups(
            ProviderStore.providers(ctx), ModelStore.models(ctx)
        )
        val sel = ProviderStore.current(ctx)
        panel.addView(
            sub(
                ctx, "点供应商展开它的模型，再点一个模型就切过去。共 "
                        + groups.size + " 个启用中的供应商。\n供应商或模型被禁用时不在这里出现，"
                        + "要增删改去设置页「聊天设置（云端 API）」的「提供商」里操作。"
            )
        )
        val sc = ScrollView(ctx)
        val open = OpenRow()
        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        sc.addView(list, ViewGroup.LayoutParams(-1, -2))
        for (g in groups) {
            val activeGroup = sel.provider != null && g.providerId == sel.providerId
            val right = g.models.size.toString() + " 个模型"
            list.addView(buildProviderSlot(ctx, open, g, sel, activeGroup, right, layer))
        }
        if (groups.isEmpty()) {
            list.addView(
                labelOf(
                    ctx, "还没有可用的供应商。去设置页「聊天设置（云端 API）」的「提供商」里"
                            + "添加一个，并在它下面启用至少一个聊天模型。"
                )
            )
        }
        // 【坑】这里必须用 wrap_content，不能用 (0, weight=1)：
        //       面板是 wrap_content 弹上来的，权重子在 wrap_content 父容器里会被量成 0 高，
        //       结果整个配置列表被折叠成一条线，点开面板什么都看不到。
        //       内容多到超屏时由 FrameLayout 的 AT_MOST 约束收紧，ScrollView 照旧能滚。
        panel.addView(sc, LinearLayout.LayoutParams(-1, -2))
    }

    /** 构建一行「供应商 + 可展开模型清单」：点供应商切换展开，点模型切到该模型。 */
    private fun buildProviderSlot(ctx: Context, open: OpenRow, g: ModelRules.ModelGroup,
                                  sel: ProviderStore.Selection, activeGroup: Boolean,
                                  right: String, layer: ViewGroup?): LinearLayout {
        // 每一行外面套一个竖向容器：上面是供应商行，下面挂模型清单（展开时才长出来）。
        val slot = LinearLayout(ctx)
        slot.orientation = LinearLayout.VERTICAL
        slot.layoutParams = LinearLayout.LayoutParams(-1, -2)
        val models = LinearLayout(ctx)
        models.orientation = LinearLayout.VERTICAL
        models.visibility = View.GONE
        val opened = booleanArrayOf(false)
        // 【坑】row() 是纯工厂，不会自己挂上去 —— 必须接住返回值 addView，
        //       否则供应商行压根不进布局（面板看着是空的）。
        val head = row(ctx, (if (activeGroup) "● " else "") + g.providerName, right, activeGroup) {
            // 【归属】先把上一组收干净：两组同时挂着，
            //   「谁是下级」在观感上就糊了 —— 用户报的正是这个。
            val prev = open.models
            if (prev != null && prev !== models) {
                UiKit.collapse(prev)
                prev.removeAllViews()
                val op = open.opened
                if (op != null) {
                    op[0] = false
                }
                open.models = null
                open.opened = null
            }
            if (opened[0]) {
                opened[0] = false
                open.models = null
                open.opened = null
                // 【丝滑】模型清单收起用淡出，不再一下消失。
                UiKit.collapse(models)
                models.removeAllViews()
            } else {
                opened[0] = true
                open.models = models
                open.opened = opened
                // 【丝滑】模型清单展开用淡入，不再一下冒出来。
                UiKit.reveal(models)
                models.removeAllViews()
                for (m in g.models) {
                    val on = sel.modelId == m.id
                    models.addView(modelRow(ctx, m.displayName, on) {
                        ProviderStore.setCurrent(ctx, g.providerId, m.id)
                        closeAll(layer)
                    })
                }
            }
        }
        slot.addView(head)
        slot.addView(models)
        return slot
    }

    /**
     * 「当前展开的那一组」的句柄：只记容器和它自己的开关标志。
     * 【为什么需要】展开前要先把它收干净，否则两组的模型清单会同时挂在面板上，
     *   看着就像「不是这个供应商的下级也被列出来了」。
     */
    private class OpenRow {
        var models: LinearLayout? = null
        var opened: BooleanArray? = null
    }

    /** 模型清单里的一行：选中态给底色，点一下就把「这套配置」的模型换成它。 */
    private fun modelRow(ctx: Context, name: String, active: Boolean,
                         onClick: View.OnClickListener): TextView {
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        // 【图标语义】选中项用实心勾图标代替裸「✓ 」字符；未选中不再占位（原先的「· 」是视觉噪声）。
        t.text = name
        t.setTextSize(UiKit.FS_SUB)
        t.setTextColor(if (active) UiKit.CHAT_CHIP_FG else UiKit.TITLE)
        if (active) {
            Icons.stateIcon(t, Icons.IC_CHECK, t.currentTextColor, 13.0f, 5)
        }
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.END
        t.background = UiKit.round(if (active) UiKit.CHAT_CHIP_ON else UiKit.card(), ctx, 8f)
        t.setPadding(UiKit.dp(ctx, 22f), UiKit.dp(ctx, 9f), UiKit.dp(ctx, 12f), UiKit.dp(ctx, 9f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(ctx, 4f)
        t.layoutParams = lp
        t.isClickable = true
        t.setOnClickListener(onClick)
        UiKit.press(t)
        return t
    }

    /* ------------------------------ 2. 思考程度 ------------------------------ */

    /**
     * 弹「调整模型思考深度」面板：灯泡 + 当前档位名 + 6 档刻度滑块。
     * 【交互】点刻度或点档位名都直接存盘；滑块只做视觉指示，不参与手势
     *        （纯 View 的拖动会和父级滚动打架，点选更稳）。
     */
    @JvmStatic
    fun showThink(ctx: Context, layer: ViewGroup?, host: ChatPanel?) {
        val shade = open(layer, TAG_THINK, ctx, 0)
        val panel = body(shade) ?: return
        panel.addView(title(ctx, "调整模型思考深度"))
        panel.addView(sub(ctx, "并不是所有模型都支持深度调整。服务端不认识这些参数时会忽略，不影响正常对话。"))

        val rail = LinearLayout(ctx)
        rail.orientation = LinearLayout.HORIZONTAL
        rail.gravity = Gravity.CENTER_VERTICAL
        panel.addView(rail, LinearLayout.LayoutParams(-1, -2))

        val names = LinearLayout(ctx)
        names.orientation = LinearLayout.HORIZONTAL
        val nlp = LinearLayout.LayoutParams(-1, -2)
        nlp.topMargin = UiKit.dp(ctx, 12f)
        panel.addView(names, nlp)

        panel.addView(sub(ctx, "\u300c不思考」= 明确要求她别绕弯子；「自动」= 交给服务端自己决定；再往后依次加深。"))

        ThinkSheet(ctx, rail, names, host).rebuild()
    }

    /**
     * 思考程度面板的「灯泡 + 档位名 + 6 档刻度」这一块。
     * 【坑】做成内部类是为了拿到自身引用来重画：Java 的匿名内部类里拿不到自己的 Runnable，
     *       用静态字段存会串（同时开两次面板就指向后建的那个）。
     */
    private class ThinkSheet(private val ctx: Context, private val rail: LinearLayout,
                             private val names: LinearLayout, private val host: ChatPanel?) {

        /** 按当前档位整块重画：档位名、刻度高亮、6 个档位名的选中态一起刷。 */
        fun rebuild() {
            val level = PetPrefs.thinkLevel(ctx)
            rail.removeAllViews()
            names.removeAllViews()

            val bulb = Icons.view(ctx, Icons.IC_BRAIN, 24.0f, UiKit.ACC)
            rail.addView(bulb, LinearLayout.LayoutParams(UiKit.dp(ctx, 46f), UiKit.dp(ctx, 46f)))

            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(UiKit.dp(ctx, 10f), 0, 0, 0)

            val state = TextView(ctx)
            state.text = PetPrefs.THINK_NAMES[level]
            state.setTextSize(19.0f)
            state.setTextColor(UiKit.ACC)
            state.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
            box.addView(state)

            // 轨道：每档一个等宽格子，选中那格给主色，视觉上就是「滑块停在这一档」。
            val track = LinearLayout(ctx)
            track.orientation = LinearLayout.HORIZONTAL
            track.gravity = Gravity.CENTER_VERTICAL
            val tlp = LinearLayout.LayoutParams(-1, -2)
            tlp.topMargin = UiKit.dp(ctx, 8f)
            box.addView(track, tlp)
            for (i in PetPrefs.THINK_NAMES.indices) {
                val on = i == level
                val dot = TextView(ctx)
                dot.typeface = Fonts.ui(ctx)
                dot.background = UiKit.round(if (on) UiKit.ACC else UiKit.SWITCH_OFF, ctx, 3f)
                val dlp = LinearLayout.LayoutParams(
                    0, UiKit.dp(ctx, if (on) 8f else 5f), 1.0f
                )
                dlp.rightMargin = if (i == PetPrefs.THINK_NAMES.size - 1) 0 else UiKit.dp(ctx, 5f)
                dlp.gravity = Gravity.CENTER_VERTICAL
                dot.layoutParams = dlp
                dot.isClickable = true
                dot.setOnClickListener(pick(i))
                track.addView(dot)
            }
            box.addView(labelOf(ctx, "拖不动也没关系，点刻度就行"))
            rail.addView(box, LinearLayout.LayoutParams(0, -2, 1.0f))

            // 六个档位的名字横排，点名字同样生效（比点细刻度好按）。
            for (i in PetPrefs.THINK_NAMES.indices) {
                val on = i == level
                val t = TextView(ctx)
                t.text = PetPrefs.THINK_NAMES[i]
                t.setTextSize(UiKit.FS_SUB)
                t.gravity = Gravity.CENTER
                t.setTextColor(if (on) UiKit.ON_ACC else UiKit.SUB)
                t.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
                t.setPadding(0, UiKit.dp(ctx, 7f), 0, UiKit.dp(ctx, 7f))
                t.background = UiKit.round(if (on) UiKit.ACC else UiKit.SOFT, ctx, 8f)
                val lp = LinearLayout.LayoutParams(0, -2, 1.0f)
                lp.rightMargin = if (i == PetPrefs.THINK_NAMES.size - 1) 0 else UiKit.dp(ctx, 5f)
                t.layoutParams = lp
                t.isClickable = true
                t.setOnClickListener(pick(i))
                UiKit.press(t)
                names.addView(t)
            }
        }

        private fun pick(idx: Int): View.OnClickListener {
            return View.OnClickListener {
                PetPrefs.setThinkLevel(ctx, idx)
                rebuild()
            }
        }
    }

    private fun labelOf(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = text
        t.setTextSize(UiKit.FS_TINY)
        t.setTextColor(UiKit.SUB)
        t.setPadding(0, UiKit.dp(ctx, 10f), 0, 0)
        return t
    }

    /* ------------------------------ 3. 记忆（加号） ------------------------------ */

    /**
     * 弹「记忆」功能面板：发图 / 立即总结 / 打开记忆库。
     * 【入口】工具条上的加号。
     *
     * 【d19】原「自动保存记忆」「自动精简记忆」两个开关已移植到设置页「记忆」卡片下级；
     *   本面板因此只剩三个可点项，顶部标题与说明行也一并删掉，间距相应收紧。
     */
    @JvmStatic
    fun showMemory(ctx: Context, layer: ViewGroup?, host: ChatPanel?) {
        val shade = open(layer, TAG_MEM, ctx, 0)
        val panel = body(shade) ?: return
        // 【需求】收紧：少了标题与两行开关说明后，公共 open() 的 14/18dp 内边距显得上下都空，
        //   本面板单独压到 10/12dp。（只改本面板，不动 open()，避免波及模型配置 / 思考程度两个面板。）
        val memPad = UiKit.dp(ctx, 16f)
        panel.setPadding(memPad, UiKit.dp(ctx, 10f), memPad, UiKit.dp(ctx, 12f))
        // 【需求】本面板只留三个可点项：发图 / 立即总结 / 打开记忆库。
        //   · 原「记忆」标题已删（面板本身不需要再自报家门）；
        //   · 「自动保存记忆」「自动精简记忆」两个开关已移植到设置页「记忆」卡片下级；
        //   · 「发图」行不再带副标题 —— 图标 + 「发图」二字已足够说明它是什么。
        panel.addView(
            entryRow(ctx, Icons.IC_IMAGE, "发图", null, Runnable {
                closeAll(layer)
                host?.openPicker()
            })
        )

        val acts = LinearLayout(ctx)
        acts.orientation = LinearLayout.HORIZONTAL
        val alp = LinearLayout.LayoutParams(-1, -2)
        // 【需求】收紧：原先 14dp 的顶间距在少了标题与说明行之后显得空，收到 10dp。
        alp.topMargin = UiKit.dp(ctx, 10f)
        acts.layoutParams = alp

        // 【v2.3】原「立即整理记忆」入口按需求删除（记忆归并保留自动档）；
        //         原位置改为「立即总结」——把当前对话压成摘要，与右侧「打开记忆库」同构造、等宽同行。
        val sum = pill(ctx, "立即总结")
        sum.setOnClickListener {
            closeAll(layer)
            // 【v2.9.4】覆盖复审报的 P1：host==null 会让整条链路静默不执行。
            Logs.i("DollhouseMemo", "[入口] 面板点按 host=" + (host != null))
            host?.summarizeNow()
        }
        acts.addView(sum, LinearLayout.LayoutParams(0, -2, 1.0f))

        val lib = pill(ctx, "打开记忆库")
        val llp = LinearLayout.LayoutParams(0, -2, 1.0f)
        llp.leftMargin = UiKit.dp(ctx, 8f)
        lib.layoutParams = llp
        lib.setOnClickListener {
            closeAll(layer)
            MemPage.open(ctx)
        }
        acts.addView(lib)
        panel.addView(acts)
    }

    /**
     * 面板里的一行「图标 + 名称/说明 + ›」入口。
     * 样式：SOFT 圆角底 + 12dp 横向内边距 + 8dp 上间距，点一下先收面板再执行动作。
     * hint 传 null 或空串则不显示副标题行（与 ApiPageKit.entryRow 同一口径）。
     */
    private fun entryRow(ctx: Context, icon: Int, name: String, hint: String?,
                         action: Runnable?): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = UiKit.round(UiKit.SOFT, ctx, 10f)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(ctx, 8f)
        r.layoutParams = lp
        val pad = UiKit.dp(ctx, 12f)
        r.setPadding(pad, UiKit.dp(ctx, 10f), pad, UiKit.dp(ctx, 10f))
        val iv = UiKit.iconView(ctx, icon, 15.0f, UiKit.SUB)
        val ilp = LinearLayout.LayoutParams(-2, -2)
        ilp.rightMargin = UiKit.dp(ctx, 10f)
        iv.layoutParams = ilp
        r.addView(iv)
        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = name
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        texts.addView(t)
        if (hint != null && hint.length > 0) {
            val s = TextView(ctx)
            s.text = hint
            s.setTextSize(UiKit.FS_TINY)
            s.setTextColor(UiKit.SUB)
            s.setPadding(0, UiKit.dp(ctx, 2f), 0, 0)
            texts.addView(s)
        }
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1.0f))
        val go = TextView(ctx)
        go.text = "\u203a"
        go.setTextSize(UiKit.FS_BTN)
        go.setTextColor(UiKit.SUB)
        go.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        r.addView(go, LinearLayout.LayoutParams(-2, -2))
        r.isClickable = true
        UiKit.press(r)
        r.setOnClickListener {
            action?.run()
        }
        return r
    }

    /** 面板底部的胶囊按钮。 */
    private fun pill(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(UiKit.FS_SUB)
        t.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        t.setTextColor(UiKit.CHAT_CHIP_FG)
        t.gravity = Gravity.CENTER
        t.background = UiKit.round(UiKit.CHAT_CHIP_BG, ctx, 12f)
        t.setPadding(0, UiKit.dp(ctx, 11f), 0, UiKit.dp(ctx, 11f))
        t.isClickable = true
        UiKit.press(t)
        return t
    }
}
