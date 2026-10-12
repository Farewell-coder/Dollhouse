package com.dollhouse.app.pet

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.PetService
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
import com.dollhouse.app.ui.compose.pressable
import com.dollhouse.app.ui.theme.Design
import com.dollhouse.app.ui.theme.Icons
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 【职责】「屏幕范围」的悬浮窗（r12 改）：**盖在真实桌面上**，四周一条可拖的虚线框，
 *   底部一条横向操作栏（取消 / 恢复 / 旋转 / 保存）。
 *
 * 【r12 的变化】
 *   ① 虚线框**不受屏幕限制**：可以拖到屏幕之外，人偶也能走到屏幕外
 *      （底层 [DollProfile.bounds] 的值域已放宽到 -1.0 ~ 2.0）；
 *   ② 中段**双指捏合**可整体缩放，锚点是双指的实时中点（需求定案）；
 *   ③ 底部操作栏钉在**屏幕下方居中**，不超出屏幕、按键一定点得到；
 *   ④ 新增「恢复」：把框拉回出厂默认（整屏内缩 24dp），防止框被拖丢；
 *   ⑤ 拖出框的松手落点 = 吸到**最近的框边**（由 [PetWindow.settle] 落地）。
 *
 * 【框平时是隐藏的】日常桌面上不画虚线；只有进来调整时才显示。
 *
 * 【权限】TYPE_APPLICATION_OVERLAY，需 SYSTEM_ALERT_WINDOW（manifest 已声明）。
 *   未授权时 [show] 返回 false，调用方降级为 App 内页面（DollRangePage）。
 */
object DollRangeOverlay {

    /** 边命中宽度（dp）：按下点离哪条边最近就拖哪条。 */
    private const val EDGE_HIT_DP = 48f

    /** 虚线线宽 / 实段 / 空段（dp）。 */
    private const val DASH_WIDTH_DP = 2f
    private const val DASH_ON_DP = 12f
    private const val DASH_OFF_DP = 8f

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
            // 【不抢焦点】拖框不需要输入焦点；抢焦点会把下面的桌面按键吞掉。
            // 【LAYOUT_IN_SCREEN】坐标从屏幕顶边算，虚线框才能贴到真正的屏幕边缘之外。
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
        val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val orient = remember(land) { DollProfile.orientOf(land) }
        val init = remember(dollId, orient) { DollProfile.bounds(ctx, dollId, orient) }
        var l by remember(dollId, orient) { mutableStateOf(init[0]) }
        var t by remember(dollId, orient) { mutableStateOf(init[1]) }
        var r by remember(dollId, orient) { mutableStateOf(init[2]) }
        var b by remember(dollId, orient) { mutableStateOf(init[3]) }
        var flipped by remember(dollId) { mutableStateOf(DollProfile.flip(ctx, dollId)) }

        // 【为什么用 rememberUpdatedState】手势检测器只装一次（key = Unit）：
        //   若把 l/t/r/b 当 key，拖动过程中每次更新都会重建检测器 → 手势被掐断。
        val curL = rememberUpdatedState(l)
        val curT = rememberUpdatedState(t)
        val curR = rememberUpdatedState(r)
        val curB = rememberUpdatedState(b)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val den = LocalDensity.current
            val wPx = with(den) { maxWidth.toPx() }
            val hPx = with(den) { maxHeight.toPx() }
            val hitPx = EDGE_HIT_DP * den.density

            // 【r12 修 bug·虚线只能控一边】单指与双指合并到**同一个**手势循环。
            //
            // 【旧实现的坑】原来叠了两个独立 pointerInput：下面的 detectDragGestures（单指）
            //   + 上面的 detectTransformGestures（双指）。后者会消费**全部**触摸（含单指），
            //   于是单指拖边根本收不到事件 —— 用户看到的就是「框只能整体挪 / 只能控一边」。
            //
            // 【现在的分工】
            //   · 单指按在**角**上   → 同时改相邻两条边（即同时控长和宽）
            //   · 单指按在**边**上   → 只改那一条边
            //   · 单指按在**内部**   → 整体挪框（四条边一起平移）
            //   · 双指捏合           → 以双指实时中点为锚等比缩放
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            // 单指模式下锁定的目标：见 [pickTarget] 的返回值。
                            var target = -1
                            var multi = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    break
                                }
                                val sw = size.width.toFloat()
                                val sh = size.height.toFloat()
                                if (sw <= 0f || sh <= 0f) {
                                    continue
                                }
                                val cl = curL.value
                                val ct = curT.value
                                val cr = curR.value
                                val cb = curB.value
                                if (pressed.size >= 2) {
                                    // 双指：捏合缩放（锚点 = 双指实时中点，需求定案）。
                                    multi = true
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1.0f) {
                                        val cx = (cl + cr) / 2f
                                        val cy = (ct + cb) / 2f
                                        val nw = (cr - cl) * zoom
                                        val nh = (cb - ct) * zoom
                                        l = cx - nw / 2f
                                        t = cy - nh / 2f
                                        r = cx + nw / 2f
                                        b = cy + nh / 2f
                                    }
                                } else {
                                    // 回到单指：重新判定按的是哪儿。
                                    if (multi) {
                                        multi = false
                                        target = -1
                                    }
                                    val ch = pressed[0]
                                    val x = ch.position.x
                                    val y = ch.position.y
                                    if (target < 0) {
                                        target = pickTarget(x, y, cl, ct, cr, cb, sw, sh, hitPx)
                                    }
                                    val dx = ch.positionChange().x / sw
                                    val dy = ch.positionChange().y / sh
                                    if (dx != 0f || dy != 0f) {
                                        when (target) {
                                            0 -> l = cl + dx                       // 左边
                                            1 -> r = cr + dx                       // 右边
                                            2 -> t = ct + dy                       // 上边
                                            3 -> b = cb + dy                       // 下边
                                            4 -> { l = cl + dx; t = ct + dy }       // 左上角
                                            5 -> { r = cr + dx; t = ct + dy }       // 右上角
                                            6 -> { l = cl + dx; b = cb + dy }       // 左下角
                                            7 -> { r = cr + dx; b = cb + dy }       // 右下角
                                            else -> {                              // 整体挪框
                                                l = cl + dx
                                                t = ct + dy
                                                r = cr + dx
                                                b = cb + dy
                                            }
                                        }
                                    }
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    }
            )

            // 四周虚线框。
            Box(
                modifier = Modifier
                    .offset { IntOffset((l * wPx).roundToInt(), (t * hPx).roundToInt()) }
                    .size(
                        width = with(den) { ((r - l) * wPx).toDp() },
                        height = with(den) { ((b - t) * hPx).toDp() }
                    )
                    .drawBehind {
                        val stroke = Stroke(
                            width = DASH_WIDTH_DP * den.density,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(
                                    DASH_ON_DP * den.density,
                                    DASH_OFF_DP * den.density
                                )
                            )
                        )
                        drawRect(color = c.acc, style = stroke, topLeft = Offset.Zero, size = size)
                    }
            )

            // 底部横向操作栏：取消 / 恢复 / 旋转 / 保存。
            // 【r12 位置】钉在屏幕下方居中；窗口本身是整屏，故 bottomCenter 即「屏幕下方居中」。
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
                    // 【r12 新增】把框拉回出厂默认（整屏内缩 24dp），防止框被拖丢找不到。
                    val d = DollProfile.defaultBounds(ctx)
                    l = d[0]
                    t = d[1]
                    r = d[2]
                    b = d[3]
                }
                Spacer(Modifier.width(16.dp))
                DhKit.BarButton(Icons.IC_ROTATE_CW, c.acc, "旋转") {
                    // 【r13 修 bug·取消没生效】只改本地草稿，**不写盘、也不通知桌宠** ——
                    //   原来这里立刻 setFlip，用户点「取消」时朝向已经被改掉了。
                    //   方向随「保存」一起提交（见下方）。
                    flipped = !flipped
                }
                Spacer(Modifier.width(16.dp))
                DhKit.BarButton(Icons.IC_CHECK, c.acc, "保存") {
                    // 框与朝向一起提交（取消时两者都不写）。
                    DollProfile.setBounds(ctx, dollId, orient, l, t, r, b)
                    DollProfile.setFlip(ctx, dollId, flipped)
                    hide()
                    // 【为什么通知服务】框变了，人偶要按新框重新摆回范围内。
                    notifyPet(ctx)
                    onDone?.invoke()
                }
            }
        }
    }

    /**
     * 【r12】判定单指按在哪儿，返回要改的目标：
     *   0 左 / 1 右 / 2 上 / 3 下 / 4 左上角 / 5 右上角 / 6 左下角 / 7 右下角 / 8 整体挪。
     *
     * 【为什么要判角】需求定案「虚线本身也可以控制长和宽」—— 按在角上时同时改
     *   相邻两条边，等于一次拖动同时调宽和高；只按在边上则只动那一条。
     * 【判定顺序】先看是否同时靠近一横一纵（角），再看是否靠近单条边，都不算就整体挪。
     */
    private fun pickTarget(
        x: Float,
        y: Float,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        sw: Float,
        sh: Float,
        hit: Float
    ): Int {
        val nearLeft = abs(x - l * sw) < hit
        val nearRight = abs(x - r * sw) < hit
        val nearTop = abs(y - t * sh) < hit
        val nearBottom = abs(y - b * sh) < hit
        if (nearLeft && nearTop) return 4
        if (nearRight && nearTop) return 5
        if (nearLeft && nearBottom) return 6
        if (nearRight && nearBottom) return 7
        if (nearLeft) return 0
        if (nearRight) return 1
        if (nearTop) return 2
        if (nearBottom) return 3
        return 8
    }

    /* 【r12-UI】原私有 BarButton 已上提到 [DhKit.BarButton]：
     *   两个悬浮窗原来各写一份完全相同的实现，且字号 13.sp 是全仓唯一的非标按钮字号。
     *   收口到 DhKit 后统走 UiKit.FS_CHIP，不会再分叉。 */

    /** 通知桌宠服务刷新（朝向 / 框变了要立刻生效；服务没在跑就静默忽略）。 */
    private fun notifyPet(ctx: Context) {
        try {
            val i = Intent(ctx, PetService::class.java)
            i.setAction(PetService.ACTION_REFRESH)
            ctx.startService(i)
        } catch (ignored: Throwable) {
        }
    }
}