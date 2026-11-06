package com.dollhouse.app.agent

import com.dollhouse.app.device.ShizukuBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】把 Shizuku 权限包成模型可调用的工具：让 AI 能像 adb shell 一样执行系统命令。
 *
 * 【入口】ChatToolRegistry.TOOLS 注册；模型返回 shell 调用时由工具循环分发到这里。
 *
 * 【交互】只经 ShizukuBridge（本类不直接碰 Shizuku API）；结果以纯文本回填给模型。
 *
 * 【扩展】想加别的系统能力（如按包名开关应用）请新建工具类，不要在 execute 里加分支。
 *
 * 【坑】execute 拿到的 arguments 可能不是合法 JSON、command 也可能为空；
 *        未授权 / 服务未运行时必须返回可读文案而不是抛异常 —— 否则会把整轮对话打断。
 */
class ShellTool : ChatTool {

    override fun name(): String {
        return "shell"
    }

    override fun describe(): String {
        return "以 adb shell（系统 shell 用户）身份执行一条 Android/Linux 命令，可用于查询系统状态、" +
                "列出应用与进程、读取系统属性等。只在确实需要系统级信息时使用；" +
                "涉及卸载、清数据、改系统设置等破坏性操作前，必须先向主人说明再执行。"
    }

    override fun properties(): JSONObject {
        val props = JSONObject()
        try {
            val cmd = JSONObject()
            cmd.put("type", "string")
            cmd.put("description", "要执行的命令，例如「pm list packages | wc -l」「getprop ro.product.model」")
            props.put("command", cmd)
        } catch (unused: Throwable) {
        }
        return props
    }

    override fun required(): JSONArray {
        val arr = JSONArray()
        arr.put("command")
        return arr
    }

    override fun execute(arguments: String?): String {
        // 【B1·P0 第三道门】开关没开就不执行。
        //   即使注册表被绕过（内部直接调不门控的 execute 重载），这里也兜得住。
        if (!ChatToolRegistry.isShellEnabled()) {
            return "（主人尚未开启 shell 工具，本次调用已被拒绝）"
        }
        val raw = if (arguments == null) "" else arguments.trim()
        if (raw.isEmpty()) {
            return "（没有给出要执行的命令）"
        }
        var command = ""
        try {
            val obj = JSONObject(raw)
            // 【坑】合法 JSON 但缺 command / command 为空时，绝不能拿整段 JSON 当命令执行
            //   （否则会真的把 {"command":""} 这串字面量喂给 sh -c）。
            if (obj.has("command")) {
                command = obj.optString("command", "").trim()
                if (command.isEmpty()) {
                    return "（没有给出要执行的命令）"
                }
            } else {
                // 没有 command 字段：模型可能直接把裸命令当参数给了，按裸命令处理。
                command = if (raw.startsWith("{")) "" else raw
                if (command.isEmpty()) {
                    return "（没有给出要执行的命令）"
                }
            }
        } catch (notJson: Throwable) {
            // 非 JSON：兼容模型直接传裸命令的写法。
            command = raw
        }
        // 【v0.2.6·安全沙盒】过了开关与授权两道门之后，命令仍可能是不可逆的：
        //   模型把「帮我清理一下」理解成 `pm clear` 或 `rm -rf` 并非假设，而是真实风险。
        //   这里在执行前分级：
        //   · DANGER → 不执行，回一句让它停手（工具执行发生在对话中间，此时弹窗确认
        //     会打断整轮回复，用户也多半没在看屏幕）；
        //   · WRITE  → 执行，但结果前面明确标出「这是一次会改变设备状态的命令」。
        val level = ShellRisk.level(command)
        if (level == ShellRisk.Level.DANGER) {
            com.dollhouse.app.core.Logs.w("DollhouseShell", "拒绝高风险命令：" + command)
            return ShellRisk.rejectText(command)
        }
        val out = ShizukuBridge.exec(command, 15000L)
        return if (level == ShellRisk.Level.WRITE) ShellRisk.writeNote(command) + out else out
    }
}
