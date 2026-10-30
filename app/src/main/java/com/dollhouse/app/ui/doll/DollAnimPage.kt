package com.dollhouse.app.ui.doll

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.model.ModelExporter
import com.dollhouse.app.data.model.ModelRepository
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
 * 【职责】单个动作的详情页：**播放器 + 帧序列编辑器 + 属性设置**。
 *
 * ## v0.1.0 的三处关键变化
 * 1. **贴边壳专属**：多一条可拖的「裁截线」，决定人偶贴到屏幕边时露出多少。
 *    线外就是会被屏幕挡掉的区域，实时预览 —— 与真机上的观感一致。
 * 2. **固定三格（常驻/贴边/拖拽）**：
 *    · 「属性」行显示「XX动作（固定）」并**置灰**（不隐藏 —— 隐藏会让用户以为功能丢了）；
 *    · 「循环播放」强制开且置灰（持续态播完定格 = 卡死）；
 *    · 顶栏没有删除，改成「清空」（只清帧，名字/描述/速度保留）。
 * 3. **修掉几处老缺陷**：1 帧动作显示「还没有帧」、播放中点步进被循环覆盖、
 *    预览框上下拖动同时滚页面。
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
    private fun Content(act: MainActivity, dollId: String, initialActionId: String) {
        // 贴边页在同一个工作台切换循环与贴入，预览和全部编辑参数随当前动作一起切换。
        var actionId by remember(initialActionId) { mutableStateOf(initialActionId) }
        val c = DhTokens.colors

        // 动作元数据：改一处就 reload 一次。
        var action by remember(actionId) { mutableStateOf(DollAnim.byId(act, dollId, actionId)) }
        // 帧图路径（内置固定壳第一次进来要现烘）。
        var frames by remember { mutableStateOf<List<String>>(emptyList()) }
        var baking by remember { mutableStateOf(false) }
        var frameIdx by remember { mutableStateOf(0) }
        // 解码缓存（LRU，见 decodeForPlay）。
        var cache by remember(actionId) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
        var playing by remember { mutableStateOf(false) }
        // 播放进度用「累计已播毫秒」而不是 delay 叠加 —— 熄屏 / 卡顿之后不会漂移。
        var playClockMs by remember { mutableStateOf(0L) }

        // 改名 / 改描述 / 调速 / 属性 / 资源管理弹窗
        var renameOpen by remember { mutableStateOf(false) }
        var renameDraft by remember { mutableStateOf("") }
        var descOpen by remember { mutableStateOf(false) }
        var descDraft by remember { mutableStateOf("") }
        var speedOpen by remember { mutableStateOf(false) }
        var groupOpen by remember { mutableStateOf(false) }
        var resOpen by remember { mutableStateOf(false) }
        var clearOpen by remember { mutableStateOf(false) }
        var exportMsg by remember { mutableStateOf<String?>(null) }
        // ---------- 【工作台】显示与适配 / 行为绑定 的弹窗开关 ----------
        var anchorOpen by remember { mutableStateOf(false) }
        var offsetOpen by remember { mutableStateOf(false) }
        var triggerOpen by remember { mutableStateOf(false) }
        // 显示设置 / 行为绑定的本地刷新信号（写完要立刻重画设置区）。
        var dispTick by remember(actionId) { mutableStateOf(0) }
        var bindTick by remember(actionId) { mutableStateOf(0) }

        val group = action?.group ?: DollAnim.GROUP_MANUAL
        val fixed = DollAnim.isFixedGroup(group)
        val isEdge = group == DollAnim.GROUP_EDGE || group == DollAnim.GROUP_EDGE_IN

        // ---------- 非帧引擎预览：复用桌宠同一套渲染器 ----------
        val engineAction = action?.takeIf {
            it.engine != DollAnim.ENGINE_SPRITE && it.assetPath.isNotEmpty()
        }
        val previewRenderer = androidx.compose.runtime.remember(engineAction?.id, engineAction?.assetPath) {
            if (engineAction == null) null else {
                val r = com.dollhouse.app.pet.render.RendererRegistry.create(engineAction.engine)
                val source = if (engineAction.engine == DollAnim.ENGINE_LOTTIE)
                    com.dollhouse.app.pet.render.PetSource.Lottie(engineAction.assetPath)
                else com.dollhouse.app.pet.render.PetSource.AnimatedImage(engineAction.assetPath)
                if (r?.prepare(source) == true) r else { r?.release(); null }
            }
        }
        var previewTick by remember { mutableStateOf(0L) }
        DisposableEffect(previewRenderer) {
            previewRenderer?.attach(object : com.dollhouse.app.pet.render.PetDisplayHost {
                override fun context(): Context = act
                override fun requestDraw() { previewTick++ }
                override fun onSizeChanged(w: Int, h: Int) {}
                override fun isShown(): Boolean = true
                override fun isScreenOn(): Boolean = true
            })
            if (playing) previewRenderer?.play()
            onDispose { previewRenderer?.release() }
        }
        LaunchedEffect(playing, previewRenderer) {
            if (playing) previewRenderer?.play() else previewRenderer?.pause()
            while (playing && previewRenderer != null) {
                previewRenderer.advance(android.os.SystemClock.uptimeMillis())
                kotlinx.coroutines.delay(16L)
            }
        }

        // ---------- 准备帧图：动作切换与加载只有这一条状态写入链 ----------
        LaunchedEffect(actionId) {
            frames = emptyList()
            frameIdx = 0
            playing = false
            baking = false
            val a = DollAnim.byId(act, dollId, actionId) ?: return@LaunchedEffect
            action = a
            // 【v0.1.0·修 P0】原来是 `frames.size >= 2` —— 只有 1 帧的动作会掉进
            //   「现烘」分支，而烘帧只会烘内置动作，于是永远显示「还没有帧」。
            //   改成 >= 1：有帧就用，不再管有几帧。
            if (a.frames.isNotEmpty()) {
                frames = a.frames
                return@LaunchedEffect
            }
            if (!a.builtin) {
                // 用户自己建的空壳：没有帧是正常的，如实显示空态。
                frames = emptyList()
                return@LaunchedEffect
            }
            // 【本轮·修「空白模板还是有动作」】空白模板的三个固定壳虽然带 `builtin`，
            //   但它们是「占位空壳」，不该自动把内置动画烘进来 ——
            //   否则用户新建的空模板里会凭空出现「呼吸 36 帧」。
            //   判据走数据层的 [DollAnim.isBlankTemplate]（种子版本 == SEED_NONE），
            //   用户一旦点过「恢复默认配置」，这条就自动失效（那时他确实要内置动作）。
            if (DollAnim.isBlankTemplate(act, dollId)) {
                frames = emptyList()
                return@LaunchedEffect
            }
            // 内置固定壳：帧图还没烘，现烘一次（后台线程）。
            baking = true
            // 【v0.1.0·修 N1】烘帧比例必须用**这只人偶自己的比例**（DollProfile.scale），
            //   而不是全局的 PetPrefs.petScale —— 两套比例不同源时，
            //   预览里的帧和桌面上的实际大小对不上。
            val scale = DollProfile.scale(act, dollId) / 100.0f
            val out = withContext(Dispatchers.IO) {
                com.dollhouse.app.pet.AnimBaker.ensure(act, dollId, actionId, scale)
            }
            baking = false
            if (out.isNotEmpty()) {
                val updated = a.copy(frames = out)
                DollAnim.update(act, dollId, updated)
                action = updated
                frames = out
            }
        }

        // ---------- 解码当前帧（按需，只保留最近 12 张） ----------
        val curPath = if (frameIdx in frames.indices) frames[frameIdx] else ""
        LaunchedEffect(curPath) {
            if (curPath.isEmpty() || cache.containsKey(curPath)) {
                return@LaunchedEffect
            }
            val bmp = withContext(Dispatchers.IO) { decodeFull(curPath) }
            if (bmp != null) {
                // 【v0.1.0·修 P0】原来是 `cache + (path to bmp)`，只增不减 ——
                //   一整套 120 帧就是 690MB。这里改成带上限的「最近使用」：
                //   超过 12 张就把最久没用的那张丢掉并回收。
                cache = putBounded(cache, curPath, bmp.asImageBitmap(), CACHE_MAX)
            }
        }

        // ---------- 自动播放（时基推进） ----------
        LaunchedEffect(playing, frames, action?.fps, action?.loop) {
            val a = action ?: return@LaunchedEffect
            if (!playing || frames.size < 2) {
                return@LaunchedEffect
            }
            val step = DollAnim.frameMs(a.fps)
            playClockMs = 0L
            while (true) {
                kotlinx.coroutines.delay(step)
                if (!playing) {
                    break
                }
                // 【v0.1.0·修 P1】用累计时间除以单帧时长算「该走到第几帧」，
                //   而不是「每 tick 加一帧」。后者在系统忙 / 熄屏回来时会丢帧累积误差。
                playClockMs += step
                val adv = (playClockMs / step).toInt()
                if (adv <= 0) {
                    continue
                }
                playClockMs -= adv.toLong() * step
                val next = frameIdx + adv
                frameIdx = if (next >= frames.size) {
                    if (a.loop) next % frames.size else frames.size - 1
                } else {
                    next
                }
                if (!a.loop && frameIdx >= frames.size - 1) {
                    playing = false
                    break
                }
            }
        }

        // ---------- 页面 ----------
        DhKit.Page(
            title = action?.name ?: "动作",
            sub = (if (fixed) group + " · 固定" else group) + " · 共 " + frames.size + " 帧",
            onBack = { PageStack.pop(act) },
            scrollable = false,
            actions = {
                if (action != null) {
                    if (fixed) {
                        // 固定壳：只能清空（不能删）。图标用橡皮，语义一目了然。
                        DhKit.IconButton(Icons.IC_ERASER, Design.SZ_MD, c.title, label = "清空") {
                            clearOpen = true
                        }
                    } else {
                        DhKit.IconButton(Icons.IC_TRASH, Design.SZ_MD, c.err, label = "删除动作") {
                            // 【v0.1.0·修 P0】原先只 remove + pop：动作页靠 `DollState.rev` 重算
                            //   分组，不 bump 就还显示着刚删掉的那条（点进去空白）；桌宠也
                            //   不知道随机组变了，紧接着还可能抽到一个已经删掉的动作。
                            // 【R-112 九.4】删最后一个可播动作且没有形象图时会被拒绝，
                            //   必须告诉用户为什么，不能「点了没反应」（R-096）。
                            if (!DollAnim.remove(act, dollId, actionId)) {
                                HomeUi.showNotice(act, "至少保留一个动作", "这是最后一个动作了。先给她一张形象图，再删除。")
                            } else {
                                HomeUi.notifyPetReload(act)
                                DollState.bump()
                                PageStack.pop(act)
                            }
                        }
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
                // ============ ① 播放器 ============
                var offTick by remember(dollId) { mutableStateOf(0) }
                val off = remember(dollId, offTick) { DollProfile.frameOffset(act, dollId) }
                var previewW by remember { mutableStateOf(1f) }
                var previewH by remember { mutableStateOf(1f) }
                val oxPx = off[0]
                val oyPx = off[1]
// 贴边裁截线状态
                // 贴边裁截线状态（只有贴边壳用得上）。
                var insetTick by remember(actionId) { mutableStateOf(0) }
                val edgeInset = remember(actionId, insetTick) {
                    action?.edgeInset ?: DollAnim.EDGE_INSET_DEFAULT
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.1f)
                        .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                        .background(c.soft)
                        .border(1.dp, c.line, RoundedCornerShape(DhKit.R_CARD.dp))
                        .onSizeChanged { sz ->
                            previewW = Math.max(1f, sz.width.toFloat())
                            previewH = Math.max(1f, sz.height.toFloat())
                        }
                        // 【v0.0.9 起的拖动改偏移】加**方向锁**：
                        //   只有横向位移明显大于纵向时才改偏移，否则交给外层滚动。
                        //   （用户报的「预览框上下拖，页面跟着滚」就是没有方向锁。）
                        .pointerInput(dollId, isEdge, edgeInset) {
                            // 【v0.1.0·修 P0】裁截线拖动原先有两个错：
                            //   ① `edgeInset` 是组合期捕获的值，pointerInput 的 key 里没有它，
                            //      拖完一次后闭包里仍是旧值 —— 再拖会跳回原处；
                            //   ② 每帧用 `edgeInset - drag.x / w`（每帧增量 + 同一个起点值），
                            //      等于把每帧位移当成绝对位置算，手指一动线就乱跳。
                            //   现在：按下时**拍一次起点快照**，拖动期间累加总位移再算一次百分比。
                            var accX = 0f
                            var accY = 0f
                            var locked = false
                            var isInsetDrag = false
                            var insetStart = edgeInset
                            detectDragGestures(
                                onDragStart = { start ->
                                    accX = 0f
                                    accY = 0f
                                    locked = false
                                    insetStart = edgeInset
                                    // 起点落在裁截线附近（±24dp）→ 拖裁截线，不改偏移。
                                    isInsetDrag = isEdge &&
                                        Math.abs(start.x - size.width * (1f - edgeInset)) <
                                        (24 * density)
                                },
                                onDrag = { change, drag ->
                                    accX += drag.x
                                    accY += drag.y
                                    if (!locked) {
                                        if (Math.abs(accX) + Math.abs(accY) < 6f * density) {
                                            return@detectDragGestures
                                        }
                                        locked = Math.abs(accX) > Math.abs(accY)
                                    }
                                    if (isInsetDrag) {
                                        // 裁截线：只认横向位移，线往左拖 = 露得更多。
                                        change.consume()
                                        val w = size.width.toFloat()
                                        if (w <= 0f) {
                                            return@detectDragGestures
                                        }
                                        // 累计位移 ÷ 宽度 = 比例变化；起点值 + 变化量 = 目标值。
                                        val next = insetStart - accX / w
                                        DollAnim.setEdgeInset(act, dollId, actionId, next)
                                        insetTick++
                                        return@detectDragGestures
                                    }
                                    if (!locked) {
                                        return@detectDragGestures
                                    }
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
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = cache[curPath]
                    if (previewRenderer != null) {
                        previewTick.let { }
                        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            previewRenderer.draw(drawContext.canvas.nativeCanvas)
                        }
                    } else if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .offset { IntOffset((oxPx * previewW).roundToInt(), (oyPx * previewH).roundToInt()) },
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = if (baking) "正在生成帧…" else if (engineAction != null) "资源加载失败" else if (frames.isEmpty()) "还没有帧" else "加载中…",
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
                    // 中心点位（对齐基准）画在所有帧之上。
                    CenterMarker(c.acc)
                    // ---------- 贴边裁截线 ----------
                    if (isEdge && bmp != null) {
                        EdgeCutLine(
                            inset = edgeInset,
                            color = c.acc,
                            onDrag = { w ->
                                if (w > 0f) {
                                    // 由外层 onDrag 统一落盘；这里只负责「拖到哪」。
                                }
                            }
                        )
                    }
                }

                // ---------- 贴边：同一画板切换两种动作，不叠加预览与编辑控件 ----------
                if (isEdge) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DhForm.SelectChip("贴边循环 · 必选", actionId != DollAnim.SLOT_EDGE_IN_ID, Modifier.weight(1f)) {
                            actionId = DollAnim.SLOT_EDGE_ID
                        }
                        DhForm.SelectChip("贴入瞬间 · 可选", actionId == DollAnim.SLOT_EDGE_IN_ID, Modifier.weight(1f)) {
                            actionId = DollAnim.SLOT_EDGE_IN_ID
                        }
                    }
                    DhForm.Note(if (actionId == DollAnim.SLOT_EDGE_IN_ID)
                        "播放一次后进入贴边循环；未设置时会直接进入循环。"
                        else "贴着屏幕边缘时持续播放。")
                    DhKit.MiniChip(if (frames.isEmpty()) "添加当前动画" else "更换当前动画", iconRes = Icons.IC_IMAGE_PLUS) {
                        pickFrames(act, dollId, actionId) { changed ->
                            action = DollAnim.byId(act, dollId, actionId)
                            frames = changed
                            frameIdx = 0
                            HomeUi.notifyPetReload(act)
                            DollState.bump()
                        }
                    }
                }
                // ---------- 贴边裁截线的数值说明 ----------
                if (isEdge) {
                    Text(
                        text = "裁截线：人偶贴到屏幕边时，有 " +
                            Math.round(edgeInset * 100) + "% 会被屏幕挡掉，露出 " +
                            Math.round((1f - edgeInset) * 100) + "%。" +
                            "直接拖预览里的竖线，或点下面的按钮微调。",
                        modifier = Modifier.padding(top = 6.dp),
                        color = c.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // 【图标别再用左右箭头】贴边可能贴左也可能贴右，固定的「←/→」在贴右边时
                        //   方向就反了（用户会以为「露出更多」是把人偶往左边拉）。
                        //   「露出更多」= 少挡一点 = 面积变大 → 用放大/缩小这一对语义，
                        //   与方向无关，贴哪边都说得通。
                        DhKit.OutlineChip("露出更多", iconRes = Icons.IC_MAXIMIZE) {
                            DollAnim.setEdgeInset(act, dollId, actionId, edgeInset - 0.02f)
                            insetTick++
                        }
                        Spacer(Modifier.width(8.dp))
                        DhKit.OutlineChip("露出更少", iconRes = Icons.IC_MINIMIZE) {
                            DollAnim.setEdgeInset(act, dollId, actionId, edgeInset + 0.02f)
                            insetTick++
                        }
                        Spacer(Modifier.width(8.dp))
                        DhKit.OutlineChip("恢复默认") {
                            DollAnim.setEdgeInset(act, dollId, actionId, DollAnim.EDGE_INSET_DEFAULT)
                            insetTick++
                        }
                    }
                }

                // ---------- 偏移微调（方向键） ----------
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // 【v0.1.0·修·图标偏小】chevron 图形只占视口 58%，16dp 画出来仅约 9.3dp。
                    //   这四个是「微调偏移」的主操作按钮，必须一眼可辨 → 按按钮档走 SZ_MD。
                    DhKit.IconButton(Icons.IC_CHEVRON_LEFT, Design.SZ_MD, c.title) {
                        DollProfile.setFrameOffset(act, dollId, off[0] - FINE_STEP, off[1])
                        offTick++
                    }
                    Spacer(Modifier.width(4.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_UP, Design.SZ_MD, c.title) {
                        DollProfile.setFrameOffset(act, dollId, off[0], off[1] - FINE_STEP)
                        offTick++
                    }
                    Spacer(Modifier.width(4.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_DOWN, Design.SZ_MD, c.title) {
                        DollProfile.setFrameOffset(act, dollId, off[0], off[1] + FINE_STEP)
                        offTick++
                    }
                    Spacer(Modifier.width(4.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_RIGHT, Design.SZ_MD, c.title) {
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
                    text = "微调：横向拖动预览可粗调（纵向拖动会让页面滚），方向键精调；" +
                        "「居中」把偏移清零。当前偏移 " + pct(off[0]) + " / " + pct(off[1]),
                    modifier = Modifier.padding(top = 2.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )

                // ---------- 播放控制 ----------
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    DhKit.IconButton(Icons.IC_STEP_BACK, Design.SZ_MD, c.title, label = "上一帧") {
                        if (frames.isNotEmpty()) {
                            // 【v0.1.0·修 P1】点步进必须先把自动播放停掉，
                            //   否则下一 tick 就把 frameIdx 覆盖回去（用户看到的「按了没反应」）。
                            playing = false
                            frameIdx = if (frameIdx <= 0) frames.size - 1 else frameIdx - 1
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    DhKit.IconButton(
                        // 【v0.1.0·修·图标语义】暂停不能用方块（方块 = 停止，语义是「回到开头」）。
                        //   这里只是「停在这一帧、稍后从同一帧继续」，必须用暂停图标，
                        //   否则用户会以为按下去会把播放进度清零。
                        if (playing) Icons.IC_PAUSE else Icons.IC_PLAY,
                        Design.SZ_MD, c.acc,
                        label = if (playing) "暂停" else "播放"
                    ) { playing = !playing }
                    Spacer(Modifier.width(6.dp))
                    DhKit.IconButton(Icons.IC_STEP_FORWARD, Design.SZ_MD, c.title, label = "下一帧") {
                        if (frames.isNotEmpty()) {
                            playing = false
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

                // ============ ② 帧序列条 ============
                DhKit.SectionLabel("帧序列（左右滑动查看）", iconRes = Icons.IC_IMAGE)
                if (frames.isEmpty()) {
                    DhForm.Note(
                        if (fixed) "这一格还是空的。点下面「添加帧」导入一组图，它就会开始生效。"
                        else "这个动作还没有帧。点下面「添加帧」从相册选图。"
                    )
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

                // ---------- 属性 ----------
                // 【v0.1.0】固定三格显示「（固定）」并**置灰不隐藏** ——
                //   隐藏会让用户以为「这个功能在这类动作上丢了」；置灰 + 说明更诚实。
                DhKit.ValueRow(
                    label = "属性",
                    iconRes = Icons.IC_TAGS,
                    value = if (fixed) group + "（固定）" else group + " ›",
                    onClick = if (fixed) null else ({ groupOpen = true })
                )
                if (fixed) {
                    DhForm.Note(
                        "「" + group + "」是这只人偶的固定位，不能换组。" +
                            "想换内容就清空它、再导入新的一组图。"
                    )
                }

                // ---------- 【工作台】显示与适配 ----------
                // 【为什么放在「属性」之后、「资源管理」之前】
                //   顺序上它跟「这个动作是什么」一脉（属性 → 长什么样 → 挂什么资源），
                //   与下面的「帧操作」距离也够远，不会被当成帧级设置。
                val cur = action
                if (cur != null) {
                    DhKit.SectionLabel("显示与适配", iconRes = Icons.IC_MAXIMIZE)
                    val disp = remember(actionId, dispTick) {
                        com.dollhouse.app.data.ActionWorkbench.display(act, dollId, actionId)
                    }
                    DhKit.ValueRow(
                        label = "锚点",
                        iconRes = Icons.IC_CROSSHAIR,
                        value = com.dollhouse.app.data.ActionWorkbench.anchorLabel(disp.anchor) + " ›"
                    ) { anchorOpen = true }
                    DhKit.ValueRow(
                        label = "适配方式",
                        iconRes = Icons.IC_MAXIMIZE,
                        value = com.dollhouse.app.data.ActionWorkbench.fitLabel(disp.fit) + " ›"
                    ) {
                        val next = if (disp.fit == com.dollhouse.app.data.ActionWorkbench.FIT_CONTAIN)
                            com.dollhouse.app.data.ActionWorkbench.FIT_UNIFIED
                        else com.dollhouse.app.data.ActionWorkbench.FIT_CONTAIN
                        com.dollhouse.app.data.ActionWorkbench.setDisplay(
                            act, dollId, actionId,
                            com.dollhouse.app.data.ActionWorkbench.ActionDisplay(
                                anchor = disp.anchor, fit = next, ox = disp.ox, oy = disp.oy
                            )
                        )
                        dispTick++
                        HomeUi.notifyPetReload(act)
                    }
                    DhKit.ValueRow(
                        label = "内容偏移",
                        iconRes = Icons.IC_MOVE,
                        value = pct(disp.ox) + " / " + pct(disp.oy) + " ›"
                    ) { offsetOpen = true }
                    DhForm.Note(
                        "这三个只影响**这一个动作**怎么摆，不会改变人偶的整体大小 —— " +
                            "尺寸统一由「人偶比例」管，所以切换动作不会忽大忽小。" +
                            "偏移上限 ±50%，再大人偶会整只移出窗口。"
                    )
                }

                // ---------- 【工作台】行为绑定 ----------
                if (cur != null) {
                    DhKit.SectionLabel("行为绑定", iconRes = Icons.IC_WAYPOINTS)
                    val bind = remember(actionId, bindTick) {
                        com.dollhouse.app.data.ActionWorkbench.binding(act, dollId, actionId)
                    }
                    Text(
                        text = "允许被这些情况唤起：" +
                            if (bind.triggers.isEmpty()) "（无）"
                            else bind.triggers.joinToString("、") {
                                com.dollhouse.app.data.ActionWorkbench.triggerLabel(it)
                            },
                        modifier = Modifier.padding(top = 6.dp),
                        color = c.sub,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts
                    )
                    Spacer(Modifier.height(6.dp))
                    DhKit.MiniChip("选择触发方式", iconRes = Icons.IC_WAYPOINTS) { triggerOpen = true }
                    DhKit.ValueRow(
                        label = "优先级",
                        iconRes = Icons.IC_GAUGE,
                        value = com.dollhouse.app.data.ActionWorkbench.priorityLabel(bind.priority) + " ›"
                    ) {
                        // 三档循环：普通 → 高 → 最高 → 普通。
                        val next = when (bind.priority) {
                            com.dollhouse.app.data.ActionWorkbench.PRIORITY_NORMAL ->
                                com.dollhouse.app.data.ActionWorkbench.PRIORITY_HIGH
                            com.dollhouse.app.data.ActionWorkbench.PRIORITY_HIGH ->
                                com.dollhouse.app.data.ActionWorkbench.PRIORITY_TOP
                            else -> com.dollhouse.app.data.ActionWorkbench.PRIORITY_NORMAL
                        }
                        com.dollhouse.app.data.ActionWorkbench.setBinding(
                            act, dollId, actionId,
                            com.dollhouse.app.data.ActionWorkbench.ActionBinding(
                                triggers = bind.triggers, priority = next
                            )
                        )
                        bindTick++
                        HomeUi.notifyPetReload(act)
                    }
                    DhForm.Note(
                        "优先级是在原有语义顺序上「加一档力气」：普通不越级；" +
                            "高能压过常驻/贴边但压不过手动；最高能压过拖动（临时强制）。"
                    )
                }

                // ---------- 资源管理（非帧序列引擎） ----------
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

                // ---------- 循环 ----------
                // 【v0.1.0】固定三格强制循环：常驻/贴边/拖拽都是持续态，
                //   播完定格在最后一帧等于卡死（拖动时长不可控尤其明显）。
                DhKit.SwitchRow(
                    name = if (fixed) "循环播放（固定位强制开启）" else "循环播放",
                    iconRes = Icons.IC_REFRESH,
                    checked = if (fixed) true else (action?.loop ?: true)
                ) { on ->
                    if (fixed) {
                        // 置灰语义：点了也不改（给一句解释，避免用户以为坏了）。
                        HomeUi.showNotice(
                            act, "固定位必须循环",
                            "「" + group + "」要一直播下去，播完定格会看起来像卡死。"
                        )
                        return@SwitchRow
                    }
                    val a = action ?: return@SwitchRow
                    val u = a.copy(loop = on)
                    DollAnim.update(act, dollId, u)
                    action = u
                    HomeUi.notifyPetReload(act)
                    DollState.bump()
                }

                // ---------- 帧操作 ----------
                DhKit.SectionLabel("帧操作", iconRes = Icons.IC_BLOCKS)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        DhKit.MiniChip("添加帧", iconRes = Icons.IC_IMAGE_PLUS) {
                            pickFrames(act, dollId, actionId) { onFramesChanged ->
                                action = DollAnim.byId(act, dollId, actionId)
                                frames = onFramesChanged
                                frameIdx = 0
                                // 【v0.1.0】帧变了就要通知桌宠重载，否则它仍按旧帧播。
                                HomeUi.notifyPetReload(act)
                                DollState.bump()
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        DhKit.MiniChip("删除当前帧", iconRes = Icons.IC_TRASH) {
                            if (frames.size <= 1) {
                                // 【v0.1.0】允许删到 0 帧：固定壳本来就应该能「清空」，
                                //   单帧动作也该能删掉那一帧。但删到 0 之后播放区立刻显示空态。
                                val a = action
                                if (a != null && frames.isNotEmpty()) {
                                    try {
                                        File(frames[0]).delete()
                                    } catch (t: Throwable) {
                                    }
                                    val u = a.copy(frames = emptyList())
                                    DollAnim.update(act, dollId, u)
                                    action = u
                                    frames = emptyList()
                                    frameIdx = 0
                                    playing = false
                                    HomeUi.notifyPetReload(act)
                                    DollState.bump()
                                }
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
                                    HomeUi.notifyPetReload(act)
                                    DollState.bump()
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        DhKit.MiniChip("前移一帧", iconRes = Icons.IC_ARROW_LEFT) {
                            moveFrame(act, dollId, action, frames, frameIdx, -1) { a2, f2, i2 ->
                                action = a2
                                frames = f2
                                frameIdx = i2
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        DhKit.MiniChip("后移一帧", iconRes = Icons.IC_ARROW_RIGHT) {
                            moveFrame(act, dollId, action, frames, frameIdx, 1) { a2, f2, i2 ->
                                action = a2
                                frames = f2
                                frameIdx = i2
                            }
                        }
                    }
                }

                DhForm.Note(
                    if (fixed) {
                        "这是「" + group + "」固定位。帧图由真实渲染管线生成时可以直接用；" +
                            "也可以清空后导入你自己的一组图。改完即时保存。"
                    } else {
                        "这是你导入的动作。帧的顺序就是播放顺序，改完即时保存。"
                    }
                )
            }
        }

        // ---------- 【工作台】锚点选择 ----------
        if (anchorOpen) {
            val disp = com.dollhouse.app.data.ActionWorkbench.display(act, dollId, actionId)
            DhForm.Alert(
                title = "锚点",
                onDismiss = { anchorOpen = false },
                posText = "完成",
                onPos = { anchorOpen = false }
            ) {
                Text(
                    text = "决定这个动作的画面「对齐到窗口的哪个点」。" +
                        "真实播放时人偶的脚踩在窗口底边，所以默认是底部居中。",
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
                DhForm.Gap()
                for (a in com.dollhouse.app.data.ActionWorkbench.ANCHORS) {
                    DhKit.CheckRow(
                        text = com.dollhouse.app.data.ActionWorkbench.anchorLabel(a),
                        selected = disp.anchor == a
                    ) {
                        com.dollhouse.app.data.ActionWorkbench.setDisplay(
                            act, dollId, actionId,
                            com.dollhouse.app.data.ActionWorkbench.ActionDisplay(
                                anchor = a, fit = disp.fit, ox = disp.ox, oy = disp.oy
                            )
                        )
                        dispTick++
                        HomeUi.notifyPetReload(act)
                        anchorOpen = false
                    }
                }
            }
        }

        // ---------- 【工作台】内容偏移 ----------
        if (offsetOpen) {
            val disp = com.dollhouse.app.data.ActionWorkbench.display(act, dollId, actionId)
            DhForm.Alert(
                title = "内容偏移",
                onDismiss = { offsetOpen = false },
                posText = "完成",
                onPos = { offsetOpen = false }
            ) {
                Text(
                    text = "把这个动作的画面整体挪一点。当前：" + pct(disp.ox) + " / " + pct(disp.oy),
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
                DhForm.Gap()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    DhKit.IconButton(Icons.IC_CHEVRON_LEFT, Design.SZ_MD, c.title, label = "左移") {
                        com.dollhouse.app.data.ActionWorkbench.setDisplay(
                            act, dollId, actionId,
                            com.dollhouse.app.data.ActionWorkbench.ActionDisplay(
                                anchor = disp.anchor, fit = disp.fit,
                                ox = disp.ox - 0.01f, oy = disp.oy
                            )
                        )
                        dispTick++
                        HomeUi.notifyPetReload(act)
                    }
                    Spacer(Modifier.width(4.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_UP, Design.SZ_MD, c.title, label = "上移") {
                        com.dollhouse.app.data.ActionWorkbench.setDisplay(
                            act, dollId, actionId,
                            com.dollhouse.app.data.ActionWorkbench.ActionDisplay(
                                anchor = disp.anchor, fit = disp.fit,
                                ox = disp.ox, oy = disp.oy - 0.01f
                            )
                        )
                        dispTick++
                        HomeUi.notifyPetReload(act)
                    }
                    Spacer(Modifier.width(4.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_DOWN, Design.SZ_MD, c.title, label = "下移") {
                        com.dollhouse.app.data.ActionWorkbench.setDisplay(
                            act, dollId, actionId,
                            com.dollhouse.app.data.ActionWorkbench.ActionDisplay(
                                anchor = disp.anchor, fit = disp.fit,
                                ox = disp.ox, oy = disp.oy + 0.01f
                            )
                        )
                        dispTick++
                        HomeUi.notifyPetReload(act)
                    }
                    Spacer(Modifier.width(4.dp))
                    DhKit.IconButton(Icons.IC_CHEVRON_RIGHT, Design.SZ_MD, c.title, label = "右移") {
                        com.dollhouse.app.data.ActionWorkbench.setDisplay(
                            act, dollId, actionId,
                            com.dollhouse.app.data.ActionWorkbench.ActionDisplay(
                                anchor = disp.anchor, fit = disp.fit,
                                ox = disp.ox + 0.01f, oy = disp.oy
                            )
                        )
                        dispTick++
                        HomeUi.notifyPetReload(act)
                    }
                }
                DhForm.Gap()
                DhKit.MiniChip("清零", iconRes = Icons.IC_CROSSHAIR) {
                    com.dollhouse.app.data.ActionWorkbench.setDisplay(
                        act, dollId, actionId,
                        com.dollhouse.app.data.ActionWorkbench.ActionDisplay(
                            anchor = disp.anchor, fit = disp.fit, ox = 0f, oy = 0f
                        )
                    )
                    dispTick++
                    HomeUi.notifyPetReload(act)
                    offsetOpen = false
                }
                DhForm.Note(
                    "每点一次挪 1%（屏幕宽度/高度的比例）。要粗调直接在预览里横向拖，" +
                        "或者用上面「设置」里的方向键。"
                )
            }
        }

        // ---------- 【工作台】触发方式 ----------
        if (triggerOpen) {
            val bind = com.dollhouse.app.data.ActionWorkbench.binding(act, dollId, actionId)
            var draft by remember { mutableStateOf(bind.triggers) }
            DhForm.Alert(
                title = "触发方式",
                onDismiss = { triggerOpen = false },
                posText = "保存",
                onPos = {
                    com.dollhouse.app.data.ActionWorkbench.setBinding(
                        act, dollId, actionId,
                        com.dollhouse.app.data.ActionWorkbench.ActionBinding(
                            triggers = draft, priority = bind.priority
                        )
                    )
                    bindTick++
                    HomeUi.notifyPetReload(act)
                    triggerOpen = false
                },
                negText = "取消"
            ) {
                Text(
                    text = "勾上「这个动作允许被哪些情况唤起」。全都不勾等于只保留手动触发" +
                        "（否则它永远播不到）。",
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
                DhForm.Gap()
                for (t in com.dollhouse.app.data.ActionWorkbench.TRIGGERS) {
                    val on = draft.contains(t)
                    DhKit.CheckRow(
                        text = com.dollhouse.app.data.ActionWorkbench.triggerLabel(t),
                        selected = on
                    ) {
                        val next = LinkedHashSet(draft)
                        if (on) next.remove(t) else next.add(t)
                        draft = ArrayList(next)
                    }
                }
            }
        }

        // ---------- 清空固定壳 ----------
        if (clearOpen) {
            DhForm.Alert(
                title = "清空「" + group + "」",
                onDismiss = { clearOpen = false },
                posText = "清空",
                onPos = {
                    DollAnim.clearFrames(act, dollId, actionId)
                    HomeUi.notifyPetReload(act)
                    DollState.bump()
                    action = DollAnim.byId(act, dollId, actionId)
                    frames = emptyList()
                    frameIdx = 0
                    playing = false
                    clearOpen = false
                }
            ) {
                Text(
                    text = "会把这一格里的动画与资源引用清空，之后你可以导入新的一组图。\n\n" +
                        "名字、描述、播放速度（以及贴边裁截线）都会保留。",
                    color = c.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }

        // ---------- 资源管理弹窗 ----------
        if (resOpen) {
            val a = action
            DhForm.Alert(
                title = "资源管理",
                onDismiss = { resOpen = false },
                posText = "重新指定",
                onPos = {
                    resOpen = false
                    com.dollhouse.app.PickFileActivity.setFileListener { paths, _ ->
                        if (paths.isEmpty()) {
                            return@setFileListener
                        }
                        val src = File(paths[0])
                        val oldEngine = action?.engine ?: DollAnim.ENGINE_LOTTIE
                        val dstDir = DollAnim.framesDir(act, dollId, actionId)
                        val dst = File(dstDir, src.name)
                        val incoming = File(dstDir, ".incoming_" + System.currentTimeMillis() + "_" + src.name)
                        try {
                            src.copyTo(incoming, overwrite = false)
                            if (oldEngine == DollAnim.ENGINE_LOTTIE) {
                                val check = com.dollhouse.app.pet.render.LottieRenderer()
                                if (!check.prepare(com.dollhouse.app.pet.render.PetSource.Lottie(incoming.absolutePath))) {
                                    incoming.delete()
                                    throw IllegalArgumentException(check.lastError() ?: "Lottie 文件无效")
                                }
                                check.release()
                            }
                            if (!incoming.renameTo(dst)) {
                                incoming.copyTo(dst, overwrite = true)
                                incoming.delete()
                            }
                            src.delete()
                        } catch (t: Throwable) {
                            incoming.delete()
                            src.delete()
                            HomeUi.showNotice(act, "资源替换失败", t.message ?: "原资源保持不变。")
                            return@setFileListener
                        }
                        val old = DollAnim.byId(act, dollId, actionId) ?: return@setFileListener
                        val u = old.copy(assetPath = dst.absolutePath)
                        DollAnim.update(act, dollId, u)
                        action = u
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

        // ---------- 导出结果 ----------
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
                        HomeUi.notifyPetReload(act)
                        DollState.bump()
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
                        HomeUi.notifyPetReload(act)
                        DollState.bump()
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

        // ---------- 属性弹窗（只列随机 / 手动） ----------
        if (groupOpen) {
            DhForm.Alert(
                title = "动作属性",
                onDismiss = { groupOpen = false },
                posText = null,
                onPos = null,
                negText = "关闭"
            ) {
                DhForm.Note(
                    "属性决定这个动作出现在「动作」页的哪一组。" +
                        "常驻 / 贴边 / 拖拽是固定位，不出现在这里。"
                )
                for (g in DollAnim.SWAPPABLE_GROUPS) {
                    val sel = action?.group == g
                    DhKit.CheckRow(text = g, selected = sel) {
                        if (!sel) {
                            DollAnim.moveToGroup(act, dollId, actionId, g)
                            action = DollAnim.byId(act, dollId, actionId)
                            HomeUi.notifyPetReload(act)
                            DollState.bump()
                        }
                        groupOpen = false
                    }
                }
            }
        }
    }

    /* ============================ 预览辅助 ============================ */

    /**
     * 【C1】预览框正中的**中心点位**：十字 + 中心小圆。
     *
     * 【为什么要它】用户原话：「在上部的预览图中间放一个中心点位 这玩意是控制木偶的绝对中心
     *   方便以后添加模型的时候对齐」。它既是「对齐基准」的视觉说明，
     *   也让用户一眼看懂下面那两个方向键在挪什么。
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

    /**
     * **【v0.1.0·新增】贴边裁截线**：预览框里一条可拖的竖线。
     *
     * 【语义】线**外侧**（靠屏幕那一边）就是贴边时会被挡掉的区域。
     *   因为人偶贴左边时是从左侧被挡、贴右边时是从右侧被挡，
     *   编辑页统一按「右侧被挡」画（默认值 0.68 对应「右边 68% 在屏幕外」），
     *   与真机观感一致（真实贴边方向由 `snapToEdge` 决定，两者数值同源）。
     *
     * 【为什么线画在 `1 - inset` 的位置】`inset` 是「被挡掉的比例」，
     *   所以可见区的右边界就在 `宽度 × (1 - inset)` 处 —— 线左边是「看得见的」。
     */
    @Composable
    private fun BoxScope.EdgeCutLine(inset: Float, color: Color, onDrag: (Float) -> Unit) {
        val x0 = 1f - inset
        Canvas(modifier = Modifier.matchParentSize()) {
            val x = size.width * x0
            // 被挡掉的区域铺一层淡色，直观表达「这块会被屏幕吃掉」。
            drawRect(
                color = color.copy(alpha = 0.10f),
                topLeft = Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(size.width - x, size.height)
            )
            val stroke = 2.dp.toPx()
            drawLine(
                color = color,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = stroke
            )
            // 线的抓手：中间画一个小圆，明示「这条线能拖」。
            drawCircle(color, radius = 5.dp.toPx(), center = Offset(x, size.height / 2f))
        }
    }

    /* ============================ 帧缩略图 ============================ */

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
                .pressable(onClick)
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
     * 【为什么只追加不覆盖】用户导入的是一组连贯动作，追加最符合直觉；
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
                val official = DollAnim.framesDir(act, dollId, actionId)
                val dir = File(official.parentFile, actionId + "__append_" + System.currentTimeMillis())
                // 先写临时目录：追加不能从 i0000 开始覆盖已有帧。
                val r = com.dollhouse.app.data.AnimImport.import(act, paths, dir)
                val dst = ArrayList<String>()
                val occupied = DollAnim.byId(act, dollId, actionId)?.frames ?: emptyList()
                for ((index, path) in r.frames.withIndex()) {
                    var n = occupied.size + index
                    var target = File(official, String.format("i%04d.png", n))
                    while (target.exists()) {
                        n++
                        target = File(official, String.format("i%04d.png", n))
                    }
                    val source = File(path)
                    val ok = try {
                        source.renameTo(target) || run {
                            source.copyTo(target, overwrite = false)
                            true
                        }
                    } catch (t: Throwable) { false }
                    if (ok) dst.add(target.absolutePath)
                }
                dir.deleteRecursively()
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
                    // 【本轮·动作大小统一】算一次「本体基准」再落盘。
                    //
                    // 为什么在这里算：这是**唯一**把外部图片变成这只动作帧的入口，
                    //   多帧导入 / 逐张添加都走它。放别处会漏。
                    // 为什么用整组算：见 BodyBaseline 的注释 —— 逐帧算会让尺寸逐帧抖。
                    // 为什么算不出就留空：宁可退化成旧行为（整画布铺满），
                    //   也不要写一个错的基准让人偶永远偏大/偏小。
                    val bl = com.dollhouse.app.data.FrameScope.computeBaseline(list)
                    val u = cur.copy(
                        frames = list,
                        fps = DollAnim.clampFps(r.fps),
                        baseline = bl?.encode() ?: "",
                        engine = DollAnim.ENGINE_SPRITE,
                        sourceId = "",
                        assetPath = ""
                    )
                    DollAnim.update(act, dollId, u)
                    done(list)
                    if (failed) {
                        HomeUi.showNotice(act, "部分文件导入失败", "只导入了能读到的那些。")
                    }
                    if (bl == null && list.isNotEmpty()) {
                        // 【如实告知】没算出来时明确说一句，免得用户以为「适配过了」。
                        HomeUi.showNotice(
                            act, "尺寸沿用原画布",
                            "这组图里认不出稳定的角色轮廓，已按整张画布显示。若大小不对，可换一组留白更规整的图。"
                        )
                    }
                }
            }, "dh-anim-import").start()
        }
        com.dollhouse.app.PickFileActivity.startFiles(act, true)
    }

    /** 卸载某个模型包：删它的动作 + 删包记录与资源目录。 */
    private fun uninstallModel(act: MainActivity, dollId: String, sourceId: String, done: () -> Unit) {
        if (sourceId.isEmpty()) {
            return
        }
        for (old in DollAnim.all(act, dollId)) {
            if (old.sourceId == sourceId) {
                com.dollhouse.app.data.ActionLibrary.purgeRefs(act, dollId, old.id)
            }
        }
        DollAnim.removeBySource(act, dollId, sourceId)
        ModelRepository.remove(act, dollId, sourceId)
        HomeUi.showNotice(act, "已卸载", "「" + sourceId + "」带来的动作与资源都已删除。")
        HomeUi.notifyPetReload(act)
        done()
    }

    /* ============================ 解码 ============================ */

    /** 完整解码（播放区用），统一走 ImageDecode 的采样策略。 */
    private fun decodeFull(path: String): Bitmap? {
        return com.dollhouse.app.core.ImageDecode.file(
            path, reqWidth = PLAY_TARGET_PX, reqHeight = PLAY_TARGET_PX
        )
    }

    /** 缩略图解码：只取 1/4 尺寸，避免一屏几十张把内存吃满。 */
    private fun decodeSmall(path: String): ImageBitmap? {
        val b = com.dollhouse.app.core.ImageDecode.file(
            path, reqWidth = THUMB_TARGET_PX, reqHeight = THUMB_TARGET_PX
        )
        return b?.asImageBitmap()
    }

    /** 解码缓存的容量上限（张）。一帧按 1200² ARGB 约 5.8MB，12 张约 70MB。 */
    private const val CACHE_MAX = 12

    /**
     * 往缓存里放一张，**超出上限就丢掉最久没用的那张**。
     *
     * 【为什么需要上限】改造前是「只增不减」的 Map：一整套 120 帧滑下来
     *   峰值接近 690MB（用户滑动帧序列时直接 OOM）。
     *   这里用「最近使用」近似 LRU：命中时把该键挪到队尾，插入时裁掉队头。
     *   （不引 LinkedHashMap 的 accessOrder 是因为要放进 Compose 的 immutable Map 状态里，
     *   自己维护顺序更直观、也更好测。）
     */
    private fun putBounded(
        old: Map<String, ImageBitmap>,
        key: String,
        value: ImageBitmap,
        max: Int
    ): Map<String, ImageBitmap> {
        if (old.containsKey(key)) {
            return old
        }
        val ordered = LinkedHashMap<String, ImageBitmap>(old)
        ordered[key] = value
        while (ordered.size > max) {
            val first = ordered.keys.firstOrNull() ?: break
            ordered.remove(first)
        }
        return ordered
    }
}