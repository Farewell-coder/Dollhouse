package com.dollhouse.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.theme.*

/** 只增强卡片；普通段落由 ChatUi 的原文渲染路径显示。 */
@Composable fun BlockRenderer(text: String, enhanced: Boolean) {
    val blocks = remember(text) { BlockParser.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> key(block.blockId) {
            when (block) {
                is Block.Card -> CardBlock(block)
                is Block.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = DhTokens.colors.line)
                is Block.Heading -> Text(block.text, color = DhTokens.colors.title, fontFamily = DhTokens.fontsBold)
                is Block.CodeBlock -> Text(block.code, Modifier.background(DhTokens.colors.soft, RoundedCornerShape(10.dp)).padding(10.dp), color = DhTokens.colors.title, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                is Block.Paragraph -> if (enhanced) Text(block.text, color = DhTokens.colors.title, fontFamily = DhTokens.fonts, lineHeight = 22.sp)
            }
        } }
    }
}
@Composable private fun CardBlock(block: Block.Card) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(52.dp).background(DhTokens.colors.soft, RoundedCornerShape(14.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            DhKit.Icon(Icons.IC_PACKAGE_CHECK, Design.SZ_MD, DhTokens.colors.acc)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (block.title.isNotBlank()) Text(block.title, Modifier.background(DhTokens.colors.soft, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), color = DhTokens.colors.title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (block.body.isNotBlank()) Text(block.body, color = DhTokens.colors.title, fontFamily = DhTokens.fonts, lineHeight = 26.sp)
            block.sources.forEach { s -> Text(s.name + if (s.count > 0) " +${s.count}" else "", Modifier.background(DhTokens.colors.soft, RoundedCornerShape(99.dp)).padding(horizontal = 9.dp, vertical = 4.dp), color = DhTokens.colors.sub, fontSize = 12.sp) }
        }
    }
}