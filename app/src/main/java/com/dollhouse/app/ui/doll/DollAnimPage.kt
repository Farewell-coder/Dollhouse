package com.dollhouse.app.ui.doll

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.model.ModelExporter
import com.dollhouse.app.data.model.ModelRepository
import com.dollhouse.app.data.DollProfile
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 【职责】单个动作的详情页：**播放器 + 帧序列编辑器**。
 *
 * 【需求原文】「点开之后 能通过滑动的方式看到每一帧的图片 并且能编辑 添加 修改和调速」
 *   本页把这三件事拆成三块：
 *   ① 顶部播放器 —— 按 fps 自动播；可暂停、可单帧步进；
 *   ② 帧序列条 —— 横向 `LazyRow`，「滑动就能看到每一帧」；点一帧即跳到那一帧；
 *   ③ 下方设置 —— 改名、改描述、调速、循环开关、加帧、删帧、试演。
 *
 * 【为什么单帧也能编辑】用户导入的是一组**连贯动作图**，偶尔会有一两张不对
 *   （顺序反了 / 混进无关图）。这里给「删除当前帧」，让用户不必重导整组。
 */
object DollAnimPage {
    const val PREFIX = "doll:anim:"

    /** 【C2】按键式精调每次的步长（归一化：0.005 = 预览框宽高的 0.5%）。 */
    private const val FINE_STEP = 0.005f

    /** 播放区帧图采样目标边长（px）。播放控件通常 ≤600px，留一倍余量取 1200。 */
    private const val PLAY_TARGET_PX = 1200

    /** 缩略图采样目标边长（px）。列表项 ~96px，留 2 倍余量取 192。 */
    private const val THUMB_TARGET_PX = 192

    /** 归一化偏移 → 百分比文案（只用于回显）。 */
    private fun pct(v: Float): String {
        val p = Math.round(v * 1000f)
        return (if (p >= 0) "+" else "") + p / 10f + "%"
    }

    @JvmStatic
    fun open(act: MainActivity, dollId: String, actionId: String) {
        PageStack.push(act, PREFIX + dollId + "|" + actionId) { buildPage(act, dollId, actionId) }
    }

    /**
     * 按 key 重建。key = `<dollId>|<actionId>`。
     *
     * 【为什么用 `|` 分隔而不是 `:`】dollId 与 actionId 都是纯字母数字，
     *   理论上用 `:` 也行；但 `|` 在 key 里绝不会与别的前缀撞车，可读性也更好。
     */
    @JvmStatic
    fun buildPage(act: MainActivity, key: String): View {
        val i = key.indexOf('|')
        val dollId = if (i < 0) key else key.substring(0, i)
        val actionId = if (i < 0) "" else key.substring(i + 1)
        return buildPage(act, dollId, actionId)
    }

    @JvmStatic
    fun buildPage(act: MainActivity, dollId: String, actionId: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, dollId, actionId) }
    }

    @Composable
    private fun Content(act: MainActivity, dollId: String, actionId: String) {
        val c = DhTokens.colors

        // 动作元数据：改一处就 reload 一次。
        var action by remember { mutableStateOf(DollAnim.byId(act, dollId, actionId)) }
        // 帧图路径（内置动作第一次进来要现烘）。
        var frames by remember { mutableStateOf<List<String>>(emptyList()) }
        var baking by remember { mutableStateOf(false) }
        var frameIdx by remember { mutableStateOf(0) }
        // 解码缓存：路径 → Bitmap（避免每帧都重新解码）。
        var cache by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
        var playing by remember { mutableStateOf(true) }

        // 改名 / 改描述用弹窗
        var renameOpen by remember { mutableStateOf(false) }
        var renameDraft by remember { mutableStateOf("") }
        var descOpen by remember { mutableStateOf(false) }
        var descDraft by remember { mutableStateOf("") }
        var speedOpen by remember { mutableStateOf(false) }
        // 【r13】属性选择弹窗开关。
        var groupOpen by remember { mutableStateOf(false) }
        // 【r16】资源管理弹窗开关（非帧序列引擎的入口资源）。
        var resOpen by remember { mutableStateOf(false) }
        // 【r16】导出模型包的结果提示。
        var exportMsg by remember { mutableStateOf<String?>(null) }

        // ---------- 准备帧图 ----------
        LaunchedEffect(actionId) {
            val a = action ?: return@LaunchedEffect
            if (a.frames.size >= 2) {
                frames = a.frames
                return@LaunchedEffect
            }
            // 内置动作：帧图还没烘，现烘一次（后台线程）。
            baking = true
            val scale = com.dollhouse.app.data.PetPrefs.petScale(act) / 100.0f
            val out = withContext(Dispatchers.IO) {
                com.dollhouse.app.pet.AnimBaker.ensure(act, dollId, actionId, scale)
            }
            baking = false
            if (out.size >= 2) {
                // 【回写】烘好的路径登记进动作，下次进页直接读，不再重烘。
                val updated = a.copy(frames = out)
                DollAnim.update(act, dollId, updated)
                action = updated
                frames = out
            }
        }

        // ---------- 解码当前帧（按需） ----------
        val curPath = if (frameIdx in frames.indices) frames[frameIdx] else ""
        LaunchedEffect(curPath) {
            if (curPath.isEmpty() || cache.containsKey(curPath)) {
                return@LaunchedEffect
            }
            // 【只解码当前帧】一屏只需要一帧完整位图；缩略图另走 decodeSmall。
            val bmp = withContext(Dispatchers.IO) { decodeFull(curPath) }
            if (bmp != null) {
                cache = cache + (curPath to bmp.asImageBitmap())
            }
        }

        // ---------- 自动播放 ----------
        LaunchedEffect(playing, frames, action?.fps) {
            val a = action ?: return@LaunchedEffect
            if (!playing || frames.size < 2) {
                return@LaunchedEffect
            }
            val step = DollAnim.frameMs(a.fps)
            while (true) {
                kotlinx.coroutines.delay(step)
                if (!playing) {
                    break
                }
                val next = frameIdx + 1
                frameIdx = if (next >= frames.size) {
                    if (a.loop) 0 else frames.size - 1
                } else {
                    next
                }
            }
        }

        // ---------- 页面 ----------
        DhKit.Page(
            title = action?.name ?: "动作",
            sub = "共 " + frames.size + " 帧",
            onBack = { PageStack.pop(act) },
            scrollable = false,
            actions = {
                if (action != null && !action!!.builtin) {
                    // 【r15·图标+文字】顶栏空间宽裕 → 加文字（删除是危险操作，明确写出来更安全）。
                    DhKit.IconButton(Icons.IC_TRASH, Design.SZ_MD, c.err, label = "删除动作") {
                        DollAnim.remove(act, dollId, actionId)
                        PageStack.pop(act)
                    }
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // ============ ① 播放器（含中心点位 + 双通道微调） ============
                // 【C1/C2 定案】「在上部的预览图中间放一个中心点位，这玩意是控制木偶的绝对中心，
                //   方便以后添加模型的时候对齐」＋「以后添加的模型图片默认居中的是这个中心点位」。
                //   ⇒ 预览框正中画一个十字点位；帧图**默认**按自身中心对准它（偏移 0）。
                //     若用户导入的图内容偏心，可用下面两种方式微调：
                //     ① 在预览框里直接拖（粗调）；② 方向键按钮（精微调）。
                //   偏移按人偶存一份（DollProfile.frameOffset）。
                var offTick by remember(dollId) { mutableStateOf(0) }
                val off = remember(dollId, offTick) { DollProfile.frameOffset(act, dollId) }
                // 【为什么另存一个 Int 版】offset{} 的 lambda 作用域里拿不到 Box 的 size
                //   （它不是 BoxWithConstraints 的 scope），得先在组合期量出预览框尺寸。
                var previewW by remember { mutableStateOf(1f) }
                var previewH by remember { mutableStateOf(1f) }
                val oxPx = off[0]
                val oyPx = off[1]
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.1f)
                        .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                        .background(c.soft)
                        .border(1.dp, c.line, RoundedCornerShape(DhKit.R_CARD.dp))
                        // 【先量尺寸】偏移是归一化的，转成像素要知道预览框宽高。
                        .onSizeChanged { sz ->
                            previewW = Math.max(1f, sz.width.toFloat())
                            previewH = Math.max(1f, sz.height.toFloat())
                        }
                        // 【拖动即改偏移】用 detectDragGestures 直接累加归一化位移。
                        .pointerInput(dollId) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                if (w <= 0f || h <= 0f) {
                                    return@detectDragGestures
                                }
                                val cur = DollProfile.frameOffset(act, dollId)
                                DollProfile.setFrameOffset(
                                    act, dollId,
                                    cur[0] + drag.x / w, cur[1] + drag.y / h
                                )
                                offTick++
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = cache[curPath]
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                // 【默认居中于中心点位】偏移为 0 时即正中；有偏移时按归一化比例挪。
                                .offset { IntOffset((oxPx * previewW).roundToInt(), (oyPx * previewH).roundToInt()) },
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = if (baking) "正在生成帧…" else if (frames.isEmpty()) "还没有帧" else "加载中…",
                            color = c.sub,
                            fontSize = UiKit.FS_SUB.sp,
                            fontFamily = DhTokens.fonts
                        )
                    }
                    if (baking) {
                        Text(
                            text = "首次打开会把这只人偶的动画逐帧渲染出来，稍等片刻。",
                            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                            color = c.sub,
                            fontSize = UiKit.FS_TINY.sp,
                            fontFamily = DhTokens.fonts
                        )
                    }
                    // 【C1·中心点位】画在**所有帧之上**，保证任何帧都盖不住它 ——
                    //   它是「控制木偶的绝对中心」的视觉基准，必须始终可见。
                    //   十字 + 中心小圆：比单点更容易在花哨的插画上分辨。
                    CenterMarker(c.acc)
                }

                // 【C2·按键式微调】用户要求「手动的微调可以滑动 但也要加入按键式 方便精微调控」。
                //   滑动是粗调（上面那个拖手势），这里是精调：每次挪 0.5% 预览框宽高。
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    DhKit.IconButton(Icons.IC_CHEVRON_LEFT, Design.SZ_SM, c.title) {
                        DollProfile.setFrameOffset(act, dollId, off[0] - FINE_STEP, off[1])
                        offTick++
                    }
                    Spacer(Modifier.width(4.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_UP, Design.SZ_SM, c.title) {
                        DollProfile.setFrameOffset(act, dollId, off[0], off[1] - FINE_STEP)
                        offTick++
                    }
                    Spacer(Modifier.width(4.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_DOWN, Design.SZ_SM, c.title) {
                        DollProfile.setFrameOffset(act, dollId, off[0], off[1] + FINE_STEP)
                        offTick++
                    }
                    Spacer(Modifier.width(4.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_RIGHT, Design.SZ_SM, c.title) {
                        DollProfile.setFrameOffset(act, dollId, off[0] + FINE_STEP, off[1])
                        offTick++
                    }
                    Spacer(Modifier.width(10.dp))
                    DhKit.OutlineChip("居中", iconRes = Icons.IC_CROSSHAIR) {
                        DollProfile.setFrameOffset(act, dollId, 0f, 0f)
                        offTick++
                    }
                }
                Text(
                    text = "微调：拖动预览可粗调，方向键精调；「居中」把偏移清零。" +
                        "当前偏移 " + pct(off[0]) + " / " + pct(off[1]),
                    modifier = Modifier.padding(top = 2.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )

                // 播放控制行
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    DhKit.IconButton(Icons.IC_ARROW_LEFT, Design.SZ_MD, c.title) {
                        if (frames.isNotEmpty()) {
                            frameIdx = if (frameIdx <= 0) frames.size - 1 else frameIdx - 1
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    // 【r16·图标+文字】播放条空间宽裕 → 给出「播放 / 暂停」文字。
                    DhKit.IconButton(
                        if (playing) Icons.IC_SQUARE else Icons.IC_PLAY,
                        Design.SZ_MD, c.acc,
                        label = if (playing) "暂停" else "播放"
                    ) { playing = !playing }
                    Spacer(Modifier.width(6.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_RIGHT, Design.SZ_MD, c.title) {
                        if (frames.isNotEmpty()) {
                            frameIdx = (frameIdx + 1) % frames.size
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = if (frames.isEmpty()) "-- / --"
                        else (frameIdx + 1).toString() + " / " + frames.size,
                        color = c.sub,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold
                    )
                }

                // ============ ② 帧序列条（横向滑动看每一帧） ============
                DhKit.SectionLabel("帧序列（左右滑动查看）", iconRes = Icons.IC_IMAGE)
                if (frames.isEmpty()) {
                    DhForm.Note("这个动作还没有帧。点下面「添加帧」从相册选图。")
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (i in frames.indices) {
                            FrameThumb(
                                path = frames[i],
                                index = i,
                                selected = i == frameIdx,
                                onClick = {
                                    playing = false
                                    frameIdx = i
                                }
                            )
                        }
                    }
                }

                // ============ ③ 设置区 ============
                DhKit.SectionLabel("设置", iconRes = Icons.IC_SETTINGS)

                DhKit.ValueRow(
                    label = "名称",
                    iconRes = Icons.IC_SIGNATURE,
                    value = (action?.name ?: "") + " ›"
                ) {
                    renameDraft = action?.name ?: ""
                    renameOpen = true
                }
                DhKit.ValueRow(
                    label = "描述",
                    iconRes = Icons.IC_QUOTE,
                    value = if ((action?.desc ?: "").isEmpty()) "未填写 ›" else "已填写 ›"
                ) {
                    descDraft = action?.desc ?: ""
                    descOpen = true
                }
                DhKit.ValueRow(
                    label = "播放速度",
                    iconRes = Icons.IC_GAUGE,
                    value = (action?.fps ?: DollAnim.FPS_DEFAULT).toString() + " 帧/秒 ›"
                ) { speedOpen = true }
                // 【r13·属性归属】用户要求「认真辨利每个动作属于哪个属性」。
                //   属性是数据里的一等字段，这里让用户也能改 —— 导入的动作默认进「自定义」，
                //   想归到别的分组（比如把自绘的「挥手」归进「交互动作」）随时可调。
                DhKit.ValueRow(
                    label = "属性",
                    iconRes = Icons.IC_TAGS,
                    value = (action?.group ?: DollAnim.GROUP_CUSTOM) + " ›"
                ) { groupOpen = true }

                // ---------- r16·默认动作 ----------
                //
                // 【这是「桌宠能实际用上动作」的用户入口】设了默认动作的人偶，
                //   无交互时桌面上的小人就会播它（见 PetView.playSemantic → DollAnim.bySemantic）。
                //   没有这个开关，用户导入的动作只能在编辑页里看看，桌面上永远不生效 ——
                //   正是任务书 §1.4 说的「只做框架不接线 = 失败」。
                DhKit.SwitchRow(
                    name = "设为待机默认动作",
                    checked = action?.isDefault ?: false
                ) { on ->
                    val a = action ?: return@SwitchRow
                    // 【为什么传 null 表示取消】见 DollAnim.setDefault 的注释：
                    //   同人偶内互斥由数据层保证，界面不需要先遍历一遍去清别人。
                    DollAnim.setDefault(act, dollId, if (on) a.id else null)
                    HomeUi.notifyPetReload(act)
                    DollState.bump()
                    action = DollAnim.byId(act, dollId, actionId)
                }
                if (action?.isDefault == true) {
                    DhForm.Note("桌面上的小人无交互时会一直播这个动作。同一只人偶只能有一个默认动作。")
                }

                // ---------- r16·资源管理 ----------
                //
                // 【为什么必须有】帧序列动作的「资源」就是那一串帧图（在「帧操作」里管）；
                //   但 Lottie / 动图的资源是**一个入口文件**，用户换图时得能换、能看见路径、
                //   也能发现「文件没了」。任务书 §3.8 F 项明确要求这块。
                val cur = action
                if (cur != null && cur.engine != DollAnim.ENGINE_SPRITE) {
                    DhKit.SectionLabel("资源管理", iconRes = Icons.IC_CABLE)
                    val exists = cur.assetPath.isNotEmpty() && File(cur.assetPath).exists()
                    DhKit.ValueRow(
                        label = "入口资源",
                        iconRes = Icons.IC_CABLE,
                        value = if (cur.assetPath.isEmpty()) "未指定 ›" else
                            (if (exists) "已就绪 ›" else "文件缺失 ›")
                    ) { resOpen = true }
                    DhKit.ValueRow(
                        label = "来源模型包",
                        iconRes = Icons.IC_PACKAGE,
                        value = if (cur.sourceId.isEmpty()) "非模型包 ›" else cur.sourceId + " ›"
                    ) {}
                    if (cur.sourceId.isNotEmpty()) {
                        DhKit.MiniChip("卸载该模型包", iconRes = Icons.IC_TRASH) {
                            uninstallModel(act, dollId, cur.sourceId) {
                                action = DollAnim.byId(act, dollId, actionId)
                                frames = action?.frames ?: emptyList()
                                DollState.bump()
                            }
                        }
                    }
                }

                DhKit.SwitchRow(
                    name = "循环播放",
                    checked = action?.loop ?: true
                ) { on ->
                    val a = action ?: return@SwitchRow
                    val u = a.copy(loop = on)
                    DollAnim.update(act, dollId, u)
                    action = u
                }

                DhKit.SectionLabel("帧操作", iconRes = Icons.IC_BLOCKS)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        DhKit.MiniChip("添加帧", iconRes = Icons.IC_IMAGE_PLUS) { pickFrames(act, dollId, actionId) { onFramesChanged ->
                            action = DollAnim.byId(act, dollId, actionId)
                            frames = onFramesChanged
                            frameIdx = 0
                        } }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        DhKit.MiniChip("删除当前帧", iconRes = Icons.IC_TRASH) {
                            if (frames.size <= 1) {
                                HomeUi.showNotice(act, "不能删", "至少保留一帧。")
                            } else {
                                val list = ArrayList(frames)
                                val gone = list.removeAt(frameIdx)
                                try {
                                    File(gone).delete()
                                } catch (t: Throwable) {
                                }
                                val a = action
                                if (a != null) {
                                    val u = a.copy(frames = list)
                                    DollAnim.update(act, dollId, u)
                                    action = u
                                    frames = list
                                    frameIdx = Math.max(0, Math.min(frameIdx, list.size - 1))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        DhKit.MiniChip("前移一帧", iconRes = Icons.IC_CHEVRON_LEFT) { moveFrame(act, dollId, action, frames, frameIdx, -1) { a2, f2, i2 ->
                            action = a2
                            frames = f2
                            frameIdx = i2
                        } }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        DhKit.MiniChip("后移一帧", iconRes = Icons.IC_CHEVRON_RIGHT) { moveFrame(act, dollId, action, frames, frameIdx, 1) { a2, f2, i2 ->
                            action = a2
                            frames = f2
                            frameIdx = i2
                        } }
                    }
                }

                DhForm.Note(
                    if (action?.builtin == true) {
                        "这是工程内置动作（帧图由真实渲染管线生成）。可以改名、改描述、调速、删帧；" +
                            "删掉的动作下次进页会重新生成。"
                    } else {
                        "这是你导入的动作。帧的顺序就是播放顺序，改完即时保存。"
                    }
                )
            }
        }

        // ---------- r16·资源管理弹窗 ----------
        if (resOpen) {
            val a = action
            DhForm.Alert(
                title = "资源管理",
                onDismiss = { resOpen = false },
                posText = "重新指定",
                onPos = {
                    resOpen = false
                    // 【复用既有文件选择通道】替换入口资源 = 换一个 .json / .gif。
                    //   不做「格式校验后再放行」——格式问题在播的时候由渲染器
                    //   给出可读原因（LottieRenderer.lastError），比在这里猜更准。
                    com.dollhouse.app.PickFileActivity.setFileListener { paths, _ ->
                        if (paths.isEmpty()) {
                            return@setFileListener
                        }
                        val src = File(paths[0])
                        val dstDir = DollAnim.framesDir(act, dollId, actionId)
                        val dst = File(dstDir, src.name)
                        try {
                            src.copyTo(dst, overwrite = true)
                        } catch (t: Throwable) {
                        }
                        val old = DollAnim.byId(act, dollId, actionId) ?: return@setFileListener
                        val u = old.copy(assetPath = dst.absolutePath)
                        DollAnim.update(act, dollId, u)
                        action = u
                        // 【回到主线程】文件回调发生在 PickFileActivity 的流程里，
                        //   直接改 Compose 状态会触发「不在主线程」检查。
                        act.runOnUiThread { HomeUi.notifyPetReload(act) }
                    }
                    com.dollhouse.app.PickFileActivity.startFiles(act, false)
                },
                negText = "关闭"
            ) {
                val cur = a ?: return@Alert
                Text(
                    text = "引擎：" + cur.engine + "\n" +
                        "路径：" + cur.assetPath.ifEmpty { "（未指定）" } + "\n" +
                        "状态：" + if (cur.assetPath.isNotEmpty() && File(cur.assetPath).exists())
                            "文件存在" else "文件不存在或未指定",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
                DhForm.Gap()
                // 【为什么把「导出模型包」放在这里】导出的是「这只人偶 + 它的动作」，
                //   而用户此刻正在看某个动作 —— 顺势给一个「把它连同这个动作分享出去」的入口，
                //   比在别处单开一页更贴近他要做的事（任务书要求导出粒度就是单个人偶）。
                DhForm.PrimaryChip("导出这只人偶为模型包") {
                    val r = ModelExporter.exportDoll(
                        act, dollId,
                        DollStore.byId(act, dollId)?.name ?: dollId,
                        ModelExporter.suggestOutDir()
                    )
                    exportMsg = r.message + if (r.ok) "\n" + r.zipPath else ""
                    resOpen = false
                }
            }
        }

        // ---------- r16·导出结果 ----------
        val em = exportMsg
        if (em != null) {
            DhForm.Alert(
                title = "导出模型包",
                onDismiss = { exportMsg = null },
                posText = "知道了",
                onPos = { exportMsg = null }
            ) {
                Text(
                    text = em,
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
                DhForm.Note(
                    "包内只有这只人偶的动作素材，不含聊天记录与个人信息。\n" +
                        "路径在 /sdcard/Download/Dollhouse模型/，可以直接发给别人导入。"
                )
            }
        }

        // ---------- 改名弹窗 ----------
        if (renameOpen) {
            DhForm.Alert(
                title = "动作名称",
                onDismiss = { renameOpen = false },
                posText = "保存",
                onPos = {
                    val a = action
                    if (a != null && renameDraft.isNotBlank()) {
                        val u = a.copy(name = renameDraft.trim())
                        DollAnim.update(act, dollId, u)
                        action = u
                    }
                },
                negText = "取消"
            ) {
                DhForm.Input(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = "名称",
                    hint = "例如：挥手、转圈"
                )
            }
        }

        // ---------- 描述弹窗 ----------
        if (descOpen) {
            DhForm.Alert(
                title = "动作描述",
                onDismiss = { descOpen = false },
                posText = "保存",
                onPos = {
                    val a = action
                    if (a != null) {
                        val u = a.copy(desc = descDraft.trim())
                        DollAnim.update(act, dollId, u)
                        action = u
                    }
                },
                negText = "取消"
            ) {
                DhForm.InputMultiline(
                    value = descDraft,
                    onValueChange = { descDraft = it },
                    label = "描述",
                    hint = "这个动作是什么、什么时候用"
                )
            }
        }

        // ---------- 调速弹窗 ----------
        if (speedOpen) {
            var draft by remember { mutableStateOf(action?.fps ?: DollAnim.FPS_DEFAULT) }
            DhForm.Alert(
                title = "播放速度",
                onDismiss = { speedOpen = false },
                posText = "保存",
                onPos = {
                    val a = action
                    if (a != null) {
                        val u = a.copy(fps = DollAnim.clampFps(draft))
                        DollAnim.update(act, dollId, u)
                        action = u
                    }
                },
                negText = "取消"
            ) {
                DhForm.Note("每秒播多少帧（" + DollAnim.FPS_MIN + "～" + DollAnim.FPS_MAX + "）。数字越大动作越快。")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    DhKit.IconButton(Icons.IC_MINUS, Design.SZ_MD, c.title) {
                        draft = DollAnim.clampFps(draft - 1)
                    }
                    Text(
                        text = draft.toString() + " 帧/秒",
                        modifier = Modifier.width(96.dp),
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    DhKit.IconButton(Icons.IC_PLUS, Design.SZ_MD, c.title) {
                        draft = DollAnim.clampFps(draft + 1)
                    }
                }
                Spacer(Modifier.height(6.dp))
                DhForm.Note("单帧时长约 " + DollAnim.frameMs(draft) + " 毫秒。")
            }
        }

        // ---------- 属性弹窗 ----------
        // 【r13·属性归属】属性是数据里的一等字段（Action.group），不是界面里的死表；
        //   这里把它做成可选项，导入的动作也能归到「交互动作」等分组里。
        if (groupOpen) {
            DhForm.Alert(
                title = "动作属性",
                onDismiss = { groupOpen = false },
                posText = null,
                onPos = null,
                negText = "关闭"
            ) {
                DhForm.Note("属性决定这个动作出现在「动作」页的哪一组。")
                for (g in DollAnim.GROUP_ORDER) {
                    val cur = action?.group == g
                    DhKit.CheckRow(text = g, selected = cur) {
                        val a = action
                        if (a != null && !cur) {
                            val u = a.copy(group = g)
                            DollAnim.update(act, dollId, u)
                            action = u
                        }
                        groupOpen = false
                    }
                }
            }
        }
    }

    /**
     * 【C1】预览框正中的**中心点位**：十字 + 中心小圆。
     *
     * 【为什么要它】用户原话：「在上部的预览图中间放一个中心点位 这玩意是控制木偶的绝对中心
     *   方便以后添加模型的时候对齐」＋「以后添加的模型图片默认居中的是这个中心点位」。
     *   它既是「对齐基准」的视觉说明，也让用户一眼看懂下面那两个方向键在挪什么。
     *
     * 【为什么画在最上层】任何帧都不能盖住它 —— 它是基准，被盖住就失去意义。
     */
    @Composable
    private fun BoxScope.CenterMarker(color: Color) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val arm = 14.dp.toPx()
            val stroke = 1.5.dp.toPx()
            drawLine(color, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke)
            drawLine(color, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke)
            drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
        }
    }

    /** 帧缩略图：一帧一格，点选跳帧。 */
    @Composable
    private fun FrameThumb(path: String, index: Int, selected: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(path) {
            bmp = withContext(Dispatchers.IO) { decodeSmall(path) }
        }
        Column(
            modifier = Modifier
                .padding(end = 8.dp)
                .width(72.dp)
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .background(c.cardUp)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) c.acc else c.line,
                    RoundedCornerShape(DhKit.R_ROW.dp)
                )
                .pressableOnClick(onClick)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center
            ) {
                val b = bmp
                if (b != null) {
                    Image(
                        bitmap = b,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Text(
                text = (index + 1).toString(),
                color = if (selected) c.acc else c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
        }
    }

    /** 给整块加按压反馈（Thumb 本身在横向滚动条里，用 pressable 的点击 + 缩放语义）。 */
    @Composable
    private fun Modifier.pressableOnClick(onClick: () -> Unit): Modifier {
        return this.pressable(onClick)
    }

    /** 前移 / 后移一帧：顺序即播放顺序，所以必须能调。 */
    private fun moveFrame(
        act: MainActivity, dollId: String, a: DollAnim.Action?,
        frames: List<String>, idx: Int, delta: Int,
        done: (DollAnim.Action?, List<String>, Int) -> Unit
    ) {
        if (a == null || frames.size < 2) {
            return
        }
        val to = idx + delta
        if (to < 0 || to >= frames.size) {
            return
        }
        val list = ArrayList(frames)
        val tmp = list[idx]
        list[idx] = list[to]
        list[to] = tmp
        val u = a.copy(frames = list)
        DollAnim.update(act, dollId, u)
        done(u, list, to)
    }

    /**
     * 添加帧：走「任意文件」通道，一次可以选**一个成果文件**或**一整套图**。
     *
     * 【r13 需求原文】「添加动作的时候也可以直接导入这个文件 但是要保留逐张修改的功能」。
     *   所以这里只做「往末尾追加帧」，不覆盖已有帧；追加完照样能用
     *   「删除当前帧 / 前移一帧 / 后移一帧」逐张调整 —— 两条路并存。
     *
     * 【为什么追加而不是插入】用户导入的是一组连贯动作，追加最符合直觉；
     *   顺序不对可以用「前移 / 后移」微调。
     */
    private fun pickFrames(
        act: MainActivity, dollId: String, actionId: String,
        done: (List<String>) -> Unit
    ) {
        com.dollhouse.app.PickFileActivity.setFileListener { paths, failed ->
            if (paths.isEmpty()) {
                if (failed) {
                    HomeUi.showNotice(act, "导入失败", "没能读取选中的文件，请重试。")
                }
                return@setFileListener
            }
            Thread({
                val dir = DollAnim.framesDir(act, dollId, actionId)
                // 【关键】交给统一的成果文件解析器：json / 动图 / 精灵图 / 压缩包 / 纯图都认。
                val r = com.dollhouse.app.data.AnimImport.import(act, paths, dir)
                val dst = ArrayList(r.frames)
                // 【中间副本用完即清】PickFileActivity 拷进 cacheDir 的那份不再需要。
                for (p in paths) {
                    try {
                        File(p).delete()
                    } catch (t: Throwable) {
                    }
                }
                act.runOnUiThread {
                    val cur = DollAnim.byId(act, dollId, actionId)
                    if (cur == null || dst.isEmpty()) {
                        HomeUi.showNotice(
                            act, "没导入成功",
                            r.note.ifEmpty { "选中的文件里没找到能用的图片。" }
                        )
                        return@runOnUiThread
                    }
                    val list = ArrayList(cur.frames)
                    if (list.size + dst.size > DollAnim.FRAMES_MAX) {
                        val room = DollAnim.FRAMES_MAX - list.size
                        if (room <= 0) {
                            HomeUi.showNotice(
                                act, "帧数已达上限",
                                "单个动作最多 " + DollAnim.FRAMES_MAX + " 帧。"
                            )
                            return@runOnUiThread
                        }
                        list.addAll(dst.subList(0, room))
                        HomeUi.showNotice(act, "只导入了一部分", "已达 " + DollAnim.FRAMES_MAX + " 帧上限。")
                    } else {
                        list.addAll(dst)
                    }
                    // 【顺手把 fps 也带上】成果文件里带了帧率就用它（如 Lottie 的 fr）。
                    val u = cur.copy(frames = list, fps = DollAnim.clampFps(r.fps))
                    DollAnim.update(act, dollId, u)
                    done(list)
                    if (failed) {
                        HomeUi.showNotice(act, "部分文件导入失败", "只导入了能读到的那些。")
                    }
                }
            }, "dh-anim-import").start()
        }
        com.dollhouse.app.PickFileActivity.startFiles(act, true)
    }

    /**
     * 【r16】卸载某个模型包：删它的动作 + 删包记录与资源目录。
     *
     * 【为什么要一起删动作】模型包带来的动作是按 `sourceId` 标记的
     *   （见 `DollAnim.addEngineAction`）。只删包记录的话，
     *   动作列表里会留下一堆「来源已消失」的动作 —— 点了什么都不播。
     */
    private fun uninstallModel(act: MainActivity, dollId: String, sourceId: String, done: () -> Unit) {
        if (sourceId.isEmpty()) {
            return
        }
        DollAnim.removeBySource(act, dollId, sourceId)
        ModelRepository.remove(act, dollId, sourceId)
        HomeUi.showNotice(act, "已卸载", "「" + sourceId + "」带来的动作与资源都已删除。")
        HomeUi.notifyPetReload(act)
        done()
    }

    /** 完整解码（播放区用）。
     *
     * 【r18·内存】改采样解码：播放区控件尺寸远小于帧图原始分辨率
     *   （帧图普遍 ≥1024²，播放区通常 ≤600px），全尺寸解码纯属浪费内存。
     *   采样到 PLAY_TARGET_PX 后绘制阶段仍精确缩放，视觉零差异。
     *   与 decodeSmall 的区别只在目标尺寸（播放区要清晰，缩略图要省）。 */
    private fun decodeFull(path: String): Bitmap? {
        return com.dollhouse.app.core.ImageDecode.file(
            path, reqWidth = PLAY_TARGET_PX, reqHeight = PLAY_TARGET_PX)
    }

    /** 缩略图解码：只取 1/4 尺寸，避免一屏几十张把内存吃满。
     *
     * 【r18·收口】统一走 ImageDecode（采样策略与全工程一致）。
     *   缩略图目标边长取 THUMB_TARGET_PX（列表项 ~96px 的 2 倍余量）。 */
    private fun decodeSmall(path: String): ImageBitmap? {
        val b = com.dollhouse.app.core.ImageDecode.file(
            path, reqWidth = THUMB_TARGET_PX, reqHeight = THUMB_TARGET_PX)
        return b?.asImageBitmap()
    }
}