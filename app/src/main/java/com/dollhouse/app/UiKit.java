package com.dollhouse.app;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 统一视觉与动效。配色 / 圆角 / 时长全部在此收口，
 * 页面代码不再散写裸值 —— 对齐参考软件 My Life, My Sim 的设计令牌。
 *
 * 【硬约束】本 App 不允许任何形式的 Toast / Snackbar / 自实现浮层短提示。
 *   历史：2026-10-03 全量删除（原 55 处调用 + 1 个出口），因其视觉上与系统通知冲突且很丑。
 *   新增按键 / 交互一律用页面内的文字变化、控件状态或页面跳转做反馈，禁止重新引入浮层短提示。
 */
public final class UiKit {
    /*
     * 【重要】以下颜色字段全部是可变静态字段（不是 final）：
     * 它们由 ThemeManager.apply() 在每次界面构建前按当前主题整体覆写，
     * 全工程数百处 UiKit.XXX 的引用点因此不用改一行。
     * 新增颜色时记得同步在 ThemeManager 的三套调色板里补位，否则切换主题后该色不会变。
     */
    /** 主色（参考软件 --acc / --acc2）。 */
    public static int ACC = 0xFF6B4EE6;
    public static int ACC2 = 0xFF8B6EF7;
    public static int CARD = 0xFFFFFFFF;
    public static int BG = 0xFFF4F5F9;
    public static int TITLE = 0xFF22315B;
    public static int SUB = 0xFF5A6B99;
    public static int LINE = 0xFFE6E7EF;
    /** 选项/对照组底色（设置页用）。 */
    public static int OPTION = 0xFFF2F5FF;
    public static int SOFT = 0xFFF2F3F7;
    public static int FIELD = 0xFFF4F2FD;
    public static int OK = 0xFF1B8A3A;
    public static int ERR = 0xFFB3261E;
    public static int ON_ACC = 0xFFFFFFFF;
    /** 聊天面板专用色（气泡 / 操作条 / 提示条）。 */
    public static int CHAT_BUBBLE_USER = 0xFF4C6FDE;
    public static int CHAT_BORDER = 0xFFC9D4EE;
    public static int CHAT_CHIP_BG = 0xFFE4EAF8;
    public static int CHAT_CHIP_FG = 0xFF3A5BC7;
    public static int CHAT_CHIP_ON = 0xFFDCE6FF;
    public static int CHAT_CHIP_OFF = 0xFFEDF1FA;
    public static int CHAT_CHIP_MUTE = 0xFF7A88B0;
    public static int CHAT_ACTION_BG = 0xFFE6EBF8;
    public static int HINT_FG = 0xFF8A5A00;
    public static int HINT_BG = 0xFFFFF4D6;
    /** 自绘开关的关闭态轨道色：比 LINE 深，保证白底卡片上看得清。 */
    public static int SWITCH_OFF = 0xFFC9CEDD;
    /** 桌宠表情色（惊叹 / 开心 / 眩晕）。 */
    public static int EMOTE_1 = 0xFFF2603C;
    public static int EMOTE_2 = 0xFFE8608F;
    public static int EMOTE_3 = 0xFF5A7BD8;
    /** 卡片描边（带透明度，深色主题下会换成浅色）。 */
    public static int STROKE = 0x1422315B;
    /** 弹层遮罩色：抽屉 / 底部面板背后的压暗层，深色主题下更重。 */
    public static int SCRIM = 0x8A000000;

    /** 动效时长：按压 90ms / 微交互 180ms / 弹层 260ms / 整页转场 300ms。 */
    public static final int D_PRESS = 90;
    public static final int D_MICRO = 180;
    public static final int D_LAYER = 260;
    public static final int D_PAGE = 300;

    /*
     * 全局动效曲线（Material 标准 easing）。
     * 【为何收口】此前各处直接用 DecelerateInterpolator（纯减速）或干脆不设曲线，
     *   起步偏“硬”。统一成标准曲线后，全 App 的位移／透明度／高度动画手感一致。
     *   EASE_STD   = 标准（进慢出慢，用于状态切换、开关、按压回弹）
     *   EASE_DECEL = 减速（用于入场：页面／面板从侧边或底部推进来）
     *   EASE_ACCEL = 加速（用于出场：让“离开”比“进入”更快，符合视觉习惯）
     */
    public static final PathInterpolator EASE_STD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    public static final PathInterpolator EASE_DECEL = new PathInterpolator(0f, 0f, 0.2f, 1f);
    public static final PathInterpolator EASE_ACCEL = new PathInterpolator(0.4f, 0f, 1f, 1f);

    private UiKit() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** dp -> px，但不取整。给需要保留小数精度的动画/绘制计算用。 */
    public static float dpf(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    // 生成纯色圆角背景。
    public static GradientDrawable round(int color, Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    // 生成带描边的圆角背景。
    public static GradientDrawable roundStroke(int color, int stroke, Context c, float radiusDp) {
        GradientDrawable g = round(color, c, radiusDp);
        g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    /** 卡片：白底 + 16dp 圆角 + 极淡描边 + 近地投影。 */
    public static void card(View v, Context c) {
        GradientDrawable g = round(CARD, c, 16);
        g.setStroke(1, STROKE);
        v.setBackground(g);
        v.setElevation(dp(c, 2));
    }

    /** 主按钮：紫渐变 + 白字 + 微光。 */
    public static void primary(TextView b, Context c) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{ACC, ACC2});
        g.setCornerRadius(dp(c, 12));
        b.setBackground(g);
        b.setTextColor(ON_ACC);
        b.setGravity(Gravity.CENTER);
        b.setElevation(dp(c, 3));
        press(b);
    }

    /**
     * 发送键：圆形紫渐变 + 图标字。
     * 【坑】用 Button 时必须自行清掉主题带来的 minWidth/minHeight（默认 88×48dp），
     *      否则外层给的 38dp 方形会被撑成椭圆，圆形就不圆了。
     */
    public static void sendButton(ImageView b, Context c) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{ACC, ACC2});
        g.setCornerRadius(dp(c, 999));
        b.setBackground(g);
        b.setImageResource(R.drawable.ic_send);
        Drawable d = b.getDrawable();
        if (d != null) {
            try {
                d.mutate().setTint(ON_ACC);
            } catch (Throwable ignored) {
            }
        }
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setClickable(true);
        b.setPadding(0, 0, 0, 0);
        b.setElevation(dp(c, 3));
        press(b);
    }

    /** 次按钮：白底 + 淡描边 + 深字。 */
    public static void secondary(TextView b, Context c) {
        b.setBackground(roundStroke(CARD, LINE, c, 12));
        b.setTextColor(TITLE);
        b.setGravity(Gravity.CENTER);
        b.setElevation(dp(c, 1));
        press(b);
    }

    /** 按压反馈：按下缩到 0.97，抬起回弹。 */
    public static void press(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    // 【坑】不可点的行收不到 ACTION_UP：View 不消费 DOWN，后续事件就不会再派发回来，
                    //   缩放会一直停在 0.97 再也回不去（表现就是「点一下之后按钮一直缩着」）。
                    //   所以不可点时不播按压动画，直接放行。
                    if (!view.isClickable()) {
                        return false;
                    }
                    view.animate().scaleX(0.96f).scaleY(0.96f)
                            .setDuration(D_PRESS)
                            .setInterpolator(EASE_STD).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    // 【弹簧】松手那一下用 bouncy（ζ=0.40）弹回，按下仍用 EASE_STD 保持跟手。
                    view.animate().cancel();
                    final View fv = view;
                    Springs.drive(Springs.bouncy(), new Springs.Listener() {
                        @Override
                        public void onUpdate(float p) {
                            float s = Springs.lerp(0.96f, 1.0f, p);
                            fv.setScaleX(s);
                            fv.setScaleY(s);
                        }

                        @Override
                        public void onEnd() {
                            fv.setScaleX(1f);
                            fv.setScaleY(1f);
                        }
                    });
                }
                return false;
            }
        });
    }

    /**
     * 卡片展开 / 收起：高度动画 + 箭头旋转 90°。
     * 收起态 height=0（不用 GONE，免得布局跳），展开结束回到 WRAP_CONTENT。
     */
    public static void expand(final View body, final View arrow, final boolean open) {
        if (body.getLayoutParams() == null) {
            return;
        }
        int w = body.getWidth();
        if (w <= 0 && body.getParent() instanceof View) {
            View p = (View) body.getParent();
            w = p.getWidth() - p.getPaddingLeft() - p.getPaddingRight();
        }
        if (w <= 0) {
            w = body.getResources().getDisplayMetrics().widthPixels;
        }
        body.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int full = Math.max(1, body.getMeasuredHeight());
        final int from = Math.max(0, body.getHeight());
        final int to = open ? full : 0;

        ValueAnimator va = ValueAnimator.ofInt(from, to);
        va.setDuration(D_LAYER);
        va.setInterpolator(EASE_STD);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator an) {
                int h = (Integer) an.getAnimatedValue();
                ViewGroup.LayoutParams lp = body.getLayoutParams();
                lp.height = h;
                body.setLayoutParams(lp);
                body.setAlpha(Math.min(1f, (float) h / full));
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                ViewGroup.LayoutParams lp = body.getLayoutParams();
                lp.height = open ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
                body.setLayoutParams(lp);
                body.setAlpha(open ? 1f : 0f);
            }
        });
        va.start();
        if (arrow != null) {
            // 【弹簧】箭头旋转同样走 snappy，与卡片高度动画同节奏。
            final View fa = arrow;
            final float fromRot = fa.getRotation();
            final float toRot = open ? 90f : 0f;
            Springs.drive(Springs.snappy(), new Springs.Listener() {
                @Override
                public void onUpdate(float p) {
                    fa.setRotation(Springs.lerp(fromRot, toRot, p));
                }

                @Override
                public void onEnd() {
                    fa.setRotation(toRot);
                }
            });
        }
    }

    /** 入场：淡入 + 上移 8dp（错峰用 delayMs）。 */
    public static void enter(final View v, final int delayMs) {
        if (v.getParent() == null) {
            // 还没挂进视图树：动画会被丢，等挂上再播。
            v.post(new Runnable() {
                @Override
                public void run() {
                    enter(v, delayMs);
                }
            });
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 8));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.max(0, delayMs))
                .setDuration(D_LAYER)
                .setInterpolator(EASE_DECEL).start();
    }

    /** 卡片头右侧的折叠箭头：描边 chevron 图标，靠 rotation 0 ↔ 90 表示开合。 */
    public static ImageView arrow(Context ctx) {
        ImageView t = Icons.view(ctx, Icons.IC_CHEVRON_RIGHT, 18.0f, TITLE);
        return t;
    }

    /**
     * 自绘滑动开关：圆角轨道 + 圆形滑块，切换时滑块平移 + 轨道变色。
     * 刻意不继承 CompoundButton —— 各家 ROM 对 Switch 的默认样式差异很大，
     * 自绘才能保证和本 App 的配色一致。
     */
    public static class Switch extends FrameLayout {
        private static final int W_DP = 44;
        private static final int H_DP = 24;
        private static final int PAD_DP = 3;
        private final View knob;
        private boolean on;
        /** 轨道变色动画句柄：连点时先取消上一段，避免两个动画抢同一个背景。 */
        private ValueAnimator trackAnim;
        public Switch(Context ctx) {
            super(ctx);
            setBackground(round(SWITCH_OFF, ctx, 999));
            setElevation(dp(ctx, 1));

            knob = new View(ctx);
            int k = dp(ctx, H_DP - PAD_DP * 2);
            FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(k, k);
            klp.gravity = Gravity.CENTER_VERTICAL;
            klp.leftMargin = dp(ctx, PAD_DP);
            knob.setBackground(round(0xFFFFFFFF, ctx, 999));
            knob.setElevation(dp(ctx, 3));
            addView(knob, klp);
        }

        /**
         * 强制固定尺寸：开关的 54×30dp 是视觉基准，不能被外层的 WRAP_CONTENT
         * 压成子 View 大小（FrameLayout 默认就是按子 View 量，会把轨道压扁成方块）。
         */
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(dp(getContext(), W_DP), dp(getContext(), H_DP));
        }

        public boolean isOn() {
            return on;
        }

        /** 只改状态与视觉（不动画），用于从 prefs 恢复初始值。 */
        public void setOn(boolean value) {
            setOn(value, false);
        }

        /** animated=true 时播放滑块位移动画。 */
        public void setOn(boolean value, boolean animated) {
            this.on = value;
            final Context c = getContext();
            int travel = dp(c, W_DP - H_DP);
            final float to = value ? travel : 0f;
            knob.animate().cancel();
            if (trackAnim != null) {
                trackAnim.cancel();
                trackAnim = null;
            }
            if (!animated) {
                setBackground(round(value ? ACC : SWITCH_OFF, c, 999));
                knob.setTranslationX(to);
                return;
            }
            // 轨道颜色：ArgbEvaluator 逐帧插值。
            // 【为何】原先是 setBackground(三元) 一下跳色，与滑块 180ms 的位移不同步，
            //   看上去就是“轨道啪一下变了、滑块慢慢跟”。两者同时长同曲线才顺。
            final int from = value ? SWITCH_OFF : ACC;
            final int toColor = value ? ACC : SWITCH_OFF;
            final ArgbEvaluator ev = new ArgbEvaluator();
            final float fromX = knob.getTranslationX();
            final float toX = to;
            // 【弹簧】轨道与滑块共用同一条弹簧进度：snappy（ζ=0.73）。
            //   历史坑：轨道 setBackground 硬跳 + 滑块 180ms 位移，看着是「轨道啪一下变了、滑块慢慢跟」。
            //   现在两者由同一个 progress 驱动，绝不会再脱节。
            ValueAnimator ta = Springs.drive(Springs.snappy(), new Springs.Listener() {
                @Override
                public void onUpdate(float p) {
                    knob.setTranslationX(Springs.lerp(fromX, toX, p));
                    setBackground(round(((Integer) ev.evaluate(p, from, toColor)).intValue(), c, 999));
                }

                @Override
                public void onEnd() {
                    knob.setTranslationX(toX);
                    setBackground(round(toColor, c, 999));
                }
            });
            trackAnim = ta;
        }
    }

    /** 【职责】列表/输入框的圆角背景。原 SettingsFold.rowBackground。 */
    public static GradientDrawable rowBg(Context c, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(c, 8));
        return g;
    }

    /** 【职责】设置页卡片的白色圆角背景 + 极淡描边。原 SettingsFold.cardBackground。 */
    public static GradientDrawable cardBg(Context c) {
        GradientDrawable g = round(CARD, c, 16);
        g.setStroke(1, STROKE);
        return g;
    }


    /** 【职责】沿 ContextWrapper 向上找宿主 Activity；找不到返回 null。 */
    public static Activity findActivity(Context c) {
        Context cur = c;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof Activity) {
                return (Activity) cur;
            }
            if (cur instanceof ContextWrapper) {
                cur = ((ContextWrapper) cur).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }
    /* ===== 弹窗与输入框：全工程统一走这里，避免各处弹出系统原生白框。 ===== */

    /** 弹窗标题：16sp 加粗 + 主字色。 */
    public static TextView dialogTitle(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(16.0f);
        t.setTextColor(TITLE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** 弹窗按钮：primary=true 走紫渐变主样式，否则白底描边次样式。 */
    public static Button dialogButton(Context ctx, String text, boolean primary) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(UiKit.FS_BTN);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = dp(ctx, 12);
        b.setPadding(pad, pad, pad, pad);
        if (primary) {
            primary(b, ctx);
        } else {
            secondary(b, ctx);
        }
        return b;
    }

    /** 弹窗正文说明：13sp 副字色。 */
    public static TextView dialogMessage(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(UiKit.FS_SUB);
        t.setTextColor(SUB);
        t.setLineSpacing(dp(ctx, 3), 1.0f);
        return t;
    }

    /** 输入框统一外观：淡紫底圆角 + 内边距 + 主字色（对齐 SettingsPage.addLabeled）。 */
    public static void field(EditText e, Context c) {
        e.setBackground(rowBg(c, FIELD));
        int pad = dp(c, 12);
        e.setPadding(pad, pad, pad, pad);
        e.setTextSize(UiKit.FS_BTN);
        e.setTextColor(TITLE);
    }

    /**
     * 自绘弹窗：UiKit 卡片 + 标题 + 内容 + 右侧按钮行。
     * 刻意不用系统 AlertDialog —— 各家 ROM 的原生弹窗是白底 Material 外观，
     * 与全局配色割裂；自绘才能保证暗色 / 纯黑主题下也不刺眼。
     * posText / negText 传 null 表示不显示该按钮；两者都 null 则整行不出现。
     */
    public static Dialog showDialog(Activity act, String title, View content,
                                    String posText, final View.OnClickListener pos,
                                    String negText, final View.OnClickListener neg) {
        if (act == null) {
            return null;
        }
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(round(CARD, act, 16));
        int pad = dp(act, 18);
        col.setPadding(pad, pad, pad, dp(act, 14));

        if (title != null && title.length() > 0) {
            col.addView(dialogTitle(act, title));
        }
        if (content != null) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.topMargin = dp(act, 12);
            content.setLayoutParams(clp);
            col.addView(content);
        }
        if (posText != null || negText != null) {
            LinearLayout bar = new LinearLayout(act);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = dp(act, 18);
            bar.setLayoutParams(blp);
            if (negText != null) {
                Button nb = dialogButton(act, negText, false);
                nb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dlg.dismiss();
                        if (neg != null) {
                            neg.onClick(v);
                        }
                    }
                });
                bar.addView(nb);
            }
            if (posText != null) {
                Button pb = dialogButton(act, posText, true);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
                plp.leftMargin = dp(act, 10);
                pb.setLayoutParams(plp);
                pb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dlg.dismiss();
                        if (pos != null) {
                            pos.onClick(v);
                        }
                    }
                });
                bar.addView(pb);
            }
            col.addView(bar);
        }
        dlg.setContentView(col);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
            int wide = Math.min((int) (act.getResources().getDisplayMetrics().widthPixels * 0.88f),
                    dp(act, 420));
            w.setLayout(wide, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        // 弹窗入场：轻微上浮 + 缩放 + 淡入。
        // 【为何】原先是 show() 后凭空出现，与 App 其他地方的过渡感不一致。
        //   初始态必须在 show() 之前设好，否则会先闪一帧完整尺寸再缩回去。
        col.setAlpha(0f);
        col.setScaleX(0.94f);
        col.setScaleY(0.94f);
        col.setTranslationY(dp(act, 14));
        col.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(D_MICRO).setInterpolator(EASE_DECEL).start();
        dlg.show();
        return dlg;
    }

    /** 【职责】安全取 TextView 文本并 trim；非 TextView 返回 null。 */
    public static String textOf(View v) {
        if (!(v instanceof TextView)) {
            return null;
        }
        CharSequence cs = ((TextView) v).getText();
        return cs == null ? null : cs.toString().trim();
    }

    /* ================= 统一控件规范：全 App 只用这一套 =================
     * 【背景】此前按钮样式散落在 HomeCards.mkButton / ChatDrawer.flatButton / TokenStat.chip /
     *         ApiConfigPage.pill 等六处，字号 12/13/14/15 混用、圆角 10/12/999 混用，
     *         点到哪个页面靠哪个工厂，观感不连贯。现全部收口到本节，
     *         页面只调工厂，不再散写字号 / 内边距 / 圆角。
     * 【兼容】原有 primary / secondary / press 保持不变，新工厂内部复用它们。
     */

    /** 页面标题字号。 */
    public static final float FS_TITLE = 20.0f;
    /** 副标题 / 注脚字号。 */
    public static final float FS_SUB = 12.0f;
    /** 底部说明文字号（比副标题再小一档）。 */
    public static final float FS_TINY = 11.0f;
    /** 标准按钮字号。 */
    public static final float FS_BTN = 15.0f;
    /** 小药丸 / 状态标签字号。 */
    public static final float FS_CHIP = 12.0f;
    /** 顶栏图标字号。 */
    public static final float FS_ICON = 20.0f;
    /** 图标控件的固定命中区边长（dp）。 */
    public static final int HIT_DP = 40;
    /** 按钮圆角（dp）。 */
    public static final int RADIUS_BTN = 12;
    /** 药丸 / 行 / 输入框圆角（dp）。 */
    public static final int RADIUS_CHIP = 10;

    /**
     * 统一图标按钮：裸贴描边图标 + 固定命中区，靠 press 缩放反馈表达可点。
     * 【为何用 padding 而不是直接给图标尺寸】调用方可能用 iconLp() 之类再覆盖
     *   LayoutParams（命中区要 40dp）。若图标本身跟着容器撑大，视觉就会时大时小；
     *   所以把「视觉边长」钉在 padding 上，容器给多大都不影响图标实际大小。
     */
    public static ImageView iconView(Context ctx, int resId, float sizeDp, int color) {
        ImageView iv = new ImageView(ctx);
        int hit = dp(ctx, HIT_DP);
        int pad = Math.max(0, (hit - dp(ctx, sizeDp)) / 2);
        iv.setPadding(pad, pad, pad, pad);
        iv.setLayoutParams(new ViewGroup.LayoutParams(hit, hit));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable d = Icons.get(ctx, resId, color);
        if (d != null) {
            iv.setImageDrawable(d);
        }
        iv.setClickable(true);
        iv.setFocusable(false);
        press(iv);
        return iv;
    }

    /** 统一顶栏图标（字符版）：保留给确实没有对应矢量图的场合，新代码一律用 iconView。 */
    public static TextView iconBtn(Context ctx, String glyph, float sizeSp, int color) {
        TextView t = new TextView(ctx);
        t.setText(glyph);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        press(t);
        return t;
    }

    /**
     * 统一顶栏：返回键 + 标题 + 副标题（副标题可空）。
     * 【约定】所有独立页的顶栏都走这里，保证返回键位置 / 字号 / 间距完全一致。
     * 返回 null 则不加返回键（用于不需要返回的根页面）。
     */
    public static LinearLayout topBar(Context ctx, String title, String sub, View.OnClickListener back) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        if (back != null) {
            ImageView b = iconView(ctx, Icons.IC_ARROW_LEFT, FS_ICON, TITLE);
            b.setOnClickListener(back);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(ctx, HIT_DP), dp(ctx, HIT_DP));
            blp.rightMargin = dp(ctx, 10);
            bar.addView(b, blp);
        }

        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView t1 = new TextView(ctx);
        t1.setText(title);
        t1.setTextSize(FS_TITLE);
        t1.setTextColor(TITLE);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(t1);
        if (sub != null && sub.length() > 0) {
            TextView t2 = new TextView(ctx);
            t2.setText(sub);
            t2.setTextSize(FS_SUB);
            t2.setTextColor(SUB);
            t2.setPadding(0, dp(ctx, 2), 0, 0);
            titles.addView(t2);
        }
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1.0f));
        return bar;
    }

    /** 统一小药丸 / 状态标签：CHIP 配色。 */
    public static TextView chip(Context ctx, String text) {
        return chip(ctx, text, CHAT_CHIP_FG, CHAT_CHIP_BG);
    }

    /** 统一小药丸：可指定前景 / 背景色，圆角与内边距固定。 */
    public static TextView chip(Context ctx, String text, int fg, int bg) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(FS_CHIP);
        t.setTextColor(fg);
        t.setBackground(round(bg, ctx, RADIUS_CHIP));
        t.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        return t;
    }

    /** 紧凑状态标签（比 chip 更小）：用于「启用 / 禁用」这类行内标记。 */
    public static TextView badge(Context ctx, String text, int fg, int bg) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(UiKit.FS_TINY);
        t.setTextColor(fg);
        t.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        t.setBackground(round(bg, ctx, 999));
        return t;
    }

    /** 描边药丸：白底 + 淡描边，用于「删除 / 次操作」这类行内按钮。 */
    public static TextView outlineChip(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(FS_CHIP);
        t.setTextColor(TITLE);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundStroke(CARD, LINE, ctx, 999));
        t.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        press(t);
        return t;
    }

    /** 主色药丸：紫渐变 + 白字，用于行内主操作（保存 / 重新拉取）。 */
    public static TextView primaryChip(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(FS_CHIP);
        t.setTextColor(ON_ACC);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        primary(t, ctx);
        t.setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9));
        return t;
    }

    /** 统一标准按钮：primary=true 紫渐变主样式，否则白底描边次样式。 */
    public static Button btn(Context ctx, String text, boolean primary) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(FS_BTN);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = dp(ctx, 14);
        b.setPadding(pad, pad, pad, pad);
        if (primary) {
            primary(b, ctx);
        } else {
            secondary(b, ctx);
        }
        return b;
    }

    /** 统一整宽按钮：底部主操作区用，带 10dp 上间距。 */
    public static Button blockBtn(Context ctx, String text, boolean primary) {
        Button b = btn(ctx, text, primary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 10);
        b.setLayoutParams(lp);
        return b;
    }

    /* ================= 整页转场：所有二级页共用 =================
     * 【背景】各二级页原先都是 content.removeView(旧) + addView(新)，中间没有任何过渡，
     *         观感是“畴”地一下整屏换掉 —— 这是全 App 最大的突兀源。
     *         这里收口成三个方法，页面只管调用，不再自己拼 remove/add。
     * 【单位】位移一律取容器宽度比例，不写死 dp，适配任意分辨率。
     * 【为何不用 FragmentTransaction / ActivityOptions】本项目二级页不是 Fragment，
     *         而是在 android.R.id.content 上手工叠的覆盖页；改造成 Fragment 会动到
     *         所有页面的生命周期，风险远大于收益，所以就地给叠页加动效。
     */

    /** 屏宽（拿不到布局宽度时兜底用显示宽度）。 */
    private static int pageW(ViewGroup content) {
        int w = content.getWidth();
        return w > 0 ? w : content.getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * 打开一个覆盖页：新页自右侧滑入。
     * 旧覆盖页立即移除 —— 它会被新页完全遮住，不给它做动画反而更干净、也不会残留视图。
     */
    public static void openPage(ViewGroup content, View page, String tag) {
        if (content == null || page == null) {
            return;
        }
        View old = tag == null ? null : content.findViewWithTag(tag);
        if (old != null) {
            content.removeView(old);
        }
        page.setTag(tag);
        page.setTranslationX(pageW(content) * 0.14f);
        page.setAlpha(0f);
        content.addView(page, new ViewGroup.LayoutParams(-1, -1));
        page.animate().translationX(0f).alpha(1f)
                .setDuration(D_PAGE).setInterpolator(EASE_DECEL).start();
    }

    /** 同层内容替换（切分段 / 翻日期 / 二级页重建）：只交叉淡入，不做位移，避免方向误导。 */
    public static void swapPage(ViewGroup content, View page, String tag) {
        if (content == null || page == null) {
            return;
        }
        final View old = tag == null ? null : content.findViewWithTag(tag);
        page.setTag(tag);
        page.setAlpha(0f);
        content.addView(page, new ViewGroup.LayoutParams(-1, -1));
        page.animate().alpha(1f).setDuration(D_PAGE).setInterpolator(EASE_DECEL)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // 挂在新页（一直在树上）的结束回调上，比挂旧页可靠。
                        if (old != null && old.getParent() instanceof ViewGroup) {
                            ((ViewGroup) old.getParent()).removeView(old);
                        }
                    }
                }).start();
    }

    /**
     * 关闭一个覆盖页：向右滑出 + 淡出后移除。
     * 【幂等】removeView 对非子 View 是空操作，重复调用安全。
     */
    public static void closePage(final View page) {
        if (page == null) {
            return;
        }
        if (!(page.getParent() instanceof ViewGroup)) {
            return;
        }
        final ViewGroup parent = (ViewGroup) page.getParent();
        page.animate().translationX(pageW(parent) * 0.14f).alpha(0f)
                .setDuration(D_LAYER).setInterpolator(EASE_ACCEL)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        parent.removeView(page);
                    }
                }).start();
    }

    /**
     * 淡入出现（列表项 / 提示条）——先可见再淡入，绝不用"喷"一下的方式。
     * 【为何】全 App 有 30 处 setVisibility(VISIBLE/GONE) 直接切换，
     *   其中提示条、附件条、记忆状态这类小元素的硬切换最刺眼，这里统一收口。
     */
    public static void reveal(final View v) {
        if (v == null) {
            return;
        }
        if (v.getVisibility() != View.VISIBLE) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
        }
        v.animate().cancel();
        v.animate().alpha(1f).setDuration(D_MICRO).setInterpolator(EASE_STD).start();
    }

    /** 淡出隐藏（动画结束后才置 GONE，保留布局占位直到真正隐藏）。 */
    public static void collapse(final View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return;
        }
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(D_MICRO).setInterpolator(EASE_ACCEL)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (v.getAlpha() < 0.05f) {
                            v.setVisibility(View.GONE);
                        }
                    }
                }).start();
    }

    /** 一行代码表达"要显示就淡入、要隐藏就淡出"。 */
    public static void showHide(View v, boolean show) {
        if (show) {
            reveal(v);
        } else {
            collapse(v);
        }
    }

    /** 文字颜色平滑过渡（权限状态红↔绿、选中态紫↔灰）。 */
    public static void setTextColorAnimated(final TextView t, int toColor) {
        if (t == null) {
            return;
        }
        int from = t.getCurrentTextColor();
        if (from == toColor) {
            return;
        }
        final ArgbEvaluator ev = new ArgbEvaluator();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(D_MICRO);
        va.setInterpolator(EASE_STD);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator an) {
                float f = (Float) an.getAnimatedValue();
                t.setTextColor(((Integer) ev.evaluate(f, from, toColor)).intValue());
            }
        });
        va.start();
    }

    /**
     * 平滑滚回顶部。
     * 【为何】scrollTo(0,0) 是瞬时硬拽，卡片折叠、展开更早历史后用这个太生硬。
     *   低版本没有 smoothScrollTo 的 ScrollView 子类仍有 API，直接可用。
     */
    public static void scrollToTop(final ScrollView sv) {
        if (sv == null) {
            return;
        }
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.smoothScrollTo(0, 0);
            }
        });
    }

    /**
     * 列表错峰入场：容器每个直接子 View 依次淡入 + 上浮。
     * 【用于】首屏 / 打开面板时一次性挂了很多行，一起出现看着很“顿”；错峰 24ms 就有流动感。
     * 【步长】默认 26ms；行数超 12 时自动压到 14ms，免得底部项等太久。
     */
    public static void stagger(ViewGroup col) {
        stagger(col, 0);
    }

    public static void stagger(ViewGroup col, int firstDelayMs) {
        if (col == null) {
            return;
        }
        int n = col.getChildCount();
        int step = n > 12 ? 14 : 24;
        for (int i = 0; i < n; i++) {
            enter(col.getChildAt(i), firstDelayMs + i * step);
        }
    }

    /** 错峰上限：只给前 12 个错峰，其余立即可见，避免长列表末尾迟迟不出现。 */
    public static void staggerCapped(ViewGroup col, int maxCount) {
        if (col == null) {
            return;
        }
        int n = Math.min(col.getChildCount(), maxCount);
        for (int i = 0; i < n; i++) {
            enter(col.getChildAt(i), i * 24);
        }
    }

    /**
     * 从底部弹上来：遮罩淡入 + 面板上滑。
     * 【用于】ChatDrawer / SheetPanel 这类底部面板，替代原先直接 addView 的硬弹。
     */
    public static void slideUpIn(final View shade, final View panel) {
        if (shade == null || panel == null) {
            return;
        }
        float h = panel.getHeight() > 0 ? panel.getHeight()
                : panel.getResources().getDisplayMetrics().heightPixels * 0.5f;
        shade.setAlpha(0f);
        panel.setTranslationY(h);
        shade.animate().alpha(1f).setDuration(D_LAYER).setInterpolator(EASE_STD).start();
        panel.animate().translationY(0f).setDuration(D_PAGE).setInterpolator(EASE_DECEL).start();
    }

    /** 从左侧推入：抽屉专用（遮罩淡入 + 抽屉右移进屏）。 */
    public static void slideInLeft(final View shade, final View panel) {
        if (shade == null || panel == null) {
            return;
        }
        float w = panel.getWidth() > 0 ? panel.getWidth()
                : panel.getResources().getDisplayMetrics().widthPixels * 0.8f;
        shade.setAlpha(0f);
        panel.setTranslationX(-w);
        shade.animate().alpha(1f).setDuration(D_LAYER).setInterpolator(EASE_STD).start();
        panel.animate().translationX(0f).setDuration(D_PAGE).setInterpolator(EASE_DECEL).start();
    }

    /**
     * 底部面板出场：面板下滑 + 遮罩淡出，结束后整体移除。
     * 取遮罩的子 View 0 当面板（与 SheetPanel.open 的层级一致）。
     */
    public static void slideDownOut(final View shade) {
        slideOut(shade, true);
    }

    /** 左侧抽屉出场：面板左滑 + 遮罩淡出。 */
    public static void slideOutLeft(final View shade) {
        slideOut(shade, false);
    }

    private static void slideOut(final View shade, final boolean down) {
        if (shade == null || !(shade.getParent() instanceof ViewGroup)) {
            return;
        }
        final ViewGroup parent = (ViewGroup) shade.getParent();
        View panel = shade instanceof ViewGroup && ((ViewGroup) shade).getChildCount() > 0
                ? ((ViewGroup) shade).getChildAt(0) : null;
        shade.animate().alpha(0f).setDuration(D_LAYER).setInterpolator(EASE_ACCEL).start();
        if (panel != null) {
            float dist = down
                    ? (panel.getHeight() > 0 ? panel.getHeight()
                        : shade.getResources().getDisplayMetrics().heightPixels * 0.5f)
                    : -(panel.getWidth() > 0 ? panel.getWidth()
                        : shade.getResources().getDisplayMetrics().widthPixels * 0.8f);
            android.animation.ObjectAnimator oa = down
                    ? android.animation.ObjectAnimator.ofFloat(panel, "translationY", panel.getTranslationY(), dist)
                    : android.animation.ObjectAnimator.ofFloat(panel, "translationX", panel.getTranslationX(), dist);
            oa.setDuration(D_LAYER);
            oa.setInterpolator(EASE_ACCEL);
            oa.start();
        }
        shade.postDelayed(new Runnable() {
            @Override
            public void run() {
                parent.removeView(shade);
            }
        }, D_LAYER);
    }

    /**
     * 换主题 / 重建后的一次性淡入：消除 recreate 那一瞬的闪白。
     * 【为何】ThemeManager 换色后走 act.recreate()，新 Activity 首帧直出，
     *   深色↔浅色跳变时格外刺眼。挂一层与窗口同大的临时遮罩淡出，
     *   等于把"重建"这一帧盖过去，观感变成平滑过渡。
     * 【注意】遮罩必须不可点、且动画结束后一定移除，否则会吃掉全屏触摸。
     */
    public static void themeFade(Activity act) {
        if (act == null || act.getWindow() == null) {
            return;
        }
        View decor = act.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        final ViewGroup root = (ViewGroup) decor;
        final View cover = new View(act);
        cover.setBackgroundColor(BG);
        cover.setClickable(false);
        root.addView(cover, new ViewGroup.LayoutParams(-1, -1));
        cover.animate().alpha(0f).setDuration(D_PAGE).setInterpolator(EASE_STD)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        root.removeView(cover);
                    }
                }).start();
    }

    /** 淡出后移除（遮罩类浮层的统一关闭）。 */
    public static void fadeOutRemove(final View v) {
        if (v == null) {
            return;
        }
        if (!(v.getParent() instanceof ViewGroup)) {
            return;
        }
        final ViewGroup parent = (ViewGroup) v.getParent();
        v.animate().alpha(0f).setDuration(D_MICRO).setInterpolator(EASE_ACCEL)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        parent.removeView(v);
                    }
                }).start();
    }
}
