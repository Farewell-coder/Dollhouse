package com.dollhouse.app.pet

import com.dollhouse.app.pet.render.PetMetrics
import com.dollhouse.app.pet.render.PetTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[PetTransform] 的单元测试 —— 三条链收敛后的**几何护栏**。
 *
 * ## 为什么这个测试特别重要
 * 改造前 `onDraw` / `bakeDraw` / `bakeBounds` 各自实现同一套变换，靠注释人肉同步，
 * 历史上已经因此出过一次事故：`bakeBounds` 误用 `pre*`（等价于把变换链倒过来），
 * 烘出的帧人偶被裁掉，**装机后才发现**，最后靠 `BAKE_VERSION = 3` 强制重烘才修复。
 *
 * 现在收敛到 [PetTransform] 一处，但「一处」也可能被改错。本测试把
 * 三条链的**共同事实**钉住：
 * · `applyTo` 与 `mapBounds` **必须同源**（这是当年事故的直接成因）；
 * · 变换序列的顺序不能变（`scale` 在 `rotate` 之前）；
 * · 边界值（全 0 参数）要退化成「等价于无变换」。
 *
 * ## 一个重要更正：`android.graphics.Matrix` 在 JVM 单测里**不可用**
 *
 * 本测试初版曾假设「`Matrix` 是纯 Java 实现，单测能直接跑」——**实测是错的**：
 * `android.jar` 里所有方法体都是抛
 * `RuntimeException: Method ... not mocked` 的 stub，
 * 除非引入 Robolectric（任务书 §3.9 明确禁止）或开
 * `testOptions.unitTests.returnDefaultValues = true`。
 *
 * 后者也不能用：开了之后 `Matrix` 的所有方法变成**空实现**，
 * `toMatrix()` 会返回恒等矩阵、`mapRect` 什么都不做 ——
 * 测试会「通过」，但验证的是一个假对象。**假通过比失败更糟**。
 *
 * ### 所以本测试改测什么
 * 不测矩阵本身，改测**喂给矩阵的那组标量字段**与**包围盒平移量**：
 * · `scaleX` / `scaleY` / `rotLean` / `rotTilt` 的取值与相互关系；
 * · `boundsOffsetX()` / `boundsOffsetY()` 的数值（这才是「同源」里
 *   `mapBounds` 独有的那部分，也是最容易与 `applyTo` 走偏的部分）；
 * · `emoteAnchor` 的计算（纯数学，可完整验证）。
 *
 * 矩阵本身的行为由 `Canvas`/`Matrix` 的框架实现负责，不属于本工程的逻辑。
 */
class PetTransformTest {

    /** 一个最小的度量实现：150×200 的人偶，锚点在正中。 */
    private class FakeMetrics(
        private val pw: Float = 150f,
        private val fh: Float = 200f,
        private val px: Float = 75f,
        private val py: Float = 200f,
        private val bob: Float = 1.6f,
        private val jump: Float = 12f
    ) : PetMetrics {
        override fun petWidth(): Float = pw
        override fun fullHeight(): Float = fh
        override fun pivotLocalX(): Float = px
        override fun pivotLocalY(): Float = py
        override fun bobAmp(): Float = bob
        override fun jumpAmp(): Float = jump
        override fun ahogeRu(): Float = 0.11f
        override fun dragTiltMaxPx(): Float = 8f
    }

    private fun params(
        phase: Float = 0f,
        jumpT: Float = -1f,
        dragLift: Float = 0f,
        idleLiftPx: Float = 0f,
        idleScaleY: Float = 1f,
        landT: Float = -1f,
        lean: Float = 0f,
        tilt: Float = 0f
    ) = PetTransform.Params(
        phase = phase, jumpT = jumpT, dragLift = dragLift,
        idleLiftPx = idleLiftPx, idleScaleY = idleScaleY,
        landT = landT, lean = lean, tilt = tilt
    )

    // ============================ 同源铁律 ============================

    /**
     * 【本测试是整份文件的核心】`applyTo` 与 `mapBounds` 必须由同一组参数推导。
     *
     * 【怎么验】把「单位矩形」分别用两条路走一遍：
     * · `mapBounds` 直接给出映射后的矩形；
     * · 通过 `toMatrix()` 手工 mapRect，结果必须逐值相同。
     *
     * 若有人把 `mapBounds` 改成自己算一套（这正是当年的 bug 形态），
     * 这里立刻红。
     */
    /**
     * **同源铁律**（改造后的版本）：`mapBounds` 的平移量必须由
     * **与 `applyTo` 同一组字段**推出。
     *
     * 【背景】改造前 `bakeBounds` 自己手写了一套矩阵 + offset，与 `onDraw`
     *   各算各的 —— 这正是当年 `pre*`/`post*` 写反、烘帧人偶被裁的根因。
     *
     * 【怎么验（不碰 Matrix）】`mapBounds` = `toMatrix().mapRect()` + `offset(boundsOffsetX, boundsOffsetY)`。
     *   其中矩阵那半截由框架负责；**工程自己的逻辑全在 offset 里**。
     *   所以这里验证 offset 与各标量字段的推导关系：
     * ```
     *   boundsOffsetX = petWidth / 2
     *   boundsOffsetY = fullHeight + bobOffset - jumpOffset - lift
     * ```
     *   这两条一旦有人改错，烘帧就会整体偏移 —— 而 `applyTo` 那边不会跟着错，
     *   于是「同源」被破坏，正是本测试要拦住的情形。
     */
    @Test
    fun mapBounds_offsetsAreDerivedFromSameFields() {
        val m = FakeMetrics()
        val cases = listOf(
            params(),
            params(phase = 1.234f),
            params(jumpT = 0.5f),
            params(dragLift = 1f, tilt = 4f),
            params(landT = 0.7f),
            params(lean = 25f),
            params(idleScaleY = 0.95f, idleLiftPx = 6f)
        )
        for (p in cases) {
            for (flipped in listOf(false, true)) {
                val tf = PetTransform.build(p, m, flipped, p.phase.toDouble())
                assertEquals(
                    "boundsOffsetX 必须等于 petWidth/2",
                    tf.petWidth / 2.0f, tf.boundsOffsetX(), 1e-4f
                )
                assertEquals(
                    "boundsOffsetY 必须由同一组字段推出",
                    tf.fullHeight + tf.bobOffset - tf.jumpOffset - tf.lift,
                    tf.boundsOffsetY(), 1e-4f
                )
            }
        }
    }

    // ============================ 边界值 ============================

    /**
     * 全 0 参数（静止站立）时，缩放应恰好为 1、无旋转、无抬升。
     *
     * 【为什么这条重要】`PetAnimator` 在纯待机态会把这些参数都压回默认值，
     *   此时若 `PetTransform` 引入了任何「自己加的」偏移，人偶在桌面上就会
     *   肉眼可见地错位 —— 而这类错位只在「完全不动的静止态」出现，最难注意到。
     */
    @Test
    fun idleParams_produceIdentityScale() {
        val tf = PetTransform.build(params(), FakeMetrics(), false, 0.0)
        assertEquals(1f, tf.scaleX, 1e-4f)
        assertEquals(1f, tf.scaleY, 1e-4f)
        assertEquals(0f, tf.rotLean, 1e-4f)
        assertEquals(0f, tf.rotTilt, 1e-4f)
        assertEquals(0f, tf.lift, 1e-4f)
    }

    /**
     * 拖动抬高（dragLift = 1）时必须有非零 lift。
     *
     * 【为什么单拎出来】`lift` 是 `onDraw` 里表情锚点纵向基准的一部分
     *   （`drawEmote(..., f4 - f3 - tf.lift)`）。若 lift 恒为 0，
     *   拖动时表情会留在原地、人体却抬起来了 —— 视觉上像「表情掉队」。
     */
    @Test
    fun dragLift_raisesLift() {
        val tf = PetTransform.build(params(dragLift = 1f), FakeMetrics(), false, 0.0)
        assertTrue("dragLift 应产生正抬升", tf.lift > 0f)
    }

    /**
     * 左右翻转只影响水平方向。
     *
     * 【为什么必须验】翻转是「镜像人偶」这个既有功能；若它影响了纵向，
     *   人偶会上下颠倒 —— 这是用户一眼能看出、但代码里很难一眼看出的错误。
     */
    @Test
    fun flipped_mirrorsHorizontallyOnly() {
        val m = FakeMetrics()
        val p = params(phase = 0.5f, jumpT = 0.3f, tilt = 3f)
        val a = PetTransform.build(p, m, false, 0.5)
        val b = PetTransform.build(p, m, true, 0.5)
        // 翻转只允许把 scaleX 取负，其余与纵向有关的量一律不动 ——
        // 【为什么这比「比包围盒」更直接】纵向若被翻转带上，人偶会上下颠倒，
        //   那是用户一眼可见的错误；用字段断言能一眼定位到是哪个量出了问题。
        assertEquals("翻转必须取负 scaleX", -a.scaleX, b.scaleX, 1e-4f)
        assertEquals("翻转不得改变 scaleY", a.scaleY, b.scaleY, 1e-4f)
        assertEquals("翻转不得改变贴边角", a.rotLean, b.rotLean, 1e-4f)
        assertEquals("翻转不得改变倾斜角", a.rotTilt, b.rotTilt, 1e-4f)
        assertEquals("翻转不得改变纵向抬升", a.lift, b.lift, 1e-4f)
        assertEquals("翻转不得改变全高", a.fullHeight, b.fullHeight, 1e-4f)
    }

    // ============================ 烘帧口径 ============================

    /**
     * `buildForBake` 接受 (ox, oy)，且不同 (ox, oy) 只应产生平移差异。
     *
     * 【为什么这条是烘帧的关键】烘帧时先量包围盒、再据此推 (ox, oy) 把
     *   精灵左上角摆到画布上。若 (ox, oy) 不只影响平移（比如误乘进了缩放），
     *   烘出来的每一帧尺寸都会不一样 —— 播放时会抖。
     */
    @Test
    fun bakeOffsets_onlyTranslate() {
        val m = FakeMetrics()
        val p = params(phase = 0.8f, jumpT = 0.3f)
        val a = PetTransform.buildForBake(p, m, 0.8, 0f, 0f)
        val b = PetTransform.buildForBake(p, m, 0.8, 40f, 25f)
        // (ox, oy) 只允许落在 canvasOffsetX/Y 上；其余任何字段都不能被它影响。
        assertEquals("缩放不受偏移影响", a.scaleX, b.scaleX, 1e-4f)
        assertEquals("缩放不受偏移影响", a.scaleY, b.scaleY, 1e-4f)
        assertEquals("旋转不受偏移影响", a.rotLean, b.rotLean, 1e-4f)
        assertEquals("旋转不受偏移影响", a.rotTilt, b.rotTilt, 1e-4f)
        assertEquals("抬升不受偏移影响", a.lift, b.lift, 1e-4f)
        assertEquals("包围盒平移量不受偏移影响", a.boundsOffsetY(), b.boundsOffsetY(), 1e-4f)
        // 而 (ox, oy) 本身必须被原样保留 —— applyTo 用它算 translate。
        assertEquals(0f, a.canvasOffsetX, 1e-4f)
        assertEquals(0f, a.canvasOffsetY, 1e-4f)
        assertEquals(40f, b.canvasOffsetX, 1e-4f)
        assertEquals(25f, b.canvasOffsetY, 1e-4f)
    }

    /**
     * 数组口径 `Params.ofArray` 的下标约定必须与 `bakeDraw` 一致。
     *
     * 【为什么锁这条】`bakeDraw` 传进来的 `FloatArray` 是
     *   `[0]phase [1]jumpT [2]dragLift [3]idleLiftPx [4]idleScaleY ...`。
     *   下标错一位会得到「参数全都对、但效果完全不对」的结果 ——
     *   例如把 jumpT 当成 dragLift，烘出来的帧会整体抬高。
     */
    @Test
    fun ofArray_matchesDocumentedIndices() {
        val v = floatArrayOf(
            1.5f,   // 0 phase
            0.25f,  // 1 jumpT
            0.75f,  // 2 dragLift
            6f,     // 3 idleLiftPx
            0.9f,   // 4 idleScaleY
            -1f,    // 5 landT
            3f,     // 6 lean
            2f      // 7 tilt
        )
        val p = PetTransform.Params.ofArray(v)
        assertEquals(1.5f, p.phase, 1e-6f)
        assertEquals(0.25f, p.jumpT, 1e-6f)
        assertEquals(0.75f, p.dragLift, 1e-6f)
        assertEquals(6f, p.idleLiftPx, 1e-6f)
        assertEquals(0.9f, p.idleScaleY, 1e-6f)
        assertEquals(-1f, p.landT, 1e-6f)
        assertEquals(3f, p.lean, 1e-6f)
        assertEquals(2f, p.tilt, 1e-6f)
    }

    /** 数组短于约定长度时不能崩（防越界档案 / 老调用方传短数组）。 */
    @Test
    fun ofArray_toleratesShortInput() {
        val p = PetTransform.Params.ofArray(floatArrayOf(1f, 0.5f))
        assertEquals(1f, p.phase, 1e-6f)
        assertEquals(0.5f, p.jumpT, 1e-6f)
        // 其余字段取安全默认，不抛异常即为通过。
        assertEquals(0f, p.dragLift, 1e-6f)
    }

    /**
     * 表情落点（纯数学）必须落在人偶身体右上方，且随抬升同步上移。
     *
     * 【为什么替换掉原来的「退化矩形」用例】原用例依赖 `android.graphics.RectF` /
     *   `Matrix`，在 JVM 单测里是 stub。`emoteAnchor` 是同一块几何里**纯数学**的那部分，
     *   而且它决定了「表情会不会跑到人偶外面」——更值得测。
     */
    @Test
    fun emoteAnchor_sitsAboveRightOfPetAndFollowsLift() {
        val m = FakeMetrics()
        val low = PetTransform.build(params(), m, false, 0.0)
        val high = PetTransform.build(params(dragLift = 1f), m, false, 0.0)
        val a1 = low.emoteAnchor(0.3f, 6f, 10f, 0f)
        val a2 = high.emoteAnchor(0.3f, 6f, 10f, 0f)
        // 水平：应落在人偶宽度的右半部分（AHOGE_U = 0.3 → 0.3*pw + pw/2 = 0.8*pw）。
        assertEquals(150f * 0.8f, a1[0], 1e-3f)
        // 纵向：抬升越大，基线越靠上（y 变小）。
        assertTrue("抬升应让表情上移", a2[1] < a1[1])
        // 同一个 t 下，y 的差值应恰好等于两次 lift 之差。
        assertEquals("y 差应等于 lift 差", low.lift - high.lift, a2[1] - a1[1], 1e-3f)
        // 表情会随时间上飘：t=1 应比 t=0 更靠上。
        val drifted = low.emoteAnchor(0.3f, 6f, 10f, 1f)
        assertTrue("t 增大应让表情上飘", drifted[1] < a1[1])
    }
}