package com.dollhouse.app.data

import android.content.Context

/**
 * 【职责】人偶尺寸标准化的**唯一事实源**：把「一张素材里角色占多大」翻译成
 * 「桌面上该显示多大」。
 *
 * ## 要解决的问题
 *
 * 用户导入的素材千奇百怪：有的图角色占满画布，有的图角色只占 40%，四周全是透明留白；
 * 有的是 512×512 方图，有的是 1080×2400 竖图。改造前的口径是
 * 「整张画布铺满窗口」—— 于是画布大而角色小的素材在桌面上人偶特别小，
 * 而画布小而角色大的素材又撑爆窗口。同一只人偶换动作，大小还会跟着画布比例跳。
 *
 * ## 四层结构（与需求书一致）
 *
 * 1. **原始素材**：用户导入的文件原样保留，不覆盖、不强制裁剪。
 * 2. **标准化画布**：逻辑参考尺寸 [REF_SIDE]（不是 Android 固定像素）。
 *    素材等比缩放进这个画布、居中、空白透明，**绝不拉伸**。
 * 3. **人偶显示规格**：每只人偶一份（[DollProfile] 存），含基础大小与偏移。
 *    桌面、编辑器预览、动作详情三处共用同一份。
 * 4. **动作坐标与变换**：动作可以有自己的位移 / 旋转 / 腾空，
 *    但**不许**决定人偶的基础显示尺寸。
 *
 * ## 为什么单独一个类而不是塞进 DollProfile
 *
 * 「多大算标准」是一套换算规则，涉及 [BodyBaseline.Box] 与窗口尺寸的关系；
 * 而 `DollProfile` 只负责存取。规则与存取分开之后，
 * 换算能被 JVM 单测钉死（见 `DollSizeSpecTest`），不必上真机试。
 *
 * ## 不做什么
 *
 * - **不**自动抠图、**不**猜角色轮廓。没有可靠透明边界时（普通 JPG 照片）
 *   如实退回「整画布」口径，并允许用户手动调大小 —— 需求书明确要求
 *   「无法可靠识别角色轮廓时，应允许用户调整，而不是伪造精确的自动识别结果」。
 * - **不**改写用户的原图文件。
 */
object DollSizeSpec {

    /**
     * 标准化逻辑画布的参考边长（px）。
     *
     * 【为什么是参考值而不是硬性像素】需求书原文：「512×512 只是可配置的逻辑参考值，
     *   不能将其直接等同于 Android 屏幕上的固定像素尺寸」。
     *   本常量只用于「内容占比」的归一化计算，从不直接当显示尺寸用。
     */
    const val REF_SIDE = 512

    /**
     * 内容占比的合理区间。
     *
     * 【为什么需要上下限】
     * - 下限：一帧里角色只占 2% 通常是「几乎全透明」的坏帧，若认真按它放大，
     *   人偶会比屏幕还高 —— 明显不是用户想要的。
     * - 上限：占比不可能超过 1.0（包围盒被裁到画布内）。
     *   落在区间外一律退回「整画布」口径，宁可大小不完美也不要放大到离谱。
     */
    private const val MIN_FILL = 0.05f
    private const val MAX_FILL = 1.0f

    /**
     * 归一化系数区间。
     *
     * 【含义】`fill` 是「角色占画布高度的比例」。同样一个窗口，
     *   角色占 100% 的素材按 1.0 显示；占 50% 的素材要放大 2 倍才能看起来一样大。
     * 这里把倍数夹在 [MIN_K] ~ [MAX_K]，防止极端素材把人偶撑到屏幕外。
     */
    private const val MIN_K = 0.5f
    private const val MAX_K = 4.0f

    /**
     * 由「本体包围盒」算出内容占比归一化系数。
     *
     * 【为什么用高度而不是宽度】人偶在屏幕上的**视觉大小**主要由身高决定：
     *   一个横向挥手帧的肩膀可能很宽，但人不该因此突然变矮。
     *   需求书第五条第 5 点也明确「允许跳跃、挥手、坐下等动作自然改变姿势和占用空间」。
     *   所以这里只按**角色高度占画布高度的比例**归一化，宽度变化不参与。
     *
     * @param box 整组帧合并后的本体包围盒（相对画布比例）；null 表示没有可靠边界
     * @return 系数 k；调用方按「显示尺寸 = 窗口尺寸 / k」使用；null 表示按旧口径
     */
    fun fillFactor(box: BodyBaseline.Box?): Float? {
        if (box == null || !box.isValid()) {
            return null
        }
        return fillFactorOf(box.height)
    }

    /**
     * 直接由「角色占画布高度的比例」算系数（单测与 UI 滑条用）。
     *
     * @param fill 0..1 的占比；非法值返回 null
     */
    fun fillFactorOf(fill: Float): Float? {
        if (!fill.isFinite() || fill < MIN_FILL || fill > MAX_FILL) {
            return null
        }
        val k = 1.0f / fill
        if (!k.isFinite()) {
            return null
        }
        return Math.max(MIN_K, Math.min(MAX_K, k))
    }

    /* ============================ 人偶级显示规格 ============================ */

    /**
     * 人偶显示规格：**每只人偶一份**，与动作无关。
     *
     * 【为什么与动作分离】需求书第三条第 2 点：「人偶基础尺寸应与具体动作分离。
     *   更换动作时，默认继续使用同一套显示规格」。
     *   改造前缩放是全局 `PetPrefs.petScale` 加动作自己的 baseline 两套尺子，
     *   换动作时两把尺子重新组合，结果是忽大忽小。现在基础尺寸只有这一份。
     */
    data class Spec(
        /** 基础大小百分比（100 = 按标准化画布算出的默认大小）。 */
        val size: Int,
        /** 水平偏移（占窗口宽的比例，-0.5 ~ 0.5）。 */
        val ox: Float,
        /** 垂直偏移（占窗口高的比例，-0.5 ~ 0.5）。 */
        val oy: Float
    ) {
        /** 是否全默认（全默认时播放层完全跳过新分支，保证老行为逐像素一致）。 */
        fun isDefault(): Boolean = size == SIZE_DEFAULT && ox == 0f && oy == 0f

        fun encode(): String = size.toString() + "," + fmt(ox) + "," + fmt(oy)

        private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.4f", v)

        companion object {
            /** 基础大小下限（30%）。 */
            const val SIZE_MIN = 30
            /** 基础大小上限（400%，与 DollProfile.SCALE_MAX 同口径）。 */
            const val SIZE_MAX = 400
            /** 默认大小：100% = 标准化口径算出来的原生大小。 */
            const val SIZE_DEFAULT = 100
            /** 偏移的绝对值上限。 */
            const val OFFSET_MAX = 0.5f

            val DEFAULT = Spec(SIZE_DEFAULT, 0f, 0f)

            /** 解析 [encode] 的输出；格式不对返回 [DEFAULT]（旧档 / 坏档都走这里兜底）。 */
            fun decode(s: String?): Spec {
                if (s.isNullOrEmpty()) {
                    return DEFAULT
                }
                val p = s.split(",")
                if (p.size != 3) {
                    return DEFAULT
                }
                return try {
                    Spec(
                        clampSize(p[0].trim().toInt()),
                        clampOffset(p[1].trim().toFloat()),
                        clampOffset(p[2].trim().toFloat())
                    )
                } catch (t: Throwable) {
                    DEFAULT
                }
            }

            fun clampSize(v: Int): Int = Math.max(SIZE_MIN, Math.min(SIZE_MAX, v))

            fun clampOffset(v: Float): Float {
                if (!v.isFinite()) {
                    return 0f
                }
                return Math.max(-OFFSET_MAX, Math.min(OFFSET_MAX, v))
            }
        }
    }

    /* ============================ 存取 ============================ */

    private const val K_SPEC = "size_spec"

    /** 读取这只人偶的显示规格（没设过返回 [Spec.DEFAULT]）。 */
    fun spec(ctx: Context, dollId: String?): Spec {
        return Spec.decode(DollProfile.raw(ctx, DollProfile.key(dollId, K_SPEC)))
    }

    /**
     * 写入显示规格。
     *
     * 【为什么用 commit】用户调完大小可能立刻划掉后台；`apply()` 的异步写盘有丢的风险，
     *   与 `DollProfile.setScale` 保持同一约定（见 [DollProfile.putRaw]）。
     */
    fun setSpec(ctx: Context, dollId: String?, s: Spec) {
        DollProfile.putRaw(ctx, DollProfile.key(dollId, K_SPEC),
            Spec(clampSizeOf(s.size), Spec.clampOffset(s.ox), Spec.clampOffset(s.oy)).encode())
    }

    private fun clampSizeOf(v: Int): Int = Spec.clampSize(v)

    /** 恢复默认（等价于「自动适配」）。 */
    fun reset(ctx: Context, dollId: String?) {
        DollProfile.putRaw(ctx, DollProfile.key(dollId, K_SPEC), Spec.DEFAULT.encode())
    }

    /**
     * 最终显示缩放系数（相对「标准化画布铺满窗口」）。
     *
     * @param spec 人偶显示规格
     * @return 乘在窗口尺寸上的系数；1.0 = 与改造前完全一致
     */
    fun scaleOf(spec: Spec): Float {
        return Spec.clampSize(spec.size) / 100.0f
    }
}