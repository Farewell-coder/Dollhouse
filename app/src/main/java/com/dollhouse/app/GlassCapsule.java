package com.dollhouse.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.FrameLayout;

/**
 * 【职责】底栏胶囊的「毛玻璃」底：把身后内容模糊后当自己的背景，再叠半透明染色 + 顶高光 + 描边。
 *
 * 【为什么要自己画】已用 javap 实测 android.jar：
 *   View 层只有 setRenderEffect —— 它模糊的是「自己画出来的内容」，不是身后；
 *   能模糊「窗口背后」的只有 Window.setBackgroundBlurRadius，而本页是叠在设置页之上的
 *   内嵌整屏页，同一窗口内没有任何系统 API 能模糊兄弟 View。真毛玻璃只能自己采样 + 自己模糊。
 *
 * 【性能】模糊不需要高分辨率：先按 1/SCALE 降采样（像素量降到 1/16），再跑三次 box blur
 *   （三次 box ≈ 一次高斯）。bitmap / Canvas / 像素缓冲全部按尺寸缓存复用；底栏滚动淡出
 *   期间直接关掉模糊（见 setBlurOn），既省算力又不会采到过期内容。
 *
 * 【坑】
 *   ① 偏移必须走「共同祖先」两段换算，直接拿 getLeft() 只对同父兄弟成立；
 *   ② 画布变换顺序必须是先 scale 再 translate，反了采样原点会整体错位；
 *   ③ 采样源绝不能包含本 View 自己（否则无限递归）—— 这里采的是内容层，它是本 View 的
 *      兄弟而非祖先，天然安全；
 *   ④ 采样前必须先填一层不透明底色，否则内容层的透明区域在模糊后会把 alpha 拉平均，
 *      叠到画布上会出现「越靠边越透」的脏边。
 */
final class GlassCapsule extends FrameLayout {

    /** 降采样倍率：最好取 2 的幂，像素量按平方降。 */
    private static final int SCALE = 4;
    /** box blur 半径（以降采样后的像素计）。 */
    private static final int BLUR_RADIUS = 5;
    /** 三次 box ≈ 一次高斯。 */
    private static final int BLUR_PASSES = 3;

    private final int fillColor;
    private final int hairlineColor;
    private final int baseColor;

    private View source;
    private boolean blurOn = true;

    private Bitmap bmp;
    private Canvas bmpCanvas;
    private int[] bufA;
    private int[] bufB;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private final RectF inner = new RectF();
    private final Rect dstRect = new Rect();
    private final Path clip = new Path();

    GlassCapsule(Context ctx, int fillColor, int hairlineColor, int baseColor) {
        super(ctx);
        this.fillColor = fillColor;
        this.hairlineColor = hairlineColor;
        this.baseColor = baseColor;
        // 【必须显式关掉 willNotDraw】ViewGroup 默认不调用 onDraw（WILL_NOT_DRAW 标志），
        //   而本类的全部视觉效果都在 onDraw 里 —— 不关掉就是一颗完全透明的胶囊。
        setWillNotDraw(false);
        hairPaint.setStyle(Paint.Style.STROKE);
        hairPaint.setStrokeWidth(Math.max(1f, UiKit.dpf(ctx, 1f)));
        // 胶囊外轮廓交给系统做投影：本 View 是全底栏唯一挂 elevation 的一层，形状即胶囊。
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                int w = v.getWidth();
                int h = v.getHeight();
                if (w > 0 && h > 0) {
                    o.setRoundRect(0, 0, w, h, h / 2f);
                }
            }
        });
    }

    /** 采样源：需要被「透过来」的内容层。 */
    void setSource(View v) {
        source = v;
    }

    /** 模糊开关。滚动淡出期间关掉：既省算力，也避免拿一帧过期内容去模糊。 */
    void setBlurOn(boolean on) {
        if (blurOn != on) {
            blurOn = on;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            glossPaint.setShader(null);
            return;
        }
        // 顶部高光：上亮下透。浅色主题下几乎看不出，深色主题下自然成为玻璃的高光边。
        glossPaint.setShader(new LinearGradient(0, 0, 0, h * 0.55f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = h / 2f;

        box.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(box, r, r, Path.Direction.CW);

        int save = canvas.save();
        canvas.clipPath(clip);
        drawBlur(canvas, w, h);
        fillPaint.setColor(fillColor);
        canvas.drawRect(box, fillPaint);
        canvas.drawRect(box, glossPaint);
        canvas.restoreToCount(save);

        // 描边画在裁剪之外，否则会被自己切掉一半、只剩半透明的一条浅痕。
        if (hairlineColor != 0) {
            float sw = hairPaint.getStrokeWidth();
            float in = sw / 2f;
            inner.set(in, in, w - in, h - in);
            hairPaint.setColor(hairlineColor);
            canvas.drawRoundRect(inner, Math.max(0f, r - in), Math.max(0f, r - in), hairPaint);
        }
    }

    private void drawBlur(Canvas canvas, int w, int h) {
        View src = source;
        if (src == null || !blurOn || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return;
        }
        int bw = Math.max(1, w / SCALE);
        int bh = Math.max(1, h / SCALE);
        if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != bh) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            bmpCanvas = new Canvas(bmp);
            bufA = new int[bw * bh];
            bufB = new int[bw * bh];
        }
        int[] off = sampleOrigin(src);
        if (off == null) {
            return;
        }
        bmpCanvas.drawColor(baseColor);
        int save = bmpCanvas.save();
        // 【变换顺序 + 符号，两个都不能错】Canvas 的 scale/translate 都是 pre-concat
        //   （M' = M × T），点 p 的最终坐标是 (p + t) / SCALE。
        //   本 View 的原点在 src 坐标系里位于 off，要让 src 的 off 点正好落到降采样画布的
        //   原点，就得解 (off + t) / SCALE == 0，即 t = -off。
        //   （若写成 +off，采样区会整体平移 2×off，采到离胶囊很远的另一块内容。）
        bmpCanvas.scale(1f / SCALE, 1f / SCALE);
        bmpCanvas.translate(-off[0], -off[1]);
        try {
            src.draw(bmpCanvas);
        } catch (Throwable ignored) {
            // 采样失败就退化成纯半透明玻璃，功能与观感不崩。
            bmpCanvas.restoreToCount(save);
            return;
        }
        bmpCanvas.restoreToCount(save);
        blur(bmp, bufA, bufB);
        dstRect.set(0, 0, w, h);
        canvas.drawBitmap(bmp, null, dstRect, bmpPaint);
    }

    /** 本 View 的原点在 src 坐标系里的偏移；两者无共同祖先时返回 null。 */
    private int[] sampleOrigin(View src) {
        View root = null;
        View v = this;
        while (v != null) {
            if (isAncestor(v, src)) {
                root = v;
                break;
            }
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        if (root == null) {
            return null;
        }
        int[] g = offsetIn(root, this);
        int[] s = offsetIn(root, src);
        if (g == null || s == null) {
            return null;
        }
        return new int[]{g[0] - s[0], g[1] - s[1]};
    }

    /** 后代原点相对祖先的偏移；ancestor 不是 descendant 的祖先时返回 null。 */
    private static int[] offsetIn(View ancestor, View descendant) {
        int x = 0;
        int y = 0;
        View v = descendant;
        while (v != null && v != ancestor) {
            x += v.getLeft();
            y += v.getTop();
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return v == ancestor ? new int[]{x, y} : null;
    }

    private static boolean isAncestor(View a, View b) {
        View v = b;
        while (v != null) {
            if (v == a) {
                return true;
            }
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    // ---------- 三次 box blur（滑动窗口累加，复杂度与半径无关） ----------

    private static void blur(Bitmap b, int[] a, int[] c) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (a == null || c == null || a.length < w * h || w < 3 || h < 3) {
            return;
        }
        b.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < BLUR_PASSES; i++) {
            blurH(a, c, w, h, BLUR_RADIUS);
            blurV(c, a, w, h, BLUR_RADIUS);
        }
        b.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void blurH(int[] src, int[] dst, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0;
            int rr = 0;
            int gg = 0;
            int bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[row + clamp(i, 0, w - 1)];
                a += c >>> 24;
                rr += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                bb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = ((a / div) << 24) | ((rr / div) << 16) | ((gg / div) << 8) | (bb / div);
                int out = src[row + clamp(x - r, 0, w - 1)];
                int in = src[row + clamp(x + r + 1, 0, w - 1)];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                gg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                bb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static void blurV(int[] src, int[] dst, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int a = 0;
            int rr = 0;
            int gg = 0;
            int bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[clamp(i, 0, h - 1) * w + x];
                a += c >>> 24;
                rr += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                bb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = ((a / div) << 24) | ((rr / div) << 16) | ((gg / div) << 8) | (bb / div);
                int out = src[clamp(y - r, 0, h - 1) * w + x];
                int in = src[clamp(y + r + 1, 0, h - 1) * w + x];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                gg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                bb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}