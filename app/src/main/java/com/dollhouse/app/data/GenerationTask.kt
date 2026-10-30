package com.dollhouse.app.data

import java.util.UUID
import org.json.JSONArray

/** 请求创建时冻结归属，异步回调不得重新读取“当前好友/当前会话”。 */
data class GenerationTask(
    val generationId: String = UUID.randomUUID().toString(),
    val personaId: String,
    val conversationId: String,
    val historySnapshot: JSONArray,
    val status: Status = Status.GENERATING
) {
    enum class Status { DRAFT, SENDING, SENT, GENERATING, COMPLETED, FAILED, INTERRUPTED, REGENERATING }
}
object GenerationTasks {
    private val active = LinkedHashMap<String, GenerationTask>()
    @JvmStatic @Synchronized fun put(task: GenerationTask) { active[task.generationId] = task }
    @JvmStatic @Synchronized fun get(id: String) = active[id]
    @JvmStatic @Synchronized fun finish(id: String) { active.remove(id) }
    @JvmStatic @Synchronized fun forConversation(id: String) = active.values.filter { it.conversationId == id }
}