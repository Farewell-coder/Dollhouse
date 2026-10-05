package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
final class HomeCards {
    /** 悬浮窗权限行的 tag，供 HomeUi.syncPerm 定位该行。 */
    static final String TAG_PERM_OVERLAY = "feiyu_perm_overlay";
    /** Shizuku 授权行的 tag，供 HomeUi.syncPerm 定位该行（四态，文案由 ShizukuBridge 给）。 */
    static final String TAG_PERM_SHIZUKU = "feiyu_perm_shizuku";
    /**
     * 「外观」卡片里两个图片功能入口按钮的 tag。
     * 【为什么必须用 tag 而不是文本】这两个按钮创建在「聊天背景」分组里，SettingsPage.apply
     *   会先把它们包进那张卡片的 body，HomeUi.apply 再按顶层遍历已经找不到；
     *   而且背景按钮的文本要等 onResume 的 refreshLocalUi 才写入，创建时是空串，文本匹配也靠不住。
     */
    static final String TAG_BG_PICK = "feiyu_look_bg_pick";
    static final String TAG_BG_CLEAR = "feiyu_look_bg_clear";
    /** 权限行右侧状态文案。 */
    static final String S_OK = "\u5df2\u6388\u6743";
    static final String S_NO = "\u672a\u6388\u6743";

    /** 顶部「← 设置」一行：箭头可点，等价于返回首页。 */
    // 顶部标题行：统一走 UiKit.topBar，返回键点击等价于返回首页。
    static LinearLayout buildHeader(final Activity activity, Context ctx) {
        LinearLayout row = UiKit.topBar(ctx, "设置", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HomeUi.show(activity, true);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 10);
        lp.bottomMargin = UiKit.dp(ctx, 2);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 权限卡片内的一行开关子项：左名称 + 右开关，样式与 permRow 的权限行对齐。
     * 点整行或点开关都能切换。
     */
    static LinearLayout switchRow(Context ctx, String name, final UiKit.Switch sw) {
        return switchRow(ctx, name, sw, new OnChanged() {
            @Override
            public void onChanged(boolean on, Context c) {
                HomeUi.onHideRecentsChanged(on, c);
            }
        });
    }
    /** 开关行变化回调（主题开关这类不走 HomeUi 固定分发的场景用）。 */
    interface OnChanged {
        void onChanged(boolean on, Context ctx);
    }
    /** 同 switchRow，但开关变化后的动作由调用方指定。 */
    static LinearLayout switchRow(Context ctx, String name, final UiKit.Switch sw,
                                  final OnChanged cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.round(UiKit.SOFT, ctx, 10));
        int padH = UiKit.dp(ctx, 12);
        row.setPadding(padH, UiKit.dp(ctx, 10), padH, UiKit.dp(ctx, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 8);
        row.setLayoutParams(lp);
        TextView label = new TextView(ctx);
        label.setText(name);
        label.setTextSize(UiKit.FS_BTN);
        label.setTextColor(UiKit.TITLE);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        row.setClickable(true);
        UiKit.press(row);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setOn(!sw.isOn(), true);
                if (cb != null) {
                    cb.onChanged(sw.isOn(), v.getContext());
                }
            }
        });
        return row;
    }

    /**
     * 一行权限子项：左侧名称（常规字重）+ 右侧状态（加粗，颜色随授权状态变）。
     * 右侧文本与是否可点由 bindPermRow 在 syncPerm 里按实时状态绑定；
     * 行的点击动作在创建时就挂在行上，syncPerm 通过 setClickable 开关，不反复换监听器。
     */
    static LinearLayout permRow(Context ctx, String name, String tag) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(tag);
        row.setBackground(UiKit.round(UiKit.SOFT, ctx, 10));
        UiKit.press(row);
        int padX = UiKit.dp(ctx, 12);
        row.setPadding(padX, UiKit.dp(ctx, 12), padX, UiKit.dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 8);
        row.setLayoutParams(lp);

        TextView label = new TextView(ctx);
        label.setText(name);
        label.setTextSize(UiKit.FS_BTN);
        label.setTextColor(UiKit.TITLE);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView state = new TextView(ctx);
        state.setText(S_NO);
        state.setTextSize(UiKit.FS_BTN);
        state.setTextColor(UiKit.ERR);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(state, new LinearLayout.LayoutParams(-2, -2));
        // 点击动作统一交回 HomeUi 按 tag 分发，本类不认具体权限。
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object t = v.getTag();
                HomeUi.permClicked(t == null ? null : t.toString(), v.getContext());
            }
        });
        return row;
    }

    /**
     * 保活引导行：左侧名称 + 右侧「去设置 ›」。
     * 用于系统私有开关（自启动 / 后台活动 / 后台弹出界面）——这类项查不到授权状态，
     * 只能引导用户手动去系统设置里开，因此右侧固定显示入口箭头而非状态。
     * 行的点击动作同样交回 HomeUi.guideClicked 按 tag 分发。
     */
    static LinearLayout guideRow(Context ctx, String name, String tag) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(tag);
        row.setBackground(UiKit.round(UiKit.SOFT, ctx, 10));
        UiKit.press(row);
        int padX = UiKit.dp(ctx, 12);
        row.setPadding(padX, UiKit.dp(ctx, 12), padX, UiKit.dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 8);
        row.setLayoutParams(lp);
        TextView label = new TextView(ctx);
        label.setText(name);
        label.setTextSize(UiKit.FS_BTN);
        label.setTextColor(UiKit.TITLE);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView go = new TextView(ctx);
        go.setText("\u53bb\u8bbe\u7f6e");
        go.setTextSize(UiKit.FS_BTN);
        go.setTextColor(UiKit.SUB);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(Icons.labeled(ctx, Icons.IC_CHEVRON_RIGHT, 14.0f, UiKit.SUB, go, 2),
                new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object t = v.getTag();
                HomeUi.guideClicked(t == null ? null : t.toString(), v.getContext());
            }
        });
        return row;
    }

    /**
     * 一行可点的普通子项：左侧名称 + 右侧当前值文本（带「›」提示可点）。
     * 用于「主题模式」这类点了弹选择面板的行；状态文本由调用方通过返回值里的 tag 更新。
     */
    static LinearLayout valueRow(Context ctx, String name, String tag) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(tag);
        row.setBackground(UiKit.round(UiKit.SOFT, ctx, 10));
        UiKit.press(row);
        int padX = UiKit.dp(ctx, 12);
        row.setPadding(padX, UiKit.dp(ctx, 12), padX, UiKit.dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 8);
        row.setLayoutParams(lp);
        TextView label = new TextView(ctx);
        label.setText(name);
        label.setTextSize(UiKit.FS_BTN);
        label.setTextColor(UiKit.TITLE);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView state = new TextView(ctx);
        state.setText("");
        state.setTextSize(UiKit.FS_BTN);
        state.setTextColor(UiKit.SUB);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(state, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }
    /** 更新 valueRow 的右侧状态文本。 */
    static void setRowValue(View row, String text) {
        if (!(row instanceof LinearLayout)) {
            return;
        }
        LinearLayout r = (LinearLayout) row;
        if (r.getChildCount() >= 2 && r.getChildAt(1) instanceof TextView) {
            TextView tv = (TextView) r.getChildAt(1);
            tv.setText(text == null ? "" : text);
            // 【丝滑】状态值刷新时淡入一下，避免"秒变"的突兀。
            tv.setAlpha(0.35f);
            tv.animate().alpha(1f).setDuration(UiKit.D_MICRO).setInterpolator(UiKit.EASE_STD).start();
        }
    }
    /**
     * 造一张可展开的卡片：头部 = 标题（左）+ 箭头（右），body 为 card 的第 1 个子。
     */
    static LinearLayout buildCard(Context ctx, String title) {
        return buildCard(ctx, title, false);
    }

    /**
     * 【v2.10.0】带默认展开态的版本。
     * 收起态是把 body 高度压成 0（而非 GONE），所以卡里的入口按钮在收起时点不到；
     * 「关于」卡片整个内容就是一行入口，必须默认展开，否则入口等于不存在。
     * 用户手动调过之后以用户的选择为准（走 hasCardOpen 判定，不用注册表兜底覆盖）。
     */
    static LinearLayout buildCard(Context ctx, String title, boolean fallbackOpen) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        UiKit.card(card, ctx);
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

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1.0f));

        final ImageView arrow = UiKit.arrow(ctx);
        head.addView(arrow, new LinearLayout.LayoutParams(UiKit.dp(ctx, 30), UiKit.dp(ctx, 30)));
        card.addView(head);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        card.addView(body);
        // 展开态存进偏好：用户手动调过就记住，换主题重建 / 下次启动都照此还原。
        final String fTitle = title;
        final Context appCtx = ctx.getApplicationContext();
        final boolean open = PetPrefs.hasCardOpen(appCtx, fTitle)
                ? PetPrefs.cardOpen(appCtx, fTitle) : fallbackOpen;
        // 【丝滑】初始展开态也走动画，不再瞬间旋转。
        Springs.drive(Springs.snappy(), new Springs.Listener() {
            @Override
            public void onUpdate(float p) {
                arrow.setRotation(Springs.lerp(0f, open ? 90f : 0f, p));
            }

            @Override
            public void onEnd() {
                arrow.setRotation(open ? 90f : 0f);
            }
        });
        if (open) {
            body.setVisibility(View.VISIBLE);
        } else {
            // 收起态用高度 0 表示，交给 UiKit.expand 做展开动画（不用 GONE，免得布局跳）。
            body.setLayoutParams(new LinearLayout.LayoutParams(-1, 0));
            body.setAlpha(0f);
        }
        final LinearLayout fBody = body;
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ViewGroup.LayoutParams lp = fBody.getLayoutParams();
                boolean shown = lp != null && lp.height != 0;
                UiKit.expand(fBody, arrow, !shown);
                PetPrefs.setCardOpen(appCtx, fTitle, !shown);
            }
        });
        return card;
    }

    /** 把原按钮统一成「外观」里的功能入口样式（白底描边次按钮）。 */
    static void styleFeature(Context ctx, Button b) {
        if (b == null) {
            return;
        }
        b.setAllCaps(false);
        b.setTextSize(UiKit.FS_BTN);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = UiKit.dp(ctx, 14);
        b.setPadding(pad, pad, pad, pad);
        UiKit.secondary(b, ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 10);
        b.setLayoutParams(lp);
    }

    /** 一行浅灰小注脚。 */
    static void addHint(Context ctx, LinearLayout dest, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(UiKit.FS_SUB);
        t.setTextColor(UiKit.SUB);
        t.setPadding(0, UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 2));
        dest.addView(t);
    }

    /** 取卡片标题：card = 竖排 LinearLayout，第 0 子是横排 head，head 第 0 子是 TextView。 */
    static String cardTitle(View v) {
        TextView t = cardTitleView(v);
        return t == null ? null : UiKit.textOf(t);
    }

    // 改写卡片标题文本（配合 cardTitleView 定位）。
    static void setCardTitle(View v, String title) {
        TextView t = cardTitleView(v);
        if (t != null && title != null) {
            t.setText(title);
        }
    }

    // 定位卡片标题 TextView：card 竖排 -> head 横排 -> 第 0 子。
    static TextView cardTitleView(View v) {
        if (!(v instanceof LinearLayout)) {
            return null;
        }
        LinearLayout g = (LinearLayout) v;
        if (g.getOrientation() != LinearLayout.VERTICAL || g.getChildCount() == 0) {
            return null;
        }
        View head = g.getChildAt(0);
        if (!(head instanceof LinearLayout)) {
            return null;
        }
        LinearLayout h = (LinearLayout) head;
        if (h.getOrientation() != LinearLayout.HORIZONTAL || h.getChildCount() == 0) {
            return null;
        }
        View first = h.getChildAt(0);
        return first instanceof TextView ? (TextView) first : null;
    }

    /** 首页按钮：primary=true 走紫渐变主样式，否则白底描边次样式。 */
    static Button mkButton(Context ctx, String text, boolean primary) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(UiKit.FS_BTN);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = UiKit.dp(ctx, 14);
        b.setPadding(pad, pad, pad, pad);
        if (primary) {
            UiKit.primary(b, ctx);
        } else {
            UiKit.secondary(b, ctx);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = UiKit.dp(ctx, 10);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * 一行「减号 / 数值 / 加号」步进器。
     * 两个回调由调用方决定怎么改值与存盘，本方法只管外观与点击。
     */
    static LinearLayout stepperRow(Context ctx, String name, String tag,
            final Runnable onMinus, final Runnable onPlus) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(tag);
        row.setBackground(UiKit.round(UiKit.SOFT, ctx, 10));
        int padX = UiKit.dp(ctx, 12);
        row.setPadding(padX, UiKit.dp(ctx, 10), padX, UiKit.dp(ctx, 10));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = UiKit.dp(ctx, 8);
        row.setLayoutParams(rowLp);

        TextView label = new TextView(ctx);
        label.setText(name);
        label.setTextSize(UiKit.FS_BTN);
        label.setTextColor(UiKit.TITLE);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));

        ImageView minus = stepButton(ctx, Icons.IC_MINUS);
        minus.setTag(tag + "_minus");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onMinus.run();
            }
        });
        row.addView(minus);

        TextView value = new TextView(ctx);
        value.setText("50%");
        value.setTextSize(UiKit.FS_BTN);
        value.setTextColor(UiKit.TITLE);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.CENTER);
        value.setTag(tag + "_value");
        value.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(ctx, 62), -2));
        row.addView(value);

        ImageView plus = stepButton(ctx, Icons.IC_PLUS);
        plus.setTag(tag + "_plus");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPlus.run();
            }
        });
        row.addView(plus);
        return row;
    }

    /** 步进器的圆形按钮：直径 34dp，白底描边 + 描边图标（加/减）。 */
    private static ImageView stepButton(Context ctx, int resId) {
        ImageView t = Icons.view(ctx, resId, 16.0f, UiKit.TITLE);
        t.setClickable(true);
        int size = UiKit.dp(ctx, 34);
        t.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        t.setPadding(0, 0, 0, 0);
        t.setBackground(UiKit.roundStroke(UiKit.CARD, UiKit.LINE, ctx, 17));
        UiKit.press(t);
        return t;
    }

    /** 更新步进器中间显示的数值文本。 */
    static void setStepperValue(View row, String text) {
        if (row == null) {
            return;
        }
        View v = row.findViewWithTag(row.getTag() + "_value");
        if (v instanceof TextView) {
            ((TextView) v).setText(text == null ? "" : text);
        }
    }
}
