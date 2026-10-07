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

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        // 【修·选图回调丢失】原实现在重建时直接 finish()，配合 Manifest 的 noHistory 会让本页
        //   在系统相册覆盖它的瞬间就死掉，结果永远回不来。现在改成「重建不自尽、也不重弹」：
        //   只等系统把选择结果投递进 onActivityResult。
        if (bundle != null) {
            Logs.i(LOG_TAG, "[重建] 跳过重复拉起选择器，等待结果")
            return
        }
        try {
            startActivityForResult(pickImageIntent(), REQ)
        } catch (anf: ActivityNotFoundException) {
            Logs.w(LOG_TAG, "照片选择器不可用，降级", anf)
            try {
                startActivityForResult(compatImageIntent(), REQ)
            } catch (anf2: ActivityNotFoundException) {
                try {
                    startActivityForResult(legacyImageIntent(), REQ)
                } catch (th: Throwable) {
                    fail("打不开相册：" + th.message)
                }
            }
        } catch (th: Throwable) {
            fail("打不开相册：" + th.message)
        }
    }

    /** Android 13+ 原生照片选择器；10~12 上由 Google Play 服务回填，无需存储权限。 */
    private fun pickImageIntent(): Intent {
        val intent = Intent("android.provider.action.PICK_IMAGES")
        intent.type = "image/*"
        return intent
    }

    /** 回退一：只认图片的 GET_CONTENT —— 由相册/图库应用接单，不再进文件管理器。 */
    private fun compatImageIntent(): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        return intent
    }

    /** 回退二：传统相册 ACTION_PICK。 */
    private fun legacyImageIntent(): Intent {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        return intent
    }

    private fun fail(str: String?) {
        val listener2 = listener
        if (listener2 != null) {
            listener2.onFailed(str)
        }
        finish()
    }

    override fun onActivityResult(i: Int, i2: Int, intent: Intent?) {
        super.onActivityResult(i, i2, intent)
        if (i != REQ) {
            return
        }
        if (i2 != RESULT_OK || intent == null || intent.data == null) {
            Logs.i(LOG_TAG, "[取消] 没选图，直接收尾")
            finish()
            return
        }
        val data: Uri = intent.data!!
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
        private var listener: Listener? = null
        private var purpose = "chat_img"

        @JvmStatic
        fun setListener(listener2: Listener?) {
            listener = listener2
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
