package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * 【职责】供应商详情页的「模型」tab：列出本供应商**已经添加**的模型。
 *
 * 【与「可用模型清单」页的分工】本页只管 ModelStore 里真实存在的记录；
 *        联网拉清单、把名字变成记录的那件事归 ModelListPage（「添加模型」按钮的落点）。
 *        两者不重叠，各自单一职责。
 *
 * 【交互】点一行 → 模型编辑页（改名称 / 类型 / 模态 / 能力开关）；
 *        左滑该行 → 露出垃圾桶 → 点它删除（二次确认）。
 *
 * 【为什么删除走左滑而不是行内常驻按钮】行内按钮会让每一行看起来都是危险操作；
 *        左滑是明确的意图表达，平时不留视觉噪音。
 *
 * 【坑】① 悬浮的「添加模型」按钮会盖住列表最后一行，所以列表底部必须留白；
 *        ② 顶栏的「共 N 个模型」是 topBar 内部创建的控件，只能按结构取回引用；
 *        ③ 删除后整页重建（ProviderNav.refresh），行数与顶栏计数一起刷新，不需要局部打补丁。
 */
final class ProviderModelsPage {

    private ProviderModelsPage() {
    }

    static View build(final Activity act, String providerId) {
        final Context ctx = act;
        final Provider pv = ProviderStore.findProvider(ctx, providerId);
        final FrameLayout root = new FrameLayout(ctx);
        if (pv == null) {
            LinearLayout miss = ApiPageKit.pageRoot(ctx);
            miss.addView(UiKit.topBar(ctx, "模型", "供应商已不存在", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ProviderNav.back(act);
                }
            }));
            miss.addView(ApiPageKit.note(ctx, "这个供应商已经被删除了。"));
            root.addView(miss, new FrameLayout.LayoutParams(-1, -1));
            return root;
        }

        LinearLayout col = ApiPageKit.pageRoot(ctx);
        col.addView(UiKit.topBar(ctx, pv.name, countText(ctx, pv.id), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProviderNav.back(act);
            }
        }));

        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout host = new LinearLayout(ctx);
        host.setOrientation(LinearLayout.VERTICAL);
        int pad = ApiPageKit.dp(ctx, 16);
        host.setPadding(pad, 0, pad, 0);
        host.addView(rows);
        // 底部留白：悬浮按钮高 44dp + 与它上下各 12dp 呼吸。
        host.addView(new View(ctx), new LinearLayout.LayoutParams(-1, ApiPageKit.dp(ctx, 68)));
        col.addView(ApiPageKit.scrollWrap(ctx, host), new LinearLayout.LayoutParams(-1, 0, 1.0f));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        fill(act, rows, pv);
        root.addView(addButton(act, pv), addLp(ctx));
        return root;
    }

    /** 顶栏副标题文案。 */
    private static String countText(Context ctx, String providerId) {
        return "共 " + ModelStore.modelsOf(ctx, providerId).size() + " 个模型";
    }

    /** 按当前数据重建行列表。 */
    private static void fill(final Activity act, LinearLayout rows, final Provider pv) {
        rows.removeAllViews();
        List<AiModel> list = ModelStore.modelsOf(act, pv.id);
        if (list.isEmpty()) {
            rows.addView(empty(act));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final AiModel m = list.get(i);
            SwipeRow row = new SwipeRow(act, modelCard(act, m), new Runnable() {
                @Override
                public void run() {
                    ProviderNav.openModel(act, m.id);
                }
            }, new Runnable() {
                @Override
                public void run() {
                    confirmDelete(act, m);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = ApiPageKit.dp(act, 8);
            row.setLayoutParams(lp);
            rows.addView(row);
        }
    }

    /** 空状态：还没添加任何模型。 */
    private static View empty(final Activity act) {
        Context ctx = act;
        LinearLayout box = ApiPageKit.card(ctx);
        TextView t = new TextView(ctx);
        t.setText("这个供应商下还没有模型。");
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        box.addView(t);
        box.addView(ApiPageKit.note(ctx, "点下面的「添加模型」从供应商拉取清单，选中的模型会出现在这里，"
                + "也会出现在聊天页的模型选择器里。"));
        return box;
    }

    /** 一行模型卡片（不含左滑包装）。 */
    private static LinearLayout modelCard(Context ctx, AiModel m) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UiKit.cardBg(ctx));
        r.setPadding(ApiPageKit.dp(ctx, 14), ApiPageKit.dp(ctx, 12),
                ApiPageKit.dp(ctx, 14), ApiPageKit.dp(ctx, 12));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(ctx);
        nm.setText(m.displayName);
        nm.setTextSize(UiKit.FS_BTN);
        nm.setTextColor(UiKit.TITLE);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm);

        LinearLayout tags = new LinearLayout(ctx);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        tags.setPadding(0, ApiPageKit.dp(ctx, 6), 0, 0);
        tags.addView(UiKit.badge(ctx, AiModel.kindLabel(m.kind),
                UiKit.CHAT_CHIP_FG, UiKit.CHAT_CHIP_BG));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = ApiPageKit.dp(ctx, 6);
        tags.addView(UiKit.badge(ctx, m.enabled ? "启用" : "禁用",
                m.enabled ? UiKit.OK : UiKit.ERR,
                // 【状态色】底色同上，由写死值改成派生方法。
                m.enabled ? UiKit.okBg() : UiKit.errBg()), blp);
        col.addView(tags);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));
        // 右侧箭头提示「点进去能编辑」，与其它列表页的语义保持一致。
        r.addView(Icons.view(ctx, Icons.IC_CHEVRON_RIGHT, 18.0f, UiKit.SUB));
        return r;
    }

    /** 删除二次确认：写明是哪个模型、不可恢复。 */
    private static void confirmDelete(final Activity act, final AiModel m) {
        UiKit.showDialog(act, "删除模型",
                UiKit.dialogMessage(act, "「" + m.displayName + "」将从本供应商删除，删除后无法恢复。"),
                "删除", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ModelStore.deleteModel(act, m.id);
                        ProviderNav.refresh(act);
                    }
                }, "取消", null);
    }

    /** 悬浮的「添加模型」胶囊：落在底栏正上方居中。 */
    private static View addButton(final Activity act, final Provider pv) {
        final Context ctx = act;
        LinearLayout btn = new LinearLayout(ctx);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setBackground(UiKit.round(UiKit.ACC, ctx, 999));
        btn.setElevation(ApiPageKit.dp(ctx, 8));
        btn.setPadding(ApiPageKit.dp(ctx, 20), ApiPageKit.dp(ctx, 12),
                ApiPageKit.dp(ctx, 22), ApiPageKit.dp(ctx, 12));
        btn.addView(Icons.view(ctx, Icons.IC_PLUS, 18.0f, UiKit.ON_ACC));
        TextView t = new TextView(ctx);
        t.setText("添加模型");
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.ON_ACC);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = ApiPageKit.dp(ctx, 8);
        btn.addView(t, tlp);
        btn.setClickable(true);
        UiKit.press(btn);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProviderNav.openModels(act, pv.id);
            }
        });
        return btn;
    }

    private static FrameLayout.LayoutParams addLp(Context ctx) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        // 【坑·不能自留底距】本页被塞进详情页的 slot，slot 已经按 RESERVE_DP 垫了底。
        //   这里再加一段底距，按钮就会被推到屏幕中部去（两段间距叠加）。
        lp.bottomMargin = 0;
        return lp;
    }
}