package com.dollhouse.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】记忆工具：让模型自己决定「这件事值得记下来」时写入总记忆库。
 *
 * 【入口】ChatToolRegistry 注册；模型返回 remember 调用时由 ChatPanel.runTools 分发。
 *
 * 【交互】只写 MemDb（偏好键 memdb_list），不碰会话历史；结果以纯文本回填给模型。
 *
 * 【扩展】想加「忘记某条」再建一个 forget 工具，不要在 execute 里加分支。
 *
 * 【坑】ChatTool.execute 没有 Context 参数，这里持有的是应用级 Context（构造时传入），
 *        不会泄漏 Activity；arguments 可能不是合法 JSON，必须全程容错。
 */
final class MemoryTool implements ChatTool {
    private final Context appCtx;

    MemoryTool(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    @Override
    public String name() {
        return "remember";
    }

    @Override
    public String describe() {
        return "把值得长期记住的事写进记忆库。当主人明确说「记住…」「以后都…」，"
                + "或者出现了稳定的偏好、称呼、约定、重要日期时用它。闲聊、一次性信息不要用。";
    }

    @Override
    public JSONObject properties() {
        JSONObject p = new JSONObject();
        try {
            JSONObject title = new JSONObject();
            title.put("type", "string");
            title.put("description", "这条记忆的短标题，例如「主人喜欢冰美式」");
            p.put("title", title);
            JSONObject text = new JSONObject();
            text.put("type", "string");
            text.put("description", "要记住的内容，一两句话说清");
            p.put("text", text);
        } catch (Throwable unused) {
        }
        return p;
    }

    @Override
    public JSONArray required() {
        JSONArray a = new JSONArray();
        a.put("title");
        a.put("text");
        return a;
    }

    @Override
    public String execute(String arguments) {
        String title = "";
        String text = "";
        try {
            JSONObject o = new JSONObject(arguments == null ? "{}" : arguments);
            title = o.optString("title", "");
            text = o.optString("text", "");
        } catch (Throwable unused) {
        }
        if (text.trim().isEmpty()) {
            text = arguments == null ? "" : arguments.trim();
        }
        if (text.isEmpty()) {
            return "（记忆内容为空，没有写入）";
        }
        boolean ok = MemDb.add(this.appCtx, "auto", title, text);
        if (ok) {
            // 【交互】写完立刻看条数：够多了就让 AI 自己把旧记忆归并精简一次，
            // 异步跑，不阻塞这次回复（它只发一次请求，失败也只是不整理）。
            MemMerger.maybeAuto(this.appCtx);
        }
        return ok ? "记住了。" : "（写入记忆失败了）";
    }
}