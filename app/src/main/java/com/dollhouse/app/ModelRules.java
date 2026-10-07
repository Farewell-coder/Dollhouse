package com.dollhouse.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】供应商 / 模型管理的纯规则层：不碰存储、不碰 View，只做字符串与结构计算。
 *
 * 【为什么单独一个类】规格书要求「关键逻辑抽纯函数」；这些规则（URL 拼接、协议头、
 *        两级分组、模型清单解析）错一处就整条链路不通，放一起最好核对与复算。
 */
final class ModelRules {

    private ModelRules() {
    }

    /* ------------------------------ 协议默认值 ------------------------------ */

    /**
     * 协议默认对话路径（用户没填 chatPath 时用）。
     * 【口径】与规格书一致：baseUrl 自带版本段（OpenAI 的 /v1），chatPath 只写端点名。
     *        于是用户把 baseUrl 填成 https://api.deepseek.com/v1 也能一次拼对。
     */
    static String defaultChatPath(String protocol) {
        return "/chat/completions";
    }

    /** 默认 BaseUrl：新建时填这个占位，用户可改成任意中转站 / 官网地址。 */
    static String defaultBaseUrl(String protocol) {
        return "https://api.openai.com/v1";
    }
    /* ------------------------------ URL 组装 ------------------------------ */

    /**
     * 组装最终请求地址：baseUrl + chatPath。
     * 【规则】① baseUrl 先规范化（补 https://、去尾斜杠）；
     *        ② chatPath 为空时走协议默认；若 baseUrl 本身已是完整端点（老用户直接把
     *           chat/completions 填进 BaseUrl），则原样使用，绝不重复追加；
     *        ③ chatPath 以 http 开头视为绝对地址，原样用（反代场景常见）；
     *        ④ 两边接缝处保证只有一个斜杠。
     */
    static String chatUrl(Provider p, String model) {
        if (p == null) {
            return "";
        }
        String base = ApiEndpoint.ensure(p.baseUrl);
        String path = p.chatPath == null ? "" : p.chatPath.trim();
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return withModelPlaceholder(path, model);
        }
        if (path.isEmpty()) {
            if (p.useResponseApi && Provider.PROTO_OPENAI.equals(p.protocol)) {
                path = hasVersionSegment(base) ? "/responses" : "/v1/responses";
            } else {
                // 【修·必须】未填 chatPath 时统一委托 ApiEndpoint.chatUrl：
                //   根地址（不带 /v1）会自动回填版本段，不再拼成 /chat/completions 而 404。
                return withModelPlaceholder(ApiEndpoint.chatUrl(p.baseUrl), model);
            }
        }
        if (base.isEmpty()) {
            return withModelPlaceholder(path, model);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return withModelPlaceholder(base + path, model);
    }

    /** 地址末段是否已经是版本段；实现收口在 ApiEndpoint，避免两处口径漂移。 */
    static boolean hasVersionSegment(String url) {
        return ApiEndpoint.hasVersionSegment(url);
    }

    /** Google 系把模型名写在路径里，用 {model} 占位；其余协议原样返回。 */
    private static String withModelPlaceholder(String url, String model) {
        String m = model == null ? "" : model.trim();
        if (url.indexOf("{model}") < 0) {
            return url;
        }
        return url.replace("{model}", m);
    }

    /**
     * 拉取模型列表的地址：规格书的「{baseUrl}/models」。
     * 【口径】baseUrl 自带版本段就直接接 /models（https://x.com/v1 → https://x.com/v1/models）；
     *        没带版本段才补 /v1（Claude 同理，它也从 /v1/models 拉）。
     */
    static String modelsUrl(Provider p) {
        if (p == null) {
            return "";
        }
        // 【收口】与对话地址同源，杜绝「测试连接」与「拉取模型」两套拼法。
        return ApiEndpoint.modelsUrl(p.baseUrl);
    }

    /* ------------------------------ 请求头 ------------------------------ */

    /**
     * 给连接装协议头。
     * 【为什么单独抽出来】统一走 OpenAI 兼容的 Bearer 鉴权。
     */
    static void applyHeaders(java.net.HttpURLConnection conn, Provider p, String key) {
        if (conn == null) {
            return;
        }
        String k = key == null ? "" : key.trim();
        conn.setRequestProperty("Authorization", "Bearer " + k);
    }

    /* ------------------------------ 模型清单解析 ------------------------------ */

    /**
     * 从服务端返回体里抠出模型名清单。
     * 【兼容】OpenAI 风格 {data:[{id:...}]}、Google 风格 {models:[{name:"models/xxx"}]}、
     *        以及裸数组 ["a","b"] 三种；解析失败返回空表而不是抛异常。
     */
    static java.util.List<String> parseModels(String body) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (body == null || body.trim().isEmpty()) {
            return out;
        }
        try {
            String t = body.trim();
            if (t.startsWith("[")) {
                return parseBareArray(t);
            }
            JSONObject o = new JSONObject(t);
            collectDataArray(o.optJSONArray("data"), out);
            collectModelsArray(o.optJSONArray("models"), out);
        } catch (Throwable t) {
            Logs.w("Dollhouse", "parseModels failed len=" + body.length(), t);
        }
        return out;
    }

    /** 裸数组格式：["a","b"]。解析失败返回空表（与 parseModels 的吞异常口径一致）。 */
    private static java.util.List<String> parseBareArray(String text) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                addName(out, arr.optString(i, ""));
            }
        } catch (Throwable t) {
            Logs.w("Dollhouse", "parseModels failed len=" + text.length(), t);
        }
        return out;
    }

    /** OpenAI 风格 data:[{id:...}]。 */
    private static void collectDataArray(JSONArray data, java.util.List<String> out) {
        if (data == null) {
            return;
        }
        for (int i = 0; i < data.length(); i++) {
            JSONObject it = data.optJSONObject(i);
            if (it != null) {
                addName(out, it.optString("id", ""));
            } else {
                addName(out, data.optString(i, ""));
            }
        }
    }

    /** Google 风格 models:[{name:"models/xxx"}]。 */
    private static void collectModelsArray(JSONArray models, java.util.List<String> out) {
        if (models == null) {
            return;
        }
        for (int i = 0; i < models.length(); i++) {
            JSONObject it = models.optJSONObject(i);
            if (it != null) {
                // Google 给的是 models/gemini-xxx，剥掉前缀才是模型名。
                addName(out, stripGooglePrefix(it.optString("name", "")));
            } else {
                addName(out, models.optString(i, ""));
            }
        }
    }

    private static String stripGooglePrefix(String s) {
        String v = s == null ? "" : s.trim();
        return v.startsWith("models/") ? v.substring("models/".length()) : v;
    }

    private static void addName(java.util.List<String> out, String s) {
        String v = s == null ? "" : s.trim();
        if (v.length() > 0 && !out.contains(v)) {
            out.add(v);
        }
    }

    /* ------------------------------ 两级分组 ------------------------------ */

    /**
     * 聊天页模型选择器用的分组：只列「供应商启用 && 模型启用」的聊天类模型。
     * 【返回结构】每个元素是一组，组内字段：providerId / providerName / models(List&lt;AiModel&gt;)。
     * 【规则】① 供应商禁用 → 其下模型整组隐藏（数据不删）；
     *        ② 模型禁用 → 该条不列出；
     *        ③ 非聊天类（图片 / 向量）不进对话选择器；
     *        ④ 组按供应商创建时间排序，组内按 sortOrder 再按名字。
     */
    static java.util.List<ModelGroup> enabledGroups(java.util.List<Provider> providers, java.util.List<AiModel> models) {
        java.util.List<ModelGroup> out = new java.util.ArrayList<ModelGroup>();
        if (providers == null || models == null) {
            return out;
        }
        java.util.List<Provider> ps = new java.util.ArrayList<Provider>(providers);
        java.util.Collections.sort(ps, PROVIDER_BY_CREATED);
        for (int i = 0; i < ps.size(); i++) {
            Provider p = ps.get(i);
            if (!p.enabled) {
                continue;
            }
            ModelGroup g = new ModelGroup();
            g.providerId = p.id;
            g.providerName = p.name;
            collectChatModels(models, p, g);
            if (g.models.isEmpty()) {
                continue;
            }
            java.util.Collections.sort(g.models, MODEL_BY_ORDER);
            out.add(g);
        }
        return out;
    }

    /** 供应商按创建时间排序。 */
    private static final java.util.Comparator<Provider> PROVIDER_BY_CREATED =
            new java.util.Comparator<Provider>() {
                @Override
                public int compare(Provider a, Provider b) {
                    return Long.compare(a.createdAt, b.createdAt);
                }
            };

    /** 组内模型：先按 sortOrder，再按名字（忽略大小写）。 */
    private static final java.util.Comparator<AiModel> MODEL_BY_ORDER =
            new java.util.Comparator<AiModel>() {
                @Override
                public int compare(AiModel a, AiModel b) {
                    if (a.sortOrder != b.sortOrder) {
                        return a.sortOrder - b.sortOrder;
                    }
                    return a.displayName.compareToIgnoreCase(b.displayName);
                }
            };

    /** 收集某供应商名下「启用 && 聊天类」的模型。 */
    private static void collectChatModels(java.util.List<AiModel> models, Provider p, ModelGroup g) {
        for (int k = 0; k < models.size(); k++) {
            AiModel m = models.get(k);
            if (m.enabled && AiModel.KIND_CHAT.equals(m.kind) && p.id.equals(m.providerId)) {
                g.models.add(m);
            }
        }
    }

    /** 两级分组的一组：一个供应商 + 它下面启用中的聊天模型。 */
    static final class ModelGroup {
        String providerId = "";
        String providerName = "";
        final java.util.List<AiModel> models = new java.util.ArrayList<AiModel>();
    }

    /* ------------------------------ 校验 ------------------------------ */

    /** 名称是否重复（id 为自己时不算重复，供编辑页用）。 */
    static boolean nameExists(java.util.List<Provider> list, String name, String selfId) {
        if (list == null || name == null) {
            return false;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            Provider p = list.get(i);
            if (n.equals(p.name) && !p.id.equals(selfId)) {
                return true;
            }
        }
        return false;
    }

    /** 表单必填校验：返回空串 = 通过，否则返回要提示给用户的那句话。 */
    static String validate(Provider p) {
        if (p == null) {
            return "数据为空";
        }
        if (p.name == null || p.name.trim().isEmpty()) {
            return "请填写名称";
        }
        String base = ApiEndpoint.ensure(p.baseUrl);
        if (base.isEmpty()) {
            return "请填写 BaseUrl";
        }
        if (base.indexOf("://") < 0) {
            return "BaseUrl 需要以 http:// 或 https:// 开头";
        }
        return "";
    }

    /** 归一化 baseUrl：没有协议头就补 https://（保存前调用）。 */
    static String normalizeBaseUrl(String s) {
        return ApiEndpoint.ensure(s);
    }
}
