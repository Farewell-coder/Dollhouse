package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.ApiPageKit

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
object ModelEditPage {

    /** 页面状态。 */
    private class F {
        lateinit var act: Activity
        var pv: Provider? = null
        var id: String = ""
        var providerId: String = ""
        var created: Boolean = false
        var name: EditText? = null
        var hint: TextView? = null
        var kinds: Array<TextView?> = arrayOfNulls(AiModel.KINDS.size)
        var ins: Array<TextView?> = arrayOfNulls(ModelEditKit.MODS.size)
        var outs: Array<TextView?> = arrayOfNulls(ModelEditKit.MODS.size)
        val sel: ModelEditKit.Sel = ModelEditKit.Sel()
        var capVision: UiKit.Switch? = null
        var capTool: UiKit.Switch? = null
        var capReason: UiKit.Switch? = null
        var capStream: UiKit.Switch? = null
        var enabled: UiKit.Switch? = null
        var advBody: LinearLayout? = null
        var bound: Boolean = false
    }

    @JvmStatic
    fun buildNew(act: Activity, providerId: String?): View {
        return build(act, providerId, null)
    }

    @JvmStatic
    fun buildEdit(act: Activity, modelId: String?): View {
        val m = ModelStore.findModel(act, modelId)
        return build(act, if (m == null) "" else m.providerId, m)
    }

    private fun build(act: Activity, providerId: String?, src: AiModel?): View {
        val ctx: Context = act
        val f = F()
        f.act = act
        f.providerId = if (providerId == null) "" else providerId
        f.pv = ProviderStore.findProvider(ctx, f.providerId)
        if (src != null) {
            f.id = src.id
            f.created = true
            copyInto(f.sel, ModelEditKit.of(src))
        }

        val root = ApiPageKit.pageRoot(ctx)
        root.addView(UiKit.topBar(ctx, if (f.created) "编辑模型" else "添加模型",
            if (f.pv == null) "供应商不存在" else f.pv!!.name, api { ProviderNav.back(f.act) }))
        val host = ApiPageKit.contentHost(ctx)

        // —— 从供应商拉取（跳到模型列表页，那里有搜索 / 全选） ——
        val pull = ApiPageKit.card(ctx)
        pull.addView(ApiPageKit.row(ctx, "从供应商拉取模型", "\u203a", api {
            if (f.pv == null) {
                hint(f, false, "供应商不存在，无法拉取")
                return@api
            }
            ProviderNav.openModels(f.act, f.providerId)
        }))
        pull.addView(ApiPageKit.note(ctx, "拉回来的模型按「文本聊天 + 流式」建默认记录，之后可回这里逐条调能力。"))
        host.addView(pull)

        // —— 基本信息 ——
        val box = ApiPageKit.card(ctx)
        val nameField = ApiPageKit.labeledInput(ctx, box, "模型名称", "例如 deepseek-v4.1-flash-api", false)
        f.name = nameField
        if (src != null) {
            nameField.setText(src.displayName)
        }
        host.addView(box)

        // —— 模型类型 ——
        val kc = ApiPageKit.card(ctx)
        kc.addView(ApiPageKit.sectionTitle(ctx, "模型类型"))
        val kl = Array(AiModel.KINDS.size) { i -> AiModel.kindLabel(AiModel.KINDS[i]) }
        val kRow = ModelEditKit.chipRow(ctx, kl, f.kinds)
        kRow.setPadding(0, ApiPageKit.dp(ctx, 10), 0, 0)
        kc.addView(kRow)
        for (i in f.kinds.indices) {
            val idx = i
            f.kinds[i]!!.setOnClickListener(api {
                f.sel.kind = AiModel.KINDS[idx]
                refresh(f)
            })
        }
        host.addView(kc)

        // —— 输入 / 输出模态 ——
        val mc = ApiPageKit.card(ctx)
        mc.addView(ApiPageKit.sectionTitle(ctx, "输入模态"))
        mc.addView(modRow(ctx, f, true), topGap(ctx))
        mc.addView(ApiPageKit.sectionTitle(ctx, "输出模态"), topGap(ctx))
        mc.addView(modRow(ctx, f, false), topGap(ctx))
        mc.addView(ApiPageKit.note(ctx, "勾「视觉」会自动保证输入含图片；类型为「图片」时必须能输出图片。"))
        host.addView(mc)

        // —— 能力开关 ——
        val caps = ApiPageKit.card(ctx)
        caps.addView(ApiPageKit.sectionTitle(ctx, "能力"))
        f.capVision = ModelEditKit.switchRow(ctx, caps, "视觉（VISION）", "能理解图片输入",
            f.sel.vision)
        f.capTool = ModelEditKit.switchRow(ctx, caps, "工具调用（TOOL_CALL）", "支持函数调用",
            f.sel.tool)
        f.capReason = ModelEditKit.switchRow(ctx, caps, "推理（REASONING）", "支持思考过程字段",
            f.sel.reasoning)
        val enabledSw = ModelEditKit.switchRow(ctx, caps, "启用", "关掉后不出现在聊天页选择器里",
            src == null || src.enabled)
        f.enabled = enabledSw
        enabledSw.setOn(true)
        if (src != null) {
            enabledSw.setOn(src.enabled)
        }
        host.addView(caps)

        // —— 可折叠：生成参数 ——
        val adv = ApiPageKit.card(ctx)
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val ht = TextView(ctx)
        ht.text = "生成参数"
        ht.setTextSize(UiKit.FS_SUB)
        ht.setTextColor(UiKit.SUB)
        head.addView(ht, LinearLayout.LayoutParams(0, -2, 1.0f))
        val arrow = Icons.view(ctx, Icons.IC_CHEVRON_DOWN, 18.0f, UiKit.SUB)
        head.addView(arrow)
        head.isClickable = true
        adv.addView(head)
        val advBody = LinearLayout(ctx)
        f.advBody = advBody
        advBody.orientation = LinearLayout.VERTICAL
        advBody.visibility = View.GONE
        f.capStream = ModelEditKit.switchRow(ctx, advBody, "流式输出",
            "边生成边渲染，长回答不必等整段", f.sel.stream)
        adv.addView(advBody)
        head.setOnClickListener(api {
            UiKit.expand(advBody, arrow, advBody.visibility != View.VISIBLE)
        })
        host.addView(adv)

        val hintView = ApiPageKit.note(ctx, "")
        f.hint = hintView
        hintView.visibility = View.GONE
        host.addView(hintView)

        // —— 底栏 ——
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(0, ApiPageKit.dp(ctx, 14), 0, ApiPageKit.dp(ctx, 8))
        val cancel = UiKit.outlineChip(ctx, "取消")
        cancel.setOnClickListener(api { ProviderNav.back(f.act) })
        bar.addView(cancel)
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1.0f))
        val saveBtn = UiKit.primaryChip(ctx, "保存")
        saveBtn.setOnClickListener(api { save(f) })
        bar.addView(saveBtn)
        host.addView(bar)
        root.addView(ApiPageKit.scrollWrap(ctx, host), LinearLayout.LayoutParams(-1, 0, 1.0f))

        // 控件的点击要读 sel，绑定时必须保证 sel 已被 src 初始化过（上面已 copyInto）。
        bindToggles(f)
        refresh(f)
        return root
    }

    /** 输入 / 输出两排模态格子。 */
    private fun modRow(ctx: Context, f: F, input: Boolean): LinearLayout {
        val labels = Array(ModelEditKit.MODS.size) { i -> AiModel.modLabel(ModelEditKit.MODS[i]) }
        val row = ModelEditKit.chipRow(ctx, labels, if (input) f.ins else f.outs)
        for (i in labels.indices) {
            val idx = i
            (if (input) f.ins else f.outs)[i]!!.setOnClickListener(api {
                val s = if (input) f.sel.`in` else f.sel.out
                s[idx] = !s[idx]
                refresh(f)
            })
        }
        return row
    }

    /** 能力开关的点击：只翻转 sel 里的值，再交给 refresh 统一钳制与重绘。 */
    private fun bindToggles(f: F) {
        if (f.bound) {
            return
        }
        f.bound = true
        f.capVision!!.setOnClickListener(api {
            f.sel.vision = !f.sel.vision
            f.capVision!!.setOn(f.sel.vision, true)
            refresh(f)
        })
        f.capTool!!.setOnClickListener(api {
            f.sel.tool = !f.sel.tool
            f.capTool!!.setOn(f.sel.tool, true)
            refresh(f)
        })
        f.capReason!!.setOnClickListener(api {
            f.sel.reasoning = !f.sel.reasoning
            f.capReason!!.setOn(f.sel.reasoning, true)
            refresh(f)
        })
        f.capStream!!.setOnClickListener(api {
            f.sel.stream = !f.sel.stream
            f.capStream!!.setOn(f.sel.stream, true)
        })
        f.enabled!!.setOnClickListener(api { f.enabled!!.setOn(!f.enabled!!.isOn(), true) })
    }

    /** 钳制 + 重绘：界面上任何一次点击都走这里，保证显示状态永远合法。 */
    private fun refresh(f: F) {
        ModelEditKit.normalize(f.sel)
        val ctx: Context = f.act
        for (i in f.kinds.indices) {
            ModelEditKit.paintChip(f.kinds[i], AiModel.KINDS[i] == f.sel.kind, ctx)
        }
        val emb = AiModel.KIND_EMBEDDING == f.sel.kind
        for (i in ModelEditKit.MODS.indices) {
            ModelEditKit.paintChip(f.ins[i], f.sel.`in`[i], ctx)
            ModelEditKit.paintChip(f.outs[i], f.sel.out[i], ctx)
            val lock = emb && AiModel.MOD_TEXT != ModelEditKit.MODS[i]
            f.ins[i]!!.alpha = if (lock) 0.45f else 1.0f
            f.outs[i]!!.alpha = if (lock) 0.45f else 1.0f
        }
        // EMBEDDING 与三个能力互斥，置灰提示用户；开关值留到保存时统一落 false。
        f.capVision!!.alpha = if (emb) 0.45f else 1.0f
        f.capTool!!.alpha = if (emb) 0.45f else 1.0f
        f.capReason!!.alpha = if (emb) 0.45f else 1.0f
    }

    private fun save(f: F) {
        val ctx: Context = f.act
        val name = text(f.name)
        if (name.length == 0) {
            hint(f, false, "请填写模型名称")
            return
        }
        if (f.providerId.isEmpty()
            || ProviderStore.findProvider(ctx, f.providerId) == null) {
            hint(f, false, "这个模型没有归属的供应商，请从供应商的模型列表进入")
            return
        }
        if (ModelStore.modelExists(ctx, f.providerId, name, f.id)) {
            hint(f, false, "本供应商下已有同名模型，换一个名字")
            return
        }
        val m = ModelEditKit.toModel(f.sel, f.id, f.providerId, name,
            f.enabled == null || f.enabled!!.isOn())
        ModelStore.saveModel(ctx, m)
        ProviderNav.back(f.act)
    }

    /* ------------------------------ 小工具 ------------------------------ */

    private fun copyInto(dest: ModelEditKit.Sel, src: ModelEditKit.Sel) {
        dest.kind = src.kind
        src.`in`.copyInto(dest.`in`, 0, 0, dest.`in`.size)
        src.out.copyInto(dest.out, 0, 0, dest.out.size)
        dest.vision = src.vision
        dest.tool = src.tool
        dest.reasoning = src.reasoning
        dest.stream = src.stream
    }

    private fun topGap(ctx: Context): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = ApiPageKit.dp(ctx, 8)
        return lp
    }

    private fun text(e: EditText?): String {
        return if (e == null || e.text == null) "" else e.text.toString().trim()
    }

    private fun hint(f: F, ok: Boolean, detail: String?) {
        val h = f.hint
        if (h == null || h.parent == null) {
            return
        }
        h.text = if (detail == null) "" else detail
        h.setTextColor(if (ok) UiKit.OK else UiKit.ERR)
        UiKit.reveal(h)
    }

    private fun api(r: () -> Unit): View.OnClickListener {
        return View.OnClickListener { r() }
    }
}
