package com.dollhouse.app.ui.provider

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.ApiPageKit
import com.dollhouse.app.ui.widget.SwipeRow

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
object ProviderModelsPage {

    @JvmStatic
    fun build(act: Activity, providerId: String?): View {
        val ctx: Context = act
        val pv = ProviderStore.findProvider(ctx, providerId)
        val root = FrameLayout(ctx)
        if (pv == null) {
            val miss = ApiPageKit.pageRoot(ctx)
            miss.addView(UiKit.topBar(ctx, "模型", "供应商已不存在", View.OnClickListener {
                ProviderNav.back(act)
            }))
            miss.addView(ApiPageKit.note(ctx, "这个供应商已经被删除了。"))
            root.addView(miss, FrameLayout.LayoutParams(-1, -1))
            return root
        }

        val col = ApiPageKit.pageRoot(ctx)
        col.addView(UiKit.topBar(ctx, pv.name, countText(ctx, pv.id), View.OnClickListener {
            ProviderNav.back(act)
        }))

        val rows = LinearLayout(ctx)
        rows.orientation = LinearLayout.VERTICAL
        val host = LinearLayout(ctx)
        host.orientation = LinearLayout.VERTICAL
        val pad = ApiPageKit.dp(ctx, 16)
        host.setPadding(pad, 0, pad, 0)
        host.addView(rows)
        // 底部留白：悬浮按钮高 44dp + 与它上下各 12dp 呼吸。
        host.addView(View(ctx), LinearLayout.LayoutParams(-1, ApiPageKit.dp(ctx, 68)))
        col.addView(ApiPageKit.scrollWrap(ctx, host), LinearLayout.LayoutParams(-1, 0, 1.0f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        fill(act, rows, pv)
        root.addView(addButton(act, pv), addLp(ctx))
        return root
    }

    /** 顶栏副标题文案。 */
    private fun countText(ctx: Context, providerId: String?): String {
        return "共 " + ModelStore.modelsOf(ctx, providerId).size + " 个模型"
    }

    /** 按当前数据重建行列表。 */
    private fun fill(act: Activity, rows: LinearLayout, pv: Provider) {
        rows.removeAllViews()
        val list = ModelStore.modelsOf(act, pv.id)
        if (list.isEmpty()) {
            rows.addView(empty(act))
            return
        }
        for (i in list.indices) {
            val m = list[i]
            val row = SwipeRow(act, modelCard(act, m), Runnable {
                ProviderNav.openModel(act, m.id)
            }, Runnable {
                confirmDelete(act, m)
            })
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = ApiPageKit.dp(act, 8)
            row.layoutParams = lp
            rows.addView(row)
        }
    }

    /** 空状态：还没添加任何模型。 */
    private fun empty(act: Activity): View {
        val ctx: Context = act
        val box = ApiPageKit.card(ctx)
        val t = TextView(ctx)
        t.text = "这个供应商下还没有模型。"
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        box.addView(t)
        box.addView(ApiPageKit.note(ctx, "点下面的「添加模型」从供应商拉取清单，选中的模型会出现在这里，"
            + "也会出现在聊天页的模型选择器里。"))
        return box
    }

    /** 一行模型卡片（不含左滑包装）。 */
    private fun modelCard(ctx: Context, m: AiModel): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = UiKit.cardBg(ctx)
        r.setPadding(ApiPageKit.dp(ctx, 14), ApiPageKit.dp(ctx, 12),
            ApiPageKit.dp(ctx, 14), ApiPageKit.dp(ctx, 12))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val nm = TextView(ctx)
        nm.text = m.displayName
        nm.setTextSize(UiKit.FS_BTN)
        nm.setTextColor(UiKit.TITLE)
        nm.typeface = Typeface.DEFAULT_BOLD
        nm.setSingleLine(true)
        nm.ellipsize = TextUtils.TruncateAt.END
        col.addView(nm)

        val tags = LinearLayout(ctx)
        tags.orientation = LinearLayout.HORIZONTAL
        tags.gravity = Gravity.CENTER_VERTICAL
        tags.setPadding(0, ApiPageKit.dp(ctx, 6), 0, 0)
        tags.addView(UiKit.badge(ctx, AiModel.kindLabel(m.kind),
            UiKit.CHAT_CHIP_FG, UiKit.CHAT_CHIP_BG))
        val blp = LinearLayout.LayoutParams(-2, -2)
        blp.leftMargin = ApiPageKit.dp(ctx, 6)
        tags.addView(UiKit.badge(ctx, if (m.enabled) "启用" else "禁用",
            if (m.enabled) UiKit.OK else UiKit.ERR,
            // 【状态色】底色同上，由写死值改成派生方法。
            if (m.enabled) UiKit.okBg() else UiKit.errBg()), blp)
        col.addView(tags)
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1.0f))
        // 右侧箭头提示「点进去能编辑」，与其它列表页的语义保持一致。
        r.addView(Icons.view(ctx, Icons.IC_CHEVRON_RIGHT, 18.0f, UiKit.SUB))
        return r
    }

    /** 删除二次确认：写明是哪个模型、不可恢复。 */
    private fun confirmDelete(act: Activity, m: AiModel) {
        UiKit.showDialog(act, "删除模型",
            UiKit.dialogMessage(act, "「" + m.displayName + "」将从本供应商删除，删除后无法恢复。"),
            "删除", View.OnClickListener {
                ModelStore.deleteModel(act, m.id)
                ProviderNav.refresh(act)
            }, "取消", null)
    }

    /** 悬浮的「添加模型」胶囊：落在底栏正上方居中。 */
    private fun addButton(act: Activity, pv: Provider): View {
        val ctx: Context = act
        val btn = LinearLayout(ctx)
        btn.orientation = LinearLayout.HORIZONTAL
        btn.gravity = Gravity.CENTER_VERTICAL
        btn.background = UiKit.round(UiKit.ACC, ctx, 999f)
        btn.elevation = ApiPageKit.dp(ctx, 8).toFloat()
        btn.setPadding(ApiPageKit.dp(ctx, 20), ApiPageKit.dp(ctx, 12),
            ApiPageKit.dp(ctx, 22), ApiPageKit.dp(ctx, 12))
        btn.addView(Icons.view(ctx, Icons.IC_PLUS, 18.0f, UiKit.ON_ACC))
        val t = TextView(ctx)
        t.text = "添加模型"
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.ON_ACC)
        t.typeface = Typeface.DEFAULT_BOLD
        val tlp = LinearLayout.LayoutParams(-2, -2)
        tlp.leftMargin = ApiPageKit.dp(ctx, 8)
        btn.addView(t, tlp)
        btn.isClickable = true
        UiKit.press(btn)
        btn.setOnClickListener(View.OnClickListener { ProviderNav.openModels(act, pv.id) })
        return btn
    }

    private fun addLp(ctx: Context): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(-2, -2)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        // 【坑·不能自留底距】本页被塞进详情页的 slot，slot 已经按 RESERVE_DP 垫了底。
        //   这里再加一段底距，按钮就会被推到屏幕中部去（两段间距叠加）。
        lp.bottomMargin = 0
        return lp
    }
}
