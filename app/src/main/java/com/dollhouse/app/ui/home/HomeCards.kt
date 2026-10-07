package com.dollhouse.app.ui.home

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.anim.Springs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】首页 / 设置页的控件工厂：卡片壳、头部行、开关行、权限行、按钮样式、小注脚。
 *
 * 【入口】HomeUi.apply 装配两页时调用；本类不主动发起调用。
 *
 * 【交互】控件点击后的落地动作全部回到 HomeUi（开关写偏好、去系统设置、切回首页）；
 *         tag 常量与状态文案由本类持有。
 *
 * 【扩展】新增一类卡片 / 行样式 = 在本类加一个工厂方法，不动 HomeUi 的装配逻辑。
 *
 * 【坑】本类不持有状态：卡片展开态存在 view 的 LayoutParams.height 上，
 *       权限行状态由 HomeUi.bindPermRow 在回到前台时刷新。
 */
object HomeCards {
    /** 悬浮窗权限行的 tag，供 HomeUi.syncPerm 定位该行。 */
    const val TAG_PERM_OVERLAY = "feiyu_perm_overlay"

    /** Shizuku 授权行的 tag，供 HomeUi.syncPerm 定位该行（四态，文案由 ShizukuBridge 给）。 */
    const val TAG_PERM_SHIZUKU = "feiyu_perm_shizuku"

    /**
     * 「外观」卡片里两个图片功能入口按钮的 tag。
     * 【为什么必须用 tag 而不是文本】这两个按钮创建在「聊天背景」分组里，SettingsPage.apply
     *   会先把它们包进那张卡片的 body，HomeUi.apply 再按顶层遍历已经找不到；
     *   而且背景按钮的文本要等 onResume 的 refreshLocalUi 才写入，创建时是空串，文本匹配也靠不住。
     */
    const val TAG_BG_PICK = "feiyu_look_bg_pick"
    const val TAG_BG_CLEAR = "feiyu_look_bg_clear"

    /** 权限行右侧状态文案。 */
    const val S_OK = "\u5df2\u6388\u6743"
    const val S_NO = "\u672a\u6388\u6743"

    /** 顶部「← 设置」一行：箭头可点，等价于返回首页。 */
    // 顶部标题行：统一走 UiKit.topBar，返回键点击等价于返回首页。
    @JvmStatic
    fun buildHeader(activity: Activity, ctx: Context): LinearLayout {
        val row = UiKit.topBar(ctx, "设置", null, View.OnClickListener {
            HomeUi.show(activity, true)
        })
        val lp = LinearLayout.LayoutParams(-1, -2)
        // 【顶部不再加 margin】窗口未铺满时代，topBar 上面还有一层容器让位，
        //   这里的 10dp 是「标题与状态栏之间」的额外呼吸；窗口铺满后这 10dp 会直接
        //   顶在状态栏下沿、把标题又推下去，改成 0。
        lp.topMargin = 0
        lp.bottomMargin = UiKit.dp(ctx, 2f)
        row.layoutParams = lp
        return row
    }

    /**
     * 权限卡片内的一行开关子项：左名称 + 右开关，样式与 permRow 的权限行对齐。
     * 点整行或点开关都能切换。
     */
    @JvmStatic
    fun switchRow(ctx: Context, name: String, sw: UiKit.Switch): LinearLayout {
        return switchRow(ctx, name, sw) { on, c ->
            HomeUi.onHideRecentsChanged(on, c)
        }
    }

    /** 开关行变化回调（主题开关这类不走 HomeUi 固定分发的场景用）。 */
    fun interface OnChanged {
        fun onChanged(on: Boolean, ctx: Context)
    }

    /** 同 switchRow，但开关变化后的动作由调用方指定。 */
    @JvmStatic
    fun switchRow(ctx: Context, name: String, sw: UiKit.Switch, cb: OnChanged?): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = UiKit.round(UiKit.SOFT, ctx, 10f)
        val padH = UiKit.dp(ctx, 12f)
        row.setPadding(padH, UiKit.dp(ctx, 10f), padH, UiKit.dp(ctx, 10f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(ctx, 8f)
        row.layoutParams = lp
        val label = TextView(ctx)
        label.text = name
        label.setTextSize(UiKit.FS_BTN)
        label.setTextColor(UiKit.TITLE)
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))
        row.addView(sw, LinearLayout.LayoutParams(-2, -2))
        row.isClickable = true
        UiKit.press(row)
        row.setOnClickListener { v ->
            sw.setOn(!sw.isOn(), true)
            cb?.onChanged(sw.isOn(), v.context)
        }
        return row
    }

    /**
     * 一行权限子项：左侧名称（常规字重）+ 右侧状态（加粗，颜色随授权状态变）。
     * 右侧文本与是否可点由 bindPermRow 在 syncPerm 里按实时状态绑定；
     * 行的点击动作在创建时就挂在行上，syncPerm 通过 setClickable 开关，不反复换监听器。
     */
    @JvmStatic
    fun permRow(ctx: Context, name: String, tag: String): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = tag
        row.background = UiKit.round(UiKit.SOFT, ctx, 10f)
        UiKit.press(row)
        val padX = UiKit.dp(ctx, 12f)
        row.setPadding(padX, UiKit.dp(ctx, 12f), padX, UiKit.dp(ctx, 12f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(ctx, 8f)
        row.layoutParams = lp

        val label = TextView(ctx)
        label.text = name
        label.setTextSize(UiKit.FS_BTN)
        label.setTextColor(UiKit.TITLE)
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))

        val state = TextView(ctx)
        state.text = S_NO
        state.setTextSize(UiKit.FS_BTN)
        state.setTextColor(UiKit.ERR)
        state.typeface = Typeface.DEFAULT_BOLD
        // 【状态图标】初始未授权：文字前面挂一枚叉图标，等 bindPermRow 按实时状态换成勾 / 叉。
        Icons.stateIcon(state, Icons.IC_X_CIRCLE, UiKit.ERR, 13.0f, 4)
        row.addView(state, LinearLayout.LayoutParams(-2, -2))
        // 点击动作统一交回 HomeUi 按 tag 分发，本类不认具体权限。
        row.setOnClickListener { v ->
            val t = v.tag
            HomeUi.permClicked(t?.toString(), v.context)
        }
        return row
    }

    /**
     * 保活引导行：左侧名称 + 右侧「去设置 ›」。
     * 用于系统私有开关（自启动 / 后台活动 / 后台弹出界面）——这类项查不到授权状态，
     * 只能引导用户手动去系统设置里开，因此右侧固定显示入口箭头而非状态。
     * 行的点击动作同样交回 HomeUi.guideClicked 按 tag 分发。
     */
    @JvmStatic
    fun guideRow(ctx: Context, name: String, tag: String): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = tag
        row.background = UiKit.round(UiKit.SOFT, ctx, 10f)
        UiKit.press(row)
        val padX = UiKit.dp(ctx, 12f)
        row.setPadding(padX, UiKit.dp(ctx, 12f), padX, UiKit.dp(ctx, 12f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(ctx, 8f)
        row.layoutParams = lp
        val label = TextView(ctx)
        label.text = name
        label.setTextSize(UiKit.FS_BTN)
        label.setTextColor(UiKit.TITLE)
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))
        val go = TextView(ctx)
        go.text = "\u53bb\u8bbe\u7f6e"
        go.setTextSize(UiKit.FS_BTN)
        go.setTextColor(UiKit.SUB)
        go.typeface = Typeface.DEFAULT_BOLD
        row.addView(Icons.labeled(ctx, Icons.IC_CHEVRON_RIGHT, 14.0f, UiKit.SUB, go, 2),
                LinearLayout.LayoutParams(-2, -2))
        row.setOnClickListener { v ->
            val t = v.tag
            HomeUi.guideClicked(t?.toString(), v.context)
        }
        return row
    }

    /**
     * 一行可点的普通子项：左侧名称 + 右侧当前值文本（带「›」提示可点）。
     * 用于「主题模式」这类点了弹选择面板的行；状态文本由调用方通过返回值里的 tag 更新。
     */
    @JvmStatic
    fun valueRow(ctx: Context, name: String, tag: String): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = tag
        row.background = UiKit.round(UiKit.SOFT, ctx, 10f)
        UiKit.press(row)
        val padX = UiKit.dp(ctx, 12f)
        row.setPadding(padX, UiKit.dp(ctx, 12f), padX, UiKit.dp(ctx, 12f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(ctx, 8f)
        row.layoutParams = lp
        val label = TextView(ctx)
        label.text = name
        label.setTextSize(UiKit.FS_BTN)
        label.setTextColor(UiKit.TITLE)
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))
        val state = TextView(ctx)
        state.text = ""
        state.setTextSize(UiKit.FS_BTN)
        state.setTextColor(UiKit.SUB)
        state.typeface = Typeface.DEFAULT_BOLD
        row.addView(state, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    /** 更新 valueRow 的右侧状态文本（不改颜色）。 */
    @JvmStatic
    fun setRowValue(row: View, text: String?) {
        setRowValue(row, text, NO_TINT)
    }

    /** setRowValue 的「不改色」哨兵：正常颜色不可能是 -1（ARGB 恒为非负）。 */
    const val NO_TINT = -1

    /**
     * 更新 valueRow 的右侧状态文本，并指定语义色。
     *
     * 【为何要带颜色】这些行的右侧写的是「状态」而不是「值」：
     *   运行中 / 未运行、已设置 / 未设置、已授权 / 未授权。
     *   之前一律 SUB 灰，用户看不出好坏 —— 这正是「未授权和已授权颜色没区分」的落点之一。
     *
     * @param color UiKit.OK / UiKit.ERR 语义色；传 NO_TINT 表示沿用当前色。
     */
    @JvmStatic
    fun setRowValue(row: View, text: String?, color: Int) {
        if (row !is LinearLayout) {
            return
        }
        val r = row
        if (r.childCount >= 2 && r.getChildAt(1) is TextView) {
            val tv = r.getChildAt(1) as TextView
            val s = text ?: ""
            val changed = !s.contentEquals(tv.text)
            tv.text = s
            if (color != NO_TINT) {
                // 【丝滑】走颜色插值而不是硬切：状态翻转 / 切主题时颜色是「流」过去的。
                UiKit.setTextColorAnimated(tv, color)
                // 【状态图标】按语义色挂一枚形状标记，与权限行同一套口径：绿 = 勾、红 = 叉。
                //   中性色（NO_TINT / SUB）不挂 —— 「主题模式」「复制 ›」这类是入口行而不是状态行，
                //   挂上状态标记反而语义混乱。
                Icons.stateIcon(tv, if (color == UiKit.OK) Icons.IC_CHECK_CIRCLE
                        else if (color == UiKit.ERR) Icons.IC_X_CIRCLE else 0, color, 13.0f, 4)
            }
            if (changed) {
                // 【丝滑】先起弹簧脉冲，再起 alpha 淡入 —— 顺序不能反：
                //   pulse 内部有一发 v.animate().cancel()（收掉上一段未跑完的缩放），
                //   若放在 alpha 之后会把这次淡入一起取消，文字就卡在 0.35 透明度。
                //   先 pulse 后 alpha，两者各管一条通道（scaleX/Y 走 Springs，alpha 走 View 动画），互不干扰。
                UiKit.pulse(tv)
                tv.alpha = 0.35f
                tv.animate().alpha(1f).setDuration(UiKit.D_MICRO.toLong()).setInterpolator(UiKit.EASE_STD).start()
            }
        }
    }

    /**
     * 造一张可展开的卡片：头部 = 标题（左）+ 箭头（右），body 为 card 的第 1 个子。
     */
    @JvmStatic
    fun buildCard(ctx: Context, title: String): LinearLayout {
        return buildCard(ctx, title, false)
    }

    /**
     * 【v2.10.0】带默认展开态的版本。
     * 收起态是把 body 高度压成 0（而非 GONE），所以卡里的入口按钮在收起时点不到；
     * 「关于」卡片整个内容就是一行入口，必须默认展开，否则入口等于不存在。
     * 用户手动调过之后以用户的选择为准（走 hasCardOpen 判定，不用注册表兜底覆盖）。
     */
    @JvmStatic
    fun buildCard(ctx: Context, title: String, fallbackOpen: Boolean): LinearLayout {
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        UiKit.card(card, ctx)
        val pad = UiKit.dp(ctx, 16f)
        card.setPadding(pad, UiKit.dp(ctx, 4f), pad, UiKit.dp(ctx, 14f))
        val cardLp = LinearLayout.LayoutParams(-1, -2)
        cardLp.topMargin = UiKit.dp(ctx, 10f)
        card.layoutParams = cardLp

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.isClickable = true
        head.setPadding(0, UiKit.dp(ctx, 14f), 0, UiKit.dp(ctx, 12f))

        val t = TextView(ctx)
        t.text = title
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        t.typeface = Typeface.DEFAULT_BOLD
        head.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))

        val arrow = UiKit.arrow(ctx)
        head.addView(arrow, LinearLayout.LayoutParams(UiKit.dp(ctx, 30f), UiKit.dp(ctx, 30f)))
        card.addView(head)

        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.VERTICAL
        card.addView(body)
        // 展开态存进偏好：用户手动调过就记住，换主题重建 / 下次启动都照此还原。
        val fTitle = title
        val appCtx = ctx.applicationContext
        val open = if (PetPrefs.hasCardOpen(appCtx, fTitle))
            PetPrefs.cardOpen(appCtx, fTitle) else fallbackOpen
        // 【丝滑】初始展开态也走动画，不再瞬间旋转。
        Springs.drive(Springs.snappy(), object : Springs.Listener {
            override fun onUpdate(p: Float) {
                arrow.rotation = Springs.lerp(0f, if (open) 90f else 0f, p)
            }

            override fun onEnd() {
                arrow.rotation = if (open) 90f else 0f
            }
        })
        if (open) {
            body.visibility = View.VISIBLE
        } else {
            // 收起态用高度 0 表示，交给 UiKit.expand 做展开动画（不用 GONE，免得布局跳）。
            body.layoutParams = LinearLayout.LayoutParams(-1, 0)
            body.alpha = 0f
        }
        val fBody = body
        head.setOnClickListener {
            val lp = fBody.layoutParams
            val shown = lp != null && lp.height != 0
            UiKit.expand(fBody, arrow, !shown)
            PetPrefs.setCardOpen(appCtx, fTitle, !shown)
        }
        return card
    }

    /**
     * 【已删·styleFeature(Context, Button)】原把「聊天背景」卡片里两颗大按钮改成白底描边次按钮样式。
     *   本轮两入口已改成 valueRow 行样式（与「主题模式」一致），该方法不再有调用方。
     */
    /** 一行浅灰小注脚。 */
    @JvmStatic
    fun addHint(ctx: Context, dest: LinearLayout, s: String) {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(UiKit.FS_SUB)
        t.setTextColor(UiKit.SUB)
        t.setPadding(0, UiKit.dp(ctx, 6f), 0, UiKit.dp(ctx, 2f))
        dest.addView(t)
    }

    /** 取卡片标题：card = 竖排 LinearLayout，第 0 子是横排 head，head 第 0 子是 TextView。 */
    @JvmStatic
    fun cardTitle(v: View): String? {
        val t = cardTitleView(v) ?: return null
        return UiKit.textOf(t)
    }

    // 改写卡片标题文本（配合 cardTitleView 定位）。
    @JvmStatic
    fun setCardTitle(v: View, title: String?) {
        val t = cardTitleView(v)
        if (t != null && title != null) {
            t.text = title
        }
    }

    // 定位卡片标题 TextView：card 竖排 -> head 横排 -> 第 0 子。
    @JvmStatic
    fun cardTitleView(v: View): TextView? {
        if (v !is LinearLayout) {
            return null
        }
        val g = v
        if (g.orientation != LinearLayout.VERTICAL || g.childCount == 0) {
            return null
        }
        val head = g.getChildAt(0)
        if (head !is LinearLayout) {
            return null
        }
        val h = head
        if (h.orientation != LinearLayout.HORIZONTAL || h.childCount == 0) {
            return null
        }
        val first = h.getChildAt(0)
        return if (first is TextView) first else null
    }

    /** 首页按钮：primary=true 走紫渐变主样式，否则白底描边次样式。 */
    @JvmStatic
    fun mkButton(ctx: Context, text: String, primary: Boolean): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.setTextSize(UiKit.FS_BTN)
        b.typeface = Typeface.DEFAULT_BOLD
        val pad = UiKit.dp(ctx, 14f)
        b.setPadding(pad, pad, pad, pad)
        if (primary) {
            UiKit.primary(b, ctx)
        } else {
            UiKit.secondary(b, ctx)
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(ctx, 10f)
        b.layoutParams = lp
        return b
    }

    /**
     * 一行「减号 / 数值 / 加号」步进器。
     * 两个回调由调用方决定怎么改值与存盘，本方法只管外观与点击。
     */
    @JvmStatic
    fun stepperRow(ctx: Context, name: String, tag: String,
                   onMinus: Runnable, onPlus: Runnable): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = tag
        row.background = UiKit.round(UiKit.SOFT, ctx, 10f)
        val padX = UiKit.dp(ctx, 12f)
        row.setPadding(padX, UiKit.dp(ctx, 10f), padX, UiKit.dp(ctx, 10f))
        val rowLp = LinearLayout.LayoutParams(-1, -2)
        rowLp.topMargin = UiKit.dp(ctx, 8f)
        row.layoutParams = rowLp

        val label = TextView(ctx)
        label.text = name
        label.setTextSize(UiKit.FS_BTN)
        label.setTextColor(UiKit.TITLE)
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))

        val minus = stepButton(ctx, Icons.IC_MINUS)
        minus.tag = tag + "_minus"
        minus.setOnClickListener {
            onMinus.run()
        }
        row.addView(minus)

        val value = TextView(ctx)
        value.text = "50%"
        value.setTextSize(UiKit.FS_BTN)
        value.setTextColor(UiKit.TITLE)
        value.typeface = Typeface.DEFAULT_BOLD
        value.gravity = Gravity.CENTER
        value.tag = tag + "_value"
        value.layoutParams = LinearLayout.LayoutParams(UiKit.dp(ctx, 62f), -2)
        row.addView(value)

        val plus = stepButton(ctx, Icons.IC_PLUS)
        plus.tag = tag + "_plus"
        plus.setOnClickListener {
            onPlus.run()
        }
        row.addView(plus)
        return row
    }

    /** 步进器的圆形按钮：直径 34dp，白底描边 + 描边图标（加/减）。 */
    private fun stepButton(ctx: Context, resId: Int): ImageView {
        val t = Icons.view(ctx, resId, 16.0f, UiKit.TITLE)
        t.isClickable = true
        val size = UiKit.dp(ctx, 34f)
        t.layoutParams = LinearLayout.LayoutParams(size, size)
        t.setPadding(0, 0, 0, 0)
        t.background = UiKit.roundStroke(UiKit.CARD, UiKit.LINE, ctx, 17f)
        UiKit.press(t)
        return t
    }

    /** 更新步进器中间显示的数值文本。 */
    @JvmStatic
    fun setStepperValue(row: View?, text: String?) {
        if (row == null) {
            return
        }
        val v = row.findViewWithTag<View>(row.tag?.toString() + "_value")
        if (v is TextView) {
            v.text = text ?: ""
        }
    }
}
