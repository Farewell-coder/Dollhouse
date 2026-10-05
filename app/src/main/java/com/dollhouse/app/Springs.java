package com.dollhouse.app;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.animation.Interpolator;

/**
 * 弹簧预置与驱动器。
 *
 * 【来源】预置参数照搬 morphicons 的 SPRING_PRESETS（MIT），ζ = c / (2√k)：
 *   smooth  ζ=1.00  临界阻尼，无过冲
 *   snappy  ζ=0.73  快，轻微过冲
 *   bouncy  ζ=0.40  活泼，明显回弹
 *
 * 【时钟源】不新起线程、不加常驻回调：用 ValueAnimator 借 vsync，
 *   每帧从 SystemClock 取真实 dt 喂给 Spring。duration 只作安全上限，
 *   弹簧自收敛会提前 cancel，因此手感与设备帧率无关。
 *
 * 【可取消】drive() 返回 ValueAnimator，调用方可按工程既有约定在
 *   onDestroy / 重排前 cancel（参考 PetWindowController 对 snapAnim 的处理）。
 */
public final class Springs {

    private Springs() {
    }

    /** 安全上限：预置里最慢的 bouncy 也在 1s 内收敛，2s 足够兜底。 */
    private static final long MAX_MS = 2000L;

    // ---- 预置 ----

    /** ζ=1.00，临界阻尼不过冲。适合高度、透明度这类「不能穿帮」的量。 */
    public static Spring smooth() {
        return new Spring(170.0, 26.0);
    }

    /** ζ=0.73，快且带轻微过冲。默认手感，适合位移、缩放、旋转。 */
    public static Spring snappy() {
        return new Spring(420.0, 30.0);
    }

    /** ζ=0.40，活泼回弹。仅用于按压松手这类「弹一下」的场合。 */
    public static Spring bouncy() {
        return new Spring(300.0, 14.0);
    }

    /** 回调：progress 已经过弹簧整形，0 → 1。 */
    public interface Listener {
        /** 每帧回调，progress ∈ [0, 1]（bouncy 时会短暂 > 1）。 */
        void onUpdate(float progress);

        /** 收敛或被打断时回调一次，用于收尾（置终态、清理引用）。 */
        void onEnd();
    }

    /**
     * 用弹簧驱动一次 0 → 1。
     *
     * @param spring   预置的弹簧实例（内部会先 start()）
     * @param listener 逐帧回调
     * @return 可取消的驱动句柄
     */
    public static ValueAnimator drive(final Spring spring, final Listener listener) {
        if (spring == null || listener == null) {
            return null;
        }
        spring.start();
        final long t0 = SystemClock.uptimeMillis();
        long[] last = new long[]{t0};
        final boolean[] ended = new boolean[]{false};

        ValueAnimator va = ValueAnimator.ofFloat(0.0f, 1.0f);
        va.setDuration(MAX_MS);
        // 不设 Interpolator：进度由 Spring 自己积分，ValueAnimator 只当时钟。
        va.setInterpolator(new Interpolator() {
            @Override
            public float getInterpolation(float input) {
                return input;
            }
        });
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator an) {
                long now = SystemClock.uptimeMillis();
                double dt = (now - last[0]) / 1000.0;
                last[0] = now;
                boolean settled = spring.step(dt);
                listener.onUpdate((float) spring.x);
                if (settled) {
                    an.cancel();
                }
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator an) {
                finish(ended, spring, listener);
            }

            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                finish(ended, spring, listener);
            }
        });
        va.start();
        return va;
    }

    /** 保证 onEnd 只回调一次，并把进度钉到终态。 */
    private static void finish(boolean[] ended, Spring spring, Listener listener) {
        if (ended[0]) {
            return;
        }
        ended[0] = true;
        spring.settle();
        listener.onUpdate(1.0f);
        listener.onEnd();
    }

    /**
     * 工具：线性映射 + 弹簧进度，得到当前值。
     * 例：from=0, to=100, progress=0.5 → 50。
     */
    public static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    /** 工具：int 版 lerp（带四舍五入，避免反复截断产生的抖动）。 */
    public static int lerpInt(int from, int to, float progress) {
        return Math.round(from + (to - from) * progress);
    }
}