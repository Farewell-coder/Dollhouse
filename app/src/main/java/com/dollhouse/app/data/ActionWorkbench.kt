package com.dollhouse.app.data

import android.content.Context
import org.json.JSONObject

/**
 * 【职责】「动作工作台」的数据层 —— 动作库操作、动作级显示设置、行为绑定。
 *
 * ## 为什么单独一层，而不是继续塞进 [DollAnim]
 *
 * [DollAnim] 已经有 1200 行，管的是「动作是什么 + 帧文件怎么存」。
 * 本类管的是**工作台**特有的三件事：
 * ```
 * ① 动作库操作：搜索 / 复制 / 重命名 / 引用检查 / 导出
 * ② 动作级显示设置：锚点 / 适配方式 / 内容偏移（只影响这一个动作的画面摆放）
 * ③ 行为绑定：这个动作允许被哪些触发条件唤起 + 它自己的优先级档位
 * ```
 * 三者都是「围绕动作的配置」，不是动作本体，混进 [DollAnim] 会让那个类更难以维护，
 * 也会把「存帧」与「存显示参数」两条完全不同的路径缠在一起。
 *
 * ## 与「统一显示尺寸」的关系（需求 §4 的核心）
 *
 * ```
 * 资源坐标系   ← 帧图自身的像素尺寸（每个动作可以不同，不影响显示）
 * 内容边界     ← Action.baseline（导入时算出来的本体矩形）
 * 统一显示区域 ← 人偶级：DollProfile.scale（整个人偶的比例，所有动作共享）
 * 显示缩放     ← 同上一行，不为单个动作单独设比例
 * 锚点         ← 本类的 ActionDisplay.anchor（默认底部居中）
 * 窗口位置/触摸 ← PetWindow 管理，切动作时**不重建窗口**，所以天然不跳
 * ```
 * 单个动作**没有**「自己的缩放比例」字段 —— 这是刻意设计的：
 * 一旦允许逐动作调比例，用户随便动一下就会让某个动作里的人偶变大，
 * 正是需求书要消灭的那类跳变。逐动作只能调「锚点」与「内容偏移」。
 *
 * ## 存储
 *
 * 全部落在 `PetPrefs` 的同一份 SharedPreferences：
 * ```
 * act_disp_<dollId>_<actionId>   JSON {anchor, fit, ox, oy}
 * act_bind_<dollId>_<actionId>   JSON {triggers[], priority}
 * ```
 * 键不存在 = 用默认值（底部居中 / 按统一尺寸适配 / 无偏移 / 默认触发 / 普通优先级），
 * 所以旧档升级后行为逐像素不变，不需要迁移代码。
 */
object ActionWorkbench {

    /* ============================ 显示设置 ============================ */

    /**
     * 锚点：把动作内容对齐到统一显示区域的哪一点。
     *
     * 【为什么默认底部居中】桌宠是「脚踩在窗口底边」的语义
     * （`ActionPlayerCore.draw` 就是按本体底边对齐窗口底边算的），
     * 选中心会让所有动作整体上移半个身位，与人偶平时的位置对不上。
     */
    const val ANCHOR_BOTTOM_CENTER = "bottom_center"
    const val ANCHOR_CENTER = "center"
    const val ANCHOR_BOTTOM_LEFT = "bottom_left"
    const val ANCHOR_BOTTOM_RIGHT = "bottom_right"

    val ANCHORS: List<String> = listOf(
        ANCHOR_BOTTOM_CENTER, ANCHOR_CENTER, ANCHOR_BOTTOM_LEFT, ANCHOR_BOTTOM_RIGHT
    )

    fun anchorLabel(anchor: String): String = when (anchor) {
        ANCHOR_CENTER -> "中心"
        ANCHOR_BOTTOM_LEFT -> "底部左侧"
        ANCHOR_BOTTOM_RIGHT -> "底部右侧"
        else -> "底部居中（推荐）"
    }

    /**
     * 适配方式：把动作画面放进窗口时怎么缩放。
     *
     * 【为什么不给「按内容边界自动裁切」单独一种】
     * 裁切意味着丢像素。用户导入的图若内容偏心，裁掉的是他自己画的东西，
     * 而且不同帧裁得不一样会导致抖动。这里只提供两种**不丢内容**的适配，
     * 内容偏心交给 [ActionDisplay.ox]/[ActionDisplay.oy] 平移解决。
     */
    const val FIT_UNIFIED = "unified"      // 按统一人偶尺寸适配（默认，本体宽度对齐窗口）
    const val FIT_CONTAIN = "contain"      // 保持原始比例并留边（整张画布塞进窗口）

    fun fitLabel(fit: String): String =
        if (fit == FIT_CONTAIN) "保持原始比例并留边" else "按统一人偶尺寸适配"

    /**
     * 单个动作的显示设置。
     *
     * @param anchor 锚点，取值见 `ANCHOR_*`
     * @param fit    适配方式，取值见 `FIT_*`
     * @param ox     内容横向偏移（占窗口宽的比例，正数向右）
     * @param oy     内容纵向偏移（占窗口高的比例，正数向下）
     */
    class ActionDisplay(
        val anchor: String = ANCHOR_BOTTOM_CENTER,
        val fit: String = FIT_UNIFIED,
        val ox: Float = 0f,
        val oy: Float = 0f
    ) {
        fun isDefault(): Boolean =
            anchor == ANCHOR_BOTTOM_CENTER && fit == FIT_UNIFIED && ox == 0f && oy == 0f

        fun encode(): String {
            val o = JSONObject()
            o.put("anchor", anchor)
            o.put("fit", fit)
            o.put("ox", ox.toDouble())
            o.put("oy", oy.toDouble())
            return o.toString()
        }

        companion object {
            val DEFAULT = ActionDisplay()

            /**
             * 宽松解码：任何字段缺失 / 非法都退回该字段的默认值。
             *
             * 【为什么不整体作废】用户手改坏一个字段，不该让整个动作的显示设置全丢 ——
             * 那会让画面突然从「调好的位置」跳回默认，比保持大部分设置更糟。
             */
            @JvmStatic
            fun decode(raw: String?): ActionDisplay {
                if (raw.isNullOrEmpty()) {
                    return DEFAULT
                }
                return try {
                    val o = JSONObject(raw)
                    val a = o.optString("anchor", ANCHOR_BOTTOM_CENTER)
                    val f = o.optString("fit", FIT_UNIFIED)
                    ActionDisplay(
                        anchor = if (ANCHORS.contains(a)) a else ANCHOR_BOTTOM_CENTER,
                        fit = if (f == FIT_CONTAIN) FIT_CONTAIN else FIT_UNIFIED,
                        ox = clampOffset(o.optDouble("ox", 0.0).toFloat()),
                        oy = clampOffset(o.optDouble("oy", 0.0).toFloat())
                    )
                } catch (t: Throwable) {
                    DEFAULT
                }
            }

            /** 偏移上限 ±50%：再大就整只人偶移出窗口，用户会以为「动作没了」。 */
            private fun clampOffset(v: Float): Float {
                if (v.isNaN()) {
                    return 0f
                }
                return Math.max(-0.5f, Math.min(0.5f, v))
            }
        }
    }

    private fun dispKey(dollId: String?, actionId: String): String =
        DollProfile.key(dollId, "act_disp_" + actionId)

    /** 读某个动作的显示设置；没存过就是默认值（不写盘）。 */
    @JvmStatic
    fun display(ctx: Context, dollId: String?, actionId: String?): ActionDisplay {
        if (actionId.isNullOrEmpty()) {
            return ActionDisplay.DEFAULT
        }
        val raw = try {
            PetPrefs.get(ctx).getString(dispKey(dollId, actionId), null)
        } catch (t: Throwable) {
            null
        }
        return ActionDisplay.decode(raw)
    }

    /** 写显示设置；等于默认值时**删键**，避免给每个动作都留一条无意义的记录。 */
    @JvmStatic
    fun setDisplay(ctx: Context, dollId: String?, actionId: String?, d: ActionDisplay) {
        if (actionId.isNullOrEmpty()) {
            return
        }
        try {
            val e = PetPrefs.get(ctx).edit()
            if (d.isDefault()) {
                e.remove(dispKey(dollId, actionId))
            } else {
                e.putString(dispKey(dollId, actionId), d.encode())
            }
            e.apply()
        } catch (t: Throwable) {
        }
    }

    /* ============================ 行为绑定 ============================ */

    /**
     * 触发条件。**直接复用** [com.dollhouse.app.pet.behavior.ActionSemantic] 的语义名，
     * 不另造一套 —— 需求书要求「复用已有机制，避免互相冲突的触发器」。
     *
     * ## 为什么这里只列这几项
     *
     * ```
     * 给用户的  →  manual / idle / tap / drag / longpress / edge / random
     * 不给用户的 →  appear / disappear（生命周期，用户没法「手动让偶现一下」）
     *              sleep（休眠时本来就不响应待机动作，列表里加一项只会让人误以为能加速）
     * ```
     * 关键点：**名字就是语义 id**。仲裁层拿到 `semantic` 直接拿去查，中间不做映射 ——
     * 一旦引入「界面叫拖动结束、内部叫 drag_end」这种别名，改名时必有一处忘改，
     * 症状是「界面上勾了但没生效」，而且极难自查。
     */
    val TRIGGERS: List<String> = listOf(
        "manual", "idle", "tap", "drag", "longpress", "edge", "random"
    )

    fun triggerLabel(t: String): String = when (t) {
        "idle" -> "常驻待机时"
        "tap" -> "点击人偶时"
        "drag" -> "拖动人偶时"
        "longpress" -> "长按人偶时"
        "edge" -> "靠近屏幕边缘"
        "random" -> "随机闲置事件"
        else -> "手动触发"
    }

    /**
     * 优先级档位：把连续的优先级数值收成三档给用户选。
     *
     * 【为什么不是让用户填任意数字】数值优先级是内部实现细节，
     * 让用户填 0~100 会产生「为什么 31 比 30 优先」这种无法解释的困惑。
     * 三档分别映射到既有语义的区间，档位名直接说明后果。
     */
    const val PRIORITY_NORMAL = "normal"
    const val PRIORITY_HIGH = "high"
    const val PRIORITY_TOP = "top"

    fun priorityLabel(p: String): String = when (p) {
        PRIORITY_HIGH -> "高"
        PRIORITY_TOP -> "最高（临时强制）"
        else -> "普通"
    }

    /**
     * 档位 → 优先级加成。
     *
     * ## 为什么是 +12 / +35 这两个数
     *
     * 语义优先级是 `DRAG=LONG_PRESS 40 > TAP 35 > MANUAL 30 > APPEAR/DISAPPEAR 20
     * > RANDOM 15 > EDGE 12 > IDLE 10 > SLEEP 5`。想让用户的三档**有实际差别**，
     * 加成必须刚好跨过这些档位（以最低的 IDLE=10 为基准计算最坏情况）：
     * ```
     * 普通  +0   IDLE 10    抢不过点击(35)          —— 系统自发行为不打断交互
     * 高   +12   IDLE 22    压过贴边(12)，但 < 手动(30)
     * 最高 +35   IDLE 45    > 拖动(40)              —— 「临时强制」名副其实
     * ```
     * 【为什么「最高」必须 > 30 而不是 ≥ 30】加成是加在**语义优先级**上的：
     *   最坏的组合是「常驻壳(IDLE 10) 被提到最高档」，它要压过拖动(40) 就必须 ≥ 41。
     *   早期写成 +30，结果 10+30=40 与 DRAG 恰好相等，`incoming < current` 判据下
     *   打不过拖动 —— 单元测试 `最高档能压过拖动` 当场失败。这里改为 +35 留出余量。
     *
     * 【为什么不是随便给个 100】加成本身是「在语义表上偏移」，给太大会让
     * 「高」和「最高」在事实上没有区别（都压过一切），档位就失去意义了。
     */
    fun priorityBoost(p: String): Int = when (p) {
        PRIORITY_HIGH -> 12
        PRIORITY_TOP -> 35
        else -> 0
    }

    class ActionBinding(
        val triggers: List<String> = listOf("manual"),
        val priority: String = PRIORITY_NORMAL
    ) {
        fun isDefault(): Boolean =
            triggers.size == 1 && triggers[0] == "manual" && priority == PRIORITY_NORMAL

        fun encode(): String {
            val o = JSONObject()
            val arr = org.json.JSONArray()
            for (t in triggers) {
                arr.put(t)
            }
            o.put("triggers", arr)
            o.put("priority", priority)
            return o.toString()
        }

        companion object {
            val DEFAULT = ActionBinding()

            @JvmStatic
            fun decode(raw: String?): ActionBinding {
                if (raw.isNullOrEmpty()) {
                    return DEFAULT
                }
                return try {
                    val o = JSONObject(raw)
                    val arr = o.optJSONArray("triggers")
                    val list = ArrayList<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val t = arr.optString(i, "")
                            if (TRIGGERS.contains(t) && !list.contains(t)) {
                                list.add(t)
                            }
                        }
                    }
                    // 空集合 = 用户把所有触发都关了 → 至少保留「手动」，否则这个动作永远播不到。
                    if (list.isEmpty()) {
                        list.add("manual")
                    }
                    val p = o.optString("priority", PRIORITY_NORMAL)
                    ActionBinding(
                        triggers = list,
                        priority = if (p == PRIORITY_HIGH || p == PRIORITY_TOP) p else PRIORITY_NORMAL
                    )
                } catch (t: Throwable) {
                    DEFAULT
                }
            }
        }
    }

    private fun bindKey(dollId: String?, actionId: String): String =
        DollProfile.key(dollId, "act_bind_" + actionId)

    @JvmStatic
    fun binding(ctx: Context, dollId: String?, actionId: String?): ActionBinding {
        if (actionId.isNullOrEmpty()) {
            return ActionBinding.DEFAULT
        }
        val raw = try {
            PetPrefs.get(ctx).getString(bindKey(dollId, actionId), null)
        } catch (t: Throwable) {
            null
        }
        return ActionBinding.decode(raw)
    }

    @JvmStatic
    fun setBinding(ctx: Context, dollId: String?, actionId: String?, b: ActionBinding) {
        if (actionId.isNullOrEmpty()) {
            return
        }
        try {
            val e = PetPrefs.get(ctx).edit()
            if (b.isDefault()) {
                e.remove(bindKey(dollId, actionId))
            } else {
                e.putString(bindKey(dollId, actionId), b.encode())
            }
            e.apply()
        } catch (t: Throwable) {
        }
    }

    /**
     * 动作是否允许被某个触发条件唤起。
     *
     * 【给谁用】仲裁层在决定「要不要播这个动作」时查一眼。
     * 没有记录 = 默认策略（允许），所以老档行为不变。
     */
    @JvmStatic
    fun allows(ctx: Context, dollId: String?, actionId: String?, trigger: String): Boolean {
        if (actionId.isNullOrEmpty()) {
            return true
        }
        val raw = try {
            PetPrefs.get(ctx).getString(bindKey(dollId, actionId), null)
        } catch (t: Throwable) {
            null
        }
        // 【没有记录时不查表】绝大多数动作都没被用户改过绑定，
        //   每次都 new 一个 ActionBinding 既浪费又容易掩盖「默认允许」这条语义。
        if (raw.isNullOrEmpty()) {
            return true
        }
        return ActionBinding.decode(raw).triggers.contains(trigger)
    }
}