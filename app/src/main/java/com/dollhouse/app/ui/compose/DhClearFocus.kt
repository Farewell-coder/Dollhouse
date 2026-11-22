package com.dollhouse.app.ui.compose

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 【职责】点空白处清输入焦点 + 收键盘。
 *
 * 【参考】MAA-Meow `presentation/components/ClearFocus.kt`（用户授权参考）。
 *   那个实现要处理 MAA 特有的两件事：**悬浮窗里的原生 EditText**（所以要走 View 层
 *   `findFocus()` + `onCheckIsTextEditor()` + 坐标命中判定），以及 **Pager 预加载**
 *   （滚动清焦不能误触发预加载）。本工程**两种场景都没有**：输入框全是 Compose，
 *   也没有 Pager。所以只保留它的**交互语义**，实现换成 Compose 标准 API。
 *
 * 【语义】点「不属于任何输入框」的位置时：
 *   1. `FocusManager.clearFocus()` —— 让正在编辑的输入框失去焦点（光标消失）；
 *   2. `SoftwareKeyboardController.hide()` —— 键盘收起，把屏幕下方还给内容。
 *
 * 【为什么用 detectTapGestures 而不是 clickable】`clickable` 会带 `MutableInteractionSource`
 *   与语义（读屏会把它念成一个按钮），而"空白处"不该是一个可聚焦的控件。
 *   `detectTapGestures` 只负责收手势，不产生任何可访问性噪音。
 *
 * 【挂在哪】挂在**页面级容器**（而不是某个具体输入框）上，例如搜索页 / 编辑页的
 *   根 Column。子控件的点击**先**被它们自己消费（输入框聚焦、按钮响应），
 *   只有落到空白处的点击才会走到这里 —— 所以不会抢输入框自己的聚焦。
 *
 * 【与 reduceMotion 的关系】本组件没有动画，不受该闸门影响。
 */
@Composable
fun Modifier.clearFocusOnBlankTap(): Modifier {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return pointerInput(Unit) {
        detectTapGestures {
            // 先收键盘再清焦点：反过来时，个别 ROM 会在焦点仍停留的瞬间把键盘又拉起来。
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
}
