package com.dollhouse.app.data

/**
 * 【职责】模型适配向导的纯逻辑：从服务端返回的模型名里挑一个**默认模型**建议。
 *
 * 不联网、不写盘、不依赖 Android，因此可以直接单测；
 * 网络与落盘仍由 [com.dollhouse.app.ui.provider.ProviderNav] 与页面负责。
 *
 * 【为什么需要建议】服务端一次返回几十上百个名字，普通用户看不出该选哪个：
 *   - 名字里带 `embedding` / `rerank` / `tts` / `whisper` 的不能用来聊天；
 *   - 带 `vision` / `vl` 的通常也能聊天，但优先给纯文本更稳；
 *   - 各家命名毫无统一（`gpt-4o` / `deepseek-chat` / `qwen-max`）。
 * 这里按「明显可用 → 明显不可用」的顺序排除，再从剩下的里挑一个。
 *
 * 【为什么宁可返回 null 也不乱猜】没有合适候选时，让用户自己选比替他选错更好：
 *   选错的表现是「聊天发不出去」，而用户不会想到是默认模型的问题。
 */
object ModelRecommend {

    /** 明确不能用于「对话」的关键词（命中即排除）。 */
    private val NOT_CHAT = arrayOf(
        "embedding", "embed", "rerank", "tts", "speech", "whisper", "audio",
        "moderation", "dall-e", "image", "imagen", "stable-diffusion", "ocr"
    )

    /** 明确适合闲聊/对话的关键词，命中优先。 */
    private val PREFER = arrayOf(
        "chat", "instruct", "turbo", "gpt-4o", "gpt-4", "gpt-3.5",
        "deepseek", "qwen", "glm", "moonshot", "yi-", "claude", "gemini", "llama"
    )

    /**
     * 推荐一个默认模型名。
     *
     * @param models 服务端 `/models` 返回的名字（未排序）
     * @return 建议名；没有任何可用于聊天的候选时返回 null
     */
    @JvmStatic
    fun suggest(models: List<String>): String? {
        if (models.isEmpty()) {
            return null
        }
        // ① 先剔除明显不是聊天模型的（保留原始顺序，便于「第一个可用」稳定可复现）。
        val usable = models.filter { it.isNotBlank() && !isNonChat(it) }
        if (usable.isEmpty()) {
            return null
        }
        // ② 再优先挑「名字像聊天模型」的；此时仍按服务端顺序取第一个，
        //    不按字母排 —— 各家通常把主力模型放前面。
        val preferred = usable.firstOrNull { m -> PREFER.any { m.lowercase().contains(it) } }
        if (preferred != null) {
            return preferred
        }
        // ③ 没有明显特征词时，退回第一个可用项（总比空着好）。
        return usable.first()
    }

    /** 是否是明显不能用于对话的模型。 */
    @JvmStatic
    fun isNonChat(name: String): Boolean {
        val n = name.lowercase()
        return NOT_CHAT.any { n.contains(it) }
    }
}