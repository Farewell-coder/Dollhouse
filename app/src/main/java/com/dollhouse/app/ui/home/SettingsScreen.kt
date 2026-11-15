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
            PermissionRow(act)
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
     * 【v0.2.8·一键全量备份】把整台手机的 Dollhouse 打成一个文件：人偶、人设、
     *   全部聊天记录、长期记忆、配置与图片。
     *
     * 【为什么只剩这一条通道】改造前这张卡外面还有两个平级「导出」入口：
     *   「导出配置」（供应商页顶栏，只导供应商与模型，给 Aivora 互通用）与
     *   「导出角色卡」（人偶编辑页，只导单只人偶的卡）。三者名字里都有「导出」，
     *   **用户无法从措辞判断哪个才是换机要用的那个** —— 而换机恰恰是备份功能唯一的
     *   真实场景。它俩能导的东西已经逐字段包含在全量存档里（见 [com.dollhouse.app.data.AllInOneArchive]），
     *   留着就是两份永远可能与全量存档分叉的平行实现，故本轮删除，
     *   只保留「导出 / 恢复」这一对，且做成**同一张卡片的上下两行**，语义自明。
     *   【注意】角色卡「导入」保留：用户仍要从网上下载酒馆卡，那不是备份场景。
     *
     * 【为什么不再写「不含图片」】v2 起封面 / 形象 / 聊天背景 / 头像 / 自建动作帧
     *   都会打包。文案说错范围与丢数据等价 —— 用户会以为图片没进包，
     *   于是另找办法搬图，或者干脆不敢用备份。
     *
     * 【不含 API Key 是**有意**的】provider 的密钥是 KeyVault 密文，与来源机 Keystore
     *   绑定，搬过去也解不开；写进包里只是白白外泄一份密文。代价是换机后必须重填，
     *   所以恢复前后两处都要明确告知（见下面弹窗与恢复摘要）。
     */
    @Composable
    private fun BackupCard(act: MainActivity) {
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        // 【为什么单独一个成功标志，而不是看文案前缀】改造前这里是
        //   `message.startsWith("已")` 判色，而恢复摘要的第一句是「恢复完成。」——
        //   于是**恢复成功会显示成红色错误样式**。文案是会改的，判色不能依赖文案。
        var okMsg by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf<org.json.JSONObject?>(null) }
        var pendingSummary by remember { mutableStateOf<List<String>>(emptyList()) }

        DhKit.Card(collapsibleTitle = "备份与恢复", titleIcon = Icons.IC_DOWNLOAD) {
            DhForm.Note(
                "一个文件装下整台手机：人偶与设定、全部聊天记录、长期记忆、外观与开关、" +
                    "以及封面 / 形象 / 头像 / 自建动作帧等图片。\n" +
                    "**不含 API 密钥**（它绑定本机，搬过去也用不了），换机后请在新机重填。"
            )

            if (message.isNotEmpty()) {
                DhForm.Note(message, color = if (okMsg) DhTokens.colors.ok else DhTokens.colors.err)
            }

            DhKit.ActionRow(
                label = "一键全量备份",
                iconRes = Icons.IC_UPLOAD,
                desc = "生成一个文件，通过系统分享保存或直接发到新手机",
                trailing = if (busy) "处理中…" else null,
                showChevron = !busy
            ) {
                // 【重入闸门】备份要遍历全部会话与图片，可能十来秒；期间重复点击
                //   会并发组装两份包，还各写一个文件。busy 在这里是必须的，不是装饰。
                if (busy) return@ActionRow
                busy = true
                okMsg = false
                Thread {
                    var out: String? = null
                    var err = ""
                    var skipped = 0
                    try {
                        // 【appVersion 传真实版本号，不传包名】改造前这里传的是
                        //   `act.packageName`（值形如 com.dollhouse.app），被当作「来源版本」
                        //   写进信封 —— 排查用户备份时这一行永远是无用信息。
                        val json = com.dollhouse.app.data.BackupService.export(act, appVersionOf(act))
                        // 【必须紧接着读】它是伴随 export 的静态计数（签名不能改，
                        //   见 AllInOneArchive.lastSkippedImageCount 的说明），
                        //   下一次 export 会覆盖它。所以同一线程内立刻取走。
                        skipped = com.dollhouse.app.data.AllInOneArchive.lastSkippedImageCount()
                        out = json.toString()
                    } catch (t: Throwable) {
                        err = t.message ?: "导出失败"
                    }
                    act.runOnUiThread {
                        busy = false
                        if (out == null) {
                            okMsg = false
                            message = err
                        } else {
                            val f = com.dollhouse.app.data.BackupFile.write(act, out)
                            com.dollhouse.app.data.BackupFile.prune(act)
                            if (f == null) {
                                okMsg = false
                                message = "写文件失败，请检查存储空间"
                            } else if (!com.dollhouse.app.data.BackupFile.share(act, f)) {
                                okMsg = false
                                message = "没有可用的分享目标"
                            } else {
                                okMsg = true
                                message = "已生成备份文件，选择保存位置即可"
                                // 【为什么要说这一句】图片被体积预算挤掉时，
                                //   「备份完成」这句话是不诚实的：用户换机后才发现
                                //   封面缺了几张、自建动作播不出来，而当时什么都没被告知。
                                if (skipped > 0) {
                                    okMsg = false
                                    message = "备份已生成，但有 " + skipped +
                                        " 张图片因体积过大未打包（其余内容完整）。"
                                }
                            }
                        }
                    }
                }.start()
            }

            DhKit.ActionRow(
                label = "从备份恢复",
                iconRes = Icons.IC_DOWNLOAD,
                desc = "新版与旧版备份都能导入，恢复前先给你看会还原什么",
                // 导出在跑的时候不让选文件：两条流程共用同一组状态（busy / message），
                // 并发起来会把对方的结果覆盖掉。
                enabled = !busy,
                showChevron = !busy
            ) {
                com.dollhouse.app.PickFileActivity.setFileListener { paths, failed ->
                    com.dollhouse.app.PickFileActivity.setFileListener(null)
                    if (failed || paths.isEmpty()) {
                        okMsg = false
                        message = "没有读到文件"
                        return@setFileListener
                    }
                    val f = java.io.File(paths[0])
                    val bytes = try {
                        // 【先读长度再读字节】256 MiB 的文件读进 byte[] 就是 256 MB 常驻，
                        //   在读出之前先挡掉明显不对的文件（比如把视频改名成 .json）。
                        if (f.length() > com.dollhouse.app.data.BackupEnvelope.MAX_BYTES) {
                            okMsg = false
                            message = "文件过大（超过 " +
                                com.dollhouse.app.data.BackupEnvelope.maxLabel() + "），不像是有效备份"
                            return@setFileListener
                        }
                        f.readBytes()
                    } catch (t: Throwable) {
                        okMsg = false
                        message = "读取失败：" + (t.message ?: "")
                        return@setFileListener
                    }
                    val parsed = com.dollhouse.app.data.BackupEnvelope.parse(bytes)
                    if (!parsed.ok()) {
                        okMsg = false
                        // 【反向指路】用户手上刚导出过角色卡时，最容易把那张卡
                        //   拖进「从备份恢复」。只说「这不是完整备份」会让人以为
                        //   文件坏了、回头重导一遍。这里直接告诉他该去哪个入口。
                        message = if (looksLikePersonaCard(bytes)) {
                            "这是一张角色卡，不是全量备份。\n" +
                                "请到「人偶 → 编辑 → 提示词与角色卡 → 导入角色卡」导入。"
                        } else {
                            parsed.message()
                        }
                        return@setFileListener
                    }
                    // 【自动识别版本，用户不必知道格式】v2 全量存档与 v1 旧备份都放行
                    //   （v1 的判定与兼容在 BackupEnvelope.parse 里，缺失段回落成空）。
                    //   这里只把「你手上这份是全的还是旧的」摆在弹窗第一行 ——
                    //   否则用户导入一份 v1 旧包，看到「恢复完成」就以为聊天也回来了。
                    val ver = parsed.root?.optInt("ver", 1) ?: 1
                    val lines = ArrayList<String>()
                    lines.add(
                        if (ver >= 2) {
                            "格式：全量存档 v" + ver + "（人设 / 聊天 / 图片齐全）"
                        } else {
                            "格式：旧版备份 v" + ver + "（能导入，但没有聊天 / 人设 / 图片）"
                        }
                    )
                    lines.add("文件大小：" + humanSize(f.length()))
                    lines.addAll(com.dollhouse.app.data.BackupEnvelope.summary(parsed.root))
                    // 【去重】parse() 的 warnings 与 summary() 都会说「这是旧版备份…」，
                    //   两处并列会在弹窗里出现两遍同一句话。以 summary 的口径为准，
                    //   这里只补 summary 没提到的（如「备份没有记录来源版本」）。
                    lines.addAll(parsed.warnings.filterNot { it.contains("旧版备份") })
                    pendingSummary = lines
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
                        // 【不再自己拼提示】上面这段逐行文案全部来自
                        //   `BackupEnvelope.summary()`（数据层契约，UI 原样显示）。
                        //   改造前这里没有概览；本次刻意**不**在 UI 里另写一套
                        //   「会还原什么 / 不含密钥 / 只新增不删除」——两套文案迟早会分叉，
                        //   而 summary() 已经把这三件事逐行说清了（含
                        //   「API Key 需要重新填写」与「这是旧版备份…」）。
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton({
                        pending = null
                        busy = true
                        Thread {
                            var result = ""
                            var ok = true
                            try {
                                result = com.dollhouse.app.data.BackupService.restore(act, toRestore)
                            } catch (t: Throwable) {
                                ok = false
                                result = "恢复失败：" + (t.message ?: "")
                            }
                            val r = result
                            val o = ok
                            act.runOnUiThread {
                                busy = false
                                okMsg = o
                                message = r
                                // 【恢复后必须整页对一次状态】供应商数量、主题、背景、
                                //   人偶列表、是否有常驻角色都可能被这次恢复改变；
                                //   不刷新的话用户看到的是恢复前的旧值，会以为没生效。
                                HomeUi.syncAll(act)
                                ProfileState.bump()
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

    /**
     * 当前 App 版本名，写进备份信封的 `appVersion`（只用于排查，不参与恢复决策）。
     *
     * 【为什么不写成 BuildConfig】版本号只在 build.gradle 一处维护；从 PackageManager
     *   现取与 AboutPage 的口径一致，升级时不会因为忘改某个常量而写出错误的版本标记。
     */
    private fun appVersionOf(act: MainActivity): String {
        return try {
            act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 文件大小的可读形式。
     *
     * 【为什么要显示它】全量存档常是几十 MB，用户看到「12.4 MB」才知道这个文件
     *   是真的带上了图片（旧版备份只有几十 KB）。这也是「是否选错了文件」的最快判据。
     */
    private fun humanSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        // 【显式 Locale.US】默认 Locale 会把小数点写成逗号（如德语 "12,4"），
        //   与同页其它数值文案（DeveloperOptionsPage / ChatUi）口径不一致。
        return if (mb >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        } else {
            (bytes / 1024).toString() + " KB"
        }
    }

    /**
     * 这份文件是不是一张角色卡（而不是全量备份）。
     *
     * 【为什么要判】全量存档与角色卡都是 `.json`，系统文件选择器里长得一模一样。
     *   选错时只说「这不是完整备份」，用户会以为文件损坏 → 回头重新导出 → 再选错。
     *   直接把正确的入口指出来，一次就能走对。
     *
     * 【为什么只查开头 4 KiB】两种格式的标识字段都在信封最前面
     *   （角色卡是 `dollhouse_card` / `chara_card`，全量存档是 `dollhouse.backup`）。
     *   角色卡本身很小，这里仍按前缀查，避免对大文件做全文扫描。
     */
    private fun looksLikePersonaCard(bytes: ByteArray): Boolean {
        val probeLen = Math.min(bytes.size, 4096)
        val head = String(bytes, 0, probeLen, Charsets.UTF_8)
        // 【三种都算】本 App 自有格式、酒馆 v2（spec=chara_card_v2）、酒馆 v1。
        return head.contains("dollhouse_card") ||
            head.contains("chara_card") ||
            head.contains("\"tavern\"")
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
            //
            // 【为什么不按设计稿「每组 ≤5 行」把它拆成三张卡】
            //   设计稿那条规则的意图是「别把一个分组塞爆」，而本卡内部**已经是三个分组**：
            //   对外连接 1 行、人偶行为 2 行、对话与模型 3 行 —— 每组都远低于 5。
            //   若再拆成三张独立卡片，首页卡片数会从 5 涨到 7，反而回到「一页一堆小块」
            //   的形态，与本轮「减少卡片数、组内用发丝线」的方向相反。
            //   所以保留单卡 + 组内小标题：规则的精神（每组不臃肿）满足，字面行数不满足，
            //   这个取舍是显式的，不是漏做。

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

    /**
     * 权限入口行（M-结构收编）。
     *
     * 【为什么不再是一整张 8 行的折叠卡】设计稿 §4.6 明确要求
     *   「Toggles live on their own sub-screen, never on the hub」——
     *   开关必须有自己的子页，设置首页只做分组导航。
     *   改造前这 8 行（4 个权限 + 3 个开关 + 1 个指令）占着首页最显眼的位置，
     *   而它们是「设完就走」的低频项，还要靠展开折叠卡才能看到。
     *
     * 【为什么保留一行摘要】导航行不该是空壳：右侧显示当前最关键的状态
     *   （有几项未授权），用户不点进去也能知道「这里有没有事要处理」。
     *   这是设计稿「trailing values 是状态摘要而非名词」的落地。
     */
    @Composable
    private fun PermissionRow(act: MainActivity) {
        // 未授权的项数：Shizuku 未授权 / 悬浮窗 / 通知 / 电池白名单，各自 0 或 1。
        val missing = listOf(
            HomeState.overlay,
            HomeState.notif,
            HomeState.battery,
            HomeState.shizuku == com.dollhouse.app.device.ShizukuBridge.S_GRANTED
        ).count { !it }
        // 【为什么用 DhGroup + DhRow 而不是 DhKit.Card + ActionRow】
        //   ActionRow 自带 soft 底 + 圆角（旧式"每行一个小卡片"），与本轮统一的
        //   "卡片内发丝线行"规范冲突；而 ui/kit 的 DhGroup/DhRow 就是那套规范本身。
        //   这也是让组件库真正被页面使用的又一处（此前 DhRow/DhGroup 库外调用为 0）。
        com.dollhouse.app.ui.kit.DhGroup {
            com.dollhouse.app.ui.kit.DhRow(
                title = "权限与保活",
                desc = "系统授权、后台存活与启停通道",
                iconRes = Icons.IC_SHIELD_CHECK,
                // 右侧是「状态摘要」而非名词（设计稿 §4.6）。
                value = if (missing == 0) "已就绪" else "$missing 项待处理",
                showChevron = true,
                onClick = { com.dollhouse.app.ui.settings.PermissionPage.open(act) }
            )
        }
    }

    /* ============================ 聊天 ============================ */

    @Composable
    private fun ChatCard(act: MainActivity) {
        DhKit.Card(collapsibleTitle = "聊天", titleIcon = Icons.IC_MESSAGE_SQUARE_HEART) {
            DhKit.ValueRow(
                label = "供应商",
                iconRes = Icons.IC_SERVER,
                desc = "接口地址、密钥与模型列表",
                showChevron = true
            ) {
                ProviderNav.open(act)
            }
            DhKit.ValueRow(
                label = "查看 Token",
                iconRes = Icons.IC_GAUGE,
                desc = "查看模型使用统计",
                showChevron = true
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
                showChevron = true
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