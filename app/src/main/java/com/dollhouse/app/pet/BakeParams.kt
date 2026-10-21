package com.dollhouse.app.pet

/**
 * 【职责】烘帧参数数组（12 元 `FloatArray`）的**唯一下标真源**与「静立态」定义。
 *
 * ## 为什么必须收口
 *
 * 这个数组的每一位都直接影响烘出来的帧：
 *
 * | 下标 | 含义 | 中性值 | 留 0 会怎样 |
 * |---|---|---|---|
 * | 0 | phase（呼吸相位） | 0 | 呼吸在某个相位，可接受 |
 * | 1 | jumpT | **-1** | 0 = 「刚起跳的那一瞬」，人偶会抬高 |
 * | 2 | dragLift | 0 | — |
 * | 3 | idleLiftPx | 0 | — |
 * | 4 | idleScaleY | **1** | 0 → 纵向压成一条线 |
 * | 5 | landT | **-1** | 0 = 「刚落地的那一瞬」|
 * | 6 | lean | 0 | — |
 * | 7 | tilt | 0 | — |
 * | 8 | 保留 | 0 | — |
 * | 9 | blinkT | 0 | `sin(0·π)=0`，不会眨眼，安全 |
 * | 10 | idleEyeScale | **1** | 0 → `min(base,0)=0` → **眼睛被压扁到极值** |
 * | 11 | gaze | 0 | 正视，安全 |
 *
 * 改造过程中确实踩过：`timeline()` 补了 `v[4] = 1f` 却漏了 `v[10]`，
 * 于是**每一帧的眼睛都是扁的**；算本体基准时同样只设了 1/5，量的框也跟着偏。
 * 两次都是「记得其中一位、漏了同性质另一位」—— 靠注释提醒防不住，
 * 所以这里把定义集中成 [stand] 与 [DEFAULTS]，调用方只做增量覆盖。
 *
 * 低位下标（0..7）同时被 [com.dollhouse.app.pet.render.PetTransform.Params.ofArray] 读取，
 * 两边的口径必须一致；[com.dollhouse.app.pet.render.PetTransformTest] 里有对应的守门断言。
 */
object BakeParams {
    const val IDX_PHASE = 0
    const val IDX_JUMP = 1
    const val IDX_DRAG_LIFT = 2
    const val IDX_IDLE_LIFT = 3
    const val IDX_IDLE_SCALE_Y = 4
    const val IDX_LAND = 5
    const val IDX_LEAN = 6
    const val IDX_TILT = 7
    /** 保留位，当前无消费者。 */
    const val IDX_RESERVED = 8
    const val IDX_BLINK = 9
    const val IDX_EYE_SCALE = 10
    const val IDX_GAZE = 11

    /** 数组长度。 */
    const val SIZE = 12

    /**
     * 全中性值数组 —— **不要把它当成「静立态」用**，静立态请用 [stand]。
     *
     * 这里的定义是「每一位都取该位语义上的中性值」：jumpT/landT 取 -1、
     * idleScaleY/idleEyeScale 取 1，其余 0。因为对多数位来说 0 恰好不是中性的。
     */
    fun defaults(): FloatArray {
        val v = FloatArray(SIZE)
        v[IDX_JUMP] = -1f
        v[IDX_LAND] = -1f
        v[IDX_IDLE_SCALE_Y] = 1f
        v[IDX_EYE_SCALE] = 1f
        return v
    }

    /**
     * 静立态参数：没有跳跃 / 落地 / 拖动抬升 / 倾斜 / 形变 / 视线偏移。
     *
     * 【谁在用】烘帧时量「人偶本体基准矩形」（`AnimBaker`）—— 所有动作共用这一把尺子，
     * 才可能做到「不同动作里人偶一样大」。
     *
     * @param phase 呼吸相位。默认 0；需要量「呼吸最鼓/最瘪」时显式传入。
     */
    fun stand(phase: Float = 0f): FloatArray {
        val v = defaults()
        v[IDX_PHASE] = phase
        return v
    }

    /** 把 [target] 全部清成 [defaults] 的中性值（逐帧生成时间线时的起手式）。 */
    fun reset(target: FloatArray) {
        val d = defaults()
        for (k in 0 until SIZE) {
            target[k] = if (k < d.size) d[k] else 0f
        }
    }
}