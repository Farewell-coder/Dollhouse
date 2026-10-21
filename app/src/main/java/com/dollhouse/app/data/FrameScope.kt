package com.dollhouse.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dollhouse.app.core.Logs

/**
 * 【职责】把「一组帧图文件」读成 `BodyBaseline.Box` 列表，供导入后自动适配尺寸。
 *
 * ## 为什么单独一个类
 *
 * `BodyBaseline` 是纯逻辑（能被 JVM 单测钉死），但「读像素」必须碰 Android。
 * 把两者分开，判定规则可以测，读盘细节在这里集中处理。
 *
 * ## 性能
 *
 * 一套帧可能几十张、每张上几百 KB。逐像素扫全图在主线程会明显卡顿，所以：
 * · 一律在**后台线程**调用（本类不自起线程，由调用方保证）；
 * · 用 `inSampleSize` 降采样到长边约 [TARGET_MAX_SIDE] 再扫 —— 找包围盒只关心
 *   「哪一行/列开始有内容」，降采样对结果几乎没有影响，但耗时降一个数量级；
 * · 扫的时候按行/列整行判断，命中即停，不做逐像素统计。
 *
 * ## 安全
 *
 * · 任何一张解不出来 → 跳过它，不影响其余帧；
 * · 一张都没解出来 → 返回空列表，调用方据此放弃写入基准；
 * · 全部 OOM 也不崩（`try/catch` 兜住 `OutOfMemoryError`）。
 */
object FrameScope {

    private const val TAG = "DollhouseBaseline"

    /** 降采样后长边不超过这个像素数（够判断边界，又不会慢）。 */
    private const val TARGET_MAX_SIDE = 320

    /**
     * 扫描一组帧，返回每帧的透明包围盒（相对比例）。
     *
     * 【返回列表与输入一一对应吗】不保证：解不出来的帧会被跳过。
     * 调用方用 [BodyBaseline.isReliable] 判定，而不是靠长度对齐。
     *
     * @param paths 帧文件路径
     */
    fun scan(paths: List<String>): List<BodyBaseline.Box> {
        val out = ArrayList<BodyBaseline.Box>(paths.size)
        for (p in paths) {
            val b = scanOne(p)
            if (b != null) {
                out.add(b)
            }
        }
        return out
    }

    /**
     * 扫描一组帧并算出可以写入动作的基准。
     *
     * @return 可靠的基准；不可靠（全透明 / 尺寸差异过大 / 解不出来）时返回 null，
     *         调用方应保持「没有基准」，让播放层走原有的整画布逻辑。
     */
    fun computeBaseline(paths: List<String>): BodyBaseline.Box? {
        if (paths.isEmpty()) {
            return null
        }
        val boxes = scan(paths)
        if (!BodyBaseline.isReliable(boxes)) {
            Logs.i(TAG, "baseline skipped: frames=" + paths.size + " scanned=" + boxes.size)
            return null
        }
        return BodyBaseline.merge(boxes)
    }

    /** 单帧的透明包围盒（相对比例）；失败返回 null。 */
    fun scanOne(path: String): BodyBaseline.Box? {
        var bmp: Bitmap? = null
        try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, opts)
            val ow = opts.outWidth
            val oh = opts.outHeight
            if (ow <= 0 || oh <= 0) {
                return null
            }
            val longest = Math.max(ow, oh)
            var sample = 1
            while (longest / sample > TARGET_MAX_SIDE) {
                sample *= 2
            }
            val real = BitmapFactory.Options()
            real.inSampleSize = sample
            // 统一成 ARGB_8888：有些解码器对 16 位/索引色返回的 alpha 不可靠，
            //   透明判断会因此得出空框。
            real.inPreferredConfig = Bitmap.Config.ARGB_8888
            bmp = BitmapFactory.decodeFile(path, real) ?: return null
            return boxOf(bmp)
        } catch (t: Throwable) {
            // 包括 OutOfMemoryError —— 素材过大时宁可跳过这一帧。
            Logs.w(TAG, "scanOne failed: " + path, t)
            return null
        } finally {
            try {
                bmp?.recycle()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 从位图算透明包围盒（相对比例）。
     *
     * 【阈值】alpha > [ALPHA_MIN] 才算「有内容」。
     *   取 8 而不是 1：JPEG 转 PNG、缩放重采样都会留下 alpha 为 1~5 的极淡边缘，
     *   用 1 会让包围盒几乎等于整张画布（等于没算）。
     */
    fun boxOf(bmp: Bitmap): BodyBaseline.Box? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) {
            return null
        }
        val px = IntArray(w)
        var top = -1
        var bottom = -1
        var left = w
        var right = -1

        for (y in 0 until h) {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            var rowHas = false
            for (x in 0 until w) {
                val a = px[x] ushr 24
                if (a > ALPHA_MIN) {
                    if (!rowHas) {
                        rowHas = true
                    }
                    if (x < left) {
                        left = x
                    }
                    if (x > right) {
                        right = x
                    }
                }
            }
            if (rowHas) {
                if (top < 0) {
                    top = y
                }
                bottom = y
            }
        }
        if (top < 0 || bottom < 0 || right < left) {
            return null
        }
        // 转成 0..1 的相对比例，且左右/上下各多含一个像素宽度 ——
        //   不然边界那一列会被播放层的缩放切掉一条。
        val l = left.toFloat() / w
        val t = top.toFloat() / h
        val r = (right + 1).toFloat() / w
        val b = (bottom + 1).toFloat() / h
        val box = BodyBaseline.Box(l, t, Math.min(1f, r), Math.min(1f, b))
        return if (box.isValid()) box else null
    }

    /** alpha 超过这个值才算「有内容」（见 [boxOf] 的注释）。 */
    const val ALPHA_MIN = 8
}
