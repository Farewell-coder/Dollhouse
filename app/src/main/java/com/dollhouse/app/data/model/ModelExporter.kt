package com.dollhouse.app.data.model

import android.content.Context
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollStore
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 【职责】把**单个人偶（含其动作与素材）**导出成模型包 —— 导入的逆运算。
 *
 * ## 粒度（任务书 §3.7「导出」原文）
 * > 粒度：**单个人偶 + 其关联动作**，不默认打包全库
 *
 * 这个粒度是有道理的：用户分享的是「我做的这个角色」，
 * 而不是「我手机上所有角色 + 我的聊天记录」。全库导出既巨大又含隐私。
 *
 * ## 路径还原
 * > 帧图与资源一并打包，用包内相对路径；导入时安全恢复路径，
 *    不依赖原设备私有目录
 *
 * 本类的做法：把 `filesDir/...` 下的资源**原样塞进 zip 的 `res/` 下**，
 * 清单里只写 `res/xxx` 这样的相对路径。于是：
 * · 换台设备解压后落在它自己的私有目录，路径自然正确；
 * · 包可以进 git 仓库、可以发给别人，不泄漏任何本机路径。
 *
 * ## 为什么导出要做「动作 → 语义映射」
 * 导入时 `actions` 映射是「语义 → 资源」。导出时如果不写这份映射，
 * 别人导入后拿到一堆叫「伸展」「张望」的动作，**桌宠待机时不会播任何一个**
 * （它们都不是 idle 语义）。所以导出时把「当前设了默认动作的那个」标成 `idle`，
 * 让分享出去的人偶在对方设备上**开箱即可用**。
 */
object ModelExporter {

    private const val TAG = "DollhouseExport"

    /** 导出结果。 */
    data class Result(
        val ok: Boolean,
        val zipPath: String,
        val fileCount: Int,
        val message: String
    )

    /**
     * 导出一个人偶。
     *
     * @param dollId    要导出的人偶 id。
     * @param outDir    输出目录（zip 落在 `<outDir>/<safeName>-<ts>.zip`）。
     * @param modelId   manifest 的 `id`；留空则用 `"<dollId>-export"`。
     * @param dollName  显示名（一般传人偶名）。
     */
    fun exportDoll(
        ctx: Context,
        dollId: String?,
        dollName: String,
        outDir: File,
        modelId: String = ""
    ): Result {
        if (dollId.isNullOrEmpty()) {
            return Result(false, "", 0, "没有人偶可导出")
        }
        val actions = DollAnim.all(ctx, dollId)
        if (actions.isEmpty()) {
            return Result(false, "", 0, "这只人偶还没有任何动作，导出没有意义")
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            return Result(false, "", 0, "无法创建输出目录：" + outDir.absolutePath)
        }
        val id = modelId.ifBlank { ModelRepository.safeName(dollId) + "-export" }
        val safe = ModelRepository.safeName(id)
        val zipFile = File(outDir, safe + "-" + System.currentTimeMillis() + ".zip")

        // ---------- 收集要打包的文件 ----------
        val entries = LinkedHashMap<String, File>()   // zip 内相对路径 -> 源文件
        val actionMap = LinkedHashMap<String, String>() // 语义 -> zip 内相对路径
        val assets = ArrayList<String>()

        var idx = 0
        for (a in actions) {
            // 帧序列动作：一帧一个文件。
            if (a.frames.isNotEmpty()) {
                val first = "res/" + safe + "/" + a.id + "/f0000" + extOf(a.frames[0])
                var n = 0
                for (f in a.frames) {
                    val src = File(f)
                    if (!src.isFile) {
                        continue
                    }
                    val rel = "res/" + safe + "/" + a.id + "/f" + pad4(n) + extOf(f)
                    entries[rel] = src
                    n++
                }
                if (n > 0) {
                    actionMap[semanticFor(a, idx)] = first
                    assets.add(first.substringBeforeLast('/') + "/")
                    idx++
                }
                continue
            }
            // 非帧序列引擎：入口资源一个文件（Lottie 的 json、动图的 gif）。
            //
            // 【为什么把同目录 images/ 一起带上】Lottie 常见形态是「json + images/ 分离图」。
            //   只带 json 会导致对方导入后渲染成空白（图找不到）——
            //   而导出方这边看起来一切正常，问题只在别人那里暴露，最难查。
            if (a.assetPath.isNotEmpty()) {
                val src = File(a.assetPath)
                if (!src.isFile) {
                    continue
                }
                val rel = "res/" + safe + "/" + a.id + "/" + src.name
                entries[rel] = src
                val peer = src.parentFile
                if (peer != null) {
                    for (one in peer.listFiles().orEmpty()) {
                        if (!one.isFile || one.name == src.name) {
                            continue
                        }
                        val e2 = extOf(one.name)
                        if (!ModelPackage.SAFE_EXT.contains(e2)) {
                            continue
                        }
                        entries[rel.substringBeforeLast('/') + "/" + one.name] = one
                    }
                    // 分离式图集目录。
                    val imgDir = File(peer, "images")
                    if (imgDir.isDirectory) {
                        for (one in imgDir.listFiles().orEmpty()) {
                            if (!one.isFile) {
                                continue
                            }
                            val e2 = extOf(one.name)
                            if (!ModelPackage.SAFE_EXT.contains(e2)) {
                                continue
                            }
                            entries[rel.substringBeforeLast('/') + "/images/" + one.name] = one
                        }
                    }
                }
                actionMap[semanticFor(a, idx)] = rel
                assets.add(rel)
                idx++
            }
        }

        if (entries.isEmpty()) {
            return Result(false, "", 0, "动作里没有任何可打包的素材文件")
        }

        // ---------- 写 zip ----------
        val entry = actionMap.entries.firstOrNull()
        val engine = if (entry != null) ModelPackage.engineOf(entry.value) else DollAnim.ENGINE_SPRITE
        val manifest = ModelPackage.Manifest(
            specVersion = ModelPackage.SPEC_VERSION,
            id = id,
            name = dollName.ifBlank { id },
            engine = engine,
            entry = entry?.value ?: entries.keys.first(),
            modelVersion = "1",
            assets = assets,
            actions = actionMap,
            behaviors = mapOf(
                "source" to "dollhouse-export",
                "actionCount" to entries.keys.size.toString()
            ),
            license = "请在此填写素材许可证",
            minFramework = "0.0.7"
        )

        var count = 0
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // 清单先写：解压工具与人都先看到它，便于「打开就懂」。
                putText(zos, ModelPackage.MANIFEST, ModelPackage.write(manifest))
                count++
                for ((rel, src) in entries) {
                    zos.putNextEntry(ZipEntry(rel))
                    src.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "写 zip 失败", t)
            try {
                zipFile.delete()
            } catch (ignored: Throwable) {
            }
            return Result(false, "", 0, "导出失败：" + (t.message ?: t.javaClass.simpleName))
        }

        // ---------- 登记一条导出记录（便于「我导出过什么」可追溯） ----------
        try {
            ModelRepository.put(
                ctx, dollId,
                ModelRepository.Installed(
                    modelId = id,
                    name = dollName.ifBlank { id },
                    specVersion = ModelPackage.SPEC_VERSION,
                    engine = engine,
                    entry = manifest.entry,
                    actions = actionMap,
                    rawManifest = ModelPackage.write(manifest),
                    installedAt = System.currentTimeMillis()
                )
            )
        } catch (t: Throwable) {
            // 登记失败不该让「导出成功」变成失败 —— 文件已经在盘上了。
            Logs.w(TAG, "登记导出记录失败", t)
        }

        return Result(
            true, zipFile.absolutePath, count,
            "已导出「" + dollName + "」：" + count + " 个文件"
        )
    }

    /**
     * 给一个动作挑它该登记的语义名。
     *
     * 【为什么优先用 isDefault 而不是原 id】见类注释：导出是为了别人能用，
     *   而「别人能用」的前提是至少有一个动作叫 `idle`（桌宠待机时会去播它）。
     *   用户当下设的待机默认动作，就是最该被标成 `idle` 的那个。
     */
    private fun semanticFor(a: DollAnim.Action, index: Int): String {
        if (a.isDefault) {
            return "idle"
        }
        // id 本身就是标准语义名（多半来自模型包导入）→ 原样保留。
        if (ModelValidator.isKnownSemantic(a.id)) {
            return a.id
        }
        // 其余：第一个给 idle，后面按顺序给交互语义，超出的归到 tap。
        return when (index) {
            0 -> "idle"
            1 -> "tap"
            2 -> "drag"
            3 -> "longpress"
            4 -> "appear"
            else -> "tap"
        }
    }

    private fun putText(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun pad4(n: Int): String {
        val s = n.toString()
        return when (s.length) {
            1 -> "000$s"
            2 -> "00$s"
            3 -> "0$s"
            else -> s
        }
    }

    /** 取「.xxx」形式的后缀（没有则空串）。 */
    private fun extOf(path: String): String {
        val e = ModelPackage.extOf(path)
        return if (e.isEmpty()) "" else "." + e
    }

    /** 导出目录建议值（`/sdcard/Download/Dollhouse模型/`）。 */
    fun suggestOutDir(): File {
        val a = File("/sdcard/Download/Dollhouse模型")
        if (!a.exists()) {
            a.mkdirs()
        }
        return a
    }

    /** 供 UI 显示「这只人偶能导出多少动作」。 */
    fun countExportable(ctx: Context, dollId: String?): Int {
        if (dollId.isNullOrEmpty()) {
            return 0
        }
        var n = 0
        for (a in DollAnim.all(ctx, dollId)) {
            if (a.frames.isNotEmpty() || a.assetPath.isNotEmpty()) {
                n++
            }
        }
        return n
    }

    /** 是否是可导出的人偶（存在且不是内置的默认人偶）。 */
    fun canExport(dollId: String?): Boolean {
        return !dollId.isNullOrEmpty() && dollId != DollStore.DEFAULT_ID
    }
}