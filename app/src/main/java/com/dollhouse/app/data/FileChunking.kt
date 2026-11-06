package com.dollhouse.app.data

/**
 * 【职责】把选中的长文本切成可读的「分段引用」，供聊天时按段送进模型。
 *
 * ## 为什么需要
 * 现在选中一个文件后，整段内容会被拼成 `【文件内容】\n<全文>` 一次性发出去，
 * 而读取端有 24KB 上限（[com.dollhouse.app.PickFileActivity] 的 MAX_TEXT_BYTES）。
 * 结果是：**长文件被静默截断**，用户以为模型读完了，其实只读到前半段；
 * 而真正超长的内容即使全读进来，一次性塞给模型也会撑爆上下文。
 *
 * ## 本类做什么
 * - 按自然边界（行 → 句 → 硬切）切成不超过 [LIMIT] 的段；
 * - 每段加 `【文件：名 · 第 i/n 段】` 头，模型能知道这是同一份文件的不同部分；
 * - 段数、是否超长都**如实返回**，由界面告知用户，绝不静默丢弃。
 *
 * ## 不做什么
 * 不读文件、不发请求、不写历史。纯字符串处理，可直接单测。
 */
object FileChunking {

    /** 单段字符上限。约 4000 汉字，配合系统提示词仍留有余量。 */
    const val LIMIT = 4000

    /** 最多切多少段；超过部分明确告知「未纳入」，避免上下文无限膨胀。 */
    const val MAX_CHUNKS = 6

    class Plan(
        /** 每段的完整文本（含段头）。 */
        val chunks: List<String>,
        /** 原文总长度。 */
        val totalChars: Int,
        /** 是否因为超过 [MAX_CHUNKS] 而丢弃了尾部。 */
        val truncated: Boolean
    ) {
        fun isEmpty(): Boolean = chunks.isEmpty()
    }

    /**
     * 切分。
     *
     * @param name    文件名（用于段头，可空）
     * @param content 原文
     */
    @JvmStatic
    fun plan(name: String?, content: String?): Plan {
        val text = content ?: ""
        if (text.isBlank()) {
            return Plan(emptyList(), 0, false)
        }
        val pieces = split(text, LIMIT)
        val keep = if (pieces.size > MAX_CHUNKS) pieces.take(MAX_CHUNKS) else pieces
        val truncated = pieces.size > MAX_CHUNKS
        val safeName = if (name.isNullOrBlank()) "文件" else name
        val out = ArrayList<String>(keep.size)
        for (i in keep.indices) {
            val head = "【文件：" + safeName + " · 第 " + (i + 1) + "/" + pieces.size + " 段】"
            out.add(head + "\n" + keep[i])
        }
        return Plan(out, text.length, truncated)
    }

    /**
     * 按自然边界切分。
     *
     * 【优先级】换行 → 句号 / 问号 / 叹号 → 逗号 / 分号 → 硬切。
     *   硬切会产生「半句话」，所以放到最后；中文小说与代码里，换行与句号
     *   已经能覆盖绝大多数情况。
     */
    @JvmStatic
    fun split(text: String, limit: Int): List<String> {
        if (limit <= 0) {
            return listOf(text)
        }
        if (text.length <= limit) {
            return listOf(text)
        }
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + limit, text.length)
            if (end == text.length) {
                out.add(text.substring(start))
                break
            }
            val cut = findBoundary(text, start, end)
            out.add(text.substring(start, cut))
            start = cut
        }
        return out
    }

    /** 在 (start, end] 里往回找最靠后的自然边界；找不到就用 end。 */
    private fun findBoundary(text: String, start: Int, end: Int): Int {
        // 只回溯最后 20%，避免切出过短的段。
        val floor = start + (end - start) * 8 / 10
        for (i in end downTo floor) {
            if (i <= start) {
                break
            }
            val c = text[i - 1]
            if (c == '\n') {
                return i
            }
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '.' || c == '!' || c == '?') {
                return i
            }
            if (c == '，' || c == ',' || c == '、' || c == ' ') {
                return i
            }
        }
        return end
    }

    /** 给用户看的一句话说明：切了几段、有没有被截。 */
    @JvmStatic
    fun describe(plan: Plan): String {
        if (plan.isEmpty()) {
            return "文件内容为空"
        }
        val sb = StringBuilder()
        sb.append("已切为 ").append(plan.chunks.size).append(" 段（原文 ")
            .append(plan.totalChars).append(" 字）")
        if (plan.truncated) {
            sb.append("；超出 ").append(MAX_CHUNKS).append(" 段的部分未纳入")
        }
        return sb.toString()
    }
}