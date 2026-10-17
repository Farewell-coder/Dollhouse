package com.dollhouse.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】模型包规范 v1 的**数据定义**：`manifest.json` 的结构 + 读写。
 *
 * ## 什么是「模型包」
 * 一个 zip，里面有 `manifest.json`（描述）+ 资源（图 / json 动画）：
 * ```
 * my-doll.zip
 * ├── manifest.json      必需
 * ├── preview.png        可选
 * └── res/
 *     ├── idle.json      引擎入口（lottie 引擎）
 *     └── images/...     分离式图片资源
 * ```
 *
 * ## 为什么单独抽一个「规范」出来（任务书的可持续性契约③）
 * 改造前「人偶素材」= 一张位图路径，**没有格式可言** —— 别人无法做素材分享，
 * 只能连人偶配置一起手工复制。定下这份规范后：
 * · 制作者照规范造包（不需要读本工程源码）；
 * · 使用者导入前能被**机械校验**（见 [ModelValidator]）；
 * · 未来加引擎只是多一个 `engine` 取值 + 资源后缀，规范本身不动。
 *
 * ## 版本策略
 * [SPEC_VERSION] 是**规范**版本，与 App 的 versionCode 无关。
 * 读到更高的版本号一律拒绝（见 [ModelValidator]），
 * 因为「高版本规范」可能带了本版本根本不认识的必需字段 —— 猜着解析只会错得更隐蔽。
 */
object ModelPackage {

    /** 当前支持的规范版本。 */
    const val SPEC_VERSION = 1

    /** 清单文件名（固定，不允许改名）。 */
    const val MANIFEST = "manifest.json"

    /** 预览图约定名（可选）。 */
    const val PREVIEW = "preview.png"

    /** 资源目录约定名（可选；入口也可以直接放包根）。 */
    const val RES_DIR = "res"

    /**
     * 白名单资源后缀（任务书 §3.7 安全红线）。
     *
     * 【为什么是白名单而不是黑名单】黑名单永远漏 —— 今天想不到 `.wasm`，
     * 明天就有人放进来。白名单只认「确定只是数据」的格式，
     * 别的一律拒绝，漏不了。
     */
    val SAFE_EXT = listOf("png", "jpg", "jpeg", "webp", "gif", "json", "txt")

    /** 明确拒绝的可执行 / 脚本后缀（给出「为什么拒绝」时更精确）。 */
    val DANGEROUS_EXT = listOf("dex", "jar", "so", "apk", "sh", "js", "py", "lua", "bat", "exe")

    /** 单个模型包允许的最大解压后总字节数（防 zip bomb）。 */
    const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L

    /** 单个模型包允许的最大条目数。 */
    const val MAX_ENTRIES = 512

    /**
     * 一个模型包的清单。
     *
     * 【为什么用 data class 而不是直接读 JSONObject】校验器（[ModelValidator]）
     *   必须能在 JVM 上单测，而 `org.json` 在单元测试里会抛
     *   「not mocked」异常。把「JSON → 数据」这一步限在 [parse] 里，
     *   校验器就只依赖纯数据，测试可以把清单直接构造出来。
     *
     * @param actions 标准动作语义 ID → 包内资源相对路径。
     *    例：`{"idle": "res/idle.json", "tap": "res/tap.json"}`。
     *    **逐动作可不同引擎** —— 引擎由后缀推断（`.json` → lottie、`.gif`/`.webp` → animimage，
     *    其余 → sprite），这样制作者不必为每个动作重复写引擎名。
     * @param behaviors 行为配置（待机时长等），**声明式、不含代码**。
     * @param capabilities 扩展能力声明（同上，只是标记，不执行任何东西）。
     */
    data class Manifest(
        val specVersion: Int,
        val id: String,
        val name: String,
        val engine: String,
        val entry: String,
        val author: String = "",
        val modelVersion: String = "",
        val assets: List<String> = emptyList(),
        val preview: String = "",
        val actions: Map<String, String> = emptyMap(),
        val behaviors: Map<String, String> = emptyMap(),
        val capabilities: List<String> = emptyList(),
        val license: String = "",
        val minFramework: String = ""
    )

    // ============================ 读写 ============================

    /**
     * 解析清单。
     *
     * 【为什么「未知字段忽略并保留原样」】任务书 §3.7 的校验规则：
     *   别人用更新版规范做的包，只要没有超出本版本的能力声明，
     *   就该能读 —— 但 v1 里加不了「保留原样」这种语义（没地方存），
     *   所以本实现的做法是：**解析时不报错、不做白名单裁剪**，
     *   落盘时把原始 JSON 一起存起来（见 [ModelRepository]）。
     */
    fun parse(json: String?): ParseResult {
        if (json.isNullOrBlank()) {
            return ParseResult(null, listOf("manifest 内容为空"))
        }
        val o = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return ParseResult(null, listOf("manifest 不是合法 JSON：" + (t.message ?: "格式错误")))
        }
        val errs = ArrayList<String>()

        val spec = o.optInt("specVersion", -1)
        val id = o.optString("id", "").trim()
        val name = o.optString("name", "").trim()
        val engine = o.optString("engine", "").trim()
        val entry = o.optString("entry", "").trim()

        if (spec < 0) {
            errs.add("缺少必需字段 specVersion")
        }
        if (id.isEmpty()) {
            errs.add("缺少必需字段 id")
        }
        if (name.isEmpty()) {
            errs.add("缺少必需字段 name")
        }
        if (engine.isEmpty()) {
            errs.add("缺少必需字段 engine")
        }
        if (entry.isEmpty()) {
            errs.add("缺少必需字段 entry")
        }
        if (errs.isNotEmpty()) {
            return ParseResult(null, errs)
        }

        return ParseResult(
            Manifest(
                specVersion = spec,
                id = id,
                name = name,
                engine = engine,
                entry = normalizePath(entry),
                author = o.optString("author", ""),
                modelVersion = o.optString("modelVersion", ""),
                assets = readStringList(o, "assets"),
                preview = normalizePath(o.optString("preview", "")),
                actions = readStringMap(o, "actions"),
                behaviors = readStringMap(o, "behaviors"),
                capabilities = readStringList(o, "capabilities"),
                license = o.optString("license", ""),
                minFramework = o.optString("minFramework", "")
            ),
            emptyList()
        )
    }

    /** 序列化清单（导出用）。字段顺序固定，便于比对差异。 */
    fun write(m: Manifest): String {
        val o = JSONObject()
        o.put("specVersion", m.specVersion)
        o.put("id", m.id)
        o.put("name", m.name)
        o.put("engine", m.engine)
        o.put("entry", m.entry)
        if (m.author.isNotEmpty()) {
            o.put("author", m.author)
        }
        if (m.modelVersion.isNotEmpty()) {
            o.put("modelVersion", m.modelVersion)
        }
        if (m.license.isNotEmpty()) {
            o.put("license", m.license)
        }
        if (m.minFramework.isNotEmpty()) {
            o.put("minFramework", m.minFramework)
        }
        if (m.assets.isNotEmpty()) {
            o.put("assets", JSONArray(m.assets))
        }
        if (m.preview.isNotEmpty()) {
            o.put("preview", m.preview)
        }
        if (m.actions.isNotEmpty()) {
            o.put("actions", mapToJson(m.actions))
        }
        if (m.behaviors.isNotEmpty()) {
            o.put("behaviors", mapToJson(m.behaviors))
        }
        if (m.capabilities.isNotEmpty()) {
            o.put("capabilities", JSONArray(m.capabilities))
        }
        return o.toString(2)
    }

    /**
     * 按资源后缀推断该动作该用哪个引擎。
     *
     * 【为什么按后缀而不是读 manifest】制作者写 `actions` 映射时只写路径最省事；
     *   再让他给每个动作重复写引擎名，既啰嗦又容易写错（写错就是静默不播）。
     *   后缀是资源的客观属性，推断永远不会与资源本身矛盾。
     */
    fun engineOf(path: String): String {
        val ext = extOf(path)
        return when (ext) {
            "json" -> "lottie"
            "gif", "webp" -> "animimage"
            else -> "sprite"
        }
    }

    /** 取小写后缀（不含点）。 */
    fun extOf(path: String): String {
        val clean = path.trim().substringAfterLast('/').substringAfterLast('\\')
        val i = clean.lastIndexOf('.')
        return if (i < 0 || i == clean.length - 1) "" else clean.substring(i + 1).lowercase()
    }

    /**
     * 规范化包内相对路径。
     *
     * 【为什么统一去前导 `./`、统一用 `/`】同一个文件在不同工具里会被写成
     *   `./res/a.json`、`res\\a.json`、`/res/a.json` 三种样子。
     *   规范里只认一种，剩下的在解析时就抹平 —— 否则校验与解压两处
     *   会各自处理一遍，迟早不一致。
     */
    fun normalizePath(p: String): String {
        var s = p.trim().replace('\\', '/')
        while (s.startsWith("./")) {
            s = s.substring(2)
        }
        if (s.startsWith("/")) {
            s = s.substring(1)
        }
        return s
    }

    private fun readStringList(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val s = a.optString(i, "")
            if (s.isNotEmpty()) {
                out.add(s)
            }
        }
        return out
    }

    private fun readStringMap(o: JSONObject, key: String): Map<String, String> {
        val sub = o.optJSONObject(key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = sub.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = sub.optString(k, "")
            if (v.isNotEmpty()) {
                out[k] = normalizePath(v)
            }
        }
        return out
    }

    private fun mapToJson(m: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) {
            o.put(k, v)
        }
        return o
    }

    /**
     * 解析结果。
     *
     * 【为什么返回错误列表而不是抛异常】校验失败是**正常的业务分支**
     *   （用户拿了一个坏包），不是「意外」。用返回值表达，调用方就必须处理，
     *   不会被一个没被 catch 的异常冒到顶层变成崩溃。
     */
    data class ParseResult(val manifest: Manifest?, val errors: List<String>) {
        val ok: Boolean get() = manifest != null
    }
}