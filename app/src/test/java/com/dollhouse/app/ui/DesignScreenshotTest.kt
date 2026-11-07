package com.dollhouse.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
}
