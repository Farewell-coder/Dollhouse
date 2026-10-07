package com.dollhouse.app;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】工具注册表：集中登记全部可被模型调用的工具，并生成请求用的 tools schema。
 *
 * 【入口】ChatPanel.askModel → buildSchema()；ChatPanel.runTools → execute(name, args)。
 *
 * 【交互】不持有任何状态，纯查表；执行全在具体 ChatTool 实现里。
 *
 * 【扩展】新增工具 = 写好实现类，然后在 TOOLS 里加一行。
 *
 * 【坑】name() 必须唯一，重名时先注册的优先；未注册的名字一律回「没有名为 X 的工具。」。
 */
final class ChatToolRegistry {
    private static final List<ChatTool> TOOLS = new ArrayList<ChatTool>();
    /** 联网工具的注册名：它是否随请求下发由用户开关决定，执行入口则始终保留。 */
    private static final String NAME_WEB = "web_search";
    /** 记忆工具的注册名：同上，是否下发由加号面板里的「自动保存记忆」决定。 */
    private static final String NAME_REMEMBER = "remember";
    /** 系统命令工具的注册名：不下发时也不影响「未授权→不出现」的门控语义。 */
    private static final String NAME_SHELL = "shell";
    /** 设备控制工具的注册名：lamda 服务没跑时不下发，避免模型反复空调用。 */
    private static final String NAME_DEVICE = LamdaTool.NAME;
    static {
        TOOLS.add(new SearchTool());
        // Shizuku 系统命令工具：常驻注册，是否真正下发由 buildSchema 的授权门控决定。
        TOOLS.add(new ShellTool());
        // lamda 设备控制工具：同样常驻注册，门控在 buildSchema。
        TOOLS.add(new LamdaTool());
    }
    private ChatToolRegistry() {
    }

    /**
     * 注册需要 Context 的工具（记忆工具要拿偏好库）。
     * 【坑】幂等：ChatPanel 每次构造都会调，重复 add 会让 schema 里出现两份同名工具。
     */
    static void ensure(Context ctx) {
        if (ctx == null) {
            return;
        }
        for (int i = 0; i < TOOLS.size(); i++) {
            if (NAME_REMEMBER.equals(TOOLS.get(i).name())) {
                return;
            }
        }
        TOOLS.add(new MemoryTool(ctx));
    }
    /** 按名字执行；未命中返回兜底文案，不抛异常。 */
    static String execute(String name, String arguments) {
        for (int i = 0; i < TOOLS.size(); i++) {
            ChatTool chatTool = TOOLS.get(i);
            if (chatTool.name().equals(name)) {
                return chatTool.execute(arguments);
            }
        }
        return "没有名为 " + name + " 的工具。";
    }
    /**
     * 生成请求体的 tools 数组；两个全关时返回空数组（上层按 length>0 判断，不会下发空 tools）。
     * webSearch=false 时不下发联网工具；remember=false 时不下发记忆写入工具
     * （执行入口始终保留，所以「手动整理记忆」之类不依赖它）。
     * 【门控】shell 工具只在 Shizuku 已授权且服务运行时才下发 —— 没权限还把它摆给模型，
     *   模型会反复调用、每次只拿到「未获得 Shizuku 授权」，白白烧上下文。
     *   这里用静态判定（不依赖 Context），调用方无需改签名。
     */
    static JSONArray buildSchema(boolean webSearch, boolean remember) {
        boolean shellReady = ShizukuBridge.isReady();
        // 【门控】lamda 设备工具只在服务真的活着时才下发；没起就摆给模型只会烧上下文。
        //   探活走 2s 缓存，主线程调用不会卡。
        boolean deviceReady = LamdaManager.aliveCached();
        JSONArray jSONArray = new JSONArray();
        try {
            for (int i = 0; i < TOOLS.size(); i++) {
                ChatTool chatTool = TOOLS.get(i);
                if (!webSearch && NAME_WEB.equals(chatTool.name())) {
                    continue;
                }
                if (!remember && NAME_REMEMBER.equals(chatTool.name())) {
                    continue;
                }
                if (!shellReady && NAME_SHELL.equals(chatTool.name())) {
                    continue;
                }
                if (!deviceReady && NAME_DEVICE.equals(chatTool.name())) {
                    continue;
                }
                JSONObject jSONObject = new JSONObject();
                jSONObject.put("type", "object");
                jSONObject.put("properties", chatTool.properties());
                jSONObject.put("required", chatTool.required());
                JSONObject jSONObject2 = new JSONObject();
                jSONObject2.put("name", chatTool.name());
                jSONObject2.put("description", chatTool.describe());
                jSONObject2.put("parameters", jSONObject);
                JSONObject jSONObject3 = new JSONObject();
                jSONObject3.put("type", "function");
                jSONObject3.put("function", jSONObject2);
                jSONArray.put(jSONObject3);
            }
        } catch (Throwable unused) {
            return jSONArray;
        }
        return jSONArray;
    }
}
