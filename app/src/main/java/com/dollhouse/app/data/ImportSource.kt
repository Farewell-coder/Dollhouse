package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.core.Logs
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * 【职责】把「用户选中一堆文件」展开成一份**安全的相对路径清单**，供 [ImportPlan] 识别。
 *
 * ## 为什么单独一层（v2.1·21.3）
 *
 * ZIP 是最容易被搞坏的一条输入：
 * - **Zip Slip**：条目名写 `../../databases/app_database`，直接拼目标路径就会写出暂存目录；
 * - **压缩炸弹**：声明 512 个条目实际解出几个 GB；
 * - **嵌套包**：ZIP 里再套 ZIP，无限递归解压；
 * - **符号链接**：条目带 symlink 属性，写到应用私有目录里指来指去。
 *
 * 需求 21.3 对这四条都有硬指标（≤64 MiB、≤512 条目、拒绝越界路径、不跟随符号链接）。
 * 这些限制必须**在解压过程中逐步统计**，不能只信 ZIP 头部声明值 ——
 * 头部是可以伪造的，只有实际读出来的字节数才算数。
 */
object ImportSource {

    private const val TAG = "DollhouseImportSrc"

    /** 单次导入解压后总字节上限（21.3.1）。 */
    const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L

    /** 单次导入条目上限（21.3.1，目录项也计数）。 */
    const val MAX_ENTRIES = 512

    /** 展开结果的一项。 */
    data class Item(
        /** 相对路径（用来做识别，`小肥鱼/idle/001.png`）。 */
        val rel: String,
        /** 实际可读的绝对路径。 */
        val abs: String
    )

    /** 展开结果。 */
    data class Expanded(
        val items: List<Item>,
        /** 失败原因（面向用户，已按 21.3.9 分类）。 */
        val error: Failure?
    ) {
        val ok: Boolean get() = error == null && items.isNotEmpty()
    }

    /** 展开失败的分类（21.3.9 要求区分，不能都显示成同一条泛化错误）。 */
    enum class Failure {
        /** 超出条目数 / 解压后大小限制。 */
        OVER_LIMIT,

        /** 压缩包损坏或格式不合法。 */
        CORRUPT,

        /** 条目路径非法（绝对路径 / 越界 / 非法分隔符）。 */
        ILLEGAL_PATH,

        /** 发现嵌套压缩包（首版不递归）。 */
        NESTED_ARCHIVE,

        /** 里面没有任何可用素材。 */
        NO_MATERIAL,

        /** 磁盘空间不足。 */
        NO_SPACE
    }

    /** 失败分类 → 面向用户的一句话（21.3.9 + 23.3：说现象、说能做什么）。 */
    fun textOf(f: Failure): String = when (f) {
        Failure.OVER_LIMIT -> "这个压缩包里的内容太多了，超出了单次导入的限制。请分批导入。"
        Failure.CORRUPT -> "压缩包读不出来，可能没有下载完整。请重新导出后再试。"
        Failure.ILLEGAL_PATH -> "压缩包里的文件路径不合法，为了安全已经停止导入。"
        Failure.NESTED_ARCHIVE -> "压缩包里还有压缩包，暂时不支持这样导入。请先解开一层。"
        Failure.NO_MATERIAL -> "这个文件暂时不能直接导入。里面没有找到能用的角色图。"
        Failure.NO_SPACE -> "手机空间不够，导入没有完成。原有角色没有变化。"
    }

    /**
     * 展开。
     *
     * @param picked 选文件通道给的稳定副本路径（都已是本应用私有目录下的文件）
     * @param workDir 展开 ZIP 用的临时目录（会话目录内）
     */
    fun expand(ctx: Context, picked: List<String>, workDir: File): Expanded {
        val items = ArrayList<Item>()
        var budget = MAX_TOTAL_BYTES
        var entries = 0
        for (p in picked) {
            val f = File(p)
            if (!f.isFile) {
                continue
            }
            val head = readHead(f, 4)
            val isZip = head.size == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
            if (!isZip) {
                // 普通文件：相对路径就用自己的文件名。
                items.add(Item(f.name, f.absolutePath))
                continue
            }
            // 文件夹名当相对前缀，尽量保住用户在压缩包里给的语义目录名。
            val prefix = ImportPlan.stemOf(f.name)
            val r = expandZip(f, workDir, prefix, budget, entries)
            if (r.error != null) {
                return Expanded(emptyList(), r.error)
            }
            budget -= r.usedBytes
            entries += r.usedEntries
            items.addAll(r.items)
        }
        if (items.isEmpty()) {
            return Expanded(emptyList(), Failure.NO_MATERIAL)
        }
        return Expanded(items, null)
    }

    private class ZipResult(
        val items: List<Item>,
        val usedBytes: Long,
        val usedEntries: Int,
        val error: Failure?
    )

    private fun expandZip(
        zip: File,
        workDir: File,
        prefix: String,
        budgetBytes: Long,
        budgetEntries: Int
    ): ZipResult {
        val out = ArrayList<Item>()
        var used = 0L
        var count = 0
        var zf: ZipFile? = null
        try {
            zf = ZipFile(zip)
            val es = zf.entries()
            while (es.hasMoreElements()) {
                val e = es.nextElement()
                // ① 条目计数：目录项也算（21.3.1 明确要求）。
                count++
                if (count + budgetEntries > MAX_ENTRIES) {
                    return ZipResult(emptyList(), used, count, Failure.OVER_LIMIT)
                }
                val rawName = e.name ?: continue
                if (e.isDirectory) {
                    continue
                }
                // ② 路径规范化：拒绝绝对路径、越界、反斜杠、控制字符（21.3.2 / 21.3.7）。
                val rel = safeRelName(rawName)
                if (rel == null) {
                    return ZipResult(emptyList(), used, count, Failure.ILLEGAL_PATH)
                }
                // ③ 嵌套压缩包：按普通未识别文件处理会导致后面「认不出」，这里直接明确报错（21.3.4）。
                if (rel.lowercase().endsWith(".zip")) {
                    return ZipResult(emptyList(), used, count, Failure.NESTED_ARCHIVE)
                }
                val dstRel = if (prefix.isBlank()) rel else prefix + "/" + rel
                val dst = File(workDir, dstRel.replace('/', '_'))
                try {
                    zf.getInputStream(e).use { ins ->
                        dst.outputStream().use { os ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) {
                                    break
                                }
                                used += n
                                if (used > budgetBytes) {
                                    dst.delete()
                                    return ZipResult(emptyList(), used, count, Failure.OVER_LIMIT)
                                }
                                os.write(buf, 0, n)
                            }
                        }
                    }
                } catch (io: IOException) {
                    dst.delete()
                    // 写不进去多半是空间不足；区分开，别都说成「压缩包坏了」。
                    return ZipResult(emptyList(), used, count, Failure.NO_SPACE)
                }
                out.add(Item(dstRel, dst.absolutePath))
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "unzip failed: " + zip.name, t)
            return ZipResult(emptyList(), used, count, Failure.CORRUPT)
        } finally {
            try {
                zf?.close()
            } catch (ignored: Throwable) {
            }
        }
        if (out.isEmpty()) {
            return ZipResult(emptyList(), used, count, Failure.NO_MATERIAL)
        }
        return ZipResult(out, used, count, null)
    }

    /**
     * 条目名规范化。
     *
     * @return 安全的相对名（`/` 分隔，无 `..`、无前导 `/`）；不合法返回 null。
     */
    fun safeRelName(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) {
            return null
        }
        // 反斜杠统一成 `/`（Windows 打的包也会用 `\`）。
        s = s.replace('\\', '/')
        // 绝对路径：以 `/` 开头、或带盘符（`C:`）一律拒绝。
        if (s.startsWith("/")) {
            return null
        }
        if (s.length > 1 && s[1] == ':') {
            return null
        }
        val parts = ArrayList<String>()
        for (seg in s.split('/')) {
            if (seg.isEmpty() || seg == ".") {
                continue
            }
            if (seg == "..") {
                // 越界：直接判非法（不做「能夹到目录内就放行」的宽松处理）。
                return null
            }
            // 控制字符 / 空字节：文件系统层面就非法。
            for (c in seg) {
                if (c < ' ' || c == '\u0000') {
                    return null
                }
            }
            parts.add(seg)
        }
        if (parts.isEmpty()) {
            return null
        }
        return parts.joinToString("/")
    }

    /** 读文件头部若干字节（读不到就返回空数组，不抛）。 */
    private fun readHead(f: File, n: Int): ByteArray {
        return try {
            f.inputStream().use { ins ->
                val buf = ByteArray(n)
                var read = 0
                while (read < n) {
                    val k = ins.read(buf, read, n - read)
                    if (k < 0) {
                        break
                    }
                    read += k
                }
                if (read == n) buf else buf.copyOf(read)
            }
        } catch (t: Throwable) {
            ByteArray(0)
        }
    }
}