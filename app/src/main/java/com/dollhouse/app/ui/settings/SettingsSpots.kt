package com.dollhouse.app.ui.settings

import com.dollhouse.app.device.ShizukuBridge
import com.dollhouse.app.ui.home.HomeState
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.theme.ThemeManager

/**
 * 【职责】设置项索引表 + 搜索（**纯数据 + 纯函数，不接线、不依赖任何页面类**）。
 *
 * ## 为什么要有索引表
 *
 * 设置子页有 14 个、设置行 60+ 条，用户「知道有这个东西但找不到它在哪」的成本很高。
 * 索引表把「所有可搜的设置项」集中成一份数据，搜索结果里直接给出「标题 + 面包屑 + 当前值」，
 * 点一下跳到那一页。数据来源见 `ui-research/012-P1e-settings-index-plan.md`。
 *
 * ## 为什么 pageKey 用**字符串字面量**而不是引用页面常量
 *
 * 若 `import AboutPage.TAG_PAGE`，本文件就**依赖所有页面类**，而页面反过来要从本表取标题
 * （下一步接线时）—— 依赖成环。所以这里只存字符串，靠单元测试
 * `SettingsSpotsTest` 断言「每个 pageKey 都能被 `PageRegistry.isPageKey` 识别」。
 * 那些 pageKey 常量都是 `const val`，编译期内联，测试里调用 `isPageKey` 不会加载 Android 类。
 *
 * ## 与 dsh-Go 的差距（如实说明）
 *
 * dsh-Go 用「必填 `spot` 参数」强制页面必须引用索引才能显示标题（漏登记 = 显示为空）。
 * 我们 87 处 `ActionRow` 调用点做不到一次改必填，所以下一步接线用的是
 * **可选 `spot` 参数 + 空标题自纠错**：已登记的行走索引，未登记的原样显示，
 * 登记率可以逐页提高；都为空时标题显示为空（肉眼可见的缺失，比静默漂移好查）。
 * 本文件只提供数据，不改变任何页面的现有行为。
 *
 * ## 收录范围（11 页 / 65 行）
 *
 * A 类 `PageStack` 独立页 10 个：权限与保活 / 对话显示 / 记忆库设置 / 识图模型 /
 * 随机走位 / 睡觉模式 / 外观 / 我的资料 / 诊断中心 / 关于；
 * B 类 1 个：lamda 设备控制（`ProviderNav` 内部路由 `R_LAMDA`，页 key 仍是 `ProviderNav.TAG_PAGE`）。
 *
 * **不收录**：`MemPage`（记忆图谱是展示页不是设置页）、`ModelListPage` / `ModelEditPage`
 * （行由服务端数据决定，无法静态枚举）、`ExperimentPage`（纯导航中转）。
 * 设置首页的「入口行」也不收录 —— 它们只是通往上述页面的指针，收录会造成重复搜索结果。
 */
data class SettingsSpot(
    /** 全局唯一稳定主键，形如 `appearance.monet`。跨版本不得改名（搜索历史/埋点会引用）。 */
    val rowId: String,
    /** 所属页面的 `PageStack` key（必须能被 `PageRegistry.isPageKey` 识别）。 */
    val pageKey: String,
    /** 行标题的**唯一真源**；下一步接线后页面从这里取，不再各写一份字面量。 */
    val title: String,
    /** 面包屑，如 `设置 › 外观 › 主题模式`。搜索命中路径也计分，且结果里直接展示。 */
    val path: String,
    /** 中文检索词：同义词 / 旧功能名 / 口语说法。空表示只靠标题匹配。 */
    val keywordsZh: List<String> = emptyList(),
    /**
     * 英文检索词。
     *
     * 【为什么必须显式登记】我们的设置项标题**全是中文**，只有 Shizuku / API Key / Token
     * 这类技术名词天然带英文。不做登记，用户输入 `monet`、`overlay`、`stream` 一律搜不到。
     */
    val keywordsEn: List<String> = emptyList(),
    /** 当前是否可用。不可用时**照列**（不隐藏），并在结果里展示 [unavailableHint]。 */
    val available: () -> Boolean = { true },
    /** 不可用的原因文案。仅在 [available] 返回 false 时有意义。 */
    val unavailableHint: String = "",
    /**
     * 当前值（入口行右侧那种「已开启 / 已就绪 / 75%」）。
     *
     * 【为什么是函数】值随状态实时变化（读取 `HomeState.*` 的 `mutableStateOf`）。
     * 【为什么没有 Context 参数】本表刻意不依赖 Android Context；
     * 需要 Context 才能取的值（如「已设置的背景图文件名」）这里返回 null，留给接线时的页面侧补。
     */
    val value: () -> String? = { null },
    /** 同分时的排序权重：高频=5 / 中频=2 / 低频=0。 */
    val freq: Int = 0
)

object SettingsSpots {

    /* ============================ 页面 key（字面量，见类注释） ============================ */

    private const val K_PERMISSION = "settings:permission"
    private const val K_CHAT = "feiyu_chat_settings_page"
    private const val K_MEM = "feiyu_mem_settings_page"
    private const val K_VISION = "feiyu_vision_model_page"
    private const val K_WANDER = "settings:wander"
    private const val K_SLEEP = "settings:sleep"
    private const val K_APPEARANCE = "settings:appearance"
    private const val K_PROFILE = "settings:myprofile"
    private const val K_DEV = "feiyu_dev_options_page"
    private const val K_ABOUT = "feiyu_about_page"

    /** B 类：lamda 设备控制在 `ProviderNav` 内部路由，外层页 key 是它。 */
    private const val K_PROVIDER = ProviderNav.TAG_PAGE

    /** 面包屑根。 */
    private const val ROOT = "设置 › "

    private fun spot(
        rowId: String,
        pageKey: String,
        title: String,
        path: String,
        zh: List<String> = emptyList(),
        en: List<String> = emptyList(),
        available: () -> Boolean = { true },
        hint: String = "",
        value: () -> String? = { null },
        freq: Int = 0
    ): SettingsSpot = SettingsSpot(
        rowId = rowId,
        pageKey = pageKey,
        title = title,
        path = ROOT + path,
        keywordsZh = zh,
        keywordsEn = en,
        available = available,
        unavailableHint = hint,
        value = value,
        freq = freq
    )

    /* ============================ 1. 权限与保活（8 行） ============================ */

    private val PERMISSION: List<SettingsSpot> = listOf(
        spot(
            "permission.shizuku", K_PERMISSION, "Shizuku 授权", "权限与保活 › Shizuku 授权",
            zh = listOf("shizuku", "授权", "adb", "设备控制", "shell"),
            en = listOf("shizuku", "adb", "shell"),
            value = {
                when (HomeState.shizuku) {
                    ShizukuBridge.S_GRANTED -> "已授权"
                    ShizukuBridge.S_NOT_GRANTED -> "未授权"
                    ShizukuBridge.S_NOT_RUNNING -> "未运行"
                    else -> "未安装"
                }
            },
            freq = 5
        ),
        spot(
            "permission.overlay", K_PERMISSION, "悬浮窗权限", "权限与保活 › 悬浮窗权限",
            zh = listOf("悬浮窗", "桌面人偶", "顶层窗口", "显示在其他应用上层"),
            en = listOf("overlay", "draw over apps", "float"),
            value = { if (HomeState.overlay) "已授予" else "未授予" },
            freq = 5
        ),
        spot(
            "permission.notification", K_PERMISSION, "通知权限", "权限与保活 › 通知权限",
            zh = listOf("通知", "消息提醒", "权限"),
            en = listOf("notification", "permission"),
            value = { if (HomeState.notif) "已授予" else "未授予" },
            freq = 2
        ),
        spot(
            "permission.battery", K_PERMISSION, "电池优化白名单", "权限与保活 › 电池优化白名单",
            zh = listOf("电池", "省电", "doze", "后台被杀", "自启动"),
            en = listOf("battery", "doze", "whitelist", "optimization"),
            value = { if (HomeState.battery) "已加入白名单" else "未加入" },
            freq = 5
        ),
        spot(
            "permission.keepalive", K_PERMISSION, "无感保活", "权限与保活 › 无感保活",
            zh = listOf("保活", "后台常驻", "不被杀", "无感保活"),
            en = listOf("keepalive", "keep alive", "background"),
            value = { if (HomeState.keepAlive) "已开启" else "已关闭" },
            freq = 5
        ),
        spot(
            "permission.hideRecents", K_PERMISSION, "隐藏后台卡片", "权限与保活 › 隐藏后台卡片",
            zh = listOf("后台卡片", "多任务", "最近任务", "伪装"),
            en = listOf("recents", "task card", "hide"),
            value = { if (HomeState.hideRecents) "已开启" else "已关闭" },
            freq = 2
        ),
        spot(
            "permission.tile", K_PERMISSION, "快捷设置磁贴", "权限与保活 › 快捷设置磁贴",
            zh = listOf("磁贴", "下拉开关", "状态栏快捷方式"),
            en = listOf("tile", "quick settings", "quick tile"),
            value = { if (HomeState.tile) "已添加" else "未添加" },
            freq = 2
        ),
        spot(
            "permission.shellCommand", K_PERMISSION, "开关指令", "权限与保活 › 开关指令",
            zh = listOf("指令", "命令行", "复制指令", "adb 命令"),
            en = listOf("command", "shell command", "adb"),
            freq = 0
        )
    )

    /* ============================ 2. 对话显示（3 行） ============================ */

    private val CHAT: List<SettingsSpot> = listOf(
        spot(
            "chat.stream", K_CHAT, "流式输出", "对话显示 › 流式输出",
            zh = listOf("流式", "逐字出现", "打字机"),
            en = listOf("stream", "streaming", "sse"),
            freq = 5
        ),
        spot(
            "chat.thinking", K_CHAT, "显示思考过程", "对话显示 › 显示思考过程",
            zh = listOf("思考过程", "推理", "思维链", "折叠思考"),
            en = listOf("thinking", "reasoning", "cot", "chain of thought"),
            freq = 5
        ),
        spot(
            "chat.learnExamples", K_CHAT, "把示范带进对话", "对话显示 › 把示范带进对话",
            zh = listOf("学习示例", "示范", "few-shot", "示例对话"),
            en = listOf("example", "few-shot", "learning"),
            freq = 2
        )
    )

    /* ============================ 3. 记忆库设置（8 行） ============================ */

    private val MEM: List<SettingsSpot> = listOf(
        spot(
            "mem.autosave", K_MEM, "自动保存记忆", "记忆库设置 › 自动保存",
            zh = listOf("记忆", "自动总结", "归档", "长期记忆"),
            en = listOf("memory", "autosave", "auto save"),
            freq = 5
        ),
        spot(
            "mem.interval", K_MEM, "保存间隔", "记忆库设置 › 保存间隔",
            zh = listOf("间隔", "多久保存一次", "频率", "触发阈值"),
            en = listOf("interval", "frequency", "throttle"),
            freq = 5
        ),
        spot(
            "mem.rule", K_MEM, "保存规则", "记忆库设置 › 提取规则",
            zh = listOf("规则", "提取规则", "提示词", "只记什么"),
            en = listOf("rule", "prompt", "extract"),
            freq = 2
        ),
        spot(
            "mem.retrieve", K_MEM, "记忆检索", "记忆库设置 › 检索",
            zh = listOf("检索", "注入记忆", "召回"),
            en = listOf("retrieval", "recall", "inject"),
            freq = 5
        ),
        spot(
            "mem.mode", K_MEM, "检索模式", "记忆库设置 › 检索模式",
            zh = listOf("检索模式", "关键词模式", "语义模式"),
            en = listOf("retrieval mode", "keyword", "semantic"),
            freq = 2
        ),
        spot(
            "mem.wKeyword", K_MEM, "关键词权重", "记忆库设置 › 关键词权重",
            zh = listOf("关键词权重", "权重", "调权"),
            en = listOf("keyword weight", "weight"),
            freq = 0
        ),
        spot(
            "mem.wTag", K_MEM, "标签权重", "记忆库设置 › 标签权重",
            zh = listOf("标签权重", "权重"),
            en = listOf("tag weight", "weight"),
            freq = 0
        ),
        spot(
            "mem.wLink", K_MEM, "关联权重", "记忆库设置 › 关联权重",
            zh = listOf("关联权重", "权重", "连接强度"),
            en = listOf("link weight", "association weight", "weight"),
            freq = 0
        )
    )

    /* ============================ 4. 识图模型（2 行） ============================ */

    private val VISION: List<SettingsSpot> = listOf(
        spot(
            "vision.current", K_VISION, "识图模型", "识图模型 › 当前设置",
            zh = listOf("识图", "看图", "图片理解", "视觉模型", "ocr"),
            en = listOf("vision", "image model", "multimodal", "ocr"),
            freq = 5
        ),
        spot(
            "vision.followChat", K_VISION, "跟随当前聊天模型", "识图模型 › 可选模型",
            zh = listOf("跟随聊天模型", "继承", "用同一个模型"),
            en = listOf("follow chat model", "inherit", "same as chat"),
            freq = 2
        )
    )

    /* ============================ 5. 随机走位（1 行） ============================ */

    private val WANDER: List<SettingsSpot> = listOf(
        spot(
            "wander.enable", K_WANDER, "启用随机走位", "随机走位 › 开关",
            zh = listOf("随机走位", "走动", "乱跑", "自己动"),
            en = listOf("wander", "random walk", "roam"),
            freq = 5
        )
    )

    /* ============================ 6. 睡觉模式（3 行） ============================ */

    private val SLEEP: List<SettingsSpot> = listOf(
        spot(
            "sleep.quiet", K_SLEEP, "启用安静时段", "睡觉模式 › 安静时段",
            zh = listOf("安静时段", "勿扰", "到点不说话"),
            en = listOf("sleep", "quiet hours", "do not disturb"),
            freq = 5
        ),
        spot(
            "sleep.start", K_SLEEP, "开始时间", "睡觉模式 › 安静时段",
            zh = listOf("开始时间", "几点开始安静"),
            en = listOf("start time", "from"),
            freq = 2
        ),
        spot(
            "sleep.end", K_SLEEP, "结束时间", "睡觉模式 › 安静时段",
            zh = listOf("结束时间", "几点恢复"),
            en = listOf("end time", "until"),
            freq = 2
        )
    )

    /* ============================ 7. 外观（19 行） ============================ */

    private val APPEARANCE: List<SettingsSpot> = listOf(
        spot(
            "appearance.mode", K_APPEARANCE, "当前模式", "外观 › 主题模式",
            zh = listOf("主题", "浅色", "深色", "纯黑", "跟随系统", "夜间模式"),
            en = listOf("theme", "dark mode", "light mode", "amoled"),
            value = { ThemeManager.MODE_NAMES.getOrNull(HomeState.themeMode) },
            freq = 5
        ),
        spot(
            "appearance.monet", K_APPEARANCE, "莫奈主题色", "外观 › 主题模式",
            zh = listOf("莫奈", "取色", "主题色", "壁纸配色"),
            en = listOf("monet", "material you", "dynamic color"),
            value = { if (HomeState.monet) "已开启" else "已关闭" },
            freq = 5
        ),
        spot(
            "appearance.monetPick", K_APPEARANCE, "从壁纸取色", "外观 › 主题模式",
            zh = listOf("从壁纸取色", "重新取色", "换壁纸后取色"),
            en = listOf("pick color from wallpaper", "extract color"),
            available = { HomeState.monet },
            hint = "关闭莫奈主题色后可用",
            freq = 2
        ),
        spot(
            "appearance.fontColor", K_APPEARANCE, "字体颜色", "外观 › 字体颜色",
            zh = listOf("字体颜色", "文字色", "改颜色", "对比度"),
            en = listOf("font color", "text color"),
            freq = 2
        ),
        spot(
            "appearance.bgEnable", K_APPEARANCE, "使用自定义背景", "外观 › 全局背景",
            zh = listOf("自定义背景", "聊天背景", "背景图"),
            en = listOf("background", "chat background", "wallpaper"),
            value = { if (HomeState.bgSet) "已设置" else "未设置" },
            freq = 5
        ),
        spot(
            "appearance.bgPick", K_APPEARANCE, "更换图片", "外观 › 全局背景",
            zh = listOf("更换背景", "选图", "换一张"),
            en = listOf("change image", "pick image"),
            available = { HomeState.bgSet },
            hint = "先设置一张背景图",
            freq = 2
        ),
        spot(
            "appearance.bgClear", K_APPEARANCE, "移除背景", "外观 › 全局背景",
            zh = listOf("移除背景", "删除背景图", "恢复默认"),
            en = listOf("remove background", "clear"),
            available = { HomeState.bgSet },
            hint = "当前没有设置背景图",
            freq = 0
        ),
        spot(
            "appearance.bgFill", K_APPEARANCE, "背景填充", "外观 › 全局背景",
            zh = listOf("填充", "平铺", "拉伸", "裁剪"),
            en = listOf("fill", "fit", "crop", "tile"),
            freq = 0
        ),
        spot(
            "appearance.bgAlpha", K_APPEARANCE, "背景图不透明度", "外观 › 背景参数",
            zh = listOf("背景不透明度", "背景透明度", "背景太实", "背景太淡"),
            en = listOf("background opacity", "alpha"),
            value = { HomeState.bgAlpha.toString() + "%" },
            freq = 2
        ),
        spot(
            "appearance.bgBlur", K_APPEARANCE, "背景毛玻璃", "外观 › 背景参数",
            zh = listOf("毛玻璃", "模糊", "虚化"),
            en = listOf("blur", "frosted", "glass"),
            freq = 2
        ),
        spot(
            "appearance.bgScrim", K_APPEARANCE, "遮罩强度", "外观 › 背景参数",
            zh = listOf("遮罩", "压暗", "蒙层", "看不清字"),
            en = listOf("scrim", "overlay", "dim"),
            value = { HomeState.bgScrim.toString() + "%" },
            freq = 2
        ),
        spot(
            "appearance.cardAlpha", K_APPEARANCE, "卡片不透明度", "外观 › 背景参数",
            zh = listOf("卡片不透明度", "卡片透明", "卡片太透"),
            en = listOf("card opacity", "alpha"),
            value = { HomeState.bgCardAlpha.toString() + "%" },
            freq = 2
        ),
        spot(
            "appearance.bubbleAvatar", K_APPEARANCE, "显示头像", "外观 › 气泡",
            zh = listOf("头像", "气泡头像", "显示头像"),
            en = listOf("avatar", "bubble avatar"),
            freq = 2
        ),
        spot(
            "appearance.bubbleFrost", K_APPEARANCE, "气泡磨砂", "外观 › 气泡",
            zh = listOf("磨砂", "气泡质感", "毛玻璃气泡"),
            en = listOf("frost", "frosted bubble"),
            freq = 0
        ),
        spot(
            "appearance.bubbleWidth", K_APPEARANCE, "气泡宽度", "外观 › 气泡",
            zh = listOf("气泡宽度", "气泡太宽", "气泡太窄"),
            en = listOf("bubble width"),
            freq = 0
        ),
        spot(
            "appearance.perDoll", K_APPEARANCE, "逐个人偶外观", "外观 › 人偶侧默认值",
            zh = listOf("逐个人偶", "单独设置外观", "每个人偶不一样"),
            en = listOf("per doll", "per character"),
            freq = 2
        ),
        spot(
            "appearance.dialogAlpha", K_APPEARANCE, "弹窗透明度", "外观 › 弹窗",
            zh = listOf("弹窗透明度", "对话框透明"),
            en = listOf("dialog opacity", "dialog alpha"),
            value = { HomeState.dlgAlpha.toString() + "%" },
            freq = 2
        ),
        spot(
            "appearance.dialogBlur", K_APPEARANCE, "弹窗模糊", "外观 › 弹窗",
            zh = listOf("弹窗模糊", "背景模糊"),
            en = listOf("dialog blur"),
            freq = 0
        ),
        spot(
            "appearance.touchThrough", K_APPEARANCE, "点击穿透（桌宠）", "外观 › 图标与交互",
            zh = listOf("点击穿透", "穿透", "点到桌面图标"),
            en = listOf("touch through", "passthrough", "click through"),
            freq = 5
        )
    )

    /* ============================ 8. 我的资料（4 行） ============================ */

    private val PROFILE: List<SettingsSpot> = listOf(
        spot(
            "profile.name", K_PROFILE, "昵称", "我的资料 › 昵称",
            zh = listOf("昵称", "名字", "我叫什么"),
            en = listOf("nickname", "name"),
            freq = 5
        ),
        spot(
            "profile.signature", K_PROFILE, "个性签名", "我的资料 › 个性签名",
            zh = listOf("签名", "个性签名", "简介", "自我介绍"),
            en = listOf("signature", "bio", "about me"),
            freq = 2
        ),
        spot(
            "profile.avatar", K_PROFILE, "更换头像", "我的资料 › 头像",
            zh = listOf("头像", "换头像", "我的头像"),
            en = listOf("avatar", "profile picture"),
            freq = 5
        ),
        spot(
            "profile.avatarClear", K_PROFILE, "移除头像", "我的资料 › 头像",
            zh = listOf("移除头像", "删头像", "恢复默认头像"),
            en = listOf("remove avatar", "clear avatar"),
            freq = 0
        )
    )

    /* ============================ 9. 诊断中心（9 行） ============================ */

    private val DIAG: List<SettingsSpot> = listOf(
        spot(
            "diag.logs", K_DEV, "查看全部日志", "关于 › 诊断中心 › 诊断工具",
            zh = listOf("日志", "错误日志", "记录", "排查"),
            en = listOf("logs", "diagnostics", "error log"),
            freq = 5
        ),
        spot(
            "diag.export", K_DEV, "导出诊断包", "关于 › 诊断中心 › 诊断工具",
            zh = listOf("导出诊断", "zip", "反馈 bug", "诊断包"),
            en = listOf("export diagnostics", "zip", "bug report"),
            freq = 5
        ),
        spot(
            "diag.detailed", K_DEV, "记录详细运行日志", "关于 › 诊断中心 › 诊断设置",
            zh = listOf("详细日志", "verbose", "日志开关"),
            en = listOf("verbose logging", "detailed log"),
            freq = 2
        ),
        spot(
            "diag.baseRecord", K_DEV, "严重错误基础记录", "关于 › 诊断中心 › 诊断设置",
            zh = listOf("基础记录", "始终开启", "崩溃记录"),
            en = listOf("base record", "crash record"),
            value = { "始终开启" },
            freq = 0
        ),
        spot(
            "diag.contextBuffer", K_DEV, "错误前后关键事件", "关于 › 诊断中心 › 诊断设置",
            zh = listOf("上下文", "前后事件", "缓冲"),
            en = listOf("context", "buffer"),
            value = { "有界内存缓冲" },
            freq = 0
        ),
        spot(
            "diag.rotation", K_DEV, "自动轮转保留", "关于 › 诊断中心 › 诊断设置",
            zh = listOf("轮转", "保留多久", "日志上限"),
            en = listOf("rotation", "retention"),
            value = { "7 天 / 1 MB / 8 MB" },
            freq = 0
        ),
        spot(
            "diag.clear", K_DEV, "清理 15 天前的诊断记录", "关于 › 诊断中心 › 诊断设置",
            zh = listOf("清理日志", "删除记录", "清空诊断"),
            en = listOf("clear logs", "cleanup"),
            freq = 2
        ),
        spot(
            "diag.copy", K_DEV, "复制错误详情", "关于 › 诊断中心 › 错误详情",
            zh = listOf("复制错误", "复制堆栈"),
            en = listOf("copy error", "copy stack"),
            freq = 0
        ),
        spot(
            "diag.exportOne", K_DEV, "导出此错误", "关于 › 诊断中心 › 错误详情",
            zh = listOf("导出单个错误", "只导出这条"),
            en = listOf("export error", "export one"),
            freq = 0
        )
    )

    /* ============================ 10. 关于（2 行） ============================ */

    private val ABOUT: List<SettingsSpot> = listOf(
        spot(
            "about.diagnostics", K_ABOUT, "诊断中心", "关于 › 诊断中心",
            zh = listOf("诊断", "开发者选项", "错误日志", "排查问题"),
            en = listOf("diagnostics", "developer options"),
            freq = 5
        ),
        spot(
            "about.update", K_ABOUT, "检测更新", "关于 › 检测更新",
            zh = listOf("更新", "升级", "新版本", "检查版本"),
            en = listOf("update", "upgrade", "new version"),
            freq = 5
        )
    )

    /* ============================ 11. lamda 设备控制（6 行，B 类） ============================ */

    private val LAMDA: List<SettingsSpot> = listOf(
        spot(
            "lamda.download", K_PROVIDER, "下载服务器包（约 204 MB）", "lamda 设备控制 › 安装",
            zh = listOf("lamda", "服务器包", "下载运行环境", "204mb"),
            en = listOf("lamda", "download runtime", "server package"),
            freq = 5
        ),
        spot(
            "lamda.unpack", K_PROVIDER, "解包安装", "lamda 设备控制 › 安装",
            zh = listOf("解包", "安装运行环境", "安装服务"),
            en = listOf("unpack", "install runtime"),
            freq = 5
        ),
        spot(
            "lamda.start", K_PROVIDER, "启动服务", "lamda 设备控制 › 服务",
            zh = listOf("启动服务", "开启运行环境"),
            en = listOf("start service", "start runtime"),
            available = { HomeState.shizuku == ShizukuBridge.S_GRANTED },
            hint = "Shizuku 授权后可用",
            freq = 5
        ),
        spot(
            "lamda.stop", K_PROVIDER, "停止服务", "lamda 设备控制 › 服务",
            zh = listOf("停止服务", "关闭运行环境"),
            en = listOf("stop service", "stop runtime"),
            available = { HomeState.shizuku == ShizukuBridge.S_GRANTED },
            hint = "Shizuku 授权后可用",
            freq = 2
        ),
        spot(
            "lamda.keepAlive", K_PROVIDER, "自动保活", "lamda 设备控制 › 服务",
            zh = listOf("自动保活", "服务常驻", "掉线重连"),
            en = listOf("keepalive", "auto restart"),
            available = { HomeState.shizuku == ShizukuBridge.S_GRANTED },
            hint = "Shizuku 授权后可用",
            freq = 2
        ),
        spot(
            "lamda.remove", K_PROVIDER, "删除已安装的服务与下载的包", "lamda 设备控制 › 安装",
            zh = listOf("卸载运行环境", "删除服务器包", "清理"),
            en = listOf("uninstall runtime", "delete package"),
            freq = 0
        )
    )

    /* ============================ 汇总 ============================ */

    /**
     * 全部条目（11 页 / 65 行）。
     *
     * 【顺序有意义】同分时按此顺序稳定输出，不依赖哈希。
     */
    private val ALL_SPOTS: List<SettingsSpot> =
        PERMISSION + CHAT + MEM + VISION + WANDER + SLEEP +
                APPEARANCE + PROFILE + DIAG + ABOUT + LAMDA

    /** 全表（不可变）。 */
    @JvmStatic
    fun all(): List<SettingsSpot> = ALL_SPOTS

    /** rowId → 条目。接线时页面用 `SettingsSpots.find("appearance.monet")` 取标题。 */
    private val BY_ID: Map<String, SettingsSpot> = ALL_SPOTS.associateBy { it.rowId }

    /**
     * 按 rowId 取一条。
     *
     * 【返回 null 是刻意的】rowId 是**字符串字面量**，写错编译器管不到。
     * 返回 null 而**不抛异常**，是为了让「写错 id」表现为「那一行标题为空」——
     * 与「漏传 spot」同一个可见症状，用户/开发一眼能看出哪里少了字，
     * 而不是整页因为一个拼写错误崩掉。
     *
     * 查找是 O(1)（构造时建好索引），可以在组合中反复安全调用。
     */
    @JvmStatic
    fun find(rowId: String?): SettingsSpot? {
        if (rowId.isNullOrEmpty()) {
            return null
        }
        return BY_ID[rowId]
    }

    /**
     * 【自纠错核心】行标题的唯一派生规则。
     *
     * 优先级：`spot.title` → `fallback`（页面仍写死的旧文案）→ **空串**。
     * 最后那个空串是**故意**的：三步都拿不到标题时宁可显示空白，
     * 也不让「忘了登记 / 登记写错」静默地显示一句看起来正常的文案。
     *
     * 【为什么抽成纯函数】DhKit 的三个 Row 都要用它；抽出来才能被 JVM 单测钉住
     * （`SettingsSpotsTest.spotTitle_*`），否则这条规则只能靠"看代码"确认。
     */
    @JvmStatic
    fun spotTitle(spot: SettingsSpot?, fallback: String?): String {
        val fromSpot = spot?.title
        if (!fromSpot.isNullOrBlank()) {
            return fromSpot
        }
        return fallback.orEmpty()
    }

    /** 结果条数上限 —— 与 dsh-Go 一致，超过这个数量用户不会再往下看。 */
    private const val MAX_RESULTS = 20

    /* ============================ 搜索 ============================ */

    /**
     * 归一化单个词。
     *
     * 三条规则（沿用 dsh-Go，已验证）：**全角转半角 / 转小写 / 去空白**。
     * 效果：`ＡＰＩ Key`、`apikey`、`APIKEY` 归一为同一串。
     * 中文不受影响（汉字不是全角 ASCII 区）。
     */
    internal fun normalize(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            var c = ch
            // 全角 ASCII（！-～）→ 半角
            if (c.code in 0xFF01..0xFF5E) {
                c = (c.code - 0xFEE0).toChar()
            }
            // 全角空格 / 各类空白一律丢弃：用户搜「背景 透明」与「背景透明」应等价
            if (c.isWhitespace()) {
                continue
            }
            out.append(c.lowercaseChar())
        }
        return out.toString()
    }

    /** 按空白切词后逐个归一化，丢掉空词。 */
    internal fun terms(query: String): List<String> =
        query.split(' ', '　', '\t', '\r', '\n')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

    /**
     * 单条命中某词时的得分（取各字段的最高分，不累加同字段的多次命中）。
     *
     * 分档沿用 dsh-Go 的六档权重，只补了两处我们特有的：
     * · `keywordsEn` 命中给 45（介于「同义词包含 40」与「同义词全等 50」之间）——
     *   英文词是显式登记的检索词，可信度等同同义词；
     * · 路径命中给 20（用户可能记得「外观 › 弹窗」这样的位置）。
     */
    private fun scoreOne(spot: SettingsSpot, term: String): Int {
        val title = normalize(spot.title)
        val path = normalize(spot.path)
        var best = 0
        if (title == term) best = 100
        else if (title.startsWith(term)) best = 80
        else if (title.contains(term)) best = 60

        if (best < 60) {
            for (k in spot.keywordsZh) {
                val n = normalize(k)
                if (n == term) { best = maxOf(best, 50); break }
                if (n.contains(term)) best = maxOf(best, 40)
            }
        }
        if (best < 45) {
            for (k in spot.keywordsEn) {
                if (normalize(k).contains(term)) { best = maxOf(best, 45); break }
            }
        }
        if (best < 20 && path.contains(term)) best = 20
        return best
    }

    /**
     * 搜索。
     *
     * 【多词 AND】每个词都必须至少命中一个字段，否则该条不出现在结果里。
     *   得分 = 各词得分之和 + 频率加成（高频 5 / 中频 2 / 低频 0）。
     * 【空查询】返回空表 —— 搜索框为空时不该把 65 行全倒出来（那是索引页，不是搜索页）。
     */
    @JvmStatic
    fun search(query: String): List<SettingsSpot> {
        val ts = terms(query)
        if (ts.isEmpty()) {
            return emptyList()
        }
        val scored = ArrayList<Pair<SettingsSpot, Int>>()
        for (s in ALL_SPOTS) {
            var sum = 0
            var hitAll = true
            for (t in ts) {
                val sc = scoreOne(s, t)
                if (sc <= 0) {
                    hitAll = false
                    break
                }
                sum += sc
            }
            if (hitAll) {
                scored.add(s to (sum + s.freq))
            }
        }
        // 稳定排序：分数高的在前；同分看 freq 已并入分数，再同分则保持表内顺序。
        return scored.sortedByDescending { it.second }.take(MAX_RESULTS).map { it.first }
    }
}
