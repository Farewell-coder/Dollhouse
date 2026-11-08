package com.dollhouse.app.ui.compose

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.feedback.rememberConfirmGuard
import com.dollhouse.app.ui.theme.UiKit

/**
 * 全工程**弹窗的唯一命令式入口**：通知 / 确认 / 自定义内容三种形态。
 *
 * 【为什么需要它】
 *   `UiKit.showDialog` 是 View 实现：调用方拿 `TextView`/`LinearLayout` 手搭正文，
 *   而 Compose 页面用的是 `DhForm.Alert`。同一个 App 里于是存在两套弹窗壳，
 *   标题字号、按钮、圆角各自为政 —— 这正是「看着不像同一个团队做的」的来源之一。
 *   `DhForm.Alert` 只能在 Composable 作用域里用；而剩下这 5 处调用点全在
 *   View / 命令式回调里（`UiKit.findActivity(ctx)` 之后直接弹），没有重组作用域。
 *   所以这里提供的是**命令式**入口：拿 Activity + 文案就能弹，内部渲染 Compose 内容。
 *
 * 【与 DhForm.Alert / DhForm.show 的分工】不重复造轮子，只补它们缺的那一层：
 *   · 形态与按钮直接复用 [DhForm.DialogButton]（同一套 44dp 高度 / 主次配色 / 按压缩放），
 *     颜色与字阶复用 `DhTokens`，**没有第二套按钮或第二套字号**。
 *   · Compose 宿主复用 [ComposeHost.installForDialog] / [ComposeHost.createView]
 *     （独立 Window 上装 LifecycleOwner 这一段最容易崩，不能各写一份）。
 *   · 只新写「命令式工厂 + 卡片壳 + 窗口参数」。
 *   [DhForm.Alert] 走的是 `androidx.compose.ui.window.Dialog`，没有 dim、没有宽度上限、
 *   也没有「弹窗透明度」；本入口要与被替换掉的 View 弹窗**像素级接近**，所以自带窗口参数。
 *   后续若把 `Alert` / `show` 的卡片壳也收敛到这里，三处就只剩一份实现（已在本轮汇报里提议）。
 *
 * 【与 UiKit.showDialog 的逐项对齐】（迁移时保证「行为一致」的依据）
 *   | 项目            | UiKit.showDialog            | 本入口                     |
 *   | 卡片底           | sheet() 过 dialogTint       | c.sheet 按 dialogAlphaApplied 缩放 |
 *   | 圆角 / 内边距    | 16 / 18·18·18·14            | 同（圆角走 Radius.btn 令牌） |
 *   | 标题            | dialogTitle（16sp 粗）       | DhTokens.type.section 粗体  |
 *   | 正文            | dialogMessage（13sp SUB）    | DhTokens.type.sub           |
 *   | 按钮            | 右对齐、间距 10、主按钮置灰   | DialogButton + ConfirmGuard |
 *   | 遮罩            | FLAG_DIM_BEHIND / 0.45      | 同                          |
 *   | 宽度            | min(88% 屏宽, 420dp)        | 同                          |
 *   | 背景模糊        | applyDialogBlurToWindow     | 同（API<31 内部静默跳过）   |
 *   | 入场动画        | 180ms 上浮 + 0.94→1 + 淡入  | 同（UiKit.D_MICRO / EASE_DECEL） |
 *   | 点外部 / 返回关闭 | Dialog 默认（可关）         | 同（dismissOnOutside = true）|
 *
 * 【线程兜底】全部入口在**非主线程**被调用时会自动 `runOnUiThread` 排队，并**返回 null**。
 *   为什么必须兜：`ComposeHost.installForDialog` 要取 `ViewTreeLifecycleOwner` 与
 *   `SavedStateRegistry`，这两样在非主线程走 `Looper.myLooper()` 检查时是**抛异常**、
 *   不会排队。而调用方 `ShizukuBridge` 的回调线程不由本 App 控制。
 *   所以约定：**返回 null 表示「已排队，稍后自己弹」，不是失败**，调用方不要当错误处理。
 *   （副作用：`noticeDialog?.isShowing` 这类「连点只留一个」的判重，在排队那一瞬间
 *   会因为句柄是 null 而短暂失效 —— 连点两次可能排两次队、弹两次。这是已知取舍，不是 bug。）
 *
 * 【取色】弹窗底统一走 [dialogSheet]（定义在 `DhForm.kt`），与 `DhForm.Alert` / `DhForm.show`
 *   共用同一份实现，不再各自 `c.sheet`。
 *
 * 禁止新增 `UiKit.showDialog` 调用（该函数已标 @Deprecated）。
 */
object AppDialog {

    /**
     * 一个按钮：文案 + 是否主按钮 + 点击回调。
     *
     * 点击语义与 [UiKit.showDialog] 一致：**先关窗，再执行回调**；
     * 主按钮额外走 ConfirmGuard，连点只认第一次。
     */
    class Action(
        val text: String,
        val primary: Boolean = false,
        val onClick: () -> Unit = {}
    )

    /** 弹窗遮罩浓度。与 [UiKit.showDialog] 同值 —— 两处必须一起改。 */
    private const val DIM_AMOUNT = 0.45f

    /** 弹窗最大宽度（dp）。与 [UiKit.showDialog] 同口径。 */
    private const val MAX_WIDTH_DP = 420f

    /** 弹窗占屏宽比例。 */
    private const val WIDTH_RATIO = 0.88f

    /**
     * ① 通知：标题 + 一段说明 + 一个「知道了」。
     *
     * 主按钮文案默认「知道了」，回调为空 —— 与 View 版 `posText = "知道了", pos = null`
     * 逐字节同义：点一下只关窗，不做别的事。
     * 【为什么 posText 可改】`pet/PetWindow` 的调试弹窗按钮写的是「关闭」，
     *   换字不换形态，没必要为它另开一个工厂。
     */
    fun note(
        act: Activity?,
        title: String,
        message: String,
        posText: String = "知道了",
        onDismiss: (() -> Unit)? = null
    ): Dialog? = custom(
        act = act,
        title = title,
        actions = listOf(Action(posText, true)),
        onDismiss = onDismiss
    ) {
        Body(message)
    }

    /**
     * ② 确认：标题 + 一段说明 + 主/负按钮。
     *
     * 【负按钮语义】与 `DhForm.Alert` 对齐：`onNeg == null` 时「点了就关」；
     *   传了 `onNeg` 则由调用方决定（例如先弹二次确认，而不是直接关）。
     */
    fun confirm(
        act: Activity?,
        title: String,
        message: String,
        posText: String = "确定",
        onPos: () -> Unit = {},
        negText: String? = null,
        onNeg: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        dismissOnOutside: Boolean = true
    ): Dialog? {
        val actions = ArrayList<Action>(2)
        if (negText != null) {
            actions.add(Action(negText, false) { onNeg?.invoke() })
        }
        actions.add(Action(posText, true, onPos))
        return custom(
            act = act,
            title = title,
            actions = actions,
            onDismiss = onDismiss,
            dismissOnOutside = dismissOnOutside
        ) {
            Body(message)
        }
    }

    /**
     * ③ 自定义内容：标题 + 任意 Compose 正文 + 自定义按钮。
     *
     * 【为什么正文是 ColumnScope 的 lambda】与 `DhForm.show` / `DhForm.Alert` 同签名，
     *   调用方的写法可以直接搬过来；将来迁「搜索框」这种需要 [androidx.compose.foundation.text.BasicTextField]
     *   的弹窗时，焦点与输入法都还在同一个 Compose 组合里，不用再桥接 View。
     */
    fun custom(
        act: Activity?,
        title: String?,
        actions: List<Action> = emptyList(),
        onDismiss: (() -> Unit)? = null,
        dismissOnOutside: Boolean = true,
        /**
         * 让窗口在输入法弹出时**收缩**而不是被盖住。
         * 只在正文里含输入框时打开（目前只有聊天搜索框）；不打开时保持与旧 View 弹窗
         * 完全一致的行为（`SOFT_INPUT_ADJUST_UNSPECIFIED`）。
         */
        imeSafe: Boolean = false,
        content: @Composable ColumnScope.() -> Unit
    ): Dialog? {
        if (act == null) {
            return null
        }
        // 【线程兜底·见对象头注释】非主线程一律排队，并返回 null（null = 已排队，不是失败）。
        //   兜底只写在这里：note / confirm 都汇到 custom，写三遍就是「同一语义两处实现」。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            act.runOnUiThread {
                custom(act, title, actions, onDismiss, dismissOnOutside, imeSafe, content)
            }
            return null
        }
        val dlg = Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        // 【必须在 setContentView 之前】Dialog 是独立 Window，它的 ViewTree 上没有
        //   LifecycleOwner；不先装 owner，ComposeView attach 时直接崩
        //   （ViewTreeLifecycleOwner not found / Cannot locate windowRecomposer）。
        //   与 DhForm.show 同一套宿主代码。
        val owner = ComposeHost.installForDialog(dlg)
        dlg.setOnDismissListener {
            owner?.onDestroy()
            // 回调排在 owner 之后：调用方可能在这里释放资源，两者互不依赖。
            try {
                onDismiss?.invoke()
            } catch (ignored: Throwable) {
            }
        }
        dlg.setCanceledOnTouchOutside(dismissOnOutside)
        val card = ComposeHost.createView(act) {
            DialogCard(
                title = title,
                actions = actions,
                dismiss = { dlg.dismiss() },
                content = content
            )
        }
        dlg.setContentView(card)
        applyWindow(act, dlg, imeSafe)
        runEntrance(act, card)
        dlg.show()
        return dlg
    }

    /** 正文：13sp 副字色。对齐 `UiKit.dialogMessage` 的排版意图（同档字号 + 稍松行距）。 */
    @Composable
    private fun Body(text: String) {
        Text(
            text = text,
            color = DhTokens.colors.sub,
            fontSize = DhTokens.type.sub.fontSize,
            lineHeight = DhTokens.type.sub.lineHeight,
            fontFamily = DhTokens.fonts
        )
    }

    /**
     * 卡片壳：圆角 16 + 弹窗底 + 标题 + 正文 + 右下按钮行。
     *
     * 【为什么自带一份而不是复用 DhForm.show】卡片壳要按「标题 + 正文 + 按钮行」的固定结构
     *   排布（[DhForm.show] 只有标题 + 任意内容），并且必须与 `Alert` 用同一套取色 ——
     *   底色走共享的 [dialogSheet]，透明度设置对两者同时生效。
     */
    @Composable
    private fun DialogCard(
        title: String?,
        actions: List<Action>,
        dismiss: () -> Unit,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val c = DhTokens.colors
        val guard = rememberConfirmGuard()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DhDesign.Radius.btn.dp))
                .background(dialogSheet())
                .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 14.dp)
        ) {
            if (!title.isNullOrEmpty()) {
                Text(
                    text = title,
                    color = c.title,
                    fontSize = DhTokens.type.section.fontSize,
                    lineHeight = DhTokens.type.section.lineHeight,
                    fontFamily = DhTokens.fontsBold
                )
            }
            Column(modifier = Modifier.padding(top = 12.dp)) {
                content()
            }
            if (actions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    actions.forEachIndexed { index, action ->
                        if (index > 0) {
                            Spacer(Modifier.width(10.dp))
                        }
                        DhForm.DialogButton(text = action.text, primary = action.primary) {
                            // 与 UiKit.showDialog 同序：先关窗，再回调。
                            // 主按钮加 ConfirmGuard —— View 版是「点了就置灰」，
                            // 同一个意图：连点不能重复提交。
                            if (action.primary) {
                                guard.runOnce {
                                    dismiss()
                                    action.onClick()
                                }
                            } else {
                                dismiss()
                                action.onClick()
                            }
                        }
                    }
                }
            }
        }
    }

    /** 窗口参数：透明背景 + 遮罩 + 宽度上限 + 背景模糊。与 UiKit.showDialog 同口径。 */
    private fun applyWindow(act: Activity, dlg: Dialog, imeSafe: Boolean) {
        val w = dlg.window ?: return
        w.setBackgroundDrawable(ColorDrawable(0x00000000))
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(DIM_AMOUNT)
        val metrics = act.resources.displayMetrics
        val wide = Math.min((metrics.widthPixels * WIDTH_RATIO).toInt(), UiKit.dp(act, MAX_WIDTH_DP))
        w.setLayout(wide, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (imeSafe) {
            // 输入法弹出时收缩窗口，而不是把输入框顶到屏幕外。
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // 【顺序】放在 setLayout 之后：模糊按 window 整体背景算，尺寸定好再设更稳。
        //   API < 31 由该方法内部判掉，静默不生效（不是崩溃）。
        UiKit.applyDialogBlurToWindow(w)
    }

    /**
     * 入场：轻微上浮 + 缩放 + 淡入，与 `UiKit.showDialog` 用同一时长与曲线。
     *
     * 【必须在 show() 之前设初始态】否则会先闪一帧完整尺寸再缩回去。
     */
    private fun runEntrance(act: Activity, view: View) {
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.translationY = UiKit.dp(act, 14).toFloat()
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(UiKit.D_MICRO.toLong())
            .setInterpolator(UiKit.EASE_DECEL)
            .start()
    }
}
