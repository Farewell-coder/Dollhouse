package com.dollhouse.app.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 【职责】记忆图谱的**手势画布**（r14·需求 2 的核心）。
 *
 * 【为什么是这个工程首次引入手势画布】本工程此前从未有「拖拽 + 双指缩放」的画布：
 *   页面全是列表 / 卡片，手势只有点击与长按。图谱必须要有这些手势，所以这是新能力，
 *   风险集中在本文件与 `MemGraphLayout`（见设计文档 R39）。
 *
 * 【为什么用自绘 View 而不是 Compose Canvas】
 *   ① 图谱要**跟手**：拖动一帧都不能掉；自绘 View 的 `onDraw` + `invalidate` 路径最短，
 *      没有 Compose 的重组 / 快照开销。
 *   ② 与既有工程一致：本工程的桌宠、滑条、图标都是自绘 View，画布沿用同一套手艺。
 *
 * 【手势模型（关键设计）】
 *   - **单指拖动**：命中节点 → 拖该节点（并把它钉住）；未命中 → 平移整个画布。
 *     【为什么这样分】这是所有图编辑器的通用约定（点空白是移动视图、点节点是移动它），
 *     用户不需要学。
 *   - **双指捏合**：缩放，同时按两指中点平移（保证「捏哪里就缩哪里」）。
 *   - **点击**（按下与抬起位置几乎没动）：命中节点 → 通知外部打开详情。
 *
 * 【坐标系统】世界坐标（布局算出的）→ 屏幕坐标：
 *   `screen = world * scale + offset`
 *   所有命中测试都要先把屏幕点换算回世界坐标，否则缩放后点不准。
 *
 * 【性能】`onDraw` 里零分配（Paint / 数组全部复用）；文字用 `drawText` 一次性画。
 *   节点数上限 200，一帧 400+ 次绘制调用，实测无掉帧。
 */
class MemGraphView(context: Context) : View(context) {

    /** 点击（未拖动）命中节点时的回调（传节点标题）。 */
    var onNodeClick: ((String) -> Unit)? = null
    /** 【r19·长按删除】长按节点时回调标题（用于弹删除确认）。 */
    var onNodeLongClick: ((String) -> Unit)? = null

    /** 点击空白处（用来收起详情 / 关闭搜索高亮）。 */
    var onBlankClick: (() -> Unit)? = null

    /** 拖动节点结束后回调（标题 → 世界坐标），供调用方持久化。 */
    var onNodeMoved: ((String, Float, Float) -> Unit)? = null

    /** 视图状态变化的回调（缩放 / 平移），供「适配屏幕」等按钮同步。 */
    var onViewChanged: (() -> Unit)? = null

    private var result: MemGraphLayout.Result? = null

    /** 世界 → 屏幕：缩放。 */
    private var scale = 1f
    /** 世界 → 屏幕：平移。 */
    private var offsetX = 0f
    private var offsetY = 0f

    /** 高亮的标题（搜索结果），null = 无。 */
    private var highlight: String? = null

    // ---------------- 手势状态 ----------------
    private var dragging = -1          // 正在拖的节点下标；-1 = 没在拖节点
    private var panning = false        // 是否在平移画布
    private var lastX = 0f
    private var lastY = 0f
    /** 按下时的位置 + 是否已经「动了」（用于区分点击与拖动）。 */
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    /** 【r19·长按删除】长按计时任务（500ms 未移动触发）。 */
    private var longPressJob: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---------------- 双指缩放状态 ----------------
    private var pinch = false
    private var pinchStartDist = 0f
    private var pinchStartScale = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f

    private val density = context.resources.displayMetrics.density

    // ---------------- 画笔（全部复用，onDraw 里零分配） ----------------
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val edgeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val nodeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    /** 主题色（由外部灌入；本类不依赖 UiKit，保持可测）。 */
    private var colEdge = 0xFFCCCCCC.toInt()
    private var colEdgeLabel = 0xFF999999.toInt()
    private var colNodeBg = 0xFFFFFFFF.toInt()
    private var colNodeStroke = 0xFF999999.toInt()
    private var colNodeText = 0xFF222222.toInt()
    private var colAccent = 0xFF6B4EE6.toInt()
    private var colHighlightBg = 0xFFEDE7FF.toInt()

    /**
     * 灌入主题色。
     * 【为什么参数收 Compose 的 Color 而不是 Int】调用点是 Compose 代码（`DhTokens.colors`），
     *   收 Color 可以免掉 7 处 `.toArgb()` 噪音；本类内部只把它转成 Int。
     * 【为什么不让本类直接读 UiKit】那样它就无法在无 Android 主题的环境里被推演；
     *   而且颜色是「一次性灌入、换主题时再灌」，比每帧现读更省。
     */
    fun setPalette(
        edge: androidx.compose.ui.graphics.Color, edgeLabel: androidx.compose.ui.graphics.Color,
        nodeBg: androidx.compose.ui.graphics.Color, nodeStroke: androidx.compose.ui.graphics.Color,
        nodeText: androidx.compose.ui.graphics.Color,
        accent: androidx.compose.ui.graphics.Color, highlightBg: androidx.compose.ui.graphics.Color
    ) {
        colEdge = edge.toArgb()
        colEdgeLabel = edgeLabel.toArgb()
        colNodeBg = nodeBg.toArgb()
        colNodeStroke = nodeStroke.toArgb()
        colNodeText = nodeText.toArgb()
        colAccent = accent.toArgb()
        colHighlightBg = highlightBg.toArgb()
        invalidate()
    }

    /** 设置（或替换）布局结果，并把视角适配到屏幕。 */
    fun setGraph(res: MemGraphLayout.Result?, fit: Boolean = true, keepView: Boolean = false) {
        val oldScale = scale
        val oldOx = offsetX
        val oldOy = offsetY
        result = res
        if (res == null) {
            invalidate()
            return
        }
        if (fit || !keepView) {
            fitToView()
        } else {
            // 【为什么保留视角】拖动一个节点后重建结果时，用户不想视角被重置 ——
            //   那会让他「刚摆好的位置又飞走了」。
            scale = oldScale
            offsetX = oldOx
            offsetY = oldOy
        }
        invalidate()
        onViewChanged?.invoke()
    }

    /** 把整个图缩放到屏幕内（「适配屏幕」按钮 / 首次进入）。 */
    fun fitToView() {
        val res = result ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            // 【尺寸还没量到】此时 fit 没有意义（w/h 为 0 会算出 NaN）。
            //   在 onSizeChanged 里会再调一次。
            return
        }
        val t = MemGraphLayout.fitTransform(res, w, h)
        offsetX = t.first
        offsetY = t.second
        scale = t.third
        onViewChanged?.invoke()
    }

    /** 设置搜索高亮的节点标题（null = 取消高亮）。 */
    fun setHighlight(title: String?) {
        highlight = title
        if (title != null) {
            centerOn(title)
        }
        invalidate()
    }

    /** 把某个节点移到屏幕中央（搜索结果点击后调用）。 */
    fun centerOn(title: String?) {
        val res = result ?: return
        if (title == null) {
            return
        }
        val n = res.nodes.firstOrNull { it.title == title } ?: return
        offsetX = width / 2f - n.x * scale
        offsetY = height / 2f - n.y * scale
        onViewChanged?.invoke()
        invalidate()
    }

    /** 当前缩放（供外部显示 / 保存）。 */
    fun currentScale(): Float = scale
    fun currentOffsetX(): Float = offsetX
    fun currentOffsetY(): Float = offsetY

    /**
     * 【r14】以**画布中心**为锚设置缩放（工具条的「放大 / 缩小」按钮用）。
     * 【与手势的区别】手势是「以捏合点为锚」（捏哪缩哪）；按钮没有触点，
     *   以屏幕中心为锚是最符合直觉的选择（看到的中心内容保持在中心）。
     */
    fun setZoomAtCenter(ns: Float) {
        val s = max(0.25f, min(3f, ns))
        val cx = width / 2f
        val cy = height / 2f
        val wx = (cx - offsetX) / scale
        val wy = (cy - offsetY) / scale
        scale = s
        offsetX = cx - wx * s
        offsetY = cy - wy * s
        invalidate()
        onViewChanged?.invoke()
    }

    /** 恢复视角（跨会话保存的）。传 0 表示无效。 */
    fun restoreView(s: Float, ox: Float, oy: Float): Boolean {
        if (s <= 0.01f) {
            return false
        }
        scale = s
        offsetX = ox
        offsetY = oy
        invalidate()
        onViewChanged?.invoke()
        return true
    }

    /** 钉住的节点坐标（取全部节点的当前坐标，交给调用方挑着存）。 */
    fun nodePositions(): Map<String, Pair<Float, Float>> {
        val res = result ?: return emptyMap()
        val out = HashMap<String, Pair<Float, Float>>(res.nodes.size)
        for (n in res.nodes) {
            out[n.title] = n.x to n.y
        }
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 【首次量到尺寸】setGraph 时 width/height 还是 0，fit 被跳过了；这里补一次。
        if (oldw == 0 || oldh == 0) {
            fitToView()
        }
    }

    /* ============================ 触摸 ============================ */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val res = result ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                // 【命中优先】按下时先试节点（拖动节点 / 点空白平移，见类头注释）。
                val w = toWorldX(event.x)
                val h = toWorldY(event.y)
                val hit = MemGraphLayout.hitTest(res, w, h)
                if (hit >= 0) {
                    dragging = hit
                    // 【r19·长按删除】按下节点后启动长按计时（500ms 未移动触发）。
                    longPressJob?.let { handler.removeCallbacks(it) }
                    val job = Runnable {
                        if (!moved && dragging == hit) {
                            onNodeLongClick?.invoke(res.nodes[hit].title)
                        }
                    }
                    longPressJob = job
                    handler.postDelayed(job, 500)
                } else {
                    dragging = -1
                    panning = true
                }
                // 【必须 return true】否则多指手势（ACTION_POINTER_DOWN）不会派发到本 View。
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 【双指时取消节点拖动】否则缩放的同事会把节点带走（两个手势打架）。
                dragging = -1
                panning = false
                if (event.pointerCount >= 2) {
                    pinch = true
                    pinchStartDist = dist(event)
                    pinchStartScale = scale
                    pinchFocusX = (event.getX(0) + event.getX(1)) / 2f
                    pinchFocusY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pinch && event.pointerCount >= 2) {
                    val d = dist(event)
                    if (pinchStartDist > 1f && d > 1f) {
                        val k = d / pinchStartDist
                        // 【缩放范围限制】0.25~3 之外没有实际意义：再小看不清、再大全是空白。
                        val ns = max(0.25f, min(3f, pinchStartScale * k))
                        // 【以捏合中点为锚】保证「捏哪里就缩哪里」；否则图会往屏幕角落跑。
                        scaleAt(pinchFocusX, pinchFocusY, ns)
                    }
                    moved = true
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (abs(event.x - downX) > 8f * density || abs(event.y - downY) > 8f * density) {
                    moved = true
                }
                if (dragging >= 0) {
                    // 拖动节点：把屏幕位移换算世界位移（除以 scale）。
                    val n = res.nodes[dragging]
                    n.x += dx / scale
                    n.y += dy / scale
                    invalidate()
                } else if (panning) {
                    offsetX += dx
                    offsetY += dy
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                longPressJob?.let { handler.removeCallbacks(it) }
                val wasPinch = pinch
                val wasDragging = dragging
                val hitTitle: String?
                if (!moved) {
                    // 【点击判定】位移小于阈值才算点击 —— 手指天生会抖几个像素，
                    //   严格「零位移」会导致点击几乎永远不触发。
                    val w = toWorldX(event.x)
                    val h = toWorldY(event.y)
                    val idx = MemGraphLayout.hitTest(res, w, h)
                    hitTitle = if (idx >= 0) res.nodes[idx].title else null
                } else {
                    hitTitle = null
                }
                pinch = false
                dragging = -1
                panning = false
                if (wasPinch) {
                    return true
                }
                if (hitTitle != null) {
                    onNodeClick?.invoke(hitTitle)
                } else if (!moved) {
                    onBlankClick?.invoke()
                }
                if (wasDragging >= 0 && moved) {
                    // 【通知外部持久化】用户手动摆过的位置要记住（下次进来还在他放的地方）。
                    val n = res.nodes[wasDragging]
                    onNodeMoved?.invoke(n.title, n.x, n.y)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressJob?.let { handler.removeCallbacks(it) }
                pinch = false
                dragging = -1
                panning = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 以屏幕点 (px,py) 为锚，把缩放改成 [ns]。 */
    private fun scaleAt(px: Float, py: Float, ns: Float) {
        // 锚点世界坐标固定：screen = world * scale + offset
        // ⇒ world = (px - offsetX) / scale，缩放后 offset 要让同一个 world 仍落在 px 上。
        val wx = (px - offsetX) / scale
        val wy = (py - offsetY) / scale
        scale = ns
        offsetX = px - wx * ns
        offsetY = py - wy * ns
        invalidate()
        onViewChanged?.invoke()
    }

    private fun dist(e: MotionEvent): Float {
        if (e.pointerCount < 2) {
            return 0f
        }
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private fun toWorldX(sx: Float): Float = (sx - offsetX) / scale
    private fun toWorldY(sy: Float): Float = (sy - offsetY) / scale

    /* ============================ 绘制 ============================ */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = result ?: return
        if (res.nodes.isEmpty()) {
            return
        }
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // ---------- ① 边 ----------
        edgePaint.color = colEdge
        edgeLabelPaint.color = colEdgeLabel
        for (e in res.edges) {
            val a = res.nodes[e.from]
            val b = res.nodes[e.to]
            canvas.drawLine(a.x, a.y, b.x, b.y, edgePaint)
        }
        // 关系类型标签单独一轮画（先画线再画字，字才压在线上、不被线盖住）。
        for (e in res.edges) {
            val a = res.nodes[e.from]
            val b = res.nodes[e.to]
            val mx = (a.x + b.x) / 2f
            val my = (a.y + b.y) / 2f
            canvas.drawText(typeLabel(e.type), mx, my - 3f * density / scale, edgeLabelPaint)
        }

        // ---------- ② 节点 ----------
        nodeText.color = colNodeText
        // 文字大小跟随缩放（缩放后字仍然可读），但设一个下限避免小到看不见。
        nodeText.textSize = max(9f, 11f / scale) * density
        for (n in res.nodes) {
            val r = nodeRadius(n)
            val isHi = highlight != null && highlight == n.title
            nodeFill.color = if (isHi) colHighlightBg else colNodeBg
            canvas.drawCircle(n.x, n.y, r, nodeFill)
            nodeStroke.color = if (isHi) colAccent else colNodeStroke
            canvas.drawCircle(n.x, n.y, r, nodeStroke)

            // 度数高的节点加一圈强调描边（视觉上自然形成「中心」）
            if (n.degree >= 3) {
                nodeStroke.color = colAccent
                canvas.drawCircle(n.x, n.y, r + 2.5f * density / scale, nodeStroke)
            }
            drawNodeLabel(canvas, n, r)
        }

        canvas.restore()
    }

    /** 节点半径：随「连了几条边」轻微变大，但差距不夸张（否则小节点读不到）。 */
    private fun nodeRadius(n: MemGraphLayout.Node): Float {
        val base = MemGraphLayout.NODE_R * density / scale
        val boost = min(3, n.degree) * 3f * density / scale
        return base + boost
    }

    /**
     * 画节点文字（标题），两行折行。
     *
     * 【为什么手动折行】Canvas 的 `drawText` 不支持自动换行，标题又常常是短语。
     *   按「2 个字符一行、最多两行」切分：中文标题大多 4-8 字，这样能完整显示；
     *   超出部分的第二行末尾加省略号。
     */
    private fun drawNodeLabel(canvas: Canvas, n: MemGraphLayout.Node, r: Float) {
        val s = n.label
        if (s.isEmpty()) {
            return
        }
        val per = 4
        val line1: String
        val line2: String
        if (s.length <= per) {
            line1 = s
            line2 = ""
        } else {
            line1 = s.substring(0, per)
            line2 = if (s.length > per * 2) s.substring(per, per * 2 - 1) + "…" else s.substring(per)
        }
        val lh = nodeText.textSize + 2f * density / scale
        val totalH = if (line2.isEmpty()) 0f else lh
        val cx = n.x
        // 【基线怎么定】把文字整体在圆里垂直居中：首行基线 = 圆心 - 总高/2 + 字高*0.35（经验系数）
        val baseY = n.y - totalH / 2f + nodeText.textSize * 0.35f
        canvas.drawText(line1, cx, baseY, nodeText)
        if (line2.isNotEmpty()) {
            canvas.drawText(line2, cx, baseY + lh, nodeText)
        }
    }

    /** 关系类型 → 中文短标签（边上的小字）。 */
    private fun typeLabel(type: String): String {
        return when (type.uppercase()) {
            "FOLLOWS" -> "之后"
            "CORRECTS" -> "纠正"
            "UPDATES" -> "更新"
            "INVOLVES" -> "参与"
            "HAPPENS_AT" -> "发生于"
            "PART_OF" -> "属于"
            "ALLIED_WITH" -> "同一阵营"
            "OPPOSES" -> "对立"
            "RELATED", "" -> ""   // 默认关系不画标签（太占地方，且没有信息量）
            else -> type
        }
    }
}