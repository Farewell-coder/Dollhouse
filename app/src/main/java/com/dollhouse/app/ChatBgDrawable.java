package com.dollhouse.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 【职责】聊天页背景专用 Drawable：把整张图按 center-crop 铺满容器。
 *
 * 【交互】由 ChatPanel.applyBackground() 生成并挂到聊天区 ScrollView 上。
 *
 * 【坑】不能用 BitmapDrawable + setGravity(Gravity.FILL)：FILL 会无视原图宽高比
 *        把图硬拉伸到容器尺寸，圆形变椭、人脸变扁。这里改为等比放大到「刚好覆盖」，
 *        多出来的部分居中裁掉，宁可裁边也绝不拉伸。
 */
final class ChatBgDrawable extends Drawable {
    private final Bitmap bmp;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private int alpha = 255;

    ChatBgDrawable(Bitmap bitmap) {
        this.bmp = bitmap;
    }

    @Override
    public void setAlpha(int i) {
        if (this.alpha != i) {
            this.alpha = i;
            this.paint.setAlpha(i);
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return this.alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        this.paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int vw = bounds.width();
        int vh = bounds.height();
        int bw = this.bmp.getWidth();
        int bh = this.bmp.getHeight();
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) {
            return;
        }
        // center-crop：取较小的缩放比，保证两边都铺满，再居中裁掉溢出部分。
        float scale = Math.max((float) vw / (float) bw, (float) vh / (float) bh);
        float sw = (float) vw / scale;
        float sh = (float) vh / scale;
        float left = ((float) bw - sw) / 2.0f;
        float top = ((float) bh - sh) / 2.0f;
        this.src.set(Math.round(left), Math.round(top), Math.round(left + sw), Math.round(top + sh));
        this.dst.set(bounds);
        canvas.drawBitmap(this.bmp, this.src, this.dst, this.paint);
    }
}
