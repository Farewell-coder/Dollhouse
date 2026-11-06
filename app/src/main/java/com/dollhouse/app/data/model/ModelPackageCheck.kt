package com.dollhouse.app.data.model

/**
 * 【职责】第三方模型包的来源与兼容性检查：导入前判断「能不能用、来自谁」。
 *
 * ## 为什么需要
 * 清单里其实已经有 `author` / `modelVersion` / `license` / `minFramework`
 * （见 [ModelPackage.Manifest]），但导入流程只校验「引擎是否支持、资源是否存在」，
 * 从不看这几项。结果是：
 * - 一个要求更新框架的包能被导入，然后在播放时表现为「动作不出来」，
 *   用户以为是包坏了，其实是版本不匹配；
 * - 来源与许可不展示，用户无从判断这个包能不能放心用。
 *
 * ## 本类只做判定
 * 不读 zip、不写盘、不弹窗；由调用方把清单读好后传进来。
 *
 * ## 为什么不拦截「没有作者」
 * 自制的包本来就不一定填作者。缺来源只是**提示**，不是错误 ——
 * 把自制包拦在门外，等于让这个功能只对少数人可用。
 */
object ModelPackageCheck {

    /** 本实现的包规范版本上限。 */
    const val SUPPORTED_SPEC = 1

    class Report(
        /** 阻止导入的原因；空 = 可以导入。 */
        val errors: List<String> = emptyList(),
        /** 不阻止导入，但必须让用户看到的提示。 */
        val warnings: List<String> = emptyList(),
        /** 来源描述（作者 / 版本 / 许可），可直接显示。 */
        val origin: String = ""
    ) {
        fun ok(): Boolean = errors.isEmpty()
    }

    /**
     * 检查一个清单。
     *
     * @param specVersion    包声明的规范版本（0 或负数表示缺失）
     * @param minFramework   包要求的最低框架版本（可空）
     * @param author         作者（可空）
     * @param modelVersion   包自身版本（可空）
     * @param license        许可（可空）
     * @param framework      当前框架版本，例如 "0.2.6"
     */
    @JvmStatic
    fun check(
        specVersion: Int,
        minFramework: String?,
        author: String?,
        modelVersion: String?,
        license: String?,
        framework: String
    ): Report {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        // ① 规范版本：更高版本意味着本实现可能缺字段语义，导入后会「看着成功、实际不对」。
        if (specVersion <= 0) {
            errors.add("包没有声明规范版本（specVersion）")
        } else if (specVersion > SUPPORTED_SPEC) {
            errors.add(
                "这个包用了更新的规范（v" + specVersion + "），当前应用只支持到 v" +
                    SUPPORTED_SPEC + "，请先升级应用"
            )
        }

        // ② 最低框架版本：要求高于当前的，直接拒绝 —— 否则装进去也播不出来。
        val need = minFramework?.trim().orEmpty()
        if (need.isNotEmpty()) {
            val cmp = compareVersion(framework, need)
            if (cmp == null) {
                warnings.add("包声明的最低框架版本「" + need + "」无法比较，已忽略")
            } else if (cmp < 0) {
                errors.add(
                    "这个包需要框架 " + need + " 或更新，当前是 " + framework + "，请先升级应用"
                )
            }
        }

        // ③ 来源与许可：只做提示，不拦截。
        val a = author?.trim().orEmpty()
        if (a.isEmpty()) {
            warnings.add("这个包没有写作者，来源不明，请确认来源后再使用")
        }
        val lic = license?.trim().orEmpty()
        if (lic.isEmpty()) {
            warnings.add("这个包没有声明许可协议")
        }

        val origin = buildOrigin(a, modelVersion?.trim().orEmpty(), lic)
        return Report(errors, warnings, origin)
    }

    /** 拼一句来源描述；三项都空时返回空串。 */
    private fun buildOrigin(author: String, version: String, license: String): String {
        val parts = ArrayList<String>()
        if (author.isNotEmpty()) {
            parts.add("作者 " + author)
        }
        if (version.isNotEmpty()) {
            parts.add("版本 " + version)
        }
        if (license.isNotEmpty()) {
            parts.add("许可 " + license)
        }
        return parts.joinToString(" · ")
    }

    /**
     * 比较版本号（点分段，纯数字）。
     *
     * @return 负数 = current 更旧；0 = 相同；正数 = current 更新；无法比较时 null
     */
    @JvmStatic
    fun compareVersion(current: String?, required: String?): Int? {
        val a = parse(current) ?: return null
        val b = parse(required) ?: return null
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = if (i < a.size) a[i] else 0
            val y = if (i < b.size) b[i] else 0
            if (x != y) {
                return x - y
            }
        }
        return 0
    }

    private fun parse(v: String?): List<Int>? {
        val t = v?.trim().orEmpty()
        if (t.isEmpty()) {
            return null
        }
        val parts = t.split('.')
        val out = ArrayList<Int>(parts.size)
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            out.add(n)
        }
        return out
    }
}