package com.dollhouse.app.ui.chat

data class Source(val name: String, val count: Int = 0)
sealed interface Block {
    val blockId: String
    data class Heading(override val blockId: String, val text: String) : Block
    data class Paragraph(override val blockId: String, val text: String) : Block
    data class CodeBlock(override val blockId: String, val code: String, val language: String? = null) : Block
    data class Card(override val blockId: String, val icon: String, val title: String, val body: String, val sources: List<Source>) : Block
    data class Divider(override val blockId: String) : Block
}
