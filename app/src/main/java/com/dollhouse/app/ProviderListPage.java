package com.dollhouse.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * 【职责】供应商列表页（一级页）。
 *
 * 【交互】搜索实时过滤；行内「更多」菜单提供 编辑 / 启用禁用 / 复制 / 拉取模型 / 删除；
 *        右上角「+」新增、导出按钮把全量数据导出成 JSON。
 *
 * 【坑】① 禁用中的供应商行整体降对比度但不隐藏 —— 用户要能看到自己禁用了什么；
 *        ② 删除必须二次确认，且文案要写清会连带删掉几个模型（级联删除不可逆）；
 *        ③ 所有列表操作落盘后立即重建本页，不用缓存行对象。
 */
final class ProviderListPage {

    private ProviderListPage() {
    }

    static View build(final Activity act) {
        final Context ctx = act;
        LinearLayout root = ApiPageKit.pageRoot(ctx);
        final List<Provider> all = ProviderStore.providers(ctx);

        LinearLayout bar = UiKit.topBar(ctx, "供应商", "共 " + all.size() + " 个供应商",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ProviderNav.handleBack(ctx);
                    }
                });
        ImageView export = UiKit.iconView(ctx, Icons.IC_EXTERNAL, UiKit.FS_ICON, UiKit.SUB);
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ExportSheet.show(act);
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ApiPageKit.dp(ctx, UiKit.HIT_DP),
                ApiPageKit.dp(ctx, UiKit.HIT_DP));
        elp.rightMargin = ApiPageKit.dp(ctx, 4);
        bar.addView(export, elp);
        ImageView add = UiKit.iconView(ctx, Icons.IC_PLUS, UiKit.FS_ICON, UiKit.ACC);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProviderNav.openNewProvider(act);
            }
        });
        bar.addView(add, new LinearLayout.LayoutParams(ApiPageKit.dp(ctx, UiKit.HIT_DP),
                ApiPageKit.dp(ctx, UiKit.HIT_DP)));
        root.addView(bar);

        final LinearLayout host = ApiPageKit.contentHost(ctx);
        final EditText search = new EditText(ctx);
        search.setHint("搜索供应商名称");
        search.setSingleLine(true);
        search.setTextSize(UiKit.FS_BTN);
        UiKit.field(search, ctx);
        LinearLayout searchBox = ApiPageKit.contentHost(ctx);
        searchBox.setPadding(ApiPageKit.dp(ctx, 16), 0, ApiPageKit.dp(ctx, 16), 0);
        searchBox.addView(search, new LinearLayout.LayoutParams(-1, -2));
        root.addView(searchBox);

        final LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        host.addView(rows);
        root.addView(ApiPageKit.scrollWrap(ctx, host), new LinearLayout.LayoutParams(-1, 0, 1.0f));
        fill(act, rows, all, "");

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String q = e == null ? "" : e.toString().trim();
                fill(act, rows, all, q);
            }
        });
        return root;
    }

    /** 按关键字重建行列表。 */
    private static void fill(Activity act, LinearLayout rows, List<Provider> all, String q) {
        rows.removeAllViews();
        String key = q == null ? "" : q.toLowerCase();
        int shown = 0;
        for (int i = 0; i < all.size(); i++) {
            final Provider p = all.get(i);
            if (key.length() > 0 && (p.name == null || !p.name.toLowerCase().contains(key))) {
                continue;
            }
            rows.addView(providerRow(act, p));
            shown++;
        }
        if (shown == 0) {
            rows.addView(emptyState(act, all.isEmpty()));
        }
    }

    /** 空状态：没有供应商是引导新增；搜索没命中也给一句可读的说明。 */
    private static View emptyState(final Activity act, boolean noData) {
        Context ctx = act;
        LinearLayout box = ApiPageKit.card(ctx);
        TextView t = new TextView(ctx);
        t.setText(noData ? "还没有配置任何供应商。" : "没有匹配的供应商。");
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        box.addView(t);
        if (noData) {
            TextView b = UiKit.primaryChip(ctx, "添加供应商");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = ApiPageKit.dp(ctx, 12);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ProviderNav.openNewProvider(act);
                }
            });
            box.addView(b, lp);
        }
        return box;
    }

    /** 一行：首字头像 + 名称 + 状态/模型数徽标 + 更多菜单。 */
    private static LinearLayout providerRow(final Activity act, final Provider p) {
        final Context ctx = act;
        final int count = ModelStore.modelsOf(ctx, p.id).size();
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UiKit.cardBg(ctx));
        r.setPadding(ApiPageKit.dp(ctx, 12), ApiPageKit.dp(ctx, 12),
                ApiPageKit.dp(ctx, 12), ApiPageKit.dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ApiPageKit.dp(ctx, 8);
        r.setLayoutParams(lp);
        // 禁用态整体降对比度（不隐藏）。
        r.setAlpha(p.enabled ? 1.0f : 0.55f);

        TextView avatar = new TextView(ctx);
        avatar.setText(p.name == null || p.name.isEmpty() ? "?" : p.name.substring(0, 1));
        avatar.setTextSize(UiKit.FS_BTN);
        avatar.setTextColor(UiKit.ACC);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(UiKit.round(UiKit.FIELD, ctx, 999));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ApiPageKit.dp(ctx, 38),
                ApiPageKit.dp(ctx, 38));
        alp.rightMargin = ApiPageKit.dp(ctx, 12);
        r.addView(avatar, alp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(ctx);
        nm.setText(p.name);
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
        tags.addView(UiKit.badge(ctx, p.enabled ? "启用" : "禁用",
                p.enabled ? UiKit.OK : UiKit.ERR,
                // 【状态色】底色由写死的 0x1A1B8A3A / 0x1AB3261E 改成派生方法，
                //   切主题 / 莫奈时会跟着 OK / ERR 一起变，不再固定绿红。
                p.enabled ? UiKit.okBg() : UiKit.errBg()));
        TextView cnt = UiKit.badge(ctx, count + " 个模型", UiKit.CHAT_CHIP_FG, UiKit.CHAT_CHIP_BG);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = ApiPageKit.dp(ctx, 6);
        tags.addView(cnt, clp);
        col.addView(tags);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));

        ImageView more = UiKit.iconView(ctx, Icons.IC_MORE, UiKit.FS_ICON, UiKit.SUB);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                menu(act, p);
            }
        });
        r.addView(more, new LinearLayout.LayoutParams(ApiPageKit.dp(ctx, 36), ApiPageKit.dp(ctx, 36)));
        r.setClickable(true);
        UiKit.press(r);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProviderNav.openDetail(act, p.id);
            }
        });
        return r;
    }

    /** 「更多」菜单：只留 重命名 / 删除 两项（编辑改走点卡片进详情页）。 */
    private static void menu(final Activity act, final Provider p) {
        final Context ctx = act;
        final Dialog[] dh = new Dialog[1];
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ApiPageKit.menuItem(ctx, "重命名", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss(dh);
                rename(act, p);
            }
        }));
        col.addView(ApiPageKit.menuItem(ctx, "删除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss(dh);
                confirmDelete(act, p);
            }
        }));
        dh[0] = UiKit.showDialog(act, p.name, col, null, null, null, null);
    }

    private static void dismiss(Dialog[] dh) {
        if (dh != null && dh[0] != null) {
            dh[0].dismiss();
        }
    }

    /**
     * 重命名。
     * 【为什么用 showDialog 里的输入框】全工程弹层统一走 UiKit.showDialog，弹系统原生
     *   输入框会在三家主题下各长一个样。
     * 【校验】空名与重名都拦下。showDialog 的行为是「点按钮先关框再回调」，
     *   校验失败时原框已经关了，所以只能再弹一个提示框说明原因。
     */
    private static void rename(final Activity act, final Provider p) {
        final Context ctx = act;
        final EditText input = new EditText(ctx);
        input.setText(p.name == null ? "" : p.name);
        input.setSingleLine(true);
        input.setTextSize(UiKit.FS_BTN);
        UiKit.field(input, ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        UiKit.showDialog(act, "重命名供应商", box, "保存", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = input.getText() == null ? "" : input.getText().toString().trim();
                if (name.length() == 0) {
                    warn(act, "名称不能为空");
                    return;
                }
                if (name.equals(p.name)) {
                    return;
                }
                if (ModelRules.nameExists(ProviderStore.providers(act), name, p.id)) {
                    warn(act, "已有同名供应商，换一个名字");
                    return;
                }
                p.name = name;
                ProviderStore.saveProvider(act, p);
                ProviderNav.refresh(act);
            }
        }, "取消", null);
    }

    /** 校验失败的二次提示（原输入框已关，只能新开一个）。 */
    private static void warn(Activity act, String msg) {
        UiKit.showDialog(act, "无法重命名", UiKit.dialogMessage(act, msg),
                "知道了", null, null, null);
    }

    /** 删除二次确认：文案里写明会连带删掉几个模型。 */
    private static void confirmDelete(final Activity act, final Provider p) {
        int count = ModelStore.modelsOf(act, p.id).size();
        String msg = "「" + p.name + "」将被删除，它名下的 " + count + " 个模型会一并删除，删除后无法恢复。";
        UiKit.showDialog(act, "删除供应商", UiKit.dialogMessage(act, msg),
                "删除", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ProviderStore.deleteProvider(act, p.id);
                        ProviderNav.refresh(act);
                    }
                }, "取消", null);
    }
}