package com.dollhouse.app.ui.feedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ui.compose.DhTokens

/** 普通 Snackbar 展示；需要动作时由 AppMessageQueue 调用 SnackbarHostState。 */
@Composable
fun AppSnackbarHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = DhTokens.colors
    SnackbarHost(host, modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = colors.sheetUp,
            contentColor = colors.title,
            actionColor = colors.acc,
            dismissActionContentColor = colors.sub
        )
    }
}

/** 给已有页面叠加消息宿主，不重写页面 Scaffold，也不覆盖页面中央操作。 */
@Composable
fun FeedbackBox(
    host: SnackbarHostState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier) {
        content()
        AppSnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

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