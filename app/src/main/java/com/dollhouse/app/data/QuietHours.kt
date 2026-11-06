package com.dollhouse.app.data

/**
 * 【职责】陪伴节奏的时间判定：夜间安静时段、免打扰窗口。
 *
 * ## 为什么需要
 * 现有「睡觉模式」是**低功耗休眠**（降帧率、点击唤醒），解决的是省电，
 * 不是「半夜别开口」。而人偶会因为随机事件、记忆抽取后的主动问候、保活通知
 * 在凌晨亮屏或出声 —— 用户的感受是「养了个半夜吵人的东西」。
 *
 * ## 本类只做时间判定
 * 输入「当前时间 / 起止 / 是否跨午夜」，输出「现在该不该安静」。
 * 不读偏好、不发通知、不改窗口行为；这样可以被各调用点复用，也能单独测试。
 *
 * ## 为什么用分钟而不是毫秒
 * 用户选的是「22:30 到 07:00」这种钟点；换算成分钟数比较直观，
 * 也避免时区 / 夏令时在毫秒层面的边界问题（窗口按本地钟点闭合）。
 */
object QuietHours {

    /** 默认夜间时段：22:00 – 07:00。 */
    const val DEFAULT_START = 22 * 60
    const val DEFAULT_END = 7 * 60

    /**
     * 当前是否处于安静时段。
     *
     * 【跨午夜】start > end 表示跨午夜（如 22:00 → 07:00），此时判定是
     *   「已过 start 或未到 end」；start < end 时是普通区间。
     *
     * @param nowMinutes 当天已过的分钟数（0–1439）
     */
    @JvmStatic
    fun inQuiet(nowMinutes: Int, start: Int, end: Int, enabled: Boolean = true): Boolean {
        if (!enabled) {
            return false
        }
        if (start == end) {
            // 起止相同 = 未设置有效窗口，按「不安静」处理，避免整天静音。
            return false
        }
        val n = ((nowMinutes % 1440) + 1440) % 1440
        val s = ((start % 1440) + 1440) % 1440
        val e = ((end % 1440) + 1440) % 1440
        return if (s < e) {
            n >= s && n < e
        } else {
            n >= s || n < e
        }
    }

    /** 把 "HH:mm" 解析成分钟数；失败返回 null。 */
    @JvmStatic
    fun parse(text: String?): Int? {
        if (text == null) {
            return null
        }
        val t = text.trim()
        val i = t.indexOf(':')
        if (i <= 0) {
            return null
        }
        val h = t.substring(0, i).toIntOrNull() ?: return null
        val m = t.substring(i + 1).toIntOrNull() ?: return null
        if (h < 0 || h > 23 || m < 0 || m > 59) {
            return null
        }
        return h * 60 + m
    }

    /** 分钟数 → "HH:mm"。 */
    @JvmStatic
    fun format(minutes: Int): String {
        val n = ((minutes % 1440) + 1440) % 1440
        val h = n / 60
        val m = n % 60
        return (if (h < 10) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
    }

    /** 给用户看的区间描述，例如「22:00 – 次日 07:00」。 */
    @JvmStatic
    fun describe(start: Int, end: Int): String {
        val s = format(start)
        val e = format(end)
        return if (start == end) {
            "未设置"
        } else if (start > end) {
            s + " – 次日 " + e
        } else {
            s + " – " + e
        }
    }
}