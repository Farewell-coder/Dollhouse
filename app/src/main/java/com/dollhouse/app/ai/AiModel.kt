package com.dollhouse.app.ai

import com.dollhouse.app.core.Logs
import org.json.JSONObject

/**
 * 【职责】模型实体（规格书 Model）：挂在某个供应商下的一个可用模型。
 *
 * 【交互】序列化落盘交给 ProviderStore；模度 / 能力用布尔与逗号串表达，够用且好读。
 *
 * 【坑】providerId 是弱关联：供应商被删时由 ProviderStore 级联清掉它的模型，
 *        所以读到的模型正常情况下都能找到归属，找不到的按「孤儿」跳过不显示。
 */
class AiModel {

    companion object {
        /** 模型类型。 */
        const val KIND_CHAT = "CHAT"
        const val KIND_IMAGE = "IMAGE"
        const val KIND_EMBEDDING = "EMBEDDING"
        @JvmField
        val KINDS = arrayOf(KIND_CHAT, KIND_IMAGE, KIND_EMBEDDING)

        /** 模态取值。 */
        const val MOD_TEXT = "TEXT"
        const val MOD_IMAGE = "IMAGE"
        const val MOD_AUDIO = "AUDIO"

        /** 模型类型展示名。 */
        @JvmStatic
        fun kindLabel(k: String?): String {
            if (KIND_IMAGE == k) {
                return "图片"
            }
            if (KIND_EMBEDDING == k) {
                return "向量"
            }
            return "聊天"
        }

        /** 模态代号转界面文字。 */
        @JvmStatic
        fun modLabel(m: String?): String {
            if (MOD_IMAGE == m) {
                return "图片"
            }
            if (MOD_AUDIO == m) {
                return "音频"
            }
            return "文本"
        }

        @JvmStatic
        fun fromJson(o: JSONObject?): AiModel {
            val m = AiModel()
            if (o == null) {
                return m
            }
            m.id = o.optString("id", "")
            m.displayName = o.optString("displayName", "")
            m.providerId = o.optString("providerId", "")
            m.kind = o.optString("kind", KIND_CHAT)
            m.inputModalities = o.optString("inputModalities", MOD_TEXT)
            m.outputModalities = o.optString("outputModalities", MOD_TEXT)
            m.capVision = o.optBoolean("capVision", false)
            m.capToolCall = o.optBoolean("capToolCall", false)
            m.capReasoning = o.optBoolean("capReasoning", false)
            m.capStream = o.optBoolean("capStream", true)
            m.enabled = o.optBoolean("enabled", true)
            m.sortOrder = o.optInt("sortOrder", 0)
            return m
        }
    }

    var id: String = ""
    var displayName: String = ""
    var providerId: String = ""
    var kind: String = KIND_CHAT
    /** 逗号分隔的输入模态，如 "TEXT,IMAGE"。 */
    var inputModalities: String = MOD_TEXT
    var outputModalities: String = MOD_TEXT
    var capVision: Boolean = false
    var capToolCall: Boolean = false
    var capReasoning: Boolean = false
    var capStream: Boolean = true
    var enabled: Boolean = true
    var sortOrder: Int = 0

    /** 能力标签：按固定顺序返回已开启的能力名，供列表页拼 chip。 */
    fun capabilityLabels(): MutableList<String> {
        val out = ArrayList<String>()
        if (capVision) {
            out.add("视觉")
        }
        if (capToolCall) {
            out.add("工具")
        }
        if (capReasoning) {
            out.add("推理")
        }
        if (capStream) {
            out.add("流式")
        }
        return out
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("id", id)
            o.put("displayName", displayName)
            o.put("providerId", providerId)
            o.put("kind", kind)
            o.put("inputModalities", inputModalities)
            o.put("outputModalities", outputModalities)
            o.put("capVision", capVision)
            o.put("capToolCall", capToolCall)
            o.put("capReasoning", capReasoning)
            o.put("capStream", capStream)
            o.put("enabled", enabled)
            o.put("sortOrder", sortOrder)
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
        return o
    }

    /** 深拷贝：编辑页改副本，取消即丢弃。 */
    fun copy(): AiModel {
        return fromJson(toJson())
    }
}
