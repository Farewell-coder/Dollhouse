package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.MemDb
import com.dollhouse.app.ui.theme.UiKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】总记忆库的独立页：列出 AI 自主（或用户要求）写下的长期记忆条目。
 *
 * 【入口】设置页「记忆」卡片里的「记忆库」按钮 → open()。
 *
 * 【交互】数据来自 MemDb（偏好键 memdb_list），与 TokenStat 一样整页代码构建、无 layout。
 *
 * 【踩坑】页面靠 tag 认领，任何 Context 都能调 closeIfOpen()；找不到 Activity 就静默返回
 *         （悬浮窗场景没有 Activity，所以入口只放在设置页里）。
 */
object MemPage {
    private const val LOG_TAG = "Dollhouse"

    private const val TAG_PAGE = "feiyu_mem_page"

    /** 返回键用：记忆库页开着就关掉并返回 true。 */
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

    /** 设置页「记忆库」按钮的落地动作。 */
    fun open(ctx: Context) {
        try {
            val act = findActivity(ctx) ?: return
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            val page = buildPage(act, content)
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

    private fun buildPage(act: Activity, content: ViewGroup): View {
        val ctx: Context = act

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(UiKit.BG)
        val pad = dp(ctx, 16)
        // 【顶部不再留白】同 TokenStat：紧随其后的 UiKit.topBar 已自带 statusBarPad，
        //   页盒顶部再留 10dp 就是二次叠加。顶部归零。
        box.setPadding(pad, 0, pad, dp(ctx, 20))

        // 顶栏：统一走 UiKit.topBar
        val bar = UiKit.topBar(ctx, "记忆库", "她记下的事", View.OnClickListener {
            closeIfOpen(act)
        })
        box.addView(bar)

        val arr = MemDb.list(ctx)
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            box.addView(item(ctx, o))
        }
        // 【丝滑】列表项错峰淡入，不再一次性铺满。
        UiKit.staggerCapped(box, 6)
        if (arr.length() == 0) {
            val empty = card(ctx)
            val e = TextView(ctx)
            e.text = "\u8fd8\u6ca1\u6709\u8bb0\u4e0b\u4ec0\u4e48"
            e.setTextSize(UiKit.FS_BTN)
            e.setTextColor(UiKit.SUB)
            empty.addView(e)
            box.addView(empty)
        }

        val note = TextView(ctx)
        note.setTextSize(UiKit.FS_TINY)
        note.setTextColor(UiKit.SUB)
        note.setLineSpacing(dp(ctx, 3).toFloat(), 1.0f)
        note.setPadding(dp(ctx, 2), dp(ctx, 14), dp(ctx, 2), 0)
        note.text = "\u5979\u5728\u804a\u5929\u91cc\u89c9\u5f97\u503c\u5f97\u8bb0\u7684\u4e8b\u4f1a\u81ea\u5df1\u5199\u8fdb\u6765\uff0c" +
            "\u4e0b\u6b21\u5f00\u53e3\u524d\u4f1a\u5e26\u4e0a\u8fd9\u4e9b\u5185\u5bb9\u3002\n" +
            "\u5171 " + arr.length() + " \u6761\uff0c\u6700\u591a\u4fdd\u7559 " + MemDb.MAX_ENTRIES + " \u6761\uff0c" +
            "\u8d85\u51fa\u65f6\u4e22\u6700\u65e7\u7684\u3002\u6570\u636e\u53ea\u5728\u8fd9\u53f0\u8bbe\u5907\u4e0a\u3002"
        box.addView(note)

        val sc = ScrollView(ctx)
        sc.setBackgroundColor(UiKit.BG)
        sc.addView(box, ViewGroup.LayoutParams(-1, -2))
        return sc
    }

    /** 一条记忆：标题 + 时间 + 正文 + 删除。 */
    private fun item(ctx: Context, o: JSONObject): LinearLayout {
        val c = card(ctx)

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val title = o.optString("title", "")
        val name = TextView(ctx)
        name.text = if (title.isEmpty()) "\uff08\u65e0\u6807\u9898\uff09" else title
        name.setTextSize(UiKit.FS_BTN)
        name.setTextColor(UiKit.TITLE)
        name.typeface = Typeface.DEFAULT_BOLD
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        head.addView(name, LinearLayout.LayoutParams(0, -2, 1.0f))

        val id = o.optString("id", "")
        val del = TextView(ctx)
        del.text = "\u5220\u9664"
        del.setTextSize(UiKit.FS_SUB)
        del.setTextColor(UiKit.ERR)
        del.typeface = Typeface.DEFAULT_BOLD
        val p = dp(ctx, 8)
        del.setPadding(p, dp(ctx, 4), p, dp(ctx, 4))
        del.isClickable = true
        UiKit.press(del)
        del.setOnClickListener {
            MemDb.remove(ctx.applicationContext, id)
            val act = findActivity(ctx)
            if (act != null) {
                closeIfOpen(act)
                open(act)
            }
        }
        head.addView(del, LinearLayout.LayoutParams(-2, -2))
        c.addView(head)

        val time = TextView(ctx)
        time.text = fmtTime(o.optLong("ts", 0L))
        time.setTextSize(UiKit.FS_TINY)
        time.setTextColor(UiKit.SUB)
        time.setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
        c.addView(time)

        val body = TextView(ctx)
        body.text = o.optString("text", "")
        body.setTextSize(UiKit.FS_BTN)
        body.setTextColor(UiKit.TITLE)
        body.setLineSpacing(dp(ctx, 3).toFloat(), 1.0f)
        c.addView(body)
        return c
    }

    private fun fmtTime(ts: Long): String {
        if (ts <= 0L) {
            return ""
        }
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))
        } catch (unused: Throwable) {
            ""
        }
    }

    private fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = UiKit.cardBg(ctx)
        c.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
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
