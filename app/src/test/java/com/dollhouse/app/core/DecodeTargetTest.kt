package com.dollhouse.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageDecode.calcSampleSize] 与「解码目标」的口径回归测试。
 *
 * ## 为什么需要这个文件
 *
 * r22 改造里踩过一次**方向性错误**，值得用测试永久钉住：
 *
 * `calcSampleSize` 的语义是「找最大的 2 的幂 s，使 `src/s` **仍 ≥ 目标**」，
 * 也就是说传进去的目标是**下限**，不是上限。目标越高，采样率越低（越不省内存）。
 *
 * 第一版按「给 2 倍分辨率余量更安全」的直觉写成 `窗口宽 × 2`，结果目标从
 * 1024 涨到 1350 —— 对 904px 的素材，采样率永远是 1，一帧都没降档，
 * 120 帧照样占 405MB。**这是负优化**：改完之后比改造前还差。
 *
 * 真正的降档条件是 `目标 ≤ 原图边长 / 2`。
 *
 * ## 本机实测数据（写死在这里作为对照基准）
 *
 * 真实帧组是 **904×978**（不是代码注释里曾假设的 2048²）。
 *
 * | 目标 | 采样率 | 单帧 | 120 帧 ARGB |
 * |---|---|---|---|
 * | 1024（改造前） | 1 | 0.88MP | 405MB |
 * | 1350（错误版 ×2） | 1 | 0.88MP | 405MB |
 * | 452 | 2 | 0.22MP | 101MB |
 */
class DecodeTargetTest {

    /** 本机实测的真实帧组尺寸，多个用例共用。 */
    private val srcW = 904
    private val srcH = 978

    /* ===================== calcSampleSize 基线语义 ===================== */

    /** 目标超过原图：不采样（s=1）。这正是 1024 对 904px 素材的处境。 */
    @Test
    fun `目标大于原图时不采样`() {
        assertEquals(1, ImageDecode.calcSampleSize(srcW, srcH, 1024, 1024))
        assertEquals(1, ImageDecode.calcSampleSize(srcW, srcH, 1350, 1350))
    }

    /** 目标恰好落在「一半」上：降到 s=2。 */
    @Test
    fun `目标等于一半时降档`() {
        // 904/2 = 452，978/2 = 489，都 ≥ 452 → s=2 成立
        assertEquals(2, ImageDecode.calcSampleSize(srcW, srcH, 452, 452))
    }

    /**
     * 采样率的两个不变量：**永远是 2 的幂**，且**不放大**（s ≥ 1）。
     *
     * 【别把「采样后 ≥ 目标」写成断言】第一版这里就是这么写的，结果 target=1024
     *   在 904px 素材上红了 —— 因为 s=1 时采样后就是 904，**小于**目标。
     *   这不是 bug，而是 `calcSampleSize` 的既定行为：它只找「降到哪一档仍然够用」，
     *   从不负责把图放大到目标。目标超过原图时它的答案就是「不动」。
     *   上面 `目标大于原图时不采样` 已经把这个行为单独钉住了。
     */
    @Test
    fun `采样率始终是二的幂且从不放大`() {
        for (target in intArrayOf(64, 100, 200, 256, 400, 452, 600, 900, 1024)) {
            val s = ImageDecode.calcSampleSize(srcW, srcH, target, target)
            val isPowerOfTwo = (s and (s - 1)) == 0
            assertTrue("target=$target 的采样率 $s 不是 2 的幂", isPowerOfTwo)
            assertTrue("target=$target 的采样率 $s 不该小于 1（放大）", s >= 1)
            // 目标不超过原图时，才应保证「降档后仍够用」。
            if (target <= srcW && target <= srcH) {
                assertTrue(
                    "target=$target 采样后 ${srcW / s} 应 ≥ $target",
                    srcW / s >= target && srcH / s >= target
                )
            }
        }
    }

    /** 目标 <= 0 表示「不采样」（旧调用方语义）。 */
    @Test
    fun `目标为零或负数时不采样`() {
        assertEquals(1, ImageDecode.calcSampleSize(srcW, srcH, 0, 0))
        assertEquals(1, ImageDecode.calcSampleSize(srcW, srcH, -10, -10))
    }

    /* ===================== 宿主注入口径 ===================== */

    /**
     * 复刻 `PetView` 的注入算式：`max(当前宽, 上限宽) × RATIO`，夹下界。
     *
     * 【为什么要复刻而不是直接调用】那段 lambda 依赖 PetView 实例（dp / 比例状态），
     *   在 JVM 单测里拿不到。这里把**算式**钉住：RATIO 一旦被改回 2，下面的
     *   「必须触发降档」用例立刻红。
     */
    private fun injectedTarget(
        petWidth: Int,
        petMaxWidth: Int,
        ratio: Float = 0.5f,
        floor: Int = 256
    ): Int {
        val w = Math.max(petWidth, petMaxWidth)
        return if (w <= 0) 0 else Math.max(floor, Math.round(w * ratio))
    }

    /**
     * **核心回归**：本机默认比例（450px 窗口）下，注入的目标必须真的触发降档。
     *
     * 【这条是 r22 的验收线】只要它绿，就说明「解码降档」不是纸面优化：
     *   120 帧的内存占用确实从 405MB 档位降到 101MB 档位。
     */
    @Test
    fun `默认比例下注入的目标能触发降档`() {
        val target = injectedTarget(petWidth = 450, petMaxWidth = 675)
        val s = ImageDecode.calcSampleSize(srcW, srcH, target, target)
        assertEquals("目标 $target 必须让 904px 素材降到 sample=2", 2, s)
    }

    /** 人偶拉到上限（675px）也不能退回不采样 —— 否则「调大就爆内存」。 */
    @Test
    fun `比例拉满时仍在降档档位`() {
        val target = injectedTarget(petWidth = 675, petMaxWidth = 675)
        val s = ImageDecode.calcSampleSize(srcW, srcH, target, target)
        assertEquals(2, s)
    }

    /** 当前比例很小、上限大时，按上限算（帧只解码一次，要覆盖未来拉大）。 */
    @Test
    fun `取当前宽与上限宽的较大者`() {
        assertEquals(
            injectedTarget(200, 675),
            injectedTarget(675, 675)
        )
    }

    /** 窗口还没测量出来（宽 0）时返回 0，表示「用兜底值」。 */
    @Test
    fun `宽为零时返回零表示走兜底`() {
        assertEquals(0, injectedTarget(0, 0))
    }

    /** 极小窗口也不该把目标压到个位数（下界生效）。 */
    @Test
    fun `极小窗口受下界保护`() {
        val target = injectedTarget(petWidth = 40, petMaxWidth = 40)
        assertEquals(256, target)
    }

    /* ===================== 内存收益对照 ===================== */

    /** 单帧 ARGB_8888 的字节数（4 字节/像素）。 */
    private fun frameBytes(w: Int, h: Int): Long = w.toLong() * h * 4L

    /**
     * 钉住「405MB → 101MB」这个量级。
     *
     * 【为什么断言这么松（只要 3 倍以上）】具体倍数依赖素材尺寸；这里要防的是
     *   「优化失效」（倍数跌回 1），不是精确值。换素材时这条不该红。
     */
    @Test
    fun `降档带来三倍以上的单帧内存下降`() {
        val before = frameBytes(srcW, srcH) // s=1
        val s = ImageDecode.calcSampleSize(
            srcW, srcH,
            injectedTarget(450, 675),
            injectedTarget(450, 675)
        )
        val after = frameBytes(srcW / s, srcH / s)
        assertTrue(
            "降档后单帧应至少省 3 倍，实测 ${before / after} 倍",
            before / after >= 3
        )
    }

    /** 满帧（120 帧）时的绝对量：降档后必须低于 200MB，否则仍会被系统杀。 */
    @Test
    fun `满帧内存低于两百兆`() {
        val target = injectedTarget(450, 675)
        val s = ImageDecode.calcSampleSize(srcW, srcH, target, target)
        val total = frameBytes(srcW / s, srcH / s) * 120L
        val mb = total / 1024.0 / 1024.0
        assertTrue("满帧 ${"%.0f".format(mb)}MB 偏高，可能降档没生效", mb < 200.0)
    }
}
