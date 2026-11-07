package com.dollhouse.app.ui.kit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.compose.DhTokens

/**
 * 【职责】输入框 / 字段的**内凹（inset）**质感：一圈内阴影，而不是外描边。
 *
 * # 为什么不用 `Modifier.shadow(inset = true)`
 *
 * 改造前的需求写的是「`Modifier.shadow(..., inset = true)` 可以画内阴影」—— **这条不成立**。
 * 实测本工程实际使用的 `androidx.compose.ui:ui-android:1.10.2`：
 * ```
 * /root/.gradle/caches/.../ui.aar → classes.jar → androidx/compose/ui/draw/ShadowKt.class
 * 方法签名：Modifier.shadow(elevation, shape, clip, ambientColor, spotColor)
 * ```
 * **没有任何 `inset` 参数**。Compose 1.10 确实新增了 `Modifier.innerShadow(shape, block)`
 * （`InnerShadowScope`，带 `radiusDp` / `dpOffset` / `color`），但：
 * ① 它是**实验期 API**，签名跨版本不稳定；
 * ② 本仓已有 `ui_audit.sh` 的门禁约束（`C02` 无 XML、`A07` 裸圆角上限等），
 *    引入实验 API 会让「以后升级 Compose」变成一次需要重新验证的迁移。
 *
 * 因此采用需求里给的**备选方案**：「用 `drawBehind` 画 2dp 的内阴影（上左暗、下右亮）」。
 * `drawBehind` 是本工程已用过的稳定 API（见 `pet/DollRangeOverlay.kt`）。
 *
 * # 轻拟物内凹的物理规则（这是"看起来对"的关键，不是随便画两笔）
 *
 * 真实世界里，一个**凹陷**的浅坑：
 * · **上沿 + 左沿**背光 → 这条边是**暗**的（阴影从亮处投进来）；
 * · **下沿 + 右沿**迎光 → 这条边是**亮**的（坑底反光打到边缘）。
 *
 * 【为什么不能简单「四边都画暗」】四边同暗会读成「一个更深的实心块」，
 *   而不是「凹进去的坑」—— 缺少明暗对比就没有体积暗示。
 * 【为什么也不能把明暗整圈连续插值】那是「环形渐变」的观感（像光圈），
 *   而不是「一个方向性光源在斜上方」的室内感。轻拟物要求光源**同意**：
 *   本 App 所有阴影（`Elevation.e1/e2` + `shadowTint`）都按「左上光源」假设，
 *   所以这里也必须同向，否则卡片是凸的、输入框是另一个光源照的，整体会"散"。
 *
 * # 深色档为什么要单独处理
 *
 * 【不能简单反转】深色底上「暗边」几乎看不见（本来就暗），而「亮边」会过曝成一道白线。
 * 深色档的正确配方是需求写的那句：**更暗的内壁 + 顶部一道极淡的高光**：
 * · 上沿用「比底更暗」的窄渐变（把坑画深）；
 * · 下沿几乎不画亮边（深色下反光很弱）；
 * · **顶部单独加一道 0.5~1dp 的极淡白色高光**，模拟玻璃/塑料表面的边缘反光 ——
 *   这一道是深色档「看起来清透」而不是「糊成一团」的关键。
 *
 * 判定用 [DhDesign.relativeLuminance]（而非主题枚举）：用户调主题色 / 莫奈取色后
 * 底色明暗可能变，固定枚举会失配 —— 这与 `palette.shadowTint` 的判定口径一致。
 */

/** 内凹阴影的默认深度。2dp 是「几乎看不出来但确实有」的量级（需求原话）。 */
private val INSET_DEPTH = 2.dp

/** 深色档顶部那道高光的粗细。刻意比内阴影更细，避免读成描边。 */
private val DARK_TOP_HIGHLIGHT = 1.dp

/**
 * 给字段画「内凹」质感。
 *
 * 【调用位置】必须在 `.clip(shape)` **之后**、`.background(fill)` **之前**调用：
 * · 放在 clip 之后 → 内阴影被裁进圆角，不会溢出到框外；
 * · 放在 background 之前 → 内阴影画在底色**之上**（否则会被底色盖掉）。
 *
 * 【为什么不自己 clip】本函数不负责裁切：调用方已有 `.clip(shape)`，
 *   重复 clip 会多一层 layer（性能与观感都无收益）。这里用 `clipPath` 只裁
 *   **绘制范围**，不影响布局与命中。
 *
 * @param shape 与字段一致的圆角（必须同源，否则内阴影会露在圆角外）
 * @param focused 聚焦态。聚焦时内凹加深 + 叠一圈极淡 accent 光晕
 * @param depth 内凹深度，默认 2dp
 */
@Composable
fun Modifier.dhFieldInset(
    shape: RoundedCornerShape,
    focused: Boolean,
    depth: Dp = INSET_DEPTH,
    glowColor: Color? = null
): Modifier {
    // 【必须在 Composable 上下文取色】`DhTokens.palette` 是 @Composable getter，
    //   而 `drawBehind` 的 lambda 是 DrawScope 接收者、不是 @Composable ——
    //   在 lambda 里读它会报 `@Composable invocations can only happen from
    //   the context of a @Composable function`（CI 抓到过）。
    //   所以在组合期取好，再闭包传给绘制层。
    val p = DhTokens.palette
    val dark = DhDesign.relativeLuminance(p.bg) <= 0.5f
    return this.drawBehind {
        val corner = shape.topStart.toPx(size, this)

        // 同一个圆角路径：既用于裁绘制，也用于描内阴影的弧形边。
        val path = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    radiusX = corner,
                    radiusY = corner
                )
            )
        }

        // 聚焦让"坑"更深一档：视觉上是"被激活了"，而不是靠加粗描边（那是扁平风格的做法）。
        val boost = if (focused) 1.6f else 1f
        val d = depth.toPx() * boost

        clipPath(path) {
            if (dark) {
                // ---------- 深色档：更暗的内壁 + 顶部极淡高光 ----------
                // 上沿暗（从顶部往里推）
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.34f * boost),
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = d * 2.2f
                    ),
                    size = Size(size.width, d * 2.2f)
                )
                // 【关键】顶部一道极淡高光：深色下"清透"的来源。
                //   画在 0~1dp 的极窄带里，alpha 很低 —— 过量就变成描边了。
                val hi = DARK_TOP_HIGHLIGHT.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = hi
                    ),
                    size = Size(size.width, hi)
                )
                // 左侧极轻的暗（保持左上光源一致，但比上沿弱）
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.16f * boost),
                        1f to Color.Transparent,
                        startX = 0f,
                        endX = d
                    ),
                    size = Size(d, size.height)
                )
            } else {
                // ---------- 浅色档：上左暗、下右亮 ----------
                // 上沿暗
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to p.shadowTint.copy(alpha = 0.30f * boost),
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = d * 2f
                    ),
                    size = Size(size.width, d * 2f)
                )
                // 左沿暗
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to p.shadowTint.copy(alpha = 0.20f * boost),
                        1f to Color.Transparent,
                        startX = 0f,
                        endX = d * 1.6f
                    ),
                    size = Size(d * 1.6f, size.height)
                )
                // 下沿亮（坑底反光打到下边缘）
                val bottomBand = d * 1.6f
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.42f),
                        startY = size.height - bottomBand,
                        endY = size.height
                    ),
                    topLeft = Offset(0f, size.height - bottomBand),
                    size = Size(size.width, bottomBand)
                )
                // 右沿亮
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.28f),
                        startX = size.width - d * 1.4f,
                        endX = size.width
                    ),
                    topLeft = Offset(size.width - d * 1.4f, 0f),
                    size = Size(d * 1.4f, size.height)
                )
            }

            // ---------- 聚焦光晕（两档共用）----------
            // 【为什么用"极淡 accent 内发光"而不是"加粗 accent 描边"】
            //   描边是扁平风格的语言（一条明确的线）；轻拟物里表示"激活"要用**光**——
            //   玻璃/亚克力被点亮时，边缘会泛出一圈同色系的辉光，不是一条实线。
            //   这里沿内侧描一圈 1.5dp、alpha 0.5 的 accent，读起来是"发光的边"而非"框线"。
            if (focused) {
                // 【用调用方传入的 glowColor（若给）】它的好处是**跟着 160ms 一起渐变**：
                //   调用方那边是 `animateColorAsState(Transparent → accent)`，把这个动画中的
                //   值传进来，光晕就会淡入而不是"啪一下亮起"。传 null 时退回固定 accent。
                val base = glowColor ?: p.accent
                val ring = 1.5.dp.toPx()
                drawPath(
                    path = path,
                    color = base.copy(alpha = 0.45f),
                    style = Stroke(width = ring * 2f)   // 半圈被裁在框外，视觉上约 1.5dp
                )
                // 柔和外扩：再叠一圈更淡更粗的，制造"辉光"而不是"线"
                drawPath(
                    path = path,
                    color = base.copy(alpha = 0.16f),
                    style = Stroke(width = ring * 5f)
                )
            }
        }
    }
}