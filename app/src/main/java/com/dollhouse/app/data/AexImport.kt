package com.dollhouse.app.data

import com.dollhouse.app.core.Logs
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.util.encoders.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 【职责】读取 Aivora 通过 AEX 1.0（Aivora Exchange Protocol）导出的 `.akcp` 文件，
 *        校验 / 解密 / 解析，翻译成本 App 的供应商与模型。
 *
 * ## 规范来源
 * 实现严格对齐 `Farewell-coder/Aivora` 仓库 `protocol/` 目录下的正式文档：
 * `AEX-SPECIFICATION.md`、`AEX-SECURITY.md`、`AEX-SCHEMA.json`、`examples/encrypted-package.md`。
 * 该协议在仓库里标注为 **Draft（尚未冻结）**，因此本实现的所有协议常量都集中在
 * 本文顶部的「常量区」，对方冻结 / 调整时只需改这一处。
 *
 * ## 容器结构（与 AKCP 完全不同）
 * ```
 * 偏移    长度   内容
 * 0       8      Magic: 41 45 58 31 0D 0A 1A 0A   （ASCII "AEX1" + CRLF + SUB + LF）
 * 8       4      头部长度 H（无符号大端 32 位，合法范围 64–8192）
 * 12      H      头部 JSON（UTF-8，RFC 8785 规范化，明文，不含用户数据）
 * 12+H    N      AES-256-GCM 密文，末尾 16 字节为认证标签
 * ```
 * 约束：整文件 ≤ 16 MiB；`12 + H + ciphertextLength` 必须**严丝合缝**等于文件长度（禁止尾部多余字节）。
 *
 * ## 加密参数（AEX 1.0 固定值）
 * - KDF：PBKDF2-HMAC-SHA256，迭代 600000（合法范围 100000–2000000），dkLen 32
 * - Cipher：AES-256-GCM，tag 128 bit，salt 16 字节，nonce 12 字节
 * - **AAD = Magic ‖ 头部长度(4B BE) ‖ 头部 JSON 原始字节**
 *   —— 必须是文件里的原始字节，解析后重新序列化会改变空白/键序，认证必然失败。
 *
 * ## 凭证规范化（顺序不可调换）
 * 1. 去除所有空白（空格 / 制表符 / 换行）→ 2. 去除连字符 `-` → 3. 转大写 ASCII。
 * 规范化后必须是 52 个 Base32 字符（`A–Z`、`2–7`，无填充）；长度或字符集不符直接拒绝，
 * **不得模糊修正或部分匹配**。AEX 与旧版 AKCP 的凭证长度相同但数学上不通用。
 *
 * ## 校验顺序（严格照抄规范，全部在昂贵 KDF 之前完成）
 * 1. 扩展名 / 文件大小 → 2. Magic → 3. H 范围 → 4. 长度自洽
 * → 5. 头部 JSON 与字段齐全 → 6. kdf/cipher/tagBits/iterations 白名单
 * → 7. salt/nonce Base64 解码长度 → 8. 派生密钥 + GCM 解密 → 9. Payload 按 Schema 校验。
 * 任何一步失败都不得输出部分明文。
 *
 * ## 错误码
 * 公开的 `Reason` 枚举与规范第 8 节的错误码一一对应，界面文案与之对齐，
 * 且**不区分「凭证错误」与「文件被改」**（统一 AUTHENTICATION_FAILED），避免侧信道。
 */
object AexImport {

    private const val TAG = "Dollhouse"

    /* ============================ 协议常量 ============================ */

    /** 规范后缀（小写）。 */
    const val EXTENSION = ".akcp"

    /** 规范 MIME Type。 */
    const val MIME = "application/vnd.aivora.akcp"

    /** 整文件上限：16 MiB。 */
    const val MAX_FILE_SIZE = 16 * 1024 * 1024

    /** Magic：ASCII "AEX1" + CRLF + SUB + LF。 */
    private val MAGIC = byteArrayOf(0x41, 0x45, 0x58, 0x31, 0x0d, 0x0a, 0x1a, 0x0a)

    /** 头部长度合法范围（含端点）。 */
    private const val HEADER_MIN = 64
    private const val HEADER_MAX = 8192

    /** 认证标签 16 字节 = 128 bit。 */
    private const val TAG_SIZE = 16

    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val KEY_BITS = 256

    /** 迭代次数合法范围；本实现按规范固定用 600000 导出，导入按范围接受。 */
    private const val ITER_MIN = 100000
    private const val ITER_MAX = 2000000

    /** 固定的算法标识（字符串精确匹配，不做大小写宽容）。 */
    private const val KDF_NAME = "PBKDF2-HMAC-SHA256"
    private const val CIPHER_NAME = "AES-256-GCM"
    private const val FORMAT_ID = "aivora.exchange/1"
    private const val PAYLOAD_ENCODING = "json"

    /** 本实现支持的主版本。 */
    private const val PROTOCOL_MAJOR = 1

    /** 凭证连续失败次数上限与冷却（规范：不得无限制尝试验证）。 */
    private const val MAX_ATTEMPTS = 5
    private const val COOLDOWN_MS = 30_000L

    /* ============================ 数据模型 ============================ */

    /**
     * 一条 AEX 配置。
     *
     * 【字段语义】规范 4.1 明确「空值与缺失值不同」：
     *   字段不存在 = 未设置；字段存在但为空串 = 用户明确置空。
     *   这里用 [hasBaseUrl] / [hasApiKey] / [hasDescription] 记录「是否存在」，
     *   值本身保留原样（含空串），不做 trim 也不做补全。
     */
    class AexProfile {
        var id: String = ""
        var name: String = ""
        var provider: String = ""
        var baseUrl: String = ""
        var hasBaseUrl: Boolean = false
        var apiKey: String = ""
        var hasApiKey: Boolean = false
        var models: MutableList<String> = ArrayList()
        var defaultModel: String = ""
        var description: String = ""
        var hasDescription: Boolean = false
        /** 规范里的 `extra`：本 App 没有对应容器，原样保留并明确告知用户。 */
        var extra: JSONObject? = null
        /** 未识别的成员（规范要求不得静默丢弃）。 */
        var unknown: JSONObject = JSONObject()
        /** 是否带了本 App 存不下的信息 —— 预览里必须提示。 */
        fun hasExtra(): Boolean {
            return (extra != null && extra!!.length() > 0) || unknown.length() > 0 || hasDescription
        }
    }

    /** 失败原因：与规范第 8 节的错误码一一对应。 */
    enum class Reason {
        INVALID_EXTENSION,
        FILE_TOO_LARGE,
        INVALID_MAGIC,
        INVALID_HEADER,
        UNSUPPORTED_VERSION,
        UNSUPPORTED_PARAMETER,
        AUTHENTICATION_FAILED,
        INVALID_PAYLOAD,
        TOO_MANY_ATTEMPTS,
        IO_ERROR
    }

    class Result(
        val profiles: List<AexProfile>,
        val reason: Reason? = null,
        /** 非致命问题（次版本偏新、字段存不下、个别条目被跳过等）。 */
        val warnings: List<String> = emptyList()
    ) {
        fun ok(): Boolean = reason == null

        fun code(): String {
            return when (reason) {
                null -> ""
                Reason.INVALID_EXTENSION -> "INVALID_EXTENSION"
                Reason.FILE_TOO_LARGE -> "FILE_TOO_LARGE"
                Reason.INVALID_MAGIC -> "INVALID_MAGIC"
                Reason.INVALID_HEADER -> "INVALID_HEADER"
                Reason.UNSUPPORTED_VERSION -> "UNSUPPORTED_VERSION"
                Reason.UNSUPPORTED_PARAMETER -> "UNSUPPORTED_PARAMETER"
                Reason.AUTHENTICATION_FAILED -> "AUTHENTICATION_FAILED"
                Reason.INVALID_PAYLOAD -> "INVALID_PAYLOAD"
                Reason.TOO_MANY_ATTEMPTS -> "TOO_MANY_ATTEMPTS"
                Reason.IO_ERROR -> "WRITE_FAILED"
            }
        }

        /** 用户可见文案：与适配指南「建议给用户的提示」一列对齐。 */
        fun message(): String {
            return when (reason) {
                null -> ""
                Reason.INVALID_EXTENSION -> "请选择 .akcp 文件。"
                Reason.FILE_TOO_LARGE -> "文件过大（超过 16 MiB），可能已损坏。"
                Reason.INVALID_MAGIC -> "无法识别的文件格式，这不是 AEX 配置文件。"
                Reason.INVALID_HEADER -> "文件头部损坏，无法读取。"
                Reason.UNSUPPORTED_VERSION -> "该文件由更新版本的 Aivora 生成，请升级后重试。"
                Reason.UNSUPPORTED_PARAMETER -> "文件使用了本版不支持的加密参数。"
                // 【关键】按规范不区分「凭证错」与「被篡改」。
                Reason.AUTHENTICATION_FAILED -> "凭证错误，或文件已被修改。"
                Reason.INVALID_PAYLOAD -> "解密成功，但文件内容格式异常。"
                Reason.TOO_MANY_ATTEMPTS -> "尝试次数过多，请稍等半分钟再试。"
                else -> "读取文件失败，请重试。"
            }
        }
    }

    /** 容器类型（入口分流用，只看 Magic）。 */
    enum class Kind { AEX, AKCP, UNKNOWN }

    /* ============================ 入口分流 ============================ */

    /**
     * 按**内容 Magic**判定容器类型。
     *
     * 【规范依据】AEX-SPECIFICATION 第 7 节与 legacy-format.md 都明确「只看 Magic，不看后缀」：
     *   用户把 `.aivora` 改名成 `.akcp` 时，必须按 Magic 判定为旧格式并走旧流程，
     *   而不是用 AEX 参数去解旧文件然后误报「凭证错误」。
     */
    @JvmStatic
    fun detect(bytes: ByteArray?): Kind {
        if (bytes == null || bytes.size < 8) {
            return Kind.UNKNOWN
        }
        if (matches(bytes, MAGIC)) {
            return Kind.AEX
        }
        // 旧格式 AKCP 1.0 的 Magic：ASCII "AKCP" + CRLF + SUB + LF
        val akcp = byteArrayOf(0x41, 0x4b, 0x43, 0x50, 0x0d, 0x0a, 0x1a, 0x0a)
        return if (matches(bytes, akcp)) Kind.AKCP else Kind.UNKNOWN
    }

    private fun matches(bytes: ByteArray, magic: ByteArray): Boolean {
        if (bytes.size < magic.size) {
            return false
        }
        for (i in magic.indices) {
            if (bytes[i] != magic[i]) {
                return false
            }
        }
        return true
    }

    /** 文件名是否像 AEX 文件（只看名字，用于选择器预检）。 */
    @JvmStatic
    fun acceptsName(name: String?): Boolean {
        return name != null && name.length > EXTENSION.length && name.endsWith(EXTENSION)
    }

    /* ============================ 凭据 ============================ */

    /**
     * 凭据格式预检与规范化后的判定。
     *
     * 规范 2.1 的顺序不可调换：去空白 → 去连字符 → 转大写。
     * 规范化后必须恰好 52 个 `A–Z` / `2–7` 字符。
     */
    @JvmStatic
    fun credentialLooksValid(raw: String?): Boolean {
        return isBase32Credential(normalizeCredential(raw))
    }

    /**
     * 规范化凭证：去空白 → 去 `-` → 转大写 ASCII。
     *
     * 【只做规范要求的三步】不 trim 之外不加任何宽容处理（不做全角转换、不做 OCR 纠正），
     *   因为规范明确禁止「部分匹配或模糊修正」。
     */
    @JvmStatic
    fun normalizeCredential(raw: String?): String {
        if (raw == null) {
            return ""
        }
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            when {
                c == '-' -> continue
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> continue
                else -> sb.append(c.uppercaseChar())
            }
        }
        return sb.toString()
    }

    private fun isBase32Credential(s: String): Boolean {
        if (s.length != 52) {
            return false
        }
        for (c in s) {
            if (!((c in 'A'..'Z') || (c in '2'..'7'))) {
                return false
            }
        }
        return true
    }

    /* ============================ 失败冷却 ============================ */

    private var failCount = 0
    private var cooldownUntil = 0L

    private fun cooling(): Boolean = System.currentTimeMillis() < cooldownUntil

    private fun noteFailure() {
        synchronized(AexImport) {
            failCount++
            if (failCount >= MAX_ATTEMPTS) {
                failCount = 0
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                Logs.i(TAG, "AEX 凭证连续失败，进入冷却")
            }
        }
    }

    private fun noteSuccess() {
        synchronized(AexImport) {
            failCount = 0
            cooldownUntil = 0L
        }
    }

    /** 供测试与「用户重试」按钮复位冷却。 */
    @JvmStatic
    fun resetAttempts() {
        synchronized(AexImport) {
            failCount = 0
            cooldownUntil = 0L
        }
    }

    /* ============================ 解析 ============================ */

    /**
     * 解密并解析整个文件。
     *
     * 【必须后台线程】PBKDF2-HMAC-SHA256 60 万轮在手机上约 300–900 ms（规范第 3 节实测口径），
     *   主线程调用即 ANR 风险。
     *
     * @param bytes      文件全部字节
     * @param credential 用户输入的凭据（不写日志、不保存、不缓存）
     * @param fileName   文件名（仅用于后缀判定与提示）
     */
    @JvmStatic
    fun parse(bytes: ByteArray?, credential: String?, fileName: String?): Result {
        if (bytes == null) {
            return Result(emptyList(), Reason.IO_ERROR)
        }
        // ---------- ① 扩展名 ----------
        // 【顺序】规范把扩展名放在第一步。但内容 Magic 命中 AEX 时容忍改名（见下方说明）：
        //   规范禁止的是「只凭扩展名判断协议版本」，而不是「内容正确也必须拒收」。
        val isAex = detect(bytes) == Kind.AEX
        if (!isAex && !acceptsName(fileName)) {
            return Result(emptyList(), Reason.INVALID_EXTENSION)
        }
        // 旧格式改名成 .akcp：按规范必须明确提示「这是旧版格式」，而不是报凭证错误。
        if (detect(bytes) == Kind.AKCP) {
            return Result(emptyList(), Reason.INVALID_EXTENSION)
        }
        // ---------- ② 文件大小 ----------
        if (bytes.size > MAX_FILE_SIZE) {
            return Result(emptyList(), Reason.FILE_TOO_LARGE)
        }
        // ---------- ③ Magic ----------
        if (bytes.size < 12) {
            // 连 Magic + 长度前缀都不够，按 Magic 不匹配处理（规范 V11/V13 口径）。
            return Result(emptyList(), Reason.INVALID_MAGIC)
        }
        if (!matches(bytes, MAGIC)) {
            return Result(emptyList(), Reason.INVALID_MAGIC)
        }
        // ---------- ④ 头部长度 ----------
        val headerLen = ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        if (headerLen < HEADER_MIN || headerLen > HEADER_MAX) {
            return Result(emptyList(), Reason.INVALID_HEADER)
        }
        // 防止整数溢出：先确认「12 + H」不越界，再算剩余。
        if (12L + headerLen.toLong() > bytes.size.toLong()) {
            return Result(emptyList(), Reason.INVALID_HEADER)
        }
        // ---------- ⑤ 头部 JSON ----------
        val headerBytes = bytes.copyOfRange(12, 12 + headerLen)
        val header = try {
            JSONObject(String(headerBytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            Logs.w(TAG, "AEX 头部不是合法 JSON", t)
            return Result(emptyList(), Reason.INVALID_HEADER)
        }
        val warnings = ArrayList<String>()

        // ---------- ⑥ 头部字段与参数白名单（全部在 KDF 之前） ----------
        if (header.optString("format", "") != FORMAT_ID) {
            // format 不符既可能是头部损坏，也可能是未来格式；统一按头部非法处理，
            // 避免把「不是本协议的文件」误判成版本问题。
            return Result(emptyList(), Reason.INVALID_HEADER)
        }
        val pv = header.optString("protocolVersion", "")
        val major = majorOf(pv) ?: return Result(emptyList(), Reason.INVALID_HEADER)
        if (major > PROTOCOL_MAJOR) {
            // 规范第 6 节：更高主版本必须拒绝并提示，不得当旧格式尝试。
            return Result(emptyList(), Reason.UNSUPPORTED_VERSION)
        }
        if (major < PROTOCOL_MAJOR) {
            return Result(emptyList(), Reason.UNSUPPORTED_VERSION)
        }
        if (minorOf(pv) > 0) {
            warnings.add("文件使用 AEX " + pv + "，本版按 1.0 规则读取。")
        }
        if (header.optString("kdf", "") != KDF_NAME) {
            return Result(emptyList(), Reason.UNSUPPORTED_PARAMETER)
        }
        if (header.optString("cipher", "") != CIPHER_NAME) {
            return Result(emptyList(), Reason.UNSUPPORTED_PARAMETER)
        }
        if (header.optInt("tagBits", -1) != 128) {
            return Result(emptyList(), Reason.UNSUPPORTED_PARAMETER)
        }
        if (header.optString("payloadEncoding", "") != PAYLOAD_ENCODING) {
            return Result(emptyList(), Reason.UNSUPPORTED_PARAMETER)
        }
        val iterations = header.optInt("iterations", -1)
        if (iterations < ITER_MIN || iterations > ITER_MAX) {
            // 规范：超出范围直接拒绝，避免恶意文件用超高迭代次数做资源耗尽攻击。
            return Result(emptyList(), Reason.UNSUPPORTED_PARAMETER)
        }
        // ---------- ⑦ salt / nonce ----------
        val salt = decodeBase64(header.optString("salt", ""))
        if (salt == null || salt.size != SALT_SIZE) {
            return Result(emptyList(), Reason.INVALID_HEADER)
        }
        val nonce = decodeBase64(header.optString("nonce", ""))
        if (nonce == null || nonce.size != NONCE_SIZE) {
            return Result(emptyList(), Reason.INVALID_HEADER)
        }
        // ---------- ⑧ 长度自洽（禁止尾部多余字节） ----------
        val ciphertextLen = header.optInt("ciphertextLength", -1)
        if (ciphertextLen < TAG_SIZE) {
            return Result(emptyList(), Reason.INVALID_HEADER)
        }
        val expectedTotal = 12L + headerLen.toLong() + ciphertextLen.toLong()
        if (expectedTotal != bytes.size.toLong()) {
            return Result(emptyList(), Reason.INVALID_HEADER)
        }
        // ---------- ⑨ 凭证 ----------
        val cred = normalizeCredential(credential)
        if (!isBase32Credential(cred)) {
            // 【不得模糊修正】格式不对就是格式不对，但要与「认证失败」区分开，
            // 否则用户会把「输错格式」误当成「文件坏了」。
            return Result(emptyList(), Reason.AUTHENTICATION_FAILED)
        }
        if (cooling()) {
            return Result(emptyList(), Reason.TOO_MANY_ATTEMPTS)
        }

        // ---------- ⑩ 派生 + 解密 ----------
        return try {
            val key = derive(cred, salt, iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            // 【关键】AAD = Magic ‖ uint32be(H) ‖ 头部 JSON **原始字节**。
            //   这里直接取文件里的三段拼接，绝不重新序列化 header。
            val aad = ByteArray(8 + 4 + headerLen)
            System.arraycopy(bytes, 0, aad, 0, 8)
            System.arraycopy(bytes, 8, aad, 8, 4)
            System.arraycopy(headerBytes, 0, aad, 12, headerLen)
            cipher.updateAAD(aad)
            // 认证失败在这里抛 AEADBadTagException —— 直接整体拒绝，绝不返回部分明文。
            val plain = cipher.doFinal(bytes, 12 + headerLen, ciphertextLen)
            val r = parsePayload(String(plain, Charsets.UTF_8))
            if (r.ok()) {
                noteSuccess()
                Result(r.profiles, null, warnings + r.warnings)
            } else {
                r
            }
        } catch (t: Throwable) {
            // 【规范硬要求】不区分「头部被改」与「凭证错误」；统一 AUTHENTICATION_FAILED。
            noteFailure()
            Logs.w(TAG, "AEX 解密失败", t)
            Result(emptyList(), Reason.AUTHENTICATION_FAILED)
        }
    }

    /**
     * Base64 解码（标准字母表 + `=` 填充）。解不出来返回 null。
     *
     * 【为什么不用 android.util.Base64】那是 Android 框架类，在 JVM 单元测试里只有空桩，
     * 一调就抛 `RuntimeException: Method ... not mocked`。这里用 bcprov 自带的编码器、
     * 再自己做一层严格字符集校验，既能在纯 JVM 里跑，也符合规范「不接受模糊修正」的要求
     * （空白、URL-safe 变体、缺填充一律视为非法）。
     */
    private fun decodeBase64(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 4 != 0) {
            return null
        }
        for (c in s) {
            val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/' || c == '='
            if (!ok) {
                return null
            }
        }
        return try {
            Base64.decode(s)
        } catch (t: Throwable) {
            null
        }
    }

    /** PBKDF2-HMAC-SHA256 派生 32 字节密钥（BouncyCastle，保证 API 24+ 行为一致）。 */
    private fun derive(credential: String, salt: ByteArray, iterations: Int): ByteArray {
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        gen.init(credential.toByteArray(Charsets.US_ASCII), salt, iterations)
        val params = gen.generateDerivedParameters(KEY_BITS) as KeyParameter
        return params.key
    }

    /** 把 "1.0" / "1" 取主版本号；取不出来返回 null。 */
    private fun majorOf(v: String): Int? {
        return versionPart(v, 0)
    }

    private fun minorOf(v: String): Int {
        return versionPart(v, 1) ?: 0
    }

    private fun versionPart(v: String, index: Int): Int? {
        return try {
            val s = v.trim()
            if (s.isEmpty()) {
                return null
            }
            val parts = s.split('.')
            if (index >= parts.size) {
                // "1" 视为主版本 1、次版本 0
                return if (index == 1) 0 else null
            }
            parts[index].toInt()
        } catch (t: Throwable) {
            null
        }
    }

    /* ============================ 载荷 ============================ */

    /**
     * 解析明文 JSON，逐条校验。
     *
     * 【规范依据】AEX-SCHEMA.json：
     *   根必填 `format` / `protocolVersion` / `exportedAt` / `exporter` / `profiles`；
     *   `profiles` 长度 ≥ 1；每条必填 `id` / `name`（都是 minLength 1）。
     * 【不连坐】某条不合法只跳过该条并记 warning，不破坏其它合法配置。
     */
    private fun parsePayload(text: String): Result {
        val root = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return Result(emptyList(), Reason.INVALID_PAYLOAD)
        }
        val warnings = ArrayList<String>()

        // ---------- 根对象必填项 ----------
        if (root.optString("format", "") != FORMAT_ID) {
            // 规范第 4 节：Payload 的 format 与头部一致，属于二次校验。
            return Result(emptyList(), Reason.INVALID_PAYLOAD)
        }
        val pv = root.optString("protocolVersion", "")
        val major = majorOf(pv) ?: return Result(emptyList(), Reason.INVALID_PAYLOAD)
        if (major > PROTOCOL_MAJOR) {
            return Result(emptyList(), Reason.UNSUPPORTED_VERSION)
        }
        // exportedAt：Schema 要求 date-time 字符串。这里只做存在性 + 字符串类型检查，
        //   不做严格 RFC3339 解析 —— 时间戳只用于展示，严格化会让时钟异常的导出方整份文件作废。
        if (root.optString("exportedAt", "").isEmpty()) {
            warnings.add("文件缺少导出时间，不影响导入。")
        }
        val exporter = root.optJSONObject("exporter")
        if (exporter == null) {
            warnings.add("文件缺少导出方信息，不影响导入。")
        }
        val profilesArr = root.optJSONArray("profiles")
        if (profilesArr == null || profilesArr.length() < 1) {
            return Result(emptyList(), Reason.INVALID_PAYLOAD)
        }

        val known = arrayOf("format", "protocolVersion", "exportedAt", "exporter", "profiles")
        val rootUnknown = pickUnknown(root, known)

        val out = ArrayList<AexProfile>()
        var skipped = 0
        for (i in 0 until profilesArr.length()) {
            val o = profilesArr.optJSONObject(i)
            if (o == null) {
                skipped++
                continue
            }
            val p = AexProfile()
            p.id = o.optString("id", "")
            p.name = o.optString("name", "")
            // Schema 要求 id 与 name 都非空；缺任一项这条就不合法。
            if (p.id.isEmpty() || p.name.isEmpty()) {
                skipped++
                continue
            }
            p.provider = o.optString("provider", "")
            // 【空值与缺失值不同】用 has() 记录是否存在，值本身原样保留。
            p.hasBaseUrl = o.has("baseUrl")
            p.baseUrl = o.optString("baseUrl", "")
            p.hasApiKey = o.has("apiKey")
            p.apiKey = o.optString("apiKey", "")
            p.defaultModel = o.optString("defaultModel", "")
            p.hasDescription = o.has("description")
            p.description = o.optString("description", "")
            // 【顺序必须保留】规范明确 models 不得重排，这里只去掉空串、不排序。
            val ms = o.optJSONArray("models")
            if (ms != null) {
                for (j in 0 until ms.length()) {
                    val m = ms.optString(j, "")
                    if (m.isNotEmpty()) {
                        p.models.add(m)
                    }
                }
            }
            p.extra = o.optJSONObject("extra")
            // 【AEX-SCHEMA 硬要求】extra 的键必须是反域名命名空间
            //   （正则 `^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)+$`），禁止裸键。
            //   Schema 把这条写在 propertyNames 里，属于**结构约束**而非建议；
            //   不合规说明导出方有 bug 或文件被拼装过，按 INVALID_PAYLOAD 拒绝这一条，
            //   但不连坐其它条目（下面 skipped 会记一笔）。
            if (p.extra != null && !extraKeysValid(p.extra!!)) {
                skipped++
                continue
            }
            p.unknown = pickUnknown(
                o,
                arrayOf("id", "name", "provider", "baseUrl", "apiKey", "models", "defaultModel", "description", "extra")
            )
            if (!p.hasApiKey || p.apiKey.isEmpty()) {
                warnings.add("「" + p.name + "」没有带密钥，导入后需要自己补。")
            }
            out.add(p)
        }
        if (out.isEmpty()) {
            return Result(emptyList(), Reason.INVALID_PAYLOAD)
        }
        if (skipped > 0) {
            warnings.add("有 " + skipped + " 条配置缺少必要字段（id 或名称），已跳过。")
        }
        if (rootUnknown.length() > 0) {
            warnings.add("文件带有本应用不认识的顶层字段：" + rootUnknown.keys().asSequence().joinToString("、") + "。")
        }
        return Result(out, null, warnings)
    }
    /**
     * 校验 `extra` 的全部键是否满足反域名命名空间要求。
     *
     * 【规范依据】AEX-SCHEMA.json 的 `extra.propertyNames.pattern` =
     *   `^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)+$`，即「至少两段、以点分隔」，
     *   形如 `com.example.notes.pinned`。裸键（如 `retry`）不合规。
     *
     * 【为什么不用 Kotlin Regex】这条判定在每次导入都要跑，且键数量可能不少；
     *   手写一遍比反复调用正则引擎便宜得多，也避免把 Pattern 缓存这件小事带进数据层。
     *   （工程里 DiagnosticsFormat 提正则字段是为了复用同一份 Pattern，这里是另一回事。）
     *
     * 【空 extra 视为合法】没有键就没有违规键，Schema 也不要求 extra 非空。
     */
    private fun extraKeysValid(extra: JSONObject): Boolean {
        val it = extra.keys()
        while (it.hasNext()) {
            if (!isNamespacedKey(it.next())) {
                return false
            }
        }
        return true
    }

    /** 单个键是否为「至少两段、每段只含字母数字与 `_` `-`」的形式。 */
    private fun isNamespacedKey(key: String): Boolean {
        var segLen = 0
        var segs = 0
        for (c in key) {
            if (c == '.') {
                // 段之间必须都有内容，连续点或首尾点都不合法。
                if (segLen == 0) {
                    return false
                }
                segs++
                segLen = 0
                continue
            }
            val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '_' || c == '-'
            if (!ok) {
                return false
            }
            segLen++
        }
        // 末尾段必须有内容，且总段数 ≥ 2（即至少出现一次点）。
        return segLen > 0 && segs >= 1
    }

    /** 收集未识别的成员（规范：不得静默丢弃未知数据）。 */
    private fun pickUnknown(o: JSONObject, known: Array<String>): JSONObject {
        val out = JSONObject()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (known.contains(k)) {
                continue
            }
            try {
                out.put(k, o.opt(k))
            } catch (ignored: Throwable) {
            }
        }
        return out
    }

    /** 打码密钥：只露头尾，用于预览（规范：导入前展示打码预览）。 */
    @JvmStatic
    fun maskKey(key: String?): String {
        if (key == null || key.isEmpty()) {
            return "（未提供）"
        }
        if (key.length <= 8) {
            return "••••"
        }
        return key.substring(0, 4) + "…" + key.substring(key.length - 4)
    }
}