package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * 【职责】添加 / 编辑供应商页。
 *
 * 【表单】名称 / API Key（密码态 + 眼睛）/ Base Url / 路径 → 三个开关 → 取消 / 保存。
 *        协议统一为「自定义（OpenAI 兼容）」：中转站与各家官网都按它对接，不再有协议页签。
 *
 * 【交互铁律】① 名称 / Key / BaseUrl 全由用户填，程序绝不覆盖；
 *        ② 保存做必填校验，漏写协议头自动补 https:// 并在提示里说明；
 *        ③ 保存成功后自动跑一次连通性测试，失败只提示、不回滚保存（规格书要求）。
 *
 * 【隐私】Key 输入框默认密码态；写盘走 KeyVault 加密，页面本身不落明文。
 */
final class ProviderEditPage {

    /**
     * 内置常用端点：主流官网协议 + 常见中转站写法。
     * 【为什么内置】中转站地址每家不同、还常带 /v1，用户手抄极易漏版本段 ——
     *   而漏版本段正是「别的软件能用、这里连不上」的高频原因之一。
     * 【口径】一律带版本段；用户选完仍可自己改。
     */
    private static final String[][] PRESETS = {
            {"OpenAI", "https://api.openai.com/v1"},
            {"DeepSeek", "https://api.deepseek.com/v1"},
            {"通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"},
            {"智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"},
            {"Kimi", "https://api.moonshot.cn/v1"},
            {"硅基流动", "https://api.siliconflow.cn/v1"},
            {"火山方舟", "https://ark.cn-beijing.volces.com/api/v3"},
            {"百度千帆", "https://qianfan.baidubce.com/v2"},
            {"腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1"},
            {"讯飞星火", "https://spark-api-open.xf-yun.com/v1"},
            {"阶跃星辰", "https://api.stepfun.com/v1"},
            {"OpenRouter", "https://openrouter.ai/api/v1"},
    };

    /** 当前编辑中的表单状态（每次进页重建一份，不存静态，避免多实例串味）。 */
    private static final class Form {
        String id = "";
        String protocol = Provider.PROTO_OPENAI;
        boolean created = false;
        EditText name;
        EditText key;
        EditText base;
        EditText path;
        UiKit.Switch enabled;
        UiKit.Switch respApi;
        UiKit.Switch resendReason;
        TextView hint;
        TextView preview;
    }

    private ProviderEditPage() {
    }

    /** 独立页形态：从设置页直接进（不带胶囊底栏）。 */
    static View build(Activity act, String providerId) {
        return build(act, providerId, false);
    }

    /**
     * 嵌入式形态：作为供应商详情页「配置」tab 的内容。
     * 【与独立页的差别只有一处】底部左键文案「取消」→「返回列表」。
     *   保存成功后留在本页这一点独立页本来就这样（保存分支只提示、不 back），无需分支。
     * 【为什么不复制一份页】复制会让「供应商编辑」出现两套样式，改一处必漏一处。
     */
    static View buildEmbedded(Activity act, String providerId) {
        return build(act, providerId, true);
    }

    private static View build(final Activity act, String providerId, final boolean embedded) {
        final Context ctx = act;
        final Form f = new Form();
        Provider src = providerId == null || providerId.isEmpty()
                ? null : ProviderStore.findProvider(ctx, providerId);
        if (src != null) {
            f.id = src.id;
            f.protocol = src.protocol == null ? Provider.PROTO_OPENAI : src.protocol;
            f.created = true;
        }

        LinearLayout root = ApiPageKit.pageRoot(ctx);
        root.addView(UiKit.topBar(ctx, f.created ? "编辑供应商" : "添加供应商",
                "自定义（OpenAI 兼容）", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ProviderNav.back(act);
                    }
                }));

        LinearLayout host = ApiPageKit.contentHost(ctx);


        // —— 主表单 ——
        LinearLayout box = ApiPageKit.card(ctx);
        f.name = ApiPageKit.labeledInput(ctx, box, "名称", "例如：001", false);
        f.key = ApiPageKit.labeledInput(ctx, box, "API Key", "输入 API 密钥", true);
        addEye(ctx, box, f.key);
        f.base = ApiPageKit.labeledInput(ctx, box, "API Base Url", "例如：https://api.deepseek.com/v1", false);
        f.path = ApiPageKit.labeledInput(ctx, box, "API 路径", "默认 /chat/completions", false);
        if (src != null) {
            f.name.setText(src.name);
            f.key.setText(ProviderStore.keyOf(ctx, src));
            f.base.setText(src.baseUrl);
            f.path.setText(src.chatPath);
        }
        host.addView(box);

        // —— 常用端点（一键填入，省得手抄漏 /v1） ——
        LinearLayout pre = ApiPageKit.card(ctx);
        pre.addView(ApiPageKit.sectionTitle(ctx, "常用端点（点一下填进上面的 API Base Url）"));
        pre.addView(presetRow(ctx, f.base));
        host.addView(pre);

        // —— 地址预览：下单前先看清究竟会请求哪个 URL ——
        f.preview = ApiPageKit.note(ctx, "");
        host.addView(f.preview);
        bindPreview(f);
        refreshPreview(f);

        // —— 开关组 ——
        LinearLayout sw = ApiPageKit.card(ctx);
        sw.addView(ApiPageKit.sectionTitle(ctx, "开关"));
        f.enabled = switchRow(ctx, sw, "启用", "关掉后它名下的模型不会出现在聊天页选择器里（数据保留）",
                src == null || src.enabled);
        f.respApi = switchRow(ctx, sw, "Response API",
                "开启后对话走 /responses 而不是 chat completions", src != null && src.useResponseApi);
        f.resendReason = switchRow(ctx, sw, "回传历史思考过程",
                "把历史消息里的思考内容再次随请求发出；关掉则剥离", src != null && src.resendHistoryReasoning);
        host.addView(sw);


        f.hint = ApiPageKit.note(ctx, "");
        f.hint.setVisibility(View.GONE);
        host.addView(f.hint);

        // —— 底部：取消 / 保存 ——
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, ApiPageKit.dp(ctx, 14), 0, ApiPageKit.dp(ctx, 8));
        TextView cancel = UiKit.outlineChip(ctx, embedded ? "返回列表" : "取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProviderNav.back(act);
            }
        });
        bar.addView(cancel);
        bar.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1.0f));
        TextView test = UiKit.outlineChip(ctx, "测试连接");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTest(ctx, f, false);
            }
        });
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(-2, -2);
        tlp2.rightMargin = ApiPageKit.dp(ctx, 8);
        bar.addView(test, tlp2);
        TextView save = UiKit.primaryChip(ctx, "保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTest(ctx, f, true);
            }
        });
        bar.addView(save);
        host.addView(bar);

        root.addView(ApiPageKit.scrollWrap(ctx, host), new LinearLayout.LayoutParams(-1, 0, 1.0f));
        // 新建页自动带上通用默认值；编辑页不动用户已存的值。
        if (src == null) {
            f.base.setText(ModelRules.defaultBaseUrl(f.protocol));
            f.path.setText(ModelRules.defaultChatPath(f.protocol));
        }
        return root;
    }

    /** 密码框的「显示 / 隐藏」切换。 */
    private static void addEye(Context ctx, LinearLayout box, final EditText field) {
        final TextView eye = new TextView(ctx);
        eye.setText("显示");
        eye.setTextSize(UiKit.FS_SUB);
        eye.setTextColor(UiKit.ACC);
        eye.setTypeface(Typeface.DEFAULT_BOLD);
        eye.setClickable(true);
        eye.setGravity(Gravity.RIGHT);
        eye.setPadding(ApiPageKit.dp(ctx, 2), ApiPageKit.dp(ctx, 6), ApiPageKit.dp(ctx, 2), 0);
        eye.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int now = field.getInputType();
                boolean plain = (now & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
                // 【修·必须】切换密码态会重排文字并把光标甩到末尾，
                //   用户接着敲的字符就会跑到已经填好的 Key 后面（输入被改写）。
                //   这里先存下原选区，改完再放回去。
                CharSequence cs = field.getText();
                int len = cs == null ? 0 : cs.length();
                int selStart = Math.min(field.getSelectionStart(), len);
                int selEnd = Math.min(field.getSelectionEnd(), len);
                if (selStart < 0) {
                    selStart = len;
                }
                if (selEnd < 0) {
                    selEnd = len;
                }
                field.setInputType(InputType.TYPE_CLASS_TEXT
                        | (plain ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                 : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                field.setSelection(Math.min(selStart, selEnd), Math.max(selStart, selEnd));
                eye.setText(plain ? "隐藏" : "显示");
            }
        });
        box.addView(eye);
    }

    /** 常用端点横向条：每项点一下把地址填进 Base Url 并把光标放到末尾。 */
    private static View presetRow(Context ctx, final EditText base) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ApiPageKit.dp(ctx, 8), 0, 0);
        for (int i = 0; i < PRESETS.length; i++) {
            final String url = PRESETS[i][1];
            TextView c = UiKit.chip(ctx, PRESETS[i][0]);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    base.setText(url);
                    Editable ed = base.getText();
                    base.setSelection(ed == null ? 0 : ed.length());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ApiPageKit.dp(ctx, 6);
            row.addView(c, lp);
        }
        hs.addView(row);
        return hs;
    }

    /** 监听 Base Url / 路径，实时刷新地址预览。 */
    private static void bindPreview(final Form f) {
        TextWatcher w = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                refreshPreview(f);
            }
        };
        if (f.base != null) {
            f.base.addTextChangedListener(w);
        }
        if (f.path != null) {
            f.path.addTextChangedListener(w);
        }
    }

    /**
     * 复算并展示「对话地址 + 模型列表地址」。
     * 【口径】与真正发请求时同一套代码（ApiClient -> ModelRules/ApiEndpoint），
     *   页面上看到什么，请求就打向什么，不再有两个口径。
     */
    private static void refreshPreview(Form f) {
        if (f == null || f.preview == null || f.preview.getParent() == null) {
            return;
        }
        Provider p = new Provider();
        p.protocol = f.protocol;
        p.baseUrl = ModelRules.normalizeBaseUrl(text(f.base));
        p.chatPath = text(f.path);
        String chat = ApiClient.chatUrl(p, "");
        if (chat.isEmpty()) {
            f.preview.setText("对话地址：还没填 API Base Url");
            return;
        }
        f.preview.setText("对话地址：" + chat + "\n模型列表：" + ApiClient.modelsUrl(p));
    }

    /** 一行开关，返回句柄供保存时读值。 */
    private static UiKit.Switch switchRow(Context ctx, LinearLayout dest, String name,
                                          String desc, boolean on) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, ApiPageKit.dp(ctx, 10), 0, ApiPageKit.dp(ctx, 10));
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
            d.setPadding(0, ApiPageKit.dp(ctx, 2), 0, 0);
            col.addView(d);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final UiKit.Switch sw = new UiKit.Switch(ctx);
        sw.setOn(on);
        sw.setClickable(true);
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setOn(!sw.isOn(), true);
            }
        });
        r.addView(sw);
        dest.addView(r);
        return sw;
    }
    private static String text(EditText e) {
        return e == null || e.getText() == null ? "" : e.getText().toString().trim();
    }

    /**
     * 保存 + 连通性测试。
     * 【顺序】先校验（不过就只提示、不写盘）→ 写盘 → 再跑测试（失败不影响已保存的数据）。
     */
    private static void runTest(final Context ctx, final Form f, final boolean save) {
        Provider p = new Provider();
        p.id = f.id;
        p.name = text(f.name);
        p.protocol = f.protocol;
        p.baseUrl = ModelRules.normalizeBaseUrl(text(f.base));
        p.chatPath = text(f.path);
        p.enabled = f.enabled == null || f.enabled.isOn();
        p.useResponseApi = f.respApi != null && f.respApi.isOn();
        p.resendHistoryReasoning = f.resendReason != null && f.resendReason.isOn();
        String rawKey = text(f.key);

        if (save) {
            String err = ModelRules.validate(p);
            if (err.length() > 0) {
                hint(ctx, f, false, err);
                return;
            }
            // 用户漏写协议头时自动补上，并在提示里说清。
            String rawBase = text(f.base);
            boolean patched = rawBase.length() > 0 && !rawBase.startsWith("http://")
                    && !rawBase.startsWith("https://");
            if (rawKey.length() == 0) {
                hint(ctx, f, false, "请填写 API Key");
                return;
            }
            List<Provider> all = ProviderStore.providers(ctx);
            if (ModelRules.nameExists(all, p.name, p.id)) {
                hint(ctx, f, false, "已有同名供应商，换一个名字");
                return;
            }
            String keep = f.created ? ProviderStore.keyOf(ctx, ProviderStore.findProvider(ctx, p.id)) : "";
            p.apiKey = KeyVault.encrypt(rawKey);
            if (p.apiKey.isEmpty()) {
                if (f.created && rawKey.equals(keep)) {
                    // 用户没改 Key（读回的就是明文），加密失败时保留原密文，不写空。
                    Provider old = ProviderStore.findProvider(ctx, p.id);
                    p.apiKey = old == null ? "" : old.apiKey;
                }
                if (p.apiKey.isEmpty()) {
                    // 【修·必须】不再静默写空：否则存下去的就是「无密钥」，下次请求必 401。
                    hint(ctx, f, false, "密钥加密失败，未能保存，请重试或重启应用后重填");
                    return;
                }
            }
            if (p.id == null || p.id.isEmpty()) {
                p.id = ProviderStore.newId();
            }
            ProviderStore.saveProvider(ctx, p);
            f.id = p.id;
            f.created = true;
            if (patched) {
                f.base.setText(p.baseUrl);
            }
            // 保存成功后自动跑一次连通性测试（规格书要求）：失败只提示，不回滚已存数据。
            // 页面不重建 —— 重建会把这条提示一起清掉，用户就看不到测试结果了。
            hint(ctx, f, true, "已保存，正在测试连接…");
            ProviderNav.testConnection(ctx, p, new ProviderNav.TestCallback() {
                @Override
                public void onDone(boolean ok, String detail) {
                    hint(ctx, f, ok, ok ? "已保存 · " + detail : "已保存，但连接没通过：" + detail);
                }
            });
            return;
        }
        // 只测试不保存：用当前表单值直接打一次 /models。
        if (rawKey.length() == 0) {
            hint(ctx, f, false, "请填写 API Key");
            return;
        }
        p.apiKey = KeyVault.encrypt(rawKey);
        if (p.apiKey.isEmpty()) {
            hint(ctx, f, false, "密钥加密失败，无法测试，请重试或重启应用后重填");
            return;
        }
        hint(ctx, f, true, "正在测试连接…");
        ProviderNav.testConnection(ctx, p, new ProviderNav.TestCallback() {
            @Override
            public void onDone(boolean ok, String detail) {
                hint(ctx, f, ok, detail);
            }
        });
    }

    private static void hint(Context ctx, Form f, boolean ok, String detail) {
        if (f.hint == null || f.hint.getParent() == null) {
            return;
        }
        f.hint.setText(detail == null ? "" : detail);
        f.hint.setTextColor(ok ? UiKit.OK : UiKit.ERR);
        UiKit.reveal(f.hint);
    }
}
