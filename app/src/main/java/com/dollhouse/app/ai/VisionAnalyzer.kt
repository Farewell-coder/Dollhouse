package com.dollhouse.app.ai

import android.content.Context
import com.dollhouse.app.core.ImageEncoder
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ModelStore
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.data.ProviderStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】用「识图模型」分析一张图片，把结果输出成一段文字描述。
 *
 * 【为什么单独一个类】用户定案 3C：聊天优先用**当前模型自己**的看图能力；
 *   当前模型不会看图（`capVision = false`）时，自动改用用户在设置里**指定的识图模型**
 *   来把图片「翻译」成文字，再把这段文字交给当前模型继续聊。
 *   于是「聊天用哪个模型」和「用哪个模型看图」被解耦 —— 你可以用便宜的文本模型聊天、
 *   用另一个会看图的模型专门处理图片。
 *
 * 【与请求侧的边界】它只负责「图 → 文字」，不参与会话模型切换。
 *   聊天请求照旧发给用户选定的模型，只是正文里多了这段图片描述（形如
 *   `【图片内容】……`），纯文本模型也能"看懂"。
 *
 * 【失败语义】任何一步失败都回调空串，绝不阻断聊天；调用方可选择退回本地 OCR。
 */
object VisionAnalyzer {

    private const val TAG = "DollhouseVision"

    /** 分析结果上限：太长会挤占上下文，1500 字足够描述一张图的关键信息。 */
    private const val MAX_TOKENS = 1500

    /** 用户未指定提示词时的默认问法（要「描述」而不是「识别文字」——那是 OCR 的活）。 */
    private const val DEFAULT_PROMPT =
        "请详细描述这张图片的内容，包括画面主体、场景、文字信息与关键细节。" +
                "直接输出描述，不要复述本指令。"

    fun interface Callback {
        /** @param text 分析结果；空串表示失败（调用方应退回本地 OCR 或不带图发）。 */
        fun onDone(text: String)
    }

    /**
     * 是否有可用的识图模型（用户指定 + 该模型确实开了视觉能力 + 归属的供应商可用）。
     * 【为什么要校验 capVision】用户可能把一个不会看图的模型设成识图模型 —— 那等于没设。
     */
    @JvmStatic
    fun available(ctx: Context?): Boolean {
        return modelOf(ctx) != null
    }

    /**
     * 用识图模型分析 [imagePath]。
     * 【线程】网络请求在自动后台执行，回调**不保证在主线程**，调用方注意切线程。
     */
    @JvmStatic
    fun analyze(ctx: Context?, imagePath: String?, prompt: String?, cb: Callback?) {
        val app = ctx?.applicationContext
        if (app == null || imagePath.isNullOrEmpty()) {
            cb?.onDone("")
            return
        }
        val model = modelOf(app)
        if (model == null) {
            Logs.i(TAG, "未配置可用的识图模型，跳过")
            cb?.onDone("")
            return
        }
        val pv = ProviderStore.providers(app).firstOrNull { it.id == model.providerId && it.enabled }
        if (pv == null) {
            Logs.i(TAG, "识图模型的供应商不可用，跳过")
            cb?.onDone("")
            return
        }
        val key = pv.apiKey
        if (key.isEmpty()) {
            Logs.i(TAG, "识图模型所属供应商没有 key，跳过")
            cb?.onDone("")
            return
        }
        val dataUri = ImageEncoder.toDataUri(app, imagePath)
        if (dataUri == null) {
            Logs.w(TAG, "图片编码失败，无法送识图模型")
            cb?.onDone("")
            return
        }
        val body = buildBody(model, dataUri, prompt)
        try {
            DeepSeekClient.chat(pv, key, pv.baseUrl, model.displayName, body, MAX_TOKENS,
                DeepSeekClient.Callback { text, _ ->
                    cb?.onDone(if (text == null) "" else text.trim())
                })
        } catch (t: Throwable) {
            Logs.w(TAG, "识图请求异常", t)
            cb?.onDone("")
        }
    }

    /**
     * 取可用的识图模型记录。
     * 【判定】用户指定了 id → 找得到 + 已启用 + 确实开了视觉 → 用它；
     *   否则退回「任意一个开了视觉且启用的聊天模型」（用户在设置里没指定时的兜底）。
     */
    private fun modelOf(ctx: Context?): AiModel? {
        if (ctx == null) {
            return null
        }
        val id = PetPrefs.visionModel(ctx)
        if (id.isNotEmpty()) {
            val m = ModelStore.findModel(ctx, id)
            return if (m != null && m.enabled && m.capVision
                && AiModel.KIND_CHAT == m.kind) m else null
        }
        // 未指定：找一个能看图的聊天模型。
        val all = ModelStore.models(ctx)
        for (i in all.indices) {
            val m = all[i]
            if (m.enabled && m.capVision && AiModel.KIND_CHAT == m.kind) {
                return m
            }
        }
        return null
    }

    /** 组装多模态请求体：文字提示 + 图片，与请求侧 [ChatHistoryStore.visionMessage] 同一格式。 */
    private fun buildBody(model: AiModel, dataUri: String, prompt: String?): JSONArray {
        val content = JSONArray()
        val t = JSONObject()
        t.put("type", "text")
        t.put("text", if (prompt.isNullOrBlank()) DEFAULT_PROMPT else prompt.trim())
        content.put(t)

        val img = JSONObject()
        img.put("type", "image_url")
        val url = JSONObject()
        url.put("url", dataUri)
        img.put("image_url", url)
        content.put(img)

        val usr = JSONObject()
        usr.put("role", "user")
        usr.put("content", content)

        val messages = JSONArray()
        messages.put(usr)
        return messages
    }
}