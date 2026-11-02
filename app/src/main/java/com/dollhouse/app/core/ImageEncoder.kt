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
     * 编码结果缓存。
     *
     * 【附录D-3·为什么要缓存】这条链路（`buildRequest → toDataUri → encodeFile`）
     *   每次发消息、每次重发、每次点气泡重答**都会重跑一遍**：整图解码 + 1~4 次全图
     *   JPEG 重压 + base64。同一张图在一条对话里可能被反复编码几十次，
     *   而图片文件在聊天场景里是不变的 —— 这是纯浪费，也是主线程卡顿的来源之一。
     *
     * 【缓存键为什么带 mtime 与 size】只按路径缓存会在「用户替换了同名图片」时返回旧图。
     *   加上 `lastModified` 与 `length` 之后，只要文件内容变了键就变，不需要读内容做哈希。
     *
     * 【为什么限容而不是无限缓存】缩略图/原图的 base64 字符串本身不小（几十到几百 KB），
     *   不封顶会在长聊天里慢慢吃掉堆。8 条足以覆盖「同一轮里 4 张图 + 重发」这个真实场景。
     *
     * 【线程安全】用 synchronized 保护：调用方可能来自主线程与后台线程（识图链路）。
     *   命中率相关的开销（一次 map 查找）远小于一次图片解码，加锁代价可忽略。
     */
    private const val CACHE_MAX = 8
    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > CACHE_MAX
        }
    }
    private val cacheLock = Any()

    /**
     * 编码互斥锁。
     *
     * 【为什么整段编码也要锁】（附录D-3 的「重复编码」还有第二种形态）
     *   只缓存**结果**挡不住「同一张图被两个线程同时请求」：两边都在缓存里查不到，
     *   于是并发跑两次解码 + 压缩，谁后写完谁覆盖 —— 白干一次，峰值内存也翻倍
     *   （1600 长边 ARGB_8888 ≈ 10MB/张）。
     *   把「查缓存 → 编码 → 回填」整段串起来，第二个调用方等到的是**缓存命中**，
     *   而不是再压一遍。
     *
     * 【为什么不做成按 key 的细粒度锁】这条链路同一时刻最多只有一张图在编
     *   （一次请求串行组装、识图链路一次分析一张），细粒度锁带来的复杂度没有收益。
     */
    private val encodeLock = Any()

    /** 缓存键：路径 + 大小 + 修改时间。任一变化即视为「另一张图」。 */
    private fun cacheKey(file: File): String {
        return file.absolutePath + "|" + file.length() + "|" + file.lastModified()
    }

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
        val key = cacheKey(file)
        // 【快路径】命中即返回，不加编码锁：这一步只是一次 map 查找，极短。
        synchronized(cacheLock) {
            cache[key]?.let { return it }
        }
        // 【慢路径】串行化整段「编码 + 回填」，避免并发重复编码（见 encodeLock 说明）。
        synchronized(encodeLock) {
            // 双检：等锁期间可能已经有别的线程编好了同一张图。
            synchronized(cacheLock) {
                cache[key]?.let { return it }
            }
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
            val uri = "data:image/jpeg;base64,$b64"
            synchronized(cacheLock) {
                cache[key] = uri
            }
            return uri
        }
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
            // 目标：解码后的长边 ≤ MAX_DIM。
            // 【附录D-3·为什么原来的 `> MAX_DIM * 2` 是错的】原条件等于放行到 3200，
            //   与 MAX_DIM 的注释口径矛盾。3200×3200 的 ARGB_8888 ≈ 40MB，按发图配额
            //   4 张算峰值 160MB —— 中端机要么 OOM，要么在压缩阶段 ANR。
            // 【代价】inSampleSize 只能取 2 的幂，1601×1601 会掉到 801×801；
            //   视觉模型侧普遍也会缩到 1024 内，这点像素差换 4 倍内存是划算的。
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM) {
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
