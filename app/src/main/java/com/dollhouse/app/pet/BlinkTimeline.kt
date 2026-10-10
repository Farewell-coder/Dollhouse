package com.dollhouse.app.pet

/**
 * 【职责】眨眼时间线的纯逻辑：闭合 → 保持 → 睁开。
 *
 * 【为什么单独成类】原实现把「闭合 140ms、闭眼当作正弦钟形」写死在 PetAnimator.tick 里，
 *   既无法单测，也表达不出「缓慢闭上 / 停一下 / 再睁开」的节奏。这里抽成无状态纯函数：
 *   给定「自眨眼开始起的毫秒数」，返回绘制用的相位；调用方只管计时，逻辑全部可测。
 *
 * 【相位约定】沿用绘制层的语义（mesh 用 sin(相位·π) 驱动眼高压缩）：
 *   0.0 = 完全睁眼，0.5 = 完全闭合（sin 峰值），1.0 = 重新睁开。
 *   因此「闭合段」= 相位从 0 升到 0.5 的那一段。
 */
object BlinkTimeline {
    /**
     * 闭合耗时（毫秒）。
     * 【20261008 纠正】200~240ms 指的是「整轮眨眼」，不是「闭合段」；原先误把 220 当闭合段，
     *   叠加 HOLD=80 + OPEN=120 后整轮 420ms，观感明显偏慢。现按整轮约 220ms 重排：70 + 50 + 100。
     */
    const val CLOSE_MS = 70f

    /** 完全闭合的保持时间（毫秒）。 */
    const val HOLD_MS = 50f

    /** 睁开耗时（毫秒）。 */
    const val OPEN_MS = 100f

    /** 一轮眨眼总时长（毫秒）。 */
    const val TOTAL_MS = CLOSE_MS + HOLD_MS + OPEN_MS

    /**
     * 返回自眨眼开始起第 [elapsedMs] 毫秒时的绘制相位。
     *
     * @return 0.0~1.0 的相位；[elapsedMs] 小于 0 或已到 / 超过 [TOTAL_MS] 时返回 -1（未在眨眼）。
     */
    fun phaseAt(elapsedMs: Float): Float {
        if (elapsedMs < 0f || elapsedMs >= TOTAL_MS) {
            return -1f
        }
        if (elapsedMs <= 0f) {
            return 0f
        }
        if (elapsedMs < CLOSE_MS) {
            return 0.5f * (elapsedMs / CLOSE_MS)
        }
        if (elapsedMs < CLOSE_MS + HOLD_MS) {
            return 0.5f
        }
        val open = (elapsedMs - CLOSE_MS - HOLD_MS) / OPEN_MS
        return 0.5f + 0.5f * Math.min(1f, Math.max(0f, open))
    }
}
