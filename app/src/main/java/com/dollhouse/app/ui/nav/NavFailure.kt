package com.dollhouse.app.ui.nav

import android.content.Context
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.feedback.AppToast

/**
 * 【职责】导航失败的**唯一收口点**：既留痕、又告诉用户，绝不静默吞掉。
 *
 * ## 为什么必须单独抽出来（K-05）
 *
 * 改造前 `NavHost.push` 的失败路径是这样的：
 *
 * ```
 * val key = NavKeyCodec.toKey(legacy)
 * if (key == null) { Logs.w(TAG, "push failed: ...") ; return false }
 * ```
 *
 * 问题有两层：
 * ① 调用方（页面自己的 `open()`）**几乎都不检查返回值** —— `PageStack.push(...)`
 *    被当作过程调用写，没有 if。于是失败的表现就是「点了按钮，什么都没发生」：
 *    不崩、不报错、页面上也没有任何提示。用户只能报「按钮坏了」，而我们这边一片空白。
 * ② 日志默认是关的（`Logs` 的运行期开关默认 off），真实用户设备上**连那条 W 都没有**。
 *
 * 所以本类承担两件事，缺一不可：
 * · **对开发者**：写一条带「页面 key + 阶段 + 原因」的日志，排障时一眼定位；
 * · **对用户**：给一句他看得懂的话，并且明确「这是失败」而不是「已经成功」。
 *
 * ## 文案约束（需求 23 节）
 *
 * 面向用户的文案里**不得出现**内部字段名：`idle / drag / edge_in / manifest / fps /
 * baseline / scaleFix`，也不得出现 `key / route / null / exception` 这类实现词汇。
 * 具体 key 只进日志，不进 Toast —— 用户看不懂，也没有可操作性。
 * 提示语只描述「发生了什么 + 可以做什么」，不假装成功。
 */
object NavFailure {

    private const val TAG = "DollhouseNav"

    /** 失败发生的阶段。用于日志区分是哪一类失败，也便于测试断言。 */
    enum class Stage {
        /** 页面 key 认不出（`NavKeyCodec.toKey` 返回 null）。 */
        UNKNOWN_KEY,

        /** 找不到可用的宿主 Activity。 */
        NO_HOST,

        /** 该次调用的参数不成立（key 为空 / 构造器为空 / 目标层不存在）。 */
        BAD_ARGUMENT,

        /** 栈内没有目标层的登记板（宿主未安装 / 已被回收）。 */
        NOT_INSTALLED
    }

    /**
     * 上报一次导航失败。
     *
     * @param ctx   任意 Context（Toast 会取 applicationContext，不持有 Activity）
     * @param stage 失败阶段
     * @param legacy 出问题的历史字符串 key（只进日志；可为 null）
     * @param detail 补充说明（只进日志，不面向用户）
     * @param userText 面向用户的一句话；为空则按 [defaultUserText] 给默认文案
     */
    @JvmStatic
    @JvmOverloads
    fun report(
        ctx: Context?,
        stage: Stage,
        legacy: String?,
        detail: String? = null,
        userText: String? = null
    ) {
        // ① 留痕：key / 阶段 / 原因三段都在同一行，方便 grep。
        Logs.w(
            TAG,
            "nav failed: stage=" + stage.name +
                " key=" + (legacy ?: "<null>") +
                (if (detail.isNullOrEmpty()) "" else " detail=" + detail)
        )
        // ② 告诉用户：只描述现象与可做的动作。
        val text = userText ?: defaultUserText(stage)
        if (text.isNotEmpty()) {
            try {
                AppToast.show(requireNotNull(ctx), text)
            } catch (ignored: Throwable) {
                // 兜底：Toast 本身失败（如 Context 已失效）不能再抛，否则会把
                // 「打不开页面」升级成「崩溃」—— 比原问题更糟。
                Logs.w(TAG, "nav failed toast suppressed")
            }
        }
    }

    /** 各阶段的默认用户文案：说现象、说能做什么，不暴露内部词汇。 */
    @JvmStatic
    fun defaultUserText(stage: Stage): String = when (stage) {
        Stage.UNKNOWN_KEY -> "这个页面暂时打不开，请返回后再试一次"
        Stage.NO_HOST -> "页面没有打开，请退回到主界面重新进入"
        Stage.BAD_ARGUMENT -> "页面没有打开，缺少必要的信息"
        Stage.NOT_INSTALLED -> "页面没有打开，请重新进入应用后再试"
    }
}
