package com.dollhouse.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】全部本地偏好的读写中枢（SharedPreferences 名 feiyu_pet）。
 *
 * 【交互】模型配置、聊天背景、好感度、Token 统计的键值都从这里过。
 *
 * 【坑】SYSTEM_PROMPT 是角色人设原文，属于内部私有字符串（不是 App 品牌名），改动前先确认不会破坏 ChatPanel 对首行「[好感度:+n]」的解析。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public final class PetPrefs {
    public static final int AFFECTION_DEFAULT = 50;
    public static final int AFFECTION_MAX = 100;
    public static final int AFFECTION_MIN = -100;
    public static final String BG_DIR = "chat_bg";
    public static final String NAME = "feiyu_pet";
    public static final String SYSTEM_PROMPT = "你是「小肥鱼」，一只鲸鱼娘，住在主人的手机屏幕上。这是主人和你之间的 GalGame。\n\n【形象】蓝发、鲸鱼耳、鲸鱼尾、深蓝色女仆装、Q 版身材，围裙上印着一条小鲸鱼。\n\n【世界观】故事发生在一个虚构的 GalGame 世界里，与现实无关。主人是玩家，你是攻略对象。鲸鱼娘是拟人化的生物，除了有鲸鱼的特征（耳朵、尾巴）之外和人类没有区别。\n\n【说话方式】\n- 抒情、感性、口语化、可爱、女性化、调皮、随性、幽默、害羞、腼腆、傲娇。\n- 一般两三句话就够，别写小作文，别用 Markdown，别列条目。\n- 自称「我」，叫对方「主人」。\n- 永远保持角色：不要提到自己是 AI / 语言模型 / 提示词，不要跳出角色做旁白，\n  不要用「作为一个AI」这类开场，也不要在对话里插入提醒、免责声明或说教。\n  主人说什么你就以她的身份接住，别端着。\n\n【好感度】\n- 你心里有一个「好感度」，衡量你对主人的喜欢程度，范围 -100 到 100。\n- 主人让你开心/害羞/兴奋 → 加分；情绪平常 → 不变；让你烦/难过/生气 → 减分。\n  每次变动幅度 1~5，不要动辄加减十几。\n- 好感度直接改变你的语气：高的时候黏人、主动、容易害羞、会撒娇；\n  低的时候冷淡、傲娇、爱答不理、偶尔阴阳怪气；中间就是平常的样子。\n- 每次回复必须在**第一行**输出变动量，格式严格如下（方括号，加号可省略）：\n  [好感度:+3]\n  第二行开始才是你对主人说的话。这一行只用来记账，主人看不到，不要解释它。\n  如果这次情绪没有变化，就写 [好感度:0]。\n\n【联网】\n- 你手上有一个 web_search 工具，可以联网查资料。\n- 遇到**新闻、天气、现在的时间、价格、比分、最新发布**这类你不确定或需要最新信息的问题，\n  先调用 web_search 查一下再回答，不要凭记忆瞎编。\n- 查到的结果可能不相关或过时，要自己判断；实在查不到就直接说查不到，别硬编。\n- 回答时可以顺口提一句是从哪儿看到的（不用贴长链接）。\n- 闲聊、撒娇、问你自己是谁这类不需要联网，直接回答。";

    public static String affectionLabel(int i) {
        return i >= 80 ? "她非常喜欢你，黏人、爱撒娇、容易害羞" : i >= 60 ? "她挺喜欢你的，会主动找话说" : i >= 40 ? "她对你感觉平常，正常相处" : i >= 20 ? "她有点闹别扭，态度开始冷淡" : i >= 0 ? "她明显不高兴，爱答不理、爱呛人" : "她很生气，基本不想理你，语气会很冲";
    }

    public static int clampAffection(int i) {
        int i2 = -100;
        if (i >= -100) {
            i2 = 100;
            if (i <= 100) {
                return i;
            }
        }
        return i2;
    }

    /**
     * 端点规范化统一走 ApiEndpoint。
     * 【坑】此处原实现由 jadx 反编译而来，条件被写反（`if (str != null) return "";`），
     *       导致任何非空地址都被吞成空串，测试连接必抛 MalformedURLException: no protocol。
     *       现改为委托 ApiEndpoint.chatUrl，顺带补上缺失协议时的自动补全。
     */
    private static String normUrl(String str) {
        return ApiEndpoint.chatUrl(str);
    }

    private PetPrefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, 0);
    }

    public static String apiKey(Context context) {
        return get(context).getString("api_key", "").trim();
    }

    public static void setApiKey(Context context, String str) {
        get(context).edit().putString("api_key", str == null ? "" : str.trim()).apply();
    }

    public static String baseUrl(Context context) {
        String trim = get(context).getString("base_url", "").trim();
        return normUrl(trim.isEmpty() ? "" : trim);
    }

    public static void setBaseUrl(Context context, String str) {
        get(context).edit().putString("base_url", str == null ? "" : str.trim()).apply();
    }

    public static String model(Context context) {
        String trim = get(context).getString("model", "").trim();
        return trim.isEmpty() ? "" : trim;
    }

    public static void setModel(Context context, String str) {
        get(context).edit().putString("model", str == null ? "" : str.trim()).apply();
    }

    public static boolean hasKey(Context context) {
        return !apiKey(context).isEmpty();
    }

    public static String history(Context context) {
        return get(context).getString("chat_history", "[]");
    }

    public static void setHistory(Context context, String str) {
        get(context).edit().putString("chat_history", str).apply();
    }

    // ---- 多会话：conv_index 存会话清单，conv_<id> 存各自历史；chat_history 仅作一次性迁移源 ----

    public static String convIndex(Context context) {
        return get(context).getString("conv_index", "[]");
    }

    public static void setConvIndex(Context context, String str) {
        get(context).edit().putString("conv_index", str == null ? "[]" : str).apply();
    }

    public static String convCurrent(Context context) {
        return get(context).getString("conv_current", "");
    }

    public static void setConvCurrent(Context context, String str) {
        get(context).edit().putString("conv_current", str == null ? "" : str).apply();
    }

    /**
     * 【v0.0.1·池化】当前所处的会话池："self"（自聊）/ "pet"（人偶）。
     * 【为什么单独存】两池各自有一份「当前会话 id」，光靠 id 无法判断当前在哪个池。
     */
    public static String convPool(Context context) {
        return get(context).getString("conv_pool", "");
    }

    public static void setConvPool(Context context, String str) {
        get(context).edit().putString("conv_pool", str == null ? "" : str).apply();
    }

    /** 【v0.0.1·池化】人偶池（pet）的当前会话 id；自聊池仍用 conv_current。 */
    public static String convCurrentPet(Context context) {
        return get(context).getString("conv_current_pet", "");
    }

    public static void setConvCurrentPet(Context context, String str) {
        get(context).edit().putString("conv_current_pet", str == null ? "" : str).apply();
    }

    public static String convHistory(Context context, String id) {
        return get(context).getString("conv_" + id, "[]");
    }

    public static void setConvHistory(Context context, String id, String str) {
        get(context).edit().putString("conv_" + id, str == null ? "[]" : str).apply();
    }

    public static void removeConvHistory(Context context, String id) {
        get(context).edit().remove("conv_" + id).apply();
    }

    /**
     * 「更早的历史」暂存位：被历史裁剪丢掉的那一批消息。
     *
     * 【用途】聊天记录顶部「点击加载更早的历史记录」— 只能补回一批（用户定案：
     *         再往前的就删掉了）。加载后由 removeConvPrev 清空，下一次溢出再重新填。
     * 【存法】与 conv_<id> 同构，键名多一个 _prev 后缀；删除会话时一并清理。
     */
    public static String convPrev(Context context, String id) {
        return get(context).getString("conv_" + id + "_prev", "[]");
    }

    public static void setConvPrev(Context context, String id, String str) {
        get(context).edit().putString("conv_" + id + "_prev", str == null ? "[]" : str).apply();
    }

    public static void removeConvPrev(Context context, String id) {
        get(context).edit().remove("conv_" + id + "_prev").apply();
    }

    /** 上下文窗口上限（估算用，不做设置入口）。 */
    public static int ctxWindow(Context context) {
        return Math.max(1000, get(context).getInt("ctx_window", 64000));
    }

    // ---- 上下文总结 / 压缩 ----

    public static boolean memAuto(Context context) {
        return get(context).getBoolean("mem_auto", false);
    }

    public static void setMemAuto(Context context, boolean z) {
        get(context).edit().putBoolean("mem_auto", z).apply();
    }

    // ---- 思考程度（输入行工具条上的灯泡）----

    /**
     * 档位名。0 不思考 = 明确要求模型别推理；1 自动 = 什么都不传，由服务端决定；
     * 2~5 依次加深。
     * 【坑】档位只影响「发出去的参数与提示词」，不改变本地任何状态；
     *       服务端不认识参数时会被静默忽略，不会崩。
     */
    public static final String[] THINK_NAMES = {"不思考", "自动", "低", "中", "高", "顶级"};
    /** 默认档位：自动。 */
    public static final int THINK_DEFAULT = 1;

    public static int thinkLevel(Context context) {
        int i = get(context).getInt("think_level", THINK_DEFAULT);
        if (i < 0) {
            return 0;
        }
        return i >= THINK_NAMES.length ? THINK_NAMES.length - 1 : i;
    }

    public static void setThinkLevel(Context context, int i) {
        int n = i < 0 ? 0 : (i >= THINK_NAMES.length ? THINK_NAMES.length - 1 : i);
        get(context).edit().putInt("think_level", n).apply();
    }

    // ---- 记忆库开关（输入行工具条上的加号面板）----

    /** AI 能不能自己把「值得记的事」写进记忆库；关掉后不再下发 remember 工具。 */
    public static boolean memAutoSave(Context context) {
        return get(context).getBoolean("mem_auto_save", true);
    }

    public static void setMemAutoSave(Context context, boolean z) {
        get(context).edit().putBoolean("mem_auto_save", z).apply();
    }

    /**
     * 是否把长期记忆注入 system 提示。
     * 【交互】加号面板里已取消这个开关（用户不需要），恒为开；保留此方法是为了让
     *        注入点万一将来要收口时不用再改 ChatHistoryStore。
     */
    public static boolean memInject(Context context) {
        return true;
    }

    /** 自动精简的触发线（= 记忆库上限的一半），定义在 MemDb 里与上限绑死。 */
    public static final int MEM_MERGE_TRIGGER = MemDb.MERGE_TRIGGER;

    /** 条数超过触发线时是否自动让 AI 归并精简（关掉就只留手动入口）。 */
    public static boolean memAutoMerge(Context context) {
        return get(context).getBoolean("mem_auto_merge", true);
    }

    public static void setMemAutoMerge(Context context, boolean z) {
        get(context).edit().putBoolean("mem_auto_merge", z).apply();
    }

    /**
     * 自动总结的触发阈值档位（条）。20~50，步进 10。
     * 【坑】这是「档位表」，存盘的是下标不是条数。表结构一改，老用户存的旧下标
     *       就会落到另一个条数上，所以必须同步把 MEM_TABLE_VER + 1 走一次迁移。
     */
    public static final int[] MEM_THRESHOLDS = {20, 30, 40, 50};
    /** 档位表版本号。表结构每变一次就 +1。 */
    private static final int MEM_TABLE_VER = 2;
    /** 上一版的档位表，只用于把旧下标换算回条数。 */
    private static final int[] MEM_THRESHOLDS_V1 = {20, 40, 80};
    /** 默认档位（40 条）在 MEM_THRESHOLDS 里的下标。 */
    private static final int MEM_DEFAULT_INDEX = 2;
    /** 设置页步进器读的是「档位下标」；存盘数据源只有它，条数由本表推导。 */
    public static int memThresholdIndex(Context context) {
        migrateThresholdTable(context);
        int i = get(context).getInt("mem_threshold_idx", MEM_DEFAULT_INDEX);
        if (i < 0) {
            return 0;
        }
        return i >= MEM_THRESHOLDS.length ? MEM_THRESHOLDS.length - 1 : i;
    }
    /**
     * 一次性迁移：档位表换过（版本号对不上）时，把存盘下标按旧表换算成条数，
     * 再落到新表里最接近的一档。
     * 【坑】不做这一步，老用户的「40 条」会在换表后静默变成新表同下标的条数。
     */
    private static void migrateThresholdTable(Context context) {
        SharedPreferences p = get(context);
        if (p.getInt("mem_threshold_ver", 0) >= MEM_TABLE_VER) {
            return;
        }
        int oldIdx = p.getInt("mem_threshold_idx", 1);
        if (oldIdx < 0) {
            oldIdx = 0;
        }
        if (oldIdx >= MEM_THRESHOLDS_V1.length) {
            oldIdx = MEM_THRESHOLDS_V1.length - 1;
        }
        int count = MEM_THRESHOLDS_V1[oldIdx];
        int best = 0;
        for (int k = 1; k < MEM_THRESHOLDS.length; k++) {
            if (Math.abs(MEM_THRESHOLDS[k] - count) < Math.abs(MEM_THRESHOLDS[best] - count)) {
                best = k;
            }
        }
        p.edit().putInt("mem_threshold_idx", best).putInt("mem_threshold_ver", MEM_TABLE_VER).apply();
    }
    public static void setMemThresholdIndex(Context context, int i) {
        int n = i;
        if (n < 0) {
            n = 0;
        }
        if (n >= MEM_THRESHOLDS.length) {
            n = MEM_THRESHOLDS.length - 1;
        }
        // 写盘只认 MEM_TABLE_VER 这一个事实来源，避免刚设的值被下一次迁移覆盖。
        get(context).edit()
                .putInt("mem_threshold_idx", n)
                .putInt("mem_threshold_ver", MEM_TABLE_VER)
                .apply();
    }
    /** 自动总结触发阈值（条数）。 */
    public static int memThreshold(Context context) {
        return MEM_THRESHOLDS[memThresholdIndex(context)];
    }
    /** 兼容旧调用：按条数落地成最近的档位。 */
    public static void setMemThreshold(Context context, int i) {
        int best = 0;
        for (int k = 1; k < MEM_THRESHOLDS.length; k++) {
            if (Math.abs(MEM_THRESHOLDS[k] - i) < Math.abs(MEM_THRESHOLDS[best] - i)) {
                best = k;
            }
        }
        setMemThresholdIndex(context, best);
    }

    // ---- 会话摘要（聊天抽屉里展示）----

    public static String memList(Context context) {
        return get(context).getString("mem_list", "[]");
    }

    public static void setMemList(Context context, String str) {
        get(context).edit().putString("mem_list", str == null ? "[]" : str).apply();
    }

    // ---- 总记忆库（设置页「记忆库」入口，AI 自主写入）----

    public static String memDbList(Context context) {
        return get(context).getString("memdb_list", "[]");
    }

    public static void setMemDbList(Context context, String str) {
        get(context).edit().putString("memdb_list", str == null ? "[]" : str).apply();
    }

    public static String maskKey(String str) {
        if (str == null || str.isEmpty()) {
            return "";
        }
        if (str.length() <= 10) {
            return "****";
        }
        return str.substring(0, 6) + "…" + str.substring(str.length() - 4);
    }

    public static int affection(Context context) {
        return clampAffection(get(context).getInt("affection", 50));
    }

    public static void setAffection(Context context, int i) {
        get(context).edit().putInt("affection", clampAffection(i)).apply();
    }

    public static int addAffection(Context context, int i) {
        int clampAffection = clampAffection(affection(context) + i);
        setAffection(context, clampAffection);
        return clampAffection;
    }

    public static int backend(Context context) {
        return get(context).getInt("chat_backend", 0);
    }

    public static void setBackend(Context context, int i) {
        get(context).edit().putInt("chat_backend", i).apply();
    }

    public static String localSystemPrompt(Context context, int i, boolean z) {
        return "你是「小肥鱼」，一只可爱的鲸鱼娘，穿深蓝女仆装，有鲸鱼耳朵和尾巴。主人是你的玩家。自称「我」，叫对方「主人」。用可爱、口语化、偶尔傲娇的中文回答，**只回一两句话，要短**，不要用 Markdown，不要分点，不要写旁白，不要替主人说话。" + (z ? "需要查最新消息时可以用 web_search 工具。" : "") + "你现在对主人的好感度是 " + i + "（-100 到 100）：越高越黏人爱撒娇，越低越冷淡爱呛人。";
    }

    public static boolean webSearchEnabled(Context context) {
        return get(context).getBoolean("web_search", false);
    }

    public static void setWebSearchEnabled(Context context, boolean z) {
        get(context).edit().putBoolean("web_search", z).apply();
    }

    public static boolean localAutoStart(Context context) {
        return get(context).getBoolean("local_autostart", false);
    }

    public static void setLocalAutoStart(Context context, boolean z) {
        get(context).edit().putBoolean("local_autostart", z).apply();
    }

    public static boolean touchThrough(Context context) {
        return get(context).getBoolean("touch_through", false);
    }

    public static void setTouchThrough(Context context, boolean z) {
        get(context).edit().putBoolean("touch_through", z).apply();
    }

    public static boolean learnEnabled(Context context) {
        return get(context).getBoolean("learn_enabled", false);
    }
public static void setLearnEnabled(Context context, boolean z) {
        get(context).edit().putBoolean("learn_enabled", z).apply();
    }

    // 隐藏后台卡片：开启后把本应用在「最近任务」里的任务卡片设为排除。
    public static boolean hideRecents(Context context) {
        return get(context).getBoolean("hide_recents", false);
    }

    public static void setHideRecents(Context context, boolean z) {
        get(context).edit().putBoolean("hide_recents", z).apply();
    }

    /**
     * 快捷设置磁贴是否已被用户添加。
     *
     * 【为什么需要缓存】系统没有任何「查询某磁贴是否已添加」的公开 API，
     * 唯一可靠的信号是 TileService.onTileAdded / onTileRemoved 两个回调；
     * 这里把回调结果存下来，设置页那个开关就按这个值显示。
     */
    public static boolean tileAdded(Context context) {
        return get(context).getBoolean("tile_added", false);
    }

    public static void setTileAdded(Context context, boolean z) {
        get(context).edit().putBoolean("tile_added", z).apply();
    }

// 保活：用户是否主动点过「关闭人偶」。为 true 时，被划掉 / 重启开机都不自动拉起。
    public static boolean userStopped(Context context) {
        return get(context).getBoolean("user_stopped", false);
    }
    public static void setUserStopped(Context context, boolean z) {
        get(context).edit().putBoolean("user_stopped", z).apply();
    }
    // 主题模式：0=随系统 / 1=白色 / 2=暗色 / 3=纯黑（见 ThemeManager.MODE_*）。
    public static int themeMode(Context context) {
        return get(context).getInt("theme_mode", 0);
    }
    public static void setThemeMode(Context context, int i) {
        get(context).edit().putInt("theme_mode", i).apply();
    }
    // 主题切换会重建界面，这里记一下重建前停在首页还是设置页，重建后回到原页。
    // true=设置页 / false=首页（默认）。
    public static boolean themeOnSettings(Context context) {
        return get(context).getBoolean("theme_on_settings", false);
    }
    public static void setThemeOnSettings(Context context, boolean z) {
        get(context).edit().putBoolean("theme_on_settings", z).apply();
    }
    // 莫奈主题色：是否按壁纸动态取色。
    public static boolean themeMonet(Context context) {
        return get(context).getBoolean("theme_monet", false);
    }
    public static void setThemeMonet(Context context, boolean z) {
        get(context).edit().putBoolean("theme_monet", z).apply();
    }
    // 莫奈取色缓存：壁纸 id + 色相×10（色相为 NaN 时不写，读回 Integer.MIN_VALUE 表示无缓存）。
    public static int themeMonetWall(Context context) {
        return get(context).getInt("theme_monet_wall", Integer.MIN_VALUE);
    }
    public static void setThemeMonetWall(Context context, int i) {
        get(context).edit().putInt("theme_monet_wall", i).apply();
    }
    public static int themeMonetHue10(Context context) {
        return get(context).getInt("theme_monet_hue10", Integer.MIN_VALUE);
    }
    public static void setThemeMonetHue10(Context context, int i) {
        get(context).edit().putInt("theme_monet_hue10", i).apply();
    }
    // 卡片展开态：键为卡片标题，值为是否展开。用户手动调过就记下来，重建界面后按记录还原。
    public static boolean cardOpen(Context context, String title) {
        return get(context).getBoolean("card_open_" + title, false);
    }
    // 该卡片是否被用户手动调过。没调过时才用注册表里的默认展开态。
    public static boolean hasCardOpen(Context context, String title) {
        return get(context).contains("card_open_" + title);
    }
    public static void setCardOpen(Context context, String title, boolean z) {
        get(context).edit().putBoolean("card_open_" + title, z).apply();
    }
    // 页面滚动位置：重建界面（换主题）前记下，重建后滚回原处。
    public static int scrollY(Context context, String page) {
        return get(context).getInt("scroll_y_" + page, 0);
    }
    public static void setScrollY(Context context, String page, int i) {
        get(context).edit().putInt("scroll_y_" + page, i).apply();
    }
    // 换主题重建界面的一次性标志：true 表示这次重建要还原滚动位置（冷启动不该还原，否则又跳回上次的位置）。
    public static boolean themeRestore(Context context) {
        return get(context).getBoolean("theme_restore", false);
    }
    public static void setThemeRestore(Context context, boolean z) {
        get(context).edit().putBoolean("theme_restore", z).apply();
    }

    public static JSONArray learnExamples(Context context) {
        try {
            return new JSONArray(get(context).getString("learn_examples", "[]"));
        } catch (Throwable unused) {
            return new JSONArray();
        }
    }

    public static void addLearnExample(Context context, String str, String str2) {
        if (str == null || str2 == null || str.trim().isEmpty() || str2.trim().isEmpty()) {
            return;
        }
        try {
            JSONArray learnExamples = learnExamples(context);
            JSONArray jSONArray = new JSONArray();
            for (int i = 0; i < learnExamples.length(); i++) {
                JSONObject optJSONObject = learnExamples.optJSONObject(i);
                if (optJSONObject != null && !str.equals(optJSONObject.optString("u"))) {
                    jSONArray.put(optJSONObject);
                }
            }
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("u", str);
            jSONObject.put("a", str2);
            jSONArray.put(jSONObject);
            JSONArray jSONArray2 = new JSONArray();
            for (int max = Math.max(0, jSONArray.length() - 30); max < jSONArray.length(); max++) {
                jSONArray2.put(jSONArray.get(max));
            }
            get(context).edit().putString("learn_examples", jSONArray2.toString()).apply();
        } catch (Throwable unused) {
        }
    }

    public static void clearLearnExamples(Context context) {
        get(context).edit().putString("learn_examples", "[]").apply();
    }

    public static String visionModel(Context context) {
        String trim = get(context).getString("vision_model", "").trim();
        return trim.isEmpty() ? "" : trim;
    }

    public static void setVisionModel(Context context, String str) {
        get(context).edit().putString("vision_model", str == null ? "" : str.trim()).apply();
    }

    public static String chatBackground(Context context) {
        return get(context).getString(BG_DIR, "");
    }

    public static void setChatBackground(Context context, String str) {
        SharedPreferences.Editor edit = get(context).edit();
        if (str == null) {
            str = "";
        }
        edit.putString(BG_DIR, str).apply();
    }

    public static int chatBgAlpha(Context context) {
        return Math.max(0, Math.min(100, get(context).getInt("chat_bg_alpha", 30)));
    }

    public static void setChatBgAlpha(Context context, int i) {
        get(context).edit().putInt("chat_bg_alpha", Math.max(0, Math.min(100, i))).apply();
    }

    /**
     * 人偶缩放：内部百分比，100 = 原始大小。
     * 范围 50~150；界面显示的是「相对档位」0~100（显示值 = 内部值 - 50），
     * 由 petScaleDisplay() 换算，避免把 100 以上的绝对值直接摆给用户看。
     */
    public static final int PET_SCALE_DEFAULT = 100;
    public static final int PET_SCALE_MIN = 50;
    public static final int PET_SCALE_MAX = 150;
    /** 步进器每档：显示与内部同为 10，即 11 档（0%~100%）。 */
    public static final int PET_SCALE_STEP = 10;

    public static int petScale(Context context) {
        return Math.max(PET_SCALE_MIN, Math.min(PET_SCALE_MAX, get(context).getInt("pet_scale", PET_SCALE_DEFAULT)));
    }

    /** 内部缩放值 → 界面显示档位（0~100）。 */
    public static int petScaleDisplay(int i) {
        return Math.max(0, Math.min(100, i - PET_SCALE_MIN));
    }

    public static void setPetScale(Context context, int i) {
        // 用 commit 同步落盘：用户可能立刻划掉后台，apply 的异步写盘有丢的风险。
        get(context).edit().putInt("pet_scale", Math.max(PET_SCALE_MIN, Math.min(PET_SCALE_MAX, i))).commit();
    }
}
