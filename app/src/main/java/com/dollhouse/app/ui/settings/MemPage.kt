package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.Mem
import com.dollhouse.app.data.Memory
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 【职责】某只人偶的独立记忆库页（对齐 Operit 的 MemoryScreen，去掉向量/文档/图谱三块）。
 *
 * 【入口】人偶编辑页「人格」页的「记忆库」入口行 → open(ctx, dollId)。
 *
 * 【功能】列表 / 搜索 / 文件夹筛选 / 新建 / 编辑 / 删除 / 多选批量删 / 详情。
 *
 * 【隔离契约】页面的一切读写都带 dollId —— 只碰「这条会话绑定的那只人偶」的库。
 *   页面 key 是单例 [TAG_PAGE]，但正文按 dollId 取数；跨重建恢复时
 *   [buildPage] 从 [currentDollId] 读回同一个 id（与改造前一致）。
 *
 * 【为什么页根还是 View】`UiKit.openPage(content, page, tag)` 收的是 `View`，
 *   且 [com.dollhouse.app.ui.theme.GlobalBackground.installPage] 要把背景图铺在页根的 background 上。
 *   所以用 [ComposeHost.createView] 产出 `ComposeView` 当「页」，内部 100% Compose。
 *
 * 【坑】页面靠 tag 认领，任何 Context 都能调 closeIfOpen()；找不到 Activity 就静默返回
 *         （悬浮窗场景没有 Activity，所以入口只放在带 Activity 的页面里）。
 */
object MemPage {
    private const val LOG_TAG = "Dollhouse"
    const val TAG_PAGE = "feiyu_mem_page"

    /** 返回键用：记忆库页在栈顶就退一层并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return PageStack.popIfTop(ctx, TAG_PAGE)
    }

    /** 当前正在看的那只人偶 id（跨重建恢复时读回）。 */
    @Volatile
    private var currentDollId: String = DollStore.DEFAULT_ID

    /** 页面构造：供页面栈入栈与跨重建恢复共用（用上次打开时记下的 dollId）。 */
    @JvmStatic
    fun buildPage(ctx: Context): View? {
        return buildPage(ctx, currentDollId)
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
        // 【刷新机制】删除 / 编辑 / 新建后 refresh++ 触发重组重读，不整页重建（避免闪一下）。
        var refresh by remember { mutableStateOf(0) }
        var keyword by remember { mutableStateOf("") }
        var folder by remember { mutableStateOf("") }
        // 多选态：勾选一批一起删。
        var picking by remember { mutableStateOf(false) }
        val picked = remember { mutableStateOf(HashSet<String>()) }
        // 编辑 / 新建弹窗状态：null = 不弹；否则是被编辑的那条（新建时 id 为空）。
        var editing by remember { mutableStateOf<Memory?>(null) }
        var editingNew by remember { mutableStateOf(false) }
        var detail by remember { mutableStateOf<Memory?>(null) }

        val all = remember(refresh, dollId) { readAll(act, dollId) }
        val folders = remember(refresh, dollId) { Mem.allFolders(act, dollId) }
        val items = remember(refresh, dollId, keyword, folder) {
            all.filter { m ->
                val okFolder = folder.isEmpty() ||
                        m.folderPath == folder ||
                        m.folderPath.startsWith(folder + "/")
                val kw = keyword.trim()
                val okKw = kw.isEmpty() ||
                        m.title.contains(kw) ||
                        m.content.contains(kw) ||
                        m.tags.any { it.contains(kw) }
                okFolder && okKw
            }
        }
        val dollName = remember(dollId) { DollStore.byId(act, dollId)?.name }

        DhKit.Page(
            title = "记忆库",
            sub = if (dollName == null) "她记下的事" else dollName + " 记下的事",
            onBack = { closeIfOpen(act) }
        ) {
            // ---- 工具条：新建 / 搜索 / 文件夹 / 多选 ----
            DhKit.Card {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DhKit.IconButton(iconRes = Icons.IC_PLUS, sizeDp = 18f, color = c.title) {
                        editingNew = true
                        editing = Memory()
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "新建",
                        modifier = Modifier.pressable {
                            editingNew = true
                            editing = Memory()
                        },
                        color = c.title,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts
                    )
                    Spacer(Modifier.weight(1f))
                    // 多选：勾选一批一起删。
                    DhKit.IconButton(
                        iconRes = if (picking) Icons.IC_CHECK_CIRCLE else Icons.IC_LAYERS,
                        sizeDp = 18f,
                        color = if (picking) c.acc else c.sub
                    ) {
                        picking = !picking
                        if (!picking) {
                            picked.value = HashSet()
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (picking) "退出多选" else "多选",
                        modifier = Modifier.pressable {
                            picking = !picking
                            if (!picking) {
                                picked.value = HashSet()
                            }
                        },
                        color = if (picking) c.acc else c.sub,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
                // 多选态：底部一条「已选 N / 删除选中」。
                if (picking) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "已选 " + picked.value.size + " 条",
                            color = c.sub,
                            fontSize = UiKit.FS_SUB.sp,
                            fontFamily = DhTokens.fonts
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "删除选中",
                            modifier = Modifier.pressable {
                                if (picked.value.isEmpty()) {
                                    return@pressable
                                }
                                Mem.removeMany(act.applicationContext, dollId, picked.value)
                                picked.value = HashSet()
                                picking = false
                                refresh++
                            },
                            color = c.err,
                            fontSize = UiKit.FS_BTN.sp,
                            fontFamily = DhTokens.fontsBold,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ---- 搜索框 ----
            DhForm.Input(
                value = keyword,
                onValueChange = { keyword = it },
                hint = "搜标题 / 正文 / 标签",
                label = null
            )

            // ---- 文件夹筛选 ----
            if (folders.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Chip(text = "全部", on = folder.isEmpty()) { folder = "" }
                    folders.take(4).forEach { f ->
                        Chip(text = f, on = folder == f) { folder = if (folder == f) "" else f }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 列表 ----
            if (items.isEmpty()) {
                DhKit.Card {
                    Text(
                        text = if (all.isEmpty()) {
                            "还没有记下什么。\n聊天的过程中她觉得值得记的事会自己写进来，你也能点「新建」手动加。"
                        } else {
                            "没有匹配「" + keyword + "」的内容。"
                        },
                        color = c.sub,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts,
                        lineHeight = (UiKit.FS_BTN + 6f).sp
                    )
                }
            } else {
                items.forEach { m ->
                    MemItem(
                        m = m,
                        picking = picking,
                        checked = picked.value.contains(m.id),
                        onToggle = {
                            val set = HashSet(picked.value)
                            if (!set.add(m.id)) {
                                set.remove(m.id)
                            }
                            picked.value = set
                        },
                        onDetail = { detail = m },
                        onEdit = {
                            editingNew = false
                            editing = m
                        },
                        onDelete = {
                            Mem.remove(act.applicationContext, dollId, m.id)
                            refresh++
                        }
                    )
                }
            }

            // ---- 资料文档（Operit 的 profile_markdown）----
            val doc = remember(refresh, dollId) { Mem.profile(act, dollId) }
            if (doc.isNotEmpty()) {
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

            Text(
                text = "她在聊天里觉得值得记的事会自己写进来，下次开口前会按相关性挑出来带上。\n"
                    + "每只人偶一份独立记忆库，互不可见。\n"
                    + "共 " + all.size + " 条，最多保留 " + Mem.MAX_ENTRIES + " 条。数据只在这台设备上。",
                modifier = Modifier.padding(start = 2.dp, top = 12.dp, end = 2.dp),
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts,
                lineHeight = (UiKit.FS_TINY + 5f).sp
            )
        }

        // ---- 新建 / 编辑弹窗 ----
        val edit = editing
        if (edit != null) {
            MemEditDialog(
                act = act,
                dollId = dollId,
                m = edit,
                isNew = editingNew,
                onDismiss = { editing = null },
                onSaved = {
                    editing = null
                    refresh++
                }
            )
        }

        // ---- 详情弹窗 ----
        val d = detail
        if (d != null) {
            MemDetailDialog(m = d, onDismiss = { detail = null })
        }
    }

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

    /** 一个可点的小胶囊（文件夹筛选用）。 */
    @Composable
    private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
        val c = DhTokens.colors
        Text(
            text = text,
            modifier = Modifier
                .pressable(onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = if (on) c.acc else c.sub,
            fontSize = UiKit.FS_TINY.sp,
            fontFamily = if (on) DhTokens.fontsBold else DhTokens.fonts,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    /** 一条记忆：标题 + 文件夹/时间 + 正文 + 详情/编辑/删除。 */
    @Composable
    private fun MemItem(
        m: Memory,
        picking: Boolean,
        checked: Boolean,
        onToggle: () -> Unit,
        onDetail: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        val c = DhTokens.colors
        DhKit.Card {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (picking) {
                    DhKit.IconButton(
                        iconRes = if (checked) Icons.IC_CHECK_CIRCLE else Icons.IC_CIRCLE,
                        sizeDp = 18f,
                        color = if (checked) c.acc else c.sub
                    ) { onToggle() }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = m.title.ifEmpty { "（无标题）" },
                    modifier = Modifier
                        .weight(1f)
                        .pressable { if (picking) onToggle() else onDetail() },
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!picking) {
                    Text(
                        text = "改",
                        modifier = Modifier
                            .pressable(onEdit)
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        color = c.acc,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts
                    )
                    Text(
                        text = "删",
                        modifier = Modifier
                            .pressable(onDelete)
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        color = c.err,
                        fontSize = UiKit.FS_SUB.sp,
                        fontFamily = DhTokens.fonts
                    )
                }
            }
            // 副行：文件夹 + 时间（没文件夹就只显示时间）。
            val meta = StringBuilder()
            if (m.folderPath.isNotEmpty()) {
                meta.append(m.folderPath).append(" · ")
            }
            meta.append(fmtTime(m.createdAt))
            Text(
                text = meta.toString(),
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
            Text(
                text = m.content,
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts,
                lineHeight = (UiKit.FS_BTN + 6f).sp
            )
            if (m.tags.isNotEmpty()) {
                Text(
                    text = m.tags.joinToString(" ") { "#" + it },
                    modifier = Modifier.padding(top = 4.dp),
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
            }
        }
    }

    /** 新建 / 编辑弹窗：标题 / 正文 / 文件夹 / 标签。 */
    @Composable
    private fun MemEditDialog(
        act: Activity,
        dollId: String,
        m: Memory,
        isNew: Boolean,
        onDismiss: () -> Unit,
        onSaved: () -> Unit
    ) {
        var title by remember(m.id) { mutableStateOf(m.title) }
        var content by remember(m.id) { mutableStateOf(m.content) }
        var folder by remember(m.id) { mutableStateOf(m.folderPath) }
        var tags by remember(m.id) { mutableStateOf(m.tags.joinToString(" ")) }

        DhForm.Alert(
            title = if (isNew) "新建记忆" else "编辑记忆",
            onDismiss = onDismiss,
            posText = "保存",
            onPos = {
                if (content.trim().isEmpty() && title.trim().isEmpty()) {
                    onDismiss()
                    return@Alert
                }
                val target = if (isNew) {
                    val n = Memory()
                    n.title = title.trim()
                    n.content = content.trim()
                    n.folderPath = folder.trim()
                    n.source = Memory.SOURCE_MANUAL
                    n.originDoll = dollId
                    n.tags = splitTags(tags)
                    Mem.add(act.applicationContext, dollId, n)
                } else {
                    Mem.update(act.applicationContext, dollId, m.id) {
                        this.title = title.trim()
                        this.content = content.trim()
                        this.folderPath = folder.trim()
                        this.tags = splitTags(tags)
                    }
                }
                if (!target && !isNew) {
                    // 更新失败（条目被别处删了）—— 会话仍在，不阻断用户。
                }
                onSaved()
            },
            negText = "取消"
        ) {
            DhForm.Input(value = title, onValueChange = { title = it }, label = "标题", hint = "短标题，如「主人喜欢冰美式」")
            DhForm.InputMultiline(value = content, onValueChange = { content = it }, label = "内容", hint = "要记住的事，一两句说清")
            DhForm.Input(value = folder, onValueChange = { folder = it }, label = "文件夹", hint = "可留空，如「生活/饮食」")
            DhForm.Input(value = tags, onValueChange = { tags = it }, label = "标签", hint = "空格分隔，如「饮食 偏好」")
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
            DetailLine("重要性", fmtFloat(m.importance))
            DetailLine("可信度", fmtFloat(m.credibility))
            DetailLine("创建", fmtTime(m.createdAt))
            DetailLine("更新", fmtTime(m.updatedAt))
            DetailLine("ID", m.id)
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

    private fun fmtFloat(f: Float): String {
        return String.format(Locale.US, "%.2f", f)
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