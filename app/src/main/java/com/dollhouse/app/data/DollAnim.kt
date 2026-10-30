package com.dollhouse.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 【职责】人偶「自定义动作」的数据层：一串帧图 + 名称 + 描述 + 速度，按人偶分库持久化。
 *
 * 【需求原文】「用户现在有一组连贯的动作图片，他能把这组动作图片添加进去，
 *   并且命名为他想要的名字，并且描述也能他自己描述」——
 *   核心就是「帧序列（有序）+ 元数据（名 / 描述 / 速度 / 循环）」这一件事。
 *
 * 【为什么不用 Room】本模块每个动作只存几行元数据，帧图是**文件**。
 *   工程既有的 DollStore / PetPrefs 全是 SharedPreferences + JSON，这里跟随同一约定：
 *   一个 JSON 数组塞进 `feiyu_pet` 的 `doll_anim_<dollId>` 键。
 *
 * 【帧图为什么不进数据库】帧是二进制大对象，塞进 SharedPreferences 会把
 *   单个 xml 撑到几十 MB（读一次全量进内存）。放 filesDir 下按人偶 / 动作分目录，
 *   只存路径。
 *
 * 【内置动作】工程自带的那批动画（呼吸 / 眨眼 / 跳跃 / 表情…）由
 *   [AnimBaker] 用真实渲染管线逐帧烘成 PNG 落盘，登记为 [builtin] 项：
 *   用户能在界面上看到、能试演、能调速、能复制，但不能删（删了就没得看了）。
 *
 * 【线程】读写在主线程（与 PetPrefs 同约束）；解码帧图由调用方放后台。
 */
object DollAnim {

    private const val KEY_PREFIX = "doll_anim_"

    /** 【r13】「内置动作种子版本」的存储键前缀（每只人偶一份）。见 [SEED_VERSION]。 */
    private const val KEY_SEEDV = "doll_anim_seedv_"
    private const val DIR_ANIM = "anim"
    private const val LOG_TAG = "DollhouseAnim"

    /** 速度上下限（帧 / 秒）。12fps 是手绘动画的常规拍子，30fps 已足够顺。 */
    const val FPS_MIN = 1
    const val FPS_MAX = 30
    const val FPS_DEFAULT = 12

    /** 单个动作允许的最大帧数：再多就该做成视频了，而且解码会把内存吃满。 */
    const val FRAMES_MAX = 120

    /** 自动播放时，循环以外的动作「跑完一遍」后停留多久（毫秒）。 */
    const val HOLD_MS = 400L

    /* ---------- 【r16·模型框架】引擎标识 ---------- */

    /**
     * 引擎标识 —— 与 `pet/render/Engine.kt` 的常量**取值一致**。
     *
     * 【为什么这里重复定义字符串而不直接引用 Engine】`DollAnim` 是**数据层**，
     *   渲染层在它之上。数据层引用渲染层会形成反向依赖（数据 → 渲染 → 数据），
     *   以后想单独复用数据层就拆不开了。故：**取值同源、引用解耦**，
     *   并由 `ModelValidator` 校验取值合法性（写错的引擎名进不了库）。
     */
    const val ENGINE_SPRITE = "sprite"
    const val ENGINE_LOTTIE = "lottie"
    const val ENGINE_ANIMIMAGE = "animimage"

    /** 全部已知引擎（校验 / UI 列举用）。 */
    val ENGINES: List<String> = listOf(ENGINE_SPRITE, ENGINE_LOTTIE, ENGINE_ANIMIMAGE)

    /**
     * 【种子版本】内置动作清单的版本号。
     *
     * 【为什么要它】内置动作是「工程自带」的，得能补进老档案；但用户新建的空白模板
     *   必须一个都不要（见 [all] 与 [markEmpty]）。用「记在档案里的种子版本」区分三种情况，
     *   比「缺了就补」精确得多。
     */
    /**
     * 【种子版本】**内置动作清单的版本号**。
     *
     * v3（v0.1.0）：五组体系 + 三个固定壳。老档案（<3）会被补齐三个壳，
     *   并按 [normalizeGroup] 把旧五组的动作翻译成新五组。
     */
    private const val SEED_VERSION = 3

    /** 【种子版本·哨兵】明确表示「这只人偶不要任何内置动作」（新建模板用）。 */
    private const val SEED_NONE = -1

    /* ============================ 属性分组 ============================ */

    /**
     * 动作属性分组。
     *
     * 【为什么是字符串常量而不是枚举】它们直接落进 JSON、直接当界面的分组标题用，
     *   用字符串省掉一次「枚举 ↔ 显示名」的双向映射 —— 少一层映射就少一处不一致。
     */
    /**
     * **【v0.1.0·五组体系】**「组即标签」—— 一个动作属于哪一组，它在列表里就顶着那个名字，
     *   不再有独立的「标签」概念。
     *
     * 【三个固定壳】常驻 / 贴边 / 拖拽是**天生存在**的：每只人偶各一份，不可删、不可换组，
     *   只能「清空帧 → 导入新图 → 改名」。所以「把挥手设为常驻」不是把挥手挪进常驻组，
     *   而是清空常驻壳里原来的内容、把挥手那组图放进去。
     *
     * 【两个不限量的组】随机 / 手动 —— 新建动作只会落在这两组，且**只能在这两组之间换组**。
     *   固定三组不出现在换组下拉里（见 [SWAPPABLE_GROUPS]）。
     */
    const val GROUP_ALWAYS = "常驻动作"
    const val GROUP_EDGE = "贴边动作"
    const val GROUP_DRAG = "拖拽动作"

    /**
     * **【v0.1.5·R-010 新增】贴入瞬间**：人偶刚吸附到屏幕边缘时**只播一次**的动画。
     *
     * 【为什么要和 [GROUP_EDGE] 分开】需求书 18.5 / v2.1·19.3 明确区分两个动作：
     *   · 贴入瞬间（edgeIn）：吸附那一刻播一次，播完进贴边常驻；
     *   · 贴边常驻（edgeLoop，即历史沿用至今的 [GROUP_EDGE]）：贴着边缘期间一直循环。
     *   改造前只有「贴边动作」一个壳，于是「先播一次贴入、再循环」这件事表达不出来 ——
     *   只能二选一，用户看到的要么是「贴边动作被无限循环」、要么是「入边那一下没有过渡」。
     *
     * 【为什么复用「贴边动作」当贴边常驻、而不是把四个都改名】见 v2.1·20.2.4
     *   「任何数据结构变化必须同时考虑旧数据读取」：`贴边动作` 这个**字符串值**已经
     *   写进了千万份存档的 JSON 里，改名就要写一次全量迁移，且历史模型包（manifest
     *   里存的是字符串）会全部失效。保留原值、只**追加**新组，是最小改动且零迁移。
     */
    const val GROUP_EDGE_IN = "贴入瞬间"
    const val GROUP_RANDOM = "随机动作"
    const val GROUP_MANUAL = "手动动作"

    /**
     * 界面上的分组顺序：固定四组永远排最前（常驻 → 拖拽 → 贴入瞬间 → 贴边），
     * 其后随机、手动。
     * 【为什么顺序也要在数据层】界面按这个顺序渲染分组；顺序变了只改这一处。
     *
     * **【v0.1.5·为什么把贴入瞬间排在贴边之前】** 需求书 6.4 的状态转换是
     *   「拖拽 → 贴入瞬间 → 贴边常驻」，界面顺序跟随真实播放顺序，
     *   用户读卡片时看到的就是「会依次发生的事」，不需要额外解释。
     */
    val GROUP_ORDER: List<String> = listOf(
        GROUP_ALWAYS, GROUP_DRAG, GROUP_EDGE_IN, GROUP_EDGE, GROUP_RANDOM, GROUP_MANUAL
    )

    /** 固定壳（不可删 / 不可换组）。顺序与展示顺序一致。 */
    val FIXED_GROUPS: List<String> = listOf(GROUP_ALWAYS, GROUP_DRAG, GROUP_EDGE_IN, GROUP_EDGE)

    /** 允许「新建」与「换组」的组（只有这两个）。 */
    val SWAPPABLE_GROUPS: List<String> = listOf(GROUP_RANDOM, GROUP_MANUAL)

    /** 是否是固定壳组。 */
    @JvmStatic
    fun isFixedGroup(group: String?): Boolean = group != null && FIXED_GROUPS.contains(group)

    /**
     * **【v0.1.0】固定壳的动作 id —— 历史沿用，永不改变。**
     *
     * 【为什么沿用旧 id 而不是新造 `slot_always`】
     *   ① `drag` 与语义 `ActionSemantic.DRAG` 同名 → `bySemantic("drag")` 直接命中，
     *      「拖动时播拖拽动作」这条链路一行都不用改；
     *   ② 旧存档里这三个动作的帧图路径全部保留，用户已经烘好的呼吸 36 帧不会白扔。
     *
     * **【v0.1.5·新增 [SLOT_EDGE_IN_ID]】** 只追加、不改旧值。旧存档里没有这个 id，
     *   [all] 会按 [FIXED_GROUPS] 自动补一个空壳 —— 于是老用户升级后
     *   动作页自然多出「贴入瞬间」这一格（未设置 = 走降级矩阵，不报错）。
     */
    const val SLOT_ALWAYS_ID = "breath"
    const val SLOT_EDGE_ID = "peek"
    const val SLOT_DRAG_ID = "drag"

    /** 【v0.1.5】贴入瞬间壳的动作 id。不复用 `peek`（那已经是贴边常驻）。 */
    const val SLOT_EDGE_IN_ID = "edgein"

    /** 组 → 固定壳 id（非固定组返回 null）。 */
    @JvmStatic
    fun slotIdOf(group: String?): String? = when (group) {
        GROUP_ALWAYS -> SLOT_ALWAYS_ID
        GROUP_EDGE -> SLOT_EDGE_ID
        GROUP_DRAG -> SLOT_DRAG_ID
        GROUP_EDGE_IN -> SLOT_EDGE_IN_ID
        else -> null
    }

    /** 固定壳 id → 组（非壳 id 返回 null）。 */
    @JvmStatic
    fun groupOfSlotId(id: String?): String? = when (id) {
        SLOT_ALWAYS_ID -> GROUP_ALWAYS
        SLOT_EDGE_ID -> GROUP_EDGE
        SLOT_DRAG_ID -> GROUP_DRAG
        SLOT_EDGE_IN_ID -> GROUP_EDGE_IN
        else -> null
    }

    /* ---------- 【v0.1.0·旧档映射】旧五组 → 新五组 ---------- */

    /**
     * 旧组名（v0.0.9 及以前）。**只为读档时翻译用**，新数据不会再写入这些值。
     *
     * 【映射口径（用户已授权「由系统一次性翻译」）】
     * · 待机小动作 → 随机动作（它原本就是 13~30 秒自发触发的）
     * · 常驻动画   → 常驻动作（**仅** id 为 `breath` 的那个）；其余 → 随机动作
     *   （需求书禁止「旧常驻动画全部迁进常驻」，只有呼吸是真的持续默认动作）
     * · 交互动作   → `peek` → 贴边动作、`drag` → 拖拽动作，其余 → 手动动作
     * · 表情       → 手动动作（只能用户主动触发）
     * · 自定义     → 手动动作（它们今天也只有绑手势才能播，归手动不改变任何现有行为）
     */
    private const val OLD_GROUP_IDLE = "待机小动作"
    private const val OLD_GROUP_ALWAYS = "常驻动画"
    private const val OLD_GROUP_TOUCH = "交互动作"
    private const val OLD_GROUP_FACE = "表情"
    private const val OLD_GROUP_CUSTOM = "自定义"

    /**
     * 把（可能是旧档里的）分组值翻译成新五组之一；无法识别时归 [GROUP_MANUAL]。
     *
     * @param id       动作 id（用于识别三个固定壳）
     * @param isDefault 旧档的「设为待机默认动作」开关 —— 为真时它就是常驻壳的内容
     */
    @JvmStatic
    fun normalizeGroup(group: String?, id: String, isDefault: Boolean = false): String {
        if (group != null && GROUP_ORDER.contains(group)) {
            return group
        }
        // 三个固定壳按 id 认领，优先于任何分组字段。
        val bySlot = groupOfSlotId(id)
        if (bySlot != null) {
            return bySlot
        }
        if (isDefault) {
            return GROUP_ALWAYS
        }
        return when (group) {
            OLD_GROUP_IDLE -> GROUP_RANDOM
            OLD_GROUP_ALWAYS -> GROUP_RANDOM
            OLD_GROUP_TOUCH -> GROUP_MANUAL
            OLD_GROUP_FACE -> GROUP_MANUAL
            OLD_GROUP_CUSTOM -> GROUP_MANUAL
            null, "" -> GROUP_MANUAL
            else -> GROUP_MANUAL
        }
    }

    /**
     * 一个动作。
     *
     * @param id      唯一标识。
     * @param name    用户起的名字。
     * @param desc    用户写的描述。
     * @param group   **属性归属**（[GROUP_IDLE] 等）。内置动作由 [Plan.group] 带出；
     *                用户导入的默认归 [GROUP_CUSTOM]。界面按它聚合，不写死任何表。
     * @param frames  帧图绝对路径，**按播放顺序**排列。**允许为空**（先建壳、后加帧）。
     * @param fps     播放速度（帧 / 秒）。
     * @param loop    是否循环播放（false = 跑完一遍停在最后一帧）。
     * @param builtin 内置动作（工程烘焙出来的动画）。**【r16 语义变更】**
     *                从「权限标记」（不可删）降为**「来源标记」**（说明它是工程自带的）。
     *                原因：任务书 §3.6 要求解除删除豁免 —— 用户明确不想要某个内置动作时，
     *                应该能删；删错了可以用 [restoreBuiltin] 一键补回。这比「永远不让删」更合理。
     * @param engine  **【r16 新增】**渲染引擎，取值见 [ENGINE_SPRITE] 等。
     *                默认 `sprite` = 现有的网格形变帧序列，**旧档案读到缺字段即回落此值**。
     * @param isDefault **【r16 新增】**是否为该人偶的「待机默认动作」。
     *                同一人偶内**至多一个**（由 [setDefault] 保证互斥）。
     *                语义：桌宠无交互时优先播它，让「动作页配的动作」真正跑在桌面上。
     * @param sourceId **【r16 新增】**来源模型包标识（来自 manifest 的 `id`）。
     *                空 = 非模型包来源（用户手导 / 内置）。用于「资源管理」页显示出处，
     *                以及卸载模型包时定位它带了哪些动作。
     * @param assetPath **【r16 新增】**非帧序列引擎的**入口资源**（如 Lottie 的 `.json`）。
     *                `sprite` 引擎不用它（其素材是 [frames]）。
     * @param edgeInset **【v0.1.0 新增】**贴边壳专用：人偶宽度有多少比例落在屏幕外
     *                （0.68 = 露 32%）。**只对 [GROUP_EDGE] 有意义**，其余组恒为默认值。
     *                按动作存（不按人偶存）—— 用户「有一套动作，把它添加为贴边动作」之后，
     *                裁截线是那套动作自己的属性。
     */
    data class Action(
        val id: String,
        val name: String,
        val desc: String = "",
        val group: String = GROUP_MANUAL,
        val frames: List<String> = emptyList(),
        val fps: Int = FPS_DEFAULT,
        val loop: Boolean = true,
        val builtin: Boolean = false,
        val engine: String = ENGINE_SPRITE,
        val isDefault: Boolean = false,
        val sourceId: String = "",
        val assetPath: String = "",
        val edgeInset: Float = EDGE_INSET_DEFAULT,
        /**
         * 【本体基准】这组帧里「人偶本体」占画布的相对矩形（`l,t,r,b`，0..1）。
         *
         * 【为什么需要】播放层靠它决定缩放：有基准 → 按「本体宽度 = 窗口宽度」缩放，
         * 各动作一样大；没基准 → 整张画布铺满窗口，于是「画布大而人物小」的动作
         * 在桌面上人偶会明显变小。内置动作烘帧时会写这个值（`AnimBaker`），
         * 用户导入的帧组此前从不写，所以两边的尺子不一样。
         *
         * 【为什么是空串而不是 null】数据类字段用 String 与工程既有风格一致
         * （`sourceId` / `assetPath` 都是这么写的），空串 = 没有基准 = 旧行为。
         * 旧档没有这个键时读出来也是空串，**行为与升级前完全一致**。
         */
        val baseline: String = ""
    )

    /** 贴边裁截线的默认值（= 历史硬编码的 `PetView.EDGE_INSET`，保持老观感）。 */
    const val EDGE_INSET_DEFAULT = 0.68f

    /** 贴边裁截线允许范围：至少露一条边（0.95），最多露满（0）。 */
    const val EDGE_INSET_MIN = 0.0f
    const val EDGE_INSET_MAX = 0.95f

    /** 夹取贴边裁截线（防档被改坏后把人偶整只藏到屏幕外）。 */
    @JvmStatic
    fun clampEdgeInset(v: Float): Float {
        if (v.isNaN()) {
            return EDGE_INSET_DEFAULT
        }
        return Math.max(EDGE_INSET_MIN, Math.min(EDGE_INSET_MAX, v))
    }

    /* ============================ 内置动作定义 ============================ */

    /**
     * 内置动作的「渲染计划」：怎么用真实渲染管线把这个动作逐帧画出来。
     *
     * 【为什么要 plan 而不是直接给帧图】本工程的人偶是**网格形变实时渲染**
     *   （`PetView.buildMesh` + `drawBitmapMesh`），身上根本没有「帧图」这种素材。
     *   所以内置动画只能**当场烘**：按动画自身的参数推进，把每一帧渲染成 PNG。
     *   好处是帧图与桌面上真实跑的效果**逐像素同源**，不是另画一套。
     *
     * @param frames 烘焙多少帧。
     * @param fps    建议速度。
     * @param kind   时间线类型，决定 [AnimBaker] 怎么推进参数。
     * @param group  属性归属（数据里的一等字段，见 [Action.group]）。
     */
    class Plan(
        val id: String,
        val name: String,
        val desc: String,
        val group: String,
        val frames: Int,
        val fps: Int,
        val kind: String,
        val loop: Boolean = true
    )

    const val KIND_BREATH = "breath"
    const val KIND_BLINK = "blink"
    const val KIND_GAZE = "gaze"
    const val KIND_TAIL = "tail"
    const val KIND_AHOGE = "ahoge"
    const val KIND_HAIR = "hair"
    const val KIND_STRETCH = "stretch"
    const val KIND_LOOK = "look"
    const val KIND_SQUINT = "squint"
    const val KIND_NOD = "nod"
    const val KIND_HOP = "hop"
    const val KIND_JUMP = "jump"
    const val KIND_DRAG = "drag"
    const val KIND_LAND = "land"
    const val KIND_PEEK = "peek"
    const val KIND_PERCH = "perch"
    const val KIND_SURPRISED = "surprised"
    const val KIND_HAPPY = "happy"
    const val KIND_DIZZY = "dizzy"

    /**
     * 内置动作清单（**19 个**，与 PetAnimator / PetView 里真实存在的动画分支一一对应）。
     *
     * 【口径】覆盖原「动作」页列出的四个分组：
     *   待机小动作 5 + 常驻动画 6 + 交互动作 5 + 表情 3 = 19。
     *   原页面只是「列出名字」，现在每一项都有可点开、可滑动看帧、可调速的真动画。
     */
    val PLANS: List<Plan> = listOf(
        // ---------- 随机动作（原「待机小动作」，13~30 秒自发触发一次）----------
        Plan(KIND_STRETCH, "伸展", "轻轻弹一下身体并抬高一点点，像伸懒腰。", GROUP_RANDOM, 18, 12, KIND_STRETCH, loop = false),
        Plan(KIND_LOOK, "张望", "左右扫视两轮，像在找什么东西。", GROUP_RANDOM, 28, 12, KIND_LOOK, loop = false),
        Plan(KIND_SQUINT, "眯眼", "眼睛慢慢眯成一条缝再睁开，像困了。", GROUP_RANDOM, 16, 12, KIND_SQUINT, loop = false),
        Plan(KIND_NOD, "点头", "小幅快速上下点头。", GROUP_RANDOM, 14, 12, KIND_NOD, loop = false),
        Plan(KIND_HOP, "小跳", "原地弹起一小段又落回。", GROUP_RANDOM, 10, 14, KIND_HOP, loop = false),
        Plan(KIND_BLINK, "眨眼", "闭合 → 保持 → 睁开，约 220 毫秒一轮。", GROUP_RANDOM, 6, 25, KIND_BLINK, loop = false),
        Plan(KIND_GAZE, "视线漂移", "视线缓慢地左右飘，幅度很小。", GROUP_RANDOM, 30, 10, KIND_GAZE, loop = false),
        Plan(KIND_TAIL, "尾巴摆动", "身后那束头发左右荡，节奏慢。", GROUP_RANDOM, 24, 10, KIND_TAIL, loop = false),
        Plan(KIND_AHOGE, "呆毛摆动", "头顶那根呆毛来回晃，比尾巴快。", GROUP_RANDOM, 20, 12, KIND_AHOGE, loop = false),
        Plan(KIND_HAIR, "头发波动", "整片头发细密地起伏，节奏最快。", GROUP_RANDOM, 18, 14, KIND_HAIR, loop = false),

        // ---------- 常驻动作（固定壳 1：无交互时一直循环）----------
        Plan(KIND_BREATH, "呼吸", "身体幅度极小的上下起伏，一直在跑。", GROUP_ALWAYS, 36, 12, KIND_BREATH),

        // ---------- 贴边动作（固定壳 2：人偶贴在屏幕边、持续悬着时循环）----------
        // 【语义】这是「已经贴在边上悬着」时一直在跑的动效，**不是**进入贴边那一下的过渡。
        //   所以帧里不含姿态倾斜（倾斜由播放层按 lean 施加，见 ActionPlayerCore.leanProvider），
        //   也不分左右：方向只是 lean 的正负号，同一套帧左右通用 ——
        //   复制两份一模一样的帧只会让「改一次要改两处」成为将来的坑。
        Plan(KIND_PEEK, "贴边悬停", "贴在屏幕边悬着时的轻微呼吸与摆发，偶尔眨一下眼。", GROUP_EDGE, 32, 12, KIND_PEEK),

        // ---------- 拖拽动作（固定壳 3：被拎起来拖动时循环）----------
        Plan(KIND_DRAG, "拖拽", "被拎起来时整体抬高、轻微摇晃。", GROUP_DRAG, 12, 12, KIND_DRAG),

        // ---------- 手动动作（只能由手势 / 快捷入口主动触发）----------
        Plan(KIND_JUMP, "跳跃", "被点一下时原地跳起（伴随 ♪ 表情）。", GROUP_MANUAL, 7, 16, KIND_JUMP, loop = false),
        Plan(KIND_LAND, "落地", "放下的那一下压扁再弹回。", GROUP_MANUAL, 10, 16, KIND_LAND, loop = false),
        Plan(KIND_PERCH, "趴姿", "身体前倾压低、眼睛半闭，趴在边上。", GROUP_MANUAL, 12, 10, KIND_PERCH, loop = false),
        Plan(KIND_SURPRISED, "惊讶", "身体一弹，头顶冒出「！」，被拖拽时触发。", GROUP_MANUAL, 11, 10, KIND_SURPRISED, loop = false),
        Plan(KIND_HAPPY, "开心", "轻轻弹跳，头顶冒出「♪」，跳跃时触发。", GROUP_MANUAL, 11, 10, KIND_HAPPY, loop = false),
        Plan(KIND_DIZZY, "迷糊", "身体左右打晃，头顶冒出「？」，落地时触发。", GROUP_MANUAL, 11, 10, KIND_DIZZY, loop = false)
    )

    fun planOf(id: String): Plan? {
        for (p in PLANS) {
            if (p.id == id) {
                return p
            }
        }
        return null
    }

    /* ============================ 读取 ============================ */

    /**
     * 这只人偶的全部动作。
     *
     * 【v0.1.0·三个固定壳永远存在】不管这只人偶是不是空白模板，常驻 / 贴边 / 拖拽
     *   三个壳都必须有 —— 它们不是「内置动作」，而是**动作体系的三个固定位**。
     *   缺了就补（空壳：无帧、默认名字、默认速度）。
     *
     * 【19 个内置动作只补一次】老档案（种子版本 < 3）会补齐全部 19 个；
     *   空白模板（[markEmpty] 打过 [SEED_NONE] 标记）只补三个壳、一个内置动作都不给。
     */
    @JvmStatic
    @Synchronized
    fun all(ctx: Context, dollId: String?): List<Action> {
        val list = load(ctx, dollId)
        val v = seedVersion(ctx, dollId)
        var changed = false

        // ① 三个固定壳：无条件补齐（连空白模板也补）。
        for (g in FIXED_GROUPS) {
            val sid = slotIdOf(g) ?: continue
            if (!containsId(list, sid)) {
                val p = planOf(sid)
                list.add(
                    Action(
                        id = sid,
                        name = p?.name ?: g,
                        desc = p?.desc ?: "",
                        group = g,
                        fps = p?.fps ?: FPS_DEFAULT,
                        // 【固定壳必须循环】常驻 / 贴边 / 拖拽都是「持续态」，
                        //   播完定格在那里等于卡死（拖动时长不可控尤其明显）。
                        loop = true,
                        builtin = true,
                        engine = ENGINE_SPRITE
                    )
                )
                changed = true
            }
        }

        // ② 内置动作：老档案补齐，空白模板不补。
        if (v != SEED_NONE && v < SEED_VERSION) {
            for (p in PLANS) {
                if (!containsId(list, p.id)) {
                    // 【固定三组的壳已经在 ① 建过了】这里只补随机 / 手动两组的 16 个。
                    if (isFixedGroup(p.group)) {
                        continue
                    }
                    list.add(
                        Action(
                            id = p.id, name = p.name, desc = p.desc, group = p.group,
                            fps = p.fps, loop = p.loop, builtin = true,
                            engine = ENGINE_SPRITE, isDefault = false, sourceId = "", assetPath = ""
                        )
                    )
                    changed = true
                }
            }
            setSeedVersion(ctx, dollId, SEED_VERSION)
            changed = true
            // 【v0.1.0·一次性迁移：把随机开关打开】
            //   改造前桌宠跑的是 `PetAnimator` 里硬编码的 5 个待机小动作，升级后
            //   这些动作被归位进「随机动作」组、改由开关控制。开关默认关是用户定案，
            //   但对**老档案**（种子版本 < 3）来说，默认关等于「升级后人偶再也不发呆」——
            //   那是可见的功能退化。所以只在**这次迁移**里替老档案打开一次：
            //   · 已显式设置过的（用户自己关过 / 开过）不动它；
            //   · 全新空白模板走的是另一条分支（SEED_NONE），不会到这里。
            if (!PetPrefs.randomActionSet(ctx, dollId)) {
                PetPrefs.setRandomAction(ctx, dollId, true)
            }
        }

        if (changed) {
            save(ctx, dollId, list)
        }
        return ArrayList(list)
    }

    private fun containsId(list: List<Action>, id: String): Boolean {
        for (a in list) {
            if (a.id == id) {
                return true
            }
        }
        return false
    }

    /**
     * 把这只人偶标记成「明确不要内置动作」（新建模板用）。
     *
     * 【为什么在数据层提供】DollStore 新建模板时调一次，之后 [all] 就一个内置动作都不补。
     *   标记必须在**建模板那一刻**落下 —— 若等用户第一次进动作页才落，
     *   那次 [all] 已经先把内置动作补进去了，等于没生效。
     */
    @JvmStatic
    @Synchronized
    fun markEmpty(ctx: Context, dollId: String?) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        setSeedVersion(ctx, dollId, SEED_NONE)
    }

    /**
     * **【本轮·修「空白模板还是有动作」】**这只人偶是不是「空白模板」（明确不要内置动作）。
     *
     * ## 为什么需要它
     *
     * [all] 会给空白模板补三个固定壳（空壳、无帧），这是设计如此 ——
     *   三个固定位是动作体系的柱子，不属于「内置动作」。
     *   但壳上的 `builtin` 标记是 `true`，而动作详情页看到 `builtin` 就会
     *   **自动烘一次内置动画**（`AnimBaker.ensure`）把帧填进去。
     *   于是用户新建的空白模板，一进「固定位」就看到「呼吸 36 帧」——
     *   明明一个动作都没加，内容却凭空长出来了。
     *
     * 修法不是把壳的 `builtin` 改成 false（那会让界面上把固定位标成「自定义」，
     *   语义也不对），而是**让调用方先问一句「这是不是空白模板」**，
     *   是的话就不自动烘帧，如实显示「点一下去添加帧」。
     *
     * @return true = 空白模板（[markEmpty] 打过标记且从未恢复内置）
     */
    @JvmStatic
    fun isBlankTemplate(ctx: Context, dollId: String?): Boolean {
        if (dollId.isNullOrEmpty()) {
            return false
        }
        return seedVersion(ctx, dollId) == SEED_NONE
    }

    /** 按 id 取一个动作。 */
    @JvmStatic
    @Synchronized
    fun byId(ctx: Context, dollId: String?, id: String?): Action? {
        if (id == null) {
            return null
        }
        for (a in all(ctx, dollId)) {
            if (a.id == id) {
                return a
            }
        }
        return null
    }

    /* ============================ 写入 ============================ */

    /**
     * 按属性分组聚合（界面直接用它渲染，顺序取 [GROUP_ORDER]，空组不出现）。
     *
     * 【为什么放在数据层】「哪个动作属于哪个属性」是数据事实，聚合规则只该有一份；
     *   放在界面里迟早出现「动作页按一套分、别处按另一套分」。
     */
    @JvmStatic
    fun grouped(ctx: Context, dollId: String?): List<Pair<String, List<Action>>> {
        val list = all(ctx, dollId)
        val out = ArrayList<Pair<String, List<Action>>>()
        val used = HashSet<String>()
        for (g in GROUP_ORDER) {
            val sub = ArrayList<Action>()
            for (a in list) {
                if (a.group == g) {
                    sub.add(a)
                }
            }
            if (sub.isNotEmpty()) {
                out.add(Pair(g, sub))
                used.add(g)
            }
        }
        // 兜底：档案里出现了 [GROUP_ORDER] 之外的属性值（手工改过档 / 未来新增的属性）
        // 也要显示出来，绝不静默吞掉 —— 吞掉就是用户看到的「动作凭空少了」。
        val extra = ArrayList<String>()
        for (a in list) {
            if (!used.contains(a.group) && !extra.contains(a.group)) {
                extra.add(a.group)
            }
        }
        for (g in extra) {
            val sub = ArrayList<Action>()
            for (a in list) {
                if (a.group == g) {
                    sub.add(a)
                }
            }
            if (sub.isNotEmpty()) {
                out.add(Pair(g, sub))
            }
        }
        return out
    }

    /**
     * 新增一个动作（用户导入的那组图，或先建一个空壳）。
     *
     * 【v0.1.0】**新建只能落在随机 / 手动两组**。固定三组（常驻 / 贴边 / 拖拽）
     *   是天生存在的壳，不出现在「新建」入口里 —— 想换它们的只能用「清空 + 导入」。
     *
     * @param group  目标分组（只认 [SWAPPABLE_GROUPS]，其它值一律按 [GROUP_MANUAL]）
     * @param frames 已落到本应用私有目录的帧图路径（按顺序）；可为空。
     * @return 新建项；只有 dollId 为空才返回 null。
     */
    @JvmStatic
    @Synchronized
    fun add(ctx: Context, dollId: String?, name: String, desc: String,
            frames: List<String>, fps: Int, loop: Boolean,
            group: String = GROUP_MANUAL): Action? {
        if (dollId.isNullOrEmpty()) {
            return null
        }
        val list = load(ctx, dollId)
        val g = if (SWAPPABLE_GROUPS.contains(group)) group else GROUP_MANUAL
        val a = Action(
            id = newId(),
            name = uniqueName(list, name.ifBlank { "新动作" }),
            desc = desc,
            group = g,
            frames = ArrayList(frames),
            fps = clampFps(fps),
            loop = loop,
            builtin = false
        )
        list.add(a)
        save(ctx, dollId, list)
        return a
    }

    /**
     * 整体覆盖一项（改名 / 改描述 / 改速度 / 改循环 / 换帧序列 / 换属性 / 贴边裁截线）。
     *
     * 【v0.1.0 约束】
     * · 固定壳的 id 与 builtin 位不可改（同改造前）；
     * · **固定壳不能换组**（否则它就不在固定位上了）；
     * · **随机 / 手动不能换进固定组**（固定组只留给它的壳）；
     * · 固定壳的 loop 强制为 true（常驻/贴边/拖拽是持续态，播完定格=卡死）。
     */
    @JvmStatic
    @Synchronized
    fun update(ctx: Context, dollId: String?, action: Action?) {
        if (dollId.isNullOrEmpty() || action == null) {
            return
        }
        val list = load(ctx, dollId)
        for (i in list.indices) {
            if (list[i].id != action.id) {
                continue
            }
            val old = list[i]
            val oldFixed = isFixedGroup(old.group)
            val wantGroup = if (action.group.isBlank()) old.group else action.group
            // 换组合法性：固定壳原地不动；普通动作只能进随机 / 手动。
            val group = when {
                oldFixed -> old.group
                SWAPPABLE_GROUPS.contains(wantGroup) -> wantGroup
                else -> old.group
            }
            list[i] = action.copy(
                id = old.id,
                builtin = old.builtin,
                name = action.name.ifBlank { old.name },
                group = group,
                fps = clampFps(action.fps),
                loop = if (isFixedGroup(group)) true else action.loop,
                edgeInset = clampEdgeInset(action.edgeInset)
            )
            save(ctx, dollId, list)
            return
        }
    }

    /**
     * 换组：**只允许在随机 / 手动之间换**。
     *
     * 【为什么单独一个入口】界面上的「属性」下拉只列这两项，调用点直接走本方法，
     *   不必在 UI 层再判一遍合法性（数据层才是唯一真源）。
     *
     * @return 是否真的改了。
     */
    @JvmStatic
    @Synchronized
    fun moveToGroup(ctx: Context, dollId: String?, id: String?, group: String): Boolean {
        if (dollId.isNullOrEmpty() || id == null || !SWAPPABLE_GROUPS.contains(group)) {
            return false
        }
        val list = load(ctx, dollId)
        for (i in list.indices) {
            if (list[i].id != id) {
                continue
            }
            if (isFixedGroup(list[i].group) || list[i].group == group) {
                return false
            }
            list[i] = list[i].copy(group = group)
            save(ctx, dollId, list)
            return true
        }
        return false
    }

    /** 落一次贴边裁截线（只对贴边壳有意义，其它动作直接拒绝）。 */
    @JvmStatic
    @Synchronized
    fun setEdgeInset(ctx: Context, dollId: String?, id: String?, v: Float): Boolean {
        if (dollId.isNullOrEmpty() || id == null) {
            return false
        }
        val list = load(ctx, dollId)
        for (i in list.indices) {
            if (list[i].id != id) {
                continue
            }
            if (list[i].group != GROUP_EDGE) {
                return false
            }
            list[i] = list[i].copy(edgeInset = clampEdgeInset(v))
            save(ctx, dollId, list)
            return true
        }
        return false
    }

    /** 贴边壳的裁截线（没配过 / 没有壳 → 默认 0.68）。 */
    @JvmStatic
    fun edgeInset(ctx: Context, dollId: String?): Float {
        val a = byId(ctx, dollId, SLOT_EDGE_ID)
        return clampEdgeInset(a?.edgeInset ?: EDGE_INSET_DEFAULT)
    }

    /**
     * 清空某个固定壳的内容：**只清帧**，名字 / 描述 / 速度 / 裁截线全部保留。
     *
     * 【用户定案】「只允许修改、清空，不允许删除」「清空时只清帧，名字/描述/速度保留」——
     *   清空是为了腾出位置放新的一套动作，不是为了把壳变回出厂样子。
     *
     * @return 是否真的清掉了什么。
     */
    @JvmStatic
    @Synchronized
    fun clearFrames(ctx: Context, dollId: String?, id: String?): Boolean {
        if (dollId.isNullOrEmpty() || id == null) {
            return false
        }
        val list = load(ctx, dollId)
        for (i in list.indices) {
            if (list[i].id != id) {
                continue
            }
            if (!isFixedGroup(list[i].group)) {
                return false
            }
            if (list[i].frames.isEmpty() && list[i].assetPath.isEmpty()) {
                return false
            }
            // 连带删盘上的帧文件：留着就是孤儿目录，且「清空后重新导入」会与旧帧混在一起。
            try {
                val d = File(File(animRoot(ctx), dollId), id)
                val kids = d.listFiles()
                if (kids != null) {
                    for (f in kids) {
                        f.delete()
                    }
                }
            } catch (ignored: Throwable) {
            }
            list[i] = list[i].copy(
                frames = emptyList(), assetPath = "", engine = ENGINE_SPRITE,
                sourceId = "", baseline = ""
            )
            save(ctx, dollId, list)
            return true
        }
        return false
    }

    /**
     * 从某个组里随机挑一个**可播放**的动作（[Animator] 的随机动作定时器用）。
     *
     * 【允许连着播同一个】用户明确定案「允许连续同一动作」，所以这里**不做去重**。
     * 【可播放 = 有帧或有入口资源】空壳播起来什么都不会发生，抽到它等于随机动作没生效。
     */
    @JvmStatic
    fun pickRandom(ctx: Context, dollId: String?): Action? {
        val pool = ArrayList<Action>()
        for (a in all(ctx, dollId)) {
            if (a.group == GROUP_RANDOM && (a.frames.isNotEmpty() || a.assetPath.isNotEmpty())) {
                pool.add(a)
            }
        }
        if (pool.isEmpty()) {
            return null
        }
        return pool[(Math.random() * pool.size).toInt().coerceIn(0, pool.size - 1)]
    }

    /** 某个组里有没有可播放的动作（随机开关能否打开 / 贴边壳是否空）。 */
    @JvmStatic
    fun hasPlayable(ctx: Context, dollId: String?, group: String): Boolean {
        for (a in all(ctx, dollId)) {
            if (a.group == group && (a.frames.isNotEmpty() || a.assetPath.isNotEmpty())) {
                return true
            }
        }
        return false
    }

    /**
     * 清空这只人偶的**全部动作**（删人偶 / 重建出厂默认配置时用）。
     *
     * 【为什么要连种子版本一起清】种子版本是「内置动作清单补到第几版」的游标：
     *   只清动作不重置游标，下次 `all()` 会认为「已经补过了」而什么都不补 ——
     *   用户看到的是一只**一个动作都没有**的人偶（连三个固定壳都没有）。
     *   所以这里一并把游标归零，让 `all()` 重新走一遍完整补齐。
     *
     * 【帧图也一起删】动作都没了，帧文件留着就是孤儿目录。
     */
    @JvmStatic
    @Synchronized
    fun clearAll(ctx: Context, dollId: String?) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        try {
            PetPrefs.get(ctx).edit().remove(key(dollId)).remove(seedKey(dollId)).apply()
        } catch (t: Throwable) {
        }
        // 盘上的帧目录：整只人偶的 anim 目录一起删。
        try {
            val d = File(animRoot(ctx), dollId)
            if (d.isDirectory) {
                deleteRecursively(d)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun deleteRecursively(d: File) {
        val kids = d.listFiles() ?: return
        for (f in kids) {
            if (f.isDirectory) {
                deleteRecursively(f)
            } else {
                f.delete()
            }
        }
        d.delete()
    }

    /**
     * 删除一个动作，返回是否真的删掉了。
     *
     * 【v0.1.0·三个固定壳不许删】常驻 / 贴边 / 拖拽是每只人偶天生就有的固定位，
     *   删掉之后「常驻」这个概念就无处落地了。用户对它们的操作只有两件事：
     *   **修改**（改名 / 换帧 / 调速）和 **清空**（只清帧，见 [clearFrames]）。
     *
     * 【r16 语义·builtin 不再是权限】普通动作一律可删（含内置的 16 个），
     *   `builtin` 只说明「它是工程自带的」。删错了由「删人偶 → 重建出厂配置」兜底。
     */
    /**
     * 【R-112 九.4】删除前判定：这一删会不会让角色「一个可播放动作都不剩」。
     *
     * 三种结果：
     *  - [LastActionCheck.NOT_LAST]  删了还有别的可播动作，正常删。
     *  - [LastActionCheck.NEED_IDLE] 删完就空了，但角色有形象图 —— 允许删，
     *    删除方随后应调 [ensureAtLeastOneAction] 自动生成 1 帧待机。
     *  - [LastActionCheck.BLOCK]     删完就空了且没有形象图 —— **必须阻止**，
     *    提示「至少保留一个动作」。
     *
     * 【为什么判断放在数据层而不是 UI】数据规则要求「不允许出现零可播动作的角色」。
     *   放在 UI 会有第二入口（批量清空 / 卸载模型包）绕过去；放在这里，任何删除路径
     *   都必须先过这一关。
     */
    enum class LastActionCheck { NOT_LAST, NEED_IDLE, BLOCK }

    @JvmStatic
    fun checkBeforeRemove(ctx: Context, dollId: String?, id: String?): LastActionCheck {
        if (dollId.isNullOrEmpty()) {
            return LastActionCheck.NOT_LAST
        }
        val list = load(ctx, dollId)
        // 「可播放」= 有帧或有入口资源。空壳不算（它本来就画不出东西）。
        var playable = 0
        var removingPlayable = false
        for (a in list) {
            val ok = a.frames.isNotEmpty() || a.assetPath.isNotEmpty()
            if (!ok) continue
            playable++
            if (a.id == id) removingPlayable = true
        }
        // 删的不是可播动作，或者删完仍有可播动作 —— 都不算「最后一个」。
        if (!removingPlayable || playable > 1) {
            return LastActionCheck.NOT_LAST
        }
        // 只剩这一个可播动作。看角色有没有形象图可兜底。
        val doll = DollStore.byId(ctx, dollId)
        return if (doll != null && doll.sprite.isNotEmpty()) {
            LastActionCheck.NEED_IDLE
        } else {
            LastActionCheck.BLOCK
        }
    }

    /**
     * 【R-112 九.4 前半】删除后兜底：角色一个可播动作都不剩时，用形象图自动生成 1 帧待机。
     *
     * @return true = 已确保至少有一个可播动作（本来就有 / 刚生成）；
     *         false = 无法保证（没有形象图）。
     *
     * 【为什么写进常驻壳而不是新建一个动作】常驻壳是「待机」的唯一真源。
     *   新建一个普通动作当待机，语义上是错的 —— 它不会在无交互时自动播。
     */
    @JvmStatic
    @Synchronized
    fun ensureAtLeastOneAction(ctx: Context, dollId: String?): Boolean {
        if (dollId.isNullOrEmpty()) {
            return false
        }
        val list = load(ctx, dollId)
        for (a in list) {
            if (a.frames.isNotEmpty() || a.assetPath.isNotEmpty()) {
                return true
            }
        }
        val doll = DollStore.byId(ctx, dollId) ?: return false
        if (doll.sprite.isEmpty()) {
            return false
        }
        // 常驻壳可能在 / 可能不在（老档案被手工删过）。两种都要能写。
        val idle = byId(ctx, dollId, SLOT_ALWAYS_ID)
        val target = idle ?: Action(
            id = SLOT_ALWAYS_ID,
            name = "待机",
            group = GROUP_ALWAYS,
            loop = true,
            builtin = true
        )
        val updated = target.copy(frames = listOf(doll.sprite), loop = true)
        if (idle == null) {
            list.add(updated)
        } else {
            for (i in list.indices) {
                if (list[i].id == SLOT_ALWAYS_ID) {
                    list[i] = updated
                    break
                }
            }
        }
        save(ctx, dollId, list)
        return true
    }

    @JvmStatic
    @Synchronized
    fun remove(ctx: Context, dollId: String?, id: String?): Boolean {
        if (dollId.isNullOrEmpty() || id == null) {
            return false
        }
        // 固定壳一律拒绝。
        if (groupOfSlotId(id) != null) {
            return false
        }
        val list = load(ctx, dollId)
        var hit: Action? = null
        for (a in list) {
            if (a.id == id) {
                hit = a
                break
            }
        }
        if (hit == null) {
            return false
        }
        list.remove(hit)
        save(ctx, dollId, list)
        // 帧图是用户导入时拷进来的，删动作要一并删文件，否则盘上永远留着孤儿目录。
        try {
            // 【不能调 framesDir】它会 mkdirs；删目录时不该先把目录建出来。
            val d = File(File(animRoot(ctx), dollId), id)
            if (d.isDirectory) {
                val kids = d.listFiles()
                if (kids != null) {
                    for (f in kids) {
                        f.delete()
                    }
                }
                d.delete()
            }
        } catch (t: Throwable) {
        }
        // 【R-112 九.4】删完兜底：一个可播动作都不剩时用形象图生成 1 帧待机。
        ensureAtLeastOneAction(ctx, dollId)
        IdleFallback.invalidate()
        return true
    }

    /**
     * **【r16 新增】**把工程自带的 19 个内置动作补回（幂等）。
     *
     * 【为什么要它】[remove] 现在允许删内置动作了。删是用户的权利，
     *   但必须给一条「反悔」的路 —— 否则误删之后就再也没有了
     *   （内置动作的 Plan 定义在代码里，用户手上没有源素材可导）。
     *
     * 【幂等】已存在的按 id 跳过，不会重复添加，也不会覆盖用户改过的名字 / 速度。
     *
     * 【本轮·语义升级为「恢复默认配置」】原来它只补「缺的」，还顺手把种子版本
     *   写成当前版 —— 于是**空白模板一旦点过它，就永久不再是空白模板**了
     *   （[isBlankTemplate] 从此返回 false，以后每次进动作页都会看到 19 个内置动作）。
     *   现在它在补完之后显式把种子版本落到 [SEED_VERSION]，
     *   这正是「恢复出厂」该有的效果：**这只人偶从此等同于新装软件自带的默认配置**。
     *   同时把用户自己加的动作（`builtin == false`）保留 —— 恢复默认不该等于清空用户的东西。
     *
     * @return 本次补回的数量。
     */
    @JvmStatic
    @Synchronized
    fun restoreBuiltin(ctx: Context, dollId: String?): Int {
        if (dollId.isNullOrEmpty()) {
            return 0
        }
        val list = load(ctx, dollId)
        var n = 0
        for (p in PLANS) {
            var found = false
            for (a in list) {
                if (a.id == p.id) {
                    found = true
                    break
                }
            }
            if (!found) {
                list.add(
                    Action(
                        id = p.id, name = p.name, desc = p.desc, group = p.group,
                        fps = p.fps, loop = p.loop, builtin = true
                    )
                )
                n++
            }
        }
        // 【即使一个都没补也要落版本】见上面的注释：这一步才是「恢复出厂」的实质 ——
        //   把空白模板的 SEED_NONE 标记翻成当前种子版本，之后 [all] 按老档案对待它。
        save(ctx, dollId, list)
        setSeedVersion(ctx, dollId, SEED_VERSION)
        return n
    }

    /**
     * 设为该人偶的「待机默认动作」。
     *
     * **【v0.1.0 已废弃】**这个开关的职能被「常驻动作固定壳」完全取代：
     *   常驻壳天生存在、天生循环、天生就是无交互时播的那一个，不再需要第二套「默认」概念。
     *   为了让「装了新版又退回旧版」的路径仍然可读，`is_default` 仍会随档写出，
     *   取值恒等于「本动作是否是常驻壳」（见 [save] / [load]）。
     *
     * 保留本方法只为不破坏既有调用点的编译；它**不再产生任何效果**。
     */
    @Deprecated("常驻动作固定壳取代了「待机默认动作」，此方法不再有行为")
    @JvmStatic
    @Synchronized
    fun setDefault(ctx: Context, dollId: String?, id: String?) {
        // 空实现：见上面的说明。
    }

    /**
     * 取该人偶的「待机默认动作」= **常驻壳**（没内容时返回 null）。
     *
     * 【为什么返回的是壳而不是某个 is_default 标记】壳是唯一真源。
     *   若壳里没内容，就不该有默认动作 —— 画面交回基础形变（呼吸/眨眼本来就在跑）。
     */
    @JvmStatic
    fun defaultAction(ctx: Context, dollId: String?): Action? {
        val a = byId(ctx, dollId, SLOT_ALWAYS_ID) ?: return null
        if (a.frames.isEmpty() && a.assetPath.isEmpty()) {
            return null
        }
        return a
    }

    /** 取某个固定壳（常驻 / 贴边 / 拖拽）。 */
    @JvmStatic
    fun bySlot(ctx: Context, dollId: String?, group: String): Action? {
        val sid = slotIdOf(group) ?: return null
        return byId(ctx, dollId, sid)
    }

    /**
     * 按语义 ID 找动作。
     *
     * 【v0.1.0·语义 → 固定壳】四个状态语义直接落到四个壳上：
     * · `idle` → 常驻壳 · `edge` → 贴边壳 · `drag` → 拖拽壳
     * · **`edge_in`（v0.1.5）→ 贴入瞬间壳**
     * 其余语义（tap / longpress / appear / disappear / sleep）仍按「动作 id 与语义同名」匹配
     * （模型包导入时就是按这个约定生成 id 的）。
     *
     * 【空壳必须返回 null】壳里没内容时播放层没有任何东西可画；返回一个空动作会让
     *   仲裁层误以为「播上了」，把画面从网格形变抢走却又画不出东西 —— 人偶会「消失」。
     *   **这也正是「贴入瞬间」缺失时的降级入口**：返回 null ⇒ 仲裁层直接跳到贴边常驻
     *   （需求书 19.3 降级矩阵第 3 行），不需要额外的 if 判断。
     */
    @JvmStatic
    fun bySemantic(ctx: Context, dollId: String?, semantic: String): Action? {
        if (semantic.isBlank()) {
            return null
        }
        val id = when (semantic) {
            "idle" -> SLOT_ALWAYS_ID
            "edge" -> SLOT_EDGE_ID
            "drag" -> SLOT_DRAG_ID
            "edge_in" -> SLOT_EDGE_IN_ID
            else -> semantic
        }
        val hit = byId(ctx, dollId, id) ?: return null
        if (hit.frames.isEmpty() && hit.assetPath.isEmpty()) {
            return null
        }
        return hit
    }

    /**
     * **【r16 新增】**登记一个「非帧序列」动作（模型包导入用）。
     *
     * 【为什么与 [add] 分开】[add] 是「用户自己的那组帧图」这条路，
     *   它无条件把 group 设为「自定义」；模型包导入要**指定分组 / 引擎 / 入口资源 /
     *   来源标识**，参数集不同，混在一个方法里会让两条路互相牵制。
     */
    @JvmStatic
    @Synchronized
    fun addEngineAction(ctx: Context, dollId: String?, action: Action): Boolean {
        if (dollId.isNullOrEmpty()) {
            return false
        }
        val list = load(ctx, dollId)
        var engine = action.engine
        if (!ENGINES.contains(engine)) {
            engine = ENGINE_SPRITE
        }
        val item = action.copy(
            id = if (action.id.isBlank()) newId() else action.id,
            name = uniqueName(list, action.name.ifBlank { "新动作" }),
            engine = engine,
            fps = clampFps(action.fps)
        )
        // 同 id 覆盖（模型包重装 / 升级时走这条，避免留下两份同名动作）。
        for (i in list.indices) {
            if (list[i].id == item.id) {
                list[i] = item.copy(builtin = list[i].builtin)
                save(ctx, dollId, list)
                return true
            }
        }
        list.add(item)
        save(ctx, dollId, list)
        return true
    }

    /**
     * **【r16 新增】**删除某个模型包带来的全部动作（卸载模型包）。
     *
     * @return 被删掉的动作数。
     */
    @JvmStatic
    @Synchronized
    fun removeBySource(ctx: Context, dollId: String?, sourceId: String): Int {
        if (dollId.isNullOrEmpty() || sourceId.isBlank()) {
            return 0
        }
        val list = load(ctx, dollId)
        val keep = ArrayList<Action>(list.size)
        var n = 0
        for (a in list) {
            if (a.sourceId == sourceId) {
                n++
                // 【顺手清帧图】与 [remove] 同口径，不留孤儿目录。
                try {
                    val d = File(File(animRoot(ctx), dollId), a.id)
                    val kids = d.listFiles()
                    if (kids != null) {
                        for (f in kids) {
                            f.delete()
                        }
                    }
                    d.delete()
                } catch (ignored: Throwable) {
                }
            } else {
                keep.add(a)
            }
        }
        if (n > 0) {
            save(ctx, dollId, keep)
        }
        return n
    }

    /** **【r16 新增】**某只人偶是否设了待机默认动作（供帧率策略 / 行为控制器查询）。 */
    @JvmStatic
    fun hasDefault(ctx: Context, dollId: String?): Boolean = defaultAction(ctx, dollId) != null

    /* ============================ 文件布局 ============================ */

    /** 本 App 私有目录下「某只人偶的帧图根目录」。 */
    @JvmStatic
    fun animRoot(ctx: Context): File {
        val d = File(ctx.filesDir, "doll/$DIR_ANIM")
        if (!d.exists()) {
            d.mkdirs()
        }
        return d
    }

    /** 某个动作的帧图目录。 */
    @JvmStatic
    fun framesDir(ctx: Context, dollId: String, actionId: String): File {
        val d = File(File(animRoot(ctx), dollId), actionId)
        if (!d.exists()) {
            d.mkdirs()
        }
        return d
    }

    /* ============================ 内部 ============================ */

    private fun key(dollId: String): String = KEY_PREFIX + dollId

    private fun seedKey(dollId: String): String = KEY_SEEDV + dollId

    /** 读种子版本；没登记过返回 0（= 老档案，需要补全部内置动作）。 */
    private fun seedVersion(ctx: Context, dollId: String?): Int {
        if (dollId.isNullOrEmpty()) {
            return SEED_VERSION
        }
        return try {
            PetPrefs.get(ctx).getInt(seedKey(dollId), 0)
        } catch (t: Throwable) {
            0
        }
    }

    private fun setSeedVersion(ctx: Context, dollId: String?, v: Int) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        try {
            PetPrefs.get(ctx).edit().putInt(seedKey(dollId), v).apply()
        } catch (t: Throwable) {
        }
    }

    private fun load(ctx: Context, dollId: String?): MutableList<Action> {
        val out = ArrayList<Action>()
        if (dollId.isNullOrEmpty()) {
            return out
        }
        val raw = PetPrefs.get(ctx).getString(key(dollId), "")
        if (raw.isNullOrEmpty()) {
            return out
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) {
                    continue
                }
                val fa = o.optJSONArray("frames")
                val frames = ArrayList<String>()
                if (fa != null) {
                    for (j in 0 until fa.length()) {
                        val p = fa.optString(j, "")
                        if (p.isNotEmpty()) {
                            frames.add(p)
                        }
                    }
                }
                // 【属性迁移·v0.1.0】老档案里是旧五组名（待机小动作 / 常驻动画 / 交互动作 /
                //   表情 / 自定义）。这里一次性翻译成新五组，**不写独立迁移器、不改版本号**：
                //   读的时候顺手翻，下次 save 落盘就是新值。
                //   三个固定壳按 id 认领（breath / peek / drag），优先于任何分组字段。
                val isDefaultFlag = o.optBoolean("is_default", false)
                var group = normalizeGroup(o.optString("group", ""), id, isDefaultFlag)
                // 【固定组只能有一个成员（就是它的壳）】若档里出现「两个动作都在常驻动作组」，
                //   多出来的那个归手动 —— 否则界面上会出现两个「常驻」，而只有一个能生效。
                if (isFixedGroup(group) && slotIdOf(group) != id) {
                    group = GROUP_MANUAL
                }
                // 【r16·零迁移】四个新字段全部 optXxx 取默认：
                //   旧档案根本没有这些键 → 落到 `sprite` / `false` / `""` / `""`，
                //   语义正好等于改造前的行为。**不写任何迁移代码**（任务书 §8.4）。
                // 【引擎名净化】档案可能被手工改过或来自旧版本，写进非法值时回落 sprite，
                //   否则渲染层会拿到一个「查不到的引擎」而人偶直接不显示。
                var engine = o.optString("engine", ENGINE_SPRITE)
                if (!ENGINES.contains(engine)) {
                    engine = ENGINE_SPRITE
                }
                out.add(
                    Action(
                        id = id,
                        name = o.optString("name", id),
                        desc = o.optString("desc", ""),
                        group = group,
                        frames = frames,
                        fps = clampFps(o.optInt("fps", FPS_DEFAULT)),
                        loop = o.optBoolean("loop", true),
                        builtin = o.optBoolean("builtin", false),
                        engine = engine,
                        isDefault = isDefaultFlag,
                        sourceId = o.optString("source_id", ""),
                        assetPath = o.optString("asset_path", ""),
                        // 【v0.1.0】贴边裁截线：旧档没有这个键 → 落到默认 0.68（= 旧硬编码值，
                        //   观感与升级前完全一致）。
                        edgeInset = clampEdgeInset(
                            try {
                                o.optDouble("edge_inset", EDGE_INSET_DEFAULT.toDouble()).toFloat()
                            } catch (t: Throwable) {
                                EDGE_INSET_DEFAULT
                            }
                        ),
                        // 【本体基准】旧档 / 没有留白的动作读出来是空串 → 播放层走
                        //   「整张画布铺满」，与升级前逐像素一致。
                        baseline = o.optString("baseline", "")
                    )
                )
            }
        } catch (t: Throwable) {
            // 档案被写坏时宁可从空开始（内置动作会在 all() 里补回），也不要崩在设置页。
        }
        return out
    }

    private fun save(ctx: Context, dollId: String?, list: List<Action>) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        try {
            val arr = JSONArray()
            for (a in list) {
                val o = JSONObject()
                o.put("id", a.id)
                o.put("name", a.name)
                o.put("desc", a.desc)
                o.put("group", a.group)
                o.put("fps", a.fps)
                o.put("loop", a.loop)
                o.put("builtin", a.builtin)
                // 【r16】多写四个字段：旧版本 APK 读到不认识会忽略（JSON 宽松解析），
                //   所以「装了新版又退回旧版」不会丢数据，只是新字段暂时不生效。
                o.put("engine", a.engine)
                // 【v0.1.0·双写 is_default】旧版 APK 没有「常驻壳」概念，它靠这个键决定
                //   「无交互时播哪个」。写「是不是常驻壳」能让旧版继续正常工作 ——
                //   这就是「装了新版又退回旧版」不丢功能的保证。
                o.put("is_default", a.group == GROUP_ALWAYS)
                o.put("source_id", a.sourceId)
                o.put("asset_path", a.assetPath)
                // 【v0.1.0】贴边裁截线。旧版 APK 读到不认识的键会忽略，不影响回退。
                o.put("edge_inset", a.edgeInset.toDouble())
                // 【本轮·本体基准】只写非空值：没有基准的动作不落这个键，
                //   老档因此不会被塞一堆空字符串，回退旧版时也不会因为多出字段而困惑。
                if (a.baseline.isNotEmpty()) {
                    o.put("baseline", a.baseline)
                }
                val fa = JSONArray()
                for (p in a.frames) {
                    fa.put(p)
                }
                o.put("frames", fa)
                arr.put(o)
            }
            PetPrefs.get(ctx).edit().putString(key(dollId), arr.toString()).apply()
        } catch (t: Throwable) {
        }
    }

    @JvmStatic
    fun clampFps(v: Int): Int = Math.max(FPS_MIN, Math.min(FPS_MAX, v))

    private fun newId(): String {
        return "a" + System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().substring(0, 4)
    }

    private fun uniqueName(list: List<Action>, want: String): String {
        var name = want
        var n = 2
        while (true) {
            var dup = false
            for (a in list) {
                if (a.name == name) {
                    dup = true
                    break
                }
            }
            if (!dup) {
                return name
            }
            name = want + " " + n
            n++
        }
    }

    /** 帧时长（毫秒）→ 由 fps 推。 */
    @JvmStatic
    fun frameMs(fps: Int): Long = (1000L / Math.max(1, fps)).coerceAtLeast(20L)
}
