package com.dollhouse.app.ui.qzx

import androidx.compose.ui.unit.dp

/**
 * 【职责】新 UI 系统（QZX）的**唯一令牌源**。
 *
 * ## 为什么是一个 object 而不是多套
 * 旧系统里有 `DhDesign`（分 Type/Space/Size/Radius/Motion/Palette）+ `UiKit`（View 侧）
 * + `DhTokens`（Compose 侧），三套同源却分散。新系统只有这一处：
 * **改一个数，全系统跟着变**；找不到"另一处 48"这种事。
 *
 * ## 值的来源
 * 一律是 QZX 文档的实测默认值（见 `docs/QZX_SPEC_BUTTON_GLASS.md`）。凡是**刻意不照抄**的，
 * 都在该字段上写明偏离与理由 —— 这些偏离比数值本身更重要，改之前先读那一行。
 *
 * ## 【预留·零引用】本批（令牌/列表/页面/卡片/按钮）用不到，但**下一批就要用**的令牌
 * 旧系统的教训是"零引用令牌攒了 17 个没人知道该不该删"，所以这里一次列清：
 * | 令牌 | 谁来消费 |
 * |---|---|
 * | `Space.xxs` / `Space.lg` | 批 3 的 chip / 表单行（本批的行内边距走语义化的 `listH`） |
 * | `Space.sectionGap` / `Radius.pill` / `Size.switchW` | 批 3 的筛选胶囊与分区折叠 |
 * | `Motion.ENTER` / `EXIT` / `PAGE_BACK_SCALE` | 批 2 的分层转场（当前转场还在旧 NavHost 里） |
 * | `Motion.SWIPE_SETTLE` / `SECTION_TOGGLE` | 批 2 的左滑与分区展开 |
 * **删之前先看这张表** —— 它们不是历史遗留，是已经定好用途的下一批接口。
 */
object QzxTokens {

    /** 间距。全部是 4 的倍数（QZX 的栅格是 4dp）。 */
    object Space {
        val xxs = 2.dp
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 20.dp
        val xxl = 24.dp

        /**
         * 卡片之间的组间距。QZX 的**特征值 17dp**（不是 16）。
         *
         * 【为什么保留这个奇怪的值】它比 16 多 1dp，正好让两张卡片的圆角之间
         * 有一条"看得见但说不出为什么"的缝 —— 这是 QZX 卡片显得"排得松"的细节之一。
         * 跟 16 统一会丢掉这点，而这个值的代价为零（只影响纵向节奏）。
         */
        val cardGap = 17.dp

        /** 列表的水平内边距（行内容与卡片边缘的距离，也是分隔线的**缩进起点**）。 */
        val listH = 16.dp

        /** 行标题与右侧值的间距。 */
        val rowTitleGap = 6.dp

        /** 分区标题与上方分区的间距。 */
        val sectionGap = 20.dp
    }

    /** 圆角。 */
    object Radius {
        /** 卡片圆角。 */
        val card = 16.dp

        /**
         * 按钮圆角。
         *
         * 【⚠️ 偏离 QZX】QZX 的按钮是 **12dp**，这里取 16 —— 与卡片统一。
         * 理由：上一轮刚把全 App 的圆角收敛成一套（卡片/弹窗/药丸三档），
         * 为一个外部库的值再拆出第四档不划算，而 12 与 16 在 44dp 高的按钮上肉眼几乎无差。
         * 若将来要严格复刻，把这里改回 `12.dp` 即可 —— **它是唯一的一处**。
         */
        val button = 16.dp

        /** 药丸 / 胶囊（筛选 chip、标签）。 */
        val pill = 20.dp

        /** 卡片**内部**的次级圆角（输入框、内嵌小块）。 */
        val inner = 8.dp
    }

    /** 尺寸。 */
    object Size {
        /** 发丝线（浅色主题下卡片的描边宽度；也是边缘光的带宽）。 */
        val hairline = 1.dp

        /** 最小触摸目标（dp）。低于这个数的可点区域一律不可接受。 */
        const val minTouch = 48

        /**
         * 单行行高（dp）。
         *
         * 【⚠️ 用法】这是**下限**（`heightIn(min = …)`），不是固定高度。
         * 旧系统里有一处写死 `height(56.dp)`，用户把系统字号调到 200% 时文字被裁掉。
         */
        const val rowOneLine = 56

        /** 双行行高下限（标题 + 副标题，或标题 + 输入框）。 */
        const val rowTwoLine = 64

        /** 输入行里输入框的高度。 */
        val field = 36.dp

        /** 开关行右侧开关的占位宽（保证多行开关对齐）。 */
        val switchW = 52.dp
    }

    /** 字体与标题栏规格（QZX `QZXAdaptiveTitleBar` 的 IOS_STYLE）。 */
    object Type {
        // ── 自适应大标题 ────────────────────────────────────────────────
        /** 收起态标题字号（sp）。 */
        const val MIN_TITLE_SP = 19f

        /** 展开态标题字号（sp）。 */
        const val MAX_TITLE_SP = 32f

        /** 收起态栏高（dp，不含状态栏让位）。 */
        const val MIN_BAR_DP = 46

        /** 展开态栏高（dp，不含状态栏让位）。 */
        const val MAX_BAR_DP = 120

        /** 收起完成所需的滚动距离（px）。 */
        const val MAX_SCROLL_RANGE = 370f

        // ── 列表文字 ───────────────────────────────────────────────────
        /** 行标题字号（sp）。 */
        const val ROW_TITLE_SP = 15f

        /** 行副文本 / 值字号（sp）。 */
        const val ROW_VALUE_SP = 14f

        /** 分区标题（卡片外）字号。 */
        const val SECTION_SP = 13f

        /** 分区脚注字号。 */
        const val FOOTER_SP = 12f

        /** 行高与字号的差（行高 = 字号 + 这个值 × 行数）。集中在这里，别在调用处散写。 */
        const val LINE_GAP_SP = 6f
    }

    /** 动效时长（毫秒）。 */
    object Motion {
        /** 页面进入。 */
        const val ENTER = 220

        /** 页面退出。**比进场短** —— 两层都以半透明共存的时间越短，重影越少。 */
        const val EXIT = 180

        /** 按压反馈（QZX `qzxButtonTouchFeedbackColor` 的插值时长）。 */
        const val PRESS = 90

        /** 按压**回弹**（松手）。比 [PRESS] 长，弹回来要慢一点才"有余韵"。 */
        const val PRESS_RELEASE = 220

        /** 松手吸附（左滑等）。 */
        const val SWIPE_SETTLE = 180

        /** 释放扩散（QZX 默认 300ms）。 */
        const val RELEASE_DIFFUSION = 300

        /** 分区展开/收起。 */
        const val SECTION_TOGGLE = 180

        /** 卡片按压缩放的值（与旧系统 `PRESS_SCALE` 同值，视觉上必须是同一个手感）。 */
        const val PRESS_SCALE = 0.96f

        /** 退出 / 进场时旧页缩到的比例（分层转场）。 */
        const val PAGE_BACK_SCALE = 0.92f
    }

    /** 边缘光（QZX `qzxCardRimLight`）。 */
    object RimLight {
        /** 带宽（dp）。 */
        const val WIDTH_DP = 1

        /** 顶端（主光源）亮度峰值。 */
        const val TOP_ALPHA = 0.09f

        /** 底部（次光源）亮度峰值，约顶端的 0.4。 */
        const val BOTTOM_ALPHA = 0.036f

        /** 内晕深度（dp）：光往卡片内部扩散的范围。 */
        const val GLOW_DP = 2

        /** 内晕的亮度系数（相对硬线）。 */
        const val GLOW_ALPHA_SCALE = 0.35f

        /**
         * 加法混合在浅色卡上是否**还会保留描边**。
         *
         * 【为什么需要这一条】`BlendMode.Plus` 在 `#FAFCFE` 上会全部钳到纯白（实测对比度
         * 只有 1.028，且上下同值 —— 方向感也丢了）。所以浅色主题下**必须**保留 1dp 描边，
         * 否则卡片会彻底"没有边"。深色主题则相反：描边 + 光会让边缘比卡面亮一档。
         */
        const val KEEP_STROKE_ON_LIGHT = true
    }

    /** 按钮（QZX `QZXGeoButton`）。 */
    object Button {
        /** 外发光半径（dp）。 */
        const val GLOW_RADIUS_DP = 80

        /** 外发光强度（clamp 0.05~0.3，默认 0.1）。 */
        const val GLOW_INTENSITY = 0.1f

        /** 释放扩散半径（dp）。 */
        const val DIFFUSION_RADIUS_DP = 100

        /** 释放扩散强度（clamp 0.1~1.0，默认 0.8）。 */
        const val DIFFUSION_INTENSITY = 0.8f

        /**
         * 背景按 HSV 压暗到的 V 通道比例（QZX `qzxButtonTouchFeedbackColor` 默认）。
         *
         * 【值取自 `docs/QZX_SPEC_BUTTON_GLASS.md` 的 0.92，不是 0.85】0.85 会让深色按钮
         * 出现明显"变黑一块"的死板观感；0.92 只压一档亮度，是"被按住"而不是"变暗"。
         */
        const val PRESS_VALUE = 0.92f

        /** 水平内边距（dp）。 */
        const val PAD_H_DP = 20

        /** Loading 转圈半径（dp）。 */
        const val LOADING_RADIUS_DP = 10

        /** Loading 转圈粗细（dp）。 */
        const val LOADING_STROKE_DP = 4

        /** 禁用态整体不透明度（QZX：叠白层降到 0.45）。 */
        const val DISABLED_ALPHA = 0.45f
    }
}
