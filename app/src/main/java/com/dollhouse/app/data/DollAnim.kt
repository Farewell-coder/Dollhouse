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
    private const val SEED_VERSION = 2

    /** 【种子版本·哨兵】明确表示「这只人偶不要任何内置动作」（新建模板用）。 */
    private const val SEED_NONE = -1

    /* ============================ 属性分组 ============================ */

    /**
     * 动作属性分组。
     *
     * 【为什么是字符串常量而不是枚举】它们直接落进 JSON、直接当界面的分组标题用，
     *   用字符串省掉一次「枚举 ↔ 显示名」的双向映射 —— 少一层映射就少一处不一致。
     */
    const val GROUP_IDLE = "待机小动作"
    const val GROUP_ALWAYS = "常驻动画"
    const val GROUP_TOUCH = "交互动作"
    const val GROUP_FACE = "表情"
    const val GROUP_CUSTOM = "自定义"

    /**
     * 界面上的分组顺序（与改造前「动作」页的四个分组一致，末尾补「自定义」）。
     * 【为什么顺序也要在数据层】界面按这个顺序渲染分组；顺序变了只改这一处。
     */
    val GROUP_ORDER: List<String> = listOf(
        GROUP_IDLE, GROUP_ALWAYS, GROUP_TOUCH, GROUP_FACE, GROUP_CUSTOM
    )

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
     */
    data class Action(
        val id: String,
        val name: String,
        val desc: String = "",
        val group: String = GROUP_CUSTOM,
        val frames: List<String> = emptyList(),
        val fps: Int = FPS_DEFAULT,
        val loop: Boolean = true,
        val builtin: Boolean = false,
        val engine: String = ENGINE_SPRITE,
        val isDefault: Boolean = false,
        val sourceId: String = "",
        val assetPath: String = ""
    )

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
        // ---------- 待机小动作（PetAnimator.tickIdle 的 1/2/3/4/5 号分支）----------
        Plan(KIND_STRETCH, "伸展", "轻轻弹一下身体并抬高一点点，像伸懒腰。", GROUP_IDLE, 18, 12, KIND_STRETCH, loop = false),
        Plan(KIND_LOOK, "张望", "左右扫视两轮，像在找什么东西。", GROUP_IDLE, 28, 12, KIND_LOOK, loop = false),
        Plan(KIND_SQUINT, "眯眼", "眼睛慢慢眯成一条缝再睁开，像困了。", GROUP_IDLE, 16, 12, KIND_SQUINT, loop = false),
        Plan(KIND_NOD, "点头", "小幅快速上下点头。", GROUP_IDLE, 14, 12, KIND_NOD, loop = false),
        Plan(KIND_HOP, "小跳", "原地弹起一小段又落回。", GROUP_IDLE, 10, 14, KIND_HOP, loop = false),

        // ---------- 常驻动画（一直在跑的低频形变）----------
        Plan(KIND_BREATH, "呼吸", "身体幅度极小的上下起伏，一直在跑。", GROUP_ALWAYS, 36, 12, KIND_BREATH),
        Plan(KIND_BLINK, "眨眼", "闭合 → 保持 → 睁开，约 220 毫秒一轮。", GROUP_ALWAYS, 6, 25, KIND_BLINK),
        Plan(KIND_GAZE, "视线漂移", "视线缓慢地左右飘，幅度很小。", GROUP_ALWAYS, 30, 10, KIND_GAZE),
        Plan(KIND_TAIL, "尾巴摆动", "身后那束头发左右荡，节奏慢。", GROUP_ALWAYS, 24, 10, KIND_TAIL),
        Plan(KIND_AHOGE, "呆毛摆动", "头顶那根呆毛来回晃，比尾巴快。", GROUP_ALWAYS, 20, 12, KIND_AHOGE),
        Plan(KIND_HAIR, "头发波动", "整片头发细密地起伏，节奏最快。", GROUP_ALWAYS, 18, 14, KIND_HAIR),

        // ---------- 交互动作（被点 / 被拖 / 贴边时触发）----------
        Plan(KIND_JUMP, "跳跃", "被点一下时原地跳起（伴随 ♪ 表情）。", GROUP_TOUCH, 7, 16, KIND_JUMP, loop = false),
        Plan(KIND_DRAG, "拖拽", "被拎起来时整体抬高、轻微摇晃。", GROUP_TOUCH, 12, 12, KIND_DRAG, loop = false),
        Plan(KIND_LAND, "落地", "放下的那一下压扁再弹回。", GROUP_TOUCH, 10, 16, KIND_LAND, loop = false),
        Plan(KIND_PEEK, "贴边偷看", "整个身体斜过去探出头看一眼，再缩回来。", GROUP_TOUCH, 16, 10, KIND_PEEK, loop = false),
        Plan(KIND_PERCH, "趴姿", "身体前倾压低、眼睛半闭，趴在边上。", GROUP_TOUCH, 12, 10, KIND_PERCH, loop = false),

        // ---------- 表情（头顶冒符号）----------
        Plan(KIND_SURPRISED, "惊讶", "身体一弹，头顶冒出「！」，被拖拽时触发。", GROUP_FACE, 11, 10, KIND_SURPRISED, loop = false),
        Plan(KIND_HAPPY, "开心", "轻轻弹跳，头顶冒出「♪」，跳跃时触发。", GROUP_FACE, 11, 10, KIND_HAPPY, loop = false),
        Plan(KIND_DIZZY, "迷糊", "身体左右打晃，头顶冒出「？」，落地时触发。", GROUP_FACE, 11, 10, KIND_DIZZY, loop = false)
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

    /** 这只人偶的全部动作。首次调用会按「种子版本」登记内置动作。 */
    @JvmStatic
    @Synchronized
    fun all(ctx: Context, dollId: String?): List<Action> {
        val list = load(ctx, dollId)
        val v = seedVersion(ctx, dollId)
        if (v != SEED_NONE && v < SEED_VERSION) {
            var changed = false
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
                            fps = p.fps, loop = p.loop, builtin = true,
                            engine = ENGINE_SPRITE, isDefault = false, sourceId = "", assetPath = ""
                        )
                    )
                    changed = true
                }
            }
            setSeedVersion(ctx, dollId, SEED_VERSION)
            if (changed) {
                save(ctx, dollId, list)
            }
        }
        return ArrayList(list)
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
     * 【r13 修 bug·「创建失败」】**帧允许为空**。
     *   上一版写了 frames.isEmpty() 就 return null，而「新建动作」传的正是空列表
     *   → 必然弹「创建失败」。可需求是「先有个名字的容器，再往里加图」，
     *   空帧是**正常的第一步**，不该被当成错误。
     *
     * @param frames 已落到本应用私有目录的帧图路径（按顺序）；可为空。
     * @return 新建项；只有 dollId 为空才返回 null。
     */
    @JvmStatic
    @Synchronized
    fun add(ctx: Context, dollId: String?, name: String, desc: String,
            frames: List<String>, fps: Int, loop: Boolean): Action? {
        if (dollId.isNullOrEmpty()) {
            return null
        }
        val list = load(ctx, dollId)
        val a = Action(
            id = newId(),
            name = uniqueName(list, name.ifBlank { "新动作" }),
            desc = desc,
            group = GROUP_CUSTOM,
            frames = ArrayList(frames),
            fps = clampFps(fps),
            loop = loop,
            builtin = false
        )
        list.add(a)
        save(ctx, dollId, list)
        return a
    }

    /** 整体覆盖一项（改名 / 改描述 / 改速度 / 改循环 / 换帧序列 / 换属性）。内置项的 id 与 builtin 位不可改。 */
    @JvmStatic
    @Synchronized
    fun update(ctx: Context, dollId: String?, action: Action?) {
        if (dollId.isNullOrEmpty() || action == null) {
            return
        }
        val list = load(ctx, dollId)
        for (i in list.indices) {
            if (list[i].id == action.id) {
                list[i] = action.copy(
                    id = list[i].id,
                    builtin = list[i].builtin,
                    name = action.name.ifBlank { list[i].name },
                    group = if (action.group.isBlank()) list[i].group else action.group,
                    fps = clampFps(action.fps)
                )
                save(ctx, dollId, list)
                return
            }
        }
    }

    /**
     * 删除一个动作，返回是否真的删掉了。
     *
     * 【r16 语义变更·解除内置豁免】改造前这里写着 `|| hit.builtin` ——
     *   内置动作永远删不掉。任务书 §3.6 要求把 `builtin` 从「权限标记」
     *   降为「来源标记」：它只说明「这个动作是工程自带的」，
     *   不该因此剥夺用户「我就是不想要它」的权利。
     *   删错了一键 [restoreBuiltin] 就回来了 —— 比「永远不让删」更合理。
     *
     * 【连带影响】删除时会一并删掉该动作的帧图目录（见下），
     *   所以内置动作删掉后帧图也没了；[restoreBuiltin] 会重新登记，
     *   帧图由 `AnimBaker.ensure` 在首次播放时按需重烘（它本来就是幂等的）。
     */
    @JvmStatic
    @Synchronized
    fun remove(ctx: Context, dollId: String?, id: String?): Boolean {
        if (dollId.isNullOrEmpty() || id == null) {
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
        if (n > 0) {
            save(ctx, dollId, list)
            // 【必须落种子版本】否则下次 [all] 见到「版本为 0」还会再补一遍，
            //   与本方法重复劳动；标成当前版本即可。
            setSeedVersion(ctx, dollId, SEED_VERSION)
        }
        return n
    }

    /**
     * **【r16 新增】**设为该人偶的「待机默认动作」（同人偶内互斥）。
     *
     * 【为什么必须互斥】语义是「无交互时播哪一个」。允许两个同时为默认，
     *   就得再定一套「先播谁」的规则，而这规则对用户完全不可见 ——
     *   不如从数据层保证只有一个。
     *
     * @param id 目标动作；传 null 表示**取消默认**（回到「只跑基础形变」的老行为）。
     */
    @JvmStatic
    @Synchronized
    fun setDefault(ctx: Context, dollId: String?, id: String?) {
        if (dollId.isNullOrEmpty()) {
            return
        }
        val list = load(ctx, dollId)
        var changed = false
        for (i in list.indices) {
            val want = (id != null && list[i].id == id)
            if (list[i].isDefault != want) {
                list[i] = list[i].copy(isDefault = want)
                changed = true
            }
        }
        if (changed) {
            save(ctx, dollId, list)
        }
    }

    /** **【r16 新增】**取该人偶的待机默认动作；没设过返回 null。 */
    @JvmStatic
    fun defaultAction(ctx: Context, dollId: String?): Action? {
        for (a in all(ctx, dollId)) {
            if (a.isDefault) {
                return a
            }
        }
        return null
    }

    /** **【r16 新增】**按语义 ID 找动作（`idle` / `tap` / `drag` / `longpress` / `appear` / `disappear`）。 */
    @JvmStatic
    fun bySemantic(ctx: Context, dollId: String?, semantic: String): Action? {
        if (semantic.isBlank()) {
            return null
        }
        // 约定：动作 id 若与语义同名（模型包导入时按此约定生成），直接命中；
        // 否则退回「属性分组匹配」—— 待机组的第一个动作即被视作待机默认动作。
        val direct = byId(ctx, dollId, semantic)
        if (direct != null) {
            return direct
        }
        if (semantic == "idle") {
            return defaultAction(ctx, dollId)
        }
        return null
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
                // 【属性迁移】老档案里没有 group 字段：内置动作按 plan 补齐，
                //   用户动作归「自定义」。这样升级后分组立刻正确，无需任何手工迁移。
                var group = o.optString("group", "")
                if (group.isEmpty()) {
                    val p = planOf(id)
                    group = if (p != null) p.group else GROUP_CUSTOM
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
                        isDefault = o.optBoolean("is_default", false),
                        sourceId = o.optString("source_id", ""),
                        assetPath = o.optString("asset_path", "")
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
                o.put("is_default", a.isDefault)
                o.put("source_id", a.sourceId)
                o.put("asset_path", a.assetPath)
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
