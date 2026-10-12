package com.dollhouse.app.ui.doll

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack

/**
 * 【职责】「回归时间」子页：人偶消失多久之后，再出现时要回到初始位置。
 *
 * 【机制全貌】桌宠侧的实现在 [com.dollhouse.app.pet.PetWindow]：
 *   - 暂离路径：`enterAway()` 打点 → `exitAway()` 算出隐显时长 → 满足条件则先
 *     `applyHomePivot()` 回到初始位置再显形；
 *   - 关闭路径：关人偶时 `PetPrefs.setHiddenAt` 记下时刻，下次启动时由
 *     [com.dollhouse.app.pet.PetWindow.attach] 按同一条件决定开场位置。
 *
 * 【0 的语义（r11 改）】0 = **每次出现都回初始位置**（不再是「不触发」）。
 *   N 秒 = 消失超过 N 秒才回，否则在原地出现。
 *
 * 【本页没有保存按钮】输入合法即落盘；返回时再兜一次提交，用户不需要额外确认。
 */
object DollReturnPage {
    /** 页面 key 前缀：`doll:return:<dollId>`。 */
    const val PREFIX = "doll:return:"

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
        val min = DollProfile.RETURN_MIN
        val max = DollProfile.RETURN_MAX
        var text by remember(id) { mutableStateOf(DollProfile.returnSeconds(act, id).toString()) }
        var err by remember(id) { mutableStateOf<String?>(null) }

        // 与暂离页同一约定：合法即写，非法只提示不落盘（保留用户已输入的内容让他改）。
        fun commit(s: String) {
            val n = s.trim().toIntOrNull()
            if (n == null || n < min || n > max) {
                err = "请输入 " + min + "～" + max + " 的整数"
                return
            }
            err = null
            DollProfile.setReturnSeconds(act, id, n)
        }

        DhKit.Page(
            title = "回归时间",
            sub = "消失多久后回到初始位置",
            // 【r11 无保存按钮】返回时自动提交一次：输入过程中已即时落盘，这里是兜底，
            //   保证「改了没生效」不可能发生。
            onBack = {
                commit(text)
                PageStack.pop(act)
            }
        ) {
            DhForm.Input(
                value = text,
                onValueChange = {
                    text = it
                    commit(it)
                },
                label = "时长（秒）",
                hint = "0 表示每次都回初始位置",
                keyboard = KeyboardType.Number
            )
            if (err != null) {
                DhForm.Note(err!!)
            }
            DhForm.Note("关闭人偶后，超过该秒数再启动，人偶会回到「初始位置」；未超过则在原地出现。")
            DhForm.Note("填 0 表示每次都回初始位置（不论消失多久）。")
            DhForm.Note("「暂离」不算消失，不会重置这个计时。")
            DhForm.Note("需要先设置「初始位置」，否则到点也无处可回。")
        }
    }
}