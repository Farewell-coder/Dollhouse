package com.dollhouse.app.ui.settings

import android.app.Activity
import android.view.View
import android.widget.FrameLayout

/**
 * 【已下线】原设置页「对话行为」独立页（思考程度 / 联网搜索 / 自动保存记忆 / 设备操作 / 记忆库入口）
 * 的整页 UI 及其可见入口、打开路由已按需求移除。
 *
 * 【保留原因】底层能力一处未删：开关的读写仍走 PetPrefs 的 thinkLevel / webSearchEnabled /
 * memAutoSave 等方法，聊天侧与记忆侧的既有调用点全部零改动。联网搜索已改为默认开启
 * （见 PetPrefs），不会因为入口消失而留下永久关闭的死锁。
 *
 * 【兼容】保留本文件与 build()，仅为避免遗留引用编译不过；不再渲染任何内容。
 */
object BehaviorPage {

    @JvmStatic
    fun build(act: Activity): View {
        return FrameLayout(act)
    }
}
