package com.dollhouse.app.ui.settings

import androidx.compose.runtime.Composable
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】「随机走位」设置页（实验卡片下级）。
 *
 * 【功能说明】开启后，人偶会在屏幕范围内缓慢游走（每 8 秒走一步，步幅 ≤120dp）。
 *   睡觉 / 拖动 / 有气泡时自动暂停；摘窗后停止。
 *
 * 【为什么放实验】新功能、默认关闭，由用户决定是否启用。
 */
object WanderSettingsPage {

    const val TAG_PAGE = "settings:wander"

    /**
     * 【P1-e·设置索引接线】按 rowId 取索引条目，供各行的 `spot =` 使用。
     * 【自纠错】rowId 写错 / 忘了传 spot → 标题退回空串（肉眼可见）。不要加 `?: "兜底"`。
     */
    private fun spot(rowId: String) = SettingsSpots.find(rowId)

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
        DhKit.Page(title = "随机走位", onBack = { PageStack.popIfTop(act, TAG_PAGE) }) {
            DhKit.Card(collapsibleTitle = "开关", titleIcon = Icons.IC_SPARKLES) {
                DhKit.SwitchRow(
                    spot = spot("wander.enable"),
                    iconRes = Icons.IC_WAYPOINTS,
                    checked = PetPrefs.wanderEnabled(act)
                ) { on ->
                    PetPrefs.setWanderEnabled(act, on)
                }
                DhForm.Note(
                    "开启后，人偶会在屏幕范围内缓慢游走（每 8 秒走一步，步幅不大）。\n" +
                    "睡觉 / 拖动 / 有气泡时自动暂停，摘窗后停止。\n" +
                    "关闭后人偶停在原地不动。"
                )
            }
        }
    }
}