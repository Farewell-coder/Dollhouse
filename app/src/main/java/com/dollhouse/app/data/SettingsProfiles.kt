package com.dollhouse.app.data

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.dollhouse.app.core.Logs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】多套模型配置的数据层：配置数组读写、当前配置切换、输入即存。
 * 【入口】界面侧由 SettingsProfilePanel 调用；本类不持 View，只认 EditText 数组。
 * 【交互】配置数组存 SharedPreferences 的 cfg_profiles，当前配置名存 cfg_cur；
 *         三个活跃值 base_url / api_key / model 与选中配置保持同步。
 * 【坑】程序化改写输入框前必须置 mute，否则监听器会把中间值当用户输入写进偏好。
 */
object SettingsProfiles {
    private const val LOG_TAG = "Dollhouse"

    /** 配置数组的存储键。 */
    const val KEY_PROFILES = "cfg_profiles"
    /** 当前选中配置名的存储键。 */
    const val KEY_CURRENT = "cfg_cur"
    /** 首个默认配置的名字。 */
    const val DEFAULT_NAME = "默认配置"

    /** 静默标志：置位期间输入框的文本变化不写回偏好。 */
    private var mute = false

    /* ------------------------------ 配置数组 ------------------------------ */

    /**
     * 读出全部配置；为空或损坏时重建一份默认配置。
     */
    @JvmStatic
    fun loadProfiles(ctx: Context): JSONArray {
        val raw = readPref(ctx, KEY_PROFILES)
        if (raw.length > 0) {
            try {
                val arr = JSONArray(raw)
                if (arr.length() > 0) {
                    return arr
                }
            } catch (ignored: Throwable) {
                Logs.w(LOG_TAG, "ignored", ignored)
            }
        }
        val arr = JSONArray()
        arr.put(
            newProfile(
                DEFAULT_NAME, readPref(ctx, "base_url"),
                readPref(ctx, "api_key"), readPref(ctx, "model")
            )
        )
        writePref(ctx, KEY_PROFILES, arr.toString())
        if (readPref(ctx, KEY_CURRENT).length == 0) {
            writePref(ctx, KEY_CURRENT, DEFAULT_NAME)
        }
        return arr
    }

    /** 组装一条配置记录：n=名称，u=接口端点，k=密钥，m=模型名。 */
    @JvmStatic
    fun newProfile(name: String?, url: String?, key: String?, model: String?): JSONObject {
        val o = JSONObject()
        try {
            o.put("n", name)
            o.put("u", url)
            o.put("k", key)
            o.put("m", model)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        return o
    }

    /** 按名称找配置下标；找不到返回 -1。 */
    @JvmStatic
    fun indexOfName(arr: JSONArray, name: String?): Int {
        for (i in 0 until arr.length()) {
            val profile = arr.optJSONObject(i)
            if (profile != null && name != null && name == profile.optString("n", "")) {
                return i
            }
        }
        return -1
    }

    /* ------------------------------ 偏好读写 ------------------------------ */

    /** 读一个字符串偏好；异常一律退回空串。 */
    @JvmStatic
    fun readPref(ctx: Context, key: String?): String {
        try {
            val value = PetPrefs.get(ctx).getString(key, "")
            return if (value == null) "" else value.trim()
        } catch (ignored: Throwable) {
            return ""
        }
    }

    /** 写一个字符串偏好。 */
    @JvmStatic
    fun writePref(ctx: Context, key: String?, value: String?) {
        try {
            PetPrefs.get(ctx).edit().putString(key, value).apply()
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /* ------------------------------ 文本工具 ------------------------------ */

    /** 读取输入框内容，去首尾空白。 */
    @JvmStatic
    fun textOf(input: EditText?): String {
        val cs = if (input == null) null else input.text
        return if (cs == null) "" else cs.toString().trim()
    }

    /**
     * 把文本直接替换进输入框的 Editable，绕开 TextWatcher 的 afterTextChanged。
     * 【坑】不要用 EditText.setText()：在部分 ROM 上它的文本回调会晚于静默标志复位，
     *       导致把上一个配置的值当成用户输入写回新配置（切换配置看起来“没反应”）。
     */
    @JvmStatic
    fun replaceText(input: EditText?, text: String?) {
        if (input == null) {
            return
        }
        val safe = if (text == null) "" else text
        val editable = input.text
        editable.replace(0, editable.length, safe)
        input.setSelection(safe.length)
    }

    /* ------------------------------ 同步与切换 ------------------------------ */

    /** 静默标志开关。界面侧需要程序化改写输入框时，必须先 mute 再改，最后在 finally 里 unmute。 */
    @JvmStatic
    fun setMute(value: Boolean) {
        mute = value
    }

    /** 把输入框当前内容写回「当前配置」那一项。 */
    @JvmStatic
    fun syncInputs(ctx: Context, profiles: JSONArray, inputs: Array<EditText?>?) {
        if (inputs == null) {
            return
        }
        val index = indexOfName(profiles, readPref(ctx, KEY_CURRENT))
        val profile = if (index < 0) null else profiles.optJSONObject(index)
        if (profile == null) {
            return
        }
        try {
            profile.put("u", textOf(inputs[0]))
            profile.put("k", textOf(inputs[1]))
            profile.put("m", textOf(inputs[2]))
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        writePref(ctx, KEY_PROFILES, profiles.toString())
    }

    /** 把偏好里的活跃值回写进当前配置项，避免保存被规范化后两边不一致。 */
    @JvmStatic
    fun syncFromPrefs(ctx: Context) {
        val profiles = loadProfiles(ctx)
        val index = indexOfName(profiles, readPref(ctx, KEY_CURRENT))
        val profile = if (index < 0) null else profiles.optJSONObject(index)
        if (profile == null) {
            return
        }
        try {
            profile.put("u", readPref(ctx, "base_url"))
            profile.put("k", readPref(ctx, "api_key"))
            profile.put("m", readPref(ctx, "model"))
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        writePref(ctx, KEY_PROFILES, profiles.toString())
    }

    /**
     * 切到指定配置：先把界面上的值存回旧配置，再把新配置填进输入框并写入活跃值。
     * 【坑】填值走 replaceText（直接改 Editable），不用 setText，原因见 replaceText 注释。
     */
    @JvmStatic
    fun switchTo(ctx: Context, name: String?, inputs: Array<EditText?>?, onSwitched: Runnable?) {
        var profiles = loadProfiles(ctx)
        syncInputs(ctx, profiles, inputs)
        profiles = loadProfiles(ctx)
        val index = indexOfName(profiles, name)
        if (index < 0) {
            return
        }
        val profile = profiles.optJSONObject(index) ?: return
        writePref(ctx, KEY_CURRENT, name)
        setMute(true)
        try {
            // 【坑】inputs 可能是 null（聊天面板的配置浮层里没有设置页那三个输入框）。
            // 这里必须判空 —— 原来无条件读 inputs[0]，在浮层里点一下就 NPE 崩掉。
            if (inputs != null) {
                replaceText(inputs[0], profile.optString("u", ""))
                replaceText(inputs[1], profile.optString("k", ""))
                replaceText(inputs[2], profile.optString("m", ""))
            }
            writePref(ctx, "base_url", profile.optString("u", ""))
            writePref(ctx, "api_key", profile.optString("k", ""))
            writePref(ctx, "model", profile.optString("m", ""))
        } finally {
            setMute(false)
        }
        onSwitched?.run()
    }

    /**
     * 只改某套配置里的模型名。
     * 【交互】给聊天面板的「配置 → 展开模型清单 → 选一个」用；若改的正是当前配置，
     *        顺手把活跃值 model 也更新，免得下次发请求还用旧模型。
     */
    @JvmStatic
    fun setProfileModel(ctx: Context, name: String?, model: String?) {
        val profiles = loadProfiles(ctx)
        val index = indexOfName(profiles, name)
        val profile = if (index < 0) null else profiles.optJSONObject(index)
        if (profile == null) {
            return
        }
        try {
            profile.put("m", if (model == null) "" else model)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        writePref(ctx, KEY_PROFILES, profiles.toString())
        if (name == readPref(ctx, KEY_CURRENT)) {
            writePref(ctx, "model", if (model == null) "" else model)
        }
    }

    /** 打开设置页时把当前配置已存的值填进输入框（静默，不触发输入即存）。 */
    @JvmStatic
    fun fillFromCurrent(ctx: Context, inputs: Array<EditText?>) {
        val profiles = loadProfiles(ctx)
        val index = indexOfName(profiles, readPref(ctx, KEY_CURRENT))
        val profile = if (index < 0) null else profiles.optJSONObject(index)
        if (profile == null) {
            return
        }
        setMute(true)
        try {
            replaceText(inputs[0], profile.optString("u", ""))
            replaceText(inputs[1], profile.optString("k", ""))
            replaceText(inputs[2], profile.optString("m", ""))
        } finally {
            setMute(false)
        }
    }

    /** 输入即存：值一变就写回偏好与当前配置项，不需要「保存」按钮。 */
    @JvmStatic
    fun bindAutoSave(ctx: Context, input: EditText, key: String?, inputs: Array<EditText?>?) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                if (mute) {
                    return
                }
                writePref(ctx, key, if (s == null) "" else s.toString().trim())
                syncInputs(ctx, loadProfiles(ctx), inputs)
            }
        })
    }
}
