package com.dollhouse.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ImageStore
import java.io.File

/**
 * 【职责】一次性选图 Activity：所有「选一张图」的入口（发图 / 聊天背景）都走这里。
 *
 * 【交互】用静态 Listener 把结果回传给发起方（MainActivity 或 ChatPanel），随后立即 finish。
 *
 * 【坑】listener 是静态字段，属于「本进程内凑合能用」的写法：如果发起方在选图过程中被回收，回调就会落空，所以用完必须置回 null。
 *
 * 【修·历史缺陷】原先本页走 GET_CONTENT 且 type 写成通配的任意文件，必然拉起文件管理器（用户找不到图片）；
 *   且 Manifest 上挂着 noHistory="true"，本页在被选择器覆盖的瞬间就被 finish，
 *   onActivityResult 永远收不到结果 —— 发图失效与聊天背景不生效共用这一个根因。两者均已修掉。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
class PickFileActivity : Activity() {

    interface Listener {
        fun onFailed(str: String?)

        fun onPicked(str: String?, str2: String?)
    }

    /**
     * 只要「原始文件」的回传通道：回传的是本页在 `finish()` 前拷好的**字节副本**绝对路径，
     * `null` 表示取消或失败。
     *
     * 【为什么另开一条】默认 [Listener] 通道会把图交给 `ImageStore` 落成 **JPEG**（有损），
     *   且 `chat_img` 有 MAX_FILES 自动清理 —— 不适合放「人偶形象图」这类用户资产。
     *   需要保真原图的调用方挂本监听，自己决定往哪搬。
     */
    interface RawListener {
        fun onRaw(path: String?)
    }

    /**
     * 【r13】「选任意文件」的回传通道：动作帧导入 / 人偶资源导入用。
     *
     * 【为什么需要它】原来所有选图入口都写死 type 为图片并拉起相册 ——
     *   而用户的实际工作流是「在作图软件里把图层做好、导出成一个**成果文件**再导进来」。
     *   那个文件可能是 JSON（Lottie / AE / Spine / PSD 导出的 json）、
     *   也可能是 GIF / 精灵图 PNG / WebP / zip。拉相册根本看不到它们。
     *
     * 【口径】本通道**不限制 MIME**，拉起的是系统的文件选择器（或文件管理器），
     *   用户选什么就给什么；解析与兼容判断全在调用方（见 AnimImport）。
     *
     * @param paths  已拷进本应用 cacheDir 的字节副本路径（按选择顺序）；
     *               用户取消时传空列表。
     * @param failed true = 至少有一个拷失败，列表里是拷成功的那些。
     */
    fun interface FileListener {
        fun onFiles(paths: List<String>, failed: Boolean)
    }

    /**
     * 【r13】「一次选多张」的回传通道：动作帧序列用。
     *
     * 【为什么顺序不能丢】用户选的这组图是**连贯动作**，勾选顺序就是播放顺序。
     *   这里按系统回传的顺序逐个拷贝、逐个入列表，不做任何排序，
     *   保证「选的时候什么顺序，播的时候就是什么顺序」。
     *
     * @param paths  已拷进本应用 cacheDir 的字节副本路径（按选择顺序）；
     *               用户取消时传空列表（不是 null —— 调用方要能区分「取消」与「出错」）。
     * @param failed true = 至少有一张拷失败，列表里是拷成功的那些。
     */
    fun interface MultiListener {
        fun onMulti(paths: List<String>, failed: Boolean)
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        // 【修·选图回调丢失】原实现在重建时直接 finish()，配合 Manifest 的 noHistory 会让本页
        //   在系统相册覆盖它的瞬间就死掉，结果永远回不来。现在改成「重建不自尽、也不重弹」：
        //   只等系统把选择结果投递进 onActivityResult。
        if (bundle != null) {
            Logs.i(LOG_TAG, "[重建] 跳过重复拉起选择器，等待结果")
            return
        }
        val wantMulti = intent != null && intent.getBooleanExtra(EXTRA_MULTI, false)
        val wantFiles = intent != null && intent.getBooleanExtra(EXTRA_FILES, false)
        // 【r20】「只要相册」的请求走 `startRawImages`，它**不设** EXTRA_FILES ——
//   于是本页走默认的 `pickImageIntent`（相册 / 照片选择器），而结果仍由 raw 监听收回（保原图）。
        try {
            if (wantFiles) {
                startActivityForResult(fileIntent(wantMulti), REQ)
            } else {
                startActivityForResult(pickImageIntent(wantMulti), REQ)
            }
        } catch (anf: ActivityNotFoundException) {
            Logs.w(LOG_TAG, "照片选择器不可用，降级", anf)
            try {
                if (wantFiles) {
                    startActivityForResult(compatFileIntent(wantMulti), REQ)
                } else {
                    startActivityForResult(compatImageIntent(wantMulti), REQ)
                }
            } catch (anf2: ActivityNotFoundException) {
                try {
                    if (wantFiles) {
                        startActivityForResult(legacyFileIntent(wantMulti), REQ)
                    } else {
                        startActivityForResult(legacyImageIntent(), REQ)
                    }
                } catch (th: Throwable) {
                    fail("打不开文件选择器：" + th.message)
                }
            }
        } catch (th: Throwable) {
            fail("打不开文件选择器：" + th.message)
        }
    }

    /**
     * 【r13】任意文件选择器（不限制 MIME）。
     *
     * 【为什么要带 CATEGORY_OPENABLE】不带的话部分 ROM 会给「不能打开」的目录节点，
     *   用户点进去只会得到一个失败结果。OPENABLE 保证返回的是能 openInputStream 的 Uri。
     */
    private fun fileIntent(multi: Boolean): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        if (multi) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return intent
    }

    /** 回退一：GET_CONTENT 版文件选择器（老 ROM 上 ACTION_OPEN_DOCUMENT 可能没人接单）。 */
    private fun compatFileIntent(multi: Boolean): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        if (multi) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return intent
    }

    /** 回退二：最后兜底，同样不限类型。 */
    private fun legacyFileIntent(multi: Boolean): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        if (multi) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return intent
    }

    /** Android 13+ 原生照片选择器；10~12 上由 Google Play 服务回填，无需存储权限。 */
    private fun pickImageIntent(z: Boolean): Intent {
        val intent = Intent("android.provider.action.PICK_IMAGES")
        intent.type = "image/*"
        if (z) {
            // 【r13】多选：选图器认这个 extra，返回时把多张塞进 ClipData。
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return intent
    }

    /** 回退一：只认图片的 GET_CONTENT —— 由相册/图库应用接单，不再进文件管理器。 */
    private fun compatImageIntent(z: Boolean): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        if (z) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return intent
    }

    /** 回退二：传统相册 ACTION_PICK。 */
    private fun legacyImageIntent(): Intent {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        return intent
    }

    private fun fail(str: String?) {
        // 【r13】多选 / 任意文件通道也要收尾，否则发起方永远等不到回调。
        if (multiListener != null || fileListener != null) {
            Logs.w(LOG_TAG, "多选通道失败：" + (str ?: "unknown"))
            finishWithMulti(emptyList(), true)
            return
        }
        val raw2 = rawListener
        if (raw2 != null) {
            rawListener = null
            raw2.onRaw(null)
            finish()
            return
        }
        val listener2 = listener
        if (listener2 != null) {
            listener2.onFailed(str)
        }
        finish()
    }

    /**
     * 把选中项以**原字节**拷进本应用私有 `cacheDir/pick`，返回副本绝对路径；失败返回 null。
     *
     * 【为什么不能在 finish() 之后再用 Uri】`ACTION_GET_CONTENT` / 照片选择器返回的 Uri 只带
     *   临时读授权，授权随发起方（本页）销毁而失效。而默认通道的解码是在**后台线程**里做的、
     *   做完才 finish —— 中间那段时间必须保证 Uri 还有效，所以先拷副本再收尾。
     * 【为什么按字节拷】不解码、不转码、不压缩：人偶形象图 / 动作帧图要保住原图的
     *   透明度与画质（JPEG 转码会毁掉透明通道）。解码与缩放交给调用方。
     *
     * 【r13 修 bug·「动作导入失败」】这里原来把副本统一命名成 `raw_<时间戳>_<随机>.bin`，
     *   两个后果：
     *     ① 扩展名丢失 —— [com.dollhouse.app.data.AnimImport] 靠 endsWith 判类型时全部落空；
     *     ② 同名描述文件找不到 —— 精灵图（`sheet.png` + `sheet.json`）靠「同基名」配对，
     *        改名后永远配不上，于是有描述文件也切不出帧。
     *   修法：**保留原扩展名**（`.bin` 只在拿不到扩展名时兜底）。文件名前缀仍带时间戳与随机串，
     *   不会互相覆盖；调用方（AnimImport）已改为魔数优先，两条路都通。
     *
     * @param clean true = 拷前先清空目录（单选通道的既有行为）；
     *              多选时必须传 false，由调用方在循环**外**调 [clearPickCache] 一次。
     */
    private fun copyToCache(uri: Uri, clean: Boolean): String? {
        return try {
            val dir = pickDir()
            if (!dir.isDirectory) {
                return null
            }
            if (clean) {
                clearPickCache()
            }
            val ext = displayExt(uri)
            val name = "raw_" + System.currentTimeMillis() + "_" +
                    java.util.UUID.randomUUID().toString().substring(0, 6) + ext
            val out = File(dir, name)
            val ins = contentResolver.openInputStream(uri) ?: return null
            ins.use { input ->
                out.outputStream().use { os -> input.copyTo(os) }
            }
            out.absolutePath
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "拷贝选中文件失败", t)
            null
        }
    }

    /**
     * 取选中文件的原扩展名（含点）；拿不到就返回 `.bin` 兜底。
     *
     * 【两条来源】优先用 ContentResolver 查到的显示名（最准），
     *   查不到再从 Uri 末段截（部分文件管理器只给后者）。
     */
    private fun displayExt(uri: Uri): String {
        var display: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    display = c.getString(idx)
                }
            }
        } catch (t: Throwable) {
        }
        val fromDisplay = extOf(display)
        if (fromDisplay.isNotEmpty()) {
            return fromDisplay
        }
        return extOf(uri.lastPathSegment)
    }

    /** 从文件名里取 `.xxx`；没有或太长（不像扩展名）就返回空串。 */
    private fun extOf(name: String?): String {
        if (name == null) {
            return ""
        }
        val i = name.lastIndexOf('.')
        if (i <= 0 || i == name.length - 1) {
            return ""
        }
        val e = name.substring(i).lowercase()
        // 扩展名最长 6 字符（.heic / .apng 等），再长就不是扩展名了。
        return if (e.length > 7) "" else e
    }

    /** 本通道的临时目录（cacheDir/pick）。 */
    private fun pickDir(): File {
        val dir = File(cacheDir, "pick")
        if (!dir.isDirectory) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 清空 `cacheDir/pick`。
     *
     * 【为什么需要它】本通道只在「选好图 → 调用方搬走」这一小段里用到副本；
     *   调用方失败 / 进程被杀都会留下残骸。cacheDir 虽可被系统回收，但那是
     *   「空间不足时」才发生，正常用久了仍会堆一堆大图。
     *
     * 【r13 关键坑】原来这个清空写在 [copyToCache] 内部，每拷一张就清一次 ——
     *   多选时第二张会把第一张删掉，结果永远只剩最后一张。
     *   现在把它提出来，多选由调用方在循环外调一次。
     */
    private fun clearPickCache() {
        try {
            val stale = pickDir().listFiles()
            if (stale != null) {
                for (f in stale) {
                    f.delete()
                }
            }
        } catch (t: Throwable) {
        }
    }

    override fun onActivityResult(i: Int, i2: Int, intent: Intent?) {
        super.onActivityResult(i, i2, intent)
        if (i != REQ) {
            return
        }
        if (i2 != RESULT_OK || intent == null) {
            Logs.i(LOG_TAG, "[取消] 没选图，直接收尾")
            // 【清引用】静态监听必须随本次交互一起失效，否则下次别的入口选图时
            //   会先撞上这个已经没人管的旧监听（或让发起方一直被静态字段强引用）。
            rawListener = null
            listener = null
            finishWithMulti(emptyList(), false)
            return
        }
        // 【r13】多选通道优先：一次能返回多张时走它。
        if (multiListener != null || fileListener != null) {
            val uris = collectUris(intent)
            if (uris.isEmpty()) {
                Logs.i(LOG_TAG, "[取消] 多选但没勾中任何文件")
                finishWithMulti(emptyList(), false)
                return
            }
            Logs.i(LOG_TAG, "[回传·多选] count=" + uris.size)
            // 【必须后台拷】一组原图可达数十 MB，主线程 IO 会 ANR；拷完回主线程收尾。
            Thread({
                // 【目录只在开头清一次】见 clearPickCache 的注释：放进 copyToCache 内部的话
                //   每拷一张就会把前面拷好的全删掉，多选永远只剩最后一张。
                clearPickCache()
                val out = ArrayList<String>(uris.size)
                var failed = false
                for (u in uris) {
                    val p = copyToCache(u, false)
                    if (p.isNullOrEmpty()) {
                        failed = true
                    } else {
                        out.add(p)
                    }
                }
                val fo = out
                val ff = failed
                runOnUiThread {
                    finishWithMulti(fo, ff)
                }
            }, "feiyu-pick-multi").start()
            return
        }
        if (intent.data == null) {
            Logs.i(LOG_TAG, "[取消] 没选图，直接收尾")
            rawListener = null
            listener = null
            finish()
            return
        }
        val data: Uri = intent.data!!
        // 【原始文件通道】先拷字节副本再回传：调用方拿到的是自有 cacheDir 里的稳定路径，
        //   不受 Uri 临时授权时效影响，也不经过任何有损转码。
        // 【必须后台拷】相册大图可达数十 MB，主线程 IO 会卡顿乃至 ANR；拷完回主线程收尾。
        val raw2 = rawListener
        if (raw2 != null) {
            rawListener = null
            Logs.i(LOG_TAG, "[回传·原始文件] type=" + (intent.type ?: "null"))
            Thread({
                val copied = copyToCache(data, true)
                runOnUiThread {
                    raw2.onRaw(copied)
                    finish()
                }
            }, "feiyu-pick-raw").start()
            return
        }
        val valueOf: String = intent.type ?: "null"
        val listener2 = listener
        Logs.i(LOG_TAG, "[回传] type=" + valueOf + " purpose=" + purpose)
        Thread({
            var imgPath: String? = null
            var textOut: String? = null
            var errOut: String? = null
            try {
                if (this@PickFileActivity.isImage(data, valueOf)) {
                    imgPath = ImageStore.saveFromUri(this@PickFileActivity, data, purpose)
                } else {
                    textOut = ImageStore.readTextFile(this@PickFileActivity, data, MAX_TEXT_BYTES)
                    if (textOut == null) {
                        errOut = "这个文件既不是图片，也读不出文本内容"
                    }
                }
            } catch (th: Throwable) {
                errOut = th.message ?: th.javaClass.simpleName
            }
            val fImg = imgPath
            val fText = textOut
            val fErr = errOut
            this@PickFileActivity.runOnUiThread {
                if (listener2 != null) {
                    if (fErr != null) {
                        listener2.onFailed(fErr)
                    } else {
                        listener2.onPicked(fImg, fText)
                    }
                }
                this@PickFileActivity.finish()
            }
        }, "feiyu-pick").start()
    }

    /**
     * 【r13】从回传 intent 里取出全部所选 Uri，**保持顺序**。
     *
     * 【两种形态】单选给 `Intent.getData`；多选给 `Intent.getClipData`。
     *   个别 ROM 会两者都给，先收 ClipData 再补 data，并按 Uri 去重 ——
     *   同一张图被拷两遍会导致播放时重复一帧。
     */
    private fun collectUris(intent: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            val clip = intent.clipData
            if (clip != null) {
                for (k in 0 until clip.itemCount) {
                    val u = clip.getItemAt(k).uri
                    if (u != null && !out.contains(u)) {
                        out.add(u)
                    }
                }
            }
        } catch (t: Throwable) {
        }
        try {
            val d = intent.data
            if (d != null && !out.contains(d)) {
                out.add(d)
            }
        } catch (t: Throwable) {
        }
        return out
    }

    /**
     * 多选通道收尾：回传一次，然后清掉三条通道的引用并关页。
     *
     * 【为什么三个引用一起清】它们是静态字段：留着任意一个，都会让上一个发起方
     *   一直被强引用，且下次别的入口选图时可能误投给它。
     */
    private fun finishWithMulti(paths: List<String>, failed: Boolean) {
        val l = multiListener
        val f = fileListener
        multiListener = null
        fileListener = null
        rawListener = null
        listener = null
        if (l != null) {
            l.onMulti(paths, failed)
        }
        if (f != null) {
            f.onFiles(paths, failed)
        }
        finish()
    }

    fun isImage(uri: Uri, str: String?): Boolean {
        if (str != null && str.startsWith("image/")) {
            return true
        }
        try {
            val type = contentResolver.getType(uri)
            if (type != null) {
                if (type.startsWith("image/")) {
                    return true
                }
            }
        } catch (unused: Throwable) {
        }
        return ImageStore.looksLikeImage(this, uri)
    }

    companion object {
        private const val MAX_TEXT_BYTES = 24576
        private const val REQ = 81
        private const val LOG_TAG = "DollhousePick"

        /** 【r13】本次是不是「一次选多张」。 */
        private const val EXTRA_MULTI = "dh_pick_multi"

        /** 【r13】本次是不是「选任意文件」（不限制 MIME，拉起文件选择器而不是相册）。 */
        private const val EXTRA_FILES = "dh_pick_files"
        private var listener: Listener? = null

        /**
         * 「原始文件」通道的挂载点（与 [listener] 二选一，优先响应本通道）。
         * 【用完即清】两个监听都是静态字段，属于「进程内凑合能用」的写法：
         *   发起方若在选图过程中被回收，回调就会落空。故每次回传后立刻置回 null，
         *   避免下一次选图把结果错投给上一个已死掉的调用方。
         */
        private var rawListener: RawListener? = null
        private var multiListener: MultiListener? = null

        /** 【r13】「任意文件」通道的挂载点；与其它通道互斥。 */
        private var fileListener: FileListener? = null
        private var purpose = "chat_img"

        @JvmStatic
        fun setListener(listener2: Listener?) {
            listener = listener2
            if (listener2 != null) {
                rawListener = null
                multiListener = null
                fileListener = null
            }
        }

        /** 挂「原始文件」监听；与其它通道互斥，后挂的生效。 */
        @JvmStatic
        fun setRawListener(listener2: RawListener?) {
            rawListener = listener2
            if (listener2 != null) {
                listener = null
                multiListener = null
                fileListener = null
            }
        }

        /**
         * 【r13】挂「一次选多张」监听；四条通道互斥，后挂的生效。
         *
         * 【用法】先 [setMultiListener]，再 [startMulti]。
         */
        @JvmStatic
        fun setMultiListener(listener2: MultiListener?) {
            multiListener = listener2
            if (listener2 != null) {
                listener = null
                rawListener = null
                fileListener = null
            }
        }

        /**
         * 【r13】挂「任意文件」监听；四条通道互斥，后挂的生效。
         *
         * 【用法】先 [setFileListener]，再 [startFiles]（可传 multi=true 一次选多个）。
         */
        @JvmStatic
        fun setFileListener(listener2: FileListener?) {
            fileListener = listener2
            if (listener2 != null) {
                listener = null
                rawListener = null
                multiListener = null
            }
        }

        /** 拉起「一次选多张」的选图器。 */
        @JvmStatic
        fun startMulti(context: Context) {
            val intent = Intent(context, PickFileActivity::class.java)
            intent.flags = 335544320
            intent.putExtra(EXTRA_MULTI, true)
            context.startActivity(intent)
        }

        /**
         * 【r20】拉起**相册**，把选中图的原始字节交回 [RawListener]。
         *
         * 【为什么不是 startFiles】`startFiles` 拉的是系统文件选择器（不限 MIME），
         *   用户想换头像得在文件树里翻 —— 那不是找头像该走的路。
         *   本方法不设 [EXTRA_FILES]，于是本页按默认分支拉起相册 / 照片选择器；
         *   而结果仍由 [RawListener] 接收（保原图，不经过有损 JPEG 转码）。
         *
         * 【用法】先 [setRawListener]，再调本方法。
         */
        @JvmStatic
        fun startRawImages(context: Context) {
            val intent = Intent(context, PickFileActivity::class.java)
            intent.flags = 335544320
            context.startActivity(intent)
        }

        /**
         * 【r13】拉起「选任意文件」的选择器。
         *
         * 【为什么另开一条】原通道写死图片类型，用户导出的 json / gif / zip 在相册里根本看不到。
         *
         * @param multi true = 允许一次选多个文件（帧序列常见：一整套图一次选完）。
         */
        @JvmStatic
        fun startFiles(context: Context, multi: Boolean) {
            val intent = Intent(context, PickFileActivity::class.java)
            intent.flags = 335544320
            intent.putExtra(EXTRA_FILES, true)
            if (multi) {
                intent.putExtra(EXTRA_MULTI, true)
            }
            context.startActivity(intent)
        }

        @JvmStatic
        fun setPurpose(str: String?) {
            purpose = str ?: ImageStore.DIR
        }

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, PickFileActivity::class.java)
            intent.flags = 335544320
            context.startActivity(intent)
        }
    }
}
