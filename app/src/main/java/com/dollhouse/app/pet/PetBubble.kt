package com.dollhouse.app.pet

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.util.ArrayList

/**
 * 【职责】桌宠头顶气泡：文本排版、空间测量、圆角加尖角的绘制。
 *
 * 【入口】只由 PetView 调用（showBubble / clearBubble / neededBubbleSpace / setBubbleHeight / setScale / draw）。
 *
 * 【交互】对外只回答「要多少空间」和「怎么画」；不碰 PetView 的动画与手势状态。
 *
 * 【扩展】改气泡样式（圆角 / 内边距 / 尾巴）只改本类，不动 PetView。
 *
 * 【缩放】字号由 PetView 的 TextPaint 负责，圆角 / 内边距 / 尾巴 / 行距 / 排版可用宽度
 *         全部乘 scale，保证「气泡 : 人偶」比例恒定；scale 变化必须重排，否则排版结果还是旧字号。
 *
 * 【贴边】人偶贴边偷看时窗口有一截在屏幕外。排版用的可用宽度与绘制时的左右边界
 *         都按「窗口 ∩ 屏幕」的可见区来算，否则气泡会有一半画到屏幕外
 *         （用户报的「趴着时对话框一半在外面」）。
 *
 * 【截断】AI 回答可能很长，最多 MAX_LINES 行 + 末尾省略号，避免气泡撑满整屏。
 *
 * 【坑】TextPaint 与 Paint 由 PetView 注入并共享，本类不改它们的配色（字号只由 PetView 改）；
 *        layout 可用宽度依赖 PetView 换来的宽度，精灵图为空时会是 0，必须先 setSprite。
 */
class PetBubble(private val fill: Paint, private val edge: Paint, private val textPaint: TextPaint) {
    companion object {
        /** 气泡圆角半径。 */
        private const val RADIUS_DP = 14.0f

        /** 尾巴半宽。 */
        private const val TAIL_DP = 9.0f

        /**
         * 尾巴高度（= 气泡圆角矩形底边到头顶的空隙）。
         * 【调优】旧值 9dp 让圆角底边比头顶高 9dp，只有中间那条尾巴搭住头，
         *         两侧各露 9dp 空档 —— 用户报的「聊天框与头分离」。收到 3dp 后贴合。
         */
        private const val TAIL_H_DP = 3.0f

        /** 左右内边距。 */
        private const val PAD_H_DP = 12.0f

        /** 上下内边距。 */
        private const val PAD_V_DP = 11.0f

        /** 气泡与窗口边缘的留白。 */
        private const val INSET_DP = 2.0f

        /** 默认最多显示行数（未显式设置时）。 */
        private const val MAX_LINES = 3

        /** 截断态行数：只露两行 + 省略号，点一下才展开。 */
        const val LINES_TRUNC = 2

        /** 展开态行数上限：再长也到此为止，避免气泡盖住整个人偶。 */
        const val LINES_EXPAND = 10

        /** 每页句数（用户要求「点击一次直接回答两句完整的话」）；改这里 = 改翻页粒度。 */
        const val PAGE_SENTENCES = 2

        /** 一片的行数硬上限（用户说的「对话框格子」）：两句超了就退一句，一句还超就按此硬切。 */
        const val PAGE_MAX_LINES = 3

        /**
         * 气泡最少显示行数（用户定案：不足三行就按两行显示）。
         * 【为什么】单行回复若只占一行高，气泡会随字数忽高忽低、切页时突兀；
         *   固定下限两行后，短句与两行句同一个高度，视觉稳定。
         */
        const val LINES_MIN = 2

        /** 行间距（dp），必须与 layout() 的 setLineSpacing 一致，否则补足行数时高度算不准。 */
        private const val LINE_GAP_DP = 2.0f

        /**
         * 气泡底部余量（dp）。
         * 【与 draw 对齐】draw() 里「框顶 = 高度 - 文字高 - 2*上下内边距 - 尾巴高」，
         *   恒等于 4dp；高度公式必须与之一致，脱节就会框比文字高一截。
         */
        private const val BUBBLE_SLACK_DP = 4.0f

        private fun clamp(v: Int, lo: Int, hi: Int): Int {
            return if (v < lo) lo else (if (v > hi) hi else v)
        }

        /** 句末标点本体（中英文都算）。 */
        private fun isSentenceEndCore(c: Char): Boolean {
            return c == '\u3002' || c == '\uFF01' || c == '\uFF1F' || c == '\uFF1B' || c == '\u2026'
                    || c == '.' || c == '!' || c == '?'
        }

        /** 收尾类标点：跟在句末标点后面时，仍算这一句的末尾。 */
        private fun isTailPunct(c: Char): Boolean {
            return c == '\u201D' || c == '\u300F' || c == '\u300D' || c == '\u300B' || c == '\uFF09'
                    || c == '\uFF3D' || c == '\uFF5D'
        }

        /** 英文点号防误切：小数 / 千分位里的点不认；句末点后必须跟空白、引号或行尾。 */
        private fun isBadNumberDot(prev: Char, c: Char, next: Char): Boolean {
            if (c == '.') {
                if (prev >= '0' && prev <= '9') {
                    return true
                }
                return next != ' ' && next != '\n' && next != '"' && next != '\'' && next != ')'
            }
            return next != ' ' && next != '\n' && next != '"' && next != '\''
        }

        /** [startLine, cut) 跨了几行；用于「一片不超过 PAGE_MAX_LINES 行」的判定。 */
        private fun lineSpan(sl: StaticLayout, startLine: Int, cut: Int): Int {
            val endLine = sl.getLineForOffset(Math.max(0, cut - 1))
            return endLine - startLine + 1
        }
    }

    private val path = Path()

    /**
     * 【v2.10.2】绘制用临时矩形：draw() 每帧都会走到，预分配避免每帧一个 RectF 对象
     * （原实现是 addRoundRect(new RectF(...))，待机 30fps × 气泡常显 ≈ 每秒 30 个短命对象）。
     */
    private val rect = RectF()

    private var text: String? = null

    /** 未切片的全文（分页用）；页表基于它，不受当前显示的页文本影响。 */
    private var fullText: String? = null

    /** 分页页表：每页 [startChar, endChar)；文本 / 宽度 / 缩放变化时失效，下次取页懒重建。 */
    private var pages: MutableList<IntArray>? = null

    /** buildPages 内部重排期间置真：内部 layout() 不能把刚算好的页表清掉。 */
    private var paging = false

    /** 期望每页行数；由 showPaged 写入，供懒重建使用。 */
    private var pageTarget = PAGE_SENTENCES

    private var layout: StaticLayout? = null

    private var height = 0

    /** 跟随人偶的缩放系数：圆角 / 内边距 / 尾巴 / 行距全部按比例放大。 */
    private var scale = 1.0f

    /** 当前生效的最大行数：截断态 / 展开态由外部切换。 */
    private var maxLines = MAX_LINES

    /** 上一次排版用的可用宽度与 density，缩放 / 宽度变化时据此重排。 */
    private var lastWidth = 0.0f
    private var lastDensity = 1.0f

    /** 设置缩放系数。字号由 PetView 改，这里改的是几何；必须重排才能让尺寸跟着变。 */
    fun setScale(f: Float) {
        if (f <= 0.0f || f == this.scale) {
            return
        }
        this.scale = f
        // 【v2.7】缩放改了可用文字宽度（换算到行数就不一样），页表必须重算。
        this.pages = null
        if (this.text != null) {
            layout(this.lastWidth, this.lastDensity)
        }
    }

    /** 按可用宽度排一版文本；density 由 PetView 传入其 dp()。 */
    private fun layout(availWidth: Float, density: Float) {
        // 【v2.7】只有可用宽度 / density 真变时才让页表失效。
        //   旧写法任何一次 layout（包括翻页时换显示文本）都清页表，
        //   结果 showPaged 刚建好的页表立刻被自己踢掉，page()/pageCount() 每次重算。
        val geometryChanged = availWidth != this.lastWidth || density != this.lastDensity
        this.lastWidth = availWidth
        this.lastDensity = density
        val text = this.text
        if (text == null) {
            return
        }
        // 可用宽度扣减也要乘 scale，否则放大后文字被挤成一行几个字。
        // 【修 v0.0.1】可用宽度还要扣掉左右留白（INSET）：绘制时框的左右边界各自
        //   内缩 inset，只扣 PAD_H 的话排版宽会比框内宽多 2*inset，最右一列字会顶出框外。
        val max = Math.max(1, Math.round(availWidth)
                - Math.round((PAD_H_DP * 2.0f + INSET_DP * 2.0f) * density * this.scale))
        this.layout = StaticLayout.Builder.obtain(text, 0, text.length, this.textPaint, max)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(LINE_GAP_DP * density * this.scale, 1.0f)
                // 回答过长只显示前 MAX_LINES 行，末尾省略号，不把气泡撑满整屏。
                .setMaxLines(this.maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        // 排版变了页边界就失效；paging 期间是自己的临时重排，不能清刚算好的页表。
        if (!this.paging && geometryChanged) {
            this.pages = null
        }
    }

    /** 设置文案并重排；availWidth 为 PetView 那边算好的可用宽度（贴边时已扣掉屏幕外部分）。 */
    fun show(str: String?, availWidth: Float, density: Float) {
        this.text = str
        layout(availWidth, density)
    }

    /**
     * 切换最大行数（截断态 LINES_TRUNC / 展开态 LINES_EXPAND）。
     *
     * 【坑】改完必须重排：StaticLayout 的行数在 build 时就定死了，
     *        只改字段不重排，画出来还是旧行数（与 setScale 同理）。
     */
    fun setMaxLines(i: Int) {
        val n = Math.max(1, i)
        if (n == this.maxLines) {
            return
        }
        this.maxLines = n
        if (this.text != null) {
            layout(this.lastWidth, this.lastDensity)
        }
    }

    /**
     * 当前文案在「截断行数」下是否被省略号截掉了。
     *
     * 【用途】点击推进气泡相位：没截断就别展开，直接收掉。
     *         判据用「排版行数 > 截断行数」，不依赖字符数——中英文混排宽度差异太大。
     */
    fun hasOverflow(): Boolean {
        if (this.text == null) {
            return false
        }
        // 把行数临时放到足够大，量出「全文到底几行」；量完恢复外部设定的值并重排。
        // 只在点击气泡时调用（频率极低），两次重排的代价可以接受。
        if (this.lastWidth <= 0.0f) {
            return false
        }
        val keep = this.maxLines
        this.maxLines = 1000
        layout(this.lastWidth, this.lastDensity)
        val lines = this.layout?.lineCount ?: 0
        this.maxLines = keep
        layout(this.lastWidth, this.lastDensity)
        return lines > LINES_TRUNC
    }

    /** 清空气泡。 */
    fun clear() {
        this.text = null
        this.fullText = null
        this.pages = null
        this.layout = null
        this.maxLines = MAX_LINES
    }

    /** 记下未切片的全文（分页用）；与 show() 的切片共存。 */
    fun setFullText(str: String?) {
        this.fullText = str
        this.pages = null
    }

    /**
     * 分页页表：[startChar, endChar)，按需构建并缓存。
     *
     * 【为什么按标点】用户要求「划分的时候用标点符号划分，不然可读性有点差」——
     *   按行硬切会把句子拦腰截断（切点落在 StaticLayout 的像素换行点上，
     *   中文可落在任意两字之间），读起来断句难看。
     * 【v2.7 粒度】按「句」切：一片尽量放 target 句完整的话；一片的行数硬上限是
     *   PAGE_MAX_LINES（用户说的「格子」），两句超限就退成一句，一句还超限就按行硬切。
     * 【边界】切点一律用「字符偏移」表达，下一片的起点直接接上一片的切点 ——
     *   绝不用 getLineStart(行号) 换算：切点落在行中时那样会丢字 / 重字。
     */
    private fun pageRanges(target: Int): MutableList<IntArray> {
        val cached = this.pages
        if (cached != null && this.pageTarget == target) {
            return cached
        }
        this.pageTarget = target
        val out = ArrayList<IntArray>()
        this.pages = out
        val full = this.fullText
        if (full == null || full.isEmpty() || this.lastWidth <= 0.0f) {
            return out
        }
        val keepText = this.text
        val keepMax = this.maxLines
        var sl: StaticLayout? = null
        try {
            this.paging = true
            this.text = full
            this.maxLines = 10000
            layout(this.lastWidth, this.lastDensity)
            sl = this.layout
        } finally {
            // 【v2.7】异常也要把临时状态恢复回去：否则 paging 停在 true、
            //   text 停在全文、maxLines 停在 10000，气泡会一直按整篇排版。
            this.paging = false
            this.text = keepText
            this.maxLines = keepMax
        }
        if (sl != null && sl.lineCount > 0) {
            val total = sl.lineCount
            val n = full.length
            val want = Math.max(1, target)
            val maxLines = Math.max(1, PAGE_MAX_LINES)
            var from = 0
            while (from < n) {
                val startLine = sl.getLineForOffset(Math.min(from, n - 1))
                // (1) 找第 want 个句末标点，切在其后：一片就是 want 句完整的话。
                var cut = sentenceCut(from, want, n)
                // (2) 该片超过行数上限（「格子」）：退到第 1 句；第 1 句仍超就放弃，走硬切。
                if (cut > from && lineSpan(sl, startLine, cut) > maxLines) {
                    val one = sentenceCut(from, 1, n)
                    cut = if (one > from && lineSpan(sl, startLine, one) <= maxLines) one else -1
                }
                // (3) 没标点 / 单句也超行：按行硬切（保底，绝不空页）。
                if (cut <= from) {
                    val lastLine = Math.min(startLine + maxLines, total)
                    cut = clamp(sl.getLineEnd(lastLine - 1), from + 1, n)
                }
                cut = clamp(cut, from + 1, n)
                out.add(intArrayOf(from, cut))
                // 【坑 v2.6】下一页起点必须直接接上一片的 cut；换成 getLineStart(行号)
                //   时，切点落在行中会让中间那段被丢掉或重复。
                from = cut
            }
        }
        // 【v2.7】恢复性重排仍包在 paging 里：别把刚算好的页表又清掉。
        this.paging = true
        try {
            layout(this.lastWidth, this.lastDensity)
        } finally {
            this.paging = false
        }
        return out
    }

    /**
     * 从 from 起找第 want 句的末尾，返回切点（标点及其收尾引号之后）；找不到返 -1。
     *
     * 【坑 v2.7】收尾引号 / 括号不能计句：“他说：“你好。””——“。”与“””会被数成两句，
     *   结果每片只放出一句，「两句一页」退化成「一句一页」。
     *   正确处理：收尾引号只把切点往后延一格，不增加句数。
     */
    private fun sentenceCut(from: Int, want: Int, n: Int): Int {
        var seen = 0
        var cut = -1
        for (i in from until n) {
            if (isSentenceEndCoreAt(i)) {
                seen++
                cut = i + 1
            } else if (cut == i && isTailPunct(this.fullText!![i])) {
                cut = i + 1
            }
            if (seen >= want) {
                return cut
            }
        }
        return -1
    }

    /** i 位置是不是句末标点本体（不含收尾引号 / 括号）。 */
    private fun isSentenceEndCoreAt(i: Int): Boolean {
        val full = this.fullText
        if (full == null || i < 0 || i >= full.length) {
            return false
        }
        val c = full[i]
        val prev = if (i > 0) full[i - 1] else 0.toChar()
        val next = if (i + 1 < full.length) full[i + 1] else ' '
        if (!isSentenceEndCore(c)) {
            return false
        }
        // 省略号是「……」两个字符，只切在第二个上，避免切出一半。
        if (c == '\u2026' && next == '\u2026') {
            return false
        }
        return !isBadNumberDot(prev, c, next)
    }

    /** 按「每页句数」分页并只显示第一页，返回总页数（供 PetTalk 判「还有没有下一片」）。 */
    fun showPaged(full: String?, target: Int, availWidth: Float, density: Float): Int {
        this.lastWidth = availWidth
        this.lastDensity = density
        setFullText(full)
        this.pageTarget = Math.max(1, target)
        this.pages = null
        val count = pageRanges(this.pageTarget).size
        val first = if (count > 0) page(0) else full
        show(first ?: full, availWidth, density)
        return count
    }

    /** 总页数（按当前 pageTarget 粒度）。 */
    fun pageCount(): Int {
        return pageRanges(this.pageTarget).size
    }

    /** 取第 index 页文本（越界返 null）；翻页用。 */
    fun page(index: Int): String? {
        val list = pageRanges(this.pageTarget)
        val full = this.fullText ?: return null
        if (index < 0 || index >= list.size) {
            return null
        }
        val r = list[index]
        val from = clamp(r[0], 0, full.length)
        val to = clamp(r[1], from, full.length)
        return full.substring(from, to)
    }

    /** 窗口尺寸变化时按新宽度重排。只在 View 尺寸真变时触发，频率极低，不做等宽短路（宽度语义与可见区不同，比较会误判）。 */
    fun onWidth(width: Float, density: Float) {
        if (this.text == null) {
            return
        }
        layout(width, density)
    }

    /** 强制按新可用宽度重排（窗口被移动、可见区变了时必须走这里，不能靠 onWidth 的等宽短路）。 */
    fun relayout(availWidth: Float, density: Float) {
        if (this.text == null) {
            return
        }
        if (availWidth != this.lastWidth) {
            this.pages = null
        }
        layout(availWidth, density)
    }

    /** 气泡自身需要的高度（含上下内边距与尾巴）；无内容时返 0。 */
    fun neededHeight(width: Float, density: Float): Int {
        // 【修 v0.0.1】宽度变了必须重排：旧实现在有缓存 layout 时直接拿旧 layout 量高，
        //   若那一版是按更窄宽度排的（多一行），高度就按多一行算（用户报的「两行占三行」来源之一）。
        if (this.layout == null || width != this.lastWidth) {
            layout(width, density)
        }
        val s = this.scale
        return Math.round(needTextHeight(density))
                + (Math.round(PAD_V_DP * density * s) * 2)
                + Math.round(TAIL_H_DP * density * s)
                + Math.round(BUBBLE_SLACK_DP * density * s)
    }

    /**
     * 文字区实占高度：不足 LINES_MIN 行时按 LINES_MIN 行补足，其余按实际行高。
     * 【用户定案】文字过多用三行、不足三行按两行显示 —— 下限固定，上限由 maxLines 截断。
     */
    private fun needTextHeight(density: Float): Float {
        val staticLayout = this.layout
        if (staticLayout == null) {
            return 0.0f
        }
        val lines = staticLayout.lineCount
        if (lines <= 0) {
            return 0.0f
        }
        if (lines >= LINES_MIN) {
            return staticLayout.height.toFloat()
        }
        return (staticLayout.height * LINES_MIN
                + (Math.round(LINE_GAP_DP * density * this.scale) * (LINES_MIN - 1))).toFloat()
    }

    /** 由 PetView 设置可用高度（决定气泡贴底位置）。 */
    fun setHeight(i: Int) {
        this.height = i
    }

    fun height(): Int {
        return this.height
    }

    /** 是否有可画内容。 */
    fun hasContent(): Boolean {
        return this.text != null && this.layout != null
    }

    /**
     * 画气泡：rounded rect + 底部中间尾巴。
     *
     * @param windowX PetView 所在窗口的屏幕 x（贴边时可能为负）
     * @param screenW 屏幕宽度，用于把气泡夹进可见区
     */
    fun draw(canvas: Canvas, density: Float, windowX: Float, screenW: Float) {
        val s = this.scale
        val padV = PAD_V_DP * density * s
        val padH = PAD_H_DP * density * s
        val radius = RADIUS_DP * density * s
        val inset = INSET_DP * density * s
        val tailH = TAIL_H_DP * density * s
        val tail = TAIL_DP * density * s
        // 【修 v0.0.1】框宽锁定为「排版时那一版可用宽度」：canvas 宽是窗口宽，
        //   贴边时窗口有一截在屏外，两套口径不一致会让文字按 A 宽换行、框按 B 宽绘制
        //   （用户报的「串字 / 造成格子 / 有字溢出」）。
        val width = if (this.lastWidth > 0.0f) this.lastWidth else canvas.width.toFloat()
        // 人偶贴边时窗口有 0.68*petWidth 在屏幕外，气泡按可见区收边，否则一半画在屏外。
        var left = Math.max(inset, (0.0f - windowX) + inset)
        var right = Math.min(width - inset, (screenW - windowX) - inset)
        if (right - left < (4.0f * density)) {
            // 可见区过窄（极端缩放 + 贴边）时退回窗口内绘制，总比画到屏外强。
            left = inset
            right = width - inset
        }
        // 【修 v0.0.1】框高与 neededHeight 同口径（至少 LINES_MIN 行），
        //   文字再于框内垂直居中：单行回复时框比文字高，不居中会贴框顶像填空。
        val textH = this.layout!!.height.toFloat()
        val boxInnerH = needTextHeight(density)
        val boxH = boxInnerH + (padV * 2.0f)
        val top = Math.max(1.0f * density, (this.height - boxH) - tailH)
        val bottom = boxH + top
        this.path.reset()
        this.rect.set(left, top, right, bottom)
        this.path.addRoundRect(this.rect, radius, radius, Path.Direction.CW)
        val mid = (left + right) / 2.0f
        this.path.moveTo(mid - tail, bottom - (1.0f * density))
        this.path.lineTo(mid, tailH + bottom)
        this.path.lineTo(mid + tail, bottom - (1.0f * density))
        this.path.close()
        canvas.drawPath(this.path, this.fill)
        canvas.drawPath(this.path, this.edge)
        canvas.save()
        // 【修 v0.0.1】把文字裁进框内：排版口径万一对不齐，也不会溢到框外。
        canvas.clipRect(left + padH, top + padV, right - padH, bottom - padV)
        canvas.translate(left + padH, top + padV + Math.max(0.0f, (boxInnerH - textH) / 2.0f))
        this.layout!!.draw(canvas)
        canvas.restore()
    }
}
