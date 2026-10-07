package com.dollhouse.app.keepalive

import android.os.Build
import java.util.Locale

/**
 * 【职责】按 Build.MANUFACTURER / BRAND 识别厂商，供 [VendorIntents] 选表。
 *
 * 【为什么用双字段】同一台机器上 MANUFACTURER 与 BRAND 常不一致
 *   （如 realme 的 MANUFACTURER=realme 但 BRAND=realme，而部分一加机型 MANUFACTURER=OnePlus、BRAND=oneplus）。
 *   两个字段合并成一个小写串再做包含判断，能摊平大小写与个别差异。
 *
 * 【坑】不要用 Build.MODEL 判断：机型名在厂商内部复用率很高，误判率高于品牌字段。
 */
enum class VendorProfile {
    HUAWEI, XIAOMI, OPPO, VIVO, HONOR, SAMSUNG, OTHER;

    companion object {
        /** 当前设备厂商。每次调用重新计算（Build 字段进程内不变，成本极低）。 */
        fun current(): VendorProfile {
            val raw = (Build.MANUFACTURER + "," + Build.BRAND).lowercase(Locale.ROOT)
            return when {
                raw.contains("huawei") -> HUAWEI
                raw.contains("xiaomi") || raw.contains("redmi") || raw.contains("poco") -> XIAOMI
                raw.contains("oppo") || raw.contains("realme") || raw.contains("oneplus") -> OPPO
                raw.contains("vivo") || raw.contains("iqoo") -> VIVO
                raw.contains("honor") -> HONOR
                raw.contains("samsung") -> SAMSUNG
                else -> OTHER
            }
        }
    }
}
