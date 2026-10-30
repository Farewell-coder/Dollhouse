package com.dollhouse.app.ui.doll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.data.DollSizeSpec
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.ImportCommit
import com.dollhouse.app.data.ImportPlan
import com.dollhouse.app.data.ImportSession
import com.dollhouse.app.data.ModelBaseline
import com.dollhouse.app.data.FrameScope
import com.dollhouse.app.data.ImportSource
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Icons
import java.io.File

/**
 * 【职责】「添加角色」导入助手 —— 新手 / 高手两条路径，共用同一套导入核心。
 *
 * ## 页面结构（R-040 / R-056：最多三步）
 *
 * ```
 * 选择页（添加角色 / 选一种方式开始。）
 *   ├─ 新手 · 拎包入住：选文件 → 预览（名字/大小/位置）→ 完成      （3 步）
 *   └─ 高手 · 亲手打造：三组素材 + 可选贴入 → 预览 → 完成                （2 步）
 * ```
 *
 * 识别结果页（8.6）**不是必经页**：只有识别出现歧义（同槽多组 / 像两个角色）才插入。
 * 这正是 R-044「只有一张图时跳过识别结果页」与 19.1.4 / 19.1.5 的落点 ——
 * 简单场景不该付出复杂流程的代价。
 *
 * ## 为什么步骤是同一页面里的状态机，而不是压多个页面栈层
 *
 * R-060 要求「所有导入入口必须复用同一套导入核心」，21.1 要求会话与正式数据分离。
 * 把步骤做成同一页面内的状态机有两个直接好处：
 * ① 返回键行为自然 —— 退一步，而不是退一层再重建；
 * ② 会话对象与步骤状态一起被 `remember` 持有，页面重组不会丢掉用户已选的文件
 *    （素材本身在 filesDir 的会话目录里，重建也还在）。
 *
 * ## 文案纪律（R-053 / 23.3）
 *
 * 面向用户的文字里不出现 `idle / drag / edge_in / edge_loop / manifest / fps /
 * baseline / scaleFix`。状态名一律走 [ImportPlan.labelOf]。
 */
object ImportAssistantPage {

    /** 页面 key（无参数）。 */
    const val PREFIX = "doll:add"

    /** 内部步骤。 */
    private enum class Step {
        /** 选路径：拎包入住 / 亲手打造。 */
        CHOOSE,

        /** 新手第 1 步：选图片或压缩包。 */
        NOVICE_PICK,

        /** 高手第 1 步：三组素材，贴入瞬间作为贴边的可选项。 */
        ARTIST,

        /** 识别完成（仅在有歧义 / 需确认时出现）。 */
        REVIEW,

        /** 预览（真实比例、四态页签）。 */
        PREVIEW,

        /** 完成页。 */
        DONE
    }

    @JvmStatic
    fun open(act: MainActivity) {
        PageStack.push(act, PREFIX) {
            ComposeHost.createView(act) { Content(act) }
        }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity): android.view.View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @Composable
    private fun Content(act: MainActivity) {
        var step by remember { mutableStateOf(Step.CHOOSE) }
        // 【R-112】用户从哪条路径进来的：影响预览页标题与主按钮文案。
        var fromArtist by remember { mutableStateOf(false) }
        var session by remember { mutableStateOf<ImportSession?>(null) }
        var busy by remember { mutableStateOf(false) }
        var busyText by remember { mutableStateOf("") }
        var failure by remember { mutableStateOf<String?>(null) }
        // 【R-070-5】是否等待用户回答「这是一组待机动画吗」。
        var pendingAskIdle by remember { mutableStateOf(false) }
        // 【R-070-6】识别失败时是否显示三个出口按钮。
        var showFailureActions by remember { mutableStateOf(false) }
        // 支持格式说明弹窗。
        var showFormats by remember { mutableStateOf(false) }
        // 【R-112 十二.5】导入中断确认。
        var showInterrupted by remember { mutableStateOf(false) }
        // 高手路径三组素材及可选贴入图片。
        var artistIdle by remember { mutableStateOf<List<String>>(emptyList()) }
        var artistDrag by remember { mutableStateOf<List<String>>(emptyList()) }
        var artistEdge by remember { mutableStateOf<List<String>>(emptyList()) }
        // 贴入瞬间并入贴边设置，为可选项。
        var artistEdgeIn by remember { mutableStateOf<List<String>>(emptyList()) }
        // 【R-112 六节】高手页「额外动作（可选）」弹窗开关。
        var showExtraSheet by remember { mutableStateOf(false) }
        // 预览页当前选中的状态页签。
        var previewSlot by remember { mutableStateOf(ImportPlan.Slot.IDLE) }
        // 【R-087/R-088】大小检查面板操作后的一句说明。
        var sizeCheckNote by remember { mutableStateOf<String?>(null) }
        // 【R-086】预览页的尺寸差异等级：null=未测 / 量不出。
        var sizeDiffLevel by remember { mutableStateOf<ModelBaseline.Level?>(null) }
        // 完成页信息。
        var doneFilled by remember { mutableStateOf<List<ImportPlan.Slot>>(emptyList()) }
        var doneReplaced by remember { mutableStateOf(false) }
        var doneDollId by remember { mutableStateOf<String?>(null) }

        /**
         * 【R-086 / R-112 六.4】算一下各状态与待机之间的最大身高差。
         *
         * 返回 null = 量不出来（全空壳 / 全 Lottie / 坏图），此时按「无法判断」提示。
         * 这里**只读不写**：19.7.9 要求微调保存为参数，不能在预览阶段改数据。
         */
        fun measureMaxDiff(ses: ImportSession): Float? {
            val idleGroup = ses.groupOf(ImportPlan.Slot.IDLE) ?: return null
            val idleBox = try {
                FrameScope.computeBaseline(idleGroup.files)
            } catch (t: Throwable) {
                null
            } ?: return null
            val ref = idleBox.height
            if (!ref.isFinite() || ref <= 0f) {
                return null
            }
            var maxDiff = 0f
            for (slot in ImportPlan.FIXED_SLOTS) {
                if (slot == ImportPlan.Slot.IDLE) continue
                val g = ses.groupOf(slot) ?: continue
                if (g.files.isEmpty()) continue
                val box = try {
                    FrameScope.computeBaseline(g.files)
                } catch (t: Throwable) {
                    null
                } ?: continue
                val d = ModelBaseline.diffRatio(ref, box.height)
                if (d > maxDiff) maxDiff = d
            }
            return maxDiff
        }

        /** 后台识别一批选中的文件。 */
        fun analyzeAsync(picked: List<String>) {
            showFailureActions = false
            pendingAskIdle = false
            busy = true
            busyText = "正在识别…"
            failure = null
            Thread({
                val s = ImportSession.create(act, ImportSession.Entry.NEW_DOLL, "")
                s.picked = picked
                val expanded = ImportSource.expand(act, picked, File(s.root, "expand"))
                if (!expanded.ok) {
                    val msg = expanded.error?.let { ImportSource.textOf(it) }
                        ?: "这个文件里没有找到能用的角色图。"
                    ImportSession.deleteTree(s.root)
                    act.runOnUiThread {
                        busy = false
                        failure = msg
                        // 【R-070 第 6 条 / R-112 十二.1】失败不能只留一句话，要给出口。
                        showFailureActions = true
                    }
                    return@Thread
                }
                val analysis = ImportPlan.analyze(expanded.items.map { it.rel })
                // 【R-070 第 5 条】多张同尺寸 PNG 且完全认不出槽位时，问一次
                //   「这是一组待机动画吗」。不默认替用户决定（此前是直接当待机）。
                val needAskIdle = !analysis.has(ImportPlan.Slot.IDLE) &&
                    analysis.bySlot.isEmpty() && analysis.loose.size > 1
                act.runOnUiThread { pendingAskIdle = needAskIdle }
                // 散图 → 「一组待机帧」：整批都认不出槽位时，按待机处理（R-070 第 4 条）。
                val effective = if (analysis.has(ImportPlan.Slot.IDLE) || analysis.loose.isEmpty()) {
                    analysis
                } else {
                    val idle = ImportPlan.Group(
                        key = "loose",
                        slot = ImportPlan.Slot.IDLE,
                        files = expanded.items.map { it.abs },
                        name = "待机"
                    )
                    ImportPlan.Result(
                        bySlot = analysis.bySlot + (ImportPlan.Slot.IDLE to listOf(idle)),
                        extras = analysis.extras,
                        loose = emptyList(),
                        warnings = analysis.warnings
                    )
                }
                s.analysis = effective
                if (s.nameDraft.isBlank()) {
                    s.nameDraft = nameFromFile(picked.firstOrNull())
                }
                act.runOnUiThread {
                    busy = false
                    session?.discard()
                    session = s
                    previewSlot = ImportPlan.Slot.IDLE
                    val ambiguous = effective.ambiguousSlots().isNotEmpty() ||
                        effective.looksLikeTwoRoles() ||
                        effective.warnings.contains(ImportPlan.Warning.MULTI_ROLE)
                    sizeDiffLevel = measureMaxDiff(s)?.let { ModelBaseline.levelOf(it) }
                    step = if (ambiguous) Step.REVIEW else Step.PREVIEW
                }
            }, "dh-import-analyze").start()
        }

        /** 提交（后台 → 回主线程进完成页）。 */
        fun commitAsync() {
            val s = session
            if (s == null) {
                failure = "还没有选好素材，请重新选择。"
                return
            }
            if (s.committing) {
                // R-090 / 21.2.8：执行中禁止重复提交。
                return
            }
            s.committing = true
            busy = true
            busyText = "正在把角色安顿好…"
            Thread({
                val r = try {
                    ImportCommit.commit(act, s)
                } catch (t: Throwable) {
                    ImportCommit.Result(false, "导入没有完成，原有角色没有变化。请重试。")
                }
                act.runOnUiThread {
                    busy = false
                    s.committing = false
                    if (!r.ok) {
                        failure = r.message
                        return@runOnUiThread
                    }
                    doneFilled = r.filled
                    doneReplaced = r.replaced
                    doneDollId = r.dollId
                    HomeUi.notifyPetReload(act)
                    DollState.bump()
                    session = null
                    step = Step.DONE
                }
            }, "dh-import-commit").start()
        }

        /**
         * 离开页面。
         *
         * 【R-112 十二.5】识别到一半 / 已经选好素材但还没提交时离开，
         *   属于「导入中断」：不静默丢素材，先告诉用户「已经保留你选的文件」，
         *   由他自己选继续还是重来。这样就不会出现「我选好的图去哪了」。
         *
         * 【为什么只在有会话时提示】没选文件就退出 = 正常取消，不该弹窗（23.2.6）。
         */
        fun leave() {
            val hasPending = session != null && !busy
            if (hasPending) {
                showInterrupted = true
                return
            }
            session?.discard()
            session = null
            PageStack.pop(act)
        }

        /** 真正退出（中断弹窗里点「重新选择」也走这条）。 */
        fun leaveForReal() {
            session?.discard()
            session = null
            PageStack.pop(act)
        }

        // 系统返回与顶栏返回共用页内路由：素材页先回选择页，不整页退出。
        DisposableEffect(step) {
            PageStack.backInterceptor = PageStack.BackInterceptor(PREFIX) {
                if (step != Step.CHOOSE) { step = Step.CHOOSE; true } else false
            }
            onDispose {
                if (PageStack.backInterceptor?.key == PREFIX) PageStack.backInterceptor = null
            }
        }
        val s = session
        when (step) {
            Step.CHOOSE -> DhKit.Page(
                title = "添加角色",
                // 【R-112 四节】副标题定案：不再用「她想怎么住进来？」。
                sub = "选一种方式开始。",
                onBack = { leave() }
            ) {
                ImportAssistantKit.ChoiceCard(
                    tag = "新手",
                    title = "拎包入住",
                    desc = "用一张图或现成模型，几步就能住进来。",
                    iconRes = Icons.IC_WAND_SPARKLES
                ) {
                    failure = null
                    fromArtist = false
                    step = Step.NOVICE_PICK
                }
                ImportAssistantKit.ChoiceCard(
                    // 【R-112 四节】「画师」→「高手」，并补一行解释：
                    //   「高手」指创作能力（会画画、懂素材），不是操作熟练度。
                    tag = "高手",
                    title = "亲手打造",
                    desc = "导入你画好的角色和动作，自己做细节。",
                    iconRes = Icons.IC_BRUSH,
                    extra = "会画画、懂素材"
                ) {
                    failure = null
                    fromArtist = true
                    step = Step.ARTIST
                }
                DhForm.Note("随时可以返回。选好之前，不会动到任何已有的角色。")
                failure?.let { ImportAssistantKit.FailureBox(it) }
            }

            Step.NOVICE_PICK -> DhKit.Page(
                title = "先选一张图",
                sub = "一张图也可以",
                onBack = { step = Step.CHOOSE }
            ) {
                DhForm.Note(
                    "支持 PNG、GIF、WebP、Lottie 动画和压缩包。" +
                        "如果是一整组动作，可以一起选，或者打包成压缩包。",
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
                Spacer(Modifier.height(10.dp))
                DhKit.MiniChip("选择图片或压缩包", iconRes = Icons.IC_FOLDER_OPEN) {
                    PickFileActivity.setFileListener { paths, failed ->
                        if (paths.isEmpty()) {
                            if (failed) {
                                failure = "没能读取选中的文件，请再试一次。"
                            }
                            return@setFileListener
                        }
                        analyzeAsync(paths)
                    }
                    PickFileActivity.startFiles(act, true)
                }
                Spacer(Modifier.height(8.dp))
                // 【R-112 五.1】次按钮：示例角色（不经识别流程，直接给默认模板）。
                DhKit.MiniChip("先用示例角色试试", iconRes = Icons.IC_WAND_SPARKLES) {
                    DollStore.addTemplate(act, "示例角色")
                    DollState.bump()
                    HomeUi.notifyPetReload(act)
                    PageStack.pop(act)
                }
                if (busy) {
                    ImportAssistantKit.BusyHint(busyText)
                }
                failure?.let { ImportAssistantKit.FailureBox(it) }
                // 【R-070-6】识别失败的三个出口：重选 / 支持格式 / 示例角色。
                if (showFailureActions) {
                    Spacer(Modifier.height(8.dp))
                    DhKit.MiniChip("重新选择", iconRes = Icons.IC_REFRESH) {
                        showFailureActions = false
                        failure = null
                        PickFileActivity.startFiles(act, true)
                    }
                    Spacer(Modifier.height(6.dp))
                    DhKit.MiniChip("查看支持格式", iconRes = Icons.IC_BOOK_OPEN) {
                        showFormats = true
                    }
                    Spacer(Modifier.height(6.dp))
                    DhKit.MiniChip("用示例角色", iconRes = Icons.IC_WAND_SPARKLES) {
                        DollStore.addTemplate(act, "示例角色")
                        DollState.bump()
                        HomeUi.notifyPetReload(act)
                        PageStack.pop(act)
                    }
                }
            }

            Step.ARTIST -> DhKit.Page(
                title = "亲手打造",
                // 【R-112 六节】两步：放素材 → 预览与大小检查。
                sub = "第 1 步 / 共 2 步",
                onBack = { step = Step.CHOOSE }
            ) {
                DhForm.Note("待机和贴边循环必需；拖动与贴入瞬间可以以后再补。")
                ImportAssistantKit.ArtistRow(
                    title = "站着的她",
                    desc = "平时的待机姿势。",
                    required = true,
                    picked = artistIdle,
                    frames = artistIdle.size,
                    iconRes = Icons.IC_PERSON_STANDING
                ) { pickInto(act) { artistIdle = it } }
                ImportAssistantKit.ArtistRow(
                    title = "拖动时的她",
                    desc = "没有也可以跳过。",
                    required = false,
                    picked = artistDrag,
                    frames = artistDrag.size,
                    iconRes = Icons.IC_HAND_METAL
                ) { pickInto(act) { artistDrag = it } }
                ImportAssistantKit.ArtistRow(
                    title = "贴边的她",
                    desc = "贴边循环必选；贴入瞬间可在里面选补。",
                    required = true,
                    picked = artistEdge,
                    frames = artistEdge.size,
                    iconRes = Icons.IC_GRAB
                ) { pickInto(act) { artistEdge = it } }
                DhKit.SectionLabel("贴入瞬间（可选）", iconRes = Icons.IC_MOVE)
                DhForm.Note(
                    if (artistEdgeIn.isEmpty()) "不设置时，贴到边缘会直接播放贴边循环。"
                    else "已选择 ${artistEdgeIn.size} 帧，会先播放一次再进入贴边循环。"
                )
                DhKit.MiniChip(if (artistEdgeIn.isEmpty()) "添加贴入瞬间" else "更换贴入瞬间", iconRes = Icons.IC_IMAGE_PLUS) {
                    pickInto(act) { artistEdgeIn = it }
                }

                // 【R-112 六.5】只有一组素材时明确告诉用户：这一组会被当成待机。
                //   不这么做，用户会以为「我选了贴边那组，怎么就成待机了」。
                val filledRows = listOf(artistIdle, artistDrag, artistEdgeIn, artistEdge)
                    .count { it.isNotEmpty() }
                if (filledRows == 1 && artistIdle.isEmpty()) {
                    DhForm.Note("只有这一组，先用它当待机。")
                }

                // ---------- 额外动作（可选） ----------
                DhKit.SectionLabel("额外动作（可选）")
                DhForm.Note("可以以后再加。")
                DhKit.MiniChip("添加动作", iconRes = Icons.IC_USER_ROUND_PEN) {
                    showExtraSheet = true
                }
                DhForm.Note("待机、拖拽和贴边在上面设置；贴入瞬间是贴边里的可选项。")

                Spacer(Modifier.height(12.dp))
                // 【R-112 六.4】「站着的她」未选时主按钮置灰（视觉降级 + 点击只提示）。
                val canNext = artistIdle.isNotEmpty() && artistEdge.isNotEmpty()
                Box(Modifier.alpha(if (canNext) 1f else 0.45f)) {
                    DhKit.MiniChip("看看效果", iconRes = Icons.IC_PLAY) {
                        if (artistIdle.isEmpty()) {
                            failure = "第一行「站着的她」是必需的，先选一组图片。"
                            return@MiniChip
                        }
                        if (artistEdge.isEmpty()) {
                            failure = "「贴边的她」是必需的，先选一组贴边循环图片。"
                            return@MiniChip
                        }
                        failure = null
                        busy = true
                        busyText = "正在检查…"
                        Thread({
                            val ses = buildArtistSession(
                                act, artistIdle, artistDrag, artistEdge, artistEdgeIn
                            )
                            act.runOnUiThread {
                                busy = false
                                if (ses == null) {
                                    failure = "这些图读不出来，换一组试试。"
                                } else {
                                    session?.discard()
                                    session = ses
                                    previewSlot = ImportPlan.Slot.IDLE
                                    sizeDiffLevel = measureMaxDiff(ses)?.let { ModelBaseline.levelOf(it) }
                                    step = Step.PREVIEW
                                }
                            }
                        }, "dh-import-artist").start()
                    }
                }
                if (busy) {
                    ImportAssistantKit.BusyHint(busyText)
                }
                failure?.let { ImportAssistantKit.FailureBox(it) }
            }

            Step.REVIEW -> DhKit.Page(
                title = "识别完成",
                sub = "看看她带了哪些动作",
                onBack = { step = Step.CHOOSE }
            ) {
                if (s?.analysis == null) {
                    DhForm.Note("还没有识别结果，请先选择文件。")
                } else {
                    // 【必须先取一次局部引用】`session.analysis` 是可空可变属性，
                    //   Kotlin 不允许对它做智能转换（后台线程可能改写它）。
                    //   取成本地 val 之后这一段就稳定了，不会出现「判空通过但用时为空」。
                    val analysis = s.analysis ?: ImportPlan.Result(
                        bySlot = emptyMap(),
                        extras = emptyList(),
                        loose = emptyList(),
                        warnings = emptyList()
                    )
                    if (analysis.looksLikeTwoRoles()) {
                        ImportAssistantKit.FailureBox("这像两个角色，请分别导入。")
                    }
                    for (slot in ImportPlan.FIXED_SLOTS) {
                        val groups = analysis.bySlot[slot] ?: emptyList()
                        val chosen = s.groupOf(slot)
                        ImportAssistantKit.SlotRow(
                            label = ImportPlan.labelOf(slot),
                            frames = chosen?.files?.size ?: 0,
                            alternatives = groups,
                            chosenKey = chosen?.key
                        ) { key ->
                            s.chosen[slot] = key
                        }
                    }
                    if (analysis.extras.isNotEmpty()) {
                        DhKit.SectionLabel("额外动作（可选）")
                        for (g in analysis.extras) {
                            DhKit.CheckRow(
                                text = g.name + "（" + g.files.size + " 帧）",
                                selected = s.chosenExtras.isEmpty() || s.chosenExtras.contains(g.key)
                            ) {
                                if (s.chosenExtras.contains(g.key)) {
                                    s.chosenExtras.remove(g.key)
                                } else {
                                    s.chosenExtras.add(g.key)
                                }
                            }
                        }
                    }
                    DhForm.Note("缺少动作也能继续，先用待机代替。")
                }
                Spacer(Modifier.height(12.dp))
                DhKit.MiniChip("预览效果", iconRes = Icons.IC_EYE) {
                    step = Step.PREVIEW
                }
                failure?.let { ImportAssistantKit.FailureBox(it) }
            }

            Step.PREVIEW -> DhKit.Page(
                title = if (fromArtist) "看看效果" else "看看她住进来的样子",
                sub = "她会这样住在桌面上",
                onBack = {
                    // 返回修改：有歧义回识别页，否则回选择页（会话保留）。
                    step = if (s?.analysis?.ambiguousSlots()?.isNotEmpty() == true) {
                        Step.REVIEW
                    } else {
                        Step.CHOOSE
                    }
                }
            ) {
                if (s == null) {
                    DhForm.Note("还没有可预览的素材，请先选择文件。")
                } else {
                    val group = s.groupOf(previewSlot) ?: s.groupOf(ImportPlan.Slot.IDLE)
                    // 【R-065】预览必须可真实拖动；拖到边缘按 19.3 矩阵播贴入 → 贴边。
                    ImportAssistantKit.DragPreview(
                        files = group?.files ?: emptyList(),
                        sizePercent = s.sizeDraft,
                        offsetX = s.offsetX,
                        offsetY = s.offsetY,
                        edgeInFiles = s.groupOf(ImportPlan.Slot.EDGE_IN)?.files,
                        edgeLoopFiles = s.groupOf(ImportPlan.Slot.EDGE_LOOP)?.files,
                        onOffsetChange = { ox, oy ->
                            s.offsetX = DollSizeSpec.Spec.clampOffset(ox)
                            s.offsetY = DollSizeSpec.Spec.clampOffset(oy)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    ImportAssistantKit.SlotTabRow(
                        slots = ImportPlan.FIXED_SLOTS,
                        selected = previewSlot,
                        hasOf = { (s.groupOf(it)?.files?.isNotEmpty()) == true }
                    ) { slot ->
                        if ((s.groupOf(slot)?.files?.isNotEmpty()) == true) {
                            previewSlot = slot
                            failure = null
                        } else {
                            // R-055：点未设置的状态要明确告诉他「先用待机代替」。
                            failure = "还没有这个动作，导入后会用待机代替。"
                        }
                    }
                    DhForm.Gap()
                    DhForm.Input(
                        value = s.nameDraft,
                        onValueChange = { s.nameDraft = it },
                        label = "名字",
                        hint = "给她起个名字"
                    )
                    DhKit.SliderRow(
                        title = "大小",
                        hint = "当前 " + s.sizeDraft + "%",
                        max = DollSizeSpec.Spec.SIZE_MAX,
                        value = s.sizeDraft
                    ) { v, _ ->
                        s.sizeDraft = DollSizeSpec.Spec.clampSize(v)
                    }
                    DhKit.SliderRow(
                        title = "水平位置",
                        hint = ImportAssistantKit.offsetText(s.offsetX, true),
                        max = 100,
                        value = Math.round(s.offsetX * 100f).toInt() + 50
                    ) { v, _ ->
                        s.offsetX = DollSizeSpec.Spec.clampOffset((v - 50) / 100f)
                    }
                    DhKit.SliderRow(
                        title = "垂直位置",
                        hint = ImportAssistantKit.offsetText(s.offsetY, false),
                        max = 100,
                        value = Math.round(s.offsetY * 100f).toInt() + 50
                    ) { v, _ ->
                        s.offsetY = DollSizeSpec.Spec.clampOffset((v - 50) / 100f)
                    }
                    // ---------- 位置快捷档位（Q2 选 B：两滑条保留，加快捷按钮） ----------
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) {
                            DhKit.MiniChip("居中", iconRes = Icons.IC_CROSSHAIR) {
                                s.offsetX = 0f
                                s.offsetY = 0f
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            DhKit.MiniChip("底部", iconRes = Icons.IC_ALIGN_END_VERTICAL) {
                                s.offsetX = 0f
                                s.offsetY = DollSizeSpec.Spec.OFFSET_MAX
                            }
                        }
                    }

                    // ---------- R-074 尺寸不一致警告真正显示（此前只写不读） ----------
                    val warns = s.analysis?.warnings ?: emptyList()
                    if (warns.contains(ImportPlan.Warning.SIZE_MISMATCH)) {
                        DhForm.Note("尺寸不一致，可能出现跳动。")
                    }
                    if (warns.contains(ImportPlan.Warning.FRAME_COUNT_OUT_OF_RANGE)) {
                        DhForm.Note("有的动作帧数偏多，预览只放前面一部分；导入后会按上限保留。")
                    }
                    if (warns.contains(ImportPlan.Warning.LOOSE_AS_IDLE)) {
                        DhForm.Note("这些图没有标明状态，先当成待机。")
                    }
                    if (warns.contains(ImportPlan.Warning.MULTI_GROUP_IN_SLOT)) {
                        DhForm.Note("同一状态里认出了多组图片，请确认选择。")
                    }

                    // 【R-086 三档判定】<5% 不提示；5~10% 已统一；>10% 建议统一；量不出 → 手动对齐。
                    when (sizeDiffLevel) {
                        ModelBaseline.Level.AUTO_FIXED ->
                            DhForm.Note("已统一大小")
                        ModelBaseline.Level.REVIEW ->
                            DhForm.Note("建议统一大小")
                        null ->
                            DhForm.Note("这个动作的大小无法自动判断，请手动对齐。")
                        else -> { /* OK：不打扰 */ }
                    }

                    // ---------- R-087 / R-088 大小检查（折叠面板，不阻止继续） ----------
                    ImportAssistantKit.SizeCheckPanel(
                        session = s,
                        onFixAll = { sizeCheckNote = "已按待机统一大小" },
                        onAlignBottom = { sizeCheckNote = "已对齐脚底" },
                        onAlignTop = { sizeCheckNote = "已对齐头顶" },
                        onReset = { sizeCheckNote = "已恢复自动" }
                    )
                    sizeCheckNote?.let { DhForm.Note(it) }

                    val missing = ImportPlan.FIXED_SLOTS.filter {
                        (s.groupOf(it)?.files?.isNotEmpty()) != true
                    }
                    // 【E-04 同名角色】不能静默覆盖同名角色，也不能把两个角色混成一份素材（9.2.7）。
                    //   【为什么默认改名而不是弹「替换吗」】「替换」在只有素材、还没有角色的
                    //   新建场景里没有可靠语义：旧角色身上的人格 / 记忆 / 聊天关联都属于它自己，
                    //   拿新素材去顶掉它是破坏性操作（23.2.5 要求破坏性动作必须说清影响范围）。
                    //   所以这里走**安全的那一半**：自动加序号，并把这件事明确写出来 ——
                    //   用户既不会覆盖任何旧数据，也不会以为「自己建的其实是另一个」。
                    val sameName = DollStore.all(act).any { it.name == s.nameDraft.trim() }
                    if (sameName) {
                        DhForm.Note(
                            "角色库里已经有一个叫「" + s.nameDraft.trim() + "」的角色了。" +
                                "这次会另存为一个新角色（自动加序号），原来那个不会被动。"
                        )
                    }
                    // 【9.2.3 / TC-032】JPG 本身不带透明通道：必须明说背景会一起显示，
                    //   不能让人以为系统替他去了背。
                    if (hasOpaqueSource(s)) {
                        DhForm.Note(
                            "选的图里有 JPG，它没有透明背景，所以人物的背景色也会一起显示在桌面上。"
                        )
                    }
                    if (missing.isNotEmpty()) {
                        DhForm.Note(
                            "还缺" + missing.joinToString("、") { ImportPlan.labelOf(it) } +
                                "的图，先用待机代替。缺少动作也能继续。"
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DhKit.MiniChip(
                        text = if (busy) busyText else "就这样住进来",
                        iconRes = Icons.IC_CHECK
                    ) {
                        if (!busy) {
                            commitAsync()
                        }
                    }
                    if (busy) {
                        ImportAssistantKit.BusyHint(busyText)
                    }
                    failure?.let { ImportAssistantKit.FailureBox(it) }
                }
            }

            Step.DONE -> DhKit.Page(
                title = "她住进来了",
                onBack = { PageStack.pop(act) }
            ) {
                for (slot in ImportPlan.FIXED_SLOTS) {
                    ImportAssistantKit.StatusRow(
                        label = ImportAssistantKit.statusTextOf(slot, doneFilled.contains(slot)),
                        has = doneFilled.contains(slot)
                    )
                }
                if (doneReplaced) {
                    // 8.8：替换过旧模型时要明说旧模型还在。
                    DhForm.Note("已替换原来的模型，旧模型仍保留在角色库。")
                }
                DhForm.Note("以后想补动作，可以在人偶列表里找到她，进「动作」添加。")
                Spacer(Modifier.height(12.dp))
                DhKit.MiniChip("去桌面看看", iconRes = Icons.IC_EXTERNAL) {
                    val id = doneDollId
                    if (id != null) {
                        // 「去桌面看看」= 设她为唯一常驻（G-01 的互斥写入入口）。
                        DollStore.setActive(act, id)
                    }
                    HomeUi.notifyPetReload(act)
                    DollState.bump()
                    PageStack.pop(act)
                }
                // 【R-112 五.4】次按钮：不强制现在就把四个状态配齐。
                DhKit.MiniChip("以后再补动作", iconRes = Icons.IC_LAYERS) {
                    PageStack.pop(act)
                }
                DhForm.Note("也可以直接返回，她会留在角色库里。")
            }
        }

        // ---------- R-070-5 多张同尺寸 PNG：「这是一组待机动画吗」 ----------
        if (pendingAskIdle) {
            DhForm.Alert(
                title = "这是一组待机动画吗？",
                onDismiss = { pendingAskIdle = false },
                posText = "是",
                onPos = { pendingAskIdle = false },
                negText = "不是，让我选状态",
                onNeg = {
                    pendingAskIdle = false
                    step = Step.REVIEW
                }
            ) {
                DhForm.Note("选的是同一尺寸的连续图片的话，通常就是一组。")
            }
        }

        // ---------- R-112 十二.5 导入中断 ----------
        if (showInterrupted) {
            DhForm.Alert(
                title = "导入中断了",
                onDismiss = { showInterrupted = false },
                posText = "继续",
                onPos = { showInterrupted = false },
                negText = "重新选择",
                onNeg = {
                    showInterrupted = false
                    leaveForReal()
                }
            ) {
                DhForm.Note("已经保留你选的文件。点「继续」回到刚才那一步。")
            }
        }

        // ---------- 支持格式说明 ----------
        if (showFormats) {
            DhForm.Alert(
                title = "支持哪些格式",
                onDismiss = { showFormats = false },
                posText = "知道了",
                onPos = { showFormats = false }
            ) {
                DhForm.Note("图片：PNG、JPG、WebP。")
                DhForm.Note("动图：GIF、动态 WebP。")
                DhForm.Note("Lottie：Bodymovin 导出的 .json。")
                DhForm.Note("压缩包：.zip，按 idle / drag / edge_in / edge_loop 分文件夹即可识别。")
                DhForm.Note("JPG 没有透明背景，背景会一起显示在桌面上。")
            }
        }
    }

    /* ============================ 内部工具 ============================ */

    /** 打开文件选择器，把结果写回某一行的草稿。 */
    private fun pickInto(act: MainActivity, apply: (List<String>) -> Unit) {
        PickFileActivity.setFileListener { paths, failed ->
            if (paths.isEmpty()) {
                if (failed) {
                    HomeUi.showNotice(act, "读取失败", "没能读取选中的文件，请重试。")
                }
                return@setFileListener
            }
            apply(paths)
        }
        PickFileActivity.startFiles(act, true)
    }

    /** 从文件名单猜角色名（去扩展名，空则给默认名）。 */
    private fun nameFromFile(path: String?): String {
        if (path.isNullOrEmpty()) {
            return "新角色"
        }
        val stem = ImportPlan.stemOf(File(path).name)
        // 选文件通道把副本命名成 `raw_<ts>_<rand>.png`，这种名字对用户没意义。
        if (stem.startsWith("raw_") || stem.isBlank()) {
            return "新角色"
        }
        return if (stem.length > 24) stem.substring(0, 24) else stem
    }

    /**
     * 高手路径 → 会话。
     *
     * 【为什么不重复走一遍「选文件 → 展开」】三行的图已经在 cacheDir 里了，
     *   再复制一次纯属浪费。这里直接按「目录语义」把三行拼成相对路径喂给同一套识别，
     *   于是提交仍然只有 [ImportCommit] 一条路（R-060）。
     */
    private fun buildArtistSession(
        act: MainActivity,
        idle: List<String>,
        drag: List<String>,
        edge: List<String>,
        edgeIn: List<String>
    ): ImportSession? {
        if (idle.isEmpty()) {
            return null
        }
        val ses = ImportSession.create(act, ImportSession.Entry.NEW_DOLL, "")
        ses.picked = idle + drag + edgeIn + edge
        val bySlot = LinkedHashMap<ImportPlan.Slot, List<ImportPlan.Group>>()
        if (idle.isNotEmpty()) {
            bySlot[ImportPlan.Slot.IDLE] = listOf(
                ImportPlan.Group("idle", ImportPlan.Slot.IDLE, idle, "待机")
            )
        }
        if (drag.isNotEmpty()) {
            bySlot[ImportPlan.Slot.DRAG] = listOf(
                ImportPlan.Group("drag", ImportPlan.Slot.DRAG, drag, "拖拽")
            )
        }
        if (edgeIn.isNotEmpty()) {
            bySlot[ImportPlan.Slot.EDGE_IN] = listOf(
                ImportPlan.Group("edge_in", ImportPlan.Slot.EDGE_IN, edgeIn, "贴入瞬间")
            )
        }
        if (edge.isNotEmpty()) {
            bySlot[ImportPlan.Slot.EDGE_LOOP] = listOf(
                ImportPlan.Group("edge_loop", ImportPlan.Slot.EDGE_LOOP, edge, "贴边常驻")
            )
        }
        ses.analysis = ImportPlan.Result(
            bySlot = bySlot,
            extras = emptyList(),
            loose = emptyList(),
            warnings = emptyList()
        )
        ses.nameDraft = nameFromFile(idle.firstOrNull())
        return ses
    }

    /**
     * 【9.2.3 / TC-032】本次导入里是否会出现「不透明背景」。
     *
     * 判据只看**真正会被用到**的素材：
     *  - 每个槽位取用户已选定（[ImportSession.chosen]）的那一组，未选则取第一组；
     *  - 额外动作取 [ImportSession.chosenExtras] 里被勾上的；
     *  - 散图（loose）直接计入。
     *
     * 命中条件：其中有 JPG（`.jpg` / `.jpeg`）且该文件没有同名 / 同目录的带透明通道替代
     * （PNG / WebP）。有替代时用户实际用的是替代图，不该弹这条提示。
     *
     * 只做「提示」不做「处理」：系统不会替他抠背景，文案里也不得暗示已经去背。
     */
    private fun hasOpaqueSource(s: ImportSession): Boolean {
        val a = s.analysis ?: return false
        val files = ArrayList<String>()
        for (slot in a.bySlot.keys) {
            val groups = a.bySlot[slot] ?: continue
            if (groups.isEmpty()) continue
            val k = s.chosen[slot]
            val g = if (k == null) groups.first() else (groups.firstOrNull { it.key == k } ?: groups.first())
            files.addAll(g.files)
        }
        for (g in a.extras) {
            if (s.chosenExtras.isEmpty() || s.chosenExtras.contains(g.key)) files.addAll(g.files)
        }
        files.addAll(a.loose)
        if (files.isEmpty()) return false

        val alphaNames = files.filter { hasAlphaChannel(it) }
            .map { stripExt(it).substringAfterLast('/') }
            .toSet()
        return files.any { p ->
            isJpeg(p) && !alphaNames.contains(stripExt(p).substringAfterLast('/'))
        }
    }

    private fun stripExt(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) path.substring(0, path.length - (name.length - dot)) else path
    }

    private fun isJpeg(path: String): Boolean {
        val n = path.substringAfterLast('/').lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg")
    }

    private fun hasAlphaChannel(path: String): Boolean {
        val n = path.substringAfterLast('/').lowercase()
        return n.endsWith(".png") || n.endsWith(".webp")
    }
}