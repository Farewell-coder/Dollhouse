package com.dollhouse.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】完整备份信封（App 私有格式，扩展名 `.dollhouse-backup`）。
 *
 * ## 与另外两种配置交换的分工
 * | 格式 | 用途 | 是否含对话 / 记忆 / 图片 |
 * |---|---|---|
 * | `dollhouse.providers` | 只搬供应商与模型，供 Aivora 互通 | 否 |
 * | AEX `.akcp` | 跨软件交换单/多条配置 | 否 |
 * | **`dollhouse.backup`（本类）** | **本机完整迁移 / 换机还原** | 是 |
 *
 * 【为什么必须另开格式】用户换手机时真正想带走的是：供应商、人偶设定、聊天记录、
 *   记忆库、外观。前三者都各存各的键；只导出供应商等于丢掉绝大部分内容，
 *   而用户并不知道「导出的其实只是配置」。
 *
 * ## 结构
 * ```
 * {
 *   "kind": "dollhouse.backup",
 *   "ver": 1,
 *   "exportedAt": 1699...,
 *   "appVersion": "0.2.6",
 *   "sections": {
 *     "providers": [...],  "models": [...],
 *     "dolls": [...],      "memories": { "<dollId>": [...] },
 *     "settings": { ... }
 *   }
 * }
 * ```
 * ## 明确不装的东西
 * - **API Key**：`providers[].apiKey` 导出时已是 KeyVault 密文，换机后无法解密
 *   （设备密钥不可迁移），因此恢复时会**保留目标机上的原值**、绝不写入这份密文。
 * - **图片文件**：封面 / 形象 / 聊天背景是私有目录里的文件，不塞进 JSON
 *   （会让文件体积失控）。缺图时人偶用内置默认形象，界面会明确提示。
 *
 * 【本类只做信封的组装 / 校验 / 拆解】不读不写存储，便于单测覆盖版本与容错。
 */
object BackupEnvelope {

    const val KIND = "dollhouse.backup"
    const val EXTENSION = ".dollhouse-backup"

    /** 当前格式版本；结构断层才升，加字段不升。 */
    const val VER = 1

    /** 单文件上限：完整备份可能含大量聊天文本，给到 64 MiB。 */
    const val MAX_BYTES = 64 * 1024 * 1024

    /** 这个文件是不是完整备份。 */
    @JvmStatic
    fun looksLike(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) {
            return false
        }
        val text = String(bytes, Charsets.UTF_8).trimStart()
        return text.startsWith("{") && text.contains("\"" + KIND + "\"")
    }

    /** 组装信封。各 section 为空时也保留键（便于用户看出「这类数据是空的」而不是缺失）。 */
    @JvmStatic
    fun build(
        exportedAt: Long,
        appVersion: String,
        providers: JSONArray?,
        models: JSONArray?,
        dolls: JSONArray?,
        memories: JSONObject?,
        settings: JSONObject?
    ): JSONObject {
        val root = JSONObject()
        root.put("kind", KIND)
        root.put("ver", VER)
        root.put("exportedAt", exportedAt)
        root.put("appVersion", appVersion)
        val sections = JSONObject()
        sections.put("providers", providers ?: JSONArray())
        sections.put("models", models ?: JSONArray())
        sections.put("dolls", dolls ?: JSONArray())
        sections.put("memories", memories ?: JSONObject())
        sections.put("settings", settings ?: JSONObject())
        root.put("sections", sections)
        return root
    }

    /** 解析结果；`reason` 为空表示可导入。 */
    class Parsed(
        val root: JSONObject?,
        val reason: Reason? = null,
        val warnings: List<String> = emptyList()
    ) {
        fun ok(): Boolean = root != null && reason == null

        fun message(): String {
            return when (reason) {
                null -> ""
                Reason.NOT_BACKUP -> "这不是 Dollhouse 的完整备份文件。"
                Reason.TOO_LARGE -> "文件过大（超过 64 MiB），可能已损坏。"
                Reason.BAD_JSON -> "文件内容损坏，无法读取。"
                Reason.UNSUPPORTED_VERSION -> "备份来自更新版本，请先升级应用再恢复。"
                Reason.MISSING_SECTIONS -> "备份内容不完整，缺少数据区。"
            }
        }
    }

    enum class Reason { NOT_BACKUP, TOO_LARGE, BAD_JSON, UNSUPPORTED_VERSION, MISSING_SECTIONS }

    /**
     * 解析并做严格校验。
     *
     * 【为什么版本要拒绝更高版本】本格式包含多类数据的结构；高版本可能改了字段语义，
     *   按旧规则强行导入会把「不认识的字段」静默丢掉 —— 用户以为恢复成功，
     *   实际丢了一半数据。宁可明确拒绝。
     */
    @JvmStatic
    fun parse(bytes: ByteArray?): Parsed {
        if (bytes == null || bytes.isEmpty()) {
            return Parsed(null, Reason.NOT_BACKUP)
        }
        if (bytes.size > MAX_BYTES) {
            return Parsed(null, Reason.TOO_LARGE)
        }
        if (!looksLike(bytes)) {
            return Parsed(null, Reason.NOT_BACKUP)
        }
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            return Parsed(null, Reason.BAD_JSON)
        }
        if (KIND != root.optString("kind", "")) {
            return Parsed(null, Reason.NOT_BACKUP)
        }
        val ver = root.optInt("ver", -1)
        if (ver < 1) {
            return Parsed(null, Reason.BAD_JSON)
        }
        if (ver > VER) {
            return Parsed(null, Reason.UNSUPPORTED_VERSION)
        }
        if (root.optJSONObject("sections") == null) {
            return Parsed(null, Reason.MISSING_SECTIONS)
        }
        val warnings = ArrayList<String>()
        if (root.optString("appVersion", "").isEmpty()) {
            warnings.add("备份没有记录来源版本，不影响恢复。")
        }
        return Parsed(root, null, warnings)
    }

    /** 读某个数据区；缺失时返回空数组 / 空对象，调用方不必判空。 */
    @JvmStatic
    fun sectionArray(root: JSONObject?, name: String): JSONArray {
        val s = root?.optJSONObject("sections") ?: return JSONArray()
        return s.optJSONArray(name) ?: JSONArray()
    }

    @JvmStatic
    fun sectionObject(root: JSONObject?, name: String): JSONObject {
        val s = root?.optJSONObject("sections") ?: return JSONObject()
        return s.optJSONObject(name) ?: JSONObject()
    }

    /**
     * 恢复前的概览：让用户在覆盖前看清「会还原什么、什么不会」。
     *
     * @return 逐行说明（可直接显示）
     */
    @JvmStatic
    fun summary(root: JSONObject?): List<String> {
        val out = ArrayList<String>()
        val providers = sectionArray(root, "providers").length()
        val models = sectionArray(root, "models").length()
        val dolls = sectionArray(root, "dolls").length()
        val mem = sectionObject(root, "memories")
        var memCount = 0
        val keys = mem.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            memCount += mem.optJSONArray(k)?.length() ?: 0
        }
        out.add("供应商 " + providers + " 个，模型 " + models + " 个")
        out.add("人偶 " + dolls + " 只，记忆 " + memCount + " 条")
        out.add("API Key 需要重新填写（备份不含可用密钥）")
        out.add("封面与人偶图片不包含在备份里")
        return out
    }
}