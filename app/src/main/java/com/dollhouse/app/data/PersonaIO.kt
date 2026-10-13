package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.core.Logs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】角色卡的导入 / 导出（r13 新增）。
 *
 * 【两种格式】
 *   ① **Dollhouse 自有格式**（`dollhouse_card`）：14 个字段全带，换手机 / 备份用它，无损。
 *   ② **Tavern 兼容格式**（`chara_card_v2` / `chara_card_v3`）：酒馆生态的事实标准。
 *      做它的唯一理由是「能直接用网上现成的角色卡」——这是普通用户最容易受益的一条。
 *
 * 【为什么导出仍以自有格式为主】Tavern 格式装不下「注入位置 / 优先级 / 输出替换」这些
 *   Dollhouse 扩展；只导 Tavern 会把一半设定丢掉。所以：**默认导自有格式**，
 *   Tavern 只用于「发给别人用其他软件读」这种场景。
 *
 * 【零依赖】只用 `org.json`，与全工程口径一致。
 */
object PersonaIO {

    /** 自有格式的标识字段。 */
    private const val SCHEMA = "dollhouse_card"

    /** 自有格式版本；以后加字段时升它，读盘时按版本兼容。 */
    private const val SCHEMA_VER = 1

    /**
     * 导出成自有格式 JSON 文本。
     *
     * @param name    人偶名（角色卡要带上，否则导入方不知道她叫什么）
     * @param persona 性格描述（自由文本）
     */
    @JvmStatic
    fun exportOwn(name: String?, persona: String?, spec: PersonaSpec?): String {
        val root = JSONObject()
        try {
            root.put("schema", SCHEMA)
            root.put("ver", SCHEMA_VER)
            root.put("name", name ?: "")
            root.put("persona", persona ?: "")
            root.put("spec", (spec ?: PersonaSpec()).toJson())
            root.put("exportedAt", System.currentTimeMillis())
        } catch (ignored: Throwable) {
        }
        return pretty(root)
    }

    /**
     * 导出成 Tavern 兼容格式（`chara_card_v2`）。
     *
     * 【字段映射】这是格式转换的核心，逐条说明，便于以后核对：
     *   · description     ← 性格描述（Tavern 里它就是「角色定义」的主字段）
     *   · personality     ← 说话风格
     *   · scenario        ← 世界观
     *   · first_mes       ← 开场白
     *   · mes_example     ← 示例对话
     *   · system_prompt   ← 常驻注入里位置为「人设之前」的第一条（语义最接近）
     *   · post_history_instructions ← 后置指令
     *   · character_book  ← 世界书（Tavern 的 lorebook 结构）
     *   · tags            ← 标签（按逗号拆）
     *   · extensions      ← 装不下的东西（注入位置 / 优先级 / 输出替换）挂在这里，
     *                        再导回来时能还原；其他软件读到会忽略，不影响兼容。
     */
    @JvmStatic
    fun exportTavern(name: String?, persona: String?, spec: PersonaSpec?): String {
        val s = spec ?: PersonaSpec()
        val root = JSONObject()
        try {
            root.put("spec", "chara_card_v2")
            root.put("spec_version", "2.0")
            val data = JSONObject()
            data.put("name", name ?: "")
            data.put("description", persona ?: "")
            data.put("personality", s.style)
            data.put("scenario", s.scenario)
            data.put("first_mes", s.greeting)
            data.put("mes_example", s.examples)
            data.put("creator_notes", s.brief)
            data.put("system_prompt", firstBefore(s))
            data.put("post_history_instructions", s.postscript)
            data.put("alternate_greetings", JSONArray())
            // 标签：用户写的是逗号分隔的自由文本，这里拆成数组。
            val tags = JSONArray()
            for (t in s.tags.split(',', '，')) {
                val v = t.trim()
                if (v.isNotEmpty()) {
                    tags.put(v)
                }
            }
            data.put("tags", tags)
            data.put("creator", "Dollhouse")
            data.put("character_version", "1.0")
            // 世界书：Tavern 的 character_book 结构。
            if (s.lore.isNotEmpty()) {
                data.put("character_book", tavernBook(s.lore))
            }
            // 扩展：把 Tavern 装不下的原样带上，再导回来时能完整还原。
            val ext = JSONObject()
            val dh = JSONObject()
            dh.put("schema", SCHEMA)
            dh.put("ver", SCHEMA_VER)
            dh.put("callsYou", s.callsYou)
            dh.put("youCall", s.youCall)
            dh.put("injections", JSONArray(s.injections.map { it.toJson() }))
            dh.put("rules", JSONArray(s.rules.map { it.toJson() }))
            ext.put("dollhouse", dh)
            data.put("extensions", ext)
            root.put("data", data)
        } catch (ignored: Throwable) {
        }
        return pretty(root)
    }

    private fun firstBefore(s: PersonaSpec): String {
        for (i in s.injections) {
            if (i.enabled && InjectPos.BEFORE == i.position && i.content.trim().isNotEmpty()) {
                return i.content
            }
        }
        return ""
    }

    private fun tavernBook(lore: List<LoreEntry>): JSONObject {
        val book = JSONObject()
        val entries = JSONArray()
        for (e in lore) {
            val o = JSONObject()
            o.put("name", e.name)
            o.put("content", e.content)
            val keys = JSONArray()
            for (k in e.keywords) {
                keys.put(k)
            }
            o.put("keys", keys)
            o.put("enabled", e.enabled)
            o.put("constant", e.constantActive)
            o.put("insertion_order", e.priority)
            entries.put(o)
        }
        book.put("name", "Dollhouse 世界书")
        book.put("entries", entries)
        return book
    }

    /**
     * 导入结果。
     *
     * @param name    卡里带的角色名（可能为空）
     * @param persona 性格描述
     * @param spec    结构化设定
     * @param source  识别到的来源（用于界面提示「这是酒馆卡 / Dollhouse 卡」）
     */
    class Imported(val name: String, val persona: String, val spec: PersonaSpec, val source: String)

    /**
     * 解析一份角色卡文本。
     *
     * 【识别顺序】先看是不是 Dollhouse 自有格式（有 schema 字段），再看 Tavern v2/v3
     *   （有 spec + data），最后退化到「裸 JSON 对象」—— 有些卡只写了 `description`
     *   一层。三层都试不出内容时返回 null，由调用方提示「不是能识别的角色卡」。
     *
     * 【为什么不抛异常】用户从网上随便下了个 json 就点导入是常态，报错弹窗要有用，
     *   不能让一个格式错误直接把页面搞崩。
     */
    @JvmStatic
    fun parse(text: String?): Imported? {
        val t = text?.trim() ?: return null
        if (t.isEmpty()) {
            return null
        }
        val root = try {
            JSONObject(t)
        } catch (ignored: Throwable) {
            Logs.i("DollhousePersona", "[import] 不是 JSON")
            return null
        }
        // ---- ① 自有格式 ----
        if (SCHEMA == root.optString("schema")) {
            return Imported(
                root.optString("name", ""),
                root.optString("persona", ""),
                PersonaSpec.fromJson(root.optJSONObject("spec")),
                "Dollhouse"
            )
        }
        // ---- ② Tavern v2 / v3 ----
        val data = root.optJSONObject("data")
        if (data != null && root.optString("spec").startsWith("chara_card")) {
            return fromTavern(root.optString("spec"), data)
        }
        // ---- ③ 裸对象：可能直接就是 data 那一层，也可能整份卡没有 spec 标记 ----
        if (data != null && (data.has("description") || data.has("first_mes"))) {
            return fromTavern("chara_card_v2", data)
        }
        if (root.has("description") || root.has("personality") || root.has("first_mes")) {
            return fromTavern("chara_card_v2", root)
        }
        Logs.i("DollhousePersona", "[import] 认不出结构的键：" + root.keys().asSequence().joinToString(","))
        return null
    }

    private fun fromTavern(spec: String, data: JSONObject): Imported {
        val s = PersonaSpec()
        s.style = data.optString("personality", "")
        s.scenario = data.optString("scenario", "")
        s.greeting = data.optString("first_mes", "")
        s.examples = data.optString("mes_example", "")
        s.brief = data.optString("creator_notes", "")
        s.postscript = data.optString("post_history_instructions", "")
        // 标签数组 → 逗号分隔。
        val tags = data.optJSONArray("tags")
        if (tags != null) {
            val sb = StringBuilder()
            for (i in 0 until tags.length()) {
                val v = tags.optString(i, "").trim()
                if (v.isEmpty()) {
                    continue
                }
                if (sb.isNotEmpty()) {
                    sb.append('，')
                }
                sb.append(v)
            }
            s.tags = sb.toString()
        }
        // system_prompt → 一条「人设之前」的常驻注入（与导出时同口径，往返一致）。
        val sp = data.optString("system_prompt", "").trim()
        if (sp.isNotEmpty()) {
            val inj = PersonaInject()
            inj.name = "系统提示词"
            inj.content = sp
            inj.position = InjectPos.BEFORE
            s.injections.add(inj)
        }
        // 世界书：Tavern 的 character_book。
        val book = data.optJSONObject("character_book")
        if (book != null) {
            val entries = book.optJSONArray("entries")
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val le = LoreEntry()
                    le.name = e.optString("name", "")
                    le.content = e.optString("content", "")
                    le.enabled = e.optBoolean("enabled", true)
                    le.constantActive = e.optBoolean("constant", false)
                    le.priority = e.optInt("insertion_order", 0)
                    val keys = e.optJSONArray("keys")
                    if (keys != null) {
                        for (k in 0 until keys.length()) {
                            val v = keys.optString(k, "").trim()
                            if (v.isNotEmpty()) {
                                le.keywords.add(v)
                            }
                        }
                    }
                    s.lore.add(le)
                }
            }
        }
        // 扩展：优先吃自己的扩展（完整还原），否则忽略别人的扩展。
        val ext = data.optJSONObject("extensions")
        val dh = if (ext == null) null else ext.optJSONObject("dollhouse")
        if (dh != null) {
            s.callsYou = dh.optString("callsYou", "")
            s.youCall = dh.optString("youCall", "")
            val injs = dh.optJSONArray("injections")
            if (injs != null) {
                for (i in 0 until injs.length()) {
                    try {
                        s.injections.add(PersonaInject.fromJson(injs.optJSONObject(i)))
                    } catch (ignored: Throwable) {
                    }
                }
            }
            val rules = dh.optJSONArray("rules")
            if (rules != null) {
                for (i in 0 until rules.length()) {
                    try {
                        s.rules.add(OutputRule.fromJson(rules.optJSONObject(i)))
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
        val src = if (spec.contains("v3")) "酒馆 v3" else "酒馆 v2"
        return Imported(data.optString("name", ""), data.optString("description", ""), s, src)
    }

    /**
     * 把设定落进某只人偶。
     *
     * 【覆盖策略】整体覆盖而不是合并 —— 导入的语义是「把这张卡用作她的设定」，
     *   半合并会让用户完全无法预期结果。
     * 【名字】卡里有名字且当前人偶名是空白时才写入，避免静默改掉用户起的名字。
     */
    @JvmStatic
    fun apply(ctx: Context, dollId: String?, imported: Imported): Boolean {
        if (dollId.isNullOrEmpty()) {
            return false
        }
        try {
            DollProfile.setPersona(ctx, dollId, imported.persona)
            DollProfile.setSpec(ctx, dollId, imported.spec)
            return true
        } catch (t: Throwable) {
            Logs.w("DollhousePersona", "apply 失败", t)
            return false
        }
    }

    /** 把 JSON 排成两空格缩进的多行文本，方便用户直接看 / 存文件。 */
    private fun pretty(o: JSONObject): String {
        return try {
            // Android 的 org.json 没有内置 pretty；这里手写一层（只处理对象与数组）。
            o.toString(2)
        } catch (ignored: Throwable) {
            o.toString()
        }
    }
}