package com.dollhouse.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】一次模型工具调用的抽象：名字、给模型看的说明与参数 schema、以及执行逻辑。
 *
 * 【入口】由 ChatToolRegistry 注册；ChatPanel.runTools 按名字查表分发。
 *
 * 【交互】执行结果以纯文本回填给模型（role=tool），不改动会话状态、不碰 View。
 *
 * 【扩展】想加一个联网/本地能力 = 新建一个实现类 + 在 ChatToolRegistry.TOOLS 里注册一行。
 *
 * 【坑】execute 拿到的是模型给的 arguments 原文（可能不是合法 JSON，甚至为空串），
 *        实现类必须自行容错，不要往外抛异常；schema 里 properties/required 会被直接塞进请求体。
 */
interface ChatTool {
    /** 工具名，模型按它回调；必须唯一。 */
    String name();
    /** 给模型看的能力说明，写清什么时候该用它。 */
    String describe();
    /** 参数 schema 的 properties 段。 */
    JSONObject properties();
    /** 参数 schema 的 required 段。 */
    JSONArray required();
    /** 执行并返回回填文本；arguments 是模型原始入参。 */
    String execute(String arguments);
}
