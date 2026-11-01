package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】AKCP 1.0 的互操作与拒绝路径测试。
 *
 * 【为什么必须有这组测试】导入功能的价值全在「真的能解开 Aivora 导出的文件」。
 *   只靠自写加密 + 自写解密的自环测试毫无意义（两边一起错也是绿的）。
 *   所以这里直接吃 Aivora 官方仓库公开的两个测试向量：
 *     - python-export.aivora：由官方独立 Python 实现生成
 *     - kotlin-export.aivora：由官方 Kotlin 实现生成
 *   两个文件的凭据都是 52 个 A（公开的合成凭据，仅用于互操作验证）。
 *   只要这两个能解出预期字段，就证明本实现对协议的理解与官方一致。
 *
 * 【覆盖的拒绝路径】规范要求「认证失败 / 截断 / 版本不符 / 后缀不符」一律整体失败，
 *   所以正常路径之外，这些也必须有用例，否则将来一次「省事」的改动就能悄悄放行坏文件。
 */
class AkcpImportTest {

    /** 官方向量统一使用这条公开的合成凭据。 */
    private val vectorCredential = "A".repeat(52)

    private fun vector(name: String): ByteArray {
        val s = javaClass.classLoader!!.getResourceAsStream("akcp/" + name)
        assertNotNull("缺少测试向量：" + name, s)
        return s!!.use { it.readBytes() }
    }

    @Test
    fun decryptsOfficialPythonVector() {
        val r = AkcpImport.parse(vector("python-export.aivora"), vectorCredential, "python-export.aivora")
        assertTrue("解密应成功，实际：" + r.message(), r.ok())
        assertEquals(1, r.configs.size)
        val c = r.configs[0]
        assertEquals("Python interoperability", c.configName)
        assertEquals("Synthetic", c.provider)
        assertEquals("sk-not-a-real-key", c.apiKey)
        assertEquals("https://example.invalid/v1", c.endpoint)
        assertEquals(listOf("model-a"), c.models)
        assertEquals("model-a", c.defaultModel)
    }

    @Test
    fun decryptsOfficialKotlinVector() {
        val r = AkcpImport.parse(vector("kotlin-export.aivora"), vectorCredential, "kotlin-export.aivora")
        assertTrue("解密应成功，实际：" + r.message(), r.ok())
        assertEquals(1, r.configs.size)
        val c = r.configs[0]
        assertEquals("Kotlin interoperability", c.configName)
        assertEquals("Synthetic", c.provider)
        assertEquals("sk-not-a-real-key", c.apiKey)
        assertEquals("https://example.invalid/v1", c.endpoint)
        assertEquals(listOf("model-a"), c.models)
        assertEquals("model-a", c.defaultModel)
        // 官方 Kotlin 实现会把可选字段显式写出来，解析后应为默认值而非报错。
        assertTrue(c.enabled)
    }

    @Test
    fun credentialNormalizationIgnoresHyphensAndCase() {
        val spaced = "aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa"
        val mixed = "AaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAa"
        assertTrue(AkcpImport.credentialLooksValid(spaced))
        assertTrue(AkcpImport.credentialLooksValid(mixed))
        assertFalse(AkcpImport.credentialLooksValid("A".repeat(51)))
        // 数字 0/1 不在 Base32 字母表内，必须判为非法。
        assertFalse(AkcpImport.credentialLooksValid("0".repeat(52)))
        assertFalse(AkcpImport.credentialLooksValid("1".repeat(52)))
    }

    @Test
    fun wrongCredentialFailsWithoutOutput() {
        val r = AkcpImport.parse(
            vector("python-export.aivora"),
            "B".repeat(52),
            "python-export.aivora"
        )
        assertFalse(r.ok())
        assertEquals(AkcpImport.Reason.BAD_CREDENTIAL, r.reason)
        // 关键：失败时绝不能带回半截明文。
        assertTrue(r.configs.isEmpty())
    }

    @Test
    fun renamedFileIsRejected() {
        // 规范第 1 条：改名成 .json / .akcp 一律在入口处拒绝，与内容是否合法无关。
        val bytes = vector("python-export.aivora")
        // 【注意】规范只要求小写 .aivora；这里按此严格拒绝大写后缀，
        //   与官方 Kotlin 实现的 requireFilename（endsWith 且大小写敏感）口径一致。
        for (name in listOf("config.json", "config.akcp", "config", "config.AIVORA")) {
            val r = AkcpImport.parse(bytes, vectorCredential, name)
            assertEquals("后缀 " + name + " 应被拒绝", AkcpImport.Reason.BAD_EXTENSION, r.reason)
            assertTrue(r.configs.isEmpty())
        }
        assertTrue(AkcpImport.acceptsName("a.aivora"))
        assertFalse(AkcpImport.acceptsName(".aivora"))
    }

    @Test
    fun truncatedFileIsRejected() {
        val full = vector("python-export.aivora")
        val cut = full.copyOf(full.size - 1)
        val r = AkcpImport.parse(cut, vectorCredential, "python-export.aivora")
        assertEquals(AkcpImport.Reason.BAD_CONTAINER, r.reason)
        assertTrue(r.configs.isEmpty())
    }

    @Test
    fun tamperedHeaderIsRejected() {
        val full = vector("python-export.aivora")
        // 改一个 salt 字节：头部参与 AAD，必然认证失败。
        full[34] = (full[34].toInt() xor 0x01).toByte()
        val r = AkcpImport.parse(full, vectorCredential, "python-export.aivora")
        assertEquals(AkcpImport.Reason.BAD_CREDENTIAL, r.reason)
        assertTrue(r.configs.isEmpty())
    }

    @Test
    fun unsupportedVersionIsRejected() {
        val full = vector("python-export.aivora")
        // 协议版本落在 offset 8..9，改成 2 应被拒绝。
        full[9] = 2
        val r = AkcpImport.parse(full, vectorCredential, "python-export.aivora")
        assertEquals(AkcpImport.Reason.UNSUPPORTED_VERSION, r.reason)
    }

    @Test
    fun badMagicIsRejected() {
        val full = vector("python-export.aivora")
        full[0] = 'X'.code.toByte()
        val r = AkcpImport.parse(full, vectorCredential, "python-export.aivora")
        assertEquals(AkcpImport.Reason.BAD_CONTAINER, r.reason)
    }

    @Test
    fun tooSmallFileIsRejected() {
        val r = AkcpImport.parse(ByteArray(40), vectorCredential, "x.aivora")
        assertEquals(AkcpImport.Reason.BAD_CONTAINER, r.reason)
    }

    @Test
    fun maskKeepsEndsOnly() {
        assertEquals("", AkcpImport.maskKey(""))
        assertEquals("****", AkcpImport.maskKey("abcd"))
        // 9 位以上：保留首尾各 4 位，中间以省略号代替。
        assertEquals("sk-a…-key", AkcpImport.maskKey("sk-abcd-efgh-key"))
        // 关键：打码结果不能包含完整密钥。
        val full = "sk-1234567890abcdef"
        assertFalse(AkcpImport.maskKey(full).contains("1234567890"))
    }

    @Test
    fun nullCredentialFailsCleanly() {
        val r = AkcpImport.parse(vector("python-export.aivora"), null, "python-export.aivora")
        assertEquals(AkcpImport.Reason.BAD_CREDENTIAL, r.reason)
        assertTrue(r.configs.isEmpty())
    }
}