package com.dollhouse.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】聊天面板左侧抽屉：对话列表（新建 / 切换 / 重命名 / 删除）。
 *
 * 【入口】ChatPanel 的「☰」入口调 open()；底部操作条复用本类的 confirm / prompt。
 *
 * 【交互】会话数据走 ChatSessions，当前会话历史由 ChatPanel 持有；
 *        切换会话后由 ChatPanel.reloadHistory 重铺气泡。
 *
 * 【扩展】再加一个分区（比如收藏）只需在 buildContent 里多 add 一段，不用动挂载逻辑。
 *
 * 【坑】本类不自建 Activity，也不用系统 Dialog —— 桌宠模式（宿主 PetService，走迷你输入框）
 *        的 Context 链里没有 Activity，系统弹窗拿不到 window token。所以遮罩、确认框、输入框
 *        全部是叠在 layer（浮窗 root / Activity 的 content）上的普通 View。
 *        挂载层是从 ChatPanel 的父链里往上找的第一个 FrameLayout，ChatPanel 自身的
 *        父子结构因此不需要改。
 */
final class ChatDrawer {

    /** 抽屉宽度占屏幕宽度的比例。 */
    private static final float W_RATIO = 0.78f;

    private final ChatPanel host;
    private ViewGroup layer;
    private FrameLayout shade;
    private LinearLayout content;

    ChatDrawer(ChatPanel host) {
        this.host = host;
    }

    private int dp(float f) {
        return UiKit.dp(this.host.getContext(), f);
    }

    boolean isOpen() {
        FrameLayout frame = this.shade;
        return frame != null && frame.getParent() != null;
    }

    ViewGroup layer() {
        return this.layer;
    }

    /** 在给定层上铺开抽屉（重复调用只会重建内容）。 */
    void open(ViewGroup parent) {
        this.layer = parent;
        Context ctx = this.host.getContext();
        if (this.shade == null) {
            FrameLayout shadeView = new FrameLayout(ctx);
            this.shade = shadeView;
            shadeView.setBackgroundColor(UiKit.SCRIM);
            shadeView.setClickable(true);
            shadeView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    close();
                }
            });
        }
        if (this.shade.getParent() != null) {
            ((ViewGroup) this.shade.getParent()).removeView(this.shade);
        }
        // 【坑】shade 是复用的：不清子 View 的话，每次 open() 都会再叠一层 panel，
        // 旧 panel 上的点击监听跟着一起累积。摘 shade 只断开它和父级的关系，子级还在。
        this.shade.removeAllViews();
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        parent.addView(this.shade, slp);

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(UiKit.CARD);
        panel.setClickable(true);
        int pad = dp(12.0f);
        panel.setPadding(pad, dp(14.0f), pad, dp(10.0f));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("对话");
        title.setTextSize(UiKit.FS_TITLE);
        title.setTextColor(UiKit.TITLE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView add = smallButton(ctx, "＋ 新建");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatSessions.create(host.getContext());
                host.reloadHistory();
                refresh();
            }
        });
        head.addView(add);
        panel.addView(head);
        // 【v0.0.1】人偶专属入口：固定顶格在标题行下方。
        //   点一下切到「人偶」会话 —— 以后这个会话里说的话就是直接跟人偶说；
        //   其他会话的回复不再往人偶头顶气泡上送（闸门见 ChatPanel 的 PetBus.say）。
        Button petEntry = flatButton(ctx, "与人偶的对话");
        // 【图标语义】用星标区分「当前在这个池里」：实心亮星 = 已切到人偶池，空心灰星 = 还在自聊池。
        //   比裸 ● 更能一眼分辨，也与聊天抽屉整体的 Lucide 描边风格一致。
        Icons.stateIcon(petEntry, ChatSessions.isPet(ctx) ? Icons.IC_STAR : Icons.IC_STAR_OFF,
                ChatSessions.isPet(ctx) ? UiKit.ACC : UiKit.SUB, 14.0f, 5);
        LinearLayout.LayoutParams pelp = new LinearLayout.LayoutParams(-1, -2);
        pelp.topMargin = dp(8.0f);
        petEntry.setLayoutParams(pelp);
        petEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 【池切换】点一下进人偶池（前导 ● 点亮），再点一下回自聊池（● 消失）。
                //   不关抽屉：留着让用户直接看到圆圈变化与列表随池切换。
                if (ChatSessions.isPet(ctx)) {
                    ChatSessions.setPool(ctx, ChatSessions.POOL_SELF);
                } else {
                    ChatSessions.ensurePet(ctx);
                    ChatSessions.setPool(ctx, ChatSessions.POOL_PET);
                }
                host.reloadHistory();
                refresh();
                // 【同步图标】池切换后星标要跟着变（实心 / 空心），只改文字会留下过期的旧状态。
                boolean petOn = ChatSessions.isPet(ctx);
                petEntry.setText("与人偶的对话");
                Icons.stateIcon(petEntry, petOn ? Icons.IC_STAR : Icons.IC_STAR_OFF,
                        petOn ? UiKit.ACC : UiKit.SUB, 14.0f, 5);
            }
        });
        panel.addView(petEntry);

        ScrollView sc = new ScrollView(ctx);
        this.content = new LinearLayout(ctx);
        this.content.setOrientation(LinearLayout.VERTICAL);
        sc.addView(this.content, new ViewGroup.LayoutParams(-1, -2));
        panel.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(8.0f);
        actions.setLayoutParams(alp);
        // 【v2.3】「立即总结」已搬到加号面板的「记忆」页（与「打开记忆库」同行），此处只留一枚整行按钮。
        Button clear = flatButton(ctx, "清空当前对话");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clear.setLayoutParams(clp);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
                host.confirmClearChat();
            }
        });
        actions.addView(clear);
        panel.addView(actions);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                (int) (parent.getResources().getDisplayMetrics().widthPixels * W_RATIO), -1);
        plp.gravity = Gravity.START;
        this.shade.addView(panel, plp);
        refresh();
        // 抽屉：遮罩淡入 + 面板自左侧推入（原先瞬现）。
        panel.post(new Runnable() {
            @Override
            public void run() {
                UiKit.slideInLeft(shade, panel);
            }
        });
    }

    void close() {
        View view = this.shade;
        if (view != null && view.getParent() != null) {
            UiKit.slideOutLeft(view);
        }
    }

    /** 重建抽屉内容（会话清单 + 摘要清单）。 */
    void refresh() {
        LinearLayout box = this.content;
        if (box == null) {
            return;
        }
        Context ctx = this.host.getContext();
        // 【丝滑】重铺前记住滚动位置，铺完恢复，避免刷新后跳回顶部。
        final ScrollView keepSc = box.getParent() instanceof ScrollView
                ? (ScrollView) box.getParent() : null;
        final int keepY = keepSc == null ? 0 : keepSc.getScrollY();
        box.removeAllViews();

        String cur = ChatSessions.currentId(ctx);
        JSONArray arr = ChatSessions.list(ctx);
        section(ctx, box, "历史对话（" + arr.length() + "）");
        for (int i = arr.length() - 1; i >= 0; i--) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final String id = o.optString("id");
            box.addView(convRow(ctx, o, id.equals(cur)));
        }

        // 【丝滑】行分批淡入；并恢复重铺前的滚动位置。
        UiKit.staggerCapped(box, 10);
        if (keepSc != null) {
            keepSc.post(new Runnable() {
                @Override
                public void run() {
                    keepSc.scrollTo(0, keepY);
                }
            });
        }
        // 【v2.3】「本会话摘要」分区已移除：摘要统一由聊天记录里的「ⓘ 历史对话摘要」呈现。
    }

    private LinearLayout convRow(final Context ctx, final JSONObject o, boolean active) {
        final String id = o.optString("id");
        final String title = o.optString("title", "新的对话");
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(UiKit.round(active ? UiKit.CHAT_CHIP_ON : UiKit.SOFT, ctx, 10));
        UiKit.press(row);
        int pad = dp(12.0f);
        row.setPadding(pad, dp(10.0f), pad, dp(10.0f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6.0f);
        row.setLayoutParams(lp);

        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(title);
        name.setTextSize(UiKit.FS_BTN);
        name.setTextColor(UiKit.TITLE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // 【图标语义】当前会话不再靠裸 ● 表示，改用实心 / 空心星标（与顶部「与人偶的对话」同一套口径）。
        Icons.stateIcon(name, active ? Icons.IC_STAR : Icons.IC_STAR_OFF,
                active ? UiKit.ACC : UiKit.SUB, 13.0f, 5);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView edit = smallButton(ctx, "改名");
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prompt("重命名对话", title, new OnText() {
                    @Override
                    public void onText(String value) {
                        ChatSessions.rename(ctx, id, value);
                        refresh();
                    }
                });
            }
        });
        line.addView(edit);
        TextView del = smallButton(ctx, "删除");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = dp(6.0f);
        del.setLayoutParams(dlp);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm("删除对话", "「" + title + "」的聊天记录会被永久删掉，不能恢复。",
                        "删除", new Runnable() {
                            @Override
                            public void run() {
                                ChatSessions.delete(ctx, id);
                                host.reloadHistory();
                                refresh();
                            }
                        });
            }
        });
        line.addView(del);
        row.addView(line);

        TextView meta = new TextView(ctx);
        meta.setText(fmtTime(o.optLong("updated", 0L)));
        meta.setTextSize(UiKit.FS_TINY);
        meta.setTextColor(UiKit.SUB);
        meta.setPadding(0, dp(4.0f), 0, 0);
        row.addView(meta);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (id.equals(ChatSessions.currentId(ctx))) {
                    close();
                    return;
                }
                ChatSessions.switchTo(ctx, id);
                host.reloadHistory();
                close();
            }
        });
        return row;
    }

    private void section(Context ctx, LinearLayout box, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(UiKit.FS_SUB);
        t.setTextColor(UiKit.SUB);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4.0f), dp(14.0f), 0, 0);
        box.addView(t);
    }

    private TextView smallButton(Context ctx, String text) {
        return UiKit.chip(ctx, text);
    }

    private Button flatButton(Context ctx, String text) {
        return UiKit.btn(ctx, text, false);
    }

    /* ------------------------- 通用确认框 / 输入框 ------------------------- */

    interface OnText {
        void onText(String value);
    }

    /** 叠一层确认框。没有挂载层时静默忽略（不该发生：抽屉一定先打开过）。 */
    void confirm(String title, String message, String okText, final Runnable onOk) {
        final ViewGroup parent = this.layer;
        if (parent == null) {
            return;
        }
        Context ctx = this.host.getContext();
        DialogShell sh = newDialogShell(ctx, title);

        TextView t2 = new TextView(ctx);
        t2.setText(message);
        t2.setTextSize(UiKit.FS_SUB);
        t2.setTextColor(UiKit.SUB);
        t2.setLineSpacing(dp(3.0f), 1.0f);
        t2.setPadding(0, dp(10.0f), 0, 0);
        sh.box.addView(t2);

        addCancel(sh, ctx);
        addOk(sh, ctx, okText, onOk);
        mountDialog(parent, sh);

    }

    /** 叠一层输入框（用于重命名）。 */
    void prompt(String title, String initial, final OnText onText) {
        final ViewGroup parent = this.layer;
        if (parent == null) {
            return;
        }
        Context ctx = this.host.getContext();
        DialogShell sh = newDialogShell(ctx, title);

        final EditText input = new EditText(ctx);
        input.setText(initial == null ? "" : initial);
        input.setSingleLine(true);
        UiKit.field(input, ctx);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = dp(12.0f);
        input.setLayoutParams(ilp);
        sh.box.addView(input);

        addCancel(sh, ctx);
        Button ok = UiKit.dialogButton(ctx, "确定", true);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
        olp.leftMargin = dp(10.0f);
        ok.setLayoutParams(olp);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                UiKit.fadeOutRemove(sh.overlay);
                if (onText != null) {
                    onText.onText(value);
                }
            }
        });
        sh.bar.addView(ok);
        mountDialog(parent, sh);

    }


    /** 弹层骨架的句柄：遮罩、卡片、按钮条。 */
    private static final class DialogShell {
        FrameLayout overlay;
        LinearLayout box;
        LinearLayout bar;
    }

    /** 建一个弹层骨架：遮罩（拦点击）+ 卡片（圆角、内边距）+ 加粗标题 + 空按钮条。 */
    private DialogShell newDialogShell(Context ctx, String title) {
        DialogShell sh = new DialogShell();
        sh.overlay = new FrameLayout(ctx);
        sh.overlay.setBackgroundColor(UiKit.SCRIM);
        sh.overlay.setClickable(true);
        sh.box = new LinearLayout(ctx);
        sh.box.setOrientation(LinearLayout.VERTICAL);
        sh.box.setBackground(UiKit.round(UiKit.CARD, ctx, 16));
        int pad = dp(18.0f);
        sh.box.setPadding(pad, pad, pad, dp(14.0f));
        sh.box.setClickable(true);
        TextView t1 = new TextView(ctx);
        t1.setText(title);
        t1.setTextSize(UiKit.FS_TITLE);
        t1.setTextColor(UiKit.TITLE);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        sh.box.addView(t1);
        sh.bar = new LinearLayout(ctx);
        sh.bar.setOrientation(LinearLayout.HORIZONTAL);
        sh.bar.setGravity(Gravity.END);
        sh.bar.setPadding(0, dp(16.0f), 0, 0);
        return sh;
    }

    /** 取消键：仅淡出移除遮罩。 */
    private void addCancel(DialogShell sh, Context ctx) {
        Button cancel = UiKit.dialogButton(ctx, "取消", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.fadeOutRemove(sh.overlay);
            }
        });
        sh.bar.addView(cancel);
    }

    /** 确认键：先淡出移除遮罩，再跑回调。 */
    private void addOk(DialogShell sh, Context ctx, String okText, final Runnable onOk) {
        Button ok = UiKit.dialogButton(ctx, okText, true);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
        olp.leftMargin = dp(10.0f);
        ok.setLayoutParams(olp);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.fadeOutRemove(sh.overlay);
                if (onOk != null) {
                    onOk.run();
                }
            }
        });
        sh.bar.addView(ok);
    }

    /** 挂载弹层：按钮条入卡片、固定屏宽 86% 居中、遮罩淡入 + 卡片弹簧放大。 */
    private void mountDialog(ViewGroup parent, DialogShell sh) {
        sh.box.addView(sh.bar);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                (int) (parent.getResources().getDisplayMetrics().widthPixels * 0.86f), -2);
        blp.gravity = Gravity.CENTER;
        sh.overlay.addView(sh.box, blp);
        parent.addView(sh.overlay, new FrameLayout.LayoutParams(-1, -1));
        // 【丝滑】弹层淡入 + 卡片轻微放大，不再瞬现。
        sh.overlay.setAlpha(0f);
        sh.box.setScaleX(0.94f);
        sh.box.setScaleY(0.94f);
        sh.overlay.animate().alpha(1f).setDuration(UiKit.D_LAYER).setInterpolator(UiKit.EASE_DECEL).start();
        // 【弹簧】卡片放大走 snappy（ζ=0.73），与 overlay 淡入同帧开始；
        //   overlay 的 alpha 保持线性淡入不动（透明度过冲会穿帮）。
        Springs.drive(Springs.snappy(), new Springs.Listener() {
            @Override
            public void onUpdate(float p) {
                float s = Springs.lerp(0.94f, 1.0f, p);
                sh.box.setScaleX(s);
                sh.box.setScaleY(s);
            }
            @Override
            public void onEnd() {
                sh.box.setScaleX(1f);
                sh.box.setScaleY(1f);
            }
        });
    }
    private static String fmtTime(long ts) {
        if (ts <= 0L) {
            return "—";
        }
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ts));
        } catch (Throwable unused) {
            return "—";
        }
    }
}