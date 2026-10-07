package com.dollhouse.app.ui.widget

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.core.KeyVault
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.theme.UiKit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 【职责】把全部供应商与模型导出成一个 JSON 文件。
 *
 * 【口径（规格书需求 14 = C）】API Key 默认打码，用户主动勾「包含密钥明文」才写全文；
 *        默认不勾，避免随手导出就把密钥落到文件里。
 *
 * 【落点】Android 10+ 走 MediaStore 写「下载」目录（无需存储权限）；
 *        更低版本退回本 App 的外部私有目录（minSdk 24 兜底，同样无需权限）。
 *
 * 【为什么没有 Toast】UiKit 硬约束：全 App 禁止 Toast / Snackbar / 自实现浮层短提示。
 *        结果一律用自绘弹窗说明（路径 + 条数），保证「点了就有反馈」。
 */
object ExportSheet {

    /** 文件信封标识：将来做导入时靠它认领自家文件。 */
    private const val KIND = "dollhouse.providers"
    private const val VER = 1

    /** 入口：先让用户选择是否包含明文密钥。 */
    @JvmStatic
    fun show(act: Activity?) {
        val activity = act ?: return
        val ctx: Context = activity
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val msg = TextView(ctx)
        msg.text = "导出全部供应商与模型（含禁用项）为 JSON 文件，写入「下载」目录。"
        msg.setTextSize(UiKit.FS_BTN)
        msg.setTextColor(UiKit.SUB)
        msg.setLineSpacing(UiKit.dp(ctx, 3f).toFloat(), 1.0f)
        box.addView(msg)

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, UiKit.dp(ctx, 14f), 0, 0)
        val label = TextView(ctx)
        label.text = "包含密钥明文"
        label.setTextSize(UiKit.FS_BTN)
        label.setTextColor(UiKit.TITLE)
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))
        val withKeys = UiKit.Switch(ctx)
        withKeys.setOn(false)
        withKeys.isClickable = true
        withKeys.setOnClickListener(View.OnClickListener {
            withKeys.setOn(!withKeys.isOn(), true)
        })
        row.addView(withKeys)
        box.addView(row)
        val warn = TextView(ctx)
        warn.text = "默认不导出密钥，导出的 Key 只保留首尾各 4 位。开启后文件里是可用的完整密钥，请自行妥善保管。"
        warn.setTextSize(UiKit.FS_TINY)
        warn.setTextColor(UiKit.ERR)
        warn.setPadding(0, UiKit.dp(ctx, 6f), 0, 0)
        warn.setLineSpacing(UiKit.dp(ctx, 3f).toFloat(), 1.0f)
        box.addView(warn)

        UiKit.showDialog(activity, "导出供应商数据", box, "导出", View.OnClickListener {
            run(activity, withKeys.isOn())
        }, "取消", null)
    }

    /** 后台组包 + 落盘，结果回主线程弹窗。 */
    private fun run(act: Activity, withKeys: Boolean) {
        val ctx = act.applicationContext
        Thread({
            var path: String? = null
            var error: String? = null
            try {
                val json = buildJson(ctx, withKeys)
                path = write(ctx, json, withKeys)
            } catch (t: Throwable) {
                path = null
                error = t.javaClass.simpleName +
                    (if (t.message == null) "" else "：" + t.message)
            }
            val fpath = path
            val ferr = error
            act.runOnUiThread {
                if (act.isFinishing) {
                    return@runOnUiThread
                }
                if (ferr != null) {
                    UiKit.showDialog(act, "导出失败", plain(act, ferr), "好", null, null, null)
                } else {
                    UiKit.showDialog(act, "导出完成", plain(act, fpath), "好", null, null, null)
                }
            }
        }).start()
    }

    /** 组装导出内容：信封 + 全量供应商（可打码）+ 全量模型。 */
    internal fun buildJson(ctx: Context, withKeys: Boolean): String {
        val providers = ProviderStore.providers(ctx)
        val models = ModelStore.models(ctx)
        val root = JSONObject()
        root.put("kind", KIND)
        root.put("ver", VER)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("includePlainKeys", withKeys)
        val pvArr = JSONArray()
        for (p in providers) {
            val o = p.toJson()
            val plain = ProviderStore.keyOf(ctx, p)
            // 打码走 KeyVault.mask：短密钥整段打码，不留半个可用片段。
            o.put("apiKey", if (withKeys) plain else KeyVault.mask(plain))
            pvArr.put(o)
        }
        root.put("providers", pvArr)
        val mArr = JSONArray()
        for (m in models) {
            mArr.put(m.toJson())
        }
        root.put("models", mArr)
        return root.toString(2)
    }

    /** 写入文件，返回可读路径。 */
    private fun write(ctx: Context, json: String, withKeys: Boolean): String {
        val name = "Dollhouse-导出-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date()) + (if (withKeys) "-含密钥" else "") + ".json"
        val data = json.toByteArray(charset("UTF-8"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues()
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name)
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/json")
            cv.put(MediaStore.Downloads.IS_PENDING, 1)
            val item = ctx.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("系统没给可写的位置")
            val os = ctx.contentResolver.openOutputStream(item)
                ?: throw IllegalStateException("打不开导出文件")
            try {
                os.write(data)
            } finally {
                os.close()
            }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(item, cv, null, null)
            return "下载/" + name + "\n共 " + data.size + " 字节"
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val f = File(dir, name)
        val os = FileOutputStream(f)
        try {
            os.write(data)
        } finally {
            os.close()
        }
        return f.absolutePath + "\n共 " + data.size + " 字节"
    }

    /** 弹窗正文：一句纯文字说明。 */
    private fun plain(ctx: Context, text: String?): TextView {
        val t = TextView(ctx)
        t.text = text ?: ""
        t.setTextSize(UiKit.FS_BTN)
        t.setTextColor(UiKit.SUB)
        t.setLineSpacing(UiKit.dp(ctx, 3f).toFloat(), 1.0f)
        t.setTextIsSelectable(true)
        return t
    }
}
