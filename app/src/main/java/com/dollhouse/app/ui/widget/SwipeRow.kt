package com.dollhouse.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.dollhouse.app.anim.Springs
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】可左滑的行容器：左滑露出右侧的删除按钮，点它触发删除。
 *
 * 【为什么不给行加长按菜单】规格定的是「左滑露出垃圾桶」，长按弹菜单与既有
 *        三个点菜单语义重复，且容易误触。左滑是这一个动作、一个结果。
 *
 * 【手势口径】只在「横向位移超过 touchSlop 且明显大于纵向位移」时才拦截事件。
 *        否则纵向滚动会被吃掉（列表滑不动），子 View 的点击也会失效。
 *
 * 【回弹】吸附用 Springs.snappy()，与 UiKit.Switch 同一套弹簧预置，
 *        手感与全 App 其它动效一致。
 *
 * 【坑】① 打开状态下点内容区应当先收回，而不是直接进编辑页 —— 否则用户
 *        想关掉删除区，却被带进了另一个页面；
 *        ② 行高由内容决定，删除区高度必须 MATCH_PARENT，否则会塌成一条线；
 *        ③ 动画实例要自己持有并 cancel，连滑两次不能有两个动画抢 translationX。
 */
internal class SwipeRow(
    ctx: Context,
    private val content: View,
    private val onTap: Runnable?,
    private val onDelete: Runnable?
) : FrameLayout(ctx) {

    private val slop: Int = ViewConfiguration.get(ctx).scaledTouchSlop
    private val max: Float = UiKit.dp(ctx, ACTION_DP.toFloat()).toFloat()
    private var downX = 0f
    private var downY = 0f
    private var open = false
    private var anim: ValueAnimator? = null

    init {
        val action = FrameLayout(ctx)
        // 【需求】删除区只把右侧两个角做圆：左侧直角与已滑开的卡片右边缘严丝合缝，
        //   不再出现「两块圆角之间夹一道底色」的穿模感；右侧圆角与卡片保持一致（16dp）。
        action.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiKit.ERR)
            val r = UiKit.dp(ctx, CARD_RADIUS_DP.toFloat()).toFloat()
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        UiKit.press(action)
        val trash = Icons.view(ctx, Icons.IC_TRASH, 22.0f, UiKit.ON_ACC)
        val tlp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        tlp.gravity = Gravity.CENTER
        action.addView(trash, tlp)
        action.isClickable = true
        action.setOnClickListener {
            if (open) {
                onDelete?.run()
            }
        }
        addView(
            action, FrameLayout.LayoutParams(
                UiKit.dp(ctx, ACTION_DP.toFloat()), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END
            )
        )

        addView(
            content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // 点击语义收口在本类：打开时先收回，否则才当作一次正常点击。
        content.setOnClickListener {
            if (open) {
                settle(false)
            } else {
                onTap?.run()
            }
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            downX = e.x
            downY = e.y
            return false
        }
        if (a == MotionEvent.ACTION_MOVE) {
            val dx = e.x - downX
            val dy = e.y - downY
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                val p = parent as? ViewGroup
                if (p != null) {
                    // 已经判定为横向滑动，父级 ScrollView 不要再抢。
                    p.requestDisallowInterceptTouchEvent(true)
                }
                cancelAnim()
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            downX = e.x
            cancelAnim()
            return true
        }
        if (a == MotionEvent.ACTION_MOVE) {
            val base = if (open) -max else 0f
            var t = base + (e.x - downX)
            if (t > 0f) {
                t = 0f
            }
            if (t < -max) {
                t = -max
            }
            content.translationX = t
            return true
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            settle(content.translationX < -max * OPEN_RATIO)
            return true
        }
        return super.onTouchEvent(e)
    }

    /** 吸附到「打开」或「收起」，走弹簧。 */
    private fun settle(target: Boolean) {
        open = target
        cancelAnim()
        val from = content.translationX
        val to = if (target) -max else 0f
        if (Math.abs(from - to) < 1.0f) {
            content.translationX = to
            return
        }
        anim = Springs.drive(Springs.snappy(), object : Springs.Listener {
            override fun onUpdate(progress: Float) {
                content.translationX = Springs.lerp(from, to, progress)
            }

            override fun onEnd() {
                content.translationX = to
            }
        })
    }

    private fun cancelAnim() {
        if (anim != null) {
            anim!!.cancel()
            anim = null
        }
        content.animate().cancel()
    }

    companion object {
        /** 露出的删除区宽度：够放下一个 24dp 图标 + 左右呼吸。 */
        private const val ACTION_DP = 68
        /** 删除区右侧圆角（dp）：与卡片圆角一致，滑开后两块拼成一片。 */
        private const val CARD_RADIUS_DP = 16

        /** 滑过这个比例（相对满宽）就算「要打开」，松手后吸附过去。 */
        private const val OPEN_RATIO = 0.4f

        /** 删除区宽度（dp），调用方给列表留底部/右侧余量时用得到。 */
        @JvmStatic
        fun actionWidthDp(): Int {
            return ACTION_DP
        }
    }
}
