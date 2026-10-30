package com.dollhouse.app.data

import android.content.Context

/**
 * 【职责】存取**动作级**的 `scaleFix`（相对模型基准的修正比例）。
 *
 * ## 为什么单独一个类
 *
 * 需求书 19.7.3 把「大小统一」拆成两层：
 * - **模型级** [ModelBaseline]：全模型共用一把尺子（`referenceBodyHeight`）；
 * - **动作级**（本类）：每个动作相对这把尺子的修正值。
 *
 * 两层分开的好处：换待机动作时基准变了，修正值仍然是有意义的相对量；
 * 而如果把修正值写进 [DollAnim.Action]，就必须改动作的 JSON 结构（牵动
 * 读档 / 导出 / 模型包 manifest 三处），风险远大于收益。
 *
 * ## 为什么不用现成的 `ActionWorkbench`
 *
 * `ActionWorkbench` 存的是「锚点 / 适配方式 / 内容偏移」——那是**排布**；
 * `scaleFix` 是**尺寸**。两者语义不同，混在一起之后「恢复默认」会互相牵连。
 *
 * ## 存储口径
 * 键 = `dollId + "|" + actionId`，值 = 浮点字符串（4 位小数）。
 * 走 [DollProfile.putRaw]，与 [DollSizeSpec] / [ModelBaseline] 同一套落盘机制。
 */
object ActionScaleFix {

    /**
     * 取某个动作的修正比例（没设过返回 1.0 = 不修正）。
     *
     * 【为什么要夹在 0.5~2.0】需求书 19.7.6 / 22.1 的 `scaleFix` 范围。
     *   越界的值一定来自坏档或被改坏的数据，直接用会让画面离谱。
     */
    fun fix(ctx: Context, dollId: String?, actionId: String?): Float {
        if (dollId.isNullOrEmpty() || actionId.isNullOrEmpty()) {
            return 1f
        }
        val raw = DollProfile.raw(ctx, key(dollId, actionId))
        if (raw.isNullOrEmpty()) {
            return 1f
        }
        val v = try {
            raw.trim().toFloat()
        } catch (t: Throwable) {
            return 1f
        }
        if (!v.isFinite() || v <= 0f) {
            return 1f
        }
        return Math.max(ModelBaseline.FIX_MIN, Math.min(ModelBaseline.FIX_MAX, v))
    }

    /** 写入修正比例（自动夹到合法区间）。 */
    fun setFix(ctx: Context, dollId: String?, actionId: String?, fix: Float) {
        if (dollId.isNullOrEmpty() || actionId.isNullOrEmpty()) {
            return
        }
        val v = if (!fix.isFinite() || fix <= 0f) {
            1f
        } else {
            Math.max(ModelBaseline.FIX_MIN, Math.min(ModelBaseline.FIX_MAX, fix))
        }
        DollProfile.putRaw(ctx, key(dollId, actionId), String.format(java.util.Locale.US, "%.4f", v))
    }

    /** 清除（回到「不修正」）。 */
    fun clear(ctx: Context, dollId: String?, actionId: String?) {
        if (dollId.isNullOrEmpty() || actionId.isNullOrEmpty()) {
            return
        }
        DollProfile.putRaw(ctx, key(dollId, actionId), "")
    }

    /**
     * 按「模型基准 + 该动作的本体高度占比」**自动算出并落盘**修正值。
     *
     * @return 实际写入的修正值；基准或动作高度不可用时返回 null（不写盘）
     */
    fun autoFix(ctx: Context, dollId: String?, actionId: String?,
                referenceFill: Float, actionFill: Float): ModelBaseline.Fix? {
        if (dollId.isNullOrEmpty() || actionId.isNullOrEmpty()) {
            return null
        }
        if (!referenceFill.isFinite() || referenceFill <= 0f) {
            return null
        }
        if (!actionFill.isFinite() || actionFill <= 0f) {
            return null
        }
        val r = ModelBaseline.scaleFixFor(referenceFill, actionFill)
        setFix(ctx, dollId, actionId, r.fix)
        return r
    }

    private fun key(dollId: String, actionId: String): String = "fix_" + dollId + "|" + actionId
}