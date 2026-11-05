package com.dollhouse.app.data

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * 【职责】解析 Aivora 0.0.8 起提供的 AEX 1.1 深链（明文通道）：
 *
 *     dollhouse://aivora-import?d=<base64url(JSON)>&v=1
 *
 * 与 `.akcp` 文件通道的差别（见 Aivora `protocol/AEX-DEEPLINK.md`）：
 *   · 不加密、不需要 52 字符凭证；
 *   · 只承载 1 条配置；
 *   · 链接是明文，安全边界依赖「用户在 Aivora 里明确点选了 Dollhouse」。
 *
 * 【安全约束】本对象不写任何日志，不打印链接 / `d` 参数 / `apiKey`。
 *   调用方只应在用户确认后把 [Payload] 写进 ProviderStore。
 *
 * 【可测试性】解析入口是 [parseLink]（纯字符串），不依赖 android.net.Uri，
 *   因此可以在 JVM 单测里直接覆盖缺参、乱码、版本过新等负向用例。
 */
object AexDeepLink {

    /** Aivora 已登记给 Dollhouse 的 scheme。 */
    const val SCHEME = "dollhouse"

    /** 深链入口 host。 */
    const val HOST = "aivora-import"

    /** 信封版本（与 AEX protocolVersion 独立）。 */
    const val ENVELOPE_VERSION = 1

    /** 载荷种类，必须先校验，防止 scheme 被别的意图复用。 */
    const val KIND = "aivora.provider"

    /** 载荷协议标识。 */
    const val SCHEMA = "AEX"

    /** 规范建议：超过 64 KiB 的 URI 直接拒绝（Android Binder 也有长度限制）。 */
    const val MAX_LINK_LENGTH = 64 * 1024

    /** 解析成功后的单条配置。字段语义与 AEX 1.1 深链文档一致。 */
    data class Payload(
        val name: String,
        val provider: String,
        val baseUrl: String,
        val apiKey: String,
        val description: String,
        val exportedAt: String,
        val protocolVersion: String,
        val exporterName: String,
        val exporterVersion: String
    )

    /** 失败原因；界面文案在 [Result.message] 里统一成用户能看懂的话。 */
    enum class Reason {
        NOT_AIVORA,
        INCOMPLETE,
        TOO_LONG,
        BAD_ENCODING,
        BAD_JSON,
        KIND_MISMATCH,
        UNSUPPORTED_VERSION,
        INVALID_CONTENT
    }

    class Result(
        val payload: Payload?,
        val reason: Reason? = null
    ) {
        fun ok(): Boolean = payload != null && reason == null

        /** 用户可见提示；不暴露 JSON 解析器 / offset 之类的技术细节。 */
        fun message(): String {
            return when (reason) {
                null -> ""
                Reason.NOT_AIVORA -> "这不是 Aivora 的导入链接。"
                Reason.INCOMPLETE -> "链接不完整，请重新从 Aivora 导出。"
                Reason.TOO_LONG -> "链接过长，已拒绝。"
                Reason.BAD_ENCODING, Reason.BAD_JSON -> "链接已损坏，请重新导出。"
                Reason.KIND_MISMATCH -> "这不是 Aivora 的导入链接。"
                Reason.UNSUPPORTED_VERSION -> "链接来自更新版本，请升级软件后再试。"
                Reason.INVALID_CONTENT -> "链接内容不完整，请重新导出。"
            }
        }
    }

    /**
     * 解析 `dollhouse://aivora-import?...`。
     *
     * 【为什么用 java.net.URI 而不是 android.net.Uri】纯 JVM 单测可以直接跑，
     *   不必依赖 Android 框架桩；Activity 侧只把 `intent.data.toString()` 传进来。
     */
    @JvmStatic
    fun parseLink(link: String?): Result {
        if (link.isNullOrEmpty()) {
            return Result(null, Reason.INCOMPLETE)
        }
        if (link.length > MAX_LINK_LENGTH) {
            return Result(null, Reason.TOO_LONG)
        }
        val uri = try {
            URI(link)
        } catch (t: Throwable) {
            return Result(null, Reason.BAD_ENCODING)
        }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) {
            return Result(null, Reason.NOT_AIVORA)
        }
        val host = uri.host ?: uri.authority?.substringBefore(':')
        if (!HOST.equals(host, ignoreCase = true)) {
            return Result(null, Reason.NOT_AIVORA)
        }
        val query = uri.rawQuery ?: return Result(null, Reason.INCOMPLETE)
        var encoded: String? = null
        var envelope: String? = null
        for (pair in query.split('&')) {
            if (pair.isEmpty()) {
                continue
            }
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            val decoded = try {
                // base64url 不含 `+`，但 query 里仍可能出现百分号编码；URLDecoder 的
                // `+`→空格行为在这里不会误伤合法 d 值，故可以直接用。
                URLDecoder.decode(value, "UTF-8")
            } catch (t: Throwable) {
                return Result(null, Reason.BAD_ENCODING)
            }
            when (key) {
                "d" -> encoded = decoded
                "v" -> envelope = decoded
            }
        }
        if (encoded.isNullOrEmpty() || envelope.isNullOrEmpty()) {
            return Result(null, Reason.INCOMPLETE)
        }
        val envelopeVersion = envelope.toIntOrNull() ?: return Result(null, Reason.INCOMPLETE)
        if (envelopeVersion > ENVELOPE_VERSION) {
            return Result(null, Reason.UNSUPPORTED_VERSION)
        }
        if (envelopeVersion < 1) {
            return Result(null, Reason.INCOMPLETE)
        }

        val json = decodeBase64Url(encoded) ?: return Result(null, Reason.BAD_ENCODING)
        val obj = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return Result(null, Reason.BAD_JSON)
        }
        val kind = obj.optString("kind", "")
        if (kind != KIND) {
            return Result(null, Reason.KIND_MISMATCH)
        }
        if (obj.optString("schema", "") != SCHEMA) {
            return Result(null, Reason.KIND_MISMATCH)
        }
        val payloadEnvelope = if (obj.has("ver")) obj.optInt("ver", -1) else 1
        if (payloadEnvelope > ENVELOPE_VERSION) {
            return Result(null, Reason.UNSUPPORTED_VERSION)
        }
        if (payloadEnvelope < 1) {
            return Result(null, Reason.INVALID_CONTENT)
        }
        val protocolVersion = obj.optString("protocolVersion", "")
        val major = protocolVersion.substringBefore('.').toIntOrNull()
            ?: return Result(null, Reason.INVALID_CONTENT)
        if (major != 1) {
            return Result(null, Reason.UNSUPPORTED_VERSION)
        }
        val name = obj.optString("name", "").trim()
        val baseUrl = obj.optString("baseUrl", "").trim()
        if (name.isEmpty() || baseUrl.isEmpty()) {
            return Result(null, Reason.INVALID_CONTENT)
        }
        val exporter = obj.optJSONObject("exporter")
        return Result(
            Payload(
                name = name,
                provider = obj.optString("provider", ""),
                baseUrl = baseUrl,
                // 规范允许空串（表示用户没填 Key），不把空串当成缺失。
                apiKey = obj.optString("apiKey", ""),
                description = obj.optString("description", ""),
                exportedAt = obj.optString("exportedAt", ""),
                protocolVersion = protocolVersion,
                exporterName = exporter?.optString("name", "") ?: "",
                exporterVersion = exporter?.optString("version", "") ?: ""
            )
        )
    }

    /** 把深链载荷翻译成 AEX 导入链路使用的 profile（models 为空，稍后自行拉取）。 */
    @JvmStatic
    fun toProfile(payload: Payload): AexImport.AexProfile {
        val p = AexImport.AexProfile()
        p.id = "aivora-deeplink"
        p.name = payload.name
        p.provider = payload.provider
        p.hasBaseUrl = true
        p.baseUrl = payload.baseUrl
        p.hasApiKey = true
        p.apiKey = payload.apiKey
        p.hasDescription = payload.description.isNotEmpty()
        p.description = payload.description
        return p
    }

    /** 确认页只显示密钥尾 4 位；空 Key 明确写「未提供」。 */
    @JvmStatic
    fun maskKeyTail(key: String?): String {
        if (key.isNullOrEmpty()) {
            return "（未提供）"
        }
        if (key.length <= 4) {
            return "••••"
        }
        return "••••" + key.takeLast(4)
    }

    /** base64url（RFC 4648 §5，无填充）解码。 */
    private fun decodeBase64Url(text: String): String? {
        return try {
            val padded = text.replace('-', '+').replace('_', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }
}
