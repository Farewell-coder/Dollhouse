package com.dollhouse.app.ui.nav

import androidx.navigation3.runtime.NavKey

/**
 * 【职责】[NavKey] 与历史字符串 key 之间的**双向编解码**。
 *
 * 【为什么需要这一层】
 *   导航迁移是「渐进式」的：路由的**真源**已换成类型安全的 [NavKey]（[NavRoutes]），
 *   但工程里仍有大量既有代码按字符串 key 工作 —— 最典型的是
 *   [com.dollhouse.app.ui.compose.PageRegistry] 与 [com.dollhouse.app.ui.compose.PageStack]
 *   的对外签名（84 处调用点）。一次性全改签名会把「导航迁移」变成「全工程大重构」，
 *   风险与收益不成比例。
 *   这一层把字符串当作**兼容视图**：外部照旧传字符串，内部立刻转成 [NavKey]。
 *   等日后调用点逐步迁完，本文件可整体删除（届时不会有任何调用方）。
 *
 * 【为什么不用 Nav3 自带的序列化做存档】
 *   `NavBackStackSerializer` 走 kotlinx.serialization 的多态机制，需要把每个 NavKey 子类
 *   注册进 `SerializersModule`；漏注册的后果是**运行时**存档失败（编译期查不出来）。
 *   本工程已有成熟的「Bundle 存字符串列表」存档通道，且字符串 key 本身是稳定的对外契约。
 *   因此存档仍走字符串，而 [NavKey] 负责内存中的类型安全 —— 两边各取所长。
 */
object NavKeyCodec {

    /* ============================ 历史字符串 key（与改造前逐字一致） ============================ */

    const val LEGACY_HOME = "feiyu_home_page"
    const val LEGACY_DOLL_LIST = "doll:list"
    const val LEGACY_SETTINGS = "feiyu_settings_page"
    const val LEGACY_MY_PROFILE = "settings:myprofile"
    const val LEGACY_APPEARANCE = "settings:appearance"
    const val LEGACY_ABOUT = "feiyu_about_page"
    const val LEGACY_TOKEN = "feiyu_token_page"
    const val LEGACY_MEM = "feiyu_mem_page"
    const val LEGACY_MEM_SETTINGS = "feiyu_mem_settings_page"
    const val LEGACY_CHAT_SETTINGS = "feiyu_chat_settings_page"
    const val LEGACY_VISION_MODEL = "feiyu_vision_model_page"
    const val LEGACY_DEV_OPTIONS = "feiyu_dev_options_page"
    const val LEGACY_PROVIDER = "feiyu_cfg_page"
    const val LEGACY_DOLL_GROUPS = "doll:groups"
    /** 【r19】随机走位设置页 */
    const val LEGACY_WANDER = "settings:wander"
    /** 【r19】睡觉模式设置页 */
    const val LEGACY_SLEEP = "settings:sleep"

    /* ============================ 各页面的 key 前缀（与各页面 PREFIX 常量同值） ============================ */

    private const val P_EDIT = "doll:edit:"
    private const val P_ACTION = "doll:action:"
    /** 【工作台】资源导入向导页。必须与 `DollImportWizardPage.PREFIX` 同值。 */
    private const val P_IMPORT = "doll:import:"
    private const val P_ANIM = "doll:anim:"
    private const val P_GESTURE = "doll:gesture:"
    private const val P_AWAY = "doll:away:"
    private const val P_RETURN = "doll:return:"
    private const val P_HOME_PAGE = "doll:home:"
    private const val P_RANGE = "doll:range:"
    private const val P_TALK = "doll:talk:"
    private const val P_PERSONA = "doll:persona:"

    /**
     * 把历史字符串 key 解成类型安全的 [NavKey]；认不出的返回 null。
     *
     * 【分派顺序】`doll:home:` 与 `doll:` 家族其它前缀互不为前缀，顺序不影响正确性，
     *   但保持「先精确全等、再长前缀、后短前缀」的书写顺序更易读、也更能防未来的前缀嵌套。
     */
    fun toKey(legacy: String?): NavKey? {
        if (legacy.isNullOrEmpty()) {
            return null
        }
        return when {
            LEGACY_HOME == legacy -> HomeRoute
            LEGACY_DOLL_LIST == legacy -> DollListRoute
            LEGACY_DOLL_GROUPS == legacy -> DollGroupsRoute
            LEGACY_SETTINGS == legacy -> SettingsRoute
            LEGACY_MY_PROFILE == legacy -> MyProfileRoute
            LEGACY_APPEARANCE == legacy -> AppearanceRoute
            LEGACY_ABOUT == legacy -> AboutRoute
            LEGACY_TOKEN == legacy -> TokenStatRoute
            LEGACY_MEM_SETTINGS == legacy -> MemSettingsRoute
            LEGACY_CHAT_SETTINGS == legacy -> ChatSettingsRoute
            LEGACY_VISION_MODEL == legacy -> VisionModelRoute
            LEGACY_DEV_OPTIONS == legacy -> DeveloperOptionsRoute
            LEGACY_PROVIDER == legacy -> ProviderRoute
            LEGACY_WANDER == legacy -> WanderSettingsRoute
            LEGACY_SLEEP == legacy -> SleepSettingsRoute
            // 记忆库页的历史 key 不含 dollId（页内自己从偏好取当前人偶），故这里给空串。
            // 【本轮修正】`toLegacy` 侧同样只输出裸 key（见下方 is MemRoute 分支）——
            //   两侧对称，「key ↔ legacy」往返才不丢信息。真正的 dollId 由
            //   `MemPage` 落到笔 `mem_page_doll_id` 里跨进程保存（见 PetPrefs.memPageDollId）。
            LEGACY_MEM == legacy -> MemRoute("")
            legacy.startsWith(P_EDIT) -> DollEditRoute(legacy.removePrefix(P_EDIT))
            legacy.startsWith(P_ACTION) -> DollActionRoute(legacy.removePrefix(P_ACTION))
            // 【工作台】导入向导页。排在 P_ACTION 之后：`doll:import:` 与 `doll:action:`
            //   互不为前缀，顺序不影响正确性，但把同族（都是 `doll:` 二级页）放一起更易读。
            legacy.startsWith(P_IMPORT) -> DollImportRoute(legacy.removePrefix(P_IMPORT))
            legacy.startsWith(P_GESTURE) -> DollGestureRoute(legacy.removePrefix(P_GESTURE))
            legacy.startsWith(P_AWAY) -> DollAwayRoute(legacy.removePrefix(P_AWAY))
            legacy.startsWith(P_RETURN) -> DollReturnRoute(legacy.removePrefix(P_RETURN))
            legacy.startsWith(P_HOME_PAGE) -> DollHomeRoute(legacy.removePrefix(P_HOME_PAGE))
            legacy.startsWith(P_RANGE) -> DollRangeRoute(legacy.removePrefix(P_RANGE))
            legacy.startsWith(P_TALK) -> DollTalkRoute(legacy.removePrefix(P_TALK))
            legacy.startsWith(P_PERSONA) -> DollPersonaRoute(legacy.removePrefix(P_PERSONA))
            legacy.startsWith(P_ANIM) -> parseAnim(legacy.removePrefix(P_ANIM))
            else -> null
        }
    }

    /**
     * 解析动作详情页的复合 key：`<dollId>|<actionId>`。
     *
     * 【分隔符必须是 `|` 而不是 `:`】这是**用错分隔符就会「点不开页面」的隐蔽 bug**：
     *   `DollAnimPage.open` 拼的是 `PREFIX + dollId + "|" + actionId`
     *   （见 DollAnimPage.kt:98，那里有注释说明「`|` 绝不会与别的前缀撞车」）；
     *   而 `DollAnimPage.buildPage(act, key)` 单参数重载也按 `indexOf('|')` 切。
     *   本方法早期版本按 `:` 切 → 切不出两段 → 返回 null → `NavHost.push` 直接失败，
     *   表现为**点动作项目毫无反应**（不崩、不报错、什么都没发生，最难查的一类症状）。
     *   现按页面自身的口径改为 `|`。
     *
     * 【兜底】切不出两段时**不再返回 null**，而是把整串当作 dollId、actionId 留空 ——
     *   与 `DollAnimPage.buildPage(act, key)` 单参数重载的行为完全一致
     *   （它也是切不出就用整串）。这样即使 key 格式将来又变，
     *   最坏是「动作页打开但内容为空」，而不是「点了没反应」。
     */
    private fun parseAnim(rest: String): NavKey {
        val i = rest.indexOf('|')
        return if (i < 0) {
            DollAnimRoute(rest, "")
        } else {
            DollAnimRoute(rest.substring(0, i), rest.substring(i + 1))
        }
    }

    /** 把 [NavKey] 还原成历史字符串 key（存档 / 调试对照用）。 */
    fun toLegacy(key: NavKey?): String? = when (key) {
        null -> null
        HomeRoute -> LEGACY_HOME
        DollListRoute -> LEGACY_DOLL_LIST
        DollGroupsRoute -> LEGACY_DOLL_GROUPS
        SettingsRoute -> LEGACY_SETTINGS
        MyProfileRoute -> LEGACY_MY_PROFILE
        AppearanceRoute -> LEGACY_APPEARANCE
        AboutRoute -> LEGACY_ABOUT
        TokenStatRoute -> LEGACY_TOKEN
        MemSettingsRoute -> LEGACY_MEM_SETTINGS
        ChatSettingsRoute -> LEGACY_CHAT_SETTINGS
        VisionModelRoute -> LEGACY_VISION_MODEL
        DeveloperOptionsRoute -> LEGACY_DEV_OPTIONS
        ProviderRoute -> LEGACY_PROVIDER
        is MemRoute -> LEGACY_MEM
        is DollEditRoute -> P_EDIT + key.dollId
        is DollActionRoute -> P_ACTION + key.dollId
        is DollImportRoute -> P_IMPORT + key.dollId
        is DollGestureRoute -> P_GESTURE + key.dollId
        is DollAwayRoute -> P_AWAY + key.dollId
        is DollReturnRoute -> P_RETURN + key.dollId
        is DollHomeRoute -> P_HOME_PAGE + key.dollId
        is DollRangeRoute -> P_RANGE + key.dollId
        is DollTalkRoute -> P_TALK + key.dollId
        is DollPersonaRoute -> P_PERSONA + key.dollId
        is DollAnimRoute -> P_ANIM + key.dollId + "|" + key.actionId
        WanderSettingsRoute -> LEGACY_WANDER
        SleepSettingsRoute -> LEGACY_SLEEP
        else -> null
    }
}