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
import com.dollhouse.app.ui.qzx.preview
import com.dollhouse.app.ui.qzx.chips
import com.dollhouse.app.ui.qzx.QzxPage
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxDialog
import com.dollhouse.app.ui.qzx.QzxChip
import androidx.compose.ui.res.painterResource
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
        // 【更新检查的状态归页面】旧实现把"检查中 / 结果 / 3 秒提示 / 通道"藏在
        //   `UpdateEntry` 这个 composable 里，于是它无法被声明式列表表达（列表里只能加行）。
        //   现在把这几个状态提到页面，行交给 `QzxList`，弹窗用 `QzxDialog` ——
        //   逻辑一行未改，只是"状态"和"排版"分开了。
        var checking by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        androidx.compose.runtime.LaunchedEffect(notice) {
            if (notice != null) {
                kotlinx.coroutines.delay(3000)
                notice = null
            }
        }
        var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
        var channel by remember { mutableStateOf(UpdatePrefs.channel(act)) }
        fun manualCheck() {
            checking = true
            // 【回调必回主线程】UpdateChecker 内部已 post 到 MAIN，这里直接改状态即可。
            UpdateChecker.manualCheck(act) { res ->
                checking = false
                when (res) {
                    // 【检测到新版本直接去发行版页】用户明确要求：更新说明 / 资产 / 校验值都在那里。
                    is UpdateCheckResult.Available -> openReleasePage(act, res.candidate.releaseUrl)
                    // 【修·点了没反应】`manualCheck` 有防抖：距上次检查不足阈值时直接回调 null。
                    null -> notice = "刚检查过，稍后再试"
                    // 「已是最新」「检查失败」仍走弹窗 —— 那两种需要用户读一句话。
                    else -> result = res
                }
            }
        }

        QzxPage(
            title = if (showLicenses) "开源许可" else "关于",

            backIcon = painterResource(Icons.IC_ARROW_LEFT),
            subtitle = if (showLicenses) "随 APK 分发的第三方许可文本" else "关于本软件",
            onBack = { if (showLicenses) showLicenses = false else closeIfOpen(act) },
        ) {
            if (showLicenses) {
                // 【整屏文本浏览器不进列表】它是"许可原文"这种**长文阅读界面**，不是一组行；
                //   直接作为页面内容调用即可 —— 列表承载不了它，也不需要它承载。
                LicenseBrowser(act)
                return@QzxPage
            }
            QzxList(spotTitle = { spot(it)?.title }) {

                // ---- 卡片 1：软件介绍 ----
                section(id = "intro") {
                    preview(
                        image = null,
                        fallbackIconRes = Icons.IC_WHALE,
                        title = "Dollhouse"
                    )
                    note(
                        "一个开源改造的桌宠小工具：一只会在屏幕上陪你的人偶，点她说话、拖着走，" +
                            "长按打开面板。"
                    )
                    note(
                        "她能记住你们聊过的事：聊天过程中会自动把值得记的东西整理成条目存进记忆库，" +
                            "下次开口前按相关性挑出来带上。每只人偶一份独立记忆库，互不可见。" +
                            "所有对话、记忆与配置都只留在这台设备上，不上传任何服务器。"
                    )
                    note(
                        "玩法很简单：给她配一个模型端点 → 点她说话。" +
                            "想让她记得更准，可以在「设置 → 实验 → 记忆库设置」里写一条提取规则；" +
                            "不想要自动记忆，把那里的开关关掉即可，原文一条都不会动。"
                    )
                    note("对话内容由所选模型生成，仅供娱乐。")
                }

                // ---- 卡片 2：关于本软件 ----
                section(id = "info") {
                    preview(
                        image = null,
                        fallbackIconRes = Icons.IC_WHALE,
                        title = "关于本软件"
                    )
                    item("作者", AUTHOR)
                    // 【pill = true】反馈群号在原设计里是描边药丸（可读、可复制的群号）。
                    //   新系统没有药丸值，用强调色表达"这是个要留意的值"。
                    item("反馈群", GROUP).valueTint()
                    item("版本", "v" + versionName(act))

                    // 【更新通道】双通道是"这次跟哪条线"的选择，与检测动作强相关。
                    chips(
                        title = "更新通道",
                        options = listOf(
                            QzxChip(UpdateChannel.STABLE.label, "stable"),
                            QzxChip(UpdateChannel.BETA.label, "beta")
                        ),
                        selected = setOf(if (channel == UpdateChannel.BETA) "beta" else "stable"),
                        onToggle = { id, _ ->
                            val next =
                                if (id == "beta") UpdateChannel.BETA else UpdateChannel.STABLE
                            channel = next
                            UpdatePrefs.setChannel(act, next)
                        }
                    )

                    item(
                        if (checking) "正在检查…" else "检测更新",
                        value = if (checking) null else channel.label,
                        iconRes = Icons.IC_REFRESH
                    ).desc(notice ?: (channel.label + " · 需要联网"))
                        .arrow()
                        .enabled(!checking)
                        .onClick { manualCheck() }

                    item(
                        "开发者选项",
                        iconRes = Icons.IC_SHIELD_CHECK
                    ).desc("本地日志、错误详情与诊断包")
                        .arrow()
                        .spot("about.diagnostics")
                        .onClick { DeveloperOptionsPage.open(act) }

                    // 【B4·合规】开源许可入口：只下载 APK 的用户也必须能拿到第三方许可文本
                    //   （见 app/build.gradle 的 syncLicenseAssets）。
                    item("开源许可", iconRes = Icons.IC_LIBRARY)
                        .desc("第三方组件清单与许可原文")
                        .arrow()
                        .onClick { showLicenses = true }
                }
            }

            // ---- 结果弹窗（只有「已是最新」和「失败」两种；有新版本时直接跳发行版页）----
            val r = result
            when (r) {
                is UpdateCheckResult.UpToDate -> QzxDialog(
                    visible = true,
                    title = "已是最新版本",
                    message = "当前 v" + r.currentVersion + " 已是「" + r.channel.label + "」的最新版本。",
                    confirmText = "知道了",
                    onConfirm = { result = null },
                    onDismiss = { result = null }
                )
                is UpdateCheckResult.Failed -> QzxDialog(
                    visible = true,
                    title = "检查更新失败",
                    message = r.error.message,
                    confirmText = "知道了",
                    onConfirm = { result = null },
                    onDismiss = { result = null }
                )
                else -> Unit
            }
        }
    }

    /* ============================ 检测更新 ============================ */

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
