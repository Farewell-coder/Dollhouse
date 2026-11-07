package com.dollhouse.app.ui.home

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.ChatSettingsPage
import com.dollhouse.app.ui.settings.AppearancePage
import com.dollhouse.app.ui.settings.MemSettingsPage
import com.dollhouse.app.ui.settings.MyProfilePage
import com.dollhouse.app.ui.settings.SleepSettingsPage
import com.dollhouse.app.ui.settings.VisionModelPage
import com.dollhouse.app.ui.settings.WanderSettingsPage
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeManager
import com.dollhouse.app.ui.theme.UiKit
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dollhouse.app.data.MyProfile
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.design.DhDesign

/**
 * 【职责】设置页（Compose）：权限 / 聊天 / 人偶 / 外观 四张卡片。
 * 【r11】「记忆」整张卡片已搬到人偶编辑页的「人格」页（手风琴展开）——
 *   记忆从这一版起按人偶分库，放在全局设置里已经没有意义。
 *
 * 【为什么整页重写】原设置页是「一棵长树 → SettingsPage 按标题切折叠卡片 →
 *   HomeUi.buildSettingsPage 再拼一次」的双重整理；Compose 侧直接声明式写出六张卡，
 *   `SettingsRegistry` / `SettingsCard` / 标题匹配 / 折叠状态全部不再需要。
 *
 * 【契约】对外只暴露 [build]；页面 tag 由调用方打上。
 */
/**
 * 卡片内行分隔线的左缩进（dp）。
 *
 * 【为什么是 28】行首图标 20dp + 图标与文字间距 8dp = 28：分隔线从**文字左缘**起，
 *   与主流设置页一致（线不穿过图标区）。行没有图标时传 0。
 */
private const val ICON_ROW_INSET = 28

object SettingsScreen {

    /** 【r13】设置页作为**覆盖层**时的页面 key（改造后它不再是底座之一，见 [open]）。 */
    const val TAG_PAGE = "feiyu_settings_page"

    /**
     * 【r13】把设置页压到页面栈上。
     *
     * 【为什么从底座改成覆盖层】改造前三 Tab 之外还有一个「设置页」作为底座之一，
     *   于是底座的语义变成「主页 ↹ 设置页」的两个面孔 —— 而设置本质上是
     *   **从主页点进去**的二级页面（它有自己的返回按钮、自己的滚动位置）。
     *   收进页面栈后，返回键、层序存档、跨重建恢复全部走既有统一机制，
     *   不需要再为它单开一套「底座互切」的逻辑。
     *
     * 【与其它覆盖层的关系】它现在是页面栈里普普通通的一层，
     *   [PageRegistry] 里也登记了它，所以重建后能原样恢复。
     */
    @JvmStatic
    fun open(act: MainActivity) {
        ComposeHost.installForActivity(act)
        com.dollhouse.app.ui.compose.PageStack.push(act, TAG_PAGE) { build(act) }
    }

    /** 搭出设置页根视图。 */
    @JvmStatic
    fun build(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { SettingsContent(act) }
    }

    @Composable
    private fun SettingsContent(act: MainActivity) {
        // 进页对一次真实状态（从系统设置页返回后立即刷新权限行）。
        LaunchedEffect(Unit) { HomeUi.syncAll(act) }

        DhKit.Page(
            title = "设置",
            onBack = {
                // 【返回】设置页是覆盖层，退层即可露出它下面的主页 ——
                //   不再需要「切回首页」那种底座级操作。
                com.dollhouse.app.ui.compose.PageStack.pop(act)
            }
        ) {
            // 【本轮·补设计稿要求的资料卡】设计稿 §4.6 的设置页是「资料卡 + 分组导航行」结构：
            //   顶部一张 Profile card（头像 56 + 我的资料 + 说明 + 箭头），下面是分组入口。
            //   现状是 6 张折叠卡，完全没有这张资料卡 —— 而「我的资料」的唯一入口
            //   藏在聊天列表左上角的头像抽屉里。设置页里找不到「我自己」是反直觉的：
            //   用户想改昵称/头像时，第一反应是进设置。
            //   【为什么这只是一张入口卡，不是把编辑搬进来】编辑仍走 MyProfilePage，
            //   这里只提供「看得见、点得到」的入口，避免一个页面承担两种职责。
            ProfileCard(act)
            PermissionCard(act)
            ChatCard(act)
            DollCard(act)
            LookCard(act)
            BackupCard(act)
            // 【r13】「实验」卡片：收纳尚未正式归入设置卡片、但已经可用的实验性功能。
            //   用户定案：**排在最后一张**，且是**展开式**（卡片内直接展开，不跳页）——
            //   与上面四张卡片的展开方式一致。
            ExperimentCard(act)
        }
    }

    /* ============================ 我的资料 ============================ */

    /**
     * 资料卡：头像 56 + 昵称 + 签名摘要 + 箭头，点击进「我的资料」页。
     *
     * 【为什么需要它】设计稿 §4.6 把它列为设置页的第一块。本 App 此前把「我的资料」
     *   只放在聊天列表左上角的头像抽屉里 —— 用户想改昵称/头像时第一反应是进设置，
     *   而设置页里找不到「我自己」，只能靠试。
     *
     * 【为什么头像 56】设计稿的 avatar-lg 档；它是本页唯一的「人」的元素，
     *   比行图标（20）明显大，才能在一屏设置项里被认出来。
     *
     * 【为什么读 ProfileState.tick】昵称/头像存在 SharedPreferences 里，
     *   在「我的资料」页改完返回时本卡不会自己更新；tick 是既有的重读信号。
     */
    @Composable
    private fun ProfileCard(act: MainActivity) {
        val c = DhTokens.colors
        val tick = ProfileState.tick
        val name = remember(tick) { MyProfile.name(act).ifBlank { "还没有取名字" } }
        val bio = remember(tick) { MyProfile.bio(act) }
        val bmp = remember(tick) { MyProfile.avatarBitmap(act, 128) }
        val showPlaceholder = bio.isBlank()

        DhKit.Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable { MyProfilePage.open(act) }
                    .padding(vertical = DhDesign.Space.xs.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(DhDesign.Size.avatarLg)
                        .clip(RoundedCornerShape(DhDesign.Radius.pill.dp))
                        .background(c.soft),
                    contentAlignment = Alignment.Center
                ) {
                    val avatar = bmp
                    if (avatar != null && !avatar.isRecycled) {
                        Image(
                            bitmap = avatar.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        DhKit.Icon(
                            Icons.IC_CIRCLE_USER,
                            DhDesign.Size.iconXl.value,
                            c.sub
                        )
                    }
                }
                Spacer(Modifier.width(DhDesign.Space.md.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        color = c.title,
                        style = DhTokens.type.section,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        // 没填签名时给一句用途说明，而不是留空 —— 空行会让卡片显得没长好。
                        text = if (showPlaceholder) "昵称、头像与签名" else bio,
                        modifier = Modifier.padding(top = 2.dp),
                        color = c.sub,
                        style = DhTokens.type.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                DhKit.Icon(
                    Icons.IC_CHEVRON_RIGHT,
                    DhDesign.Size.iconSm.value,
                    c.sub.copy(alpha = 0.62f)
                )
            }
        }
    }

    /* ============================ 备份 ============================ */

    /**
     * 【v0.2.6·完整备份】把供应商、模型、人偶、记忆与外观设置打成一个文件。
     *
     * 【为什么单独一张卡】此前「导出配置」只导供应商与模型（那是给 Aivora 互通用的），
     *   但用户换手机时真正要带走的是**对话、人偶、记忆**。两者语义完全不同，
     *   混在一个入口里会让人误以为「导出配置 = 完整备份」。
     */
    @Composable
    private fun BackupCard(act: MainActivity) {
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var pending by remember { mutableStateOf<org.json.JSONObject?>(null) }
        var pendingSummary by remember { mutableStateOf<List<String>>(emptyList()) }

        DhKit.Card(collapsibleTitle = "备份与恢复", titleIcon = Icons.IC_DOWNLOAD) {
            DhForm.Note("备份包含供应商、模型、人偶设定、聊天记忆与外观设置；不含 API Key 与图片。")

            if (message.isNotEmpty()) {
                DhForm.Note(message, color = if (message.startsWith("已")) DhTokens.colors.ok else DhTokens.colors.err)
            }

            DhKit.ActionRow(
                label = "导出完整备份",
                iconRes = Icons.IC_UPLOAD,
                desc = "生成一个文件，通过系统分享保存",
                trailing = if (busy) "处理中…" else "›"
            ) {
                if (busy) return@ActionRow
                busy = true
                Thread {
                    var out: String? = null
                    var err = ""
                    try {
                        val json = com.dollhouse.app.data.BackupService.export(act, act.packageName)
                        out = json.toString()
                    } catch (t: Throwable) {
                        err = t.message ?: "导出失败"
                    }
                    act.runOnUiThread {
                        busy = false
                        if (out == null) {
                            message = err
                        } else {
                            val f = com.dollhouse.app.data.BackupFile.write(act, out)
                            com.dollhouse.app.data.BackupFile.prune(act)
                            if (f == null) {
                                message = "写文件失败，请检查存储空间"
                            } else if (com.dollhouse.app.data.BackupFile.share(act, f)) {
                                message = "已生成备份文件，选择保存位置即可"
                            } else {
                                message = "没有可用的分享目标"
                            }
                        }
                    }
                }.start()
            }

            DhKit.ActionRow(
                label = "从备份恢复",
                iconRes = Icons.IC_DOWNLOAD,
                desc = "恢复前会先给你看会还原什么",
                trailing = "›"
            ) {
                com.dollhouse.app.PickFileActivity.setFileListener { paths, failed ->
                    com.dollhouse.app.PickFileActivity.setFileListener(null)
                    if (failed || paths.isEmpty()) {
                        message = "没有读到文件"
                        return@setFileListener
                    }
                    val f = java.io.File(paths[0])
                    val bytes = try {
                        if (f.length() > com.dollhouse.app.data.BackupEnvelope.MAX_BYTES) {
                            message = "文件过大，不像是有效备份"
                            return@setFileListener
                        }
                        f.readBytes()
                    } catch (t: Throwable) {
                        message = "读取失败：" + (t.message ?: "")
                        return@setFileListener
                    }
                    val parsed = com.dollhouse.app.data.BackupEnvelope.parse(bytes)
                    if (!parsed.ok()) {
                        message = parsed.message()
                        return@setFileListener
                    }
                    pendingSummary = com.dollhouse.app.data.BackupEnvelope.summary(parsed.root)
                    pending = parsed.root
                }
                com.dollhouse.app.PickFileActivity.startFiles(act, false)
            }
        }

        // 恢复确认：先摆概要，用户点「确认恢复」才写盘。
        val toRestore = pending
        if (toRestore != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pending = null },
                title = { androidx.compose.material3.Text("确认恢复") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        pendingSummary.forEach { line ->
                            androidx.compose.material3.Text(
                                text = "· " + line,
                                color = DhTokens.colors.sub,
                                fontSize = UiKit.FS_TINY.sp,
                                lineHeight = 18.sp,
                                fontFamily = DhTokens.fonts
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton({
                        pending = null
                        busy = true
                        Thread {
                            var result = ""
                            try {
                                result = com.dollhouse.app.data.BackupService.restore(act, toRestore)
                            } catch (t: Throwable) {
                                result = "恢复失败：" + (t.message ?: "")
                            }
                            val r = result
                            act.runOnUiThread {
                                busy = false
                                message = r
                            }
                        }.start()
                    }) { androidx.compose.material3.Text("确认恢复") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton({ pending = null }) {
                        androidx.compose.material3.Text("取消")
                    }
                }
            )
        }
    }

    /* ============================ 实验 ============================ */

    /**
     * 【r13】「实验」卡片（展开式）。
     *
     * 【需求原文】「设置界面有个实验的按钮不见了 恢复一下」→ 用户定案：加**第 5 张卡片**、
     *   **排在最后**、**展开式**（卡片内直接展开内容，不跳页）。
     *
     * 【r13 追加修正·展开后只放一个入口按钮】用户原话：「这个不是展示全部数据啊
     *   展开之后应该是一个按钮 点进去是一个界面 这个界面里才是这些」。
     *   ⇒ 卡片展开后**只显示一个入口行**；真正的正文（服务状态 / 安装 / 运行 / 删除 /
     *     AI 能力）在点进去之后的独立页面里。这样设置页不会被一大段技术信息淹没。
     */
    @Composable
    private fun ExperimentCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "实验", titleIcon = Icons.IC_WAND_SPARKLES) {
            // 【M4·按语义分组，不再是一条平铺长列表】
            //   改造前 7 个互不相干的入口挤在同一个列表里（设备控制 / 记忆 / 对话显示 /
            //   识图 / 走位 / 睡觉 / 诊断），用户要读完全部描述才知道各自属于什么。
            //   现在按「它改的是谁」分成三组：对外连接、人偶行为、对话与模型。
            //   分组名用 label 档小标题（不抢眼），组内行之间用发丝线 —— 仍是同一张卡片，
            //   卡片数没变（用户定案"实验卡排在最后且展开式"保持不变），只是内部有了结构。

            DhForm.Note("这里收纳还没正式归入上面几类、但已经能用的功能。")

            // ---------- 组 1：对外连接 ----------
            DhKit.SectionLabel("对外连接")
            DhKit.ActionRow(
                label = "lamda 设备控制",
                iconRes = Icons.IC_GAMEPAD_2,
                desc = "用手机控制另一台设备"
            ) {
                ProviderNav.openAt(act, ProviderNav.R_EXPERIMENT)
            }

            // ---------- 组 2：人偶行为 ----------
            DhKit.SectionLabel("人偶行为")
            DhKit.ActionRow(
                label = "随机走位",
                iconRes = Icons.IC_WAYPOINTS,
                desc = "人偶自己在屏幕上缓慢游走"
            ) {
                WanderSettingsPage.open(act)
            }
            DhKit.RowDivider(insetStart = ICON_ROW_INSET)
            DhKit.ActionRow(
                label = "睡觉模式",
                iconRes = Icons.IC_MOON,
                desc = "人偶低功耗休眠，点击唤醒"
            ) {
                SleepSettingsPage.open(act)
            }

            // ---------- 组 3：对话与模型 ----------
            DhKit.SectionLabel("对话与模型")
            DhKit.ActionRow(
                label = "对话显示",
                iconRes = Icons.IC_MESSAGE_SQUARE_HEART,
                desc = "流式输出、思考过程"
            ) {
                ChatSettingsPage.open(act)
            }
            DhKit.RowDivider(insetStart = ICON_ROW_INSET)
            DhKit.ActionRow(
                label = "识图模型",
                iconRes = Icons.IC_BOT,
                desc = "用哪个模型来看图片"
            ) {
                VisionModelPage.open(act)
            }
            DhKit.RowDivider(insetStart = ICON_ROW_INSET)
            DhKit.ActionRow(
                label = "记忆库设置",
                iconRes = Icons.IC_BOOK_OPEN,
                desc = "抽取与检索策略"
            ) {
                MemSettingsPage.open(act)
            }
        }
    }

    /* ============================ 权限 ============================ */

    @Composable
    private fun PermissionCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "权限", titleIcon = Icons.IC_SHIELD_CHECK) {
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
            DhKit.PermRow(
                name = "悬浮窗权限",
                iconRes = Icons.IC_APP_WINDOW,
                desc = "人偶要浮在其他应用上就必须有它",
                state = if (HomeState.overlay) 0 else 1
            ) {
                HomeUi.requestOverlay(act)
            }
            DhKit.PermRow(
                name = "通知权限",
                iconRes = Icons.IC_BELL,
                desc = "保活通知与消息提醒",
                state = if (HomeState.notif) 0 else 1
            ) {
                HomeUi.requestNotif(act)
            }
            // 保活分组：电池优化白名单 + 无感保活总开关。
            DhKit.PermRow(
                name = "电池优化白名单",
                iconRes = Icons.IC_BATTERY_CHARGING,
                desc = "加入后不容易被系统清掉",
                state = if (HomeState.battery) 0 else 1
            ) {
                HomeUi.requestIgnoreBattery(act)
            }
            DhKit.SwitchRow(
                name = "无感保活",
                iconRes = Icons.IC_ACTIVITY,
                desc = "允许应用持续执行相关后台任务",
                checked = HomeState.keepAlive
            ) { on ->
                HomeUi.onKeepAliveChanged(on, act)
            }
            // 开关通道：磁贴 + 隐藏后台卡片 + 开关指令（三者是同一条启停通道的不同入口）。
            DhKit.SwitchRow(
                name = "快捷设置磁贴",
                iconRes = Icons.IC_LAYOUT_GRID,
                desc = "从下拉快捷开关直接起停人偶",
                checked = HomeState.tile
            ) { on ->
                HomeUi.onTileSwitchChanged(on, act)
            }
            DhKit.SwitchRow(
                name = "隐藏后台卡片",
                iconRes = Icons.IC_EYE_OFF,
                desc = "不在最近任务里露出这一页",
                checked = HomeState.hideRecents
            ) { on ->
                HomeUi.onHideRecentsChanged(on, act)
            }
            DhKit.ValueRow(
                label = "开关指令",
                iconRes = Icons.IC_LINK_2,
                desc = "复制一条可粘贴到别处的指令",
                value = "复制 ›"
            ) {
                HomeUi.copyToggleLink(act)
            }
        }
    }

    /* ============================ 聊天 ============================ */

    @Composable
    private fun ChatCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "聊天", titleIcon = Icons.IC_MESSAGE_SQUARE_HEART) {
            DhKit.ValueRow(
                label = "提供商",
                iconRes = Icons.IC_SERVER,
                desc = "接口地址、密钥与模型列表",
                value = "›"
            ) {
                ProviderNav.open(act)
            }
            DhKit.ValueRow(
                label = "查看 Token",
                iconRes = Icons.IC_GAUGE,
                desc = "查看模型使用统计",
                value = "›"
            ) {
                TokenStat.open(act)
            }
            // 【r13·记忆系统重构】原「自动总结 / 触发阈值」两项已删。
            //
            // 【为什么删】它们管的是旧记忆系统的**归档**：条数到阈值就把最老一批消息
            //   压成摘要、删掉原文（那套实现已随旧系统整体删除）。用户定案引入 Operit 式
            //   记忆系统后，归档这套机制整体下线 —— 新系统是「每轮自动抽取成结构化条目」，
            //   没有阈值这个概念，原文一条不删。
            //   新的全局策略（自动保存开关 / 间隔 / 提取规则 / 检索权重）统一收到
            //   「实验 → 记忆库设置」页里；对话显示相关（流式 / 思考过程）在「实验 → 对话显示」。
        }
    }

    /* ============================ 人偶 ============================ */

    @Composable
    private fun DollCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "人偶", titleIcon = Icons.IC_USERS) {
            // 【本轮·删重复入口】原「自定义人偶」（ValueRow，值显示 "›"）与下方
            //   「管理人偶」（ActionRow，带图标与说明）**指向同一个页面** `DollListScreen.open`，
            //   两个入口功能完全重复，用户要求保留第二个、删掉第一个。
            //   【为什么留 ActionRow 那个】它带图标、带副标题（封面、动作、启停、删除），
            //   信息量更高，且与设置页其它卡片的行样式一致（ValueRow 只有「标签 + ›」）。
            //   【为什么不连带删别的东西】`DollListScreen.open` 还有别的调用方
            //   （首页人偶页、通知入口等），所以只删这一行 UI，不碰目标页面。
            //
            // 【r14·需求 4b】「管理人偶」从人偶页底部**搬到这里**。
            //   用户定案原文：「不要了 从设置的人偶下级进」——
            //   人偶页每一行都要用来展示角色（QQ 联系人形态），
            //   在列表尾部再挂一条「管理人偶」会破坏那个信息结构。
            DhKit.ActionRow(
                label = "管理人偶",
                iconRes = Icons.IC_LAYERS,
                desc = "封面、动作、启停、删除"
            ) { DollListScreen.open(act) }
            // 【r10】全局「调整比例」已移除：比例改到「每只人偶」的编辑页（活动 → 比例大小），
            //   现场调整只作用于当前启用的那几只，放在全局设置里既不准也容易误改。
        }
    }

    /* ============================ 外观 ============================ */

    /**
     * 【r14·重构】外观卡：从「平铺一堆滑条」改为**一个入口**，正文全部搬进 [AppearancePage]。
     *
     * 【为什么搬】用户定案：外观要「进入一个界面，那几个总的选项作为顶栏，可以切换」。
     *   一张折叠卡片塞不下四个分类（原来的外观卡里已经有 4 条滑条 + 7 个值行），
     *   而且顶栏 Tab 这种形态在卡片内部做不出来。
     *
     * 【旧内容去哪了】四项滑条（背景图透明度 / 背景毛玻璃 / 卡片透明度 / 遮罩强度）
     *   与「清除背景」「字体颜色」弹窗全部原样迁进 [AppearancePage] 的对应 Tab，
     *   功能一个没删 —— 这里是**搬家**，不是删功能。
     */
    @Composable
    private fun LookCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "外观", titleIcon = Icons.IC_PALETTE) {
            // 【状态摘要】让用户不用点进去就知道当前是什么状态。
            DhKit.ActionRow(
                label = "外观设置",
                iconRes = Icons.IC_PALETTE,
                desc = "主题 · 背景 · 聊天气泡 · 弹窗",
                trailing = "›"
            ) { AppearancePage.open(act) }
            DhKit.ValueRow(
                label = "当前主题",
                value = ThemeManager.MODE_NAMES[HomeState.themeMode],
                iconRes = Icons.IC_SUN
            ) { AppearancePage.open(act) }
            DhKit.ValueRow(
                label = "全局背景",
                iconRes = Icons.IC_IMAGE,
                value = when {
                    !HomeState.bgSet -> "未设置"
                    PetPrefs.bgEnabled(act) -> "已开启"
                    else -> "已关闭"
                },
                valueTint = when {
                    !HomeState.bgSet -> DhTokens.colors.err
                    PetPrefs.bgEnabled(act) -> DhTokens.colors.ok
                    else -> DhTokens.colors.sub
                }
            ) { AppearancePage.open(act) }
            DhForm.Note("点「外观设置」进去，四个分类用顶栏切换。")
        }
    }

    /* 【r11】原「记忆」卡片（MemCard）已整体移除，见本文件顶部 KDoc。 */
}