package com.dollhouse.app;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

/**
 * 【职责】桌宠悬浮窗的挂载与几何：attach/detach、按锚点摆位、贴边吸附动画、上滑偷看（peek）、气泡增高。
 *
 * 【入口】由 PetService 构造时创建（new PetWindowController(this)）；
 *         PetService 的 onStartCommand / onDestroy / handleTouch 通过同名转发壳调进来。
 *
 * 【交互】窗口状态字段（lp / petView / wm / prefs / added / peek / edgePeek / snapAnim 等）
 *         仍由 PetService 持有（包级可见），本类只读写不持有——这样 handleTouch / onTap / say
 *         这些留在 Service 里的逻辑一行都不用改；ui / safeUpdate 全部回调 host。
 *
 * 【扩展】新增悬浮窗状态：在 PetService 加包级字段 + 在本类加设置它的方法；
 *         新增贴边策略：只改 snapToEdge / settle 两处。
 *
 * 【坑】prefs 的 rest_px / rest_fy / edge_side 三个键是历史键名，改名会让用户位置丢失；
 *       snapToEdge 里的 ValueAnimator 必须存在 host.snapAnim 上，否则 onDestroy 取消不掉会泄漏。
 */
final class PetWindowController {
    private final PetService host;
    /** 【v2.10.2】上一次已知的屏幕宽高（-1 = 尚未记录）。旋转 / 折叠屏 / 分屏靠它察觉。 */
    private int screenW = -1;
    private int screenH = -1;
    /**
     * 聊天窗顶部让位里，除人头高外额外的余量。
     * 【必须与 ChatWindow.topReserve 的注释口径一致】原先是两处各写一个 16.0f 的字面量，
     * 改一处漏一处就会让人偶被聊天窗盖住；这里收口成常量，值的语义是「头肩高 + 16dp 不互相压」。
     */
    private static final float TOP_RESERVE_EXTRA_DP = 16.0f;

    PetWindowController(PetService host) {
        this.host = host;
    }

    int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
    }

    /**
     * 应用新的人偶缩放：改 PetView 系数后必须重算窗口宽高并重摆，否则只变画布不变窗口。
     *
     * 【短路】REFRESH 同时被换主题 / 拖背景透明度复用，那两处不该动几何；
     *         比例没变就直接返回，避免误把贴边态解除。
     * 【聊天窗】聊天窗开着时桌宠处于 peek（只露头搭在框顶）；此时缩放必须重新 layoutPeek，
     *         若退回全身会被上层聊天框整个挡住（用户报的「聊天框挡住人物」）。
     * 【顺序】非 peek 路径：先用旧系数取屏幕锚点 → 退出贴边态 → 改系数 → 按新宽度回夹 → 重摆。
     *         pivotLocalX/Y 依赖 petWidth()，系数必须在取锚点之后再改，否则锚点漂移。
     * 【前置清理】贴边吸附动画会持续写 lp.x，必须先取消，否则动画用旧区间覆盖缩放结果。
     * 【气泡】气泡态下 lp.height 含气泡高度，须先收起再重摆，摆完按新宽度重开，避免气泡错位。
     */
    void applyScale() {
        if (host.petView == null || host.lp == null || !host.added) {
            return;
        }
        float target = PetPrefs.petScale(host) / 100.0f;
        if (Math.abs(target - host.petScaleApplied) < 1.0E-4f) {
            return;
        }
        host.petScaleApplied = target;
        ValueAnimator valueAnimator = host.snapAnim;
        if (valueAnimator != null && valueAnimator.isRunning()) {
            valueAnimator.cancel();
        }
        // peek 态：聊天窗开着就重新贴回框顶；窗已不在才退回全身。
        if (host.peek) {
            host.petView.setBubbleHeight(0);
            host.bubbleUp = false;
            host.petView.setScaleFactor(target);
            int[] g = host.chatWindow == null ? null : host.chatWindow.currentGeometry();
            if (g != null) {
                // 输入法弹出时聊天窗按 topReserve 让位；它含 perchHeight()，缩放后必须跟着刷新。
                host.chatWindow.setTopReserve(host.petView.perchHeight()
                        + Math.round(host.getResources().getDisplayMetrics().density * TOP_RESERVE_EXTRA_DP));
                layoutPeek(g[0], g[1], g[2], g[3]);
                return;
            }
            exitPeek();
            return;
        }
        boolean wasBubbleUp = host.bubbleUp;
        // 用当前窗口里实际占用的气泡高度，而不是写死的 bubbleSpace()，重开后气泡才不会跳大小。
        int bubbleSpace = wasBubbleUp
                ? Math.max(0, host.lp.height - host.petView.baseHeight())
                : host.petView.bubbleSpace();
        if (wasBubbleUp) {
            host.petView.setBubbleHeight(0);
            host.bubbleUp = false;
        }
        float pivotX = host.lp.x + host.petView.pivotLocalX();
        float pivotY = host.lp.y + host.petView.pivotLocalY();
        if (host.edgePeek) {
            exitEdgePeek();
        }
        if (host.petView == null || host.lp == null || !host.added) {
            return;
        }
        host.petView.setScaleFactor(target);
        // 放大后靠边会顶出屏幕：按新宽度把锚点夹回可见区（沿用 attachPet 的同款算法）。
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int half = host.petView.petWidth() / 2;
        if (!host.edgePeek) {
            pivotX = Math.max(half, Math.min(screenW - half, pivotX));
        }
        host.placeByPivot(Math.round(pivotX), Math.round(pivotY));
        host.rememberPivot();
        if (wasBubbleUp) {
            expandBubble(bubbleSpace);
        }
    }

    void attachPet() {
        host.petView = new PetView(host);
        host.petScaleApplied = PetPrefs.petScale(host) / 100.0f;
        host.petView.setScaleFactor(host.petScaleApplied);
        host.petView.setSprite(BitmapFactory.decodeResource(host.getResources(), R.drawable.pet_front));
        host.petView.setAffection(PetPrefs.affection(host));
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(host.petView.windowWidth(), host.petView.petHeight(), host.overlayType(), 776, -3);
        host.lp = layoutParams;
        layoutParams.gravity = 8388659;
        int i = host.getResources().getDisplayMetrics().widthPixels;
        int i2 = host.getResources().getDisplayMetrics().heightPixels;
        int round = Math.round(host.getResources().getDisplayMetrics().density * 8.0f);
        int petWidth = host.petView.petWidth() / 2;
        // 【v2.10.2】比例键优先：横屏下重启服务 / 重挂窗口都能落到屏内，像素键只作老档兜底。
        int[] rest = restorePivot();
        int i3 = rest != null ? rest[0] : (i - round) - petWidth;
        int i4 = rest != null ? rest[1] : Math.round(i2 * 0.8f);
        int i5 = host.prefs.getInt("edge_side", -1);
        if (i5 >= 0) {
            host.enterEdgePeek(i5 == 0);
        } else {
            i3 = Math.max(petWidth, Math.min(i - petWidth, i3));
        }
        host.placeByPivot(i3, host.clampFeetY(i4, i2, round));
        host.petView.setOnTouchListener(new View.OnTouchListener() {            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                return host.handleTouch(motionEvent);
            }
        });
        try {
            host.wm.addView(host.petView, host.lp);
            host.added = true;
            // 【v2.10.2】把屏幕尺寸自检注入帧循环：系统回调不一定来（桌面旋转），
            // 但帧一直在跑，所以拿它当最可靠的那道保险。帧循环里只做一次宽高比较，开销可忽略。
            host.petView.screenTick = new Runnable() {                @Override
                public void run() {
                    PetWindowController.this.checkScreenChanged();
                }
            };
            host.petView.startAnim();
            PetBus.register(host.busListener);
            host.updateTouchable();
            // 【修 v0.0.1】启动时不再自动说话：气泡只在用户点击人偶后才出现。
            //   旧实现 addView 后立即随机说一句，此时窗口位置/尺寸尚未稳定，
            //   展开气泡会与吸附动画抢 lp，造成串字/溢出/穿模（用户报的贴边吸附问题）。
        } catch (Throwable unused) {
            host.added = false;
            PetNotifier.stopSafely(host);
        }
    }

    void detachPet() {
        PetBus.unregister(host.busListener);
        host.ui.removeCallbacks(host.hideBubble);
        PetView petView = host.petView;
        if (petView != null) {
            petView.screenTick = null;
            petView.stopAnim();
            if (host.added) {
                try {
                    host.wm.removeView(host.petView);
                } catch (Throwable unused) {
                }
            }
            host.petView = null;
        }
        host.added = false;
    }

    /**
     * 【v2.10.2】屏幕尺寸自检：宽高变了就按新屏幕把窗口复位回屏内。
     *
     * 【调用方】PetService.onConfigurationChanged / PetService 的 REFRESH 分支 /
     *           PetView 的帧内自检（screenTick）。三路都可能先到，方法本身幂等，重复调用无副作用。
     * 【为什么要有帧内自检这一路】个别 ROM（含 ColorOS）不给前台 Service 派发配置变化，
     *           只靠 onConfigurationChanged 会漏；帧循环最快 250ms 一轮，旋转后一秒内必定自愈。
     */
    void checkScreenChanged() {
        if (host.petView == null || host.lp == null || !host.added) {
            return;
        }
        int w = host.getResources().getDisplayMetrics().widthPixels;
        int h = host.getResources().getDisplayMetrics().heightPixels;
        if (w == this.screenW && h == this.screenH) {
            return;
        }
        this.screenW = w;
        this.screenH = h;
        relayoutForScreen();
        // 聊天悬浮窗的位置也是按旧屏幕存的，同步夹回来，否则旋转后聊天框会落到屏外。
        ChatWindow chatWindow = host.chatWindow;
        if (chatWindow != null) {
            chatWindow.onScreenChanged();
        }
    }

    /**
     * 【v2.10.2】按当前屏幕复位悬浮窗。
     *
     * 【为什么必须做】竖屏存的 rest_fy = 屏高 × 0.8 = 1920，而横屏屏高只有 1080 ——
     *   人偶脚底直接落到屏幕外，用户看到的现象就是「横竖屏切换后人偶消失」。
     *   同理 lp.x 在宽高互换后可能越出右边界、贴边位置也会失准（用户说的「横屏别扭」）。
     *
     * 【顺序】先取消在跑的贴边动画（ValueAnimator 会持续写 lp.x，不取消会被它覆盖回去）。
     */
    private void relayoutForScreen() {
        ValueAnimator anim = host.snapAnim;
        if (anim != null && anim.isRunning()) {
            anim.cancel();
        }
        // 聊天窗开着时人是趴姿、窗口跟着聊天窗走：退掉趴姿最省事，
        // 聊天窗自己会按新屏幕重算并重新通知几何（见 ChatWindow.onScreenChanged）。
        if (host.peek) {
            host.exitPeek();
            return;
        }
        int[] pivot = restorePivot();
        if (pivot == null) {
            return;
        }
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        int edge = Math.round(host.getResources().getDisplayMetrics().density * 8.0f);
        if (host.edgePeek) {
            // 贴边态：贴边位置必须按新宽度重算（同 snapToEdge 的算法），不能沿用旧 x。
            int petWidth = host.petView.petWidth();
            int inset = host.petView.edgeInsetPx();
            int wPivot = host.edgeAtLeft
                    ? (edge - inset) + (petWidth / 2)
                    : ((screenW - edge) + inset) - (petWidth / 2);
            host.placeByPivot(wPivot, host.clampFeetY(pivot[1], screenH, edge));
            host.rememberPivot();
            return;
        }
        int half = host.petView.petWidth() / 2;
        int x = Math.max(half, Math.min(screenW - half, pivot[0]));
        host.placeByPivot(x, host.clampFeetY(pivot[1], screenH, edge));
        host.rememberPivot();
    }

    /**
     * 【v2.10.2】还原人偶锚点 {pivotX, pivotY}；没有任何可用记录时返回 null（调用方走默认值）。
     *
     * 【为什么加比例键】像素键在竖屏单尺寸下够用，但屏幕一旋转（宽高互换）就全部失准：
     *   竖屏存下的 1920 是「屏高的 80%」，到横屏就变成了 178% —— 人偶被摆到屏幕外。
     *   所以优先用比例（占屏宽 / 屏高的百分比）换算；读不到比例键才回退像素键，
     *   老档的位置因此完全不受影响（首次启动会被 rememberPivot 自动补上比例键）。
     */
    private int[] restorePivot() {
        SharedPreferences p = host.prefs;
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        float rx = p.getFloat("rest_rx", -1.0f);
        float ry = p.getFloat("rest_ry", -1.0f);
        if (rx >= 0.0f && ry >= 0.0f) {
            return new int[]{Math.round(rx * screenW), Math.round(ry * screenH)};
        }
        if (p.contains("rest_px") || p.contains("rest_fy")) {
            return new int[]{p.getInt("rest_px", screenW / 2),
                    p.getInt("rest_fy", Math.round(screenH * 0.8f))};
        }
        return null;
    }

    void expandBubble(int i) {
        PetView petView;
        if (host.lp == null || (petView = host.petView) == null) {
            return;
        }
        if (i <= 0) {
            i = petView.bubbleSpace();
        }
        // 贴边偷看时窗口有一大截在屏幕外，气泡按窗口宽排版会被切掉一半；
        // 气泡期间把窗口临时拉进屏内（人偶跟着进屏抬头说话），收起时再由 collapseBubble 还原。
        // 【动画】贴边吸附动画会持续写 lp.x，若气泡恰在动画期间弹出会被重新拖出屏外，必须先取消。
        // 【守卫】peek（聊天框顶）时窗口本就居中完整可见，且 edgePeek 只是残留标记，
        //         此时若按贴边分支摆位，气泡收起后人偶会横向跳到屏幕边缘。
        if (host.edgePeek && !host.peek) {
            ValueAnimator running = host.snapAnim;
            if (running != null && running.isRunning()) {
                running.cancel();
            }
            int screenW = host.getResources().getDisplayMetrics().widthPixels;
            host.lp.x = Math.max(0, Math.min(Math.max(0, screenW - host.lp.width), host.lp.x));
        }
        // 顺序要紧：窗口 x 变了以后可见区才确定，必须先重排、再按重排结果定高，
        // 否则会沿用「按屏外窄宽排出的 3 行高度」，气泡比文字高一截。
        host.petView.relayoutBubble(host.lp.width, host.lp.x);
        i = host.petView.neededBubbleSpaceFor(host.lp.width, host.lp.x);
        host.petView.setBubbleHeight(i);
        host.lp.height = host.petView.baseHeight() + i;
        host.lp.y = host.petTopY - i;
        host.bubbleUp = true;
        host.safeUpdate();
    }

    /**
     * 贴边偷看时的窗口 x（与 snapToEdge 同款算法）。
     * 【用途】气泡收起后把窗口还原回贴边位置，否则人偶会一直停在屏内。
     */
    private int edgePeekX() {
        if (host.petView == null) {
            return host.lp == null ? 0 : host.lp.x;
        }
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int round = Math.round(host.getResources().getDisplayMetrics().density * 8.0f);
        int inset = host.petView.edgeInsetPx();
        int petWidth = host.petView.petWidth();
        int pivot = host.edgeAtLeft
                ? (round - inset) + (petWidth / 2)
                : ((screenW - round) + inset) - (petWidth / 2);
        return Math.round(pivot - host.petView.pivotLocalX());
    }

    public void collapseBubble() {
        PetView petView;
        if (host.lp == null || (petView = host.petView) == null || !host.bubbleUp) {
            PetView petView2 = host.petView;
            if (petView2 != null) {
                petView2.clearBubble();
                return;
            }
            return;
        }
        petView.clearBubble();
        host.petView.setBubbleHeight(0);
        host.lp.height = host.petView.baseHeight();
        host.lp.y = host.petTopY;
        // expandBubble 把贴边窗口拉进了屏内，这里还原；若气泡期间用户拖过（edgePeek 已清）就不动。
        if (host.edgePeek && !host.peek) {
            host.lp.x = edgePeekX();
        }
        host.bubbleUp = false;
        host.safeUpdate();
    }

    public void safeUpdate() {
        PetView petView = host.petView;
        if (petView == null || !host.added) {
            return;
        }
        try {
            host.wm.updateViewLayout(petView, host.lp);
        } catch (Throwable unused) {
        }
    }

    void placeByPivot(int i, int i2) {
        PetView petView = host.petView;
        if (petView == null || host.lp == null) {
            return;
        }
        petView.geometry(host.geom);
        int round = Math.round(host.geom[0]);
        int round2 = Math.round(host.geom[1]);
        int round3 = Math.round(i - host.geom[2]);
        int round4 = Math.round(i2 - host.geom[3]);
        host.petTopY = round4;
        if (host.lp.width == round && host.lp.height == round2 && host.lp.x == round3 && host.lp.y == round4) {
            return;
        }
        host.lp.width = round;
        host.lp.height = round2;
        host.lp.x = round3;
        host.lp.y = round4;
        host.safeUpdate();
    }

    void rememberPivot() {
        if (host.petView == null || host.lp == null) {
            return;
        }
        float pivotX = host.lp.x + host.petView.pivotLocalX();
        float pivotY = host.lp.y + host.petView.pivotLocalY();
        // 【v2.10.2】同时写比例键：像素键在横竖屏互换后全部失准（1920 在横屏是屏高的 178%），
        //   比例键让位置能跟着屏幕大小走。像素键继续保留 —— 老档回退与外部排查都还在读它。
        int screenW = Math.max(1, host.getResources().getDisplayMetrics().widthPixels);
        int screenH = Math.max(1, host.getResources().getDisplayMetrics().heightPixels);
        host.prefs.edit()
                .putInt("rest_px", Math.round(pivotX))
                .putInt("rest_fy", Math.round(pivotY))
                .putFloat("rest_rx", pivotX / screenW)
                .putFloat("rest_ry", pivotY / screenH)
                .apply();
    }

    int clampFeetY(int i, int i2, int i3) {
        int bubbleSpace = host.petView.bubbleSpace() + Math.round(host.petView.pivotLocalY());
        return Math.max(bubbleSpace, Math.min(Math.max(bubbleSpace, i2 - i3), i));
    }

    void settle() {
        int i = host.getResources().getDisplayMetrics().widthPixels;
        int i2 = host.getResources().getDisplayMetrics().heightPixels;
        int round = Math.round(host.getResources().getDisplayMetrics().density * 8.0f);
        int round2 = Math.round(host.lp.x + host.petView.pivotLocalX());
        int clampFeetY = host.clampFeetY(Math.round(host.lp.y + host.petView.pivotLocalY()), i2, round);
        int round3 = Math.round(i * PetService.SNAP_ZONE_RATIO);
        if (round2 < round3) {
            host.snapToEdge(true, clampFeetY);
            return;
        }
        if (round2 > i - round3) {
            host.snapToEdge(false, clampFeetY);
            return;
        }
        host.exitEdgePeek();
        int petWidth = host.petView.petWidth() / 2;
        host.placeByPivot(Math.max(petWidth + round, Math.min((i - petWidth) - round, round2)), clampFeetY);
        host.rememberPivot();
    }

    void snapToEdge(boolean z, int i) {
        int i2;
        int i3 = host.getResources().getDisplayMetrics().widthPixels;
        int round = Math.round(host.getResources().getDisplayMetrics().density * 8.0f);
        int petWidth = host.petView.petWidth();
        host.enterEdgePeek(z);
        int edgeInsetPx = host.petView.edgeInsetPx();
        if (z) {
            i2 = (round - edgeInsetPx) + (petWidth / 2);
        } else {
            i2 = ((i3 - round) + edgeInsetPx) - (petWidth / 2);
        }
        int round2 = Math.round(i2 - host.petView.pivotLocalX());
        host.lp.width = host.petView.windowWidth();
        host.lp.height = host.petView.baseHeight();
        host.lp.y = Math.round(i - host.petView.pivotLocalY());
        host.petTopY = host.lp.y;
        host.safeUpdate();
        // 【v2.10.2】贴边位置同样写一份比例键：否则「贴边 → 旋转」时会按上一次未贴边的比例还原。
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        host.prefs.edit().putInt("rest_px", i2).putInt("rest_fy", i)
                .putFloat("rest_rx", i2 / (float) i3).putFloat("rest_ry", i / (float) screenH).apply();
        ValueAnimator ofInt = ValueAnimator.ofInt(host.lp.x, round2);
        host.snapAnim = ofInt;
        ofInt.setDuration(UiKit.D_LAYER);
        host.snapAnim.setInterpolator(new DecelerateInterpolator());
        host.snapAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                host.lp.x = ((Integer) valueAnimator.getAnimatedValue()).intValue();
                host.safeUpdate();
            }
        });
        host.snapAnim.start();
    }

    void enterEdgePeek(boolean z) {
        PetView petView = host.petView;
        if (petView == null) {
            return;
        }
        host.edgePeek = true;
        host.edgeAtLeft = z;
        petView.setEdgePeek(true, z);
        host.prefs.edit().putInt("edge_side", !z ? 1 : 0).apply();
    }

    void exitEdgePeek() {
        WindowManager.LayoutParams layoutParams;
        if (!host.edgePeek || host.petView == null || (layoutParams = host.lp) == null) {
            return;
        }
        host.edgePeek = false;
        float pivotLocalX = layoutParams.x + host.petView.pivotLocalX();
        float pivotLocalY = host.lp.y + host.petView.pivotLocalY();
        host.petView.setEdgePeek(false, false);
        host.placeByPivot(Math.round(pivotLocalX), Math.round(pivotLocalY));
        host.prefs.edit().putInt("edge_side", -1).apply();
    }

    public void layoutPeek(int i, int i2, int i3, int i4) {
        if (host.petView == null || host.lp == null || !host.added) {
            return;
        }
        if (!host.peek) {
            host.peek = true;
            ValueAnimator valueAnimator = host.snapAnim;
            if (valueAnimator != null) {
                valueAnimator.cancel();
            }
            host.ui.removeCallbacks(host.hideBubble);
            host.petView.clearBubble();
            host.petView.setBubbleHeight(0);
            host.bubbleUp = false;
            host.petView.setEdgePeek(false, false);
            host.petView.setPeek(true);
        }
        float f = host.getResources().getDisplayMetrics().density;
        int i5 = host.getResources().getDisplayMetrics().widthPixels;
        int windowWidth = host.petView.windowWidth();
        int baseHeight = host.petView.baseHeight();
        // 搭接量按比例走：写死 8dp 在放大后显得几乎不重叠，头与聊天框之间会露出缝隙。
        int round = Math.max(Math.round(8.0f * f), Math.round(host.petView.petWidth() * PetView.PEEK_OVERLAP_RATIO));
        host.setPetShown(true);
        // 【不再隐藏】聊天窗顶边已被 topLimit 夹在人偶下方，正常不会再撞到屏顶；
        // 但首帧 / 键盘竞态 / topLimit 过期等瞬时仍可能越界，此时把窗口夹进屏幕内保持可见，
        // 而不是 setPetShown(false) 让它「从上方消失」（隐藏后 peek 仍为真且无自动恢复）。
        int minTop = Math.round(f * 4.0f);
        host.petTopY = Math.max(minTop, (i2 - baseHeight) + round);
        host.lp.width = windowWidth;
        host.lp.height = baseHeight;
        host.lp.x = Math.max(0, Math.min(i5 - windowWidth, i + ((i3 - windowWidth) / 2)));
        host.lp.y = host.petTopY;
        host.safeUpdate();
    }

    void updateTouchable() {
        if (host.petView == null || host.lp == null) {
            return;
        }
        boolean z = host.petShown && !PetPrefs.touchThrough(host);
        int i = host.lp.flags;
        if (z) {
            host.lp.flags &= -17;
        } else {
            host.lp.flags |= 16;
        }
        if (host.lp.flags != i) {
            host.safeUpdate();
        }
    }

    void setPetShown(boolean z) {
        PetView petView = host.petView;
        if (petView == null || host.lp == null || z == host.petShown) {
            return;
        }
        host.petShown = z;
        petView.setVisibility(z ? 0 : 8);
        host.updateTouchable();
    }

    public void exitPeek() {
        if (host.peek) {
            host.peek = false;
            host.setPetShown(true);
            if (host.petView == null || host.lp == null || !host.added) {
                return;
            }
            float f = host.getResources().getDisplayMetrics().density;
            int i = host.getResources().getDisplayMetrics().widthPixels;
            int i2 = host.getResources().getDisplayMetrics().heightPixels;
            int round = Math.round(f * 8.0f);
            host.petView.setPeek(false);
            host.petView.clearBubble();
            host.petView.setBubbleHeight(0);
            host.petView.setEdgePeek(host.edgePeek, host.edgeAtLeft);
            host.bubbleUp = false;
            int petWidth = host.petView.petWidth() / 2;
            // 【v2.10.2】改走比例优先的读取：旧像素键在旋转后会把窗口摆到屏外。
            int[] rest = restorePivot();
            int i3 = rest != null ? rest[0] : (i - round) - petWidth;
            int i4 = rest != null ? rest[1] : Math.round(i2 * 0.8f);
            if (!host.edgePeek) {
                i3 = Math.max(petWidth, Math.min(i - petWidth, i3));
            }
            host.placeByPivot(i3, host.clampFeetY(i4, i2, round));
        }
    }

    void applyDragPlacement() {
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - host.lastPlaceAt < 12) {
            return;
        }
        host.lastPlaceAt = currentTimeMillis;
        host.placeByPivot(Math.round(host.curPivotX), Math.round(host.curPivotY));
    }
}
