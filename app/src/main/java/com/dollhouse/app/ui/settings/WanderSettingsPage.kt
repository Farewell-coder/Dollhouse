package com.dollhouse.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
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
        // 【重读信号】PetPrefs 不是 Compose 可观察状态 —— 写完必须 bump tick 才会重组，
        //   否则开关会停在原位（表现为「点不动」）。
        var tick by remember { mutableStateOf(0) }
        val wanderOn = remember(tick) { PetPrefs.wanderEnabled(act) }

        QzxPage(
            title = "随机走位",
            onBack = { PageStack.popIfTop(act, TAG_PAGE) },
            backIconRes = Icons.IC_ARROW_LEFT,
        ) {
            QzxList(spotTitle = { spot(it)?.title }) {
                section(
                    id = "wander",
                    title = "开关",
                    titleIconRes = Icons.IC_SPARKLES,
                    collapsible = true,
                ) {
                    switch(
                        "随机走位",
                        checked = wanderOn,
                        iconRes = Icons.IC_WAYPOINTS,
                    ).spot("wander.enable").onChecked { on ->
                        PetPrefs.setWanderEnabled(act, on)
                        tick++
                    }
                    note(
                        "开启后，人偶会在屏幕范围内缓慢游走（每 8 秒走一步，步幅不大）。\n" +
                        "睡觉 / 拖动 / 有气泡时自动暂停，摘窗后停止。\n" +
                        "关闭后人偶停在原地不动。"
                    )
                }
            }
        }
    }
}