package com.dollhouse.app.ui.kit

import android.app.Activity
import android.app.Dialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.Icons

/**
 * 【职责】**命令式**弹出 Compose 弹窗的统一入口。
 *
 * 【为什么需要这个文件】
 *   架构统一后，常规弹窗应当全部是 Compose。但工程里有大量**命令式**调用点：
 *     · 气泡长按回调里弹消息菜单（`ChatUi.showMessageMenu`）
 *     · 桌宠快捷菜单（`PetWindow`）
 *     · 聊天页「模型配置 / 思考过程」底部面板（`SheetPanel`）
 *     · 保存成功后提示、错误详情……
 *   这些地方不在 Composable 作用域里，直接调 `@Composable` 函数编译不过 ——
 *   改造前正是因此才有了 `MessageMenu`（原生 Dialog）与 `SheetPanel`（手写 View 面板）
 *   两套自建弹层，于是同一个 App 里弹窗有两套观感。
 *
 * 【做法】把 Compose 内容塞进一个轻量 `Dialog` 的 contentView：
 *   1. 先在宿主 decorView 上补 Compose 运行所需的 owner（`ComposeHost.installForActivity`，
 *      幂等）；否则会抛 `ViewTreeLifecycleOwner not found`。
 *   2. `ComposeHost.createView { ... }` 产出 ComposeView 作为 Dialog 内容。
 *   3. Dialog 窗口底设为透明，纸张由 Compose 自己画（圆角不会被系统白方角切掉）。
 *
 * 【与 Material3 Dialog 的区别】不用 `androidx.compose.ui.window.Dialog`：它是
 *   `@Composable` 声明式的，必须在组合里存在；本文件的场景是「代码里某处突然要弹一个」，
 *   生命周期由调用方掌握。两者视觉一致（同一套 `sheet` 底 / 16 圆角 / 令牌），
 *   差别只在触发方式。
 *
 * 【可靠性】任何异常只记日志、绝不让弹窗把 App 搞崩（与 `UpdatePrompt` 同口径）。
 *   同一时刻只保留一个弹窗：新弹前先关旧的，避免连续回调叠窗。
 */
object DhDialogHost {

    private const val LOG_TAG = "Dollhouse"

    /** 当前窗口句柄（单一实例）。 */
    private var current: Dialog? = null

    /**
     * 弹一个「列表式操作菜单」。
     *
     * 【为什么保留这个签名】`MessageMenu.show` 的入参语义（标题 + 文案数组 + 动作数组 +
     *   可选图标数组）已经在 4 处调用点固化了，改成 data class 只会让调用方多写噪声。
     *
     * @param title  标题（null / 空则不显示）
     * @param labels 各菜单项文案
     * @param actions 与 [labels] 一一对应的动作；点完自动关窗
     * @param iconRes 与 [labels] 一一对应的图标资源；0 / 长度不足按无图标处理
     * @param dangerIndex 危险项下标（用 err 色），-1 = 无。删除类操作必须传，
     *        让用户在视觉上先看到「这一项不一样」。
     */
    fun showMenu(
        act: Activity?,
        title: String?,
        labels: List<String>,
        actions: List<() -> Unit>,
        iconRes: IntArray? = null,
        dangerIndex: Int = -1
    ) {
        if (act == null || labels.isEmpty()) {
            return
        }
        show(act, widthInsetDp = 28) { dismiss ->
            MenuSheet(
                title = title,
                labels = labels,
                iconRes = iconRes,
                dangerIndex = dangerIndex,
                onPick = { i ->
                    dismiss()
                    // 【动作在关窗之后执行】动作里可能自己再弹东西（确认框 / 新的面板），
                    //   先关掉当前窗才不会叠成两层。异常单独吞掉：一个菜单项失败不该影响其余。
                    try {
                        actions.getOrNull(i)?.invoke()
                    } catch (t: Throwable) {
                        Logs.w(LOG_TAG, "menu action failed: " + i, t)
                    }
                },
                onDismiss = { dismiss() }
            )
        }
    }

    /**
     * 弹一个**破坏性操作的二次确认**框。
     *
     * 【为什么需要它】改造前这类确认走 `UiKit.showDialog`（View 侧原生 Dialog），
     *   与 Compose 侧 `DhForm.Alert` 是两套壳；而命令式调用点（如聊天页工具区的
     *   「清空」按钮）又不能直接调 @Composable 的 `DhForm.Alert`。
     *   这里给出与 `DhForm.Alert` 同视觉、可从命令式代码调用的等价物。
     *
     * @param danger true 时确认按钮用 err 色 —— 破坏性操作必须让用户在点之前看到风险
     * @param body   正文（说清「会发生什么」与「什么不会动」，例如记忆不会被删）
     */
    fun confirm(
        act: Activity?,
        title: String,
        body: String,
        confirmText: String,
        onConfirm: () -> Unit,
        cancelText: String = "取消",
        onCancel: (() -> Unit)? = null,
        danger: Boolean = true
    ) {
        if (act == null) {
            return
        }
        show(act, widthInsetDp = 32) { dismiss ->
            ConfirmSheet(
                title = title,
                body = body,
                confirmText = confirmText,
                cancelText = cancelText,
                danger = danger,
                onConfirm = {
                    dismiss()
                    try {
                        onConfirm()
                    } catch (t: Throwable) {
                        Logs.w(LOG_TAG, "confirm action failed", t)
                    }
                },
                onCancel = {
                    dismiss()
                    onCancel?.invoke()
                }
            )
        }
    }

    /**
     * 确认框纸张。
     *
     * 视觉与 `DhForm.Alert` 同源：sheet 不透明底 + 16 圆角 + 标题 section / 正文 sub，
     * 两个按钮右对齐、确认在右（与系统 Alert 一致的操作顺序）。
     */
    @Composable
    private fun ConfirmSheet(
        title: String,
        body: String,
        confirmText: String,
        cancelText: String,
        danger: Boolean,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        val p = DhTokens.palette
        val t = DhTokens.type
        val shape = RoundedCornerShape(DhDesign.Radius.lg.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 【e2·确认框同为浮层】与菜单纸张同一档：先 shadow 再 clip，
                //   形状共用同一个变量，保证阴影不露出直角。
                .shadow(
                    elevation = DhDesign.Elevation.e2,
                    shape = shape,
                    clip = false
                )
                .clip(shape)
                .background(p.sheet)
                .padding(DhDesign.Space.xl.dp)
        ) {
            Text(text = title, color = p.title, style = t.section)
            Spacer(Modifier.height(DhDesign.Space.sm.dp))
            Text(text = body, color = p.sub, style = t.sub)
            Spacer(Modifier.height(DhDesign.Space.xl.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) {
                    Text(text = cancelText, color = p.sub, style = t.body)
                }
                Spacer(Modifier.width(DhDesign.Space.sm.dp))
                TextButton(onClick = onConfirm) {
                    Text(
                        text = confirmText,
                        color = if (danger) p.err else p.accent,
                        style = t.rowTitle
                    )
                }
            }
        }
    }

    /**
     * 通用的「弹一块 Compose 内容」。
     *
     * @param widthInsetDp 左右各留多少 dp（与既有弹窗口径一致：28dp 不铺满）
     * @param wrapHeight   内容高度是否包裹（true = wrap_content；false = 占满可用高度）
     * @param content      `dismiss` 回调交给内容自己关窗
     */
    fun show(
        act: Activity?,
        widthInsetDp: Int = 28,
        wrapHeight: Boolean = true,
        content: @Composable (dismiss: () -> Unit) -> Unit
    ) {
        if (act == null || act.isFinishing || act.isDestroyed) {
            return
        }
        dismissCurrent()
        try {
            ComposeHost.installForActivity(act)
            val dlg = Dialog(act)
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            // 【e2 阴影留白】浮层要投影就必须在窗口边界内留出余量，否则阴影会被窗口裁掉。
            //   这里给四周各留 e2 的宽度，并把窗口同量加宽 —— 两者相抵，
            //   纸张的**视觉宽度与改造前完全一致**，只是外面多了一圈透明可投影区。
            val shadowRoom = DhDesign.Elevation.e2
            val cv = ComposeHost.createView(act) {
                Box(modifier = Modifier.padding(shadowRoom)) {
                    content { dismissCurrent() }
                }
            }
            dlg.setContentView(cv)
            dlg.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            dlg.setCanceledOnTouchOutside(true)
            // 【浮层遮罩】改造前没设 dimAmount，弹窗与背后内容完全同亮 ——
            //   视觉上"糊在一起"，没有「浮起一层」的纵深感。
            //   【为什么只改窗口属性、不动布局】用户明确要求「不改原有功能和业务逻辑」；
            //   dim 由 WindowManager 画在窗口**下方**，与窗口背景是否透明无关，
            //   因此不会影响 62 处弹窗的任何布局与命中区。
            //   【取值 0.32】比系统默认的 0.6 轻 —— 用户要的是「轻量化、精致」，
            //   太重会像传统安卓弹窗；同时与抽屉的 scrim（title@42%）同一量级，
            //   全 App 浮层遮罩口径一致。
            runCatching { dlg.window?.setDimAmount(0.32f) }
            val dm = act.resources.displayMetrics
            val insetPx = act.resources.displayMetrics.density.toInt() * widthInsetDp * 2
            val roomPx = (act.resources.displayMetrics.density * shadowRoom.value).toInt() * 2
            val w = (dm.widthPixels - insetPx + roomPx).coerceAtMost(dm.widthPixels)
            dlg.window?.setLayout(
                w,
                if (wrapHeight) {
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
            )
            current = dlg
            dlg.show()
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "dialog host failed", t)
        }
    }

    /** 关掉当前弹窗（幂等）。 */
    fun dismissCurrent() {
        try {
            if (current?.isShowing == true) {
                current?.dismiss()
            }
        } catch (ignored: Throwable) {
        } finally {
            current = null
        }
    }

    /* ================================================================
     * 内容构件
     * ================================================================ */

    /**
     * 列表式菜单纸张。
     *
     * 视觉：`sheet` 底（不透明，弹窗必须挡住下层）+ 16 圆角 + 内边距 18/12，
     *   与 `DhForm.Alert` 同一套令牌 —— 改造前原生对话框自己设 `round(sheet(),16f)`
     *   并按 `UiKit.dp` 硬编码内边距，是同一个外观的第二份实现。
     */
    @Composable
    private fun MenuSheet(
        title: String?,
        labels: List<String>,
        iconRes: IntArray?,
        dangerIndex: Int,
        onPick: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        val p = DhTokens.palette
        val t = DhTokens.type
        val shape = RoundedCornerShape(DhDesign.Radius.lg.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 【e2·菜单属于真正的浮层】设计稿「阴影只留给真正的浮起层」——
                //   菜单纸张压在整个页面之上，是这套层级里唯一该投影的形态之一。
                //   先 shadow 再 clip：反过来剪完再投影会被裁掉，阴影看不见。
                //   形状必须与下面的 clip 完全一致，否则阴影会露出圆角外的直角。
                .shadow(
                    elevation = DhDesign.Elevation.e2,
                    shape = shape,
                    clip = false
                )
                .clip(shape)
                .background(p.sheet)
                .padding(
                    start = DhDesign.Space.lg.dp + 2.dp,
                    end = DhDesign.Space.lg.dp + 2.dp,
                    top = DhDesign.Space.lg.dp + 2.dp,
                    bottom = DhDesign.Space.md.dp
                )
        ) {
            if (!title.isNullOrEmpty()) {
                Text(
                    text = title,
                    color = p.title,
                    style = t.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = DhDesign.Space.sm.dp)
                )
            }
            // 【可滚动】菜单最多 6~7 项；窄屏 + 大字体时可能顶出屏幕，
            //   超出时内部滚动，而不是被系统裁掉。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                labels.forEachIndexed { i, label ->
                    val danger = i == dangerIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                            .pressable { onPick(i) }
                            .padding(
                                horizontal = DhDesign.Space.md.dp,
                                vertical = DhDesign.Space.md.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = iconRes?.getOrNull(i) ?: 0
                        if (icon != 0) {
                            DhKit.Icon(
                                icon,
                                DhDesign.Size.rowIcon.value,
                                if (danger) p.err else p.title
                            )
                            Spacer(Modifier.width(DhDesign.Space.iconGap.dp))
                        }
                        Text(
                            text = label,
                            color = if (danger) p.err else p.title,
                            style = t.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}