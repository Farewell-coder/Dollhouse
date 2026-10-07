package com.dollhouse.app.keepalive

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 【职责】保活相关系统白名单的只读查询。
 *
 * 【交互】只查询，不申请、不跳转（跳转统一走 [VendorNavigator]，避免职责重叠）。
 *
 * 【坑】isIgnoringBatteryOptimizations 自 API 23 可用；低于 23 一律视为「已豁免」
 *   （老系统没有该机制，不存在被限制的问题）。
 */
@Singleton
class KeepAlivePermissions @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 是否已加入电池优化白名单。查询异常一律当作「未加入」。 */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < 23) {
                return true
            }
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (t: Throwable) {
            KeepAliveLog.w("battery query failed", t)
            false
        }
    }
}
