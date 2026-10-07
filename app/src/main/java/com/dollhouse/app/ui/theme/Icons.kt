package com.dollhouse.app.ui.theme

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.R

/**
 * 图标工厂。
 *
 * 【风格】描边图标（24dp 视口 / 线宽 2 / 圆角端点），路径参照 Lucide（ISC 协议）。
 *   资源位于 res/drawable/ic_*.xml，全部只写白色占位色，真色由本类在运行时上色。
 *
 * 【为什么必须运行时上色】主题有浅色 / 深色 / 莫奈三态，图标颜色要跟着
 *   UiKit.TITLE / TITLE2 / ACC 走。VectorDrawable 的 setTint 是 API 21+，
 *   工程 minSdk 24，无兼容性顾虑。
 *
 * 【收口】所有资源 id 以 IC_* 常量暴露，替换点只改一行。
 */
object Icons {

    // ---- 导航 ----
    @JvmField
    val IC_CHEVRON_RIGHT = R.drawable.ic_chevron_right
    @JvmField
    val IC_CHEVRON_DOWN = R.drawable.ic_chevron_down
    @JvmField
    val IC_CHEVRON_UP = R.drawable.ic_chevron_up
    @JvmField
    val IC_ARROW_LEFT = R.drawable.ic_arrow_left
    @JvmField
    val IC_CHEVRON_LEFT = R.drawable.ic_chevron_left
    @JvmField
    val IC_CLOSE = R.drawable.ic_close
    @JvmField
    val IC_MORE = R.drawable.ic_more
    @JvmField
    val IC_MENU = R.drawable.ic_menu

    // ---- 操作 ----
    @JvmField
    val IC_SEND = R.drawable.ic_send
    @JvmField
    val IC_PLUS = R.drawable.ic_plus
    @JvmField
    val IC_MINUS = R.drawable.ic_minus
    @JvmField
    val IC_CHECK = R.drawable.ic_check

    @JvmField
    val IC_INFO = R.drawable.ic_info

    @JvmField
    val IC_BRAIN = R.drawable.ic_brain
    @JvmField
    val IC_IMAGE = R.drawable.ic_image
    @JvmField
    val IC_SETTINGS = R.drawable.ic_settings
    @JvmField
    val IC_CHART = R.drawable.ic_chart
    @JvmField
    val IC_WHALE = R.drawable.ic_whale
    /** 提供商模块用到的几个图标（资源早就在，只是原先没暴露成常量）。 */
    @JvmField
    val IC_SEARCH = R.drawable.ic_search
    @JvmField
    val IC_EDIT = R.drawable.ic_edit
    @JvmField
    val IC_TRASH = R.drawable.ic_trash
    @JvmField
    val IC_COPY = R.drawable.ic_copy
    @JvmField
    val IC_REFRESH = R.drawable.ic_refresh
    @JvmField
    val IC_EXTERNAL = R.drawable.ic_external
    /** 模型集合 / 分层语义（Lucide layers）：详情页「模型」tab 与模型列表用。 */
    @JvmField
    val IC_LAYERS = R.drawable.ic_layers

    // ---- 【补全】资源早就在 res/drawable/，原先只以 R.drawable.* 直接引用，
    //      没有收口成常量。统一暴露后，调用点只认 Icons.IC_*，换图标只改一行。----
    @JvmField
    val IC_CHAT = R.drawable.ic_chat
    @JvmField
    val IC_CHECK_CIRCLE = R.drawable.ic_check_circle
    @JvmField
    val IC_X_CIRCLE = R.drawable.ic_x_circle
    @JvmField
    val IC_SHIELD = R.drawable.ic_shield
    @JvmField
    val IC_WARNING = R.drawable.ic_warning
    @JvmField
    val IC_TERMINAL = R.drawable.ic_terminal
    @JvmField
    val IC_SPINNER = R.drawable.ic_spinner
    @JvmField
    val IC_STAR = R.drawable.ic_star
    @JvmField
    val IC_STAR_OFF = R.drawable.ic_star_off
    @JvmField
    val IC_TILE_PET = R.drawable.ic_tile_pet

    /** 取图并按 color 上色。返回的 Drawable 每次都是新实例，可安全用于不同 View。 */
    @JvmStatic
    fun get(ctx: Context?, resId: Int, color: Int): Drawable? {
        if (ctx == null) {
            return null
        }
        var d: Drawable? = null
        try {
            d = ctx.resources.getDrawable(resId, ctx.theme)
        } catch (ignored: Throwable) {
            // 资源缺失时静默返回 null，调用方按「无图」处理，不让 UI 崩。
        }
        if (d != null) {
            d = d.mutate()
            try {
                d.setTint(color)
            } catch (ignored: Throwable) {
            }
        }
        return d
    }

    /** 直接出一个图标 ImageView（sizeDp 为边长，含内边距由调用方自行设）。 */
    @JvmStatic
    fun view(ctx: Context, resId: Int, sizeDp: Float, color: Int): ImageView {
        val iv = ImageView(ctx)
        val size = UiKit.dp(ctx, sizeDp)
        iv.layoutParams = ViewGroup.LayoutParams(size, size)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val d = get(ctx, resId, color)
        if (d != null) {
            iv.setImageDrawable(d)
        }
        iv.isClickable = false
        iv.isFocusable = false
        return iv
    }

    /**
     * 出「图标 + 文字」横排：图标在左，文字在右，居中垂直对齐。
     * 用于原来写成「ⓘ 摘要」「✗ 还没填」这类图标嵌入文字的场合。
     */
    @JvmStatic
    fun labeled(ctx: Context, resId: Int, sizeDp: Float,
                color: Int, label: View?,
                gapDp: Int): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val iv = view(ctx, resId, sizeDp, color)
        row.addView(iv)
        if (label != null) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = UiKit.dp(ctx, gapDp)
            row.addView(label, lp)
        }
        return row
    }

    /**
     * 给文字左侧挂一枚语义图标（已授权 ✓ / 未授权 ✗ 这类状态标记）。
     *
     * 【为什么用复合 drawable，而不是再 add 一个 ImageView】
     *   权限行 / 状态行的右侧状态控件是「这一行的第 1 个子 View」——
     *   HomeUi.bindPermRow 与 HomeCards.setRowValue 都按这个下标取字改色。
     *   在文字左边再插一个 ImageView 会让子 View 下标整体错位，所有按位取字的
     *   代码全部失准。复合 drawable 不占子 View 位，索引结构零改动。
     *
     * 【为什么必须先 setBounds】TextView 画复合 drawable 时按 drawable 自身的
     *   intrinsic 尺寸铺；显式 setBounds 才能让 24dp 视口的图标稳定落在 sizeDp 上。
     *
     * @param resId  Icons.IC_* 常量；传 0 表示清除已有图标。
     * @param color  图标色，通常与同行状态文字同色（UiKit.OK / ERR / SUB）。
     * @param sizeDp 图标边长（dp）。
     * @param gapDp  图标与文字的间距（dp）。
     */
    @JvmStatic
    fun stateIcon(tv: TextView?, resId: Int, color: Int, sizeDp: Float, gapDp: Int) {
        if (tv == null) {
            return
        }
        val ctx = tv.context
        if (resId == 0 || ctx == null) {
            tv.setCompoundDrawables(null, null, null, null)
            return
        }
        val d = get(ctx, resId, color)
        if (d == null) {
            tv.setCompoundDrawables(null, null, null, null)
            return
        }
        val size = UiKit.dp(ctx, sizeDp)
        d.setBounds(0, 0, size, size)
        tv.setCompoundDrawablePadding(UiKit.dp(ctx, gapDp))
        tv.setCompoundDrawables(d, null, null, null)
    }

    /** 换主题后刷新一个 ImageView 的图标颜色（保持图片资源不变）。 */
    @JvmStatic
    fun tint(iv: ImageView?, color: Int) {
        if (iv == null) {
            return
        }
        val d = iv.drawable
        if (d != null) {
            try {
                d.mutate().setTint(color)
                iv.invalidate()
            } catch (ignored: Throwable) {
            }
        }
    }
}
