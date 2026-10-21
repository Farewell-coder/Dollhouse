package com.dollhouse.app.ui.doll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.AnimImport
import com.dollhouse.app.data.ActionWorkbench
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollSizeSpec
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 【职责】「资源导入」向导页 —— 把外部素材变成动作的**唯一正规入口**。
 *
 * ## 为什么要做成「向导」而不是点一下就导
 *
 * 用户选进来的东西可能是：一串帧图、一张精灵图集、一个 Lottie json、
 * 一个 zip、一个动图，甚至是一个改错扩展名的文件。这些在导入后**行为完全不同**
 * （帧序列 vs 引擎渲染、要不要写 baseline、有没有 fps）。一次性静默导入的结果是：
 * 用户不知道系统把他给的东西认成了什么，出了问题也说不清哪一步错了。
 *
 * 所以这里强制走四步，每一步都把「系统认成了什么」摊开给他看：
 * ```
 * ① 选文件      → 只拿到路径，什么都不写
 * ② 识别与校验  → 逐项显示「认成什么 / 能不能导 / 为什么不能」（[AnimImport.inspect]）
 * ③ 选导入方式  → 新建动作 / 作为变体 / 替换现有动作
 * ④ 确认提交    → 这一步才真正写盘；取消则磁盘上零痕迹
 * ```
 *
 * ## 「取消后原资源完整」是怎么保证的
 *
 * 不是靠「失败回滚」（回滚总漏副作用：半截文件、临时目录），
 * 而是**前三步全程只读**。只有第 ④ 步调 [AnimImport.import]，
 * 它自己用临时目录 + 成功后才落正式位置（见该方法的实现）。
 */
object DollImportWizardPage {

    /** 页面 key 前缀：`doll:import:<dollId>`。 */
    const val PREFIX = "doll:import:"

    /** 导入方式：新建一个动作 / 作为变体（复制某个动作再覆盖帧）/ 替换某个动作的帧。 */
    private const val MODE_NEW = "new"
    private const val MODE_VARIANT = "variant"
    private const val MODE_REPLACE = "replace"

    @JvmStatic
    fun open(act: MainActivity, dollId: String) {
        PageStack.push(act, PREFIX + dollId) { buildPage(act, dollId) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity, dollId: String): android.view.View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, dollId) }
    }

    @Composable
    private fun Content(act: MainActivity, dollId: String) {
        val c = DhTokens.colors
        // ---------- 向导状态 ----------
        // 已选文件（PickFileActivity 拷进 cacheDir 的稳定副本路径）。
        var picked by remember { mutableStateOf<List<String>>(emptyList()) }
        // 检查结果（null = 还没选文件 / 正在检查）。
        var checks by remember { mutableStateOf<List<AnimImport.Inspection>?>(null) }
        var checking by remember { mutableStateOf(false) }
        // 导入方式 + 目标动作（变体 / 替换要选一个现存动作）。
        var mode by remember { mutableStateOf(MODE_NEW) }
        var targetId by remember { mutableStateOf<String?>(null) }
        var nameDraft by remember { mutableStateOf("") }
        // 提交中（防重复点）。
        var submitting by remember { mutableStateOf(false) }
        // 预览：第一张能认出的图片。
        var preview by remember { mutableStateOf<ImageBitmap?>(null) }

        // 分组选择：提到组件顶层，才能传给 doImport（B4 修复）。
        var groupDraft by remember { mutableStateOf(DollAnim.GROUP_MANUAL) }
        val actions = remember(DollState.rev) { DollAnim.all(act, dollId) }
        // 默认目标：第一个手动组动作（替换 / 变体最常打的是它）。
        LaunchedEffect(actions) {
            if (targetId == null && actions.isNotEmpty()) {
                var hit = actions[0].id
                for (a in actions) {
                    if (a.group == DollAnim.GROUP_MANUAL) {
                        hit = a.id
                        break
                    }
                }
                targetId = hit
            }
        }
        // 预览解码：只解第一张「认出是图片」的文件。
        LaunchedEffect(checks) {
            preview = null
            val list = checks ?: return@LaunchedEffect
            for (it in list) {
                if (!it.canImport) {
                    continue
                }
                if (it.kind == "image" || it.kind == "anim" || it.kind == "spritesheet") {
                    val b = withContext(Dispatchers.IO) {
                        com.dollhouse.app.core.ImageDecode.file(
                            it.path, reqWidth = 320, reqHeight = 320
                        )
                    }
                    if (b != null) {
                        preview = b.asImageBitmap()
                        break
                    }
                }
            }
        }

        val okCount = remember(checks) {
            var n = 0
            val list = checks
            if (list != null) {
                for (it in list) {
                    if (it.canImport) {
                        n++
                    }
                }
            }
            n
        }
        val badCount = remember(checks) {
            val list = checks
            if (list == null) 0 else list.size - okCount
        }

        DhKit.Page(
            title = "导入动作资源",
            sub = "选文件 → 检查 → 选方式 → 确认",
            onBack = { PageStack.pop(act) }
        ) {
            // ============ ① 选文件 ============
            DhKit.SectionLabel("① 选择文件", iconRes = Icons.IC_FOLDER_OPEN)
            DhForm.Note(
                "支持：帧图序列（可多选）、精灵图集（png + json/plist/atlas）、" +
                    "Lottie 动画（.json）、动图（gif / webp / apng）、压缩包（zip）。" +
                    "扩展名被改错也没关系，按文件内容识别。"
            )
            Spacer(Modifier.height(8.dp))
            DhKit.MiniChip("选择文件", iconRes = Icons.IC_PLUS) {
                com.dollhouse.app.PickFileActivity.setFileListener { paths, failed ->
                    if (paths.isEmpty()) {
                        if (failed) {
                            HomeUi.showNotice(act, "读取失败", "没能读取选中的文件，请重试。")
                        }
                        return@setFileListener
                    }
                    picked = paths
                    checks = null
                    checking = true
                    // 名字默认取第一个文件的文件名（去掉扩展名），用户可以改。
                    val first = File(paths[0]).name
                    val dot = first.lastIndexOf('.')
                    nameDraft = if (dot > 0) first.substring(0, dot) else first
                    Thread({
                        val r = AnimImport.inspect(paths)
                        act.runOnUiThread {
                            checks = r
                            checking = false
                        }
                    }, "dh-import-inspect").start()
                }
                com.dollhouse.app.PickFileActivity.startFiles(act, true)
            }

            // ============ ② 识别与校验 ============
            DhKit.SectionLabel("② 识别与校验", iconRes = Icons.IC_SHIELD_CHECK)
            if (checking) {
                Text(
                    text = "正在检查 " + picked.size + " 个文件…",
                    modifier = Modifier.padding(top = 6.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            val list = checks
            if (list != null) {
                if (okCount > 0) {
                    Text(
                        text = "可用 " + okCount + " 个" + (if (badCount > 0) "，不可用 " + badCount + " 个" else ""),
                        modifier = Modifier.padding(top = 6.dp),
                        color = c.title,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold
                    )
                }
                for (it in list) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                            .background(c.soft)
                            .padding(DhKit.PAD_ROW.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DhKit.Icon(
                            if (it.canImport) Icons.IC_CHECK_CIRCLE else Icons.IC_CIRCLE_ALERT,
                            Design.SZ_SM,
                            if (it.canImport) c.acc else c.err
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = it.fileName,
                                color = c.title,
                                fontSize = UiKit.FS_SUB.sp,
                                fontFamily = DhTokens.fontsBold,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = it.kindName + " · " + humanSize(it.sizeBytes) +
                                    (if (it.reason.isEmpty()) "" else " · " + it.reason),
                                color = if (it.reason.isEmpty()) c.sub else c.err,
                                fontSize = UiKit.FS_TINY.sp,
                                fontFamily = DhTokens.fonts,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (okCount == 0) {
                    DhForm.Note(
                        "这些文件都用不了，换个文件试试。常见原因：不是图片/动画、" +
                            "文件下载不完整、或者是只有矢量形状的 Lottie。"
                    )
                }
            }

            // ============ 预览 ============
            val bmp = preview
            if (bmp != null) {
                DhKit.SectionLabel("预览", iconRes = Icons.IC_IMAGE)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                        .background(c.soft)
                        .border(1.dp, c.line, RoundedCornerShape(DhKit.R_CARD.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                DhForm.Note("只显示第一张可用图的缩略。实际播放按导入后的帧序列走。")
            }

            // ============ ③ 导入方式 ============
            if (list != null && okCount > 0) {
                DhKit.SectionLabel("③ 导入方式", iconRes = Icons.IC_LAYERS)
                DhKit.CheckRow(
                    text = "新建一个动作（推荐）",
                    selected = mode == MODE_NEW
                ) { mode = MODE_NEW }
                DhKit.CheckRow(
                    text = "作为变体（复制选中的动作，再换成这批帧）",
                    selected = mode == MODE_VARIANT
                ) { mode = MODE_VARIANT }
                DhKit.CheckRow(
                    text = "替换选中动作的帧（原帧会被删掉）",
                    selected = mode == MODE_REPLACE
                ) { mode = MODE_REPLACE }

                when (mode) {
                    MODE_NEW -> {
                        DhForm.Gap()
                        DhKit.SectionLabel("放到哪一组")
                        for (g in DollAnim.SWAPPABLE_GROUPS) {
                            DhKit.CheckRow(text = g, selected = groupDraft == g) { groupDraft = g }
                        }
                        DhForm.Input(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            label = "动作名称",
                            hint = "例如：挥手、转圈"
                        )
                    }
                    MODE_VARIANT, MODE_REPLACE -> {
                        DhForm.Gap()
                        DhKit.SectionLabel("选一个动作")
                        if (actions.isEmpty()) {
                            DhForm.Note("这只人偶还没有动作可选，请改用「新建」。")
                        }
                        for (a in actions) {
                            DhKit.CheckRow(
                                text = a.name + "（" + a.group + "，" + a.frames.size + " 帧）",
                                selected = targetId == a.id
                            ) { targetId = a.id }
                        }
                        DhForm.Note(
                            if (mode == MODE_REPLACE)
                                "替换会把这个动作原来的帧全部删掉，换成新导入的这批。" +
                                    "名字 / 速度 / 显示设置保持不变。"
                            else
                                "变体会复制这个动作（含速度 / 显示设置 / 触发绑定），" +
                                    "然后把帧换成新导入的这批。原动作不受影响。"
                        )
                    }
                }

                // ============ ④ 确认 ============
                Spacer(Modifier.height(12.dp))
                DhKit.MiniChip(
                    text = if (submitting) "正在导入…" else "确认导入",
                    iconRes = Icons.IC_CHECK
                ) {
                    if (submitting) {
                        return@MiniChip
                    }
                    if (list.isEmpty()) {
                        return@MiniChip
                    }
                    if (mode != MODE_NEW && targetId == null) {
                        HomeUi.showNotice(act, "还没选动作", "请先选一个要变体/替换的动作。")
                        return@MiniChip
                    }
                    submitting = true
                    Thread({
                        val r = doImport(act, dollId, picked, mode, targetId, nameDraft, groupDraft)
                        act.runOnUiThread {
                            submitting = false
                            if (!r.first) {
                                HomeUi.showNotice(act, "导入失败", r.second)
                                return@runOnUiThread
                            }
                            HomeUi.notifyPetReload(act)
                            DollState.bump()
                            HomeUi.showNotice(act, "导入完成", r.second)
                            // 成功才清空向导状态：失败时保留用户的选择，让他能重试。
                            picked = emptyList()
                            checks = null
                            preview = null
                        }
                    }, "dh-import-commit").start()
                }
                DhForm.Note(
                    "点「确认导入」之前，磁盘上什么都没写。取消或中途退出不会动到任何现有资源。"
                )
            }
        }
    }

    /**
     * 真正落盘（第 ④ 步）。
     *
     * ## 「取消 / 失败后原资源完整」是怎么保证的
     *
     * **绝不直接往动作的正式帧目录里写。** 先导到同级的一个临时目录
     * （`<actionId>__incoming_<时间戳>`），全部成功后才搬进正式目录并更新档案；
     * 任何一步失败就只删临时目录 —— 原动作的帧文件一个字节都不会被动到。
     *
     * 【为什么不能用「同名覆盖」了事】`AnimImport.import` 落到磁盘的文件名是
     *   `i0000.png / i0001.png …`，与旧帧**同名**。替换模式下直接写正式目录，
     *   等于边导边覆盖旧帧：中途失败（磁盘满 / 用户杀进程 / 解码异常）之后，
     *   动作档案里还记着 N 个帧路径，而盘上的文件已被覆盖成半截或已删掉 ——
     *   用户看到的是「这个动作废了」，且源素材若没备份就永久丢失。
     *
     * @param group 新建时落到哪一组（变体 / 替换沿用目标动作自己的组，此参数忽略）
     * @return `Pair(成功, 给用户看的结果文案)`
     */
    private fun doImport(
        act: MainActivity,
        dollId: String,
        paths: List<String>,
        mode: String,
        targetId: String?,
        name: String,
        group: String
    ): Pair<Boolean, String> {
        if (paths.isEmpty()) {
            return Pair(false, "没有选中的文件。")
        }
        // ---------- 先确定帧要落到哪个动作 id 下 ----------
        //
        // 【为什么要先定 id 再导】`AnimImport.import` 需要一个目标目录，
        //   而目录名必须就是动作 id（[DollAnim.framesDir]）。所以：
        //   新建 → 先建壳拿 id；变体 → 先复制拿 id；替换 → 用现有 id。
        var actionId = targetId
        var createdNew = false
        when (mode) {
            MODE_NEW -> {
                // 【B4 修复】用调用方传进来的 group，而不是硬写 GROUP_MANUAL。
                //   非法的 group 由 DollAnim.add 自己收敛（它只认 SWAPPABLE_GROUPS）。
                val a = DollAnim.add(
                    act, dollId, name.ifBlank { "导入的动作" }, "",
                    emptyList(), DollAnim.FPS_DEFAULT, true, group
                )
                if (a == null) {
                    return Pair(false, "没能创建动作，请重试。")
                }
                actionId = a.id
                createdNew = true
            }
            MODE_VARIANT -> {
                if (actionId == null) {
                    return Pair(false, "没有指定要变体的动作。")
                }
                val copy = com.dollhouse.app.data.ActionLibrary.duplicate(act, dollId, actionId)
                if (copy == null) {
                    return Pair(false, "没能复制出变体，请重试。")
                }
                actionId = copy.id
                createdNew = true
            }
            MODE_REPLACE -> {
                if (actionId == null) {
                    return Pair(false, "没有指定要替换的动作。")
                }
            }
        }
        val id = actionId ?: return Pair(false, "内部错误：没有动作 id。")
        val officialDir = DollAnim.framesDir(act, dollId, id)

        // ---------- 【B3 修复】先导进临时目录，绝不碰正式目录 ----------
        //
        // 临时目录放在正式目录的**同级**（同一个 `anim/<dollId>/` 下），
        // 这样最后一步「把文件搬过去」是同一次挂载内的 rename，快且原子。
        val incoming = File(officialDir.parentFile, id + "__incoming_" + System.currentTimeMillis())
        val r = try {
            AnimImport.import(act, paths, incoming)
        } catch (t: Throwable) {
            // 导入过程本身抛异常：清掉临时目录，正式目录完好。
            deleteTreeQuietly(incoming)
            cleanupPicked(paths)
            if (createdNew) {
                DollAnim.remove(act, dollId, id)
            }
            return Pair(false, "导入过程出错：" + (t.message ?: t.javaClass.simpleName))
        }
        // 中间副本用完即清（PickFileActivity 拷进 cacheDir 的那份）。
        cleanupPicked(paths)

        if (!r.ok || r.frames.isEmpty()) {
            // 【失败要收拾干净】临时目录 + 新建的空壳 / 变体副本都要撤掉，
            //   否则动作列表里会多一个「0 帧的空壳」，用户以为导入成功了。
            deleteTreeQuietly(incoming)
            if (createdNew) {
                DollAnim.remove(act, dollId, id)
            }
            return Pair(false, r.note.ifEmpty { "没读出任何可用的帧。" })
        }

        // ---------- 提交：临时目录 → 正式目录 ----------
        val cur = DollAnim.byId(act, dollId, id)
        if (cur == null) {
            deleteTreeQuietly(incoming)
            return Pair(false, "动作在读回时不见了，请重试。")
        }
        val oldFrames = ArrayList(cur.frames)
        // ① 把新帧搬进正式目录（同名覆盖是安全的：此刻提交已注定成功，不会再回滚）。
        val frameCount = r.frames.size
        val moved = ArrayList<String>(frameCount)
        for (src in r.frames) {
            val f = File(src)
            if (!f.isFile) {
                continue
            }
            val dst = File(officialDir, f.name)
            try {
                f.renameTo(dst)
                moved.add(dst.absolutePath)
            } catch (t: Throwable) {
                // rename 失败（跨挂载 / 同名已存在且被占用）→ 退回拷贝。
                try {
                    f.copyTo(dst, overwrite = true)
                    moved.add(dst.absolutePath)
                } catch (t2: Throwable) {
                    // 单张失败不中断：剩下的照搬，最后按实际帧数如实汇报。
                }
            }
        }
        deleteTreeQuietly(incoming)
        if (moved.isEmpty()) {
            // 一张都没搬成功：不能把动作改成 0 帧（那等于把用户的动作废掉）。
            return Pair(false, "帧文件落盘失败，动作保持原样。")
        }

        // ② 更新档案
        val bl = com.dollhouse.app.data.FrameScope.computeBaseline(moved)
        val u = cur.copy(
            frames = moved,
            fps = DollAnim.clampFps(r.fps),
            baseline = bl?.encode() ?: ""
        )
        DollAnim.update(act, dollId, u)

        // ②.5 【尺寸标准化·自动适配】把这批素材的「角色占画布高度的比例」换算成
        //   这只人偶的显示大小。
        //
        // 【为什么落在这里】需求书第四节第 5 点：「自动计算初始缩放比例和内容边界」，
        //   而导入向导是**唯一**把外部素材变成人偶画面的入口（新建模板导入、
        //   追加帧都走它）。放在这里，用户导入完就能看到合理的大小，不必自己去拖滑条。
        //
        // 【只在「用户还没手动调过」时写入】判据是规格仍为默认值。
        //   否则用户精心调好的大小，会因为多导一组帧就被自动改回去 —— 那是倒退。
        //
        // 【算不出就什么都不做】普通 JPG 照片没有透明边界，[computeBaseline] 返回 null，
        //   此时保持原规格（100%），让用户自己调 —— 需求书明确要求不要伪造识别结果。
        if (bl != null && DollSizeSpec.spec(act, dollId).isDefault()) {
            val k = DollSizeSpec.fillFactor(bl)
            if (k != null) {
                // k = 1/占比。相邻两组素材占比差得多时，用它把视觉大小拉回同一档。
                // 注意 size 是百分比且夹在 [30, 400]，直接乘 100 再交给 Spec 收敛。
                val size = Math.round(k * 100f)
                DollSizeSpec.setSpec(act, dollId, DollSizeSpec.Spec(
                    DollSizeSpec.Spec.clampSize(size.toInt()),
                    0f,
                    0f
                ))
            }
        }

        // ③ 清掉「不再被引用」的旧帧文件。
        //    【为什么按交集判断而不是整目录删】正式目录里可能还留着本动作
        //    上一批的帧（我们刚才是同名覆盖 + 可能多出几张），
        //    只删「旧档案里记着、新档案里没有」的那些，避免误删同名新文件。
        for (p in oldFrames) {
            if (moved.contains(p)) {
                continue
            }
            try {
                File(p).delete()
            } catch (ignored: Throwable) {
            }
        }
        val tail = if (bl == null) "（认不出稳定的角色轮廓，已按整张画布显示）" else ""
        val shortNote = if (moved.size < frameCount) {
            "（有 " + (frameCount - moved.size) + " 帧落盘失败，已跳过）"
        } else {
            ""
        }
        return Pair(
            true,
            "「" + u.name + "」已导入 " + moved.size + " 帧，" + u.fps + " 帧/秒。" +
                r.note + shortNote + tail
        )
    }

    /** 清掉选文件时产生的中间副本（PickFileActivity 放在 cacheDir 的那份）。 */
    private fun cleanupPicked(paths: List<String>) {
        for (p in paths) {
            try {
                File(p).delete()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 递归删目录/文件，失败静默（清理路径不该再抛）。 */
    private fun deleteTreeQuietly(d: File) {
        try {
            if (d.isDirectory) {
                val kids = d.listFiles()
                if (kids != null) {
                    for (k in kids) {
                        deleteTreeQuietly(k)
                    }
                }
            }
            d.delete()
        } catch (ignored: Throwable) {
        }
    }

    /** 字节数 → 人读的大小（只用于展示，精度不追求）。 */
    private fun humanSize(b: Long): String {
        if (b <= 0L) {
            return "0 B"
        }
        if (b < 1024L) {
            return b.toString() + " B"
        }
        val kb = b / 1024.0
        if (kb < 1024.0) {
            return String.format("%.0f KB", kb)
        }
        return String.format("%.1f MB", kb / 1024.0)
    }
}