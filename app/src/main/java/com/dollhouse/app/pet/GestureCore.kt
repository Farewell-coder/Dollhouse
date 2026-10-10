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

    /** 分发顺序：交谈 -> 聊天（继续单独先行判定）。暂离是配置项，不参与手势分发。 */
    val ORDER = arrayOf(A_TALK, A_CHAT)

    /** 设置页多选项的展示名。 */
    const val NONE_LABEL = "无"
    const val TALK_LABEL = "交谈"
    const val CHAT_LABEL = "聊天"
    const val CONTINUE_LABEL = "继续"
    const val AWAY_LABEL = "暂离"

    /** 解析存储串；非法项忽略。原有三项（交谈 / 聊天 / 继续）顺序与默认值保持不变。 */
    fun parse(raw: String?): MutableSet<String> {
        val out = LinkedHashSet<String>()
        if (raw == null) {
            return out
        }
        for (part in raw.split(",")) {
            val s = part.trim()
            if (s == A_TALK || s == A_CHAT || s == A_CONTINUE || s == A_AWAY) {
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
        return sb.toString()
    }

    /** 行内展示文案：无 + 各项以「 + 」相连。 */
    fun label(set: Set<String>): String {
        if (set.isEmpty()) {
            return NONE_LABEL
        }
        val names = ArrayList<String>(4)
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
        return names.joinToString(" + ")
    }

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
