package com.dollhouse.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】「角色设定」的结构化模型与手写 JSON 编解码（r13 新增）。
 *
 * 【为什么要结构化】改造前只有一段自由文本（`persona_<dollId>`）。用户要求「性格自定义程度很高」，
 *   而 Operit 的角色卡与 rikkahub 的助手都证明：真正能拉开自定义上限的是**若干语义明确的分段**
 *   （性格 / 世界观 / 说话风格 / 开场白 / 示例对话）+ **注入控制**（世界书 / 位置 / 优先级）。
 *
 * 【为什么手写 JSON 而不是引入序列化库】本工程零新增第三方依赖（这是长期约束），
 *   而 `org.json` 已在全工程使用（历史、记忆、供应商全走它）。手写编解码与
 *   [Memory] / [MemLink] 同一套做法，读盘容错也一致：字段缺失一律取默认值，绝不抛。
 *
 * 【兼容】既有的 `persona` 自由文本**仍然是「性格描述」这一段的存储**（键名不变），
 *   老用户升级后原来写的那一大段会原样出现在新界面的「性格描述」里，一个字都不丢。
 */

/** 注入位置。取值与落盘字符串分离，避免以后改枚举顺序把老数据读歪。 */
object InjectPos {
    /** 人设之前。 */
    const val BEFORE = "before"

    /** 人设之后（最常用）。 */
    const val AFTER = "after"

    /** 对话最开头（第一条用户消息之前）。 */
    const val TOP = "top"

    /** 最新消息之前（当前用户输入之前）。 */
    const val BOTTOM = "bottom"

    /** 全部取值（界面按此顺序展示）。 */
    val ALL = arrayOf(BEFORE, AFTER, TOP, BOTTOM)

    /** 界面展示名。 */
    fun label(v: String?): String {
        return when (v) {
            BEFORE -> "人设之前"
            TOP -> "对话最前"
            BOTTOM -> "最新消息前"
            else -> "人设之后"
        }
    }
}

/**
 * 【常驻注入】一段「无条件塞进请求」的设定。
 *
 * 【跟世界书的分工】常驻注入不看对话内容、每次都进；世界书要**关键词命中**才进。
 *   前者适合「铁的规则」（如「永远不要跳出角色」），后者适合「聊到才用得上」的背景设定。
 */
class PersonaInject {
    var id: String = ""
    var name: String = ""
    var content: String = ""
    /** 见 [InjectPos]。 */
    var position: String = InjectPos.AFTER
    /** 越大越靠后（越晚注入 = 越接近对话末尾 = 模型越容易照做）。 */
    var priority: Int = 0
    /** 注入成什么角色。`system` 权重最高，`user` 更像「用户提醒」。 */
    var role: String = "system"
    var enabled: Boolean = true

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("id", id)
            o.put("name", name)
            o.put("content", content)
            o.put("position", position)
            o.put("priority", priority)
            o.put("role", role)
            o.put("enabled", enabled)
        } catch (ignored: Throwable) {
        }
        return o
    }

    companion object {
        @JvmStatic
        fun fromJson(o: JSONObject?): PersonaInject {
            val v = PersonaInject()
            if (o == null) {
                return v
            }
            v.id = o.optString("id", "")
            v.name = o.optString("name", "")
            v.content = o.optString("content", "")
            v.position = o.optString("position", InjectPos.AFTER)
            v.priority = o.optInt("priority", 0)
            v.role = o.optString("role", "system")
            v.enabled = o.optBoolean("enabled", true)
            if (v.id.isEmpty()) {
                v.id = java.util.UUID.randomUUID().toString().substring(0, 8)
            }
            return v
        }
    }
}

/**
 * 【世界书条目】关键词命中才注入的一段设定。
 *
 * 【为什么值得做】这是「角色卡能装下整个世界」的关键：把支线设定（某个人物、某个地点、
 *   某个事件）拆成条目，只在聊到的时候才占上下文 —— 不聊就完全不花 token。
 */
class LoreEntry {
    var id: String = ""
    var name: String = ""
    var content: String = ""
    /** 触发关键词；[constantActive] 为真时忽略。 */
    var keywords: MutableList<String> = ArrayList()
    /** 常驻激活：无需命中，每次都在（相当于一条常驻注入）。 */
    var constantActive: Boolean = false
    /** 关键词按正则解释。 */
    var useRegex: Boolean = false
    /** 大小写敏感（对英文关键词有意义）。 */
    var caseSensitive: Boolean = false
    /** 扫描最近 N 条消息找关键词。 */
    var scanDepth: Int = 4
    var position: String = InjectPos.AFTER
    var priority: Int = 0
    var enabled: Boolean = true

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("id", id)
            o.put("name", name)
            o.put("content", content)
            val arr = JSONArray()
            for (k in keywords) {
                arr.put(k)
            }
            o.put("keywords", arr)
            o.put("constantActive", constantActive)
            o.put("useRegex", useRegex)
            o.put("caseSensitive", caseSensitive)
            o.put("scanDepth", scanDepth)
            o.put("position", position)
            o.put("priority", priority)
            o.put("enabled", enabled)
        } catch (ignored: Throwable) {
        }
        return o
    }

    companion object {
        @JvmStatic
        fun fromJson(o: JSONObject?): LoreEntry {
            val v = LoreEntry()
            if (o == null) {
                return v
            }
            v.id = o.optString("id", "")
            v.name = o.optString("name", "")
            v.content = o.optString("content", "")
            val arr = o.optJSONArray("keywords")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) {
                        v.keywords.add(s)
                    }
                }
            }
            v.constantActive = o.optBoolean("constantActive", false)
            v.useRegex = o.optBoolean("useRegex", false)
            v.caseSensitive = o.optBoolean("caseSensitive", false)
            v.scanDepth = o.optInt("scanDepth", 4)
            v.position = o.optString("position", InjectPos.AFTER)
            v.priority = o.optInt("priority", 0)
            v.enabled = o.optBoolean("enabled", true)
            if (v.id.isEmpty()) {
                v.id = java.util.UUID.randomUUID().toString().substring(0, 8)
            }
            return v
        }
    }
}

/**
 * 【输出替换】把她的话按正则清洗一遍。
 *
 * 【典型用法】模型爱在句尾带「（笑）」这类动作描写，加一条把 `（.*?）` 换成空串即可。
 * 【作用范围】[scopeMe] / [scopeHer] 对应「我看到的」与「历史里存的」两种口径：
 *   · 只勾前者 = 仅视觉（历史原文保留，模型下一轮仍看得到）；
 *   · 勾上后者 = 落盘前替换（模型下轮也看不到被替换的内容）。
 */
class OutputRule {
    var id: String = ""
    var name: String = ""
    var find: String = ""
    var replace: String = ""
    var enabled: Boolean = true
    /** 只影响显示，不改历史。 */
    var visualOnly: Boolean = true
    /** 作用于她说的话。 */
    var scopeHer: Boolean = true
    /** 作用于主人说的话。 */
    var scopeMe: Boolean = false

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("id", id)
            o.put("name", name)
            o.put("find", find)
            o.put("replace", replace)
            o.put("enabled", enabled)
            o.put("visualOnly", visualOnly)
            o.put("scopeHer", scopeHer)
            o.put("scopeMe", scopeMe)
        } catch (ignored: Throwable) {
        }
        return o
    }

    companion object {
        @JvmStatic
        fun fromJson(o: JSONObject?): OutputRule {
            val v = OutputRule()
            if (o == null) {
                return v
            }
            v.id = o.optString("id", "")
            v.name = o.optString("name", "")
            v.find = o.optString("find", "")
            v.replace = o.optString("replace", "")
            v.enabled = o.optBoolean("enabled", true)
            v.visualOnly = o.optBoolean("visualOnly", true)
            v.scopeHer = o.optBoolean("scopeHer", true)
            v.scopeMe = o.optBoolean("scopeMe", false)
            if (v.id.isEmpty()) {
                v.id = java.util.UUID.randomUUID().toString().substring(0, 8)
            }
            return v
        }
    }
}

/** 「角色设定」里除「性格描述」之外的整份结构化数据（性格描述单独存，见 [DollProfile.persona]）。 */
class PersonaSpec {
    /** 一句话简介（列表卡片上显示）。 */
    var brief: String = ""

    /** 世界观 / 场景。 */
    var scenario: String = ""

    /** 说话风格（语气、口头禅、习惯）。 */
    var style: String = ""

    /** 称呼方式（她怎么叫你）。 */
    var callsYou: String = ""

    /** 你怎么叫她。 */
    var youCall: String = ""

    /** 开场白：新会话建立时她说的第一句。 */
    var greeting: String = ""

    /** 示例对话：给模型照抄语气的示范。 */
    var examples: String = ""

    /** 标签（自由文本，逗号分隔）。 */
    var tags: String = ""

    /** 后置指令：放在请求最末尾，权重最高。 */
    var postscript: String = ""

    /** 常驻注入列表。 */
    var injections: MutableList<PersonaInject> = ArrayList()

    /** 世界书条目。 */
    var lore: MutableList<LoreEntry> = ArrayList()

    /** 输出替换规则。 */
    var rules: MutableList<OutputRule> = ArrayList()

    /** 是否有任何结构化内容（用于界面显示「已设置 / 未设置」与提示词预览判空）。 */
    fun isEmpty(): Boolean {
        return brief.isEmpty() && scenario.isEmpty() && style.isEmpty()
                && callsYou.isEmpty() && youCall.isEmpty() && greeting.isEmpty()
                && examples.isEmpty() && tags.isEmpty() && postscript.isEmpty()
                && injections.isEmpty() && lore.isEmpty() && rules.isEmpty()
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("brief", brief)
            o.put("scenario", scenario)
            o.put("style", style)
            o.put("callsYou", callsYou)
            o.put("youCall", youCall)
            o.put("greeting", greeting)
            o.put("examples", examples)
            o.put("tags", tags)
            o.put("postscript", postscript)
            o.put("injections", arr(injections.map { it.toJson() }))
            o.put("lore", arr(lore.map { it.toJson() }))
            o.put("rules", arr(rules.map { it.toJson() }))
        } catch (ignored: Throwable) {
        }
        return o
    }

    companion object {
        private fun arr(list: List<JSONObject>): JSONArray {
            val a = JSONArray()
            for (o in list) {
                a.put(o)
            }
            return a
        }

        /** 逐项读取；任何一项坏掉都只丢那一项，不影响其余（老数据 / 手改文件都容错）。 */
        @JvmStatic
        fun fromJson(o: JSONObject?): PersonaSpec {
            val v = PersonaSpec()
            if (o == null) {
                return v
            }
            v.brief = o.optString("brief", "")
            v.scenario = o.optString("scenario", "")
            v.style = o.optString("style", "")
            v.callsYou = o.optString("callsYou", "")
            v.youCall = o.optString("youCall", "")
            v.greeting = o.optString("greeting", "")
            v.examples = o.optString("examples", "")
            v.tags = o.optString("tags", "")
            v.postscript = o.optString("postscript", "")
            val inj = o.optJSONArray("injections")
            if (inj != null) {
                for (i in 0 until inj.length()) {
                    try {
                        v.injections.add(PersonaInject.fromJson(inj.optJSONObject(i)))
                    } catch (ignored: Throwable) {
                    }
                }
            }
            val lore = o.optJSONArray("lore")
            if (lore != null) {
                for (i in 0 until lore.length()) {
                    try {
                        v.lore.add(LoreEntry.fromJson(lore.optJSONObject(i)))
                    } catch (ignored: Throwable) {
                    }
                }
            }
            val rules = o.optJSONArray("rules")
            if (rules != null) {
                for (i in 0 until rules.length()) {
                    try {
                        v.rules.add(OutputRule.fromJson(rules.optJSONObject(i)))
                    } catch (ignored: Throwable) {
                    }
                }
            }
            return v
        }
    }
}
