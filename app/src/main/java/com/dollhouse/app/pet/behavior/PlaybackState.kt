package com.dollhouse.app.pet.behavior

/**
 * 【职责】播放状态 —— 动作的生命周期相位。
 *
 * 【为什么单独建类型】改造前「在不在播」这件事由多个布尔/浮点字段**隐式表达**
 *   （`idleT >= 0`、`jumpT >= 0`、`blinkT >= 0`、`expression != 0`…），
 *   想回答「现在到底在播什么」要 OR 五六个字段。
 *   换成显式状态机后：**一个字段回答一个问题**。
 *
 * 【状态迁移】
 * ```
 * IDLE ──play──> PLAYING ──pause──> PAUSED ──resume──> PLAYING
 *                    │                  │
 *                    └──── release ─────┴──> RELEASED（终态）
 * ```
 *
 * 【为什么不加 STOPPED】「播完了」与「还没开始」在行为上等价
 *   （都要回落到待机档），多一个状态只会让 `when` 分支变多而没有实际区分度。
 *   真需要区分「刚播完」用一次性的回调，不用常驻状态。
 */
enum class PlaybackState {

    /** 空闲：没有动作在播，可接受新的播放请求。 */
    IDLE,

    /** 播放中：动作正在推进。 */
    PLAYING,

    /** 暂停：保留进度，可 resume。 */
    PAUSED,

    /** 已释放：**终态**，不可再 play/pause/resume。 */
    RELEASED;

    /** 是否可接受新的播放请求。 */
    fun canPlay(): Boolean = this == IDLE

    /** 是否处于活跃相位（供帧率策略判断要不要全速）。 */
    fun isActive(): Boolean = this == PLAYING

    /** 是否已释放（所有生命周期方法的第一道闸门）。 */
    fun isReleased(): Boolean = this == RELEASED
}