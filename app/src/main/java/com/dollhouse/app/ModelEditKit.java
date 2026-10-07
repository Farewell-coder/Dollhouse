package com.dollhouse.app;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 【职责】模型编辑的纯规则层 + 两个页面复用的小控件。
 *
 * 【为什么单独一个类】规格书要求单文件 ≤400 行，编辑页的界面件与规则混在一起会把文件撑爆；
 *        而「类型 / 模态 / 能力三者互相约束」这段逻辑是纯函数，抽出来才能单独对照规格书检查。
 *
 * 【硬约束来源（规格书原文口径）】
 *        ① kind=IMAGE 必须勾输出 IMAGE；
 *        ② kind=EMBEDDING 输入只允许 TEXT，且不能带视觉 / 工具 / 推理 / 流式；
 *        ③ 勾 VISION 自动保证输入含 IMAGE；勾 TOOL_CALL 或 REASONING 自动把 kind 拉回 CHAT。
 */
final class ModelEditKit {

    /** 模态顺序固定：文本 / 图片 / 音频（界面三格顺序即此）。 */
    static final String[] MODS = {AiModel.MOD_TEXT, AiModel.MOD_IMAGE, AiModel.MOD_AUDIO};

    private ModelEditKit() {
    }

    /** 表单里的类型 / 模态 / 能力选择状态，钳制与保存共用同一份，避免两处判断走偏。 */
    static final class Sel {
        String kind = AiModel.KIND_CHAT;
        final boolean[] in = new boolean[MODS.length];
        final boolean[] out = new boolean[MODS.length];
        boolean vision;
        boolean tool;
        boolean reasoning;
        boolean stream = true;

        Sel() {
            in[0] = true;
            out[0] = true;
        }
    }

    /** 模态在 MODS 里的下标。 */
    static int idxOf(String mod) {
        for (int i = 0; i < MODS.length; i++) {
            if (MODS[i].equals(mod)) {
                return i;
            }
        }
        return -1;
    }

    /** 从模型的逗号串还原成勾选状态。 */
    static boolean[] modsOf(String csv) {
        boolean[] out = new boolean[MODS.length];
        for (int i = 0; i < MODS.length; i++) {
            out[i] = hasMod(csv, MODS[i]);
        }
        if (!out[0] && !out[1] && !out[2]) {
            out[0] = true;
        }
        return out;
    }

    static boolean hasMod(String csv, String one) {
        if (csv == null || csv.length() == 0) {
            return false;
        }
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (one.equals(parts[i].trim())) {
                return true;
            }
        }
        return false;
    }

    /** 勾选状态还原成落盘的逗号串；一格都没勾时兜底 TEXT，避免存出空模态。 */
    static String join(boolean[] sel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MODS.length; i++) {
            if (sel[i]) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(MODS[i]);
            }
        }
        return sb.length() == 0 ? AiModel.MOD_TEXT : sb.toString();
    }

    /**
     * 把选择状态纠正成合法组合（界面每点一格就调一次）。
     * 【不做什么】不清空用户已开的能力开关 —— 只有 EMBEDDING 是「彻底不适用」，
     *        在保存时统一落成 false；界面上只做置灰，避免用户切一下类型能力就被清光。
     */
    static void normalize(Sel s) {
        if (s.kind == null) {
            s.kind = AiModel.KIND_CHAT;
        }
        if (AiModel.KIND_EMBEDDING.equals(s.kind)) {
            for (int i = 0; i < MODS.length; i++) {
                s.in[i] = AiModel.MOD_TEXT.equals(MODS[i]);
                s.out[i] = s.in[i];
            }
            return;
        }
        int img = idxOf(AiModel.MOD_IMAGE);
        if (AiModel.KIND_IMAGE.equals(s.kind)) {
            s.out[img] = true;
        }
        if (s.vision && img >= 0) {
            s.in[img] = true;
        }
        if (s.tool || s.reasoning) {
            s.kind = AiModel.KIND_CHAT;
        }
    }

    /** 把表单状态落成模型记录（保存前会再钳一次，保证字段自洽）。 */
    static AiModel toModel(Sel s, String id, String providerId, String name, boolean enabled) {
        normalize(s);
        AiModel m = new AiModel();
        m.id = id == null ? "" : id;
        m.providerId = providerId;
        m.displayName = name;
        m.kind = s.kind;
        m.inputModalities = join(s.in);
        m.outputModalities = join(s.out);
        m.capVision = s.vision;
        m.capToolCall = s.tool;
        m.capReasoning = s.reasoning;
        m.capStream = s.stream;
        m.enabled = enabled;
        if (AiModel.KIND_EMBEDDING.equals(m.kind)) {
            m.inputModalities = AiModel.MOD_TEXT;
            m.outputModalities = AiModel.MOD_TEXT;
            m.capVision = false;
            m.capToolCall = false;
            m.capReasoning = false;
            m.capStream = false;
        } else if (AiModel.KIND_IMAGE.equals(m.kind) && !hasMod(m.outputModalities, AiModel.MOD_IMAGE)) {
            m.outputModalities = m.outputModalities + "," + AiModel.MOD_IMAGE;
        }
        if (m.capToolCall || m.capReasoning) {
            m.kind = AiModel.KIND_CHAT;
        }
        return m;
    }

    /** 从已有模型还原表单状态。 */
    static Sel of(AiModel m) {
        Sel s = new Sel();
        if (m == null) {
            return s;
        }
        s.kind = m.kind == null ? AiModel.KIND_CHAT : m.kind;
        System.arraycopy(modsOf(m.inputModalities), 0, s.in, 0, MODS.length);
        System.arraycopy(modsOf(m.outputModalities), 0, s.out, 0, MODS.length);
        s.vision = m.capVision;
        s.tool = m.capToolCall;
        s.reasoning = m.capReasoning;
        s.stream = m.capStream;
        return s;
    }

    /** 一行开关（左标题 + 可选说明 + 右 UiKit.Switch），返回句柄供读值。 */
    static UiKit.Switch switchRow(Context ctx, LinearLayout dest, String name, String desc, boolean on) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 10));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(name);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        col.addView(t);
        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(ctx);
            d.setText(desc);
            d.setTextSize(UiKit.FS_TINY);
            d.setTextColor(UiKit.SUB);
            d.setPadding(0, UiKit.dp(ctx, 2), 0, 0);
            col.addView(d);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final UiKit.Switch s = new UiKit.Switch(ctx);
        s.setOn(on);
        s.setClickable(true);
        r.addView(s);
        dest.addView(r);
        return s;
    }

    /** 三格一排的可点 chip 行，返回这一行（chip 由调用方从返回的数组里取）。 */
    static LinearLayout chipRow(Context ctx, String[] labels, TextView[] store) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            TextView t = UiKit.outlineChip(ctx, labels[i]);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            if (i < labels.length - 1) {
                lp.rightMargin = UiKit.dp(ctx, 6);
            }
            r.addView(t, lp);
            store[i] = t;
        }
        return r;
    }

    /** 选中 / 未选中的 chip 样式（选中实心、未选描边）。 */
    static void paintChip(TextView t, boolean on, Context ctx) {
        if (t == null) {
            return;
        }
        t.setTextColor(on ? UiKit.ON_ACC : UiKit.TITLE);
        t.setBackground(on ? UiKit.round(UiKit.CHAT_CHIP_FG, ctx, 10)
                : UiKit.roundStroke(0xFFFFFFFF, UiKit.STROKE, ctx, 10));
    }

    /** 点一下按钮的通用外壳。 */
    static View.OnClickListener click(final Runnable r) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        };
    }
}