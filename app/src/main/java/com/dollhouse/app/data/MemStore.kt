package com.dollhouse.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】会话摘要库：把早期对话压成的要点，供「压缩上下文」与聊天抽屉展示。
 *
 * 【入口】MemSummarizer 写入；ChatDrawer 读取/删除；压缩时被 ChatPanel 消费。
 *
 * 【交互】与总记忆库（MemDb）是两份独立数据，键也不同（mem_list / memdb_list）。
 *
 * 【扩展】要按会话分组展示只改 listOf(convId)。
 *
 * 【坑】每条摘要记录了 covered（被压掉的消息条数）与 convId（属于哪个会话）；
 *        删除会话时摘要不必跟着删（用户可能还想回看），只按 id 单条删。
 */
object MemStore {

    @JvmStatic
    fun list(ctx: Context): JSONArray {
        try {
            return JSONArray(PetPrefs.memList(ctx))
        } catch (unused: Throwable) {
            return JSONArray()
        }
    }

    /** 某会话下的摘要，按时间先后排列。 */
    @JvmStatic
    fun listOf(ctx: Context, convId: String?): JSONArray {
        val all = list(ctx)
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i)
            if (o != null && convId != null && convId == o.optString("convId")) {
                out.put(o)
            }
        }
        return out
    }

    private fun save(ctx: Context, arr: JSONArray) {
        PetPrefs.setMemList(ctx, arr.toString())
    }

    @JvmStatic
    fun add(ctx: Context, convId: String?, text: String?, covered: Int): Boolean {
        if (text == null || text.trim().isEmpty()) {
            return false
        }
        try {
            var arr = list(ctx)
            val o = JSONObject()
            o.put("id", "s" + System.currentTimeMillis() + "-" + (Math.random() * 1000).toInt())
            o.put("ts", System.currentTimeMillis())
            o.put("convId", if (convId == null) "" else convId)
            o.put("text", text.trim())
            o.put("covered", covered)
            arr.put(o)
            if (arr.length() > 100) {
                val cut = JSONArray()
                for (i in arr.length() - 100 until arr.length()) {
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
}
