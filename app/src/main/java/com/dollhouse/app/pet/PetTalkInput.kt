package com.dollhouse.app.pet

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.theme.Fonts

/**
 * 【职责】三击人偶后出现的迷你输入框：一个输入框 + 一个发送键，挂在 WindowManager 上的独立悬浮窗。
 *
 * 【入口】只由 PetService 调用（show / hide / setBusy / moveTo / release）。
 *
 * 【交互】落点由 PetService 算好（宽 = 人偶宽、位于人偶正下方），本类只负责显示与收键盘；
 *          任何收起路径都回调 Host.onClosed，让宿主判断该不该退掉人偶的趴姿。
 *
 * 【扩展】改样式只动 build()；改收起时长只动 AUTO_HIDE_MS。
 *
 * 【收起规则】框里有未发送的内容时永不自动收起（TextWatcher + scheduleAutoHide 双重保证），
 *             空框 60 秒无操作才收。这样长文本输入不会再被中途吞掉。
 *
 * 【坑】窗口 flags 不能含 FLAG_NOT_FOCUSABLE，
 *        EditText 才拿得到焦点、键盘才弹得出来；摘窗之前要先 hideSoftInputFromWindow。
 */
class PetTalkInput internal constructor(
    private val ctx: Context,
    private val host: Host
) {
    companion object {
        /** 输入框高度（dp）。 */
        internal const val HEIGHT_DP = 46.0f

        /**
         * 无操作自动收起时长。
         * 【坑】旧值 15 秒太短：打字时软键盘是独立窗口，点它不会重置计时，
         *   打满 15 秒整组（含已输入的文字）会被收掉 —— 用户报的「根本无法正常输入」。
         *   现在配合 TextWatcher：框内有内容时根本不排计时，清空 / 发出后才重新计时。
         */
        private const val AUTO_HIDE_MS = 60000L
    }

    interface Host {
        /** 收起时回调（发送 / 超时 / 点人偶等所有路径）。 */
        fun onClosed()

        /** 点发送键或回车：文本已 trim，非空。 */
        fun onSend(text: String)

        /** 输入法高度变化（0 = 收起）。宿主需要据此把人偶与输入框整组上移。 */
        fun onIme(imeBottom: Int)
    }

    private val wm: WindowManager = ctx.getSystemService("window") as WindowManager
    private val dm: DisplayMetrics = ctx.resources.displayMetrics
    private val ui = Handler(Looper.getMainLooper())
    private var lp: WindowManager.LayoutParams? = null
    private var root: LinearLayout? = null
    private var input: EditText? = null
    private var button: ImageView? = null
    private var added = false
    private val autoHide = Runnable { hide() }

    internal fun isShowing(): Boolean {
        return added
    }

    /** 显示输入框；x / y / w 由宿主算好（已夹进屏幕）。 */
    internal fun show(x: Int, y: Int, w: Int) {
        if (added) {
            focus()
            return
        }
        build()
        lp = WindowManager.LayoutParams(w, UiKit.dp(ctx, HEIGHT_DP), overlayType(), 16777248, -3)
        lp!!.gravity = 8388659
        // 【坑】16 = 只有 ADJUST_RESIZE，没有 STATE_VISIBLE，悬浮窗的键盘可能不弹。
        //   20 = ADJUST_RESIZE | STATE_VISIBLE。
        lp!!.softInputMode = 20
        lp!!.x = Math.max(0, x)
        lp!!.y = Math.max(0, y)
        try {
            wm.addView(root, lp)
            added = true
        } catch (unused: Throwable) {
            added = false
            root = null
            return
        }
        focus()
        scheduleAutoHide()
    }

    /** 收起：藏键盘 + 摘窗 + 回调宿主。 */
    internal fun hide() {
        if (!added) {
            return
        }
        added = false
        ui.removeCallbacks(autoHide)
        hideIme()
        try {
            wm.removeView(root)
        } catch (unused: Throwable) {
        }
        root = null
        input = null
        button = null
        host.onClosed()
    }

    /**
     * 收起但不回调宿主：宿主自己接管后续。
     * 【为什么需要】发完消息约定「只收输入框、人偶继续趴着等回复」，
     *   而 hide() 会回调 onClosed，宿主默认语义是「整组退掉（exitPeek）」——
     *   会把刚摆好的趴姿与「思考中」气泡一起清掉，所以发完这一步必须用静默收窗。
     */
    internal fun hideQuiet() {
        if (!added) {
            return
        }
        added = false
        ui.removeCallbacks(autoHide)
        hideIme()
        try {
            wm.removeView(root)
        } catch (unused: Throwable) {
        }
        root = null
        input = null
        button = null
    }

    /** 服务销毁：不回调宿主（宿主正在拆自己）。 */
    internal fun release() {
        added = false
        ui.removeCallbacksAndMessages(null)
        if (root != null) {
            hideIme()
            try {
                wm.removeView(root)
            } catch (unused: Throwable) {
            }
        }
        root = null
        input = null
        button = null
    }

    /** 请求在途时禁用发送键，避免连点重复发。 */
    internal fun setBusy(z: Boolean) {
        val textView = button ?: return
        textView.isEnabled = !z
        textView.alpha = if (z) 0.5f else 1.0f
    }

    /** 人偶移动 / 比例变化后重新落位（坐标由宿主算好）。 */
    internal fun moveTo(x: Int, y: Int, w: Int) {
        if (!added || lp == null) {
            return
        }
        lp!!.width = w
        lp!!.x = Math.max(0, x)
        lp!!.y = Math.max(0, y)
        try {
            wm.updateViewLayout(root, lp)
        } catch (unused: Throwable) {
        }
    }

    private fun build() {
        val bar = LinearLayout(ctx)
        bar.orientation = 0
        bar.gravity = 16
        val pad = UiKit.dp(ctx, 6.0f)
        bar.setPadding(pad, pad, pad, pad)
        bar.background = UiKit.roundStroke(UiKit.card(), UiKit.CHAT_BORDER, ctx, 14.0f)
        bar.elevation = UiKit.dp(ctx, 6.0f).toFloat()
        buildInput(bar)
        buildSendButton(bar)
        attachImeListener(bar)
        root = bar
    }

    /** 输入框本体：单行、回车发送；内容变化与触摸都重置自动收起计时。 */
    private fun buildInput(bar: LinearLayout) {
        val editText = EditText(ctx)
        editText.typeface = Fonts.ui(ctx)
        editText.isSingleLine = true
        editText.hint = "说点什么…"
        editText.setHintTextColor(UiKit.SUB)
        editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        editText.imeOptions = EditorInfo.IME_ACTION_SEND
        editText.setBackgroundColor(Color.TRANSPARENT)
        editText.setTextSize(UiKit.FS_BTN)
        editText.setTextColor(UiKit.TITLE)
        editText.setPadding(UiKit.dp(ctx, 8.0f), 0, UiKit.dp(ctx, 8.0f), 0)
        editText.setOnEditorActionListener { _, i, keyEvent ->
            if (i == 4) {
                submit()
                return@setOnEditorActionListener true
            }
            if (keyEvent == null || keyEvent.keyCode != 66) {
                return@setOnEditorActionListener false
            }
            submit()
            true
        }
        // 【核心修复】原来只有 ACTION_DOWN 会重置计时，软键盘是另一个窗口、点它不算，
        //   于是「打满 15 秒」必然被收窗，已输入的文字一起丢。这里补 TextWatcher，
        //   每次内容变化都重新计时；再配合 scheduleAutoHide 的「有内容不排计时」，
        //   只要框里还有字就永远不会自动收起。
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence?, i: Int, i2: Int, i3: Int) {
            }

            override fun onTextChanged(charSequence: CharSequence?, i: Int, i2: Int, i3: Int) {
            }

            override fun afterTextChanged(editable: Editable?) {
                scheduleAutoHide()
            }
        })
        editText.setOnTouchListener { _, motionEvent ->
            if (motionEvent.actionMasked == 0) {
                scheduleAutoHide()
            }
            false
        }
        input = editText
        bar.addView(editText, LinearLayout.LayoutParams(0, -1, 1.0f))
    }

    /** 发送键：圆形按钮，点击即提交。 */
    private fun buildSendButton(bar: LinearLayout) {
        val textView = ImageView(ctx)
        textView.setOnClickListener { submit() }
        button = textView
        UiKit.sendButton(textView, ctx)
        val btnLp = LinearLayout.LayoutParams(UiKit.dp(ctx, 62.0f), -1)
        btnLp.leftMargin = UiKit.dp(ctx, 6.0f)
        bar.addView(textView, btnLp)
    }

    /** 输入法让位：键盘弹起时把整条栏顶到键盘上方。 */
    private fun attachImeListener(bar: LinearLayout) {
        // 输入法让位：键盘弹起时把整条栏顶到键盘上方。
        bar.setOnApplyWindowInsetsListener { _, windowInsets ->
            val bottom: Int = if (Build.VERSION.SDK_INT >= 30) {
                windowInsets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                windowInsets.systemWindowInsetBottom
            }
            onIme(bottom)
            windowInsets
        }
    }

    /**
     * 键盘高度变化：自己不摆位，转给宿主。
     * 【理由】迷你聊天是一组（人偶 + 输入框），只抬框不抬人偶就会上下分家。
     */
    private fun onIme(ime: Int) {
        host.onIme(Math.max(0, ime))
    }

    private fun submit() {
        val editText = input ?: return
        val trim = editText.text.toString().trim()
        if (trim.isEmpty()) {
            return
        }
        editText.setText("")
        host.onSend(trim)
    }

    private fun focus() {
        val editText = input ?: return
        // 【坑】不能紧跟 addView 同步抢焦点：那一刻窗口还没 attach 完，
        //   requestFocus 拿不到 focus、showSoftInput 也拿不到 window token，键盘就弹不出来。
        //   推到下一帧执行。
        editText.post { focusNow() }
    }

    private fun focusNow() {
        val editText = input ?: return
        editText.requestFocus()
        try {
            val imm = ctx.getSystemService("input_method") as? InputMethodManager
            imm?.showSoftInput(editText, 0)
        } catch (unused: Throwable) {
        }
    }

    private fun hideIme() {
        val editText = input ?: return
        try {
            val imm = ctx.getSystemService("input_method") as? InputMethodManager
            imm?.hideSoftInputFromWindow(editText.windowToken, 0)
        } catch (unused: Throwable) {
        }
    }

    private fun scheduleAutoHide() {
        ui.removeCallbacks(autoHide)
        val editText = input
        // 【核心修复】有未发送的内容就不排自动收起：收起会把用户正在打的字一起吞掉。
        if (editText != null && editText.text != null && editText.text.length > 0) {
            return
        }
        ui.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26) 2038 else 2002
    }
}
