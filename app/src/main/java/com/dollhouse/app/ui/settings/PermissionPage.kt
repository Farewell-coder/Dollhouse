package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.dollhouse.app.device.ShizukuBridge
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.home.HomeState
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.qzx.QzxColors
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
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

    /**
     * 【P1-e·设置索引接线】按 rowId 取索引条目，供各行的 `spot =` 使用。
     *
     * 【自纠错】rowId 写错 / 忘了传 spot → 标题退回空串（肉眼可见）。
     * 这里刻意不写 `?: "兜底"` —— 那会把漏登记重新藏起来。
     */
    private fun spot(rowId: String) = SettingsSpots.find(rowId)

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
        // Shizuku：四态（已授权 / 未授权 / 服务未运行 / 未安装）。
        val sh = HomeState.shizuku
        val shGranted = sh == ShizukuBridge.S_GRANTED

        QzxPage(
            title = "权限与保活",

            backIcon = painterResource(Icons.IC_ARROW_LEFT),
            subtitle = "系统授权、后台存活与启停通道",
            onBack = { closeIfOpen(act) },
        ) {
            QzxList(spotTitle = { spot(it)?.title }) {

                // ---------- 系统授权 ----------
                section(
                    id = "system",
                    title = "系统授权",
                    titleIconRes = Icons.IC_SHIELD_CHECK,
                    collapsible = true,
                ) {
                    item(
                        "Shizuku",
                        value = when (sh) {
                            ShizukuBridge.S_GRANTED -> "已授权"
                            ShizukuBridge.S_NOT_RUNNING -> "待处理"
                            ShizukuBridge.S_NOT_INSTALLED -> "待处理"
                            else -> "未授权"
                        },
                        iconRes = 
                            when (sh) {
                                ShizukuBridge.S_GRANTED -> Icons.IC_CHECK_CIRCLE
                                ShizukuBridge.S_NOT_RUNNING -> Icons.IC_WARNING
                                ShizukuBridge.S_NOT_INSTALLED -> Icons.IC_WARNING
                                else -> Icons.IC_X_CIRCLE
                            }
                        
                    ).desc("通过 ADB 授权后可用系统级能力")
                        .valueTint(
                            when (sh) {
                                ShizukuBridge.S_GRANTED -> QzxColors.ok
                                ShizukuBridge.S_NOT_RUNNING,
                                ShizukuBridge.S_NOT_INSTALLED -> QzxColors.danger
                                else -> null
                            }
                        )
                        .arrow(!shGranted)
                        .spot("permission.shizuku")
                        // 【已授权不可点】旧 `PermRow` 在 granted 时**压根不挂点击**；
                        //   这里同样不设 onClick —— 不给"点了没反应"的假可点态。
                        .let { b -> if (!shGranted) b.onClick { HomeUi.shizukuClicked(act) } else b }

                    item(
                        "悬浮窗",
                        value = grantedText(HomeState.overlay),
                        iconRes = grantedIcon(HomeState.overlay)
                    ).desc("人偶要浮在其他应用上就必须有它")
                        .valueTint(grantedTint(HomeState.overlay))
                        .arrow()
                        .spot("permission.overlay")
                        .onClick { HomeUi.requestOverlay(act) }

                    item(
                        "通知",
                        value = grantedText(HomeState.notif),
                        iconRes = grantedIcon(HomeState.notif)
                    ).desc("保活通知与消息提醒")
                        .valueTint(grantedTint(HomeState.notif))
                        .arrow()
                        .spot("permission.notification")
                        .onClick { HomeUi.requestNotif(act) }

                    item(
                        "电池优化白名单",
                        value = grantedText(HomeState.battery),
                        iconRes = grantedIcon(HomeState.battery)
                    ).desc("加入后不容易被系统清掉")
                        .valueTint(grantedTint(HomeState.battery))
                        .arrow()
                        .spot("permission.battery")
                        .onClick { HomeUi.requestIgnoreBattery(act) }
                }

                // ---------- 后台存活 ----------
                section(
                    id = "keepalive",
                    title = "后台存活",
                    titleIconRes = Icons.IC_ACTIVITY,
                    collapsible = true,
                ) {
                    switch(
                        "无感保活",
                        checked = HomeState.keepAlive,
                        iconRes = Icons.IC_ACTIVITY
                    ).desc("允许应用持续执行相关后台任务")
                        .spot("permission.keepalive")
                        .onChecked { on -> HomeUi.onKeepAliveChanged(on, act) }

                    switch(
                        "隐藏后台卡片",
                        checked = HomeState.hideRecents,
                        iconRes = Icons.IC_EYE_OFF
                    ).desc("不在最近任务里露出这一页")
                        .spot("permission.hideRecents")
                        .onChecked { on -> HomeUi.onHideRecentsChanged(on, act) }
                }

                // ---------- 启停通道 ----------
                // 【为什么把「磁贴」与「开关指令」放同组】它们是同一条启停通道的不同入口：
                //   磁贴是系统快捷开关，指令是可粘贴到外部（如自动化工具）的文本。
                section(
                    id = "channel",
                    title = "启停通道",
                    titleIconRes = Icons.IC_LAYOUT_GRID,
                    collapsible = true,
                ) {
                    switch(
                        "快捷磁贴",
                        checked = HomeState.tile,
                        iconRes = Icons.IC_LAYOUT_GRID
                    ).desc("从下拉快捷开关直接起停人偶")
                        .spot("permission.tile")
                        .onChecked { on -> HomeUi.onTileSwitchChanged(on, act) }

                    item(
                        "开关指令",
                        value = "复制",
                        iconRes = Icons.IC_LINK_2
                    ).desc("复制一条可粘贴到别处的指令")
                        .arrow()
                        .spot("permission.shellCommand")
                        .valueTint()
                        .onClick { HomeUi.copyToggleLink(act) }
                }
            }
        }
    }

    /** 权限行的值文案（与旧 `PermRow` 的 `已授权 / 未授权` 完全一致）。 */
    private fun grantedText(granted: Boolean) = if (granted) "已授权" else "未授权"

    /** 权限行的状态图标（与旧 `PermRow` 的 CHECK_CIRCLE / X_CIRCLE 一致）。 */
    private fun grantedIcon(granted: Boolean) =
        if (granted) Icons.IC_CHECK_CIRCLE else Icons.IC_X_CIRCLE

    /** 权限行的值色：已授权用成功色，未授权保持默认副文本色。 */
    private fun grantedTint(granted: Boolean) = if (granted) QzxColors.ok else null

}