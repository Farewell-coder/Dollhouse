package com.dollhouse.app.ai

import com.dollhouse.app.core.Logs
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】供应商 / 模型管理的纯规则层：不碰存储、不碰 View，只做字符串与结构计算。
 *
 * 【为什么单独一个类】规格书要求「关键逻辑抽纯函数」；这些规则（URL 拼接、协议头、
 *        两级分组、模型清单解析）错一处就整条链路不通，放一起最好核对与复算。
 */
object ModelRules {

    /* ------------------------------ 协议默认值 ------------------------------ */

    /**
     * 协议默认对话路径（用户没填 chatPath 时用）。
     * 【口径】与规格书一致：baseUrl 自带版本段（OpenAI 的 /v1），chatPath 只写端点名。
     *        于是用户把 baseUrl 填成 https://api.deepseek.com/v1 也能一次拼对。
     */
    @JvmStatic
    fun defaultChatPath(protocol: String?): String {
        return "/chat/completions"
    }

    /** 默认 BaseUrl：新建时填这个占位，用户可改成任意中转站 / 官网地址。 */
    @JvmStatic
    fun defaultBaseUrl(protocol: String?): String {
        return "https://api.openai.com/v1"
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
    @JvmStatic
    fun chatUrl(p: Provider?, model: String?): String {
        if (p == null) {
            return ""
        }
        val base = ApiEndpoint.ensure(p.baseUrl)
        var path = p.chatPath.trim()
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return withModelPlaceholder(path, model)
        }
        if (path.isEmpty()) {
            if (p.useResponseApi && Provider.PROTO_OPENAI == p.protocol) {
                path = if (hasVersionSegment(base)) "/responses" else "/v1/responses"
            } else {
                // 【修·必须】未填 chatPath 时统一委托 ApiEndpoint.chatUrl：
                //   根地址（不带 /v1）会自动回填版本段，不再拼成 /chat/completions 而 404。
                return withModelPlaceholder(ApiEndpoint.chatUrl(p.baseUrl), model)
            }
        }
        if (base.isEmpty()) {
            return withModelPlaceholder(path, model)
        }
        if (!path.startsWith("/")) {
            path = "/" + path
        }
        return withModelPlaceholder(base + path, model)
    }

    /** 地址末段是否已经是版本段；实现收口在 ApiEndpoint，避免两处口径漂移。 */
    @JvmStatic
    fun hasVersionSegment(url: String?): Boolean {
        return ApiEndpoint.hasVersionSegment(url)
    }

    /** Google 系把模型名写在路径里，用 {model} 占位；其余协议原样返回。 */
    private fun withModelPlaceholder(url: String, model: String?): String {
        val m = if (model == null) "" else model.trim()
        if (url.indexOf("{model}") < 0) {
            return url
        }
        return url.replace("{model}", m)
    }

    /**
     * 拉取模型列表的地址：规格书的「{baseUrl}/models」。
     * 【口径】baseUrl 自带版本段就直接接 /models（https://x.com/v1 → https://x.com/v1/models）；
     *        没带版本段才补 /v1（Claude 同理，它也从 /v1/models 拉）。
     */
    @JvmStatic
    fun modelsUrl(p: Provider?): String {
        if (p == null) {
            return ""
        }
        // 【收口】与对话地址同源，杜绝「测试连接」与「拉取模型」两套拼法。
        return ApiEndpoint.modelsUrl(p.baseUrl)
    }

    /* ------------------------------ 请求头 ------------------------------ */

    /**
     * 给连接装协议头。
     * 【为什么单独抽出来】统一走 OpenAI 兼容的 Bearer 鉴权。
     */
    @JvmStatic
    fun applyHeaders(conn: HttpURLConnection?, p: Provider?, key: String?) {
        if (conn == null) {
            return
        }
        val k = if (key == null) "" else key.trim()
        conn.setRequestProperty("Authorization", "Bearer $k")
    }

    /* ------------------------------ 模型清单解析 ------------------------------ */

    /**
     * 从服务端返回体里抠出模型名清单。
     * 【兼容】OpenAI 风格 {data:[{id:...}]}、Google 风格 {models:[{name:"models/xxx"}]}、
     *        以及裸数组 ["a","b"] 三种；解析失败返回空表而不是抛异常。
     */
    @JvmStatic
    fun parseModels(body: String?): MutableList<String> {
        val out = ArrayList<String>()
        if (body == null || body.trim().isEmpty()) {
            return out
        }
        try {
            val t = body.trim()
            if (t.startsWith("[")) {
                return parseBareArray(t)
            }
            val o = JSONObject(t)
            collectDataArray(o.optJSONArray("data"), out)
            collectModelsArray(o.optJSONArray("models"), out)
        } catch (t: Throwable) {
            Logs.w("Dollhouse", "parseModels failed len=" + body.length, t)
        }
        return out
    }

    /** 裸数组格式：["a","b"]。解析失败返回空表（与 parseModels 的吞异常口径一致）。 */
    private fun parseBareArray(text: String): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                addName(out, arr.optString(i, ""))
            }
        } catch (t: Throwable) {
            Logs.w("Dollhouse", "parseModels failed len=" + text.length, t)
        }
        return out
    }

    /** OpenAI 风格 data:[{id:...}]。 */
    private fun collectDataArray(data: JSONArray?, out: MutableList<String>) {
        if (data == null) {
            return
        }
        for (i in 0 until data.length()) {
            val it = data.optJSONObject(i)
            if (it != null) {
                addName(out, it.optString("id", ""))
            } else {
                addName(out, data.optString(i, ""))
            }
        }
    }

    /** Google 风格 models:[{name:"models/xxx"}]。 */
    private fun collectModelsArray(models: JSONArray?, out: MutableList<String>) {
        if (models == null) {
            return
        }
        for (i in 0 until models.length()) {
            val it = models.optJSONObject(i)
            if (it != null) {
                // Google 给的是 models/gemini-xxx，剥掉前缀才是模型名。
                addName(out, stripGooglePrefix(it.optString("name", "")))
            } else {
                addName(out, models.optString(i, ""))
            }
        }
    }

    private fun stripGooglePrefix(s: String?): String {
        val v = if (s == null) "" else s.trim()
        return if (v.startsWith("models/")) v.substring("models/".length) else v
    }

    private fun addName(out: MutableList<String>, s: String?) {
        val v = if (s == null) "" else s.trim()
        if (v.length > 0 && !out.contains(v)) {
            out.add(v)
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
    @JvmStatic
    fun enabledGroups(providers: List<Provider>?, models: List<AiModel>?): MutableList<ModelGroup> {
        val out = ArrayList<ModelGroup>()
        if (providers == null || models == null) {
            return out
        }
        val ps = ArrayList(providers)
        ps.sortWith(PROVIDER_BY_CREATED)
        for (p in ps) {
            if (!p.enabled) {
                continue
            }
            val g = ModelGroup()
            g.providerId = p.id
            g.providerName = p.name
            collectChatModels(models, p, g)
            if (g.models.isEmpty()) {
                continue
            }
            g.models.sortWith(MODEL_BY_ORDER)
            out.add(g)
        }
        return out
    }

    /** 供应商按创建时间排序。 */
    private val PROVIDER_BY_CREATED: Comparator<Provider> =
            Comparator<Provider> { a, b ->
                a.createdAt.compareTo(b.createdAt)
            }

    /** 组内模型：先按 sortOrder，再按名字（忽略大小写）。 */
    private val MODEL_BY_ORDER: Comparator<AiModel> =
            Comparator<AiModel> { a, b ->
                if (a.sortOrder != b.sortOrder) {
                    a.sortOrder - b.sortOrder
                } else {
                    a.displayName.compareTo(b.displayName, ignoreCase = true)
                }
            }

    /** 收集某供应商名下「启用 && 聊天类」的模型。 */
    private fun collectChatModels(models: List<AiModel>, p: Provider, g: ModelGroup) {
        for (m in models) {
            if (m.enabled && AiModel.KIND_CHAT == m.kind && p.id == m.providerId) {
                g.models.add(m)
            }
        }
    }

    /** 两级分组的一组：一个供应商 + 它下面启用中的聊天模型。 */
    class ModelGroup {
        var providerId: String = ""
        var providerName: String = ""
        val models: MutableList<AiModel> = ArrayList()
    }

    /* ------------------------------ 校验 ------------------------------ */

    /** 名称是否重复（id 为自己时不算重复，供编辑页用）。 */
    @JvmStatic
    fun nameExists(list: List<Provider>?, name: String?, selfId: String?): Boolean {
        if (list == null || name == null) {
            return false
        }
        val n = name.trim()
        if (n.isEmpty()) {
            return false
        }
        for (p in list) {
            if (n == p.name && p.id != selfId) {
                return true
            }
        }
        return false
    }

    /** 表单必填校验：返回空串 = 通过，否则返回要提示给用户的那句话。 */
    @JvmStatic
    fun validate(p: Provider?): String {
        if (p == null) {
            return "数据为空"
        }
        if (p.name.trim().isEmpty()) {
            return "请填写名称"
        }
        val base = ApiEndpoint.ensure(p.baseUrl)
        if (base.isEmpty()) {
            return "请填写 BaseUrl"
        }
        if (base.indexOf("://") < 0) {
            return "BaseUrl 需要以 http:// 或 https:// 开头"
        }
        return ""
    }

    /** 归一化 baseUrl：没有协议头就补 https://（保存前调用）。 */
    @JvmStatic
    fun normalizeBaseUrl(s: String?): String {
        return ApiEndpoint.ensure(s)
    }
}
