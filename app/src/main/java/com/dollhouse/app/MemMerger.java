package com.dollhouse.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】记忆归并：条目多了以后，让模型把重复的合成一条、按主题重新划分、只留关键，真删除原条目。
 *
 * 【入口】MemoryTool 写完一条后调 maybeAuto（自动档）；加号面板的「立即整理记忆」调 mergeNow（手动档）。
 *
 * 【交互】读写全走 MemDb（偏好键 memdb_list），不碰会话历史与会话摘要。
 *
 * 【扩展】想保留原文只做「归类置顶」的话，把 apply() 里的 remove 段去掉、改成把合并结果 add 回去即可。
 *
 * 【坑】① 真删除，所以只在模型返回合法结果后才动数据；
 *        ② 必须全程校验「下标还在不在、是不是同一条」，模型可能少给、多给或给重复下标；
 *        ③ 必须有全局重入锁，否则用户一边聊 AI 一边写记忆会并发触发两次归并；
 *        ④ 归并同样会吃 token，所以只在条数过线时做一次，做完条数必然下降，不会再触发。
 */
final class MemMerger {

    /** 全局重入锁：同一时间只允许一次归并。 */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** 条目数超过这个值才值得归并（比触发线多 1，避免刚好 5 条时就白跑一次）。 */
    private static final int MIN_MERGE_COUNT = PetPrefs.MEM_MERGE_TRIGGER + 1;

    /** 单次最多送进模型的条目数。 */
    private static final int MAX_BATCH = 40;

    /** 喂给模型的正文上限：再长也没意义，反而把 prompt 撑爆。 */
    private static final int MAX_FEED_CHARS = 4000;

    /** 合并后最多保留多少条（模型不听话时兜底）。 */
    private static final int MAX_KEPT = 12;

    private static final String MERGE_PROMPT = "下面是一份长期记忆清单。请把它精简合并成更少的条目。"
            + "要求：\n"
            + "1. 同一主题、同一件事、含义重复的条目合并成一条，不要重复记账。\n"
            + "2. 只保留以后还用得上的关键信息：主人的称呼、稳定偏好、约定、重要日期、重要事件与关系变化。\n"
            + "3. 一次性的、过时的、没意义的琐碎内容直接丢掉。\n"
            + "4. 合并后的每条都要短：标题不超过 12 个字，正文一两句话说清，不要写废话。\n"
            + "5. 按主题划分得细一点：不同主题不要硬凑成一条。\n"
            + "只输出一个 JSON 数组，形如\n"
            + "[{\"title\":\"主人喜欢冰美式\",\"text\":\"夏天常点冰美式，不加糖。\"}]\n"
            + "不要 Markdown 代码块，不要解释，不要多余文字。";

    private MemMerger() {
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    /** 自动档：条数过线且开着自动归并时跑一次。 */
    static void maybeAuto(Context ctx) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        if (!PetPrefs.memAutoMerge(app)) {
            return;
        }
        if (MemDb.list(app).length() < MIN_MERGE_COUNT) {
            return;
        }
        merge(app, null);
    }

    /** 手动档：不受阈值与开关限制。done 在主线程回调，用来刷新界面。 */
    static void mergeNow(Context ctx, Runnable done) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        if (MemDb.list(app).length() < 2) {
            post(done);
            return;
        }
        merge(app, done);
    }

    private static void merge(final Context app, final Runnable done) {
        if (!PetPrefs.hasKey(app)) {
            post(done);
            return;
        }
        final JSONArray snapshot = MemDb.list(app);
        int len = snapshot.length();
        if (len < 2) {
            post(done);
            return;
        }
        // 只送最近 MAX_BATCH 条：清单是按时间正序存的，越靠后越新。
        final int from = Math.max(0, len - MAX_BATCH);
        final StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = from; i < len; i++) {
            JSONObject o = snapshot.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String title = o.optString("title", "").trim();
            String body = o.optString("text", "").trim();
            String line = "[" + i + "] " + (title.isEmpty() ? "" : title + "：") + body;
            if (sb.length() + line.length() > MAX_FEED_CHARS) {
                break;
            }
            sb.append(line).append('\n');
            shown++;
        }
        if (shown < 2) {
            post(done);
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            post(done);
            return;
        }

        JSONArray messages = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", MERGE_PROMPT);
            messages.put(sys);
            JSONObject usr = new JSONObject();
            usr.put("role", "user");
            usr.put("content", sb.toString());
            messages.put(usr);
        } catch (Throwable unused) {
        }

        try {
            DeepSeekClient.chat(PetPrefs.apiKey(app), PetPrefs.baseUrl(app), PetPrefs.model(app), messages,
                    new DeepSeekClient.Callback() {
                        @Override
                        public void onResult(final String reply, final String error) {
                            try {
                                if (reply != null && !reply.trim().isEmpty()) {
                                    apply(app, snapshot, reply);
                                }
                            } catch (Throwable unused) {
                            } finally {
                                RUNNING.set(false);
                                post(done);
                            }
                        }
                    });
        } catch (Throwable t) {
            // 【坑】RUNNING 一旦置位，只有回调里的 finally 会清；chat() 同步抛异常时
            // 这个锁会永久卡住，自动归并从此静默失效。
            RUNNING.set(false);
            post(done);
        }
    }

    /**
     * 把模型返回的 JSON 数组落地。
     * 【坑】模型返回的是「合并后的清单」而不是「保留哪些」，所以不能按 id 删——
     *       做法是：先把这一整版合并结果全部 add 进去（新 id、新时间），再把快照里
     *       被送进模型的那批逐条 remove。原条目删除后合并结果自然顶上，条数必然下降。
     * 【坑】必须校验每条的 title/text 非空、长度合理，否则会把一堆空条目写进记忆库。
     */
    private static void apply(Context app, JSONArray snapshot, String reply) {
        JSONArray merged = parse(reply);
        if (merged == null || merged.length() == 0) {
            return;
        }
        int added = 0;
        int limit = Math.min(merged.length(), MAX_KEPT);
        for (int i = 0; i < limit; i++) {
            JSONObject o = merged.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String title = o.optString("title", "").trim();
            String text = o.optString("text", "").trim();
            if (title.isEmpty() && text.isEmpty()) {
                continue;
            }
            if (text.length() > 200) {
                text = text.substring(0, 200);
            }
            if (title.length() > 20) {
                title = title.substring(0, 20);
            }
            if (MemDb.add(app, "merge", title, text)) {
                added++;
            }
        }
        // 一条都没写进去就别删原文了，否则等于把记忆清空。
        if (added == 0) {
            return;
        }
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject o = snapshot.optJSONObject(i);
            if (o == null) {
                continue;
            }
            MemDb.remove(app, o.optString("id", ""));
        }
    }

    /** 从模型回复里抠出 JSON 数组；容忍 ```json 包裹、前后废话、只给对象的情况。 */
    private static JSONArray parse(String reply) {
        String s = reply == null ? "" : reply.trim();
        int a = s.indexOf('[');
        int b = s.lastIndexOf(']');
        if (a >= 0 && b > a) {
            try {
                return new JSONArray(s.substring(a, b + 1));
            } catch (Throwable unused) {
            }
        }
        // 兜底：模型只回了一个对象
        int c = s.indexOf('{');
        int d = s.lastIndexOf('}');
        if (c >= 0 && d > c) {
            try {
                JSONArray arr = new JSONArray();
                arr.put(new JSONObject(s.substring(c, d + 1)));
                return arr;
            } catch (Throwable unused) {
            }
        }
        return null;
    }

    private static void post(final Runnable done) {
        if (done == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                done.run();
            }
        });
    }
}
