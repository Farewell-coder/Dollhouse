package com.dollhouse.app.pet

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 单个消费者的「只保留最新请求」后台解码器。
 *
 * 每个实例只有一个工作线程：连续切换动作时，排队中的旧请求不会开始解码；
 * 正在执行的旧请求会在每一帧之间失效，并立即释放已经得到的帧。
 * 调用方仍需在 UI 回调中复核自己的代次，因为回调可能已在线程切换前排入 UI 队列。
 *
 * T 的所有权：成功回调交付给调用方；失效/关闭时由本类调用 dispose。
 */
internal class SerialLatestDecoder<T>(
    private val decode: (String) -> T?,
    private val dispose: (T) -> Unit,
    private val onDecoded: (Int, List<T>) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var closed = false

    private val executor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()
    ) { task ->
        Thread(task, "dh-action-decode").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    @Volatile private var worker: Worker? = null

    /** 中断必须与具体任务绑定，不能落到复用线程正在执行的下一任务上。 */
    private class Worker(val thread: Thread, val gen: Int)

    /** 返回本次代次。旧任务不会再产生有效结果。 */
    fun submit(paths: List<String>): Int {
        val gen = generation.incrementAndGet()
        if (closed) return gen
        val snapshot = ArrayList(paths)
        executor.execute {
            val currentWorker = Worker(Thread.currentThread(), gen)
            worker = currentWorker
            val out = ArrayList<T>(snapshot.size)
            try {
                for (path in snapshot) {
                    if (!isCurrent(gen)) break
                    val value = try {
                        decode(path)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (t: Throwable) {
                        // 单帧损坏与旧行为一致：跳过该帧，不报废整组动作。
                        try { onError(t) } catch (_: Throwable) {}
                        null
                    }
                    if (value != null) {
                        // decode 可能吞掉中断或在等待期间已被 close；返回后必须再次校验。
                        if (isCurrent(gen)) out.add(value) else dispose(value)
                    }
                    if (!isCurrent(gen)) break
                }
                if (isCurrent(gen)) {
                    // 失效后所有权仍在本类，不能在 post 之后才回收。
                    onDecoded(gen, out)
                } else {
                    disposeAll(out)
                }
            } catch (t: Throwable) {
                // 解码器的实现异常不能遗留已解码帧；错误回调不能再次破坏工作线程。
                disposeAll(out)
                try { onError(t) } catch (_: Throwable) {}
            } finally {
                // 每个任务清除自己的中断标志，不能把上一动作的取消带到下一动作。
                Thread.interrupted()
                if (worker === currentWorker) worker = null
            }
        }
        return gen
    }

    /** 停止当前/待处理工作，但实例仍可提交下一组。 */
    fun invalidate() {
        if (closed) return
        generation.incrementAndGet()
        executor.queue.clear()
        worker?.takeIf { generation.get() == it.gen }?.thread?.interrupt()
    }

    /** 使所有未交付结果失效；已交付结果由调用方释放。幂等。 */
    fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun isCurrent(gen: Int): Boolean =
        !closed && generation.get() == gen && !Thread.currentThread().isInterrupted

    private fun disposeAll(items: List<T>) {
        for (item in items) dispose(item)
    }
}
