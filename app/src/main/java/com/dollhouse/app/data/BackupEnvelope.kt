package com.dollhouse.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】完整备份信封（App 私有格式，扩展名 `.dollhouse-backup`）。
 *
 * ## 与另外两种配置交换的分工
 * | 格式 | 用途 | 是否含对话 / 记忆 / 图片 |
 * |---|---|---|
 * | `dollhouse.providers` | 只搬供应商与模型，供 Aivora 互通 | 否 |
 * | AEX `.akcp` | 跨软件交换单/多条配置 | 否 |
 * | **`dollhouse.backup`（本类）** | **本机完整迁移 / 换机还原** | 是 |
 *
 * 【为什么必须另开格式】用户换手机时真正想带走的是：供应商、人偶设定、聊天记录、
 *   记忆库、外观。前三者都各存各的键；只导出供应商等于丢掉绝大部分内容，
 *   而用户并不知道「导出的其实只是配置」。
 *
 * ## 结构（v2）
 * ```
 * {
 *   "kind": "dollhouse.backup",
 *   "ver": 2,
 *   "exportedAt": 1699...,
 *   "appVersion": "0.2.6",
 *   "sections": {
 *     "providers": [...],           // 不含 apiKey
 *     "models": [...],
 *     "dolls": [...],
 *     "persona": { "<dollId>": [ {k,t,v}, ... ] },
 *     "chat": { "index", "current", "currentPet", "pool",
 *               "items": { "<convId>": "<历史 JSON>" },
 *               "prev":  { "<convId>": "<更早历史 JSON>" },
 *               "meta":  { "<convId>": {doll,avatar,read,hidden,cleared} } },
 *     "myprofile": { "name", "bio", "avatar" },
 *     "memories":    { "<dollId>": [...] },
 *     "memlinks":    { "<dollId>": [...] },
 *     "memprofile":  { "<dollId>": "<Markdown 画像>" },
 *     "anim":        { "<dollId>": "<动作库 JSON>" },
 *     "images": [ {rel,owner,field,src,size,b64}, ... ],
 *     "settings": { ... }
 *   }
 * }
 * ```
 * ### v1 → v2 是「加段」不是「改段」
 * v1 的五个段（providers / models / dolls / memories / settings）在 v2 里**字段名与形状
 * 一个都没动**，新增段彼此独立。于是：
 * · 旧文件（`ver=1`）按 v2 代码读，缺的段一律 `optXxx` 回落成空 —— 不报错；
 * · 新文件（`ver=2`）若被旧版本 App 打开，会被旧版判成「更高版本」而拒绝，
 *   **这是有意的**：旧版恢复不了图片与人设，强行导入会让用户以为恢复成功了。
 *
 * ## 明确不装的东西
 * - **API Key**：`providers[].apiKey` 导出时已是 KeyVault 密文，换机后无法解密
 *   （设备密钥不可迁移），因此 v2 写入时直接剔除该字段，恢复时**保留目标机上的原值**。
 * - **内置动作的帧图**：那是本机按机型实时烘出来的位图，换机必须重烘才与屏幕匹配；
 *   塞进包只会换来一堆目标机用不上的旧帧。用户自建动作的帧是唯一副本，必须打包。
 *
 * 【本类只做信封的组装 / 校验 / 拆解】不读不写存储，便于单测覆盖版本与容错。
 */
object BackupEnvelope {

    const val KIND = "dollhouse.backup"
    const val EXTENSION = ".dollhouse-backup"

    /**
     * 当前格式版本；结构断层才升，加字段不升。
     *
     * 【v2 为什么算结构断层】v1 恢复出来的是一只「没有人设、没有聊天、没有封面」的空壳，
     *   而 v1 的 `summary()` 又明文告诉用户「图片不包含在备份里」。用户换机后才知道
     *   备份本身就是残的 —— 这属于语义级变化（备份的定义从「配置」变成「全量存档」），
     *   必须在版本号上体现出来，否则新旧两端会互相以为对方是同一种东西。
     */
    const val VER = 2

    /**
     * 单文件上限。
     *
     * 【为什么从 64 MiB 提到 256 MiB】v2 把图片也装进来了，而且是 base64
     *   （体积 +33%）。实测口径：一只人偶的封面 + 形象 + 若干自建动作帧常在
     *   5~15 MiB 原始体积，base64 后 7~20 MiB；十只人偶就到了 v1 的上限。
     *   64 MiB 会让「人偶稍微多一点」的正常用户直接备份失败 —— 那正是本模块的失败态。
     *   256 MiB 足以覆盖「十几只人偶 + 大量自建动作」，同时仍是一个能挡住
     *   「把 2 GB 视频改名成 .dollhouse-backup」这类明显错误的天花板。
     */
    const val MAX_BYTES = 256 * 1024 * 1024

    /**
     * 图片段的总预算（解码后的原始字节数，不含 base64 膨胀）。
     *
     * 【为什么不按 [MAX_BYTES] 的百分比给】整包上限是「**读文件之前**的粗略闸门」，
     *   而真正决定能不能恢复的是**解析**这一步：本工程的 JSON 走 `org.json`，
     *   它没有流式解析，`JSONObject(String)` 要把整份文本读成一个 String。
     *   256 MiB 的 UTF-8 文本解码成 Java String（UTF-16）≈ 512 MB 常驻 ——
     *   中低端机当场 OOM，且用户看到的只是「恢复失败」。
     *
     *   所以这里刻意给一个**远低于文件上限**的组装预算：48 MiB 原始图片
     *   → base64 后约 64 MiB → 加上聊天与人设，整包通常落在 20~70 MiB，
     *   解析峰值约 140 MB，是当前主流机型能承受的量级。
     *   超出的图片会**跳过并记进导出提示**（见 [AllInOneArchive] 的 notes），
     *   不会让整次备份失败。
     */
    const val MAX_IMAGE_BYTES = 48 * 1024 * 1024

    /**
     * 这个文件是不是完整备份。
     *
     * 【为什么只读文件头，而不是整份转成 String】v1 时代这里写的是
     *   `String(bytes, UTF_8)` 再 `contains`。那时上限 64 MiB，勉强能扛；
     *   提到 256 MiB 之后这个写法会**凭空造出一份 512 MB 的 char 数组**
     *   （Kotlin/Java 的 String 是 UTF-16），中低端机当场 OOM ——
     *   而且 [parse] 随后还要再转一次，等于双倍峰值。
     *
     * 【为什么判定还能成立】本格式的 `"kind"` 一定出现在**第一个对象的开头几行**
     *   （见类头的结构图，它是 [build] put 的第一个键）。任何真实备份都满足；
     *   而一个「kind 出现在 200 MiB 之后」的文件本来就不是本格式 ——
     *   把它判成 NO 而不是判成 YES 更安全（宁可拒绝，也不要去解析它）。
     *
     * 【为什么留 8 KiB 的窗口】给「被别的工具包了一层 minified 外壳」这种情况
     *   留足余量 —— 正常备份在 200 字节内就能命中 kind。
     */
    @JvmStatic
    fun looksLike(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) {
            return false
        }
        val probeLen = Math.min(bytes.size, HEAD_PROBE_BYTES)
        val head = String(bytes, 0, probeLen, Charsets.UTF_8)
        val text = head.trimStart()
        return text.startsWith("{") && text.contains("\"" + KIND + "\"")
    }

    /**
     * 判定文件头要解码多少字节。
     *
     * 【8 KiB 的理由】最坏情况是 kind 前面被塞了一大段空白 / 别的键；
     *   8 KiB 已经是「正常备份的实际需要（约 30 字节）」的两百多倍，
     *   同时转出来的 String 只有 16 KB —— 与 256 MiB 的量级无关。
     */
    private const val HEAD_PROBE_BYTES = 8 * 1024

    /**
     * 组装信封。各 section 为空时也保留键（便于用户看出「这类数据是空的」而不是缺失）。
     *
     * 【v2 新增的参数为什么都带默认值】v1 时代的调用点（含单测）只传五个段；
     *   给新段加默认值能让那些调用点原样编译通过，不必一次改到位 ——
     *   而默认值 `null` 会被下面统一成空数组 / 空对象，语义正好等于「这个段是空的」。
     */
    @JvmStatic
    fun build(
        exportedAt: Long,
        appVersion: String,
        providers: JSONArray?,
        models: JSONArray?,
        dolls: JSONArray?,
        memories: JSONObject?,
        settings: JSONObject?,
        persona: JSONObject? = null,
        chat: JSONObject? = null,
        myProfile: JSONObject? = null,
        memLinks: JSONObject? = null,
        memProfiles: JSONObject? = null,
        anim: JSONObject? = null,
        images: JSONArray? = null
    ): JSONObject {
        val root = JSONObject()
        root.put("kind", KIND)
        root.put("ver", VER)
        root.put("exportedAt", exportedAt)
        root.put("appVersion", appVersion)
        val sections = JSONObject()
        // 【顺序】先 v1 的五个段（任何老读取端按名字取，顺序无关，但与 v1 文件对齐便于人工比对），
        //   再 v2 新增段。图片放最后：它是唯一可能有很多条目的段，
        //   人眼看 JSON 时不必先翻过几万行 base64 才找到人设。
        sections.put("providers", providers ?: JSONArray())
        sections.put("models", models ?: JSONArray())
        sections.put("dolls", dolls ?: JSONArray())
        sections.put("persona", persona ?: JSONObject())
        sections.put("chat", chat ?: JSONObject())
        sections.put("myprofile", myProfile ?: JSONObject())
        sections.put("memories", memories ?: JSONObject())
        sections.put("memlinks", memLinks ?: JSONObject())
        sections.put("memprofile", memProfiles ?: JSONObject())
        sections.put("anim", anim ?: JSONObject())
        sections.put("images", images ?: JSONArray())
        sections.put("settings", settings ?: JSONObject())
        root.put("sections", sections)
        return root
    }

    /** 解析结果；`reason` 为空表示可导入。 */
    class Parsed(
        val root: JSONObject?,
        val reason: Reason? = null,
        val warnings: List<String> = emptyList()
    ) {
        fun ok(): Boolean = root != null && reason == null

        fun message(): String {
            return when (reason) {
                null -> ""
                Reason.NOT_BACKUP -> "这不是 Dollhouse 的完整备份文件。"
                // 【为什么动态算】上限是会变的常量（v2 从 64 MiB 提到 256 MiB）。
                //   文案里写死数字，改常量时必然漏改，用户会看到「超过 64 MiB」
                //   而手上明明只有 100 MiB —— 一条自相矛盾的报错比没有报错更糟。
                //   显式写全限定名：Parsed 是嵌套类，不依赖「外层 object 成员可隐式访问」这条规则。
                Reason.TOO_LARGE -> "文件过大（超过 " + BackupEnvelope.maxLabel() + "），可能已损坏。"
                Reason.BAD_JSON -> "文件内容损坏，无法读取。"
                Reason.UNSUPPORTED_VERSION -> "备份来自更新版本，请先升级应用再恢复。"
                Reason.MISSING_SECTIONS -> "备份内容不完整，缺少数据区。"
            }
        }
    }

    enum class Reason { NOT_BACKUP, TOO_LARGE, BAD_JSON, UNSUPPORTED_VERSION, MISSING_SECTIONS }

    /** [MAX_BYTES] 的人类可读形式（MiB）。 */
    @JvmStatic
    fun maxLabel(): String {
        return (MAX_BYTES / (1024 * 1024)).toString() + " MiB"
    }

    /**
     * 解析并做严格校验。
     *
     * 【为什么版本要拒绝更高版本】本格式包含多类数据的结构；高版本可能改了字段语义，
     *   按旧规则强行导入会把「不认识的字段」静默丢掉 —— 用户以为恢复成功，
     *   实际丢了一半数据。宁可明确拒绝。
     *
     * 【为什么旧的 v1 必须照常放行】用户手里可能还留着改造前导出的文件，
     *   而「备份打不开」是所有体验里最坏的一种 —— 那份文件里仍有供应商、模型、
     *   人偶记录与记忆，能救回一半总比全丢好。这里只把「少哪些」如实塞进 warnings，
     *   由概览与恢复摘要告诉用户。
     */
    @JvmStatic
    fun parse(bytes: ByteArray?): Parsed {
        if (bytes == null || bytes.isEmpty()) {
            return Parsed(null, Reason.NOT_BACKUP)
        }
        if (bytes.size > MAX_BYTES) {
            return Parsed(null, Reason.TOO_LARGE)
        }
        if (!looksLike(bytes)) {
            return Parsed(null, Reason.NOT_BACKUP)
        }
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            return Parsed(null, Reason.BAD_JSON)
        }
        if (KIND != root.optString("kind", "")) {
            return Parsed(null, Reason.NOT_BACKUP)
        }
        val ver = root.optInt("ver", -1)
        if (ver < 1) {
            return Parsed(null, Reason.BAD_JSON)
        }
        if (ver > VER) {
            return Parsed(null, Reason.UNSUPPORTED_VERSION)
        }
        if (root.optJSONObject("sections") == null) {
            return Parsed(null, Reason.MISSING_SECTIONS)
        }
        val warnings = ArrayList<String>()
        if (ver < 2) {
            warnings.add("这是旧版备份（v" + ver + "），不含聊天记录 / 人设 / 图片。")
        }
        if (root.optString("appVersion", "").isEmpty()) {
            warnings.add("备份没有记录来源版本，不影响恢复。")
        }
        return Parsed(root, null, warnings)
    }

    /** 读某个数据区；缺失时返回空数组 / 空对象，调用方不必判空。 */
    @JvmStatic
    fun sectionArray(root: JSONObject?, name: String): JSONArray {
        val s = root?.optJSONObject("sections") ?: return JSONArray()
        return s.optJSONArray(name) ?: JSONArray()
    }

    @JvmStatic
    fun sectionObject(root: JSONObject?, name: String): JSONObject {
        val s = root?.optJSONObject("sections") ?: return JSONObject()
        return s.optJSONObject(name) ?: JSONObject()
    }

    /**
     * 恢复前的概览：让用户在覆盖前看清「会还原什么、什么不会」。
     *
     * 【为什么按段「有才说」】概览是给用户决策用的，不是格式说明书。
     *   把 12 个段名无条件列出来，用户会在十几行里找不到重点；
     *   只在真有内容时才出现那一行，恢复前扫一眼就知道这份备份「全不全」。
     *
     * @return 逐行说明（可直接显示）
     */
    @JvmStatic
    fun summary(root: JSONObject?): List<String> {
        val out = ArrayList<String>()
        val ver = root?.optInt("ver", 1) ?: 1
        val providers = sectionArray(root, "providers").length()
        val models = sectionArray(root, "models").length()
        val dolls = sectionArray(root, "dolls").length()
        val mem = sectionObject(root, "memories")
        var memCount = 0
        val keys = mem.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            memCount += mem.optJSONArray(k)?.length() ?: 0
        }
        out.add("供应商 " + providers + " 个，模型 " + models + " 个")
        out.add("人偶 " + dolls + " 只，记忆 " + memCount + " 条")

        // 人设：按「有几只人偶带设定」计数，比数键名更有意义（一只人偶有 25 个键）。
        val persona = sectionObject(root, "persona")
        if (persona.length() > 0) {
            out.add("人设与角色设定：" + persona.length() + " 只人偶")
        }
        val chat = sectionObject(root, "chat")
        val items = chat.optJSONObject("items")
        if (items != null && items.length() > 0) {
            var msgs = 0
            val ids = items.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                msgs += countMessages(items.optString(id, ""))
            }
            out.add("聊天记录 " + items.length() + " 个会话，共 " + msgs + " 条消息")
        }
        val me = sectionObject(root, "myprofile")
        if (me.length() > 0) {
            out.add("「我」的资料（昵称 / 签名 / 头像）")
        }
        val images = sectionArray(root, "images").length()
        if (images > 0) {
            out.add("图片 " + images + " 张（封面 / 形象 / 聊天背景 / 头像 / 自建动作帧）")
        } else {
            out.add("图片不包含在备份里（这份备份没有图片段）")
        }
        if (ver < 2) {
            out.add("这是旧版备份（v" + ver + "），不含聊天记录 / 人设 / 图片")
        }
        out.add("API Key 需要重新填写（备份不含可用密钥）")
        return out
    }

    /** 一段会话 JSON 里的消息条数；结构不认识按 0 算（概览不能因为脏数据整页失败）。 */
    private fun countMessages(raw: String): Int {
        if (raw.isEmpty()) {
            return 0
        }
        return try {
            JSONArray(raw).length()
        } catch (t: Throwable) {
            0
        }
    }
}