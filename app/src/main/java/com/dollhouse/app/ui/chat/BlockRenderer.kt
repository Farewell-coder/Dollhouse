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
import com.dollhouse.app.ui.design.DhDesign
import com.dollhouse.app.ui.theme.*
import androidx.compose.ui.Alignment

/** 只增强卡片；普通段落由 ChatUi 的原文渲染路径显示。 */
@Composable fun BlockRenderer(text: String, enhanced: Boolean) {
    val blocks = remember(text) { BlockParser.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> key(block.blockId) {
            when (block) {
                is Block.Card -> CardBlock(block)
                is Block.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = DhTokens.colors.line)
                is Block.Heading -> Text(block.text, color = DhTokens.colors.title, fontFamily = DhTokens.fontsBold)
                is Block.CodeBlock -> Text(block.code, Modifier.background(DhTokens.colors.soft, RoundedCornerShape(DhDesign.Radius.md.dp) /* 原 10dp -> Radius.md(12)：代码块与卡片同族 */).padding(DhDesign.Space.md.dp), color = DhTokens.colors.title, fontFamily = FontFamily.Monospace, fontSize = DhTokens.type.caption.fontSize, lineHeight = DhTokens.type.caption.lineHeight)
                is Block.Paragraph -> if (enhanced) Text(block.text, color = DhTokens.colors.title, fontFamily = DhTokens.fontsBody, lineHeight = 22.sp)
            }
        } }
    }
}
@Composable private fun CardBlock(block: Block.Card) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // 【修·图标与文字错位】Row 默认 Top 对齐，而文字有行高 —— 图标会浮在上面、
        //   文字沉在下面，看起来就是「符号错位」。图标座与文字必须 CenterVertically。
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 【圆角收敛】原 14dp 不在阶梯上；这是图标座，与 IconSeatShape.ROUNDED 同为 12。
        Box(Modifier.size(52.dp).background(DhTokens.colors.soft, RoundedCornerShape(DhDesign.Radius.md.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            DhKit.Icon(Icons.IC_PACKAGE_CHECK, Design.SZ_MD, DhTokens.colors.acc)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (block.title.isNotBlank()) Text(block.title, Modifier.background(DhTokens.colors.soft, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), color = DhTokens.colors.title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (block.body.isNotBlank()) Text(block.body, color = DhTokens.colors.title, fontFamily = DhTokens.fontsBody, lineHeight = 26.sp)
            block.sources.forEach { s -> Text(s.name + if (s.count > 0) " +${s.count}" else "", Modifier.background(DhTokens.colors.soft, RoundedCornerShape(DhDesign.Radius.pill.dp)).padding(horizontal = 9.dp, vertical = 4.dp), color = DhTokens.colors.sub, fontSize = DhTokens.type.caption.fontSize) }
            // 【v0.2.3·不加行高】来源药丸是单行短标签，高度由 padding 决定；
            //   12sp 配 18sp 行高会把 4dp 上下留白吃掉，反而更局促。
        }
    }
}