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

    /**
     * **【v0.1.0】贴边状态探测** —— 由宿主注入（读 `PetView.lean != 0`）。
     *
     * 【为什么用探测而不是让本类记状态】贴边/离边的**唯一真源**在视图几何里
     *   （窗口 x、lean 角、吸附动画）。仲裁层再存一份必然与它不同步；
     *   问一次当前值，永远同源。
     */
    fun interface EdgeProbe {
        fun atEdge(): Boolean
    }

    /** 真正播放一个动作（由宿主实现，内部调用对应渲染器）。 */
    interface ActionPlayer {
        fun play(actionId: String, semantic: String)
        fun stop()
    }

    private var resolver: ActionResolver? = null
    private var player: ActionPlayer? = null

    /** **【v0.1.0】**贴边状态探测（宿主注入；未注入时一律视为「不在边上」）。 */
    private var edgeProbe: EdgeProbe? = null

    /** **【工作台】**触发条件准入判定（宿主注入；未注入 = 一律允许）。 */
    private var triggerGate: ((actionId: String, trigger: String) -> Boolean)? = null

    /** **【工作台】**优先级偏置（宿主注入；未注入 = 不加分）。 */
    private var priorityBoost: ((actionId: String) -> Int)? = null

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

    /**
     * **【v0.1.0】**注入贴边探测。
     *
     * 【为什么与 bind 分开】bind 是「必备依赖」，本探测是可选的：
     *   编辑页预览之类没有贴边概念的宿主不需要传，缺省即「不在边上」。
     */
    fun bindEdgeProbe(probe: EdgeProbe?) {
        this.edgeProbe = probe
    }

    /**
     * **【工作台·行为绑定】**注入「这个动作允许被哪些触发条件唤起」的判定。
     *
     * 【为什么放在仲裁层而不是播放层】「允不允许播」是**决策**，
     *   「怎么播」才是播放层的事。把过滤放在这里，所有入口
     *   （随机定时器 / 手势 / 待机回落）都会自动受约束 ——
     *   若放到播放层，就得在七八个调用点各写一遍判断，漏一处就出现
     *   「界面上关掉了，实际还在播」。
     *
     * 【返回 true 的默认语义】没注入、或该动作没设置过绑定 → 允许。
     *   绝大多数动作都是这个状态，老档行为逐像素不变。
     *
     * @param trigger 触发条件名（与 `ActionWorkbench.TRIGGERS` 同一套字符串）
     */
    fun bindTriggerGate(gate: ((actionId: String, trigger: String) -> Boolean)?) {
        this.triggerGate = gate
    }

    /**
     * **【工作台·优先级偏置】**注入「这个动作要不要被额外提权」。
     *
     * 【为什么需要它】动作默认按「语义」排队（拖动 40 > 点击 35 > 手动 30 …）。
     *   但用户可能想让某个随机动作**强行盖住**待机、甚至盖住点击反馈 ——
     *   这是「这个动作特别重要」的产品诉求，靠语义表表达不了。
     *
     * 【返回什么】给「语义优先级」加的分（0 = 不改；40 = 压过一切手动/点击）。
     *   加成而不是替换，是为了保住语义之间的相对关系：
     *   提权后的手动动作仍然打不过提权后的拖动动作。
     */
    fun bindPriorityBoost(boost: ((actionId: String) -> Int)?) {
        this.priorityBoost = boost
    }

    // ============================ 对外行为 ============================

    /**
     * 待机：按「当前是不是贴在边上」播常驻壳或贴边壳。
     *
     * 【v0.1.0 语义变更】改造前这里是「播 idle 语义动作，没有就什么都不做」。
     *   现在 `idle` 已固定绑定常驻壳、`edge` 绑定贴边壳，两者都由 [restoreBase] 裁决 ——
     *   本方法退化成它的别名，保留是因为调用点很多（PetAnimator / PetWindow）。
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
        restoreBase()
    }

    /**
     * **【v0.1.0·新增】回落到「基础态」** —— 这是整套动作语义的收口点。
     *
     * ## 为什么必须按贴边状态分流
     * 「没有交互时播什么」不是一句话能答的：站在屏幕中间和在屏幕边上，
     * 该播的完全是两套动作（常驻 vs 贴边）。改造前所有地方都硬写 `playSemantic(IDLE)`，
     * 于是贴边动作永远没机会播、离边之后又回不到常驻。
     *
     * ## 为什么每次都重读探测值
     * 调用它的时机五花八门（交互结束、动作播完、拖到边上、拖离边缘……），
     * 谁都不知道「此刻在不在边上」。重读一次，永远拿到当下真值。
     *
     * ## 两个壳都空的时候
     * [play] 内部解析为 null → 不改现状。此时画面交回网格形变
     * （呼吸 / 眨眼 / 站立形变本来就在跑），不会出现「死掉的人偶」。
     */
    fun restoreBase() {
        if (released) {
            return
        }
        val atEdge = edgeProbe?.atEdge() == true
        val target = if (atEdge) ActionSemantic.EDGE else ActionSemantic.IDLE
        // 【v0.1.0·修 P0】不能直接走 [play]：贴边是 12、常驻是 10，从贴边离边时
        //   10 < 12 会被优先级裁决**拒绝**，于是人偶离开屏幕边之后仍在播贴边动作
        //   （看起来就是「贴过一次边，之后永远是那个斜着的姿势」）。
        //   「常驻 ↔ 贴边」是同一层的两个基础态，互相之间不该有优先级门槛。
        if (isBaseState(currentSemantic)) {
            // 【工作台·B5 修复】基础态切换也要受「触发条件准入」约束。
            //
            // 【为什么之前漏了】这一段为了绕开优先级门槛（贴边12 vs 常驻10），
            //   直接 `player.play(id, target)`，于是用户在界面里把某个壳的
            //   「常驻待机时 / 靠近屏幕边缘」取消勾选之后，它照样会被播 ——
            //   界面承诺与实际行为不一致。
            //
            // 【拒绝时怎么处理】当作「目标壳没配动作」：把画面还给基础形变。
            //   不能保持原样继续播上一个壳 —— 那会变成「刚从贴边离边，
            //   却还挂着贴边姿势」，正是 [restoreBase] 当初要修的那个 bug。
            val id = resolver?.resolve(target)?.takeIf { gateOk(it, target) }
            if (id != null) {
                currentSemantic = target
                currentActionId = id
                state = PlaybackState.PLAYING
                player?.play(id, target)
            } else {
                // 目标壳是空的：把画面还给基础形变，别让上一个壳的最后一帧留在屏上。
                currentSemantic = null
                currentActionId = null
                state = PlaybackState.IDLE
                player?.stop()
            }
            return
        }
        play(target)
    }

    /** 是否是「基础态」语义（常驻 / 贴边）。两者之间可无条件切换。 */
    private fun isBaseState(semantic: String?): Boolean {
        return semantic == ActionSemantic.IDLE || semantic == ActionSemantic.EDGE
    }

    /**
     * **【工作台】**动作是否允许被该触发条件唤起（没注入 / 没设置过 → 允许）。
     *
     * 【为什么集中成一个私有方法】[play] / [playManualOf] / [restoreBase] 三个入口
     *   都要查这一条；各写一遍迟早出现某条路径漏判，于是「界面关掉了但还在播」。
     */
    private fun gateOk(actionId: String?, trigger: String): Boolean {
        if (actionId.isNullOrEmpty()) {
            return true
        }
        val g = triggerGate ?: return true
        return g(actionId, trigger)
    }

    /**
     * **【工作台】**带用户偏置的实际优先级。
     *
     * 【为什么「加成」而不是「替换成新值」】语义之间的相对顺序（拖动 > 点击 > 手动 > …）
     *   是产品层面的既定规则，不能被单动作设置打乱；用户能做到的只是
     *   「让这个动作在它那一档里更强势」。加成天然保住这个性质。
     */
    private fun effectivePriority(actionId: String?, semantic: String): Int {
        val base = ActionSemantic.priority(semantic)
        if (actionId.isNullOrEmpty()) {
            return base
        }
        val b = priorityBoost ?: return base
        return base + b(actionId)
    }

    /**
     * **【v0.1.0·新增】贴边状态变了**（吸附到边上 / 离开边缘）—— 由窗口层在
     * `enterEdgePeek` / `exitEdgePeek` 之后调用。
     *
     * 【为什么要单独一个入口，而不是让调用方自己 `playSemantic(IDLE)`】
     *   改造后贴边(12) 的优先级高于常驻(10)，从边上离开时那次 IDLE 请求会被裁决
     *   **拒绝**，于是人偶离开边缘后仍播着贴边动作（用户看到的是「贴过一次边就永远是
     *   那个姿势」）。这里直接按新的位置重算基础态，不走优先级门。
     *
     * 【正在播交互 / 手动动作时什么都不做】那些动作有自己的生命周期，
     *   播完由 [onActionFinished] / [onInteractionEnd] 调 [restoreBase] 重新裁决，
     *   此刻硬切会把手势播的动作闹断（「动作闪一下就没了」那类观感）。
     */
    fun onEdgeChanged() {
        if (released) {
            return
        }
        if (!isBaseState(currentSemantic)) {
            return
        }
        restoreBase()
    }

    /**
     * **【v0.1.0·新增】播一个「手动动作」**（手势绑定的动作走这条路）。
     *
     * 【为什么不能直接 `player.play()`】改造前 `PetView.playActionId` 直接调播放层，
     *   绕过了仲裁层 —— 于是 `currentSemantic` 还是旧值，紧接着一次 `interactionEnd`
     *   就把刚播上的动作停掉了（用户看到的「动作闪一帧就消失」）。
     *   走这里：语义被登记为 MANUAL（优先级 30），后续回落也有据可依。
     */
    fun playManual(actionId: String): Boolean {
        return playManualOf(actionId, ActionSemantic.MANUAL)
    }

    /**
     * **【v0.1.0】按指定语义播一个具体动作**（语义由调用方定：手动 / 随机）。
     *
     * 【为什么要「任意语义 + 任意动作」】随机动作组里可能有十几个动作，
     *   而 resolver 只能按语义映射到**一个** id。要让「随机」真的能覆盖整组，
     *   就得允许调用方先挑好动作、再登记语义。手动动作同理（手势里可以绑任意一个）。
     */
    fun playManualOf(actionId: String, semantic: String): Boolean {
        if (released || actionId.isEmpty()) {
            return false
        }
        val p = player ?: return false
        if (!ActionSemantic.isStandard(semantic)) {
            return false
        }
        // 【v0.1.0·修 P0】这里也必须守优先级，否则 13~30 秒的随机定时器会把
        //   用户刚点出来的手动动作（MANUAL 30）/ 点击反馈（TAP 35）当场顶掉 ——
        //   用户看到的正是「动作播一半突然自己换了一个」。
        //   低优先级请求一律拒绝：随机（15）永远抢不过手动 / 点击 / 拖动。
        val incoming = effectivePriority(actionId, semantic)
        val current = currentSemantic?.let { effectivePriority(currentActionId, it) } ?: -1
        if (incoming < current) {
            return false
        }
        // 【工作台】触发条件准入：用户在这个动作上关掉的触发方式不该生效。
        //   放在优先级判定之后 —— 「优先级不够」是更根本的拒绝理由，
        //   先判它可以让「随机动作抢不过手动」这类既有行为完全不受影响。
        if (!gateOk(actionId, semantic)) {
            return false
        }
        currentSemantic = semantic
        currentActionId = actionId
        state = PlaybackState.PLAYING
        p.play(actionId, semantic)
        return true
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
        // 【工作台】触发条件准入：`resolver` 解析出来的那个动作若被用户关掉了
        //   这个触发方式，就当作「该语义没配动作」处理 —— 保持现状而不是硬播。
        if (!gateOk(id, semantic)) {
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
        val id = if (ActionSemantic.isStandard(semantic)) {
            resolver?.resolve(semantic)?.takeIf { gateOk(it, semantic) }
        } else {
            null
        }
        // 【r18·原比例滑动 / v0.1.0 收窄】拖动 / 长按时，**只有拖拽壳真有内容**才播它；
        //   壳是空的时候保持原行为（不腾位、不动当前画面）——
        //   动作帧是「等比缩放铺满画布」，精灵图是「按 petWidth 算」，两者尺寸不一致，
        //   腾位会让画面从动作帧切回精灵图，视觉上就是「缩小」（历史 bug，别踩）。
        if ((ActionSemantic.DRAG == semantic || ActionSemantic.LONG_PRESS == semantic)) {
            if (id == null) {
                // 没配拖拽动作：维持现状（若正在播常驻壳就让它继续播，尺寸不变）。
                return state == PlaybackState.PLAYING
            }
            idleSuspended = true
            currentSemantic = semantic
            currentActionId = id
            state = PlaybackState.PLAYING
            player?.play(id, semantic)
            return true
        }
        idleSuspended = true
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
        // 【v0.1.0】任何动作播完都回落到「基础态」—— 由 restoreBase 按贴边状态分流。
        //   改造前这里写的是「若当前是 idle 就停下，否则 onInteractionEnd」，
        //   而 onInteractionEnd 又硬写 play(IDLE) —— 贴边动作播完会跳回常驻，
        //   人在屏幕边上却播「站立」的动作，非常突兀。
        val wasBase = currentSemantic == ActionSemantic.IDLE ||
            currentSemantic == ActionSemantic.EDGE
        idleSuspended = false
        currentSemantic = null
        currentActionId = null
        state = PlaybackState.IDLE
        player?.stop()
        if (wasBase) {
            // 基础态动作（常驻/贴边）自己播完 → 不必立刻重播同一个；
            //   非循环的基础态动作本来就该停在最后一帧，等下一次事件再回落。
            return
        }
        restoreBase()
    }

    /**
     * 交互结束：回到「基础态」。
     *
     * 【什么时候会被调】拖拽抬手、点击动画播完、长按判定取消等。
     *   只在「确实被挂起过」时才回落 —— 避免无交互时反复触发重播。
     */
    fun onInteractionEnd() {
        if (released) {
            return
        }
        val wasBase = ActionSemantic.IDLE == currentSemantic ||
            ActionSemantic.EDGE == currentSemantic
        if (wasBase) {
            // 本来就在基础态，没什么可回落的。
            return
        }
        idleSuspended = false
        currentSemantic = null
        currentActionId = null
        state = PlaybackState.IDLE
        player?.stop()
        // 回落：按当前贴边状态播常驻或贴边（两个壳都空时什么都不会发生）。
        restoreBase()
    }

    /**
     * **【v0.1.0·新增】强制播一个语义**（无视优先级）。
     *
     * 【谁需要它】睡觉：`sleep` 的优先级是最低的（5，任何交互都能唤醒），
     *   但「进入睡觉」这个动作本身必须能压下正在播的常驻 / 贴边动作 ——
     *   否则用户点了「睡觉」，人偶还在那儿呼吸（看起来就是「点了没反应」）。
     *
     * 【与 [play] 的分工】[play] 是「按优先级裁决」，本方法是「用户明确下令」。
     *   只给睡觉这类「模式切换」用，不要滥用（滥用会让优先级表形同虚设）。
     *
     * @return 是否真的播上了（该人偶没配 sleep 动作时返回 false，由调用方走兜底）。
     */
    fun playForced(semantic: String): Boolean {
        if (released || !ActionSemantic.isStandard(semantic)) {
            return false
        }
        val id = resolver?.resolve(semantic) ?: return false
        currentSemantic = semantic
        currentActionId = id
        state = PlaybackState.PLAYING
        idleSuspended = true
        player?.play(id, semantic)
        return true
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
        edgeProbe = null
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