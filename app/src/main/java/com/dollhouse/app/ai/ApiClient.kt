package com.dollhouse.app.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 【职责】网络请求的唯一出口：地址拼装、协议头、连接创建、响应读取。
 *
 * 【为什么单独一个类】规格书要求「请求统一走一个客户端，业务层不要散落 if/else」。
 *        三个协议在地址与鉴权上的差异全部收在 ModelRules 里，本类只负责把它们落到连接上；
 *        业务层因此永远只传「供应商 + 密钥 + 模型名」三样东西。
 *
 * 【隐私】密钥只在内存里流转：不写日志、不拼进异常文案。
 *
 * 【坑】超时是硬要求：连接 15s、读 120s。规格书要求「所有外部调用都按可能失败处理」，
 *       这里不给无限等待的口子，卡死比报错更难排查。
 */
object ApiClient {

    /** 建连超时。 */
    const val CONNECT_TIMEOUT_MS = 15000
    /** 读超时：推理型模型首字慢，给足两分钟。 */
    const val READ_TIMEOUT_MS = 120000

    /**
     * 统一 UA。
     * 【为什么换掉默认值】Android 的 HttpURLConnection 默认发的是 Dalvik/... 这类
     *   运行环境 UA，部分第三方中转站 / WAF 会把它当异常客户端拦掉，现象就是
     *   「别的软件能用、这里连不上」；换成普通移动浏览器 UA 即可通过。
     */
    const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 最近一次真正发出去的请求地址（诊断用，只读；不落盘、不进日志）。 */
    @Volatile
    private var lastUrl: String = ""

    /** 读取最近一次实际请求地址；还没发过请求时为空串。 */
    @JvmStatic
    fun lastRequestUrl(): String {
        return lastUrl
    }

    /** 对话请求地址（按供应商协议 + chatPath）。 */
    @JvmStatic
    fun chatUrl(pv: Provider?, model: String?): String {
        return ModelRules.chatUrl(pv, model)
    }

    /** 拉取模型清单的地址。 */
    @JvmStatic
    fun modelsUrl(pv: Provider?): String {
        return ModelRules.modelsUrl(pv)
    }

    /**
     * 把「旧式 baseUrl」包成一个临时供应商，让仍按 baseUrl 传参的老调用点也能走统一网关。
     * 【口径】按 OpenAI 协议处理：Authorization: Bearer + 完整端点原样使用 —— 与改造前行为等价。
     */
    @JvmStatic
    fun fromBaseUrl(baseUrl: String?): Provider {
        val p = Provider()
        p.protocol = Provider.PROTO_OPENAI
        p.baseUrl = if (baseUrl == null) "" else baseUrl
        p.chatPath = ""
        return p
    }

    /**
     * 开一个连好协议头的连接。
     * 【参数】pv 为 null 时退回 Bearer 鉴权；key 为空串时也照常建连 —— 服务端会回 401，
     *        那条错误信息比本地拦截更有诊断价值。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun open(urlText: String?, pv: Provider?, key: String?, method: String?, output: Boolean): HttpURLConnection {
        val u = if (urlText == null) "" else urlText.trim()
        if (u.isEmpty()) {
            throw IllegalStateException("还没填接口地址")
        }
        val k = if (key == null) "" else key.trim()
        // 【诊断】把最终地址记下来：界面才有能力回答「到底打向了哪个地址」。
        lastUrl = u
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.requestMethod = if (method == null) "GET" else method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        if (output) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        if (pv == null) {
            conn.setRequestProperty("Authorization", "Bearer " + k)
        } else {
            ModelRules.applyHeaders(conn, pv, k)
        }
        return conn
    }

    /** 发出 POST 的 JSON 体。 */
    @JvmStatic
    @Throws(Exception::class)
    fun writeJson(conn: HttpURLConnection, body: String?) {
        val os: OutputStream = conn.outputStream
        os.write((if (body == null) "" else body).toByteArray(StandardCharsets.UTF_8))
        os.flush()
        os.close()
    }

    /**
     * 读响应体：非 2xx 一定读 errorStream。
     * 【为什么】服务端的错误说明写在 body 里，只报状态码会把排查线索丢掉。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun readBody(conn: HttpURLConnection, code: Int): String {
        return readAll(if (code < 200 || code >= 300) conn.errorStream else conn.inputStream)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun readAll(inputStream: InputStream?): String {
        if (inputStream == null) {
            return ""
        }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = inputStream.read(buf)
            if (n <= 0) {
                inputStream.close()
                return String(out.toByteArray(), StandardCharsets.UTF_8)
            }
            out.write(buf, 0, n)
        }
    }

    /** 压成单行并截断，用于错误气泡（不要把整段 HTML 错误页甩给用户）。 */
    @JvmStatic
    fun shorten(s: String?, max: Int): String {
        if (s == null) {
            return ""
        }
        val t = s.replace('\n', ' ').trim()
        return if (t.length <= max) t else t.substring(0, max) + "…"
    }
}
