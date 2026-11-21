package com.dollhouse.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.DollhouseTheme
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.Icons
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 设计系统截图测试。
 *
 * 【为什么要它】本项目长期无法验证「渲染出来是什么样」：
 *   对比度是公式算的、阴影 / 圆角 / 渐变从来没在屏幕上确认过。
 *   于是每次视觉改动都是「推理交付」——用户看不出变化，我也说不清哪里不对。
 *
 * Robolectric + [GraphicsMode.NATIVE] 能在 **JVM 单元测试**里真渲染 Compose
 * （不是 mock、不是 layout-only，是真光栅化到位图），因此：
 *   · 不需要真机 / 模拟器；
 *   · CI 里就能跑；
 *   · 产出 PNG，人和模型都能直接看。
 *
 * 【为什么不用 createAndroidComposeRule + captureToImage】
 *   实测在本项目上它会抛 `ComposeTimeoutException`：`captureToImage()` 内部等
 *   `waitForIdle()`，而本项目有常驻/循环动画（底栏选中缩放、按压弹簧等），
 *   Compose 永远到不了 idle，于是一直等到超时。
 *   改成**手动 measure / layout / draw 到 Bitmap**：完全不依赖 idle 语义，
 *   画出来的就是当前帧，反而是截图真正想要的。
 *
 * 【产物】`app/build/screenshot/` 下的 png（CI 收成 artifact）。
 * 【注意】`GraphicsMode.NATIVE` 需要 Robolectric 4.14+；更早版本截图是白板 ——
 *   那种「测试通过但图是空白」的假绿比没有测试更糟。所以下面会**校验像素非全白**。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-480dpi")
class DesignScreenshotTest {

    private val widthPx = 1080
    private val heightPx = 1600

    @Test
    fun designSystemOverview() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val cv = ComposeView(activity).apply {
            setContent { DollhouseTheme { SampleContent() } }
        }
        activity.setContentView(cv)
        // 让组合跑起来（一帧就够：我们不依赖动画终态）。
        ShadowLooper.idleMainLooper()

        val bmp = renderToBitmap(cv)
        save(bmp, "01-light")
    }

    /** 手动 measure / layout / draw —— 绕开所有 idle 同步。 */
    private fun renderToBitmap(v: View): Bitmap {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, widthPx, heightPx)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun save(bmp: Bitmap, name: String) {
        val dir = File("build/screenshot").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // 【自证：不是白板】
        //   「测试通过但图是空白」是这类截图测试最典型的假绿，必须显式校验。
        var nonBg = 0
        val bg = bmp.getPixel(2, 2)
        outer@ for (y in 0 until bmp.height step 7) {
            for (x in 0 until bmp.width step 7) {
                if (bmp.getPixel(x, y) != bg) {
                    nonBg++
                    if (nonBg > 200) break@outer
                }
            }
        }
        println("[screenshot] $name -> ${bmp.width}x${bmp.height}, 非底色采样点=$nonBg")
        check(nonBg > 200) {
            "截图疑似白板（非底色采样点仅 $nonBg）：GraphicsMode 可能未生效，或内容没渲染"
        }
    }

    /* ======================================================================
     * 页面级覆盖（第二批）
     *
     * 【为什么不再只测「设计系统总览」】第一批的 `SampleContent` 是手搭的示范片段，
     *   它只能证明「令牌能被读出来」，证明不了「真实页面渲染正确」。本轮改动
     *   （P1-f 配色中性化、11 处文字对比度、莫奈气泡底、触控 48dp、加载态）
     *   全部落在真实页面的构件上，必须有页面级截图才守得住。
     *
     * 【为什么用 DhKit 公开构件拼「页面片段」而不是直接调 SettingsScreen.build()】
     *   真实页面入口全部签名成 `buildPage(act: MainActivity)`，其内部还有
     *   `HomeUi.syncAll()`（读 Shizuku / KeepAlive 桥）、`GlobalBackground.installHome()`、
     *   `PetService` 通知、1s ticker 等重依赖。在 Robolectric 里把 MainActivity
     *   完整拉起来的风险远大于收益（一个桥接没 mock 就会让测试红，而这种红
     *   与「UI 回归」无关，会迅速把这条线变成噪音）。
     *   因此这里用**同一批公开构件**（DhKit.Page / Card / ActionRow / ValueRow /
     *   SwitchRow / InfoRow）按真实页面的排布拼片段：它们就是真实页面渲染出来的
     *   同一份代码路径（DhKit.ActionRow 有 87 处调用、Card 53 处、Page 49 处），
     *   所以对配色/对比度/圆角/行高的回归**同样敏感**，但不引入任何服务依赖。
     *
     * 【断言策略】不做 golden 基线比对（存仓库会让 diff 变大、存 CI artifact
     *   无法跨 run 比对，两条都已评估并暂缓）。改为三条可自动判定的：
     *   ① 非白板（既有 save() 已校验）；
     *   ② 关键语义色确实出现（用当前调色板实算，不写死色值 —— 换主题不会误报）；
     *   ③ 内容包围盒合理（内容不能只占角落，防「渲染出一小块」的假绿）。
     * ==================================================================== */

    /**
     * 统计位图里出现的目标颜色像素数（容差匹配，抗锯齿边缘也算命中）。
     *
     * @param targets 目标色 + 该色至少要有几个命中（0 = 只要出现即可，不校验数量）
     */
    private fun countColors(
        bmp: Bitmap,
        targets: Map<Int, Int>,
        tolerance: Int = 6
    ): Map<Int, Int> {
        val hits = HashMap<Int, Int>(targets.size)
        val list = targets.keys.toIntArray()
        for (k in list) hits[k] = 0
        val step = 2
        for (y in 0 until bmp.height step step) {
            for (x in 0 until bmp.width step step) {
                val c = bmp.getPixel(x, y)
                for (k in list) {
                    if (hits[k]!! > 4000) continue
                    if (Math.abs((c shr 16 and 0xff) - (k shr 16 and 0xff)) <= tolerance &&
                        Math.abs((c shr 8 and 0xff) - (k shr 8 and 0xff)) <= tolerance &&
                        Math.abs((c and 0xff) - (k and 0xff)) <= tolerance
                    ) {
                        hits[k] = hits[k]!! + 1
                    }
                }
            }
        }
        return hits
    }

    /** 内容包围盒：相对「四角众数背景」的差异像素范围，返回 (minX, minY, maxX, maxY)。 */
    private fun contentBounds(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val corners = intArrayOf(
            bmp.getPixel(2, 2), bmp.getPixel(w - 3, 2),
            bmp.getPixel(2, h - 3), bmp.getPixel(w - 3, h - 3)
        )
        val counts = HashMap<Int, Int>()
        for (c in corners) counts[c] = (counts[c] ?: 0) + 1
        var bg = corners[0]
        for ((c, n) in counts) if (n > (counts[bg] ?: 0)) bg = c
        val br = bg shr 16 and 0xff
        val bg2 = bg shr 8 and 0xff
        val bb = bg and 0xff
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        var found = 0
        for (y in 0 until h step 3) {
            for (x in 0 until w step 3) {
                val c = bmp.getPixel(x, y)
                if (Math.abs((c shr 16 and 0xff) - br) > 8 ||
                    Math.abs((c shr 8 and 0xff) - bg2) > 8 ||
                    Math.abs((c and 0xff) - bb) > 8
                ) {
                    found++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return intArrayOf(minX, minY, maxX, maxY, found)
    }

    /** 把内容色断言打成人能读的一行。 */
    private fun assertColors(
        bmp: Bitmap,
        name: String,
        required: Map<Int, Int>,
        tolerance: Int = 6
    ) {
        val hits = countColors(bmp, required, tolerance)
        val missing = StringBuilder()
        for ((color, minHits) in required) {
            val n = hits[color] ?: 0
            val ok = n >= minHits
            println(
                "[color] $name #%06X 命中=%d 需求>=%d %s".format(color and 0xFFFFFF, n, minHits, if (ok) "OK" else "MISS")
            )
            if (!ok) missing.append("#%06X(得%d/需%d) ".format(color and 0xFFFFFF, n, minHits))
        }
        check(missing.isEmpty()) {
            "$name 缺少关键语义色：$missing —— 可能是主题未生效、构件未渲染，或配色被改坏"
        }
    }

    /** 断言内容不是「只在角落一小块」。 */
    private fun assertContentSpans(bmp: Bitmap, name: String, minW: Int, minH: Int) {
        val b = contentBounds(bmp)
        val (minX, minY, maxX, maxY, found) = b
        val cw = maxX - minX
        val chh = maxY - minY
        println(
            "[bounds] $name 包围盒=($minX,$minY)-($maxX,$maxY) 尺寸=${cw}x${chh} 采样命中=$found"
        )
        check(found > 500) { "$name 内容像素过少（$found），疑似没渲染出来" }
        check(cw >= minW && chh >= minH) {
            "$name 内容包围盒过小：${cw}x${chh}，要求至少 ${minW}x${minH} —— 内容可能只占角落"
        }
    }

    /**
     * 设置页片段：复刻 `SettingsScreen` 的真实排布。
     *
     * 【为什么是这几行】SettingsScreen 的 5 张主卡里，「备份与恢复」「实验」
     *    是折叠卡（标题 + 内容），「聊天」「人偶」「外观」是入口卡（ActionRow）。
     *    这里取最有代表性且无服务依赖的组合：SectionLabel + Card{
     *    ActionRow(带 desc/chevron) + ValueRow(右值) + SwitchRow } + InfoRow。
     *    · `ActionRow` 全 App 87 处调用 —— 本轮触控 48dp 改的是它的命中区；
     *    · `InfoRow` 正是本轮 11 处 `c.line→c.sub` 对比度修复的通用组件
     *      （`DollLookTab:250/254` 是它唯一的真实调用点，之前 1.50:1 看不见）；
     *    · `SwitchRow` 覆盖自绘开关 + DhRow 行几何。
     */
    @Test
    fun settingsFragment_rendersPalette() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // 【为什么要在 setContent 里捕获】DhTokens.palette 是 @Composable get()，
        //   只能在组合作用域读；@Test 函数体不是 @Composable，直接读会编译失败
        //   （Composable invocations can only happen from the context of a @Composable function）。
        var capturedP: DhDesign.Palette? = null
        val cv = ComposeView(activity).apply {
            setContent {
                DollhouseTheme {
                    capturedP = DhTokens.palette
                    DhKit.Page(title = "设置", onBack = {}) {
                        DhKit.SectionLabel("通用", iconRes = Icons.IC_SETTINGS)
                        DhKit.Card {
                            DhKit.ActionRow(
                                label = "供应商",
                                iconRes = Icons.IC_PLUG,
                                desc = "已接入 1 家，共 3 个模型",
                                showChevron = true
                            ) {}
                            DhKit.ValueRow(
                                label = "外观",
                                value = "跟随系统",
                                iconRes = Icons.IC_PALETTE
                            ) {}
                            DhKit.SwitchRow(
                                name = "使用自定义背景",
                                desc = "关闭后使用纯色页面底",
                                iconRes = Icons.IC_IMAGE,
                                checked = true
                            ) {}
                            DhKit.ActionRow(
                                label = "备份与恢复",
                                iconRes = Icons.IC_DOWNLOAD,
                                desc = "导出为单个 .akcp 文件",
                                showChevron = true
                            ) {}
                        }
                        Spacer(Modifier.height(DhDesign.Space.md.dp))
                        DhKit.Card {
                            DhKit.InfoRow(
                                iconRes = Icons.IC_PALETTE,
                                text = "全局背景 / 你的气泡 / 弹窗 → 设置 → 外观"
                            )
                            DhKit.InfoRow(
                                iconRes = Icons.IC_MESSAGES_SQUARE,
                                text = "聊天里看到的背景与她的气泡，跟随这里的设置"
                            )
                        }
                    }
                }
            }
        }
        activity.setContentView(cv)
        ShadowLooper.idleMainLooper()

        val bmp = renderToBitmap(cv)
        save(bmp, "03-settings")

        // ② 关键语义色必须出现（从当前调色板现读，不写死 —— 换主题/莫奈都不会误报）
        val p = requireNotNull(capturedP) { "setContent 未执行，palette 未捕获" }
        assertColors(
            bmp, "03-settings", mapOf(
                p.card.toArgbForTest() to 1,     // 卡片底：DhKit.Card 转发 kit.DhGroup
                p.sub.toArgbForTest() to 1,      // 副文：InfoRow 本轮对比度修复后的字色
                p.title.toArgbForTest() to 1,    // 标题/正文
                p.accent.toArgbForTest() to 1    // 强调：图标/开关开启态
            )
        )
        // ③ 内容要铺开：设置页是「顶栏 + 两段卡」，高度至少占画布一半
        assertContentSpans(bmp, "03-settings", minW = 700, minH = 500)
    }

    /**
     * 聊天气泡片段：复刻 `ChatUi.Bubble` 的真实配色路径。
     *
     * 【为什么必须覆盖它】本轮莫奈浅色档的气泡底缺陷就在这里：
     *   气泡底原按「白字可读」反推（中明度），而 `ChatUi` 实际用 `title` 画字，
     *   24 个色相实测 1.91~2.63:1。修法是把气泡底改成按 `title` 反提
     *   （`ThemeManager.inkOnLit`）。这条路径只有截图能证明「真的能读」。
     *
     * 【为什么不用 ChatUi.Bubble 本体】它是 `private` 且需要 `ChatHostUi`（宿主回调），
     *   测试里构造一个假宿主只会让测试依赖更多与视觉无关的东西。这里按它的
     *   真实取色路径复刻：我方 = `chatBubbleUser` 底 + `title` 字；对方 = `card` 底 + `title` 字。
     *   取色完全走令牌，与真实气泡同一份代码来源。
     */
    @Test
    fun chatBubbleFragment_rendersPalette() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var capturedP: DhDesign.Palette? = null
        val cv = ComposeView(activity).apply {
            setContent {
                DollhouseTheme {
                    val p = DhTokens.palette
                    capturedP = p
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(p.bg)
                            .padding(DhDesign.Space.lg.dp)
                    ) {
                        // 我方消息：user 气泡（chatBubbleUser 底 + title 字）
                        BubbleRow(mine = true, text = "这是我的消息：气泡底与字色都要读得清")
                        Spacer(Modifier.height(DhDesign.Space.md.dp))
                        // 对方消息：assistant 气泡（card 底 + title 字）
                        BubbleRow(mine = false, text = "这是她的消息：走卡片底与正文色")
                    }
                }
            }
        }
        activity.setContentView(cv)
        ShadowLooper.idleMainLooper()

        val bmp = renderToBitmap(cv)
        save(bmp, "04-chatbubble")

        val p = requireNotNull(capturedP) { "setContent 未执行，palette 未捕获" }
        // 【为什么这里容差给 3】chatBubbleUser(#DCE9FA) 与 bg(#E8EFF7) 的最大通道差只有 12、
        //   card(#FAFCFE) 与 bg 只有 18 —— 用默认的 6 会让「气泡底」与「页面底」互相误中，
        //   于是气泡底缺失也可能被 bg 的命中数顶过阈值，断言形同虚设。
        //   3 既避开互中，又足以覆盖抗锯齿边缘（1~2 个色阶）。
        assertColors(
            bmp, "04-chatbubble", mapOf(
                p.chatBubbleUser.toArgbForTest() to 1,  // 我方气泡底（本轮莫奈修复的目标槽位）
                p.card.toArgbForTest() to 1,           // 对方气泡底
                p.title.toArgbForTest() to 1,          // 两侧气泡的字色
                p.bg.toArgbForTest() to 1              // 页面底
            ),
            tolerance = 3
        )
        assertContentSpans(bmp, "04-chatbubble", minW = 400, minH = 80)
    }

    /** 聊天气泡行：与 `ChatUi.Bubble` 同源的取色与内边距。 */
    @Composable
    private fun BubbleRow(mine: Boolean, text: String) {
        val p = DhTokens.palette
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(DhDesign.Radius.lg.dp))
                    .background(if (mine) p.chatBubbleUser else p.card)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = text,
                    color = p.title,
                    fontSize = 15.sp,
                    lineHeight = 24.sp
                )
            }
        }
    }

    @Composable
    private fun SampleContent() {
        val p = DhTokens.palette
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.bg)
                .padding(DhDesign.Space.lg.dp)
        ) {
            Text("设计系统 · 浅色", color = p.title, fontSize = 15.sp)
            Spacer(Modifier.height(DhDesign.Space.md.dp))

            DhKit.Card {
                DhKit.ActionRow(
                    label = "设置项",
                    iconRes = Icons.IC_SETTINGS,
                    desc = "说明文字，用来看主次层级"
                ) {}
                DhKit.ValueRow(
                    label = "外观",
                    value = "浅色",
                    iconRes = Icons.IC_PALETTE
                ) {}
                DhKit.SwitchRow(
                    name = "使用自定义背景",
                    iconRes = Icons.IC_IMAGE,
                    checked = true
                ) {}
            }

            Spacer(Modifier.height(DhDesign.Space.md.dp))

            Row {
                RadiusChip(p.card, p.sub, 10.dp, "R10")
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                RadiusChip(p.card, p.sub, 16.dp, "R16")
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                RadiusChip(p.card, p.sub, 22.dp, "R22")
            }
        }
    }

    @Composable
    private fun RadiusChip(
        fill: androidx.compose.ui.graphics.Color,
        fg: androidx.compose.ui.graphics.Color,
        r: Dp,
        label: String
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(r))
                .background(fill),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = fg, fontSize = 11.sp)
        }
    }

    /**
     * 【像素级居中验证】IconButton 的图标必须落在圆底的几何中心。
     *
     * 【为什么用像素而不是肉眼】用户反馈「左上角返回键不在最中心」，
     *   而目视只能看出"大概偏左"，说不出偏几个像素。
     *   这里直接算：把渲染结果里**明显区别于底色的像素**（即图标笔画）的包围盒中心，
     *   与容器中心对比，偏差超过 1dp 就报错。
     *
     * 【为什么阈值是 1dp】抗锯齿会让边缘像素抖动 1px 左右；
     *   1dp（480dpi 下约 3px）以内可认为视觉居中，超过就是布局没对齐。
     */
    @Test
    fun iconButton_isCentered() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val sizePx = 96
        val cv = ComposeView(activity).apply {
            setContent {
                DollhouseTheme {
                    // 【必须让容器居中】否则 48dp 的按钮会落在画布左上角，
                    //   而下面按「画布中心」比对就会得出"偏了几百像素"的假结论。
                    //   第一版就是这么写错的 —— 测试本身错了，比没有测试更误导。
                    Box(
                        modifier = Modifier.fillMaxSize().background(DhTokens.colors.bg),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(48.dp)) {
                            DhKit.IconButton(
                                iconRes = Icons.IC_ARROW_LEFT,
                                sizeDp = 20f,
                                color = DhTokens.colors.title,
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
        activity.setContentView(cv)
        ShadowLooper.idleMainLooper()

        val bmp = renderToBitmap(cv)
        save(bmp, "02-iconbutton")

        // 找出所有"深色笔画"像素（图标是 title 色，明显深于 soft 圆底）
        var minX = Int.MAX_VALUE; var maxX = -1
        var minY = Int.MAX_VALUE; var maxY = -1
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val c = bmp.getPixel(x, y)
                val lum = (0.2126 * ((c shr 16) and 0xff) +
                           0.7152 * ((c shr 8) and 0xff) +
                           0.0722 * (c and 0xff)) / 255.0
                if (lum < 0.35) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        check(maxX > 0 && maxY > 0) { "没找到图标笔画，截图可能没渲染出来" }
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val px = (bmp.width - 1) / 2
        val py = (bmp.height - 1) / 2
        val dx = kotlin.math.abs(cx - px)
        val dy = kotlin.math.abs(cy - py)
        println("[center] 图标包围盒=($minX,$minY)-($maxX,$maxY) 中心=($cx,$cy) 容器中心=($px,$py) 偏差=($dx,$dy)px")
        // 1dp ≈ 3px @480dpi
        check(dx <= 3) { "图标水平不居中：偏 ${dx}px（>3px ≈ 1dp）" }
        check(dy <= 3) { "图标垂直不居中：偏 ${dy}px（>3px ≈ 1dp）" }
    }

    /**
     * 把 Compose 的 [Color] 转成与 `Bitmap.getPixel()` 同一位布局的 ARGB Int。
     *
     * 【为什么不直接比较 Color 对象】断言要对着位图像素做，必须走到 ARGB；
     *   Compose 的 `Color.toArgb()` 即可，这里包一层只是让调用点读起来是「转成可断言的色」。
     */
    private fun Color.toArgbForTest(): Int = this.toArgb()
}
