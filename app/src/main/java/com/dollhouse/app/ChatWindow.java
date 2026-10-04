package com.dollhouse.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 【职责】桌宠模式下的悬浮聊天窗，用 WindowManager 直接挂在屏幕上。
 *
 * 【交互】实现 ChatPanel.Controller，负责窗口尺寸/位置/拖动/贴边；宿主是 PetService。
 *
 * 【坑】窗口类型是 TYPE_APPLICATION_OVERLAY（SDK>=26 用 2038，否则 2002），装 View 前必须先拿到悬浮窗权限，否则 addView 会抛异常。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public class ChatWindow implements ChatPanel.Controller {
    private static final int MIN_H_DP = 240;
    private static final int MIN_W_DP = 220;
    private int baseH;
    private int baseW;
    private int baseX;
    private int baseY;
    private final Context ctx;
    private final DisplayMetrics dm;
    private final ChatWindow.Host host;
    private WindowManager.LayoutParams lp;
    private ChatPanel panel;
    private final SharedPreferences prefs;
    private FrameLayout root;
    private final WindowManager wm;
    private boolean added = false;
    private int imeHeight = 0;
    /**
     * 顶部让位高度：既是输入法弹出时聊天窗给桌宠预留的高度，也是聊天窗顶边能到达的最小 y。
     * 【坑】必须与 PetService.toggleChat / PetWindowController.applyScale 里算的
     *      perchHeight() + 16dp 保持一致；否则聊天窗会顶进人偶的停靠区，人偶被整个盖住。
     */
    private int topReserve = 0;

    public interface Host {
        void onClosed();

        void onGeometry(int i, int i2, int i3, int i4);

        void onOpenFullScreen();
    }

    private static int clamp(int i, int i2, int i3) {
        if (i3 < i2) {
            i3 = i2;
        }
        return i < i2 ? i2 : i > i3 ? i3 : i;
    }

    public void setTopReserve(int i) {
        this.topReserve = Math.max(0, i);
    }

    /**
     * 聊天窗顶边能到达的最小 y（= 人偶贴顶偷看时的头肩高度 + 16dp 余量）。
     * 【交互】拖拽 / 缩放 / 键盘弹出三条路径都必须按它夹一次，人偶才不会「从上方消失」。
     */
    private int topLimit() {
        return Math.max(0, this.topReserve);
    }

    public ChatWindow(Context context, ChatWindow.Host host) {
        this.ctx = context;
        this.host = host;
        this.wm = (WindowManager) context.getSystemService("window");
        this.prefs = PetPrefs.get(context);
        this.dm = context.getResources().getDisplayMetrics();
    }

    // 尺寸换算：统一走 UiKit，避免多处重复实现。
    private int dp(float f) {
        return UiKit.dp(this.ctx, f);
    }

    public boolean isShowing() {
        return this.added;
    }

    public void refreshBackground() {
        ChatPanel chatPanel;
        if (!this.added || (chatPanel = this.panel) == null) {
            return;
        }
        chatPanel.applyBackground();
    }

    // 显示聊天窗：补权限检查、建面板、挂 WindowManager。
    public void show() {
        if (this.added) {
            this.panel.focusInput();
            return;
        }
        this.root = new FrameLayout(this.ctx);
        ChatPanel chatPanel = new ChatPanel(this.ctx, this, false);
        this.panel = chatPanel;
        this.root.addView(chatPanel, new FrameLayout.LayoutParams(-1, -1));
        ChatWindow.GripView gripView = new ChatWindow.GripView(this.ctx, UiKit.CHAT_BORDER);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(30.0f), dp(30.0f));
        layoutParams.gravity = 8388693;
        this.root.addView(gripView, layoutParams);
        gripView.setOnTouchListener(new View.OnTouchListener() {            private float lastX;
            private float lastY;

            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                int actionMasked = motionEvent.getActionMasked();
                if (actionMasked == 0) {
                    this.lastX = motionEvent.getRawX();
                    this.lastY = motionEvent.getRawY();
                    return true;
                }
                if (actionMasked != 2) {
                    return false;
                }
                float rawX = motionEvent.getRawX();
                float rawY = motionEvent.getRawY();
                ChatWindow.this.resizeBy(rawX - this.lastX, rawY - this.lastY);
                this.lastX = rawX;
                this.lastY = rawY;
                return true;
            }
        });
        this.root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                int systemWindowInsetBottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    systemWindowInsetBottom = windowInsets.getInsets(WindowInsets.Type.ime()).bottom;
                } else {
                    systemWindowInsetBottom = windowInsets.getSystemWindowInsetBottom();
                }
                if (systemWindowInsetBottom != ChatWindow.this.imeHeight) {
                    ChatWindow.this.imeHeight = systemWindowInsetBottom;
                    ChatWindow.this.applyLayout();
                }
                return windowInsets;
            }
        });
        int min = Math.min(dp(310.0f), this.dm.widthPixels - dp(24.0f));
        int min2 = Math.min(dp(420.0f), this.dm.heightPixels - dp(90.0f));
        this.baseW = clamp(this.prefs.getInt("chat_w", min), dp(MIN_W_DP), this.dm.widthPixels - dp(12.0f));
        this.baseH = clamp(this.prefs.getInt("chat_h", min2), dp(MIN_H_DP), this.dm.heightPixels - dp(80.0f));
        this.baseX = clamp(this.prefs.getInt("chat_x", (this.dm.widthPixels - this.baseW) - dp(12.0f)), 0, Math.max(0, this.dm.widthPixels - this.baseW));
        this.baseY = clamp(this.prefs.getInt("chat_y", Math.round(this.dm.heightPixels * 0.28f)), 0, Math.max(0, (this.dm.heightPixels - this.baseH) - dp(8.0f)));
        WindowManager.LayoutParams layoutParams2 = new WindowManager.LayoutParams(this.baseW, this.baseH, overlayType(), 16777248, -3);
        this.lp = layoutParams2;
        layoutParams2.gravity = 8388659;
        this.lp.softInputMode = 16;
        applyLayout();
        try {
            this.wm.addView(this.root, this.lp);
            this.added = true;
        } catch (Throwable unused) {
            this.added = false;
            this.root = null;
            this.panel = null;
        }
        if (this.added) {
            this.panel.refreshHint();
            notifyGeometry();
        }
    }

    // 隐藏聊天窗并从 WindowManager 摘除。
    public void hide() {
        hide(true);
    }

    /**
     * 隐藏聊天窗。
     *
     * 【坑】凡「收起聊天窗」都必须让宿主知道（宿主据此退出 peek 恢复全身），
     * 否则桌宠会停在「只露头」的偷看态。因此默认 notifyHost=true；
     * 仅 Service 销毁时传 false —— 那会儿宿主正在拆自己，不需要再回调。
     */
    public void hide(boolean notifyHost) {
        if (this.added) {
            saveGeom();
            try {
                this.wm.removeView(this.root);
            } catch (Throwable unused) {
            }
            this.added = false;
            this.root = null;
            this.panel = null;
            this.imeHeight = 0;
            if (notifyHost) {
                ChatWindow.Host host = this.host;
                if (host != null) {
                    host.onClosed();
                }
            }
        }
    }

    /** 当前窗口的屏幕几何 [x, y, w, h]；gravity 是 TOP|LEFT，所以 lp 里的值就是屏幕绝对坐标。 */
    public int[] currentGeometry() {
        WindowManager.LayoutParams layoutParams = this.lp;
        if (!this.added || layoutParams == null) {
            return null;
        }
        return new int[]{layoutParams.x, layoutParams.y, layoutParams.width, layoutParams.height};
    }

    // 开/关切换。
    public void toggle() {
        if (this.added) {
            hide();
        } else {
            show();
        }
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
    }

    public void applyLayout() {
        WindowManager.LayoutParams layoutParams = this.lp;
        if (layoutParams == null) {
            return;
        }
        if (this.imeHeight > 0) {
            int dp = (this.dm.heightPixels - this.imeHeight) - dp(6.0f);
            int min = Math.min(this.baseH, Math.max(dp(150.0f), dp - this.topReserve));
            this.lp.width = this.baseW;
            this.lp.height = min;
            this.lp.x = this.baseX;
            // 键盘把窗口顶得太高时也要留出人偶的停靠区，否则桌宠会被聊天窗盖住。
            this.lp.y = Math.max(topLimit(), Math.max(0, dp - min));
        } else {
            layoutParams.width = this.baseW;
            this.lp.height = this.baseH;
            this.lp.x = this.baseX;
            layoutParams.y = Math.max(topLimit(), this.baseY);
        }
        update();
    }

    private void update() {
        FrameLayout frameLayout;
        if (!this.added || (frameLayout = this.root) == null) {
            return;
        }
        try {
            this.wm.updateViewLayout(frameLayout, this.lp);
        } catch (Throwable unused) {
        }
        notifyGeometry();
    }

    private void notifyGeometry() {
        final FrameLayout frameLayout;
        if (this.host == null || !this.added || (frameLayout = this.root) == null) {
            return;
        }
        frameLayout.post(new Runnable() {            @Override
            public void run() {
                if (!ChatWindow.this.added || ChatWindow.this.host == null) {
                    return;
                }
                FrameLayout frameLayout2 = ChatWindow.this.root;
                View view = frameLayout;
                if (frameLayout2 != view) {
                    return;
                }
                int[] iArr = new int[2];
                view.getLocationOnScreen(iArr);
                int width = frameLayout.getWidth();
                int height = frameLayout.getHeight();
                if (width <= 0 || height <= 0) {
                    width = ChatWindow.this.lp.width;
                    height = ChatWindow.this.lp.height;
                }
                ChatWindow.this.host.onGeometry(iArr[0], iArr[1], width, height);
            }
        });
    }

    // 把窗口位置与尺寸存进偏好，下次打开还原。
    private void saveGeom() {
        this.prefs.edit().putInt("chat_x", this.baseX).putInt("chat_y", this.baseY).putInt("chat_w", this.baseW).putInt("chat_h", this.baseH).apply();
    }
    /**
     * 【v2.10.2】屏幕尺寸变化后把聊天窗夹回屏内。
     *
     * 【为什么需要】baseX / baseY 是从 prefs 恢复的旧屏幕像素值：横屏下旧 baseY(屏高*0.4)
     *   会超出新屏高，窗口整个落到屏外，用户就再也看不到这个聊天框。
     *   这里按当前屏幕重新夹一次并落盘，不必等下一次 show()。
     *
     * 【幂等】没显示 / 数值本来就在屏内时结果不变，可重复调用。
     */
    public void onScreenChanged() {
        if (!this.added || this.lp == null || this.root == null) {
            return;
        }
        int maxH = Math.max(dp(MIN_H_DP), this.dm.heightPixels - dp(80.0f));
        this.baseW = clamp(this.baseW, dp(MIN_W_DP), Math.max(dp(MIN_W_DP), this.dm.widthPixels - dp(12.0f)));
        this.baseH = clamp(this.baseH, dp(MIN_H_DP), maxH);
        this.baseX = clamp(this.baseX, 0, Math.max(0, this.dm.widthPixels - this.baseW));
        this.baseY = clamp(this.baseY, 0, Math.max(0, (this.dm.heightPixels - this.baseH) - dp(8.0f)));
        applyLayout();
        saveGeom();
    }

    public void resizeBy(float f, float f2) {
        int dp = (this.dm.widthPixels - this.baseX) - dp(4.0f);
        int dp2 = (this.dm.heightPixels - this.baseY) - dp(4.0f);
        this.baseW = clamp(Math.round(this.baseW + f), dp(MIN_W_DP), Math.max(dp(MIN_W_DP), dp));
        this.baseH = clamp(Math.round(this.baseH + f2), dp(MIN_H_DP), Math.max(dp(MIN_H_DP), dp2));
        applyLayout();
    }

    @Override
    public void onDrag(float f, float f2) {
        this.baseX = clamp(Math.round(this.baseX + f), 0, Math.max(0, this.dm.widthPixels - this.baseW));
        // 上界用 topLimit：往上拖到底就停住，人偶始终留在聊天窗上方可见。
        this.baseY = clamp(Math.round(this.baseY + f2), topLimit(),
                Math.max(topLimit(), this.dm.heightPixels - this.baseH));
        applyLayout();
    }

    @Override
    // 切到全屏聊天 Activity。
    public void onOpenFullScreen() {
        hide();
        ChatWindow.Host host = this.host;
        if (host != null) {
            host.onOpenFullScreen();
        }
    }

    @Override
    // 悬浮窗里点顶栏的上下文环：本 Context 链里没有 Activity，开不了统计页，
    // 只能跳全屏聊天页并带上 extra，由 ChatActivity 落地后再叠上统计页。
    public void onOpenTokenStat() {
        hide();
        openFullScreen(this.ctx, ChatActivity.PAGE_TOKEN);
    }

    @Override
    // 关闭入口：收起聊天窗（hide 内部已回调 onClosed，让桌宠退出偷看态）。
    public void onClose() {
        hide();
    }

    private static class GripView extends View {
        private final Paint p;

        GripView(Context context, int i) {
            super(context);
            Paint paint = new Paint(1);
            this.p = paint;
            paint.setColor(i);
            paint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.6f);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float f = 0.22f * width;
            for (int i = 1; i <= 3; i++) {
                float f2 = width - f;
                float f3 = (i * f2) / 3.6f;
                float f4 = height - f;
                canvas.drawLine(f2 - f3, f4, f2, f4 - f3, this.p);
            }
        }
    }

    static void openFullScreen(Context context) {
        openFullScreen(context, null);
    }

    /** page 非空时，全屏页落地后会顺带打开对应子页（见 ChatActivity.EXTRA_PAGE）。 */
    static void openFullScreen(Context context, String page) {
        try {
            Intent intent = new Intent(context, (Class<?>) ChatActivity.class);
            intent.setFlags(335544320);
            if (page != null) {
                intent.putExtra(ChatActivity.EXTRA_PAGE, page);
            }
            context.startActivity(intent);
        } catch (Throwable unused) {
        }
    }
}
