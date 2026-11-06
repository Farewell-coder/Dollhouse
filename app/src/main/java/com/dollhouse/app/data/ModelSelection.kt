package com.dollhouse.app.data

/**
 * 【职责】模型列表的纯逻辑筛选与待加入清单。
 *
 * 网络请求和写盘仍由页面负责；这里只负责「哪些名字应显示 / 哪些名字尚未加入」。
 * 这样筛选、批量添加的口径可以脱离 Android UI 单测，避免界面改动时把筛选语义改坏。
 */
object ModelSelection {

    /** 按名称过滤；忽略大小写，多个空格分词按 AND 匹配。 */
    @JvmStatic
    fun filter(models: List<String>, query: String): List<String> {
        val terms = query.trim().lowercase().split(' ', '　').filter { it.isNotEmpty() }
        return models.filter { name ->
            val hay = name.lowercase()
            terms.all { hay.contains(it) }
        }
    }

    /** 当前筛选结果里、尚未加入本供应商的模型；保持服务端原始顺序。 */
    @JvmStatic
    fun missing(models: List<String>, existing: Collection<String>): List<String> {
        val known = HashSet(existing)
        val seen = HashSet<String>()
        // 【重复名】同一个服务端偶尔会返回重复 id；否则批量加入会生成重复记录。
        return models.filter { it.isNotBlank() && !known.contains(it) && seen.add(it) }
    }
}
