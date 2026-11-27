package com.dollhouse.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.QuietHours
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage

/**
 * 【职责】「睡觉模式」设置页（实验卡片下级）。
 *
 * 【功能说明】开启后，快捷菜单里会出现「睡觉」选项；点击后人偶进入低功耗休眠
 *   （播 sleep 循环动作，或闭眼表情兜底），任何点击 / 拖动 / 长按都会唤醒。
 *
 * 【为什么放实验】新功能、默认关闭，由用户决定是否启用。
 *
 * 【QZX 迁移】本页是**声明式列表的模板页**：页面里不再有任何尺寸、间距、描边或分隔线
 *   的字面量 —— 它们全部由 `QzxList` 按令牌算，页面只描述"有哪些行"。
 */
object SleepSettingsPage {

    const val TAG_PAGE = "settings:sleep"

    /**
     * 【P1-e·设置索引接线】按 rowId 取索引条目。
     *
     * 【自纠错】rowId 写错 / 忘了登记 → `QzxList` 退回调用方写的字面量（不会显示空白）。
     *   与旧 `SettingsSpots.spotTitle(spot, fallback)` 同一条规则。
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
        val ctx = act.applicationContext
        // 【状态仍在页面里】列表是**声明式**的：它只负责"把这些行按规范画出来"，
        //   值从哪来、改完写到哪，仍归页面 —— 这也是"功能不变"的前提。
        var on by remember { mutableStateOf(PetPrefs.quietEnabled(ctx)) }
        var start by remember { mutableStateOf(PetPrefs.quietStart(ctx)) }
        var end by remember { mutableStateOf(PetPrefs.quietEnd(ctx)) }

        QzxPage(
            title = "睡觉模式",
            onBack = { PageStack.popIfTop(act, TAG_PAGE) },
        ) {
            // 【spotTitle 注入】新系统不依赖 SettingsSpots；查表这层依赖只出现在这一行。
            QzxList(spotTitle = { spot(it)?.title }) {

                // 【v0.2.6·安静时段】与睡觉模式并列但语义不同：
                //   睡觉 = 省电休眠；安静 = 到点不主动开口（问候、随机台词、通知）。
                //   放在同一页是因为用户心智里它们都属于「别在晚上烦我」。
                section(
                    id = "quiet",
                    title = "安静时段",
                    // 【把操作提示提到 footer】旧版是两行各写一条 `desc = "点一下 +1 小时"`；
                    //   同样的话说两遍不如在卡片下说一次 —— 这既是声明式的收益（footer 由系统排版），
                    //   也保住了那条**操作提示**本身（它是可发现性，不是装饰）。
                    footer = "点一下时刻 +1 小时。这段时间里人偶不主动开口" +
                        "（问候、随机台词、提醒），但不影响你主动找她。"
                ) {
                    item(
                        "启用安静时段",
                        if (on) "已开启" else "已关闭"
                    ).arrow().onClick {
                        on = !on
                        PetPrefs.setQuietEnabled(ctx, on)
                    }.spot("sleep.quiet")

                    item("开始时间", QuietHours.format(start)).arrow().onClick {
                        start = (start + 60) % 1440
                        PetPrefs.setQuietStart(ctx, start)
                    }.spot("sleep.start")

                    item("结束时间", QuietHours.format(end)).arrow().onClick {
                        end = (end + 60) % 1440
                        PetPrefs.setQuietEnd(ctx, end)
                    }.spot("sleep.end")

                    // 【逃生口】一段说明文字，仍然享有系统给的左右内边距与分隔线。
                    custom {
                        DhForm.Note(
                            "当前： " + QuietHours.describe(start, end) +
                                if (start > end) "（跨午夜）" else ""
                        )
                    }
                }

                section(id = "help", title = "说明") {
                    custom {
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
    }
}
