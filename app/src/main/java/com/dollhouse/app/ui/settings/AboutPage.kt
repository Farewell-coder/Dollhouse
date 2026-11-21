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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.kit.DhLoadingState
import com.dollhouse.app.ui.kit.IconSeat
import com.dollhouse.app.ui.kit.IconSeatShape
import com.dollhouse.app.ui.kit.IconSeatSize
import com.dollhouse.app.ui.kit.LoadingState
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.update.UpdateChannel
import com.dollhouse.app.update.UpdateCheckResult
import com.dollhouse.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dollhouse.app.update.UpdatePrefs
import android.content.Intent

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

    /**
     * 【P1-e·设置索引接线】按 rowId 取索引条目，供各行的 `spot =` 使用。
     * 【自纠错】rowId 写错 / 忘了传 spot → 标题退回空串（肉眼可见）。不要加 `?: "兜底"`。
     */
    private fun spot(rowId: String) = SettingsSpots.find(rowId)

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
        // 【B4·合规】本页内有两个视图：关于（默认）/ 开源许可。
        //   【为什么不新开一层 PageStack 页】`NavKeyCodec.toKey` 是**白名单**：没登记的 key
        //   返回 null → `NavHost.push` 静默失败，表现为「点了没反应」（既不崩也不报错，
        //   本项目为这个坑专门写过注释）。新开一层要同时改 NavKeyCodec 常量 / Route 类 /
        //   双向映射 / PageRegistry —— 那是另一批改动。这里用「一层页面 + 内部视图」，
        //   与 `DeveloperOptionsPage`（五个子屏同处一层）完全同构，**零导航改动**。
        var showLicenses by remember { mutableStateOf(false) }
        // 系统返回键：先在页内从「开源许可」退回「关于」，再交给 PageStack 弹层。
        //   不装这个拦截的话，用户在许可页按返回会**直接退回设置页**（跨过关于页）。
        PageStack.backInterceptor = PageStack.BackInterceptor(AboutPage.TAG_PAGE) {
            if (showLicenses) {
                showLicenses = false
                true
            } else {
                false
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                if (PageStack.backInterceptor?.key == AboutPage.TAG_PAGE) {
                    PageStack.backInterceptor = null
                }
            }
        }
        DhKit.Page(
            title = if (showLicenses) "开源许可" else "关于",
            sub = if (showLicenses) "随 APK 分发的第三方许可文本" else "关于本软件",
            onBack = { if (showLicenses) showLicenses = false else closeIfOpen(act) }
        ) {
            if (showLicenses) {
                LicenseBrowser(act)
                // 早退而不是把下面整段包进 else：包起来要重排 40 多行缩进，
                // 而这段代码正被另一批排版改动同时编辑 —— 冲突面越小越好。
                return@Page
            }
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
                DhKit.ActionRow(
                    spot = spot("about.diagnostics"),
                    iconRes = Icons.IC_SHIELD_CHECK,
                    desc = "本地日志、错误详情与诊断包"
                ) { DeveloperOptionsPage.open(act) }
                // 【B4·合规】开源许可入口。
                //   【为什么必须有】审计发现：`licenses/` 与 `THIRD_PARTY_NOTICES.md` 原先
                //   **都不在 APK 内**，应用里也没有任何入口 —— 只下载 APK 的用户拿不到任何
                //   第三方许可文本。现在文本随包（见 app/build.gradle 的 syncLicenseAssets），
                //   这个入口负责让用户**找得到**。
                DhKit.ActionRow(
                    label = "开源许可",
                    iconRes = Icons.IC_LIBRARY,
                    desc = "第三方组件清单与许可原文"
                ) { showLicenses = true }
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
        // 【防抖反馈】`manualCheck` 在短时间内的第二次点击会回调 null（防抖），
        //   那时界面不能毫无变化 —— 用这个临时提示告诉用户「刚才检查过了」。
        //   3 秒后自动消失，避免一直挂着。
        var notice by remember { mutableStateOf<String?>(null) }
        androidx.compose.runtime.LaunchedEffect(notice) {
            if (notice != null) {
                kotlinx.coroutines.delay(3000)
                notice = null
            }
        }
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
                    posText = "知道了",
                    onPos = { result = null }
                ) {
                    Text(
                        text = "当前 v" + r.currentVersion + " 已是「" + r.channel.label + "」的最新版本。",
                        color = DhTokens.colors.sub,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fontsBody,
                        lineHeight = (UiKit.FS_BTN + 8f).sp
                    )
                }
                is UpdateCheckResult.Failed -> DhForm.Alert(
                    title = "检查更新失败",
                    onDismiss = { result = null },
                    posText = "知道了",
                    onPos = { result = null }
                ) {
                    Text(
                        text = r.error.message,
                        color = DhTokens.colors.sub,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fontsBody,
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
                .padding(top = DhDesign.Space.md.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // E16-ALLOW: "更新通道"行标题：4 字固定标签，`weight(1f)` 单行
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
            Spacer(Modifier.width(DhDesign.Space.sm.dp))
            ChannelChip(
                text = UpdateChannel.BETA.label,
                selected = channel == UpdateChannel.BETA
            ) {
                channel = UpdateChannel.BETA
                UpdatePrefs.setChannel(act, UpdateChannel.BETA)
            }
        }

        // ---- 检测按钮 ----
        //
        // 【B4·接入加载态】改造前 `checking` 只用来把按钮文案改成「正在检查…」并禁用 ——
        //   页面其余部分（版本号、通道选择、链接）**没有任何"正在联网"的信号**。
        //   而 `UpdateChecker.manualCheck` 是仓库 API 请求（GitHub Releases），
        //   弱网 / 被墙时会挂很久，用户只能盯着一个变灰的按钮猜。
        //
        //   【为什么是 Refreshing 而不是 FirstLoad】本页主体（版本 / 通道 / 链接）
        //   此刻全部可读，按 MAA 硬规则 1 不许遮挡已有内容 —— 只加一行细圈在按钮上方。
        DhLoadingState(
            state = if (checking) LoadingState.Refreshing else LoadingState.Idle,
            label = "正在向仓库查询最新版本…"
        )
        DhKit.ActionRow(
            label = if (checking) "正在检查…" else "检测更新",
            iconRes = Icons.IC_REFRESH,
            desc = notice ?: (channel.label + " · 需要联网"),
            enabled = !checking,
            onClick = {
                checking = true
                // 【回调必回主线程】UpdateChecker 内部已 post 到 MAIN，这里直接改状态即可。
                UpdateChecker.manualCheck(act) { res ->
                    checking = false
                    when (res) {
                        // 【改·检测到新版本直接去发行版页】用户明确要求：
                        //   「检测到最新版本就弹到那个最新的发行版那里」。
                        //   原来弹一个对话框再让用户点「下载」，多一步、而且直链 APK 容易失效；
                        //   现在直接打开那条 Release 的页面，更新说明 / 资产 / 校验值都在那里。
                        is UpdateCheckResult.Available -> openReleasePage(act, res.candidate.releaseUrl)
                        // 【修·点了没反应】`manualCheck` 有防抖：距上次检查不足阈值时
                        //   直接回调 **null**，界面毫无变化 —— 用户看到的就是「点击无反应」。
                        //   给一个会自己消失的行内提示（不用 Toast，一闪而过更看不见）。
                        // 【顺序要求】`else` 必须放最后，Kotlin 不允许它后面还有分支。
                        null -> notice = "刚检查过，稍后再试"
                        // 「已是最新」「检查失败」仍走弹窗 —— 那两种需要用户读一句话，
                        //   用 Toast 一闪而过反而看不清。
                        else -> result = res
                    }
                }
            }
        )
    }

    /**
     * 打开某条 Release 的网页。
     *
     * 【为什么用浏览器而不是应用内 WebView】更新说明里有外链、有附件，
     *   WebView 要处理一堆权限与返回栈，且用户可能想「在浏览器里留个标签页」。
     *   【为什么不是 apkUrl 直链】直链会随资产改名失效；发布页永远有效，
     *   而且用户能看到发布说明与校验值。
     */
    private fun openReleasePage(act: android.app.Activity, url: String) {
        val target = url.ifBlank { com.dollhouse.app.update.UpdateRepo.RELEASES_PAGE }
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)))
        } catch (t: Throwable) {
            // 没有浏览器 / 无法解析链接：退化成 Toast，至少让用户知道该去哪。
            android.widget.Toast.makeText(act, "打不开链接：" + target, android.widget.Toast.LENGTH_LONG).show()
        }
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
            // 【修·文字不在中心】`Box` 的 `contentAlignment` 默认是 **TopStart**，
            //   这里只设了 padding 没设对齐 —— 药丸里文字会贴在左上角。
            //   「稳定版」「公测版」这两个按钮就是这么偏的。
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(DhDesign.Radius.pill.dp))
                .background(if (selected) c.acc else c.cardUp)
                .then(if (selected) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(DhDesign.Radius.pill.dp)))
                /*
                 * 【P0-3·无障碍：更新通道药丸补选中语义】
                 *   原来 `selected` 只换底与字色（实心主色 + 反白字），**零语义** ——
                 *   读屏把「稳定版」「公测版」念成两个普通按钮，
                 *   用户**听不出当前跟的是哪条更新线**，而这是决定"会不会收到测试版"的关键信息。
                 *
                 *   【mergeDescendants = true】内部只有 1 个 `Text(text)`。
                 *     不合并 → 父节点（有语义）与 `Text` 是两个可聚焦节点，多划一次；
                 *     合并 → 一次念清「稳定版，已选中」。
                 *
                 *   【role 用 `RadioButton` 而不是 `Role.Tab` —— 这里与 Tab 有实质区别】
                 *     这两个药丸表达「二选一」的**设置项**（更新通道），不是主导航。
                 *     用 `Role.Tab` 会让读屏把它当标签页、提示"切换标签"手势，
                 *     与真实语义不符（它不切换页面，只改一个持久化设置）。
                 *     `Role.RadioButton` 让读屏念「单选按钮，已选中」——
                 *     准确表达"二者只能选一"。这也是本仓第一次使用该 role，
                 *     全 App 只有这一处是真正的"二选一设置"。
                 *
                 *   【为什么是 `selected` 而不是 `toggleable`】
                 *     `toggleable` 表达"开/关"（每项独立），而这里是"互斥二选一"：
                 *     选中一个必然意味着另一个取消。`selected` + `Role.RadioButton`
                 *     正好表达这个语义；用 `toggleable` 会让读屏念「已开启」，
                 *     用户可能以为两项都能同时打开。
                 */
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    role = Role.RadioButton
                    contentDescription = text
                }
                .pressable(onClick)
                .padding(horizontal = 12.dp, vertical = DhDesign.Space.sm.dp)
        ) {
            // E16-ALLOW: 通道 Chip（"稳定版"/"公测版"）：药丸定高容器
            Text(
                text = text,
                color = if (selected) c.onAcc else c.title,
                fontSize = UiKit.FS_CHIP.sp,
                fontFamily = if (selected) DhTokens.fontsBold else DhTokens.fonts,
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
                // 【E16·真问题（原 ALLOW 理由错：含 channel.label 中文，≈22 视觉字）】补行高
                Text(
                    text = "v" + available.currentVersion + "  →  v" + available.candidate.versionName +
                            "（" + available.channel.label + "）",
                    color = c.acc,
                    fontSize = UiKit.FS_BTN.sp,
                    lineHeight = DhTokens.type.body.lineHeight,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
                )
                val notes = available.candidate.notes
                if (notes.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                            .background(c.soft)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = notes,
                            color = c.sub,
                            fontSize = UiKit.FS_TINY.sp,
                            fontFamily = DhTokens.fontsBody,
                            lineHeight = (UiKit.FS_TINY + 6f).sp
                        )
                    }
                }
                // 「不再提醒」做成一行低调文字，与主按钮拉开层级。
                // E16-ALLOW: "不再提醒此版本"：7 字固定文案，`fillMaxWidth` 单行低调文字
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
            // 【v3·清透轻拟物】圆角方块 → **圆形浅底**（用户定案"图标配浅色圆形背景"）。
            //   40dp = IconSeatSize.MD，图标 20dp 落在 iconMd 档。
            //   底色显式传 card 保持品牌鲸鱼的观感；圆形态自带 e1 阴影 + 顶部高光。
            IconSeat(
                iconRes = Icons.IC_WHALE,
                sizeDp = IconSeatSize.MD,
                shape = IconSeatShape.CIRCLE,
                bg = c.card,
                tint = c.acc
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                color = c.title,
                fontSize = DhTokens.type.section.fontSize, lineHeight = DhTokens.type.section.lineHeight,
                fontFamily = DhTokens.fontsBold,
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
            fontFamily = DhTokens.fontsBody,
            lineHeight = (UiKit.FS_BTN + 8f).sp
        )
    }

    /** 一行「左名称 + 右值」；pill=true 时值做成主题色药丸。← `AboutPage.infoRow`。 */
    @Composable
    private fun InfoRow(name: String, value: String, pill: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = DhDesign.Space.sm.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // E16-ALLOW: InfoRow 的名称列（"作者"/"反馈群"/"版本"）：固定 2~3 字，`weight(1f)`
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
                // E16-ALLOW: InfoRow 的值列：来自 `AUTHOR`/`GROUP`/`versionName(act)` 三个常量，固定短串（作者名、QQ 群号、版本号）
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

/**
 * 【B4·合规】「开源许可」的正文：把**随 APK 分发**的第三方许可文本列出来并展示。
 *
 * ## 为什么需要它
 *
 * 2026-11 的许可证审计发现：`licenses/` 与 `THIRD_PARTY_NOTICES.md` 原先**只存在于仓库**，
 * APK 里一个都没有 —— `packaging.resources.excludes`（见 `app/build.gradle`）早就把
 * `META-INF/LICENSE*` / `NOTICE*` 剔掉了（那对瘦身是对的，但代价是"只下载 APK 的用户
 * 拿到的许可文本约等于零"）。对 GPL-3.0 项目是实质缺口，对 Apache-2.0 §4(a) 也不合格。
 *
 * 现在文本由 `app/build.gradle` 的 `syncLicenseAssets` 在**构建时**从仓库根同步进
 * `assets/licenses/`（单一真源，不会漂移），本函数只负责读出来给用户看。
 *
 * ## 为什么不做 Markdown 渲染
 *
 * 不引新依赖。`THIRD_PARTY_NOTICES.md` 的表格按纯文本显示会略丑，但**内容完整、可读** ——
 * 许可声明的价值在于「原文可核验」，不在排版。
 *
 * ## 为什么 bundled 那 34 份要单独分组 + 截断
 *
 * 它们是 Google（ML Kit / Play services / ODML）要求随分发转交的 `third_party_licenses.txt`，
 * 单份最大 2.3 MB。整段塞进一个 `Text` 会把布局测量拖死，所以：
 * ① 单独分组，不与 10 份许可原文混在一起；② 超长时**截断并明确告知**（见 [MAX_CHARS]）
 * 与被截断后的完整路径 —— 绝不假装那就是全文。
 */
private const val MAX_CHARS = 120_000

/** assets 下的许可目录（由 `app/build.gradle` 的 `syncLicenseAssets` 生成）。 */
private const val LICENSES_DIR = "licenses"

/** 列表里的一项。 */
private data class LicenseEntry(val path: String, val label: String, val desc: String)

/**
 * 许可浏览视图。**不再是独立页**：它渲染在「关于」页的 Page 内（标题/返回由外层提供），
 * 理由见 `AboutContent` 顶部关于「NavKeyCodec 白名单」的注释。
 */
@Composable
private fun LicenseBrowser(act: Activity) {
    val ctx = LocalContext.current.applicationContext
    var groups by remember { mutableStateOf<List<Pair<String, List<LicenseEntry>>>?>(null) }
    /** null = 列表态；非 null = 正在读这一份 */
    var reading by remember { mutableStateOf<LicenseEntry?>(null) }
    var body by remember { mutableStateOf<String?>(null) }
    var bodyFailed by remember { mutableStateOf(false) }

    // ---- 列表：assets 里有什么就列什么（不写死文件名，跟着构建产物走）----
    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) {
            runCatching {
                val out = ArrayList<Pair<String, List<LicenseEntry>>>()
                // `assets.list()` 同时返回文件与子目录；带点的才是文件（`bundled` 会被滤掉）。
                val top = ctx.assets.list(LICENSES_DIR).orEmpty()
                    .filter { it.contains('.') }
                    .sorted()
                val notices = top.filter { it.endsWith(".md") }
                val texts = top.filterNot { it.endsWith(".md") }
                if (notices.isNotEmpty()) {
                    out += "第三方声明（完整清单）" to notices.map {
                        LicenseEntry("$LICENSES_DIR/$it", "第三方声明", "第三方组件清单与许可条款汇总")
                    }
                }
                if (texts.isNotEmpty()) {
                    out += "许可原文" to texts.map {
                        LicenseEntry("$LICENSES_DIR/$it", it, "随 APK 分发的许可文本")
                    }
                }
                val bundled = ctx.assets.list("$LICENSES_DIR/bundled").orEmpty().sorted()
                if (bundled.isNotEmpty()) {
                    out += "随附声明（来自依赖 AAR，共 ${bundled.size} 份）" to bundled.map {
                        LicenseEntry("$LICENSES_DIR/bundled/$it", it, "上游要求随分发转交")
                    }
                }
                out
            }.getOrElse { emptyList<Pair<String, List<LicenseEntry>>>() }
        }
    }

    // ---- 正文：读选中那一份（I/O 走 IO 线程，主线程只拿结果）----
    LaunchedEffect(reading) {
        val e = reading ?: return@LaunchedEffect
        body = null
        bodyFailed = false
        val r = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open(e.path).bufferedReader().use { it.readText() } }
        }
        if (r.isSuccess) {
            body = r.getOrNull().orEmpty()
        } else {
            bodyFailed = true
            Logs.w("Dollhouse", "开源许可读取失败: " + e.path, r.exceptionOrNull())
        }
    }

    val cur = reading
    if (cur == null) {
        val g = groups
        when {
            g == null -> DhLoadingState(state = LoadingState.FirstLoad, label = "正在读取许可文本…")
            g.isEmpty() -> com.dollhouse.app.ui.kit.DhState(
                title = "没有找到许可文本",
                body = "这次构建没有把 licenses/ 同步进 APK。从源码自行构建时，" +
                    "请确认 app/build.gradle 里的 syncLicenseAssets 任务执行过。",
                iconRes = Icons.IC_CIRCLE_ALERT
            )
            else -> g.forEach { (title, items) ->
                DhKit.SectionLabel(title, iconRes = Icons.IC_CLIPBOARD)
                items.forEach { e ->
                    DhKit.ActionRow(
                        label = e.label,
                        iconRes = if (e.path.contains("/bundled/")) Icons.IC_PACKAGE else Icons.IC_NEWSPAPER,
                        desc = e.desc
                    ) { reading = e }
                }
            }
        }
    } else {
        // 顶部给一个「返回列表」—— 页头的返回箭头会**直接退出本页**（它归外层 Page 管），
        // 所以列表态与阅读态之间需要自己的返回入口。
        DhKit.ActionRow(label = "返回列表", iconRes = Icons.IC_ARROW_LEFT, desc = "回到许可文件清单") {
            reading = null
        }
        val text = body
        when {
            bodyFailed -> com.dollhouse.app.ui.kit.DhState(
                title = "这一份读不出来",
                body = "文件可能在这次构建里没有被同步进 APK：" + cur.path,
                iconRes = Icons.IC_CIRCLE_ALERT
            )
            text == null -> DhLoadingState(state = LoadingState.FirstLoad, label = "正在读取…")
            else -> {
                // 完整资源路径：用户报障时能直接对上包内文件，也方便开发者自查。
                DhForm.Note("文件：assets/" + cur.path)
                if (text.length > MAX_CHARS) {
                    DhForm.Note(
                        "这份文本共 " + text.length + " 字符，下面只显示前 " + MAX_CHARS +
                            " 字符（完整原文随包在 assets/" + cur.path + "）。"
                    )
                }
                Text(
                    text = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text,
                    modifier = Modifier.fillMaxWidth(),
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fontsBody,
                    lineHeight = (UiKit.FS_TINY + 6f).sp
                )
            }
        }
    }
}
