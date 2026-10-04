package com.dollhouse.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【职责】轻量联网搜索：抓取搜索结果页并用正则提取标题/链接/摘要。
 *
 * 【交互】只被 ChatPanel 的联网工具调用，无第三方依赖。
 *
 * 【坑】解析靠正则匹配 HTML，页面结构一变就会失效；H2/P_CLAMP/P_ANY 是几套兜底的抽取规则，命中率下降时应先看搜索结果页的 DOM 是否改版，而不是改正则硬凑。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public final class WebSearch {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern H2 = Pattern.compile("<h2[^>]*>(.*?)</h2>", 32);
    private static final Pattern A_HREF = Pattern.compile("<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", 32);
    private static final Pattern P_CLAMP = Pattern.compile("<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>", 32);
    private static final Pattern P_ANY = Pattern.compile("<p[^>]*>(.*?)</p>", 32);

    public static final class Result {
        public final String snippet;
        public final String title;
        public final String url;

        Result(String str, String str2, String str3) {
            this.title = str;
            this.url = str2;
            this.snippet = str3;
        }
    }

    private WebSearch() {
    }

    // 抓搜索结果页并解析成结构化条目；页面改版会直接导致解析为空。
    public static List<WebSearch.Result> search(String str, int i) throws Exception {
        String encode = URLEncoder.encode(str, "UTF-8");
        List<WebSearch.Result> parse = parse(fetch("https://cn.bing.com/search?q=" + encode + "&ensearch=0&setlang=zh-CN"), i);
        if (!parse.isEmpty()) {
            return parse;
        }
        return parse(fetch("https://www.bing.com/search?q=" + encode + "&setlang=zh-CN"), i);
    }

    static String fetch(String str) throws Exception {
        HttpURLConnection httpURLConnection = null;
        try {
            HttpURLConnection httpURLConnection2 = (HttpURLConnection) new java.net.URL(str).openConnection();
            try {
                httpURLConnection2.setConnectTimeout(12000);
                httpURLConnection2.setReadTimeout(20000);
                httpURLConnection2.setInstanceFollowRedirects(true);
                httpURLConnection2.setRequestProperty("User-Agent", UA);
                httpURLConnection2.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                httpURLConnection2.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                int responseCode = httpURLConnection2.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IllegalStateException("HTTP " + responseCode);
                }
                InputStream inputStream = httpURLConnection2.getInputStream();
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                byte[] bArr = new byte[16384];
                int i = 0;
                do {
                    int read = inputStream.read(bArr);
                    if (read <= 0) {
                        break;
                    }
                    byteArrayOutputStream.write(bArr, 0, read);
                    i += read;
                } while (i <= 2097152);
                inputStream.close();
                String str2 = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
                if (httpURLConnection2 != null) {
                    httpURLConnection2.disconnect();
                }
                return str2;
            } catch (Throwable th) {
                httpURLConnection = httpURLConnection2;
                if (httpURLConnection != null) {
                    httpURLConnection.disconnect();
                }
                throw th;
            }
        } catch (Throwable th2) {
            throw th2;
        }
    }

    static List<WebSearch.Result> parse(String str, int i) {
        int indexOf;
        String strip;
        ArrayList arrayList = new ArrayList();
        if (str == null) {
            return arrayList;
        }
        int i2 = 0;
        while (arrayList.size() < i && (indexOf = str.indexOf("<li class=\"b_algo\"", i2)) >= 0) {
            int indexOf2 = str.indexOf("</li>", indexOf);
            if (indexOf2 < 0) {
                indexOf2 = Math.min(str.length(), indexOf + 30000);
            }
            String substring = str.substring(indexOf, indexOf2);
            int i3 = indexOf2 + 5;
            Matcher matcher = H2.matcher(substring);
            if (matcher.find()) {
                Matcher matcher2 = A_HREF.matcher(matcher.group(1));
                if (matcher2.find()) {
                    String group = matcher2.group(1);
                    String strip2 = strip(matcher2.group(2));
                    if (!strip2.isEmpty()) {
                        Matcher matcher3 = P_CLAMP.matcher(substring);
                        if (matcher3.find()) {
                            strip = strip(matcher3.group(1));
                        } else {
                            Matcher matcher4 = P_ANY.matcher(substring);
                            strip = matcher4.find() ? strip(matcher4.group(1)) : "";
                        }
                        arrayList.add(new WebSearch.Result(strip2, group, strip));
                    }
                }
            }
            i2 = i3;
        }
        return arrayList;
    }

    // 把结果整理成喂给模型的纯文本（带序号与来源）。
    public static String format(List<WebSearch.Result> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < list.size()) {
            WebSearch.Result result = list.get(i);
            i++;
            sb.append(i);
            sb.append(". ");
            sb.append(result.title);
            sb.append("\n   ");
            sb.append(result.url);
            sb.append('\n');
            if (!result.snippet.isEmpty()) {
                sb.append("   ");
                sb.append(result.snippet);
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    static String strip(String str) {
        return str == null ? "" : unescape(str.replaceAll("<[^>]*>", " ")).replace((char) 160, ' ').replaceAll("\\s+", " ").trim();
    }

    static String unescape(String str) {
        String valueOf;
        StringBuilder sb = new StringBuilder(str.length());
        int i = 0;
        while (i < str.length()) {
            char charAt = str.charAt(i);
            if (charAt != '&') {
                sb.append(charAt);
            } else {
                int indexOf = str.indexOf(59, i);
                if (indexOf < 0 || indexOf - i > 12) {
                    sb.append(charAt);
                } else {
                    i++;
                    String substring = str.substring(i, indexOf);
                    if (substring.startsWith("#x") || substring.startsWith("#X")) {
                        valueOf = String.valueOf((char) Integer.parseInt(substring.substring(2), 16));
                    } else if (substring.startsWith("#")) {
                        try {
                            valueOf = String.valueOf((char) Integer.parseInt(substring.substring(1)));
                        } catch (Throwable unused) {
                            valueOf = null;
                        }
                    } else {
                        valueOf = named(substring);
                    }
                    if (valueOf == null) {
                        sb.append(charAt);
                    } else {
                        sb.append(valueOf);
                        i = indexOf + 1;
                    }
                }
            }
            i++;
        }
        return sb.toString();
    }

    private static String named(String str) {
        str.hashCode();
        switch (str) {
            case "hellip":
                return "…";
            case "middot":
                return "·";
            case "thinsp":
                return " ";
            case "gt":
                return ">";
            case "lt":
                return "<";
            case "amp":
                return "&";
            case "apos":
                return "'";
            case "emsp":
            case "ensp":
            case "nbsp":
                return " ";
            case "quot":
                return "\"";
            case "ldquo":
                return "“";
            case "lsquo":
                return "‘";
            case "mdash":
                return "—";
            case "ndash":
                return "–";
            case "rdquo":
                return "”";
            case "rsquo":
                return "’";
            case "times":
                return "×";
            default:
                return null;
        }
    }
}
