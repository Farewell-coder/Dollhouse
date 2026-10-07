package com.dollhouse.app.ui.settings

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * 【职责】设置页里一张分组的声明：标题、是否折叠卡片、是否整组摘除、以及卡片专属装饰。
 *
 * 【入口】只在 SettingsRegistry 里注册；SettingsPage 按标题文本查表后决定怎么摆。
 *
 * 【交互】decorate 在卡片主体装好之后回调（如 API 卡片往里塞模型下拉框）；不持有状态。
 *
 * 【扩展】新增一张设置卡片 = 写一个实现类 + 在 SettingsRegistry.CARDS 加一行。
 *
 * 【坑】title() 必须和主页面上那一行的标题文案逐字一致——SettingsPage 就是靠文本匹配认组的，改文案等于改注册表。
 */
interface SettingsCard {
    /** 分组标题，必须与页面上的文案逐字一致。 */
    fun title(): String
    /** true = 整组从视图树摘除（下线功能）；默认 false。 */
    fun dropped(): Boolean
    /** true = 展开态；默认 false（收起）。 */
    fun openByDefault(): Boolean
    /** 卡片主体装好后回调，做本卡片专属的整理；默认什么都不做。 */
    fun decorate(ctx: Context, body: LinearLayout, all: List<View>, from: Int, to: Int)
}
