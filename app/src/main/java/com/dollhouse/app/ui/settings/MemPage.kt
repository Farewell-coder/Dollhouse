package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.Mem
import com.dollhouse.app.data.MemLink
import com.dollhouse.app.data.Memory
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.home.HomeUi
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 【职责】某只人偶的独立记忆库页（r13 重做，对齐 Operit 的 MemoryScreen / rikkahub 的记忆页）。
 *
 * 【入口】人偶编辑页「人格」页的「记忆库」行 → [open]。
 *
 * 【r13 为什么重做】上一版只有「搜索框 + 新建 + 空态」三件套，用户反馈两点：
 *   ① **样式不对** —— 参考项目的记忆库有分类、有筛选、有结构，这里是一张白纸；
 *   ② **没法添加** —— 只有「新建条目」一条路，不能建文件夹、不能导入、不能改分类。
 *   而数据层（[Memory]）其实早有 `folderPath` / `tags` / `importance` / `credibility` 字段，
 *   只是界面一个都没接出来 —— 这是纯浪费，不是缺能力。
 *
 * 【本轮的结构】
 *   ┌ 搜索
 *   ├ 分类条：全部 / 未分类 / 各文件夹（横向滚动，点一下筛选）
 *   ├ 工具条：文件夹管理 / 多选 / 新建 / 导入文件
 *   ├ 列表：标题 · 文件夹 · 时间 / 正文 / 标签 · 重要度
 *   └ 底部：统计 + 容量
 *
 * 【多选删除去掉了】改成「每条卡片直接可操作」+ 长按进入多选（与聊天页同款手势），
 *   避免上一版那个「多选」按钮藏在工具条里、用户找不到也看不懂。
 *
 * 【隔离契约】页面的一切读写都带 dollId —— 只碰「这只人偶」的库。
 *   页面 key 是单例 [TAG_PAGE]，但正文按 dollId 取数；跨重建恢复时
 *   [buildPage] 从 [currentDollId] 读回同一个 id。
 */
object MemPage {
    private const val LOG_TAG = "Dollhouse"
    const val TAG_PAGE = "feiyu_mem_page"

    /** 分类条里「全部」的哨兵值（不能用空串 —— 空串与「未分类」的 folderPath 同形）。 */
    private const val FILTER_ALL = "\u0000all"

    /** 分类条里「未分类」的哨兵值。 */
    private const val FILTER_NONE = "\u0000none"

    /** 返回键用：记忆库页在栈顶就退一层并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return PageStack.popIfTop(ctx, TAG_PAGE)
    }

    /**
     * 当前正在看的那只人偶 id（跨重建恢复时读回）。
     *
     * 【本轮的修正】原来这里是**纯内存**的 `@Volatile var`，默认值是 `DollStore.DEFAULT_ID`。
     *   进程被系统回收后再恢复，这个字段跟着进程一起没了 → 恢复到「记忆库」这一层时，
     *   `buildPage(ctx)` 读到的是默认配置，用户看到的是**别人偶的记忆库**
     *   （或者在多个人偶之间来回串）。这类问题在开发机上几乎测不出来
     *   （进程不被杀就一直是对的），只有真机长时间挂后台才会出现。
     *
     *   现在改为「内存缓存 + 偏好落盘」双写：内存为快路径，落盘用于跨进程存活。
     *   键按页面单个（不含 dollId），因为这里记的就是「这一层现在指向谁」。
     */
    @Volatile
    private var currentDollId: String? = null

    /** 读回「上次打开记忆库时看的是哪只人偶」；没有记录时回落默认配置。 */
    private fun readCurrentDollId(ctx: Context): String {
        val cached = currentDollId
        if (!cached.isNullOrEmpty()) {
            return cached
        }
        val saved = PetPrefs.memPageDollId(ctx)
        val id = if (saved.isNullOrEmpty()) DollStore.DEFAULT_ID else saved
        currentDollId = id
        return id
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用（用上次打开时记下的 dollId）。 */
    @JvmStatic
    fun buildPage(ctx: Context): View? {
        return buildPage(ctx, readCurrentDollId(ctx))
    }

    @JvmStatic
    fun buildPage(ctx: Context, dollId: String): View? {
        val act = findActivity(ctx) ?: return null
        // Compose 运行所需 owner（原生 Activity 不自动装），幂等。
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { MemContent(act, dollId) }
    }

    /** 打开某只人偶的记忆库；不传 dollId 时用默认配置（旧调用点兜底）。 */
    @JvmStatic
    fun open(ctx: Context) {
        open(ctx, DollStore.DEFAULT_ID)
    }

    @JvmStatic
    fun open(ctx: Context, dollId: String) {
        try {
            currentDollId = dollId
            // 【落盘】让进程被杀后还能回到同一只人偶（见 [currentDollId] 的注释）。
            PetPrefs.setMemPageDollId(ctx, dollId)
            PageStack.push(ctx, TAG_PAGE) { buildPage(ctx, dollId) }
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

    // ============================ 页面正文 ============================
    @Composable
    private fun MemContent(act: Activity, dollId: String) {
        val c = DhTokens.colors
        // 【刷新机制】任何写操作后 refresh++ 触发重组重读，不整页重建（避免闪一下）。
        var refresh by remember { mutableStateOf(0) }
        var keyword by remember { mutableStateOf("") }
        // 当前分类筛选：全部 / 未分类 / 某个文件夹路径。
        var filter by remember { mutableStateOf(FILTER_ALL) }
        // 编辑 / 新建条目：null = 不弹；isNew 决定标题与写入方式。
        var editing by remember { mutableStateOf<Memory?>(null) }
        var editingNew by remember { mutableStateOf(false) }
        var detail by remember { mutableStateOf<Memory?>(null) }
        /** 【r19·长按删除】长按节点要删的条目（弹确认框用）。 */
        var delAskFor by remember { mutableStateOf<Memory?>(null) }
        // 文件夹管理弹窗（= 右上角文件夹图标进的「分类界面」）。
        var folderMgr by remember { mutableStateOf(false) }
        // 【r14】点中的边（显示关系详情）。
        var pickedEdge by remember { mutableStateOf<MemLink?>(null) }
        // 【r14】搜索命中的列表（非空 = 覆盖显示结果列表）。
        var searchOpen by remember { mutableStateOf(false) }

        val all = remember(refresh, dollId) { readAll(act, dollId) }
        val folders = remember(refresh, dollId) { Mem.allFolders(act, dollId) }
        val allTags = remember(refresh, dollId) {
            val set = LinkedHashSet<String>()
            for (m in all) {
                set.addAll(m.tags)
            }
            set.toList()
        }

        // 【过滤】按分类 + 关键词筛出「这次要画的节点」。
        //   【为什么先筛再画】没筛的话，100 条记忆全画上去会糊成一团；
        //   分类筛选 + 搜索正好给了用户「只看我关心的那部分」的能力。
        val shownMems = remember(refresh, dollId, keyword, filter) {
            all.filter { m ->
                val okFolder = when (filter) {
                    FILTER_ALL -> true
                    FILTER_NONE -> m.folderPath.isEmpty() ||
                            folders.none { f -> m.folderPath == f || m.folderPath.startsWith(f + "/") }
                    else -> m.folderPath == filter || m.folderPath.startsWith(filter + "/")
                }
                val kw = keyword.trim()
                val okKw = kw.isEmpty() ||
                        m.title.contains(kw) ||
                        m.content.contains(kw) ||
                        m.tags.any { it.contains(kw) }
                okFolder && okKw
            }
        }
        val dollName = remember(dollId) { DollStore.byId(act, dollId)?.name }

        // 【关系边】读这只人偶的全部关系，交给布局。
        val links = remember(refresh, dollId) { readLinks(act, dollId) }

        // 【手动摆位】用户拖过的节点坐标（按标题存）。
        val pins = remember(refresh, dollId) { readPins(act, dollId) }

        // 【布局结果】在 IO 线程算（200 节点 × 300 轮，主线程算会掉帧）。
        //   key 含 shownMems / links / pins：数据或摆位变了才重算。
        var layout by remember(dollId) { mutableStateOf<MemGraphLayout.Result?>(null) }
        LaunchedEffect(shownMems, links, pins) {
            layout = withContext(Dispatchers.Default) {
                MemGraphLayout.compute(shownMems, links, pins)
            }
        }

        // 【画布实例】用 AndroidView 桥接自绘手势画布（见 [MemGraphView] 头注释）。
        val viewRef = remember { mutableStateOf<MemGraphView?>(null) }
        val highlight = remember { mutableStateOf<String?>(null) }

        DhKit.Page(
            title = "记忆图谱",
            sub = if (dollName == null) {
                "她记下的事 · " + all.size + " 条"
            } else {
                dollName + " 记下的事 · " + all.size + " 条"
            },
            onBack = { closeIfOpen(act) },
            // 【自带滚动 = false】图谱是固定画布（自己处理手势与平移），
            //   外面再套一层滚动会让「拖动节点」与「滚动页面」打架。
            scrollable = false,
            actions = {
                // 【右上角文件夹图标 → 分类界面】用户定案：
                //   「纯替换成图谱 + 可以通过右上角的一个文件夹图标进入分类界面」。
                // 【r15·图标+文字】顶栏右侧空间宽裕 → 加文字。
                DhKit.IconButton(Icons.IC_FOLDER_TREE, Design.SZ_MD, c.title, label = "分类") {
                    folderMgr = true
                }
                // 新建节点。图标用 circle-dot（图谱里的一个节点），与「新建文件夹」区分开。
                DhKit.IconButton(Icons.IC_CIRCLE_DOT, Design.SZ_MD, c.title, label = "新建") {
                    editingNew = true
                    editing = Memory()
                }
            }
        ) {
            // ---- 搜索栏（保留：结果为列表，点一条直达该节点）----
            DhForm.Input(
                value = keyword,
                onValueChange = {
                    keyword = it
                    searchOpen = it.trim().isNotEmpty()
                },
                hint = "搜标题 / 正文 / 标签",
                label = null,
                trailing = if (keyword.isEmpty()) null else {
                    {
                        DhKit.IconButton(Icons.IC_CLOSE, Design.SZ_SM, c.sub) {
                            keyword = ""
                            searchOpen = false
                            highlight.value = null
                            viewRef.value?.setHighlight(null)
                        }
                    }
                }
            )

            // ---- 分类条（全部 / 未分类 / 各文件夹）----
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("全部", filter == FILTER_ALL) { filter = FILTER_ALL }
                FilterChip("未分类", filter == FILTER_NONE) { filter = FILTER_NONE }
                if (folders.size > 0) {
                    FilterChip(shortFolder(folders[0]), filter == folders[0]) { filter = folders[0] }
                }
            }

            // ---- 图谱画布 ----
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(DhKit.R_CARD.dp))
                    .background(c.soft)
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        MemGraphView(ctx).apply {
                            // 【颜色从主题现取】本 View 不依赖 UiKit（见其注释），由这里灌入。
                            setPalette(
                                edge = c.line,
                                edgeLabel = c.sub,
                                nodeBg = c.card,
                                nodeStroke = c.stroke,
                                nodeText = c.title,
                                accent = c.acc,
                                highlightBg = c.option
                            )
                            onNodeClick = { title ->
                                // 【点节点 → 编辑框】用户定案：「点节点 → 编辑框选关联」。
                                val m = shownMems.firstOrNull { it.title == title }
                                if (m != null) {
                                    editingNew = false
                                    editing = m
                                }
                            }
                            // 【r19·长按删除】长按节点弹删除确认（用户反馈「删除按键失效」）。
                            onNodeLongClick = { title ->
                                val m = shownMems.firstOrNull { it.title == title }
                                if (m != null) {
                                    // 复用编辑弹窗的删除确认逻辑（先删边、再删条目）。
                                    delAskFor = m
                                }
                            }
                            onBlankClick = {
                                highlight.value = null
                                setHighlight(null)
                            }
                            onNodeMoved = { title, x, y ->
                                // 【持久化手动摆位】下次进来还在他放的地方。
                                savePin(act, dollId, title, x, y)
                            }
                            // 【v0.1.0·修·视角不记忆】缩放/平移原来只在本次会话内有效：
                            //   MemGraphView 早就提供了 currentScale/currentOffsetX/Y 与
                            //   restoreView()，PetPrefs 也早就备好了 memGraphView 的存取，
                            //   但**两头都没人接** —— 退出图谱再进来，视图总是回到默认。
                            //   这里把 onViewChanged 接上（该回调在缩放/平移/居中/聚焦时都会触发），
                            //   并在 factory 末尾恢复上次视角。
                            onViewChanged = {
                                saveGraphView(
                                    act, dollId,
                                    currentScale(), currentOffsetX(), currentOffsetY()
                                )
                            }
                            viewRef.value = this
                            // 【v0.1.0·恢复上次视角】必须等布局完成（width/height 才有值），
                            //   否则 restoreView 拿到的是 0×0 画布基准，偏移算出来是错的。
                            //   用 post 排到下一次布局之后执行一次即可。
                            val saved = loadGraphView(act, dollId)
                            if (saved != null) {
                                post {
                                    // 二次确认控件已量好；仍为 0 就放弃（保持默认视角比错位好）。
                                    if (width > 0 && height > 0) {
                                        restoreView(saved[0], saved[1], saved[2])
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    // 【关键】布局结果变化时把新图灌进去，但**保留视角**（keepView = true），
                    //   否则用户刚调好的缩放/平移会在每次数据刷新时被重置。
                    update = { v ->
                        v.setGraph(layout, fit = false, keepView = true)
                        viewRef.value = v
                    }
                )

                // ---- 空态 ----
                if (shownMems.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            DhKit.Icon(Icons.IC_WAYPOINTS, Design.SZ_XL, c.line)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = if (all.isEmpty()) "还没有记下什么" else "这一分类下没有内容",
                                color = c.sub,
                                fontSize = UiKit.FS_BTN.sp,
                                fontFamily = DhTokens.fonts
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (all.isEmpty()) {
                                    "聊天时她会自己写进来；也可以手动加一条"
                                } else {
                                    "换个分类或关键词看看"
                                },
                                color = c.line,
                                fontSize = UiKit.FS_SUB.sp,
                                fontFamily = DhTokens.fonts
                            )
                        }
                    }
                }

                // ---- 搜索命中列表（覆盖在图谱上）----
                if (searchOpen && keyword.trim().isNotEmpty()) {
                    SearchPanel(
                        hitList = shownMems,
                        onPick = { m ->
                            keyword = m.title
                            searchOpen = false
                            highlight.value = m.title
                            viewRef.value?.setHighlight(m.title)
                        },
                        onClose = { searchOpen = false }
                    )
                }

                // ---- 图例（右上角小字，说明连线上的字是什么意思）----
                Legend(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }

            // ---- 底部工具条 ----
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolAction(Icons.IC_MAXIMIZE, "适配") {
                    viewRef.value?.fitToView()
                }
                Spacer(Modifier.width(10.dp))
                ToolAction(Icons.IC_ZOOM_IN, "放大") {
                    zoomBy(viewRef.value, 1.25f)
                }
                Spacer(Modifier.width(10.dp))
                ToolAction(Icons.IC_ZOOM_OUT, "缩小") {
                    zoomBy(viewRef.value, 0.8f)
                }
                Spacer(Modifier.weight(1f))
                // 【导入】把一份文本塞进来（与旧版一致的能力，不能丢）。
                ToolAction(Icons.IC_DOWNLOAD, "导入") {
                    val main = act as? MainActivity
                    if (main == null) {
                        HomeUi.showNotice(act, "无法导入", "请在主界面里打开记忆库再导入。")
                    } else {
                        importFile(main, dollId) {
                            refresh++
                            viewRef.value?.fitToView()
                        }
                    }
                }
            }

            // ---- 资料文档（关于主人的印象）----
            val doc = remember(refresh, dollId) { Mem.profile(act, dollId) }
            if (doc.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                DhKit.Card(collapsibleTitle = "关于主人的印象") {
                    Text(
                        text = doc,
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts,
                        lineHeight = (UiKit.FS_BTN + 6f).sp
                    )
                }
            }

            // 【r19·删描述】底部三行说明文字全部删除（用户要求「那一堆描述文字不需要」）。
            //   只保留「共 N 条 / 最多保留 M 条」这一行统计信息（用户需要知道容量）。
        }

        // ---- 新建 / 编辑条目（含「关联」区 = 手动连线）----
        val edit = editing
        if (edit != null) {
            MemEditDialog(
                act = act,
                dollId = dollId,
                m = edit,
                isNew = editingNew,
                knownFolders = folders,
                knownTags = allTags,
                allMemories = all,
                onDismiss = { editing = null },
                onAskDelete = { target ->
                    // 【r20】先把编辑弹窗关掉，再弹确认框 —— 同一时刻只存在一个 Dialog。
                    editing = null
                    delAskFor = target
                },
                onSaved = {
                    editing = null
                    refresh++
                }
            )
        }

        // ---- 详情 ----
        val d = detail
        if (d != null) {
            MemDetailDialog(m = d, onDismiss = { detail = null })
        }

        // ---- 关系详情（点连线）----
        val pe = pickedEdge
        if (pe != null) {
            EdgeDialog(
                link = pe,
                onDismiss = { pickedEdge = null },
                onDelete = {
                    removeLink(act, dollId, pe)
                    pickedEdge = null
                    refresh++
                }
            )
        }

        // ---- 长按节点删除确认（r19·修复「删除按键失效」）----
        val del = delAskFor
        if (del != null) {
            DhForm.Alert(
                title = "删除记忆",
                onDismiss = { delAskFor = null },
                posText = "删除",
                onPos = {
                    // 【删之前先把关系边清掉】边是按标题连的，条目没了边还在的话，
                    //   图谱里会残留一条指向「不存在的标题」的悬空线。
                    Mem.removeLinksOfTitle(act.applicationContext, dollId, del.title)
                    Mem.remove(act.applicationContext, dollId, del.id)
                    MemoryIds.invalidate()
                    delAskFor = null
                    refresh++
                },
                negText = "取消"
            ) {
                Text(
                    text = "确定删除「" + del.title + "」吗？这条记忆和它的连线会一起删掉。",
                    color = DhTokens.colors.sub,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }

        // ---- 分类界面（右上角文件夹图标）----
        if (folderMgr) {
            MemFolderDialog(
                act = act,
                dollId = dollId,
                onDismiss = { folderMgr = false },
                onChanged = { refresh++ }
            )
        }
    }

    /* ============================ r14·图谱辅助小件 ============================ */

    /**
     * 搜索命中列表（覆盖在图谱之上）。
     *
     * 【为什么用「覆盖面板」而不是「替换图谱」】用户定案「保留搜索栏，结果为列表可直达」——
     *   直达之后还要能回到图谱看那个节点的位置，所以列表是**临时浮层**，选完即收。
     */
    @Composable
    private fun SearchPanel(
        hitList: List<Memory>,
        onPick: (Memory) -> Unit,
        onClose: () -> Unit
    ) {
        val c = DhTokens.colors
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .background(c.card)
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "找到 " + hitList.size + " 条",
                    modifier = Modifier.weight(1f),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
                DhKit.IconButton(Icons.IC_CLOSE, Design.SZ_SM, c.sub) { onClose() }
            }
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                // 【为什么改成 for + item】`items(list)` 是 LazyListScope 的扩展函数，
//   它的 `items` 参数名与本函数参数冲突，且扩展必须靠 import 才能解析（全限定名无效）。
//   改用 `item {}` 逐个铺：命中列表最多几十条，性能差异可忽略，代码更直白。
            for (m in hitList) {
                item(key = m.id) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                            .pressable { onPick(m) }
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = m.title.ifEmpty { "（无标题）" },
                            color = c.title,
                            fontSize = UiKit.FS_BTN.sp,
                            fontFamily = DhTokens.fontsBold,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = m.content.replace('\n', ' '),
                            color = c.sub,
                            fontSize = UiKit.FS_SUB.sp,
                            fontFamily = DhTokens.fonts,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
    }

    /** 图例：解释连线上的字与节点大小的含义（小字，不抢视线）。 */
    @Composable
    private fun Legend(modifier: Modifier = Modifier) {
        val c = DhTokens.colors
        Column(modifier = modifier) {
            Text(
                text = "连线 = 关系",
                color = c.line,
                fontSize = 10.sp,
                fontFamily = DhTokens.fonts
            )
            Text(
                text = "大圈 = 关联多",
                color = c.line,
                fontSize = 10.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /** 关系详情弹窗（点连线）。 */
    @Composable
    private fun EdgeDialog(link: MemLink, onDismiss: () -> Unit, onDelete: () -> Unit) {
        val c = DhTokens.colors
        DhForm.Alert(
            title = "关系",
            onDismiss = onDismiss,
            negText = "关闭"
        ) {
            Text(
                text = link.source.ifEmpty { "（无标题）" } + "  —" + LINK_NAMES.getOrDefault(link.type.uppercase(), link.type) + "→  " + link.target.ifEmpty { "（无标题）" },
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            if (link.description.isNotEmpty()) {
                Text(
                    text = link.description,
                    color = c.sub,
                    fontSize = UiKit.FS_SUB.sp,
                    fontFamily = DhTokens.fonts
                )
                Spacer(Modifier.height(8.dp))
            }
            DetailLine("强度", pct(link.weight))
            Spacer(Modifier.height(10.dp))
            DhKit.OutlineChip("删除这条关系") { onDelete() }
        }
    }

    /** 缩放（工具条按钮）。 */
    private fun zoomBy(v: MemGraphView?, k: Float) {
        if (v == null) {
            return
        }
        // 【为什么直接设缩放而不是走手势】按钮是「一格一格地缩」，
        //   以屏幕中心为锚最符合直觉（与手势的「以捏合点为锚」不同）。
        val ns = (v.currentScale() * k).coerceIn(0.25f, 3f)
        v.setZoomAtCenter(ns)
    }


    // ============================ 小件 ============================

    /** 分类条上的一枚胶囊。 */
    @Composable
    private fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        Text(
            text = text,
            modifier = Modifier
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .then(
                    if (on) {
                        Modifier.background(c.chatChipFg)
                    } else {
                        Modifier
                            .background(c.soft)
                            .border(1.dp, c.line, RoundedCornerShape(DhKit.R_ROW.dp))
                    }
                )
                .pressable(onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            color = if (on) c.onAcc else c.title,
            fontSize = UiKit.FS_TINY.sp,
            fontFamily = if (on) DhTokens.fontsBold else DhTokens.fonts,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    /** 工具条上的「图标 + 文字」小按钮。 */
    @Composable
    private fun ToolAction(iconRes: Int, text: String, onClick: () -> Unit) {
        val c = DhTokens.colors
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .pressable(onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(iconRes = iconRes, sizeDp = Design.SZ_XS, color = c.sub)
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    // ============================ 弹窗 ============================

    /** 新建 / 编辑条目：标题 / 正文 / 文件夹（可选已有）/ 标签 / 重要度 / 关联。 */
    @Composable
    private fun MemEditDialog(
        act: Activity,
        dollId: String,
        m: Memory,
        isNew: Boolean,
        knownFolders: List<String>,
        knownTags: List<String>,
        allMemories: List<Memory>,
        onDismiss: () -> Unit,
        /** 【r20】编辑态点「删除」时的请求：交回外层关掉本弹窗并弹一次确认框。 */
        onAskDelete: (Memory) -> Unit,
        onSaved: () -> Unit
    ) {
        val c = DhTokens.colors
        var title by remember(m.id) { mutableStateOf(m.title) }
        var content by remember(m.id) { mutableStateOf(m.content) }
        var folder by remember(m.id) { mutableStateOf(m.folderPath) }
        var tags by remember(m.id) { mutableStateOf(m.tags.joinToString(" ")) }
        var importance by remember(m.id) { mutableStateOf(m.importance) }
        // 【r14·手动连线】本条目要连到哪些条目（用户定案：「点节点 → 编辑框选关联」）。
        //   只在**编辑已有条目**时可用 —— 新建的条目还没写盘，没有稳定的标题可供连线。
        var linked by remember(m.id) {
            mutableStateOf(if (isNew) emptySet<String>() else readOutLinks(act, dollId, m.title))
        }
        var linkPicker by remember { mutableStateOf(false) }
        // 【r20·修「删除按键失效」】原先这里自己再弹一个 `DhForm.Alert` 做二次确认，
//   有两个明确后果：
//   ① **删成功了也不刷新**：确认框的 onPos 只调 `onDismiss()`（= editing = null），
//      没有让外层的 `refresh++`。而图谱数据是 `remember(refresh, dollId)` 缓存的，
//      refresh 不变就不会重算 —— 条目其实已经从盘上删掉了，屏幕上那条还在，
//      用户看到的就是「点了删除没反应、删不掉」。
//   ② 两个 Dialog 叠在同一个宿主里，上层确认框的点击命中不稳定。
//   现在改成**向外层请求删除**：外层先关掉本编辑弹窗，再弹一次确认框。
//   始终只有一个 Dialog，且删除成功后走的是外层那条带 refresh++ 的分支。
        DhForm.Alert(
            title = if (isNew) "新建记忆" else "编辑记忆",
            onDismiss = onDismiss,
            posText = "保存",
            // 【r15·补删除按钮】编辑态把「取消」换成「删除」会误导，所以：
            //   保留 pos=保存，neg 若是编辑态则显示「删除」，点击交给外层弹确认框。
            negText = if (isNew) "取消" else "删除",
            onNeg = if (isNew) null else ({ onAskDelete(m) }),
            onPos = {
                if (content.trim().isEmpty() && title.trim().isEmpty()) {
                    onDismiss()
                    return@Alert
                }
                if (isNew) {
                    val n = Memory()
                    n.title = title.trim()
                    n.content = content.trim()
                    n.folderPath = folder.trim()
                    n.source = Memory.SOURCE_MANUAL
                    n.originDoll = dollId
                    n.tags = splitTags(tags)
                    n.importance = importance
                    Mem.add(act.applicationContext, dollId, n)
                } else {
                    val oldTitle = m.title
                    Mem.update(act.applicationContext, dollId, m.id) {
                        this.title = title.trim()
                        this.content = content.trim()
                        this.folderPath = folder.trim()
                        this.tags = splitTags(tags)
                        this.importance = importance
                    }
                    // 【改标题 = 关系边要跟着改】**关系是按标题连的**（MemLink 只有 source/target
                    //   两个字符串，没有 id）。标题一改，旧的边就成了指向不存在条目的悬空边。
                    //   所以这里必须把旧标题上的边整体搬到新标题上，否则用户会看到
                    //   「改了名字，线全断了」。
                    val newTitle = title.trim()
                    if (oldTitle != newTitle) {
                        renameLinkEnds(act, dollId, oldTitle, newTitle)
                    }
                    // 写回关联（以「本条目 → 目标」的方向为准）。
                    writeOutLinks(act, dollId, newTitle, linked)
                    MemoryIds.invalidate()
                }
                onSaved()
            },
            // 【r15】negText 已在上方按「新建=取消 / 编辑=删除」给过，这里不再重复传，
            //   否则 Kotlin 报「An argument is already passed for this parameter」。
        ) {
            DhForm.Input(
                value = title,
                onValueChange = { title = it },
                label = "标题",
                hint = "短标题，如「主人喜欢冰美式」"
            )
            DhForm.InputMultiline(
                value = content,
                onValueChange = { content = it },
                label = "内容",
                hint = "要记住的事，一两句说清",
                minHeight = 96
            )
            DhForm.Input(
                value = folder,
                onValueChange = { folder = it },
                label = "文件夹",
                hint = "可留空（未分类）；用 / 分层，如「生活/饮食」"
            )
            // 【已有的文件夹直接点】用输入框 + 快捷 chip 的双通道：既支持新建任意路径，
            //   也支持一键复用已有分类（避免手打错字产生「生活」和「生活 」两个文件夹）。
            if (knownFolders.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    knownFolders.take(3).forEach { f ->
                        FilterChip(shortFolder(f), folder == f) { folder = f }
                    }
                }
            }
            DhForm.Input(
                value = tags,
                onValueChange = { tags = it },
                label = "标签",
                hint = "空格分隔，如「饮食 偏好」"
            )
            if (knownTags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    knownTags.take(3).forEach { t ->
                        FilterChip("#" + t, false) {
                            val cur = splitTags(tags)
                            if (!cur.contains(t)) {
                                cur.add(t)
                                tags = cur.joinToString(" ")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            DhKit.StepperRow(
                name = "重要度",
                value = pct(importance),
                canMinus = importance > 0f,
                canPlus = importance < 1f,
                onMinus = { importance = round2(importance - 0.2f) },
                onPlus = { importance = round2(importance + 0.2f) }
            )
            DhForm.Note("重要度越高，聊天时越容易被优先想起。")
            // ---------- 【r14】关联（手动连线）----------
            if (!isNew) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DhKit.Icon(iconRes = Icons.IC_LINK_2, sizeDp = Design.SZ_XS, color = c.sub)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "关联到其它记忆",
                        modifier = Modifier.weight(1f),
                        color = c.sub,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts
                    )
                    DhKit.OutlineChip("选择") { linkPicker = true }
                }
                if (linked.isEmpty()) {
                    DhForm.Note("还没有关联。关联之后，图谱上会从这里连一条线出去。")
                } else {
                    // 【已选的关联直接列出】让用户一眼看到「这条连到了谁」，
                    //   不用再点开选择器确认。点它即可移除。
                    Spacer(Modifier.height(6.dp))
                    linked.toList().forEach { t ->
                        DhKit.CheckRow(text = t, selected = true) {
                            linked = linked - t
                        }
                    }
                }
            }
            // 【为什么放最后】它是只读信息，不参与编辑。
            if (!isNew && m.updatedAt > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "上次修改：" + fmtTime(m.updatedAt),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }

        // ---------- 关联选择器 ----------
        if (linkPicker) {
            // 【候选怎么来】除了自己以外的所有条目。「关联」是对等的：A 连 B 与 B 连 A
            //   在图谱上是同一根线（无向），所以不区分方向。
            val others = allMemories.filter { it.title.isNotEmpty() && it.title != m.title }
            DhForm.Alert(
                title = "关联到…",
                onDismiss = { linkPicker = false },
                posText = "完成",
                negText = "取消"
            ) {
                if (others.isEmpty()) {
                    DhForm.Note("暂时没有别的记忆可以关联。先多记几条吧。")
                } else {
                    // 【为什么要限高】条目多时列表会撑爆弹窗；这里最多列 12 条，
                    //   更多的用搜索找（见图谱内的搜索栏）。
                    for (o in others.take(12)) {
                        DhKit.CheckRow(text = o.title, selected = linked.contains(o.title)) {
                            linked = if (linked.contains(o.title)) {
                                linked - o.title
                            } else {
                                linked + o.title
                            }
                        }
                    }
                    if (others.size > 12) {
                        DhForm.Note("还有 " + (others.size - 12) + " 条没列出；可以用图谱里的搜索先找到它。")
                    }
                }
            }
        }
    }

    /** 文件夹管理：列出、新建、重命名、删除。 */
    @Composable
    private fun MemFolderDialog(
        act: Activity,
        dollId: String,
        onDismiss: () -> Unit,
        onChanged: () -> Unit
    ) {
        val c = DhTokens.colors
        var refresh by remember { mutableStateOf(0) }
        val folders = remember(refresh, dollId) { Mem.allFolders(act, dollId) }
        var draft by remember { mutableStateOf("") }
        // 正在重命名哪个（null = 没在改名）。
        var renaming by remember { mutableStateOf<String?>(null) }
        var renameText by remember { mutableStateOf("") }

        DhForm.Alert(
            title = "文件夹",
            onDismiss = onDismiss,
            posText = "关闭"
        ) {
            DhForm.Note("文件夹用来分类。改名前先想好 —— 改完会同步挪动里面的条目。")
            if (folders.isEmpty()) {
                DhForm.Note("还没有文件夹。在下面新建一个吧。")
            }
            for (f in folders) {
                if (renaming == f) {
                    DhForm.Input(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = null,
                        hint = "新名字"
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip("确定", true) {
                            if (renameFolder(act, dollId, f, renameText.trim())) {
                                renaming = null
                                refresh++
                                onChanged()
                            }
                        }
                        FilterChip("取消", false) { renaming = null }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DhKit.Icon(Icons.IC_FOLDER_OPEN, Design.SZ_XS, c.sub)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = f,
                            modifier = Modifier.weight(1f),
                            color = c.title,
                            fontSize = UiKit.FS_BTN.sp,
                            fontFamily = DhTokens.fonts,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "改",
                            modifier = Modifier
                                .pressable {
                                    renaming = f
                                    renameText = f
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                            color = c.acc,
                            fontSize = UiKit.FS_SUB.sp,
                            fontFamily = DhTokens.fonts
                        )
                        Text(
                            text = "删",
                            modifier = Modifier
                                .pressable {
                                    deleteFolder(act, dollId, f)
                                    refresh++
                                    onChanged()
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                            color = c.err,
                            fontSize = UiKit.FS_SUB.sp,
                            fontFamily = DhTokens.fonts
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DhForm.Input(
                value = draft,
                onValueChange = { draft = it },
                label = "新建文件夹",
                hint = "用 / 分层，如「生活/饮食」"
            )
            Spacer(Modifier.height(6.dp))
            DhKit.ActionRow(label = "创建", iconRes = Icons.IC_PLUS) {
                val name = draft.trim().trim('/')
                if (name.isNotEmpty() && !folders.contains(name)) {
                    createFolder(act, dollId, name)
                    draft = ""
                    refresh++
                    onChanged()
                }
            }
        }
    }

    /** 详情弹窗：全部字段。 */
    @Composable
    private fun MemDetailDialog(m: Memory, onDismiss: () -> Unit) {
        val c = DhTokens.colors
        DhForm.Alert(
            title = m.title.ifEmpty { "（无标题）" },
            onDismiss = onDismiss,
            posText = "关闭"
        ) {
            Text(
                text = m.content,
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts,
                lineHeight = (UiKit.FS_BTN + 6f).sp
            )
            Spacer(Modifier.height(10.dp))
            DetailLine("文件夹", m.folderPath.ifEmpty { "未分类" })
            DetailLine("标签", if (m.tags.isEmpty()) "无" else m.tags.joinToString(" "))
            DetailLine("来源", sourceName(m.source))
            DetailLine("重要度", pct(m.importance))
            DetailLine("可信度", pct(m.credibility))
            DetailLine("创建", fmtTime(m.createdAt))
            DetailLine("更新", fmtTime(m.updatedAt))
        }
    }

    @Composable
    private fun DetailLine(label: String, value: String) {
        val c = DhTokens.colors
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                text = label,
                modifier = Modifier.width(60.dp),
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                color = c.title,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    // ============================ 数据操作 ============================

    /** 读某只人偶的全部条目（新的在前）。 */
    private fun readAll(ctx: Context, dollId: String): List<Memory> {
        val arr = Mem.list(ctx, dollId)
        val out = ArrayList<Memory>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Memory.fromJson(o))
        }
        return out
    }

    /* ============================ 【r14】图谱数据 ============================ */

    /**
     * 读这只人偶的全部关系。
     *
     * 【为什么要做「去重 + 归一化」】AI 抽取时可能把同一条关系抽出两次（两次对话都提到），
     *   画在图上就是两根重合的线 —— 视觉上像一根更粗的线，但点它只会命中其中一根，
     *   用户会疑惑「为什么删了还在」。这里按 (source, target, type) 去重，保留先出现的。
     */
    private fun readLinks(ctx: Context, dollId: String): List<MemLink> {
        val arr = Mem.links(ctx, dollId)
        val out = ArrayList<MemLink>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val l = MemLink.fromJson(o)
            val key = l.source + "\u0000" + l.target + "\u0000" + l.type
            if (seen.add(key)) {
                out.add(l)
            }
        }
        return out
    }

    /** 读用户手动摆过的节点位置（标题 → (x, y)）。 */
    private fun readPins(ctx: Context, dollId: String): Map<String, Pair<Float, Float>> {
        val raw = PetPrefs.memGraphPins(ctx, dollId)
        if (raw.isEmpty()) {
            return emptyMap()
        }
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, Pair<Float, Float>>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.optJSONObject(k) ?: continue
                out[k] = v.optDouble("x", 0.0).toFloat() to v.optDouble("y", 0.0).toFloat()
            }
            out
        } catch (ignored: Throwable) {
            emptyMap()
        }
    }

    /**
     * 落一次节点摆位。
     *
     * 【为什么「读-改-写」】摆位是「按标题散存」的，每次只更新被拖的那一个；
     *   直接覆盖整份会把其它节点的摆位全丢掉。
     */
    private fun savePin(ctx: Context, dollId: String, title: String, x: Float, y: Float) {
        try {
            val cur = HashMap(readPins(ctx, dollId))
            cur[title] = x to y
            val o = JSONObject()
            for ((k, v) in cur) {
                val item = JSONObject()
                item.put("x", v.first.toDouble())
                item.put("y", v.second.toDouble())
                o.put(k, item)
            }
            PetPrefs.setMemGraphPins(ctx, dollId, o.toString())
        } catch (ignored: Throwable) {
        }
    }

    /**
     * **【v0.1.0·新增】保存 / 恢复记忆图谱的视角**（缩放 + 平移）。
     *
     * 【为什么现在才有】`MemGraphView` 早就暴露了 `currentScale/currentOffsetX/Y`
     *   与 `restoreView()`，`PetPrefs.memGraphView` 也早就备好了存取接口 ——
     *   但两端都没人调用，于是「用户把图谱缩放/拖到自己习惯的位置」这件事
     *   一退出页面就丢失，每次进来都回到默认视角。
     *
     * 【为什么用 JSON 而不是三个独立键】视图状态是「一份快照」，
     *   三个键分开写会读到「新 scale + 旧 offset」的半更新中间态（抖动）。
     *   一个键一次 `apply()` 落盘，读到的一定是自洽的一组值。
     */
    private fun saveGraphView(ctx: Context, dollId: String, scale: Float, ox: Float, oy: Float) {
        try {
            // 默认视角（scale<=0）不落盘：省得在字段里塞一堆无意义的初值。
            if (scale <= 0.01f) {
                return
            }
            val o = JSONObject()
            o.put("scale", scale.toDouble())
            o.put("tx", ox.toDouble())
            o.put("ty", oy.toDouble())
            PetPrefs.setMemGraphView(ctx, dollId, o.toString())
        } catch (ignored: Throwable) {
        }
    }

    /** 读出上次保存的视角；返回 null 表示没有（调用方保持默认视角）。 */
    private fun loadGraphView(ctx: Context, dollId: String): FloatArray? {
        return try {
            val raw = PetPrefs.memGraphView(ctx, dollId)
            if (raw.isEmpty()) {
                return null
            }
            val o = JSONObject(raw)
            val s = o.optDouble("scale", 0.0).toFloat()
            if (s <= 0.01f) {
                return null
            }
            floatArrayOf(s, o.optDouble("tx", 0.0).toFloat(), o.optDouble("ty", 0.0).toFloat())
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 删掉一条关系（按 (source, target, type) 三要素定位）。 */
    private fun removeLink(ctx: Context, dollId: String, link: MemLink) {
        try {
            val arr = Mem.links(ctx, dollId)
            val keep = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val same = o.optString("source") == link.source &&
                        o.optString("target") == link.target &&
                        o.optString("type") == link.type
                if (!same) {
                    keep.put(o)
                }
            }
            // 【没有公开的「整份替换链接」入口？走已有的 removeLinksOfTitle 会误删其它类型】——
            //   所以这里直接用 setMem2Links 覆盖（与 Mem.addLink 的落盘键一致）。
            PetPrefs.setMem2Links(ctx, dollId, keep.toString())
        } catch (ignored: Throwable) {
        }
    }
/** 关系类型的中文名（图谱边标签 / 关系详情用）。与 [MemGraphView.typeLabel] 保持同一套口径。 */
    private val LINK_NAMES = mapOf(
        "FOLLOWS" to "之后",
        "CORRECTS" to "纠正",
        "UPDATES" to "更新",
        "INVOLVES" to "参与",
        "HAPPENS_AT" to "发生于",
        "PART_OF" to "属于",
        "ALLIED_WITH" to "同一阵营",
        "OPPOSES" to "对立",
        "RELATED" to "相关"
    )

    /* ============================ 【r14】手动连线 ============================ */

    /**
     * 读「从 [title] 出发」的关联目标（去重）。
     *
     * 【为什么两个方向都算】图谱上的连线是**无向**的（力导向布局与绘制都不区分方向），
     *   但 `MemLink` 有 source/target。若只读一个方向，用户会看到「编辑框里没勾选、
     *   图谱上却有线」的矛盾。这里把「我指向别人」与「别人指向我」合并成同一份关联集合，
     *   保存时再统一写成一个方向。
     */
    private fun readOutLinks(ctx: Context, dollId: String, title: String): Set<String> {
        if (title.isEmpty()) {
            return emptySet()
        }
        val out = LinkedHashSet<String>()
        val arr = Mem.links(ctx, dollId)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optString("source")
            val t = o.optString("target")
            if (s == title && t.isNotEmpty()) {
                out.add(t)
            } else if (t == title && s.isNotEmpty()) {
                out.add(s)
            }
        }
        return out
    }

    /**
     * 把 [title] 的关联写回成「[title] → 每个目标」的一组边。
     *
     * 【为什么先删后写】关联集合是**用户最终看到的那份**；与磁盘上已有的边做 diff 再增删
     *   会让代码复杂一倍，而条目数上限 200、每条关联不过几条 —— 整体重写更简单也更不容易错。
     *
     * 【保留什么】只重写「涉及 [title] 的边」；其它条目之间的边原样保留
     *   （所以不能简单地对整个 links 数组盖写）。
     */
    private fun writeOutLinks(ctx: Context, dollId: String, title: String, targets: Set<String>) {
        if (title.isEmpty()) {
            return
        }
        try {
            val arr = Mem.links(ctx, dollId)
            val keep = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = o.optString("source")
                val t = o.optString("target")
                // 丢掉所有与 title 相关的边（下面按新集合重建）。
                if (s == title || t == title) {
                    continue
                }
                keep.put(o)
            }
            for (tg in targets) {
                if (tg.isEmpty() || tg == title) {
                    continue
                }
                val l = MemLink()
                l.source = title
                l.target = tg
                // 【为什么默认 RELATED】用户手动连的线没有明确语义关系，
                //   用「相关」这个中性类型最诚实（不假装知道是「属于」还是「对立」）。
                l.type = "RELATED"
                l.weight = 0.6f
                l.description = ""
                keep.put(l.toJson())
            }
            PetPrefs.setMem2Links(ctx, dollId, keep.toString())
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 条目改名时，把关系边两端的旧标题一起改掉。
     *
     * 【不做会怎样】关系按标题连；条目一改名，原边就成了指向不存在条目的悬空边，
     *   图谱上表现为「线全断了」。用户会以为改个名把数据弄坏了。
     */
    private fun renameLinkEnds(ctx: Context, dollId: String, oldTitle: String, newTitle: String) {
        if (oldTitle.isEmpty() || newTitle.isEmpty() || oldTitle == newTitle) {
            return
        }
        try {
            val arr = Mem.links(ctx, dollId)
            val out = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("source") == oldTitle) {
                    o.put("source", newTitle)
                }
                if (o.optString("target") == oldTitle) {
                    o.put("target", newTitle)
                }
                out.put(o)
            }
            PetPrefs.setMem2Links(ctx, dollId, out.toString())
        } catch (ignored: Throwable) {
        }
    }
    /**
     * 新建文件夹。
     *
     * 【为什么需要写一个「空条目」】文件夹是从条目的 `folderPath` 推导出来的
     *   （见 [Mem.allFolders]）—— 没有任何条目挂在下面，文件夹就不存在。
     *   所以这里插入一条**占位条目**把路径「钉住」。它会被用户看见，
     *   因此内容写得像一条正常的自动记忆，而不是「占位」这种技术词。
     */
    private fun createFolder(act: Activity, dollId: String, path: String) {
        val n = Memory()
        n.title = "新建文件夹：" + path
        n.content = "这个文件夹用来归类。你可以把这条改成别的，也可以直接往里面加条目。"
        n.folderPath = path
        n.source = Memory.SOURCE_MANUAL
        n.originDoll = dollId
        n.tags = ArrayList()
        Mem.add(act.applicationContext, dollId, n)
    }

    /** 改文件夹名（同步把下面所有条目的路径前缀换掉）。 */
    private fun renameFolder(act: Activity, dollId: String, old: String, newName: String): Boolean {
        if (newName.isEmpty() || newName == old) {
            return false
        }
        try {
            val arr = Mem.list(act.applicationContext, dollId)
            var hit = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val fp = o.optString("folderPath", "")
                val nf = newFolderPath(fp, old, newName)
                if (nf != null) {
                    o.put("folderPath", nf)
                    hit = true
                }
            }
            if (!hit) {
                return false
            }
            // 【复用 add 里的落盘路径】Mem 没有暴露「整表回写」，这里用 update 逐条改会更啰嗦；
            //   直接走一次 add + 清空的组合太重，故用最直白的方式：把改好的数组整体存回去。
            Mem.replaceAll(act.applicationContext, dollId, arr)
            return true
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "renameFolder 失败", t)
            return false
        }
    }

    /** 算出新路径：命中前缀（自身或子路径）就替换，否则返回 null。 */
    private fun newFolderPath(fp: String, old: String, newName: String): String? {
        if (fp == old) {
            return newName
        }
        if (fp.startsWith(old + "/")) {
            return newName + fp.substring(old.length)
        }
        return null
    }

    /** 删文件夹：把下面条目的 folderPath 清空（变成未分类），条目本身**不删**。 */
    private fun deleteFolder(act: Activity, dollId: String, path: String) {
        try {
            val arr = Mem.list(act.applicationContext, dollId)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val fp = o.optString("folderPath", "")
                if (fp == path || fp.startsWith(path + "/")) {
                    o.put("folderPath", "")
                }
            }
            Mem.replaceAll(act.applicationContext, dollId, arr)
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "deleteFolder 失败", t)
        }
    }

    /**
     * 导入一份文本文件为一条记忆。
     *
     * 【为什么复用 PickFileActivity】工程已有一条成熟的「选文件 → 复制到缓存 → 回调路径」
     *   通道（动作导入、角色卡导入都走它）。不再另起一套，用户看到的选择器完全一致。
     *
     * 【大文件截断】一份长文可能上万字，而单条记忆是「一两句」的量级。
     *   这里按 [IMPORT_MAX] 截断，并**保留开头** —— 开头通常就是文件的主题。
     */
    private fun importFile(act: MainActivity, dollId: String, onDone: () -> Unit) {
        val listener = object : PickFileActivity.FileListener {
            override fun onFiles(paths: List<String>, failed: Boolean) {
                if (failed || paths.isEmpty()) {
                    HomeUi.showNotice(act, "导入失败", "没能读取选中的文件，请重试。")
                    return
                }
                val path = paths[0]
                val text = try {
                    File(path).readText(Charsets.UTF_8)
                } catch (t: Throwable) {
                    Logs.w(LOG_TAG, "读取导入文件失败", t)
                    HomeUi.showNotice(act, "导入失败", "读不了这个文件，换一个再试。")
                    return
                }
                val body = text.trim()
                if (body.isEmpty()) {
                    HomeUi.showNotice(act, "导入失败", "这个文件是空的。")
                    return
                }
                val name = File(path).name
                val n = Memory()
                n.title = if (name.length > 24) name.substring(0, 24) else name
                n.content = if (body.length > IMPORT_MAX) body.substring(0, IMPORT_MAX) + "…" else body
                n.folderPath = "导入"
                n.source = Memory.SOURCE_MANUAL
                n.originDoll = dollId
                n.tags = arrayListOf("导入")
                Mem.add(act.applicationContext, dollId, n)
                HomeUi.showNotice(
                    act, "已导入",
                    "存成了一条记忆，在「导入」文件夹里。"
                )
                onDone()
            }
        }
        PickFileActivity.setFileListener(listener)
        PickFileActivity.startFiles(act, false)
    }

    // ============================ 工具 ============================

    /** 单条导入的文本上限（超出截断并补省略号）。 */
    private const val IMPORT_MAX = 2000

    /** 文件夹太多时，分类条上只显示末段。 */
    private fun shortFolder(f: String): String {
        val i = f.lastIndexOf('/')
        val s = if (i >= 0) f.substring(i + 1) else f
        return if (s.length > 8) s.substring(0, 8) + "…" else s
    }

    private fun splitTags(raw: String): MutableList<String> {
        val out = ArrayList<String>()
        for (s in raw.split(' ', ',', '，', '、')) {
            val t = s.trim()
            if (t.isNotEmpty()) {
                out.add(t)
            }
        }
        return out
    }

    private fun sourceName(s: String): String {
        return when (s) {
            Memory.SOURCE_MANUAL -> "手动添加"
            Memory.SOURCE_CHAT -> "从聊天存下"
            else -> "AI 自动记下"
        }
    }

    private fun round2(f: Float): Float {
        val v = Math.round(f * 100f) / 100f
        return if (v < 0f) 0f else if (v > 1f) 1f else v
    }

    private fun pct(f: Float): String {
        return Math.round(f * 100f).toString() + "%"
    }

    private fun fmtTime(ts: Long): String {
        if (ts <= 0L) {
            return ""
        }
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))
        } catch (unused: Throwable) {
            ""
        }
    }
}

/**
 * 【职责】记忆条目 id 的失效广播（r14）。
 *
 * 【为什么需要】`Memory.newId()` 与 id 的落盘由数据层管，界面侧没有「刷新 id」的入口。
 *   新建条目后如果列表缓存了旧 id，后续的编辑 / 删除会作用到错的对象上。
 *   这里给一个极轻的失效信号，让需要的地方能主动清缓存。
 */
private object MemoryIds {
    /** 失效计数：读到它变化即知道 id 集合可能已经变了。 */
    @Volatile
    var rev: Int = 0
        private set

    fun invalidate() {
        rev++
    }
}
