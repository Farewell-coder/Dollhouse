package com.dollhouse.app.ui.theme

import android.content.Context
import android.view.View
import android.widget.TextView

/**
 * 【职责】全 App 的 UI 设计令牌与语义映射层。
 *
 * 【为什么单独一个文件】这是「建立本软件自己的 UI 设计体系」的落点：
 *   尺寸、间距、语义色、状态语义 → 图标，全部收口在这里。
 *   页面代码只认 [Design.Status]、[Design.icons]、[Design.SZ_*]，
 *   不再各自 setTextSize(17f)、不再各自挑图标、不再各自写状态色分支。
 *
 * 【与 UiKit 的分工】UiKit 管「颜色字段 + 动画 + 通用构件」，
 *   Design 管「尺寸刻度 + 状态语义 + 图标映射」。两者都不引 View 树结构。
 *
 * 【为什么状态色不新增调色板】ThemeManager 的调色板是定长数组（PAL_SIZE=29），
 *   cachedMonetPalette() 会校验长度，历史上有过「莫奈开关能开、配色却不变」的故障。
 *   警告语义直接复用已有的 HINT_FG / HINT_BG（三档主题下都是琥珀，本来就表达注意），
 *   数组长度不变 —— 零风险，且切主题自动跟随。
 *
 * 【硬约束】颜色一律走 UiKit 的可变字段，不写裸色值。
 */
object Design {

    /* ============================ 尺寸刻度 ============================ */

    /*
     * 【图标尺寸只允许这几档】历史问题：页面里到处出现 13f / 14f / 17f / 18f / 19f 这类随手值，
     *   同一层级的图标视觉重量不一致，页面看着「毛」。收口成 5 档后，
     *   谁在什么位置用哪一档是明确的，不再靠感觉。
     */
    /** 行内极小图标（备注、次级标记）。 */
    const val SZ_XS = 14.0f
    /** 行内小图标（列表行主图标、状态徽标）。 */
    const val SZ_SM = 16.0f
    /** 操作项图标（动作行左侧、AI 能力项）。 */
    const val SZ_MD = 20.0f
    /** 主要状态图标 / 顶栏图标。 */
    const val SZ_LG = 24.0f
    /** 强调图标（空状态、大状态块）。 */
    const val SZ_XL = 28.0f

    /** 图标与紧邻文字的默认间距（dp）。 */
    const val GAP_ICON = 8

    /* ------------ 间距刻度（dp）------------ */

    /** 卡片内边距。 */
    const val PAD_CARD = 16
    /** 卡片之间的垂直间距。 */
    const val GAP_CARD = 12
    /** 操作行的高度（保证点击区域 ≥ 48dp）。 */
    const val H_ROW = 48
    /** 区块标题与内容的间距。 */
    const val GAP_SECTION = 8

    /* ============================ 语义色 ============================ */

    /*
     * Status / Accent / Disabled 三类语义。
     * 一律现读 UiKit 字段（它是可变的，切主题后被 ThemeManager 覆写），
     * 所以这里必须用 fun 而不是 val —— 用 val 会把切主题前的旧色冻住。
     */

    /** 成功：只在「真的可用 / 真的成功」时用。 */
    fun ok(): Int = UiKit.OK

    /** 警告：需要用户注意，但不是失败（未安装、待处理、不可逆提示）。 */
    fun warn(): Int = UiKit.HINT_FG

    /** 警告淡底，与 [warn] 配套做徽标背景。 */
    fun warnBg(): Int = UiKit.HINT_BG

    /** 失败 / 异常。 */
    fun err(): Int = UiKit.ERR

    /** 主要交互（可点击的主操作）。 */
    fun accent(): Int = UiKit.ACC

    /** 中性信息（正文、说明、次级信息）。 */
    fun neutral(): Int = UiKit.SUB

    /**
     * 不可操作。
     *
     * 【为什么不是「灰色文字」】置灰靠给整行设 alpha，
     *   这样同时把图标、主文案、副文案一起压下去，
     *   不会出现「文字灰了、图标还亮着」的破绽（见 [applyDisabled]）。
     */
    fun disabledAlpha(): Float = 0.38f

    /** 把「不可操作」的视觉一次性应用到整行。 */
    fun applyDisabled(row: View?, disabled: Boolean) {
        if (row == null) {
            return
        }
        row.alpha = if (disabled) disabledAlpha() else 1.0f
        row.isEnabled = !disabled
    }

    /** 给文字设语义色（带插值，状态跳变时不「啪」地换色）。 */
    fun tintOf(status: Status): Int = status.color()

    /* ============================ 服务状态语义 ============================ */

    /**
     * 设备服务的真实状态。
     *
     * 【为什么要建这组枚举】改造前只有「运行中 / 已安装，未运行 / 未安装」三种文字，
     *   颜色只有绿 / 红两态，用户看不出「正在启动」与「已停」的区别，
     *   图标也只有勾 / 叉两种，语义含糊。
     *   这里把【文字 + 颜色 + 图标】三者绑定在同一个枚举上，
     *   任何一处要改都改这一个地方，页面不再自己 if-else 拼状态。
     */
    enum class Status {
        /** 服务器包和解包产物都不在。 */
        NOT_INSTALLED,

        /**
         * 本地有文件但体量对不上（下载中断留下的半截包 / .part）。
         *
         * 【为什么单列一态】改造前这种情况显示「未安装」，但下载按钮同时被置灰，
         *   用户看到的是「说没装、又不让下」的自相矛盾（审查结论 A4）。
         *   它有明确的出路（先删残留再重下），所以必须说出来。
         */
        PARTIAL,

        /** 包已下载但还没解包。 */
        PENDING_UNPACK,

        /** 已解包安装，但服务没在跑。 */
        STOPPED,

        /** 正在启动（点击后到探活成功之间）。 */
        STARTING,

        /** 正在运行。 */
        RUNNING,

        /** 正在停止。 */
        STOPPING,

        /** 异常（动作失败 / 探测到不一致）。 */
        ERROR,

        /**
         * 无法检测。
         *
         * 【为什么需要这一态】安装与启停全都走 Shizuku，Shizuku 没就绪时根本查不到
         *   服务到底装没装、跑没跑。改造前这种情况会直接显示「未安装」，是明确的误报
         *   （审查 A5）。宁可如实说「无法检测」，也不能拿一个猜的结论糊弄用户。
         */
        UNKNOWN;

        /** 状态文案：图标之外必须同时有文字，不能只靠颜色或图标表意。 */
        fun label(): String = when (this) {
            NOT_INSTALLED -> "未安装"
            PARTIAL -> "下载未完成"
            PENDING_UNPACK -> "已下载，待解包"
            STOPPED -> "已安装，未运行"
            STARTING -> "启动中"
            RUNNING -> "运行中"
            STOPPING -> "停止中"
            ERROR -> "异常"
            UNKNOWN -> "无法检测"
        }

        /** 状态语义色。 */
        fun color(): Int = when (this) {
            RUNNING -> Design.ok()
            STARTING, STOPPING -> Design.accent()
            STOPPED, NOT_INSTALLED, PENDING_UNPACK, PARTIAL, UNKNOWN -> Design.warn()
            ERROR -> Design.err()
        }

        /** 状态底衬色（徽标背景）。 */
        fun bg(): Int = when (this) {
            RUNNING -> UiKit.okBg()
            ERROR -> UiKit.errBg()
            else -> Design.warnBg()
        }

        /** 状态图标：与本枚举一一对应，页面不自行挑图。 */
        fun icon(): Int = when (this) {
            NOT_INSTALLED -> Icons.IC_CIRCLE_ALERT
            PARTIAL -> Icons.IC_CIRCLE_ALERT
            UNKNOWN -> Icons.IC_CIRCLE_ALERT
            PENDING_UNPACK -> Icons.IC_PACKAGE_OPEN
            STOPPED -> Icons.IC_X_CIRCLE
            STARTING, STOPPING -> Icons.IC_SPINNER
            RUNNING -> Icons.IC_CHECK_CIRCLE
            ERROR -> Icons.IC_WARNING
        }

        /** 该状态是否表示「此刻正忙」（用于禁掉重复触发）。 */
        fun busy(): Boolean = this == STARTING || this == STOPPING
    }

    /* ============================ 图标语义映射 ============================ */

    /**
     * 业务语义 → 图标资源。业务代码只认这里，不直接写 R.drawable.* 或具体图标常量，
     * 以后换图标只改这一处（需求第 25 条）。
     */
    object icons {
        /*
         * 【为什么是 val 而不是 const val】右值来自 Icons.IC_*，那是运行期常量（R.drawable.* 由 aapt 生成），
         *   不满足 Kotlin「编译期常量」的要求，写成 const val 会直接编译不过。
         */
        /** 返回。 */
        val BACK = Icons.IC_ARROW_LEFT

        /** 进入下一级。 */
        val ENTER = Icons.IC_CHEVRON_RIGHT

        /** 展开 / 收起。 */
        val EXPAND = Icons.IC_CHEVRON_DOWN
        val COLLAPSE = Icons.IC_CHEVRON_UP

        /** 关闭。 */
        val CLOSE = Icons.IC_CLOSE

        /** 服务本体。 */
        val SERVER = Icons.IC_SERVER

        /** 下载。 */
        val DOWNLOAD = Icons.IC_DOWNLOAD

        /** 解包 / 安装。 */
        val UNPACK = Icons.IC_PACKAGE_OPEN
        val INSTALL = Icons.IC_PACKAGE_PLUS

        /** 启停。 */
        val START = Icons.IC_PLAY
        val STOP = Icons.IC_SQUARE
        val RESTART = Icons.IC_ROTATE_CCW

        /** 删除（危险）。 */
        val DELETE = Icons.IC_TRASH

        /** 连接 / 网络 / 终端。 */
        val PLUG = Icons.IC_PLUG
        val NETWORK = Icons.IC_NETWORK
        val TERMINAL = Icons.IC_TERMINAL
        val COMMAND = Icons.IC_SQUARE_TERMINAL

        /** 设备控制能力。 */
        val APP = Icons.IC_APP_WINDOW
        val TAP = Icons.IC_MOUSE_POINTER_2
        val LONG_PRESS = Icons.IC_HAND
        val SWIPE = Icons.IC_MOVE
        val HOME = Icons.IC_HOUSE
        val WAKE = Icons.IC_SUN
        val KEYBOARD = Icons.IC_KEYBOARD
        val CLIPBOARD = Icons.IC_CLIPBOARD
        val OBSERVE = Icons.IC_SCAN

        /** AI。 */
        val AI = Icons.IC_SPARKLES
    }

    /* ============================ 小工具 ============================ */

    /**
     * 给一枚状态文字挂上「图标 + 颜色」，三个属性一次设齐。
     * 【为什么强制一起设】历史上出现过「只 setText 忘了 setTextColor」「只改色忘了换图标」，
     *   结果就是四态一色、或勾叉对不上。绑定成一个动作，漏不掉。
     */
    fun applyStatus(tv: TextView?, status: Status, sizeDp: Float) {
        if (tv == null) {
            return
        }
        tv.text = status.label()
        UiKit.setTextColorAnimated(tv, status.color())
        Icons.stateIcon(tv, status.icon(), status.color(), sizeDp, GAP_ICON / 2)
    }

    /** dp 快捷方式（供本对象内部使用，避免每个调用点都写 UiKit.dp）。 */
    fun dp(ctx: Context, v: Int): Int = UiKit.dp(ctx, v.toFloat())
}