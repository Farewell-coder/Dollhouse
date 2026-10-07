package com.dollhouse.app.ui.chat

import android.provider.Settings
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ai.DeepSeekClient
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.ui.settings.SettingsPage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】聊天设置区的行为：保存三个输入框、测试连通性、悬浮窗权限查询。
 * 【入口】HomeScreenBuilder 搭「保存设置 / 测试连接」按钮时把点击回调挂到这里。
 * 【交互】配置读写走 PetPrefs；测试请求走 DeepSeekClient，结果写回 MainActivity.testResult；
 *         保存后调 SettingsPage.onSaved 把活跃值回写进当前配置项。
 * 【坑】testResult 由 SettingsProfilePanel 从卡片尾部摘出、紧贴「测试连接」下方
 *       （靠 SettingsPage.TAG_TEST_RESULT 认领）。本类只管往里写文字，不要改它的位置。
 */
object ChatSettingsSection {

    /** 悬浮窗权限是否已授予。 */
    @JvmStatic
    fun hasOverlay(act: MainActivity): Boolean {
        return Settings.canDrawOverlays(act)
    }

    /**
     * 保存聊天设置。
     * 【坑】保持原有语义：输入框非空时以偏好里的旧值为准（输入即存已经写过一次），
     *       因此这里不要改成「直接存输入框内容」。
     */
    @JvmStatic
    fun saveSettings(act: MainActivity) {
        var key = act.keyInput!!.text.toString()
        if (!key.isEmpty()) {
            key = PetPrefs.apiKey(act)
        }
        PetPrefs.setApiKey(act, key)

        var model = act.modelInput!!.text.toString()
        if (!model.isEmpty()) {
            model = PetPrefs.model(act)
        }
        PetPrefs.setModel(act, model)

        var url = act.urlInput!!.text.toString()
        if (!url.isEmpty()) {
            url = PetPrefs.baseUrl(act)
        }
        PetPrefs.setBaseUrl(act, url)

        act.keyInput!!.setText(PetPrefs.apiKey(act))
        act.modelInput!!.setText(PetPrefs.model(act))
        act.urlInput!!.setText(PetPrefs.baseUrl(act))

        val stored = PetPrefs.apiKey(act)
        SettingsPage.onSaved(act)
    }

    /**
     * 测试连接：发一句最短的问候，把结果显示到 testResult。
     * 【交互】结果文本写在 MainActivity.testResult 上，位置由 SettingsProfilePanel 保证。
     */
    @JvmStatic
    fun testConnection(act: MainActivity) {
        SettingsPage.onSaved(act)
        if (!PetPrefs.hasKey(act)) {
            return
        }
        // 模型名是必填：不填服务端会回 400 Model name not specified，
        // 与其发一个注定失败的请求，不如在这里直接说清楚。
        if (PetPrefs.model(act).isEmpty()) {
            act.testResult!!.setText("\u2717 \u8fd8\u6ca1\u586b\u6a21\u578b\u540d\u79f0")
            return
        }
        act.testResult!!.setText("\u6b63\u5728\u6d4b\u8bd5\u8fde\u63a5\u2026")

        val messages = JSONArray()
        try {
            val msg = JSONObject()
            msg.put("role", "user")
            msg.put("content", "\u53ea\u56de\u590d\u4e24\u4e2a\u5b57\uff1a\u5728\u7684")
            messages.put(msg)
        } catch (ignored: Throwable) {
        }

        DeepSeekClient.chat(PetPrefs.apiKey(act), PetPrefs.baseUrl(act), PetPrefs.model(act), messages,
            object : DeepSeekClient.Callback {
                override fun onResult(reply: String?, error: String?) {
                    if (act.isFinishing) {
                        return
                    }
                    act.testResult!!.setText(
                        if (reply != null)
                            "\u2713 \u8fde\u63a5\u6b63\u5e38\uff0c\u5979\u56de\u4e86\uff1a" + reply
                        else
                            "\u2717 " + error
                    )
                }
            })
    }
}
