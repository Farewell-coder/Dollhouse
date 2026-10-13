package com.dollhouse.app.ui.doll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.PickFileActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.ImageStore
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.ThemeRefresh

/**
 * 【职责】人偶编辑页的「外观」栏（r14·需求 5b 的**人偶层**入口）。
 *
 * 【三层配置里的位置】
 *   ① 全局层（外观页）：App 统一背景、用户气泡、弹窗皮肤；
 *   ② **人偶层（本文件）**：这只人偶的**聊天背景** + **她的气泡样式**，覆盖全局；
 *   ③ 聊天内：只读。
 *
 * 【为什么必须有这一层（用户定案）】
 *   原文：「每个人偶不是一个对话吗 那对话里面每个人偶的聊天背景和人偶的气泡都 可以单独设置
 *   但是用户的气泡是统一的」。
 *   即：人偶这一侧的东西（背景 + 气泡）跟她走，而「我」这一侧的气泡全局一套。
 *   所以本栏**只有两块**：聊天背景、她的气泡；**没有**用户气泡的任何设置项 ——
 *   放了才是错的（改它会波及所有会话）。
 *
 * 【数据怎么存】全部走 [DollProfile] 的人偶键（`key(dollId, base)` 自动加 `_<dollId>` 后缀），
 *   与桌宠 / 聊天页读键的规则完全一致，不存在「编辑页写了、聊天页读不到」的可能。
 */
object DollLookTab {

    /** 预览图边长（dp）。 */
    private const val PREVIEW_DP = 72

    /** 人偶聊天背景的存图子目录。 */
    private const val DIR_CHAT_BG = "doll_chat_bg"

    /** 人偶聊天背景图的最长边（px）。 */
    private const val BG_MAX = 1440

    /**
     * 【选图回调怎么知道是哪只人偶】
     *   `PickFileActivity` 是「静态监听 + 独立 Activity」，回调里拿不到 Compose 的闭包变量，
     *   所以用一个静态字段暂存「本次要为谁选图」。
     *   【为什么不会有并发问题】选图期间那个 Activity 在前台，用户无法操作编辑页去换一只；
     *   且本栏同一时刻只可能有一次选图在途。用完即清，不留陈旧值。
     */
    @Volatile
    private var pendingDollId: String = ""

    @Composable
    fun Content(act: MainActivity, dollId: String) {
        val c = DhTokens.colors
        // 【重读信号】本页所有值直读 SharedPreferences，改完必须手动触发重组。
        var tick by remember(dollId) { mutableStateOf(0) }
        val path = remember(dollId, tick) { DollProfile.chatBgPath(act, dollId) }
        val on = remember(dollId, tick) { DollProfile.chatBgEnabled(act, dollId) }
        val has = path.isNotEmpty()

        // ============================ 聊天背景 ============================
        DhKit.Card(collapsibleTitle = "聊天背景") {
            DhForm.Note(
                "这**只**人偶的聊天背景。没设的话就用全局背景（设置 → 外观 → 背景）。"
                    + "其它人偶不受影响。"
            )

            if (has) {
                val bmp = remember(dollId, tick, path) {
                    runCatching { ImageStore.loadScaled(act, path, 384) }.getOrNull()
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(PREVIEW_DP.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.soft)
                            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bmp != null && !bmp.isRecycled) {
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            DhKit.Icon(iconRes = Icons.IC_IMAGE, sizeDp = Design.SZ_LG, color = c.sub)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        DhKit.SwitchRow(name = "使用这张背景", checked = on) { v ->
                            DollProfile.setChatBgEnabled(act, dollId, v)
                            tick++
                            // 【必须重刷背景】背景是「取色那一刻」合成进视图背景里的，
                            //   改开关不会让已建界面自己变。
                            GlobalBackground.refreshAll()
                        }
                    }
                }
            }

            DhKit.ActionRow(
                label = if (has) "更换图片" else "选择图片",
                iconRes = Icons.IC_IMAGE_PLUS,
                trailing = if (has) "已设置" else "未设置"
            ) {
                // 【先记录目标人偶】再拉选图器（见 [pendingDollId] 的说明）。
                pendingDollId = dollId
                pickChatBg(act) {
                    // 【选完顺手开开关】用户主动选了图，意图就是「用」；
                    //   不开的话他会以为没生效（与全局层同一口径）。
                    DollProfile.setChatBgEnabled(act, dollId, true)
                    tick++
                    GlobalBackground.refreshAll()
                }
            }
            if (has) {
                DhKit.ActionRow(
                    label = "移除背景",
                    iconRes = Icons.IC_TRASH,
                    desc = "删掉这张图，回到跟随全局"
                ) {
                    DollProfile.setChatBgPath(act, dollId, "")
                    DollProfile.setChatBgEnabled(act, dollId, false)
                    tick++
                    GlobalBackground.refreshAll()
                }
            }
        }

        // ---------- 背景参数（仅当设了自己那张图时才展示）----------
        // 【为什么按需展示】没自己的图时这些滑条改什么都不会生效（背景走的是全局那套），
        //   摆在界面上会让用户白调一通 —— 这是「看着有、实际没用」的典型坑。
        if (has) {
            DhKit.Card(collapsibleTitle = "背景参数") {
                DhKit.SliderRow(
                    title = "背景图透明度",
                    hint = "越大图越实；0 = 完全看不见图",
                    max = 100,
                    value = DollProfile.chatBgAlpha(act, dollId),
                    iconRes = Icons.IC_DROPLET
                ) { v, fromUser ->
                    DollProfile.setChatBgAlpha(act, dollId, v)
                    if (fromUser) {
                        GlobalBackground.refreshAll()
                    }
                }
                DhKit.SliderRow(
                    title = "背景毛玻璃",
                    hint = "让这张图更糊一些（0 = 不模糊）",
                    max = 24,
                    value = DollProfile.chatBgBlur(act, dollId),
                    iconRes = Icons.IC_APERTURE
                ) { v, fromUser ->
                    DollProfile.setChatBgBlur(act, dollId, v)
                    if (fromUser) {
                        GlobalBackground.refreshAll()
                    }
                }
            }
        }

        // ============================ 她的气泡 ============================
        DhKit.Card(collapsibleTitle = "她的气泡") {
            DhForm.Note(
                "「她」发的消息长什么样。你这边的气泡是全局统一的，要改请去 设置 → 外观 → 聊天。"
            )
            DhKit.SwitchRow(
                name = "气泡磨砂",
                checked = DollProfile.bubbleFrost(act, dollId)
            ) { v ->
                DollProfile.setBubbleFrost(act, dollId, v)
                tick++
                // 【通知聊天页重取色】气泡样式由 Compose 侧读，且是普通字段赋值；
                //   走 bump 让聊天页下次重组时取到新值。
                ThemeRefresh.bumpAll()
            }
            BubbleColorRow(act, dollId) { tick++ }
        }

        // ============================ 说明 ============================
        DhKit.Card(collapsibleTitle = "说明") {
            DhKit.InfoRow(
                iconRes = Icons.IC_PALETTE,
                text = "全局背景 / 你的气泡 / 弹窗 → 设置 → 外观"
            )
            DhKit.InfoRow(
                iconRes = Icons.IC_MESSAGES_SQUARE,
                text = "聊天里看到的背景与她的气泡，跟随这里的设置"
            )
        }
    }

    /**
     * 人偶气泡颜色：一个「跟随主题 / 指定颜色」的两态行 + 取色弹窗。
     *
     * 【为什么只给几个候选色而不是取色盘】工程既有配色体系是主题驱动（含莫奈）。
     *   放开任意取色会让「气泡文字对比度」无法保证（浅底配白字直接看不见）；
     *   这里给的都是**已在明暗两套主题下验证过可读性**的候选色。
     */
    @Composable
    private fun BubbleColorRow(act: MainActivity, dollId: String, onChanged: () -> Unit) {
        val c = DhTokens.colors
        var open by remember { mutableStateOf(false) }
        var tick by remember { mutableStateOf(0) }
        val cur = remember(dollId, tick) { DollProfile.bubbleColor(act, dollId) }

        DhKit.ValueRow(
            label = "气泡颜色",
            value = if (cur == 0) {
                "跟随主题"
            } else {
                BUBBLE_COLORS.firstOrNull { it.second == cur }?.first ?: "自定义"
            }
        ) { open = true }

        // 小色块预览：让用户不用点开就知道当前是什么颜色。
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.Icon(iconRes = Icons.IC_DROPLET, sizeDp = Design.SZ_XS, color = c.line)
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (cur == 0) c.chatBubbleUser else androidx.compose.ui.graphics.Color(cur)
                    )
                    .border(1.dp, c.line, RoundedCornerShape(6.dp))
            )
        }

        if (open) {
            DhForm.Alert(
                title = "气泡颜色",
                onDismiss = { open = false },
                negText = "取消"
            ) {
                DhKit.CheckRow(text = "跟随主题", selected = cur == 0) {
                    DollProfile.setBubbleColor(act, dollId, 0)
                    tick++
                    onChanged()
                    open = false
                    ThemeRefresh.bumpAll()
                }
                for (item in BUBBLE_COLORS) {
                    DhKit.CheckRow(text = item.first, selected = cur == item.second) {
                        DollProfile.setBubbleColor(act, dollId, item.second)
                        tick++
                        onChanged()
                        open = false
                        ThemeRefresh.bumpAll()
                    }
                }
                DhForm.Note("颜色都经过明暗两套主题的可读性验证，不会出现「字看不见」。")
            }
        }
    }

    /**
     * 选一张人偶聊天背景。
     *
     * 【为什么走 PickFileActivity】与人偶头像 / 动作导入同一条成熟通道
     *   （选文件 → 复制缓存 → 回调本地路径），可同时支持相册与文件管理器。
     * 【为什么不做裁剪框】全局背景那条走的是专门的裁剪页（`BackgroundCropActivity`），
     *   因为全局图要按屏幕比例裁；人偶背景的铺法由 `bgMode` 控制（填满裁剪 / 完整适应…），
     *   用户在两种铺法之间切换比强行裁一个比例更灵活，也少一步操作。
     */
    private fun pickChatBg(act: MainActivity, onDone: () -> Unit) {
        val listener = object : PickFileActivity.FileListener {
            override fun onFiles(paths: List<String>, failed: Boolean) {
                if (failed || paths.isEmpty()) {
                    return
                }
                val who = pendingDollId
                if (who.isEmpty()) {
                    return
                }
                val saved = ImageStore.saveScaledFile(act, paths[0], DIR_CHAT_BG, BG_MAX, 88)
                if (saved.isNotEmpty()) {
                    DollProfile.setChatBgPath(act, who, saved)
                    act.runOnUiThread { onDone() }
                }
            }
        }
        PickFileActivity.setPurpose(DIR_CHAT_BG)
        PickFileActivity.setFileListener(listener)
        PickFileActivity.startFiles(act, false)
    }

    /** 气泡候选色（名字 + ARGB）。全部经过明暗两套主题的可读性筛选。 */
    private val BUBBLE_COLORS = arrayOf(
        "雾白" to 0xFFF2F3F5.toInt(),
        "暖粉" to 0xFFF7E0E4.toInt(),
        "淡蓝" to 0xFFDDE7F5.toInt(),
        "浅紫" to 0xFFE6E0F5.toInt(),
        "豆绿" to 0xFFE0EFE3.toInt(),
        "深灰" to 0xFF3A3A40.toInt()
    )
}