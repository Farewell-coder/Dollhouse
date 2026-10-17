package com.dollhouse.app.pet.behavior

/**
 * 【职责】动作语义仲裁与调度 —— 决定「**此刻该播什么动作**」。
 *
 * 【为什么由它统一决定（本任务成败判据的落点）】
 *   改造前：动作是**只读素材库**（`DollAnim` 只被编辑页与烘帧器读取），
 *   桌宠的行为由 `PetAnimator` **硬编码**（1/2/3/4/5 号待机分支 + jump/drag/land）。
 *   两套系统之间没有桥 —— 用户在动作页精心配置的动作，桌宠一个都不会播。
 *   **本类就是那座桥**：桌宠问它「该播哪个」，它按优先级裁决后交给对应 [com.dollhouse.app.pet.render.PetRenderer]。
 *
 * 【优先级】交互（点击/拖动/长按）> 出现/消失 > 待机 > 基础形变。
 *   见 [ActionSemantic.priority]。
 *
 * 【硬约束（任务书 §3.5）】
 *   · **不新建第二套调度器** —— 本类不自己计时、不自己驱动帧循环，
 *     它只回答「该播什么」，推进仍由既有的 `PetAnimator` / 帧循环负责。
 *   · 交互优先级**必须高于**待机（点击时不能被待机动作顶掉）。
 *   · [release] 必须**幂等**。
 *
 * 【纯逻辑可取巧】本类不依赖 Android 类型（除了注入进来的渲染器），
 *   于是「优先级仲裁」这段核心逻辑可以在 JVM 上直接单测（见 `BehaviorPriorityTest`）。
 */
class PetBehaviorController {

    /** 解析出的动作来源：给定语义 → 动作 id（由宿主注入，避免本类依赖数据层）。 */
    fun interface ActionResolver {
        /** @return 该语义对应的动作 id；没有则返回 null。 */
        fun resolve(semantic: String): String?
    }

    /** 真正播放一个动作（由宿主实现，内部调用对应渲染器）。 */
    interface ActionPlayer {
        fun play(actionId: String, semantic: String)
        fun stop()
    }

    private var resolver: ActionResolver? = null
    private var player: ActionPlayer? = null

    /** 当前相位。 */
    var state: PlaybackState = PlaybackState.IDLE
        private set

    /** 当前正在播的语义（IDLE / 无则 null）。 */
    var currentSemantic: String? = null
        private set

    /** 当前正在播的动作 id。 */
    var currentActionId: String? = null
        private set

    /** 已释放标志（release 幂等用）。 */
    private var released = false

    /**
     * 待机档是否被「交互抢占」挂起。
     *
     * 【为什么需要这个标志】交互结束后要**回落到待机档** ——
     *   但如果只是简单地在交互结束时调 [onIdle]，而交互期间又来了多次待机请求，
     *   就会出现「交互还没结束，待机动作先把它顶掉」。
     *   用标志记录「待机被挂起」，交互一结束就知道该不该回。
     */
    private var idleSuspended = false

    /** 注入依赖（宿主在构造后、首次使用前调用）。 */
    fun bind(resolver: ActionResolver?, player: ActionPlayer?) {
        this.resolver = resolver
        this.player = player
    }

    // ============================ 对外行为 ============================

    /**
     * 待机：若该人偶设了 `idle` 语义的动作，播它；否则什么都不做（交回基础形变/呼吸）。
     *
     * 【为什么「否则什么都不做」而不是「播个默认动作」】改造前待机由 PetAnimator
     *   的 5 个内置小动作承担；如果这里硬塞一个默认动作，会把那套既有行为顶掉 ——
     *   属于「重构顺便改行为」，是任务书 §8.1 明令禁止的。
     */
    fun onIdle() {
        if (released) {
            return
        }
        // 交互进行中：把待机请求挂起，等交互结束再回。
        if (isInteractionActive()) {
            idleSuspended = true
            return
        }
        play(ActionSemantic.IDLE)
    }

    /**
     * 交互抢占：暂停待机档，播指定动作。
     *
     * 【优先级规则】新来的语义优先级**不低于**当前正在播的才允许抢占 ——
     *   否则「拖动中来了一个待机请求」会把拖拽动作顶掉（用户看到的是手还按着、
     *   人偶却开始伸懒腰）。
     */
    fun play(semantic: String) {
        if (released) {
            return
        }
        if (!ActionSemantic.isStandard(semantic)) {
            return
        }
        val incoming = ActionSemantic.priority(semantic)
        val current = currentSemantic?.let { ActionSemantic.priority(it) } ?: -1
        if (incoming < current) {
            // 低优先级请求：拒绝，不改现状。
            return
        }
        val id = resolver?.resolve(semantic)
        if (id == null) {
            // 该语义没配动作：不改现状（不去顶掉别人）。
            return
        }
        currentSemantic = semantic
        currentActionId = id
        state = PlaybackState.PLAYING
        player?.play(id, semantic)
    }

    /**
     * **【必须由交互方主动调用】交互开始**（手指按下 / 拖动开始 / 长按到点）。
     *
     * ## 为什么这个方法不可省
     * 动作播放层是「有内容就独占绘制」的（见 [com.dollhouse.app.pet.ActionPlayerCore.draw]）——
     * 只要它在播，`PetView.onDraw` 就会整帧让位。
     * 于是**循环的待机默认动作会把网格形变（拖动倾斜 / 贴边趴姿 / 跳跃 / 眨眼）全部盖住**，
     * 用户按住人偶拖时看不到任何反馈 —— 那是明显的功能退化。
     *
     * 所以交互一开始就必须**无条件**把待机档让出来（哪怕该语义没配动作），
     * 让原管线接管；交互结束再由 [onInteractionEnd] 回落。
     *
     * 【与 [play] 的区别】[play] 是「有更好的就换」，本方法是「必须腾位」——
     *   即便没配 `drag` 动作也要停掉正在播的待机动作。
     *
     * @param semantic 交互语义（`drag` / `longpress` / `tap`）。
     * @return 是否播上了该语义的动作（没配则只腾位、不播）。
     */
    fun onInteractionStart(semantic: String): Boolean {
        if (released) {
            return false
        }
        // 【r18·原比例滑动】拖动 / 长按时若动作层正在播，**不腾位**——
        //   动作帧是「等比缩放铺满画布」，精灵图是「按 petWidth 算」，
        //   两者尺寸不一致；腾位会让画面从动作帧切回精灵图，视觉上就是「缩小」。
        //   保持动作帧播放则尺寸不变，代价是拖动期间看不到网格倾斜/抬升 ——
        //   用户明确要「原比例滑动」，尺寸稳定优先于拖动反馈。
        //   【仅 DRAG / LONG_PRESS 豁免】tap 等瞬时语义仍走原逻辑（点一下不该被动作盖住）。
        if ((ActionSemantic.DRAG == semantic || ActionSemantic.LONG_PRESS == semantic)
            && state == PlaybackState.PLAYING
        ) {
            return true
        }
        idleSuspended = true
        val id = if (ActionSemantic.isStandard(semantic)) resolver?.resolve(semantic) else null
        if (id == null) {
            // 没配交互动作：腾位（停掉待机动作），状态回到 IDLE，
            // 于是绘制让回给网格形变 —— 拖动倾斜/趴姿立刻可见。
            currentSemantic = null
            currentActionId = null
            state = PlaybackState.IDLE
            player?.stop()
            return false
        }
        currentSemantic = semantic
        currentActionId = id
        state = PlaybackState.PLAYING
        player?.play(id, semantic)
        return true
    }

    /**
     * **【必须由播放层回调】**非循环动作播完了。
     *
     * 【为什么要单独一个入口，而不是复用 [onInteractionEnd]】
     * · 交互动作播完 → 应该回落待机（那是 [onInteractionEnd] 的语义）；
     * · **待机动作自己播完** → 不能再去「回落待机」（就是它自己），
     *   否则会无限自我重播。这种情况应该**停下来，把画面还给基础形变**
     *   （呼吸/眨眼本来就一直在跑，不会让用户看到「死掉的人偶」）。
     */
    fun onActionFinished() {
        if (released) {
            return
        }
        if (currentSemantic == ActionSemantic.IDLE) {
            currentSemantic = null
            currentActionId = null
            state = PlaybackState.IDLE
            player?.stop()
            return
        }
        onInteractionEnd()
    }

    /**
     * 交互结束：回到待机档。
     *
     * 【什么时候会被调】拖拽抬手、点击动画播完、长按判定取消等。
     *   实现里只在「确实被挂起过」时才回落 —— 避免无交互时反复触发待机重播。
     */
    fun onInteractionEnd() {
        if (released) {
            return
        }
        val wasIdle = ActionSemantic.IDLE == currentSemantic
        if (wasIdle) {
            // 本来就在待机档，没什么可回落的。
            return
        }
        idleSuspended = false
        currentSemantic = null
        currentActionId = null
        state = PlaybackState.IDLE
        player?.stop()
        // 回落：尝试播待机动作（若该人偶没配 idle 动作，这里什么都不会发生）。
        play(ActionSemantic.IDLE)
    }

    /** 暂停（保留进度）。 */
    fun pause() {
        if (released || state != PlaybackState.PLAYING) {
            return
        }
        state = PlaybackState.PAUSED
    }

    /** 从暂停恢复。 */
    fun resume() {
        if (released || state != PlaybackState.PAUSED) {
            return
        }
        state = PlaybackState.PLAYING
    }

    /**
     * 释放全部资源（**幂等**）。
     *
     * 【为什么必须幂等】人偶移除的路径有三条（用户手动关闭、服务销毁、多实例重挂），
     *   任意两条先后到达就会重复调用 —— 不幂等的话第二条会 NPE 或重复释放。
     */
    fun release() {
        if (released) {
            return
        }
        released = true
        state = PlaybackState.RELEASED
        currentSemantic = null
        currentActionId = null
        idleSuspended = false
        player?.stop()
        resolver = null
        player = null
    }

    /** 是否已释放。 */
    fun isReleased(): Boolean = released

    /** 交互是否正在进行（供帧率策略 / 外部查询）。 */
    fun isInteractionActive(): Boolean {
        val s = currentSemantic ?: return false
        return ActionSemantic.priority(s) >= ActionSemantic.priority(ActionSemantic.TAP)
    }

    /** 待机档是否被交互挂起（供测试与调试）。 */
    fun isIdleSuspended(): Boolean = idleSuspended
}