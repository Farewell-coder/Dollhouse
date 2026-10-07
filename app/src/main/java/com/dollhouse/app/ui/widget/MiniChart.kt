package com.dollhouse.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】统计页用的迷你折线图：自绘网格 + 折线 + 首尾点 + 首尾标签，空数据显示「暂无消耗」。
 *
 * 【入口】只由 TokenStat.buildPage 创建，setPoints 一次性喂数据。
 *
 * 【交互】不持有业务数据；点按最近的数据点后回调 OnPickListener，由调用方决定怎么呈现
 *        （工程硬约束不允许浮层短提示，所以调用方把它写进已有的提示行里）。
 *
 * 【坑】① 只有一个数据点时步长要兜底 1，否则 x 坐标除零；
 *       ② 全 0 数据时最大值用 1 兜底，同样是为了不除零；
 *       ③ 只画首尾两个标签，中间省略——120dp 高度里塞 24 个「0 时」会糊成一片。
 */
class MiniChart(ctx: Context) : View(ctx) {

    /** 点按数据点的回调。 */
    fun interface OnPickListener {
        fun onPick(index: Int, value: Float, label: String?)
    }

    private val grid = Paint(1)
    private val line = Paint(1)
    private val fill = Paint(1)
    private val dot = Paint(1)
    private val text = Paint(1)

    private var values: FloatArray? = null
    private var labels: Array<String>? = null
    private var listener: OnPickListener? = null

    init {
        val d = ctx.resources.displayMetrics.density
        grid.style = Paint.Style.STROKE
        grid.strokeWidth = 1f
        grid.color = UiKit.LINE
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2.5f * d
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        line.color = UiKit.ACC
        fill.style = Paint.Style.FILL
        fill.color = (UiKit.ACC and 0x00FFFFFF) or 0x22000000
        dot.style = Paint.Style.FILL
        dot.color = UiKit.ACC
        text.isAntiAlias = true
        text.color = UiKit.SUB
        text.typeface = Typeface.DEFAULT_BOLD
    }

    /** 喂数据：values 为 null 或空数组即空态。 */
    fun setPoints(values: FloatArray?, labels: Array<String>?) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    fun setOnPick(l: OnPickListener?) {
        this.listener = l
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var w = View.MeasureSpec.getSize(widthSpec)
        if (w <= 0) {
            w = UiKit.dp(context, 240f)
        }
        setMeasuredDimension(w, UiKit.dp(context, 120f))
    }

    private fun value(i: Int): Float {
        val v = values?.get(i) ?: return 0f
        if (v.isNaN() || v < 0f) {
            return 0f
        }
        return v
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = UiKit.dp(context, 8f)
        val padR = UiKit.dp(context, 8f)
        val padT = UiKit.dp(context, 10f)
        val padB = UiKit.dp(context, 18f)
        val left = padL.toFloat()
        val right = w - padR
        val top = padT.toFloat()
        val bottom = h - padB

        val vals = values
        if (vals == null || vals.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = UiKit.dp(context, 13f).toFloat()
            val cy = h / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText("暂无消耗", w / 2f, cy, text)
            return
        }

        // 4 条横向网格线（含顶与底）
        for (i in 0 until 4) {
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(left, y, right, y, grid)
        }

        val n = vals.size
        var max = 1f
        for (i in 0 until n) {
            max = Math.max(max, value(i))
        }
        val step = if (n > 1) (right - left) / (n - 1) else 0f

        val path = Path()
        val area = Path()
        for (i in 0 until n) {
            val x = if (n > 1) left + step * i else (left + right) / 2f
            val y = bottom - (bottom - top) * (value(i) / max)
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, bottom)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        if (n > 1) {
            area.lineTo(right, bottom)
            area.close()
        }
        if (n > 1) {
            canvas.drawPath(area, fill)
            canvas.drawPath(path, line)
        }
        val rr = 2.5f * resources.displayMetrics.density
        val firstX = if (n > 1) left else (left + right) / 2f
        val lastX = if (n > 1) right else (left + right) / 2f
        canvas.drawCircle(firstX, bottom - (bottom - top) * (value(0) / max), rr, dot)
        canvas.drawCircle(lastX, bottom - (bottom - top) * (value(n - 1) / max), rr, dot)

        // 峰值小结（左上）
        text.textAlign = Paint.Align.LEFT
        text.textSize = UiKit.FS_TINY * resources.displayMetrics.density
        canvas.drawText("峰值 " + max.toLong(), left, top - UiKit.dp(context, 2f), text)

        // 首尾标签
        val lbls = labels
        if (lbls != null && lbls.size == n) {
            val ty = h - UiKit.dp(context, 5f)
            if (lbls[0] != null) {
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(lbls[0], left, ty, text)
            }
            if (lbls[n - 1] != null && n > 1) {
                text.textAlign = Paint.Align.RIGHT
                canvas.drawText(lbls[n - 1], right, ty, text)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(e)
        }
        val vals = values
        val pick = listener
        if (vals == null || vals.isEmpty() || pick == null) {
            return true
        }
        val padL = UiKit.dp(context, 8f)
        val padR = UiKit.dp(context, 8f)
        val left = padL.toFloat()
        val right = (width - padR).toFloat()
        val n = vals.size
        var step = if (n > 1) (right - left) / (n - 1) else 1f
        if (step <= 0f) {
            step = 1f
        }
        var idx = Math.round((e.x - left) / step)
        if (idx < 0) {
            idx = 0
        }
        if (idx > n - 1) {
            idx = n - 1
        }
        val ls = labels
        val label = if (ls != null && ls.size == n) ls[idx] else null
        pick.onPick(idx, value(idx), label)
        return true
    }
}
