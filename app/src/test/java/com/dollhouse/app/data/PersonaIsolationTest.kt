package com.dollhouse.app.data

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PersonaIsolationTest {
    @Test fun configDoesNotLeakAcrossScopes() {
        val a = PersonaChatConfig(); a.providerId = "pa"; a.modelId = "ma"; a.temperature = 1.4
        val b = PersonaChatConfig.decode(PersonaChatConfig.encode(a))
        assertEquals("pa", b.providerId); assertEquals("ma", b.modelId); assertEquals(1.4, b.temperature!!, 0.0)
        assertTrue(PersonaChatConfig.decode("{}").isInheritAll())
    }
    @Test fun draftSeparatesTextAndRejectsBrokenJson() {
        val d = ChatDraft(); d.text = "A 的草稿\n第二行"
        assertEquals(d.text, ChatDraft.decode(ChatDraft.encode(d)).text)
        assertTrue(ChatDraft.decode("{broken").isEmpty())
        assertNotEquals(d.text, ChatDraft.decode("B 的草稿").text)
    }
    @Test fun generationOwnershipIsFrozen() {
        val a = GenerationTask(personaId = "A", conversationId = "a1", historySnapshot = JSONArray("[{}]"))
        val b = GenerationTask(personaId = "B", conversationId = "b1", historySnapshot = JSONArray())
        GenerationTasks.put(a); GenerationTasks.put(b)
        assertEquals(listOf(a), GenerationTasks.forConversation("a1"))
        GenerationTasks.finish(a.generationId)
        assertNull(GenerationTasks.get(a.generationId)); assertEquals(b, GenerationTasks.get(b.generationId))
        GenerationTasks.finish(b.generationId)
    }
}