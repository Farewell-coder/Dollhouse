package com.dollhouse.app.ui.doll

import android.graphics.BitmapFactory
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.CoverCropActivity
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollSizeSpec
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.theme.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 【职责】人偶管理页：列表 + 启停 + 编辑入口 + 更多操作 + 添加面板。
 *
 * 【为什么用 PageStack】本页与编辑页是多层页面的前两层；统一走全 App 页面栈，
 *   返回键 / 退场动画 / 层数都不再各页各管一段。
 */
object DollListScreen {
    const val KEY = "doll:list"

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
        var sheet by remember { mutableStateOf(false) }
        var moreId by remember { mutableStateOf<String?>(null) }
        // 【r13·需求B】命名 / 改封面的弹窗状态。
        var renameId by remember { mutableStateOf<String?>(null) }
        var renameDraft by remember { mutableStateOf("") }
        var coverId by remember { mutableStateOf<String?>(null) }
        // true = 正弹「封面从哪来」的选择窗。
        var coverAsk by remember { mutableStateOf(false) }
        // true = 正弹「从动作库挑一帧」的挑帧窗。
        var coverFromAnim by remember { mutableStateOf(false) }
        // 【本轮·新增】尺寸设置窗（人偶级显示规格）与「恢复默认配置」确认窗。
        var restoreId by remember { mutableStateOf<String?>(null) }
        // 【r12】三点菜单：null = 关闭；0 = 主菜单；1 = 列数二级菜单。
        var menu by remember { mutableStateOf(-1) }
        // 列数全局记住（PetPrefs.dollCols），改完 bump 触发重组重排。
        var cols by remember(rev) { mutableStateOf(PetPrefs.dollCols(act)) }
        // 【本地导入·r13 改造】走「任意文件」通道，**不再**只拉相册。
        //   【为什么改】用户原话：「添加人偶那里 本地添加 你不要给我引导到相册 应该是我在 PS 里面
        //     把所有的图层画好之后在 AE 里面导出 json 文件 应该是导入这个文件」。
        //   现在拿到的可能是：单张图 / 动图 / json（帧清单或动画工程）/ 精灵图+描述 / 压缩包，
        //   统一交给 [AnimImport] 解析成一组 PNG 帧，再作为「素材帧」存进该人偶的帧目录。
        //   【仍然不用 rememberLauncherForActivityResult】宿主是原生 Activity + 自建 HostOwner，
        //   提供不了 ActivityResultRegistryOwner，会在首帧组合期直接抛异常（见原注释）。
        val importPick = object : PickFileActivity.FileListener {
            override fun onFiles(paths: List<String>, failed: Boolean) {
                if (paths.isEmpty()) {
                    if (failed) {
                        HomeUi.showNotice(act, "导入失败", "没能读取选中的文件，请重试。")
                    }
                    return
                }
                // 【为什么开线程】解析 json / 解包 / 拆动图 / 解码缩放全是重活，
                //   主线程做会卡顿乃至 ANR。后台做完再回主线程刷新列表。
                Thread({
                    // 【顺序很关键】先建人偶拿到 id（它自带一个空白动作库），
                    //   再用它的帧目录去解析成果文件，最后把第一帧设为形象图。
                    val doll = DollStore.addTemplate(act, "本地导入")
                    val dir = DollAnim.framesDir(act, doll.id, "import")
                    val r = com.dollhouse.app.data.AnimImport.import(act, paths, dir)
                    if (r.frames.isEmpty()) {
                        // 没解析出任何帧就把这只空壳撤掉，别在列表里留一个没用的条目。
                        DollStore.remove(act, doll.id)
                        act.runOnUiThread {
                            DollState.bump()
                            HomeUi.showNotice(act, "没导入成功", r.note)
                        }
                        return@Thread
                    }
                    DollStore.setSpriteFromFrame(act, doll.id, r.frames[0])
                    DollAnim.add(
                        act, doll.id, "导入的动作", r.note,
                        r.frames, r.fps, true
                    )
                    act.runOnUiThread {
                        DollState.bump()
                        HomeUi.showNotice(act, "已导入", r.note)
                    }
                }, "dh-doll-import").start()
            }
        }
        DhKit.Page(
            title = "人偶",
            onBack = { PageStack.pop(act) },
            scrollable = false,
            actions = {
                // 【r15·图标+文字】顶栏空间宽裕 → 加文字，避免「这个加号是加什么」的猜测。
                DhKit.IconButton(Icons.IC_PLUS, 20f, DhTokens.colors.title, label = "新建") { sheet = true }
                // 【r12】右上角三个点 → 弹出小卡片，第一项「列数」。
                DhKit.IconButton(Icons.IC_MORE, 20f, DhTokens.colors.title, label = "更多") {
                    menu = if (menu == 0) -1 else 0
                }
            }
        ) {
            // 【r12】按列数换排布：一列 / 两列 / 三列。
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(dolls, key = { it.id }) { doll ->
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

        // 【r12】三点菜单（右上角浮层）：主菜单 + 列数二级菜单。
        //   用 Box 绝对定位而不是 DropdownMenu —— 后者取 MaterialTheme 配色，
        //   本工程刻意不建主题（见 DhTheme 头注释），会落到浅色默认值上。
        if (menu >= 0) {
            MenuOverlay(
                cols = cols,
                level = menu,
                onPickCols = {
                    PetPrefs.setDollCols(act, it)
                    // 【r13·需求E】切列数时把封面换成该列数对应的那一张。
                    //   用户裁封面时按三种比例各裁了一张（见 CoverCropActivity），
                    //   这里不换的话，三列排布下会拿着 1 列的竖图去凑 —— 白裁了。
                    //   没裁过的（老数据 / 只设过一次封面）返回 false，保留当前封面即可。
                    var changed = false
                    val all = DollStore.all(act)
                    for (d in all) {
                        if (DollStore.applyCoverForCols(act, d.id, it)) {
                            changed = true
                        }
                    }
                    if (changed) {
                        DollState.bump()
                    }
                    cols = it
                    menu = -1
                },
                onOpenCols = { menu = 1 },
                onBack = { menu = 0 },
                onDismiss = { menu = -1 }
            )
        }
        if (sheet) {
            AddSheet(
                onDismiss = { sheet = false },
                onTemplate = {
                    DollStore.addTemplate(act)
                    sheet = false
                    DollState.bump()
                },
                onImport = {
                    sheet = false
                    // 【r13】改走「任意文件」通道（不再只拉相册）：
                    //   用户要导的是作图软件导出的成果文件（json / 动图 / 精灵图 / 压缩包）。
                    PickFileActivity.setFileListener(importPick)
                    PickFileActivity.startFiles(act, false)
                }
            )
        }
        val more = DollStore.byId(act, moreId)
        if (more != null) {
            MoreSheet(
                doll = more,
                onDismiss = { moreId = null },
                onRename = {
                    // 【r13·需求B】命名：打开改名弹窗（保留 moreId，弹窗关掉才清）。
                    renameId = more.id
                    renameDraft = more.name
                    moreId = null
                },
                onCover = {
                    // 【r13·需求B】改封面：先弹「从哪来」的选择窗。
                    coverId = more.id
                    coverAsk = true
                    moreId = null
                },
                onCopy = {
                    DollStore.duplicate(act, more.id)
                    moreId = null
                    DollState.bump()
                },
                onDelete = {
                    DollStore.remove(act, more.id)
                    moreId = null
                    DollState.bump()
                    HomeUi.notifyPetReload(act)
                },
                onExport = {
                    DollStore.export(act)
                    moreId = null
                },
                onRestore = {
                    restoreId = more.id
                    moreId = null
                }
            )
        }

        // ---------- 恢复默认配置 ----------
        // 【用户定案】「这个恢复的功能就是恢复默认配置的备份样子，
        //   也就是下载软件的时候自带的样子」。
        //   只重置**这只人偶**的动作库与个性化项，用户自己导入的动作 / 形象图保留。
        val restoring = restoreId
        if (restoring != null) {
            DhForm.Alert(
                title = "恢复默认配置",
                onDismiss = { restoreId = null },
                posText = "恢复",
                onPos = {
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
                negText = "取消"
            ) {
                Text(
                    text = "把这只人偶恢复成**刚装好软件时的样子**：\n\n" +
                        "· 动作库回到出厂状态（常驻 / 贴边 / 拖拽三格 + 全部内置动作）\n" +
                        "· 大小与位置回到自动适配\n" +
                        "· 手势、暂离、性格、聊天背景等个性化设置复位\n\n" +
                        "你**自己导入**的动作和换过的形象图会保留，不会被清掉。",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }

        // ---------- 命名弹窗 ----------
        val renaming = DollStore.byId(act, renameId)
        if (renaming != null) {
            DhForm.Alert(
                title = "命名",
                onDismiss = { renameId = null },
                posText = "保存",
                onPos = {
                    val name = renameDraft.trim()
                    if (name.isNotEmpty()) {
                        DollStore.update(act, renaming.copy(name = name))
                        DollState.bump()
                        HomeUi.notifyPetReload(act)
                    }
                    renameId = null
                },
                negText = "取消"
            ) {
                DhForm.Input(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = "名字",
                    hint = "给这只人偶起个名字"
                )
            }
        }

        // ---------- 改封面：来源选择 ----------
        if (coverAsk && coverId != null) {
            DhForm.Alert(
                title = "改封面",
                onDismiss = { coverAsk = false; coverId = null },
                negText = "取消"
            ) {
                DhForm.Note("封面只影响列表里这张卡片，不影响桌面上的人偶长相。")
                // 二选一：动作库的某一帧 / 自己的图片。
                DhKit.ActionRow(
                    label = "从动作库选一帧",
                    iconRes = Icons.IC_LIBRARY
                ) {
                    coverAsk = false
                    coverFromAnim = true
                }
                DhKit.ActionRow(
                    label = "用自己的图片",
                    iconRes = Icons.IC_IMAGE_PLUS
                ) {
                    coverAsk = false
                    val id = coverId
                    coverId = null
                    if (id != null) {
                        // 【r13·需求E】改走**封面裁剪页**，不再直接把原图当封面。
                        //   需求原文：「选自己的图片 请定位到相册 并且得有编辑图片功能……
                        //   比例得重新调整 符合那个比例 同时 得选择三次比例」。
                        //   旧路径（PickFileActivity → setCoverFromFile）会把任意比例的
                        //   原图直接存下来，卡片里靠 ContentScale.Fit 硬缩 —— 留边或裁切，
                        //   正是用户要修的问题。现在由 CoverCropActivity 按三种列数
                        //   各裁一张，比例与卡片严格吻合。
                        CoverCropActivity.open(act, id)
                    }
                }
            }
        }

        // ---------- 改封面：从动作库挑帧 ----------
        if (coverFromAnim && coverId != null) {
            CoverPickDialog(
                act = act,
                dollId = coverId!!,
                onDismiss = { coverFromAnim = false; coverId = null },
                onDone = {
                    coverFromAnim = false
                    coverId = null
                    DollState.bump()
                }
            )
        }
    }

    /**
     * 人偶卡片（r12 改：竖矩形 + 等比缩放）。
     *
     * 【为什么是竖矩形】需求定案：「把这个卡片改成竖着的矩形 不要横着的矩形」。
     *   旧卡片是通栏宽 + 固定 260dp 高（宽 > 高，横着）；现在改成固定宽高比 3:4，
     *   列数决定宽度（一列 = 通栏 / 两列 = 半宽 / 三列 = 三分之一），高度随之定下来，
     *   于是任何列数下都是一张竖矩形。
     *
     * @param cols 当前列数：用来等比缩放内边距、封面与按钮（定案 Q24「等比缩放就行」）。
     */
    @Composable
    private fun DollCard(
        act: MainActivity,
        doll: DollStore.Doll,
        cols: Int,
        onEdit: () -> Unit,
        onMore: () -> Unit
    ) {
        val c = DhTokens.colors
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
        val pad = (16 * k).dp
        // 按钮图标固定这一档，不乘 k。
        val btnDp = Design.SZ_MD

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 【3:4 竖矩形】列数越小、卡片越窄，高度按比例缩短，始终保持竖着。
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(16.dp))
                .background(c.card)
                .padding(pad)
        ) {
            // 名字
            Text(
                text = doll.name,
                color = c.title,
                fontSize = (UiKit.FS_BTN * k).sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 封面：铺满整块区域（比例由用户裁剪时定，这里不再二次缩放）。
            //
            // 【r13 修 bug·封面没铺满】原实现是 `ContentScale.Fit` + `padding(6.dp)`：
            //   ① `Fit` 是「完整装进框里」——只要图片比例与框不完全一致就**必然留边**，
            //      截图里那条明显的空隙就是它；② `padding(6.dp)` 又让图片再缩进一圈。
            //   而用户在裁剪页是按「这个框的比例」裁的（见 CoverCropActivity），
            //   裁出来的图与框**本来就该严丝合缝**，再 Fit 一次纯属画蛇添足。
            //   现在改用 `Crop` + 去掉内边距：比例一致时零留边、完全铺满；
            //   万一某张老图比例不合，`Crop` 也只会裁掉边缘而不是留白条（视觉更干净）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 10.dp * k)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.soft)
                    // 【r13·实测封面框比例】布局完成后把这块区域的真实宽高比写回偏好，
                    //   供「改封面」裁剪页按它定裁剪框 —— 这样裁出来的图与卡片严丝合缝。
                    //   【为什么在这里量】这里就是封面的**真实落点**：列数、字号缩放、
                    //   系统显示大小的影响全都体现在这次测量里，比任何静态推导都准。
                    //   【只写一次】比例只取决于列数（同一列数下所有卡片一样宽），
                    //   所以比对一下不同才写，避免每帧都碰 SharedPreferences。
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
                        // 【铺满】不设 padding —— 图片直接顶到圆角边上。
                        //   圆角由外层的 clip 提供，图片自己不需要再留白。
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // 【r13·需求C】封面为空（新建模板就是这种状态）：给一个淡淡的占位，
                    //   让用户一眼看出「这里可以放封面」，而不是一片空白让人以为坏了。
                    DhKit.Icon(Icons.IC_IMAGE_PLUS, Design.SZ_LG, c.line)
                }
            }
            // 使用 / 编辑 / 更多（图标按钮，不配文字说明）
            //
            // 【r13 用户定案·布局】「就是一个在中间 另外两个在两（边）；缩小之后
            //   就是他们三个的间距变小了 但是不能重叠 大小就不用变了」。
            //   ⇒ 三个**等宽槽位**（weight 1f）横向排开，按钮在各自槽位里居中：
            //     列数变小 → 槽位同步变窄 → 间距自动变小；
            //     而按钮本身是固定尺寸、各占一个槽位，物理上不可能重叠。
            //     比「right 对齐 + 手调间距」稳：无论卡片多窄都不需要再算 padding。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // 【r16·修·窄槽不加文字】卡片底部三枚按钮处在「等分窄槽」里：
                    //   列数 3 时每槽仅约 36dp，而「图标+文字」需要约 70dp ——
                    //   加了文字会挤出卡片或被裁。此处保持纯图标（符合 DhKit.IconButton 的
                    //   「宽裕处才加 label」约定），文字语义由卡片整体可点区域承载。
                    DhKit.IconButton(
                        iconRes = if (doll.enabled) Icons.IC_SQUARE else Icons.IC_PLAY,
                        sizeDp = btnDp,
                        color = if (doll.enabled) c.acc else c.title
                    ) {
                        DollStore.toggleEnabled(act, doll.id)
                        DollState.bump()
                        HomeUi.notifyPetReload(act)
                    }
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // 【r16·修】同上一槽：窄槽内保持纯图标。
                    DhKit.IconButton(
                        iconRes = Icons.IC_EDIT,
                        sizeDp = btnDp,
                        color = c.title,
                        onClick = onEdit
                    )
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // 【r16·修】同上：窄槽内保持纯图标。
                    DhKit.IconButton(
                        iconRes = Icons.IC_MORE,
                        sizeDp = btnDp,
                        color = c.title,
                        onClick = onMore
                    )
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
     * 人偶管理页是人偶级参数的天然归属地 —— 缩放、封面、命名都在这儿，
     * 尺寸放同一个「更多」菜单下，用户不用猜它在哪。
     *
     * ## 为什么只有三个滑条
     *
     * 「自动适配」不是一个开关，而是**默认值本身**：大小 100% + 偏移 0 就等于
     * 「按标准化画布算出的原生大小、居中」，也就是自动适配的结果。
     * 多一个开关会出现「自动适配开、但大小滑条停在 180%」这种自相矛盾的状态。
     * 需要自动适配就点「恢复自动适配」，它把三个值一起复位。
     */
    // 【本轮·从人偶管理页「更多」搬到编辑页的「外观」页签】
    //   用户定案：「还有下面那个大小按键 移动到外观里面 不要在外面」。
    //   理由站得住：大小 / 偏移是「这只人偶长什么样」的外观属性，与聊天背景、气泡样式同类，
    //   放在外观页签里才和它的语义一致；人偶管理页的「更多」是管理动作（命名 / 封面 / 复制 / 删除 / 导出），
    //   塞一个视觉参数进去反而让人猜不到归属。
    //   【为什么留在本文件而不是搬家】它依赖 [percentText] 与 `DollSizeSpec` 的一整套换算，
    //   就地开放可见性（internal）比复制一份到 DollLookTab 更安全 —— 不会出现两份实现漂移。
    @Composable
    internal fun SizeDialog(
        act: MainActivity,
        dollId: String,
        onDismiss: () -> Unit
    ) {
        val c = DhTokens.colors
        // 【草稿态】滑条拖动是连续的，每动一下就落盘会产生大量 SharedPreferences 写入；
        //   这里先在内存里跟手，点「保存」才落盘（与编辑页的草稿层同一口径）。
        var specDraft by remember(dollId) { mutableStateOf(DollSizeSpec.spec(act, dollId)) }
        DhForm.Alert(
            title = "大小与位置",
            onDismiss = onDismiss,
            posText = "保存",
            onPos = {
                DollSizeSpec.setSpec(act, dollId, specDraft)
                HomeUi.notifyPetReload(act)
                onDismiss()
            },
            negText = "取消"
        ) {
            DhForm.Note(
                "这里调的是这只人偶本身的大小，与动作无关 —— 换动作不会忽大忽小。"
            )
            // 【控件选型】全部走 [DhKit.SliderRow]（桥接 View 版 UiKit.Slider）。
            //   本工程刻意不建 MaterialTheme，Compose 原生 Slider 会回落到 M3 默认配色，
            //   轨道与刻度都和全 App 的胶囊滑条不是一个形态 —— 那正是「UI 突兀」的来源。
            // ---------- 基础大小 ----------
            // 取值范围 30~400，用「最大值 + 当前值」表达（SliderRow 的接口是 Int 量程）。
            DhKit.SliderRow(
                title = "人偶大小",
                hint = "当前 " + specDraft.size + "%（100% = 按素材里角色的实际占比算出的原生大小）",
                max = DollSizeSpec.Spec.SIZE_MAX,
                value = specDraft.size
            ) { v, _ ->
                specDraft = specDraft.copy(size = DollSizeSpec.Spec.clampSize(v))
            }
            // ---------- 水平位置 ----------
            // 偏移是 -50% ~ +50%，滑条量程 0~100，取值时减 50 换算。
            DhKit.SliderRow(
                title = "水平位置",
                hint = percentText(specDraft.ox, true),
                max = 100,
                value = Math.round(specDraft.ox * 100f).toInt() + 50
            ) { v, _ ->
                specDraft = specDraft.copy(ox = DollSizeSpec.Spec.clampOffset((v - 50) / 100f))
            }
            // ---------- 垂直位置 ----------
            DhKit.SliderRow(
                title = "垂直位置",
                hint = percentText(specDraft.oy, false),
                max = 100,
                value = Math.round(specDraft.oy * 100f).toInt() + 50
            ) { v, _ ->
                specDraft = specDraft.copy(oy = DollSizeSpec.Spec.clampOffset((v - 50) / 100f))
            }
            Spacer(Modifier.height(6.dp))
            // ---------- 恢复自动适配 ----------
            DhForm.Note(
                "「恢复自动适配」= 大小回到 100%、位置回到居中。" +
                    "100% 是按素材里角色实际占画布的比例算出来的，不是把整张图拉满。"
            )
            DhForm.PrimaryChip("恢复自动适配") {
                specDraft = DollSizeSpec.Spec.DEFAULT
                DollSizeSpec.reset(act, dollId)
                HomeUi.notifyPetReload(act)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "提示：调完若觉得桌面没变，先退出编辑页再看 —— 编辑期间桌面只显示预览。",
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
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

    /** 添加面板：添加模板 / 本地导入。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AddSheet(onDismiss: () -> Unit, onTemplate: () -> Unit, onImport: () -> Unit) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val c = DhTokens.colors
        // 【为什么显式配色】本工程的主题真源是 UiKit → DhColors，**刻意不建 MaterialTheme**
        //   （见 DhTheme 文件头）。而 ModalBottomSheet 的默认取色来自 MaterialTheme.colorScheme，
        //   在我们这儿会回落到 M3 默认的浅色方案 —— 深色主题下弹出白底面板，与全局撕裂。
        //   故三个颜色槽位全部显式传入，并自绘拖拽把手，不依赖任何 M3 默认取色。
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = state,
            // 【r13】面板底走 c.sheet：不透明 + 磨砂质感，与弹窗同一层口径。
            containerColor = c.sheet,
            contentColor = c.title,
            scrimColor = c.scrim,
            dragHandle = { SheetDragHandle() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DhKit.IconButton(Icons.IC_LAYOUT_TEMPLATE, 24f, c.title, label = "模板", onClick = onTemplate)
                DhKit.IconButton(Icons.IC_FOLDER_OPEN, 24f, c.title, label = "导入", onClick = onImport)
            }
            Spacer(Modifier.height(16.dp))
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

    /**
     * 卡片「更多」面板：命名 / 改封面 / 复制 / 删除 / 导出 / 恢复默认配置。
     *
     * 【本轮·新增「恢复默认配置」】用户定案：「这个恢复的功能就是恢复默认配置的备份样子，
     *   也就是下载软件的时候自带的样子」。
     *   它做两件事，且只对**这只人偶**生效：
     *   ① 动作库回到出厂状态（三个固定位 + 全部内置动作，见 [DollAnim.restoreBuiltin]）；
     *   ② 尺寸规格回到自动适配，表情 / 手势等个性化项一并复位（见 [DollProfile.resetToFactory]）。
     *   保留用户自己导入的动作与形象图 —— 「恢复默认」不该等于「清空我的东西」。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MoreSheet(
        doll: DollStore.Doll,
        onDismiss: () -> Unit,
        onRename: () -> Unit,
        onCover: () -> Unit,
        onCopy: () -> Unit,
        onDelete: () -> Unit,
        onExport: () -> Unit,
        onRestore: () -> Unit
    ) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val c = DhTokens.colors
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = state,
            // 【r13】面板底走 c.sheet：不透明 + 磨砂质感，与弹窗同一层口径。
            containerColor = c.sheet,
            contentColor = c.title,
            scrimColor = c.scrim,
            dragHandle = { SheetDragHandle() }
        ) {
            // 【本轮·修「两个面板并排挤爆」】原来七个入口全塞在一行 `SpaceEvenly` 里，
            //   在 1080p 屏上每个按钮只剩约 46dp，「恢复默认配置」这种四字标签
            //   直接被裁掉一半（和动作页顶栏同一个病）。
            //   改成两行网格：每行 4 个，等分宽度，标签完整可读。
            val perRow = 4
            val all = ArrayList<Pair<Int, Pair<String, () -> Unit>>>()
            // 【图标 + 文字】底部面板横向空间宽裕，按钮都补上文字，不用再靠图标猜功能。
            all.add(Pair(Icons.IC_SIGNATURE, Pair("命名", onRename)))
            all.add(Pair(Icons.IC_IMAGE_PLUS, Pair("改封面", onCover)))
            all.add(Pair(Icons.IC_COPY, Pair("复制", onCopy)))
            // 内置默认配置不可删除：不渲染删除键。
            // 【r21·判据加固】从 `builtin` 标记改为**认固定身份** `id == DEFAULT_ID`。
            //   【为什么必须换】`DollStore.read()` 里有一条 `list[0].copy(builtin = true)`
            //   的兜底分支：若历史存档里某个自建人偶排在首位被写成 builtin=true，
            //   读出来就仍是 true —— 那会让自建人偶也冒出一个「删除」判据的反面
            //   （即误判成「内置」而不给删除键）。
            //   需求文档明确要求：删除与恢复默认必须用可靠的配置身份判断，不能只看标记。
            if (!isFactoryDoll(doll)) {
                all.add(Pair(Icons.IC_TRASH, Pair("删除", onDelete)))
            }
            all.add(Pair(Icons.IC_DOWNLOAD, Pair("导出", onExport)))
            // 【只在默认配置上出现】用户定案：「这个恢复默认 只需要默认配置有就好」。
            //   「恢复默认」的语义是「回到下载软件时自带的样子」，这个概念对**自建人偶**
            //   不成立 —— 自建人偶出厂状态就是用户自己刚建出来的空样子，
            //   点了既多余又容易被误解成「清空我的设置」。
            //   判据同样用固定身份，理由见上。
            // 【r21·文案缩短】「恢复默认」四个字在 4 列网格里被裁掉（每格约 46dp，
//   四字标签超出可用宽）。用户定案：「那个恢复默认4个字没展示 改成恢复吧」。
//   菜单项的上下文已经足够清楚（它只出现在默认配置上），「恢复」二字语义完整。
            if (isFactoryDoll(doll)) {
                all.add(Pair(Icons.IC_ROTATE_CCW, Pair("恢复", onRestore)))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                var i = 0
                while (i < all.size) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (k in 0 until perRow) {
                            val idx = i + k
                            if (idx < all.size) {
                                val it = all[idx]
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    DhKit.IconButton(it.first, 24f, c.title, label = it.second.first) {
                                        it.second.second()
                                    }
                                }
                            } else {
                                // 占位，保证最后一行仍然等分（否则 5 个按钮会拉宽）。
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    i += perRow
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    /**
     * 【r13·需求B】「从动作库挑一帧」当封面：两级选择（先挑动作，再挑帧）。
     *
     * 【为什么做成两级】需求原文：「点击之后会有个弹窗询问你 是选择人偶动作库的某个动作的
     *   某一帧还是自己的图片 封面」。一屏里塞不下「动作 × 帧」的全部组合，
     *   分两级既符合描述，也不会让弹窗变成一张长列表。
     *
     * 【帧从哪来】[DollAnim.all] 里的每个动作都有自己的帧序列；用户导入的动作也有。
     *   动作还没烘过帧时（frames 为空）该动作直接跳过 —— 点进去也没有帧可选。
     */
    @Composable
    private fun CoverPickDialog(
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
            DhForm.Alert(
                title = "从动作库选一帧",
                onDismiss = onDismiss,
                negText = "取消"
            ) {
                if (actions.isEmpty()) {
                    DhForm.Note("这只人偶还没有可用的动作帧。先在「动作」页里烘一次或导入一组图。")
                } else {
                    DhForm.Note("先选一个动作，再从它的帧里挑一张。")
                    for (i in actions.indices) {
                        val a = actions[i]
                        DhKit.ActionRow(
                            label = a.name + "（" + a.frames.size + " 帧）",
                            iconRes = Icons.IC_BLOCKS
                        ) { picked = i }
                    }
                }
            }
            return
        }

        val action = actions[idx]
        DhForm.Alert(
            title = action.name + " · 选一帧",
            onDismiss = onDismiss,
            negText = "返回"
        ) {
            DhForm.Note("点任意一帧就把它设为封面。")
            // 帧数最多 120，用横向滚动展示。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (p in action.frames) {
                    CoverThumb(path = p) {
                        Thread({
                            DollStore.setCoverFromFrame(act, dollId, p)
                            act.runOnUiThread { onDone() }
                        }, "dh-cover-frame").start()
                    }
                }
            }
        }
    }

    /** 挑帧用的小缩略图（只解码 1/4 尺寸，一屏几十张也不吃内存）。 */
    @Composable
    private fun CoverThumb(path: String, onClick: () -> Unit) {
        val c = DhTokens.colors
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
        Box(
            modifier = Modifier
                .padding(end = 8.dp)
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.soft)
                .border(1.dp, c.line, RoundedCornerShape(10.dp))
                .pressable(onClick),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                DhKit.Icon(Icons.IC_IMAGE, Design.SZ_SM, c.sub)
            }
        }
    }

    /**
     * 【r12】三点菜单浮层：主菜单（第一项「列数」）+ 列数二级菜单（向左展开）。
     *
     * 【为什么自绘】本工程刻意不建 MaterialTheme（见 DhTheme 头注释），
     *   DropdownMenu 的取色会落到 M3 浅色默认值上，与暗色主题撕裂。
     *
     * 【布局】整体贴右上角；二级卡片在主卡片**左侧**并排（需求原文「向左」）。
     *   点空白处关闭。
     *
     * @param level 0 = 主菜单；1 = 列数二级菜单
     */
    @Composable
    private fun MenuOverlay(
        cols: Int,
        level: Int,
        onPickCols: (Int) -> Unit,
        onOpenCols: () -> Unit,
        onBack: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val c = DhTokens.colors
        // 点空白关闭：铺满全屏的透明层先吃掉点击。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.TopEnd
        ) {
            Row(
                modifier = Modifier.padding(top = 64.dp, end = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // 二级卡片（列数）：在主卡片左边。
                if (level == 1) {
                    MenuCard {
                        for (n in PetPrefs.DOLL_COLS) {
                            MenuItem(
                                text = colsName(n) + if (n == cols) "  ✓" else "",
                                selected = n == cols
                            ) { onPickCols(n) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // 主卡片。
                MenuCard {
                    MenuItem(text = "列数  ›", selected = false) {
                        if (level == 1) onBack() else onOpenCols()
                    }
                    // 【预留位置】需求定案：菜单结构留好口子，以后再加其它项。
                }
            }
        }
    }

    /** 菜单小卡片外壳：CARD 底 + 圆角 + 描边。 */
    @Composable
    private fun MenuCard(content: @Composable () -> Unit) {
        val c = DhTokens.colors
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                .background(c.card)
                .border(1.dp, c.stroke, RoundedCornerShape(DhKit.R_CARD.dp))
                .padding(vertical = 6.dp)
        ) {
            content()
        }
    }

    /** 菜单项：一行文字；选中项用主色加粗。 */
    @Composable
    private fun MenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        Text(
            text = text,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            color = if (selected) c.acc else c.title,
            fontSize = UiKit.FS_BTN.sp,
            fontFamily = DhTokens.fontsBold,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }

    /** 列数的展示名。 */
    private fun colsName(n: Int): String {
        return when (n) {
            1 -> "一列"
            2 -> "两列"
            else -> "三列"
        }
    }

    /** 自绘拖拽把手：M3 默认把手取 `MaterialTheme.colorScheme`，本工程没建主题，会取到浅色默认值。 */
    @Composable
    private fun SheetDragHandle() {
        Box(
            modifier = Modifier
                .padding(top = 10.dp, bottom = 6.dp)
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(DhTokens.colors.line)
        )
    }
}