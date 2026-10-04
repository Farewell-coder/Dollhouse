package com.dollhouse.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】「配置 API」独立页：底部三个页签 —— 配置 / 模型 / 高级设置。
 *
 * 【入口】设置页「聊天」卡片里的「配置 API」入口行 → open()；返回键 → closeIfOpen()。
 *
 * 【交互】多套配置与星标收藏走 SettingsProfiles；高级开关走 PetPrefs；
 *         模型清单走 ApiSectionTuner.fetchModels；Token 统计走 TokenStat.open。
 *
 * 【坑】① 页面挂在 android.R.id.content 上并打 tag，不新建 Activity，返回键靠 closeIfOpen 消费；
 *        ② 本页是「显式保存」：表单不挂 bindAutoSave，点「保存」才写盘；
 *        ③ 配置名是主键，改名必须先查重，否则 indexOfName 会撞车；
 *        ④ 颜色一律走 UiKit 常量，不写裸色值，暗色 / 纯黑主题才能自动跟随。
 */
final class ApiConfigPage {
    private static final String LOG_TAG = "Dollhouse";
    private static final String TAG_PAGE = "feiyu_cfg_page";
    /** 底部页签标题。 */
    private static final String[] TABS = {"配置", "模型", "高级设置"};
    /** 设置页「聊天」卡片里两行入口的 tag。 */
    static final String TAG_ENTRY_CFG = "feiyu_entry_cfg";
    static final String TAG_ENTRY_TOKEN = "feiyu_entry_token";
    /**
     * 当前停在哪个提供商（空串 = 一级列表）。
     * 【坑】不能用 View.setTag(String,Object) —— Android 只有带 int key 的重载，key 是 String 编译不过。
     */
    private static String CURRENT_PROVIDER = "";

    private ApiConfigPage() {
    }

    /* ------------------------------ 入口 / 出口 ------------------------------ */

    /** 返回键用：本页开着就关掉并返回 true。 */
    static boolean closeIfOpen(Context ctx) {
        try {
            Activity act = findActivity(ctx);
            if (act == null) {
                return false;
            }
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null) {
                return false;
            }
            View old = content.findViewWithTag(TAG_PAGE);
            if (old == null) {
                return false;
            }
            UiKit.closePage(old);
            CURRENT_PROVIDER = "";
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 设置页「配置 API」入口行的落地动作：进一级「提供商列表」。 */
    static void open(Context ctx) {
        Activity act = findActivity(ctx);
        if (act != null) {
            show(act, null);
        }
    }

    /**
     * 返回键：二级 → 回一级（返回 true）；一级 → 关掉整页（返回 true）；没开返 false。
     * 【坑】不能用 closeIfOpen 代替：那个无论几级都直接关门，二级里按返回会一下子退到桌面。
     */
    static boolean handleBack(Context ctx) {
        try {
            Activity act = findActivity(ctx);
            if (act == null) {
                return false;
            }
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null) {
                return false;
            }
            View old = content.findViewWithTag(TAG_PAGE);
            if (old == null) {
                return false;
            }
            if (CURRENT_PROVIDER.length() > 0) {
                show(act, null);
            } else {
                UiKit.closePage(old);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 重建整页：provider 为空 = 一级列表，非空 = 该配置的二级详情。 */
    private static void show(Activity act, String provider) {
        try {
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            // 进二级详情 = 前进（右侧滑入）；返回一级 / 同层刷新 = 交叉淡入。
            boolean push = provider != null || content.findViewWithTag(TAG_PAGE) == null;
            View page = build(act, provider);
            CURRENT_PROVIDER = provider == null ? "" : provider;
            if (push) {
                UiKit.openPage(content, page, TAG_PAGE);
            } else {
                UiKit.swapPage(content, page, TAG_PAGE);
            }
        } catch (Throwable ignored) {
            Log.w(LOG_TAG, "ignored", ignored);
        }
    }

    private static Activity findActivity(Context ctx) {
        Context c = ctx;
        for (int i = 0; i < 8 && c != null; i++) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            if (c instanceof ContextWrapper) {
                c = ((ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    /* ------------------------------ 页面状态 ------------------------------ */

    /**
     * 页面状态：每次 open 重建一份，不存静态，避免多实例互相串味。
     * provider 为当前停在的提供商名；仅在二级详情下有效。
     */
    private static final class St {
        final Activity act;
        final String provider;
        final FrameLayout stack;
        final LinearLayout cfgHost;
        final LinearLayout mdlBox;
        final TextView mdlStatus;
        final TextView[] tabs = new TextView[TABS.length];
        St(Activity a, String p, FrameLayout s, LinearLayout cfg, LinearLayout mdl, TextView status) {
            this.act = a;
            this.provider = p;
            this.stack = s;
            this.cfgHost = cfg;
            this.mdlBox = mdl;
            this.mdlStatus = status;
        }
    }

    /* ------------------------------ 页面骨架 ------------------------------ */

    /** 整页入口：provider 为空 = 一级列表；非空 = 该配置的二级详情。 */
    private static View build(Activity act, String provider) {
        if (provider == null || provider.length() == 0) {
            return buildProviderList(act);
        }
        return buildProviderDetail(act, provider);
    }

    /* ------------------------------ 一级：提供商列表 ------------------------------ */

    private static View buildProviderList(final Activity act) {
        final Context ctx = act;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);

        // 顶栏：← 返回 + 标题「提供商」+ 副标题（真实数量）+ 右上角「+」新建（全部走 UiKit 统一规范）
        final JSONArray arr = SettingsProfiles.loadProfiles(ctx);
        final String cur = SettingsProfiles.readPref(ctx, SettingsProfiles.KEY_CURRENT);
        LinearLayout bar = UiKit.topBar(ctx, "提供商", "共 " + arr.length() + " 个提供商",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        handleBack(ctx);
                    }
                });
        TextView add = UiKit.iconBtn(ctx, "+", UiKit.FS_ICON, UiKit.ACC);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askNew(act);
            }
        });
        bar.addView(add, new LinearLayout.LayoutParams(dp(ctx, UiKit.HIT_DP), dp(ctx, UiKit.HIT_DP)));
        root.addView(bar);

        ScrollView sc = new ScrollView(ctx);
        LinearLayout host = new LinearLayout(ctx);
        host.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 16);
        host.setPadding(pad, 0, pad, dp(ctx, 16));

        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) {
                continue;
            }
            String name = p.optString("n", "");
            if (name.length() == 0) {
                continue;
            }
            host.addView(providerRow(act, name, cur.equals(name), p.optString("m", "")));
        }
        sc.addView(host, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        root.addView(note(ctx, "点某个提供商进入它的详情：配置 / 模型 / 高级设置。"));
        return root;
    }

    /** 提供商列表行：首字头像 + 名称 + 状态/模型标签 + ⋮ 菜单。 */
    private static LinearLayout providerRow(final Activity act, final String name,
                                            boolean active, String model) {
        final Context ctx = act;
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UiKit.cardBg(ctx));
        r.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 8);
        r.setLayoutParams(lp);

        TextView avatar = new TextView(ctx);
        avatar.setText(name.substring(0, 1));
        avatar.setTextSize(UiKit.FS_BTN);
        avatar.setTextColor(UiKit.ACC);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(UiKit.round(UiKit.FIELD, ctx, 999));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 38));
        alp.rightMargin = dp(ctx, 12);
        r.addView(avatar, alp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(ctx);
        nm.setText(name);
        nm.setTextSize(UiKit.FS_BTN);
        nm.setTextColor(UiKit.TITLE);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm);

        LinearLayout tags = new LinearLayout(ctx);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        tags.setPadding(0, dp(ctx, 6), 0, 0);
        tags.addView(UiKit.badge(ctx, active ? "启用" : "禁用",
                active ? UiKit.OK : UiKit.HINT_FG,
                active ? 0x1A1B8A3A : UiKit.HINT_BG));
        String mm = model == null ? "" : model.trim();
        tags.addView(UiKit.badge(ctx, mm.length() > 0 ? mm : "未选模型",
                UiKit.CHAT_CHIP_FG, UiKit.CHAT_CHIP_BG));
        col.addView(tags);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView more = UiKit.iconBtn(ctx, "⋮", UiKit.FS_ICON, UiKit.SUB);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfigMenu(act, name);
            }
        });
        r.addView(more, new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)));

        r.setClickable(true);
        UiKit.press(r);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(act, name);
            }
        });
        return r;
    }



    /* ------------------------------ 二级：配置详情（三页签） ------------------------------ */


    /**
     * 二级：某个提供商的详情，底部三个页签（配置 / 模型 / 高级设置）。
     * 【坑】二级的返回键必须先回一级；靠 handleBack 读 CURRENT_PROVIDER 判断，不能直接关门。
     */
    private static View buildProviderDetail(Activity act, String provider) {
        Context ctx = act;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);

        root.addView(UiKit.topBar(ctx, provider, "配置 / 模型 / 高级设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleBack(ctx);
            }
        }));

        FrameLayout stack = new FrameLayout(ctx);
        stack.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));
        int pad = dp(ctx, 16);

        // 页签一：配置（本套的字段表单）
        final LinearLayout cfgHost = new LinearLayout(ctx);
        cfgHost.setOrientation(LinearLayout.VERTICAL);
        cfgHost.setPadding(pad, 0, pad, dp(ctx, 16));
        stack.addView(scrollWrap(ctx, cfgHost));

        // 页签二：模型
        LinearLayout mdlOuter = new LinearLayout(ctx);
        mdlOuter.setOrientation(LinearLayout.VERTICAL);
        mdlOuter.setPadding(pad, 0, pad, dp(ctx, 16));
        final TextView mdlStatus = new TextView(ctx);
        mdlStatus.setTextSize(UiKit.FS_SUB);
        mdlStatus.setTextColor(UiKit.SUB);
        mdlStatus.setPadding(dp(ctx, 2), 0, 0, dp(ctx, 6));
        mdlOuter.addView(mdlStatus);
        // 【丝滑】初始收起态：先可见再淡出，避免首帧硬闪一下。
        UiKit.collapse(mdlStatus);
        final LinearLayout mdlBox = new LinearLayout(ctx);
        mdlBox.setOrientation(LinearLayout.VERTICAL);
        mdlOuter.addView(mdlBox);
        stack.addView(scrollWrap(ctx, mdlOuter));

        // 页签三：高级设置
        LinearLayout advOuter = new LinearLayout(ctx);
        advOuter.setOrientation(LinearLayout.VERTICAL);
        advOuter.setPadding(pad, 0, pad, dp(ctx, 16));
        stack.addView(scrollWrap(ctx, advOuter));

        root.addView(stack);

        final St st = new St(act, provider, stack, cfgHost, mdlBox, mdlStatus);

        LinearLayout nav = new LinearLayout(ctx);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(UiKit.CARD);
        nav.setPadding(pad, dp(ctx, 8), pad, dp(ctx, 8));
        nav.setElevation(dp(ctx, 8));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView tab = new TextView(ctx);
            tab.setText(TABS[i]);
            tab.setTextSize(UiKit.FS_BTN);
            tab.setTypeface(Typeface.DEFAULT_BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setClickable(true);
            tab.setPadding(dp(ctx, 6), dp(ctx, 9), dp(ctx, 6), dp(ctx, 9));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(st, idx);
                }
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            tlp.leftMargin = dp(ctx, 4);
            tlp.rightMargin = dp(ctx, 4);
            nav.addView(tab, tlp);
            st.tabs[i] = tab;
        }
        root.addView(nav);

        refreshCfg(st);
        buildAdvancedTab(st, advOuter);
        switchTab(st, 0);
        return root;
    }

    private static View scrollWrap(Context ctx, View inner) {
        ScrollView sc = new ScrollView(ctx);
        sc.setBackgroundColor(UiKit.BG);
        sc.addView(inner, new ViewGroup.LayoutParams(-1, -2));
        return sc;
    }

    /** 切页签：只改可见性 + 页签配色，重建成本最低。 */
    private static void switchTab(St st, int index) {
        if (st == null || index < 0 || index >= st.stack.getChildCount()) {
            return;
        }
        for (int i = 0; i < st.stack.getChildCount(); i++) {
            // 【丝滑】页签切换用淡入淡出，不再硬切。
            UiKit.showHide(st.stack.getChildAt(i), i == index);
        }
        Context ctx = st.act;
        for (int i = 0; i < st.tabs.length; i++) {
            boolean on = i == index;
            UiKit.setTextColorAnimated(st.tabs[i], on ? UiKit.ACC : UiKit.SUB);
            st.tabs[i].setBackground(on ? UiKit.round(UiKit.FIELD, ctx, 10) : null);
        }
        if (index == 1 && st.mdlBox.getChildCount() == 0) {
            buildModelsTab(st);
        }
    }

    /* ------------------------------ 小组件 ------------------------------ */

    private static int dp(Context ctx, int v) {
        return UiKit.dp(ctx, v);
    }

    /** 一张白卡片。 */
    private static LinearLayout card(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(UiKit.cardBg(ctx));
        c.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 10);
        c.setLayoutParams(lp);
        return c;
    }

    /** 整宽可点行：左名称 + 右值/箭头。 */
    private static LinearLayout row(Context ctx, String name, String right, View.OnClickListener cb) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UiKit.round(UiKit.SOFT, ctx, 10));
        r.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 8);
        r.setLayoutParams(lp);

        TextView label = new TextView(ctx);
        label.setText(name);
        label.setTextSize(UiKit.FS_BTN);
        label.setTextColor(UiKit.TITLE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));

        if (right != null && right.length() > 0) {
            TextView v = new TextView(ctx);
            v.setText(right);
            v.setTextSize(UiKit.FS_BTN);
            v.setTextColor(UiKit.SUB);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        }
        if (cb != null) {
            r.setClickable(true);
            UiKit.press(r);
            r.setOnClickListener(cb);
        }
        return r;
    }



    /** 小字备注。 */
    private static TextView note(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(UiKit.FS_TINY);
        t.setTextColor(UiKit.SUB);
        t.setLineSpacing(dp(ctx, 3), 1.0f);
        t.setPadding(dp(ctx, 2), dp(ctx, 8), dp(ctx, 2), 0);
        return t;
    }

    /** 字段名 + 输入框（与设置页 addLabeled 的观感一致）。 */
    private static EditText labeledInput(Context ctx, LinearLayout dest, String label, String hint, boolean password) {
        TextView lb = new TextView(ctx);
        lb.setText(label);
        lb.setTextSize(UiKit.FS_SUB);
        lb.setTextColor(UiKit.SUB);
        lb.setPadding(dp(ctx, 2), dp(ctx, 12), 0, dp(ctx, 4));
        dest.addView(lb);

        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextSize(UiKit.FS_BTN);
        e.setSingleLine(true);
        UiKit.field(e, ctx);
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        dest.addView(e);
        return e;
    }

    /* ------------------------------ 「配置」页签 ------------------------------ */

    /** 卡片内入口行（供 SettingsRegistry.ApiCard.decorate 调用）：整宽可点 + 右侧 ›。 */
    static LinearLayout entryRow(Context ctx, String tag, String name, String desc,
                                 View.OnClickListener cb) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(tag);
        box.addView(row(ctx, name, "\u203a", cb));
        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(ctx);
            d.setText(desc);
            d.setTextSize(UiKit.FS_TINY);
            d.setTextColor(UiKit.SUB);
            d.setPadding(dp(ctx, 14), dp(ctx, 2), 0, 0);
            box.addView(d);
        }
        return box;
    }

    /** 重建「配置」页签：按 st.provider 定位当前提供商并渲染表单。 */
    private static void refreshCfg(St st) {
        st.cfgHost.removeAllViews();
        JSONArray arr = SettingsProfiles.loadProfiles(st.act);
        int i = SettingsProfiles.indexOfName(arr, st.provider);
        JSONObject p = i < 0 ? null : arr.optJSONObject(i);
        if (p == null) {
            st.cfgHost.addView(note(st.act, "这套配置已不存在。"));
            return;
        }
        buildConfigForm(st, p);
    }

    /* ------------------------------ 表单 / 新建 / 菜单 ------------------------------ */

    /** 列名右侧「⋯」的菜单：重命名 / 删除。 */
    private static void showConfigMenu(final Activity act, final String name) {
        final Dialog[] dh = new Dialog[1];
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(menuItem(act, "重命名", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dh[0] != null) {
                    dh[0].dismiss();
                }
                askRename(act, name);
            }
        }));
        col.addView(menuItem(act, "删除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dh[0] != null) {
                    dh[0].dismiss();
                }
                confirmDelete(act, name);
            }
        }));
        dh[0] = UiKit.showDialog(act, name, col, null, null, null, null);
    }

    private static TextView menuItem(Context ctx, String label, View.OnClickListener cb) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        t.setPadding(dp(ctx, 8), dp(ctx, 14), dp(ctx, 8), dp(ctx, 14));
        t.setClickable(true);
        UiKit.press(t);
        t.setOnClickListener(cb);
        return t;
    }

    /** 新建：先问名字，再以空白三项建一套并直接进表单。 */
    private static void askNew(final Activity act) {
        final EditText input = new EditText(act);
        input.setHint("给这套配置起个名字");
        input.setSingleLine(true);
        UiKit.field(input, act);
        UiKit.showDialog(act, "新建配置", input, "创建", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = input.getText() == null ? "" : input.getText().toString().trim();
                if (name.length() == 0) {
                    return;
                }
                JSONArray arr = SettingsProfiles.loadProfiles(act);
                if (SettingsProfiles.indexOfName(arr, name) >= 0) {
                    return;
                }
                arr.put(SettingsProfiles.newProfile(name, "", "", ""));
                SettingsProfiles.writePref(act, SettingsProfiles.KEY_PROFILES, arr.toString());
                show(act, name);
            }
        }, "取消", null);
    }

    /** 重命名：只改名字，其他字段不动。 */
    private static void askRename(final Activity act, final String oldName) {
        final EditText input = new EditText(act);
        input.setSingleLine(true);
        UiKit.field(input, act);
        input.setText(oldName);
        UiKit.showDialog(act, "重命名配置", input, "确定", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = input.getText() == null ? "" : input.getText().toString().trim();
                if (name.length() == 0) {
                    return;
                }
                if (name.equals(oldName)) {
                    return;
                }
                JSONArray arr = SettingsProfiles.loadProfiles(act);
                if (SettingsProfiles.indexOfName(arr, name) >= 0) {
                    return;
                }
                int i = SettingsProfiles.indexOfName(arr, oldName);
                JSONObject p = i < 0 ? null : arr.optJSONObject(i);
                if (p == null) {
                    return;
                }
                try {
                    p.put("n", name);
                } catch (Throwable ignored) {
                }
                SettingsProfiles.writePref(act, SettingsProfiles.KEY_PROFILES, arr.toString());
                if (oldName.equals(SettingsProfiles.readPref(act, SettingsProfiles.KEY_CURRENT))) {
                    SettingsProfiles.writePref(act, SettingsProfiles.KEY_CURRENT, name);
                }
                show(act, null);
            }
        }, "取消", null);
    }

    /** 删除前先确认，且至少给页面留一套配置。 */
    private static void confirmDelete(final Activity act, final String name) {
        JSONArray arr = SettingsProfiles.loadProfiles(act);
        if (arr.length() <= 1) {
            return;
        }
        UiKit.showDialog(act, "删除配置", UiKit.dialogMessage(act, "「" + name + "」删除后无法恢复。"),
                "删除", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteProfile(act, name);
                    }
                }, "取消", null);
    }

    private static void deleteProfile(Activity act, String name) {
        JSONArray arr = SettingsProfiles.loadProfiles(act);
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null || name.equals(p.optString("n", ""))) {
                continue;
            }
            out.put(p);
        }
        SettingsProfiles.writePref(act, SettingsProfiles.KEY_PROFILES, out.toString());
        if (name.equals(SettingsProfiles.readPref(act, SettingsProfiles.KEY_CURRENT))) {
            JSONObject first = out.optJSONObject(0);
            String n0 = first == null ? SettingsProfiles.DEFAULT_NAME
                    : first.optString("n", SettingsProfiles.DEFAULT_NAME);
            SettingsProfiles.switchTo(act, n0, null, null);
        }
        show(act, null);
    }

    /* ------------------------------ 配置表单页 ------------------------------ */

    /**
     * 配置表单：四个字段全部本地暂存，只有点底部「保存」才写盘。
     * 【坑】这里刻意不挂 SettingsProfiles.bindAutoSave：输入即存会拿不到「填完再确认」的手感，
     *        而且新建的空白配置会被半成品中间态污染。
     */
    private static void buildConfigForm(final St st, JSONObject p) {
        final Context ctx = st.act;
        final String name = p.optString("n", "");

        // 顶部：返回列表
        LinearLayout backRow = row(ctx, "\u2039 返回上一层", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(st.act, null);
            }
        });
        st.cfgHost.addView(backRow);

        LinearLayout box = card(ctx);
        final EditText[] f = new EditText[4];
        f[0] = labeledInput(ctx, box, "配置名称", "给这套配置起个名字", false);
        f[0].setText(name);
        f[1] = labeledInput(ctx, box, "API Base Url", "例如：https://api.deepseek.com", false);
        f[1].setText(p.optString("u", ""));
        f[2] = labeledInput(ctx, box, "API Key", "输入 API 密钥", true);
        f[2].setText(p.optString("k", ""));

        final TextView eye = new TextView(ctx);
        eye.setText("显示");
        eye.setTextSize(UiKit.FS_SUB);
        eye.setTextColor(UiKit.ACC);
        eye.setTypeface(Typeface.DEFAULT_BOLD);
        eye.setClickable(true);
        eye.setGravity(Gravity.RIGHT);
        eye.setPadding(dp(ctx, 2), dp(ctx, 6), dp(ctx, 2), 0);
        eye.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int now = f[2].getInputType();
                boolean plain = (now & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
                f[2].setInputType(InputType.TYPE_CLASS_TEXT
                        | (plain ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                 : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                f[2].setSelection(f[2].getText() == null ? 0 : f[2].getText().length());
                eye.setText(plain ? "隐藏" : "显示");
            }
        });
        box.addView(eye);

        f[3] = labeledInput(ctx, box, "模型名称", "例如：deepseek-chat（也可到「模型」页签选）", false);
        f[3].setText(p.optString("m", ""));
        st.cfgHost.addView(box);

        // 底部操作栏：删除 / 保存
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(ctx, 14), 0, 0);

        TextView del = UiKit.outlineChip(ctx, "\u2716 删除");
        del.setTextColor(UiKit.ERR);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(st.act, name);
            }
        });
        bar.addView(del);

        View spacer = new View(ctx);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1.0f));

        TextView save = UiKit.primaryChip(ctx, "保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveProfile(st, name, f);
            }
        });
        bar.addView(save);
        st.cfgHost.addView(bar);

        st.cfgHost.addView(note(ctx, "「保存」会把当前编辑内容写入这套配置；若它正是启用中的那套，新值立即对聊天生效。"));
    }

    /** 保存当前表单：改名 + 三项字段；若保存的是启用中的那套，顺手刷新活跃值。 */
    private static void saveProfile(St st, String oldName, EditText[] f) {
        Activity act = st.act;
        String newName = f[0].getText() == null ? "" : f[0].getText().toString().trim();
        if (newName.length() == 0) {
            return;
        }
        String url = ApiEndpoint.ensure(f[1].getText() == null ? "" : f[1].getText().toString().trim());
        String key = f[2].getText() == null ? "" : f[2].getText().toString().trim();
        String model = f[3].getText() == null ? "" : f[3].getText().toString().trim();

        JSONArray arr = SettingsProfiles.loadProfiles(act);
        int idx = SettingsProfiles.indexOfName(arr, oldName);
        JSONObject p = idx < 0 ? null : arr.optJSONObject(idx);
        if (p == null) {
            show(st.act, null);
            return;
        }
        if (!newName.equals(oldName) && SettingsProfiles.indexOfName(arr, newName) >= 0) {
            return;
        }
        try {
            p.put("n", newName);
            p.put("u", url);
            p.put("k", key);
            p.put("m", model);
        } catch (Throwable ignored) {
        }
        SettingsProfiles.writePref(act, SettingsProfiles.KEY_PROFILES, arr.toString());

        String cur = SettingsProfiles.readPref(act, SettingsProfiles.KEY_CURRENT);
        if (oldName.equals(cur)) {
            if (!newName.equals(oldName)) {
                SettingsProfiles.writePref(act, SettingsProfiles.KEY_CURRENT, newName);
            }
            PetPrefs.setBaseUrl(act, url);
            PetPrefs.setApiKey(act, key);
            PetPrefs.setModel(act, model);
        }
        show(act, newName);
    }

    /* ------------------------------ 「模型」页签 ------------------------------ */

    /** 模型页签：拉当前配置端点的模型清单，★ 收藏（全局一份，聊天面板只列收藏的）。 */
    private static void buildModelsTab(final St st) {
        final Activity act = st.act;
        final Context ctx = act;
        final LinearLayout box = st.mdlBox;
        final TextView status = st.mdlStatus;
        box.removeAllViews();
        status.setVisibility(View.VISIBLE);
        UiKit.reveal(status);
        status.setText("正在拉取模型列表\u2026");

        LinearLayout tips = card(ctx);
        TextView t1 = new TextView(ctx);
        t1.setText("已收藏（★）的模型才会出现在聊天面板的「模型配置」里。");
        t1.setTextSize(UiKit.FS_SUB);
        t1.setTextColor(UiKit.SUB);
        tips.addView(t1);

        TextView fresh = UiKit.primaryChip(ctx, "\u21bb 重新拉取");
        final LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.topMargin = dp(ctx, 12);
        final View.OnClickListener onFresh = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                buildModelsTab(st);
            }
        };
        fresh.setOnClickListener(onFresh);

        JSONArray arr = SettingsProfiles.loadProfiles(act);
        int ci = SettingsProfiles.indexOfName(arr, st.provider);
        JSONObject cur = ci < 0 ? null : arr.optJSONObject(ci);
        final String curModel = cur == null ? "" : cur.optString("m", "");
        String base = cur == null ? "" : cur.optString("u", "");
        String key = cur == null ? "" : cur.optString("k", "");

        box.addView(tips);
        box.addView(fresh, flp);
        if (base.length() == 0) {
            status.setText("当前配置还没填 API Base Url，先去「配置」页签填好。");
            return;
        }
        ApiSectionTuner.fetchModels(base, key, new ApiSectionTuner.ModelsCallback() {
            @Override
            public void onDone(final List<String> models, final String error) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        box.removeAllViews();
                        box.addView(tips);
                        box.addView(fresh, flp);
                        if (error != null) {
                            status.setText(error);
                            return;
                        }
                        status.setText("检测到 " + models.size() + " 个可用模型");
                        for (int i = 0; i < models.size(); i++) {
                            final String mn = models.get(i);
                            box.addView(modelRow(st, mn, mn.equals(curModel)));
                        }
                        // 【丝滑】模型较多，只让前几行错峰淡入，其余立即可见。
                        UiKit.staggerCapped(box, 8);
                    }
                });
            }
        });
    }

    /** 模型行：点正文 = 把这套配置的模型改成它；点 ★ = 收藏/取消。 */
    private static LinearLayout modelRow(final St st, final String model, boolean selected) {
        final Context ctx = st.act;
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UiKit.round(selected ? UiKit.CHAT_CHIP_ON : UiKit.CARD, ctx, 10));
        r.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 8);
        r.setLayoutParams(lp);

        TextView nm = new TextView(ctx);
        nm.setText((selected ? "\u2713 " : "") + model);
        nm.setTextSize(UiKit.FS_BTN);
        nm.setTextColor(selected ? UiKit.CHAT_CHIP_FG : UiKit.TITLE);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(nm, new LinearLayout.LayoutParams(0, -2, 1.0f));

        final boolean starred = SettingsProfiles.isStarred(ctx, st.provider, model);
        final TextView star = new TextView(ctx);
        star.setText(starred ? "\u2605" : "\u2606");
        star.setTextSize(UiKit.FS_ICON);
        star.setTextColor(starred ? UiKit.ACC : UiKit.SUB);
        star.setGravity(Gravity.CENTER);
        star.setClickable(true);
        UiKit.press(star);
        star.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 【归属】这个地方的清单是按 st.provider 这套配置的端点拉回来的，
                //   收藏也必须落进同一套配置 —— 无参版会写进「当前配置」，
                //   当详情页停留的配置 ≠ 当前配置时，星就点到了别人身上。
                boolean now = SettingsProfiles.toggleStar(ctx, st.provider, model);
                star.setText(now ? "\u2605" : "\u2606");
                star.setTextColor(now ? UiKit.ACC : UiKit.SUB);
            }
        });
        r.addView(star, new LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 36)));

        r.setClickable(true);
        UiKit.press(r);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 【归属】改的是「这一页所属配置」的模型，不是当前活跃配置。
                //   原先读 KEY_CURRENT 再写，在详情页停留的配置 ≠ 当前配置时会改错对象。
                SettingsProfiles.setProfileModel(ctx, st.provider, model);
                buildModelsTab(st);
            }
        });
        return r;
    }

    /* ------------------------------ 「高级设置」页签 ------------------------------ */

    /** 开关回调（UiKit.Switch 不是 CompoundButton，所以自定义一个）。 */
    private interface OnToggle {
        void onChanged(boolean value);
    }

    /** 高级设置页签：只放真实生效的选项。 */
    private static void buildAdvancedTab(final St st, LinearLayout host) {
        final Context ctx = st.act;
        host.removeAllViews();

        // —— 对话行为 ——
        LinearLayout box = card(ctx);
        TextView head = new TextView(ctx);
        head.setText("对话行为");
        head.setTextSize(UiKit.FS_SUB);
        head.setTextColor(UiKit.SUB);
        box.addView(head);

        TextView tl = new TextView(ctx);
        tl.setText("思考程度");
        tl.setTextSize(UiKit.FS_BTN);
        tl.setTextColor(UiKit.TITLE);
        tl.setTypeface(Typeface.DEFAULT_BOLD);
        tl.setPadding(0, dp(ctx, 12), 0, dp(ctx, 8));
        box.addView(tl);

        for (int row = 0; row < (PetPrefs.THINK_NAMES.length + 2) / 3; row++) {
            LinearLayout rl = new LinearLayout(ctx);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final int idx = row * 3 + c;
                if (idx >= PetPrefs.THINK_NAMES.length) {
                    break;
                }
                TextView chip = UiKit.outlineChip(ctx, PetPrefs.THINK_NAMES[idx]);
                boolean on = idx == PetPrefs.thinkLevel(ctx);
                if (on) {
                    chip.setTextColor(UiKit.ON_ACC);
                    UiKit.primary(chip, ctx);
                }
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PetPrefs.setThinkLevel(ctx, idx);
                        buildAdvancedTab(st, host);
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1.0f);
                if (c < 2) {
                    clp.rightMargin = dp(ctx, 6);
                }
                rl.addView(chip, clp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = row == 0 ? 0 : dp(ctx, 6);
            box.addView(rl, rlp);
        }
        box.addView(note(ctx, "只对支持 reasoning_effort 的服务端真正生效，其余服务端靠提示词兜底。"));
        box.addView(switchRow(ctx, "联网搜索", "遇到时效性问题先联网查一下再回答",
                PetPrefs.webSearchEnabled(ctx), new OnToggle() {
                    @Override
                    public void onChanged(boolean v) {
                        PetPrefs.setWebSearchEnabled(ctx, v);
                    }
                }));
        box.addView(switchRow(ctx, "自动保存记忆", "聊完自动把值得记的内容写进记忆库",
                PetPrefs.memAutoSave(ctx), new OnToggle() {
                    @Override
                    public void onChanged(boolean v) {
                        PetPrefs.setMemAutoSave(ctx, v);
                    }
                }));
        host.addView(box);

        // —— 记忆库入口 ——
        LinearLayout mb = card(ctx);
        TextView mh = new TextView(ctx);
        mh.setText("记忆");
        mh.setTextSize(UiKit.FS_SUB);
        mh.setTextColor(UiKit.SUB);
        mb.addView(mh);
        host.addView(mb);
    }

    /** 一行开关：左标题（可带说明）+ 右 UiKit.Switch。 */
    private static LinearLayout switchRow(final Context ctx, String name, String desc,
                                          boolean on, final OnToggle cb) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(name);
        t.setTextSize(UiKit.FS_BTN);
        t.setTextColor(UiKit.TITLE);
        col.addView(t);
        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(ctx);
            d.setText(desc);
            d.setTextSize(UiKit.FS_TINY);
            d.setTextColor(UiKit.SUB);
            d.setPadding(0, dp(ctx, 2), 0, 0);
            col.addView(d);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));

        final UiKit.Switch sw = new UiKit.Switch(ctx);
        sw.setOn(on);
        sw.setClickable(true);
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !sw.isOn();
                sw.setOn(now, true);
                if (cb != null) {
                    cb.onChanged(now);
                }
            }
        });
        r.addView(sw);
        return r;
    }
}
