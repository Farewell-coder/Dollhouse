package com.dollhouse.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

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
final class MemStore {

    private MemStore() {
    }

    static JSONArray list(Context ctx) {
        try {
            return new JSONArray(PetPrefs.memList(ctx));
        } catch (Throwable unused) {
            return new JSONArray();
        }
    }

    /** 某会话下的摘要，按时间先后排列。 */
    static JSONArray listOf(Context ctx, String convId) {
        JSONArray all = list(ctx);
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && convId != null && convId.equals(o.optString("convId"))) {
                out.put(o);
            }
        }
        return out;
    }

    private static void save(Context ctx, JSONArray arr) {
        PetPrefs.setMemList(ctx, arr.toString());
    }

    static boolean add(Context ctx, String convId, String text, int covered) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            JSONArray arr = list(ctx);
            JSONObject o = new JSONObject();
            o.put("id", "s" + System.currentTimeMillis() + "-" + (int) (Math.random() * 1000));
            o.put("ts", System.currentTimeMillis());
            o.put("convId", convId == null ? "" : convId);
            o.put("text", text.trim());
            o.put("covered", covered);
            arr.put(o);
            if (arr.length() > 100) {
                JSONArray cut = new JSONArray();
                for (int i = arr.length() - 100; i < arr.length(); i++) {
                    cut.put(arr.opt(i));
                }
                arr = cut;
            }
            save(ctx, arr);
            return true;
        } catch (Throwable unused) {
            return false;
        }
    }

    static void remove(Context ctx, String id) {
        try {
            JSONArray arr = list(ctx);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !id.equals(o.optString("id"))) {
                    out.put(o);
                }
            }
            save(ctx, out);
        } catch (Throwable unused) {
        }
    }
}