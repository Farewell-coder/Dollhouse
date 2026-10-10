package com.dollhouse.app.ui.theme

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 【职责】原生游戏式沉浸状态栏的唯一落点。
 *
 * 【做法】隐藏 statusBars（不碰导航栏的显示 / 隐藏），走 [WindowInsetsControllerCompat] 的
 *   BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE：系统 transient 自己负责「边缘呼出 -> 自动隐藏」，
 *   本类不做任何计时轮询、不因边缘呼出改布局。
 *
 * 【minSdk 全覆盖】hide(statusBars) 在**所有** minSdk 上都走 WindowInsetsControllerCompat：
 *   - API>=30：framework WindowInsetsController.hide。
 *   - API<30：compat 落到 View.SYSTEM_UI_FLAG_FULLSCREEN（视图级标志，**不是**
 *     WindowManager.LayoutParams.FLAG_FULLSCREEN），因此不会破坏 manifest 的 adjustResize。
 *   （此前误以为旧机 hide 会走窗口 FLAG_FULLSCREEN 而破坏 adjustResize，直接在 API<30 return，
 *     导致旧机从未沉浸 —— 已按 compat 实际实现修正。）
 *
 * 【edge-to-edge】统一 [WindowCompat.setDecorFitsSystemWindows] (false)：
 *   API>=30 走 framework；API<30 等价落到 LAYOUT_STABLE | LAYOUT_FULLSCREEN | LAYOUT_HIDE_NAVIGATION。
 *   内容因此顶到 y=0；顶部不再让任何 status inset，顶部 / 左右安全区由
 *   [UiKit.cutoutSafeInsets] 在构建时读取物理 cutout（见 UiKit）。
 *
 * 【安全区】cutout：API>=28 SHORT_EDGES，API>=30 ALWAYS；只避开物理挖孔 / 屏幕圆角。
 *
 * 【IME / 底部】content 容器只补「底部」= max(稳定导航栏, cutout 底, IME)（旧机由 frame 兜底），
 *   补偿 decorFits=false 交出的让位；不改导航栏颜色 / 显示行为（仅保持原透明）。
 *   content 底部 padding 只在取值变化时才重设，同值不重复设置。
 *   键盘收起（或窗口重新获焦）才重新隐藏状态栏，绝不在每次 insets 事件里抢系统临时状态栏。
 *
 * 【双扣修正】insets 监听挂在 android.R.id.content（不是 decor）。返回时只把自己消费的
 *   statusBars / navigationBars / displayCutout / ime 四类按 0 下传（copy builder setInsets），
 *   既不返回 CONSUMED 吞掉其它类型，也不会让 DecorView 再默认扣一次 insets。
 *
 * 【边界】不自绘状态栏、不 show/hide 导航栏、不全局消费 IME。浮动 Service / PickFile / PetLink 不接本类。
 */
object ImmersiveStatus {

    fun applyEdgeToEdge(act: Activity?) {
        val a = act ?: return
        try {
            val w = a.window
            setupWindow(w)
            val content = a.findViewById<ViewGroup>(android.R.id.content)
            if (content != null) {
                applyInsets(w, content)
            }
            hideStatusBars(w)
            w.decorView.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                // 窗口回到前台时把状态栏重新纳入沉浸；不在 insets 事件里反复 hide。
                if (hasFocus) {
                    hideStatusBars(w)
                }
            }
            applyLightStatusBar(w, !ThemeManager.isDark(a))
        } catch (ignored: Throwable) {
            // 沉浸失败不影响功能，最多顶部多一条系统栏。
        }
    }

    /**
     * 弹窗是独立窗口，需同步沉浸口径（透明状态栏 + 隐藏 + 图标反色）。
     *
     * 【坑】必须在 dlg.show() 之后调用：show 之前窗口 / decor 还没建立，controller 无宿主，调用无效。
     *   且浮动 dialog **不** setDecorFitsSystemWindows(false)：它是 wrap_content 的浮动窗，
     *   交给系统默认让位；强设 edge-to-edge 会让软键盘 / 输入框让位失效。
     */
    fun syncDialog(dlg: Dialog?) {
        val w = dlg?.window ?: return
        try {
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            w.statusBarColor = Color.TRANSPARENT
            hideStatusBars(w)
            applyLightStatusBar(w, !ThemeManager.isDark(w.context))
        } catch (ignored: Throwable) {
        }
    }

    private fun setupWindow(w: Window) {
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = w.attributes
            lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            w.attributes = lp
        }
        // 全 minSdk 统一 edge-to-edge，不再按版本分新旧实现。
        WindowCompat.setDecorFitsSystemWindows(w, false)
        // 【运行期 adjustResize】decorFits=false 后窗口不再自动缩窗，已观测到键盘弹出时目标窗口被系统整体 pan。
        //   只改 adjust 档，保留 SOFT_INPUT_STATE_* 等其它 bits；不碰 Manifest。
        val soft = w.attributes
        soft.softInputMode =
            (soft.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        w.attributes = soft
    }

    private fun hideStatusBars(w: Window) {
        val c = WindowInsetsControllerCompat(w, w.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.statusBars())
    }

    private fun applyLightStatusBar(w: Window, light: Boolean) {
        try {
            WindowInsetsControllerCompat(w, w.decorView).isAppearanceLightStatusBars = light
        } catch (ignored: Throwable) {
        }
    }

    private fun applyInsets(w: Window, content: ViewGroup) {
        if (Build.VERSION.SDK_INT >= 30) {
            installModernInsets(w, content)
        } else {
            installLegacyInsets(content)
        }
    }

    /** API>=30：decorFits=false 下 IME / 导航栏都作为 insets 下发，直接按 insets 计算底部让位。 */
    private fun installModernInsets(w: Window, content: ViewGroup) {
        val lastBottom = intArrayOf(-1)
        val imeWasVisible = booleanArrayOf(false)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // 只取稳定导航栏 / cutout / IME；瞬时 status inset 不参与布局。
            val stable = Math.max(nav.bottom, cutout.bottom)
            val bottom = Math.max(stable, if (imeVisible) ime.bottom else 0)
            if (bottom != lastBottom[0]) {
                lastBottom[0] = bottom
                content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
            }
            if (imeVisible) {
                imeWasVisible[0] = true
            } else if (imeWasVisible[0]) {
                // 键盘收起：此时才恢复沉浸，避免每次 insets 事件抢系统临时状态栏。
                imeWasVisible[0] = false
                hideStatusBars(w)
            }
            consumeHandled(insets)
        }
    }

    /**
     * API<30：WindowInsetsCompat 的 IME 表现随 ROM / adjustResize 不一致，改用整窗 visibleDisplayFrame 兜底。
     *
     * 【不双扣】基准取 rootView（整窗 DecorView）的高度：manifest 是 adjustResize，键盘弹出会把
     *   root 与 frame.bottom 一起缩掉，此时 diff 只剩导航栏；只有未缩窗（edge-to-edge 下 IME 不下发）
     *   时 diff 才是「导航栏 + IME」。因此不会像按 content 现高计算那样把 IME 重复扣一次。
     */
    private fun installLegacyInsets(content: ViewGroup) {
        // 先消费自己处理的类型，避免 DecorView / 子视图默认再让位造成双扣。
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets -> consumeHandled(insets) }
        val root = content.rootView
        val frame = Rect()
        val lastBottom = intArrayOf(-1)
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                root.getWindowVisibleDisplayFrame(frame)
                val h = root.height
                if (h <= 0) {
                    return
                }
                val diff = h - frame.bottom
                val bottom = if (diff in 1 until h) diff else 0
                if (bottom != lastBottom[0]) {
                    lastBottom[0] = bottom
                    content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
                }
            }
        })
    }

    /**
     * 把自己处理过的四类 insets 归零后下传，其余类型原样保留。
     * 【为何不返回 CONSUMED】CONSUMED 会吞掉全部类型；这里只对消费的类型归零，
     *   其它 insets（系统手势 / 未来新增类型）仍能到达下游，悬浮 Service 不受影响。
     */
    private fun consumeHandled(insets: WindowInsetsCompat): WindowInsetsCompat {
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .build()
    }
}
