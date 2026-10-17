package com.dollhouse.app.ui.settings

import androidx.compose.runtime.Composable
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】「睡觉模式」设置页（实验卡片下级）。
 *
 * 【功能说明】开启后，快捷菜单里会出现「睡觉」选项；点击后人偶进入低功耗休眠
 *   （播 sleep 循环动作，或闭眼表情兜底），任何点击 / 拖动 / 长按都会唤醒。
 *
 * 【为什么放实验】新功能、默认关闭，由用户决定是否启用。
 */
object SleepSettingsPage {

    const val TAG_PAGE = "settings:sleep"

    @JvmStatic
    fun open(act: MainActivity) {
        PageStack.push(act, TAG_PAGE) { build(act) }
    }

    @JvmStatic
    fun build(act: MainActivity): android.view.View {
        return com.dollhouse.app.ui.compose.ComposeHost.createView(act) { Content(act) }
    }

    @Composable
    private fun Content(act: MainActivity) {
        DhKit.Page(title = "睡觉模式", onBack = { PageStack.pop(act) }) {
            DhKit.Card(collapsibleTitle = "说明", titleIcon = Icons.IC_SPARKLES) {
                DhForm.Note(
                    "睡觉模式没有独立开关 —— 它由「快捷菜单」里的「睡觉」选项触发。\n\n" +
                    "使用方法：\n" +
                    "1. 设置页 → 人偶 → 手势 → 把「双击」配上「快捷菜单」\n" +
                    "2. 双击人偶 → 点「睡觉」\n" +
                    "3. 人偶进入休眠（播 sleep 动作，或闭眼表情兜底）\n" +
                    "4. 点击 / 拖动 / 长按人偶即可唤醒\n\n" +
                    "休眠时帧率降到最低，省电。"
                )
            }
        }
    }
}