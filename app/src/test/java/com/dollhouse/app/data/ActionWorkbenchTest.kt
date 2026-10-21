package com.dollhouse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】动作工作台**纯逻辑**部分的单元测试（不碰 Context / 不碰磁盘）。
 *
 * ## 测什么、为什么不测那些
 *
 * 这里只测「编解码 + 判定」这类**纯函数**：
 * ```
 * ActionDisplay   默认值判定 / 宽松解码 / 偏移钳位
 * ActionBinding   触发集合清洗 / 优先级档位归一
 * priorityBoost   三档加成是否真的跨过语义档位
 * ActionLibrary   搜索匹配 / 序号补零
 * ```
 * 存储读写（`display` / `setDisplay` / `allows`）依赖 SharedPreferences，
 * 在 JVM 单测里需要 Robolectric —— 那会把这个测试变慢且引入环境依赖。
 * 那部分由「解码/编码本身正确」+「键名规则复用 `DollProfile.key`」共同保证。
 */
class ActionWorkbenchTest {

    /* ============================ ActionDisplay ============================ */

    @Test
    fun `默认显示设置应判定为默认`() {
        val d = ActionWorkbench.ActionDisplay()
        assertTrue(d.isDefault())
        assertEquals(ActionWorkbench.ANCHOR_BOTTOM_CENTER, d.anchor)
        assertEquals(ActionWorkbench.FIT_UNIFIED, d.fit)
        assertEquals(0f, d.ox, 0f)
        assertEquals(0f, d.oy, 0f)
    }

    @Test
    fun `改任意一个字段就不再是默认`() {
        assertFalse(
            ActionWorkbench.ActionDisplay(anchor = ActionWorkbench.ANCHOR_CENTER).isDefault()
        )
        assertFalse(
            ActionWorkbench.ActionDisplay(fit = ActionWorkbench.FIT_CONTAIN).isDefault()
        )
        assertFalse(ActionWorkbench.ActionDisplay(ox = 0.1f).isDefault())
        assertFalse(ActionWorkbench.ActionDisplay(oy = -0.1f).isDefault())
    }

    @Test
    fun `编码后解码应还原全部字段`() {
        val src = ActionWorkbench.ActionDisplay(
            anchor = ActionWorkbench.ANCHOR_BOTTOM_RIGHT,
            fit = ActionWorkbench.FIT_CONTAIN,
            ox = 0.12f,
            oy = -0.34f
        )
        val back = ActionWorkbench.ActionDisplay.decode(src.encode())
        assertEquals(src.anchor, back.anchor)
        assertEquals(src.fit, back.fit)
        assertEquals(src.ox, back.ox, 0.0001f)
        assertEquals(src.oy, back.oy, 0.0001f)
    }

    @Test
    fun `空串与坏 json 都退回默认值`() {
        assertTrue(ActionWorkbench.ActionDisplay.decode(null).isDefault())
        assertTrue(ActionWorkbench.ActionDisplay.decode("").isDefault())
        assertTrue(ActionWorkbench.ActionDisplay.decode("{ 这不是 json").isDefault())
    }

    @Test
    fun `非法锚点退回底部居中而不是整条作废`() {
        // 【这条是「宽松解码」的核心断言】用户手改档案写了个不存在的锚点，
        //   应该只丢这一个字段（退回默认），而不是把整条设置丢掉 ——
        //   否则他调好的偏移会一起消失，画面突然跳回去。
        val d = ActionWorkbench.ActionDisplay.decode(
            "{\"anchor\":\"外星锚点\",\"fit\":\"contain\",\"ox\":0.2,\"oy\":0.1}"
        )
        assertEquals(ActionWorkbench.ANCHOR_BOTTOM_CENTER, d.anchor)
        // 其它字段必须保住。
        assertEquals(ActionWorkbench.FIT_CONTAIN, d.fit)
        assertEquals(0.2f, d.ox, 0.0001f)
        assertEquals(0.1f, d.oy, 0.0001f)
    }

    @Test
    fun `偏移超过正负五十百分比要被钳位`() {
        val d = ActionWorkbench.ActionDisplay.decode("{\"ox\":9.9,\"oy\":-9.9}")
        assertEquals(0.5f, d.ox, 0f)
        assertEquals(-0.5f, d.oy, 0f)
    }

    /* ============================ ActionBinding ============================ */

    @Test
    fun `默认绑定是只允许手动且普通优先级`() {
        val b = ActionWorkbench.ActionBinding()
        assertTrue(b.isDefault())
        assertEquals(listOf("manual"), b.triggers)
        assertEquals(ActionWorkbench.PRIORITY_NORMAL, b.priority)
    }

    @Test
    fun `解码会丢掉不认识的触发条件`() {
        val b = ActionWorkbench.ActionBinding.decode(
            "{\"triggers\":[\"tap\",\"外星触发\",\"tap\"],\"priority\":\"high\"}"
        )
        // 非法项剔除、重复项去重。
        assertEquals(listOf("tap"), b.triggers)
        assertEquals(ActionWorkbench.PRIORITY_HIGH, b.priority)
    }

    @Test
    fun `全部触发条件都不合法时至少保留手动`() {
        // 【为什么保底】否则这个动作永远播不到，用户在界面上也看不出原因。
        val b = ActionWorkbench.ActionBinding.decode("{\"triggers\":[\"外星触发\"]}")
        assertEquals(listOf("manual"), b.triggers)
    }

    @Test
    fun `非法优先级档位退回普通`() {
        val b = ActionWorkbench.ActionBinding.decode(
            "{\"triggers\":[\"manual\"],\"priority\":\"超级最高\"}"
        )
        assertEquals(ActionWorkbench.PRIORITY_NORMAL, b.priority)
    }

    @Test
    fun `绑定编码后解码应还原`() {
        val src = ActionWorkbench.ActionBinding(
            triggers = listOf("manual", "tap", "random"),
            priority = ActionWorkbench.PRIORITY_TOP
        )
        val back = ActionWorkbench.ActionBinding.decode(src.encode())
        assertEquals(src.triggers, back.triggers)
        assertEquals(src.priority, back.priority)
    }

    /* ============================ 优先级加成 ============================ */

    @Test
    fun `普通档不加分`() {
        assertEquals(0, ActionWorkbench.priorityBoost(ActionWorkbench.PRIORITY_NORMAL))
    }

    @Test
    fun `高档能压过常驻与贴边但压不过手动`() {
        // 语义表：IDLE 10 / EDGE 12 / MANUAL 30。
        val boost = ActionWorkbench.priorityBoost(ActionWorkbench.PRIORITY_HIGH)
        // 「高档的常驻动作」（10+boost）应当压过「普通档的常驻」（10），
        // 但仍低于「普通档的手动」（30）—— 这正是「高」这个字的含义。
        assertTrue(10 + boost > 12)
        assertTrue(10 + boost < 30)
    }

    @Test
    fun `最高档能压过拖动`() {
        // DRAG 40 是语义表最高档；「最高」要名副其实。
        //
        // 【为什么按最低的 IDLE(10) 算最坏情况】加成是加在**语义优先级**上的，
        //   而用户可以把任意动作（包括常驻壳）设成「最高」。最坏的组合是
        //   IDLE 10 + boost；它必须 > 40 才算真的压过拖动。
        //   （历史上写成 +30 时 10+30=40 恰好相等，本用例当场失败。）
        val boost = ActionWorkbench.priorityBoost(ActionWorkbench.PRIORITY_TOP)
        assertTrue("最高档加成 " + boost + " 不足以让最弱的语义(IDLE 10) 压过拖动(40)", 10 + boost > 40)
        // 同时也要压过点击(35) —— 「临时强制」在用户眼里就是「谁也别抢」。
        assertTrue(10 + boost > 35)
    }

    @Test
    fun `高档压不过拖动与点击`() {
        // 「高」是「比平时更强势」，不是「压过一切」。若高也能压过拖动，
        //   那用户就分不出「高」与「最高」两档的差别了。
        val boost = ActionWorkbench.priorityBoost(ActionWorkbench.PRIORITY_HIGH)
        assertTrue(10 + boost < 40)
        assertTrue(10 + boost < 35)
    }

    @Test
    fun `最高档加成分必须严格大于高档`() {
        assertTrue(
            ActionWorkbench.priorityBoost(ActionWorkbench.PRIORITY_TOP) >
                ActionWorkbench.priorityBoost(ActionWorkbench.PRIORITY_HIGH)
        )
    }

    /* ============================ 触发条件表 ============================ */

    @Test
    fun `触发条件名必须是标准语义 id`() {
        // 【这条是契约断言】触发条件名会被直接当 `semantic` 传给仲裁层，
        //   一旦有人在这里写了个 `drag_end` 这种别名，界面勾了也不会生效。
        for (t in ActionWorkbench.TRIGGERS) {
            assertTrue(
                "触发条件「" + t + "」不是标准语义 id，仲裁层查不到",
                com.dollhouse.app.pet.behavior.ActionSemantic.isStandard(t)
            )
        }
    }

    @Test
    fun `触发条件表不含生命周期语义`() {
        // appear / disappear / sleep 不暴露给用户（绑了也没有可靠的触发入口）。
        assertFalse(ActionWorkbench.TRIGGERS.contains(com.dollhouse.app.pet.behavior.ActionSemantic.APPEAR))
        assertFalse(ActionWorkbench.TRIGGERS.contains(com.dollhouse.app.pet.behavior.ActionSemantic.DISAPPEAR))
        assertFalse(ActionWorkbench.TRIGGERS.contains(com.dollhouse.app.pet.behavior.ActionSemantic.SLEEP))
    }

    @Test
    fun `每个触发条件都有中文标签`() {
        for (t in ActionWorkbench.TRIGGERS) {
            val label = ActionWorkbench.triggerLabel(t)
            assertTrue(label.isNotEmpty())
            // 认不出的会兜底成「手动触发」—— 那说明表里加了一项却没加标签。
            if (t != "manual") {
                assertFalse(
                    "触发条件「" + t + "」没有专属标签，兜底成了「手动触发」",
                    label == "手动触发"
                )
            }
        }
    }

    /* ============================ 搜索 ============================ */

    private fun action(id: String, name: String, desc: String = ""): DollAnim.Action =
        DollAnim.Action(id = id, name = name, desc = desc)

    @Test
    fun `空关键词返回全部`() {
        val list = listOf(action("a1", "挥手"), action("a2", "转圈"))
        assertEquals(2, ActionLibrary.search(list, "").size)
        assertEquals(2, ActionLibrary.search(list, "   ").size)
    }

    @Test
    fun `按名称匹配且忽略大小写`() {
        val list = listOf(action("a1", "Wave"), action("a2", "转圈"))
        assertEquals(1, ActionLibrary.search(list, "wave").size)
        assertEquals(1, ActionLibrary.search(list, "WAVE").size)
    }

    @Test
    fun `按描述匹配`() {
        val list = listOf(
            action("a1", "挥手", "跟人打招呼"),
            action("a2", "转圈", "原地旋转")
        )
        val hit = ActionLibrary.search(list, "打招呼")
        assertEquals(1, hit.size)
        assertEquals("a1", hit[0].id)
    }

    @Test
    fun `搜不到时返回空列表而不是全部`() {
        val list = listOf(action("a1", "挥手"))
        assertEquals(0, ActionLibrary.search(list, "根本不存在").size)
    }

    @Test
    fun `搜索不匹配内部 id`() {
        // id 是机器串，用户记不住也不该靠它搜 —— 搜 id 应当搜不到。
        val list = listOf(action("a1x2y3z4", "挥手"))
        assertEquals(0, ActionLibrary.search(list, "a1x2y3z4").size)
    }
}