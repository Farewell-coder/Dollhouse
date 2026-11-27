package com.dollhouse.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.setValue
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.PageStack
import com.dollhouse.app.ui.qzx.QzxColors
import com.dollhouse.app.ui.qzx.QzxList
import com.dollhouse.app.ui.qzx.QzxPage
import com.dollhouse.app.ui.theme.Icons

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

    /**
     * 【P1-e·设置索引接线】按 rowId 取索引条目，供各行的 `spot =` 使用。
     * 【自纠错】rowId 写错 / 忘了传 spot → 标题退回空串（肉眼可见）。不要加 `?: "兜底"`。
     */
    private fun spot(rowId: String) = SettingsSpots.find(rowId)

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

        QzxPage(
            title = "识图模型",

            backIconRes = Icons.IC_ARROW_LEFT,
            subtitle = "用哪个模型来看图片",
            onBack = { closeIfOpen(act) },
        ) {
            QzxList(spotTitle = { spot(it)?.title }) {

                section(id = "current", title = "当前设置", collapsible = true) {
                    val nowText = when {
                        curRecord != null -> curRecord.displayName
                        picked.isNotEmpty() -> "已失效（模型被删或关闭了视觉）"
                        else -> "跟随当前聊天模型"
                    }
                    item(
                        "当前识图模型",
                        value = nowText,
                        iconRes = Icons.IC_EYE
                    ).valueTint(
                        // 【语义色】失效是"要处理"的状态，用错误色；正常用默认副文本色。
                        if (picked.isNotEmpty() && curRecord == null) QzxColors.danger else null
                    ).spot("vision.current")
                    note("不指定时：聊天模型自己会看图就直接看图，不会看图就用本地识别（只认文字）。")
                }

                section(id = "candidates", title = "可选模型", collapsible = true) {
                    if (candidates.isEmpty()) {
                        note(
                            "还没有勾选「视觉（VISION）」的模型。" +
                                "去「提供商 → 某个模型 → 能力」里把「视觉」打开，它才会出现在这里。"
                        )
                    } else {
                        item("跟随当前聊天模型", iconRes = Icons.IC_BOT)
                            // 【P1-e】默认行为解释，属可选了解。
                            .desc("不单独指定，用到哪个模型就用哪个的看图能力")
                            .arrow()
                            .spot("vision.followChat")
                            .onClick {
                                PetPrefs.setVisionModel(act, "")
                                tick++
                            }
                        // 【动态行】候选模型由数据决定，无法静态枚举 —— 声明式列表允许在
                        //   section 里用 for 循环逐行 add，排版仍归系统（不需要 custom{}）。
                        for (m in candidates) {
                            val pv = ProviderStore.providers(act).firstOrNull { it.id == m.providerId }
                            item(
                                m.displayName,
                                value = if (m.id == picked) "✓" else null,
                                iconRes = Icons.IC_EYE
                            ).desc(if (pv == null) "供应商已删除" else pv.name)
                                .valueTint()
                                .arrow()
                                .onClick {
                                    PetPrefs.setVisionModel(act, m.id)
                                    tick++
                                }
                        }
                    }
                }

                section(id = "help", title = "说明", collapsible = true) {
                    note(
                        "发图时的顺序：① 聊天模型自己会看图 → 直接看图（最准）；" +
                            "② 这里指定了识图模型 → 由它把图描述成文字，再交给聊天模型；" +
                            "③ 都没有 → 本地离线识别，只能读出图上的文字。"
                    )
                    note("无论走哪一档，聊天本身始终用你当前选的模型，不会被换掉。")
                }
            }
        }
    }
}