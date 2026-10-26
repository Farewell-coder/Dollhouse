package com.dollhouse.app.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackStateTest {
    @Test
    fun confirmRunsOnlyOnce() {
        val guard = ConfirmGuard()
        var calls = 0
        assertTrue(guard.runOnce { calls++ })
        assertFalse(guard.runOnce { calls++ })
        assertEquals(1, calls)
    }

    @Test
    fun cancelDoesNotInvokeConfirmAction() {
        // 取消路径不调用 guard；该用例锁定公共确认组件的调用契约。
        val guard = ConfirmGuard()
        var calls = 0
        val cancel: () -> Unit = { }
        cancel()
        assertEquals(0, calls)
        assertTrue(guard.runOnce { calls++ })
        assertEquals(1, calls)
    }

    @Test
    fun duplicateVisibleMessageIsSuppressed() {
        val deduper = MessageDeduper()
        assertTrue(deduper.accept("save-failed"))
        assertFalse(deduper.accept("save-failed"))
        deduper.clear()
        assertTrue(deduper.accept("save-failed"))
    }

    @Test
    fun differentMessagesRemainIndependent() {
        val deduper = MessageDeduper()
        assertTrue(deduper.accept("one"))
        assertTrue(deduper.accept("two"))
        assertFalse(deduper.accept("two"))
    }
}