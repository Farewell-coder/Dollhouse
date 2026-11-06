package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 安静时段：跨午夜、边界分钟、未设置都必须判对，否则会整天静音或半夜照吵。 */
class QuietHoursTest {

    @Test fun `跨午夜时段在深夜与清晨都命中`() {
        val start = 22 * 60
        val end = 7 * 60
        assertTrue(QuietHours.inQuiet(23 * 60, start, end))
        assertTrue(QuietHours.inQuiet(0, start, end))
        assertTrue(QuietHours.inQuiet(6 * 60 + 59, start, end))
        assertFalse(QuietHours.inQuiet(7 * 60, start, end))
        assertFalse(QuietHours.inQuiet(12 * 60, start, end))
    }

    @Test fun `普通时段按区间判定`() {
        val start = 13 * 60
        val end = 14 * 60
        assertTrue(QuietHours.inQuiet(13 * 60, start, end))
        assertFalse(QuietHours.inQuiet(12 * 60 + 59, start, end))
        assertFalse(QuietHours.inQuiet(14 * 60, start, end))
    }

    @Test fun `未启用或起止相同时不静音`() {
        assertFalse(QuietHours.inQuiet(23 * 60, 22 * 60, 7 * 60, enabled = false))
        assertFalse(QuietHours.inQuiet(23 * 60, 22 * 60, 22 * 60))
    }

    @Test fun `时间解析与格式化互为逆运算`() {
        assertEquals(22 * 60 + 30, QuietHours.parse("22:30"))
        assertEquals("07:05", QuietHours.format(7 * 60 + 5))
        assertNull(QuietHours.parse("25:00"))
        assertNull(QuietHours.parse("12:99"))
        assertNull(QuietHours.parse("abc"))
        assertNull(QuietHours.parse(null))
    }

    @Test fun `描述能区分跨午夜`() {
        assertTrue(QuietHours.describe(22 * 60, 7 * 60).contains("次日"))
        assertFalse(QuietHours.describe(13 * 60, 14 * 60).contains("次日"))
        assertEquals("未设置", QuietHours.describe(60, 60))
    }
}