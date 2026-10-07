package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
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
 * 【职责】可用模型列表页：拉本供应商的模型清单 → 搜索筛选 → 「全选 (N)」批量导入 → 单条添加。
 *
 * 【口径】「全选」只作用于当前筛选结果，被搜索过滤掉的项不动（规格书明确要求）。
 *        已添加的模型不重复添加：「+」换成勾选态且不可点。
 *
 * 【不做什么】本页只管「把服务端返回的名字变成模型记录」，能力字段一律按默认值创建
 *        （文本聊天模型），用户想改去模型编辑页逐个调。
 */
object ModelListPage {

    /** 页面可变状态。 */
    private class St {
        lateinit var act: Activity
        var pv: Provider? = null
        lateinit var rows: LinearLayout
        lateinit var status: TextView
        lateinit var selectAll: TextView
        lateinit var search: EditText
        var loaded: MutableList<String>? = null
        var added: MutableList<String>? = null
        var busy: Boolean = false
    }

    @JvmStatic
    fun build(act: Activity, providerId: String): View {
        val ctx: Context = act
        val st = St()
        st.act = act
        st.pv = ProviderStore.findProvider(ctx, providerId)
        if (st.pv == null) {
            val root = ApiPageKit.pageRoot(ctx)
            root.addView(UiKit.topBar(ctx, "模型", "供应商已不存在", View.OnClickListener {
                ProviderNav.back(act)
            }))
            root.addView(ApiPageKit.note(ctx, "这个供应商已经被删除了。"))
            return root
        }

        val root = ApiPageKit.pageRoot(ctx)
        st.selectAll = TextView(ctx)
        st.selectAll.text = "全选 (0)"
        st.selectAll.setTextSize(UiKit.FS_BTN)
        st.selectAll.setTextColor(UiKit.ACC)
        st.selectAll.typeface = Typeface.DEFAULT_BOLD
        st.selectAll.setPadding(ApiPageKit.dp(ctx, 8), ApiPageKit.dp(ctx, 8),
            ApiPageKit.dp(ctx, 8), ApiPageKit.dp(ctx, 8))
        st.selectAll.isClickable = true
        val bar = UiKit.topBar(ctx, st.pv!!.name, "可用模型列表", View.OnClickListener {
            ProviderNav.back(act)
        })
        bar.addView(st.selectAll, LinearLayout.LayoutParams(-2, -2))
        root.addView(bar)

        st.search = EditText(ctx)
        st.search.hint = "按名称筛选"
        st.search.isSingleLine = true
        st.search.setTextSize(UiKit.FS_BTN)
        UiKit.field(st.search, ctx)
        val sbox = ApiPageKit.contentHost(ctx)
        sbox.setPadding(ApiPageKit.dp(ctx, 16), 0, ApiPageKit.dp(ctx, 16), 0)
        sbox.addView(st.search, LinearLayout.LayoutParams(-1, -2))
        root.addView(sbox)

        val host = ApiPageKit.contentHost(ctx)
        st.status = ApiPageKit.note(ctx, "正在拉取模型列表…")
        st.status.setTextColor(UiKit.SUB)
        host.addView(st.status)
        st.rows = LinearLayout(ctx)
        st.rows.orientation = LinearLayout.VERTICAL
        host.addView(st.rows)
        root.addView(ApiPageKit.scrollWrap(ctx, host), LinearLayout.LayoutParams(-1, 0, 1.0f))
        root.addView(ApiPageKit.note(ctx, "点 + 立即加入本供应商；已加入的显示为勾选。"))
        st.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }

            override fun afterTextChanged(e: Editable?) {
                render(st)
            }
        })
        // 视图树挂好后再发网络：构造期就请求会让失败回调找不到宿主行容器。
        st.rows.post { load(st) }
        return root
    }

    /** 进入页面后拉一次（由 Nav 在渲染后回调触发，避免构造期就发网络）。 */
    private fun load(st: St) {
        if (st.busy) {
            return
        }
        st.busy = true
        ProviderNav.fetchModels(st.act, st.pv, object : ProviderNav.ModelsCallback {
            override fun onDone(models: MutableList<String>?, error: String?) {
                st.busy = false
                st.status.setTextColor(UiKit.SUB)
                if (error != null) {
                    st.status.text = error
                    st.rows.removeAllViews()
                    return
                }
                st.loaded = models
                st.added = ModelStore.namesOf(st.act, st.pv!!.id)
                st.status.text = "检测到 " + models!!.size + " 个可用模型"
                render(st)
            }
        })
    }

    /** 按当前筛选词重建结果。 */
    private fun render(st: St) {
        val ctx: Context = st.act
        st.rows.removeAllViews()
        val q = st.search.text?.toString()?.trim()?.lowercase() ?: ""
        var shown = 0
        val loaded = st.loaded
        if (loaded != null) {
            for (i in loaded.indices) {
                val name = loaded[i]
                if (q.length > 0 && !name.lowercase().contains(q)) {
                    continue
                }
                st.rows.addView(row(st, name))
                shown++
            }
        }
        st.selectAll.text = "全选 (" + shown + ")"
        st.selectAll.setOnClickListener {
            addAllFiltered(st)
        }
    }

    /** 「全选」：把当前筛选结果里还没添加的全部加进去。 */
    private fun addAllFiltered(st: St) {
        val loaded = st.loaded ?: return
        val q = st.search.text?.toString()?.trim()?.lowercase() ?: ""
        val pvId = st.pv!!.id
        val added = ModelStore.namesOf(st.act, pvId)
        val batch = ArrayList<AiModel>()
        for (i in loaded.indices) {
            val name = loaded[i]
            if (q.length > 0 && !name.lowercase().contains(q)) {
                continue
            }
            if (added.contains(name)) {
                continue
            }
            batch.add(newModel(pvId, name))
        }
        if (!batch.isEmpty()) {
            ModelStore.saveModels(st.act, batch)
        }
        st.added = ModelStore.namesOf(st.act, pvId)
        render(st)
    }

    /** 一条：模型名 + 能力徽标行（默认值）+ 右侧 + / 勾选。 */
    private fun row(st: St, name: String): LinearLayout {
        val ctx: Context = st.act
        val has = st.added?.contains(name) == true
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = UiKit.cardBg(ctx)
        r.setPadding(ApiPageKit.dp(ctx, 12), ApiPageKit.dp(ctx, 10),
            ApiPageKit.dp(ctx, 10), ApiPageKit.dp(ctx, 10))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = ApiPageKit.dp(ctx, 8)
        r.layoutParams = lp

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val nm = TextView(ctx)
        nm.text = name
        nm.setTextSize(UiKit.FS_BTN)
        nm.setTextColor(UiKit.TITLE)
        nm.isSingleLine = true
        nm.ellipsize = TextUtils.TruncateAt.END
        col.addView(nm)
        val tags = LinearLayout(ctx)
        tags.orientation = LinearLayout.HORIZONTAL
        tags.gravity = Gravity.CENTER_VERTICAL
        tags.setPadding(0, ApiPageKit.dp(ctx, 5), 0, 0)
        tags.addView(UiKit.badge(ctx, "文本 > 文本", UiKit.CHAT_CHIP_FG, UiKit.CHAT_CHIP_BG))
        tags.addView(badge(ctx, "流式", false))
        col.addView(tags)
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1.0f))

        if (has) {
            val done = UiKit.iconView(ctx, Icons.IC_CHECK, UiKit.FS_ICON, UiKit.OK)
            r.addView(done, LinearLayout.LayoutParams(ApiPageKit.dp(ctx, 36), ApiPageKit.dp(ctx, 36)))
        } else {
            val add = UiKit.iconView(ctx, Icons.IC_PLUS, UiKit.FS_ICON, UiKit.ACC)
            add.setOnClickListener {
                val m = newModel(st.pv!!.id, name)
                ModelStore.saveModel(st.act, m)
                st.added = ModelStore.namesOf(st.act, st.pv!!.id)
                render(st)
            }
            r.addView(add, LinearLayout.LayoutParams(ApiPageKit.dp(ctx, 36), ApiPageKit.dp(ctx, 36)))
        }
        return r
    }

    /** 能力小徽标（无 emoji、纯文字 chip）。 */
    private fun badge(ctx: Context, text: String, on: Boolean): TextView {
        val t = UiKit.badge(ctx, text, if (on) UiKit.ON_ACC else UiKit.SUB, if (on) UiKit.ACC else UiKit.SOFT)
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.leftMargin = ApiPageKit.dp(ctx, 6)
        t.layoutParams = lp
        return t
    }

    /** 拉回来的模型一律按「文本聊天 + 流式」建成默认记录，用户可再逐个调。 */
    private fun newModel(providerId: String, name: String): AiModel {
        val m = AiModel()
        m.id = ProviderStore.newId()
        m.providerId = providerId
        m.displayName = name
        m.kind = AiModel.KIND_CHAT
        m.inputModalities = AiModel.MOD_TEXT
        m.outputModalities = AiModel.MOD_TEXT
        m.capStream = true
        m.enabled = true
        return m
    }
}
