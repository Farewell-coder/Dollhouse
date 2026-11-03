package com.dollhouse.app.data

import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.core.KeyVault
import com.dollhouse.app.core.Logs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】把本 App 自己导出的 JSON（`ExportSheet` 产出的那份）读回来，还原成
 * [AexImport.AexProfile] 列表，从而复用「预览 → 勾选 → 冲突处理 → 写入」这条既有链路。
 *
 * ## 为什么需要它
 *
 * 导出侧（[com.dollhouse.app.ui.widget.ExportSheet]）产出的是一份私有信封：
 *
 * ```json
 * {
 *   "kind": "dollhouse.providers",
 *   "ver": 1,
 *   "exportedAt": 1750000000000,
 *   "includePlainKeys": false,
 *   "providers": [ ... Provider.toJson() ... ],
 *   "models": [ ... AiModel.toJson() ... ]
 * }
 * ```
 *
 * 而导入侧的旧口径只认 AEX（`.akcp`）与 AKCP（`.aivora`）两种二进制 Magic，
 * `AexImport.acceptsName` 还额外要求后缀是 `.akcp`。于是**自己导出的文件自己读不回来**，
 * 导出按钮事实上只能当「给人看的备份」用，闭环是断的。
 *
 * 本类补上这一段：解信封 → 逐条映射成 [AexImport.AexProfile] → 交给同一个导入面板。
 *
 * ## 与 AEX 的关系
 *
 * **完全独立**。AEX 是公开协议、面向跨软件交换；本类是 App 私有格式、只服务自己。
 * 两者在 [AexImport.detect] 里的判定顺序也是分开的：先 AEX，再 AKCP，最后才轮到本格式
 * （见该方法的注释）—— 保证外部文件永远走原来那条路，不会被本格式截胡。
 *
 * ## 密钥策略（用户定案）
 *
 * 导出时若没勾「包含密钥」，文件里的 `apiKey` 是 `KeyVault.mask()` 打码后的样子
 * （例如 `sk-1***…**19`）。这种值**绝不能当成真密钥写进库**：
 * 写进去以后请求必然 401，而用户会以为是自己填错了。
 *
 * 所以这里的处理是：
 * - 打码值 → [AexImport.AexProfile.hasApiKey] = false、`apiKey` 留空；
 * - 覆盖同名供应商时，由写入侧按 `hasApiKey` 判断**不要用空值覆盖**原有密钥。
 *
 * 判据是「看起来像不像打码值」，不是「是不是空串」——用户当初真的什么都没填时，
 * 导出的是空串，那种情况下覆盖成空是正确行为（原值本来就是空）。
 */
object DollhouseJsonImport {

    private const val TAG = "DollhouseJsonImport"

    /** 本格式的信封标识。与 [com.dollhouse.app.ui.widget.ExportSheet] 的 `KIND` 必须一致。 */
    const val KIND = "dollhouse.providers"

    /**
     * 本解析器支持的最高版本号。
     *
     * 【为什么不是「只认 1」】`ver` 是为了将来加字段时能明确拒绝，而不是现在就锁死。
     *   只要信封还是这个结构，`ver` 更大也只意味着「多了我们不认识的字段」，
     *   按「未知字段忽略」处理即可；真正的结构断层（例如 `providers` 改名）才会改 `kind`。
     */
    private const val MAX_VER = 1

    /** 单文件上限，与 AEX 侧保持一致（16 MiB），防止误选巨大文件把内存吃穿。 */
    private const val MAX_BYTES = 16 * 1024 * 1024

    /* ============================ 判定 ============================ */

    /**
     * 这段字节是不是本 App 导出的 JSON。
     *
     * 【判据两条，都必须满足】
     * ① 去掉前导空白后以 `{` 开头 —— 排除二进制与其它文本；
     * ② 文本里出现 `"kind":"dollhouse.providers"` —— 信封标识。
     *    这里对空白做了容忍（`"kind" : "dollhouse.providers"` 也认），
     *    因为 JSON 允许任意空白，而我们自己导出时用的是 `toString(2)` 缩进格式。
     *
     * 【为什么不看后缀】用户可能把文件改名、也可能经由其它 App 转存。
     *   规范（以及用户明确要求）都是「只认内容」。
     *
     * 【为什么不用 JSONObject 解析来判定】判定要足够便宜：它会在**每个**被分享进来的
     *   文件上跑一遍（可能是几 MB 的二进制）。先做字符串包含检查，命中后才真正解析。
     */
    @JvmStatic
    fun looksLike(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) {
            return false
        }
        val head = String(bytes, Charsets.UTF_8)
        var i = 0
        while (i < head.length && head[i].isWhitespace()) {
            i++
        }
        if (i >= head.length || head[i] != '{') {
            return false
        }
        return containsKind(head)
    }

    /** 手写扫描而非正则：正则要在几 MB 文本上回溯，这里只需主串一次线性扫描。 */
    private fun containsKind(text: String): Boolean {
        val needle = "\"kind\""
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) {
                return false
            }
            var p = at + needle.length
            while (p < text.length && text[p].isWhitespace()) {
                p++
            }
            if (p < text.length && text[p] == ':') {
                p++
                while (p < text.length && text[p].isWhitespace()) {
                    p++
                }
                if (p < text.length && text[p] == '"') {
                    if (text.startsWith(KIND, p + 1)) {
                        return true
                    }
                }
            }
            from = at + needle.length
        }
    }

    /* ============================ 解析 ============================ */

    /** 解析结果：要么是一批可导入的配置，要么是一条可读的错误说明。 */
    class Result {
        var profiles: MutableList<AexImport.AexProfile> = ArrayList()
        /** 非空表示失败，直接上屏给用户看。 */
        var error: String = ""
        /** 本次文件里被忽略的模型条数（供应商匹配不上时丢弃），仅用于预览提示。 */
        var droppedModels: Int = 0
        /** 文件版本比本版支持的更新时的提示；空表示无提示。 */
        var notice: String = ""
    }

    /**
     * 把完整字节解析成配置列表。
     *
     * 【失败一律给可读中文】本格式是自家的，出错基本只可能是「文件被改坏 / 选错了文件」，
     *   没必要区分到错误码级别。
     */
    @JvmStatic
    fun parse(bytes: ByteArray?): Result {
        val out = Result()
        if (bytes == null || bytes.isEmpty()) {
            out.error = "文件是空的。"
            return out
        }
        if (bytes.size > MAX_BYTES) {
            out.error = "文件过大（超过 16 MB），可能不是导出文件。"
            return out
        }
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            Logs.w(TAG, "解析 JSON 失败", t)
            out.error = "文件内容不是有效的 JSON。"
            return out
        }
        // 【再校验一次 kind】looksLike 已经查过，但那是宽松的字符串包含；
        //   这里是解析后的权威判定，防止「文本里恰好出现这个字符串」的误判。
        if (KIND != root.optString("kind", "")) {
            out.error = "这不是 Dollhouse 导出的配置文件。"
            return out
        }
        val ver = root.optInt("ver", 0)
        if (ver > MAX_VER) {
            // 【不拒绝，只提示】结构没变，多了字段而已；硬拒会让用户白白导入不了。
            out.notice = "该文件版本较新（v$ver），部分字段可能无法识别。"
        }
        val providers = root.optJSONArray("providers") ?: JSONArray()
        if (providers.length() == 0) {
            out.error = "文件里没有任何供应商配置。"
            return out
        }
        // 【模型按供应商归组】导出信封把 models 平铺在顶层、靠 providerId 关联。
        //   原样搬过来会导致「所有模型都挂到第一个供应商上」，所以先建索引。
        val modelsByProvider = indexModels(root.optJSONArray("models"))
        val keptModelIds = HashSet<String>()
        for (i in 0 until providers.length()) {
            val o = providers.optJSONObject(i) ?: continue
            val p = toProfile(o)
            if (p.name.isBlank()) {
                // 没有名字的条目在列表里无法勾选，也无法落库（ProviderStore 要求 name）。
                continue
            }
            val list = modelsByProvider[o.optString("id", "")]
            if (list != null) {
                for (m in list) {
                    p.models.add(m)
                    keptModelIds.add("${o.optString("id", "")}\u0000$m")
                }
            }
            out.profiles.add(p)
        }
        if (out.profiles.isEmpty()) {
            out.error = "文件里的供应商条目都缺少名称，无法导入。"
            return out
        }
        // 统计被丢弃的模型（供应商在文件里不存在）。
        var total = 0
        val modelsArr = root.optJSONArray("models")
        if (modelsArr != null) {
            total = modelsArr.length()
        }
        out.droppedModels = (total - keptModelIds.size).coerceAtLeast(0)
        return out
    }

    /**
     * 把 `models` 数组按 `providerId` 归组，值是该供应商下的模型显示名列表。
     *
     * 【为什么用显示名而不是模型 id】[AexImport.AexProfile.models] 的语义就是「模型名列表」，
     *   写入侧（`AexImportSheet`）会拿这些名字去 `ModelStore.saveModel` 建新记录。
     *   本 App 的模型 id 是本机自增的，跨设备/跨次导出没有意义。
     */
    private fun indexModels(arr: JSONArray?): Map<String, MutableList<String>> {
        val out = HashMap<String, MutableList<String>>()
        if (arr == null) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pid = o.optString("providerId", "")
            if (pid.isEmpty()) {
                continue
            }
            // `displayName` 是界面展示名；老数据可能只有 `name`。
            val name = o.optString("displayName", o.optString("name", "")).trim()
            if (name.isEmpty()) {
                continue
            }
            out.getOrPut(pid) { ArrayList() }.add(name)
        }
        return out
    }

    /**
     * 单条供应商 → [AexImport.AexProfile]。
     *
     * 【为什么不直接用 Provider.fromJson】那是对的路线，但它会读 `toJson` 写出的**全部**字段
     *   （`chatPath` / `useResponseApi` / `allowCleartext` / 时间戳…），而导入面板只认
     *   [AexImport.AexProfile] 这几项。这里刻意按 AexProfile 的形状映射，让「自家 JSON」
     *   与「外部 AEX」在预览与写入侧走**同一条**代码，避免出现两套落库逻辑各自漂移。
     *   被舍弃的字段见 [buildProvider]，那里会把它们尽量带回去。
     */
    private fun toProfile(o: JSONObject): AexImport.AexProfile {
        val p = AexImport.AexProfile()
        // 【v0.2.0】把原始条目挂到 profile 上：写入时靠它补回 AexProfile 装不下的字段。
        //   挂在这里而不是另存旁路，是为了让“这份数据属于哪次导入”天然随对象生命周期走。
        p.sourceProvider = o
        p.id = o.optString("id", "")
        p.name = o.optString("name", "").trim()
        p.provider = p.name
        val baseUrl = o.optString("baseUrl", "").trim()
        p.baseUrl = baseUrl
        p.hasBaseUrl = baseUrl.isNotEmpty()
        val rawKey = o.optString("apiKey", "")
        // 【打码值不写入】见类注释的「密钥策略」。
        if (rawKey.isEmpty() || isMaskedKey(rawKey)) {
            p.apiKey = ""
            p.hasApiKey = false
        } else {
            p.apiKey = rawKey
            p.hasApiKey = true
        }
        return p
    }

    /**
     * 判断某个密钥是不是「打码后的样子」。
     *
     * 【为什么需要这个函数】导出侧打码走 [KeyVault.mask]。它的实现是把中间替换成 `*`，
     *   但具体形态可能随版本调整，所以这里不硬编码某一种掩码字符，而是按「**含有连续 3 个
     *   以上掩码字符**」判断。真实 API Key 里偶尔会出现单个 `*`（极罕见），但不会有 `***`。
     *
     * 【为什么不用长度判断】短密钥会被整段打码成 `***`，长度不可靠；掩码字符的连续性才稳。
     */
    @JvmStatic
    fun isMaskedKey(key: String): Boolean {
        if (key.isEmpty()) {
            return false
        }
        var run = 0
        for (c in key) {
            if (c == '*' || c == '\u2022' || c == '·' || c == '…') {
                run++
                if (run >= 3) {
                    return true
                }
            } else {
                run = 0
            }
        }
        // 单个省略号实现（`sk-1…9`）也算打码：真密钥不会用省略号。
        return key.contains('\u2026')
    }

    /**
     * 从原始条目里抢救那些 [AexImport.AexProfile] 装不下、但对本 App 有意义的字段。
     *
     * 【为什么需要】导出是「完整备份」，导回来若把 `chatPath` / `useResponseApi` /
     *   `allowCleartext` 丢掉，用户重建出来的供应商在行为上就和备份前不一样了
     *   （例如自建站必须开 `allowCleartext`，丢了就会「导入成功但一发请求就失败」）。
     *
     * @param profileName 预览里勾选的那条配置名，用于回到原始数组里找同一条。
     * @return 补全后的 Provider；找不到对应条目时返回 null。
     */
    @JvmStatic
    fun buildProvider(bytes: ByteArray?, profileName: String): Provider? {
        if (bytes == null) {
            return null
        }
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            return null
        }
        val arr = root.optJSONArray("providers") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name", "").trim() != profileName) {
                continue
            }
            val p = Provider.fromJson(o)
            // 【密钥】打码值一律清空，交给写入侧的 `hasApiKey` 语义决定是否保留原值。
            if (isMaskedKey(p.apiKey)) {
                p.apiKey = ""
            }
            return p
        }
        return null
    }

    /**
     * 取出文件里某个供应商的全部模型记录（含 `contextWindow` 等 [AexImport.AexProfile] 装不下的字段）。
     *
     * @param providerId 原始条目里的 id
     */
    @JvmStatic
    fun buildModels(bytes: ByteArray?, providerId: String): List<AiModel> {
        val out = ArrayList<AiModel>()
        if (providerId.isEmpty()) {
            return out
        }
        if (bytes == null) {
            return out
        }
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            return out
        }
        val arr = root.optJSONArray("models") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("providerId", "") != providerId) {
                continue
            }
            out.add(AiModel.fromJson(o))
        }
        return out
    }

    /** 原始条目里第一条供应商的 id（供 [buildModels] 查询）。 */
    @JvmStatic
    fun providerIdOf(bytes: ByteArray?, profileName: String): String {
        if (bytes == null) {
            return ""
        }
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            return ""
        }
        val arr = root.optJSONArray("providers") ?: return ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name", "").trim() == profileName) {
                return o.optString("id", "")
            }
        }
        return ""
    }
}
