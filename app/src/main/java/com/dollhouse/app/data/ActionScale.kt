package com.dollhouse.app.data

/**
 * 【职责】动作帧 → 窗口的**摆放换算**（纯函数，可 JVM 单测）。
 *
 * ## 要解决的问题（需求书第五条第 3/4/9 点）
 *
 * 改造前播放层用**宽度**当尺子：`scale = 窗口宽 / 本体宽px`。
 * 这在「站立」帧上没问题，但一套动作里只要有一帧**手臂外伸 / 披风张开**，
 * 整组帧的本体包围盒就被撑宽 → 尺度变小 → 播放这套动作时人偶整体**变小**。
 * 用户看到的现象就是「切换动作时人偶突然变小 / 忽大忽小」。
 *
 * 需求书明确：
 * - 5.3 不允许每一帧独立自动缩放；
 * - 5.4 不允许动作切换时根据当前帧内容边界反复计算基础缩放；
 * - 5.9 对超出画布的内容采用合理的边界策略，不能因此导致人偶在播放过程中突然缩小；
 * - 9 重点验收：角色在切换动作时不能因素材边界差异突然改变基础显示大小。
 *
 * ## 修法：以「本体高度」为唯一尺子
 *
 * 人偶的**视觉大小**由身高决定，姿态变化主要改变**宽度**（挥手、张开、坐下）。
 * 于是：
 * - 基准尺度 = 窗口高 / 本体高px —— 任何动作的本体都占满同样的窗口高度；
 * - 宽度只作**上界保护**（本体横向不得超出窗口宽），绝不参与「放大」。
 *
 * 这样「跳跃（画布高）」「呼吸（画布矮）」「挥手（画布宽）」在桌面上都是同一个尺寸，
 * 且手臂外伸**只**表现为横向溢出（透明区，不裁不缩）。
 *
 * ## 为什么单独成文件
 *
 * 换算要能被单测钉死（见 `ActionScaleTest`），而 [com.dollhouse.app.pet.ActionPlayerCore]
 * 依赖 Canvas / Bitmap，只能在设备上跑。把纯算术抽出来，规则改一处、测试同步跟上。
 */
object ActionScale {

    /**
     * 本体高度占比的安全下限。
     *
     * 【为什么需要】基准里的本体高是「本体高 / 画布高」。若某组帧的包围盒只有画布高度的
     *   3%，按它放大就等于把画面拉高 33 倍 —— 内存与绘制都受不了，而且几乎必然是坏数据
     *   （全透明帧、识别失败）。夹到 10% 后最坏放大 10 倍，仍在可接受范围。
     */
    private const val MIN_BODY_FRAC = 0.10f

    /** 画面高度的上界倍数（相对窗口高），用于兜住极端比例素材。 */
    private const val MAX_FRAME_H_RATIO = 6.0f

    /**
     * 本体横向允许溢出窗口的倍数。
     *
     * 【为什么允许溢出而不是「塞进窗口」】第一版这里取 1.0（本体宽必须 ≤ 窗口宽），
     *   单测立刻报出问题：**挥手帧的本体宽是站立帧的 3.6 倍**，按 1.0 上界会把它压成
     *   站立时高度的 79% —— 人偶在挥手瞬间整只缩小，正好是需求书 5.9
     *   明令禁止的「因超出画布而突然缩小」。
     *
     *   需求 5.5 允许「挥手、张开」自然改变占用空间：横向多一点溢出（被窗口裁掉一小截
     *   透明边缘或手臂尖端）是可以接受的，**整只变小不可接受**。
     *   所以这里只挡住**极端**素材（横长条图、本体被误识别成整张宽图），
     *   正常姿态一律不触发。
     */
    private const val WIDTH_OVERFLOW = 2.0f

    /**
     * 计算一帧在窗口里的目标矩形。
     *
     * @param cw  窗口宽（px）
     * @param ch  窗口高（px）
     * @param bw  帧图宽（px）
     * @param bh  帧图高（px）
     * @param bl  本体基准 `[left, top, right, bottom]`（相对画布 0..1）；非法 / null 表示没有基准
     * @return `intArrayOf(left, top, right, bottom)`；null 表示调用方应跳过绘制
     */
    fun place(cw: Int, ch: Int, bw: Int, bh: Int, bl: FloatArray?): IntArray? {
        if (cw <= 0 || ch <= 0 || bw <= 0 || bh <= 0) {
            return null
        }
        if (!valid(bl)) {
            return contain(cw, ch, bw, bh)
        }
        val l = bl!![0]
        val t = bl[1]
        val r = bl[2]
        val b = bl[3]
        // 本体在**像素**上的尺寸（帧图坐标系）。
        val bodyWpx = (r - l) * bw
        val bodyHpx = (b - t) * bh
        if (bodyWpx <= 0f || bodyHpx <= 0f) {
            return contain(cw, ch, bw, bh)
        }
        // ① 主尺：本体高 = 窗口高。
        var scale = ch / bodyHpx
        // ② 极端宽图的保护：允许横向溢出 [WIDTH_OVERFLOW] 倍，再宽就一定是素材有问题。
        //   【不要收成 1.0】那会让「挥手」这套动作整体缩小（见 [WIDTH_OVERFLOW] 的说明），
        //   而需求书 5.9 明确要求「不能因此导致人偶在播放过程中突然缩小」。
        val maxScaleByW = cw * WIDTH_OVERFLOW / bodyWpx
        if (scale > maxScaleByW) {
            scale = maxScaleByW
        }
        // ③ 画面整体高度的上界（兜住「画布极扁 + 本体极小」的坏数据）。
        val maxByH = ch * MAX_FRAME_H_RATIO / bh
        if (scale > maxByH) {
            scale = maxByH
        }
        if (!scale.isFinite() || scale <= 0f) {
            return contain(cw, ch, bw, bh)
        }
        val dw = Math.round(bw * scale)
        val dh = Math.round(bh * scale)
        // 对齐：本体水平居中 + 本体底边贴窗口底（脚踩在窗口底，与静态精灵的 pivot 同源）。
        val bodyCx = (l + r) / 2f * dw
        val bodyBottom = b * dh
        val left = Math.round(cw / 2f - bodyCx)
        val top = Math.round(ch - bodyBottom)
        return intArrayOf(left, top, left + dw, top + dh)
    }

    /**
     * 无基准时的兜底：整张画布等比塞进窗口（= 改造前的旧行为）。
     *
     * 【为什么保留 contain 而不是「按高度铺满」】没有基准就没有「本体在哪」的信息，
     *   按高度铺满会把「竖长画布」的素材拉得极大。contain 至少不会溢出窗口，
     *   与旧版本逐像素一致 —— 导入流程现在会给每套帧写基准（`FrameScope.computeBaseline`），
     *   走到这里的只剩旧数据。
     */
    fun contain(cw: Int, ch: Int, bw: Int, bh: Int): IntArray? {
        if (cw <= 0 || ch <= 0 || bw <= 0 || bh <= 0) {
            return null
        }
        val s = Math.min(cw.toFloat() / bw, ch.toFloat() / bh)
        if (!s.isFinite() || s <= 0f) {
            return null
        }
        val dw = Math.round(bw * s)
        val dh = Math.round(bh * s)
        val left = (cw - dw) / 2
        val top = (ch - dh) / 2
        return intArrayOf(left, top, left + dw, top + dh)
    }

    /** 基准是否可用（4 个值、宽高为正、落在 0..1 附近）。 */
    fun valid(bl: FloatArray?): Boolean {
        if (bl == null || bl.size != 4) {
            return false
        }
        val l = bl[0]
        val t = bl[1]
        val r = bl[2]
        val b = bl[3]
        if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !b.isFinite()) {
            return false
        }
        if (r - l <= 0f || b - t <= 0f) {
            return false
        }
        // 高度占比过小视为坏数据（见 [MIN_BODY_FRAC]）。
        if (b - t < MIN_BODY_FRAC) {
            return false
        }
        return l >= -0.05f && t >= -0.05f && r <= 1.05f && b <= 1.05f
    }
}