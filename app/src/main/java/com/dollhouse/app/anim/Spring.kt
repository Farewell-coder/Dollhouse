package com.dollhouse.app.anim

/**
 * 阻尼谐振子（弹簧）积分器。
 *
 * 【来源】移植自 morphicons（MIT，<https://github.com/guillermolg00/morphicons>）
 *   的 src/core/spring.ts（48 行）。原实现用于图标形变的进度驱动，此处作为全 App
 *   统一的动效进度源。
 *
 * 【算法】ẍ = k·(1−x) − c·ẋ，用半隐式欧拉积分，子步 h = 1/240 s。
 *   子步数 clamp 到 [1,16]：dt 过大（后台切回）时不会因单步过大而发散。
 *   稳定性边界 ω·h ≲ 2，以 k=420 计 ω≈20.5、ω·h≈0.085，余量充足。
 *
 * 【可中断】start() 把 x 归零但**保留当前速度**（clamp ±14）——
 *   连续触发时不会出现「弹到一半被硬拽回起点」的顿挫。
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


    /** 重新起跳：进度归零，速度保留并 clamp。 */
    fun start() {
        this.x = 0.0
        if (this.v > 14.0) {
            this.v = 14.0
        }
        if (this.v < -14.0) {
            this.v = -14.0
        }
    }

    /** 直接落到终态（不播动画）。 */
    fun settle() {
        this.x = 1.0
        this.v = 0.0
    }

    /**
     * 按 dt 秒推进。
     *
     * @return true 表示已收敛（|1−x| < 0.001 且 |v| < 0.02），调用方可停止驱动。
     */
    fun step(dt: Double): Boolean {
        if (dt <= 0.0) {
            return isSettled()
        }
        val h = 1.0 / 240.0
        var steps = Math.ceil(dt / h).toInt()
        if (steps < 1) {
            steps = 1
        }
        if (steps > 16) {
            steps = 16
        }
        val s = dt / steps
        for (i in 0 until steps) {
            val a = this.k * (1.0 - this.x) - this.c * this.v
            this.v += a * s
            this.x += this.v * s
        }
        return isSettled()
    }

    fun isSettled(): Boolean {
        return Math.abs(1.0 - this.x) < 0.001 && Math.abs(this.v) < 0.02
    }

}
