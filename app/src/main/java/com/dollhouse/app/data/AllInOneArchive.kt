package com.dollhouse.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.dollhouse.app.ai.AiModel
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.core.Logs
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
            val keys = ArrayList<String>()
            for (b in PERSONA_BASES) {
                keys.add(DollProfile.key(dollId, b))
            }
            for (o in BOUND_ORIENTS) {
                for (s in BOUND_SIDES) {
                    keys.add(DollProfile.key(dollId, "bounds_" + s + "_" + o))
                }
            }
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

    /** 动作库元数据（原样搬运；帧图本体在图片段）。 */
    private fun exportAnim(ctx: Context): JSONObject {
        val out = JSONObject()
        val p = PetPrefs.get(ctx)
        for (doll in DollStore.all(ctx)) {
            val raw = p.getString(ANIM_KEY_PREFIX + doll.id, "") ?: ""
            if (raw.isNotEmpty()) {
                out.put(doll.id, raw)
            }
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
    }

    /**
     * 恢复：把信封写回本机。
     *
     * 【冲突口径】「只新增 / 只补齐」：目标机已有的同名供应商、已有的人偶字段、
     *   已存在的会话历史与人设键一律不动。这样用户在任何时候点恢复，
     *   最坏情况都是「没导进来」，而不是「把现在的东西覆盖掉」。
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

        // ---- 供应商 ----
        var providersAdded = 0
        var apiKeyNeeded = 0
        val existing = ProviderStore.providers(ctx)
        val byName = HashMap<String, Provider>()
        for (p in existing) {
            byName[p.name] = p
        }
        val pvArr = BackupEnvelope.sectionArray(root, SEC_PROVIDERS)
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

        // ---- 模型 ----
        var modelsAdded = 0
        val modelsArr = BackupEnvelope.sectionArray(root, SEC_MODELS)
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

        // ---- 图片（必须早于人偶：新人偶要直接指向写好的文件） ----
        val restored = restoreImages(ctx, BackupEnvelope.sectionArray(root, SEC_IMAGES))

        // ---- 人偶 ----
        var dollsAdded = 0
        var dollsFilled = 0
        var activeCandidate = ""
        val dollsArr = BackupEnvelope.sectionArray(root, SEC_DOLLS)
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

        // ---- 人设 ----
        // 【必须在 restoreImages 之后】人设里含人偶背景图的路径，要换成目标机的新路径。
        val personaDolls = restorePersona(
            ctx,
            BackupEnvelope.sectionObject(root, SEC_PERSONA),
            restored.singles
        )

        // ---- 动作库（先落元数据，再改写帧路径） ----
        val animCount = restoreAnim(ctx, BackupEnvelope.sectionObject(root, SEC_ANIM), restored.frames)

        // ---- 记忆 ----
        var memsAdded = 0
        val memObj = BackupEnvelope.sectionObject(root, SEC_MEMORIES)
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
                if (Mem.add(ctx, dollId, m)) {
                    memsAdded++
                }
            }
        }
        val linksAdded = restoreMemLinks(ctx, BackupEnvelope.sectionObject(root, SEC_MEMLINKS))
        val profileFilled = restoreMemProfiles(ctx, BackupEnvelope.sectionObject(root, SEC_MEMPROFILE))

        // ---- 聊天 ----
        val chatStat = restoreChat(ctx, BackupEnvelope.sectionObject(root, SEC_CHAT), restored.singles)

        // ---- 我 ----
        val meFilled = restoreMyProfile(ctx, BackupEnvelope.sectionObject(root, SEC_MYPROFILE), restored.singles)

        // ---- 开关与外观 ----
        val settingsFilled = restoreSettings(ctx, BackupEnvelope.sectionObject(root, SEC_SETTINGS))

        // ---- 摘要 ----
        val sb = StringBuilder("恢复完成。")
        sb.append("\n供应商 +").append(providersAdded).append(" 个，模型 +").append(modelsAdded).append(" 个")
        sb.append("\n人偶 +").append(dollsAdded).append(" 只")
        if (dollsFilled > 0) {
            sb.append("（补齐 ").append(dollsFilled).append(" 只的封面 / 形象 / 分组）")
        }
        if (personaDolls > 0) {
            sb.append("\n人设：").append(personaDolls).append(" 只人偶补齐了设定")
        }
        if (animCount > 0) {
            sb.append("\n动作库：").append(animCount).append(" 只人偶的动作已写回")
        }
        if (chatStat.sessions > 0) {
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
        sb.append("\n内置动作会在新机上按当前屏幕重新生成（这是正常的，不是丢动作）。")
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
                    Logs.w(TAG, "写回图片失败：" + rel, t)
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
     * 人设写回：只写目标机**从来没有过**的键。
     *
     * 【为什么不覆盖】人设是用户最主观的部分（性格、台词、手势映射）。
     *   恢复一份三个月前的备份就把她现在的性格盖回去，用户会当成「恢复把我改的东西弄没了」。
     *   新建的人偶本来就没有任何键，于是会完整继承 — 这正是想要的。
     *
     * 【为什么要吃 singles 映射】人设段里有一个**路径型**的键：`chatbg`
     *   （人偶独立的聊天背景图）。它存的是绝对路径，恢复后图落在了目标机的
     *   私有目录里、路径已经变了。不换成新路径的话，这只人偶的背景在恢复后
     *   永远显示不出来 —— 用户会以为「背景没备份过来」。
     *   其余键都是标量（整数 / 布尔 / 文本），原样写回即可。
     *
     * @param singles 图片段的「归属|字段 → 目标机绝对路径」映射（见 [restoreImages]）
     * @return 实际补了人设的人偶数
     */
    private fun restorePersona(ctx: Context, section: JSONObject, singles: HashMap<String, String>): Int {
        if (section.length() == 0) {
            return 0
        }
        val p = PetPrefs.get(ctx)
        var dolls = 0
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
                if (k.isEmpty() || p.contains(k)) {
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
                putValue(e, o.optString("t", "s"), k, o)
                wrote++
            }
            if (wrote == 0) {
                continue
            }
            // 用 commit：恢复是一次性动作，用户随后很可能立刻杀掉进程；
            // apply 的异步写在这个窗口里丢掉的话，表现是「恢复完重启又没了」。
            e.commit()
            dolls++
        }
        return dolls
    }

    /** 按类型写一个偏好值；类型位不认识时按字符串兜底（比整条丢弃更接近用户预期）。 */
    private fun putValue(e: SharedPreferences.Editor, t: String, key: String, o: JSONObject) {
        when (t) {
            "b" -> e.putBoolean(key, o.optBoolean("v", false))
            "i" -> e.putInt(key, o.optInt("v", 0))
            "l" -> e.putLong(key, o.optLong("v", 0L))
            "f" -> e.putFloat(key, o.optDouble("v", 0.0).toFloat())
            else -> e.putString(key, o.optString("v", ""))
        }
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
            // 只有在「本机本来就有动作库、且这次既没追加也没改写」时才可以跳过写盘。
            if (!rebound && mergedLen == mineLen && mineRaw.isNotEmpty()) {
                continue
            }
            p.edit().putString(key, merged.toString()).commit()
            count++
        }
        return count
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

    private fun restoreMemLinks(ctx: Context, section: JSONObject): Int {
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
            val arr = section.optJSONArray(dollId) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // addLink 自带「同源同目标同类型去重」，重复恢复不会长出多条边。
                if (Mem.addLink(ctx, dollId, MemLink.fromJson(o))) {
                    n++
                }
            }
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
        var indexChanged = false
        for (i in 0 until backupIndex.length()) {
            val o = backupIndex.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty() || !have.add(id)) {
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
                val raw = items.optString(id, "")
                if (raw.isEmpty() || p.contains("conv_" + id)) {
                    continue
                }
                PetPrefs.setConvHistory(ctx, id, raw)
                out.sessions++
                out.messages += countMessages(raw)
            }
        }
        val prev = section.optJSONObject("prev")
        if (prev != null) {
            val keys = prev.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val raw = prev.optString(id, "")
                if (raw.isEmpty() || p.contains("conv_" + id + "_prev")) {
                    continue
                }
                PetPrefs.setConvPrev(ctx, id, raw)
            }
        }
        // 展示层偏好：只补空的。
        val meta = section.optJSONObject("meta")
        if (meta != null) {
            val keys = meta.keys()
            while (keys.hasNext()) {
                val id = keys.next()
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