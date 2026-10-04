package com.dollhouse.app;

import org.json.JSONArray;
import org.json.JSONObject;

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
final class ShellTool implements ChatTool {

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String describe() {
        return "以 adb shell（系统 shell 用户）身份执行一条 Android/Linux 命令，可用于查询系统状态、"
                + "列出应用与进程、读取系统属性等。只在确实需要系统级信息时使用；"
                + "涉及卸载、清数据、改系统设置等破坏性操作前，必须先向主人说明再执行。";
    }

    @Override
    public JSONObject properties() {
        JSONObject props = new JSONObject();
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("type", "string");
            cmd.put("description", "要执行的命令，例如「pm list packages | wc -l」「getprop ro.product.model」");
            props.put("command", cmd);
        } catch (Throwable unused) {
        }
        return props;
    }

    @Override
    public JSONArray required() {
        JSONArray arr = new JSONArray();
        arr.put("command");
        return arr;
    }

    @Override
    public String execute(String arguments) {
        String raw = arguments == null ? "" : arguments.trim();
        if (raw.isEmpty()) {
            return "（没有给出要执行的命令）";
        }
        String command = "";
        try {
            JSONObject obj = new JSONObject(raw);
            // 【坑】合法 JSON 但缺 command / command 为空时，绝不能拿整段 JSON 当命令执行
            //   （否则会真的把 {"command":""} 这串字面量喂给 sh -c）。
            if (obj.has("command")) {
                command = obj.optString("command", "").trim();
                if (command.isEmpty()) {
                    return "（没有给出要执行的命令）";
                }
            } else {
                // 没有 command 字段：模型可能直接把裸命令当参数给了，按裸命令处理。
                command = raw.startsWith("{") ? "" : raw;
                if (command.isEmpty()) {
                    return "（没有给出要执行的命令）";
                }
            }
        } catch (Throwable notJson) {
            // 非 JSON：兼容模型直接传裸命令的写法。
            command = raw;
        }
        return ShizukuBridge.exec(command, 15000L);
    }
}