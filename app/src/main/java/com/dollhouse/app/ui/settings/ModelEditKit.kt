package com.dollhouse.app.ui.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ui.theme.UiKit

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
object ModelEditKit {

    /** 模态顺序固定：文本 / 图片 / 音频（界面三格顺序即此）。 */
    val MODS = arrayOf(AiModel.MOD_TEXT, AiModel.MOD_IMAGE, AiModel.MOD_AUDIO)

    /** 表单里的类型 / 模态 / 能力选择状态，钳制与保存共用同一份，避免两处判断走偏。 */
    class Sel {
        var kind: String? = AiModel.KIND_CHAT
        val `in` = BooleanArray(ModelEditKit.MODS.size)
        val `out` = BooleanArray(ModelEditKit.MODS.size)
        var vision = false
        var tool = false
        var reasoning = false
        var stream = true

        init {
            `in`[0] = true
            `out`[0] = true
        }
    }

    /** 模态在 MODS 里的下标。 */
    fun idxOf(mod: String): Int {
        for (i in MODS.indices) {
            if (MODS[i] == mod) {
                return i
            }
        }
        return -1
    }

    /** 从模型的逗号串还原成勾选状态。 */
    fun modsOf(csv: String?): BooleanArray {
        val out = BooleanArray(MODS.size)
        for (i in MODS.indices) {
            out[i] = hasMod(csv, MODS[i])
        }
        if (!out[0] && !out[1] && !out[2]) {
            out[0] = true
        }
        return out
    }

    fun hasMod(csv: String?, one: String): Boolean {
        if (csv == null || csv.isEmpty()) {
            return false
        }
        val parts = csv.split(",")
        for (i in parts.indices) {
            if (one == parts[i].trim()) {
                return true
            }
        }
        return false
    }

    /** 勾选状态还原成落盘的逗号串；一格都没勾时兜底 TEXT，避免存出空模态。 */
    fun join(sel: BooleanArray): String {
        val sb = StringBuilder()
        for (i in MODS.indices) {
            if (sel[i]) {
                if (sb.isNotEmpty()) {
                    sb.append(',')
                }
                sb.append(MODS[i])
            }
        }
        return if (sb.isEmpty()) AiModel.MOD_TEXT else sb.toString()
    }

    /**
     * 把选择状态纠正成合法组合（界面每点一格就调一次）。
     * 【不做什么】不清空用户已开的能力开关 —— 只有 EMBEDDING 是「彻底不适用」，
     *        在保存时统一落成 false；界面上只做置灰，避免用户切一下类型能力就被清光。
     */
    fun normalize(s: Sel) {
        if (s.kind == null) {
            s.kind = AiModel.KIND_CHAT
        }
        if (AiModel.KIND_EMBEDDING == s.kind) {
            for (i in MODS.indices) {
                s.`in`[i] = AiModel.MOD_TEXT == MODS[i]
                s.`out`[i] = s.`in`[i]
            }
            return
        }
        val img = idxOf(AiModel.MOD_IMAGE)
        if (AiModel.KIND_IMAGE == s.kind) {
            s.`out`[img] = true
        }
        if (s.vision && img >= 0) {
            s.`in`[img] = true
        }
        if (s.tool || s.reasoning) {
            s.kind = AiModel.KIND_CHAT
        }
    }

    /** 把表单状态落成模型记录（保存前会再钳一次，保证字段自洽）。 */
    fun toModel(s: Sel, id: String?, providerId: String?, name: String?, enabled: Boolean): AiModel {
        normalize(s)
        val m = AiModel()
        m.id = id ?: ""
        m.providerId = providerId ?: ""
        m.displayName = name ?: ""
        m.kind = s.kind ?: AiModel.KIND_CHAT
        m.inputModalities = join(s.`in`)
        m.outputModalities = join(s.`out`)
        m.capVision = s.vision
        m.capToolCall = s.tool
        m.capReasoning = s.reasoning
        m.capStream = s.stream
        m.enabled = enabled
        if (AiModel.KIND_EMBEDDING == m.kind) {
            m.inputModalities = AiModel.MOD_TEXT
            m.outputModalities = AiModel.MOD_TEXT
            m.capVision = false
            m.capToolCall = false
            m.capReasoning = false
            m.capStream = false
        } else if (AiModel.KIND_IMAGE == m.kind && !hasMod(m.outputModalities, AiModel.MOD_IMAGE)) {
            m.outputModalities = m.outputModalities + "," + AiModel.MOD_IMAGE
        }
        if (m.capToolCall || m.capReasoning) {
            m.kind = AiModel.KIND_CHAT
        }
        return m
    }

    /** 从已有模型还原表单状态。 */
    fun of(m: AiModel?): Sel {
        val s = Sel()
        if (m == null) {
            return s
        }
        s.kind = m.kind ?: AiModel.KIND_CHAT
        System.arraycopy(modsOf(m.inputModalities), 0, s.`in`, 0, MODS.size)
        System.arraycopy(modsOf(m.outputModalities), 0, s.`out`, 0, MODS.size)
        s.vision = m.capVision
        s.tool = m.capToolCall
        s.reasoning = m.capReasoning
        s.stream = m.capStream
        return s
    }

    /** 一行开关（左标题 + 可选说明 + 右 UiKit.Switch），返回句柄供读值。 */
    fun switchRow(ctx: Context, dest: LinearLayout, name: String, desc: String?, on: Boolean): UiKit.Switch {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, UiKit.dp(ctx, 10f), 0, UiKit.dp(ctx, 10f))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(ctx)
        t.text = name
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        col.addView(t)
        if (desc != null && desc.isNotEmpty()) {
            val d = TextView(ctx)
            d.text = desc
            d.setTextSize(UiKit.FS_TINY)
            d.setTextColor(UiKit.SUB)
            d.setPadding(0, UiKit.dp(ctx, 2f), 0, 0)
            col.addView(d)
        }
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1.0f))
        val s = UiKit.Switch(ctx)
        s.setOn(on)
        s.isClickable = true
        r.addView(s)
        dest.addView(r)
        return s
    }

    /** 三格一排的可点 chip 行，返回这一行（chip 由调用方从返回的数组里取）。 */
    fun chipRow(ctx: Context, labels: Array<String>, store: Array<TextView?>): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        for (i in labels.indices) {
            val t = UiKit.outlineChip(ctx, labels[i])
            t.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, -2, 1.0f)
            if (i < labels.size - 1) {
                lp.rightMargin = UiKit.dp(ctx, 6f)
            }
            r.addView(t, lp)
            store[i] = t
        }
        return r
    }

    /** 选中 / 未选中的 chip 样式（选中实心、未选描边）。 */
    fun paintChip(t: TextView?, on: Boolean, ctx: Context) {
        if (t == null) {
            return
        }
        t.setTextColor(if (on) UiKit.ON_ACC else UiKit.TITLE)
        t.background = if (on) UiKit.round(UiKit.CHAT_CHIP_FG, ctx, 10f)
        else UiKit.roundStroke(0xFFFFFFFF.toInt(), UiKit.STROKE, ctx, 10f)
    }

    /** 点一下按钮的通用外壳。 */
    fun click(r: Runnable): View.OnClickListener {
        return View.OnClickListener {
            r.run()
        }
    }
}
