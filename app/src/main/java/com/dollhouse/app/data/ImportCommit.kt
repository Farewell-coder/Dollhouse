package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.core.Logs
import java.io.File
import java.io.IOException

/**
 * 【职责】把导入会话**原子地**落成正式角色数据。
 *
 * ## 事务顺序（v2.1·21.2）
 *
 * ```
 * ① 校验      ID / 帧非空 / 名称合法 / 目标路径可写
 * ② 暂存      全部帧先写进会话的 staging 目录（此时正式数据零变化）
 * ③ 备份      把将被覆盖的旧帧目录整体改名成 `__bak_<ts>`（不删！）
 * ④ 写入      把暂存帧搬进正式目录
 * ⑤ 校验      帧文件数 == 索引里的帧数，且每个文件都存在
 * ⑥ 更新索引  DollAnim.update / add 写档案（唯一「可见」的一步）
 * ⑦ 清理备份  删掉旧目录
 * ```
 *
 * 【为什么不能「先删旧再写新」】需求 21.2.3 明确禁止。原因是任何一步中断都会
 *   留下「索引指向空气」的状态：档案里还记着 N 个帧路径，盘上已经没了。
 *   本类改成「旧目录改名保守、新目录写好再删备份」，
 *   每个中断点上**要么是完整的旧版本，要么是完整的新版本**。
 *
 * 【为什么索引一定要最后写】DollAnim 的档案是「可见性」的唯一真源：
 *   帧文件多几个没人看得到，但档案指向不存在的文件会立刻表现为「人偶不动 / 空白」。
 *   所以写档案必须是最后一步，且失败要整体回滚。
 */
object ImportCommit {

    private const val TAG = "DollhouseImportCommit"

    /**
     * 【附录D-4】单个槽提交成功后留下的「凭据」。
     *
     * 【为什么不再在 [writeSlot] 里自己删备份】原来的写法是「每个槽各自备份 → 写 → 校验 → 删备份」，
     *   单槽内部是原子的，但**多个槽之间不是**：第 1 个槽成功（旧帧已被覆盖、备份已删），
     *   第 2 个槽失败时只对「新建角色」做了 `DollStore.remove` ——
     *   已有角色没有任何回滚，而界面还提示「原有角色没有变化」。
     *   用户看到「失败」后去重试或换图，角色的常驻动作其实已被半成品替换。
     *
     * 【现在的口径】[writeSlot] 只负责「备份 + 写入 + 校验」，**不删备份**，
     *   并把这条凭据返回给 [commit]：
     *   · 全部槽都成功后 → [commit] 统一删掉所有备份（正常收尾）；
     *   · 任一槽失败   → [commit] 按逆序用凭据把每个已提交的槽换回旧帧。
     *
     * @param slotId 固定壳 id
     * @param backup 旧帧目录的备份位置；null 表示这个槽原本是空的（回滚 = 清空新帧）
     * @param previous 提交前的档案快照（回滚时写回，保证索引与磁盘一致）
     */
    private class SlotCommit(
        val slotId: String,
        val backup: File?,
        val previous: DollAnim.Action?
    )

    /** 提交结果。 */
    data class Result(
        val ok: Boolean,
        /** 面向用户的一句话（成功 / 失败都非空）。 */
        val message: String,
        /** 成功时的角色 ID。 */
        val dollId: String? = null,
        /** 成功时已具备的固定状态。 */
        val filled: List<ImportPlan.Slot> = emptyList(),
        /** 是否替换了原有的同槽素材（完成页要显示「已替换」那一行）。 */
        val replaced: Boolean = false
    )

    /**
     * 提交。
     *
     * 【调用约束】必须在**后台线程**调用（会解码 / 写盘 / 递归目录）。
     *   [ImportSession.committing] 已在入口处置位，防止重复提交（21.2.8）。
     */
    fun commit(ctx: Context, session: ImportSession): Result {
        // ---------- ① 校验 ----------
        val analysis = session.analysis
            ?: return Result(false, "还没有识别到可用的角色，请重新选择文件。")

        // 固定状态里至少要有一个可用组（缺动作能继续，但一个都没有就没意义）。
        val fixed = LinkedHashMap<ImportPlan.Slot, ImportPlan.Group>()
        for (slot in ImportPlan.FIXED_SLOTS) {
            val g = session.groupOf(slot) ?: continue
            if (g.files.isEmpty()) {
                continue
            }
            fixed[slot] = g
        }
        if (fixed.isEmpty()) {
            return Result(false, "没有找到能用的角色图，请换一组文件再试。")
        }
        // 名字合法性：空 → 用默认名；超长截断（21.2.1）。
        val name = session.nameDraft.trim().ifBlank { "新角色" }.let {
            if (it.length > 24) it.substring(0, 24) else it
        }

        // ---------- ② 暂存：全部帧先落到会话目录 ----------
        val staged = LinkedHashMap<ImportPlan.Slot, List<String>>()
        for ((slot, group) in fixed) {
            val dir = session.dirFor(ImportPlan.keyOf(slot))
            val r = try {
                AnimImport.import(ctx, group.files, dir)
            } catch (t: Throwable) {
                Logs.w(TAG, "staging failed for " + slot, t)
                return Result(false, "导入过程出错，原有角色没有变化。请重试。")
            }
            if (!r.ok || r.frames.isEmpty()) {
                return Result(
                    false,
                    "「" + ImportPlan.labelOf(slot) + "」这几张图读不出来，导入没有完成。原有角色没有变化。"
                )
            }
            staged[slot] = r.frames
        }
        // 额外动作（自动 / 手动）同样先暂存。
        val stagedExtras = LinkedHashMap<String, Pair<ImportPlan.Group, List<String>>>()
        for (g in session.extraGroups()) {
            if (g.files.isEmpty()) {
                continue
            }
            val dir = session.dirFor("extra_" + g.key)
            val r = try {
                AnimImport.import(ctx, g.files, dir)
            } catch (t: Throwable) {
                continue
            }
            if (r.ok && r.frames.isNotEmpty()) {
                stagedExtras[g.key] = Pair(g, r.frames)
            }
        }

        // ---------- ③ 定角色 ----------
        var createdNew = false
        val dollId: String
        if (session.entry == ImportSession.Entry.NEW_DOLL || session.dollId.isEmpty()) {
            val doll = try {
                DollStore.addTemplate(ctx, name)
            } catch (t: Throwable) {
                Logs.w(TAG, "addTemplate failed", t)
                return Result(false, "没能保存这个角色，请重试。")
            }
            dollId = doll.id
            createdNew = true
        } else {
            dollId = session.dollId
            if (DollStore.byId(ctx, dollId) == null) {
                return Result(false, "这个角色已经不在了，请返回列表重新选择。")
            }
        }

        // 到这里为止，正式数据唯一的改动就是「新建了一个空角色」——
        // 它自带四个空壳（DollAnim.all 自愈），不影响任何已有角色。
        var committedAny = false
        var replaced = false
        // 【附录D-4】已提交槽的凭据：任一槽失败就按逆序全部还原；全部成功才清理备份。
        val commits = ArrayList<SlotCommit>()
        try {
            // ---------- ④⑤⑥ 逐槽提交 ----------
            for ((slot, frames) in staged) {
                val slotId = DollAnim.slotIdOf(groupOf(slot) ?: continue) ?: continue
                val wasFilled = hasContent(ctx, dollId, slotId)
                val c = writeSlot(ctx, dollId, slotId, frames)
                if (c == null) {
                    throw IOException("slot commit failed: " + slot)
                }
                commits.add(c)
                committedAny = true
                if (wasFilled) {
                    replaced = true
                }
            }
            // 额外动作：新建（不替换同名），失败不影响已提交的固定状态。
            for ((_, pair) in stagedExtras) {
                val g = pair.first
                val frames = pair.second
                val group = if (g.slot == ImportPlan.Slot.AUTO) {
                    DollAnim.GROUP_RANDOM
                } else {
                    DollAnim.GROUP_MANUAL
                }
                try {
                    val a = DollAnim.add(
                        ctx, dollId, g.name.ifBlank { "动作" }, "", frames,
                        DollAnim.FPS_DEFAULT, true, group
                    )
                    if (a != null) {
                        // 顺带把本体基准写进去（没有它，这个动作播放时会按整画布缩放）。
                        val box = try {
                            FrameScope.computeBaseline(frames)
                        } catch (t: Throwable) {
                            null
                        }
                        if (box != null && box.isValid()) {
                            DollAnim.update(ctx, dollId, a.copy(baseline = encodeBox(box)))
                        }
                        committedAny = true
                    }
                } catch (t: Throwable) {
                    Logs.w(TAG, "extra action commit failed: " + g.key, t)
                }
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "commit failed, rolling back doll=" + dollId, t)
            // ---------- 回滚 ----------
            // 【附录D-4】先把已提交的槽按逆序还原（后写的先退），再处理角色记录。
            //   逆序不是必须的（槽之间互不依赖），但保持「与写入相反」的直觉顺序，
            //   排查日志时能一眼对上。
            var rollbackClean = true
            for (i in commits.indices.reversed()) {
                val c = commits[i]
                try {
                    rollbackSlot(ctx, dollId, c.slotId,
                        c.backup != null && c.backup.exists(), c.backup, c.previous)
                } catch (e: Throwable) {
                    rollbackClean = false
                    Logs.w(TAG, "rollback failed for slot=" + c.slotId, e)
                }
            }
            if (createdNew) {
                // 新建的角色整体删掉：用户没确认成功，列表里不该留半成品。
                // 【附录D-11】remove 内部已经会清掉动作帧目录（DollAnim.clearAll），
                //   这里不再重复扫目录；但本批次自己的备份要清（否则留下 __bak_ 残骸）。
                try {
                    DollStore.remove(ctx, dollId)
                } catch (ignored: Throwable) {
                }
                cleanBackups(commits)
            } else if (rollbackClean) {
                // 已有角色：槽已经全部还原成旧帧，这时的备份就是「旧帧的副本」，
                //   留着只会白占空间 —— 但只有回滚完全成功才敢删。
                cleanBackups(commits)
            } else {
                Logs.w(TAG, "回滚未完全成功，保留备份目录供人工恢复 doll=" + dollId)
            }
            // 【附录D-4】文案必须区分「新建失败（真的没变化）」与「替换失败（已回滚）」，
            //   否则用户会以为角色还好好的，实际上动作可能已是旧版本。
            val msg = if (createdNew) {
                "导入没有完成，没有新建任何角色。可以再试一次，或换一组文件。"
            } else if (rollbackClean) {
                "导入没有完成，这个角色已经还原成导入前的样子。可以再试一次，或换一组文件。"
            } else {
                "导入没有完成，且部分动作没能自动还原。请到角色动作页检查，必要时重新导入。"
            }
            return Result(false, msg)
        }

        if (!committedAny) {
            // 【附录D-4】一个槽都没提交成功：没有任何槽的备份需要还原（commits 为空），
            //   但新建的角色要删掉，否则列表里留一个没有动作的空壳。
            if (createdNew) {
                try {
                    DollStore.remove(ctx, dollId)
                } catch (ignored: Throwable) {
                }
            }
            cleanBackups(commits)
            return Result(false, "这些图没能变成可用的动作，导入没有完成。")
        }

        // ---------- 【附录D-4】全部槽都成功：这时才敢删备份 ----------
        //   放在「角色级收尾」之前：收尾（封面 / 尺寸 / 基准）失败不回滚，
        //   备份在这里已经没有用处 —— 它只为「槽写入失败要换回旧帧」而存在。
        cleanBackups(commits)

        // ---------- 角色级收尾：形象图 / 封面 / 大小 / 名称 ----------
        try {
            // 形象图 = 待机第一帧（列表缩略、气泡头像都用它）。
            val idleFrames = staged[ImportPlan.Slot.IDLE]
            val spriteSrc = idleFrames?.firstOrNull() ?: staged.values.firstOrNull()?.firstOrNull()
            if (spriteSrc != null) {
                DollStore.setSpriteFromFrame(ctx, dollId, spriteSrc)
                DollStore.setCoverFromFrame(ctx, dollId, spriteSrc)
            }
            DollStore.byId(ctx, dollId)?.let { d ->
                DollStore.update(ctx, d.copy(name = name))
            }
            DollSizeSpec.setSpec(
                ctx, dollId,
                DollSizeSpec.Spec(
                    DollSizeSpec.Spec.clampSize(session.sizeDraft),
                    DollSizeSpec.Spec.clampOffset(session.offsetX),
                    DollSizeSpec.Spec.clampOffset(session.offsetY)
                )
            )
        } catch (t: Throwable) {
            // 收尾失败不影响「角色可用」，只是外观不完美 —— 记日志，不整体回滚。
            Logs.w(TAG, "post-commit polish failed for " + dollId, t)
        }
        // ---------- 模型级基准（R-080 / R-086） ----------
        var level = ModelBaseline.Level.OK
        try {
            val r = ModelBaselineCalculator.recompute(ctx, dollId)
            if (r != null) {
                level = r.level
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "baseline recompute failed for " + dollId, t)
        }

        // ---------- ⑥-补 待机回退（v2.1·19.3 最后一行 + 补充规则 2/3） ----------
        // 用户这次没给待机图（只给了拖拽 / 贴边）时，必须挑一个**确定的**回退动作并持久化，
        // 不能每次启动随机挑。候选顺序按「用户最可能期望」排：拖拽 → 贴入 → 贴边。
        // 常驻壳有内容时 recordAfterImport 内部会自行清掉旧记录，这里不需要先判断。
        try {
            IdleFallback.invalidate()
            IdleFallback.recordAfterImport(
                ctx,
                dollId,
                listOf(DollAnim.SLOT_DRAG_ID, DollAnim.SLOT_EDGE_IN_ID, DollAnim.SLOT_EDGE_ID)
            )
        } catch (t: Throwable) {
            // 回退记录失败不影响本次导入结果：下次渲染时会按固定规则重算并落盘。
            Logs.w(TAG, "idle fallback record failed for " + dollId, t)
        }


        // ---------- ⑦ 清理会话 ----------
        session.committedDollId = dollId
        session.discard()

        val filledSlots = ImportPlan.FIXED_SLOTS.filter { staged.containsKey(it) }
        val msg = when (level) {
            ModelBaseline.Level.REVIEW -> "导入完成。有几个动作的大小差得比较多，建议手动对齐一下。"
            ModelBaseline.Level.AUTO_FIXED -> "导入完成，动作大小已经自动对齐。"
            else -> "导入完成。"
        }
        return Result(true, msg, dollId, filledSlots, replaced)
    }

    /** 槽位 → 动作分组名。 */
    private fun groupOf(slot: ImportPlan.Slot): String? = when (slot) {
        ImportPlan.Slot.IDLE -> DollAnim.GROUP_ALWAYS
        ImportPlan.Slot.DRAG -> DollAnim.GROUP_DRAG
        ImportPlan.Slot.EDGE_IN -> DollAnim.GROUP_EDGE_IN
        ImportPlan.Slot.EDGE_LOOP -> DollAnim.GROUP_EDGE
        else -> null
    }

    /** 该固定壳当前是否有内容。 */
    private fun hasContent(ctx: Context, dollId: String, slotId: String): Boolean {
        val a = DollAnim.byId(ctx, dollId, slotId) ?: return false
        return a.frames.isNotEmpty() || a.assetPath.isNotEmpty()
    }

    /**
     * 把一批暂存帧写进某个固定壳（**带备份，备份的清理交给调用方**）。
     *
     * ## 为什么备份是「改名」而不是「复制」
     * 复制会临时占用双倍空间（一整套 120 帧 PNG 可能几十 MB），而改名的成本是常数级，
     * 且两者在同一个 `filesDir` 里，`renameTo` 必然成功（21.2.2 要求的「同一文件系统」）。
     *
     * ## 【附录D-4】为什么成功时也不删备份
     * 多槽导入的事务边界在 [commit]，不在单槽。这里把备份路径返回给调用方，
     * 由它在「全部槽成功」时统一删、在「有槽失败」时按凭据回滚 ——
     * 否则第 1 个槽的备份一删，第 2 个槽失败时就再也换不回旧帧了。
     *
     * @return 成功时返回凭据，失败时返回 null（失败路径内部已把自己改过的部分还原）
     */
    private fun writeSlot(
        ctx: Context,
        dollId: String,
        slotId: String,
        frames: List<String>
    ): SlotCommit? {
        val official = DollAnim.framesDir(ctx, dollId, slotId)
        val stamp = System.currentTimeMillis()
        val backup = File(official.parentFile, slotId + "__bak_" + stamp)
        // 提交前的档案快照：回滚时连同索引一起还原（否则索引会指向已被搬走的新帧）。
        val previous = DollAnim.byId(ctx, dollId, slotId)
        // 这个槽原本有没有内容 —— 决定回滚时是「换回备份」还是「清空」。
        var hadContent = official.exists() && (official.listFiles()?.isNotEmpty() == true)
        // ① 旧目录整体改名（不删）。
        if (official.exists()) {
            if (!official.renameTo(backup)) {
                // 改名失败（极少见：被占用 / 权限）：退回「逐文件备份」的保守路径。
                if (!backupByCopy(official, backup)) {
                    return null
                }
                deleteFilesIn(official)
            }
        } else {
            official.mkdirs()
            hadContent = false
        }
        // ② 新帧搬进正式目录。
        val written = ArrayList<String>(frames.size)
        for (src in frames) {
            val f = File(src)
            if (!f.isFile) {
                continue
            }
            val dst = File(official, f.name)
            val ok = if (f.renameTo(dst)) {
                true
            } else {
                try {
                    f.copyTo(dst, overwrite = true)
                    true
                } catch (t: Throwable) {
                    false
                }
            }
            if (!ok) {
                // ③ 校验失败 → 回滚到提交前状态（见 rollbackSlot）。
                rollbackSlot(ctx, dollId, slotId, hadContent, backup, previous)
                return null
            }
            written.add(dst.absolutePath)
        }
        if (written.isEmpty()) {
            rollbackSlot(ctx, dollId, slotId, hadContent, backup, previous)
            return null
        }
        // ④ 校验：文件真的都在。
        for (p in written) {
            if (!File(p).isFile) {
                rollbackSlot(ctx, dollId, slotId, hadContent, backup, previous)
                return null
            }
        }
        // ⑤ 最后写索引（唯一可见的一步）。
        val cur = previous
        if (cur == null) {
            rollbackSlot(ctx, dollId, slotId, hadContent, backup, previous)
            return null
        }
        // 本体基准：能算出来就写，算不出来留空（行为回到「整画布」口径，不会更糟）。
        var baseline = ""
        try {
            val box = FrameScope.computeBaseline(written)
            if (box != null && box.isValid()) {
                baseline = encodeBox(box)
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "baseline of new frames failed for " + slotId, t)
        }
        val next = cur.copy(
            frames = ArrayList(written),
            baseline = baseline,
            // 固定壳恒为循环（拖拽 / 常驻 / 贴边都是持续态；19.3 要求）。
            loop = true
        )
        DollAnim.update(ctx, dollId, next)
        // 校验：档案里确实写进去了。
        val check = DollAnim.byId(ctx, dollId, slotId)
        if (check == null || check.frames.size != written.size) {
            // 索引没写进去 → 恢复旧帧目录，并尽力恢复旧索引。
            rollbackSlot(ctx, dollId, slotId, hadContent, backup, previous)
            return null
        }
        // ⑥ 【附录D-4】不在这里删备份：交给 commit 在所有槽都成功后统一清理。
        //   backup 是否存在与 hadContent 无关（旧目录可能只是个空壳），
        //   所以这里按「盘上真的有备份目录」返回，清理逻辑才不会漏。
        return SlotCommit(slotId, if (backup.exists()) backup else null, previous)
    }

    /**
     * 【附录D-4】把一个槽还原到提交前的状态。
     *
     * 【三种情况】
     * · 原本有内容（[hadContent]）且备份在 → 删掉新帧、把备份改名回去；
     * · 原本有内容但改名回滚失败 → 退化为「把备份内容拷回」再清备份
     *   （这是附录 D-6 点出的「回滚路径静默失败」：原帧还在盘上，不能让它永久失联）；
     * · 原本是空的 → 只清掉新帧，不动索引以外的东西。
     *
     * 【索引一起还原】只还原磁盘而不还原索引会让档案指向已被删掉的新帧 ——
     *   表现为「动作列表里有这一项，但播放时是空白」。
     */
    private fun rollbackSlot(
        ctx: Context,
        dollId: String,
        slotId: String,
        hadContent: Boolean,
        backup: File?,
        previous: DollAnim.Action?
    ) {
        val official = DollAnim.framesDir(ctx, dollId, slotId)
        deleteFilesIn(official)
        if (hadContent && backup != null && backup.exists()) {
            official.delete()
            if (!backup.renameTo(official)) {
                // 改名失败（跨挂载点 / 被占用）→ 逐文件拷贝回去，再清掉备份。
                deleteFilesIn(official)
                val all = backup.listFiles()
                var lost = 0
                if (all != null) {
                    for (k in all) {
                        if (k.isFile) {
                            try {
                                k.copyTo(File(official, k.name), overwrite = true)
                            } catch (t: Throwable) {
                                lost++
                                Logs.w(TAG, "回滚拷贝失败: " + k.name, t)
                            }
                        }
                    }
                }
                if (lost == 0) {
                    ImportSession.deleteTree(backup)
                } else {
                    // 原帧没能全部回来 —— 留着备份目录，至少数据还在盘上。
                    Logs.w(TAG, "回滚未能完全恢复 slot=" + slotId + "，备份保留在 " + backup.name)
                }
            }
        }
        // 索引回到提交前：有旧档案就写回旧档案，没有就把帧列表清空。
        if (previous != null) {
            DollAnim.update(ctx, dollId, previous)
        }
    }

    /** 全部槽成功后的统一清理：删掉本批次产生的备份目录。 */
    private fun cleanBackups(commits: List<SlotCommit>) {
        for (c in commits) {
            val b = c.backup ?: continue
            try {
                ImportSession.deleteTree(b)
            } catch (t: Throwable) {
                Logs.w(TAG, "backup cleanup failed: " + b.name, t)
            }
        }
    }

    /** 改名失败时的退路：逐个文件拷到备份目录。 */
    private fun backupByCopy(from: File, to: File): Boolean {
        return try {
            if (!to.exists()) {
                to.mkdirs()
            }
            val kids = from.listFiles() ?: return true
            for (k in kids) {
                if (k.isFile) {
                    k.copyTo(File(to, k.name), overwrite = true)
                }
            }
            true
        } catch (t: Throwable) {
            Logs.w(TAG, "backupByCopy failed", t)
            false
        }
    }

    /** 只删目录里的文件，保留目录本身。 */
    private fun deleteFilesIn(dir: File) {
        val kids = dir.listFiles() ?: return
        for (k in kids) {
            if (k.isDirectory) {
                ImportSession.deleteTree(k)
            } else {
                k.delete()
            }
        }
    }

    /** 本体框编码（与 `AnimBaker` 同格式：`l,t,r,b`，4 位小数）。 */
    private fun encodeBox(b: BodyBaseline.Box): String {
        val f = java.util.Locale.US
        return String.format(f, "%.4f,%.4f,%.4f,%.4f", b.left, b.top, b.right, b.bottom)
    }
}