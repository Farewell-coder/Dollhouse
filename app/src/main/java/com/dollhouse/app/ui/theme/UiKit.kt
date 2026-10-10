package com.dollhouse.app.ui.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.R
import com.dollhouse.app.anim.Springs

/**
 * 统一视觉与动效。配色 / 圆角 / 时长全部在此收口，
 * 页面代码不再散写裸值 —— 对齐参考软件 My Life, My Sim 的设计令牌。
 *
 * 【硬约束】本 App 不允许任何形式的 Toast / Snackbar / 自实现浮层短提示。
 *   历史：2026-10-03 全量删除（原 55 处调用 + 1 个出口），因其视觉上与系统通知冲突且很丑。
 *   新增按键 / 交互一律用页面内的文字变化、控件状态或页面跳转做反馈，禁止重新引入浮层短提示。
 */
object UiKit {
    /*
     * 【重要】以下颜色字段全部是可变静态字段（不是 final）：
     * 它们由 ThemeManager.apply() 在每次界面构建前按当前主题整体覆写，
     * 全工程数百处 UiKit.XXX 的引用点因此不用改一行。
     * 新增颜色时记得同步在 ThemeManager 的三套调色板里补位，否则切换主题后该色不会变。
     */
    /** 主色（参考软件 --acc / --acc2）。 */
    @JvmField
    var ACC = 0xFF6B4EE6.toInt()

    @JvmField
    var ACC2 = 0xFF8B6EF7.toInt()

    @JvmField
    var CARD = 0xFFFFFFFF.toInt()

    @JvmField
    var BG = 0xFFF4F5F9.toInt()

    @JvmField
    var TITLE = 0xFF22315B.toInt()

    @JvmField
    var SUB = 0xFF5A6B99.toInt()

    @JvmField
    var LINE = 0xFFE6E7EF.toInt()

    /** 选项/对照组底色（设置页用）。 */
    @JvmField
    var OPTION = 0xFFF2F5FF.toInt()

    @JvmField
    var SOFT = 0xFFF2F3F7.toInt()

    @JvmField
    var FIELD = 0xFFF4F2FD.toInt()

    @JvmField
    var OK = 0xFF1B8A3A.toInt()

    @JvmField
    var ERR = 0xFFB3261E.toInt()

    @JvmField
    var ON_ACC = 0xFFFFFFFF.toInt()

    /** 聊天面板专用色（气泡 / 操作条 / 提示条）。 */
    @JvmField
    var CHAT_BUBBLE_USER = 0xFF4C6FDE.toInt()

    @JvmField
    var CHAT_BORDER = 0xFFC9D4EE.toInt()

    @JvmField
    var CHAT_CHIP_BG = 0xFFE4EAF8.toInt()

    @JvmField
    var CHAT_CHIP_FG = 0xFF3A5BC7.toInt()

    @JvmField
    var CHAT_CHIP_ON = 0xFFDCE6FF.toInt()

    @JvmField
    var CHAT_CHIP_OFF = 0xFFEDF1FA.toInt()

    @JvmField
    var CHAT_CHIP_MUTE = 0xFF7A88B0.toInt()

    @JvmField
    var CHAT_ACTION_BG = 0xFFE6EBF8.toInt()

    @JvmField
    var HINT_FG = 0xFF8A5A00.toInt()

    @JvmField
    var HINT_BG = 0xFFFFF4D6.toInt()

    /** 自绘开关的关闭态轨道色：比 LINE 深，保证白底卡片上看得清。 */
    @JvmField
    var SWITCH_OFF = 0xFFC9CEDD.toInt()

    /** 桌宠表情色（惊叹 / 开心 / 眩晕）。 */
    @JvmField
    var EMOTE_1 = 0xFFF2603C.toInt()

    @JvmField
    var EMOTE_2 = 0xFFE8608F.toInt()

    @JvmField
    var EMOTE_3 = 0xFF5A7BD8.toInt()

    /** 卡片描边（带透明度，深色主题下会换成浅色）。 */
    @JvmField
    var STROKE = 0x1422315B

    /** 弹层遮罩色：抽屉 / 底部面板背后的压暗层，深色主题下更重。 */
    @JvmField
    var SCRIM = 0x8A000000.toInt()

    /**
     * 状态语义色的「淡底」：把 OK / ERR 压成 12% 透明当徽标背景。
     *
     * 【为何用方法现算而不是新增调色板格子】ThemeManager 的调色板是定长数组（PAL_SIZE=29），
     *   cachedMonetPalette() 会校验 length == PAL_SIZE，对不上就整体丢弃取色结果。
     *   历史上正是因为这个校验失败，出现过「莫奈开关能开、配色却完全不变」的故障。
     *   派生色用方法算，数组长度不变，切主题时它跟着 OK / ERR 自动变 —— 零风险的路径。
     */
    @JvmStatic
    fun okBg(): Int {
        return (OK and 0x00FFFFFF) or 0x1E000000
    }

    /** 错误语义色的淡底，见 okBg()。 */
    @JvmStatic
    fun errBg(): Int {
        return (ERR and 0x00FFFFFF) or 0x1E000000
    }

    /**
     * 【外观】卡片底色：把 [CARD] 按用户设的「卡片透明度」叠成半透明。
     *
     * 【为什么是派生色而不是新增调色板格子】沿用 [okBg] / [errBg] 的既有范式：
     *   ThemeManager 的调色板是定长数组（PAL_SIZE=29），cachedMonetPalette() 会校验长度，
     *   新增格子会让校验失败、莫奈配色整体被丢弃（历史上真出过这个故障）。
     *   派生色用方法现算，数组长度不变，切主题时跟着 CARD 自动变 —— 零风险路径。
     *
     * 【为什么卡片底都取它】View 侧 `card()` / `round(CARD,…)` 与 Compose 侧
     *   `DhColors.card` 都读这一个方法，于是「一处改、全 App 生效」，
     *   不存在「首页卡片变了、聊天气泡没变」的撕裂。
     *
     * 【100% 时原样返回 CARD】不做任何位运算，保证默认外观与改造前逐像素一致。
     */
    @JvmStatic
    fun card(): Int {
        val p = cardAlphaApplied
        if (p >= 100) {
            return CARD
        }
        return ContrastCore.withAlpha(CARD, Math.max(0, Math.min(255, p * 255 / 100)))
    }

    /**
     * 落一次「卡片透明度」偏好。由 [ThemeManager.apply] 与 ThemeRefresh 在需要生效时调用。
     */
    @JvmStatic
    fun applyCardAlpha(percent: Int) {
        cardAlphaApplied = Math.max(0, Math.min(100, percent))
    }

    /** 当前已应用的卡片透明度（0~100）。 */
    @JvmField
    var cardAlphaApplied = 100

    /**
     * 当前调色板快照，顺序与 ThemeManager.apply() 的赋值顺序严格一致（29 位）。
     *
     * 【用途】就地换主题时做「旧色 → 新色」逐点重映射（见 ThemeRefresh）：
     *   必须在 ThemeManager.apply() 之前取旧快照，之后取新快照，两张表按下标对齐。
     * 【为何不新增数组字段】不新增任何调色板格子，PAL_SIZE 仍为 29，零风险。
     */
    @JvmStatic
    fun snapshotPalette(): IntArray {
        return intArrayOf(
            ACC, ACC2, CARD, BG, TITLE, SUB, LINE, OPTION, SOFT, FIELD,
            OK, ERR, ON_ACC, CHAT_BUBBLE_USER, CHAT_BORDER, CHAT_CHIP_BG, CHAT_CHIP_FG,
            CHAT_CHIP_ON, CHAT_CHIP_OFF, CHAT_CHIP_MUTE, CHAT_ACTION_BG, HINT_FG, HINT_BG,
            SWITCH_OFF, EMOTE_1, EMOTE_2, EMOTE_3, STROKE, SCRIM
        )
    }

    /**
     * 【接收处夹紧对比度】ThemeManager 冻结不能改，所以夹紧收在 UiKit 侧：
     *   调 ThemeManager.apply() 之后（或 [ThemeRefresh.applyInPlace]）调用本方法，
     *   把正文 / 副标题 / 提示 / 药丸文字夹到 WCAG 4.5:1，保证莫奈 / 浅色 / 暗色 / 纯黑都读得清。
     *
     * 【只在「接收处」改值】不新增调色板格子、不动 PAL_SIZE，也不改 ThemeManager；
     *   只把已经落到字段上的颜色按预期底色微调到达标，方向由 [ContrastCore] 实测决定。
     * 【幂等】对已达标颜色原样返回，重复调用无副作用。
     */
    @JvmStatic
    fun clampPaletteContrast() {
        // 正文 / 副标题：卡片与页面两种底色上都要达标。
        TITLE = ContrastCore.ensureContrast(TITLE, CARD, ContrastCore.AA_NORMAL)
        TITLE = ContrastCore.ensureContrast(TITLE, BG, ContrastCore.AA_NORMAL)
        SUB = ContrastCore.ensureContrast(SUB, CARD, ContrastCore.AA_NORMAL)
        SUB = ContrastCore.ensureContrast(SUB, BG, ContrastCore.AA_NORMAL)
        // 提示条：字压在提示底色上。
        HINT_FG = ContrastCore.ensureContrast(HINT_FG, HINT_BG, ContrastCore.AA_NORMAL)
        // 药丸 / 标签文字。
        CHAT_CHIP_FG = ContrastCore.ensureContrast(CHAT_CHIP_FG, CHAT_CHIP_BG, ContrastCore.AA_NORMAL)
        CHAT_CHIP_MUTE = ContrastCore.ensureContrast(CHAT_CHIP_MUTE, CHAT_ACTION_BG, ContrastCore.AA_NORMAL)
        // 主按钮：白（或深）字压在强调色上。
        ON_ACC = ContrastCore.ensureContrast(ON_ACC, ACC, ContrastCore.AA_NORMAL)
    }

    /** 动效时长：按压 90ms / 微交互 180ms / 弹层 260ms / 整页转场 300ms。 */
    const val D_PRESS = 90
    const val D_MICRO = 180
    const val D_LAYER = 260
    const val D_PAGE = 300

    /** 列表错峰入场的每一档延迟（毫秒）。 */
    const val D_STAGGER = 26

    /*
     * 全局动效曲线（Material 标准 easing）。
     * 【为何收口】此前各处直接用 DecelerateInterpolator（纯减速）或干脆不设曲线，
     *   起步偏“硬”。统一成标准曲线后，全 App 的位移／透明度／高度动画手感一致。
     *   EASE_STD   = 标准（进慢出慢，用于状态切换、开关、按压回弹）
     *   EASE_DECEL = 减速（用于入场：页面／面板从侧边或底部推进来）
     *   EASE_ACCEL = 加速（用于出场：让“离开”比“进入”更快，符合视觉习惯）
     */
    @JvmField
    val EASE_STD = PathInterpolator(0.2f, 0f, 0f, 1f)

    @JvmField
    val EASE_DECEL = PathInterpolator(0f, 0f, 0.2f, 1f)

    @JvmField
    val EASE_ACCEL = PathInterpolator(0.4f, 0f, 1f, 1f)

    @JvmStatic
    fun dp(c: Context, v: Float): Int {
        return Math.round(v * c.resources.displayMetrics.density)
    }

    /** 整数字面量的旧调用点（Java 原 dp(Context,int) 的隐式转换在 Kotlin 不存在）。 */
    fun dp(c: Context, v: Int): Int {
        return dp(c, v.toFloat())
    }

    /**
     * 沉浸安全区顶部留白（px）：只让开物理 cutout（刘海 / 挖孔）与屏幕圆角，不再固定让出状态栏高度。
     * 见 [cutoutSafeInsets]。
     */
    @JvmStatic
    fun statusBarPad(c: Context): Int {
        return cutoutSafeInsets(c)[1]
    }

    /**
     * 构建期物理安全区（px）：[left, top, right, bottom]，只含 display cutout（刘海 / 挖孔）。
     *
     * 【与 viewCutoutInsets 的区别】本函数无 View，只能构建期读 Display.getCutout()（**API29**），
     *   故以 29 为门槛；API28 返回 0 —— 绝不按 28 判定，否则 NoSuchMethodError。
     *   需要 API28 也生效且跟随窗口 attached / 旋转更新时，请用 [bindCutoutPadding] / [viewCutoutInsets]。
     *
     * 【圆角·不再扣除】不再把 API31 getRoundedCorner 的 radius 当整边内边距 —— 四角半径远大于
     *   实际被裁区域，按四边扣除会出现过大空条 / 内容变窄。四角遮挡由控件既有基础间距覆盖。
     */
    @JvmStatic
    fun cutoutSafeInsets(c: Context): IntArray {
        val out = intArrayOf(0, 0, 0, 0)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val wm = c.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val cutout = wm?.defaultDisplay?.cutout
                if (cutout != null) {
                    out[0] = Math.max(out[0], cutout.safeInsetLeft)
                    out[1] = Math.max(out[1], cutout.safeInsetTop)
                    out[2] = Math.max(out[2], cutout.safeInsetRight)
                    out[3] = Math.max(out[3], cutout.safeInsetBottom)
                }
            }
        } catch (ignored: Throwable) {
            // 无 cutout / 取不到：保留 0。
        }
        return out
    }

    /**
     * 视图级物理安全区（px）：[left, top, right, bottom]，只含 display cutout（刘海 / 挖孔）。
     *
     * 【为何用视图】API28 起窗口即允许 SHORT_EDGES，但 Display.getCutout() 是 API29，
     *   API28 调用会 NoSuchMethodError；WindowInsets.getDisplayCutout() 才是 API28 起可用，
     *   且必须等窗口 attached 后从 View.rootWindowInsets 取到的才是真实值。本函数只读该值，不判 29。
     * 【圆角】不取 getRoundedCorner 当整边内边距（理由见 [cutoutSafeInsets]）。
     */
    @JvmStatic
    fun viewCutoutInsets(v: View?): IntArray {
        val out = intArrayOf(0, 0, 0, 0)
        if (v == null || Build.VERSION.SDK_INT < 28) {
            return out
        }
        try {
            val co = v.rootWindowInsets?.displayCutout ?: return out
            out[0] = co.safeInsetLeft
            out[1] = co.safeInsetTop
            out[2] = co.safeInsetRight
            out[3] = co.safeInsetBottom
        } catch (ignored: Throwable) {
            // 取不到：保留 0。
        }
        return out
    }

    /**
     * 把 [v] 的内边距绑定为「基础间距 + 物理 cutout 安全区」：绑定当帧即生效，窗口 attached / 旋转后自动更新。
     *
     * 【为何不挂 insets 监听】不覆盖既有 setOnApplyWindowInsetsListener（IME / 底部由
     *   ImmersiveStatus 统一处理），只加 OnLayoutChangeListener；同值不重复 setPadding，
     *   避免自触发布局循环。背景仍铺到 y=0，只有该控件自身避让刘海 / 挖孔，不重复扣 cutout。
     * 【首帧兜底】原实现只在 post / layout 里 apply，首帧没有任何 base padding。
     *   现改为绑定即同步 apply 一次：窗口未 attached（rootWindowInsets 为空）时用构建期
     *   [cutoutSafeInsets] 兜底，attached 后再由 layout / requestApplyInsets 校正；
     *   三条路径共用同一个幂等 apply，取值不会互相打架。
     */
    @JvmStatic
    fun bindCutoutPadding(v: View?, baseL: Int, baseT: Int, baseR: Int, baseB: Int) {
        if (v == null) {
            return
        }
        val last = intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        // 窗口已 attached 时用视图级 insets（API28 起随窗口更新）；未 attached 时退回构建期 cutout。
        fun currentInsets(): IntArray {
            return if (v.rootWindowInsets != null) viewCutoutInsets(v) else cutoutSafeInsets(v.context)
        }
        val apply = Runnable {
            val s = currentInsets()
            val l = baseL + s[0]
            val t = baseT + s[1]
            val r = baseR + s[2]
            val b = baseB + s[3]
            if (l != last[0] || t != last[1] || r != last[2] || b != last[3]) {
                last[0] = l
                last[1] = t
                last[2] = r
                last[3] = b
                v.setPadding(l, t, r, b)
            }
        }
        // 同步首帧 + attach 后 post + 布局变化 / 主动请求 insets 校正，全部幂等。
        apply.run()
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
        v.post(apply)
        v.requestApplyInsets()
    }

    /** 横屏侧边刘海 / 圆角的左侧安全区（px）。 */
    @JvmStatic
    fun safeInsetLeft(c: Context): Int {
        return cutoutSafeInsets(c)[0]
    }

    /** 横屏侧边刘海 / 圆角的右侧安全区（px）。 */
    @JvmStatic
    fun safeInsetRight(c: Context): Int {
        return cutoutSafeInsets(c)[2]
    }

    /**
     * 系统导航栏高度（px）。取不到时按 0 兜底（手势导航下本就为 0）。
     * 【为什么要它】悬浮底栏要做「适配安全区」，即距屏幕底部必须让开导航栏，
     *   否则在手势条 / 三键导航上会被系统手势区压住，点不到。
     */
    @JvmStatic
    fun navBarPad(c: Context): Int {
        try {
            val id = c.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) {
                val h = c.resources.getDimensionPixelSize(id)
                if (h > 0) {
                    return h
                }
            }
        } catch (ignored: Throwable) {
            // 落到兜底值。
        }
        return 0
    }

    /**
     * 沉浸铺满：统一委托 [ImmersiveStatus]（透明栏 + 原生沉浸状态栏 + IME 让位 + cutout 适配）。
     * 详见 ImmersiveStatus 的类型注释。
     */
    @JvmStatic
    fun applyEdgeToEdge(act: Activity?) {
        ImmersiveStatus.applyEdgeToEdge(act)
    }

    /** 状态栏图标 / 文字是否走深色（浅底用 true）。 */
    @JvmStatic
    fun setLightStatusBar(decor: View?, light: Boolean) {
        if (decor == null) {
            return
        }
        try {
            var flags = decor.systemUiVisibility
            if (light) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            decor.systemUiVisibility = flags
        } catch (ignored: Throwable) {
            // 照旧用系统默认对比度。
        }
    }

    /** dp -> px，但不取整。给需要保留小数精度的动画/绘制计算用。 */
    @JvmStatic
    fun dpf(c: Context, v: Float): Float {
        return v * c.resources.displayMetrics.density
    }

    // 生成纯色圆角背景。
    @JvmStatic
    fun round(color: Int, c: Context, radiusDp: Int): GradientDrawable = round(color, c, radiusDp.toFloat())

    @JvmStatic
    fun round(color: Int, c: Context, radiusDp: Float): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.setColor(color)
        g.cornerRadius = dp(c, radiusDp).toFloat()
        return g
    }

    // 生成带描边的圆角背景。
    @JvmStatic
    fun roundStroke(color: Int, stroke: Int, c: Context, radiusDp: Float): GradientDrawable {
        val g = round(color, c, radiusDp)
        g.setStroke(Math.max(1, dp(c, 1f)), stroke)
        return g
    }

    /**
     * 主按钮渐变的「浅端」色：按当前字色 [ON_ACC] 的可读性收敛 [ACC2]。
     *
     * 【为什么需要】主按钮 / 发送键画的是 ACC → ACC2 的渐变，而 [clampPaletteContrast] 只按
     *   单色 ACC 夹 [ON_ACC]，渐变浅端没被覆盖 —— 浅色主题下白字压 ACC2(0xFF8B6EF7) 只有
     *   ≈4.15:1，低于 WCAG AA 4.5，肉眼就是「紫按钮上的白字发灰发糊」。
     * 【怎么修】不改品牌色字段、不动冻结的 ThemeManager，只在绘制端把浅端朝「远离字色」的方向
     *   微调到达标：深端仍是品牌主色，按钮仍是紫的，白字重新清晰。
     * 【幂等 + 缓存】已达标时原样返回该色；按 (ACC2, ON_ACC) 记忆结果，避免每次建按钮都重算。
     */
    private var gradEndKey = 0L
    private var gradEndCache = 0

    @JvmStatic
    fun gradientEnd(): Int {
        val key = (ACC2.toLong() shl 32) or (ON_ACC.toLong() and 0xFFFFFFFFL)
        if (key != gradEndKey) {
            gradEndCache = ContrastCore.ensureContrast(ACC2, ON_ACC, ContrastCore.AA_NORMAL)
            gradEndKey = key
        }
        return gradEndCache
    }

    /** 卡片：白底 + 16dp 圆角 + 极淡描边 + 近地投影。 */
    @JvmStatic
    fun card(v: View, c: Context) {
        val g = round(card(), c, 16f)
        g.setStroke(1, STROKE)
        v.background = g
        v.elevation = dp(c, 2).toFloat()
    }

    /** 主按钮：紫渐变 + 白字 + 微光。 */
    @JvmStatic
    fun primary(b: TextView, c: Context) {
        val g = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(ACC, gradientEnd())
        )
        g.cornerRadius = dp(c, 12).toFloat()
        b.background = g
        b.setTextColor(ON_ACC)
        b.gravity = Gravity.CENTER
        b.elevation = dp(c, 3).toFloat()
        press(b)
    }

    /**
     * 发送键：圆形紫渐变 + 图标字。
     * 【坑】用 Button 时必须自行清掉主题带来的 minWidth/minHeight（默认 88×48dp），
     *      否则外层给的 38dp 方形会被撑成椭圆，圆形就不圆了。
     */
    @JvmStatic
    fun sendButton(b: ImageView, c: Context) {
        val g = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(ACC, gradientEnd())
        )
        g.cornerRadius = dp(c, 999).toFloat()
        b.background = g
        b.setImageResource(R.drawable.ic_send)
        val d = b.drawable
        if (d != null) {
            try {
                d.mutate().setTint(ON_ACC)
            } catch (ignored: Throwable) {
            }
        }
        b.scaleType = ImageView.ScaleType.CENTER
        b.isClickable = true
        b.setPadding(0, 0, 0, 0)
        b.elevation = dp(c, 3).toFloat()
        press(b)
    }

    /** 次按钮：白底 + 淡描边 + 深字。 */
    @JvmStatic
    fun secondary(b: TextView, c: Context) {
        b.background = roundStroke(card(), LINE, c, 12f)
        b.setTextColor(TITLE)
        b.gravity = Gravity.CENTER
        b.elevation = dp(c, 1).toFloat()
        press(b)
    }

    /** 按压反馈：按下缩到 0.97，抬起回弹。 */
    @JvmStatic
    fun press(v: View) {
        v.setOnTouchListener { view, e ->
            val a = e.actionMasked
            if (a == MotionEvent.ACTION_DOWN) {
                // 【坑】不可点的行收不到 ACTION_UP：View 不消费 DOWN，后续事件就不会再派发回来，
                //   缩放会一直停在 0.97 再也回不去（表现就是「点一下之后按钮一直缩着」）。
                //   所以不可点时不播按压动画，直接放行。
                if (!view.isClickable) {
                    return@setOnTouchListener false
                }
                view.animate().scaleX(0.96f).scaleY(0.96f)
                    .setDuration(D_PRESS.toLong())
                    .setInterpolator(EASE_STD).start()
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                // 【弹簧】松手那一下用 bouncy（ζ=0.40）弹回，按下仍用 EASE_STD 保持跟手。
                view.animate().cancel()
                Springs.drive(Springs.bouncy(), object : Springs.Listener {
                    override fun onUpdate(p: Float) {
                        val s = Springs.lerp(0.96f, 1.0f, p)
                        view.scaleX = s
                        view.scaleY = s
                    }

                    override fun onEnd() {
                        view.scaleX = 1f
                        view.scaleY = 1f
                    }
                })
            }
            false
        }
    }

    /**
     * 卡片展开 / 收起：高度动画 + 箭头旋转 90°。
     * 收起态 height=0（不用 GONE，免得布局跳），展开结束回到 WRAP_CONTENT。
     */
    @JvmStatic
    fun expand(body: View, arrow: View?, open: Boolean) {
        if (body.layoutParams == null) {
            return
        }
        var w = body.width
        if (w <= 0 && body.parent is View) {
            val p = body.parent as View
            w = p.width - p.paddingLeft - p.paddingRight
        }
        if (w <= 0) {
            w = body.resources.displayMetrics.widthPixels
        }
        body.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val full = Math.max(1, body.measuredHeight)
        val from = Math.max(0, body.height)
        val to = if (open) full else 0

        val va = ValueAnimator.ofInt(from, to)
        va.duration = D_LAYER.toLong()
        va.interpolator = EASE_STD
        va.addUpdateListener { an ->
            val h = an.animatedValue as Int
            val lp = body.layoutParams
            lp.height = h
            body.layoutParams = lp
            body.alpha = Math.min(1f, h.toFloat() / full)
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: Animator) {
                val lp = body.layoutParams
                lp.height = if (open) ViewGroup.LayoutParams.WRAP_CONTENT else 0
                body.layoutParams = lp
                body.alpha = if (open) 1f else 0f
            }
        })
        va.start()
        if (arrow != null) {
            // 【弹簧】箭头旋转同样走 snappy，与卡片高度动画同节奏。
            val fromRot = arrow.rotation
            val toRot = if (open) 90f else 0f
            Springs.drive(Springs.snappy(), object : Springs.Listener {
                override fun onUpdate(p: Float) {
                    arrow.rotation = Springs.lerp(fromRot, toRot, p)
                }

                override fun onEnd() {
                    arrow.rotation = toRot
                }
            })
        }
    }

    /** 入场：淡入 + 上移 8dp（错峰用 delayMs）。 */
    @JvmStatic
    fun enter(v: View, delayMs: Int) {
        if (v.parent == null) {
            // 还没挂进视图树：动画会被丢，等挂上再播。
            v.post { enter(v, delayMs) }
            return
        }
        v.alpha = 0f
        v.translationY = dp(v.context, 8).toFloat()
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(Math.max(0, delayMs).toLong())
            .setDuration(D_LAYER.toLong())
            .setInterpolator(EASE_DECEL).start()
    }

    /**
     * 列表错峰入场：同一屏内的子项按顺序依次淡入上移，而不是「啪」地整块出现。
     *
     * 【为何收口在这】此前各页的入场要么不做、要么各写一段 delay 计算，
     *   手感不一致。统一成一个入口后，任何页面「加一行 enterList」就有一致的入场。
     * 【为何要有上限】列表长了以后逐项延时会累积到几百毫秒，用户会觉得「卡」。
     *   这里对超过 cap 的项一律贴着 cap 入场，后段不再继续加延时。
     */
    @JvmStatic
    fun enterList(parent: ViewGroup?, cap: Int) {
        if (parent == null) {
            return
        }
        val n = parent.childCount
        for (i in 0 until n) {
            val d = Math.min(i, cap) * D_STAGGER
            enter(parent.getChildAt(i), d)
        }
    }

    /** enterList 的默认档位（前 8 项错峰，其余一起）。 */
    @JvmStatic
    fun enterList(parent: ViewGroup?) {
        enterList(parent, 8)
    }

    /**
     * 数值 / 状态文字变化时的「脉冲」反馈：轻微放大再弹回。
     *
     * 【为何用弹簧】值变化本身是瞬时的，直接换字会显得干；
     *   一次 1.0 → 1.06 → 1.0 的弹回能让眼睛捕捉到「这里变了」。
     */
    @JvmStatic
    fun pulse(v: View?) {
        if (v == null) {
            return
        }
        v.animate().cancel()
        Springs.drive(Springs.bouncy(), object : Springs.Listener {
            override fun onUpdate(p: Float) {
                val s = Springs.lerp(1.06f, 1f, p)
                v.scaleX = s
                v.scaleY = s
            }

            override fun onEnd() {
                v.scaleX = 1f
                v.scaleY = 1f
            }
        })
    }

    /** 卡片头右侧的折叠箭头：描边 chevron 图标，靠 rotation 0 ↔ 90 表示开合。 */
    @JvmStatic
    fun arrow(ctx: Context): ImageView {
        return Icons.view(ctx, Icons.IC_CHEVRON_RIGHT, 18.0f, TITLE)
    }

    /**
     * 自绘滑动开关：圆角轨道 + 圆形滑块，切换时滑块平移 + 轨道变色。
     * 刻意不继承 CompoundButton —— 各家 ROM 对 Switch 的默认样式差异很大，
     * 自绘才能保证和本 App 的配色一致。
     */
    class Switch(ctx: Context) : FrameLayout(ctx) {
        private val knob: View
        private var onState = false

        /** 轨道变色动画句柄：连点时先取消上一段，避免两个动画抢同一个背景。 */
        private var trackAnim: ValueAnimator? = null

        init {
            setBackground(UiKit.round(UiKit.SWITCH_OFF, ctx, 999f))
            elevation = UiKit.dp(ctx, 1).toFloat()

            knob = View(ctx)
            val k = UiKit.dp(ctx, (H_DP - PAD_DP * 2).toFloat())
            val klp = FrameLayout.LayoutParams(k, k)
            klp.gravity = Gravity.CENTER_VERTICAL
            klp.leftMargin = UiKit.dp(ctx, PAD_DP.toFloat())
            knob.setBackground(UiKit.round(0xFFFFFFFF.toInt(), ctx, 999f))
            knob.elevation = UiKit.dp(ctx, 3).toFloat()
            addView(knob, klp)
        }

        /**
         * 强制固定尺寸：开关的 54×30dp 是视觉基准，不能被外层的 WRAP_CONTENT
         * 压成子 View 大小（FrameLayout 默认就是按子 View 量，会把轨道压扁成方块）。
         */
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(UiKit.dp(context, W_DP.toFloat()), UiKit.dp(context, H_DP.toFloat()))
        }

        fun isOn(): Boolean {
            return onState
        }

        /** 只改状态与视觉（不动画），用于从 prefs 恢复初始值。 */
        fun setOn(value: Boolean) {
            setOn(value, false)
        }

        /** animated=true 时播放滑块位移动画。 */
        fun setOn(value: Boolean, animated: Boolean) {
            onState = value
            val c = context
            val travel = UiKit.dp(c, (W_DP - H_DP).toFloat())
            val to = if (value) travel.toFloat() else 0f
            knob.animate().cancel()
            trackAnim?.cancel()
            trackAnim = null
            if (!animated) {
                setBackground(UiKit.round(if (value) UiKit.ACC else UiKit.SWITCH_OFF, c, 999f))
                knob.translationX = to
                return
            }
            // 轨道颜色：ArgbEvaluator 逐帧插值。
            // 【为何】原先是 setBackground(三元) 一下跳色，与滑块 180ms 的位移不同步，
            //   看上去就是“轨道啪一下变了、滑块慢慢跟”。两者同时长同曲线才顺。
            val from = if (value) UiKit.SWITCH_OFF else UiKit.ACC
            val toColor = if (value) UiKit.ACC else UiKit.SWITCH_OFF
            val ev = ArgbEvaluator()
            val fromX = knob.translationX
            val toX = to
            // 【弹簧】轨道与滑块共用同一条弹簧进度：snappy（ζ=0.73）。
            //   历史坑：轨道 setBackground 硬跳 + 滑块 180ms 位移，看着是「轨道啪一下变了、滑块慢慢跟」。
            //   现在两者由同一个 progress 驱动，绝不会再脱节。
            val ta = Springs.drive(Springs.snappy(), object : Springs.Listener {
                override fun onUpdate(p: Float) {
                    knob.translationX = Springs.lerp(fromX, toX, p)
                    setBackground(UiKit.round(ev.evaluate(p, from, toColor) as Int, c, 999f))
                }

                override fun onEnd() {
                    knob.translationX = toX
                    setBackground(UiKit.round(toColor, c, 999f))
                }
            })
            trackAnim = ta
        }

        /**
         * 【就地换主题】按当前 onState 重设轨道底色（不动画）。
         * 开关的轨道底色是构造 / setOn 那一刻写死的 GradientDrawable 快照，
         * 主题静态字段变了它也不会变；ThemeRefresh 递归到本控件时会调这个方法。
         */
        fun reapplyTheme() {
            setBackground(UiKit.round(if (onState) UiKit.ACC else UiKit.SWITCH_OFF, context, 999f))
        }

        companion object {
            private const val W_DP = 44
            private const val H_DP = 24
            private const val PAD_DP = 3
        }
    }

    /**
     * 自绘滑动条（胶囊轨道 + 刻度点 + 进度胶囊 + 实时数值 + 终点标记）。
     *
     * 【形态来源】按用户给的参考图重做：轨道是两端全圆的粗胶囊、轨道内均匀分布刻度点、
     *   轨道右侧外面一根垂直粗线作终点标记、右侧实时显示百分比。
     *   参考图没有「拇指」—— 这里也刻意不做，直接拖轨道：少一个挡视线的圆点，长条控件拖动面积更大。
     *
     * 【为什么自绘】系统 SeekBar 的拇指与轨道形状由各家 ROM 主题决定，
     *   tintList 只能改色、改不了形，做不出「胶囊 + 刻度」这种形态。
     *
     * 【配色】全部读主题字段（ACC / LINE / TITLE / ON_ACC），
     *   莫奈切换时整体变色，不需要在这里单独维护一份颜色。
     */
    class Slider(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

        private var maxValue = 100
        private var value = 0
        private var shown = 0f          // 绘制用进度（0..1），动画中间值
        private var dragging = false
        private var downX = 0f
        private var startProg = 0f
        private var anim: ValueAnimator? = null
        private var listener: OnChange? = null

        interface OnChange {
            /** fromUser=true 表示这次变化来自手指拖动。 */
            fun onChanged(value: Int, fromUser: Boolean)
        }

        init {
            tp.textAlign = Paint.Align.RIGHT
            tp.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(context)
        }

        fun setMax(m: Int) {
            if (m > 0) {
                maxValue = m
            }
        }

        fun getMax(): Int {
            return maxValue
        }

        fun getProgress(): Int {
            return value
        }

        /** 只改值不动画：用于从偏好恢复初始值。 */
        fun setProgress(v: Int) {
            setProgress(v, false)
        }

        fun setProgress(v: Int, animated: Boolean) {
            val vv = Math.max(0, Math.min(maxValue, v))
            val to = vv.toFloat() / maxValue
            anim?.cancel()
            anim = null
            val changed = vv != value
            value = vv
            if (!animated || !changed) {
                shown = to
                postInvalidateOnAnimation()
                return
            }
            // 【丝滑】值变化时进度胶囊用同一套弹簧推过去，不做硬跳。
            val from = shown
            anim = Springs.drive(Springs.snappy(), object : Springs.Listener {
                override fun onUpdate(q: Float) {
                    shown = Springs.lerp(from, to, q)
                    postInvalidateOnAnimation()
                }

                override fun onEnd() {
                    shown = to
                    postInvalidateOnAnimation()
                }
            })
        }

        fun setOnChange(cb: OnChange?) {
            listener = cb
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthSpec), UiKit.dp(context, H_DP.toFloat()))
        }

        override fun onDraw(g: Canvas) {
            val c = context
            val left = UiKit.dp(c, PAD_LEFT_DP.toFloat()).toFloat()
            val right = (width - UiKit.dp(c, PAD_RIGHT_DP.toFloat())).toFloat()
            if (right <= left) {
                return
            }
            val cy = height / 2f
            val r = UiKit.dp(c, TRACK_DP.toFloat()) / 2f
            p.style = Paint.Style.FILL

            // 1) 底轨：整条胶囊，无色差。
            p.color = UiKit.LINE
            g.drawRoundRect(left, cy - r, right, cy + r, r, r, p)

            // 2) 已选段：同一胶囊，只画到当前进度（最少保留左侧半圆，读得出这条是「滑过的」）。
            var px = left + (right - left) * shown
            if (px < left + r) {
                px = left + r
            }
            p.color = UiKit.ACC
            g.drawRoundRect(left, cy - r, px, cy + r, r, r, p)

            // 3) 刻度点：已选段上的点用反白色（压在胶囊上要看得见），未选段上的点用淡标题色。
            val dot = UiKit.dp(c, 1.7f).toFloat()
            for (i in 1 until SEG) {
                val tx = left + (right - left) * i / SEG.toFloat()
                p.color = if (tx <= px) al(UiKit.ON_ACC, 0xCC) else al(UiKit.TITLE, 0x30)
                g.drawCircle(tx, cy, dot, p)
            }

            // 4) 终点标记：轨道右侧外面一根两端圆角的竖线（参考图里最明显的特征）。
            val half = UiKit.dp(c, 1.8f).toFloat()
            val mx = right + UiKit.dp(c, 6f)
            val hh = UiKit.dp(c, 11f).toFloat()
            p.color = UiKit.ACC
            g.drawRoundRect(mx - half, cy - hh, mx + half, cy + hh, half, half, p)

            // 5) 实时数值：跟随当前值走，拖动时也在变。
            tp.color = UiKit.TITLE
            tp.textSize = UiKit.dp(c, 14f).toFloat()
            g.drawText(
                Math.round(shown * maxValue).toString() + "%",
                (width - UiKit.dp(c, 2f)).toFloat(), cy + UiKit.dp(c, 5f), tp
            )
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            return when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    downX = e.x
                    startProg = shown
                    // 横向拖动期间不让外层 ScrollView 抢事件，否则一滑就变成滚动。
                    val par = parent as? ViewGroup
                    par?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        moveTo(e.x)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val pg = parent as? ViewGroup
                    pg?.requestDisallowInterceptTouchEvent(false)
                    true
                }

                else -> super.onTouchEvent(e)
            }
        }

        /**
         * 相对位移映射：手指挪多少，进度就挪多少。
         *
         * 【为什么不按指尖绝对位置赋值】绝对映射会让手指一按下就把进度拽到指尖处，
         *   细调时非常跳，而且点错一下值就飞了。相对位移更跟手，也和参考图那种「推着走」的手感一致。
         */
        private fun moveTo(x: Float) {
            val c = context
            val left = UiKit.dp(c, PAD_LEFT_DP.toFloat()).toFloat()
            val right = (width - UiKit.dp(c, PAD_RIGHT_DP.toFloat())).toFloat()
            val span = Math.max(1f, right - left)
            var v = startProg + (x - downX) / span
            v = Math.max(0f, Math.min(1f, v))
            shown = v
            val nv = Math.round(v * maxValue)
            if (nv != value) {
                value = nv
                listener?.onChanged(nv, true)
            }
            postInvalidateOnAnimation()
        }

        companion object {
            /** 轨道粗细：与 44×24dp 的自绘开关在视觉重量上对齐。 */
            private const val TRACK_DP = 12

            /** 右侧给「终点标记 + 百分比」留的宽度。 */
            private const val PAD_RIGHT_DP = 58
            private const val PAD_LEFT_DP = 2

            /** 整条轨道的总高度（含终点标记与数字的呼吸空间）。 */
            private const val H_DP = 34

            /** 刻度点把轨道分成的段数。 */
            private const val SEG = 10

            private fun al(color: Int, a: Int): Int {
                return (color and 0x00FFFFFF) or ((a and 0xFF) shl 24)
            }
        }
    }

    /** 【职责】列表/输入框的圆角背景。原 SettingsFold.rowBackground。 */
    @JvmStatic
    fun rowBg(c: Context, color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.setColor(color)
        g.cornerRadius = dp(c, 8f).toFloat()
        return g
    }

    /** 【职责】设置页卡片的白色圆角背景 + 极淡描边。原 SettingsFold.cardBackground。 */
    @JvmStatic
    fun cardBg(c: Context): GradientDrawable {
        val g = round(card(), c, 16f)
        g.setStroke(1, STROKE)
        return g
    }


    /** 【职责】沿 ContextWrapper 向上找宿主 Activity；找不到返回 null。 */
    @JvmStatic
    fun findActivity(c: Context): Activity? {
        var cur: Context? = c
        var i = 0
        while (i < 8 && cur != null) {
            if (cur is Activity) {
                return cur
            }
            if (cur is ContextWrapper) {
                cur = cur.baseContext
            } else {
                break
            }
            i++
        }
        return null
    }
    /* ===== 弹窗与输入框：全工程统一走这里，避免各处弹出系统原生白框。 ===== */

    /** 弹窗标题：16sp 加粗 + 主字色。 */
    @JvmStatic
    fun dialogTitle(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(16.0f)
        t.setTextColor(TITLE)
        t.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        return t
    }

    /** 弹窗按钮：primary=true 走紫渐变主样式，否则白底描边次样式。 */
    @JvmStatic
    fun dialogButton(ctx: Context, text: String, primary: Boolean): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.setTextSize(FS_BTN)
        b.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        val pad = dp(ctx, 12)
        b.setPadding(pad, pad, pad, pad)
        if (primary) {
            UiKit.primary(b, ctx)
        } else {
            secondary(b, ctx)
        }
        // 【按键动画】弹窗按钮同样统一挂按压反馈（与 btn 同一套手感）。
        press(b)
        return b
    }

    /** 弹窗正文说明：13sp 副字色。 */
    @JvmStatic
    fun dialogMessage(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = text
        t.setTextSize(FS_SUB)
        t.setTextColor(SUB)
        t.setLineSpacing(dp(ctx, 3).toFloat(), 1.0f)
        return t
    }

    /** 输入框统一外观：淡紫底圆角 + 内边距 + 主字色（对齐 SettingsPage.addLabeled）。 */
    @JvmStatic
    fun field(e: EditText, c: Context) {
        e.background = rowBg(c, FIELD)
        val pad = dp(c, 12)
        e.setPadding(pad, pad, pad, pad)
        e.setTextSize(FS_BTN)
        e.setTextColor(TITLE)
    }

    /**
     * 自绘弹窗：UiKit 卡片 + 标题 + 内容 + 右侧按钮行。
     * 刻意不用系统 AlertDialog —— 各家 ROM 的原生弹窗是白底 Material 外观，
     * 与全局配色割裂；自绘才能保证暗色 / 纯黑主题下也不刺眼。
     * posText / negText 传 null 表示不显示该按钮；两者都 null 则整行不出现。
     */
    @JvmStatic
    fun showDialog(
        act: Activity?, title: String?, content: View?,
        posText: String?, pos: View.OnClickListener?,
        negText: String?, neg: View.OnClickListener?
    ): Dialog? {
        if (act == null) {
            return null
        }
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.background = round(card(), act, 16f)
        val pad = dp(act, 18)
        col.setPadding(pad, pad, pad, dp(act, 14))

        if (title != null && title.length > 0) {
            col.addView(dialogTitle(act, title))
        }
        if (content != null) {
            val clp = LinearLayout.LayoutParams(-1, -2)
            clp.topMargin = dp(act, 12)
            content.layoutParams = clp
            col.addView(content)
        }
        if (posText != null || negText != null) {
            val bar = LinearLayout(act)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.RIGHT
            val blp = LinearLayout.LayoutParams(-1, -2)
            blp.topMargin = dp(act, 18)
            bar.layoutParams = blp
            if (negText != null) {
                val nb = dialogButton(act, negText, false)
                nb.setOnClickListener { v ->
                    dlg.dismiss()
                    neg?.onClick(v)
                }
                bar.addView(nb)
            }
            if (posText != null) {
                val pb = dialogButton(act, posText, true)
                val plp = LinearLayout.LayoutParams(-2, -2)
                plp.leftMargin = dp(act, 10)
                pb.layoutParams = plp
                pb.setOnClickListener { v ->
                    dlg.dismiss()
                    pos?.onClick(v)
                }
                bar.addView(pb)
            }
            col.addView(bar)
        }
        dlg.setContentView(col)

        val w = dlg.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(0x00000000))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.45f)
            val wide = Math.min(
                (act.resources.displayMetrics.widthPixels * 0.88f).toInt(),
                dp(act, 420)
            )
            w.setLayout(wide, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // 弹窗入场：轻微上浮 + 缩放 + 淡入。
        // 【为何】原先是 show() 后凭空出现，与 App 其他地方的过渡感不一致。
        //   初始态必须在 show() 之前设好，否则会先闪一帧完整尺寸再缩回去。
        col.alpha = 0f
        col.scaleX = 0.94f
        col.scaleY = 0.94f
        col.translationY = dp(act, 14).toFloat()
        col.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(D_MICRO.toLong()).setInterpolator(EASE_DECEL).start()
        dlg.show()
        // 【坑·沉浸同步】必须放在 show() 之后：show 之前窗口 / decor 还没建立，controller 无宿主，
        //   同步调用无效；放在 show 之后才能把状态栏状态同步到已建立的弹窗窗口。
        ImmersiveStatus.syncDialog(dlg)
        return dlg
    }

    /** 【职责】安全取 TextView 文本并 trim；非 TextView 返回 null。 */
    @JvmStatic
    fun textOf(v: View?): String? {
        if (v !is TextView) {
            return null
        }
        val cs = v.text
        return cs?.toString()?.trim()
    }

    /* ================= 统一控件规范：全 App 只用这一套 =================
     * 【背景】此前按钮样式散落在 HomeCards.mkButton / ChatDrawer.flatButton / TokenStat.chip /
     *         ApiConfigPage.pill 等六处，字号 12/13/14/15 混用、圆角 10/12/999 混用，
     *         点到哪个页面靠哪个工厂，观感不连贯。现全部收口到本节，
     *         页面只调工厂，不再散写字号 / 内边距 / 圆角。
     * 【兼容】原有 primary / secondary / press 保持不变，新工厂内部复用它们。
     */

    /** 页面标题字号。 */
    const val FS_TITLE = 20.0f

    /** 副标题 / 注脚字号。 */
    const val FS_SUB = 12.0f

    /** 底部说明文字号。
     *  【需求】11sp 在浅底上偏小偏淡（用户实测反馈「底部小字看不清」），提到 12sp。
     *   不再继续下探：再小一档在 1080p 上已接近可读下限。 */
    const val FS_TINY = 12.0f

    /** 标准按钮字号。 */
    const val FS_BTN = 15.0f

    /** 小药丸 / 状态标签字号。 */
    const val FS_CHIP = 12.0f

    /** 顶栏图标字号。 */
    const val FS_ICON = 20.0f

    /** 图标控件的固定命中区边长（dp）。 */
    const val HIT_DP = 40

    /** 按钮圆角（dp）。 */
    const val RADIUS_BTN = 12

    /** 药丸 / 行 / 输入框圆角（dp）。 */
    const val RADIUS_CHIP = 10

    /**
     * 统一图标按钮：裸贴描边图标 + 固定命中区，靠 press 缩放反馈表达可点。
     * 【为何用 padding 而不是直接给图标尺寸】调用方可能用 iconLp() 之类再覆盖
     *   LayoutParams（命中区要 40dp）。若图标本身跟着容器撑大，视觉就会时大时小；
     *   所以把「视觉边长」钉在 padding 上，容器给多大都不影响图标实际大小。
     */
    @JvmStatic
    fun iconView(ctx: Context, resId: Int, sizeDp: Float, color: Int): ImageView {
        val iv = ImageView(ctx)
        val hit = dp(ctx, HIT_DP.toFloat())
        val pad = Math.max(0, (hit - dp(ctx, sizeDp)) / 2)
        iv.setPadding(pad, pad, pad, pad)
        iv.layoutParams = ViewGroup.LayoutParams(hit, hit)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val d = Icons.get(ctx, resId, color)
        if (d != null) {
            iv.setImageDrawable(d)
        }
        iv.isClickable = true
        iv.isFocusable = false
        press(iv)
        return iv
    }


    /**
     * 统一顶栏：返回键 + 标题 + 副标题（副标题可空）。
     * 【约定】所有独立页的顶栏都走这里，保证返回键位置 / 字号 / 间距完全一致。
     * 返回 null 则不加返回键（用于不需要返回的根页面）。
     */
    @JvmStatic
    fun topBar(ctx: Context, title: String, sub: String?, back: View.OnClickListener?): LinearLayout {
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        // 【显式尺寸】横向撑满、纵向按内容；不显式给出时可能被调用方容器量成内容宽 / 塌高。
        bar.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // 【不被剪】高 padding（含刘海安全区）下允许内容越过 padding 绘制，避免标题 / 返回键被裁掉。
        bar.clipToPadding = false
        // 【最小高度兜底】至少装得下一行图标命中区（HIT_DP）+ 顶部基础间距 + 当前物理顶部安全区；
        //   只设下限不锁死总高度，大字号下仍可随内容撑高。
        bar.minimumHeight = dp(ctx, HIT_DP) + dp(ctx, 12) + cutoutSafeInsets(ctx)[1]
        // 【状态栏嵌入】状态栏透明且已隐藏，顶栏只让开物理 cutout（刘海 / 挖孔）与既有 12dp 间距，
        //   不再按整边圆角放大顶部留白，标题也就不会与状态栏时间 / 电量挤在一起。
        bindCutoutPadding(bar, dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), 0)

        if (back != null) {
            val b = iconView(ctx, Icons.IC_ARROW_LEFT, FS_ICON, TITLE)
            b.setOnClickListener(back)
            val blp = LinearLayout.LayoutParams(dp(ctx, HIT_DP.toFloat()), dp(ctx, HIT_DP.toFloat()))
            blp.rightMargin = dp(ctx, 10)
            bar.addView(b, blp)
        }

        val titles = LinearLayout(ctx)
        titles.orientation = LinearLayout.VERTICAL
        titles.gravity = Gravity.CENTER_VERTICAL
        val t1 = TextView(ctx)
        t1.text = title
        t1.setTextSize(FS_TITLE)
        t1.setTextColor(TITLE)
        t1.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        titles.addView(t1)
        if (sub != null && sub.length > 0) {
            val t2 = TextView(ctx)
            t2.typeface = Fonts.ui(ctx)
            t2.text = sub
            t2.setTextSize(FS_SUB)
            t2.setTextColor(SUB)
            t2.setPadding(0, dp(ctx, 2), 0, 0)
            titles.addView(t2)
        }
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1.0f))
        return bar
    }

    /** 统一小药丸 / 状态标签：CHIP 配色。 */
    @JvmStatic
    fun chip(ctx: Context, text: String): TextView {
        return chip(ctx, text, CHAT_CHIP_FG, CHAT_CHIP_BG)
    }

    /**
     * 统一小药丸：可指定前景 / 背景色，圆角与内边距固定。
     *
     * 【按压反馈】药丸多数被当按钮用（左栏「新建 / 改名」这类），原来点了没有任何反馈。
     *   这里的 press 只在调用方把 clickable 打开后才生效（见 press 内 isClickable 判断），
     *   所以纯展示型 chip 不会被误加动效。
     */
    @JvmStatic
    fun chip(ctx: Context, text: String, fg: Int, bg: Int): TextView {
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = text
        t.setTextSize(FS_CHIP)
        t.setTextColor(fg)
        t.background = round(bg, ctx, RADIUS_CHIP.toFloat())
        t.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
        press(t)
        return t
    }

    /** 紧凑状态标签（比 chip 更小）：用于「启用 / 禁用」这类行内标记。 */
    @JvmStatic
    fun badge(ctx: Context, text: String, fg: Int, bg: Int): TextView {
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = text
        t.setTextSize(FS_TINY)
        t.setTextColor(fg)
        t.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3))
        t.background = round(bg, ctx, 999f)
        return t
    }

    /** 描边药丸：白底 + 淡描边，用于「删除 / 次操作」这类行内按钮。 */
    @JvmStatic
    fun outlineChip(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.typeface = Fonts.ui(ctx)
        t.text = text
        t.setTextSize(FS_CHIP)
        t.setTextColor(TITLE)
        t.isClickable = true
        t.gravity = Gravity.CENTER
        t.background = roundStroke(card(), LINE, ctx, 999f)
        t.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        press(t)
        return t
    }

    /** 主色药丸：紫渐变 + 白字，用于行内主操作（保存 / 重新拉取）。 */
    @JvmStatic
    fun primaryChip(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(FS_CHIP)
        t.setTextColor(ON_ACC)
        t.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        t.isClickable = true
        t.gravity = Gravity.CENTER
        primary(t, ctx)
        t.setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9))
        press(t)
        return t
    }

    /** 统一标准按钮：primary=true 紫渐变主样式，否则白底描边次样式。 */
    @JvmStatic
    fun btn(ctx: Context, text: String, primary: Boolean): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.setTextSize(FS_BTN)
        b.typeface = com.dollhouse.app.ui.theme.Fonts.uiBold(ctx)
        val pad = dp(ctx, 14)
        b.setPadding(pad, pad, pad, pad)
        if (primary) {
            UiKit.primary(b, ctx)
        } else {
            secondary(b, ctx)
        }
        // 【按键动画】按压反馈在工厂里统一挂：调用方只管设 onClick，手感自动一致。
        //   之前 btn 出来的按钮点了没有任何反馈，是全 App 最明显的「不跟手」来源。
        press(b)
        return b
    }


    /* ================= 整页转场：所有二级页共用 =================
     * 【背景】各二级页原先都是 content.removeView(旧) + addView(新)，中间没有任何过渡，
     *         观感是“畴”地一下整屏换掉 —— 这是全 App 最大的突兀源。
     *         这里收口成三个方法，页面只管调用，不再自己拼 remove/add。
     * 【单位】位移一律取容器宽度比例，不写死 dp，适配任意分辨率。
     * 【为何不用 FragmentTransaction / ActivityOptions】本项目二级页不是 Fragment，
     *         而是在 android.R.id.content 上手工叠的覆盖页；改造成 Fragment 会动到
     *         所有页面的生命周期，风险远大于收益，所以就地给叠页加动效。
     */

    /** 屏宽（拿不到布局宽度时兜底用显示宽度）。 */
    private fun pageW(content: ViewGroup): Int {
        val w = content.width
        return if (w > 0) w else content.resources.displayMetrics.widthPixels
    }

    /**
     * 打开一个覆盖页：新页自右侧滑入。
     * 旧覆盖页立即移除 —— 它会被新页完全遮住，不给它做动画反而更干净、也不会残留视图。
     */
    @JvmStatic
    fun openPage(content: ViewGroup?, page: View?, tag: String?) {
        if (content == null || page == null) {
            return
        }
        val old = if (tag == null) null else content.findViewWithTag<View>(tag)
        if (old != null) {
            content.removeView(old)
        }
        page.setTag(tag)
        page.translationX = pageW(content) * 0.14f
        page.alpha = 0f
        content.addView(page, ViewGroup.LayoutParams(-1, -1))
        // 【全局背景】覆盖页自铺一次全局背景（带不透明底，遮住下层同一张图避免叠影），
        // 页内页面级不透明底色统一置透明，图只由页根画一次；卡片自身底色保留。
        GlobalBackground.installPage(page)
        page.animate().translationX(0f).alpha(1f)
            .setDuration(D_PAGE.toLong()).setInterpolator(EASE_DECEL).start()
    }

    /** 同层内容替换（切分段 / 翻日期 / 二级页重建）：只交叉淡入，不做位移，避免方向误导。 */
    @JvmStatic
    fun swapPage(content: ViewGroup?, page: View?, tag: String?) {
        if (content == null || page == null) {
            return
        }
        val old = if (tag == null) null else content.findViewWithTag<View>(tag)
        page.setTag(tag)
        page.alpha = 0f
        content.addView(page, ViewGroup.LayoutParams(-1, -1))
        // 同 openPage：页根铺全局背景，页内页面级底色置透明，避免与宿主重复叠图。
        GlobalBackground.installPage(page)
        page.animate().alpha(1f).setDuration(D_PAGE.toLong()).setInterpolator(EASE_DECEL)
            .withEndAction {
                // 挂在新页（一直在树上）的结束回调上，比挂旧页可靠。
                val oldParent: ViewParent? = if (old == null) null else old.parent
                if (oldParent is ViewGroup) {
                    oldParent.removeView(old)
                }
            }.start()
    }

    /**
     * 关闭一个覆盖页：向右滑出 + 淡出后移除。
     * 【幂等】removeView 对非子 View 是空操作，重复调用安全。
     */
    @JvmStatic
    fun closePage(page: View?) {
        if (page == null) {
            return
        }
        if (page.parent !is ViewGroup) {
            return
        }
        val parent = page.parent as ViewGroup
        page.animate().translationX(pageW(parent) * 0.14f).alpha(0f)
            .setDuration(D_LAYER.toLong()).setInterpolator(EASE_ACCEL)
            .withEndAction {
                parent.removeView(page)
            }.start()
    }

    /**
     * 淡入出现（列表项 / 提示条）——先可见再淡入，绝不用"喷"一下的方式。
     * 【为何】全 App 有 30 处 setVisibility(VISIBLE/GONE) 直接切换，
     *   其中提示条、附件条、记忆状态这类小元素的硬切换最刺眼，这里统一收口。
     */
    @JvmStatic
    fun reveal(v: View?) {
        if (v == null) {
            return
        }
        if (v.visibility != View.VISIBLE) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
        }
        v.animate().cancel()
        v.animate().alpha(1f).setDuration(D_MICRO.toLong()).setInterpolator(EASE_STD).start()
    }

    /** 淡出隐藏（动画结束后才置 GONE，保留布局占位直到真正隐藏）。 */
    @JvmStatic
    fun collapse(v: View?) {
        if (v == null || v.visibility != View.VISIBLE) {
            return
        }
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(D_MICRO.toLong()).setInterpolator(EASE_ACCEL)
            .withEndAction {
                if (v.alpha < 0.05f) {
                    v.visibility = View.GONE
                }
            }.start()
    }

    /** 一行代码表达"要显示就淡入、要隐藏就淡出"。 */
    @JvmStatic
    fun showHide(v: View?, show: Boolean) {
        if (show) {
            reveal(v)
        } else {
            collapse(v)
        }
    }

    /** 文字颜色平滑过渡（权限状态红↔绿、选中态紫↔灰）。 */
    @JvmStatic
    fun setTextColorAnimated(t: TextView?, toColor: Int) {
        if (t == null) {
            return
        }
        val from = t.currentTextColor
        if (from == toColor) {
            return
        }
        val ev = ArgbEvaluator()
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = D_MICRO.toLong()
        va.interpolator = EASE_STD
        va.addUpdateListener { an ->
            val f = an.animatedValue as Float
            t.setTextColor(ev.evaluate(f, from, toColor) as Int)
        }
        va.start()
    }

    /**
     * 平滑滚回顶部。
     * 【为何】scrollTo(0,0) 是瞬时硬拽，卡片折叠、展开更早历史后用这个太生硬。
     *   低版本没有 smoothScrollTo 的 ScrollView 子类仍有 API，直接可用。
     */
    @JvmStatic
    fun scrollToTop(sv: ScrollView?) {
        if (sv == null) {
            return
        }
        sv.post {
            sv.smoothScrollTo(0, 0)
        }
    }

    /**
     * 列表错峰入场：容器每个直接子 View 依次淡入 + 上浮。
     * 【用于】首屏 / 打开面板时一次性挂了很多行，一起出现看着很“顿”；错峰 24ms 就有流动感。
     * 【步长】默认 26ms；行数超 12 时自动压到 14ms，免得底部项等太久。
     */
    @JvmStatic
    fun stagger(col: ViewGroup?) {
        stagger(col, 0)
    }

    @JvmStatic
    fun stagger(col: ViewGroup?, firstDelayMs: Int) {
        if (col == null) {
            return
        }
        val n = col.childCount
        val step = if (n > 12) 14 else 24
        for (i in 0 until n) {
            enter(col.getChildAt(i), firstDelayMs + i * step)
        }
    }

    /** 错峰上限：只给前 12 个错峰，其余立即可见，避免长列表末尾迟迟不出现。 */
    @JvmStatic
    fun staggerCapped(col: ViewGroup?, maxCount: Int) {
        if (col == null) {
            return
        }
        val n = Math.min(col.childCount, maxCount)
        for (i in 0 until n) {
            enter(col.getChildAt(i), i * 24)
        }
    }

    /**
     * 从底部弹上来：遮罩淡入 + 面板上滑。
     * 【用于】ChatDrawer / SheetPanel 这类底部面板，替代原先直接 addView 的硬弹。
     */
    @JvmStatic
    fun slideUpIn(shade: View?, panel: View?) {
        if (shade == null || panel == null) {
            return
        }
        val h = if (panel.height > 0) panel.height.toFloat()
        else panel.resources.displayMetrics.heightPixels * 0.5f
        shade.alpha = 0f
        panel.translationY = h
        shade.animate().alpha(1f).setDuration(D_LAYER.toLong()).setInterpolator(EASE_STD).start()
        panel.animate().translationY(0f).setDuration(D_PAGE.toLong()).setInterpolator(EASE_DECEL).start()
    }

    /** 从左侧推入：抽屉专用（遮罩淡入 + 抽屉右移进屏）。 */
    @JvmStatic
    fun slideInLeft(shade: View?, panel: View?) {
        if (shade == null || panel == null) {
            return
        }
        val w = if (panel.width > 0) panel.width.toFloat()
        else panel.resources.displayMetrics.widthPixels * 0.8f
        shade.alpha = 0f
        panel.translationX = -w
        shade.animate().alpha(1f).setDuration(D_LAYER.toLong()).setInterpolator(EASE_STD).start()
        panel.animate().translationX(0f).setDuration(D_PAGE.toLong()).setInterpolator(EASE_DECEL).start()
    }

    /**
     * 底部面板出场：面板下滑 + 遮罩淡出，结束后整体移除。
     * 取遮罩的子 View 0 当面板（与 SheetPanel.open 的层级一致）。
     */
    @JvmStatic
    fun slideDownOut(shade: View?) {
        slideOut(shade, true)
    }

    /** 左侧抽屉出场：面板左滑 + 遮罩淡出。 */
    @JvmStatic
    fun slideOutLeft(shade: View?) {
        slideOut(shade, false)
    }

    private fun slideOut(shade: View?, down: Boolean) {
        if (shade == null || shade.parent !is ViewGroup) {
            return
        }
        val parent = shade.parent as ViewGroup
        val panel = if (shade is ViewGroup && shade.childCount > 0)
            shade.getChildAt(0) else null
        shade.animate().alpha(0f).setDuration(D_LAYER.toLong()).setInterpolator(EASE_ACCEL).start()
        if (panel != null) {
            val dist = if (down)
                (if (panel.height > 0) panel.height.toFloat()
                else shade.resources.displayMetrics.heightPixels * 0.5f)
            else
                -(if (panel.width > 0) panel.width.toFloat()
                else shade.resources.displayMetrics.widthPixels * 0.8f)
            val oa = if (down)
                ObjectAnimator.ofFloat(panel, "translationY", panel.translationY, dist)
            else
                ObjectAnimator.ofFloat(panel, "translationX", panel.translationX, dist)
            oa.duration = D_LAYER.toLong()
            oa.interpolator = EASE_ACCEL
            oa.start()
        }
        shade.postDelayed({
            parent.removeView(shade)
        }, D_LAYER.toLong())
    }

    /**
     * 换主题 / 重建后的一次性淡入：消除 recreate 那一瞬的闪白。
     * 【为何】ThemeManager 换色后走 act.recreate()，新 Activity 首帧直出，
     *   深色↔浅色跳变时格外刺眼。挂一层与窗口同大的临时遮罩淡出，
     *   等于把"重建"这一帧盖过去，观感变成平滑过渡。
     * 【注意】遮罩必须不可点、且动画结束后一定移除，否则会吃掉全屏触摸。
     */
    @JvmStatic
    fun themeFade(act: Activity?) {
        if (act == null) {
            return
        }
        val win = act.window ?: return
        val decor = win.decorView
        if (decor !is ViewGroup) {
            return
        }
        val root = decor as ViewGroup
        val cover = View(act)
        cover.setBackgroundColor(BG)
        cover.isClickable = false
        root.addView(cover, ViewGroup.LayoutParams(-1, -1))
        cover.animate().alpha(0f).setDuration(D_PAGE.toLong()).setInterpolator(EASE_STD)
            .withEndAction {
                root.removeView(cover)
            }.start()
    }

    /** 淡入 / 淡出到 1f / 0f（不改可见性，只改透明度，避免布局跳动）。 */
    @JvmStatic
    fun fade(v: View?, show: Boolean) {
        if (v == null) {
            return
        }
        v.animate().alpha(if (show) 1f else 0f).setDuration(D_MICRO.toLong())
            .setInterpolator(if (show) EASE_DECEL else EASE_ACCEL).start()
    }

    /** 淡出后移除（遮罩类浮层的统一关闭）。 */
    @JvmStatic
    fun fadeOutRemove(v: View?) {
        if (v == null) {
            return
        }
        if (v.parent !is ViewGroup) {
            return
        }
        val parent = v.parent as ViewGroup
        v.animate().alpha(0f).setDuration(D_MICRO.toLong()).setInterpolator(EASE_ACCEL)
            .withEndAction {
                parent.removeView(v)
            }.start()
    }

    /**
     * 两个视图「交叉淡入淡出」：旧的淡出、新的淡入，中间用一层与背景同色的幕布盖住半途的重影。
     *
     * 【要解决的问题】同一位置上的两块内容互换（列表 ⇄ 空态、页 A ⇄ 页 B），
     *   如果只做 setVisibility，会有一帧的跳变；如果直接叠着淡入淡出，
     *   半途两段文字会同时半透明地压在一起，看着很脏。
     * 【为什么用幕布】ColorDrawable 走 View 的 alpha 通道，不依赖主题字段，
     *   所以整段动画期间用户切换主题也不会崩；幕布只在中间 60% 时间里不透明，
     *   首尾完全透明，观感上就是「旧内容淡出 → 短暂留白 → 新内容淡入」。
     */
    @JvmStatic
    fun fadeSwap(from: View?, to: View?, bgColor: Int) {
        if (to == null || to.parent == null) {
            return
        }
        val c = to.context
        val parent = if (to.parent is ViewGroup) to.parent as ViewGroup else null

        to.alpha = 0f
        to.visibility = View.VISIBLE
        if (from != null && from !== to) {
            from.animate().alpha(0f).setDuration(D_LAYER.toLong()).setInterpolator(EASE_STD).start()
        }
        if (parent is FrameLayout) {
            // 是叠加式容器：用父容器同级的幕布盖住半途，淡完自己移除。
            val scrim = View(c)
            scrim.background = ColorDrawable(bgColor)
            scrim.alpha = 0f
            parent.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            scrim.animate().alpha(1f).setDuration(D_MICRO.toLong()).setInterpolator(EASE_STD)
                .withEndAction {
                    to.animate().alpha(1f).setDuration(D_MICRO.toLong()).setInterpolator(EASE_STD).start()
                    scrim.animate().alpha(0f).setDuration(D_MICRO.toLong()).setInterpolator(EASE_STD)
                        .withEndAction {
                            UiKit.fadeOutRemove(scrim)
                            if (from != null && from !== to) {
                                from.visibility = View.GONE
                                from.alpha = 1f
                            }
                        }.start()
                }.start()
            return
        }
        // 普通 LinearLayout 之类：直接让新的淡入，不叠幕布（幕布会把兄弟节点压住）。
        to.animate().alpha(1f).setDuration(D_MICRO.toLong()).setInterpolator(EASE_STD).start()
        if (from != null && from !== to) {
            from.visibility = View.GONE
            from.alpha = 1f
        }
    }
}
