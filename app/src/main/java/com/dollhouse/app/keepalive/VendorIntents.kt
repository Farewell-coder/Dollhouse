package com.dollhouse.app.keepalive

/**
 * 【职责】厂商私有设置页的组件名候选表。
 *
 * 【为什么是列表而不是常量】厂商 ROM 每次大版本更新都可能改组件名 / 换包名，
 *   硬编码单个组件名在别家机器上会「点了没反应」；候选表 + 逐个 resolve 探测是唯一稳健做法。
 *
 * 【交互】由 [VendorNavigator] 按顺序 resolveActivity，第一个能解析的就用。
 */
object VendorIntents {

    /** 设置页大类。 */
    enum class Target { AUTOSTART, BATTERY }

    /**
     * 取某厂商某类页面的候选列表。
     * 返回顺序即优先级；全部解析失败时由 [VendorNavigator] 降级到应用详情页。
     */
    fun candidates(vendor: VendorProfile, target: Target): List<Pair<String, String>> {
        return when (vendor) {
            VendorProfile.HUAWEI -> when (target) {
                Target.AUTOSTART -> listOf(
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
                Target.BATTERY -> listOf(
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.power.ui.HwPowerManagerActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            }
            VendorProfile.XIAOMI -> when (target) {
                Target.AUTOSTART -> listOf(
                    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                Target.BATTERY -> listOf(
                    "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                    "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings"
                )
            }
            VendorProfile.OPPO -> when (target) {
                Target.AUTOSTART -> listOf(
                    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity"
                )
                Target.BATTERY -> listOf(
                    // 本机（ColorOS 14）实测可 resolve 的冻结后台应用开关页。
                    "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerAppsBgSetting",
                    "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerAppsBgSetting"
                )
            }
            VendorProfile.VIVO -> when (target) {
                Target.AUTOSTART -> listOf(
                    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    "com.iqoo.secure" to "com.iqoo.secure.safeguard.PurviewTabActivity"
                )
                Target.BATTERY -> listOf(
                    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity",
                    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            }
            VendorProfile.HONOR -> when (target) {
                Target.AUTOSTART -> listOf(
                    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
                Target.BATTERY -> listOf(
                    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity"
                )
            }
            VendorProfile.SAMSUNG -> when (target) {
                Target.AUTOSTART -> listOf(
                    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
                    "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
                Target.BATTERY -> listOf(
                    "com.samsung.android.lool" to "com.samsung.android.sm.ui.ram.RamActivity",
                    "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.ram.RamActivity"
                )
            }
            VendorProfile.OTHER -> emptyList()
        }
    }
}
