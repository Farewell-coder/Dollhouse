package com.dollhouse.app.ui.nav

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【NavFailure 文案与阶段覆盖测试】（需求 23 节 + K-05）
 *
 * 【为什么要测文案】需求 23 节明确禁止面向用户的提示里出现内部字段名
 * （`idle / drag / edge_in / manifest / fps / baseline / scaleFix`），
 * 也要求「失败必须有文字说明」。这两条属于**只能靠人眼守住**的规则，
 * 一旦有人在文案里塞进一个 `edge_in` 就会漏网 —— 所以固化成断言。
 */
class NavFailureTest {

    /** 需求 23 节列的内部字段名（含 Nav 层自己的实现词汇）。 */
    private val forbidden = listOf(
        "idle", "drag", "edge_in", "manifest", "fps",
        "baseline", "scaleFix", "scale_fix",
        "key", "route", "null", "exception", "stack"
    )

    @Test
    fun `every stage must have non-blank user text`() {
        for (stage in NavFailure.Stage.values()) {
            val text = NavFailure.defaultUserText(stage)
            assertTrue("阶段 " + stage.name + " 没有面向用户的说明", text.isNotBlank())
        }
    }

    @Test
    fun `user text must not leak internal field names`() {
        for (stage in NavFailure.Stage.values()) {
            val lower = NavFailure.defaultUserText(stage).lowercase()
            for (word in forbidden) {
                assertTrue(
                    "阶段 " + stage.name + " 的文案泄露了内部词「" + word + "」：" + lower,
                    !lower.contains(word.lowercase())
                )
            }
        }
    }
}