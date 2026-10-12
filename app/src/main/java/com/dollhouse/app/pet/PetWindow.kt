package com.dollhouse.app.pet

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PetService
import com.dollhouse.app.anim.Springs
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】单个启用人偶的悬浮窗控制器：把 PetView / LayoutParams / 坐标 / 贴边 / 气泡 /
 *   动画取消句柄全部收在一份独立实例里，多个 PetWindow 同时挂 WindowManager 互不覆盖。
 *
 * 【入口】由 PetWindowController.attachAll() 按 DollStore.enabled() 逐个创建；
 *   旧 PetService 的窗口字段 / 方法（attach / placeByPivot / snapToEdge / say / layoutMiniTalk…）
 *   在这里变成实例成员，语义逐行保留。
 *
 * 【交互】每个窗口各自持有 PetView、自己的 LayoutParams、自己的 snapAnim（贴边 ValueAnimator）、
 *   自己的气泡状态与迷你聊天（PetTalk / PetTalkInput），并各自注册一个 PetBus.Listener；
 *   PetBus 广播时所有窗口同时联动，但位置 / 尺寸 / 动画互不影响。
 *
 * 【形象】精灵图统一走 DollStore.spriteOf(host, doll)：形象图路径为空时回落到内置 pet_front。
 *
 * 【坑】snapAnim 必须存在本实例字段上，detach 时才能逐个取消，否则多窗口下会泄漏；
 *   prefs 的 rest_px / rest_fy / rest_rx / rest_ry / edge_side 是历史键名，改名会让用户位置丢失。
 */
class PetWindow(private val host: PetService, val doll: DollStore.Doll) {
    // ---- 窗口运行态（原 PetService 包级字段，改成实例字段）----
    var curPivotX = 0f
    var curPivotY = 0f
    var downPivotX = 0f
    var downPivotY = 0f
    var downRawX = 0f
    var downRawY = 0f
    var lastMoveAt = 0L
    var lastRawX = 0f
    var lp: WindowManager.LayoutParams? = null
    var petTopY = 0
    var petView: PetView? = null
    var snapAnim: ValueAnimator? = null
    var touchSlop = ViewConfiguration.get(host).scaledTouchSlop
    var added = false
    var bubbleUp = false
    var peek = false
    var edgePeek = false
    /** 已应用到 PetView 的缩放系数；applyScale() 用来判断「比例真的变了没」。 */
    var petScaleApplied = 0.0f
    /* 【r12】原 `pendingHiddenMs` 已删：消失时长改为在 attach() 里按 dollId 现取，
     *   因为每只人偶有自己的 returnSeconds，全局一个时长无法区分。 */
    var edgeAtLeft = false
    var petShown = true
    var lastPlaceAt = 0L
    var dragging = false
    var away = false
    /** 上次「消失」的起始时刻（暂离摘窗）；0 = 当前没有进行中的消失计时。 */
    private var hiddenAt = 0L

    // ---- 迷你聊天（三击召唤）----
    var talkInput: PetTalkInput? = null
    var talk: PetTalk? = null
    var talkFeetY = 0
    var talkIme = 0
    var talkAnchorX = 0
    var talkOnSend = false
    var talkInputWanted = false

    private val wm: WindowManager = host.wm
    private val prefs: SharedPreferences = host.prefs
    private val ui = Handler(Looper.getMainLooper())
    private val geom = FloatArray(4)
    private val gesture = GestureCore(PetService.TAP_WINDOW_MS)
    private var longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val awayCore = AwayCore()

    /** 上一次已知的屏幕宽高（-1 = 尚未记录）；旋转 / 折叠屏 / 分屏靠它察觉。 */
    private var screenW = -1
    private var screenH = -1

    /**
     * 【r13 修 bug】是否已经记录过一次屏幕尺寸。
     *
     * 【为什么必须加这个标志】原来只用 `screenW/screenH == -1` 表示「还没记录」。
     *   但窗口一挂上，[PetView] 的第一次帧自检就会调 [checkScreenChanged]，
     *   此时 `-1 != 真实宽高` 必然成立 → 判定「屏幕变了」→ [relayoutForScreen] →
     *   [restorePivot] 取**上次记住的旧位置**并摆回去，
     *   把 [attach] 刚算好的初始位置整体盖掉。
     *   这就是「关闭再打开，人偶不回初始位置」的直接原因。
     *   有了本标志，首次只记录尺寸、绝不复位。
     */
    private var screenChecked = false

    private val hideBubble = Runnable { hideBubbleNow() }

    /** 连击窗口到点：结算为单 / 双 / 三击并按配置派发。 */
    private val tapResolve = Runnable {
        val g = gesture.onWindowTimeout()
        if (g != null) {
            dispatchGesture(g)
        }
    }

    /** 长按到点：判定为长按手势（即便配置为「无」也消费这一次按压）。 */
    private val longPressTick = Runnable {
        val g = gesture.onLongPressTimeout()
        if (g != null) {
            ui.removeCallbacks(tapResolve)
            dispatchGesture(g)
        }
    }

    /** 暂离到期恢复：重新 addView 同一 PetView 并启动动画。 */
    private val awayResume = Runnable { exitAway() }

    /** 本窗口的 PetBus 订阅：广播时只动自己的 PetView，不碰别人。 */
    val busListener: PetBus.Listener = object : PetBus.Listener {
        override fun onSay(str: String, j: Long) {
            ui.post { this@PetWindow.say(str, j) }
        }
    }

    fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26) 2038 else 2002
    }

    /**
     * 位置记忆键名：默认配置沿用历史键名（保住老用户已存的位置），
     * 其余人偶按 id 加后缀分开存 —— 多窗口各自记自己的坐标，重启后不会互相覆盖。
     */
    private fun key(base: String): String {
        return if (doll.id == DollStore.DEFAULT_ID) base else base + "_" + doll.id
    }

    // ============================ 挂载 / 摘除 ============================

    /** 创建本窗口的 PetView 并挂进 WindowManager；失败时摘掉并复位。 */
    fun attach() {
        val petView = PetView(host)
        this.petView = petView
        petScaleApplied = DollProfile.scale(host, doll.id) / 100.0f
        petView.setScaleFactor(petScaleApplied)
        // 【形象】统一走 DollStore.spriteOf：空路径自动回落内置 pet_front。
        petView.setSprite(DollStore.spriteOf(host, doll))
        petView.setFlipped(DollProfile.flip(host, doll.id))
        petView.snapActiveCheck = { snapAnim?.isRunning == true }
        val layoutParams = WindowManager.LayoutParams(petView.windowWidth(), petView.petHeight(), overlayType(), 776, -3)
        lp = layoutParams
        layoutParams.gravity = 8388659
        val i = host.resources.displayMetrics.widthPixels
        val i2 = host.resources.displayMetrics.heightPixels
        val round = Math.round(host.resources.displayMetrics.density * 8.0f)
        val petWidth = petView.petWidth() / 2
        val rest = restorePivot()
        // 【r12 修 bug·初始位置没生效】回归条件判定。
        //
        // 【消失判据·用户定案】「只要人偶从屏幕消失 …… 不会自动回来 那都算人偶消失；
        //   会自动回来的就不算人偶消失，算暂时离场」。
        //   ⇒ 消失（关闭 / 取消启用）才写 pet_hidden_at_<dollId>；
        //     暂离有定时器会自己回来，**不写**该键，所以不会重置这里的计时。
        //
        // 【旧实现的坑】旧代码是 `hiddenMs >= 0L && (ret <= 0 || ...)` —— 要求「必须有关闭记录」
        //   才算回归。但若用户没点过「关闭人偶」（服务本来就没跑 / 首次启动 / 刚装完），
        //   hiddenAt = 0 → hiddenMs = -1 → wantHome 恒为 false，于是「初始位置」被
        //   rest_rx/rest_ry（历史位置）整体盖掉 —— 用户看到的就是「设了没用」。
        // 【正确语义】ret <= 0 = 「每次出现都回初始位置」⇒ **任何一次出现**都该回，包括首次；
        //   ret > 0 才拿消失时长比阈值。
        val hiddenMs = PetPrefs.takeHiddenMs(host, doll.id)
        val ret = DollProfile.returnSeconds(host, doll.id)
        // 【r13 修 bug】再加一道「有没有设过初始位置」的判定。
        //   原来只看「消失多久」：用户没设过初始位置时 wantHome 为真，
        //   于是回落到右下角默认位 —— 把用户自己放好的旧位置也搬走了。
        //   没设过就应当沿用旧位置（restorePivot），而不是硬塞一个默认点。
        val hasHome = DollProfile.hasHomePivot(host, doll.id)
        val wantHome = hasHome && (if (ret <= 0) true else (hiddenMs >= 0L && hiddenMs >= ret * 1000L))
        val rest2 = if (wantHome) null else rest
        // 【初始位置】没有任何历史位置时优先用本只人偶的「初始位置」（归一化坐标）；
        //   没设过才回落到右下角默认位 —— 设了初始位置却不生效会让用户困惑。
        val home = if (rest2 == null) DollProfile.homePivot(host, doll.id) else null
        var i3 = if (rest2 != null) rest2[0] else if (home != null) Math.round(home[0] * i) else (i - round) - petWidth
        val i4 = if (rest2 != null) rest2[1] else if (home != null) Math.round(home[1] * i2) else Math.round(i2 * 0.8f)
        // 【r12 修 bug·回归时间没生效】wantHome 为真时**不恢复贴边姿态**：
        //   上次关闭前人偶正贴在屏幕边，edge_side 记着方向；旧代码无条件恢复贴边，
        //   把上面刚算好的「初始位置」整体盖掉 —— 用户看到的就是「在哪儿消失就在哪儿出现」。
        //   回归优先于姿态记忆：这次是「回家」，不该再趴回边上。
        val i5 = if (wantHome) -1 else prefs.getInt(key("edge_side"), -1)
        if (i5 >= 0) {
            enterEdgePeek(i5 == 0)
        } else {
            // 【r12】初始落位夹到虚线框内（框可超出屏幕，所以不能用屏幕宽当边界）。
            val box0 = boundsBox()
            i3 = if (box0[2] - box0[0] <= petWidth * 2) {
                Math.round(box0[2] - petWidth)
            } else {
                Math.max(Math.round(box0[0] + petWidth), Math.min(Math.round(box0[2] - petWidth), i3))
            }
        }
        placeByPivot(i3, clampFeetY(i4, i2, round))
        // 【r13 修 bug】摆完立刻把「本次落点」写进记忆。
        //
        // 【为什么必须写】placeByPivot 只改 lp，不碰记忆键。于是会出现
        //   「窗口显示在初始位置（lp 对）而记忆仍是上次的旧位置（rest_* 旧）」的不一致；
        //   只要之后再有一次走 [restorePivot] 的复位（帧自检 / 转屏 / 保活 / 无气泡重建），
        //   窗口就会被搬回旧位置 —— 用户看到的就是「我手指一动，它从初始位置飞到我那里」。
        //   当场对齐后，记忆与显示永远同源，任何复位都落在同一处。
        rememberPivot()
        // 【为什么用 post】此刻窗口还没 addView，lp 已是最终值；但 PetView 的
        //   pivotLocalX/Y 要等测量完成才稳定，丢到下一帧写才能取到准确锚点。
        ui.post { rememberPivot() }
        petView.setOnTouchListener { _, motionEvent -> handleTouch(motionEvent) }
        try {
            wm.addView(petView, layoutParams)
            added = true
            petView.screenTick = Runnable { checkScreenChanged() }
            petView.startAnim()
            PetBus.register(busListener)
            updateTouchable()
        } catch (unused: Throwable) {
            added = false
            // 主窗挂载失败才停服务（与旧行为一致）；副窗失败只记日志，不影响其它人偶。
            if (host.primary === this) {
                PetNotifier.stopSafely(host)
            } else {
                Logs.w("Dollhouse", "secondary pet window attach failed")
            }
        }
    }

    /** 从 WindowManager 摘掉 PetView 并复位状态（本窗口独占的资源一并释放）。 */
    fun detach() {
        PetBus.unregister(busListener)
        cancelAway()
        ui.removeCallbacksAndMessages(null)
        snapAnim?.cancel()
        talkInput?.release()
        talk?.release()
        val petView = petView
        if (petView != null) {
            petView.screenTick = null
            petView.stopAnim()
            if (added) {
                try {
                    wm.removeView(petView)
                } catch (unused: Throwable) {
                }
            }
            this.petView = null
        }
        added = false
    }

    /**
     * 【暂离】把同一 PetView 从 WindowManager 摘掉，实例 / lp / PetBus / PetTalk 原样保留。
     */
    fun awayHide() {
        val petView = petView ?: return
        if (!added) {
            return
        }
        petView.screenTick = null
        petView.stopAnim()
        try {
            wm.removeView(petView)
        } catch (unused: Throwable) {
        }
        added = false
    }

    /** 【暂离恢复】把同一 PetView 重新挂回原 lp 并启动动画；成功返回 true。 */
    fun awayShow(petView: PetView, layoutParams: WindowManager.LayoutParams): Boolean {
        return try {
            wm.addView(petView, layoutParams)
            added = true
            petView.screenTick = Runnable { checkScreenChanged() }
            petView.startAnim()
            safeUpdate()
            true
        } catch (unused: Throwable) {
            added = false
            false
        }
    }

    /**
     * 屏幕尺寸自检：宽高变了就按新屏幕把本窗口复位回屏内。
     * 【调用方】host.onConfigurationChanged / REFRESH / PetView 帧内自检（screenTick）。
     */
    fun checkScreenChanged() {
        if (petView == null || lp == null || !added) {
            return
        }
        val w = host.resources.displayMetrics.widthPixels
        val h = host.resources.displayMetrics.heightPixels
        // 【r13 修 bug】首次只记录，不复位 —— 此刻 attach() 刚摆好位置，
        //   任何「按旧记忆重摆」都会把它冲掉（见 [screenChecked] 的说明）。
        if (!screenChecked) {
            screenChecked = true
            screenW = w
            screenH = h
            return
        }
        if (w == screenW && h == screenH) {
            return
        }
        screenW = w
        screenH = h
        relayoutForScreen()
    }

    /** 按当前屏幕复位本窗口（取消在跑的吸附动画 → 退趴姿或按比例键重摆）。 */
    private fun relayoutForScreen() {
        val anim = snapAnim
        if (anim != null && anim.isRunning) {
            anim.cancel()
        }
        if (peek) {
            exitPeek()
            return
        }
        val pivot = restorePivot()
        if (pivot == null) {
            return
        }
        val screenH = host.resources.displayMetrics.heightPixels
        val edge = Math.round(host.resources.displayMetrics.density * 8.0f)
        // 【r12】转屏 / 分屏后按**新方向的那套虚线框**复位（不再是屏幕边缘）。
        val box = boundsBox()
        val sig = (box[0].toInt() * 31) xor (box[1].toInt() * 17) xor (box[2].toInt() * 7) xor box[3].toInt()
        boxSig = sig
        if (edgePeek) {
            val petWidth = petView!!.petWidth()
            val inset = petView!!.edgeInsetPx()
            // 【r12】与 snapToEdge 同口径：贴虚线框的边；框坐标是 Float，窗口 x 需取整。
            val wPivot = Math.round(
                if (edgeAtLeft) (box[0] - inset) + (petWidth / 2) else (box[2] + inset) - (petWidth / 2)
            )
            placeByPivot(wPivot, clampFeetY(pivot[1], screenH, edge))
            rememberPivot()
            return
        }
        val half = petView!!.petWidth() / 2
        val x = if (box[2] - box[0] <= petView!!.petWidth()) {
            Math.round(box[2] - half)
        } else {
            Math.max(Math.round(box[0] + half), Math.min(Math.round(box[2] - half), pivot[0]))
        }
        placeByPivot(x, clampFeetY(pivot[1], screenH, edge))
        rememberPivot()
    }

    /** 还原本窗口人偶锚点 {pivotX, pivotY}；无记录时返回 null（调用方走默认值）。 */
    private fun restorePivot(): IntArray? {
        val screenW = host.resources.displayMetrics.widthPixels
        val screenH = host.resources.displayMetrics.heightPixels
        val rx = prefs.getFloat(key("rest_rx"), -1.0f)
        val ry = prefs.getFloat(key("rest_ry"), -1.0f)
        if (rx >= 0.0f && ry >= 0.0f) {
            return intArrayOf(Math.round(rx * screenW), Math.round(ry * screenH))
        }
        if (prefs.contains(key("rest_px")) || prefs.contains(key("rest_fy"))) {
            return intArrayOf(prefs.getInt(key("rest_px"), screenW / 2),
                    prefs.getInt(key("rest_fy"), Math.round(screenH * 0.8f)))
        }
        return null
    }

    // ============================ 几何 / 摆位 ============================

    fun safeUpdate() {
        val petView = petView
        if (petView == null || !added) {
            return
        }
        try {
            wm.updateViewLayout(petView, lp!!)
        } catch (unused: Throwable) {
        }
    }

    fun placeByPivot(i: Int, i2: Int) {
        val petView = petView
        val lp = lp
        if (petView == null || lp == null) {
            return
        }
        petView.geometry(geom)
        val round = Math.round(geom[0])
        val round2 = Math.round(geom[1])
        val round3 = Math.round(i - geom[2])
        val round4 = Math.round(i2 - geom[3])
        petTopY = round4
        if (bubbleUp && !peek && petView.hasBubbleContent()) {
            val space = petView.neededBubbleSpaceFor(round, round3)
            if (space > 0) {
                petView.setBubbleHeight(space)
                val h = petView.baseHeight() + space
                val y = round4 - space
                if (lp.width == round && lp.height == h && lp.x == round3 && lp.y == y) {
                    return
                }
                lp.width = round
                lp.height = h
                lp.x = round3
                lp.y = y
                safeUpdate()
                return
            }
        }
        if (lp.width == round && lp.height == round2 && lp.x == round3 && lp.y == round4) {
            return
        }
        lp.width = round
        lp.height = round2
        lp.x = round3
        lp.y = round4
        safeUpdate()
    }

    fun rememberPivot() {
        val petView = petView
        val lp = lp
        if (petView == null || lp == null) {
            return
        }
        val pivotX = lp.x + petView.pivotLocalX()
        val pivotY = lp.y + petView.pivotLocalY() + bubbleHeightIfUp()
        val screenW = Math.max(1, host.resources.displayMetrics.widthPixels)
        val screenH = Math.max(1, host.resources.displayMetrics.heightPixels)
        prefs.edit()
                .putInt(key("rest_px"), Math.round(pivotX))
                .putInt(key("rest_fy"), Math.round(pivotY))
                .putFloat(key("rest_rx"), pivotX / screenW)
                .putFloat(key("rest_ry"), pivotY / screenH)
                .apply()
    }

    /* ============================ 【r12】虚线框（屏幕范围）============================ */

    /** 复用缓冲，避免每帧 new float[4]。 */
    private val boxBuf = FloatArray(4)

    /** 上次应用的框签名（左/上/右/下 量化后）。框变了才重摆。 */
    private var boxSig = Int.MIN_VALUE

    /**
     * 【r12】本只人偶的虚线框，像素口径 {left, top, right, bottom}。
     *
     * 【为什么按方向取】框是横竖屏各存一套的（[DollProfile.ORIENT_PORT] / [ORIENT_LAND]），
     *   转屏后要取对应的那一套，否则用户横屏设的范围会莫名其妙地作用到竖屏上。
     * 【为什么允许超出屏幕】需求定案「虚线不受屏幕限制」—— 框可以在屏幕之外，
     *   人偶也能走到屏幕外，所以这里不做任何与屏幕求交的处理。
     */
    private fun boundsBox(): FloatArray {
        val land = host.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val orient = DollProfile.orientOf(land)
        val b = DollProfile.bounds(host, doll.id, orient)
        val w = Math.max(1, host.resources.displayMetrics.widthPixels).toFloat()
        val h = Math.max(1, host.resources.displayMetrics.heightPixels).toFloat()
        boxBuf[0] = b[0] * w
        boxBuf[1] = b[1] * h
        boxBuf[2] = b[2] * w
        boxBuf[3] = b[3] * h
        return boxBuf
    }

    /**
     * 【r12】虚线框变了就把人偶重新摆回框内。
     * 【调用时机】用户在悬浮窗里调完框点「保存」→ ACTION_REFRESH → [applyTheme]。
     *   用签名比对做去重，没变就什么都不做（这个回调本来也用于主题刷新）。
     */
    private fun reflowToBounds() {
        val pv = petView ?: return
        if (lp == null || !added) {
            return
        }
        val b = boundsBox()
        val sig = (b[0].toInt() * 31) xor (b[1].toInt() * 17) xor (b[2].toInt() * 7) xor b[3].toInt()
        if (sig == boxSig) {
            return
        }
        boxSig = sig
        // 出框了就按「吸到最近框边」收回来；还在框内则只做上下夹取。
        settle()
        pv.invalidate()
    }

    /**
     * 【r12】上下夹取：脚不低于框下边、头顶（含气泡）不高于框上边。
     * 【参数】后两个（屏幕高 / 边距）为兼容既有调用点保留，实际不再使用 ——
     *   边界改由虚线框给出，与屏幕尺寸解耦。
     */
    fun clampFeetY(i: Int, i2: Int, i3: Int): Int {
        val petView = petView!!
        val box = boundsBox()
        val headMin = Math.round(box[1] + petView.pivotLocalY() + bubbleHeightIfUp())
        val minY = Math.max(petView.bubbleSpace() + Math.round(petView.pivotLocalY()), headMin)
        val maxY = Math.round(box[3])
        if (maxY <= minY) {
            return minY
        }
        return Math.max(minY, Math.min(maxY, i))
    }

    fun settle() {
        val i2 = host.resources.displayMetrics.heightPixels
        val round = Math.round(host.resources.displayMetrics.density * 8.0f)
        val round2 = Math.round(lp!!.x + petView!!.pivotLocalX())
        val feetY = Math.round(lp!!.y + petView!!.pivotLocalY() + bubbleHeightIfUp())
        val clampFeetY = clampFeetY(feetY, i2, round)
        // 【r12】吸附区与落位边界改由虚线框决定（不再用屏幕边缘）。
        //   需求定案：拖出去之后「会被拉回到虚线处」= 吸到最近的框边。
        val box = boundsBox()
        val left = box[0]
        val right = box[2]
        val zone = Math.round((right - left) * PetService.SNAP_ZONE_RATIO)
        if (round2 < left + zone) {
            snapToEdge(true, clampFeetY)
            return
        }
        if (round2 > right - zone) {
            snapToEdge(false, clampFeetY)
            return
        }
        exitEdgePeek()
        // 框比人偶还窄时（极端缩放）直接贴右，保证不越界。
        val half = petView!!.petWidth() / 2
        val x = if (right - left <= petView!!.petWidth()) {
            Math.round(right - half)
        } else {
            Math.max(Math.round(left + half), Math.min(Math.round(right - half), round2))
        }
        placeByPivot(x, clampFeetY)
        rememberPivot()
    }

    fun snapToEdge(z: Boolean, i: Int) {
        petView?.wakeFrame()
        val petView = petView!!
        val petWidth = petView.petWidth()
        // 【r12】贴的是**虚线框**的左右边，不再是屏幕边缘（需求定案）。
        val box = boundsBox()
        val i3 = Math.max(1, box[2].toInt())
        enterEdgePeek(z)
        val edgeInsetPx = petView.edgeInsetPx()
        // 【r12】框坐标是 Float，窗口 x 必须是 Int：这里就取整，后面直接当像素用。
        val i2 = Math.round(if (z) {
            (box[0] - edgeInsetPx) + (petWidth / 2)
        } else {
            (box[2] + edgeInsetPx) - (petWidth / 2)
        })
        val round2 = Math.round(i2 - petView.pivotLocalX())
        val lp = lp!!
        val space = if (bubbleUp && !peek && petView.hasBubbleContent())
            petView.neededBubbleSpaceFor(petView.windowWidth(), round2) else 0
        if (space > 0) {
            petView.setBubbleHeight(space)
        }
        lp.width = petView.windowWidth()
        lp.height = petView.baseHeight() + space
        lp.y = Math.round(i - petView.pivotLocalY() - space)
        petTopY = lp.y + space
        safeUpdate()
        val screenW = Math.max(1, host.resources.displayMetrics.widthPixels)
        val screenH = Math.max(1, host.resources.displayMetrics.heightPixels)
        // 【r13 修 bug】rest_rx 的分母改回**屏幕宽**。
        //   原来这里用框宽、而 [rememberPivot] / [restorePivot] 用屏幕宽 —— 同一个键
        //   三处口径不一，贴过一次边之后值就错位，之后每次按它恢复都偏。
        //   统一成屏幕宽，与另外两处同源。
        prefs.edit().putInt(key("rest_px"), i2).putInt(key("rest_fy"), i)
                .putFloat(key("rest_rx"), i2 / screenW.toFloat())
                .putFloat(key("rest_ry"), i / screenH.toFloat()).apply()
        val fromX = lp.x
        snapAnim = Springs.drive(Springs.snappy(), object : Springs.Listener {
            override fun onUpdate(p: Float) {
                lp!!.x = Springs.lerpInt(fromX, round2, p)
                safeUpdate()
            }

            override fun onEnd() {
                lp!!.x = round2
                safeUpdate()
            }
        })
    }

    fun enterEdgePeek(z: Boolean) {
        val petView = petView
        if (petView == null) {
            return
        }
        edgePeek = true
        edgeAtLeft = z
        petView.setEdgePeek(true, z)
        prefs.edit().putInt(key("edge_side"), if (!z) 1 else 0).apply()
    }

    fun exitEdgePeek() {
        val petView = petView
        val layoutParams = lp
        if (!edgePeek || petView == null || layoutParams == null) {
            return
        }
        edgePeek = false
        val pivotLocalX = layoutParams.x + petView.pivotLocalX()
        val pivotLocalY = layoutParams.y + petView.pivotLocalY() + bubbleHeightIfUp()
        petView.setEdgePeek(false, false)
        placeByPivot(Math.round(pivotLocalX), Math.round(pivotLocalY))
        prefs.edit().putInt(key("edge_side"), -1).apply()
    }

    fun applyDragPlacement() {
        val currentTimeMillis = System.currentTimeMillis()
        if (currentTimeMillis - lastPlaceAt < 12) {
            return
        }
        lastPlaceAt = currentTimeMillis
        placeByPivot(Math.round(curPivotX), Math.round(curPivotY))
    }

    // ============================ 气泡 ============================

    /**
     * 气泡占用的窗口高度：有气泡、非 peek 且确有内容时为其实际高度，否则 0。
     */
    private fun bubbleHeightIfUp(): Int {
        val petView = petView
        return if (bubbleUp && !peek && petView != null && petView.hasBubbleContent())
            petView.bubbleHeight() else 0
    }

    fun expandBubble(i: Int) {
        val petView = petView
        if (lp == null || petView == null) {
            return
        }
        var space = if (i <= 0) petView.bubbleSpace() else i
        if (edgePeek && !peek) {
            val running = snapAnim
            if (running != null && running.isRunning) {
                running.cancel()
            }
            // 【r12】气泡展开时把窗口夹回框内（框可超出屏幕，故以框为界）。
            val box = boundsBox()
            val insetPx = petView.edgeInsetPx()
            val minX = Math.round(box[0] - insetPx)
            val maxX = Math.round(box[2] + insetPx) - lp!!.width
            lp!!.x = Math.max(minX, Math.min(Math.max(minX, maxX), lp!!.x))
        }
        petView.relayoutBubble(lp!!.width, lp!!.x)
        space = petView.neededBubbleSpaceFor(lp!!.width, lp!!.x)
        petView.setBubbleHeight(space)
        lp!!.height = petView.baseHeight() + space
        lp!!.y = petTopY - space
        bubbleUp = true
        safeUpdate()
    }

    /** 贴边偷看时的窗口 x（与 snapToEdge 同款算法）。 */
    private fun edgePeekX(): Int {
        val petView = petView
        if (petView == null) {
            return lp?.x ?: 0
        }
        // 【r12】与 snapToEdge 同口径：贴虚线框的边。
        val box = boundsBox()
        val inset = petView.edgeInsetPx()
        val petWidth = petView.petWidth()
        val pivot = if (edgeAtLeft) (box[0] - inset) + (petWidth / 2) else (box[2] + inset) - (petWidth / 2)
        return Math.round(pivot - petView.pivotLocalX())
    }

    fun collapseBubble() {
        val petView = petView
        if (lp == null || petView == null || !bubbleUp) {
            val petView2 = petView
            if (petView2 != null) {
                petView2.clearBubble()
            }
            return
        }
        petView.clearBubble()
        petView.setBubbleHeight(0)
        lp!!.height = petView.baseHeight()
        lp!!.y = petTopY
        if (edgePeek && !peek) {
            lp!!.x = edgePeekX()
        }
        bubbleUp = false
        safeUpdate()
    }

    // ============================ 可见性 / 触摸穿透 ============================

    fun updateTouchable() {
        val lp = lp
        if (petView == null || lp == null) {
            return
        }
        val z = petShown && !PetPrefs.touchThrough(host)
        val i = lp.flags
        if (z) {
            lp.flags = lp.flags and -17
        } else {
            lp.flags = lp.flags or 16
        }
        if (lp.flags != i) {
            safeUpdate()
        }
    }

    fun applyPetShown(z: Boolean) {
        val petView = petView
        if (petView == null || lp == null || z == petShown) {
            return
        }
        petShown = z
        petView.setVisibility(if (z) 0 else 8)
        updateTouchable()
    }

    // ============================ 缩放 ============================

    /**
     * 应用新的人偶缩放：改 PetView 系数后重算窗口宽高并重摆。
     * 【短路】比例没变直接返回；【聊天窗】peek 态走 layoutMiniTalk 重排整组。
     */
    fun applyScale() {
        val petView = petView
        if (petView == null || lp == null || !added) {
            return
        }
        val target = DollProfile.scale(host, doll.id) / 100.0f
        if (Math.abs(target - petScaleApplied) < 1.0E-4f) {
            return
        }
        petScaleApplied = target
        val valueAnimator = snapAnim
        if (valueAnimator != null && valueAnimator.isRunning) {
            valueAnimator.cancel()
        }
        if (peek) {
            petView.setBubbleHeight(0)
            bubbleUp = false
            petView.setScaleFactor(target)
            layoutMiniTalk(talkIme)
            return
        }
        val wasBubbleUp = bubbleUp
        val bubbleSpace = if (wasBubbleUp) Math.max(0, lp!!.height - petView.baseHeight()) else petView.bubbleSpace()
        if (wasBubbleUp) {
            petView.setBubbleHeight(0)
            bubbleUp = false
        }
        var pivotX: Float = lp!!.x + petView.pivotLocalX()
        val pivotY = lp!!.y + petView.pivotLocalY()
        if (edgePeek) {
            exitEdgePeek()
        }
        if (this.petView == null || lp == null || !added) {
            return
        }
        petView.setScaleFactor(target)
        val screenW = host.resources.displayMetrics.widthPixels
        val half = petView.petWidth() / 2
        if (!edgePeek) {
            pivotX = Math.max(half.toFloat(), Math.min((screenW - half).toFloat(), pivotX))
        }
        placeByPivot(Math.round(pivotX), Math.round(pivotY))
        rememberPivot()
        if (wasBubbleUp) {
            expandBubble(bubbleSpace)
        }
    }

    /** 主题切换后刷新本窗口绘制用色。 */
    fun applyTheme() {
        petView?.applyTheme()
        // 【r11】面朝方向也可能刚被改过（悬浮窗的旋转按钮 → ACTION_REFRESH），一并刷新。
        petView?.setFlipped(DollProfile.flip(host, doll.id))
        // 【r12】虚线框也可能刚被改过（屏幕范围悬浮窗点「保存」→ ACTION_REFRESH）：
        //   框变了就把人偶重新收进框内。
        reflowToBounds()
    }

    // ============================ 迷你聊天（趴姿）============================

    /** 三击召唤：挂出迷你输入框，人偶趴到框顶。 */
    internal fun openMiniTalk() {
        if (petView == null || lp == null || !added) {
            return
        }
        ensureMiniTalk()
        talkInputWanted = true
        layoutMiniTalk(talkIme)
    }

    /** 首次使用时组装逻辑层与输入框（之后常驻复用，不反复建窗）。 */
    private fun ensureMiniTalk() {
        if (talk == null) {
            talk = PetTalk(host, newTalkHost())
        }
        if (talkInput == null) {
            talkInput = PetTalkInput(host, newTalkInputHost())
        }
    }

    /** PetTalk 的宿主回调：气泡分页 / 行数变化后的整组重排、气泡读回、忙碌态透传。 */
    private fun newTalkHost(): PetTalk.Host {
        return object : PetTalk.Host {
            /** 【r13·一人偶一对话】把本窗口的人偶 id 透给迷你聊天做会话绑定。 */
            override fun dollId(): String {
                return this@PetWindow.doll.id
            }

            override fun saySticky(str: String) {
                this@PetWindow.saySticky(str)
            }

            override fun sayTemp(str: String, j: Long) {
                this@PetWindow.say(str, j)
            }

            override fun clearBubble() {
                this@PetWindow.hideBubbleNow()
            }

            override fun showBubblePaged(str: String, i: Int): Int {
                val petView = this@PetWindow.petView ?: return 0
                val pages = petView.showBubblePaged(str, i)
                if (this@PetWindow.peek && this@PetWindow.bubbleUp) {
                    this@PetWindow.layoutMiniTalk(this@PetWindow.talkIme)
                } else {
                    this@PetWindow.expandBubble(petView.neededBubbleSpace())
                }
                return pages
            }

            override fun bubblePage(i: Int): String? {
                return this@PetWindow.petView?.bubblePage(i)
            }

            override fun setBubbleLines(i: Int) {
                val petView = this@PetWindow.petView ?: return
                petView.setBubbleMaxLines(i)
                if (this@PetWindow.peek && this@PetWindow.bubbleUp) {
                    this@PetWindow.layoutMiniTalk(this@PetWindow.talkIme)
                }
            }

            override fun onBusy(z: Boolean) {
                this@PetWindow.talkInput?.setBusy(z)
            }

            override fun bubbleHasOverflow(): Boolean {
                val petView = this@PetWindow.petView
                return petView != null && petView.bubbleHasOverflow()
            }
        }
    }

    /** PetTalkInput 的宿主回调：输入框收起 / 发送 / 输入法高度变化时的整组重排。 */
    private fun newTalkInputHost(): PetTalkInput.Host {
        return object : PetTalkInput.Host {
            override fun onClosed() {
                this@PetWindow.exitPeek()
            }

            override fun onSend(str: String) {
                this@PetWindow.talkIme = 0
                this@PetWindow.talkInputWanted = false
                this@PetWindow.talkOnSend = true
                this@PetWindow.exitPeek()
                this@PetWindow.talk?.talk(str)
            }

            override fun onIme(i: Int) {
                this@PetWindow.talkIme = i
                if (this@PetWindow.peek) {
                    this@PetWindow.layoutMiniTalk(i)
                }
            }
        }
    }

    /**
     * 迷你聊天的整组摆位：人偶完整悬浮在框上方，输入框紧贴人偶下方。
     */
    internal fun layoutMiniTalk(imeBottom: Int) {
        val pv = petView
        val lpv = lp
        if (pv == null || lpv == null || !added) {
            return
        }
        enterPeek(pv)
        val dm = host.resources.displayMetrics
        val gap = Math.round(PetService.PET_HOVER_GAP_DP * dm.density)
        val barH = UiKit.dp(host, PetTalkInput.HEIGHT_DP)
        val edge = UiKit.dp(host, 8.0f)
        val width = Math.max(1, pv.petMaxWidth())
        lpv.x = Math.max(0, Math.min(dm.widthPixels - width, talkAnchorX - width / 2))
        var perch = pv.perchHeight()
        if (perch <= 0) {
            perch = Math.max(1, pv.fullHeight())
        }
        var bubble = 0
        if (bubbleUp) {
            pv.relayoutBubble(width, lpv.x)
            bubble = pv.neededBubbleSpaceFor(width, lpv.x)
            pv.setBubbleHeight(bubble)
        }
        val availBottom = dm.heightPixels - Math.max(0, imeBottom)
        val maxTalkY = availBottom - edge - barH
        var baseTalkY = talkFeetY + gap
        if (baseTalkY > maxTalkY) {
            baseTalkY = maxTalkY
        }
        var bottom = baseTalkY - gap
        val minTop = Math.round(dm.density * 4.0f)
        if (bottom - (perch + bubble) < minTop) {
            bottom = minTop + perch + bubble
            if (bottom + gap > maxTalkY) {
                bottom = maxTalkY - gap
            }
        }
        val talkY = bottom + gap
        var top = bottom - (perch + bubble)
        if (top < 0) {
            top = 0
        }
        petTopY = top
        lpv.width = width
        lpv.height = perch + bubble
        lpv.y = top
        safeUpdate()
        placeTalkInput(talkY, width)
    }

    /** 首次进 peek：锁死锚点（脚底 Y / 水平中心），清气泡、关贴边、切趴姿。 */
    private fun enterPeek(petView: PetView) {
        val lpv = lp!!
        talkFeetY = lpv.y + lpv.height
        talkAnchorX = lpv.x + Math.max(1, lpv.width) / 2
        peek = true
        ui.removeCallbacks(hideBubble)
        petView.clearBubble()
        petView.setBubbleHeight(0)
        bubbleUp = false
        petView.setEdgePeek(false, false)
        petView.setPeek(true)
    }

    /** 输入框摆放：发完消息 / 退出趴姿时只收起；否则按当前位置显示或移动。 */
    private fun placeTalkInput(talkY: Int, width: Int) {
        val input = talkInput
        if (input == null) {
            return
        }
        if (!talkInputWanted) {
            if (input.isShowing()) {
                input.hideQuiet()
            }
            return
        }
        val x = lp!!.x
        if (input.isShowing()) {
            input.moveTo(x, talkY, width)
        } else {
            input.show(x, talkY, width)
        }
    }

    /** 拖动人偶：先收掉迷你输入框（静默 hideQuiet，不 release），人偶照样能被拖走。 */
    private fun abortMiniTalkForDrag() {
        if (!peek) {
            return
        }
        peek = false
        talkInputWanted = false
        val input = talkInput
        if (input != null && input.isShowing()) {
            input.hideQuiet()
        }
        petView?.setPeek(false)
        talkIme = 0
    }

    /** 退出趴姿：清 peek 并复位窗口（不改 talk 实例，供下次复用）。 */
    fun exitPeek() {
        if (peek) {
            peek = false
            applyPetShown(true)
            if (petView == null || lp == null || !added) {
                return
            }
            val f = host.resources.displayMetrics.density
            val i = host.resources.displayMetrics.widthPixels
            val i2 = host.resources.displayMetrics.heightPixels
            val round = Math.round(f * 8.0f)
            val petView = petView!!
            petView.setPeek(false)
            petView.clearBubble()
            petView.setBubbleHeight(0)
            petView.setEdgePeek(edgePeek, edgeAtLeft)
            bubbleUp = false
            val petWidth = petView.petWidth() / 2
            val rest = restorePivot()
            var i3 = if (rest != null) rest[0] else (i - round) - petWidth
            val i4 = if (rest != null) rest[1] else Math.round(i2 * 0.8f)
            if (!edgePeek) {
                i3 = Math.max(petWidth, Math.min(i - petWidth, i3))
            }
            placeByPivot(i3, clampFeetY(i4, i2, round))
        }
        talkIme = 0
        talkInputWanted = false
        val input = talkInput
        if (input != null && input.isShowing()) {
            input.hideQuiet()
        }
    }

    /** 兼容旧 PetWindowController.layoutPeek 的趴姿布局（当前无调用方，保留对外契约）。 */
    fun layoutPeek(i: Int, i2: Int, i3: Int, i4: Int) {
        if (petView == null || lp == null || !added) {
            return
        }
        if (!peek) {
            peek = true
            val valueAnimator = snapAnim
            if (valueAnimator != null) {
                valueAnimator.cancel()
            }
            ui.removeCallbacks(hideBubble)
            petView!!.clearBubble()
            petView!!.setBubbleHeight(0)
            bubbleUp = false
            petView!!.setEdgePeek(false, false)
            petView!!.setPeek(true)
        }
        val f = host.resources.displayMetrics.density
        val i5 = host.resources.displayMetrics.widthPixels
        val petView = petView!!
        val windowWidth = petView.windowWidth()
        val baseHeight = petView.baseHeight()
        val round = Math.max(Math.round(8.0f * f), Math.round(petView.petWidth() * PetView.PEEK_OVERLAP_RATIO))
        applyPetShown(true)
        val minTop = Math.round(f * 4.0f)
        petTopY = Math.max(minTop, (i2 - baseHeight) + round)
        val lp = lp!!
        lp.width = windowWidth
        lp.height = baseHeight
        lp.x = Math.max(0, Math.min(i5 - windowWidth, i + ((i3 - windowWidth) / 2)))
        lp.y = petTopY
        safeUpdate()
    }

    // ============================ 台词 / 气泡入口 ============================

    fun say(str: String, j: Long) {
        val pv = petView
        if (pv == null || (!added && !away)) {
            return
        }
        ui.removeCallbacks(hideBubble)
        pv.showBubble(str)
        if (peek) {
            bubbleUp = true
            layoutMiniTalk(talkIme)
        } else {
            expandBubble(pv.neededBubbleSpace())
        }
        if (!away) {
            ui.postDelayed(hideBubble, j)
        }
    }

    /** 收气泡：peek 态只收气泡并重排整组，非 peek 态沿用原逻辑。 */
    internal fun hideBubbleNow() {
        val pv = petView
        if (peek && pv != null) {
            pv.clearBubble()
            pv.setBubbleHeight(0)
            bubbleUp = false
            layoutMiniTalk(talkIme)
            return
        }
        collapseBubble()
    }

    /** 常驻气泡：显示后不排自动消失，相位由 PetTalk 自己管。 */
    internal fun saySticky(str: String) {
        val pv = petView
        if (pv == null || (!added && !away)) {
            return
        }
        ui.removeCallbacks(hideBubble)
        pv.showBubble(str)
        if (peek) {
            bubbleUp = true
            layoutMiniTalk(talkIme)
        } else {
            expandBubble(pv.neededBubbleSpace())
        }
    }

    // ============================ 手势 / 拖拽 ============================

    fun handleTouch(motionEvent: MotionEvent): Boolean {
        val actionMasked = motionEvent.actionMasked
        if (actionMasked == 0) {
            downRawX = motionEvent.rawX
            downRawY = motionEvent.rawY
            lastRawX = downRawX
            lastMoveAt = System.currentTimeMillis()
            val lpv = lp!!
            val pv = petView!!
            downPivotX = lpv.x + pv.pivotLocalX()
            val pivotLocalY = lpv.y + pv.pivotLocalY() + bubbleSpaceIfUp()
            downPivotY = pivotLocalY
            curPivotX = downPivotX
            curPivotY = pivotLocalY
            dragging = false
            snapAnim?.cancel()
            pv.setTouchBusy(true)
            gesture.onDown()
            ui.removeCallbacks(tapResolve)
            ui.postDelayed(longPressTick, longPressTimeout.toLong())
            return true
        }
        if (actionMasked != 1) {
            if (actionMasked == 2) {
                val rawX = motionEvent.rawX - downRawX
                val rawY = motionEvent.rawY - downRawY
                val currentTimeMillis = System.currentTimeMillis()
                val rawX2 = (motionEvent.rawX - lastRawX) / Math.max(0.008f, (currentTimeMillis - lastMoveAt) / 1000.0f)
                lastRawX = motionEvent.rawX
                lastMoveAt = currentTimeMillis
                if (!dragging && Math.hypot(rawX.toDouble(), rawY.toDouble()) > touchSlop) {
                    dragging = true
                    gesture.cancel()
                    ui.removeCallbacks(longPressTick)
                    ui.removeCallbacks(tapResolve)
                    if (peek) {
                        abortMiniTalkForDrag()
                    }
                    if (talk?.hasBubble() != true) {
                        collapseBubble()
                    }
                    exitEdgePeek()
                    petView!!.setDragging(true)
                }
                if (dragging) {
                    curPivotX = downPivotX + rawX
                    curPivotY = downPivotY + rawY
                    applyDragPlacement()
                    petView!!.setDragVelocity(rawX2)
                    petView!!.lookAt(Math.max(-1.0f, Math.min(1.0f, rawX2 / 900.0f)))
                }
                return true
            }
            if (actionMasked != 3) {
                return false
            }
        }
        if (actionMasked == 3) {
            gesture.cancel()
            ui.removeCallbacks(longPressTick)
            ui.removeCallbacks(tapResolve)
        }
        if (dragging) {
            petView!!.setDragging(false)
            placeByPivot(Math.round(curPivotX), Math.round(curPivotY))
            settle()
        } else if (actionMasked == 1) {
            ui.removeCallbacks(longPressTick)
            if (gesture.onUp()) {
                ui.removeCallbacks(tapResolve)
                ui.postDelayed(tapResolve, PetService.TAP_WINDOW_MS)
            }
            talkOnSend = false
        }
        dragging = false
        petView?.setTouchBusy(false)
        return true
    }

    private fun bubbleSpaceIfUp(): Int {
        val pv = petView
        return if (bubbleUp && !peek && pv != null && pv.hasBubbleContent()) pv.bubbleHeight() else 0
    }

    /**
     * 手势响应派发：按用户配置的动作集合执行（交谈 / 聊天 / 继续 / 暂离）。
     * 【顺序】继续（仅在有气泡或加载中时独占）> 交谈 > 聊天；暂离最先判定并独占。
     */
    private fun dispatchGesture(g: String) {
        val actions = DollProfile.gestureActions(host, doll.id, g)
        if (actions.isEmpty()) {
            return
        }
        // 记录「当前交互的窗口」，让 PetActionRegistry 的 tap / 三击落到本窗口而不是主窗。
        host.activeWindow = this
        if (actions.contains(GestureActions.A_AWAY)) {
            enterAway(DollProfile.awaySeconds(host, doll.id))
            return
        }
        val t = talk
        if (actions.contains(GestureActions.A_CONTINUE) && t != null && (t.hasBubble() || t.isBusy())) {
            t.onBubbleTap()
            return
        }
        if (actions.contains(GestureActions.A_TALK)) {
            PetActionRegistry.perform("tap", host)
        }
        if (actions.contains(GestureActions.A_CHAT)) {
            openMiniTalk()
        }
    }

    internal fun petJump() {
        petView?.playJump()
    }

    internal fun openPanel() {
        val intent = Intent(host, MainActivity::class.java)
        intent.flags = 335544320
        try {
            host.startActivity(intent)
            return
        } catch (unused: Throwable) {
        }
        try {
            val pending = PendingIntent.getActivity(host, 2, intent, 201326592 or 67108864)
            pending.send()
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
    }

    // ============================ 暂离 ============================

    /** 进入暂离：整只人偶连同气泡 / 迷你输入框一起从屏幕消失，到点返回原位。 */
    internal fun enterAway(seconds: Int) {
        val pv = petView
        if (pv == null || (!added && !away)) {
            return
        }
        if (!away) {
            away = true
            ui.removeCallbacks(hideBubble)
            snapAnim?.cancel()
            talkInputWanted = false
            val input = talkInput
            if (input != null && input.isShowing()) {
                input.hideQuiet()
            }
            peek = false
            bubbleUp = false
            pv.setPeek(false)
            pv.clearBubble()
            pv.setBubbleHeight(0)
            val lpv = lp
            if (lpv != null) {
                lpv.height = pv.baseHeight()
                lpv.y = petTopY
            }
            // 【回归时间】记下本窗口的隐显时刻：暂离结束回显时用它判断是否该回「初始位置」。
            // 【r11】注意这**不是**「消失」—— 全服务级的消失时刻由 PetPrefs.setHiddenAt 记录，
            //   暂离不写那个键，所以「暂离独立、不重置回归计时」。
            hiddenAt = System.currentTimeMillis()
            awayHide()
        }
        val delay = awayCore.reset(seconds, System.currentTimeMillis())
        ui.removeCallbacks(awayResume)
        ui.postDelayed(awayResume, delay)
    }

    /** 暂离到期恢复：把同一 PetView 重新 addView 回原 lp 并启动动画。 */
    internal fun exitAway() {
        if (!away) {
            return
        }
        away = false
        awayCore.cancel()
        val pv = petView
        val lpv = lp
        if (pv == null || lpv == null || added) {
            return
        }
        if (!Settings.canDrawOverlays(host)) {
            PetNotifier.stopSafely(host)
            return
        }
        // 【回归时间】0 = 每次都回「初始位置」；N 秒 = 暂离超过 N 秒才回，否则原位出现。
        val hiddenMs = if (hiddenAt > 0L) System.currentTimeMillis() - hiddenAt else 0L
        hiddenAt = 0L
        val ret = DollProfile.returnSeconds(host, doll.id)
        if (ret <= 0 || hiddenMs >= ret * 1000L) {
            applyHomePivot(pv, lpv)
        }
        if (!awayShow(pv, lpv)) {
            PetNotifier.stopSafely(host)
        }
    }

    /**
     * 【回归时间】把窗口重摆到本只人偶的「初始位置」；用户没设过则保持原位（不动任何状态）。
     * 【为什么先 placeByPivot 再 addView】此刻窗口尚未挂回 WindowManager，
     *   `safeUpdate()` 会自行短路；这里只改 lp，等 [awayShow] 一次性挂上去。
     */
    private fun applyHomePivot(pv: PetView, lpv: WindowManager.LayoutParams) {
        val home = DollProfile.homePivot(host, doll.id) ?: return
        edgePeek = false
        edgeAtLeft = false
        pv.setEdgePeek(false, false)
        peek = false
        bubbleUp = false
        pv.setBubbleHeight(0)
        val screenW = Math.max(1, host.resources.displayMetrics.widthPixels)
        val screenH = Math.max(1, host.resources.displayMetrics.heightPixels)
        placeByPivot(Math.round(home[0] * screenW), Math.round(home[1] * screenH))
        lpv.height = pv.baseHeight()
        lpv.y = petTopY
        rememberPivot()
    }

    /** 手动停止 / 摘窗时取消本窗口的暂离恢复。 */
    internal fun cancelAway() {
        awayCore.cancel()
        ui.removeCallbacks(awayResume)
    }
}
