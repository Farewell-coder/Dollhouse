package com.dollhouse.app.ui.settings

import android.app.Activity
import android.view.View

/**
 * 【职责】「实验」页：收纳尚未正式归入设置卡片、但已经可用的实验性功能。
 *
 * 【当前内容】仅「lamda 设备控制」：整页复用 [LamdaPage]，入口挂在设置页「记忆」与
 *   「关于」之间的「实验」卡片下级（原设置页聊天卡片下的同类入口已删除）。
 *
 * 【路由】由 ProviderNav.R_EXPERIMENT 分派；返回与其它子页一致，走 ProviderNav.handleBack。
 */
object ExperimentPage {

    /** 搭出「实验」页；当前正文即 lamda 设备控制。 */
    @JvmStatic
    fun build(act: Activity): View {
        return LamdaPage.build(act)
    }
}
