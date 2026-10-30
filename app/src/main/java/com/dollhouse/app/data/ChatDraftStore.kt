package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.core.Logs
import org.json.JSONObject

/**
 * 【职责】按 `personaId + conversationId` 隔离的输入草稿（r27 新增）。
 *
 * 【为什么需要】草稿改造前只活在 [com.dollhouse.app.ui.chat.ChatState.input] 里：
 *   切会话 / 退到后台被回收，用户敲了一半的话就没了；图片工具选好的图也一起丢。
 *   更糟的是如果只做一份全局草稿，切到另一个人设时会把她的话带过去 ——
 *   所以键里必须同时带上人设 id 与会话 id。
 *
 * 【存什么】文本、图片工具状态（是否启用 + 选中的图片路径）、最后更新时间。
 *   不存「分析中 / 加载中」这类瞬时状态：它们跨进程没有意义，恢复出来只会卡住界面。
 *
 * 【存法】与全工程一致：org.json 手写编解码 + SharedPreferences 字符串值。
 *   读盘容错优先级：损坏的 JSON 当空草稿（绝不把坏 JSON 灌进输入框）>
 *   兼容旧版的「裸文本」与「imageTool 为布尔 / 字符串」的旧结构。
 *
 * 【边界】本类不碰 Compose 状态、不触发保存节流；由调用方在切会话 / onPause 时调用。
 */
class ChatImageToolState {

    /** 图片工具是否处于打开状态。 */
    var enabled: Boolean = false

    /** 选中的图片绝对路径；空串 = 没有选图。 */
    var imagePath: String = ""

    fun isEmpty(): Boolean {
        return !enabled && imagePath.isBlank()
    }

    fun copy(): ChatImageToolState {
        val v = ChatImageToolState()
        v.enabled = enabled
        v.imagePath = imagePath
        return v
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("enabled", enabled)
            o.put("imagePath", ChatDraft.clipPath(imagePath))
        } catch (ignored: Throwable) {
        }
        return o
    }

    companion object {

        /** 「键不存在」与「键存在但值为 null」必须区分。 */
        private val MISSING = Any()

        @JvmStatic
        fun fromJson(raw: Any?): ChatImageToolState {
            val v = ChatImageToolState()
            when (raw) {
                is JSONObject -> {
                    // 先看新键，再看旧键；键存在即表态，null 不再往旧键回落。
                    v.enabled = asBool(first(raw, "enabled", "on")) ?: false
                    v.imagePath = asString(
                        first(raw, "imagePath", "image_path", "image", "path")
                    ) ?: ""
                }
                // 旧版可能只存了一个开关。
                is Boolean -> v.enabled = raw
                // 旧版也可能直接把路径当状态存。
                is String -> {
                    val p = raw.trim()
                    if (p.isNotEmpty()) {
                        v.enabled = true
                        v.imagePath = p
                    }
                }
                is Number -> v.enabled = raw.toInt() != 0
            }
            v.imagePath = ChatDraft.clipPath(v.imagePath)
            return v
        }

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
                is String -> v.trim()
                is Number -> v.toString()
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
    }
}

/** 一份输入草稿。 */
class ChatDraft {

    /** 输入框原文；保留换行与首尾空格，只在判空时用 isBlank。 */
    var text: String = ""

    /** 图片工具状态。 */
    var imageTool: ChatImageToolState = ChatImageToolState()

    /** 最后更新时间（毫秒）。0 = 从未保存过。 */
    var updatedAt: Long = 0L

    /** 没有可恢复的内容。 */
    fun isEmpty(): Boolean {
        return text.isBlank() && imageTool.isEmpty()
    }

    fun copy(): ChatDraft {
        val v = ChatDraft()
        v.text = text
        v.imageTool = imageTool.copy()
        v.updatedAt = updatedAt
        return v
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("schema", SCHEMA)
            o.put("ver", SCHEMA_VER)
            o.put("text", clipText(text))
            o.put("imageTool", imageTool.toJson())
            o.put("updatedAt", if (updatedAt > 0L) updatedAt else 0L)
        } catch (ignored: Throwable) {
        }
        return o
    }

    companion object {

        /** 自有 JSON 标识。 */
        const val SCHEMA = "chat_draft"

        /** 结构版本。 */
        const val SCHEMA_VER = 1

        /**
         * 文本上限。
         * 【为什么是 20000】SharedPreferences 是整文件读写，草稿每次保存都会把整份偏好
         *   序列化一次；粘贴一整本书进输入框会把主线程卡出可见掉帧。
         *   20000 字已远超正常聊天输入（几十条长消息的量级），超出部分截头保留。
         */
        const val MAX_TEXT_CHARS = 20_000

        /** 图片路径上限：正常路径几百字符，1024 足够且挡住脏数据。 */
        const val MAX_PATH_CHARS = 1_024

        private val MISSING = Any()

        @JvmStatic
        fun empty(): ChatDraft {
            return ChatDraft()
        }

        /** 编码为可落盘的 JSON 文本；异常时回落成空草稿。 */
        @JvmStatic
        fun encode(draft: ChatDraft?): String {
            return try {
                (draft ?: ChatDraft()).toJson().toString()
            } catch (ignored: Throwable) {
                ChatDraft().toJson().toString()
            }
        }

        /**
         * 安全解析。
         * 【兼容两种旧形态】
         *   · 裸文本（历史实现可能直接存输入框内容）→ text = 原文；
         *   · imageTool 是布尔 / 字符串 → 见 [ChatImageToolState.fromJson]。
         * 【损坏 JSON】看起来像对象但解析失败 → 空草稿。绝不把半截 JSON 灌回输入框。
         */
        @JvmStatic
        fun decode(raw: String?): ChatDraft {
            if (raw == null || raw.isBlank()) {
                return empty()
            }
            val looksLikeJson = raw.trimStart().startsWith("{")
            if (!looksLikeJson) {
                // 旧版裸文本：原样保留（不 trim，用户可能刻意留了缩进）。
                val v = empty()
                v.text = clipText(raw)
                return v
            }
            val o = try {
                JSONObject(raw)
            } catch (ignored: Throwable) {
                return empty()
            }
            return fromJson(o)
        }

        @JvmStatic
        fun fromJson(o: JSONObject?): ChatDraft {
            val v = empty()
            if (o == null) {
                return v
            }
            v.text = clipText(asString(first(o, "text", "input", "draft")) ?: "")
            v.updatedAt = (asLong(first(o, "updatedAt", "updated_at", "ts")) ?: 0L)
                .coerceAtLeast(0L)

            val tool = first(o, "imageTool", "image_tool")
            if (tool !== MISSING) {
                v.imageTool = ChatImageToolState.fromJson(tool)
            } else {
                // 旧结构：工具状态拆在顶层字段里。
                val path = asString(
                    first(o, "imagePath", "image_path", "image", "attachImage")
                )
                val on = asBool(
                    first(o, "imageToolEnabled", "image_tool_enabled", "imageToolOn")
                )
                if (path != null && path.isNotEmpty()) {
                    v.imageTool.imagePath = clipPath(path)
                    v.imageTool.enabled = on ?: true
                } else if (on != null) {
                    v.imageTool.enabled = on
                }
            }
            return v
        }

        /* ------------------------------ 解析 / 裁剪 ------------------------------ */

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
                is String -> v
                is Number -> v.toString()
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

        private fun asLong(v: Any?): Long? {
            if (v === MISSING || v == null) {
                return null
            }
            return when (v) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull()
                else -> null
            }
        }

        @JvmStatic
        fun clipText(s: String?): String {
            return clip(s ?: "", MAX_TEXT_CHARS)
        }

        @JvmStatic
        fun clipPath(s: String?): String {
            return clip(s ?: "", MAX_PATH_CHARS)
        }

        /** 按 char 截断，但不把一个代理对切成两半（否则尾部会显示成乱码方块）。 */
        private fun clip(s: String, max: Int): String {
            if (s.length <= max) {
                return s
            }
            var end = max.coerceAtLeast(0)
            if (end > 0 && end < s.length && Character.isHighSurrogate(s[end - 1])) {
                end--
            }
            return s.substring(0, end)
        }
    }
}

/**
 * 【职责】[ChatDraft] 的按作用域持久化。
 *
 * 【作用域】`personaId + conversationId` 共同参与键名；任一段不同就是另一份草稿。
 *
 * 【存储】复用 `feiyu_pet` 偏好文件（与 PetPrefs / UpdatePrefs 同一惯例），
 *   键前缀 `chat_draft_v1_`。与历史、配置各用各的键空间，互不覆盖。
 *
 * 【空草稿即删除】用户把输入框清空后，盘上那份也删掉：
 *   否则「切走再回来」会把已经删掉的字又恢复出来，看起来像闹鬼。
 *
 * 【线程】与既有偏好一致，`apply()` 异步落盘；只在主线程调用。
 */
object ChatDraftStore {

    private const val TAG = "DollhouseChatDraft"
    private const val NAME = "feiyu_pet"
    private const val PREFIX = "chat_draft_v1_"

    @JvmStatic
    fun scopeKey(personaId: String?, conversationId: String?): String {
        return PREFIX + enc(personaId) + "|" + enc(conversationId)
    }

    /** 读该作用域的草稿；没有 / 损坏返回空草稿（[ChatDraft.updatedAt] == 0）。 */
    @JvmStatic
    fun load(ctx: Context, personaId: String?, conversationId: String?): ChatDraft {
        val raw = try {
            prefs(ctx).getString(scopeKey(personaId, conversationId), null)
        } catch (t: Throwable) {
            Logs.w(TAG, "load failed", t)
            null
        }
        return ChatDraft.decode(raw)
    }

    /**
     * 保存草稿并盖上当前时间戳。
     * 【副作用】会更新 [ChatDraft.updatedAt]：调用方持有的实例与盘上保持一致。
     * 【空草稿】直接删键，不写一份空 JSON。
     */
    @JvmStatic
    fun save(ctx: Context, personaId: String?, conversationId: String?, draft: ChatDraft?) {
        try {
            val d = draft ?: ChatDraft()
            val key = scopeKey(personaId, conversationId)
            if (d.isEmpty()) {
                prefs(ctx).edit().remove(key).apply()
                return
            }
            d.updatedAt = System.currentTimeMillis()
            prefs(ctx).edit().putString(key, ChatDraft.encode(d)).apply()
        } catch (t: Throwable) {
            Logs.w(TAG, "save failed", t)
        }
    }

    @JvmStatic
    fun clear(ctx: Context, personaId: String?, conversationId: String?) {
        try {
            prefs(ctx).edit().remove(scopeKey(personaId, conversationId)).apply()
        } catch (t: Throwable) {
            Logs.w(TAG, "clear failed", t)
        }
    }

    @JvmStatic
    fun has(ctx: Context, personaId: String?, conversationId: String?): Boolean {
        return try {
            prefs(ctx).contains(scopeKey(personaId, conversationId))
        } catch (t: Throwable) {
            false
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, 0)

    private fun enc(s: String?): String {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) {
            return ""
        }
        return try {
            java.net.URLEncoder.encode(t, "UTF-8")
        } catch (t2: Throwable) {
            t
        }
    }
}
