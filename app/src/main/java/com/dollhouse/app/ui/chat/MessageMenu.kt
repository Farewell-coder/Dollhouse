package com.dollhouse.app.ui.chat

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】聊天页的**消息长按菜单**与**输入弹窗**（r13 新增）。
 *
 * 【为什么单开一个文件、不用 Compose 的 DhForm.Alert】
 *   菜单是从「气泡长按回调」里弹出来的 —— 那是**命令式**调用点，
 *   而 `DhForm.Alert` / `DhForm.Input` 都标了 `@Composable`，
 *   只能在 Compose 作用域里声明，从回调里调会编译不过。
 *   本类用原生 `Dialog` + `UiKit` 的既有基础件（`sheet()` 底、`dp()` 尺寸）搭，
 *   视觉口径与工程其它弹窗一致（同一套磨砂底、同一套圆角）。
 *
 * 【为什么不用 UiKit.showDialog】
 *   那个只支持「两个按钮 + 一个正文 View」，而长按菜单有 5~6 个可点项。
 *   与其给它加重载（会牵动所有既有调用点），不如在这里独立实现一个列表式菜单。
 */
object MessageMenu {

    /**
     * 弹一个列表式操作菜单。
     *
     * @param title   标题（这里是消息摘要，让用户确认操作的是哪一条）
     * @param labels  各菜单项文案
     * @param actions 与 [labels] 一一对应的动作；点完自动关弹窗
     * @param iconRes 与 [labels] 一一对应的图标资源（Icons.IC_*）；0 = 该项不配图标。
     *                【r17】用户要求「删除」这类按键用垃圾桶图标而非纯文字。
     *                传 null / 长度不足时按 0 处理，与原行为逐像素一致。
     */
    @JvmStatic
    @JvmOverloads
    fun show(act: Activity?, title: String?, labels: List<String>, actions: List<() -> Unit>,
             iconRes: IntArray? = null) {
        if (act == null || labels.isEmpty()) {
            return
        }
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.background = UiKit.round(UiKit.sheet(), act, 16f)
        val pad = UiKit.dp(act, 18)
        col.setPadding(pad, pad, pad, UiKit.dp(act, 12))

        if (!title.isNullOrEmpty()) {
            val tv = TextView(act)
            tv.text = title
            tv.setTextColor(UiKit.TITLE)
            tv.textSize = 15f
            tv.setPadding(0, 0, 0, UiKit.dp(act, 10))
            col.addView(tv)
        }

        // 【可滚动】菜单项最多 6 条，窄屏 + 大字体时可能顶出屏幕；
        //   套一层 ScrollView，超出时内部滚动而不是被裁掉。
        val sc = ScrollView(act)
        val list = LinearLayout(act)
        list.orientation = LinearLayout.VERTICAL
        sc.addView(list)
        for (i in labels.indices) {
            val icon = iconRes?.getOrNull(i) ?: 0
            list.addView(menuRow(act, labels[i], icon, i == 0) { v ->
                dlg.dismiss()
                try {
                    actions.getOrNull(i)?.invoke()
                } catch (ignored: Throwable) {
                }
            })
        }
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.topMargin = UiKit.dp(act, 4)
        sc.layoutParams = slp
        col.addView(sc)

        dlg.setContentView(col)
        // 【宽度】与其它弹窗一致：左右各留 28dp，不铺满。
        val w = act.resources.displayMetrics.widthPixels - UiKit.dp(act, 56)
        dlg.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.show()
    }

    /**
     * 一行菜单项：整行可点、左对齐、按下有反馈色。
     *
     * 【r17·图标】[iconRes] 非 0 时在文字左侧挂一枚 Lucide 图标（如「删除」→ 垃圾桶），
     *   整行仍是同一个可点区域，不改变点击语义与行高。
     */
    private fun menuRow(act: Activity, text: String, iconRes: Int, first: Boolean,
                        onClick: (View) -> Unit): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isClickable = true
        row.setOnClickListener(onClick)
        row.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 13), UiKit.dp(act, 14), UiKit.dp(act, 13))
        if (iconRes != 0) {
            val iv = com.dollhouse.app.ui.theme.Icons.view(act, iconRes, 18f, UiKit.TITLE)
            val ivp = LinearLayout.LayoutParams(UiKit.dp(act, 18), UiKit.dp(act, 18))
            ivp.rightMargin = UiKit.dp(act, 10)
            row.addView(iv, ivp)
        }
        val tv = TextView(act)
        tv.text = text
        tv.textSize = 15f
        tv.setTextColor(UiKit.TITLE)
        tv.gravity = Gravity.CENTER_VERTICAL
        row.addView(tv)
        // 【分隔线】第一项不加（紧贴标题），其余加一条淡线，视觉上把条目切开。
        val lp = LinearLayout.LayoutParams(-1, -2)
        if (!first) {
            lp.topMargin = UiKit.dp(act, 2)
        }
        row.layoutParams = lp
        return row
    }

    /**
     * 弹一个「输入一行文字」的弹窗。
     *
     * 【用途】「插入总结」要用户写摘要内容；工程里没有现成的输入弹窗（`DhForm.Input`
     *   是 Compose 的，命令式调用点用不了），所以在这里补一个最小的。
     *
     * @param hint 输入框的提示文案
     * @param done 点「确定」时回调输入内容（已 trim；空串不回调）
     */
    @JvmStatic
    fun askText(act: Activity?, title: String, hint: String, done: (String) -> Unit) {
        if (act == null) {
            return
        }
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.background = UiKit.round(UiKit.sheet(), act, 16f)
        val pad = UiKit.dp(act, 18)
        col.setPadding(pad, pad, pad, UiKit.dp(act, 14))

        val tv = TextView(act)
        tv.text = title
        tv.setTextColor(UiKit.TITLE)
        tv.textSize = 16f
        col.addView(tv)

        val et = EditText(act)
        et.hint = hint
        et.setTextColor(UiKit.TITLE)
        et.setHintTextColor(UiKit.SUB)
        et.textSize = 15f
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.minLines = 2
        et.gravity = Gravity.TOP or Gravity.START
        et.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10))
        et.background = UiKit.round(UiKit.card(), act, 10f)
        val elp = LinearLayout.LayoutParams(-1, -2)
        elp.topMargin = UiKit.dp(act, 12)
        et.layoutParams = elp
        col.addView(et)

        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.RIGHT
        val blp = LinearLayout.LayoutParams(-1, -2)
        blp.topMargin = UiKit.dp(act, 16)
        bar.layoutParams = blp
        val cancel = UiKit.dialogButton(act, "取消", false)
        cancel.setOnClickListener { dlg.dismiss() }
        val ok = UiKit.dialogButton(act, "确定", true)
        ok.setOnClickListener {
            val t = et.text.toString().trim()
            dlg.dismiss()
            if (t.isNotEmpty()) {
                try {
                    done(t)
                } catch (ignored: Throwable) {
                }
            }
        }
        bar.addView(cancel)
        val okp = LinearLayout.LayoutParams(-2, -2)
        okp.leftMargin = UiKit.dp(act, 8)
        ok.layoutParams = okp
        bar.addView(ok)
        col.addView(bar)

        dlg.setContentView(col)
        val w = act.resources.displayMetrics.widthPixels - UiKit.dp(act, 56)
        dlg.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.show()
        et.requestFocus()
    }
}

/**
 * 【职责】消息**多选**态的跨气泡状态（r13）。
 *
 * 【为什么需要共享状态】多选是「列表级」行为：进入多选后，点**任意**气泡都是勾选，
 *   而每个气泡是各自独立的 Composable，互相看不到对方的状态。
 *   放在这里当全局状态，气泡只需读 [on] / 调 [toggle]。
 *
 * 【为什么不用 Compose 状态】多选由**命令式**的长按菜单触发（见 [MessageMenu]），
 *   不是 Composable 内部事件；用普通对象最直接。
 *   Compose 侧读它不会自动重组，但多选的视觉反馈只在「选中项」上体现，
 *   而选中项变化必然伴随列表重组（`ChatState.items` 是 Compose 状态），够用。
 *
 * 【怎么退出】再次长按任意消息 → 菜单里再点「多选」= 取消；或发消息 / 切会话时由
 *   调用方 [reset]。当前实现刻意做得最简：不额外加 UI 元素。
 */
object MultiSelect {
    /** 是否处于多选态。 */
    @JvmField
    var on: Boolean = false

    /** 已勾选的消息下标（对应 `ChatPanel.history` 的下标）。 */
    private val picked = LinkedHashSet<Int>()

    /** 勾选 / 取消勾选一条。 */
    @JvmStatic
    fun toggle(index: Int) {
        if (index < 0) {
            return
        }
        if (!picked.add(index)) {
            picked.remove(index)
        }
    }

    /** 当前已勾选的下标快照（升序）。 */
    @JvmStatic
    fun picked(): List<Int> {
        val out = ArrayList<Int>(picked)
        out.sort()
        return out
    }

    /** 退出多选并清空勾选。 */
    @JvmStatic
    fun reset() {
        on = false
        picked.clear()
    }
}