package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.runtime.Composable
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.home.HomeState
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】「权限与保活」独立页：系统授权状态 + 保活开关 + 启停通道。
 *
 * 【M-结构收编】这些内容原来直接铺在设置页首页的一张折叠卡里（8 行，含 4 个权限行
 *   与 3 个开关）。设计稿 §4.6 明确要求：
 *     · 「Toggles live on their own sub-screen, never on the hub」
 *     （开关必须有自己的子页，绝不放在设置首页）
 *     · 设置首页只做「分组导航行」，每组不超过 5 行
 *   改造前设置页有 22 个入口挤在 6 张折叠卡里，用户要逐张展开才知道有什么，
 *   且「权限」这种低频、系统级的东西占着首页最显眼的位置。
 *
 * 【为什么是搬家而不是删功能】8 行内容一行未删，全部原样迁入本页：
 *   Shizuku 四态 / 悬浮窗 / 通知 / 电池优化白名单 / 无感保活 / 快捷磁贴 /
 *   隐藏后台卡片 / 开关指令。
 *
 * 【为什么单独一页比折叠卡好】
 *   ① 这些是「设完就走」的项，不该常驻首页；
 *   ② 折叠卡的展开/收起状态还要落盘（`PetPrefs.setCardOpen`），状态多一层；
 *   ③ 权限被拒时用户往往要来回切系统设置页，独立页有正常的返回栈，
 *     返回后仍是这个页面，而不是「回到首页再重新展开卡片」。
 */
object PermissionPage {

    /** 页面 key（进页面栈用）。 */
    const val TAG_PAGE = "settings:permission"

    /** 返回键用：本页在栈顶就退一层并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return PageStack.popIfTop(ctx, TAG_PAGE)
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(ctx: Context): View? {
        val act = findActivity(ctx) ?: return null
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @JvmStatic
    fun open(ctx: Context) {
        PageStack.push(ctx, TAG_PAGE) { buildPage(ctx) }
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
            i++
        }
        return null
    }

    /**
     * 页面正文。
     *
     * 【分组为什么是「系统授权 / 保活 / 启停通道」】
     *   这三组回答的是三个不同问题：谁能让我用系统能力、怎么不被系统杀掉、怎么快捷启停。
     *   混在一起时用户要读完 8 行描述才知道各自属于什么（与设置页「实验」卡此前的问题同型）。
     */
    @Composable
    private fun Content(act: Activity) {
        DhKit.Page(
            title = "权限与保活",
            sub = "系统授权、后台存活与启停通道",
            onBack = { closeIfOpen(act) }
        ) {
            // ---------- 系统授权 ----------
            DhKit.Card(titleIcon = Icons.IC_SHIELD_CHECK, collapsibleTitle = "系统授权") {
                // Shizuku：四态（已授权 / 未授权 / 服务未运行 / 未安装）。
                val sh = HomeState.shizuku
                DhKit.PermRow(
                    name = "Shizuku 授权",
                    iconRes = Icons.IC_KEY_ROUND,
                    desc = "通过 ADB 授权后可用系统级能力",
                    state = when (sh) {
                        com.dollhouse.app.device.ShizukuBridge.S_GRANTED -> 0
                        com.dollhouse.app.device.ShizukuBridge.S_NOT_RUNNING -> 2
                        com.dollhouse.app.device.ShizukuBridge.S_NOT_INSTALLED -> 2
                        else -> 1
                    }
                ) { HomeUi.shizukuClicked(act) }
                DhKit.RowDivider(insetStart = ROW_INSET)
                DhKit.PermRow(
                    name = "悬浮窗权限",
                    iconRes = Icons.IC_APP_WINDOW,
                    desc = "人偶要浮在其他应用上就必须有它",
                    state = if (HomeState.overlay) 0 else 1
                ) {
                    HomeUi.requestOverlay(act)
                }
                DhKit.RowDivider(insetStart = ROW_INSET)
                DhKit.PermRow(
                    name = "通知权限",
                    iconRes = Icons.IC_BELL,
                    desc = "保活通知与消息提醒",
                    state = if (HomeState.notif) 0 else 1
                ) {
                    HomeUi.requestNotif(act)
                }
                DhKit.RowDivider(insetStart = ROW_INSET)
                DhKit.PermRow(
                    name = "电池优化白名单",
                    iconRes = Icons.IC_BATTERY_CHARGING,
                    desc = "加入后不容易被系统清掉",
                    state = if (HomeState.battery) 0 else 1
                ) {
                    HomeUi.requestIgnoreBattery(act)
                }
            }

            // ---------- 后台存活 ----------
            DhKit.Card(titleIcon = Icons.IC_ACTIVITY, collapsibleTitle = "后台存活") {
                DhKit.SwitchRow(
                    name = "无感保活",
                    iconRes = Icons.IC_ACTIVITY,
                    desc = "允许应用持续执行相关后台任务",
                    checked = HomeState.keepAlive
                ) { on ->
                    HomeUi.onKeepAliveChanged(on, act)
                }
                DhKit.RowDivider(insetStart = ROW_INSET)
                DhKit.SwitchRow(
                    name = "隐藏后台卡片",
                    iconRes = Icons.IC_EYE_OFF,
                    desc = "不在最近任务里露出这一页",
                    checked = HomeState.hideRecents
                ) { on ->
                    HomeUi.onHideRecentsChanged(on, act)
                }
            }

            // ---------- 启停通道 ----------
            // 【为什么把「磁贴」与「开关指令」放同组】它们是同一条启停通道的不同入口：
            //   磁贴是系统快捷开关，指令是可粘贴到外部（如自动化工具）的文本。
            DhKit.Card(titleIcon = Icons.IC_LAYOUT_GRID, collapsibleTitle = "启停通道") {
                DhKit.SwitchRow(
                    name = "快捷设置磁贴",
                    iconRes = Icons.IC_LAYOUT_GRID,
                    desc = "从下拉快捷开关直接起停人偶",
                    checked = HomeState.tile
                ) { on ->
                    HomeUi.onTileSwitchChanged(on, act)
                }
                DhKit.RowDivider(insetStart = ROW_INSET)
                DhKit.ValueRow(
                    label = "开关指令",
                    iconRes = Icons.IC_LINK_2,
                    desc = "复制一条可粘贴到别处的指令",
                    value = "复制",
                    showChevron = true
                ) {
                    HomeUi.copyToggleLink(act)
                }
            }
        }
    }

    /** 行分隔线左缩进：行首图标 20dp + 图标间距 8dp。 */
    private const val ROW_INSET = 28
}