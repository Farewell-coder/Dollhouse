package com.dollhouse.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

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
final class MiniChart extends View {

    /** 点按数据点的回调。 */
    interface OnPickListener {
        void onPick(int index, float value, String label);
    }

    private final Paint grid = new Paint(1);
    private final Paint line = new Paint(1);
    private final Paint fill = new Paint(1);
    private final Paint dot = new Paint(1);
    private final Paint text = new Paint(1);

    private float[] values;
    private String[] labels;
    private OnPickListener listener;

    MiniChart(Context ctx) {
        super(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f);
        grid.setColor(UiKit.LINE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(UiKit.ACC);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor((UiKit.ACC & 0x00FFFFFF) | 0x22000000);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(UiKit.ACC);
        text.setAntiAlias(true);
        text.setColor(UiKit.SUB);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** 喂数据：values 为 null 或空数组即空态。 */
    void setPoints(float[] values, String[] labels) {
        this.values = values;
        this.labels = labels;
        invalidate();
    }

    void setOnPick(OnPickListener l) {
        this.listener = l;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (w <= 0) {
            w = UiKit.dp(getContext(), 240);
        }
        setMeasuredDimension(w, UiKit.dp(getContext(), 120));
    }

    private float value(int i) {
        float v = values[i];
        if (Float.isNaN(v) || v < 0f) {
            return 0f;
        }
        return v;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float padL = UiKit.dp(getContext(), 8);
        float padR = UiKit.dp(getContext(), 8);
        float padT = UiKit.dp(getContext(), 10);
        float padB = UiKit.dp(getContext(), 18);
        float left = padL;
        float right = w - padR;
        float top = padT;
        float bottom = h - padB;

        if (values == null || values.length == 0) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(UiKit.dp(getContext(), 13));
            float cy = h / 2f - (text.descent() + text.ascent()) / 2f;
            canvas.drawText("暂无消耗", w / 2f, cy, text);
            return;
        }

        // 4 条横向网格线（含顶与底）
        for (int i = 0; i < 4; i++) {
            float y = top + (bottom - top) * i / 3f;
            canvas.drawLine(left, y, right, y, grid);
        }

        int n = values.length;
        float max = 1f;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, value(i));
        }
        float step = n > 1 ? (right - left) / (n - 1) : 0f;

        Path path = new Path();
        Path area = new Path();
        for (int i = 0; i < n; i++) {
            float x = n > 1 ? left + step * i : (left + right) / 2f;
            float y = bottom - (bottom - top) * (value(i) / max);
            if (i == 0) {
                path.moveTo(x, y);
                area.moveTo(x, bottom);
                area.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        if (n > 1) {
            area.lineTo(right, bottom);
            area.close();
        }
        if (n > 1) {
            canvas.drawPath(area, fill);
            canvas.drawPath(path, line);
        }
        float rr = 2.5f * getResources().getDisplayMetrics().density;
        float firstX = n > 1 ? left : (left + right) / 2f;
        float lastX = n > 1 ? right : (left + right) / 2f;
        canvas.drawCircle(firstX, bottom - (bottom - top) * (value(0) / max), rr, dot);
        canvas.drawCircle(lastX, bottom - (bottom - top) * (value(n - 1) / max), rr, dot);

        // 峰值小结（左上）
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(UiKit.FS_TINY * getResources().getDisplayMetrics().density);
        canvas.drawText("峰值 " + ((long) max), left, top - UiKit.dp(getContext(), 2), text);

        // 首尾标签
        if (labels != null && labels.length == n) {
            float ty = h - UiKit.dp(getContext(), 5);
            if (labels[0] != null) {
                text.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(labels[0], left, ty, text);
            }
            if (labels[n - 1] != null && n > 1) {
                text.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(labels[n - 1], right, ty, text);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(e);
        }
        if (values == null || values.length == 0 || listener == null) {
            return true;
        }
        float padL = UiKit.dp(getContext(), 8);
        float padR = UiKit.dp(getContext(), 8);
        float left = padL;
        float right = getWidth() - padR;
        int n = values.length;
        float step = n > 1 ? (right - left) / (n - 1) : 1f;
        if (step <= 0f) {
            step = 1f;
        }
        int idx = Math.round((e.getX() - left) / step);
        if (idx < 0) {
            idx = 0;
        }
        if (idx > n - 1) {
            idx = n - 1;
        }
        String label = (labels != null && labels.length == n) ? labels[idx] : null;
        listener.onPick(idx, value(idx), label);
        return true;
    }
}
