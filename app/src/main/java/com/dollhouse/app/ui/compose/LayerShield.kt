package com.dollhouse.app.ui.compose

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 【职责】层根「交互闸门」——本次界面框架重构的核心构件。
 *
 * ## 为什么需要它（这是必须理解的根因）
 *
 * 主界面是 `android.R.id.content` 上的 **4 个兄弟 View**：
 * ```
 * host(三 Tab) → bar(底栏) → drawer(抽屉) → nav(覆盖页栈)
 * ```
 * Android 分发触摸时按**逆序**逐个询问子 View，**某个返回 false 就继续问下一个**。
 *
 * 旧做法是在层根上改 `isClickable` / 挂 `setOnTouchListener { _, _ -> true }`。这条路
 * 原理上就挡不住子按钮：
 * ```
 * ViewGroup.dispatchTouchEvent
 *   → 先把事件分发给每个子 View（子按钮命中就消费）
 *   → 所有子 View 都不消费，才轮到自己 onTouchEvent / OnTouchListener
 * ```
 * 于是「给旧页根贴封条」只在这一层**内部没有任何可点节点**时才生效；
 * 只要旧页里有按钮、开关、输入框，它们先被问到并吃掉事件 ——
 * 用户看到的就是「点新页面的空白处，触发了下面旧页面对应位置的按钮」。
 *
 * Compose 又多一层坑：`ComposeView` 内部是整屏的 `AndroidComposeView`，
 * 落点处 Compose 没有可消费节点时它返回 false，事件继续落到**下一个兄弟层**。
 *
 * ## 本类怎么解决
 *
 * 只重写 `dispatchTouchEvent`：`blocking = true` 时**不下发给子树、并返回 false**。
 * 对被屏蔽的层来说，它在分发链上**等于不存在** —— 事件确实往下一个兄弟走，
 * 但每一个兄弟都是「关着的闸门」，一路 false 到底，最终谁也收不到。
 *
 * 关键取舍：
 * · **不消费**（返回 false 而不是 true）→ 不会出现「点哪都被吃掉」；
 * · **不下发给子树** → 子按钮、滚动、输入、长按、拖动一并暂停，
 *   这正是文档要求的「被暂停页面的整个交互区域及其子组件均符合暂停规则」；
 * · 只作用于**层根**，绝不进入页面内容 → 页面自身的滚动 / 输入 / 手势零影响。
 *
 * ## 兼容的两个细节
 *
 * ① **手势收尾**：手势进行到一半时若本层被屏蔽，子树里的按钮会停在「按下」态
 *    （收不到 ACTION_CANCEL）。故屏蔽瞬间补发一个 CANCEL。
 * ② **自己正在跟踪的手势不掐断**：边缘右滑打开抽屉时，一旦抽屉认主，
 *    底座层随即被屏蔽 —— 但那一串事件是闸门自己在处理的，必须让它走完 UP，
 *    否则「滑到一半卡住」。用 [tracking] 区分。
 */
class LayerShield(context: Context) : FrameLayout(context) {

    /**
     * 本层当前是否暂停交互。
     *
     * 【由谁写】只由 [LayerGate] 写（单一真源）。**禁止**页面或业务代码直接改它 ——
     *   这正是上一轮「每层各自判断能不能收触摸」导致混乱的原因。
     */
    var blocking: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            com.dollhouse.app.core.OperationRecorder.event("Layer", "blocking=$value owner=$gestureOwner tracking=$tracking")
            if (value && !tracking) {
                // 子树里可能有按钮正处于按下态，补一个 CANCEL 让它回弹。
                cancelChildren()
            }
        }

    /** 当前 DOWN 序列是否由本层接收。序列内锁定所有权，禁止中途换层导致只收到 UP。 */
    private var gestureOwner = false
    /** 本层是否正在跟踪一段手势（边缘滑动用）。跟踪期间屏蔽不打断。 */
    private var tracking = false

    /* ======================== 边缘右滑（仅底座层启用） ======================== */

    /**
     * 是否启用「从左边往右滑打开抽屉」。
     *
     * 【只在底座层开】覆盖页有自己的左边缘返回手势，再塞一个抽屉滑动会互相抢。
     */
    var edgeSwipeEnabled: Boolean = false

    /** 起手区宽度（px）：只有从屏幕左侧这么宽的一条里起手，才算抽屉手势。 */
    var edgeWidth: Float = 0f

    /** 与系统手势区之间的呼吸间距（px）。 */
    var edgeMargin: Float = 0f

    /** 手指移动超过这个距离才认方向（px）。默认取系统 touch slop。 */
    private val slop: Int = ViewConfiguration.get(context).scaledTouchSlop

    /** 方向判定：横向位移必须大于纵向的这个倍数，才认成抽屉手势。 */
    private val horizontalRatio = 1.5f

    /** 手势开始（已被闸门接管）。 */
    var onSwipeStart: (() -> Unit)? = null

    /** 手势进行中：参数为**累计**横向位移（px，向右为正）。 */
    var onSwipeMove: ((Float) -> Unit)? = null

    /** 手势结束：参数为累计位移与横向速度（px、px/s）。 */
    var onSwipeEnd: ((Float, Float) -> Unit)? = null

    /** DOWN 时的落点 x / y。 */
    private var downX = 0f
    private var downY = 0f

    /** 本次手势是否「可能」是抽屉手势（起手点在起手区内）。 */
    private var maybeSwipe = false

    /** 起手点是否压在系统手势禁区里（此时一律不接管）。 */
    private var inSystemEdge = false

    /** 本次手势是否被认作纵向滚动（认了之后就不再关心它）。 */
    private var gaveUp = false

    /** 上一次移动的 x（算速度）。 */
    private var lastX = 0f
    private var lastT = 0L
    private var velocityX = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_UP) {
            com.dollhouse.app.core.OperationRecorder.event("Touch", "action=${ev.actionMasked} blocking=$blocking owner=$gestureOwner")
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // 新 DOWN 结束此前异常残留的序列；不能要求必须先收到上一段 CANCEL。
            gestureOwner = !blocking
            return if (gestureOwner) super.dispatchTouchEvent(ev) else false
        }
        if (!gestureOwner) {
            // 新打开的层不得接收旧 DOWN 的 UP；所有权只在新序列重新计算。
            return false
        }
        // 所有权锁定到序列结束。若中途屏蔽，setter 已向原子树补发 CANCEL。
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestureOwner = false
        }
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!edgeSwipeEnabled || blocking) {
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastT = ev.eventTime
                velocityX = 0f
                gaveUp = false
                tracking = false
                // 起手区：必须落在「系统手势区右缘 + 呼吸间距」到「再往右 edgeWidth」之间。
                //   edgeMargin 已经把系统手势区排除掉 —— 从系统区起手的手势
                //   根本到不了这里（系统先吃掉），即便到了我们也不接管，避免打架。
                //   【为什么要动态算而不是写死 dp】手势导航的手机系统区约 20~30px、
                //   三键导航为 0；写死会在某些机型上完全失效或抢走系统返回。
                val minX = systemGestureLeft() + edgeMargin
                val maxX = minX + edgeWidth
                inSystemEdge = ev.x < minX
                maybeSwipe = !inSystemEdge && ev.x <= maxX
            }

            MotionEvent.ACTION_MOVE -> {
                if (tracking || gaveUp || !maybeSwipe) {
                    return tracking
                }
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) < slop && abs(dy) < slop) {
                    return false
                }
                // 【方向判定】横向明显大于纵向、且方向是「向右」才认。
                //   纵向 → 放行给列表滚动；向左 → 放行给页面自己的左滑手势（如左滑删除）。
                if (abs(dy) > abs(dx)) {
                    gaveUp = true
                    return false
                }
                if (abs(dx) < abs(dy) * horizontalRatio) {
                    // 斜着走，还没定论；继续等。
                    return false
                }
                if (dx <= 0f) {
                    gaveUp = true
                    return false
                }
                // 认了：接管整段手势。
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onSwipeStart?.invoke()
                onSwipeMove?.invoke(dx)
                return true
            }
        }
        return tracking
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!tracking) {
            // 未被接管的手势不消费（保住页面自己的点击与滚动）。
            return super.onTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dt = ev.eventTime - lastT
                if (dt > 0) {
                    velocityX = (ev.x - lastX) / dt * 1000f
                }
                lastX = ev.x
                lastT = ev.eventTime
                onSwipeMove?.invoke(dx)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.x - downX
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    // 被打断：按当前位置吸附，不做速度加成。
                    onSwipeEnd?.invoke(dx, 0f)
                } else {
                    onSwipeEnd?.invoke(dx, velocityX)
                }
            }
        }
        return true
    }

    /** 回前台时终止可能跨 Activity 生命周期的旧手势，避免 DOWN/UP 落到不同层。 */
    fun cancelPendingTouch() {
        gestureOwner = false
        tracking = false
        maybeSwipe = false
        gaveUp = false
        parent?.requestDisallowInterceptTouchEvent(false)
        cancelChildren()
    }
    /** 给子树补一个 ACTION_CANCEL（屏蔽瞬间收尾按下态）。 */
    private fun cancelChildren() {
        try {
            val t = android.os.SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super.dispatchTouchEvent(e)
            e.recycle()
        } catch (ignored: Throwable) {
            // 收尾失败无害：最坏是某个按钮保持按下态，下一次重组会修正。
        }
    }

    /** 系统手势区左缘（px）。Android 10 以下没有该概念，返回 0。 */
    fun systemGestureLeft(): Int {
        if (Build.VERSION.SDK_INT < 29) {
            return 0
        }
        return try {
            rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemGestures())?.left ?: 0
        } catch (ignored: Throwable) {
            0
        }
    }
}
