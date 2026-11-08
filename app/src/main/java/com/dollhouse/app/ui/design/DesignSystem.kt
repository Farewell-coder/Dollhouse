package com.dollhouse.app.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】全 App **唯一**的 Compose 设计系统定义：色彩语义、字体阶梯、间距阶梯、
 * 圆角阶梯、高度层级、动效规格。
 *
 * 【为什么需要这个文件】
 *   改造前同一个语义存在三套刻度：
 *     · `ui/theme/Design.kt`   → SZ_XS/SM/MD/LG/XL = 14/16/20/24/28、PAD_CARD=16、GAP_CARD=12、H_ROW=48
 *     · `ui/compose/DhKit.kt`  → R_CARD=16、R_ROW=10、GAP_CARD=10、LH_TITLE=29、TAB_TITLE_SP=26
 *     · `ui/theme/UiKit.kt`    → FS_TITLE=20、FS_BTN=15、FS_SUB=12、HIT_DP=40
 *   于是「卡片间距 12 还是 10」「图标间距 8 还是 10」在不同页面各答各的，
 *   页面之间看着「不是同一个团队做的」。本文件把这些收敛成一套，其它入口只做委托。
 *
 * 【颜色的唯一真源仍然是 [UiKit]】
 *   本文件**不存颜色值**，只把 `UiKit` 的 29 个可变字段映射成 Compose 的语义槽。
 *   原因：`ThemeManager.apply()` 覆写的是 UiKit 的 `@JvmField var`，View 侧（桌宠自绘、
 *   悬浮窗）也在读同一批字段。若这里自建第二份配色，必然出现「Compose 页一个色、
 *   桌宠另一个色」的撕裂 —— 这正是改造前 DhTheme 头注释里反复强调的禁忌。
 *
 * 【为什么不扩 PAL_SIZE】
 *   `ThemeManager` 的调色板是定长数组（29 格），`cachedMonetPalette()` 校验长度不符就整体
 *   丢弃取色结果（历史故障：莫奈开关能开、配色却不变）。所以本文件新增的语义
 *   （surface / surfaceRaised / outline / disabled …）全部用**派生计算**得到，
 *   不占数组槽位，切主题时跟着源色自动变。
 *
 * 【设计依据】`design-studio/dollhouse-refresh/design_prompts_forcoders.md`
 *   方向 A「安静居所」：暖纸底、单一强调色、以留白与发丝分隔线承载层级、卡片弱化、
 *   阴影只留给真正浮起的层。B 方向的两点被吸收：人偶列表/详情用抬升卡片、顶栏主操作用实心紫。
 */
object DhDesign {

    /* ================================================================
     * 1. 字体阶梯
     * ================================================================ */

    /**
     * 字号 / 字重 / 行高阶梯。
     *
     * 【行高为什么必须显式写】Compose 的 Text 不给行高时会用字体自带的行高；
     *   本工程用霞鹜文楷派生的粗体，中文多行文案行距偏紧，用户反馈过「文字贴在一起」。
     *   改造前 36 处内联 `fontSize = X.sp` 没有一处带 lineHeight —— 这是「阅读性低」的结构性原因。
     *
     * 【取值来源】设计稿 §2.1：brand 22/600/30、display 28/600/36、title 20/600/28、
     *   section 16/600/22、body 15/400/24、sub 13/400/20、caption 12/400/17、label 11/600/16。
     *   改造前的 12sp 底部小字提升到 13sp（sub），12sp 只留给真正的 caption。
     */
    @Immutable
    data class Type(
        /** 品牌字：App 名 / 人偶名 / 头像首字。唯一使用霞鹜文楷的地方。 */
        val brand: TextStyle,
        /** 大号标题：导入向导标题、空状态主文案。 */
        val display: TextStyle,
        /** 页面标题（每个屏的顶栏标题都走这一档）。 */
        val title: TextStyle,
        /** 模块 / 卡片标题。 */
        val section: TextStyle,
        /** 正文：消息、段落、列表主行。 */
        val body: TextStyle,
        /** 次级文字：列表第二行、时间戳、说明。 */
        val sub: TextStyle,
        /** 注脚：提示、标签、日期分隔。 */
        val caption: TextStyle,
        /** 极小标签：底栏文字、徽标、分组名。 */
        val label: TextStyle
    ) {
        /**
         * 列表行主文案：body 字号 + Medium 字重。
         *
         * 【为什么不单开一个 token】设计稿明确「行标题 = body@500」；
         *   单开一档会让「同一段文字放在行里和放在段落里」变成两个尺寸。
         */
        val rowTitle: TextStyle
            get() = body.copy(fontWeight = FontWeight.Medium)

        /**
         * 数字 / 时间：等宽数字，避免计时与统计跳动。
         *
         * 【适用范围·仅在正文档 15sp 时用它】本令牌固定为 [body] 的形状，
         *   所以它只在「数字就是正文」的地方（如统计行）能直接用。
         *   大号数字（30sp 总量、18sp 分项、帧率、计数）**不能**用它 ——
         *   套上去会把字号压回 15sp。那些地方用 `style = TextStyle(fontFeatureSettings = "tnum")`
         *   叠加在原有 fontSize 上（工程既有写法见 ChatListScreen 未读徽标、BottomNav Badge）。
         */
        val numeric: TextStyle
            get() = body.copy(fontFeatureSettings = "tnum")

        companion object {
            /**
             * 用 [FontFamily] 构造阶梯。
             *
             * 【为什么字族是参数而不是常量】品牌档要用霞鹜文楷，正文档要回落到干净的无衬线；
             *   两者都依赖运行时加载（[com.dollhouse.app.ui.theme.Fonts]），不能在 object 里冻死。
             */
            fun of(brandFamily: FontFamily, bodyFamily: FontFamily): Type = Type(
                brand = TextStyle(
                    fontFamily = brandFamily,
                    fontSize = 22.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.22.sp
                ),
                display = TextStyle(
                    fontFamily = bodyFamily,
                    fontSize = 28.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.28).sp
                ),
                title = TextStyle(
                    fontFamily = bodyFamily,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                section = TextStyle(
                    fontFamily = bodyFamily,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                body = TextStyle(
                    fontFamily = bodyFamily,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Normal
                ),
                sub = TextStyle(
                    fontFamily = bodyFamily,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal
                ),
                caption = TextStyle(
                    fontFamily = bodyFamily,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.12.sp
                ),
                label = TextStyle(
                    fontFamily = bodyFamily,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.44.sp
                )
            )

            /** 兜底：字体未就绪时的系统字族版本（首帧可用，缓存就绪后换回）。 */
            fun fallback(): Type = of(FontFamily.Default, FontFamily.Default)
        }
    }

    /* ================================================================
     * 2. 间距阶梯
     * ================================================================ */

    /**
     * 间距刻度：4 / 8 / 12 / 16 / 24 / 32。
     *
     * 【为什么废弃 10dp】改造前卡片间距有 12（Design.GAP_CARD）与 10（DhKit.GAP_CARD）两个值，
     *   同一列表里相邻两张卡片间距不一样，看着像没对齐。统一取 12（两者之间，也是 4 的倍数）。
     * 【为什么废弃 8/10 两档图标间距】统一 8（Design.GAP_ICON），图标与文字的关系全 App 一致。
     */
    @Immutable
    object Space {
        /** 4dp：紧邻元素（图标与徽标、行内小间隙）。 */
        const val xs = 4
        /** 8dp：图标与文字、行内元素之间。 */
        const val sm = 8
        /** 12dp：列表行之间、卡片内段落之间。 */
        const val md = 12
        /** 16dp：**页面左右边距**，所有屏统一。 */
        const val lg = 16
        /** 24dp：区块之间（分组间距）。 */
        const val xl = 24
        /** 32dp：页面顶部 / 底部大留白。 */
        const val xxl = 32

        /** 页面左右内边距（全 App 一口径）。 */
        const val pageX = lg

        /** 页面底部安全留白（列表滚到底时不贴住底栏）。 */
        const val pageBottom = xxl

        /** 卡片内边距。 */
        const val cardPad = lg

        /** 卡片之间 / 分组之间的间距。 */
        const val cardGap = md

        /** 区块标题与其内容的间距。 */
        const val sectionGap = sm

        /** 图标与紧邻文字的间距。 */
        const val iconGap = sm
    }

    /* ================================================================
     * 3. 尺寸刻度
     * ================================================================ */

    /**
     * 固定尺寸。
     *
     * 【图标只允许这几档】改造前页面里到处 13/14/17/18/19 随手值，同一层级的图标视觉重量不一致。
     *   收口成 5 档后「谁在什么位置用哪一档」是明确的。
     */
    @Immutable
    object Size {
        /** 行内极小图标（备注、次级标记）。 */
        val iconXs = 14.dp
        /** 行内小图标（列表行主图标、状态徽标）。 */
        val iconSm = 16.dp
        /** 操作项图标（动作行左侧、经手统一后的行图标）。 */
        val iconMd = 20.dp
        /** 主要状态图标 / 顶栏图标。 */
        val iconLg = 24.dp
        /** 强调图标（空状态、大状态块）。 */
        val iconXl = 28.dp

        /** 列表行左侧图标（设计稿定为 20dp，与 15sp 正文、24dp 图标三者比例协调）。 */
        val rowIcon = iconMd

        /** 最小可点击边长（dp）：Android 无障碍基线，与视觉尺寸分离。 */
        val minTouch = 48.dp

        /** 顶栏高度。 */
        val topBarH = 56.dp

        /** 底栏（悬浮胶囊）高度。 */
        val navBarH = 64.dp

        /** 两行列表行高度。 */
        val rowTwoLine = 64.dp

        /** 单行列表行高度。 */
        val rowOneLine = 56.dp

        /** 列表行最小高度（无可点内容时也保证可点）。 */
        val rowMin = 48.dp

        /**
         * 头像尺寸阶梯（dp）。
         *
         * 【为什么不止设计稿的 40/56/72 三档】
         *   设计稿给的是**详情页/主视觉**的规格；本 App 在聊天场景里还有两处更小的头像，
         *   它们此前都是各页自己写的字面量（ChatListScreen 的 52 与 36、ChatUi 的 34），
         *   与 token 脱节。这里把它们收进同一阶梯，并保留原有的**视觉层级**
         *   （列表 52 > 列表顶栏 36 ≈ 气泡 34），而不是硬压成统一 40 ——
         *   层级本身是有意义的：列表头像是「身份标识」，顶栏与气泡旁的头像是「辅助信息」。
         *
         * 【为什么列表用 52 而不是设计稿的 40】会话列表的行高是 64dp，52 的头像
         *   让每一行有明确的主体感；40 在这个行高里显得偏小、留白过多。
         *   这属于「实际使用场景优先于设计稿默认值」的取舍，已在此显式记录。
         */
        val avatar = 40.dp
        /** 头像：会话列表（比详情页略小，但仍是行内主体）。 */
        val avatarRow = 52.dp
        /** 头像：列表顶栏「我」的入口（不抢标题视觉重心）。 */
        val avatarTopBar = 36.dp
        /** 头像：聊天气泡旁（最小一档，纯辅助）。 */
        val avatarBubble = 34.dp
        /** 头像：详情页。 */
        val avatarLg = 56.dp
        /** 头像：人偶主视觉。 */
        val avatarXl = 72.dp

        /** 输入区高度。 */
        val composerH = 64.dp

        /**
         * 单行输入框高度（dp）。
         *
         * 【为什么单开一槽】规范里「输入框 40dp」被两处各写一遍：
         *   `ui/kit/Components.kt` 的私有 `FieldHeight`、`ui/compose/DhForm.kt` 的 `FIELD_MIN_DP`。
         *   两处都写 40 时看不出问题，一旦有人只改一处，「同一个输入框在两个页面高度不同」
         *   就会复发 —— 这正是本轮要根治的「同一语义两个值」。
         *
         * 【注意它与 48dp 命中区的关系】设计稿的口径是「视觉 40 / 命中 48」。
         *   带尾部图标（如清除叉、密码显示）的输入框若强行定高 40，
         *   会把 48dp 的可点区域裁掉，等于用可见性换无障碍。所以调用方允许
         *   只把它当**下限**用（defaultMinSize），由内容自己决定实际高度。
         */
        val fieldH = 40.dp

        /** 分隔线粗。 */
        val hairline = 1.dp
    }

    /* ================================================================
     * 4. 圆角阶梯
     * ================================================================ */

    /**
     * 圆角：8 / 12 / 16 / pill 四档。
     *
     * 【为什么废弃 10dp】改造前 R_ROW=10 与 R_CARD=16 并存，10 既不是 4 的倍数、
     *   也不是设计稿里的任何一档，行与按钮之间「该圆到什么程度」没有依据。收敛到 12。
     */
    @Immutable
    object Radius {
        /*
         * 【v3·清透轻拟物】整体上抬一档。
         *
         * 旧档位（8/12/16）是「安静居所」方向：靠发丝线和克制圆角做层级。
         * 新方向要「大圆角分组卡片 + 柔和明暗层次」，圆角必须更大才撑得住那种体积感 ——
         * 12dp 的卡片在柔和阴影下会显得"没圆到位"，像被削了角。
         *
         * 各档 +4~6，并新增 xl 给大面板（底部面板、抽屉、弹窗）。
         */
        /** 10dp：缩略图、标签、小字段。 */
        const val sm = 10
        /**
         * 12dp：列表行、小方块、图标底、提示条。
         *
         * 【v3.1·回退】上一版把它提到 16，本意是让按钮/输入框更圆润，
         *   但**很多小元素也用这一档**（30dp 提示条、26dp 色块、20dp 图标）——
         *   圆角 16 已经大于它们半高，直接变成药丸/变形，视觉上就是"很丑"。
         *   档位的语义是"用在哪"，不是"多大"；把按钮的需求塞进通用档会连累所有小元素。
         */
        const val md = 12
        /** 16dp：按钮、输入框专用（比通用档更圆润，但只给尺寸够大的控件）。 */
        const val btn = 16
        /** 22dp：卡片、人偶磁贴、气泡。 */
        const val lg = 22
        /** 28dp：底部面板顶角、抽屉、弹窗纸张。 */
        const val xl = 28
        /** 全圆（药丸）。 */
        const val pill = 999

        val smShape = RoundedCornerShape(sm.dp)
        val mdShape = RoundedCornerShape(md.dp)
        val lgShape = RoundedCornerShape(lg.dp)
        val pillShape = RoundedCornerShape(pill.dp)
    }

    /* ================================================================
     * 5. 动效
     * ================================================================ */

    /**
     * 动效时长与曲线。
     *
     * 【为什么收口】改造前时长散在 UiKit.D_PRESS/D_MICRO/D_LAYER/D_PAGE/D_STAGGER、
     *   DhAnim 的 spring/tween、以及各页面 setTimeout 式的 postDelayed。
     *   「同一种点击反馈在 A 页 90ms、B 页 180ms」是最容易被感知的不一致。
     *
     * 【为什么否定弹跳过强】设计稿要求「克制」：弹簧阻尼比不低于 0.72，
     *   只在底栏指示器、按压回弹这类「有物理直觉」的地方用弹簧，转场一律用 tween。
     */
    @Immutable
    object Motion {
        /** 按压反馈：90ms。 */
        const val press = 90
        /** 微交互（开关、芯片切换、颜色过渡）：160ms。 */
        const val micro = 160
        /** 进入（页面压栈、面板弹出）：220ms。 */
        const val enter = 220
        /** 退出（返回、面板收起）：180ms。 */
        const val exit = 180

        /** 列表项入场错峰间隔。 */
        const val stagger = 26

        /* ---------------------------------------------------------------
         * 缓动曲线（4 条，与设计稿 token.css 的 --ease-* 一一对应）
         *
         * 【为什么必须显式给贝塞尔而不是用 Compose 内置名】
         *   设计稿给的是 CSS 的 cubic-bezier 四元组；Compose 内置的
         *   FastOutSlowInEasing 只是「近似」标准曲线，数值上并不相等
         *   （内置是 (0.4, 0.0, 0.2, 1.0) 的 Material 版本）。
         *   要让 Compose 页与 View 页（走 UiKit.EASE_* 的 PathInterpolator）
         *   在同样的时长下走出来**同一条曲线**，必须用同一个四元组。
         *
         * 【命名】与设计稿 token 名严格同名，避免出现「中文名 / 英文名 / 缩写」
         *   三套叫法（历史上 DhAnim 与 UiKit 各叫过一遍，就是混乱的起点）。
         * --------------------------------------------------------------- */

        /** 减速（标准）：cubic-bezier(0.2, 0, 0, 1)。绝大多数进入 / 位移 / 颜色过渡。 */
        val easeStd: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** 标准进出：cubic-bezier(0.4, 0, 0.2, 1)。双向对称的动作（展开后又收起这类）。 */
        val easeIo: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

        /** 加速退出：cubic-bezier(0.4, 0, 1, 1)。**离开永远比进入快**，用加速曲线。 */
        val easeExit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

        /** 弹簧阻尼比（仅用于必须带物理直觉的位移：底栏指示器、按压回弹）。 */
        const val springDamping = 0.82f
        const val springStiffness = 380f
    }

    /* ================================================================
     * 5b. 高度层级（Elevation）
     * ================================================================ */

    /**
     * 阴影三档：e0 / e1 / e2。
     *
     * 【设计稿原文】「elevation（0/1/2，outline-first）」——
     *   也就是说**默认不投影，靠 1dp 发丝描边承载层级**；
     *   只有真正浮起于页面之上的层才用 e2。
     *
     * 【为什么会缺这一组】改造前全工程 `.shadow(` 调用数为 0：
     *   弹窗 / 底部面板 / 菜单纸张全靠描边区分，与本工程「卡片只微微浮起」的取向一致，
     *   但**浮层与页面之间的纵深完全消失了** —— 用户看不出「这个框是压在上面的」。
     *
     * 【数值怎么来的】设计稿给的是 CSS 的 box-shadow：
     *   e1 = `0 1px 2px rgba(...,0.06)`   → Compose shadowElevation 取 **2dp**
     *   e2 = `0 12px 32px rgba(...,0.16)` → Compose shadowElevation 取 **12dp**
     *   Compose 的 shadowElevation 只接受一个模糊半径近似值，
     *   无法表达 CSS 的「纵向偏移 + 扩散」，因此这里只存 dp 近似值；
     *   CSS 的原始四元组保留在设计稿 token.css，供墨卡 / 网页端对齐。
     *
     * 【硬规则】**列表行 / 芯片 / 顶栏永不加阴影**（见设计稿「不要做」清单）。
     *   e1 只用于「被按下的卡片」这类瞬时反馈，e2 只用于 sheet / dialog / menu / snackbar。
     */
    @Immutable
    object Elevation {
        /** e0：默认。不投影，层级由 1dp 描边承担。 */
        val e0 = 0.dp

        /*
         * 【v3·清透轻拟物】阴影整体上调。
         * 旧值（2/12）是「安静居所」方向：层级靠发丝线，阴影只做几乎看不见的暗示。
         * 新方向要「柔和的明暗层次体现空间感」—— 2dp 完全不产生体积感，
         * 卡片看起来仍是贴在页面上的色块。
         * 同时**染色**（蓝灰而非纯黑）是轻拟物的关键：纯黑阴影在浅蓝底上会显脏。
         */
        /** e1：卡片轻浮起（大圆角分组卡片）。 */
        val e1 = 6.dp

        /** e2：真正的浮层（底部面板、对话框、抽屉、Snackbar）。 */
        val e2 = 16.dp
    }

    /* ================================================================
     * 6. 颜色语义
     * ================================================================ */

    /**
     * 把 [UiKit] 的 29 个可变字段映射成 Compose 语义槽。
     *
     * 【调用时机】只能在已经执行过 `ThemeManager.apply()` 之后调用（`DollhouseTheme` 内），
     *   否则读到的是上一次的旧色或未初始化的默认值。
     *
     * 【派生规则】设计稿 §5 明确 9 个槽位「计算而非存储」：
     *   switchOn = accent、stroke = line@78%、scrim = title@42%、skeleton = soft+line。
     *   这样既不加调色板槽位，又保证切主题时它们跟着源色一起变。
     */
    @Immutable
    data class Palette(
        val accent: Color,
        val accent2: Color,
        val card: Color,
        val cardUp: Color,
        val sheet: Color,
        val sheetUp: Color,
        val capsule: Color,
        val capsuleSel: Color,
        val bg: Color,
        val title: Color,
        val sub: Color,
        val line: Color,
        val option: Color,
        val soft: Color,
        val field: Color,
        val ok: Color,
        val err: Color,
        val onAcc: Color,
        val chatBubbleUser: Color,
        val chatBorder: Color,
        val chatChipBg: Color,
        val chatChipFg: Color,
        val chatChipOn: Color,
        val chatChipOff: Color,
        val chatChipMute: Color,
        val chatActionBg: Color,
        val hintFg: Color,
        val hintBg: Color,
        val switchOff: Color,
        val switchOn: Color,
        val emote1: Color,
        val emote2: Color,
        val emote3: Color,
        val stroke: Color,
        val scrim: Color
    ) {
        /** 成功淡底（徽标背景）：OK 压到 12% 透明。 */
        val okBg: Color get() = ok.copy(alpha = 0.12f)

        /** 失败淡底。 */
        val errBg: Color get() = err.copy(alpha = 0.12f)

        /**
         * 「错误色底上的文字」—— 徽标（未读数、失败计数）用。
         *
         * 【为什么必须单独一档，不能直接用 [onAcc]】
         *   徽标底色是 `err`，而 `err` 在暗色/纯黑档是**浅红**（#E58E77 / #E0866F）——
         *   白字压上去实测只有 **2.48 / 2.69:1**，远低于 4.5（浅色档 #B0563F 上白字 4.94 是达标的）。
         *   改造前徽标硬编码 `Color.White`，于是暗色下小红点里的数字几乎看不清。
         *   这里按「底色明暗」自动选黑或白：暗底配白字、亮底配深字，
         *   不新增调色板槽位（仍是 29 格），切主题时跟着 [err] 自动变。
         */
        val onErr: Color
            get() {
                // 【为什么按"取对比度更高者"而不是固定阈值】
                //   第一版用「亮度 > 0.5 就用深色字」，实测漏判：暗色档 err=#E58E77 的
                //   相对亮度约 0.37（<0.5），于是仍选白字 → 对比度只有 2.48:1（要求 4.5）。
                //   正确做法是分别算出「压白字」与「压深字」的 WCAG 对比度，取大的那个。
                //   分界点其实是 L≈0.18，但直接比较对比度不依赖任何经验常数。
                val l = relativeLuminance(err)
                // 【v3】原来用旧暖纸 title(0xFF2A2620)。新调色板下 title 变成偏亮的深蓝灰(#2B3A4A)，
                //   它压浅红 err 只有 3.41:1（不足 4.5）—— 徽标字会发糊。
                //   徽标是 11sp 小字，必须按最严的标准来，所以这里用**近黑**而不是 title 色。
                val onDark = Color(0xFF0F141A)
                val onLight = Color(0xFFFFFFFF)
                val cDark = (l + 0.05f) / (relativeLuminance(onDark) + 0.05f)
                val cLight = (1.05f) / (l + 0.05f)
                return if (cDark >= cLight) onDark else onLight
            }

        /** 禁用前景：靠 alpha 表达「不可操作」，不另设灰色。 */
        val disabledFg: Color get() = title.copy(alpha = 0.38f)

        /** 禁用底。 */
        val disabledBg: Color get() = soft.copy(alpha = 0.60f)

        /** 骨架屏底：用 soft 与 line 之间的一档，不做扫光渐变。 */
        /**
         * 【v3·清透轻拟物】阴影的染色。
         *
         * 【为什么不能固定一个色】浅色底上，纯黑阴影会显脏显硬 —— 染成蓝灰才"柔"；
         *   但深色底上同一个浅蓝会**发亮**，像一圈光晕，反而更脏。
         *   所以按底色明暗二选一：浅色用蓝灰、深色用近黑。
         *
         * 【为什么用相对亮度判】比"按主题枚举"稳：用户调主题色 / 莫奈取色后，
         *   底色明暗可能变，固定枚举会失配。
         */
        val shadowTint: Color
            get() = if (relativeLuminance(bg) > 0.5) Color(0xFF8FA8C4) else Color(0xFF05080C)

        val skeleton: Color get() = soft

        /** 聚焦环：accent2 的 40%。 */
        val focusRing: Color get() = accent2.copy(alpha = 0.40f)

        /** 浮起层的描边（比 hairline 实一档）。 */
        val outlineStrong: Color get() = stroke

        companion object {
            /** 从 [UiKit] 现读一份快照。字段顺序与 `UiKit.snapshotPalette()` 语义分组一致。 */
            fun fromUiKit(): Palette = Palette(
                accent = Color(UiKit.ACC),
                accent2 = Color(UiKit.ACC2),
                card = Color(UiKit.card()),
                cardUp = Color(UiKit.cardUp()),
                sheet = Color(UiKit.sheet()),
                sheetUp = Color(UiKit.sheetUp()),
                capsule = Color(UiKit.capsuleFill()),
                capsuleSel = Color(UiKit.capsuleSel()),
                bg = Color(UiKit.BG),
                title = Color(UiKit.TITLE),
                sub = Color(UiKit.SUB),
                line = Color(UiKit.LINE),
                option = Color(UiKit.option()),
                soft = Color(UiKit.soft()),
                field = Color(UiKit.field()),
                ok = Color(UiKit.OK),
                err = Color(UiKit.ERR),
                onAcc = Color(UiKit.ON_ACC),
                chatBubbleUser = Color(UiKit.CHAT_BUBBLE_USER),
                chatBorder = Color(UiKit.CHAT_BORDER),
                chatChipBg = Color(UiKit.CHAT_CHIP_BG),
                chatChipFg = Color(UiKit.CHAT_CHIP_FG),
                chatChipOn = Color(UiKit.CHAT_CHIP_ON),
                chatChipOff = Color(UiKit.CHAT_CHIP_OFF),
                chatChipMute = Color(UiKit.CHAT_CHIP_MUTE),
                chatActionBg = Color(UiKit.CHAT_ACTION_BG),
                hintFg = Color(UiKit.HINT_FG),
                hintBg = Color(UiKit.HINT_BG),
                switchOff = Color(UiKit.SWITCH_OFF),
                switchOn = Color(UiKit.SWITCH_ON),
                emote1 = Color(UiKit.EMOTE_1),
                emote2 = Color(UiKit.EMOTE_2),
                emote3 = Color(UiKit.EMOTE_3),
                stroke = Color(UiKit.STROKE),
                scrim = Color(UiKit.SCRIM)
            )
        }
    }

    /**
     * WCAG 2.1 相对亮度（0=纯黑，1=纯白）。
     *
     * 【用在哪】[Palette.onErr] 需要按底色亮度决定配黑字还是白字；
     *   `tools/palette_contrast.py` 用同一公式做门禁校验，两边口径必须一致。
     */
    fun relativeLuminance(c: Color): Float {
        fun ch(v: Float): Float =
            if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    /* ================================================================
     * 7. Material 3 投影
     * ================================================================ */

    /**
     * 把 [Palette] 投影成 M3 的 [ColorScheme]。
     *
     * 【为什么需要】改造前工程**刻意不建 MaterialTheme**，于是 material3 的 `Text`（39 处）、
     *   `SnackbarHost`、`TooltipBox`、`rememberModalBottomSheetState` 全部落到 M3 默认值 ——
     *   默认主色是紫 6200EE，与本工程的主题色毫无关系；输入框的光标把手也因此是那个蓝
     *   （虽然 DhTheme 曾用 LocalTextSelectionColors 单独打补丁）。
     *   现在建立**投影**而不是**第二真源**：所有槽位都由 [Palette] 派生，
     *   Palette 又只来自 UiKit，所以主题仍然只有一个真源。
     *
     * 【为什么不是「新建一套 M3 配色」】那会让「Compose 页一个色、桌宠另一个色」重现。
     *   这里 M3 只是**读者**，不是作者。
     */
    fun colorScheme(p: Palette): ColorScheme = ColorScheme(
        primary = p.accent,
        onPrimary = p.onAcc,
        primaryContainer = p.capsuleSel,
        onPrimaryContainer = p.accent,
        inversePrimary = p.accent2,
        secondary = p.accent2,
        onSecondary = p.onAcc,
        secondaryContainer = p.option,
        onSecondaryContainer = p.title,
        tertiary = p.ok,
        onTertiary = p.onAcc,
        tertiaryContainer = p.hintBg,
        onTertiaryContainer = p.hintFg,
        background = p.bg,
        onBackground = p.title,
        surface = p.card,
        onSurface = p.title,
        surfaceVariant = p.soft,
        onSurfaceVariant = p.sub,
        surfaceTint = p.accent,
        inverseSurface = p.title,
        inverseOnSurface = p.card,
        error = p.err,
        onError = p.onAcc,
        errorContainer = p.errBg,
        onErrorContainer = p.err,
        outline = p.line,
        outlineVariant = p.line.copy(alpha = 0.6f),
        scrim = p.scrim,
        surfaceBright = p.cardUp,
        surfaceDim = p.bg,
        surfaceContainer = p.card,
        surfaceContainerHigh = p.cardUp,
        surfaceContainerHighest = p.cardUp,
        surfaceContainerLow = p.soft,
        surfaceContainerLowest = p.bg
    )

    /** 把 [Type] 投影成 M3 的 [Typography]。 */
    fun typography(t: Type): Typography = Typography(
        displayLarge = t.display.copy(fontSize = 44.sp, lineHeight = 52.sp),
        displayMedium = t.display,
        displaySmall = t.title.copy(fontSize = 24.sp, lineHeight = 32.sp),
        headlineLarge = t.title.copy(fontSize = 24.sp, lineHeight = 32.sp),
        headlineMedium = t.display,
        headlineSmall = t.title,
        titleLarge = t.title,
        titleMedium = t.section,
        titleSmall = t.section.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = t.body,
        bodyMedium = t.sub,
        bodySmall = t.caption,
        labelLarge = t.body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.caption,
        labelSmall = t.label
    )

    /** 把圆角阶梯投影成 M3 的 [Shapes]。 */
    fun shapes(): Shapes = Shapes(
        extraSmall = RoundedCornerShape(Radius.sm.dp),
        small = RoundedCornerShape(Radius.sm.dp),
        medium = RoundedCornerShape(Radius.md.dp),
        large = RoundedCornerShape(Radius.lg.dp),
        extraLarge = RoundedCornerShape(Radius.lg.dp)
    )
}