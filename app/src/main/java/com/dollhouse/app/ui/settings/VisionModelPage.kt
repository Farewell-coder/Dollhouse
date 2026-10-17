package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.PageStack

/**
 * 【职责】「识图模型」设置页（r15 新增）：指定用哪个模型来"看"图片。
 *
 * 【入口】设置页「实验」卡片下级的一行 —— 与「对话显示」「记忆库设置」并列
 *   （用户定案：多出来的全局设置都放实验卡片下级）。
 *
 * 【为什么需要这一页】发图时的处理策略分三档：
 *   ① 当前聊天模型自己会看图（勾了「视觉」）→ 原图直送，最准；
 *   ② 当前模型不会看图、但这里指定了识图模型 → 用识图模型把图分析成文字，
 *      再交给当前模型继续聊 —— 于是「聊天用便宜的文本模型」和「看图用好模型」
 *      可以分开配置；
 *   ③ 两者都没有 → 本地离线 OCR（只认得字，认不出画面）。
 *
 * 【为什么不做成必填】不指定也能用（走 ① 或 ③）。指定只是给「想省钱又想看图」
 *   的用户多一个选择，不该变成新的必答题。
 */
object VisionModelPage {

    const val TAG_PAGE = "feiyu_vision_model_page"

    /** 返回键用：本页在栈顶就退一层并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return PageStack.popIfTop(ctx, TAG_PAGE)
    }

    @JvmStatic
    fun buildPage(ctx: Context): View? {
        val act = findActivity(ctx) ?: return null
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { Content(act) }
    }

    @JvmStatic
    fun open(ctx: Context) {
        try {
            PageStack.push(ctx, TAG_PAGE) { buildPage(ctx) }
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        var i = 0
        while (i < 8 && c != null) {
            if (c is Activity) {
                return c
            }
            if (c is ContextWrapper) {
                c = c.baseContext
            } else {
                break
            }
        }
        return null
    }

    @Composable
    private fun Content(act: Activity) {
        // 每次改动 bump 一次，让界面立刻反映（prefs 同步读）。
        var tick by remember { mutableStateOf(0) }
        // 【为什么用「无」+ 模型列表】不做成下拉：模型可能几十个，下拉里滚半天；
        //   平铺成可点行，当前选中的打勾，一眼看清。
        val picked = remember(tick) { PetPrefs.visionModel(act) }
        // 只列「会看图」的聊天模型 —— 不会看图的模型设为识图模型等于没设。
        val candidates = remember(tick) {
            val out = ArrayList<AiModel>()
            for (m in ModelStore.models(act)) {
                if (m.capVision && AiModel.KIND_CHAT == m.kind && m.enabled) {
                    out.add(m)
                }
            }
            out
        }
        val curRecord = if (picked.isEmpty()) null else ModelStore.findModel(act, picked)

        DhKit.Page(
            title = "识图模型",
            sub = "用哪个模型来看图片",
            onBack = { closeIfOpen(act) }
        ) {
            DhKit.Card(collapsibleTitle = "当前设置") {
                val nowText = when {
                    curRecord != null -> curRecord.displayName
                    picked.isNotEmpty() -> "已失效（模型被删或关闭了视觉）"
                    else -> "跟随当前聊天模型"
                }
                DhKit.ValueRow(label = "识图模型", value = nowText)
                DhForm.Note("不指定时：聊天模型自己会看图就直接看图，不会看图就用本地识别（只认文字）。")
            }

            DhKit.Card(collapsibleTitle = "可选模型") {
                if (candidates.isEmpty()) {
                    DhForm.Note("还没有勾选「视觉（VISION）」的模型。"
                            + "去「提供商 → 某个模型 → 能力」里把「视觉」打开，它才会出现在这里。")
                } else {
                    DhKit.ActionRow(
                        label = "跟随当前聊天模型",
                        desc = "不单独指定，用到哪个模型就用哪个的看图能力"
                    ) {
                        PetPrefs.setVisionModel(act, "")
                        tick++
                    }
                    for (m in candidates) {
                        val pv = ProviderStore.providers(act).firstOrNull { it.id == m.providerId }
                        DhKit.ActionRow(
                            label = m.displayName,
                            desc = if (pv == null) "供应商已删除" else pv.name,
                            trailing = if (m.id == picked) "✓" else null
                        ) {
                            PetPrefs.setVisionModel(act, m.id)
                            tick++
                        }
                    }
                }
            }

            DhKit.Card(collapsibleTitle = "说明") {
                DhForm.Note("发图时的顺序：① 聊天模型自己会看图 → 直接看图（最准）；"
                        + "② 这里指定了识图模型 → 由它把图描述成文字，再交给聊天模型；"
                        + "③ 都没有 → 本地离线识别，只能读出图上的文字。")
                DhForm.Note("无论走哪一档，聊天本身始终用你当前选的模型，不会被换掉。")
            }
        }
    }
}