package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.core.Logs
import java.io.File
import java.util.UUID

/**
 * 【职责】一次导入的**完整会话**：从「用户选了文件」到「提交成功」之间的全部临时状态。
 *
 * ## 为什么必须有会话对象（v2.1·21.1）
 *
 * 需求 21.1 要求「导入会话数据与正式角色数据分离」：用户没点最终确认之前，
 * 只允许写临时目录，**不得**改正式角色目录、`Doll.enabled`、常驻角色 ID 或既有模型文件。
 * 改造前这一点是靠「向导前三步只读」隐式保证的 —— 一旦界面被回收重建，
 * 用户选的文件、识别结果、改过的名字全丢，只能从头再来（需求 21.1.3 明确禁止这种体验）。
 *
 * 所以这里把「选了什么、认出什么、用户改了什么」都收进一个可序列化的小对象，
 * 并由 [root] 下的临时目录承载帧素材；提交成功才搬进正式目录。
 *
 * ## 生命周期
 *
 * ```
 * create()   → 建会话 + 临时目录（此时正式数据零变化）
 *   …用户识别 / 预览 / 调整（全部只写本会话）…
 * commit()   → 原子提交（见 ImportCommit）
 * discard()  → 用户取消 / 离开页面 → 删临时目录
 * ```
 *
 * 【为什么用 UUID 而不是时间戳】同一秒内连点两次「选择文件」会撞名，两个会话共用目录，
 *   后一个把前一个的中间产物覆盖掉 —— 表现是「导入的帧莫名其妙丢了几张」。
 */
class ImportSession(
    /** 会话 ID（UUID），同时是临时目录名。 */
    val id: String,
    /** 入口：新建角色 / 给已有角色补动作。 */
    val entry: Entry,
    /** 目标角色 ID；[Entry.NEW_DOLL] 时为空（提交时才创建）。 */
    val dollId: String,
    /** 会话根目录（`filesDir/import/<id>`）。 */
    val root: File
) {

    /** 导入入口。 */
    enum class Entry {
        /** 「添加角色」：会创建新角色。 */
        NEW_DOLL,

        /** 「资源导入」：只给现有角色加 / 换动作。 */
        ANIMATION
    }

    /** 用户最初选中的文件（来自选文件通道的稳定副本）。 */
    var picked: List<String> = emptyList()

    /** 识别结果（null = 还没识别）。 */
    var analysis: ImportPlan.Result? = null

    /**
     * 用户为各槽位**显式选定**的分组键。
     *
     * 【为什么需要】R-072 要求「同一状态识别到多组素材时必须让用户选，不能静默覆盖」。
     *   用户的决定必须落在会话里，而不是从 `analysis` 里每次重算「第一组」——
     *   否则用户选了第二组，界面一重组就弹回第一组。
     */
    val chosen: MutableMap<ImportPlan.Slot, String> = LinkedHashMap()

    /** 额外动作（自动 / 手动）中被采纳的分组键。 */
    val chosenExtras: MutableSet<String> = LinkedHashSet()

    /** 角色名字（默认取文件名，可改）。 */
    var nameDraft: String = ""

    /** 显示大小（百分比，100 = 默认）。 */
    var sizeDraft: Int = DollSizeSpec.Spec.SIZE_DEFAULT

    /** 水平 / 垂直偏移（-1..1，0 = 居中）。 */
    var offsetX: Float = 0f
    var offsetY: Float = 0f

    /** 用户是否已确认过识别结果（用于「直接使用」路径跳过歧义页）。 */
    var confirmed: Boolean = false

    /** 提交状态（防重复提交，21.2.8）。 */
    var committing: Boolean = false

    /** 提交成功后的角色 ID（完成页用）。 */
    var committedDollId: String? = null

    /** 提交时生成的一条面向用户的结果摘要（完成页用）。 */
    var resultNote: String = ""

    /** 会话临时目录下给某一动作分配的子目录（不存在就建）。 */
    fun dirFor(key: String): File {
        val safe = key.replace('/', '_').replace('\\', '_')
        val d = File(root, "staging/" + safe)
        if (!d.isDirectory) {
            d.mkdirs()
        }
        return d
    }

    /** 取某槽位用户选定的那一组（没选过就取识别到的第一组）。 */
    fun groupOf(slot: ImportPlan.Slot): ImportPlan.Group? {
        val r = analysis ?: return null
        val all = r.bySlot[slot] ?: return null
        val key = chosen[slot]
        if (key != null) {
            for (g in all) {
                if (g.key == key) {
                    return g
                }
            }
        }
        return all.firstOrNull()
    }

    /** 采纳的额外动作组。 */
    fun extraGroups(): List<ImportPlan.Group> {
        val r = analysis ?: return emptyList()
        val out = ArrayList<ImportPlan.Group>()
        for (g in r.extras) {
            if (chosenExtras.isEmpty() || chosenExtras.contains(g.key)) {
                out.add(g)
            }
        }
        return out
    }

    /** 四个固定状态里已设置的那些（完成页 / 识别页展示用）。 */
    fun filledSlots(): List<ImportPlan.Slot> =
        ImportPlan.FIXED_SLOTS.filter { (groupOf(it)?.files?.isNotEmpty()) == true }

    /** 删除本会话的全部临时内容（幂等）。 */
    fun discard() {
        try {
            deleteTree(root)
        } catch (t: Throwable) {
            Logs.w(TAG, "discard failed for " + id, t)
        }
    }

    companion object {
        private const val TAG = "DollhouseImportSession"

        /** 会话根目录：`filesDir/import`。 */
        @JvmStatic
        fun rootOf(ctx: Context): File {
            val d = File(ctx.filesDir, "import")
            if (!d.isDirectory) {
                d.mkdirs()
            }
            return d
        }

        /** 新建会话。 */
        @JvmStatic
        fun create(ctx: Context, entry: Entry, dollId: String = ""): ImportSession {
            val id = UUID.randomUUID().toString()
            val dir = File(rootOf(ctx), id)
            if (!dir.isDirectory) {
                dir.mkdirs()
            }
            return ImportSession(id, entry, dollId, dir)
        }

        /**
         * 清理**上次遗留**的会话目录。
         *
         * 【为什么需要】进程被杀 / 用户强退时 [discard] 不会执行，临时目录会一直堆在
         *   `filesDir/import` 下。它们可能几十 MB（一整套帧），不清就是慢性漏空间。
         *   【为什么不在启动时全清】可能还有别的会话在工作（多窗口）；
         *   这里只清「超过一天没动过」的，保守到不会误伤。
         */
        @JvmStatic
        fun sweepStale(ctx: Context, olderThanMs: Long = 24L * 3600L * 1000L) {
            try {
                val root = rootOf(ctx)
                val kids = root.listFiles() ?: return
                val now = System.currentTimeMillis()
                for (d in kids) {
                    if (!d.isDirectory) {
                        d.delete()
                        continue
                    }
                    if (now - d.lastModified() > olderThanMs) {
                        deleteTree(d)
                    }
                }
            } catch (t: Throwable) {
                Logs.w(TAG, "sweepStale failed", t)
            }
        }

        /** 递归删除（幂等、不抛）。 */
        @JvmStatic
        fun deleteTree(d: File?) {
            if (d == null || !d.exists()) {
                return
            }
            if (d.isDirectory) {
                val kids = d.listFiles()
                if (kids != null) {
                    for (k in kids) {
                        deleteTree(k)
                    }
                }
            }
            d.delete()
        }
    }
}