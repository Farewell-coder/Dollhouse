package com.dollhouse.app.ai

import android.content.Context
import com.dollhouse.app.data.Mem
import com.dollhouse.app.data.Memory
import com.dollhouse.app.data.PetPrefs
import org.json.JSONArray

/**
 * 【职责】按相关性挑选记忆（对齐 Operit 的 `MemoryRepository.searchMemories`，去掉向量维）。
 *
 * 【三维打分】
 *   · keyword：查询词在 title / content 里命中的占比（按词切分后逐词累加），权重最高。
 *   · tag    ：查询词命中该条 tags 的数量。
 *   · edge   ：**关联传播** —— 先算出关键词得分，再顺着记忆连接给相邻条目加分。
 *     例子：问「我的猫怎么样」，命中「养了猫」后，连着的「猫上周生病」也会被捞出来。
 *
 * 【为什么不用向量】工程零 embedding 依赖，SharedPreferences 也塞不下几百个 float。
 *   关键词 + 标签 + 关联三维对「桌宠日常对话」这个量级完全够用。
 *
 * 【隔离】查的是某只人偶自己的库；关联传播也只在它的连接表里游走，不跨库。
 */
object MemSearch {
    /** 返回条数上限。 */
    private const val TOP_K = 8

    /** 关联传播的衰减系数：一跳得分 = 源分 × 权重 × 该值。 */
    private const val EDGE_DECAY = 0.5

    /** 命中得分低于该值视为不相关，直接丢弃。 */
    private const val MIN_SCORE = 0.15

    /** 中文常见的无意义字，参与切分时会造噪声，直接过滤。 */
    private val STOP = hashSetOf(
        "的", "了", "是", "在", "我", "你", "他", "她", "它", "们", "有", "和", "就",
        "不", "也", "都", "这", "那", "个", "上", "下", "吗", "呢", "吧", "啊", "嗯",
        "什么", "怎么", "为", "与", "把", "被", "给", "对", "从", "到", "会", "要", "能"
    )

    /**
     * 检索某只人偶的记忆。
     *
     * @param query 用户这一轮说的话（问题本身）。
     * @param dollId 目标人偶 —— 只查它的库。
     * @return 命中的条目（按得分降序，最多 [TOP_K] 条）；无命中返回空表。
     */
    @JvmStatic
    fun search(ctx: Context, dollId: String?, query: String?): List<Memory> {
        val q = (query ?: "").trim()
        val arr = Mem.list(ctx, dollId)
        if (arr.length() == 0) {
            return emptyList()
        }
        val all = ArrayList<Memory>()
        for (i in 0 until arr.length()) {
            all.add(Memory.fromJson(arr.optJSONObject(i)))
        }
        if (q.isEmpty()) {
            // 没有查询词（如开场）时退回「最近 N 条」，保证仍能带上记忆。
            val out = ArrayList<Memory>()
            for (i in all.size - 1 downTo 0) {
                out.add(all[i])
                if (out.size >= TOP_K) {
                    break
                }
            }
            return out
        }

        val terms = tokenize(q)
        val wk = PetPrefs.mem2KeywordWeight(ctx).toDouble() / 10.0
        val wt = PetPrefs.mem2TagWeight(ctx).toDouble() / 10.0
        val we = PetPrefs.mem2EdgeWeight(ctx).toDouble() / 10.0

        // ① 基础分（关键词 + 标签）
        val score = HashMap<String, Double>()
        for (m in all) {
            val s = baseScore(m, terms, wk, wt)
            if (s > 0.0) {
                score[m.title] = s
            }
        }

        // ② 关联传播：顺着连接给相邻条目补分。
        if (we > 0.0 && score.isNotEmpty()) {
            propagate(ctx, dollId, score, we)
        }

        // ③ 排序取前 K
        val ranked = score.entries
            .filter { it.value >= MIN_SCORE }
            .sortedByDescending { it.value }
            .map { it.key }

        val out = ArrayList<Memory>()
        for (title in ranked) {
            val m = all.firstOrNull { it.title == title } ?: continue
            out.add(m)
            if (out.size >= TOP_K) {
                break
            }
        }
        return out
    }

    /** 拼成注入用正文（标题 + 正文一行条）。 */
    @JvmStatic
    fun joinForInject(list: List<Memory>): String {
        val sb = StringBuilder()
        for (m in list) {
            val line = if (m.title.isEmpty()) m.content else m.title + "：" + m.content
            if (sb.length > 0) {
                sb.append('\n')
            }
            sb.append(line)
        }
        return sb.toString()
    }

    /** 关键词 + 标签两部分得分。 */
    private fun baseScore(m: Memory, terms: List<String>, wk: Double, wt: Double): Double {
        val title = m.title
        val body = m.content
        var hit = 0.0
        for (t in terms) {
            if (title.contains(t)) {
                // 命中标题比命中正文更可信：标题短而聚焦。
                hit += 1.0
            } else if (body.contains(t)) {
                hit += 0.6
            }
        }
        if (hit <= 0.0) {
            return 0.0
        }
        val keyword = hit / terms.size
        var tagHit = 0.0
        for (t in terms) {
            for (tag in m.tags) {
                if (tag.contains(t) || t.contains(tag)) {
                    tagHit += 1.0
                    break
                }
            }
        }
        val tag = if (m.tags.isEmpty()) 0.0 else tagHit / m.tags.size
        // 重要性作为乘性微调：0.5 是中性，上下浮动 ±30%。
        val imp = 0.7 + 0.6 * m.importance.toDouble()
        return (keyword * wk + tag * wt) * imp
    }

    /**
     * 关联传播：对每个已命中条目，顺着它的边给邻居加分。
     *
     * 【为什么用一跳】两跳以上会把整张图都染上分，检索就退化成「全量返回」，
     *   与「挑最相关的几条」背道而驰。
     */
    private fun propagate(ctx: Context, dollId: String?, score: HashMap<String, Double>, we: Double) {
        val links = Mem.links(ctx, dollId)
        if (links.length() == 0) {
            return
        }
        val delta = HashMap<String, Double>()
        for (i in 0 until links.length()) {
            val o = links.optJSONObject(i) ?: continue
            val s = o.optString("source")
            val t = o.optString("target")
            val w = o.optDouble("weight", 0.5).toFloat().toDouble().coerceIn(0.0, 1.0)
            val ss = score[s] ?: 0.0
            val ts = score[t] ?: 0.0
            if (ss > 0.0) {
                delta[t] = (delta[t] ?: 0.0) + ss * w * EDGE_DECAY
            }
            if (ts > 0.0) {
                delta[s] = (delta[s] ?: 0.0) + ts * w * EDGE_DECAY
            }
        }
        for ((k, v) in delta) {
            score[k] = (score[k] ?: 0.0) + v * we
        }
    }

    /**
     * 极简分词：中文按「2 字滑窗」+ 英文/数字整词。
     *
     * 【为什么不上分词库】中文分词要引入词典（几 MB）且对口语短句收益有限；
     *   2 字滑窗对「猫生病」「项目闪退」这类短语的召回已经够用。
     */
    private fun tokenize(q: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in q) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
            } else {
                if (sb.isNotEmpty()) {
                    addTerm(out, sb.toString())
                    sb.setLength(0)
                }
            }
        }
        if (sb.isNotEmpty()) {
            addTerm(out, sb.toString())
        }
        return out
    }

    private fun addTerm(out: ArrayList<String>, word: String) {
        if (word.isEmpty()) {
            return
        }
        val isAscii = word.all { it.code < 128 }
        if (isAscii) {
            if (word.length >= 2 && !STOP.contains(word.lowercase())) {
                out.add(word.lowercase())
            }
            return
        }
        // 中文：整段（若 ≤4 字）+ 每个 2 字滑窗，避免长句拼不上。
        if (word.length <= 4 && !STOP.contains(word)) {
            out.add(word)
        }
        if (word.length > 1) {
            for (i in 0 until word.length - 1) {
                val g = word.substring(i, i + 2)
                if (!STOP.contains(g)) {
                    out.add(g)
                }
            }
        }
    }
}