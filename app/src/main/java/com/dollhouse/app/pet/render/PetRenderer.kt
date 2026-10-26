package com.dollhouse.app.pet.render

import android.content.Context
import android.graphics.Canvas

/**
 * 【职责】宿主契约 —— 渲染器需要宿主提供的能力。
 *
 * 【为什么只建接口不建新实现】既有 `PetService` / `PetWindow` 已经承担了
 *   「谁在显示、显示在哪、怎么响应触摸」，再建一套会与多实例机制冲突。
 *   故本接口**由它们实现**（或由薄适配器转接），不重复建设显示宿主。
 *
 * 【为什么渲染器需要「问」宿主这些事】渲染器本身不该知道窗口、屏幕、可见性 ——
 *   那些是多实例与窗口管理的领域。它只知道「画什么、什么时候该重绘」；
 *   「现在能不能画」由宿主回答。
 */
interface PetDisplayHost {

    /** 上下文（加载资源用）。 */
    fun context(): Context

    /** 请求宿主重绘（渲染器推进了一帧、内容变了）。 */
    fun requestDraw()

    /** 渲染器自报尺寸变化，由宿主实际应用到窗口。 */
    fun onSizeChanged(w: Int, h: Int)

    /** 当前是否处于前台可见状态。 */
    fun isShown(): Boolean

    /** 屏幕是否亮着。 */
    fun isScreenOn(): Boolean
}

/**
 * 【职责】统一的桌宠渲染契约 —— 不管背后是网格形变、Lottie 还是动图，
 *   调用方只认这几个方法。
 *
 * 【生命周期】**严格按此顺序**：
 * ```
 * prepare(source) → attach(host) → (play / pause / resume)* → release()
 * ```
 * · `prepare` 可能耗时，**允许后台调用**；失败返回 false 并给出原因（[lastError]）。
 * · `release` 之后**不得再调用** play / pause / resume / advance —— 行为未定义。
 * · `release` 必须**幂等**（重复调用不报错、不重复释放）。
 *
 * 【为什么要定义得这么细】这是给第三方看的契约（见交付报告 03/05）：
 *   照着实现就能加引擎，**不需要读本工程源码**。
 */
interface PetRenderer {

    /** 引擎标识（取值见 [Engine]）。 */
    fun engine(): String

    /**
     * 准备资源（可能耗时，允许后台调用）。
     *
     * @return 成功 true；失败 false，原因见 [lastError]。
     */
    fun prepare(source: PetSource): Boolean

    /** 最近一次失败原因（成功时为 null）。 */
    fun lastError(): String?

    /**
     * 绑定到宿主（View / Canvas 的能力提供者）。
     *
     * 【为什么参数是 Any? 而不是 PetDisplayHost】
     *   宿主（`PetView`）位于 `com.dollhouse.app.pet` 包，而本接口位于
     *   `com.dollhouse.app.pet.render` —— 若这里写死 `PetDisplayHost`，
     *   渲染器为了拿宿主就**必须**反向依赖 `pet` 包，形成
     *   `render → pet → render` 的循环。Kotlin 允许包循环，但那意味着
     *   「渲染层可以被单独拿走复用」这件事不成立，也说不清谁依赖谁。
     *   放宽为 `Any?` 后，依赖方向是单向的 `pet → render`；
     *   真正需要宿主能力（重绘 / 屏幕状态）的引擎自己 `as? PetDisplayHost`，
     *   拿不到就退化工作（Lottie 不下发重绘请求而已，不影响正确性）。
     */
    fun attach(host: Any?)

    /**
     * 当前帧尺寸（px）—— **由渲染器自报**，调用方据此算窗口大小。
     *
     * 【为什么要自报】改造前窗口尺寸由 `PetView` 自己算（`petWidth()` / `baseHeight()`），
     *   于是「换个引擎」就意味着窗口尺寸算法也得跟着换。自报之后，
     *   窗口只管用 [IntArray] 里的数字，不懂引擎细节。
     *
     * @return `[宽, 高]`
     */
    fun size(): IntArray

    /** 推进一帧。`nowMs` = 单调时钟毫秒（`SystemClock.uptimeMillis`）。 */
    fun advance(nowMs: Long)

    /** 是否还有动画在跑（供帧率策略判断要不要全速）。 */
    fun isActive(): Boolean

    /** 开始播放。 */
    fun play()

    /** 暂停（保留进度）。 */
    fun pause()

    /** 从暂停恢复。 */
    fun resume()

    /** 释放全部资源（**幂等**）。 */
    fun release()

    /** 画到画布。 */
    fun draw(canvas: Canvas)

    /** 画到宿主统一计算的目标矩形；旧渲染器默认保持原行为。 */
    fun draw(canvas: Canvas, target: android.graphics.Rect) {
        draw(canvas)
    }
}