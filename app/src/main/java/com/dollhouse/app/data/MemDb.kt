package com.dollhouse.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】总记忆库：AI 自主（或用户要求）写入的长期记忆条目。
 *
 * 【入口】MemoryTool.execute 写入；MemPage 读取/删除。
 *
 * 【交互】与会话摘要（MemStore）是两份独立数据；本类只碰偏好键 memdb_list。
 *
 * 【扩展】新增字段（标签 / 来源会话）时只改 add 与 MemPage 的渲染。
 *
 * 【坑】条目用 JSONArray 存偏好，全量重写；注入 system 提示时有字数上限，
 *       否则记忆一多会把上下文吃光（见 recentText）。
 */
object MemDb {
    /** 注入 system 提示时的最大字符数。 */
    const val MAX_INJECT_CHARS = 1200

    /** 记忆库条数上限：超出丢最旧的。 */
    const val MAX_ENTRIES = 50

    /**
     * 自动精简的触发线：记忆库上限的一半。
     * 【坑】别写死具体数字 —— 上限调了触发线要跟着动，否则上限缩小后会永远触发不到。
     */
    const val MERGE_TRIGGER = MAX_ENTRIES / 2

    @JvmStatic
    fun list(ctx: Context): JSONArray {
        try {
            return JSONArray(PetPrefs.memDbList(ctx))
        } catch (unused: Throwable) {
            return JSONArray()
        }
    }

    private fun save(ctx: Context, arr: JSONArray) {
        PetPrefs.setMemDbList(ctx, arr.toString())
    }

    /** 新增一条；返回是否写入成功。 */
    @JvmStatic
    fun add(ctx: Context, kind: String?, title: String?, text: String?): Boolean {
        if (text == null || text.trim().isEmpty()) {
            return false
        }
        try {
            var arr = list(ctx)
            val o = JSONObject()
            o.put("id", "m" + System.currentTimeMillis() + "-" + (Math.random() * 1000).toInt())
            o.put("ts", System.currentTimeMillis())
            o.put("kind", if (kind == null) "auto" else kind)
            o.put("title", if (title == null) "" else title.trim())
            o.put("text", text.trim())
            arr.put(o)
            // 上限 MAX_ENTRIES 条：超出丢最旧的，避免偏好无限膨胀。
            if (arr.length() > MAX_ENTRIES) {
                val cut = JSONArray()
                for (i in arr.length() - MAX_ENTRIES until arr.length()) {
                    cut.put(arr.opt(i))
                }
                arr = cut
            }
            save(ctx, arr)
            return true
        } catch (unused: Throwable) {
            return false
        }
    }

    @JvmStatic
    fun remove(ctx: Context, id: String?) {
        try {
            val arr = list(ctx)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null && id != null && id != o.optString("id")) {
                    out.put(o)
                }
            }
            save(ctx, out)
        } catch (unused: Throwable) {
        }
    }

    @JvmStatic
    fun clear(ctx: Context) {
        save(ctx, JSONArray())
    }

    /** 倒序拼接最近记忆，总数不超过 maxChars；无记忆时返回空串。 */
    @JvmStatic
    fun recentText(ctx: Context, maxChars: Int): String {
        val arr = list(ctx)
        if (arr.length() == 0) {
            return ""
        }
        val sb = StringBuilder()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", "")
            val body = o.optString("text", "")
            val line = if (title.isEmpty()) body else title + "：" + body
            if (sb.length + line.length + 1 > maxChars) {
                break
            }
            if (sb.length > 0) {
                sb.append('\n')
            }
            sb.append(line)
        }
        return sb.toString()
    }
}
