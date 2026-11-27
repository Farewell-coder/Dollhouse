package com.dollhouse.app.ui.doll

import android.graphics.BitmapFactory
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dollhouse.app.CoverCropActivity
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollSizeSpec
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.design.LocalCompactHeight
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.qzx.LocalQzxScrollStates
import com.dollhouse.app.ui.qzx.QzxButtonSpec
import com.dollhouse.app.ui.qzx.QzxButtonStyle
import com.dollhouse.app.ui.qzx.QzxChip
import com.dollhouse.app.ui.qzx.QzxColors
import com.dollhouse.app.ui.qzx.QzxDialog
import com.dollhouse.app.ui.qzx.QzxFonts
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
import com.dollhouse.app.ui.qzx.QzxPageScroll
import com.dollhouse.app.ui.qzx.QzxPageScrollStates
import com.dollhouse.app.ui.qzx.QzxSurface
import com.dollhouse.app.ui.qzx.QzxTokens
// 【必须显式 import】`chips` / `buttons` / `slider` 是 QzxSectionScope 的**顶层扩展函数**
//   （在 QzxControls.kt 里，不在行型类内部），与 `item` / `note` / `input`（类成员）不同 ——
//   漏 import 会编译不过（本仓 MemSettingsPage 也是这么 import 的）。
import com.dollhouse.app.ui.qzx.buttons
import com.dollhouse.app.ui.qzx.chips
import com.dollhouse.app.ui.qzx.slider
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dollhouse.app.ui.qzx.QzxSectionScope

/**
 * 【职责】人偶管理页：列表 + 启停 + 编辑入口 + 更多操作 + 列数。
 *
 * 【为什么用 PageStack】本页与编辑页是多层页面的前两层；统一走全 App 页面栈，
 *   返回键 / 退场动画 / 层数都不再各页各管一段。
 *
 * ## 【QZX 迁移】整页从旧 UI 系统迁到新声明式系统 `ui.qzx`
 *
 * | 旧 | 新 |
 * |---|---|
 * | `DhKit.Page(scrollable = false, adaptiveTitle = true, externalGridScroll = …)` | [QzxPage] + `scroll = QzxPageScroll.Grid` |
 * | 自持 `rememberLazyGridState()` 再手传给标题栏 | 由 [QzxPage] 创建**唯一一份**，标题栏与网格从 [LocalQzxScrollStates] 取同一个 |
 * | `Modifier.background(c.card) + clip + padding` 手拼的「格子」 | [QzxSurface]（卡底 + 边缘光 + 按压反馈由系统给） |
 * | `DhForm.Alert` / `DhForm.Note` / `DhForm.Input` / `DhForm.PrimaryChip` | [QzxDialog]（纯文字确认）/ [QzxList] 的 `note` / `input` / `buttons` |
 * | `DhKit.ActionRow` / `DhKit.SliderRow` | [QzxList] 的 `item(...).arrow()` / `slider(...)` |
 * | `ModalBottomSheet` =2（添加面板 / 更多面板） | [DollPanel] + [QzxList] 的 section（行高 / 内边距 / 分隔线归系统） |
 * | 自绘三点浮层（主菜单 + 列数二级菜单） | 「更多」→ 面板里的「列数」行 + 三枚选择 chip |
 *
 * ## 逐项对照：旧入口一个不少
 *
 * | 操作 | 旧实现 | 新实现 |
 * |---|---|---|
 * | 添加人偶 | 顶栏图标按钮 → 导入助手 | **同**（导航调用一字未改） |
 * | 列数 | 浮层「列数」→ 二级菜单里三选一 | 「更多」面板 →「列数」行 → 三枚 chip（+「返回」回上一级） |
 * | 启用 / 停止 · 编辑 · 更多操作 | 卡片底部三枚图标按钮 | **同**（三个等宽槽位，无障碍名逐字保留） |
 * | 命名 / 改封面 / 换模型 / 模型 / 复制 / 删除 / 恢复 | 底部面板里的图标网格 | 面板里的 7 个列表行（删除 / 恢复仍按固定身份二选一） |
 * | 封面来源（动作库 / 自己的图） | `DhForm.Alert` + 两颗 ActionRow | 面板里两行 item |
 * | 挑帧（先挑动作、再挑帧） | 两级 `DhForm.Alert` | 两级面板；帧条走 `custom { }` 逃生口（见下） |
 * | 恢复默认配置 | `DhForm.Alert` | [QzxDialog]（`confirmDestructive = true`：不可撤销的复位） |
 * | 大小与位置 | `DhForm.Alert` + 3 个 `DhKit.SliderRow` + `PrimaryChip` | [DollPanel] + `slider` ×3 + `buttons`（签名不变，两个调用方零改动） |
 *
 * ## 本轮删掉的唯一一段（且**不可达**，不影响任何入口）
 *
 * 旧的「添加面板」`AddSheet`（模板 / 导入 两颗按钮）连同只被它引用的
 * `PickFileActivity` 监听器一起删除。判据是代码本身：自 v0.1.5·R-062 把顶栏加号改成
 * 直达「导入助手」之后，该文件里**再也没有任何一处把 `sheet` 置 true**（只有 `sheet = false`），
 * 也就是说这个面板与它那两颗按钮**从来没有被用户看到过**。它承诺的两条路都另有入口：
 * 空白模板在导入助手选择页底部，本地导入在助手第一步。
 *
 * ## 一个真实的能力缺口（已在交付里报告，未用旧系统糊过去）
 *
 * 迁移开工时 `QzxDialog` 只收 `message: String`（它刻意不做自定义布局），而本页有五处需要
 * 「弹窗里放行与控件」。这里用一个 5 行的 [DollPanel] 壳补上：它只负责
 * **模态 + 点外部 / 返回键关闭**（平台 [Dialog] 语义），内容一律走 `QzxList` ——
 * 所以行高、内边距、分隔线、按钮对齐仍然全部归系统，页面里没有任何排版字面量。
 *
 * 【后续可整段删掉】`ui/qzx/QzxDialog.kt` 此刻正被并行改动、已经出现了
 *   `content: (@Composable () -> Unit)?` 与 `dismissAction` 两个参数（**未提交**）。
 *   本页没有依赖它：同一批里 `QzxList` 的新行型改动先前出现过又消失过，依赖未落稳的
 *   qzx 接口会让本页随时编译不过。等那边落定后，把 [DollPanel] 的调用点换成
 *   `QzxDialog(visible = true, title = …, content = { QzxList { … } })` 即可 ——
 *   注意届时 section 要加 `flat = true`（弹窗自己已经是卡片，否则卡片套卡片）。
 */
object DollListScreen {
    const val KEY = "doll:list"

    /** 卡片宽高比：**竖矩形 3:4**（列数决定宽度，高度随之定下来，任何列数下都是竖的）。 */
    private const val CARD_ASPECT = 0.75f

    @JvmStatic
    fun open(act: MainActivity) {
        PageStack.push(act, KEY) { buildPage(act) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    @JvmStatic
    fun buildPage(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @Composable
    private fun Content(act: MainActivity) {
        // 【为什么按 rev 重算】本页 View 常驻页面栈，子页（编辑 / 添加 / 删除）完成后只是把
        //   子页摘掉，本层的组合不会重建 —— 若把列表存在 `remember` 里就永远停在首次快照上
        //   （改名后返回列表仍显示旧名）。这里读全局变更信号，任一写操作后自动重算。
        //   【为什么不用 List 状态再存一份】单一真源保持在 DollStore，这里只派生。
        val rev = DollState.rev
        val dolls = remember(rev) { DollStore.all(act) }
        var moreId by remember { mutableStateOf<String?>(null) }
        // 【r13·需求B】命名 / 改封面的状态。
        var renameId by remember { mutableStateOf<String?>(null) }
        var renameDraft by remember { mutableStateOf("") }
        var coverId by remember { mutableStateOf<String?>(null) }
        // true = 正弹「封面从哪来」的选择面板。
        var coverAsk by remember { mutableStateOf(false) }
        // true = 正弹「从动作库挑一帧」的挑帧面板。
        var coverFromAnim by remember { mutableStateOf(false) }
        // 【本轮·新增】「恢复默认配置」确认窗。
        var restoreId by remember { mutableStateOf<String?>(null) }
        // 【r12】「更多」浮层：-1 = 关闭；0 = 主菜单；1 = 列数二级。
        var menu by remember { mutableStateOf(-1) }
        // 列数全局记住（PetPrefs.dollCols），改完 bump 触发重组重排。
        var cols by remember(rev) { mutableStateOf(PetPrefs.dollCols(act)) }

        /**
         * 切到某一列数：落盘 → 把每只人偶的封面换成该列数对应的那一张 → 刷新 → 关面板。
         *
         * 【为什么要换封面】用户裁封面时按三种比例各裁了一张（见 [CoverCropActivity]），
         *   这里不换的话，三列排布下会拿着 1 列的竖图去凑 —— 白裁了。
         *   没裁过的（老数据 / 只设过一次封面）返回 false，保留当前封面即可。
         */
        fun pickCols(n: Int) {
            PetPrefs.setDollCols(act, n)
            var changed = false
            val all = DollStore.all(act)
            for (d in all) {
                if (DollStore.applyCoverForCols(act, d.id, n)) {
                    changed = true
                }
            }
            if (changed) {
                DollState.bump()
            }
            cols = n
            menu = -1
        }

        QzxPage(
            title = "人偶",
            onBack = { PageStack.popIfTop(act, KEY) },
            // 【Grid 模式】网格自己滚，QzxPage **不包裹**任何滚动容器（双层滚动在结构上不可能发生）。
            scroll = QzxPageScroll.Grid,
            // 【返回键】新系统不持有旧 drawable，图标必须由调用方给；不传就不画返回键。
            backIcon = painterResource(Icons.IC_ARROW_LEFT),
            // 【有背景图时栏底恒 88%】旧 `DhKit.Page(adaptiveTitle = true)` 内部也是读这个判断，
            //   这里只是把它显式交进新系统（新系统不认识"背景图"这个概念）。
            hasPhotoBackdrop = DhKit.hasPhotoBackdrop(),
            actions = {
                // 【r15·图标+文字】顶栏空间宽裕 → 加文字，避免「这个加号是加什么」的猜测。
                // 【为什么还是 DhKit.IconButton】新系统**没有公开的图标按钮**（QzxButton 只收文字），
                //   与本批已迁移的 MyProfilePage 顶栏同一做法；语义名走 label，读屏念得到。
                DhKit.IconButton(Icons.IC_PLUS, Design.SZ_MD, QzxColors.title, label = "添加人偶") {
                    // 【v0.1.5·R-062】加号直接进「添加角色」导入助手（第 1 屏标题就是它）。
                    //   【为什么不再弹中间那层面板】R-040 / R-056 要求导入最多三步，
                    //   再叠一个「模板 / 导入」选择面板就成了四步，且用户得先猜哪个才是「加角色」。
                    ImportAssistantPage.open(act)
                }
                // 【r12】右上角三个点 → 列数面板。
                DhKit.IconButton(Icons.IC_MORE, Design.SZ_MD, QzxColors.title, label = "更多") {
                    menu = if (menu == 0) -1 else 0
                }
            }
        ) {
            // 浮层（面板）与网格同一层：网格在下、面板以 Dialog 抬起（独立窗口，不参与此处布局）。
            Box(modifier = Modifier.fillMaxSize()) {
                // 【与标题栏同一份 state】由 QzxPage 创建、经 CompositionLocal 分发；
                //   取不到就是页面 scroll 参数写错了，requireGrid 会给出可定位的错误。
                val grid = QzxPageScrollStates.requireGrid(LocalQzxScrollStates.current)
                // 【P2-a·紧凑高度】横屏 / 小屏（窗口高 < 480dp）时只收紧**纵向**行距
                //   （cardGap 17 → sm 8），横向列距保持 cardGap —— 横屏宽度充足，压缩列距
                //   反而会让卡片更挤；真正受限的是垂直方向「一屏能放几行卡片」。
                val compact = LocalCompactHeight
                LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Fixed(cols),
                    // 【不挂 qzxBounce】旧实现（`scrollable = false`）本来就没有回弹；
                    //   迁移只换骨架、不改手感 —— 要统一加回弹应当整批页面一起加。
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(QzxTokens.Space.cardGap),
                    verticalArrangement = Arrangement.spacedBy(
                        if (compact) QzxTokens.Space.sm else QzxTokens.Space.cardGap
                    )
                ) {
                    items(dolls, key = { it.id }, contentType = { "doll" }) { doll ->
                        /* 【v0.2.4·条目动画】新增 / 删除人偶时平滑让位，避免整屏重排。 */
                        Box(Modifier.animateItem()) {
                            DollCard(
                                act = act,
                                doll = doll,
                                cols = cols,
                                onEdit = {
                                    DollEditScreen.open(act, doll.id)
                                    menu = -1
                                },
                                onMore = { moreId = doll.id }
                            )
                        }
                    }
                }
            }

            // ---------- 「更多」：列数 ----------
            // 【r12】旧版是自绘三点浮层（主菜单 + 向左展开的二级卡片），因为本工程刻意不建
            //   MaterialTheme，DropdownMenu 会落到 M3 浅色默认值上。迁到新系统后这件事
            //   由 `QzxList` 的行型表达：一个「列数」行（右侧显示当前值）+ 三枚 chip，
            //   当前值、可选项与切换逻辑全部原样保留。
            if (menu >= 0) {
                DollPanel(onDismiss = { menu = -1 }) {
                    QzxList(modifier = Modifier.fillMaxWidth()) {
                        section(id = "cols", title = "更多") {
                            if (menu == 1) {
                                // 二级：三选一。选中项由 chips 的高亮表达（= 当前列数）。
                                chips(
                                    title = "列数",
                                    options = PetPrefs.DOLL_COLS.map {
                                        QzxChip(label = colsName(it), id = it.toString())
                                    },
                                    selected = setOf(cols.toString()),
                                    // 【点当前项也当作"选中"】旧的二级菜单里点任意一项（含当前列数）
                                    //   都会落盘并关面板；chip 的 onToggle 对已选项给 on=false，
                                    //   这里忽略 on，一律按"选中它"处理 —— 手感与旧版一致。
                                    onToggle = { id, _ -> pickCols(id.toInt()) }
                                )
                                // 旧二级菜单里「列数」那一行点回主菜单（onBack）；这里显式给一行。
                                item("返回", iconRes = Icons.IC_ARROW_LEFT)
                                    .onClick { menu = 0 }
                            } else {
                                item(
                                    "列数",
                                    value = colsName(cols),
                                    iconRes = Icons.IC_LAYOUT_TEMPLATE
                                ).arrow()
                                    .onClick { menu = 1 }
                            }
                        }
                    }
                }
            }

            // ---------- 卡片「更多」面板 ----------
            val more = DollStore.byId(act, moreId)
            if (more != null) {
                DollPanel(onDismiss = { moreId = null }) {
                    QzxList(modifier = Modifier.fillMaxWidth()) {
                        section(
                            id = "more",
                            title = more.name,
                            titleIconRes = Icons.IC_MORE
                        ) {
                            // 命名：打开命名面板（保留 moreId，面板关掉才清）。
                            item("命名", iconRes = Icons.IC_SIGNATURE)
                                .arrow()
                                .onClick {
                                    renameId = more.id
                                    renameDraft = more.name
                                    moreId = null
                                }
                            // 改封面：先弹「从哪来」的选择面板。
                            item("改封面", iconRes = Icons.IC_IMAGE_PLUS)
                                .arrow()
                                .onClick {
                                    coverId = more.id
                                    coverAsk = true
                                    moreId = null
                                }
                            // 【v0.1.5·19.2.3】在当前角色内换模型。先关面板再进页面，
                            //   避免回退后底部面板还盖在列表上。
                            item("换模型", iconRes = Icons.IC_REFRESH)
                                .arrow()
                                .onClick {
                                    val id = more.id
                                    moreId = null
                                    ReplaceModelPage.open(act, id)
                                }
                            // 【v0.1.5·8.9】模型管理页：一屏看全四态状态 + 名字 / 大小 / 位置，
                            //   两个动作（更换模型 / 调整位置）都在那一页里，这里只负责导航。
                            item("模型", iconRes = Icons.IC_IMAGE)
                                .arrow()
                                .onClick {
                                    val id = more.id
                                    moreId = null
                                    ModelManagePage.open(act, id)
                                }
                            item("复制", iconRes = Icons.IC_COPY)
                                .onClick {
                                    DollStore.duplicate(act, more.id)
                                    moreId = null
                                    DollState.bump()
                                }
                            // 内置默认配置不可删除：不渲染删除键。
                            // 【r21·判据加固】从 `builtin` 标记改为**认固定身份** `id == DEFAULT_ID`
                            //   （`DollStore.read()` 里有一条 `list[0].copy(builtin = true)` 的兜底分支，
                            //   靠标记会把自建人偶误判成内置）。详见 [isFactoryDoll]。
                            if (!isFactoryDoll(more)) {
                                item("删除", iconRes = Icons.IC_TRASH)
                                    .danger()
                                    .onClick {
                                        DollStore.remove(act, more.id)
                                        moreId = null
                                        DollState.bump()
                                        HomeUi.notifyPetReload(act)
                                    }
                            }
                            // 【只在默认配置上出现】「恢复默认」的语义是「回到下载软件时自带的样子」，
                            //   这个概念对**自建人偶**不成立（出厂状态就是用户刚建出来的空样子）。
                            // 【r21·文案缩短】「恢复」二字在本面板里语义完整（它只出现在默认配置上）。
                            // 【v0.2.8】原「导出」入口已删：它产出的目录全工程零读取方（单向死路）。
                            if (isFactoryDoll(more)) {
                                item("恢复", iconRes = Icons.IC_ROTATE_CCW)
                                    .onClick {
                                        restoreId = more.id
                                        moreId = null
                                    }
                            }
                        }
                    }
                }
            }

            // ---------- 命名 ----------
            val renaming = DollStore.byId(act, renameId)
            if (renaming != null) {
                DollPanel(onDismiss = { renameId = null }) {
                    QzxList(modifier = Modifier.fillMaxWidth()) {
                        section(id = "rename", title = "命名") {
                            input(
                                "名字",
                                renameDraft,
                                iconRes = Icons.IC_SIGNATURE
                            ).hint("给这只人偶起个名字")
                                .onValueChange { renameDraft = it }
                            buttons(
                                QzxButtonSpec("保存") {
                                    val name = renameDraft.trim()
                                    if (name.isNotEmpty()) {
                                        DollStore.update(act, renaming.copy(name = name))
                                        DollState.bump()
                                        HomeUi.notifyPetReload(act)
                                    }
                                    renameId = null
                                },
                                QzxButtonSpec("取消", QzxButtonStyle.Secondary) {
                                    renameId = null
                                }
                            )
                        }
                    }
                }
            }

            // ---------- 改封面：来源选择 ----------
            if (coverAsk && coverId != null) {
                DollPanel(onDismiss = { coverAsk = false; coverId = null }) {
                    QzxList(modifier = Modifier.fillMaxWidth()) {
                        section(id = "cover", title = "改封面") {
                            note("封面只影响列表里这张卡片，不影响桌面上的人偶长相。")
                            // 二选一：动作库的某一帧 / 自己的图片。
                            item("从动作库选一帧", iconRes = Icons.IC_LIBRARY)
                                .arrow()
                                .onClick {
                                    coverAsk = false
                                    coverFromAnim = true
                                }
                            // 【r13·需求E】改走**封面裁剪页**，不再直接把原图当封面。
                            //   需求原文：「选自己的图片 请定位到相册 并且得有编辑图片功能……
                            //   比例得重新调整 符合那个比例 同时 得选择三次比例」。
                            //   旧路径（PickFileActivity → setCoverFromFile）会把任意比例的原图
                            //   直接存下来，卡片里靠 ContentScale.Fit 硬缩 —— 留边或裁切，
                            //   正是用户要修的问题。现在由 CoverCropActivity 按三种列数各裁一张。
                            item("用自己的图片", iconRes = Icons.IC_IMAGE_PLUS)
                                .arrow()
                                .onClick {
                                    coverAsk = false
                                    val id = coverId
                                    coverId = null
                                    if (id != null) {
                                        CoverCropActivity.open(act, id)
                                    }
                                }
                            buttons(
                                QzxButtonSpec("取消", QzxButtonStyle.Secondary) {
                                    coverAsk = false
                                    coverId = null
                                }
                            )
                        }
                    }
                }
            }

            // ---------- 改封面：从动作库挑帧 ----------
            if (coverFromAnim && coverId != null) {
                CoverPickPanel(
                    act = act,
                    dollId = coverId!!,
                    onDismiss = {
                        coverFromAnim = false
                        coverId = null
                    },
                    onDone = {
                        coverFromAnim = false
                        coverId = null
                        DollState.bump()
                    }
                )
            }

            // ---------- 恢复默认配置 ----------
            // 【用户定案】「这个恢复的功能就是恢复默认配置的备份样子，
            //   也就是下载软件的时候自带的样子」。
            //   只重置**这只人偶**的动作库与个性化项，用户自己导入的动作 / 形象图保留。
            val restoring = restoreId
            if (restoring != null) {
                QzxDialog(
                    visible = true,
                    title = "恢复默认配置",
                    // 旧文案里的 `**` 是写给人看的强调记号（旧 `DhForm.Alert` 也原样当正文画出来，
                    // 屏幕上就是一对星号）。新系统只收纯文本，这里去掉记号、字句一字未改。
                    message = "把这只人偶恢复成刚装好软件时的样子：\n\n" +
                        "· 动作库回到出厂状态（常驻 / 贴边 / 拖拽三格 + 全部内置动作）\n" +
                        "· 大小与位置回到自动适配\n" +
                        "· 手势、暂离、性格、聊天背景等个性化设置复位\n\n" +
                        "你自己导入的动作和换过的形象图会保留，不会被清掉。",
                    confirmText = "恢复",
                    dismissText = "取消",
                    // 不可撤销的复位：确认键走危险色，且不默认聚焦（防误触）。
                    confirmDestructive = true,
                    onConfirm = {
                        val n = DollAnim.restoreBuiltin(act, restoring)
                        DollProfile.resetToFactory(act, restoring)
                        DollState.bump()
                        HomeUi.notifyPetReload(act)
                        restoreId = null
                        HomeUi.showNotice(
                            act, "已恢复默认配置",
                            if (n > 0) "补回了 " + n + " 个内置动作；大小与位置已回到自动适配。"
                            else "内置动作本来就在；大小与位置已回到自动适配。"
                        )
                    },
                    onDismiss = { restoreId = null }
                )
            }
        }
    }

    /**
     * 人偶卡片（r12 改：竖矩形 + 等比缩放）。
     *
     * 【为什么是竖矩形】需求定案：「把这个卡片改成竖着的矩形 不要横着的矩形」。
     *   旧卡片是通栏宽 + 固定 260dp 高（宽 > 高，横着）；现在改成固定宽高比 3:4，
     *   列数决定宽度（一列 = 通栏 / 两列 = 半宽 / 三列 = 三分之一），高度随之定下来。
     *
     * 【为什么卡内留白走 Spacer + Arrangement 而不是 padding】新系统的卡片表面
     *   [QzxSurface] **不提供内容内边距参数**（卡内排版属于调用方的组合），而本页的
     *   迁移约定是「页面里不出现 padding / height 字面量」——内边距一律由令牌给出，
     *   这里用 `Arrangement.spacedBy`（行间）+ 首尾 `Spacer`（上下沿）表达，
     *   数值仍然只来自 [QzxTokens]，没有一处 magick number。
     *
     * @param cols 当前列数：用来等比缩放封面解码尺寸与名字字号（定案 Q24「等比缩放就行」）。
     */
    @Composable
    private fun DollCard(
        act: MainActivity,
        doll: DollStore.Doll,
        cols: Int,
        onEdit: () -> Unit,
        onMore: () -> Unit
    ) {
        // 【r24·修内存】原来不论列数都按 1024px 解码：3 列时卡片实际只有 ~350px 宽，
        //   多出来的像素全部浪费成内存（4MB/张 vs 0.8MB/张）。
        //   按列数给目标边长：1 列铺满屏宽需要 1024，2 列 720，3 列 512。
        //   2 的幂附近即可 —— ImageDecode 内部会把 inSampleSize 向上取整。
        val reqPx = when (cols) {
            1 -> 1024
            2 -> 720
            else -> 512
        }
        val bitmap = remember(doll.id, doll.cover, doll.sprite, reqPx) {
            DollStore.coverOf(act, doll, reqPx)
        }
        // 【等比缩放因子】一列 = 1.0；两列 = 0.72；三列 = 0.55。
        //   【r13 用户定案】只缩**文字与内边距**，按钮**不再跟着缩**：
        //   「这 三个按钮本来就不大 也没必要缩小了 …… 大小就不用变了」。
        val k = when (cols) {
            2 -> 0.72f
            3 -> 0.55f
            else -> 1.0f
        }
        // 按钮图标固定这一档，不乘 k。
        val btnDp = Design.SZ_MD

        QzxSurface(modifier = Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(QzxTokens.Space.sm)
            ) {
                // 上沿 / 下沿留白：两个 `Spacer` 而不是 padding —— 见函数头「卡内留白」那段。
                Spacer(Modifier.size(QzxTokens.Space.xs))
                // 名字：居中单行（卡片窄，等宽居中比左对齐更耐看；仍按 k 等比缩小）。
                Text(
                    text = doll.name,
                    color = QzxColors.title,
                    fontSize = (QzxTokens.Type.ROW_TITLE_SP * k).sp,
                    fontFamily = QzxFonts.bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 封面：铺满整块区域（比例由用户裁剪时定，这里不再二次缩放）。
                //
                // 【r13 修 bug·封面没铺满】旧实现是 `ContentScale.Fit` + 一圈 6dp 内边距：
                //   ① `Fit` 是「完整装进框里」——只要图片比例与框不完全一致就**必然留边**；
                //   ② 额外的内边距又让图片再缩进一圈。而用户在裁剪页是按「这个框的比例」裁的
                //   （见 CoverCropActivity），裁出来的图与框**本来就该严丝合缝**，再 Fit 一次纯属画蛇添足。
                //   现在改用 `Crop` + 零内边距：比例一致时零留边、完全铺满。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(QzxTokens.Radius.inner))
                        .background(QzxColors.soft)
                        // 【r13·实测封面框比例】布局完成后把这块区域的真实宽高比写回偏好，
                        //   供「改封面」裁剪页按它定裁剪框 —— 这样裁出来的图与卡片严丝合缝。
                        //   【为什么在这里量】这里就是封面的**真实落点**：列数、字号缩放、
                        //   系统显示大小的影响全都体现在这次测量里，比任何静态推导都准。
                        //   【只写一次】比例只取决于列数，所以比对一下不同才写，避免每帧都碰 SharedPreferences。
                        .onSizeChanged { sz ->
                            if (sz.width > 0 && sz.height > 0) {
                                val r = sz.width.toFloat() / sz.height.toFloat()
                                if (Math.abs(r - PetPrefs.coverRatioMeasured(act, cols)) > 0.005f) {
                                    PetPrefs.setCoverRatioMeasured(act, cols, r)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            // 【铺满】不设内边距 —— 图片直接顶到圆角边上，圆角由外层 clip 提供。
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // 【r13·需求C】封面为空（新建模板就是这种状态）：给一个淡淡的占位，
                        //   让用户一眼看出「这里可以放封面」，而不是一片空白让人以为坏了。
                        DhKit.Icon(Icons.IC_IMAGE_PLUS, Design.SZ_LG, QzxColors.line)
                    }
                }
                // 使用 / 编辑 / 更多（图标按钮，不配文字说明）
                //
                // 【r13 用户定案·布局】「就是一个在中间 另外两个在两（边）；缩小之后
                //   就是他们三个的间距变小了 但是不能重叠 大小就不用变了」。
                //   ⇒ 三个**等宽槽位**（weight 1f）横向排开，按钮在各自槽位里居中：
                //     列数变小 → 槽位同步变窄 → 间距自动变小；
                //     而按钮本身是固定尺寸、各占一个槽位，物理上不可能重叠。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        // 【r16·修·窄槽不加文字】列数 3 时每槽仅约 36dp，而「图标+文字」需要约 70dp
                        //   —— 加了文字会挤出卡片或被裁。此处保持纯图标。
                        // 【无障碍·P0】纯图标 + 无文字 = 读屏只会念「按钮」，故补 a11yName；
                        //   这一颗的名字**随状态变**：当前启用 → 念「停用 X」。
                        DhKit.IconButton(
                            iconRes = if (doll.enabled) Icons.IC_SQUARE else Icons.IC_PLAY,
                            sizeDp = btnDp,
                            color = if (doll.enabled) QzxColors.accent else QzxColors.title,
                            a11yName = if (doll.enabled) "停用 " + doll.name else "启用 " + doll.name
                        ) {
                            DollStore.toggleEnabled(act, doll.id)
                            DollState.bump()
                            HomeUi.notifyPetReload(act)
                        }
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        // 【无障碍·P0】纯图标 + 无文字，补 a11yName。
                        DhKit.IconButton(
                            iconRes = Icons.IC_EDIT,
                            sizeDp = btnDp,
                            color = QzxColors.title,
                            a11yName = "编辑 " + doll.name,
                            onClick = onEdit
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        // 【无障碍·P0】它打开的是「命名 / 封面 / 换模型 / 复制 / 删除 / 恢复」
                        //   那一整套操作，名字取「更多操作」而不是「更多」——后者读屏听不出含义。
                        DhKit.IconButton(
                            iconRes = Icons.IC_MORE,
                            sizeDp = btnDp,
                            color = QzxColors.title,
                            a11yName = "更多操作 " + doll.name,
                            onClick = onMore
                        )
                    }
                }
                Spacer(Modifier.size(QzxTokens.Space.xs))
            }
        }
    }

    /**
     * 【r13·需求B】「从动作库挑一帧」当封面：两级选择（先挑动作，再挑帧）。
     *
     * 【为什么做成两级】需求原文：「点击之后会有个弹窗询问你 是选择人偶动作库的某个动作的
     *   某一帧还是自己的图片 封面」。一屏里塞不下「动作 × 帧」的全部组合，
     *   分两级既符合描述，也不会让面板变成一张长列表。
     *
     * 【帧从哪来】[DollAnim.all] 里的每个动作都有自己的帧序列；用户导入的动作也有。
     *   动作还没烘过帧时（frames 为空）该动作直接跳过 —— 点进去也没有帧可选。
     */
    @Composable
    private fun CoverPickPanel(
        act: MainActivity,
        dollId: String,
        onDismiss: () -> Unit,
        onDone: () -> Unit
    ) {
        // -1 = 还在挑动作；>=0 = 已选定某个动作，正在挑帧。
        var picked by remember(dollId) { mutableStateOf(-1) }
        val actions = remember(dollId) {
            DollAnim.all(act, dollId).filter { it.frames.isNotEmpty() }
        }
        val idx = picked

        if (idx < 0) {
            DollPanel(onDismiss = onDismiss) {
                QzxList(modifier = Modifier.fillMaxWidth()) {
                    section(id = "pickAction", title = "从动作库选一帧") {
                        if (actions.isEmpty()) {
                            note("这只人偶还没有可用的动作帧。先在「动作」页里烘一次或导入一组图。")
                        } else {
                            note("先选一个动作，再从它的帧里挑一张。")
                            for (i in actions.indices) {
                                val a = actions[i]
                                item(
                                    a.name + "（" + a.frames.size + " 帧）",
                                    iconRes = Icons.IC_BLOCKS
                                ).arrow()
                                    .onClick { picked = i }
                            }
                        }
                        buttons(
                            QzxButtonSpec("取消", QzxButtonStyle.Secondary) { onDismiss() }
                        )
                    }
                }
            }
            return
        }

        val action = actions[idx]
        DollPanel(onDismiss = onDismiss) {
            QzxList(modifier = Modifier.fillMaxWidth()) {
                section(id = "pickFrame", title = action.name + " · 选一帧") {
                    note("点任意一帧就把它设为封面。")
                    // 【唯一用到逃生口的地方】`QzxList` 没有「横向图片条」这种行型
                    //   （preview 是单张头像，item 只有图标位）。这里用 `custom { }` 承载一条
                    //   可横向滚动的帧条 —— 这是系统文档里写明的逃生口：左右内边距与分隔线
                    //   仍归系统，条内的间距 / 尺寸只取自令牌。
                    custom {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(QzxTokens.Space.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 帧数最多 120，用横向滚动展示。
                            for (i in action.frames.indices) {
                                val p = action.frames[i]
                                CoverThumb(path = p, name = "第 " + (i + 1) + " 帧，设为封面") {
                                    // 【为什么开线程】设封面要解码一张整图，主线程做会卡一下。
                                    Thread({
                                        DollStore.setCoverFromFrame(act, dollId, p)
                                        act.runOnUiThread { onDone() }
                                    }, "dh-cover-frame").start()
                                }
                            }
                        }
                    }
                    buttons(
                        QzxButtonSpec("返回", QzxButtonStyle.Secondary) { onDismiss() }
                    )
                }
            }
        }
    }

    /**
     * 挑帧用的小缩略图（只解码 1/4 尺寸，一屏几十张也不吃内存）。
     *
     * 【为什么是 [QzxSurface] 而不是自绘 Box】它本身就是新系统的「可点卡片表面」：
     *   按压时背景压一档 + 缩放到 0.96，命中区有下限 —— 旧实现要自己 clip / border /
     *   pressable 三件套，现在只剩「给尺寸 + 给无障碍名 + 给点击」。
     *
     * @param name 无障碍名。本瓦片内只有一张图（`contentDescription = null`）没有任何文字，
     *   不给名字读屏只念「按钮」。这里由调用方按序号传入（「第 N 帧」），
     *   否则一屏几十个瓦片全叫同一个名字，读屏用户无法知道选的是哪一帧。
     */
    @Composable
    private fun CoverThumb(path: String, name: String, onClick: () -> Unit) {
        var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(path) {
            bmp = withContext(Dispatchers.IO) {
                try {
                    val opt = BitmapFactory.Options()
                    opt.inSampleSize = 4
                    BitmapFactory.decodeFile(path, opt)?.asImageBitmap()
                } catch (t: Throwable) {
                    null
                }
            }
        }
        QzxSurface(
            modifier = Modifier
                .size(DhDesign.Size.avatarXl)
                // 名字挂在可点区域上；里面的 Image 继续是 null（否则同一张图会被念两遍）。
                .semantics { contentDescription = name },
            corner = QzxTokens.Radius.inner,
            onClick = onClick
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) {
                    Image(
                        bitmap = b,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    DhKit.Icon(Icons.IC_IMAGE, Design.SZ_SM, QzxColors.sub)
                }
            }
        }
    }

    /**
     * 【本轮·新增】「大小」弹窗：人偶级显示规格（基础大小 + 水平 / 垂直位置）。
     *
     * ## 为什么放在人偶管理页
     *
     * 需求书第七节：「在现有人偶编辑器中，以尽量少改动现有布局的方式增加尺寸设置」，
     * 并列出「自动适配 / 人偶大小调整 / 水平与垂直位置 / 恢复默认设置」。
     * 人偶管理页是人偶级参数的天然归属地 —— 缩放、封面、命名都在这儿。
     *
     * ## 为什么只有三个滑条
     *
     * 「自动适配」不是一个开关，而是**默认值本身**：大小 100% + 偏移 0 就等于
     * 「按标准化画布算出的原生大小、居中」。多一个开关会出现「自动适配开、但大小滑条停在 180%」
     * 这种自相矛盾的状态。需要自动适配就点「恢复自动适配」，它把三个值一起复位。
     *
     * ## 【QZX 迁移】调用点零改动
     * 两个调用方（`DollLookTab` / `ModelManagePage`）都是 `if (showSize) { SizeDialog(...) }`，
     * 本函数自己负责「弹窗」这件事 —— 迁到 [DollPanel] 后这个契约不变：
     * 依旧是「调它 = 弹出一个模态窗」。
     *
     * 【为什么留在本文件而不是搬家】它依赖 [percentText] 与 `DollSizeSpec` 的一整套换算，
     *   就地开放可见性（internal）比复制一份到 DollLookTab 更安全 —— 不会出现两份实现漂移。
     */
    @Composable
    internal fun SizeDialog(
        act: MainActivity,
        dollId: String,
        onDismiss: () -> Unit
    ) {
        // 【草稿态】滑条拖动是连续的，每动一下就落盘会产生大量 SharedPreferences 写入；
        //   这里先在内存里跟手，点「保存」才落盘（与编辑页的草稿层同一口径）。
        var specDraft by remember(dollId) { mutableStateOf(DollSizeSpec.spec(act, dollId)) }
        DollPanel(onDismiss = onDismiss) {
            QzxList(modifier = Modifier.fillMaxWidth()) {
                section(id = "size", title = "大小与位置") {
                    note("这里调的是这只人偶本身的大小，与动作无关 —— 换动作不会忽大忽小。")
                    // 【控件选型】全部走 `QzxList` 的 `slider` 行型：它桥接的是同一颗
                    //   View 版 `UiKit.Slider`（胶囊轨道 + 刻度 + 终点标记）。
                    //   不用 Compose 原生 Slider —— 那会回落到 M3 默认配色，与全 App 的胶囊滑条不是一个形态。
                    // ---------- 基础大小 ----------
                    // 取值范围 0~SIZE_MAX，用「最大值 + 当前值」表达（滑条接口是 Int 量程）。
                    slider(
                        title = "人偶大小",
                        value = specDraft.size,
                        range = 0..DollSizeSpec.Spec.SIZE_MAX,
                        hint = "当前 " + specDraft.size +
                            "%（100% = 按素材里角色的实际占比算出的原生大小）"
                    ) { v, _ ->
                        specDraft = specDraft.copy(size = DollSizeSpec.Spec.clampSize(v))
                    }
                    // ---------- 水平位置 ----------
                    // 偏移是 -50% ~ +50%，滑条量程 0~100，取值时减 50 换算。
                    slider(
                        title = "水平位置",
                        value = Math.round(specDraft.ox * 100f).toInt() + 50,
                        range = 0..100,
                        hint = percentText(specDraft.ox, true)
                    ) { v, _ ->
                        specDraft = specDraft.copy(
                            ox = DollSizeSpec.Spec.clampOffset((v - 50) / 100f)
                        )
                    }
                    // ---------- 垂直位置 ----------
                    slider(
                        title = "垂直位置",
                        value = Math.round(specDraft.oy * 100f).toInt() + 50,
                        range = 0..100,
                        hint = percentText(specDraft.oy, false)
                    ) { v, _ ->
                        specDraft = specDraft.copy(
                            oy = DollSizeSpec.Spec.clampOffset((v - 50) / 100f)
                        )
                    }
                    note(
                        "「恢复自动适配」= 大小回到 100%、位置回到居中。" +
                            "100% 是按素材里角色实际占画布的比例算出来的，不是把整张图拉满。"
                    )
                    // ---------- 恢复自动适配 ----------
                    // 【为什么不关窗】复位只是把三个值一起归位，用户通常还要接着微调 / 再保存。
                    buttons(
                        QzxButtonSpec("恢复自动适配", QzxButtonStyle.Secondary) {
                            specDraft = DollSizeSpec.Spec.DEFAULT
                            DollSizeSpec.reset(act, dollId)
                            HomeUi.notifyPetReload(act)
                        }
                    )
                    buttons(
                        QzxButtonSpec("保存") {
                            DollSizeSpec.setSpec(act, dollId, specDraft)
                            HomeUi.notifyPetReload(act)
                            onDismiss()
                        },
                        QzxButtonSpec("取消", QzxButtonStyle.Secondary) { onDismiss() }
                    )
                    note("提示：调完若觉得桌面没变，先退出编辑页再看 —— 编辑期间桌面只显示预览。")
                }
            }
        }
    }

    /**
     * 把 [-0.5, 0.5] 的偏移比例显示成「右 12% / 居中」这类人话。
     *
     * @param horizontal true = 水平方向（左右），false = 垂直方向（上下）
     */
    private fun percentText(v: Float, horizontal: Boolean): String {
        val p = Math.round(v * 100f)
        return when {
            p > 0 -> (if (horizontal) "偏右 " else "偏下 ") + p + "%"
            p < 0 -> (if (horizontal) "偏左 " else "偏上 ") + (-p) + "%"
            else -> "居中"
        }
    }

    /**
     * 带自定义内容的弹窗壳。
     *
     * 【为什么本文件自己写这 4 行】新系统的 [QzxDialog] 只收 `message: String`
     *   —— 它刻意不做「自定义布局」（见 QzxDialog 文件头）。而本页的「更多 / 命名 /
     *   改封面 / 挑帧 / 大小与位置」都必须在弹窗里放**行与控件**。
     *
     * 【它只负责三件事】模态、点外部 / 返回键关闭（平台 [Dialog] 自带这两条语义）、
     *   把内容托到最上层。**不画卡底、不管排版** —— 内容一律是 `QzxList`，
     *   卡片底 / 行高 / 内边距 / 分隔线全部由系统的 section 给。
     *
     * 【为什么不用 BackHandler】本工程的 Activity 继承 `android.app.Activity`、没有
     *   `OnBackPressedDispatcherOwner`，自绘遮罩要想接返回键只能靠 `BackHandler`，
     *   那会在真机上直接抛异常（QzxDialog 的注释里记着这条）。
     */
    @Composable
    private fun DollPanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            content()
        }
    }

    /**
     * 【r21·单一判据】这只人偶是不是「本地自带的那只默认配置」。
     *
     * 【为什么不用 `doll.builtin`】那个标记是**可变字段**，会被存档读写与兜底逻辑改写
     *   （`DollStore.read()` 里有 `list[0].copy(builtin = true)` 的分支），
     *   靠它判断会出现「自建人偶也被当成内置」或「内置人偶丢了标记」两种误判。
     *   固定身份 `id == DEFAULT_ID` 才是不可变的真源。
     *
     * 【影响面】只决定菜单里出现「删除」还是「恢复默认」，不影响任何数据操作本身
     *   （删除与恢复的实现在 `DollStore` 内部已有各自的 id 校验）。
     */
    private fun isFactoryDoll(doll: DollStore.Doll): Boolean {
        return doll.id == DollStore.DEFAULT_ID
    }

    /** 列数的展示名。 */
    private fun colsName(n: Int): String {
        return when (n) {
            1 -> "一列"
            2 -> "两列"
            else -> "三列"
        }
    }
}
