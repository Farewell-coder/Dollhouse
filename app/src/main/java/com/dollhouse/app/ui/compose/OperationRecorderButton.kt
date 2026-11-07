package com.dollhouse.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.core.Logs
import com.dollhouse.app.core.OperationRecorder
import com.dollhouse.app.ui.compose.DhTokens

/** 仅应用内显示，不使用系统悬浮窗权限，也不会覆盖其他应用。 */
@Composable fun OperationRecorderButton() {
    if (!Logs.isOn()) return
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().padding(end = 16.dp, bottom = 100.dp), contentAlignment = Alignment.BottomEnd) {
        Box(Modifier.size(56.dp).background(DhTokens.colors.acc, CircleShape).pressable {
            OperationRecorder.toggle(ctx)
        }, contentAlignment = Alignment.Center) {
            // 【特例·不进令牌】56dp 圆钮内单行两字，caption 档会带行高把文字压偏（原注释已记录）。
            Text(
                if (OperationRecorder.recording) "停止" else "记录",
                color = DhTokens.colors.onAcc,
                fontSize = 12.sp
            )
            // 【v0.2.3·不加行高】56dp 圆形按钮里是单行两字，行高不影响可读性，
            //   加了会让文字在圆里偏下。
        }
    }
}