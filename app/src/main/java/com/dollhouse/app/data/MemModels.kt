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

        /**
         * 从存档对象取出「这条记忆的 id」—— **全工程唯一的 id 解析入口**。
         *
         * 【为什么必须唯一】存档里可能存在 `"id": ""`（外部 JSON、手工编辑、老版本写坏）。
         *   如果读取侧（UI 列表）与写入侧（update / remove）对同一个对象算出**不同**的 id，
         *   就会出现最难查的一类 bug：条目改不动、删不掉，但界面上看着一切正常。
         *   所以两边都必须走这里。
         *
         * 【空串 id 的处理】`JSONObject.optString(name, fallback)` 只在 key **不存在或为 null**
         *   时返回 fallback；key 存在但值是空串时它返回空串 —— 不是 fallback。
         *   空 id 会直接崩 UI：记忆列表的 LazyColumn key 用的就是 id，两条空 id
         *   立刻抛 Duplicate key 把整页打白。
         *
         * 【为什么不用 newId() 兜底】newId() 带 System.currentTimeMillis()，每次调用都不同 →
         *   同一条记忆在两次读取之间"换了身份"：update / remove 永远命中不了它，
         *   MemPage 的定位滚动（anchorId）与高亮（highlightId）也全都失效，
         *   按 id 去重更是形同虚设（每次都不同，永远去不掉）。
         *   所以要一个**确定性**派生 id：同样的存档内容，算多少次都是同一个。
         *
         * 【派生规则】"mh" + createdAt + "-" + 内容哈希。
         *   · 稳定：只依赖存档字段，与调用次数无关；
         *   · 唯一：只有「createdAt 相同且标题正文等全同」的两条才会撞 ——
         *     那种情况它们在语义上就是同一条，收敛掉是对的；
         *   · 不与 newId() 冲突：newId() 形如 "m<millis>-<num>"，这里以 "mh" 开头。
         *   · 种子用 `optLong("createdAt", 0)` 而**不是** currentTimeMillis() 兜底 ——
         *     字段缺失时也要拿到恒定值，否则又不稳定了。
         */
        fun idFrom(o: JSONObject?): String {
            if (o == null) {
                return ""
            }
            val raw = o.optString("id", "")
            if (raw.isNotEmpty()) {
                return raw
            }
            val createdRaw = o.optLong("createdAt", 0L)
            val seed = o.optString("title", "") + "\u0000" + o.optString("content", "") +
                    "\u0000" + o.optString("source", "") + "\u0000" + createdRaw +
                    "\u0000" + o.optString("folderPath", "") + "\u0000" + o.optString("originDoll", "")
            return "mh" + createdRaw + "-" + Integer.toHexString(seed.hashCode())
        }

        fun fromJson(o: JSONObject?): Memory {
            val m = Memory()
            if (o == null) {
                return m
            }
            // id 走统一入口（含空串派生兜底），保证读取侧与 update/remove 侧算出同一个值。
            m.id = idFrom(o)
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