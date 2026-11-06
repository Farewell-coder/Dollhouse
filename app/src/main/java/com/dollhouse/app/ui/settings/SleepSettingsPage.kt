package com.dollhouse.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
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
            // 【v0.2.6·安静时段】与睡觉模式并列但语义不同：
            //   睡觉 = 省电休眠；安静 = 到点不主动开口（问候、随机台词、通知）。
            //   放在同一页是因为用户心智里它们都属于「别在晚上烦我」。
            QuietCard(act)

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

    /**
     * 安静时段卡片。
     *
     * 【为什么用两个可点的时刻行而不是滑条】用户调的是「22:30 到 07:00」这种钟点，
     *   滑条在手机上很难精确到 10 分钟，而且看不出当前值。这里每点一次把小时 +1，
     *   长按分钟 +15 —— 简单、可预期，且不需要引入时间选择器依赖。
     */
    @Composable
    private fun QuietCard(act: MainActivity) {
        val ctx = act.applicationContext
        var on by remember { mutableStateOf(PetPrefs.quietEnabled(ctx)) }
        var start by remember { mutableStateOf(PetPrefs.quietStart(ctx)) }
        var end by remember { mutableStateOf(PetPrefs.quietEnd(ctx)) }

        DhKit.Card(collapsibleTitle = "安静时段", titleIcon = Icons.IC_CLOCK_3) {
            DhForm.Note("这段时间里人偶不主动开口（问候、随机台词、提醒），但不影响你主动找她。")
            DhKit.ValueRow(
                label = "启用安静时段",
                value = if (on) "已开启" else "已关闭",
                valueTint = if (on) DhTokens.colors.acc else null,
                iconRes = if (on) Icons.IC_CHECK else Icons.IC_CLOSE,
                onClick = {
                    on = !on
                    PetPrefs.setQuietEnabled(ctx, on)
                }
            )
            DhKit.ValueRow(
                label = "开始时间",
                value = com.dollhouse.app.data.QuietHours.format(start),
                iconRes = Icons.IC_CLOCK_3,
                desc = "点一下 +1 小时",
                onClick = {
                    start = (start + 60) % 1440
                    PetPrefs.setQuietStart(ctx, start)
                }
            )
            DhKit.ValueRow(
                label = "结束时间",
                value = com.dollhouse.app.data.QuietHours.format(end),
                iconRes = Icons.IC_CLOCK_3,
                desc = "点一下 +1 小时",
                onClick = {
                    end = (end + 60) % 1440
                    PetPrefs.setQuietEnd(ctx, end)
                }
            )
            DhForm.Note(
                "当前： " + com.dollhouse.app.data.QuietHours.describe(start, end) +
                    if (start > end) "（跨午夜）" else ""
            )
        }
    }
}