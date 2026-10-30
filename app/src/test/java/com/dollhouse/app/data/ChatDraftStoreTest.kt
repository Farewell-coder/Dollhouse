package com.dollhouse.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】[ChatDraft] / [ChatDraftStore] 的行为契约：往返、迁移兼容、坏数据兜底、作用域隔离。
 *
 * ## 这组测试挡的是什么
 *
 * 1. **切会话不能串草稿**：键里必须同时有人设与会话；分隔符不能被 id 伪造。
 * 2. **旧形态能读**：裸文本、`imageTool` 为布尔 / 字符串、工具状态拆在顶层的写法，
 *    升级后都不能变成空草稿。
 * 3. **坏 JSON 不进输入框**：解析失败返回空草稿，而不是把半截 JSON 灌给用户。
 * 4. **草稿不能撑爆偏好**：超长文本 / 路径必须截断，且不能切坏代理对。
 */
class ChatDraftStoreTest {

    @Test
    fun emptyDraft_isEmpty() {
        val d = ChatDraft.empty()

        assertTrue(d.isEmpty())
        assertEquals(0L, d.updatedAt)
        assertFalse(d.imageTool.enabled)
        assertEquals("", d.imageTool.imagePath)
    }

    @Test
    fun whitespaceOnlyText_isEmpty() {
        val d = ChatDraft.empty()
        d.text = "  \n\t "

        assertTrue(d.isEmpty())
    }

    @Test
    fun imageToolAlone_isNotEmpty() {
        val d = ChatDraft.empty()
        d.imageTool.enabled = true
        assertFalse(d.isEmpty())

        val withPath = ChatDraft.empty()
        withPath.imageTool.imagePath = "/sdcard/a.png"
        assertFalse(withPath.isEmpty())
    }

    @Test
    fun encodeDecode_roundTrip() {
        val d = ChatDraft.empty().apply {
            text = "主人，晚上好\n第二行"
            imageTool.enabled = true
            imageTool.imagePath = "/sdcard/Pictures/a b.png"
            updatedAt = 1_726_000_000_000L
        }

        val back = ChatDraft.decode(ChatDraft.encode(d))

        assertEquals("主人，晚上好\n第二行", back.text)
        assertTrue(back.imageTool.enabled)
        assertEquals("/sdcard/Pictures/a b.png", back.imageTool.imagePath)
        assertEquals(1_726_000_000_000L, back.updatedAt)
    }

    @Test
    fun encodeNull_returnsEmptyDraftJson() {
        val back = ChatDraft.decode(ChatDraft.encode(null))

        assertTrue(back.isEmpty())
        assertEquals(ChatDraft.SCHEMA, JSONObject(ChatDraft.encode(null)).optString("schema"))
    }

    @Test
    fun blankOrNullRaw_isEmpty() {
        assertTrue(ChatDraft.decode(null).isEmpty())
        assertTrue(ChatDraft.decode("").isEmpty())
        assertTrue(ChatDraft.decode("   ").isEmpty())
    }

    @Test
    fun malformedJson_becomesEmptyNotGarbageText() {
        val back = ChatDraft.decode("{\"text\":\"half written")

        assertTrue("坏 JSON 不能被当成正文灌回输入框", back.isEmpty())
        assertEquals("", back.text)
    }

    @Test
    fun legacyBareText_isMigrated() {
        val back = ChatDraft.decode("  旧版直接存的一整段话\n第二行")

        assertEquals("  旧版直接存的一整段话\n第二行", back.text)
        assertFalse(back.imageTool.enabled)
    }

    @Test
    fun legacyBooleanImageTool_isMigrated() {
        assertTrue(ChatDraft.decode("""{"imageTool":true}""").imageTool.enabled)
        assertFalse(ChatDraft.decode("""{"imageTool":false}""").imageTool.enabled)
    }

    @Test
    fun legacyStringImageTool_isMigrated() {
        val back = ChatDraft.decode("""{"imageTool":"/sdcard/x.png"}""")

        assertTrue(back.imageTool.enabled)
        assertEquals("/sdcard/x.png", back.imageTool.imagePath)
    }

    @Test
    fun legacyTopLevelImageFields_areMigrated() {
        val back = ChatDraft.decode(
            """{"text":"配图","image":"/sdcard/y.png","imageToolEnabled":false}"""
        )

        assertEquals("配图", back.text)
        assertEquals("/sdcard/y.png", back.imageTool.imagePath)
        assertFalse(back.imageTool.enabled)
    }

    @Test
    fun topLevelImageWithoutFlag_defaultsToEnabled() {
        val back = ChatDraft.decode("""{"imagePath":"/sdcard/z.png"}""")

        assertTrue(back.imageTool.enabled)
        assertEquals("/sdcard/z.png", back.imageTool.imagePath)
    }

    @Test
    fun updatedAt_acceptsStringAndClampsNegative() {
        assertEquals(42L, ChatDraft.decode("""{"updatedAt":"42"}""").updatedAt)
        assertEquals(7L, ChatDraft.decode("""{"updated_at":7}""").updatedAt)
        assertEquals(0L, ChatDraft.decode("""{"updatedAt":-5}""").updatedAt)
        assertEquals(0L, ChatDraft.decode("""{"updatedAt":"yesterday"}""").updatedAt)
    }

    @Test
    fun wrongTextFieldType_isIgnored() {
        val back = ChatDraft.decode("""{"text":{"nested":true}}""")

        assertTrue(back.isEmpty())
    }

    @Test
    fun longText_isClippedToLimit() {
        val raw = "字".repeat(ChatDraft.MAX_TEXT_CHARS + 10)

        assertEquals(ChatDraft.MAX_TEXT_CHARS, ChatDraft.clipText(raw).length)
        assertEquals(ChatDraft.MAX_TEXT_CHARS, ChatDraft.decode(raw).text.length)
    }

    @Test
    fun clipping_doesNotSplitSurrogatePair() {
        // 末尾是一个 emoji（两个 char）：截断点正好落在高代理上时必须退一格。
        val raw = "a".repeat(ChatDraft.MAX_TEXT_CHARS - 1) + "\uD83D\uDE00"
        val clipped = ChatDraft.clipText(raw)

        assertEquals(ChatDraft.MAX_TEXT_CHARS - 1, clipped.length)
        assertFalse(Character.isHighSurrogate(clipped[clipped.length - 1]))
    }

    @Test
    fun longPath_isClipped() {
        val p = "/".padEnd(ChatDraft.MAX_PATH_CHARS + 100, 'a')

        assertEquals(ChatDraft.MAX_PATH_CHARS, ChatDraft.clipPath(p).length)
    }

    @Test
    fun copy_isIndependent() {
        val src = ChatDraft.empty().apply {
            text = "hello"
            imageTool.enabled = true
            imageTool.imagePath = "/a.png"
        }
        val copy = src.copy()
        copy.text = "changed"
        copy.imageTool.imagePath = "/b.png"

        assertEquals("hello", src.text)
        assertEquals("/a.png", src.imageTool.imagePath)
    }

    @Test
    fun scopeKey_isolatesPersonaAndConversation() {
        val base = ChatDraftStore.scopeKey("p1", "c1")

        assertEquals(base, ChatDraftStore.scopeKey("p1", "c1"))
        assertNotEquals(base, ChatDraftStore.scopeKey("p1", "c2"))
        assertNotEquals(base, ChatDraftStore.scopeKey("p2", "c1"))
        assertNotEquals(base, ChatDraftStore.scopeKey("", ""))
    }

    @Test
    fun scopeKey_delimiterCannotBeForgedByIds() {
        assertNotEquals(
            ChatDraftStore.scopeKey("a|b", "c"),
            ChatDraftStore.scopeKey("a", "b|c")
        )
    }

    @Test
    fun draftScope_neverCollidesWithConfigScope() {
        // 两套基础设施共用 feiyu_pet：前缀必须不同，否则同一个人设会话会互相覆盖。
        assertNotEquals(
            ChatDraftStore.scopeKey("p1", "c1"),
            PersonaChatConfigStore.scopeKey("p1", "c1")
        )
    }
}
