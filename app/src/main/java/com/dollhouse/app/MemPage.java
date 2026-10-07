package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】总记忆库的独立页：列出 AI 自主（或用户要求）写下的长期记忆条目。
 *
 * 【入口】设置页「记忆」卡片里的「记忆库」按钮 → open()。
 *
 * 【交互】数据来自 MemDb（偏好键 memdb_list），与 TokenStat 一样整页代码构建、无 layout。
 *
 * 【踩坑】页面靠 tag 认领，任何 Context 都能调 closeIfOpen()；找不到 Activity 就静默返回
 *         （悬浮窗场景没有 Activity，所以入口只放在设置页里）。
 */
final class MemPage {
    private static final String LOG_TAG = "Dollhouse";

    private static final String TAG_PAGE = "feiyu_mem_page";

    private MemPage() {
    }

    /** 返回键用：记忆库页开着就关掉并返回 true。 */
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

    /** 设置页「记忆库」按钮的落地动作。 */
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
            View page = buildPage(act, content);
            UiKit.openPage(content, page, TAG_PAGE);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
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

    private static View buildPage(final Activity act, final ViewGroup content) {
        Context ctx = act;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(UiKit.BG);
        int pad = dp(ctx, 16);
        // 【顶部不再留白】同 TokenStat：紧随其后的 UiKit.topBar 已自带 statusBarPad，
        //   页盒顶部再留 10dp 就是二次叠加。顶部归零。
        box.setPadding(pad, 0, pad, dp(ctx, 20));

        // 顶栏：统一走 UiKit.topBar
        LinearLayout bar = UiKit.topBar(ctx, "记忆库", "她记下的事", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeIfOpen(act);
            }
        });
        box.addView(bar);

        JSONArray arr = MemDb.list(ctx);
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            box.addView(item(ctx, o));
        }
        // 【丝滑】列表项错峰淡入，不再一次性铺满。
        UiKit.staggerCapped(box, 6);
        if (arr.length() == 0) {
            LinearLayout empty = card(ctx);
            TextView e = new TextView(ctx);
            e.setText("\u8fd8\u6ca1\u6709\u8bb0\u4e0b\u4ec0\u4e48");
            e.setTextSize(UiKit.FS_BTN);
            e.setTextColor(UiKit.SUB);
            empty.addView(e);
            box.addView(empty);
        }

        TextView note = new TextView(ctx);
        note.setTextSize(UiKit.FS_TINY);
        note.setTextColor(UiKit.SUB);
        note.setLineSpacing(dp(ctx, 3), 1.0f);
        note.setPadding(dp(ctx, 2), dp(ctx, 14), dp(ctx, 2), 0);
        note.setText("\u5979\u5728\u804a\u5929\u91cc\u89c9\u5f97\u503c\u5f97\u8bb0\u7684\u4e8b\u4f1a\u81ea\u5df1\u5199\u8fdb\u6765\uff0c"
                + "\u4e0b\u6b21\u5f00\u53e3\u524d\u4f1a\u5e26\u4e0a\u8fd9\u4e9b\u5185\u5bb9\u3002\n"
                + "\u5171 " + arr.length() + " \u6761\uff0c\u6700\u591a\u4fdd\u7559 " + MemDb.MAX_ENTRIES + " \u6761\uff0c"
                + "\u8d85\u51fa\u65f6\u4e22\u6700\u65e7\u7684\u3002\u6570\u636e\u53ea\u5728\u8fd9\u53f0\u8bbe\u5907\u4e0a\u3002");
        box.addView(note);

        ScrollView sc = new ScrollView(ctx);
        sc.setBackgroundColor(UiKit.BG);
        sc.addView(box, new ViewGroup.LayoutParams(-1, -2));
        return sc;
    }

    /** 一条记忆：标题 + 时间 + 正文 + 删除。 */
    private static LinearLayout item(final Context ctx, JSONObject o) {
        LinearLayout c = card(ctx);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        String title = o.optString("title", "");
        TextView name = new TextView(ctx);
        name.setText(title.isEmpty() ? "\uff08\u65e0\u6807\u9898\uff09" : title);
        name.setTextSize(UiKit.FS_BTN);
        name.setTextColor(UiKit.TITLE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));

        final String id = o.optString("id", "");
        TextView del = new TextView(ctx);
        del.setText("\u5220\u9664");
        del.setTextSize(UiKit.FS_SUB);
        del.setTextColor(UiKit.ERR);
        del.setTypeface(Typeface.DEFAULT_BOLD);
        int p = dp(ctx, 8);
        del.setPadding(p, dp(ctx, 4), p, dp(ctx, 4));
        del.setClickable(true);
        UiKit.press(del);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MemDb.remove(ctx.getApplicationContext(), id);
                Activity act = findActivity(ctx);
                if (act != null) {
                    closeIfOpen(act);
                    open(act);
                }
            }
        });
        head.addView(del, new LinearLayout.LayoutParams(-2, -2));
        c.addView(head);

        TextView time = new TextView(ctx);
        time.setText(fmtTime(o.optLong("ts", 0L)));
        time.setTextSize(UiKit.FS_TINY);
        time.setTextColor(UiKit.SUB);
        time.setPadding(0, dp(ctx, 2), 0, dp(ctx, 6));
        c.addView(time);

        TextView body = new TextView(ctx);
        body.setText(o.optString("text", ""));
        body.setTextSize(UiKit.FS_BTN);
        body.setTextColor(UiKit.TITLE);
        body.setLineSpacing(dp(ctx, 3), 1.0f);
        c.addView(body);
        return c;
    }

    private static String fmtTime(long ts) {
        if (ts <= 0L) {
            return "";
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ts));
        } catch (Throwable unused) {
            return "";
        }
    }

    private static LinearLayout card(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(UiKit.cardBg(ctx));
        c.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));
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
