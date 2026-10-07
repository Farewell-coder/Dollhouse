package com.dollhouse.app.agent

import org.json.JSONArray
import org.json.JSONObject

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
class SearchTool : ChatTool {

    override fun name(): String {
        return "web_search"
    }

    override fun describe(): String {
        return "联网搜索。当主人问到新闻、天气、当前时间、价格、比分、最新发布等你不确定或需要最新信息的问题时，用它查一下再回答。"
    }

    override fun properties(): JSONObject {
        val jSONObject = JSONObject()
        try {
            val jSONObject2 = JSONObject()
            jSONObject2.put("type", "string")
            jSONObject2.put("description", "搜索关键词，尽量精炼，例如「北京今天天气」「某某新闻」")
            jSONObject.put("query", jSONObject2)
        } catch (unused: Throwable) {
        }
        return jSONObject
    }

    override fun required(): JSONArray {
        val jSONArray = JSONArray()
        jSONArray.put("query")
        return jSONArray
    }

    override fun execute(arguments: String?): String {
        var query: String
        try {
            query = JSONObject(if (arguments == null) "{}" else arguments).optString("query", "")
        } catch (unused: Throwable) {
            query = ""
        }
        if (query.trim().isEmpty()) {
            query = if (arguments == null) "" else arguments.trim()
        }
        if (query.isEmpty()) {
            return "没有名为 " + name() + " 的工具。"
        }
        try {
            val search = WebSearch.search(query, TOP_N)
            if (search.isEmpty()) {
                return "（没有搜到相关结果）"
            }
            return "搜索「" + query + "」的结果：\n" + WebSearch.format(search)
        } catch (th: Throwable) {
            val simpleName = th.javaClass.simpleName
            val str = if (th.message == null) "" else ": " + th.message
            return "（搜索失败：" + simpleName + str + "）"
        }
    }

    companion object {
        private const val TOP_N = 5
    }
}
