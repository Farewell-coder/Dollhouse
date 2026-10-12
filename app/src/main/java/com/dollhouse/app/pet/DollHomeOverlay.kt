package com.dollhouse.app.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import kotlin.math.roundToInt

/**
 * 【职责】「初始位置」的悬浮窗（r12 新增）：**盖在真实桌面上**，中间一张虚化人偶图，
 *   拖中心点定位置，底部操作栏（取消 / 恢复 / 保存）。
 *
 * 【需求原文】「初始位置直接跟那个屏幕范围一样 在屏幕上显示 不需要单开界面」——
 *   所以它和 [DollRangeOverlay] 是同一套形态，只是把「拖四条边定范围」换成
 *   「拖一个中心点定位置」。
 *
 * 【比例一致】需求明确「这个虚化图的比例 也是调整好的相同比例 并不是随机一个大小」：
 *   这里按 [DollProfile.scale] 换算显示尺寸，与桌宠实际大小一致。
 *
 * 【默认位置】用户没设过时用 50% × 80%（中下偏底）；「恢复」按钮也回到这里。
 *
 * 【权限】TYPE_APPLICATION_OVERLAY；未授权时 [show] 返回 false，调用方降级为 App 内页面。
 */
object DollHomeOverlay {

    /** 中心点直径（dp）。 */
    private const val DOT_DP = 26f

    /** 默认位置：中下偏底。 */
    private const val DEFAULT_RX = 0.5f
    private const val DEFAULT_RY = 0.8f

    private var wm: WindowManager? = null
    private var root: View? = null
    private var owner: ComposeHost.HostOwner? = null

    @JvmStatic
    fun isShowing(): Boolean = root != null

    /**
     * 弹出悬浮窗。返回 true 表示已显示；false 表示没权限 / 失败（调用方应降级）。
     */
    @JvmStatic
    fun show(ctx: Context, dollId: String, onDone: (() -> Unit)?): Boolean {
        if (root != null) {
            return true
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            return false
        }
        return try {
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = ComposeHost.createView(ctx) { Content(ctx, dollId, onDone) }
            val lp = WindowManager.LayoutParams()
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.type = if (Build.VERSION.SDK_INT >= 26) 2038 else 2002
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            lp.format = PixelFormat.TRANSLUCENT
            lp.gravity = Gravity.TOP or Gravity.START
            val o = ComposeHost.attachOverlayOwner(view)
            manager.addView(view, lp)
            wm = manager
            root = view
            owner = o
            true
        } catch (ignored: Throwable) {
            root = null
            owner = null
            wm = null
            false
        }
    }

    /** 关掉悬浮窗（幂等）。 */
    @JvmStatic
    fun hide() {
        val view = root ?: return
        root = null
        try {
            wm?.removeView(view)
        } catch (ignored: Throwable) {
        }
        wm = null
        owner?.onDestroy()
        owner = null
    }

    /* ============================ 内容 ============================ */

    @Composable
    private fun Content(ctx: Context, dollId: String, onDone: (() -> Unit)?) {
        val c = DhTokens.colors
        // 【比例一致】按这只人偶设定的比例显示，与桌宠一样大。
        val scalePct = remember(dollId) { DollProfile.scale(ctx, dollId) } / 100.0f
        val doll = remember(dollId) { DollStore.byId(ctx, dollId) }
        val sprite = remember(dollId) { DollStore.spriteOf(ctx, doll)?.asImageBitmap() }
        val init = remember(dollId) { DollProfile.homePivot(ctx, dollId) }
        var rx by remember(dollId) { mutableStateOf(init?.get(0) ?: DEFAULT_RX) }
        var ry by remember(dollId) { mutableStateOf(init?.get(1) ?: DEFAULT_RY) }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val den = LocalDensity.current
            val wPx = with(den) { maxWidth.toPx() }
            val hPx = with(den) { maxHeight.toPx() }
            val dotPx = DOT_DP * den.density

            // 虚化人偶铺底：按比例算尺寸，整体平移到 (rx, ry) —— 其视觉中心即中心点。
            if (sprite != null) {
                val basePx = with(den) { 150.dp.toPx() } * scalePct
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = (rx - 0.5f) * wPx
                            translationY = (ry - 0.5f) * hPx
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = sprite,
                        contentDescription = null,
                        modifier = Modifier
                            .size(with(den) { basePx.toDp() })
                            .alpha(0.35f)
                            .blur(6.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // 中心点：可拖动（拖它 = 挪人偶）。
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (rx * wPx - dotPx / 2f).roundToInt(),
                            (ry * hPx - dotPx / 2f).roundToInt()
                        )
                    }
                    .size(DOT_DP.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.acc)
                    .border(2.dp, c.card, RoundedCornerShape(999.dp))
                    .pointerInput(wPx, hPx) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            if (wPx > 0f && hPx > 0f) {
                                rx = (rx + drag.x / wPx).coerceIn(0f, 1f)
                                ry = (ry + drag.y / hPx).coerceIn(0f, 1f)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                DhKit.Icon(Icons.IC_CROSSHAIR, Design.SZ_XS, c.onAcc)
            }

            // 底部横向操作栏：取消 / 恢复 / 保存（钉在屏幕下方居中）。
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.card)
                    .border(1.dp, c.line, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DhKit.BarButton(Icons.IC_CLOSE, c.sub, "取消") {
                    hide()
                    onDone?.invoke()
                }
                Spacer(Modifier.width(16.dp))
                DhKit.BarButton(Icons.IC_ROTATE_CCW, c.sub, "恢复") {
                    // 【r12】恢复 = 回到默认位置（中下偏底）。
                    rx = DEFAULT_RX
                    ry = DEFAULT_RY
                }
                Spacer(Modifier.width(16.dp))
                DhKit.BarButton(Icons.IC_CHECK, c.acc, "保存") {
                    DollProfile.setHomePivot(ctx, dollId, rx, ry)
                    hide()
                    onDone?.invoke()
                }
            }
        }
    }

    /* 【r12-UI】原私有 BarButton 已上提到 [DhKit.BarButton]（同 DollRangeOverlay）。 */
}