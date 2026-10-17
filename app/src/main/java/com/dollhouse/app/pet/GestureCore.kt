package com.dollhouse.app.pet

/**
 * 【职责】桌宠手势的纯 Kotlin 识别状态机与配置映射：不依赖任何 Android 类，
 *   可直接用 kotlinc 单独编译 / 测试，避免把单双三击与长按的判定逻辑和设备手势流绑死。
 *
 * 【计时归属】本类不自己计时：按下后由调用方按 ViewConfiguration 的长按阈值安排定时器，
 *   抬手后由调用方安排连击窗口（350ms）定时器；本类只负责计数与判定。
 *
 * 【契约】
 *   · 长按到点 -> onLongPressTimeout() 返回 LONG，且吞掉尚未结算的连击计数；
 *     此后这一次抬手不再计为点击（避免把长按误当成单击）。
 *   · 抬手 -> onUp() 返回 true 表示需要一个连击窗口结算；窗口到点用 onWindowTimeout() 取单/双/三击。
 *   · 拖动 / CANCEL -> cancel()，撤销待派发手势，不产生任何动作。
 */
class GestureCore(
    val tapWindowMs: Long = DEFAULT_TAP_WINDOW_MS,
    val longPressMs: Long = DEFAULT_LONG_PRESS_MS
) {
    companion object {
        const val G_SINGLE = "single"
        const val G_DOUBLE = "double"
        const val G_TRIPLE = "triple"
        const val G_LONG = "long"

        /** 连击窗口惯例：350ms。 */
        const val DEFAULT_TAP_WINDOW_MS = 350L

        /** 长按阈值默认值（真正生效的值由调用方从 ViewConfiguration 读入）。 */
        const val DEFAULT_LONG_PRESS_MS = 500L

        private const val MAX_TAPS = 3

        /** 四个手势的固定顺序（设置页与分发都按它走）。 */
        val GESTURES = arrayOf(G_SINGLE, G_DOUBLE, G_TRIPLE, G_LONG)
    }

    private var tapCount = 0
    private var down = false
    private var longFired = false
    private var pending = false

    /** 手指按下：重置长按标记，保留上一批尚未结算的连击计数。 */
    fun onDown() {
        down = true
        longFired = false
    }

    /** 拖动 / CANCEL：整批撤销，不产生任何手势。 */
    fun cancel() {
        tapCount = 0
        down = false
        longFired = false
        pending = false
    }

    /**
     * 抬手。
     * @return true 表示调用方应安排一次「连击窗口结束」的结算定时器。
     */
    fun onUp(): Boolean {
        if (!down) {
            return false
        }
        down = false
        if (longFired) {
            // 长按已被消费：这次抬手不再计为点击。
            longFired = false
            return false
        }
        if (tapCount < MAX_TAPS) {
            tapCount++
        }
        pending = true
        return true
    }

    /** 长按定时器到点：仍按住且未长按过则返回 LONG，否则 null。 */
    fun onLongPressTimeout(): String? {
        if (!down || longFired) {
            return null
        }
        longFired = true
        tapCount = 0
        pending = false
        return G_LONG
    }

    /** 连击窗口结束：结算为单 / 双 / 三击；没有待结算时返回 null。 */
    fun onWindowTimeout(): String? {
        if (!pending) {
            return null
        }
        pending = false
        val n = tapCount
        tapCount = 0
        return when {
            n <= 0 -> null
            n == 1 -> G_SINGLE
            n == 2 -> G_DOUBLE
            else -> G_TRIPLE
        }
    }

    fun tapCount(): Int = tapCount
}

/**
 * 【职责】手势 -> 响应集合的纯逻辑：解析 / 序列化 / 默认值 / 文案。
 *
 * 【响应】交谈 talk / 聊天 chat / 继续 continue。空集合即「无」。
 *   允许多选、允许同一手势含多个响应；「无」与其余互斥（得空集合即无）。
 */
object GestureActions {
    const val A_TALK = "talk"
    const val A_CHAT = "chat"
    const val A_CONTINUE = "continue"
    const val A_AWAY = "away"
    /** 【r19·新增】快捷菜单：弹出一个悬浮菜单（聊天 / 动作列表 / 设置 / 睡觉）。 */
    const val A_MENU = "menu"

    /**
     * **【r16 新增】**「播放动作」条目的前缀 —— `act:<actionId>`。
     *
     * 【为什么用前缀而不是新开一个存储键】手势响应本来就是一个「集合」
     *   （`talk,continue` 这样存）。把「播某个动作」也做成集合里的一个条目，
     *   · 不用改存储结构 → 旧档案零迁移；
     *   · 天然支持「一边弹聊天框一边播动作」这种组合；
     *   · 旧版本 APK 读到 `act:xxx` 会当成无法识别而忽略（[parse] 已过滤），
     *     不会崩、也不会误触发别的行为 —— **前后兼容都是安全的**。
     */
    const val P_ACT = "act:"

    /** 构造一个「播放动作」条目。 */
    fun actValue(actionId: String): String = P_ACT + actionId

    /** 该条目是不是「播放动作」；是则返回动作 id，否则 null。 */
    fun actId(value: String?): String? {
        if (value == null || !value.startsWith(P_ACT)) {
            return null
        }
        val id = value.substring(P_ACT.length)
        return if (id.isEmpty()) null else id
    }

    /** 分发顺序：交谈 -> 聊天（继续单独先行判定）。暂离是配置项，不参与手势分发。 */
    val ORDER = arrayOf(A_TALK, A_CHAT)

    /** 设置页多选项的展示名。 */
    const val NONE_LABEL = "无"
    const val TALK_LABEL = "交谈"
    const val CHAT_LABEL = "聊天"
    const val CONTINUE_LABEL = "继续"
    const val AWAY_LABEL = "暂离"

    /**
     * 解析存储串；非法项忽略。原有四项（交谈 / 聊天 / 继续 / 暂离）顺序与默认值保持不变。
     *
     * 【r16】新增接受 `act:<actionId>` 条目（见 [P_ACT]）。
     * 【为什么 id 里不可能有逗号】动作 id 由 [DollAnim.newId] 生成
     *   （`a` + 36 进制时间戳 + uuid 前 4 位）或来自模型包 manifest 的 `id`，
     *   两者都不含逗号 —— 逗号是分隔符，含逗号的 id 会把一条拆成两条。
     *   这里额外做一次防御：id 里出现空格或逗号就丢弃。
     */
    fun parse(raw: String?): MutableSet<String> {
        val out = LinkedHashSet<String>()
        if (raw == null) {
            return out
        }
        for (part in raw.split(",")) {
            val s = part.trim()
            if (s == A_TALK || s == A_CHAT || s == A_CONTINUE || s == A_AWAY || s == A_MENU) {
                out.add(s)
                continue
            }
            val act = actId(s)
            if (act != null && !act.contains(' ') && !act.contains(',')) {
                out.add(s)
            }
        }
        return out
    }

    /** 序列化为存储串（规范化顺序，空集合 -> 空串）。暂离排在既有三项之后。 */
    fun format(set: Set<String>): String {
        val sb = StringBuilder()
        for (a in ORDER) {
            if (set.contains(a)) {
                if (sb.isNotEmpty()) {
                    sb.append(',')
                }
                sb.append(a)
            }
        }
        if (set.contains(A_CONTINUE)) {
            if (sb.isNotEmpty()) {
                sb.append(',')
            }
            sb.append(A_CONTINUE)
        }
        if (set.contains(A_AWAY)) {
            if (sb.isNotEmpty()) {
                sb.append(',')
            }
            sb.append(A_AWAY)
        }
        // 【r16】最后追加「播放动作」条目。排序输出（按 id 字典序）：
        //   集合本身无序，若不排序，同一个配置每次存盘顺序都可能不同，
        //   造成「没改任何东西但 prefs 一直在变」的假象（也会干扰对比排查）。
        val acts = ArrayList<String>()
        for (one in set) {
            if (actId(one) != null) {
                acts.add(one)
            }
        }
        acts.sort()
        for (one in acts) {
            if (sb.isNotEmpty()) {
                sb.append(',')
            }
            sb.append(one)
        }
        return sb.toString()
    }

    /** 行内展示文案：无 + 各项以「 + 」相连。 */
    fun label(set: Set<String>): String = label(set, null)

    /**
     * 行内展示文案（带动作名解析）。
     *
     * @param nameOf 把动作 id 翻成显示名；为 null 时退化为显示原始 id
     *   （宁可显示 `act:a1x2y3` 也不要显示「一个动作」——后者会让用户
     *   分不清到底绑了哪个，反而更难排查）。
     */
    fun label(set: Set<String>, nameOf: ((String) -> String)?): String {
        if (set.isEmpty()) {
            return NONE_LABEL
        }
        val names = ArrayList<String>(5)
        if (set.contains(A_TALK)) {
            names.add(TALK_LABEL)
        }
        if (set.contains(A_CHAT)) {
            names.add(CHAT_LABEL)
        }
        if (set.contains(A_CONTINUE)) {
            names.add(CONTINUE_LABEL)
        }
        if (set.contains(A_AWAY)) {
            names.add(AWAY_LABEL)
        }
        val acts = ArrayList<String>()
        for (one in set) {
            val id = actId(one) ?: continue
            acts.add(ACT_LABEL_PREFIX + (nameOf?.invoke(id) ?: id))
        }
        acts.sort()
        names.addAll(acts)
        return names.joinToString(" + ")
    }

    /** 「播放动作」在文案里的前缀。 */
    const val ACT_LABEL_PREFIX = "播·"

    /** 各手势的默认响应（存储串形式）。 */
    fun defaultRaw(gesture: String): String {
        return when (gesture) {
            GestureCore.G_SINGLE -> "talk,continue"
            GestureCore.G_TRIPLE -> "chat"
            else -> ""
        }
    }

    /** 手势的界面名称。 */
    fun gestureName(gesture: String): String {
        return when (gesture) {
            GestureCore.G_SINGLE -> "单击"
            GestureCore.G_DOUBLE -> "双击"
            GestureCore.G_TRIPLE -> "三击"
            GestureCore.G_LONG -> "长按"
            else -> gesture
        }
    }
}
