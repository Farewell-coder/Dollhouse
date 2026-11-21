package com.dollhouse.app.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.design.DhDesign

/**
 * 【职责】冷启动自动检查命中新版本时的**提示弹窗**（非关于页内）。
 *
 * 【M8·从 View 迁到 Compose】
 *   改造前这里用 `LinearLayout` + `TextView` 手搭正文，再交给 `UiKit.showDialog` 的
 *   View 侧弹窗壳。理由是「工程既有 View 弹窗入口，复用即可」。但那条理由在架构统一后不成立：
 *     · 它是 `ui/` 之外**唯一**还在新建 View 树的功能界面（其余 View 代码都是浮层/自绘引擎）；
 *     · `UiKit.showDialog` 的观感与 Compose 侧 `DhForm.Alert` 是两套壳，同一个 App 里
 *       「更新提示」和「确认恢复」长得不一样，字号与内边距也不一致；
 *     · 它自己设字号（FS_BTN / FS_TINY），绕过了设计阶梯。
 *   现在改为 Compose `AlertDialog` + 统一令牌，与全站其余弹窗同源。
 *
 * 【职责边界】本类只负责「展示 + 跳转」；节流、通道、判版本都在 [UpdateChecker]。
 * 【静默兜底】弹窗构建失败（Activity 已销毁等）只记日志，绝不让更新提示把 App 搞崩。
 */
object UpdatePrompt {

    private const val LOG_TAG = "Dollhouse"

    /**
     * 弹出「发现新版本」。
     *
     * @param available 检查结果（必然是 [UpdateCheckResult.Available]）
     */
    fun show(act: Activity?, available: UpdateCheckResult.Available) {
        if (act == null || act.isFinishing || act.isDestroyed) {
            return
        }
        // owner 声明在 try **之外**：`dlg.show()` 失败时 OnDismissListener 不会触发，
        //   catch 必须能就地回收它，否则 Recomposer 观察者与 ViewModelStore 永久挂着。
        //   口径与 ui/kit/DhDialogHost.show() 完全一致（两处必须同形，改一处要同步另一处）。
        var owner: ComposeHost.HostOwner? = null
        try {
            // 【配套】字段注释一直写着「同一时刻只允许一个」，但旧实现从没真正执行过：
            //   连续两次回调会叠两个窗，且旧窗的 owner 无人销毁（现在是硬泄漏）。
            //   这里按注释的原意先关旧的 —— 关闭会走 OnDismissListener，owner 一并释放。
            dismissTop(act)
            // 【崩溃修复·根因】`Dialog` 是**独立 Window**，它的 ViewTree 与 Activity 的
            //   decorView 完全不相干：`ViewTreeLifecycleOwner.get()` 只沿 parent 上溯、
            //   **不跨窗口**，所以装在 Activity decorView 上的 owner 在这里永远找不到。
            //   旧代码调的正是 `installForActivity(act)` → ComposeView attach 时必抛
            //   `IllegalStateException: ViewTreeLifecycleOwner not found`（诊断包里 37 个
            //   文件、去重后 29 次真实崩溃，全部来自这条路径的同族缺陷）。
            //   正确做法：owner 必须装在**这个 Dialog 自己的 decorView** 上 ——
            //   用 `installForDialog(dlg)`，且必须在 `setContentView` 之前装好
            //   （晚一步 ComposeView 就先 attach 了，一样崩）。
            //   范式与 `ui/compose/AppDialog.kt` 的 custom() 完全一致。
            val cv = ComposeHost.createView(act) {
                UpdateDialog(
                    available = available,
                    onDownload = {
                        openDownload(act, available)
                        dismissTop(act)
                    },
                    onLater = { dismissTop(act) }
                )
            }
            // 用一个轻量 Dialog 承载：不引 Material 的 Dialog 状态机，
            //   关窗由按钮回调直接完成（`dismissTop`），与 AlertDialog 的语义一致。
            val dlg = android.app.Dialog(act)
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            // 【顺序·必须】installForDialog 内部要先取 `window.decorView`（触发 installDecor）。
            val installed = ComposeHost.installForDialog(dlg)
            owner = installed
            // 【必须释放】owner 持有 Recomposer 观察者与 ViewModelStore；只关窗不销毁 = 泄漏。
            //   挂在 OnDismissListener 上才能覆盖全部关闭路径（按钮 / 点外部 / 返回键）。
            //   `topDialog === dlg` 判等是为了不误清后来新弹出的那个句柄。
            dlg.setOnDismissListener {
                installed?.onDestroy()
                if (topDialog === dlg) {
                    topDialog = null
                }
            }
            dlg.setContentView(cv)
            // 透明窗底：Compose 自己画纸张，去掉系统默认的白色方角。
            dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dlg.setCanceledOnTouchOutside(true)
            dlg.show()
            // 【赋值放在 show 之后】`dlg` 只有在确实显示出来之后才算「当前弹窗」：
            //   show() 抛异常时它从没出现在屏幕上，不该占着这个句柄
            //   （否则下次回调开头的 dismissTop 会去 dismiss 一个从未显示的窗）。
            topDialog = dlg
        } catch (t: Throwable) {
            // 【异常路径·必须】窗口没 show 出来（或 setContentView / dlg.show() 抛）时
            //   OnDismissListener 不会触发，owner 只能在这里回收。
            runCatching { owner?.onDestroy() }
            owner = null
            Logs.w(LOG_TAG, "ignored", t)
        }
    }

    /** 当前弹窗句柄（同一时刻只允许一个，避免连续回调叠出多个窗）。 */
    private var topDialog: android.app.Dialog? = null

    private fun dismissTop(act: Activity) {
        try {
            if (topDialog?.isShowing == true) {
                topDialog?.dismiss()
            }
        } catch (ignored: Throwable) {
        } finally {
            topDialog = null
        }
    }

    /**
     * 弹窗正文。
     *
     * 【为什么版本对照用主色】它是本屏唯一的"重点信息"（用户只关心 vA → vB 这一行），
     *   其余（更新日志、下载说明）都是次要，一律 `sub` 色 + `caption/sub` 字号。
     */
    @Composable
    private fun UpdateDialog(
        available: UpdateCheckResult.Available,
        onDownload: () -> Unit,
        onLater: () -> Unit
    ) {
        val p = DhTokens.palette
        val t = DhTokens.type
        AlertDialog(
            onDismissRequest = onLater,
            containerColor = p.sheet,
            shape = RoundedCornerShape(DhDesign.Radius.lg.dp),
            title = {
                Text(text = "发现新版本", color = p.title, style = t.section)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "v" + available.currentVersion +
                            "  →  v" + available.candidate.versionName +
                            "（" + available.channel.label + "）",
                        color = p.accent,
                        style = t.body
                    )
                    val notes = available.candidate.notes
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(DhDesign.Space.md.dp))
                        // 【更新日志可能很长】给一个最大高度并让它可滚动，
                        //   否则一条长日志会把弹窗撑到整屏、按钮被挤出可视区。
                        //   用 verticalScroll 而不是单纯 heightIn：后者只会把文字裁掉。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(DhDesign.Radius.md.dp))
                        ) {
                            Text(
                                text = notes,
                                color = p.sub,
                                style = t.sub,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                    Spacer(Modifier.height(DhDesign.Space.md.dp))
                    Text(
                        text = "下载与安装由系统浏览器完成。",
                        color = p.sub,
                        style = t.caption
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDownload) {
                    Text("去下载", color = p.accent, style = t.rowTitle)
                }
            },
            dismissButton = {
                TextButton(onClick = onLater) {
                    Text("稍后", color = p.sub, style = t.body)
                }
            }
        )
    }

    /**
     * 跳转下载：优先 APK 直链，没有则回退 Release 页面（需求 5）。
     *
     * 【为什么交给系统浏览器】签名校验与安装确认都由系统接管，不需要申请存储权限、
     *   不引入 FileProvider、也不做静默安装（需求明确禁止）。
     */
    private fun openDownload(act: Activity, available: UpdateCheckResult.Available) {
        val url = available.candidate.apkUrl.ifEmpty { available.candidate.releaseUrl }
        if (url.isEmpty()) {
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setData(Uri.parse(url))
            act.startActivity(intent)
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "ignored", t)
        }
    }
}