package com.dollhouse.app.data

import android.content.Context
import android.content.SharedPreferences
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.ApiEndpoint
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.core.KeyVault
import com.dollhouse.app.core.Logs
import org.json.JSONArray

/**
 * 【职责】供应商的数据层：落盘、读取、增删改、当前选中项。
 *
 * 【存储】键都在 PetPrefs 的同一份 SharedPreferences 里：
 *        pv_list   供应商数组（Provider JSON）
 *        pv_models 模型数组（AiModel JSON，读写见 ModelStore）
 *        pv_cur    当前选中的模型标识，格式「providerId|modelName」；空串 = 未选
 *
 * 【交互】五大页面只读写本类；apiKey 字段进出这里都是密文，明文只在 activeKey() 里短暂出现。
 *
 * 【坑】① 写盘一律用 commit：用户可能保存完立刻划掉后台，apply 的异步写有丢的风险。
 *        ② 删供应商必须级联删掉它的模型，否则会留下永远显示不出来的孤儿数据。
 *        ③ 旧的扁平配置键（cfg_profiles / cfg_cur / model_stars* / api_key）在首次读到
 *          本类时一次性清掉（标志 legacy_purged_v003），此后不再碰；用户已确认不做旧档迁移。
 *        ④ base_url / model 两个旧活跃键留着不清 —— 它们是 PetPrefs 的既有字段，
 *          清掉会让老版本回退时白屏，且不含密钥。
 */
object ProviderStore {
    private const val TAG = "Dollhouse"
    /** 供应商数组。 */
    const val KEY_PROVIDERS = "pv_list"
    /** 模型数组（读写入口在 ModelStore）。 */
    const val KEY_MODELS = "pv_models"
    /** 当前选中的模型：providerId|modelName。 */
    const val KEY_CURRENT = "pv_cur"
    /** 旧数据一次性清理的持久标志。 */
    private const val KEY_PURGED = "legacy_purged_v003"

    /** 当前选中项的极简解析结果。 */
    class Selection {
        var providerId: String = ""
        /** 模型内部记录 id（AiModel.id，仅本 App 内用；请求体的 "model" 用的是 displayName）。 */
        var modelId: String = ""
        /** 该模型所属的供应商；未选或找不到时为 null。 */
        var provider: Provider? = null

        fun valid(): Boolean {
            return provider != null && modelId.length > 0
        }
    }

    internal fun p(ctx: Context): SharedPreferences {
        return PetPrefs.get(ctx)
    }

    /* ------------------------------ 旧数据清理 ------------------------------ */

    /**
     * 一次性清掉旧扁平配置时代的键。
     * 【清什么】cfg_profiles / cfg_cur / model_stars 及其 | 派生键 / api_key（明文密钥）。
     * 【不清什么】base_url / model：PetPrefs 既有字段，无密钥，留着不碍事。
     */
    internal fun purgeLegacy(ctx: Context) {
        try {
            val sp = p(ctx)
            if (sp.getBoolean(KEY_PURGED, false)) {
                return
            }
            val ed = sp.edit().putBoolean(KEY_PURGED, true)
            ed.remove("cfg_profiles")
            ed.remove("cfg_cur")
            ed.remove("api_key")
            for (k in sp.all.keys) {
                if (k != null && (k == "model_stars" || k.startsWith("model_stars|"))) {
                    ed.remove(k)
                }
            }
            ed.commit()
        } catch (t: Throwable) {
            Logs.w(TAG, "purgeLegacy failed", t)
        }
    }

    /* ------------------------------ 供应商读写 ------------------------------ */

    /** 读全部供应商（顺序即列表展示顺序）。 */
    @JvmStatic
    fun providers(ctx: Context): MutableList<Provider> {
        purgeLegacy(ctx)
        val out = ArrayList<Provider>()
        try {
            val raw = p(ctx).getString(KEY_PROVIDERS, "")
            if (raw == null || raw.isEmpty()) {
                return out
            }
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) {
                    out.add(Provider.fromJson(o))
                }
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "providers parse failed", t)
        }
        return out
    }

    internal fun writeProviders(ctx: Context, list: List<Provider>) {
        val arr = JSONArray()
        for (i in list.indices) {
            arr.put(list[i].toJson())
        }
        p(ctx).edit().putString(KEY_PROVIDERS, arr.toString()).commit()
    }

    @JvmStatic
    fun findProvider(ctx: Context, id: String?): Provider? {
        if (id == null || id.isEmpty()) {
            return null
        }
        val list = providers(ctx)
        for (i in list.indices) {
            if (id == list[i].id) {
                return list[i]
            }
        }
        return null
    }

    /**
     * 新增或更新一个供应商（按 id 判定）。
     * 【返回】保存后的供应商（含补齐的 id / 时间戳）。
     */
    @JvmStatic
    fun saveProvider(ctx: Context, `in`: Provider?): Provider? {
        if (`in` == null) {
            return null
        }
        val list = providers(ctx)
        val now = System.currentTimeMillis()
        if (`in`.createdAt <= 0L) {
            `in`.createdAt = now
        }
        `in`.updatedAt = now
        var done = false
        for (i in list.indices) {
            if (list[i].id == `in`.id) {
                list[i] = `in`
                done = true
                break
            }
        }
        if (!done) {
            list.add(`in`)
        }
        writeProviders(ctx, list)
        return `in`
    }

    /** 只改启用状态。 */
    @JvmStatic
    fun setProviderEnabled(ctx: Context, id: String?, enabled: Boolean) {
        val list = providers(ctx)
        for (i in list.indices) {
            if (list[i].id == id) {
                list[i].enabled = enabled
                list[i].updatedAt = System.currentTimeMillis()
                break
            }
        }
        writeProviders(ctx, list)
    }

    /**
     * 删除供应商，并级联删掉它名下的全部模型。
     * 【为什么级联】模型是弱关联（只有 providerId），留着就成孤儿：界面永远显示不到，
     *   但会永久占着存储、还会出现在导出文件里，属于脏数据。
     */
    @JvmStatic
    fun deleteProvider(ctx: Context, id: String?) {
        val list = providers(ctx)
        val keep = ArrayList<Provider>()
        var name = ""
        for (i in list.indices) {
            if (list[i].id == id) {
                name = list[i].name
            } else {
                keep.add(list[i])
            }
        }
        writeProviders(ctx, keep)
        ModelStore.deleteModelsOfProvider(ctx, id)
        // 当前选中若指向被删的供应商，一并置空
        val sel = current(ctx)
        if (sel.providerId == id) {
            clearCurrent(ctx)
        }
        Logs.i(TAG, "已删除供应商及其模型：" + name)
    }

    /* ------------------------------ 当前选中 ------------------------------ */

    internal fun curKey(providerId: String?, modelId: String?): String {
        return (if (providerId == null) "" else providerId) + "|" + (if (modelId == null) "" else modelId)
    }

    /** 读出当前选中项（连带解析出供应商对象）。 */
    @JvmStatic
    fun current(ctx: Context): Selection {
        val s = Selection()
        try {
            val raw = p(ctx).getString(KEY_CURRENT, "")
            if (raw == null || raw.isEmpty()) {
                return s
            }
            val i = raw.indexOf('|')
            if (i <= 0) {
                return s
            }
            s.providerId = raw.substring(0, i)
            s.modelId = raw.substring(i + 1)
            s.provider = findProvider(ctx, s.providerId)
        } catch (t: Throwable) {
            Logs.w(TAG, "current parse failed", t)
        }
        return s
    }

    /** 设置当前选中的模型（providerId + 模型 id）。 */
    @JvmStatic
    fun setCurrent(ctx: Context, providerId: String?, modelId: String?) {
        p(ctx).edit().putString(KEY_CURRENT, curKey(providerId, modelId)).commit()
    }

    /** 清空当前选中（删掉选中模型 / 供应商时用）。 */
    @JvmStatic
    fun clearCurrent(ctx: Context) {
        p(ctx).edit().putString(KEY_CURRENT, "").commit()
    }

    /* ------------------------------ 给聊天链路用的取值 ------------------------------ */

    /**
     * 当前生效的供应商；没有选中项时退回「第一个启用的供应商」。
     * 【为什么兜底】用户可能只建了供应商还没去聊天页选模型，此时请求不该直接失败。
     */
    @JvmStatic
    fun activeProvider(ctx: Context): Provider? {
        val s = current(ctx)
        val sp = s.provider
        if (sp != null && sp.enabled) {
            return sp
        }
        val list = providers(ctx)
        for (i in list.indices) {
            if (list[i].enabled) {
                return list[i]
            }
        }
        return null
    }

    /**
     * 当前生效的模型名（请求体 "model" 字段的值）；没有可用模型时返回空串。
     * 【修·必须】这里过去返回的是 AiModel.id —— 那是本 App 自己生成的内部记录标识
     *   （形如 1791264613907-61f1），服务端根本不认识，发给中转站必然报
     *   「No available channel for model ...」。请求体要的是「模型名」，即 displayName。
     *   AiModel.id 只用于内部查找 / 选中态比对（见 SheetPanel 高亮、ModelStore.findModel），
     *   绝不能出网。
     * 【为什么兜底】用户可能只建了供应商还没去聊天页选模型，此时请求不该直接失败。
     * 【为什么校验存在性】模型被删 / 被禁用后 pv_cur 可能残留旧值（例如跨版本升级留档），
     *   直接拿去发请求服务端必然 400，所以命中不到就退回组内第一个可用聊天模型。
     */
    @JvmStatic
    fun activeModel(ctx: Context): String {
        val m = activeModelRecord(ctx)
        return if (m == null) "" else m.displayName
    }

    /**
     * 当前生效的模型记录（给需要读全字段的调用方用）；没有可用模型时返回 null。
     * 选取规则与 {@link #activeModel} 完全一致，只是不把 id 当模型名吐出去。
     */
    @JvmStatic
    fun activeModelRecord(ctx: Context): AiModel? {
        val pv = activeProvider(ctx) ?: return null
        val s = current(ctx)
        if (s.valid() && pv.id == s.providerId) {
            val m = ModelStore.findModel(ctx, s.modelId)
            if (m != null && m.enabled && AiModel.KIND_CHAT == m.kind) {
                return m
            }
        }
        val ms = ModelStore.modelsOf(ctx, pv.id)
        for (i in ms.indices) {
            if (ms[i].enabled && AiModel.KIND_CHAT == ms[i].kind) {
                return ms[i]
            }
        }
        return null
    }

    /**
     * 当前生效的 API 密钥明文。
     * 【隐私】明文只在这里短暂出现，绝不写日志、绝不落盘。
     * 【失败语义】解不开（换设备 / 清了数据）返回空串，上层按「未填密钥」处理并提示重填。
     */
    @JvmStatic
    fun activeKey(ctx: Context): String {
        val pv = activeProvider(ctx) ?: return ""
        return KeyVault.decrypt(pv.apiKey)
    }

    /**
     * 当前生效的 BaseUrl（已规范化）；没有任何可用供应商时返回空串。
     * 【坑·必须】这里绝不能回调 PetPrefs.baseUrl —— PetPrefs.baseUrl 会先调本方法取活跃值，
     *   两边互相回退就是无限递归（StackOverflowError）。兜底一律留给调用方做。
     */
    @JvmStatic
    fun activeBaseUrl(ctx: Context): String {
        val pv = activeProvider(ctx) ?: return ""
        return ApiEndpoint.ensure(pv.baseUrl)
    }

    /** 某供应商的密钥明文（编辑页连通性测试用）。 */
    @JvmStatic
    fun keyOf(ctx: Context, pv: Provider?): String {
        return if (pv == null) "" else KeyVault.decrypt(pv.apiKey)
    }

    /** 生成的 id：时间戳 + 随机后缀，够用且不与旧数据冲突。 */
    @JvmStatic
    fun newId(): String {
        return System.currentTimeMillis().toString() + "-" + Integer.toHexString((Math.random() * 0xFFFF).toInt())
    }

    /** 是否已经建过任何供应商（空状态判断用）。 */
    @JvmStatic
    fun isEmpty(ctx: Context): Boolean {
        return providers(ctx).isEmpty()
    }
}
