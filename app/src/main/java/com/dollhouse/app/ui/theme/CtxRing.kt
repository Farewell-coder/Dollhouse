package com.dollhouse.app.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * 【职责】聊天面板顶栏的进度环：环形画「当前聊天条数 / 记忆触发阈值」，中心显示整数百分比。
 *
 * 【入口】只由 ChatPanel.build 创建，ChatPanel.refreshCtxRing 每轮刷新。
 *
 * 【交互】不持有数据，比例由外部按「history 条数 ÷ 记忆触发阈值」算好传进来；点击回调由外部挂。
 *
 * 【扩展】换口径只改 ChatPanel 的估算函数，本类不用动。
 *
 * 【坑】比例必须先 clamp 到 0~1 再画，否则 sweep 角会绕圈；文字用非等宽字体并在 onMeasure 里
 *       固定成正方形，避免不同字号下控件宽高抖动、把顶栏挤歪。
 */
class CtxRing(ctx: Context) : View(ctx) {
    private val track = Paint(1)
    private val arc = Paint(1)
    private val text = Paint(1)
    /** 【v2.10.2】复用绘制矩形，避免每次重绘都产生一个短命 RectF。 */
    private val box = RectF()
    private var ratioValue = 0f

    init {
        val d = ctx.resources.displayMetrics.density
        track.style = Paint.Style.STROKE
        track.strokeWidth = 2.4f * d
        track.color = UiKit.CHAT_BORDER
        arc.style = Paint.Style.STROKE
        arc.strokeWidth = 2.4f * d
        arc.strokeCap = Paint.Cap.ROUND
        arc.color = UiKit.CHAT_BUBBLE_USER
        text.isAntiAlias = true
        text.textAlign = Paint.Align.CENTER
        text.textSize = 10.0f * d
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = UiKit.TITLE
    }

    /** 设置占用比例（0~1）。 */
    fun setRatio(f: Float) {
        var v = f
        if (v < 0f) {
            v = 0f
        }
        if (v > 1f) {
            v = 1f
        }
        if (Math.abs(v - ratioValue) < 0.001f) {
            return
        }
        ratioValue = v
        invalidate()
    }

    fun ratio(): Float {
        return ratioValue
    }

    /** 中心显示的整数百分比（0~100）。到 100 即已满足记忆自动总结的条数条件。 */
    fun percent(): Int {
        val p = Math.round(ratioValue * 100f)
        return Math.max(0, Math.min(100, p))
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = UiKit.dp(context, 30f)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val strokePad = track.strokeWidth / 2f + 1f
        box.set(strokePad, strokePad, w - strokePad, h - strokePad)
        canvas.drawArc(box, 0f, 360f, false, track)
        if (ratioValue > 0f) {
            canvas.drawArc(box, -90f, 360f * ratioValue, false, arc)
        }
        val s = percent().toString()
        // 「100」比「9」宽一倍不止：先按基准字号量一次，超宽就等比缩，避免三位数被圆周裁掉。
        val base = 10.0f * resources.displayMetrics.density
        val maxW = w - 2f * (track.strokeWidth + 1.5f)
        text.textSize = base
        val tw = text.measureText(s)
        if (tw > maxW && tw > 0f) {
            text.textSize = base * (maxW / tw)
        }
        val cy = h / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(s, w / 2f, cy, text)
    }
}
