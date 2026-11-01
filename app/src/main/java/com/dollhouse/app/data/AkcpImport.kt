package com.dollhouse.app.data

import com.dollhouse.app.core.Logs
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 【职责】读取 Aivora 导出的 `.aivora` 文件（AKCP 1.0 开放协议），把它翻译成本 App 的供应商 / 模型。
 *
 * 【协议要点】格式与算法全部公开，不依赖 Aivora 的账号、服务器或 SDK：
 *   - 文件名必须是 `.aivora` 结尾；改名成 .json / .akcp 一律不认（规范强制要求）。
 *   - 容器 = 64 字节定长头 + AES-256-GCM 密文（尾部 16 字节认证标签），全文件上限 16 MiB。
 *   - 头里写明协议 / 容器 / 加密版本（均为 1）、KDF=Argon2id、Cipher=AES-256-GCM、
 *     Argon2id 参数（64 MiB / 3 轮 / 并行 1）、密文长度、16 字节 salt、12 字节 nonce。
 *   - 密钥 = Argon2id(52 位 Base32 凭据去掉连字符并转大写, salt, 上述参数) 派生 32 字节。
 *   - 解密 = AES/GCM/NoPadding，AAD 就是那完整的 64 字节头。
 *   - 明文 = UTF-8 JSON：`{schema:"akcp.config-set/1", configs:[{config_name, provider, api_key,
 *     endpoint, models?, default_model?, notes?, enabled?, extensions?}]}`。
 *
 * 【安全口径】认证失败（凭据错 / 文件被改 / 被截断）一律整体拒绝，绝不返回半截明文；
 *   所有长度、版本、KDF 参数在跑昂贵的 Argon2id 之前先校验完，避免被畸形文件拖死。
 *
 * 【坑】① Argon2id 单次要 64 MiB 内存，必须在后台线程调用，绝不能在主线程。
 *        ② 凭据里的连字符与大小写只在「输入归一化」时忽略，不能做部分匹配。
 *        ③ 规范要求「不要静默丢弃未知字段」——`extensions` 之外的未知键收进 [AkcpConfig.unknown]，
 *          导入前会提示用户「有内容存不下」，不做静默删除。
 */
object AkcpImport {

    private const val TAG = "Dollhouse"
    const val EXTENSION = ".aivora"
    const val MAX_FILE_SIZE = 16 * 1024 * 1024
    private const val HEADER_SIZE = 64
    private const val TAG_SIZE = 16
    private const val M_KIB = 65536
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1
    private const val CREDENTIAL_SYMBOLS = 52
    private val MAGIC = byteArrayOf(0x41, 0x4b, 0x43, 0x50, 0x0d, 0x0a, 0x1a, 0x0a)

    /** 一次 AKCP 配置条目（已解密、已校验必填字段）。 */
    class AkcpConfig {
        var configName: String = ""
        var provider: String = ""
        var apiKey: String = ""
        var endpoint: String = ""
        var models: MutableList<String> = ArrayList()
        var defaultModel: String = ""
        var notes: String = ""
        var enabled: Boolean = true
        var extensions: JSONObject? = null

        /** 规范要求尽量保留的未知键（不含上面已解析的那些）。 */
        var unknown: JSONObject = JSONObject()

        /** 是否带了本 App 存不下的额外信息（导入前需要提示用户）。 */
        fun hasExtra(): Boolean {
            return (extensions != null && extensions!!.length() > 0) || unknown.length() > 0
        }
    }

    /** 解密失败的原因分类；界面据此给出不同提示，不含任何敏感内容。 */
    enum class Reason {
        BAD_EXTENSION,
        BAD_CONTAINER,
        UNSUPPORTED_VERSION,
        BAD_CREDENTIAL,
        BAD_PAYLOAD,
        IO_ERROR
    }

    class Result(
        val configs: List<AkcpConfig>,
        /** 失败原因；成功时为 null。 */
        val reason: Reason? = null
    ) {
        fun ok(): Boolean {
            return reason == null
        }

        fun message(): String {
            return when (reason) {
                null -> ""
                Reason.BAD_EXTENSION -> "这不是 Aivora 导出文件，只支持 .aivora 后缀。"
                Reason.BAD_CONTAINER -> "文件结构不完整或已被截断，无法识别。"
                Reason.UNSUPPORTED_VERSION -> "文件使用了本版不支持的 AKCP 或加密版本。"
                Reason.BAD_CREDENTIAL -> "凭据不正确，或文件已被修改。"
                Reason.BAD_PAYLOAD -> "解密成功，但里面的内容不是合法的 AKCP 配置。"
                else -> "读取文件失败，请重试。"
            }
        }
    }

    /** 界面预检：只看文件名，不读内容。 */
    @JvmStatic
    fun acceptsName(name: String?): Boolean {
        return name != null && name.length > EXTENSION.length && name.endsWith(EXTENSION)
    }

    /** 凭据格式提示：去掉连字符后必须是 52 位 Base32。 */
    @JvmStatic
    fun credentialLooksValid(raw: String?): Boolean {
        val s = normalize(raw)
        if (s.length != CREDENTIAL_SYMBOLS) {
            return false
        }
        for (i in s.indices) {
            val c = s[i]
            val ok = (c in 'A'..'Z') || (c in '2'..'7')
            if (!ok) {
                return false
            }
        }
        return true
    }

    /** 把用户输入归一化成派生用的 ASCII：去连字符、去空白、转大写。 */
    @JvmStatic
    fun normalize(raw: String?): String {
        if (raw == null) {
            return ""
        }
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (c == '-' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                continue
            }
            sb.append(c.uppercaseChar())
        }
        return sb.toString()
    }

    /**
     * 解密并解析整个文件。
     *
     * 【必须后台线程】Argon2id 64 MiB / 3 轮在手机上要几百毫秒到数秒。
     */
    @JvmStatic
    fun parse(bytes: ByteArray?, credential: String?, fileName: String?): Result {
        if (!acceptsName(fileName)) {
            return Result(emptyList(), Reason.BAD_EXTENSION)
        }
        if (bytes == null || bytes.size < HEADER_SIZE + TAG_SIZE || bytes.size > MAX_FILE_SIZE) {
            return Result(emptyList(), Reason.BAD_CONTAINER)
        }
        val cred = normalize(credential)
        if (cred.length != CREDENTIAL_SYMBOLS) {
            return Result(emptyList(), Reason.BAD_CREDENTIAL)
        }
        // —— 头部校验：先做全部廉价的边界检查，再跑昂贵的 KDF。
        val header = bytes.copyOf(HEADER_SIZE)
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) {
                return Result(emptyList(), Reason.BAD_CONTAINER)
            }
        }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buf.position(8)
        val protocolVer = buf.short.toInt()
        val containerVer = buf.short.toInt()
        val cryptoVer = buf.short.toInt()
        val kdfId = buf.get().toInt()
        val cipherId = buf.get().toInt()
        val flags = buf.get().toInt()
        val headerLen = buf.get().toInt()
        if (protocolVer != 1 || containerVer != 1 || cryptoVer != 1 || kdfId != 1 || cipherId != 1) {
            return Result(emptyList(), Reason.UNSUPPORTED_VERSION)
        }
        val memKib = buf.int
        val iters = buf.int
        val parallel = buf.int
        if (memKib != M_KIB || iters != ITERATIONS || parallel != PARALLELISM) {
            return Result(emptyList(), Reason.UNSUPPORTED_VERSION)
        }
        val len = buf.int
        // 规范要求：长度必须与文件实际大小严丝合缝，不接受「后面还有别的东西」。
        if (flags != 0 || headerLen != HEADER_SIZE || len < TAG_SIZE || HEADER_SIZE + len != bytes.size) {
            return Result(emptyList(), Reason.BAD_CONTAINER)
        }
        val salt = header.copyOfRange(34, 50)
        val nonce = header.copyOfRange(50, 62)
        // 保留位必须为 0。
        if (header[62].toInt() != 0 || header[63].toInt() != 0) {
            return Result(emptyList(), Reason.BAD_CONTAINER)
        }
        return try {
            val key = derive(cred, salt, memKib, iters, parallel)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            // 认证失败会在这里抛 AEADBadTagException —— 绝不返回任何部分明文。
            val plain = cipher.doFinal(bytes, HEADER_SIZE, len)
            parsePayload(String(plain, Charsets.UTF_8))
        } catch (t: Throwable) {
            Logs.w(TAG, "AKCP 解密失败", t)
            Result(emptyList(), Reason.BAD_CREDENTIAL)
        }
    }

    /** Argon2id 派生 32 字节密钥。 */
    private fun derive(credential: String, salt: ByteArray, memKib: Int, iters: Int, parallel: Int): ByteArray {
        val gen = Argon2BytesGenerator()
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(memKib)
            .withIterations(iters)
            .withParallelism(parallel)
            .build()
        gen.init(params)
        val out = ByteArray(32)
        gen.generateBytes(credential.toByteArray(Charsets.UTF_8), out)
        return out
    }

    /** 解析明文 JSON，逐条校验必填字段。 */
    private fun parsePayload(text: String): Result {
        val root = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return Result(emptyList(), Reason.BAD_PAYLOAD)
        }
        if (root.optString("schema", "") != "akcp.config-set/1") {
            return Result(emptyList(), Reason.BAD_PAYLOAD)
        }
        val arr: JSONArray = root.optJSONArray("configs") ?: return Result(emptyList(), Reason.BAD_PAYLOAD)
        val out = ArrayList<AkcpConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return Result(emptyList(), Reason.BAD_PAYLOAD)
            val c = AkcpConfig()
            c.configName = o.optString("config_name", "")
            c.provider = o.optString("provider", "")
            c.apiKey = o.optString("api_key", "")
            c.endpoint = o.optString("endpoint", "")
            if (c.configName.isEmpty() || c.provider.isEmpty() || c.apiKey.isEmpty() || c.endpoint.isEmpty()) {
                return Result(emptyList(), Reason.BAD_PAYLOAD)
            }
            val models = o.optJSONArray("models")
            if (models != null) {
                for (k in 0 until models.length()) {
                    val m = models.optString(k, "").trim()
                    if (m.isNotEmpty()) {
                        c.models.add(m)
                    }
                }
            }
            c.defaultModel = o.optString("default_model", "")
            c.notes = o.optString("notes", "")
            c.enabled = o.optBoolean("enabled", true)
            c.extensions = o.optJSONObject("extensions")
            c.unknown = pickUnknown(o)
            out.add(c)
        }
        if (out.isEmpty()) {
            return Result(emptyList(), Reason.BAD_PAYLOAD)
        }
        return Result(out)
    }

    /** 挑出本 App 不认识的键，原样保留（规范要求不静默丢弃）。 */
    private fun pickUnknown(o: JSONObject): JSONObject {
        val known = setOf(
            "config_name", "provider", "api_key", "endpoint",
            "models", "default_model", "notes", "enabled", "extensions"
        )
        val res = JSONObject()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k == null || known.contains(k)) {
                continue
            }
            try {
                res.put(k, o.opt(k))
            } catch (t: Throwable) {
                // 单个键复制失败不影响整体导入。
            }
        }
        return res
    }

    /** API Key 打码：只露首尾各 4 位，长度很短时整体遮住。 */
    @JvmStatic
    fun maskKey(key: String?): String {
        val k = key ?: ""
        if (k.isEmpty()) {
            return ""
        }
        if (k.length <= 8) {
            return "*".repeat(k.length)
        }
        return k.substring(0, 4) + "…" + k.substring(k.length - 4)
    }
}
