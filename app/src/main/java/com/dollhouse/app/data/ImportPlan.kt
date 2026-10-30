package com.dollhouse.app.data

import java.util.Locale

/**
 * 【职责】导入识别的**纯逻辑**部分：把一堆文件名/路径变成「四个状态槽各有哪些帧」。
 *
 * ## 为什么单独抽出来（可测试是硬要求）
 *
 * 识别规则看起来简单，实际全是边界：
 * 自然排序（`1, 10, 2` 必须排成 `1, 2, 10`）、大小写混用、`edge_loop` 与 `edge_in` 前缀互相包含、
 * `manual` / `auto` 下面还有二级子目录（一个子目录 = 一个动作）、
 * 同尺寸多图要不要问「这是一组待机动画吗」、以及**同时识别到两个角色**必须报歧义。
 *
 * 这些规则一旦只在 UI 里 if-else，就只能靠真机手点验证，且改一次要重测一遍。
 * 所以这里保持**零 Android 依赖**（不碰 Context / Bitmap），用 JUnit 全覆盖。
 *
 * ## 与需求条款的对应
 *
 * - R-070：识别顺序 = 文件夹名 → 文件名前缀 → ZIP 目录结构 → 单文件默认待机；
 * - R-071：自然排序；
 * - R-072：同一状态多组素材必须让用户选，不得静默覆盖；
 * - R-073：识别到两个角色 → 提示分别导入；
 * - R-012（v2.1·19.3）：四个状态的缺失降级，这里给出**结构化的缺失清单**，
 *   由调用方决定文案，而不是在核心层拼中文。
 */
object ImportPlan {

    /** 四个固定状态 + 两组普通动作。 */
    enum class Slot {
        /** 站着的她（常驻 / 待机）。 */
        IDLE,
        /** 拖动时的她。 */
        DRAG,
        /** 贴入瞬间（只播一次）。 */
        EDGE_IN,
        /** 贴边常驻（循环）。 */
        EDGE_LOOP,
        /** 自动动作（随机播放）。 */
        AUTO,
        /** 手动动作（点一下播一个）。 */
        MANUAL
    }

    /**
     * 一个被识别出来的「动作组」：同一槽位下的连续帧。
     *
     * @param key    分组键（`idle` / `drag` / `auto/wave` 这种），用于同名多组时区分
     * @param slot   落到哪个槽位
     * @param files  已按自然顺序排好的文件路径
     * @param name   给用户看的名字（取分组目录名或文件名主干）
     */
    data class Group(
        val key: String,
        val slot: Slot,
        val files: List<String>,
        val name: String
    )

    /** 识别结果。 */
    data class Result(
        /** 每个槽位识别到的**全部**分组（同槽多组 = 歧义，必须让用户选，R-072）。 */
        val bySlot: Map<Slot, List<Group>>,
        /** 落在「自动 / 手动」里的额外动作组。 */
        val extras: List<Group>,
        /** 完全认不出槽位的文件（单张图 / 动图 / Lottie 走「默认待机」，见 R-070 第 4 条）。 */
        val loose: List<String>,
        /** 结构性警告（面向开发者/日志；面向用户的文案由 UI 决定）。 */
        val warnings: List<Warning>
    ) {
        /** 某槽位选定的那一组（多组时取第一组，实际应由用户选）。 */
        fun groupOf(slot: Slot): Group? = bySlot[slot]?.firstOrNull()

        /** 某槽位是否已设置（按「有可用帧」判，不是看有没有壳）。 */
        fun has(slot: Slot): Boolean = (groupOf(slot)?.files?.isNotEmpty()) == true

        /** 缺失的固定状态（按固定顺序）。 */
        fun missingFixed(): List<Slot> = FIXED_SLOTS.filter { !has(it) }

        /** 同槽多组 = 必须有选择交互，不得静默覆盖。 */
        fun ambiguousSlots(): List<Slot> =
            FIXED_SLOTS.filter { (bySlot[it]?.size ?: 0) > 1 }

        /**
         * 是否「看起来是两个角色」（R-073）。
         *
         * 【判据】同一槽位下出现**两个以上互不包含的同级目录组**，且这些组各自都有 ≥2 帧。
         *   单张散图数量再多也不判成两个角色 —— 那是「一组待机帧」的常见形态（R-070 第 5 条走询问）。
         */
        fun looksLikeTwoRoles(): Boolean =
            FIXED_SLOTS.any { slot ->
                val gs = bySlot[slot] ?: return@any false
                gs.count { it.files.size >= 2 } >= 2
            }
    }

    /** 结构性问题（给日志和调试用，不直接做用户文案）。 */
    enum class Warning {
        /** 同槽位出现多组素材（需用户选）。 */
        MULTI_GROUP_IN_SLOT,

        /** 多个角色混在一起（R-073）。 */
        MULTI_ROLE,

        /** 同一组内帧尺寸不一致（R-074，UI 提示「可能出现跳动」）。 */
        SIZE_MISMATCH,

        /** 帧数超出该状态推荐范围（9.3 节）。 */
        FRAME_COUNT_OUT_OF_RANGE,

        /** 既不是四个状态也不是自动/手动：按「一组待机帧」处理，需询问用户。 */
        LOOSE_AS_IDLE
    }

    /** 固定四态，顺序即界面顺序（需求 8.6：待机 / 拖拽 / 贴入瞬间 / 贴边常驻）。 */
    val FIXED_SLOTS: List<Slot> = listOf(Slot.IDLE, Slot.DRAG, Slot.EDGE_IN, Slot.EDGE_LOOP)

    /** 各状态的推荐帧数（9.3 节），用于给出「帧数偏少/偏多」的提示。 */
    fun frameRangeOf(slot: Slot): IntRange = when (slot) {
        Slot.IDLE -> 1..120
        Slot.DRAG -> 1..120
        Slot.EDGE_IN -> 1..120
        Slot.EDGE_LOOP -> 1..120
        Slot.AUTO -> 1..120
        Slot.MANUAL -> 1..120
    }

    /** 面向用户的中文状态名（R-053：不得出现 idle / drag 等内部词）。 */
    fun labelOf(slot: Slot): String = when (slot) {
        Slot.IDLE -> "待机"
        Slot.DRAG -> "拖拽"
        Slot.EDGE_IN -> "贴入瞬间"
        Slot.EDGE_LOOP -> "贴边常驻"
        Slot.AUTO -> "自动动作"
        Slot.MANUAL -> "手动动作"
    }

    /* ============================ 识别 ============================ */

    /**
     * 从一组「相对路径」识别出分组。
     *
     * @param paths 每个元素是形如 `小肥鱼/idle/001.png` 或 `idle_001.png` 或 `wave.png` 的**相对路径**
     *              （分隔符 `/` 或 `\` 都可以）。绝对前缀由调用方先剥掉。
     */
    fun analyze(paths: List<String>): Result {
        // ① 逐个归类：先看任一层目录名，再看文件名前缀（R-070 的顺序）。
        //   【为什么要「先目录后文件」】用户可能把整包导出成 `idle/me_001.png`，
        //   文件名里的 `me` 不带语义，而目录名 `idle` 是权威的。反过来，
        //   `idle_001.png` 直接摊在一个目录里时，目录名可能是 `新建文件夹`，无意义。
        val bucketed = LinkedHashMap<String, MutableList<String>>()
        val keyToSlot = LinkedHashMap<String, Slot>()
        val loose = ArrayList<String>()

        for (p in paths) {
            if (p.isBlank()) {
                continue
            }
            val norm = p.replace('\\', '/').trim()
            if (norm.isEmpty()) {
                continue
            }
            val segs = norm.split('/').filter { it.isNotEmpty() }
            if (segs.isEmpty()) {
                continue
            }
            val fileName = segs[segs.size - 1]
            // 跳过描述文件与常见垃圾文件：它们不是帧，参与分组反而会多出一个「组」。
            if (isSidecar(fileName) || isJunk(segs)) {
                continue
            }
            val hit = slotFromSegments(segs)
            if (hit == null) {
                loose.add(norm)
                continue
            }
            val (slot, key) = hit
            keyToSlot[key] = slot
            bucketed.getOrPut(key) { ArrayList() }.add(norm)
        }

        // ② 每组内按自然顺序排（R-071）。
        val bySlot = LinkedHashMap<Slot, MutableList<Group>>()
        val extras = ArrayList<Group>()
        for ((key, files) in bucketed) {
            val slot = keyToSlot[key] ?: continue
            val sorted = files.sortedWith { a, b -> naturalCompare(baseName(a), baseName(b)) }
            val g = Group(
                key = key,
                slot = slot,
                files = sorted,
                name = groupNameOf(key, sorted)
            )
            if (slot == Slot.AUTO || slot == Slot.MANUAL) {
                extras.add(g)
            } else {
                bySlot.getOrPut(slot) { ArrayList() }.add(g)
            }
        }

        // ③ 结构性警告。
        val warnings = ArrayList<Warning>()
        for (slot in FIXED_SLOTS) {
            val gs = bySlot[slot]
            if (gs != null && gs.size > 1) {
                warnings.add(Warning.MULTI_GROUP_IN_SLOT)
            }
        }
        if (bySlot.values.any { it.count { g -> g.files.size >= 2 } >= 2 }) {
            warnings.add(Warning.MULTI_ROLE)
        }
        if (loose.isNotEmpty()) {
            warnings.add(Warning.LOOSE_AS_IDLE)
        }

        return Result(
            bySlot = bySlot.mapValues { it.value.toList() },
            extras = extras,
            loose = loose.sortedWith { a, b -> naturalCompare(baseName(a), baseName(b)) },
            warnings = warnings.distinct()
        )
    }

    /**
     * 单文件 / 散图 → 「一组待机帧」。
     *
     * 【为什么单独一个入口】R-070 第 4、5 条：单个 GIF / WebP / Lottie 默认待机；
     *   多张同尺寸 PNG 要问一次「这是一组待机动画吗」。
     *   这两条都不该走目录识别（它们没有目录结构），但仍然要产出同一个 [Group] 形状，
     *   好让后续预览 / 提交只有一条路径。
     */
    fun looseAsIdle(result: Result): Group? {
        if (result.loose.isEmpty()) {
            return null
        }
        return Group(
            key = "loose",
            slot = Slot.IDLE,
            files = result.loose,
            name = groupNameOf("loose", result.loose)
        )
    }

    /**
     * 判定「多张散图是否像一组待机动画」——给 UI 决定要不要问那一句。
     *
     * 【判据】至少 2 张、全部是图片类扩展名。尺寸是否一致由调用方在解码后判断
     *   （本对象不碰 Bitmap，保持可单测）。
     */
    fun looksLikeIdleFrames(files: List<String>): Boolean {
        if (files.size < 2) {
            return false
        }
        return files.all { isImageName(it) }
    }

    /* ============================ 归类细节 ============================ */

    /**
     * 按「目录名优先、文件名前缀其次」判断槽位。
     *
     * @return `(槽位, 分组键)`；认不出返回 null。
     *         分组键的含义：固定状态用槽位名（`idle`），自动/手动带二级子目录（`auto/wave`）。
     */
    private fun slotFromSegments(segs: List<String>): Pair<Slot, String>? {
        // ① 目录名：从最内层目录往外找，第一个认得的就是它。
        //   【为什么从内往外】`auto/wave/001.png` 里 `wave` 是动作名（认不出），
        //   `auto` 才是语义；反过来 `idle/wave/001.png` 同理。
        //   从内往外找，`wave` 认不出就继续到 `auto`，行为正确。
        var dirSlot: Slot? = null
        var dirIndex = -1
        for (i in segs.size - 2 downTo 0) {
            val s = slotOfToken(segs[i])
            if (s != null) {
                dirSlot = s
                dirIndex = i
                break
            }
        }
        if (dirSlot != null) {
            // ② 二级目录（自动 / 手动的动作名）：取语义目录之后的全部目录段。
            val sub = if (dirIndex >= 0 && dirIndex < segs.size - 2) {
                segs.subList(dirIndex + 1, segs.size - 1).joinToString("/")
            } else {
                ""
            }
            val key = if (sub.isEmpty()) keyOf(dirSlot) else keyOf(dirSlot) + "/" + sub
            return Pair(dirSlot, key)
        }
        // ③ 文件名前缀：`idle_001.png` / `drag-1.png` / `edge_loop_12.png`。
        val name = segs[segs.size - 1]
        val prefix = prefixSlotOf(name)
        if (prefix != null) {
            return Pair(prefix, keyOf(prefix))
        }
        return null
    }

    /** 词 → 槽位。大小写、连字符、空格都归一化（`Edge In` / `edge-in` 等价）。 */
    fun slotOfToken(raw: String): Slot? {
        val t = raw.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')
        return when (t) {
            "idle", "stand", "待机", "常驻" -> Slot.IDLE
            "drag", "拖拽", "拖动" -> Slot.DRAG
            "edge_in", "edgein", "贴入", "贴入瞬间" -> Slot.EDGE_IN
            "edge_loop", "edgeloop", "edge", "贴边", "贴边常驻" -> Slot.EDGE_LOOP
            "auto", "自动", "自动动作" -> Slot.AUTO
            "manual", "手动", "手动动作" -> Slot.MANUAL
            else -> null
        }
    }

    /**
     * 文件名前缀识别。
     *
     * 【顺序陷阱】`edge_loop_001` 必须先于 `edge_in` 判，因为 `edge_` 是公共前缀；
     *   而 `edge_in_001` 与 `edge_loop_001` 互不为前缀，所以实际只需「长词优先」。
     */
    private fun prefixSlotOf(fileName: String): Slot? {
        val n = fileName.lowercase(Locale.US)
        val ordered = listOf(
            "edge_loop" to Slot.EDGE_LOOP,
            "edgeloop" to Slot.EDGE_LOOP,
            "edge_in" to Slot.EDGE_IN,
            "edgein" to Slot.EDGE_IN,
            "idle" to Slot.IDLE,
            "drag" to Slot.DRAG,
            "auto" to Slot.AUTO,
            "manual" to Slot.MANUAL
        )
        for ((token, slot) in ordered) {
            if (!n.startsWith(token)) {
                continue
            }
            // 必须跟着分隔符，否则 `idle2.png` 会被当成 idle 前缀（那是另一个词）。
            val rest = n.substring(token.length)
            if (rest.isEmpty()) {
                return slot
            }
            val c = rest[0]
            if (c == '_' || c == '-' || c == '.' || c == ' ' || c.isDigit()) {
                return slot
            }
        }
        return null
    }

    /** 槽位 → 分组键（固定用英文，仅内部使用，不进 UI 文案）。 */
    fun keyOf(slot: Slot): String = when (slot) {
        Slot.IDLE -> "idle"
        Slot.DRAG -> "drag"
        Slot.EDGE_IN -> "edge_in"
        Slot.EDGE_LOOP -> "edge_loop"
        Slot.AUTO -> "auto"
        Slot.MANUAL -> "manual"
    }

    /** 分组键 → 槽位；带二级目录的（`auto/wave`）取第一段。 */
    fun slotOfKey(key: String): Slot? = slotOfToken(key.substringBefore('/'))

    /* ============================ 文件名工具 ============================ */

    fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    /** 去掉扩展名的主干。 */
    fun stemOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }

    /** 组名：优先用分组键里最后一段（动作名），没有就用第一帧的主干。 */
    private fun groupNameOf(key: String, files: List<String>): String {
        val tail = key.substringAfterLast('/')
        val generic = tail == "idle" || tail == "drag" || tail == "edge_in" ||
            tail == "edge_loop" || tail == "auto" || tail == "manual" || tail == "loose"
        if (!generic) {
            return tail
        }
        val first = files.firstOrNull() ?: return tail
        val stem = stemOf(baseName(first))
        // `idle_001` → `idle_001` 去掉语义前缀后只剩 `001`，没意义；这种就退回组名。
        val stripped = stripPrefix(stem)
        return stripped.ifBlank { tail }
    }

    /** 去掉 `idle_` / `edge_loop-` 这类语义前缀。 */
    private fun stripPrefix(stem: String): String {
        val lower = stem.lowercase(Locale.US)
        val tokens = listOf("edge_loop", "edgeloop", "edge_in", "edgein", "idle", "drag", "auto", "manual")
        for (t in tokens) {
            if (lower.startsWith(t)) {
                var rest = stem.substring(t.length)
                while (rest.isNotEmpty() && (rest[0] == '_' || rest[0] == '-' || rest[0] == ' ')) {
                    rest = rest.substring(1)
                }
                // 只剩数字 = 帧序号，不是动作名。
                return if (rest.isNotEmpty() && !rest.all { it.isDigit() }) rest else ""
            }
        }
        return stem
    }

    /**
     * 自然排序比较器（R-071）。
     *
     * 【为什么不能用 String.compareTo】`001, 002, 010` 恰好能排对，但 `1, 2, 10` 会排成
     *   `1, 10, 2` —— 用户自己导出的帧常常不带前导零。这里按「数字段按数值、其余按字符」比较：
     *   数字段相等时**短的那个在前**（`1` 在 `01` 前），保证排序稳定可复现。
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        val la = a.length
        val lb = b.length
        while (i < la && j < lb) {
            val ca = a[i]
            val cb = b[j]
            if (isDigit(ca) && isDigit(cb)) {
                // 取两边的完整数字段
                var si = i
                while (si < la && isDigit(a[si])) {
                    si++
                }
                var sj = j
                while (sj < lb && isDigit(b[sj])) {
                    sj++
                }
                val na = a.substring(i, si)
                val nb = b.substring(j, sj)
                // 去前导零后按长度比，长度相同再按字典序（避免 Long 溢出）
                val ta = na.trimStart('0')
                val tb = nb.trimStart('0')
                if (ta.length != tb.length) {
                    return ta.length - tb.length
                }
                val c = ta.compareTo(tb)
                if (c != 0) {
                    return c
                }
                // 数值相同：原始串短（前导零少）的在前
                if (na.length != nb.length) {
                    return na.length - nb.length
                }
                i = si
                j = sj
            } else {
                if (ca != cb) {
                    return ca.compareTo(cb)
                }
                i++
                j++
            }
        }
        return (la - i) - (lb - j)
    }

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    /* ============================ 杂项 ============================ */

    /** 精灵图描述文件 / 帧清单：不是帧本身，不参与分组。 */
    private fun isSidecar(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.endsWith(".json") || n.endsWith(".plist") || n.endsWith(".atlas") ||
            n.endsWith(".txt") || n.endsWith(".md")
    }

    /** 系统 / 压缩工具留下的垃圾项，直接跳过（不报警，也不当帧）。 */
    private fun isJunk(segs: List<String>): Boolean {
        for (s in segs) {
            val n = s.lowercase(Locale.US)
            if (n == "__macosx" || n == ".ds_store" || n == "thumbs.db" || n.startsWith("._")) {
                return true
            }
        }
        return false
    }

    /** 按扩展名粗判是否图片（尺寸 / 真实类型由解码层负责）。 */
    fun isImageName(path: String): Boolean {
        val n = path.lowercase(Locale.US)
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".heic") ||
            n.endsWith(".gif") || n.endsWith(".apng")
    }
}