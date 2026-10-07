package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 【职责】「提供商」模块各页面共用的小构建件：卡片 / 整宽行 / 备注 / 字段输入框 / 菜单项 / 滚动壳。
 *
 * 【为什么单独一个类】规格书要求单文件 ≤400 行。这些件被四五个页面复用，挂在任何一个页面里
 *        都会把那个文件撑爆，而且改一处漏一处。
 *
 * 【坑】颜色一律走 UiKit 常量，不写裸色值，暗色 / 纯黑主题才能自动跟随。
 */
final class ApiPageKit {

    /** 设置页「提供商」入口行的 tag（沿用旧值，避免别处按 tag 找控件时失效）。 */
    static final String TAG_ENTRY_CFG = "feiyu_entry_cfg";
    /** 设置页「查看 Token」入口行的 tag。 */
    static final String TAG_ENTRY_TOKEN = "feiyu_entry_token";

    private ApiPageKit() {
    }

    /** 设置页用的入口行：整宽行 + 可选说明小字。 */
    static LinearLayout entryRow(Context ctx, String tag, String name, String desc, View.OnClickListener cb) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(tag);
        box.addView(row(ctx, name, "\u203a", cb));
        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(ctx);
            d.setText(desc);
            d.setTextSize(UiKit.FS_TINY);
            d.setTextColor(UiKit.SUB);
            d.setPadding(dp(ctx, 14), dp(ctx, 2), 0, 0);
            box.addView(d);
        }
        return box;
    }

    static int dp(Context ctx, int v) {
        return UiKit.dp(ctx, v);
    }

    /** 向上找宿主 Activity（页面挂在 android.R.id.content 上，必须拿得到 Activity）。 */
    static Activity findActivity(Context ctx) {
        Context c = ctx;
        for (int i = 0; i < 8 && c != null; i++) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            if (c instanceof ContextWrapper) {
                c = ((ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    /** 一张白卡片（自带 10dp 上间距）。 */
    static LinearLayout card(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(UiKit.cardBg(ctx));
        c.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 10);
        c.setLayoutParams(lp);
        return c;
    }

    /** 单字符尖括号 -> 图标资源；其它返回 0（按文字渲染）。 */
    static int arrowRes(String s) {
        if (s == null || s.length() != 1) {
            return 0;
        }
        char c = s.charAt(0);
        if (c == '\u203a') {
            return Icons.IC_CHEVRON_RIGHT;
        }
        if (c == '\u2039') {
            return Icons.IC_CHEVRON_LEFT;
        }
        return 0;
    }

    /** 整宽可点行：左名称 + 右值/箭头。 */
    static LinearLayout row(Context ctx, String name, String right, View.OnClickListener cb) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UiKit.round(UiKit.SOFT, ctx, 10));
        r.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 8);
        r.setLayoutParams(lp);
        TextView label = new TextView(ctx);
        label.setText(name);
        label.setTextSize(UiKit.FS_BTN);
        label.setTextColor(UiKit.TITLE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));
        if (right != null && right.length() > 0) {
            int arrow = arrowRes(right);
            if (arrow != 0) {
                // 单字符尖括号一律换成描边图标，避免字形在各家 ROM 上粗细不一。
                r.addView(Icons.view(ctx, arrow, 18.0f, UiKit.SUB));
            } else {
                TextView v = new TextView(ctx);
                v.setText(right);
                v.setTextSize(UiKit.FS_BTN);
                v.setTextColor(UiKit.SUB);
                v.setTypeface(Typeface.DEFAULT_BOLD);
                r.addView(v, new LinearLayout.LayoutParams(-2, -2));
            }
        }
        if (cb != null) {
            r.setClickable(true);
            UiKit.press(r);
            r.setOnClickListener(cb);
        }
        return r;
    }

    /** 小字备注。 */
    static TextView note(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(UiKit.FS_TINY);
        t.setTextColor(UiKit.SUB);
        t.setLineSpacing(dp(ctx, 3), 1.0f);
        t.setPadding(dp(ctx, 2), dp(ctx, 8), dp(ctx, 2), 0);
        // 【图标语义】备注左侧挂一枚 info 图标：二级页里「说明性文字」与「可操作内容」
        //   在视觉上要能分开，否则满屏都是字、用户分不清哪块是下一步要点的。
        Icons.stateIcon(t, Icons.IC_INFO, UiKit.SUB, 12.0f, 4);
        return t;
    }

    /** 分组小标题（卡片内部用）。 */
    static TextView sectionTitle(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(UiKit.FS_SUB);
        t.setTextColor(UiKit.SUB);
        return t;
    }

    /** 字段名 + 输入框（与设置页 addLabeled 的观感一致）。 */
    static EditText labeledInput(Context ctx, LinearLayout dest, String label, String hint, boolean password) {
        TextView lb = new TextView(ctx);
        lb.setText(label);
        lb.setTextSize(UiKit.FS_SUB);
        lb.setTextColor(UiKit.SUB);
        lb.setPadding(dp(ctx, 2), dp(ctx, 12), 0, dp(ctx, 4));
        dest.addView(lb);

        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextSize(UiKit.FS_BTN);
        e.setSingleLine(true);
        UiKit.field(e, ctx);
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        dest.addView(e);
        return e;
    }

    /** 弹出菜单里的一项（纯文字行）。 */
    static TextView menuItem(Context ctx, String label, View.OnClickListener cb) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        t.setPadding(dp(ctx, 8), dp(ctx, 14), dp(ctx, 8), dp(ctx, 14));
        t.setClickable(true);
        UiKit.press(t);
        t.setOnClickListener(cb);
        return t;
    }

    /** 竖直滚动壳（页面内容区统一用它，背景跟随主题）。 */
    static View scrollWrap(Context ctx, View inner) {
        ScrollView sc = new ScrollView(ctx);
        sc.setBackgroundColor(UiKit.BG);
        sc.addView(inner, new ViewGroup.LayoutParams(-1, -2));
        return sc;
    }

    /** 整页根容器：竖向 + 主题背景色。 */
    static LinearLayout pageRoot(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        // 【状态栏高度不在这里留】pageRoot 内部一律紧跟一个 UiKit.topBar，
        //   让位统一由 topBar 自己完成；这里再加一次会变成双倍留白。
        return root;
    }

    /** 内容区容器：左右 16dp、底部 16dp，白卡片自己带上间距。 */
    static LinearLayout contentHost(Context ctx) {
        final LinearLayout host = new LinearLayout(ctx);
        host.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 16);
        host.setPadding(pad, 0, pad, dp(ctx, 16));
        // 【d17】内容区错峰入场：所有走 contentHost 的二级页自动获得与首页一致的入场手感，
        //   不必每页各写一遍。之所以放在 post 里，是因为此刻调用方还没 addView 完。
        host.post(new Runnable() {
            @Override
            public void run() {
                // 页面可能已经被移除（快速返回），此时不再播动画。
                if (host.getParent() != null) {
                    UiKit.enterList(host, 6);
                }
            }
        });
        return host;
    }
}
