package com.dollhouse.app.core

/** 无 Android 依赖的诊断格式规则，供存储层与 JVM 回归测试共同使用。 */
object DiagnosticsFormat {
    const val MAX_STACK_CHARS = 64 * 1024
    fun redact(input: String): String {
        var s = input.replace(Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE), "Bearer [已过滤]")
        s = s.replace(Regex("(?i)(api[_-]?key|authorization|access[_-]?token|password|secret)(\\s*[=:]\\s*)([^\\s,;]+)"), "$1$2[已过滤]")
        s = s.replace(Regex("(?<=/storage/emulated/0/)[^/\\s]+"), "[用户目录]")
        return s.take(MAX_STACK_CHARS)
    }
    fun groupKey(severity: String, module: String, exceptionType: String, message: String): String = severity + "|" + module + "|" + exceptionType + "|" + message
    fun boundedText(input: String, max: Int): String = if (input.length <= max) input else input.take(max)
}