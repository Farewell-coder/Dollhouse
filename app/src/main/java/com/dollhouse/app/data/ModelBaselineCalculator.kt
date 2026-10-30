package com.dollhouse.app.data

import android.content.Context
import com.dollhouse.app.core.Logs

/**
 * 【职责】把「这只人偶的四个状态动作」量一遍，算出**模型级基准**与**动作级修正**并落盘。
 *
 * ## 为什么需要一个「计算器」而不是让各页面自己算
 *
 * 需求书 19.7 要求「所有动作必须共用同一个模型级 referenceBodyHeight」，
 * 而基准的来源有三处入口：导入助手、模型管理页、旧档首次进入。
 * **三处必须算得一模一样**，否则同一个模型在不同入口进出的身高会不同。
 * 把口径收口到这里，任何入口都只调一次 [recompute]。
 *
 * ## 计算步骤（对应 19.7.1 ~ 19.7.9）
 *
 * 1. 对每个自定义状态动作，用 [FrameScope.computeBaseline] 取**整组帧的本体框并集**
 *    （不是逐帧，19.7.7 禁止逐帧缩放造成抖动）；
 * 2. 本体高度占比 = 框高（相对画布，已经是 0..1）；
 * 3. 待机高度优先作基准（19.7.2）；缺失则取其余动作的**中位数**；
 * 4. 每个动作算 `scaleFix = 基准 / 本动作高度`，夹在 0.5~2.0（19.7.6）；
 * 5. **贴边动作不参与中位数、也不单独算修正**（19.7.8：它只是画面裁剪，
 *    必须用待机基准缩放后再裁，不能按可见半身重新估算身高）。
 *
 * ## 幂等与性能
 * - 只在**导入完成 / 用户手动要求 / 旧档首次进入**时调用；
 * - 已算过且 [ModelBaseline.Source] 不是 NONE 的直接返回，不重复解码全量帧图
 *   （19.7.4 禁止反复估算覆盖）。
 * - 解码走 [FrameScope.scanOne]（内部按采样解码），且逐帧处理不驻留全量位图。
 */
object ModelBaselineCalculator {

    private const val TAG = "DollhouseModelBaseline"

    /** 一次计算的结果，供界面展示「已统一 / 建议手动对齐」。 */
    data class Result(
        val ref: ModelBaseline.Ref,
        /** 各动作的高度占比与其修正值；key = actionId。 */
        val fixes: Map<String, ModelBaseline.Fix>,
        /** 是否存在需要人工核验的动作（19.7.6）。 */
        val needsReview: Boolean,
        /** 最大相对差异（用于 R-086 的三档判定）。 */
        val maxDiff: Float
    ) {
        val level: ModelBaseline.Level get() = ModelBaseline.levelOf(maxDiff)
    }

    /**
     * 重算并落盘。
     *
     * @param force true = 即使已有基准也重算（用户在大小检查页点了「恢复自动」）
     * @return 计算结果；拿不到任何有效动作高度时返回 null（**不写盘**，保持原状）
     */
    fun recompute(ctx: Context, dollId: String?, force: Boolean = false): Result? {
        if (dollId.isNullOrEmpty()) {
            return null
        }
        val existing = ModelBaseline.ref(ctx, dollId)
        if (!force && existing.isValid()) {
            // 已有可靠基准：不重算（19.7.4）。但把各动作的修正补齐 ——
            // 用户可能刚导入了一个新动作，那个动作还没有修正值。
            val fixes = ensureActionFixes(ctx, dollId, existing)
            return Result(existing, fixes, existing.needsReview, 0f)
        }

        val actions = DollAnim.all(ctx, dollId)

        // ① 量「自定义状态动作」的本体高度占比。
        //    只量四个固定壳 —— 额外动作（随机/手动）的尺寸由用户自己看着办，
        //    强行统一它们会破坏「挥手时手臂伸出去」这类自然表现（需求 5.5）。
        var idleFill: Float? = null
        val otherFills = ArrayList<Float>()
        val fills = LinkedHashMap<String, Float>()

        for (group in DollAnim.FIXED_GROUPS) {
            val slotId = DollAnim.slotIdOf(group) ?: continue
            var action: DollAnim.Action? = null
            for (a in actions) {
                if (a.id == slotId) {
                    action = a
                    break
                }
            }
            if (action == null) {
                continue
            }
            // 空壳（没有帧也没有入口资源）不参与 —— 它没有像素，量不出高度。
            if (action.frames.isEmpty() && action.assetPath.isEmpty()) {
                continue
            }
            // 只量 sprite 引擎的帧序列；Lottie 没有「本体框」概念（矢量动画，19.7 不适用）。
            if (action.engine != DollAnim.ENGINE_SPRITE || action.frames.isEmpty()) {
                continue
            }
            val box = try {
                FrameScope.computeBaseline(action.frames)
            } catch (t: Throwable) {
                Logs.w(TAG, "computeBaseline failed for " + slotId, t)
                null
            }
            if (box == null || !box.isValid()) {
                continue
            }
            val fill = box.height
            if (!fill.isFinite() || fill <= 0f) {
                continue
            }
            fills[slotId] = fill
            when (group) {
                DollAnim.GROUP_ALWAYS -> idleFill = fill
                // ② 贴边不参与中位数（19.7.8），也不单独算修正。
                DollAnim.GROUP_EDGE -> Unit
                else -> otherFills.add(fill)
            }
        }

        if (fills.isEmpty()) {
            // 一个都量不出来（全空壳 / 全 Lottie / 全坏图）：不写盘，保持「待校准」。
            return null
        }

        // ③ 算模型级基准。
        val ref = ModelBaseline.compute(idleFill, otherFills)
        if (!ref.isValid()) {
            return null
        }

        // ④ 逐动作算修正并落盘。
        val fixes = LinkedHashMap<String, ModelBaseline.Fix>()
        var maxDiff = 0f
        var needsReview = ref.needsReview
        for ((actionId, fill) in fills) {
            // 贴边动作：不单独修正（19.7.8 要求用待机基准缩放再裁）。
            //   显式写 1.0，清掉历史可能存在的错误修正值。
            if (actionId == DollAnim.SLOT_EDGE_ID) {
                ActionScaleFix.setFix(ctx, dollId, actionId, 1f)
                fixes[actionId] = ModelBaseline.Fix(1f, false)
                continue
            }
            val r = ModelBaseline.scaleFixFor(ref.referenceFill, fill)
            ActionScaleFix.setFix(ctx, dollId, actionId, r.fix)
            fixes[actionId] = r
            if (r.needsReview) {
                needsReview = true
            }
            val d = ModelBaseline.diffRatio(ref.referenceFill, fill)
            if (d > maxDiff) {
                maxDiff = d
            }
        }

        val finalRef = ref.copy(needsReview = needsReview)
        ModelBaseline.setRef(ctx, dollId, finalRef)
        Logs.i(
            TAG, "baseline doll=" + dollId + " fill=" + finalRef.referenceFill +
                " src=" + finalRef.source.key + " maxDiff=" + maxDiff +
                " review=" + needsReview
        )
        return Result(finalRef, fixes, needsReview, maxDiff)
    }

    /**
     * 已有基准时，只为**还没有修正值**的动作补算修正。
     *
     * 【为什么需要】用户先导入了待机（基准就此确定），后来又补了拖拽动作。
     *   这时不能重算基准（19.7.4），但新动作必须拿到它自己的修正值 ——
     *   否则它在播放时会按「自己的本体高 = 基准」错误缩放。
     */
    private fun ensureActionFixes(
        ctx: Context,
        dollId: String,
        ref: ModelBaseline.Ref
    ): Map<String, ModelBaseline.Fix> {
        val out = LinkedHashMap<String, ModelBaseline.Fix>()
        val actions = DollAnim.all(ctx, dollId)
        for (group in DollAnim.FIXED_GROUPS) {
            val slotId = DollAnim.slotIdOf(group) ?: continue
            if (slotId == DollAnim.SLOT_EDGE_ID) {
                // 贴边恒定 1.0（19.7.8）。
                ActionScaleFix.setFix(ctx, dollId, slotId, 1f)
                out[slotId] = ModelBaseline.Fix(1f, false)
                continue
            }
            var action: DollAnim.Action? = null
            for (a in actions) {
                if (a.id == slotId) {
                    action = a
                    break
                }
            }
            if (action == null || action.frames.isEmpty() ||
                action.engine != DollAnim.ENGINE_SPRITE
            ) {
                continue
            }
            val box = try {
                FrameScope.computeBaseline(action.frames)
            } catch (t: Throwable) {
                null
            }
            if (box == null || !box.isValid() || box.height <= 0f) {
                continue
            }
            val r = ModelBaseline.scaleFixFor(ref.referenceFill, box.height)
            ActionScaleFix.setFix(ctx, dollId, slotId, r.fix)
            out[slotId] = r
        }
        return out
    }

    /**
     * **【v0.1.5·T-04 旧档】**首次进入时确保这只人偶有基准。
     *
     * 与 [recompute] 的区别：本方法**幂等且安静** —— 已有基准直接返回，
     * 算不出来也不报错（界面自然显示「待校准」）。
     *
     * 【为什么需要它】旧档（v0.1.4 及以前）没有任何模型级基准。
     *   若不补，升级后所有人偶都继续走「逐动作各自缩放」的老路径 ——
     *   需求 R-083「默认配置切换动作 ≤2%」就永远不达标。
     *
     * 【为什么不在 Application.onCreate 里对所有人偶跑】
     *   解码全量帧图很贵，启动时批量做会让首次启动卡住（N-007 要求 2 秒内可操作）。
     *   改为**按需触发**：某只人偶第一次上桌 / 第一次进动作页时补一次。
     */
    fun ensure(ctx: Context, dollId: String?): ModelBaseline.Ref? {
        if (dollId.isNullOrEmpty()) {
            return null
        }
        val existing = ModelBaseline.ref(ctx, dollId)
        if (existing.isValid()) {
            return existing
        }
        return try {
            recompute(ctx, dollId)?.ref
        } catch (t: Throwable) {
            Logs.w(TAG, "ensure baseline failed for " + dollId, t)
            null
        }
    }
}