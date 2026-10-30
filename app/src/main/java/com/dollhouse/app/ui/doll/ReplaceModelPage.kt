package com.dollhouse.app.ui.doll

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.ImportCommit
import com.dollhouse.app.data.ImportPlan
import com.dollhouse.app.data.ImportSession
import com.dollhouse.app.data.ImportSource
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Icons
import java.io.File

/**
 * 【职责】「更换模型」——在**当前角色内**替换视觉模型，**不新建角色**。
 *
 * ## 为什么必须与「添加角色」分开（v2.1·19.2.3 / 19.2.5 / 19.2.7）
 *
 * 这三个入口的用户意图完全不同，混用会造成不可逆的数据后果：
 *
 * | 入口 | 作用域 | 会新建角色吗 | 人格/记忆 |
 * |---|---|---|---|
 * | 添加角色 | 角色库 | **会** | 新角色从空白开始 |
 * | 设为常驻 | 桌面显示 | 不会 | 完全不动 |
 * | 更换模型 | 当前这一个角色 | **不会** | 必须保留 |
 *
 * 改成「添加角色」再设为常驻，会留下一个多余的空角色，并且原角色的人格 / 记忆
 * 与聊天关联都留在旧 id 上 —— 用户看到的是「人换了个样子，但性格和记忆没了」。
 *
 * ## 事务与回滚（19.2.5 / 21.2）
 *
 * 复用 [ImportCommit] 的同一条链路：旧帧目录先改名备份、新帧写好并校验、
 * 最后才写索引；任何一步失败都恢复旧模型。**角色 id 与人格数据全程不变**。
 *
 * ## 文案纪律（19.2.7）
 *
 * 确认页必须写清「影响的是当前角色的模型」，并且明确「不会新建角色」「人格和记忆不会变」。
 */
object ReplaceModelPage {

    /** 页面 key 前缀：`doll:swap:`（`<dollId>`）。 */
    const val PREFIX = "doll:swap:"

    private enum class Step { PICK, PREVIEW, DONE }

    @JvmStatic
    fun open(act: MainActivity, dollId: String) {
        PageStack.push(act, PREFIX + dollId) {
            ComposeHost.createView(act) { Content(act, dollId) }
        }
    }

    @JvmStatic
    fun buildPage(act: MainActivity, dollId: String): android.view.View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, dollId) }
    }

    @Composable
    private fun Content(act: MainActivity, dollId: String) {
        val doll = remember(dollId) { DollStore.byId(act, dollId) }
        var step by remember { mutableStateOf(Step.PICK) }
        var session by remember { mutableStateOf<ImportSession?>(null) }
        var busy by remember { mutableStateOf(false) }
        var busyText by remember { mutableStateOf("") }
        var failure by remember { mutableStateOf<String?>(null) }
        var previewSlot by remember { mutableStateOf(ImportPlan.Slot.IDLE) }
        var confirm by remember { mutableStateOf(false) }
        var doneFilled by remember { mutableStateOf<List<ImportPlan.Slot>>(emptyList()) }

        fun analyzeAsync(picked: List<String>) {
            busy = true
            busyText = "正在识别…"
            failure = null
            Thread({
                val s = ImportSession.create(act, ImportSession.Entry.ANIMATION, dollId)
                s.picked = picked
                // 【关键】名字沿用当前角色名 —— 更换模型不改变角色身份（19.2.5）。
                s.nameDraft = doll?.name ?: "角色"
                val expanded = ImportSource.expand(act, picked, File(s.root, "expand"))
                if (!expanded.ok) {
                    val msg = expanded.error?.let { ImportSource.textOf(it) }
                        ?: "这个文件里没有找到能用的角色图。"
                    ImportSession.deleteTree(s.root)
                    act.runOnUiThread {
                        busy = false
                        failure = msg
                    }
                    return@Thread
                }
                val analysis = ImportPlan.analyze(expanded.items.map { it.rel })
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
                        extras = emptyList(),
                        loose = emptyList(),
                        warnings = analysis.warnings
                    )
                }
                s.analysis = effective
                act.runOnUiThread {
                    busy = false
                    session?.discard()
                    session = s
                    previewSlot = ImportPlan.Slot.IDLE
                    step = Step.PREVIEW
                }
            }, "dh-swap-analyze").start()
        }

        fun commitAsync() {
            val s = session
            if (s == null || s.committing) {
                return
            }
            s.committing = true
            busy = true
            busyText = "正在替换…"
            Thread({
                val r = try {
                    ImportCommit.commit(act, s)
                } catch (t: Throwable) {
                    ImportCommit.Result(false, "替换没有完成，原来的样子没变。请重试。")
                }
                act.runOnUiThread {
                    busy = false
                    s.committing = false
                    confirm = false
                    if (!r.ok) {
                        failure = r.message
                        return@runOnUiThread
                    }
                    doneFilled = r.filled
                    HomeUi.notifyPetReload(act)
                    DollState.bump()
                    session = null
                    step = Step.DONE
                }
            }, "dh-swap-commit").start()
        }

        fun leave() {
            session?.discard()
            session = null
            PageStack.pop(act)
        }

        val s = session
        when (step) {
            Step.PICK -> DhKit.Page(
                title = "更换模型",
                sub = (doll?.name ?: "这个角色") + " 的新样子",
                onBack = { leave() }
            ) {
                DhForm.Note(
                    "这里换的是「" + (doll?.name ?: "这个角色") + "」的样子本身：" +
                        "她的人格、记忆和聊天记录都留着不动，也不会在角色库里多出一个人。"
                )
                DhForm.Gap()
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
                if (busy) {
                    ImportAssistantKit.BusyHint(busyText)
                }
                failure?.let { ImportAssistantKit.FailureBox(it) }
            }

            Step.PREVIEW -> DhKit.Page(
                title = "预览新样子",
                sub = "确认之前，原来的样子一点都没动",
                onBack = { step = Step.PICK }
            ) {
                if (s == null) {
                    DhForm.Note("还没有可预览的素材，请先选择文件。")
                } else {
                    val group = s.groupOf(previewSlot) ?: s.groupOf(ImportPlan.Slot.IDLE)
                    ImportAssistantKit.FramePreview(
                        files = group?.files ?: emptyList(),
                        sizePercent = 100,
                        offsetX = 0f,
                        offsetY = 0f
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
                            failure = "还没有这个动作，先用待机代替。"
                        }
                    }
                    val missing = ImportPlan.FIXED_SLOTS.filter {
                        (s.groupOf(it)?.files?.isNotEmpty()) != true
                    }
                    if (missing.isNotEmpty()) {
                        DhForm.Note(
                            "还缺" + missing.joinToString("、") { ImportPlan.labelOf(it) } +
                                "的图，先用待机代替。缺少动作也能继续。"
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DhKit.MiniChip(
                        text = if (busy) busyText else "用这个新样子",
                        iconRes = Icons.IC_REFRESH
                    ) {
                        if (!busy) {
                            confirm = true
                        }
                    }
                    if (busy) {
                        ImportAssistantKit.BusyHint(busyText)
                    }
                    failure?.let { ImportAssistantKit.FailureBox(it) }
                }
            }

            Step.DONE -> DhKit.Page(
                title = "换好了",
                onBack = { PageStack.pop(act) }
            ) {
                for (slot in ImportPlan.FIXED_SLOTS) {
                    ImportAssistantKit.StatusRow(
                        label = ImportAssistantKit.statusTextOf(slot, doneFilled.contains(slot)),
                        has = doneFilled.contains(slot)
                    )
                }
                DhForm.Note("她的人格、记忆和聊天记录都没变，角色库里也没有多出人。")
                Spacer(Modifier.height(12.dp))
                DhKit.MiniChip("回角色列表", iconRes = Icons.IC_CHECK) {
                    PageStack.pop(act)
                }
            }
        }

        // ---------- 替换确认（19.2.7：必须说清影响范围）----------
        if (confirm) {
            DhForm.Alert(
                title = "替换模型？",
                onDismiss = { confirm = false },
                posText = "替换",
                onPos = { commitAsync() },
                negText = "再看看"
            ) {
                DhForm.Note(
                    "这次会替换「" + (doll?.name ?: "这个角色") + "」的模型，" +
                        "不会新建角色，也不会改动她的人格和记忆。"
                )
                DhForm.Note("替换失败会自动保留原来的样子，不会只换一半。")
            }
        }
    }
}