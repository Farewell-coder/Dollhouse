package com.dollhouse.app.pet

import android.os.SystemClock
import com.dollhouse.app.data.PetPrefs

/**
 * 【职责】桌宠的动画推进：呼吸相位、眨眼、待机小动作、跳跃、落地、拖拽倾斜、视线跟随、表情计时。
 *
 * 【入口】由 PetView 构造时创建（new PetAnimator(this)），PetView 的公开方法全部转发到本类；
 *         每帧由 PetView.frame 这个 Runnable 调 tick() 驱动。
 *
 * 【交互】动画状态字段仍由 PetView 持有（包级可见），本类只读写不持有——
 *         这样 PetView 的绘制代码（buildMesh / onDraw / drawEmote）不用改一行；
 *         invalidate / postDelayed / removeCallbacks / dp 全部回调 host。
 *
 * 【扩展】新增待机动作：加一个 IDLE_* 常量 + 在 startIdleAction 里排时长 + 在 tickIdle 里做形变；
 *         新增表情：在 PetView 加 EXPR_* 常量 + 在 emoteColor / emoteGlyph 里配色与字形。
 *
 * 【坑】tick() 里的 delta 上限是 120ms（防止后台切回来一次跳太多）；
 *       动画状态字段不能加 private，否则 PetView 绘制会编译不过。
 */
class PetAnimator(private val host: PetView) {
    companion object {
        /**
         * 趴姿（peek）下眨眼间隔的下界（秒）。
         * 【v2.10.2 修】原实现这里写的是 PetView.DRAG_TILT_MAX —— 那是个拖拽倾角常量（8.0f），
         *   语义完全不对，只是数值碰巧能用；现收口成语义正确的独立常量，值保持 8.0f 不变，
         *   以免改动趴姿下的眨眼频率（保功能优先）。
         */
        private const val PEEK_BLINK_MIN_SEC = 8.0f

        /** 站立待机时眨眼间隔的下界（秒）。 */
        private const val BLINK_MIN_SEC = 3.0f
    }

    private fun nextBlinkDelay(): Float {
        // 【v2.10.2】原两个分支的随机项一模一样，合并；只差下界。
        return (Math.random().toFloat()) * 4.0f + (if (host.peek) PEEK_BLINK_MIN_SEC else BLINK_MIN_SEC)
    }

    fun startAnim() {
        if (host.animating) {
            return
        }
        host.animating = true
        host.lastFrame = SystemClock.uptimeMillis()
        host.postDelayed(host.frame, 16L)
    }

    fun stopAnim() {
        host.animating = false
        host.removeCallbacks(host.frame)
    }

    fun playJump() {
        host.jumpT = 0.0f
        host.blinkT = 0.0f
        // blinkT 重置时必须同步清进度，否则会从上一轮的 blinkElapsed 接着走，出现半截眨眼。
        host.blinkElapsed = 0.0f
        setExpression(2, 1100L)
    }

    fun setTilt(f: Float) {
        host.tilt = f
        host.invalidate()
    }

    /** 单例构造时把待机计时的初值摆正（旧实现靠 tickIdle 第一帧自愈，现在不会了）。 */
    init {
        host.idleAction = 0
        host.idleT = -1.0f
        host.idleNextIn = (Math.random().toFloat()) * 17.0f + 13.0f
    }

    /**
     * **【v0.1.0·重写】触发一次随机动作。**
     *
     * 【改造前】这里从 5 个**硬编码**的待机小动作里挑一个，改网格字段（idleScaleY /
     *   idleLiftPx / idleEyeScale / gazeTarget）做形变 —— 用户在动作页配的东西
     *   一个都参与不进来，也没有「关掉」的开关。
     *
     * 【现在】只做一件事：告诉宿主「该播一次随机动作了」。
     *   播不播、播哪个、能不能播（开关是否打开 / 随机组是否为空 / 是否贴边中），
     *   全部由宿主（PetView → DollAnim.pickRandom → 仲裁层）裁决。
     *
     * 【间隔】仍是 13~30 秒（用户定案），且**允许连续抽到同一个动作**（用户明确定案）。
     * 【开关关着时】**不做任何事**，5 秒后再看一次 ——
     *   不能每帧都去问（那会每帧读一次 SharedPreferences）。
     */
    private fun startIdleAction() {
        if (!host.randomActionEnabled()) {
            host.idleNextIn = 5.0f
            return
        }
        host.idleNextIn = (Math.random().toFloat()) * 17.0f + 13.0f
        host.playRandomAction()
    }

    fun pokeIdle(f: Float) {
        host.idleNextIn = Math.min(host.idleNextIn, f)
    }

    /**
     * **【v0.1.0·重写】待机计时** —— 只剩「到点触发一次随机动作」这一件事。
     *
     * 【删掉了什么】改造前这里有一整套形变计算（伸懒腰 / 张望 / 眯眼 / 点头 / 小跳），
     *   现在这些动作都是「随机动作组」里的真实动作（有帧、可编辑、可删除），
     *   由播放层统一画，本方法不再碰网格。
     *
     * 【静止时仍把 4 个 idle 字段归位】万一某帧被判成「正在播待机形变」而留下了
     *   非默认值（例如热更新 / 旧档回退），这里把它们拉回默认，保证画面干净。
     *   这一步是纯保险，正常运行中这些字段恒为默认值。
     */
    private fun tickIdle(f: Float) {
        if (host.idleScaleY != 1.0f || host.idleLiftPx != 0.0f ||
            host.idleEyeScale != 1.0f || host.idleTilt != 0.0f
        ) {
            host.idleScaleY = 1.0f
            host.idleLiftPx = 0.0f
            host.idleEyeScale = 1.0f
            host.idleTilt = 0.0f
        }
        // 【贴边 / 拖动 / 倾斜期间不触发随机动作】用户定案「贴边状态下不随机」，
        //   拖动中指头在屏上更不该冒出一个自主动作。
        if (host.dragging || host.peek || host.lean != 0.0f) {
            return
        }
        val f4 = host.idleNextIn - f
        host.idleNextIn = f4
        if (f4 <= 0.0f) {
            startIdleAction()
        }
    }

    /**
     * 把当前值向目标值按帧时长平滑推进（指数趋近，与 dragLift 同速率 11×dt）。
     * 用于拖动开始时把待机贡献（呼吸 / 抬升 / 眨眼 / 倾斜）平滑收回，
     * 避免「瞬跳回默认值」造成的视觉缩小。
     */
    private fun decayTo(cur: Float, target: Float, dt: Float): Float {
        val k = Math.min(1.0f, 11.0f * dt)
        return cur + (target - cur) * k
    }

    fun setExpression(i: Int, j: Long) {
        host.expression = i
        host.exprStartAt = SystemClock.uptimeMillis()
        host.exprDuration = Math.max(300L, j)
        host.invalidate()
    }

    fun clearExpression() {
        host.expression = 0
    }

    fun setDragging(z: Boolean) {
        if (host.dragging == z) {
            return
        }
        host.dragging = z
        if (z) {
            setExpression(1, 900L)
            // 【r19·拖动不压扁】拖动期间 landT 恒为 -1（不触发落地压扁），
            //   否则拖动结束瞬间 squash 从 1 降到 1-ahogeRu()，视觉上「变小」。
            host.landT = -1.0f
        } else {
            // 【r19·拖动不压扁】拖动结束也不触发落地压扁（landT 保持 -1）。
            //   原逻辑 landT=0 会播一段压扁动画，但用户明确「不要变小」。
            host.landT = -1.0f
            setExpression(3, 1100L)
        }
        host.invalidate()
    }

    fun setDragVelocity(f: Float) {
        host.dragVx = f
    }

    fun lookAt(f: Float) {
        host.gazeTarget = Math.max(-1.0f, Math.min(1.0f, f))
        host.gazeCountdown = 1.6f
    }

    fun tick() {
        val uptimeMillis = SystemClock.uptimeMillis()
        val min = Math.min(120.0f, (uptimeMillis - host.lastFrame).toFloat()) / 1000.0f
        host.lastFrame = uptimeMillis
        // 【贴边低耗·冻结连续相位】稳定贴在边缘且无任何动作时，本档帧率只有 4fps；
        //   若还让 phase 按 1.7rad/s 推进，呼吸/尾巴会在两帧之间跳一大步（用户看到的「贴边抖」）。
        //   冻结后彻底静止，消除跳动；触摸 / 吸附 / 眨眼等动起来时自动恢复推进。
        if (!(host.lean != 0.0f && !host.frameBusy())) {
            val f = host.phase + (1.7f * min)
            host.phase = f
            if (f > 6.2831855f) {
                host.phase = f - 6.2831855f
            }
        }
        val f2 = host.jumpT
        if (f2 >= 0.0f) {
            val f3 = f2 + (min / 0.42f)
            host.jumpT = f3
            if (f3 >= 1.0f) {
                host.jumpT = -1.0f
            }
        }
        val f4 = if (host.dragging) 1.0f else 0.0f
        val f5 = host.dragLift
        host.dragLift = f5 + ((f4 - f5) * Math.min(1.0f, 11.0f * min))
        val max = if (host.dragging) Math.max(-8.0f, Math.min(PetView.DRAG_TILT_MAX, (-host.dragVx) * 0.014f)) else 0.0f
        val f6 = host.tilt
        host.tilt = f6 + ((max - f6) * Math.min(1.0f, 9.0f * min))
        val f7 = host.landT
        if (f7 >= 0.0f) {
            val f8 = f7 + (min / 0.38f)
            host.landT = f8
            if (f8 >= 1.0f) {
                host.landT = -1.0f
            }
        }
        if (host.expression != 0 && SystemClock.uptimeMillis() - host.exprStartAt > host.exprDuration) {
            host.expression = 0
        }
        // 【眨眼时间线】闭合 → 保持 → 睁开走 BlinkTimeline（纯逻辑可测），整轮约 220ms
        //   （CLOSE 70 + HOLD 50 + OPEN 100），比原来的 140ms 正弦钟形更接近「闭上、停一下、再睁开」。
        if (host.lean != 0.0f) {
            // 贴边偷看时不眨眼：眨眼是唯一会周期性把 busy 判定顶回 60fps 的自动动作，
            //   压掉它（以及 tickIdle 的待机小动作），贴边态才能稳定停在 EDGE_IDLE_MS 那一档。
            host.blinkT = -1.0f
            host.blinkElapsed = -1.0f
        } else if (host.blinkElapsed >= 0.0f || host.blinkT >= 0.0f) {
            // 外部（如 playJump）可能只置 blinkT=0，这里统一按时间线推进。
            if (host.blinkElapsed < 0.0f) {
                host.blinkElapsed = 0.0f
            }
            val e = host.blinkElapsed + (min * 1000.0f)
            val p = BlinkTimeline.phaseAt(e)
            if (p < 0.0f) {
                host.blinkElapsed = -1.0f
                host.blinkT = -1.0f
                host.blinkCountdown = nextBlinkDelay()
            } else {
                host.blinkElapsed = e
                host.blinkT = p
            }
        } else {
            val f11 = host.blinkCountdown - min
            host.blinkCountdown = f11
            if (f11 <= 0.0f) {
                host.blinkElapsed = 0.0f
                host.blinkT = BlinkTimeline.phaseAt(0.0f)
            }
        }
        tickIdle(min)
        if (host.idleAction != 2 && !(host.lean != 0.0f && !host.frameBusy())) {
            val f12 = host.gazeCountdown - min
            host.gazeCountdown = f12
            if (f12 <= 0.0f) {
                // 【r11】好感度已删除：凝视幅度固定取中间档，不再按数值分黏人 / 冷淡两态。
                host.gazeTarget = ((Math.random() * 2.0 - 1.0).toFloat()) * PetView.TAILR_V
                host.gazeCountdown = (Math.random().toFloat()) * 6.0f + 4.0f
            }
        }
        val f13 = host.gaze
        host.gaze = f13 + ((host.gazeTarget - f13) * Math.min(1.0f, min * 3.5f))
        host.invalidate()
    }
}
