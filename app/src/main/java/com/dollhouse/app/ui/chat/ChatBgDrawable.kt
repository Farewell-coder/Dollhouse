package com.dollhouse.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * 【职责】聊天页背景专用 Drawable：把整张图按 center-crop 铺满容器。
 *
 * 【交互】由 ChatPanel.applyBackground() 生成并挂到聊天区 ScrollView 上。
 *
 * 【坑】不能用 BitmapDrawable + setGravity(Gravity.FILL)：FILL 会无视原图宽高比
 *        把图硬拉伸到容器尺寸，圆形变椭、人脸变扁。这里改为等比放大到「刚好覆盖」，
 *        多出来的部分居中裁掉，宁可裁边也绝不拉伸。
 */
class ChatBgDrawable(private val bmp: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private var alphaLevel = 255

    override fun setAlpha(i: Int) {
        if (alphaLevel != i) {
            alphaLevel = i
            paint.alpha = i
            invalidateSelf()
        }
    }

    override fun getAlpha(): Int {
        return alphaLevel
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val vw = bounds.width()
        val vh = bounds.height()
        val bw = bmp.width
        val bh = bmp.height
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) {
            return
        }
        // center-crop：取较小的缩放比，保证两边都铺满，再居中裁掉溢出部分。
        val scale = Math.max(vw.toFloat() / bw.toFloat(), vh.toFloat() / bh.toFloat())
        val sw = vw.toFloat() / scale
        val sh = vh.toFloat() / scale
        val left = (bw.toFloat() - sw) / 2.0f
        val top = (bh.toFloat() - sh) / 2.0f
        src.set(Math.round(left), Math.round(top), Math.round(left + sw), Math.round(top + sh))
        dst.set(bounds)
        canvas.drawBitmap(bmp, src, dst, paint)
    }
}
