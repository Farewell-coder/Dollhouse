package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 【职责】「关于」独立页：软件介绍 + 关于本软件（作者 / 反馈群 / 版本）。
 *
 * 【入口】设置页「关于」卡片里的入口行 → open()；返回键 → closeIfOpen()。
 *
 * 【交互】与 MemPage / ApiConfigPage 同构：整页代码构建、无 layout，挂在 android.R.id.content
 *         上并打 TAG_PAGE，不新建 Activity，因此主题切换重建后由调用方重新 open。
 *
 * 【坑】① 配色必须全走 UiKit 主题变量（用户硬约束：不能另起一套深色皮肤，要跟全局统一）；
 *       ② 版本号从 PackageManager 现取，不硬编码，避免升级时忘了改；
 *       ③ 反馈群号点击后只做「选中态」不做复制——工程禁用浮层短提示，也不引剪贴板权限。
 */
final class AboutPage {
    private static final String LOG_TAG = "Dollhouse";

    private static final String TAG_PAGE = "feiyu_about_page";

    /** 作者（与参考图一致，用户只要求改软件名与描述）。 */
    private static final String AUTHOR = "aerree";
    /** 反馈群（QQ 群号，与参考图一致）。 */
    private static final String GROUP = "864339949";

    private AboutPage() {
    }

    /** 返回键用：本页开着就关掉并返回 true。 */
    static boolean closeIfOpen(Context ctx) {
        try {
            Activity act = findActivity(ctx);
            if (act == null) {
                return false;
            }
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null) {
                return false;
            }
            View old = content.findViewWithTag(TAG_PAGE);
            if (old == null) {
                return false;
            }
            UiKit.closePage(old);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 设置页「关于」入口行的落地动作。 */
    static void open(Context ctx) {
        try {
            Activity act = findActivity(ctx);
            if (act == null) {
                return;
            }
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            View page = buildPage(act);
            UiKit.openPage(content, page, TAG_PAGE);
        } catch (Throwable ignored) {
            Log.w(LOG_TAG, "ignored", ignored);
        }
    }

    private static Activity findActivity(Context ctx) {
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

    private static View buildPage(final Activity act) {
        Context ctx = act;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(UiKit.BG);
        int pad = dp(ctx, 16);
        box.setPadding(pad, dp(ctx, 10), pad, dp(ctx, 24));

        // 顶栏：统一走 UiKit.topBar（左上返回箭头 + 主标题 + 副标题）
        box.addView(UiKit.topBar(ctx, "关于", "关于本软件", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeIfOpen(act);
            }
        }));

        // ---- 卡片 1：软件介绍 ----
        LinearLayout c1 = card(ctx);
        c1.addView(logoRow(ctx, "Dollhouse"));
        c1.addView(body(ctx, "一个开源改造的桌宠小工具：一只会在屏幕上陪你的人偶，点她说话、拖着走，"
                + "长按打开面板。"));
        c1.addView(body(ctx, "她能记住你们聊过的事，聊长了会把早期内容压成要点，"
                + "把这些要点和你的对话一起带进下一次回复。"
                + "所有对话、记忆与配置都只留在这台设备上，不上传任何服务器。"));
        c1.addView(body(ctx, "玩法很简单：给她配一个模型端点 → 点她说话 → 聊久了自动总结。"
                + "关掉自动总结，她就只保留原文，不会替你压缩。"));
        c1.addView(body(ctx, "对话内容由所选模型生成，仅供娱乐。"));
        box.addView(c1);

        // ---- 卡片 2：关于本软件 ----
        LinearLayout c2 = card(ctx);
        c2.addView(logoRow(ctx, "关于本软件"));
        c2.addView(infoRow(ctx, "作者", AUTHOR, false));
        c2.addView(divider(ctx));
        c2.addView(infoRow(ctx, "反馈群", GROUP, true));
        c2.addView(divider(ctx));
        c2.addView(infoRow(ctx, "版本", "v" + versionName(ctx), false));
        box.addView(c2);

        ScrollView sc = new ScrollView(ctx);
        sc.setBackgroundColor(UiKit.BG);
        sc.addView(box, new ViewGroup.LayoutParams(-1, -2));
        return sc;
    }

    /** 卡片头部：左侧主题色圆角方块 + 右侧标题。 */
    private static LinearLayout logoRow(Context ctx, String title) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(ctx, 12));

        TextView logo = new TextView(ctx);
        logo.setText("◈");
        logo.setTextSize(20.0f);
        logo.setTextColor(UiKit.ACC);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(UiKit.roundStroke(UiKit.CARD, UiKit.ACC, ctx, 12));
        int s = dp(ctx, 40);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(s, s);
        llp.rightMargin = dp(ctx, 12);
        row.addView(logo, llp);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(18.0f);
        t.setTextColor(UiKit.TITLE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1.0f));
        return row;
    }

    /** 一段正文。 */
    private static TextView body(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.SUB);
        t.setLineSpacing(dp(ctx, 4), 1.0f);
        t.setPadding(0, dp(ctx, 5), 0, dp(ctx, 5));
        return t;
    }

    /** 一行「左名称 + 右值」；pill=true 时值做成主题色药丸。 */
    private static LinearLayout infoRow(Context ctx, String name, String value, boolean pill) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));

        TextView n = new TextView(ctx);
        n.setText(name);
        n.setTextSize(UiKit.FS_BTN);
        n.setTextColor(UiKit.TITLE);
        row.addView(n, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView v = pill
                ? UiKit.outlineChip(ctx, value)
                : plainValue(ctx, value);
        row.addView(v, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static TextView plainValue(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.SUB);
        return t;
    }

    private static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(UiKit.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        v.setLayoutParams(lp);
        return v;
    }

    private static String versionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static LinearLayout card(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(UiKit.cardBg(ctx));
        c.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 10);
        c.setLayoutParams(lp);
        return c;
    }

    /** 尺寸换算：统一走 UiKit，避免多处重复实现。 */
    private static int dp(Context ctx, int v) {
        return UiKit.dp(ctx, v);
    }
}