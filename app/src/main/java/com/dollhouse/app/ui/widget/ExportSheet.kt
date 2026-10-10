package com.dollhouse.app.ui.widget

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.core.KeyVault
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.theme.UiKit
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

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
 *
 * 【Compose 迁移口径】弹窗内容整体换成 Compose（父页面 ProviderListPage 已是 Compose）；
 *        「选择 → 结果」两态收在同一个弹窗里切状态，不再关一个再开一个，
 *        避免嵌套 Dialog 的时序问题。对外入口 [show] 签名一字未动。
 */
object ExportSheet {

    /** 文件信封标识：将来做导入时靠它认领自家文件。 */
    private const val KIND = "dollhouse.providers"
    private const val VER = 1

    /** 入口：先让用户选择是否包含明文密钥。 */
    @JvmStatic
    fun show(act: Activity?) {
        val activity = act ?: return
        val ref = AtomicReference<Dialog?>()
        // 句柄回填：content 在 dlg.show() 之后才组合，点击时一定拿得到。
        ref.set(DhForm.show(activity, "导出供应商数据") { ExportBody(activity, ref) })
    }

    /* ----------------------------- Compose 弹窗内容 ----------------------------- */

    @Composable
    private fun ExportBody(act: Activity, ref: AtomicReference<Dialog?>) {
        val c = DhTokens.colors
        var withKeys by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        // null = 还没跑；非 null = 结果文案（failed 决定配色）。
        var result by remember { mutableStateOf<String?>(null) }
        var failed by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxWidth()) {
            val body = result
            if (body == null) {
                Text(
                    text = "导出全部供应商与模型（含禁用项）为 JSON 文件，写入「下载」目录。",
                    color = c.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts,
                    lineHeight = (UiKit.FS_BTN + 5f).sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "包含密钥明文",
                        modifier = Modifier.weight(1f),
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts
                    )
                    DhKit.Switch(checked = withKeys, onCheckedChange = { withKeys = it })
                }
                Text(
                    text = "默认不导出密钥，导出的 Key 只保留首尾各 4 位。开启后文件里是可用的完整密钥，请自行妥善保管。",
                    modifier = Modifier.padding(top = 6.dp),
                    color = c.err,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts,
                    lineHeight = (UiKit.FS_TINY + 5f).sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    DhForm.OutlineChip(text = "取消") { ref.get()?.dismiss() }
                    Spacer(Modifier.width(10.dp))
                    DhForm.PrimaryChip(text = if (busy) "导出中…" else "导出") {
                        if (!busy) {
                            busy = true
                            run(act, withKeys) { path, err ->
                                busy = false
                                failed = err != null
                                result = err ?: (path ?: "")
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = body,
                    color = if (failed) c.err else c.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts,
                    lineHeight = (UiKit.FS_BTN + 5f).sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    DhForm.PrimaryChip(text = "好") { ref.get()?.dismiss() }
                }
            }
        }
    }

    /* ----------------------------- 组包与落盘 ----------------------------- */

    /** 后台组包 + 落盘，结果回主线程回调（[done] 的第一个参数为路径，第二个为错误）。 */
    private fun run(act: Activity, withKeys: Boolean, done: (String?, String?) -> Unit) {
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
                done(fpath, ferr)
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
}
