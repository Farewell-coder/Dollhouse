package com.dollhouse.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】多会话管理：两个会话池（self 自聊 / pet 人偶）的增删改查、当前会话与当前池的切换。
 *
 * 【入口】ChatHistoryStore.loadHistory / saveHistory（取当前会话 id）、ChatDrawer（列表与操作）、
 *        PetTalk（人偶池落点）。
 *
 * 【交互】清单统一存 conv_index，每条带 pool 字段（"self" / "pet"）区分池；
 *        每个会话的历史存 conv_&lt;id&gt;；当前会话 id 分池存：self → conv_current、pet → conv_current_pet；
 *        当前处于哪个池存 conv_pool。历史内容本身仍由 ChatPanel.history 持有，本类只搬 id 与清单。
 *
 * 【池的作用】人偶池的 assistant 回复落库时压成单行（见 ChatHistoryStore 的 byPool），自聊池原样保留；
 *        两池共用同一个记忆库（MemStore），自动压缩按各自会话 id 归档。
 *
 * 【坑】旧版单会话数据放在 chat_history 键里，首次调用 ensure() 时会迁移一次；
 *        迁完不再写回 chat_history，避免两份数据打架。
 *        第一版的人偶会话 id 固定为 "pet" 且没有 pool 字段，ensurePet() 里就地改记为 pet 池，
 *        否则它会被当成自聊池的会话（老的 pet 会话会跑到自聊列表里）。
 */
final class ChatSessions {
    /** 自聊池：用户自己聊的，回复不套正则。 */
    static final String POOL_SELF = "self";
    /** 人偶池：与木偶聊的，回复压成单行。 */
    static final String POOL_PET = "pet";
    /** 【v0.0.1】人偶池的种子会话 id：池里一条都没有时补建，作为 PetTalk 的兜底落点。 */
    static final String PET_ID = "pet";

    private ChatSessions() {
    }

    // ==================== 池标记 ====================

    /** 当前处于哪个池（默认 self）。 */
    static String pool(Context ctx) {
        return POOL_PET.equals(PetPrefs.convPool(ctx)) ? POOL_PET : POOL_SELF;
    }

    /** 切换当前池（各自池里的当前会话 id 不受影响）。 */
    static void setPool(Context ctx, String p) {
        PetPrefs.setConvPool(ctx, POOL_PET.equals(p) ? POOL_PET : POOL_SELF);
    }

    /** 当前是否处于人偶池（供 ChatPanel 决定要不要让人偶头顶说话）。 */
    static boolean isPet(Context ctx) {
        return POOL_PET.equals(pool(ctx));
    }

    /** 清单项属于哪个池（没有 pool 字段的历史数据一律视为 self）。 */
    static String poolOf(JSONObject o) {
        return o != null && POOL_PET.equals(o.optString("pool", POOL_SELF)) ? POOL_PET : POOL_SELF;
    }

    /** 指定会话是否属于人偶池。 */
    static boolean isPetId(Context ctx, String id) {
        return POOL_PET.equals(poolOf(find(ctx, id)));
    }

    // ==================== 清单 ====================

    /** 完整清单（两个池都在）。 */
    static JSONArray listAll(Context ctx) {
        try {
            return new JSONArray(PetPrefs.convIndex(ctx));
        } catch (Throwable unused) {
            return new JSONArray();
        }
    }

    private static void saveAll(Context ctx, JSONArray arr) {
        PetPrefs.setConvIndex(ctx, arr == null ? "[]" : arr.toString());
    }

    /** 当前池的清单（抽屉列表只显示这一个池）。 */
    static JSONArray list(Context ctx) {
        return listOf(listAll(ctx), pool(ctx));
    }

    /** 从完整清单里过滤出某个池的子集。 */
    static JSONArray listOf(JSONArray all, String p) {
        JSONArray out = new JSONArray();
        if (all == null) {
            return out;
        }
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && p.equals(poolOf(o))) {
                out.put(o);
            }
        }
        return out;
    }

    /** 用 arr 替换「当前池」的清单项，另一个池的项原样保留。 */
    static void save(Context ctx, JSONArray arr) {
        JSONArray out = new JSONArray();
        JSONArray all = listAll(ctx);
        String p = pool(ctx);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && !p.equals(poolOf(o))) {
                out.put(o);
            }
        }
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            try {
                o.put("pool", p);
            } catch (Throwable unused) {
            }
            out.put(o);
        }
        saveAll(ctx, out);
    }

    /** 跨池按 id 找清单项。 */
    static JSONObject find(Context ctx, String id) {
        JSONArray arr = listAll(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id"))) {
                return o;
            }
        }
        return null;
    }

    private static JSONObject firstOf(JSONArray arr) {
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    // ==================== 当前会话（分池） ====================

    private static String getCurrent(Context ctx, String p) {
        return POOL_PET.equals(p) ? PetPrefs.convCurrentPet(ctx) : PetPrefs.convCurrent(ctx);
    }

    private static void setCurrent(Context ctx, String p, String id) {
        if (POOL_PET.equals(p)) {
            PetPrefs.setConvCurrentPet(ctx, id);
        } else {
            PetPrefs.setConvCurrent(ctx, id);
        }
    }

    /** 当前池的当前会话 id（保证可用）。 */
    static String currentId(Context ctx) {
        return ensure(ctx);
    }
    /**
     * 【v0.0.1】id 所属池的当前会话 id；无副作用（不触发 ensure / 不写回任何键）。
     * 【为什么需要】两池拆分后 currentId(ctx) 只代表「当前池」：人偶池的会话在
     *   当前池为 self 时会被误判成「已切换」；反过来若拿 currentId 当锚点，
     *   人偶池压缩就会把摘要/暂存写到 self 池名下（静默串池）。
     *   MemSummarizer 的守卫①用它核对锚定会话。
     * 【返回 null】id 不存在（被删）或池内一条都没有时，调用方应放弃。
     */
    static String currentOfPool(Context ctx, String id) {
        JSONObject o = find(ctx, id);
        if (o == null) {
            return null;
        }
        String p = poolOf(o);
        String cur = getCurrent(ctx, p);
        if (cur != null && !cur.isEmpty()) {
            JSONObject co = find(ctx, cur);
            if (co != null && p.equals(poolOf(co))) {
                return cur;
            }
        }
        JSONObject f = firstOf(listOf(listAll(ctx), p));
        return f == null ? null : f.optString("id");
    }

    /** 切到指定会话；若它属于另一个池，连同池标记一起切。 */
    static void switchTo(Context ctx, String id) {
        JSONObject o = find(ctx, id);
        if (o == null) {
            return;
        }
        String p = poolOf(o);
        setPool(ctx, p);
        setCurrent(ctx, p, id);
    }

    // ==================== 新建 ====================

    /** 在指定池里新建会话（不动当前池与当前会话）。 */
    static String createIn(Context ctx, String p, String prefix, String title) {
        String base = prefix + System.currentTimeMillis();
        String id = base;
        for (int n = 1; find(ctx, id) != null && n < 50; n++) {
            id = base + "_" + n;
        }
        try {
            JSONArray all = listAll(ctx);
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("pool", POOL_PET.equals(p) ? POOL_PET : POOL_SELF);
            o.put("updated", System.currentTimeMillis());
            all.put(o);
            saveAll(ctx, all);
            PetPrefs.setConvHistory(ctx, id, "[]");
        } catch (Throwable unused) {
        }
        return id;
    }

    /** 在当前池新建会话并切过去，返回新 id。 */
    static String create(Context ctx) {
        String p = pool(ctx);
        String id = createIn(ctx, p, POOL_PET.equals(p) ? "p" : "c", "新的对话");
        setCurrent(ctx, p, id);
        return id;
    }

    // ==================== 人偶池种子 ====================

    /**
     * 保证人偶池里至少有一条会话，返回人偶池的当前会话 id；不改变当前池。
     * 【迁移】第一版的人偶会话 id 固定为 "pet" 且没有 pool 字段，这里就地把它改记为 pet 池。
     */
    static String ensurePet(Context ctx) {
        JSONArray all = listAll(ctx);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && PET_ID.equals(o.optString("id"))
                    && !POOL_PET.equals(o.optString("pool"))) {
                try {
                    o.put("pool", POOL_PET);
                    saveAll(ctx, all);
                    Logs.i("DollhouseMemo", "[ensurePet] 旧 pet 会话已迁入人偶池");
                } catch (Throwable unused) {
                }
                break;
            }
        }
        JSONObject first = firstOf(listOf(listAll(ctx), POOL_PET));
        if (first == null) {
            String id;
            if (find(ctx, PET_ID) != null) {
                // 该 id 已被别的池占用（理论上不该发生）：换个带池前缀的新 id。
                id = createIn(ctx, POOL_PET, "p", "人偶");
            } else {
                id = PET_ID;
                try {
                    JSONArray arr = listAll(ctx);
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("title", "人偶");
                    o.put("pool", POOL_PET);
                    o.put("updated", System.currentTimeMillis());
                    arr.put(o);
                    saveAll(ctx, arr);
                } catch (Throwable unused) {
                }
            }
            PetPrefs.setConvCurrentPet(ctx, id);
            return id;
        }
        String cur = PetPrefs.convCurrentPet(ctx);
        if (cur != null && !cur.isEmpty() && find(ctx, cur) != null
                && POOL_PET.equals(poolOf(find(ctx, cur)))) {
            return cur;
        }
        String id2 = first.optString("id");
        PetPrefs.setConvCurrentPet(ctx, id2);
        return id2;
    }

    // ==================== 兜底 ====================

    /**
     * 保证当前池有可用会话：为空时新建；旧版单会话数据存在时迁移过来。
     * 返回当前会话 id。
     */
    static String ensure(Context ctx) {
        // 人偶池的种子槽先确保存在（PetTalk 的兜底落点）。
        ensurePet(ctx);
        String p = pool(ctx);
        JSONArray all = listAll(ctx);
        if (firstOf(listOf(all, p)) == null) {
            if (POOL_PET.equals(p)) {
                return ensurePet(ctx);
            }
            // 旧版单会话数据：chat_history 非空则迁成第一个自聊会话。
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
            if (migrated) {
                // 【为什么必须清源】否则 self 池被删空后，下一次 ensure 又会读到这份旧数据
                //   把它当成「新会话」重建出来 —— 升级用户会看到已删掉的旧单会话历史复活。
                PetPrefs.setHistory(ctx, "[]");
            }
            try {
                JSONArray arr = listAll(ctx);
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("title", migrated ? autoTitleFrom(legacy) : "新的对话");
                o.put("pool", POOL_SELF);
                o.put("updated", System.currentTimeMillis());
                arr.put(o);
                saveAll(ctx, arr);
                if (!migrated) {
                    PetPrefs.setConvHistory(ctx, id, "[]");
                }
                PetPrefs.setConvCurrent(ctx, id);
            } catch (Throwable unused) {
            }
            return id;
        }
        String cur = getCurrent(ctx, p);
        JSONObject co = cur == null || cur.isEmpty() ? null : find(ctx, cur);
        if (co == null || !p.equals(poolOf(co))) {
            JSONObject f = firstOf(listOf(listAll(ctx), p));
            cur = f == null ? "" : f.optString("id");
            setCurrent(ctx, p, cur);
        }
        return cur;
    }

    // ==================== 会话操作 ====================

    static void rename(Context ctx, String id, String title) {
        JSONObject o = find(ctx, id);
        if (o == null) {
            return;
        }
        try {
            o.put("title", title == null || title.trim().isEmpty() ? "未命名" : title.trim());
        } catch (Throwable unused) {
            return;
        }
        JSONArray arr = listAll(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it != null && id.equals(it.optString("id"))) {
                try {
                    arr.put(i, o);
                } catch (Throwable unused) {
                }
            }
        }
        saveAll(ctx, arr);
    }

    /** 删除会话；若删的是某个池的当前会话，只在同一个池里兜底，不串池。 */
    static void delete(Context ctx, String id) {
        JSONObject target = find(ctx, id);
        if (target == null) {
            return;
        }
        String p = poolOf(target);
        try {
            JSONArray arr = listAll(ctx);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !id.equals(o.optString("id"))) {
                    out.put(o);
                }
            }
            saveAll(ctx, out);
            PetPrefs.removeConvHistory(ctx, id);
            PetPrefs.removeConvPrev(ctx, id);
            if (id.equals(getCurrent(ctx, p))) {
                JSONObject f = firstOf(listOf(out, p));
                setCurrent(ctx, p, f == null ? "" : f.optString("id"));
            }
        } catch (Throwable unused) {
        }
        // 删的是当前显示的池 → 兜底补一条可用会话（人偶池删空了会补回「人偶」）。
        if (p.equals(pool(ctx))) {
            ensure(ctx);
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
        } catch (Throwable unused) {
            return;
        }
        JSONArray arr = listAll(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it != null && id.equals(it.optString("id"))) {
                try {
                    arr.put(i, o);
                } catch (Throwable unused) {
                }
            }
        }
        saveAll(ctx, arr);
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