package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】「模型配置」区域的全部界面：标题行 + 新建/改名/删除 + 下拉列表 + 三个输入框。
 * 【入口】ApiSectionTuner.tidy() 在聊天设置卡片里调用 buildProfileSection()。
 * 【交互】数据全部走 SettingsProfiles；按钮由 SettingsPage.findButton 装配。
 * 【坑】askName 的输入框必须用 UiKit 风格背景，用原生样式会和 App 其它输入框格格不入。
 */
public final class SettingsProfilePanel {
    private static final String LOG_TAG = "Dollhouse";

    private SettingsProfilePanel() {
    }

    // 小胶囊按钮（新建/改名/删除）：白底 + 淡描边 + 深字，与卡片底色拉开层次。
    private static TextView pill(Context ctx, String s) {
        return UiKit.outlineChip(ctx, s);
    }

    // 重建配置下拉列表，并标出当前选中项。
    private static void refreshProfiles(Context ctx, LinearLayout panel, TextView curLabel,
                                        EditText[] inputs) {
        panel.removeAllViews();
        final JSONArray a = SettingsProfiles.loadProfiles(ctx);
        String cur = SettingsProfiles.readPref(ctx, SettingsProfiles.KEY_CURRENT);
        if (SettingsProfiles.indexOfName(a, cur) < 0) {
            JSONObject first = a.optJSONObject(0);
            cur = first == null ? "\u9ed8\u8ba4\u914d\u7f6e" : first.optString("n", "\u9ed8\u8ba4\u914d\u7f6e");
            SettingsProfiles.writePref(ctx, SettingsProfiles.KEY_CURRENT, cur);
        }
        curLabel.setText(cur);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final String name = o.optString("n", "");
            TextView row = new TextView(ctx);
            row.setText(name.equals(SettingsProfiles.readPref(ctx, SettingsProfiles.KEY_CURRENT)) ? name + "\u3000\u2713" : name);
            row.setTextSize(UiKit.FS_BTN);
            row.setTextColor(UiKit.TITLE);
            row.setClickable(true);
            row.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 10), UiKit.dp(ctx, 12), UiKit.dp(ctx, 10));
            row.setBackground(UiKit.rowBg(ctx, UiKit.CARD));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = UiKit.dp(ctx, 2);
            row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SettingsProfiles.switchTo(ctx, name, inputs, null);
                    panel.setVisibility(View.GONE);
                    SettingsProfilePanel.refreshProfiles(ctx, panel, curLabel, inputs);
                }
            });
            panel.addView(row);
        }
    }

    private static void askName(Activity act, String title, String init, final SettingsPage.NameCallback cb) {
        if (act == null) {
            return;
        }
        final EditText in = new EditText(act);
        in.setText(init == null ? "" : init);
        in.setSelection(in.getText().length());
        in.setSingleLine(true);
        UiKit.field(in, act);
        UiKit.showDialog(act, title, in, "\u786e\u5b9a", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String s = in.getText() == null ? "" : in.getText().toString().trim();
                if (s.length() > 0) {
                    cb.onName(s);
                }
            }
        }, "\u53d6\u6d88", null);
    }

    /**
     * API 卡片顶部的「模型配置」：多套配置互不影响，
     * 可新建、改名、删除，点当前配置那行展开切换。
     */
    public static View buildProfileSection(final Context ctx, final EditText urlIn,
                                            final EditText keyIn, final EditText modelIn,
                                            List<View> tail) {
        final Activity act = ctx instanceof Activity ? (Activity) ctx : UiKit.findActivity(ctx);
        final EditText[] inputs = new EditText[]{urlIn, keyIn, modelIn};

        // 「保存设置」按钮不再需要：输入即存，配置项实时同步。
        // 「测试连接」上移到模型配置下方。原布局里这两个按钮与同排的
        // 结果文字被包在一层横向容器里，必须把那层容器整体从 tail 摘出。
        Button save = SettingsPage.findButton(tail, "\u4fdd\u5b58");
        Button test = SettingsPage.findButton(tail, "\u6d4b\u8bd5");
        ViewGroup testBar = null;
        if (test != null && test.getParent() instanceof ViewGroup) {
            testBar = (ViewGroup) test.getParent();
        }
        if (save != null && save.getParent() instanceof ViewGroup) {
            ((ViewGroup) save.getParent()).removeView(save);
        }
        if (testBar != null) {
            tail.remove(testBar);
        } else if (test != null) {
            tail.remove(test);
        }
        // ③ 修复：测试结果原来排在 tail 末尾，被加到卡片最底部，按钮上移后结果就看不见了。
        //        这里把它从 tail 摘出来，稍后紧跟按钮插入。
        View resultView = null;
        for (int i = 0; i < tail.size(); i++) {
            View v = tail.get(i);
            if (SettingsPage.TAG_TEST_RESULT.equals(v.getTag())) {
                resultView = v;
                tail.remove(i);
                break;
            }
        }
        final View testResult = resultView;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(-1, -2);
        boxLp.topMargin = UiKit.dp(ctx, 6);
        box.setLayoutParams(boxLp);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView lb = new TextView(ctx);
        lb.setText("\u6a21\u578b\u914d\u7f6e");
        lb.setTextSize(UiKit.FS_SUB);
        lb.setTextColor(UiKit.SUB);
        head.addView(lb, new LinearLayout.LayoutParams(0, -2, 1.0f));

        final TextView bNew = pill(ctx, "+ \u65b0\u5efa");
        final TextView bRen = pill(ctx, "\u6539\u540d");
        final TextView bDel = pill(ctx, "\u5220\u9664");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.leftMargin = UiKit.dp(ctx, 6);
        head.addView(bNew, plp);
        LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(-2, -2);
        plp2.leftMargin = UiKit.dp(ctx, 6);
        head.addView(bRen, plp2);
        LinearLayout.LayoutParams plp3 = new LinearLayout.LayoutParams(-2, -2);
        plp3.leftMargin = UiKit.dp(ctx, 6);
        head.addView(bDel, plp3);
        box.addView(head);

        TextView hint = new TextView(ctx);
        hint.setText("\u591a\u5957\u914d\u7f6e\u4e92\u4e0d\u5f71\u54cd\uff0c\u53ef\u5206\u522b\u4fdd\u5b58\u4e0d\u540c\u7aef\u70b9\u4e0e\u5bc6\u94a5");
        hint.setTextSize(UiKit.FS_TINY);
        hint.setTextColor(UiKit.SUB);
        hint.setPadding(UiKit.dp(ctx, 2), UiKit.dp(ctx, 4), 0, 0);
        box.addView(hint);

        final LinearLayout sel = new LinearLayout(ctx);
        sel.setOrientation(LinearLayout.HORIZONTAL);
        sel.setGravity(Gravity.CENTER_VERTICAL);
        sel.setClickable(true);
        sel.setBackground(UiKit.rowBg(ctx, UiKit.OPTION));
        sel.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 10), UiKit.dp(ctx, 12), UiKit.dp(ctx, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = UiKit.dp(ctx, 6);
        sel.setLayoutParams(slp);

        final TextView curLabel = new TextView(ctx);
        curLabel.setTextSize(UiKit.FS_BTN);
        curLabel.setTextColor(UiKit.TITLE);
        curLabel.setSingleLine(true);
        curLabel.setEllipsize(TextUtils.TruncateAt.END);
        sel.addView(curLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));

        final ImageView caret = Icons.view(ctx, Icons.IC_CHEVRON_DOWN, 18.0f, UiKit.SUB);
        sel.addView(caret, new LinearLayout.LayoutParams(UiKit.dp(ctx, 24), UiKit.dp(ctx, 24)));
        box.addView(sel);

        final LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        // 展开态记进偏好：用户调过就保留，换主题重建 / 下次进入都照此还原。
        final Context appCtx = ctx.getApplicationContext();
        final String panelKey = "配置列表";
        final boolean panelOpen = PetPrefs.cardOpen(appCtx, panelKey);
        panel.setVisibility(panelOpen ? View.VISIBLE : View.GONE);
        caret.setImageResource(panelOpen ? Icons.IC_CHEVRON_UP : Icons.IC_CHEVRON_DOWN);
        box.addView(panel);

        sel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = panel.getVisibility() != View.VISIBLE;
                // 【丝滑】配置列表展开/收起用淡入淡出，不再硬切换。
                UiKit.showHide(panel, show);
                caret.setImageResource(show ? Icons.IC_CHEVRON_UP : Icons.IC_CHEVRON_DOWN);
                PetPrefs.setCardOpen(appCtx, panelKey, show);
            }
        });

        bNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (act == null) {
                    return;
                }
                SettingsProfiles.syncInputs(ctx, SettingsProfiles.loadProfiles(ctx), inputs);
                SettingsProfilePanel.askName(act, "\u65b0\u5efa\u914d\u7f6e", "\u914d\u7f6e " + (SettingsProfiles.loadProfiles(ctx).length() + 1),
                        new SettingsPage.NameCallback() {
                            @Override
                            public void onName(String name) {
                                JSONArray a = SettingsProfiles.loadProfiles(ctx);
                                if (SettingsProfiles.indexOfName(a, name) >= 0) {
                                    return;
                                }
                                // 【坑】新建的配置必须保持空白：原来这里抄了当前输入框的三段内容，
                                //       于是「+ 新建」出来的是上一套配置的副本，看着像没生效。
                                a.put(SettingsProfiles.newProfile(name, "", "", ""));
                                SettingsProfiles.writePref(ctx, SettingsProfiles.KEY_PROFILES, a.toString());
                                // 走 switchTo 一并把输入框与活跃值（base_url / api_key / model）清成空白。
                                SettingsProfiles.switchTo(ctx, name, inputs, null);
                                SettingsProfilePanel.refreshProfiles(ctx, panel, curLabel, inputs);
                            }
                        });
            }
        });

        bRen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (act == null) {
                    return;
                }
                final String cur = SettingsProfiles.readPref(ctx, SettingsProfiles.KEY_CURRENT);
                SettingsProfilePanel.askName(act, "\u914d\u7f6e\u6539\u540d", cur, new SettingsPage.NameCallback() {
                    @Override
                    public void onName(String name) {
                        JSONArray a = SettingsProfiles.loadProfiles(ctx);
                        if (SettingsProfiles.indexOfName(a, name) >= 0) {
                            return;
                        }
                        int i = SettingsProfiles.indexOfName(a, cur);
                        JSONObject o = i < 0 ? null : a.optJSONObject(i);
                        if (o == null) {
                            return;
                        }
                        try {
                            o.put("n", name);
                        } catch (Throwable ignored) {
                            Logs.w(LOG_TAG, "ignored", ignored);
                        }
                        SettingsProfiles.writePref(ctx, SettingsProfiles.KEY_PROFILES, a.toString());
                        SettingsProfiles.writePref(ctx, SettingsProfiles.KEY_CURRENT, name);
                        SettingsProfilePanel.refreshProfiles(ctx, panel, curLabel, inputs);
                    }
                });
            }
        });

        bDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (act == null) {
                    return;
                }
                final JSONArray a0 = SettingsProfiles.loadProfiles(ctx);
                if (a0.length() <= 1) {
                    return;
                }
                final String cur = SettingsProfiles.readPref(ctx, SettingsProfiles.KEY_CURRENT);
                UiKit.showDialog(act, "\u5220\u9664\u914d\u7f6e",
                        UiKit.dialogMessage(act, "\u786e\u5b9a\u5220\u9664\u300c" + cur + "\u300d\uff1f"),
                        "\u5220\u9664", new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                JSONArray arr = SettingsProfiles.loadProfiles(ctx);
                                int i = SettingsProfiles.indexOfName(arr, cur);
                                if (i < 0) {
                                    return;
                                }
                                JSONArray na = new JSONArray();
                                for (int j = 0; j < arr.length(); j++) {
                                    if (j != i) {
                                        na.put(arr.optJSONObject(j));
                                    }
                                }
                                SettingsProfiles.writePref(ctx, SettingsProfiles.KEY_PROFILES, na.toString());
                                JSONObject first = na.optJSONObject(0);
                                String nn = first == null ? "\u9ed8\u8ba4\u914d\u7f6e" : first.optString("n", "\u9ed8\u8ba4\u914d\u7f6e");
                                SettingsProfiles.switchTo(ctx, nn, inputs, null);
                                SettingsProfilePanel.refreshProfiles(ctx, panel, curLabel, inputs);
                            }
                        }, "\u53d6\u6d88", null);
            }
        });

        // 「测试连接」上移到模型配置下方（字段之上）。
        if (testBar != null) {
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = UiKit.dp(ctx, 8);
            testBar.setLayoutParams(tlp);
            box.addView(testBar);
        } else if (test != null) {
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = UiKit.dp(ctx, 8);
            test.setLayoutParams(tlp);
            box.addView(test);
        }
        // ③ 修复收尾：结果文字贴在「保存 / 测试」这一排正下方。
        if (testResult != null) {
            box.addView(testResult);
        }

        SettingsProfilePanel.refreshProfiles(ctx, panel, curLabel, inputs);
        SettingsProfiles.bindAutoSave(ctx, urlIn, "base_url", inputs);
        SettingsProfiles.bindAutoSave(ctx, keyIn, "api_key", inputs);
        SettingsProfiles.bindAutoSave(ctx, modelIn, "model", inputs);
        SettingsProfiles.fillFromCurrent(ctx, inputs);
        return box;
    }
}
