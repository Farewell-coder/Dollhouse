package com.dollhouse.app.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * 【职责】Jetpack Navigation 3 的**导航 key 定义**（类型安全路由）。
 *
 * 【为什么用 data class / data object 当 key】
 *   Navigation 3 的核心模型是「back stack 是一个 key 列表」：
 *   `NavBackStack<NavKey>` 就是一个 `SnapshotStateList<NavKey>`。
 *   每个 key 描述「一个目的地的全部入参」，`NavDisplay` 按 key 查 `entryProvider` 渲染。
 *
 * 【为什么必须 @Serializable】
 *   `rememberNavBackStack` 用 `NavBackStackSerializer` 把整条栈写进 `SavedStateRegistry`，
 *   进程被杀 / 配置变更重建时按 key 原样恢复。
 *   不标 `@Serializable` 的 key 会在存档时抛序列化异常 —— 这正是本工程旧实现
 *   「页面恢复失败」问题在 Nav3 下的等价形态，必须从类型层面杜绝。
 *
 * 【与旧 key 字符串的关系】
 *   旧 `PageStack` 用字符串 key（如 `doll:edit:<dollId>`），参数藏在字符串里靠 substring 取。
 *   这里把参数提升为**构造参数**：类型安全、编译期可查、不再有「前缀顺序错了就被抢走」的隐患
 *   （旧实现为此在 PageRegistry 里专门写了「长的在前」的分派顺序注释）。
 *   旧字符串 key 仍在 `PageKeys` 里保留一份映射，供存档迁移与调试对照。
 */

/* ============================ 底座（Tab 宿主） ============================ */

/**
 * 主界面底座：三 Tab 的容器。
 *
 * 【为什么底座也是一个 NavKey】Nav3 的 `NavDisplay` 只显示「栈顶那一项」。
 *   底座必须始终在栈底、且**永不弹出**，这样：
 *   · 从任意 Tab 压覆盖页时，覆盖页在栈顶、底座被盖住（与旧实现的层级一致）；
 *   · 覆盖页弹掉后，露出底座（且 Tab 选中项由 [com.dollhouse.app.ui.home.AppNav] 保留）。
 *   【为什么不用 NavDisplay 的 sceneStrategy 做多目的地同显】那需要 `Scene` 自定义，
 *   而本工程的底座与覆盖页是「完全互相遮挡」的关系，用最朴素的一层栈最稳。
 */
@Serializable
data object HomeRoute : NavKey

/* ============================ 二级页（覆盖层） ============================ */

/** 人偶列表页（`doll:list`）。 */
@Serializable
data object DollListRoute : NavKey

/** 人偶编辑页（`doll:edit:<dollId>`）。 */
@Serializable
data class DollEditRoute(val dollId: String) : NavKey

/** 人偶动作清单页（`doll:action:<dollId>`）。 */
@Serializable
data class DollActionRoute(val dollId: String) : NavKey
/**
 * **【工作台】**资源导入向导页（`doll:import:<dollId>`）。
 *
 * 【为什么必须是一个独立的 NavKey】
 *   本工程所有页面都走 `PageStack.push(ctx, "<legacy key>") { … }`，而
 *   `NavHost.push` 会先用 [`NavKeyCodec.toKey`] 把 legacy 字符串解成 NavKey；
 *   **解不出来就直接 return false**（只打一条 W 日志）。
 *   于是「新页面忘了在这里加一条」的症状是：点了入口既不崩也不报错、什么也不发生
 *   —— 极难自查。本条与 [`PageRegistry`] 里那一条必须成对出现。
 */
@Serializable
data class DollImportRoute(val dollId: String) : NavKey

/**
 * 动作详情页（`doll:anim:<dollId>:<actionId>`）。
 *
 * 【为什么不用字符串拼接】旧实现 key 形如 `doll:anim:<dollId>:<actionId>`，
 *   若 dollId 里含 `:` 就会解析错位。改成两个独立字段后，这个问题在类型层面消失。
 */
@Serializable
data class DollAnimRoute(val dollId: String, val actionId: String) : NavKey

/** 手势响应页（`doll:gesture:<dollId>`）。 */
@Serializable
data class DollGestureRoute(val dollId: String) : NavKey

/** 暂离设置页（`doll:away:<dollId>`）。 */
@Serializable
data class DollAwayRoute(val dollId: String) : NavKey

/** 回归时间页（`doll:return:<dollId>`）。 */
@Serializable
data class DollReturnRoute(val dollId: String) : NavKey

/** 活动页（`doll:home:<dollId>`）。 */
@Serializable
data class DollHomeRoute(val dollId: String) : NavKey

/** 屏幕范围页（`doll:range:<dollId>`）。 */
@Serializable
data class DollRangeRoute(val dollId: String) : NavKey

/** 交谈内容页（`doll:talk:<dollId>`）。 */
@Serializable
data class DollTalkRoute(val dollId: String) : NavKey

/** 人格 / 性格内容页（`doll:persona:<dollId>`）。 */
@Serializable
data class DollPersonaRoute(val dollId: String) : NavKey

/** 人偶分组页（`doll:groups`）。 */
@Serializable
data object DollGroupsRoute : NavKey

/**
 * 好友资料页（`chat:friend:<dollId>`）。
 *
 * 【为什么参数是 dollId 而不是会话 id】资料页聚合的是「这个人」的人格、模型、
 *   记忆与列表状态；会话是 `d_<dollId>` 的投影，可能被删除重建，不能当身份用。
 */
@Serializable
data class FriendProfileRoute(val dollId: String) : NavKey
@Serializable data class ConversationHistoryRoute(val dollId: String) : NavKey
@Serializable data class FriendModelConfigRoute(val dollId: String) : NavKey

/**
 * **【v0.1.5·19.2.3】更换模型**（`doll:swap:<dollId>`）。
 *
 * 【为什么单独一条路由，而不复用 DollAddRoute】两者作用域根本不同：
 *   · 添加角色 → 在角色库**新建**一个 Doll；
 *   · 更换模型 → 在**当前** Doll 内替换视觉模型，角色 id / 人格 / 记忆全部不变。
 *   需求 19.2.5 明确「提交失败则恢复旧模型；不得在角色库额外生成重复 Doll」，
 *   共用一条路径迟早会把这两种语义混起来，所以从路由层就分开。
 */
@Serializable
data class DollSwapRoute(val dollId: String) : NavKey

/**
 * **【v0.1.5·8.9】模型管理页**（`doll:model:<dollId>`）。
 *
 * 【为什么单独一条】它是「当前角色长什么样」的**查看 + 两个动作**的聚合页，
 *   与「更换模型」（只做替换）、「资源导入」（只加动作）都不同：
 *   本页只读展示四态状态，动作是跳出去的新的页面。
 */
@Serializable
data class DollModelRoute(val dollId: String) : NavKey

/**
 * **【v0.1.5·R-040】添加角色导入助手**（`doll:add`）。
 *
 * 【为什么是无参数的对象】导入助手的会话由页面自己创建与管理（v2.1·21.1），
 *   它不依附任何已存在的角色 —— 角色是在提交那一刻才产生的。
 *   所以 key 里没有 dollId，与「资源导入向导」（`doll:import:<dollId>`）刻意区分开：
 *   后者是给**已有角色**加动作，两者语义不同，不能共用 key。
 */
@Serializable
data object DollAddRoute : NavKey

/* ============================ 设置层 ============================ */

/** 设置页（`feiyu_settings_page`）。 */
@Serializable
data object SettingsRoute : NavKey

/** 我的资料页（`settings:myprofile`）。 */
@Serializable
data object MyProfileRoute : NavKey

/** 外观页（`settings:appearance`）。 */
@Serializable
data object AppearanceRoute : NavKey

/** 关于页（`feiyu_about_page`）。 */
@Serializable
data object AboutRoute : NavKey

/** Token 统计页（`feiyu_token_page`）。 */
@Serializable
data object TokenStatRoute : NavKey

/** 记忆库页（`feiyu_mem_page`）。 */
@Serializable
data class MemRoute(val dollId: String) : NavKey

/** 记忆库设置页（`feiyu_mem_settings_page`）。 */
@Serializable
data object MemSettingsRoute : NavKey

/** 聊天设置页（`feiyu_chat_settings_page`）。 */
@Serializable
data object ChatSettingsRoute : NavKey

/** 识图模型页（`feiyu_vision_model_page`）。 */
@Serializable
data object VisionModelRoute : NavKey

/** 开发者选项页（`feiyu_dev_options_page`）。 */
@Serializable
data object DeveloperOptionsRoute : NavKey

/**
 * 随机走位设置页（`settings:wander`）。
 * 【r19】实验卡片下级入口，含真实开关（PetPrefs.wanderEnabled）。
 */
@Serializable
data object WanderSettingsRoute : NavKey

/**
 * 睡觉模式设置页（`settings:sleep`）。
 * 【r19】实验卡片下级入口，仅说明文案（睡觉由快捷菜单触发，无独立开关）。
 */
@Serializable
data object SleepSettingsRoute : NavKey

/**
 * 供应商模块（`feiyu_cfg_page`）。
 *
 * 【为什么内部子路由不展开成 NavKey】供应商模块是「一层壳 + 内部业务路由栈」
 *   （列表 / 新建 / 编辑 / 模型 / 详情），旧实现用 `ProviderNav` 自管一条 `MutableList<String>`，
 *   只占 PageStack 的一层。用户明确要求「保留供应商内部路由」，
 *   故这里保持「一个 NavKey = 一层」，内部路由仍由 [com.dollhouse.app.ui.provider.ProviderNav] 管理。
 *   把它展开成多个 NavKey 会让每个子路由都变成独立层，返回语义与业务路由栈不再一致。
 */
@Serializable
data object ProviderRoute : NavKey
