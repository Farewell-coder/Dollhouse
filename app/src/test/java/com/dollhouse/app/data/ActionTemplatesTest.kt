package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 行为模板：点击更积极 / 安静陪伴 / 恢复默认，三者语义必须明确且互不混淆。 */
class ActionTemplatesTest {

    private val ids = listOf("a_tap", "a_idle", "a_random", "a_drag", "a_other")

    private fun trigger(id: String): String? = when (id) {
        "a_tap" -> "tap"
        "a_idle" -> "idle"
        "a_random" -> "random"
        "a_drag" -> "drag"
        else -> null
    }

    @Test fun `点击更积极只提升点击与长按类`() {
        val m = ActionTemplates.apply(ActionTemplates.Template.FOCUS_TAP, ids) { trigger(it) }
        assertEquals(ActionWorkbench.PRIORITY_HIGH, m.getValue("a_tap").priority)
        assertTrue(m.getValue("a_tap").triggers.contains("tap"))
        assertEquals(ActionWorkbench.PRIORITY_NORMAL, m.getValue("a_idle").priority)
        assertEquals(ActionWorkbench.PRIORITY_NORMAL, m.getValue("a_other").priority)
    }

    @Test fun `安静陪伴只保留待机与随机`() {
        val m = ActionTemplates.apply(ActionTemplates.Template.CALM_IDLE, ids) { trigger(it) }
        assertEquals(listOf("manual", "idle"), m.getValue("a_idle").triggers)
        assertEquals(listOf("manual", "random"), m.getValue("a_random").triggers)
        // 点击类不再由点击自动触发
        assertEquals(listOf("manual"), m.getValue("a_tap").triggers)
        assertEquals(listOf("manual"), m.getValue("a_drag").triggers)
    }

    @Test fun `恢复默认全部等价于默认绑定`() {
        val m = ActionTemplates.apply(ActionTemplates.Template.RESET, ids) { trigger(it) }
        assertTrue(m.values.all { it.isDefault() })
    }

    @Test fun `未知触发器不会导致异常`() {
        val m = ActionTemplates.apply(ActionTemplates.Template.FOCUS_TAP, ids) { null }
        assertEquals(ids.size, m.size)
        assertTrue(m.values.all { it.isDefault() })
    }

    @Test fun `模板名称与说明都不为空`() {
        for (t in ActionTemplates.Template.values()) {
            assertTrue(ActionTemplates.label(t).isNotBlank())
            assertTrue(ActionTemplates.desc(t).isNotBlank())
        }
    }
}