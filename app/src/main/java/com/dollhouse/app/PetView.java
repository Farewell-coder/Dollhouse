package com.dollhouse.app;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.Log;
import android.view.View;
/**
 * 【职责】桌宠的绘制与手势：呼吸、眨眼、拖拽倾斜、贴边吸附。
 *
 * 【交互】位图由 PetService 注入；位置变化通过 PetBus 广播。
 *
 * 【坑】geometry() 在精灵图为空时把尺寸全置 0，此时 windowWidth()/petWidth() 会返回 0 —— 依赖尺寸做布局前必须先确认 setSprite() 已经调用过。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public class PetView extends View {
    private static final float AHOGE_AMP = 0.022f;
    private static final float AHOGE_RU = 0.11f;
    private static final float AHOGE_RV = 0.09f;
    private static final float AHOGE_U = 0.3f;
    private static final float AHOGE_V = 0.06f;
    private static final float BLINK_SQUASH = 0.88f;
    private static final float BREATH_AMP = 0.01f;
    private static final float DRAG_POP = 1.04f;
    static final float DRAG_TILT_MAX = 8.0f;
    public static final float EDGE_INSET = 0.68f;
    public static final float EDGE_LEAN = 25.0f;
    public static final int EXPR_DIZZY = 3;
    public static final int EXPR_HAPPY = 2;
    public static final int EXPR_NONE = 0;
    public static final int EXPR_SURPRISED = 1;
    /** 表情字号 / 人偶宽度：0.20 即 150dp 人偶配 30dp 字（= 原始 APK 的固定 30dp 锚点），随人偶缩放同步。 */
    private static final float EMOTE_SIZE_RATIO = 0.20f;
    /** 表情描边宽度 / 人偶宽度：0.02 即 150dp 人偶配 3dp 描边。 */
    private static final float EMOTE_STROKE_RATIO = 0.02f;
    /** 气泡字号 / 人偶宽度：0.095 即 150dp 人偶配 14dp 字，气泡与人偶同比例缩放。 */
    private static final float BUBBLE_TEXT_RATIO = 0.095f;
    private static final float EYE_RU = 0.28f;
    private static final float EYE_RV = 0.065f;
    private static final float EYE_U = 0.5f;
    static final float EYE_V = 0.45f;
    private static final float GAZE_AMP = 0.019f;
    private static final float HAIR_AMP = 0.02f;
    static final int IDLE_HOP = 5;
    private static final int IDLE_LOOK = 2;
    static final int IDLE_NOD = 4;
    private static final int IDLE_NONE = 0;
    private static final int IDLE_STRETCH = 1;
    private static final int IDLE_YAWN = 3;
    private static final int MX = 28;
    private static final int MY = 36;
    /**
     * 趴姿露出比例：趴到聊天框顶时露出人偶自身的多少。
     * 【调优】0.6 只露头+肩，用户报「有点断头」；提到 0.72 能看到胸／腰，
     *         仍是标准的「趴」而非「被切断」。
     */
    public static final float PEEK_FRACTION = 0.72f;
    /**
     * 聊天框顶边与人偶的搭接量 / 人偶宽度：0.013 即 150dp 人偶搭约 2dp、225dp 人偶搭约 3dp。
     * 【调优】旧值 0.06（150dp 搭 9dp）在 3x 屏下实测 27px，视觉上像「被砍一刀」；
     *         用户要求「重合一丝丝」，收到 2~3dp 量级，仅洴住头顶一点点。
     */
    public static final float PEEK_OVERLAP_RATIO = 0.013f;
    private static final float TAILL_RU = 0.14f;
    private static final float TAILL_RV = 0.12f;
    private static final float TAILL_U = 0.06f;
    private static final float TAILL_V = 0.73f;
    private static final float TAILR_RU = 0.16f;
    private static final float TAILR_RV = 0.14f;
    private static final float TAILR_U = 0.93f;
    static final float TAILR_V = 0.7f;
    private static final float TAIL_AMP = 0.026f;
    private static final int VN = 1073;
    /**
     * 【v2.10.2】熄屏低功耗的心跳间隔。
     *
     * 【为什么不彻底停帧】旧实现熄屏时 removeCallbacks 完全停摆，只等 SCREEN_ON 广播唤醒；
     *   个别 ROM（含 ColorOS）的该广播会晚到甚至丢失，人偶就一直黑着不回来。
     *   保留这一档慢心跳 + checkAwake() 自检，保证「用户开屏之后一定看得到人偶」。
     */
    static final long HEARTBEAT_MS = 1000L;
    /**
     * 【v2.10.2】贴边偷看时的最低帧率（250ms ≈ 4fps）。
     *
     * 【依据】人偶吸附在屏幕边缘 = 用户正在别处操作，此时呼吸与尾巴都是低频正弦，
     *   4fps 肉眼看不出差别；而待机小动作与眨眼（两个唯一的周期性动作）已在 PetAnimator 里压掉，
     *   所以本档能稳定住，不会被 60fps 顶回去。
     */
    static final long EDGE_IDLE_MS = 250L;
    /** 【v2.10.2】熄屏低功耗标志：true 时不推进动画、不绘制，只跑 HEARTBEAT_MS 心跳自检。 */
    boolean lowPower;
    /**
     * 【v2.10.2】屏幕尺寸自检回调，由 PetService 注入（指向 PetWindowController.checkScreenChanged）。
     *
     * 【为什么要它】不能指望 ROM 一定派发 Service 的 onConfigurationChanged：
     *   只要帧循环还在跑，这里每帧多一次 int 比较，旋转后最多一帧就能把窗口复位回屏内。
     */
    Runnable screenTick;
    /** 【v2.10.2】getLocationOnScreen 的复用缓冲（旧实现每帧 new int[2]，onDraw 里尤其浪费）。 */
    private final int[] locBuf = new int[2];
    int affection;
    boolean animating;
    float blinkCountdown;
    float blinkT;
    private final Paint bmpPaint;
    private final float bobAmp;
    private final float[] boxA;
    private final float[] boxB;
    private final float[] boxBuf;
    /** boxAt 的预分配复用数组：原来是两个 float[4] 字面量，每帧被调用多次会产生大量临时数组。 */
    private final float[] boxAtX = new float[IDLE_NOD];
    private final float[] boxAtY = new float[IDLE_NOD];
    // 网格缓存：记录上一次 buildMesh 的量化输入，未变则跳过重建。
    private float meshW = -1.0f;
    private float meshH = -1.0f;
    private int meshPhaseQ = Integer.MIN_VALUE;
    private int meshBlinkQ = Integer.MIN_VALUE;
    private int meshEyeQ = Integer.MIN_VALUE;
    private int meshGazeQ = Integer.MIN_VALUE;
    private int meshExpr = Integer.MIN_VALUE;
    private boolean meshValid = false;
    private final Paint bubbleEdge;
    private final Paint bubblePaint;
    float dragLift;
    float dragVx;
    boolean dragging;
    /** 人偶缩放系数：1.0 = 原始 150dp。由 PetService 从偏好读入。 */
    float scaleFactor = 1.0f;
    private final TextPaint emotePaint;
    long exprDuration;
    long exprStartAt;
    int expression;
    final Runnable frame;
    float gaze;
    float gazeCountdown;
    float gazeTarget;
    int idleAction;
    float idleDuration;
    float idleEyeScale;
    float idleLiftPx;
    float idleNextIn;
    float idleScaleY;
    float idleT;
    float idleTilt;
    final float jumpAmp;
    float jumpT;
    float landT;
    long lastFrame;
    int lastIdleAction;
    float lean;
    boolean peek;
    float phase;
    private Bitmap sprite;
    private final TextPaint textPaint;
    float tilt;
    private final float[] verts;
    private final PetBubble bubble;
    private final PetAnimator anim;
    private String emoteGlyph(int i) {
        return i != 1 ? i != 2 ? i != 3 ? "" : "？" : "♪" : "！";
    }
    private static float smoothstep(float f, float f2, float f3) {
        float f4 = (f3 - f) / (f2 - f);
        if (f4 < 0.0f) {
            f4 = 0.0f;
        }
        if (f4 > 1.0f) {
            f4 = 1.0f;
        }
        return f4 * f4 * (3.0f - (f4 * 2.0f));
    }
    public PetView(Context context) {
        super(context);
        this.verts = new float[VN * 2];
        this.lean = 0.0f;
        this.expression = 0;
        this.exprStartAt = 0L;
        this.exprDuration = 1200L;
        TextPaint textPaint = new TextPaint(1);
        this.emotePaint = textPaint;
        this.dragging = false;
        this.dragVx = 0.0f;
        this.dragLift = 0.0f;
        this.landT = -1.0f;
        this.idleAction = 0;
        this.lastIdleAction = 0;
        this.idleT = -1.0f;
        this.idleDuration = 1.5f;
        this.idleNextIn = 14.0f;
        this.idleScaleY = 1.0f;
        this.idleLiftPx = 0.0f;
        this.idleEyeScale = 1.0f;
        this.idleTilt = 0.0f;
        this.peek = false;
        this.bmpPaint = new Paint(7);
        Paint paint = new Paint(1);
        this.bubblePaint = paint;
        Paint paint2 = new Paint(1);
        this.bubbleEdge = paint2;
        TextPaint textPaint2 = new TextPaint(1);
        this.textPaint = textPaint2;
        this.bubble = new PetBubble(this.bubblePaint, this.bubbleEdge, this.textPaint);
        this.anim = new PetAnimator(this);
        this.phase = 0.0f;
        this.jumpT = -1.0f;
        this.tilt = 0.0f;
        this.blinkT = -1.0f;
        this.blinkCountdown = 3.0f;
        this.gaze = 0.0f;
        this.gazeTarget = 0.0f;
        this.gazeCountdown = 2.0f;
        this.affection = 50;
        this.animating = false;
        this.lastFrame = 0L;
        this.frame = new Runnable() {            @Override
            public void run() {
                // 【v2.10.2】屏幕尺寸自检：不论哪一档帧率都先跑（成本只是两个 int 比较）。
                //   旋转后即使 ROM 不派发配置变化，这里也能把窗口复位回屏内（用户报的「切换后消失」）。
                Runnable tickCb = PetView.this.screenTick;
                if (tickCb != null) {
                    tickCb.run();
                }
                // 【v2.10.2】低功耗档（熄屏）：不推进动画、不重绘，只自检「是不是已经亮屏了」。
                if (PetView.this.lowPower) {
                    if (PetView.this.screenOn() && PetView.this.isShown()) {
                        // 【坑】这里不能直接调 exitLowPower()：它内部会 removeCallbacks + postDelayed(16)，
                        //   而本方法尾部还会再 post 一次同一个 Runnable —— 同一 Runnable 排队两次，
                        //   之后每次执行又各自续一次，帧率会永久翻倍。
                        //   帧内自愈只改状态，续帧统一交给尾部这一次 post。
                        PetView.this.lowPower = false;
                        PetView.this.lastFrame = SystemClock.uptimeMillis();
                        PetView.this.invalidate();
                    }
                } else {
                    PetView.this.tick();
                }
                if (PetView.this.animating) {
                    PetView.this.postDelayed(this, PetView.this.nextFrameDelay());
                }
            }
        };
        this.boxBuf = new float[IDLE_NOD];
        this.boxA = new float[IDLE_NOD];
        this.boxB = new float[IDLE_NOD];
        paint.setColor(UiKit.CARD);
        paint.setStyle(Paint.Style.FILL);
        paint2.setColor(UiKit.TITLE);
        paint2.setStyle(Paint.Style.STROKE);
        paint2.setStrokeWidth(dp(2.0f));
        textPaint2.setColor(UiKit.TITLE);
        textPaint2.setTextSize(dp(14.0f));
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        // 气泡字号随人偶宽度走（0.095 → 150dp 人偶配 14dp 字）；setScaleFactor 会重设，这里只给初值。
        // 注意目标是 this.textPaint（气泡用），textPaint 是 emotePaint 的局部别名，别写串。
        this.textPaint.setTextSize(dp(150.0f) * BUBBLE_TEXT_RATIO);
        this.bobAmp = dp(2.5f);
        this.jumpAmp = dp(18.0f);
        setLayerType(2, null);
    }
    /** 尺寸换算：统一走 UiKit，保留小数精度（原实现就是 float）。 */
    float dp(float f) {
        return UiKit.dpf(getContext(), f);
    }

    /**
     * 主题切换后刷新绘制用色。气泡与提示条的 Paint 是构造时定色的，不会自动跟随
     * ThemeManager 改字段，所以桌宠已在运行时由 PetService 在 REFRESH 时回调这里。
     */
    public void applyTheme() {
        this.bubblePaint.setColor(UiKit.CARD);
        this.bubbleEdge.setColor(UiKit.TITLE);
        this.textPaint.setColor(UiKit.TITLE);
        invalidate();
    }

    /**
     * 下一帧的间隔：默认 60fps（16ms），静止待机时降到约 30fps（32ms）省电。
     *
     * 判定「需要全速」的任一条件成立即回 16ms：
     *   拖拽 / 跳跃 / 落地回弹 / 眨眼 / 待机小动作 / 表情 / 视线插值未收敛 / 倾斜回正。
     * 全部不成立（纯站立呼吸态，含 peek 贴顶静止）时用 32ms —— 呼吸与尾巴摆动是低频正弦，30fps 看不出差别。
     *
     * 【性能】peek 不再强制 60fps：聊天窗开着时桌宠与聊天窗是两个独立 overlay 窗口，
     *         人偶每帧重建 1073 顶点会抢渲染线程，是聊天区滚动掉帧的主因。
     */
    long nextFrameDelay() {
        // 【v2.10.2】低功耗档（熄屏）：1s 心跳。不再是旧实现的 250ms —— 熄屏时既不推进动画
        //   也不重绘，250ms 纯属空转；1s 足以保证亮屏后被 checkAwake 及时发现。
        if (lowPower) {
            return HEARTBEAT_MS;
        }
        // 屏幕灭时 4fps 兜底（真正的低功耗在 enterLowPower，这里防的是
        // 广播晚到或个别 ROM 不发广播的情况）。isShown() 只看「已 attach + visibility」，
        // 熄屏时它仍为 true，所以必须再问一次屏幕开关，否则屏灭后桌宠照旧 30fps 空转。
        if (!isShown() || !screenOn()) {
            return 250L;
        }
        boolean busy = dragging
                || jumpT >= 0.0f
                || landT >= 0.0f
                || blinkT >= 0.0f
                || idleAction != 0
                || idleT >= 0.0f
                || expression != 0
                || dragLift > 0.002f
                || Math.abs(tilt) > 0.05f
                || Math.abs(gaze - gazeTarget) > 0.004f;
        if (busy) {
            return 16L;
        }
        // 【v2.10.2】贴边偷看且无任何动作：降到最低帧率。
        //   人偶吸在屏幕边缘 = 用户正在别处操作，此时只有低频呼吸与尾巴摆动，
        //   4fps 看不出差别；眨眼与待机小动作已由 PetAnimator 在 lean != 0 时压掉，
        //   所以本档不会被周期性动作顶回 60fps。
        if (lean != 0.0f) {
            return EDGE_IDLE_MS;
        }
        return 32L;
    }
    /**
     * 【v2.10.2】进入低功耗（熄屏）。
     *
     * 【与旧 stopAnim 的区别】旧实现在熄屏时 removeCallbacks 彻底停帧，只等 SCREEN_ON 广播唤醒；
     *   一旦该广播晚到或丢失，人偶就再也不动、亮屏也看不到（用户报的「切换后容易消失」同类问题）。
     *   现在改为「保留 1s 心跳 + 帧内自检屏幕」，亮屏最多 1 秒内自恢复。
     */
    void enterLowPower() {
        if (this.lowPower) {
            return;
        }
        this.lowPower = true;
        this.animating = true;
        removeCallbacks(this.frame);
        postDelayed(this.frame, HEARTBEAT_MS);
    }
    /** 【v2.10.2】退出低功耗（亮屏 / 恢复可见）：接着原状态继续跑，并立刻补一帧。 */
    void exitLowPower() {
        if (!this.lowPower) {
            return;
        }
        this.lowPower = false;
        this.lastFrame = SystemClock.uptimeMillis();
        this.animating = true;
        removeCallbacks(this.frame);
        postDelayed(this.frame, 16L);
        invalidate();
    }
    // 屏幕是否亮着。拿不到电源服务时按「亮着」处理，宁可多跑也不让桌宠在亮屏时卡住。
    private boolean screenOn() {
        try {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }
    /**
     * 熄屏时彻底停掉帧循环（不是降频，是 removeCallbacks + 停 invalidate），
     * 亮屏立刻恢复 —— 这样屏灭期间 CPU 完全不被桌宠唤醒，醒来时动画是接着原状态跑的，
     * 不会出现「重新开始」或卡顿。
     */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            if (on) {
                // 亮屏：从当前状态接着跑，并立刻补一帧，避免第一帧还停在旧画面。
                exitLowPower();
                if (!animating) {
                    startAnim();
                }
                invalidate();
            } else {
                // 【v2.10.2】熄屏改为低功耗心跳（旧实现是 stopAnim 彻底停帧，广播丢了就再也回不来）。
                enterLowPower();
            }
        }
    };
    private boolean screenReceiverOn = false;
    /** 由 attach/detach 时调用：注册熄屏/亮屏广播，重复调用安全。 */
    void registerScreenReceiver() {
        if (screenReceiverOn) {
            return;
        }
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            getContext().registerReceiver(screenReceiver, f);
            screenReceiverOn = true;
            // 注册时如果屏已经灭着，直接按灭屏处理，别等下一次广播。
            if (!screenOn()) {
                stopAnim();
            }
        } catch (Throwable ignored) {
            Log.w("Dollhouse", "ignored", ignored);
        }
    }
    /** 由 detach 时调用：注销广播。 */
    void unregisterScreenReceiver() {
        if (!screenReceiverOn) {
            return;
        }
        try {
            getContext().unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
            Log.w("Dollhouse", "ignored", ignored);
        }
        screenReceiverOn = false;
    }
    /** 分页用全文（未切片）；与 PetBubble 的 fullText 配套，clearBubble 时一并清空。 */
    private String bubbleFull;
    public void setSprite(Bitmap bitmap) {
        this.sprite = bitmap;
        invalidate();
    }
    /** 设置人偶缩放系数（1.0 = 原始大小）。调用方负责随后重算窗口尺寸。 */
    public void setScaleFactor(float f) {
        if (f <= 0.0f) {
            return;
        }
        this.scaleFactor = f;
        // 顺序要紧：先把字号设好，再让气泡按新字号重排；反过来的话重排用的还是旧字号。
        this.textPaint.setTextSize(dp(150.0f) * BUBBLE_TEXT_RATIO * f);
        // 气泡的几何（圆角/内边距/尾巴/行距）也要跟着缩放，否则放大后人偶配一颗小气泡。
        this.bubble.setScale(f);
        invalidate();
    }
    public Bitmap current() {
        return this.sprite;
    }
    public void setEdgePeek(boolean z, boolean z2) {
        // 【v2.10.2】原实现这里硬写 25.0f / -25.0f，与上面的 EDGE_LEAN 常量重复；
        //   统一走常量（值相同，倾角行为不变），改倾角时才不会漏掉这一处。
        float f = z ? (z2 ? EDGE_LEAN : -EDGE_LEAN) : 0.0f;
        if (this.lean == f) {
            return;
        }
        this.lean = f;
        if (z) {
            this.blinkT = -1.0f;
            this.blinkCountdown = 0.8f;
        }
        invalidate();
    }
    public boolean isEdgePeeking() {
        return this.lean != 0.0f;
    }
    public int edgeInsetPx() {
        return Math.round(petWidth() * EDGE_INSET);
    }
    /**
     * 气泡可用的排版宽度（贴边时小于窗口宽）。
     *
     * 【贴边】人偶贴边偷看时窗口有一大截在屏幕外，气泡若按满窗宽排版，文字会排到屏幕外、
     *         画出来只剩一半（用户报的「趴着时对话框一半在外面」）。
     *         这里取「窗口 ∩ 屏幕」的可见区宽度，让排版与绘制边界一致。
     */
    public int bubbleAvailWidth() {
        int[] loc = this.locBuf;
        getLocationOnScreen(loc);
        return bubbleAvailWidthFor(Math.max(getWidth(), petWidth()), loc[0]);
    }
    /**
     * 气泡可用的排版宽度（贴边时小于窗口宽）。
     *
     * 【贴边】人偶贴边偷看时窗口有一大截在屏幕外，气泡若按满窗宽排版，文字会排到屏幕外、
     *         画出来只剩一半（用户报的「趴着时对话框一半在外面」）。
     *         这里取「窗口 ∩ 屏幕」的可见区宽度，让排版与绘制边界一致。
     *
     * 【参数】windowW / windowX 必须显式传入——刚 updateViewLayout 过、还没走下一帧时
     *         getWidth() 与 getLocationOnScreen() 拿到的仍是旧值（缩放后尤其明显）。
     */
    public int bubbleAvailWidthFor(int windowW, int windowX) {
        int w = Math.max(1, windowW);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int visible = Math.min(screenW, windowX + w) - Math.max(0, windowX);
        if (visible <= 0 || visible >= w) {
            return w;
        }
        return visible;
    }
    /** 窗口移动 / 缩放后按新的可见区强制重排气泡（不能靠 onWidth 兜底，它的宽度语义不同）。 */
    public void relayoutBubble(int windowW, int windowX) {
        this.bubble.relayout(bubbleAvailWidthFor(windowW, windowX), dp(1.0f));
        invalidate();
    }
    public int petWidth() {
        if (current() == null) {
            return 0;
        }
        return Math.round(dp(150.0f) * this.scaleFactor);
    }
    /**
     * 最大比例（界面 100% = PET_SCALE_MAX）下的人偶宽度。
     *
     * 【用途】迷你聊天的输入框宽度固定取本值：框宽不再随人偶比例浮动，
     *         用户把人偶调小后框宽不变，人偶在框上水平居中。
     * 【依据】petWidth() = dp(150) * scaleFactor，而 scaleFactor = petScale/100，
     *         上限 PET_SCALE_MAX=150 ⇒ 系数 1.5 ⇒ 宽度 225dp。
     *         直接引 PetPrefs 的常量，避免再造一个写死的 1.5。
     */
    public int petMaxWidth() {
        if (current() == null) {
            return 0;
        }
        return Math.round(dp(150.0f) * (PetPrefs.PET_SCALE_MAX / 100.0f));
    }
    private void computeBox(float[] fArr) {
        boolean z = this.dragging;
        float f = z ? DRAG_POP : 1.0f;
        float f2 = z ? DRAG_TILT_MAX : 0.0f;
        boxAt(this.lean + f2, f, this.boxA);
        boxAt(this.lean - f2, f, this.boxB);
        float min = Math.min(this.boxA[0], this.boxB[0]);
        float max = Math.max(this.boxA[1], this.boxB[1]);
        float min2 = Math.min(this.boxA[2], this.boxB[2]);
        float max2 = Math.max(this.boxA[3], this.boxB[3]);
        fArr[0] = max - min;
        fArr[1] = max2 - min2;
        fArr[2] = -min;
        fArr[3] = -min2;
    }
    private void boxAt(float f, float f2, float[] fArr) {
        double radians = Math.toRadians(f);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float petWidth = (petWidth() * f2) / 2.0f;
        float f3 = -petWidth;
        float[] fArr2 = this.boxAtX;
        fArr2[0] = f3;
        fArr2[1] = petWidth;
        fArr2[2] = petWidth;
        fArr2[3] = f3;
        float f4 = -(fullHeight() * f2);
        float[] fArr3 = this.boxAtY;
        fArr3[0] = 0.0f;
        fArr3[1] = 0.0f;
        fArr3[2] = f4;
        fArr3[3] = f4;
        float f5 = Float.MAX_VALUE;
        float f6 = -3.4028235E38f;
        float f7 = -3.4028235E38f;
        float f8 = Float.MAX_VALUE;
        for (int i = 0; i < IDLE_NOD; i++) {
            float f9 = fArr2[i];
            float f10 = fArr3[i];
            float f11 = (f9 * cos) - (f10 * sin);
            float f12 = (f9 * sin) + (f10 * cos);
            if (f11 < f5) {
                f5 = f11;
            }
            if (f11 > f6) {
                f6 = f11;
            }
            if (f12 < f8) {
                f8 = f12;
            }
            if (f12 > f7) {
                f7 = f12;
            }
        }
        fArr[0] = f5;
        fArr[1] = f6;
        fArr[2] = f8;
        fArr[3] = f7;
    }
    private float headroom() {
        return this.jumpAmp + dp(6.0f);
    }
    public void geometry(float[] fArr) {
        if (current() == null) {
            fArr[3] = 0.0f;
            fArr[2] = 0.0f;
            fArr[1] = 0.0f;
            fArr[0] = 0.0f;
            return;
        }
        if (this.peek) {
            // 【定案 v2.6】peek 态窗口高 = 完整身高（不再截到 0.72）：
            //   用户要求「人偶以完整形态悬浮在对话框上面」，
            //   不再是「半个头搭在框上」。
            int petWidth = petWidth();
            float f = petWidth;
            fArr[0] = f;
            fArr[1] = fullHeight();
            fArr[2] = f / 2.0f;
            fArr[3] = fullHeight();
            return;
        }
        computeBox(this.boxBuf);
        float[] fArr2 = this.boxBuf;
        fArr[0] = fArr2[0];
        fArr[1] = fArr2[1] + headroom();
        float[] fArr3 = this.boxBuf;
        fArr[2] = fArr3[2];
        fArr[3] = fArr3[3] + headroom();
    }
    public int windowWidth() {
        geometry(this.boxBuf);
        return Math.round(this.boxBuf[0]);
    }
    public float pivotLocalX() {
        geometry(this.boxBuf);
        return this.boxBuf[2];
    }
    public float pivotLocalY() {
        geometry(this.boxBuf);
        return this.boxBuf[3];
    }
    public int petHeight() {
        return baseHeight();
    }
    int fullHeight() {
        if (current() == null) {
            return 0;
        }
        Bitmap bitmap = current();
        return Math.round((petWidth() * bitmap.getHeight()) / bitmap.getWidth());
    }
    public int baseHeight() {
        geometry(this.boxBuf);
        return Math.round(this.boxBuf[1]);
    }
    public void setPeek(boolean z) {
        if (this.peek == z) {
            return;
        }
        this.peek = z;
        if (z) {
            this.blinkT = -1.0f;
            this.blinkCountdown = 1.2f;
        }
        invalidate();
    }
    public boolean isPeeking() {
        return this.peek;
    }
    public int perchHeight() {
        // 【定案 v2.6】完整身高：人偶整只悬在框上，不裁下半截。
        return fullHeight();
    }
    // 【已删除 v2.5】perchHeightAtMax()：曾用于「框宽恒定为最大比例」方案。
    //   该方案会把人偶强行放大，已撤销；趴姿高度统一走 perchHeight()。
    public int bubbleSpace() {
        return Math.round(dp(96.0f));
    }
    /** 气泡需要的高度；实现已拆到 PetBubble。 */
    public int neededBubbleSpace() {
        return this.bubble.neededHeight(bubbleAvailWidth(), dp(1.0f));
    }
    /** 指定窗口宽与位置时的气泡所需高度（窗口刚移动 / 缩放、布局还没生效时用）。 */
    public int neededBubbleSpaceFor(int windowW, int windowX) {
        return this.bubble.neededHeight(bubbleAvailWidthFor(windowW, windowX), dp(1.0f));
    }
    public void setBubbleHeight(int i) {
        this.bubble.setHeight(i);
        invalidate();
    }
    public void showBubble(String str) {
        this.bubble.show(str, bubbleAvailWidth(), dp(1.0f));
        invalidate();
    }
    /**
     * 分页显示一段长文本：第一屏只显示前 linesPerPage 行，返回全文总行数。
     *
     * 【为什么需要】用户要求「点一下进入下一片」，而不是一次展开到底。
     *   全文塞进 PetBubble 的 fullText，后续翻页用 bubblePage() 取片。
     * 【坑】总量行数必须在「全文」排版下量，不能拿切片后的文本量 —— 越切越短，页数会漂。
     */
    public int showBubblePaged(String full, int targetLines) {
        this.bubbleFull = full;
        int[] loc = this.locBuf;
        getLocationOnScreen(loc);
        float w = bubbleAvailWidthFor(Math.max(getWidth(), petWidth()), loc[0]);
        float d = dp(1.0f);
        // 【v2.6】改标点分页：返回值从「总行数」变为「总页数」。
        int pages = this.bubble.showPaged(full, targetLines, w, d);
        invalidate();
        return pages;
    }
    /** 取第 index 页文本（翻页用）；必须与 showBubblePaged 的全文配套。 */
    public String bubblePage(int index) {
        if (this.bubbleFull == null) {
            return null;
        }
        return this.bubble.page(index);
    }
    /** 切换气泡最大行数（截断态 / 展开态）；实现已拆到 PetBubble。 */
    public void setBubbleMaxLines(int i) {
        this.bubble.setMaxLines(i);
        invalidate();
    }
    /** 当前气泡文案是否被截断（决定点一下是展开还是收掉）；实现已拆到 PetBubble。 */
    public boolean bubbleHasOverflow() {
        return this.bubble.hasOverflow();
    }
    public void clearBubble() {
        this.bubbleFull = null;
        this.bubble.clear();
        invalidate();
    }
    @Override
    protected void onDetachedFromWindow() {
        unregisterScreenReceiver();
        stopAnim();
        super.onDetachedFromWindow();
    }
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 挂上就开始盯屏幕开关：屏灭停帧、亮屏续跑，屏灭期间零唤醒。
        registerScreenReceiver();
    }
    private int emoteColor(int i) {
        if (i == 1) {
            return UiKit.EMOTE_1;
        }
        if (i == 2) {
            return UiKit.EMOTE_2;
        }
        if (i != 3) {
            return UiKit.CARD;
        }
        return UiKit.EMOTE_3;
    }
    @Override
    protected void onSizeChanged(int i, int i2, int i3, int i4) {
        super.onSizeChanged(i, i2, i3, i4);
        if (this.bubble.hasContent()) {
            this.bubble.onWidth(i, dp(1.0f));
        }
    }
    private static float falloff(float f, float f2, float f3, float f4, float f5, float f6) {
        float f7 = (f - f3) / f5;
        float f8 = (f2 - f4) / f6;
        float sqrt = (float) Math.sqrt((f7 * f7) + (f8 * f8));
        if (sqrt >= 1.0f) {
            return 0.0f;
        }
        float f9 = 1.0f - sqrt;
        return f9 * f9 * (3.0f - (f9 * 2.0f));
    }
    /**
     * 网格重建的缓存门面：只有在形变输入真正变化时才重建 1073 顶点，否则直接复用上一帧。
     *
     * 判定用「量化后的输入」而不是精确相等：phase / blinkT / idleEyeScale / gaze 都是逐帧微变的
     * 连续量，精确比较等于每帧都重建。量化步长取得足够小（呼吸位移误差 < 0.5px），视觉无差别，
     * 但站立待机时的重建次数能从 60 次/秒降到约 25 次/秒。
     */
    private void mesh(float f, float f2) {
        // 量化步长必须大于「一帧的相位增量」，否则待机时每帧都跨过一格、缓存永不命中：
        // phase 按 1.7 rad/s 推进，32ms 一帧增 0.0544 rad，故步长取 0.1（×10）；
        // 呼吸振幅 2.5dp，相位差 0.1 带来的位移误差约 0.25dp，肉眼不可见。
        int phaseQ = (int) (this.phase * 10.0f);
        int blinkQ = (int) (this.blinkT * 200.0f);
        int eyeQ = (int) (this.idleEyeScale * 200.0f);
        int gazeQ = (int) (this.gaze * 400.0f);
        if (meshValid && f == meshW && f2 == meshH && phaseQ == meshPhaseQ
                && blinkQ == meshBlinkQ && eyeQ == meshEyeQ && gazeQ == meshGazeQ
                && this.expression == meshExpr) {
            return;
        }
        buildMesh(f, f2, this.phase, this.blinkT, this.idleEyeScale, this.gaze);
        meshW = f;
        meshH = f2;
        meshPhaseQ = phaseQ;
        meshBlinkQ = blinkQ;
        meshEyeQ = eyeQ;
        meshGazeQ = gazeQ;
        meshExpr = this.expression;
        meshValid = true;
    }

    private void buildMesh(float f, float f2, float phaseQ, float blinkQ, float eyeQ, float gazeQ) {
        float sin = ((float) Math.sin(phaseQ)) * BREATH_AMP;
        float f3 = phaseQ;
        float f4 = 1.6f * f3;
        float f5 = 2.6f * f3;
        float f6 = f3 * 3.4f;
        float f7 = blinkQ;
        float sin2 = f7 < 0.0f ? 0.0f : (float) Math.sin(f7 * 3.141592653589793d);
        int i = this.expression;
        float min = Math.min(i == 1 ? 1.2f : i == 2 ? 0.74f : 1.0f, eyeQ);
        if (sin2 > 0.0f) {
            min = Math.min(min, 1.0f - (sin2 * BLINK_SQUASH));
        }
        float f8 = 1.0f - min;
        int i2 = 0;
        for (int i3 = 0; i3 <= MY; i3++) {
            float f9 = i3 / (float) MY;
            for (int i4 = 0; i4 <= MX; i4++) {
                float f10 = i4 / (float) MX;
                float f11 = 0.0f - ((1.0f - f9) * sin);
                float smoothstep = ((((((smoothstep(TAILR_RU, 0.44f, Math.abs(f10 - EYE_U)) * HAIR_AMP) * smoothstep(EYE_RU, 0.95f, f9)) * ((float) Math.sin(((4.2f * f9) + f4) + (1.4f * f10)))) + 0.0f) + ((falloff(f10, f9, TAILR_U, TAILR_V, TAILR_RU, TAILL_RU) * TAIL_AMP) * ((float) Math.sin(f5)))) - ((falloff(f10, f9, TAILL_U, TAILL_V, TAILL_RU, TAILL_RV) * 0.0195f) * ((float) Math.sin(f5 - TAILR_V)))) + (falloff(f10, f9, AHOGE_U, AHOGE_V, AHOGE_RU, AHOGE_RV) * AHOGE_AMP * ((float) Math.sin(f6)));
                float falloff = falloff(f10, f9, EYE_U, EYE_V, EYE_RU, EYE_RV);
                if (falloff > 0.0f) {
                    f11 -= ((f9 - EYE_V) * f8) * falloff;
                    smoothstep += gazeQ * GAZE_AMP * falloff;
                }
                float[] fArr = this.verts;
                int i5 = i2 + 1;
                fArr[i2] = (f10 + smoothstep) * f;
                i2 = i5 + 1;
                fArr[i5] = (f9 + f11) * f2;
            }
        }
    }
    @Override
    protected void onDraw(Canvas canvas) {
        float pivotLocalX;
        float pivotLocalY;
        Bitmap bitmap;
        int i;
        float f;
        int i2;
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        Bitmap current = current();
        if (width <= 0 || height <= 0 || current == null) {
            return;
        }
        int petWidth = petWidth();
        int fullHeight = fullHeight();
        // 【回退 v2.5】v2.4 曾在此强制按「界面最大比例」绘制精灵，导致人偶被调小后
        //   趴姿反而被放大 2 倍（用户报「框上面的人怎么也是最大模型的」），已撤销。
        //   趴姿与普通态同用当前缩放率；框宽也跟着取 petWidth()，两者边缘对齐。
        float sin = ((float) Math.sin(this.phase)) * this.bobAmp;
        float f2 = this.jumpT;
        float sin2 = f2 >= 0.0f ? ((float) Math.sin(f2 * 3.141592653589793d)) * this.jumpAmp : 0.0f;
        float f3 = fullHeight;
        mesh(petWidth, f3);
        if (this.peek) {
            pivotLocalX = width / 2.0f;
            pivotLocalY = (height - baseHeight()) + fullHeight;
        } else {
            pivotLocalX = pivotLocalX();
            pivotLocalY = pivotLocalY();
        }
        float f4 = pivotLocalY;
        float dp = (this.dragLift * dp(DRAG_TILT_MAX)) + this.idleLiftPx;
        float f5 = (this.dragLift * 0.05f) + 1.0f;
        float f6 = this.landT;
        if (f6 >= 0.0f) {
            bitmap = current;
            i = petWidth;
            f = 1.0f - (((float) Math.sin(f6 * 3.141592653589793d)) * AHOGE_RU);
        } else {
            bitmap = current;
            i = petWidth;
            f = 1.0f;
        }
        float f7 = f * f5 * this.idleScaleY;
        canvas.save();
        canvas.translate(pivotLocalX, ((sin + f4) - sin2) - dp);
        canvas.scale(f5, f7);
        canvas.rotate(this.lean);
        canvas.rotate(this.tilt + this.idleTilt);
        canvas.translate((-i) / 2.0f, -fullHeight);
        float f8 = pivotLocalX;
        canvas.drawBitmapMesh(bitmap, MX, MY, this.verts, 0, null, 0, this.bmpPaint);
        canvas.restore();
        drawEmote(canvas, f8, (f4 - f3) - dp);
        if (!this.bubble.hasContent() || this.bubble.height() <= 0) {
            return;
        }
        // 贴边偷看时窗口有约 0.68*人偶宽 在屏幕外，气泡要按「窗口在屏幕内的可见区」绘制，
        // 否则会有一半画到屏幕外（用户报的「趴着时对话框一半在外面」）。
        int[] loc = this.locBuf;
        getLocationOnScreen(loc);
        this.bubble.draw(canvas, dp(1.0f), loc[0], getResources().getDisplayMetrics().widthPixels);
    }
    private void drawEmote(Canvas canvas, float f, float f2) {
        if (this.expression == 0) {
            return;
        }
        long uptimeMillis = SystemClock.uptimeMillis() - this.exprStartAt;
        if (uptimeMillis >= 0) {
            long j = this.exprDuration;
            if (uptimeMillis > j) {
                return;
            }
            float f3 = uptimeMillis / (float) j;
            float f4 = f3 < TAILR_RU ? ((f3 / TAILR_RU) * 0.9f) + AHOGE_U : f3 < EYE_RU ? 1.2f - (((f3 - TAILR_RU) / TAILL_RV) * 0.2f) : 1.0f;
            int i = f3 < 0.65f ? 255 : (int) ((1.0f - ((f3 - 0.65f) / 0.35f)) * 255.0f);
            float f5 = (-dp(10.0f)) * f3;
            this.emotePaint.setColor(emoteColor(this.expression));
            this.emotePaint.setAlpha(Math.max(0, Math.min(255, i)));
            // 字号随人偶实时宽度走，保证「表情 : 人偶」比例恒定。
            this.emotePaint.setTextSize(petWidth() * EMOTE_SIZE_RATIO);
            float petWidth = f + (petWidth() * AHOGE_U);
            float dp = f2 + dp(6.0f) + f5;
            canvas.save();
            canvas.translate(petWidth, dp);
            canvas.scale(f4, f4);
            this.emotePaint.setStyle(Paint.Style.STROKE);
            this.emotePaint.setStrokeWidth(petWidth() * EMOTE_STROKE_RATIO);
            this.emotePaint.setColor(UiKit.CARD);
            this.emotePaint.setAlpha(Math.max(0, Math.min(255, i)));
            canvas.drawText(emoteGlyph(this.expression), 0.0f, 0.0f, this.emotePaint);
            this.emotePaint.setStyle(Paint.Style.FILL);
            this.emotePaint.setColor(emoteColor(this.expression));
            this.emotePaint.setAlpha(Math.max(0, Math.min(255, i)));
            canvas.drawText(emoteGlyph(this.expression), 0.0f, 0.0f, this.emotePaint);
            canvas.restore();
        }
    }
    // ---------- 以下为 PetAnimator 的转发薄壳，对外契约不变 ----------
    // 【v2.10.2】start/stop 同时清低功耗标志：attach / detach 时状态必须干净，
    //   否则下次挂载会带着上一轮的 lowPower=true 进去（虽然帧内自检能自愈，但没必要绕这一圈）。
    public void startAnim() { this.lowPower = false; this.anim.startAnim(); }
    public void stopAnim() { this.lowPower = false; this.anim.stopAnim(); }
    public void playJump() { this.anim.playJump(); }
    public void setTilt(float f) { this.anim.setTilt(f); }
    public void pokeIdle(float f) { this.anim.pokeIdle(f); }
    public void setExpression(int i, long j) { this.anim.setExpression(i, j); }
    public void clearExpression() { this.anim.clearExpression(); }
    public void setDragging(boolean z) { this.anim.setDragging(z); }
    public void setDragVelocity(float f) { this.anim.setDragVelocity(f); }
    public void lookAt(float f) { this.anim.lookAt(f); }
    public void setAffection(int i) { this.anim.setAffection(i); }
    public void tick() { this.anim.tick(); }
}
