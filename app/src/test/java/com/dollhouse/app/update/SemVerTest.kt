package com.dollhouse.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[SemVer] 的单元测试（建议①：给纯逻辑补测试底座）。
 *
 * 【为什么挑它当第一个】SemVer 是**纯函数、零 Android 依赖**，能在 JVM 上直接跑，
 *   不需要 Robolectric 也不需要真机 —— 是工程里「加测试」的最低成本切入点。
 *   而它又恰好是更新检查的判定核心：判错会出现「有新版本不提示」或「反复提示同一个版本」
 *   这类用户直接可见的 bug，值得有回归护栏。
 *
 * 【用例来源】全部对着 SemVer 2.0.0 §11 的优先级规则写，外加工程实际遇到过的
 *   GitHub tag 形态（带 v 前缀、四段版本号）。
 */
class SemVerTest {

    // ---------------- normalize ----------------

    @Test
    fun normalize_stripsVPrefix() {
        assertEquals("0.0.7", SemVer.normalize("v0.0.7"))
        assertEquals("1.2.3", SemVer.normalize("V1.2.3"))
        assertEquals("1.2.3", SemVer.normalize("1.2.3"))
        assertEquals("1.2.3", SemVer.normalize("  v1.2.3  "))
    }

    @Test
    fun normalize_rejectsGarbage() {
        assertNull(SemVer.normalize(null))
        assertNull(SemVer.normalize(""))
        assertNull(SemVer.normalize("   "))
        assertNull(SemVer.normalize("v"))
        assertNull(SemVer.normalize("abc"))
        assertNull(SemVer.normalize("1.2.3.4.5"))   // 超过四段
        assertNull(SemVer.normalize("1..2"))
    }

    // ---------------- 数值段比较 ----------------

    @Test
    fun numericSegments_compareAsNumbers() {
        // 【关键用例】字符串比较会得出 1.9.0 > 1.10.0 的错误结论。
        assertTrue(SemVer.isNewer("1.10.0", "1.9.0"))
        assertTrue(SemVer.isNewer("0.0.10", "0.0.9"))
        assertTrue(SemVer.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun missingSegments_countAsZero() {
        assertEquals(0, SemVer.compare("1.0", "1.0.0"))
        assertEquals(0, SemVer.compare("1.0.0", "1"))
        assertTrue(SemVer.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun fourSegments_supported() {
        assertTrue(SemVer.isNewer("1.2.3.4", "1.2.3.3"))
        assertEquals(0, SemVer.compare("1.2.3", "1.2.3.0"))
    }

    @Test
    fun equalVersions_areEqual() {
        assertEquals(0, SemVer.compare("1.2.3", "1.2.3"))
        assertEquals(0, SemVer.compare("v1.2.3", "1.2.3"))
        assertFalse(SemVer.isNewer("1.2.3", "1.2.3"))
        assertFalse(SemVer.isNewer("1.2.3", "1.2.4"))
    }

    // ---------------- pre-release ----------------

    @Test
    fun preRelease_isLowerThanRelease() {
        // SemVer §11.3：1.0.0-alpha < 1.0.0
        assertTrue(SemVer.isNewer("1.0.0", "1.0.0-alpha"))
        assertTrue(SemVer.isNewer("1.0.0-rc.1", "1.0.0-alpha.1"))
        assertEquals(-1, SemVer.compare("1.0.0-beta", "1.0.0"))
    }

    @Test
    fun preRelease_numericLowerThanAlpha() {
        // §11.4.3：数字段 < 非数字段 → 1.0.0-1 < 1.0.0-alpha
        assertTrue(SemVer.isNewer("1.0.0-alpha", "1.0.0-1"))
        assertTrue(SemVer.isNewer("1.0.0-2", "1.0.0-1"))
    }

    @Test
    fun preRelease_moreSegmentsWin() {
        // §11.4.4：前缀相同则段数多者更大 → alpha < alpha.1
        assertTrue(SemVer.isNewer("1.0.0-alpha.1", "1.0.0-alpha"))
        assertTrue(SemVer.isNewer("1.0.0-rc.2", "1.0.0-rc.1"))
    }

    @Test
    fun buildMetadata_ignored() {
        // §11.2：构建元数据不参与比较。
        assertEquals(0, SemVer.compare("1.0.0+build1", "1.0.0+build2"))
        assertEquals(0, SemVer.compare("1.0.0+20130313", "1.0.0"))
        assertTrue(SemVer.isNewer("1.0.1+x", "1.0.0+y"))
    }

    // ---------------- 不可解析 ----------------

    @Test
    fun unparsable_yieldsNullAndNoUpdate() {
        // 【需求口径】不可判定时「不提示更新」，绝不当成相等或更大。
        assertNull(SemVer.compare("abc", "1.0.0"))
        assertNull(SemVer.compare("1.0.0", "not-a-version"))
        assertNull(SemVer.compare(null, null))
        assertFalse(SemVer.isNewer("abc", "1.0.0"))
        assertFalse(SemVer.isNewer("1.0.0", "abc"))
    }

    // ---------------- 工程真实版本线 ----------------

    @Test
    fun dollhouseVersionLine() {
        // 工程当前线：0.0.1 → 0.0.2 → … → 0.0.7，逐版可比较。
        assertTrue(SemVer.isNewer("v0.0.7", "v0.0.6"))
        assertTrue(SemVer.isNewer("v0.0.6", "v0.0.5"))
        assertTrue(SemVer.isNewer("0.0.7", "0.0.1"))
        assertEquals(0, SemVer.compare("v0.0.7", "0.0.7"))
    }
}