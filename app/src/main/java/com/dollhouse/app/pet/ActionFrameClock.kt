package com.dollhouse.app.pet

import com.dollhouse.app.data.DollAnim

/**
 * 【职责】动作帧序列的**纯时间推进器** —— 只回答「现在该显示第几帧」，不碰位图、不碰 Canvas。
 *
 * ## 为什么从 [ActionPlayerCore] 里抽出来
 *
 * 原来这段逻辑内嵌在 `tick()` 里，判据是「`nowMs - lastStepMs >= step` 就 `lastStepMs = nowMs`
 * 然后 `frameIdx++`」。它有两个只有长跑才暴露的缺陷：
 *
 * 1. **单向量化误差**。帧循环按 16ms 排队，而帧间隔是 `1000/fps`：
 *    fps=12 → step=83ms，实际执行时刻落在 80/96/96/80… 这种网格上。
 *    每帧都 `lastStepMs = nowMs`，于是每帧都顺手丢掉 0~16ms 的余数 —— 误差**永远朝一个方向**累积。
 *    实测一段 2 秒的 12fps 循环动作会整体慢接近 20%，肉眼就是「这个动作有点拖」。
 * 2. **掉一帧就永久慢一帧**。`frameIdx++` 是相对推进：系统忙一次跳过两次 tick，
 *    也只会 +1，动画从此追不回来。
 *
 * 现在改成**绝对基准 + 整除**：用「从起表到现在过了多少个 step」直接算目标帧，
 * 余数留在基准里不丢。误差不再累积，掉帧也能自动追上。
 *
 * ## 为什么单独成类而不是留在原处
 *
 * 这段逻辑是纯算术，抽出来就能在 JVM 上单测（[ActionFrameClockTest]）。
 * 留在 `ActionPlayerCore` 里的话，测它要先造 Bitmap + Canvas，等于测不了 ——
 * 而「动画节奏」恰恰是最该被钉死的东西之一。
 *
 * @param fpsProvider 取当前帧率；每次调用即读，支持运行中调速。
 * @param loopProvider 取是否循环。
 */
class ActionFrameClock(
    private val fpsProvider: () -> Int,
    private val loopProvider: () -> Boolean
) {
    /** 当前显示帧号（0 起）。 */
    var frame: Int = 0
        private set

    /** 时间基准：已经「消费掉」的时间量，余数留在这里不清零。 */
    private var baseMs: Long = 0L

    /** 已推进的帧数（不取模，循环时也一直累加 —— 取模只在算 [frame] 时做）。 */
    private var cursor: Long = 0L

    /** 是否已经起表。第一次 [advance] 只建立基准、不推进（否则首个间隔会被算成 0）。 */
    private var started: Boolean = false

    /** 重置到第一帧并重新起表（换动作 / 帧图就绪 / 暂停恢复时调用）。 */
    fun reset() {
        frame = 0
        baseMs = 0L
        cursor = 0L
        started = false
    }

    /** 只清时间基准、保留当前帧号（暂停后再续时用）。 */
    fun rebase() {
        baseMs = 0L
        started = false
    }

    /** 跳到指定帧（手动步进时用）。 */
    fun seek(index: Int, count: Int) {
        if (count <= 0) {
            frame = 0
            cursor = 0L
            return
        }
        val i = Math.max(0, Math.min(count - 1, index))
        frame = i
        cursor = i.toLong()
        baseMs = 0L
        started = false
    }

    /**
     * 推进到 [nowMs] 对应的帧。
     *
     * @param nowMs 单调时钟（`SystemClock.uptimeMillis()`）
     * @param count 帧总数
     * @return `true` 表示帧号变了、调用方需要重绘
     */
    fun advance(nowMs: Long, count: Int): Boolean {
        if (count <= 1) {
            return false
        }
        if (!started) {
            baseMs = nowMs
            started = true
            return false
        }
        val step = DollAnim.frameMs(fpsProvider())
        val elapsed = nowMs - baseMs
        if (elapsed < step) {
            return false
        }
        val advanced = elapsed / step
        if (advanced <= 0L) {
            return false
        }
        // 只消费掉「整数帧」的时间，余数留在基准里 —— 这是不累积误差的关键。
        baseMs += advanced * step
        cursor += advanced
        val loop = loopProvider()
        val want = if (loop) {
            (cursor % count).toInt()
        } else {
            Math.min(cursor, (count - 1).toLong()).toInt()
        }
        if (want == frame) {
            return false
        }
        frame = want
        return true
    }

    /** 非循环动作是否已停在末帧（调用方据此判定播放结束）。 */
    fun isAtEnd(count: Int): Boolean {
        return !loopProvider() && count > 0 && frame >= count - 1
    }
}