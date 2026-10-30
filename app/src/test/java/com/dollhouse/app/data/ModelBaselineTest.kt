package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【测试对象】[ModelBaseline] —— 模型级基准身高的纯算术部分。
 *
 * 【为什么这些用例值得写】需求书 R-080 ~ R-086 的验收方式是「切换动作前后量像素高度」，
 *   那必须上真机。但「基准取谁 / 修正值多少 / 差异算哪一档」是纯算术，
 *   在这里钉死之后，真机测试只需要验证「渲染是否忠实执行了这个数」，
 *   出问题时可以立刻区分是「算法错」还是「渲染错」。
 */
class ModelBaselineTest {

    /* ---------- 19.7.2 基准来源 ---------- */

    @Test
    fun `有待机时以待机为本基准`() {
        val r = ModelBaseline.compute(idleFill = 0.8f, otherFills = listOf(0.5f, 0.9f))
        assertEquals(ModelBaseline.Source.IDLE, r.source)
        assertEquals(0.8f, r.referenceFill, 0.0001f)
        assertTrue(r.isValid())
    }

    @Test
    fun `无待机时取其它动作的中位数_奇数个`() {
        val r = ModelBaseline.compute(idleFill = null, otherFills = listOf(0.4f, 0.6f, 0.5f))
        assertEquals(ModelBaseline.Source.MEDIAN, r.source)
        // 排序后 0.4 / 0.5 / 0.6 → 中位数 0.5
        assertEquals(0.5f, r.referenceFill, 0.0001f)
    }

    @Test
    fun `无待机时取其它动作的中位数_偶数个`() {
        val r = ModelBaseline.compute(idleFill = null, otherFills = listOf(0.4f, 0.6f))
        assertEquals(ModelBaseline.Source.MEDIAN, r.source)
        // 0.4 / 0.6 → 中位数 0.5
        assertEquals(0.5f, r.referenceFill, 0.0001f)
    }

    @Test
    fun `待机不可靠时退回中位数`() {
        // 0.01 低于 MIN_FILL(0.08)：这是坏素材（几乎全透明），不能当基准
        val r = ModelBaseline.compute(idleFill = 0.01f, otherFills = listOf(0.5f, 0.7f))
        assertEquals(ModelBaseline.Source.MEDIAN, r.source)
        assertEquals(0.6f, r.referenceFill, 0.0001f)
    }

    @Test
    fun `完全没有有效值时返回 NONE`() {
        val r = ModelBaseline.compute(idleFill = null, otherFills = emptyList())
        assertEquals(ModelBaseline.Source.NONE, r.source)
        assertFalse(r.isValid())
    }

    @Test
    fun `中位数计算必须忽略越界值`() {
        // 0.005 与 5f 都是坏数据（低于下限 / 高于上限）
        val r = ModelBaseline.compute(idleFill = null, otherFills = listOf(0.005f, 0.5f, 5f))
        assertEquals(ModelBaseline.Source.MEDIAN, r.source)
        assertEquals(0.5f, r.referenceFill, 0.0001f)
    }

    /* ---------- 19.7.3 / 19.7.6 修正值 ---------- */

    @Test
    fun `修正值等于基准比动作身高`() {
        // 基准 0.8，动作 0.4 → 放大 2 倍
        val r = ModelBaseline.scaleFixFor(0.8f, 0.4f)
        assertEquals(2.0f, r.fix, 0.0001f)
        assertFalse(r.needsReview)
    }

    @Test
    fun `修正值相同时为1`() {
        val r = ModelBaseline.scaleFixFor(0.6f, 0.6f)
        assertEquals(1.0f, r.fix, 0.0001f)
        assertFalse(r.needsReview)
    }

    @Test
    fun `修正值超上限时必须标记待核验_而不是静默截断`() {
        // 基准 0.9，动作 0.2 → 真实需要 4.5 倍，超出 2.0 上限
        val r = ModelBaseline.scaleFixFor(0.9f, 0.2f)
        assertEquals(2.0f, r.fix, 0.0001f)
        assertTrue("超出 0.5~2.0 必须标记 needsReview", r.needsReview)
    }

    @Test
    fun `修正值超下限时必须标记待核验`() {
        // 基准 0.2，动作 0.9 → 真实需要 0.222 倍，低于 0.5 下限
        val r = ModelBaseline.scaleFixFor(0.2f, 0.9f)
        assertEquals(0.5f, r.fix, 0.0001f)
        assertTrue(r.needsReview)
    }

    @Test
    fun `非法输入返回不修正`() {
        assertEquals(1.0f, ModelBaseline.scaleFixFor(0f, 0.5f).fix, 0.0001f)
        assertEquals(1.0f, ModelBaseline.scaleFixFor(0.5f, 0f).fix, 0.0001f)
        assertEquals(1.0f, ModelBaseline.scaleFixFor(Float.NaN, 0.5f).fix, 0.0001f)
        assertEquals(1.0f, ModelBaseline.scaleFixFor(Float.POSITIVE_INFINITY, 0.5f).fix, 0.0001f)
    }

    /* ---------- R-086 三档判定 ---------- */

    @Test
    fun `差异小于5个百分点算通过`() {
        assertEquals(ModelBaseline.Level.OK, ModelBaseline.levelOf(0f))
        assertEquals(ModelBaseline.Level.OK, ModelBaseline.levelOf(0.04f))
        assertEquals(ModelBaseline.Level.OK, ModelBaseline.levelOf(0.049f))
    }

    @Test
    fun `差异5到10个百分点算自动修正`() {
        assertEquals(ModelBaseline.Level.AUTO_FIXED, ModelBaseline.levelOf(0.05f))
        assertEquals(ModelBaseline.Level.AUTO_FIXED, ModelBaseline.levelOf(0.08f))
        assertEquals(ModelBaseline.Level.AUTO_FIXED, ModelBaseline.levelOf(0.10f))
    }

    @Test
    fun `差异超过10个百分点算建议手动对齐`() {
        assertEquals(ModelBaseline.Level.REVIEW, ModelBaseline.levelOf(0.101f))
        assertEquals(ModelBaseline.Level.REVIEW, ModelBaseline.levelOf(0.5f))
    }

    @Test
    fun `差异比率以基准为分母`() {
        // |0.4 - 0.5| / 0.5 = 0.2
        assertEquals(0.2f, ModelBaseline.diffRatio(0.5f, 0.4f), 0.0001f)
        // 基准无效时返回 0（不做除零）
        assertEquals(0f, ModelBaseline.diffRatio(0f, 0.4f), 0.0001f)
    }

    /* ---------- 序列化：旧档 / 坏档兜底 ---------- */

    @Test
    fun `编码解码往返一致`() {
        val src = ModelBaseline.Ref(0.8123f, ModelBaseline.Source.MEDIAN, true)
        val back = ModelBaseline.Ref.decode(src.encode())
        assertEquals(src.referenceFill, back.referenceFill, 0.0001f)
        assertEquals(src.source, back.source)
        assertEquals(src.needsReview, back.needsReview)
    }

    @Test
    fun `坏档一律回落 NONE 而不是崩溃`() {
        assertEquals(ModelBaseline.Source.NONE, ModelBaseline.Ref.decode(null).source)
        assertEquals(ModelBaseline.Source.NONE, ModelBaseline.Ref.decode("").source)
        assertEquals(ModelBaseline.Source.NONE, ModelBaseline.Ref.decode("abc").source)
        assertEquals(ModelBaseline.Source.NONE, ModelBaseline.Ref.decode("0.5").source)
        // 数值越界也要回落（0.001 低于 MIN_FILL）
        assertEquals(ModelBaseline.Source.NONE, ModelBaseline.Ref.decode("0.001,idle,0").source)
    }

    @Test
    fun `NONE 不算有效基准`() {
        assertFalse(ModelBaseline.Ref.NONE.isValid())
    }

    /* ---------- 边界：不能把「半身」当基准 ---------- */

    @Test
    fun `贴边半身不参与中位数_由调用方保证`() {
        // 这条用例用来锁定「调用方必须排除贴边」的契约：
        // 如果调用方误把贴边(0.35)算进中位数，基准会被拉到 0.35~0.5 之间，
        // 于是所有人偶都被放大。这里验证「只传拖拽/贴入」时基准是正常值。
        val withHalfBody = ModelBaseline.compute(
            idleFill = null,
            otherFills = listOf(0.8f, 0.35f) // 0.35 = 半身贴边，本应被排除
        )
        // 如果没排除，中位数会是 0.575；排除后只剩 0.8 → 基准 0.8
        // 本用例锁定「调用方必须自己排除」这一约定，防止未来被误改。
        assertEquals(0.575f, withHalfBody.referenceFill, 0.0001f)

        val excluded = ModelBaseline.compute(idleFill = null, otherFills = listOf(0.8f))
        assertEquals(0.8f, excluded.referenceFill, 0.0001f)
    }
}