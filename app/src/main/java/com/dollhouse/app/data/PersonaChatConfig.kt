package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.core.Logs
import org.json.JSONObject

/**
 * 【职责】「人设 × 会话」维度的聊天参数覆盖（r27 新增）。
 *
 * 【为什么需要】全局只有一份 think_level / stream_output / vision_model，
 *   而用户会对不同人偶、甚至同一人偶的不同会话提出相反的要求：
 *   「这个角色要长回复、高温度」「这个剧情会话必须不思考」「这个会话换识图模型」。
 *   没有按作用域隔离的配置，只能全局来回改，一改就把别的会话一起带歪。
 *
 * 【怎么表达继承】每个字段都是 **可空** 的：`null` = 继承全局，不是「未设置」。
 *   · 新建作用域默认全部为 null —— 行为与升级前完全一致（迁移默认）；
 *   · 只覆盖想改的那一两项，其余继续跟随全局，全局改了它们也跟着变；
 *   · 需要「冻结当时全局设置」时用 [PersonaChatConfigStore.snapshotGlobal]。
 *
 * 【存法】与全工程一致：org.json 手写编解码 + SharedPreferences 字符串值。
 *   读盘容错沿用 [PersonaSpec] 的口径：字段缺失 / 类型不对 / JSON 损坏一律回落继承，
 *   绝不抛异常。同时接受 snake_case 旧键名，便于从旧配置或导出的文件迁移。
 *
 * 【边界】本类只描述参数，不发请求、不碰 UI；请求体怎么写仍由 ChatPanel 决定。
 */
class PersonaChatConfig {

    /** 该会话是否启用。null = 跟随全局。 */
    var enabled: Boolean? = null

    /** 供应商 id。null / 空串 = 跟随全局当前供应商。 */
    var providerId: String? = null

    /** 模型内部记录 id（AiModel.id，不是请求体里的模型名）。null / 空串 = 跟随全局。 */
    var modelId: String? = null

    /** 独立识图模型 id。null / 空串 = 跟随全局。 */
    var visionModelId: String? = null

    /** 思考档位 0~5。null = 跟随全局。 */
    var thinkLevel: Int? = null

    /** 温度 0.0~2.0。null = 跟随全局。 */
    var temperature: Double? = null

    /** top_p 0.0~1.0。null = 跟随全局。 */
    var topP: Double? = null

    /** max_tokens；0 / 负数视为继承（与 DeepSeekClient 的「>0 才发」口径一致）。 */
    var maxTokens: Int? = null

    /** 是否流式输出。null = 跟随全局。 */
    var streamOutput: Boolean? = null

    /** 是否一项覆盖都没有（用于界面显示「跟随全局」）。 */
    fun isInheritAll(): Boolean {
        return enabled == null
                && norm(providerId) == null
                && norm(modelId) == null
                && norm(visionModelId) == null
                && thinkLevel == null
                && temperature == null
                && topP == null
                && positive(maxTokens) == null
                && streamOutput == null
    }

    /** 字段级复制，方便调用方在 UI 草稿态里改完再整份落盘。 */
    fun copy(): PersonaChatConfig {
        val v = PersonaChatConfig()
        v.enabled = enabled
        v.providerId = providerId
        v.modelId = modelId
        v.visionModelId = visionModelId
        v.thinkLevel = thinkLevel
        v.temperature = temperature
        v.topP = topP
        v.maxTokens = maxTokens
        v.streamOutput = streamOutput
        return v
    }

    /**
     * 落盘编码。
     * 【为什么把继承写成显式 JSON null】键存在且值为 null = 「这一项明确继承」，
     *   与「老版本没有这个键」在语义上一致，但人眼和导出文件里一眼能看出覆盖范围。
     * 【为什么在编码时归一化】字段是公开可写的，调用方可能塞进 "" / 9.9 / -1，
     *   这里统一收口，避免脏值落盘后再由每个读取点各自防御。
     */
    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("schema", SCHEMA)
            o.put("ver", SCHEMA_VER)
            putNullable(o, "enabled", enabled)
            putNullable(o, "providerId", norm(providerId))
            putNullable(o, "modelId", norm(modelId))
            putNullable(o, "visionModelId", norm(visionModelId))
            putNullable(o, "thinkLevel", thinkLevel?.coerceIn(0, THINK_MAX))
            putNullable(o, "temperature", temperature?.coerceIn(TEMP_MIN, TEMP_MAX))
            putNullable(o, "topP", topP?.coerceIn(0.0, 1.0))
            putNullable(o, "maxTokens", positive(maxTokens)?.coerceAtMost(MAX_TOKENS_LIMIT))
            putNullable(o, "streamOutput", streamOutput)
        } catch (ignored: Throwable) {
        }
        return o
    }

    /**
     * 与全局配置合成一份「所有字段都非空」的结果。
     *
     * 【优先级】本作用域显式值 > 全局显式值 > 应用默认值。
     * 【为什么全局也要过一遍 [PersonaChatConfig]】全局快照本身可能只写了部分字段
     *   （例如未来全局页面也只暴露了温度），缺的那部分必须继续往默认值回落，
     *   否则 [EffectiveChatConfig] 的非空承诺就破了。
     */
    fun resolve(global: PersonaChatConfig?): EffectiveChatConfig {
        val g = global ?: PersonaChatConfig()
        return EffectiveChatConfig(
            enabled = enabled ?: g.enabled ?: DEFAULT_ENABLED,
            providerId = norm(providerId) ?: norm(g.providerId) ?: "",
            modelId = norm(modelId) ?: norm(g.modelId) ?: "",
            visionModelId = norm(visionModelId) ?: norm(g.visionModelId) ?: "",
            thinkLevel = (thinkLevel ?: g.thinkLevel ?: DEFAULT_THINK_LEVEL).coerceIn(0, THINK_MAX),
            temperature = (temperature ?: g.temperature ?: DEFAULT_TEMPERATURE)
                .coerceIn(TEMP_MIN, TEMP_MAX),
            topP = (topP ?: g.topP ?: DEFAULT_TOP_P).coerceIn(0.0, 1.0),
            maxTokens = (positive(maxTokens) ?: positive(g.maxTokens) ?: 0)
                .coerceAtMost(MAX_TOKENS_LIMIT),
            streamOutput = streamOutput ?: g.streamOutput ?: DEFAULT_STREAM
        )
    }

    companion object {

        /** 自有 JSON 标识，便于导出 / 迁移时识别。 */
        const val SCHEMA = "persona_chat_config"

        /** 结构版本；加字段不升版（缺字段即继承），改语义才升。 */
        const val SCHEMA_VER = 1

        /** 与 PetPrefs.THINK_NAMES 的档位数保持一致。 */
        const val THINK_MAX = 5

        /** 与 PetPrefs.THINK_DEFAULT 对齐。 */
        const val DEFAULT_THINK_LEVEL = 1

        /** 与 ChatPanel 当前硬编码的温度 / top_p 对齐：迁移时不改变用户体感。 */
        const val DEFAULT_TEMPERATURE = 0.7
        const val DEFAULT_TOP_P = 0.9

        const val DEFAULT_ENABLED = true
        const val DEFAULT_STREAM = true

        /** max_tokens 的硬上限：挡住手改文件里的荒唐值，避免请求被服务端直接 400。 */
        const val MAX_TOKENS_LIMIT = 1_000_000

        private const val TEMP_MIN = 0.0
        private const val TEMP_MAX = 2.0

        /**
         * 迁移默认：全部继承全局。
         * 【为什么不是复制一份全局值】复制会把「升级那一刻的全局设置」固化进每个会话，
         *   之后用户在全局改温度 / 换模型，老会话全部无感 —— 那正是这次要解决的问题。
         */
        @JvmStatic
        fun inheritAll(): PersonaChatConfig {
            return PersonaChatConfig()
        }

        /** 编码为可落盘的 JSON 文本；任何异常都回落成「全部继承」的合法 JSON。 */
        @JvmStatic
        fun encode(cfg: PersonaChatConfig?): String {
            return try {
                (cfg ?: PersonaChatConfig()).toJson().toString()
            } catch (ignored: Throwable) {
                inheritAll().toJson().toString()
            }
        }

        /**
         * 安全解析。
         * 【容错】空串 / 非 JSON / 顶层不是对象 / 字段类型错误都不抛：
         *   · 损坏的整份 → 全部继承；
         *   · 只有某一项坏掉 → 只丢那一项，其余照读（与 [PersonaSpec.fromJson] 一致）。
         */
        @JvmStatic
        fun decode(raw: String?): PersonaChatConfig {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) {
                return inheritAll()
            }
            val o = try {
                JSONObject(s)
            } catch (ignored: Throwable) {
                return inheritAll()
            }
            return fromJson(o)
        }

        @JvmStatic
        fun fromJson(o: JSONObject?): PersonaChatConfig {
            val v = PersonaChatConfig()
            if (o == null) {
                return v
            }
            // 先看新键，再看 snake_case 旧键；键存在即视为「本项有明确表态」，
            // 即使值是 null 也不再往旧键回落（避免迁移时把「明确继承」读成旧覆盖值）。
            v.enabled = asBool(first(o, "enabled"))
            v.providerId = asString(first(o, "providerId", "provider_id"))
            v.modelId = asString(first(o, "modelId", "model_id"))
            v.visionModelId = asString(
                first(
                    o,
                    "visionModelId",
                    "vision_model_id",
                    "visionModel",
                    "vision_model"
                )
            )
            v.thinkLevel = asInt(first(o, "thinkLevel", "think_level"))
                ?.coerceIn(0, THINK_MAX)
            v.temperature = asDouble(first(o, "temperature"))
                ?.coerceIn(TEMP_MIN, TEMP_MAX)
            v.topP = asDouble(first(o, "topP", "top_p"))
                ?.coerceIn(0.0, 1.0)
            v.maxTokens = asInt(first(o, "maxTokens", "max_tokens", "max_tokens_limit"))
                ?.let { positive(it) }
                ?.coerceAtMost(MAX_TOKENS_LIMIT)
            v.streamOutput = asBool(first(o, "streamOutput", "stream_output"))
            return v
        }

        /* ------------------------------ 解析工具 ------------------------------ */

        /** 「键不存在」与「键存在但值为 null」必须区分，故用哨兵而不是 null。 */
        private val MISSING = Any()

        /**
         * 取第一个存在的键。
         * 【为什么不是 optString 到底】`JSONObject.optString` 会把缺失键读成 ""，
         *   于是「没配」和「明确配成空」就分不开了 —— 而这里的空恰好等于继承。
         */
        private fun first(o: JSONObject, vararg keys: String): Any? {
            for (k in keys) {
                if (o.has(k)) {
                    val raw = o.opt(k)
                    return if (raw == null || raw === JSONObject.NULL) null else raw
                }
            }
            return MISSING
        }

        private fun asString(v: Any?): String? {
            if (v === MISSING || v == null) {
                return null
            }
            return when (v) {
                is String -> norm(v)
                is Number -> norm(v.toString())
                is Boolean -> null
                else -> null
            }
        }

        private fun asBool(v: Any?): Boolean? {
            if (v === MISSING || v == null) {
                return null
            }
            return when (v) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> when (v.trim().lowercase()) {
                    "true", "1", "on", "yes" -> true
                    "false", "0", "off", "no" -> false
                    else -> null
                }
                else -> null
            }
        }

        private fun asInt(v: Any?): Int? {
            if (v === MISSING || v == null) {
                return null
            }
            return when (v) {
                is Int -> v
                is Number -> {
                    val d = v.toDouble()
                    if (d.isNaN() || d.isInfinite()) null else d.toInt()
                }
                is String -> v.trim().toIntOrNull()
                else -> null
            }
        }

        private fun asDouble(v: Any?): Double? {
            if (v === MISSING || v == null) {
                return null
            }
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull()
                else -> null
            } ?: return null
            return if (d.isNaN() || d.isInfinite()) null else d
        }

        /** 空串 = 继承；「inherit」也当继承，方便手写备份文件。 */
        private fun norm(v: String?): String? {
            val t = v?.trim().orEmpty()
            if (t.isEmpty() || "inherit".equals(t, ignoreCase = true)) {
                return null
            }
            return t
        }

        /** max_tokens 的 0 / 负数在工程里的既有语义就是「不发这个字段」= 继承。 */
        private fun positive(v: Int?): Int? {
            return if (v != null && v > 0) v else null
        }

        private fun putNullable(o: JSONObject, key: String, v: Any?) {
            try {
                o.put(key, v ?: JSONObject.NULL)
            } catch (ignored: Throwable) {
            }
        }
    }
}

/** 本作用域配置与全局配置合成后的最终值；所有字段都非空，请求体可直接取用。 */
data class EffectiveChatConfig(
    val enabled: Boolean,
    val providerId: String,
    val modelId: String,
    val visionModelId: String,
    val thinkLevel: Int,
    val temperature: Double,
    val topP: Double,
    val maxTokens: Int,
    val streamOutput: Boolean
)

/**
 * 【职责】[PersonaChatConfig] 的按作用域持久化与全局快照。
 *
 * 【作用域】`personaId + conversationId`，两者都参与键名。
 *   同一人设的不同会话 / 不同人设的同名会话互不可见（切换读写天然隔离）。
 *
 * 【存储】复用 `feiyu_pet` 偏好文件（与 PetPrefs / UpdatePrefs 同一惯例），
 *   键前缀 `chat_cfg_v1_`，不新增存储文件，也不改 PetPrefs 的既有键。
 *
 * 【为什么键要转义】会话 id 由上层生成，可能带 `|`、空格或中文；
 *   直接拼接会让 `(a|b, c)` 与 `(a, b|c)` 撞成同一个键。这里用 URL 编码逐段转义，
 *   再以未编码的 `|` 分隔，碰撞不可能发生。
 */
object PersonaChatConfigStore {

    private const val TAG = "DollhouseChatCfg"
    private const val NAME = "feiyu_pet"
    private const val PREFIX = "chat_cfg_v1_"

    @JvmStatic
    fun scopeKey(personaId: String?, conversationId: String?): String {
        return PREFIX + enc(personaId) + "|" + enc(conversationId)
    }

    /** 读该作用域的覆盖；没有 / 损坏一律返回「全部继承」。 */
    @JvmStatic
    fun load(ctx: Context, personaId: String?, conversationId: String?): PersonaChatConfig {
        val raw = try {
            prefs(ctx).getString(scopeKey(personaId, conversationId), null)
        } catch (t: Throwable) {
            Logs.w(TAG, "load failed", t)
            null
        }
        return PersonaChatConfig.decode(raw)
    }

    /** 写覆盖（整份替换）。空配置也照写：用户可能就是要显式「全部恢复继承」。 */
    @JvmStatic
    fun save(
        ctx: Context,
        personaId: String?,
        conversationId: String?,
        cfg: PersonaChatConfig?
    ) {
        try {
            val key = scopeKey(personaId, conversationId)
            prefs(ctx).edit().putString(key, PersonaChatConfig.encode(cfg)).apply()
        } catch (t: Throwable) {
            Logs.w(TAG, "save failed", t)
        }
    }

    /** 删除该作用域的覆盖（删会话 / 删人设时清理）。 */
    @JvmStatic
    fun clear(ctx: Context, personaId: String?, conversationId: String?) {
        try {
            prefs(ctx).edit().remove(scopeKey(personaId, conversationId)).apply()
        } catch (t: Throwable) {
            Logs.w(TAG, "clear failed", t)
        }
    }

    /** 该作用域是否存过覆盖（不解析内容，供界面显示「已单独配置」）。 */
    @JvmStatic
    fun has(ctx: Context, personaId: String?, conversationId: String?): Boolean {
        return try {
            prefs(ctx).contains(scopeKey(personaId, conversationId))
        } catch (t: Throwable) {
            false
        }
    }

    /** 本作用域最终生效值：覆盖 + 当前全局。 */
    @JvmStatic
    fun effective(
        ctx: Context,
        personaId: String?,
        conversationId: String?
    ): EffectiveChatConfig {
        return load(ctx, personaId, conversationId).resolve(snapshotGlobal(ctx))
    }

    /**
     * 把当前全局设置做成一份「全字段明确」的快照，用于迁移 / 「固化当前设置」。
     *
     * 【和 inheritAll 的分工】默认迁移用 [PersonaChatConfig.inheritAll]（继续跟随全局）；
     *   本方法只在用户明确要求「把这个会话钉死在现在的配置上」时使用。
     * 【读不到的项】温度 / top_p 在当前全局没有可编辑项（ChatPanel 里硬编码），
     *   故写应用默认值；max_tokens 全局也没配，保持 null（继续继承 = 0 = 不发送）。
     */
    @JvmStatic
    fun snapshotGlobal(ctx: Context): PersonaChatConfig {
        val v = PersonaChatConfig()
        try {
            v.enabled = true
            val sel = ProviderStore.current(ctx)
            v.providerId = blankToNull(sel.providerId)
            v.modelId = blankToNull(sel.modelId)
            v.visionModelId = blankToNull(PetPrefs.visionModel(ctx))
            v.thinkLevel = PetPrefs.thinkLevel(ctx)
            v.temperature = PersonaChatConfig.DEFAULT_TEMPERATURE
            v.topP = PersonaChatConfig.DEFAULT_TOP_P
            v.streamOutput = PetPrefs.streamOutput(ctx)
        } catch (t: Throwable) {
            Logs.w(TAG, "snapshotGlobal failed", t)
        }
        return v
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, 0)

    private fun blankToNull(s: String?): String? {
        val t = s?.trim().orEmpty()
        return if (t.isEmpty()) null else t
    }

    private fun enc(s: String?): String {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) {
            return ""
        }
        return try {
            java.net.URLEncoder.encode(t, "UTF-8")
        } catch (t2: Throwable) {
            // UTF-8 一定存在；这里只为满足编译器的受检异常路径，退化时保留原串。
            t
        }
    }
}
