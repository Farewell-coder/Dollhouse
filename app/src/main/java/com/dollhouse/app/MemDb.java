package com.dollhouse.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

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
final class MemDb {
    /** 注入 system 提示时的最大字符数。 */
    static final int MAX_INJECT_CHARS = 1200;

    /** 记忆库条数上限：超出丢最旧的。 */
    static final int MAX_ENTRIES = 50;

    /**
     * 自动精简的触发线：记忆库上限的一半。
     * 【坑】别写死具体数字 —— 上限调了触发线要跟着动，否则上限缩小后会永远触发不到。
     */
    static final int MERGE_TRIGGER = MAX_ENTRIES / 2;

    private MemDb() {
    }

    static JSONArray list(Context ctx) {
        try {
            return new JSONArray(PetPrefs.memDbList(ctx));
        } catch (Throwable unused) {
            return new JSONArray();
        }
    }

    private static void save(Context ctx, JSONArray arr) {
        PetPrefs.setMemDbList(ctx, arr.toString());
    }

    /** 新增一条；返回是否写入成功。 */
    static boolean add(Context ctx, String kind, String title, String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            JSONArray arr = list(ctx);
            JSONObject o = new JSONObject();
            o.put("id", "m" + System.currentTimeMillis() + "-" + (int) (Math.random() * 1000));
            o.put("ts", System.currentTimeMillis());
            o.put("kind", kind == null ? "auto" : kind);
            o.put("title", title == null ? "" : title.trim());
            o.put("text", text.trim());
            arr.put(o);
            // 上限 MAX_ENTRIES 条：超出丢最旧的，避免偏好无限膨胀。
            if (arr.length() > MAX_ENTRIES) {
                JSONArray cut = new JSONArray();
                for (int i = arr.length() - MAX_ENTRIES; i < arr.length(); i++) {
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

    static void clear(Context ctx) {
        save(ctx, new JSONArray());
    }

    /** 倒序拼接最近记忆，总数不超过 maxChars；无记忆时返回空串。 */
    static String recentText(Context ctx, int maxChars) {
        JSONArray arr = list(ctx);
        if (arr.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String title = o.optString("title", "");
            String body = o.optString("text", "");
            String line = title.isEmpty() ? body : title + "：" + body;
            if (sb.length() + line.length() + 1 > maxChars) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
