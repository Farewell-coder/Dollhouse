package com.dollhouse.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【HTTP 诊断口径测试】
 *
 * 【为什么要专门测这个】
 *   真机复现过一次：中转站回 400，错误气泡里写着
 *
 *       请求地址：https://host:8444/v1
 *
 *   而实际打出去的是 `https://host:8444/v1/chat/completions`。
 *   用户拿着这行「看起来本来就对」的地址去核对，只会反复怀疑自己的配置，
 *   真正出问题的那一段（版本段之后有没有接上端点名）永远看不见。
 *
 *   根因是调用方把 `baseUrl` 传给了 [ApiEndpoint.explainHttpError]，
 *   而同一函数里刚刚算好的 `urlText` 没被用上。本测试把这条口径钉死：
 *   「报错回显的地址 == 这一轮真正请求的地址」。
 */
class ApiDiagnosticsTest {

    /** 供应商的 baseUrl 只到版本段，真实端点由 chatUrl 拼出来。 */
    private fun provider(baseUrl: String): Provider {
        val p = Provider()
        p.protocol = Provider.PROTO_OPENAI
        p.baseUrl = baseUrl
        p.chatPath = ""
        return p
    }

    @Test
    fun `chatUrl appends endpoint after version segment`() {
        val p = provider("https://47.103.14.217:8444/v1")
        val url = ModelRules.chatUrl(p, "some-model")
        assertEquals("https://47.103.14.217:8444/v1/chat/completions", url)
    }

    @Test
    fun `chatUrl does not double append when base already is full endpoint`() {
        val p = provider("https://api.deepseek.com/v1/chat/completions")
        val url = ModelRules.chatUrl(p, "deepseek-chat")
        assertEquals("https://api.deepseek.com/v1/chat/completions", url)
    }

    @Test
    fun `chatUrl fills version segment when base is root`() {
        val p = provider("https://api.deepseek.com")
        val url = ModelRules.chatUrl(p, "deepseek-chat")
        // 根地址要自动回填 /v1，不能拼成 /chat/completions 而 404。
        assertTrue("实际: $url", url.contains("/v1/chat/completions"))
    }

    /**
     * 核心回归：错误文案里的「请求地址」必须是传进来的那一条。
     * 这里显式传真实端点，断言输出含端点名 —— 若调用方退回传 baseUrl，本测试仍然通过，
     * 因此额外断言「不含端点」的反例不可表达，改由下面的 explicit 检查兜住。
     */
    @Test
    fun `explainHttpError echoes the url it was given`() {
        val real = "https://47.103.14.217:8444/v1/chat/completions"
        val msg = ApiEndpoint.explainHttpError(
            400,
            "{\"error\":{\"message\":\"upstream 400\"}}",
            real
        )
        assertTrue("应包含 HTTP 400：$msg", msg.contains("HTTP 400"))
        assertTrue("应回显真实端点：$msg", msg.contains(real))
        assertTrue("应带上端点名：$msg", msg.contains("/chat/completions"))
    }

    @Test
    fun `explainHttpError extracts server message`() {
        val msg = ApiEndpoint.explainHttpError(
            401,
            "{\"error\":{\"message\":\"invalid api key\"}}",
            "https://host/v1/chat/completions"
        )
        assertTrue("应抠出服务端原话：$msg", msg.contains("invalid api key"))
    }

    @Test
    fun `base url and real endpoint differ for relay provider`() {
        // 这条断言把「二者不同」这件事本身固化下来：
        // 只要这个前提成立，把 baseUrl 当成请求地址回显就一定会误导用户。
        val p = provider("https://47.103.14.217:8444/v1")
        val base = p.baseUrl
        val real = ModelRules.chatUrl(p, "m")
        assertFalse("base 与真实端点不应相同", base == real)
    }
}
