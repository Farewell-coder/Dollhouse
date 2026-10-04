package com.dollhouse.app;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】聊天设置分组的二次整理 + 从服务端拉真实模型清单。
 * 【入口】SettingsPage.addCard 在包「聊天设置（云端 API）」卡片时调用 tidy()；
 *         卡片里的展开图标点下去时走到 injectModelPicker 挂上的监听器。
 * 【交互】输入框三件套由 SettingsProfilePanel 装配并传入；本类不写偏好，只整理视图。
 * 【坑】tidy() 靠 hint 前缀识别三个输入框，MainActivity 里 mkInput 的 hint 文案不能改。
 */
public final class ApiSectionTuner {
    private static final String LOG_TAG = "Dollhouse";

    /** 三个字段名（输入框上方的分组标签）。 */
    private static final String L_URL = "接口端点";
    private static final String L_KEY = "API 密钥";
    private static final String L_MODEL = "模型名称";

    /** 模型清单浮层超过这个行数就固定高度、内部滚动。 */
    public static final int MODEL_ROWS = 5;

    /** 拉取模型清单的回调。 */
    public interface ModelsCallback {
        void onDone(List<String> models, String error);
    }

    private ApiSectionTuner() {
    }

    /**
     * 把 API 分组整理成参考图那种「字段名 + 输入框」的款式：
     * 顺序改为 接口端点 / API 密钥 / 模型名称，每个输入框上方加一行字段名，
     * 输入框换成淡紫底白边框的圆角样式。原有控件实例与监听器一律不动。
     */
    public static void tidy(Context ctx, LinearLayout body) {
        int n = body.getChildCount();
        if (n == 0) {
            return;
        }
        List<View> kids = new ArrayList<View>(n);
        for (int i = 0; i < n; i++) {
            kids.add(body.getChildAt(i));
        }

        EditText urlIn = null;
        EditText keyIn = null;
        EditText modelIn = null;
        List<View> head = new ArrayList<View>();
        List<View> tail = new ArrayList<View>();
        boolean seenInput = false;
        for (int i = 0; i < kids.size(); i++) {
            View v = kids.get(i);
            String h = SettingsPage.hintOf(v);
            if (h != null && h.startsWith(SettingsPage.URL_HINT)) {
                urlIn = (EditText) v;
                seenInput = true;
            } else if (h != null && h.startsWith(SettingsPage.KEY_HINT)) {
                keyIn = (EditText) v;
                seenInput = true;
            } else if (h != null && h.startsWith(SettingsPage.MODEL_HINT)) {
                modelIn = (EditText) v;
                seenInput = true;
            } else if (seenInput) {
                tail.add(v);
            } else {
                head.add(v);
            }
        }
        if (urlIn == null || keyIn == null || modelIn == null) {
            return;
        }

        body.removeAllViews();
        for (int i = 0; i < head.size(); i++) {
            body.addView(head.get(i));
        }
        body.addView(SettingsProfilePanel.buildProfileSection(ctx, urlIn, keyIn, modelIn, tail));
        SettingsPage.addLabeled(ctx, body, L_URL, urlIn);
        SettingsPage.addLabeled(ctx, body, L_KEY, keyIn);
        SettingsPage.addLabeled(ctx, body, L_MODEL, modelIn);
        for (int i = 0; i < tail.size(); i++) {
            body.addView(tail.get(i));
        }
        body.addView(SettingsPage.buildTokenEntry(ctx));
    }

    /**
     * 在 API 分组里插入「模型」选择器。
     * 候选来自服务端 {接口地址}/models 的真实返回，不预置任何名字；
     * 没检测到（未填地址/未填 key/请求失败）就不展示任何候选。
     */
    public static void injectModelPicker(Context ctx, LinearLayout body, List<View> all,
                                          int from, int to) {
        EditText modelInput = null;
        int insertAt = -1;
        for (int k = from; k < to; k++) {
            View v = all.get(k);
            if (v instanceof EditText) {
                CharSequence hint = ((EditText) v).getHint();
                if (hint != null && hint.toString().startsWith(SettingsPage.MODEL_HINT)) {
                    modelInput = (EditText) v;
                    insertAt = body.indexOfChild(v);
                    break;
                }
            }
        }
        if (modelInput == null || insertAt < 0) {
            return;
        }

        final EditText fInput = modelInput;
        final Context fCtx = ctx;

        // 输入框末尾挂一颗方形展开图标：点击拉取 / 收起服务端返回的模型清单。
        final TextView caret = new TextView(ctx);
        caret.setTextSize(18.0f);
        caret.setTextColor(UiKit.TITLE);
        caret.setGravity(Gravity.CENTER);
        caret.setBackground(UiKit.round(UiKit.SOFT, ctx, 10));
        caret.setText(UiKit.ARROW_CLOSED);

        LinearLayout hbox = new LinearLayout(ctx);
        hbox.setOrientation(LinearLayout.HORIZONTAL);
        hbox.setGravity(Gravity.CENTER_VERTICAL);
        body.removeView(modelInput);
        hbox.addView(modelInput, new LinearLayout.LayoutParams(0, -2, 1.0f));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(UiKit.dp(ctx, 46), UiKit.dp(ctx, 46));
        clp.leftMargin = UiKit.dp(ctx, 8);
        hbox.addView(caret, clp);

        // 输入框 + 展开箭头合成一张圆角卡片：白底 + 内边距，
        // 视觉上是一个整体控件，不再各自撑一块色块贴在页面背景上。
        LinearLayout fieldCard = new LinearLayout(ctx);
        fieldCard.setOrientation(LinearLayout.VERTICAL);
        fieldCard.setBackground(UiKit.rowBg(ctx, UiKit.CARD));
        fieldCard.setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 4), UiKit.dp(ctx, 4), UiKit.dp(ctx, 4));
        fieldCard.addView(hbox, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        wrap.addView(fieldCard);

        final TextView status = new TextView(ctx);
        status.setTextSize(UiKit.FS_SUB);
        status.setTextColor(UiKit.SUB);
        status.setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 6), 0, 0);
        status.setVisibility(View.GONE);
        wrap.addView(status);

        final LinearLayout options = new LinearLayout(ctx);
        options.setOrientation(LinearLayout.VERTICAL);

        // 模型清单不再内嵌进页面，改成点 caret 弹出的独立浮层（PopupWindow）。
        // 独立窗口层自带限高与滚动，不受设置页外层 ScrollView 影响，也就没有嵌套滚动冲突。
        final ScrollView optScroll = new ScrollView(ctx);
        optScroll.setBackground(UiKit.round(UiKit.CARD, ctx, 12));
        optScroll.setPadding(0, UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 6));
        optScroll.setVerticalScrollBarEnabled(true);
        optScroll.setScrollbarFadingEnabled(false);
        optScroll.addView(options, new FrameLayout.LayoutParams(-1, -2));

        final PopupWindow popup = new PopupWindow(ctx);
        popup.setContentView(optScroll);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
        popup.setElevation(UiKit.dp(ctx, 8));
        // 浮层一旦关闭（点空白 / 选中 / 失败），箭头同步收回，避免图标与实际状态不一致。
        popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                caret.setText(UiKit.ARROW_CLOSED);
            }
        });

        // 拉取中标志：避免连点 caret 重复发起请求（后到的失败回调会把已弹出的浮层清掉）。
        final boolean[] loading = {false};
        final Runnable loader = new Runnable() {
            @Override
            public void run() {
                if (loading[0]) {
                    return;
                }
                loading[0] = true;
                final String base = SettingsProfiles.readPref(fCtx, "base_url");
                final String key = SettingsProfiles.readPref(fCtx, "api_key");
                if (base.length() == 0) {
                    loading[0] = false;
                    status.setVisibility(View.VISIBLE);
                    status.setTextColor(UiKit.ERR);
                    status.setText("\u5148\u586b\u5199\u300c\u63a5\u53e3\u7aef\u70b9\u300d\u5e76\u4fdd\u5b58\uff0c\u518d\u70b9\u8fd9\u91cc\u68c0\u6d4b");
                    return;
                }
                status.setVisibility(View.VISIBLE);
                status.setTextColor(UiKit.SUB);
                status.setText("\u6b63\u5728\u68c0\u6d4b " + ApiSectionTuner.modelsUrl(base) + " \u2026");
                ApiSectionTuner.fetchModels(base, key, new ModelsCallback() {
                    @Override
                    public void onDone(List<String> models, String error) {
                        if (models != null && !models.isEmpty()) {
                            options.removeAllViews();
                            for (int i = 0; i < models.size(); i++) {
                                final String name = models.get(i);
                                // 【交互】每行 = 名字（点它选中模型） + 星（点它收藏/取消收藏）。
                                //         收藏过的模型才会出现在聊天面板圆环的「展开模型」里。
                                LinearLayout item = new LinearLayout(fCtx);
                                item.setOrientation(LinearLayout.HORIZONTAL);
                                item.setGravity(Gravity.CENTER_VERTICAL);
                                item.setBackground(UiKit.round(UiKit.SOFT, fCtx, 8));
                                item.setPadding(UiKit.dp(fCtx, 12), 0, UiKit.dp(fCtx, 6), 0);
                                LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
                                itemLp.topMargin = UiKit.dp(fCtx, 2);
                                item.setLayoutParams(itemLp);

                                TextView label = new TextView(fCtx);
                                label.setText(name);
                                label.setTextSize(UiKit.FS_BTN);
                                label.setTextColor(UiKit.TITLE);
                                label.setSingleLine(true);
                                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                                item.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));

                                final TextView star = new TextView(fCtx);
                                star.setTextSize(17.0f);
                                star.setGravity(Gravity.CENTER);
                                star.setPadding(UiKit.dp(fCtx, 8), UiKit.dp(fCtx, 8),
                                        UiKit.dp(fCtx, 4), UiKit.dp(fCtx, 8));
                                star.setText(SettingsProfiles.isStarred(fCtx, name) ? "★" : "☆");
                                star.setTextColor(SettingsProfiles.isStarred(fCtx, name) ? UiKit.ACC : UiKit.SUB);
                                star.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        boolean on = SettingsProfiles.toggleStar(fCtx, name);
                                        star.setText(on ? "★" : "☆");
                                        star.setTextColor(on ? UiKit.ACC : UiKit.SUB);
                                    }
                                });
                                item.addView(star, new LinearLayout.LayoutParams(-2, -2));

                                label.setClickable(true);
                                label.setPadding(0, UiKit.dp(fCtx, 11), 0, UiKit.dp(fCtx, 11));
                                label.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        SettingsProfiles.writePref(fCtx, "model", name);
                                        fInput.setText(name);
                                        fInput.setSelection(name.length());
                                        popup.dismiss();
                                        caret.setText(UiKit.ARROW_CLOSED);
                                        status.setTextColor(UiKit.SUB);
                                        status.setText("\u5df2\u9009\u7528\uff1a" + name);
                                    }
                                });
                                options.addView(item);
                            }
                            status.setTextColor(UiKit.SUB);
                            status.setText("\u68c0\u6d4b\u5230 " + models.size() + " \u4e2a\u53ef\u7528\u6a21\u578b");
                            // \u8d85\u8fc7 ApiSectionTuner.MODEL_ROWS \u884c\u56fa\u5b9a\u9ad8\u5ea6\u53ef\u6eda\u52a8\uff0c\u5426\u5219\u81ea\u9002\u5e94\u3002
                            popup.setHeight(models.size() > ApiSectionTuner.MODEL_ROWS ? UiKit.dp(fCtx, 210) : -2);
                            ApiSectionTuner.showModelPopup(popup, fInput);
                            caret.setText(UiKit.ARROW_OPEN);
                            loading[0] = false;
                        } else {
                            options.removeAllViews();
                            if (popup.isShowing()) {
                                popup.dismiss();
                            }
                            caret.setText(UiKit.ARROW_CLOSED);
                            status.setVisibility(View.VISIBLE);
                            status.setTextColor(UiKit.ERR);
                            status.setText("\u672a\u80fd\u83b7\u53d6\u6a21\u578b\u5217\u8868\uff1a" + (error == null ? "\u672a\u77e5\u9519\u8bef" : error));
                            loading[0] = false;
                        }
                    }
                });
            }
        };

        caret.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (options.getChildCount() > 0) {
                    if (popup.isShowing()) {
                        popup.dismiss();
                        caret.setText(UiKit.ARROW_CLOSED);
                    } else {
                        ApiSectionTuner.showModelPopup(popup, fInput);
                        caret.setText(UiKit.ARROW_OPEN);
                    }
                    return;
                }
                // \u8fd8\u6ca1\u62c9\u8fc7\u6e05\u5355\uff1a\u5148\u62c9\u53d6\uff0c\u6210\u529f\u540e onDone \u91cc\u81ea\u52a8\u5f39\u51fa\u6d6e\u5c42\u3002
                loader.run();
            }
        });
        // \u957f\u6309\u5f3a\u5236\u91cd\u62c9\uff1a\u6539\u4e86\u63a5\u53e3\u7aef\u70b9 / \u5bc6\u94a5\u540e\u7528\u6765\u5237\u65b0\u5df2\u7f13\u5b58\u7684\u6a21\u578b\u6e05\u5355\u3002
        caret.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                options.removeAllViews();
                if (popup.isShowing()) {
                    popup.dismiss();
                }
                loader.run();
                return true;
            }
        });

        body.addView(wrap, insertAt);
    }

    /** \u628a\u6a21\u578b\u6e05\u5355\u4ee5\u72ec\u7acb\u6d6e\u5c42\u5f39\u5728\u8f93\u5165\u6846\u4e0b\u65b9\uff08\u5bbd\u5ea6\u5bf9\u9f50\u8f93\u5165\u6846\uff09\u3002 */
    private static void showModelPopup(PopupWindow popup, View anchor) {
        if (popup == null || anchor == null) {
            return;
        }
        int w = anchor.getWidth();
        if (w <= 0) {
            Context c = anchor.getContext();
            w = c.getResources().getDisplayMetrics().widthPixels - UiKit.dp(c, 64);
        }
        popup.setWidth(w);
        popup.showAsDropDown(anchor, 0, UiKit.dp(anchor.getContext(), 4));
    }

    /** 由「接口地址」推导模型列表地址：去掉 completions 段落，拼 /models。 */
    static String modelsUrl(String base) {
        // 端点规范化统一走 ApiEndpoint：补协议、去注释、识别完整端点后缀。
        String u = ApiEndpoint.base(base);
        if (u.isEmpty()) {
            return "";
        }
        return u + "/models";
    }

    static void fetchModels(final String base, final String apiKey,
                                    final ModelsCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> list = null;
                String err = null;
                HttpURLConnection c = null;
                try {
                    URL url = new URL(ApiSectionTuner.modelsUrl(base));
                    c = (HttpURLConnection) url.openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Accept", "application/json");
                    if (apiKey != null && apiKey.length() > 0) {
                        c.setRequestProperty("Authorization", "Bearer " + apiKey);
                    }
                    int code = c.getResponseCode();
                    InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                    String body = ApiSectionTuner.readAll(in);
                    if (code < 200 || code >= 300) {
                        err = "HTTP " + code + (body.length() > 0 ? "\u3000" + ApiSectionTuner.shorten(body) : "");
                    } else {
                        list = ApiSectionTuner.parseModels(body);
                        if (list.isEmpty()) {
                            err = "\u63a5\u53e3\u6ca1\u8fd4\u56de\u6a21\u578b\u5217\u8868";
                        }
                    }
                } catch (Throwable t) {
                    err = ApiEndpoint.friendlyError(t, ApiSectionTuner.modelsUrl(base));
                } finally {
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Throwable ignored) {
                            Log.w(LOG_TAG, "ignored", ignored);
                        }
                    }
                }
                final List<String> fl = list;
                final String fe = err;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(fl, fe);
                    }
                });
            }
        }).start();
    }

    private static List<String> parseModels(String body) {
        List<String> out = new ArrayList<String>();
        try {
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            if (data == null) {
                data = root.optJSONArray("models");
            }
            if (data == null) {
                return out;
            }
            for (int i = 0; i < data.length(); i++) {
                Object o = data.opt(i);
                String id = null;
                if (o instanceof JSONObject) {
                    id = ((JSONObject) o).optString("id", "");
                    if (id.length() == 0) {
                        id = ((JSONObject) o).optString("name", "");
                    }
                } else if (o instanceof String) {
                    id = (String) o;
                }
                if (id != null) {
                    id = id.trim();
                    if (id.length() > 0 && !out.contains(id)) {
                        out.add(id);
                    }
                }
            }
        } catch (Throwable ignored) {
            Log.w(LOG_TAG, "ignored", ignored);
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
        }
        r.close();
        return sb.toString();
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) + "\u2026" : t;
    }
}
