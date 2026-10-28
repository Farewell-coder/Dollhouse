package com.dollhouse.app.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollSizeSpec
import com.dollhouse.app.data.ActionViewport
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.pet.behavior.ActionSemantic
import com.dollhouse.app.pet.behavior.PetBehaviorController
import com.dollhouse.app.pet.render.PetDisplayHost
import com.dollhouse.app.pet.render.PetMetrics
import com.dollhouse.app.pet.render.PetTransform
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
    /**
     * 【r16·三链收敛】几何度量的视图侧实现。
     *
     * 【为什么用内部类而不是让 PetView 直接实现 PetMetrics】
     *   `PetMetrics` 的 `petWidth()/fullHeight()/bobAmp()/jumpAmp()` 与本类现有成员
     *   **同名但类型不同**（Int vs Float、属性 vs 函数），Kotlin 不允许这样共存
     *   （报 Conflicting declarations）。用一个内部类隔开，两边都保持原样、零冲突。
     *
     * 【为什么 pivot 要按 peek 分叉】改造前 `onDraw` 里趴姿与普通态用的是**不同锚点**：
     *   趴姿用「窗口水平中心 / 窗口底边」、普通态用视图自报的 pivot。
     *   这里把那条分支收进度量，于是 [PetTransform] 拿到的永远是「该用哪个锚点」的
     *   最终答案，不必自己判断姿态。
     */
    private inner class Metrics(private val peekMode: Boolean) : PetMetrics {
        override fun petWidth(): Float = this@PetView.petWidth().toFloat()
        override fun fullHeight(): Float = this@PetView.fullHeight().toFloat()
        override fun pivotLocalX(): Float =
            if (peekMode) width / 2.0f else this@PetView.pivotLocalX()
        override fun pivotLocalY(): Float =
            if (peekMode) {
                ((height - baseHeight()) + this@PetView.fullHeight()).toFloat()
            } else {
                this@PetView.pivotLocalY()
            }
        override fun bobAmp(): Float = this@PetView.bobAmp
        override fun jumpAmp(): Float = this@PetView.jumpAmp
        override fun ahogeRu(): Float = AHOGE_RU
        override fun dragTiltMaxPx(): Float = dp(DRAG_TILT_MAX)
    }

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
    internal var animating = false
    /**
     * 【r11】面朝方向：true = 水平镜像（朝左）。
     * 由 PetWindow 在挂载 / 刷新时按 DollProfile.flip 写入；绘制时把画布水平翻转。
     */
    internal var flipped = false
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

    /**
     * 人偶级显示规格（大小 + 水平 / 垂直偏移），由 `PetWindow` 从 [DollSizeSpec] 读入。
     *
     * 【默认值为什么是 DEFAULT】没设置过的用户（绝大多数）拿到全默认，
     *   [onDraw] 里 `shifted` 为 false，直接跳过 translate —— 与改造前逐像素一致。
     */
    internal var sizeSpec: DollSizeSpec.Spec = DollSizeSpec.Spec.DEFAULT
        private set
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
        bubblePaint.color = UiKit.card()
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
        bubblePaint.color = UiKit.card()
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
    /** 触摸忙：手指按下期间置位，帧循环立即回到 16ms；抬手 / CANCEL 释放。 */
    internal var touchBusy = false
        private set

    /** 本轮眨眼已进行的毫秒数（-1 = 未在眨眼）；由 PetAnimator 推进，相位交给 BlinkTimeline。 */
    internal var blinkElapsed = -1.0f

    /**
     * 吸附弹簧运行探测，由 PetWindowController 注入（读 host.snapAnim.isRunning）。
     * 【为何用探测而非布尔】开始 / 完成 / 取消三种结束路径都直接反映在 isRunning 上，
     *   不必在每个 cancel() 调用点手动同步标志、也不会漏清。
     */
    internal var snapActiveCheck: (() -> Boolean)? = null

    /** 吸附弹簧是否在跑（涵盖吸附开始 / 完成 / 取消）。 */
    internal fun snapActive(): Boolean {
        return snapActiveCheck?.invoke() == true
    }

    /**
     * 当前是否有动作需要全速帧。
     * 【r16·帧率收敛】判定逻辑已搬进 [PetFramePolicy.needsFullSpeed] —— 纯逻辑、可单测。
     *   本方法保留为薄壳：一是不改对外调用点（PetAnimator / PetWindow 多处依赖），
     *   二是把「视图字段 → 判定输入」这一步留在视图层（判定本身不认识 PetView）。
     */
    internal fun frameBusy(): Boolean {
        // 【r16】动作在播 → 必须全速。这是**纯新增**分支：
        //   改造前不存在「动作在播」这种状态，所以对原有行为零影响；
        //   而少了它，动作帧率会被待机档的 32ms 拖成 15fps（用户会看到卡顿）。
        if (action?.isAnimated() == true) {
            return true
        }
        return PetFramePolicy.needsFullSpeed(currentState())
    }

    /** 把当前视图状态打包成帧率判定的输入（[PetFramePolicy.State]）。 */
    private fun currentState(): PetFramePolicy.State {
        return PetFramePolicy.State(
            touchBusy = touchBusy,
            snapActive = snapActive(),
            dragging = dragging,
            jumpT = jumpT,
            landT = landT,
            blinkT = blinkT,
            idleAction = idleAction,
            idleT = idleT,
            expression = expression,
            dragLift = dragLift,
            tilt = tilt,
            gaze = gaze,
            gazeTarget = gazeTarget
        )
    }

    /** 设置触摸忙标志；由忙转闲不唤醒（让帧循环自然落回待机档）。 */
    internal fun setTouchBusy(z: Boolean) {
        if (touchBusy == z) {
            return
        }
        touchBusy = z
        if (z) {
            wakeFrame()
        }
    }

    /**
     * 立即把帧循环拉回 16ms：先撤掉旧的排队，再补一个 —— 保证同一 Runnable 只有一个实例。
     * 【为何不能直接再 post 一次】同一 Runnable 排队两份，之后每次执行又各自续一次，帧率永久翻倍。
     * 【为何重置 lastFrame】从 250ms 档突然跳到 16ms 时，tick() 的 delta 上限 120ms
     *   会让呼吸相位一次跨约 0.2rad（肉眼可见跳一下）；把时间基准挪到当下即可消除。
     */
    internal fun wakeFrame() {
        if (!animating) {
            return
        }
        removeCallbacks(frame)
        lastFrame = SystemClock.uptimeMillis()
        postDelayed(frame, 16L)
    }

    /**
     * 下一帧延迟（毫秒）。
     * 【r16·帧率收敛】五档判定已搬进 [PetFramePolicy.delayMs]。
     *   改造前这里的 `250L` 出现两次、语义完全不同（屏灭兜底 / 贴边低耗），
     *   是本工程最容易改错的地方之一；现在两个 250 变成两个有名字的常量。
     */
    internal fun nextFrameDelay(): Long {
        // 【本次修复·熄屏空转】改造前这里是：
        // ```kotlin
        // if (action?.isAnimated() == true) return PetFramePolicy.MS_TOUCH_BUSY  // 16ms
        // return PetFramePolicy.delayMs(...)
        // ```
        // 动作在播就**无条件**给 16ms，把后面的 lowPower / screenOff 判定一起短路了 ——
        // 熄屏后只要有一个循环动作在播，帧循环仍以 60fps 空转，既费电又与
        // 「熄屏时 CPU 完全不被桌宠唤醒」的既定策略冲突（用户反馈的「放一晚掉电」）。
        // 现在把「动作在播」降级成**提帧的输入**而不是最终裁决：熄屏/不可见仍然优先。
        val acting = action?.isAnimated() == true
        return PetFramePolicy.delayMs(
            s = currentState(),
            lowPower = lowPower,
            shown = isShown,
            screenOn = screenOn(),
            lean = lean,
            actionAnimating = acting
        )
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
            // 【r25·修 8.0+ 注册失败】从 Android 8.0（API 26）起，
            //   隐式条件广播**必须**用 ContextCompat.registerReceiver 显式声明导出性，
            //   否则系统会抛 IllegalArgumentException / 或按显式广播语义导致收不到。
            //   这里用 ContextCompat 并标记 NOT_EXPORTED：
            //   亮灭屏是系统发出的广播，标记 NOT_EXPORTED 完全不影响接收，
            //   但杜绝「任意第三方 App 伪造亮灭屏 Intent 让桌宠反复起停帧」的可能。
            //   【为什么必须换】原写法在 targetSdk 34 的机器上虽然一般能跑，
            //   但属于「依赖系统宽容」的写法，部分 ROM 会直接静默失联 ——
            //   届时表现是「熄屏后人偶还在耗电跑帧」，很难定位。
            androidx.core.content.ContextCompat.registerReceiver(
                context, screenReceiver, f,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
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

    /**
     * **【本轮·修「模型被切成块、零件乱窜」】**是否允许对当前形象做网格形变。
     *
     * ## 问题
     *
     * 内置人偶图（`pet_front`）是按「眼睛在 45% 高度、呆毛在顶部、尾巴在两侧」
     * 这些**写死的归一化坐标**画的。`buildMesh` 依赖这套坐标做呼吸 / 摆发 / 眨眼位移，
     * 对内置换上的图 28×36 个网格顶点时，视觉上完全成立。
     *
     * 但用户一旦换了自己的形象图（照片、插画、任意比例的图），
     * 那些坐标对它毫无意义 —— 网格会把「随机位置的一小块像素」当成眼睛或头发去推，
     * 结果就是整个人像被**切成许多小块各自乱动**（这正是用户反馈的现象）。
     *
     * ## 修法
     *
     * 只有「形象图是内置人偶」时才允许网格形变；其它一律**整幅直绘**。
     * 姿态变换（倾斜、跳跃、拖拽抬升、贴边旋转）仍然照常施加 —— 那是整体的刚体变换，
     * 不会把画面切碎，动态表现不丢。
     *
     * 【为什么不在 buildMesh 里做判断】那边每帧调用几十次，判断放这里一次就够；
     *   而且「这张图能不能形变」是**素材属性**，属于设置阶段的事，不是绘制阶段的事。
     */
    internal var deformSprite = true

    /** 设置形象图，并声明它是否允许网格形变（见 [deformSprite]）。 */
    fun setSprite(bitmap: Bitmap?, deform: Boolean) {
        deformSprite = deform
        setSprite(bitmap)
    }

    /**
     * 【r11】设置面朝方向（水平镜像）。值没变则直接返回，避免每帧白白重绘。
     * 【为什么镜像的是画布而不是位图】镜像画布只需一个负缩放，零内存开销；
     *   翻转位图要重新分配一张同样大的 Bitmap（人偶图不小），每切一次都吃内存。
     */
    fun setFlipped(z: Boolean) {
        if (flipped == z) {
            return
        }
        flipped = z
        invalidate()
    }

    /**
     * 设置人偶级显示规格（大小 + 偏移）。由 `PetWindow` 在挂载 / 刷新时调用。
     *
     * 【为什么大小也要走这里】大小本质上是「窗口宽高按多少算」，而窗口宽高在
     *   [petWidth] 里由 `scaleFactor` 决定。规格里的 size 与 `DollProfile.scale`
     *   是**同一件事的两种表达**（一个是「相对标准化的百分比」，
     *   一个是「相对 150dp 的百分比」），所以这里把 size 折算进 scaleFactor，
     *   而不是在绘制时再乘一次 —— 否则会双重缩放。
     *
     * @return true = 值确实变了（调用方需要重算窗口尺寸）
     */
    internal fun setSizeSpec(s: DollSizeSpec.Spec): Boolean {
        if (s == sizeSpec) {
            return false
        }
        sizeSpec = s
        invalidate()
        return true
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
        // 【r25·修气泡不重排】比例变了 → 字号变了 → 换行位置也变了。
        //   旧实现只改了 textSize，没有让气泡重新排版；于是把「窄档」切到「宽档」
        //   之后，气泡仍在按旧字号的宽度换行，文字被截断或留大片空白。
        //   【为什么先判 hasContent】没内容时 relayout 是纯空转，每次比例变化都跑一次没必要。
        if (bubble.hasContent()) {
            bubble.relayout(bubbleAvailWidth().toFloat(), dp(1.0f))
        }
        // 【v0.1.0】比例变了 → 窗口尺寸也变了，动作播放层的缩放缓存必须失效，
        //   否则要等下一帧发现帧图宽高变化（其实没变）才重算，表现为「调大后动作偏小」。
        action?.resetScale()
        action?.refreshViewport()
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
            blinkElapsed = -1.0f
            blinkCountdown = 0.8f
        }
        invalidate()
    }

    fun edgeInsetPx(): Int {
        return Math.round(petWidth() * edgeInset)
    }

    /**
     * **【v0.1.0】贴边露出的比例**（0.68 = 人偶宽度的 68% 在屏幕外）。
     *
     * 【为什么要变成可变】用户可以在「贴边动作」的编辑页拖一条裁截线，决定人偶贴边时
     *   露出多少。裁截线存在那个动作上（按动作存，不按人偶存），
     *   所以每次吸附到边缘前由 `PetWindow.snapToEdge` 推一次进来。
     *
     * 【默认值 = 历史硬编码值】没有推过时与改造前逐像素一致。
     */
    private var edgeInset = EDGE_INSET

    /** 设置贴边露出比例（会被夹到合法区间，防档坏了把人偶整只藏到屏幕外）。 */
    fun setEdgeInset(v: Float) {
        val raw = if (v.isNaN()) EDGE_INSET else v
        val f = Math.max(0.0f, Math.min(0.95f, raw))
        if (edgeInset == f) {
            return
        }
        edgeInset = f
        invalidate()
    }

    /** 当前贴边露出比例（供窗口层回读）。 */
    fun edgeInset(): Float = edgeInset

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
        val baseWidth = buf[0]
        val baseHeight = buf[1] + headroom()
        val actionWidth = action?.viewport()?.requiredWidth ?: 0
        // 动作需要更宽画布时向两侧对称扩展；人物中心与脚底锚点保持不变。
        val extra = Math.max(0, actionWidth - Math.round(baseWidth))
        fArr[0] = baseWidth + extra
        fArr[1] = baseHeight
        fArr[2] = buf[2] + extra / 2.0f
        fArr[3] = buf[3] + headroom()
    }

    /**
     * 【v0.1.4】动作扩展出来的「额外半宽」（像素，单侧）。
     *
     * 【为什么要暴露这个值】`geometry()` 里动作需要更宽画布时会向两侧对称扩窗：
     *   `extra = max(0, actionWidth - baseWidth)`，单侧各 `extra / 2`。
     *   窗口变宽了，但边界夹取（`PetWindow.settle`）仍只按 `petWidth()` 算半宽 ——
     *   多出来的那截在贴边时会被屏幕/虚线框切掉，表现为「一抬手就被裁」。
     *   静态形象 `extra == 0`，恒返回 0，夹取结果与改造前完全一致。
     */
    fun actionExtraHalf(): Int {
        val actionWidth = action?.viewport()?.requiredWidth ?: 0
        if (actionWidth <= 0) {
            return 0
        }
        computeBox(boxBuf)
        val baseWidth = Math.round(boxBuf[0])
        val extra = Math.max(0, actionWidth - baseWidth)
        return extra / 2
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
            blinkElapsed = -1.0f
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

    /** 气泡是否有可画内容（思考中 / 回复都算），供拖动时判断要不要保留窗口内气泡高度。 */
    fun hasBubbleContent(): Boolean {
        return bubble.hasContent()
    }

    /**
     * 当前气泡实际占用的窗口高度（无内容时为 0）。
     * 【用途】窗口几何在拖动 / 贴边吸附时按「实际」气泡高度换算静止锚点，
     *   而不是用写死的 bubbleSpace()（默认 96dp），避免气泡被裁掉或人偶跳位。
     */
    fun bubbleHeight(): Int {
        return if (bubble.hasContent()) bubble.height() else 0
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
            return UiKit.card()
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
        // 【本轮·尺寸标准化第 3 层】人偶级位置偏移（[DollSizeSpec.Spec.ox/oy]）。
        //
        // 【为什么不缩放、只平移】大小已经在窗口尺寸层面处理掉了（PetWindow 建窗口时
        //   就把 petWidth() 算进了 LayoutParams，见 PetView.petWidth()）。
        //   这里若再缩放一次，同一份规格会被应用两遍，人偶变成「平方」大。
        //   所以这一层只负责**位移**：把整幅画面按规格挪一点，让用户能把人偶
        //   从默认的底边居中微调到自己想要的位置。
        //
        // 【零影响保证】规格全默认时 ox == oy == 0，直接跳过 save/restore，
        //   与改造前逐像素一致（这也是绝大多数用户的状态）。
        val spec = sizeSpec
        val shifted = spec.ox != 0f || spec.oy != 0f
        if (shifted) {
            canvas.save()
            canvas.translate(spec.ox * w, spec.oy * h)
        }
        drawPet(canvas, w, h, bmp)
        if (shifted) {
            canvas.restore()
        }
        // 【气泡不参与位移】见 [drawBubblePart]：它是独立 UI 层，锚在窗口底边。
        drawBubblePart(canvas)
    }

    /** 真正的人偶绘制（原 [onDraw] 主体，逐行未改；外面只多了一层平移包裹）。 */
    private fun drawPet(canvas: Canvas, w: Int, h: Int, bmp: android.graphics.Bitmap) {
        // 【r16·成败判据接线】有 DollAnim 动作在播时，把绘制让给 [ActionPlayerCore]。
        //
        // 【为什么放在这里、且放在变换之前】动作层画的是别人给的成品帧图
        //   （或 Lottie 自绘），**不能**再套一遍网格形变与姿态变换 ——
        //   否则导入的圆脸会被拉成作者没画过的形状。
        //   放在 `mesh()` / `PetTransform` 之前，就等于「有动作时走另一条完整分支」，
        //   不掺进原几何链的任何一步。
        //
        // 【零影响保证】`action` 为 null（编辑器预览、通知预览、旧路径）或没内容时，
        //   这一句只是两次引用比较 + 一次 `hasContent()`，原有绘制路径**一行都没变**。
        val act = action
        if (act != null && act.hasContent()) {
            val vp = act.viewport()
            if (vp != null) {
                // 动作矩形以基础窗口中心为轴；实际画布加宽后只平移这一次。
                val offset = (width - vp.baseWidth) / 2f - ActionViewport.contentOffsetX(vp)
                canvas.save()
                canvas.translate(offset, 0f)
                act.draw(canvas)
                canvas.restore()
            } else {
                act.draw(canvas)
            }
            return
        }
        val pw = petWidth()
        val fh = fullHeight()
        // 【回退 v2.5】v2.4 曾在此强制按「界面最大比例」绘制精灵，导致人偶被调小后
        //   趴姿反而被放大 2 倍（用户报「框上面的人怎么也是最大模型的」），已撤销。
        //   趴姿与普通态同用当前缩放率；框宽也跟着取 petWidth()，两者边缘对齐。
        // 【r16·三链收敛】本处的几何变换已改走 [PetTransform] —— 全工程唯一的几何事实来源。
        //   改造前这里与 bakeDraw / bakeBounds 各自实现同一套变换、靠注释人肉同步，
        //   历史上已因 pre*/post* 写反导致烘帧裁切事故。现在三处共用同一份推导。
        val f3 = fh.toFloat()
        mesh(pw.toFloat(), f3)
        // 锚点：趴姿时用窗口中心/底边，普通态用视图自报的 pivot。
        val tf = PetTransform.build(
            params = PetTransform.Params(
                phase = phase,
                jumpT = jumpT,
                dragLift = dragLift,
                idleLiftPx = idleLiftPx,
                idleScaleY = idleScaleY,
                landT = landT,
                lean = lean,
                tilt = tilt + idleTilt
            ),
            metrics = Metrics(peek),
            flipped = flipped,
            sinPhase = phase.toDouble()
        )
        // f4 = 表情的纵向基准（趴姿取「窗口底边 + 全高」，普通态取视图 pivot）。
        val f4 = if (peek) ((h - baseHeight()) + fh).toFloat() else pivotLocalY()
        canvas.save()
        tf.applyTo(canvas)
        val f8 = if (peek) w / 2.0f else pivotLocalX()
        if (deformSprite) {
            // 内置人偶：走网格形变（呼吸 / 摆发 / 眨眼）。
            canvas.drawBitmapMesh(bmp, MX, MY, verts, 0, null, 0, bmpPaint)
        } else {
            // 【本轮·修「模型被切成块、零件乱窜」】用户自己的形象图不做网格形变。
            //
            // 网格顶点是按内置人偶的五官比例算出来的，套到任意图上 =
            //   把随机位置的像素块当成眼睛/头发去推，画面被切碎并四处乱窜。
            // 这里改成整幅直绘：只保留刚体姿态（倾斜 / 抬升 / 翻转），动态不丢、画面不碎。
            //
            // 落点仍是「画布左上角 → 视图原点」，与网格版本的坐标系一致，
            // 所以切换分支时人偶位置不会跳。
            canvas.drawBitmap(bmp, 0.0f, 0.0f, bmpPaint)
        }
        canvas.restore()
        drawEmote(canvas, f8, (f4 - f3) - tf.lift)
    }

    /**
     * 气泡单独绘制（不参与人偶级位移）。
     *
     * 【为什么拆出来】气泡是**独立的 UI 层**：它锚在窗口底边、宽度按屏幕可见区算，
     *   与人偶本体的位置偏移无关。若把气泡也塞进 `drawPet` 里被 translate 一起挪，
     *   用户往右调 30% 人偶位置，气泡会跟着跑出屏幕 —— 那不是「挪人偶」，是「挪整块面板」。
     */
    private fun drawBubblePart(canvas: Canvas) {
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
        if (uptimeMillis < 0) {
            return
        }
        val j = exprDuration
        if (uptimeMillis > j) {
            return
        }
        drawEmoteAt(canvas, f, f2, expression, uptimeMillis / j.toFloat())
    }

    /**
     * 【r13 抽参】按「阶段比例 [t]」绘制一枚表情（0 = 刚出现，1 = 完全消失）。
     *
     * 【为什么从 drawEmote 拆出来】内置动作要烘焙表情帧图，必须能把表情定格在
     *   任意中间态；而 drawEmote 是拿 SystemClock 现算进度的，烘焙时无法控制。
     *   抽成纯参数函数后，实时绘制与烘焙**共用同一份**绘制代码，不会出现
     *   「烘出来的表情和桌面上看到的不一样」。
     *
     * @param expr 表情 id（[EXPR_SURPRISED] / [EXPR_HAPPY] / [EXPR_DIZZY]）。
     */
    private fun drawEmoteAt(canvas: Canvas, f: Float, f2: Float, expr: Int, t: Float) {
        val f3 = t
        val f4 = if (f3 < TAILR_RU) ((f3 / TAILR_RU) * 0.9f) + AHOGE_U else if (f3 < EYE_RU) 1.2f - (((f3 - TAILR_RU) / TAILL_RV) * 0.2f) else 1.0f
        val i = if (f3 < 0.65f) 255 else ((1.0f - ((f3 - 0.65f) / 0.35f)) * 255.0f).toInt()
        val f5 = (-dp(10.0f)) * f3
        emotePaint.color = emoteColor(expr)
        emotePaint.alpha = Math.max(0, Math.min(255, i))
        // 字号随人偶实时宽度走，保证「表情 : 人偶」比例恒定。
        emotePaint.textSize = petWidth() * EMOTE_SIZE_RATIO
        val ex = f + (petWidth() * AHOGE_U)
        val y = f2 + dp(6.0f) + f5
        canvas.save()
        canvas.translate(ex, y)
        canvas.scale(f4, f4)
        // 【r25·修 Paint 状态泄漏】旧实现直接改 emotePaint.style：若中途异常
        //   （Canvas 抛错 / Bitmap 被回收），style 会残留成 STROKE，
        //   下一帧表情就变成空心描边。用局部变量暂存，画完恢复。
        val savedStyle = emotePaint.style
        emotePaint.style = Paint.Style.STROKE
        emotePaint.strokeWidth = petWidth() * EMOTE_STROKE_RATIO
        emotePaint.color = UiKit.card()
        emotePaint.alpha = Math.max(0, Math.min(255, i))
        canvas.drawText(emoteGlyph(expr), 0.0f, 0.0f, emotePaint)
        emotePaint.style = Paint.Style.FILL
        emotePaint.color = emoteColor(expr)
        emotePaint.alpha = Math.max(0, Math.min(255, i))
        canvas.drawText(emoteGlyph(expr), 0.0f, 0.0f, emotePaint)
        emotePaint.style = savedStyle
        canvas.restore()
    }

    /* ==================== 【r13】内置动作帧图烘焙 ==================== */

    /**
     * 按显式参数重算网格并返回顶点数组（测量用，不绘制）。
     *
     * 【为什么单独给一个入口】烘焙前要先量出「所有帧的顶点包围盒」才能定帧图尺寸，
     *   而 [verts] 是私有字段、[buildMesh] 也是私有方法，只能在类内开口。
     *
     * @param v    参数数组，约定见 [bakeDraw]。
     * @param expr 表情 id（0 = 无）。
     */
    internal fun bakeMesh(v: FloatArray, expr: Int): FloatArray {
        val pw = petWidth().toFloat()
        val fh = fullHeight().toFloat()
        if (pw <= 0.0f || fh <= 0.0f) {
            return verts
        }
        val saved = expression
        expression = expr
        try {
            buildMesh(pw, fh, v[0], v[9], v[10], v[11])
        } finally {
            expression = saved
        }
        return verts
    }

    /**
     * 【r13 修 bug·烘焙帧把人偶裁掉】按 [bakeDraw] 的**完整变换链**量出这一帧
     * 在**画布坐标系**里的外接矩形（即 ox = oy = 0 时的落点范围）。
     *
     * 【为什么必须换算到画布坐标】[bakeDraw] 用 (ox, oy) 把「精灵左上角」摆到画布上，
     *   而 ox/oy 又依赖本方法量出的矩形 —— 鸡生蛋。解法是把 ox/oy **保留为符号**：
     *   本方法只量「ox = oy = 0 时的落点范围」，调用方用它推出 ox/oy 与画布尺寸。
     *
     * 【严格推导】[bakeDraw] 的变换链（Canvas 的 translate/scale/rotate 都是 preConcat，
     *   即 CTM' = CTM × T，**先调用的在更外层**）：
     * ```
     *   dx = pvx - pw/2 - ox
     *   dy = pvy - fh  - oy
     *   CTM = T(-dx,-dy) · T(pvx, P) · S · R6 · R7 · T(-pw/2,-fh)
     *   其中 P = (sin + pvy) - sin2 - lift
     * ```
     * 网格点 (u,v) 的最终落点：
     * ```
     *   (x, y) = CTM · (u,v)
     *   x = qx + pw/2 + ox                        （q = 去掉最外层 T(-dx,-dy) 的结果）
     *   y = qy + fh + sin - sin2 - lift + oy
     * ```
     * 本方法返回的 out 即「q 映射后再平移 (pw/2, fh+sin-sin2-lift)」的包围盒 ——
     * 也就是 **ox = oy = 0 时的画布落点范围**。
     *
     * 【调用方怎么用·符号是关键】
     * ```
     *   ox = pad - out.left        ← 不是 out.left - pad！
     *   oy = pad - out.top
     *   画布尺寸 = out 宽高各加 2*pad
     * ```
     * 上一版写成 `out.left - pad`，等于把 rect.left **重复计入两次**，
     * 人偶被整体推向画布右下、左上角被推出画布外 —— 用户报的
     * 「左边和上面都被截了一段」正是这个符号错的直接后果。
     *
     * @param v    参数数组，约定同 [bakeDraw]。
     * @param expr 表情 id（0 = 无）；表情绘制在精灵之外，也要并进包围盒。
     * @param out  输出矩形（画布坐标，ox = oy = 0 口径）。
     * @return 是否量到了有效矩形。
     */
    internal fun bakeBounds(v: FloatArray, expr: Int, out: RectF): Boolean {
        val pw = petWidth().toFloat()
        val fh = fullHeight().toFloat()
        if (pw <= 0.0f || fh <= 0.0f) {
            return false
        }
        // 【r16·三链收敛】包围盒走 [PetTransform.mapBounds] —— 与 [applyTo] **严格同源**。
        //   改造前这里是第三条独立实现：一段 8 行数学推导 + 手写 post* 矩阵。
        //   当年正是在这条链上把 pre* 误用成 post*，等价于把变换链倒过来，
        //   导致烘出的帧人偶被裁掉。现在矩阵与 offset 都由 PetTransform 给出，
        //   「两处必须一致」不再是靠注释提醒的纪律，而是**结构上不可能不一致**。
        val tf = PetTransform.buildForBake(
            params = PetTransform.Params.ofArray(v),
            metrics = Metrics(false),
            sinPhase = v[0].toDouble(),
            ox = 0.0f,
            oy = 0.0f
        )

        // 精灵本体的矩形：0..pw × 0..fh。再叠上形变后网格顶点的包围盒兜一层
        // （呼吸/尾巴等形变会让顶点略超出本体矩形）。
        val r = RectF(0f, 0f, pw, fh)
        val verts = bakeMesh(v, expr)
        if (verts.size >= 4) {
            var k = 0
            while (k + 1 < verts.size) {
                val x = verts[k]
                val y = verts[k + 1]
                if (x.isFinite() && y.isFinite()) {
                    if (x < r.left) {
                        r.left = x
                    }
                    if (x > r.right) {
                        r.right = x
                    }
                    if (y < r.top) {
                        r.top = y
                    }
                    if (y > r.bottom) {
                        r.bottom = y
                    }
                }
                k += 2
            }
        }
        tf.mapBounds(out, r)

        // 【表情也要算进去】drawEmoteAt 画在精灵之外（头顶上方 + 横向偏移），
        //   它不在网格顶点里，不并进来同样会被画布裁掉。
        //   落点口径（同样换到 ox = oy = 0 的画布坐标）：
        //     中心 x = pw * AHOGE_U + pw/2
        //     基线 y = (sin - sin2 - lift) + dp(6) + f5，f5 = -dp(10)*t（t ∈ [0,1]）
        //   字号 = pw * EMOTE_SIZE_RATIO；字形以基线为原点，向上约 1.4 个字高、
        //   向下约 0.5 个字高，横向左右各留 1 个字宽。取最极端（t=1）的位置。
        if (expr != 0) {
            // 表情的包围盒同样由 PetTransform 给（内部取「飘到最高」的极端位置，
            //   否则烘帧时符号顶部会被画布裁掉）。
            tf.emoteBounds(out, AHOGE_U, pw * EMOTE_SIZE_RATIO, dp(6.0f), dp(10.0f))
        }
        return out.width() > 0f && out.height() > 0f
    }

    /**
     * 把一帧画到画布上（内置动作帧图烘焙用）。
     *
     * 【几何完全复用 onDraw】这里的变换序列与 [onDraw] 逐行一致，
     *   只是额外先把「精灵左上角」平移落点到画布的 (ox, oy)，
     *   于是烘出来的每一帧都与桌面上真实绘制的结果同源。
     *
     * @param v    参数数组：[0]phase [1]jumpT [2]dragLift [3]idleLiftPx [4]idleScaleY
     *             [5]landT [6]lean [7]tilt [8]保留 [9]blinkT [10]idleEyeScale [11]gaze。
     *             jumpT / landT 传 -1 表示「不在进行」。
     * @param expr 表情 id；0 = 不画表情。
     * @param t    表情的阶段比例（0~1）；[expr] 为 0 时忽略。
     * @param ox   精灵左上角在画布上的落点 X。
     * @param oy   精灵左上角在画布上的落点 Y。
     */
    internal fun bakeDraw(canvas: Canvas, v: FloatArray, expr: Int, t: Float,
                          ox: Float, oy: Float) {
        val bmp = current() ?: return
        val pw = petWidth().toFloat()
        val fh = fullHeight().toFloat()
        if (pw <= 0.0f || fh <= 0.0f) {
            return
        }
        val saved = expression
        expression = expr
        try {
            buildMesh(pw, fh, v[0], v[9], v[10], v[11])
        } finally {
            expression = saved
        }
        // 【r16·三链收敛】几何走 [PetTransform] —— 与 onDraw **调用同一份 applyTo**。
        //   改造前这里手工复制了 onDraw 的整条变换链（连注释都在提醒「与 onDraw 逐参数对齐」
        //   「必须用 post*」），是三条链里最容易走样的一条。现在公式只在 PetTransform 里存在。
        val tf = PetTransform.buildForBake(
            params = PetTransform.Params.ofArray(v),
            metrics = Metrics(false),
            sinPhase = v[0].toDouble(),
            ox = ox,
            oy = oy
        )
        canvas.save()
        tf.applyTo(canvas)
        canvas.drawBitmapMesh(bmp, MX, MY, verts, 0, null, 0, bmpPaint)
        canvas.restore()
        if (expr != 0) {
            // 表情的锚点同样由 PetTransform 给 —— 改造前这里是手算的 `pvy - fh + sin - sin2 - lift`。
            canvas.save()
            val pvx = pivotLocalX()
            val pvy = pivotLocalY()
            val dx = pvx - (pw / 2.0f) - ox
            val dy = pvy - fh - oy
            canvas.translate(-dx, -dy)
            drawEmoteAt(canvas, pvx, tf.emoteAnchor(AHOGE_U, dp(6.0f), dp(10.0f), t)[1], expr, t)
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
        // 【释放】摘窗 / 暂离 / detach 都经由此处：清触摸忙标志，否则中途摘窗收不到 UP，
        //   下次挂载会带着 touchBusy=true 满速空转。
        touchBusy = false
        // 【r16】一并停动作（但**不释放**播放层）：
        //   暂离（awayHide → awayShow）复用同一个 PetView，若在此处释放，
        //   回来时动作层已死、必须重建 —— 白白多一次帧图解码。
        //   真正的释放在 [disableActions]，由 PetWindow.detach 调用。
        stopAction()
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

    /**
     * **【r22·甩抛落地】** 播一次着陆压缩反馈。
     *
     * 【为什么复用 `landT` 而不是新造一套】`PetAnimator` 每帧把 `landT` 从 0 推到 1
     *   （0.38 秒走完，推到 1 后自动置 -1），画面上的「压扁 → 回弹」完全由它驱动。
     *   所以这里只需要把相位归零、再让帧循环醒一次 —— 一笔新状态都不必加。
     *
     * 【为什么先判 -1 之外的值】如果人偶此刻正在播「跳跃落地」的着陆（`landT >= 0`），
     *   重复归零会让压缩卡在起始相位反复重来，看起来像抽搐。只在「没有着陆在进行」时才触发。
     */
    fun playLandedFeedback() {
        if (landT >= 0f) {
            return
        }
        landT = 0f
        wakeFrame()
    }

    fun setDragVelocity(f: Float) {
        anim.setDragVelocity(f)
    }

    fun lookAt(f: Float) {
        anim.lookAt(f)
    }

    fun tick() {
        anim.tick()
        // 【r16】动作层跟随**同一个帧时钟**推进。
        //   任务书 §3.5 硬约束「不新建第二套调度器」—— 所以这里不 post、不排定时器，
        //   只是借用已经存在的帧循环多推进一步；帧间隔仍由 [nextFrameDelay] 统一决定。
        val a = action
        if (a != null && a.isPlaying()) {
            a.tick(SystemClock.uptimeMillis())
        }
    }

    // ---------- 【r16】动作播放层：让桌宠真正用上 DollAnim ----------
    //
    // 【这一节的地位】任务书 §1.4 的成败判据 ——
    //   「桌宠能够实际使用动作系统，而不是只有编辑器和预览界面」。
    //   改造前 PetView / PetWindow / PetAnimator 对 DollAnim 零引用，
    //   用户在动作页配的动作桌宠一个都不播。

    /** 动作播放层；为 null 表示「本视图不播动作」（编辑页预览、通知预览等）。 */
    private var action: ActionPlayerCore? = null

    /** 人物原窗口的触摸宽度，不含只为显示而增加的透明扩展区。 */
    fun baseWindowWidth(): Int {
        computeBox(boxBuf)
        return Math.round(boxBuf[0]).coerceAtLeast(petWidth())
    }

    /** 由窗口层同步 LayoutParams；不在视图绘制阶段直接操作 WindowManager。 */
    internal var actionViewportChanged: ((Int) -> Unit)? = null
    private var pendingActionViewportWidth = 0

    /** 本视图当前绑定的动作来源（人偶 id），用于把语义解析成动作。 */
    private var actionDollId: String? = null

    /**
     * 语义仲裁层 —— 「此刻该播什么动作」的唯一决策者。
     *
     * 【为什么必须有它（而不是让 PetView 直接调度动作）】
     *   优先级规则（交互 > 出现/消失 > 待机）是**对外契约**
     *   （见模型包规范与 `ActionSemantic.priority`）。散在视图里就没人保证它被遵守；
     *   收在一处，规则改了只改一个地方，且能单测（`BehaviorPriorityTest`）。
     */
    private var behavior: PetBehaviorController? = null

    /**
     * 启用动作播放。
     *
     * 【为什么由外部显式打开而不是构造时就建】不是所有 PetView 都该播动作：
     *   编辑页预览（`DollPreviewOverlay`）要的是「当前帧的静态效果」，
     *   通知预览要的是形象图本身。让它们保持原样，是「不波及无关功能」的底线。
     *
     * @param dollId 人偶 id（动作按人偶分库，见 DollAnim.KEY_PREFIX）。
     */
    fun enableActions(dollId: String?) {
        if (action != null && actionDollId == dollId) {
            return
        }
        // 换人偶：旧播放层必须释放（帧图是本类自己解码的，不释放就是泄漏）。
        action?.release()
        behavior?.release()
        actionDollId = dollId

        val player = ActionPlayerCore(
            requestDraw = { invalidate() },
            hostProvider = { displayHost },
            viewportProvider = {
                if (peek) {
                    // 迷你聊天使用独立的窗口布局，动作不得撑破输入框宽度。
                    null
                } else {
                    val baseHeight = fullHeight()
                    val base = ActionViewport.Spec(
                        baseWidth = run {
                        computeBox(boxBuf)
                        Math.round(boxBuf[0]).coerceAtLeast(petWidth())
                    },
                        baseHeight = baseHeight,
                        bodyHeight = baseHeight
                    )
                    base.copy(requiredWidth = pendingActionViewportWidth)
                }
            },
            viewportChanged = { spec ->
                pendingActionViewportWidth = spec.requiredWidth
                actionViewportChanged?.invoke(spec.requiredWidth)
            },
            // 【v0.1.0·动作大小统一】从烘帧记录里取「本体基准矩形」。
            //   有了它，播放层按「人偶本体」而不是「整张画布」映射到窗口 ——
            //   跳跃（画布高）与呼吸（画布矮）在桌面上就是同一个大小。
            //   没有记录时返回 null，播放层自动回退成旧行为（不裁、不崩）。
            baselineProvider = { actionId ->
                val tag = actionDollId
                if (tag.isNullOrEmpty()) null else AnimBaker.baseline(context, tag, actionId)
            },
            // 【v0.1.0·贴边姿态】把当前倾斜角注入播放层。
            //   读的是本视图的 `lean`（`setEdgePeek` 唯一的写入点），
            //   于是「窗口算外接框用的角度」与「画面实际旋转的角度」永远同一个源，
            //   不可能出现「窗口按 25° 留了余量、画面却按别的角度转」而溢出。
            leanProvider = { lean },
            // 【工作台·锚点/适配】把「这个动作怎么摆」注入播放层。
            //   没有设置过的动作（绝大多数）返回 null → 绘制路径完全跳过，
            //   与改造前逐像素一致；用户改过锚点/适配的才走新分支。
            displayProvider = { actionId ->
                val tag = actionDollId
                if (tag.isNullOrEmpty()) {
                    null
                } else {
                    com.dollhouse.app.data.ActionWorkbench.display(context, tag, actionId)
                }
            },
            // 【r22·内存】帧图解码尺寸按人偶**实际绘制宽度**给，不再写死 1024。
            //
            //   算式：`max(当前宽, 上限宽) × DECODE_TARGET_RATIO`，并夹一个下界。
            //
            //   为什么取「上限宽」而不是当前宽：帧只解码一次，用户随时会把人偶拉大，
            //   按当前值解码会「刚调大就糊」。
            //
            //   为什么乘 0.5 而不是乘 2：`calcSampleSize` 把目标当**下限**用
            //   （「采样后仍 ≥ 目标」），目标越高采样率越低 —— 这里要的是**触发降档**，
            //   不是要更多像素。实测 904×978 素材：0.5 档让 120 帧从 405MB 降到 101MB。
            //   详见 `ActionPlayerCore.DECODE_TARGET_RATIO` 的注释（含对照表）。
            decodeTargetPx = {
                val w = Math.max(petWidth(), petMaxWidth())
                if (w <= 0) {
                    0
                } else {
                    Math.max(
                        ActionPlayerCore.DECODE_TARGET_MIN,
                        Math.round(w * ActionPlayerCore.DECODE_TARGET_RATIO)
                    )
                }
            }
        )
        action = player

        // 【r16·仲裁层必须真的接上】改造过程中一度漏掉这一步：
        //   `PetBehaviorController` 写好了却没被任何生产代码引用，
        //   R8 直接把它当死代码删掉（mapping.txt 里 0 次出现）。
        //   后果不是「少个功能」，而是**优先级规则完全没生效** ——
        //   循环的待机默认动作会一直占着画面，拖动倾斜/贴边趴姿全被盖住。
        //   现在把它绑成「语义 → 动作」的唯一通道。
        val b = PetBehaviorController()
        b.bind(
            // 解析：语义 → 动作 id（数据层问答，动作层不依赖数据层）。
            PetBehaviorController.ActionResolver { semantic ->
                DollAnim.bySemantic(context, dollId, semantic)?.id
            },
            // 播放：真正落到 ActionPlayerCore 上。
            object : PetBehaviorController.ActionPlayer {
                override fun play(actionId: String, semantic: String) {
                    DollAnim.byId(context, dollId, actionId)?.let { player.play(it) }
                }

                override fun stop() {
                    player.stop()
                }
            }
        )
        // 【播完回落的唯一入口】非循环动作播完时由播放层回调，
        //   交给仲裁层决定是「回基础态」还是「停下来把画面还给基础形变」。
        player.onFinish = { b.onActionFinished() }
        // 【v0.1.0·贴边探测】仲裁层需要知道「此刻在不在屏幕边上」才能决定
        //   「回常驻还是回贴边」。真源是 lean（贴边倾斜角，!= 0 即贴边），所以问视图自己。
        b.bindEdgeProbe(PetBehaviorController.EdgeProbe { lean != 0.0f })
        // 【工作台·行为绑定】用户在这一页给动作设的「允许哪些触发方式」与「优先级档位」。
        //   注入到仲裁层而不是播放层：所有入口（随机定时器 / 手势 / 待机回落）
        //   都会自动受约束，不会出现某条路径漏判。
        b.bindTriggerGate { actionId, trigger ->
            com.dollhouse.app.data.ActionWorkbench.allows(context, dollId, actionId, trigger)
        }
        b.bindPriorityBoost { actionId ->
            val bind = com.dollhouse.app.data.ActionWorkbench.binding(context, dollId, actionId)
            com.dollhouse.app.data.ActionWorkbench.priorityBoost(bind.priority)
        }
        behavior = b
    }

    /** 关闭动作播放并释放资源（人偶被移除 / 服务销毁）。 */
    fun disableActions() {
        action?.release()
        behavior?.release()
        action = null
        behavior = null
        actionDollId = null
    }

    /** 当前动作层（供 PetWindow 决定要不要写默认动作 / 派发交互动作）。 */
    internal fun actionPlayer(): ActionPlayerCore? = action

    /**
     * **【交互必须通知动作层】**交互开始（手指按下 / 拖动开始 / 长按到点）。
     *
     * 【不调会怎样】动作层是「有内容就独占绘制」的：循环待机动作会把
     *   拖动倾斜、贴边趴姿、跳跃表情**全部盖住**，用户拖人偶时看不到任何反馈。
     *   所以交互一开始就要把待机档让出来（哪怕该语义没配动作）。
     *
     * @return 是否播上了该语义的动作。
     */
    internal fun interactionStart(semantic: String): Boolean {
        return behavior?.onInteractionStart(semantic) ?: false
    }

    /** **【交互必须通知动作层】**交互结束（抬手 / 拖动放下）。 */
    internal fun interactionEnd() {
        behavior?.onInteractionEnd()
    }

    /**
     * **【v0.1.4·拖动持续播放】手势仍在继续的心跳**（拖动的每个 MOVE 调用）。
     *
     * 【为什么需要它】`interactionStart` 只在手指按下那一刻调一次；
     *   之后整个拖动过程没有任何「我还在拖」的信号，
     *   拖拽动作一旦被其它路径顶掉（贴边判定 / 动作播完 / 主题刷新），就再也回不来。
     *   由 `PetWindow` 在每个 MOVE 上调用，内部幂等（已在播就什么都不做）。
     */
    internal fun interactionHold(semantic: String): Boolean {
        return behavior?.onInteractionHold(semantic) ?: false
    }

    /**
     * **【v0.1.0·新增】贴边状态变化通知**（吸附到边上 / 离开边缘之后调用）。
     *
     * 【为什么不让窗口层直接 `playSemantic(IDLE)`】贴边(12) 的优先级高于常驻(10)，
     *   离边那次 IDLE 会被裁决拒绝，人偶就会一直停在贴边动作上。
     *   走这条路：由仲裁层按新位置**直接切基础态**，不经过优先级门槛。
     */
    internal fun notifyEdgeChanged() {
        behavior?.onEdgeChanged()
    }

    /** 供渲染器（Lottie / 动图）查询宿主能力：重绘、可见性、屏幕状态。 */
    private val displayHost: PetDisplayHost = object : PetDisplayHost {
        override fun context(): Context = this@PetView.context
        override fun requestDraw() {
            this@PetView.invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int) {
            // 【为什么空实现】尺寸由 WindowManager 的 LayoutParams 决定，
            //   渲染器只能「建议」，实际变更必须走 PetWindow 那条路（它要同步 lp）。
            //   容器尺寸与 lp 不一致会画出界，宁可不动。
        }

        override fun isShown(): Boolean = this@PetView.isShown

        override fun isScreenOn(): Boolean = this@PetView.screenOn()
    }

    /**
     * 播一个语义动作（`idle` / `tap` / `drag` / `longpress` / `appear` / `disappear`）。
     *
     * @return 是否真的开始播（没配这个语义的动作时返回 false）。
     */
    internal fun playSemantic(semantic: String): Boolean {
        val b = behavior ?: return false
        // 【必须先记住「原来在播什么」】仲裁层可能因为优先级不够而**拒绝**这次请求
        //   （例如交互进行中来了待机请求）。若不做这个对比，就会把「被拒绝」
        //   误报成「播上了」，上层会据此做出错误判断。
        val before = b.currentSemantic
        b.play(semantic)
        if (b.currentSemantic == semantic) {
            return true
        }
        // 只有在「原本就在播这个语义」时才算成功（幂等调用不该被当成失败）。
        return before == semantic
    }

    /**
     * **【v0.1.0·新增】强制播一个语义**（无视优先级）。
     *
     * 【为什么需要】睡觉 / 贴边这类「模式切换」必须压得住优先级更高的当前动作，
     *   而 [playSemantic] 走的是按优先级裁决的 `play` ——
     *   贴边（12）压不住手动（30），于是「贴到边上还播着手势动作」。
     */
    internal fun playSemanticForced(semantic: String): Boolean {
        return behavior?.playForced(semantic) ?: false
    }

    /**
     * **【v0.1.0·新增】播一次「随机动作」**（13~30 秒定时器调用）。
     *
     * 【为什么挑谁在这里定】挑谁、能不能播、播完怎么回落，都属于动作语义的范畴；
     *   数据层只负责给候选（`DollAnim.pickRandom`）。
     *
     * @return 是否真的播上了。
     */
    internal fun playRandomAction(): Boolean {
        val b = behavior ?: return false
        val a = DollAnim.pickRandom(context, actionDollId) ?: return false
        return b.playManualOf(a.id, ActionSemantic.RANDOM)
    }

    /**
     * **【v0.1.0·新增】随机动作开关是否打开**（按人偶各管各）。
     *
     * 【三重合取】开关打开 **且** 随机组里有可播动作 **且** 当前不在贴边。
     *   最后一条件用户明确定案「贴边状态下不随机」。
     * 【为什么要三合一】只要有一条件不满足就什么都不该播；
     *   把判断收在一处，Animator 不必知道任何细节。
     */
    internal fun randomActionEnabled(): Boolean {
        val tag = actionDollId ?: return false
        if (!PetPrefs.randomAction(context, tag)) {
            return false
        }
        // 贴边时不随机（用户定案）。
        if (lean != 0.0f) {
            return false
        }
        return DollAnim.hasPlayable(context, tag, DollAnim.GROUP_RANDOM)
    }

    /**
     * 播一个指定 id 的动作（手势绑定 / 快捷入口用）。
     *
     * **【v0.1.0·修 P0】**改造前这里直接 `a.play(target)` 绕过仲裁层 ——
     *   仲裁层完全不知道有人在播动作，紧接着的一次 `interactionEnd()`（点完手势就会调）
     *   会把刚播上的动作立刻停掉，用户看到的是「动作闪一帧就消失」。
     *   现在统一走 `behavior.playManual()`：语义登记为 MANUAL（优先级 30），
     *   回落也有据可依。
     */
    internal fun playActionId(actionId: String): Boolean {
        val b = behavior ?: return false
        // 目标动作必须存在且可播（空壳播起来什么都不发生，是「虚空动作」）。
        val target = DollAnim.byId(context, actionDollId, actionId) ?: return false
        if (target.frames.isEmpty() && target.assetPath.isEmpty()) {
            return false
        }
        return b.playManual(actionId)
    }

    /**
     * **【v0.1.0·新增】本视图像素是否需要随机动作的「提帧」** —— 供帧率策略使用。
     *
     * 【为什么需要】随机动作是间歇的（13~30 秒一次），播放期间必须全速，
     *   但它的「在播」状态已经由 [isActionPlaying] 覆盖；这里只是给外部一个
     *   语义明确的查询点，避免各处直接读 action 字段。
     */
    internal fun isRandomPlaying(): Boolean {
        val b = behavior ?: return false
        return b.currentSemantic == ActionSemantic.RANDOM && isActionPlaying()
    }

    /** 是否正在播动作（供 PetWindow 判断要不要打断 / 是否已回落）。 */
    internal fun isActionPlaying(): Boolean = action?.isPlaying() == true

    /** 停止播动作（回落到原网格形变管线）。 */
    internal fun stopAction() {
        action?.stop()
    }

    /** 【r19·调试】当前行为层状态摘要（语义 / 动作 id / 播放状态）。 */
    internal fun behaviorDebugInfo(): String {
        val b = behavior ?: return "未初始化"
        return (b.currentSemantic ?: "idle") + "/" + (b.currentActionId ?: "无") + "/" + b.state
    }
}
