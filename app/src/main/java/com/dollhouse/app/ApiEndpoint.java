package com.dollhouse.app;

/**
 * 【职责】接口端点（base URL）的唯一收口：补协议、去注释、识别各类完整端点。
 *
 * 【坑】原先这段逻辑散在 PetPrefs.normUrl / ApiSectionTuner.modelsUrl 两处，
 *       且 PetPrefs 那份由 jadx 反编译时条件被写反（`if (str != null) return "";`），
 *       导致 baseUrl() 恒为空串，测试连接直接抛 MalformedURLException: no protocol。
 *       凡是拼 URL 的地方都必须走本类，不要再各自 substring。
 */
final class ApiEndpoint {

    /** 已知的「完整对话端点」后缀：命中即认为用户已填全，不再追加。 */
    private static final String[] TAILS = {
            "/chat/completions", "/completions", "/responses", "/messages", "/generate",
    };

    private ApiEndpoint() {
    }

    /**
     * 规范化：去 #fragment、去 ?query、补 https://、去尾部斜杠。
     * 用户常见写法（api.deepseek.com/v1、https://x.com/v1/、带 # 注释）都能吃下。
     */
    static String ensure(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) {
            return "";
        }
        int cut = u.indexOf('#');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        cut = u.indexOf('?');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        u = u.trim();
        if (u.isEmpty()) {
            return "";
        }
        // 裸域名 / IP 一律按 https 处理；已写 http:// 或 https:// 的原样保留。
        if (!hasScheme(u)) {
            u = "https://" + u;
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** 服务根地址：去掉已知的端点后缀，用于拼 /models 之类。 */
    static String base(String raw) {
        String u = ensure(raw);
        if (u.isEmpty()) {
            return "";
        }
        for (int i = 0; i < TAILS.length; i++) {
            String t = TAILS[i];
            if (u.endsWith(t)) {
                u = u.substring(0, u.length() - t.length());
                while (u.endsWith("/")) {
                    u = u.substring(0, u.length() - 1);
                }
                break;
            }
        }
        return u;
    }

    /**
     * 对话补全端点：已是完整端点就原样返回，否则补 /chat/completions。
     * 这样 https://api.deepseek.com/v1、https://api.deepseek.com/v1/chat/completions
     * 两种写法都能用，不强迫用户记住要不要带 /v1。
     */
    static String chatUrl(String raw) {
        String u = ensure(raw);
        if (u.isEmpty()) {
            return "";
        }
        return isEndpoint(u) ? u : u + "/chat/completions";
    }

    /** 是否已经是完整端点。 */
    static boolean isEndpoint(String u) {
        if (u == null || u.isEmpty()) {
            return false;
        }
        for (int i = 0; i < TAILS.length; i++) {
            if (u.endsWith(TAILS[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把网络/解析异常翻译成用户能看懂的一句话。
     * 原来直接把 MalformedURLException 这类类名甩给用户，他根本不知道该怎么办。
     */
    static String friendlyError(Throwable t, String rawUrl) {
        if (t == null) {
            return "\u672a\u77e5\u9519\u8bef";
        }
        String cls = t.getClass().getSimpleName();
        String u = ensure(rawUrl);
        if ("MalformedURLException".equals(cls) || "IllegalStateException".equals(cls)
                || "IllegalArgumentException".equals(cls)) {
            return "\u63a5\u53e3\u5730\u5740\u4e0d\u5bf9\uff0c\u68c0\u67e5\u6709\u6ca1\u6709\u6f0f\u6389 http:// \u6216 https://";
        }
        if ("UnknownHostException".equals(cls)) {
            return "\u8fde\u4e0d\u4e0a\u670d\u52a1\u5668\uff0c\u68c0\u67e5\u5730\u5740\u662f\u5426\u5199\u5bf9\uff1a" + host(u);
        }
        if ("SocketTimeoutException".equals(cls)) {
            return "\u8bf7\u6c42\u8d85\u65f6\uff0c\u68c0\u67e5\u7f51\u7edc\u6216\u6362\u4e00\u4e2a\u63a5\u53e3\u5730\u5740";
        }
        if ("ConnectException".equals(cls) || "SocketException".equals(cls)) {
            return "\u8fde\u4e0d\u4e0a\u670d\u52a1\u5668\uff0c\u68c0\u67e5\u7f51\u7edc\uff1a" + host(u);
        }
        if ("SSLHandshakeException".equals(cls) || "SSLException".equals(cls)) {
            return "HTTPS \u8bc1\u4e66\u6821\u9a8c\u5931\u8d25\uff0c\u8bd5\u8bd5\u628a\u5730\u5740\u5199\u6210 http:// \u5f00\u5934";
        }
        String m = t.getMessage();
        return cls + (m == null || m.isEmpty() ? "" : "\uff1a" + m);
    }

    /**
     * 把 HTTP 错误响应翻成人话。
     * 服务端（new-api / one-api / OpenAI 兼容系）通常回一段 JSON，
     * 原样甩给用户就是一串引号花括号，他根本读不出哪里填错了。
     * 这里先抠出服务端的 message 原话，再按关键词补一句中文建议。
     */
    static String explainHttpError(int code, String body, String rawUrl) {
        String msg = extractMessage(body);
        String tip = tipFor(code, msg);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(code);
        if (!msg.isEmpty()) {
            sb.append("\uff1a").append(msg);
        }
        if (!tip.isEmpty()) {
            sb.append("\n\u5efa\u8bae\uff1a").append(tip);
        }
        String u = chatUrl(rawUrl);
        if (!u.isEmpty()) {
            sb.append("\n\u8bf7\u6c42\u5730\u5740\uff1a").append(u);
        }
        return sb.toString();
    }

    /** 从各种服务端 JSON 里抠出 message 字段，抠不到就把 body 截断。 */
    private static String extractMessage(String body) {
        if (body == null) {
            return "";
        }
        String b = body.trim();
        if (b.isEmpty()) {
            return "";
        }
        // 依次尝试 "message" / "msg" / "error" 三种常见字段
        String[] keys = {"\"message\"", "\"msg\""};
        for (int i = 0; i < keys.length; i++) {
            String v = valueAfter(b, keys[i]);
            if (!v.isEmpty()) {
                return v;
            }
        }
        // "error" 可能是字符串，也可能是对象里的 message
        String err = valueAfter(b, "\"error\"");
        if (!err.isEmpty() && !"{\u0000".equals(err) && !err.startsWith("{")) {
            return err;
        }
        String inner = valueAfter(b, "\"message\"");
        if (!inner.isEmpty()) {
            return inner;
        }
        return b.length() > 160 ? b.substring(0, 160) + "\u2026" : b;
    }

    /** 取 JSON 里 "key":"value" 的 value；找不到返回空串。 */
    private static String valueAfter(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return "";
        }
        int colon = s.indexOf(':', i + key.length());
        if (colon < 0) {
            return "";
        }
        int q1 = s.indexOf('"', colon + 1);
        if (q1 < 0) {
            return "";
        }
        int q2 = s.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return "";
        }
        return s.substring(q1 + 1, q2).trim();
    }

    /** 按状态码与服务端原话给出可执行的建议。 */
    private static String tipFor(int code, String msg) {
        String m = msg == null ? "" : msg.toLowerCase();
        if (code == 401 || code == 403 || m.contains("invalid api key")
                || m.contains("unauthorized") || m.contains("\u5bc6\u94a5")) {
            return "API \u5bc6\u94a5\u4e0d\u5bf9\u6216\u5df2\u5931\u6548\uff0c\u53bb\u63a7\u5236\u53f0\u91cd\u65b0\u590d\u5236\u4e00\u4e2a";
        }
        if (m.contains("model") && (m.contains("not") || m.contains("empty")
                || m.contains("invalid") || m.contains("unknown"))) {
            return "\u6a21\u578b\u540d\u79f0\u6ca1\u586b\u6216\u4e0d\u5b58\u5728\uff0c\u70b9\u8f93\u5165\u6846\u53f3\u4fa7\u56fe\u6807\u62c9\u53d6\u53ef\u7528\u5217\u8868";
        }
        if (code == 402 || m.contains("insufficient") || m.contains("quota")
                || m.contains("balance") || m.contains("\u4f59\u989d")) {
            return "\u8d26\u6237\u4f59\u989d\u4e0d\u8db3\uff0c\u53bb\u670d\u52a1\u5546\u540e\u53f0\u5145\u503c";
        }
        if (code == 429 || m.contains("rate limit") || m.contains("too many")) {
            return "\u8bf7\u6c42\u592a\u9891\u7e41\uff0c\u7b49\u51e0\u5341\u79d2\u518d\u8bd5";
        }
        if (code == 404) {
            return "\u63a5\u53e3\u8def\u5f84\u4e0d\u5bf9\uff0c\u68c0\u67e5\u7aef\u70b9\u8981\u4e0d\u8981\u5e26 /v1";
        }
        if (code >= 500) {
            return "\u670d\u52a1\u5668\u7aef\u51fa\u9519\uff0c\u7a0d\u540e\u91cd\u8bd5\u6216\u8054\u7cfb\u670d\u52a1\u5546";
        }
        return "";
    }

    /** 从 URL 里抠出主机名，只用于报错提示。 */
    private static String host(String u) {
        if (u == null || u.isEmpty()) {
            return "(\u672a\u586b)";
        }
        int i = u.indexOf("://");
        String rest = i < 0 ? u : u.substring(i + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /** 是否带了合法 scheme（http、https、ws 等，按 RFC 3986 的 scheme 字符集判断）。 */
    private static boolean hasScheme(String u) {
        int i = u.indexOf("://");
        if (i <= 0) {
            return false;
        }
        for (int k = 0; k < i; k++) {
            char ch = u.charAt(k);
            boolean alpha = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
            boolean tail = (ch >= '0' && ch <= '9') || ch == '+' || ch == '-' || ch == '.';
            if (alpha) {
                continue;
            }
            if (k > 0 && tail) {
                continue;
            }
            return false;
        }
        return true;
    }
}