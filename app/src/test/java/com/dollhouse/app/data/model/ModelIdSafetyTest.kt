package com.dollhouse.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【附录D-2 回归】模型包 id 的两道安全边界。
 *
 * 【为什么这组用例值得写】`manifest.id` 会被当目录名拼进 `filesDir/doll/model/<dollId>/<id>`，
 *   而 `remove()` 对它是**递归删除**。一旦 id 能解析成 `..`，那就是「删掉整棵模型树」——
 *   所有角色的模型一起没。这条链路只有单测能低成本钉住，靠真机试代价太大（真删数据）。
 *
 * 覆盖两层：
 *  1. [ModelValidator.isSafeModelId] —— 导入前的准入判定（不符合就拒绝整个包）；
 *  2. [ModelRepository.safeName] —— 落盘时的兜底改名（尽量抢救成合法目录名）。
 */
class ModelIdSafetyTest {

    /* ============ 第一层：导入准入 ============ */

    @Test
    fun `正常 id 被接受`() {
        assertTrue(ModelValidator.isSafeModelId("cat"))
        assertTrue(ModelValidator.isSafeModelId("cat-v2"))
        assertTrue(ModelValidator.isSafeModelId("cat_v2"))
        assertTrue(ModelValidator.isSafeModelId("cat.v2"))
        assertTrue(ModelValidator.isSafeModelId("A1"))
    }

    @Test
    fun `穿越形态一律拒绝`() {
        // 报告里点名的两种，以及与之等价的变体。
        assertFalse(ModelValidator.isSafeModelId(".."))
        assertFalse(ModelValidator.isSafeModelId("../.."))
        assertFalse(ModelValidator.isSafeModelId("."))
        assertFalse(ModelValidator.isSafeModelId("..."))
        assertFalse(ModelValidator.isSafeModelId(".hidden"))
        assertFalse(ModelValidator.isSafeModelId("a/../.."))
        assertFalse(ModelValidator.isSafeModelId("a/b"))
        assertFalse(ModelValidator.isSafeModelId("a\\b"))
    }

    @Test
    fun `空与超长被拒绝`() {
        assertFalse(ModelValidator.isSafeModelId(null))
        assertFalse(ModelValidator.isSafeModelId(""))
        // 64 个字符是上限，65 就超了。
        assertTrue(ModelValidator.isSafeModelId("a".repeat(64)))
        assertFalse(ModelValidator.isSafeModelId("a".repeat(65)))
    }

    @Test
    fun `非法字符被拒绝`() {
        assertFalse(ModelValidator.isSafeModelId("a b"))
        assertFalse(ModelValidator.isSafeModelId("a:b"))
        assertFalse(ModelValidator.isSafeModelId("a*b"))
        assertFalse(ModelValidator.isSafeModelId("a%2e"))
    }

    /* ============ 第二层：落盘兜底 ============ */

    @Test
    fun `兜底改名不会产生穿越目录名`() {
        // safeName 的契约：无论输入什么，输出都不能是 `.`、`..`，也不能含分隔符。
        val dangerous = listOf(
            "..", "../..", ".", "...", ". . .",
            "a/../..", "../../../etc", "a\\..\\..", ""
        )
        for (id in dangerous) {
            val out = ModelRepository.safeName(id)
            assertTrue("safeName($id) 不能为空", out.isNotEmpty())
            assertTrue("safeName($id) 不能是 . 或 ..，实际 $out", out != "." && out != "..")
            assertFalse("safeName($id) 不能含斜杠，实际 $out", out.contains("/") || out.contains("\\"))
            // 剥掉前导点之后又可能产生 `..`，所以再确认一次不以点开头。
            assertFalse("safeName($id) 不能以点开头，实际 $out", out.startsWith("."))
        }
    }

    @Test
    fun `正常 id 的兜底改名保持不变`() {
        assertEquals("cat-v2", ModelRepository.safeName("cat-v2"))
        assertEquals("cat_v2", ModelRepository.safeName("cat_v2"))
        assertEquals("cat.v2", ModelRepository.safeName("cat.v2"))
        // 有非法字符时替换成下划线，这是「抢救」语义，不是穿越。
        assertEquals("a_b", ModelRepository.safeName("a/b"))
    }
}