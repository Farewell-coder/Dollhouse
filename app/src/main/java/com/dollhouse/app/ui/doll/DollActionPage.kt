package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.ActionLibrary
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.model.ModelImporter
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhAnim
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 【职责】「动作」清单页：这只人偶的**全部动作**，按五个组分区展示。
 *
 * ## v0.1.0 的五组体系（本页是它唯一的落地界面）
 * ```
 * ① 常驻动作（固定 1）  天生存在，不可删、不可换组，只能「编辑」和「清空」
 * ② 贴边动作（固定 1）  同上 + 一条可拖的裁截线
 * ③ 拖拽动作（固定 1）  同上，拖动期间强制循环
 * ④ 随机动作（不限量）  新建的动作只落这两组；可删、可在两组之间换
 * ⑤ 手动动作（不限量）
 * ```
 *
 * ## 三个固定壳为什么「永远排最前」
 * 用户的原话是「新建人偶的时候默认会有一个常驻任务和侧面的空白模板」——
 * 它们是动作体系的**三个固定位**，不是「恰好排在前面的两个动作」。
 * 所以本页把它们单独画成一排**槽位卡**（横向三格，带组名小字与状态），
 * 与下面「普通动作列表」在视觉上明确分层；滚动时它们也不会被列表项盖住。
 *
 * ## 为什么每个固定壳都有「清空」而没有「删除」
 * 删掉之后「常驻」这个概念就无处落地了。想换内容是「清空帧 → 导入新图 → 改名」，
 * 名字 / 描述 / 速度在清空后**保留**（用户定案）。
 */
object DollActionPage {
    /** 页面 key 前缀：`doll:action:<dollId>`。 */
    const val PREFIX = "doll:action:"

    /**
     * 「固定位」页签的合成 key。
     *
     * 【为什么需要一个假组名】本轮把常驻 / 贴边 / 拖拽三个固定组并成一个页签，
     *   而页签的选中值是 `String`（其余两个页签直接复用 [DollAnim.GROUP_RANDOM] /
     *   [DollAnim.GROUP_MANUAL]）。这里用一个不会与真实组名冲突的哨兵值，
     *   渲染时按 [DollAnim.FIXED_GROUPS] 逐张取卡。
     *   **不要**把它写进动作的 `group` 字段 —— 动作的组名仍然是那五个真实值。
     */
    private const val TAB_FIXED = "__fixed__"

    /** 固定槽位卡的边长（dp）。三格并排，屏幕上正好放得下且不挤。 */
    private const val SLOT_SIZE = 92

    /**
     * 「固定位」页签上的角标数字 = 三格里已经有内容的那几格。
     *
     * 【为什么不是三个组的动作总数】固定组各自只有一格，总数恒为 3，
     *   挂在页签上毫无信息量。真正有用的是「有几格是空的」——
     *   用户一眼就知道还缺几个固定动作。
     */
    private fun countFixed(groups: List<Pair<String, List<DollAnim.Action>>>): Int {
        var n = 0
        for (p in groups) {
            if (!DollAnim.isFixedGroup(p.first)) {
                continue
            }
            val a = p.second.firstOrNull() ?: continue
            if (a.frames.isNotEmpty() || a.assetPath.isNotEmpty()) {
                n++
            }
        }
        return n
    }

    @JvmStatic
    fun open(act: MainActivity, id: String) {
        PageStack.push(act, PREFIX + id) { buildPage(act, id) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity, id: String): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act, id) }
    }

    @Composable
    private fun Content(act: MainActivity, id: String) {
        val doll = DollStore.byId(act, id)
        // 【按 rev 重算】本页 View 常驻页面栈；从动作详情页返回后本层不会重建。
        //   改名 / 改帧 / 删除都要立刻看到，故用全局变更信号 + 本地 tick 双保险。
        var tick by remember { mutableStateOf(0) }
        val rev = DollState.rev
        val groups = remember(rev, tick, id) { DollAnim.grouped(act, id) }
        var newOpen by remember { mutableStateOf(false) }
        // 【v0.1.0】新建弹窗默认落哪一组：从某个组的「+」进来就落那一组，
        //   从顶栏「+」进来看 [DollAnim.GROUP_MANUAL]（默认值）。
        var newGroup by remember { mutableStateOf(DollAnim.GROUP_MANUAL) }
        // 待确认删除的动作 id（长按普通列表项设置）。
        var delId by remember { mutableStateOf<String?>(null) }
        // 待确认清空的固定壳 id。
        var clearId by remember { mutableStateOf<String?>(null) }
        // 【v0.1.0·按需求稿重排】顶部用分段控件切组，一次只看一组。
        //
        // 【为什么改】原先五个组一口气平铺：固定三格 + 两个可折叠列表。
        //   动作多的用户滚到「手动」时已经忘了上面还有「随机」，
        //   而且折叠状态不落盘，每次进页面都从「全展开」开始。
        //   现在一次只呈现一组，滚动长度恒定，切换是**互斥**的，不会迷路。
        //
        // 【默认停在哪一组】固定位：进来第一眼就能看到这套动作体系的三根柱子，
        //   也是新用户唯一「一定有内容」的地方（内置呼吸已在常驻格里）。
        var tab by remember { mutableStateOf(TAB_FIXED) }
        // 模型包导入。
        var pkgZip by remember { mutableStateOf<String?>(null) }
        var pkgCheck by remember { mutableStateOf<ModelImporter.Inspection?>(null) }
        var pkgMsg by remember { mutableStateOf<String?>(null) }
        // ---------- 【工作台】动作库：搜索 ----------
        // 【为什么要搜索】动作可以建到几十个（随机组不限量），组内滚动手找
        //   早就不现实了。搜索是「动作库」变成「库」的分水岭。
        var query by remember { mutableStateOf("") }
        // 复制 / 导出 / 引用检查的动作 id（非空则弹对应窗）。
        var dupId by remember { mutableStateOf<String?>(null) }
        var exportId by remember { mutableStateOf<String?>(null) }
        var exportMsg by remember { mutableStateOf<String?>(null) }

        DhKit.Page(
            title = "动作",
            sub = if (doll == null) null else doll.name,
            onBack = { PageStack.pop(act) },
            actions = {
                // 【本轮·修顶栏挤压】改造前三个入口都带文字（「恢复内置」「导入资源」「新建」），
                //   合计约 250dp，而 `DhKit.TopBar` 让标题占满剩余宽后再放 actions ——
                //   1080p 屏上三者会把标题挤到换行、最右那颗「新建」被裁掉一半，
                //   表现为「按钮点了没反应」（其实点在了裁剪区之外）。
                //   现在一律走**纯图标**（DhKit.IconButton 的 label 只在宽裕处才加），
                //   语义由图标 + 下方说明承载；图标本身已是 Lucide 语义对齐的。
                // 【恢复内置已搬走】它做的是「把这套动作恢复成出厂自带的样子」，
                //   属于人偶级操作，已挪到「人偶管理 → 更多」里（见 DollListScreen）。
                DhKit.IconButton(Icons.IC_FOLDER_OPEN, Design.SZ_MD, DhTokens.colors.title) {
                    DollImportWizardPage.open(act, id)
                }
                DhKit.IconButton(Icons.IC_PLUS, Design.SZ_MD, DhTokens.colors.title) {
                    newGroup = DollAnim.GROUP_MANUAL
                    newOpen = true
                }
            }
        ) {
            val total = remember(groups) {
                var n = 0
                for (g in groups) {
                    n += g.second.size
                }
                n
            }
            DhForm.Note(
                "常驻 / 贴边 / 拖拽是固定三格，永远存在：想换内容就清空它、再导入新的一组图。" +
                    "随机和手动的动作可以随意增删，「属性」里能在两者之间换组。"
            )
            if (total == 0) {
                // 【只在真的一无所有时提示】三个固定壳正常都由 [DollAnim.grouped] 补齐，
                //   走到这里说明动作数据整体不可读（存储损坏 / 首次迁移失败），
                //   属于异常态，必须明确告诉用户「可以恢复」而不是「点 + 开始添加」。
                DhForm.Note("动作数据是空的。点右上角「恢复内置」可以重建三个固定动作。")
            }
            // ---------- ⓪-1 搜索框 ----------
            // 【为什么放在切组之前】搜索是「跨组找东西」，若放在组页签下面，
            //   用户会理所当然地以为「只在本组里搜」，于是搜不到就以为动作丢了。
            //   这里明确摆在最上面 + 提示文案，语义是「在整个动作库里找」。
            if (total > 0) {
                DhForm.Input(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索动作",
                    hint = "输入名字或描述里的字，例如「挥手」"
                )
                if (query.trim().isNotEmpty()) {
                    val hitN = remember(groups, query) {
                        var n = 0
                        for (p in groups) {
                            n += ActionLibrary.search(p.second, query).size
                        }
                        n
                    }
                    DhForm.Note(
                        if (hitN == 0) "没有匹配「" + query.trim() + "」的动作。清空搜索框可以看全部。"
                        else "在全部五个分组里找到 " + hitN + " 个匹配「" + query.trim() + "」的动作。"
                    )
                }
            }
            // ---------- ⓪ 顶部切组 ----------
            // 【为什么要分段控件而不是继续平铺】分组平铺时，滚动长度随动作数量线性增长，
            //   用户滚到「手动」就看不到「固定位」了；而且每组列表各自带折叠箭头，
            //   同一个页面里出现好几种「要不要展开」的判断，心智负担很重。
            //   改成「一次只看一组」：滚动手感恒定，切组是互斥的，位置永远可预期。
            //
            // 【本轮·需求 2：五个页签收敛成三个】
            //   用户原话：「我喜欢把三个固定动作弄到一个分组里面 一共三个分组」。
            //   改造前常驻 / 贴边 / 拖拽各占一个页签，加上随机 / 手动共 5 个，
            //   横向要滚动才看得全，而且三个固定位本来就是一整套东西。
            //   现在：**固定位**（三张卡同屏并排）/ **随机动作** / **手动动作**，共 3 个。
            //   贴边、拖拽的专属设置（裁截线、强制循环）没有被藏起来 ——
            //   它们仍在各自卡片的详情页里，点卡片就能进。
            val segItems = remember(groups) {
                val countOf = HashMap<String, Int>()
                for (p in groups) {
                    countOf[p.first] = p.second.size
                }
                val fixedCount = countFixed(groups)
                listOf(
                    DhKit.SegItem(TAB_FIXED, "固定位", Icons.IC_INFINITY, fixedCount),
                    DhKit.SegItem(DollAnim.GROUP_RANDOM, "随机", Icons.IC_SHUFFLE,
                        countOf[DollAnim.GROUP_RANDOM] ?: 0),
                    DhKit.SegItem(DollAnim.GROUP_MANUAL, "手动", Icons.IC_HAND,
                        countOf[DollAnim.GROUP_MANUAL] ?: 0)
                )
            }
            DhKit.Segmented(
                items = segItems,
                selectedKey = tab,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            ) { tab = it }

            // ---------- ① 固定位：三张卡并排（本轮需求 2 的核心）----------
            if (tab == TAB_FIXED) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (g in DollAnim.FIXED_GROUPS.filter { it != DollAnim.GROUP_EDGE_IN }) {
                        val fixedAction = remember(groups, g) {
                            var hit: DollAnim.Action? = null
                            for (p in groups) {
                                if (p.first == g) {
                                    hit = p.second.firstOrNull()
                                }
                            }
                            hit
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            SlotCard(
                                dollId = id,
                                group = g,
                                action = fixedAction,
                                onOpen = {
                                    if (fixedAction != null) {
                                        DollAnimPage.open(act, id, fixedAction.id)
                                    }
                                },
                                onClear = { if (fixedAction != null) clearId = fixedAction.id },
                                onDuplicate = { if (fixedAction != null) dupId = fixedAction.id },
                                onExport = { if (fixedAction != null) exportId = fixedAction.id }
                            )
                        }
                    }
                }
                DhForm.Note(
                    "常驻 / 拖拽 / 贴边是固定三格，永远存在，不能删也不能换组。" +
                        "常驻 = 平时站着；拖拽 = 手指按住移动时；贴边 = 贴着边缘期间一直循环。" +
                        "进入贴边可以设置必选的循环动画，也可以补充可选的贴入瞬间。"
                )
            }

            // ---------- ② 随机 / 手动：当前页签的那一组 ----------
            if (DollAnim.SWAPPABLE_GROUPS.contains(tab)) {
                var list: List<DollAnim.Action> = emptyList()
                for (p in groups) {
                    if (p.first == tab) {
                        list = p.second
                    }
                }
                // 【工作台】搜索过滤只作用于**当前页签的列表**。
                //   「命中数」提示已经在上面按全库算过，这里只需要把本组结果剪出来，
                //   两处口径不同但各有明确语义：上面回答「整库有几个」，
                //   这里回答「本组要显示哪几条」。
                if (query.trim().isNotEmpty()) {
                    list = ActionLibrary.search(list, query)
                }
                val isRandom = tab == DollAnim.GROUP_RANDOM
                if (list.isEmpty()) {
                    DhForm.Note(
                        if (query.trim().isNotEmpty()) "这一组里没有匹配「" + query.trim() + "」的动作。"
                        else if (isRandom) "这一组还是空的。加了动作之后，还要在「控制」里打开随机开关才会自己播。"
                        else "这一组还是空的。加了动作之后，可以在「操作」里把它绑到某个手势上。"
                    )
                }
                for (a in list) {
                    ActionCard(
                        action = a,
                        onOpen = {
                            DollAnimPage.open(act, id, a.id)
                            tick++
                        },
                        onDelete = { delId = a.id },
                        onDuplicate = { dupId = a.id },
                        onExport = { exportId = a.id }
                    )
                }
                // 组内新建：与需求稿一致，把「＋ 新建」放在列表末尾，
                //   而不是只留右上角那个 —— 追加动作是这一页最高频的动作之一，
                //   让它在拇指可及处。
                // 【为什么用 MiniChip 而不是 OutlineChip】OutlineChip 是「药丸」形态
                //   （内容自适应宽度、靠左），铺满一行会变成一条左对齐的窄药丸；
                //   MiniChip 内部把内容居中且撑满调用方给的宽度，正是「整行按钮」要的形态，
                //   也与帧操作那几个按钮同款，视觉上属于同一族。
                Spacer(Modifier.height(DhKit.GAP_ROW.dp))
                DhKit.MiniChip("新建" + tab, iconRes = Icons.IC_PLUS) {
                    newGroup = tab
                    newOpen = true
                }
            }
        }

        // ---------- 新建弹窗 ----------
        if (newOpen) {
            NewActionSheet(
                act = act,
                dollId = id,
                initialGroup = newGroup,
                onDismiss = { newOpen = false },
                onDone = {
                    newOpen = false
                    tick++
                }
            )
        }

        // ---------- 模型包：检查结果确认 ----------
        val chk = pkgCheck
        if (chk != null && chk.canInstall) {
            val m = chk.manifest
            DhForm.Alert(
                title = if (chk.code == ModelImporter.Code.DUPLICATE) "覆盖导入？" else "导入模型包",
                onDismiss = { pkgCheck = null; pkgZip = null },
                posText = "导入",
                onPos = {
                    pkgCheck = null
                    val zip = pkgZip
                    pkgZip = null
                    if (zip == null) {
                        return@Alert
                    }
                    Thread({
                        val r = ModelImporter.install(act, zip, id, chk)
                        act.runOnUiThread {
                            pkgMsg = r.message + if (r.warnings.isEmpty()) "" else
                                "（" + r.warnings.joinToString("；") + "）"
                            HomeUi.notifyPetReload(act)
                            DollState.bump()
                            tick++
                        }
                    }, "dh-model-install").start()
                },
                negText = "取消"
            ) {
                Text(
                    text = "名称：" + (m?.name ?: "") + "\n" +
                        "标识：" + (m?.id ?: "") + "\n" +
                        "引擎：" + (m?.engine ?: "") + "\n" +
                        "规范版本：" + (m?.specVersion ?: 0) +
                        (if ((m?.author ?: "").isEmpty()) "" else "\n作者：" + m?.author) +
                        (if (chk.code == ModelImporter.Code.DUPLICATE)
                            "\n\n⚠ 已存在同标识的模型包，导入会覆盖它。" else ""),
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
                if (chk.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = chk.warnings.joinToString("\n"),
                        color = DhTokens.colors.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
        }

        // ---------- 模型包：失败提示 ----------
        val msg = pkgMsg
        if (msg != null) {
            DhForm.Alert(
                title = "模型包",
                onDismiss = { pkgMsg = null },
                posText = "知道了",
                onPos = { pkgMsg = null }
            ) {
                Text(
                    text = msg,
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }

        // ---------- 删除普通动作 ----------
        val delTarget = delId
        if (delTarget != null) {
            val target = remember(delTarget, rev) { DollAnim.byId(act, id, delTarget) }
            // 【工作台】删前查引用：手势绑了这个动作的话，必须告诉用户「删了会一起解绑」，
            //   否则他下次点那个手势会静默没反应（这类「点了没反应」的 bug 极难自查）。
            val refs = remember(delTarget, rev) { ActionLibrary.refsOf(act, id, delTarget) }
            DhForm.Alert(
                title = "删除动作",
                onDismiss = { delId = null },
                posText = "删除",
                onPos = {
                    // 【R-112 九.4】最后一个动作且无形象图时拒绝删除，并说明原因。
                    if (!DollAnim.remove(act, id, delTarget)) {
                        HomeUi.showNotice(act, "至少保留一个动作", "这是最后一个动作了。先给她一张形象图，再删除。")
                        delId = null
                        return@Alert
                    }
                    // 【顺序要紧】先删动作再清引用：反过来若 remove 失败，
                    //   引用已经被擦掉，用户的手势会莫名其妙失效。
                    val n = ActionLibrary.purgeRefs(act, id, delTarget)
                    HomeUi.notifyPetReload(act)
                    DollState.bump()
                    tick++
                    delId = null
                    if (n > 0) {
                        HomeUi.showNotice(
                            act, "已删除并解绑",
                            "这个动作占了 " + n + " 个手势位，已一并取消绑定（不会留下点了没反应的手势）。"
                        )
                    }
                }
            ) {
                Text(
                    text = "确定删除「" + (target?.name ?: "") + "」吗？它下面的所有帧都会被一起删掉。",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
                if (refs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "⚠ 它现在还被这些地方用着：",
                        color = DhTokens.colors.title,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fontsBold
                    )
                    for (r in refs) {
                        Text(
                            text = "· " + r.label,
                            color = DhTokens.colors.sub,
                            fontSize = UiKit.FS_TINY.sp,
                            fontFamily = DhTokens.fonts
                        )
                    }
                    Text(
                        text = "删除时会一并取消这些绑定，不会留下悬空的设置。",
                        color = DhTokens.colors.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
        }

        // ---------- 【工作台】复制动作 ----------
        val dupTargetId = dupId
        if (dupTargetId != null) {
            val target = remember(dupTargetId, rev) { DollAnim.byId(act, id, dupTargetId) }
            DhForm.Alert(
                title = "复制动作",
                onDismiss = { dupId = null },
                posText = "复制",
                onPos = {
                    dupId = null
                    val a = target ?: return@Alert
                    Thread({
                        val copy = ActionLibrary.duplicate(act, id, a.id)
                        act.runOnUiThread {
                            if (copy == null) {
                                HomeUi.showNotice(act, "复制失败", "没能建出副本，请重试。")
                            } else {
                                DollState.bump()
                                tick++
                                HomeUi.showNotice(
                                    act, "已复制",
                                    "「" + copy.name + "」已建好（" + copy.frames.size + " 帧）。" +
                                        "帧图是独立的一份，删掉其中一个不会影响另一个。"
                                )
                            }
                        }
                    }, "dh-action-dup").start()
                },
                negText = "取消"
            ) {
                Text(
                    text = "会把「" + (target?.name ?: "") + "」连同它的 " +
                        (target?.frames?.size ?: 0) + " 帧、播放速度、显示设置一起复制一份。",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
                if (target != null && DollAnim.isFixedGroup(target.group)) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "「" + target.group + "」是固定位，副本会放进「" + DollAnim.GROUP_MANUAL +
                            "」组（固定位只能有一个）。",
                        color = DhTokens.colors.sub,
                        fontSize = UiKit.FS_TINY.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
        }

        // ---------- 【工作台】导出动作 ----------
        val expTargetId = exportId
        if (expTargetId != null) {
            val target = remember(expTargetId, rev) { DollAnim.byId(act, id, expTargetId) }
            DhForm.Alert(
                title = "导出动作",
                onDismiss = { exportId = null },
                posText = "导出",
                onPos = {
                    exportId = null
                    val a = target ?: return@Alert
                    Thread({
                        // 导出到 App 的外部文件目录：无需存储权限，用户也能在
                        // 文件管理里找到（Android/data/com.dollhouse.app/files/export）。
                        val dir = java.io.File(act.getExternalFilesDir(null) ?: act.filesDir, "export")
                        val r = ActionLibrary.export(act, id, a.id, dir)
                        act.runOnUiThread {
                            exportMsg = r.message
                        }
                    }, "dh-action-export").start()
                },
                negText = "取消"
            ) {
                Text(
                    text = "会打包成 zip：「" + (target?.name ?: "") + "」的 " +
                        (target?.frames?.size ?: 0) + " 帧、播放参数、锚点与触发设置都带上。",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "导出位置：应用文件目录 / export/。帧图会按播放顺序重命名，" +
                        "别人拿到包不用猜顺序。",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }

        // ---------- 【工作台】导出结果 ----------
        val expMsg = exportMsg
        if (expMsg != null) {
            DhForm.Alert(
                title = "导出结果",
                onDismiss = { exportMsg = null },
                posText = "知道了",
                onPos = { exportMsg = null }
            ) {
                Text(
                    text = expMsg,
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }

        // ---------- 清空固定壳 ----------
        val clrTarget = clearId
        if (clrTarget != null) {
            val target = remember(clrTarget, rev) { DollAnim.byId(act, id, clrTarget) }
            val group = target?.group ?: DollAnim.GROUP_ALWAYS
            DhForm.Alert(
                title = "清空「" + group + "」",
                onDismiss = { clearId = null },
                posText = "清空",
                onPos = {
                    DollAnim.clearFrames(act, id, clrTarget)
                    HomeUi.notifyPetReload(act)
                    DollState.bump()
                    tick++
                    clearId = null
                }
            ) {
                Text(
                    text = "会把这一格里的 " + (target?.frames?.size ?: 0) + " 帧全部删掉，" +
                        "之后你可以导入新的一组图。\n\n名字、描述、播放速度都会保留。",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /* ============================ 固定槽位卡 ============================ */

    /**
     * 固定壳（常驻 / 贴边 / 拖拽）的槽位卡。
     *
     * 【为什么竖排小卡而不是普通列表行】三格要一眼看出「这是固定的三个位子」；
     *   排成横向三格 + 统一高度，视觉上就是一组，与下面的可变列表明确分层。
     *
     * @param action null 表示这一格还没被补上（理论上不会发生，`all()` 会补）
     * @param onDuplicate 复制（固定位副本会落到手动组）
     * @param onExport    导出成 zip
     */
    @Composable
    private fun SlotCard(
        dollId: String,
        group: String,
        action: DollAnim.Action?,
        onOpen: () -> Unit,
        onClear: () -> Unit,
        onDuplicate: () -> Unit,
        onExport: () -> Unit
    ) {
        val c = DhTokens.colors
        val empty = action == null || (action.frames.isEmpty() && action.assetPath.isEmpty())
        val first = action?.frames?.firstOrNull()
        var thumb by remember(first) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(first) {
            if (first == null) {
                return@LaunchedEffect
            }
            thumb = withContext(Dispatchers.IO) { decodeSmall(first) }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                .background(c.cardUp)
                .border(1.dp, if (empty) c.line else c.acc.copy(alpha = 0.35f),
                    RoundedCornerShape(DhKit.R_CARD.dp))
                .pressable(onOpen)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 图标 + 组名（固定位的身份标识）
            Row(verticalAlignment = Alignment.CenterVertically) {
                DhKit.Icon(fixedIcon(group), Design.SZ_XS, c.acc)
                Spacer(Modifier.width(3.dp))
                Text(
                    text = group.removeSuffix("动作"),
                    color = c.acc,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((SLOT_SIZE - 34).dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.soft)
                    .border(1.dp, c.line, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                val b = thumb
                if (b != null) {
                    Image(
                        bitmap = b,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().padding(3.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    DhKit.Icon(fixedIcon(group), Design.SZ_MD, c.sub)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = if (empty) "未设置" else (action?.name ?: ""),
                color = if (empty) c.sub else c.title,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = if (empty) DhTokens.fonts else DhTokens.fontsBold,
                fontWeight = if (empty) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (empty) "点一下去添加帧"
                else action!!.frames.size.toString() + " 帧 · " + action.fps + "fps",
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 【统一】三个操作横排一行，与「动作详情页」的复制 / 导出同一种排法。
            //   改造前这里是「清空」独占一行、下面再排两个 —— 同一种小按钮在一张卡里
            //   出现两种排法（一列 + 一行），卡片高度被拉高，视觉上也比别处松散。
            //   【为什么改成三列等分】固定位卡片宽度足够放三个 2 字按钮；
            //   等分后每个命中区都 ≥ 40dp，比原来「清空」整行的超宽按钮更均匀。
            if (!empty && dollId != DollStore.DEFAULT_ID) {
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    DhKit.MiniChip("清空", iconRes = Icons.IC_ERASER) { onClear() }
                    Spacer(Modifier.width(12.dp))
                    DhKit.MiniChip("导出", iconRes = Icons.IC_DOWNLOAD, onClick = onExport)
                }
            }
        }
    }

    /**
     * 组图标（Lucide 语义对齐）。
     *
     * 【为什么五个组全都要有】早先只有三个固定壳有图标，随机 / 手动落到
     *   `else -> IC_SPARKLES` —— 两个可换组长得一样。分段页签把它们并排放在一起时，
     *   「随机」和「手动」的图标完全相同，等于没给信息。
     *   现在五组各一个：无限=常驻、贴边=面板靠边、抓手=拖拽、洗牌=随机、手掌=手动。
     */
    private fun fixedIcon(group: String): Int = when (group) {
        DollAnim.GROUP_ALWAYS -> Icons.IC_INFINITY
        DollAnim.GROUP_EDGE -> Icons.IC_PANEL_RIGHT
        DollAnim.GROUP_DRAG -> Icons.IC_GRAB
        // 【v0.1.5】贴入瞬间：入边那一下的过渡，用「移入」语义的图标。
        //   【为什么不新画图标】工程已集成 Lucide 子集（见 ui/theme/Icons.kt），
        //   为一个固定卡新增 drawable 收益很低；IC_MOVE 与「滑入边缘」的语义也对得上。
        DollAnim.GROUP_EDGE_IN -> Icons.IC_MOVE
        DollAnim.GROUP_RANDOM -> Icons.IC_SHUFFLE
        DollAnim.GROUP_MANUAL -> Icons.IC_HAND
        else -> Icons.IC_SPARKLES
    }

    /* ============================ 普通动作卡 ============================ */

    /**
     * 一条动作：左侧缩略图（取第一帧）+ 名称 + 描述 + 帧数/速度。
     *
     * @param onDuplicate 复制（深拷贝帧文件，见 [ActionLibrary.duplicate]）
     * @param onExport   导出成 zip（见 [ActionLibrary.export]）
     */
    @Composable
    private fun ActionCard(
        action: DollAnim.Action,
        onOpen: () -> Unit,
        onDelete: () -> Unit,
        onDuplicate: () -> Unit,
        onExport: () -> Unit
    ) {
        val c = DhTokens.colors
        val first = action.frames.firstOrNull()
        var thumb by remember(first) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(first) {
            if (first == null) {
                return@LaunchedEffect
            }
            thumb = withContext(Dispatchers.IO) { decodeSmall(first) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DhKit.GAP_ROW.dp)
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .background(c.soft)
                // 短按进详情、长按弹删除确认（与聊天会话列表同款手势，不挤占行内空间）。
                .pressable(onOpen, onDelete)
                .padding(horizontal = DhKit.PAD_ROW.dp, vertical = DhKit.PAD_ROW.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.cardUp)
                    .border(1.dp, c.line, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                val b = thumb
                if (b != null) {
                    Image(
                        bitmap = b,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().padding(2.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    DhKit.Icon(
                        if (action.group == DollAnim.GROUP_RANDOM) Icons.IC_SHUFFLE
                        else Icons.IC_HAND,
                        Design.SZ_SM, c.sub
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.name,
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (action.desc.isEmpty()) "（没有描述）" else action.desc,
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = (if (action.builtin) "内置 · " else "自定义 · ") +
                        (if (action.frames.isEmpty() && action.assetPath.isEmpty()) "待生成"
                        else if (action.frames.isEmpty()) mapEngineLabel(action.engine)
                        else action.frames.size.toString() + " 帧") +
                        " · " + action.fps + " 帧/秒" +
                        (if (action.loop) " · 循环" else ""),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
            DhKit.Icon(Icons.IC_CHEVRON_RIGHT, Design.SZ_SM, c.sub)
        }
        // ---------- 【工作台】行内操作条 ----------
        // 【为什么单独一行而不是塞进右侧】右侧已经有一个 chevron（进详情），
        //   再并排两颗图标会让「点哪是哪」变得模糊；分一行后每个动作的
        //   复制 / 导出都用文字说清楚，不会误触。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                DhKit.MiniChip("复制", iconRes = Icons.IC_COPY, onClick = onDuplicate)
            }
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                DhKit.MiniChip("导出", iconRes = Icons.IC_DOWNLOAD, onClick = onExport)
            }
        }
    }

    /* ============================ 新建弹窗 ============================ */

    /**
     * 【R-112 七节】添加动作：只允许随机 / 手动两种（四态在状态动画里设置）。
     *
     * 【为什么固定三组不在选项里】它们是天生存在的壳，
     *   建成「第二个常驻」没有意义 —— 换内容的方式是清空壳再导入。
     */
    @Composable
    private fun NewActionSheet(
        act: MainActivity,
        dollId: String,
        initialGroup: String = DollAnim.GROUP_MANUAL,
        onDismiss: () -> Unit,
        onDone: () -> Unit
    ) {
        var draft by remember { mutableStateOf("") }
        // 目标组（0 = 随机，1 = 手动）—— 由调用方带入，默认落「手动」。
        var groupIdx by remember(initialGroup) {
            mutableStateOf(
                Math.max(0, DollAnim.SWAPPABLE_GROUPS.indexOf(initialGroup))
            )
        }
        // 【R-112 七.3】来源只有三种。模型包不在这里。
        var mode by remember { mutableStateOf(0) }
        val modes = arrayOf(
            "空白动作",
            "图片 / 序列帧",
            "Lottie"
        )
        val modeHints = arrayOf(
            "先创建，稍后逐帧添加。",
            "PNG、GIF、WebP、ZIP。可以多选。",
            "Bodymovin 导出的 .json。"
        )
        DhForm.Alert(
            title = "添加动作",
            onDismiss = onDismiss,
            posText = if (mode == 0) "创建" else "选择文件",
            onPos = {
                val name = draft.trim()
                val group = DollAnim.SWAPPABLE_GROUPS[groupIdx]
                if (mode == 1) {
                    onDismiss()
                    DollImportWizardPage.open(act, dollId, name, group)
                    return@Alert
                }
                if (mode == 2) {
                    onDismiss()
                    pickNewLottie(act, dollId, name, group)
                    return@Alert
                }
                val a = DollAnim.add(
                    act, dollId, name.ifBlank { "新动作" }, "",
                    emptyList(), DollAnim.FPS_DEFAULT, true, group
                )
                if (a == null) {
                    HomeUi.showNotice(act, "创建失败", "没能保存这个动作，请重试。")
                } else {
                    onDone()
                    // 直接进详情页，让用户马上加帧（少一次往返）。
                    DollAnimPage.open(act, dollId, a.id)
                }
            },
            negText = "取消"
        ) {
            // 【R-112 七节】副标题：明确告诉用户「这是给谁的」，避免他以为在新建独立动作。
            DhForm.Note("给 " + (DollStore.byId(act, dollId)?.name ?: "这个角色"))
            DhKit.SectionLabel("什么时候播放")
            DhKit.CheckRow(
                text = "自动：待机时每隔 15–60 秒自己播放一次",
                selected = groupIdx == 0
            ) { groupIdx = 0 }
            DhKit.CheckRow(
                text = "手动：点击角色时随机播放",
                selected = groupIdx == 1
            ) { groupIdx = 1 }
            DhForm.Note("待机、拖拽和贴边在「状态动画」里设置。")
            DhForm.Gap()
            DhKit.SectionLabel("来源")
            for (i in modes.indices) {
                DhKit.CheckRow(text = modes[i], selected = mode == i) { mode = i }
            }
            DhForm.Note(modeHints[mode])
            DhForm.Gap()
            DhForm.Input(
                value = draft,
                onValueChange = { draft = it },
                label = "名称",
                hint = "例如：挥手、转圈"
            )
            when (mode) {
                0 -> DhForm.Note("创建后进入详情页：可以一次导入整组图，也可以逐张添加、删除、调顺序。")
                1 -> DhForm.Note("打开图片导入向导：选择并检查资源后，再确认创建动作。")
                else -> DhForm.Note("选择自包含的 Lottie .json；校验和保存成功后才创建动作。")
            }
            DhForm.Note("整套模型请到「模型 → 更换模型」导入。")
        }
    }

    /** 新建独立 Lottie 动作：先验证并复制到私有目录，成功后才登记动作。 */
    private fun pickNewLottie(act: MainActivity, dollId: String, name: String, group: String) {
        com.dollhouse.app.PickFileActivity.setFileListener { paths, failed ->
            if (paths.isEmpty()) {
                if (failed) HomeUi.showNotice(act, "读取失败", "没能读取选中的文件，请重试。")
                return@setFileListener
            }
            Thread({
                val src = File(paths[0])
                var saved: File? = null
                try {
                    val renderer = com.dollhouse.app.pet.render.LottieRenderer()
                    if (!renderer.prepare(com.dollhouse.app.pet.render.PetSource.Lottie(src.absolutePath))) {
                        throw IllegalArgumentException(renderer.lastError() ?: "不是有效的 Lottie 动画")
                    }
                    renderer.release()
                    val id = "lottie_" + UUID.randomUUID().toString().replace("-", "").take(12)
                    val dst = File(DollAnim.framesDir(act, dollId, id), "animation.json")
                    src.copyTo(dst, overwrite = false)
                    saved = dst
                    val action = DollAnim.Action(
                        id = id, name = name.ifBlank { "Lottie 动作" }, group = group,
                        engine = DollAnim.ENGINE_LOTTIE, assetPath = dst.absolutePath
                    )
                    if (!DollAnim.addEngineAction(act, dollId, action)) {
                        throw java.io.IOException("动作保存失败")
                    }
                    src.delete()
                    act.runOnUiThread {
                        HomeUi.notifyPetReload(act)
                        DollState.bump()
                        DollAnimPage.open(act, dollId, id)
                    }
                } catch (t: Throwable) {
                    saved?.delete()
                    src.delete()
                    act.runOnUiThread {
                        HomeUi.showNotice(act, "Lottie 导入失败", t.message ?: "文件无效，请重新选择。")
                    }
                }
            }, "dh-lottie-import").start()
        }
        com.dollhouse.app.PickFileActivity.startFiles(act, false)
    }

    /** 非帧序列引擎在列表里的显示名（避免写死字符串在多处）。 */
    private fun mapEngineLabel(engine: String): String = when (engine) {
        DollAnim.ENGINE_LOTTIE -> "Lottie"
        DollAnim.ENGINE_ANIMIMAGE -> "动图"
        else -> "待生成"
    }

    /** 缩略图解码：统一走 ImageDecode（采样策略与全工程一致）。 */
    private fun decodeSmall(path: String): ImageBitmap? {
        val b = com.dollhouse.app.core.ImageDecode.file(
            path, reqWidth = 192, reqHeight = 192
        )
        return b?.asImageBitmap()
    }
}
