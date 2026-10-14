package com.dollhouse.app.update

/**
 * 【职责】SemVer 2.0.0 版本比较，纯函数、**不引用任何 Android API**。
 *
 * 【为什么自己写而不引依赖】工程按「不为一个功能加新依赖」的口径维护（见关于页与
 *   第三方清单），而版本比较本身是不到 100 行的纯逻辑，引入 semver 库不划算。
 *
 * 【判定规则】严格按 SemVer 2.0.0 §11：
 *   · 数值段逐段按**数值**比较（所以 1.10.0 > 1.9.0，而不是字符串比较的 < ）；
 *   · 有 pre-release 标识的一律**小于**同版本的正式版（1.0.0-rc < 1.0.0）；
 *   · pre-release 逐段比较：纯数字段按数值比、非数字段按 ASCII 字典序；
 *     数字段 < 字母段；前缀相同则**段数多者更大**（1.0.0-alpha < 1.0.0-alpha.1）；
 *   · 构建元数据（+ 之后）不参与比较。
 *
 * 【v 前缀】GitHub tag 普遍带 `v`（v0.0.7），本类在 [normalize] 里统一剥掉，
 *   调用方不必自己处理。
 *
 * 【为什么返回 Int? 而不是 Int】无法解析的版本号属「不可判定」——按需求口径
 *   应当**不提示更新并记日志**，而不是当成相等或更大。用 null 表达这一态，
 *   调用方不可能忘记处理（编译器会提醒）。
 */
object SemVer {

    /**
     * 版本号语法：至多四段数值 + 可选 pre-release + 可选 build 元数据。
     *
     * 【为什么允许四段】Android 生态里 `1.2.3.4` 很常见（含本项目未来的 versionName 习惯），
     *   SemVer 只规定三段，这里宽进：多出来的段照样按数值参与比较。
     */
    private val PATTERN = Regex(
        "^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?" +
                "(?:-([0-9A-Za-z.\\-]+))?(?:\\+[0-9A-Za-z.\\-]+)?$"
    )

    /**
     * 剥掉 `v` / `V` 前缀并做语法校验。
     *
     * @return 规范化后的版本号；无法解析时返回 null。
     */
    fun normalize(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) {
            return null
        }
        val body = if (s[0] == 'v' || s[0] == 'V') s.substring(1) else s
        if (body.isEmpty() || !PATTERN.matches(body)) {
            return null
        }
        return body
    }

    /**
     * 比较两个版本号。
     *
     * @return a > b 返回正数、a < b 返回负数、a == b 返回 0；任一无法解析返回 null。
     */
    fun compare(a: String?, b: String?): Int? {
        val na = normalize(a) ?: return null
        val nb = normalize(b) ?: return null
        val ma = PATTERN.matchEntire(na) ?: return null
        val mb = PATTERN.matchEntire(nb) ?: return null

        // ---- 1. 主版本：四段数值逐段比 ----
        for (i in 1..4) {
            val va = parseNum(ma.groupValues[i])
            val vb = parseNum(mb.groupValues[i])
            if (va != vb) {
                return if (va > vb) 1 else -1
            }
        }

        // ---- 2. pre-release ----
        val pa = ma.groupValues[5]
        val pb = mb.groupValues[5]
        val hasA = pa.isNotEmpty()
        val hasB = pb.isNotEmpty()
        if (!hasA && !hasB) {
            return 0
        }
        // 有 pre-release 的一律更小（1.0.0-rc < 1.0.0）。
        if (hasA != hasB) {
            return if (hasA) -1 else 1
        }
        return comparePre(pa, pb)
    }

    /** 是否 a 比 b 新；任一不可解析返回 false（宁可不提示，也不误报）。 */
    fun isNewer(a: String?, b: String?): Boolean = (compare(a, b) ?: 0) > 0

    /** pre-release 逐段比较。 */
    private fun comparePre(a: String, b: String): Int {
        val sa = a.split('.')
        val sb = b.split('.')
        val n = Math.min(sa.size, sb.size)
        for (i in 0 until n) {
            val x = sa[i]
            val y = sb[i]
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            if (nx != null && ny != null) {
                if (nx != ny) {
                    return if (nx > ny) 1 else -1
                }
            } else if (nx != null) {
                // 数字段 < 字母段（SemVer 规定）。
                return -1
            } else if (ny != null) {
                return 1
            } else {
                val c = x.compareTo(y)
                if (c != 0) {
                    return if (c > 0) 1 else -1
                }
            }
        }
        // 前缀相同：段数多者更大。
        return when {
            sa.size > sb.size -> 1
            sa.size < sb.size -> -1
            else -> 0
        }
    }

    /** 取一段数值；缺失段按 0 计（`1.0` 与 `1.0.0` 等价）。 */
    private fun parseNum(s: String): Long {
        if (s.isEmpty()) {
            return 0L
        }
        return s.toLongOrNull() ?: 0L
    }
}
