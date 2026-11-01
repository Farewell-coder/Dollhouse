package com.dollhouse.app.core

/** 无 Android 依赖的诊断格式规则，供存储层与 JVM 回归测试共同使用。 */
object DiagnosticsFormat {
    const val MAX_STACK_CHARS = 64 * 1024
    // 【性能·附录A-1】三个正则在旧实现里写在 [redact] 方法体内，每次调用都会新建
    //   `Regex(...)`（内部即 `Pattern.compile`，无缓存）。全工程 256 处日志调用点分布在
    //   主线程 UI 路径、SSE 读流线程与悬浮窗渲染线程上，单条日志要编译三次正则，
    //   在主线程上就是实打实的掉帧来源。提到 object 字段后只编译一次。
    private val BEARER = Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
    private val SECRET = Regex("(?i)(api[_-]?key|authorization|access[_-]?token|password|secret)(\\s*[=:]\\s*)([^\\s,;]+)")
    private val USER_DIR = Regex("(?<=/storage/emulated/0/)[^/\\s]+")
    fun redact(input: String): String {
        var s = input.replace(BEARER, "Bearer [已过滤]")
        s = s.replace(SECRET, "$1$2[已过滤]")
        s = s.replace(USER_DIR, "[用户目录]")
        return s.take(MAX_STACK_CHARS)
    }
    fun groupKey(severity: String, module: String, exceptionType: String, message: String): String = severity + "|" + module + "|" + exceptionType + "|" + message
    fun boundedText(input: String, max: Int): String = if (input.length <= max) input else input.take(max)
}