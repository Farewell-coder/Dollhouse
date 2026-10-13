package com.dollhouse.app.ui.doll

import com.dollhouse.app.MainActivity
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ChatHistoryStore
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.chat.ChatSessions
import com.dollhouse.app.ui.chat.ChatState
import com.dollhouse.app.ui.home.HomeUi

/**
 * 【职责】把「开场白」落进聊天记录（r13 新增）。
 *
 * 【为什么要单独一层】开场白有两种进入时机，逻辑必须一致：
 *   ① 用户手动点「重放开场白」（人格页）；
 *   ② 新会话建立、且会话为空时自动铺（见 ChatPanel.seedGreetingIfEmpty）。
 *   两处各写一遍必然分叉（一边落盘一边只上屏之类），所以收口到这里。
 *
 * 【为什么不直接写进 `history` 全局】聊天历史由 [ChatPanel] 持有，
 *   本类拿不到它。落盘走 [ChatHistoryStore.push] 的**显式 id 版**（数据层入口），
 *   界面重铺交给调用方（聊天页自己会 reload）。
 */
object DollGreeting {

    /**
     * 把开场白插进「这只人偶的会话」。
     *
     * 【幂等性】不幂等 —— 用户在人格页点「重放」就是要看它再说一遍。
     *   自动铺的那条路径由调用方自己保证「只在会话为空时调一次」。
     *
     * @return true = 写入成功
     */
    @JvmStatic
    fun insert(act: MainActivity, dollId: String): Boolean {
        val text = DollProfile.spec(act, dollId).greeting.trim()
        if (text.isEmpty()) {
            return false
        }
        return try {
            // 【同一只人偶 → 同一个会话】会话 id 由 dollId 确定性推导
            //   （[ChatSessions.ensureForDoll] 会顺便处理老数据迁移与新建）。
            val convId = ChatSessions.ensureForDoll(act, dollId)
            val arr = try {
                org.json.JSONArray(PetPrefs.convHistory(act, convId))
            } catch (ignored: Throwable) {
                org.json.JSONArray()
            }
            ChatHistoryStore.push(act, arr, "assistant", text, convId)
            // 若当前正开着的就是这条会话，顺手让聊天页重铺一次（否则要切出去再进来才看得到）。
            if (ChatSessions.currentId(act) == convId) {
                ChatState.bumpList()
            }
            true
        } catch (t: Throwable) {
            Logs.w("DollhousePersona", "开场白写入失败", t)
            HomeUi.showNotice(act, "没能写入开场白", "请稍后再试。")
            false
        }
    }
}