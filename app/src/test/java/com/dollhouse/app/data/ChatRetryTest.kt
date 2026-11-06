package com.dollhouse.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出错后「是否还有可重答的问题」的判定口径。
 *
 * 该判定决定错误气泡上要不要出现「重试这次」——判错的后果是用户点了却得到
 * 「这条前面没有可重答的问题」，比不提供入口更糟。
 */
class ChatRetryTest {

    private fun msg(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("content", text)

    /** 与 ChatPanel.lastUserBeforeError 同口径：跳过尾部的助手消息，找到最近的 user。 */
    private fun lastUser(history: JSONArray): JSONObject? {
        for (i in history.length() - 1 downTo 0) {
            val o = history.optJSONObject(i) ?: continue
            when (o.optString("role")) {
                "assistant" -> continue
                "user" -> return o
            }
        }
        return null
    }

    @Test
    fun `提问后失败可以重试`() {
        val h = JSONArray().put(msg("user", "你好")).put(msg("assistant", "（出错了：超时）"))
        assertTrue(lastUser(h) != null)
    }

    @Test
    fun `空历史不可重试`() {
        assertFalse(lastUser(JSONArray()) != null)
    }

    @Test
    fun `只有助手消息不可重试`() {
        val h = JSONArray().put(msg("assistant", "（出错了：超时）"))
        assertFalse(lastUser(h) != null)
    }

    @Test
    fun `跳过连续多条助手消息找到提问`() {
        val h = JSONArray()
            .put(msg("user", "问一句"))
            .put(msg("assistant", "（出错了：1）"))
            .put(msg("assistant", "（出错了：2）"))
        assertTrue(lastUser(h)?.optString("content") == "问一句")
    }
}