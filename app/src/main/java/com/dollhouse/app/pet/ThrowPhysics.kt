package com.dollhouse.app.pet

/**
 * 【职责】拖拽**甩抛**的纯算术：抬手瞬间由指针轨迹估初速，之后按重力 / 反弹 / 摩擦积分。
 *
 * ## 要解决的问题（需求书第四阶段）
 *
 * 改造前抬手就是「就地放下并吸附」：`PetWindow.settle()` 直接按最终位置算吸附边。
 * 无论甩得多快，人偶都**立刻停住** —— 拖拽手感是「拖一块木板」，没有重量感。
 *
 * 需求原文要求「甩抛惯性 / 屏幕边界约束 / 手势结束后的动作恢复」，
 * 同时明确「不为简单的桌宠移动引入大型物理引擎」。
 *
 * ## 为什么做成纯对象而不是塞进 PetWindow
 *
 * `PetWindow` 有 1700 行、依赖 WindowManager / MotionEvent / View，任何逻辑进去都只能真机试。
 * 这里全部是 `Float` 算术，能在 JVM 单测里把「速度估计 / 死区 / 反弹 / 静止判定」
 * 逐条钉死（见 `ThrowPhysicsTest`）。
 *
 * ## 参数为什么集中在这里
 *
 * 需求要求「将必要的物理参数集中管理，避免散落在多个类中」。
 * 全部常量 + [Params] 都在本文件，`PetWindow` 只调用、不定义数值。
 *
 * ## 刻意不做的事
 *
 * - **不做宠物互撞**：本应用桌面上可以有多只人偶，但用户从没要求它们互相弹开；
 *   加进来要多维护一套 AABB 相交与冲量分配，属于「没有明确使用场景的功能」。
 * - **不做多屏空洞 / 跨屏拼接**：Android 上人偶窗口固定在当前屏，
 *   `PetWindow` 本来就用屏幕 work area 夹取，没有「屏缝」这回事。
 * - **不做弹弓**：拉距弹射是桌面端双击第二下按住才有的玩法，Android 上双击已绑定
 *   手势动作，再造一条「双击+按住」会与现有手势冲突。
 */
object ThrowPhysics {

    /* ============================ 参数 ============================ */

    /**
     * 甩抛的物理参数。全部有默认值，宿主按需覆盖（当前一律用默认）。
     *
     * 【为什么给 data class 而不是一堆散常量】将来若要「总力度」滑条，
     *   一处传参即可，不必改调用链。
     */
    data class Params(
        /** 重力加速度（px/s²）。手感取值：偏大让抛出去很快落地，不像羽毛。 */
        val gravity: Float = GRAVITY,
        /** 碰边速度保留率（0..1）。0.78 ≈ 每次反弹保留约 78%。 */
        val restitution: Float = RESTITUTION,
        /** 落地后的水平摩擦（每秒衰减率）。 */
        val groundFriction: Float = GROUND_FRICTION,
        /** 总力度倍数（1.0 = 默认）。 */
        val throwPower: Float = 1f,
        /** 顶边是否也反弹（false = 顶到就停竖直速度贴住）。 */
        val ceilingBounce: Boolean = true
    )

    /* ============================ 常量 ============================ */

    /** 重力（px/s²）。 */
    const val GRAVITY = 1400f

    /** 碰边恢复系数。 */
    const val RESTITUTION = 0.78f

    /** 地面水平摩擦（/s）。 */
    const val GROUND_FRICTION = 2.5f

    /**
     * 低于此速度视为「不甩，原地放下」（px/s）。
     *
     * 【为什么需要死区】手指轻微抖动会被估速放大成几十 px/s，
     *   没有死区的话「轻轻一放」人偶会自己滑一小段，看起来像没抓稳。
     */
    const val DEAD_ZONE_SPEED = 500f

    /**
     * 甩出速度的软上限（px/s）。
     *
     * 【为什么用软钳制而不是硬夹】`cap·(1-e^(-s/cap))` 是单调的：
     *   无论用户甩得多猛，速度都还能区分出「更猛一点」，但永远不超过 cap。
     *   硬夹会让「很猛」和「极其猛」得到完全一样的初速，手感失真。
     */
    const val MAX_THROW_SPEED = 3600f

    /** 估算窗口（ms）：只看抬手前这一段的轨迹。 */
    const val RELEASE_WINDOW_MS = 150L

    /** 抬手前停顿超过它 = 温柔放下，不带残余速度（ms）。 */
    const val RELEASE_STALE_MS = 150L

    /** 窗口跨度过短视为不可估算（ms）。 */
    const val MIN_SPAN_MS = 20L

    /**
     * 分段速度的最小 dt（ms）。
     *
     * 【为什么】高回报率触摸屏的事件间隔可低至 1~2ms，
     *   用这么小的 dt 做除法会把坐标抖动放大成虚假的速度峰值。
     *   短段向前合并到 [MIN_SPAN_MS] 以上再算。
     */
    const val SEG_MIN_DT_MS = 8L

    /** 端点均值与窗口峰值的权重：峰值补偿「快甩时位移集中在窗口内一小段」的低估。 */
    const val PEAK_WEIGHT = 0.5f

    /** 落地时 |vy| 小于它直接停竖直（px/s）。 */
    const val REST_VY = 40f

    /** 地面上 |vx| 小于它认为已静止（px/s）。 */
    const val REST_VX = 15f

    /**
     * 单步最大 dt（秒）。
     *
     * 【为什么必须夹】系统卡顿 / 后台切回时两帧间隔可能是几百毫秒，
     *   按真实 dt 积分会让一步跨出屏幕好几屏，表现为「人偶突然飞走」。
     */
    const val MAX_STEP_DT = 0.05f

    /* ============================ 轨迹采样 ============================ */

    /**
     * 一次指针采样。
     *
     * @param t 采样时刻（`SystemClock.uptimeMillis()`）
     * @param x 人偶**轴心**的屏幕 x（不是手指位置 —— 手指按下时可能不在轴心上）
     * @param y 同上，屏幕 y
     */
    data class Sample(val t: Long, val x: Float, val y: Float)

    /**
     * 裁掉窗口外的旧采样。
     *
     * 【为什么在这里而不是采样时顺手删】采样回调里删要遍历一次列表；
     *   抬手才估速一次，那时裁一遍就够，热路径零额外开销。
     */
    fun trim(samples: List<Sample>, now: Long): List<Sample> {
        val from = now - RELEASE_WINDOW_MS
        var i = 0
        while (i < samples.size && samples[i].t < from) {
            i++
        }
        return if (i == 0) samples else samples.subList(i, samples.size)
    }

    /** 速度向量（px/s）。 */
    data class Velocity(val vx: Float, val vy: Float) {
        val speed: Float get() = kotlin.math.sqrt(vx * vx + vy * vy)
    }

    /**
     * 由抬手前的轨迹估初速。
     *
     * ## 算法
     *
     * 1. 裁到最近 [RELEASE_WINDOW_MS]；
     * 2. **抬手前停顿超过 [RELEASE_STALE_MS]** → 视为温柔放下，返回零速度
     *    （否则「甩一下、停住、再松手」会带着早已消失的速度飞出去，很怪）；
     * 3. 窗口跨度 < [MIN_SPAN_MS] → 不可估算，返回零；
     * 4. 分段算速度（每段 dt 至少 [SEG_MIN_DT_MS]），取**端点均值**与**峰值**加权：
     *    快甩时位移集中在窗口内一小段，均值会低估，用峰值补一半。
     *
     * ## 为什么不用「首尾两点直接除」
     *
     * 用户甩动时手指可能先慢后快再慢，首尾两点会把中间的加速完全抹平，
     * 而且手指轻微回拉会让结果变成反方向。
     *
     * @return 未钳制的速度；调用方用 [softClamp] 限幅，用 [isThrow] 判死区
     */
    fun estimate(samples: List<Sample>, now: Long): Velocity {
        val win = trim(samples, now)
        if (win.size < 2) {
            return Velocity(0f, 0f)
        }
        val last = win[win.size - 1]
        // 抬手前已经停住：不带残余速度。
        if (now - last.t > RELEASE_STALE_MS) {
            return Velocity(0f, 0f)
        }
        val first = win[0]
        val span = last.t - first.t
        if (span < MIN_SPAN_MS) {
            return Velocity(0f, 0f)
        }
        // 分段速度
        var sumVx = 0f
        var sumVy = 0f
        var n = 0
        var peak = 0f
        var peakVx = 0f
        var peakVy = 0f
        var i = 1
        while (i < win.size) {
            val a = win[i - 1]
            val b = win[i]
            val dtMs = b.t - a.t
            // 段太短：向前合并到上一段的起点再算（见 SEG_MIN_DT_MS）。
            if (dtMs < SEG_MIN_DT_MS) {
                i++
                continue
            }
            val dt = dtMs / 1000f
            val vx = (b.x - a.x) / dt
            val vy = (b.y - a.y) / dt
            sumVx += vx
            sumVy += vy
            n++
            val sp = kotlin.math.sqrt(vx * vx + vy * vy)
            if (sp > peak) {
                peak = sp
                peakVx = vx
                peakVy = vy
            }
            i++
        }
        if (n == 0) {
            // 所有段都太短：退回端点法（此时至少跨度 ≥ MIN_SPAN_MS，不会除零）。
            val dt = span / 1000f
            return Velocity((last.x - first.x) / dt, (last.y - first.y) / dt)
        }
        val avgVx = sumVx / n
        val avgVy = sumVy / n
        // 端点均值与峰值加权（峰值按方向投影，避免反向分量互相抵消）。
        if (peak <= 0f) {
            return Velocity(avgVx, avgVy)
        }
        val avgSpeed = kotlin.math.sqrt(avgVx * avgVx + avgVy * avgVy)
        val w = PEAK_WEIGHT
        return if (avgSpeed <= 0f) {
            Velocity(peakVx * w, peakVy * w)
        } else {
            // 按「均值方向 + 加权后的模长」合成，方向永远跟随均值（更稳）。
            val ux = avgVx / avgSpeed
            val uy = avgVy / avgSpeed
            val mag = avgSpeed * (1f - w) + peak * w
            Velocity(ux * mag, uy * mag)
        }
    }

    /**
     * 速度软钳制：`cap·(1-e^(-s/cap))`。
     *
     * 单调递增、恒小于 cap，任意力度都能区分。见 [MAX_THROW_SPEED]。
     */
    fun softClamp(speed: Float, cap: Float = MAX_THROW_SPEED): Float {
        if (speed <= 0f) {
            return 0f
        }
        if (cap <= 0f) {
            return 0f
        }
        return cap * (1f - kotlin.math.exp(-speed / cap))
    }

    /**
     * 这个初速算不算「甩」。
     *
     * 低于 [DEAD_ZONE_SPEED] 视为原地放下 —— 抬手后直接吸附，不进入飞行。
     */
    fun isThrow(v: Velocity): Boolean {
        return v.speed >= DEAD_ZONE_SPEED
    }

    /**
     * 把估出来的速度整理成最终初速：先乘总力度，再软钳制。
     *
     * @param power 总力度倍数（[Params.throwPower]）
     */
    fun applyPower(v: Velocity, power: Float): Velocity {
        val p = if (power.isFinite() && power > 0f) power else 1f
        val target = v.speed * p
        if (target <= 0f) {
            return Velocity(0f, 0f)
        }
        val ux = v.vx / v.speed
        val uy = v.vy / v.speed
        val clamped = softClamp(target)
        return Velocity(ux * clamped, uy * clamped)
    }

    /* ============================ 积分 ============================ */

    /**
     * 飞行状态的边界（人偶轴心允许的活动范围）。
     *
     * 【为什么用轴心而不是左上角】`PetWindow` 的位置以「轴心」为准
     *   （见 `placeByPivot` / `rememberPivot`），窗口 x/y 还要减 `pivotLocalX`。
     *   用轴心做边界，反弹判定与吸附判定同一套坐标，不必来回换算。
     */
    data class Bounds(
        /** 轴心允许的最小 x。 */
        val minX: Float,
        /** 轴心允许的最大 x。 */
        val maxX: Float,
        /** 轴心允许的最大 y = 地面（脚底贴屏幕底的安全线）。 */
        val groundY: Float,
        /** 轴心允许的最小 y（顶边）。 */
        val minY: Float = Float.NEGATIVE_INFINITY
    )

    /** 一帧积分的结果。 */
    data class Step(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        /** 这一帧是否**落地**（用于触发落地反馈 / 结束判定）。 */
        val landed: Boolean,
        /** 是否已经静止（调用方据此停止 rAF 并做吸附）。 */
        val resting: Boolean
    )

    /**
     * 固定积分子步长（秒）。
     *
     * 【附录C-6·为什么必须固定】原实现把「这一帧的真实间隔」直接当 dt 做**一次**显式欧拉，
     *   撞边时把位置夹到边界、反算一次速度，穿透掉的位移直接丢弃。
     *   于是 60Hz（16.7ms/帧）与 120Hz（8.3ms/帧）的穿透量差一倍，
     *   反弹后的水平速度与落点因此不同 —— 同一个甩动手势在不同刷新率的手机上
     *   抛出的距离与弹跳高度不一致。
     *
     *   改为固定子步长后，「一帧走几个子步」只影响精度、不影响结果。
     *   取 1/240s：比任何手机刷新率都细，单子步的位移远小于屏宽，反射只发生一次。
     */
    const val FIXED_DT = 1f / 240f

    /**
     * 单帧最多走多少个子步。
     *
     * 【为什么封顶】[MAX_STEP_DT] 是 50ms，50 / (1/240) = 12 步；留余量到 16，
     *   既能覆盖夹取后的最大间隔，又不会让卡顿帧占用过多 CPU。
     */
    private const val MAX_SUBSTEPS = 16

    /**
     * 推进一步。
     *
     * 【附录C-6·改法】「固定子步长 + 几何镜像反射」：
     *
     *  · **子步长固定**：`dt` 被切成若干个 [FIXED_DT]，帧率只决定切几刀，
     *    不改变每一刀的物理量 —— 这是「与刷新率无关」的来源；
     *  · **反射走完剩余位移**：撞边后不用「夹到边界」，而是把这一子步里
     *    剩余的位移按镜面反射继续走完（`new = 2·bound - old - delta`）。
     *    穿透量因此不会被丢掉，60/120Hz 得到同一个落点；
     *  · **支撑状态**：贴住地面 / 顶边（速度不指向外侧）时竖直速度保持 0，
     *    不再累加重力 —— 否则每子步都会重新获得 `g·dt` 的向下速度，
     *    落地后末态带着几十 px/s 抖动（实测 47.5 = 1400×4×1/240）。
     *
     * @param dtMs 距上一帧的毫秒数
     * @param params 物理参数
     */
    fun step(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        dtMs: Long,
        b: Bounds,
        params: Params = Params()
    ): Step {
        // 夹 dt：卡顿 / 后台切回时防止一步飞出屏幕。
        var remain = (dtMs.coerceAtLeast(0L).toFloat() / 1000f).coerceAtMost(MAX_STEP_DT)
        if (remain <= 0f) {
            return Step(x, y, vx, vy, landed = false, resting = false)
        }
        var nx = x
        var ny = y
        var nvx = vx
        var nvy = vy
        var landed = false

        // 【支撑初值】贴边 **且速度不指向外侧** 才算被支撑。
        //   若刚被人向上甩（vy < 0）却因为「y 恰在地面」被判成贴地，人偶会被粘住不走。
        var onFloor = ny >= b.groundY - 0.5f && nvy >= 0f
        var onCeiling = !onFloor && ny <= b.minY + 0.5f && nvy <= 0f && !params.ceilingBounce

        var steps = 0
        while (remain > 0f && steps < MAX_SUBSTEPS) {
            val dt = if (remain >= FIXED_DT) FIXED_DT else remain
            remain -= dt
            steps++

            if (onFloor) {
                // ---- 贴地：竖直不动，只走水平 + 摩擦 ----
                nvy = 0f
                nvx *= kotlin.math.exp(-params.groundFriction * dt)
                val dx = nvx * dt
                if (nx + dx < b.minX) {
                    nx = 2f * b.minX - nx - dx
                    nvx = -nvx * params.restitution
                    if (nx > b.maxX) {
                        nx = b.maxX
                    }
                } else if (nx + dx > b.maxX) {
                    nx = 2f * b.maxX - nx - dx
                    nvx = -nvx * params.restitution
                    if (nx < b.minX) {
                        nx = b.minX
                    }
                } else {
                    nx += dx
                }
            } else if (onCeiling) {
                // ---- 贴顶（仅在关闭顶边反弹时）：竖直不动 ----
                //   `ceilingBounce = false` 的语义就是「顶到就贴住」，所以这里不下落。
                nvy = 0f
                val dx = nvx * dt
                if (nx + dx < b.minX) {
                    nx = 2f * b.minX - nx - dx
                    nvx = -nvx * params.restitution
                    if (nx > b.maxX) {
                        nx = b.maxX
                    }
                } else if (nx + dx > b.maxX) {
                    nx = 2f * b.maxX - nx - dx
                    nvx = -nvx * params.restitution
                    if (nx < b.minX) {
                        nx = b.minX
                    }
                } else {
                    nx += dx
                }
            } else {
                // ---- 自由飞行：半隐式欧拉 + 镜像反射 ----
                nvy += params.gravity * dt
                val dx = nvx * dt
                val dy = nvy * dt

                // 左右墙
                if (nx + dx < b.minX) {
                    nx = 2f * b.minX - nx - dx
                    nvx = -nvx * params.restitution
                    if (nx > b.maxX) {
                        nx = b.maxX
                    }
                } else if (nx + dx > b.maxX) {
                    nx = 2f * b.maxX - nx - dx
                    nvx = -nvx * params.restitution
                    if (nx < b.minX) {
                        nx = b.minX
                    }
                } else {
                    nx += dx
                }

                // 地面
                if (ny + dy >= b.groundY) {
                    val hitSpeed = nvy
                    if (hitSpeed > REST_VY) {
                        // 反弹：剩余位移镜像后继续向上走完。
                        ny = 2f * b.groundY - ny - dy
                        nvy = -hitSpeed * params.restitution
                        landed = true
                        if (ny < b.minY) {
                            // 一子步内撞到顶边：按顶边规则收口（罕见，但必须夹住）。
                            ny = b.minY
                            nvy = if (params.ceilingBounce) -nvy * params.restitution else 0f
                        }
                        // 本子步已离开地面，且水平速度受摩擦。
                        nvx *= kotlin.math.exp(-params.groundFriction * dt)
                    } else {
                        // 低速触地：贴住。
                        ny = b.groundY
                        nvy = 0f
                        onFloor = true
                        nvx *= kotlin.math.exp(-params.groundFriction * dt)
                    }
                } else if (ny + dy < b.minY) {
                    val hitSpeed = nvy
                    if (params.ceilingBounce) {
                        ny = 2f * b.minY - ny - dy
                        nvy = -hitSpeed * params.restitution
                        if (ny > b.groundY) {
                            ny = b.groundY
                            onFloor = true
                            nvy = 0f
                        }
                    } else {
                        ny = b.minY
                        nvy = 0f
                        onCeiling = true
                    }
                } else {
                    ny += dy
                }
            }

            // ---- 静止判定 ----
            // 【必须同时满足「贴地」与「速度够小」】只看速度会把「抛到最高点的瞬间」
            //   误判成静止（那一刻 vy≈0 但人还在空中），人偶会定格在半空。
            if (onFloor &&
                kotlin.math.abs(nvx) < REST_VX &&
                kotlin.math.abs(nvy) < REST_VY
            ) {
                nvx = 0f
                nvy = 0f
                break
            }
        }

        val resting = onFloor &&
            kotlin.math.abs(nvx) < REST_VX &&
            kotlin.math.abs(nvy) < REST_VY
        if (resting) {
            nvx = 0f
            nvy = 0f
        }
        return Step(nx, ny, nvx, nvy, landed, resting)
    }

    /**
     * 飞行是否应该结束（剩余能量不可能再产生可见位移）。
     *
     * 【为什么不只看 [Step.resting]】人偶可能在半空水平滑行（贴顶飞），
     *   那时 vy 可能接近 0 但 vx 还很大；反之在地面滑行时也可能只剩很小的 vy。
     *   这里给宿主一个「还值不值得继续跑 rAF」的统一判据。
     */
    fun shouldStop(s: Step): Boolean {
        return s.resting || (s.vx == 0f && s.vy == 0f)
    }
}
