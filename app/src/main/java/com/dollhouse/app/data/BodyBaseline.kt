package com.dollhouse.app.data

/**
 * 【职责】从一个动作的**整组帧图**里算出「人偶本体」在画布上的相对矩形（基准），
 * 让用户导入的动作和内置动作用同一把尺子量大小。
 *
 * ## 为什么必须有这一层
 *
 * 播放层（`ActionPlayerCore.draw`）有两条分支：
 * · **有基准**（内置动作烘帧时写入）：按「本体宽度 = 窗口宽度」缩放，底边对齐 —— 各种动作一样大；
 * · **没基准**（用户导入的帧组、旧帧）：`min(窗宽/图宽, 窗高/图高)`，**整张画布铺满窗口**。
 *
 * 于是用户导入的帧有多大画布，人偶就被缩成多大：画布大而人物小 → 人偶变小；
 * 画布小而人物大 → 人偶撑爆。这不是「调个参数」能解决的，
 * 缺的是「这张图里人偶本体占多少」这个信息 —— 本类负责把它算出来。
 *
 * ## 三条硬约束（来自需求）
 *
 * 1. **整组共用一个框**，不逐帧各算。
 *    逐帧算会让「挥手帧手臂外伸」把基准撑大、下一帧又缩回去，播放时人偶尺寸逐帧抖动。
 *    App 内置动作也是这么做的（`AnimBaker.sharedBounds` 取全体帧并集）。
 * 2. **不能靠裁剪/拉伸制造一致的假象**。本类只输出「框在哪」，不动像素。
 *    播放层据此缩放整张图，人物比例保持原样。
 * 3. **认不出来要有安全回退**。全透明、或各帧轮廓差异过大时返回 null，
 *    调用方退回旧行为（整画布铺满），宁可大小不完美也不要抖动或崩。
 *
 * ## 为什么不直接复用 `AnimBaker`
 *
 * 那边的基准是「用真实渲染管线烘出来的」，需要 `PetView` + `Bitmap`，只能在设备上跑；
 * 这边处理的是**已经存在的图片文件**，只读像素。两者口径必须一致（都是 0..1 的相对矩形），
 * 但实现不能共用 —— 所以这里做成纯逻辑，能在 JVM 单测里钉死。
 */
object BodyBaseline {

    /**
     * 一帧的透明包围盒，单位是**相对画布的比例**（0..1）。
     *
     * 用比例而不是像素：播放时帧图可能被采样过（解码尺寸变化），
     * 像素值会失效，比例与分辨率无关 —— 与 `AnimBaker` 存基准的口径一致。
     */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {

        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** 是否是可用的框（宽高都为正、且在 0..1 内）。 */
        fun isValid(): Boolean {
            if (!width.isFinite() || !height.isFinite()) {
                return false
            }
            if (width <= MIN_SIZE || height <= MIN_SIZE) {
                return false
            }
            return left >= -EPS && top >= -EPS && right <= 1f + EPS && bottom <= 1f + EPS
        }

        /** 与另一框的并集。 */
        fun union(o: Box): Box = Box(
            Math.min(left, o.left),
            Math.min(top, o.top),
            Math.max(right, o.right),
            Math.max(bottom, o.bottom)
        )

        /** 按 4 位小数序列化（与 `AnimBaker` 的 `FloatArray.joinToString` 同风格，便于人工核对）。 */
        fun encode(): String = fmt(left) + "," + fmt(top) + "," + fmt(right) + "," + fmt(bottom)

        private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.4f", v)

        companion object {
            /** 小于这个尺寸视为「没有内容」（0.5% 画布）。 */
            const val MIN_SIZE = 0.005f
            private const val EPS = 0.02f

            /** 解析 [encode] 的输出；格式不对返回 null（旧档 / 被改坏的档都走这里兜底）。 */
            fun decode(s: String?): Box? {
                if (s.isNullOrEmpty()) {
                    return null
                }
                val p = s.split(",")
                if (p.size != 4) {
                    return null
                }
                return try {
                    val b = Box(p[0].trim().toFloat(), p[1].trim().toFloat(),
                        p[2].trim().toFloat(), p[3].trim().toFloat())
                    if (b.isValid()) b else null
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }

    /**
     * 一组帧的单个包围盒（相对比例）。所有帧的只读快照，用来做判定。
     *
     * @param frames 每帧的框；**不得为空**
     * @param canvasW 画布宽（这套帧是否同尺寸由调用方保证；不同尺寸时调用方应先归一化）
     */
    fun merge(frames: List<Box>): Box? {
        if (frames.isEmpty()) {
            return null
        }
        var acc = frames[0]
        for (i in 1 until frames.size) {
            val b = frames[i]
            if (!b.isValid()) {
                // 单帧无效（全透明）：不计入并集，但也不因此整体失效 ——
                //   某帧偶然全透明是可能的（如淡出到空），不该连累整组。
                continue
            }
            acc = acc.union(b)
        }
        return if (acc.isValid()) acc else null
    }

    /**
     * 判定这组框是否「可靠」——不可靠时调用方应退回旧行为。
     *
     * 【两条拒绝理由】
     * ① **轮廓差异过大**：某帧的宽或高不到并集的 [MIN_SPREAD]。
     *    这种组多半混进了尺寸不同的图（或某帧只剩一个小色块），
     *    用一个框去套会让正常帧的人偶偏大/偏小。
     * ② **留白几乎没变**：所有帧的框都贴着画布边缘（并集覆盖 > [MAX_COVER]），
     *    说明图里根本没有可利用的透明留白 —— 这时候算出来的框≈整张画布，
     *    与旧行为等价，没必要写入基准（少写一个键，也让旧档更干净）。
     *
     * 【为什么阈值是可调常量而不是魔法数】这类判定将来一定需要微调
     * （不同用户的素材风格差异很大），集中在这里便于改一处、单测同时跟上。
     */
    fun isReliable(frames: List<Box>): Boolean {
        if (frames.isEmpty()) {
            return false
        }
        val merged = merge(frames) ?: return false
        // ① 逐帧尺寸离散度
        var minW = Float.MAX_VALUE
        var minH = Float.MAX_VALUE
        var counted = 0
        for (b in frames) {
            if (!b.isValid()) {
                continue
            }
            counted++
            minW = Math.min(minW, b.width)
            minH = Math.min(minH, b.height)
        }
        if (counted == 0) {
            return false
        }
        if (minW < merged.width * MIN_SPREAD || minH < merged.height * MIN_SPREAD) {
            return false
        }
        // ② 留白是否真的存在
        val cover = merged.width * merged.height
        if (cover > MAX_COVER) {
            return false
        }
        return true
    }

    /** 单帧尺寸不到并集的这个比例 → 判定为「混了不同尺寸的图」。 */
    const val MIN_SPREAD = 0.55f

    /** 并集面积占画布超过这个比例 → 判定为「没有可利用的留白」。 */
    const val MAX_COVER = 0.985f
}