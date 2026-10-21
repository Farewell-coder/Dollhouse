package com.dollhouse.app.data

import android.content.Context
import android.content.SharedPreferences
import com.dollhouse.app.PetService
import com.dollhouse.app.pet.GestureActions
import org.json.JSONArray

/**
 * 【职责】「每只人偶各存各的」的配置中枢（r10 新增）。
 *
 * 【为什么单独一层】r10 把人偶编辑页里每一项设置都改成「针对这一只人偶」：
 *   手势响应 / 暂离时长 / 比例大小 / 回归时间 / 初始位置 / 屏幕范围 / 交谈内容 / 性格内容。
 *   若这些键继续散在 [PetPrefs] 里，会和「全局偏好」混成一锅，且键名无法一眼看出归属。
 *   集中到本类后统一走 [key] 命名规则：`<base>_<dollId>`。
 *
 * 【向后兼容】默认配置（[DollStore.DEFAULT_ID]）**沿用历史全局键名**（`gesture_single` /
 *   `away_seconds` / `pet_scale`），于是老用户升级后，原先那份全局设置自然成为「默认配置」
 *   这只人偶的设置，不需要任何迁移代码。其余人偶才加 `_<id>` 后缀分开存。
 *
 * 【存储介质】直接复用 [PetPrefs.get] 的同一份 SharedPreferences（`feiyu_pet`）——
 *   读者（[com.dollhouse.app.pet.PetWindow]）与写者（编辑页）必须看到同一份数据，
 *   另开一个 prefs 文件只会制造「写进去读不出来」的隐性 bug。
 *
 * 【线程】读写在主线程（与 [PetPrefs] 同约束）；落盘一律 `apply()`，
 *   唯一例外是比例（见 [setScale] 的注释）。
 */
object DollProfile {

    /* ============================ 键名 ============================ */

    /** 暂离时长（秒）。 */
    private const val K_AWAY = "away_seconds"
    /** 缩放百分比（100 = 原始大小）。 */
    private const val K_SCALE = "pet_scale"
    /** 回归时间（秒）：0 = 每次出现都回初始位置。 */
    private const val K_RETURN = "return_seconds"
    /** 初始位置：归一化的锚点坐标（0~1），与桌宠「起点」同语义。 */
    private const val K_HOME_RX = "home_rx"
    private const val K_HOME_RY = "home_ry"
    /** 屏幕范围（可移动边界）：归一化四边，按横竖屏各存一套。 */
    private const val K_BOUND = "bounds"
    /** 交谈内容：JSON 数组文本。 */
    private const val K_TALK = "talk_lines"
    /** 性格内容：整段自由文本。 */
    private const val K_PERSONA = "persona"
    /** 人偶面朝方向：true = 水平镜像（朝左）。 */
    private const val K_FLIP = "flip"

    /**
     * 【r13·C1/C2】帧图在预览里的**中心对齐偏移**（按人偶存一份）。
     *
     * 【为什么需要】用户要求「在上部的预览图中间放一个中心点位，这玩意是控制木偶的绝对中心，
     *   方便以后添加模型的时候对齐」＋「以后添加的模型图片默认居中的是这个中心点位」。
     *   内置动作烘出来的帧已经是四边等值留白（天然居中），但**用户自己导入的图**
     *   可能画布大小不一、内容偏心 —— 这时需要一个显式偏移把内容挪到中心点位上。
     *
     * 【为什么归一化】与 [homePivot] 同一口径：绝对像素换分辨率 / 横竖屏就废了，
     *   归一化坐标（相对预览框宽高的比例）能自动跟随。
     */
    private const val K_FRAME_OX = "frame_ox"
    private const val K_FRAME_OY = "frame_oy"

    /** 屏幕范围的方向键：竖屏。 */
    const val ORIENT_PORT = "port"
    /** 屏幕范围的方向键：横屏。 */
    const val ORIENT_LAND = "land"

    /* ---- 【r14】外观（人偶层）键名基名 ---- */
    /** 人偶独立聊天背景图路径。 */
    private const val K_CHAT_BG = "chatbg"
    /** 人偶独立聊天背景开关。 */
    private const val K_CHAT_BG_ON = "chatbg_on"
    /** 人偶独立聊天背景透明度。 */
    private const val K_CHAT_BG_ALPHA = "chatbg_alpha"
    /** 人偶独立聊天背景毛玻璃半径。 */
    private const val K_CHAT_BG_BLUR = "chatbg_blur"
    /** 人偶气泡底色（0 = 跟随主题）。 */
    private const val K_BUBBLE_COLOR = "bubble_color"
    /** 人偶气泡磨砂开关。 */
    private const val K_BUBBLE_FROST = "bubble_frost"

    /** 回归时间的取值区间（秒）；默认 0 = 每次出现都回初始位置。 */
    const val RETURN_DEFAULT = 0
    const val RETURN_MIN = 0
    const val RETURN_MAX = 86400

    /**
     * 生成某只人偶的偏好键。
     * 默认配置沿用历史键名，其余人偶加后缀 —— 与 `PetWindow.key(base)` 同一规则，
     * 保证桌宠侧读到的就是编辑页写下的那一份。
     */
    @JvmStatic
    fun key(dollId: String?, base: String): String {
        return if (dollId == null || dollId.isEmpty() || dollId == DollStore.DEFAULT_ID) {
            base
        } else {
            base + "_" + dollId
        }
    }

    private fun prefs(ctx: Context): SharedPreferences {
        return PetPrefs.get(ctx)
    }

    /* ============================ 原始读写（尺寸规格等子模块用） ============================ */

    /**
     * 按**已拼好的完整键**读一个字符串。
     *
     * 【为什么开放原始读写】[com.dollhouse.app.data.DollSizeSpec] 有自己的编解码
     * （`size,ox,oy`），如果这里为它单开一对 `sizeSpec()/setSizeSpec()`，
     * 每加一个结构化配置就要在 DollProfile 里再开一对方法。
     * 开放一对「按完整键存取字符串」的底层口子，子模块自己拼键、自己编解码，
     * 键规则（[key] 的默认人偶裸键约定）仍然只有这一处。
     *
     * @param fullKey 已经过 [key] 拼装的完整键
     */
    @JvmStatic
    fun raw(ctx: Context, fullKey: String): String? {
        return prefs(ctx).getString(fullKey, null)
    }

    /**
     * 按**已拼好的完整键**写一个字符串。
     *
     * 【为什么用 commit】用户改完设置可能立刻划掉后台，`apply()` 的异步写盘有丢的风险。
     *   与 [setScale] 保持同一约定。
     */
    @JvmStatic
    fun putRaw(ctx: Context, fullKey: String, value: String) {
        try {
            prefs(ctx).edit().putString(fullKey, value).commit()
        } catch (t: Throwable) {
            com.dollhouse.app.core.Logs.w("DollProfile", "putRaw failed: " + fullKey, t)
        }
    }

    /**
     * **【本轮·新增】把这**只人偶**的个性化设置复位成「刚装好软件时的样子」。**
     *
     * ## 用户定案
     *
     * 原话：「这个恢复的功能就是恢复默认配置的备份样子，也就是下载软件的时候自带的样子」。
     * 所以语义不是「清空」，而是**回到出厂默认值**。
     *
     * ## 清哪些、留哪些
     *
     * 清（回到各自 DEFAUT 常量）：
     * - 尺寸规格（[DollSizeSpec]：大小 100% / 位置居中）
     * - 手势响应（单击 / 双击 / 三击 / 长按 回到内置默认映射）
     * - 暂离 / 回归时长
     * - 性格内容、交谈内容
     * - 聊天背景、气泡色与磨砂
     * - 帧图中心偏移、初始位置、屏幕范围
     * - 面朝方向
     *
     * 留：**用户自己导出的东西**
     * - 形象图路径（`DollStore.Doll.sprite`）、封面（`cover`）—— 那是用户换的形象，不是"设置"
     * - 动作库里的自定义动作 —— 「恢复默认」不该等于删除用户的作品
     *   （内置动作由 [DollAnim.restoreBuiltin] 单独负责补回）
     *
     * ## 为什么用逐个 remove 而不是 clean 整个 SharedPreferences
     *
     * 这个 SharedPreferences 是**全 App 共用**的（[PetPrefs.get]），里面还有聊天记录、
     * 供应商配置、记忆库等大量与「这只人偶的显示设置」无关的数据。
     * 一旦 clear，等于把用户的聊天和数据全删了 —— 不可接受。
     * 所以这里**只按本类的键名清单逐条移除**，范围严格可控。
     */
    @JvmStatic
    fun resetToFactory(ctx: Context, dollId: String?) {
        val bases = listOf(
            K_AWAY, K_SCALE, K_RETURN,
            K_HOME_RX, K_HOME_RY,
            K_BOUND,
            K_TALK, K_PERSONA, K_FLIP,
            K_FRAME_OX, K_FRAME_OY,
            K_CHAT_BG, K_CHAT_BG_ON, K_CHAT_BG_ALPHA, K_CHAT_BG_BLUR,
            K_BUBBLE_COLOR, K_BUBBLE_FROST,
            K_SIZE_SPEC,
            // 手势：五个手势各一条键，名字由 [GestureActions] 决定。
            "gesture_single", "gesture_double", "gesture_triple",
            "gesture_long", "gesture_hold"
        )
        try {
            val e = prefs(ctx).edit()
            for (b in bases) {
                e.remove(key(dollId, b))
            }
            // 「上一次清理」类标志位也一并复位，否则重置后可能带着旧的一次性标记。
            e.commit()
        } catch (t: Throwable) {
            com.dollhouse.app.core.Logs.w("DollProfile", "resetToFactory failed", t)
        }
    }

    /**
     * 尺寸规格的键名（本轮新增），供 [resetToFactory] 使用。
     *
     * 【为什么不叫 K_SPEC】`K_SPEC` 已被「性格规格（persona_spec）」占用
     *   （见下方「性格本体 · 结构化规格」一节）。同名会造成编译期重复声明。
     * 【为什么放在这里】键名清单只能有一份，否则重置时漏删就是
     *   「点了恢复默认却还有残留」。
     */
    private const val K_SIZE_SPEC = "size_spec"

    /* ============================ 手势响应 ============================ */

    /** 某只人偶对某个手势配置的响应集合（空集合 = 无）。键不存在时返回默认映射，不写盘。 */
    @JvmStatic
    fun gestureActions(ctx: Context, dollId: String?, gesture: String): MutableSet<String> {
        val raw = prefs(ctx).getString(key(dollId, "gesture_" + gesture), GestureActions.defaultRaw(gesture))
        return GestureActions.parse(raw)
    }

    /** 写入某只人偶的手势响应集合。 */
    @JvmStatic
    fun setGestureActions(ctx: Context, dollId: String?, gesture: String, actions: Set<String>) {
        prefs(ctx).edit().putString(key(dollId, "gesture_" + gesture), GestureActions.format(actions)).apply()
    }

    /* ============================ 暂离 / 回归 ============================ */

    /** 暂离时长（秒），范围 [PetPrefs.AWAY_SECONDS_MIN] ~ [PetPrefs.AWAY_SECONDS_MAX]。 */
    @JvmStatic
    fun awaySeconds(ctx: Context, dollId: String?): Int {
        val v = prefs(ctx).getInt(key(dollId, K_AWAY), PetPrefs.AWAY_SECONDS_DEFAULT)
        if (v < PetPrefs.AWAY_SECONDS_MIN) {
            return PetPrefs.AWAY_SECONDS_MIN
        }
        return if (v > PetPrefs.AWAY_SECONDS_MAX) PetPrefs.AWAY_SECONDS_MAX else v
    }

    @JvmStatic
    fun setAwaySeconds(ctx: Context, dollId: String?, i: Int) {
        val n = Math.max(PetPrefs.AWAY_SECONDS_MIN, Math.min(PetPrefs.AWAY_SECONDS_MAX, i))
        prefs(ctx).edit().putInt(key(dollId, K_AWAY), n).apply()
    }

    /**
     * 回归时间（秒）：人偶消失超过该时长后，再出现时回到「初始位置」。
     *
     * 【r11 语义】0 = 每次出现都回初始位置（不再是「不触发」）。
     *   计时的起点是「人偶消失」（关闭人偶按钮 / 指令关闭，见 [PetPrefs.setHiddenAt]）；
     *   暂离是本窗口内部的隐显，不参与这个计时。
     */
    @JvmStatic
    fun returnSeconds(ctx: Context, dollId: String?): Int {
        val v = prefs(ctx).getInt(key(dollId, K_RETURN), RETURN_DEFAULT)
        if (v < RETURN_MIN) {
            return RETURN_MIN
        }
        return if (v > RETURN_MAX) RETURN_MAX else v
    }

    @JvmStatic
    fun setReturnSeconds(ctx: Context, dollId: String?, i: Int) {
        val n = Math.max(RETURN_MIN, Math.min(RETURN_MAX, i))
        prefs(ctx).edit().putInt(key(dollId, K_RETURN), n).apply()
    }

    /* ============================ 比例 ============================ */
    /**
     * 缩放百分比的落盘区间。
     * 【为什么不沿用 PetPrefs 的 50~150】r10 要求编辑页「不给端点置灰、加减一直可用」；
     *   沿用 50~150 会让加减到端点后按钮必须变灰，与需求冲突。这里放宽到 20~400：
     *   既保证「永远点得动」，也不会缩到 0（人偶不可见）或放到几倍（整屏都是它）。
     */
    const val SCALE_DEFAULT = 100
    const val SCALE_MIN = 20
    const val SCALE_MAX = 400

    /** 缩放百分比；范围 [SCALE_MIN] ~ [SCALE_MAX]。 */
    @JvmStatic
    fun scale(ctx: Context, dollId: String?): Int {
        val v = prefs(ctx).getInt(key(dollId, K_SCALE), SCALE_DEFAULT)
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, v))
    }

    /**
     * 写入缩放百分比。
     * 【为什么用 commit】用户点完「+ / −」可能立刻划掉后台，`apply()` 的异步写盘有丢的风险；
     *   这条与 [PetPrefs.setPetScale] 保持同一约定。
     */
    @JvmStatic
    fun setScale(ctx: Context, dollId: String?, i: Int) {
        val n = Math.max(SCALE_MIN, Math.min(SCALE_MAX, i))
        prefs(ctx).edit().putInt(key(dollId, K_SCALE), n).commit()
    }

    /* ============================ 初始位置 ============================ */

    /**
     * 初始位置的归一化坐标 {rx, ry}（0~1，相对屏幕宽高）；用户没设过返回 null。
     * 【为什么归一化】同一只人偶换了分辨率 / 横竖屏后，绝对像素会跑到屏幕外；
     *   比例坐标能自动跟随屏幕尺寸。
     */
    @JvmStatic
    fun homePivot(ctx: Context, dollId: String?): FloatArray? {
        val p = prefs(ctx)
        val krx = key(dollId, K_HOME_RX)
        val kry = key(dollId, K_HOME_RY)
        if (!p.contains(krx) || !p.contains(kry)) {
            return null
        }
        val rx = p.getFloat(krx, -1.0f)
        val ry = p.getFloat(kry, -1.0f)
        if (rx < 0.0f || ry < 0.0f) {
            return null
        }
        return floatArrayOf(rx, ry)
    }

    @JvmStatic
    fun setHomePivot(ctx: Context, dollId: String?, rx: Float, ry: Float) {
        prefs(ctx).edit()
            .putFloat(key(dollId, K_HOME_RX), Math.max(0.0f, Math.min(1.0f, rx)))
            .putFloat(key(dollId, K_HOME_RY), Math.max(0.0f, Math.min(1.0f, ry)))
            .apply()
    }

    @JvmStatic
    fun hasHomePivot(ctx: Context, dollId: String?): Boolean {
        return homePivot(ctx, dollId) != null
    }

    /* ============================ 帧图中心对齐偏移（C1/C2） ============================ */

    /**
     * 帧图在预览框里的中心对齐偏移 {ox, oy}，**归一化**（相对预览框宽高，可为负）。
     * 用户没调过返回 {0, 0}。
     *
     * 【默认值就是「居中」】0 表示「帧图内容按自身中心对准预览框的中心点位」——
     *   也就是用户要的「默认居中的是这个中心点位」。只有内容本身偏心时才需要调。
     */
    @JvmStatic
    fun frameOffset(ctx: Context, dollId: String?): FloatArray {
        val p = prefs(ctx)
        return floatArrayOf(
            p.getFloat(key(dollId, K_FRAME_OX), 0.0f),
            p.getFloat(key(dollId, K_FRAME_OY), 0.0f)
        )
    }

    /** 落一次帧图中心偏移（夹在 ±0.5 内：再大就把内容挪出预览框了）。 */
    @JvmStatic
    fun setFrameOffset(ctx: Context, dollId: String?, ox: Float, oy: Float) {
        prefs(ctx).edit()
            .putFloat(key(dollId, K_FRAME_OX), Math.max(-0.5f, Math.min(0.5f, ox)))
            .putFloat(key(dollId, K_FRAME_OY), Math.max(-0.5f, Math.min(0.5f, oy)))
            .apply()
    }

    /* ============================ 屏幕范围（可移动边界） ============================ */

    /**
     * 边界归一化坐标的合法值域。
     * 【r12 为什么放宽】需求定案「虚线不受屏幕限制」：框可以拖到屏幕之外，
     *   人偶也能走到屏幕外。旧实现夹在 [0,1]，等于把框锁死在屏幕内。
     *   这里放宽到 -1.0 ~ 2.0（允许整框在一次屏幕尺寸之外再出去一整屏），
     *   同时保留「不能缩成一个点」的最小尺寸约束。
     */
    private const val BOUND_MIN = -1.0f
    private const val BOUND_MAX = 2.0f

    /** 虚线框的最小尺寸（归一化）：防止缩成一条线后点不到。 */
    private const val BOUND_MIN_SIZE = 0.1f

    /**
     * 某只人偶的可移动边界，归一化 {left, top, right, bottom}；默认整屏（0,0,1,1）。
     * 【r12】值域放宽到 [BOUND_MIN] ~ [BOUND_MAX]，允许超出屏幕。
     * 横屏 / 竖屏各存一套，由调用方按当前方向取 [ORIENT_PORT] / [ORIENT_LAND]。
     */
    @JvmStatic
    fun bounds(ctx: Context, dollId: String?, orient: String): FloatArray {
        val p = prefs(ctx)
        val l = p.getFloat(key(dollId, K_BOUND + "_l_" + orient), 0.0f)
        val t = p.getFloat(key(dollId, K_BOUND + "_t_" + orient), 0.0f)
        val r = p.getFloat(key(dollId, K_BOUND + "_r_" + orient), 1.0f)
        val b = p.getFloat(key(dollId, K_BOUND + "_b_" + orient), 1.0f)
        val left = Math.max(BOUND_MIN, Math.min(BOUND_MAX - BOUND_MIN_SIZE, l))
        val top = Math.max(BOUND_MIN, Math.min(BOUND_MAX - BOUND_MIN_SIZE, t))
        val right = Math.max(left + BOUND_MIN_SIZE, Math.min(BOUND_MAX, r))
        val bottom = Math.max(top + BOUND_MIN_SIZE, Math.min(BOUND_MAX, b))
        return floatArrayOf(left, top, right, bottom)
    }

    @JvmStatic
    fun setBounds(ctx: Context, dollId: String?, orient: String, left: Float, top: Float, right: Float, bottom: Float) {
        val l = Math.max(BOUND_MIN, Math.min(BOUND_MAX - BOUND_MIN_SIZE, left))
        val t = Math.max(BOUND_MIN, Math.min(BOUND_MAX - BOUND_MIN_SIZE, top))
        val r = Math.max(l + BOUND_MIN_SIZE, Math.min(BOUND_MAX, right))
        val b = Math.max(t + BOUND_MIN_SIZE, Math.min(BOUND_MAX, bottom))
        prefs(ctx).edit()
            .putFloat(key(dollId, K_BOUND + "_l_" + orient), l)
            .putFloat(key(dollId, K_BOUND + "_t_" + orient), t)
            .putFloat(key(dollId, K_BOUND + "_r_" + orient), r)
            .putFloat(key(dollId, K_BOUND + "_b_" + orient), b)
            .apply()
    }

    /** 该方向的边界是否被用户改过（没改过就是整屏，不必写盘）。 */
    @JvmStatic
    fun hasBounds(ctx: Context, dollId: String?, orient: String): Boolean {
        return prefs(ctx).contains(key(dollId, K_BOUND + "_l_" + orient))
    }

    /**
     * 【r12】虚线框的出厂默认值：整屏内缩 24dp（横竖屏各自按当前屏幕换算）。
     *
     * 【为什么内缩】需求定案「动态生成一个合理的范围」—— 若默认就是整屏（0,0,1,1），
     *   四条虚线正好压在屏幕物理边缘上，手指够不着（系统手势区会抢触摸），
     *   「恢复」按钮就失去了意义。内缩 24dp 后四边都在屏内、一定点得到。
     *
     * @return 归一化 {left, top, right, bottom}
     */
    @JvmStatic
    fun defaultBounds(ctx: Context): FloatArray {
        val dm = ctx.resources.displayMetrics
        val w = Math.max(1, dm.widthPixels).toFloat()
        val h = Math.max(1, dm.heightPixels).toFloat()
        val inset = 24.0f * dm.density
        return floatArrayOf(inset / w, inset / h, 1.0f - (inset / w), 1.0f - (inset / h))
    }

    /** 【r12】把虚线框恢复成 [defaultBounds]（「恢复」按钮的落地动作）。 */
    @JvmStatic
    fun resetBounds(ctx: Context, dollId: String?, orient: String) {
        val d = defaultBounds(ctx)
        setBounds(ctx, dollId, orient, d[0], d[1], d[2], d[3])
    }

    /** 按当前是否横屏取方向键。 */
    @JvmStatic
    fun orientOf(landscape: Boolean): String {
        return if (landscape) ORIENT_LAND else ORIENT_PORT
    }

    /* ============================ 交谈内容 ============================ */

    /** 这只人偶在「交谈」机制下会出现的台词，一条一句；未设置返回空列表。 */
    @JvmStatic
    fun talkLines(ctx: Context, dollId: String?): MutableList<String> {
        val out = ArrayList<String>()
        val raw = prefs(ctx).getString(key(dollId, K_TALK), null) ?: return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotEmpty()) {
                    out.add(s)
                }
            }
        } catch (ignored: Throwable) {
            // 存档损坏时按「空」处理，不崩界面。
        }
        return out
    }

    @JvmStatic
    fun setTalkLines(ctx: Context, dollId: String?, lines: List<String>) {
        val arr = JSONArray()
        for (s in lines) {
            if (s.isNotEmpty()) {
                arr.put(s)
            }
        }
        prefs(ctx).edit().putString(key(dollId, K_TALK), arr.toString()).apply()
    }

    /* ============================ 性格内容 ============================ */

    /** 这只人偶的性格描述（给模型用的人设片段）；未设置返回空串。 */
    @JvmStatic
    fun persona(ctx: Context, dollId: String?): String {
        return prefs(ctx).getString(key(dollId, K_PERSONA), "") ?: ""
    }

    @JvmStatic
    fun setPersona(ctx: Context, dollId: String?, s: String) {
        prefs(ctx).edit().putString(key(dollId, K_PERSONA), s).apply()
    }

    /* ============================ 角色设定（r13） ============================ */

    /** 结构化「角色设定」的存储键（与 [K_PERSONA] 区分：那段自由文本仍是「性格描述」）。 */
    private const val K_SPEC = "persona_spec"

    /**
     * 读取这只人偶的结构化角色设定。
     *
     * 【容错】JSON 坏掉 / 从没写过都返回一个空对象，**绝不抛** ——
     *   人格页是高频入口，读盘失败不能让它打不开。
     */
    @JvmStatic
    fun spec(ctx: Context, dollId: String?): PersonaSpec {
        val raw = prefs(ctx).getString(key(dollId, K_SPEC), "") ?: ""
        if (raw.isEmpty()) {
            return PersonaSpec()
        }
        return try {
            PersonaSpec.fromJson(org.json.JSONObject(raw))
        } catch (ignored: Throwable) {
            PersonaSpec()
        }
    }

    @JvmStatic
    fun setSpec(ctx: Context, dollId: String?, s: PersonaSpec?) {
        prefs(ctx).edit().putString(key(dollId, K_SPEC), if (s == null) "" else s.toJson().toString()).apply()
    }

    /** 这只人偶是否写过任何角色设定（含性格描述与结构化分段）。 */
    @JvmStatic
    fun hasPersona(ctx: Context, dollId: String?): Boolean {
        if (persona(ctx, dollId).trim().isNotEmpty()) {
            return true
        }
        return !spec(ctx, dollId).isEmpty()
    }

    /* ============================ 面朝方向（r11） ============================ */

    /**
     * 这只人偶是否水平镜像（面朝左）。默认 false = 原始朝向。
     * 【用途】「屏幕范围」悬浮窗中间那个旋转按钮改的就是它：转人偶的面朝方向。
     */
    @JvmStatic
    fun flip(ctx: Context, dollId: String?): Boolean {
        return prefs(ctx).getBoolean(key(dollId, K_FLIP), false)
    }

    @JvmStatic
    fun setFlip(ctx: Context, dollId: String?, z: Boolean) {
        prefs(ctx).edit().putBoolean(key(dollId, K_FLIP), z).apply()
    }

    /* ============================ 种子（r11） ============================ */

    /**
     * 【r11 一次性】把内置的「交谈内容」与「性格内容」种子写进「默认配置」这只人偶。
     *
     * 【为什么需要】r11 起交谈与性格都绑人偶：默认配置要继承改造前的那一份
     *   （3 条单击台词 + 4 段人设），新建人偶才是空白模板。不写种子的话，
     *   老用户升级后默认配置是空的 —— 单击不说话、AI 也失去人设。
     *
     * 【为什么只做一次】用 doll_seed_ver 标记（与 PetPrefs 的一次性清理同款）。
     *   做完之后用户若把台词删光 / 把性格清空，重启不会再被强行写回 —— 不夺走用户的选择权。
     *   同时只在「该键从未落盘」时才写，避免覆盖用户已经改过的内容。
     *
     * 【坑·致命】内部走 prefs(ctx) 取已解析好的 SharedPreferences，
     *   绝不能再调 PetPrefs.get() 之外的递归路径，否则与 PetPrefs.purgeDeadKeys 犯同一个无限递归的错。
     */
    @JvmStatic
    fun seedDefault(ctx: Context) {
        try {
            val p = prefs(ctx)
            if (p.getInt("doll_seed_ver", 0) >= 1) {
                return
            }
            val edit = p.edit()
            if (!p.contains(key(DollStore.DEFAULT_ID, K_TALK))) {
                val arr = JSONArray()
                for (line in PetService.LINES_TAP) {
                    arr.put(line)
                }
                edit.putString(key(DollStore.DEFAULT_ID, K_TALK), arr.toString())
            }
            if (!p.contains(key(DollStore.DEFAULT_ID, K_PERSONA))) {
                edit.putString(key(DollStore.DEFAULT_ID, K_PERSONA), PetPrefs.SYSTEM_PROMPT)
            }
            edit.putInt("doll_seed_ver", 1).apply()
        } catch (ignored: Throwable) {
            // 种子写入失败不该拦住启动：留空即可，用户可在人格页手填。
        }
    }

    /* ============================ r14·外观（人偶层） ============================
     *
     * 【为什么走 [key] 的既有规则】`key(dollId, base)` 对默认配置沿用历史键名、
     *   其余人偶加 `_<dollId>` 后缀 —— 与桌宠侧读键的规则**完全一致**。
     *   新增的人偶外观键必须复用同一规则，否则桌宠与编辑页会读到两份不同的值。
     *
     * 【三层配置中的位置】人偶层覆盖全局层（见 GlobalBackground.resolvePath）：
     *   这只人偶设了自己的图且开关开着 → 用它的；否则回落全局层。
     */

    /** 人偶独立聊天背景的路径（空串 = 没设，回落全局）。 */
    @JvmStatic
    fun chatBgPath(ctx: Context, dollId: String?): String {
        return try {
            prefs(ctx).getString(key(dollId, K_CHAT_BG), "") ?: ""
        } catch (ignored: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun setChatBgPath(ctx: Context, dollId: String?, path: String?) {
        prefs(ctx).edit().putString(key(dollId, K_CHAT_BG), if (path == null) "" else path).apply()
    }

    /**
     * 人偶独立背景开关。**默认 true**。
     * 【为什么默认开】用户主动给这只人偶设了图，意图就是「要用它」；
     *   默认关会让用户设完图却看不到效果（还以为是 bug）。
     */
    @JvmStatic
    fun chatBgEnabled(ctx: Context, dollId: String?): Boolean {
        return try {
            prefs(ctx).getBoolean(key(dollId, K_CHAT_BG_ON), true)
        } catch (ignored: Throwable) {
            true
        }
    }

    @JvmStatic
    fun setChatBgEnabled(ctx: Context, dollId: String?, z: Boolean) {
        prefs(ctx).edit().putBoolean(key(dollId, K_CHAT_BG_ON), z).apply()
    }

    /** 人偶独立背景的透明度百分比（0~100）。默认沿用全局口径 60。 */
    @JvmStatic
    fun chatBgAlpha(ctx: Context, dollId: String?): Int {
        val v = prefs(ctx).getInt(key(dollId, K_CHAT_BG_ALPHA), 60)
        return Math.max(0, Math.min(100, v))
    }

    @JvmStatic
    fun setChatBgAlpha(ctx: Context, dollId: String?, i: Int) {
        prefs(ctx).edit().putInt(key(dollId, K_CHAT_BG_ALPHA), Math.max(0, Math.min(100, i))).apply()
    }

    /** 人偶独立背景的毛玻璃半径（0~24 dp）。 */
    @JvmStatic
    fun chatBgBlur(ctx: Context, dollId: String?): Int {
        val v = prefs(ctx).getInt(key(dollId, K_CHAT_BG_BLUR), 0)
        return Math.max(0, Math.min(24, v))
    }

    @JvmStatic
    fun setChatBgBlur(ctx: Context, dollId: String?, i: Int) {
        prefs(ctx).edit().putInt(key(dollId, K_CHAT_BG_BLUR), Math.max(0, Math.min(24, i))).apply()
    }

    /* ---- 人偶气泡样式（覆盖全局默认）---- */

    /**
     * 人偶气泡的底色 ARGB。**0 = 跟随主题**（默认）。
     * 【为什么用 0 作哨兵】与 [PetPrefs.fontColor] 同一口径：`#000000` 是合法颜色，不能当「未设」。
     */
    @JvmStatic
    fun bubbleColor(ctx: Context, dollId: String?): Int {
        return try {
            prefs(ctx).getInt(key(dollId, K_BUBBLE_COLOR), 0)
        } catch (ignored: Throwable) {
            0
        }
    }

    @JvmStatic
    fun setBubbleColor(ctx: Context, dollId: String?, argb: Int) {
        prefs(ctx).edit().putInt(key(dollId, K_BUBBLE_COLOR), argb).apply()
    }

    /** 人偶气泡是否用磨砂质感。默认 true。 */
    @JvmStatic
    fun bubbleFrost(ctx: Context, dollId: String?): Boolean {
        return try {
            prefs(ctx).getBoolean(key(dollId, K_BUBBLE_FROST), true)
        } catch (ignored: Throwable) {
            true
        }
    }

    @JvmStatic
    fun setBubbleFrost(ctx: Context, dollId: String?, z: Boolean) {
        prefs(ctx).edit().putBoolean(key(dollId, K_BUBBLE_FROST), z).apply()
    }

    /* ============================ 清理 ============================ */

    /**
     * 删除某只人偶时清掉它的全部独立配置。
     * 【为什么必须做】否则「新建一只 → 恰好复用旧 id」或列表里新旧交替时，
     *   新的人偶会凭空继承上一只的手势 / 人格，属于可见的数据串味。
     *
     * @param force **【v0.1.0】**传 true 时连默认配置也清。
     *   改造前这里对 [DollStore.DEFAULT_ID] 直接 return —— 因为默认配置不可删，清它没有意义。
     *   现在「删默认配置 = 彻底重置」，那条路径必须能真的清干净（否则重建出来的人偶
     *   会继承旧的手势 / 人格 / 背景），所以要能越过这道闸门。
     */
    @JvmStatic
    fun clear(ctx: Context, dollId: String?, force: Boolean = false) {
        if (dollId == null || dollId.isEmpty()) {
            return
        }
        if (!force && dollId == DollStore.DEFAULT_ID) {
            return
        }
        val p = prefs(ctx)
        val edit = p.edit()
        for (g in arrayOf("single", "double", "triple", "long")) {
            edit.remove(key(dollId, "gesture_" + g))
        }
        edit.remove(key(dollId, K_AWAY))
        edit.remove(key(dollId, K_SCALE))
        edit.remove(key(dollId, K_RETURN))
        edit.remove(key(dollId, K_HOME_RX))
        edit.remove(key(dollId, K_HOME_RY))
        edit.remove(key(dollId, K_TALK))
        edit.remove(key(dollId, K_PERSONA))
        edit.remove(key(dollId, K_SPEC))
        edit.remove(key(dollId, K_FLIP))
        // 【r14】人偶层外观配置也要清：不然「新建一只 → 恰好复用旧 id」时会继承上一只的背景图。
        edit.remove(key(dollId, K_CHAT_BG))
        edit.remove(key(dollId, K_CHAT_BG_ON))
        edit.remove(key(dollId, K_CHAT_BG_ALPHA))
        edit.remove(key(dollId, K_CHAT_BG_BLUR))
        edit.remove(key(dollId, K_BUBBLE_COLOR))
        edit.remove(key(dollId, K_BUBBLE_FROST))
        for (o in arrayOf(ORIENT_PORT, ORIENT_LAND)) {
            edit.remove(key(dollId, K_BOUND + "_l_" + o))
            edit.remove(key(dollId, K_BOUND + "_t_" + o))
            edit.remove(key(dollId, K_BOUND + "_r_" + o))
            edit.remove(key(dollId, K_BOUND + "_b_" + o))
        }
        // 桌宠侧的「上次位置」也一并清掉，免得新人偶继承旧坐标。
        edit.remove("rest_px_" + dollId)
        edit.remove("rest_fy_" + dollId)
        edit.remove("rest_rx_" + dollId)
        edit.remove("rest_ry_" + dollId)
        edit.remove("edge_side_" + dollId)
        // 【r13】记忆库也按人偶分库（见 Mem），人偶删了就把它的记忆一并清掉。
        edit.remove("memdb_list_" + dollId)
        // 【r13·新记忆系统】新版三份键（条目 / 连接 / 资料文档）也要清。
        edit.remove("mem2_list_" + dollId)
        edit.remove("mem2_links_" + dollId)
        edit.remove("mem2_profile_" + dollId)
        edit.commit()
    }
}
