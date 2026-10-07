package com.dollhouse.app.ai

import com.dollhouse.app.core.Logs
import org.json.JSONObject

/**
 * 【职责】供应商实体（规格书 Provider）：一个接口服务商 + 它的连接参数。
 *
 * 【交互】序列化落盘交给 ProviderStore；界面侧直接读写本类的公开字段。
 *
 * 【坑】apiKey 存的是密文（KeyVault 的 enc:1: 前缀），不是明文。
 *        要真密钥一律走 ProviderStore.keyOf(...)，不要直接读这个字段。
 */
class Provider {

    companion object {
        /** 协议取值：落盘用大写字符串，将来加新协议只需往这里加常量。 */
        /** 唯一的协议取值：OpenAI 兼容。第三方中转站与各家官网都按这个协议对接。 */
        const val PROTO_OPENAI = "OPENAI"
        /** 旧数据兼容：历史版本存过 GOOGLE / CLAUDE，读盘时一律归一到 OpenAI 兼容。 */
        const val PROTO_GOOGLE = "GOOGLE"
        const val PROTO_CLAUDE = "CLAUDE"

        /** 协议在界面上的展示名。 */
        @JvmStatic
        fun protoLabel(p: String?): String {
            return "自定义"
        }

        @JvmStatic
        fun fromJson(o: JSONObject?): Provider {
            val p = Provider()
            if (o == null) {
                return p
            }
            p.id = o.optString("id", "")
            p.name = o.optString("name", "")
            // 历史数据里的 GOOGLE / CLAUDE 记录：本版只保留「自定义（OpenAI 兼容）」，
            // 读盘即归一，省得旧记录带着已无 UI 的协议值跑不起来。
            p.protocol = PROTO_OPENAI
            p.apiKey = o.optString("apiKey", "")
            p.baseUrl = o.optString("baseUrl", "")
            p.chatPath = o.optString("chatPath", "")
            p.enabled = o.optBoolean("enabled", true)
            p.useResponseApi = o.optBoolean("useResponseApi", false)
            p.resendHistoryReasoning = o.optBoolean("resendHistoryReasoning", false)
            p.createdAt = o.optLong("createdAt", 0L)
            p.updatedAt = o.optLong("updatedAt", 0L)
            return p
        }
    }

    var id: String = ""
    var name: String = ""
    var protocol: String = PROTO_OPENAI
    /** 密文；空串 = 还没填。 */
    var apiKey: String = ""
    var baseUrl: String = ""
    /** 相对 baseUrl 的对话路径；空串 = 用协议默认值。 */
    var chatPath: String = ""
    var enabled: Boolean = true
    /** 走 Responses 接口而不是 chat/completions（OpenAI 系的新接口）。 */
    var useResponseApi: Boolean = false
    /** 多轮请求时把历史里的 reasoning 字段一并回传。 */
    var resendHistoryReasoning: Boolean = false
    var createdAt: Long = 0L
    var updatedAt: Long = 0L

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("id", id)
            o.put("name", name)
            o.put("protocol", protocol)
            o.put("apiKey", apiKey)
            o.put("baseUrl", baseUrl)
            o.put("chatPath", chatPath)
            o.put("enabled", enabled)
            o.put("useResponseApi", useResponseApi)
            o.put("resendHistoryReasoning", resendHistoryReasoning)
            o.put("createdAt", createdAt)
            o.put("updatedAt", updatedAt)
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
        return o
    }

    /** 深拷贝：编辑页先改副本，点保存才写回，取消即丢弃。 */
    fun copy(): Provider {
        return fromJson(toJson())
    }
}
