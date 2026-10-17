package com.dollhouse.app.pet.render

/**
 * 【职责】人偶几何度量契约 —— 渲染层向宿主索要的「尺寸与锚点」信息。
 *
 * 【为什么单独抽出来】改造前 `PetView` 的几何计算直接读自己的字段
 *   （`petWidth()` / `fullHeight()` / `pivotLocalX()` / `pivotLocalY()` /
 *   `bobAmp` / `jumpAmp` / `AHOGE_RU` / `DP(DRAG_TILT_MAX)`），
 *   于是几何推导与视图实例焊死，既无法单测，也无法让别的渲染器复用同一套口径。
 *   抽成接口后：[PetTransform] 只依赖这几个数，谁实现都行 —— 于是几何能脱离
 *   Android 的 Canvas/View 在 JVM 上单测。
 *
 * 【实现者】改造后由 `PetView` 实现（它本来就是这些值的来源）；日后若出现
 *   别的宿主（预览窗、编辑器画布），按同一口径实现即可，几何结果自动一致。
 *
 * 【单位约定】全部为像素（px）。`pivotLocal*` 是**窗口局部坐标**下的锚点。
 */
interface PetMetrics {

    /** 人偶本体宽（已含当前缩放率）。 */
    fun petWidth(): Float

    /** 人偶全高（含头顶呆毛等装饰的完整高度）。 */
    fun fullHeight(): Float

    /** 锚点 X（窗口局部坐标）—— 变换链里「旋转/缩放的支点」横向位置。 */
    fun pivotLocalX(): Float

    /** 锚点 Y（窗口局部坐标）—— 注意传入的是**未叠加呼吸/跳跃/抬升**的基础值。 */
    fun pivotLocalY(): Float

    /** 呼吸位移振幅（px）：`sin(phase) * bobAmp()`。 */
    fun bobAmp(): Float

    /** 跳跃位移振幅（px）：`sin(jumpT * π) * jumpAmp()`。 */
    fun jumpAmp(): Float

    /** 落地压扁系数（对应 PetView 的 `AHOGE_RU`）。 */
    fun ahogeRu(): Float

    /** 拖拽抬升的最大像素数（对应 `dp(DRAG_TILT_MAX)`）。 */
    fun dragTiltMaxPx(): Float
}