package com.dollhouse.app.ai

import android.os.Handler
import android.os.Looper
import com.dollhouse.app.core.Logs
import java.io.InputStream
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】与 OpenAI 兼容接口通信的客户端：普通对话 + 原始 JSON 两种回调。
 *
 * 【交互】ChatPanel 负责调用；所有请求都跑在工作线程，结果切回主线程回调。
 *
 * 【坑】非 2xx 的响应体也要读出来，因为服务端的错误说明写在 body 里，只报状态码会丢掉排查线索。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
object DeepSeekClient {
    private val MAIN = Handler(Looper.getMainLooper())

    /** 用户主动停止生成时返回的固定错误标记（上层据此静默收尾，不当故障提示）。 */
    const val STOPPED = "__STOPPED__"

    fun interface Callback {
        fun onResult(str: String?, str2: String?)
    }

    fun interface RawCallback {
        fun onMessage(jSONObject: JSONObject?, str: String?)
    }

    /**
     * 【职责】一次请求的取消句柄：把连接抓住，随时可以断掉。
     * 【坑】cancel() 一定是异步生效的：底层的 read 会立刻抛异常，
     *       回调线程拿到的会是错误文案，调用方需自行判断「这是不是用户主动停的」。
     */
    class Task {
        @Volatile
        var conn: HttpURLConnection? = null

        @Volatile
        var cancelled: Boolean = false

        fun cancel() {
            this.cancelled = true
            try {
                val c = this.conn
                if (c != null) {
                    c.disconnect()
                }
            } catch (ignored: Throwable) {
            }
        }

        fun isCancelled(): Boolean {
            return this.cancelled
        }
    }

    // 普通对话请求：只要最终文本，错误也以文本形式回调。
    @JvmStatic
    fun chat(str: String?, str2: String?, str3: String?, jSONArray: JSONArray?, callback: Callback) {
        chatRaw(str, str2, str3, jSONArray, null, RawCallback { jSONObject, str4 ->
            emitChat(jSONObject, str4, callback)
        })
    }

    @JvmStatic
    fun chatRaw(str: String?, str2: String?, str3: String?, jSONArray: JSONArray?, jSONArray2: JSONArray?, rawCallback: RawCallback) {
        chatRaw(str, str2, str3, jSONArray, jSONArray2, null, rawCallback)
    }

    /**
     * 【v2.9.4】带输出预算的对话请求。
     * 【为什么】默认 max_tokens=500 对「中文摘要（≤300 字）」这类任务偏紧：
     *   预算被挤光时接口照样回 HTTP 200，但 choices[0].message.content 是空串，
     *   上层只能报「模型返回了空内容」，用户看到的就是「总结没成功」。
     * 【实现】走既有的 extraBody 通道覆盖 max_tokens —— chatRaw 里该对象在
     *   硬编码的 max_tokens 之后应用，后写覆盖先写。
     * 【不限制】i < 0 时该字段不下发，用服务端默认上限（总结走这条）。
     */
    @JvmStatic
    fun chat(str: String?, str2: String?, str3: String?, jSONArray: JSONArray?, i: Int, callback: Callback) {
        val extra = JSONObject()
        try {
            extra.put("max_tokens", i)
        } catch (ignored: Throwable) {
        }
        chatRaw(str, str2, str3, jSONArray, null, extra, RawCallback { jSONObject, str4 ->
            emitChat(jSONObject, str4, callback)
        })
    }

    @JvmStatic
    fun chatRaw(str: String?, str2: String?, str3: String?, jSONArray: JSONArray?, jSONArray2: JSONArray?, jSONObject: JSONObject?, rawCallback: RawCallback) {
        chatRaw(str, str2, str3, jSONArray, jSONArray2, jSONObject, null, rawCallback)
    }

    /* ------------------------------ 供应商口径（规格书） ------------------------------ */

    /**
     * 【规格书口径】按供应商发一次对话请求。
     * 【为什么保留旧重载】老调用点传的是裸 baseUrl；新调用点传 Provider，协议头与地址全由
     *   ModelRules 决定。两条路最终都汇到同一个工厂方法，业务层不再各自拼 URL 与协议头。
     * 【baseUrl 的作用】仅在 pv 为空时兜底（老配置 / 未迁移数据），pv 非空时完全忽略。
     */
    @JvmStatic
    fun chat(pv: Provider?, key: String?, baseUrl: String?, model: String?, jSONArray: JSONArray?,
             maxTokens: Int, callback: Callback) {
        val extra = JSONObject()
        if (maxTokens > 0) {
            try {
                extra.put("max_tokens", maxTokens)
            } catch (ignored: Throwable) {
            }
        }
        chatRaw(pv, key, baseUrl, model, jSONArray, null, extra, null, RawCallback { jSONObject, str4 ->
            val text = textOf(jSONObject, str4)
            if (text == null && str4 == null) {
                callback.onResult(null, "模型返回了空内容")
                return@RawCallback
            }
            callback.onResult(text, str4)
        })
    }

    /** 不带输出预算的供应商口径版本。 */
    @JvmStatic
    fun chat(pv: Provider?, key: String?, baseUrl: String?, model: String?, jSONArray: JSONArray?,
             callback: Callback) {
        chat(pv, key, baseUrl, model, jSONArray, 0, callback)
    }

    /** 普通对话回调的正文抽取（两个重载共用，逐条等价于原先的匿名 RawCallback 实现）。 */
    private fun emitChat(jSONObject: JSONObject?, str4: String?, callback: Callback) {
        if (str4 != null) {
            callback.onResult(null, str4)
            return
        }
        val optString: String? = if (jSONObject == null) null else jSONObject.optString("content", "")
        if (optString == null || optString.trim().isEmpty()) {
            // 【v2.9.4】兜底：推理型模型会把正文写进 reasoning_content、content 留空。
            //  只在 content 为空时启用，完全不影响正常返回路径。
            val rc: String? = if (jSONObject == null) null else jSONObject.optString("reasoning_content", "")
            if (rc != null && !rc.trim().isEmpty()) {
                Logs.i("DollhouseMemo", "[parse] content 为空，回退 reasoning_content len=" + rc.length)
                callback.onResult(rc.trim(), null)
                return
            }
            Logs.i("DollhouseMemo", "[parse] content 与 reasoning_content 均为空，判定为空内容")
            callback.onResult(null, "模型返回了空内容")
        } else {
            callback.onResult(optString.trim(), null)
        }
    }

    /**
     * 【规格书·规则5】把本次请求体里历史消息的思考内容摘掉。
     * 【字段】兼容常见两种写法：reasoning_content（DeepSeek 系）与 reasoning（OpenAI 系）。
     * 【为什么可以就地改】messages 是调用方为「这一次请求」刚构建出来的数组（下次请求会重新
     *   构建一份），所以就地删字段不会影响它持有的历史；不需要深拷贝，省一次整包解析。
     */
    private fun stripReasoning(messages: JSONArray?) {
        if (messages == null || messages.length() == 0) {
            return
        }
        try {
            for (i in 0 until messages.length()) {
                val o = messages.optJSONObject(i)
                if (o != null) {
                    o.remove("reasoning_content")
                    o.remove("reasoning")
                }
            }
        } catch (t: Throwable) {
            // 清理失败就保持原样：宁可多发一个字段，也不要因为清理动作把整次请求搞崩。
            Logs.w("Dollhouse", "stripReasoning failed", t)
        }
    }

    /**
     * 【正文抽取】把一次回调整理成「正文 / 错误」两段。
     * 【坑】推理型模型会把正文写进 reasoning_content、content 留空，这里做兜底回退；
     *   两者都空才算失败，不能拿空串当成功交上去。
     */
    private fun textOf(msg: JSONObject?, err: String?): String? {
        if (err != null) {
            return null
        }
        val content: String? = if (msg == null) "" else msg.optString("content", "")
        if (content != null && !content.trim().isEmpty()) {
            return content.trim()
        }
        val rc: String? = if (msg == null) "" else msg.optString("reasoning_content", "")
        if (rc != null && !rc.trim().isEmpty()) {
            return rc.trim()
        }
        return null
    }

    /**
     * 带取消句柄的版本：task 非空时，调用方可以随时 task.cancel() 把连接断掉。
     * 【坑】取消判定看的是 task.cancelled，不是异常类型——主动断开抛出来的异常
     *       与网络故障长得一模一样，只能靠这个标志区分。
     */
    @JvmStatic
    fun chatRaw(str: String?, str2: String?, str3: String?, jSONArray: JSONArray?, jSONArray2: JSONArray?, jSONObject: JSONObject?, task: Task?, rawCallback: RawCallback) {
        chatRaw(null, str, str2, str3, jSONArray, jSONArray2, jSONObject, task, rawCallback)
    }

    /** 真正干活的那一个：pv 为空时按裸 baseUrl 走（旧口径），否则按供应商拼地址与协议头。 */
    @JvmStatic
    fun chatRaw(pv: Provider?, str: String?, str2: String?, str3: String?, jSONArray: JSONArray?, jSONArray2: JSONArray?, jSONObject: JSONObject?, task: Task?, rawCallback: RawCallback) {
        Thread({
            var msgObject: JSONObject? = null
            var errText: String? = null
            var conn: HttpURLConnection? = null
            try {
                if (task != null && task.cancelled) {
                    errText = STOPPED
                    throw InterruptedException(STOPPED)
                }
                if (str == null || str.isEmpty()) {
                    throw IllegalStateException("还没填 API key")
                }
                val body = buildBody(str3, jSONArray, jSONArray2, jSONObject)
                // 【规格书·规则5】回传历史思考过程：关掉时把历史消息里的 reasoning 字段剥掉，
                //   避免把上一轮的思考内容再次送回；开着时保持原样。
                if (pv != null && !pv.resendHistoryReasoning) {
                    stripReasoning(jSONArray)
                }
                // 地址与协议头全部收口到 ApiClient：老口径传裸 baseUrl（str2），
                // 新口径传 Provider（协议决定路径与鉴权方式）。
                val urlText = if (pv == null) ApiEndpoint.chatUrl(str2) else ApiClient.chatUrl(pv, str3)
                if (urlText.isEmpty()) {
                    throw IllegalStateException("还没填接口地址")
                }
                val c = ApiClient.open(urlText, pv, str, "POST", true)
                conn = c
                if (task != null) {
                    task.conn = c
                }
                ApiClient.writeJson(c, body.toString())
                val code = c.responseCode
                val text: String? = ApiClient.readBody(c, code)
                Logs.i("DollhouseMemo", "[http] code=" + code + " bodyLen=" + (if (text == null) -1 else text.length))
                if (code >= 200 && code < 300) {
                    msgObject = parseOkBody(text)
                    if (msgObject == null) {
                        errText = "模型返回结构不对"
                    }
                } else {
                    errText = buildHttpError(code, text, str2, jSONArray2)
                }
            } catch (t: Throwable) {
                msgObject = null
                // 用户主动停止：不要报成网络错误，回一个固定标记让上层静默处理。
                errText = if (task != null && task.cancelled) STOPPED else ApiEndpoint.friendlyError(t, str2)
            } finally {
                conn?.disconnect()
                if (task != null) {
                    task.conn = null
                }
            }
            val fObj = msgObject
            val fErr = errText
            MAIN.post {
                rawCallback.onMessage(fObj, fErr)
            }
        }, "feiyu-deepseek").start()
    }

    /** 读流收口到 ApiClient（保留本方法只为兼容既有调用点）。 */
    @JvmStatic
    @Throws(Exception::class)
    fun readAll(inputStream: InputStream): String {
        return ApiClient.readAll(inputStream)
    }

    /** 组装 chat/completions 请求体：默认参数 + tools + 调用方 extraBody 覆盖合并。 */
    @Throws(Exception::class)
    private fun buildBody(model: String?, messages: JSONArray?, tools: JSONArray?,
                          extraBody: JSONObject?): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("messages", messages)
        // 【v2.9.4】调用方在 extraBody 里显式带了 max_tokens 就不要再塞默认值：
        //  传 -1 表示「不限制」，该字段根本不下发，由服务端按模型默认上限处理。
        if (!(extraBody != null && extraBody.has("max_tokens"))) {
            body.put("max_tokens", 500)
        }
        body.put("temperature", 1.2)
        body.put("stream", false)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }
        if (extraBody != null) {
            val keys = extraBody.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = extraBody.get(k)
                // 【v2.9.4】-1 = 不限制输出长度：从请求体里摘掉该字段，
                //  交给服务端默认上限，避免小预算把中文摘要截成空 content。
                if ("max_tokens" == k && v is Number && v.toInt() < 0) {
                    body.remove("max_tokens")
                    continue
                }
                body.put(k, v)
            }
        }
        return body
    }

    /**
     * 解析 2xx 响应体，取第一条 choice 的 message。
     * 【v2.10.0】usage 是响应「顶层」字段，不在 message 里。以前只把 message 交回上层，
     *  TokenStat.recordFrom 从 message 里找 usage 永远是 null，统计因此恒为 0（页面能开、
     *  数据全空）。这里把它挂到 message 上一并带回，键名用 __ 前缀避免与 message 自身字段
     *  冲突；runTools 重建 assistant 消息时只取 content / tool_calls，不会回灌下一次请求体。
     */
    @Throws(Exception::class)
    private fun parseOkBody(text: String?): JSONObject? {
        val root = JSONObject(text)
        val choices = root.optJSONArray("choices")
        val msg = if (choices == null || choices.length() <= 0)
            null else choices.getJSONObject(0).optJSONObject("message")
        if (msg == null) {
            return null
        }
        try {
            val usage = root.optJSONObject("usage")
            if (usage != null) {
                msg.putOpt("__usage", usage)
            }
        } catch (ignored: Throwable) {
        }
        // 【v2.9.4】确诊用：finish_reason=length 即输出被预算截断；msgKeys 只看字段名
        //  （deepseek 系推理模型会把正文放进 reasoning_content），两者都不含正文与端点信息。
        try {
            val fr = if (choices != null && choices.length() > 0)
                choices.getJSONObject(0).optString("finish_reason", "") else ""
            val keys = StringBuilder()
            val ks = msg.keys()
            while (ks.hasNext()) {
                keys.append(ks.next()).append(',')
            }
            Logs.i("DollhouseMemo", "[http] finishReason=" + fr
                    + " msgKeys=" + keys + " contentLen=" + msg.optString("content", "").length)
        } catch (ignored: Throwable) {
        }
        return msg
    }

    /**
     * 非 2xx 的失败原因。
     * 401/402/429 与其余状态码都走统一解释；仅在带 tools 时对 400 单独提示「不支持工具调用」。
     */
    private fun buildHttpError(code: Int, text: String?, baseUrl: String?, tools: JSONArray?): String {
        if (code == 401 || code == 402 || code == 429) {
            return ApiEndpoint.explainHttpError(code, text, baseUrl)
        }
        if (tools != null) {
            return "TOOLS_UNSUPPORTED HTTP 400: " + shorten(text, 200)
        }
        return ApiEndpoint.explainHttpError(code, text, baseUrl)
    }

    /** 单行截断收口到 ApiClient。 */
    @JvmStatic
    fun shorten(str: String?, i: Int): String {
        return ApiClient.shorten(str, i)
    }
}
