package com.dollhouse.app.pet

import com.dollhouse.app.PetService

/**
 * 【职责】桌宠手势动作注册表：手势 id → 具体响应。
 *
 * 【入口】PetService 判定出手势后调 perform(id, this)。
 *
 * 【交互】只做查表与派发，不持有状态；具体行为在 PetAction 实现里。
 *
 * 【扩展】新增手势 = 在 ACTIONS 加一行（先注册的优先）。
 *
 * 【坑】未知 id 静默忽略（与拆前 if/else 分支漏掉时行为一致），不要抛异常打断触摸流程。
 */
object PetActionRegistry {
    /** 单击：原地跳一下。 */
    private const val ID_TAP = "tap"

    /** 双击：无动作（原「开合聊天窗口」已取消，双击不再进全屏聊天页）。 */
    private const val ID_DOUBLE_TAP = "double_tap"

    /** 三击：召唤迷你输入框（人偶趴到框顶说话）。 */
    private const val ID_TRIPLE_TAP = "triple_tap"

    private val ACTIONS = ArrayList<PetAction>()

    init {
        ACTIONS.add(object : PetAction {
            override fun id(): String {
                return ID_TAP
            }

            override fun label(): String {
                return "点一下：跳一下，并随机说一句"
            }

            override fun run(host: PetService) {
                host.petJump()
                // 【补回 v2.5】v2.4 删 chatter 时只剩 petJump()，单击再也不出词
                //   （用户报「点击没有消息弹出来」）。这里补回「跳一下 + 随机说一句」。
                val lines = PetService.LINES_TAP
                if (lines != null && lines.isNotEmpty()) {
                    host.say(lines[host.random.nextInt(lines.size)], 2400L)
                }
            }
        })
        ACTIONS.add(object : PetAction {
            override fun id(): String {
                return ID_DOUBLE_TAP
            }

            override fun label(): String {
                return "双击：无动作"
            }

            override fun run(host: PetService) {
                // 【改动】双击不再开聊天（改由三击召唤迷你输入框）；
                //   入口保留为空实现，方便以后重新启用时不至于找不到接线点。
            }
        })
        ACTIONS.add(object : PetAction {
            override fun id(): String {
                return ID_TRIPLE_TAP
            }

            override fun label(): String {
                return "三击：迷你聊天"
            }

            override fun run(host: PetService) {
                host.openMiniTalk()
            }
        })
    }

    /** 按手势 id 派发；未知 id 直接忽略。 */
    @JvmStatic
    fun perform(id: String, host: PetService) {
        for (i in ACTIONS.indices) {
            if (ACTIONS[i].id() == id) {
                ACTIONS[i].run(host)
                return
            }
        }
    }
}
