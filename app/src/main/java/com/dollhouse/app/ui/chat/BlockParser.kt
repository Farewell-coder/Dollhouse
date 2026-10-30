package com.dollhouse.app.ui.chat

/** 纯 Kotlin、按消息位置生成稳定 ID；流式半截内容不会抛异常。 */
object BlockParser {
    private val attr = Regex("([a-zA-Z]+)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s}]+))")
    @JvmStatic fun parse(text: String): List<Block> {
        if (!text.contains(":::card")) return markdown(text)
        // 普通消息保持原始文本；只有真实卡片标记才拆块。
        if (text.lineSequence().none { it.trimStart().startsWith(":::card") }) return markdown(text)
        val out = ArrayList<Block>(); val lines = text.split('\n'); var i = 0
        var plain = StringBuilder(); var fenced = false
        fun flush() { if (plain.isNotEmpty()) { out += markdown(plain.toString()); plain = StringBuilder() } }
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) { fenced = !fenced; plain.append(line).append('\n'); i++; continue }
            if (!fenced && line.trimStart().startsWith(":::card")) {
                flush()
                val header = line.trim()
                val body = ArrayList<String>(); val sources = ArrayList<Source>(); var closed = false; i++
                while (i < lines.size) {
                    val inner = lines[i]
                    if (inner.trim() == ":::") { closed = true; i++; break }
                    if (inner.trimStart().startsWith(":::source")) {
                        val a = attrs(inner); val name = a["name"].orEmpty()
                        if (name.isNotEmpty()) sources += Source(name, a["count"]?.toIntOrNull() ?: 0)
                    } else body += inner
                    i++
                }
                val title = body.firstOrNull { it.trimStart().startsWith("### ") }?.trim()?.removePrefix("### ")?.trim().orEmpty()
                val rest = body.filterNot { it.trimStart().startsWith("### ") }.joinToString("\n").trim()
                if (!closed && title.isEmpty() && rest.isEmpty()) out += Block.Paragraph("p-${out.size}", text.substring(text.indexOf(line)))
                else out += Block.Card("b-${out.size}", attrs(header)["icon"] ?: "archive", title, rest, sources)
                if (closed && i < lines.size) out += Block.Divider("d-${out.size}")
            } else { plain.append(line).append('\n'); i++ }
        }
        flush(); return out
    }
    private fun attrs(s: String): Map<String, String> = attr.findAll(s).associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
    private fun markdown(text: String): List<Block> {
        val out = ArrayList<Block>(); val buf = StringBuilder(); var code = false; var language: String? = null
        var blockIndex = 0; var lineIndex = 0
        fun nextId(kind: String) = "$kind-$lineIndex-${blockIndex++}"
        fun flush() { if (buf.isNotBlank()) out += Block.Paragraph("p-${out.size}", buf.toString().trim()); buf.clear() }
        text.split('\n').forEach { line ->
            if (line.trimStart().startsWith("```")) {
                if (code) { out += Block.CodeBlock("c-${out.size}", buf.toString().trimEnd(), language); buf.clear(); code = false; language = null }
                else { flush(); code = true; language = line.trim().removePrefix("```").ifBlank { null } }
            } else if (code) buf.append(line).append('\n')
            else if (line.startsWith("### ")) { flush(); out += Block.Heading("h-${out.size}", line.removePrefix("### ")) }
            else buf.append(line).append('\n')
        }
        if (code) out += Block.CodeBlock("c-${out.size}", buf.toString(), language) else flush()
        return out
    }
}