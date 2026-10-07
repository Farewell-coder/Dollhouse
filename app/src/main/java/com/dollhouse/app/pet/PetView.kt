package com.dollhouse.app.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】桌宠的绘制与手势：呼吸、眨眼、拖拽倾斜、贴边吸附。
 *
 * 【交互】位图由 PetService 注入；位置变化通过 PetBus 广播。
 *
 * 【坑】geometry() 在精灵图为空时把尺寸全置 0，此时 windowWidth()/petWidth() 会返回 0 —— 依赖尺寸做布局前必须先确认 setSprite() 已经调用过。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
class PetView(context: Context) : View(context) {
    companion object {
        private const val AHOGE_AMP = 0.022f
        private const val AHOGE_RU = 0.11f
        private const val AHOGE_RV = 0.09f
        private const val AHOGE_U = 0.3f
        private const val AHOGE_V = 0.06f
        private const val BLINK_SQUASH = 0.88f
        private const val BREATH_AMP = 0.01f
        private const val DRAG_POP = 1.04f
        internal const val DRAG_TILT_MAX = 8.0f
        const val EDGE_INSET = 0.68f
        const val EDGE_LEAN = 25.0f
        const val EXPR_DIZZY = 3
        const val EXPR_HAPPY = 2
        const val EXPR_NONE = 0
        const val EXPR_SURPRISED = 1

        /** 表情字号 / 人偶宽度：0.20 即 150dp 人偶配 30dp 字（= 原始 APK 的固定 30dp 锚点），随人偶缩放同步。 */
        private const val EMOTE_SIZE_RATIO = 0.20f

        /** 表情描边宽度 / 人偶宽度：0.02 即 150dp 人偶配 3dp 描边。 */
        private const val EMOTE_STROKE_RATIO = 0.02f

        /** 气泡字号 / 人偶宽度：0.095 即 150dp 人偶配 14dp 字，气泡与人偶同比例缩放。 */
        private const val BUBBLE_TEXT_RATIO = 0.095f
        private const val EYE_RU = 0.28f
        private const val EYE_RV = 0.065f
        private const val EYE_U = 0.5f
        internal const val EYE_V = 0.45f
        private const val GAZE_AMP = 0.019f
        private const val HAIR_AMP = 0.02f
        internal const val IDLE_HOP = 5
        private const val IDLE_LOOK = 2
        internal const val IDLE_NOD = 4
        private const val IDLE_NONE = 0
        private const val IDLE_STRETCH = 1
        private const val IDLE_YAWN = 3
        private const val MX = 28
        private const val MY = 36

        /**
         * 聊天框顶边与人偶的搭接量 / 人偶宽度：0.013 即 150dp 人偶搭约 2dp、225dp 人偶搭约 3dp。
         * 【调优】旧值 0.06（150dp 搭 9dp）在 3x 屏下实测 27px，视觉上像「被砍一刀」；
         *         用户要求「重合一丝丝」，收到 2~3dp 量级，仅洴住头顶一点点。
         */
        const val PEEK_OVERLAP_RATIO = 0.013f
        private const val TAILL_RU = 0.14f
        private const val TAILL_RV = 0.12f
        private const val TAILL_U = 0.06f
        private const val TAILL_V = 0.73f
        private const val TAILR_RU = 0.16f
        private const val TAILR_RV = 0.14f
        private const val TAILR_U = 0.93f
        internal const val TAILR_V = 0.7f
        private const val TAIL_AMP = 0.026f
        private const val VN = 1073

        /**
         * 【v2.10.2】熄屏低功耗的心跳间隔。
         *
         * 【为什么不彻底停帧】旧实现熄屏时 removeCallbacks 完全停摆，只等 SCREEN_ON 广播唤醒；
         *   个别 ROM（含 ColorOS）的该广播会晚到甚至丢失，人偶就一直黑着不回来。
         *   保留这一档慢心跳 + checkAwake() 自检，保证「用户开屏之后一定看得到人偶」。
         */
        internal const val HEARTBEAT_MS = 1000L

        /**
         * 【v2.10.2】贴边偷看时的最低帧率（250ms ≈ 4fps）。
         *
         * 【依据】人偶吸附在屏幕边缘 = 用户正在别处操作，此时呼吸与尾巴都是低频正弦，
         *   4fps 肉眼看不出差别；而待机小动作与眨眼（两个唯一的周期性动作）已在 PetAnimator 里压掉，
         *   所以本档能稳定住，不会被 60fps 顶回去。
         */
        internal const val EDGE_IDLE_MS = 250L

        private fun smoothstep(f: Float, f2: Float, f3: Float): Float {
            var f4 = (f3 - f) / (f2 - f)
            if (f4 < 0.0f) {
                f4 = 0.0f
            }
            if (f4 > 1.0f) {
                f4 = 1.0f
            }
            return f4 * f4 * (3.0f - (f4 * 2.0f))
        }

        private fun falloff(f: Float, f2: Float, f3: Float, f4: Float, f5: Float, f6: Float): Float {
            val f7 = (f - f3) / f5
            val f8 = (f2 - f4) / f6
            val sqrt = Math.sqrt((f7 * f7) + (f8 * f8).toDouble()).toFloat()
            if (sqrt >= 1.0f) {
                return 0.0f
            }
            val f9 = 1.0f - sqrt
            return f9 * f9 * (3.0f - (f9 * 2.0f))
        }
    }

    /** 【v2.10.2】熄屏低功耗标志：true 时不推进动画、不绘制，只跑 HEARTBEAT_MS 心跳自检。 */
    internal var lowPower = false

    /**
     * 【v2.10.2】屏幕尺寸自检回调，由 PetService 注入（指向 PetWindowController.checkScreenChanged）。
     *
     * 【为什么要它】不能指望 ROM 一定派发 Service 的 onConfigurationChanged：
     *   只要帧循环还在跑，这里每帧多一次 int 比较，旋转后最多一帧就能把窗口复位回屏内。
     */
    internal var screenTick: Runnable? = null

    /** 【v2.10.2】getLocationOnScreen 的复用缓冲（旧实现每帧 new int[2]，onDraw 里尤其浪费）。 */
    private val locBuf = IntArray(2)
    internal var affection = 0
    internal var animating = false
    internal var blinkCountdown = 0.0f
    internal var blinkT = 0.0f
    private val bmpPaint = Paint(7)
    private val bobAmp = dp(2.5f)
    private val boxA = FloatArray(IDLE_NOD)
    private val boxB = FloatArray(IDLE_NOD)
    private val boxBuf = FloatArray(IDLE_NOD)

    /** boxAt 的预分配复用数组：原来是两个 float[4] 字面量，每帧被调用多次会产生大量临时数组。 */
    private val boxAtX = FloatArray(IDLE_NOD)
    private val boxAtY = FloatArray(IDLE_NOD)

    // 网格缓存：记录上一次 buildMesh 的量化输入，未变则跳过重建。
    private var meshW = -1.0f
    private var meshH = -1.0f
    private var meshPhaseQ = Int.MIN_VALUE
    private var meshBlinkQ = Int.MIN_VALUE
    private var meshEyeQ = Int.MIN_VALUE
    private var meshGazeQ = Int.MIN_VALUE
    private var meshExpr = Int.MIN_VALUE
    private var meshValid = false
    private val bubbleEdge = Paint(1)
    private val bubblePaint = Paint(1)
    internal var dragLift = 0.0f
    internal var dragVx = 0.0f
    internal var dragging = false

    /** 人偶缩放系数：1.0 = 原始 150dp。由 PetService 从偏好读入。 */
    internal var scaleFactor = 1.0f
    private val emotePaint = TextPaint(1)
    internal var exprDuration = 0L
    internal var exprStartAt = 0L
    internal var expression = 0
    internal val frame: Runnable = object : Runnable {
        override fun run() {
            // 【v2.10.2】屏幕尺寸自检：不论哪一档帧率都先跑（成本只是两个 int 比较）。
            //   旋转后即使 ROM 不派发配置变化，这里也能把窗口复位回屏内（用户报的「切换后消失」）。
            val tickCb = this@PetView.screenTick
            if (tickCb != null) {
                tickCb.run()
            }
            // 【v2.10.2】低功耗档（熄屏）：不推进动画、不重绘，只自检「是不是已经亮屏了」。
            if (this@PetView.lowPower) {
                if (this@PetView.screenOn() && this@PetView.isShown) {
                    // 【坑】这里不能直接调 exitLowPower()：它内部会 removeCallbacks + postDelayed(16)，
                    //   而本方法尾部还会再 post 一次同一个 Runnable —— 同一 Runnable 排队两次，
                    //   之后每次执行又各自续一次，帧率会永久翻倍。
                    //   帧内自愈只改状态，续帧统一交给尾部这一次 post。
                    this@PetView.lowPower = false
                    this@PetView.lastFrame = SystemClock.uptimeMillis()
                    this@PetView.invalidate()
                }
            } else {
                this@PetView.tick()
            }
            if (this@PetView.animating) {
                this@PetView.postDelayed(this, this@PetView.nextFrameDelay())
            }
        }
    }
    internal var gaze = 0.0f
    internal var gazeCountdown = 0.0f
    internal var gazeTarget = 0.0f
    internal var idleAction = 0
    internal var idleDuration = 0.0f
    internal var idleEyeScale = 0.0f
    internal var idleLiftPx = 0.0f
    internal var idleNextIn = 0.0f
    internal var idleScaleY = 0.0f
    internal var idleT = 0.0f
    internal var idleTilt = 0.0f
    internal val jumpAmp = dp(18.0f)
    internal var jumpT = 0.0f
    internal var landT = 0.0f
    internal var lastFrame = 0L
    internal var lastIdleAction = 0
    internal var lean = 0.0f
    internal var peek = false
    internal var phase = 0.0f
    private var sprite: Bitmap? = null
    private val textPaint = TextPaint(1)
    internal var tilt = 0.0f
    private val verts = FloatArray(VN * 2)
    private val bubble = PetBubble(bubblePaint, bubbleEdge, textPaint)
    private val anim = PetAnimator(this)

    /** screenOn() 结果缓存时间：帧循环 30~60fps 下，原来每帧一次 Binder 太浪费。 */
    private var screenCheckedAt = 0L
    private var screenCached = true

    /**
     * 熄屏时彻底停掉帧循环（不是降频，是 removeCallbacks + 停 invalidate），
     * 亮屏立刻恢复 —— 这样屏灭期间 CPU 完全不被桌宠唤醒，醒来时动画是接着原状态跑的，
     * 不会出现「重新开始」或卡顿。
     */
    private val screenReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action == null) {
                return
            }
            invalidateScreenCache()
            val on = Intent.ACTION_SCREEN_ON == intent.action
            if (on) {
                // 亮屏：从当前状态接着跑，并立刻补一帧，避免第一帧还停在旧画面。
                exitLowPower()
                if (!animating) {
                    startAnim()
                }
                invalidate()
            } else {
                // 【v2.10.2】熄屏改为低功耗心跳（旧实现是 stopAnim 彻底停帧，广播丢了就再也回不来）。
                enterLowPower()
            }
        }
    }
    private var screenReceiverOn = false

    /** 分页用全文（未切片）；与 PetBubble 的 fullText 配套，clearBubble 时一并清空。 */
    private var bubbleFull: String? = null

    init {
        bubblePaint.color = UiKit.CARD
        bubblePaint.style = Paint.Style.FILL
        bubbleEdge.color = UiKit.TITLE
        bubbleEdge.style = Paint.Style.STROKE
        bubbleEdge.strokeWidth = dp(2.0f)
        textPaint.color = UiKit.TITLE
        textPaint.textSize = dp(14.0f)
        emotePaint.isFakeBoldText = true
        emotePaint.textAlign = Paint.Align.CENTER
        // 气泡字号随人偶宽度走（0.095 → 150dp 人偶配 14dp 字）；setScaleFactor 会重设，这里只给初值。
        // 注意目标是 this.textPaint（气泡用），textPaint 是 emotePaint 的局部别名，别写串。
        textPaint.textSize = dp(150.0f) * BUBBLE_TEXT_RATIO
        setLayerType(2, null)
    }

    /** 尺寸换算：统一走 UiKit，保留小数精度（原实现就是 float）。 */
    internal fun dp(f: Float): Float {
        return UiKit.dpf(context, f)
    }

    /**
     * 主题切换后刷新绘制用色。气泡与提示条的 Paint 是构造时定色的，不会自动跟随
     * ThemeManager 改字段，所以桌宠已在运行时由 PetService 在 REFRESH 时回调这里。
     */
    fun applyTheme() {
        bubblePaint.color = UiKit.CARD
        bubbleEdge.color = UiKit.TITLE
        textPaint.color = UiKit.TITLE
        invalidate()
    }

    /**
     * 下一帧的间隔：默认 60fps（16ms），静止待机时降到约 30fps（32ms）省电。
     *
     * 判定「需要全速」的任一条件成立即回 16ms：
     *   拖拽 / 跳跃 / 落地回弹 / 眨眼 / 待机小动作 / 表情 / 视线插值未收敛 / 倾斜回正。
     * 全部不成立（纯站立呼吸态，含 peek 贴顶静止）时用 32ms —— 呼吸与尾巴摆动是低频正弦，30fps 看不出差别。
     *
     * 【性能】peek 不再强制 60fps：聊天窗开着时桌宠与聊天窗是两个独立 overlay 窗口，
     *         人偶每帧重建 1073 顶点会抢渲染线程，是聊天区滚动掉帧的主因。
     */
    internal fun nextFrameDelay(): Long {
        // 【v2.10.2】低功耗档（熄屏）：1s 心跳。不再是旧实现的 250ms —— 熄屏时既不推进动画
        //   也不重绘，250ms 纯属空转；1s 足以保证亮屏后被 checkAwake 及时发现。
        if (lowPower) {
            return HEARTBEAT_MS
        }
        // 屏幕灭时 4fps 兜底（真正的低功耗在 enterLowPower，这里防的是
        // 广播晚到或个别 ROM 不发广播的情况）。isShown() 只看「已 attach + visibility」，
        // 熄屏时它仍为 true，所以必须再问一次屏幕开关，否则屏灭后桌宠照旧 30fps 空转。
        if (!isShown || !screenOn()) {
            return 250L
        }
        val busy = dragging
                || jumpT >= 0.0f
                || landT >= 0.0f
                || blinkT >= 0.0f
                || idleAction != 0
                || idleT >= 0.0f
                || expression != 0
                || dragLift > 0.002f
                || Math.abs(tilt) > 0.05f
                || Math.abs(gaze - gazeTarget) > 0.004f
        if (busy) {
            return 16L
        }
        // 【v2.10.2】贴边偷看且无任何动作：降到最低帧率。
        //   人偶吸在屏幕边缘 = 用户正在别处操作，此时只有低频呼吸与尾巴摆动，
        //   4fps 看不出差别；眨眼与待机小动作已由 PetAnimator 在 lean != 0 时压掉，
        //   所以本档不会被周期性动作顶回 60fps。
        if (lean != 0.0f) {
            return EDGE_IDLE_MS
        }
        return 32L
    }

    /**
     * 【v2.10.2】进入低功耗（熄屏）。
     *
     * 【与旧 stopAnim 的区别】旧实现在熄屏时 removeCallbacks 彻底停帧，只等 SCREEN_ON 广播唤醒；
     *   一旦该广播晚到或丢失，人偶就再也不动、亮屏也看不到（用户报的「切换后容易消失」同类问题）。
     *   现在改为「保留 1s 心跳 + 帧内自检屏幕」，亮屏最多 1 秒内自恢复。
     */
    internal fun enterLowPower() {
        if (lowPower) {
            return
        }
        lowPower = true
        animating = true
        invalidateScreenCache()
        removeCallbacks(frame)
        postDelayed(frame, HEARTBEAT_MS)
    }

    /** 【v2.10.2】退出低功耗（亮屏 / 恢复可见）：接着原状态继续跑，并立刻补一帧。 */
    internal fun exitLowPower() {
        if (!lowPower) {
            return
        }
        lowPower = false
        lastFrame = SystemClock.uptimeMillis()
        animating = true
        removeCallbacks(frame)
        postDelayed(frame, 16L)
        invalidate()
    }

    /**
     * 屏幕是否亮着。拿不到电源服务时按「亮着」处理，宁可多跑也不让桌宠在亮屏时卡住。
     * 【为何加缓存】isInteractive() 是跨进程调用，帧循环里每帧问一次纯属浪费；
     *   亮灭切换有 SCREEN_ON/OFF 广播兜底（广播里会强制刷新），500ms 的上限误差无感。
     */
    private fun screenOn(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - screenCheckedAt < 500L) {
            return screenCached
        }
        screenCheckedAt = now
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
            screenCached = pm == null || pm.isInteractive
        } catch (ignored: Throwable) {
            screenCached = true
        }
        return screenCached
    }

    /** 亮灭广播到达时强制刷新缓存，保证状态切换零延迟。 */
    private fun invalidateScreenCache() {
        screenCheckedAt = 0L
    }

    /** 由 attach/detach 时调用：注册熄屏/亮屏广播，重复调用安全。 */
    internal fun registerScreenReceiver() {
        if (screenReceiverOn) {
            return
        }
        try {
            val f = IntentFilter()
            f.addAction(Intent.ACTION_SCREEN_ON)
            f.addAction(Intent.ACTION_SCREEN_OFF)
            context.registerReceiver(screenReceiver, f)
            screenReceiverOn = true
            // 注册时如果屏已经灭着，直接按灭屏处理，别等下一次广播。
            if (!screenOn()) {
                stopAnim()
            }
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
    }

    /** 由 detach 时调用：注销广播。 */
    internal fun unregisterScreenReceiver() {
        if (!screenReceiverOn) {
            return
        }
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
        screenReceiverOn = false
    }

    fun setSprite(bitmap: Bitmap?) {
        sprite = bitmap
        invalidate()
    }

    /** 设置人偶缩放系数（1.0 = 原始大小）。调用方负责随后重算窗口尺寸。 */
    fun setScaleFactor(f: Float) {
        if (f <= 0.0f) {
            return
        }
        scaleFactor = f
        // 顺序要紧：先把字号设好，再让气泡按新字号重排；反过来的话重排用的还是旧字号。
        textPaint.textSize = dp(150.0f) * BUBBLE_TEXT_RATIO * f
        // 气泡的几何（圆角/内边距/尾巴/行距）也要跟着缩放，否则放大后人偶配一颗小气泡。
        bubble.setScale(f)
        invalidate()
    }

    fun current(): Bitmap? {
        return sprite
    }

    fun setEdgePeek(z: Boolean, z2: Boolean) {
        // 【v2.10.2】原实现这里硬写 25.0f / -25.0f，与上面的 EDGE_LEAN 常量重复；
        //   统一走常量（值相同，倾角行为不变），改倾角时才不会漏掉这一处。
        val f = if (z) (if (z2) EDGE_LEAN else -EDGE_LEAN) else 0.0f
        if (lean == f) {
            return
        }
        lean = f
        if (z) {
            blinkT = -1.0f
            blinkCountdown = 0.8f
        }
        invalidate()
    }

    fun edgeInsetPx(): Int {
        return Math.round(petWidth() * EDGE_INSET)
    }

    /**
     * 气泡可用的排版宽度（贴边时小于窗口宽）。
     *
     * 【贴边】人偶贴边偷看时窗口有一大截在屏幕外，气泡若按满窗宽排版，文字会排到屏幕外、
     *         画出来只剩一半（用户报的「趴着时对话框一半在外面」）。
     *         这里取「窗口 ∩ 屏幕」的可见区宽度，让排版与绘制边界一致。
     */
    fun bubbleAvailWidth(): Int {
        val loc = locBuf
        getLocationOnScreen(loc)
        return bubbleAvailWidthFor(Math.max(width, petWidth()), loc[0])
    }

    /**
     * 气泡可用的排版宽度（贴边时小于窗口宽）。
     *
     * 【贴边】人偶贴边偷看时窗口有一大截在屏幕外，气泡若按满窗宽排版，文字会排到屏幕外、
     *         画出来只剩一半（用户报的「趴着时对话框一半在外面」）。
     *         这里取「窗口 ∩ 屏幕」的可见区宽度，让排版与绘制边界一致。
     *
     * 【参数】windowW / windowX 必须显式传入——刚 updateViewLayout 过、还没走下一帧时
     *         getWidth() 与 getLocationOnScreen() 拿到的仍是旧值（缩放后尤其明显）。
     */
    fun bubbleAvailWidthFor(windowW: Int, windowX: Int): Int {
        val w = Math.max(1, windowW)
        val screenW = resources.displayMetrics.widthPixels
        val visible = Math.min(screenW, windowX + w) - Math.max(0, windowX)
        if (visible <= 0 || visible >= w) {
            return w
        }
        return visible
    }

    /** 窗口移动 / 缩放后按新的可见区强制重排气泡（不能靠 onWidth 兜底，它的宽度语义不同）。 */
    fun relayoutBubble(windowW: Int, windowX: Int) {
        bubble.relayout(bubbleAvailWidthFor(windowW, windowX).toFloat(), dp(1.0f))
        invalidate()
    }

    fun petWidth(): Int {
        if (current() == null) {
            return 0
        }
        return Math.round(dp(150.0f) * scaleFactor)
    }

    /**
     * 最大比例（界面 100% = PET_SCALE_MAX）下的人偶宽度。
     *
     * 【用途】迷你聊天的输入框宽度固定取本值：框宽不再随人偶比例浮动，
     *         用户把人偶调小后框宽不变，人偶在框上水平居中。
     * 【依据】petWidth() = dp(150) * scaleFactor，而 scaleFactor = petScale/100，
     *         上限 PET_SCALE_MAX=150 ⇒ 系数 1.5 ⇒ 宽度 225dp。
     *         直接引 PetPrefs 的常量，避免再造一个写死的 1.5。
     */
    fun petMaxWidth(): Int {
        if (current() == null) {
            return 0
        }
        return Math.round(dp(150.0f) * (PetPrefs.PET_SCALE_MAX / 100.0f))
    }

    private fun computeBox(fArr: FloatArray) {
        val z = dragging
        val f = if (z) DRAG_POP else 1.0f
        val f2 = if (z) DRAG_TILT_MAX else 0.0f
        boxAt(lean + f2, f, boxA)
        boxAt(lean - f2, f, boxB)
        val min = Math.min(boxA[0], boxB[0])
        val max = Math.max(boxA[1], boxB[1])
        val min2 = Math.min(boxA[2], boxB[2])
        val max2 = Math.max(boxA[3], boxB[3])
        fArr[0] = max - min
        fArr[1] = max2 - min2
        fArr[2] = -min
        fArr[3] = -min2
    }

    private fun boxAt(f: Float, f2: Float, fArr: FloatArray) {
        val radians = Math.toRadians(f.toDouble())
        val cos = Math.cos(radians).toFloat()
        val sin = Math.sin(radians).toFloat()
        val pw = (petWidth() * f2) / 2.0f
        val f3 = -pw
        val xs = boxAtX
        xs[0] = f3
        xs[1] = pw
        xs[2] = pw
        xs[3] = f3
        val f4 = -(fullHeight() * f2)
        val ys = boxAtY
        ys[0] = 0.0f
        ys[1] = 0.0f
        ys[2] = f4
        ys[3] = f4
        var f5 = Float.MAX_VALUE
        var f6 = -3.4028235E38f
        var f7 = -3.4028235E38f
        var f8 = Float.MAX_VALUE
        for (i in 0 until IDLE_NOD) {
            val f9 = xs[i]
            val f10 = ys[i]
            val f11 = (f9 * cos) - (f10 * sin)
            val f12 = (f9 * sin) + (f10 * cos)
            if (f11 < f5) {
                f5 = f11
            }
            if (f11 > f6) {
                f6 = f11
            }
            if (f12 < f8) {
                f8 = f12
            }
            if (f12 > f7) {
                f7 = f12
            }
        }
        fArr[0] = f5
        fArr[1] = f6
        fArr[2] = f8
        fArr[3] = f7
    }

    private fun headroom(): Float {
        return jumpAmp + dp(6.0f)
    }

    fun geometry(fArr: FloatArray) {
        if (current() == null) {
            fArr[3] = 0.0f
            fArr[2] = 0.0f
            fArr[1] = 0.0f
            fArr[0] = 0.0f
            return
        }
        if (peek) {
            // 【定案 v2.6】peek 态窗口高 = 完整身高（不再截到 0.72）：
            //   用户要求「人偶以完整形态悬浮在对话框上面」，
            //   不再是「半个头搭在框上」。
            val pw = petWidth()
            val f = pw.toFloat()
            fArr[0] = f
            fArr[1] = fullHeight().toFloat()
            fArr[2] = f / 2.0f
            fArr[3] = fullHeight().toFloat()
            return
        }
        computeBox(boxBuf)
        val buf = boxBuf
        fArr[0] = buf[0]
        fArr[1] = buf[1] + headroom()
        fArr[2] = buf[2]
        fArr[3] = buf[3] + headroom()
    }

    fun windowWidth(): Int {
        geometry(boxBuf)
        return Math.round(boxBuf[0])
    }

    fun pivotLocalX(): Float {
        geometry(boxBuf)
        return boxBuf[2]
    }

    fun pivotLocalY(): Float {
        geometry(boxBuf)
        return boxBuf[3]
    }

    fun petHeight(): Int {
        return baseHeight()
    }

    internal fun fullHeight(): Int {
        val bitmap = current() ?: return 0
        return Math.round(((petWidth() * bitmap.height) / bitmap.width).toFloat())
    }

    fun baseHeight(): Int {
        geometry(boxBuf)
        return Math.round(boxBuf[1])
    }

    fun setPeek(z: Boolean) {
        if (peek == z) {
            return
        }
        peek = z
        if (z) {
            blinkT = -1.0f
            blinkCountdown = 1.2f
        }
        invalidate()
    }

    fun perchHeight(): Int {
        // 【定案 v2.6】完整身高：人偶整只悬在框上，不裁下半截。
        return fullHeight()
    }

    // 【已删除 v2.5】perchHeightAtMax()：曾用于「框宽恒定为最大比例」方案。
    //   该方案会把人偶强行放大，已撤销；趴姿高度统一走 perchHeight()。
    fun bubbleSpace(): Int {
        return Math.round(dp(96.0f))
    }

    /** 气泡需要的高度；实现已拆到 PetBubble。 */
    fun neededBubbleSpace(): Int {
        return bubble.neededHeight(bubbleAvailWidth().toFloat(), dp(1.0f))
    }

    /** 指定窗口宽与位置时的气泡所需高度（窗口刚移动 / 缩放、布局还没生效时用）。 */
    fun neededBubbleSpaceFor(windowW: Int, windowX: Int): Int {
        return bubble.neededHeight(bubbleAvailWidthFor(windowW, windowX).toFloat(), dp(1.0f))
    }

    fun setBubbleHeight(i: Int) {
        bubble.setHeight(i)
        invalidate()
    }

    fun showBubble(str: String) {
        bubble.show(str, bubbleAvailWidth().toFloat(), dp(1.0f))
        invalidate()
    }

    /**
     * 分页显示一段长文本：第一屏只显示前 linesPerPage 行，返回全文总行数。
     *
     * 【为什么需要】用户要求「点一下进入下一片」，而不是一次展开到底。
     *   全文塞进 PetBubble 的 fullText，后续翻页用 bubblePage() 取片。
     * 【坑】总量行数必须在「全文」排版下量，不能拿切片后的文本量 —— 越切越短，页数会漂。
     */
    fun showBubblePaged(full: String, targetLines: Int): Int {
        bubbleFull = full
        val loc = locBuf
        getLocationOnScreen(loc)
        val w = bubbleAvailWidthFor(Math.max(width, petWidth()), loc[0]).toFloat()
        val d = dp(1.0f)
        // 【v2.6】改标点分页：返回值从「总行数」变为「总页数」。
        val pages = bubble.showPaged(full, targetLines, w, d)
        invalidate()
        return pages
    }

    /** 取第 index 页文本（翻页用）；必须与 showBubblePaged 的全文配套。 */
    fun bubblePage(index: Int): String? {
        if (bubbleFull == null) {
            return null
        }
        return bubble.page(index)
    }

    /** 切换气泡最大行数（截断态 / 展开态）；实现已拆到 PetBubble。 */
    fun setBubbleMaxLines(i: Int) {
        bubble.setMaxLines(i)
        invalidate()
    }

    /** 当前气泡文案是否被截断（决定点一下是展开还是收掉）；实现已拆到 PetBubble。 */
    fun bubbleHasOverflow(): Boolean {
        return bubble.hasOverflow()
    }

    fun clearBubble() {
        bubbleFull = null
        bubble.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        unregisterScreenReceiver()
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 挂上就开始盯屏幕开关：屏灭停帧、亮屏续跑，屏灭期间零唤醒。
        registerScreenReceiver()
    }

    private fun emoteColor(i: Int): Int {
        if (i == 1) {
            return UiKit.EMOTE_1
        }
        if (i == 2) {
            return UiKit.EMOTE_2
        }
        if (i != 3) {
            return UiKit.CARD
        }
        return UiKit.EMOTE_3
    }

    override fun onSizeChanged(i: Int, i2: Int, i3: Int, i4: Int) {
        super.onSizeChanged(i, i2, i3, i4)
        if (bubble.hasContent()) {
            // 【修 v0.0.1】必须走「可见区」口径重排：onWidth 用的是整窗宽，
            //   与 draw / neededHeight 的可见宽度语义不同。气泡展开会让 lp.height 变化
            //   而触发本回调，若按整窗宽重排，贴边时文字会被多折一行、进而与框错配
            //   （用户报的「吸附时串字 / 有字溢出」的持续来源）。
            val loc = locBuf
            getLocationOnScreen(loc)
            bubble.relayout(bubbleAvailWidthFor(i, loc[0]).toFloat(), dp(1.0f))
        }
    }

    /**
     * 网格重建的缓存门面：只有在形变输入真正变化时才重建 1073 顶点，否则直接复用上一帧。
     *
     * 判定用「量化后的输入」而不是精确相等：phase / blinkT / idleEyeScale / gaze 都是逐帧微变的
     * 连续量，精确比较等于每帧都重建。量化步长取得足够小（呼吸位移误差 < 0.5px），视觉无差别，
     * 但站立待机时的重建次数能从 60 次/秒降到约 25 次/秒。
     */
    private fun mesh(f: Float, f2: Float) {
        // 量化步长必须大于「一帧的相位增量」，否则待机时每帧都跨过一格、缓存永不命中：
        // phase 按 1.7 rad/s 推进，32ms 一帧增 0.0544 rad，故步长取 0.1（×10）；
        // 呼吸振幅 2.5dp，相位差 0.1 带来的位移误差约 0.25dp，肉眼不可见。
        val phaseQ = (phase * 10.0f).toInt()
        val blinkQ = (blinkT * 200.0f).toInt()
        val eyeQ = (idleEyeScale * 200.0f).toInt()
        val gazeQ = (gaze * 400.0f).toInt()
        if (meshValid && f == meshW && f2 == meshH && phaseQ == meshPhaseQ
                && blinkQ == meshBlinkQ && eyeQ == meshEyeQ && gazeQ == meshGazeQ
                && expression == meshExpr) {
            return
        }
        buildMesh(f, f2, phase, blinkT, idleEyeScale, gaze)
        meshW = f
        meshH = f2
        meshPhaseQ = phaseQ
        meshBlinkQ = blinkQ
        meshEyeQ = eyeQ
        meshGazeQ = gazeQ
        meshExpr = expression
        meshValid = true
    }

    private fun buildMesh(f: Float, f2: Float, phaseQ: Float, blinkQ: Float, eyeQ: Float, gazeQ: Float) {
        val sin = Math.sin(phaseQ.toDouble()).toFloat() * BREATH_AMP
        val f3 = phaseQ
        val f4 = 1.6f * f3
        val f5 = 2.6f * f3
        val f6 = f3 * 3.4f
        val f7 = blinkQ
        val sin2 = if (f7 < 0.0f) 0.0f else Math.sin(f7 * 3.141592653589793).toFloat()
        val i = expression
        var min = Math.min(if (i == 1) 1.2f else if (i == 2) 0.74f else 1.0f, eyeQ)
        if (sin2 > 0.0f) {
            min = Math.min(min, 1.0f - (sin2 * BLINK_SQUASH))
        }
        val f8 = 1.0f - min
        var i2 = 0
        for (i3 in 0..MY) {
            val f9 = i3 / MY.toFloat()
            for (i4 in 0..MX) {
                val f10 = i4 / MX.toFloat()
                var f11 = 0.0f - ((1.0f - f9) * sin)
                var wave = ((((((smoothstep(TAILR_RU, 0.44f, Math.abs(f10 - EYE_U)) * HAIR_AMP) * smoothstep(EYE_RU, 0.95f, f9)) * Math.sin((((4.2f * f9) + f4) + (1.4f * f10)).toDouble()).toFloat()) + 0.0f) + ((falloff(f10, f9, TAILR_U, TAILR_V, TAILR_RU, TAILL_RU) * TAIL_AMP) * Math.sin(f5.toDouble()).toFloat())) - ((falloff(f10, f9, TAILL_U, TAILL_V, TAILL_RU, TAILL_RV) * 0.0195f) * Math.sin((f5 - TAILR_V).toDouble()).toFloat())) + (falloff(f10, f9, AHOGE_U, AHOGE_V, AHOGE_RU, AHOGE_RV) * AHOGE_AMP * Math.sin(f6.toDouble()).toFloat())
                val eyeFall = falloff(f10, f9, EYE_U, EYE_V, EYE_RU, EYE_RV)
                if (eyeFall > 0.0f) {
                    f11 -= ((f9 - EYE_V) * f8) * eyeFall
                    wave += gazeQ * GAZE_AMP * eyeFall
                }
                verts[i2] = (f10 + wave) * f
                i2 += 1
                verts[i2] = (f9 + f11) * f2
                i2 += 1
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        val bmp = current()
        if (w <= 0 || h <= 0 || bmp == null) {
            return
        }
        val pw = petWidth()
        val fh = fullHeight()
        // 【回退 v2.5】v2.4 曾在此强制按「界面最大比例」绘制精灵，导致人偶被调小后
        //   趴姿反而被放大 2 倍（用户报「框上面的人怎么也是最大模型的」），已撤销。
        //   趴姿与普通态同用当前缩放率；框宽也跟着取 petWidth()，两者边缘对齐。
        val sin = Math.sin(phase.toDouble()).toFloat() * bobAmp
        val f2 = jumpT
        val sin2 = if (f2 >= 0.0f) Math.sin(f2 * 3.141592653589793).toFloat() * jumpAmp else 0.0f
        val f3 = fh.toFloat()
        mesh(pw.toFloat(), f3)
        val pivotLocalX: Float
        val pivotLocalY: Float
        if (peek) {
            pivotLocalX = w / 2.0f
            pivotLocalY = ((h - baseHeight()) + fh).toFloat()
        } else {
            pivotLocalX = pivotLocalX()
            pivotLocalY = pivotLocalY()
        }
        val f4 = pivotLocalY
        val lift = (dragLift * dp(DRAG_TILT_MAX)) + idleLiftPx
        val f5 = (dragLift * 0.05f) + 1.0f
        val f6 = landT
        val f = if (f6 >= 0.0f) {
            1.0f - (Math.sin(f6 * 3.141592653589793).toFloat() * AHOGE_RU)
        } else {
            1.0f
        }
        val f7 = f * f5 * idleScaleY
        canvas.save()
        canvas.translate(pivotLocalX, ((sin + f4) - sin2) - lift)
        canvas.scale(f5, f7)
        canvas.rotate(lean)
        canvas.rotate(tilt + idleTilt)
        canvas.translate((-pw) / 2.0f, -fh.toFloat())
        val f8 = pivotLocalX
        canvas.drawBitmapMesh(bmp, MX, MY, verts, 0, null, 0, bmpPaint)
        canvas.restore()
        drawEmote(canvas, f8, (f4 - f3) - lift)
        if (!bubble.hasContent() || bubble.height() <= 0) {
            return
        }
        // 贴边偷看时窗口有约 0.68*人偶宽 在屏幕外，气泡要按「窗口在屏幕内的可见区」绘制，
        // 否则会有一半画到屏幕外（用户报的「趴着时对话框一半在外面」）。
        val loc = locBuf
        getLocationOnScreen(loc)
        bubble.draw(canvas, dp(1.0f), loc[0].toFloat(), resources.displayMetrics.widthPixels.toFloat())
    }

    private fun drawEmote(canvas: Canvas, f: Float, f2: Float) {
        if (expression == 0) {
            return
        }
        val uptimeMillis = SystemClock.uptimeMillis() - exprStartAt
        if (uptimeMillis >= 0) {
            val j = exprDuration
            if (uptimeMillis > j) {
                return
            }
            val f3 = uptimeMillis / j.toFloat()
            val f4 = if (f3 < TAILR_RU) ((f3 / TAILR_RU) * 0.9f) + AHOGE_U else if (f3 < EYE_RU) 1.2f - (((f3 - TAILR_RU) / TAILL_RV) * 0.2f) else 1.0f
            val i = if (f3 < 0.65f) 255 else ((1.0f - ((f3 - 0.65f) / 0.35f)) * 255.0f).toInt()
            val f5 = (-dp(10.0f)) * f3
            emotePaint.color = emoteColor(expression)
            emotePaint.alpha = Math.max(0, Math.min(255, i))
            // 字号随人偶实时宽度走，保证「表情 : 人偶」比例恒定。
            emotePaint.textSize = petWidth() * EMOTE_SIZE_RATIO
            val ex = f + (petWidth() * AHOGE_U)
            val y = f2 + dp(6.0f) + f5
            canvas.save()
            canvas.translate(ex, y)
            canvas.scale(f4, f4)
            emotePaint.style = Paint.Style.STROKE
            emotePaint.strokeWidth = petWidth() * EMOTE_STROKE_RATIO
            emotePaint.color = UiKit.CARD
            emotePaint.alpha = Math.max(0, Math.min(255, i))
            canvas.drawText(emoteGlyph(expression), 0.0f, 0.0f, emotePaint)
            emotePaint.style = Paint.Style.FILL
            emotePaint.color = emoteColor(expression)
            emotePaint.alpha = Math.max(0, Math.min(255, i))
            canvas.drawText(emoteGlyph(expression), 0.0f, 0.0f, emotePaint)
            canvas.restore()
        }
    }

    private fun emoteGlyph(i: Int): String = when (i) {
        1 -> "！"
        2 -> "♪"
        3 -> "？"
        else -> ""
    }

    // ---------- 以下为 PetAnimator 的转发薄壳，对外契约不变 ----------
    // 【v2.10.2】start/stop 同时清低功耗标志：attach / detach 时状态必须干净，
    //   否则下次挂载会带着上一轮的 lowPower=true 进去（虽然帧内自检能自愈，但没必要绕这一圈）。
    fun startAnim() {
        lowPower = false
        anim.startAnim()
    }

    fun stopAnim() {
        lowPower = false
        anim.stopAnim()
    }

    fun playJump() {
        anim.playJump()
    }

    fun setTilt(f: Float) {
        anim.setTilt(f)
    }

    fun pokeIdle(f: Float) {
        anim.pokeIdle(f)
    }

    fun setExpression(i: Int, j: Long) {
        anim.setExpression(i, j)
    }

    fun clearExpression() {
        anim.clearExpression()
    }

    fun setDragging(z: Boolean) {
        anim.setDragging(z)
    }

    fun setDragVelocity(f: Float) {
        anim.setDragVelocity(f)
    }

    fun lookAt(f: Float) {
        anim.lookAt(f)
    }

    fun setAffection(i: Int) {
        anim.setAffection(i)
    }

    fun tick() {
        anim.tick()
    }
}
