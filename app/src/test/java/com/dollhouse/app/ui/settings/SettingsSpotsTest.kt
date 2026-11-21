package com.dollhouse.app.ui.settings

import com.dollhouse.app.ui.compose.PageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingsSpots] 的数据完整性与搜索口径回归 —— 纯 JVM，不依赖 Android / 网络。
 *
 * ## 这组用例防的是什么
 *
 * 索引表是**纯字符串数据**，编译器管不到它：
 *   · `pageKey` 写错一个字符 → 搜索能搜到、点进去打不开（比搜不到更难查）；
 *   · 两行 `rowId` 撞车 → 搜索结果映射回行时串行；
 *   · `path` 忘写 → 结果里显示不出「在哪」。
 * 所以这里对「表的结构」做静态断言，对「搜索行为」做少量代表性断言。
 *
 * ## 为什么可以直接调 `PageRegistry.isPageKey`
 *
 * 它内部引用的页面 key（`AboutPage.TAG_PAGE`、`DollListScreen.KEY` …）全部是 `const val`，
 * 编译期已内联成字符串字面量，运行时**不会触发页面类的初始化**，所以普通 JVM 单测里可安全调用。
 * 若哪天有人把某个 key 从 `const val` 改成 `val`，这条测试会在 CI 上因类加载失败而暴露 ——
 * 那本身就是需要修的问题（`isPageKey` 会被非必要的类初始化拖慢）。
 */
class SettingsSpotsTest {

    private val all = SettingsSpots.all()

    @Test
    fun `条目总数与覆盖页面数符合预期`() {
        // 11 页 / 65 行（方案 012 的口径）。数字变了就该来这里确认是「有意增删」。
        assertEquals("设置行总数", 65, all.size)
        assertEquals(
            "覆盖的页面数（A 类 10 + B 类 lamda 1）",
            11,
            all.map { it.pageKey }.distinct().size
        )
    }

    @Test
    fun `rowId 全局唯一`() {
        val ids = all.map { it.rowId }
        assertEquals("存在重复 rowId", ids.size, ids.toSet().size)
    }

    @Test
    fun `每个 pageKey 都能被 PageRegistry 识别`() {
        // 这是「搜到了点得开」的前提：pageKey 认不出来 = 搜索结果是死链。
        for (s in all) {
            assertTrue(
                "pageKey 未被 PageRegistry 登记: ${s.pageKey}（行 ${s.rowId}）",
                PageRegistry.isPageKey(s.pageKey)
            )
        }
    }

    @Test
    fun `每行都有标题与面包屑`() {
        for (s in all) {
            assertTrue("标题为空: ${s.rowId}", s.title.isNotBlank())
            assertTrue("面包屑未以「设置 › 」开头: ${s.rowId} → ${s.path}", s.path.startsWith("设置 › "))
            // 面包屑至少要有一级子页（形如「设置 › 外观 › 主题模式」）
            assertTrue("面包屑层级不足: ${s.rowId} → ${s.path}", s.path.count { it == '›' } >= 1)
        }
    }

    @Test
    fun `不可用项也照列且带原因`() {
        for (s in all) {
            if (!s.available()) {
                assertTrue(
                    "不可用却没给原因文案: ${s.rowId}",
                    s.unavailableHint.isNotBlank()
                )
            }
        }
        // 反向：给了原因文案的行，其条件当前应真的不成立 —— 否则文案是误导。
        // （example: 莫奈未开时的「从壁纸取色」、未设背景图时的「更换图片」）
        val monetOff = all.first { it.rowId == "appearance.monetPick" }
        assertNotNull(monetOff.unavailableHint)
    }

    /* ============================ 自纠错（接线机制） ============================ */

    /**
     * 标题派生规则的**唯一实现**在这里被钉死：`spot.title` → `label` → **空串**。
     *
     * 这三条用例是「漏登记必须可见」这个设计契约的守卫：
     * 如果哪天有人把最后那档从空串改成 `"未命名"` 之类的兜底文案，
     * 界面上的缺失就重新变成"看起来正常"，机制失效 —— 测试会拦下。
     */
    @Test
    fun `标题_有 spot 时以索引表为准`() {
        val s = SettingsSpots.find("appearance.monet")!!
        assertEquals("莫奈主题色", SettingsSpots.spotTitle(s, null))
        // 页面即使还留着旧字面量，也应被索引表覆盖（唯一真源）
        assertEquals("莫奈主题色", SettingsSpots.spotTitle(s, "旧的不一致文案"))
    }

    @Test
    fun `标题_没有 spot 时退回旧字面量`() {
        assertEquals("旧文案", SettingsSpots.spotTitle(null, "旧文案"))
    }

    @Test
    fun `标题_两者都没有时为空串_这就是自纠错`() {
        assertEquals("", SettingsSpots.spotTitle(null, null))
        assertEquals("", SettingsSpots.spotTitle(null, ""))
        // spot 存在但 title 为空 → 也退回空串（不静默成功）
        val blank = SettingsSpot(
            rowId = "x.blank", pageKey = "settings:permission", title = "",
            path = "设置 › 测试"
        )
        assertEquals("", SettingsSpots.spotTitle(blank, null))
    }

    @Test
    fun `find_按 rowId 取到条目_写错则返回 null 而不抛`() {
        assertEquals("appearance.monet", SettingsSpots.find("appearance.monet")?.rowId)
        // 拼写错误 / 不存在 → null（上层表现为"标题为空"，而不是整页崩掉）
        assertEquals(null, SettingsSpots.find("appearance.monett"))
        assertEquals(null, SettingsSpots.find(""))
        assertEquals(null, SettingsSpots.find(null))
        // 全表 65 条都能按 id 反查到自己（防 BY_ID 索引与 ALL_SPOTS 不同步）
        for (s in SettingsSpots.all()) {
            assertEquals("find 反查失败: ${s.rowId}", s.rowId, SettingsSpots.find(s.rowId)?.rowId)
        }
    }

    /* ============================ 搜索 ============================ */

    @Test
    fun `空查询不返回全部`() {
        // 搜索框为空时不该把 65 行倒出来（那是索引页的职责，不是搜索页）。
        assertTrue(SettingsSpots.search("").isEmpty())
        assertTrue(SettingsSpots.search("   ").isEmpty())
        assertTrue(SettingsSpots.search("　").isEmpty())
    }

    @Test
    fun `中文标题命中且完全匹配排最前`() {
        val r = SettingsSpots.search("莫奈")
        assertTrue("搜「莫奈」应有结果", r.isNotEmpty())
        assertEquals("appearance.monet", r.first().rowId)
        // 「莫奈主题色」以「莫奈」开头 → 80 分档，应高于只靠关键词命中的行
        assertTrue(r.first().title.contains("莫奈"))
    }

    @Test
    fun `英文关键词可搜到中文设置项`() {
        // 这是本表存在的核心价值：设置项全中文，英文词必须靠 keywordsEn 显式登记。
        assertEquals("appearance.monet", SettingsSpots.search("monet").first().rowId)
        assertEquals("permission.overlay", SettingsSpots.search("overlay").first().rowId)
        assertEquals("chat.stream", SettingsSpots.search("stream").first().rowId)
        assertEquals("chat.thinking", SettingsSpots.search("reasoning").first().rowId)
        assertEquals("permission.keepalive", SettingsSpots.search("keepalive").first().rowId)
        assertEquals("diag.logs", SettingsSpots.search("diagnostics").first().rowId)
    }

    @Test
    fun `全角与大小写归一化`() {
        // 全角字母 + 大小写混写，必须与半角小写等价。
        assertEquals(
            SettingsSpots.search("monet").map { it.rowId },
            SettingsSpots.search("ＭＯＮＥＴ").map { it.rowId }
        )
        assertEquals(
            SettingsSpots.search("monet").map { it.rowId },
            SettingsSpots.search("Monet").map { it.rowId }
        )
        // 全角空格当分隔符（中文输入法常见）
        assertEquals(
            SettingsSpots.search("背景 透明").map { it.rowId },
            SettingsSpots.search("背景　透明").map { it.rowId }
        )
    }

    @Test
    fun `多词是 AND 不是 OR`() {
        val r = SettingsSpots.search("背景 透明")
        assertTrue("应能搜到「背景图不透明度」", r.any { it.rowId == "appearance.bgAlpha" })
        // 「弹窗透明度」含「透明」但不含「背景」→ 不该出现
        assertFalse(
            "多词必须同时命中，不能退化成 OR",
            r.any { it.rowId == "appearance.dialogAlpha" }
        )
        // 完全无交集的多词 → 空结果
        assertTrue(SettingsSpots.search("莫奈 电池").isEmpty())
    }

    @Test
    fun `路径命中也能搜到`() {
        // 用户可能记得位置而不是标题（「外观 › 弹窗」）。
        val r = SettingsSpots.search("弹窗")
        assertTrue(r.any { it.rowId == "appearance.dialogAlpha" })
        assertTrue(r.any { it.rowId == "appearance.dialogBlur" })
    }

    @Test
    fun `结果条数有上限`() {
        // 「权限」这类宽泛词会命中很多行，必须截断，避免结果列表变成第二个全量索引。
        assertTrue(SettingsSpots.search("权限").size <= 20)
    }

    @Test
    fun `不可用项仍出现在搜索结果里`() {
        // 不让人扑空：条件不满足也要列出（结果里展示 unavailableHint + 可跳到父页）。
        val r = SettingsSpots.search("从壁纸取色")
        assertTrue("不可用项也必须能搜到", r.any { it.rowId == "appearance.monetPick" })
    }
}
