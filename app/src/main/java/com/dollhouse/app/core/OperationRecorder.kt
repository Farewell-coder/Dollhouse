package com.dollhouse.app.core

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 有界、显式启停的操作现场记录；不记录坐标、输入文本、聊天正文或密钥。 */
object OperationRecorder {
    private val recordingState = mutableStateOf(false)
    var recording: Boolean
        get() = recordingState.value
        private set(value) { recordingState.value = value }
    private var started = 0L
    private val events = ArrayDeque<String>()
    @JvmStatic fun event(type: String, detail: String) {
        if (!recording) return
        val text = "${System.currentTimeMillis() - started}ms [$type] ${DiagnosticsFormat.redact(detail).take(700)}"
        synchronized(events) { events.addLast(text); while (events.size > 1200) events.removeFirst() }
    }
    @JvmStatic fun toggle(ctx: Context) { if (recording) stop(ctx) else start() }
    private fun start() { synchronized(events) { events.clear() }; started = System.currentTimeMillis(); recording = true; event("Session", "开始记录") }
    private fun stop(ctx: Context) {
        event("Session", "结束记录")
        val copy = synchronized(events) { events.toList() }
        recording = false
        val duration = (System.currentTimeMillis() - started) / 1000
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(started))
        DiagnosticsStore.record(ctx, DiagnosticsStore.Severity.WARNING, "操作记录",
            "$stamp · ${duration}秒 · ${copy.size}条\n${copy.joinToString("\n")}",
            category = "operation_session")
    }
}
