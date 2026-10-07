package com.dollhouse.app.ai

import org.json.JSONObject

/**
 * 【职责】思考程度（输入行灯泡）的唯一落点：把 0~5 档位翻译成请求体字段 + system 提示。
 *
 * 【入口】ChatPanel.samplingParams / buildRequest。
 *
 * 【交互】档位值存在 PetPrefs.think_level；本类不读偏好，只按传入的 level 算字段。
 *
 * 【扩展】想换口径（比如只留 reasoning_effort）只改 parameters() 的 else-if 分支。
 *
 * 【坑】① 各家对「推理深度」的字段名不一样，多写一个字段就多一分被拒的风险，
 *        所以只有 2~5 档（加深）才往请求体里补，且只补一个字段；0 档与 1 档一律走提示词；
 *        ② 任何参数都只是「建议」，模型不支持就被忽略，不影响正文；
 *        ③ 提示词兜底是必须的 —— 中转常常把未知字段悄悄吃掉，那时只有提示词还起作用。
 */
object ThinkLevel {

    /** 【思考方式（内部）】只有「加深」档（2~5）才往请求体里补字段。 */
    @JvmStatic
    fun shouldInject(level: Int): Boolean {
        return level >= 2
    }

    /**
     * 往请求体里补思考相关字段。
     * 【坑】只写一组字段，不要同时塞 reasoning_effort + thinking + budget —— 多写一个就多一分
     *       被服务端拒的风险，一条出错整轮对话就废了。
     * 【坑】「不思考」档刻意一个字段都不发：enable_thinking 是 Qwen/vLLM 风格开关、不是 OpenAPI
     *       标准字段，本工程走非流式请求，Qwen 部分模型会直接回 400
     *       （"enable_thinking only support stream call"）；DeepSeek 官方也不认它（它用
     *       thinking:{type}）。发出去弊大于利，这一档只靠 prompt() 的提示词兜底。
     */
    @JvmStatic
    fun parameters(body: JSONObject?, level: Int) {
        if (body == null || level < 2) {
            return
        }
        try {
            // 2~5 档：低 / 中 / 高 / 顶级
            val effort = arrayOf("", "", "low", "medium", "high", "high")
            val e = if (level < effort.size) effort[level] else "high"
            body.put("reasoning_effort", e)
        } catch (unused: Throwable) {
        }
    }

    /**
     * 给 system 提示追加的一句约束，把档位语义说给模型听。
     * 【坑】自动档返回空串：那是「什么都不干预」的意思，加了提示反而等于强制干预。
     * 【坑】0 档（不思考）只靠这段提示词 —— 请求体里不发开关，见 parameters() 的说明。
     *       所以要说得比别档更硬一些。
     */
    @JvmStatic
    fun prompt(level: Int): String {
        return when (level) {
            0 -> "【思考方式】这次请直接回答，不要做任何推理、分析或自我检查，也不要罗列思考步骤，一句话给结论即可。"
            2 -> "【思考方式】先在心里简单想一下再回答，不用展开分析过程。"
            3 -> "【思考方式】回答前先在心里理清思路，拿出更周全一点的回答，但不要把推理过程写出来。"
            4 -> "【思考方式】回答前请认真推演一遍，考虑几种可能再作答，力求准确周全，但只输出结论，不要输出推理过程。"
            5 -> "【思考方式】这是一个需要慎重对待的问题。请在内部充分推演、反复检查后再作答，务必准确周全；只输出最终结果，不要输出推理过程。"
            else -> ""
        }
    }
}
