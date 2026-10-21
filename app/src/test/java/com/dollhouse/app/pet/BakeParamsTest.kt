package com.dollhouse.app.pet

import com.dollhouse.app.pet.render.PetTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】把「烘帧参数数组的每一位」钉死，防「补了一位、漏了同性质另一位」再犯。
 *
 * ## 这份测试要挡的具体事故
 *
 * `AnimBaker.timeline()` 与「量本体基准」两处都曾用「`FloatArray(12)` 全清 0，
 * 再手动补 `v[1]=-1 / v[4]=1 / v[5]=-1`」的写法。两处都漏了 `v[10]`（idleEyeScale）：
 * 它在 `buildMesh` 里算 `min = Math.min(base, eyeQ)` → `f8 = 1 - min`，
 * 留 0 就等于「眼睛纵向压扁到极值」，**烘出来的每一帧眼睛都是扁的**。
 *
 * 这个缺陷不会崩、不会报错，只会让画面"哪里怪怪的"，属于最难自查的一类。
 * 所以这里用断言把「每一位的中性值」写成契约。
 */
class BakeParamsTest {

    @Test
    fun size_isTwelve_documentedByContract() {
        assertEquals(12, BakeParams.SIZE)
    }

    /** 跳跃 / 落地两位的中性值是 **-1**，不是 0（0 表示「刚好在起跳/落地那一瞬」）。 */
    @Test
    fun jumpAndLand_defaultToMinusOne() {
        val v = BakeParams.defaults()
        assertEquals(-1f, v[BakeParams.IDX_JUMP], 0f)
        assertEquals(-1f, v[BakeParams.IDX_LAND], 0f)
    }

    /**
     * **两个缩放位的中性值必须是 1**。
     *
     * 这是本条测试的核心：`IDX_IDLE_SCALE_Y` 与 `IDX_EYE_SCALE` 留 0 都会把形态压成极值
     * （前者把整个身体纵向压扁，后者把眼睛压扁），而它们恰好是「容易被忘掉」的两位。
     */
    @Test
    fun bothScaleFactors_defaultToOne_notZero() {
        val v = BakeParams.defaults()
        assertEquals("idleScaleY 留 0 会把身体纵向压扁", 1f, v[BakeParams.IDX_IDLE_SCALE_Y], 0f)
        assertEquals("idleEyeScale 留 0 会把眼睛压扁到极值", 1f, v[BakeParams.IDX_EYE_SCALE], 0f)
    }

    /** 其余位（相位 / 抬升 / 倾斜 / 眨眼 / 视线）中性值是 0。 */
    @Test
    fun rest_defaultToZero() {
        val v = BakeParams.defaults()
        assertEquals(0f, v[BakeParams.IDX_PHASE], 0f)
        assertEquals(0f, v[BakeParams.IDX_DRAG_LIFT], 0f)
        assertEquals(0f, v[BakeParams.IDX_IDLE_LIFT], 0f)
        assertEquals(0f, v[BakeParams.IDX_LEAN], 0f)
        assertEquals(0f, v[BakeParams.IDX_TILT], 0f)
        assertEquals(0f, v[BakeParams.IDX_RESERVED], 0f)
        assertEquals(0f, v[BakeParams.IDX_BLINK], 0f)
        assertEquals(0f, v[BakeParams.IDX_GAZE], 0f)
    }

    /** 静立态与 defaults 的差别只在相位，且相位可指定。 */
    @Test
    fun stand_differsFromDefaultsOnlyByPhase() {
        val a = BakeParams.stand()
        val b = BakeParams.defaults()
        assertEquals(0f, a[BakeParams.IDX_PHASE], 0f)
        for (i in 0 until BakeParams.SIZE) {
            if (i == BakeParams.IDX_PHASE) {
                continue
            }
            assertEquals("下标 $i 不该被 stand() 改动", b[i], a[i], 0f)
        }
        val c = BakeParams.stand(1.5f)
        assertEquals(1.5f, c[BakeParams.IDX_PHASE], 0f)
    }

    /** reset() 把已污染的数组恢复成中性值（逐帧生成时间线时的起手式）。 */
    @Test
    fun reset_cleansPollutedArray() {
        val v = FloatArray(BakeParams.SIZE) { 9.9f }
        BakeParams.reset(v)
        val d = BakeParams.defaults()
        for (i in 0 until BakeParams.SIZE) {
            assertEquals("下标 $i 未被 reset 复原", d[i], v[i], 0f)
        }
    }

    /**
     * **交叉守门：与 [PetTransform.Params.ofArray] 的下标口径必须一致。**
     *
     * 这两个类读同一个数组：`AnimBaker` 按下标写，`PetTransform` 按下标读。
     * 任一方改了顺序而另一方没跟着改，烘出来的帧就会莫名其妙地偏/歪 ——
     * 而症状（人偶位置不对）和「包围盒算错」长得一模一样，极难定位。
     * 这里直接喂一组**互不相同**的值，验证读出来的是对应的那一位。
     */
    @Test
    fun transformReadsTheSameIndicesThisClassWrites() {
        val v = FloatArray(BakeParams.SIZE)
        v[BakeParams.IDX_PHASE] = 0.25f
        v[BakeParams.IDX_JUMP] = 0.31f
        v[BakeParams.IDX_DRAG_LIFT] = 0.42f
        v[BakeParams.IDX_IDLE_LIFT] = 0.53f
        v[BakeParams.IDX_IDLE_SCALE_Y] = 0.64f
        v[BakeParams.IDX_LAND] = 0.75f
        v[BakeParams.IDX_LEAN] = 0.86f
        v[BakeParams.IDX_TILT] = 0.97f
        val p = PetTransform.Params.ofArray(v)
        assertEquals(0.25f, p.phase, 1e-6f)
        assertEquals(0.31f, p.jumpT, 1e-6f)
        assertEquals(0.42f, p.dragLift, 1e-6f)
        assertEquals(0.53f, p.idleLiftPx, 1e-6f)
        assertEquals(0.64f, p.idleScaleY, 1e-6f)
        assertEquals(0.75f, p.landT, 1e-6f)
        assertEquals(0.86f, p.lean, 1e-6f)
        assertEquals(0.97f, p.tilt, 1e-6f)
    }

    /**
     * 短数组不能抛，且**缺位**必须回退成该位的中性值（历史上有过「传了短数组崩在烘帧线程」）。
     *
     * 【为什么用 `FloatArray(1)` 而不是 2】长度 2 的数组 `[1]` 是真实存在的元素，
     * 里面就是 0 —— 那是调用方明确给了「起跳瞬间」，不算「缺位」。
     * 只有 `size <= 下标` 才是缺位，才该走回退。用 2 去断言 `jumpT == -1f` 是测试自己写错了。
     */
    @Test
    fun shortArray_fallsBackToNeutralDefaults() {
        val p = PetTransform.Params.ofArray(FloatArray(1))
        assertEquals("缺位的 jumpT 应回退为 -1（未在跳跃）", -1f, p.jumpT, 0f)
        assertEquals("缺位的 landT 应回退为 -1（未在落地）", -1f, p.landT, 0f)
        assertEquals("缺位的 idleScaleY 应回退为 1", 1f, p.idleScaleY, 0f)
        assertEquals(0f, p.dragLift, 0f)
        assertEquals(0f, p.phase, 0f)
    }

    /** 空数组同样不抛（极端兜底）。 */
    @Test
    fun emptyArray_usesAllNeutralDefaults() {
        val p = PetTransform.Params.ofArray(FloatArray(0))
        assertEquals(-1f, p.jumpT, 0f)
        assertEquals(-1f, p.landT, 0f)
        assertEquals(1f, p.idleScaleY, 0f)
    }
}