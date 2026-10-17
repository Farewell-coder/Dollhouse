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

    /** 全部标准语义（供校验器 / UI 列举）。 */
    val ALL: List<String> = listOf(IDLE, TAP, DRAG, LONG_PRESS, APPEAR, DISAPPEAR, SLEEP)

    /** 人类可读名（UI 直接用，避免各处再写一份中文映射）。 */
    fun label(semantic: String): String = when (semantic) {
        IDLE -> "待机"
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
     * 语义优先级 —— **交互 > 待机 > 基础形变**。
     *
     * 【为什么用数字而不是比较函数】调用方要同时比较多个候选（当前正在播的
     *   vs 新来的），数字最直观：数值大的赢。
     *
     * 【为什么待机排中间】待机是「没有交互时」的档位；它必须高于「基础形变」
     *   （呼吸/眨眼这类一直跑的），又必须**低于任何交互** ——
     *   否则点一下人偶、待机动作却把它顶掉，用户会觉得「点了没反应」。
     */
    fun priority(semantic: String): Int = when (semantic) {
        TAP, DRAG, LONG_PRESS -> 30
        APPEAR, DISAPPEAR -> 20
        IDLE -> 10
        SLEEP -> 5 // 睡觉优先级最低：任何交互都能唤醒
        else -> 0
    }
}