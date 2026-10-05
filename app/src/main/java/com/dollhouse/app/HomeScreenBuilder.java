package com.dollhouse.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 【职责】首页全部控件的搭建：标题栏、人偶图、启停按钮、聊天设置输入区、背景与透明度、页脚说明。
 * 【入口】MainActivity.onCreate 里 setContentView(HomeScreenBuilder.build(this))。
 * 【交互】建好的控件写回 MainActivity 的包级字段，供 refresh / ChatSettingsSection 使用；
 *         搭完后依次交给 SettingsPage 切卡片、HomeUi 做二次重排。
 * 【坑】控件顺序与文案是 SettingsPage 分组匹配、HomeUi 重排的契约：
 *       标题文字、三个输入框 hint 前缀、按钮文本一旦改动，首页会整体退回未整理状态。
 */
final class HomeScreenBuilder {

    private HomeScreenBuilder() {
    }

    /**
     * 按「实际显示宽度」解码人偶首屏图。
     * 【为何需要】原实现直接 decodeResource 出 391×512 全图，只为在首页显示 190dp（≈570px
     *   宽屏下更小）；ARGB8888 一张就占 0.76MB，冷启动白掏内存。
     *   这里用 inSampleSize 把解码尺寸压到不超过目标宽度的 2 倍，肉眼无差、内存降到 1/4。
     */
    private static android.graphics.Bitmap decodePet(Context ctx, int targetPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(ctx.getResources(), R.drawable.pet_front, o);
            if (o.outWidth <= 0) {
                return null;
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetPx && sample * 2 <= 8) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeResource(ctx.getResources(), R.drawable.pet_front, o2);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 搭出首页根视图；控件实例通过 act 的包级字段回传。 */
    static View build(final MainActivity act) {
        ScrollView scroll = new ScrollView(act);
        scroll.setBackgroundColor(UiKit.OPTION);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(act.dp(20.0f));
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box, new ViewGroup.LayoutParams(-1, -2));

        // 【v2.10.0】标题美化：字号加大、加粗、加字间距，颜色仍走 UiKit.TITLE（跟随主题）。
        // 【v2.10.1】字号 30sp → 36sp、顶部留白 6dp → 34dp：标题整体下移并再放大一档，
        //   与下方人偶区的距离拉开，视觉重心不再贴着屏幕顶部。
        TextView title = new TextView(act);
        title.setText("Dollhouse");
        title.setTextSize(36.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.06f);
        title.setTextColor(UiKit.TITLE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Math.round(act.dp(34.0f)), 0, Math.round(act.dp(4.0f)));
        box.addView(title);

        TextView subtitle = new TextView(act);
        subtitle.setText("\u70b9\u5979\u8bf4\u8bdd \u00b7 \u62d6\u7740\u8d70 \u00b7 \u957f\u6309\u6253\u5f00\u8fd9\u4e2a\u9762\u677f");
        subtitle.setTextSize(UiKit.FS_SUB);
        subtitle.setTextColor(UiKit.SUB);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, Math.round(act.dp(4.0f)), 0, Math.round(act.dp(14.0f)));
        box.addView(subtitle);

        ImageView pet = new ImageView(act);
        pet.setImageBitmap(decodePet(act, Math.round(act.dp(190.0f))));
        pet.setAdjustViewBounds(true);
        LinearLayout.LayoutParams petLp = new LinearLayout.LayoutParams(Math.round(act.dp(190.0f)), -2);
        petLp.gravity = Gravity.CENTER;
        box.addView(pet, petLp);

        act.status = new TextView(act);
        act.status.setTextSize(UiKit.FS_SUB);
        act.status.setTextColor(UiKit.TITLE);
        act.status.setGravity(Gravity.CENTER);
        act.status.setPadding(0, Math.round(act.dp(12.0f)), 0, Math.round(act.dp(12.0f)));
        box.addView(act.status);

        // 1. 悬浮窗权限
        act.overlayBtn = mkButton(act, "1. \u6388\u4e88\u300c\u663e\u793a\u5728\u5176\u4ed6\u5e94\u7528\u4e0a\u5c42\u300d");
        act.overlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.requestOverlay();
            }
        });
        UiKit.primary(act.overlayBtn, act);
        box.addView(act.overlayBtn);

        // 2. 启动桌宠
        act.startBtn = mkButton(act, "2. \u542f\u52a8\u684c\u5ba0");
        act.startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.onStartClicked();
            }
        });
        box.addView(act.startBtn);

        // 停止桌宠
        act.stopBtn = mkButton(act, "\u505c\u6b62\u684c\u5ba0");
        act.stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(act, PetService.class);
                intent.setAction(PetService.ACTION_STOP);
                act.startService(intent);
                // 【v2.10.0】原用 act.status 做延迟句柄：状态行已被首页移除（不再挂在视图树上），
                //   未 attach 的 View 的 postDelayed 只会排进 runqueue、永远不会执行。
                //   改用 DecorView 当句柄，它任何时候都在树上。
                act.getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        act.refresh();
                    }
                }, 300L);
            }
        });
        box.addView(act.stopBtn);

        // 打开聊天
        Button openChat = mkButton(act, "\u6253\u5f00\u804a\u5929\uff08\u70b9\u684c\u5ba0\u4e5f\u80fd\u8fdb\uff09");
        openChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.startActivity(new Intent(act, ChatActivity.class));
                // 【丝滑】跳全屏聊天页时淡入，不用系统默认的硬切。
                act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });
        box.addView(openChat);

        // 聊天设置分组标题（SettingsPage 按此文本识别分组，不可改）。
        box.addView(sectionTitle(act, "\u804a\u5929\u8bbe\u7f6e\uff08\u4e91\u7aef API\uff09"));

        act.keyInput = mkInput(act, "\u8f93\u5165 API \u5bc6\u94a5", "", true);
        act.modelInput = mkInput(act, "\u4f8b\u5982\uff1adeepseek-v4-flash\uff1b\u70b9\u53f3\u4fa7\u56fe\u6807\u62c9\u53d6\u53ef\u7528\u5217\u8868", "", false);
        act.urlInput = mkInput(act, "\u4f8b\u5982\uff1ahttps://api.openai.com/v1/chat/completions", "", false);
        box.addView(act.keyInput);
        box.addView(act.modelInput);
        box.addView(act.urlInput);

        // 保存 / 测试按钮同排；SettingsProfilePanel 会把这一排整体搬到「模型配置」下方。
        LinearLayout buttonBar = new LinearLayout(act);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        saveLp.topMargin = Math.round(act.dp(8.0f));
        saveLp.rightMargin = Math.round(act.dp(4.0f));
        Button save = new Button(act);
        save.setText("\u4fdd\u5b58\u8bbe\u7f6e");
        styleBarButton(save, act);
        save.setLayoutParams(saveLp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatSettingsSection.saveSettings(act);
            }
        });
        buttonBar.addView(save);

        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        testLp.topMargin = Math.round(act.dp(8.0f));
        testLp.leftMargin = Math.round(act.dp(4.0f));
        Button test = new Button(act);
        test.setText("\u6d4b\u8bd5\u8fde\u63a5");
        styleBarButton(test, act);
        test.setLayoutParams(testLp);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatSettingsSection.testConnection(act);
            }
        });
        buttonBar.addView(test);
        box.addView(buttonBar);

        act.testResult = new TextView(act);
        act.testResult.setTextSize(UiKit.FS_SUB);
        act.testResult.setTextColor(UiKit.TITLE);
        act.testResult.setPadding(0, Math.round(act.dp(10.0f)), 0, 0);
        // 打标签：SettingsProfilePanel 据此把它从卡片尾部摘出来紧贴「测试连接」下方（修复结果看不见）。
        act.testResult.setTag(SettingsPage.TAG_TEST_RESULT);
        box.addView(act.testResult);

        // 操作方式
        box.addView(sectionTitle(act, "\u64cd\u4f5c\u65b9\u5f0f"));
        box.addView(hintText(act, 0,
                "\u00b7 \u5355\u51fb\u5979\uff1a\u8df3\u4e00\u4e0b\uff0c\u5e76\u968f\u673a\u8bf4\u4e00\u53e5\n"
                        + "\u00b7 \u8fde\u70b9\u4e09\u4e0b\uff1a\u53ec\u5524\u8ff7\u4f60\u8f93\u5165\u6846\uff0c\u53d1\u6d88\u606f\u8ddf\u5979\u804a\n"
                        + "\u00b7 \u62d6\u52a8\u5979\uff1a\u62d6\u5230\u5c4f\u5e55\u4e24\u4fa7\u8fb9\u7f18\u4f1a\u5438\u9644\u5e76\u63a2\u5934\uff0c\u4e22\u5728\u4e2d\u95f4\u5c31\u505c\u5728\u539f\u5730"));

        // 聊天背景
        box.addView(sectionTitle(act, "\u804a\u5929\u80cc\u666f"));
        act.bgBtn = mkButton(act, "");
        act.bgBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.pickPurpose = 1;
                PickFileActivity.setListener(act);
                PickFileActivity.setPurpose(PetPrefs.BG_DIR);
                PickFileActivity.start(act);
            }
        });
        box.addView(act.bgBtn);

        Button clearBg = mkButton(act, "\u6e05\u9664\u80cc\u666f");
        clearBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PetPrefs.setChatBackground(act, "");
                act.notifyPetService();
                act.refreshLocalUi();
            }
        });
        box.addView(clearBg);

        box.addView(hintText(act, 0,
                "\u9009\u4e00\u5f20\u56fe\u5f53\u804a\u5929\u9875\u7684\u80cc\u666f\u3002\u4f1a\u81ea\u52a8\u7f29\u5230 1080px \u5b58\u5728\u5e94\u7528\u79c1\u6709\u76ee\u5f55\u91cc\u3002\n"
                        + "\u4e0b\u9762\u7684\u6ed1\u5757\u8c03\u80cc\u666f\u56fe\u7684\u900f\u660e\u5ea6\uff1a\u8c03\u592a\u4f4e\u53ea\u5269\u9762\u677f\u5e95\u8272\uff0c\u8c03\u592a\u9ad8\u767d\u8272\u6c14\u6ce1\u548c\u6df1\u8272\u6587\u5b57\u4f1a\u7cca\u5728\u82b1\u54e8\u7684\u56fe\u4e0a\u3002"));

        act.bgAlphaLabel = new TextView(act);
        act.bgAlphaLabel.setTextSize(UiKit.FS_SUB);
        act.bgAlphaLabel.setTextColor(UiKit.TITLE);
        act.bgAlphaLabel.setPadding(0, Math.round(act.dp(10.0f)), 0, 0);
        box.addView(act.bgAlphaLabel);

        SeekBar alpha = new SeekBar(act);
        alpha.setMax(100);
        alpha.setProgress(PetPrefs.chatBgAlpha(act));
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                PetPrefs.setChatBgAlpha(act, value);
                act.updateBgAlphaLabel(value);
                if (fromUser) {
                    act.notifyPetService();
                }
            }
        });
        box.addView(alpha, new LinearLayout.LayoutParams(-1, -2));
        act.updateBgAlphaLabel(PetPrefs.chatBgAlpha(act));

        TextView footer = hintText(act, 18,
                "\u8bf4\u660e\uff1a\n"
                        + "\u00b7 \u684c\u5ba0\u9760\u524d\u53f0\u670d\u52a1\u8fd0\u884c\uff0c\u901a\u77e5\u680f\u4f1a\u6709\u4e00\u6761\u5e38\u9a7b\u901a\u77e5\uff0c\u70b9\u300c\u9000\u51fa\u684c\u5ba0\u300d\u4e5f\u80fd\u5173\u6389\u3002\n"
                        + "\u00b7 \u677e\u624b\u540e\u4f1a\u81ea\u52a8\u5438\u9644\u5230\u6700\u8fd1\u7684\u5c4f\u5e55\u8fb9\u7f18\uff0c\u4f4d\u7f6e\u4f1a\u88ab\u8bb0\u4f4f\u3002\n"
                        + "\u00b7 \u90e8\u5206\u7cfb\u7edf\uff08MIUI / EMUI / ColorOS\uff09\u9700\u8981\u989d\u5916\u5141\u8bb8\u300c\u540e\u53f0\u5f39\u51fa\u754c\u9762\u300d\u6216\u628a\u672c\u5e94\u7528\u52a0\u5165\u81ea\u542f\u52a8\u767d\u540d\u5355\uff0c\u5426\u5219\u91cd\u542f\u540e\u684c\u5ba0\u4e0d\u4f1a\u81ea\u52a8\u51fa\u73b0\u3002\n"
                        + "\u00b7 \u5979\u4e0d\u4f1a\u8054\u7f51\uff0c\u4e5f\u4e0d\u4f1a\u8bfb\u53d6\u4f60\u7684\u4efb\u4f55\u6570\u636e\u3002");
        box.addView(footer);

        return scroll;
    }

    /** 分组标题：16sp 加粗标题色，上间距 24dp。 */
    static TextView sectionTitle(MainActivity act, String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(16.0f);
        t.setTextColor(UiKit.TITLE);
        t.setPadding(0, Math.round(act.dp(24.0f)), 0, Math.round(act.dp(4.0f)));
        return t;
    }

    /** 说明小字：12sp 副色，可指定上间距。 */
    static TextView hintText(MainActivity act, int topDp, String text) {
        TextView t = new TextView(act);
        t.setTextSize(UiKit.FS_SUB);
        t.setTextColor(UiKit.SUB);
        if (topDp > 0) {
            t.setPadding(0, Math.round(act.dp((float) topDp)), 0, 0);
        }
        t.setText(text);
        return t;
    }

    /** 次按钮：白底描边、全宽、上间距 8dp。 */
    static Button mkButton(MainActivity act, String text) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        UiKit.secondary(b, act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Math.round(act.dp(10.0f));
        b.setLayoutParams(lp);
        return b;
    }

    /** 单行输入框；password=true 时掩码显示。 */
    static EditText mkInput(MainActivity act, String hint, String value, boolean password) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextSize(UiKit.FS_BTN);
        e.setSingleLine(true);
        e.setInputType(password ? 524433 : 524289);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Math.round(act.dp(6.0f));
        e.setLayoutParams(lp);
        return e;
    }

    /** 弹窗展示一段可选中的等宽文本（调试与故障排查用）。 */
    static void showText(MainActivity act, String title, String body) {
        TextView t = new TextView(act);
        t.setText(body == null || body.isEmpty() ? "(\u7a7a)" : body);
        t.setTextSize(UiKit.FS_TINY);
        t.setTextIsSelectable(true);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(UiKit.TITLE);
        int pad = Math.round(act.dp(12.0f));
        t.setPadding(pad, pad, pad, pad);
        t.setBackground(UiKit.rowBg(act, UiKit.FIELD));
        ScrollView scroll = new ScrollView(act);
        scroll.addView(t);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,
                Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.5f)));
        UiKit.showDialog(act, title, scroll, "\u5173\u95ed", null, null, null);
    }

    /** 同排按钮统一样式：走 UiKit 次按钮（白底描边），与首页其它按钮保持一致。 */
    private static void styleBarButton(Button b, MainActivity act) {
        b.setAllCaps(false);
        b.setTextSize(UiKit.FS_BTN);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = Math.round(act.dp(14.0f));
        b.setPadding(pad, pad, pad, pad);
        UiKit.secondary(b, act);
    }
}
