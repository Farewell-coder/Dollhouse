package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.update.UpdateChannel
import com.dollhouse.app.update.UpdateCheckResult
import com.dollhouse.app.update.UpdateChecker
import com.dollhouse.app.update.UpdatePrefs

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
    const val TAG_PAGE = "feiyu_about_page"

    /** 作者（与参考图一致，用户只要求改软件名与描述）。 */
    private const val AUTHOR = "aerree"

    /** 反馈群（QQ 群号，与参考图一致）。 */
    private const val GROUP = "864339949"

    /** 返回键用：本页在栈顶就退一层并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return PageStack.popIfTop(ctx, TAG_PAGE)
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(ctx: Context): View? {
        val act = findActivity(ctx) ?: return null
        // Compose 运行所需 owner（原生 Activity 不自动装），幂等。
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { AboutContent(act) }
    }

    /** 设置页「关于」入口行的落地动作。 */
    @JvmStatic
    fun open(ctx: Context) {
        try {
            PageStack.push(ctx, TAG_PAGE) { buildPage(ctx) }
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
                    "她能记住你们聊过的事：聊天过程中会自动把值得记的东西整理成条目存进记忆库，"
                        + "下次开口前按相关性挑出来带上。每只人偶一份独立记忆库，互不可见。"
                        + "所有对话、记忆与配置都只留在这台设备上，不上传任何服务器。"
                )
                Body(
                    "玩法很简单：给她配一个模型端点 → 点她说话。"
                        + "想让她记得更准，可以在「设置 → 实验 → 记忆库设置」里写一条提取规则；"
                        + "不想要自动记忆，把那里的开关关掉即可，原文一条都不会动。"
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
                // 【更新检查】入口行放版本下方 —— 用户找「我是不是最新版」时视线就在这里。
                UpdateEntry(act)
            }
        }
    }

    /* ============================ 检测更新 ============================ */

    /**
     * 「检测更新」入口行 + 结果弹窗。
     *
     * 【为什么整块自包含】关于页的其余部分只读展示；更新这块带状态（检查中 / 内容）、
     *   带弹窗。把它收进一个私有 @Composable，[AboutContent] 保持「一行一个卡片」的可读性。
     *
     * 【状态放哪】用 `remember` 存在本页组合里：离开关于页即释放，不引入 ViewModel ——
     *   检查本身是「一次性查询」，没有需要跨页保活的状态。后台调度与节流都在
     *   [UpdateChecker]，与界面生命周期无关。
     *
     * 【三态呈现】Available / UpToDate / Failed 严格分开（需求 4.1）：
     *   · Available → 弹窗展示「当前版本 → 新版本」+ 更新日志 + 「去下载 / 稍后」；
     *   · UpToDate   → 弹窗告知已是最新；
     *   · Failed     → 弹窗展示失败原因（手动检查必须给反馈）。
     */
    @Composable
    private fun UpdateEntry(act: Activity) {
        var checking by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
        var channel by remember { mutableStateOf(UpdatePrefs.channel(act)) }

        // ---- 结果弹窗 ----
        val r = result
        if (r != null) {
            when (r) {
                is UpdateCheckResult.Available -> UpdateAvailableDialog(
                    act = act,
                    available = r,
                    onDismiss = { result = null },
                    onSkip = {
                        UpdatePrefs.setSkippedVersion(act, r.candidate.versionName)
                        result = null
                    }
                )
                is UpdateCheckResult.UpToDate -> DhForm.Alert(
                    title = "已是最新版本",
                    onDismiss = { result = null },
                    posText = "好",
                    onPos = { result = null }
                ) {
                    Text(
                        text = "当前 v" + r.currentVersion + " 已是「" + r.channel.label + "」的最新版本。",
                        color = DhTokens.colors.sub,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts,
                        lineHeight = (UiKit.FS_BTN + 8f).sp
                    )
                }
                is UpdateCheckResult.Failed -> DhForm.Alert(
                    title = "检查更新失败",
                    onDismiss = { result = null },
                    posText = "好",
                    onPos = { result = null }
                ) {
                    Text(
                        text = r.error.message,
                        color = DhTokens.colors.sub,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts,
                        lineHeight = (UiKit.FS_BTN + 8f).sp
                    )
                }
            }
        }

        // ---- 通道切换 ----
        // 【为什么放这里】双通道（稳定版 / 公测版）是「这次更新想跟哪条线」的选择，
        //   与「检测」动作强相关；放进设置页深处反而找不到。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "更新通道",
                modifier = Modifier.weight(1f),
                color = DhTokens.colors.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            ChannelChip(
                text = UpdateChannel.STABLE.label,
                selected = channel == UpdateChannel.STABLE
            ) {
                channel = UpdateChannel.STABLE
                UpdatePrefs.setChannel(act, UpdateChannel.STABLE)
            }
            Spacer(Modifier.width(8.dp))
            ChannelChip(
                text = UpdateChannel.BETA.label,
                selected = channel == UpdateChannel.BETA
            ) {
                channel = UpdateChannel.BETA
                UpdatePrefs.setChannel(act, UpdateChannel.BETA)
            }
        }

        // ---- 检测按钮 ----
        DhKit.ActionRow(
            label = if (checking) "正在检查…" else "检测更新",
            iconRes = Icons.IC_REFRESH,
            desc = channel.label + " · 需要联网",
            enabled = !checking,
            onClick = {
                checking = true
                // 【回调必回主线程】UpdateChecker 内部已 post 到 MAIN，这里直接改状态即可。
                UpdateChecker.manualCheck(act) { res ->
                    checking = false
                    result = res
                }
            }
        )
    }

    /**
     * 通道选择药丸。
     *
     * 【为什么不用 [DhKit.OutlineChip]】它只有「未选」一种长相，表达不了选中态；
     *   这里需要「选中 = 主题色实底 + 反白字」的明确对比（与底栏选中态同语言）。
     */
    @Composable
    private fun ChannelChip(text: String, selected: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (selected) c.acc else c.cardUp)
                .then(if (selected) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(999.dp)))
                .pressable(onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                color = if (selected) c.onAcc else c.title,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold
                else androidx.compose.ui.text.font.FontWeight.Normal
            )
        }
    }

    /**
     * 「发现新版本」弹窗：当前版本 → 新版本、更新日志、「去下载」「稍后」。
     *
     * 【为什么按钮是「稍后」而不是「取消」】用户已经知道有新版本了，这一栏表达的是
     *   「这次先不装」，不是「我没看到」。
     *
     * 【为什么没有「不再提醒」主按钮】它属于低频且不可逆（会吞掉后续同类提醒），
     *   放成第三个文字入口，避免误触。
     */
    @Composable
    private fun UpdateAvailableDialog(
        act: Activity,
        available: UpdateCheckResult.Available,
        onDismiss: () -> Unit,
        onSkip: () -> Unit
    ) {
        val c = DhTokens.colors
        DhForm.Alert(
            title = "发现新版本",
            onDismiss = onDismiss,
            posText = "去下载",
            onPos = {
                openDownload(act, available)
            },
            negText = "稍后",
            content = {
                Text(
                    text = "v" + available.currentVersion + "  →  v" + available.candidate.versionName +
                            "（" + available.channel.label + "）",
                    color = c.acc,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                val notes = available.candidate.notes
                if (notes.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.soft)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = notes,
                            color = c.sub,
                            fontSize = UiKit.FS_TINY.sp,
                            fontFamily = DhTokens.fonts,
                            lineHeight = (UiKit.FS_TINY + 6f).sp
                        )
                    }
                }
                // 「不再提醒」做成一行低调文字，与主按钮拉开层级。
                Text(
                    text = "不再提醒此版本",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .pressable(onSkip),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        )
    }

    /**
     * 打开下载：优先 APK 直链，没有则回退到 Release 页面（需求 5「第一阶段仅跳转」）。
     *
     * 【为什么只跳浏览器】应用内下载要处理 FileProvider、存储权限、签名校验、
     *   安装 Intent 与未知来源授权，风险面大得多；系统浏览器 + 系统安装器这条链路
     *   自带签名校验与用户确认，是更稳的第一阶段形态（需求原文即如此）。
     */
    private fun openDownload(act: Activity, available: UpdateCheckResult.Available) {
        val url = available.candidate.apkUrl.ifEmpty { available.candidate.releaseUrl }
        if (url.isEmpty()) {
            return
        }
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setData(android.net.Uri.parse(url))
            act.startActivity(intent)
        } catch (t: Throwable) {
            // 没有浏览器 / 无可处理该链接的应用：不崩，只记日志。
            Logs.w(LOG_TAG, "ignored", t)
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
                // 【r12】22f 不在 [Design] 的图标五档内 → 归到 SZ_LG(24)。
                DhKit.Icon(iconRes = Icons.IC_WHALE, sizeDp = Design.SZ_LG, color = c.acc)
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
