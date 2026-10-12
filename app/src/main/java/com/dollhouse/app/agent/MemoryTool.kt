package com.dollhouse.app.agent

import android.content.Context
import com.dollhouse.app.data.Mem
import com.dollhouse.app.data.Memory
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】记忆工具：让模型自己决定「这件事值得记下来」时写入**当前人偶**的记忆库。
 *
 * 【入口】ChatToolRegistry 注册；模型返回 remember 调用时由 ChatPanel.runTools 分发。
 *
 * 【r13·修记忆混杂 bug】旧实现写死 `DollStore.DEFAULT_ID` —— 无论跟哪只人偶聊，
 *   AI 记的东西全堆进「默认配置」那个桶。后果有二：
 *     ① 只有默认配置读得到这些记忆，其它人偶的库永远是空的；
 *     ② 所有人偶的内容混在同一处，分库形同虚设，彼此还会串人设。
 *   现在改为从 [ChatToolRegistry.doll] 取「本轮属于哪只人偶」——
 *   由 ChatPanel 在发起请求前用 `boundDollId()` 写入。这是本工具的第一原则。
 *
 * 【交互】只写 [Mem]，不碰会话历史；结果以纯文本回填给模型。
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
            val folder = JSONObject()
            folder.put("type", "string")
            folder.put("description", "可选：归到哪个文件夹，例如「生活/饮食」。不知道该归哪就留空。")
            p.put("folder", folder)
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
        var folder = ""
        try {
            val o = JSONObject(if (arguments == null) "{}" else arguments)
            title = o.optString("title", "")
            text = o.optString("text", "")
            folder = o.optString("folder", "")
        } catch (unused: Throwable) {
        }
        if (text.trim().isEmpty()) {
            text = if (arguments == null) "" else arguments.trim()
        }
        if (text.isEmpty()) {
            return "（记忆内容为空，没有写入）"
        }
        // 【r13】写进「当前会话绑定的那只人偶」的库 —— 不再写死默认配置。
        val dollId = ChatToolRegistry.doll()
        val m = Memory()
        m.title = title.trim()
        m.content = text.trim()
        m.source = Memory.SOURCE_AUTO
        m.folderPath = folder.trim()
        m.originDoll = dollId ?: ""
        val ok = Mem.add(appCtx, dollId, m)
        return if (ok) "记住了。" else "（写入记忆失败了）"
    }
}