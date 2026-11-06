package com.dollhouse.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 完整备份信封的判定、版本与概览口径。 */
class BackupEnvelopeTest {

    private fun build(verOverride: Int? = null): JSONObject {
        val root = BackupEnvelope.build(
            exportedAt = 1L,
            appVersion = "0.2.6",
            providers = JSONArray().put(JSONObject().put("name", "A")),
            models = JSONArray().put(JSONObject().put("displayName", "m1")).put(JSONObject().put("displayName", "m2")),
            dolls = JSONArray().put(JSONObject().put("id", "d1")),
            memories = JSONObject().put("d1", JSONArray().put(JSONObject().put("title", "t"))),
            settings = JSONObject().put("theme", "SYSTEM")
        )
        if (verOverride != null) {
            root.put("ver", verOverride)
        }
        return root
    }

    private fun bytes(o: JSONObject): ByteArray = o.toString().toByteArray(Charsets.UTF_8)

    @Test fun `正常备份可识别并解析`() {
        val p = BackupEnvelope.parse(bytes(build()))
        assertTrue(p.message(), p.ok())
        assertEquals("dollhouse.backup", p.root!!.optString("kind"))
    }

    @Test fun `非备份文件被拒绝`() {
        val other = """{"kind":"dollhouse.providers","ver":1}"""
        assertFalse(BackupEnvelope.looksLike(other.toByteArray()))
        assertEquals(BackupEnvelope.Reason.NOT_BACKUP, BackupEnvelope.parse(other.toByteArray()).reason)
    }

    @Test fun `损坏 JSON 被拒绝`() {
        val bad = "{\"kind\":\"dollhouse.backup\"".toByteArray()
        assertEquals(BackupEnvelope.Reason.BAD_JSON, BackupEnvelope.parse(bad).reason)
    }

    @Test fun `更高版本备份被拒绝`() {
        val p = BackupEnvelope.parse(bytes(build(verOverride = 2)))
        assertEquals(BackupEnvelope.Reason.UNSUPPORTED_VERSION, p.reason)
    }

    @Test fun `缺少 sections 被拒绝`() {
        val root = JSONObject().put("kind", "dollhouse.backup").put("ver", 1)
        assertEquals(BackupEnvelope.Reason.MISSING_SECTIONS, BackupEnvelope.parse(bytes(root)).reason)
    }

    @Test fun `概览如实统计且提示密钥与图片不含`() {
        val lines = BackupEnvelope.summary(build())
        assertTrue(lines.any { it.contains("供应商 1 个") })
        assertTrue(lines.any { it.contains("模型 2 个") })
        assertTrue(lines.any { it.contains("记忆 1 条") })
        assertTrue(lines.any { it.contains("需要重新填写") })
        assertTrue(lines.any { it.contains("图片不包含") })
    }

    @Test fun `缺失数据区按空处理而不崩溃`() {
        val root = build()
        root.put("sections", JSONObject())
        assertEquals(0, BackupEnvelope.sectionArray(root, "providers").length())
        assertEquals(0, BackupEnvelope.sectionObject(root, "memories").length())
    }
}