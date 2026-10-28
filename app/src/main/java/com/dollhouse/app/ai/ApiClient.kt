package com.dollhouse.app.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream
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

    /**
     * 【v0.1.4】明文 HTTP 的应用层闸门。
     *
     * 【为什么需要】系统层 `network_security_config` 是安装期静态配置，没法在运行时
     *   按用户填写的域名放开。要让「用户自己的 HTTP 中转站」能用，系统层只能整体放行；
     *   放行之后**必须有一处代码负责拒绝**，否则等于退回「全站允许明文」的旧缺陷
     *   （API Key 与聊天内容可被同网段中间人读到）。
     *
     * 【放行条件（三者之一）】
     * · 目标是本机回环（127.0.0.1 / localhost / ::1）—— 流量不出本机，无中间人；
     * · `https` 与其它非 http 协议 —— 不受此限制；
     * · 供应商显式打开 [Provider.allowCleartext]。
     *
     * 【为什么抛 IllegalStateException 而不是 IOException】它是「配置问题」不是「网络问题」，
     *   文案要能直接指导用户去勾开关，而不是让人以为对方服务器挂了。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun guardCleartext(url: String, pv: Provider?) {
        val u = url.trim().lowercase()
        if (!u.startsWith("http://")) {
            return
        }
        if (pv != null && pv.allowCleartext) {
            return
        }
        val host = try {
            val h = URL(url.trim()).host ?: ""
            h.lowercase()
        } catch (ignored: Throwable) {
            ""
        }
        if (host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]") {
            return
        }
        throw IllegalStateException(
            "这个供应商走的是明文 HTTP（" + (if (host.isEmpty()) url else host) + "），" +
                "默认被安全策略拦下。\n" +
                "如果这是你自己搭的 / 可信任的服务，请在「编辑供应商」里打开「允许明文 HTTP」；\n" +
                "否则请把地址改成 https://"
        )
    }

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
        // 【v0.1.4】明文 HTTP 的应用层闸门。
        //   系统层的 network_security_config 已放开明文（否则任何 http:// 都发不出去），
        //   安全性改由这里保证：供应商没显式勾选「允许明文 HTTP」就不放行。
        //   这样默认行为与改造前一致（默认禁明文），只有用户明确授权的供应商才走 HTTP。
        guardCleartext(u, pv)
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
        // 【r22·修流泄漏】原写法 write → flush → close 三步直线：write 抛异常
        //   （连接被 reset、磁盘/网络缓冲失败）时 close 被跳过，漏一个流。
        //   请求体写失败虽然多半会连带整次请求失败，但这个流挂在 HttpURLConnection 上，
        //   不关会让连接复用池里的那条连接一直处于脏状态。
        conn.outputStream.use { os ->
            os.write((if (body == null) "" else body).toByteArray(StandardCharsets.UTF_8))
            os.flush()
        }
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
        // 【r22·修流泄漏】原写法在「读到 n <= 0」那一刻才 close —— 读超时 / 连接被
        //   服务端掐断 / 响应体畸形都会抛 IOException，close 永远不会执行。
        //   而流式对话与长回复正是最容易被超时打断的两条路径，漏得最勤。
        inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) {
                    break
                }
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), StandardCharsets.UTF_8)
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
