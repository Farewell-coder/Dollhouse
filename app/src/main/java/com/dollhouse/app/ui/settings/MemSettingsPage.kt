package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import androidx.compose.ui.res.painterResource
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.qzx.QzxButtonSpec
import com.dollhouse.app.ui.qzx.QzxButtonStyle
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
import com.dollhouse.app.ui.qzx.buttons
import com.dollhouse.app.ui.qzx.stepper
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】「记忆库设置」页：全局记忆策略（自动保存 / 间隔 / 提取规则 / 检索 / 权重 / 模式）。
 *
 * 【入口】设置页「实验」卡片下级的一行 → 本页（用户定案：多出来的全局设置都放实验卡片下级）。
 *
 * 【为什么是全局而不是每人偶一份】这些是**抽取与检索的策略**，与具体某只人偶无关；
 *   记忆数据本身按人偶分库（见 [com.dollhouse.app.data.Mem]），策略是共用的。
 *
 * 【控件选择】权重 0~10、间隔档位 —— 都用 [DhKit.StepperRow] 而不是滑条：
 *   数值是离散档位，步进器比滑条更容易精确命中，也与设置页其它项保持同一套观感。
 *
 * 【坑】页面靠 tag 认领，找不到 Activity 就静默返回（与其它子页一致）。
 */
object MemSettingsPage {
    const val TAG_PAGE = "feiyu_mem_settings_page"

    /**
     * 【P1-e·设置索引接线】按 rowId 取索引条目，供各行的 `spot =` 使用。
     * 【自纠错】rowId 写错 / 忘了传 spot → 标题退回空串（肉眼可见）。不要加 `?: "兜底"`。
     */
    private fun spot(rowId: String) = SettingsSpots.find(rowId)

    /** 返回键用：本页在栈顶就退一层并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return PageStack.popIfTop(ctx, TAG_PAGE)
    }

    @JvmStatic
    fun buildPage(ctx: Context): View? {
        val act = findActivity(ctx) ?: return null
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @JvmStatic
    fun open(ctx: Context) {
        try {
            PageStack.push(ctx, TAG_PAGE) { buildPage(ctx) }
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        var i = 0
        while (i < 8 && c != null) {
            if (c is Activity) {
                return c
            }
            if (c is ContextWrapper) {
                c = c.baseContext
            } else {
                break
            }
        }
        return null
    }

    @Composable
    private fun Content(act: Activity) {
        // 每次改动都 bump 一次，让控件立刻反映到界面（prefs 是同步读的）。
        var tick by remember { mutableStateOf(0) }

        QzxPage(
            title = "记忆库设置",
            subtitle = "抽取与检索的全局策略",
            onBack = { closeIfOpen(act) },
        ) {
            QzxList(spotTitle = { spot(it)?.title }) {

                // ---------------- 自动保存 ----------------
                section(id = "autosave", title = "自动保存", collapsible = true) {
                    note("开着她才会在聊天里自己记东西；关掉后只能手动加。")
                    switch(
                        "自动整理",
                        checked = remember(tick) { PetPrefs.mem2Auto(act) },
                        icon = painterResource(Icons.IC_SAVE)
                    ).spot("mem.autosave")
                        .onChecked { on ->
                            PetPrefs.setMem2Auto(act, on)
                            tick++
                        }
                    val idx = remember(tick) { PetPrefs.mem2IntervalIndex(act) }
                    stepper(
                        title = "整理间隔",
                        value = PetPrefs.MEM2_INTERVALS[idx].toString() + " 分钟",
                        canMinus = idx > 0,
                        canPlus = idx < PetPrefs.MEM2_INTERVALS.size - 1,
                        onMinus = {
                            PetPrefs.setMem2IntervalIndex(act, idx - 1)
                            tick++
                        },
                        onPlus = {
                            PetPrefs.setMem2IntervalIndex(act, idx + 1)
                            tick++
                        }
                    )
                    note("每隔这么久，后台会把这段时间的新内容整理一次写进记忆库。")
                }

                // ---------------- 提取规则 ----------------
                section(id = "rule", title = "提取规则", collapsible = true) {
                    note(
                        "写一段你的要求，会拼进抽取时的提示词。" +
                            "比如「只记项目决策和我的偏好，按项目分类」。"
                    )
                    var rule by remember(tick) { mutableStateOf(PetPrefs.memExtractRule(act)) }
                    input("提取规则", rule)
                        .hint("留空表示不加额外规则")
                        .multiline(minHeight = 80)
                        .onValueChange { rule = it }
                    buttons(
                        QzxButtonSpec("保存规则", style = QzxButtonStyle.Primary) {
                            PetPrefs.setMemExtractRule(act, rule)
                            tick++
                        }
                    )
                }

                // ---------------- 检索 ----------------
                section(id = "retrieve", title = "检索", collapsible = true) {
                    note("开着时，每次开口前按相关性挑几条记忆带上；关掉就只按时间取最近的。")
                    switch(
                        "按相关性检索",
                        checked = remember(tick) { PetPrefs.mem2Search(act) },
                        icon = painterResource(Icons.IC_SCAN)
                    ).spot("mem.retrieve")
                        .onChecked { on ->
                            PetPrefs.setMem2Search(act, on)
                            tick++
                        }

                    // 模式：一键预设三档权重。
                    val mode = remember(tick) { PetPrefs.mem2Mode(act) }
                    stepper(
                        title = "检索模式",
                        value = MODE_NAMES[mode],
                        canMinus = mode > 0,
                        canPlus = mode < MODE_NAMES.size - 1,
                        onMinus = {
                            PetPrefs.setMem2Mode(act, mode - 1)
                            tick++
                        },
                        onPlus = {
                            PetPrefs.setMem2Mode(act, mode + 1)
                            tick++
                        }
                    )
                    note("平衡：三者兼顾；关键词优先：只认字面命中；关联优先：顺着记忆之间的连线找。")

                    // 权重细调：0~10。
                    val wk = remember(tick) { PetPrefs.mem2KeywordWeight(act) }
                    stepper(
                        title = "关键词权重",
                        value = wk.toString(),
                        hint = "你说的字在记忆里出现得越多，分越高。",
                        canMinus = wk > 0,
                        canPlus = wk < 10,
                        onMinus = { PetPrefs.setMem2KeywordWeight(act, wk - 1); tick++ },
                        onPlus = { PetPrefs.setMem2KeywordWeight(act, wk + 1); tick++ }
                    )
                    val wt = remember(tick) { PetPrefs.mem2TagWeight(act) }
                    stepper(
                        title = "标签权重",
                        value = wt.toString(),
                        hint = "命中了记忆的标签时额外加分。",
                        canMinus = wt > 0,
                        canPlus = wt < 10,
                        onMinus = { PetPrefs.setMem2TagWeight(act, wt - 1); tick++ },
                        onPlus = { PetPrefs.setMem2TagWeight(act, wt + 1); tick++ }
                    )
                    val we = remember(tick) { PetPrefs.mem2EdgeWeight(act) }
                    stepper(
                        title = "连线权重",
                        value = we.toString(),
                        hint = "命中一条后，顺着连线把它旁边的记忆也带出来。",
                        canMinus = we > 0,
                        canPlus = we < 10,
                        onMinus = { PetPrefs.setMem2EdgeWeight(act, we - 1); tick++ },
                        onPlus = { PetPrefs.setMem2EdgeWeight(act, we + 1); tick++ }
                    )
                }

                // ---------------- 说明 ----------------
                section(id = "help", title = "说明", collapsible = true) {
                    note(
                        "每只人偶一份独立记忆库，互不可见。要单独查看或编辑某只人偶的记忆，" +
                            "去「人偶 → 编辑 → 人格 → 记忆库」。"
                    )
                }
            }
        }
    }
    private val MODE_NAMES = arrayOf("平衡", "关键词优先", "关联优先")
}