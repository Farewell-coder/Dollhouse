package com.dollhouse.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[PersonaChatConfig] 的行为契约：继承、覆盖、迁移容错、作用域隔离。
 *
 * ## 这组测试挡的是什么
 *
 * 1. **继承不是「复制默认值」**：新建作用域必须继续跟随全局，否则全局换模型 / 改温度
 *    对老会话全部失效 —— 那正是加这层配置要解决的问题。
 * 2. **false 不能被 `?:` 吞掉**：`enabled = false` / `streamOutput = false` 是明确覆盖，
 *    不能因为全局是 true 就被当成「没配」。
 * 3. **坏数据不能把聊天打挂**：手改备份、旧版本、半截 JSON 都可能进盘；
 *    解析必须回落继承，而不是抛异常或产生 `NaN` 参数。
 * 4. **作用域不能串**：`(a|b, c)` 与 `(a, b|c)` 必须是两份配置。
 */
class PersonaChatConfigTest {

    private fun global(): PersonaChatConfig {
        return PersonaChatConfig().apply {
            enabled = true
            providerId = "prov-global"
            modelId = "model-global"
            visionModelId = "vision-global"
            thinkLevel = 3
            temperature = 1.1
            topP = 0.8
            maxTokens = 2048
            streamOutput = false
        }
    }

    @Test
    fun newScope_inheritsEverything() {
        val eff = PersonaChatConfig.inheritAll().resolve(global())

        assertTrue(eff.enabled)
        assertEquals("prov-global", eff.providerId)
        assertEquals("model-global", eff.modelId)
        assertEquals("vision-global", eff.visionModelId)
        assertEquals(3, eff.thinkLevel)
        assertEquals(1.1, eff.temperature, 0.0001)
        assertEquals(0.8, eff.topP, 0.0001)
        assertEquals(2048, eff.maxTokens)
        assertFalse(eff.streamOutput)
    }

    @Test
    fun explicitFalse_isNotTreatedAsInherit() {
        val cfg = PersonaChatConfig.inheritAll().apply {
            enabled = false
            streamOutput = true
        }
        val eff = cfg.resolve(global())

        assertFalse("显式关闭必须压过全局 true", eff.enabled)
        assertTrue("显式打开必须压过全局 false", eff.streamOutput)
    }

    @Test
    fun onlySetFields_override_restFollowGlobal() {
        val cfg = PersonaChatConfig.inheritAll().apply {
            temperature = 0.2
            modelId = "model-local"
        }
        val eff = cfg.resolve(global())

        assertEquals(0.2, eff.temperature, 0.0001)
        assertEquals("model-local", eff.modelId)
        // 其余仍跟随全局
        assertEquals("prov-global", eff.providerId)
        assertEquals("vision-global", eff.visionModelId)
        assertEquals(3, eff.thinkLevel)
        assertEquals(0.8, eff.topP, 0.0001)
        assertEquals(2048, eff.maxTokens)
        assertFalse(eff.streamOutput)
    }

    @Test
    fun resolveWithoutGlobal_usesAppDefaults() {
        val eff = PersonaChatConfig.inheritAll().resolve(null)

        assertTrue(eff.enabled)
        assertEquals("", eff.providerId)
        assertEquals("", eff.modelId)
        assertEquals("", eff.visionModelId)
        assertEquals(PersonaChatConfig.DEFAULT_THINK_LEVEL, eff.thinkLevel)
        assertEquals(PersonaChatConfig.DEFAULT_TEMPERATURE, eff.temperature, 0.0001)
        assertEquals(PersonaChatConfig.DEFAULT_TOP_P, eff.topP, 0.0001)
        assertEquals(0, eff.maxTokens)
        assertTrue(eff.streamOutput)
    }

    @Test
    fun encodeDecode_roundTrip() {
        val cfg = PersonaChatConfig().apply {
            enabled = false
            providerId = "p1"
            modelId = "m1"
            visionModelId = "v1"
            thinkLevel = 5
            temperature = 1.7
            topP = 0.25
            maxTokens = 8192
            streamOutput = true
        }

        val back = PersonaChatConfig.decode(PersonaChatConfig.encode(cfg))

        assertEquals(false, back.enabled)
        assertEquals("p1", back.providerId)
        assertEquals("m1", back.modelId)
        assertEquals("v1", back.visionModelId)
        assertEquals(5, back.thinkLevel)
        assertEquals(1.7, back.temperature!!, 0.0001)
        assertEquals(0.25, back.topP!!, 0.0001)
        assertEquals(8192, back.maxTokens)
        assertEquals(true, back.streamOutput)
    }

    @Test
    fun encodedInherit_isExplicitJsonNull() {
        val o = JSONObject(PersonaChatConfig.encode(PersonaChatConfig.inheritAll()))

        assertEquals(PersonaChatConfig.SCHEMA, o.optString("schema"))
        assertEquals(PersonaChatConfig.SCHEMA_VER, o.optInt("ver"))
        assertTrue("继承项应保留键", o.has("temperature"))
        assertTrue("继承项应写成显式 null", o.isNull("temperature"))
        assertTrue(o.isNull("modelId"))
        assertTrue(o.isNull("streamOutput"))
    }

    @Test
    fun malformedJson_fallsBackToInheritAll() {
        val bad = listOf(null, "", "   ", "{", "[]", "null", "\"just a string\"", "{\"a\":}")
        for (raw in bad) {
            assertTrue("坏输入必须回落继承：$raw", PersonaChatConfig.decode(raw).isInheritAll())
        }
    }

    @Test
    fun wrongFieldTypes_fallBackPerField() {
        val cfg = PersonaChatConfig.decode(
            """{"temperature":"hot","thinkLevel":"high","enabled":"maybe","maxTokens":{}}"""
        )

        assertNull(cfg.temperature)
        assertNull(cfg.thinkLevel)
        assertNull(cfg.enabled)
        assertNull(cfg.maxTokens)
        assertTrue(cfg.isInheritAll())
    }

    @Test
    fun outOfRangeValues_areClamped() {
        val cfg = PersonaChatConfig.decode(
            """{"temperature":9,"topP":-1,"thinkLevel":99,"maxTokens":5000000}"""
        )

        assertEquals(2.0, cfg.temperature!!, 0.0001)
        assertEquals(0.0, cfg.topP!!, 0.0001)
        assertEquals(PersonaChatConfig.THINK_MAX, cfg.thinkLevel)
        assertEquals(PersonaChatConfig.MAX_TOKENS_LIMIT, cfg.maxTokens)
    }

    @Test
    fun zeroOrNegativeMaxTokens_meansInherit() {
        assertNull(PersonaChatConfig.decode("""{"maxTokens":0}""").maxTokens)
        assertNull(PersonaChatConfig.decode("""{"maxTokens":-100}""").maxTokens)
    }

    @Test
    fun snakeCaseLegacyKeys_areAccepted() {
        val cfg = PersonaChatConfig.decode(
            """
            {
              "enabled": false,
              "provider_id": "p-legacy",
              "model_id": "m-legacy",
              "vision_model": "v-legacy",
              "think_level": 4,
              "top_p": 0.5,
              "max_tokens": 512,
              "stream_output": false
            }
            """.trimIndent()
        )

        assertEquals(false, cfg.enabled)
        assertEquals("p-legacy", cfg.providerId)
        assertEquals("m-legacy", cfg.modelId)
        assertEquals("v-legacy", cfg.visionModelId)
        assertEquals(4, cfg.thinkLevel)
        assertEquals(0.5, cfg.topP!!, 0.0001)
        assertEquals(512, cfg.maxTokens)
        assertEquals(false, cfg.streamOutput)
    }

    @Test
    fun blankAndInheritStrings_meanInherit() {
        val cfg = PersonaChatConfig.decode(
            """{"providerId":"   ","modelId":"inherit","visionModelId":"INHERIT"}"""
        )

        assertNull(cfg.providerId)
        assertNull(cfg.modelId)
        assertNull(cfg.visionModelId)
        assertTrue(cfg.isInheritAll())
    }

    @Test
    fun requiredBooleanStrings_areRead() {
        val cfg = PersonaChatConfig.decode("""{"streamOutput":"false","enabled":"0"}""")

        assertEquals(false, cfg.enabled)
        assertEquals(false, cfg.streamOutput)
    }

    @Test
    fun copy_isIndependent() {
        val src = global()
        val copy = src.copy()
        copy.modelId = "changed"
        copy.temperature = 2.0

        assertEquals("model-global", src.modelId)
        assertEquals(1.1, src.temperature!!, 0.0001)
    }

    @Test
    fun scopeKey_isolatesPersonaAndConversation() {
        val base = PersonaChatConfigStore.scopeKey("p1", "c1")

        assertEquals(base, PersonaChatConfigStore.scopeKey("p1", "c1"))
        assertNotEquals(base, PersonaChatConfigStore.scopeKey("p1", "c2"))
        assertNotEquals(base, PersonaChatConfigStore.scopeKey("p2", "c1"))
        assertNotEquals(base, PersonaChatConfigStore.scopeKey("p1", ""))
        assertNotEquals(base, PersonaChatConfigStore.scopeKey("", "c1"))
    }

    @Test
    fun scopeKey_delimiterCannotBeForgedByIds() {
        // 未转义时这两个会拼成同一个键，切会话就会读到别人的配置。
        assertNotEquals(
            PersonaChatConfigStore.scopeKey("a|b", "c"),
            PersonaChatConfigStore.scopeKey("a", "b|c")
        )
    }

    @Test
    fun scopeKey_trimsSurroundingSpaces() {
        assertEquals(
            PersonaChatConfigStore.scopeKey("p1", "c1"),
            PersonaChatConfigStore.scopeKey(" p1 ", " c1 ")
        )
    }
}
