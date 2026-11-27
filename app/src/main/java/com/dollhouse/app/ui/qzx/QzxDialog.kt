package com.dollhouse.app.ui.qzx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 【职责】确认对话框（QZX `QZXDialog` 的等价物，**数据式**，无标签页 / 无自定义布局）。
 *
 * ## 为什么是参数而不是 Builder 链
 * QZX 的文档说"对话框统一使用 Builder 模式"，`QzxList` 那边用的是建造器链（`.arrow().onClick{}`）。
 * 对话框这里**不需要链**：它没有"同一实体的多个可选属性要按顺序配置"的场景，一次性给全反而
 * 更清楚。所以走"参数即数据"，与 [QzxList] 同一条路线 —— **给数据，不给布局权**。
 *
 * ## 三条硬要求
 * 1. **`confirmDestructive = true`**：确认按钮用 [QzxColors.danger]，且**不主动请求焦点**
 *    —— 防误触。用户要么点遮罩取消，要么明确点到红按钮。
 * 2. **点遮罩 / 返回键 = 取消**（不是确认）。这里用 Compose 的 [Dialog] 而不是自绘全屏遮罩：
 *    它自带"点外部 / 返回键 → onDismissRequest"的平台语义，而本工程的 Activity 继承
 *    `android.app.Activity`、**没有** `OnBackPressedDispatcherOwner`，
 *    自绘遮罩要想接返回键就只能靠 `BackHandler`，那会在真机上直接抛异常。
 * 3. **`visible = false` 时不组合任何东西** —— 不是 `alpha = 0`。留一个全屏透明层会
 *    拦住下层所有点击（"界面突然点不动了"就是这么来的）。
 *
 * ## 背景为什么要 80% 不透明
 * 弹窗背后是页面内容。半透明到能看清字时，两层文字会叠在一起、谁都读不出来；
 * QZX 的对话框也是实底。这里取 80%：既压住背后，又保留一点点"浮起"的层次。
 */
@Composable
fun QzxDialog(
    visible: Boolean,
    title: String,
    message: String? = null,
    confirmText: String? = null,
    dismissText: String? = null,
    confirmDestructive: Boolean = false,
    onConfirm: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    /**
     * 可选的自定义正文 —— **表单弹窗走这里**。
     *
     * 【为什么需要它】`message: String?` 只能装纯文本。而「新建/编辑记忆」这类弹窗要装
     *   多个输入框、快捷分类、重要度步进、动态关联列表 —— 纯文本表达不了。
     *
     * 【但它不等于「把布局权交出去」】调用方在这个 lambda 里**仍然应该只写 `QzxList { … }`**
     *   —— `QzxList` 本身就是一个普通 Composable，放进 Dialog 里完全成立。
     *   这样表单弹窗依旧是声明式的：行怎么排、间距多少、分隔线在哪，全由系统算。
     *
     * 【与 [message] 的关系】两者可同时给（先 message 再 content），也可只给一个。
     *   `content != null` 时不额外加间距 —— 由 content 自己决定（通常是 `QzxList` 的 section 间距）。
     *
     * 【限高】content 外层会包一层 `heightIn(max = CONTENT_MAX_HEIGHT)` + 垂直滚动，
     *   保证按钮永远在屏幕内（与 message 的限高是同一个理由）。
     */
    content: (@Composable () -> Unit)? = null,
    /**
     * 负按钮的独立动作（默认 null = 用 [onDismiss]）。
     *
     * 【为什么需要】「编辑记忆」这类弹窗的负按钮是**删除**，不是关闭 ——
     *   它要执行删除再关闭，而不是单纯关掉弹窗。把删除挪到别处是**行为改变**，
     *   不是等价迁移，所以这里必须允许负按钮走自己的回调。
     *
     * 【语义边界】`dismissAction != null` 时负按钮只调它，**不自动关闭** ——
     *   由调用方决定删完要不要 `onDismiss()`（有的删除要先弹二次确认）。
     */
    dismissAction: (() -> Unit)? = null,
) {
    if (!visible) {
        // 【关键】不组合任何节点。
        return
    }
    val c = QzxColors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        val shape = RoundedCornerShape(QzxTokens.Radius.card)
        Column(
            modifier = Modifier
                .width(DIALOG_WIDTH)
                .clip(shape)
                .background(c.card.copy(alpha = DIALOG_BG_ALPHA))
                .qzxRimLight(corner = QzxTokens.Radius.card)
                .padding(
                    start = QzxTokens.Space.xl,
                    end = QzxTokens.Space.xl,
                    top = QzxTokens.Space.xl,
                    bottom = QzxTokens.Space.sm
                )
        ) {
            Text(
                text = title,
                color = c.title,
                fontSize = QzxTokens.Type.ROW_TITLE_SP.sp,
                lineHeight = (QzxTokens.Type.ROW_TITLE_SP + QzxTokens.Type.LINE_GAP_SP).sp
            )
            if (!message.isNullOrEmpty()) {
                Spacer(Modifier.height(QzxTokens.Space.sm))
                // 【多行正文】`message` 是普通 String，`Text` 会自动换行 —— 但**必须限高并允许滚动**：
                //   更新日志这类正文可能几百字，不限高会把两个按钮顶出屏幕（用户连"取消"都点不到）。
                Text(
                    text = message,
                    modifier = Modifier
                        .heightIn(max = MESSAGE_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                    color = c.sub,
                    fontSize = QzxTokens.Type.ROW_VALUE_SP.sp,
                    lineHeight = (QzxTokens.Type.ROW_VALUE_SP + QzxTokens.Type.LINE_GAP_SP).sp
                )
            }
            if (content != null) {
                Spacer(Modifier.height(QzxTokens.Space.sm))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = CONTENT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                }
            }
            Spacer(Modifier.height(QzxTokens.Space.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 取消永远在左、确认在右 —— 与系统对话框的肌肉记忆一致。
                if (dismissText != null) {
                    QzxDialogButton(
                        text = dismissText,
                        color = c.sub,
                        onClick = dismissAction ?: onDismiss
                    )
                }
                if (confirmText != null && onConfirm != null) {
                    Spacer(Modifier.width(QzxTokens.Space.sm))
                    QzxDialogButton(
                        text = confirmText,
                        color = if (confirmDestructive) c.danger else c.accent,
                        onClick = onConfirm
                    )
                }
            }
        }
    }
}

/** 弹窗宽度。320dp 在 360dp 屏上留 20dp 边；大屏不会拉成一条横线。 */
private val DIALOG_WIDTH = 320.dp

/** 正文最大高度：超过就内部滚动，保证底部的两个按钮永远在屏幕里。 */
private val MESSAGE_MAX_HEIGHT = 280.dp
/** 自定义正文的最大高度：超过就在弹窗内部滚动，保证按钮永远在屏幕内。 */
private val CONTENT_MAX_HEIGHT = 420.dp

/** 弹窗底不透明度：压住背后内容，同时保留一点"浮起"的层次。 */
private const val DIALOG_BG_ALPHA = 0.92f

/**
 * 对话框里的一个文字按钮。
 *
 * 不主动请求焦点：破坏性操作的确认按钮**永远不默认聚焦**，避免"回车就删了"。
 */
@Composable
private fun QzxDialogButton(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = QzxTokens.Size.minTouch.dp)
            .clip(RoundedCornerShape(QzxTokens.Radius.inner))
            .clickable(onClick = onClick)
            .padding(horizontal = QzxTokens.Space.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = QzxTokens.Type.ROW_TITLE_SP.sp,
            lineHeight = (QzxTokens.Type.ROW_TITLE_SP + QzxTokens.Type.LINE_GAP_SP).sp,
            textAlign = TextAlign.Center
        )
    }
}
