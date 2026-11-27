package com.dollhouse.app.ui.qzx

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dollhouse.app.R

/**
 * 新 UI 系统自己的字族（替代旧系统 Compose 层的常规/加粗字族）。
 *
 * ## 为什么自带一份
 *
 * 旧字族来自旧系统 Compose 层的主题对象（两个 CompositionLocal）——
 * 属于旧系统。新包 import 它，删旧系统时新包会一起死。这里直接按字体资源自建，
 * **不引用旧包任何符号**。
 *
 * ## 字族分工（沿用既有定案）
 *
 * | 用途 | 取哪个 | 说明 |
 * |---|---|---|
 * | 大标题 / 人偶名 / App 名 | [bold] | 霞鹜文楷，情感载体 |
 * | 正文 / 说明 / 列表 | 系统默认 | 11~15sp 小字更清晰（设计稿 §1「品牌字体是调味料，不是主食」） |
 *
 * ## ⚠️ [bold] 是「合成粗体」，取它就不要再写 `fontWeight`
 *
 * 做法与旧实现一致：**同一份 ttf 以 `FontWeight.Bold` 声明一次**。
 * Compose 在找不到真正的粗体字重时按该声明合成加粗。
 * 若调用方再写 `fontWeight = FontWeight.Bold`，会**叠成双粗体**（笔画糊、字面变胖）。
 *
 * 【为什么只在本文件里 `runCatching`】字体资源缺失（裁剪版 APK / 资源被误删）时，
 *   降级到 `FontFamily.Default` 比整页崩掉好；但只在构造期兜一次，不污染调用点。
 */
object QzxFonts {

    /**
     * 品牌字族（文楷，常规）。
     *
     * 【为什么是 `by lazy` 而不是组合期 `remember`】`FontFamily` 是不可变的，
     *   与主题/密度无关 —— 没必要放进组合作用域，进程内建一次即可。
     *   （旧实现放在 `LocalDhFontFamily` 里，是因为它还要按 `ctx` 做安全加载；
     *   本包没有那层需求。）
     */
    val normal: FontFamily by lazy {
        runCatching { FontFamily(Font(R.font.lxgw_wenkai)) }
            .getOrElse { FontFamily.Default }
    }

    /** 品牌字族（文楷，合成粗体）。用法见类注释的「不要再写 fontWeight」。 */
    val bold: FontFamily by lazy {
        runCatching { FontFamily(Font(R.font.lxgw_wenkai, weight = FontWeight.Bold)) }
            .getOrElse { FontFamily.Default }
    }
}
