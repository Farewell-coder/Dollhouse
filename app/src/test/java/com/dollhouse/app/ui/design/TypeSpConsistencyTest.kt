package com.dollhouse.app.ui.design

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 【职责】[DhDesign.TypeSp] 与 [DhDesign.Type.of] 的字号一致性守卫。
 *
 * 【为什么必须有这个测试 —— 004 点出的唯一漂移风险】
 *   [DhDesign.TypeSp] 之所以存在，是因为 Kotlin 无法用 `const val` 引用
 *   `Type.body.fontSize`（实例属性 / 需运行时 FontFamily / TextUnit 不是 Float），
 *   所以只能把 8 档字号**手抄**成一组 `const val`。手抄就有漂移：
 *   改 [DhDesign.Type.of] 里的 `fontSize = 15.sp` 时忘了同步 `TypeSp.BODY = 15f`，
 *   两边从此不一致 —— 而**编译器不会报错**，界面上也未必立刻看得出。
 *
 *   004 在 TypeSp 的 KDoc 里写死了「必须同步 + 校验命令」，但那是文档约束，
 *   不是强制约束。本测试把那条文档约束变成**可执行断言**：
 *   CI 跑 `./gradlew test` 时若两边不等，直接红。
 *
 * 【为什么要 Robolectric 而不是裸 JVM 单测】
 *   构造 `TextStyle` / `sp` 走的是 androidx.compose.ui.text（aar 里的类）。
 *   本工程 `testOptions` **没有** 开 `unitTests.returnDefaultValues`，
 *   裸 JVM 测试一旦碰到 Android 桩方法就会抛 `RuntimeException: Stub!`。
 *   全仓目前唯一的 compose 相关单测（`DesignScreenshotTest`）也是
 *   `@RunWith(RobolectricTestRunner::class)`，此处沿用同一套 runner，
 *   保证在 CI 里走的是「有 Android 运行时的 JVM」。
 *   代价：比裸 JUnit 慢几秒；换来的是一致性守卫不会因环境差异假红。
 *
 * 【本测试管不到的部分（E15 静态门禁补上）】
 *   若将来 [DhDesign.Type] 新增第 9 档（例如 `hero`）而 TypeSp 忘了加常量，
 *   本测试**不会**自动报警 —— 它只逐条比对已列出的 8 对。
 *   这类「漏加一档」由 `tools/kotlin_precheck.py` 的 E15 覆盖（两边档数也要相等）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypeSpConsistencyTest {

    /**
     * 构造字号阶梯。
     *
     * 【为什么可以随便传字族】本测试只比 `fontSize`，字族不参与；
     *   用 `FontFamily.Default` 即可拿到全部 8 档（与 `Type.fallback()` 同一条路径）。
     */
    private fun type(dark: Boolean = false): DhDesign.Type =
        DhDesign.Type.of(FontFamily.Default, FontFamily.Default, dark)

    /**
     * 【核心守卫】8 档字号必须与 [DhDesign.TypeSp] 逐一相等。
     *
     * 【失败时怎么读】消息里写了档位名；改字号时看到 `body（TypeSp.BODY）` 这条红，
     *   就说明 `Type.of` 与 `TypeSp` 有一边改了、另一边没跟上。
     *   修法二选一：把改的那边改回去，或把 `TypeSp` 同步成新值（**改字号就该同步**）。
     */
    @Test
    fun `TypeSp 与 Type 各档字号必须逐一相等`() {
        val t = type()
        // 【delta 取 1e-6】两边都是「n.sp」与「n f」的字面量，理论上是精确相等；
        //   留一丝容差只为避开浮点比较的写法争议，不影响判据（差 1sp 就是 1.0f）。
        assertEquals("brand（TypeSp.BRAND）", DhDesign.TypeSp.BRAND.toDouble(), t.brand.fontSize.value.toDouble(), 1e-6)
        assertEquals("display（TypeSp.DISPLAY）", DhDesign.TypeSp.DISPLAY.toDouble(), t.display.fontSize.value.toDouble(), 1e-6)
        assertEquals("title（TypeSp.TITLE）", DhDesign.TypeSp.TITLE.toDouble(), t.title.fontSize.value.toDouble(), 1e-6)
        assertEquals("section（TypeSp.SECTION）", DhDesign.TypeSp.SECTION.toDouble(), t.section.fontSize.value.toDouble(), 1e-6)
        assertEquals("body（TypeSp.BODY）", DhDesign.TypeSp.BODY.toDouble(), t.body.fontSize.value.toDouble(), 1e-6)
        assertEquals("sub（TypeSp.SUB）", DhDesign.TypeSp.SUB.toDouble(), t.sub.fontSize.value.toDouble(), 1e-6)
        assertEquals("caption（TypeSp.CAPTION）", DhDesign.TypeSp.CAPTION.toDouble(), t.caption.fontSize.value.toDouble(), 1e-6)
        assertEquals("label（TypeSp.LABEL）", DhDesign.TypeSp.LABEL.toDouble(), t.label.fontSize.value.toDouble(), 1e-6)
    }

    /**
     * 【附带守卫】深色档只改**字重**，绝不能动字号。
     *
     * 【为什么值得单独测】`Type.of` 的 `dark` 分支是 `if (dark) Medium else SemiBold`，
     *   看起来只碰字重。但将来若有人为了「深色下标题显得小」顺手把 brand 从 22 调成 21，
     *   就会变成「深浅两套字号」—— 而 TypeSp 只有一个值，`UiKit.FS_*` 引用它的人
     *   全都会与 `style = DhTokens.type.brand` 的人对不上。这条断言把这条路堵死。
     */
    @Test
    fun `深色档不得改变任何一档的字号`() {
        val light = type(dark = false)
        val dark = type(dark = true)
        assertEquals("brand", light.brand.fontSize.value.toDouble(), dark.brand.fontSize.value.toDouble(), 1e-6)
        assertEquals("display", light.display.fontSize.value.toDouble(), dark.display.fontSize.value.toDouble(), 1e-6)
        assertEquals("title", light.title.fontSize.value.toDouble(), dark.title.fontSize.value.toDouble(), 1e-6)
        assertEquals("section", light.section.fontSize.value.toDouble(), dark.section.fontSize.value.toDouble(), 1e-6)
        assertEquals("body", light.body.fontSize.value.toDouble(), dark.body.fontSize.value.toDouble(), 1e-6)
        assertEquals("sub", light.sub.fontSize.value.toDouble(), dark.sub.fontSize.value.toDouble(), 1e-6)
        assertEquals("caption", light.caption.fontSize.value.toDouble(), dark.caption.fontSize.value.toDouble(), 1e-6)
        assertEquals("label", light.label.fontSize.value.toDouble(), dark.label.fontSize.value.toDouble(), 1e-6)
    }

    /**
     * 【附带守卫】`fallback()` 与 `of(FontFamily.Default, FontFamily.Default)` 字号必须一致。
     *
     * 【为什么值得测】`fallback()` 是 `Type.of` 抛异常时的兜底路径（字体未就绪）。
     *   它内部就是转调 `of(Default, Default, dark)`，但如果将来有人「优化」成
     *   自己写死一份 TextStyle 副本，字号就可能悄悄和主路径分叉 ——
     *   而且是**只在异常路径才出现**的那种差异，最难排查。
     */
    @Test
    fun `fallback 与系统字族主路径字号一致`() {
        val viaOf = type()
        val viaFallback = DhDesign.Type.fallback()
        assertEquals("brand", viaOf.brand.fontSize.value.toDouble(), viaFallback.brand.fontSize.value.toDouble(), 1e-6)
        assertEquals("display", viaOf.display.fontSize.value.toDouble(), viaFallback.display.fontSize.value.toDouble(), 1e-6)
        assertEquals("title", viaOf.title.fontSize.value.toDouble(), viaFallback.title.fontSize.value.toDouble(), 1e-6)
        assertEquals("section", viaOf.section.fontSize.value.toDouble(), viaFallback.section.fontSize.value.toDouble(), 1e-6)
        assertEquals("body", viaOf.body.fontSize.value.toDouble(), viaFallback.body.fontSize.value.toDouble(), 1e-6)
        assertEquals("sub", viaOf.sub.fontSize.value.toDouble(), viaFallback.sub.fontSize.value.toDouble(), 1e-6)
        assertEquals("caption", viaOf.caption.fontSize.value.toDouble(), viaFallback.caption.fontSize.value.toDouble(), 1e-6)
        assertEquals("label", viaOf.label.fontSize.value.toDouble(), viaFallback.label.fontSize.value.toDouble(), 1e-6)
    }
}
