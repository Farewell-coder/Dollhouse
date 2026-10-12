package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.pet.GestureActions
import com.dollhouse.app.pet.GestureCore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack

/**
 * 【职责】「操作」子页：这只人偶的四个手势各自触发哪些动作。
 *
 * 【与原设置页的关系】r10 之前这套多选挂在设置页的「操作」卡片里，全局一份；
 *   现在改成「每只人偶各存各的」（见 [DollProfile.gestureActions]），入口移到本页。
 *   多选项的视觉口径与旧弹窗逐项一致（[DhKit.CheckRow] 就是照旧 `optionRow` 复刻的），
 *   所以从老用户视角看只是「换了个地方」，不是「换了个东西」。
 *
 * 【为什么写盘后要 tick++】[DollProfile] 不是 Compose 状态，写完必须手动触发重组，
 *   否则返回后行尾的「单击 交谈 + 继续」还是旧文案。
 */
object DollGesturePage {
    /** 页面 key 前缀：`doll:gesture:<dollId>`。 */
    const val PREFIX = "doll:gesture:"

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
        var tick by remember(id) { mutableStateOf(0) }
        var picking by remember(id) { mutableStateOf<String?>(null) }

        DhKit.Page(
            title = "操作",
            sub = "手势响应",
            onBack = { PageStack.pop(act) }
        ) {
            DhForm.Note("点一条手势，勾选它要触发的动作；可多选，勾「无」表示清空。")
            for (g in GestureCore.GESTURES) {
                val set = remember(id, tick, g) { DollProfile.gestureActions(act, id, g) }
                DhKit.ActionRow(
                    label = GestureActions.gestureName(g),
                    trailing = GestureActions.label(set)
                ) { picking = g }
            }
        }

        val cur = picking
        if (cur != null) {
            PickDialog(
                act = act,
                id = id,
                gesture = cur,
                onClose = { picking = null },
                onChanged = { tick++ }
            )
        }
    }

    /** 单个手势的多选弹窗：无 / 交谈 / 聊天 / 继续 / 暂离，点一项立即落盘并就地刷新勾选。 */
    @Composable
    private fun PickDialog(
        act: MainActivity,
        id: String,
        gesture: String,
        onClose: () -> Unit,
        onChanged: () -> Unit
    ) {
        // 弹窗内部自己也要一个重读信号：勾选后不重建整个页面，只让弹窗里的勾动起来。
        var tick by remember(gesture) { mutableStateOf(0) }
        val keys = arrayOf(
            null, GestureActions.A_TALK, GestureActions.A_CHAT,
            GestureActions.A_CONTINUE, GestureActions.A_AWAY
        )
        val labels = arrayOf(
            GestureActions.NONE_LABEL, GestureActions.TALK_LABEL,
            GestureActions.CHAT_LABEL, GestureActions.CONTINUE_LABEL,
            GestureActions.AWAY_LABEL
        )
        val set = remember(gesture, tick) { DollProfile.gestureActions(act, id, gesture) }

        DhForm.Alert(
            title = GestureActions.gestureName(gesture),
            onDismiss = onClose
        ) {
            for (i in keys.indices) {
                val key = keys[i]
                val selected = if (key == null) set.isEmpty() else set.contains(key)
                DhKit.CheckRow(text = labels[i], selected = selected) {
                    val now = DollProfile.gestureActions(act, id, gesture)
                    if (key == null) {
                        now.clear()
                    } else if (now.contains(key)) {
                        now.remove(key)
                    } else {
                        now.add(key)
                    }
                    DollProfile.setGestureActions(act, id, gesture, now)
                    tick++
                    onChanged()
                }
            }
        }
    }
}
