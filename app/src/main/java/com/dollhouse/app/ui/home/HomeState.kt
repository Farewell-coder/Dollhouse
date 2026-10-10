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
    /** 背景填充模式档位下标（[PetPrefs.BG_MODES]）。 */
    var bgMode by mutableStateOf(0)
    /** 背景毛玻璃半径（0~24 dp，0 = 不模糊）。 */
    var bgBlur by mutableStateOf(0)

    /* ---- 人偶卡片 ---- */
    /** 人偶比例显示值（0~100，10 档）。 */
    var petScale by mutableStateOf(50)

    /* ---- 记忆卡片 ---- */
    var memAuto by mutableStateOf(false)
    var memSave by mutableStateOf(false)
    var memMerge by mutableStateOf(false)
    /** 触发阈值档位下标（[PetPrefs.MEM_THRESHOLDS]）。 */
    var memIndex by mutableStateOf(1)

    /* ---- 操作卡片（手势响应） ---- */
    /** 四个手势各自选中的响应键集合（`single` / `double` / `triple` / `long`）。 */
    var gestureSingle by mutableStateOf(emptySet<String>())
    var gestureDouble by mutableStateOf(emptySet<String>())
    var gestureTriple by mutableStateOf(emptySet<String>())
    var gestureLong by mutableStateOf(emptySet<String>())
    /** 「暂离」时长（秒）。 */
    var awaySeconds by mutableStateOf(60)

    /** 按手势键取当前响应集合。 */
    fun gesture(key: String): Set<String> = when (key) {
        "single" -> gestureSingle
        "double" -> gestureDouble
        "triple" -> gestureTriple
        else -> gestureLong
    }

    /** 按手势键写响应集合。 */
    fun setGesture(key: String, set: Set<String>) {
        when (key) {
            "single" -> gestureSingle = set
            "double" -> gestureDouble = set
            "triple" -> gestureTriple = set
            else -> gestureLong = set
        }
    }
}