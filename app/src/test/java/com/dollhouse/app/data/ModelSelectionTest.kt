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

    /* ================================================================
     * 补充：子串匹配 / 全角空格 / 两条「显式契约」
     *
     * 【为什么补这些】原有用例只覆盖了「空查询 / 大小写 / 多词 AND / missing 的过滤与去重」，
     *   但下面两条**跨包隐式依赖**没有任何测试守着，改动时极易默默破坏：
     *
     *   ① `filter` **不去重**。模型选择列表之所以没有重复项，靠的是上游
     *      `ai/ModelRules.parseModels()` 在 `addName()` 里的 `!out.contains(v)`（跨包！），
     *      而不是本对象。谁把 parseModels 的判重去掉，`shown` 就会开始出现重复行。
     *      这里把「filter 原样透传、允许重复」写成**显式契约**：
     *      ——将来真给 filter 加去重，这条测试会红，提醒改的人「你在改一处别人依赖的语义」；
     *      ——只是删 parseModels 的判重则不会误报（因为契约本就是「允许重复」），
     *        但 review 时能看见这层关系。
     *
     *   ② `missing` 是**大小写敏感**的，而 `filter` 是**不敏感**的。
     *      两处口径不同是既有事实（`missing` 比对的是「已入库的精确模型名」，
     *      大小写不同就是不同的模型 id），但很容易被误当成 bug「统一」掉。
     *      把它钉住，改动时至少能立刻看到差异被显式化。
     * ================================================================ */

    @Test fun `部分匹配靠子串包含`() {
        // 不要求前缀匹配：只要名称里含该片段即命中。
        assertEquals(listOf("gpt-4o"), ModelSelection.filter(models, "4o"))
        assertEquals(listOf("deepseek-chat", "DeepSeek-Reasoner"), ModelSelection.filter(models, "eep"))
        assertEquals(listOf("gpt-4o", "gpt-4.1-mini"), ModelSelection.filter(models, "gpt-4"))
    }

    @Test fun `全角空格同样作为分词分隔符`() {
        // 实现里 split(' ', '　') —— 中文输入法敲出的全角空格必须等价于半角。
        assertEquals(listOf("gpt-4o", "gpt-4.1-mini"), ModelSelection.filter(models, "gpt　4"))
        // 首尾空白被 trim 掉，不该产生空词把结果筛没。
        assertEquals(models, ModelSelection.filter(models, "   "))
    }

    @Test fun `契约·filter 不去重_允许重复_判重是上游 parseModels 的职责`() {
        val dup = listOf("gpt-4o", "gpt-4o", "deepseek-chat")
        // 空查询：原样返回（含重复），顺序不变。
        assertEquals(dup, ModelSelection.filter(dup, ""))
        // 带查询：仍然保留重复 —— filter 只做「筛」，不做「去重」。
        assertEquals(listOf("gpt-4o", "gpt-4o"), ModelSelection.filter(dup, "gpt"))
        assertEquals(
            "filter 的契约是「原样透传筛选结果」，去重发生在 ai/ModelRules.addName()",
            2,
            ModelSelection.filter(listOf("x", "x"), "x").size
        )
    }

    @Test fun `契约·missing 大小写敏感_与 filter 相反`() {
        // filter 不敏感……
        assertEquals(listOf("DeepSeek-Reasoner"), ModelSelection.filter(models, "deepseek-reasoner"))
        // ……而 missing 按精确字符串比对：大小写不同视为「另一个模型」，不会被视为已存在。
        assertEquals(
            listOf("GPT-4o"),
            ModelSelection.missing(listOf("GPT-4o"), setOf("gpt-4o"))
        )
        // 大小写一致时才被正确排除。
        assertEquals(
            emptyList<String>(),
            ModelSelection.missing(listOf("gpt-4o"), setOf("gpt-4o"))
        )
    }

    @Test fun `missing 保持服务端顺序且已存在项无论重复几次都跳过`() {
        val server = listOf("z-model", "a-model", "z-model", "m-model")
        assertEquals(
            listOf("z-model", "m-model"),
            ModelSelection.missing(server, setOf("a-model"))
        )
        // existing 自身含重复不影响判定。
        assertEquals(
            listOf("z-model", "a-model"),
            ModelSelection.missing(listOf("z-model", "a-model"), listOf("dup", "dup"))
        )
    }
}
