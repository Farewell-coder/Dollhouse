package com.dollhouse.app;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】与 OpenAI 兼容接口通信的客户端：普通对话 + 原始 JSON 两种回调。
 *
 * 【交互】ChatPanel 负责调用；所有请求都跑在工作线程，结果切回主线程回调。
 *
 * 【坑】非 2xx 的响应体也要读出来，因为服务端的错误说明写在 body 里，只报状态码会丢掉排查线索。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public final class DeepSeekClient {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 用户主动停止生成时返回的固定错误标记（上层据此静默收尾，不当故障提示）。 */
    public static final String STOPPED = "__STOPPED__";

    public interface Callback {
        void onResult(String str, String str2);
    }

    public interface RawCallback {
        void onMessage(JSONObject jSONObject, String str);
    }

    /**
     * 【职责】一次请求的取消句柄：把连接抓住，随时可以断掉。
     * 【坑】cancel() 一定是异步生效的：底层的 read 会立刻抛异常，
     *       回调线程拿到的会是错误文案，调用方需自行判断「这是不是用户主动停的」。
     */
    public static final class Task {
        volatile HttpURLConnection conn;
        volatile boolean cancelled;

        public void cancel() {
            this.cancelled = true;
            try {
                HttpURLConnection c = this.conn;
                if (c != null) {
                    c.disconnect();
                }
            } catch (Throwable ignored) {
            }
        }

        public boolean isCancelled() {
            return this.cancelled;
        }
    }

    private DeepSeekClient() {
    }

    // 普通对话请求：只要最终文本，错误也以文本形式回调。
    public static void chat(String str, String str2, String str3, JSONArray jSONArray, final DeepSeekClient.Callback callback) {
        chatRaw(str, str2, str3, jSONArray, null, new DeepSeekClient.RawCallback() {            @Override
            public void onMessage(JSONObject jSONObject, String str4) {
                if (str4 != null) {
                    callback.onResult(null, str4);
                    return;
                }
                String optString = jSONObject == null ? null : jSONObject.optString("content", "");
                if (optString == null || optString.trim().isEmpty()) {
                    // 【v2.9.4】兜底：推理型模型会把正文写进 reasoning_content、content 留空。
                    //  只在 content 为空时启用，完全不影响正常返回路径。
                    String rc = jSONObject == null ? null : jSONObject.optString("reasoning_content", "");
                    if (rc != null && !rc.trim().isEmpty()) {
                        Logs.i("DollhouseMemo", "[parse] content 为空，回退 reasoning_content len=" + rc.length());
                        callback.onResult(rc.trim(), null);
                        return;
                    }
                    Logs.i("DollhouseMemo", "[parse] content 与 reasoning_content 均为空，判定为空内容");
                    callback.onResult(null, "模型返回了空内容");
                } else {
                    callback.onResult(optString.trim(), null);
                }
            }
        });
    }

    public static void chatRaw(String str, String str2, String str3, JSONArray jSONArray, JSONArray jSONArray2, DeepSeekClient.RawCallback rawCallback) {
        chatRaw(str, str2, str3, jSONArray, jSONArray2, null, rawCallback);
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
    public static void chat(String str, String str2, String str3, JSONArray jSONArray, int i, final DeepSeekClient.Callback callback) {
        JSONObject extra = new JSONObject();
        try {
            extra.put("max_tokens", i);
        } catch (Throwable ignored) {
        }
        chatRaw(str, str2, str3, jSONArray, null, extra, new DeepSeekClient.RawCallback() {            @Override
            public void onMessage(JSONObject jSONObject, String str4) {
                if (str4 != null) {
                    callback.onResult(null, str4);
                    return;
                }
                String optString = jSONObject == null ? null : jSONObject.optString("content", "");
                if (optString == null || optString.trim().isEmpty()) {
                    // 【v2.9.4】兜底：推理型模型会把正文写进 reasoning_content、content 留空。
                    //  只在 content 为空时启用，完全不影响正常返回路径。
                    String rc = jSONObject == null ? null : jSONObject.optString("reasoning_content", "");
                    if (rc != null && !rc.trim().isEmpty()) {
                        Logs.i("DollhouseMemo", "[parse] content 为空，回退 reasoning_content len=" + rc.length());
                        callback.onResult(rc.trim(), null);
                        return;
                    }
                    Logs.i("DollhouseMemo", "[parse] content 与 reasoning_content 均为空，判定为空内容");
                    callback.onResult(null, "模型返回了空内容");
                } else {
                    callback.onResult(optString.trim(), null);
                }
            }
        });
    }

    public static void chatRaw(final String str, final String str2, final String str3, final JSONArray jSONArray, final JSONArray jSONArray2, final JSONObject jSONObject, final DeepSeekClient.RawCallback rawCallback) {
        chatRaw(str, str2, str3, jSONArray, jSONArray2, jSONObject, null, rawCallback);
    }

    /**
     * 带取消句柄的版本：task 非空时，调用方可以随时 task.cancel() 把连接断掉。
     * 【坑】取消判定看的是 task.cancelled，不是异常类型——主动断开抛出来的异常
     *       与网络故障长得一模一样，只能靠这个标志区分。
     */
    public static void chatRaw(final String str, final String str2, final String str3, final JSONArray jSONArray, final JSONArray jSONArray2, final JSONObject jSONObject, final DeepSeekClient.Task task, final DeepSeekClient.RawCallback rawCallback) {
        new Thread(new Runnable() {            @Override
            public void run() {
                JSONObject msgObject = null;
                String errText = null;
                HttpURLConnection conn = null;
                try {
                    if (task != null && task.cancelled) {
                        errText = STOPPED;
                        throw new InterruptedException(STOPPED);
                    }
                    if (str == null || str.isEmpty()) {
                        throw new IllegalStateException("\u8fd8\u6ca1\u586b API key");
                    }
                    JSONObject body = new JSONObject();
                    body.put("model", str3);
                    body.put("messages", jSONArray);
                    // 【v2.9.4】调用方在 extraBody 里显式带了 max_tokens 就不要再塞默认值：
                    //  传 -1 表示「不限制」，该字段根本不下发，由服务端按模型默认上限处理。
                    if (!(jSONObject != null && jSONObject.has("max_tokens"))) {
                        body.put("max_tokens", 500);
                    }
                    body.put("temperature", 1.2d);
                    body.put("stream", false);
                    if (jSONArray2 != null && jSONArray2.length() > 0) {
                        body.put("tools", jSONArray2);
                        body.put("tool_choice", "auto");
                    }
                    if (jSONObject != null) {
                        Iterator<String> keys = jSONObject.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            Object v = jSONObject.get(k);
                            // 【v2.9.4】-1 = 不限制输出长度：从请求体里摘掉该字段，
                            //  交给服务端默认上限，避免小预算把中文摘要截成空 content。
                            if ("max_tokens".equals(k) && (v instanceof Number)
                                    && ((Number) v).intValue() < 0) {
                                body.remove("max_tokens");
                                continue;
                            }
                            body.put(k, v);
                        }
                    }
                    // 兜底再规范一次：调用方若直接传了用户原始输入，这里也不至于抛 no protocol。
                    String urlText = ApiEndpoint.chatUrl(str2);
                    if (urlText.isEmpty()) {
                        throw new IllegalStateException("\u8fd8\u6ca1\u586b\u63a5\u53e3\u5730\u5740");
                    }
                    conn = (HttpURLConnection) new java.net.URL(urlText).openConnection();
                    if (task != null) {
                        task.conn = conn;
                    }
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(120000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("Authorization", "Bearer " + str);
                    conn.setRequestProperty("Accept", "application/json");
                    OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();
                    int code = conn.getResponseCode();
                    String text = DeepSeekClient.readAll((code < 200 || code >= 300) ? conn.getErrorStream() : conn.getInputStream());
                    Logs.i("DollhouseMemo", "[http] code=" + code + " bodyLen=" + (text == null ? -1 : text.length()));
                    if (code >= 200 && code < 300) {
                        JSONObject root = new JSONObject(text);
                        JSONArray choices = root.optJSONArray("choices");
                        JSONObject msg = (choices == null || choices.length() <= 0) ? null : choices.getJSONObject(0).optJSONObject("message");
                        // 【v2.10.0】usage 是响应「顶层」字段，不在 message 里。以前只把 message
                        //  交回上层，TokenStat.recordFrom 从 message 里找 usage 永远是 null，
                        //  统计因此恒为 0（页面能开、数据全空）。这里把它挂到 message 上一并带回，
                        //  键名用 __ 前缀避免与 message 自身字段冲突；runTools 重建 assistant 消息时
                        //  只取 content / tool_calls，不会把 __usage 回灌进下一次请求体。
                        if (msg != null) {
                            try {
                                JSONObject usage = root.optJSONObject("usage");
                                if (usage != null) {
                                    msg.putOpt("__usage", usage);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        msgObject = msg;
                        if (msg == null) {
                            errText = "\u6a21\u578b\u8fd4\u56de\u7ed3\u6784\u4e0d\u5bf9";
                        } else {
                            // 【v2.9.4】确诊用：finish_reason=length 即输出被预算截断；
                            //   msgKeys 只看字段名（deepseek 系推理模型会把正文放进 reasoning_content），
                            //   两者都不含任何正文与端点信息。
                            try {
                                String fr = (choices != null && choices.length() > 0)
                                        ? choices.getJSONObject(0).optString("finish_reason", "") : "";
                                StringBuilder keys = new StringBuilder();
                                Iterator<String> ks = msg.keys();
                                while (ks.hasNext()) {
                                    keys.append(ks.next()).append(',');
                                }
                                Logs.i("DollhouseMemo", "[http] finishReason=" + fr
                                        + " msgKeys=" + keys + " contentLen=" + msg.optString("content", "").length());
                            } catch (Throwable ignored) {
                            }
                        }
                    } else if (code == 401) {
                        errText = ApiEndpoint.explainHttpError(code, text, str2);
                    } else if (code == 402) {
                        errText = ApiEndpoint.explainHttpError(code, text, str2);
                    } else if (code == 429) {
                        errText = ApiEndpoint.explainHttpError(code, text, str2);
                    } else if (jSONArray2 != null) {
                        errText = "TOOLS_UNSUPPORTED HTTP 400: " + DeepSeekClient.shorten(text, 200);
                    } else {
                        errText = ApiEndpoint.explainHttpError(code, text, str2);
                    }
                } catch (Throwable t) {
                    msgObject = null;
                    // 用户主动停止：不要报成网络错误，回一个固定标记让上层静默处理。
                    errText = (task != null && task.cancelled) ? STOPPED : ApiEndpoint.friendlyError(t, str2);
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                    if (task != null) {
                        task.conn = null;
                    }
                }
                final JSONObject fObj = msgObject;
                final String fErr = errText;
                DeepSeekClient.MAIN.post(new Runnable() {                    @Override
                    public void run() {
                        rawCallback.onMessage(fObj, fErr);
                    }
                });
            }
        }, "feiyu-deepseek").start();
    }
    public static String readAll(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] bArr = new byte[4096];
        while (true) {
            int read = inputStream.read(bArr);
            if (read <= 0) {
                inputStream.close();
                return new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
            }
            byteArrayOutputStream.write(bArr, 0, read);
        }
    }

    public static String shorten(String str, int i) {
        if (str == null) {
            return "";
        }
        String trim = str.replace('\n', ' ').trim();
        if (trim.length() <= i) {
            return trim;
        }
        return trim.substring(0, i) + "…";
    }
}
