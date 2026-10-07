package com.dollhouse.app.pet

import com.dollhouse.app.PetService

/**
 * 【职责】桌宠上一次手势的含义：一个可注册、可独立修改的动作。
 *
 * 【入口】只在 PetActionRegistry 里注册；PetService 判定出手势后按 id 派发。
 *
 * 【交互】run 拿得到宿主服务，可随意调它的包级方法（petView / toggleChat / openPanel 等）。
 *
 * 【扩展】新增手势响应 = 写一个实现类 + 在 PetActionRegistry.ACTIONS 加一行。
 *
 * 【坑】id 必须与 PetService 派发时用的字符串逐字一致；
 *        连击的时序判定留在 PetService（tapCount + TAP_WINDOW_MS），本类只负责「判定成立后干什么」。
 */
interface PetAction {
    /** 手势标识：tap / double_tap / triple_tap（long_press 已下线）。 */
    fun id(): String

    /** 自述用途，排查用。 */
    fun label(): String

    /** 执行动作。 */
    fun run(host: PetService)
}
