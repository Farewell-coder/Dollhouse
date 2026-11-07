package com.dollhouse.app.ui.feedback

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dollhouse.app.ui.compose.DhTokens

/** Material3 原生按住提示；松手、取消、离开组合由原组件清理，不拦截按钮点击。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HintTooltip(text: String, content: @Composable () -> Unit) {
    if (text.isBlank()) {
        content()
        return
    }
    val colors = DhTokens.colors
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(containerColor = colors.sheetUp, contentColor = colors.title) {
                Text(text)
            }
        },
        state = rememberTooltipState()
    ) {
        content()
    }
}

/** 每个确认框实例持有一次性提交状态；取消与外部关闭均不执行确认回调。 */
@Composable
fun rememberConfirmGuard(): ConfirmGuard = remember { ConfirmGuard() }

class ConfirmGuard {
    private var submitted = false

    /** 只有第一次调用执行，重复点击直接忽略。 */
    fun runOnce(action: () -> Unit): Boolean {
        if (submitted) return false
        submitted = true
        action()
        return true
    }
}