package com.dollhouse.app.ui.compose

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.Fonts
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】Compose 侧的设计令牌快照 + 主题分发。
 *
 * 【唯一真源】所有颜色都从 [UiKit] 的可变静态字段现读，Compose 侧**不维护第二份配色**。
 *   这是本工程「外观系统全局同步」的命门：`ThemeManager.apply()` 覆写 UiKit 的 29 个字段后，
 *   只要触发一次重组，所有 Compose 页与残留 View 页、桌宠、裁剪页取到的就是同一套色。
 *   若 Compose 侧自建 Material3 ColorScheme 当第二真源，必然出现「Compose 页一个色、
 *   桌宠/裁剪页另一个色」的撕裂 —— 规格书明令禁止。
 *
 * 【为什么不直接用 MaterialTheme】Material3 的 ColorScheme 有自己的一套语义槽位
 *   （primary / surface / onSurface...），与本工程的 ACC / CARD / TITLE 不是一一对应；
 *   硬套会把「莫奈 29 色调色板」这个既有强项压扁。故这里只借用 Material3 的**组件实现**，
 *   颜色一律走本文件的 [DhColors]。
 *
 * 【重组触发】UiKit 字段是普通 var，赋值不会通知 Compose。就地换主题（[ThemeRefresh.applyInPlace]）
 *   改完色后调用 [DhTheme.bump]，顶层 [DollhouseTheme] 订阅该版本号并整体重组一次。
 */
@Immutable
data class DhColors(
    val acc: Color,
    val acc2: Color,
    val card: Color,
    /** 【r13】卡片内嵌实体按钮底（比 [card] 实两档，卡片半透明时按钮不「消失」）。 */
    val cardUp: Color,
    /**
     * 【r13】弹窗 / 底部面板底：**不透明**，按用户「磨砂程度」调质感。
     * 【为什么必须与 [card] 分开】[card] 跟着卡片透明度走，弹窗若用它就会透出背后正文，
     *   与弹窗文字叠成一片（用户报过）。见 `UiKit.sheet()`。
     */
    val sheet: Color,
    /** 【r13】弹窗内的实体按钮底：与 [sheet] 同色系但更实，保证按钮浮得出来。 */
    val sheetUp: Color,
    /** 【r13·底栏】悬浮胶囊底（半透明主题色），两处底栏共用。 */
    val capsule: Color,
    /** 【r13·底栏】胶囊内选中项高亮块底。 */
    val capsuleSel: Color,
    val bg: Color,
    val title: Color,
    val sub: Color,
    val line: Color,
    val option: Color,
    val soft: Color,
    val field: Color,
    val ok: Color,
    val err: Color,
    val onAcc: Color,
    val chatBubbleUser: Color,
    val chatBorder: Color,
    val chatChipBg: Color,
    val chatChipFg: Color,
    val chatChipOn: Color,
    val chatChipOff: Color,
    val chatChipMute: Color,
    val chatActionBg: Color,
    val hintFg: Color,
    val hintBg: Color,
    val switchOff: Color,
    /** 【v0.1.4】开关开启态强调色（薄荷绿，独立于主色）。 */
    val switchOn: Color,
    val emote1: Color,
    val emote2: Color,
    val emote3: Color,
    val stroke: Color,
    val scrim: Color
) {
    companion object {
        /** 从 [UiKit] 现读一份快照。字段顺序与 `UiKit.snapshotPalette()` 保持一致的语义分组。 */
        fun fromUiKit(): DhColors {
            return DhColors(
                acc = Color(UiKit.ACC),
                acc2 = Color(UiKit.ACC2),
                card = Color(UiKit.card()),
                cardUp = Color(UiKit.cardUp()),
                // 【r13】弹窗 / 面板底：不透明 + 磨砂质感，见 UiKit.sheet()。
                sheet = Color(UiKit.sheet()),
                sheetUp = Color(UiKit.sheetUp()),
                // 【r13·底栏】两处悬浮胶囊底栏共用同一组令牌，避免再次分叉。
                capsule = Color(UiKit.capsuleFill()),
                capsuleSel = Color(UiKit.capsuleSel()),
                bg = Color(UiKit.BG),
                title = Color(UiKit.TITLE),
                sub = Color(UiKit.SUB),
                line = Color(UiKit.LINE),
                // 【r13】三个层级底走派生方法而不是裸常量：卡片透明度改变时，
                //   它们跟着一起透、但永远比卡片实一档，层级关系在任何透明度下都成立。
                //   （调用点因此零改动 —— 全 App 的 c.soft / c.option / c.field 自动跟随。）
                option = Color(UiKit.option()),
                soft = Color(UiKit.soft()),
                field = Color(UiKit.field()),
                ok = Color(UiKit.OK),
                err = Color(UiKit.ERR),
                onAcc = Color(UiKit.ON_ACC),
                chatBubbleUser = Color(UiKit.CHAT_BUBBLE_USER),
                chatBorder = Color(UiKit.CHAT_BORDER),
                chatChipBg = Color(UiKit.CHAT_CHIP_BG),
                chatChipFg = Color(UiKit.CHAT_CHIP_FG),
                chatChipOn = Color(UiKit.CHAT_CHIP_ON),
                chatChipOff = Color(UiKit.CHAT_CHIP_OFF),
                chatChipMute = Color(UiKit.CHAT_CHIP_MUTE),
                chatActionBg = Color(UiKit.CHAT_ACTION_BG),
                hintFg = Color(UiKit.HINT_FG),
                hintBg = Color(UiKit.HINT_BG),
                switchOff = Color(UiKit.SWITCH_OFF),
                switchOn = Color(UiKit.SWITCH_ON),
                emote1 = Color(UiKit.EMOTE_1),
                emote2 = Color(UiKit.EMOTE_2),
                emote3 = Color(UiKit.EMOTE_3),
                stroke = Color(UiKit.STROKE),
                scrim = Color(UiKit.SCRIM)
            )
        }
    }
}

/**
 * Compose 侧主题版本号。
 *
 * 【为什么需要】`UiKit` 的颜色字段是 `@JvmField var`（不是 Compose State），赋值不会触发重组。
 *   冷启动路径不需要它（先 apply 再建界面，首帧读到的就是新色）；
 *   只有「就地换主题、不重建 Activity」这条路径需要显式通知 Compose 重新取色。
 */
object DhTheme {
    private val revisionState = mutableStateOf(0)

    /** 当前版本号；Composable 读到它即订阅主题变化。 */
    val revision: Int
        get() = revisionState.value

    /** 就地换主题后调用，通知所有 Compose 内容重新从 [UiKit] 取色。 */
    @JvmStatic
    fun bump() {
        revisionState.value = revisionState.value + 1
    }
}

/** Compose 侧配色（由 [DollhouseTheme] 提供）。 */
val LocalDhColors = compositionLocalOf<DhColors> {
    error("DhColors 未提供：请在 DollhouseTheme 内使用")
}

/** Compose 侧常规字族（LXGW WenKai）。 */
val LocalDhFontFamily = compositionLocalOf<FontFamily> { FontFamily.Default }

/** Compose 侧加粗字族（单字重字体派生合成粗体）。 */
val LocalDhFontFamilyBold = compositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * 【M1·统一设计系统】Compose 侧**唯一**的设计系统令牌集。
 *
 * 【与 [LocalDhColors] 的关系】两者都从 [UiKit] 现读，是同一次计算的两个视图：
 *   · [DhDesign.Palette] 是**新的规范命名**（accent / surface / …），新代码只用它；
 *   · [DhColors] 保留旧字段名（acc / acc2 / …），只为不一次性改掉数百处调用点。
 *   M9 清理时会把 [DhColors] 的调用点全部迁到 [DhDesign.Palette] 并删除旧类型。
 */
val LocalDhPalette = compositionLocalOf<DhDesign.Palette> {
    error("DhPalette 未提供：请在 DollhouseTheme 内使用")
}

/** 当前字体阶梯（随 [DhDesign.Type] 与系统字族快照一起提供）。 */
val LocalDhType = compositionLocalOf<DhDesign.Type> { DhDesign.Type.fallback() }

/** 令牌读取入口：`DhTokens.colors.acc` / `DhTokens.palette.accent` / `DhTokens.type.title`。 */
object DhTokens {
    /** 当前配色（旧命名，兼容既有调用点）。 */
    val colors: DhColors
        @Composable @ReadOnlyComposable
        get() = LocalDhColors.current

    /** 当前配色（规范命名，新代码用这个）。 */
    val palette: DhDesign.Palette
        @Composable @ReadOnlyComposable
        get() = LocalDhPalette.current

    /** 当前字体阶梯。 */
    val type: DhDesign.Type
        @Composable @ReadOnlyComposable
        get() = LocalDhType.current

    /** 常规字族。 */
    val fonts: FontFamily
        @Composable @ReadOnlyComposable
        get() = LocalDhFontFamily.current

    /** 加粗字族。 */
    val fontsBold: FontFamily
        @Composable @ReadOnlyComposable
        get() = LocalDhFontFamilyBold.current
}

/**
 * Compose 内容的主题外壳。所有 Compose 页都必须包在它里面（[ComposeHost] 已自动包裹）。
 *
 * 【一次订阅，整体重组】顶层读 [DhTheme.revision]，换主题时只重组这一层，向下 provide 新快照；
 *   子节点通过 [DhTokens] 读取，不在各自函数里散读 UiKit —— 避免几百个订阅点。
 *
 * 【M1·交付 MaterialTheme】改造前本函数**刻意不建 MaterialTheme**，于是 material3 的
 *   `Text`（39 处）、`SnackbarHost`、`TooltipBox`、`rememberModalBottomSheetState`
 *   全部落到 M3 默认值：默认主色是紫 6200EE、默认正文是 16sp/24、默认圆角 12/16 ——
 *   与本工程的配色和字号阶梯毫无关系，是「页面间字体比例不一致」的另一半根源。
 *   现在建立 **M3 投影**：ColorScheme / Typography / Shapes 全部由 [DhDesign] 从 [UiKit] 派生。
 *   注意这仍然是**投影而非第二真源** —— M3 只是读者，写入者永远是 ThemeManager → UiKit。
 */
@Composable
fun DollhouseTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // 订阅主题版本号：bump 后这里重跑，下面 remember 的 key 变化 → 重新取色。
    val revision = DhTheme.revision
    val palette = remember(revision) { DhDesign.Palette.fromUiKit() }
    val colors = remember(revision) { DhColors.fromUiKit() }
    val family = remember(ctx) { safeFamily(ctx, bold = false) }
    val familyBold = remember(ctx) { safeFamily(ctx, bold = true) }
    // 【字体阶梯】品牌档用霞鹜文楷（情感载体），正文档回落系统无衬线（中文小字更清晰）。
    //   设计稿 §1 明确「品牌字体是调味料，不是主食」：文楷只出现在 App 名、人偶名、头像首字。
    val type = remember(family, familyBold) {
        runCatching { DhDesign.Type.of(brandFamily = family, bodyFamily = FontFamily.Default) }
            .getOrElse { DhDesign.Type.fallback() }
    }
    // 【r12 修 bug·蓝色水滴】文本选择把手与选中底色的主题化。
    //   改造前 M3 的 LocalTextSelectionColors 默认读到 0xFF4285F4（那个蓝），
    //   于是每一个输入框的光标把手都是蓝的。现在色值与 M3 主题都由本层提供，双保险。
    val selection = remember(palette) {
        TextSelectionColors(
            handleColor = palette.accent,
            backgroundColor = palette.accent.copy(alpha = 0.4f)
        )
    }
    val colorScheme = remember(palette) { DhDesign.colorScheme(palette) }
    val typography = remember(type) { DhDesign.typography(type) }
    val shapes = remember { DhDesign.shapes() }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = shapes
    ) {
        CompositionLocalProvider(
            LocalDhColors provides colors,
            LocalDhPalette provides palette,
            LocalDhType provides type,
            LocalDhFontFamily provides family,
            LocalDhFontFamilyBold provides familyBold,
            LocalTextSelectionColors provides selection,
            content = content
        )
    }
}

/**
 * 把工程既有的 [Fonts] Typeface 桥接成 Compose 字族。
 * 【回退】任何异常（ROM 裁剪字体 / 类缺失）都退回系统默认，绝不影响功能 —— 与 [Fonts] 自身口径一致。
 */
private fun safeFamily(ctx: Context, bold: Boolean): FontFamily {
    return try {
        val tf = if (bold) Fonts.uiBold(ctx) else Fonts.ui(ctx)
        FontFamily(tf)
    } catch (ignored: Throwable) {
        FontFamily.Default
    }
}
