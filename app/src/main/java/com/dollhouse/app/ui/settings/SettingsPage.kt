package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.SettingsProfiles
import com.dollhouse.app.data.SettingsRegistry
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】设置页后处理器：界面构建完成后按标题切成可折叠卡片，摘掉已下线的分组。
 * 【入口】MainActivity.onCreate 里 setContentView 之后调用一次 apply(activity)。
 * 【交互】卡片头折叠依赖 UiKit.expand；聊天设置组交给 ApiSectionTuner 整理；
 *         被摘掉的分组只是脱离视图树，字段引用仍然有效，因此每秒刷新的代码不会空指针。
 * 【坑】isKnownTitle 按文本匹配分组标题，主界面里那些标题文案不能改，否则整组退回原样。
 */
object SettingsPage {
    private const val LOG_TAG = "Dollhouse"

    /** 页面尾部「说明：」长文，不参与折叠，保留为页脚。 */
    private const val FOOTER_PREFIX = "说明："

    /** 三个输入框的 hint 前缀，用来在视图树里定位它们（hint 后带示例，用前缀匹配）。 */
    const val MODEL_HINT = "例如：deepseek"
    const val KEY_HINT = "输入 API"
    const val URL_HINT = "例如：http"

    /** 挂在「测试连接」结果 TextView 上的标签，用于在视图树里认出它并摆到按钮下方。 */
    const val TAG_TEST_RESULT = "feiyu_test_result"

    /** 配置面板提交名字后的回调。 */
    fun interface NameCallback {
        fun onName(name: String)
    }

    /** smali 侧唯一入口：在 setContentView 之后调用一次。 */
    @JvmStatic
    fun apply(activity: Activity?) {
        try {
            if (activity == null) {
                return
            }
            val decor = if (activity.window == null) null else activity.window.decorView
            val scrollView = findScrollView(decor)
            if (scrollView == null || scrollView.childCount == 0) {
                return
            }
            val child = scrollView.getChildAt(0)
            if (child !is LinearLayout) {
                return
            }
            val root = child
            if (root.orientation != LinearLayout.VERTICAL || root.childCount < 5) {
                return
            }
            build(root, activity)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
            // 呈现层改动，任何意外都不能影响设置页原本可用的功能。
        }
    }

    /** 在视图树里深度优先找第一个 ScrollView（页面骨架的标志）。 */
    private fun findScrollView(v: View?): ScrollView? {
        if (v == null) {
            return null
        }
        if (v is ScrollView) {
            return v
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = findScrollView(v.getChildAt(i))
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    // 重排主流程：按标题切组 → 丢弃废弃组 → 其余包成折叠卡片。
    private fun build(box: LinearLayout, ctx: Context) {
        val n = box.childCount
        val all = ArrayList<View>(n)
        for (i in 0 until n) {
            all.add(box.getChildAt(i))
        }

        var firstTitle = -1
        for (i in all.indices) {
            if (isKnownTitle(all[i])) {
                firstTitle = i
                break
            }
        }
        if (firstTitle < 0) {
            return
        }

        box.removeAllViews()
        for (i in 0 until firstTitle) {
            box.addView(all[i])
        }

        var i = firstTitle
        while (i < all.size) {
            val v = all[i]
            if (!isKnownTitle(v)) {
                box.addView(v)
                i++
                continue
            }
            var j = i + 1
            while (j < all.size && !isKnownTitle(all[j]) && !isFooter(all[j])) {
                j++
            }
            val title = textOf(v)
            if (isDropped(title)) {
                // 整组丢弃：只从视图树摘除，不销毁控件
                i = j
                continue
            }
            if (isCard(title)) {
                addCard(box, ctx, v as TextView, all, i + 1, j, title!!)
            } else {
                box.addView(v)
                for (k in i + 1 until j) {
                    box.addView(all[k])
                }
            }
            i = j
        }
    }

    // 判断某个 View 是不是已知的分组标题（查 SettingsRegistry）。
    private fun isKnownTitle(v: View): Boolean {
        val t = textOf(v)
        if (t == null) {
            return false
        }
        return SettingsRegistry.known(t)
    }

    // 该分组是否要包成折叠卡片。
    private fun isCard(t: String?): Boolean {
        return SettingsRegistry.card(t)
    }

    // 该分组是否整组摘除。
    private fun isDropped(t: String?): Boolean {
        return SettingsRegistry.dropped(t)
    }

    // 是否是页脚「说明：」长文。
    private fun isFooter(v: View): Boolean {
        val t = textOf(v)
        return t != null && t.startsWith(FOOTER_PREFIX)
    }

    private fun textOf(v: View): String? {
        if (v !is TextView) {
            return null
        }
        val cs = v.text
        return cs?.toString()?.trim()
    }

    // 把一个分组包成可折叠卡片（头部可点、主体做高度动画）。
    private fun addCard(box: LinearLayout, ctx: Context, titleView: TextView,
                        all: List<View>, from: Int, to: Int, titleText: String) {
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.background = UiKit.cardBg(ctx)
        val pad = UiKit.dp(ctx, 16f)
        card.setPadding(pad, UiKit.dp(ctx, 4f), pad, UiKit.dp(ctx, 14f))
        val cardLp = LinearLayout.LayoutParams(-1, -2)
        cardLp.topMargin = UiKit.dp(ctx, 10f)
        card.layoutParams = cardLp

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.isClickable = true
        head.setPadding(0, UiKit.dp(ctx, 14f), 0, UiKit.dp(ctx, 12f))

        titleView.setPadding(0, 0, 0, 0)
        titleView.setTextSize(UiKit.FS_BTN)
        titleView.setTextColor(UiKit.TITLE)
        titleView.typeface = Typeface.DEFAULT_BOLD
        head.addView(titleView, LinearLayout.LayoutParams(0, -2, 1.0f))

        val arrow = UiKit.arrow(ctx)
        head.addView(arrow, LinearLayout.LayoutParams(UiKit.dp(ctx, 30f), UiKit.dp(ctx, 30f)))
        card.addView(head)

        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.VERTICAL
        for (k in from until to) {
            body.addView(all[k])
        }
        card.addView(body)

        val spec = SettingsRegistry.find(titleText)
        if (spec != null) {
            spec.decorate(ctx, body, all, from, to)
        }

        // 展开态优先取用户手动调过的记录；没调过才用注册表里的默认值。
        // 这样换主题重建界面、乃至下次冷启动，卡片都不会自己回到默认。
        val fTitle = titleText
        val appCtx = ctx.applicationContext
        val open = if (PetPrefs.hasCardOpen(appCtx, fTitle))
            PetPrefs.cardOpen(appCtx, fTitle)
        else
            (spec != null && spec.openByDefault())
        arrow.rotation = if (open) 90f else 0f
        if (open) {
            body.visibility = View.VISIBLE
        } else {
            // 收起态用高度 0 表示，交给 UiKit.expand 做展开动画。
            body.visibility = View.VISIBLE
            val blp = LinearLayout.LayoutParams(-1, 0)
            body.layoutParams = blp
            body.alpha = 0f
        }

        val fBody = body
        head.setOnClickListener {
            val lp = fBody.layoutParams
            val shown = lp != null && lp.height != 0
            UiKit.expand(fBody, arrow, !shown)
            // 记下用户这次的选择，重建 / 重启后照此还原。
            PetPrefs.setCardOpen(appCtx, fTitle, !shown)
        }

        box.addView(card)
        // 换主题重建时跳过入场动画，否则每张卡片会按序号延迟重冒一遍，看着像按钮消失。
        if (!PetPrefs.themeRestore(ctx)) {
            // 【丝滑】错峰序号加上限：卡片多时最末尾不再等太久。
            UiKit.enter(card, Math.min(box.childCount, 8) * 24)
        }
    }

    // 按文本在给定列表里找按钮。
    @JvmStatic
    fun findButton(list: List<View>, key: String): Button? {
        for (i in list.indices) {
            val b = findButton(list[i], key)
            if (b != null) {
                return b
            }
        }
        return null
    }

    @JvmStatic
    fun findButton(v: View, key: String): Button? {
        if (v is Button) {
            val s = textOf(v)
            if (s != null && s.contains(key)) {
                return v
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val b = findButton(v.getChildAt(i), key)
                if (b != null) {
                    return b
                }
            }
        }
        return null
    }

    /** 由 MainActivity 的 saveSettings 桥接调用：保存后把 prefs 回写进当前配置项。 */
    @JvmStatic
    fun onSaved(ctx: Context) {
        try {
            SettingsProfiles.syncFromPrefs(ctx)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /** 读输入框的 hint；按 hint 前缀在视图树里定位三个配置输入框时用。 */
    @JvmStatic
    fun hintOf(v: View): String? {
        if (v !is EditText) {
            return null
        }
        val cs = v.hint
        return cs?.toString()?.trim()
    }

    /** 在输入框上方插一行字段名，并把输入框换成淡紫底圆角样式。 */
    @JvmStatic
    fun addLabeled(ctx: Context, body: LinearLayout, label: String, input: EditText) {
        val t = TextView(ctx)
        t.text = label
        t.setTextSize(UiKit.FS_SUB)
        t.setTextColor(UiKit.SUB)
        t.setPadding(UiKit.dp(ctx, 2f), UiKit.dp(ctx, 12f), 0, UiKit.dp(ctx, 4f))

        input.background = UiKit.rowBg(ctx, UiKit.FIELD)
        input.setPadding(UiKit.dp(ctx, 12f), UiKit.dp(ctx, 12f), UiKit.dp(ctx, 12f), UiKit.dp(ctx, 12f))
        input.setTextSize(UiKit.FS_BTN)
        input.setTextColor(UiKit.TITLE)
        input.layoutParams = LinearLayout.LayoutParams(-1, -2)

        body.addView(t)
        body.addView(input)
    }

    /** API 卡片底部的「用量统计」入口。 */
    @JvmStatic
    fun buildTokenEntry(ctx: Context): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isClickable = true
        row.background = UiKit.rowBg(ctx, UiKit.OPTION)
        row.setPadding(UiKit.dp(ctx, 12f), UiKit.dp(ctx, 12f), UiKit.dp(ctx, 12f), UiKit.dp(ctx, 12f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = UiKit.dp(ctx, 14f)
        row.layoutParams = lp

        val t = TextView(ctx)
        t.text = "用量统计"
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.TITLE)
        t.typeface = Typeface.DEFAULT_BOLD
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))

        val go = TextView(ctx)
        go.text = "查看 Token 消耗统计"
        go.setTextSize(UiKit.FS_SUB)
        go.setTextColor(UiKit.SUB)
        row.addView(Icons.labeled(ctx, Icons.IC_CHART, 14.0f, UiKit.SUB, go, 4),
                LinearLayout.LayoutParams(-2, -2))

        row.setOnClickListener { TokenStat.open(ctx) }
        return row
    }
}
