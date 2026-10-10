package com.dollhouse.app.ui.settings
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.device.LamdaManager
import com.dollhouse.app.device.ShizukuBridge
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】设置页「lamda 设备控制」：安装 / 启动 / 停止服务，并说明 AI 能拿它做什么。
 *
 * 【本页的设计口径】状态、文案、颜色、图标、按钮可用性全部来自**同一次探测的同一份快照**
 *   （[Snap]），不再出现「状态行说未安装、下载按钮却是灰的」这类自相矛盾。
 *
 * 【为什么单开一页】安装包 204MB、要下载、要解包、状态要刷新，塞进设置卡片会把卡片撑成一屏。
 *        卡片下只留一个入口行，动手的事全在这页里。
 *
 * 【口径】本页不直接碰 Shizuku / 网络，全部经 LamdaManager / ShizukuBridge；UI 只负责触发与显示。
 *
 * 【坑】所有耗时动作（下载 / 解包 / 启停）都必须丢到 worker 线程，结果经 Handler 回主线程刷新，
 *        否则解包期间的阻塞会把界面冻住。
 *
 * 【r7 迁移】整页 UI 已从手写 View 换为 Jetpack Compose（[LamdaContent]）；
 *   对外入口 [build] 的签名与返回类型保持不变，`ProviderNav` / `ExperimentPage` 零改动。
 *   探测状态机（[SNAP] / [PROBING] / [AGAIN] / [STATE]）原样保留 —— 它是纯逻辑，与 UI 无关。
 */
object LamdaPage {
    /**
     * 一次状态探测的完整快照。
     *
     * 【为什么要有快照】状态文字与各按钮的可用性都由这同一组事实推导：
     *   有没有文件 / 包完不完整 / 装没装 / 服务活没活 / Shizuku 能不能用。
     *   如果分别去问，页面就会出现「状态说未安装、解包按钮却可点」这种打架。
     */
    private class Snap(
        val status: Design.Status,
        val hasFile: Boolean,
        val pkgReady: Boolean,
        val installed: Boolean,
        val shizukuReady: Boolean
    )
    /**
     * 状态行上的一段临时文字（动作进行中 / 动作结果）。
     * 存在它的时候状态行显示它，不存在时显示快照里的真实状态。
     */
    private class Hint(val text: String, val color: Int, val busy: Boolean)
    /**
     * Compose 侧的可观察状态。
     *
     * 【为什么要有它】原 View 版靠 `removeAllViews` + 重建控件来表达状态变化；
     *   Compose 版只需要把「事实」放进 `mutableStateOf`，UI 自动重组。
     *   三个字段一一对应原版 `paint()` 的三个入参（snap / hint / busyOverride）。
     */
    private class UiState(val ctx: Context) {
        var snap by mutableStateOf<Snap?>(null)
        var hint by mutableStateOf<Hint?>(null)
        var busyKey by mutableStateOf<String?>(null)
    }
    /** 最近一次探测结果。首帧尚未探测时为 null，页面按「正在检查」渲染。 */
    @Volatile
    private var SNAP: Snap? = null
    /** 探测重入锁：同一时刻只允许一次探测在飞，杜绝旧结果覆盖新结果。 */
    @Volatile
    private var PROBING = false
    /** 探测期间又来了新的刷新请求：本轮到点后补一次。 */
    @Volatile
    private var AGAIN = false
    /**
     * 当前活着的页面状态。
     *
     * 【为什么需要它】探测在工作线程跑，回来时页面可能已经被退出重建。
     *   若把结果写进已废弃的那份状态，用户看到的是一张永远停在「正在检查…」的新页面
     *   （审查 A3：刷新没有绑定生命周期）。这里记住最新一版，结果一律落到它上面。
     */
    @Volatile
    private var STATE: UiState? = null

    @JvmStatic
    fun build(act: Activity): View {
        ComposeHost.installForActivity(act)
        val state = UiState(act)
        // 【为什么先置空】每次进入都从「正在检查」开始，绝不用上一次的旧状态渲染。
        SNAP = null
        // 记住这一版状态：在飞的探测回来时，结果要落到活着的这一份上。
        STATE = state
        refresh(state, null)
        return ComposeHost.createView(act) { LamdaContent(act, state) }
    }
    /* ============================== 渲染 ============================== */
    @Composable
    private fun LamdaContent(ctx: Context, st: UiState) {
        // 本帧认定的「正在跑的动作名」：动作刚发起时 LamdaManager 侧的标记可能还没来得及置上，
        //   用 st.busyKey 立刻把按钮禁掉，避免抢跑。
        val busy = st.busyKey ?: LamdaManager.busyAction()
        val idle = busy == null
        val s = st.snap
        DhKit.Page(
            title = "lamda 设备控制",
            sub = "让 AI 能操作手机（shell 级命令通道）",
            onBack = { ProviderNav.handleBack(ctx) }
        ) {
            statusCard(ctx, st, s, st.hint, busy)
            installCard(ctx, st, s, idle)
            runCard(ctx, st, s, idle)
            deleteCard(ctx, st, idle)
            abilityCard(ctx)
        }
    }
    /** 卡片头：图标 + 标题（原 LamdaUiKit.header 的 Compose 等价物）。 */
    @Composable
    private fun CardHead(text: String, iconRes: Int) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DhKit.Icon(iconRes = iconRes, sizeDp = 16f, color = DhTokens.colors.acc)
            Spacer(Modifier.width(8.dp))
            DhKit.CardTitle(text)
        }
    }
    /** 可点行：图标 + 名称 + 右箭头。禁用时整体降透明度并吃掉点击。 */
    @Composable
    private fun ActionRow(name: String, iconRes: Int, enabled: Boolean, danger: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        val fg = when {
            !enabled -> c.sub
            danger -> c.err
            else -> c.acc
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.38f)
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .then(if (enabled) Modifier.pressable(onClick) else Modifier)
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(iconRes = iconRes, sizeDp = 18f, color = fg)
            Spacer(Modifier.width(10.dp))
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                color = fg,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts
            )
            DhKit.Icon(iconRes = Icons.IC_CHEVRON_RIGHT, sizeDp = 16f, color = fg)
        }
    }
    /** 能力行：图标 + 名称 + 描述（两行文字，不折叠）。 */
    @Composable
    private fun CapabilityRow(name: String, desc: String, iconRes: Int) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(modifier = Modifier.padding(top = 1.dp)) {
                DhKit.Icon(iconRes = iconRes, sizeDp = 16f, color = c.acc)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
                Text(
                    text = desc,
                    modifier = Modifier.padding(top = 2.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }
    /** 信息行：左标签 + 右等宽值。 */
    @Composable
    private fun InfoLine(label: String, value: String) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
            Text(
                text = value,
                color = c.title,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
        }
    }
    /** 服务状态：一态一图标一颜色，全部由 [Design.Status] 绑定。 */
    @Composable
    private fun statusCard(ctx: Context, st: UiState, s: Snap?, hint: Hint?, busy: String?) {
        DhKit.Card {
            CardHead("服务状态", Design.icons.SERVER)
            val text: String
            val color: Color
            val icon: Int
            if (hint != null) {
                // 动作进行中 / 动作结果：这一句比「服务状态」更能回答用户此刻的疑问。
                text = hint.text
                color = Color(hint.color)
                icon = when {
                    hint.busy -> Icons.IC_SPINNER
                    hint.color == Design.err() -> Icons.IC_WARNING
                    hint.color == Design.ok() -> Icons.IC_CHECK_CIRCLE
                    else -> Icons.IC_INFO
                }
            } else if (s == null) {
                text = "正在检查…"
                color = Color(Design.neutral())
                icon = Icons.IC_SPINNER
            } else {
                // 文字 / 颜色 / 图标一次设齐，漏不掉其中任何一项。
                text = s.status.label()
                color = Color(s.status.color())
                icon = s.status.icon()
            }
            // 【为什么让它可点】状态来自真实探测，用户有疑问时最直接的答案就是「再探一次」。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                    .pressable { refresh(st, null) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DhKit.Icon(iconRes = icon, sizeDp = Design.SZ_XS, color = color)
                Spacer(Modifier.width((Design.GAP_ICON / 2).dp))
                Text(
                    text = text,
                    color = color,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            if (busy != null) {
                DhForm.Note("正在" + busyLabel(busy) + "中，等它跑完再操作，重复点容易把文件写坏。")
            } else if (s != null && s.status == Design.Status.UNKNOWN) {
                DhForm.Note("Shizuku 没就绪，查不到服务状态。"
                    + "安装与启停都靠它的 shell 通道，请先确认 Shizuku 在运行且已给本应用授权。")
            } else if (s != null && s.status == Design.Status.PARTIAL) {
                DhForm.Note("上次下载没有完成，本地留了一份不完整的文件。"
                    + "建议先用下面的「删除」清干净，再重新下载。")
            }
        }
    }
    /** 安装：下载 + 解包。 */
    @Composable
    private fun installCard(ctx: Context, st: UiState, s: Snap?, idle: Boolean) {
        // 把快照读成局部量：不再到处写 !!，也就不会有依赖短路求值的脆弱判断。
        val hasFile = s != null && s.hasFile
        val pkgReady = s != null && s.pkgReady
        val installed = s != null && s.installed
        DhKit.Card {
            CardHead("安装", Design.icons.DOWNLOAD)
            // 下载：本地已有文件（含半截）就置灰。理由见 LamdaManager.download —— 不静默覆盖、不静默续传。
            ActionRow("下载服务器包（约 204 MB）", Design.icons.DOWNLOAD,
                idle && s != null && !hasFile, false) { download(st) }
            // 解包：只有包完整时才允许点。半截包解出来的是坏文件，服务起不来还难排查。
            ActionRow("解包安装", Design.icons.UNPACK,
                idle && pkgReady && !installed, false) { install(st) }
            if (hasFile && !pkgReady) {
                DhForm.Note("本地有一份体积不完整的服务器包，"
                    + "下载与解包都已停用。请先用下面的「删除」清掉它，再重新下载。")
            } else if (hasFile) {
                DhForm.Note(LamdaManager.downloadRefusedText(ctx) + "。")
            } else if (s != null) {
                DhForm.Note("服务器包不随 App 分发，首次使用需下载一次。"
                    + "解包到 /data/local/tmp/server，全程以 shell 身份执行，不需要 root。")
            }
        }
    }
    /** 运行：启停 + 自动保活 + 服务接口信息。 */
    @Composable
    private fun runCard(ctx: Context, st: UiState, s: Snap?, idle: Boolean) {
        // 【互斥规则】跑着的时候不给点启动、没跑的时候不给点停止。以前两个都能点，
        //   点错一个只会得到一句「已经在运行 / 没在运行」，纯属浪费一次往返。
        val running = s != null && s.status == Design.Status.RUNNING
        val installed = s != null && s.installed
        DhKit.Card {
            CardHead("运行", Design.icons.START)
            ActionRow("启动服务", Design.icons.START, idle && installed && !running, false) { start(st) }
            ActionRow("停止服务", Design.icons.STOP, idle && running, false) { stop(st) }
            // 【自动保活】开关必须落到真实配置上：重进页面读的是 prefs，不是内存里的临时值。
            var autoOn by remember { mutableStateOf(PetPrefs.lamdaAutoStart(ctx)) }
            DhKit.SwitchRow(name = "自动保活", checked = autoOn, onCheckedChange = { now ->
                autoOn = now
                PetPrefs.setLamdaAutoStart(ctx, now)
                if (now) {
                    // 用户刚开就希望它马上可用，别让他等下一轮巡检。
                    LamdaManager.resumeGuard()
                    runAsync(st, "start", "正在检查服务…") {
                        if (LamdaManager.probe()) {
                            "服务运行中"
                        } else if (LamdaManager.installed(ctx)) {
                            LamdaManager.start()
                        } else {
                            "（还没解包安装服务器包）"
                        }
                    }
                }
            })
            DhForm.Note("服务掉了或手机重启后，自动把它拉回来。")
            // 【技术信息】端口与接口形态属于「查得到但不需要抢注意力」的内容，
            //   用等宽小字单独成行，不用大段说明文字淹没页面。
            CardHead("服务接口", Design.icons.NETWORK)
            InfoLine("协议", "HTTP + MCP")
            InfoLine("监听地址", "127.0.0.1:65000")
            DhForm.Note("开机后需要重新启动一次；打开上面的开关后，"
                + "只要小肥鱼在屏幕上（桌宠在运行），她会每隔一分钟看一眼服务，掉了就拉回来。")
        }
    }
    /** 删除：危险操作，独立成块并与普通操作在视觉上分开。 */
    @Composable
    private fun deleteCard(ctx: Context, st: UiState, idle: Boolean) {
        var confirm by remember { mutableStateOf(false) }
        DhKit.Card {
            CardHead("删除", Design.icons.DELETE)
            ActionRow("删除已安装的服务与下载的包", Design.icons.DELETE, idle, true) { confirm = true }
            DhForm.Note("会先停掉服务，再删掉 /data/local/tmp 下解包出来的文件，"
                + "最后删掉下载的服务器包（约 204 MB）。删完想再用，需要重新下载并解包。")
        }
        if (confirm) {
            DhForm.Alert(
                title = "删除 lamda？",
                onDismiss = { confirm = false },
                posText = "删除",
                onPos = {
                    runAsync(st, "delete", "正在删除…") { LamdaManager.deleteAll(ctx) }
                },
                negText = "取消"
            ) {
                Text(
                    text = "会先停掉 lamda 服务，然后删除 /data/local/tmp 下解包出来的文件"
                        + "和下载的服务器包（约 204 MB）。\n这个操作不能撤销，删完想再用需要重新下载并解包。",
                    color = DhTokens.colors.title,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts,
                    lineHeight = (UiKit.FS_TINY + 3f).sp
                )
            }
        }
    }
    /**
     * AI 能力：按**真实实现**逐项列，不合并成一整段，也不虚构没有的能力。
     * 每一项都对应 LamdaTool 里真实存在的动作。
     */
    @Composable
    private fun abilityCard(ctx: Context) {
        DhKit.Card {
            CardHead("AI 能做什么", Design.icons.AI)
            CapabilityRow("打开 / 关闭应用", "按包名或应用名启动、结束某个应用", Design.icons.APP)
            CapabilityRow("点按", "按坐标点一下屏幕上的元素", Design.icons.TAP)
            CapabilityRow("长按", "按住不放，触发长按菜单或选择", Design.icons.LONG_PRESS)
            CapabilityRow("滑动", "从一点划到另一点，用来翻页与滚动", Design.icons.SWIPE)
            CapabilityRow("回主页 / 返回", "回到桌面，或退回上一个界面", Design.icons.HOME)
            CapabilityRow("唤醒屏幕", "点亮屏幕（不解锁，解锁仍需主人自己来）", Design.icons.WAKE)
            CapabilityRow("输入文字 / 清空输入框", "往当前焦点里打字，也能把输入框清干净", Design.icons.KEYBOARD)
            CapabilityRow("读写剪贴板", "读取剪贴板内容，或把一段文字放进去", Design.icons.CLIPBOARD)
            CapabilityRow("读取当前界面结构", "返回屏幕的层级与可点元素，据此判断该点哪里", Design.icons.OBSERVE)
            DhForm.Note("服务启动后，小肥鱼会多出上面这组设备操作能力。"
                + "服务没启动时，这组能力不会下发给模型，聊天与其他功能不受影响。")
        }
    }
    /* ============================== 探测 ============================== */
    /**
     * 探一次状态，完成后回主线程刷新。
     *
     * 【为什么要串行】以前每次刷新都起一条线程，慢的那条后回来，会把新状态覆盖成旧的。
     *   这里用探测中标记把并发压成一条，并在期间攒下新的请求，跑完补一次。
     */
    private fun refresh(st: UiState, hint: Hint?) {
        if (PROBING) {
            AGAIN = true
            return
        }
        PROBING = true
        AGAIN = false
        val ctx = st.ctx
        Thread(Runnable {
            var snap: Snap? = null
            try {
                snap = probe(ctx)
            } catch (ignored: Throwable) {
            }
            val f = snap
            Handler(Looper.getMainLooper()).post {
                PROBING = false
                if (f != null) {
                    SNAP = f
                }
                // 【落到活着的状态】页面可能已经重建，优先刷到最新那一份上；
                //   万一没有记录（理论上不会），退回本次请求带的那一份，绝不抛异常。
                val t = STATE ?: st
                t.snap = SNAP
                t.hint = hint
                t.busyKey = null
                if (AGAIN) {
                    AGAIN = false
                    refresh(t, null)
                }
            }
        }, "lamda-st").start()
    }
    /** 真实探测。必须在工作线程调用。 */
    private fun probe(ctx: Context): Snap {
        // 【顺序有讲究】先探 HTTP（不依赖 Shizuku，服务活着就是活着），再问 Shizuku 侧的安装态。
        val alive = LamdaManager.probe()
        val shizukuReady = ShizukuBridge.isReady()
        val hasFile = LamdaManager.hasFile(ctx)
        val pkgReady = LamdaManager.pkgReady(ctx)
        // Shizuku 没就绪时不去问安装态：问也不准，还会白等一次 shell 往返。
        val installed = shizukuReady && LamdaManager.installed(ctx)
        val status: Design.Status = when {
            alive -> Design.Status.RUNNING
            !shizukuReady -> Design.Status.UNKNOWN
            installed -> Design.Status.STOPPED
            pkgReady -> Design.Status.PENDING_UNPACK
            hasFile -> Design.Status.PARTIAL
            else -> Design.Status.NOT_INSTALLED
        }
        return Snap(status, hasFile, pkgReady, installed, shizukuReady)
    }
    /* ============================== 交互 ============================== */
    /**
     * 跑一个耗时动作。
     *
     * @param busyKey 动作名（download/install/start/stop/delete），这一帧据此禁掉所有操作行
     * @param busyHint 进行中的状态行文字
     */
    private fun runAsync(st: UiState, busyKey: String, busyHint: String, job: () -> String) {
        // 立刻刷新：按钮当场变灰、状态行显示正在做什么，不留「点了没反应」的空窗。
        st.hint = Hint(busyHint, Design.accent(), true)
        st.busyKey = busyKey
        val ctx = st.ctx
        Thread(Runnable {
            val detail: String = try {
                job()
            } catch (t: Throwable) {
                // 【兜底】t.message 可能为 null，那样会输出半句「执行失败：」，模型与用户都读不懂。
                "（执行失败：" + (t.message ?: t.javaClass.simpleName) + "）"
            }
            Handler(Looper.getMainLooper()).post {
                // 动作结束后重新探一次，状态行与按钮一起回到真实状态；动作结果先挂在状态行上。
                refresh(st, Hint(detail, colorOf(detail), false))
            }
        }, "lamda-act").start()
    }
    /** 下载：进度直接写在状态行上（不重建整页，避免进度跳字时按钮闪）。 */
    private fun download(st: UiState) {
        st.hint = Hint("开始下载…", Design.accent(), true)
        st.busyKey = "download"
        val ctx = st.ctx
        LamdaManager.download(ctx, object : LamdaManager.Progress {
            override fun onProgress(done: Long, total: Long) {
                Handler(Looper.getMainLooper()).post {
                    val t = if (total > 0) {
                        "下载中 " + (done / 1048576L) + " / " + (total / 1048576L) + " MB"
                    } else {
                        "下载中 " + (done / 1048576L) + " MB"
                    }
                    st.hint = Hint(t, Design.accent(), true)
                }
            }
            override fun onDone(ok: Boolean, detail: String?) {
                Handler(Looper.getMainLooper()).post {
                    val d = detail ?: ""
                    st.hint = Hint(d, if (ok) Design.ok() else Design.err(), false)
                    refresh(st, null)
                }
            }
        })
    }
    private fun install(st: UiState) {
        val ctx = st.ctx
        runAsync(st, "install", "正在解包安装…") {
            val r = LamdaManager.install(ctx)
            // 装好了就清掉静默期，让自动保活立刻接管（否则要等静默期走完才发现）。
            if (r.contains("完成")) {
                LamdaManager.resumeGuard()
            }
            r
        }
    }
    private fun start(st: UiState) {
        runAsync(st, "start", "正在启动…") {
            // 手动启动视为用户明确要它开着，清掉静默期，恢复自动保活。
            LamdaManager.resumeGuard()
            LamdaManager.start()
        }
    }
    private fun stop(st: UiState) {
        runAsync(st, "stop", "正在停止…") {
            // 【为什么先静默】开着自动保活时点「停止」，一秒后守护就会把它拉回来，
            //   用户看到的是「停不掉」。进入静默期，尊重这最后一次手动操作。
            LamdaManager.suppressGuard()
            LamdaManager.stop()
        }
    }
    /** 动作结果文案 → 语义色。没有「成功」字样的一律按中性处理，不夸大也不隐瞒。 */
    private fun colorOf(s: String): Int {
        if (s.contains("失败") || s.contains("未确认")) {
            return Design.err()
        }
        // 「（…）」是 LamdaManager 的「这次没做成，原因如下」句式。
        if (s.startsWith("\uFF08")) {
            return Design.warn()
        }
        return Design.neutral()
    }
    /** 动作名的中文说法。 */
    private fun busyLabel(action: String): String {
        if ("download" == action) {
            return "下载"
        }
        if ("install" == action) {
            return "解包"
        }
        if ("start" == action) {
            return "启动"
        }
        if ("stop" == action) {
            return "停止"
        }
        if ("delete" == action) {
            return "删除"
        }
        return action
    }
}
