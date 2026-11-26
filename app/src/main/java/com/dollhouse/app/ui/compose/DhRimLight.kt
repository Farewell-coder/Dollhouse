package com.dollhouse.app.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 【职责】**边缘光**（QZX `qzxCardRimLight`）—— 卡片边缘一圈**加法混合**的淡白光，
 * 用来取代 1dp 描边。
 *
 * ## 它和描边的本质差别（这是"贴纸 vs 玻璃"的分水岭）
 *
 * 描边是**颜料**：占掉像素、盖在底色上、把边缘"画"出来。
 * 边缘光是**光**：`BlendMode.Plus` 只往「亮」的方向加，**从不压暗任何像素** ——
 * 深色卡片上读作自然的镀光，浅色卡片上只剩一点点起伏，永远不会像贴上去一条胶带。
 *
 * 参数抄自 QZX 文档实测值（`rim-light` 组件页）：颜色白、带宽 1dp、内晕 2dp、
 * 顶端亮度 ≈0.09、底部 ≈0.036（约顶端的 0.4）、上下各一个亮度峰（dualPeak）。
 *
 * ## 为什么用「垂直渐变 + 描边路径」近似，而不是逐像素 SDF
 *
 * QZX 原版是圆角 SDF + 法线 + 平滑，逐像素算，靠 `LruCache` 缓存位图才不卡。
 * 在 Compose 里没必要：拿一张卡片看，亮度分布其实只由**该点是顶边还是底边**决定 ——
 * · 顶边法线朝上 → 主光源最亮（0.09）
 * · 底边法线朝下 → 次光源弱（0.036）
 * · 左右边法线**水平** → 约等于 0
 * · 圆角处自然插值
 * 一条**垂直渐变描边**正好落在这个分布上（0 → 0.09 → 0 → 0.036），
 * 而且零逐像素计算、无缓存、无后台线程。
 *
 * 【这个近似在哪失真】圆角上的亮度取的是"该点 Y 处的渐变色"，而真实值是"该点法线方向的光照"。
 * 差别出现在 45° 角附近（我们给的是中间亮度，真实值更接近按法线连续过渡）。
 * 在 1dp 宽的一圈光上，这点差异肉眼分辨不出 —— 但要知道它**是近似**，不是精确复刻。
 *
 * ## 为什么路径要向内缩半个线宽
 *
 * `Stroke` 是**以路径为中心**向两侧各画一半的。若不缩，1dp 的光有一半落在卡片外，
 * 而卡片通常带 `clip(RoundedCornerShape(...))` —— 那一半直接被裁掉，
 * 实际只剩 0.5dp 且边缘变硬。所以按 `线宽/2` 向内缩，让整条光落在卡片内侧。
 */
fun Modifier.rimLight(
    cornerRadius: Dp,
    width: Dp = 1.dp,
    topAlpha: Float = RIM_TOP_ALPHA,
    bottomAlpha: Float = RIM_BOTTOM_ALPHA,
    /**
     * 内晕深度（dp）：光往卡片**内部**扩散的范围。
     *
     * 【为什么要有】QZX 的规格里这一项是 2dp —— 只有一条 1dp 硬线的话，
     * 边缘会显得"锋利"，而真实玻璃的反射是**有衰减**的：亮线 + 向内 2dp 的余晖。
     * 传 `0.dp` 可以关掉，只留硬线。
     */
    glow: Dp = 2.dp,
    /**
     * 是否绘制。**透明面不该有边缘光** —— 没有实体表面就没有可反光的东西。
     *
     * 调用方传「卡片背景色的 alpha > 0」即可：卡片透明度被用户拉低到 0 时自动跳过。
     */
    enabled: Boolean = true,
): Modifier {
    if (!enabled) {
        return this
    }
    return this.drawWithContent {
        // 先画卡片自己（底色 / 内容），再把光叠上去
        drawContent()

        val radiusPx = cornerRadius.toPx()
        val wPx = width.toPx()
        val gPx = glow.toPx()

        // 上下两个亮度峰：顶 0.09 → 中 0 → 底 0.036。左右边落在中点附近 ⇒ 接近 0，
        // 与「左右法线水平、几乎不受光」一致。
        val brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = topAlpha),
            0.5f to Color.White.copy(alpha = 0f),
            1f to Color.White.copy(alpha = bottomAlpha)
        )

        /** 造一圈与卡片同心的圆角路径，向内缩 [inset]。 */
        fun ring(inset: Float): Path {
            val r = (radiusPx - inset).coerceAtLeast(0f)
            return Path().apply {
                addRoundRect(
                    RoundRect(
                        left = inset,
                        top = inset,
                        right = size.width - inset,
                        bottom = size.height - inset,
                        cornerRadius = CornerRadius(r)
                    )
                )
            }
        }

        // ① 内晕：宽而淡的一圈，从边缘向内 gPx。先画，让后面的硬线压在它上面。
        if (gPx > 0f && size.width > gPx * 2f && size.height > gPx * 2f) {
            drawPath(
                path = ring(gPx),
                brush = brush,
                // 余晖比硬线淡得多：同一束光扩散开后亮度必然下降
                alpha = RIM_GLOW_ALPHA_SCALE,
                style = Stroke(width = gPx * 2f),
                blendMode = BlendMode.Plus
            )
        }

        // ② 硬线：1dp 全亮。`BlendMode.Plus` 是「光」与「颜料」的唯一区别 ——
        //    换成 SrcOver 就立刻变回一条画的边框。
        if (wPx > 0f) {
            drawPath(
                path = ring(wPx / 2f),
                brush = brush,
                style = Stroke(width = wPx),
                blendMode = BlendMode.Plus
            )
        }
    }
}

/** 顶端（主光源）亮度峰值。QZX 实测 ≈0.09。 */
const val RIM_TOP_ALPHA = 0.09f

/** 底端（次光源）亮度峰值。QZX 实测 ≈0.036（约顶端的 0.4）。 */
const val RIM_BOTTOM_ALPHA = 0.036f

/**
 * 内晕的亮度系数（相对硬线）。
 *
 * 【取值理由】光扩散开之后单位面积的能量必然下降；2dp 的余晖取硬线的 0.35 倍时，
 * 叠加后峰值约 0.09 × (1 + 0.35) ≈ 0.12 —— 仍远低于"看得见一条白边"的程度，
 * 但又足以让边缘不再是硬切。再高就会开始像描边了（那就背离了本组件的初衷）。
 */
private const val RIM_GLOW_ALPHA_SCALE = 0.35f
