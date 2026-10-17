package com.dollhouse.app.pet.render

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF

/**
 * 【职责】人偶一帧的完整几何变换 —— 全工程**唯一的几何事实来源**。
 *
 * 【为什么需要它】改造前 `onDraw` / `bakeDraw` / `bakeBounds` **各自独立实现了同一套
 *   几何变换**，必须人肉保持一致（代码注释里反复出现「几何完全复用 onDraw」
 *   「必须用 post* 而不是 pre*」这类警告）。历史上已经因此出过事故：
 *   `bakeBounds` 早期误用 `pre*`，等价于把变换链倒过来，导致烘出的帧**人偶被裁掉**，
 *   用户装机后才发现，最终靠 `BAKE_VERSION = 3` 强制重烘才修复。
 *
 *   **只要还是三条链，同类 bug 就会再犯。** 收敛到本类后：改几何只改这里，
 *   三处自动一致 —— 这不是洁癖，是止血。
 *
 * 【设计要点】本类**不持有 Canvas**，只描述「一组变换参数」；
 *   [applyTo]（绘制用）与 [toMatrix]（测量用）由**同一组字段**推导，
 *   绝不允许各自计算 —— 这是「两条路必然一致」的结构性保证，
 *   而不是靠注释提醒后来者「别忘了同步改另一处」。
 *
 * 【与旧代码的逐行对应】
 * ```
 * onDraw:                translate(pvx, sin+pvy-sin2-lift)
 *                        scale(sx, sy)          ← flipped 时 sx 为负
 *                        rotate(lean)
 *                        rotate(tilt)
 *                        translate(-pw/2, -fh)
 * bakeDraw:              额外先 translate(-dx, -dy)（把精灵左上角搬到画布 (ox,oy)）
 * bakeBounds:            等价矩阵 = S · R6 · R7 · T3，之后统一 offset(pw/2, fh+sin-sin2-lift)
 * ```
 * 【为什么 bakeBounds 的矩阵里没有 (pvx, P) 这一层】见 [boundsOffsetY] 的推导注释。
 *
 * 【线程安全】不可变对象（字段全 val），可跨线程使用与缓存。
 */
class PetTransform private constructor(
    /** 水平缩放（flipped 时为负）。 */
    val scaleX: Float,
    /** 垂直缩放（落地压扁 × 水平缩放 × 待机缩放）。 */
    val scaleY: Float,
    /** 贴边倾斜角（度）。 */
    val rotLean: Float,
    /** 待机/拖拽倾斜角（度）。 */
    val rotTilt: Float,
    /** 锚点 X（窗口局部坐标）。 */
    val pivotX: Float,
    /** 锚点 Y（窗口局部坐标，未叠加位移前的基准）。 */
    val pivotY: Float,
    /** 呼吸位移：`sin(phase) * bobAmp`。 */
    val bobOffset: Float,
    /** 跳跃位移：`sin(jumpT * π) * jumpAmp`（jumpT < 0 时为 0）。 */
    val jumpOffset: Float,
    /** 抬升量：`dragLift * dragTiltMaxPx + idleLiftPx`。 */
    val lift: Float,
    /** 精灵本体宽。 */
    val petWidth: Float,
    /** 精灵全高。 */
    val fullHeight: Float,
    /** 画布落点 X（仅烘帧口径使用；onDraw 口径为 0）。 */
    val canvasOffsetX: Float,
    /** 画布落点 Y（仅烘帧口径使用；onDraw 口径为 0）。 */
    val canvasOffsetY: Float
) {

    /** 动画参数口径的输入（与 `AnimBaker.timeline` 产出的 12 元数组一一对应）。 */
    class Params(
        val phase: Float,
        val jumpT: Float,
        val dragLift: Float,
        val idleLiftPx: Float,
        val idleScaleY: Float,
        val landT: Float,
        val lean: Float,
        val tilt: Float
    ) {
        companion object {
            /**
             * 从 12 元参数数组构造。
             *
             * 【下标口径】**完全沿用** `PetView.bakeDraw` 的既有约定：
             * `[0]phase [1]jumpT [2]dragLift [3]idleLiftPx [4]idleScaleY
             *  [5]landT [6]lean [7]tilt [8]保留 [9]blinkT [10]idleEyeScale [11]gaze`
             * （9/10/11 只影响网格顶点，不影响几何变换，故本类不取）。
             */
            fun ofArray(v: FloatArray): Params {
                // 【为什么要防越界】数组来自 `bakeDraw` 的调用方，历史上出现过
                //   「传了个短数组」的调用；越界会抛 ArrayIndexOutOfBounds 直接崩在
                //   烘帧线程里（而崩在后台线程最难定位）。缺位一律取安全默认：
                //   jumpT/landT 取 -1（表示「未在跳跃/落地」）、其余取中性值。
                fun at(i: Int, def: Float): Float = if (i < v.size) v[i] else def
                return Params(
                    phase = at(0, 0f),
                    jumpT = at(1, -1f),
                    dragLift = at(2, 0f),
                    idleLiftPx = at(3, 0f),
                    idleScaleY = at(4, 1f),
                    landT = at(5, -1f),
                    lean = at(6, 0f),
                    tilt = at(7, 0f)
                )
            }
        }
    }

    /**
     * 应用到 Canvas（`onDraw` 与 `bakeDraw` **共用这一份序列**）。
     *
     * 【调用前提】调用方已 `canvas.save()`；本方法只做变换、不负责 restore，
     *   与改造前两处调用点的既有写法一致（那两处都是 save → 变换 → draw → restore）。
     *
     * 【顺序必须与改造前逐行一致】`translate → scale → rotate → rotate → translate`。
     *   顺序一变，结果就变（Canvas 的 translate/scale/rotate 是 preConcat，
     *   先调用的在更外层）。
     */
    fun applyTo(canvas: Canvas) {
        // 烘帧口径：先把「精灵左上角」从窗口局部坐标搬到画布 (ox, oy)。
        //   onDraw 口径下 canvasOffsetX/Y 均为 0，这一步等价于空操作 —— 于是两条
        //   链走的是**同一份代码**，而不是「看起来一样的两份代码」。
        if (canvasOffsetX != 0f || canvasOffsetY != 0f) {
            val dx = pivotX - (petWidth / 2.0f) - canvasOffsetX
            val dy = pivotY - fullHeight - canvasOffsetY
            canvas.translate(-dx, -dy)
        }
        canvas.translate(pivotX, ((bobOffset + pivotY) - jumpOffset) - lift)
        canvas.scale(scaleX, scaleY)
        canvas.rotate(rotLean)
        canvas.rotate(rotTilt)
        canvas.translate((-petWidth) / 2.0f, -fullHeight)
    }

    /**
     * 等价矩阵（`bakeBounds` 测量用 / 单测用）。
     *
     * 【只放四层】`S · R6 · R7 · T3` —— 与 `bakeBounds` 改造前的矩阵逐层一致。
     *   【为什么不含 (pvx, P)】完整链是 `T(-dx,-dy) · T(pvx,P) · S · R·R · T(-pw/2,-fh)`；
     *   展开点变换后 `(pvx, P)` 与 `T(-dx,-dy)` 里的 `-pvx` / `+fh` 互相抵消，
     *   净效果就是「矩阵结果 + [boundsOffsetX]/[boundsOffsetY]」。
     *   多加一层 `(pvx, P)` 会让结果整体再偏一次，人偶被推出画布。
     *
     * 【必须用 postXxx 而不是 preXxx】Canvas 的 translate/scale/rotate 是 preConcat
     *   （先调用的在更外层）；Matrix 里与之等价的是 **postConcat**（M' = M × X），
     *   所以要用 post* 并且**调用顺序与 Canvas 逐行一致** —— 这正是当年事故的根因，
     *   现在这一条知识只存在于本方法里。
     */
    fun toMatrix(): Matrix {
        val m = Matrix()
        m.postScale(scaleX, scaleY)
        m.postRotate(rotLean)
        m.postRotate(rotTilt)
        m.postTranslate((-petWidth) / 2.0f, -fullHeight)
        return m
    }

    /** [toMatrix] 之后需要补的横向平移（见 [toMatrix] 的推导）。 */
    fun boundsOffsetX(): Float {
        return petWidth / 2.0f
    }

    /** [toMatrix] 之后需要补的纵向平移（见 [toMatrix] 的推导）。 */
    fun boundsOffsetY(): Float {
        return fullHeight + bobOffset - jumpOffset - lift
    }

    /**
     * 映射包围盒（`bakeBounds` 用）—— 与 [applyTo] **严格同源**：
     *   同一个矩阵 + 同一对 offset，绝无第二套计算。
     */
    fun mapBounds(out: RectF, src: RectF) {
        toMatrix().mapRect(out, src)
        out.offset(boundsOffsetX(), boundsOffsetY())
    }

    /**
     * 表情符号的落点（`drawEmote` / `drawEmoteAt` 用）。
     *
     * 【为什么放这里】表情不在网格顶点里，`bakeBounds` 必须单独并进来，
     *   否则头顶的「！」会被画布裁掉。它的落点也是几何的一部分，同样不该有第二份实现。
     *
     * @return FloatArray[2] = [中心 x, 基线 y]，均为画布坐标。
     */
    fun emoteAnchor(pwEmoteU: Float, dp6: Float, dp10: Float, t: Float): FloatArray {
        // 中心 x = pw * AHOGE_U + pw/2
        // 基线 y = (sin - sin2 - lift) + dp(6) - dp(10) * t
        val ex = (petWidth * pwEmoteU) + petWidth / 2.0f
        val ey = (bobOffset - jumpOffset - lift) + dp6 - dp10 * t
        return floatArrayOf(ex, ey)
    }

    /**
     * 表情符号的包围盒（`bakeBounds` 用）。
     *
     * 【为什么用 t=1 的最极端位置】表情会上飘，取「飘到最高」那一刻才算得住，
     *   否则包围盒偏小、烘帧时会把符号顶部裁掉。改造前就是这么取的，逐字保留。
     */
    fun emoteBounds(out: RectF, pwEmoteU: Float, size: Float, dp6: Float, dp10: Float) {
        val a = emoteAnchor(pwEmoteU, dp6, dp10, 1.0f)
        // 字形以基线为原点，向上约 1.4 个字高、向下约 0.5 个字高，横向左右各留 1 个字宽。
        val er = RectF(a[0] - size, a[1] - size * 1.4f, a[0] + size, a[1] + size * 0.5f)
        out.union(er)
    }

    companion object {

        /**
         * 从「已算好的各项」构造 —— 唯一入口，三个口径（draw / bake / bounds）都走它。
         *
         * 【为什么不让调用方各传各的】改造前的三处各自推导 sin/squash/lift，
         *   公式复制了三遍。这里把公式收在一处，调用方只提供原始状态量。
         *
         * @param params   动画参数
         * @param metrics  几何度量（宽高 / 锚点 / 振幅）
         * @param flipped  是否水平镜像（面朝方向）
         * @param sinPhase 已算好的 `sin(phase)`（onDraw 传入实时相位、bake 传入参数相位）
         */
        fun build(
            params: Params,
            metrics: PetMetrics,
            flipped: Boolean,
            sinPhase: Double
        ): PetTransform {
            val pw = metrics.petWidth()
            val fh = metrics.fullHeight()
            val bob = Math.sin(sinPhase).toFloat() * metrics.bobAmp()
            val jump = if (params.jumpT >= 0.0f) {
                Math.sin(params.jumpT * Math.PI).toFloat() * metrics.jumpAmp()
            } else {
                0.0f
            }
            // 落地压扁：landT >= 0 时按正弦压扁，否则不压。
            val squash = if (params.landT >= 0.0f) {
                1.0f - (Math.sin(params.landT * Math.PI).toFloat() * metrics.ahogeRu())
            } else {
                1.0f
            }
            // 【r18·原比例滑动】拖动不再叠加整体缩放（原 pop = dragLift*0.05+1 会放大 5%，
            //   与待机呼吸的瞬跳叠加后被用户感知为「先缩小再放大」）。
            //   拖动的「被拎起来」手感由 lift（抬升位移）与 tilt（倾斜）表达，比例恒为 1。
            val pop = 1.0f
            val sy = squash * pop * (if (params.idleScaleY > 0.0f) params.idleScaleY else 1.0f)
            val lift = (params.dragLift * metrics.dragTiltMaxPx()) + params.idleLiftPx
            return PetTransform(
                scaleX = if (flipped) -pop else pop,
                scaleY = sy,
                rotLean = params.lean,
                rotTilt = params.tilt,
                pivotX = metrics.pivotLocalX(),
                pivotY = metrics.pivotLocalY(),
                bobOffset = bob,
                jumpOffset = jump,
                lift = lift,
                petWidth = pw,
                fullHeight = fh,
                canvasOffsetX = 0.0f,
                canvasOffsetY = 0.0f
            )
        }

        /**
         * 指定画布落点（烘帧口径）。
         *
         * 【与 [build] 的关系】变换参数完全一致，只是把「精灵左上角」搬到 (ox, oy)。
         *   改造前这一步是 `bakeDraw` 自己算的 `-dx/-dy`，现在收进本类 ——
         *   于是烘帧与实时绘制**走的是同一份 applyTo**，不可能再出现「只改了一处」。
         */
        fun buildForBake(
            params: Params,
            metrics: PetMetrics,
            sinPhase: Double,
            ox: Float,
            oy: Float
        ): PetTransform {
            val base = build(params, metrics, flipped = false, sinPhase = sinPhase)
            return PetTransform(
                scaleX = base.scaleX,
                scaleY = base.scaleY,
                rotLean = base.rotLean,
                rotTilt = base.rotTilt,
                pivotX = base.pivotX,
                pivotY = base.pivotY,
                bobOffset = base.bobOffset,
                jumpOffset = base.jumpOffset,
                lift = base.lift,
                petWidth = base.petWidth,
                fullHeight = base.fullHeight,
                canvasOffsetX = ox,
                canvasOffsetY = oy
            )
        }
    }
}