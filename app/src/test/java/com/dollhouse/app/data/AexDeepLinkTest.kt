package com.dollhouse.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 【职责】AEX 1.1 深链解析的回归测试。
 *
 * 覆盖 Aivora `protocol/AEX-DEEPLINK.md` §6 的必测用例：
 * 正常链接、缺 d、乱码、非 JSON、kind 不匹配、版本过新、baseUrl/name 缺失、超长。
 * 重点不是「能解析成功」，而是**每一种坏链接都要有明确原因且不崩溃**。
 */
class AexDeepLinkTest {

    private fun link(json: String, version: String = "1"): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        return "dollhouse://aivora-import?d=" + encoded + "&v=" + version
    }

    private fun payload(
        kind: String = "aivora.provider",
        ver: Int = 1,
        schema: String = "AEX",
        protocolVersion: String = "1.1",
        name: String = "来自 Aivora",
        baseUrl: String = "https://api.example.com/v1",
        apiKey: String = "sk-test-1234",
        includeBaseUrl: Boolean = true,
        includeName: Boolean = true
    ): String {
        val o = JSONObject()
        o.put("kind", kind)
        o.put("ver", ver)
        o.put("schema", schema)
        o.put("protocolVersion", protocolVersion)
        if (includeName) {
            o.put("name", name)
        }
        o.put("provider", "custom")
        if (includeBaseUrl) {
            o.put("baseUrl", baseUrl)
        }
        o.put("apiKey", apiKey)
        o.put("description", "备注")
        o.put("exportedAt", "2026-11-01T00:00:00Z")
        o.put("exporter", JSONObject().put("name", "Aivora").put("version", "0.0.8"))
        return o.toString()
    }

    @Test
    fun `正常深链解析出全部字段`() {
        val r = AexDeepLink.parseLink(link(payload()))
        assertTrue(r.message(), r.ok())
        val p = r.payload!!
        assertEquals("来自 Aivora", p.name)
        assertEquals("custom", p.provider)
        assertEquals("https://api.example.com/v1", p.baseUrl)
        assertEquals("sk-test-1234", p.apiKey)
        assertEquals("备注", p.description)
        assertEquals("1.1", p.protocolVersion)
        assertEquals("Aivora", p.exporterName)
        assertEquals("0.0.8", p.exporterVersion)
    }

    @Test
    fun `不是 dollhouse 的链接被拒绝`() {
        val r = AexDeepLink.parseLink(
            link(payload()).replace("dollhouse://", "https://")
        )
        assertEquals(AexDeepLink.Reason.NOT_AIVORA, r.reason)
        assertFalse(r.ok())
    }

    @Test
    fun `缺少 d 参数被拒绝`() {
        val r = AexDeepLink.parseLink("dollhouse://aivora-import?v=1")
        assertEquals(AexDeepLink.Reason.INCOMPLETE, r.reason)
    }

    @Test
    fun `缺少 v 参数被拒绝`() {
        val r = AexDeepLink.parseLink("dollhouse://aivora-import?d=abc")
        assertEquals(AexDeepLink.Reason.INCOMPLETE, r.reason)
    }

    @Test
    fun `d 参数不是合法 base64 被拒绝`() {
        val r = AexDeepLink.parseLink("dollhouse://aivora-import?d=!!!!&v=1")
        assertEquals(AexDeepLink.Reason.BAD_ENCODING, r.reason)
    }

    @Test
    fun `d 解出的不是 JSON 被拒绝`() {
        val bad = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not-json".toByteArray(Charsets.UTF_8))
        val r = AexDeepLink.parseLink("dollhouse://aivora-import?d=" + bad + "&v=1")
        assertEquals(AexDeepLink.Reason.BAD_JSON, r.reason)
    }

    @Test
    fun `kind 不匹配被拒绝`() {
        val r = AexDeepLink.parseLink(link(payload(kind = "other")))
        assertEquals(AexDeepLink.Reason.KIND_MISMATCH, r.reason)
    }

    @Test
    fun `schema 不匹配被拒绝`() {
        val r = AexDeepLink.parseLink(link(payload(schema = "OTHER")))
        assertEquals(AexDeepLink.Reason.KIND_MISMATCH, r.reason)
    }

    @Test
    fun `信封版本高于支持被拒绝`() {
        val r = AexDeepLink.parseLink(link(payload(), version = "2"))
        assertEquals(AexDeepLink.Reason.UNSUPPORTED_VERSION, r.reason)
    }

    @Test
    fun `载荷内 ver 高于支持被拒绝`() {
        val r = AexDeepLink.parseLink(link(payload(ver = 2)))
        assertEquals(AexDeepLink.Reason.UNSUPPORTED_VERSION, r.reason)
    }

    @Test
    fun `更高主版本协议被拒绝`() {
        val r = AexDeepLink.parseLink(link(payload(protocolVersion = "2.0")))
        assertEquals(AexDeepLink.Reason.UNSUPPORTED_VERSION, r.reason)
    }

    @Test
    fun `name 为空被拒绝`() {
        val r = AexDeepLink.parseLink(link(payload(includeName = false)))
        assertEquals(AexDeepLink.Reason.INVALID_CONTENT, r.reason)
    }

    @Test
    fun `baseUrl 为空被拒绝`() {
        val r = AexDeepLink.parseLink(link(payload(includeBaseUrl = false)))
        assertEquals(AexDeepLink.Reason.INVALID_CONTENT, r.reason)
    }

    @Test
    fun `超长链接被拒绝`() {
        val huge = "dollhouse://aivora-import?d=" + "A".repeat(AexDeepLink.MAX_LINK_LENGTH) + "&v=1"
        val r = AexDeepLink.parseLink(huge)
        assertEquals(AexDeepLink.Reason.TOO_LONG, r.reason)
    }

    @Test
    fun `空链接被拒绝`() {
        assertEquals(AexDeepLink.Reason.INCOMPLETE, AexDeepLink.parseLink(null).reason)
        assertEquals(AexDeepLink.Reason.INCOMPLETE, AexDeepLink.parseLink("").reason)
    }

    @Test
    fun `载荷转换为导入 profile 时模型留空`() {
        val p = AexDeepLink.toProfile(AexDeepLink.parseLink(link(payload())).payload!!)
        assertEquals("来自 Aivora", p.name)
        assertEquals("https://api.example.com/v1", p.baseUrl)
        assertEquals("sk-test-1234", p.apiKey)
        assertTrue(p.hasBaseUrl)
        assertTrue(p.hasApiKey)
        assertTrue("1.1 深链不带模型，交由 /models 拉取", p.models.isEmpty())
        assertTrue(p.defaultModel.isEmpty())
    }

    @Test
    fun `密钥只显示尾四位`() {
        assertEquals("（未提供）", AexDeepLink.maskKeyTail(""))
        assertEquals("（未提供）", AexDeepLink.maskKeyTail(null))
        assertEquals("••••", AexDeepLink.maskKeyTail("abc"))
        assertEquals("••••1234", AexDeepLink.maskKeyTail("sk-test-1234"))
    }
}
