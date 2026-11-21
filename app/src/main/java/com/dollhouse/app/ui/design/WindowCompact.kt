package com.dollhouse.app.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 【职责】「紧凑高度」判定：窗口高度不足时把页面切到更省垂直空间的排布。
 *
 * 【为什么需要】本工程此前**零尺寸适配**（全仓无 `WindowSizeClass`、无 `sw600dp`、
 *   `res/` 无 `layout-sw600dp` / `values-land`）。手机横屏（如 915×412）时垂直空间被压到
 *   412dp，聊天页一屏只剩 2 条消息，而横屏**宽度是充足的** —— 需要收紧的只有纵向间距，
 *   不动横向。
 *
 * 【为什么阈值取 480dp】来自 dsh-Go 的既有口径：窗口高 < 480dp 一律按「手机紧凑档」处理。
 *   480 落在「竖屏手机（≥640dp 高）之下、横屏手机（约 360~420dp 高）」之上，
 *   只命中横屏与小屏，不会误伤任何竖屏机型。
 *
 * 【为什么不做成 CompositionLocal 而是普通可组合派生值】本判定只依赖
 *   `LocalConfiguration.screenHeightDp`（系统会随旋转/分屏/字号变化触发重组），
 *   不需要跨层注入；直接读 Configuration 已是 Compose 的标准且零依赖的做法。
 *   名字沿用方案里的 `LocalCompactHeight`，但它是**派生属性**而非可写的 CompositionLocal，
 *   避免误导调用方以为可以 provide 一个假值。
 *
 * 【用法】
 * ```kotlin
 * val compact = LocalCompactHeight
 * contentPadding = PaddingValues(vertical = if (compact) 4.dp else 8.dp)
 * ```
 */
const val COMPACT_HEIGHT_DP = 480

/**
 * 纯函数版：给定屏幕高度（dp）是否为紧凑档。
 *
 * 【为什么同时保留纯函数】方便单元测试与非 Composable 场景（例如 View 侧 / 计算逻辑）
 *   复用同一判据，不必为了一个比较去搭 Compose 运行时。
 */
fun isCompactHeight(screenHeightDp: Int): Boolean = screenHeightDp < COMPACT_HEIGHT_DP

/**
 * 当前窗口是否为「紧凑高度」（横屏 / 小屏）。
 *
 * 只读 [LocalConfiguration]，不引入任何状态；Compose 会在配置变化时自动重组。
 */
val LocalCompactHeight: Boolean
    @Composable
    @ReadOnlyComposable
    get() = isCompactHeight(LocalConfiguration.current.screenHeightDp)
