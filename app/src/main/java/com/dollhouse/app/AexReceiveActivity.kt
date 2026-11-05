package com.dollhouse.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.AexDeepLink
import com.dollhouse.app.data.AexImport
import com.dollhouse.app.data.AexInbox
import com.dollhouse.app.data.AexLinkInbox
import java.io.ByteArrayOutputStream

/**
 * 【职责】AEX 1.1 的系统级接收入口：接住 ACTION_SEND / SEND_MULTIPLE / ACTION_VIEW 三种
 *        文件分享与打开方式，以及 `dollhouse://aivora-import` 深链，把内容放进内存信箱后
 *        交给主界面去弹导入确认。
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

    /** 读文件 / 深链 → 塞进内存信箱 → 唤起主界面。任何异常都收敛成一条日志。 */
    private fun handle(from: Intent?) {
        // 【AEX 1.1 深链】dollhouse://aivora-import?d=...&v=1
        //   深链不是文件，不能走 ContentResolver；解析完直接放内存信箱。
        //   错误也放进去，让主界面统一提示（本页 NoDisplay，不能自己弹窗）。
        val data = from?.data
        if (data != null && AexDeepLink.SCHEME.equals(data.scheme, ignoreCase = true)) {
            val result = if (AexDeepLink.HOST.equals(data.host, ignoreCase = true)) {
                AexDeepLink.parseLink(data.toString())
            } else {
                AexDeepLink.Result(null, AexDeepLink.Reason.NOT_AIVORA)
            }
            AexInbox.clear()
            AexLinkInbox.put(result)
            openMain()
            return
        }
        AexLinkInbox.clear()
        try {
            val all = extractAll(from)
            if (all.isEmpty()) {
                AexInbox.clear()
                Logs.w(TAG, "分享意图里没有可读的文件")
            } else {
                // 【批量】一次分享多个文件时全部留下，不再只取第一个。
                //   上游 AexImportSheet.dispatchMulti 会把它们合并成一次预览。
                AexInbox.putAll(all.map { it.first }, all.map { it.second })
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
     * 从 Intent 里取出**全部**可读文件（每项 = 字节 + 文件名）。
     *
     * 【为什么改成「全部」】原来只返回第一个读成功的 URI，ACTION_SEND_MULTIPLE
     *   带多个文件时其余全被丢掉。虽然靠 ClipData 逐项调 [readUri] 也能读到，
     *   但函数一 `return` 就只剩一个 —— 用户分享 3 个文件，实际只导入 1 个。
     *
     * 【优先级】ClipData 优先（SEND_MULTIPLE 的主通道），其次 EXTRA_STREAM（多值），
     *   再次 ACTION_VIEW 的 data，最后 EXTRA_TEXT（仅辅助，不取数据）。
     *
     * 【去重】部分发送方会把同一个 URI 既放进 ClipData 又放进 EXTRA_STREAM，
     *   按 uri 字符串去重，避免同一份文件被导入两遍。
     */
    private fun extractAll(from: Intent?): List<Pair<ByteArray, String>> {
        if (from == null) {
            return emptyList()
        }
        val out = ArrayList<Pair<ByteArray, String>>()
        val seen = HashSet<String>()

        fun pull(uri: Uri?) {
            if (uri == null) {
                return
            }
            val key = uri.toString()
            if (!seen.add(key)) {
                return
            }
            val one = readUri(uri)
            if (one != null) {
                out.add(one)
            }
        }

        // ---------- ① ClipData（SEND_MULTIPLE 的主通道） ----------
        val clip = from.clipData
        if (clip != null && clip.itemCount > 0) {
            for (i in 0 until clip.itemCount) {
                pull(clip.getItemAt(i).uri)
            }
        }
        // ---------- ② EXTRA_STREAM（单值 / 多值两种写法都要认） ----------
        @Suppress("DEPRECATION")
        val stream: Uri? = from.getParcelableExtra(Intent.EXTRA_STREAM)
        pull(stream)
        try {
            @Suppress("DEPRECATION")
            val many = from.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (many != null) {
                for (u in many) {
                    pull(u)
                }
            }
        } catch (ignored: Throwable) {
            // 某些发送方把 EXTRA_STREAM 塞成字符串，取 ArrayList 会抛，忽略即可。
        }
        // ---------- ③ ACTION_VIEW 的 data ----------
        if (Intent.ACTION_VIEW == from.action) {
            pull(from.data)
        }
        // ---------- ④ EXTRA_TEXT（仅辅助，不取数据） ----------
        val text = from.getStringExtra(Intent.EXTRA_TEXT)
        if (out.isEmpty() && !text.isNullOrBlank() && text.trim().endsWith(AexImport.EXTENSION)) {
            Logs.i(TAG, "分享方只给了文件名（" + text.trim() + "），没有可读的 URI")
        }
        return out
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