package com.dollhouse.app.ui.doll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dollhouse.app.MainActivity
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhTokens

/**
 * 【职责】「暂离」与「回归时间」的**弹窗**形态（r12 改）。
 *
 * 【需求原文】「这两个设置不需要展开界面，像第二幅图一样，并且标注一下输入的时长
 *   是以秒为单位的」—— 从「整页」改成「标题 + 输入框 + 按钮行」的小弹窗，
 *   输入框标签统一带「（秒）」。
 *
 * 【保存语义·定案 Q1=A】边打字边落盘，保存 = 确认并关闭。
 *   保留保存键的理由：即便实现上已经在存，这个键能给「我改过没有」一个明确回执。
 *
 * 【按钮组合·定案 Q28】取消 / 恢复 / 保存。
 *   取消 = 直接关（不点保存也不会丢，因为已经在存）；
 *   恢复 = 拨回出厂默认（暂离 60 秒 / 回归 0），立刻就存；
 *   保存 = 关掉弹窗。
 *
 * 【校验】非法输入只提示、不写盘，保留用户已输入的内容让他改。
 */
object DollNumDialog {

    /** 弹窗种类。 */
    const val KIND_AWAY = 0
    const val KIND_RETURN = 1

    /**
     * 弹出对话框。
     *
     * @param onDone 关闭回调（保存 / 取消都会调），用于刷新调用页的 trailing 文案。
     */
    @JvmStatic
    fun show(act: MainActivity, id: String, kind: Int, onDone: (() -> Unit)?) {
        val title = if (kind == KIND_AWAY) "暂离" else "回归时间"
        var dlgRef: android.app.Dialog? = null
        dlgRef = DhForm.show(act, title) {
            NumBody(act, id, kind) {
                dlgRef?.dismiss()
                onDone?.invoke()
            }
        }
    }

    @Composable
    private fun NumBody(act: MainActivity, id: String, kind: Int, onClose: () -> Unit) {
        val away = kind == KIND_AWAY
        val min = if (away) PetPrefs.AWAY_SECONDS_MIN else DollProfile.RETURN_MIN
        val max = if (away) PetPrefs.AWAY_SECONDS_MAX else DollProfile.RETURN_MAX
        val def = if (away) PetPrefs.AWAY_SECONDS_DEFAULT else DollProfile.RETURN_DEFAULT

        var text by remember(id, kind) {
            mutableStateOf(
                (if (away) DollProfile.awaySeconds(act, id)
                else DollProfile.returnSeconds(act, id)).toString()
            )
        }
        var err by remember(id, kind) { mutableStateOf<String?>(null) }

        /**
         * 【r13 修 bug·保存/取消没生效】校验只写本地，**不落盘**。
         *
         * 【旧实现的坑】原来这里一合法就 `setAwaySeconds` / `setReturnSeconds`，
         *   于是「边打字边存」——点「取消」也留痕，取消按钮形同虚设。
         *   用户定案：「编辑里面有保存和取消按钮的 得生效 取消之后就不要记录那些改动了」。
         *   现在只做校验 + 回显错误，真正的写盘在下面「保存」里。
         *
         * @return 解析出的合法值；非法返回 null。
         */
        fun parse(s: String): Int? {
            val n = s.trim().toIntOrNull()
            if (n == null || n < min || n > max) {
                err = "请输入 " + min + "～" + max + " 的整数"
                return null
            }
            err = null
            return n
        }

        /** 把当前草稿写盘；非法则不写并返回 false（弹窗不关，让用户改）。 */
        fun commitNow(): Boolean {
            val n = parse(text) ?: return false
            if (away) {
                DollProfile.setAwaySeconds(act, id, n)
            } else {
                DollProfile.setReturnSeconds(act, id, n)
            }
            return true
        }

        DhForm.Input(
            value = text,
            onValueChange = {
                text = it
                parse(it)
            },
            label = "时长（秒）",
            hint = if (away) "建议 0～60 秒" else "0 表示每次都回初始位置",
            keyboard = KeyboardType.Number
        )
        if (err != null) {
            DhForm.Note(err!!)
        }
        DhForm.Note(if (away) "暂离后到点会自动恢复；填 0 表示不自动恢复。" else "关闭人偶超过该秒数后再启动，会回到初始位置；填 0 表示每次都回。")

        // 按钮行：取消 / 恢复 / 保存。
        // 【r12-UI 为什么改成文字药丸】本工程的弹窗家族（[DhForm.Alert]）统一用文字药丸；
        //   这里原来用三个「图标圆球」，同一个家族里混两套按钮语言就是「突兀」。
        //   现在复用同一个 [DhForm.DialogButton]（实色 = 主操作，描边 = 次操作）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 取消 = 直接关，不写任何键（用户定案：取消之后不记录改动）。
            DhForm.DialogButton(text = "取消", primary = false) { onClose() }
            Spacer(Modifier.width(10.dp))
            DhForm.DialogButton(text = "恢复", primary = false) {
                // 【恢复】只把草稿拨回出厂默认，**不落盘** —— 要落盘得再点「保存」。
                text = def.toString()
                err = null
            }
            Spacer(Modifier.width(10.dp))
            DhForm.DialogButton(text = "保存", primary = true) {
                // 校验通过才关窗；非法时保持打开，让用户看到错误提示。
                if (commitNow()) {
                    onClose()
                }
            }
        }
    }

    /* 【r12-UI】原 CircleButton（图标圆球）已删：弹窗按钮统一走 [DhForm.DialogButton] 文字药丸。 */
}