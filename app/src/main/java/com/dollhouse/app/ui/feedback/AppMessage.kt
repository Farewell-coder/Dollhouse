package com.dollhouse.app.ui.feedback

import android.content.Context
import android.widget.Toast

/** 与 UI 解耦的展示期去重状态机，可用普通 JVM 测试验证。 */
class MessageDeduper {
    private var showingKey: String? = null

    fun accept(key: String): Boolean {
        if (showingKey == key) return false
        showingKey = key
        return true
    }

    fun clear() {
        showingKey = null
    }
}

/** View/悬浮窗侧的简单状态消息；Snackbar 仍由各自 Compose 页面托管。 */
object AppToast {
    @JvmStatic
    @JvmOverloads
    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        if (text.isBlank()) return
        Toast.makeText(context.applicationContext, text,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}