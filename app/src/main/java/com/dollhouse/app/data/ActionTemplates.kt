package com.dollhouse.app.data

/**
 * 【职责】动作绑定的「行为模板」：一次把一组动作设成合理的触发器与优先级组合。
 *
 * ## 为什么需要
 * 现在动作页是**逐个动作**调「允许哪些触发条件 + 三档优先级」。一只人偶有十几个动作，
 * 想表达「点击时优先给我加的这些动作、待机时保持默认」，要点十几次 ——
 * 绝大多数用户不会做第二次，于是这个能力形同虚设。
 *
 * ## 本类只产出「要写成什么」，不自己写盘
 * 输入是动作清单，输出是 `id -> ActionBinding` 的映射；写入仍交给
 * [ActionWorkbench.setBinding]。这样模板本身可单测，且不会绕过既有的存储逻辑。
 *
 * ## 三个模板的取舍
 * - [Template.FOCUS_TAP]：点击相关动作提升为「高」，其余保持普通 —— 想要「点她有反应」。
 * - [Template.CALM_IDLE]：只让常驻类留在待机，随机与点击类降级 —— 想要「安静陪着」。
 * - [Template.RESET]：全部恢复默认（等价于清掉绑定记录）。
 */
object ActionTemplates {

    enum class Template { FOCUS_TAP, CALM_IDLE, RESET }

    fun label(t: Template): String = when (t) {
        Template.FOCUS_TAP -> "点击更积极"
        Template.CALM_IDLE -> "安静陪伴"
        Template.RESET -> "全部恢复默认"
    }

    fun desc(t: Template): String = when (t) {
        Template.FOCUS_TAP -> "点击与长按触发的动作提到「高」，其余不变"
        Template.CALM_IDLE -> "只保留待机与随机动作，点击类不主动抢"
        Template.RESET -> "清掉本只人偶的所有动作绑定"
    }

    /**
     * 生成绑定映射。
     *
     * @param actionIds 该人偶当前的全部动作 id
     * @param triggerOf 取某个动作的语义触发器类型（tap / idle / random / ...）；
     *                  传 null 表示「不知道」，按普通处理
     */
    @JvmStatic
    fun apply(
        template: Template,
        actionIds: List<String>,
        triggerOf: (String) -> String?
    ): Map<String, ActionWorkbench.ActionBinding> {
        val out = LinkedHashMap<String, ActionWorkbench.ActionBinding>()
        for (id in actionIds) {
            val trigger = triggerOf(id)
            out[id] = when (template) {
                // 点击/长按类：保持手动可触发 + 高优先级。
                Template.FOCUS_TAP ->
                    if (trigger == "tap" || trigger == "longpress") {
                        ActionWorkbench.ActionBinding(
                            triggers = listOf("manual", "tap", "longpress"),
                            priority = ActionWorkbench.PRIORITY_HIGH
                        )
                    } else {
                        ActionWorkbench.ActionBinding()

                    }
                // 安静：只保留手动 + 待机/随机，且用普通优先级。
                Template.CALM_IDLE -> when (trigger) {
                    "idle" -> ActionWorkbench.ActionBinding(listOf("manual", "idle"), ActionWorkbench.PRIORITY_NORMAL)
                    "random" -> ActionWorkbench.ActionBinding(listOf("manual", "random"), ActionWorkbench.PRIORITY_NORMAL)
                    // 点击类：只允许手动，不再由点击自动触发。
                    "tap", "longpress", "drag", "edge" ->
                        ActionWorkbench.ActionBinding(listOf("manual"), ActionWorkbench.PRIORITY_NORMAL)
                    else -> ActionWorkbench.ActionBinding()
                }
                Template.RESET -> ActionWorkbench.ActionBinding()
            }
        }
        return out
    }

    /**
     * 应用模板。
     *
     * @return 实际改写的动作数量（RESET 时统计被清掉的记录数由调用方判断，
     *         这里按「传入的动作数」返回，便于提示）
     */
    @JvmStatic
    fun applyTo(
        ctx: android.content.Context,
        dollId: String?,
        template: Template,
        actionIds: List<String>,
        triggerOf: (String) -> String?
    ): Int {
        val map = apply(template, actionIds, triggerOf)
        var n = 0
        for ((id, b) in map) {
            ActionWorkbench.setBinding(ctx, dollId, id, b)
            n++
        }
        return n
    }
}