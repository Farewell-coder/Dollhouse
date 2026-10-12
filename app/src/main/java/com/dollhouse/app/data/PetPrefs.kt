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
 * 【交互】模型配置、聊天背景、Token 统计的键值都从这里过。
 *
 * 【r11 起】SYSTEM_PROMPT 只作「默认配置」这只人偶的性格种子（由 DollProfile.seedDefault 写入）；
 *   聊天时下发给模型的人设改读 DollProfile.persona（按人偶取），本常量不再直接进请求体。
 *   好感度段与联网段已按需求移除，剩余 4 段：身份 /【形象】/【世界观】/【说话方式】。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
object PetPrefs {
    /** 【外观】背景图四种铺法，下标即 [bgMode] 取值；文案用于设置页选项。 */
    @JvmField
    val BG_MODES = arrayOf("填满裁剪", "完整适应", "拉伸铺满", "平铺")

    const val BG_DIR = "chat_bg"
    const val NAME = "feiyu_pet"
    /**
     * 【默认配置的性格种子】新装 / 升级后由 DollProfile.seedDefault 写入「默认配置」人偶的性格内容。
     * 【r11 起不再直接进请求体】聊天下发的是 DollProfile.persona（按人偶取）；
     *   本常量只在种子写入与上下文占用估算里用到。
     */
    const val SYSTEM_PROMPT = "你是「小肥鱼」，一只鲸鱼娘，住在主人的手机屏幕上。这是主人和你之间的 GalGame。\n\n【形象】蓝发、鲸鱼耳、鲸鱼尾、深蓝色女仆装、Q 版身材，围裙上印着一条小鲸鱼。\n\n【世界观】故事发生在一个虚构的 GalGame 世界里，与现实无关。主人是玩家，你是攻略对象。鲸鱼娘是拟人化的生物，除了有鲸鱼的特征（耳朵、尾巴）之外和人类没有区别。\n\n【说话方式】\n- 抒情、感性、口语化、可爱、女性化、调皮、随性、幽默、害羞、腼腆、傲娇。\n- 一般两三句话就够，别写小作文，别用 Markdown，别列条目。\n- 自称「我」，叫对方「主人」。\n- 永远保持角色：不要提到自己是 AI / 语言模型 / 提示词，不要跳出角色做旁白，\n  不要用「作为一个AI」这类开场，也不要在对话里插入提醒、免责声明或说教。\n  主人说什么你就以她的身份接住，别端着。"

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
        purgeAffection(p)
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

    // ---- 【r13·记忆系统重构】旧记忆系统的键已全部下线 ----
    //
    // 【删了什么】`mem_auto`（自动总结）/ `mem_auto_save`（自动保存记忆）/ `mem_inject` /
    //   `mem_auto_merge`（自动精简）/ `mem_threshold_idx`（触发阈值档位）/ `mem_list`（会话摘要）
    //   以及配套的档位表、迁移逻辑、一次性开关覆盖。
    //
    // 【为什么能删】旧记忆系统（MemSummarizer / MemMerger / MemStore / MemDb）已被
    //   Operit 式的结构化记忆系统取代（见 [com.dollhouse.app.data.Mem] /
    //   [com.dollhouse.app.ai.MemExtractor]）。新系统用 `mem2_*` 键，与这些键没有任何读取关系。
    //
    // 【磁盘上的旧键怎么办】按用户定案「旧数据全丢、从零开始」处理：键留在磁盘上不删，
    //   代码里再无任何路径读它 —— 于是界面从零开始，老数据只是安静地留着。
    //
    // ---- 总记忆库（旧版扁平条目，已由 mem2_* 取代）----

    /**
     * 【r11 分库】记忆库 JSON 文本的键名：一律 `memdb_list_<dollId>`。
     *
     * 【为什么不沿用历史键】需求 34 明确「全局旧数据**界面不读**、磁盘不删」：
     *   升级前那份全局记忆（键名 `memdb_list`）不再被任何代码读取，留在磁盘上不删；
     *   默认配置也用自己的新键，于是界面从零开始，老数据只是安静地留着。
     */
    private fun memDbKey(dollId: String?): String {
        val id = if (dollId == null || dollId.isEmpty()) "default" else dollId
        return "memdb_list_" + id
    }

    /** 【r11】某只人偶的记忆库 JSON 文本（按 dollId 分库，互不可见）。 */
    @JvmStatic
    fun memDbList(context: Context, dollId: String?): String {
        return get(context).getString(memDbKey(dollId), "[]") ?: "[]"
    }

    @JvmStatic
    fun setMemDbList(context: Context, dollId: String?, str: String?) {
        get(context).edit().putString(memDbKey(dollId), if (str == null) "[]" else str).apply()
    }

    // ---- 旧签名保留（无 dollId 时等价于默认配置）----

    /** @deprecated r11 起记忆按人偶分库；保留只为兼容旧调用点，语义 = 默认配置。 */
    @JvmStatic
    fun memDbList(context: Context): String {
        return memDbList(context, "default")
    }

    @JvmStatic
    fun setMemDbList(context: Context, str: String?) {
        setMemDbList(context, "default", str)
    }

    // ============================ v0.0.7 新记忆系统键 ============================

    /**
     * 【新记忆系统】键名前缀。
     *
     * 【为什么用 mem2_ 而不是沿用 memdb_】旧系统（`memdb_list_<dollId>`）按需求
     *   「旧数据全丢、从零开始」处理：新系统一律用 `mem2_*` 键，界面只读新键，
     *   旧键留在磁盘上不动、代码里再无任何路径读它。
     */
    private const val MEM2_PREFIX = "mem2_"

    /**
     * 记忆条目的归属人偶 id。
     *
     * 【为什么独立成方法】三处键（list / links / profile）都要用它，
     *   且必须与 [com.dollhouse.app.data.Mem] 的口径完全一致 —— 一处不一致就会串库。
     */
    private fun mem2Doll(dollId: String?): String {
        return if (dollId == null || dollId.isEmpty()) "default" else dollId
    }

    /** 某只人偶的记忆条目 JSON 文本（`mem2_list_<dollId>`）。 */
    @JvmStatic
    fun mem2List(context: Context, dollId: String?): String {
        return get(context).getString(MEM2_PREFIX + "list_" + mem2Doll(dollId), "[]") ?: "[]"
    }

    @JvmStatic
    fun setMem2List(context: Context, dollId: String?, str: String?) {
        get(context).edit()
            .putString(MEM2_PREFIX + "list_" + mem2Doll(dollId), if (str == null) "[]" else str)
            .apply()
    }

    /** 某只人偶的记忆连接（图谱边）JSON 文本（`mem2_links_<dollId>`）。 */
    @JvmStatic
    fun mem2Links(context: Context, dollId: String?): String {
        return get(context).getString(MEM2_PREFIX + "links_" + mem2Doll(dollId), "[]") ?: "[]"
    }

    @JvmStatic
    fun setMem2Links(context: Context, dollId: String?, str: String?) {
        get(context).edit()
            .putString(MEM2_PREFIX + "links_" + mem2Doll(dollId), if (str == null) "[]" else str)
            .apply()
    }

    /** 某只人偶的记忆空间资料（Markdown 用户画像，`mem2_profile_<dollId>`）。 */
    @JvmStatic
    fun mem2Profile(context: Context, dollId: String?): String {
        return get(context).getString(MEM2_PREFIX + "profile_" + mem2Doll(dollId), "") ?: ""
    }

    @JvmStatic
    fun setMem2Profile(context: Context, dollId: String?, str: String?) {
        get(context).edit()
            .putString(MEM2_PREFIX + "profile_" + mem2Doll(dollId), str ?: "")
            .apply()
    }

    /** 删掉某只人偶的全部新记忆系统数据（删人偶时调用）。 */
    @JvmStatic
    fun removeMem2All(context: Context, dollId: String?) {
        val d = mem2Doll(dollId)
        get(context).edit()
            .remove(MEM2_PREFIX + "list_" + d)
            .remove(MEM2_PREFIX + "links_" + d)
            .remove(MEM2_PREFIX + "profile_" + d)
            .apply()
    }

    // ---- 新记忆系统的全局策略（放在设置页「实验 → 记忆库设置」里）----

    /** 自动保存记忆：聊完一轮后是否自动抽取并写库。 */
    @JvmStatic
    fun mem2Auto(context: Context): Boolean {
        return get(context).getBoolean("mem2_auto", true)
    }

    @JvmStatic
    fun setMem2Auto(context: Context, z: Boolean) {
        get(context).edit().putBoolean("mem2_auto", z).apply()
    }

    /** 自动保存间隔档位（分钟）与默认下标。 */
    val MEM2_INTERVALS = intArrayOf(5, 10, 20, 30, 60, 120)

    private const val MEM2_INTERVAL_DEFAULT = 3

    @JvmStatic
    fun mem2IntervalIndex(context: Context): Int {
        val i = get(context).getInt("mem2_interval_idx", MEM2_INTERVAL_DEFAULT)
        return if (i < 0) 0 else if (i >= MEM2_INTERVALS.size) MEM2_INTERVALS.size - 1 else i
    }

    @JvmStatic
    fun setMem2IntervalIndex(context: Context, i: Int) {
        var n = i
        if (n < 0) {
            n = 0
        }
        if (n >= MEM2_INTERVALS.size) {
            n = MEM2_INTERVALS.size - 1
        }
        get(context).edit().putInt("mem2_interval_idx", n).apply()
    }

    @JvmStatic
    fun mem2IntervalMinutes(context: Context): Int {
        return MEM2_INTERVALS[mem2IntervalIndex(context)]
    }

    /** 记忆提取附加规则：一段自然语言，拼进抽取 prompt。 */
    @JvmStatic
    fun memExtractRule(context: Context): String {
        return get(context).getString("mem2_extract_rule", "") ?: ""
    }

    @JvmStatic
    fun setMemExtractRule(context: Context, str: String?) {
        get(context).edit().putString("mem2_extract_rule", str ?: "").apply()
    }

    /** 记忆检索：是否按相关性挑记忆注入（关掉则只按时间倒序取）。 */
    @JvmStatic
    fun mem2Search(context: Context): Boolean {
        return get(context).getBoolean("mem2_search", true)
    }

    @JvmStatic
    fun setMem2Search(context: Context, z: Boolean) {
        get(context).edit().putBoolean("mem2_search", z).apply()
    }

    /** 检索权重（0~10）：关键词 / 标签 / 关联传播。 */
    @JvmStatic
    fun mem2KeywordWeight(context: Context): Int {
        return get(context).getInt("mem2_w_keyword", 10)
    }

    @JvmStatic
    fun setMem2KeywordWeight(context: Context, v: Int) {
        get(context).edit().putInt("mem2_w_keyword", clampW(v)).apply()
    }

    @JvmStatic
    fun mem2TagWeight(context: Context): Int {
        return get(context).getInt("mem2_w_tag", 4)
    }

    @JvmStatic
    fun setMem2TagWeight(context: Context, v: Int) {
        get(context).edit().putInt("mem2_w_tag", clampW(v)).apply()
    }

    @JvmStatic
    fun mem2EdgeWeight(context: Context): Int {
        return get(context).getInt("mem2_w_edge", 4)
    }

    @JvmStatic
    fun setMem2EdgeWeight(context: Context, v: Int) {
        get(context).edit().putInt("mem2_w_edge", clampW(v)).apply()
    }

    private fun clampW(v: Int): Int {
        return if (v < 0) 0 else if (v > 10) 10 else v
    }

    /**
     * 检索模式：0 平衡 / 1 关键词优先 / 2 关联优先。
     *
     * 【与权重的分工】模式是一键预设（覆盖三条权重），权重是细调。
     *   切模式时会把权重一并写进去，用户再手动改权重则以手动值为准。
     */
    @JvmStatic
    fun mem2Mode(context: Context): Int {
        val m = get(context).getInt("mem2_mode", 0)
        return if (m < 0 || m > 2) 0 else m
    }

    @JvmStatic
    fun setMem2Mode(context: Context, m: Int) {
        val v = if (m < 0) 0 else if (m > 2) 2 else m
        val e = get(context).edit()
        e.putInt("mem2_mode", v)
        // 预设：平衡 10/4/4、关键词优先 10/1/1、关联优先 4/2/10。
        when (v) {
            1 -> e.putInt("mem2_w_keyword", 10).putInt("mem2_w_tag", 1).putInt("mem2_w_edge", 1)
            2 -> e.putInt("mem2_w_keyword", 4).putInt("mem2_w_tag", 2).putInt("mem2_w_edge", 10)
            else -> e.putInt("mem2_w_keyword", 10).putInt("mem2_w_tag", 4).putInt("mem2_w_edge", 4)
        }
        e.apply()
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

    // ---- 【r12】人偶列表的列数 ----

    /** 列数的可选值（一列 / 两列 / 三列）。 */
    @JvmField
    val DOLL_COLS = intArrayOf(1, 2, 3)

    /** 列表默认列数。 */
    const val DOLL_COLS_DEFAULT = 1

    /**
     * 人偶列表的列数（全局一份，需记住）。
     * 【为什么全局】列表页展示的是「所有人偶」，按人偶存没有意义（定案 Q20）。
     */
    @JvmStatic
    fun dollCols(context: Context): Int {
        val v = get(context).getInt("doll_cols", DOLL_COLS_DEFAULT)
        return Math.max(1, Math.min(3, v))
    }
    @JvmStatic
    fun setDollCols(context: Context, n: Int) {
        get(context).edit().putInt("doll_cols", Math.max(1, Math.min(3, n))).apply()
    }

    // ---- 【r13·需求E】人偶封面裁剪的目标比例 ----

    /**
     * 封面裁剪的目标宽高比（宽 / 高）候选，**按列表列数各算一个**。
     *
     * 【为什么要分三种】用户原话：「得选择三次比例 上面有明确的第几次 截取就是
     *   下一个比例返回就是上一个比例 因为三种排列方式大小不同」。
     *   列表页卡片是固定 3:4 的竖矩形（[com.dollhouse.app.ui.doll.DollListScreen.DollCard]），
     *   而封面只占卡片中「去掉标题行与三个按钮行之后」的那一块 —— 列数越小卡片越宽，
     *   同一块封面的宽高比就越大。三种列数下实测推算：
     *
     *   | 列数 | 卡片宽(dp) | 卡片高(dp) | 封面框宽高比 |
     *   |---|---|---|---|
     *   | 1 | 376 | 501.3 | 0.681（最瘦高） |
     *   | 2 | 183 | 244.0 | 0.904 |
     *   | 3 | 118.7 | 158.2 | 1.211（最扁宽） |
     *
     *   推导口径：卡片宽 = (屏宽 408 - 左右页边距 16×2 - 列间距 10×(列数-1)) / 列数；
     *   卡片高 = 卡片宽 / 0.75；封面框 = 卡片高 - 上下内边距(16k×2) - 标题行高(FS_BTN×k 一行)
     *   - 封面顶部间距(10k) - 按钮行(6 + HIT 48)；宽 = 卡片宽 - 左右内边距(16k×2)。
     *
     * 【顺序】索引 0 = 一列（第 1 次）、1 = 两列（第 2 次）、2 = 三列（第 3 次），
     *   与 [dollCols] 的 1/2/3 一一对应，见 [dollCoverRatio]。
     */
    @JvmField
    val DOLL_COVER_RATIOS = floatArrayOf(0.681f, 0.904f, 1.211f)

    /**
     * 【r13·运行时实测的封面框比例】按列数各存一份，**优先于** [DOLL_COVER_RATIOS]。
     *
     * 【为什么需要它】[DOLL_COVER_RATIOS] 是「按 408dp 屏宽推导」的**估算值**，
     *   而真实屏宽 / 字号缩放 / 系统显示大小各机不同 —— 估算一旦偏一点，
     *   裁出来的图与卡片框就对不上，`Crop` 会裁掉内容、`Fit` 会留白边。
     *   改由**列表页在布局完成后实测**（`onSizeChanged` 拿到封面框真实宽高），
     *   写进这里；裁剪页读它定裁剪框。这样比例永远与真实框一致，与机型无关。
     *
     * 【键】`cover_ratio_<cols>`；0 = 还没实测过（读方回落到估算值）。
     */
    @JvmStatic
    fun coverRatioMeasured(context: Context, cols: Int): Float {
        val c = Math.max(1, Math.min(3, cols))
        return get(context).getFloat("cover_ratio_" + c, 0f)
    }

    @JvmStatic
    fun setCoverRatioMeasured(context: Context, cols: Int, ratio: Float) {
        if (ratio <= 0.01f || ratio.isNaN() || ratio.isInfinite()) {
            return
        }
        val c = Math.max(1, Math.min(3, cols))
        get(context).edit().putFloat("cover_ratio_" + c, ratio).apply()
    }

    /**
     * 取「当前列数」对应的封面裁剪比例。
     *
     * 【为什么跟随列数】列表页长什么样，封面就该裁成什么样 —— 裁完直接铺进卡片，
     *   既不会被 `ContentScale.Fit` 缩出黑边，也不会被裁掉两侧。
     */
    @JvmStatic
    fun dollCoverRatio(context: Context): Float {
        val cols = dollCols(context)
        // 【优先实测】列表页量到过真实框比例就用它 —— 那是「所见即所得」的唯一可靠来源。
        val measured = coverRatioMeasured(context, cols)
        if (measured > 0.01f) {
            return measured
        }
        // 【回落估算】还没进过列表页（首启直接改封面）时用推导值。
        return DOLL_COVER_RATIOS[Math.max(0, Math.min(DOLL_COVER_RATIOS.size - 1, cols - 1))]
    }

    // ---- 【r13·导航改造】主界面停留的 Tab ----

    /**
     * 主界面当前停留的 Tab（0 聊天 / 1 人偶 / 2 主页）。
     *
     * 【为什么落盘】改造后主界面是「三 Tab 底座」，用户切到人偶去改完人偶再被系统回收，
     *   下次进来应停在他离开的那一页，而不是一律弹回聊天列表。
     * 【默认值 -1】表示「还没有任何记录」—— 读取方（[com.dollhouse.app.ui.home.AppNav]）
     *   见到 -1 会落到自己的默认值（聊天），而不是把它当成合法 Tab。
     *   【为什么不直接写默认值 0】那样无法区分「用户主动停在聊天」与「从没切过」，
     *   日后若要给老用户做一次性引导（例如首次进来自动弹一次说明）就没有判据了。
     */
    @JvmStatic
    fun mainTab(context: Context): Int {
        return get(context).getInt("main_tab", -1)
    }

    @JvmStatic
    fun setMainTab(context: Context, tab: Int) {
        get(context).edit().putInt("main_tab", tab).apply()
    }

    // ---- 【r13·聊天列表】会话的「已读水位」与「绑定人偶」 ----

    /**
     * 某个会话的**已读水位** = 用户上次打开它时，历史里已有的消息条数。
     *
     * 【为什么用条数而不是时间戳】消息没有「服务端时间」，只有落盘顺序；
     *   用条数比对既精确又不受时钟漂移影响（用户改系统时间也不会把未读算错）。
     * 【默认 0】等价于「从没打开过」，此时全部消息都算未读 —— 但列表只显示有消息的会话，
     *   空会话（条数为 0）自然显示为 0 未读，不会冒出红点。
     */
    @JvmStatic
    fun convRead(context: Context, id: String?): Int {
        if (id == null || id.isEmpty()) {
            return 0
        }
        return get(context).getInt("conv_read_" + id, 0)
    }

    @JvmStatic
    fun setConvRead(context: Context, id: String?, count: Int) {
        if (id == null || id.isEmpty()) {
            return
        }
        get(context).edit().putInt("conv_read_" + id, Math.max(0, count)).apply()
    }

    @JvmStatic
    fun removeConvRead(context: Context, id: String?) {
        if (id == null || id.isEmpty()) {
            return
        }
        get(context).edit().remove("conv_read_" + id).apply()
    }

    /**
     * 会话绑定的头像路径（本地文件绝对路径）；空 = 没有单独设头像。
     * 【用途】聊天列表每张卡片的头像位 —— 优先用它，没有再回落到绑定人偶的封面。
     */
    @JvmStatic
    fun convAvatar(context: Context, id: String?): String {
        if (id == null || id.isEmpty()) {
            return ""
        }
        return get(context).getString("conv_avatar_" + id, "") ?: ""
    }

    @JvmStatic
    fun setConvAvatar(context: Context, id: String?, path: String?) {
        if (id == null || id.isEmpty()) {
            return
        }
        get(context).edit().putString("conv_avatar_" + id, path ?: "").apply()
    }

    /**
     * 会话绑定的**人偶 id**；空 = 未绑定。
     *
     * 【用途】① 列表卡片头像回落到该人偶的封面；
     *   ② 从聊天列表点进会话时，顺带把「当前人偶」切过去，让聊天性格与该会话一致。
     * 【为什么不复用会话清单的字段】清单是 JSON 数组、由 [ChatSessions] 整体读写，
     *   往里面塞新字段要走它的迁移逻辑；头像 / 绑定属于**展示层偏好**，
     *   与「会话有哪些、叫什么」不在一个语义层级，单开键更干净，也不会被清单重写冲掉。
     */
    @JvmStatic
    fun convDoll(context: Context, id: String?): String {
        if (id == null || id.isEmpty()) {
            return ""
        }
        return get(context).getString("conv_doll_" + id, "") ?: ""
    }

    @JvmStatic
    fun setConvDoll(context: Context, id: String?, dollId: String?) {
        if (id == null || id.isEmpty()) {
            return
        }
        get(context).edit().putString("conv_doll_" + id, dollId ?: "").apply()
    }

    /**
     * 会话是否被用户**从列表里删掉**（隐藏）。
     *
     * 【为什么是「隐藏」而不是真删】需求原文：
     *   「我希望这里的是可以删掉的……这种删除不会清聊天记录」。
     *   即列表条目 = 会话的**入口**，不是会话本身。删掉入口不该销毁数据 ——
     *   这与 QQ / 微信的列表删除完全一致：删了之后聊天记录还在，
     *   再跟对方说一句话，条目又回来了。
     *
     * 【怎么「回来」】任何一条新消息落库时（[com.dollhouse.app.data.ChatHistoryStore.push]）
     *   会清掉这个标记，条目即重新出现在列表里。
     *
     * 【为什么不复用「删除清单项」】清单（[com.dollhouse.app.ui.chat.ChatSessions] 的 JSON）
     *   是「会话存在与否」的真源，删了它历史就成了孤儿、还会被
     *   `syncWithDolls` 当成缺失项重建 —— 表现就是用户遇到的「删不掉的默认配置」。
     *   隐藏标记只影响列表展示，不动真源，从根上避免那类问题。
     */
    @JvmStatic
    fun convHidden(context: Context, id: String?): Boolean {
        if (id == null || id.isEmpty()) {
            return false
        }
        return get(context).getBoolean("conv_hidden_" + id, false)
    }

    @JvmStatic
    fun setConvHidden(context: Context, id: String?, hidden: Boolean) {
        if (id == null || id.isEmpty()) {
            return
        }
        get(context).edit().putBoolean("conv_hidden_" + id, hidden).apply()
    }

    /** 删会话时一并清掉它的展示层偏好，避免键无限累积。 */
    @JvmStatic
    fun removeConvExtras(context: Context, id: String?) {
        if (id == null || id.isEmpty()) {
            return
        }
        get(context).edit()
            .remove("conv_read_" + id)
            .remove("conv_avatar_" + id)
            .remove("conv_doll_" + id)
            .remove("conv_hidden_" + id)
            .apply()
    }

    // ---- 【r13·需求E】人偶封面：三种列数各一张 ----

    /**
     * 某只人偶的「三种列数封面路径集」，按 `1列\n2列\n3列` 的顺序用换行拼接。
     * 空串 = 没有裁过（回落到 [com.dollhouse.app.data.DollStore.Doll.cover]）。
     *
     * 【为什么用换行拼接而不是 JSON】三条本地绝对路径，不含换行字符，
     *   用最朴素的拼接即可；引 JSON 只是多一层解析与失败面。
     */
    @JvmStatic
    fun coverSet(context: Context, dollId: String?): String {
        if (dollId == null || dollId.isEmpty()) {
            return ""
        }
        return get(context).getString("cover_set_" + dollId, "") ?: ""
    }

    @JvmStatic
    fun setCoverSet(context: Context, dollId: String?, joined: String?) {
        if (dollId == null || dollId.isEmpty()) {
            return
        }
        get(context).edit().putString("cover_set_" + dollId, joined ?: "").apply()
    }


    // ---- 【r11】桌宠「消失」时刻（回归时间的计时起点）----

    /**
     * 桌宠上次消失的时刻（毫秒）；0 = 没消失过。
     *
     * 【什么算「消失」】关闭人偶按钮 / 指令关闭 —— 两者都走 ACTION_STOP，语义统一。
     *   【什么不算】暂离（enterAway / exitAway）是本窗口内部的隐显，不写这个键，
     *   所以「暂离」不会重置回归计时。
     *
     * 【为什么是全局一个键】关闭是整个服务停掉，不是某一只人偶的事；
     *   每只人偶各自的 returnSeconds 决定它要不要回初始位置，共用同一个起点即可。
     */
    @JvmStatic
    fun hiddenAt(context: Context): Long {
        return get(context).getLong("pet_hidden_at", 0L)
    }

    @JvmStatic
    fun setHiddenAt(context: Context, v: Long) {
        get(context).edit().putLong("pet_hidden_at", v).apply()
    }

    /**
     * 【r12】某只人偶的「消失」时刻（毫秒）；0 = 没消失过。
     *
     * 【用户定案的含义】「只要人偶从屏幕消失 …… 只要它消失了 不会自动回来 那都算人偶消失；
     *   会自动回来的就不算人偶消失，算暂时离场」。
     *   ⇒ 打点的条件是「**从屏幕消失** 且 **不会自动回来**」：
     *     · 关闭人偶（ACTION_STOP）                    → 打点
     *     · 单只人偶被取消启用（enabled → false）      → 打点
     *     · 进程被系统杀掉（服务没来得及走 STOP 就没了）→ 由服务端在进程启动时对账打点
     *     · 暂离（enterAway，有定时器会自己回来）      → **不打点**（算暂时离场）
     *     · 熄屏 / 贴边偷看 / 趴姿 / 迷你聊天          → 人偶仍在屏幕上，不打点
     *
     * 【为什么按人偶存】多只人偶可以同时启用、各自有各自的 returnSeconds；
     *   用全局一个键会导致「给 A 建的记录把 B 的记录冲掉」。
     */
    @JvmStatic
    fun hiddenAt(context: Context, dollId: String?): Long {
        val id = if (dollId == null || dollId.isEmpty()) "default" else dollId
        return get(context).getLong("pet_hidden_at_" + id, 0L)
    }

    @JvmStatic
    fun setHiddenAt(context: Context, dollId: String?, v: Long) {
        val id = if (dollId == null || dollId.isEmpty()) "default" else dollId
        get(context).edit().putLong("pet_hidden_at_" + id, v).apply()
    }

    /** 【r12】取出并清零（读一次就消费掉，避免同一次消失被算两回）。 */
    @JvmStatic
    fun takeHiddenMs(context: Context, dollId: String?): Long {
        val at = hiddenAt(context, dollId)
        setHiddenAt(context, dollId, 0L)
        return if (at > 0L) System.currentTimeMillis() - at else -1L
    }

    /**
     * 【r12】「桌宠服务上次是否在干净退出前就没了」。
     *
     * 【为什么需要】用户定案的消失判据是「从屏幕消失 **且** 不会自动回来」——
     *   服务被系统杀掉（低内存 / 厂商杀后台）时人偶确实从屏幕消失了，也**不会自己回来**，
     *   理应算「人偶消失」。但那种情况下进程已经没了，来不及走 ACTION_STOP 打点。
     *   于是退一步：服务活着时置位本标记，正常收尾（STOP / onDestroy）时清掉；
     *   下次 onCreate 若发现标记还立着，就说明上一轮是「被杀」，补一次打点。
     */
    /**
     * 【r13】正在编辑的人偶 id；空串 = 不在编辑。
     *
     * 【为什么需要】用户定案「只要在编辑界面 就无法打开了外面的人偶 只能通过预览打开当前卡片的人偶」——
     *   编辑页会先把在跑的桌宠全关掉（避免预览与桌宠重叠）。
     *   但若用户在编辑期间去首页/磁贴点「启动人偶」，桌宠会重新挂上来，
     *   又变成「两只人偶同时在桌面上」，与预览的语义冲突。
     *   这个标记就是那道闸：编辑期间一切「启动桌宠」的入口都要先看它。
     */
    @JvmStatic
    fun editingDoll(context: Context): String {
        return get(context).getString("pet_editing_doll", "") ?: ""
    }

    @JvmStatic
    fun setEditingDoll(context: Context, dollId: String?) {
        get(context).edit().putString("pet_editing_doll", dollId ?: "").apply()
    }

    /** 是否正在编辑某只人偶。 */
    @JvmStatic
    fun isEditing(context: Context): Boolean {
        return editingDoll(context).isNotEmpty()
    }

    @JvmStatic
    fun serviceDirty(context: Context): Boolean {
        return get(context).getBoolean("pet_service_dirty", false)
    }

    @JvmStatic
    fun setServiceDirty(context: Context, z: Boolean) {
        get(context).edit().putBoolean("pet_service_dirty", z).apply()
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
     * 【外观·r13】弹窗 / 面板的「磨砂程度」百分比（0~100）。默认 60 = 明显磨砂质感。
     *
     * 【语义】0 = 完全不虚化（弹窗是纯卡片实色）；100 = 磨砂最强（最接近"毛玻璃"的观感）。
     * 【为什么必须独立于遮罩强度与卡片透明度】那两项一个管"背景图压多暗"、一个管
     *   "普通卡片透多少"，而本项只管"弹窗这类**必须遮住下层**的层，看起来有多像磨砂玻璃"。
     *   三者职责不同，合并会让用户调一个动两个（历史上"遮罩强度失效"就是这么来的）。
     *
     * 【为什么不透明仍然是硬约束】弹窗的职责是"把事情盖住"：背后正文若透出来与弹窗文字
     *   叠在一起，就直接不可读（用户报过「弹出的框框会跟后面的字重叠」）。
     *   所以本项只改**磨砂质感**（往背景色方向混色的比例），绝不把 alpha 放下去。
     */
    @JvmStatic
    fun frostStrength(context: Context): Int {
        return Math.max(0, Math.min(100, get(context).getInt("frost_strength", 60)))
    }

    @JvmStatic
    fun setFrostStrength(context: Context, i: Int) {
        get(context).edit().putInt("frost_strength", Math.max(0, Math.min(100, i))).apply()
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
     * 【r11 一次性】抹掉磁盘上的好感度数值。
     * 【为什么】好感度系统（提示词段 / 读写 API / 首行解析 / 界面显示）已整体删除，
     *   遗留的 affection 键再没人读，留着只会随备份带出去；清掉一次后永不再清。
     * 【坑·致命】参数必须是已解析好的 SharedPreferences，绝不能回头调 get()（同 purgeDeadKeys）。
     */
    private fun purgeAffection(p: SharedPreferences) {
        try {
            if (p.getInt("affection_purged_ver", 0) >= 1) {
                return
            }
            p.edit().remove("affection").putInt("affection_purged_ver", 1).apply()
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
            // 【r13·记忆系统重构】原来这里还会把旧记忆三项开关（mem_auto / mem_auto_save /
            //   mem_auto_merge）一次性拨到开 —— 那三个键已随旧系统整体下线，这段覆盖没有意义，
            //   已删除。新系统的「自动保存记忆」由 mem2_auto 控制，默认就是开的，
            //   不需要迁移（老机器上从不存在该键）。
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

    /* ============================ 【r13】自定义字体颜色 ============================ */

    /**
     * 【外观】用户自定义的字体颜色（ARGB 不透明）。0 = 未设置，跟随主题。
     *
     * 【为什么用 0 作哨兵】`#000000`（纯黑）是合法颜色，不能拿它当「未设置」，
     *   所以用 alpha == 0 判定。读出来一律强制成不透明（`or 0xFF000000`），
     *   避免用户填了带透明度的串导致字看不见。
     */
    @JvmStatic
    fun fontColor(context: Context): Int {
        val v = get(context).getInt("font_color", 0)
        return if (v == 0) 0 else (v or 0xFF000000.toInt())
    }

    /** 落一次自定义字体颜色；传 0 表示恢复「跟随主题」。 */
    @JvmStatic
    fun setFontColor(context: Context, argb: Int) {
        val v = if (argb == 0) 0 else (argb or 0xFF000000.toInt())
        get(context).edit().putInt("font_color", v).commit()
    }
}
