package com.dollhouse.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.dollhouse.app.core.Logs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 【职责】把「作图软件导出的成果文件」解析成一串 PNG 帧，供动作系统直接使用。
 *
 * 【需求原文】「添加人偶那里 本地添加 你不要给我引导到相册 应该是我在 PS 里面把所有的图层
 *   画好之后在 AE 里面导出 json 文件 应该是导入这个文件」＋
 *   「我那个作图的软件只是个例子 请兼容绝大部分的成果图」。
 *
 * 【设计口径·按文件形态分流，不绑定某一家软件】
 *
 * | 形态 | 判据 | 处理 |
 * |---|---|---|
 * | 单张静态图 | png / jpg / webp / bmp / heic | 一帧 |
 * | 动图 | gif / apng / webp 动图 | 系统解码器逐帧 |
 * | 精灵图 | png + 同名 json/plist/atlas | 按描述文件里的矩形切 |
 * | Lottie / AE | json 有 layers 且带 ip/op 或 fr | 逐帧渲染（含内嵌图与外部图） |
 * | 帧清单 json | 顶层数组，或含 frames/images/sprites/sequence | 按顺序收帧 |
 * | 压缩包 | zip / apk 改名（靠魔数 PK） | 解包后递归识别 |
 * | 纯图序列 | 用户一次选多张 | 按选择顺序 |
 *
 * 【为什么必须这么做】各家导出格式差异极大（Bodymovin 出 Lottie json、PS 出 PSD 或切图、
 *   Spine 出 json+atlas+png、TexturePacker 出 plist+png）。写死一种必然不够用，
 *   所以按「这个文件里到底有没有帧信息」判断：认得出就用，认不出给明确提示。
 *
 * 【线程】全部方法都是阻塞 IO，调用方必须放后台线程（本类不做线程切换）。
 */
object AnimImport {

    private const val LOG_TAG = "DollhouseImport"

    /** 单张帧图允许的最大边长；超过就缩放，避免一整套大图把内存吃满。 */
    private const val MAX_EDGE = 1024

    /** Lottie 逐帧渲染的帧数上限。 */
    private const val LOTTIE_MAX_FRAMES = 120

    /** 一次导入的结果。 */
    class Result(
        val frames: List<String>,
        val fps: Int,
        val note: String,
        val ok: Boolean
    )

    /**
     * 解析一批用户选中的文件，把帧写进 [dstDir]。
     *
     * 【为什么允许一批】用户可能「选一个 json」也可能「选一整套 png」，
     *   两条路都走这里，避免调用方分叉。
     */
    fun import(ctx: Context, paths: List<String>, dstDir: File): Result {
        if (!dstDir.isDirectory) {
            dstDir.mkdirs()
        }
        val out = ArrayList<String>()
        var fps = DollAnim.FPS_DEFAULT
        var note = ""

        for (p in paths) {
            if (out.size >= DollAnim.FRAMES_MAX) {
                break
            }
            val f = File(p)
            if (!f.isFile) {
                continue
            }
            val kind = sniff(f)
            Logs.i(LOG_TAG, "导入文件 " + f.name + " 判定为 " + kind)
            when (kind) {
                K_LOTTIE -> {
                    val r = lottieFrames(ctx, f, dstDir, out.size)
                    if (r.first.isNotEmpty()) {
                        if (r.second > 0) {
                            fps = r.second
                        }
                        note = "已按动画工程逐帧渲染出 " + r.first.size + " 帧。"
                    } else if (note.isEmpty()) {
                        note = "这个 json 像是动画工程，但没渲染出帧（可能只含矢量形状）。"
                    }
                    out.addAll(r.first)
                }
                K_FRAMEJSON -> {
                    val r = framesFromJson(ctx, f, dstDir, out.size)
                    if (r.isNotEmpty()) {
                        note = "已从 json 里取出 " + r.size + " 帧。"
                    } else if (note.isEmpty()) {
                        note = "这个 json 里没找到可用的帧（既没有图片路径，也没有内嵌图片数据）。"
                    }
                    out.addAll(r)
                }
                K_SPRITESHEET -> {
                    val r = spriteSheetFrames(ctx, f, dstDir, out.size)
                    if (r.isNotEmpty()) {
                        note = "已按描述文件从这张大图里切出 " + r.size + " 帧。"
                    } else if (note.isEmpty()) {
                        note = "这张图配了描述文件，但没切出帧（格式没认出来）。"
                    }
                    out.addAll(r)
                }
                K_ARCHIVE -> {
                    val r = fromArchive(ctx, f, dstDir, out.size)
                    if (r.first.isNotEmpty()) {
                        note = "已从压缩包里取出 " + r.first.size + " 帧。"
                    } else if (note.isEmpty()) {
                        note = "压缩包里没找到能当帧用的图片。"
                    }
                    out.addAll(r.first)
                }
                K_ANIM -> {
                    val r = animFrames(ctx, f, dstDir, out.size)
                    if (r.isNotEmpty()) {
                        note = "已把动图拆成 " + r.size + " 帧。"
                    } else if (note.isEmpty()) {
                        note = "这个动图没拆出帧（系统解码器不支持）。"
                    }
                    out.addAll(r)
                }
                K_IMAGE -> {
                    val saved = copyImage(ctx, f, dstDir, out.size)
                    if (saved != null) {
                        out.add(saved)
                    }
                }
                else -> {
                    if (note.isEmpty()) {
                        note = "「" + f.name + "」不是能识别的图片或动画文件。"
                    }
                }
            }
        }

        if (out.isEmpty()) {
            return Result(emptyList(), fps, note.ifEmpty { "没读出任何可用的帧。" }, false)
        }
        if (note.isEmpty()) {
            note = "已导入 " + out.size + " 帧。"
        }
        return Result(out, fps, note, true)
    }

    /* ============================ 形态判定 ============================ */

    private const val K_LOTTIE = "lottie"
    private const val K_FRAMEJSON = "framejson"
    private const val K_SPRITESHEET = "spritesheet"
    private const val K_ARCHIVE = "archive"
    private const val K_ANIM = "anim"
    private const val K_IMAGE = "image"
    private const val K_UNKNOWN = "unknown"

    /**
     * 按「魔数 + 扩展名 +（json 时）结构」判定文件形态。
     *
     * 【为什么不能只看扩展名】用户从作图软件导出的文件经常被改名
     *   （anim.zip 实际是 apk、out.txt 实际是 json）；只看扩展名会漏判。
     *
     * 【r13 修 bug·「动作导入失败」】文件选择器（[com.dollhouse.app.PickFileActivity]）
     *   为了让调用方拿到「不受临时授权时效影响」的稳定副本，会把选中项按**原字节**
     *   拷进私有 cacheDir，文件名统一是 `raw_<时间戳>_<随机>.bin` —— **扩展名被抹掉了**。
     *   于是原来那套「靠 endsWith 判类型」在这里全部落空（连 PNG 都认不出），
     *   用户无论选什么都会得到「不是能识别的图片或动画文件」。
     *   修法：**魔数优先**（PNG / JPEG / GIF / WebP / BMP / AVIF 都有固定头字节），
     *   扩展名只作为补充线索。
     */
    private fun sniff(f: File): String {
        val name = f.name.lowercase()
        val head = readHead(f, 16)
        // ① zip 家族（含 apk / jar 等一切 zip 容器）：靠魔数，不看扩展名。
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
            return K_ARCHIVE
        }
        // ② 图片魔数：文件名可能被改成 .bin，必须靠头部字节认。
        val magic = imageMagic(head)
        if (magic != null) {
            // 有同名描述文件（json / plist / atlas）时按精灵图切；否则当单张图。
            return if (hasSidecar(f)) K_SPRITESHEET else magic
        }
        // ③ json：可能没扩展名（out.txt / .bin），靠首个非空白字符是 { 或 [ 判。
        val text = readText(f, 4 * 1024 * 1024)
        if (name.endsWith(".json") || looksLikeJson(text)) {
            return jsonKind(f)
        }
        if (name.endsWith(".zip")) {
            return K_ARCHIVE
        }
        return K_UNKNOWN
    }

    /**
     * 按图片头字节判类型。
     *
     * 【为什么动图与静图要分开返】GIF / 动态 WebP / APNG 要拆帧，PNG / JPEG 直接当单张。
     */
    private fun imageMagic(head: ByteArray): String? {
        if (head.size < 12) {
            return null
        }
        val b = head
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte()
            && b[3] == 0x47.toByte()) {
            // APNG 在 PNG 之后再带 acTL 块；这里按「疑似动图」处理交给解码器，
            // 拆不出多帧时会自然退化成单张（animFrames 内部已容错）。
            return if (isApng(b)) K_ANIM else K_IMAGE
        }
        // JPEG: FF D8 FF
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) {
            return K_IMAGE
        }
        // GIF: 47 49 46 38（GIF8）
        if (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()
            && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()) {
            return K_ANIM
        }
        // BMP: 42 4D（BM）
        if (b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()) {
            return K_IMAGE
        }
        // WebP: RIFF....WEBP
        if (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte()
            && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()
            && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte()
            && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()) {
            // 动图 WebP 带头部 "VP8X" 且置了动画标志；粗略判：含 ANIM 块即当动图。
            return if (containsAscii(b, "ANIM")) K_ANIM else K_IMAGE
        }
        // AVIF / HEIC：ftyp 盒（偏移 4 起）
        if (b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte()
            && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()) {
            return K_IMAGE
        }
        return null
    }

    /** PNG 里有没有 acTL 块（有则是 APNG 动图）。 */
    private fun isApng(head: ByteArray): Boolean {
        return containsAscii(head, "acTL")
    }

    private fun containsAscii(buf: ByteArray, s: String): Boolean {
        val t = s.toByteArray(Charsets.US_ASCII)
        if (buf.size < t.size) {
            return false
        }
        for (i in 0..buf.size - t.size) {
            var hit = true
            for (j in t.indices) {
                if (buf[i + j] != t[j]) {
                    hit = false
                    break
                }
            }
            if (hit) {
                return true
            }
        }
        return false
    }

    /** 文本看起来是 json（首个非空白字符是 { 或 [）。 */
    private fun looksLikeJson(text: String?): Boolean {
        if (text == null) {
            return false
        }
        for (ch in text) {
            if (ch.isWhitespace() || ch == '\uFEFF') {
                continue
            }
            return ch == '{' || ch == '['
        }
        return false
    }

    /** json 的进一步细分：动画工程（layers）→ 帧清单 → 都不是。 */
    private fun jsonKind(f: File): String {
        val text = readText(f, 4 * 1024 * 1024) ?: return K_UNKNOWN
        return try {
            val t = text.trim()
            if (t.startsWith("[")) {
                return K_FRAMEJSON
            }
            val o = JSONObject(t)
            val hasLayers = o.optJSONArray("layers") != null
            val hasTiming = o.has("ip") || o.has("op") || o.has("fr")
            if (hasLayers && hasTiming) {
                return K_LOTTIE
            }
            if (o.optJSONArray("frames") != null || o.optJSONArray("images") != null
                || o.optJSONArray("sprites") != null || o.optJSONArray("sequence") != null) {
                return K_FRAMEJSON
            }
            K_UNKNOWN
        } catch (t: Throwable) {
            K_UNKNOWN
        }
    }

    /** 同目录下有没有可配对的描述文件（精灵图的常见搭配）。 */
    private fun hasSidecar(img: File): Boolean {
        return findSidecar(img) != null
    }

    /**
     * 找与 [img] 配对的描述文件。
     *
     * 【两条口径】
     *  ① 严格同名（同基名 + json/plist/atlas/txt 扩展名）—— 原始目录里就是这个约定；
     *  ② **兜底**：同一批文件里如果**恰好只有一个**描述文件，就认它。
     *
     * 【为什么要兜底】文件选择器会把选中项复制进私有 cacheDir，且为了避免互相覆盖，
     *   每个副本的文件名都带了时间戳与随机串（`raw_<ts>_<rand>.png`）——
     *   于是 `sheet.png` 与 `sheet.json` 到这边已成了两个毫不相关的名字，
     *   严格同名永远配不上。用户在「一次选中 png + json」时，兜底那条路才是能走通的那条。
     *   多个描述文件时不兜底（无法判断哪个对应哪张图，宁可当单图也不乱切）。
     */
    private fun findSidecar(img: File): File? {
        val base = img.name.substringBeforeLast('.').lowercase()
        val dir = img.parentFile ?: return null
        val kids = dir.listFiles() ?: return null
        val descs = ArrayList<File>()
        for (k in kids) {
            if (k === img || !k.isFile) {
                continue
            }
            val n = k.name.lowercase()
            if (!isDescName(n)) {
                continue
            }
            if (n.startsWith(base + ".")) {
                return k
            }
            descs.add(k)
        }
        return if (descs.size == 1) descs[0] else null
    }

    /** 是不是描述文件（json / plist / atlas / texture 描述 txt）。 */
    private fun isDescName(name: String): Boolean {
        return name.endsWith(".json") || name.endsWith(".plist")
                || name.endsWith(".atlas") || name.endsWith(".txt")
    }

    /* ============================ 帧清单 json ============================ */

    /**
     * 从 json 的「帧清单」里取帧。
     *
     * 【兼容哪些写法】至少四种：
     *   ① ["1.png","2.png"]
     *   ② {"frames":[{"file":"1.png"}, ...]}
     *   ③ {"frames":["data:image/png;base64,...", ...]}（内嵌图）
     *   ④ {"images":[{"name":"1","data":"..."}]}
     *   全部收，认不出的条目跳过而不是整体失败。
     */
    private fun framesFromJson(ctx: Context, f: File, dstDir: File, start: Int): List<String> {
        val text = readText(f, 8 * 1024 * 1024) ?: return emptyList()
        val out = ArrayList<String>()
        val base = f.parentFile
        try {
            val t = text.trim()
            val arr: JSONArray = if (t.startsWith("[")) {
                JSONArray(t)
            } else {
                val o = JSONObject(t)
                o.optJSONArray("frames") ?: o.optJSONArray("images")
                ?: o.optJSONArray("sprites") ?: o.optJSONArray("sequence") ?: JSONArray()
            }
            var i = 0
            while (i < arr.length() && out.size < DollAnim.FRAMES_MAX - start) {
                val item = arr.opt(i)
                val saved = when (item) {
                    is String -> fromString(ctx, item, base, dstDir, start + out.size)
                    is JSONObject -> {
                        val data = item.optString("data", "")
                        if (data.startsWith("data:")) {
                            fromDataUri(ctx, data, dstDir, start + out.size)
                        } else {
                            val file = item.optString("file", item.optString("name", item.optString("path", "")))
                            if (file.isEmpty()) null else fromString(ctx, file, base, dstDir, start + out.size)
                        }
                    }
                    else -> null
                }
                if (saved != null) {
                    out.add(saved)
                }
                i++
            }
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "解析帧清单失败", t)
        }
        return out
    }

    /** 一条帧条目：base64 dataURI，或相对 / 绝对路径。 */
    private fun fromString(ctx: Context, s: String, base: File?, dstDir: File, index: Int): String? {
        if (s.startsWith("data:")) {
            return fromDataUri(ctx, s, dstDir, index)
        }
        if (s.isEmpty()) {
            return null
        }
        val cand = if (s.startsWith("/")) File(s) else File(base, s)
        return if (cand.isFile) copyImage(ctx, cand, dstDir, index) else null
    }

    /** 内嵌 base64 图片（data:image/png;base64,....）。 */
    private fun fromDataUri(ctx: Context, uri: String, dstDir: File, index: Int): String? {
        return try {
            val comma = uri.indexOf(',')
            if (comma < 0) {
                return null
            }
            val b64 = uri.substring(comma + 1)
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            writeFrame(ctx, bmp, dstDir, index)
        } catch (t: Throwable) {
            null
        }
    }

    /* ============================ 精灵图 ============================ */

    /**
     * 精灵图：按同目录描述文件里的矩形切帧。
     *
     * 【兼容哪些写法】
     *   - TexturePacker json（hash 或 array）：frames[].frame = {x,y,w,h}
     *   - Cocos plist：frame = "{{x,y},{w,h}}"
     *   - Spine / DragonBones atlas：文本行 xy: x, y + size: w, h
     */
    private fun spriteSheetFrames(ctx: Context, img: File, dstDir: File, start: Int): List<String> {
        val sheet = BitmapFactory.decodeFile(img.absolutePath) ?: return emptyList()
        // 【r23·修位图泄漏】这张大图原来**从头到尾没人回收**（decodeFile 之后没有任何
        //   recycle 调用，全函数三条 return 路径都直接返回）——一张 4096×4096 的精灵图
        //   是 64MB，导一次动作包就常驻到 GC 才放手，连续导两个就有 OOM 风险。
        //   改成 try/finally 统一回收。
        val out = ArrayList<String>()
        try {
            val desc = findSidecar(img) ?: return emptyList()
            val rects = parseRects(desc)
            if (rects.isEmpty()) {
                return emptyList()
            }
            for (r in rects) {
                if (out.size >= DollAnim.FRAMES_MAX - start) {
                    break
                }
                val x = Math.max(0, r[0])
                val y = Math.max(0, r[1])
                val w = Math.min(sheet.width - x, r[2])
                val h = Math.min(sheet.height - y, r[3])
                if (w <= 0 || h <= 0) {
                    continue
                }
                try {
                    val cut = Bitmap.createBitmap(sheet, x, y, w, h)
                    // 【关键】`createBitmap` 在「切出来的区域 = 整图」时会**直接返回原对象**。
                    //   此时不能把所有权交给 writeFrame —— 那会把 sheet 回收掉，
                    //   后面的帧全部炸在「trying to use a recycled bitmap」上。
                    val saved = writeFrame(ctx, cut, dstDir, start + out.size, owned = cut !== sheet)
                    if (saved != null) {
                        out.add(saved)
                    }
                } catch (t: Throwable) {
                }
            }
        } finally {
            if (!sheet.isRecycled) {
                sheet.recycle()
            }
        }
        return out
    }

    /** 解析描述文件里的帧矩形，返回 [x, y, w, h] 列表。 */
    private fun parseRects(desc: File): List<IntArray> {
        val out = ArrayList<IntArray>()
        val name = desc.name.lowercase()
        val text = readText(desc, 4 * 1024 * 1024) ?: return out
        try {
            if (name.endsWith(".json")) {
                val o = JSONObject(text.trim())
                val frames = o.optJSONArray("frames")
                if (frames != null) {
                    for (i in 0 until frames.length()) {
                        val it = frames.optJSONObject(i) ?: continue
                        val r = rectOf(it.optJSONObject("frame") ?: it)
                        if (r != null) {
                            out.add(r)
                        }
                    }
                } else {
                    val fo = o.optJSONObject("frames")
                    if (fo != null) {
                        val keys = fo.keys()
                        while (keys.hasNext()) {
                            val it = fo.optJSONObject(keys.next()) ?: continue
                            val r = rectOf(it.optJSONObject("frame") ?: it)
                            if (r != null) {
                                out.add(r)
                            }
                        }
                    }
                }
                return out
            }
            if (name.endsWith(".plist")) {
                // Cocos 的 frame 值形如 {{x,y},{w,h}}。
                val re = Regex("\\{\\{(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)\\},\\{(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)\\}\\}")
                for (m in re.findAll(text)) {
                    out.add(
                        intArrayOf(
                            m.groupValues[1].toFloat().toInt(),
                            m.groupValues[2].toFloat().toInt(),
                            m.groupValues[3].toFloat().toInt(),
                            m.groupValues[4].toFloat().toInt()
                        )
                    )
                }
                return out
            }
            // Spine / DragonBones 的 .atlas 文本格式：xy / size 分两行。
            var x = Int.MIN_VALUE
            var y = 0
            var w = 0
            var h = 0
            for (raw in text.split('\n')) {
                val line = raw.trim()
                if (line.startsWith("xy:")) {
                    val p = line.removePrefix("xy:").split(',')
                    if (p.size >= 2) {
                        x = p[0].trim().toFloat().toInt()
                        y = p[1].trim().toFloat().toInt()
                    }
                } else if (line.startsWith("size:")) {
                    val p = line.removePrefix("size:").split(',')
                    if (p.size >= 2) {
                        w = p[0].trim().toFloat().toInt()
                        h = p[1].trim().toFloat().toInt()
                    }
                }
                if (x != Int.MIN_VALUE && w > 0 && h > 0) {
                    out.add(intArrayOf(x, y, w, h))
                    x = Int.MIN_VALUE
                    w = 0
                    h = 0
                }
            }
            return out
        } catch (t: Throwable) {
            return out
        }
    }

    private fun rectOf(o: JSONObject): IntArray? {
        return try {
            val x = o.optInt("x", o.optInt("left", Int.MIN_VALUE))
            val y = o.optInt("y", o.optInt("top", Int.MIN_VALUE))
            val w = o.optInt("w", o.optInt("width", 0))
            val h = o.optInt("h", o.optInt("height", 0))
            if (x == Int.MIN_VALUE || y == Int.MIN_VALUE || w <= 0 || h <= 0) {
                null
            } else {
                intArrayOf(x, y, w, h)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /* ============================ Lottie / AE 工程 ============================ */

    /**
     * 把动画工程 json 逐帧渲染成位图。
     *
     * 【支持到什么程度·说清楚】只做**图像图层**（layer 的 refId 指向 assets 里的图片）：
     *   逐帧算每层的 transform（位置 / 缩放 / 旋转 / 透明度），按层序叠画。
     *   这正是「PS 画图层 → AE 里做位移动画 → 导出 json」这类工作流的标准产物。
     *
     * 【不支持什么】纯矢量形状图层（shape / solid / text）不渲染 —— 那需要完整的
     *   贝塞尔求值器，代价远超收益。遇到这类工程会返回空并给一句明确说明，
     *   用户可改用「导出 PNG 序列」或「导出精灵图」两种更通用的方式。
     *
     * @return Pair(帧路径列表, 建议 fps)；fps 为 0 表示沿用默认。
     */
    private fun lottieFrames(ctx: Context, f: File, dstDir: File, start: Int): Pair<List<String>, Int> {
        val text = readText(f, 8 * 1024 * 1024) ?: return Pair(emptyList(), 0)
        return try {
            val root = JSONObject(text.trim())
            val fr = root.optDouble("fr", 0.0)
            val ip = root.optInt("ip", 0)
            val op = root.optInt("op", 0)
            val w = root.optInt("w", 0)
            val h = root.optInt("h", 0)
            if (op <= ip || w <= 0 || h <= 0) {
                return Pair(emptyList(), 0)
            }
            // 采样帧数：按工程真实长度取，但压到上限内。
            val total = op - ip
            val step = Math.max(1, Math.ceil(total.toDouble() / LOTTIE_MAX_FRAMES).toInt())
            val frameIdx = ArrayList<Int>()
            var k = ip
            while (k < op && frameIdx.size < LOTTIE_MAX_FRAMES) {
                frameIdx.add(k)
                k += step
            }
            if (frameIdx.isEmpty()) {
                return Pair(emptyList(), 0)
            }
            val assets = root.optJSONArray("assets")
            val layers = root.optJSONArray("layers") ?: return Pair(emptyList(), 0)
            // 预解图片资源（refId → Bitmap），同一张图只解一次。
            val bitmaps = HashMap<String, Bitmap>()
            try {
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val id = a.optString("id", "")
                        val bmp = assetBitmap(a, f.parentFile) ?: continue
                        if (id.isNotEmpty()) {
                            bitmaps[id] = bmp
                        } else {
                            // 【r23·修位图泄漏】没有 id 的资源位图原来**没人回收**：
                            //   解出来了、没进 map、也没人引用 —— 纯泄漏。
                            if (!bmp.isRecycled) {
                                bmp.recycle()
                            }
                        }
                    }
                }
                if (bitmaps.isEmpty()) {
                    // 没有图片资源的工程（纯矢量）—— 明确不支持，别产出空帧。
                    return Pair(emptyList(), 0)
                }
                val out = ArrayList<String>()
                for (fi in frameIdx) {
                    if (out.size >= DollAnim.FRAMES_MAX - start) {
                        break
                    }
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.TRANSPARENT)
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    // Lottie 的 layers 数组是**从上到下**（索引 0 在最上层），绘制要反着来。
                    for (i in layers.length() - 1 downTo 0) {
                        val l = layers.optJSONObject(i) ?: continue
                        val refId = l.optString("refId", "")
                        val img = bitmaps[refId] ?: continue
                        val li = l.optInt("ip", ip)
                        val lo = l.optInt("op", op)
                        if (fi < li || fi >= lo) {
                            continue
                        }
                        drawLayer(canvas, img, l, fi, w, h, paint)
                    }
                    // writeFrame 默认接管位图所有权（落盘后回收），无需再手动 recycle。
                    val saved = writeFrame(ctx, bmp, dstDir, start + out.size)
                    if (saved != null) {
                        out.add(saved)
                    }
                }
                val fps = if (fr > 0.5) Math.max(1, Math.min(30, Math.round(fr).toInt())) else 0
                Pair(out, fps)
            } finally {
                // 【r23·修位图泄漏】这批资源位图是**逐帧绘制用的底图**，渲染结束就没用了。
                //   原来全程不回收：一个 1024×1024 的 AE 工程带 20 张素材图 ≈ 80MB 常驻，
                //   导完不退、再导第二个就直接 OOM。整批渲染完毕立刻回收。
                for (b in bitmaps.values) {
                    try {
                        if (!b.isRecycled) {
                            b.recycle()
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                bitmaps.clear()
            }
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "渲染动画工程失败", t)
            Pair(emptyList(), 0)
        }
    }

    /** 取一个 asset 的位图：支持内嵌 base64（p）与外部路径（u + p）。 */
    private fun assetBitmap(a: JSONObject, base: File?): Bitmap? {
        val p = a.optString("p", "")
        val u = a.optString("u", "")
        try {
            if (p.startsWith("data:")) {
                val comma = p.indexOf(',')
                if (comma >= 0) {
                    val bytes = android.util.Base64.decode(p.substring(comma + 1), android.util.Base64.DEFAULT)
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            if (p.isEmpty()) {
                return null
            }
            val cand = if (p.startsWith("/")) File(p) else File(File(base, u.trim('/')), p)
            if (cand.isFile) {
                return BitmapFactory.decodeFile(cand.absolutePath)
            }
        } catch (t: Throwable) {
        }
        return null
    }

    /**
     * 画一个图像图层：按 [frame] 求 transform，再画到画布。
     *
     * 【坐标口径】Lottie 的图层位置以**合成中心**为原点（Bodymovin 的约定），
     *   而画布坐标以左上角为原点 —— 所以先按合成中心定位，再叠图层位置。
     */
    private fun drawLayer(canvas: Canvas, img: Bitmap, l: JSONObject, frame: Int,
                          compW: Int, compH: Int, paint: Paint) {
        val ks = l.optJSONObject("ks") ?: return
        val pos = valueAt(ks.optJSONObject("p"), frame, 2)
        val scale = valueAt(ks.optJSONObject("s"), frame, 2)
        val rot = valueAt(ks.optJSONObject("r"), frame, 1)
        val opa = valueAt(ks.optJSONObject("o"), frame, 1)
        val anchor = valueAt(ks.optJSONObject("a"), frame, 2)
        val px = if (pos != null && pos.isNotEmpty()) pos[0] else compW / 2f
        val py = if (pos != null && pos.size > 1) pos[1] else compH / 2f
        val sx = if (scale != null && scale.isNotEmpty() && scale[0] != 0f) scale[0] / 100f else 1f
        val sy = if (scale != null && scale.size > 1 && scale[1] != 0f) scale[1] / 100f else 1f
        val r = if (rot != null && rot.isNotEmpty()) rot[0] else 0f
        val alpha = if (opa != null && opa.isNotEmpty()) opa[0] else 100f
        val ax = if (anchor != null && anchor.isNotEmpty()) anchor[0] else img.width / 2f
        val ay = if (anchor != null && anchor.size > 1) anchor[1] else img.height / 2f
        val save = canvas.save()
        canvas.translate(px, py)
        canvas.rotate(r)
        canvas.scale(sx, sy)
        canvas.translate(-ax, -ay)
        paint.alpha = Math.max(0, Math.min(255, Math.round(alpha * 255f / 100f)))
        canvas.drawBitmap(img, 0f, 0f, paint)
        canvas.restoreToCount(save)
    }

    /**
     * 求某个属性在第 [frame] 帧的值。
     *
     * 【Lottie 的两种形态】静态属性直接是 {a:0, k:数值或数组}；
     *   动画属性是 {a:1, k:[{t:起始帧, s:[值]}, ...]}。
     *   这里对动画属性做**线性插值**（够用；缓动曲线对帧序列观感影响很小）。
     */
    private fun valueAt(prop: JSONObject?, frame: Int, dim: Int): FloatArray? {
        if (prop == null) {
            return null
        }
        try {
            val animated = prop.optInt("a", 0) == 1
            val k = prop.opt("k")
            if (!animated) {
                return toFloats(k, dim)
            }
            val arr = k as? JSONArray ?: return null
            if (arr.length() == 0) {
                return null
            }
            var prev: JSONObject? = null
            var next: JSONObject? = null
            for (i in 0 until arr.length()) {
                val kf = arr.optJSONObject(i) ?: continue
                val t = kf.optInt("t", 0)
                if (t <= frame) {
                    prev = kf
                } else {
                    next = kf
                    break
                }
            }
            val cur = prev ?: arr.optJSONObject(0)
            if (cur == null) {
                return null
            }
            val s = toFloats(cur.opt("s"), dim) ?: toFloats(cur.opt("k"), dim) ?: return null
            if (next == null) {
                return s
            }
            val t0 = cur.optInt("t", frame)
            val t1 = next.optInt("t", t0 + 1)
            val e = toFloats(next.opt("s"), dim) ?: toFloats(cur.opt("e"), dim) ?: return s
            if (t1 <= t0) {
                return e
            }
            val p = Math.max(0f, Math.min(1f, (frame - t0).toFloat() / (t1 - t0).toFloat()))
            val out = FloatArray(dim)
            for (i in 0 until dim) {
                val a = if (i < s.size) s[i] else 0f
                val b = if (i < e.size) e[i] else a
                out[i] = a + (b - a) * p
            }
            return out
        } catch (t: Throwable) {
            return null
        }
    }

    private fun toFloats(v: Any?, dim: Int): FloatArray? {
        return try {
            when (v) {
                is Number -> {
                    val out = FloatArray(dim)
                    out[0] = v.toFloat()
                    out
                }
                is JSONArray -> {
                    val out = FloatArray(dim)
                    for (i in 0 until Math.min(dim, v.length())) {
                        out[i] = v.optDouble(i, 0.0).toFloat()
                    }
                    out
                }
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /* ============================ 动图 ============================ */

    /**
     * 动图（GIF / WebP / APNG）逐帧拆解。
     *
     * 【为什么两套解码器】`Movie` 只在 API 24+ 可用且只认 GIF；
     *   `AnimatedImageDrawable` 从 API 28 起支持 GIF / WebP / APNG。
     *   `AnimatedImageDrawable` 的时间轴不可由外部推进，所以这里只在
     *   API 24+ 的 `Movie` 路径上做真逐帧；API 28+ 若拆不出多帧就回落单帧。
     */
    private fun animFrames(ctx: Context, f: File, dstDir: File, start: Int): List<String> {
        val out = ArrayList<String>()
        // 先试 Movie（能真按时间轴取帧）。
        try {
            val movie = android.graphics.Movie.decodeFile(f.absolutePath)
            if (movie != null) {
                val dur = movie.duration()
                if (dur > 0) {
                    val n = Math.min(DollAnim.FRAMES_MAX - start, 60)
                    for (i in 0 until n) {
                        val ms = (dur.toLong() * i / n).toInt()
                        movie.setTime(ms)
                        val bmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp)
                        movie.draw(c, 0f, 0f)
                        val saved = writeFrame(ctx, bmp, dstDir, start + out.size)
                        if (saved != null) {
                            out.add(saved)
                        }
                    }
                    return out
                }
            }
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "Movie 拆帧失败", t)
        }
        // API 28+：AnimatedImageDrawable 至少能给出第一帧。
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val src = android.graphics.ImageDecoder.createSource(f)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(src)
                if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                    val w = drawable.intrinsicWidth
                    val h = drawable.intrinsicHeight
                    if (w > 0 && h > 0) {
                        drawable.setBounds(0, 0, w, h)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp)
                        drawable.draw(c)
                        val saved = writeFrame(ctx, bmp, dstDir, start)
                        if (saved != null) {
                            out.add(saved)
                        }
                    }
                } else {
                    val bmp = android.graphics.ImageDecoder.decodeBitmap(src)
                    val saved = writeFrame(ctx, bmp, dstDir, start)
                    if (saved != null) {
                        out.add(saved)
                    }
                }
                if (out.isNotEmpty()) {
                    return out
                }
            } catch (t: Throwable) {
                Logs.w(LOG_TAG, "ImageDecoder 拆帧失败", t)
            }
        }
        // 最后回落：当静态图收一帧。
        val saved = copyImage(ctx, f, dstDir, start)
        return if (saved == null) emptyList() else listOf(saved)
    }

    /* ============================ 压缩包 ============================ */

    /** 压缩包（zip / apk / 改名包）：解出里面的图片，再递归识别。 */
    private fun fromArchive(ctx: Context, f: File, dstDir: File, start: Int): Pair<List<String>, Int> {
        val out = ArrayList<String>()
        var tmp: File? = null
        try {
            tmp = File(dstDir, "_unzip_" + System.currentTimeMillis())
            if (!tmp.mkdirs()) {
                return Pair(out, 0)
            }
            val zf = ZipFile(f)
            val entries = zf.entries()
            var n = 0
            while (entries.hasMoreElements() && out.size < DollAnim.FRAMES_MAX - start) {
                val e = entries.nextElement()
                if (e.isDirectory) {
                    continue
                }
                val nm = e.name.lowercase()
                val wanted = nm.endsWith(".png") || nm.endsWith(".jpg") || nm.endsWith(".jpeg")
                    || nm.endsWith(".webp") || nm.endsWith(".gif")
                if (!wanted) {
                    continue
                }
                val target = File(tmp, "e" + n + "_" + File(e.name).name)
                try {
                    zf.getInputStream(e).use { ins ->
                        target.outputStream().use { os -> ins.copyTo(os) }
                    }
                } catch (t: Throwable) {
                    continue
                }
                n++
                val kind = sniff(target)
                if (kind == K_ANIM) {
                    out.addAll(animFrames(ctx, target, dstDir, start + out.size))
                } else {
                    val saved = copyImage(ctx, target, dstDir, start + out.size)
                    if (saved != null) {
                        out.add(saved)
                    }
                }
            }
            zf.close()
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "解包失败", t)
        } finally {
            try {
                tmp?.let { deleteTree(it) }
            } catch (t: Throwable) {
            }
        }
        return Pair(out, 0)
    }

    /* ============================ 落盘工具 ============================ */

    /**
     * 把一张图收成帧：必要时缩放，统一命名 i0000.png。
     *
     * 【为什么统一转 PNG】用户导入的可能是 JPEG / WebP；PNG 无损、支持透明通道，
     *   而人偶形象图经常带透明边（PS 导出切图就是这样）。统一转一次，后续读取只有一条路径。
     */
    fun copyImage(ctx: Context, src: File, dstDir: File, index: Int): String? {
        return try {
            val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return null
            writeFrame(ctx, bmp, dstDir, index)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 【位图 → 缩放 → PNG 落盘（统一入口，避免各处各写一遍）。
     *
     * 【r23·修位图泄漏】原写法有两个洞：
     *   ① `compress` 抛异常（磁盘满 / OOM）时，`scaled` 与被缩放的源图都不会回收；
     *   ② 源图**不需要缩放**时 `scaled === bmp`，那句 `if (scaled !== bmp) bmp.recycle()`
     *      直接跳过 —— 于是「导入一张 ≤1024 的小图」每次都留下整张解码位图不释放。
     *      批量导 120 帧小图 = 一百多张位图堆积，正是导入大动作包时最常见的 OOM 来源。
     *
     * 【ownership】默认由本方法接管源图所有权（几乎所有调用方都是「解出来就只为落盘」）；
     *   唯一例外是精灵图切帧 —— 那里的 `cut` 可能**就是**那张大图本身（整图即一帧），
     *   由调用方传 `owned = false` 保住它，不能在这里回收。
     */
    fun writeFrame(ctx: Context, bmp: Bitmap, dstDir: File, index: Int, owned: Boolean = true): String? {
        var scaled: Bitmap? = null
        return try {
            val s = scaleDown(bmp)
            scaled = s
            if (!dstDir.isDirectory) {
                dstDir.mkdirs()
            }
            val out = File(dstDir, String.format("i%04d.png", index))
            FileOutputStream(out).use { os ->
                s.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            if (out.length() > 0) out.absolutePath else null
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "写帧失败", t)
            null
        } finally {
            // 先回收缩放产物（与源不同才单独回收，避免重复 recycle）。
            val s = scaled
            if (s != null && s !== bmp && !s.isRecycled) {
                s.recycle()
            }
            // 再按所有权回收源图。`s === bmp` 时上面已跳过，这里回收一次，不会重复。
            if (owned && !bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val max = Math.max(src.width, src.height)
        if (max <= MAX_EDGE) {
            return src
        }
        val ratio = MAX_EDGE.toFloat() / max
        val nw = Math.max(1, Math.round(src.width * ratio))
        val nh = Math.max(1, Math.round(src.height * ratio))
        val out = Bitmap.createScaledBitmap(src, nw, nh, true)
        return if (out === src) src else out
    }

    private fun deleteTree(d: File) {
        val kids = d.listFiles()
        if (kids != null) {
            for (k in kids) {
                if (k.isDirectory) {
                    deleteTree(k)
                } else {
                    k.delete()
                }
            }
        }
        d.delete()
    }

    private fun readHead(f: File, n: Int): ByteArray {
        return try {
            val buf = ByteArray(n)
            f.inputStream().use { ins ->
                val read = ins.read(buf)
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            }
        } catch (t: Throwable) {
            ByteArray(0)
        }
    }

    private fun readText(f: File, max: Int): String? {
        return try {
            if (f.length() > max) {
                val buf = ByteArray(max)
                f.inputStream().use { ins ->
                    val read = ins.read(buf)
                    if (read <= 0) null else String(buf, 0, read, Charsets.UTF_8)
                }
            } else {
                String(f.readBytes(), Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            null
        }
    }
}
