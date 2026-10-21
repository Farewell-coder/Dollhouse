package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】钉住 [DollSizeSpec] 的换算规则与边界收敛。
 *
 * ## 为什么这些用例值得写
 *
 * 尺寸标准化最容易出的三类错都在纯逻辑层，真机上看不出来、但用户天天遇到：
 * 1. **换动作忽大忽小** —— 归一化系数算错（该除的乘了）。
 * 2. **老用户升级后大小全变** —— 默认值没走「系数恒为 1」这条路径。
 * 3. **极端素材把人偶撑到屏幕外** —— 缺少上下限收敛。
 * 这三条都不需要 Android 环境，可以在这里钉死。
 */
class DollSizeSpecTest {

    /* ============================ 内容占比换算 ============================ */

    @Test
    fun `角色占满画布时系数为一`() {
        // 占比 100%：不需要任何放大，系数必须恰好 1.0（否则升级后所有内置人偶都会变大小）。
        assertEquals(1.0f, DollSizeSpec.fillFactorOf(1.0f)!!, 1e-4f)
    }

    @Test
    fun `角色只占一半高度时要放大一倍`() {
        // 这是「导入素材四周透明留白很多」的典型情形：
        // 整张画布铺满窗口的话，角色只有一半高 → 必须放大 2 倍才与内置人偶同视觉大小。
        assertEquals(2.0f, DollSizeSpec.fillFactorOf(0.5f)!!, 1e-4f)
    }

    @Test
    fun `占比越小的角色被放大得越多`() {
        val small = DollSizeSpec.fillFactorOf(0.25f)!!
        val big = DollSizeSpec.fillFactorOf(0.75f)!!
        assertTrue("占比小的应当放大更多", small > big)
    }

    @Test
    fun `极端占比要退回旧口径而不是放大到离谱`() {
        // 占比 1%（几乎全透明）若认真按 100 倍放大，人偶会比屏幕还大。
        // 这种情况必须返回 null，让调用方保持「整画布铺满」的旧行为。
        assertNull(DollSizeSpec.fillFactorOf(0.01f))
        // 占比非法（0 / 负数 / 大于 1 / NaN）同样退回。
        assertNull(DollSizeSpec.fillFactorOf(0f))
        assertNull(DollSizeSpec.fillFactorOf(-0.2f))
        assertNull(DollSizeSpec.fillFactorOf(1.5f))
        assertNull(DollSizeSpec.fillFactorOf(Float.NaN))
    }

    @Test
    fun `包围盒为null时不产生系数`() {
        // 普通 JPG 照片没有透明边界 → 算不出包围盒 → 不写系数（让用户自己调）。
        assertNull(DollSizeSpec.fillFactor(null))
    }

    @Test
    fun `包围盒按高度算占比而不是宽度`() {
        // 【为什么是高度】人偶的视觉大小由身高决定；挥手时肩膀变宽不该让人偶变矮。
        // 这里给一个「很高但很窄」的框：高度占 50% → 系数 2.0，
        // 若误用宽度（10%）会算出 10 倍。
        val box = BodyBaseline.Box(0.45f, 0.25f, 0.55f, 0.75f)
        assertEquals(0.5f, box.height, 1e-4f)
        assertEquals(2.0f, DollSizeSpec.fillFactor(box)!!, 1e-4f)
    }

    /* ============================ 规格编解码 ============================ */

    @Test
    fun `默认规格就是自动适配`() {
        val d = DollSizeSpec.Spec.DEFAULT
        assertEquals(100, d.size)
        assertEquals(0f, d.ox, 0f)
        assertEquals(0f, d.oy, 0f)
        assertTrue("默认规格必须被判定为默认（否则老用户画面会变）", d.isDefault())
    }

    @Test
    fun `默认规格的缩放系数恰好是一`() {
        assertEquals(1.0f, DollSizeSpec.scaleOf(DollSizeSpec.Spec.DEFAULT), 1e-4f)
    }

    @Test
    fun `编码后解码应还原全部字段`() {
        val s = DollSizeSpec.Spec(180, 0.25f, -0.4f)
        val back = DollSizeSpec.Spec.decode(s.encode())
        assertEquals(s.size, back.size)
        assertEquals(s.ox, back.ox, 1e-4f)
        assertEquals(s.oy, back.oy, 1e-4f)
    }

    @Test
    fun `空串与坏数据都退回默认值`() {
        assertTrue(DollSizeSpec.Spec.decode(null).isDefault())
        assertTrue(DollSizeSpec.Spec.decode("").isDefault())
        assertTrue(DollSizeSpec.Spec.decode("不是数字").isDefault())
        assertEquals(DollSizeSpec.Spec.DEFAULT, DollSizeSpec.Spec.decode("1,2"))
    }

    @Test
    fun `大小与偏移超出范围要被钳位`() {
        val lo = DollSizeSpec.Spec.decode("-999,9,-9")
        assertEquals(DollSizeSpec.Spec.SIZE_MIN, lo.size)
        assertEquals(DollSizeSpec.Spec.OFFSET_MAX, lo.ox, 1e-4f)
        assertEquals(-DollSizeSpec.Spec.OFFSET_MAX, lo.oy, 1e-4f)

        val hi = DollSizeSpec.Spec.decode("99999,0,0")
        assertEquals(DollSizeSpec.Spec.SIZE_MAX, hi.size)
    }

    @Test
    fun `改任意一个字段就不再是默认`() {
        assertTrue(!DollSizeSpec.Spec.DEFAULT.copy(size = 120).isDefault())
        assertTrue(!DollSizeSpec.Spec.DEFAULT.copy(ox = 0.1f).isDefault())
        assertTrue(!DollSizeSpec.Spec.DEFAULT.copy(oy = -0.1f).isDefault())
    }

    @Test
    fun `放大到上限时系数等于四倍`() {
        assertEquals(4.0f,
            DollSizeSpec.scaleOf(DollSizeSpec.Spec(DollSizeSpec.Spec.SIZE_MAX, 0f, 0f)),
            1e-4f)
    }

    /* ============================ 与标准化画布的关系 ============================ */

    @Test
    fun `标准化画布参考值存在且为正`() {
        // 需求书明确 512 只是「逻辑参考值」，但它是换算的锚点，必须是个正常正数。
        assertTrue(DollSizeSpec.REF_SIDE > 0)
        assertNotNull(DollSizeSpec.REF_SIDE)
    }
}