package com.dollhouse.app.data

import android.content.Context

/**
 * 【职责】**模型级基准身高** —— 让同一只人偶的**所有动作**在屏幕上看起来一样高。
 *
 * ## 要解决的问题（需求书 R-080 ~ R-086 / v2.1·19.7）
 *
 * 现状（改造前）：
 * - [ActionViewport.measure] 的尺子是 `spec.bodyHeight`（窗口高），**每个动作**各自拿自己的
 *   baseline 去算缩放 —— 于是「同一只人偶，待机 400px 高、拖拽 380px 高」是常态。
 * - 贴边动作如果只画了露出的**半身**，它的 baseline 高度只有全身的 60%，
 *   按它自己的 baseline 放大会把人物**整体放大 1.67 倍** —— 用户看到「一贴边人就变大」。
 *
 * 修法（需求书 19.7 第 1~4 条）：
 * 1. 取一个**模型级** `referenceBodyHeight`，全模型共用；
 * 2. 优先用**待机**动作的本体高度（19.7.2）；
 * 3. 待机缺失时取所有动作本体高度的**中位数**（19.7.2），并记下来源；
 * 4. 每个动作存一个相对基准的 `scaleFix`（限 0.5~2.0，19.7.6）；
 * 5. 贴边动作**不按可见半身重算**，直接用待机基准（19.7.8）。
 *
 * ## 为什么单独成一个文件
 * 「基准怎么算」是纯算术，要能在 JVM 单测里钉死；「存在哪」依赖 [DollProfile]。
 * 两者分开之后，规则改一处、测试同步跟上，不必上真机试。
 *
 * ## 不做什么
 * - **不**改写用户原始帧图（19.7.9 明令禁止不可逆烘焙）；
 * - **不**在每次播放时重算并覆盖（19.7.4 禁止累积误差）；
 * - **不**把超出 0.5~2.0 的修正静默截断后谎称「已统一」（19.7.6）。
 */
object ModelBaseline {

    /* ============================ 数据合同 ============================ */

    /**
     * 基准来源 —— 必须可追溯（需求书 22.1 `referenceSource`）。
     *
     * 【为什么记录来源而不是只存一个数字】用户问「凭什么这么大」时，
     *   界面/日志要能回答；且「待机换了」时要能判断基准是否该重算。
     */
    enum class Source(val key: String) {
        /** 由待机动作本体高度得出（首选）。 */
        IDLE("idle"),

        /** 待机缺失，取所有有效动作本体高度的中位数。 */
        MEDIAN("median"),

        /** 用户手动指定（大小检查页的「手动微调」）。 */
        MANUAL("manual"),

        /** 旧档没有基准，首次进入时按当时显示大小反推一次（v2.1·17.8 风险二回退方案）。 */
        LEGACY("legacy"),

        /** 还没有可靠基准 —— 界面标「待校准」，播放走原有逐动作逻辑。 */
        NONE("none");

        companion object {
            fun of(key: String?): Source {
                if (key != null) {
                    for (s in values()) {
                        if (s.key == key) {
                            return s
                        }
                    }
                }
                return NONE
            }
        }
    }

    /**
     * 一个模型的基准状态。
     *
     * @param referenceFill 基准本体高度**占画布高度的比例**（0..1）。
     *                      用比例而不是像素：帧图解码尺寸会变（采样解码），像素值会失效。
     * @param source        来源，见 [Source]
     * @param needsReview   是否需要人工核验（19.7.6：修正超出 0.5~2.0 时必须标记，不能静默截断）
     */
    data class Ref(
        val referenceFill: Float,
        val source: Source,
        val needsReview: Boolean = false
    ) {
        /** 是否有可用的基准（NONE 或数值非法都算没有）。 */
        fun isValid(): Boolean {
            return source != Source.NONE && referenceFill.isFinite() &&
                referenceFill >= MIN_FILL && referenceFill <= MAX_FILL
        }

        fun encode(): String {
            return fmt(referenceFill) + "," + source.key + "," + (if (needsReview) "1" else "0")
        }

        companion object {
            /** 本体高度占画布的下限：低于此认为素材异常（几乎全透明）。 */
            const val MIN_FILL = 0.08f

            /** 上限：本体不可能超过画布（留 1% 容差给抗锯齿边缘）。 */
            const val MAX_FILL = 1.01f

            val NONE = Ref(0f, Source.NONE, false)

            fun decode(s: String?): Ref {
                if (s.isNullOrEmpty()) {
                    return NONE
                }
                val p = s.split(",")
                if (p.size < 2) {
                    return NONE
                }
                return try {
                    val fill = p[0].trim().toFloat()
                    val src = Source.of(p[1].trim())
                    val review = p.size >= 3 && p[2].trim() == "1"
                    val ref = Ref(fill, src, review)
                    if (ref.isValid()) ref else NONE
                } catch (t: Throwable) {
                    NONE
                }
            }

            private fun fmt(v: Float): String =
                String.format(java.util.Locale.US, "%.4f", v)
        }
    }

    /* ============================ 计算 ============================ */

    /**
     * 由「各动作的本体高度占比」算出模型级基准（需求书 19.7.2）。
     *
     * @param idleFill   待机动作的本体高度占比；没有待机 / 不可靠时传 null
     * @param otherFills 其它各自定义状态动作的本体高度占比（拖拽 / 贴入 / 贴边）
     *
     * 【贴边动作要不要参与中位数】**不参与**。
     *   19.7.8 明确「贴边半身只是画面裁剪，必须用待机基准缩放」，也就是说它的本体高度
     *   本身就是**片面的**（只有露出的部分）。若让它参与中位数，基准会被它拉小，
     *   于是所有人偶都被放大 —— 这正是本需求要修的病。
     *
     * @return 算出的基准；一个有效值都没有时返回 [Ref.NONE]（界面标「待校准」）
     */
    fun compute(idleFill: Float?, otherFills: List<Float>): Ref {
        // ① 待机优先。
        if (idleFill != null && idleFill.isFinite() && idleFill in Ref.MIN_FILL..Ref.MAX_FILL) {
            return Ref(idleFill, Source.IDLE)
        }
        // ② 待机缺失 → 有效动作高度中位数。
        val valid = ArrayList<Float>()
        for (f in otherFills) {
            if (f.isFinite() && f >= Ref.MIN_FILL && f <= Ref.MAX_FILL) {
                valid.add(f)
            }
        }
        if (valid.isEmpty()) {
            return Ref.NONE
        }
        valid.sort()
        val n = valid.size
        val median = if (n % 2 == 1) {
            valid[n / 2]
        } else {
            (valid[n / 2 - 1] + valid[n / 2]) / 2f
        }
        return Ref(median, Source.MEDIAN)
    }

    /**
     * 某动作相对模型基准的修正比例（需求书 19.7.3）。
     *
     * `scaleFix = referenceFill / actionFill`
     *
     * 【含义】动作自己的本体比基准**矮** → scaleFix > 1 → 放大该动作，使本体回到基准高度。
     * 【为什么不直接改帧图】19.7.9：微调只保存参数，不改原始文件。
     *
     * @param actionFill 该动作的本体高度占比
     * @return 修正比例，以及是否**超出** 0.5~2.0（超出时按边界截断，但要标记需人工核验）
     */
    fun scaleFixFor(referenceFill: Float, actionFill: Float): Fix {
        if (!referenceFill.isFinite() || !actionFill.isFinite() ||
            referenceFill <= 0f || actionFill <= 0f
        ) {
            return Fix(1f, false)
        }
        val raw = referenceFill / actionFill
        if (!raw.isFinite() || raw <= 0f) {
            return Fix(1f, false)
        }
        val clamped = Math.max(FIX_MIN, Math.min(FIX_MAX, raw))
        // 超出边界 ⇒ 真实修正无法表达。19.7.6 要求：不得静默截断后谎称「已统一」。
        val outOfRange = raw < FIX_MIN - EPS || raw > FIX_MAX + EPS
        return Fix(clamped, outOfRange)
    }

    /** 修正比例上下限（需求书 19.7.6 / 22.1 `scaleFix` 范围）。 */
    const val FIX_MIN = 0.5f
    const val FIX_MAX = 2.0f
    private const val EPS = 0.001f

    /**
     * 修正结果。
     *
     * @param fix          实际生效的修正比例（已夹在 [FIX_MIN]~[FIX_MAX]）
     * @param needsReview  true = 真实修正超出允许范围，界面应标「建议手动对齐」
     */
    data class Fix(val fix: Float, val needsReview: Boolean)

    /**
     * 判定动作之间的身高差异等级（需求书 R-086 / 19.7.1）。
     *
     * | 差异 | 等级 | 界面动作 |
     * |---|---|---|
     * | < 5%  | [Level.OK]        | 自动通过，不打扰用户 |
     * | 5~10% | [Level.AUTO_FIXED]| 自动修正并提示「已统一大小」 |
     * | > 10% | [Level.REVIEW]    | 标记「建议手动对齐」，但**不阻止使用** |
     */
    enum class Level { OK, AUTO_FIXED, REVIEW }

    fun levelOf(diffRatio: Float): Level = when {
        !diffRatio.isFinite() || diffRatio < 0f -> Level.OK
        diffRatio < 0.05f -> Level.OK
        diffRatio <= 0.10f -> Level.AUTO_FIXED
        else -> Level.REVIEW
    }

    /** 两个本体高度占比之间的相对差异（分母取基准；基准无效时返回 0）。 */
    fun diffRatio(referenceFill: Float, actionFill: Float): Float {
        if (!referenceFill.isFinite() || !actionFill.isFinite() || referenceFill <= 0f) {
            return 0f
        }
        return Math.abs(actionFill - referenceFill) / referenceFill
    }

    /* ============================ 存取 ============================ */

    private const val K_REF = "model_ref"

    /** 读取这只人偶的模型级基准（没算过返回 [Ref.NONE]）。 */
    fun ref(ctx: Context, dollId: String?): Ref {
        return Ref.decode(DollProfile.raw(ctx, DollProfile.key(dollId, K_REF)))
    }

    /**
     * 写入模型级基准。
     *
     * 【为什么用 commit】用户可能调完就划掉后台；`apply()` 的异步写盘有丢的风险，
     *   与 [DollSizeSpec.setSpec] 保持同一约定。
     */
    fun setRef(ctx: Context, dollId: String?, ref: Ref) {
        DollProfile.putRaw(ctx, DollProfile.key(dollId, K_REF), ref.encode())
    }

    /** 清除（回到「待校准」）。 */
    fun clear(ctx: Context, dollId: String?) {
        DollProfile.putRaw(ctx, DollProfile.key(dollId, K_REF), Ref.NONE.encode())
    }
}
