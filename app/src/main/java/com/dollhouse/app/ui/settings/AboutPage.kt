package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】「关于」独立页：软件介绍 + 关于本软件（作者 / 反馈群 / 版本）。
 *
 * 【入口】设置页「关于」卡片里的入口行 → open()；返回键 → closeIfOpen()。
 *
 * 【迁移】r7 起正文改为 Jetpack Compose（规格书主线），但**对外契约一字未动**：
 *   仍是 `object` + `@JvmStatic open/closeIfOpen`，仍挂 `android.R.id.content` 且打同一个 [TAG_PAGE]，
 *   仍走 `UiKit.openPage/closePage` 的页面栈与转场。调用方（`HomeUi` / `MainActivity`）零改动。
 *
 * 【为什么页根还是 View】`UiKit.openPage(content, page, tag)` 收的是 `View`，
 *   且 [com.dollhouse.app.ui.theme.GlobalBackground.installPage] 要把背景图铺在**页根的 background** 上。
 *   所以这里用 [ComposeHost.createView] 产出一个 `ComposeView` 当「页」，
 *   内部 100% Compose —— 满足「不要 View 页面」的终态口径（View 只作宿主壳，不承载任何 UI）。
 *
 * 【坑】① 配色全走 [DhTokens]（源自 `UiKit` 的 29 色），不写裸色值；
 *       ② 版本号从 PackageManager 现取，不硬编码；
 *       ③ 反馈群号点击后只做「选中态」不做复制 —— 工程禁用浮层短提示，也不引剪贴板权限。
 */
object AboutPage {
    private const val LOG_TAG = "Dollhouse"
    private const val TAG_PAGE = "feiyu_about_page"

    /** 作者（与参考图一致，用户只要求改软件名与描述）。 */
    private const val AUTHOR = "aerree"

    /** 反馈群（QQ 群号，与参考图一致）。 */
    private const val GROUP = "864339949"

    /** 返回键用：本页开着就关掉并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return try {
            val act = findActivity(ctx) ?: return false
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
            val old = content.findViewWithTag<View>(TAG_PAGE) ?: return false
            UiKit.closePage(old)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 设置页「关于」入口行的落地动作。 */
    @JvmStatic
    fun open(ctx: Context) {
        try {
            val act = findActivity(ctx) ?: return
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            // Compose 运行所需 owner（原生 Activity 不自动装），幂等。
            ComposeHost.installForActivity(act)
            val page = ComposeHost.createView(act) { AboutContent(act) }
            UiKit.openPage(content, page, TAG_PAGE)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
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
            i++
        }
        return null
    }

    /** 页面正文（Compose）。 */
    @Composable
    private fun AboutContent(act: Activity) {
        DhKit.Page(
            title = "关于",
            sub = "关于本软件",
            onBack = { closeIfOpen(act) }
        ) {
            // ---- 卡片 1：软件介绍 ----
            DhKit.Card {
                LogoRow("Dollhouse")
                Body(
                    "一个开源改造的桌宠小工具：一只会在屏幕上陪你的人偶，点她说话、拖着走，"
                        + "长按打开面板。"
                )
                Body(
                    "她能记住你们聊过的事，聊长了会把早期内容压成要点，"
                        + "把这些要点和你的对话一起带进下一次回复。"
                        + "所有对话、记忆与配置都只留在这台设备上，不上传任何服务器。"
                )
                Body(
                    "玩法很简单：给她配一个模型端点 → 点她说话 → 聊久了自动总结。"
                        + "关掉自动总结，她就只保留原文，不会替你压缩。"
                )
                Body("对话内容由所选模型生成，仅供娱乐。")
            }
            // ---- 卡片 2：关于本软件 ----
            DhKit.Card {
                LogoRow("关于本软件")
                InfoRow("作者", AUTHOR, pill = false)
                DhKit.Divider()
                InfoRow("反馈群", GROUP, pill = true)
                DhKit.Divider()
                InfoRow("版本", "v" + versionName(act), pill = false)
            }
        }
    }

    /** 卡片头部：左侧主题色圆角方块 + 右侧标题。← `AboutPage.logoRow`。 */
    @Composable
    private fun LogoRow(title: String) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.card),
                contentAlignment = Alignment.Center
            ) {
                DhKit.Icon(iconRes = Icons.IC_WHALE, sizeDp = 22f, color = c.acc)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                color = c.title,
                fontSize = 18.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
    }

    /** 一段正文。← `AboutPage.body`（FS_BTN / SUB / 行距 4dp / 上下各 5dp）。 */
    @Composable
    private fun Body(text: String) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 5.dp),
            color = DhTokens.colors.sub,
            fontSize = UiKit.FS_BTN.sp,
            fontFamily = DhTokens.fonts,
            lineHeight = (UiKit.FS_BTN + 8f).sp
        )
    }

    /** 一行「左名称 + 右值」；pill=true 时值做成主题色药丸。← `AboutPage.infoRow`。 */
    @Composable
    private fun InfoRow(name: String, value: String, pill: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                color = DhTokens.colors.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            if (pill) {
                DhKit.OutlineChip(value)
            } else {
                Text(
                    text = value,
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /** 版本号：从 PackageManager 现取，避免升级时忘了改。 */
    private fun versionName(ctx: Context): String {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }
    }
}
