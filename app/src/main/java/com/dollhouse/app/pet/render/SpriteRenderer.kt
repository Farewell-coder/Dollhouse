package com.dollhouse.app.pet.render

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * 【职责】「网格形变」渲染器 —— 把**现有**的 Sprite 绘制管线包装成 [PetRenderer]。
 *
 * 【为什么是「包装」而不是重写】现有的网格形变管线（28×36 网格 + 12 参数曲线 +
 *   三条几何链）是这个 App 的视觉核心，行为已在真机上验证过多轮。
 *   本次重构的目标是**把地基抽象出来**，不是换掉渲染本身 ——
 *   所以本类把「绘制」委托给宿主提供的回调，自己只负责生命周期与状态。
 *
 * 【为什么绘制要委托出去】真正的绘制代码（`drawBitmapMesh` + 网格顶点计算）
 *   与 `PetView` 的字段（`verts` / `bmpPaint` / `expression` / `phase`…）**深度耦合**，
 *   强行搬出来会把状态也一起搬、风险远大于收益。
 *   这里用「委托」拿到**同一份几何事实来源**（`PetTransform`）与**同一套帧率策略**
 *   （`PetFramePolicy`），这才是本次要收敛的东西。
 *
 * 【与 PetView 的关系】PetView 仍是 Sprite 引擎的实际执行者；
 *   本类提供统一的**对外身份**，使「加引擎」这件事在调用方看来是一条路。
 */
class SpriteRenderer : PetRenderer {

    private var host: PetDisplayHost? = null
    private var source: PetSource.Sprite? = null
    private var err: String? = null
    private var playing = false
    private var released = false

    /** 实际绘制委托（由宿主注入；不注入时本渲染器只做尺寸/生命周期管理）。 */
    var drawDelegate: ((Canvas, Bitmap) -> Unit)? = null

    override fun engine(): String = Engine.SPRITE

    override fun prepare(source: PetSource): Boolean {
        err = null
        if (source !is PetSource.Sprite) {
            err = "Sprite 引擎只接受位图资源，收到的是：" + source.describe()
            return false
        }
        if (source.bitmap.isRecycled) {
            err = "位图已被回收，无法作为人偶素材"
            return false
        }
        if (source.bitmap.width <= 0 || source.bitmap.height <= 0) {
            err = "位图尺寸非法：" + source.bitmap.width + "×" + source.bitmap.height
            return false
        }
        this.source = source
        return true
    }

    override fun lastError(): String? = err

    override fun attach(host: Any?) {
        // 宿主身份按能力识别；不是 PetDisplayHost 就退化为「无宿主，不主动要求重绘」。
        this.host = host as? PetDisplayHost
    }

    override fun size(): IntArray {
        val s = source ?: return intArrayOf(0, 0)
        return intArrayOf(s.bitmap.width, s.bitmap.height)
    }

    /**
     * 推进一帧。
     *
     * 【Sprite 引擎不需要这里做时间推进】呼吸/眨眼/拖拽的相位由 `PetAnimator`
     *   驱动（它已经在跑，且带着 delta 上限 120ms 等既有约束）。
     *   本方法只负责「告诉宿主该重绘了」。
     */
    override fun advance(nowMs: Long) {
        if (released || !playing) {
            return
        }
        host?.requestDraw()
    }

    /** Sprite 引擎的「活跃」由 PetAnimator 决定，这里保守返回「播放中」。 */
    override fun isActive(): Boolean = playing && !released

    override fun play() {
        if (released) {
            return
        }
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun resume() {
        if (released) {
            return
        }
        playing = true
    }

    /** 幂等释放。**不回收 Bitmap** —— 位图归素材池所有，渲染器只是借用者。 */
    override fun release() {
        if (released) {
            return
        }
        released = true
        playing = false
        drawDelegate = null
        source = null
        host = null
    }

    override fun draw(canvas: Canvas) {
        if (released) {
            return
        }
        val bmp = source?.bitmap ?: return
        drawDelegate?.invoke(canvas, bmp)
    }
}