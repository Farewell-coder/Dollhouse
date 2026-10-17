package com.dollhouse.app.ui.theme

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dollhouse.app.R

/**
 * 图标工厂。
 *
 * 【风格】描边图标（24dp 视口 / 线宽 2 / 圆角端点），路径参照 Lucide（ISC 协议）。
 *   资源位于 res/drawable/ic_*.xml，全部只写白色占位色，真色由本类在运行时上色。
 *
 * 【为什么必须运行时上色】主题有浅色 / 深色 / 莫奈三态，图标颜色要跟着
 *   UiKit.TITLE / TITLE2 / ACC 走。VectorDrawable 的 setTint 是 API 21+，
 *   工程 minSdk 24，无兼容性顾虑。
 *
 * 【收口】所有资源 id 以 IC_* 常量暴露，替换点只改一行。
 */
object Icons {

    // ---- 导航 ----
    @JvmField
    val IC_CHEVRON_RIGHT = R.drawable.ic_chevron_right
    @JvmField
    val IC_CHEVRON_DOWN = R.drawable.ic_chevron_down
    @JvmField
    val IC_CHEVRON_UP = R.drawable.ic_chevron_up
    @JvmField
    val IC_ARROW_LEFT = R.drawable.ic_arrow_left
    @JvmField
    val IC_CHEVRON_LEFT = R.drawable.ic_chevron_left
    @JvmField
    val IC_CLOSE = R.drawable.ic_close
    @JvmField
    val IC_MORE = R.drawable.ic_more

    // ---- 操作 ----
    @JvmField
    val IC_SEND = R.drawable.ic_send
    @JvmField
    val IC_PLUS = R.drawable.ic_plus
    @JvmField
    val IC_MINUS = R.drawable.ic_minus
    @JvmField
    val IC_CHECK = R.drawable.ic_check

    @JvmField
    val IC_INFO = R.drawable.ic_info

    @JvmField
    val IC_BRAIN = R.drawable.ic_brain
    @JvmField
    val IC_IMAGE = R.drawable.ic_image
    @JvmField
    val IC_SETTINGS = R.drawable.ic_settings
    @JvmField
    val IC_WHALE = R.drawable.ic_whale
    @JvmField
    val IC_EDIT = R.drawable.ic_edit
    @JvmField
    val IC_TRASH = R.drawable.ic_trash
    /** 保存（Lucide save）：把当前编辑落盘并返回。 */
    @JvmField
    val IC_SAVE = R.drawable.ic_save
    @JvmField
    val IC_COPY = R.drawable.ic_copy
    @JvmField
    val IC_REFRESH = R.drawable.ic_refresh
    @JvmField
    val IC_EXTERNAL = R.drawable.ic_external
    /** 模型集合 / 分层语义（Lucide layers）：详情页「模型」tab 与模型列表用。 */
    @JvmField
    val IC_LAYERS = R.drawable.ic_layers

    // ---- 【补全】资源早就在 res/drawable/，原先只以 R.drawable.* 直接引用，
    //      没有收口成常量。统一暴露后，调用点只认 Icons.IC_*，换图标只改一行。----
    @JvmField
    val IC_CHAT = R.drawable.ic_chat
    @JvmField
    val IC_CHECK_CIRCLE = R.drawable.ic_check_circle
    @JvmField
    val IC_X_CIRCLE = R.drawable.ic_x_circle
    @JvmField
    val IC_WARNING = R.drawable.ic_warning
    @JvmField
    val IC_TERMINAL = R.drawable.ic_terminal
    @JvmField
    val IC_SPINNER = R.drawable.ic_spinner
    @JvmField
    val IC_TILE_PET = R.drawable.ic_tile_pet

    /* ================= 设备控制 / 服务状态图标层（官方 Lucide 几何） =================
     * 【为什么单独一段】这一组是本次「统一图标语言」新增的官方几何图标，
     *   与上面历史遗留的自绘图标区分开，便于日后整体替换与核对。
     *   资源全部来自 lucide-static 官方 SVG，24dp 视口 / 线宽 2 / 圆角端点。
     * 【调用方式】业务代码不要直接写 R.drawable.*，走 Design.icons.* 语义映射。
     */
    /** 服务 / 状态。 */
    @JvmField
    val IC_CIRCLE_ALERT = R.drawable.ic_circle_alert
    /* triangle-alert 在转换时已按命名表映射到既有资源 ic_warning，语义完全一致，
       所以这里不另开常量，沿用 IC_WARNING，避免两个名字指同一个东西。 */
    @JvmField
    val IC_SERVER = R.drawable.ic_server
    /** 安装 / 解包 / 下载。 */
    @JvmField
    val IC_DOWNLOAD = R.drawable.ic_download
    @JvmField
    val IC_PACKAGE_OPEN = R.drawable.ic_package_open
    @JvmField
    val IC_PACKAGE_PLUS = R.drawable.ic_package_plus
    /** 启停 / 重启。 */
    @JvmField
    val IC_PLAY = R.drawable.ic_play
    @JvmField
    val IC_SQUARE = R.drawable.ic_square
    @JvmField
    val IC_ROTATE_CCW = R.drawable.ic_rotate_ccw
    @JvmField
    val IC_ROTATE_CW = R.drawable.ic_rotate_cw
    /** 连接 / 网络 / 终端。 */
    @JvmField
    val IC_PLUG = R.drawable.ic_plug
    @JvmField
    val IC_NETWORK = R.drawable.ic_network
    @JvmField
    val IC_SQUARE_TERMINAL = R.drawable.ic_square_terminal
    /** 设备控制能力。 */
    @JvmField
    val IC_APP_WINDOW = R.drawable.ic_app_window
    @JvmField
    val IC_MOUSE_POINTER_2 = R.drawable.ic_mouse_pointer_2
    @JvmField
    val IC_HAND = R.drawable.ic_hand
    @JvmField
    val IC_MOVE = R.drawable.ic_move
    @JvmField
    val IC_HOUSE = R.drawable.ic_house
    @JvmField
    val IC_SUN = R.drawable.ic_sun
    @JvmField
    val IC_KEYBOARD = R.drawable.ic_keyboard
    @JvmField
    val IC_CLIPBOARD = R.drawable.ic_clipboard
    @JvmField
    val IC_SCAN = R.drawable.ic_scan
    @JvmField
    val IC_SPARKLES = R.drawable.ic_sparkles
    @JvmField
    val IC_EYE = R.drawable.ic_eye
    @JvmField
    val IC_EYE_OFF = R.drawable.ic_eye_off
    @JvmField
    val IC_SLIDERS_HORIZONTAL = R.drawable.ic_sliders_horizontal
    @JvmField
    val IC_USER_ROUND = R.drawable.ic_user_round
    @JvmField
    val IC_LAYOUT_TEMPLATE = R.drawable.ic_layout_template
    @JvmField
    val IC_FOLDER_OPEN = R.drawable.ic_folder_open
    @JvmField
    val IC_CROSSHAIR = R.drawable.ic_crosshair
    /** 【r13】命名（Lucide signature）：给动作 / 人偶改名字。 */
    @JvmField
    val IC_SIGNATURE = R.drawable.ic_signature
    /** 【r13】改封面（Lucide image-plus）：换一张封面图。 */
    @JvmField
    val IC_IMAGE_PLUS = R.drawable.ic_image_plus

    /* ================= 【r13·导航改造】三 Tab 与聊天列表用图标 =================
     * 【来源】全部取自 lucide-static 官方 SVG（ISC 协议）v1.55.0，
     *   经 /root/dh_lucide/convert.py 转成 VectorDrawable，与本工程既有图标
     *   同一风格（24dp 视口 / 线宽 2 / 圆角端点 / 白色占位描边）。
     * 【为什么另起一段】这一组是本次「聊天列表成为默认首页」新增的语义图标，
     *   与设备控制那批分开登记，日后核对来源时一目了然。
     */
    /** 聊天 Tab / 会话列表（Lucide message-square：方形气泡，比 message-circle 更像「列表」）。 */
    @JvmField
    val IC_MESSAGE_SQUARE = R.drawable.ic_message_square
    /** 人偶 Tab（Lucide users：双人）。 */
    @JvmField
    val IC_USERS = R.drawable.ic_users
    /* 【不重复登记 user-round】它在设备控制那段已经叫 [IC_USER_ROUND]（第 180 行），
       语义与几何完全一致，这里再开一个别名只会让「同一个图标两个名字」——
       正是本项目在 triangle-alert 上刻意避免过的情况。需要时直接用 IC_USER_ROUND。 */
    /** 添加人偶（Lucide user-plus）。 */
    @JvmField
    val IC_USER_PLUS = R.drawable.ic_user_plus

    /* ================= 【r14·外观 / 图谱 / 分组 语义图标层】 =================
     * 【来源】全部取自 lucide-static 官方 SVG（ISC 协议）main 分支，
     *   经 /root/dh_lucide/convert.py 转成 VectorDrawable，与既有图标同一风格
     *   （24dp 视口 / 线宽 2 / 圆角端点 / 白色占位描边，运行期由 [get] 上色）。
     * 【为什么另起一段】这一组是 r14 新增页面的语义图标（外观四 Tab、记忆图谱、
     *   人偶分组、我的资料、弹窗与卡片）。单独登记便于日后核对来源与替换。
     * 【命名约定】一律 IC_ + 下划线大写下划线名，与 Lucide 原名的连字符一一对应。
     */

    // ---- 外观 / 主题 ----
    /** 调色板（Lucide palette）：外观页整体入口 / 主题 Tab。 */
    @JvmField
    val IC_PALETTE = R.drawable.ic_palette
    /** 水滴（Lucide droplet）：不透明度类滑条。 */
    @JvmField
    val IC_DROPLET = R.drawable.ic_droplet
    /** 对比度（Lucide contrast）：可读性遮罩强度。 */
    @JvmField
    val IC_CONTRAST = R.drawable.ic_contrast
    /** 光圈（Lucide aperture）：毛玻璃 / 模糊半径。 */
    @JvmField
    val IC_APERTURE = R.drawable.ic_aperture
    /** 卡片堆叠（Lucide square-stack）：弹窗与卡片 Tab。 */
    @JvmField
    val IC_SQUARE_STACK = R.drawable.ic_square_stack
    /** 方形气泡组（Lucide messages-square）：聊天界面 Tab。 */
    @JvmField
    val IC_MESSAGES_SQUARE = R.drawable.ic_messages_square

    // ---- 记忆图谱 ----
    /** 路径点（Lucide waypoints）：记忆图谱主干入口。 */
    @JvmField
    val IC_WAYPOINTS = R.drawable.ic_waypoints
    /** 节点（Lucide circle-dot）：图谱节点 / 记忆条目。 */
    @JvmField
    val IC_CIRCLE_DOT = R.drawable.ic_circle_dot
    /** 连线（Lucide link-2）：图谱关系边 / 手动关联。 */
    @JvmField
    val IC_LINK_2 = R.drawable.ic_link_2
    /** 放大（Lucide zoom-in）：图谱放大。 */
    @JvmField
    val IC_ZOOM_IN = R.drawable.ic_zoom_in
    /** 缩小（Lucide zoom-out）：图谱缩小。 */
    @JvmField
    val IC_ZOOM_OUT = R.drawable.ic_zoom_out
    /** 最大化（Lucide maximize）：图谱「适配屏幕」。 */
    @JvmField
    val IC_MAXIMIZE = R.drawable.ic_maximize

    /** 新建文件夹（Lucide folder-plus）：新建分组。 */
    @JvmField
    val IC_FOLDER_PLUS = R.drawable.ic_folder_plus
    /** 文件夹树（Lucide folder-tree）：分组层级 / 管理分组。 */
    @JvmField
    val IC_FOLDER_TREE = R.drawable.ic_folder_tree

    // ---- 我的资料 / 说说 ----
    /** 我的头像（Lucide circle-user）：用户自身资料。 */
    @JvmField
    val IC_CIRCLE_USER = R.drawable.ic_circle_user
    /** 引号（Lucide quote）：个性签名。 */
    @JvmField
    val IC_QUOTE = R.drawable.ic_quote
    /** 喇叭（Lucide megaphone）：说说 / 公告。 */
    @JvmField
    val IC_MEGAPHONE = R.drawable.ic_megaphone
    /** 报纸（Lucide newspaper）：说说动态流。 */
    @JvmField
    val IC_NEWSPAPER = R.drawable.ic_newspaper

    /* ================= 【r16·图标扩充层】Lucide 全量补充 =================
     * 【来源】全部取自 lucide-icons/lucide 官方仓库（ISC 协议）main 分支，
     *   经 /root/dh_lucide/convert.py 转成 VectorDrawable，与本工程既有图标同一风格
     *   （24dp 视口 / 线宽 2 / 圆角端点 / 白色占位描边，运行期由 [get] 上色）。
     *
     * 【为什么扩充】用户要求「充分利用 Lucide、尽量大量应用新图标、不要重复、
     *   图标要符合文字描述、不要奇怪突兀」。本次先盘点出**全部无图标的功能入口**
     *   （实验卡片 5 个入口、记忆规则保存、开发者选项、动作来源选择、人格格式选择等），
     *   再按每处**文字的实际语义**挑一枚意思对得上的 Lucide 图标 —— 不是为凑数硬塞。
     *
     * 【命名约定】一律 IC_ + Lucide 原名（连字符转下划线）。
     * 【不重复原则】同一个图标的两个名字是本项目刻意避免过的（见 triangle-alert 注释），
     *   所以下面每个常量都对应**一个不同几何**，已核对与上面各段无重名同形。
     */

    // ---- 实验卡片的 5 个入口 ----
    /** 游戏手柄（Lucide gamepad-2）：设备控制类入口（lamda 设备控制）。 */
    @JvmField
    val IC_GAMEPAD_2 = R.drawable.ic_gamepad_2
    /** 连线插头（Lucide cable）：服务连接 / 设备桥接语义。 */
    @JvmField
    val IC_CABLE = R.drawable.ic_cable
    /** 书本（Lucide book-open）：记忆库 / 知识库设置。 */
    @JvmField
    val IC_BOOK_OPEN = R.drawable.ic_book_open
    /** 聊天气泡与心（Lucide message-square-heart）：对话情感 / 对话显示策略。 */
    @JvmField
    val IC_MESSAGE_SQUARE_HEART = R.drawable.ic_message_square_heart
    /** 机器人（Lucide bot）：模型 / AI 能力相关设置。 */
    @JvmField
    val IC_BOT = R.drawable.ic_bot

    // ---- 动作 / 资源来源 ----
    /** 仪表盘（Lucide gauge）：速度 / 性能类调节（动作速度）。 */
    @JvmField
    val IC_GAUGE = R.drawable.ic_gauge
    /** 积木块（Lucide blocks）：动作模块 / 组件化的集合。 */
    @JvmField
    val IC_BLOCKS = R.drawable.ic_blocks
    /** 图书馆（Lucide library）：动作库 / 内置资源库。 */
    @JvmField
    val IC_LIBRARY = R.drawable.ic_library
    /** 打包箱（Lucide package）：模型包 / 素材包（与 package-open 是「箱合 vs 箱开」两个状态）。 */
    @JvmField
    val IC_PACKAGE = R.drawable.ic_package
    /** 标签组（Lucide tags）：分组 / 分类标记。 */
    @JvmField
    val IC_TAGS = R.drawable.ic_tags
    /** 魔法棒（Lucide wand-sparkles）：AI 生成 / 智能配置。 */
    @JvmField
    val IC_WAND_SPARKLES = R.drawable.ic_wand_sparkles

    // ---- 安全 / 凭据 ----
    /** 盾牌对勾（Lucide shield-check）：安全 / 已校验 / 凭据保护。 */
    @JvmField
    val IC_SHIELD_CHECK = R.drawable.ic_shield_check
    /** 钥匙（Lucide key-round）：API 密钥 / 凭据。 */
    @JvmField
    val IC_KEY_ROUND = R.drawable.ic_key_round

    // ---- 人格 / 情感 ----
    /** 带心的书（Lucide book-heart）：人设 / 性格设定。 */
    @JvmField
    val IC_BOOK_HEART = R.drawable.ic_book_heart
    /** 握手（Lucide heart-handshake）：互动 / 陪伴语义。 */
    @JvmField
    val IC_HEART_HANDSHAKE = R.drawable.ic_heart_handshake

    // ---- 时间 / 其他 ----
    /** 时钟（Lucide clock-3）：时间 / 定时相关（暂离倒计时、自动保活周期）。 */
    @JvmField
    val IC_CLOCK_3 = R.drawable.ic_clock_3
    /** 取图并按 color 上色。返回的 Drawable 每次都是新实例，可安全用于不同 View。 */
    @JvmStatic
    fun get(ctx: Context?, resId: Int, color: Int): Drawable? {
        if (ctx == null) {
            return null
        }
        var d: Drawable? = null
        try {
            d = ctx.resources.getDrawable(resId, ctx.theme)
        } catch (ignored: Throwable) {
            // 资源缺失时静默返回 null，调用方按「无图」处理，不让 UI 崩。
        }
        if (d != null) {
            d = d.mutate()
            try {
                d.setTint(color)
            } catch (ignored: Throwable) {
            }
        }
        return d
    }

    /** 直接出一个图标 ImageView（sizeDp 为边长，含内边距由调用方自行设）。 */
    @JvmStatic
    fun view(ctx: Context, resId: Int, sizeDp: Float, color: Int): ImageView {
        val iv = ImageView(ctx)
        val size = UiKit.dp(ctx, sizeDp)
        iv.layoutParams = ViewGroup.LayoutParams(size, size)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val d = get(ctx, resId, color)
        if (d != null) {
            iv.setImageDrawable(d)
        }
        iv.isClickable = false
        iv.isFocusable = false
        return iv
    }

    /**
     * 出「图标 + 文字」横排：图标在左，文字在右，居中垂直对齐。
     * 用于原来写成「ⓘ 摘要」「✗ 还没填」这类图标嵌入文字的场合。
     */
    @JvmStatic
    fun labeled(ctx: Context, resId: Int, sizeDp: Float,
                color: Int, label: View?,
                gapDp: Int): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val iv = view(ctx, resId, sizeDp, color)
        row.addView(iv)
        if (label != null) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = UiKit.dp(ctx, gapDp)
            row.addView(label, lp)
        }
        return row
    }

    /**
     * 给文字左侧挂一枚语义图标（已授权 ✓ / 未授权 ✗ 这类状态标记）。
     *
     * 【为什么用复合 drawable，而不是再 add 一个 ImageView】
     *   权限行 / 状态行的右侧状态控件是「这一行的第 1 个子 View」——
     *   HomeUi.bindPermRow 与 HomeCards.setRowValue 都按这个下标取字改色。
     *   在文字左边再插一个 ImageView 会让子 View 下标整体错位，所有按位取字的
     *   代码全部失准。复合 drawable 不占子 View 位，索引结构零改动。
     *
     * 【为什么必须先 setBounds】TextView 画复合 drawable 时按 drawable 自身的
     *   intrinsic 尺寸铺；显式 setBounds 才能让 24dp 视口的图标稳定落在 sizeDp 上。
     *
     * @param resId  Icons.IC_* 常量；传 0 表示清除已有图标。
     * @param color  图标色，通常与同行状态文字同色（UiKit.OK / ERR / SUB）。
     * @param sizeDp 图标边长（dp）。
     * @param gapDp  图标与文字的间距（dp）。
     */
    @JvmStatic
    fun stateIcon(tv: TextView?, resId: Int, color: Int, sizeDp: Float, gapDp: Int) {
        if (tv == null) {
            return
        }
        val ctx = tv.context
        if (resId == 0 || ctx == null) {
            tv.setCompoundDrawables(null, null, null, null)
            return
        }
        val d = get(ctx, resId, color)
        if (d == null) {
            tv.setCompoundDrawables(null, null, null, null)
            return
        }
        val size = UiKit.dp(ctx, sizeDp)
        d.setBounds(0, 0, size, size)
        tv.setCompoundDrawablePadding(UiKit.dp(ctx, gapDp))
        tv.setCompoundDrawables(d, null, null, null)
    }

    /** 换主题后刷新一个 ImageView 的图标颜色（保持图片资源不变）。 */
    @JvmStatic
    fun tint(iv: ImageView?, color: Int) {
        if (iv == null) {
            return
        }
        val d = iv.drawable
        if (d != null) {
            try {
                d.mutate().setTint(color)
                iv.invalidate()
            } catch (ignored: Throwable) {
            }
        }
    }
}
