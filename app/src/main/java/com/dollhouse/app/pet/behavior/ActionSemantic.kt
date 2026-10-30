package com.dollhouse.app.pet.behavior

/**
 * 【职责】标准动作语义 ID —— 模型包与手势都**按这套 ID 说话**。
 *
 * 【为什么需要统一语义】改造前桌宠的「动作」是**硬编码在 PetAnimator 里**的
 *   5 个待机小动作（1/2/3/4/5 号分支），而用户在动作页配置的 `DollAnim` 动作
 *   **一个都不会被桌宠播放**（grep 证实 PetView/PetWindow/PetAnimator 对 DollAnim 零引用）。
 *   两套系统之间没有共同语言 —— 本类就是那套语言。
 *
 * 【为什么用字符串而不是 enum】这些 ID 要写进模型包 manifest 的 `actions` 映射表
 *   （跨版本、跨工具的字符串契约）。字符串常量可以随时加注释说「这个值不能改」，
 *   enum 一旦有人重命名枚举项，旧模型包就读不出来了。
 *
 * 【契约稳定性】本表的 6 个 ID 是**对外契约**（见交付报告 03）：
 *   新增可以，**改名/删除不行** —— 已发布的模型包会因此失效。
 */
object ActionSemantic {

    /** 待机：无交互时的默认档。 */
    const val IDLE = "idle"

    /** 点击：单击触发。 */
    const val TAP = "tap"

    /** 拖动：按住移动。 */
    const val DRAG = "drag"

    /** 长按：长按阈值触发。 */
    const val LONG_PRESS = "longpress"

    /** 出现：人偶挂载。 */
    const val APPEAR = "appear"

    /** 消失：人偶移除。 */
    const val DISAPPEAR = "disappear"

    /** 【r19·新增】睡觉：低功耗休眠循环（帧率降到最低，不再响应待机小动作）。 */
    const val SLEEP = "sleep"

    /**
     * **【v0.1.0·新增】贴边**：人偶吸附到屏幕边时播的动作（对应「贴边动作」固定壳）。
     *
     * 【为什么需要它】改造前贴边只有一个倾斜姿态 + 趴姿形变，用户在动作页配的东西
     *   根本无法影响它。有了这个语义，「贴边动作」这个壳才有意义。
     */
    const val EDGE = "edge"

    /**
     * **【v0.1.5·R-013 新增】贴入瞬间**：人偶刚吸附到屏幕边缘时**只播一次**的动作
     * （对应「贴入瞬间」固定壳）。
     *
     * 【为什么不复用 [EDGE]】两者生命周期完全不同：
     *   · [EDGE] = 贴边常驻，**循环**播放，直到用户再次拖动；
     *   · [EDGE_IN] = 一次性过渡，播完立刻被 [EDGE] 接管。
     *   若共用一个语义，仲裁层就无法表达「播完要自动换一个动作」这件事 ——
     *   循环动作根本不会触发 `onActionFinished`，于是永远进不了贴边常驻。
     */
    const val EDGE_IN = "edge_in"

    /**
     * **【v0.1.0·新增】随机**：13~30 秒自发触发一次的动作（对应「随机动作」组）。
     *
     * 【优先级为什么低于出现/消失】它是系统自发行为，不该抢生命周期事件。
     */
    const val RANDOM = "random"

    /**
     * **【v0.1.0·新增】手动**：由手势 / 快捷入口主动触发的动作（对应「手动动作」组）。
     *
     * 【与 TAP 的区别】`tap` 是「点一下人偶」这个**具体手势**；`manual` 是「用户在
     *   手势设置里绑了某个动作，现在要播它」。后者语义更宽，优先级也更高一点
     *   （用户明确点了名要播的，比系统猜测的「摸头反馈」更该优先）。
     */
    const val MANUAL = "manual"

    /** 全部标准语义（供校验器 / UI 列举）。 */
    val ALL: List<String> = listOf(
        IDLE, EDGE, EDGE_IN, RANDOM, MANUAL, TAP, DRAG, LONG_PRESS, APPEAR, DISAPPEAR, SLEEP
    )

    /** 人类可读名（UI 直接用，避免各处再写一份中文映射）。 */
    fun label(semantic: String): String = when (semantic) {
        IDLE -> "常驻"
        EDGE -> "贴边"
        EDGE_IN -> "贴入"
        RANDOM -> "随机"
        MANUAL -> "手动"
        TAP -> "点击"
        DRAG -> "拖动"
        LONG_PRESS -> "长按"
        APPEAR -> "出现"
        DISAPPEAR -> "消失"
        SLEEP -> "睡觉"
        else -> semantic
    }

    /** 是否是标准语义。 */
    fun isStandard(semantic: String?): Boolean = semantic != null && ALL.contains(semantic)

    /**
     * 语义优先级。
     *
     * 【v0.1.0 全序】**手指在屏上 / 用户刚发起的 > 系统自发的 > 基础态**：
     * ```
     * DRAG(40) = LONG_PRESS(40)   手指按着，任何自动动作都不得抢画面
     *       > EDGE_IN(38)          **v0.1.5** 刚松手吸边的那一刻，必须是全场最强
     *       > TAP(35)             刚点完，反馈必须优先
     *       > MANUAL(30)          用户在手势里点名要播的动作
     *       > APPEAR/DISAPPEAR(20) 生命周期
     *       > RANDOM(15)          系统自发，可被任何用户动作打断
     *       > EDGE(12)            贴近「基础态」：在边上就该播贴边
     *       > IDLE(10)            兜底基础态
     *       > SLEEP(5)            最低，任何交互都能唤醒
     * ```
     * 【为什么 EDGE > IDLE】两者都是「没有交互时的状态」，但「在屏幕边上」比「随便站着」
     *   更具体 —— 更具体的状态应该赢。
     *
     * **【v0.1.5·为什么 EDGE_IN 只低于 DRAG、却高于 TAP/MANUAL】** 按需求书 18.2 的
     *   优先级表（DRAG 40 > EDGE_IN 30 > MANUAL 20 > AUTO 10 > IDLE/EDGE_HOLD 0），
     *   贴入瞬间必须压过手动与自动，否则「刚贴到边上，某个还没播完的手动动作继续播完」
     *   —— 用户看到的是「贴边之后还在挥手」，与 6.4「松手吸边 → 贴入瞬间」直接冲突。
     *   数值上与文档的 30 不同（这里用 38），是因为**本工程的既有档位已经占用了那些数字**
     *   （TAP=35、MANUAL=30），而 v2.1·19 要求「未被第 19 节覆盖的原需求全部继续有效」
     *   → 不能为了对齐数字而打乱既有全序。**相对顺序与文档完全一致**，这才是需求的本意。
     *
     * 【为什么 EDGE_IN 低于 DRAG】手指还按着的时候不可能「刚刚吸边成功」——
     *   吸边的定义就是松手。但拖动中若恰好触发了入边判定，拖拽必须赢（需求 6.5.1）。
     */
    fun priority(semantic: String): Int = when (semantic) {
        DRAG, LONG_PRESS -> 40
        EDGE_IN -> 38
        TAP -> 35
        MANUAL -> 30
        APPEAR, DISAPPEAR -> 20
        RANDOM -> 15
        EDGE -> 12
        IDLE -> 10
        SLEEP -> 5
        else -> 0
    }
}