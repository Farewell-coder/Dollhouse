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
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack

/**
 * 【职责】「暂离」子页：这只人偶挂起多久后自动恢复。
 *
 * 【功能照旧】数值区间与旧设置页弹窗完全一致（[PetPrefs.AWAY_SECONDS_MIN] ~
 *   [PetPrefs.AWAY_SECONDS_MAX]，默认 60）。差别只在存储位置：以前是全局一份，
 *   现在按 dollId 分开（见 [DollProfile.awaySeconds]）。
 *
 * 【为什么改成整页而不是弹窗】r10 规格要求「点进去是新整页」；且弹窗里塞输入框
 *   在深色主题下要另配一套键盘遮罩，而整页有 [DhKit.Page] 统一处理顶栏与滚动。
 */
object DollAwayPage {
    /** 页面 key 前缀：`doll:away:<dollId>`。 */
    const val PREFIX = "doll:away:"

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
        val min = PetPrefs.AWAY_SECONDS_MIN
        val max = PetPrefs.AWAY_SECONDS_MAX
        var text by remember(id) { mutableStateOf(DollProfile.awaySeconds(act, id).toString()) }
        var err by remember(id) { mutableStateOf<String?>(null) }

        // 【为什么每敲一个字符就落盘】与旧弹窗的「保存」按钮语义不同：本页没有保存键，
        //   合法性即时判定，合法就立即写。用户返回时不需要再确认一次。
        fun commit(s: String) {
            val n = s.trim().toIntOrNull()
            if (n == null || n < min || n > max) {
                err = "请输入 " + min + "～" + max + " 的整数"
                return
            }
            err = null
            DollProfile.setAwaySeconds(act, id, n)
        }

        DhKit.Page(
            title = "暂离",
            sub = "自动恢复时长",
            onBack = { PageStack.pop(act) }
        ) {
            DhForm.Input(
                value = text,
                onValueChange = {
                    text = it
                    commit(it)
                },
                label = "时长（秒）",
                hint = "建议 0～60 秒",
                keyboard = KeyboardType.Number
            )
            if (err != null) {
                DhForm.Note(err!!)
            }
            DhForm.Note("暂离后到点会自动恢复；填 0 表示不自动恢复。")
            DhForm.Note("当前取值区间：" + min + "～" + max + " 秒。")
        }
    }
}