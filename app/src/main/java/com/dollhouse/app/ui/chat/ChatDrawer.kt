package com.dollhouse.app.ui.chat

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.anim.Springs
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.dollhouse.app.ui.theme.Fonts

/**
 * 【职责】聊天面板左侧抽屉：对话列表（新建 / 切换 / 重命名 / 删除）。
 *
 * 【入口】ChatPanel 的「☰」入口调 open()；底部操作条复用本类的 confirm / prompt。
 *
 * 【交互】会话数据走 ChatSessions，当前会话历史由 ChatPanel 持有；
 *        切换会话后由 ChatPanel.reloadHistory 重铺气泡。
 *
 * 【扩展】再加一个分区（比如收藏）只需在 buildContent 里多 add 一段，不用动挂载逻辑。
 *
 * 【坑】本类不自建 Activity，也不用系统 Dialog —— 桌宠模式（宿主 PetService，走迷你输入框）
 *        的 Context 链里没有 Activity，系统弹窗拿不到 window token。所以遮罩、确认框、输入框
 *        全部是叠在 layer（浮窗 root / Activity 的 content）上的普通 View。
 *        挂载层是从 ChatPanel 的父链里往上找的第一个 FrameLayout，ChatPanel 自身的
 *        父子结构因此不需要改。
 */
class ChatDrawer(private val host: ChatPanel) {

    private var layer: ViewGroup? = null
    private var shade: FrameLayout? = null
    private var content: LinearLayout? = null

    private fun dp(f: Float): Int {
        return UiKit.dp(host.context, f)
    }

    fun isOpen(): Boolean {
        val frame = shade
        return frame != null && frame.parent != null
    }

    fun layer(): ViewGroup? {
        return layer
    }

    /** 在给定层上铺开抽屉（重复调用只会重建内容）。 */
    fun open(parent: ViewGroup) {
        layer = parent
        val ctx = host.context
        val sh = shade ?: run {
            val created = FrameLayout(ctx)
            created.setBackgroundColor(UiKit.SCRIM)
            created.isClickable = true
            created.setOnClickListener {
                close()
            }
            shade = created
            created
        }
        if (sh.parent != null) {
            (sh.parent as ViewGroup).removeView(sh)
        }
        // 【坑】shade 是复用的：不清子 View 的话，每次 open() 都会再叠一层 panel，
        // 旧 panel 上的点击监听跟着一起累积。摘 shade 只断开它和父级的关系，子级还在。
        sh.removeAllViews()
        val slp = FrameLayout.LayoutParams(-1, -1)
        parent.addView(sh, slp)

        val panel = LinearLayout(ctx)
        panel.orientation = LinearLayout.VERTICAL
        // 【全局背景】抽屉自身铺同一张背景图 + 可读性遮罩；opaqueBase=true 在图片下垫一层不透明底，
        //   避免透出下层聊天区那张同款图造成「叠图」。未设背景时退回卡片底色。
        GlobalBackground.install(panel, UiKit.CARD, true)
        panel.isClickable = true
        val pad = dp(12.0f)
        // 【沉浸安全区】状态栏已沉浸，顶部 / 左右只需让开物理 cutout（刘海 / 挖孔），基础间距不变。
        UiKit.bindCutoutPadding(panel, pad, dp(14.0f), pad, dp(10.0f))

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(ctx)
        title.text = "对话"
        title.setTextSize(UiKit.FS_TITLE)
        title.setTextColor(UiKit.TITLE)
        title.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1.0f))
        val add = smallButton(ctx, "＋ 新建")
        add.setOnClickListener {
            ChatSessions.create(host.context)
            host.reloadHistory()
            refresh()
        }
        head.addView(add)
        panel.addView(head)
        // 【v0.0.1】人偶专属入口：固定顶格在标题行下方。
        //   点一下切到「人偶」会话 —— 以后这个会话里说的话就是直接跟人偶说；
        //   其他会话的回复不再往人偶头顶气泡上送（闸门见 ChatPanel 的 PetBus.say）。
        val petEntry = flatButton(ctx, "与人偶的对话")
        // 【图标语义】用星标区分「当前在这个池里」：实心亮星 = 已切到人偶池，空心灰星 = 还在自聊池。
        //   比裸 ● 更能一眼分辨，也与聊天抽屉整体的 Lucide 描边风格一致。
        Icons.stateIcon(petEntry, if (ChatSessions.isPet(ctx)) Icons.IC_STAR else Icons.IC_STAR_OFF,
                if (ChatSessions.isPet(ctx)) UiKit.ACC else UiKit.SUB, 14.0f, 5)
        val pelp = LinearLayout.LayoutParams(-1, -2)
        pelp.topMargin = dp(8.0f)
        petEntry.layoutParams = pelp
        petEntry.setOnClickListener {
            // 【池切换】点一下进人偶池（前导 ● 点亮），再点一下回自聊池（● 消失）。
            //   不关抽屉：留着让用户直接看到圆圈变化与列表随池切换。
            if (ChatSessions.isPet(ctx)) {
                ChatSessions.setPool(ctx, ChatSessions.POOL_SELF)
            } else {
                ChatSessions.ensurePet(ctx)
                ChatSessions.setPool(ctx, ChatSessions.POOL_PET)
            }
            host.reloadHistory()
            refresh()
            // 【同步图标】池切换后星标要跟着变（实心 / 空心），只改文字会留下过期的旧状态。
            val petOn = ChatSessions.isPet(ctx)
            petEntry.text = "与人偶的对话"
            Icons.stateIcon(petEntry, if (petOn) Icons.IC_STAR else Icons.IC_STAR_OFF,
                    if (petOn) UiKit.ACC else UiKit.SUB, 14.0f, 5)
        }
        panel.addView(petEntry)

        val sc = ScrollView(ctx)
        val contentBox = LinearLayout(ctx)
        contentBox.orientation = LinearLayout.VERTICAL
        sc.addView(contentBox, ViewGroup.LayoutParams(-1, -2))
        panel.addView(sc, LinearLayout.LayoutParams(-1, 0, 1.0f))
        content = contentBox

        val actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.HORIZONTAL
        val alp = LinearLayout.LayoutParams(-1, -2)
        alp.topMargin = dp(8.0f)
        actions.layoutParams = alp
        // 【v2.3】「立即总结」已搬到加号面板的「记忆」页（与「打开记忆库」同行），此处只留一枚整行按钮。
        val clear = flatButton(ctx, "清空当前对话")
        val clp = LinearLayout.LayoutParams(-1, -2)
        clear.layoutParams = clp
        clear.setOnClickListener {
            close()
            host.confirmClearChat()
        }
        actions.addView(clear)
        panel.addView(actions)

        val plp = FrameLayout.LayoutParams(
                (parent.resources.displayMetrics.widthPixels * W_RATIO).toInt(), -1)
        plp.gravity = Gravity.START
        sh.addView(panel, plp)
        refresh()
        // 抽屉：遮罩淡入 + 面板自左侧推入（原先瞬现）。
        panel.post {
            UiKit.slideInLeft(sh, panel)
        }
    }

    fun close() {
        val view = shade
        if (view != null && view.parent != null) {
            UiKit.slideOutLeft(view)
        }
    }

    /** 重建抽屉内容（会话清单 + 摘要清单）。 */
    fun refresh() {
        val box = content
        if (box == null) {
            return
        }
        val ctx = host.context
        // 【丝滑】重铺前记住滚动位置，铺完恢复，避免刷新后跳回顶部。
        val keepSc = box.parent as? ScrollView
        val keepY = keepSc?.scrollY ?: 0
        box.removeAllViews()

        val cur = ChatSessions.currentId(ctx)
        val arr = ChatSessions.list(ctx)
        section(ctx, box, "历史对话（" + arr.length() + "）")
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            box.addView(convRow(ctx, o, id == cur))
        }

        // 【丝滑】行分批淡入；并恢复重铺前的滚动位置。
        UiKit.staggerCapped(box, 10)
        if (keepSc != null) {
            keepSc.post {
                keepSc.scrollTo(0, keepY)
            }
        }
        // 【v2.3】「本会话摘要」分区已移除：摘要统一由聊天记录里的「ⓘ 历史对话摘要」呈现。
    }

    private fun convRow(ctx: Context, o: JSONObject, active: Boolean): LinearLayout {
        val id = o.optString("id")
        val title = o.optString("title", "新的对话")
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.VERTICAL
        row.background = UiKit.round(if (active) UiKit.CHAT_CHIP_ON else UiKit.SOFT, ctx, 10)
        UiKit.press(row)
        val pad = dp(12.0f)
        row.setPadding(pad, dp(10.0f), pad, dp(10.0f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(6.0f)
        row.layoutParams = lp

        val line = LinearLayout(ctx)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        val name = TextView(ctx)
        name.text = title
        name.setTextSize(UiKit.FS_BTN)
        name.setTextColor(UiKit.TITLE)
        name.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        name.isSingleLine = true
        name.ellipsize = TextUtils.TruncateAt.END
        // 【图标语义】当前会话不再靠裸 ● 表示，改用实心 / 空心星标（与顶部「与人偶的对话」同一套口径）。
        Icons.stateIcon(name, if (active) Icons.IC_STAR else Icons.IC_STAR_OFF,
                if (active) UiKit.ACC else UiKit.SUB, 13.0f, 5)
        line.addView(name, LinearLayout.LayoutParams(0, -2, 1.0f))
        val edit = smallButton(ctx, "改名")
        edit.setOnClickListener {
            prompt("重命名对话", title, object : OnText {
                override fun onText(value: String) {
                    ChatSessions.rename(ctx, id, value)
                    refresh()
                }
            })
        }
        line.addView(edit)
        val del = smallButton(ctx, "删除")
        val dlp = LinearLayout.LayoutParams(-2, -2)
        dlp.leftMargin = dp(6.0f)
        del.layoutParams = dlp
        del.setOnClickListener {
            confirm("删除对话", "「" + title + "」的聊天记录会被永久删掉，不能恢复。",
                    "删除", Runnable {
                        ChatSessions.delete(ctx, id)
                        host.reloadHistory()
                        refresh()
                    })
        }
        line.addView(del)
        row.addView(line)

        val meta = TextView(ctx)
        meta.typeface = Fonts.ui(ctx)
        meta.text = fmtTime(o.optLong("updated", 0L))
        meta.setTextSize(UiKit.FS_TINY)
        meta.setTextColor(UiKit.SUB)
        meta.setPadding(0, dp(4.0f), 0, 0)
        row.addView(meta)

        row.setOnClickListener {
            if (id == ChatSessions.currentId(ctx)) {
                close()
                return@setOnClickListener
            }
            ChatSessions.switchTo(ctx, id)
            host.reloadHistory()
            close()
        }
        return row
    }

    private fun section(ctx: Context, box: LinearLayout, text: String) {
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(UiKit.FS_SUB)
        t.setTextColor(UiKit.SUB)
        t.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        t.setPadding(dp(4.0f), dp(14.0f), 0, 0)
        box.addView(t)
    }

    private fun smallButton(ctx: Context, text: String): TextView {
        return UiKit.chip(ctx, text)
    }

    private fun flatButton(ctx: Context, text: String): Button {
        return UiKit.btn(ctx, text, false)
    }

    /* ------------------------- 通用确认框 / 输入框 ------------------------- */

    fun interface OnText {
        fun onText(value: String)
    }

    /** 叠一层确认框。没有挂载层时静默忽略（不该发生：抽屉一定先打开过）。 */
    fun confirm(title: String, message: String, okText: String, onOk: Runnable?) {
        val parent = layer
        if (parent == null) {
            return
        }
        val ctx = host.context
        val sh = newDialogShell(ctx, title)

        val t2 = TextView(ctx)
        t2.typeface = Fonts.ui(ctx)
        t2.text = message
        t2.setTextSize(UiKit.FS_SUB)
        t2.setTextColor(UiKit.SUB)
        t2.setLineSpacing(UiKit.dp(host.context, 3).toFloat(), 1.0f)
        t2.setPadding(0, dp(10.0f), 0, 0)
        sh.box.addView(t2)

        addCancel(sh, ctx)
        addOk(sh, ctx, okText, onOk)
        mountDialog(parent, sh)
    }

    /** 叠一层输入框（用于重命名）。 */
    fun prompt(title: String, initial: String?, onText: OnText?) {
        val parent = layer
        if (parent == null) {
            return
        }
        val ctx = host.context
        val sh = newDialogShell(ctx, title)

        val input = EditText(ctx)
        input.typeface = Fonts.ui(ctx)
        input.setText(initial ?: "")
        input.isSingleLine = true
        UiKit.field(input, ctx)
        val ilp = LinearLayout.LayoutParams(-1, -2)
        ilp.topMargin = dp(12.0f)
        input.layoutParams = ilp
        sh.box.addView(input)

        addCancel(sh, ctx)
        val ok = UiKit.dialogButton(ctx, "确定", true)
        val olp = LinearLayout.LayoutParams(-2, -2)
        olp.leftMargin = dp(10.0f)
        ok.layoutParams = olp
        ok.setOnClickListener {
            val value = input.text?.toString()?.trim() ?: ""
            UiKit.fadeOutRemove(sh.overlay)
            onText?.onText(value)
        }
        sh.bar.addView(ok)
        mountDialog(parent, sh)
    }

    /** 弹层骨架的句柄：遮罩、卡片、按钮条。 */
    private class DialogShell {
        lateinit var overlay: FrameLayout
        lateinit var box: LinearLayout
        lateinit var bar: LinearLayout
    }

    /** 建一个弹层骨架：遮罩（拦点击）+ 卡片（圆角、内边距）+ 加粗标题 + 空按钮条。 */
    private fun newDialogShell(ctx: Context, title: String): DialogShell {
        val sh = DialogShell()
        sh.overlay = FrameLayout(ctx)
        sh.overlay.setBackgroundColor(UiKit.SCRIM)
        sh.overlay.isClickable = true
        sh.box = LinearLayout(ctx)
        sh.box.orientation = LinearLayout.VERTICAL
        sh.box.background = UiKit.round(UiKit.card(), ctx, 16)
        val pad = dp(18.0f)
        sh.box.setPadding(pad, pad, pad, dp(14.0f))
        sh.box.isClickable = true
        val t1 = TextView(ctx)
        t1.text = title
        t1.setTextSize(UiKit.FS_TITLE)
        t1.setTextColor(UiKit.TITLE)
        t1.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        sh.box.addView(t1)
        sh.bar = LinearLayout(ctx)
        sh.bar.orientation = LinearLayout.HORIZONTAL
        sh.bar.gravity = Gravity.END
        sh.bar.setPadding(0, dp(16.0f), 0, 0)
        return sh
    }

    /** 取消键：仅淡出移除遮罩。 */
    private fun addCancel(sh: DialogShell, ctx: Context) {
        val cancel = UiKit.dialogButton(ctx, "取消", false)
        cancel.setOnClickListener {
            UiKit.fadeOutRemove(sh.overlay)
        }
        sh.bar.addView(cancel)
    }

    /** 确认键：先淡出移除遮罩，再跑回调。 */
    private fun addOk(sh: DialogShell, ctx: Context, okText: String, onOk: Runnable?) {
        val ok = UiKit.dialogButton(ctx, okText, true)
        val olp = LinearLayout.LayoutParams(-2, -2)
        olp.leftMargin = dp(10.0f)
        ok.layoutParams = olp
        ok.setOnClickListener {
            UiKit.fadeOutRemove(sh.overlay)
            onOk?.run()
        }
        sh.bar.addView(ok)
    }

    /** 挂载弹层：按钮条入卡片、固定屏宽 86% 居中、遮罩淡入 + 卡片弹簧放大。 */
    private fun mountDialog(parent: ViewGroup, sh: DialogShell) {
        sh.box.addView(sh.bar)
        val blp = FrameLayout.LayoutParams(
                (parent.resources.displayMetrics.widthPixels * 0.86f).toInt(), -2)
        blp.gravity = Gravity.CENTER
        sh.overlay.addView(sh.box, blp)
        parent.addView(sh.overlay, FrameLayout.LayoutParams(-1, -1))
        // 【丝滑】弹层淡入 + 卡片轻微放大，不再瞬现。
        sh.overlay.alpha = 0f
        sh.box.scaleX = 0.94f
        sh.box.scaleY = 0.94f
        sh.overlay.animate().alpha(1f).setDuration(UiKit.D_LAYER.toLong()).setInterpolator(UiKit.EASE_DECEL).start()
        // 【弹簧】卡片放大走 snappy（ζ=0.73），与 overlay 淡入同帧开始；
        //   overlay 的 alpha 保持线性淡入不动（透明度过冲会穿帮）。
        Springs.drive(Springs.snappy(), object : Springs.Listener {
            override fun onUpdate(p: Float) {
                val s = Springs.lerp(0.94f, 1.0f, p)
                sh.box.scaleX = s
                sh.box.scaleY = s
            }

            override fun onEnd() {
                sh.box.scaleX = 1f
                sh.box.scaleY = 1f
            }
        })
    }

    companion object {
        /** 抽屉宽度占屏幕宽度的比例。 */
        private const val W_RATIO = 0.78f

        private fun fmtTime(ts: Long): String {
            if (ts <= 0L) {
                return "—"
            }
            return try {
                SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))
            } catch (unused: Throwable) {
                "—"
            }
        }
    }
}
