package com.dollhouse.app.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】无 UI 的工具执行器：把模型返回的一批 tool_calls 跑完，产出可供回填的 role=tool 消息数组。
 *
 * 【入口】PetTalk 的迷你框（没有 ChatPanel 的 UI 回调，借用本类走同一条工具循环）。
 *        ChatPanel 不用它 —— 那边有「我去查一下…」这类界面提示，自成一套。
 *
 * 【交互】只调 ChatToolRegistry.execute；不碰 View、不写会话历史。
 *
 * 【扩展】想在迷你框里加工具提示，改 PetTalk；想改工具本身，改具体 ChatTool 实现。
 *
 * 【坑】必须在后台线程调用（内部会执行 shell / 网络这类阻塞操作）。
 *        单个工具失败不影响其余工具：每个调用独立 try，失败也回填一条说明，
 *        否则模型会因为「少了一条 tool 回复」而报错（tool_calls 与 tool 消息必须一一对应）。
 */
object ChatToolRunner {

    /**
     * 同步执行一批工具调用。
     *
     * @param toolCalls 模型返回的 tool_calls 数组（可为 null）
     * @return 与 toolCalls 一一对应的 role=tool 消息数组（永不为 null）
     */
    @JvmStatic
    fun runAll(toolCalls: JSONArray?): JSONArray {
        val results = JSONArray()
        if (toolCalls == null) {
            return results
        }
        for (i in 0 until toolCalls.length()) {
            val call = toolCalls.optJSONObject(i)
            // 【1:1 契约·必须】tool_calls 有几条就必须回几条 tool 消息。整条跳过会让服务端
            //   因「有 tool_calls 无对应 tool 回复」判非法。所以 call 为 null 时也合成一条兜底。
            val id = if (call == null) "" else call.optString("id", "")
            val fn = if (call == null) null else call.optJSONObject("function")
            val name = if (fn == null) "" else fn.optString("name", "")
            val args = if (fn == null) "{}" else fn.optString("arguments", "{}")
            val output: String = try {
                if (name.isEmpty()) "（工具调用缺少函数名）" else ChatToolRegistry.execute(name, args)
            } catch (t: Throwable) {
                "（工具执行异常：" + t.javaClass.simpleName + "）"
            }
            try {
                val msg = JSONObject()
                msg.put("role", "tool")
                // 【契约】tool_call_id 必须与模型给的 id 对应，缺了或错了会被服务端拒绝。
                // 【不带 name】与全屏页 ChatPanel 的可用路径保持一致：严格 OpenAI 兼容网关
                //   不接受 tool 消息上的 name 字段（会 400），而按 name 匹配的网关也能用 id 匹配。
                msg.put("tool_call_id", id)
                msg.put("content", output)
                results.put(msg)
            } catch (t: Throwable) {
                // 【1:1 兜底】构造失败也必须补一条，否则与上面同样的配对被服务端拒绝。
                try {
                    val msg = JSONObject()
                    msg.put("role", "tool")
                    msg.put("tool_call_id", id)
                    msg.put("content", "（工具结果构造失败：" + t.javaClass.simpleName + "）")
                    results.put(msg)
                } catch (ignored: Throwable) {
                }
            }
        }
        return results
    }

    /**
     * 构造以 assistant 身份携带 tool_calls 的中间消息（工具循环必须把它先放回请求体，
     * 否则 tool 消息会因为「没有对应的 tool_calls」被服务端判为非法请求）。
     */
    @JvmStatic
    fun assistantWithCalls(reply: JSONObject?, toolCalls: JSONArray?): JSONObject {
        val msg = JSONObject()
        try {
            msg.put("role", "assistant")
            msg.put("content", if (reply == null) "" else reply.optString("content", ""))
            msg.put("tool_calls", toolCalls)
        } catch (unused: Throwable) {
        }
        return msg
    }
}
