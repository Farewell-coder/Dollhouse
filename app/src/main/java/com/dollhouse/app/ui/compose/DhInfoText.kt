package com.dollhouse.app.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】把「一长就占好几行」的说明文字折叠成一个问号图标，点了才展开。
 *
 * ## 抄自哪、为什么抄
 *
 * 原型是 MAA（`maa-meow`）的 `SettingRow.kt:139-171`：
 * 标题行右侧放一枚问号，点开在下方展开说明、点第二下收起。
 * 它解决的是我们这边一个具体痛点 —— **行内 `desc` 一长就吃掉 2~3 行**：
 * 例如「背景图不透明度」的 hint 实测 **36 视觉字**（`HomeUi.alphaHint` 生成），
 * 360dp 屏勉强一行、320dp 必折行；而这类说明**用户读一次就够了**，
 * 每次滚动都看见它，等于用一次性信息持续占用版面。
 *
 * ## 与 MAA 的差异（本地化，不是照抄）
 *
 * | | MAA | 本实现 |
 * |---|---|---|
 * | 配色 | `MaterialTheme.colorScheme.primary / onSurfaceVariant` | `DhTokens.colors.acc / sub` |
 * | 尺寸 | 裸 `28.dp / 14.dp` | `DhDesign.Space.*` + `DhDesign.Size.iconSm` |
 * | 动效 | `MaaAnimatedVisibility` + `expandVertically()` 默认曲线 | `DhAnim.CARD_EXPAND` 等四个 token（**自带 reduceMotion 降级**） |
 * | 展开态存储 | `rememberSaveable(title)` | `rememberSaveable(key)`（key 由调用方给稳定的） |
 *
 * ## 状态为什么要提升到调用方
 *
 * 标题行里的问号与下方的说明正文必须共享**同一个**展开态。若两个组件各自
 * `rememberSaveable`，它们会各存各的 —— 点问号而说明不动。所以状态由
 * [rememberInfoExpanded] 统一创建，两个组件都收 `expanded` 参数。
 *
 * ## 组合用法
 *
 * ```kotlin
 * val expanded = rememberInfoExpanded("appearance.bgAlpha")   // 稳定 key
 * // 标题行右侧
 * DhInfoToggle(expanded = expanded.value) { expanded.value = !expanded.value }
 * // 标题行下方
 * DhInfoText(text = "背景图不透明度　（图偏淡，文字最稳）", expanded = expanded.value)
 * ```
 *
 * 也可以用 [DhInfoSection] 一次画完「标题 + 问号 + 可展开说明」。
 */

/**
 * 创建一份「可展开说明」的状态，跨配置变更（旋转 / 深色切换重建）保留展开态。
 *
 * 【为什么不用普通 `remember`】设置页在系统日夜切换时会重建整棵树
 * （`ThemeRefresh` 那套）。用 `remember` 的话，用户刚点开的说明会自己收起来，
 * 看起来像「点了没反应」。这与 MAA 选 `rememberSaveable` 的理由一致。
 *
 * @param key 稳定标识（行标题 / rowId）。**跨重组必须不变**，
 *   否则 `rememberSaveable` 会把它当成新状态、展开态重置。
 */
@Composable
fun rememberInfoExpanded(key: String): MutableState<Boolean> =
    rememberSaveable(key) { mutableStateOf(false) }

/**
 * 问号开关（对应 MAA 的 `SettingInfoToggle`）。
 *
 * 【为什么视觉 28dp / 图标 16dp】视觉上它是一枚不抢眼的辅助标记，
 * 但可点区域由 `pressable` 内置的 48dp 命中区兜底（见 `DhKit.pressable` 注释）。
 * **视觉小、可点大**，与本工程全部图标按钮同口径。
 *
 * 【为什么展开时换强调色】折叠态是「可能有解释」的弱提示（sub）；
 * 展开后变成「解释正显示着」的状态指示（acc）—— 与选中态药丸用底色表达同一思路。
 *
 * 【无障碍】默认名随 [expanded] 变化（「展开说明」/「收起说明」），
 * 读屏用户能听出「现在点会发生什么」，而不是笼统一个「按钮」。
 *
 * @param expanded 当前是否展开
 * @param onToggle 点击回调
 * @param a11yName 覆盖默认无障碍名（一般不需要传）
 */
@Composable
fun DhInfoToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    a11yName: String? = null
) {
    val c = DhTokens.colors
    val name = a11yName ?: if (expanded) "收起说明" else "展开说明"
    Box(
        modifier = modifier
            .size(INFO_TOGGLE_DP.dp)
            .clip(CircleShape)
            // 【为什么加浅底】MAA 原版是无底的裸图标；本工程图标按钮的统一语言是
            //   「圆形浅底」（见 DhKit.IconButton 的注释）。这里跟随本工程，不照抄。
            .background(c.soft)
            // 【顺序】语义在 pressable 之前 —— 描述「这块可点区域是什么」，
            //   与手势处理无关（与 DhKit.CircleButton 同一写法）。
            .semantics { contentDescription = name }
            .pressable(onToggle),
        contentAlignment = Alignment.Center
    ) {
        DhKit.Icon(
            iconRes = Icons.IC_CIRCLE_HELP,
            sizeDp = DhDesign.Size.iconSm.value,
            color = if (expanded) c.acc else c.sub
        )
    }
}

/**
 * 可展开的说明正文（对应 MAA 的 `SettingInfoText`）。
 *
 * 【动效必须走 DhAnim】展开 `DhAnim.CARD_EXPAND`（`Motion.enter` = 220ms，
 * `LinearOutSlowInEasing`）、收起 `DhAnim.CARD_COLLAPSE`（`exit` = 180ms，加速曲线），
 * 透明度配 `CARD_FADE_IN` / `CARD_FADE_OUT`。
 * **四个 token 都自带 `reduceMotion()` 降级** —— 系统开「移除动画」时直接 snap。
 * 内联写 `tween(220)` 会绕过这层降级，属于无障碍回归，所以不内联。
 *
 * 【可以很长】这正是本组件存在的理由：不必再手动折行、也不必截断。
 * 若调用方给的是超长单段文本，这里 `softWrap` 默认 true 会自然折行。
 *
 * @param text 说明正文
 * @param expanded 是否展开
 */
@Composable
fun DhInfoText(
    text: String,
    expanded: Boolean,
    modifier: Modifier = Modifier
) {
    val c = DhTokens.colors
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(
            animationSpec = DhAnim.CARD_EXPAND,
            expandFrom = Alignment.Top
        ) + fadeIn(animationSpec = DhAnim.CARD_FADE_IN),
        exit = shrinkVertically(
            animationSpec = DhAnim.CARD_COLLAPSE,
            shrinkTowards = Alignment.Top
        ) + fadeOut(animationSpec = DhAnim.CARD_FADE_OUT)
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DhDesign.Space.xs.dp, bottom = DhDesign.Space.xs.dp),
            color = c.sub,
            // 【字号走 sub 档（13sp）而不是 caption(12)】说明是要读的正文级信息，
            //   12sp 在浅底上偏小；13sp 正是 `DhDesign.Type` 里「说明文字」的既定档。
            fontSize = UiKit.FS_SUB.sp,
            // 【行高必须给】12/13sp 不给行高时会按字体默认 ~1.2× 排，比令牌的
            //   1.54× 紧 30% —— 多行说明会挤成一片（E16 规则就是为这个加的）。
            lineHeight = DhTokens.type.sub.lineHeight,
            fontFamily = DhTokens.fontsBody
        )
    }
}

/**
 * 便捷重载：一次画完「标题行 + 问号 + 可展开说明」。
 *
 * 【什么时候用它】当这一行**不是** `DhRow`（比如它嵌在卡片里的自定义 Row，
 * 或调用方还要在标题旁边放滑条 / 数值）时。若这一行本来就是标准设置行，
 * 等 `DhRow` 的 `info` 参数接线后直接用那个，不必手拼。
 *
 * 【为什么 `info == null` 时不画问号】与 MAA 一致：没有解释就不该给开关 ——
 * 一个点开来是空的问号，比没有问号更糟。
 *
 * @param title 标题
 * @param info 折叠起来的长说明；null = 不折叠任何东西
 * @param stateKey 展开态的稳定 key（跨重组不变）
 */
@Composable
fun DhInfoSection(
    title: String,
    info: String?,
    stateKey: String,
    modifier: Modifier = Modifier
) {
    val c = DhTokens.colors
    val expanded = rememberInfoExpanded(stateKey)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 【weight(fill = false) 与 MAA 一致】标题按内容宽、不撑满 ——
            //   否则问号被推到最右，短标题行里两者离得很远、看不出是一组。
            Text(
                text = title,
                modifier = Modifier.weight(1f, fill = false),
                color = c.sub,
                fontSize = UiKit.FS_SUB.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = DhTokens.fonts
            )
            if (info != null) {
                DhInfoToggle(expanded = expanded.value) { expanded.value = !expanded.value }
            }
        }
        if (info != null) {
            DhInfoText(text = info, expanded = expanded.value)
        }
    }
}

/* ============================ 局部常量 ============================ */

/**
 * 问号开关的视觉直径：28dp。
 *
 * 【为什么这不违反「图标只允许 5 档」】那 5 档约束的是**图标本体**
 * （14/16/20/24/28）；28 是**容器**尺寸，里面画的是 `Size.iconSm`（16dp）。
 * 与 `DhKit.IconButton`（容器 48 / 图标 20）是同一套「容器大于图标」的思路。
 */
private const val INFO_TOGGLE_DP = 28