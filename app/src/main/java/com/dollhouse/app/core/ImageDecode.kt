package com.dollhouse.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 【职责】全工程统一的 Bitmap 解码入口 —— 采样、回退、空安全一处收口。
 *
 * 【为什么要收口】
 *   全仓有 20+ 处 `BitmapFactory.decodeFile/decodeStream`，采样策略各写各的：
 *   · 有的采样（`DollAnimPage.decodeSmall` inSampleSize=4）
 *   · 有的不采样（`ActionPlayerCore.decoder`、`DollAnimPage.decodeFull`）
 *   · 有的采样但代码重复（`ChatListData`、`ImageStore`）
 *   结果是「同一张图，A 页面省内存、B 页面吃满」，且改策略要改 20 处。
 *   统一走本类后：策略一处改、全仓生效；调用方只关心「我要多大的图」。
 *
 * 【采样策略】
 *   `inSampleSize` 取 2 的幂（1/2/4/8…），解码器直接按采样后尺寸分配内存，
 *   比「先全尺寸解码再 scaleDown」省一半以上内存（不需要中间全尺寸 Bitmap）。
 *   采样后尺寸 ≥ 目标尺寸（向上取整到 2 的幂），由绘制阶段再精确缩放 ——
 *   视觉零差异，内存大幅下降。
 *
 * 【线程】本类无状态，可在任意线程调用；解码本身是 CPU 密集，调用方应自行切后台。
 */
object ImageDecode {

    /**
     * 按目标尺寸采样解码文件。
     *
     * @param path 图片绝对路径
     * @param reqWidth 目标宽度（px）；<=0 表示不限制
     * @param reqHeight 目标高度（px）；<=0 表示不限制
     * @param config 像素格式，默认 ARGB_8888；缩略图场景可传 RGB_565 再省一半
     * @return 解码结果；文件不存在 / 解码失败返回 null（调用方走兜底，不崩）
     */
    @JvmStatic
    @JvmOverloads
    fun file(
        path: String,
        reqWidth: Int = 0,
        reqHeight: Int = 0,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): Bitmap? {
        val f = File(path)
        if (!f.isFile) {
            return null
        }
        return try {
            // 第一趟：只读尺寸（inJustDecodeBounds=true 不分配像素内存）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }
            // 第二趟：按采样率真实解码
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
                inPreferredConfig = config
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 计算 inSampleSize：最大的 2 的幂，使采样后**任一维**仍 ≥ 目标（题目要求的"不放大"）。
     * 目标 <=0 时返回 1（不采样）。
     *
     * 【附录A-4·修边界】旧实现用 `&&` 连接两个条件 —— 只要有一边已经小于目标就立刻停止，
     *   长宽比极端的图完全不降采样：4000×200 的封面按 192×192 上限，`200/2 = 100 < 192`
     *   直接短路返回 1，白分配 4000×200×4B ≈ 3.2MB；预期应为 16 倍降采样后的 250×13。
     *   调用方（封面、头像、气泡头像、动作页）在多图列表里会叠加成 OOM / 卡顿。
     *   ⇒ 改成 `||`：**任一维超限就继续降**，这才是"按最长边压到目标量级"的正确语义。
     *
     * 【为什么不是"两维都 ≥ 目标"才算合格】那样窄边的图会被压得比目标还小（细节丢失）。
     *   这里的取舍是：宁可窄边小于目标（视觉上只是不放大），也要把长边压下来控制内存 ——
     *   与 [com.dollhouse.app.core.ImageEncoder] 的 `MAX_DIM * 2` 口径一致。
     */
    @JvmStatic
    fun calcSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0 || srcW <= 0 || srcH <= 0) {
            return 1
        }
        var s = 1
        while (srcW / (s * 2) >= reqW || srcH / (s * 2) >= reqH) {
            s *= 2
        }
        return s
    }
}
