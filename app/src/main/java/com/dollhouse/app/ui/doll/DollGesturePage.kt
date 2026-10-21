package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.pet.GestureActions
import com.dollhouse.app.pet.GestureCore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.theme.Icons

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
    /**
     * 【r16·手势图标】四种手势各配一枚语义对得上的 Lucide 图标。
     *
     * 【为什么写在本文件而不是 GestureCore】GestureCore 是全工程唯一「不依赖任何
     *   Android 类」的纯逻辑类（可用 kotlinc 单独编译），把 R 常量引进去就破坏了那条
     *   边界。图标属于「展示」，归 UI 层，故放在页面里。
     *
     * 【为什么不重复】四枚图标几何各不相同，且与页面其他位置用到的图标也不重：
     *   单击=鼠标指针（一次点按）、双击=鼠标指针加圈（点在点上）、
     *   三击=点击手势（多点）、长按=手指按住。
     */
    @JvmStatic
    private fun gestureIcon(gesture: String): Int {
        return when (gesture) {
            GestureCore.G_SINGLE -> Icons.IC_MOUSE_POINTER_2
            GestureCore.G_DOUBLE -> Icons.IC_CROSSHAIR
            GestureCore.G_TRIPLE -> Icons.IC_HAND
            else -> Icons.IC_CLOCK_3
        }
    }
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
                    iconRes = gestureIcon(g),
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
        /**
         * 【r16】**v0.1.0 收窄：只列「手动动作」组**。
         *
         * 【为什么】用户定案：「只有我加入手动触发的动作，才能在那个操作组里编辑哪个操作对应哪个动作」。
         *   随机动作是系统按 13~30 秒自己播的，把它绑到手势上没有意义
         *   （而且会让用户以为「绑了它就不再随机」）。固定三格（常驻/贴边/拖拽）
         *   由位置与拖动自动触发，也不该出现在手势里。
         */
        val playable = remember(tick, id) {
            val out = ArrayList<DollAnim.Action>()
            for (a in DollAnim.all(act, id)) {
                if (a.group != DollAnim.GROUP_MANUAL) {
                    continue
                }
                // 【可播放 = 有帧或有入口资源】空壳播起来什么都不会发生；
                //   让用户绑一个「绑了等于没绑」的选项，比不给他这个选项更糟。
                if (a.frames.isNotEmpty() || a.assetPath.isNotEmpty()) {
                    out.add(a)
                }
            }
            out
        }

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

            // ---------- r16：播放动作 ----------
            //
            // 【为什么放同一个弹窗而不是新开一页（任务书 §3.8 明令）】
            //   「在现有弹窗里增加「播放动作」选项组（**不新建独立页面**）」
            //   理由也实在：手势配置的全部信息就在这一个弹窗里，
            //   跳出去再跳回来，用户会忘了自己刚在配哪个手势。
            DhForm.Gap()
            DhKit.SectionLabel("播放动作（可多选，只播最后一个）")
            if (playable.isEmpty()) {
                DhForm.Note(
                    "这只人偶还没有可播放的手动动作。到「动作」页新建一个（放进「手动动作」组）" +
                        "并给它加上帧，它就会出现在这里。"
                )
            } else {
                for (a in playable) {
                    val value = GestureActions.actValue(a.id)
                    val on = set.contains(value)
                    DhKit.CheckRow(
                        // 【为什么标注「默认」】默认动作在待机时本来就会播；
                        //   用户可能只是想把它也绑到某个手势上（比如点一下重播），
                        //   标出来能避免「怎么两个一样的」这种困惑。
                        text = a.name + (if (a.isDefault) "（默认）" else ""),
                        selected = on
                    ) {
                        val now = DollProfile.gestureActions(act, id, gesture)
                        if (on) {
                            now.remove(value)
                        } else {
                            now.add(value)
                        }
                        DollProfile.setGestureActions(act, id, gesture, now)
                        tick++
                        onChanged()
                    }
                }
                DhForm.Note(
                    "绑了动作后，如果同一条手势还勾了「交谈」，两个都会生效：" +
                        "一边弹提示、一边播动作。"
                )
            }
        }
    }
}
