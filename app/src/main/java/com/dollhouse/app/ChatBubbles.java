package com.dollhouse.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 【职责】聊天气泡的构造与呈现：气泡本体、图片附件、重新生成操作条、思考中占位。
 *
 * 【入口】只由 ChatPanel 调用（ChatPanel 保留同名薄壳，内部调用点不用改）。
 *
 * 【交互】点击重新生成时回调回 host.regenerate()；
 *        消息列表与滚动容器由 host 持有（messages / scroller），本类不缓存状态。
 *
 * 【扩展】新增一种气泡类型或操作按钮 = 在本类加一个静态工厂，不动 ChatPanel。
 *
 * 【坑】host.history 只读；本类绝不修改历史（写历史一律走 ChatPanel.push / saveHistory）。
 */
final class ChatBubbles {
    static void addBubble(ChatPanel host, String str, boolean z) {
        ChatBubbles.addBubble(host, str, z, -1, null);
    }
    static void addBubble(ChatPanel host, String str, boolean z, int i) {
        ChatBubbles.addBubble(host, str, z, i, null);
    }
    static void addBubble(ChatPanel host, String str, boolean z, int i, String str2) {
        LinearLayout linearLayout = new LinearLayout(host.getContext());
        linearLayout.setOrientation(0);
        linearLayout.setGravity(z ? 8388613 : 8388611);
        LinearLayout linearLayout2 = new LinearLayout(host.getContext());
        linearLayout2.setOrientation(1);
        linearLayout2.setPadding(UiKit.dp(host.getContext(), 12.0f), UiKit.dp(host.getContext(), 8.0f), UiKit.dp(host.getContext(), 12.0f), UiKit.dp(host.getContext(), 8.0f));
        linearLayout2.setBackground(UiKit.round(z ? UiKit.CHAT_BUBBLE_USER : UiKit.CARD,
                host.getContext(), 15));
        int i2 = (int) (host.getResources().getDisplayMetrics().widthPixels * 0.72d);
        if (str2 != null && ImageStore.exists(host.getContext(), str2)) {
            ImageView imageView = new ImageView(host.getContext());
            imageView.setAdjustViewBounds(true);
            imageView.setMaxWidth(i2);
            imageView.setMaxHeight((int) (host.getResources().getDisplayMetrics().heightPixels * 0.32f));
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // 【圆角】附件图原来是不裁剪的矩形，与气泡圆角对不上；按 12dp 圆角裁剪。
            imageView.setClipToOutline(true);
            imageView.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            UiKit.dp(host.getContext(), 12.0f));
                }
            });
            imageView.setImageBitmap(ImageStore.loadScaled(host.getContext(), str2, 720));
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, -2);
            layoutParams.bottomMargin = UiKit.dp(host.getContext(), 6.0f);
            linearLayout2.addView(imageView, layoutParams);
        }
        if (str != null && !str.trim().isEmpty()) {
            TextView textView = new TextView(host.getContext());
            textView.setText(str);
            textView.setTextSize(UiKit.FS_BTN);
            textView.setTextIsSelectable(true);
            textView.setMaxWidth(i2);
            textView.setTextColor(z ? UiKit.CARD : UiKit.TITLE);
            linearLayout2.addView(textView);
        }
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-2, -2);
        layoutParams2.topMargin = UiKit.dp(host.getContext(), 3.0f);
        layoutParams2.bottomMargin = UiKit.dp(host.getContext(), 3.0f);
        linearLayout.addView(linearLayout2, layoutParams2);
        host.messages.addView(linearLayout, new LinearLayout.LayoutParams(-1, -2));
        if (!z && i >= 0 && i == host.history.length() - 1) {
            host.messages.addView(ChatBubbles.buildActions(host), new LinearLayout.LayoutParams(-1, -2));
        }
        // 【v2.8】补回「更早的历史」的那次重铺压住自动滚底：那批挂在顶部，一滚到底会立刻满足
        //        「已经到列表底部」的收回判据，展开态刚建立就被收回去（功能等于失效）。
        if (!host.holdScroll) {
            ChatBubbles.scrollToBottom(host);
        }
    }
    // 【死代码】加宽 chip 构造函数，全工程无调用方；smali 反编译残留，保留以对齐原始行为。
    static TextView wideChip(ChatPanel host, String str, View.OnClickListener onClickListener) {
        TextView textView = new TextView(host.getContext());
        textView.setText(str);
        textView.setTextSize(UiKit.FS_CHIP);
        textView.setPadding(UiKit.dp(host.getContext(), 11.0f), UiKit.dp(host.getContext(), 6.0f), UiKit.dp(host.getContext(), 11.0f), UiKit.dp(host.getContext(), 6.0f));
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setCornerRadius(UiKit.dp(host.getContext(), UiKit.RADIUS_CHIP));
        gradientDrawable.setColor(UiKit.CHAT_CHIP_BG);
        textView.setBackground(gradientDrawable);
        textView.setTextColor(UiKit.CHAT_CHIP_FG);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, -2);
        layoutParams.rightMargin = UiKit.dp(host.getContext(), 6.0f);
        textView.setLayoutParams(layoutParams);
        textView.setOnClickListener(onClickListener);
        return textView;
    }
    static LinearLayout buildActions(final ChatPanel host) {
        LinearLayout linearLayout = new LinearLayout(host.getContext());
        linearLayout.setOrientation(0);
        linearLayout.setGravity(8388611);
        linearLayout.setPadding(UiKit.dp(host.getContext(), 2.0f), 0, 0, UiKit.dp(host.getContext(), 6.0f));
        linearLayout.addView(actionChip(host, "↻ 重新生成", false, new View.OnClickListener() {            @Override
            public void onClick(View view) {
                host.regenerate();
            }
        }));
        // 【交互】复制看上一条助手回复：取 history 里最后一条 assistant 正文。
        linearLayout.addView(actionChip(host, "⧉ 复制", false, new View.OnClickListener() {            @Override
            public void onClick(View view) {
                host.copyLastReply();
            }
        }));
        return linearLayout;
    }

    /** 把文本塞进系统剪贴板（失败只提示，不抛）。 */
    static void copyText(ChatPanel host, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    host.getContext().getSystemService("clipboard");
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Dollhouse", text));
        } catch (Throwable unused) {
        }
    }
    static TextView actionChip(ChatPanel host, String str, boolean z, View.OnClickListener onClickListener) {
        TextView textView = new TextView(host.getContext());
        textView.setText(str);
        textView.setTextSize(UiKit.FS_CHIP);
        textView.setPadding(UiKit.dp(host.getContext(), 10.0f), UiKit.dp(host.getContext(), 5.0f), UiKit.dp(host.getContext(), 10.0f), UiKit.dp(host.getContext(), 5.0f));
        textView.setBackground(UiKit.round(z ? UiKit.CHAT_CHIP_ON : UiKit.CHAT_CHIP_OFF,
                host.getContext(), UiKit.RADIUS_CHIP));
        textView.setTextColor(z ? UiKit.CHAT_CHIP_FG : UiKit.CHAT_CHIP_MUTE);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, -2);
        layoutParams.rightMargin = UiKit.dp(host.getContext(), 6.0f);
        textView.setLayoutParams(layoutParams);
        textView.setOnClickListener(onClickListener);
        return textView;
    }
    // 滚到消息区底部。
    static void scrollToBottom(ChatPanel host) {
        host.scroller.post(new Runnable() {            @Override
            public void run() {
                host.scroller.fullScroll(130);
            }
        });
    }
    // 插入「思考中…」占位气泡。
    static void addThinking(ChatPanel host, String str) {
        LinearLayout linearLayout = new LinearLayout(host.getContext());
        linearLayout.setGravity(8388611);
        TextView textView = new TextView(host.getContext());
        textView.setText(str);
        textView.setTextSize(UiKit.FS_BTN);
        textView.setTextColor(UiKit.CHAT_CHIP_MUTE);
        textView.setPadding(UiKit.dp(host.getContext(), 12.0f), UiKit.dp(host.getContext(), 8.0f), UiKit.dp(host.getContext(), 12.0f), UiKit.dp(host.getContext(), 8.0f));
        textView.setBackground(UiKit.round(UiKit.CHAT_ACTION_BG, host.getContext(), 15));
        linearLayout.addView(textView);
        host.messages.addView(linearLayout);
        host.thinkingView = linearLayout;
        host.thinkingLabel = textView;
        // 【v2.8·N4】与 addBubble 同一道门：展开重铺期间不许把视图推到底，
        //        否则补回批一挂上去就满足「已到列表底部」的收回判据。
        if (!host.holdScroll) {
            ChatBubbles.scrollToBottom(host);
        }
    }
    // 更新占位气泡的文字。
    static void setThinkingLabel(ChatPanel host, String str) {
        TextView textView = host.thinkingLabel;
        if (textView != null) {
            textView.setText(str);
            if (!host.holdScroll) {
                ChatBubbles.scrollToBottom(host);
            }
        }
    }
    // 移除占位气泡。
    static void removeThinking(ChatPanel host) {
        View view = host.thinkingView;
        if (view != null) {
            host.messages.removeView(view);
            host.thinkingView = null;
            host.thinkingLabel = null;
        }
    }
    /**
     * 「ⓘ 历史对话摘要」分割标题：两侧细线 + 居中标签，点一下展开/收起摘要全文。
     * 【位置】铺在聊天记录里早期对话被压缩的位置（历史头部 kind=summary 那条的位置）。
     */
    static View addSummaryDivider(ChatPanel host, String text) {
        Context ctx = host.getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(1);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = UiKit.dp(ctx, 10.0f);
        blp.bottomMargin = UiKit.dp(ctx, 2.0f);
        box.setLayoutParams(blp);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(0);
        head.setGravity(16);
        head.addView(sumLine(ctx), new LinearLayout.LayoutParams(0, Math.max(1, UiKit.dp(ctx, 1.0f)), 1.0f));
        final TextView label = new TextView(ctx);
        label.setText("ⓘ 历史对话摘要");
        label.setTextSize(UiKit.FS_TINY);
        label.setTextColor(UiKit.SUB);
        label.setGravity(17);
        label.setPadding(UiKit.dp(ctx, 10.0f), UiKit.dp(ctx, 3.0f), UiKit.dp(ctx, 10.0f), UiKit.dp(ctx, 3.0f));
        head.addView(label, new LinearLayout.LayoutParams(-2, -2));
        head.addView(sumLine(ctx), new LinearLayout.LayoutParams(0, Math.max(1, UiKit.dp(ctx, 1.0f)), 1.0f));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        final TextView body = new TextView(ctx);
        body.setText(text == null ? "" : text);
        body.setTextSize(UiKit.FS_SUB);
        body.setTextColor(UiKit.SUB);
        body.setTextIsSelectable(true);
        body.setLineSpacing(UiKit.dp(ctx, 3.0f), 1.0f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = UiKit.dp(ctx, 4.0f);
        tlp.leftMargin = UiKit.dp(ctx, 6.0f);
        tlp.rightMargin = UiKit.dp(ctx, 6.0f);
        body.setLayoutParams(tlp);
        body.setVisibility(8);
        box.addView(body);

        // 【交互】默认只露分割标题一行，点它才展开摘要全文。
        if (!body.getText().toString().trim().isEmpty()) {
            head.setClickable(true);
            UiKit.press(head);
            head.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean show = body.getVisibility() != 0;
                    body.setVisibility(show ? 0 : 8);
                    label.setText(show ? "ⓘ 历史对话摘要（点击收起）" : "ⓘ 历史对话摘要");
                }
            });
        }
        host.messages.addView(box, new LinearLayout.LayoutParams(-1, -2));
        // 【交互】返回节点本身：总结完成时宿主据此把视图滚到这条分割线（见 ChatPanel.scrollToSummary）。
        return box;
    }

    /** 分割标题两侧的细线。 */
    private static View sumLine(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(UiKit.LINE);
        return v;
    }

    /**
     * 聊天记录最顶部的「点击加载更早的历史记录」。
     * 【语义】暂存批只能补一次，补回后 ChatHistoryStore 会清空暂存位，本入口随之消失。
     */
    static void addLoadEarlier(ChatPanel host, View.OnClickListener onClickListener) {
        LinearLayout row = new LinearLayout(host.getContext());
        row.setOrientation(0);
        row.setGravity(17);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = UiKit.dp(host.getContext(), 4.0f);
        row.setLayoutParams(rlp);
        TextView t = new TextView(host.getContext());
        t.setText("点击加载更早的历史记录");
        t.setTextSize(UiKit.FS_CHIP);
        t.setTextColor(UiKit.CHAT_CHIP_FG);
        t.setPadding(UiKit.dp(host.getContext(), 12.0f), UiKit.dp(host.getContext(), 6.0f),
                UiKit.dp(host.getContext(), 12.0f), UiKit.dp(host.getContext(), 6.0f));
        t.setBackground(UiKit.round(UiKit.CHAT_CHIP_BG, host.getContext(), UiKit.RADIUS_CHIP));
        t.setClickable(true);
        UiKit.press(t);
        t.setOnClickListener(onClickListener);
        row.addView(t);
        host.messages.addView(row);
    }
}
