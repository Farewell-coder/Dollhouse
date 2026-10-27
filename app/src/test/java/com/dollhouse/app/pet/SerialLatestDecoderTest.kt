package com.dollhouse.app.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SerialLatestDecoderTest {
    @Test
    fun rapidReplacement_disposesObsoleteWorkWithoutWaitingForWholeOldGroup() {
        val started = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val disposed = Collections.synchronizedList(mutableListOf<String>())
        val delivered = Collections.synchronizedList(mutableListOf<Int>())
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        val queue = SerialLatestDecoder<String>(
            decode = { path ->
                threads += Thread.currentThread().name
                if (path == "old-0") {
                    started.countDown()
                    releaseOld.await(3, TimeUnit.SECONDS)
                }
                path
            },
            dispose = { disposed += it },
            onDecoded = { gen, values ->
                if (gen == 2) delivered += values.size
            }
        )
        queue.submit(List(100) { "old-$it" })
        assertTrue(started.await(3, TimeUnit.SECONDS))
        queue.submit(listOf("new"))
        releaseOld.countDown()
        waitUntil { delivered.isNotEmpty() }
        assertEquals(listOf(1), delivered.toList())
        assertTrue(disposed.contains("old-0"))
        assertEquals(1, threads.size)
        queue.close()
    }

    @Test
    fun close_cancelsQueuedAndReleasesInFlightResults() {
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val decoded = AtomicInteger()
        val disposed = AtomicInteger()
        val queue = SerialLatestDecoder<Int>(
            decode = { path: String ->
                if (path == "1") {
                    started.countDown()
                    unblock.await(3, TimeUnit.SECONDS)
                }
                decoded.incrementAndGet()
                path.toIntOrNull()
            },
            dispose = { disposed.incrementAndGet() },
            // 模拟生产端：接收结果后由接收方负责释放（本测试不需要消费它）。
            onDecoded = { _, values -> repeat(values.size) { disposed.incrementAndGet() } }
        )
        queue.submit(List(30) { it.toString() })
        assertTrue(started.await(3, TimeUnit.SECONDS))
        repeat(5) { queue.submit(List(30) { it.toString() }) }
        queue.close()
        queue.close()
        unblock.countDown()
        waitUntil { disposed.get() > 0 }
        assertTrue(disposed.get() > 0)
        assertTrue(decoded.get() <= 2)
    }

    private fun waitUntil(block: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end) {
            if (block()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition not met before timeout")
    }
}