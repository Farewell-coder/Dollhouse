package com.dollhouse.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

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
final class CtxRing extends View {
    private final Paint track = new Paint(1);
    private final Paint arc = new Paint(1);
    private final Paint text = new Paint(1);
    /** 【v2.10.2】复用绘制矩形，避免每次重绘都产生一个短命 RectF。 */
    private final RectF box = new RectF();
    private float ratio;

    CtxRing(Context ctx) {
        super(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(2.4f * d);
        track.setColor(UiKit.CHAT_BORDER);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(2.4f * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(UiKit.CHAT_BUBBLE_USER);
        text.setAntiAlias(true);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(10.0f * d);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setColor(UiKit.TITLE);
    }

    /** 设置占用比例（0~1）。 */
    void setRatio(float f) {
        if (f < 0f) {
            f = 0f;
        }
        if (f > 1f) {
            f = 1f;
        }
        if (Math.abs(f - this.ratio) < 0.001f) {
            return;
        }
        this.ratio = f;
        invalidate();
    }

    float ratio() {
        return this.ratio;
    }

    /** 中心显示的整数百分比（0~100）。到 100 即已满足记忆自动总结的条数条件。 */
    int percent() {
        int p = Math.round(this.ratio * 100f);
        return Math.max(0, Math.min(100, p));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = UiKit.dp(getContext(), 30);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float strokePad = track.getStrokeWidth() / 2f + 1f;
        this.box.set(strokePad, strokePad, w - strokePad, h - strokePad);
        canvas.drawArc(box, 0f, 360f, false, track);
        if (this.ratio > 0f) {
            canvas.drawArc(box, -90f, 360f * this.ratio, false, arc);
        }
        String s = percent() + "";
        // 「100」比「9」宽一倍不止：先按基准字号量一次，超宽就等比缩，避免三位数被圆周裁掉。
        float base = 10.0f * getResources().getDisplayMetrics().density;
        float maxW = w - 2f * (track.getStrokeWidth() + 1.5f);
        text.setTextSize(base);
        float tw = text.measureText(s);
        if (tw > maxW && tw > 0f) {
            text.setTextSize(base * (maxW / tw));
        }
        float cy = h / 2f - (text.descent() + text.ascent()) / 2f;
        canvas.drawText(s, w / 2f, cy, text);
    }
}
