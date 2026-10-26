package com.dollhouse.app.ui.feedback

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 消息语义只影响呈现，不把业务结果伪装成成功。 */
enum class MessageKind { NORMAL, SUCCESS, WARNING, ERROR }

/** 同一种消息的去重键；默认按完整文案区分。 */
data class AppMessage(
    val text: String,
    val kind: MessageKind = MessageKind.NORMAL,
    val actionLabel: String? = null,
    val dedupeKey: String = text,
    val duration: SnackbarDuration = if (actionLabel == null) {
        SnackbarDuration.Short
    } else {
        SnackbarDuration.Long
    }
)

/**
 * 页面作用域内的轻量消息队列。由 Compose 协程调用，不持有 Activity、不创建线程。
 * 正在展示的同一条消息不重复入队，过期相同消息可在之后正常再次展示。
 */
class AppMessageQueue(
    private val host: SnackbarHostState,
    private val deduper: MessageDeduper = MessageDeduper()
) {
    private val mutex = Mutex()

    suspend fun show(message: AppMessage, onAction: (suspend () -> Unit)? = null): SnackbarResult =
        mutex.withLock {
            if (!deduper.accept(message.dedupeKey)) return@withLock SnackbarResult.Dismissed
            try {
                val result = host.showSnackbar(
                    message = message.text,
                    actionLabel = message.actionLabel,
                    duration = message.duration
                )
                if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
                result
            } finally {
                deduper.clear()
            }
        }
}

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