package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】「关于」独立页：软件介绍 + 关于本软件（作者 / 反馈群 / 版本）。
 *
 * 【入口】设置页「关于」卡片里的入口行 → open()；返回键 → closeIfOpen()。
 *
 * 【交互】与 MemPage / ApiConfigPage 同构：整页代码构建、无 layout，挂在 android.R.id.content
 *         上并打 TAG_PAGE，不新建 Activity，因此主题切换重建后由调用方重新 open。
 *
 * 【坑】① 配色必须全走 UiKit 主题变量（用户硬约束：不能另起一套深色皮肤，要跟全局统一）；
 *       ② 版本号从 PackageManager 现取，不硬编码，避免升级时忘了改；
 *       ③ 反馈群号点击后只做「选中态」不做复制——工程禁用浮层短提示，也不引剪贴板权限。
 */
object AboutPage {
    private const val LOG_TAG = "Dollhouse"

    private const val TAG_PAGE = "feiyu_about_page"

    /** 作者（与参考图一致，用户只要求改软件名与描述）。 */
    private const val AUTHOR = "aerree"
    /** 反馈群（QQ 群号，与参考图一致）。 */
    private const val GROUP = "864339949"

    /** 返回键用：本页开着就关掉并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return try {
            val act = findActivity(ctx) ?: return false
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
            val old = content.findViewWithTag<View>(TAG_PAGE) ?: return false
            UiKit.closePage(old)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 设置页「关于」入口行的落地动作。 */
    @JvmStatic
    fun open(ctx: Context) {
        try {
            val act = findActivity(ctx) ?: return
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            val page = buildPage(act)
            UiKit.openPage(content, page, TAG_PAGE)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        var i = 0
        while (i < 8 && c != null) {
            if (c is Activity) {
                return c
            }
            if (c is ContextWrapper) {
                c = c.baseContext
            } else {
                break
            }
            i++
        }
        return null
    }

    private fun buildPage(act: Activity): View {
        val ctx: Context = act

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(UiKit.BG)
        val pad = dp(ctx, 16)
        // 【顶部不再留白】同 TokenStat：紧随其后的 UiKit.topBar 已自带 statusBarPad。
        box.setPadding(pad, 0, pad, dp(ctx, 24))

        // 顶栏：统一走 UiKit.topBar（左上返回箭头 + 主标题 + 副标题）
        box.addView(UiKit.topBar(ctx, "关于", "关于本软件", View.OnClickListener {
            closeIfOpen(act)
        }))

        // ---- 卡片 1：软件介绍 ----
        val c1 = card(ctx)
        c1.addView(logoRow(ctx, "Dollhouse"))
        c1.addView(body(ctx, "一个开源改造的桌宠小工具：一只会在屏幕上陪你的人偶，点她说话、拖着走，"
                + "长按打开面板。"))
        c1.addView(body(ctx, "她能记住你们聊过的事，聊长了会把早期内容压成要点，"
                + "把这些要点和你的对话一起带进下一次回复。"
                + "所有对话、记忆与配置都只留在这台设备上，不上传任何服务器。"))
        c1.addView(body(ctx, "玩法很简单：给她配一个模型端点 → 点她说话 → 聊久了自动总结。"
                + "关掉自动总结，她就只保留原文，不会替你压缩。"))
        c1.addView(body(ctx, "对话内容由所选模型生成，仅供娱乐。"))
        box.addView(c1)

        // ---- 卡片 2：关于本软件 ----
        val c2 = card(ctx)
        c2.addView(logoRow(ctx, "关于本软件"))
        c2.addView(infoRow(ctx, "作者", AUTHOR, false))
        c2.addView(divider(ctx))
        c2.addView(infoRow(ctx, "反馈群", GROUP, true))
        c2.addView(divider(ctx))
        c2.addView(infoRow(ctx, "版本", "v" + versionName(ctx), false))
        box.addView(c2)

        val sc = ScrollView(ctx)
        sc.setBackgroundColor(UiKit.BG)
        sc.addView(box, ViewGroup.LayoutParams(-1, -2))
        return sc
    }

    /** 卡片头部：左侧主题色圆角方块 + 右侧标题。 */
    private fun logoRow(ctx: Context, title: String): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 0, 0, dp(ctx, 12))

        val logo = Icons.view(ctx, Icons.IC_WHALE, 22.0f, UiKit.ACC)
        logo.background = UiKit.roundStroke(UiKit.CARD, UiKit.ACC, ctx, 12f)
        logo.setPadding(dp(ctx, 9), dp(ctx, 9), dp(ctx, 9), dp(ctx, 9))
        val s = dp(ctx, 40)
        val llp = LinearLayout.LayoutParams(s, s)
        llp.rightMargin = dp(ctx, 12)
        row.addView(logo, llp)

        val t = TextView(ctx)
        t.text = title
        t.setTextSize(18.0f)
        t.setTextColor(UiKit.TITLE)
        t.typeface = Typeface.DEFAULT_BOLD
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))
        return row
    }

    /** 一段正文。 */
    private fun body(ctx: Context, s: String): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.SUB)
        t.setLineSpacing(dp(ctx, 4).toFloat(), 1.0f)
        t.setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
        return t
    }

    /** 一行「左名称 + 右值」；pill=true 时值做成主题色药丸。 */
    private fun infoRow(ctx: Context, name: String, value: String, pill: Boolean): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))

        val n = TextView(ctx)
        n.text = name
        n.setTextSize(UiKit.FS_BTN)
        n.setTextColor(UiKit.TITLE)
        row.addView(n, LinearLayout.LayoutParams(0, -2, 1.0f))

        val v = if (pill)
            UiKit.outlineChip(ctx, value)
        else
            plainValue(ctx, value)
        row.addView(v, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun plainValue(ctx: Context, s: String): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.SUB)
        return t
    }

    private fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(UiKit.LINE)
        val lp = LinearLayout.LayoutParams(-1, 1)
        v.layoutParams = lp
        return v
    }

    private fun versionName(ctx: Context): String {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }
    }

    private fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = UiKit.cardBg(ctx)
        c.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(ctx, 10)
        c.layoutParams = lp
        return c
    }

    /** 尺寸换算：统一走 UiKit，避免多处重复实现。 */
    private fun dp(ctx: Context, v: Int): Int {
        return UiKit.dp(ctx, v.toFloat())
    }
}
