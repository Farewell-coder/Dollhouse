package com.dollhouse.app.ui.home

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.ui.chat.ChatListScreen
import com.dollhouse.app.ui.chat.ChatSessions
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.doll.DollState
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】人偶页 —— 底部导航的第 2 个 Tab。
 *
 * 【这一页是什么】本 App 没有通讯录（不申请 `READ_CONTACTS`，
 *   也不该为了一个 Tab 去申请 —— 那是「不引入不必要权限」的硬口径）。
 *   这里列的自始至终都是**人偶**：用户自己创建 / 导入的角色，正是他「聊天列表里那些对话的另一端」。
 *   所以本页展示人偶，并提供与它们相关的功能入口。
 *   【r13·改名】原叫「联系人」（借通讯录的交互形态），但那个名字会让人误以为
 *   能看到手机通讯录里的真人 —— 按用户要求统一改称「人偶」。
 *
 * 【为什么不做成 DollListScreen 的复制品】`DollListScreen` 是「管理页」：
 *   网格卡片 + 启停 / 编辑 / 更多，密度高、偏设置。而本页是**浏览页**：
 *   一列一行的人偶条目，点一下就开聊 —— 与聊天列表形成「角色和对话」两个视角。
 *   两者的信息架构与用途不同，故各自成立；共用的数据源都是 [DollStore]。
 *
 * 【状态刷新】人偶数据由 [DollState.rev] 广播；本页与聊天列表同源，
 *   在人偶页启用 / 删除人偶后，切回聊天列表也能看到变化。
 */
object ContactScreen {

    /** 搭出人偶页根视图（供 [HomeUi.apply] 装配进底座）。 */
    @JvmStatic
    fun buildPage(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @Composable
    private fun Content(act: MainActivity) {
        val c = DhTokens.colors
        // 【数据】人偶列表按变更信号重算（与 DollListScreen 同一套机制）。
        val rev = DollState.rev
        val dolls = remember(rev) { DollStore.all(act) }

        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- 顶栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // 【r13·改名】原「联系人」→「人偶」。
                    //   需求原文：「联系人按钮改成 人偶 下面的也改掉了 我要换描述了 不要叫联系人了」。
                    text = "人偶",
                    modifier = Modifier.weight(1f),
                    color = c.title,
                    fontSize = 26.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
                // 新手偶：建一个新角色。
                DhKit.IconButton(Icons.IC_USER_PLUS, Design.SZ_MD, c.title) {
                    DollListScreen.open(act)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = BottomNav.HEIGHT_DP.dp + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(dolls, key = { it.id }) { doll ->
                    ContactRow(
                        act = act,
                        doll = doll,
                        onClick = {
                            // 【点一下开聊】与人偶池的会话建立 / 切换对应关系后进聊天页。
                            openChatWith(act, doll.id)
                        }
                    )
                }
                // 【追加】底部固定给一条「管理人偶」的入口：
                //   本页是浏览视角，归档 / 封面 / 动作等管理功能仍在人偶管理页，
                //   这里给一条明确的路径，避免用户找不着。
                item {
                    Spacer(Modifier.height(4.dp))
                    DhKit.ActionRow(
                        label = "管理人偶",
                        iconRes = Icons.IC_LAYERS
                    ) {
                        DollListScreen.open(act)
                    }
                }
            }
        }
    }

    /** 一行人偶：头像 + 名字 + 启用状态 + 一句说明。 */
    @Composable
    private fun ContactRow(act: MainActivity, doll: DollStore.Doll, onClick: () -> Unit) {
        val c = DhTokens.colors
        val shape = RoundedCornerShape(14.dp)
        // 【头像是同步解码】封面已按最长边 1024 存，一张 4MB 级；这里只解码一次并缓存。
        //   几十只人偶时的总占用仍可控（列表最长也就几十行，Compose 只渲染可见项）。
        val cover = remember(doll.id, doll.cover) {
            runCatching { DollStore.coverOf(act, doll) }.getOrNull()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.card)
                .border(1.dp, c.line, shape)
                .pressable(onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像圆
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.soft),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null && !cover.isRecycled) {
                    androidx.compose.foundation.Image(
                        bitmap = cover.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    DhKit.Icon(
                        iconRes = if (doll.builtin) Icons.IC_WHALE else Icons.IC_USER_ROUND,
                        sizeDp = Design.SZ_LG,
                        color = c.sub
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = doll.name,
                        modifier = Modifier.weight(1f, fill = false),
                        color = c.title,
                        fontSize = 16.sp,
                        fontFamily = DhTokens.fontsBold,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (doll.builtin) {
                        Spacer(Modifier.width(6.dp))
                        Pill(text = "内置", color = c.sub)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (doll.enabled) "正在桌面上陪着你" else "未启用",
                    color = if (doll.enabled) c.acc else c.sub,
                    fontSize = 13.sp,
                    fontFamily = DhTokens.fonts,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            DhKit.Icon(iconRes = Icons.IC_CHEVRON_RIGHT, sizeDp = Design.SZ_SM, color = c.line)
        }
    }

    /** 小胶囊标签。 */
    @Composable
    private fun Pill(text: String, color: androidx.compose.ui.graphics.Color) {
        val c = DhTokens.colors
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(c.soft)
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text(text = text, color = color, fontSize = 10.sp, fontFamily = DhTokens.fonts)
        }
    }

    /**
     * 与某只人偶开聊：切到**她自己那条**会话，再进聊天页。
     *
     * 【r13·一人偶一对话·修 bug】原来调 `ensurePet()` —— 那拿到的是人偶池的**通用种子会话**
     *   （`pet`），跟点的那只人偶没关系。结果是「点谁都是同一个对话框」，
     *   与「一个人偶对应一个对话框」直接冲突。
     *   现在改走 `ensureForDoll(dollId)`，拿到的必然是 `d_<dollId>` 这条专属会话。
     *
     * 【为什么要「切池」】人偶池与自聊池是两个独立的历史库（见 [ChatSessions] 头注释）。
     *   从人偶页点进来，用户意图明确是「和她聊」，所以先把当前池切到 pet，
     *   再从聊天列表页的入口统一打开 —— 复用同一套打开逻辑，不在两处各写一遍。
     */
    private fun openChatWith(act: MainActivity, dollId: String) {
        try {
            ChatSessions.setPool(act, ChatSessions.POOL_PET)
            val id = ChatSessions.ensureForDoll(act, dollId)
            ChatListScreen.openConv(act, id)
        } catch (ignored: Throwable) {
        }
    }
}
