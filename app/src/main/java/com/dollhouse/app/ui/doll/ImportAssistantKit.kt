package com.dollhouse.app.ui.doll

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.ImportPlan
import com.dollhouse.app.data.ImportSession
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import kotlinx.coroutines.delay

/**
 * 【职责】导入助手的**展示组件**（与流程无关的那部分）。
 *
 * 拆出来的理由：`ImportAssistantPage` 里那条状态机已经够长了，
 * 把「一张卡片长什么样」混进去会让人不敢改流程。这里只放纯展示 + 纯局部状态，
 * 不认识会话、不认识提交，输入输出全是普通值。
 */
internal object ImportAssistantKit {

    /** 选择页的两张路径卡片（8.1）。 */
    @Composable
    fun ChoiceCard(
        tag: String,
        title: String,
        desc: String,
        /** 卡片左侧语义图标（Lucide）。默认 0 = 不画。 */
        iconRes: Int = 0,
        /** 追加一行小字说明（如「会画画、懂素材」）。空 = 不占行。 */
        extra: String = "",
        onClick: () -> Unit
    ) {
        val c = DhTokens.colors
        DhKit.Card {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().pressable(onClick),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (iconRes != 0) {
                        DhKit.Icon(iconRes, Design.SZ_MD, c.acc)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tag,
                            color = c.acc,
                            fontSize = UiKit.FS_TINY.sp,
                            fontFamily = DhTokens.fontsBold,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = title,
                            color = c.title,
                            fontSize = UiKit.FS_TITLE.sp,
                            fontFamily = DhTokens.fontsBold,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = desc,
                            color = c.sub,
                            fontSize = UiKit.FS_SUB.sp,
                            fontFamily = DhTokens.fonts
                        )
                        if (extra.isNotEmpty()) {
                            // 【R-112】高手卡片的补充说明：把「高手」解释成「会画画、懂素材」，
                            //   避免用户误以为「高手」是操作熟练度。
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = extra,
                                color = c.sub,
                                fontSize = UiKit.FS_TINY.sp,
                                fontFamily = DhTokens.fonts
                            )
                        }
                    }
                    DhKit.Icon(Icons.IC_CHEVRON_RIGHT, Design.SZ_MD, c.sub)
                }
                // 整卡内容统一接收点击，避免只覆盖底部 48dp 的透明热区。
            }
        }
    }

    /**
     * 高手页的一行（R-112 六节）：标题 + 说明 + 状态 + 「选择图片」。
     *
     * 【为什么行内独立按钮】R-094 要求「拖拽只能绑定在明确的拖拽区域」；
     *   同理这一行的点击不能整行吃掉 —— 用户可能只想滑动页面。
     */
    @Composable
    fun ArtistRow(
        title: String,
        desc: String,
        required: Boolean,
        picked: List<String>,
        //   图片 / 序列帧 = 张数 == 帧数；动图 / Lottie 一张文件对应多帧，
        //   由调用方按识别结果传入；未传时退化为张数（与旧行为一致）。
        frames: Int = picked.size,
        // 【Lucide】行首语义图标。默认 IC_IMAGE = 与改造前一致。
        iconRes: Int = Icons.IC_IMAGE,
        // 【放在最后】尾随 lambda 会覆盖最后一个函数类型参数。
        //   若 onPick 留在 picked 后面，调用方写
        //     ArtistRow(..., picked = x, frames = n) { pick() }
        //   会把 lambda 绑到 onPick 之外的位置，编译直接报「No value passed for parameter 'onPick'」。
        onPick: () -> Unit
    ) {
        val c = DhTokens.colors
        DhKit.Card {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DhKit.Icon(iconRes, Design.SZ_SM, c.acc)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            color = c.title,
                            fontSize = UiKit.FS_BTN.sp,
                            fontFamily = DhTokens.fontsBold,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (required) "必需" else "可跳过",
                            color = c.sub,
                            fontSize = UiKit.FS_TINY.sp,
                            fontFamily = DhTokens.fonts
                        )
                    }
                    Text(
                        text = desc,
                        color = c.sub,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DhKit.MiniChip(
                            if (picked.isEmpty()) "选择图片" else "重新选择",
                            iconRes = if (picked.isEmpty()) Icons.IC_IMAGE else Icons.IC_REFRESH
                        ) { onPick() }
                        if (picked.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "已选 " + picked.size + " 张 / " + frames + " 帧",
                                color = c.acc,
                                fontSize = UiKit.FS_SUB.sp,
                                fontFamily = DhTokens.fontsBold,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    /** 识别页的一行：状态 + 帧数 +（多组时）选项。 */
    @Composable
    fun SlotRow(
        label: String,
        frames: Int,
        alternatives: List<ImportPlan.Group>,
        chosenKey: String?,
        onChoose: (String) -> Unit
    ) {
        val c = DhTokens.colors
        val has = frames > 0
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .background(c.soft)
                .padding(DhKit.PAD_ROW.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DhKit.Icon(
                    if (has) Icons.IC_CHECK_CIRCLE else Icons.IC_CIRCLE_ALERT,
                    Design.SZ_SM,
                    // 未设置用中性灰（R-064 / R-107），不用红色。
                    if (has) c.acc else c.sub
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (has) frames.toString() + " 帧" else "未设置",
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            // 同槽多组：让用户选（R-072），不能静默覆盖。
            if (alternatives.size > 1) {
                Spacer(Modifier.height(6.dp))
                DhForm.Note("这里找到 " + alternatives.size + " 组「" + label + "」，选一组用：")
                for (g in alternatives) {
                    DhKit.CheckRow(
                        text = g.name + "（" + g.files.size + " 帧）",
                        selected = chosenKey == g.key
                    ) {
                        onChoose(g.key)
                    }
                }
            }
        }
    }

    /** 四态页签。未设置的置灰（不是红色）。 */
    @Composable
    fun SlotTab(
        modifier: Modifier = Modifier,
        label: String,
        enabled: Boolean,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val c = DhTokens.colors
        Box(
            modifier = modifier
                .height(DhKit.MIN_TOUCH.dp)
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .background(if (selected) c.option else c.soft)
                .border(
                    1.dp,
                    if (selected) c.acc else c.line,
                    RoundedCornerShape(DhKit.R_ROW.dp)
                )
                .pressable(onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (enabled) c.title else c.sub.copy(alpha = 0.55f),
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    /**
     * 真实比例预览框。
     *
     * 【为什么真的播帧而不是放一张静态缩略】23.1.6 明确要求「不得以静态占位图冒充
     *   真实状态动画」。这里按真实帧序列循环，缩放系数用会话里的大小设置，
     *   与桌面上将来看到的比例一致（按 Fit 居中，不拉伸变形）。
     *
     * 【为什么只解 24 帧】预览只需要「动起来」的观感；全部解码一套 120 帧 PNG
     *   会瞬时吃掉几十 MB 内存（N-011 / 21.4.1 都要求不整包常驻）。
     */
    @Composable
    fun FramePreview(
        files: List<String>,
        sizePercent: Int,
        offsetX: Float,
        offsetY: Float
    ) {
        val c = DhTokens.colors
        val limit = Math.min(files.size, MAX_PREVIEW_FRAMES)
        val cache = remember(files) { HashMap<Int, ImageBitmap>() }
        var frame by remember(files) { mutableStateOf(0) }
        LaunchedEffect(files) {
            frame = 0
            if (limit > 1) {
                while (true) {
                    delay(Math.max(40L, 1000L / Math.max(1, DollAnim.FPS_DEFAULT)))
                    frame = (frame + 1) % limit
                }
            }
        }
        val key = if (limit <= 0) 0 else frame % limit
        val bmp = remember(files, key) {
            cache[key] ?: run {
                val p = files.getOrNull(key)
                val decoded = if (p == null) {
                    null
                } else {
                    com.dollhouse.app.core.ImageDecode.file(p, reqWidth = 512, reqHeight = 512)
                }
                decoded?.asImageBitmap()?.also { cache[key] = it }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                .background(c.soft)
                .border(1.dp, c.line, RoundedCornerShape(DhKit.R_CARD.dp)),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b == null) {
                Text(
                    text = "这里会显示她的样子",
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            } else {
                val k = Math.max(0.1f, sizePercent / 100f)
                Box(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = b,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize(0.90f * k)
                            .padding(start = (offsetX * 40).dp, top = (offsetY * 40).dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }

    /** 加载态：超过 300ms 的操作必须有可见反馈（R-095）。 */
    @Composable
    fun BusyHint(text: String) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(Icons.IC_SPINNER, Design.SZ_SM, c.acc)
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                color = c.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /**
     * 失败提示。
     *
     * 【红色只在真失败时用】R-107 明确：缺失状态用中性灰，红色只留给真正的失败。
     *   这里承载的是「读不出来 / 空间不足 / 路径非法」这类真失败，所以用 err 色。
     */
    @Composable
    fun FailureBox(text: String) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .background(c.err.copy(alpha = 0.10f))
                .padding(DhKit.PAD_ROW.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(Icons.IC_CIRCLE_ALERT, Design.SZ_SM, c.err)
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = c.err,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /** 完成页的状态行。 */
    @Composable
    fun StatusRow(label: String, has: Boolean) {
        val c = DhTokens.colors
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
                if (has) Icons.IC_CHECK_CIRCLE else Icons.IC_CIRCLE_ALERT,
                Design.SZ_SM,
                if (has) c.acc else c.sub
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /** 预览帧数上限。 */
    const val MAX_PREVIEW_FRAMES = 24

    /** 完成页的状态行文案（不用「已设置 / 未设置」这种工程味说法）。 */
    fun statusTextOf(slot: ImportPlan.Slot, has: Boolean): String = when (slot) {
        ImportPlan.Slot.IDLE -> if (has) "会待机" else "还没有待机动作"
        ImportPlan.Slot.DRAG -> if (has) "会跟着你拖动" else "还没有拖拽动画"
        ImportPlan.Slot.EDGE_IN -> if (has) "贴到边上会顿一下" else "还没有贴边动画"
        ImportPlan.Slot.EDGE_LOOP -> if (has) "贴在边上会待着" else "还没有贴边动画"
        ImportPlan.Slot.AUTO -> if (has) "会自己动一动" else "还没有自动动作"
        ImportPlan.Slot.MANUAL -> if (has) "点她会有反应" else "还没有手动动作"
    }

    /** 偏移 → 人话。 */
    fun offsetText(v: Float, horizontal: Boolean): String {
        val p = Math.round(Math.abs(v) * 100f).toInt()
        if (p <= 1) {
            return "居中"
        }
        return if (horizontal) {
            if (v > 0) "偏右 " + p + "%" else "偏左 " + p + "%"
        } else {
            if (v > 0) "偏下 " + p + "%" else "偏上 " + p + "%"
        }
    }

    /** 四态横向排布。 */
    @Composable
    fun SlotTabRow(
        slots: List<ImportPlan.Slot>,
        selected: ImportPlan.Slot,
        hasOf: (ImportPlan.Slot) -> Boolean,
        onClick: (ImportPlan.Slot) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (slot in slots) {
                SlotTab(
                    modifier = Modifier.weight(1f),
                    label = ImportPlan.labelOf(slot),
                    enabled = hasOf(slot),
                    selected = selected == slot
                ) {
                    onClick(slot)
                }
            }
        }
    }


    /**
     * 【R-065】可拖动预览。
     *
     * 与 [FramePreview] 的区别：用户可以用手指把角色**拖到任意位置**，
     * 拖到左右边缘时按 19.3 矩阵播放「贴入瞬间 → 贴边常驻」，拖离边缘回待机。
     *
     * 【为什么不复用 FramePreview】它是静态播放组件，只负责循环帧；
     *   拖动需要手势状态机（idle / dragging / edgeIn / edgeLoop），
     *   混在一起会让「仅展示」的调用点也被迫接受手势参数。
     *
     * 【触摸边界】手势只在本组件内部生效（23.4.5）。
     *   页面纵向滚动由外层 Scrollable 处理，这里用 detectDragGestures
     *   并 consume 掉，避免父级横向手势与拖动竞争。
     */
    @Composable
    fun DragPreview(
        files: List<String>,
        sizePercent: Int,
        offsetX: Float,
        offsetY: Float,
        edgeInFiles: List<String>?,
        edgeLoopFiles: List<String>?,
        onOffsetChange: (Float, Float) -> Unit
    ) {
        val c = DhTokens.colors
        // 拖到边缘时用的素材：贴入 → 贴边；缺哪个就降级。
        var atEdge by remember { mutableStateOf(false) }
        var edgePhase by remember { mutableStateOf(0) } // 0=无,1=贴入,2=贴边
        var dragX by remember { mutableStateOf(0f) }
        var dragY by remember { mutableStateOf(0f) }
        val activeFiles = when {
            atEdge && edgePhase == 1 && !edgeInFiles.isNullOrEmpty() -> edgeInFiles
            atEdge && !edgeLoopFiles.isNullOrEmpty() -> edgeLoopFiles
            atEdge && !edgeInFiles.isNullOrEmpty() -> edgeInFiles
            else -> files
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                .background(c.soft)
                .border(1.dp, c.line, RoundedCornerShape(DhKit.R_CARD.dp))
                .pointerInput(files) {
                    detectDragGestures(
                        onDragStart = { atEdge = false },
                        onDragEnd = {
                            // 松手时按最终 x 位置判定是否吸边。
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val half = dragX / w
                            atEdge = Math.abs(half) > 0.32f
                            edgePhase = if (atEdge) {
                                if (!edgeInFiles.isNullOrEmpty()) 1 else 2
                            } else 0
                            // 吸边后不再让角色自由漂浮，把偏移写回会话。
                            onOffsetChange(
                                Math.max(-0.5f, Math.min(0.5f, dragX / w)),
                                Math.max(-0.5f, Math.min(0.5f, dragY / size.height.toFloat()))
                            )
                            dragX = 0f
                            dragY = 0f
                        },
                        onDragCancel = {
                            dragX = 0f
                            dragY = 0f
                            atEdge = false
                            edgePhase = 0
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragX += amount.x
                            dragY += amount.y
                        }
                    )
                }
        ) {
            // 贴入阶段播完一遍后自动转贴边（19.3 矩阵第 2 行）。
            LaunchedEffect(atEdge, edgePhase) {
                if (atEdge && edgePhase == 1) {
                    val n = edgeInFiles?.size ?: 0
                    if (n > 0) {
                        kotlinx.coroutines.delay(
                            Math.max(60L, 1000L / Math.max(1, DollAnim.FPS_DEFAULT)) * n
                        )
                        edgePhase = 2
                    }
                }
            }
            val limit = Math.min(activeFiles.size, MAX_PREVIEW_FRAMES)
            var frame by remember(activeFiles) { mutableStateOf(0) }
            LaunchedEffect(activeFiles) {
                frame = 0
                if (limit > 1) {
                    while (true) {
                        kotlinx.coroutines.delay(
                            Math.max(40L, 1000L / Math.max(1, DollAnim.FPS_DEFAULT))
                        )
                        frame = (frame + 1) % limit
                    }
                }
            }
            val key = if (limit <= 0) 0 else frame % limit
            val bmp = remember(activeFiles, key) {
                val p = activeFiles.getOrNull(key)
                val decoded = if (p == null) null else
                    com.dollhouse.app.core.ImageDecode.file(p, reqWidth = 512, reqHeight = 512)
                decoded?.asImageBitmap()
            }
            val b = bmp
            if (b == null) {
                Text(
                    text = "这里会显示她的样子",
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
            } else {
                val k = Math.max(0.1f, sizePercent / 100f)
                val density = androidx.compose.ui.platform.LocalDensity.current
                Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = b,
                        contentDescription = "预览角色，可以拖动",
                        modifier = Modifier
                            .fillMaxSize(0.90f * k)
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    (dragX + offsetX * 40 * density.density).toInt(),
                                    (dragY + offsetY * 40 * density.density).toInt()
                                )
                            },
                        contentScale = ContentScale.Fit
                    )
                }
            }
            // 「可以拖动」提示：只在还没拖过时显示一次，不长期占位。
            if (!atEdge) {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DhKit.Icon(Icons.IC_GRIP, Design.SZ_XS, c.sub)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "可以拖动，拖到边缘看看",
                        color = c.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
        }
    }

    /**
     * 【R-087 / R-088】大小检查折叠面板。
     *
     * 默认折叠（不打扰正常导入），展开后提供：
     *   全部统一到待机 / 显示待机轮廓 / 手动微调（大小滑块 + 对齐脚底 + 对齐头顶 + 恢复自动）。
     *
     * 【为什么折叠】R-086 要求「不阻止使用」。一进来就把六个按钮摊开，
     *   等于把技术细节强加给不需要它的人。
     */
    @Composable
    fun SizeCheckPanel(
        session: ImportSession,
        onFixAll: () -> Unit,
        onAlignBottom: () -> Unit,
        onAlignTop: () -> Unit,
        onReset: () -> Unit
    ) {
        val c = DhTokens.colors
        var open by remember { mutableStateOf(false) }
        var outline by remember { mutableStateOf(false) }
        DhKit.Card {
            Row(
                modifier = Modifier.fillMaxWidth().pressable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                DhKit.Icon(Icons.IC_RULER, Design.SZ_SM, c.acc)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "大小检查",
                    modifier = Modifier.weight(1f),
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
                DhKit.Icon(
                    if (open) Icons.IC_CHEVRON_UP else Icons.IC_CHEVRON_DOWN,
                    Design.SZ_SM, c.sub
                )
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                DhKit.MiniChip("全部统一到待机", iconRes = Icons.IC_LIST_CHECKS) { onFixAll() }
                Spacer(Modifier.height(6.dp))
                DhKit.MiniChip(
                    if (outline) "隐藏待机轮廓" else "显示待机轮廓",
                    iconRes = Icons.IC_SCAN_LINE
                ) { outline = !outline }
                if (outline) {
                    DhForm.Note("待机轮廓会叠在预览上，方便对齐。")
                }
                Spacer(Modifier.height(6.dp))
                DhKit.SectionLabel("手动微调", iconRes = Icons.IC_PEN_TOOL)
                DhKit.SliderRow(
                    title = "动作大小",
                    hint = "当前 " + session.sizeDraft + "%",
                    max = 400,
                    value = session.sizeDraft
                ) { v, _ ->
                    session.sizeDraft = Math.max(30, Math.min(400, v))
                }
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        DhKit.MiniChip("对齐脚底", iconRes = Icons.IC_FOOTPRINTS) { onAlignBottom() }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        DhKit.MiniChip("对齐头顶", iconRes = Icons.IC_ALIGN_START_VERTICAL) { onAlignTop() }
                    }
                }
                Spacer(Modifier.height(6.dp))
                DhKit.MiniChip("恢复自动", iconRes = Icons.IC_ROTATE_CCW) { onReset() }
            }
        }
    }

}