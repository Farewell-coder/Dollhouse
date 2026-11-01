package com.dollhouse.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.AexImport
import com.dollhouse.app.data.AexInbox
import java.io.ByteArrayOutputStream

/**
 * 【职责】AEX 1.0 的系统级文件接收入口：接住 ACTION_SEND / SEND_MULTIPLE / ACTION_VIEW 三种
 *        分享与打开方式，把文件读成字节后交给主界面去弹导入流程。
 *
 * 【为什么需要独立 Activity】规范第三节要求提供系统级入口，并明确：
 *   · 必须用 ContentResolver 读，不得把 URI 直接转成文件系统路径；
 *   · ClipData 与 EXTRA_STREAM 都要认；
 *   · 读取完成前不得提前释放临时 URI 权限。
 *   而导入流程是多级弹窗，Dialog 必须依附一个长期可见的 Activity 窗口 ——
 *   所以本页只做「读完 → 放内存 → 唤主界面 → 自己结束」，不负责展示。
 *
 * 【时序】读取在当前页完成（此时临时授权仍然有效），读取结束后才 finish；
 *   finish 之后由 MainActivity 从 AexInbox 取出字节继续，不再触碰任何 URI。
 *
 * 【坑】① NoDisplay 主题在 Android 14 起要求 onCreate 内 finish；
 *        ② singleTop + excludeFromRecents：连续分享不叠任务栈、不污染最近任务；
 *        ③ 读失败绝不能崩：记日志后照样唤主界面，让用户看到「读取失败」而不是闪退。
 */
class AexReceiveActivity : Activity() {

    companion object {
        private const val TAG = "Dollhouse"
        private const val MAX_BYTES = AexImport.MAX_FILE_SIZE

        /** 告诉主界面「这次是被分享唤起的，先去取收件箱」。 */
        const val EXTRA_FROM_SHARE = "com.dollhouse.app.extra.FROM_AEX_SHARE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
        finish()
    }

    /** 读文件 → 塞进内存信箱 → 唤起主界面。任何异常都收敛成一条日志。 */
    private fun handle(from: Intent?) {
        try {
            val payload = extract(from)
            if (payload == null) {
                AexInbox.clear()
                Logs.w(TAG, "分享意图里没有可读的文件")
            } else {
                AexInbox.put(payload.first, payload.second)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "接收 AEX 文件失败", t)
            AexInbox.clear()
        }
        openMain()
    }

    /**
     * 唤起主界面来处理信箱里的文件。
     *
     * MainActivity 是 singleTask：这样拉起会把它整个任务栈调到前台，
     * 正是我们要的效果（用户能看到导入弹窗）。只加 NEW_TASK 即可，不要 CLEAR_TOP ——
     * 那会重建实例、丢掉用户原本停留的页面。
     */
    private fun openMain() {
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.putExtra(EXTRA_FROM_SHARE, true)
            startActivity(i)
        } catch (t: Throwable) {
            Logs.w(TAG, "唤起主界面失败", t)
        }
    }

    /**
     * 从 Intent 里取出「(字节, 文件名)」。
     *
     * 【优先级】ClipData > EXTRA_STREAM > ACTION_VIEW 的 data > EXTRA_TEXT（仅辅助）。
     *   EXTRA_TEXT 按规范只作辅助：有些发送方只把文件名塞进去，把它当文件内容解析
     *   必然得到「结构不完整」，所以这里只用于日志，不用于取数据。
     */
    private fun extract(from: Intent?): Pair<ByteArray, String>? {
        if (from == null) {
            return null
        }
        // ---------- ① ClipData（SEND_MULTIPLE 的主通道） ----------
        val clip = from.clipData
        if (clip != null && clip.itemCount > 0) {
            for (i in 0 until clip.itemCount) {
                val one = readUri(clip.getItemAt(i).uri)
                if (one != null) {
                    return one
                }
            }
        }
        // ---------- ② EXTRA_STREAM ----------
        @Suppress("DEPRECATION")
        val stream: Uri? = from.getParcelableExtra(Intent.EXTRA_STREAM)
        if (stream != null) {
            val one = readUri(stream)
            if (one != null) {
                return one
            }
        }
        // ---------- ③ ACTION_VIEW 的 data ----------
        if (Intent.ACTION_VIEW == from.action) {
            val one = readUri(from.data)
            if (one != null) {
                return one
            }
        }
        // ---------- ④ EXTRA_TEXT（仅辅助，不取数据） ----------
        val text = from.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank() && text.trim().endsWith(AexImport.EXTENSION)) {
            Logs.i(TAG, "分享方只给了文件名（" + text.trim() + "），没有可读的 URI")
        }
        return null
    }

    /**
     * 用 ContentResolver 读取一个 content:// / file:// URI。
     *
     * 【不转文件路径】规范明令禁止把 URI 转成文件系统路径：那样在分区存储、
     *   云端文档提供方（网盘 / Google Drive）下必然拿不到数据。
     */
    private fun readUri(uri: Uri?): Pair<ByteArray, String>? {
        if (uri == null) {
            return null
        }
        return try {
            val name = displayName(uri)
            val ins = contentResolver.openInputStream(uri) ?: return null
            ins.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) {
                        break
                    }
                    total += n
                    // 【上限必须在读的时候就挡】等读完再判断等于先被一个巨大文件拖死。
                    if (total > MAX_BYTES) {
                        Logs.w(TAG, "分享的文件超过 16 MiB，拒绝读取")
                        return null
                    }
                    out.write(buf, 0, n)
                }
                Pair(out.toByteArray(), name)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "读取分享 URI 失败", t)
            null
        }
    }

    /** 取显示名（用于后缀判定与提示）；拿不到就回落到路径末段。 */
    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val v = c.getString(idx)
                    if (!v.isNullOrEmpty()) {
                        return v
                    }
                }
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "查询显示名失败", t)
        }
        return uri.lastPathSegment ?: "shared.akcp"
    }
}