package com.dollhouse.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.DollhouseTheme
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.Icons
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
 * 【产物】`app/build/screenshot/` 下的 png（CI 收成 artifact 即可下载）。
 * 【注意】`GraphicsMode.NATIVE` 需要 Robolectric 4.14+；更早版本截图是白板 ——
 *   那种「测试通过但图是空白」的假绿比没有测试更糟。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-480dpi")
class DesignScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(bmp: Bitmap, name: String) {
        val dir = File("build/screenshot").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // 【自证】打印尺寸，便于在 CI 日志里确认「确实渲染了」而不是 0×0 白板。
        println("[screenshot] $name -> ${bmp.width}x${bmp.height}")
    }

    /**
     * 卡片 + 三类行 + 圆角对照：一次看清当前设计语言的四件事
     * （圆角、阴影、页面底、字重层级）。
     */
    @Test
    fun designSystemOverview() {
        rule.setContent {
            DollhouseTheme {
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

                    // 圆角对照：三档并排，肉眼比较 10 / 16 / 22 的差别
                    Row {
                        RadiusChip(p.card, p.sub, 10.dp, "R10")
                        Spacer(Modifier.width(DhDesign.Space.sm.dp))
                        RadiusChip(p.card, p.sub, 16.dp, "R16")
                        Spacer(Modifier.width(DhDesign.Space.sm.dp))
                        RadiusChip(p.card, p.sub, 22.dp, "R22")
                    }
                }
            }
        }
        // 【为什么手动推进时钟】Compose 的动画/重组要跑完才是"最终画面"；
        //   直接 capture 可能抓到中间帧。advanceTimeBy 让所有 tween/spring 走完。
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        save(rule.onRoot().captureToImage().asAndroidBitmap(), "01-light")
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
}
