package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollGroups
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.qzx.QzxButton
import com.dollhouse.app.ui.qzx.QzxButtonSpec
import com.dollhouse.app.ui.qzx.QzxButtonStyle
import com.dollhouse.app.ui.qzx.QzxDialog
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
import com.dollhouse.app.ui.qzx.buttons
import com.dollhouse.app.ui.theme.Icons

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
 *
 * 【QZX 迁移】本页已从旧 `DhKit` 骨架迁到 `ui/qzx`：页面只描述「有哪些行 / 哪些分区」，
 *   行高、内边距、描边、分隔线、按钮对齐全部由 `QzxList` 按令牌算 ——
 *   本文件里**没有任何**尺寸、间距、描边或分隔线的字面量。
 *   ⚠️ `QzxList { }` 的 DSL lambda **不是** `@Composable`，所以 `painterResource` / `remember`
 *   这类组合期调用必须先在 `Content` 里算好再当参数传进去（见 [Content] 里的 `covers`）。
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

    /** 分组卡的标题（也就是它的持久化键）：`名字  ·  N 只`。 */
    private fun cardTitle(name: String, size: Int): String = name + "  ·  " + size + " 只"

    /**
     * 卡片展开态：**没被用户手动调过时按「展开」算**。
     *
     * 【为什么不能直接读 `cardOpen`】它的默认值是 false（"没这个键"与"用户收起了"在读端
     *   分不出来）。旧 `DhKit.Card` 用的是「`hasCardOpen` ? 读值 : true」，这里沿用同一口径，
     *   否则迁移后每张卡第一次进来都是收起的 —— 那是**行为变化**，不是换皮。
     */
    private fun cardOpen(act: MainActivity, title: String): Boolean =
        if (PetPrefs.hasCardOpen(act, title)) PetPrefs.cardOpen(act, title) else true

    @Composable
    private fun Content(act: MainActivity) {
        // 【重读信号】分组与归属都在 SharedPreferences 里，改完必须手动重组。
        var tick by remember { mutableStateOf(0) }
        val groups = remember(tick, DollState.rev) { DollGroups.grouped(act) }
        val allNames = remember(tick) { DollGroups.all(act) }
        val allDolls = remember(tick, DollState.rev) { DollStore.all(act) }

        // 弹窗状态：新建 / 改名 / 删除确认 / 成员调整。
        var adding by remember { mutableStateOf(false) }
        var nameInput by remember { mutableStateOf("") }
        var renaming by remember { mutableStateOf<String?>(null) }
        var deleting by remember { mutableStateOf<String?>(null) }
        // 正在调整归属的人偶 id（null = 不显示）。
        var movingDoll by remember { mutableStateOf<String?>(null) }

        // 【折叠态必须由本页持有】`QzxList` 的 `onToggle` 一旦给了，展开态就完全由调用方决定：
        //   只把 `PetPrefs` 里那个 Boolean 传进去的话，点一下**画面不会动**（prefs 不是
        //   Compose 状态，写完没人重组）。所以这里放一份 `SnapshotStateMap`：读它 → 能重组，
        //   写它 + 回写 prefs → 观感与「下次进来还记得」两件都对。
        val openState = remember(groups) {
            val m = mutableStateMapOf<String, Boolean>()
            for ((n, ds) in groups) {
                val t = cardTitle(n, ds.size)
                m[t] = cardOpen(act, t)
            }
            m["全部人偶"] = cardOpen(act, "全部人偶")
            m
        }
        /** 切换一张卡的展开态：先改状态（画面立刻动），再落盘（下次进来还是这个样）。 */
        fun toggleCard(title: String, open: Boolean) {
            openState[title] = open
            PetPrefs.setCardOpen(act, title, open)
        }

        // 【封面必须在组合期解析】`QzxList { }` 的 DSL 是普通 lambda（不是 @Composable），
        //   里面既不能 `remember` 也不能 `painterResource` —— 行参数只能是现成的值。
        // 【r24·修内存】头像别再按 1024px 解码（4MB/张），96px 已足够（列表里画 20dp）。
        val covers: Map<String, Painter> = remember(tick, DollState.rev) {
            val out = HashMap<String, Painter>(allDolls.size)
            for (d in allDolls) {
                val bmp = runCatching { DollStore.coverOf(act, d, 96) }.getOrNull() ?: continue
                if (bmp.isRecycled) continue
                out[d.id] = BitmapPainter(bmp.asImageBitmap())
            }
            out
        }
        // 【图标用 Int 资源 id】QzxList 的 DSL lambda 不是 @Composable，
        //   在里面调 painterResource 会编译不过 —— 直接传 Icons.IC_*（它本身就是 R.drawable.*）。
        val icBuiltin = Icons.IC_WHALE
        val icUser = Icons.IC_USER_ROUND
        val icCheck = Icons.IC_CHECK

        /**
         * 一行人偶的左侧图标。
         * 【为什么返回两个值】封面是**运行时位图**（`BitmapPainter`），没有资源 id；
         *   没有封面时才回退到资源图标。两者本质不同，不能强行统一成一个类型。
         */
        fun rowIconRes(d: DollStore.Doll): Int? =
            if (covers[d.id] == null) (if (d.builtin) icBuiltin else icUser) else null

        fun rowIconPainter(d: DollStore.Doll): Painter? = covers[d.id]

        QzxPage(
            title = "分组管理",
            subtitle = "把 " + groups.sumOf { it.second.size } + " 只人偶分组归置",
            onBack = { PageStack.popIfTop(act, KEY) },
            // 【图标由调用方给】新系统不持有旧 drawable —— 删旧资源时这里编译不过，正好是
            //   「还没迁完」的信号，而不是静默退化成没有返回键。
            backIcon = painterResource(Icons.IC_ARROW_LEFT),
            actions = {
                // 新建分组：右上角。【r15】旧版是 folder-plus 图标 + 无障碍名「新建分组」，
                //   新系统顶栏没有图标按钮件 → 改成同语义的文字按钮。
                //   位置、文案、点下去的动作都不变（只是从图标变文字，不是少了一个入口）。
                QzxButton(
                    text = "新建分组",
                    style = QzxButtonStyle.Ghost,
                    onClick = {
                        nameInput = ""
                        adding = true
                    },
                )
            },
        ) {
            QzxList {
                // ---------- 页首说明 ----------
                // flat 分区（不画卡片底）：一句话不值得单独占一张卡。
                section(id = "intro", flat = true) {
                    note(
                        "分组只影响「人偶」页的展示顺序，不会改变聊天记录与角色设定。"
                            + "删除分组时，组里的人偶会回到「未分组」。"
                    )
                }

                // ---------- 空态 ----------
                // 【条件原样】「没有任何分组」或「只有一个空的兜底桶」才算空。
                val noRealGroup = groups.isEmpty() ||
                    groups.all { it.second.isEmpty() && it.first == DollStore.GROUP_NONE }
                if (noRealGroup) {
                    // 【文案基本原样】标题按 `allNames` 分岔，说明是操作指引；只把
                    //   「文件夹图标」换成新顶栏上真实存在的那个按钮名，免得指错地方。
                    section(id = "empty") {
                        item(if (allNames.isEmpty()) "还没有分组" else "分组还是空的")
                            .desc("点右上角的「新建分组」新建一个")
                    }
                }

                // ---------- 各分组 ----------
                for ((name, dolls) in groups) {
                    // 「未分组」不是真分组，不给改名 / 删除按钮 —— 它是兜底桶，永远存在。
                    val isNone = name == DollStore.GROUP_NONE
                    // 【标题即折叠键】旧卡片就是用这个动态标题当持久化键的，保持一字不差。
                    val title = cardTitle(name, dolls.size)
                    section(
                        id = "group:" + name,
                        title = title,
                        collapsible = true,
                        expanded = openState[title] ?: true,
                        onToggle = { toggleCard(title, it) },
                    ) {
                        if (!isNone) {
                            // 【两个入口原样】重命名 / 删除分组：旧版是并排两个次按钮，
                            //   这里走系统的按钮行（对齐与间距归系统）。
                            buttons(
                                QzxButtonSpec("重命名", QzxButtonStyle.Secondary) {
                                    nameInput = name
                                    renaming = name
                                },
                                QzxButtonSpec("删除分组", QzxButtonStyle.Secondary) {
                                    deleting = name
                                },
                            )
                        }
                        for (d in dolls) {
                            item(d.name, iconRes = rowIconRes(d), iconPainter = rowIconPainter(d)).arrow().onClick {
                                movingDoll = d.id
                            }
                        }
                        if (dolls.isEmpty()) {
                            note("这一组还没有人偶。点任意人偶可以把它移进来。")
                        }
                    }
                }

                // ---------- 全部人偶：用来调整归属 ----------
                section(
                    id = "all",
                    title = "全部人偶",
                    collapsible = true,
                    expanded = openState["全部人偶"] ?: true,
                    onToggle = { toggleCard("全部人偶", it) },
                ) {
                    note("点一只人偶，选择它要放进哪个分组。")
                    for (d in allDolls) {
                        // 【值原样】右侧显示的是这名人偶当前的组（空 = 未分组）。
                        item(
                            d.name,
                            value = d.group.ifEmpty { DollStore.GROUP_NONE },
                            iconRes = rowIconRes(d),
                            iconPainter = rowIconPainter(d),
                        ).arrow().onClick { movingDoll = d.id }
                    }
                }
            }
        }

        // ============================ 弹窗 ============================

        // 新建分组
        QzxDialog(
            visible = adding,
            title = "新建分组",
            confirmText = "创建",
            dismissText = "取消",
            onDismiss = { adding = false },
            onConfirm = {
                val r = DollGroups.add(act, nameInput)
                adding = false
                tick++
                if (r == null) {
                    // 【失败要说清原因】重名 / 空名 / 数量超限都会走到这里。
                    //   统一一句「名称不能用」比「创建失败」有用得多。
                    HomeNotice.show(
                        act,
                        "不能创建",
                        "名称是空的、和已有分组重名，或者分组已达上限（" +
                            DollGroups.MAX_GROUPS + " 个）。"
                    )
                }
            },
        ) {
            QzxList {
                section(id = "new-group") {
                    // 【就地限长】与旧实现同口径：超过 NAME_MAX 的字直接不收（系统再画字数计数）。
                    input("分组名", nameInput)
                        .hint("比如：常用 / 工作")
                        .counter(DollGroups.NAME_MAX)
                        .onValueChange { nameInput = it.take(DollGroups.NAME_MAX) }
                }
            }
        }

        // 重命名
        val ren = renaming
        QzxDialog(
            visible = ren != null,
            title = "重命名分组",
            confirmText = "保存",
            dismissText = "取消",
            onDismiss = { renaming = null },
            onConfirm = {
                if (ren != null) {
                    val ok = DollGroups.rename(act, ren, nameInput)
                    renaming = null
                    tick++
                    DollState.bump()
                    if (!ok) {
                        HomeNotice.show(act, "不能改名", "名称是空的，或者和已有分组重名。")
                    }
                }
            },
        ) {
            QzxList {
                section(id = "rename-group") {
                    note("原分组：" + ren + "。改名后组里的人偶会一起跟过来。")
                    input("新名字", nameInput)
                        .hint(ren ?: "")
                        .counter(DollGroups.NAME_MAX)
                        .onValueChange { nameInput = it.take(DollGroups.NAME_MAX) }
                }
            }
        }

        // 删除确认
        val del = deleting
        QzxDialog(
            visible = del != null,
            title = "删除分组",
            // 【必须说清「不删人偶」】否则用户会以为删组等于删角色，不敢点。
            message = "只会删掉「" + del + "」这个分组，组里的人偶会回到「未分组」，"
                + "人偶本身、聊天记录、角色设定全部保留。",
            confirmText = "删除",
            dismissText = "取消",
            confirmDestructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                if (del != null) {
                    DollGroups.remove(act, del)
                    deleting = null
                    tick++
                    DollState.bump()
                }
            },
        )

        // 移动人偶
        val moving = movingDoll
        if (moving != null) {
            val doll = DollStore.byId(act, moving)
            QzxDialog(
                visible = true,
                title = if (doll == null) "移动人偶" else "把「" + doll.name + "」放进",
                dismissText = "取消",
                onDismiss = { movingDoll = null },
            ) {
                QzxList {
                    section(id = "move-pick") {
                        // 【反馈】移动后立刻关窗并刷新；没有成功提示是刻意的 ——
                        //   列表里的分组归属会立即变化，那就是最直接的反馈。
                        //   （这里保留旧版的 toast，是因为弹窗一关就看得到的新位置在别的卡里。）
                        // 【选中态 = 对勾图标】旧 `CheckRow(selected)` 的等价物。
                        for (n in allNames) {
                            val cur = doll != null && doll.group == n
                            item(n, iconRes = if (cur) icCheck else null).onClick {
                                DollGroups.assign(act, moving, n)
                                movingDoll = null
                                tick++
                                DollState.bump()
                                HomeNotice.show(
                                    act,
                                    "已移动",
                                    "「" + (doll?.name ?: "") + "」现在在「" + n + "」。"
                                )
                            }
                        }
                        val noneCur = doll != null && doll.group.isEmpty()
                        item(
                            DollStore.GROUP_NONE,
                            iconRes = if (noneCur) icCheck else null
                        ).onClick {
                            DollGroups.assign(act, moving, "")
                            movingDoll = null
                            tick++
                            DollState.bump()
                            HomeNotice.show(
                                act,
                                "已移出分组",
                                "「" + (doll?.name ?: "") + "」回到了未分组。"
                            )
                        }
                        if (allNames.isEmpty()) {
                            note("还没有分组，先在右上角建一个。")
                        }
                    }
                }
            }
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
