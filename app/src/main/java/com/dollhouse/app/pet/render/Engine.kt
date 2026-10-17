package com.dollhouse.app.pet.render

import android.graphics.Bitmap

/**
 * 【职责】引擎标识常量 —— 全工程**只用这一份**引擎名。
 *
 * 【为什么单独成文件】改造前「引擎」这个概念根本不存在（只有一种渲染方式），
 *   于是字符串会散落在：数据层的默认值、注册表的 key、模型包 manifest 的字段、
 *   校验器的白名单、UI 的下拉项……**五处各写一遍字面量**。
 *   集中到本类后，加引擎只需在此加一个常量，其余各处引用它即可。
 *
 * 【为什么不写成 enum】引擎名要落进 JSON（`Action.engine`）与模型包 manifest
 *   （`manifest.json` 的 `engine` 字段），这两处都是跨版本、跨工具的字符串契约。
 *   enum 的 `name` 也能序列化，但一旦有人重命名枚举项，**旧档案就读不出来了**；
 *   字符串常量可以随时加注释说明「这个值不能改」。
 */
object Engine {

    /** 现有网格形变管线（`drawBitmapMesh` + 28×36 网格 + 12 参数曲线）。 */
    const val SPRITE = "sprite"

    /** Lottie 矢量动画（`LottieDrawable` 原生播放）。 */
    const val LOTTIE = "lottie"

    /** 系统原生动图（GIF / WebP / APNG）。 */
    const val ANIMIMAGE = "animimage"

    /** Live2D（**仅声明契约，未实现** —— 官方 SDK 许可非自由分发，见交付报告 04）。 */
    const val LIVE2D = "live2d"

    /** 全部已登记的引擎名（供校验器 / UI 列举）。 */
    val ALL: List<String> = listOf(SPRITE, LOTTIE, ANIMIMAGE)

    /** 引擎是否可用（Live2D 只有契约没有实现，故不在 [ALL] 里）。 */
    fun supported(engine: String?): Boolean {
        return engine != null && ALL.contains(engine)
    }
}