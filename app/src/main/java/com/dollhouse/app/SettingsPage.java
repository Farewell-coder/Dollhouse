package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 【职责】设置页后处理器：界面构建完成后按标题切成可折叠卡片，摘掉已下线的分组。
 * 【入口】MainActivity.onCreate 里 setContentView 之后调用一次 apply(activity)。
 * 【交互】卡片头折叠依赖 UiKit.expand；聊天设置组交给 ApiSectionTuner 整理；
 *         被摘掉的分组只是脱离视图树，字段引用仍然有效，因此每秒刷新的代码不会空指针。
 * 【坑】isKnownTitle 按文本匹配分组标题，主界面里那些标题文案不能改，否则整组退回原样。
 */
public final class SettingsPage {
    private static final String LOG_TAG = "Dollhouse";

    /** 页面尾部「说明：」长文，不参与折叠，保留为页脚。 */
    private static final String FOOTER_PREFIX = "说明：";

    /** 三个输入框的 hint 前缀，用来在视图树里定位它们（hint 后带示例，用前缀匹配）。 */
    public static final String MODEL_HINT = "例如：deepseek";
    public static final String KEY_HINT = "输入 API";
    public static final String URL_HINT = "例如：http";

    /** 挂在「测试连接」结果 TextView 上的标签，用于在视图树里认出它并摆到按钮下方。 */
    public static final String TAG_TEST_RESULT = "feiyu_test_result";

    /** 配置面板提交名字后的回调。 */
    public interface NameCallback {
        void onName(String name);
    }

    private SettingsPage() {
    }

    /** smali 侧唯一入口：在 setContentView 之后调用一次。 */
    public static void apply(Activity activity) {
        try {
            if (activity == null) {
                return;
            }
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            ScrollView scrollView = findScrollView(decor);
            if (scrollView == null || scrollView.getChildCount() == 0) {
                return;
            }
            View child = scrollView.getChildAt(0);
            if (!(child instanceof LinearLayout)) {
                return;
            }
            LinearLayout root = (LinearLayout) child;
            if (root.getOrientation() != LinearLayout.VERTICAL || root.getChildCount() < 5) {
                return;
            }
            build(root, activity);
        } catch (Throwable ignored) {
            Log.w(LOG_TAG, "ignored", ignored);
            // 呈现层改动，任何意外都不能影响设置页原本可用的功能。
        }
    }

    /** 在视图树里深度优先找第一个 ScrollView（页面骨架的标志）。 */
    private static ScrollView findScrollView(View v) {
        if (v == null) {
            return null;
        }
        if (v instanceof ScrollView) {
            return (ScrollView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = findScrollView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // 重排主流程：按标题切组 → 丢弃废弃组 → 其余包成折叠卡片。
    private static void build(LinearLayout box, Context ctx) {
        int n = box.getChildCount();
        List<View> all = new ArrayList<View>(n);
        for (int i = 0; i < n; i++) {
            all.add(box.getChildAt(i));
        }

        int firstTitle = -1;
        for (int i = 0; i < all.size(); i++) {
            if (isKnownTitle(all.get(i))) {
                firstTitle = i;
                break;
            }
        }
        if (firstTitle < 0) {
            return;
        }

        box.removeAllViews();
        for (int i = 0; i < firstTitle; i++) {
            box.addView(all.get(i));
        }

        int i = firstTitle;
        while (i < all.size()) {
            View v = all.get(i);
            if (!isKnownTitle(v)) {
                box.addView(v);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < all.size() && !isKnownTitle(all.get(j)) && !isFooter(all.get(j))) {
                j++;
            }
            String title = textOf(v);
            if (isDropped(title)) {
                // 整组丢弃：只从视图树摘除，不销毁控件
                i = j;
                continue;
            }
            if (isCard(title)) {
                addCard(box, ctx, (TextView) v, all, i + 1, j, title);
            } else {
                box.addView(v);
                for (int k = i + 1; k < j; k++) {
                    box.addView(all.get(k));
                }
            }
            i = j;
        }
    }

    // 判断某个 View 是不是已知的分组标题（查 SettingsRegistry）。
    private static boolean isKnownTitle(View v) {
        String t = textOf(v);
        if (t == null) {
            return false;
        }
        return SettingsRegistry.known(t);
    }

    // 该分组是否要包成折叠卡片。
    private static boolean isCard(String t) {
        return SettingsRegistry.card(t);
    }

    // 该分组是否整组摘除。
    private static boolean isDropped(String t) {
        return SettingsRegistry.dropped(t);
    }

    // 是否是页脚「说明：」长文。
    private static boolean isFooter(View v) {
        String t = textOf(v);
        return t != null && t.startsWith(FOOTER_PREFIX);
    }

    private static String textOf(View v) {
        if (!(v instanceof TextView)) {
            return null;
        }
        CharSequence cs = ((TextView) v).getText();
        return cs == null ? null : cs.toString().trim();
    }

    // 把一个分组包成可折叠卡片（头部可点、主体做高度动画）。
    private static void addCard(LinearLayout box, Context ctx, TextView titleView,
                                List<View> all, int from, int to, String titleText) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.cardBg(ctx));
        int pad = UiKit.dp(ctx, 16);
        card.setPadding(pad, UiKit.dp(ctx, 4), pad, UiKit.dp(ctx, 14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = UiKit.dp(ctx, 10);
        card.setLayoutParams(cardLp);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);
        head.setPadding(0, UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 12));

        titleView.setPadding(0, 0, 0, 0);
        titleView.setTextSize(UiKit.FS_BTN);
        titleView.setTextColor(UiKit.TITLE);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1.0f));

        final TextView arrow = UiKit.arrow(ctx);
        head.addView(arrow, new LinearLayout.LayoutParams(UiKit.dp(ctx, 30), UiKit.dp(ctx, 30)));
        card.addView(head);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        for (int k = from; k < to; k++) {
            body.addView(all.get(k));
        }
        card.addView(body);

        SettingsCard spec = SettingsRegistry.find(titleText);
        if (spec != null) {
            spec.decorate(ctx, body, all, from, to);
        }

        // 展开态优先取用户手动调过的记录；没调过才用注册表里的默认值。
        // 这样换主题重建界面、乃至下次冷启动，卡片都不会自己回到默认。
        final String fTitle = titleText;
        final Context appCtx = ctx.getApplicationContext();
        final boolean open = PetPrefs.hasCardOpen(appCtx, fTitle)
                ? PetPrefs.cardOpen(appCtx, fTitle)
                : (spec != null && spec.openByDefault());
        arrow.setRotation(open ? 90f : 0f);
        if (open) {
            body.setVisibility(View.VISIBLE);
        } else {
            // 收起态用高度 0 表示，交给 UiKit.expand 做展开动画。
            body.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0);
            body.setLayoutParams(blp);
            body.setAlpha(0f);
        }

        final LinearLayout fBody = body;
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ViewGroup.LayoutParams lp = fBody.getLayoutParams();
                boolean shown = lp != null && lp.height != 0;
                UiKit.expand(fBody, arrow, !shown);
                // 记下用户这次的选择，重建 / 重启后照此还原。
                PetPrefs.setCardOpen(appCtx, fTitle, !shown);
            }
        });

        box.addView(card);
        // 换主题重建时跳过入场动画，否则每张卡片会按序号延迟重冒一遍，看着像按钮消失。
        if (!PetPrefs.themeRestore(ctx)) {
            // 【丝滑】错峰序号加上限：卡片多时最末尾不再等太久。
            UiKit.enter(card, Math.min(box.getChildCount(), 8) * 24);
        }
    }

    // 按文本在给定列表里找按钮。
    public static Button findButton(List<View> list, String key) {
        for (int i = 0; i < list.size(); i++) {
            Button b = findButton(list.get(i), key);
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    public static Button findButton(View v, String key) {
        if (v instanceof Button) {
            String s = textOf(v);
            if (s != null && s.contains(key)) {
                return (Button) v;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Button b = findButton(g.getChildAt(i), key);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    /** 由 MainActivity 的 saveSettings 桥接调用：保存后把 prefs 回写进当前配置项。 */
    public static void onSaved(Context ctx) {
        try {
            SettingsProfiles.syncFromPrefs(ctx);
        } catch (Throwable ignored) {
            Log.w(LOG_TAG, "ignored", ignored);
        }
    }

    /** 读输入框的 hint；按 hint 前缀在视图树里定位三个配置输入框时用。 */
    public static String hintOf(View v) {
        if (!(v instanceof EditText)) {
            return null;
        }
        CharSequence cs = ((EditText) v).getHint();
        return cs == null ? null : cs.toString().trim();
    }

    /** 在输入框上方插一行字段名，并把输入框换成淡紫底圆角样式。 */
    public static void addLabeled(Context ctx, LinearLayout body, String label, EditText input) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(UiKit.FS_SUB);
        t.setTextColor(UiKit.SUB);
        t.setPadding(UiKit.dp(ctx, 2), UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 4));

        input.setBackground(UiKit.rowBg(ctx, UiKit.FIELD));
        input.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 12), UiKit.dp(ctx, 12), UiKit.dp(ctx, 12));
        input.setTextSize(UiKit.FS_BTN);
        input.setTextColor(UiKit.TITLE);
        input.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        body.addView(t);
        body.addView(input);
    }

    /** API 卡片底部的「用量统计」入口。 */
    public static View buildTokenEntry(final Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setBackground(UiKit.rowBg(ctx, UiKit.OPTION));
        row.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 12), UiKit.dp(ctx, 12), UiKit.dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 14);
        row.setLayoutParams(lp);

        TextView t = new TextView(ctx);
        t.setText("用量统计");
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView go = new TextView(ctx);
        go.setText("查看 Token 消耗统计　›");
        go.setTextSize(UiKit.FS_SUB);
        go.setTextColor(UiKit.SUB);
        row.addView(go, new LinearLayout.LayoutParams(-2, -2));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TokenStat.open(ctx);
            }
        });
        return row;
    }
}
