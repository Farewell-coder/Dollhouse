package com.dollhouse.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Arrays
import java.util.Comparator

/**
 * 【职责】聊天附件与应用内图片的本地落盘管理（应用私有目录 chat_img）。
 *
 * 【交互】PickFileActivity 选文件 → 这里存盘；ChatPanel 读回来发请求。
 *
 * 【坑】MAX_FILES / MAX_DIM 是自动清理阈值：超量会按时间删旧图，改小要当心把用户还在用的图删掉。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
object ImageStore {
    const val DIR = "chat_img"
    private const val MAX_DIM = 1024
    private const val MAX_FILES = 80

    /** 【背景裁剪】读原图的长宽，失败返回 null；只读尺寸，不解码像素。 */
    @JvmStatic
    fun size(context: Context, uri: Uri): IntArray? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                return null
            }
            BitmapFactory.decodeStream(input, null, o)
            input.close()
            return if (o.outWidth <= 0 || o.outHeight <= 0) null else intArrayOf(o.outWidth, o.outHeight)
        } catch (t: Throwable) {
            return null
        }
    }

    /**
     * 【背景裁剪】把选区裁出来、按目标比例缩放后存成聊天背景。
     * 【入参与出参】src 是相册回传的 Uri；框内坐标为源图像素；ratio = 宽/高。
     * 【为什么在这里落盘】与 saveFromUri 同一条通道，落盘后返回「chat_bg/img_xxx.jpg」相对路径，
     *   背景行读到的还是同一个相对路径，下游（ChatPanel.applyBackground / PetService）无需改动。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun saveCrop(context: Context, src: Uri, left: Int, top: Int, width: Int, height: Int,
                 ratio: Float, outW: Int): String {
        var full: Bitmap? = null
        try {
            val input = context.contentResolver.openInputStream(src)
            if (input == null) {
                throw IllegalStateException("读不到这张图")
            }
            val decoded = BitmapFactory.decodeStream(input, null, null)
            full = decoded
            input.close()
            if (decoded == null) {
                throw IllegalStateException("这张图解不开")
            }
            return saveCropFrom(context, decoded, left, top, width, height, ratio, outW)
        } finally {
            if (full != null) {
                full.recycle()
            }
        }
    }

    /**
     * 【背景裁剪·位图版】直接吃一张已经解好的图，不再二次解码（裁剪页就是这么调的）。
     * 【注意】不回收传入的 full —— 它归调用方所有（裁剪页在 onDestroy 里自己释放）。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun saveCropFrom(context: Context, full: Bitmap?, left: Int, top: Int, width: Int, height: Int,
                     ratio: Float, outW: Int): String {
        if (full == null || full.isRecycled) {
            throw IllegalStateException("这张图解不开")
        }
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        var out: FileOutputStream? = null
        try {
            val l = Math.max(0, left)
            val t = Math.max(0, top)
            val w = Math.max(1, Math.min(width, full.width - l))
            val h = Math.max(1, Math.min(height, full.height - t))
            val cropBitmap = Bitmap.createBitmap(full, l, t, w, h)
            crop = cropBitmap
            val tw = Math.max(1, Math.min(1080, outW))
            val th = Math.max(1, Math.round(tw / Math.max(0.01f, ratio)))
            val scaledBitmap = Bitmap.createScaledBitmap(cropBitmap, tw, th, true)
            scaled = scaledBitmap
            val file = File(dir(context, PetPrefs.BG_DIR), "img_" + System.currentTimeMillis() + ".jpg")
            out = FileOutputStream(file)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            return PetPrefs.BG_DIR + "/" + file.name
        } finally {
            try {
                if (out != null) {
                    out.close()
                }
            } catch (ignored: Throwable) {
            }
            if (scaled != null && scaled != crop) {
                scaled.recycle()
            }
            if (crop != null && crop != full) {
                crop.recycle()
            }
        }
    }

    @JvmStatic
    fun dir(context: Context): File {
        return dir(context, DIR)
    }

    @JvmStatic
    fun dir(context: Context, str: String): File {
        val file = File(context.filesDir, str)
        if (!file.isDirectory) {
            file.mkdirs()
        }
        return file
    }

    @JvmStatic
    fun fileFor(context: Context, str: String): File {
        return File(context.filesDir, str)
    }

    @JvmStatic
    fun exists(context: Context, str: String?): Boolean {
        return if (str == null || str.isEmpty() || !fileFor(context, str).isFile) false else true
    }

    // 从外部 Uri 落盘一张图并返回内部文件名。
    @JvmStatic
    @Throws(Exception::class)
    fun saveFromUri(context: Context, uri: Uri): String {
        return saveFromUri(context, uri, DIR)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun saveFromUri(context: Context, uri: Uri, str: String): String {
        val decodeScaled = decodeScaled(context, uri, MAX_DIM)
        if (decodeScaled == null) {
            throw IllegalStateException("这个文件不是图片，或者解不开")
        }
        val file = File(dir(context, str), "img_" + System.currentTimeMillis() + ".jpg")
        val fileOutputStream = FileOutputStream(file)
        decodeScaled.compress(Bitmap.CompressFormat.JPEG, 85, fileOutputStream)
        fileOutputStream.flush()
        fileOutputStream.close()
        decodeScaled.recycle()
        prune(context, str)
        return str + "/" + file.name
    }

    @Throws(Exception::class)
    private fun decodeScaled(context: Context, uri: Uri, i: Int): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        val openInputStream = context.contentResolver.openInputStream(uri)
        if (openInputStream == null) {
            return null
        }
        BitmapFactory.decodeStream(openInputStream, null, options)
        openInputStream.close()
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }
        var i2 = 1
        while (true) {
            val i3 = i * 2
            if (options.outWidth / i2 <= i3 && options.outHeight / i2 <= i3) {
                break
            }
            i2 *= 2
        }
        val options2 = BitmapFactory.Options()
        options2.inSampleSize = i2
        val openInputStream2 = context.contentResolver.openInputStream(uri)
        if (openInputStream2 == null) {
            return null
        }
        val decodeStream = BitmapFactory.decodeStream(openInputStream2, null, options2)
        openInputStream2.close()
        if (decodeStream == null) {
            return null
        }
        val width = decodeStream.width
        val height = decodeStream.height
        val max = Math.max(width, height)
        if (max <= i) {
            return decodeStream
        }
        val f = i.toFloat() / max
        val createScaledBitmap = Bitmap.createScaledBitmap(decodeStream, Math.max(1, Math.round(width * f)), Math.max(1, Math.round(height * f)), true)
        if (createScaledBitmap != decodeStream) {
            decodeStream.recycle()
        }
        return createScaledBitmap
    }

    // 把本地图片转成 data:image/...;base64 供接口直传。
    @JvmStatic
    @Throws(Exception::class)
    fun dataUrl(context: Context, str: String): String {
        val file = fileFor(context, str)
        if (!file.isFile) {
            throw IllegalStateException("图片文件不见了")
        }
        val length = file.length().toInt()
        val bArr = ByteArray(length)
        val fileInputStream = FileInputStream(file)
        var i = 0
        while (i < length) {
            val read = fileInputStream.read(bArr, i, length - i)
            if (read <= 0) {
                break
            }
            i += read
        }
        fileInputStream.close()
        return "data:image/jpeg;base64," + Base64.encodeToString(bArr, 2)
    }

    // 按目标边长加载并缩放位图，用于列表缩略图。
    @JvmStatic
    fun loadScaled(context: Context, str: String, i: Int): Bitmap? {
        return loadScaled(context, str, i, false)
    }

    /**
     * 同上，但可由调用方指定「是否按不透明图解码」。
     *
     * 【为什么要这个开关】背景图与聊天图片附件两条链路里落盘的图**一律是 JPEG**
     *   （见 [saveCropFrom] / [saveFromUri]），天然没有 alpha 通道。默认的 ARGB_8888
     *   每像素 4 字节，一张 1440×1440 的背景图就是 ≈8.3MB 常驻；对这类不透明图改用
     *   [android.graphics.Bitmap.Config.RGB_565]（每像素 2 字节）可省掉一半，约 4MB。
     * 【默认关闭】只在明确知道图不透明时传 true。缩略图链路（160dp）与未知来源一律保持
     *   原行为（ARGB_8888），避免给带透明通道的图丢 alpha。
     * 【代价】RGB_565 每通道 5/6/5 bit，深色渐变可能出现极轻微色带；图上方另有半透明遮罩，
     *   实际观感无可感差异，且不改任何功能行为。
     */
    @JvmStatic
    fun loadScaled(context: Context, str: String, i: Int, preferOpaque: Boolean): Bitmap? {
        try {
            val file = fileFor(context, str)
            if (!file.isFile) {
                return null
            }
            val options = BitmapFactory.Options()
            var i2 = 1
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)
            while (true) {
                val i3 = i * 2
                if (options.outWidth / i2 <= i3 && options.outHeight / i2 <= i3) {
                    val options2 = BitmapFactory.Options()
                    options2.inSampleSize = i2
                    if (preferOpaque) {
                        options2.inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    return BitmapFactory.decodeFile(file.absolutePath, options2)
                }
                i2 *= 2
            }
        } catch (unused: Throwable) {
            return null
        }
    }

    // 超过 MAX_FILES 时按时间删最旧的文件。
    private fun prune(context: Context, str: String) {
        val listFiles = dir(context, str).listFiles()
        if (listFiles == null || listFiles.size <= MAX_FILES) {
            return
        }
        Arrays.sort(listFiles, Comparator<File> { file, file2 ->
            file.lastModified().compareTo(file2.lastModified())
        })
        for (i in 0 until listFiles.size - MAX_FILES) {
            listFiles[i].delete()
        }
    }

    /* ------------------------- 文件签名常量（按魔数含义命名） ------------------------- */
    /** JPEG 起始两字节 FF D8。 */
    private const val SIG_JPG_0 = 0xFF
    private const val SIG_JPG_1 = 0xD8

    /** PNG 前四字节 89 50 4E 47。 */
    private const val SIG_PNG_0 = 0x89
    private const val SIG_PNG_1 = 0x50
    private const val SIG_PNG_2 = 0x4E
    private const val SIG_PNG_3 = 0x47

    /** GIF 前三字节 47 49 46。 */
    private const val SIG_GIF_0 = 0x47
    private const val SIG_GIF_1 = 0x49
    private const val SIG_GIF_2 = 0x46

    /** BMP 前两字节 42 4D。 */
    private const val SIG_BMP_0 = 0x42
    private const val SIG_BMP_1 = 0x4D

    /** WEBP：0-3 字节 RIFF，8-11 字节 WEBP。 */
    private const val SIG_RIFF_0 = 0x52
    private const val SIG_RIFF_1 = 0x49
    private const val SIG_RIFF_2 = 0x46
    private const val SIG_WEBP_3 = 0x50
    private const val SIG_WEBP_8 = 0x57
    private const val SIG_WEBP_9 = 0x45
    private const val SIG_WEBP_10 = 0x42

    /** 签名探测所需的头部字节数。 */
    private const val HEAD_PROBE_BYTES = 12

    /** 安静关流：失败无所谓，调用点原先各自抄了一遍「try/catch 吞掉」。 */
    private fun closeQuietly(input: InputStream?) {
        if (input != null) {
            try {
                input.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 按文件头魔数判断是否图片。
     * 【重写说明】原实现把 9 段关闭逻辑抄了 9 遍，且魔数被反编译工具误替成
     *   MAX_FILES / MAX_DIM（数值巧合相等但语义错位）。
     *   本次只做等价改写：判定顺序、比较值、短路行为与原来完全一致。
     */
    @JvmStatic
    fun looksLikeImage(context: Context, uri: Uri): Boolean {
        var input: InputStream? = null
        try {
            input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                return false
            }
            val head = ByteArray(HEAD_PROBE_BYTES)
            val read = input.read(head)
            if (read < 4) {
                return false
            }
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            if (b0 == SIG_JPG_0 && b1 == SIG_JPG_1) {
                return true
            }
            if (b0 == SIG_PNG_0 && b1 == SIG_PNG_1 && head[2].toInt() == SIG_PNG_2 && head[3].toInt() == SIG_PNG_3) {
                return true
            }
            if (b0 == SIG_GIF_0 && b1 == SIG_GIF_1 && head[2].toInt() == SIG_GIF_2) {
                return true
            }
            if (b0 == SIG_BMP_0 && b1 == SIG_BMP_1) {
                return true
            }
            if (read >= HEAD_PROBE_BYTES && b0 == SIG_RIFF_0 && b1 == SIG_RIFF_1
                    && head[2].toInt() == SIG_RIFF_2 && head[3].toInt() == SIG_RIFF_2
                    && head[8].toInt() == SIG_WEBP_8 && head[9].toInt() == SIG_WEBP_9 && head[10].toInt() == SIG_WEBP_10
                    && head[11].toInt() == SIG_WEBP_3) {
                return true
            }
            return false
        } catch (ignored: Throwable) {
            return false
        } finally {
            closeQuietly(input)
        }
    }

    // 读取文本类附件（限制最大字节数，超出即拒绝）。
    @JvmStatic
    @Throws(Exception::class)
    fun readTextFile(context: Context, uri: Uri, i: Int): String? {
        val openInputStream = context.contentResolver.openInputStream(uri)
        if (openInputStream == null) {
            return null
        }
        val byteArrayOutputStream = ByteArrayOutputStream()
        val bArr = ByteArray(8192)
        var i2 = 0
        do {
            val read = openInputStream.read(bArr)
            if (read <= 0) {
                break
            }
            byteArrayOutputStream.write(bArr, 0, read)
            i2 += read
        } while (i2 < i)
        openInputStream.close()
        val byteArray = byteArrayOutputStream.toByteArray()
        for (b in byteArray) {
            if (b.toInt() == 0) {
                return null
            }
        }
        val str = String(byteArray, Charsets.UTF_8)
        if (byteArray.size < i) {
            return str
        }
        return str + "\n…（文件太长，只读了前 " + (i / MAX_DIM) + " KB）"
    }
}
