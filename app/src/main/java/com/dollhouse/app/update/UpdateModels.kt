package com.dollhouse.app.update

/**
 * 【职责】更新功能的纯数据模型：通道、候选版本、检查结果、错误。
 *
 * 【为什么单独一层】检查流程（网络 + 解析）与展示（弹窗）不该互相知道对方的存在。
 *   中间只用这里的数据类型通信，将来换数据源（GitHub API → 自建清单）时，
 *   只有 [UpdateRepo] 需要改。
 *
 * 【为什么错误要分类】需求要求「检查失败绝不能等同于已是最新」——用独立的
 *   [UpdateError] 表达每种失败原因，界面才能给有意义的提示（而不是笼统的「失败」）。
 */

/** 更新通道。 */
enum class UpdateChannel(val label: String) {
    /** 稳定版：正式 Release（非 pre-release）。 */
    STABLE("稳定版"),

    /** 公测版：标记为 pre-release 的测试 Release。 */
    BETA("公测版");

    companion object {
        /** 读盘用：非法值一律回落到 [STABLE]（宁可用稳定版，也不因脏数据把检查搞崩）。 */
        fun fromKey(key: String?): UpdateChannel {
            return if (key == BETA.name) BETA else STABLE
        }
    }
}

/**
 * 一个候选版本（来自某个 Release）。
 *
 * @param versionName 规范化后的版本号（已剥 `v` 前缀）
 * @param rawTag      原始 tag，展示用（如 `v0.0.7-r14`）
 * @param notes       Release 正文（更新日志），已做长度裁剪
 * @param releaseUrl  Release 页面地址
 * @param apkUrl      APK 直链；该 Release 没传 APK 时为空串
 * @param publishedAt 发布时间（ISO8601 原样保留，仅展示）
 * @param isPrerelease 是否 pre-release
 */
data class UpdateCandidate(
    val versionName: String,
    val rawTag: String,
    val notes: String,
    val releaseUrl: String,
    val apkUrl: String,
    val publishedAt: String,
    val isPrerelease: Boolean
)

/**
 * 检查结果：三态**互斥**，禁止合并。
 *
 * 【为什么不用「可空 + 布尔」表达】需求明确要求区分「有新版 / 已最新 / 检查失败」，
 *   其中失败态不能与「已最新」混同（否则断网时会骗用户「已是最新」）。
 *   sealed class 让调用方的 when 必须覆盖全部分支。
 */
sealed class UpdateCheckResult {

    /** 有可用更新。 */
    data class Available(
        val candidate: UpdateCandidate,
        val channel: UpdateChannel,
        /** 当前版本，展示「0.0.7 → 0.0.8」用。 */
        val currentVersion: String
    ) : UpdateCheckResult()

    /** 已是最新。 */
    data class UpToDate(
        val channel: UpdateChannel,
        val currentVersion: String
    ) : UpdateCheckResult()

    /** 检查失败（网络 / 解析 / 无候选等），[error] 说明原因。 */
    data class Failed(val error: UpdateError) : UpdateCheckResult()
}

/** 失败原因。文案在此集中，界面不自己拼字符串。 */
sealed class UpdateError(val message: String) {

    /** 网络不可达 / 超时 / IO 异常。 */
    class Network(detail: String? = null) : UpdateError(
        if (detail.isNullOrEmpty()) "网络连接失败，请检查网络后重试" else "网络连接失败：$detail"
    )

    /** HTTP 状态码非 2xx。 */
    class Http(code: Int, detail: String? = null) : UpdateError(
        if (code == 403) {
            "请求过于频繁（GitHub 限流），请稍后再试"
        } else if (detail.isNullOrEmpty()) {
            "服务返回异常（HTTP $code）"
        } else {
            "服务返回异常（HTTP $code）：$detail"
        }
    )

    /** JSON 结构不符合预期。 */
    class Parse(detail: String? = null) : UpdateError(
        if (detail.isNullOrEmpty()) "更新数据格式异常" else "更新数据格式异常：$detail"
    )

    /** 该通道下没有任何可用的 Release。 */
    class NoRelease(channelLabel: String) : UpdateError("$channelLabel 还没有可用的发布版本")

    /** 远端版本号无法解析 → 不可判定，按需求不提示更新。 */
    class Unparsable(version: String) : UpdateError("无法识别的版本号：$version")
}