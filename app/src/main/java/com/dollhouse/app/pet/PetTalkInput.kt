package com.dollhouse.app.pet

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.dollhouse.app.R
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】三击人偶后出现的迷你输入框：一个输入框 + 一个发送键，挂在 WindowManager 上的独立悬浮窗。
 *
 * 【入口】只由 PetService 调用（show / hide / setBusy / moveTo / release）。
 *
 * 【交互】落点由 PetService 算好（宽 = 人偶宽、位于人偶正下方），本类只负责显示与收键盘；
 *          任何收起路径都回调 Host.onClosed，让宿主判断该不该退掉人偶的趴姿。
 *
 * 【收起规则】框里有未发送的内容时永不自动收起，空框 60 秒无操作才收。
 *
 * 【v3·Compose 化】外壳从手写 View 换成 Compose，解决三件事：
 *   ① 字号硬编码（原 UiKit.FS_BTN）→ 走 [DhTokens.type] 阶梯；
 *   ② 颜色不跟主题（原 UiKit.TITLE / SUB 是静态 Int）→ 走 [DhTokens.palette]，随深浅色切换；
 *   ③ 按压反馈是 View 侧的 UiKit.press → 改用 Compose 的 ripple（Modifier.clickable 默认指示）。
 *
 * 【窗口与 Compose 的分工】
 *   悬浮窗宿主仍是 View：**窗口**由 [WindowManager] 管理，本类持有根 `FrameLayout`，
 *   Compose 只接管它内部那一条栏。`show / hide / moveTo / setBusy` 的语义与改造前逐字一致。
 *
 * 【坑·生命周期的来源】本类的 Context 是 Service，不在任何 Activity 的 ViewTree 里，
 *   直接挂 ComposeView 会抛 `ViewTreeLifecycleOwner not found`。所以必须先
 *   [ComposeHost.attachOverlayOwner] 把三个 owner（Lifecycle / ViewModelStore /
 *   SavedStateRegistry）装到根 View 上，**再** addView —— 顺序错了同样崩。
 *   摘窗时必须配对调 [ComposeHost.HostOwner.onDestroy]，否则 Recomposer 与
 *   观察者会一直挂着（见 DollHomeOverlay 的同款注释）。
 *
 * 【坑·窗口 flags 不能含 FLAG_NOT_FOCUSABLE】否则拿不到焦点、键盘弹不出来。
 *   摘窗之前要先收键盘。
 */
class PetTalkInput internal constructor(
    private val ctx: Context,
    private val host: Host
) {
    companion object {
        /** 输入框高度（dp）。 */
        internal const val HEIGHT_DP = 46.0f

        /** 栏内边距（dp）：与原 View 版的 6dp 一致，不加厚。 */
        private const val PAD_DP = 6.0f

        /** 栏圆角（dp）：保持原值 14，不套令牌 —— 换档会连带整条栏的观感。 */
        private const val RADIUS_DP = 14.0f

        /** 发送键宽度（dp）：原 LinearLayout.LayoutParams(62, -1)。 */
        private const val SEND_W_DP = 62.0f

        /** 输入框左右内边距（dp）。 */
        private const val INPUT_PAD_DP = 8.0f

        /** 发送键与输入框的间距（dp）。 */
        private const val SEND_GAP_DP = 6.0f

        /**
         * 无操作自动收起时长。
         * 【坑】旧值 15 秒太短：打字时软键盘是独立窗口，点它不会重置计时，
         *   打满 15 秒整组（含已输入的文字）会被收掉 —— 用户报的「根本无法正常输入」。
         *   现在配合内容监听：框内有内容时根本不排计时，清空 / 发出后才重新计时。
         */
        private const val AUTO_HIDE_MS = 60000L

        /** 补弹键盘的延迟（ms）：窗口 attach 与 IME 会话建立都不在同一帧。 */
        private const val IME_RETRY_MS = 60L
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
    private val ui = Handler(Looper.getMainLooper())
    private var lp: WindowManager.LayoutParams? = null

    /** 根 View：只做窗口内容容器，不负责外观（外观全在 Compose 里）。 */
    private var root: FrameLayout? = null
    private var compose: ComposeView? = null
    private var owner: ComposeHost.HostOwner? = null
    private var added = false
    private val autoHide = Runnable { hide() }

    /**
     * 输入内容。替代原 `EditText.text` —— 自动收起判定与发送都读它。
     * 【为什么放在类上而不是 remember】宿主可能在窗口关闭前就调 setBusy，
     *   状态必须活在 Composition 之外，窗口重建也不丢。
     */
    private val text = mutableStateOf("")

    /** 请求在途：替代原 `button.isEnabled / alpha`。 */
    private val busy = mutableStateOf(false)

    /** 焦点请求次数。每加一次，Compose 侧重新 requestFocus 一次。 */
    private val focusTick = mutableStateOf(0)
    private val focusRequester = FocusRequester()

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
        // 【r15·收敛 !!】原为 `lp = WindowManager.LayoutParams(...)` 后连用 lp!!。
        //   lp 是本类的可空字段（hide/release 会清），全部 !! 换成局部变量，语义完全一致。
        val params = WindowManager.LayoutParams(w, UiKit.dp(ctx, HEIGHT_DP), overlayType(), 16777248, -3)
        lp = params
        params.gravity = 8388659
        // 【坑】16 = 只有 ADJUST_RESIZE，没有 STATE_VISIBLE，悬浮窗的键盘可能不弹。
        //   20 = ADJUST_RESIZE | STATE_VISIBLE。
        params.softInputMode = 20
        params.x = Math.max(0, x)
        params.y = Math.max(0, y)
        try {
            wm.addView(root, params)
            added = true
        } catch (unused: Throwable) {
            // 【附录C-3 同款】addView 失败时 owner 已经建好，必须就地回收，
            //   否则它的 Recomposer 会一直挂着，反复失败就是持续泄漏。
            runCatching { owner?.onDestroy() }
            owner = null
            added = false
            root = null
            compose = null
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
        teardown()
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
        teardown()
    }

    /** 服务销毁：不回调宿主（宿主正在拆自己）。 */
    internal fun release() {
        added = false
        ui.removeCallbacksAndMessages(null)
        if (root != null) {
            hideIme()
            teardown()
        } else {
            owner?.onDestroy()
            owner = null
        }
    }

    /** 请求在途时禁用发送键，避免连点重复发。 */
    internal fun setBusy(z: Boolean) {
        // 与原实现同口径：窗口没挂时没有可禁用的控件，直接返回。
        if (!added) {
            return
        }
        busy.value = z
    }

    /** 人偶移动 / 比例变化后重新落位（坐标由宿主算好）。 */
    internal fun moveTo(x: Int, y: Int, w: Int) {
        // 【r15·收敛 !!】同 show()：字段 lp 可空，改用局部引用。
        val params = lp
        if (!added || params == null) {
            return
        }
        params.width = w
        params.x = Math.max(0, x)
        params.y = Math.max(0, y)
        try {
            wm.updateViewLayout(root, params)
        } catch (unused: Throwable) {
        }
    }

    /** 摘窗收尾：卸 owner + 清引用 + 复位一次性状态（与原「重建控件」的语义对齐）。 */
    private fun teardown() {
        try {
            wm.removeView(root)
        } catch (unused: Throwable) {
        }
        // 先摘 View 再销毁 owner：反过来的话 Compose 会在已 detach 的树上重跑一帧。
        owner?.let { runCatching { it.onDestroy() } }
        owner = null
        root = null
        compose = null
        // 原实现在每次 show 时都新建 EditText / ImageView，等于「内容与忙碌态都归零」，
        // 这里显式复位以保持同一语义（否则下次打开会看到上次没发出去的草稿）。
        text.value = ""
        busy.value = false
    }

    private fun build() {
        val container = FrameLayout(ctx)
        // 【顺序·必须】owner 要在任何 ComposeView attach 之前装好，否则组合一挂就崩。
        owner = ComposeHost.attachOverlayOwner(container)
        val view = ComposeHost.createView(ctx) {
            PetBar()
        }
        // 铺满容器：窗口高度已经是 HEIGHT_DP，Compose 内容用 fillMaxSize 吃满它。
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        attachImeListener(container)
        root = container
        compose = view
    }

    /**
     * 栏体本体（Compose）。
     * 【高度纪律】这里**不使用**任何 Material 的 IconButton / Button ——
     *   它们会强制 48dp 最小触摸区，而整条窗口只有 46dp，一用就把栏撑破。
     *   发送键用 Box + Modifier.clickable 手拼，命中区严格等于视觉尺寸。
     */
    @Composable
    private fun PetBar() {
        val palette = DhTokens.palette
        val bodyStyle = DhTokens.type.body
        // 【字族】正文用文楷：这条栏是「用户在对人偶说话」，与聊天输入框同一口径。
        val inputStyle = bodyStyle.copy(fontFamily = DhTokens.fonts, color = palette.title)
        val hintStyle = bodyStyle.copy(fontFamily = DhTokens.fonts, color = palette.sub)
        val tick = focusTick.value
        LaunchedEffect(tick) {
            if (tick > 0) {
                runCatching { focusRequester.requestFocus() }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(6.dp, RoundedCornerShape(RADIUS_DP.dp))
                .clip(RoundedCornerShape(RADIUS_DP.dp))
                .background(palette.card)
                .border(1.dp, palette.line, RoundedCornerShape(RADIUS_DP.dp))
                .padding(PAD_DP.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = text.value,
                    onValueChange = {
                        text.value = it
                        scheduleAutoHide()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(focusRequester)
                        // 【契约保留】原实现用 setOnEditorActionListener 同时处理
                        //   软键盘「发送」与物理回车（keyCode 66）。软键盘那条由
                        //   keyboardActions.onSend 承担；物理回车在这里补上，
                        //   否则接实体键盘时按回车不会有任何反应。
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = inputStyle,
                    singleLine = true,
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { field ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = INPUT_PAD_DP.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (text.value.isEmpty()) {
                                Text(text = "说点什么…", style = hintStyle)
                            }
                            field()
                        }
                    }
                )
                Box(
                    modifier = Modifier
                        .padding(start = SEND_GAP_DP.dp)
                        // 【高度纪律】只定宽、高度跟着行高（原 LayoutParams(62, -1)），
                        //   不要再写固定高度 —— 会与父容器的 34dp 打架。
                        .width(SEND_W_DP.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(palette.accent, palette.accent2))
                        )
                        .alpha(if (busy.value) 0.5f else 1f)
                        .clickable(enabled = !busy.value) { submit() },
                    // 【对齐门禁】有背景 + 只有一个图标，必须显式居中，否则贴左上。
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_send),
                        contentDescription = "发送",
                        tint = palette.onAcc,
                        // 【尺寸对齐原图】ic_send 的固有尺寸就是 24dp，原 ImageView 是
                        //   scaleType=CENTER（按固有尺寸原样画、不缩放），
                        //   所以这里必须写 24dp —— 写 20dp 会把图标缩小 17%。
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    /** 输入法让位：键盘弹起时把整条栏顶到键盘上方。 */
    private fun attachImeListener(container: FrameLayout) {
        container.setOnApplyWindowInsetsListener { _, windowInsets ->
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
        val trim = text.value.trim()
        if (trim.isEmpty()) {
            return
        }
        text.value = ""
        host.onSend(trim)
    }

    private fun focus() {
        val view = root ?: return
        // 【坑·沿用旧结论】不能紧跟 addView 同步抢焦点：那一刻窗口还没 attach 完，
        //   requestFocus 拿不到 focus、showSoftInput 也拿不到 window token，键盘就弹不出来。
        //   推到下一帧执行。Compose 侧由 focusTick 触发 requestFocus。
        view.post {
            if (!added) {
                return@post
            }
            focusTick.value = focusTick.value + 1
            // 【兜底】个别 ROM 在悬浮窗上不会因 Compose 拿到焦点就自动弹键盘，
            //   这里补一次 showSoftInput（幂等：已经弹出时是空操作）。
            view.postDelayed({ showIme() }, IME_RETRY_MS)
        }
    }

    private fun showIme() {
        if (!added) {
            return
        }
        val view = compose ?: return
        try {
            val imm = ctx.getSystemService("input_method") as? InputMethodManager
            imm?.showSoftInput(view, 0)
        } catch (unused: Throwable) {
        }
    }

    private fun hideIme() {
        val view = root ?: return
        try {
            val imm = ctx.getSystemService("input_method") as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (unused: Throwable) {
        }
    }

    private fun scheduleAutoHide() {
        ui.removeCallbacks(autoHide)
        // 【核心修复】有未发送的内容就不排自动收起：收起会把用户正在打的字一起吞掉。
        if (text.value.isNotEmpty()) {
            return
        }
        ui.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26) 2038 else 2002
    }
}
