package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.core.Logs
import org.json.JSONArray

/**
 * 【职责】模型的数据层：落盘、读取、增删改、级联清理。
 *
 * 【存储】键 pv_models，数组元素是 AiModel JSON；与供应商共用 PetPrefs 的同一份偏好。
 *
 * 【交互】ProviderStore.deleteProvider 会调本类做级联清理；编辑页保存后由本类落盘。
 *
 * 【坑】① 写盘用 commit，理由同 ProviderStore。
 *        ② 删模型时若它正是「当前选中」，必须清掉 pv_cur，否则聊天页会拿着
 *          一个不存在的模型名发请求，服务端直接 400。
 *        ③ 模型的 providerId 是弱关联，找不到归属的模型按孤儿跳过不显示，
 *          但不会被自动删除（用户可能只是临时删了供应商又建回来）。
 */
object ModelStore {

    @JvmStatic
    fun models(ctx: Context): MutableList<AiModel> {
        ProviderStore.purgeLegacy(ctx)
        val out = ArrayList<AiModel>()
        try {
            val raw = ProviderStore.p(ctx).getString(ProviderStore.KEY_MODELS, "")
            if (raw == null || raw.isEmpty()) {
                return out
            }
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) {
                    out.add(AiModel.fromJson(o))
                }
            }
        } catch (t: Throwable) {
            Logs.w("Dollhouse", "models parse failed", t)
        }
        return out
    }

    internal fun writeModels(ctx: Context, list: List<AiModel>) {
        val arr = JSONArray()
        for (i in list.indices) {
            arr.put(list[i].toJson())
        }
        ProviderStore.p(ctx).edit().putString(ProviderStore.KEY_MODELS, arr.toString()).commit()
    }

    /** 某个供应商名下的模型（按 sortOrder / 名称排好）。 */
    @JvmStatic
    fun modelsOf(ctx: Context, providerId: String?): MutableList<AiModel> {
        val all = models(ctx)
        val out = ArrayList<AiModel>()
        for (i in all.indices) {
            if (all[i].providerId == providerId) {
                out.add(all[i])
            }
        }
        out.sortWith(Comparator<AiModel> { a, b ->
            if (a.sortOrder != b.sortOrder) {
                a.sortOrder - b.sortOrder
            } else {
                a.displayName.compareTo(b.displayName, ignoreCase = true)
            }
        })
        return out
    }

    @JvmStatic
    fun findModel(ctx: Context, id: String?): AiModel? {
        val list = models(ctx)
        for (i in list.indices) {
            if (list[i].id == id) {
                return list[i]
            }
        }
        return null
    }

    @JvmStatic
    fun saveModel(ctx: Context, `in`: AiModel?): AiModel? {
        if (`in` == null) {
            return null
        }
        val list = models(ctx)
        var done = false
        for (i in list.indices) {
            if (list[i].id == `in`.id) {
                list[i] = `in`
                done = true
                break
            }
        }
        if (!done) {
            `in`.sortOrder = list.size
            list.add(`in`)
        }
        writeModels(ctx, list)
        return `in`
    }

    @JvmStatic
    fun setModelEnabled(ctx: Context, id: String?, enabled: Boolean) {
        val list = models(ctx)
        for (i in list.indices) {
            if (list[i].id == id) {
                list[i].enabled = enabled
                break
            }
        }
        writeModels(ctx, list)
    }

    /**
     * 删除一个模型。
     * 【坑】若删掉的正是当前选中项，必须把 pv_cur 一并置空 —— 否则聊天页会拿着
     *   一个已经不存在的模型名去发请求，服务端回 400，用户看到的是「模型不存在」。
     */
    @JvmStatic
    fun deleteModel(ctx: Context, id: String?) {
        val list = models(ctx)
        val keep = ArrayList<AiModel>()
        var gone: AiModel? = null
        for (i in list.indices) {
            if (list[i].id == id) {
                gone = list[i]
            } else {
                keep.add(list[i])
            }
        }
        if (gone == null) {
            return
        }
        writeModels(ctx, keep)
        val cur = ProviderStore.p(ctx).getString(ProviderStore.KEY_CURRENT, "")
        if (ProviderStore.curKey(gone.providerId, gone.id) == cur) {
            ProviderStore.clearCurrent(ctx)
        }
    }

    /** 删除某个供应商名下的全部模型（由 ProviderStore.deleteProvider 调用）。 */
    internal fun deleteModelsOfProvider(ctx: Context, providerId: String?) {
        val list = models(ctx)
        val keep = ArrayList<AiModel>()
        for (i in list.indices) {
            if (providerId != list[i].providerId) {
                keep.add(list[i])
            }
        }
        writeModels(ctx, keep)
    }

    /** 批量写模型（拉取模型列表后一次性落盘，避免逐条 IO）。 */
    @JvmStatic
    fun saveModels(ctx: Context, add: List<AiModel>?) {
        if (add == null || add.isEmpty()) {
            return
        }
        val all = models(ctx)
        for (i in add.indices) {
            val `in` = add[i]
            var done = false
            for (k in all.indices) {
                if (all[k].id == `in`.id) {
                    all[k] = `in`
                    done = true
                    break
                }
            }
            if (!done) {
                `in`.sortOrder = all.size
                all.add(`in`)
            }
        }
        writeModels(ctx, all)
    }

    /** 同供应商下是否已有同名模型。 */
    @JvmStatic
    fun modelExists(ctx: Context, providerId: String?, displayName: String?, selfId: String?): Boolean {
        val list = models(ctx)
        val n = if (displayName == null) "" else displayName.trim()
        for (i in list.indices) {
            val m = list[i]
            if (n == m.displayName && providerId == m.providerId && m.id != selfId) {
                return true
            }
        }
        return false
    }

    /** 该供应商下已存在的模型名集合（拉取模型列表时用来标「已添加」）。 */
    @JvmStatic
    fun namesOf(ctx: Context, providerId: String?): MutableList<String> {
        val list = modelsOf(ctx, providerId)
        val out = ArrayList<String>()
        for (i in list.indices) {
            out.add(list[i].displayName)
        }
        return out
    }
}
