package com.dollhouse.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】多会话管理：会话清单的增删改查与当前会话切换。
 *
 * 【入口】ChatHistoryStore.loadHistory / saveHistory（取当前会话 id）、ChatDrawer（列表与操作）。
 *
 * 【交互】会话清单存 conv_index，每个会话的历史存 conv_<id>，当前会话 id 存 conv_current；
 *        历史内容本身仍由 ChatPanel.history 持有，本类只搬 id 与清单。
 *
 * 【扩展】想加「按最后更新时间排序」只改 list()；想加分组只往清单对象里加字段。
 *
 * 【坑】旧版单会话数据放在 chat_history 键里，首次调用 ensure() 时会迁移一次；
 *        迁完不再写回 chat_history，避免两份数据打架。
 */
final class ChatSessions {

    private ChatSessions() {
    }

    static JSONArray list(Context ctx) {
        try {
            return new JSONArray(PetPrefs.convIndex(ctx));
        } catch (Throwable unused) {
            return new JSONArray();
        }
    }

    private static void save(Context ctx, JSONArray arr) {
        PetPrefs.setConvIndex(ctx, arr.toString());
    }

    static JSONObject find(Context ctx, String id) {
        JSONArray arr = list(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id"))) {
                return o;
            }
        }
        return null;
    }

    /** 新建会话并切过去，返回新 id。 */
    static String create(Context ctx) {
        String id = "c" + System.currentTimeMillis();
        try {
            JSONArray arr = list(ctx);
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", "新的对话");
            o.put("updated", System.currentTimeMillis());
            arr.put(o);
            save(ctx, arr);
            PetPrefs.setConvHistory(ctx, id, "[]");
            PetPrefs.setConvCurrent(ctx, id);
        } catch (Throwable unused) {
        }
        return id;
    }

    /**
     * 保证有可用会话：为空时新建；旧版单会话数据存在时迁移过来。
     * 返回当前会话 id。
     */
    static String ensure(Context ctx) {
        JSONArray arr = list(ctx);
        if (arr.length() == 0) {
            // 旧版单会话数据：chat_history 非空则迁成第一个会话。
            String legacy = "[]";
            try {
                legacy = PetPrefs.history(ctx);
            } catch (Throwable unused) {
            }
            String id = "c" + System.currentTimeMillis();
            boolean migrated = false;
            try {
                JSONArray old = new JSONArray(legacy);
                if (old.length() > 0) {
                    PetPrefs.setConvHistory(ctx, id, legacy);
                    migrated = true;
                }
            } catch (Throwable unused) {
            }
            try {
                JSONArray fresh = new JSONArray();
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("title", migrated ? autoTitleFrom(legacy) : "新的对话");
                o.put("updated", System.currentTimeMillis());
                fresh.put(o);
                save(ctx, fresh);
                if (!migrated) {
                    PetPrefs.setConvHistory(ctx, id, "[]");
                }
                PetPrefs.setConvCurrent(ctx, id);
            } catch (Throwable unused) {
            }
            return id;
        }
        String cur = PetPrefs.convCurrent(ctx);
        if (find(ctx, cur) == null) {
            JSONObject first = arr.optJSONObject(0);
            cur = first == null ? "" : first.optString("id");
            PetPrefs.setConvCurrent(ctx, cur);
        }
        return cur;
    }

    static String currentId(Context ctx) {
        return ensure(ctx);
    }

    static void switchTo(Context ctx, String id) {
        if (find(ctx, id) != null) {
            PetPrefs.setConvCurrent(ctx, id);
        }
    }

    static void rename(Context ctx, String id, String title) {
        JSONObject o = find(ctx, id);
        if (o == null) {
            return;
        }
        try {
            o.put("title", title == null || title.trim().isEmpty() ? "未命名" : title.trim());
            JSONArray arr = list(ctx);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it != null && id.equals(it.optString("id"))) {
                    arr.put(i, o);
                }
            }
            save(ctx, arr);
        } catch (Throwable unused) {
        }
    }

    /** 删除会话；若删的是当前会话则自动切到剩下的第一条。 */
    static void delete(Context ctx, String id) {
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
            PetPrefs.removeConvHistory(ctx, id);
            PetPrefs.removeConvPrev(ctx, id);
            if (id.equals(PetPrefs.convCurrent(ctx))) {
                JSONObject first = out.optJSONObject(0);
                PetPrefs.setConvCurrent(ctx, first == null ? "" : first.optString("id"));
            }
            ensure(ctx);
        } catch (Throwable unused) {
        }
    }

    /** 有新消息时刷新会话的 updated 时间戳。 */
    static void touch(Context ctx, String id) {
        JSONObject o = find(ctx, id);
        if (o == null) {
            return;
        }
        try {
            o.put("updated", System.currentTimeMillis());
            JSONArray arr = list(ctx);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it != null && id.equals(it.optString("id"))) {
                    arr.put(i, o);
                }
            }
            save(ctx, arr);
        } catch (Throwable unused) {
        }
    }

    /** 会话标题为空/默认时，用第一条用户消息自动命名。 */
    static void autoTitleIfNeeded(Context ctx, String id, String firstUserText) {
        JSONObject o = find(ctx, id);
        if (o == null) {
            return;
        }
        String title = o.optString("title", "");
        if (!title.isEmpty() && !"新的对话".equals(title)) {
            return;
        }
        rename(ctx, id, autoTitle(firstUserText));
    }

    /** 取首条用户消息前 12 字作标题。 */
    static String autoTitle(String text) {
        if (text == null) {
            return "新的对话";
        }
        String t = text.replace('\n', ' ').trim();
        if (t.isEmpty()) {
            return "新的对话";
        }
        return t.length() <= 12 ? t : t.substring(0, 12) + "…";
    }

    private static String autoTitleFrom(String historyJson) {
        try {
            JSONArray arr = new JSONArray(historyJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && "user".equals(o.optString("role"))) {
                    return autoTitle(o.optString("content"));
                }
            }
        } catch (Throwable unused) {
        }
        return "旧的对话";
    }
}