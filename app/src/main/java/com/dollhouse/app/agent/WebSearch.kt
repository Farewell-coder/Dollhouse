package com.dollhouse.app.agent

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * 【职责】轻量联网搜索：抓取搜索结果页并用正则提取标题/链接/摘要。
 *
 * 【交互】只被 ChatPanel 的联网工具调用，无第三方依赖。
 *
 * 【坑】解析靠正则匹配 HTML，页面结构一变就会失效；H2/P_CLAMP/P_ANY 是几套兜底的抽取规则，命中率下降时应先看搜索结果页的 DOM 是否改版，而不是改正则硬凑。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
object WebSearch {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val H2 = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL)
    private val A_HREF = Pattern.compile("<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL)
    private val P_CLAMP = Pattern.compile("<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>", Pattern.DOTALL)
    private val P_ANY = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL)

    class Result(val title: String, val url: String, val snippet: String)

    // 抓搜索结果页并解析成结构化条目；页面改版会直接导致解析为空。
    @JvmStatic
    @Throws(Exception::class)
    fun search(str: String, i: Int): List<Result> {
        val encode = URLEncoder.encode(str, "UTF-8")
        val parse = parse(fetch("https://cn.bing.com/search?q=" + encode + "&ensearch=0&setlang=zh-CN"), i)
        if (!parse.isEmpty()) {
            return parse
        }
        return parse(fetch("https://www.bing.com/search?q=" + encode + "&setlang=zh-CN"), i)
    }

    @Throws(Exception::class)
    fun fetch(str: String): String {
        var httpURLConnection: HttpURLConnection? = null
        try {
            val httpURLConnection2 = URL(str).openConnection() as HttpURLConnection
            try {
                httpURLConnection2.connectTimeout = 12000
                httpURLConnection2.readTimeout = 20000
                httpURLConnection2.instanceFollowRedirects = true
                httpURLConnection2.setRequestProperty("User-Agent", UA)
                httpURLConnection2.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                httpURLConnection2.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                val responseCode = httpURLConnection2.responseCode
                if (responseCode < 200 || responseCode >= 300) {
                    throw IllegalStateException("HTTP " + responseCode)
                }
                val inputStream = httpURLConnection2.inputStream
                val byteArrayOutputStream = ByteArrayOutputStream()
                val bArr = ByteArray(16384)
                var i = 0
                do {
                    val read = inputStream.read(bArr)
                    if (read <= 0) {
                        break
                    }
                    byteArrayOutputStream.write(bArr, 0, read)
                    i += read
                } while (i <= 2097152)
                inputStream.close()
                val str2 = String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8)
                httpURLConnection2.disconnect()
                return str2
            } catch (th: Throwable) {
                httpURLConnection = httpURLConnection2
                if (httpURLConnection != null) {
                    httpURLConnection.disconnect()
                }
                throw th
            }
        } catch (th2: Throwable) {
            throw th2
        }
    }

    fun parse(str: String?, i: Int): List<Result> {
        val arrayList = ArrayList<Result>()
        if (str == null) {
            return arrayList
        }
        var i2 = 0
        while (arrayList.size < i) {
            val indexOf = str.indexOf("<li class=\"b_algo\"", i2)
            if (indexOf < 0) {
                break
            }
            var indexOf2 = str.indexOf("</li>", indexOf)
            if (indexOf2 < 0) {
                indexOf2 = Math.min(str.length, indexOf + 30000)
            }
            val substring = str.substring(indexOf, indexOf2)
            val i3 = indexOf2 + 5
            val matcher = H2.matcher(substring)
            if (matcher.find()) {
                val matcher2 = A_HREF.matcher(matcher.group(1))
                if (matcher2.find()) {
                    val group = matcher2.group(1)
                    val strip2 = strip(matcher2.group(2))
                    if (!strip2.isEmpty()) {
                        val matcher3 = P_CLAMP.matcher(substring)
                        val strip: String
                        if (matcher3.find()) {
                            strip = strip(matcher3.group(1))
                        } else {
                            val matcher4 = P_ANY.matcher(substring)
                            strip = if (matcher4.find()) strip(matcher4.group(1)) else ""
                        }
                        arrayList.add(Result(strip2, group, strip))
                    }
                }
            }
            i2 = i3
        }
        return arrayList
    }

    // 把结果整理成喂给模型的纯文本（带序号与来源）。
    @JvmStatic
    fun format(list: List<Result>?): String {
        if (list == null || list.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        var i = 0
        while (i < list.size) {
            val result = list[i]
            i++
            sb.append(i)
            sb.append(". ")
            sb.append(result.title)
            sb.append("\n   ")
            sb.append(result.url)
            sb.append('\n')
            if (!result.snippet.isEmpty()) {
                sb.append("   ")
                sb.append(result.snippet)
                sb.append('\n')
            }
        }
        return sb.toString().trim()
    }

    fun strip(str: String?): String {
        return if (str == null) "" else unescape(str.replace(Regex("<[^>]*>"), " ")).replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    fun unescape(str: String): String {
        val sb = StringBuilder(str.length)
        var i = 0
        while (i < str.length) {
            val charAt = str[i]
            if (charAt != '&') {
                sb.append(charAt)
            } else {
                val indexOf = str.indexOf(';', i)
                if (indexOf < 0 || indexOf - i > 12) {
                    sb.append(charAt)
                } else {
                    i++
                    val substring = str.substring(i, indexOf)
                    val valueOf: String?
                    if (substring.startsWith("#x") || substring.startsWith("#X")) {
                        valueOf = Integer.parseInt(substring.substring(2), 16).toChar().toString()
                    } else if (substring.startsWith("#")) {
                        try {
                            valueOf = Integer.parseInt(substring.substring(1)).toChar().toString()
                        } catch (unused: Throwable) {
                            valueOf = null
                        }
                    } else {
                        valueOf = named(substring)
                    }
                    if (valueOf == null) {
                        sb.append(charAt)
                    } else {
                        sb.append(valueOf)
                        i = indexOf + 1
                    }
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun named(str: String): String? {
        return when (str) {
            "hellip" -> "…"
            "middot" -> "·"
            "thinsp" -> " "
            "gt" -> ">"
            "lt" -> "<"
            "amp" -> "&"
            "apos" -> "'"
            "emsp", "ensp", "nbsp" -> " "
            "quot" -> "\""
            "ldquo" -> "“"
            "lsquo" -> "‘"
            "mdash" -> "—"
            "ndash" -> "–"
            "rdquo" -> "”"
            "rsquo" -> "’"
            "times" -> "×"
            else -> null
        }
    }
}
