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
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollSizeSpec
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.IdleFallback
import com.dollhouse.app.data.ImportPlan
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.data.model.ModelImporter
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.theme.UiKit
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】**模型管理页**（v2.1·8.9）——一屏说清「这只角色现在长什么样」。
 *
 * ## 规格（8.9 原文）
 *
 * ```
 * 标题：模型
 * 当前模型
 * [预览图]
 * 名字：小肥鱼
 * 状态：待机 ✓  拖拽 ✓  贴边 ○
 * 大小：100%
 * 位置：已设置
 * 按钮：[更换模型] [调整位置]
 * ```
 *
 * ## 为什么与 `ui/settings/ModelListPage` 不是同一个东西
 *
 * 那个页面的「模型」是 **AI 供应商模型**（DeepSeek / GPT 之类，用于聊天）。
 * 本页的「模型」是**角色视觉素材**（四态动画帧）。两者同名不同物，绝不能合并 ——
 * 混在一页会让用户以为「换了模型她就连脑子都换了」。
 *
 * ## 状态行的口径（19.3）
 *
 * - 有内容 = 中性「已设置」，不是绿色成功徽章：这是**能力说明**，不是成就。
 * - 缺内容 = 中性灰 + 文字说明（`statusTextOf`），**不用红色**。19.3.7 明确：
 *   缺动作是正常降级状态，不是错误。
 * - 待机缺失时额外说明「现在用哪个动作代替」—— 19.3 补充规则 2 要求这件事对用户可见，
 *   否则用户会以为「她一直趴着」是 bug。
 */
object ModelManagePage {

    /** 页面 key 前缀：`doll:model:<dollId>`。 */
    const val PREFIX = "doll:model:"

    /** 三个固定状态在页面上的展示顺序（= 19.3 降级矩阵的列顺序）。 */
    private val SLOTS = listOf(
        ImportPlan.Slot.IDLE,
        ImportPlan.Slot.DRAG,
        ImportPlan.Slot.EDGE_LOOP
    )

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
        // 【为什么 remember(dollId) 且带一个 version】本页会「换模型」再返回，
        //   回来时必须看到新素材；用一个本地自增版本当 key 强制重算状态。
        var version by remember(dollId) { mutableStateOf(0) }
        var showSize by remember(dollId) { mutableStateOf(false) }
        // ---------- 模型包导入（R-112 七.5：整套模型入口挪到这里） ----------
        var pkgCheck by remember(dollId) { mutableStateOf<ModelImporter.Inspection?>(null) }
        var pkgZip by remember(dollId) { mutableStateOf<String?>(null) }
        var pkgMsg by remember(dollId) { mutableStateOf<String?>(null) }
        var pkgBusy by remember(dollId) { mutableStateOf(false) }
        val doll = remember(dollId, version) { DollStore.byId(act, dollId) }

        // 三态是否有内容（每次重组重读，代价只是一次 JSON 解析，页面静态可接受）。
        val hasOf = remember(dollId, version) {
            HashMap<ImportPlan.Slot, Boolean>().also { m ->
                for (s in SLOTS) {
                    m[s] = hasSlot(act, dollId, s)
                }
            }
        }
        val spec = remember(dollId, version) { DollSizeSpec.spec(act, dollId) }
        // 待机缺失时实际在播的那个动作名（19.3 补充规则 2：回退必须对用户可见）。
        val fallbackName = remember(dollId, version) {
            if (hasOf[ImportPlan.Slot.IDLE] == true) {
                null
            } else {
                val id = IdleFallback.resolve(act, dollId)
                val a = if (id == null) null else DollAnim.byId(act, dollId, id)
                a?.name
            }
        }
        // 预览用的帧：优先待机，没有就退回「当前回退动作」，都没有就空。
        val previewFrames = remember(dollId, version) {
            val idle = DollAnim.bySlot(act, dollId, DollAnim.GROUP_ALWAYS)
            if (idle != null && idle.frames.isNotEmpty()) {
                idle.frames
            } else {
                val id = IdleFallback.resolve(act, dollId)
                val a = if (id == null) null else DollAnim.byId(act, dollId, id)
                a?.frames ?: emptyList()
            }
        }

        DhKit.Page(
            title = "模型",
            sub = doll?.name ?: "这个角色",
            onBack = { PageStack.pop(act) }
        ) {
            DhKit.Card {
                DhKit.CardTitle("当前模型", iconRes = Icons.IC_IMAGE)
                if (previewFrames.isEmpty()) {
                    // 一张图都没有：不是错误，只说清现状 + 给出可做的动作。
                    DhForm.Note("还没有可以显示的图。下面选一个动作素材换上就能看到。")
                } else {
                    ImportAssistantKit.FramePreview(
                        files = previewFrames,
                        sizePercent = 100,
                        offsetX = 0f,
                        offsetY = 0f
                    )
                }
                Spacer(Modifier.height(8.dp))
                DhKit.ValueRow(
                    label = "名字",
                    value = doll?.name ?: "未命名"
                )
                // ---------- 状态：三态一行，中性口径 ----------
                DhKit.SectionLabel("状态")
                for (s in SLOTS) {
                    val has = hasOf[s] == true
                    ImportAssistantKit.StatusRow(
                        label = ImportAssistantKit.statusTextOf(s, has),
                        has = has
                    )
                }
                DhKit.MiniChip("设置贴边", iconRes = Icons.IC_PANEL_RIGHT) {
                    DollAnimPage.open(act, dollId, DollAnim.SLOT_EDGE_ID)
                }
                DhForm.Note("贴边循环必选；贴入瞬间可在同一页面选补。")
                if (fallbackName != null) {
                    // 中性说明，不是警告框：缺待机是正常降级。
                    DhForm.Note(
                        "现在没有专门的待机动作，会先用「" + fallbackName + "」代替。" +
                            "以后补上待机图就会自动换回来。"
                    )
                } else if (!hasOf[ImportPlan.Slot.IDLE]!!) {
                    DhForm.Note(
                        "现在没有待机动作，也没有可以代替的动作。" +
                            "她会保持原来的姿势，补一套图就能动起来。"
                    )
                }
                // ---------- 大小 / 位置 ----------
                DhKit.ValueRow(
                    label = "大小",
                    value = spec.size.toString() + "%"
                )
                DhKit.ValueRow(
                    label = "位置",
                    value = if (spec.isDefault()) "默认（居中）" else positionText(spec)
                )
            }

            // ---------- 两个动作（8.9 规定的按钮） ----------
            Spacer(Modifier.height(4.dp))
            DhKit.MiniChip("更换模型", iconRes = Icons.IC_REFRESH) {
                // 【19.2.3】换模型 = 在当前角色内替换视觉模型，不新建角色。
                ReplaceModelPage.open(act, dollId)
                version++
            }
            Spacer(Modifier.height(8.dp))
            DhKit.MiniChip(
                if (pkgBusy) "正在检查…" else "导入模型包",
                iconRes = Icons.IC_PACKAGE_CHECK
            ) {
                if (pkgBusy) {
                    return@MiniChip
                }
                PickFileActivity.setFileListener { paths, failed ->
                    if (paths.isEmpty()) {
                        if (failed) {
                            pkgMsg = "没能读取选中的文件，请重试。"
                        }
                        return@setFileListener
                    }
                    val zip = paths[0]
                    pkgBusy = true
                    pkgMsg = null
                    Thread({
                        val r = ModelImporter.inspect(act, zip, dollId)
                        act.runOnUiThread {
                            pkgBusy = false
                            pkgZip = zip
                            pkgCheck = r
                            if (!r.canInstall) {
                                pkgMsg = r.summary()
                            }
                        }
                    }, "dh-model-inspect").start()
                }
                PickFileActivity.startFiles(act, false)
            }
            pkgMsg?.let { msg ->
                Spacer(Modifier.height(6.dp))
                DhForm.Note(msg)
            }
            Spacer(Modifier.height(8.dp))
            DhKit.MiniChip("调整位置", iconRes = Icons.IC_MOVE) {
                showSize = true
            }
            Spacer(Modifier.height(6.dp))
            DhForm.Note(
                "换模型只换样子：她的人格、记忆和聊天记录都不会变，角色库里也不会多出人。"
            )
        }

        // ---------- 模型包：检查结果确认 ----------
        val chk = pkgCheck
        if (chk != null && chk.canInstall) {
            val m = chk.manifest
            DhForm.Alert(
                title = if (chk.code == ModelImporter.Code.DUPLICATE) "覆盖导入？" else "导入模型包",
                onDismiss = { pkgCheck = null; pkgZip = null },
                posText = "导入",
                onPos = {
                    pkgCheck = null
                    val zip = pkgZip
                    pkgZip = null
                    if (zip == null) {
                        return@Alert
                    }
                    Thread({
                        val r = ModelImporter.install(act, zip, dollId, chk)
                        act.runOnUiThread {
                            pkgMsg = r.message + if (r.warnings.isEmpty()) "" else
                                "（" + r.warnings.joinToString("；") + "）"
                            HomeUi.notifyPetReload(act)
                            DollState.bump()
                            version++
                        }
                    }, "dh-model-install").start()
                },
                negText = "取消"
            ) {
                Text(
                    text = "名称：" + (m?.name ?: "") + "\n" +
                        "标识：" + (m?.id ?: "") + "\n" +
                        "引擎：" + (m?.engine ?: "") + "\n" +
                        "规范版本：" + (m?.specVersion ?: 0) +
                        (if ((m?.author ?: "").isEmpty()) "" else "\n作者：" + m?.author) +
                        (if (chk.code == ModelImporter.Code.DUPLICATE)
                            "\n\n⚠ 已存在同标识的模型包，导入会覆盖它。" else ""),
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
                if (chk.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = chk.warnings.joinToString("\n"),
                        color = DhTokens.colors.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
                Spacer(Modifier.height(8.dp))
                DhForm.Note("只替换这个角色的模型。她的人格、记忆和聊天记录不会变。")
            }
        }

        if (showSize) {
            // 复用角色列表页的同一套滑条（同一实现，不复制第二份口径）。
            DollListScreen.SizeDialog(
                act = act,
                dollId = dollId,
                onDismiss = {
                    showSize = false
                    HomeUi.notifyPetReload(act)
                    version++
                }
            )
        }
    }

    /** 某固定状态当前是否有可用素材。 */
    private fun hasSlot(act: MainActivity, dollId: String, slot: ImportPlan.Slot): Boolean {
        val group = when (slot) {
            ImportPlan.Slot.IDLE -> DollAnim.GROUP_ALWAYS
            ImportPlan.Slot.DRAG -> DollAnim.GROUP_DRAG
            ImportPlan.Slot.EDGE_IN -> DollAnim.GROUP_EDGE_IN
            ImportPlan.Slot.EDGE_LOOP -> DollAnim.GROUP_EDGE
            else -> return false
        }
        val a = DollAnim.bySlot(act, dollId, group) ?: return false
        return a.frames.isNotEmpty() || a.assetPath.isNotEmpty()
    }

    /** 「位置」的人话。 */
    private fun positionText(spec: DollSizeSpec.Spec): String {
        val h = ImportAssistantKit.offsetText(spec.ox, true)
        val v = ImportAssistantKit.offsetText(spec.oy, false)
        return h + "、" + v
    }
}
