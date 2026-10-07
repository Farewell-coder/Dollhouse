package com.dollhouse.app;

import org.json.JSONArray;
import org.json.JSONObject;

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
final class LamdaTool implements ChatTool {

    /** 工具注册名：短、好记、和 shell / web_search 同风格。 */
    static final String NAME = "device";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String describe() {
        return "直接操作手机：点按、长按、滑动、返回、回主页、唤醒屏幕、打开或关闭应用、"
                + "输入文字、读写剪贴板，以及读取当前界面结构。"
                + "需要用「手」去点某个按钮、或需要知道屏幕上现在有什么时用这个工具；"
                + "只是查系统信息（进程、属性、包名）用 shell 工具。"
                + "读界面（observe）返回的是当前屏幕的层级与可点元素，是判断该点哪里的首选方式。";
    }

    @Override
    public JSONObject properties() {
        JSONObject props = new JSONObject();
        try {
            JSONObject action = new JSONObject();
            action.put("type", "string");
            action.put("description", "要做的事：observe 读界面 / tap 点按 / long_press 长按 / "
                    + "swipe 滑动 / back 返回 / home 回主页 / wake 唤醒屏幕 / "
                    + "open_app 启动应用 / close_app 结束应用 / input_text 输入文字 / "
                    + "clear_text 清空输入框 / get_clipboard 读剪贴板 / set_clipboard 写剪贴板");
            props.put("action", action);

            props.put("x", num("tap / long_press 的横坐标"));
            props.put("y", num("tap / long_press 的纵坐标"));
            props.put("duration", num("long_press 的按压时长，毫秒"));
            props.put("from_x", num("swipe 起点横坐标"));
            props.put("from_y", num("swipe 起点纵坐标"));
            props.put("to_x", num("swipe 终点横坐标"));
            props.put("to_y", num("swipe 终点纵坐标"));
            props.put("step", num("swipe 步长，越小越接近真实手指"));
            props.put("package_name", str("应用包名，例如 com.tencent.mm"));
            props.put("text", str("要输入的文字 / 要写入剪贴板的内容"));
        } catch (Throwable unused) {
        }
        return props;
    }

    @Override
    public JSONArray required() {
        JSONArray arr = new JSONArray();
        arr.put("action");
        return arr;
    }

    @Override
    public String execute(String arguments) {
        String raw = arguments == null ? "" : arguments.trim();
        if (raw.isEmpty()) {
            return "（没有给出要执行的动作）";
        }
        JSONObject obj;
        try {
            obj = new JSONObject(raw);
        } catch (Throwable notJson) {
            return "（参数不是合法的 JSON）";
        }
        if (!LamdaManager.alive()) {
            return "（lamda 服务没在运行，先去设置 → 聊天设置 → lamda 设备控制 启动它）";
        }
        String action = obj.optString("action", "").trim();
        if (action.isEmpty()) {
            return "（没有给出要执行的动作）";
        }
        try {
            // 无参数动作。
            if ("observe".equals(action)) {
                return LamdaManager.mcpCall("observeDevice", new JSONObject(), 30000L);
            }
            if ("home".equals(action)) {
                return LamdaManager.mcpCall("home", new JSONObject(), 10000L);
            }
            if ("back".equals(action)) {
                return LamdaManager.mcpCall("back", new JSONObject(), 10000L);
            }
            if ("wake".equals(action)) {
                return LamdaManager.mcpCall("wakeUp", new JSONObject(), 10000L);
            }
            if ("get_clipboard".equals(action)) {
                return LamdaManager.mcpCall("getClipboard", new JSONObject(), 10000L);
            }
            if ("clear_text".equals(action)) {
                return LamdaManager.mcpCall("imeClearText", new JSONObject(), 10000L);
            }
            // 单文本参数动作。
            if ("input_text".equals(action)) {
                String text = obj.optString("text", "");
                if (text.isEmpty()) {
                    return "（没有给出要输入的文字）";
                }
                JSONObject a = new JSONObject();
                a.put("text", text);
                return LamdaManager.mcpCall("imeInputText", a, 20000L);
            }
            if ("set_clipboard".equals(action)) {
                String text = obj.optString("text", "");
                if (text.isEmpty()) {
                    return "（没有给出要写入剪贴板的内容）";
                }
                JSONObject a = new JSONObject();
                a.put("text", text);
                return LamdaManager.mcpCall("setClipboard", a, 10000L);
            }
            // 包名动作。
            if ("open_app".equals(action) || "close_app".equals(action)) {
                String pkg = obj.optString("package_name", "").trim();
                if (pkg.isEmpty()) {
                    return "（没有给出应用包名）";
                }
                JSONObject a = new JSONObject();
                a.put("package_name", pkg);
                return LamdaManager.mcpCall(
                        "open_app".equals(action) ? "startApplication" : "stopApplication",
                        a, 20000L);
            }
            // 坐标动作。
            if ("tap".equals(action) || "long_press".equals(action)) {
                JSONObject a = new JSONObject();
                a.put("x", obj.optInt("x", -1));
                a.put("y", obj.optInt("y", -1));
                if (a.optInt("x", -1) < 0 || a.optInt("y", -1) < 0) {
                    return "（缺少坐标 x / y）";
                }
                if ("long_press".equals(action)) {
                    int d = obj.optInt("duration", 0);
                    if (d > 0) {
                        a.put("duration", d);
                    }
                    return LamdaManager.mcpCall("longPress", a, 20000L);
                }
                return LamdaManager.mcpCall("tap", a, 15000L);
            }
            if ("swipe".equals(action)) {
                JSONObject a = new JSONObject();
                a.put("from_x", obj.optInt("from_x", -1));
                a.put("from_y", obj.optInt("from_y", -1));
                a.put("to_x", obj.optInt("to_x", -1));
                a.put("to_y", obj.optInt("to_y", -1));
                if (a.optInt("from_x", -1) < 0 || a.optInt("from_y", -1) < 0
                        || a.optInt("to_x", -1) < 0 || a.optInt("to_y", -1) < 0) {
                    return "（缺少滑动坐标 from_x / from_y / to_x / to_y）";
                }
                int step = obj.optInt("step", 0);
                if (step > 0) {
                    a.put("step", step);
                }
                return LamdaManager.mcpCall("swipe", a, 20000L);
            }
        } catch (Throwable t) {
            return "（执行失败：" + t.getMessage() + "）";
        }
        return "（不认识的动作：" + action + "）";
    }

    /* ------------------------------ schema 小工具 ------------------------------ */

    private static JSONObject num(String desc) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "number");
        o.put("description", desc);
        return o;
    }

    private static JSONObject str(String desc) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "string");
        o.put("description", desc);
        return o;
    }
}