package com.dollhouse.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】把「角色设定」拼成真正下发给模型的 system 消息（r13 新增）。
 *
 * 【为什么单独一层】改造前这件事塞在 [ChatHistoryStore.buildRequest] 里，只有一句
 *   「人设 = 一段文本」。现在要处理：分段拼接、注入位置排序、世界书命中、
 *   输出替换、后置指令 —— 再塞进去会让那个方法变成一锅粥，也没法单独预览。
 *   独立成类后，「实际发出去的提示词」与「界面上的预览」走**同一条代码路径**，
 *   预览因此天然可信（这点很关键：预览要是另写一套，就会与实际不符）。
 *
 * 【零依赖】只用 `org.json` 与 Android 的 `Context`，不引入任何第三方库。
 */
object PersonaBuilder {

    /**
     * 组装结果。
     *
     * @param messages 按「自上而下」排好序的消息列表；每项形如 `{"role": "system", "content": "..."}`
     * @param matchedLore 本次命中的世界书条目名（供预览页显示「哪几条生效了」，便于排查）
     */
    class Built(val messages: JSONArray, val matchedLore: List<String>)

    /**
     * 组装某一轮请求的全部人设消息。
     *
     * @param history 完整历史（世界书要靠它扫关键词）；可为 null
     * @param query   本轮用户输入（一并参与世界书匹配）；可为 null
     */
    @JvmStatic
    fun build(ctx: Context, dollId: String?, name: String?, history: JSONArray?, query: String?): Built {
        val who = if (dollId.isNullOrEmpty()) DollStore.DEFAULT_ID else dollId
        val spec = DollProfile.spec(ctx, who)
        val persona = DollProfile.persona(ctx, who)
        val out = JSONArray()
        val matched = ArrayList<String>()

        // ---- ① 人设正文：把分段拼成一段自然语言 ----
        // 【为什么合成一条而不是一条一段】人设是「同一个人的整体形象」，拆成多条 system
        //   会让模型把它当互相独立的指令；合成一段更贴近「读一份人物设定」的直觉。
        val personaText = composePersona(name, persona, spec)
        if (personaText.isNotEmpty()) {
            out.put(sys(personaText, "persona"))
        }

        // ---- ② 注入（人设之前 / 之后）：常驻注入 + 命中的世界书 ----
        val injections = collectInjections(spec, history, query, matched)
        for (it in injections) {
            val text = it.content.trim()
            if (text.isEmpty()) {
                continue
            }
            if (InjectPos.BEFORE == it.position) {
                // 「人设之前」要插在**最前面**：JSONArray 没有 insert，翻一次数组。
                prepend(out, sys(text, tagOf(it)))
            } else if (InjectPos.AFTER == it.position) {
                out.put(sys(text, tagOf(it)))
            }
        }
        return Built(out, matched)
    }

    /**
     * 收集本次请求要用的注入项（常驻 + 命中），按「位置 → 优先级」排好。
     *
     * 【排序口径】同一位置内优先级小的在前、大的在后 —— 越靠后越接近对话，
     *   模型越容易照做。这是通用提示词工程的共识，与 rikkahub 的 priority 语义一致。
     */
    @JvmStatic
    fun collectInjections(spec: PersonaSpec, history: JSONArray?, query: String?,
                          matchedOut: MutableList<String>?): List<PersonaInject> {
        val list = ArrayList<PersonaInject>()
        // 上下文文本：最近 N 条 + 本轮输入。每个条目自己决定扫描深度。
        for (inj in spec.injections) {
            if (!inj.enabled || inj.content.trim().isEmpty()) {
                continue
            }
            list.add(inj)
        }
        for (e in spec.lore) {
            if (!e.enabled || e.content.trim().isEmpty()) {
                continue
            }
            if (!isLoreHit(e, history, query)) {
                continue
            }
            matchedOut?.add(if (e.name.isNotEmpty()) e.name else e.id)
            // 世界书条目复用注入的数据结构下发：位置与优先级同口径。
            val v = PersonaInject()
            v.id = e.id
            v.name = e.name
            v.content = e.content
            v.position = e.position
            v.priority = e.priority
            v.role = "system"
            v.enabled = true
            list.add(v)
        }
        list.sortWith(Comparator { a, b ->
            val pa = posOrder(a.position)
            val pb = posOrder(b.position)
            if (pa != pb) {
                pa - pb
            } else {
                a.priority - b.priority
            }
        })
        return list
    }

    /** 位置排序权重：之前 → 之后 → 对话最前 → 最新消息前。 */
    private fun posOrder(p: String?): Int {
        return when (p) {
            InjectPos.BEFORE -> 0
            InjectPos.TOP -> 2
            InjectPos.BOTTOM -> 3
            else -> 1
        }
    }

    /** 世界书是否命中。 */
    @JvmStatic
    fun isLoreHit(e: LoreEntry, history: JSONArray?, query: String?): Boolean {
        if (e.constantActive) {
            return true
        }
        if (e.keywords.isEmpty()) {
            return false
        }
        val sb = StringBuilder()
        if (history != null) {
            val from = if (history.length() > e.scanDepth) history.length() - e.scanDepth else 0
            for (i in from until history.length()) {
                val o = history.optJSONObject(i) ?: continue
                if ("system" == o.optString("role")) {
                    continue
                }
                sb.append(o.optString("content", "")).append('\n')
            }
        }
        if (!query.isNullOrEmpty()) {
            sb.append(query)
        }
        val ctxText = sb.toString()
        if (ctxText.isEmpty()) {
            return false
        }
        for (k in e.keywords) {
            if (k.isEmpty()) {
                continue
            }
            if (e.useRegex) {
                try {
                    val opts = if (e.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    if (Regex(k, opts).containsMatchIn(ctxText)) {
                        return true
                    }
                } catch (ignored: Throwable) {
                    // 正则写错：不命中，不崩 —— 界面上会提示「正则无效」。
                }
            } else if (e.caseSensitive) {
                if (ctxText.contains(k)) {
                    return true
                }
            } else if (ctxText.contains(k, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * 把分段合成一段人设正文。
     *
     * 【顺序】性格描述 → 一句话简介（只在没有性格描述时作为兜底）→ 世界观 → 说话风格 → 称呼。
     *   【为什么性格描述在最前】它是用户写得最用心、也最能定义「她是谁」的一段，
     *   放最前能让模型第一时间抓住主体；其余分段是对它的细化。
     */
    private fun composePersona(name: String?, persona: String, spec: PersonaSpec): String {
        val sb = StringBuilder()
        val p = persona.trim()
        if (p.isNotEmpty()) {
            sb.append(p)
        }
        if (spec.scenario.trim().isNotEmpty()) {
            if (sb.isNotEmpty()) {
                sb.append("\n\n")
            }
            sb.append("【世界观】").append(spec.scenario.trim())
        }
        if (spec.style.trim().isNotEmpty()) {
            if (sb.isNotEmpty()) {
                sb.append("\n\n")
            }
            sb.append("【说话风格】").append(spec.style.trim())
        }
        // 称呼：两条合成一句，避免「她怎么叫你」「你怎么叫她」各占一段。
        val cy = spec.callsYou.trim()
        val yc = spec.youCall.trim()
        if (cy.isNotEmpty() || yc.isNotEmpty()) {
            if (sb.isNotEmpty()) {
                sb.append("\n\n")
            }
            sb.append("【称呼】")
            if (cy.isNotEmpty()) {
                sb.append("你称呼主人为「").append(cy).append("」")
            }
            if (yc.isNotEmpty()) {
                if (cy.isNotEmpty()) {
                    sb.append("；")
                }
                sb.append("主人称呼你为「").append(yc).append("」")
            }
            sb.append("。")
        }
        // 名字：只在用户没在正文里自报姓名时补一句，避免重复。
        val nm = name?.trim() ?: ""
        if (nm.isNotEmpty() && sb.indexOf(nm) < 0) {
            sb.insert(0, "【名字】你叫「" + nm + "」。\n")
        }
        return sb.toString()
    }

    /** 示例对话：单独一段，标注「示范语气」让模型明白它的用途。 */
    @JvmStatic
    fun exampleText(spec: PersonaSpec): String {
        val e = spec.examples.trim()
        if (e.isEmpty()) {
            return ""
        }
        return "【对话示例】以下是语气示范，请模仿这种说话方式，不要把示例内容当作正在发生的事：\n" + e
    }

    private fun tagOf(it: PersonaInject): String {
        return if (it.name.isNotEmpty()) it.name else it.id
    }

    private fun sys(text: String, name: String): JSONObject {
        val o = JSONObject()
        o.put("role", "system")
        o.put("content", text)
        return o
    }

    /** 往数组最前面插一项（`JSONArray` 无 insert，逐项搬一次）。 */
    private fun prepend(arr: JSONArray, item: JSONObject) {
        val tmp = ArrayList<Any>()
        for (i in 0 until arr.length()) {
            tmp.add(arr.opt(i))
        }
        // 先清空再从后往前放：`JSONArray` 不支持按下标插入。
        for (i in arr.length() - 1 downTo 0) {
            arr.remove(i)
        }
        arr.put(item)
        for (o in tmp) {
            arr.put(o)
        }
    }

    /**
     * 【输出替换】按规则清洗文本。
     *
     * @param visual 是否「只影响显示」的那一批（true = 取 visualOnly 的规则）
     * @param scopeHer true = 作用于她说的话
     * @param scopeMe true = 作用于主人说的话
     */
    @JvmStatic
    fun applyRules(spec: PersonaSpec, text: String?, visual: Boolean, scopeHer: Boolean, scopeMe: Boolean): String {
        val t = text ?: return ""
        if (spec.rules.isEmpty()) {
            return t
        }
        var out = t
        for (r in spec.rules) {
            if (!r.enabled || r.find.isEmpty()) {
                continue
            }
            if (r.visualOnly != visual) {
                continue
            }
            val hit = if (scopeHer && r.scopeHer) true else scopeMe && r.scopeMe
            if (!hit) {
                continue
            }
            out = try {
                out.replace(Regex(r.find), r.replace)
            } catch (ignored: Throwable) {
                // 正则写错：原样返回，不让她的话凭空消失。
                out
            }
        }
        return out
    }

    /**
     * 【提示词预览】把「这一轮实际会发出去的全部 system 消息」拼成一段可读文本。
     *
     * 【为什么要跟 [build] 共用代码】预览的意义就是「所见即所得」。
     *   另写一套拼装逻辑必然与实际漂移，那预览还不如不做。
     */
    @JvmStatic
    fun preview(ctx: Context, dollId: String?, name: String?, history: JSONArray?, query: String?): String {
        val built = build(ctx, dollId, name, history, query)
        val sb = StringBuilder()
        val spec = DollProfile.spec(ctx, dollId)
        for (i in 0 until built.messages.length()) {
            val o = built.messages.optJSONObject(i) ?: continue
            sb.append("─── system ───\n")
            sb.append(o.optString("content", "")).append("\n\n")
        }
        // 示例对话与后置指令在 build 里不进 messages（位置由调用方安排），预览里注明。
        val ex = exampleText(spec)
        if (ex.isNotEmpty()) {
            sb.append("─── system（对话示例）───\n").append(ex).append("\n\n")
        }
        val post = spec.postscript.trim()
        if (post.isNotEmpty()) {
            sb.append("─── system（后置指令，置于最末）───\n").append(post).append("\n\n")
        }
        if (built.matchedLore.isNotEmpty()) {
            sb.append("─── 本次命中的世界书 ───\n")
            for (n in built.matchedLore) {
                sb.append("· ").append(n).append('\n')
            }
        }
        return sb.toString().trim()
    }
}