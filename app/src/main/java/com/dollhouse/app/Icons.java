package com.dollhouse.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

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
public final class Icons {

    // ---- 导航 ----
    public static final int IC_CHEVRON_RIGHT = R.drawable.ic_chevron_right;
    public static final int IC_CHEVRON_DOWN = R.drawable.ic_chevron_down;
    public static final int IC_CHEVRON_UP = R.drawable.ic_chevron_up;
    public static final int IC_ARROW_LEFT = R.drawable.ic_arrow_left;
    public static final int IC_CHEVRON_LEFT = R.drawable.ic_chevron_left;
    public static final int IC_CLOSE = R.drawable.ic_close;
    public static final int IC_MORE = R.drawable.ic_more;
    public static final int IC_MENU = R.drawable.ic_menu;

    // ---- 操作 ----
    public static final int IC_SEND = R.drawable.ic_send;
    public static final int IC_PLUS = R.drawable.ic_plus;
    public static final int IC_MINUS = R.drawable.ic_minus;
    public static final int IC_TRASH = R.drawable.ic_trash;
    public static final int IC_COPY = R.drawable.ic_copy;
    public static final int IC_EDIT = R.drawable.ic_edit;
    public static final int IC_REFRESH = R.drawable.ic_refresh;
    public static final int IC_SEARCH = R.drawable.ic_search;
    public static final int IC_CHECK = R.drawable.ic_check;
    public static final int IC_EXTERNAL = R.drawable.ic_external;

    // ---- 状态 ----
    public static final int IC_CHECK_CIRCLE = R.drawable.ic_check_circle;
    public static final int IC_X_CIRCLE = R.drawable.ic_x_circle;
    public static final int IC_INFO = R.drawable.ic_info;
    public static final int IC_WARNING = R.drawable.ic_warning;
    /** 收藏星（实心 / 空心两态）。 */
    public static final int IC_STAR = R.drawable.ic_star;
    public static final int IC_STAR_OFF = R.drawable.ic_star_off;
    public static final int IC_SPINNER = R.drawable.ic_spinner;

    // ---- 功能 ----
    public static final int IC_CHAT = R.drawable.ic_chat;
    public static final int IC_BRAIN = R.drawable.ic_brain;
    public static final int IC_IMAGE = R.drawable.ic_image;
    public static final int IC_SETTINGS = R.drawable.ic_settings;
    public static final int IC_CHART = R.drawable.ic_chart;
    public static final int IC_SHIELD = R.drawable.ic_shield;
    public static final int IC_TERMINAL = R.drawable.ic_terminal;
    public static final int IC_WHALE = R.drawable.ic_whale;

    private Icons() {
    }

    /** 取图并按 color 上色。返回的 Drawable 每次都是新实例，可安全用于不同 View。 */
    public static Drawable get(Context ctx, int resId, int color) {
        if (ctx == null) {
            return null;
        }
        Drawable d = null;
        try {
            d = ctx.getResources().getDrawable(resId, ctx.getTheme());
        } catch (Throwable ignored) {
            // 资源缺失时静默返回 null，调用方按「无图」处理，不让 UI 崩。
        }
        if (d != null) {
            d = d.mutate();
            try {
                d.setTint(color);
            } catch (Throwable ignored) {
            }
        }
        return d;
    }

    /** 直接出一个图标 ImageView（sizeDp 为边长，含内边距由调用方自行设）。 */
    public static ImageView view(Context ctx, int resId, float sizeDp, int color) {
        ImageView iv = new ImageView(ctx);
        int size = UiKit.dp(ctx, sizeDp);
        iv.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable d = get(ctx, resId, color);
        if (d != null) {
            iv.setImageDrawable(d);
        }
        iv.setClickable(false);
        iv.setFocusable(false);
        return iv;
    }

    /**
     * 出「图标 + 文字」横排：图标在左，文字在右，居中垂直对齐。
     * 用于原来写成「ⓘ 摘要」「✗ 还没填」这类图标嵌入文字的场合。
     */
    public static LinearLayout labeled(Context ctx, int resId, float sizeDp,
                                       int color, android.view.View label,
                                       int gapDp) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = view(ctx, resId, sizeDp, color);
        row.addView(iv);
        if (label != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = UiKit.dp(ctx, gapDp);
            row.addView(label, lp);
        }
        return row;
    }

    /** 换主题后刷新一个 ImageView 的图标颜色（保持图片资源不变）。 */
    public static void tint(ImageView iv, int color) {
        if (iv == null) {
            return;
        }
        Drawable d = iv.getDrawable();
        if (d != null) {
            try {
                d.mutate().setTint(color);
                iv.invalidate();
            } catch (Throwable ignored) {
            }
        }
    }
}