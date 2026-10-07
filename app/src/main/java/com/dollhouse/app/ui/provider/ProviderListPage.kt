package com.dollhouse.app.ui.provider

import android.app.Activity
import android.app.Dialog
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
import com.dollhouse.app.ai.ModelRules
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.ApiPageKit
import com.dollhouse.app.ui.widget.ExportSheet

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
object ProviderListPage {

    @JvmStatic
    fun build(act: Activity): View {
        val ctx: Context = act
        val root = ApiPageKit.pageRoot(ctx)
        val all = ProviderStore.providers(ctx)

        val bar = UiKit.topBar(ctx, "供应商", "共 " + all.size + " 个供应商",
            View.OnClickListener { ProviderNav.handleBack(ctx) })
        val export = UiKit.iconView(ctx, Icons.IC_EXTERNAL, UiKit.FS_ICON, UiKit.SUB)
        export.setOnClickListener(View.OnClickListener { ExportSheet.show(act) })
        val elp = LinearLayout.LayoutParams(ApiPageKit.dp(ctx, UiKit.HIT_DP),
            ApiPageKit.dp(ctx, UiKit.HIT_DP))
        elp.rightMargin = ApiPageKit.dp(ctx, 4)
        bar.addView(export, elp)
        val add = UiKit.iconView(ctx, Icons.IC_PLUS, UiKit.FS_ICON, UiKit.ACC)
        add.setOnClickListener(View.OnClickListener { ProviderNav.openNewProvider(act) })
        bar.addView(add, LinearLayout.LayoutParams(ApiPageKit.dp(ctx, UiKit.HIT_DP),
            ApiPageKit.dp(ctx, UiKit.HIT_DP)))
        root.addView(bar)

        val host = ApiPageKit.contentHost(ctx)
        val search = EditText(ctx)
        search.setHint("搜索供应商名称")
        search.setSingleLine(true)
        search.setTextSize(UiKit.FS_BTN)
        UiKit.field(search, ctx)
        val searchBox = ApiPageKit.contentHost(ctx)
        searchBox.setPadding(ApiPageKit.dp(ctx, 16), 0, ApiPageKit.dp(ctx, 16), 0)
        searchBox.addView(search, LinearLayout.LayoutParams(-1, -2))
        root.addView(searchBox)

        val rows = LinearLayout(ctx)
        rows.orientation = LinearLayout.VERTICAL
        host.addView(rows)
        root.addView(ApiPageKit.scrollWrap(ctx, host), LinearLayout.LayoutParams(-1, 0, 1.0f))
        fill(act, rows, all, "")

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(e: Editable?) {
                val q = if (e == null) "" else e.toString().trim()
                fill(act, rows, all, q)
            }
        })
        return root
    }

    /** 按关键字重建行列表。 */
    private fun fill(act: Activity, rows: LinearLayout, all: List<Provider>, q: String?) {
        rows.removeAllViews()
        val key = if (q == null) "" else q.lowercase()
        var shown = 0
        for (i in all.indices) {
            val p = all[i]
            if (key.length > 0 && !p.name.lowercase().contains(key)) {
                continue
            }
            rows.addView(providerRow(act, p))
            shown++
        }
        if (shown == 0) {
            rows.addView(emptyState(act, all.isEmpty()))
        }
    }

    /** 空状态：没有供应商是引导新增；搜索没命中也给一句可读的说明。 */
    private fun emptyState(act: Activity, noData: Boolean): View {
        val ctx: Context = act
        val box = ApiPageKit.card(ctx)
        val t = TextView(ctx)
        t.text = if (noData) "还没有配置任何供应商。" else "没有匹配的供应商。"
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        box.addView(t)
        if (noData) {
            val b = UiKit.primaryChip(ctx, "添加供应商")
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.topMargin = ApiPageKit.dp(ctx, 12)
            b.setOnClickListener(View.OnClickListener { ProviderNav.openNewProvider(act) })
            box.addView(b, lp)
        }
        return box
    }

    /** 一行：首字头像 + 名称 + 状态/模型数徽标 + 更多菜单。 */
    private fun providerRow(act: Activity, p: Provider): LinearLayout {
        val ctx: Context = act
        val count = ModelStore.modelsOf(ctx, p.id).size
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = UiKit.cardBg(ctx)
        r.setPadding(ApiPageKit.dp(ctx, 12), ApiPageKit.dp(ctx, 12),
            ApiPageKit.dp(ctx, 12), ApiPageKit.dp(ctx, 12))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = ApiPageKit.dp(ctx, 8)
        r.layoutParams = lp
        // 禁用态整体降对比度（不隐藏）。
        r.alpha = if (p.enabled) 1.0f else 0.55f

        val avatar = TextView(ctx)
        avatar.text = if (p.name.isEmpty()) "?" else p.name.substring(0, 1)
        avatar.setTextSize(UiKit.FS_BTN)
        avatar.setTextColor(UiKit.ACC)
        avatar.typeface = Typeface.DEFAULT_BOLD
        avatar.gravity = Gravity.CENTER
        avatar.background = UiKit.round(UiKit.FIELD, ctx, 999f)
        val alp = LinearLayout.LayoutParams(ApiPageKit.dp(ctx, 38),
            ApiPageKit.dp(ctx, 38))
        alp.rightMargin = ApiPageKit.dp(ctx, 12)
        r.addView(avatar, alp)

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val nm = TextView(ctx)
        nm.text = p.name
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
        tags.addView(UiKit.badge(ctx, if (p.enabled) "启用" else "禁用",
            if (p.enabled) UiKit.OK else UiKit.ERR,
            // 【状态色】底色由写死的 0x1A1B8A3A / 0x1AB3261E 改成派生方法，
            //   切主题 / 莫奈时会跟着 OK / ERR 一起变，不再固定绿红。
            if (p.enabled) UiKit.okBg() else UiKit.errBg()))
        val cnt = UiKit.badge(ctx, count.toString() + " 个模型", UiKit.CHAT_CHIP_FG, UiKit.CHAT_CHIP_BG)
        val clp = LinearLayout.LayoutParams(-2, -2)
        clp.leftMargin = ApiPageKit.dp(ctx, 6)
        tags.addView(cnt, clp)
        col.addView(tags)
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1.0f))

        val more = UiKit.iconView(ctx, Icons.IC_MORE, UiKit.FS_ICON, UiKit.SUB)
        more.setOnClickListener(View.OnClickListener { menu(act, p) })
        r.addView(more, LinearLayout.LayoutParams(ApiPageKit.dp(ctx, 36), ApiPageKit.dp(ctx, 36)))
        r.isClickable = true
        UiKit.press(r)
        r.setOnClickListener(View.OnClickListener { ProviderNav.openDetail(act, p.id) })
        return r
    }

    /** 「更多」菜单：只留 重命名 / 删除 两项（编辑改走点卡片进详情页）。 */
    private fun menu(act: Activity, p: Provider) {
        val ctx: Context = act
        val dh = arrayOfNulls<Dialog>(1)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(ApiPageKit.menuItem(ctx, "重命名", View.OnClickListener {
            dismiss(dh)
            rename(act, p)
        }))
        col.addView(ApiPageKit.menuItem(ctx, "删除", View.OnClickListener {
            dismiss(dh)
            confirmDelete(act, p)
        }))
        dh[0] = UiKit.showDialog(act, p.name, col, null, null, null, null)
    }

    private fun dismiss(dh: Array<Dialog?>) {
        dh[0]?.dismiss()
    }

    /**
     * 重命名。
     * 【为什么用 showDialog 里的输入框】全工程弹层统一走 UiKit.showDialog，弹系统原生
     *   输入框会在三家主题下各长一个样。
     * 【校验】空名与重名都拦下。showDialog 的行为是「点按钮先关框再回调」，
     *   校验失败时原框已经关了，所以只能再弹一个提示框说明原因。
     */
    private fun rename(act: Activity, p: Provider) {
        val ctx: Context = act
        val input = EditText(ctx)
        input.setText(p.name)
        input.setSingleLine(true)
        input.setTextSize(UiKit.FS_BTN)
        UiKit.field(input, ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        UiKit.showDialog(act, "重命名供应商", box, "保存", View.OnClickListener {
            val name = input.text?.toString()?.trim() ?: ""
            if (name.length == 0) {
                warn(act, "名称不能为空")
            } else if (name != p.name) {
                if (ModelRules.nameExists(ProviderStore.providers(act), name, p.id)) {
                    warn(act, "已有同名供应商，换一个名字")
                } else {
                    p.name = name
                    ProviderStore.saveProvider(act, p)
                    ProviderNav.refresh(act)
                }
            }
        }, "取消", null)
    }

    /** 校验失败的二次提示（原输入框已关，只能新开一个）。 */
    private fun warn(act: Activity, msg: String) {
        UiKit.showDialog(act, "无法重命名", UiKit.dialogMessage(act, msg),
            "知道了", null, null, null)
    }

    /** 删除二次确认：文案里写明会连带删掉几个模型。 */
    private fun confirmDelete(act: Activity, p: Provider) {
        val count = ModelStore.modelsOf(act, p.id).size
        val msg = "「" + p.name + "」将被删除，它名下的 " + count.toString() + " 个模型会一并删除，删除后无法恢复。"
        UiKit.showDialog(act, "删除供应商", UiKit.dialogMessage(act, msg),
            "删除", View.OnClickListener {
                ProviderStore.deleteProvider(act, p.id)
                ProviderNav.refresh(act)
            }, "取消", null)
    }
}
