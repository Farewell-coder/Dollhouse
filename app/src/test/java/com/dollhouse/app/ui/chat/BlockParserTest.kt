package com.dollhouse.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class BlockParserTest {
    private val sample = ":::card{icon=\"archive\"}\n### .zip / .rar\n普通压缩包\n:::source{name=\"Microsoft\",count=1}\n:::"
    @Test fun completeCard() {
        val c = BlockParser.parse(sample).filterIsInstance<Block.Card>().single()
        assertEquals("archive", c.icon); assertEquals(".zip / .rar", c.title)
        assertEquals("普通压缩包", c.body); assertEquals(Source("Microsoft", 1), c.sources.single())
    }
    @Test fun streamingDoesNotCrashAndCompletes() {
        for (n in 0..sample.length) BlockParser.parse(sample.substring(0, n))
        assertEquals(BlockParser.parse(sample), BlockParser.parse(sample))
    }
    @Test fun sourceWithoutCountAndUnknownFields() {
        val c = BlockParser.parse(sample.replace("icon=\"archive\"", "icon=\"unknown\" extra=1").replace(",count=1", "")).filterIsInstance<Block.Card>().single()
        assertEquals("unknown", c.icon); assertEquals(0, c.sources.single().count)
    }
    @Test fun fencedMarkersRemainCode() {
        val b = BlockParser.parse("```text\n$sample\n```")
        assertTrue(b.single() is Block.CodeBlock); assertTrue((b.single() as Block.CodeBlock).code.contains(":::card"))
    }
    @Test fun plainMarkdownIsNotLost() {
        val b = BlockParser.parse("### 标题\n正文")
        assertTrue(b[0] is Block.Heading); assertEquals("正文", (b[1] as Block.Paragraph).text)
    }
    @Test fun outsideSourceIsPlainText() {
        assertTrue(BlockParser.parse(":::source{name=\"x\"}").single() is Block.Paragraph)
    }
}