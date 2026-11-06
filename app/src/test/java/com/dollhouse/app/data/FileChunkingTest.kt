package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长文本分段：不能丢内容、不能切出过短段、必须在超限时如实告知。 */
class FileChunkingTest {

    @Test fun `短文本不切分`() {
        val p = FileChunking.plan("a.txt", "很短的一句话")
        assertEquals(1, p.chunks.size)
        assertFalse(p.truncated)
        assertTrue(p.chunks[0].contains("第 1/1 段"))
    }

    @Test fun `空内容得到空计划`() {
        assertTrue(FileChunking.plan("a.txt", "").isEmpty())
        assertTrue(FileChunking.plan("a.txt", null).isEmpty())
        assertTrue(FileChunking.plan("a.txt", "   \n ").isEmpty())
    }

    @Test fun `长文本按行切分且拼接后内容不丢`() {
        val line = "这是一行内容。".repeat(10)
        val text = (1..50).joinToString("\n") { line }
        val p = FileChunking.plan("b.txt", text)
        assertTrue(p.chunks.size > 1)
        // 去掉段头后，拼起来应与原文一致（允许尾部因超段数被截）
        val rebuilt = p.chunks.joinToString("") { it.substringAfter("\n") }
        assertTrue(text.startsWith(rebuilt))
    }

    @Test fun `每段不超过上限`() {
        val text = "啊".repeat(FileChunking.LIMIT * 3 + 17)
        for (c in FileChunking.split(text, FileChunking.LIMIT)) {
            assertTrue("段长 " + c.length, c.length <= FileChunking.LIMIT)
        }
    }

    @Test fun `超过最多段数时明确标记截断`() {
        val text = "内容。".repeat(FileChunking.LIMIT)
        val p = FileChunking.plan("c.txt", text)
        assertTrue(p.truncated)
        assertEquals(FileChunking.MAX_CHUNKS, p.chunks.size)
        assertTrue(FileChunking.describe(p).contains("未纳入"))
    }

    @Test fun `没有自然边界时硬切不丢字符`() {
        val text = "x".repeat(10000)
        val parts = FileChunking.split(text, 4000)
        assertEquals(text, parts.joinToString(""))
    }
}