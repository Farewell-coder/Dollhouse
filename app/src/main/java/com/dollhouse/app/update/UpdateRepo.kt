package com.dollhouse.app.update

import com.dollhouse.app.ai.ApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 【职责】从 GitHub Releases 拉取候选版本并解析为 [UpdateCandidate]。
 *
 * 【为什么走 GitHub API 而不是自建清单】需求要求「纯 GitHub、零费用、无动态后端」。
 *   GitHub REST API 是公开、免鉴权、免费的基础能力，直接省掉了 Actions 生成清单
 *   与 Pages 托管那一整套链路；客户端只需读取，不必部署任何服务。
 *
 * 【鉴权】刻意**不带任何 Token**：未鉴权调用每小时 60 次，配合 24h 节流完全够用，
 *   而且客户端二进制里不留任何凭据（需求「客户端代码不出现任何秘密」）。
 *
 * 【通道取数】
 *   · 稳定版 → `/releases/latest`：GitHub 原生排除 draft 与 pre-release，取最新正式版；
 *   · 公测版 → `/releases?per_page=30` 全量拉取后**本地过滤** `prerelease == true`，
 *     再按 semver 取最高（不能取列表首个——排序是发布时间，不是版本号）。
 *
 * 【复用】UA 复用 [ApiClient.USER_AGENT]（GitHub API 强制要求带 UA，否则 403）——
 *   复用常量而非另建连接层，符合「优先复用现有网络栈」的口径。
 *
 * 【纯函数边界】解析与选版逻辑不碰网络（[parseCandidates] / [pickBest]），
 *   便于单测直接喂 JSON 字符串验证。
 */
object UpdateRepo {

    /** 仓库坐标。 */
    private const val OWNER = "Farewell-coder"
    private const val REPO = "Dollhouse"

    /** 仓库 Releases 页面：该版本没有 APK 时的兜底跳转目标。 */
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    /** 稳定版：latest 接口原生排除 draft 与 pre-release。 */
    private const val URL_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 公测版：列表接口，本地过滤 pre-release。本项目发版频率低，30 条足够。 */
    private const val URL_LIST = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"

    /** 网络超时：需求口径 10s 建连 / 10s 读取。 */
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 10000

    /** 响应体上限：防止异常响应把内存撑爆。 */
    private const val MAX_BODY_BYTES = 512 * 1024

    /** 更新日志展示上限：GitHub 正文可能几万字，截断后再给界面。 */
    private const val MAX_NOTES_CHARS = 3000

    /**
     * 拉取并判定是否有更新。**阻塞调用**，须放在 IO 线程（自动检查走协程 IO、
     * 手动检查走线程池）。
     *
     * @param currentVersion 本地版本号（可从 PackageManager 取，允许带 v 前缀）
     */
    fun fetch(channel: UpdateChannel, currentVersion: String): UpdateCheckResult {
        val url = if (channel == UpdateChannel.STABLE) URL_LATEST else URL_LIST
        val body = try {
            get(url)
        } catch (e: HttpStatusException) {
            if (e.code == 404) {
                // 404 = 该仓库还没有任何正式 Release（或列表为空）。
                return UpdateCheckResult.Failed(UpdateError.NoRelease(channel.label))
            }
            return UpdateCheckResult.Failed(UpdateError.Http(e.code, e.detail()))
        } catch (t: Throwable) {
            return UpdateCheckResult.Failed(UpdateError.Network(t.message))
        }

        val candidates = try {
            parseCandidates(channel, body)
        } catch (t: Throwable) {
            return UpdateCheckResult.Failed(UpdateError.Parse(t.message))
        }

        val best = pickBest(candidates)
            ?: return UpdateCheckResult.Failed(UpdateError.NoRelease(channel.label))

        // 本地版本无法解析 → 不可判定：不提示更新，也不谎报「已最新」。
        if (SemVer.normalize(currentVersion) == null) {
            return UpdateCheckResult.Failed(UpdateError.Unparsable(currentVersion))
        }
        if (SemVer.isNewer(best.versionName, currentVersion)) {
            return UpdateCheckResult.Available(best, channel, currentVersion)
        }
        return UpdateCheckResult.UpToDate(channel, currentVersion)
    }

    /* ==================== 解析（纯函数，可单测） ==================== */

    /**
     * 把 GitHub 返回的 JSON 解析成候选列表。
     *
     * @param body `/releases/latest` 返回**对象**、`/releases` 返回**数组**，这里两种都能吃。
     */
    fun parseCandidates(channel: UpdateChannel, body: String): List<UpdateCandidate> {
        val out = ArrayList<UpdateCandidate>()
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("响应为空")
        }
        val items: List<JSONObject> = if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } else {
            listOf(JSONObject(trimmed))
        }

        for (o in items) {
            // draft 一律不算候选（需求：过滤 draft 与 pre-release）。
            if (o.optBoolean("draft", false)) {
                continue
            }
            val pre = o.optBoolean("prerelease", false)
            // 稳定版通道只要正式版；公测版通道只要 pre-release。两个通道互不串味。
            if (channel == UpdateChannel.STABLE && pre) {
                continue
            }
            if (channel == UpdateChannel.BETA && !pre) {
                continue
            }
            val tag = o.optString("tag_name", "").trim()
                .ifEmpty { o.optString("name", "").trim() }
            // 版本号解析不出来就跳过（如自造的非版本 tag），不让它污染候选集。
            val ver = SemVer.normalize(tag) ?: continue
            out.add(
                UpdateCandidate(
                    versionName = ver,
                    rawTag = tag,
                    notes = clip(o.optString("body", "").trim(), MAX_NOTES_CHARS),
                    releaseUrl = o.optString("html_url", "").trim().ifEmpty { RELEASES_PAGE },
                    apkUrl = pickApkUrl(o),
                    publishedAt = o.optString("published_at", "").trim(),
                    isPrerelease = pre
                )
            )
        }
        return out
    }

    /** 按 semver 取最高版本；列表为空返回 null。 */
    fun pickBest(list: List<UpdateCandidate>): UpdateCandidate? {
        var best: UpdateCandidate? = null
        for (c in list) {
            val b = best
            if (b == null || SemVer.isNewer(c.versionName, b.versionName)) {
                best = c
            }
        }
        return best
    }

    /**
     * 从 assets 里挑 APK 直链。
     *
     * 【择优顺序】含 `arm64` 的包 > 含 `universal` 的包 > 第一个 `.apk`。
     *   本机是 arm64，专用包体积最小；没有专用包时用通用包。
     */
    private fun pickApkUrl(release: JSONObject): String {
        val assets = release.optJSONArray("assets") ?: return ""
        var first = ""
        var universal = ""
        var arm64 = ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "").trim()
            if (url.isEmpty() || !name.endsWith(".apk", ignoreCase = true)) {
                continue
            }
            val lower = name.lowercase()
            if (first.isEmpty()) {
                first = url
            }
            if (universal.isEmpty() && lower.contains("universal")) {
                universal = url
            }
            if (arm64.isEmpty() && (lower.contains("arm64") || lower.contains("aarch64"))) {
                arm64 = url
            }
        }
        return arm64.ifEmpty { universal.ifEmpty { first } }
    }

    private fun clip(s: String, max: Int): String {
        return if (s.length <= max) s else s.substring(0, max) + "…"
    }

    /* ==================== HTTP ==================== */

    /** HTTP 非 2xx：带着状态码与响应体摘要往上抛，便于分类报错。 */
    private class HttpStatusException(val code: Int, val body: String) : Exception("HTTP $code") {
        fun detail(): String = clip(body.replace(Regex("\\s+"), " ").trim(), 160)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            // GitHub API 强制要求 UA，缺失会直接 403。
            conn.setRequestProperty("User-Agent", ApiClient.USER_AGENT)
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = readAll(stream)
            if (code !in 200..299) {
                throw HttpStatusException(code, body)
            }
            return body
        } finally {
            try {
                conn.disconnect()
            } catch (ignored: Throwable) {
                // 断开失败不影响结果。
            }
        }
    }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) {
            return ""
        }
        return stream.use { input ->
            val buf = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < MAX_BODY_BYTES) {
                val n = input.read(buf)
                if (n <= 0) {
                    break
                }
                out.write(buf, 0, n)
                total += n
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}