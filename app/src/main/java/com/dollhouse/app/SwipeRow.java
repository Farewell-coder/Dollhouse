package com.dollhouse.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

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
final class SwipeRow extends FrameLayout {

    /** 露出的删除区宽度：够放下一个 24dp 图标 + 左右呼吸。 */
    private static final int ACTION_DP = 64;
    /** 滑过这个比例（相对满宽）就算「要打开」，松手后吸附过去。 */
    private static final float OPEN_RATIO = 0.4f;

    private final View content;
    private final Runnable onTap;
    private final Runnable onDelete;
    private final int slop;
    private final float max;
    private float downX;
    private float downY;
    private boolean open;
    private ValueAnimator anim;

    SwipeRow(Context ctx, View content, Runnable onTap, Runnable onDelete) {
        super(ctx);
        this.content = content;
        this.onTap = onTap;
        this.onDelete = onDelete;
        this.slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        this.max = UiKit.dp(ctx, ACTION_DP);

        FrameLayout action = new FrameLayout(ctx);
        action.setBackground(UiKit.round(UiKit.ERR, ctx, 14));
        ImageView trash = Icons.view(ctx, Icons.IC_TRASH, 20.0f, UiKit.ON_ACC);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        action.addView(trash, tlp);
        action.setClickable(true);
        action.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (SwipeRow.this.open && SwipeRow.this.onDelete != null) {
                    SwipeRow.this.onDelete.run();
                }
            }
        });
        addView(action, new FrameLayout.LayoutParams(
                UiKit.dp(ctx, ACTION_DP), LayoutParams.MATCH_PARENT, Gravity.END));

        addView(content, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        // 点击语义收口在本类：打开时先收回，否则才当作一次正常点击。
        content.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (SwipeRow.this.open) {
                    settle(false);
                    return;
                }
                if (SwipeRow.this.onTap != null) {
                    SwipeRow.this.onTap.run();
                }
            }
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
            return false;
        }
        if (a == MotionEvent.ACTION_MOVE) {
            float dx = e.getX() - downX;
            float dy = e.getY() - downY;
            if (Math.abs(dx) > this.slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null) {
                    // 已经判定为横向滑动，父级 ScrollView 不要再抢。
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                cancelAnim();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            cancelAnim();
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE) {
            float base = this.open ? -this.max : 0f;
            float t = base + (e.getX() - downX);
            if (t > 0f) {
                t = 0f;
            }
            if (t < -this.max) {
                t = -this.max;
            }
            this.content.setTranslationX(t);
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            settle(this.content.getTranslationX() < -this.max * OPEN_RATIO);
            return true;
        }
        return super.onTouchEvent(e);
    }

    /** 吸附到「打开」或「收起」，走弹簧。 */
    private void settle(final boolean target) {
        this.open = target;
        cancelAnim();
        final float from = this.content.getTranslationX();
        final float to = target ? -this.max : 0f;
        if (Math.abs(from - to) < 1.0f) {
            this.content.setTranslationX(to);
            return;
        }
        this.anim = Springs.drive(Springs.snappy(), new Springs.Listener() {
            @Override
            public void onUpdate(float p) {
                SwipeRow.this.content.setTranslationX(Springs.lerp(from, to, p));
            }

            @Override
            public void onEnd() {
                SwipeRow.this.content.setTranslationX(to);
            }
        });
    }

    private void cancelAnim() {
        if (this.anim != null) {
            this.anim.cancel();
            this.anim = null;
        }
        this.content.animate().cancel();
    }

    /** 删除区宽度（dp），调用方给列表留底部/右侧余量时用得到。 */
    static int actionWidthDp() {
        return ACTION_DP;
    }
}
