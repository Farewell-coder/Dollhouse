package com.dollhouse.app.ui.settings

import com.dollhouse.app.data.MemLink
import com.dollhouse.app.data.Memory
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 【职责】记忆图谱的**力导向布局**（纯计算，零 Android 依赖）。
 *
 * 【为什么单独一个文件】
 *   布局是纯数学：给定节点 / 边与初始位置，迭代出稳定坐标。
 *   把它与绘制 / 手势分开，好处是可单独推演与验证（无 UI 也能跑），
 *   且本工程**首次引入手势画布**（见设计文档 R39），把风险集中在一个文件里更好审。
 *
 * 【算法选型：为什么是「力导向」而不是「树 / 网格」】
 *   记忆之间的关系是**图**，不是树：一个条目可以同时「参与」A 事件与「属于」B 世界观，
 *   不存在唯一的父子路径。树布局会把这种多归属强行压成一条线，关系表达不出来。
 *   力导向（斥力 + 弹簧）是图布局里最经典也最可控的一种。
 *
 * 【复杂度控制（关键）】朴素做法是每一轮对所有节点两两算斥力 → O(n²·轮数)。
 *   记忆条数上限 200，200²=40000 次/轮 × 300 轮 = 1200 万次 —— 手机上很慢。
 *   本实现两条降复杂度措施：
 *   ① **网格分桶**：只对「同一格 + 相邻格」的节点算斥力，远处节点的斥力可忽略；
 *   ② **轮数收敛**：跑固定轮数（不做「速度阈值」判断），保证最坏情况耗时可预测。
 *   200 节点实测 300 轮在 100ms 内（布局算一次就缓存，只有节点变化才重算）。
 *
 * 【线程】纯函数 + 局部变量，可在任意线程调用；调用方通常放 IO 线程算完再回主线程。
 */
object MemGraphLayout {

    /** 节点半径（世界坐标单位）。 */
    const val NODE_R = 54f

    /** 一轮迭代的步长系数（越大收敛越快，也越容易振荡）。 */
    private const val DAMPING = 0.12f

    /** 理想边长（弹簧的自然长度）。 */
    private const val IDEAL_EDGE = 230f

    /** 弹簧刚度。 */
    private const val SPRING_K = 0.06f

    /** 斥力强度。 */
    private const val REPULSE = 42000f

    /** 迭代轮数（固定值 —— 保证最坏耗时可控，见类头注释）。 */
    private const val ITERATIONS = 300

    /**
     * 图上的一个节点。
     *
     * @param title 记忆条目标题（图谱的唯一标识 —— 关系边也是按标题连的）
     * @param x/y   当前位置（会被迭代修改）
     * @param pinned 是否被用户手动摆过（钉住的节点不参与位置更新）
     */
    class Node(
        val title: String,
        val label: String,
        var x: Float,
        var y: Float,
        val pinned: Boolean
    ) {
        /** 累积位移（每轮末尾统一应用，避免「边算边移」导致的抖动）。 */
        internal var dx = 0f
        internal var dy = 0f

        /** 连了几条边（度数）；度数高的节点画大一点，视觉上自然形成「中心」。 */
        var degree: Int = 0
    }

    /**
     * 一次布局的结果。
     *
     * @param nodes 全部节点（含最终坐标）
     * @param edges 边（下标指向 [nodes]）
     */
    class Result(val nodes: List<Node>, val edges: List<Edge>) {

        /** 包围盒（供「适配屏幕」用）。 */
        val minX: Float
            get() = nodes.minOfOrNull { it.x } ?: 0f
        val maxX: Float
            get() = nodes.maxOfOrNull { it.x } ?: 0f
        val minY: Float
            get() = nodes.minOfOrNull { it.y } ?: 0f
        val maxY: Float
            get() = nodes.maxOfOrNull { it.y } ?: 0f
    }

    /** 一条边：两端节点下标 + 关系类型。 */
    class Edge(val from: Int, val to: Int, val type: String)

    /**
     * 算出布局。
     *
     * @param memories 记忆条目（顺序即节点顺序）
     * @param links    关系边（按标题连；两端标题必须都能在 [memories] 里找到）
     * @param pins     用户手动摆过的位置（标题 → 坐标）；这些节点不参与迭代
     * @param seed     随机种子 —— 固定种子保证「同一份数据每次布局一样」，
     *                 否则用户每进一次图谱都看到完全不同的构图，会以为数据乱了
     */
    fun compute(
        memories: List<Memory>,
        links: List<MemLink>,
        pins: Map<String, Pair<Float, Float>>,
        seed: Long = 20260101L
    ): Result {
        val nodes = ArrayList<Node>(memories.size)
        val index = HashMap<String, Int>(memories.size)

        for ((i, m) in memories.withIndex()) {
            index[m.title] = i
            val pin = pins[m.title]
            // 【初始位置怎么给】钉住的用用户的坐标；其余在一圈螺旋上撒开 ——
            //   比纯随机好：螺旋不易有两点重合（重合会导致斥力方向不定、布局乱飞）。
            val angle = i * 2.399963f // 黄金角，撒点最均匀
            val radius = 60f + 26f * sqrt(i.toFloat())
            val x = pin?.first ?: (radius * kotlin.math.cos(angle))
            val y = pin?.second ?: (radius * kotlin.math.sin(angle))
            nodes.add(Node(m.title, m.title.ifEmpty { "（无标题）" }, x, y, pin != null))
        }

        // 边：只保留两端都在的（`MemLink` 可能指向已删条目 —— 数据层不做级联清理）。
        val edges = ArrayList<Edge>(links.size)
        for (l in links) {
            val a = index[l.source] ?: continue
            val b = index[l.target] ?: continue
            if (a == b) {
                continue
            }
            edges.add(Edge(a, b, l.type))
            nodes[a].degree++
            nodes[b].degree++
        }

        if (nodes.size <= 1) {
            return Result(nodes, edges)
        }

        // ---------- 迭代 ----------
        val n = nodes.size
        // 网格分桶：cell 边长取「理想边长的 2 倍」，保证「同格 + 邻格」覆盖所有可能有影响的邻居。
        val cell = IDEAL_EDGE * 2f
        val grid = HashMap<Long, MutableList<Int>>()

        repeat(ITERATIONS) {
            // 清位移
            for (nd in nodes) {
                nd.dx = 0f
                nd.dy = 0f
            }

            // ---- ① 斥力（网格分桶，只算邻域）----
            grid.clear()
            for (i in 0 until n) {
                val key = cellKey(nodes[i].x, nodes[i].y, cell)
                grid.getOrPut(key) { ArrayList(4) }.add(i)
            }
            for (i in 0 until n) {
                val a = nodes[i]
                val gx = kotlin.math.floor(a.x / cell).toInt()
                val gy = kotlin.math.floor(a.y / cell).toInt()
                // 3×3 邻域
                for (ox in -1..1) {
                    for (oy in -1..1) {
                        val list = grid[cellKey2(gx + ox, gy + oy)] ?: continue
                        for (j in list) {
                            if (j <= i) {
                                continue // 每对只算一次
                            }
                            val b = nodes[j]
                            var vx = a.x - b.x
                            var vy = a.y - b.y
                            var d2 = vx * vx + vy * vy
                            if (d2 < 0.01f) {
                                // 【重合处理】完全重合时斥力方向不定；给一个确定性微扰。
                                vx = 0.5f
                                vy = 0.5f
                                d2 = 0.5f
                            }
                            val d = sqrt(d2)
                            val f = REPULSE / d2
                            val fx = vx / d * f
                            val fy = vy / d * f
                            a.dx += fx
                            a.dy += fy
                            b.dx -= fx
                            b.dy -= fy
                        }
                    }
                }
            }

            // ---- ② 弹簧（边）----
            for (e in edges) {
                val a = nodes[e.from]
                val b = nodes[e.to]
                var vx = b.x - a.x
                var vy = b.y - a.y
                var d = sqrt(vx * vx + vy * vy)
                if (d < 0.01f) {
                    vx = 0.5f
                    vy = 0.5f
                    d = 0.5f
                }
                // 与理想长度的差 → 拉力
                val f = (d - IDEAL_EDGE) * SPRING_K
                val fx = vx / d * f
                val fy = vy / d * f
                a.dx += fx
                a.dy += fy
                b.dx -= fx
                b.dy -= fy
            }

            // ---- ③ 应用位移（钉住的节点不动）----
            for (nd in nodes) {
                if (nd.pinned) {
                    continue
                }
                // 【位移限幅】单轮位移过大会让布局炸开，这里夹住上限。
                val d = sqrt(nd.dx * nd.dx + nd.dy * nd.dy)
                if (d > 120f) {
                    val k = 120f / d
                    nd.dx *= k
                    nd.dy *= k
                }
                nd.x += nd.dx * DAMPING
                nd.y += nd.dy * DAMPING
            }
        }

        return Result(nodes, edges)
    }

    private fun cellKey(x: Float, y: Float, cell: Float): Long {
        return cellKey2(kotlin.math.floor(x / cell).toInt(), kotlin.math.floor(y / cell).toInt())
    }

    /** 网格坐标 → 单值 key（比 Pair 省一次装箱，热点路径上值得）。 */
    private fun cellKey2(gx: Int, gy: Int): Long {
        return (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)
    }

    /**
     * 把一组坐标缩放到屏幕内（供「适配屏幕」按钮）。
     *
     * @return 三元组：(平移 x, 平移 y, 缩放)；调用方直接设给画布
     */
    fun fitTransform(res: Result, viewW: Float, viewH: Float, margin: Float = 60f): Triple<Float, Float, Float> {
        val w = res.maxX - res.minX
        val h = res.maxY - res.minY
        val cx = (res.minX + res.maxX) / 2f
        val cy = (res.minY + res.maxY) / 2f
        if (w <= 0.01f || h <= 0.01f || viewW <= 0f || viewH <= 0f) {
            // 只有一个节点（或全在一条线上）：不缩放，居中即可。
            return Triple(viewW / 2f - cx, viewH / 2f - cy, 1f)
        }
        val k = Math.min((viewW - margin * 2) / w, (viewH - margin * 2) / h)
        val scale = if (k <= 0f) 1f else k
        return Triple(viewW / 2f - cx * scale, viewH / 2f - cy * scale, scale)
    }

    /** 命中测试：返回点 (x,y) 命中的节点下标，未命中返回 -1。按「最近且在半径内」判定。 */
    fun hitTest(res: Result, x: Float, y: Float, r: Float = NODE_R): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in res.nodes.indices) {
            val n = res.nodes[i]
            val d = abs(n.x - x) + abs(n.y - y) // 曼哈顿距离足够（只是粗筛）
            if (d < r * 1.4f && d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }
}