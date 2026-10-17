package com.dollhouse.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【职责】模型包规范 v1 的单元测试 —— 覆盖任务书 §3.7 的九条校验规则与安全红线。
 *
 * ## 为什么这份测试覆盖的是「安全」而不只是「功能」
 * 模型包是**第三方输入**。任务书 §3.7「安全红线」明确要求：
 * > 禁止执行模型包中的任何脚本、动态代码、可执行文件
 * > 拒绝 `.dex / .jar / .so / .apk / .sh / .js / .py / .lua`
 * > 解包时必须校验路径不逃出目标目录
 *
 * 这些规则一旦失效，后果不是「功能不好用」而是「装个模型包把手机搞坏」。
 * 所以每一条红线都有一条对应测试。
 *
 * ## 关于 org.json
 * `ModelPackage.parse` / `write` 依赖 `org.json`；本模块的 `build.gradle`
 * 为单元测试引入了 JVM 版 `org.json:json`，所以这些测试在 JVM 上可以直接跑，
 * 不需要 Robolectric、不需要设备。
 */
class ModelPackageTest {

    private fun manifest(
        specVersion: Int = 1,
        id: String = "demo",
        name: String = "示例",
        engine: String = "lottie",
        entry: String = "res/idle.json",
        actions: Map<String, String> = mapOf("idle" to "res/idle.json")
    ) = ModelPackage.Manifest(
        specVersion = specVersion, id = id, name = name,
        engine = engine, entry = entry, actions = actions
    )

    // ============================ 路径规范化 ============================

    @Test
    fun normalizePath_stripsPrefixesAndBackslashes() {
        assertEquals("res/a.json", ModelPackage.normalizePath("./res/a.json"))
        assertEquals("res/a.json", ModelPackage.normalizePath("res\\a.json"))
        assertEquals("res/a.json", ModelPackage.normalizePath("/res/a.json"))
        assertEquals("res/a.json", ModelPackage.normalizePath("  res/a.json  "))
        assertEquals("", ModelPackage.normalizePath(""))
    }

    /**
     * 【安全红线】路径穿越必须被识别。
     *
     * 覆盖形态：直接 `..`、藏在中间、`..` 与反斜杠组合、冒号、空路径。
     *
     * 【注意反斜杠的两种命运】`whyUnsafePath` 会先 `normalizePath`（把反斜杠换成斜杠），
     *   所以 `res\\..\\a` 会先命中「含 ..」这条**更严重**的判断 —— 这个顺序是对的
     *   （报更危险的原因比报反斜杠更有用）。要单独验证「反斜杠」分支，
     *   必须用**不含 ..** 的样例（下面第三个断言）。
     */
    @Test
    fun whyUnsafePath_rejectsTraversal() {
        assertNull(ModelValidator.whyUnsafePath("res/a.json"))
        assertEquals("含上级目录引用（..）", ModelValidator.whyUnsafePath("../a.json"))
        assertEquals("含上级目录引用（..）", ModelValidator.whyUnsafePath("res/../../etc/passwd"))
        // 反斜杠 + .. 的组合：先命中更严重的「..」。
        assertEquals("含上级目录引用（..）", ModelValidator.whyUnsafePath("res\\..\\a"))
        // 只有反斜杠、没有 .. 时，才走到反斜杠那条分支。
        assertEquals("含反斜杠（可能是绕过检查的尝试）", ModelValidator.whyUnsafePath("res\\a.png"))
        assertEquals("含冒号", ModelValidator.whyUnsafePath("file:/x"))
        assertEquals("路径为空", ModelValidator.whyUnsafePath(""))
    }

    /** 纯 `..` 在任何位置都要拒（`a/../b` 与 `../b` 一样危险）。 */
    @Test
    fun whyUnsafePath_rejectsDotDotAnywhere() {
        assertEquals("含上级目录引用（..）", ModelValidator.whyUnsafePath("a/../b"))
        assertEquals("含上级目录引用（..）", ModelValidator.whyUnsafePath("a/b/.."))
        assertEquals("含上级目录引用（..）", ModelValidator.whyUnsafePath("./.."))
    }

    // ============================ 引擎推断 ============================

    @Test
    fun engineOf_infersByExtension() {
        assertEquals("lottie", ModelPackage.engineOf("res/a.json"))
        assertEquals("animimage", ModelPackage.engineOf("res/a.gif"))
        assertEquals("animimage", ModelPackage.engineOf("res/a.webp"))
        assertEquals("sprite", ModelPackage.engineOf("res/f0001.png"))
        assertEquals("sprite", ModelPackage.engineOf("res/noext"))
    }

    @Test
    fun extOf_isCaseInsensitiveAndHandlesEdgeCases() {
        assertEquals("json", ModelPackage.extOf("A.JSON"))
        assertEquals("png", ModelPackage.extOf("dir.x/a.png"))
        assertEquals("", ModelPackage.extOf("noext"))
        assertEquals("", ModelPackage.extOf("trailingdot."))
    }

    // ============================ 九条校验规则 ============================

    /** ① specVersion 过高 → **拒绝**。 */
    @Test
    fun validate_rejectsFutureSpecVersion() {
        val r = ModelValidator.validate(
            manifest(specVersion = 99),
            listOf("res/idle.json")
        )
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("规范版本过高") })
    }

    /** ② specVersion 偏旧 → **只警告**（宽松解析）。 */
    @Test
    fun validate_warnsOnOlderSpecVersion() {
        val r = ModelValidator.validate(
            manifest(specVersion = 0),
            listOf("res/idle.json")
        )
        assertTrue(r.ok)
        assertTrue(r.warnings.any { it.contains("偏旧") })
    }

    /** ③ 资源缺失 → **拒绝** + 列出缺失文件。 */
    @Test
    fun validate_rejectsMissingAsset() {
        val r = ModelValidator.validate(
            manifest(entry = "res/missing.json"),
            listOf("res/idle.json")
        )
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("缺少清单声明的资源") })
        assertTrue(r.errors.any { it.contains("res/missing.json") })
    }

    /** ④ 引擎不支持 → **拒绝** + 提示可用引擎。 */
    @Test
    fun validate_rejectsUnsupportedEngine() {
        val r = ModelValidator.validate(
            manifest(engine = "flash"),
            listOf("res/idle.json")
        )
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("引擎不支持") })
        assertTrue(r.errors.any { it.contains("sprite") })
    }

    /**
     * ⑤ 含脚本 / 可执行文件 → **拒绝**（安全红线）。
     *
     * 逐个后缀验证：任务书点名的 `.dex / .jar / .so / .apk / .sh / .js / .py / .lua`
     * 一个都不能漏。
     */
    @Test
    fun validate_rejectsExecutablesAndScripts() {
        for (ext in listOf("dex", "jar", "so", "apk", "sh", "js", "py", "lua")) {
            val r = ModelValidator.validate(
                manifest(),
                listOf("res/idle.json", "res/evil.$ext")
            )
            assertFalse("后缀 .$ext 必须被拒", r.ok)
            assertTrue(
                "拒绝理由应点名 .$ext",
                r.errors.any { it.contains("可执行") || it.contains("白名单") }
            )
        }
    }

    /** ⑥ 白名单外的资源格式 → **拒绝**（白名单制，不是黑名单制）。 */
    @Test
    fun validate_rejectsUnknownExtension() {
        val r = ModelValidator.validate(
            manifest(),
            listOf("res/idle.json", "res/data.wasm")
        )
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("白名单") })
    }

    /** 白名单内的格式必须放行（别把正常包也拦了）。 */
    @Test
    fun validate_acceptsAllWhitelistedExtensions() {
        val files = listOf(
            "manifest.json", "res/idle.json",
            "res/a.png", "res/b.jpg", "res/c.jpeg", "res/d.webp", "res/e.gif", "res/readme.txt"
        )
        val r = ModelValidator.validate(manifest(entry = "res/idle.json"), files)
        assertTrue("白名单格式不该被拦：" + r.errors, r.ok)
    }

    /** ⑦ 重名 → 只**警告**，仍然可导入（覆盖是正常操作）。 */
    @Test
    fun validate_warnsOnDuplicateId() {
        val r = ModelValidator.validate(
            manifest(id = "demo"),
            listOf("res/idle.json"),
            existingIds = listOf("demo")
        )
        assertTrue("重名不该拦住导入", r.ok)
        assertTrue(r.warnings.any { it.contains("覆盖") })
    }

    /** ⑧ 路径穿越（zip 条目）→ **拒绝**。 */
    @Test
    fun validate_rejectsTraversalEntry() {
        val r = ModelValidator.validate(
            manifest(),
            listOf("res/idle.json", "../outside.txt")
        )
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("不安全路径") })
    }

    /** 路径穿越写在**清单里**（而非 zip 条目）同样要拒。 */
    @Test
    fun validate_rejectsTraversalInManifest() {
        val r = ModelValidator.validate(
            manifest(entry = "../../secret.json"),
            listOf("res/idle.json")
        )
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("不安全") })
    }

    /** ⑨ 未知动作语义 → 只**警告**并忽略该条（允许未来规范新增语义）。 */
    @Test
    fun validate_warnsOnUnknownSemantic() {
        val r = ModelValidator.validate(
            manifest(actions = mapOf("idle" to "res/idle.json", "dance" to "res/idle.json")),
            listOf("res/idle.json")
        )
        assertTrue("未知语义不该拦住整个包", r.ok)
        assertTrue(r.warnings.any { it.contains("dance") })
    }

    /** 条目数超限 → 拒绝（防 zip bomb 的第一道门）。 */
    @Test
    fun validate_rejectsTooManyEntries() {
        val many = ArrayList<String>()
        many.add("res/idle.json")
        for (i in 0 until ModelPackage.MAX_ENTRIES + 10) {
            many.add("res/f$i.png")
        }
        val r = ModelValidator.validate(manifest(), many)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("条目过多") })
    }

    /** 正常包必须通过且无错误（防止校验器过严，把好包也拦了）。 */
    @Test
    fun validate_acceptsWellFormedPackage() {
        val r = ModelValidator.validate(
            manifest(),
            listOf("manifest.json", "preview.png", "res/idle.json", "res/images/a.png")
        )
        assertTrue("好包不该被拦：" + r.errors, r.ok)
        assertTrue(r.errors.isEmpty())
    }

    // ============================ 目录名安全 ============================

    /**
     * 【安全红线】model id 会被拼进文件路径，必须先净化。
     *
     * 【为什么这条必须有测试】`ModelRepository.modelDir()` 直接用 id 拼目录。
     *   若 id 是 `../../x`，解压/删除就会跑到目标目录之外 ——
     *   这是「装个模型包把别人数据删了」级别的漏洞。
     */
    @Test
    fun safeName_preventsPathEscape() {
        // 【关键判据不是「字符串长什么样」，而是「结果里不可能出现路径分隔符」】
        //   `../../x` → `_.._x`：斜杠已被换成下划线，于是它变成**一个**目录名，
        //   拼出来是 `<root>/_.._x` —— root 底下的普通子目录，出不去。
        //   （`..` 作为普通字符留在名字里无害；真正危险的是分隔符。）
        assertEquals("_.._x", ModelRepository.safeName("../../x"))
        assertEquals("a_b", ModelRepository.safeName("a/b"))
        assertEquals("a_b", ModelRepository.safeName("a\\b"))
        assertEquals("model", ModelRepository.safeName(""))
        // 纯点会被剥掉（避免变成隐藏目录 / `.` / `..`）。
        assertEquals("model", ModelRepository.safeName("..."))
        assertEquals("model", ModelRepository.safeName("."))
        assertEquals("demo-1.0", ModelRepository.safeName("demo-1.0"))
        // 兜底断言：任何输入都不得产生路径分隔符。
        for (bad in listOf("../../etc/passwd", "a/b/c", "..\\..\\x", "/abs/path")) {
            val s = ModelRepository.safeName(bad)
            assertFalse("净化后不该还有斜杠：" + s, s.contains('/'))
            assertFalse("净化后不该还有反斜杠：" + s, s.contains('\\'))
        }
    }

    // ============================ manifest 读写 ============================

    /** 往返：write → parse 后字段一致（导出再导入不会丢信息）。 */
    @Test
    fun manifest_roundTrip() {
        val m = ModelPackage.Manifest(
            specVersion = 1, id = "demo", name = "示例", engine = "lottie",
            entry = "res/idle.json", author = "某人", modelVersion = "2",
            assets = listOf("res/idle.json"), preview = "preview.png",
            actions = mapOf("idle" to "res/idle.json", "tap" to "res/tap.json"),
            behaviors = mapOf("idleDelay" to "10"), capabilities = listOf("motion"),
            license = "CC0", minFramework = "0.0.7"
        )
        val parsed = ModelPackage.parse(ModelPackage.write(m))
        assertTrue(parsed.errors.toString(), parsed.ok)
        assertEquals(m, parsed.manifest)
    }

    /** 必需字段缺失 → 解析失败并**指出哪个字段**（不要只说「格式错误」）。 */
    @Test
    fun parse_reportsWhichRequiredFieldIsMissing() {
        val r = ModelPackage.parse("""{"specVersion":1,"id":"x","name":"y"}""")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("engine") })
        assertTrue(r.errors.any { it.contains("entry") })
    }

    /** 非法 JSON → 解析失败但不抛异常（坏包是正常业务分支）。 */
    @Test
    fun parse_handlesBrokenJson() {
        val r = ModelPackage.parse("{not json")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("不是合法 JSON") })
        assertFalse(ModelPackage.parse(null).ok)
        assertFalse(ModelPackage.parse("").ok)
    }

    /**
     * 未知字段**忽略且不报错**（任务书明确要求）。
     *
     * 【为什么重要】别人用更新版规范做的包，只要没有新能力声明，
     *   就该能读。若解析层对未知字段报错，用户会看到「这个包不能用」，
     *   而真正原因只是「它多写了一个我们不认识的字段」。
     */
    @Test
    fun parse_ignoresUnknownFields() {
        // 【为什么用一个普通字符串而不是多行字面量】
        //   初版把两段字符串拼起来时漏了一个引号，结果 JSON 本身非法，
        //   测试失败原因是「不是合法 JSON」而不是「未知字段没被忽略」——
        //   失败信息指向了错误的地方。这里用一个可肉眼核对的完整 JSON。
        val json = "{" +
            "\"specVersion\":1," +
            "\"id\":\"x\"," +
            "\"name\":\"y\"," +
            "\"engine\":\"sprite\"," +
            "\"entry\":\"res/a.png\"," +
            "\"futureField\":{\"a\":1}," +
            "\"anotherNew\":true" +
            "}"
        val r = ModelPackage.parse(json)
        assertTrue(r.errors.toString(), r.ok)
        assertEquals("x", r.manifest?.id)
    }

    /** 缺省的可选字段取空值（不能是 null，否则上层到处判空）。 */
    @Test
    fun parse_optionalFieldsDefaultToEmpty() {
        val r = ModelPackage.parse(
            """{"specVersion":1,"id":"x","name":"y","engine":"sprite","entry":"res/a.png"}"""
        )
        val m = r.manifest!!
        assertEquals("", m.author)
        assertEquals("", m.preview)
        assertTrue(m.assets.isEmpty())
        assertTrue(m.actions.isEmpty())
        assertTrue(m.capabilities.isEmpty())
    }

    /** 供 UI 显示的一行摘要（错误优先）。 */
    @Test
    fun resultSummary_prefersErrors() {
        val r = ModelValidator.Result(errors = listOf("坏了"), warnings = listOf("提醒"))
        assertTrue(r.summary().contains("坏了"))
        assertTrue(ModelValidator.Result(emptyList(), emptyList()).summary().contains("通过"))
    }
}