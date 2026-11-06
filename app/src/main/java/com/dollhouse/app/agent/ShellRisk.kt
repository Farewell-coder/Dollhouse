package com.dollhouse.app.agent

/**
 * 【职责】对将要执行的 shell 命令做风险分级，供工具层在执行前拦截。
 *
 * ## 为什么必须做
 * `shell` 工具以 adb shell 身份执行模型生成的命令。此前只有两道门：
 * 用户开关 + Shizuku 授权。**过了这两道门，任何命令都会真的执行** ——
 * 包括 `pm uninstall`、`rm -rf`、`settings put`、写 `/system` 之类不可逆操作。
 * 而模型是会出错的：它可能把用户一句随口的话理解成「帮我清理一下」。
 *
 * ## 分级口径
 * | 级别 | 含义 | 处理 |
 * |---|---|---|
 * | [Level.READ] | 只读查询 | 直接执行 |
 * | [Level.WRITE] | 可逆修改（改设置、装/停应用） | 执行，但在结果里标明做了什么 |
 * | [Level.DANGER] | 不可逆或影响系统稳定 | **拒绝执行**，回一句说明让模型停手 |
 *
 * 【为什么默认拒绝而不是弹确认框】工具执行发生在模型回复的中间，
 *   此刻弹窗会打断整轮对话，而且用户往往没在看屏幕。对于明确不可逆的命令，
 *   最安全的做法是「不执行 + 告知」，让用户自己决定要不要动手。
 *
 * 【本类只做判定】不执行、不弹窗、不写日志，可直接单测。
 */
object ShellRisk {

    enum class Level { READ, WRITE, DANGER }

    /** 明确不可逆 / 破坏系统的命令片段（小写比较）。 */
    private val DANGER = arrayOf(
        "rm -rf", "rm -r /", "mkfs", "dd if=", "dd of=",
        "pm uninstall", "pm clear", "pm disable-user --user 0 com.android",
        "settings delete", "reboot", "reboot recovery", "shutdown",
        "wipe", "format", "fastboot", "flash",
        "chmod 777 /", "mount -o rw,remount", "setenforce 0",
        "> /system", ">/system", "su -c", "su 0",
    )

    /** 可逆但会改变设备状态的操作。 */
    private val WRITE = arrayOf(
        "settings put", "pm install", "pm enable", "pm disable",
        "am force-stop", "am start", "svc wifi", "svc data",
        "cmd wifi", "cmd battery", "input keyevent", "wm size", "wm density",
    )

    /**
     * 判定命令风险级别。
     *
     * 【为什么要归一化】模型可能写成 `PM  UNINSTALL`、`rm  -rf`、中间多空格、
     *   或用 `;` 串起多条命令。这里先压掉多余空白、转小写，再逐条比较。
     */
    @JvmStatic
    fun level(command: String?): Level {
        val raw = command ?: return Level.READ
        val c = normalize(raw)
        if (c.isEmpty()) {
            return Level.READ
        }
        for (d in DANGER) {
            if (c.contains(d)) {
                return Level.DANGER
            }
        }
        for (w in WRITE) {
            if (c.contains(w)) {
                return Level.WRITE
            }
        }
        return Level.READ
    }

    /** 给模型看的拒绝说明；不暴露内部规则细节，只说结论与建议。 */
    @JvmStatic
    fun rejectText(command: String?): String {
        return "（这条命令可能造成不可逆改动，已按安全策略拒绝执行：" +
            short(command) + "。请改用只读查询，或请主人自己在设备上确认后再操作）"
    }

    /** 写操作的结果前缀：让用户/模型都清楚这次改了设备状态。 */
    @JvmStatic
    fun writeNote(command: String?): String {
        return "（这是一次会改变设备状态的命令：" + short(command) + "）\n"
    }

    /** 截断过长的命令，避免把整段脚本回显到对话里。 */
    private fun short(command: String?): String {
        val c = (command ?: "").trim().replace("\n", " ")
        return if (c.length <= 60) c else c.substring(0, 60) + "…"
    }

    /** 压缩空白、统一小写，便于匹配。 */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var space = false
        for (ch in s) {
            if (ch == ' ' || ch == '\t' || ch == '\n') {
                if (!space) {
                    sb.append(' ')
                    space = true
                }
            } else {
                sb.append(ch.lowercaseChar())
                space = false
            }
        }
        return sb.toString().trim()
    }
}