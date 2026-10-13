package com.dollhouse.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】「暂未开放」占位页（r14）。
 *
 * 【用在哪】底栏第三格「说说」。用户定案原文：
 *   「那个底边栏 变成说说 然后那个界面暂时 不可进入 那个说说 标注一下未开放就好 不要太过于突兀」。
 *
 * 【为什么是「能进但只有一句话」而不是「点了没反应」】
 *   点了没反应会被读成「坏了」；进来看到一句「暂未开放」才知道是「还没做」，
 *   而且用户能进去看一眼、再原路返回，不会被卡住（返回键语义正常）。
 *
 * 【为什么不做成大标题 + 大图 + 大段文案】
 *   用户明确要求「不要太过于突兀」。本页刻意压得很轻：
 *   一枚低对比度图标 + 一行正文 + 一行小字，且**不画任何不透明底**，
 *   让全局背景照常透出来（与全 App 其它页面观感连续，不会像"另一个 App"）。
 */
object ComingSoonPage {

    /**
     * 通用占位内容。
     *
     * @param title    一句话主文案
     * @param hint     第二行小字说明（可空）
     * @param iconRes  图标（走 [Icons]，与全 App 图标语言一致）
     * @param sub      顶栏副标题（可空）
     */
    @Composable
    fun Content(
        title: String,
        hint: String? = null,
        iconRes: Int = Icons.IC_NEWSPAPER,
        sub: String? = null
    ) {
        val c = DhTokens.colors
        Column(modifier = Modifier.fillMaxSize()) {
            // 【为什么自带顶栏】本页作为 Tab 直接渲染（没有页面栈的返回键），
            //   需要一个标题让用户知道自己点进了哪一页。
            Box(modifier = Modifier.padding(horizontal = DhKit.PAD_CARD.dp)) {
                DhKit.TopBar(title = "说说", sub = sub)
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 40.dp)
                ) {
                    // 【低对比度】线条色（[DhTokens.colors].line）比副标题色更淡，
                    //   让这枚图标"退到背景里"，不抢视线 —— 这是"不突兀"的关键。
                    DhKit.Icon(iconRes = iconRes, sizeDp = Design.SZ_XL, color = c.line)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = title,
                        color = c.sub,
                        fontSize = UiKit.FS_BTN.sp,
                        fontFamily = DhTokens.fonts,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    if (!hint.isNullOrEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = hint,
                            color = c.line,
                            fontSize = UiKit.FS_SUB.sp,
                            fontFamily = DhTokens.fonts,
                            textAlign = TextAlign.Center
                        )
                    }
                    // 【底栏避让】主界面底栏是悬浮的（不在布局流里），内容要自己留出高度。
                    Spacer(Modifier.height(BottomNav.HEIGHT_DP.dp))
                }
            }
        }
    }

    /** 「说说」占位页（底栏第三格的内容）。 */
    @Composable
    fun Social() {
        Content(
            title = "功能开发中，敬请期待",
            hint = "这里以后会是你的动态",
            iconRes = Icons.IC_NEWSPAPER
        )
    }
}