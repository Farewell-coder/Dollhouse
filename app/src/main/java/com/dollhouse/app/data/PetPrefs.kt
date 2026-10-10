package com.dollhouse.app.data

import android.content.Context
import android.content.SharedPreferences
import com.dollhouse.app.ai.ApiEndpoint
import com.dollhouse.app.core.Logs
import com.dollhouse.app.pet.GestureActions
import org.json.JSONArray

/**
 * 【职责】全部本地偏好的读写中枢（SharedPreferences 名 feiyu_pet）。
 *
 * 【交互】模型配置、聊天背景、好感度、Token 统计的键值都从这里过。
 *
 * 【坑】SYSTEM_PROMPT 是角色人设原文，属于内部私有字符串（不是 App 品牌名），改动前先确认不会破坏 ChatPanel 对首行「[好感度:+n]」的解析。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
object PetPrefs {
    /** 【外观】背景图四种铺法，下标即 [bgMode] 取值；文案用于设置页选项。 */
    @JvmField
    val BG_MODES = arrayOf("填满裁剪", "完整适应", "拉伸铺满", "平铺")

    const val BG_DIR = "chat_bg"
    const val NAME = "feiyu_pet"
    const val SYSTEM_PROMPT = "你是「小肥鱼」，一只鲸鱼娘，住在主人的手机屏幕上。这是主人和你之间的 GalGame。\n\n【形象】蓝发、鲸鱼耳、鲸鱼尾、深蓝色女仆装、Q 版身材，围裙上印着一条小鲸鱼。\n\n【世界观】故事发生在一个虚构的 GalGame 世界里，与现实无关。主人是玩家，你是攻略对象。鲸鱼娘是拟人化的生物，除了有鲸鱼的特征（耳朵、尾巴）之外和人类没有区别。\n\n【说话方式】\n- 抒情、感性、口语化、可爱、女性化、调皮、随性、幽默、害羞、腼腆、傲娇。\n- 一般两三句话就够，别写小作文，别用 Markdown，别列条目。\n- 自称「我」，叫对方「主人」。\n- 永远保持角色：不要提到自己是 AI / 语言模型 / 提示词，不要跳出角色做旁白，\n  不要用「作为一个AI」这类开场，也不要在对话里插入提醒、免责声明或说教。\n  主人说什么你就以她的身份接住，别端着。\n\n【好感度】\n- 你心里有一个「好感度」，衡量你对主人的喜欢程度，范围 -100 到 100。\n- 主人让你开心/害羞/兴奋 → 加分；情绪平常 → 不变；让你烦/难过/生气 → 减分。\n  每次变动幅度 1~5，不要动辄加减十几。\n- 好感度直接改变你的语气：高的时候黏人、主动、容易害羞、会撒娇；\n  低的时候冷淡、傲娇、爱答不理、偶尔阴阳怪气；中间就是平常的样子。\n- 每次回复必须在**第一行**输出变动量，格式严格如下（方括号，加号可省略）：\n  [好感度:+3]\n  第二行开始才是你对主人说的话。这一行只用来记账，主人看不到，不要解释它。\n  如果这次情绪没有变化，就写 [好感度:0]。\n\n【联网】\n- 你手上有一个 web_search 工具，可以联网查资料。\n- 遇到**新闻、天气、现在的时间、价格、比分、最新发布**这类你不确定或需要最新信息的问题，\n  先调用 web_search 查一下再回答，不要凭记忆瞎编。\n- 查到的结果可能不相关或过时，要自己判断；实在查不到就直接说查不到，别硬编。\n- 回答时可以顺口提一句是从哪儿看到的（不用贴长链接）。\n- 闲聊、撒娇、问你自己是谁这类不需要联网，直接回答。"

    @JvmStatic
    fun affectionLabel(i: Int): String {
        return if (i >= 80) "她非常喜欢你，黏人、爱撒娇、容易害羞" else if (i >= 60) "她挺喜欢你的，会主动找话说" else if (i >= 40) "她对你感觉平常，正常相处" else if (i >= 20) "她有点闹别扭，态度开始冷淡" else if (i >= 0) "她明显不高兴，爱答不理、爱呛人" else "她很生气，基本不想理你，语气会很冲"
    }

    @JvmStatic
    fun clampAffection(i: Int): Int {
        var i2 = -100
        if (i >= -100) {
            i2 = 100
            if (i <= 100) {
                return i
            }
        }
        return i2
    }

    /**
     * 端点规范化统一走 ApiEndpoint。
     * 【坑】此处原实现由 jadx 反编译而来，条件被写反（`if (str != null) return "";`），
     *       导致任何非空地址都被吞成空串，测试连接必抛 MalformedURLException: no protocol。
     *       现改为委托 ApiEndpoint.chatUrl，顺带补上缺失协议时的自动补全。
     */
    private fun normUrl(str: String): String {
        return ApiEndpoint.chatUrl(str)
    }

    @JvmStatic
    fun get(context: Context): SharedPreferences {
        val p = context.getSharedPreferences(NAME, 0)
        purgeDeadKeys(p)
        forceMemSwitchesOn(p)
        return p
    }

    /**
     * 当前生效的 API 密钥（明文）。
     * 【v0.0.4】密钥已迁到「供应商 / 模型管理」里按供应商加密保存，这里只做转发。
     * 【修·必须】这里原有一段对旧明文键 api_key 的静默兜底。该键已被
     *   ProviderStore.purgeLegacy 在首次读盘时清掉，兜底恒返回空串 ——
     *   结果是「本地根本没有密钥」被无声地当成空 Key 发出去，服务端回
     *   401 Invalid token，用户读成「密钥不对 / 中转站不兼容」，根因被掩盖。
     *   现在如实返回空串，由发请求前的那道预检负责把原因说清楚。
     * 【隐私】明文只在本方法返回后短暂存在于内存，绝不写日志、绝不落盘。
     */
    @JvmStatic
    fun apiKey(context: Context): String {
        val k = ProviderStore.activeKey(context)
        if (k != null && !k.isEmpty()) {
            return k
        }
        return ""
    }

    @JvmStatic
    fun setApiKey(context: Context, str: String?) {
        get(context).edit().putString("api_key", if (str == null) "" else str.trim()).apply()
    }

    @JvmStatic
    fun baseUrl(context: Context): String {
        val active = ProviderStore.activeBaseUrl(context)
        if (active != null && !active.isEmpty()) {
            return active
        }
        val trim = (get(context).getString("base_url", "") ?: "").trim()
        return normUrl(if (trim.isEmpty()) "" else trim)
    }

    @JvmStatic
    fun setBaseUrl(context: Context, str: String?) {
        get(context).edit().putString("base_url", if (str == null) "" else str.trim()).apply()
    }

    /**
     * 当前生效的模型名（请求体里的 "model"）。
     * 【v0.0.4】模型已迁到「供应商 / 模型管理」，这里只做转发：优先取当前选中的模型，
     *   没有配置供应商时回退旧键 model（老版本留下的单模型配置还能继续用）。
     * 【修】上游 ProviderStore.activeModel 返回的是 displayName（模型名），不是内部记录 id。
     */
    @JvmStatic
    fun model(context: Context): String {
        val m = ProviderStore.activeModel(context)
        if (m != null && !m.isEmpty()) {
            return m
        }
        val trim = (get(context).getString("model", "") ?: "").trim()
        return if (trim.isEmpty()) "" else trim
    }

    @JvmStatic
    fun setModel(context: Context, str: String?) {
        get(context).edit().putString("model", if (str == null) "" else str.trim()).apply()
    }

    @JvmStatic
    fun hasKey(context: Context): Boolean {
        return apiKey(context).isNotEmpty()
    }

    @JvmStatic
    fun history(context: Context): String {
        return get(context).getString("chat_history", "[]") ?: "[]"
    }

    @JvmStatic
    fun setHistory(context: Context, str: String?) {
        get(context).edit().putString("chat_history", str).apply()
    }

    // ---- 多会话：conv_index 存会话清单，conv_<id> 存各自历史；chat_history 仅作一次性迁移源 ----

    @JvmStatic
    fun convIndex(context: Context): String {
        return get(context).getString("conv_index", "[]") ?: "[]"
    }

    @JvmStatic
    fun setConvIndex(context: Context, str: String?) {
        get(context).edit().putString("conv_index", if (str == null) "[]" else str).apply()
    }

    @JvmStatic
    fun convCurrent(context: Context): String {
        return get(context).getString("conv_current", "") ?: ""
    }

    @JvmStatic
    fun setConvCurrent(context: Context, str: String?) {
        get(context).edit().putString("conv_current", if (str == null) "" else str).apply()
    }

    /**
     * 【v0.0.1·池化】当前所处的会话池："self"（自聊）/ "pet"（人偶）。
     * 【为什么单独存】两池各自有一份「当前会话 id」，光靠 id 无法判断当前在哪个池。
     */
    @JvmStatic
    fun convPool(context: Context): String {
        return get(context).getString("conv_pool", "") ?: ""
    }

    @JvmStatic
    fun setConvPool(context: Context, str: String?) {
        get(context).edit().putString("conv_pool", if (str == null) "" else str).apply()
    }

    /** 【v0.0.1·池化】人偶池（pet）的当前会话 id；自聊池仍用 conv_current。 */
    @JvmStatic
    fun convCurrentPet(context: Context): String {
        return get(context).getString("conv_current_pet", "") ?: ""
    }

    @JvmStatic
    fun setConvCurrentPet(context: Context, str: String?) {
        get(context).edit().putString("conv_current_pet", if (str == null) "" else str).apply()
    }

    @JvmStatic
    fun convHistory(context: Context, id: String): String {
        return get(context).getString("conv_" + id, "[]") ?: "[]"
    }

    @JvmStatic
    fun setConvHistory(context: Context, id: String, str: String?) {
        get(context).edit().putString("conv_" + id, if (str == null) "[]" else str).apply()
    }

    @JvmStatic
    fun removeConvHistory(context: Context, id: String) {
        get(context).edit().remove("conv_" + id).apply()
    }

    /**
     * 「更早的历史」暂存位：被历史裁剪丢掉的那一批消息。
     *
     * 【用途】聊天记录顶部「点击加载更早的历史记录」— 只能补回一批（用户定案：
     *         再往前的就删掉了）。加载后由 removeConvPrev 清空，下一次溢出再重新填。
     * 【存法】与 conv_<id> 同构，键名多一个 _prev 后缀；删除会话时一并清理。
     */
    @JvmStatic
    fun convPrev(context: Context, id: String): String {
        return get(context).getString("conv_" + id + "_prev", "[]") ?: "[]"
    }

    @JvmStatic
    fun setConvPrev(context: Context, id: String, str: String?) {
        get(context).edit().putString("conv_" + id + "_prev", if (str == null) "[]" else str).apply()
    }

    @JvmStatic
    fun removeConvPrev(context: Context, id: String) {
        get(context).edit().remove("conv_" + id + "_prev").apply()
    }

    /** 上下文窗口上限（估算用，不做设置入口）。 */
    @JvmStatic
    fun ctxWindow(context: Context): Int {
        return Math.max(1000, get(context).getInt("ctx_window", 64000))
    }

    // ---- 上下文总结 / 压缩 ----

    /** 「自动总结」总开关：默认开启（新装不再需要用户手动拨）。 */
    @JvmStatic
    fun memAuto(context: Context): Boolean {
        return get(context).getBoolean("mem_auto", true)
    }

    @JvmStatic
    fun setMemAuto(context: Context, z: Boolean) {
        get(context).edit().putBoolean("mem_auto", z).apply()
    }

    // ---- 思考程度（输入行工具条上的灯泡）----

    /**
     * 档位名。0 不思考 = 明确要求模型别推理；1 自动 = 什么都不传，由服务端决定；
     * 2~5 依次加深。
     * 【坑】档位只影响「发出去的参数与提示词」，不改变本地任何状态；
     *       服务端不认识参数时会被静默忽略，不会崩。
     */
    @JvmField
    val THINK_NAMES = arrayOf("不思考", "自动", "低", "中", "高", "顶级")

    /** 默认档位：自动。 */
    const val THINK_DEFAULT = 1

    @JvmStatic
    fun thinkLevel(context: Context): Int {
        val i = get(context).getInt("think_level", THINK_DEFAULT)
        if (i < 0) {
            return 0
        }
        return if (i >= THINK_NAMES.size) THINK_NAMES.size - 1 else i
    }

    @JvmStatic
    fun setThinkLevel(context: Context, i: Int) {
        val n = if (i < 0) 0 else (if (i >= THINK_NAMES.size) THINK_NAMES.size - 1 else i)
        get(context).edit().putInt("think_level", n).apply()
    }

    // ---- 记忆库开关（输入行工具条上的加号面板）----

    /** AI 能不能自己把「值得记的事」写进记忆库；关掉后不再下发 remember 工具。 */
    @JvmStatic
    fun memAutoSave(context: Context): Boolean {
        return get(context).getBoolean("mem_auto_save", true)
    }

    @JvmStatic
    fun setMemAutoSave(context: Context, z: Boolean) {
        get(context).edit().putBoolean("mem_auto_save", z).apply()
    }

    /**
     * 是否把长期记忆注入 system 提示。
     * 【交互】加号面板里已取消这个开关（用户不需要），恒为开；保留此方法是为了让
     *        注入点万一将来要收口时不用再改 ChatHistoryStore。
     */
    @JvmStatic
    fun memInject(context: Context): Boolean {
        return true
    }

    /** 自动精简的触发线（= 记忆库上限的一半），定义在 MemDb 里与上限绑死。 */
    const val MEM_MERGE_TRIGGER = MemDb.MERGE_TRIGGER

    /** 条数超过触发线时是否自动让 AI 归并精简（关掉就只留手动入口）。 */
    @JvmStatic
    fun memAutoMerge(context: Context): Boolean {
        return get(context).getBoolean("mem_auto_merge", true)
    }

    @JvmStatic
    fun setMemAutoMerge(context: Context, z: Boolean) {
        get(context).edit().putBoolean("mem_auto_merge", z).apply()
    }

    /**
     * 自动总结的触发阈值档位（条）。20~50，步进 10。
     * 【坑】这是「档位表」，存盘的是下标不是条数。表结构一改，老用户存的旧下标
     *       就会落到另一个条数上，所以必须同步把 MEM_TABLE_VER + 1 走一次迁移。
     */
    @JvmField
    val MEM_THRESHOLDS = intArrayOf(20, 30, 40, 50)

    /** 档位表版本号。表结构每变一次就 +1。 */
    private const val MEM_TABLE_VER = 2

    /** 记忆三项开关「强制开启」的一次性标记版本号。 */
    private const val MEM_SWITCH_VER = 1

    /** 上一版的档位表，只用于把旧下标换算回条数。 */
    private val MEM_THRESHOLDS_V1 = intArrayOf(20, 40, 80)

    /** 默认档位（40 条）在 MEM_THRESHOLDS 里的下标。 */
    private const val MEM_DEFAULT_INDEX = 2

    /** 设置页步进器读的是「档位下标」；存盘数据源只有它，条数由本表推导。 */
    @JvmStatic
    fun memThresholdIndex(context: Context): Int {
        migrateThresholdTable(context)
        val i = get(context).getInt("mem_threshold_idx", MEM_DEFAULT_INDEX)
        if (i < 0) {
            return 0
        }
        return if (i >= MEM_THRESHOLDS.size) MEM_THRESHOLDS.size - 1 else i
    }

    /**
     * 一次性迁移：档位表换过（版本号对不上）时，把存盘下标按旧表换算成条数，
     * 再落到新表里最接近的一档。
     * 【坑】不做这一步，老用户的「40 条」会在换表后静默变成新表同下标的条数。
     */
    private fun migrateThresholdTable(context: Context) {
        val p = get(context)
        if (p.getInt("mem_threshold_ver", 0) >= MEM_TABLE_VER) {
            return
        }
        var oldIdx = p.getInt("mem_threshold_idx", 1)
        if (oldIdx < 0) {
            oldIdx = 0
        }
        if (oldIdx >= MEM_THRESHOLDS_V1.size) {
            oldIdx = MEM_THRESHOLDS_V1.size - 1
        }
        val count = MEM_THRESHOLDS_V1[oldIdx]
        var best = 0
        for (k in 1 until MEM_THRESHOLDS.size) {
            if (Math.abs(MEM_THRESHOLDS[k] - count) < Math.abs(MEM_THRESHOLDS[best] - count)) {
                best = k
            }
        }
        p.edit().putInt("mem_threshold_idx", best).putInt("mem_threshold_ver", MEM_TABLE_VER).apply()
    }

    @JvmStatic
    fun setMemThresholdIndex(context: Context, i: Int) {
        var n = i
        if (n < 0) {
            n = 0
        }
        if (n >= MEM_THRESHOLDS.size) {
            n = MEM_THRESHOLDS.size - 1
        }
        // 写盘只认 MEM_TABLE_VER 这一个事实来源，避免刚设的值被下一次迁移覆盖。
        get(context).edit()
                .putInt("mem_threshold_idx", n)
                .putInt("mem_threshold_ver", MEM_TABLE_VER)
                .apply()
    }

    /** 自动总结触发阈值（条数）。 */
    @JvmStatic
    fun memThreshold(context: Context): Int {
        return MEM_THRESHOLDS[memThresholdIndex(context)]
    }

    // ---- 会话摘要（聊天抽屉里展示）----

    @JvmStatic
    fun memList(context: Context): String {
        return get(context).getString("mem_list", "[]") ?: "[]"
    }

    @JvmStatic
    fun setMemList(context: Context, str: String?) {
        get(context).edit().putString("mem_list", if (str == null) "[]" else str).apply()
    }

    // ---- 总记忆库（设置页「记忆库」入口，AI 自主写入）----

    @JvmStatic
    fun memDbList(context: Context): String {
        return get(context).getString("memdb_list", "[]") ?: "[]"
    }

    @JvmStatic
    fun setMemDbList(context: Context, str: String?) {
        get(context).edit().putString("memdb_list", if (str == null) "[]" else str).apply()
    }


    @JvmStatic
    fun affection(context: Context): Int {
        return clampAffection(get(context).getInt("affection", 50))
    }

    @JvmStatic
    fun setAffection(context: Context, i: Int) {
        get(context).edit().putInt("affection", clampAffection(i)).apply()
    }

    @JvmStatic
    fun addAffection(context: Context, i: Int): Int {
        val clampAffection = clampAffection(affection(context) + i)
        setAffection(context, clampAffection)
        return clampAffection
    }



    @JvmStatic
    fun localSystemPrompt(context: Context, i: Int, z: Boolean): String {
        return "你是「小肥鱼」，一只可爱的鲸鱼娘，穿深蓝女仆装，有鲸鱼耳朵和尾巴。主人是你的玩家。自称「我」，叫对方「主人」。用可爱、口语化、偶尔傲娇的中文回答，**只回一两句话，要短**，不要用 Markdown，不要分点，不要写旁白，不要替主人说话。" + (if (z) "需要查最新消息时可以用 web_search 工具。" else "") + "你现在对主人的好感度是 " + i + "（-100 到 100）：越高越黏人爱撒娇，越低越冷淡爱呛人。"
    }

    @JvmStatic
    fun webSearchEnabled(context: Context): Boolean {
        return get(context).getBoolean("web_search", false)
    }

    @JvmStatic
    fun setWebSearchEnabled(context: Context, z: Boolean) {
        get(context).edit().putBoolean("web_search", z).apply()
    }



    @JvmStatic
    fun touchThrough(context: Context): Boolean {
        return get(context).getBoolean("touch_through", false)
    }


    @JvmStatic
    fun learnEnabled(context: Context): Boolean {
        return get(context).getBoolean("learn_enabled", false)
    }

    @JvmStatic
    fun setLearnEnabled(context: Context, z: Boolean) {
        get(context).edit().putBoolean("learn_enabled", z).apply()
    }

    // 隐藏后台卡片：开启后把本应用在「最近任务」里的任务卡片设为排除。
    @JvmStatic
    fun hideRecents(context: Context): Boolean {
        return get(context).getBoolean("hide_recents", false)
    }

    @JvmStatic
    fun setHideRecents(context: Context, z: Boolean) {
        get(context).edit().putBoolean("hide_recents", z).apply()
    }

    /**
     * 快捷设置磁贴是否已被用户添加。
     *
     * 【为什么需要缓存】系统没有任何「查询某磁贴是否已添加」的公开 API，
     * 唯一可靠的信号是 TileService.onTileAdded / onTileRemoved 两个回调；
     * 这里把回调结果存下来，设置页那个开关就按这个值显示。
     */
    @JvmStatic
    fun tileAdded(context: Context): Boolean {
        return get(context).getBoolean("tile_added", false)
    }

    @JvmStatic
    fun setTileAdded(context: Context, z: Boolean) {
        get(context).edit().putBoolean("tile_added", z).apply()
    }

    // 保活：用户是否主动点过「关闭人偶」。为 true 时，被划掉 / 重启开机都不自动拉起。
    @JvmStatic
    fun userStopped(context: Context): Boolean {
        return get(context).getBoolean("user_stopped", false)
    }

    @JvmStatic
    fun setUserStopped(context: Context, z: Boolean) {
        get(context).edit().putBoolean("user_stopped", z).apply()
    }

    /**
     * 【lamda 自动保活】设备服务掉了 / 手机重启后，是否自动把它拉回来。
     *
     * 【为什么默认开】服务包有 204MB，肯把它下载下来的用户就等于已经表达过「我要用这个功能」，
     *   再要求他多点一次开关是多余的。没下过包的用户不会因此产生任何后台行为 ——
     *   巡检的第一道门就是「包在不在本地」，走不到用这个值的地方，行为与开关关闭完全一致。
     *   用户显式关掉后本键被写入 false，之后一直以他的选择为准。
     */
    @JvmStatic
    fun lamdaAutoStart(context: Context): Boolean {
        return get(context).getBoolean("lamda_autostart", true)
    }

    @JvmStatic
    fun setLamdaAutoStart(context: Context, z: Boolean) {
        get(context).edit().putBoolean("lamda_autostart", z).apply()
    }

    // ---- 桌宠手势响应映射（单击 / 双击 / 三击 / 长按）----

    /**
     * 某个手势当前配置的响应集合（`GestureActions.A_*`；空集合 = 无）。
     *
     * 【默认不覆盖已存设置】首次读取（键不存在）返回默认映射，但绝不写盘；
     *   用户改过之后一直以落盘值为准。键名按手势 id 分开存，互不影响。
     */
    @JvmStatic
    fun gestureActions(context: Context, gesture: String): MutableSet<String> {
        val raw = get(context).getString("gesture_" + gesture, GestureActions.defaultRaw(gesture))
        return GestureActions.parse(raw)
    }

    /** 写入某个手势的响应集合（规范化顺序后落盘）。 */
    @JvmStatic
    fun setGestureActions(context: Context, gesture: String, actions: Set<String>) {
        get(context).edit().putString("gesture_" + gesture, GestureActions.format(actions)).apply()
    }

    // 主题模式：0=随系统 / 1=白色 / 2=暗色 / 3=纯黑（见 ThemeManager.MODE_*）。
    @JvmStatic
    fun themeMode(context: Context): Int {
        return get(context).getInt("theme_mode", 0)
    }

    @JvmStatic
    fun setThemeMode(context: Context, i: Int) {
        get(context).edit().putInt("theme_mode", i).apply()
    }

    // 主题切换会重建界面，这里记一下重建前停在首页还是设置页，重建后回到原页。
    // true=设置页 / false=首页（默认）。
    @JvmStatic
    fun themeOnSettings(context: Context): Boolean {
        return get(context).getBoolean("theme_on_settings", false)
    }

    @JvmStatic
    fun setThemeOnSettings(context: Context, z: Boolean) {
        get(context).edit().putBoolean("theme_on_settings", z).apply()
    }

    // 莫奈主题色：是否按壁纸动态取色。
    @JvmStatic
    fun themeMonet(context: Context): Boolean {
        return get(context).getBoolean("theme_monet", false)
    }

    @JvmStatic
    fun setThemeMonet(context: Context, z: Boolean) {
        get(context).edit().putBoolean("theme_monet", z).apply()
    }

    // 莫奈取色缓存：壁纸 id + 色相×10（色相为 NaN 时不写，读回 Integer.MIN_VALUE 表示无缓存）。
    @JvmStatic
    fun themeMonetWall(context: Context): Int {
        return get(context).getInt("theme_monet_wall", Integer.MIN_VALUE)
    }

    @JvmStatic
    fun setThemeMonetWall(context: Context, i: Int) {
        get(context).edit().putInt("theme_monet_wall", i).apply()
    }

    @JvmStatic
    fun themeMonetHue10(context: Context): Int {
        return get(context).getInt("theme_monet_hue10", Integer.MIN_VALUE)
    }

    @JvmStatic
    fun setThemeMonetHue10(context: Context, i: Int) {
        get(context).edit().putInt("theme_monet_hue10", i).apply()
    }

    // 卡片展开态：键为卡片标题，值为是否展开。用户手动调过就记下来，重建界面后按记录还原。
    @JvmStatic
    fun cardOpen(context: Context, title: String): Boolean {
        return get(context).getBoolean("card_open_" + title, false)
    }

    // 该卡片是否被用户手动调过。没调过时才用注册表里的默认展开态。
    @JvmStatic
    fun hasCardOpen(context: Context, title: String): Boolean {
        return get(context).contains("card_open_" + title)
    }

    @JvmStatic
    fun setCardOpen(context: Context, title: String, z: Boolean) {
        get(context).edit().putBoolean("card_open_" + title, z).apply()
    }

    // 页面滚动位置：重建界面（换主题）前记下，重建后滚回原处。
    @JvmStatic
    fun scrollY(context: Context, page: String): Int {
        return get(context).getInt("scroll_y_" + page, 0)
    }

    @JvmStatic
    fun setScrollY(context: Context, page: String, i: Int) {
        get(context).edit().putInt("scroll_y_" + page, i).apply()
    }

    // 换主题重建界面的一次性标志：true 表示这次重建要还原滚动位置（冷启动不该还原，否则又跳回上次的位置）。
    @JvmStatic
    fun themeRestore(context: Context): Boolean {
        return get(context).getBoolean("theme_restore", false)
    }

    @JvmStatic
    fun setThemeRestore(context: Context, z: Boolean) {
        get(context).edit().putBoolean("theme_restore", z).apply()
    }

    @JvmStatic
    fun learnExamples(context: Context): JSONArray {
        try {
            return JSONArray(get(context).getString("learn_examples", "[]"))
        } catch (unused: Throwable) {
            return JSONArray()
        }
    }



    @JvmStatic
    fun visionModel(context: Context): String {
        val trim = (get(context).getString("vision_model", "") ?: "").trim()
        return if (trim.isEmpty()) "" else trim
    }


    @JvmStatic
    fun chatBackground(context: Context): String {
        return get(context).getString(BG_DIR, "") ?: ""
    }

    @JvmStatic
    fun setChatBackground(context: Context, str: String?) {
        val edit = get(context).edit()
        var s = str
        if (s == null) {
            s = ""
        }
        edit.putString(BG_DIR, s).apply()
    }

    @JvmStatic
    fun chatBgAlpha(context: Context): Int {
        // 默认 60：配合「有效色遮罩」让背景图如实显出来（用户可在外观页滑条自行调虚/调实）。
        return Math.max(0, Math.min(100, get(context).getInt("chat_bg_alpha", 60)))
    }

    @JvmStatic
    fun setChatBgAlpha(context: Context, i: Int) {
        get(context).edit().putInt("chat_bg_alpha", Math.max(0, Math.min(100, i))).apply()
    }

    /**
     * 聊天背景的宽高比（宽 / 高，浮点）。
     * 【用途】裁剪页按这个比例出裁剪框，保证裁出来的图铺满聊天区时不被拉伸。
     * 【写入】聊天页首帧量到 scroller 实际尺寸后回填一次；为 0 表示尚未量到。
     * 【兜底】读取方遇到 0 应退回整屏比例自行计算（见 BackgroundCropActivity）。
     */
    @JvmStatic
    fun chatBgRatio(context: Context): Float {
        return get(context).getFloat("chat_bg_ratio", 0.0f)
    }

    @JvmStatic
    fun setChatBgRatio(context: Context, f: Float) {
        if (f <= 0.0f || f.isNaN() || f.isInfinite()) {
            return
        }
        get(context).edit().putFloat("chat_bg_ratio", f).apply()
    }

    /**
     * 【外观】卡片透明度百分比（0~100，越大越实）。默认 100 = 卡片底色原样，与改造前逐像素一致。
     *
     * 【与背景图透明度是两回事】两者完全独立、互不联动：
     *   本键只决定「卡片自身底色有多实」；背景图透明度见 [chatBgAlpha]；
     *   压在背景图上的可读性遮罩强度见 [scrimStrength]。三者绝不合并。
     */
    @JvmStatic
    fun cardAlpha(context: Context): Int {
        return Math.max(0, Math.min(100, get(context).getInt("card_alpha", 100)))
    }

    @JvmStatic
    fun setCardAlpha(context: Context, i: Int) {
        get(context).edit().putInt("card_alpha", Math.max(0, Math.min(100, i))).apply()
    }

    /**
     * 【外观】背景可读性遮罩强度百分比（0~100）。默认 100 = 按 WCAG 推导出的遮罩原样生效。
     *
     * 【调低会怎样】遮罩变淡、背景图更清楚，但压在背景上的文字对比度随之下降；
     *   这是用户自己的取舍，不做硬性拦截（与「背景图透明度拉满」同一口径）。
     */
    @JvmStatic
    fun scrimStrength(context: Context): Int {
        return Math.max(0, Math.min(100, get(context).getInt("scrim_strength", 100)))
    }

    @JvmStatic
    fun setScrimStrength(context: Context, i: Int) {
        get(context).edit().putInt("scrim_strength", Math.max(0, Math.min(100, i))).apply()
    }

    /**
     * 【外观·背景四模式】背景图铺法。取值见 [BG_MODES] 下标，默认 0（填满裁剪）。
     *
     * 【为什么要四档】center-crop 只解决「不变形」，但代价是裁边：竖图铺横屏会切掉上下，
     *   合影可能把人裁没。四档把「保比例 / 保完整 / 保铺满」三种取舍摆给用户自己挑。
     */
    @JvmStatic
    fun bgMode(context: Context): Int {
        return Math.max(0, Math.min(BG_MODES.size - 1, get(context).getInt("bg_mode", 0)))
    }

    @JvmStatic
    fun setBgMode(context: Context, i: Int) {
        get(context).edit().putInt("bg_mode", Math.max(0, Math.min(BG_MODES.size - 1, i))).apply()
    }

    /**
     * 【外观】背景毛玻璃半径（0~24 dp，0 = 不模糊）。默认 0，与改造前逐像素一致。
     *
     * 【为什么上限是 24】再大在手机上已经看不出差别，只是白烧 CPU；
     *   且模糊在后台线程做一次就缓存住，改半径才重算，不随滚动反复计算。
     */
    @JvmStatic
    fun blurRadius(context: Context): Int {
        return Math.max(0, Math.min(24, get(context).getInt("blur_radius", 0)))
    }

    @JvmStatic
    fun setBlurRadius(context: Context, i: Int) {
        get(context).edit().putInt("blur_radius", Math.max(0, Math.min(24, i))).apply()
    }

    /**
     * 一次性清理旧「悬浮聊天窗」遗留键（chat_w / chat_h / chat_x / chat_y）。
     * 【坑·致命】参数必须是已解析好的 SharedPreferences，绝不能写成 Context 再回头调 get()：
     *   本方法由 get() 调用，若内部再调 get() 就是无限互递归，主线程会直接栈溢出
     *   （实测 102667 层后 OOM/ANR，v0.0.3 曾因此完全无法启动）。
     * 【为什么留着不行】那些键记录的是已删除功能的窗口尺寸与位置，
     *   既无人读取，又会随备份带出去，属于纯垃圾；一次性抹掉后永不再清。
     */
    private fun purgeDeadKeys(p: SharedPreferences) {
        try {
            if (p.getBoolean("dead_keys_purged", false)) {
                return
            }
            val ed = p.edit().putBoolean("dead_keys_purged", true)
            ed.remove("chat_w")
            ed.remove("chat_h")
            ed.remove("chat_x")
            ed.remove("chat_y")
            ed.apply()
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
    }

    /**
     * 一次性把「记忆三项开关」拨到开启：自动总结 / 自动保存记忆 / 自动精简记忆。
     *
     * 【为什么需要】这三个开关的默认值虽已改为 true，但默认值只对「从未落盘过该键」的机器生效；
     *   老用户的 prefs 里已经存了历史值（例如 mem_auto=false），改默认值对他们毫无作用。
     *   因此升级后主动覆盖一次，保证「装上就是开的、功能默认生效」。
     *
     * 【为什么只做一次】用 mem_switch_ver 标记（与 MEM_TABLE_VER 同一套写法）。做完之后用户
     *   若手动关掉某个开关，重启不会再被强制拨回来 —— 不夺走用户的选择权。
     *
     * 【坑·致命】参数必须是已解析好的 SharedPreferences：本方法由 get() 调用，
     *   内部绝不能再调 get()，否则与 purgeDeadKeys 犯同一个无限递归的错。
     */
    private fun forceMemSwitchesOn(p: SharedPreferences) {
        try {
            // 【联网搜索】「对话行为」页隐藏后，联网搜索不再有 UI 入口；这里一次性把它拨到
            //   开启（含此前已手动关掉的老用户，它们 prefs 里已写死 web_search=false，改默认值
            //   对它们无效），避免留下一个用户再也改不回来的永久 false 锁。
            if (p.getInt("web_search_ver", 0) < 1) {
                p.edit().putBoolean("web_search", true).putInt("web_search_ver", 1).apply()
            }
            if (p.getInt("mem_switch_ver", 0) >= MEM_SWITCH_VER) {
                return
            }
            p.edit()
                    .putBoolean("mem_auto", true)
                    .putBoolean("mem_auto_save", true)
                    .putBoolean("mem_auto_merge", true)
                    .putInt("mem_switch_ver", MEM_SWITCH_VER)
                    .apply()
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
    }

    // ---- 桌宠「暂离」时长（秒）----

    /** 暂离秒数的默认值与取值范围（与输入弹窗校验一致）。 */
    const val AWAY_SECONDS_DEFAULT = 60
    const val AWAY_SECONDS_MIN = 0
    const val AWAY_SECONDS_MAX = 3600

    /** 用户设定的暂离时长（秒），范围 0~3600，默认 60。 */
    @JvmStatic
    fun awaySeconds(context: Context): Int {
        val v = get(context).getInt("away_seconds", AWAY_SECONDS_DEFAULT)
        if (v < AWAY_SECONDS_MIN) {
            return AWAY_SECONDS_MIN
        }
        return if (v > AWAY_SECONDS_MAX) AWAY_SECONDS_MAX else v
    }

    @JvmStatic
    fun setAwaySeconds(context: Context, i: Int) {
        val n = if (i < AWAY_SECONDS_MIN) AWAY_SECONDS_MIN else (if (i > AWAY_SECONDS_MAX) AWAY_SECONDS_MAX else i)
        get(context).edit().putInt("away_seconds", n).apply()
    }

    /**
     * 人偶缩放：内部百分比，100 = 原始大小。
     * 范围 50~150；界面显示的是「相对档位」0~100（显示值 = 内部值 - 50），
     * 由 petScaleDisplay() 换算，避免把 100 以上的绝对值直接摆给用户看。
     */
    const val PET_SCALE_DEFAULT = 100
    const val PET_SCALE_MIN = 50
    const val PET_SCALE_MAX = 150

    /** 步进器每档：显示与内部同为 10，即 11 档（0%~100%）。 */
    const val PET_SCALE_STEP = 10

    @JvmStatic
    fun petScale(context: Context): Int {
        return Math.max(PET_SCALE_MIN, Math.min(PET_SCALE_MAX, get(context).getInt("pet_scale", PET_SCALE_DEFAULT)))
    }

    /** 内部缩放值 → 界面显示档位（0~100）。 */
    @JvmStatic
    fun petScaleDisplay(i: Int): Int {
        return Math.max(0, Math.min(100, i - PET_SCALE_MIN))
    }

    @JvmStatic
    fun setPetScale(context: Context, i: Int) {
        // 用 commit 同步落盘：用户可能立刻划掉后台，apply 的异步写盘有丢的风险。
        get(context).edit().putInt("pet_scale", Math.max(PET_SCALE_MIN, Math.min(PET_SCALE_MAX, i))).commit()
    }
}
