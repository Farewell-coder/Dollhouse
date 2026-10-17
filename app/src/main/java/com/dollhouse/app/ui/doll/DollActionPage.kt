package com.dollhouse.app.ui.doll

import android.graphics.BitmapFactory
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.model.ModelImporter
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 【职责】「动作」清单页：这只人偶的**全部动画动作**，每一项都能点进去看真动画。
 *
 * 【r13 大改】上一版这一页只是「把动作名字列出来」，用户明说不行：
 *   「同时为我动作界面的每个操作填充动画 …… 点开之后 能通过滑动的方式看到每一帧的图片
 *     并且能编辑 添加 修改和调速」。
 *
 * 【动画从哪来】本工程的人偶是网格形变实时渲染（`buildMesh` + `drawBitmapMesh`），
 *   身上没有帧图素材。内置动画由 [com.dollhouse.app.pet.AnimBaker] 用**同一套渲染管线**
 *   逐帧烘成 PNG：参数曲线照搬 PetAnimator / BlinkTimeline 的真实系数，所以
 *   「预览里的每一帧」就是「桌面上真实跑的那一帧」。
 *
 * 【用户自定义】用户也能把自己那一组连贯动作图导进来，命名 + 写描述 + 调速，
 *   与内置动作在同一个列表里管理（内置项不可删，自定义项可删可改）。
 */
object DollActionPage {
    /** 页面 key 前缀：`doll:action:<dollId>`。 */
    const val PREFIX = "doll:action:"

    @JvmStatic
    fun open(act: MainActivity, id: String) {
        PageStack.push(act, PREFIX + id) { buildPage(act, id) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity, id: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, id) }
    }

    @Composable
    private fun Content(act: MainActivity, id: String) {
        val doll = DollStore.byId(act, id)
        // 【按 rev 重算】本页 View 常驻页面栈；从动作详情页返回后本层不会重建。
        //   改名 / 改帧 / 删除都要立刻看到，故用全局变更信号 + 本地 tick 双保险。
        var tick by remember { mutableStateOf(0) }
        val rev = DollState.rev
        // 【r13·属性分组从数据来】grouped() 按 Action.group 聚合，界面不再硬编码任何分组表 ——
        //   新增动作自动归位，属性改错了改数据即可，不用动界面。
        val groups = remember(rev, tick, id) { DollAnim.grouped(act, id) }
        var newOpen by remember { mutableStateOf(false) }
        // 【r15·补删除入口】待确认删除的动作 id（长按列表项设置）。
        var delId by remember { mutableStateOf<String?>(null) }
        // 【r16·模型包导入】已选中的 zip 与它的检查结果（检查通过后弹确认）。
        var pkgZip by remember { mutableStateOf<String?>(null) }
        var pkgCheck by remember { mutableStateOf<ModelImporter.Inspection?>(null) }
        var pkgMsg by remember { mutableStateOf<String?>(null) }

        DhKit.Page(
            title = "动作",
            sub = if (doll == null) null else doll.name,
            onBack = { PageStack.pop(act) },
            actions = {
                DhKit.IconButton(Icons.IC_PLUS, Design.SZ_MD, DhTokens.colors.title, label = "新建") {
                    newOpen = true
                }
            }
        ) {
            val total = remember(groups) {
                var n = 0
                for (g in groups) {
                    n += g.second.size
                }
                n
            }
            DhForm.Note(
                "这只人偶一共有 " + total + " 个动作，按属性分好了组。点任意一项可以看它的每一帧、" +
                    "改名字、改描述、调速；右上角「+」可以新建动作（自己导入一组图，或先建空壳再加帧）。"
            )
            if (total == 0) {
                DhForm.Note("还没有任何动作。点右上角「+」开始添加。")
            }
            for (g in groups) {
                DhKit.SectionLabel(g.first + "（" + g.second.size + "）")
                for (a in g.second) {
                    ActionCard(
                        action = a,
                        onOpen = {
                            DollAnimPage.open(act, id, a.id)
                            tick++
                        },
                        // 【r15·补删除入口】用户反馈「很多地方缺少删除按钮」——
                        //   动作列表原先只能进详情页才能删，列表本身没有任何删除入口。
                        //   这里走长按（与聊天会话列表同款手势），避免每行多一个按钮把列表挤乱；
                        //   长按弹确认，确认后才真删（动作里可能有几十帧，误删代价高）。
                        onDelete = { delId = a.id }
                    )
                }
            }
        }

        if (newOpen) {
            NewActionSheet(
                act = act,
                dollId = id,
                onDismiss = { newOpen = false },
                onDone = {
                    newOpen = false
                    tick++
                },
                onImportModel = {
                    // 【r16·模型包入口】先收起新建弹窗，再走「选 zip → 检查 → 确认 → 安装」。
                    //   【为什么分两步走】`PickFileActivity` 是独立 Activity，
                    //   弹窗留在上层会挡住文件选择器的结果回调。
                    newOpen = false
                    com.dollhouse.app.PickFileActivity.setFileListener { paths, failed ->
                        if (paths.isEmpty()) {
                            if (failed) {
                                pkgMsg = "没能读取选中的文件，请重试。"
                            }
                            return@setFileListener
                        }
                        val zip = paths[0]
                        Thread({
                            val r = ModelImporter.inspect(act, zip, id)
                            act.runOnUiThread {
                                pkgZip = zip
                                pkgCheck = r
                                if (!r.canInstall) {
                                    // 【为什么失败也要弹】用户需要知道「为什么不能用」；
                                    //   只提示「导入失败」会逼用户来问我们。
                                    pkgMsg = r.summary()
                                }
                            }
                        }, "dh-model-inspect").start()
                    }
                    com.dollhouse.app.PickFileActivity.startFiles(act, false)
                }
            )
        }

        // ---------- r16·模型包：检查结果确认 ----------
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
                        val r = ModelImporter.install(act, zip, id, chk)
                        act.runOnUiThread {
                            pkgMsg = r.message + if (r.warnings.isEmpty()) "" else
                                "（" + r.warnings.joinToString("；") + "）"
                            HomeUi.notifyPetReload(act)
                            DollState.bump()
                            tick++
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
            }
        }

        // ---------- r16·模型包：失败提示 ----------
        val msg = pkgMsg
        if (msg != null) {
            DhForm.Alert(
                title = "模型包",
                onDismiss = { pkgMsg = null },
                posText = "知道了",
                onPos = { pkgMsg = null }
            ) {
                Text(
                    text = msg,
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }

        // 【r15·补删除入口】长按动作 → 确认弹窗 → 真删。
        //   内置动作不给删（删了列表里会空一块，且它们是 App 自带能力），
        //   这里与详情页口径保持一致：内置的弹提示、不执行删除。
        val delTarget = delId
        if (delTarget != null) {
            val target = remember(delTarget, rev) {
                // 【为什么用 byId】DollAnim.load 是 private（模块内部用），对外只暴露 byId。
                DollAnim.byId(act, id, delTarget)
            }
            val builtin = target != null && target.builtin
            DhForm.Alert(
                title = if (builtin) "不能删除" else "删除动作",
                onDismiss = { delId = null },
                posText = if (builtin) "知道了" else "删除",
                onPos = {
                    if (!builtin) {
                        DollAnim.remove(act, id, delTarget)
                        HomeUi.notifyPetReload(act)
                        DollState.bump()
                        tick++
                    }
                    delId = null
                }
            ) {
                Text(
                    text = if (builtin) {
                        "「" + (target?.name ?: "") + "」是内置动作，不能删除。"
                    } else {
                        "确定删除「" + (target?.name ?: "") + "」吗？它下面的所有帧都会被一起删掉。"
                    },
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /**
     * 【r13】新建动作：两条路 —— 自己导入一组图（走多选通道），或先建空壳再加帧。
     *
     * 【为什么保留「先建空壳」】用户要的是「保留逐张修改的功能」：
     *   先有个名字的容器、再逐张加/删/排序，比一次性选完所有图更可控。
     *   两条路都走 [DollAnim.add]，帧为空**不再**被当成错误（上一版会弹「创建失败」）。
     */
    @Composable
    private fun NewActionSheet(
        act: MainActivity,
        dollId: String,
        onDismiss: () -> Unit,
        onDone: () -> Unit,
        onImportModel: () -> Unit = {}
    ) {
        var draft by remember { mutableStateOf("") }
        // 【r16·四入口】任务书 §3.8 要求添加菜单有四个入口：
        //   建空壳｜图片/序列帧｜Lottie｜模型包。
        //   【为什么用「单选一」再按底部按钮】四个入口各有一个必须交代的说明
        //   （比如「Lottie 需要 .json」），写成一列可见的选项比四个按钮更清楚。
        var mode by remember { mutableStateOf(0) }
        val modes = arrayOf(
            "只建空壳（稍后逐张加帧）",
            "导入图片 / 序列帧",
            "导入 Lottie 动画（.json）",
            "导入模型包（.zip）"
        )
        val modeHints = arrayOf(
            "先有个容器，进详情页后可以一张一张添加、删除、调顺序。",
            "可以多选：一串帧图、动图、精灵图集、压缩包都能识别并自动排序。",
            "Lottie 的 .json 会被存成「动作的入口资源」，直接由 Lottie 引擎播放。",
            "按模型包规范 v1 打包的 .zip，含清单、预览图与资源；会先做安全检查再导入。"
        )
        DhForm.Alert(
            title = "新建动作",
            onDismiss = onDismiss,
            posText = if (mode == 3) "选择文件" else "创建",
            onPos = {
                if (mode == 3) {
                    onImportModel()
                    return@Alert
                }
                val name = draft.trim()
                val a = DollAnim.add(
                    act, dollId, name.ifBlank { "新动作" }, "",
                    emptyList(), DollAnim.FPS_DEFAULT, true
                )
                if (a == null) {
                    HomeUi.showNotice(act, "创建失败", "没能保存这个动作，请重试。")
                } else {
                    onDone()
                    // 直接进详情页，让用户马上加帧（少一次往返）。
                    DollAnimPage.open(act, dollId, a.id)
                }
            },
            negText = "取消"
        ) {
            DhKit.SectionLabel("来源")
            for (i in modes.indices) {
                DhKit.CheckRow(text = modes[i], selected = mode == i) { mode = i }
            }
            DhForm.Note(modeHints[mode])
            DhForm.Gap()
            DhForm.Input(
                value = draft,
                onValueChange = { draft = it },
                label = "名称",
                hint = "例如：挥手、转圈"
            )
            // 【文案与行为必须一致】这四条说明分别对应下面 onPos 里真实会发生的事：
            //   模式 0/1/2 都是「建库项 → 进详情页」，区别只在进页后该干什么；
            //   模式 3 走的是另一条路（选 zip → 检查 → 安装），由 onImportModel 接管。
            //   写超前（比如「会立刻打开文件选择器」而实际没打开）比不写更糟 ——
            //   用户会以为功能坏了。
            when (mode) {
                0 -> DhForm.Note("创建后进入详情页：可以一次导入整组图，也可以逐张添加、删除、调顺序。")
                1 -> DhForm.Note("创建后进入详情页，点「添加帧」导入那组图片（支持多选、自动排序）。")
                2 -> DhForm.Note("创建后进入详情页，在「资源管理」里指定入口 .json 文件。")
                else -> DhForm.Note(
                    "会打开文件选择器，选一个按模型包规范 v1 打包的 .zip；" +
                        "确认前会先做安全检查，不会直接写入。"
                )
            }
        }
    }

    /** 一条动作：左侧缩略图（取第一帧）+ 名称 + 描述 + 帧数/速度。 */
    @Composable
    private fun ActionCard(action: DollAnim.Action, onOpen: () -> Unit, onDelete: () -> Unit) {
        val c = DhTokens.colors
        // 缩略图：取第一帧。内置动作在进详情页前还没烘，这里显示语义图标占位。
        val first = action.frames.firstOrNull()
        var thumb by remember(first) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(first) {
            if (first == null) {
                return@LaunchedEffect
            }
            thumb = withContext(Dispatchers.IO) { decodeSmall(first) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DhKit.GAP_ROW.dp)
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .background(c.soft)
                // 【r15·补删除入口】改带长按重载：短按进详情、长按弹删除确认。
                .pressable(onOpen, onDelete)
                .padding(horizontal = DhKit.PAD_ROW.dp, vertical = DhKit.PAD_ROW.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.cardUp)
                    .border(1.dp, c.line, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                val b = thumb
                if (b != null) {
                    Image(
                        bitmap = b,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().padding(2.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    DhKit.Icon(
                        if (action.builtin) Icons.IC_SPARKLES else Icons.IC_IMAGE,
                        Design.SZ_SM, c.sub
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.name,
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (action.desc.isEmpty()) "（没有描述）" else action.desc,
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = (if (action.isDefault) "默认 · " else "") +
                        (if (action.builtin) "内置 · " else "自定义 · ") +
                        (if (action.frames.isEmpty() && action.assetPath.isEmpty()) "待生成"
                        else if (action.frames.isEmpty()) mapEngineLabel(action.engine)
                        else action.frames.size.toString() + " 帧") +
                        " · " + action.fps + " 帧/秒" +
                        (if (action.loop) " · 循环" else ""),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            DhKit.Icon(Icons.IC_CHEVRON_RIGHT, Design.SZ_SM, c.sub)
        }
    }

    /**
     * 新建一个空动作（帧稍后在详情页里加）。
     *
     * 【r13 已废弃】本函数被 [NewActionSheet] 取代（后者是 Compose 弹窗，与全 App 弹窗同族）。
     *   保留一个空的私有占位会让编译器报「未使用」，故整段删除。
     */

    /** 非帧序列引擎在列表里的显示名（避免写死字符串在多处）。 */
    private fun mapEngineLabel(engine: String): String = when (engine) {
        DollAnim.ENGINE_LOTTIE -> "Lottie"
        DollAnim.ENGINE_ANIMIMAGE -> "动图"
        else -> "待生成"
    }

    /** 缩略图解码：只取 1/4 尺寸，避免列表滚动时把内存吃满。 */
    private fun decodeSmall(path: String): ImageBitmap? {
        return try {
            if (!File(path).exists()) {
                return null
            }
            val opt = BitmapFactory.Options()
            opt.inSampleSize = 4
            val b = BitmapFactory.decodeFile(path, opt)
            if (b == null) null else b.asImageBitmap()
        } catch (t: Throwable) {
            null
        }
    }
}