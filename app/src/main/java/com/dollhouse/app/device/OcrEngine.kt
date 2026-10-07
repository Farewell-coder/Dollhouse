package com.dollhouse.app.device

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ImageStore
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * 【职责】把应用内的图片扫成文字（本地离线 OCR），替掉「把原图丢给视觉模型」这条链路。
 *
 * 【为什么要换】不少中转 / 小模型根本不支持 image_url，原链路一碰上这类模型整条请求就失败；
 *   改成本地扫成文字、当正文发出去后，任何纯文本模型都能看懂图里写了什么。
 *
 * 【交互】组装请求体时由 ChatHistoryStore 读缓存文本；发请求前由 recognizePending 把
 *   还没扫过的图统一补扫一遍。识别全程在后台单线程里跑，界面上只会看到「正在看图…」。
 *
 * 【坑】识别结果（包括「没读出文字」的空串）一律落缓存：不然同一张图会在每轮请求里重扫，
 *   既费电，又把「正在看图」的等待越拖越长。
 *
 * 【坑】识别器要加载模型、是重量级对象，全局只建一个；不要每张图 new 一个。
 */
object OcrEngine {
    private const val TAG = "DollhouseOcr"

    /** 缓存落在独立 prefs，不挤进 feiyu_pet 的键空间。 */
    private const val PREFS = "feiyu_ocr"
    private const val KEY_PREFIX = "t_"

    /** 单张图的识别上限：超时按「没读出文字」收尾，不能让人偶永远停在思考中。 */
    private const val TIMEOUT_SEC = 30L

    /** 送识别的最大边长：原图动辄几千像素，缩到 1600 已足够看清文字，还能省内存。 */
    private const val MAX_DIM = 1600

    private val POOL: ExecutorService = Executors.newSingleThreadExecutor(object : ThreadFactory {
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "dollhouse-ocr")
            t.isDaemon = true
            return t
        }
    })
    private val UI = Handler(Looper.getMainLooper())

    @Volatile
    private var recognizer: TextRecognizer? = null

    fun interface Callback {
        /** 识别结束（可能一个字的没读出来，此时 text 为空串）；始终在主线程回调。 */
        fun onDone(text: String)
    }

    /** 这张图是否还没扫过。没扫过的图要先补扫，再发请求。 */
    @JvmStatic
    fun isPending(ctx: Context?, path: String?): Boolean {
        return ctx != null && path != null && path.isNotEmpty()
                && !prefs(ctx).contains(KEY_PREFIX + path)
    }

    /** 已缓存的识别文本；返回 null 表示这张图还没扫过。 */
    @JvmStatic
    fun text(ctx: Context?, path: String?): String? {
        if (ctx == null || path == null || path.isEmpty()) {
            return null
        }
        val p = prefs(ctx)
        val key = KEY_PREFIX + path
        return if (p.contains(key)) p.getString(key, "") else null
    }

    /**
     * 把一段历史里所有「有图但还没扫」的图片依次扫掉，全部结束后在主线程回调 done。
     * 【为什么串行】识别器是单例、模型只算一份，串行既不抢内存也不会互相干扰；
     *   请求窗口最多十来条，串行耗时可接受。
     */
    @JvmStatic
    fun recognizePending(ctx: Context?, history: JSONArray?, done: Runnable?) {
        val app = ctx?.applicationContext
        val todo = ArrayList<String>()
        if (app != null && history != null) {
            for (i in 0 until history.length()) {
                val o = history.optJSONObject(i) ?: continue
                val path = o.optString("image", null)
                if (path == null || path.isEmpty() || !ImageStore.exists(app, path)) {
                    continue
                }
                if (isPending(app, path) && !todo.contains(path)) {
                    todo.add(path)
                }
            }
        }
        if (todo.isEmpty() || app == null) {
            if (done != null) {
                UI.post(done)
            }
            return
        }
        runNext(app, todo, 0, done)
    }

    private fun runNext(app: Context, todo: List<String>, idx: Int, done: Runnable?) {
        if (idx >= todo.size) {
            if (done != null) {
                UI.post(done)
            }
            return
        }
        val path = todo[idx]
        recognize(app, path, Callback { t ->
            put(app, path, t)
            runNext(app, todo, idx + 1, done)
        })
    }

    /** 扫一张图；结果在主线程回调（失败同样回空串，不会把调用方卡住）。 */
    @JvmStatic
    fun recognize(ctx: Context?, path: String?, cb: Callback?) {
        val app = ctx?.applicationContext
        if (app == null || path == null || path.isEmpty()) {
            post(cb, "")
            return
        }
        val appCtx = app
        val imagePath = path
        POOL.execute(Runnable {
            var out = ""
            var bmp: Bitmap? = null
            try {
                bmp = decode(appCtx, imagePath)
                if (bmp != null) {
                    val task: Task<Text> = recognizer().process(InputImage.fromBitmap(bmp, 0))
                    out = join(Tasks.await(task, TIMEOUT_SEC, TimeUnit.SECONDS))
                }
            } catch (t: Throwable) {
                Logs.w(TAG, "识别失败，按「没读出文字」处理：" + imagePath, t)
            } finally {
                if (bmp != null && !bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            post(cb, out)
        })
    }

    private fun put(ctx: Context, path: String?, text: String?) {
        try {
            prefs(ctx).edit().putString(KEY_PREFIX + path, text ?: "").apply()
        } catch (unused: Throwable) {
        }
    }

    private fun prefs(ctx: Context): SharedPreferences {
        return ctx.applicationContext.getSharedPreferences(PREFS, 0)
    }

    private fun post(cb: Callback?, text: String) {
        if (cb == null) {
            return
        }
        UI.post(Runnable { cb.onDone(text) })
    }

    private fun recognizer(): TextRecognizer {
        recognizer?.let { return it }
        synchronized(OcrEngine) {
            recognizer?.let { return it }
            val created = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer = created
            return created
        }
    }

    /** 按行拼接识别结果：行间留换行，空行丢掉。 */
    private fun join(text: Text?): String {
        if (text == null) {
            return ""
        }
        val sb = StringBuilder()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val s = line.text
                if (s == null || s.trim().isEmpty()) {
                    continue
                }
                if (sb.length > 0) {
                    sb.append('\n')
                }
                sb.append(s.trim())
            }
        }
        return sb.toString()
    }

    /** 解码本地图片并按 MAX_DIM 降采样：先只读尺寸，再按采样率解码，避免整张大图进内存。 */
    private fun decode(ctx: Context, path: String): Bitmap? {
        val f: File = ImageStore.fileFor(ctx, path)
        if (!f.isFile) {
            return null
        }
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sample = 1
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }
}
