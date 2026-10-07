package com.dollhouse.app.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 【职责】把「去设置」这件事做成三级降级链，尽最大可能直达厂商私有页。
 *
 * 【三级降级】
 *   ① 直达私有页：ComponentName 显式指定（[VendorIntents.candidates] 的第一条）；
 *   ② 厂商备用页：同一目标的其余候选（多为安全中心列表页）；
 *   ③ 应用详情页：ACTION_APPLICATION_DETAILS_SETTINGS，所有 Android 都必有。
 *
 * 【为什么必须三级】厂商组件名随 ROM 版本漂移，硬编码单一组件必然在部分机型上
 *   ActivityNotFoundException 直接崩；每一级都先 resolveActivity() 探测再启动。
 *
 * 【硬约束】全走标准 startActivity；不做自动化点击、不做无障碍注入、不读控件树。
 *
 * 【坑】context 来自 Hilt 的 ApplicationContext，非 Activity —— 启动必须补
 *   FLAG_ACTIVITY_NEW_TASK，否则抛 AndroidRuntimeException。
 */
@Singleton
class VendorNavigator @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 打开厂商页；返回是否至少有一级成功启动。 */
    fun open(target: VendorIntents.Target): Boolean {
        val vendor = VendorProfile.current()
        val candidates = VendorIntents.candidates(vendor, target)
        for ((pkg, cls) in candidates) {
            if (startExplicit(pkg, cls)) {
                KeepAliveLog.d("vendor page opened: " + pkg + "/" + cls + " @" + vendor)
                return true
            }
        }
        KeepAliveLog.d("vendor page fallback to app details: " + vendor + " / " + target)
        return startAppDetails()
    }

    /**
     * 启动显式组件。
     * 【先探测后启动】resolveActivity 返回 null 说明该 ROM 上没有这个组件，
     *   直接跳过，不必靠 try/catch 接异常（异常在部分 ROM 上会顺带打日志）。
     */
    private fun startExplicit(pkg: String, cls: String): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(intent, 0) == null) {
                return false
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            KeepAliveLog.w("startExplicit failed: " + pkg + "/" + cls, t)
            false
        }
    }

    /** 第三级：应用详情页兜底。 */
    private fun startAppDetails(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            KeepAliveLog.w("startAppDetails failed", t)
            false
        }
    }
}
