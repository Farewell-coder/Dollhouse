package com.dollhouse.app.agent

import com.dollhouse.app.device.LamdaManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】把 lamda 设备控制能力包成模型可调用的工具：让 AI 真的能点、能滑、能开应用。
 *
 * 【能力从哪来】不是本类自己实现的 —— 它把调用转发给 lamda 服务的内置 MCP 服务器（/mcp/），
 *        由 lamda 以 shell 身份代执行。本类只做参数整形与错误兜底，因此能力面能跟着 lamda 升级。
 *
 * 【入口】ChatToolRegistry.TOOLS 注册；模型返回 device 调用时由工具循环分发到这里。
 *
 * 【门控】与 shell 工具同款：只有 Shizuku 已授权 + lamda 服务在跑时才下发 schema，
 *        否则模型会反复调用、每次都只拿到「服务没起」，白烧上下文。
 *
 * 【坑】execute 拿到的 arguments 可能不是合法 JSON、action 可能不认识；
 *        一律返回可读文案，绝不抛异常 —— 否则会把整轮对话打断。
 */
class LamdaTool : ChatTool {

    /** 工具注册名：短、好记、和 shell / web_search 同风格。 */
    companion object {
        const val NAME = "device"

        /** 坐标参数上限：真实屏幕坐标远小于它，超过即视为模型乱填，直接拒绝而非静默钳制。 */
        private const val COORD_MAX = 100000

        /** 长按时长上限（毫秒），与 lamda 侧约定一致。 */
        private const val DURATION_MAX = 60000
    }

    override fun name(): String {
        return NAME
    }

    override fun describe(): String {
        return "直接操作手机：点按、长按、滑动、返回、回主页、唤醒屏幕、打开或关闭应用、" +
                "输入文字、读写剪贴板，以及读取当前界面结构。" +
                "需要用「手」去点某个按钮、或需要知道屏幕上现在有什么时用这个工具；" +
                "只是查系统信息（进程、属性、包名）用 shell 工具。" +
                "读界面（observe）返回的是当前屏幕的层级与可点元素，是判断该点哪里的首选方式。"
    }

    override fun properties(): JSONObject {
        val props = JSONObject()
        try {
            val action = JSONObject()
            action.put("type", "string")
            action.put("description", "要做的事：observe 读界面 / tap 点按 / long_press 长按 / " +
                    "swipe 滑动 / back 返回 / home 回主页 / wake 唤醒屏幕 / " +
                    "open_app 启动应用 / close_app 结束应用 / input_text 输入文字 / " +
                    "clear_text 清空输入框 / get_clipboard 读剪贴板 / set_clipboard 写剪贴板")
            props.put("action", action)

            props.put("x", num("tap / long_press 的横坐标"))
            props.put("y", num("tap / long_press 的纵坐标"))
            props.put("duration", num("long_press 的按压时长，毫秒"))
            props.put("from_x", num("swipe 起点横坐标"))
            props.put("from_y", num("swipe 起点纵坐标"))
            props.put("to_x", num("swipe 终点横坐标"))
            props.put("to_y", num("swipe 终点纵坐标"))
            props.put("step", num("swipe 步长，越小越接近真实手指"))
            props.put("package_name", str("应用包名，例如 com.tencent.mm"))
            props.put("text", str("要输入的文字 / 要写入剪贴板的内容"))
        } catch (unused: Throwable) {
        }
        return props
    }

    override fun required(): JSONArray {
        val arr = JSONArray()
        arr.put("action")
        return arr
    }

    override fun execute(arguments: String?): String {
        val raw = if (arguments == null) "" else arguments.trim()
        if (raw.isEmpty()) {
            return "（没有给出要执行的动作）"
        }
        val obj: JSONObject
        try {
            obj = JSONObject(raw)
        } catch (notJson: Throwable) {
            return "（参数不是合法的 JSON）"
        }
        if (!LamdaManager.alive()) {
            return "（设备通道当前不可用：lamda 服务未在运行或正在启动，暂时无法操作手机。" +
                    "不要重试本工具，直接用文字告诉用户当前无法操作手机，" +
                    "并建议用户在「设置 → 聊天设置 → lamda 设备控制」里启动 lamda 服务）"
        }
        val action = obj.optString("action", "").trim()
        if (action.isEmpty()) {
            return "（没有给出要执行的动作）"
        }
        try {
            // 无参数动作。
            when (action) {
                "observe" -> return LamdaManager.mcpCall("observeDevice", JSONObject(), 30000L)
                "home" -> return LamdaManager.mcpCall("home", JSONObject(), 10000L)
                "back" -> return LamdaManager.mcpCall("back", JSONObject(), 10000L)
                "wake" -> return LamdaManager.mcpCall("wakeUp", JSONObject(), 10000L)
                "get_clipboard" -> return LamdaManager.mcpCall("getClipboard", JSONObject(), 10000L)
                "clear_text" -> return LamdaManager.mcpCall("imeClearText", JSONObject(), 10000L)
            }
            // 单文本参数动作。
            when (action) {
                "input_text" -> {
                    val text = obj.optString("text", "")
                    if (text.isEmpty()) {
                        return "（缺少参数 text；正确用法：{\"action\":\"input_text\",\"text\":\"要输入的文字\"}）"
                    }
                    val a = JSONObject()
                    a.put("text", text)
                    return LamdaManager.mcpCall("imeInputText", a, 20000L)
                }
                "set_clipboard" -> {
                    val text = obj.optString("text", "")
                    if (text.isEmpty()) {
                        return "（缺少参数 text；正确用法：{\"action\":\"set_clipboard\",\"text\":\"要写入剪贴板的内容\"}）"
                    }
                    val a = JSONObject()
                    a.put("text", text)
                    return LamdaManager.mcpCall("setClipboard", a, 10000L)
                }
            }
            // 包名动作。
            if ("open_app" == action || "close_app" == action) {
                val pkg = obj.optString("package_name", "").trim()
                if (pkg.isEmpty()) {
                    return "（缺少参数 package_name；正确用法：{\"action\":\"open_app\",\"package_name\":\"com.tencent.mm\"}）"
                }
                val a = JSONObject()
                a.put("package_name", pkg)
                return LamdaManager.mcpCall(
                        if ("open_app" == action) "startApplication" else "stopApplication",
                        a, 20000L)
            }
            // 坐标动作。
            if ("tap" == action || "long_press" == action) {
                val tapUsage = "正确用法：{\"action\":\"" + action + "\",\"x\":540,\"y\":1200}" +
                        "；坐标未提供时请先 observe 获取界面可点元素坐标，不要凭空猜"
                val ex = checkIntArg(obj, "x", 0, COORD_MAX)
                if (ex != null) {
                    return "（" + ex + "；" + tapUsage + "）"
                }
                val ey = checkIntArg(obj, "y", 0, COORD_MAX)
                if (ey != null) {
                    return "（" + ey + "；" + tapUsage + "）"
                }
                val a = JSONObject()
                a.put("x", obj.getInt("x"))
                a.put("y", obj.getInt("y"))
                if ("long_press" == action) {
                    // duration 可选；一旦给出就必须是 0..60000 的整数，否则拒绝而不是静默纠正。
                    if (obj.has("duration") && !obj.isNull("duration")) {
                        val ed = checkIntArg(obj, "duration", 0, DURATION_MAX)
                        if (ed != null) {
                            return "（" + ed + "；正确用法：{\"action\":\"long_press\",\"x\":540,\"y\":1200,\"duration\":800}）"
                        }
                        val d = obj.getInt("duration")
                        if (d > 0) {
                            a.put("duration", d)
                        }
                    }
                    return LamdaManager.mcpCall("longPress", a, 20000L)
                }
                return LamdaManager.mcpCall("tap", a, 15000L)
            }
            if ("swipe" == action) {
                val swipeUsage = "正确用法：{\"action\":\"swipe\",\"from_x\":540,\"from_y\":1500," +
                        "\"to_x\":540,\"to_y\":500}"
                val e1 = checkIntArg(obj, "from_x", 0, COORD_MAX)
                if (e1 != null) {
                    return "（" + e1 + "；" + swipeUsage + "）"
                }
                val e2 = checkIntArg(obj, "from_y", 0, COORD_MAX)
                if (e2 != null) {
                    return "（" + e2 + "；" + swipeUsage + "）"
                }
                val e3 = checkIntArg(obj, "to_x", 0, COORD_MAX)
                if (e3 != null) {
                    return "（" + e3 + "；" + swipeUsage + "）"
                }
                val e4 = checkIntArg(obj, "to_y", 0, COORD_MAX)
                if (e4 != null) {
                    return "（" + e4 + "；" + swipeUsage + "）"
                }
                val a = JSONObject()
                a.put("from_x", obj.getInt("from_x"))
                a.put("from_y", obj.getInt("from_y"))
                a.put("to_x", obj.getInt("to_x"))
                a.put("to_y", obj.getInt("to_y"))
                // step 可选；它同属「optInt 静默截断」的缺陷，这里一并手写校验。
                if (obj.has("step") && !obj.isNull("step")) {
                    val es = checkIntArg(obj, "step", 0, COORD_MAX)
                    if (es != null) {
                        return "（" + es + "；" + swipeUsage + "）"
                    }
                    val s = obj.getInt("step")
                    if (s > 0) {
                        a.put("step", s)
                    }
                }
                return LamdaManager.mcpCall("swipe", a, 20000L)
            }
        } catch (t: Throwable) {
            // 【为什么兜底】t.message 可能为 null（例如某些 NPE），直接拼会得到 "null"；空值时回退类名。
            val msg = t.message
            return "（执行失败：" + (if (msg.isNullOrEmpty()) t.javaClass.simpleName else msg) + "）"
        }
        return "（不认识的动作：" + action + "；合法动作：observe / tap / long_press / swipe / back / home / " +
                "wake / open_app / close_app / input_text / clear_text / get_clipboard / set_clipboard）"
    }

    /**
     * 校验一个「必须是整数」的数值参数，不合规返回可纠错的中文说明（合规返回 null）。
     *
     * 【为什么不用 optInt】optInt(key, -1) 会把三种情况合并成同一个 -1：参数缺失、显式负数、
     *   类型不对（字符串 / 布尔）；而且它会静默截断 100.7 → 100、把字符串 "500" 也解析成功。
     *   这种「悄悄纠正」会让模型以为自己参数没问题，继续按错误理解往下走。这里逐项显式校验：
     *   存在 → 必须是 Number → 必须是整数（有小数就拒）→ 落在 [min, max] 内。
     */
    private fun checkIntArg(obj: JSONObject, key: String, min: Int, max: Int): String? {
        if (!obj.has(key) || obj.isNull(key)) {
            return "缺少参数 " + key
        }
        val v = obj.opt(key)
        if (v !is Number) {
            return "参数 " + key + " 必须是整数数字，当前类型是 " +
                    (if (v == null) "null" else v.javaClass.simpleName)
        }
        val d = v.toDouble()
        if (d.isNaN() || d.isInfinite() || d != Math.floor(d)) {
            return "参数 " + key + " 必须是整数，当前为 " + v
        }
        if (d < min || d > max) {
            return "参数 " + key + " 超出允许范围 [" + min + ".." + max + "]，当前为 " + v
        }
        return null
    }
}

/* ------------------------------ schema 小工具 ------------------------------ */

private fun num(desc: String): JSONObject {
    val o = JSONObject()
    o.put("type", "number")
    o.put("description", desc)
    return o
}

private fun str(desc: String): JSONObject {
    val o = JSONObject()
    o.put("type", "string")
    o.put("description", desc)
    return o
}
