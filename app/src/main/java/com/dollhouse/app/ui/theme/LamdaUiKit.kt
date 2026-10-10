package com.dollhouse.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 【职责】服务类设置页（lamda 设备控制，以及日后同类页面）共用的 UI 构件层。
 *
 * 【为什么单独一层】要做的是「建立本软件自己的 UI 设计体系」，不是把一页写好看。
 *   操作行 / 能力项 / 接口行这几类结构会被反复使用，收口在这里之后，
 *   页面只负责描述「有什么内容、点了做什么」，不再各自拼 padding 与颜色。
 *
 * 【约定】尺寸、间距、语义色、图标一律走 [Design]；本文件不出现裸色值与随手尺寸。
 *
 * 【禁用观感】不可点不是「只把文字变灰」——那样图标往往还亮着。
 *   统一用 [Design.applyDisabled] 给整行上透明度，三种主题下都不会破。
 */
object LamdaUiKit {

    /** 服务状态行的 View tag。状态行与按钮必须同源刷新，靠它定位。 */
    const val TAG_STATUS = "lamda_status"

    /**
     * 卡片内的分组标题：语义图标 + 降权小标题。
     *
     * 【为什么不用 ApiPageKit.sectionTitle】它不带图标，而本页每一组都需要一枚
     *   统一语义图标来支撑「图标语言」这件事（需求：图标要成体系，不是装饰）。
     */
    fun header(ctx: Context, text: String, iconRes: Int): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(Icons.view(ctx, iconRes, Design.SZ_XS, Design.neutral()))
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = text
        t.setTextSize(UiKit.FS_SUB)
        t.setTextColor(Design.neutral())
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.marginStart = dp(ctx, Design.GAP_ICON / 2)
        r.addView(t, lp)
        return r
    }

    /**
     * 操作行：左图标 + 名称 + 右箭头。
     *
     * @param enabled false = 置灰且不可点（前置条件不满足，或有别的动作在跑）
     * @param danger  true = 危险操作（删除类），用错误色与警示底衬区分
     * @param cb      null = 视为禁用（不设 clickable，也就没有按压反馈）
     */
    fun actionRow(ctx: Context, dest: LinearLayout, name: String, iconRes: Int,
                  enabled: Boolean, danger: Boolean, cb: View.OnClickListener?) {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = UiKit.round(
            if (danger && enabled) UiKit.errBg() else UiKit.SOFT, ctx, 10f)
        r.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        // 【为什么用最小高度而不是固定高度】系统字体放大时要能撑开，
        //   固定高度会把文字裁掉（需求第 23 条：不得文字截断）。
        r.minimumHeight = dp(ctx, Design.H_ROW)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(ctx, Design.GAP_SECTION)
        r.layoutParams = lp

        val fg = if (!enabled) Design.neutral() else if (danger) Design.err() else Design.accent()
        r.addView(Icons.view(ctx, iconRes, Design.SZ_MD, fg))

        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = name
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(if (enabled && danger) Design.err() else UiKit.TITLE)
        val tlp = LinearLayout.LayoutParams(0, -2, 1.0f)
        tlp.marginStart = dp(ctx, Design.GAP_ICON)
        r.addView(t, tlp)

        r.addView(Icons.view(ctx, Design.icons.ENTER, Design.SZ_SM, fg))

        if (cb != null && enabled) {
            r.isClickable = true
            UiKit.press(r)
            r.setOnClickListener(cb)
        } else {
            Design.applyDisabled(r, true)
        }
        dest.addView(r)
    }

    /**
     * 能力项（只读展示）：图标 + 名称 + 一句说明。
     * 与操作行的区别是「不可点、无箭头」，用户一眼能分出「这块是介绍」而不是「要点的」。
     */
    fun capabilityRow(ctx: Context, dest: LinearLayout, name: String, desc: String, iconRes: Int) {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(ctx, 2), dp(ctx, 10), dp(ctx, 2), dp(ctx, 10))
        r.addView(Icons.view(ctx, iconRes, Design.SZ_MD, Design.accent()))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = name
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        col.addView(t)
        val d = TextView(ctx)
        d.typeface = Fonts.ui(ctx)
        d.text = desc
        d.setTextSize(UiKit.FS_TINY)
        d.setTextColor(Design.neutral())
        d.setPadding(0, dp(ctx, 2), 0, 0)
        col.addView(d)

        val lp = LinearLayout.LayoutParams(0, -2, 1.0f)
        lp.marginStart = dp(ctx, Design.GAP_ICON)
        r.addView(col, lp)
        dest.addView(r)
    }

    /**
     * 技术信息行：左标签右取值（等宽字体）。
     *
     * 【为什么用等宽】端口号 / 地址这类内容用比例字体读起来是「一串字符」，
     *   等宽后能一眼看出结构（localhost、65000 分别是什么），也更像技术信息该有的样子。
     */
    fun infoLine(ctx: Context, label: String, value: String): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(ctx, 2), dp(ctx, 10), dp(ctx, 2), dp(ctx, 10))
        val l = TextView(ctx)
        l.text = label
        l.setTextSize(UiKit.FS_SUB)
        l.setTextColor(Design.neutral())
        r.addView(l, LinearLayout.LayoutParams(0, -2, 1.0f))
        val v = TextView(ctx)
        v.text = value
        v.setTextSize(UiKit.FS_TINY)
        v.setTextColor(UiKit.TITLE)
        v.typeface = Typeface.MONOSPACE
        r.addView(v, LinearLayout.LayoutParams(-2, -2))
        return r
    }

    private fun dp(ctx: Context, v: Int): Int = UiKit.dp(ctx, v.toFloat())
}
