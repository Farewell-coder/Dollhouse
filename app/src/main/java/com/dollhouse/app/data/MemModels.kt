package com.dollhouse.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】一条长期记忆（对齐 Operit 的 Memory 实体，去掉向量与文档字段）。
 *
 * 【字段说明】
 *   · [title]       简短标题，AI 抽取时要求「事件式」（如「[项目] 修好了闪退」）。
 *   · [content]     正文，只写已发生的事实与当前已确认状态。
 *   · [source]      来源：auto（AI 自动）/ manual（用户手写）/ chat（长按菜单存）。
 *   · [credibility] 可信度 0~1，AI 可给，默认 0.5。
 *   · [importance]  重要性 0~1，检索打分时加权，默认 0.5。
 *   · [folderPath]  所属文件夹（如「工作/项目A」），空串 = 未分类。
 *   · [tags]        标签数组。
 *   · [originDoll]  写入时所属人偶 id —— 冗余存一份便于排障时确认没串库。
 *
 * 【为什么不用 data class 直接 JSON 化】工程零序列化库依赖，
 *   手写 toJson/fromJson 比引入 kotlinx-serialization 更符合现有风格。
 */
class Memory {
    var id: String = newId()
    var title: String = ""
    var content: String = ""
    var source: String = SOURCE_AUTO
    var credibility: Float = 0.5f
    var importance: Float = 0.5f
    var contentType: String = "text/plain"
    var folderPath: String = ""
    var tags: MutableList<String> = ArrayList()
    var originDoll: String = ""
    var createdAt: Long = System.currentTimeMillis()
    var updatedAt: Long = System.currentTimeMillis()

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("id", id)
            o.put("title", title)
            o.put("content", content)
            o.put("source", source)
            o.put("credibility", credibility.toDouble())
            o.put("importance", importance.toDouble())
            o.put("contentType", contentType)
            o.put("folderPath", folderPath)
            val t = JSONArray()
            for (s in tags) {
                t.put(s)
            }
            o.put("tags", t)
            o.put("originDoll", originDoll)
            o.put("createdAt", createdAt)
            o.put("updatedAt", updatedAt)
        } catch (unused: Throwable) {
        }
        return o
    }

    companion object {
        /** AI 自动抽取写入。 */
        const val SOURCE_AUTO = "auto"

        /** 用户在记忆库页手写。 */
        const val SOURCE_MANUAL = "manual"

        /** 从聊天气泡长按「修改记忆」存下来。 */
        const val SOURCE_CHAT = "chat"

        fun newId(): String {
            return "m" + System.currentTimeMillis() + "-" + (Math.random() * 100000).toInt()
        }

        fun fromJson(o: JSONObject?): Memory {
            val m = Memory()
            if (o == null) {
                return m
            }
            m.id = o.optString("id", m.id)
            m.title = o.optString("title", "")
            m.content = o.optString("content", "")
            m.source = o.optString("source", SOURCE_AUTO)
            m.credibility = o.optDouble("credibility", 0.5).toFloat()
            m.importance = o.optDouble("importance", 0.5).toFloat()
            m.contentType = o.optString("contentType", "text/plain")
            m.folderPath = o.optString("folderPath", "")
            val t = o.optJSONArray("tags")
            if (t != null) {
                for (i in 0 until t.length()) {
                    val s = t.optString(i)
                    if (s.isNotEmpty()) {
                        m.tags.add(s)
                    }
                }
            }
            m.originDoll = o.optString("originDoll", "")
            m.createdAt = o.optLong("createdAt", System.currentTimeMillis())
            m.updatedAt = o.optLong("updatedAt", m.createdAt)
            return m
        }
    }
}

/**
 * 【职责】两条记忆之间的连接（图谱边）。
 *
 * 【类型】AI 抽取时会产出 UPPER_SNAKE_CASE 关系名：
 *   事件流程 FOLLOWS / CORRECTS / UPDATES；
 *   参与上下文 INVOLVES / HAPPENS_AT；
 *   世界观结构 PART_OF / ALLIED_WITH / OPPOSES。
 *
 * 【用途】检索打分时做「关联传播」——命中一条后，顺着边给相邻记忆加权，
 *   于是问「我的猫怎么样」不只能捞到「养了猫」，还能捞到「猫上周生病」。
 */
class MemLink {
    var source: String = ""
    var target: String = ""
    var type: String = "RELATED"
    var weight: Float = 0.5f
    var description: String = ""

    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("source", source)
            o.put("target", target)
            o.put("type", type)
            o.put("weight", weight.toDouble())
            o.put("description", description)
        } catch (unused: Throwable) {
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject?): MemLink {
            val l = MemLink()
            if (o == null) {
                return l
            }
            l.source = o.optString("source", "")
            l.target = o.optString("target", "")
            l.type = o.optString("type", "RELATED")
            l.weight = o.optDouble("weight", 0.5).toFloat()
            l.description = o.optString("description", "")
            return l
        }
    }
}