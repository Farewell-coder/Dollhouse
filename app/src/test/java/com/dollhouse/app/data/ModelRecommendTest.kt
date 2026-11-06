package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 默认模型推荐口径：不能把向量/语音/绘图模型当成聊天模型推荐出去。 */
class ModelRecommendTest {

    @Test fun `优先推荐带 chat 特征的模型`() {
        assertEquals("deepseek-chat", ModelRecommend.suggest(listOf("text-embedding-3", "deepseek-chat")))
    }

    @Test fun `排除向量与语音模型`() {
        assertTrue(ModelRecommend.isNonChat("text-embedding-3-large"))
        assertTrue(ModelRecommend.isNonChat("whisper-1"))
        assertTrue(ModelRecommend.isNonChat("tts-1-hd"))
        assertTrue(ModelRecommend.isNonChat("gpt-4o-mini-tts"))
        assertFalse(ModelRecommend.isNonChat("gpt-4o"))
    }

    @Test fun `全部不可用时不乱猜`() {
        assertNull(ModelRecommend.suggest(listOf("text-embedding-3-small", "whisper-1")))
        assertNull(ModelRecommend.suggest(emptyList()))
    }

    @Test fun `没有特征词时退回第一个可用项`() {
        assertEquals("my-model-a", ModelRecommend.suggest(listOf("whisper-1", "my-model-a", "my-model-b")))
    }

    @Test fun `空名不会成为候选`() {
        assertEquals("qwen-max", ModelRecommend.suggest(listOf("", " ", "qwen-max")))
    }
}