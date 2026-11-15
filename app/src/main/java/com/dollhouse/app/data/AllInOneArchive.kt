package com.dollhouse.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.dollhouse.app.PetService
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.core.Logs
import com.dollhouse.app.pet.AnimBaker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 【职责】**全量存档**（All-in-One）的导出与恢复：把「换一台手机后真正想带走的东西」
 *   一次性装进 [BackupEnvelope] 信封，并在目标机按「只新增 / 只补齐」的口径写回。
 *
 * ## 为什么需要它（而不是继续用 BackupService 那 210 行）
 * 改造前的 [BackupService] 只导供应商 / 模型 / 人偶记录 / 记忆 / 三个开关，
 * 并且恢复时**明确不新建人偶**（那时 DollStore 没有可用的插入接口）。
 * 用户换机后实际拿到的是：一堆没有人设、没有聊天、没有封面的人偶壳 ——
 * 「备份恢复完了，但她不认识我了」，这是本模块存在的唯一理由。
 *
 * ## 本类与其它模块的分工
 * | 谁 | 管什么 |
 * |---|---|
 * | [BackupEnvelope] | 信封格式、版本校验、概览文案（纯字符串层，不碰存储） |
 * | **本类** | 逐段采集 / 写回（唯一碰存储的地方） |
 * | [BackupService] | 老调用方的兼容外壳（转发到本类） |
 * | [BackupFile] | 落盘与分享（不在本类改动范围内） |
 *
 * ## 三条不可动摇的口径
 * 1. **只装已知键**：人设段的键名是一份**显式清单**（见 [PERSONA_BASES]），
 *    绝不整份 SharedPreferences 导出。那份偏好里混着聊天记录、供应商密钥密文等
 *    大量与本段无关的数据，整份导出既超量又容易把不该出去的字段带出去。
 * 2. **不装 API Key**：`providers[].apiKey` 写入前一律 `remove`。它是 KeyVault 密文，
 *    与本机 Keystore 绑定，换机解不开；写进包里只会让用户以为「密钥备好了」。
 * 3. **恢复不删除**：所有写回都是「目标机没有才写」。用户现有的聊天、人设、图片
 *    一个都不会被备份里的旧版本顶掉；图片文件是唯一例外（见 [restoreImages]），
 *    且同样遵循「目标已存在就跳过」。
 *
 * ## 线程
 * 读写偏好与文件，**不要在 UI 线程直接调**（聊天记录 + 图片可达几十 MiB）。
 * 现有调用方（设置页）已经在 `Thread { }` 里调，本类不额外开线程。
 */
object AllInOneArchive {

    private const val TAG = "DollhouseArchive"

    /**
     * 【v0.2.8·路径口径分叉】这几个字段在**磁盘上存的是相对 filesDir 的路径**，不是绝对路径。
     *
     * 依据是两条真实链路，不是约定俗成：
     *   · 写：`DollProfile.setChatBgPath` 吃的是 `ImageStore.saveScaledFile` 的返回值，
     *     那是 `subDir + "/" + name` 形式的**相对路径**；
     *     `MyProfile.commitAvatar` 同理走 ImageStore 的相对口径。
     *   · 读：`GlobalBackground.resolvePath` → `ImageStore.exists` → `fileFor` → `isInsideBase`，
     *     而 `isInsideBase` 对绝对路径**直接返回 false**（见 ImageStore 第 180 行）。
     *
     * 因此恢复时若把绝对路径写进这两个键，图虽然落在盘上，却永远读不出来 ——
     * 用户看到的是"背景没恢复""头像变回默认"。这个集合就是用来区分口径的。
     *
     * 反例（故意**不**收进来）：`cover` / `sprite` 由 `DollStore.encode` 落盘并返回**绝对路径**，
     * 消费方直接 `File(path)` 读取。把它们改成相对路径会反过来让封面显示不出来。
     */
    private val REL_FIELDS = setOf("chatbg", "avatar")

    /**
     * `forceMemSwitchesOn()` 在每台机器首次启动时无条件把 `web_search` 写成 true
     * （见 PetPrefs 第 1532 行附近的迁移链），所以恢复时无法用「键是否存在」判断用户是否设过。
     *
     * 【为什么用快照而不是和常量 true 比】true 本身也是用户的合法值。
     * 现在联网搜索没有 UI 入口，用户改不到它，和常量比也能得到正确结果；
     * 但一旦将来恢复该入口，用户设成 true 就会被误判成"没动过"而覆盖。
     * 迁移处已另存 `web_search_migrated_val`，这里与它比对，从源头避免这个坑。
     */
    private fun webSearchUntouched(ctx: Context): Boolean {
        return try {
            val p = PetPrefs.get(ctx)
            if (!p.contains("web_search_migrated_val")) {
                // 老机器上迁移发生在加快照之前：无法区分，保守不覆盖。
                false
            } else {
                p.getBoolean("web_search", false) == p.getBoolean("web_search_migrated_val", false)
            }
        } catch (t: Throwable) {
            false
        }
    }

    /* ============================ 段名 ============================ */

    /** 供应商（不含 apiKey）。 */
    const val SEC_PROVIDERS = "providers"
    /** 模型。 */
    const val SEC_MODELS = "models"
    /** 人偶记录（id / 名字 / 封面 / 形象 / 启用 / 分组）。 */
    const val SEC_DOLLS = "dolls"
    /** 人设与角色设定（[DollProfile] 的已知键，按人偶分组）。 */
    const val SEC_PERSONA = "persona"
    /** 聊天记录（会话清单 + 每条历史 + 暂存的更早历史 + 展示层偏好）。 */
    const val SEC_CHAT = "chat"
    /** 用户自己（昵称 / 签名 / 头像）。 */
    const val SEC_MYPROFILE = "myprofile"
    /** 记忆条目（沿用 v1 的 `{ "<dollId>": [...] }` 结构）。 */
    const val SEC_MEMORIES = "memories"
    /** 记忆连接（图谱边）。 */
    const val SEC_MEMLINKS = "memlinks"
    /** 记忆空间资料（Markdown 用户画像）。 */
    const val SEC_MEMPROFILE = "memprofile"
    /** 动作库（含用户自建动作的元数据，帧图本体在 [SEC_IMAGES]）。 */
    const val SEC_ANIM = "anim"
    /** 图片资产（base64 + 相对路径 + 归属字段映射）。 */
    const val SEC_IMAGES = "images"
    /** 全局开关与外观。 */
    const val SEC_SETTINGS = "settings"

    /**
     * 单张图片入包上限。
     *
     * 【为什么要有】人偶帧图是 1024 长边的 PNG，单张常在 1~3 MiB；
     *   但用户也可能把一张 8000px 的原图手工塞进 `doll/` 目录。
     *   不设上限时一张异常大图就能吃掉整个预算，把后面的封面全挤掉。
     */
    private const val MAX_FILE_BYTES = 12L * 1024 * 1024

    /**
     * 人设段的键基名清单。
     *
     * 【为什么是硬编码的清单，而不是反射 DollProfile 的私有常量】
     *   那些 `K_XXX` 全是 `private`，本类读不到；改成 public 等于给数据层加一批
     *   对外的长期承诺，且会让「键名只有一个来源」这条约束失效。
     *   这里显式抄一份，并在 [DollProfile.resetToFactory] 里也有一份同源清单 ——
     *   两处必须同步：**DollProfile 新增人设键时，这里要跟着加**，
     *   否则新加的那一项会静默不进存档（用户表现为「恢复了但设置没带过去」）。
     */
    private val PERSONA_BASES = arrayOf(
        // 行为
        "away_seconds", "pet_scale", "return_seconds",
        // 位置
        "home_rx", "home_ry", "frame_ox", "frame_oy",
        // 内容
        "talk_lines", "persona", "persona_spec",
        // 外观（人偶层）
        "chatbg", "chatbg_on", "chatbg_alpha", "chatbg_blur",
        "bubble_color", "bubble_frost",
        // 尺寸规格（`size,ox,oy`）
        "size_spec",
        // 面朝方向
        "flip",
        // 五个手势各一条键（名字与 DollProfile 注释里的一致）
        "gesture_single", "gesture_double", "gesture_triple",
        "gesture_long", "gesture_hold"
    )

    /** 屏幕范围是动态键：`bounds_<l|t|r|b>_<port|land>`，横竖屏各一套。 */
    private val BOUND_SIDES = arrayOf("l", "t", "r", "b")
    private val BOUND_ORIENTS = arrayOf(DollProfile.ORIENT_PORT, DollProfile.ORIENT_LAND)

    /**
     * 【P0 安全·键注入】危险键前缀 / 全名黑名单（纵深防御，第二道闸门）。
     *
     * ## 为什么需要这一层（即使已经有白名单）
     * 白名单是**根治**，但它有一个只有未来才会暴露的失效模式：将来有人往
     * [PERSONA_BASES] 里加一项、而那一项恰好与敏感键同名前缀（例如有人图省事加
     * `"provider"`、`"model"`、`"conv_"`），白名单就会当场把注入通道重新打开。
     * 黑名单独立于白名单判定，能在那一刻拦住 —— 两道闸门的失效条件不重叠。
     *
     * ## 清单依据（每个前缀对应一个真实的越权后果）
     * · `pv_`        → [ProviderStore.KEY_PROVIDERS] `pv_list` / `KEY_CURRENT` `pv_cur` /
     *                  `KEY_MODELS` `pv_models`：**改写供应商列表与当前模型 → baseUrl 指向
     *                  攻击者服务器 → 之后所有聊天明文 POST 出去**。这是本漏洞的实际攻击目标。
     * · `api_key`    → 密钥位置。换机不可用的密文塞进来会让用户以为「密钥备好了」，实际全部 401。
     * · `base_url`   → [PetPrefs] 旧版出网地址；`activeBaseUrl` 为空时**回退读它**，
     *                  与 `pv_*` 等效的劫持入口（老用户没有 `pv_list` 也照样被打中）。
     * · `provider` / `model` → 供应商与模型的其它持久化键；不属于人设，一律不写。
     * · `conv_`      → 聊天记录（`conv_<id>` / `conv_read_<id>` / `conv_doll_<id>` …）。
     *                  人设段写聊天 = 伪造用户历史 / 覆盖会话指针，且与 task-14 的
     *                  restoreChat 加固撞车，必须在此彻底关闭。
     * · `my_avatar` / `my_name` / `my_bio` → 「我」的资料（[MyProfile]）。只应由
     *                  `restoreMyProfile` 用**具名 API** 合并，不经任意键通道。
     * · `mem2_`      → 记忆条目与画像（`mem2_list_` / `mem2_profile_` / `mem2_conn_`）。
     * · `doll_anim_` → 动作库整段 JSON；由 [restoreAnim] 专管（含帧路径重写），
     *                  不能绕过它直接写。
     * · `theme` / `bg_` / `scrim` / `web_search` / `think_level` / `groups` /
     *                  `affection` → 全局外观与开关，由 `restoreSettings` 的具名 setter 管。
     * · `token` / `secret` / `key_` / `chat_history` / `history` → 任何形态的凭据与全量历史。
     */
    private val DANGEROUS_KEY_PREFIXES = arrayOf(
        "pv_", "api_key", "base_url", "provider", "model",
        "conv_", "my_avatar", "my_name", "my_bio",
        "mem2_", "doll_anim_",
        "theme", "bg_", "scrim", "web_search", "think_level", "groups", "affection",
        "token", "secret", "key_", "chat_history", "history"
    )

    /** 危险键的**精确**全名（前缀表之外的补充，用于无共同前缀的旧键）。 */
    private val DANGEROUS_KEY_EXACT = arrayOf(
        "base_url", "active_base_url", "api_key", "apiKey",
        "pv_list", "pv_cur", "pv_models"
    )

    /**
     * 键是否命中危险清单（黑名单 / 第二道闸门）。
     *
     * 【为什么先判黑名单再判白名单】两道闸门的失效条件不同，顺序上让「无论白名单怎么变
     *   都不许写」的判定先执行，语义更接近意图：**敏感键永不放行**。
     */
    private fun isDangerousKey(key: String): Boolean {
        if (key.isEmpty()) {
            return true
        }
        for (k in DANGEROUS_KEY_EXACT) {
            if (key == k) {
                return true
            }
        }
        for (p in DANGEROUS_KEY_PREFIXES) {
            if (key.startsWith(p)) {
                return true
            }
        }
        return false
    }

    /**
     * 【P0 安全·唯一展开点】某只人偶**允许导出 / 允许导入**的完整键清单。
     *
     * ## 为什么抽成一个函数（这是本次修复的根治点）
     * 修复前：导出侧按 [PERSONA_BASES] 生成键（严格），导入侧直接用备份文件里的 `k`
     * 写盘（宽松）。**导出严、导入松的非对称**就是漏洞本身 —— 备份文件是用户可以从
     * 网上下载的**外部输入**，不能按「自己的导出格式」无条件信任。
     * 现在两侧都走这一个函数：键集合只有一份，将来给 [PERSONA_BASES] 加项时，
     * 导出与导入会**自动同步**，不存在「一边加了另一边忘了」而重新打开缺口的可能。
     *
     * ## 动态键 [DollProfile.key] `bounds` 的处理
     * `bounds` 本身不是键，真键是 `bounds_<l|t|r|b>_<port|land>` 共 8 个/人偶。
     * 这里按 [BOUND_SIDES] × [BOUND_ORIENTS]**穷举展开**，而不是用 `startsWith("bounds_")`
     * 之类的模糊前缀 —— 模糊匹配会把 `bounds_l_port_evil` 这类不存在的键也放进来，
     * 所以必须枚举成精确集合。
     *
     * 键形态仍由 [DollProfile.key] 决定（默认人偶裸键、其余带 `_<dollId>` 后缀），
     * 与读取端同一规则，不在这里重新实现拼接约定。
     */
    private fun personaKeysOf(dollId: String): List<String> {
        val keys = ArrayList<String>(
            PERSONA_BASES.size + BOUND_SIDES.size * BOUND_ORIENTS.size
        )
        for (b in PERSONA_BASES) {
            keys.add(DollProfile.key(dollId, b))
        }
        for (o in BOUND_ORIENTS) {
            for (s in BOUND_SIDES) {
                keys.add(DollProfile.key(dollId, "bounds_" + s + "_" + o))
            }
        }
        return keys
    }

    /**
     * 某个**外来**键（来自备份文件）是否允许写到 [`dollId`] 名下。
     *
     * 判据是**精确成员判定**：键必须是 [personaKeysOf] 算出来的那一批之一。
     * 不做「后缀看起来像人偶 id」之类的猜测 —— 猜测型的反向解析很容易被
     * `dollId` 里带下划线（如 `a_b`）的构造绕过。
     *
     * @param dollId 已经过 `DollStore.byId` 存在性校验的人偶 id
     */
    private fun isAllowedPersonaKey(dollId: String, fullKey: String): Boolean {
        if (dollId.isEmpty() || fullKey.isEmpty()) {
            return false
        }
        if (isDangerousKey(fullKey)) {
            return false
        }
        return personaKeysOf(dollId).contains(fullKey)
    }

    /**
     * 【P0·工厂预写豁免清单】启动种子 `DollProfile.seedDefault()` 会给**默认人偶**预写的键。
     *
     * ## 为什么需要单独一张表（而不是把判据改宽）
     * 恢复侧的口径是「只写本机没有的键」，目的是**不覆盖用户的选择**。
     * 但 `p.contains(k)` 把「工厂写的默认值」也当成了「用户的选择」—— 保护对象搞错了。
     * 我们对「用户**没**表过态」的键没有意见；我们只是不能碰「用户**改过**」的键。
     *
     * ## 为什么必须限定在 DEFAULT_ID
     * `seedDefault()` 只写 `key(DEFAULT_ID, ...)` = **裸键**（见 [DollProfile.key]）。
     * 用户新建的人偶没有任何工厂预写（`重置/新建`走的是空白模板），
     * 所以非默认人偶的键**不在**本表里 —— 它们的判据保持纯 `contains`，
     * 效果与修复前一致。把整张表当成「所有人偶都豁免」会误把别人机器上的
     * 旧人设顶到本机非默认人偶上。
     *
     * ## 为什么值比对是安全的（对比 scrim 那次）
     * `seedDefault` 写的是**确定常量**：
     *   · `persona`    ← `PetPrefs.SYSTEM_PROMPT`（固定字符串常量）
     *   · `talk_lines` ← `PetService.LINES_TAP` 序列化成 JSON（固定 3 条）
     * 它们不随机器/时间变化，所以「当前值 == 工厂值」能可靠地代表「这个值还是工厂写的」。
     * 反例（**故意不采用**）：`scrim_strength` 的迁移公式里含着当前值，值比对会变成
     * 循环不动点（会把用户主动拖到 0~40 的任何值都判成「没动过」）—— 那个场景必须用
     * 快照键。本处没有这个风险，所以不需要快照键。
     *
     * 注意这两个键**不在**本表之外的任何地方被启动逻辑写：`DollProfile` 的其余
     * setter 只在 UI 交互时调用（已逐个核对 `away_seconds` / `pet_scale` /
     * `return_seconds` / `home_*` / `frame_*` / `bounds_*` / `chatbg*` / `bubble_*` /
     * `size_spec` / `flip` / `gesture_*` / `persona_spec` —— 均无启动预写）。
     */
    private val SEEDED_PERSONA_BASES = arrayOf(
        "persona", "talk_lines"
    )

    /**
     * 把 `talk_lines` 序列化成与 `DollProfile.seedDefault()` 完全一致的形式。
     *
     * 【为什么必须逐字复刻而不是「解析成列表再比内容」】`seedDefault` 用
     * `JSONArray().put(line)` 逐条写入，恢复侧的 `hasMessages`/解析口径不影响这里；
     * 如果我们用「解析后比较集合」，用户把 3 条台词**调换了顺序**就会被误判成
     * 「没动过」而覆盖掉 —— 顺序本身就是用户的编辑。所以按原始字符串比较。
     */
    private fun seedTalkLinesRaw(): String {
        val arr = JSONArray()
        for (line in PetService.LINES_TAP) {
            arr.put(line)
        }
        return arr.toString()
    }

    /**
     * 这个键的当前值是否**仍等于工厂种子写的那个值**（= 用户没改过）。
     *
     * @return true 表示「用户没动过，允许备份覆盖」
     */
    private fun isStillFactoryValue(p: SharedPreferences, key: String): Boolean {
        return try {
            when (key) {
                DollProfile.key(DollStore.DEFAULT_ID, "persona") ->
                    p.getString(key, null) == PetPrefs.SYSTEM_PROMPT
                DollProfile.key(DollStore.DEFAULT_ID, "talk_lines") ->
                    p.getString(key, null) == seedTalkLinesRaw()
                else -> false
            }
        } catch (t: Throwable) {
            // 读失败就当作「不是工厂值」→ 保守地不覆盖（宁可少写，不可误覆盖用户内容）。
            false
        }
    }

    /**
     * 人设恢复的核心判据：**这个键到底该不该被备份写入**。
     *
     * 三种情形：
     * 1. **键不存在** → 写（本机确实没有，这正是「补齐」的本意）。
     * 2. **键存在，但值是工厂种子写的** → 写（用户没表过态，工厂值不是用户的选择）。
     * 3. **键存在，且值不是工厂值** → 不写（用户改过，必须保留）。
     *
     * 第 2 条是本次 P0 修复的关键：没有它，换机用户（必然先启动过 App）
     * 的性格与台词永远恢复不了。
     *
     * 【为什么例外表只对 DEFAULT_ID 生效】见 [SEEDED_PERSONA_BASES] 的说明。
     */
    private fun shouldWritePersonaKey(p: SharedPreferences, dollId: String, key: String): Boolean {
        if (!p.contains(key)) {
            return true
        }
        // 只有默认人偶会被 seedDefault 预写；非默认人偶保持纯 contains 语义。
        if (dollId != DollStore.DEFAULT_ID) {
            return false
        }
        // 这个键是否属于「会被工厂预写」的那一批（避免把用户真改过的其它键也覆盖）。
        var seeded = false
        for (b in SEEDED_PERSONA_BASES) {
            if (key == DollProfile.key(dollId, b)) {
                seeded = true
                break
            }
        }
        if (!seeded) {
            return false
        }
        return isStillFactoryValue(p, key)
    }

    /**
     * 动作库在偏好里的键前缀。
     *
     * 【为什么硬编码】`DollAnim.KEY_PREFIX` 是 private；本类只需要「原样搬运这段 JSON」，
     *   不需要理解它的语义（语义仍归 DollAnim），所以拿前缀拼键比开放一个 getter 更轻。
     *   前缀一旦变动，DollAnim 那侧的动作会全部读不到 —— 属于会立刻被发现的错误，
     *   不存在「悄悄错很久」的风险。
     */
    private const val ANIM_KEY_PREFIX = "doll_anim_"

    /* ============================ 导出 ============================ */

    /**
     * 导出：把当前数据组装成 v2 信封。
     *
     * 段落顺序不影响语义，但按「先小后大」排（图片放最后）能让超限时的日志更容易读。
     */
    @JvmStatic
    fun export(ctx: Context, appVersion: String): JSONObject {
        val dolls = exportDolls(ctx)
        val wanted = collectWanted(ctx)
        val images = packImages(ctx, wanted)
        return BackupEnvelope.build(
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersion,
            providers = exportProviders(ctx),
            models = exportModels(ctx),
            dolls = dolls,
            memories = exportMemories(ctx),
            settings = exportSettings(ctx),
            persona = exportPersona(ctx, dolls),
            chat = exportChat(ctx),
            myProfile = exportMyProfile(ctx),
            memLinks = exportMemLinks(ctx),
            memProfiles = exportMemProfiles(ctx),
            anim = exportAnim(ctx),
            images = images
        )
    }

    /**
     * 导出时被跳过的图片张数（供导出后的提示使用）。
     *
     * 【为什么要有这个数字】「备份完成了」这句话在图片被预算挤掉时是不诚实的。
     *   用户换机后才发现封面缺了几张、自建动作播不出来，而当时什么都没被告知 ——
     *   那正是本模块要消灭的失败态。导出方拿到非 0 就该提示「有 N 张图因体积过大未打包」。
     *
     * 【为什么用 @Volatile 的静态字段而不是改 export 的返回类型】
     *   [export] 的签名 `(Context, String) -> JSONObject` 是老调用方（设置页 / UI）的契约，
     *   改返回值会直接撞上正在并行改造的 UI 任务。这里用「附带结果」的方式给出，
     *   不破坏任何既有调用。
     */
    @Volatile
    private var lastSkippedImages: Int = 0

    /** 上一次 [export] 因体积原因跳过的图片张数；0 表示全部打包成功。 */
    @JvmStatic
    fun lastSkippedImageCount(): Int {
        return lastSkippedImages
    }

    /** 供应商：**逐字段手写并剔除 apiKey**，避免哪天 Provider.toJson 加了密钥字段就顺手带出去。 */
    private fun exportProviders(ctx: Context): JSONArray {
        val arr = JSONArray()
        for (p in ProviderStore.providers(ctx)) {
            val o = p.toJson()
            o.remove("apiKey")
            arr.put(o)
        }
        return arr
    }

    private fun exportModels(ctx: Context): JSONArray {
        val arr = JSONArray()
        for (m in ModelStore.models(ctx)) {
            arr.put(m.toJson())
        }
        return arr
    }

    /**
     * 人偶记录。
     *
     * 【为什么还写绝对路径】v1 就是这么写的；保留它能让「同机重装」的旧路径继续可用，
     *   也让 [BackupEnvelope] 的 v1 结构性兼容不需要任何转换代码。
     *   换机时真正起作用的是 [SEC_IMAGES] 里的相对路径映射（见 [restoreImages]）。
     */
    private fun exportDolls(ctx: Context): JSONArray {
        val arr = JSONArray()
        for (d in DollStore.all(ctx)) {
            val o = JSONObject()
            o.put("id", d.id)
            o.put("name", d.name)
            o.put("cover", d.cover)
            o.put("sprite", d.sprite)
            o.put("enabled", d.enabled)
            o.put("builtin", d.builtin)
            if (d.group.isNotEmpty()) {
                o.put("group", d.group)
            }
            arr.put(o)
        }
        return arr
    }

    /**
     * 人设段。
     *
     * 【为什么存「完整键 + 类型 + 值」而不是「基名 + 值」】
     *   键规则（默认人偶裸键、其余带 `_<dollId>` 后缀）已经由 [DollProfile.key] 拼好，
     *   恢复端直接按完整键写回即可，**不必再实现一遍人名拼接规则**；
     *   而 SharedPreferences 是强类型的（int / float / boolean / String），
     *   只存字符串会在恢复时把 `pet_scale=120` 变成字符串，读取端 `getInt` 直接抛
     *   ClassCastException。带上类型位是一行代码换掉一整类崩溃。
     */
    private fun exportPersona(ctx: Context, dolls: JSONArray): JSONObject {
        val out = JSONObject()
        val all = PetPrefs.get(ctx).all
        for (i in 0 until dolls.length()) {
            val d = dolls.optJSONObject(i) ?: continue
            val dollId = d.optString("id", "")
            if (dollId.isEmpty()) {
                continue
            }
            // 【P0 安全·与导入侧共用同一份清单】键的展开规则只有 [personaKeysOf] 一处。
            //   修复前这里用的是内联的两层循环、导入侧完全没有对应校验 ——
            //   正是那种「两边各写一份」导致导出严、导入松的非对称。详见 [isAllowedPersonaKey]。
            val keys = personaKeysOf(dollId)
            val arr = JSONArray()
            for (k in keys) {
                val v = all[k] ?: continue
                val e = encodeValue(k, v)
                if (e != null) {
                    arr.put(e)
                }
            }
            if (arr.length() > 0) {
                out.put(dollId, arr)
            }
        }
        return out
    }

    /** 把一个偏好值编码成 `{k,t,v}`；不认识的值类型返回 null（宁可少装一个，也不要写错类型）。 */
    private fun encodeValue(key: String, v: Any?): JSONObject? {
        if (v == null) {
            return null
        }
        val o = JSONObject()
        o.put("k", key)
        when (v) {
            is Boolean -> {
                o.put("t", "b")
                o.put("v", v)
            }
            is Int -> {
                o.put("t", "i")
                o.put("v", v)
            }
            is Long -> {
                o.put("t", "l")
                o.put("v", v)
            }
            is Float -> {
                // 【坑】JSON 只有 double；这里存 double，读的时候再缩回 float。
                o.put("t", "f")
                o.put("v", v.toDouble())
            }
            is String -> {
                o.put("t", "s")
                o.put("v", v)
            }
            else -> return null
        }
        return o
    }

    /**
     * 聊天记录段。
     *
     * 【为什么原样搬运字符串】每条会话本身已经是一段 JSON 文本（`conv_<id>`），
     *   重新解析再序列化只会引入一轮「结构不认识就丢字段」的风险；
     *   本段不解释消息结构，只负责把字节搬过去。
     */
    private fun exportChat(ctx: Context): JSONObject {
        val out = JSONObject()
        out.put("index", PetPrefs.convIndex(ctx))
        out.put("current", PetPrefs.convCurrent(ctx))
        out.put("currentPet", PetPrefs.convCurrentPet(ctx))
        out.put("pool", PetPrefs.convPool(ctx))
        val items = JSONObject()
        val prev = JSONObject()
        val meta = JSONObject()
        val index = parseArray(PetPrefs.convIndex(ctx))
        for (i in 0 until index.length()) {
            val o = index.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) {
                continue
            }
            val raw = PetPrefs.convHistory(ctx, id)
            if (raw.isNotEmpty() && raw != "[]") {
                items.put(id, raw)
            }
            val p = PetPrefs.convPrev(ctx, id)
            if (p.isNotEmpty() && p != "[]") {
                prev.put(id, p)
            }
            // 展示层偏好：绑定的人偶、头像、已读水位、是否隐藏 / 已清空。
            // 【为什么带这些】换机后如果丢了「这条会话绑的是哪只人偶」，
            //   人偶池会话就会全部变成「未绑定」，聊天性格与抬头像对不上。
            val m = JSONObject()
            m.put("doll", PetPrefs.convDoll(ctx, id))
            m.put("avatar", PetPrefs.convAvatar(ctx, id))
            m.put("read", PetPrefs.convRead(ctx, id))
            m.put("hidden", PetPrefs.convHidden(ctx, id))
            m.put("cleared", PetPrefs.isConvCleared(ctx, id))
            meta.put(id, m)
        }
        out.put("items", items)
        out.put("prev", prev)
        out.put("meta", meta)
        return out
    }

    private fun exportMyProfile(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("name", MyProfile.name(ctx))
        o.put("bio", MyProfile.bio(ctx))
        o.put("avatar", MyProfile.avatarPath(ctx))
        return o
    }

    /** 记忆条目：沿用 v1 的 `{ "<dollId>": [...] }`，v1 文件读进来就是同一形状。 */
    private fun exportMemories(ctx: Context): JSONObject {
        val out = JSONObject()
        for (doll in DollStore.all(ctx)) {
            val arr = Mem.list(ctx, doll.id)
            if (arr.length() > 0) {
                out.put(doll.id, arr)
            }
        }
        return out
    }

    private fun exportMemLinks(ctx: Context): JSONObject {
        val out = JSONObject()
        for (doll in DollStore.all(ctx)) {
            val arr = Mem.links(ctx, doll.id)
            if (arr.length() > 0) {
                out.put(doll.id, arr)
            }
        }
        return out
    }

    private fun exportMemProfiles(ctx: Context): JSONObject {
        val out = JSONObject()
        for (doll in DollStore.all(ctx)) {
            val md = Mem.profile(ctx, doll.id)
            if (md.isNotEmpty()) {
                out.put(doll.id, md)
            }
        }
        return out
    }

    /**
     * 动作库元数据（帧图本体在图片段）。
     *
     * 【v0.2.8·修 P1·内置动作不得带出源机帧路径】
     *
     * 改造前这里是**整段原样搬运**：`out.put(doll.id, raw)`。而 [collectWanted] 又
     * **刻意跳过内置动作的帧图**（理由见该函数：内置帧由 [com.dollhouse.app.pet.AnimBaker]
     * 在本机按机型实时烘，换机后必须按新机型重烘，塞进包里只是十几 MB 废数据）。
     *
     * 这两条决策放在一起就自相矛盾：
     *   · 帧**不**打包  → 目标机拿到的是 base64 之外的空气
     *   · 路径**照**导出 → 目标机动作库里写满 `/data/user/0/.../anim/<id>/0.png` 这类源机绝对路径
     * 恢复后 `frames` **非空但全部指向不存在的文件**，于是：
     *   · `DollAnimPage` 判 `frames.isNotEmpty()` 就当"有帧"直接用，**不会重烘**；
     *   · `ActionPlayerCore.decodeFramesAsync` 逐张解码失败 → 画面停成一张静态图。
     * 用户换机后看到的是「人偶不会动了」，而且没有任何提示。
     *
     * 【修法】内置动作只导元数据，**frames 与 asset_path 一律不写**。
     *   恢复侧（[restoreAnim]）据此把它们标成「待重烘」，再由 [rebakeBuiltinFrames]
     *   按本机屏幕重新生成。这样「帧不打包」这条决策才真正自洽。
     *
     * 【为什么保留其余字段】id / name / fps / loop / group / builtin 是**元数据**，
     *   与机型无关：用户可能调过某个内置动作的 fps，或把它挪过分组 —— 那些要带走。
     */
    private fun exportAnim(ctx: Context): JSONObject {
        val out = JSONObject()
        val p = PetPrefs.get(ctx)
        for (doll in DollStore.all(ctx)) {
            val raw = p.getString(ANIM_KEY_PREFIX + doll.id, "") ?: ""
            if (raw.isEmpty()) {
                continue
            }
            val arr = parseArray(raw)
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                if (!a.optBoolean("builtin", false)) {
                    continue
                }
                // 【就地剥掉】arr 是 parseArray 出来的新对象（不是共享引用），
                //   改它不会影响磁盘上那份动作库。
                a.remove("frames")
                a.put("asset_path", "")
            }
            out.put(doll.id, arr.toString())
        }
        return out
    }

    /** 全局开关与外观：只带「换机仍然有意义」的项，路径 / 会话 id / 时间戳这类设备相关值不带。 */
    private fun exportSettings(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("theme", PetPrefs.themeMode(ctx))
        o.put("themeMonet", PetPrefs.themeMonet(ctx))
        o.put("memAuto", PetPrefs.mem2Auto(ctx))
        o.put("memInterval", PetPrefs.mem2IntervalMinutes(ctx))
        o.put("memMode", PetPrefs.mem2Mode(ctx))
        o.put("memSearch", PetPrefs.mem2Search(ctx))
        o.put("webSearch", PetPrefs.webSearchEnabled(ctx))
        o.put("think", PetPrefs.thinkLevel(ctx))
        o.put("bgOn", PetPrefs.bgEnabled(ctx))
        o.put("bgMode", PetPrefs.bgMode(ctx))
        o.put("bgAlpha", PetPrefs.chatBgAlpha(ctx))
        o.put("bgBlur", PetPrefs.blurRadius(ctx))
        o.put("scrim", PetPrefs.scrimStrength(ctx))
        o.put("frost", PetPrefs.frostStrength(ctx))
        o.put("groups", PetPrefs.dollGroups(ctx))
        return o
    }

    /* ============================ 图片 ============================ */

    /**
     * 一张待入包的图片：谁在用（owner）、用在哪（field）、记录里原样的路径（raw）。
     *
     * 【为什么按「归属字段」而不是按目录扫描】私有目录里还有导入中间件
     *   （`import/`）、Live2D 模型包（`doll/model/`）等大量与本段无关的体积；
     *   只装**记录里真的引用着**的文件，才不会把一份「早就没人用的老封面」
     *   也搬进包里，也不会把几十 MB 的模型目录顶爆存档。
     */
    private class Wanted(val owner: String, val field: String, val raw: String)

    /**
     * 收集需要入包的图片。
     *
     * 【形象帧为什么只收「非内置」动作】内置动作的帧是 [com.dollhouse.app.pet.AnimBaker]
     *   在**本机实时烘**出来的，换机后会按新机型重新烘一次（分辨率不同本来也必须重烘）。
     *   把它们塞进包只会让存档膨胀十几 MB 换一堆目标机用不上的旧帧。
     *   用户自己导入 / 裁剪的帧没法重建，必须带上 —— 那才是「丢失就找不回来」的部分。
     */
    private fun collectWanted(ctx: Context): List<Wanted> {
        val out = ArrayList<Wanted>()
        val p = PetPrefs.get(ctx)
        for (d in DollStore.all(ctx)) {
            out.add(Wanted("doll:" + d.id, "cover", d.cover))
            out.add(Wanted("doll:" + d.id, "sprite", d.sprite))
            out.add(Wanted("doll:" + d.id, "chatbg", DollProfile.chatBgPath(ctx, d.id)))
            val raw = p.getString(ANIM_KEY_PREFIX + d.id, "") ?: ""
            if (raw.isEmpty()) {
                continue
            }
            val arr = parseArray(raw)
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                if (a.optBoolean("builtin", false)) {
                    continue
                }
                val actionId = a.optString("id", "")
                val frames = a.optJSONArray("frames")
                if (frames != null) {
                    for (j in 0 until frames.length()) {
                        val f = frames.optString(j, "")
                        if (f.isNotEmpty()) {
                            out.add(Wanted("doll:" + d.id, "frame:" + actionId, f))
                        }
                    }
                }
                val asset = a.optString("asset_path", "")
                if (asset.isNotEmpty()) {
                    out.add(Wanted("doll:" + d.id, "asset:" + actionId, asset))
                }
            }
        }
        // 用户自己的头像 / 人偶独立背景之外，还有「会话自定义头像」。
        out.add(Wanted("myprofile", "avatar", MyProfile.avatarPath(ctx)))
        val index = parseArray(PetPrefs.convIndex(ctx))
        for (i in 0 until index.length()) {
            val o = index.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) {
                continue
            }
            out.add(Wanted("conv:" + id, "avatar", PetPrefs.convAvatar(ctx, id)))
        }
        return out
    }

    /**
     * 编码图片段：`{rel, owner, field, src, size, b64}`。
     *
     * 【为什么 b64 而不是 zip 条目】存档的落盘与分享全在 [BackupFile]（本次不改它），
     *   它只会写「一个 JSON 文本文件」。往 JSON 里放 base64 是唯一不需要
     *   改动落盘链路、也不破坏「单文件、可读、可 diff」这三条既有性质的做法。
     *   代价是体积 +33%，因此 [BackupEnvelope.MAX_BYTES] 同步提到 256 MiB。
     */
    private fun packImages(ctx: Context, wanted: List<Wanted>): JSONArray {
        val arr = JSONArray()
        val seen = HashSet<String>()
        var budget = BackupEnvelope.MAX_IMAGE_BYTES
        var skipped = 0
        for (w in wanted) {
            val rel = relOf(ctx, w.raw)
            if (rel.isEmpty()) {
                continue
            }
            // 同一个「归属 + 字段」只会有一张图（一只人偶的封面就是一张），
            // 按这个组合去重即可：重复条目会让恢复端把同一字段写两遍、白占体积。
            val f = File(ctx.filesDir, rel)
            if (!f.isFile || f.length() <= 0L) {
                continue
            }
            val size = f.length()
            // 【去重键必须分两类】
            //   · 单值字段（cover / sprite / chatbg / avatar）：一只人偶只有一个封面，
            //     按「归属|字段」去重即可；
            //   · 多值字段（frame:" + actionId / asset:" + actionId）：一个动作有 N 张帧，
            //     它们共用同一个 owner|field —— 只按这两段去重会让**除第一帧外的所有帧都被丢掉**，
            //     恢复后动作只剩一帧，用户看到的是「动作闪一下」。所以必须带上 rel。
            val key = if (w.field.startsWith("frame:") || w.field.startsWith("asset:")) {
                w.owner + "|" + w.field + "|" + rel
            } else {
                w.owner + "|" + w.field
            }
            if (!seen.add(key)) {
                continue
            }
            if (size > MAX_FILE_BYTES) {
                Logs.w(TAG, "跳过超大图片：" + rel + "（" + size + " 字节）")
                skipped++
                continue
            }
            if (size > budget) {
                Logs.w(TAG, "图片预算用尽，跳过：" + rel)
                skipped++
                continue
            }
            val bytes = try {
                f.readBytes()
            } catch (t: Throwable) {
                Logs.w(TAG, "读取图片失败：" + rel, t)
                skipped++
                continue
            }
            budget -= bytes.size
            val e = JSONObject()
            e.put("rel", rel)
            e.put("owner", w.owner)
            e.put("field", w.field)
            // src = 来源机上记录里原样的路径字符串：恢复帧图时要靠它把
            // 动作 JSON 里的旧绝对路径改写成目标机的新绝对路径（见 rebindAnim）。
            e.put("src", w.raw)
            e.put("size", bytes.size.toLong())
            e.put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            arr.put(e)
        }
        // 【必须落这个计数】见 [lastSkippedImageCount] 的说明：
        //   「备份完成」在图片被挤掉时不诚实，导出方要能据此提示用户。
        lastSkippedImages = skipped
        return arr
    }

    /**
     * 把记录里的路径（可能是绝对路径，也可能是相对 filesDir 的路径）换算成 filesDir 内的相对路径。
     *
     * 【越界口径】与 [ImageStore.isInsideBase] 一致：解析到 filesDir 之外一律返回空串
     *   （用户手工填过别的目录、或路径里带 `../`），这类文件不入包 ——
     *   否则恢复时目标机会把包里的字节写到它自己的私有目录之外。
     */
    private fun relOf(ctx: Context, raw: String?): String {
        if (raw.isNullOrEmpty()) {
            return ""
        }
        val base = ctx.filesDir
        return try {
            val f = if (raw.startsWith("/")) File(raw) else File(base, raw)
            val basePath = base.canonicalPath.trimEnd(File.separatorChar)
            val target = f.canonicalPath
            if (target == basePath || !target.startsWith(basePath + File.separatorChar)) {
                return ""
            }
            target.substring(basePath.length + 1)
        } catch (t: Throwable) {
            ""
        }
    }

    /* ============================ 恢复 ============================ */

    /** 恢复时统计到的结果，最后拼成一句摘要。 */
    private class Restored {
        /** 「归属|字段」→ 目标机路径（单值字段：封面 / 形象 / 背景 / 头像）。 */
        val singles = HashMap<String, String>()
        /** 来源机路径 → 目标机绝对路径（多值字段：动作帧 / 入口资源）。 */
        val frames = HashMap<String, String>()
        /** 真正写到盘上的文件数（含本来就存在而跳过的）。 */
        var filesWritten = 0

        /**
         * 写盘失败的图片张数（【P1-3】此前完全静默，用户拿到「恢复完成」却看到空白封面）。
         *
         * 【为什么要单独计数而不是并进 filesWritten】两者语义相反：一个是「确实有了」，
         *   一个是「本来该有却没有」。混在一起会让摘要既不能报成功也不能报失败。
         *   这个数字唯一的用途就是让摘要**诚实**地说出「有几张没写进去」。
         */
        var failedImages = 0
    }

    /**
     * 恢复过程的失败收集器 —— **唯一口径**。
     *
     * ## 为什么必须逐段收集，而不是包一个巨大的 try/catch
     * 恢复的九段是**依次落盘**的。若第 5 段（人设）抛异常而外面只有一个大 catch：
     *   · 前 4 段（供应商 / 模型 / 图片 / 人偶）**已经写进去了**；
     *   · 异常冒泡到 UI → 显示红色「恢复失败」；
     *   · **用户得到的信息与事实相反**：他以为全没恢复，实际恢复了一半。
     *   他可能去卸载重装「清干净」，**把已恢复的那半也一并丢掉** —— 比不恢复更糟。
     *
     * 所以每段独立 try/catch，一段失败不阻断后续（能恢复多少算多少），
     * 并把**段名**记下来让摘要能如实说出「哪几段没成」。
     *
     * ## 段名用中文而非段常量
     * `SEC_PERSONA` 这类是存储层标识（"persona"），用户读不懂也不必读；
     * 这里存的是给人看的名字（"人设"）。两者不混用，避免把内部字段名泄漏到 UI。
     */
    private class RestoreFailures {
        private val names = ArrayList<String>()

        /** 记一段失败。`t` 可为 null（调用方只想标记失败、不想记栈）。 */
        fun record(name: String, t: Throwable?) {
            names.add(name)
            Logs.w(TAG, "恢复段失败：" + name, t)
        }

        fun isEmpty(): Boolean = names.isEmpty()

        /** 给人看的段名串联，如「人设、聊天记录」。 */
        fun describe(): String {
            val sb = StringBuilder()
            for (n in names) {
                if (sb.isNotEmpty()) {
                    sb.append("、")
                }
                sb.append(n)
            }
            return sb.toString()
        }
    }

    /** 跑一个不返回值的恢复段；抛异常就记名并继续下一段。 */
    private inline fun runSection(failures: RestoreFailures, name: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            failures.record(name, t)
        }
    }

    /** 跑一个返回值的恢复段；抛异常时记名并返回 null（调用方用 `?: 默认值` 兜底）。 */
    private inline fun <T> runSectionValue(
        failures: RestoreFailures,
        name: String,
        body: () -> T
    ): T? {
        return try {
            body()
        } catch (t: Throwable) {
            failures.record(name, t)
            null
        }
    }

    /**
     * 恢复：把信封写回本机。
     *
     * 【冲突口径】「只新增 / 只补齐」：目标机已有的同名供应商、已有的人偶字段、
     *   已存在的会话历史与人设键一律不动。这样用户在任何时候点恢复，
     *   最坏情况都是「没导进来」，而不是「把现在的东西覆盖掉」。
     *
     * 【P1·逐段异常隔离】见 [RestoreFailures]。任何一段失败都不会阻断后续段，
     *   摘要会如实报出失败的段名并把整体表述为「部分恢复完成」。
     *
     * @return 一句可直接给用户看的摘要
     */
    @JvmStatic
    fun restore(ctx: Context, root: JSONObject?): String {
        if (root == null) {
            return "备份内容为空，未做任何修改。"
        }
        val ver = root.optInt("ver", 1)
        val old = ver < 2

        val failures = RestoreFailures()

        // ---- 供应商 ----
        var providersAdded = 0
        var apiKeyNeeded = 0
        // `byName` 要跨到模型段用（模型要按供应商名重新挂归属），所以在段外声明。
        val byName = HashMap<String, Provider>()
        val pvArr = BackupEnvelope.sectionArray(root, SEC_PROVIDERS)
        runSection(failures, "供应商") {
            for (p in ProviderStore.providers(ctx)) {
                byName[p.name] = p
            }
            for (i in 0 until pvArr.length()) {
                val o = pvArr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) {
                    continue
                }
                if (byName.containsKey(name)) {
                    // 同名已存在：不覆盖。密钥是设备绑定的密文，覆盖只会把能用的换成不能用的。
                    continue
                }
                val p = Provider.fromJson(o)
                // 存档里本就没有密钥（v2 已剔除）；这里显式再清一次，防止有人手工塞了密文进来。
                p.apiKey = ""
                if (p.id.isEmpty()) {
                    p.id = ProviderStore.newId()
                }
                ProviderStore.saveProvider(ctx, p)
                byName[name] = p
                providersAdded++
                apiKeyNeeded++
            }
        }

        // ---- 模型 ----
        var modelsAdded = 0
        val modelsArr = BackupEnvelope.sectionArray(root, SEC_MODELS)
        runSection(failures, "模型") {
            for (i in 0 until modelsArr.length()) {
                val o = modelsArr.optJSONObject(i) ?: continue
                val m = AiModel.fromJson(o)
                if (m.displayName.isEmpty()) {
                    continue
                }
                // 归属供应商可能因为同名已存在而换了 id：按名字重新挂一次。
                val owner = byName.values.firstOrNull { it.id == m.providerId }
                    ?: byName.values.firstOrNull { it.name == o.optString("providerName", "") }
                if (owner != null) {
                    m.providerId = owner.id
                }
                if (m.providerId.isEmpty()) {
                    continue
                }
                if (ModelStore.modelExists(ctx, m.providerId, m.displayName, null)) {
                    continue
                }
                if (m.id.isEmpty() || ModelStore.findModel(ctx, m.id) != null) {
                    m.id = ProviderStore.newId()
                }
                ModelStore.saveModel(ctx, m)
                modelsAdded++
            }
        }

        // ---- 图片（必须早于人偶：新人偶要直接指向写好的文件） ----
        // 【顺序不可调换】人偶段要用 restored.singles 里的封面 / 形象路径，
        //   人设段还要用 singles 里的 chatbg 新路径。图片没落盘就换不到路径。
        //   即使图片段抛异常，`restored` 也会是那个「尽力而为」的实例（见下），
        //   后续段照常执行、只是路径可能回落默认 —— 这比整段中止好得多。
        val restored = runSectionValue(failures, "图片") {
            restoreImages(ctx, BackupEnvelope.sectionArray(root, SEC_IMAGES))
        } ?: Restored()

        // ---- 人偶 ----
        var dollsAdded = 0
        var dollsFilled = 0
        var activeCandidate = ""
        val dollsArr = BackupEnvelope.sectionArray(root, SEC_DOLLS)
        runSection(failures, "人偶") {
        for (i in 0 until dollsArr.length()) {
            val o = dollsArr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) {
                continue
            }
            val name = o.optString("name", "").trim()
            val cover = pickPath(restored.singles["doll:" + id + "|cover"], o.optString("cover", ""))
            val sprite = pickPath(restored.singles["doll:" + id + "|sprite"], o.optString("sprite", ""))
            val group = o.optString("group", "")
            val hit = DollStore.byId(ctx, id)
            if (hit == null) {
                // 默认配置永远存在（DollStore.load 会补），走不到这里；
                // 但万一存档里混进了一条 id=default 的脏记录，也不该再插一条。
                if (id == DollStore.DEFAULT_ID) {
                    continue
                }
                DollStore.upsertRecord(
                    ctx,
                    DollStore.Doll(
                        id = id,
                        name = if (name.isEmpty()) "恢复的人偶" else name,
                        cover = cover,
                        sprite = sprite,
                        // 【不照搬 enabled】恢复时把别人机器上的「常驻」也照搬过来，
                        //   等于一恢复就替用户换了屏幕上的角色。这里一律先 false，
                        //   仅当本机当前没有任何常驻角色时，才在循环后启用一只（见下）。
                        enabled = false,
                        builtin = false,
                        group = group
                    )
                )
                dollsAdded++
                if (o.optBoolean("enabled", false) && activeCandidate.isEmpty()) {
                    activeCandidate = id
                }
            } else {
                var cur = hit
                if (cur.name.isEmpty() && name.isNotEmpty()) {
                    cur = cur.copy(name = name)
                }
                if (cur.cover.isEmpty() && cover.isNotEmpty()) {
                    cur = cur.copy(cover = cover)
                }
                if (cur.sprite.isEmpty() && sprite.isNotEmpty()) {
                    cur = cur.copy(sprite = sprite)
                }
                if (cur.group.isEmpty() && group.isNotEmpty()) {
                    cur = cur.copy(group = group)
                }
                if (cur != hit) {
                    DollStore.update(ctx, cur)
                    dollsFilled++
                }
            }
        }
        // 【只在没有常驻角色时补一只】本机本来就有在用的角色，就不动它。
        if (activeCandidate.isNotEmpty() && DollStore.active(ctx) == null) {
            DollStore.setActive(ctx, activeCandidate)
        }
        }

        // ---- 人设 ----
        // 【必须在 restoreImages 之后】人设里含人偶背景图的路径，要换成目标机的新路径。
        // 【本段失败时摘要口径】personaStat 会是 null → 摘要显示「人设未能恢复（这一段没跑）」，
        //   与「跑了但全部被你现有的设置保留」是**两种不同状态**，绝不能混成一个数字。
        //   所以这里用 runSectionValue 返回 null，而不是兜一个空的 PersonaStat()。
        val personaStat = runSectionValue(failures, "人设") {
            restorePersona(
                ctx,
                BackupEnvelope.sectionObject(root, SEC_PERSONA),
                restored.singles
            )
        }

        // ---- 动作库（先落元数据，再改写帧路径） ----
        val animCount = runSectionValue(failures, "动作库") {
            restoreAnim(ctx, BackupEnvelope.sectionObject(root, SEC_ANIM), restored.frames)
        } ?: 0

        // ---- 记忆 ----
        //
        // 【P1·超限必须在**写入前**拦住，不能靠 Mem.add 事后截断】
        //   `Mem.add` 的语义是「追加后若超 MAX_ENTRIES 就砍掉最旧的」，并且**仍然返回 true**。
        //   这个语义对日常使用是对的（新记忆最重要，旧的让位），但**恢复场景下是灾难**：
        //     备份 300 条 → 第 201 条起每条写进去就被砍 → Mem.add 照样 true →
        //     `memsAdded` 照常累加 → 摘要报「新增 300 条」，盘上只有 200 条，
        //     而且**丢的恰是最早那批**（时间最久、情感价值最高）。
        //   所以在恢复侧**写入前查条数**：满了就不写、单独计数、摘要如实报出。
        //   不改进 Mem.add 的 Boolean 契约 —— 它被多处调用，改返回值会波及一大片。
        var memsAdded = 0
        // 因条数上限而**没有写入**的记忆条数（按人偶累计）。
        var memsSkippedByLimit = 0
        // 因上限跳过的人偶 id 集合（摘要里提示「哪些人偶的记忆没导全」）。
        val memLimitedDolls = LinkedHashSet<String>()
        val memObj = BackupEnvelope.sectionObject(root, SEC_MEMORIES)
        runSection(failures, "记忆") {
            val memKeys = memObj.keys()
            while (memKeys.hasNext()) {
                val dollId = memKeys.next()
                // 只恢复到确实存在的人偶上：孤儿记忆检索不到，也没有界面能看。
                if (DollStore.byId(ctx, dollId) == null) {
                    continue
                }
                val arr = memObj.optJSONArray(dollId) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val m = Memory.fromJson(o)
                    if (m.title.isEmpty()) {
                        continue
                    }
                    if (Mem.findByTitle(ctx, dollId, m.title) != null) {
                        continue
                    }
                    // 【写入前查上限】每次循环都重查：本机条数会随上面几次 add 增长，
                    //   用循环外的缓存值会在「本机已有 199 条 + 备份 5 条」时多写 4 条，
                    //   而那 4 条会被 Mem.add 静默砍掉 —— 又回到本任务要消灭的静默丢失。
                    if (Mem.list(ctx, dollId).length() >= Mem.MAX_ENTRIES) {
                        memsSkippedByLimit++
                        memLimitedDolls.add(dollId)
                        continue
                    }
                    if (Mem.add(ctx, dollId, m)) {
                        memsAdded++
                    }
                }
            }
        }
        // 记忆连接同理：`Mem.addLink` 超 MAX_LINKS 也砍最旧并返回 true。
        var linksSkippedByLimit = 0
        val linksAdded = runSectionValue(failures, "记忆连接") {
            restoreMemLinks(ctx, BackupEnvelope.sectionObject(root, SEC_MEMLINKS)) { skipped ->
                linksSkippedByLimit += skipped
            }
        } ?: 0
        val profileFilled = runSectionValue(failures, "记忆画像") {
            restoreMemProfiles(ctx, BackupEnvelope.sectionObject(root, SEC_MEMPROFILE))
        } ?: 0

        // ---- 聊天 ----
        // 【失败时 chatStat 为 null】摘要据此说「聊天记录未能恢复」，
        //   而不是用一个空的 ChatStat 让摘要一句「新增 0 个会话」蒙过去
        //   （那正是本任务要消灭的「看起来成功了」）。
        val chatStat = runSectionValue(failures, "聊天记录") {
            restoreChat(ctx, BackupEnvelope.sectionObject(root, SEC_CHAT), restored.singles)
        }

        // ---- 我 ----
        val meFilled = runSectionValue(failures, "「我」的资料") {
            restoreMyProfile(ctx, BackupEnvelope.sectionObject(root, SEC_MYPROFILE), restored.singles)
        } ?: 0

        // ---- 开关与外观 ----
        val settingsFilled = runSectionValue(failures, "外观与开关") {
            restoreSettings(ctx, BackupEnvelope.sectionObject(root, SEC_SETTINGS))
        } ?: 0

        // ---- 摘要 ----
        //
        // 【P1·先说「部分成功」，再说细节】三态在这里定调：
        //   · 完全成功            → 「恢复完成。」
        //   · 有段失败 / 有图写坏  → 「**部分恢复完成**。」+ 失败清单 + 「其余已恢复」
        //   · 全部段都没成（极端） → 仍然不是「失败」，但要明确说「几乎什么都没恢复」
        // 绝不能把「部分成功」说成「恢复失败」—— 数据其实已经写进去一半了，
        // 说成失败会诱导用户卸载重装「清干净」，把已恢复的那半也丢掉。
        //
        // 【失败 = 段失败 或 图片写盘失败，统一口径】
        //   这两类都是「本次恢复有东西没到位」，必须用**同一个**判据定"部分成功"。
        //   否则会出现「图失败算部分、段失败算成功」这种两套标准。
        val sectionFailed = !failures.isEmpty()
        val imageFailed = restored.failedImages > 0
        val partial = sectionFailed || imageFailed

        val sb = StringBuilder(if (partial) "部分恢复完成。" else "恢复完成。")
        if (sectionFailed) {
            // 【为什么列出段名】用户需要知道「哪些没成、哪些已到位」，
            //   否则他会以为全没恢复（与事实相反），或以为全都好了（更糟）。
            sb.append("\n以下数据恢复失败：").append(failures.describe()).append("。")
            sb.append("其余数据已恢复。")
        }
        sb.append("\n供应商 +").append(providersAdded).append(" 个，模型 +").append(modelsAdded).append(" 个")
        sb.append("\n人偶 +").append(dollsAdded).append(" 只")
        if (dollsFilled > 0) {
            sb.append("（补齐 ").append(dollsFilled).append(" 只的封面 / 形象 / 分组）")
        }
        // 【personaStat 为 null = 这一段根本没跑成】绝不能与「跑了但全部保留」混成一个数字：
        //   · null            → 段失败，摘要已在上面「以下数据恢复失败：人设」里报过；
        //   · dolls==0 且 kept>0 → 跑了，但用户现有设置更新，全部保留（这是**正常且正确**的）。
        //   两者对用户的意义完全不同：前者要重试 / 找回备份，后者什么都不用做。
        if (personaStat != null) {
            if (personaStat.dolls > 0) {
                sb.append("\n人设：").append(personaStat.dolls).append(" 只人偶补齐了设定")
            }
            // 【说清楚「没被覆盖」这件事】被保留的项如果不报，用户会以为备份里的人设已经导进来了，
            //   之后发现性格不对，无从判断是「没导」还是「导了但被我现在的人设挡住了」。
            //   这两者的处置完全不同（前者要重试 / 找回备份，后者什么都不用做），
            //   所以必须显式告知 —— 判对与说清楚是同一件事的两半。
            if (personaStat.keptByUser > 0) {
                sb.append("\n人设：").append(personaStat.keptByUser)
                    .append(" 项保留了你现有的设置（备份里的更旧，未覆盖）")
            }
        }
        if (animCount > 0) {
            sb.append("\n动作库：").append(animCount).append(" 只人偶的动作已写回")
        }
        // 【chatStat 为 null = 聊天段没跑成】同上：不能说「新增 0 个会话」蒙过去。
        if (chatStat != null && chatStat.sessions > 0) {
            sb.append("\n聊天记录：新增 ").append(chatStat.sessions).append(" 个会话")
            if (chatStat.messages > 0) {
                sb.append("，共 ").append(chatStat.messages).append(" 条消息")
            }
        }
        if (memsAdded > 0) {
            sb.append("\n新增记忆 ").append(memsAdded).append(" 条")
        }
        if (linksAdded > 0) {
            sb.append("，记忆连接 ").append(linksAdded).append(" 条")
        }
        // 【P1·超限必须说出来】`Mem.add` 超 200 条会砍最旧的，且**仍返回 true** ——
        //   不说的话用户以为「备份里的记忆全恢复了」，实际最早那批（情感价值最高）没了。
        //   这里报的是**因上限根本没写进去**的条数（restore 侧已改成写前拦截），
        //   与「已写入」严格互斥，不重复计数。
        if (memsSkippedByLimit > 0) {
            sb.append("\n").append(memsSkippedByLimit)
                .append(" 条记忆因超出上限（").append(Mem.MAX_ENTRIES)
                .append(" 条/人偶）未写入")
            if (memLimitedDolls.size == 1) {
                val onlyId = memLimitedDolls.first()
                val name = DollStore.byId(ctx, onlyId)?.name ?: ""
                if (name.isNotEmpty()) {
                    sb.append("，涉及「").append(name).append("」")
                }
            } else if (memLimitedDolls.size > 1) {
                sb.append("，涉及 ").append(memLimitedDolls.size).append(" 只人偶")
            }
        }
        if (linksSkippedByLimit > 0) {
            sb.append("\n").append(linksSkippedByLimit)
                .append(" 条记忆连接因超出上限（").append(Mem.MAX_LINKS)
                .append(" 条/人偶）未写入")
        }
        if (profileFilled > 0) {
            sb.append("\n记忆画像：").append(profileFilled).append(" 只人偶已补齐")
        }
        if (meFilled > 0) {
            sb.append("\n「我」的资料已补齐（昵称 / 签名 / 头像）")
        }
        if (settingsFilled > 0) {
            sb.append("\n外观与开关：补了 ").append(settingsFilled).append(" 项")
        }
        // 【为什么用 filesWritten 而不是映射条数】frames 是「来源路径→新路径」的字典，
            //   同一个 src 被多个动作引用时会合并成一条 —— 拿它的大小当「图片张数」
            //   会少报。filesWritten 数的是真正落到盘上的文件，与用户看到的一致。
            if (restored.filesWritten > 0) {
                sb.append("\n图片写回 ").append(restored.filesWritten).append(" 张")
            }
            // 【P1-3·必须如实报出失败】这些图**没写进去**，对应的人偶封面 / 头像 /
            //   聊天背景会显示为默认或空白。不说的话，用户看到的是一句「恢复完成」，
            //   然后自己发现图没了 —— 比一开始就说清楚更伤信任。
            //   单独一行（不与上一行合并成「N 张，其中 M 张失败」），因为它读起来是
            //   **警告**而不是统计，UI 也据此升级为「部分成功」。
            if (restored.failedImages > 0) {
                sb.append("\n").append(restored.failedImages)
                    .append(" 张图片写回失败（封面 / 头像可能显示为默认）")
            }
            if (old) {
                sb.append("\n\n这是旧版备份，不含聊天记录 / 人设 / 图片。")
            }
            if (apiKeyNeeded > 0) {
                sb.append("\n\nAPI Key 需要重新填写：备份不含密钥，请到「供应商」页补填。")
            } else if (providersAdded == 0 && pvArr.length() > 0) {
                // 【同名供应商已存在的情况】上面那条提示的触发条件是「新建了供应商」，
                //   但更常见的是「供应商本来就在、只是密钥要重填」。这时也必须说，
                //   否则用户恢复完点发送，得到 401，才发现根本没人告诉他密钥没跟着来。
                sb.append("\n\n备份不含 API Key：现有供应商的密钥保持本机原值，若无法使用请重填。")
            }
        // 【v0.2.8·修 P1·这句话以前是谎话】
        //   改造前它写「内置动作会在新机上按当前屏幕重新生成（这是正常的，不是丢动作）」，
        //   但当时**根本没有任何重生成**：导出把源机绝对 frames 路径原样带过来了，
        //   恢复后 `frames` 非空 ⇒ `DollAnimPage` 判"有帧"直接用、不重烘 ⇒
        //   解码全部失败 ⇒ 人偶停成一张静态图。那句提示让用户以为「坏了是正常的」，
        //   于是连报障都不会报 —— 是本轮"界面不说谎"命题里最隐蔽的一处。
        //
        //   现在 [restoreAnim] 会真的清空并重烘，于是这句话才成立；但**必须带上真实数字**：
        //   烘失败的那些不能含糊过去（`AnimBaker` 在缺少渲染环境时会返回空列表）。
        val bake = animBakeStat()
        if (bake.asked > 0) {
            if (bake.ok >= bake.asked) {
                sb.append("\n内置动作已按本机屏幕重新生成（").append(bake.ok).append(" 个）。")
            } else {
                sb.append("\n内置动作已按本机屏幕重新生成（")
                    .append(bake.ok).append("/").append(bake.asked).append(" 个），")
                    .append("其余 ").append(bake.asked - bake.ok)
                    .append(" 个未能生成，可到「动作」页逐个点开重试。")
            }
        }
        sb.append("\n本次只做新增与补齐，没有删除你现有的任何数据。")
        return sb.toString()
    }

    /**
     * 图片段写回。
     *
     * 【唯一允许「写文件」的地方】图片是「同一个文件被记录引用」，记录本身不需要
     *   合并语义；目标机已有同名文件时**跳过不覆盖** —— 那张可能是用户后来自己换的新图。
     *
     * @return 两条映射：单值字段（cover/sprite/chatbg/avatar）与帧图旧路径→新绝对路径
     */
    private fun restoreImages(ctx: Context, images: JSONArray): Restored {
        val out = Restored()
        for (i in 0 until images.length()) {
            val o = images.optJSONObject(i) ?: continue
            val rel = o.optString("rel", "")
            // 越界 / 绝对路径一律拒绝：备份文件可能被手工改过，不能让写文件跑出私有目录。
            if (rel.isEmpty() || !ImageStore.isInsideBase(ctx.filesDir, rel)) {
                continue
            }
            val b64 = o.optString("b64", "")
            if (b64.isEmpty()) {
                continue
            }
            val target = File(ctx.filesDir, rel)
            if (!target.isFile) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val parent = target.parentFile
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                    }
                    target.writeBytes(bytes)
                    out.filesWritten++
                } catch (t: Throwable) {
                    // 【P1-3·不许静默】这张图**没落到盘上**。以前只 Logs.w 后 continue，
                    //   既不计数也不进摘要，用户看到的是绿色「恢复完成」，换机后却是
                    //   空白封面 / 默认头像 —— 以为恢复成功，实际图丢了。
                    //   这里计数，由 [restore] 的摘要如实报出。
                    //
                    //   注意 `continue` 保持了「失败不登记映射」这个正确行为：
                    //   下面那些 `out.singles[...] = ...` / `out.frames[...] = ...`
                    //   都不会执行，于是记录不会指向一个不存在的文件（无死链）。
                    Logs.w(TAG, "写回图片失败：" + rel, t)
                    out.failedImages++
                    continue
                }
            } else {
                // 目标机已有同名文件：跳过不解码，但这条映射仍然有效（记录要指到它）。
                out.filesWritten++
            }
            val owner = o.optString("owner", "")
            val field = o.optString("field", "")
            if (field.startsWith("frame:") || field.startsWith("asset:")) {
                // 帧图是「多值」：一个动作 N 张，靠来源路径逐个对上。
                val src = o.optString("src", "")
                if (src.isNotEmpty()) {
                    out.frames[src] = target.absolutePath
                }
            } else if (REL_FIELDS.contains(field)) {
                // 【v0.2.8·修 P1·路径口径】cover / sprite 存的是**绝对路径**
                // （DollStore.encode 返回绝对路径，消费方 File(path) 直接读），
                // 而 chatbg / avatar 存的是**相对 filesDir 的路径**
                // （ImageStore.saveScaledFile 返回 "子目录/文件名"，消费方
                //   ImageStore.exists → fileFor → isInsideBase 会**明确拒绝绝对路径**）。
                //
                // 改造前这里一律填 target.absolutePath，于是恢复后：
                //   · 人偶独立聊天背景被 isInsideBase 判为越界 → 回落到全局背景；
                //   · 「我」的头像被 commitAvatar 存成绝对路径 → avatarBitmap 读不出 → 显示占位图。
                // 两张图都是"恢复成功但看不见"，最伤信任。按字段区分口径才是对的。
                out.singles[owner + "|" + field] = rel
            } else {
                out.singles[owner + "|" + field] = target.absolutePath
            }
        }
        return out
    }

    /**
     * 选一个可用路径：优先用刚写回的目标机路径；没有就用备份里原样的路径（仅当它在本机真实存在）。
     *
     * 【为什么要求「真实存在」】同机重装的场景里，原绝对路径仍然有效，留着能让记录立刻可用；
     *   换机的场景里原路径必然指向一个不存在的文件，留着就是一条死链 ——
     *   界面会显示空白封面，用户以为「恢复了但图丢了」。宁可为空走默认形象。
     */
    private fun pickPath(restored: String?, original: String?): String {
        if (!restored.isNullOrEmpty()) {
            return restored
        }
        if (original.isNullOrEmpty()) {
            return ""
        }
        return try {
            if (File(original).isFile) original else ""
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 人设写回：只写目标机**从来没有过**的键，且**只写白名单内的键**。
     *
     * 【为什么不覆盖】人设是用户最主观的部分（性格、台词、手势映射）。
     *   恢复一份三个月前的备份就把她现在的性格盖回去，用户会当成「恢复把我改的东西弄没了」。
     *   新建的人偶本来就没有任何键，于是会完整继承 — 这正是想要的。
     *
     * 【P0 安全·为什么必须有白名单（本次修复）】
     *   本段的键名 `k` **整条来自外部备份文件**，而备份文件是用户能从网上下载的东西，
     *   属于**外部输入**，不能按「自己的导出格式」无条件信任。修复前这里唯一的闸门是
     *   `p.contains(k)`（「目标机还没有这个键才写」），于是：
     *     · 恶意备份塞 `{"k":"pv_list","v":"[{\"baseUrl\":\"https://evil.example/v1\"}]"}`
     *     · 刚换新机、**还没配过供应商**的用户 `pv_list` 尚不存在 → 闸门放行
     *     · 供应商列表被整体替换 → 之后**所有聊天明文 POST 到攻击者服务器**
     *   所以「只写本机没有的键」是**功能口径**（不覆盖用户数据），
     *   而「只写白名单内的键」是**安全口径**（不信任外部输入）—— 两者必须同时成立。
     *   判据见 [isAllowedPersonaKey]（与导出侧共用 [personaKeysOf] + [isDangerousKey]）。
     *
     * 【为什么要吃 singles 映射】人设段里有一个**路径型**的键：`chatbg`
     *   （人偶独立的聊天背景图）。它存的是绝对路径，恢复后图落在了目标机的
     *   私有目录里、路径已经变了。不换成新路径的话，这只人偶的背景在恢复后
     *   永远显示不出来 —— 用户会以为「背景没备份过来」。
     *   其余键都是标量（整数 / 布尔 / 文本），原样写回即可。
     *
     * @param singles 图片段的「归属|字段 → 目标机绝对路径」映射（见 [restoreImages]）
     * @return 两项统计：真正补齐的人偶数 + 因「本机已有用户自己的值」而保留的键数
     */
    private fun restorePersona(ctx: Context, section: JSONObject, singles: HashMap<String, String>): PersonaStat {
        val stat = PersonaStat()
        if (section.length() == 0) {
            return stat
        }
        val p = PetPrefs.get(ctx)
        var rejected = 0
        val keys = section.keys()
        while (keys.hasNext()) {
            val dollId = keys.next()
            // 没有这只人偶就没有人读它的键，写进去只会变成永久垃圾。
            if (DollStore.byId(ctx, dollId) == null) {
                continue
            }
            val arr = section.optJSONArray(dollId) ?: continue
            val chatBgKey = DollProfile.key(dollId, "chatbg")
            val chatBgNew = singles["doll:" + dollId + "|chatbg"]
            val e = p.edit()
            var wrote = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("k", "")
                if (k.isEmpty()) {
                    continue
                }
                // 【P0 安全·键注入的唯一收口】键名 k 完全来自外部备份文件，**不能**直接信任。
                //   修复前这里只有「非空 + 目标机不存在」两道闸门，于是恶意备份
                //   塞一条 {"k":"pv_list"} 就能改写供应商列表、把 baseUrl 指向攻击者，
                //   此后用户所有聊天明文都会 POST 出去。
                //
                //   判据 = 白名单（[isAllowedPersonaKey]，与导出侧同一份 [personaKeysOf]）
                //         + 黑名单（[isDangerousKey]，纵深防御）。
                //   注意顺序：**先拒绝后写盘**，rejected 只用于日志计数，绝不 e.put 任何东西。
                if (!isAllowedPersonaKey(dollId, k)) {
                    rejected++
                    continue
                }
                // 【P0·本段最核心的判据】这个键要不要写 —— 见 [shouldWritePersonaKey]。
                //
                // 历史沿革（与 restoreChat 同一个模式，这里错了两次）：
                //   ① `p.contains(k)` —— 判「本机有没有这个键」。但启动种子
                //      `DollProfile.seedDefault()` 会在**首次启动**就给默认人偶写死
                //      `persona` + `talk_lines`（裸键，见 DollProfile.key 对 DEFAULT_ID 的规则）。
                //      于是换机用户的典型路径「新机装 App → 打开看一眼 → 导入备份」里，
                //      这两个键**恒已存在** ⇒ 用户自己写的性格与台词**一个字都写不进去**，
                //      而她说话用的是工厂 SYSTEM_PROMPT（性格完全不对）。
                //      更糟的是摘要还报「人设：1 只人偶补齐了设定」—— 在说谎。
                //   ② 现在：能区分「工厂写的」与「用户改过的」。
                //      工厂常量为确定值（PetPrefs.SYSTEM_PROMPT / PetService.LINES_TAP），
                //      所以直接**值比对**：当前值仍等于工厂值 → 用户没动过 → 允许备份覆盖。
                if (!shouldWritePersonaKey(p, dollId, k)) {
                    // 【说清楚】这个键本机已有**用户自己改过**的值，备份里的更新更旧，
                    //   所以保留本机的。计数是为了让摘要能如实说出来（见 PersonaStat）。
                    //
                    //   直接累到 stat（而不是循环内的局部变量）：这个计数是**跨人偶**的，
                    //   而每轮循环都要重新声明局部变量。更要紧的是——某只人偶**一个键都没写成**
                    //   时（wrote==0 → 下面 continue）它保留的键也必须计入，那正是用户最需要的
                    //   「我这只人偶一个字都没被改」这条信息。
                    stat.keptByUser++
                    continue
                }
                // 背景图路径必须指到目标机真正写好的那份文件；没有新图就写空串，
                // 让它回落到「没有背景」而不是一条指向别人机器目录的死路径。
                if (k == chatBgKey) {
                    val old = o.optString("v", "")
                    if (old.isNotEmpty()) {
                        e.putString(k, chatBgNew ?: "")
                        wrote++
                    }
                    continue
                }
                // putValue 自己做第二道防御（见该函数的判定），这里把它的返回值当作
                // 「真的写进去了」才算 wrote：被拒的条目不该被算成「补齐了设定」。
                if (putValue(e, o.optString("t", "s"), k, o)) {
                    wrote++
                }
            }
            if (wrote == 0) {
                continue
            }
            // 用 commit：恢复是一次性动作，用户随后很可能立刻杀掉进程；
            // apply 的异步写在这个窗口里丢掉的话，表现是「恢复完重启又没了」。
            e.commit()
            stat.dolls++
        }
        if (rejected > 0) {
            // 只记一行汇总，不逐键打日志：恶意备份里可能有上千条注入项，
            // 逐条打印会把日志刷满，反而盖掉真正要排查的那一行。
            Logs.w(TAG, "persona 段拒绝 " + rejected + " 个非法键（不在白名单或命中危险清单），已跳过")
        }
        return stat
    }

    /**
     * 按类型写一个偏好值；类型位不认识时按字符串兜底（比整条丢弃更接近用户预期）。
     *
     * 【P0 安全】这个函数**不是**任意键通道。调用前必须已由 [isAllowedPersonaKey]
     *   放行；这里再做一次独立防御（白名单 + 黑名单），因为「任意键写盘」这个能力
     *   一旦扩散到别的 restore 段，就是一个比本次漏洞更大的洞。
     *   命中危险键时**直接不写**并返回 false，调用方据此不计入 wrote。
     */
    private fun putValue(
        e: SharedPreferences.Editor,
        t: String,
        key: String,
        o: JSONObject
    ): Boolean {
        if (isDangerousKey(key)) {
            Logs.w(TAG, "拒绝写入危险键（纵深防御）：" + key)
            return false
        }
        when (t) {
            "b" -> e.putBoolean(key, o.optBoolean("v", false))
            "i" -> e.putInt(key, o.optInt("v", 0))
            "l" -> e.putLong(key, o.optLong("v", 0L))
            "f" -> e.putFloat(key, o.optDouble("v", 0.0).toFloat())
            else -> e.putString(key, o.optString("v", ""))
        }
        return true
    }

    /**
     * 动作库写回。
     *
     * 【两种情形】
     * · 目标机这只人偶**从没有过**动作库 → 整段照搬，然后改写帧路径；
     * · 目标机已有 → 按动作 id 合并：只追加本机没有的，本机已有的动作一个字都不改
     *   （用户可能已经重新调过帧、换过快慢），最后统一把帧路径改写成本机的新绝对路径。
     *
     * 【为什么必须改写帧路径】存档里的帧图是 base64、恢复后落在**目标机**的
     *   `files/doll/anim/<dollId>/<actionId>/` 下，绝对路径必然不同。
     *   不改写的话动作库里全是来源机的路径，播放器一张帧都找不到。
     *
     * @return 实际写回了动作库的人偶数
     */
    private fun restoreAnim(ctx: Context, section: JSONObject, frameMap: HashMap<String, String>): Int {
        if (section.length() == 0 && frameMap.isEmpty()) {
            return 0
        }
        val p = PetPrefs.get(ctx)
        var count = 0
        // 【P1·内置动作待重烘清单】见 [exportAnim] 的说明与 [rebakeBuiltinFrames]。
        //   收齐后**统一**重烘，而不是边写边烘：烘一帧要占主线程渲染，
        //   夹在偏好写盘中间会让恢复的总耗时更难预测，也不好统计失败数。
        val pending = ArrayList<Pair<String, String>>()
        val keys = section.keys()
        while (keys.hasNext()) {
            val dollId = keys.next()
            if (DollStore.byId(ctx, dollId) == null) {
                continue
            }
            val backupRaw = section.optString(dollId, "")
            if (backupRaw.isEmpty()) {
                continue
            }
            val key = ANIM_KEY_PREFIX + dollId
            val mineRaw = p.getString(key, "") ?: ""
            val merged = try {
                if (mineRaw.isEmpty()) {
                    parseArray(backupRaw)
                } else {
                    mergeAnim(parseArray(mineRaw), parseArray(backupRaw))
                }
            } catch (t: Throwable) {
                Logs.w(TAG, "动作库合并失败：" + dollId, t)
                continue
            }
            if (merged.length() == 0) {
                continue
            }
            // 【先改写再判要不要写盘，顺序不能反】rebindAnim 是**原地修改** merged：
            //   若先判「要不要写」再改写，那个判定看到的是改写前的数据，
            //   于是「本机已经有动作库、但帧路径确实需要改写」这一支会被判成「不必写盘」，
            //   结果动作库里留下一堆来源机的死路径 —— 播放器一张帧都找不到。
            val mineLen = parseArray(mineRaw).length()
            val mergedLen = merged.length()
            val rebound = rebindAnim(merged, frameMap)
            // 【P1·把内置动作的帧清空并登记重烘】
            //   为什么必须清空：来源机的 `frames` 是**绝对路径**，本机不存在；
            //   而 `DollAnimPage` 的判据是 `frames.isNotEmpty()` 就直接拿去播、**不会重烘**，
            //   于是死链会一直留在档案里，画面停成静态图。
            //   为什么不能只清空：[DollAnim.all] 按 id 判 `containsId` —— 动作条目已存在
            //   就**不会**再补 frames，清空后没有任何东西会把它填回来，
            //   结果从「看得见的死链」变成「看不见的空帧」（`PetView` 把空帧当合法无内容，
            //   `return false` 且不打日志）。所以必须显式登记，交给 [rebakeBuiltinFrames]。
            //
            // 【只对 builtin 生效】用户自建动作的帧是真图片、已由图片段打包进来，
            //   并由上面 rebindAnim 把旧绝对路径改成新绝对路径 —— 那些**不能清**。
            var cleared = false
            for (i in 0 until merged.length()) {
                val a = merged.optJSONObject(i) ?: continue
                if (!a.optBoolean("builtin", false)) {
                    continue
                }
                val actionId = a.optString("id", "")
                if (actionId.isEmpty()) {
                    continue
                }
                a.put("frames", JSONArray())
                a.put("asset_path", "")
                cleared = true
                pending.add(Pair(dollId, actionId))
            }
            // 只有在「本机本来就有动作库、且这次既没追加也没改写也没清空」时才跳过写盘。
            if (!rebound && !cleared && mergedLen == mineLen && mineRaw.isNotEmpty()) {
                continue
            }
            p.edit().putString(key, merged.toString()).commit()
            count++
        }
        // 【重烘放在所有偏好写完之后】此时动作库里已经是「内置动作空帧」的状态，
        //   重烘拿到的就是最终形态，不会被后面的写盘覆盖回去。
        val baked = rebakeBuiltinFrames(ctx, pending)
        lastAnimBakeStat = AnimBakeStat(pending.size, baked)
        return count
    }

    /**
     * 一次恢复里内置动作重烘的结果（供摘要如实报告）。
     *
     * 【为什么用静态字段而不是改 restoreAnim 的返回类型】[restoreAnim] 的返回值
     *   `Int` 是「写回了动作库的人偶数」，摘要里已经用它；再塞一个失败数会污染语义。
     *   而改 `restore()` 的整条调用链会与并行进行的 UI 改造撞车。
     *   这里沿用本类既有的 [lastSkippedImages] 同款做法。
     *
     * 【并发说明】恢复是用户在设置页触发的一次性串行动作（`SettingsScreen` 在
     *   单条 `Thread{}` 里调），不存在两个恢复同时写这个字段的真实路径；
     *   与 [lastSkippedImages] 的风险等级相同，不为它引入锁。
     */
    private class AnimBakeStat(val asked: Int, val ok: Int)

    @Volatile
    private var lastAnimBakeStat: AnimBakeStat = AnimBakeStat(0, 0)

    /**
     * 上一次恢复里「内置动作重烘」的统计；[asked] 为 0 表示本次没有需要重烘的动作。
     *
     * 供 [restore] 拼摘要用：**必须如实报数** —— 否则等于把「看得见的坏」
     * （死链）换成「看不见的坏」（空帧静默不播）。
     */
    private fun animBakeStat(): AnimBakeStat {
        return lastAnimBakeStat
    }

    /**
     * 按本机屏幕重新烘一批内置动作的帧图，并把结果写回动作元数据。
     *
     * ## 为什么必须真的烘，不能只清空
     * 见 [restoreAnim] 里那段说明：`DollAnim.all()` 只补**缺失的条目**，不补
     * **已存在条目的空帧**。所以清空之后必须有人主动填。
     *
     * ## 为什么不用 `DefaultModelWarmup`
     * 那个预热器有三个硬限定，**覆盖不到本场景**：
     *   · `DollStore.DEFAULT_ID` —— 只服务默认人偶，用户的多只人偶完全不管；
     *   · 只有 `SLOT_ALWAYS/DRAG/EDGE_IN/EDGE` **4 个固定位**，其余 15 个内置动作不管；
     *   · 还要 `hasWindowFocus()`，且失败只重试 3 次（见 `DefaultModelWarmup.runNext`）。
     * 依赖它 = 「默认人偶的 4 个动作能自愈，其余永久空帧」。
     *
     * ## 线程
     * [AnimBaker.ensure] 的 KDoc 明写「可从任意线程调用」：它内部用 `onMain{}`
     * 把渲染切回主线程，编码与落盘留在调用方线程。恢复本来就在后台线程跑，
     * 所以这里**不要**再自己开线程。
     *
     * ## 比例口径（这里最容易错）
     * 必须用**这只人偶自己的** `DollProfile.scale(dollId)`，不能全局 `PetPrefs.petScale`：
     * `AnimBaker.bakeStamp` 把比例编进了烘帧版本号（见其注释），用错比例会导致
     * ①帧的尺寸与桌宠实际显示对不上（糊或被裁）；②版本戳对不上，之后每次进动作页都重烘。
     *
     * @param pending (dollId, actionId) 清单
     * @return 真正烘成功的动作数
     */
    private fun rebakeBuiltinFrames(ctx: Context, pending: List<Pair<String, String>>): Int {
        if (pending.isEmpty()) {
            return 0
        }
        var ok = 0
        for ((dollId, actionId) in pending) {
            try {
                val scale = DollProfile.scale(ctx, dollId) / 100.0f
                val out = AnimBaker.ensure(ctx, dollId, actionId, scale)
                if (out.isEmpty()) {
                    continue
                }
                val cur = DollAnim.byId(ctx, dollId, actionId)
                // 【只填空的】防止把并发已经烘好的（或用户刚手工换过帧的）覆盖掉。
                if (cur != null && cur.frames.isEmpty()) {
                    DollAnim.update(ctx, dollId, cur.copy(frames = out))
                    ok++
                }
            } catch (t: Throwable) {
                // 单个动作烘失败不该让整次恢复中断 —— 但必须计数并进摘要。
                Logs.w(TAG, "内置动作重烘失败：" + dollId + "/" + actionId, t)
            }
        }
        return ok
    }

    /** 按动作 id 合并：以本机为准，只追加本机没有的条目。 */
    private fun mergeAnim(mine: JSONArray, backup: JSONArray): JSONArray {
        val have = HashSet<String>()
        for (i in 0 until mine.length()) {
            val o = mine.optJSONObject(i) ?: continue
            have.add(o.optString("id", ""))
        }
        for (i in 0 until backup.length()) {
            val o = backup.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty() || have.contains(id)) {
                continue
            }
            mine.put(o)
            have.add(id)
        }
        return mine
    }

    /**
     * 把动作 JSON 里的帧路径 / 入口资源路径改写成本机路径。
     *
     * @return 是否真的改过（调用方据此决定要不要写盘）
     */
    private fun rebindAnim(arr: JSONArray, frameMap: HashMap<String, String>): Boolean {
        if (frameMap.isEmpty()) {
            return false
        }
        var changed = false
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val frames = a.optJSONArray("frames")
            if (frames != null) {
                for (j in 0 until frames.length()) {
                    val old = frames.optString(j, "")
                    val hit = frameMap[old]
                    if (hit != null && hit != old) {
                        frames.put(j, hit)
                        changed = true
                    }
                }
            }
            val asset = a.optString("asset_path", "")
            val hit = frameMap[asset]
            if (hit != null && hit != asset) {
                a.put("asset_path", hit)
                changed = true
            }
        }
        return changed
    }

    /**
     * 记忆连接写回。
     *
     * @param onSkipped 因超出 [Mem.MAX_LINKS] 而未写入的条数（回调式上报，见调用点）。
     *        用回调而不是扩返回值：返回值 `Int` 已是「写入了多少条」，语义不能混。
     * @return 实际写入的连接数
     */
    private fun restoreMemLinks(ctx: Context, section: JSONObject, onSkipped: (Int) -> Unit): Int {
        if (section.length() == 0) {
            return 0
        }
        var n = 0
        var skipped = 0
        val keys = section.keys()
        while (keys.hasNext()) {
            val dollId = keys.next()
            if (DollStore.byId(ctx, dollId) == null) {
                continue
            }
            val arr = section.optJSONArray(dollId) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // 【写入前查上限】同记忆条目：`Mem.addLink` 超 MAX_LINKS 会砍最旧的并返回 true，
                //   靠它是静默丢失。先查再写，用户才知道有几条连接没恢复。
                //   每次循环重查（不用缓存），理由同记忆段。
                if (Mem.links(ctx, dollId).length() >= Mem.MAX_LINKS) {
                    skipped++
                    continue
                }
                // addLink 自带「同源同目标同类型去重」，重复恢复不会长出多条边。
                if (Mem.addLink(ctx, dollId, MemLink.fromJson(o))) {
                    n++
                }
            }
        }
        if (skipped > 0) {
            onSkipped(skipped)
        }
        return n
    }

    private fun restoreMemProfiles(ctx: Context, section: JSONObject): Int {
        if (section.length() == 0) {
            return 0
        }
        var n = 0
        val keys = section.keys()
        while (keys.hasNext()) {
            val dollId = keys.next()
            if (DollStore.byId(ctx, dollId) == null) {
                continue
            }
            val md = section.optString(dollId, "")
            if (md.isEmpty()) {
                continue
            }
            // 画像是一份会持续被 AI 重写的文档：目标机已经有了就不动，避免把新的盖成旧的。
            if (Mem.profile(ctx, dollId).isNotEmpty()) {
                continue
            }
            Mem.setProfile(ctx, dollId, md)
            n++
        }
        return n
    }

    /**
     * 人设恢复的统计。
     *
     * 【为什么需要 keptByUser，而不只是「写了几只」】`restorePersona` 的口径是
     * 「键不存在 / 值是工厂种子 → 写；否则保留本机的」。于是用户改过性格时，
     * 备份里的那份会被**正确地**跳过 —— 但这在摘要里**完全不可见**：
     * `wrote==0` 就 `continue`，该人偶不计入任何数字，用户只看到一句「恢复完成」。
     *
     * 用户因此无从判断状态：是「没导进来」还是「导进来了但被我现在的人设挡住了」？
     * 这两种情况的处理方式完全不同（前者要重试/找回备份，后者什么都不用做）。
     * 所以必须把「保留了几项」如实说出来 —— 判对与说清楚是同一件事的两半。
     */
    private class PersonaStat {
        /** 实际写入了设定的**人偶数**。 */
        var dolls = 0

        /** 因为「本机已有用户自己改过的值」而被保留的**键数**（跨人偶累计）。 */
        var keptByUser = 0
    }

    /** 聊天恢复的统计。 */
    private class ChatStat {
        var sessions = 0
        var messages = 0
    }

    /**
     * 聊天记录写回。
     *
     * 【语义】会话清单只**追加**备份里有、本机没有的条目（清单本身由 ChatSessions 维护，
     *   它是一个 JSON 数组，这里按它的既有形状原样追加，不做任何字段重写）；
     *   每条历史只在目标机**完全没有这条 id 的记录**时才写入。
     *   「当前会话」与「当前池」这两个指针属于本机使用习惯，只在目标机为空时才补 ——
     *   否则用户一恢复，聊天页就被强行切到别人机器上最后打开的那条会话。
     */
    private fun restoreChat(ctx: Context, section: JSONObject, singles: HashMap<String, String>): ChatStat {
        val out = ChatStat()
        if (section.length() == 0) {
            return out
        }
        val p = PetPrefs.get(ctx)
        val backupIndex = parseArray(section.optString("index", "[]"))
        val myIndex = parseArray(PetPrefs.convIndex(ctx))
        val have = HashSet<String>()
        for (i in 0 until myIndex.length()) {
            val o = myIndex.optJSONObject(i) ?: continue
            have.add(o.optString("id", ""))
        }
        // 【P0 安全·准入名单】只认「备份自己的会话清单里出现过」的 id。
        //
        // 为什么必须卡这一层：`items` / `prev` / `meta` 的**键名**来自备份文件，
        // 而下面每一条都会拼进 SharedPreferences 的键（`conv_<id>`、`conv_read_<id>` …）。
        // 不设准入时，外部文件里塞 `"<任意串>": {...}` 就能让恢复流程替它写一串键。
        // 用「清单 ∋ id」这种**精确成员判定**而不是前缀/形态猜测 —— 猜测型校验
        // 很容易被带下划线的构造绕过（同 personaKeysOf 的取舍）。
        //
        // 对正常备份零影响：[exportChat] 的 items/prev/meta **只遍历 conv_index 里的 id**，
        // 所以真实备份必然满足这条准入。
        val allowed = HashSet<String>()
        var indexChanged = false
        for (i in 0 until backupIndex.length()) {
            val o = backupIndex.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) {
                continue
            }
            allowed.add(id)
            if (!have.add(id)) {
                continue
            }
            myIndex.put(o)
            indexChanged = true
        }
        if (indexChanged) {
            PetPrefs.setConvIndex(ctx, myIndex.toString())
        }

        val items = section.optJSONObject("items")
        if (items != null) {
            val keys = items.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                if (!allowed.contains(id)) {
                    continue
                }
                val raw = items.optString(id, "")
                // 【P1·判据必须**两侧都判**，只判一侧就是在毁用户数据】
                //
                // 历史沿革（三个版本，前两版都错，且错的方向相反）：
                //   ① 最早判 `p.contains("conv_" + id)` —— 判的是「本机有没有这个键」。
                //      但那个键**几乎必然已存在**：`ChatSessions.addConv()` 每建一条会话
                //      就无条件 `setConvHistory(ctx, id, "[]")`（写空数组），
                //      `syncWithDolls()` 启动时又给每只人偶补建 `d_<dollId>`。
                //      于是「键存在」恒为真 ⇒ **聊天一条都回不来**（功能失效）。
                //   ② 修 ① 时改成了 `if (hasMessages(raw))` —— 但这个 raw 是**备份里**的值，
                //      等于「备份里有消息就无条件覆盖本机」。而本机可能已有 50 条更近的对话，
                //      导入一份旧备份就把它们**整体替换**掉（数据被毁）。
                //      上一版的注释写着「真有消息就保留本机的」，实现却没读本机 ——
                //      注释与实现相反，这正是缺陷能藏下来的原因。
                //   ③ 现在（本版）：先读本机现值，两侧都判 ——
                //      **本机没有内容** 且 **备份有内容** 才写。两个条件缺一不可：
                //        · 缺「本机没有」→ 覆盖用户现有聊天（②的错）；
                //        · 缺「备份有」  → 用空数组把本机预览清空（①的错）。
                //
                // 「有没有内容」用 [hasMessages] 判，而不是 `isNotEmpty()`：
                // 键不存在(null) / `"[]"` / `"[ ]"` / 解析不出消息 一律算「没有」。
                val existing = p.getString("conv_" + id, null)
                if (!hasMessages(existing) && hasMessages(raw)) {
                    PetPrefs.setConvHistory(ctx, id, raw)
                    // 【计数只在真写入时累加】被跳过的会话不能算进「新增 N 个会话」，
                    // 否则摘要会撒谎（用户以为恢复了，实际一条没动）。
                    out.sessions++
                    out.messages += countMessages(raw)
                }
            }
        }
        val prev = section.optJSONObject("prev")
        if (prev != null) {
            val keys = prev.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                if (!allowed.contains(id)) {
                    continue
                }
                val raw = prev.optString(id, "")
                // 【P1·与 items 同一口径：两侧都判】`conv_<id>_prev` 存的是「更早的历史」，
                //   同样会被无条件写覆盖。这里原本是 `if (!hasMessages(raw)) continue`
                //   —— 只判备份侧就写，同样会顶掉本机已有的更早历史。
                val existingPrev = p.getString("conv_" + id + "_prev", null)
                if (!hasMessages(existingPrev) && hasMessages(raw)) {
                    PetPrefs.setConvPrev(ctx, id, raw)
                }
            }
        }
        // 展示层偏好：只补空的。
        val meta = section.optJSONObject("meta")
        if (meta != null) {
            val keys = meta.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                if (!allowed.contains(id)) {
                    continue
                }
                val m = meta.optJSONObject(id) ?: continue
                val curDoll = PetPrefs.convDoll(ctx, id)
                val boundDoll = m.optString("doll", "")
                if (curDoll.isEmpty() && boundDoll.isNotEmpty()) {
                    PetPrefs.setConvDoll(ctx, id, boundDoll)
                }
                val curAvatar = PetPrefs.convAvatar(ctx, id)
                val avatarFile = singles["conv:" + id + "|avatar"]
                if (curAvatar.isEmpty() && !avatarFile.isNullOrEmpty()) {
                    PetPrefs.setConvAvatar(ctx, id, avatarFile)
                }
                if (!p.contains("conv_read_" + id)) {
                    PetPrefs.setConvRead(ctx, id, m.optInt("read", 0))
                }
                if (!p.contains("conv_hidden_" + id) && m.optBoolean("hidden", false)) {
                    PetPrefs.setConvHidden(ctx, id, true)
                }
                if (!p.contains("conv_cleared_" + id) && m.optBoolean("cleared", false)) {
                    PetPrefs.setConvCleared(ctx, id, true)
                }
            }
        }
        // 两个指针：只在本机为空时补，避免强行切换用户的当前会话。
        val cur = section.optString("current", "")
        if (cur.isNotEmpty() && PetPrefs.convCurrent(ctx).isEmpty()) {
            PetPrefs.setConvCurrent(ctx, cur)
        }
        val curPet = section.optString("currentPet", "")
        if (curPet.isNotEmpty() && PetPrefs.convCurrentPet(ctx).isEmpty()) {
            PetPrefs.setConvCurrentPet(ctx, curPet)
        }
        val pool = section.optString("pool", "")
        if (pool.isNotEmpty() && PetPrefs.convPool(ctx).isEmpty()) {
            PetPrefs.setConvPool(ctx, pool)
        }
        return out
    }

    /**
     * 这段历史里**是否真的有消息**（`null` / `"[]"` / 空白一律算「没有」）。
     *
     * 【为什么不能用 `raw.isNotEmpty()` 代替】`addConv()` 给新会话写的占位值就是
     *   非空的字符串 `"[]"`；用 isNotEmpty 判断会把它当成「备份里有内容」而写回，
     *   等于把本机刚建的会话又覆盖成空 —— 既没恢复聊天，还可能清掉预览。
     *
     * 【为什么解析而不是直接比 `"[]"`】备份可能经过手工编辑或别的工具转发，
     *   出现 `"[ ]"`、`"[\n]"` 这类空白变体时仍然要判成「没有」。
     *   解析失败也按「没有」算：一个坏掉的历史值不值得覆盖本机记录。
     */
    private fun hasMessages(raw: String?): Boolean {
        if (raw == null) {
            return false
        }
        if (raw.isBlank() || raw.trim() == "[]") {
            return false
        }
        return try {
            parseArray(raw).length() > 0
        } catch (t: Throwable) {
            false
        }
    }

    /** 一条会话里的消息条数（用于摘要；结构不认识就按 0 算，绝不因此让恢复失败）。 */
    private fun countMessages(raw: String): Int {
        return try {
            parseArray(raw).length()
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * 「我」的资料写回：昵称 / 签名只在目标机还是默认空值时补；头像只在没设过时补。
     *
     * @return 补了几项（用于摘要）
     */
    private fun restoreMyProfile(ctx: Context, section: JSONObject, singles: HashMap<String, String>): Int {
        var n = 0
        if (section.length() == 0) {
            return 0
        }
        val name = section.optString("name", "")
        if (name.isNotEmpty() && MyProfile.name(ctx) == MyProfile.DEFAULT_NAME) {
            MyProfile.setName(ctx, name)
            n++
        }
        val bio = section.optString("bio", "")
        if (bio.isNotEmpty() && MyProfile.bio(ctx).isEmpty()) {
            MyProfile.setBio(ctx, bio)
            n++
        }
        val avatar = singles["myprofile|avatar"]
        if (!avatar.isNullOrEmpty()) {
            if (MyProfile.avatarPath(ctx).isEmpty()) {
                // commitAvatar 会写指针并清掉旧图（这里目标机本来就没有旧图）。
                if (MyProfile.commitAvatar(ctx, avatar)) {
                    n++
                }
            }
        } else if (MyProfile.avatarPath(ctx).isEmpty()) {
            // 没有图片段（v1 备份 / 图片没打进来）时，至少把原路径接上（仅当它在本机存在）。
            val raw = section.optString("avatar", "")
            if (raw.isNotEmpty() && ImageStore.exists(ctx, raw)) {
                if (MyProfile.commitAvatar(ctx, raw)) {
                    n++
                }
            }
        }
        return n
    }

    /**
     * 全局开关与外观写回：只补目标机**没有这个键**的项。
     *
     * 【为什么用「有没有这个键」而不是「值等不等于默认值」】
     *   用户可能真的把某项设成了默认值（例如刻意关掉毛玻璃）。按值判断会把它当成
     *   「没设过」而覆盖掉；按键是否存在判断才真正对应「这台机器表过态没有」。
     */
    private fun restoreSettings(ctx: Context, s: JSONObject): Int {
        if (s.length() == 0) {
            return 0
        }
        val p = PetPrefs.get(ctx)
        var n = 0
        if (s.has("theme") && !p.contains("theme_mode")) {
            PetPrefs.setThemeMode(ctx, s.optInt("theme", 0))
            n++
        }
        if (s.has("themeMonet") && !p.contains("theme_monet")) {
            PetPrefs.setThemeMonet(ctx, s.optBoolean("themeMonet", false))
            n++
        }
        if (s.has("memAuto") && !p.contains("mem2_auto")) {
            PetPrefs.setMem2Auto(ctx, s.optBoolean("memAuto", true))
            n++
        }
        if (s.has("memInterval") && !p.contains("mem2_interval_idx")) {
            // 存的是「分钟」，写入口吃的是「档位下标」：这里换回下标，避免越界。
            val minutes = s.optInt("memInterval", 0)
            PetPrefs.setMem2IntervalIndex(ctx, indexOfInterval(minutes))
            n++
        }
        if (s.has("memMode") && !p.contains("mem2_mode")) {
            PetPrefs.setMem2Mode(ctx, s.optInt("memMode", 0))
            n++
        }
        if (s.has("memSearch") && !p.contains("mem2_search")) {
            PetPrefs.setMem2Search(ctx, s.optBoolean("memSearch", false))
            n++
        }
        // 【v0.2.8·修 P1·两个被启动迁移「预置」的键】
        //   普遍判据是 `!p.contains(键)`（目标机没设过才补）。绝大多数设置项用它是对的。
        //   但有两个键在**任何**机器上於 `PetPrefs.get()` 首次调用时就已被迁移链写死：
        //     · `web_search`       ← forceMemSwitchesOn() 无条件 putBoolean(true)
        //     · `scrim_strength`   ← migrateScrimLinear() 无条件写一个按当前图算出的值
        //   于是这两个键的 contains 恒为真 ⇒ 判据退化为恒假 ⇒ 换机恢复后这两项
        //   **永远不会被带过来**，而摘要里也不会出现它们（没计入 n），
        //   用户以为还原了，实际是旧机的值被新机的迁移默认值顶替。
        //   修法：对这两项改「值比对」——只要当前值仍等于本机迁移会写入的默认值，
        //   就视为「用户没动过」，用备份里的值覆盖。用户真改过则保留本机选择。
        if (s.has("webSearch") && webSearchUntouched(ctx)) {
            PetPrefs.setWebSearchEnabled(ctx, s.optBoolean("webSearch", false))
            n++
        }
        if (s.has("think") && !p.contains("think_level")) {
            PetPrefs.setThinkLevel(ctx, s.optInt("think", 1))
            n++
        }
        if (s.has("bgOn") && !p.contains("bg_enabled")) {
            PetPrefs.setBgEnabled(ctx, s.optBoolean("bgOn", false))
            n++
        }
        if (s.has("bgMode") && !p.contains("bg_mode")) {
            PetPrefs.setBgMode(ctx, s.optInt("bgMode", 0))
            n++
        }
        if (s.has("bgAlpha") && !p.contains("chat_bg_alpha")) {
            PetPrefs.setChatBgAlpha(ctx, s.optInt("bgAlpha", 60))
            n++
        }
        if (s.has("bgBlur") && !p.contains("blur_radius")) {
            PetPrefs.setBlurRadius(ctx, s.optInt("bgBlur", 0))
            n++
        }
        // 【v0.2.8·修 P1】同 webSearch：scrim_strength 被 migrateScrimLinear() 在启动时写死。
        //
        // 【判据的演进，三版都试过，只有这版是对的】
        //   ① `!p.contains("scrim_strength")`：迁移已把键写死 ⇒ 判据恒假 ⇒ 永不恢复。
        //   ② 「当前值 == 用迁移公式重算的值」：公式里**含着当前值**，
        //      退化成 `v == clamp(min(100-alpha, v), 0, 40)` 这个不动点条件 ——
        //      alpha=60 时用户主动设在 0..40 的任何值都会被判成"没动过"并被覆盖。
        //      而 0 正是「原图直出」这个合法选择。**覆盖用户的选择比不恢复更糟**，
        //      所以这一版被否掉（由 archive-verify 的静态分析发现）。
        //   ③ 现方案：迁移时会另存一份 `scrim_migrated_val` 快照（见 PetPrefs 迁移处）。
        //      判据换成「当前值 == 迁移当年真实写入值」——
        //      不碰当前值参与计算，因此不受用户后续改动干扰。
        //      用户只要动过滑块，当前值就与快照不等，这一项就保持本机不动。
        if (s.has("scrim") && scrimUntouched(ctx)) {
            PetPrefs.setScrimStrength(ctx, s.optInt("scrim", 100))
            n++
        }
        if (s.has("frost") && !p.contains("frost_strength")) {
            PetPrefs.setFrostStrength(ctx, s.optInt("frost", 0))
            n++
        }
        if (s.has("groups") && !p.contains("doll_groups")) {
            val g = s.optString("groups", "")
            if (g.isNotEmpty() && g != "[]") {
                PetPrefs.setDollGroups(ctx, g)
                n++
            }
        }
        return n
    }

    /** 分钟 → [PetPrefs.MEM2_INTERVALS] 的下标；找不到就落到「最接近且不超过」的档。 */
    /**
     * 判断本机的遮罩强度是否**仍是迁移当年写进去的那个值**（即用户从未动过）。
     *
     * 【为什么读快照而不是重算公式】重算会把当前值卷进计算，退化成不动点条件 ——
     *   用户设在 0..40 的任何值都会被误判成「没动过」。快照值是迁移当时真实写入的常量，
     *   与当前值比较是一次纯粹的相等判断，不存在循环。
     *
     * 【缺失快照时怎么办】老机器上迁移可能在加这个快照键之前就跑完了，
     *   此时 `scrim_migrated_val` 不存在 —— 返回 false（不覆盖）。
     *   宁可这一项不还原，也不能赌用户没动过他自己的遮罩。
     *
     * @return true = 用户没动过，可以安全用备份值覆盖
     */
    private fun scrimUntouched(ctx: Context): Boolean {
        return try {
            val p = PetPrefs.get(ctx)
            if (!p.contains("scrim_migrated_val")) {
                false
            } else {
                p.getInt("scrim_strength", -1) == p.getInt("scrim_migrated_val", -2)
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun indexOfInterval(minutes: Int): Int {
        val arr = PetPrefs.MEM2_INTERVALS
        var best = 0
        for (i in arr.indices) {
            if (arr[i] <= minutes) {
                best = i
            }
        }
        return best
    }

    /* ============================ 小工具 ============================ */

    /** 宽松解析：坏 JSON 一律回落成空数组（恢复流程不能因为一段脏数据整单失败）。 */
    private fun parseArray(raw: String?): JSONArray {
        if (raw.isNullOrEmpty()) {
            return JSONArray()
        }
        return try {
            JSONArray(raw)
        } catch (t: Throwable) {
            JSONArray()
        }
    }
}