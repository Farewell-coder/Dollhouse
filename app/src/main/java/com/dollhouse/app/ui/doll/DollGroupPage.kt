package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollGroups
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】人偶分组管理页（r14·需求 4：「手动分组」的入口页）。
 *
 * 【从哪进】人偶页**顶栏**的 folder-tree 图标（用户定案：「入口在列表页顶部」）。
 *
 * 【能做什么】
 *   - 新建分组 / 重命名 / 删除（删组不删人偶，成员自动落回「未分组」）；
 *   - 把某只人偶移进 / 移出分组（点人偶行 → 选组）。
 *
 * 【为什么分组管理单开一页而不是弹窗】分组是**两级结构**（组 + 组内成员），
 *   弹窗里做「选组 → 看成员 → 再选人」的来回跳转体验很差；
 *   整页可以上下滚动，一屏看完所有组与成员，符合「管理」这个动作的语义。
 *
 * 【与「记忆图谱分类」的区别】那是记忆条目的分类，与本页无关（键名也刻意分开）。
 */
object DollGroupPage {

    /** 页面 key（进页面栈用）。 */
    const val KEY = "doll:groups"

    /** 打开本页；[onClose] 在页面退出时回调（列表页据此刷新分组）。 */
    fun open(act: MainActivity, onClose: () -> Unit = {}) {
        ComposeHost.installForActivity(act)
        // 【离开时通知】分组改了以后人偶页的列表要重算 —— 它不会自己重建。
        PageStack.onLeave(KEY) { onClose() }
        PageStack.push(act, KEY) { build(act) }
    }

    /** 页面构造：供页面栈入栈与跨重建恢复共用。 */
    fun build(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @Composable
    private fun Content(act: MainActivity) {
        val c = DhTokens.colors
        // 【重读信号】分组与归属都在 SharedPreferences 里，改完必须手动重组。
        var tick by remember { mutableStateOf(0) }
        val groups = remember(tick, DollState.rev) { DollGroups.grouped(act) }
        val allNames = remember(tick) { DollGroups.all(act) }

        // 弹窗状态：新建 / 改名 / 删除确认 / 成员调整。
        var adding by remember { mutableStateOf(false) }
        var input by remember { mutableStateOf("") }
        var renaming by remember { mutableStateOf<String?>(null) }
        var deleting by remember { mutableStateOf<String?>(null) }
        // 正在调整归属的人偶 id（null = 不显示）。
        var movingDoll by remember { mutableStateOf<String?>(null) }

        DhKit.Page(
            title = "分组管理",
            sub = "把 " + groups.sumOf { it.second.size } + " 只人偶分组归置",
            onBack = { PageStack.pop(act) },
            actions = {
                // 新建分组：右上角 +。【r15·图标+文字】顶栏空间宽裕 → 加文字。
                DhKit.IconButton(Icons.IC_FOLDER_PLUS, Design.SZ_MD, c.title, label = "新建分组") {
                    input = ""
                    adding = true
                }
            }
        ) {
            DhForm.Note(
                "分组只影响「人偶」页的展示顺序，不会改变聊天记录与角色设定。"
                    + "删除分组时，组里的人偶会回到「未分组」。"
            )

            if (groups.isEmpty() || groups.all { it.second.isEmpty() && it.first == DollStore.GROUP_NONE }) {
                // ---------- 空态 ----------
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DhKit.Icon(Icons.IC_TAGS, Design.SZ_XL, c.line)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (allNames.isEmpty()) "还没有分组" else "分组还是空的",
                            color = c.sub,
                            fontSize = 15.sp,
                            fontFamily = DhTokens.fonts
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "点右上角的文件夹图标新建一个",
                            color = c.line,
                            fontSize = 12.sp,
                            fontFamily = DhTokens.fonts
                        )
                    }
                }
            }

            for ((name, dolls) in groups) {
                // 「未分组」不是真分组，不给改名 / 删除按钮 —— 它是兜底桶，永远存在。
                val isNone = name == DollStore.GROUP_NONE
                DhKit.Card(collapsibleTitle = name + "  ·  " + dolls.size + " 只") {
                    if (!isNone) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DhKit.OutlineChip(text = "重命名", iconRes = Icons.IC_SIGNATURE) {
                                input = name
                                renaming = name
                            }
                            Spacer(Modifier.width(8.dp))
                            DhKit.OutlineChip(text = "删除分组", iconRes = Icons.IC_TRASH) {
                                deleting = name
                            }
                        }
                    }
                    for (d in dolls) {
                        DollLine(act, d) {
                            movingDoll = d.id
                        }
                    }
                    if (dolls.isEmpty()) {
                        DhForm.Note("这一组还没有人偶。点任意人偶可以把它移进来。")
                    }
                }
            }

            // ---------- 全部人偶：用来调整归属 ----------
            DhKit.Card(collapsibleTitle = "全部人偶") {
                DhForm.Note("点一只人偶，选择它要放进哪个分组。")
                val all = remember(tick, DollState.rev) { DollStore.all(act) }
                for (d in all) {
                    DollLine(act, d, showGroup = true) { movingDoll = d.id }
                }
            }
        }

        // ============================ 弹窗 ============================

        // 新建分组
        if (adding) {
            DhForm.Alert(
                title = "新建分组",
                onDismiss = { adding = false },
                posText = "创建",
                onPos = {
                    val r = DollGroups.add(act, input)
                    adding = false
                    tick++
                    if (r == null) {
                        // 【失败要说清原因】重名 / 空名 / 数量超限都会走到这里。
                        //   统一一句「名称不能用」比「创建失败」有用得多。
                        HomeNotice.show(act, "不能创建", "名称是空的、和已有分组重名，或者分组已达上限（" + DollGroups.MAX_GROUPS + " 个）。")
                    }
                },
                negText = "取消"
            ) {
                DhForm.Input(
                    value = input,
                    onValueChange = { if (it.length <= DollGroups.NAME_MAX) input = it },
                    label = "分组名",
                    hint = "比如：常用 / 工作",
                    trailing = {
                        Text(
                            text = input.length.toString() + "/" + DollGroups.NAME_MAX,
                            color = DhTokens.colors.line,
                            fontSize = UiKit.FS_TINY.sp,
                            fontFamily = DhTokens.fonts
                        )
                    }
                )
            }
        }

        // 重命名
        val ren = renaming
        if (ren != null) {
            DhForm.Alert(
                title = "重命名分组",
                onDismiss = { renaming = null },
                posText = "保存",
                onPos = {
                    val ok = DollGroups.rename(act, ren, input)
                    renaming = null
                    tick++
                    DollState.bump()
                    if (!ok) {
                        HomeNotice.show(act, "不能改名", "名称是空的，或者和已有分组重名。")
                    }
                },
                negText = "取消"
            ) {
                DhForm.Note("原分组：" + ren + "。改名后组里的人偶会一起跟过来。")
                DhForm.Input(
                    value = input,
                    onValueChange = { if (it.length <= DollGroups.NAME_MAX) input = it },
                    label = "新名字",
                    hint = ren
                )
            }
        }

        // 删除确认
        val del = deleting
        if (del != null) {
            DhForm.Alert(
                title = "删除分组",
                onDismiss = { deleting = null },
                posText = "删除",
                onPos = {
                    DollGroups.remove(act, del)
                    deleting = null
                    tick++
                    DollState.bump()
                },
                negText = "取消"
            ) {
                // 【必须说清「不删人偶」】否则用户会以为删组等于删角色，不敢点。
                DhForm.Note(
                    "只会删掉「" + del + "」这个分组，组里的人偶会回到「未分组」，"
                        + "人偶本身、聊天记录、角色设定全部保留。"
                )
            }
        }

        // 移动人偶
        val moving = movingDoll
        if (moving != null) {
            val doll = DollStore.byId(act, moving)
            DhForm.Alert(
                title = if (doll == null) "移动人偶" else "把「" + doll.name + "」放进",
                onDismiss = { movingDoll = null },
                negText = "取消"
            ) {
                // 【反馈】移动后立刻关窗并刷新；没有成功提示是刻意的 ——
                //   列表里的分组归属会立即变化，那就是最直接的反馈。
                for (n in allNames) {
                    val cur = doll != null && doll.group == n
                    DhKit.CheckRow(text = n, selected = cur) {
                        DollGroups.assign(act, moving, n)
                        movingDoll = null
                        tick++
                        DollState.bump()
                        HomeNotice.show(act, "已移动", "「" + (doll?.name ?: "") + "」现在在「" + n + "」。")
                    }
                }
                DhKit.CheckRow(
                    text = DollStore.GROUP_NONE,
                    selected = doll != null && doll.group.isEmpty()
                ) {
                    DollGroups.assign(act, moving, "")
                    movingDoll = null
                    tick++
                    DollState.bump()
                    HomeNotice.show(act, "已移出分组", "「" + (doll?.name ?: "") + "」回到了未分组。")
                }
                if (allNames.isEmpty()) {
                    DhForm.Note("还没有分组，先在右上角建一个。")
                }
            }
        }
    }

    /** 一行人偶：头像 + 名字 +（可选）当前分组标签。 */
    @Composable
    private fun DollLine(
        act: MainActivity,
        doll: DollStore.Doll,
        showGroup: Boolean = false,
        onClick: () -> Unit
    ) {
        val c = DhTokens.colors
        val cover = remember(doll.id, doll.cover) {
            runCatching { DollStore.coverOf(act, doll) }.getOrNull()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DhKit.GAP_ROW.dp)
                .clip(RoundedCornerShape(DhKit.R_ROW.dp))
                .background(c.soft)
                .pressable(onClick)
                .padding(horizontal = DhKit.PAD_ROW.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.cardUp),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null && !cover.isRecycled) {
                    androidx.compose.foundation.Image(
                        bitmap = cover.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    DhKit.Icon(
                        iconRes = if (doll.builtin) Icons.IC_WHALE else Icons.IC_USER_ROUND,
                        sizeDp = Design.SZ_SM,
                        color = c.sub
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = doll.name,
                modifier = Modifier.weight(1f),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fonts,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showGroup) {
                Text(
                    text = doll.group.ifEmpty { DollStore.GROUP_NONE },
                    color = c.sub,
                    fontSize = UiKit.FS_TINY.sp,
                    fontFamily = DhTokens.fonts
                )
                Spacer(Modifier.width(6.dp))
            }
            DhKit.Icon(iconRes = Icons.IC_FOLDER_TREE, sizeDp = Design.SZ_XS, color = c.line)
        }
    }
}

/**
 * 【职责】分组管理页的轻量提示。
 *
 * 【为什么不直接用 HomeUi.showNotice】那会引入 `ui.home` → `ui.doll` 的反向依赖
 *   （`ui.doll` 不该依赖 `ui.home` 的宿主函数）。这里做一层转发，
 *   把依赖方向留在「本文件 → HomeUi」这一条上，且只在弹提示时用。
 */
private object HomeNotice {
    fun show(act: MainActivity, title: String, msg: String) {
        try {
            com.dollhouse.app.ui.home.HomeUi.showNotice(act, title, msg)
        } catch (ignored: Throwable) {
        }
    }
}