package com.dollhouse.app.ui.qzx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】新系统的**配色入口**。
 *
 * ## 为什么不引入旧 Compose 层的 `DhTokens`
 * `ui.compose.DhTokens` 是旧系统的东西（它自己还套着一层 `toGlass` / `subOnPhoto` 的
 * 压图逻辑）。新系统只在**最底层的调色板**上取色 —— `ui.theme.UiKit` 是全工程唯一真源
 * （View 与 Compose 共用），所以新系统与旧 UI 只共享"颜色"，不共享任何组件。
 *
 * ## 为什么全是 `get()`
 * `UiKit.ACC` 这些是 `var`（主题切换时被重新赋值）。用 `get()` 让每次读取都拿到最新值，
 * 而不是在类初始化时抓一份快照。代价是每次读都过一层 getter —— 可以忽略。
 *
 * 【主题切换的重组】本文件不额外制造可观察状态；主题切换由既有机制（`DhTheme.bump()` /
 *   页面重建）触发整棵子树重组，届时这些 `get()` 会返回新值。
 */
object QzxColors {

    /** 页面底色。 */
    val bg: Color get() = Color(UiKit.BG)

    /** 卡片底。 */
    val card: Color get() = Color(UiKit.CARD)

    /** 主文字。 */
    val title: Color get() = Color(UiKit.TITLE)

    /** 副文字 / 值。 */
    val sub: Color get() = Color(UiKit.SUB)

    /** 主色（按钮底、强调）。 */
    val accent: Color get() = Color(UiKit.ACC)

    /** 主色上的文字。 */
    val onAccent: Color get() = Color(UiKit.ON_ACC)

    /** 输入框底。 */
    val field: Color get() = Color(UiKit.FIELD)

    /** 略深的软底（次级按钮、chip）。 */
    val soft: Color get() = Color(UiKit.SOFT)

    /** 发丝线 / 描边。 */
    val line: Color get() = Color(UiKit.LINE)

    /** 半透明描边（浅色主题下卡片那 1dp）。 */
    val stroke: Color get() = Color(UiKit.STROKE)

    /** 危险 / 删除。 */
    val danger: Color get() = Color(UiKit.ERR)

    /** 成功。 */
    val ok: Color get() = Color(UiKit.OK)

    /** 当前是否深色主题（含纯黑主题）。 */
    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = ThemeManager.isDark(LocalContext.current)
}

/**
 * 把颜色按 HSV 的 **V 通道**压暗 [factor] 倍，色相与饱和度不动。
 *
 * 【为什么不用 `copy(alpha = …)` 或简单地乘 RGB】乘 RGB 会同时压低饱和度，
 * 深色按钮按压后会"发灰"；只压 V 是 QZX `qzxButtonTouchFeedbackColor` 的做法，
 * 观感是"同一块颜色被按住、光少了一点"，而不是"换了一种颜色"。
 */
internal fun darkenValue(color: Color, factor: Float): Color {
    val hsv = FloatArray(3)
    val argb = color.toArgbCompat()
    android.graphics.Color.colorToHSV(argb, hsv)
    hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(android.graphics.Color.alpha(argb), hsv))
}

/** `Color.toArgb()` 的兼容写法（避免依赖 `androidx.compose.ui.graphics.toArgb` 的导入名在不同版本变化）。 */
private fun Color.toArgbCompat(): Int =
    android.graphics.Color.argb(
        (alpha * 255f + 0.5f).toInt().coerceIn(0, 255),
        (red * 255f + 0.5f).toInt().coerceIn(0, 255),
        (green * 255f + 0.5f).toInt().coerceIn(0, 255),
        (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    )
