package com.dollhouse.app.pet

/**
 * 【职责】桌宠帧率决策 —— 把「什么时候全速、什么时候省电」从绘制代码里分离出来。
 *
 * 【为什么需要它】改造前 `frameBusy()` 直接读 `PetView` 的 12 个状态字段、
 *   `nextFrameDelay()` 里的 `250L` **出现两次而语义完全不同**
 *   （一次是「屏幕灭兜底」，一次是「贴边低耗」）——
 *   既没法单测，也极易改错（改一处会顺带影响另一处）。
 *   抽成本类后：帧率逻辑可在 JVM 上直接验证（`PetFramePolicyTest`），
 *   档位语义也变成显式常量。
 *
 * 【行为零变化】判定条件与档位数值**逐个保留**：
 *   · 12 个字段的 OR 一个不少（见 [needsFullSpeed]）；
 *   · 250ms 双语义显式区分为 [MS_EDGE_LOW_POWER] 与 [MS_SCREEN_OFF]，数值都还是 250；
 *   · 判定**顺序**也原样保留（低功耗 → 屏灭 → 忙 → 贴边 → 待机）。
 *
 * 【为什么顺序不能改】`lowPower` 优先于屏灭判断、屏灭优先于 `frameBusy`：
 *   熄屏时即便有动画在跑也只走心跳，这是 v2.10.2 修「切换后容易消失」时定的口径。
 *   顺序一变，熄屏省电策略就失效。
 */
object PetFramePolicy {

    /** 触摸 / 吸附 / 拖拽 / 跳跃 / 落地 / 眨眼 / 待机动作 / 表情 / 收敛中。 */
    const val MS_TOUCH_BUSY = 16L

    /** 纯站立呼吸态。 */
    const val MS_IDLE = 32L

    /** 贴边偷看且无任何动作（4fps）。 */
    const val MS_EDGE_LOW_POWER = 250L

    /**
     * 屏幕灭兜底（4fps）。
     * 【与 [MS_EDGE_LOW_POWER] 数值相同但语义不同】这是「防广播晚到/丢失」的兜底，
     *   那是「贴边时主动降频」。改造前两者都写裸 `250L`，是本工程最容易改错的地方之一。
     */
    const val MS_SCREEN_OFF = 250L

    /** 熄屏心跳：保 1s 自检，能亮屏自恢复。 */
    const val MS_HEARTBEAT = 1000L

    /**
     * 「是否需要全速帧」的输入 —— 一帧的全部相关状态。
     *
     * 【为什么用 data class 而不是直接读 PetView 字段】抽出来才能在 JVM 上单测：
     *   改造前这 12 个字段散在 1316 行的 PetView 里，想验证「落地时会不会掉帧」
     *   只能真机目视。
     */
    data class State(
        val touchBusy: Boolean,
        val snapActive: Boolean,
        val dragging: Boolean,
        val jumpT: Float,
        val landT: Float,
        val blinkT: Float,
        val idleAction: Int,
        val idleT: Float,
        val expression: Int,
        val dragLift: Float,
        val tilt: Float,
        val gaze: Float,
        val gazeTarget: Float
    )

    /**
     * 是否需要全速帧 —— 12 个字段的 OR，**逐字段、逐阈值与改造前一致**。
     *
     * 【阈值一个都不能松】`dragLift > 0.002f` / `|tilt| > 0.05f` / `|gaze-gazeTarget| > 0.004f`
     *   这三个「收敛中」判定决定了松手后动画还跑不跑最后几帧 —— 阈值写错会让动画
     *   在收尾时提前定格，看起来「弹到一半卡住」。
     */
    fun needsFullSpeed(s: State): Boolean {
        return s.touchBusy
                || s.snapActive
                || s.dragging
                || s.jumpT >= 0.0f
                || s.landT >= 0.0f
                || s.blinkT >= 0.0f
                || s.idleAction != 0
                || s.idleT >= 0.0f
                || s.expression != 0
                || s.dragLift > 0.002f
                || Math.abs(s.tilt) > 0.05f
                || Math.abs(s.gaze - s.gazeTarget) > 0.004f
    }

    /**
     * 下一帧延迟（毫秒）—— 5 档判定，**顺序与数值原样保留**。
     *
     * @param s        当前状态（用于 [needsFullSpeed]）
     * @param lowPower 是否已进入熄屏低功耗
     * @param shown    是否处于前台可见
     * @param screenOn 屏幕是否亮着（有 500ms 缓存，见 PetView.screenOn）
     * @param lean     贴边倾斜角（!= 0 表示正贴边）
     */
    fun delayMs(
        s: State,
        lowPower: Boolean,
        shown: Boolean,
        screenOn: Boolean,
        lean: Float
    ): Long {
        // ① 熄屏低功耗：1s 心跳（不是 250ms —— 熄屏既不推进动画也不重绘，250ms 纯属空转）。
        if (lowPower) {
            return MS_HEARTBEAT
        }
        // ② 屏灭兜底：4fps。防的是「广播晚到或个别 ROM 不发广播」。
        //    isShown 只看「已 attach + visibility」，熄屏时它仍为 true，
        //    所以必须再问一次屏幕开关，否则屏灭后桌宠照旧 30fps 空转。
        if (!shown || !screenOn) {
            return MS_SCREEN_OFF
        }
        // ③ 忙：全速。要么起手要么落位，最怕掉帧。
        if (needsFullSpeed(s)) {
            return MS_TOUCH_BUSY
        }
        // ④ 贴边偷看且无动作：4fps。眨眼与待机小动作已被 PetAnimator 在 lean != 0 时压掉，
        //    连续相位也在 tick() 里冻结，所以本档不会被顶回全速、也不会跳。
        if (lean != 0.0f) {
            return MS_EDGE_LOW_POWER
        }
        // ⑤ 纯站立呼吸态。
        return MS_IDLE
    }
}