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
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollSizeSpec
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.pet.behavior.ActionSemantic
import com.dollhouse.app.ui.chat.MessageMenu
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.doll.DollActionPage
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
    var snapAnim: Springs.Handle? = null

    /* ============================ 甩抛（r22） ============================ */

    /**
     * 拖拽期间的**轴心轨迹**采样，抬手时用来估甩抛初速。
     *
     * 【为什么记轴心而不是手指】手指按下时未必落在轴心上，手指的位移 ≠ 人偶的位移；
     *   而且手指抬手瞬间的最后一点常带抖动。记轴心的实际位置更贴近「人偶怎么动的」。
     * 【为什么用 ArrayDeque】拖拽期间只追加、抬手裁一遍，头尾操作都是 O(1)；
     *   用 ArrayList 每次 removeFirst 是 O(n)，长时间拖拽会变慢。
     */
    private val dragTrail = ArrayDeque<ThrowPhysics.Sample>()

    /**
     * 甩抛的帧回调句柄（null = 不在飞）。
     *
     * 【为什么是 FrameCallback 而不是 Runnable】驱动用的是 `Choreographer`（对齐 vsync，
     *   不用不跑），它的注册/注销接口收的就是 `FrameCallback`。存成 Runnable 再强转
     *   只是给自己挖坑：类型不安全，且 removeFrameCallback 那一步要写无谓的 as。
     */
    private var throwRef: android.view.Choreographer.FrameCallback? = null

    /**
     * 甩抛代次。
     *
     * 【为什么需要】取消 rAF 后已入队的回调仍可能执行一次；没有代次校验的话，
     *   用户重新按住 → 取消飞行 → 那个残留回调又把位置写一次，表现为「刚抓住就弹了一下」。
     */
    private var throwToken = 0

    /**
     * 这次按下是否**打断了正在进行的飞行**。
     *
     * 【为什么需要】飞行中人偶可能在半空。若按下就 `cancelThrow()` 而不记一笔，
     *   「单击一下就松手」的路径（`dragging == false`）**不会**调用 `settle()`，
     *   人偶就永远停在半空 —— 重力没了、也没落位，看起来像卡住。
     *   记下来后，抬手时补一次 `settle()` 把它放到合法位置。
     */
    private var throwInterrupted = false
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
        // 【点完要回落】`dispatchGesture` 可能播了一个手动动作。
        //   **【v0.1.0·修 P0】这里不能在播完手势动作后立刻 interactionEnd** ——
        //   那会把刚播上的动作当场停掉（用户看到的「动作闪一帧就消失」）。
        //   现在的判据是：
        //   · 播了动作（MANUAL 语义在播）→ 让它自己播完，由播放层的 onFinish 回落；
        //   · 没播动作（只是弹气泡 / 开聊天）→ 立刻回落，把手势期间的档位收回来。
        if (petView?.isActionPlaying() != true) {
            petView?.interactionEnd()
        }
    }

    /** 长按到点：判定为长按手势（即便配置为「无」也消费这一次按压）。 */
    private val longPressTick = Runnable {
        val g = gesture.onLongPressTimeout()
        if (g != null) {
            ui.removeCallbacks(tapResolve)
            // 【长按也算交互】先让动作层腾位（长按常配「聊天/继续」这类 UI 行为，
            //   此时人偶不该继续播着循环待机动画）。
            petView?.interactionStart(ActionSemantic.LONG_PRESS)
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
        // 【尺寸标准化·唯一入口】人偶显示规格（大小 + 偏移）一次性灌进视图。
        //   大小折算进 scaleFactor（窗口宽高由它派生），偏移只影响绘制平移。
        //   改造前这里直接读 DollProfile.scale，与动作自己的 baseline 组成两把尺子，
        //   换动作时会忽大忽小；现在基础大小只有这一个来源。
        petView.setSizeSpec(DollSizeSpec.spec(host, doll.id))
        petScaleApplied = appliedScale()
        petView.setScaleFactor(petScaleApplied)
        // 【形象】统一走 DollStore.spriteOf：空路径自动回落内置 pet_front。
        // 【本轮·修「模型被切成块」】只有「没换过形象图」（回落到内置 pet_front）时
        //   才允许网格形变 —— 网格顶点是按内置人偶的五官比例写死的，
        //   套到用户自己的图上就会把画面切成许多小块各自乱窜。
        //   判据取「形象图路径是否为空」，而不是比较位图内容：前者是稳定的素材属性。
        petView.setSprite(DollStore.spriteOf(host, doll), doll.sprite.isEmpty())
        petView.setFlipped(DollProfile.flip(host, doll.id))
        petView.snapActiveCheck = { snapAnim?.isRunning == true }
        // 【r16·成败判据接线】打开动作播放层 —— 从此桌宠会读 DollAnim。
        //   是否真的会播，取决于该人偶有没有「待机默认动作」；
        //   没有配置时动作层 `hasContent()` 恒为 false，绘制路径与改造前逐行一致。
        petView.enableActions(doll.id)
        petView.actionViewportChanged = { requiredWidth -> resizeActionViewport(requiredWidth) }
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
            // 【r16】挂载后先播「出现」，播完（非循环动作）由 onFinish 回落到待机默认动作；
            //   没有配出现动作时，playSemantic 返回 false，于是直接进待机档。
            //   注意顺序：必须在 startAnim() 之后 —— 帧循环才是动作真正的推进力。
            startSemanticWithIdleFallback(ActionSemantic.APPEAR)
            PetBus.register(busListener)
            updateTouchable()
            // 【r19·随机走位】挂载后启动（若用户在设置里开了开关）。
            startWander()
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
        stopWander() // 【r19】摘窗时停走位
        cancelAway()
        ui.removeCallbacksAndMessages(null)
        snapAnim?.cancel()
        // 【r22·甩抛】飞行是 Choreographer 上的常驻回调，摘窗时不停会一直跑
        //   （每帧去 updateViewLayout 一个已移除的窗口 → 抛异常被我们自己 catch 掉，
        //    表现为「窗口关了 CPU 还在转」）。这里显式取消。
        cancelThrow()
        // 【顺序陷阱】cancelThrow() 会顺手 startWander()（把走位从飞行里接回来），
        //   而上面刚 stopWander() —— 顺序反了就等于「摘窗后又把走位排上」。
        //   虽然 wanderStep 每步都判 petView/lp 为空即返回，不会真崩，
        //   但留着一个空转的延时队列没有意义。这里再停一次，明确终态。
        stopWander()
        trailClear()
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
            // 【r16】彻底释放动作层（本窗口被丢弃，PetView 不再复用）。
            //   与 awayHide 的区别：那是「暂时离场」，PetView 会被 awayShow 重新挂回，
            //   所以那里只 stopAnim 不释放。
            petView.disableActions()
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
        // 【r22·甩抛】暂离期间窗口不在 WindowManager 上，飞行循环每帧 updateViewLayout
        //   会抛异常被 catch 掉，等于空转烧 CPU。这里显式停掉。
        //   （cancelThrow 会顺手 startWander，紧接着 stopWander 归位。）
        cancelThrow()
        stopWander()
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
        // 【r15·收敛 !!】本函数原先裸用 petView!!，但 detach() 会把 petView 置空 ——
        //   转屏 / 分屏回调若恰在摘窗后到达，这里就是一次 NPE。
        //   统一改成顶部一次取值 + 判空返回：后面全部走局部变量，语义不变、崩溃路径消失。
        val pv = petView ?: return
        val screenH = host.resources.displayMetrics.heightPixels
        val edge = Math.round(host.resources.displayMetrics.density * 8.0f)
        // 【r12】转屏 / 分屏后按**新方向的那套虚线框**复位（不再是屏幕边缘）。
        val box = boundsBox()
        val sig = (box[0].toInt() * 31) xor (box[1].toInt() * 17) xor (box[2].toInt() * 7) xor box[3].toInt()
        boxSig = sig
        if (edgePeek) {
            val petWidth = pv.petWidth()
            val inset = pv.edgeInsetPx()
            // 【r12】与 snapToEdge 同口径：贴虚线框的边；框坐标是 Float，窗口 x 需取整。
            val wPivot = Math.round(
                if (edgeAtLeft) (box[0] - inset) + (petWidth / 2) else (box[2] + inset) - (petWidth / 2)
            )
            placeByPivot(wPivot, clampFeetY(pivot[1], screenH, edge))
            rememberPivot()
            return
        }
        val half = pv.petWidth() / 2
        val x = if (box[2] - box[0] <= pv.petWidth()) {
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
        val lp = lp
        if (petView == null || lp == null || !added) {
            return
        }
        try {
            wm.updateViewLayout(petView, lp)
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
        // 【r15·收敛 !!】原来 `val petView = petView!!`，摘窗后若被调用即 NPE。
        //   本函数只做纯几何夹取，无视图时直接放行原值（等价于「不夹」），行为最安全。
        val petView = petView ?: return i
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
        // 【r15·收敛 !!】这是**真实崩溃路径**：手指抬起（handleTouch 的 ACTION_UP）会走到这里，
        //   而 detach() 恰好在同一帧把 petView / lp 置空时，旧代码四处 !! 会直接抛 NPE。
        //   统一在顶部一次取值 + 判空返回。
        val pv = petView ?: return
        val params = lp ?: return
        val i2 = host.resources.displayMetrics.heightPixels
        val round = Math.round(host.resources.displayMetrics.density * 8.0f)
        val round2 = Math.round(params.x + pv.pivotLocalX())
        val feetY = Math.round(params.y + pv.pivotLocalY() + bubbleHeightIfUp())
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
        val half = pv.petWidth() / 2
        val x = if (right - left <= pv.petWidth()) {
            Math.round(right - half)
        } else {
            Math.max(Math.round(left + half), Math.min(Math.round(right - half), round2))
        }
        placeByPivot(x, clampFeetY)
        rememberPivot()
    }

    /* ============================ 甩抛（r22） ============================ */

    /**
     * 记录一次拖拽轨迹采样（**只在拖动中调用**）。
     *
     * 【为什么在这里裁旧样本】拖拽可能是几十秒的慢速移动，不裁的话列表会一直长；
     *   而裁的成本是「一个 while 循环」，每帧最多删几个元素，可以忽略。
     */
    private fun trailPush(now: Long) {
        dragTrail.addLast(ThrowPhysics.Sample(now, curPivotX, curPivotY))
        // 留 2 倍窗口的余量：抬手时要再按 RELEASE_WINDOW_MS 裁一次，
        //   这里多留一点免得边界上恰好只剩一个点（无法算速度）。
        val keepFrom = now - ThrowPhysics.RELEASE_WINDOW_MS * 2
        while (dragTrail.isNotEmpty() && dragTrail.first().t < keepFrom) {
            dragTrail.removeFirst()
        }
    }

    /** 清空轨迹（按下 / 取消时调）。 */
    private fun trailClear() {
        dragTrail.clear()
    }

    /** 人偶轴心当前允许的活动范围（由虚线框定，与 `settle` 的夹取同源）。 */
    private fun throwBounds(pv: PetView): ThrowPhysics.Bounds {
        val box = boundsBox()
        val half = pv.petWidth() / 2f
        val minX = box[0] + half
        val maxX = box[2] - half
        // 框比人偶窄时（极端缩放）退化成「恒在框中心」，不让 minX > maxX。
        val (lo, hi) = if (maxX < minX) {
            val mid = (box[0] + box[2]) / 2f
            mid to mid
        } else {
            minX to maxX
        }
        // ---- 竖直方向：全部换算成「脚底 y」再交给积分器 ----
        //
        // 【坐标系必须统一，这里踩过坑】`ThrowPhysics.Bounds` 的 minY / groundY
        //   都是**脚底（轴心）的屏幕 y**，而 `boundsBox()` 返回的是**窗口框**的
        //   上下边。两者差一个 `pivotLocalY`（≈ 人偶身高，本机 543px）与气泡高度。
        //
        //   第一版直接把 `box[1]` 当 minY 传进去，实测「上甩一次人偶整只飞出屏幕」：
        //   因为 box[1]≈72 被当成「脚底最高只能到 72」，等于允许「头顶到 -471」。
        //
        //   正确关系（同一份几何，settle / clampFeetY 也用它）：
        //     windowY = feetY - pivotLocalY - bubble
        //   所以「窗口顶边不低于 box[1]」等价于「feetY ≥ box[1] + pivotLocalY + bubble」。
        val pivot = pv.pivotLocalY() + bubbleHeightIfUp()
        // `curPivotY` 在 ACTION_DOWN 时就是按这个式子记的脚底坐标，直接用，不再重复加。
        val feetNow = Math.round(curPivotY)
        // 落地高度：走 settle 的同一套夹取（上限 = 框底边），避免「飞完落地又被猛拉一下」。
        val groundFeet = clampFeetY(feetNow, host.resources.displayMetrics.heightPixels, 0).toFloat()
        // 顶边 = 让「窗口顶」刚好贴住框顶，再往上就是脑袋出框。
        val topFeet = box[1] + pivot
        return ThrowPhysics.Bounds(lo, hi, groundFeet, topFeet)
    }

    /**
     * 抬手：若轨迹表明是「甩」，进入飞行；否则走原来的就地吸附。
     *
     * 【为什么甩抛期间不做吸附】吸附是「贴到框边」的弹簧动画，与自由飞行互斥 ——
     *   同时跑会出现「一边飞一边被拉向边缘」。飞行静止后再由 [settle] 决定吸附。
     */
    private fun releaseOrThrow(pv: PetView) {
        val now = android.os.SystemClock.uptimeMillis()
        val raw = ThrowPhysics.estimate(dragTrail.toList(), now)
        val v = ThrowPhysics.applyPower(raw, ThrowPhysics.Params().throwPower)
        if (!ThrowPhysics.isThrow(v)) {
            // 不是甩：原地放下（原行为）。
            placeByPivot(Math.round(curPivotX), Math.round(curPivotY))
            settle()
            return
        }
        // 起飞前先退出贴边姿态：贴边是「脚在屏外」，飞行中要按正常姿态飞。
        if (edgePeek) {
            exitEdgePeek()
        }
        // 【飞行独占位置】走位会在每个 vsync 写 params.x/y，与飞行循环撞车 ——
        //   需求书明确禁止「同时存在多套相互冲突的运动循环」。停掉，落地再接回。
        stopWander()
        startThrow(pv, v)
    }

    /**
     * 启动甩抛飞行循环。
     *
     * 【为什么用 `Choreographer` 而不是 `postDelayed(16)`】
     *   `postDelayed` 与屏幕刷新率无关：60Hz 上偶尔会挤两帧、120Hz 上又慢半拍，
     *   视觉上是「飞起来一顿一顿」。`Choreographer` 的回调直接对齐 vsync，
     *   且**不用不跑** —— 静止后我们立刻移除回调，没有常驻轮询。
     */
    private fun startThrow(pv: PetView, v0: ThrowPhysics.Velocity) {
        cancelThrow()
        val token = ++throwToken
        val b = throwBounds(pv)
        var x = curPivotX
        var y = curPivotY
        var vx = v0.vx
        var vy = v0.vy
        var last = android.os.SystemClock.uptimeMillis()
        // 【为什么用 Choreographer 而不是自己起线程】位置必须写回窗口 LayoutParams，
        //   那是 View 体系的属性，只能在主线程改；Choreographer 本身就在主线程且对齐 vsync。
        val chor = android.view.Choreographer.getInstance()
        val frame = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (token != throwToken) {
                    return
                }
                val now = android.os.SystemClock.uptimeMillis()
                val dt = now - last
                last = now
                val s = ThrowPhysics.step(x, y, vx, vy, dt, b)
                x = s.x
                y = s.y
                vx = s.vx
                vy = s.vy
                // 直接写位置：这里不走 placeByPivot（那个会做夹取与 rememberPivot，
                //   每帧都 rememberPivot 会覆盖用户的落点记忆）。
                val params = lp
                val pvv = petView
                if (params == null || pvv == null || token != throwToken) {
                    cancelThrow()
                    return
                }
                params.x = Math.round(x - pvv.pivotLocalX())
                params.y = Math.round(y - pvv.pivotLocalY() - bubbleHeightIfUp())
                try {
                    // 与 attach / placeByPivot 同一条通道：窗口根视图就是 petView 本身。
                    wm.updateViewLayout(pvv, params)
                } catch (t: Throwable) {
                    // 窗口已被摘除：静默结束，不崩。
                    cancelThrow()
                    return
                }
                curPivotX = x
                curPivotY = y
                if (s.landed) {
                    // 落地反馈：让 PetView 做一次「着陆挤压」，与点击回弹同一套视觉语言。
                    pvv.playLandedFeedback()
                }
                if (ThrowPhysics.shouldStop(s)) {
                    cancelThrow() // 内部会顺带把走位接回来
                    // 静止后交给原来的吸附逻辑决定「停哪、要不要贴边」。
                    // 【顺序】先 settle 落位，再让走位从落好的位置继续 —— 反过来的话
                    //   走位会从「半空残余位置」起步，看到人偶先飘一下再被吸附拉回。
                    settle()
                    startWander()
                    return
                }
                throwRef = this
                chor.postFrameCallback(this)
            }
        }
        throwRef = frame
        chor.postFrameCallback(frame)
    }

    /**
     * 取消飞行（幂等）。
     *
     * ## 为什么「恢复走位」写在这里
     *
     * 飞行会独占 `params.x/y`（见 [wanderStep] 里的 `throwRef != null` 判据），
     * 而走位一旦被停，全局只有 `attach()` 会再启动它。所以**每一处停止飞行的地方**
     * 都必须把走位接回来，否则「甩一次 / 抓一次飞在空中的人偶」之后走位就永久失效。
     *
     * 放在这里而不是每个调用点：调用点有 5 处（落地、按下、摘窗、写窗口失败、
     * detach），逐个写必然漏。
     *
     * 【为什么摘窗之后调一下 startWander 也没事】`startWander` 内部会先查
     * `PetPrefs.wanderEnabled`，且 `wanderTick` 每步都取 `petView ?: return` /
     * `lp ?: return`，摘窗后即使被排上也是空转即返回；下一次 `attach()` 会
     * 重新 `startWander()` 并 `removeCallbacks` 去重，不会叠加。
     */
    private fun cancelThrow() {
        throwToken++
        val r = throwRef ?: return
        throwRef = null
        try {
            android.view.Choreographer.getInstance().removeFrameCallback(r)
        } catch (ignored: Throwable) {
        }
        // 只有「真的停了一段飞行」才需要接回走位（r == null 时上面已 return）。
        startWander()
    }

    fun snapToEdge(z: Boolean, i: Int) {
        // 【r15·收敛 !!】贴边动画的每一帧回调也在用 lp!!，摘窗后动画没停就会 NPE。
        val pv = petView ?: return
        val params = lp ?: return
        pv.wakeFrame()
        val petWidth = pv.petWidth()
        // 【r12】贴的是**虚线框**的左右边，不再是屏幕边缘（需求定案）。
        val box = boundsBox()
        val i3 = Math.max(1, box[2].toInt())
        // 【v0.1.0·裁截线按动作存】贴边前先把这只人偶「贴边动作」上的裁截线推给视图，
        //   否则用户在编辑页拖的线在真机上不生效（会一直按 0.68 算）。
        pv.setEdgeInset(DollAnim.edgeInset(host, doll.id))
        enterEdgePeek(z)
        val edgeInsetPx = pv.edgeInsetPx()
        // 【r12】框坐标是 Float，窗口 x 必须是 Int：这里就取整，后面直接当像素用。
        val i2 = Math.round(if (z) {
            (box[0] - edgeInsetPx) + (petWidth / 2)
        } else {
            (box[2] + edgeInsetPx) - (petWidth / 2)
        })
        val round2 = Math.round(i2 - pv.pivotLocalX())
        val space = if (bubbleUp && !peek && pv.hasBubbleContent())
            pv.neededBubbleSpaceFor(pv.windowWidth(), round2) else 0
        if (space > 0) {
            pv.setBubbleHeight(space)
        }
        params.width = pv.windowWidth()
        params.height = pv.baseHeight() + space
        params.y = Math.round(i - pv.pivotLocalY() - space)
        petTopY = params.y + space
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
        val fromX = params.x
        snapAnim = Springs.drive(Springs.snappy(), object : Springs.Listener {
            override fun onUpdate(p: Float) {
                // 【r15·收敛 !!】动画回调里原来直接 lp!! —— 摘窗后动画若在跑就 NPE。
                //   改成每帧重新取（取不到就自然停下，不再 update）。
                lp?.let {
                    it.x = Springs.lerpInt(fromX, round2, p)
                    safeUpdate()
                }
            }

            override fun onEnd() {
                lp?.let {
                    it.x = round2
                    safeUpdate()
                }
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
        // 【v0.1.0·接线】贴边动作固定壳终于能播了 —— 语义 `edge` → 贴边壳。
        //   【为什么不再用 playSemantic(EDGE)】那样只是「尝试按优先级播一次」：
        //   若此刻正播着手势动作（MANUAL 30），贴边(12) 会被拒绝，于是人偶贴到边上
        //   却仍播旧动作。贴边是**模式切换**，该直接重算基础态。
        petView.notifyEdgeChanged()
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
        // 【v0.1.0·接线】离边 → 回常驻壳。`lean` 此刻已归零，仲裁层的贴边探测返回 false，
        //   基础态自然选常驻。
        //   【为什么必须走 notifyEdgeChanged 而不是 playSemantic(IDLE)】贴边(12)
        //   > 常驻(10)，直接请求 IDLE 会被优先级裁决**拒绝** ——
        //   人偶离开屏幕边之后就一直停在贴边动作上（贴一次边，姿势永远不变）。
        petView.notifyEdgeChanged()
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
        // 【r15·收敛 !!】原写法 `if (lp == null || petView == null) return` 之后再全程 lp!! ——
        //   中间任何一处调用（relayoutBubble 等）若回调里摘窗，后面的 !! 就会 NPE。统一取局部。
        val petView = petView
        val params = lp
        if (params == null || petView == null) {
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
            val maxX = Math.round(box[2] + insetPx) - params.width
            params.x = Math.max(minX, Math.min(Math.max(minX, maxX), params.x))
        }
        petView.relayoutBubble(params.width, params.x)
        space = petView.neededBubbleSpaceFor(params.width, params.x)
        petView.setBubbleHeight(space)
        params.height = petView.baseHeight() + space
        params.y = petTopY - space
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
        val params = lp
        if (params == null || petView == null || !bubbleUp) {
            petView?.clearBubble()
            return
        }
        petView.clearBubble()
        petView.setBubbleHeight(0)
        params.height = petView.baseHeight()
        params.y = petTopY
        if (edgePeek && !peek) {
            params.x = edgePeekX()
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
     * 最终生效的缩放系数 = 人偶编辑页设的基础比例 × 尺寸规格里的大小。
     *
     * 【为什么两者相乘而不是只取一个】它们是**两个不同的用户动作**：
     * - `DollProfile.scale`：人偶编辑页里那根「缩放」滑条（历史功能，按人偶存）；
     * - `DollSizeSpec.size`：本轮新增的「大小」滑条（标准化之后的比例，默认 100%）。
     * 两者语义不同但都作用于基础大小。相乘能保证：
     * · 老用户没碰过新滑条（size = 100）→ 系数恒等于原来那个值，**行为完全不变**；
     * · 新用户只用新滑条 → 也一样直接生效。
     * 取「只用一个」会逼用户去猜改哪根才有效，反而更差。
     */
    private fun appliedScale(): Float {
        val base = DollProfile.scale(host, doll.id) / 100.0f
        val spec = DollSizeSpec.spec(host, doll.id)
        return base * DollSizeSpec.scaleOf(spec)
    }


    /**
     * 应用新的人偶缩放：改 PetView 系数后重算窗口宽高并重摆。
     * 【短路】比例没变直接返回；【聊天窗】peek 态走 layoutMiniTalk 重排整组。
     */
    /** 动作切换后只调整透明视口，保持人物中心、脚底与气泡位置不变。 */
    private fun resizeActionViewport(requiredWidth: Int) {
        val pv = petView ?: return
        val params = lp ?: return
        if (!added || peek || requiredWidth <= 0 || params.width == requiredWidth) return
        val pivotX = params.x + pv.pivotLocalX()
        val bubbleSpace = Math.max(0, params.height - pv.baseHeight())
        params.width = requiredWidth
        params.x = Math.round(pivotX - pv.pivotLocalX())
        params.height = pv.baseHeight() + bubbleSpace
        safeUpdate()
        if (bubbleSpace > 0) {
            pv.relayoutBubble(params.width, params.x)
        }
    }

    fun applyScale() {
        val petView = petView
        val params = lp
        if (petView == null || params == null || !added) {
            return
        }
        // 【先刷新规格】用户可能刚在「人偶管理 → 更多 → 大小」里改过，
        //   刷新时顺便把偏移也一起灌进去（偏移不需要重算窗口，只重绘）。
        val spec = DollSizeSpec.spec(host, doll.id)
        petView.setSizeSpec(spec)
        val target = appliedScale()
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
        val bubbleSpace = if (wasBubbleUp) Math.max(0, params.height - petView.baseHeight()) else petView.bubbleSpace()
        if (wasBubbleUp) {
            petView.setBubbleHeight(0)
            bubbleUp = false
        }
        var pivotX: Float = params.x + petView.pivotLocalX()
        val pivotY = params.y + petView.pivotLocalY()
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
        // 【r15·收敛 !!】lp 为空时原代码 NPE；这里改成直接返回（没窗口就没得锁锚点）。
        val lpv = lp ?: return
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
        val x = lp?.x ?: return
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
            // 【r15·收敛 !!】原为 `val petView = petView!!`：判空检查在本函数开头，
            //   但这中间调用了 applyPetShown（可能触发窗口更新），保险起见再取一次并判空。
            val pv = petView ?: return
            pv.setPeek(false)
            pv.clearBubble()
            pv.setBubbleHeight(0)
            pv.setEdgePeek(edgePeek, edgeAtLeft)
            bubbleUp = false
            val petWidth = pv.petWidth() / 2
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
        // 【r15·收敛 !!】整段原来散着 7 处 !!，现在开头一次取、判空返回，全走局部。
        val pv = petView ?: return
        val params = lp ?: return
        if (!added) {
            return
        }
        if (!peek) {
            peek = true
            val valueAnimator = snapAnim
            if (valueAnimator != null) {
                valueAnimator.cancel()
            }
            ui.removeCallbacks(hideBubble)
            pv.clearBubble()
            pv.setBubbleHeight(0)
            bubbleUp = false
            pv.setEdgePeek(false, false)
            pv.setPeek(true)
        }
        val f = host.resources.displayMetrics.density
        val i5 = host.resources.displayMetrics.widthPixels
        val windowWidth = pv.windowWidth()
        val baseHeight = pv.baseHeight()
        val round = Math.max(Math.round(8.0f * f), Math.round(pv.petWidth() * PetView.PEEK_OVERLAP_RATIO))
        applyPetShown(true)
        val minTop = Math.round(f * 4.0f)
        petTopY = Math.max(minTop, (i2 - baseHeight) + round)
        params.width = windowWidth
        params.height = baseHeight
        params.x = Math.max(0, Math.min(i5 - windowWidth, i + ((i3 - windowWidth) / 2)))
        params.y = petTopY
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
            val lpv = lp ?: return false
            val pv = petView ?: return false
            downPivotX = lpv.x + pv.pivotLocalX()
            val pivotLocalY = lpv.y + pv.pivotLocalY() + bubbleSpaceIfUp()
            downPivotY = pivotLocalY
            curPivotX = downPivotX
            curPivotY = pivotLocalY
            dragging = false
            snapAnim?.cancel()
            // 【r22·甩抛】按下即接管：正在飞的人偶被抓住，飞行立刻停止。
            // 【必须先记 throwInterrupted 再 cancelThrow】cancelThrow 会把
            //   throwRef 置空；置空之后就分不出「刚才在飞」了。
            throwInterrupted = throwRef != null
            cancelThrow()
            trailClear()
            // 【r19·睡觉唤醒】按下即唤醒（点击 / 拖动 / 长按都算）。
            if (sleeping) {
                exitSleep()
            }
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
                    // 【r15·收敛 !!】拖拽期间理论上不会摘窗，但摘窗 + 手指同帧的边界下
                    //   原来的 petView!! 就是一次 NPE；这里改成安全调用，行为完全不变。
                    petView?.setDragging(true)
                    // 【r16·交互要让出绘制位】拖动一开始就通知动作层腾位，
                    //   否则循环的待机默认动作会一直占着画面 ——
                    //   用户按住人偶拖，却看不到倾斜与抬升（明显的功能退化）。
                    petView?.interactionStart(ActionSemantic.DRAG)
                }
                if (dragging) {
                    curPivotX = downPivotX + rawX
                    curPivotY = downPivotY + rawY
                    applyDragPlacement()
                    // 【r22·甩抛】拖拽期间持续采样轴心位置，抬手时用来估初速。
                    //   位置必须写在 applyDragPlacement 之后 —— 那个方法会夹取边界，
                    //   采样当然要记「夹取后的真实位置」，否则估出来的速度是没发生过的。
                    trailPush(android.os.SystemClock.uptimeMillis())
                    petView?.setDragVelocity(rawX2)
                    petView?.lookAt(Math.max(-1.0f, Math.min(1.0f, rawX2 / 900.0f)))
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
            // 拖动路径自带收尾（releaseOrThrow → 起飞 或 settle），标记作废。
            throwInterrupted = false
            petView?.setDragging(false)
            // 【r22·甩抛】抬手不再直接吸附：先按轨迹判「是甩还是放下」。
            //   · 甩 → 进入飞行（重力 + 反弹 + 摩擦），静止后自己调 settle() 收尾；
            //   · 放下 → 走原来的就地吸附，与改造前完全一致。
            //   注意这里**不再**先 placeByPivot 再 settle：那会把位置定死，
            //   飞行起点就永远等于抬手点，甩出去的第一步是跳的。
            val pv = petView
            if (pv != null) {
                curPivotX = Math.round(curPivotX).toFloat()
                curPivotY = Math.round(curPivotY).toFloat()
                releaseOrThrow(pv)
            } else {
                settle()
            }
            // 【交互结束必须回落】拖动放下 → 回到待机档（若有默认动作就续播它）。
            petView?.interactionEnd()
        } else if (actionMasked == 1) {
            // 【飞行被打断后必须落位】只点一下就松手时 dragging == false，
            //   不走 releaseOrThrow；不补这一下，半空抓住的人偶会悬在那里。
            if (throwInterrupted) {
                throwInterrupted = false
                settle()
            }
            ui.removeCallbacks(longPressTick)
            // 【r19·摸头反馈】单击且落点在头部 1/3 区域时，额外触发 tap 动作。
            //   「头部」= 人偶精灵图的上 1/3 区域（以 PetView 坐标系计）。
            if (gesture.onUp()) {
                ui.removeCallbacks(tapResolve)
                ui.postDelayed(tapResolve, PetService.TAP_WINDOW_MS)
                val pv = petView
                if (pv != null && motionEvent.y < pv.height / 3f) {
                    // 摸头 = 点了一下头 —— 播 tap 语义动作（用户配的摸头反应）。
                    pv.playSemantic(ActionSemantic.TAP)
                }
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
     * **【r16 新增】**播掉手势配置里的 `act:<actionId>` 条目。
     *
     * 【为什么可能一次播多个】存储结构是集合，允许同一个手势同时绑多个动作
     *   （用户完全可能「双击 = 播个挥手 + 再播个眨眼」）。逐个 play 时后一个覆盖前一个，
     *   所以这里**只播最后一个**，并把顺序固定为存储顺序（稳定、可预期）。
     *   不并发播多个的理由：三个引擎共用一个 ActionPlayerCore 的一个绘制位，
     *   同时播只能显示一个，并发反而让人以为「有 bug 只播了最后一个」。
     */
    private fun playGestureActions(actions: Set<String>) {
        val pv = petView ?: return
        var last: String? = null
        for (one in actions) {
            val id = GestureActions.actId(one)
            if (id != null) {
                last = id
            }
        }
        if (last != null) {
            pv.playActionId(last)
        }
    }

    /**
     * 播一个语义动作；该人偶没配这个语义时回落到「基础态」。
     *
     * 【为什么需要「回落」这一步】以 [ActionSemantic.APPEAR] 为例：
     *   人偶挂载时理应出现，但绝大多数人不会去配「出现动作」。
     *   若不回落，挂载后就停在「什么都没播」状态，常驻动作也永远不会启动 ——
     *   用户看到的是「设了常驻动作，重启后不生效」。
     *
     * **【v0.1.0·修 P0】这里不能再覆盖 `player.onFinish`**。
     *   改造前它写的是 `pv.actionPlayer()?.onFinish = { playSemantic(IDLE) }` ——
     *   而 `PetView.enableActions` 早已把 onFinish 设成 `behavior.onActionFinished()`。
     *   两者互相覆盖：谁后设谁生效。结果是仲裁层收不到「播完了」的通知，
     *   非循环动作停在最后一帧再也不动（用户看到的「人偶卡住了」）。
     *   现在只负责「播出现」，播完的回落由仲裁层统一处理。
     */
    private fun startSemanticWithIdleFallback(semantic: String) {
        val pv = petView ?: return
        if (!pv.playSemantic(semantic)) {
            // 没配「出现」动作 → 直接进基础态（常驻 / 贴边由仲裁层按位置裁决）。
            pv.playSemantic(ActionSemantic.IDLE)
        }
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
        // 【r16·手势绑动作】`act:<actionId>` 条目 —— 手势直接播一个动作。
        //
        // 【为什么排在「暂离 / 继续」之后、与交谈 / 聊天并列】
        //   交谈（弹气泡）与聊天（开迷你聊天窗）是「手势的主功能」，
        //   播动作是**附加效果**。若先 return，用户选了「交谈 + 播动作」
        //   就只能得到其中一个；这里让两者都能生效，符合「多选」这一既有语义
        //   （存储层本来就是集合，见 GestureActions.parse）。
        //   例外是「暂离」—— 它会把窗口摘掉，播动作没意义，所以仍在它之后。
        playGestureActions(actions)
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
        // 【r19·快捷菜单】手势配了 menu 时弹出快捷菜单（聊天 / 动作 / 设置 / 睡觉）。
        if (actions.contains(GestureActions.A_MENU)) {
            showQuickMenu()
        }
    }

    /**
     * 【r19·快捷菜单】在人偶旁边弹一个悬浮菜单：聊天 / 动作列表 / 设置 / 睡觉。
     *
     * 【为什么用 UiKit.showDialog 而不是 Compose 弹窗】这里是命令式回调（非 Composable 作用域），
     *   且菜单要悬浮在桌宠之上（WindowManager 层），原生 Dialog 与既有确认框同一套壳。
     * 【位置】菜单跟着人偶走 —— 用窗口坐标定位，而不是屏幕居中。
     */
    private fun showQuickMenu() {
        val act = UiKit.findActivity(host) ?: return
        val labels = listOf("聊天", "动作", "设置", "睡觉", "调试状态")
        val actions = listOf<() -> Unit>(
            { openMiniTalk() },
            { openActionList() },
            { openPanel() },
            { enterSleep() },
            { showDebugInfo(act) }
        )
        MessageMenu.show(act, "快捷菜单", labels, actions, IntArray(labels.size))
    }

    /** 【r19·调试状态】弹窗显示当前人偶的运行状态（动作 / 帧率 / 内存 / 坐标）。 */
    private fun showDebugInfo(act: android.app.Activity) {
        val pv = petView ?: return
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
        val info = buildString {
            append("人偶：").append(doll.name).append('\n')
            append("动作：").append(pv.behaviorDebugInfo()).append('\n')
            append("睡觉：").append(if (sleeping) "是" else "否").append('\n')
            append("拖动中：").append(if (dragging) "是" else "否").append('\n')
            append("坐标：(").append(lp?.x ?: 0).append(", ").append(lp?.y ?: 0).append(")\n")
            append("内存：").append(usedMb).append(" MB\n")
            append("帧尺寸：").append(pv.width).append("×").append(pv.height)
        }
        UiKit.showDialog(act, "调试状态", android.widget.TextView(act).apply {
            text = info
            setPadding(24, 16, 24, 16)
        }, "关闭", null, null, null)
    }

    /** 打开该人偶的动作列表页（先回主页，再 push 动作页）。 */
    private fun openActionList() {
        // PetWindow 的宿主是 PetService（不是 Activity），findActivity 拿不到。
        // 先打开主界面（singleTask 唤回 MainActivity），再在里面 push 动作页。
        openPanel()
        val act = UiKit.findActivity(host) as? MainActivity ?: return
        PageStack.push(act, "doll:action:" + doll.id) { DollActionPage.buildPage(act, doll.id) }
    }

    /**
     * 【r19·睡觉模式】进入低功耗休眠：播 sleep 语义动作（循环），帧率降到最低档。
     *
     * 【为什么是「播动作」而不是「停帧」】睡觉也得有呼吸感 —— 完全静止像死机。
     *   sleep 动作由用户在动作页配置（循环动作，建议 1~2fps 的缓慢呼吸帧）；
     *   没配时回退到「待机帧率最低档 + 闭眼表情」，零配置也能用。
     * 【唤醒】任何交互（点击 / 拖动 / 长按）都会触发 `exitSleep` ——
     *   睡觉语义优先级最低（5），任何交互动作都能顶掉它。
     */
    internal fun enterSleep() {
        sleeping = true
        // 优先播用户配的 sleep 动作；没配则走「待机 + 闭眼」兜底。
        // 【r20·修 NPE】这里原来是 `petView?.playSemantic(...)!!` —— 安全调用 + 非空断言的
        //   矛盾组合：`petView` 为 null 时 `?.` 直接短路返回 **null**，随后的 `!!` 就把这个
        //   null 拆包 → NullPointerException。而 `petView` 确实会被置 null：
        //   `removeFromWindow()` / `detach()` 里都有 `this.petView = null`。
        //   触发路径：人偶窗口已摘除但 PetWindow 实例与快捷菜单回调仍存活时点「睡觉」。
        //   修法：显式取局部引用并判空，语义与原意一致（没配 sleep 动作即走兜底）。
        val view = petView
        // 【v0.1.0·强制播】sleep 优先级最低（5），走普通 play 会被正在播的常驻/贴边顶掉，
        //   用户点「睡觉」却看到人偶还在呼吸。所以这里用 forced 通道。
        if (view != null && !view.playSemanticForced(ActionSemantic.SLEEP)) {
            view.setExpression(4, 3600000L) // 闭眼表情，1 小时超时
        }
        view?.invalidate()
    }

    /** 唤醒：退出睡觉，回基础态（常驻 / 贴边由仲裁层按位置裁决）。 */
    internal fun exitSleep() {
        if (!sleeping) {
            return
        }
        sleeping = false
        petView?.clearExpression()
        // 回基础态：走 interactionEnd 会先 stop 再 restoreBase，
        //   贴边时自然回到贴边动作、站在中间时回到常驻动作。
        petView?.interactionEnd()
    }

    /** 当前是否在睡觉。 */
    internal var sleeping = false
        private set

    // ============================ 【r19】随机走位 ============================

    /**
     * 随机走位定时器：每隔 WANDER_INTERVAL_MS 在框内随机挑一个点附近缓慢移动。
     *
     * 【为什么是「点附近」而不是「全屏随机」】人偶是「活物」而不是弹球，
     *   全屏瞬移会出戏。每次在当前位置 ±WANDER_RANGE_DP 范围内挑点，
     *   用弹簧缓慢滑过去（时长与距离成正比，模拟「走」而不是「飞」）。
     * 【省电】睡觉时暂停走位；拖动 / 贴边 / 有气泡时也暂停（避免干扰交互）。
     * 【本次修复·走位顿挫】改造前用 `ValueAnimator + DecelerateInterpolator`：
     *   ① 走位时长与位移成正比、但曲线固定为纯减速 —— 起步瞬间是最高速，
     *      看起来是「被弹出去」而不是「迈步走」，与 App 其它位移（snappy 弹簧）手感不一致；
     *   ② 每次走位在结尾把窗口 x/y 直接写成 Round 结果，若下一段走位紧接着启动，
     *      起点取的是上一个已四舍五入的整数，误差逐次累积（长时间放置会「越走越偏」）。
     *   现在改用 `Springs.smooth()`（临界阻尼，不越界、不抖），
     *   并且走位不再覆盖正在跑的吸附动画（贴边/回位优先）。
     */
    private val wanderTick = object : Runnable {
        override fun run() {
            if (!added || sleeping || dragging || peek || talk?.hasBubble() == true) {
                scheduleWander() // 条件不满足也要续上，否则永远停了
                return
            }
            wanderStep()
            scheduleWander()
        }
    }

    private var wanderAnimator: Springs.Handle? = null

    /** 启动随机走位（由 PetPrefs.wanderEnabled 控制）。 */
    internal fun startWander() {
        if (!PetPrefs.wanderEnabled(host)) {
            return
        }
        scheduleWander()
    }

    /** 停止随机走位。 */
    internal fun stopWander() {
        ui.removeCallbacks(wanderTick)
        wanderAnimator?.cancel()
        wanderAnimator = null
    }

    private fun scheduleWander() {
        ui.removeCallbacks(wanderTick)
        ui.postDelayed(wanderTick, WANDER_INTERVAL_MS)
    }

    /** 走一步：在当前位置 ±WANDER_RANGE_DP 内挑点，用临界阻尼弹簧滑过去。 */
    private fun wanderStep() {
        val pv = petView ?: return
        val lpv = lp ?: return
        // 【本次修复·别抢吸附动画】贴边吸附 / 回原位正在跑时不要插一脚，
        //   否则两段动画同时写 params.x，会看到人偶在贴边和走位之间来回抽。
        if (snapAnim?.isRunning == true) {
            return
        }
        // 【r22·甩抛】飞行中同理：位置由 Choreographer 的积分循环独占。
        //   光靠 startThrow 里的 stopWander 不够 —— 若走位在飞行开始后才被
        //   PetService / 设置变更重新排上（startWander 是 internal，别处会调），
        //   这道判据保证它至少不会插进正在飞的这一段。
        if (throwRef != null) {
            return
        }
        val b = boundsBox()
        val range = pv.dp(WANDER_RANGE_DP)
        // 目标点 = 当前位置 ± range，夹到框内。
        val tx = Math.max(b[0], Math.min(b[2], (lpv.x + (Math.random() * 2 - 1) * range).toFloat()))
        val ty = Math.max(b[1], Math.min(b[3], (lpv.y + (Math.random() * 2 - 1) * range).toFloat()))
        val sx = lpv.x.toFloat()
        val sy = lpv.y.toFloat()
        val dist = Math.hypot((tx - sx).toDouble(), (ty - sy).toDouble()).toFloat()
        if (dist < 1f) {
            return
        }
        wanderAnimator?.cancel()
        // 【弹簧】smooth（ζ=1.00）：起步缓、落位稳，不会冲过目标点再拉回来。
        //   位移量级最大只有 ±120dp，spring 的收敛时长与距离无关，因此「远走近走」速度感一致 ——
        //   这正是「走路」该有的样子（原来时长按距离线性放大，远距离会拖成慢动作）。
        wanderAnimator = Springs.drive(Springs.smooth(), object : Springs.Listener {
            override fun onUpdate(p: Float) {
                lp?.let {
                    placeByPivot(
                        Springs.lerpInt(sx.toInt(), tx.toInt(), p),
                        Springs.lerpInt(sy.toInt(), ty.toInt(), p)
                    )
                }
            }

            override fun onEnd() {
                lp?.let {
                    placeByPivot(Math.round(tx), Math.round(ty))
                }
            }

            override fun onCancelled() {
                // 被打断（拖动 / 贴边 / 摘窗）：停在当前位置，不硬拽回起点也不跳终点。
            }
        })
    }

    private companion object {
        /** 走位间隔（ms）：每 8 秒走一步。 */
        const val WANDER_INTERVAL_MS = 8000L
        /** 单步最大位移（dp）：避免全屏瞬移。 */
        const val WANDER_RANGE_DP = 120f
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
