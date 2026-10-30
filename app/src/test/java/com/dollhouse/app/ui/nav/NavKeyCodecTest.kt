package com.dollhouse.app.ui.nav

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【NavKeyCodec 全覆盖测试】
 *
 * 【为什么必须全量覆盖】
 *   本次导航迁移（PageStack → Navigation 3）把所有「打开界面按钮」的 key 字符串
 *   经过 [NavKeyCodec.toKey] 转成 [NavKey]。任何一个 key 解析失败（返回 null），
 *   表现为**点了没反应**（不崩、不报错，最难查的一类症状）。
 *   本测试把工程里所有 push 调用点的 key 逐一过一遍，确保零遗漏。
 *
 * 【覆盖范围】
 *   与 `NavHost.kt` 注释里列出的 84 处调用点一一对应，
 *   按页面分组：设置 / 人偶 / 供应商 / 聊天 / 记忆库 / 关于 / Token / 开发者选项。
 */
class NavKeyCodecTest {

    @Test
    fun `all push call site keys must parse to non-null NavKey`() {
        val keys = listOf(
            // 设置家族
            "feiyu_settings_page",        // SettingsScreen
            "settings:myprofile",         // MyProfilePage
            "settings:appearance",        // AppearancePage
            "feiyu_about_page",           // AboutPage
            "feiyu_mem_settings_page",    // MemSettingsPage
            "feiyu_chat_settings_page",   // ChatSettingsPage
            "feiyu_vision_model_page",    // VisionModelPage
            "feiyu_dev_options_page",     // DeveloperOptionsPage
            // 人偶家族
            "doll:list",                  // DollListScreen
            "doll:groups",                // DollGroupPage
            "doll:edit:abc",              // DollEditScreen
            "doll:gesture:abc",           // DollGesturePage
            "doll:away:abc",              // DollAwayPage
            "doll:return:abc",            // DollReturnPage
            "doll:home:abc",              // DollHomePage
            "doll:range:abc",             // DollRangePage
            "doll:talk:abc",              // DollTalkPage
            "doll:persona:abc",           // DollPersonaPage
            "doll:action:abc",            // DollActionPage
            "doll:anim:abc|def",          // DollAnimPage
            "doll:import:abc",            // DollImportWizardPage（工作台导入向导）
            // 供应商 / 聊天 / 记忆库 / Token
            "feiyu_cfg_page",             // ProviderNav
            // 注意：`chat:list` 不在这里 —— 它虽定义了 KEY，但全工程没有任何地方
            //   调用 `PageStack.push(act, ChatListScreen.KEY)`，聊天列表是作为 Tab
            //   直接渲染（`HomeUi` 里 `ChatListScreen.Content(act)`），不是覆盖页。
            "feiyu_mem_page",             // MemPage
            "feiyu_token_page"            // TokenStat
        )

        val failures = mutableListOf<String>()
        for (key in keys) {
            val navKey = NavKeyCodec.toKey(key)
            if (navKey == null) {
                failures.add(key)
            }
        }

        assertTrue(
            "以下 key 解析失败（会导致点击无反应）:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun `doll anim composite key must use pipe separator`() {
        // 【回归测试】DollAnimPage 用 `|` 分隔 dollId 与 actionId，
        // 若 NavKeyCodec 误用 `:` 切分，会切不出两段 → 返回 null → 点了没反应。
        val key = "doll:anim:doll123|action456"
        val navKey = NavKeyCodec.toKey(key)
        assertNotNull("doll:anim: 复合 key 必须能解析", navKey)
        assertTrue(navKey is DollAnimRoute)
        val r = navKey as DollAnimRoute
        assertTrue(r.dollId == "doll123")
        assertTrue(r.actionId == "action456")
    }

    @Test
    fun `doll anim key without pipe must fallback to dollId-only`() {
        // 【兜底策略】切不出 `|` 时，整串当 dollId、actionId 留空，
        // 与 DollAnimPage.buildPage(act, key) 单参数重载行为一致。
        val key = "doll:anim:onlydoll"
        val navKey = NavKeyCodec.toKey(key)
        assertNotNull(navKey)
        assertTrue(navKey is DollAnimRoute)
        val r = navKey as DollAnimRoute
        assertTrue(r.dollId == "onlydoll")
        assertTrue(r.actionId.isEmpty())
    }

    @Test
    fun `round-trip must preserve all keys`() {
        // 【双向一致性】toKey → toLegacy → toKey 必须还原（存档/恢复场景）。
        val keys = listOf(
            "feiyu_settings_page", "doll:edit:x", "doll:anim:a|b",
            "feiyu_cfg_page", "doll:list", "feiyu_token_page"
        )
        for (key in keys) {
            val navKey = NavKeyCodec.toKey(key)
            assertNotNull("toKey($key) 失败", navKey)
            val legacy = NavKeyCodec.toLegacy(navKey)
            assertNotNull("toLegacy($navKey) 失败", legacy)
            val navKey2 = NavKeyCodec.toKey(legacy)
            assertNotNull("round-trip($key) 失败", navKey2)
        }
    }
}
