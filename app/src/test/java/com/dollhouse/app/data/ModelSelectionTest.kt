package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 模型筛选与批量加入的口径回归；不依赖 Android 或网络。 */
class ModelSelectionTest {
    private val models = listOf("deepseek-chat", "DeepSeek-Reasoner", "gpt-4o", "gpt-4.1-mini")

    @Test fun `空查询保留全部和原顺序`() {
        assertEquals(models, ModelSelection.filter(models, ""))
    }

    @Test fun `大小写不敏感且支持多词 AND`() {
        assertEquals(listOf("deepseek-chat", "DeepSeek-Reasoner"), ModelSelection.filter(models, "DEEPSEEK"))
        assertEquals(listOf("DeepSeek-Reasoner"), ModelSelection.filter(models, "deepseek reason"))
        assertEquals(emptyList<String>(), ModelSelection.filter(models, "deepseek absent"))
    }

    @Test fun `只返回尚未加入的项目且不改变顺序`() {
        assertEquals(listOf("gpt-4o", "gpt-4.1-mini"), ModelSelection.missing(models, setOf("deepseek-chat", "DeepSeek-Reasoner")))
    }

    @Test fun `重复与空名不会被重复加入`() {
        assertEquals(listOf("gpt-4o"), ModelSelection.missing(listOf("gpt-4o", "gpt-4o", ""), setOf()))
    }
}
