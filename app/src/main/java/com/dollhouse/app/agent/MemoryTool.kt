package com.dollhouse.app.agent

import android.content.Context
import com.dollhouse.app.ai.MemMerger
import com.dollhouse.app.data.MemDb
import org.json.JSONArray
import org.json.JSONObject

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
class MemoryTool(ctx: Context) : ChatTool {
    private val appCtx: Context = ctx.applicationContext

    override fun name(): String {
        return "remember"
    }

    override fun describe(): String {
        return "把值得长期记住的事写进记忆库。当主人明确说「记住…」「以后都…」，" +
                "或者出现了稳定的偏好、称呼、约定、重要日期时用它。闲聊、一次性信息不要用。"
    }

    override fun properties(): JSONObject {
        val p = JSONObject()
        try {
            val title = JSONObject()
            title.put("type", "string")
            title.put("description", "这条记忆的短标题，例如「主人喜欢冰美式」")
            p.put("title", title)
            val text = JSONObject()
            text.put("type", "string")
            text.put("description", "要记住的内容，一两句话说清")
            p.put("text", text)
        } catch (unused: Throwable) {
        }
        return p
    }

    override fun required(): JSONArray {
        val a = JSONArray()
        a.put("title")
        a.put("text")
        return a
    }

    override fun execute(arguments: String?): String {
        var title = ""
        var text = ""
        try {
            val o = JSONObject(if (arguments == null) "{}" else arguments)
            title = o.optString("title", "")
            text = o.optString("text", "")
        } catch (unused: Throwable) {
        }
        if (text.trim().isEmpty()) {
            text = if (arguments == null) "" else arguments.trim()
        }
        if (text.isEmpty()) {
            return "（记忆内容为空，没有写入）"
        }
        val ok = MemDb.add(appCtx, "auto", title, text)
        if (ok) {
            // 【交互】写完立刻看条数：够多了就让 AI 自己把旧记忆归并精简一次，
            // 异步跑，不阻塞这次回复（它只发一次请求，失败也只是不整理）。
            MemMerger.maybeAuto(appCtx)
        }
        return if (ok) "记住了。" else "（写入记忆失败了）"
    }
}
