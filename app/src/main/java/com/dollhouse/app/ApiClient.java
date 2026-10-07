package com.dollhouse.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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
final class ApiClient {

    /** 建连超时。 */
    static final int CONNECT_TIMEOUT_MS = 15000;
    /** 读超时：推理型模型首字慢，给足两分钟。 */
    static final int READ_TIMEOUT_MS = 120000;

    /**
     * 统一 UA。
     * 【为什么换掉默认值】Android 的 HttpURLConnection 默认发的是 Dalvik/... 这类
     *   运行环境 UA，部分第三方中转站 / WAF 会把它当异常客户端拦掉，现象就是
     *   「别的软件能用、这里连不上」；换成普通移动浏览器 UA 即可通过。
     */
    static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 最近一次真正发出去的请求地址（诊断用，只读；不落盘、不进日志）。 */
    private static volatile String lastUrl = "";

    /** 读取最近一次实际请求地址；还没发过请求时为空串。 */
    static String lastRequestUrl() {
        return lastUrl;
    }

    private ApiClient() {
    }

    /** 对话请求地址（按供应商协议 + chatPath）。 */
    static String chatUrl(Provider pv, String model) {
        return ModelRules.chatUrl(pv, model);
    }

    /** 拉取模型清单的地址。 */
    static String modelsUrl(Provider pv) {
        return ModelRules.modelsUrl(pv);
    }

    /**
     * 把「旧式 baseUrl」包成一个临时供应商，让仍按 baseUrl 传参的老调用点也能走统一网关。
     * 【口径】按 OpenAI 协议处理：Authorization: Bearer + 完整端点原样使用 —— 与改造前行为等价。
     */
    static Provider fromBaseUrl(String baseUrl) {
        Provider p = new Provider();
        p.protocol = Provider.PROTO_OPENAI;
        p.baseUrl = baseUrl == null ? "" : baseUrl;
        p.chatPath = "";
        return p;
    }

    /**
     * 开一个连好协议头的连接。
     * 【参数】pv 为 null 时退回 Bearer 鉴权；key 为空串时也照常建连 —— 服务端会回 401，
     *        那条错误信息比本地拦截更有诊断价值。
     */
    static HttpURLConnection open(String urlText, Provider pv, String key, String method, boolean output)
            throws Exception {
        String u = urlText == null ? "" : urlText.trim();
        if (u.isEmpty()) {
            throw new IllegalStateException("还没填接口地址");
        }
        String k = key == null ? "" : key.trim();
        // 【诊断】把最终地址记下来：界面才有能力回答「到底打向了哪个地址」。
        lastUrl = u;
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setRequestMethod(method == null ? "GET" : method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        if (output) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        if (pv == null) {
            conn.setRequestProperty("Authorization", "Bearer " + k);
        } else {
            ModelRules.applyHeaders(conn, pv, k);
        }
        return conn;
    }

    /** 发出 POST 的 JSON 体。 */
    static void writeJson(HttpURLConnection conn, String body) throws Exception {
        OutputStream os = conn.getOutputStream();
        os.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();
    }

    /**
     * 读响应体：非 2xx 一定读 errorStream。
     * 【为什么】服务端的错误说明写在 body 里，只报状态码会把排查线索丢掉。
     */
    static String readBody(HttpURLConnection conn, int code) throws Exception {
        return readAll((code < 200 || code >= 300) ? conn.getErrorStream() : conn.getInputStream());
    }

    static String readAll(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (true) {
            int n = inputStream.read(buf);
            if (n <= 0) {
                inputStream.close();
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            out.write(buf, 0, n);
        }
    }

    /** 压成单行并截断，用于错误气泡（不要把整段 HTML 错误页甩给用户）。 */
    static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
