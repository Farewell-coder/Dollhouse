package com.dollhouse.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】v0.2.0 新增「自家 JSON 导回」链路的回归测试，同时钉住
 *         **「外部分享零退化」** 这条硬约束。
 *
 * ## 为什么这两件事必须写在同一个文件里
 *
 * 新增一个 `detect()` 分支，风险不在于「新格式能不能认出来」，而在于
 * **「它会不会把原来能认的文件抢走」**。所以每个「能认」的用例旁边都配一条
 * 「外部格式仍然按原样认」的断言 —— 一旦将来有人把 JSON 判定挪到前面、
 * 或放宽条件，这些用例会立刻红。
 *
 * ## 覆盖范围
 *
 * | 组 | 内容 |
 * |---|---|
 * | ① 判定 | `looksLike` 的正反例、`detect` 的三态分流顺序 |
 * | ② 解析 | 信封解构、模型归组、名称去重、错误分支 |
 * | ③ 密钥 | 打码值不写入、空值语义、覆盖时不写空密钥的判据 |
 * | ④ 零退化 | AEX Magic / AKCP Magic 仍然分别返回 `AEX` / `AKCP` |
 */
class DollhouseJsonImportTest {

    /* ============================ 样本构造 ============================ */

    /** 构造一份与 `ExportSheet.buildJson` 结构一致的导出文件。 */
    private fun envelope(
        ver: Int = 1,
        providers: JSONArray,
        models: JSONArray = JSONArray()
    ): ByteArray {
        val root = JSONObject()
        root.put("kind", DollhouseJsonImport.KIND)
        root.put("ver", ver)
        root.put("exportedAt", 1750000000000L)
        root.put("includePlainKeys", false)
        root.put("providers", providers)
        root.put("models", models)
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun provider(
        id: String,
        name: String,
        baseUrl: String = "https://api.deepseek.com/v1",
        apiKey: String = "",
        chatPath: String = "",
        allowCleartext: Boolean = false
    ): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("protocol", "OPENAI")
        o.put("apiKey", apiKey)
        o.put("baseUrl", baseUrl)
        o.put("chatPath", chatPath)
        o.put("enabled", true)
        o.put("useResponseApi", false)
        o.put("resendHistoryReasoning", false)
        o.put("allowCleartext", allowCleartext)
        o.put("createdAt", 0L)
        o.put("updatedAt", 0L)
        return o
    }

    private fun model(providerId: String, displayName: String): JSONObject {
        val o = JSONObject()
        o.put("id", "m_" + displayName)
        o.put("providerId", providerId)
        o.put("displayName", displayName)
        o.put("kind", "chat")
        o.put("enabled", true)
        return o
    }

    /* ============================ ① 判定 ============================ */

    @Test
    fun looksLike_acceptsOwnEnvelope() {
        val arr = JSONArray().put(provider("p1", "DeepSeek"))
        assertTrue(DollhouseJsonImport.looksLike(envelope(providers = arr)))
    }

    @Test
    fun looksLike_toleratesLeadingWhitespace() {
        val raw = "\n\n   " + String(envelope(providers = JSONArray().put(provider("p1", "A"))), Charsets.UTF_8)
        assertTrue(DollhouseJsonImport.looksLike(raw.toByteArray(Charsets.UTF_8)))
    }

    /** 缩进格式（`toString(2)`）里 `"kind": "..."` 中间有空格，必须仍认得。 */
    @Test
    fun looksLike_toleratesSpaceAroundColon() {
        val raw = "{ \"kind\" :  \"dollhouse.providers\" }"
        assertTrue(DollhouseJsonImport.looksLike(raw.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun looksLike_rejectsOtherJson() {
        val raw = "{ \"kind\": \"something.else\", \"providers\": [] }"
        assertFalse(DollhouseJsonImport.looksLike(raw.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun looksLike_rejectsNonJson() {
        assertFalse(DollhouseJsonImport.looksLike("hello world".toByteArray()))
        assertFalse(DollhouseJsonImport.looksLike(ByteArray(0)))
        assertFalse(DollhouseJsonImport.looksLike(null))
    }

    /* ============================ ④ 零退化（核心保护） ============================ */

    /**
     * 【最重要的一条】AEX Magic 仍然返回 `AEX`。
     *
     * 【为什么单列一个用例】新增 JSON 分支时最可能犯的错是「判定顺序被挪动」，
     *   那样 `.akcp` 文件会被当成自家 JSON 或未知格式，用户从 Aivora 分享进来的
     *   文件全部失效 —— 这是用户明确列为硬约束的「外部分享零退化」。
     */
    @Test
    fun detect_stillReturnsAexForAexMagic() {
        val bytes = ByteArray(64)
        val magic = byteArrayOf(0x41, 0x45, 0x58, 0x31, 0x0d, 0x0a, 0x1a, 0x0a)
        System.arraycopy(magic, 0, bytes, 0, magic.size)
        assertEquals(AexImport.Kind.AEX, AexImport.detect(bytes))
    }

    /** 旧 AKCP 1.0 Magic 仍然返回 `AKCP`。 */
    @Test
    fun detect_stillReturnsAkcpForAkcpMagic() {
        val bytes = ByteArray(64)
        val magic = byteArrayOf(0x41, 0x4b, 0x43, 0x50, 0x0d, 0x0a, 0x1a, 0x0a)
        System.arraycopy(magic, 0, bytes, 0, magic.size)
        assertEquals(AexImport.Kind.AKCP, AexImport.detect(bytes))
    }

    /**
     * 【最刁钻的一条】一段**同时**以 AEX Magic 开头、正文里又恰好含自家信封标识的字节，
     * 必须仍被判成 `AEX`。
     *
     * 【为什么要有这条】它直接钉住「JSON 判定排在 AEX 之后」这个顺序。
     *   若有人把 JSON 判定提到前面，本用例是唯一能抓住这种改动的测试。
     */
    @Test
    fun detect_aexMagicWinsEvenIfBodyContainsJsonMarker() {
        val magic = byteArrayOf(0x41, 0x45, 0x58, 0x31, 0x0d, 0x0a, 0x1a, 0x0a)
        val marker = "{\"kind\":\"dollhouse.providers\"}".toByteArray()
        val bytes = ByteArray(magic.size + marker.size)
        System.arraycopy(magic, 0, bytes, 0, magic.size)
        System.arraycopy(marker, 0, bytes, magic.size, marker.size)
        assertEquals(AexImport.Kind.AEX, AexImport.detect(bytes))
    }

    @Test
    fun detect_returnsDollhouseJsonForOwnEnvelope() {
        val arr = JSONArray().put(provider("p1", "DeepSeek"))
        assertEquals(AexImport.Kind.DOLLHOUSE_JSON, AexImport.detect(envelope(providers = arr)))
    }

    @Test
    fun detect_returnsUnknownForRandomBytes() {
        assertEquals(AexImport.Kind.UNKNOWN, AexImport.detect(ByteArray(64) { 0x7f }))
    }

    /* ============================ ② 解析 ============================ */

    @Test
    fun parse_readsProvidersAndModels() {
        val providers = JSONArray()
            .put(provider("p1", "DeepSeek"))
            .put(provider("p2", "Moonshot"))
        val models = JSONArray()
            .put(model("p1", "deepseek-chat"))
            .put(model("p1", "deepseek-reasoner"))
            .put(model("p2", "moonshot-v1-8k"))
        val r = DollhouseJsonImport.parse(envelope(providers = providers, models = models))
        assertEquals("", r.error)
        assertEquals(2, r.profiles.size)
        // 名称保真
        assertEquals("DeepSeek", r.profiles[0].name)
        assertEquals("Moonshot", r.profiles[1].name)
        // 【模型必须归到各自的供应商】原实现若把 models 平铺，两条会全挂到第一个上。
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), r.profiles[0].models)
        assertEquals(listOf("moonshot-v1-8k"), r.profiles[1].models)
        assertEquals(0, r.droppedModels)
    }

    @Test
    fun parse_dropsModelWithoutMatchingProvider() {
        val providers = JSONArray().put(provider("p1", "A"))
        val models = JSONArray()
            .put(model("p1", "m1"))
            .put(model("p404", "orphan"))
        val r = DollhouseJsonImport.parse(envelope(providers = providers, models = models))
        assertEquals(1, r.droppedModels)
        assertEquals(listOf("m1"), r.profiles[0].models)
    }

    @Test
    fun parse_skipsProviderWithoutName() {
        val providers = JSONArray()
            .put(provider("p1", ""))
            .put(provider("p2", "有名字"))
        val r = DollhouseJsonImport.parse(envelope(providers = providers))
        assertEquals(1, r.profiles.size)
        assertEquals("有名字", r.profiles[0].name)
    }

    @Test
    fun parse_rejectsWrongKind() {
        val root = JSONObject()
        root.put("kind", "other.app")
        root.put("ver", 1)
        root.put("providers", JSONArray().put(provider("p1", "A")))
        val r = DollhouseJsonImport.parse(root.toString().toByteArray())
        assertTrue(r.error.isNotEmpty())
    }

    @Test
    fun parse_rejectsEmptyProviders() {
        val r = DollhouseJsonImport.parse(envelope(providers = JSONArray()))
        assertTrue(r.error.isNotEmpty())
    }

    @Test
    fun parse_rejectsBrokenJson() {
        val r = DollhouseJsonImport.parse("{ not json".toByteArray())
        assertTrue(r.error.isNotEmpty())
    }

    /** 更高 `ver` 不拒绝，只给提示 —— 结构没变时硬拒会让用户白白导入不了。 */
    @Test
    fun parse_acceptsHigherVersionWithNotice() {
        val arr = JSONArray().put(provider("p1", "A"))
        val r = DollhouseJsonImport.parse(envelope(ver = 9, providers = arr))
        assertEquals("", r.error)
        assertEquals(1, r.profiles.size)
        assertTrue(r.notice.isNotEmpty())
    }

    /* ============================ ③ 密钥策略 ============================ */

    /**
     * 【用户定案】打码密钥不写入，密钥留空。
     *
     * 【为什么这条必须有】把 `sk-1***…**19` 这种打码串当成真密钥存进库，
     *   之后每次请求都 401，而用户会以为是自己填错了 —— 比「没导入」更糟。
     */
    @Test
    fun parse_maskedKeyIsNotStored() {
        val arr = JSONArray().put(provider("p1", "A", apiKey = "sk-1******19"))
        val r = DollhouseJsonImport.parse(envelope(providers = arr))
        assertEquals(1, r.profiles.size)
        assertFalse(r.profiles[0].hasApiKey)
        assertEquals("", r.profiles[0].apiKey)
    }

    /** 真密钥要原样带出来（`includePlainKeys = true` 导出的文件）。 */
    @Test
    fun parse_plainKeyIsPreserved() {
        val arr = JSONArray().put(provider("p1", "A", apiKey = "sk-real-key-123456"))
        val r = DollhouseJsonImport.parse(envelope(providers = arr))
        assertTrue(r.profiles[0].hasApiKey)
        assertEquals("sk-real-key-123456", r.profiles[0].apiKey)
    }

    /**
     * 【空值语义】导出时本来就是空密钥（用户没填过），导回来仍是「没密钥」。
     *
     * 【为什么要与打码值分开测】两者最终都是 `hasApiKey = false`，但成因不同：
     *   · 打码值 → 「文件里有值，但不可用」，覆盖时**必须保留原密钥**；
     *   · 空值 → 「文件里确实没有值」，覆盖时可以清空。
     *   现在两条都归到 `hasApiKey = false`，因此**覆盖时必须一律保留原密钥**，
     *   这是保守但不会弄坏配置的选择（见 [DollhouseJsonImport.isMaskedKey] 的注释）。
     */
    @Test
    fun parse_emptyKeyYieldsNoKey() {
        val arr = JSONArray().put(provider("p1", "A", apiKey = ""))
        val r = DollhouseJsonImport.parse(envelope(providers = arr))
        assertFalse(r.profiles[0].hasApiKey)
        assertEquals("", r.profiles[0].apiKey)
    }

    @Test
    fun isMaskedKey_detectsCommonForms() {
        assertTrue(DollhouseJsonImport.isMaskedKey("sk-1******19"))
        assertTrue(DollhouseJsonImport.isMaskedKey("••••"))
        assertTrue(DollhouseJsonImport.isMaskedKey("sk-1…19"))
        assertFalse(DollhouseJsonImport.isMaskedKey("sk-1234567890abcdef"))
        assertFalse(DollhouseJsonImport.isMaskedKey(""))
    }

    /* ============================ 私有字段保留 ============================ */

    /**
     * 【备份→恢复必须行为一致】`allowCleartext` 这类字段丢了，
     * 自建站会「导入成功但一发请求就失败」，用户查不出原因。
     */
    @Test
    fun buildProvider_restoresPrivateFields() {
        val arr = JSONArray().put(
            provider("p1", "自建站", baseUrl = "http://10.0.0.5:8080/v1",
                chatPath = "/chat/completions", allowCleartext = true)
        )
        val bytes = envelope(providers = arr)
        val p = DollhouseJsonImport.buildProvider(bytes, "自建站")
        assertNotNull(p)
        assertEquals("http://10.0.0.5:8080/v1", p!!.baseUrl)
        assertEquals("/chat/completions", p.chatPath)
        assertTrue(p.allowCleartext)
    }

    /** 打码密钥在 `buildProvider` 里也要清掉，不能让它绕过 `toProfile` 的过滤。 */
    @Test
    fun buildProvider_clearsMaskedKey() {
        val arr = JSONArray().put(provider("p1", "A", apiKey = "sk-1******19"))
        val p = DollhouseJsonImport.buildProvider(envelope(providers = arr), "A")
        assertNotNull(p)
        assertEquals("", p!!.apiKey)
    }

    @Test
    fun buildProvider_returnsNullWhenNameMissing() {
        val arr = JSONArray().put(provider("p1", "A"))
        assertNull(DollhouseJsonImport.buildProvider(envelope(providers = arr), "不存在"))
    }

    @Test
    fun providerIdOf_matchesByName() {
        val arr = JSONArray()
            .put(provider("p1", "A"))
            .put(provider("p2", "B"))
        val bytes = envelope(providers = arr)
        assertEquals("p2", DollhouseJsonImport.providerIdOf(bytes, "B"))
        assertEquals("", DollhouseJsonImport.providerIdOf(bytes, "C"))
    }
}