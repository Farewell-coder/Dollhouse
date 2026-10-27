package com.dollhouse.app.core

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsFormatTest {
    @Test fun credentialsAreRedacted() {
        val out = DiagnosticsFormat.redact("apiKey=secret123 Authorization: Bearer abc.def password = hunter2")
        assertFalse(out.contains("secret123")); assertFalse(out.contains("abc.def")); assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("[已过滤]"))
    }
    @Test fun ordinaryErrorTextSurvives() {
        assertEquals("动作导入失败：格式不支持", DiagnosticsFormat.redact("动作导入失败：格式不支持"))
    }
    @Test fun groupIdentityIncludesSeverityAndCause() {
        assertNotEquals(DiagnosticsFormat.groupKey("error", "Import", "IOException", "bad"),
            DiagnosticsFormat.groupKey("warning", "Import", "IOException", "bad"))
        assertEquals(DiagnosticsFormat.groupKey("error", "Import", "IOException", "bad"),
            DiagnosticsFormat.groupKey("error", "Import", "IOException", "bad"))
    }
    @Test fun largeTextIsBoundedWithoutChangingShortText() {
        assertEquals(100, DiagnosticsFormat.boundedText("x".repeat(500), 100).length)
        assertEquals("abc", DiagnosticsFormat.boundedText("abc", 100))
    }
}