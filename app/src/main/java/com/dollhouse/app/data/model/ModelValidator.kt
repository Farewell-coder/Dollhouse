package com.dollhouse.app.data.model

import com.dollhouse.app.data.DollAnim

/**
 * 【职责】模型包校验器 —— 把「这个包能不能导入」变成一组**可单测的纯判断**。
 *
 * ## 为什么校验要独立成一层
 * 导入是个有副作用的动作（写文件、改配置、登记动作）。如果校验混在导入流程里，
 * 就只有「真的导一次」才知道包好不好 —— 而坏包导入到一半失败，用户会留下
 * 半套数据，还得自己收拾。**先把关，再落地**：校验全过才动磁盘。
 *
 * ## 本类不碰 Android（除了 org.json 之外零依赖）
 * 任务书 §3.9 要求这些规则能在 JVM 上单测。所以输入是
 * [ModelPackage.Manifest] + 一份「包内都有哪些文件」的名字清单，
 * 输出是错误 / 警告列表 —— 没有任何 I/O。
 *
 * ## 九条规则（任务书 §3.7 逐条对应）
 * | 规则 | 处置 |
 * |---|---|
 * | `specVersion` 高于当前 | **拒绝** + 明确提示 |
 * | `specVersion` 低于当前 | 宽松解析（只警告） |
 * | 未知字段 | 忽略（解析层已保证不报错） |
 * | 必需字段缺失 | **拒绝** + 指出哪个字段（解析层报） |
 * | 资源缺失 | **拒绝** + 列出缺失文件 |
 * | 引擎不支持 | **拒绝** + 提示可用引擎 |
 * | 含脚本 / 可执行文件 | **拒绝**（安全拦截） |
 * | 重名 | **警告**（允许改名或覆盖） |
 * | 路径穿越（`../`） | **拒绝** |
 */
object ModelValidator {

    /**
     * 校验结果。
     *
     * 【为什么区分 errors 与 warnings】「重名」和「规范版本偏旧」不该拦住导入 ——
     *   用户完全可以就是想把同一个模型重装一遍。把它们混进 errors 会让
     *   正常操作被拦，用户只能想办法绕过校验（更糟）。
     */
    data class Result(
        val errors: List<String>,
        val warnings: List<String>
    ) {
        val ok: Boolean get() = errors.isEmpty()

        /** 供 UI 直接显示的一行摘要。 */
        fun summary(): String {
            return when {
                errors.isNotEmpty() -> "导入被拒绝：" + errors.first()
                warnings.isNotEmpty() -> "可以导入，但有提醒：" + warnings.first()
                else -> "校验通过"
            }
        }
    }

    /**
     * 全量校验。
     *
     * @param manifest 已解析的清单（解析失败时不会走到这里）。
     * @param entries  包内**全部文件**的相对路径（zip 条目名）。
     * @param existingIds 目标人偶上已存在的模型包 id（判断重名）。
     */
    fun validate(
        manifest: ModelPackage.Manifest,
        entries: List<String>,
        existingIds: Collection<String> = emptyList()
    ): Result {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val norm = entries.map { ModelPackage.normalizePath(it) }.filter { it.isNotEmpty() }

        // ---------- ① 规范版本 ----------
        if (manifest.specVersion > ModelPackage.SPEC_VERSION) {
            // 【为什么不尝试兼容】高版本规范里的「必需字段」本版本不认识，
            //   猜着解析的后果是某个动作静默不生效 —— 用户只会觉得「导入的包坏了」。
            //   直接拒绝并说清版本，用户就明白该升级 App。
            errors.add(
                "模型包规范版本过高（包 specVersion=" + manifest.specVersion +
                    "，本版最高支持 " + ModelPackage.SPEC_VERSION + "），请升级 App"
            )
        } else if (manifest.specVersion < ModelPackage.SPEC_VERSION) {
            warnings.add("模型包规范版本偏旧（" + manifest.specVersion + "），将按兼容方式解析")
        }

        // ---------- ② 路径穿越 / 非法路径 ----------
        for (e in norm) {
            val bad = whyUnsafePath(e)
            if (bad != null) {
                errors.add("包含不安全路径「" + e + "」：" + bad)
            }
        }
        // 入口与各资源路径同样要查（它们可能指向包外）。
        for (p in allPaths(manifest)) {
            val bad = whyUnsafePath(p)
            if (bad != null) {
                errors.add("清单里的路径「" + p + "」不安全：" + bad)
            }
        }

        // ---------- ③ 危险文件类型（安全红线） ----------
        for (e in norm) {
            val ext = ModelPackage.extOf(e)
            if (ModelPackage.DANGEROUS_EXT.contains(ext)) {
                errors.add("含可执行/脚本文件，按安全红线拒绝：「" + e + "」")
            } else if (ext.isNotEmpty() && !ModelPackage.SAFE_EXT.contains(ext)) {
                errors.add("资源格式不在白名单内：「" + e + "」（允许：" + ModelPackage.SAFE_EXT.joinToString("/") + "）")
            }
        }

        // ---------- ④ 规模上限（防 zip bomb） ----------
        if (norm.size > ModelPackage.MAX_ENTRIES) {
            errors.add("条目过多（" + norm.size + " 个，上限 " + ModelPackage.MAX_ENTRIES + "）")
        }

        // ---------- ⑤ 引擎支持 ----------
        if (!DollAnim.ENGINES.contains(manifest.engine)) {
            // 【为什么不静默回落成 sprite】回落之后用户的包「导入成功但画出来是空白」，
            //   而真正的原因（引擎名写错）反而被藏起来了。
            errors.add(
                "引擎不支持：" + manifest.engine +
                    "（当前可用：" + DollAnim.ENGINES.joinToString("/") + "）"
            )
        }

        // ---------- ⑥ 资源存在性 ----------
        val missing = ArrayList<String>()
        for (p in allPaths(manifest)) {
            if (p.isEmpty()) {
                continue
            }
            if (!norm.contains(p)) {
                missing.add(p)
            }
        }
        if (missing.isNotEmpty()) {
            errors.add("包内缺少清单声明的资源：" + missing.joinToString("、"))
        }

        // ---------- ⑦ 动作语义 ----------
        for ((semantic, path) in manifest.actions) {
            if (!isKnownSemantic(semantic)) {
                // 【为什么只是警告】语义表是「对外契约」，未来会加（任务书允许新增不允许改名）。
                //   遇到比本版新的语义 ID，正确的做法是「忽略这一条、把别的动作导进来」，
                //   而不是让整个包导不进来。
                warnings.add("忽略未知动作语义「" + semantic + "」（本版不认识，可能是更新版规范新增的）")
                continue
            }
            val e = ModelPackage.extOf(path)
            if (e.isEmpty()) {
                errors.add("动作「" + semantic + "」的资源没有扩展名，无法判断引擎：" + path)
            }
        }

        // ---------- ⑧ 重名 ----------
        if (existingIds.contains(manifest.id)) {
            warnings.add("已存在同 id 的模型包「" + manifest.id + "」，导入将覆盖它")
        }

        return Result(errors, warnings)
    }

    /**
     * 路径是否不安全；不安全时返回原因。
     *
     * 【为什么单独抽出来】「路径穿越」这个判断要在三处用（zip 条目 / 入口 / 各资源），
     *   写三遍必然有一处漏。抽成一个函数，改规则只改这里。
     */
    fun whyUnsafePath(path: String): String? {
        val p = ModelPackage.normalizePath(path)
        if (p.isEmpty()) {
            return "路径为空"
        }
        // `..` 出现在任意一段都是穿越（`a/../../b` 与 `../b` 一样危险）。
        val segs = p.split('/')
        for (seg in segs) {
            if (seg == "..") {
                return "含上级目录引用（..）"
            }
        }
        if (p.contains(':')) {
            // Windows 盘符 / `file:` 之类，一律视为不安全（包内路径不该有冒号）。
            return "含冒号"
        }
        // 绝对路径在 normalizePath 里已去前导斜杠；若还有反斜杠说明有意绕过。
        if (path.contains('\\')) {
            return "含反斜杠（可能是绕过检查的尝试）"
        }
        return null
    }

    private fun allPaths(m: ModelPackage.Manifest): List<String> {
        val out = ArrayList<String>()
        out.add(ModelPackage.normalizePath(m.entry))
        if (m.preview.isNotEmpty()) {
            out.add(ModelPackage.normalizePath(m.preview))
        }
        out.addAll(m.assets.map { ModelPackage.normalizePath(it) })
        out.addAll(m.actions.values.map { ModelPackage.normalizePath(it) })
        return out
    }

    /**
     * 是否是本版认识的标准动作语义。
     *
     * 【为什么在这里写一份而不是引用 ActionSemantic】`ActionSemantic` 在
     *   `pet.behavior` 包（行为层），本类在数据层。数据层引用行为层会让
     *   「模型包导入」这个纯数据流程依赖上运行时的动作调度 —— 不值得。
     *   取值保持同源，由单测 `ModelPackageTest` 锁住一致性。
     */
    fun isKnownSemantic(semantic: String): Boolean {
        return listOf("idle", "tap", "drag", "longpress", "appear", "disappear").contains(semantic)
    }
}