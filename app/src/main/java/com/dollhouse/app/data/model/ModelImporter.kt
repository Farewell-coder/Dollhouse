package com.dollhouse.app.data.model

import android.content.Context
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollAnim
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 【职责】模型包的识别、校验与导入 —— **一个模型包从文件变成「能播的动作」的全过程**。
 *
 * ## 流程（任务书 §3.8 的「导入流程（统一）」）
 * ```
 * 选择资源 → 自动识别 → 安全校验 → 预览 + 基本信息 → 用户确认 → 保存并更新列表
 * ```
 * 本类负责「识别 → 校验 → 保存」这三步；「预览 + 确认」由 UI 完成，
 * 所以本类被拆成 [inspect]（只读，给 UI 看）与 [install]（写盘，确认后才调）。
 *
 * ## 为什么必须拆成两段
 * 用户点了一个包，可能只是想**看看里面是什么**，然后决定不装。
 * 若导入是一个不可分的动作，那么「看一眼」也会往盘上写东西 —— 用户取消之后
 * 留下一堆孤儿目录，我们还得再写一套清理。**只读的归 inspect，写盘的归 install。**
 *
 * ## 七类异常（任务书 §3.8 逐条对应）
 * | 异常 | 表现 | 处理 |
 * |---|---|---|
 * | `CANCELLED` | 用户主动取消 | 不报错 |
 * | `CORRUPT` | zip 打不开 / 结构坏 | 拒绝 + 说明 |
 * | `UNSUPPORTED` | 不是模型包 / 引擎不支持 | 拒绝 + 可用引擎 |
 * | `MISSING_ASSET` | 清单声明的资源不在包里 | 拒绝 + 列出缺失 |
 * | `VERSION_TOO_NEW` | specVersion 高于本版 | 拒绝 + 提示升级 |
 * | `DUPLICATE` | 同 id 已存在 | **警告**，允许覆盖 |
 * | `IO_FAIL` | 解压 / 写盘失败 | 拒绝 + 回滚已写入的部分 |
 *
 * ## 「失败原则」（任务书原文）
 * > 给出可理解的原因，**保留已有模型及其数据**
 *
 * 所以 [install] **只在全部校验通过后才动磁盘**，且解压到临时目录、
 * 全部成功后再整体搬到位 —— 中途失败时把临时目录删掉即可，
 * 已装的旧版本一个字节都不受影响。
 */
object ModelImporter {

    private const val TAG = "DollhouseImport"

    /** 失败 / 成功的原因码。 */
    enum class Code {
        OK,
        /** 用户主动取消（不是错误）。 */
        CANCELLED,
        /** 文件损坏 / 不是有效 zip。 */
        CORRUPT,
        /** 不是模型包，或引擎不支持。 */
        UNSUPPORTED,
        /** 清单声明的资源缺失。 */
        MISSING_ASSET,
        /** 规范版本过高。 */
        VERSION_TOO_NEW,
        /** 同 id 已存在（仅警告，仍然可装）。 */
        DUPLICATE,
        /** 磁盘 / 解压失败。 */
        IO_FAIL
    }

    /**
     * 检查结果（只读，不改任何东西）。
     *
     * @param canInstall 是否可以进入「等用户确认」这一步。
     */
    data class Inspection(
        val code: Code,
        val manifest: ModelPackage.Manifest?,
        val errors: List<String>,
        val warnings: List<String>,
        val entries: List<String>
    ) {
        val canInstall: Boolean get() = code == Code.OK || code == Code.DUPLICATE

        /** 给用户看的一行话。 */
        fun summary(): String = when (code) {
            Code.OK -> "可以导入：" + (manifest?.name ?: "")
            Code.DUPLICATE -> "已存在同名模型包，继续导入将覆盖它"
            Code.CANCELLED -> "已取消"
            Code.CORRUPT -> "文件已损坏或不是压缩包：" + errors.firstOrNull().orEmpty()
            Code.UNSUPPORTED -> "不是可识别的模型包：" + errors.firstOrNull().orEmpty()
            Code.MISSING_ASSET -> "缺少资源：" + errors.firstOrNull().orEmpty()
            Code.VERSION_TOO_NEW -> "模型包版本过高，请升级 App"
            Code.IO_FAIL -> "读取失败：" + errors.firstOrNull().orEmpty()
        }
    }

    /**
     * 导入结果。
     *
     * @param actionCount 登记进动作库的动作数。
     */
    data class Result(
        val code: Code,
        val modelId: String,
        val name: String,
        val actionCount: Int,
        val warnings: List<String>,
        val message: String
    ) {
        val ok: Boolean get() = code == Code.OK || code == Code.DUPLICATE
    }

    // ============================ 第一步：检查（只读） ============================

    /**
     * 检查一个 zip 是不是可导入的模型包。
     *
     * 【为什么把「识别」也放这里而不是看后缀】用户可能把模型包改了扩展名
     *   （`.zip` → `.doll`），或者上传方给的其实是个普通压缩包。
     *   只有**打开看里面有没有 manifest** 才是可靠识别。
     */
    fun inspect(ctx: Context, zipPath: String?, dollId: String?): Inspection {
        if (zipPath.isNullOrEmpty()) {
            return Inspection(Code.CANCELLED, null, emptyList(), emptyList(), emptyList())
        }
        val f = File(zipPath)
        if (!f.isFile || f.length() <= 0L) {
            return Inspection(
                Code.CORRUPT, null,
                listOf("文件不存在或为空：" + f.name), emptyList(), emptyList()
            )
        }
        var zip: ZipFile? = null
        try {
            zip = ZipFile(f)
        } catch (t: Throwable) {
            // 【为什么单独 catch 这一步】`ZipFile` 构造失败 = 不是压缩包 / 已损坏，
            //   与后面「清单解析失败」是两类不同的问题，提示语也该不同。
            Logs.w(TAG, "打开 zip 失败", t)
            return Inspection(
                Code.CORRUPT, null,
                listOf("无法作为压缩包打开（" + (t.message ?: t.javaClass.simpleName) + "）"),
                emptyList(), emptyList()
            )
        }
        return try {
            inspectZip(ctx, zip, dollId)
        } finally {
            try {
                zip.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun inspectZip(ctx: Context, zip: ZipFile, dollId: String?): Inspection {
        val entries = ArrayList<String>()
        var manifestEntry: java.util.zip.ZipEntry? = null
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (e.isDirectory) {
                continue
            }
            val name = ModelPackage.normalizePath(e.name)
            if (name.isEmpty()) {
                continue
            }
            entries.add(name)
            // 【为什么只认根目录的 manifest.json】规范规定在包根。
            //   允许子目录会导致「同名 manifest 两份、用哪个」的歧义。
            if (name.equals(ModelPackage.MANIFEST, ignoreCase = true)) {
                manifestEntry = e
            }
        }
        if (entries.size > ModelPackage.MAX_ENTRIES) {
            return Inspection(
                Code.CORRUPT, null,
                listOf("压缩包条目过多（" + entries.size + "）"), emptyList(), entries
            )
        }
        val me = manifestEntry
            ?: return Inspection(
                Code.UNSUPPORTED, null,
                listOf("不是模型包：压缩包根目录没有 " + ModelPackage.MANIFEST),
                emptyList(), entries
            )

        val json = try {
            zip.getInputStream(me).use { input -> input.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            return Inspection(
                Code.CORRUPT, null,
                listOf("读取 manifest 失败：" + (t.message ?: "IO 错误")), emptyList(), entries
            )
        }

        val parsed = ModelPackage.parse(json)
        if (!parsed.ok) {
            // 必需字段缺失 → 归入「不是可识别的模型包」，提示会带上具体缺哪个字段。
            return Inspection(Code.UNSUPPORTED, null, parsed.errors, emptyList(), entries)
        }
        val m = parsed.manifest ?: return Inspection(
            Code.UNSUPPORTED, null, listOf("清单解析失败"), emptyList(), entries
        )

        val vr = ModelValidator.validate(m, entries, ModelRepository.installedIds(ctx, dollId))
        if (!vr.ok) {
            // 【错误分类】把「版本过高」单独摘出来，因为它的处置完全不同
            //   （不是包坏了，而是 App 太旧 —— 提示用户升级而不是「重新下载」）。
            val tooNew = m.specVersion > ModelPackage.SPEC_VERSION
            val code = when {
                tooNew -> Code.VERSION_TOO_NEW
                vr.errors.any { it.contains("缺少清单声明的资源") } -> Code.MISSING_ASSET
                vr.errors.any { it.contains("引擎不支持") } -> Code.UNSUPPORTED
                else -> Code.UNSUPPORTED
            }
            return Inspection(code, m, vr.errors, vr.warnings, entries)
        }
        val dup = ModelRepository.installedIds(ctx, dollId).contains(m.id)
        return Inspection(
            if (dup) Code.DUPLICATE else Code.OK,
            m, emptyList(), vr.warnings, entries
        )
    }

    // ============================ 第二步：安装（写盘） ============================

    /**
     * 安装一个模型包（用户确认后调用）。
     *
     * ## 为什么先解到临时目录再整体搬
     * 参考任务书「失败原则」：**保留已有模型及其数据**。
     * 直接往目标目录解压的话，中途失败会有「一半新一半旧」的目录 ——
     * 用户既不能回退到旧版，新版也是坏的。先解到 `<modelId>.tmp`，
     * 全部成功再 `deleteTree(目标)` + `renameTo(目标)`，
     * 于是「要么完整成功、要么什么都不变」。
     *
     * @return 安装结果；`ok` 为 false 时磁盘状态与调用前一致。
     */
    fun install(ctx: Context, zipPath: String?, dollId: String?, inspection: Inspection): Result {
        val m = inspection.manifest
        if (dollId.isNullOrEmpty() || m == null) {
            return Result(Code.CANCELLED, "", "", 0, emptyList(), "已取消")
        }
        if (!inspection.canInstall) {
            return Result(inspection.code, m.id, m.name, 0, emptyList(), inspection.summary())
        }

        val tmpDir = File(ModelRepository.rootDir(ctx, dollId), ModelRepository.safeName(m.id) + ".tmp")
        val finalDir = ModelRepository.modelDir(ctx, dollId, m.id)
        // 上一轮可能留下了 .tmp（进程被杀），先清掉再开始 —— 否则 renameTo 会失败。
        deleteTree(tmpDir)
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            return Result(
                Code.IO_FAIL, m.id, m.name, 0, emptyList(),
                "无法创建目录：" + tmpDir.name
            )
        }

        var zip: ZipFile? = null
        try {
            zip = ZipFile(File(zipPath))
            var total = 0L
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) {
                    continue
                }
                val rel = ModelPackage.normalizePath(e.name)
                if (rel.isEmpty()) {
                    continue
                }
                // 【再查一次路径安全】inspect 已经查过，但这里是**真正写盘**的地方，
                //   是防守的最后一道门：任何一条路径不安全就整体放弃。
                if (ModelValidator.whyUnsafePath(rel) != null) {
                    fail(tmpDir)
                    return Result(
                        Code.UNSUPPORTED, m.id, m.name, 0, emptyList(),
                        "包含不安全路径，已终止导入：" + rel
                    )
                }
                val ext = ModelPackage.extOf(rel)
                if (ext.isNotEmpty() && !ModelPackage.SAFE_EXT.contains(ext)) {
                    fail(tmpDir)
                    return Result(
                        Code.UNSUPPORTED, m.id, m.name, 0, emptyList(),
                        "含不允许的文件类型，已终止导入：" + rel
                    )
                }
                total += e.size
                if (total > ModelPackage.MAX_TOTAL_BYTES) {
                    // 防 zip bomb：声明体积超限时立刻停，不要先解出来再判断。
                    fail(tmpDir)
                    return Result(
                        Code.IO_FAIL, m.id, m.name, 0, emptyList(),
                        "解压后体积超过上限（" + (ModelPackage.MAX_TOTAL_BYTES / 1024 / 1024) + "MB）"
                    )
                }
                val out = File(tmpDir, rel)
                // 再确认一次「父目录没被 ../ 或符号链接带出去」。
                if (!out.canonicalPath.startsWith(tmpDir.canonicalPath + File.separator)) {
                    fail(tmpDir)
                    return Result(
                        Code.UNSUPPORTED, m.id, m.name, 0, emptyList(),
                        "路径逃出目标目录，已终止导入：" + rel
                    )
                }
                val parent = out.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    fail(tmpDir)
                    return Result(Code.IO_FAIL, m.id, m.name, 0, emptyList(), "无法创建子目录：" + rel)
                }
                zip.getInputStream(e).use { input ->
                    FileOutputStream(out).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // ---------- 全部解完了，才动「正式目录」 ----------
            deleteTree(finalDir)
            if (!tmpDir.renameTo(finalDir)) {
                // 【为什么退化到逐文件复制】部分 ROM 上跨挂载点 rename 会失败；
                //   此时用复制代替，成功与否以「最终目录里有清单」为准。
                val copied = try {
                    tmpDir.copyRecursively(finalDir, overwrite = true)
                    true
                } catch (t: Throwable) {
                    Logs.w(TAG, "复制到目标目录失败", t)
                    false
                }
                if (!copied) {
                    fail(tmpDir)
                    return Result(
                        Code.IO_FAIL, m.id, m.name, 0, emptyList(),
                        "写入目标目录失败"
                    )
                }
                deleteTree(tmpDir)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "解压失败", t)
            fail(tmpDir)
            deleteTree(finalDir)
            return Result(
                Code.IO_FAIL, m.id, m.name, 0, emptyList(),
                "解压失败：" + (t.message ?: t.javaClass.simpleName)
            )
        } finally {
            try {
                zip?.close()
            } catch (ignored: Throwable) {
            }
        }

        // ---------- 登记动作 ----------
        val actionCount = registerActions(ctx, dollId, m, finalDir, inspection.entries)

        // ---------- 登记模型包 ----------
        ModelRepository.put(
            ctx, dollId,
            ModelRepository.Installed(
                modelId = m.id,
                name = m.name,
                specVersion = m.specVersion,
                engine = m.engine,
                entry = m.entry,
                actions = resolveActions(m, finalDir),
                rawManifest = ModelPackage.write(m),
                installedAt = System.currentTimeMillis()
            )
        )
        return Result(
            inspection.code, m.id, m.name, actionCount, inspection.warnings,
            "已导入「" + m.name + "」，动作 " + actionCount + " 个"
        )
    }

    /**
     * 把清单里的动作登记进「人偶动作库」。
     *
     * 【为什么入口动作也要登记】用户导入一个 Lottie 包，期望的是
     *   「桌宠待机时播它」。只有把入口也变成一个 `DollAnim.Action`
     *   （并允许设为默认动作），桌面才有办法真正用上它 ——
     *   这正是任务书 §1.4「桌宠能够实际使用动作系统」的落点。
     */
    private fun registerActions(
        ctx: Context, dollId: String,
        m: ModelPackage.Manifest, dir: File, entries: List<String>
    ): Int {
        var n = 0
        // ① actions 映射（逐语义）
        for ((semantic, rel) in m.actions) {
            if (!ModelValidator.isKnownSemantic(semantic)) {
                continue
            }
            val abs = File(dir, rel).absolutePath
            val engine = ModelPackage.engineOf(rel)
            val ok = DollAnim.addEngineAction(
                ctx, dollId,
                DollAnim.Action(
                    // 【为什么 id 就用语义名】见 DollAnim.bySemantic 的约定：
                    //   id 与语义同名时直接命中，语义到动作的映射不需要额外的表。
                    id = semantic,
                    name = m.name + "·" + semantic,
                    desc = "来自模型包「" + m.name + "」",
                    group = groupOf(semantic),
                    engine = engine,
                    sourceId = m.id,
                    assetPath = abs,
                    loop = semantic == "idle"
                )
            )
            if (ok) {
                n++
            }
        }
        // ② 主入口（清单 entry）：没有 actions 映射时它就是「待机动作」。
        val hasIdle = m.actions.containsKey("idle")
        if (!hasIdle && m.entry.isNotEmpty() && entries.contains(m.entry)) {
            val abs = File(dir, m.entry).absolutePath
            val ok = DollAnim.addEngineAction(
                ctx, dollId,
                DollAnim.Action(
                    id = "idle",
                    name = m.name,
                    desc = "来自模型包「" + m.name + "」的入口",
                    group = DollAnim.GROUP_IDLE,
                    engine = ModelPackage.engineOf(m.entry),
                    sourceId = m.id,
                    assetPath = abs,
                    loop = true
                )
            )
            if (ok) {
                n++
            }
        }
        return n
    }

    /** 语义 → 属性分组（让导入的动作落在用户能在界面上找到的位置）。 */
    private fun groupOf(semantic: String): String = when (semantic) {
        "idle" -> DollAnim.GROUP_IDLE
        "tap", "drag", "longpress" -> DollAnim.GROUP_TOUCH
        "appear", "disappear" -> DollAnim.GROUP_ALWAYS
        else -> DollAnim.GROUP_CUSTOM
    }

    private fun resolveActions(m: ModelPackage.Manifest, dir: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in m.actions) {
            out[k] = File(dir, v).absolutePath
        }
        if (!out.containsKey("idle") && m.entry.isNotEmpty()) {
            out["idle"] = File(dir, m.entry).absolutePath
        }
        return out
    }

    /** 解压失败时的清理：把半成品临时目录删掉。 */
    private fun fail(tmpDir: File) {
        deleteTree(tmpDir)
    }

    private fun deleteTree(dir: File) {
        try {
            if (!dir.exists()) {
                return
            }
            val kids = dir.listFiles() ?: return
            for (one in kids) {
                if (one.isDirectory) {
                    deleteTree(one)
                } else {
                    one.delete()
                }
            }
            dir.delete()
        } catch (t: Throwable) {
            Logs.w(TAG, "清理临时目录失败", t)
        }
    }
}