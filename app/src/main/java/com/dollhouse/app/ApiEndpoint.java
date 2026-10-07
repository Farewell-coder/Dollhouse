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
    private static final String[] CHAT_TAILS = {
            "/chat/completions", "/completions", "/responses", "/messages", "/generate",
    };

    /** 已知的「模型列表端点」后缀：base() 必须剥掉它。 */
    private static final String[] MODEL_TAILS = {"/models"};

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

    /** 服务根地址：去掉已知的端点后缀（对话端点 + 模型列表端点）。 */
    static String base(String raw) {
        return stripTail(stripTail(ensure(raw), CHAT_TAILS), MODEL_TAILS);
    }

    /** 剥掉末尾命中的后缀并去多余斜杠；未命中则原样返回。 */
    private static String stripTail(String u, String[] tails) {
        for (int i = 0; i < tails.length; i++) {
            String t = tails[i];
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
     * 对话补全端点：已是完整端点就原样返回，否则补版本段 + /chat/completions。
     * 这样 https://api.deepseek.com/v1、https://api.deepseek.com/v1/chat/completions
     * 两种写法都能用，不强迫用户记住要不要带 /v1。
     * 【坑·必须】老版本只判断「是不是完整端点」，从不判断「是不是根地址」：
     *   用户填 https://host（不带 /v1）被直接接成 https://host/chat/completions 而 404。
     *   主流中转站与各家官网的对话接口都挂在 /v1 下，缺版本段时必须回填。
     */
    static String chatUrl(String raw) {
        String u = ensure(raw);
        if (u.isEmpty()) {
            return "";
        }
        if (isEndpoint(u)) {
            return u;
        }
        String b = base(u);
        if (b.isEmpty()) {
            return u;
        }
        return b + (hasVersionSegment(b) ? "/chat/completions" : "/v1/chat/completions");
    }

    /** 拉取模型清单的地址：{baseUrl}/models；baseUrl 不带版本段时补 /v1。 */
    static String modelsUrl(String raw) {
        String b = base(raw);
        if (b.isEmpty()) {
            return "";
        }
        return b + (hasVersionSegment(b) ? "/models" : "/v1/models");
    }

    /** 是否已经是完整对话端点。 */
    static boolean isEndpoint(String u) {
        if (u == null || u.isEmpty()) {
            return false;
        }
        for (int i = 0; i < CHAT_TAILS.length; i++) {
            if (u.endsWith(CHAT_TAILS[i])) {
                return true;
            }
        }
        return false;
    }

    /** 地址末段是否已经是版本段（/v1、/v1beta 这类），用于避免拼出 /v1/v1。 */
    static boolean hasVersionSegment(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            return false;
        }
        int i = u.lastIndexOf('/');
        if (i < 0) {
            return false;
        }
        String seg = u.substring(i + 1).toLowerCase();
        return seg.length() > 1 && seg.charAt(0) == 'v' && Character.isDigit(seg.charAt(1));
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
        // 【重构】原来是 5 连 if 的异常名比对；改成「异常名 -> 文案」表，顺序即优先级。
        for (int i = 0; i < ERR_RULES.length; i++) {
            String[] rule = ERR_RULES[i];
            if (rule[0].equals(cls) || rule[1].equals(cls)) {
                return rule[2] + ("1".equals(rule[3]) ? host(u) : "");
            }
        }
        String m = t.getMessage();
        return cls + (m == null || m.isEmpty() ? "" : "\uff1a" + m);
    }

    /**
     * 异常名 -> 用户文案。每项 4 组：主异常名 / 别名异常名（无则空串）/ 文案 / 是否附加主机名。
     * 【顺序】即匹配优先级，与原实现一致。
     */
    private static final String[][] ERR_RULES = {
            {"MalformedURLException", "IllegalStateException",
                    "\u63a5\u53e3\u5730\u5740\u4e0d\u5bf9\uff0c\u68c0\u67e5\u6709\u6ca1\u6709\u6f0f\u6389 http:// \u6216 https://", "0"},
            {"IllegalArgumentException", "",
                    "\u63a5\u53e3\u5730\u5740\u4e0d\u5bf9\uff0c\u68c0\u67e5\u6709\u6ca1\u6709\u6f0f\u6389 http:// \u6216 https://", "0"},
            {"UnknownHostException", "",
                    "\u8fde\u4e0d\u4e0a\u670d\u52a1\u5668\uff0c\u68c0\u67e5\u5730\u5740\u662f\u5426\u5199\u5bf9\uff1a", "1"},
            {"SocketTimeoutException", "",
                    "\u8bf7\u6c42\u8d85\u65f6\uff0c\u68c0\u67e5\u7f51\u7edc\u6216\u6362\u4e00\u4e2a\u63a5\u53e3\u5730\u5740", "0"},
            {"ConnectException", "SocketException",
                    "\u8fde\u4e0d\u4e0a\u670d\u52a1\u5668\uff0c\u68c0\u67e5\u7f51\u7edc\uff1a", "1"},
            {"SSLHandshakeException", "SSLException",
                    "HTTPS \u8bc1\u4e66\u6821\u9a8c\u5931\u8d25\uff0c\u8bd5\u8bd5\u628a\u5730\u5740\u5199\u6210 http:// \u5f00\u5934", "0"},
    };

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
        // 【坑·必须】这里原样显示调用方给的真实请求地址即可：本方法也服务于「测试连接」，
        //   那条链路打的是 /models，若在此再套一层 chatUrl() 就会拼出
        //   「/v1/models/chat/completions」，把用户骗去改一个本来就正确的地址。
        String u = ensure(rawUrl);
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
        // 【重构】原为 6 连 if；判定顺序抽到表里，语义逐条对齐（先状态码命中，再关键词命中）。
        for (int i = 0; i < TIP_RULES.length; i++) {
            String[] r = TIP_RULES[i];
            if (codeMatches(r[0], r[1], code) || keywordHit(r[2], m)) {
                return r[3];
            }
        }
        return "";
    }

    /**
     * 错误提示规则。每项 4 组：状态码下限 / 状态码上限（空 = 不判）/
     * 关键词（逗号分隔，任一命中即算，空 = 不判）/ 文案。
     * 【顺序】即优先级，与原实现一致。
     */
    private static final String[][] TIP_RULES = {
            {"401", "403", "invalid api key,unauthorized,\u5bc6\u94a5",
                    "API \u5bc6\u94a5\u4e0d\u5bf9\u6216\u5df2\u5931\u6548\uff0c\u53bb\u63a7\u5236\u53f0\u91cd\u65b0\u590d\u5236\u4e00\u4e2a"},
            {"", "", "model:not,model:empty,model:invalid,model:unknown",
                    "\u6a21\u578b\u540d\u79f0\u6ca1\u586b\u6216\u4e0d\u5b58\u5728\uff0c\u70b9\u8f93\u5165\u6846\u53f3\u4fa7\u56fe\u6807\u62c9\u53d6\u53ef\u7528\u5217\u8868"},
            {"402", "", "insufficient,quota,balance,\u4f59\u989d",
                    "\u8d26\u6237\u4f59\u989d\u4e0d\u8db3\uff0c\u53bb\u670d\u52a1\u5546\u540e\u53f0\u5145\u503c"},
            {"429", "", "rate limit,too many",
                    "\u8bf7\u6c42\u592a\u9891\u7e41\uff0c\u7b49\u51e0\u5341\u79d2\u518d\u8bd5"},
            {"404", "404", "",
                    "\u63a5\u53e3\u8def\u5f84\u4e0d\u5bf9\uff0c\u68c0\u67e5\u7aef\u70b9\u8981\u4e0d\u8981\u5e26 /v1"},
            {"500", "", "",
                    "\u670d\u52a1\u5668\u7aef\u51fa\u9519\uff0c\u7a0d\u540e\u91cd\u8bd5\u6216\u8054\u7cfb\u670d\u52a1\u5546"},
    };

    /** 状态码是否命中规则里的区间（空 = 不参与判定）。 */
    private static boolean codeMatches(String lo, String hi, int code) {
        if (lo == null || lo.isEmpty()) {
            return false;
        }
        int l = Integer.parseInt(lo);
        int h = (hi == null || hi.isEmpty()) ? (l == 500 ? Integer.MAX_VALUE : l) : Integer.parseInt(hi);
        return code >= l && code <= h;
    }

    /**
     * 关键词是否命中。冒号分隔的两段表示「都要出现」（如 model:not）；
     * 只有一段就是「出现即可」。
     */
    private static boolean keywordHit(String spec, String m) {
        if (spec == null || spec.isEmpty()) {
            return false;
        }
        String[] list = spec.split(",");
        for (int i = 0; i < list.length; i++) {
            String one = list[i];
            int c = one.indexOf(':');
            if (c >= 0) {
                if (m.contains(one.substring(0, c)) && m.contains(one.substring(c + 1))) {
                    return true;
                }
            } else if (m.contains(one)) {
                return true;
            }
        }
        return false;
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