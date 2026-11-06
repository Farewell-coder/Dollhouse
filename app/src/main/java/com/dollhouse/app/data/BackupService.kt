package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.Provider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】把本机数据装进 [BackupEnvelope] 信封，以及从信封恢复。
 *
 * 【范围口径】本类**只做组装与写回**，不碰 UI、不弹窗、不问用户；
 *   恢复前的确认与概览由调用方负责（见设置页的备份入口）。
 *
 * 【为什么不含 API Key 明文】`Provider.apiKey` 在库里本来就是 KeyVault 密文，
 *   而 KeyVault 的密钥存在这台设备的 Android Keystore 里，**换机后解不开**。
 *   所以：
 *   - 导出时保留密文字段（同机重装时若 Keystore 未变仍可用）；
 *   - 恢复时**不覆盖**目标机已有的 apiKey（`apiKey` 为空就沿用原值），
 *     避免「恢复完所有请求都 401」。
 *
 * 【为什么不含图片】封面 / 形象 / 聊天背景是私有目录里的 PNG/JPG。
 *   塞进 JSON 会让备份从几百 KB 涨到几十 MB，且 base64 之后更难排查；
 *   宁可明确告知「图片不在备份里」，也不要做一个体积失控又半残的备份。
 */
object BackupService {

    /** 导出：把当前数据组装成信封。调用方负责序列化成字节。 */
    @JvmStatic
    fun export(ctx: Context, appVersion: String): JSONObject {
        val providers = JSONArray()
        for (p in ProviderStore.providers(ctx)) {
            providers.put(p.toJson())
        }
        val models = JSONArray()
        for (m in ModelStore.models(ctx)) {
            models.put(m.toJson())
        }
        val dolls = JSONArray()
        for (d in DollStore.all(ctx)) {
            // 【为什么手写字段而不是调 DollStore 的 toJson】那个方法是私有的；
            //   把私有方法改成 public 等于给数据层加一个对外的长期承诺。
            //   这里按 `data class Doll` 的公开字段逐项写入，字段少、一眼能核对。
            val o = JSONObject()
            o.put("id", d.id)
            o.put("name", d.name)
            o.put("cover", d.cover)
            o.put("sprite", d.sprite)
            o.put("enabled", d.enabled)
            o.put("builtin", d.builtin)
            if (d.group.isNotEmpty()) {
                o.put("group", d.group)
            }
            dolls.put(o)
        }
        val memories = JSONObject()
        for (doll in DollStore.all(ctx)) {
            val arr = Mem.list(ctx, doll.id)
            if (arr.length() > 0) {
                memories.put(doll.id, arr)
            }
        }
        val settings = JSONObject()
        // 只带「跨设备仍然有意义」的外观与开关；Key、路径、会话 ID 这类设备相关信息不带。
        settings.put("theme", PetPrefs.themeMode(ctx))
        settings.put("memAuto", PetPrefs.mem2Auto(ctx))
        settings.put("memInterval", PetPrefs.mem2IntervalMinutes(ctx))
        settings.put("webSearch", PetPrefs.webSearchEnabled(ctx))
        return BackupEnvelope.build(
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersion,
            providers = providers,
            models = models,
            dolls = dolls,
            memories = memories,
            settings = settings
        )
    }

    /**
     * 恢复：把信封写回本机。
     *
     * 【冲突口径】沿用导入面板的既有约定 —— 同名供应商保留原密钥、不重复建同名模型。
     *   这里**不做覆盖删除**：只会新增或补齐，不会因为恢复一份旧备份而清空用户现有的数据。
     *
     * @return 一句可直接给用户看的摘要
     */
    @JvmStatic
    fun restore(ctx: Context, root: JSONObject?): String {
        if (root == null) {
            return "备份内容为空，未做任何修改。"
        }
        var providersAdded = 0
        var modelsAdded = 0
        var memsAdded = 0

        // ---- 供应商 ----
        val existing = ProviderStore.providers(ctx)
        val byName = HashMap<String, Provider>()
        for (p in existing) {
            byName[p.name] = p
        }
        val pvArr = BackupEnvelope.sectionArray(root, "providers")
        for (i in 0 until pvArr.length()) {
            val o = pvArr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name.isEmpty()) {
                continue
            }
            val hit = byName[name]
            if (hit != null) {
                // 【不覆盖密钥】同机重装时能沿用；换机时密文解不开，保留目标机原有值更安全。
                continue
            }
            val p = Provider.fromJson(o)
            if (p.id.isEmpty()) {
                p.id = ProviderStore.newId()
            }
            ProviderStore.saveProvider(ctx, p)
            byName[name] = p
            providersAdded++
        }

        // ---- 模型 ----
        val modelsArr = BackupEnvelope.sectionArray(root, "models")
        for (i in 0 until modelsArr.length()) {
            val o = modelsArr.optJSONObject(i) ?: continue
            val m = AiModel.fromJson(o)
            if (m.displayName.isEmpty()) {
                continue
            }
            // 归属的供应商可能因为同名已存在而换了 id：按名字重新挂一次。
            val owner = byName.values.firstOrNull { it.id == m.providerId }
                ?: byName.values.firstOrNull { it.name == o.optString("providerName", "") }
            if (owner != null) {
                m.providerId = owner.id
            }
            if (m.providerId.isEmpty()) {
                continue
            }
            if (ModelStore.modelExists(ctx, m.providerId, m.displayName, null)) {
                continue
            }
            if (m.id.isEmpty() || ModelStore.findModel(ctx, m.id) != null) {
                m.id = ProviderStore.newId()
            }
            ModelStore.saveModel(ctx, m)
            modelsAdded++
        }

        // ---- 人偶 ----
        // 【口径·不伪造人偶】DollStore 目前只有 addTemplate（新建空白模板），
        //   没有「按备份记录插入任意人偶」的公开接口；硬塞会造出缺封面、缺形象、
        //   动作对不上的人偶，用户看到的是「恢复出来一堆坏角色」。
        //   因此这里**不新建人偶**，只统计备份里有、本机没有的那些，明确告知用户。
        var dollsMissing = 0
        val dollsArr = BackupEnvelope.sectionArray(root, "dolls")
        for (i in 0 until dollsArr.length()) {
            val o = dollsArr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) {
                continue
            }
            if (DollStore.byId(ctx, id) == null) {
                dollsMissing++
            }
        }

        // ---- 记忆 ----
        val memObj = BackupEnvelope.sectionObject(root, "memories")
        val keys = memObj.keys()
        while (keys.hasNext()) {
            val dollId = keys.next()
            // 只恢复到确实存在的人偶上：孤儿记忆检索不到，也没有界面能看。
            if (DollStore.byId(ctx, dollId) == null) {
                continue
            }
            val arr = memObj.optJSONArray(dollId) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = Memory.fromJson(o)
                if (m.title.isEmpty()) {
                    continue
                }
                if (Mem.findByTitle(ctx, dollId, m.title) != null) {
                    continue
                }
                if (Mem.add(ctx, dollId, m)) {
                    memsAdded++
                }
            }
        }

        val sb = StringBuilder("恢复完成。")
        sb.append("\n新增供应商 ").append(providersAdded).append(" 个")
        sb.append("\n新增模型 ").append(modelsAdded).append(" 个")
        if (dollsMissing > 0) {
            sb.append("\n备份里有 ").append(dollsMissing)
                .append(" 只本机没有的人偶，未自动新建（缺封面与形象会变成坏角色）。")
        }
        if (memsAdded > 0) {
            sb.append("\n新增记忆 ").append(memsAdded).append(" 条")
        }
        if (providersAdded > 0) {
            sb.append("\n\nAPI Key 需要重新填写：备份里的密钥与本机密钥绑定，换机后无法解密。")
        }
        sb.append("\n封面与人偶图片不包含在备份里。")
        return sb.toString()
    }
}
