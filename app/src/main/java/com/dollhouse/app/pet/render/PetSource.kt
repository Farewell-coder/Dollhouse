package com.dollhouse.app.pet.render

import android.graphics.Bitmap

/**
 * 【职责】资源句柄 —— 渲染器**不关心资源从哪来**，只认这个句柄。
 *
 * 【为什么需要它】改造前 `PetView` 直接持有 `Bitmap`，于是渲染逻辑被「图像来源」绑死：
 *   想加一种新资源（Lottie 的 .json、GIF 文件）就得改 `PetView` 的成员类型，
 *   连带三条几何链一起动。改用句柄后：**悬浮窗 / 预览 / 编辑页三个显示场
 *   共用同一套渲染逻辑**，加资源类型只是加一个子类。
 *
 * 【为什么用 sealed class】资源类型是**封闭集合**（当前 3 种，日后加 Live2D 也只多一种），
 *   `when` 分支能被编译器查穷尽 —— 加子类时所有需要处理的地方会**编译报错**，
 *   而不是运行时悄悄走进 `else`。这与工程「不制造静默失败」的口径一致。
 */
sealed class PetSource {

    /** 位图（现有网格形变管线的输入）。 */
    class Sprite(val bitmap: Bitmap) : PetSource()

    /** Lottie 动画文件（`.json`，可含内嵌图片或同目录的 images/ 资源）。 */
    class Lottie(val jsonPath: String) : PetSource()

    /** 系统原生动图文件（GIF / WebP / APNG）。 */
    class AnimatedImage(val filePath: String) : PetSource()

    /** 人类可读的来源描述（供错误提示 / 资源管理界面显示）。 */
    fun describe(): String = when (this) {
        is Sprite -> "位图 " + bitmap.width + "×" + bitmap.height
        is Lottie -> "Lottie 动画"
        is AnimatedImage -> "动图"
    }
}