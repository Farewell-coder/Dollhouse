package com.dollhouse.app;

import org.json.JSONObject;

/**
 * 【职责】模型实体（规格书 Model）：挂在某个供应商下的一个可用模型。
 *
 * 【交互】序列化落盘交给 ProviderStore；模度 / 能力用布尔与逗号串表达，够用且好读。
 *
 * 【坑】providerId 是弱关联：供应商被删时由 ProviderStore 级联清掉它的模型，
 *        所以读到的模型正常情况下都能找到归属，找不到的按「孤儿」跳过不显示。
 */
public final class AiModel {
    /** 模型类型。 */
    public static final String KIND_CHAT = "CHAT";
    public static final String KIND_IMAGE = "IMAGE";
    public static final String KIND_EMBEDDING = "EMBEDDING";
    public static final String[] KINDS = {KIND_CHAT, KIND_IMAGE, KIND_EMBEDDING};

    /** 模态取值。 */
    public static final String MOD_TEXT = "TEXT";
    public static final String MOD_IMAGE = "IMAGE";
    public static final String MOD_AUDIO = "AUDIO";

    public String id = "";
    public String displayName = "";
    public String providerId = "";
    public String kind = KIND_CHAT;
    /** 逗号分隔的输入模态，如 "TEXT,IMAGE"。 */
    public String inputModalities = MOD_TEXT;
    public String outputModalities = MOD_TEXT;
    public boolean capVision = false;
    public boolean capToolCall = false;
    public boolean capReasoning = false;
    public boolean capStream = true;
    public boolean enabled = true;
    public int sortOrder = 0;

    /** 模型类型展示名。 */
    public static String kindLabel(String k) {
        if (KIND_IMAGE.equals(k)) {
            return "图片";
        }
        if (KIND_EMBEDDING.equals(k)) {
            return "向量";
        }
        return "聊天";
    }

    /** 模态代号转界面文字。 */
    public static String modLabel(String m) {
        if (MOD_IMAGE.equals(m)) {
            return "图片";
        }
        if (MOD_AUDIO.equals(m)) {
            return "音频";
        }
        return "文本";
    }

    /** 能力标签：按固定顺序返回已开启的能力名，供列表页拼 chip。 */
    public java.util.List<String> capabilityLabels() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (capVision) {
            out.add("视觉");
        }
        if (capToolCall) {
            out.add("工具");
        }
        if (capReasoning) {
            out.add("推理");
        }
        if (capStream) {
            out.add("流式");
        }
        return out;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("displayName", displayName);
            o.put("providerId", providerId);
            o.put("kind", kind);
            o.put("inputModalities", inputModalities);
            o.put("outputModalities", outputModalities);
            o.put("capVision", capVision);
            o.put("capToolCall", capToolCall);
            o.put("capReasoning", capReasoning);
            o.put("capStream", capStream);
            o.put("enabled", enabled);
            o.put("sortOrder", sortOrder);
        } catch (Throwable ignored) {
            Logs.w("Dollhouse", "ignored", ignored);
        }
        return o;
    }

    public static AiModel fromJson(JSONObject o) {
        AiModel m = new AiModel();
        if (o == null) {
            return m;
        }
        m.id = o.optString("id", "");
        m.displayName = o.optString("displayName", "");
        m.providerId = o.optString("providerId", "");
        m.kind = o.optString("kind", KIND_CHAT);
        m.inputModalities = o.optString("inputModalities", MOD_TEXT);
        m.outputModalities = o.optString("outputModalities", MOD_TEXT);
        m.capVision = o.optBoolean("capVision", false);
        m.capToolCall = o.optBoolean("capToolCall", false);
        m.capReasoning = o.optBoolean("capReasoning", false);
        m.capStream = o.optBoolean("capStream", true);
        m.enabled = o.optBoolean("enabled", true);
        m.sortOrder = o.optInt("sortOrder", 0);
        return m;
    }

    /** 深拷贝：编辑页改副本，取消即丢弃。 */
    public AiModel copy() {
        return fromJson(toJson());
    }
}
