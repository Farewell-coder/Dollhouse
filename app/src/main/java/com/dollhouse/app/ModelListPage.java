package com.dollhouse.app;

import android.app.Activity;
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
 * 【职责】可用模型列表页：拉本供应商的模型清单 → 搜索筛选 → 「全选 (N)」批量导入 → 单条添加。
 *
 * 【口径】「全选」只作用于当前筛选结果，被搜索过滤掉的项不动（规格书明确要求）。
 *        已添加的模型不重复添加：「+」换成勾选态且不可点。
 *
 * 【不做什么】本页只管「把服务端返回的名字变成模型记录」，能力字段一律按默认值创建
 *        （文本聊天模型），用户想改去模型编辑页逐个调。
 */
final class ModelListPage {

    private ModelListPage() {
    }

    /** 页面可变状态。 */
    private static final class St {
        Activity act;
        Provider pv;
        LinearLayout rows;
        TextView status;
        TextView selectAll;
        EditText search;
        List<String> loaded;
        List<String> added;
        boolean busy;
    }

    static View build(final Activity act, String providerId) {
        final Context ctx = act;
        final St st = new St();
        st.act = act;
        st.pv = ProviderStore.findProvider(ctx, providerId);
        if (st.pv == null) {
            LinearLayout root = ApiPageKit.pageRoot(ctx);
            root.addView(UiKit.topBar(ctx, "模型", "供应商已不存在", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ProviderNav.back(act);
                }
            }));
            root.addView(ApiPageKit.note(ctx, "这个供应商已经被删除了。"));
            return root;
        }

        LinearLayout root = ApiPageKit.pageRoot(ctx);
        st.selectAll = new TextView(ctx);
        st.selectAll.setText("全选 (0)");
        st.selectAll.setTextSize(UiKit.FS_BTN);
        st.selectAll.setTextColor(UiKit.ACC);
        st.selectAll.setTypeface(Typeface.DEFAULT_BOLD);
        st.selectAll.setPadding(ApiPageKit.dp(ctx, 8), ApiPageKit.dp(ctx, 8),
                ApiPageKit.dp(ctx, 8), ApiPageKit.dp(ctx, 8));
        st.selectAll.setClickable(true);
        final LinearLayout bar = UiKit.topBar(ctx, st.pv.name, "可用模型列表", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProviderNav.back(act);
            }
        });
        bar.addView(st.selectAll, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar);

        st.search = new EditText(ctx);
        st.search.setHint("按名称筛选");
        st.search.setSingleLine(true);
        st.search.setTextSize(UiKit.FS_BTN);
        UiKit.field(st.search, ctx);
        LinearLayout sbox = ApiPageKit.contentHost(ctx);
        sbox.setPadding(ApiPageKit.dp(ctx, 16), 0, ApiPageKit.dp(ctx, 16), 0);
        sbox.addView(st.search, new LinearLayout.LayoutParams(-1, -2));
        root.addView(sbox);

        LinearLayout host = ApiPageKit.contentHost(ctx);
        st.status = ApiPageKit.note(ctx, "正在拉取模型列表…");
        st.status.setTextColor(UiKit.SUB);
        host.addView(st.status);
        st.rows = new LinearLayout(ctx);
        st.rows.setOrientation(LinearLayout.VERTICAL);
        host.addView(st.rows);
        root.addView(ApiPageKit.scrollWrap(ctx, host), new LinearLayout.LayoutParams(-1, 0, 1.0f));
        root.addView(ApiPageKit.note(ctx, "点 + 立即加入本供应商；已加入的显示为勾选。"));
        st.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                render(st);
            }
        });
        // 视图树挂好后再发网络：构造期就请求会让失败回调找不到宿主行容器。
        st.rows.post(new Runnable() {
            @Override
            public void run() {
                load(st);
            }
        });
        return root;
    }

    /** 进入页面后拉一次（由 Nav 在渲染后回调触发，避免构造期就发网络）。 */
    private static void load(final St st) {
        if (st.busy) {
            return;
        }
        st.busy = true;
        ProviderNav.fetchModels(st.act, st.pv, new ProviderNav.ModelsCallback() {
            @Override
            public void onDone(List<String> models, String error) {
                st.busy = false;
                st.status.setTextColor(UiKit.SUB);
                if (error != null) {
                    st.status.setText(error);
                    st.rows.removeAllViews();
                    return;
                }
                st.loaded = models;
                st.added = ModelStore.namesOf(st.act, st.pv.id);
                st.status.setText("检测到 " + models.size() + " 个可用模型");
                render(st);
            }
        });
    }

    /** 按当前筛选词重建结果。 */
    private static void render(final St st) {
        final Context ctx = st.act;
        st.rows.removeAllViews();
        String q = st.search.getText() == null ? "" : st.search.getText().toString().trim().toLowerCase();
        int shown = 0;
        if (st.loaded != null) {
            for (int i = 0; i < st.loaded.size(); i++) {
                final String name = st.loaded.get(i);
                if (q.length() > 0 && !name.toLowerCase().contains(q)) {
                    continue;
                }
                st.rows.addView(row(st, name));
                shown++;
            }
        }
        st.selectAll.setText("全选 (" + shown + ")");
        st.selectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addAllFiltered(st);
            }
        });
    }

    /** 「全选」：把当前筛选结果里还没添加的全部加进去。 */
    private static void addAllFiltered(St st) {
        if (st.loaded == null) {
            return;
        }
        String q = st.search.getText() == null ? "" : st.search.getText().toString().trim().toLowerCase();
        List<String> added = ModelStore.namesOf(st.act, st.pv.id);
        java.util.List<AiModel> batch = new java.util.ArrayList<AiModel>();
        for (int i = 0; i < st.loaded.size(); i++) {
            String name = st.loaded.get(i);
            if (q.length() > 0 && !name.toLowerCase().contains(q)) {
                continue;
            }
            if (added.contains(name)) {
                continue;
            }
            batch.add(newModel(st.pv.id, name));
        }
        if (!batch.isEmpty()) {
            ModelStore.saveModels(st.act, batch);
        }
        st.added = ModelStore.namesOf(st.act, st.pv.id);
        render(st);
    }

    /** 一条：模型名 + 能力徽标行（默认值）+ 右侧 + / 勾选。 */
    private static LinearLayout row(final St st, final String name) {
        final Context ctx = st.act;
        boolean has = st.added != null && st.added.contains(name);
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UiKit.cardBg(ctx));
        r.setPadding(ApiPageKit.dp(ctx, 12), ApiPageKit.dp(ctx, 10),
                ApiPageKit.dp(ctx, 10), ApiPageKit.dp(ctx, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ApiPageKit.dp(ctx, 8);
        r.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(ctx);
        nm.setText(name);
        nm.setTextSize(UiKit.FS_BTN);
        nm.setTextColor(UiKit.TITLE);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm);
        LinearLayout tags = new LinearLayout(ctx);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        tags.setPadding(0, ApiPageKit.dp(ctx, 5), 0, 0);
        tags.addView(UiKit.badge(ctx, "文本 > 文本", UiKit.CHAT_CHIP_FG, UiKit.CHAT_CHIP_BG));
        tags.addView(badge(ctx, "流式", false));
        col.addView(tags);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));

        if (has) {
            ImageView done = UiKit.iconView(ctx, Icons.IC_CHECK, UiKit.FS_ICON, UiKit.OK);
            r.addView(done, new LinearLayout.LayoutParams(ApiPageKit.dp(ctx, 36), ApiPageKit.dp(ctx, 36)));
        } else {
            ImageView add = UiKit.iconView(ctx, Icons.IC_PLUS, UiKit.FS_ICON, UiKit.ACC);
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AiModel m = newModel(st.pv.id, name);
                    ModelStore.saveModel(st.act, m);
                    st.added = ModelStore.namesOf(st.act, st.pv.id);
                    render(st);
                }
            });
            r.addView(add, new LinearLayout.LayoutParams(ApiPageKit.dp(ctx, 36), ApiPageKit.dp(ctx, 36)));
        }
        return r;
    }

    /** 能力小徽标（无 emoji、纯文字 chip）。 */
    private static TextView badge(Context ctx, String text, boolean on) {
        TextView t = UiKit.badge(ctx, text, on ? UiKit.ON_ACC : UiKit.SUB, on ? UiKit.ACC : UiKit.SOFT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = ApiPageKit.dp(ctx, 6);
        t.setLayoutParams(lp);
        return t;
    }

    /** 拉回来的模型一律按「文本聊天 + 流式」建成默认记录，用户可再逐个调。 */
    static AiModel newModel(String providerId, String name) {
        AiModel m = new AiModel();
        m.id = ProviderStore.newId();
        m.providerId = providerId;
        m.displayName = name;
        m.kind = AiModel.KIND_CHAT;
        m.inputModalities = AiModel.MOD_TEXT;
        m.outputModalities = AiModel.MOD_TEXT;
        m.capStream = true;
        m.enabled = true;
        return m;
    }
}