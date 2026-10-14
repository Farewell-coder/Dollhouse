package com.dollhouse.app.update

import android.content.Context
import android.content.SharedPreferences

/**
 * 【职责】更新功能的状态持久化：上次检查时间、所选通道、被忽略的版本。
 *
 * 【为什么独立文件而不并进 PetPrefs】PetPrefs 是「桌宠 / 聊天相关」偏好的中枢，
 *   更新检查是独立子系统，混进去会让本就 900+ 行的 PetPrefs 继续膨胀。
 *   但仍**复用同一个 SharedPreferences 名**（[NAME]，同工程惯例），
 *   不新增存储文件，升级时也不会多出一份偏好数据。
 *
 * 【为什么存时间戳而不是「上次检查日期」】时间戳能做「距今是否超过 24h」的
 *   精确判断，跨时区 / 系统时间调整时也更稳。
 */
object UpdatePrefs {

    /** 与 PetPrefs 同一份偏好文件（feiyu_pet），只是键名独立。 */
    private const val NAME = "feiyu_pet"

    private const val KEY_LAST_CHECK = "update_last_check_ms"
    private const val KEY_CHANNEL = "update_channel"
    private const val KEY_SKIP_VERSION = "update_skip_version"
    private const val KEY_AUTO_CHECK = "update_auto_check"

    /** 自动检查间隔：24 小时（需求口径）。 */
    const val INTERVAL_MS = 24L * 60L * 60L * 1000L

    /** 手动检查防抖：60 秒（需求口径）。 */
    const val MANUAL_DEBOUNCE_MS = 60L * 1000L

    private fun sp(context: Context): SharedPreferences {
        return context.getSharedPreferences(NAME, 0)
    }

    /* ==================== 通道 ==================== */

    fun channel(context: Context): UpdateChannel {
        return UpdateChannel.fromKey(sp(context).getString(KEY_CHANNEL, null))
    }

    fun setChannel(context: Context, channel: UpdateChannel) {
        sp(context).edit().putString(KEY_CHANNEL, channel.name).apply()
    }

    /* ==================== 自动检查开关 ==================== */

    /** 自动检查默认开 —— 更新提醒本身就是「用户不主动想」时才最有价值的功能。 */
    fun autoCheck(context: Context): Boolean {
        return sp(context).getBoolean(KEY_AUTO_CHECK, true)
    }

    fun setAutoCheck(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_CHECK, on).apply()
    }

    /* ==================== 节流 ==================== */

    fun lastCheckMs(context: Context): Long {
        return sp(context).getLong(KEY_LAST_CHECK, 0L)
    }

    fun setLastCheckMs(context: Context, ms: Long) {
        sp(context).edit().putLong(KEY_LAST_CHECK, ms).apply()
    }

    /** 距上次检查是否已超过 24h。 */
    fun autoCheckDue(context: Context, nowMs: Long): Boolean {
        val last = lastCheckMs(context)
        if (last <= 0L) {
            return true
        }
        // 系统时间被往回调（last > now）时也放行，避免永久不再检查。
        if (last > nowMs) {
            return true
        }
        return nowMs - last >= INTERVAL_MS
    }

    /* ==================== 忽略此版本 ==================== */

    /** 被用户「不再提醒」的版本号；空串表示没有。 */
    fun skippedVersion(context: Context): String {
        return sp(context).getString(KEY_SKIP_VERSION, "").orEmpty()
    }

    fun setSkippedVersion(context: Context, version: String) {
        sp(context).edit().putString(KEY_SKIP_VERSION, version).apply()
    }

    /** 清掉忽略记录（用户重新开启提醒 / 检测到更高版本时用）。 */
    fun clearSkipped(context: Context) {
        sp(context).edit().remove(KEY_SKIP_VERSION).apply()
    }
}