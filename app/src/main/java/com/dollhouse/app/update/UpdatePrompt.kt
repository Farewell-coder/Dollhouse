package com.dollhouse.app.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】冷启动自动检查命中新版本时的**提示弹窗**（非关于页内，故不能复用 Compose 组件）。
 *
 * 【为什么不用 Compose】自动检查的触发点在 `MainActivity.onResume`，此时首页/设置页
 *   虽然已是 ComposeView，但弹窗属于「独立浮层」。工程既有的 View 侧弹窗入口是
 *   [UiKit.showDialog] —— 它已经处理了自绘底、圆角、遮罩、模糊、沉浸状态栏同步这些
 *   细节，直接复用即可，不必为这一个弹窗再搭一套 Compose Dialog 宿主。
 *
 * 【职责边界】本类只负责「展示 + 跳转」；节流、通道、判版本都在 [UpdateChecker]。
 *
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
        try {
            val body = buildBody(act, available)
            UiKit.showDialog(
                act,
                "发现新版本",
                body,
                "去下载",
                { openDownload(act, available) },
                "稍后",
                null
            )
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "ignored", t)
        }
    }

    /** 弹窗正文：版本对照 + 更新日志。 */
    private fun buildBody(act: Activity, available: UpdateCheckResult.Available): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val ver = android.widget.TextView(act)
        ver.text = "v" + available.currentVersion + "  →  v" + available.candidate.versionName +
                "（" + available.channel.label + "）"
        ver.setTextColor(UiKit.ACC)
        ver.setTextSize(UiKit.FS_BTN)
        col.addView(ver)

        val notes = available.candidate.notes
        if (notes.isNotEmpty()) {
            val tv = android.widget.TextView(act)
            tv.text = notes
            tv.setTextColor(UiKit.SUB)
            tv.setTextSize(UiKit.FS_TINY)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = UiKit.dp(act, 10)
            tv.layoutParams = lp
            col.addView(tv)
        }

        val hint = android.widget.TextView(act)
        hint.text = "下载与安装由系统浏览器完成。"
        hint.setTextColor(UiKit.SUB)
        hint.setTextSize(UiKit.FS_TINY)
        val hlp = LinearLayout.LayoutParams(-1, -2)
        hlp.topMargin = UiKit.dp(act, 10)
        hint.layoutParams = hlp
        col.addView(hint)

        return col
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