package com.dollhouse.app.keepalive

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 【职责】保活模块的状态持久化：开关、退避步进、最近一次存活时间。
 *
 * 【交互】只读写 feiyu_keepalive 一个 prefs 文件（本模块自有）。
 *
 * 【为什么独立文件】保活状态与桌宠偏好生命周期不同，混在一个文件里会牵引出存量 PetPrefs 的改动；
 *   独立文件使本模块与存量 Java 完全解耦。
 *
 * 【为什么不再读 feiyu_pet】本模块保的是「软件进程」，与人偶的启停状态无关，
 *   因此不读也不写 feiyu_pet 的任何字段（原 user_stopped 只读逻辑已随保活语义变更移除）。
 */
@Singleton
class KeepAliveStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val PREFS_NAME = "feiyu_keepalive"
        const val KEY_ENABLED = "keepalive_enabled"
        const val KEY_BACKOFF_STEP = "keepalive_backoff_step"
        const val KEY_LAST_ALIVE_MS = "keepalive_last_alive_ms"
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 无感保活开关状态。默认关：用户显式开启后才注册调度。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** 连续失败步进。 */
    var backoffStep: Int
        get() = prefs.getInt(KEY_BACKOFF_STEP, 0)
        set(value) {
            prefs.edit().putInt(KEY_BACKOFF_STEP, value.coerceAtLeast(0)).apply()
        }

    /** 最近一次确认存活时间戳。 */
    var lastAliveMs: Long
        get() = prefs.getLong(KEY_LAST_ALIVE_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_ALIVE_MS, value).apply()
        }

    /** 记录一次成功存活，并清零退避步进。 */
    fun markAlive() {
        backoffStep = 0
        lastAliveMs = System.currentTimeMillis()
    }
}
