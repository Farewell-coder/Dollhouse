package com.dollhouse.app;

import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import java.util.Random;

/**
 * 【职责】桌宠前台服务：持有悬浮窗、驱动动画与随机台词、常驻通知。
 *
 * 【交互】收到 MainActivity 的 START/STOP/REFRESH 动作；attachPet() 把 PetView 挂进 WindowManager；长按/点击手势转成聊天窗口与台词。
 *
 * 【坑】attachPet() 用 catch(Throwable) 吞掉所有异常然后 stopSelfSafely() —— 悬挂浮窗失败时会静默退出，排查「桌宠莫名消失」要看 logcat -b events 里的 am_foreground_service_stop，而不是找 crash 日志。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public class PetService extends Service {
    public static final String ACTION_REFRESH = "REFRESH";
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";
    /** 点一下人偶时随机说一句（原「定时自动冒台词」已按用户要求下线，改成点一下才说）。 */
    /**
     * 单击人偶时随机蹦一句（用户定案：单击 = 跳一下 + 随机说一句）。
     * 【坑】v2.4 删 chatter 时把台词入口一并删了，导致「点击没消息弹出来」；
     *   本轮在 PetActionRegistry 的 tap 动作里重新接线。
     */
    static final String[] LINES_TAP = {"你好呀～", "我一直都在哦。", "有什么想聊的吗？"};
    static final float SNAP_ZONE_RATIO = 0.25f;
    ChatWindow chatWindow;
    float curPivotX;
    float curPivotY;
    float downPivotX;
    float downPivotY;
    float downRawX;
    float downRawY;
    long lastMoveAt;
    float lastRawX;
    WindowManager.LayoutParams lp;
    int petTopY;
    PetView petView;
    SharedPreferences prefs;
    ValueAnimator snapAnim;
    int touchSlop;
    WindowManager wm;
    final Random random = new Random();
    final Handler ui = new Handler(Looper.getMainLooper());
    private PetWindowController win;
    boolean bubbleUp = false;
    boolean added = false;
    boolean peek = false;
    boolean edgePeek = false;
    /** 已应用到 PetView 的缩放系数；REFRESH 里用来判断「比例真的变了没」。 */
    float petScaleApplied = 0.0f;
    boolean edgeAtLeft = false;
    boolean petShown = true;
    long lastPlaceAt = 0;
    boolean dragging = false;
    final Runnable hideBubble = new Runnable() {        @Override
        public void run() {
            PetService.this.hideBubbleNow();
        }
    };
    final PetBus.Listener busListener = new PetBus.Listener() {        @Override
        public void onSay(final String str, final long j) {
            PetService.this.ui.post(new Runnable() {                @Override
                public void run() {
                    PetService.this.say(str, j);
                }
            });
        }
        @Override
        public void onAffection(final int i) {
            PetService.this.ui.post(new Runnable() {                @Override
                public void run() {
                    if (PetService.this.petView != null) {
                        PetService.this.petView.setAffection(i);
                    }
                }
            });
        }
    };
    final float[] geom = new float[4];
    /** 连击判定窗口：窗口内累计到 3 次才算三击（双击聊天已取消，窗口留宽一点好按）。 */
    static final long TAP_WINDOW_MS = 350;
    /**
     * 人偶与迷你输入框之间的间隙（dp）。
     * 【定案 v2.6】曾为负搭接 3dp（框顶盖住脚），用户要求「完整形态悬浮在框上面」，改正间隙。
     */
    static final float PET_HOVER_GAP_DP = 6.0f;
    /** 当前已连击次数，singleTap 到期时消费并清零。 */
    int tapCount = 0;
    /** 迷你输入框（三击召唤）与它的逻辑层；常驻复用，不反复建窗。 */
    PetTalkInput talkInput;
    PetTalk talk;
    /** 迷你聊天开启时人偶脚底的 y：整组（人偶 + 输入框）摆位的锚点，键盘上移也基于它。 */
    int talkFeetY = 0;
    /** 最近一次输入法高度，重排时沿用，避免被 0 冲掉。 */
    int talkIme = 0;
    /**
     * 迷你聊天开启那一刻的人偶水平中心（屏幕坐标）。
     * 【为何要固定】进 peek 时窗口是「人偶宽」，lp.x + lp.width/2 就是人偶视觉中心；
     *   摆完框后 lp.width 变成框宽，再拿 lp.x + lp.width/2 当基准会逐次漂移（框越走越偏）。
     *   故只在首次进入 peek 时记下来，之后一直以它为基准摆框。
     */
    int talkAnchorX = 0;
    /**
     * 本轮发送是否刚发生（一个点击周期内有效）。
     * 【为什么需要】发送后人偶立刻站起来（peek=false），同一轮触摸的 UP 落到 handleTouch
     *   时 peek 已为假，会被当成「非对话态点击」而误触单击台词。用本标志把这一下让给「看回复」。
     */
    boolean talkOnSend = false;
    /**
     * 迷你输入框当前是否应该存在。
     *
     * 【为何需要】layoutMiniTalk 末尾会「没显示就 show」，而发完消息后输入框已经被
     *   hideQuiet 收掉、紧接着又会重排一次（键盘高度归零），于是它会被重新拉出来 ——
     *   与「发完只留气泡看回复」的约定相矛盾。用本标志把「摆位」与「是否拉起输入框」分开。
     */
    boolean talkInputWanted = false;
    final Runnable singleTap = new Runnable() {        @Override
        public void run() {
            int n = PetService.this.tapCount;
            PetService.this.tapCount = 0;
            if (n >= 3) {
                PetActionRegistry.perform("triple_tap", PetService.this);
            } else if (n == 1) {
                PetActionRegistry.perform("tap", PetService.this);
            }
            // n == 2：双击聊天已取消，不产生任何动作。
        }
    };
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    @Override
    public void onCreate() {
        super.onCreate();
        this.prefs = PetPrefs.get(this);
        this.wm = (WindowManager) getSystemService("window");
        this.touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        this.win = new PetWindowController(this);
        this.chatWindow = new ChatWindow(this, new ChatWindow.Host() {            @Override
            public void onOpenFullScreen() {
                ChatWindow.openFullScreen(PetService.this);
            }
            @Override
            public void onClosed() {
                PetService.this.exitPeek();
            }
            @Override
            public void onGeometry(int i, int i2, int i3, int i4) {
                PetService.this.layoutPeek(i, i2, i3, i4);
            }
        });
    }
    @Override
    public int onStartCommand(Intent intent, int i, int i2) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // 用户主动关闭：置位标志，之后被划掉 / 被系统杀 / 重启，都不再自动拉起。
            PetPrefs.setUserStopped(this, true);
            PetNotifier.stopSafely(this);
            return 2;
        }
        if (ACTION_REFRESH.equals(action)) {
            updateTouchable();
            // 主题切换后刷新桌宠取色：颜色常量已被 ThemeManager 整体覆写，这里让画布重读。
            PetView pv = this.petView;
            if (pv != null) {
                pv.applyTheme();
                this.win.applyScale();
            }
            ChatWindow chatWindow = this.chatWindow;
            if (chatWindow != null) {
                chatWindow.refreshBackground();
            }
            // 【v2.10.2】顺带做一次屏幕尺寸自检：REFRESH 是 MainActivity 每次回到前台都会发的动作，
            // 拿它当「旋转后必然经过的一个点」用，比只等系统回调更稳。
            this.win.checkScreenChanged();
            return 1;
        }
        // 走到这里说明是用户主动启动（首页按钮 / 通知栏），清除主动停止标志。
        PetPrefs.setUserStopped(this, false);
        PetNotifier.startForegroundCompat(this);
        if (!this.added) {
            if (!Settings.canDrawOverlays(this)) {
                PetNotifier.stopSafely(this);
                return 2;
            }
            attachPet();
        }
        return 1;
    }
    /**
     * 【v2.10.2】屏幕尺寸变化回调（旋转 / 折叠屏展开 / 分屏）。
     *
     * 【为什么必须自己有】Manifest 里没有声明 configChanges，所以系统在配置变化时
     *   会重建 Activity 但不会重建本前台 Service —— 悬浮窗带着旧坐标继续挂着，
     *   竖屏存的 rest_fy(屏高*0.8=1920) 在横屏(屏高1080)下直接落到屏外，表现就是「人偶消失」。
     *   这里接一下回调，交给 win 重新摆位。
     *
     * 【注意】部分 ROM 在桌面（非本 App 前台）旋转时不一定派发这个回调，
     *   所以 PetView 的帧内自检 screenTick 是同一件事的第二道保险，两者幂等可重复调。
     */
    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        this.win.checkScreenChanged();
    }
    /**
     * 保活：用户在「最近任务」里把本卡片划掉时回调。
     * 只有当用户没有主动点过「关闭人偶」时才自动拉起，避免和用户的意愿打架。
     * 直接 startForegroundService，不抢前台、不重建任务栈。
     */
    @Override
    public void onTaskRemoved(Intent intent) {
        super.onTaskRemoved(intent);
        try {
            if (PetPrefs.userStopped(this)) {
                return;
            }
            Intent restart = new Intent(getApplicationContext(), PetService.class);
            restart.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(restart);
            } else {
                startService(restart);
            }
        } catch (Throwable ignored) {
            Logs.w("Dollhouse", "ignored", ignored);
        }
    }
    @Override
    public void onDestroy() {
        this.ui.removeCallbacksAndMessages(null);
        ValueAnimator valueAnimator = this.snapAnim;
        if (valueAnimator != null) {
            valueAnimator.cancel();
        }
        ChatWindow chatWindow = this.chatWindow;
        if (chatWindow != null) {
            // 销毁中：不要再回调宿主（宿主正在拆自己）。
            chatWindow.hide(false);
        }
        PetTalkInput input = this.talkInput;
        if (input != null) {
            input.release();
        }
        PetTalk petTalk = this.talk;
        if (petTalk != null) {
            petTalk.release();
        }
        detachPet();
        super.onDestroy();
    }
    // 悬浮窗类型：SDK>=26 用 APPLICATION_OVERLAY，否则用 PHONE。
    // 把 PetView 挂进 WindowManager；任何失败都会静默退出服务。
    // 从 WindowManager 摘掉 PetView 并复位状态。
    public void say(String str, long j) {
        if (this.petView == null || !this.added) {
            return;
        }
        this.ui.removeCallbacks(this.hideBubble);
        this.petView.showBubble(str);
        if (this.peek) {
            // peek 态：人偶是趴姿，窗口高由整组布局算，这里只标「有气泡」再重排。
            this.bubbleUp = true;
            layoutMiniTalk(this.talkIme);
        } else {
            expandBubble(this.petView.neededBubbleSpace());
        }
        this.ui.postDelayed(this.hideBubble, j);
    }
    /** 收气泡：peek 态只收气泡并重排整组（保住趴姿与输入框），非 peek 态沿用原逻辑。 */
    void hideBubbleNow() {
        PetView petView = this.petView;
        if (this.peek && petView != null) {
            petView.clearBubble();
            petView.setBubbleHeight(0);
            this.bubbleUp = false;
            layoutMiniTalk(this.talkIme);
            return;
        }
        collapseBubble();
    }
    /** 常驻气泡：显示后不排自动消失，相位由 PetTalk 自己管。 */
    void saySticky(String str) {
        if (this.petView == null || !this.added) {
            return;
        }
        this.ui.removeCallbacks(this.hideBubble);
        this.petView.showBubble(str);
        if (this.peek) {
            this.bubbleUp = true;
            layoutMiniTalk(this.talkIme);
        } else {
            expandBubble(this.petView.neededBubbleSpace());
        }
    }
    public boolean handleTouch(MotionEvent motionEvent) {
        // 【坑】peek 态不能一律早退：否则下面的拖动分支永远不可达，
        //   输入框开着时想拖走人偶会被当成「点一下」把聊天收掉。
        //   现在 peek 态照样走拖拽判定，只有「没拖动就松手」才按点击处理（推进气泡相位）。
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 0) {
            this.downRawX = motionEvent.getRawX();
            this.downRawY = motionEvent.getRawY();
            this.lastRawX = this.downRawX;
            this.lastMoveAt = System.currentTimeMillis();
            this.downPivotX = this.lp.x + this.petView.pivotLocalX();
            float pivotLocalY = this.lp.y + this.petView.pivotLocalY();
            this.downPivotY = pivotLocalY;
            this.curPivotX = this.downPivotX;
            this.curPivotY = pivotLocalY;
            this.dragging = false;
            ValueAnimator valueAnimator = this.snapAnim;
            if (valueAnimator != null) {
                valueAnimator.cancel();
            }
            return true;
        }
        if (actionMasked != 1) {
            if (actionMasked == 2) {
                float rawX = motionEvent.getRawX() - this.downRawX;
                float rawY = motionEvent.getRawY() - this.downRawY;
                long currentTimeMillis = System.currentTimeMillis();
                float rawX2 = (motionEvent.getRawX() - this.lastRawX) / Math.max(0.008f, (currentTimeMillis - this.lastMoveAt) / 1000.0f);
                this.lastRawX = motionEvent.getRawX();
                this.lastMoveAt = currentTimeMillis;
                if (!this.dragging && Math.hypot(rawX, rawY) > this.touchSlop) {
                    this.dragging = true;
                    // 清掉在途的连击判定：否则「点一下后 350ms 内开始拖动」会在拖动途中多触发一次 tap。
                    this.ui.removeCallbacks(this.singleTap);
                    this.tapCount = 0;
                    // 拖动人偶时先摘掉迷你聊天（约定：不让框跟着拖动变成半截），
                    // 但不动人偶位置 —— 紧接着的拖动流程会接管摆放。
                    if (this.peek) {
                        abortMiniTalkForDrag();
                    }
                    collapseBubble();
                    exitEdgePeek();
                    this.petView.setDragging(true);
                }
                if (this.dragging) {
                    this.curPivotX = this.downPivotX + rawX;
                    this.curPivotY = this.downPivotY + rawY;
                    applyDragPlacement();
                    this.petView.setDragVelocity(rawX2);
                    this.petView.lookAt(Math.max(-1.0f, Math.min(1.0f, rawX2 / 900.0f)));
                }
                return true;
            }
            if (actionMasked != 3) {
                return false;
            }
        }
        if (this.dragging) {
            this.petView.setDragging(false);
            placeByPivot(Math.round(this.curPivotX), Math.round(this.curPivotY));
            settle();
        } else if (motionEvent.getActionMasked() == 1) {
            if (this.peek && !this.talkOnSend) {
                // 对话打开中（还没发送）：点人偶 = 收框退场，不走连击计数。
                onPetTapWhileTalking();
            } else {
                // 非对话态，或已发送（人偶站起来了）看回复：走统一点击入口。
                // 【修复】旧代码在非 peek 态无条件走 onTap() 连击计数，
                //   而回复到位后人偶已站起来（peek=false），点它只计数、
                //   气泡永远不翻页也不收掉（用户报「点它没反应」）。
                onPetClicked();
            }
            this.talkOnSend = false;
        }
        this.dragging = false;
        return true;
    }
    // 按锚点把窗口摆到指定坐标（含边界约束）。
    // 记住当前锚点位置，供下次启动恢复。
    void openPanel() {
        Intent intent = new Intent(this, (Class<?>) MainActivity.class);
        intent.setFlags(335544320);
        try {
            startActivity(intent);
            return;
        } catch (Throwable unused) {
        }
        // ColorOS 等系统会拦截后台服务直接 startActivity；改用 PendingIntent
        // 走系统允许的通道再试一次，失败则静默放弃（长按桌宠仍可重试）。
        try {
            PendingIntent pending = PendingIntent.getActivity(this, 2, intent,
                    201326592 | 67108864);
            pending.send();
        } catch (Throwable ignored) {
            Logs.w("Dollhouse", "ignored", ignored);
        }
    }
    /** 让桌宠原地跳一下（单/双击的共用动作）。 */
    void petJump() {
        PetView petView = this.petView;
        if (petView != null) {
            petView.playJump();
        }
    }
    /**
     * 【已废弃】旧的全屏聊天入口。
     * 【改动】人偶不再把人送进全屏聊天页 —— 三击改成召唤迷你输入框（openMiniTalk）。
     *   全屏页仍可从首页「打开聊天」进入，那条路径与本类无关。
     */
    void toggleChat() {
        openMiniTalk();
    }
    /** 三击召唤：挂出迷你输入框，人偶趴到框顶。 */
    void openMiniTalk() {
        ChatWindow chatWindow = this.chatWindow;
        if (chatWindow != null && chatWindow.isShowing()) {
            // 老悬浮聊天窗还开着就先收掉：两套窗同时存在会让 peek 语义打架。
            chatWindow.hide();
        }
        if (this.petView == null || this.lp == null || !this.added) {
            return;
        }
        ensureMiniTalk();
        this.talkInputWanted = true;
        layoutMiniTalk(this.talkIme);
    }
    /** 首次使用时组装逻辑层与输入框（之后常驻复用，不反复建窗）。 */
    private void ensureMiniTalk() {
        if (this.talk == null) {
            this.talk = new PetTalk(this, new PetTalk.Host() {
                @Override
                public void saySticky(String str) {
                    PetService.this.saySticky(str);
                }
                @Override
                public void sayTemp(String str, long j) {
                    PetService.this.say(str, j);
                }
                @Override
                public void clearBubble() {
                    PetService.this.hideBubbleNow();
                }
                @Override
                public int showBubblePaged(String str, int i) {
                    PetView petView = PetService.this.petView;
                    if (petView == null) {
                        return 0;
                    }
                    int pages = petView.showBubblePaged(str, i);
                    // 【v2.7】分页后气泡高度跟着变（一片最多 PAGE_MAX_LINES 行）：
                    //   必须重排一次，否则首屏还按「思考中…」的旧窗高画，多出一行被裁。
                    if (PetService.this.peek && PetService.this.bubbleUp) {
                        PetService.this.layoutMiniTalk(PetService.this.talkIme);
                    } else {
                        PetService.this.expandBubble(petView.neededBubbleSpace());
                    }
                    return pages;
                }
                @Override
                public String bubblePage(int i) {
                    PetView petView = PetService.this.petView;
                    return petView == null ? null : petView.bubblePage(i);
                }
                @Override
                public void setBubbleLines(int i) {
                    PetView petView = PetService.this.petView;
                    if (petView == null) {
                        return;
                    }
                    petView.setBubbleMaxLines(i);
                    // 行数变了气泡高度就变，必须重排整组，否则窗高对不上文字。
                    if (PetService.this.peek && PetService.this.bubbleUp) {
                        PetService.this.layoutMiniTalk(PetService.this.talkIme);
                    }
                }
                @Override
                public void onBusy(boolean z) {
                    if (PetService.this.talkInput != null) {
                        PetService.this.talkInput.setBusy(z);
                    }
                }
                @Override
                public boolean bubbleHasOverflow() {
                    return PetService.this.petView != null && PetService.this.petView.bubbleHasOverflow();
                }
            });
        }
        if (this.talkInput == null) {
            this.talkInput = new PetTalkInput(this, new PetTalkInput.Host() {
                @Override
                public void onClosed() {
                    // 收起输入框：人偶站起来（含气泡复位）。
                    PetService.this.exitPeek();
                }
                @Override
                public void onSend(String str) {
                    // 【约定·用户定案】发完消息：收输入框 + 人偶从趴姿站回原位，
                    //   回复走头顶普通气泡展示。（旧行为只重排不退出趴姿，
                    //   于是「框收了、人偶还是半个头」。）
                    // 【顺序要紧】exitPeek() 内部会 clearBubble()，必须先退趴姿再发请求，
                    //   反过来会把刚点亮的「思考中…」一起清掉。
                    PetService.this.talkIme = 0;
                    PetService.this.talkInputWanted = false;
                    PetService.this.talkOnSend = true;
                    PetService.this.exitPeek();
                    if (PetService.this.talk != null) {
                        PetService.this.talk.talk(str);
                    }
                }
                @Override
                public void onIme(int i) {
                    PetService.this.talkIme = i;
                    if (PetService.this.peek) {
                        PetService.this.layoutMiniTalk(i);
                    }
                }
            });
        }
    }
    /**
     * 迷你聊天的整组摆位：人偶完整悬浮在框上方，输入框紧贴人偶下方。
     * 【几何 v2.7】框顶 = 人偶脚底 + PET_HOVER_GAP_DP；人偶窗口 = 完整身高 + 气泡高；
     *   输入框 = 独立 overlay。整组以人偶脚底为锥点，不得越出屏幕可用区。
     * 【键盘】可用底边扣掉输入法高度，整组不够放就整体上移，人偶与框不会分家。
     */
    private void layoutMiniTalk(int imeBottom) {
        PetView petView = this.petView;
        if (petView == null || this.lp == null || !this.added) {
            return;
        }
        if (!this.peek) {
            this.talkFeetY = this.lp.y + this.lp.height;
            // 【v2.7】此刻 lp.width 还是人偶宽、人偶画在窗内居中，中心就是人偶视觉中心。
            this.talkAnchorX = this.lp.x + Math.max(1, this.lp.width) / 2;
            this.peek = true;
            this.ui.removeCallbacks(this.hideBubble);
            petView.clearBubble();
            petView.setBubbleHeight(0);
            this.bubbleUp = false;
            petView.setEdgePeek(false, false);
            petView.setPeek(true);
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // 【定案 v2.6】不再「搭接」：人偶完整悬浮在输入框上方，中间留一条固定间隙。
        //   旧值 3dp 负搭接（框顶高于脚3dp）会让框盖住人偶下沿；
        //   用户要求「小人以完整形态悬浮在对话框上面」，故改为正间隙。
        int gap = Math.round(PET_HOVER_GAP_DP * dm.density);
        int barH = UiKit.dp(this, PetTalkInput.HEIGHT_DP);
        int edge = UiKit.dp(this, 8.0f);
        // 【定案 v2.5】框宽 = 「界面最大比例」时的宽度（petMaxWidth，默认 225dp），
        //   与人偶比例无关 —— 人偶调小后输入框照样宽，好打字（用户指正）。
        //   【但】人偶本身不跟着放大：onDraw 按当前比例绘制，在窗内水平居中，
        //   两侧留出透明带（用户要的是「框保持最大比例、小人不用保持」）。
        //   趴姿高度取 perchHeight()（当前比例），与绘制共用同一基准，头才贴住框顶。
        int width = Math.max(1, petView.petMaxWidth());
        // 【居中 v2.7】框以「人偶所在位置」为基准：每次重排都把框对人偶中心居中，
        //   并双侧夹回屏内（不越出屏幕）。锚点在首次进 peek 时锁死，避免逐次漂移。
        this.lp.x = Math.max(0, Math.min(dm.widthPixels - width, this.talkAnchorX - width / 2));
        // 【人偶高度】取完整身高：peek 态人偶整只悬在框上（v2.6 定案），
        //   与 onDraw 的绘制基准一致，脚底正好落在窗口底边。
        //   【与框宽基准不同是有意的】框宽取最大比例（好打字），高度取当前比例（人偶多大画多大）。
        int perch = petView.perchHeight();
        if (perch <= 0) {
            perch = Math.max(1, petView.fullHeight());
        }
        // 【坑】先夹 x 再量气泡：贴边态下 lp.x 可能为负，直接拿去排字会算窄，
        //   等后面夹回屏内就多出一截空白（夹 x 已提到上面，紧跟 width 计算）。
        int bubble = 0;
        if (this.bubbleUp) {
            petView.relayoutBubble(width, this.lp.x);
            bubble = petView.neededBubbleSpaceFor(width, this.lp.x);
            petView.setBubbleHeight(bubble);
        }
        int availBottom = dm.heightPixels - Math.max(0, imeBottom);
        // 【v2.7】整组「人偶 + 框」以人偶脚底为锥点向上生长，不得越出可用底边。
        //   输入框底边上限 = 可用底边 - 留白；据此反推 talkY 的上限。
        int maxTalkY = availBottom - edge - barH;
        int baseTalkY = this.talkFeetY + gap;
        if (baseTalkY > maxTalkY) {
            baseTalkY = maxTalkY;
        }
        int bottom = baseTalkY - gap;
        int minTop = Math.round(dm.density * 4.0f);
        // 人偶太高、顶部要越出屏幕：把人偶锥回屏内（头部小幅让位），
        //   保「框在屏内可见 + 两者间隙不变」。
        if (bottom - (perch + bubble) < minTop) {
            bottom = minTop + perch + bubble;
            if (bottom + gap > maxTalkY) {
                bottom = maxTalkY - gap;
            }
        }
        int talkY = bottom + gap;
        int top = bottom - (perch + bubble);
        if (top < 0) {
            top = 0;
        }
        this.petTopY = top;
        this.lp.width = width;
        this.lp.height = perch + bubble;
        this.lp.y = top;
        safeUpdate();
        PetTalkInput input = this.talkInput;
        if (input == null) {
            return;
        }
        if (!this.talkInputWanted) {
            // 发完消息 / 退出趴姿：只摆人偶与气泡，不把输入框再拉出来。
            if (input.isShowing()) {
                input.hideQuiet();
            }
            return;
        }
        if (input.isShowing()) {
            input.moveTo(this.lp.x, talkY, width);
        } else {
            input.show(this.lp.x, talkY, width);
        }
    }
    /** peek（迷你聊天开着）时点人偶：先推进气泡相位，没有气泡可推再收起输入框。 */
    private void onPetTapWhileTalking() {
        PetTalk petTalk = this.talk;
        if (petTalk != null && petTalk.onBubbleTap()) {
            return;
        }
        // 气泡没有可推进的相位：整组退场（收框 + 人偶站起）。
        this.talkInputWanted = false;
        PetTalkInput input = this.talkInput;
        if (input != null) {
            input.hideQuiet();
        }
        exitPeek();
    }
    /** 拖动人偶：先把迷你聊天整组摘掉（人偶照样能被拖走）。 */
    private void abortMiniTalkForDrag() {
        if (!this.peek) {
            return;
        }
        this.peek = false;
        this.talkInputWanted = false;
        PetTalkInput input = this.talkInput;
        if (input != null && input.isShowing()) {
            // 【坑】必须用 hideQuiet 而不是 release：release 会把 root / input 置空，
            //   但 lpx 里登记的窗口仍挂在 WindowManager 上 —— 之后 show() 会再 addView，
            //   同一个 View 被 add 两次，抛 IllegalStateException（try/catch 吞掉后输入框从此拉不起来）。
            input.hideQuiet();
        }
        PetView petView = this.petView;
        if (petView != null) {
            petView.clearBubble();
            petView.setBubbleHeight(0);
            petView.setPeek(false);
        }
        this.bubbleUp = false;
        this.talkIme = 0;
    }
    /**
     * 连击计数：窗口内累计次数，停手 TAP_WINDOW_MS 后统一派发。
     * 【契约】1 击 = 原地跳；2 击 = 无动作（双击聊天已取消）；3 击 = 召唤迷你输入框。
     */
    /**
     * 单击人偶的统一点击入口（用户定案的四态）。
     *
     * 【语义】没消息→跳一下 + 随机说；有回复→翻到下一片；最后一片→收掉；思考中→不打断。
     * 【延迟】多一下点击不会被吞掉：单击动作延后一轮连击窗口再执行，
     *   期间若有第二、三下点击会自行取消（与原来 tap/triple_tap 的判别方式一致）。
     */
    void onPetClicked() {
        PetTalk petTalk = this.talk;
        // 1) 有气泡在播：推进分页（思考中内部直接返回 true，不打断）。
        if (petTalk != null && petTalk.onBubbleTap()) {
            return;
        }
        // 2) 没气泡：走原来的连击计数（单击跳 + 说、三击召框）。
        onTap();
    }
    private void onTap() {
        this.tapCount++;
        this.ui.removeCallbacks(this.singleTap);
        this.ui.postDelayed(this.singleTap, TAP_WINDOW_MS);
    }
    // ---------- 以下为 PetWindowController 的转发薄壳，对外契约不变 ----------
    int overlayType() { return this.win.overlayType(); }
    void attachPet() { this.win.attachPet(); }
    void detachPet() { this.win.detachPet(); }
    void expandBubble(int i) { this.win.expandBubble(i); }
    public void collapseBubble() { this.win.collapseBubble(); }
    public void safeUpdate() { this.win.safeUpdate(); }
    void placeByPivot(int i, int i2) { this.win.placeByPivot(i, i2); }
    void rememberPivot() { this.win.rememberPivot(); }
    int clampFeetY(int i, int i2, int i3) { return this.win.clampFeetY(i, i2, i3); }
    void settle() { this.win.settle(); }
    void snapToEdge(boolean z, int i) { this.win.snapToEdge(z, i); }
    void enterEdgePeek(boolean z) { this.win.enterEdgePeek(z); }
    void exitEdgePeek() { this.win.exitEdgePeek(); }
    public void layoutPeek(int i, int i2, int i3, int i4) { this.win.layoutPeek(i, i2, i3, i4); }
    void updateTouchable() { this.win.updateTouchable(); }
    void setPetShown(boolean z) { this.win.setPetShown(z); }
    /**
     * 退出趴姿：把 host.peek 清掉再委托 PetWindowController 复位。
     * 【为什么覆写】迷你聊天直接改 peek 而不走 layoutPeek，win 只读 host.peek，
     *   所以必须由这里同步清位，否则 win.exitPeek() 会因 host.peek 仍为 true 而正常复位，
     *   但 applyScale 之类读 host.peek 的分支会误判成「聊天框还开着」。
     */
    public void exitPeek() {
        this.win.exitPeek();
        this.peek = false;
        this.talkIme = 0;
        this.talkInputWanted = false;
        // 【坑】必须连输入框一起静默收掉：applyScale 等路径会直接调本方法，
        //   若只复位趴姿，输入框 overlay 会孤零零挂在屏幕上直到 15 秒自愈。
        PetTalkInput input = this.talkInput;
        if (input != null && input.isShowing()) {
            input.hideQuiet();
        }
        this.bubbleUp = false;
    }
    void applyScale() { this.win.applyScale(); }
    void applyDragPlacement() { this.win.applyDragPlacement(); }
}
