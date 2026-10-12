package com.dollhouse.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 【职责】首页 / 设置页的 Compose 状态容器。
 *
 * 【为什么需要它】原 View 版的状态是「长在控件上」的：`HomeUi.syncPerm` 按 tag 找到
 *   那一行 TextView 再改字。Compose 侧没有「控件句柄」可找，改为「逻辑层写 state、
 *   界面层读 state」——本对象就是那条唯一通道。
 *
 * 【谁写】[HomeUi] 的全部 `sync*` 与点击落地函数（纯逻辑侧，一行 View 都不碰）。
 * 【谁读】[HomeScreen] 与 [SettingsScreen] 的 Composable。
 * 【线程】只在主线程读写（与旧 `find` + `setText` 同约束）。
 */
object HomeState {

    /* ---- 首页 ---- */
    /** 桌宠服务是否在运行（决定主按钮文案「启动人偶 / 关闭人偶」）。 */
    var running by mutableStateOf(false)
    /** 「打开聊天」入口是否可用（已启用供应商 + 有可用聊天模型）。 */
    var chatAvailable by mutableStateOf(false)
    /**
     * 【r12】是否至少启用了一只人偶。
     *
     * 【为什么要这个状态】需求定案「没有选择使用人偶的情况下 就不要出现人偶」——
     *   但首页主按钮原来只看 [running]：一只都没选时按钮仍写着「启动人偶」，
     *   点了才弹「还没有选择人偶」。文案与真实状态不符，用户无从预判。
     *   有了这个状态，按钮可以直接写「选择人偶」，点一下直达自定义人偶页。
     */
    var hasDoll by mutableStateOf(false)

    /* ---- 权限卡片 ---- */
    var overlay by mutableStateOf(false)
    var notif by mutableStateOf(false)
    var battery by mutableStateOf(false)
    /** Shizuku 四态：[ShizukuBridge.S_*]。 */
    var shizuku by mutableStateOf(0)
    /** 无感保活总开关。 */
    var keepAlive by mutableStateOf(false)
    /** 快捷设置磁贴。 */
    var tile by mutableStateOf(false)
    /** 隐藏后台卡片。 */
    var hideRecents by mutableStateOf(false)

    /* ---- 外观卡片 ---- */
    /** 主题模式档位下标（[ThemeManager.MODE_NAMES]）。 */
    var themeMode by mutableStateOf(0)
    /** 莫奈主题色开关。 */
    var monet by mutableStateOf(false)
    /** 聊天背景是否已设置。 */
    var bgSet by mutableStateOf(false)
    /** 背景图透明度（0~100）。 */
    var bgAlpha by mutableStateOf(50)
    /** 卡片透明度（0~100）。与背景图透明度是两个独立概念，互不联动。 */
    var bgCardAlpha by mutableStateOf(100)
    /** 背景可读性遮罩强度（0~100）。 */
    var bgScrim by mutableStateOf(100)
    /** 【r13】弹窗 / 面板磨砂程度（0~100）。0 = 无虚化，100 = 虚化最强。 */
    var bgFrost by mutableStateOf(60)
    /** 【r13】自定义字体颜色（0 = 跟随主题）。 */
    var fontColor by mutableStateOf(0)
    /** 背景填充模式档位下标（[PetPrefs.BG_MODES]）。 */
    var bgMode by mutableStateOf(0)
    /** 背景毛玻璃半径（0~24 dp，0 = 不模糊）。 */
    var bgBlur by mutableStateOf(0)

    // 【r10 已移除「人偶比例」】比例改到「每只人偶」的编辑页（活动 → 比例大小），
    //   本对象里那份全局副本再无读者，删掉以免日后有人误改一处、桌宠不响应。
    // 【r13·记忆系统重构】原「记忆卡片」的四项状态（memAuto / memSave / memMerge / memIndex）
    //   已全部删除：旧记忆系统下线后它们没有读者，新的记忆策略由「记忆库设置」页直接读写 PetPrefs。
    // 【r10 已移除「操作卡片」的全部状态】手势响应与暂离时长改为「按人偶」存在
    //   [com.dollhouse.app.data.DollProfile] 里，设置页不再有对应行；
    //   这里保留的四个集合与 `gesture()` / `setGesture()` 已成死代码，一并删除。
}