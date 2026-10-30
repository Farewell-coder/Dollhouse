package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【ZIP 路径安全测试】（v2.1·21.3.2 / 21.3.7）
 *
 * Zip Slip 是「写出去就完蛋」的一类问题：一个条目名 `../../databases/app_database`
 * 就能覆盖应用的数据库。这里把判定写成纯函数并逐条断言，
 * 而不是靠「导出时顺便试一下」—— 后者永远只能覆盖到想到的那几种写法。
 */
class ImportSourceTest {

    @Test
    fun `normal relative paths are accepted`() {
        assertEquals("idle/001.png", ImportSource.safeRelName("idle/001.png"))
        assertEquals("a/b/c.png", ImportSource.safeRelName("a/b/c.png"))
        // 反斜杠归一化成 `/`。
        assertEquals("idle/001.png", ImportSource.safeRelName("idle\\001.png"))
    }

    @Test
    fun `leading slash is rejected`() {
        assertNull(ImportSource.safeRelName("/etc/passwd"))
        assertNull(ImportSource.safeRelName("/data/local/tmp/x"))
    }

    @Test
    fun `parent traversal is rejected`() {
        assertNull(ImportSource.safeRelName("../x.png"))
        assertNull(ImportSource.safeRelName("a/../../x.png"))
        assertNull(ImportSource.safeRelName("a/.."))
        assertNull(ImportSource.safeRelName("..\\..\\windows\\x.png"))
    }

    @Test
    fun `drive letters are rejected`() {
        assertNull(ImportSource.safeRelName("C:/x.png"))
        assertNull(ImportSource.safeRelName("c:\\x.png"))
    }

    @Test
    fun `control characters are rejected`() {
        assertNull(ImportSource.safeRelName("a/\u0000.png"))
        assertNull(ImportSource.safeRelName("a/\u0007.png"))
    }

    @Test
    fun `dot segments collapse`() {
        assertEquals("a/b.png", ImportSource.safeRelName("./a/./b.png"))
        assertEquals("a/b.png", ImportSource.safeRelName("a//b.png"))
    }

    @Test
    fun `empty names are rejected`() {
        assertNull(ImportSource.safeRelName(""))
        assertNull(ImportSource.safeRelName("   "))
        assertNull(ImportSource.safeRelName("."))
        assertNull(ImportSource.safeRelName("./"))
    }

    @Test
    fun `limit constants match the spec`() {
        // 21.3.1：≤64 MiB、≤512 条目。这两个数字是验收项，不能被随手改掉。
        assertEquals(64L * 1024L * 1024L, ImportSource.MAX_TOTAL_BYTES)
        assertEquals(512, ImportSource.MAX_ENTRIES)
    }

    @Test
    fun `every failure has user facing text`() {
        for (f in ImportSource.Failure.values()) {
            val t = ImportSource.textOf(f)
            assertTrue("失败类型 " + f.name + " 没有文案", t.isNotBlank())
            // 23.3.3：不能只给异常类名 / 路径 / 内部 key。
            assertFalse(t.contains("Exception"))
            assertFalse(t.contains("/data/"))
        }
    }

    @Test
    fun `failure texts are distinct`() {
        // 21.3.9 要求不同原因给不同提示，不能都显示成同一条泛化错误。
        val texts = ImportSource.Failure.values().map { ImportSource.textOf(it) }
        assertEquals(texts.size, texts.distinct().size)
    }

    @Test
    fun `safe name keeps chinese and spaces`() {
        val n = ImportSource.safeRelName("小肥鱼/待机 动作/001.png")
        assertNotNull(n)
        assertEquals("小肥鱼/待机 动作/001.png", n)
    }
}