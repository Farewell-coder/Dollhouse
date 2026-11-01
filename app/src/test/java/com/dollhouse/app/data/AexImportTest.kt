package com.dollhouse.app.data

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 【职责】AEX 1.0 的解析 / 拒绝路径回归测试，严格对齐官方 `protocol/AEX-TEST-VECTORS.md` 的
 *         向量清单（V01–V14）与 Schema 约束。
 *
 * ## 为什么自己造样本
 * 官方测试向量目录 `protocol/test-vectors/` 目前为空（文档写明「AEX 参考实现尚未落地，
 * 正式向量将在实现后补充」）。因此这里按规范**独立构造**样本：
 *   - 用固定的合成凭证、固定 Salt/Nonce，保证可复现；
 *   - 构造代码不复用实现的私有方法，这样才能真正发现实现偏移；
 *   - 一旦官方向量落地，只需把 [build] 换成读文件，用例断言全部可复用。
 *
 * ## 覆盖范围（对应官方向量清单）
 * V01 单配置齐全 / V02 仅 id+name / V03 三配置批量 / V04 extra 扩展 /
 * V05 错误凭证 / V06 密文取反 / V07 头部 iterations 被改 / V08 主版本 2.0 /
 * V09 头部长度不符 / V10 去掉标签或截断 / V11 Magic 错误 / V12 超 16 MiB /
 * V13 尾部追加字节 / V14 旧版 .aivora 识别为 legacy。
 */
class AexImportTest {

    /** 官方向量规定的固定合成凭证：52 个 A。 */
    private val credential = "A".repeat(52)
    /** 只改一个字符的错误凭证（V05）。 */
    private val wrongCredential = "A".repeat(51) + "B"
    /** 固定的 16 字节 Salt：00…0F。 */
    private val salt = ByteArray(16) { it.toByte() }
    /** 固定的 12 字节 Nonce：00…0B。 */
    private val nonce = ByteArray(12) { it.toByte() }

    /**
     * 每个用例前复位失败计数与冷却。
     *
     * 【为什么必须】实现里有「连续 5 次认证失败 → 冷却 30 秒」的保护，
     *   而拒绝路径用例本身就会故意失败多次；不复位的话，后面的用例会先撞上
     *   TOO_MANY_ATTEMPTS 而不是预期原因，变成互相干扰的假失败。
     */
    @Before
    fun resetCooldown() {
        AexImport.resetAttempts()
    }

    /* ============================ 样本构造 ============================ */

    /**
     * 按 AEX-SPECIFICATION 第 2 节的容器结构构造一个合法 `.akcp`。
     *
     * 结构：`Magic(8) ‖ H(4, BE) ‖ header(H) ‖ ct‖tag`，
     * AAD = `Magic ‖ H ‖ header`（全部为文件里的原始字节）。
     */
    private fun build(
        payload: JSONObject,
        cred: String = credential,
        headerOverride: ((JSONObject) -> Unit)? = null,
        saltBytes: ByteArray = salt,
        nonceBytes: ByteArray = nonce,
        iterations: Int = 600000
    ): ByteArray {
        val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)

        // ① 头部 JSON（字段顺序固定，与官方示例一致）
        val header = JSONObject()
        header.put("format", "aivora.exchange/1")
        header.put("protocolVersion", "1.0")
        header.put("kdf", "PBKDF2-HMAC-SHA256")
        header.put("iterations", iterations)
        header.put("salt", b64(saltBytes))
        header.put("cipher", "AES-256-GCM")
        header.put("nonce", b64(nonceBytes))
        header.put("tagBits", 128)
        // ciphertextLength 必须在算完密文后才能定，但 GCM 标签固定 16 字节，
        // 所以这里可以直接得出，且长度本身是 AAD 的一部分 —— 一次算准。
        header.put("ciphertextLength", payloadBytes.size + 16)
        header.put("payloadEncoding", "json")
        headerOverride?.invoke(header)

        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)

        // ② AAD = Magic ‖ uint32be(H) ‖ header 原始字节
        val prefix = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x41, 0x45, 0x58, 0x31, 0x0d, 0x0a, 0x1a, 0x0a))
            .putInt(headerBytes.size)
            .array()
        val aad = prefix + headerBytes

        // ③ 密钥 = PBKDF2-HMAC-SHA256(normalize(cred), salt, iterations, 32)
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        gen.init(normalize(cred).toByteArray(Charsets.US_ASCII), saltBytes, iterations)
        val key = (gen.generateDerivedParameters(256) as KeyParameter).key

        // ④ AES-256-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceBytes))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(payloadBytes)

        val out = ByteArrayOutputStream()
        out.write(aad)
        out.write(ct)
        return out.toByteArray()
    }

    /** 与实现同口径的凭证规范化（去空白 → 去 `-` → 转大写）。 */
    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            when {
                c == '-' -> continue
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> continue
                else -> sb.append(c.uppercaseChar())
            }
        }
        return sb.toString()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** 构造一份符合 AEX-SCHEMA 的根对象。 */
    private fun payload(vararg profiles: JSONObject): JSONObject {
        val root = JSONObject()
        root.put("format", "aivora.exchange/1")
        root.put("protocolVersion", "1.0")
        root.put("exportedAt", "2026-11-01T09:00:00Z")
        root.put(
            "exporter", JSONObject()
                .put("name", "Aivora").put("version", "0.0.5").put("platform", "android")
        )
        val arr = JSONArray()
        profiles.forEach { arr.put(it) }
        root.put("profiles", arr)
        return root
    }

    private fun profile(
        id: String = "8f3a1c72-2b4d-4e11-9a55-7c0d3e6f1b20",
        name: String = "DeepSeek 官方",
        provider: String? = "deepseek",
        baseUrl: String? = "https://api.deepseek.com/v1",
        apiKey: String? = "sk-EXAMPLE-REPLACE-ME",
        models: List<String>? = listOf("deepseek-chat", "deepseek-reasoner"),
        defaultModel: String? = "deepseek-chat",
        description: String? = null
    ): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        provider?.let { o.put("provider", it) }
        baseUrl?.let { o.put("baseUrl", it) }
        apiKey?.let { o.put("apiKey", it) }
        models?.let { list ->
            val ms = JSONArray()
            list.forEach { ms.put(it) }
            o.put("models", ms)
        }
        defaultModel?.let { o.put("defaultModel", it) }
        description?.let { o.put("description", it) }
        return o
    }

    /* ============================ V01–V04 正常路径 ============================ */

    /** V01：单配置、全部字段齐全。 */
    @Test
    fun `V01 单配置字段齐全能解开并原样映射`() {
        val bytes = build(payload(profile(description = "日常对话用")))
        val r = AexImport.parse(bytes, credential, "config.akcp")
        assertTrue("应解密成功，实际：" + r.code() + " " + r.message(), r.ok())
        assertEquals(1, r.profiles.size)
        val p = r.profiles[0]
        assertEquals("DeepSeek 官方", p.name)
        assertEquals("deepseek", p.provider)
        // 【关键】baseUrl 必须原样保留，不得裁剪或补斜杠（规范「不得擅自改变原始值」）。
        assertEquals("https://api.deepseek.com/v1", p.baseUrl)
        assertEquals("sk-EXAMPLE-REPLACE-ME", p.apiKey)
        // models 顺序必须保留
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), p.models)
        assertEquals("deepseek-chat", p.defaultModel)
        assertEquals("日常对话用", p.description)
    }

    /** V02：仅 id + name，其余字段缺失 → 语义为「未设置」。 */
    @Test
    fun `V02 仅 id 与 name 也能导入且缺失字段视为未设置`() {
        val bytes = build(payload(profile(provider = null, baseUrl = null, apiKey = null, models = null, defaultModel = null)))
        val r = AexImport.parse(bytes, credential, "config.akcp")
        assertTrue(r.ok())
        val p = r.profiles[0]
        assertEquals("DeepSeek 官方", p.name)
        // 【空值与缺失值不同】缺失时 has 为 false
        assertFalse("baseUrl 未设置", p.hasBaseUrl)
        assertFalse("apiKey 未设置", p.hasApiKey)
        assertTrue("应提示没带密钥", r.warnings.any { it.contains("密钥") })
    }

    /** V03：三配置批量，顺序必须与导出顺序一致。 */
    @Test
    fun `V03 三配置批量顺序不变`() {
        val bytes = build(
            payload(
                profile(id = "a", name = "甲"),
                profile(id = "b", name = "乙"),
                profile(id = "c", name = "丙")
            )
        )
        val r = AexImport.parse(bytes, credential, "config.akcp")
        assertTrue(r.ok())
        assertEquals(3, r.profiles.size)
        assertEquals(listOf("甲", "乙", "丙"), r.profiles.map { it.name })
    }

    /** V04：带 extra 命名空间扩展 → 能读到，且提示「存不下」。 */
    @Test
    fun `V04 extra 扩展字段可读且提示存不下`() {
        val p = profile()
        p.put("extra", JSONObject().put("com.example.notes.pinned", true))
        val r = AexImport.parse(build(payload(p)), credential, "config.akcp")
        assertTrue(r.ok())
        val one = r.profiles[0]
        assertTrue("应识别为存不下的内容", one.hasExtra())
        assertTrue("extra 内容应可读", one.extra!!.optBoolean("com.example.notes.pinned"))
    }

    /* ============================ V05–V10 认证与篡改 ============================ */

    /** V05：错误凭证 → AUTHENTICATION_FAILED，且没有任何明文。 */
    @Test
    fun `V05 错误凭证被拒绝且不吐明文`() {
        val bytes = build(payload(profile()))
        val r = AexImport.parse(bytes, wrongCredential, "config.akcp")
        assertFalse(r.ok())
        assertEquals(AexImport.Reason.AUTHENTICATION_FAILED, r.reason)
        assertEquals("AUTHENTICATION_FAILED", r.code())
        assertTrue("认证失败绝不能吐出明文", r.profiles.isEmpty())
    }

    /** V06：密文倒数第 1 字节取反 → AUTHENTICATION_FAILED。 */
    @Test
    fun `V06 密文最后一字节取反即认证失败`() {
        val bytes = build(payload(profile()))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val r = AexImport.parse(bytes, credential, "config.akcp")
        assertEquals(AexImport.Reason.AUTHENTICATION_FAILED, r.reason)
        assertTrue(r.profiles.isEmpty())
    }

    /**
     * V07：头部 iterations 被改（600000 → 600001）→ AUTHENTICATION_FAILED。
     *
     * 【要点】AAD 覆盖完整头部，改 iterations 会同时改变派生密钥与 AAD，
     *   两边都变 → 必然认证失败。实现里绝不能「忽略 iterations 用默认值」。
     */
    @Test
    fun `V07 头部迭代次数被改即认证失败`() {
        val original = build(payload(profile()))
        // 在头部 JSON 文本里把 600000 改成 600001（长度不变，H 不变）
        val text = String(original, Charsets.ISO_8859_1)
        assertEquals("测试前提：头部确实含有 600000", true, text.contains("600000"))
        val hacked = text.replaceFirst("600000", "600001")
        val bytes = hacked.toByteArray(Charsets.ISO_8859_1)
        // 长度必须不变，否则会先被长度校验拦下（那就测不到 AAD 了）
        assertEquals(original.size, bytes.size)
        val r = AexImport.parse(bytes, credential, "config.akcp")
        assertEquals(AexImport.Reason.AUTHENTICATION_FAILED, r.reason)
    }

    /** V08：头部 protocolVersion 改为 2.0 → UNSUPPORTED_VERSION（参数校验先于解密）。 */
    @Test
    fun `V08 更高主版本被拒绝`() {
        val bytes = build(payload(profile()), headerOverride = { it.put("protocolVersion", "2.0") })
        val r = AexImport.parse(bytes, credential, "config.akcp")
        assertEquals(AexImport.Reason.UNSUPPORTED_VERSION, r.reason)
        assertEquals("UNSUPPORTED_VERSION", r.code())
    }

    /** V09a：头部长度字段与实际不符（H 改大，越界）→ INVALID_HEADER。 */
    @Test
    fun `V09a 头部长度越界被拒绝`() {
        val bytes = build(payload(profile()))
        // H 写成 9000（超过 8192 上限）
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(8, 9000)
        assertEquals(AexImport.Reason.INVALID_HEADER, AexImport.parse(bytes, credential, "x.akcp").reason)
    }

    /** V09b：头部 JSON 被破坏（首个字节改成非 JSON 字符）→ INVALID_HEADER。 */
    @Test
    fun `V09b 头部 JSON 损坏被拒绝`() {
        val bytes = build(payload(profile()))
        // 偏移 12 是头部 JSON 的第一个字节（应为 `{`）
        bytes[12] = 'X'.code.toByte()
        assertEquals(AexImport.Reason.INVALID_HEADER, AexImport.parse(bytes, credential, "x.akcp").reason)
    }

    /** V10：文件被截断（去掉 16 字节标签）→ 长度校验失败或认证失败，总之拒绝。 */
    @Test
    fun `V10 文件被截断即拒绝`() {
        val bytes = build(payload(profile()))
        val cut = bytes.copyOf(bytes.size - 16)
        val r = AexImport.parse(cut, credential, "x.akcp")
        assertFalse(r.ok())
        // 长度自洽校验会先命中 INVALID_HEADER；若实现改成先解密，则应是 AUTHENTICATION_FAILED。
        assertTrue(
            "应为 INVALID_HEADER 或 AUTHENTICATION_FAILED，实际：" + r.code(),
            r.reason == AexImport.Reason.INVALID_HEADER || r.reason == AexImport.Reason.AUTHENTICATION_FAILED
        )
        assertTrue(r.profiles.isEmpty())
    }

    /* ============================ V11–V14 容器级拒绝 ============================ */

    /** V11：Magic 错误 → INVALID_MAGIC。 */
    @Test
    fun `V11 Magic 错误被拒绝`() {
        val bytes = build(payload(profile()))
        bytes[0] = 'Z'.code.toByte()
        assertEquals(AexImport.Reason.INVALID_MAGIC, AexImport.parse(bytes, credential, "x.akcp").reason)
    }

    /** V12：超过 16 MiB → FILE_TOO_LARGE（在解析头部之前就拦）。 */
    @Test
    fun `V12 超大文件被拒绝`() {
        val huge = ByteArray(AexImport.MAX_FILE_SIZE + 1)
        // 前面放合法 Magic，确保不是先被 INVALID_MAGIC 拦下
        byteArrayOf(0x41, 0x45, 0x58, 0x31, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(huge)
        assertEquals(AexImport.Reason.FILE_TOO_LARGE, AexImport.parse(huge, credential, "x.akcp").reason)
    }

    /** V13：尾部追加 1 字节 → 拒绝（规范禁止尾部多余字节）。 */
    @Test
    fun `V13 尾部多余字节被拒绝`() {
        val bytes = build(payload(profile()))
        val padded = bytes + byteArrayOf(0x00)
        val r = AexImport.parse(padded, credential, "x.akcp")
        assertFalse(r.ok())
        assertEquals(AexImport.Reason.INVALID_HEADER, r.reason)
    }

    /** V14：旧版 `.aivora`（AKCP Magic）→ 识别为 legacy，绝不用 AEX 参数去解。 */
    @Test
    fun `V14 旧版容器被识别为 legacy 而非 AEX`() {
        val akcp = ByteArray(80)
        byteArrayOf(0x41, 0x4b, 0x43, 0x50, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(akcp)
        assertEquals(AexImport.Kind.AKCP, AexImport.detect(akcp))
        // 用 AEX 解析器处理旧文件：必须明确报「不是 AEX」，不得报凭证错误。
        val r = AexImport.parse(akcp, credential, "x.akcp")
        assertEquals(AexImport.Reason.INVALID_EXTENSION, r.reason)
    }

    /* ============================ 参数与边界 ============================ */

    /** 迭代次数超出合法范围 → UNSUPPORTED_PARAMETER（防资源耗尽）。 */
    @Test
    fun `迭代次数越界被拒绝`() {
        val low = build(payload(profile()), headerOverride = { it.put("iterations", 1000) })
        assertEquals(AexImport.Reason.UNSUPPORTED_PARAMETER, AexImport.parse(low, credential, "x.akcp").reason)
        val high = build(payload(profile()), headerOverride = { it.put("iterations", 3000000) })
        assertEquals(AexImport.Reason.UNSUPPORTED_PARAMETER, AexImport.parse(high, credential, "x.akcp").reason)
    }

    /** KDF / Cipher / tagBits 不在白名单 → UNSUPPORTED_PARAMETER，不得回退默认算法。 */
    @Test
    fun `未知算法与标签长度被拒绝`() {
        val kdf = build(payload(profile()), headerOverride = { it.put("kdf", "Argon2id") })
        assertEquals(AexImport.Reason.UNSUPPORTED_PARAMETER, AexImport.parse(kdf, credential, "x.akcp").reason)

        val cipher = build(payload(profile()), headerOverride = { it.put("cipher", "ChaCha20-Poly1305") })
        assertEquals(AexImport.Reason.UNSUPPORTED_PARAMETER, AexImport.parse(cipher, credential, "x.akcp").reason)

        val tag = build(payload(profile()), headerOverride = { it.put("tagBits", 96) })
        assertEquals(AexImport.Reason.UNSUPPORTED_PARAMETER, AexImport.parse(tag, credential, "x.akcp").reason)
    }

    /** salt / nonce 长度不对 → INVALID_HEADER。 */
    @Test
    fun `salt 与 nonce 长度不符被拒绝`() {
        val badSalt = build(payload(profile()), headerOverride = { it.put("salt", b64(ByteArray(8))) })
        assertEquals(AexImport.Reason.INVALID_HEADER, AexImport.parse(badSalt, credential, "x.akcp").reason)

        val badNonce = build(payload(profile()), headerOverride = { it.put("nonce", b64(ByteArray(8))) })
        assertEquals(AexImport.Reason.INVALID_HEADER, AexImport.parse(badNonce, credential, "x.akcp").reason)
    }

    /* ============================ Payload / Schema ============================ */

    /** 某条缺 name → 跳过该条，其余照常导入。 */
    @Test
    fun `缺必填字段的条被跳过其他条照常导入`() {
        val bad = JSONObject().put("id", "bad").put("name", "")
        val bytes = build(payload(profile(id = "good", name = "甲"), bad))
        val r = AexImport.parse(bytes, credential, "x.akcp")
        assertTrue(r.ok())
        assertEquals(1, r.profiles.size)
        assertEquals("甲", r.profiles[0].name)
        assertTrue(r.warnings.any { it.contains("跳过") })
    }

    /** 全部条缺字段 → INVALID_PAYLOAD。 */
    @Test
    fun `全部条不合法时整份判为载荷非法`() {
        val bad = JSONObject().put("id", "").put("name", "")
        assertEquals(
            AexImport.Reason.INVALID_PAYLOAD,
            AexImport.parse(build(payload(bad)), credential, "x.akcp").reason
        )
    }

    /** profiles 为空数组 → INVALID_PAYLOAD（Schema minItems=1）。 */
    @Test
    fun `profiles 为空被拒绝`() {
        val root = JSONObject()
        root.put("format", "aivora.exchange/1")
        root.put("protocolVersion", "1.0")
        root.put("exportedAt", "2026-11-01T09:00:00Z")
        root.put("exporter", JSONObject().put("name", "Aivora").put("version", "0.0.5"))
        root.put("profiles", JSONArray())
        assertEquals(
            AexImport.Reason.INVALID_PAYLOAD,
            AexImport.parse(build(root), credential, "x.akcp").reason
        )
    }

    /** Payload 的 format 与协议不符 → INVALID_PAYLOAD（二次校验）。 */
    @Test
    fun `Payload 的 format 不符被拒绝`() {
        val root = payload(profile())
        root.put("format", "something-else/1")
        assertEquals(
            AexImport.Reason.INVALID_PAYLOAD,
            AexImport.parse(build(root), credential, "x.akcp").reason
        )
    }

    /* ============================ 凭证规范化与入口 ============================ */

    /** 凭证带连字符 / 空格 / 小写，规范化后仍可解开。 */
    @Test
    fun `凭证带连字符空格与小写也能解开`() {
        val grouped = "aaaaaaaa-aaaaaaaa-aaaaaaaa-aaaaaaaa-aaaaaaaa-aaaaaaaa-aaaa"
        val bytes = build(payload(profile()))
        assertTrue(AexImport.normalizeCredential(grouped) == credential)
        assertTrue(AexImport.parse(bytes, grouped, "x.akcp").ok())
    }

    /** 凭证格式不对（长度或字符集）→ 直接拒绝，不做模糊修正。 */
    @Test
    fun `凭证格式不合法直接拒绝`() {
        assertFalse(AexImport.credentialLooksValid("too-short"))
        assertFalse(AexImport.credentialLooksValid("A".repeat(51) + "1"))  // `1` 不在 Base32 字母表
        assertTrue(AexImport.credentialLooksValid("A".repeat(52)))
        val bytes = build(payload(profile()))
        assertEquals(
            AexImport.Reason.AUTHENTICATION_FAILED,
            AexImport.parse(bytes, "not-a-valid-credential", "x.akcp").reason
        )
    }

    /** 后缀判定只认小写 `.akcp`。 */
    @Test
    fun `后缀判定只认小写 akcp`() {
        assertTrue(AexImport.acceptsName("config.akcp"))
        assertFalse(AexImport.acceptsName("config.AKCP"))
        assertFalse(AexImport.acceptsName("config.aivora"))
        assertFalse(AexImport.acceptsName(".akcp"))
        assertFalse(AexImport.acceptsName(null))
    }

    /** 【容错】内容 Magic 正确但文件被改名，仍应能导入（规范禁止的是「只凭后缀判断」）。 */
    @Test
    fun `内容正确但改名文件仍可导入`() {
        val bytes = build(payload(profile()))
        val r = AexImport.parse(bytes, credential, "renamed.bin")
        assertTrue("Magic 命中时应容忍改名，实际：" + r.code(), r.ok())
    }

    /** 密钥打码只露头尾。 */
    @Test
    fun `密钥打码只露头尾`() {
        assertEquals("（未提供）", AexImport.maskKey(""))
        assertEquals("••••", AexImport.maskKey("short"))
        assertEquals("sk-E…E-ME", AexImport.maskKey("sk-EXAMPLE-REPLACE-ME"))
    }

    /** 次版本偏新（1.1）仍可读，并给出提示。 */
    @Test
    fun `次版本偏新仍可读并提示`() {
        val bytes = build(payload(profile()), headerOverride = { it.put("protocolVersion", "1.1") })
        val r = AexImport.parse(bytes, credential, "x.akcp")
        assertTrue("实际：" + r.code(), r.ok())
        assertTrue(r.warnings.any { it.contains("1.1") })
    }

    // ==================== 官方测试向量互通（V01 / V03） ====================

    /**
     * 读官方 `protocol/test-vectors/` 下的 `.akcp` 文件（由 Aivora 的独立 Python 实现生成）。
     *
     * 【为什么这条最重要】自造向量只能证明「实现自洽」。官方向量是唯一能证明
     *   「我们的字节布局、AAD 拼接、KDF 参数与对方逐字节一致」的证据。
     *   这两个文件同时带有官方公布的派生密钥与期望明文，可交叉核对。
     */
    private fun officialVector(name: String): ByteArray? {
        return try {
            val f = java.io.File("src/test/resources/aex/" + name)
            if (!f.isFile) null else f.readBytes()
        } catch (t: Throwable) {
            null
        }
    }

    /** V01：官方单配置向量，字段齐全。 */
    @Test
    fun `官方向量 single 可解密且字段正确`() {
        val bytes = officialVector("single.akcp")
        if (bytes == null) {
            // 资源缺失不当作失败（CI 上可能没有同步大文件），但要让日志看得见。
            println("跳过：src/test/resources/aex/single.akcp 不存在")
            return
        }
        val r = AexImport.parse(bytes, credential, "single.akcp")
        assertTrue("实际：" + r.code() + " / " + r.message(), r.ok())
        assertEquals(1, r.profiles.size)
        val p = r.profiles[0]
        assertEquals("vector-single", p.id)
        assertEquals("Synthetic Vector", p.name)
        assertEquals("custom", p.provider)
        assertEquals("https://example.invalid/v1", p.baseUrl)
        assertEquals("sk-EXAMPLE-NOT-REAL", p.apiKey)
        // models 顺序必须与文件一致
        assertEquals(listOf("model-a", "model-b"), p.models)
        assertEquals("model-a", p.defaultModel)
        assertEquals("synthetic test vector", p.description)
    }

    /** V03：官方三配置向量，验证顺序与「缺省字段」语义。 */
    @Test
    fun `官方向量 multiple 可解密且顺序与缺省语义正确`() {
        val bytes = officialVector("multiple.akcp")
        if (bytes == null) {
            println("跳过：src/test/resources/aex/multiple.akcp 不存在")
            return
        }
        val r = AexImport.parse(bytes, credential, "multiple.akcp")
        assertTrue("实际：" + r.code() + " / " + r.message(), r.ok())
        assertEquals(3, r.profiles.size)
        // 顺序：Alpha → Beta → Gamma
        assertEquals(listOf("Alpha", "Beta", "Gamma"), r.profiles.map { it.name })
        // Beta 的 models 顺序必须保留
        assertEquals(listOf("m2", "m3"), r.profiles[1].models)
        // Gamma 只有 id/name/provider：baseUrl / apiKey 必须被识别为「不存在」而非「空串」
        val gamma = r.profiles[2]
        assertFalse("Gamma 不该有 baseUrl", gamma.hasBaseUrl)
        assertFalse("Gamma 不该有 apiKey", gamma.hasApiKey)
        // Alpha 则有 baseUrl
        assertTrue("Alpha 应有 baseUrl", r.profiles[0].hasBaseUrl)
    }

    // ==================== extra 命名空间约束 ====================

    /** extra 使用合规的反域名键 → 可读。 */
    @Test
    fun `extra 合规命名空间键通过`() {
        val p = profile()
        p.put("extra", JSONObject().put("com.example.notes.pinned", true))
        val r = AexImport.parse(build(payload(p)), credential, "x.akcp")
        assertTrue("实际：" + r.code(), r.ok())
        assertEquals(1, r.profiles.size)
        assertTrue(r.profiles[0].hasExtra())
    }

    /** extra 使用裸键（无命名空间）→ 违反 Schema 的 propertyNames，必须拒绝该条。 */
    @Test
    fun `extra 裸键被拒绝`() {
        val p = profile()
        p.put("extra", JSONObject().put("retry", 3))
        val r = AexImport.parse(build(payload(p)), credential, "x.akcp")
        // 该条不合法被跳过 → profiles 为空 → INVALID_PAYLOAD
        assertEquals(AexImport.Reason.INVALID_PAYLOAD, r.reason)
    }

    /** 单段键 / 首尾点 / 连续点都属于不合规。 */
    @Test
    fun `extra 非法键形态被拒绝`() {
        for (bad in listOf("single", ".a.b", "a.b.", "a..b", "a b.c", "a/b.c")) {
            val p = profile()
            p.put("extra", JSONObject().put(bad, 1))
            val r = AexImport.parse(build(payload(p)), credential, "x.akcp")
            assertEquals("键「" + bad + "」应被拒绝", AexImport.Reason.INVALID_PAYLOAD, r.reason)
        }
    }
}