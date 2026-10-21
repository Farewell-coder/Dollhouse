package com.dollhouse.app.anim

/**
 * 阻尼谐振子（弹簧）积分器。
 *
 * 【算法】ẍ = k·(1−x) − c·ẋ，用半隐式欧拉积分，子步 h ≤ 1/240 s。
 *
 * 【稳定性·本次修复的根因】半隐式欧拉的稳定边界是 ω·h ≲ 2。以本工程最强刚度
 *   k=420 计，ω = √k ≈ 20.5，故 h 必须 ≤ 2/20.5 ≈ 0.097 s。
 *   改造前这里是两条**会同时失效**的 clamp：
 *   ```
 *   var steps = ceil(dt / h)        // dt=2s → steps=480
 *   if (steps > 16) steps = 16      // 被砍到 16
 *   val s = dt / steps              // s = 2/16 = 0.125s → ω·h ≈ 2.56 > 2 → 发散
 *   ```
 *   即「子步数」被限制住了，但**子步长没有** —— steps 一旦被截断，
 *   真实步长就随 dt 线性变大，越过稳定边界后位移指数放大。
 *   用户侧表现：从后台切回前台、或某帧卡了一下之后，正在播的弹簧动画
 *   **飞到屏幕外**，紧接着被 onEnd 钉回终态 → 肉眼看到「闪一下 / 抖一下」。
 *   现在 [MAX_SUB_STEP] 与 [MAX_DT] 两条上限都在，`s` 恒 ≤ 1/240 s。
 *
 * 【可中断】start() 把 x 归零但**保留当前速度**（夹到 ±[MAX_V]）——
 *   连续触发时不会出现「弹到一半被硬拽回起点」的顿挫。
 *   [retarget] 则用于「从当前进度继续推向新终态」，中途不归零。
 *
 * 【纯数学】本类不引用任何 Android API，便于单测与复用。
 */
class Spring {

    /** 进度 0→1。初始 1 表示「已就位」。 */
    var x: Double = 1.0

    /** 速度。 */
    var v: Double = 0.0

    /** 刚度。 */
    var k: Double = 250.0

    /** 阻尼。 */
    var c: Double = 24.0

    constructor()

    constructor(k: Double, c: Double) {
        this.k = k
        this.c = c
    }

    /** 重新起跳：进度归零，速度保留并夹紧。 */
    fun start() {
        this.x = 0.0
        clampVelocity()
    }

    /**
     * 【新增】从当前位置继续推向终态 1，不归零。
     *
     * 【为什么需要】「按下 → 松手 → 再按下」这类连击场景，若每次都 start() 归零，
     *   位移会瞬间跳回起点再弹回来（肉眼就是「抖一下」）。retarget 保留 x，
     *   只把速度夹紧，视觉上是「从半途接着弹」。
     */
    fun retarget() {
        clampVelocity()
    }

    /** 直接落到终态（不播动画）。 */
    fun settle() {
        this.x = 1.0
        this.v = 0.0
    }

    /** 速度夹紧：防止打断时刻的极端速度把后续几帧推飞。 */
    private fun clampVelocity() {
        if (this.v > MAX_V) {
            this.v = MAX_V
        }
        if (this.v < -MAX_V) {
            this.v = -MAX_V
        }
    }

    /**
     * 按 dt 秒推进。
     *
     * @return true 表示已收敛（|1−x| < [SETTLE_X] 且 |v| < [SETTLE_V]），调用方可停止驱动。
     */
    fun step(dt: Double): Boolean {
        if (dt <= 0.0) {
            return isSettled()
        }
        // 【上限①】单帧 dt 封顶：切后台数秒再回来时首帧 dt 可能是好几秒。
        //   这不是「快进」，而是不该发生的时间跳跃 —— 按 66.7ms 处理即可，
        //   物理误差肉眼不可见，却能把发散风险彻底切掉。
        val d = if (dt > MAX_DT) MAX_DT else dt
        var steps = Math.ceil(d / SUB_STEP).toInt()
        if (steps < 1) {
            steps = 1
        }
        if (steps > MAX_STEPS) {
            steps = MAX_STEPS
        }
        // 【上限②】子步长再封一次：steps 被封顶后 d/steps 仍可能超出稳定边界，
        //   所以这里必须显式取 min，保证 s 恒 ≤ SUB_STEP。
        var s = d / steps
        if (s > MAX_SUB_STEP) {
            s = MAX_SUB_STEP
        }
        for (i in 0 until steps) {
            val a = this.k * (1.0 - this.x) - this.c * this.v
            this.v += a * s
            this.x += this.v * s
        }
        // 【有限性防护】任何原因（k 被外部改成非法值、dt 为 NaN）产生的 NaN/Inf
        //   都不能流出本类：x 一旦是 NaN，后续所有 setScale / setTranslation 全部失效，
        //   视图会「卡住不动且永不重绘」。这里直接落终态 —— 宁可不动，不能卡死。
        if (!isFinite(this.x) || !isFinite(this.v)) {
            settle()
            return true
        }
        return isSettled()
    }

    fun isSettled(): Boolean {
        return Math.abs(1.0 - this.x) < SETTLE_X && Math.abs(this.v) < SETTLE_V
    }

    companion object {
        /** 积分子步（秒）：1/240 s。 */
        private const val SUB_STEP = 1.0 / 240.0

        /** 子步长硬上限：与 SUB_STEP 同值，仅作「steps 被封顶后的第二道闸」。 */
        private const val MAX_SUB_STEP = SUB_STEP

        /** 单帧 dt 上限（秒）：约 1/15 s，覆盖任何正常卡顿。 */
        private const val MAX_DT = 0.0667

        /** 单帧最多积分多少子步（16 × 1/240 ≈ 66.7ms，与 MAX_DT 自洽）。 */
        private const val MAX_STEPS = 16

        /** 速度上限：14 单位/秒（进度是 0→1 的无量纲量，14 已是极快）。 */
        private const val MAX_V = 14.0

        /** 收敛阈值：位移 1e-3（0.1% 行程）。 */
        private const val SETTLE_X = 1e-3

        /** 收敛阈值：速度 2e-2（约 1.2 单位/帧，肉眼不可辨）。 */
        private const val SETTLE_V = 2e-2

        /** Double 是否有限。 */
        private fun isFinite(v: Double): Boolean {
            return !(v.isNaN() || v.isInfinite())
        }
    }
}