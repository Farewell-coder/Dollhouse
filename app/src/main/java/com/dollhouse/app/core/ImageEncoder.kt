package com.dollhouse.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ImageStore
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 【职责】把本机图片转成可直接塞进请求体的 `data:` URI（base64）。
 *
 * 【为什么需要它】模型带「视觉（VISION）」能力时，图片应以 OpenAI 兼容的
 *   `image_url` 形式直送（`{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`），
 *   让模型自己看图。原先工程一律走本地 OCR 把图片转成文字，视觉模型的看图能力被浪费了。
 *
 * 【参考实现】Operit 的 OpenAIProvider：`put("url", "data:${link.mimeType};base64,${link.base64Data}")`
 *   —— 即同样用 data URI 承载图片。本类按其口径实现，但不做 ImagePoolManager 那套
 *   内存池（本工程图片本来就是本地文件，直接读文件即可，无需池化）。
 *
 * 【为什么必须压缩】原图动辄几 MB，base64 后还要膨胀约 1/3；一张 5MB 的图会直接
 *   吃掉几万 token，且多数中转站对单请求体积有限制。这里按 [MAX_DIM] 降采样后
 *   再按质量压 JPEG，实测可把体积控制在几十到一两百 KB。
 *
 * 【失败语义】任何一步失败都返回 null，由调用方决定「退回 OCR」还是「不带图发」。
 *   绝不让编码失败把整条请求带崩。
 */
object ImageEncoder {

    private const val TAG = "DollhouseImgEnc"

    /** 送模型的最大边长：与 OcrEngine.MAX_DIM 同口径（1600 足够看清细节，又不至于爆 token）。 */
    private const val MAX_DIM = 1600

    /** JPEG 压缩质量：85 是「肉眼无损」与「体积」的常用平衡点。 */
    private const val QUALITY = 85

    /**
     * 编码成本上限（字节）。超过则继续降采样重试。
     * 约 1.2MB 二进制 ≈ 1.6MB base64，多数接口能接受；再大就有被拒风险。
     */
    private const val MAX_BYTES = 1200 * 1024

    /**
     * 把 [path]（ImageStore 内部相对路径）编码成 data URI。
     *
     * @return 形如 `data:image/jpeg;base64,xxxx`；失败返回 null。
     */
    @JvmStatic
    fun toDataUri(ctx: Context?, path: String?): String? {
        if (ctx == null || path.isNullOrEmpty()) {
            return null
        }
        val app = ctx.applicationContext
        if (!ImageStore.exists(app, path)) {
            return null
        }
        val file: File = ImageStore.fileFor(app, path)
        val bytes = encodeFile(file) ?: return null
        if (bytes.isEmpty()) {
            return null
        }
        val b64 = try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Logs.w(TAG, "base64 编码失败", t)
            return null
        }
        return "data:image/jpeg;base64,$b64"
    }

    /**
     * 读文件 → 按边长降采样 → 质量压缩，必要时再多降一档。
     * 全程在调用线程执行（调用方已在线程池 / 后台线程里跑）。
     */
    private fun encodeFile(file: File): ByteArray? {
        var bmp: Bitmap? = null
        try {
            // 先只读尺寸，决定采样率（避免整张大图进内存）。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }
            var sample = 1
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

            var out = compress(bmp, QUALITY)
            // 【体积兜底】仍超上限时，逐档降质量重压；一般第一档就够。
            var q = QUALITY
            while (out != null && out.size > MAX_BYTES && q > 40) {
                q -= 15
                out = compress(bmp, q)
            }
            return out
        } catch (t: Throwable) {
            Logs.w(TAG, "图片编码失败：" + file.name, t)
            return null
        } finally {
            if (bmp != null && !bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }

    private fun compress(bmp: Bitmap, quality: Int): ByteArray? {
        return try {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            baos.toByteArray()
        } catch (t: Throwable) {
            Logs.w(TAG, "JPEG 压缩失败", t)
            null
        }
    }
}
