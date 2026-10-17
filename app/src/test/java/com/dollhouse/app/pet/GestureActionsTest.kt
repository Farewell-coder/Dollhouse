package com.dollhouse.app.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】手势解析的单元测试 —— 含 r16 新增的 `act:<actionId>` 支持。
 *
 * ## 为什么这个测试关系到「改动不能波及旧功能」
 * 任务是给手势加「播放动作」选项，而**旧存档里存的是 `talk,continue` 这样的串**。
 * 存储格式一旦改错，老用户的手势配置会静默失效 —— 表现是
 * 「升级后单击人偶不再弹聊天框」，而用户完全想不到跟升级有关。
 *
 * 所以这里把三件事钉死：
 * 1. **旧值原样可用**（`talk` / `chat` / `continue` / `away` 一个不少）；
 * 2. **旧默认值不变**（单击 = `talk,continue`、三击 = `chat`、其余为空）；
 * 3. **新条目前后兼容**（旧版本 APK 读到 `act:xxx` 会忽略而不是崩）。
 */
class GestureActionsTest {

    // ============================ 旧值兼容（回归护栏） ============================

    @Test
    fun parse_acceptsLegacyValues() {
        val s = GestureActions.parse("talk,chat,continue,away")
        assertEquals(4, s.size)
        assertTrue(s.contains(GestureActions.A_TALK))
        assertTrue(s.contains(GestureActions.A_CHAT))
        assertTrue(s.contains(GestureActions.A_CONTINUE))
        assertTrue(s.contains(GestureActions.A_AWAY))
    }

    @Test
    fun parse_ignoresGarbage() {
        val s = GestureActions.parse("talk,,  ,evil,<script>")
        assertEquals(1, s.size)
        assertTrue(s.contains(GestureActions.A_TALK))
    }

    @Test
    fun parse_handlesNullAndEmpty() {
        assertTrue(GestureActions.parse(null).isEmpty())
        assertTrue(GestureActions.parse("").isEmpty())
    }

    /** 旧默认值必须**逐字不变**（改了等于改所有老用户的手势行为）。 */
    @Test
    fun defaultRaw_isUnchangedForLegacyGestures() {
        assertEquals("talk,continue", GestureActions.defaultRaw(GestureCore.G_SINGLE))
        assertEquals("chat", GestureActions.defaultRaw(GestureCore.G_TRIPLE))
        assertEquals("", GestureActions.defaultRaw(GestureCore.G_DOUBLE))
        assertEquals("", GestureActions.defaultRaw(GestureCore.G_LONG))
    }

    /** `format` 的输出顺序必须稳定（存储串每次存盘都变会让排查无从下手）。 */
    @Test
    fun format_isDeterministic() {
        val a = GestureActions.format(setOf(GestureActions.A_TALK, GestureActions.A_CONTINUE))
        val b = GestureActions.format(setOf(GestureActions.A_CONTINUE, GestureActions.A_TALK))
        assertEquals("talk,continue", a)
        assertEquals(a, b)
    }

    /** 空集合序列化成空串（旧行为；不能变成 "none" 之类）。 */
    @Test
    fun format_emptyIsEmptyString() {
        assertEquals("", GestureActions.format(emptySet()))
    }

    /** 往返一致：parse(format(x)) == x。 */
    @Test
    fun roundTrip_legacyValues() {
        val s = setOf(GestureActions.A_TALK, GestureActions.A_AWAY, GestureActions.A_CHAT)
        assertEquals(s, GestureActions.parse(GestureActions.format(s)))
    }

    // ============================ r16：act:<id> ============================

    @Test
    fun actValue_and_actId_roundTrip() {
        val v = GestureActions.actValue("a1b2c3")
        assertEquals("act:a1b2c3", v)
        assertEquals("a1b2c3", GestureActions.actId(v))
    }

    @Test
    fun actId_returnsNullForLegacyAndGarbage() {
        assertNull(GestureActions.actId(GestureActions.A_TALK))
        assertNull(GestureActions.actId("act:"))
        assertNull(GestureActions.actId(null))
        assertNull(GestureActions.actId("Act:xx"))
        // 前缀在中间不算（防「有人说 id 里含 act: 就算」这种误判）。
        assertNull(GestureActions.actId("xact:y"))
    }

    @Test
    fun parse_acceptsActEntries() {
        val s = GestureActions.parse("talk,act:wave,act:blink")
        assertEquals(3, s.size)
        assertTrue(s.contains("act:wave"))
        assertTrue(s.contains("act:blink"))
    }

    /**
     * 含空格 / 逗号的 id 必须被丢弃。
     *
     * 【为什么】存储用逗号分隔。若允许 id 含逗号，`act:a,b` 会被拆成
     *   `act:a` 与 `b` 两条，等于「用户绑的动作莫名变成了别的东西」。
     */
    @Test
    fun parse_rejectsActWithSpaceOrComma() {
        val s = GestureActions.parse("act:a b")
        assertTrue(s.isEmpty())
    }

    /** `act:` 条目与旧值可以共存，且格式化后顺序稳定（旧值在前、动作在后且排序）。 */
    @Test
    fun format_putsActsAfterLegacyAndSorts() {
        val s = linkedSetOf(
            "act:zzz", GestureActions.A_TALK, "act:aaa", GestureActions.A_AWAY
        )
        assertEquals("talk,away,act:aaa,act:zzz", GestureActions.format(s))
    }

    /** 往返一致（含 act 条目）。 */
    @Test
    fun roundTrip_withActs() {
        val s = linkedSetOf(GestureActions.A_CHAT, "act:x1", "act:x2")
        assertEquals(s, GestureActions.parse(GestureActions.format(s)))
    }

    /**
     * **向后兼容的证明**：旧版本 APK 用「只认四个旧值」的解析器读新串，
     *   应当拿到旧值、忽略 `act:` 条目，而不是崩或误判。
     *
     * 【为什么要在测试里模拟旧解析器】这是对「装了新版又退回旧版」这一真实场景的验证。
     */
    @Test
    fun newFormat_isSafeForOldParser() {
        val newRaw = GestureActions.format(
            linkedSetOf(GestureActions.A_TALK, "act:wave")
        )
        // 模拟「旧版解析器」：只认四个固定值。
        val legacy = HashSet<String>()
        for (part in newRaw.split(",")) {
            val v = part.trim()
            if (v == "talk" || v == "chat" || v == "continue" || v == "away") {
                legacy.add(v)
            }
        }
        assertEquals(1, legacy.size)
        assertTrue(legacy.contains("talk"))
    }

    // ============================ 展示文案 ============================

    @Test
    fun label_noneAndLegacy() {
        assertEquals(GestureActions.NONE_LABEL, GestureActions.label(emptySet(), null))
        val s = setOf(GestureActions.A_TALK, GestureActions.A_CONTINUE)
        assertEquals("交谈 + 继续", GestureActions.label(s, null))
    }

    /** 有动作名解析器时显示名字，没有时**退化显示 id**（而不是含糊的「一个动作」）。 */
    @Test
    fun label_showsActNameOrId() {
        val s = setOf(GestureActions.A_TALK, "act:a1")
        assertEquals("交谈 + 播·挥手", GestureActions.label(s) { "挥手" })
        assertEquals("交谈 + 播·a1", GestureActions.label(s, null))
    }

    /** 无参 `label(set)` 重载必须与旧的单参调用逐字一致（旧调用点不能变文案）。 */
    @Test
    fun label_singleArgMatchesLegacyBehaviour() {
        val s = setOf(GestureActions.A_AWAY)
        assertEquals("暂离", GestureActions.label(s))
    }

    // ============================ 手势状态机（既有护栏） ============================

    /** 长按到点后，那次抬手不该再被计为单击。 */
    @Test
    fun longPress_consumesTheUp() {
        val g = GestureCore()
        g.onDown()
        assertEquals(GestureCore.G_LONG, g.onLongPressTimeout())
        assertFalse("长按已消费，不该再要连击窗口", g.onUp())
        assertNull(g.onWindowTimeout())
    }

    /** 单 / 双 / 三击的结算。 */
    @Test
    fun tapCounts_resolveToSingleDoubleTriple() {
        assertEquals(GestureCore.G_SINGLE, resolve(1))
        assertEquals(GestureCore.G_DOUBLE, resolve(2))
        assertEquals(GestureCore.G_TRIPLE, resolve(3))
    }

    private fun resolve(taps: Int): String? {
        val g = GestureCore()
        for (i in 0 until taps) {
            g.onDown()
            assertTrue(g.onUp())
        }
        return g.onWindowTimeout()
    }

    /** 拖动（cancel）后不该产生任何手势。 */
    @Test
    fun cancel_producesNothing() {
        val g = GestureCore()
        g.onDown()
        g.cancel()
        assertNull(g.onWindowTimeout())
        assertEquals(0, g.tapCount())
    }

    /** 四个手势的顺序是设置页与分发的约定，不能乱。 */
    @Test
    fun gestures_haveFixedOrder() {
        assertEquals(4, GestureCore.GESTURES.size)
        assertEquals(GestureCore.G_SINGLE, GestureCore.GESTURES[0])
        assertEquals(GestureCore.G_DOUBLE, GestureCore.GESTURES[1])
        assertEquals(GestureCore.G_TRIPLE, GestureCore.GESTURES[2])
        assertEquals(GestureCore.G_LONG, GestureCore.GESTURES[3])
    }
}