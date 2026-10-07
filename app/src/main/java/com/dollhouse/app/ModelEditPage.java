package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 【职责】添加 / 编辑模型页：名称 + 类型 + 输入输出模态 + 能力开关 + 启用。
 *
 * 【规则】类型 / 模态 / 能力三者的互相约束全部走 ModelEditKit（纯函数），本页只负责摆控件与回读。
 *        每点一格就 normalize + 重绘，用户不可能停在非法组合上。
 *
 * 【交互】「从供应商拉取」直接跳到模型列表页（那里有搜索与全选），不在本页重复做一遍。
 *        保存走 ModelStore，成功后一步返回上一层。
 *
 * 【隐私】本页不碰密钥。
 */
final class ModelEditPage {

    /** 页面状态。 */
    private static final class F {
        Activity act;
        Provider pv;
        String id = "";
        String providerId = "";
        boolean created;
        EditText name;
        TextView hint;
        TextView[] kinds = new TextView[AiModel.KINDS.length];
        TextView[] ins = new TextView[ModelEditKit.MODS.length];
        TextView[] outs = new TextView[ModelEditKit.MODS.length];
        final ModelEditKit.Sel sel = new ModelEditKit.Sel();
        UiKit.Switch capVision;
        UiKit.Switch capTool;
        UiKit.Switch capReason;
        UiKit.Switch capStream;
        UiKit.Switch enabled;
        LinearLayout advBody;
        boolean bound;
    }

    private ModelEditPage() {
    }

    static View buildNew(Activity act, String providerId) {
        return build(act, providerId, null);
    }

    static View buildEdit(Activity act, String modelId) {
        AiModel m = ModelStore.findModel(act, modelId);
        return build(act, m == null ? "" : m.providerId, m);
    }

    private static View build(final Activity act, String providerId, AiModel src) {
        final Context ctx = act;
        final F f = new F();
        f.act = act;
        f.providerId = providerId == null ? "" : providerId;
        f.pv = ProviderStore.findProvider(ctx, f.providerId);
        if (src != null) {
            f.id = src.id;
            f.created = true;
            copyInto(f.sel, ModelEditKit.of(src));
        }

        LinearLayout root = ApiPageKit.pageRoot(ctx);
        root.addView(UiKit.topBar(ctx, f.created ? "编辑模型" : "添加模型",
                f.pv == null ? "供应商不存在" : f.pv.name, api(() -> ProviderNav.back(f.act))));
        LinearLayout host = ApiPageKit.contentHost(ctx);

        // —— 从供应商拉取（跳到模型列表页，那里有搜索 / 全选） ——
        LinearLayout pull = ApiPageKit.card(ctx);
        pull.addView(ApiPageKit.row(ctx, "从供应商拉取模型", "\u203a", api(() -> {
            if (f.pv == null) {
                hint(f, false, "供应商不存在，无法拉取");
                return;
            }
            ProviderNav.openModels(f.act, f.providerId);
        })));
        pull.addView(ApiPageKit.note(ctx, "拉回来的模型按「文本聊天 + 流式」建默认记录，之后可回这里逐条调能力。"));
        host.addView(pull);

        // —— 基本信息 ——
        LinearLayout box = ApiPageKit.card(ctx);
        f.name = ApiPageKit.labeledInput(ctx, box, "模型名称", "例如 deepseek-v4.1-flash-api", false);
        if (src != null) {
            f.name.setText(src.displayName);
        }
        host.addView(box);

        // —— 模型类型 ——
        LinearLayout kc = ApiPageKit.card(ctx);
        kc.addView(ApiPageKit.sectionTitle(ctx, "模型类型"));
        String[] kl = new String[AiModel.KINDS.length];
        for (int i = 0; i < kl.length; i++) {
            kl[i] = AiModel.kindLabel(AiModel.KINDS[i]);
        }
        LinearLayout kRow = ModelEditKit.chipRow(ctx, kl, f.kinds);
        kRow.setPadding(0, ApiPageKit.dp(ctx, 10), 0, 0);
        kc.addView(kRow);
        for (int i = 0; i < f.kinds.length; i++) {
            final int idx = i;
            f.kinds[i].setOnClickListener(api(() -> {
                f.sel.kind = AiModel.KINDS[idx];
                refresh(f);
            }));
        }
        host.addView(kc);

        // —— 输入 / 输出模态 ——
        LinearLayout mc = ApiPageKit.card(ctx);
        mc.addView(ApiPageKit.sectionTitle(ctx, "输入模态"));
        mc.addView(modRow(ctx, f, true), topGap(ctx));
        mc.addView(ApiPageKit.sectionTitle(ctx, "输出模态"), topGap(ctx));
        mc.addView(modRow(ctx, f, false), topGap(ctx));
        mc.addView(ApiPageKit.note(ctx, "勾「视觉」会自动保证输入含图片；类型为「图片」时必须能输出图片。"));
        host.addView(mc);

        // —— 能力开关 ——
        LinearLayout caps = ApiPageKit.card(ctx);
        caps.addView(ApiPageKit.sectionTitle(ctx, "能力"));
        f.capVision = ModelEditKit.switchRow(ctx, caps, "视觉（VISION）", "能理解图片输入",
                f.sel.vision);
        f.capTool = ModelEditKit.switchRow(ctx, caps, "工具调用（TOOL_CALL）", "支持函数调用",
                f.sel.tool);
        f.capReason = ModelEditKit.switchRow(ctx, caps, "推理（REASONING）", "支持思考过程字段",
                f.sel.reasoning);
        f.enabled = ModelEditKit.switchRow(ctx, caps, "启用", "关掉后不出现在聊天页选择器里",
                src == null || src.enabled);
        f.enabled.setOn(true);
        if (src != null) {
            f.enabled.setOn(src.enabled);
        }
        host.addView(caps);

        // —— 可折叠：生成参数 ——
        LinearLayout adv = ApiPageKit.card(ctx);
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ht = new TextView(ctx);
        ht.setText("生成参数");
        ht.setTextSize(UiKit.FS_SUB);
        ht.setTextColor(UiKit.SUB);
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final ImageView arrow = Icons.view(ctx, Icons.IC_CHEVRON_DOWN, 18.0f, UiKit.SUB);
        head.addView(arrow);
        head.setClickable(true);
        adv.addView(head);
        f.advBody = new LinearLayout(ctx);
        f.advBody.setOrientation(LinearLayout.VERTICAL);
        f.advBody.setVisibility(View.GONE);
        f.capStream = ModelEditKit.switchRow(ctx, f.advBody, "流式输出",
                "边生成边渲染，长回答不必等整段", f.sel.stream);
        adv.addView(f.advBody);
        head.setOnClickListener(api(() -> UiKit.expand(f.advBody, arrow,
                f.advBody.getVisibility() != View.VISIBLE)));
        host.addView(adv);

        f.hint = ApiPageKit.note(ctx, "");
        f.hint.setVisibility(View.GONE);
        host.addView(f.hint);

        // —— 底栏 ——
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, ApiPageKit.dp(ctx, 14), 0, ApiPageKit.dp(ctx, 8));
        TextView cancel = UiKit.outlineChip(ctx, "取消");
        cancel.setOnClickListener(api(() -> ProviderNav.back(f.act)));
        bar.addView(cancel);
        bar.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1.0f));
        TextView save = UiKit.primaryChip(ctx, "保存");
        save.setOnClickListener(api(() -> save(f)));
        bar.addView(save);
        host.addView(bar);
        root.addView(ApiPageKit.scrollWrap(ctx, host), new LinearLayout.LayoutParams(-1, 0, 1.0f));

        // 控件的点击要读 sel，绑定时必须保证 sel 已被 src 初始化过（上面已 copyInto）。
        bindToggles(f);
        refresh(f);
        return root;
    }

    /** 输入 / 输出两排模态格子。 */
    private static LinearLayout modRow(final Context ctx, final F f, final boolean input) {
        String[] labels = new String[ModelEditKit.MODS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = AiModel.modLabel(ModelEditKit.MODS[i]);
        }
        LinearLayout row = ModelEditKit.chipRow(ctx, labels, input ? f.ins : f.outs);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            (input ? f.ins : f.outs)[i].setOnClickListener(api(() -> {
                boolean[] s = input ? f.sel.in : f.sel.out;
                s[idx] = !s[idx];
                refresh(f);
            }));
        }
        return row;
    }

    /** 能力开关的点击：只翻转 sel 里的值，再交给 refresh 统一钳制与重绘。 */
    private static void bindToggles(final F f) {
        if (f.bound) {
            return;
        }
        f.bound = true;
        f.capVision.setOnClickListener(api(() -> {
            f.sel.vision = !f.sel.vision;
            f.capVision.setOn(f.sel.vision, true);
            refresh(f);
        }));
        f.capTool.setOnClickListener(api(() -> {
            f.sel.tool = !f.sel.tool;
            f.capTool.setOn(f.sel.tool, true);
            refresh(f);
        }));
        f.capReason.setOnClickListener(api(() -> {
            f.sel.reasoning = !f.sel.reasoning;
            f.capReason.setOn(f.sel.reasoning, true);
            refresh(f);
        }));
        f.capStream.setOnClickListener(api(() -> {
            f.sel.stream = !f.sel.stream;
            f.capStream.setOn(f.sel.stream, true);
        }));
        f.enabled.setOnClickListener(api(() -> f.enabled.setOn(!f.enabled.isOn(), true)));
    }

    /** 钳制 + 重绘：界面上任何一次点击都走这里，保证显示状态永远合法。 */
    private static void refresh(F f) {
        ModelEditKit.normalize(f.sel);
        Context ctx = f.act;
        for (int i = 0; i < f.kinds.length; i++) {
            ModelEditKit.paintChip(f.kinds[i], AiModel.KINDS[i].equals(f.sel.kind), ctx);
        }
        boolean emb = AiModel.KIND_EMBEDDING.equals(f.sel.kind);
        for (int i = 0; i < ModelEditKit.MODS.length; i++) {
            ModelEditKit.paintChip(f.ins[i], f.sel.in[i], ctx);
            ModelEditKit.paintChip(f.outs[i], f.sel.out[i], ctx);
            boolean lock = emb && !AiModel.MOD_TEXT.equals(ModelEditKit.MODS[i]);
            f.ins[i].setAlpha(lock ? 0.45f : 1.0f);
            f.outs[i].setAlpha(lock ? 0.45f : 1.0f);
        }
        // EMBEDDING 与三个能力互斥，置灰提示用户；开关值留到保存时统一落 false。
        f.capVision.setAlpha(emb ? 0.45f : 1.0f);
        f.capTool.setAlpha(emb ? 0.45f : 1.0f);
        f.capReason.setAlpha(emb ? 0.45f : 1.0f);
    }

    private static void save(F f) {
        Context ctx = f.act;
        String name = text(f.name);
        if (name.length() == 0) {
            hint(f, false, "请填写模型名称");
            return;
        }
        if (f.providerId == null || f.providerId.length() == 0
                || ProviderStore.findProvider(ctx, f.providerId) == null) {
            hint(f, false, "这个模型没有归属的供应商，请从供应商的模型列表进入");
            return;
        }
        if (ModelStore.modelExists(ctx, f.providerId, name, f.id)) {
            hint(f, false, "本供应商下已有同名模型，换一个名字");
            return;
        }
        AiModel m = ModelEditKit.toModel(f.sel, f.id, f.providerId, name,
                f.enabled == null || f.enabled.isOn());
        ModelStore.saveModel(ctx, m);
        ProviderNav.back(f.act);
    }

    /* ------------------------------ 小工具 ------------------------------ */

    private static void copyInto(ModelEditKit.Sel dest, ModelEditKit.Sel src) {
        dest.kind = src.kind;
        System.arraycopy(src.in, 0, dest.in, 0, dest.in.length);
        System.arraycopy(src.out, 0, dest.out, 0, dest.out.length);
        dest.vision = src.vision;
        dest.tool = src.tool;
        dest.reasoning = src.reasoning;
        dest.stream = src.stream;
    }

    private static LinearLayout.LayoutParams topGap(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ApiPageKit.dp(ctx, 8);
        return lp;
    }

    private static String text(EditText e) {
        return e == null || e.getText() == null ? "" : e.getText().toString().trim();
    }

    private static void hint(F f, boolean ok, String detail) {
        if (f.hint == null || f.hint.getParent() == null) {
            return;
        }
        f.hint.setText(detail == null ? "" : detail);
        f.hint.setTextColor(ok ? UiKit.OK : UiKit.ERR);
        UiKit.reveal(f.hint);
    }

    private static View.OnClickListener api(Runnable r) {
        return ModelEditKit.click(r);
    }
}