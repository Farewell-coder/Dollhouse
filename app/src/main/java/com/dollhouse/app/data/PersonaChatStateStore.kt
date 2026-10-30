package com.dollhouse.app.data

import android.content.Context
import org.json.JSONObject

/** 好友在聊天中心的展示状态；与人格、身份、记忆分开保存。 */
object PersonaChatStateStore {
    data class State(val pinned: Boolean = false, val archived: Boolean = false, val muted: Boolean = false, val unread: Int = 0)
    private fun key(id: String) = "friend_state_$id"
    @JvmStatic fun get(ctx: Context, personaId: String): State {
        val o = runCatching { JSONObject(PetPrefs.get(ctx).getString(key(personaId), "{}")) }.getOrDefault(JSONObject())
        return State(o.optBoolean("pinned"), o.optBoolean("archived"), o.optBoolean("muted"), o.optInt("unread"))
    }
    @JvmStatic fun set(ctx: Context, personaId: String, state: State) {
        PetPrefs.get(ctx).edit().putString(key(personaId), JSONObject().put("pinned", state.pinned).put("archived", state.archived).put("muted", state.muted).put("unread", state.unread).toString()).apply()
    }
}