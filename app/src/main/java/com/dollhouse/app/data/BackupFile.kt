package com.dollhouse.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.dollhouse.app.core.Logs
import java.io.File

/**
 * 【职责】备份文件的落盘与分享：把 [BackupEnvelope] 组装好的 JSON 写成文件，
 *        再通过系统分享面板交给用户（可存到网盘 / 发给自己 / 传到新手机）。
 *
 * 【为什么不弹「保存到指定位置」】本工程没有 `ACTION_CREATE_DOCUMENT` 的既有链路，
 *   为一次备份新造一套文件选择器与权限处理，收益远小于风险（见 AboutPage 对
 *   「应用内下载」的同类取舍）。系统分享面板本身就能存到文件管理器、网盘、蓝牙。
 *
 * 【落在哪个目录】`cache/crash-export/` —— 这是 FileProvider 目前唯一对外暴露的路径
 *   （见 `res/xml/crash_log_paths.xml`）。复用它可以零改动 Manifest 就完成分享；
 *   代价是文件名要带前缀避免与崩溃日志混淆。
 *
 * 【隐私】备份里没有可用的 API Key（密文与本机 Keystore 绑定），但**聊天与记忆是明文**。
 *   分享前必须提示用户「这份文件包含你的对话与记忆」。
 */
object BackupFile {

    private const val TAG = "DollhouseBackup"
    private const val DIR = "crash-export"

    /** 备份文件所在目录（不存在则建）。 */
    @JvmStatic
    fun dir(ctx: Context): File {
        val d = File(ctx.cacheDir, DIR)
        if (!d.exists()) {
            d.mkdirs()
        }
        return d
    }

    /**
     * 写出备份文件并返回它。
     *
     * @return 文件；失败返回 null（调用方提示用户，不静默）
     */
    @JvmStatic
    fun write(ctx: Context, json: String, stamp: Long = System.currentTimeMillis()): File? {
        return try {
            val f = File(dir(ctx), "dollhouse-backup-$stamp" + BackupEnvelope.EXTENSION)
            f.writeText(json, Charsets.UTF_8)
            f
        } catch (t: Throwable) {
            Logs.w(TAG, "写备份文件失败", t)
            null
        }
    }

    /**
     * 把备份文件交给系统分享面板。
     *
     * 【必须 FLAG_GRANT_READ_URI_PERMISSION】接收方（网盘 / 文件管理器）通过
     *   `content://` 读文件，没有这个 flag 会直接拿不到内容。
     */
    @JvmStatic
    fun share(ctx: Context, file: File?): Boolean {
        if (file == null || !file.isFile) {
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(Intent.createChooser(i, "保存或发送这份备份").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            Logs.w(TAG, "分享备份失败", t)
            false
        }
    }

    /** 清理旧备份文件，避免 cache 里无限堆积。保留指定数量。 */
    @JvmStatic
    fun prune(ctx: Context, keep: Int = 3) {
        try {
            val all = dir(ctx).listFiles { f -> f.name.endsWith(BackupEnvelope.EXTENSION) } ?: return
            all.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
        } catch (t: Throwable) {
            Logs.w(TAG, "清理旧备份失败", t)
        }
    }
}