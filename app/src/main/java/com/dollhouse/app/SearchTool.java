package com.dollhouse.app;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】联网搜索工具：把模型给的 query 丢给 WebSearch，再把结果压成一段纯文本。
 *
 * 【入口】模型返回 web_search 调用时，由 ChatToolRegistry 分发到这里。
 *
 * 【交互】只读 WebSearch（走网络），不写任何本地状态；失败也返回文案而不是抛异常。
 *
 * 【扩展】想换搜索源只改 execute；想加别的工具请新建实现类，不要塞进来。
 *
 * 【坑】arguments 是模型原样给过来的字符串，可能不是合法 JSON、query 也可能为空；
 *        空 query 时沿用原行为：把整段 arguments 当关键词试一次。
 */
final class SearchTool implements ChatTool {
    private static final int TOP_N = 5;
    @Override
    public String name() {
        return "web_search";
    }
    @Override
    public String describe() {
        return "联网搜索。当主人问到新闻、天气、当前时间、价格、比分、最新发布等你不确定或需要最新信息的问题时，用它查一下再回答。";
    }
    @Override
    public JSONObject properties() {
        JSONObject jSONObject = new JSONObject();
        try {
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("type", "string");
            jSONObject2.put("description", "搜索关键词，尽量精炼，例如「北京今天天气」「某某新闻」");
            jSONObject.put("query", jSONObject2);
        } catch (Throwable unused) {
        }
        return jSONObject;
    }
    @Override
    public JSONArray required() {
        JSONArray jSONArray = new JSONArray();
        jSONArray.put("query");
        return jSONArray;
    }
    @Override
    public String execute(String arguments) {
        String query;
        try {
            query = new JSONObject(arguments == null ? "{}" : arguments).optString("query", "");
        } catch (Throwable unused) {
            query = "";
        }
        if (query.trim().isEmpty()) {
            query = arguments == null ? "" : arguments.trim();
        }
        if (query.isEmpty()) {
            return "没有名为 " + name() + " 的工具。";
        }
        String str;
        try {
            List<WebSearch.Result> search = WebSearch.search(query, TOP_N);
            if (search.isEmpty()) {
                return "（没有搜到相关结果）";
            }
            return "搜索「" + query + "」的结果：\n" + WebSearch.format(search);
        } catch (Throwable th) {
            String simpleName = th.getClass().getSimpleName();
            if (th.getMessage() == null) {
                str = "";
            } else {
                str = ": " + th.getMessage();
            }
            return "（搜索失败：" + simpleName + str + "）";
        }
    }
}
