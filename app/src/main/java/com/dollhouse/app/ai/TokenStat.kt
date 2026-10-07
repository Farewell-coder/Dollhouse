package com.dollhouse.app.ai

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.theme.Icons
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.MiniChart
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【职责】本机 AI 请求用量的本地统计：记录 token、维护按天 / 按小时历史、渲染统计页。
 *
 * 【入口】ChatPanel 每次收到模型响应后调 recordFrom()；聊天页顶栏圆圈 / 设置页「查看 Token」调 open()。
 *
 * 【交互】数据全部落在本地 SharedPreferences，不联网、不上传、不估算；统计页全部代码构建，无 layout。
 *
 * 【坑】① 「今日」计数按自然日翻篇，跨天首次写入时才归零；
 *       ② 累计计数只增不减，想清零只能清 App 数据；
 *       ③ 按天历史最多留 KEEP_DAYS 天，超出丢最旧的；
 *       ④ 页面靠 TAG_PAGE 认领，切分段 / 翻日期都是整页重建（open() 里先摘旧再挂新）。
 *
 * 四件事：1. recordFrom() 从响应 usage 累加；2. 翻篇归零今日；3. 维护按天 / 按小时历史；
 *          4. open() 打开统计页（每日 / 每周 / 累计三档 + 折线图 + 费用估算）。
 */
object TokenStat {
    private const val LOG_TAG = "Dollhouse"

    private const val PREF = "feiyu_pet"
    private const val K_DAY = "tk_day"
    private const val K_DAY_IN = "tk_day_in"
    private const val K_DAY_OUT = "tk_day_out"
    private const val K_DAY_REQ = "tk_day_req"
    private const val K_DAY_PEAK = "tk_day_peak"
    private const val K_DAY_CACHE = "tk_day_cache"
    private const val K_ALL_IN = "tk_all_in"
    private const val K_ALL_OUT = "tk_all_out"
    private const val K_ALL_REQ = "tk_all_req"
    private const val K_ALL_CACHE = "tk_all_cache"

    /** 【v2.10.0】按天历史：{"2026-10-04":{"in":1,"out":2,"req":1,"cache":0,"peak":3}}，最多 KEEP_DAYS 天。 */
    private const val K_DAYS = "tk_days"

    /** 【v2.10.0】今日 24 个整点的 token 数（JSON 数组，跨天清空），给「每日」折线图用。 */
    private const val K_HOURS = "tk_hours"

    /** 【v2.10.0】单价：元 / 百万 token，默认 1.0（可点药丸编辑）。 */
    private const val K_PRICE = "tk_price"

    private const val KEEP_DAYS = 31
    private const val DEF_PRICE = 1.0f
    private const val MILLION = 1000000f

    const val TAG_PAGE = "feiyu_token_page"

    /** 当前分段：0 = 每日，1 = 每周，2 = 累计。静态保存，因为整页重建。 */
    private var MODE = 0

    /** 往前翻的偏移：每日 = 天，每周 = 周，累计 = 不用。 */
    private var OFFSET = 0

    /* ----------------------------- 记录 ----------------------------- */

    /** 由 ChatPanel 的最终回调调用；msg 为 null（出错）时忽略。 */
    @JvmStatic
    fun recordFrom(ctx: Context?, msg: JSONObject?) {
        try {
            if (ctx == null || msg == null) {
                return
            }
            // 【v2.10.0】优先读 DeepSeekClient 挂上来的顶层 usage（键 __usage）；
            //  旧路径（message 自带 usage / data.usage）保留，兼容别家返回结构。
            var u = msg.optJSONObject("__usage")
            if (u == null) {
                u = msg.optJSONObject("usage")
            }
            val data = msg.optJSONObject("data")
            if (u == null && data != null) {
                u = data.optJSONObject("usage")
            }
            if (u == null) {
                return
            }
            var inTok = u.optInt("prompt_tokens", 0)
            val outTok = u.optInt("completion_tokens", 0)
            if (inTok == 0 && outTok == 0) {
                val t = u.optInt("total_tokens", 0)
                if (t == 0) {
                    return
                }
                inTok = t
            }
            var cache = 0
            val det = u.optJSONObject("prompt_tokens_details")
            if (det != null) {
                cache = det.optInt("cached_tokens", 0)
            }
            if (cache == 0) {
                cache = u.optInt("prompt_cache_hit_tokens", 0)
            }
            add(ctx, inTok, outTok, cache)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    @Synchronized
    private fun add(ctx: Context, inTok: Int, outTok: Int, cache: Int) {
        val p = prefs(ctx)
        rollDay(p)
        val today = todayKey()

        // ---- 按天历史：今天的桶累加，顺带记单次峰值 ----
        var days = readDays(p)
        val d = days.optJSONObject(today) ?: JSONObject()
        try {
            d.put("in", d.optInt("in", 0) + inTok)
            d.put("out", d.optInt("out", 0) + outTok)
            d.put("req", d.optInt("req", 0) + 1)
            d.put("cache", d.optInt("cache", 0) + cache)
            d.put("peak", Math.max(d.optInt("peak", 0), inTok + outTok))
            days.put(today, d)
        } catch (ignored: Throwable) {
        }
        days = trimDays(days)

        // ---- 按小时：今天的第 h 格累加（跨天时 rollDay 已把整串清掉） ----
        val hours = readHours(p)
        val hh = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hh >= 0 && hh < 24) {
            hours[hh] += inTok + outTok
        }

        p.edit()
                .putInt(K_DAY_IN, p.getInt(K_DAY_IN, 0) + inTok)
                .putInt(K_DAY_OUT, p.getInt(K_DAY_OUT, 0) + outTok)
                .putInt(K_DAY_REQ, p.getInt(K_DAY_REQ, 0) + 1)
                .putInt(K_DAY_CACHE, p.getInt(K_DAY_CACHE, 0) + cache)
                .putInt(K_DAY_PEAK, Math.max(p.getInt(K_DAY_PEAK, 0), inTok + outTok))
                .putInt(K_ALL_IN, p.getInt(K_ALL_IN, 0) + inTok)
                .putInt(K_ALL_OUT, p.getInt(K_ALL_OUT, 0) + outTok)
                .putInt(K_ALL_REQ, p.getInt(K_ALL_REQ, 0) + 1)
                .putInt(K_ALL_CACHE, p.getInt(K_ALL_CACHE, 0) + cache)
                .putString(K_DAYS, days.toString())
                .putString(K_HOURS, hoursToJson(hours))
                .apply()
    }

    /** 跨自然日就把今日计数与小时桶清零（按天历史不动，那是长期账）。 */
    private fun rollDay(p: SharedPreferences) {
        val today = todayKey()
        if (today != p.getString(K_DAY, "")) {
            p.edit()
                    .putString(K_DAY, today)
                    .putInt(K_DAY_IN, 0)
                    .putInt(K_DAY_OUT, 0)
                    .putInt(K_DAY_REQ, 0)
                    .putInt(K_DAY_PEAK, 0)
                    .putInt(K_DAY_CACHE, 0)
                    .putString(K_HOURS, "")
                    .apply()
        }
    }

    private fun prefs(ctx: Context): SharedPreferences {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    /* ----------------------------- 存储工具 ----------------------------- */

    private fun todayKey(): String {
        return dayKey(0)
    }

    /** offset 天前的日期串（offset = 0 即今天）。 */
    private fun dayKey(offset: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_MONTH, -offset)
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun monthDay(offset: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_MONTH, -offset)
        return String.format(Locale.US, "%d/%d", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun readDays(p: SharedPreferences): JSONObject {
        try {
            val s = p.getString(K_DAYS, "")
            if (s == null || s.length == 0) {
                return JSONObject()
            }
            return JSONObject(s)
        } catch (t: Throwable) {
            return JSONObject()
        }
    }

    private fun readHours(p: SharedPreferences): IntArray {
        val h = IntArray(24)
        try {
            val s = p.getString(K_HOURS, "")
            if (s == null || s.length == 0) {
                return h
            }
            val a = JSONArray(s)
            val n = Math.min(24, a.length())
            for (i in 0 until n) {
                h[i] = a.optInt(i, 0)
            }
        } catch (ignored: Throwable) {
        }
        return h
    }

    private fun hoursToJson(h: IntArray): String {
        val a = JSONArray()
        for (i in 0 until 24) {
            a.put(h[i])
        }
        return a.toString()
    }

    /** 只保留最近 KEEP_DAYS 天（key 是 yyyy-MM-dd，可直接字典序排）。 */
    private fun trimDays(days: JSONObject): JSONObject {
        try {
            if (days.length() <= KEEP_DAYS) {
                return days
            }
            val keys = ArrayList<String>()
            val it = days.keys()
            while (it.hasNext()) {
                keys.add(it.next())
            }
            Collections.sort(keys)
            while (keys.size > KEEP_DAYS) {
                days.remove(keys.removeAt(0))
            }
        } catch (ignored: Throwable) {
        }
        return days
    }

    private fun price(p: SharedPreferences): Float {
        val f = p.getFloat(K_PRICE, DEF_PRICE)
        return if (f < 0f) DEF_PRICE else f
    }

    /** 费用估算：tokens / 百万 × 单价。 */
    private fun cost(tokens: Long, price: Float): Float {
        return tokens / MILLION * price
    }

    /* ----------------------------- 统计页 ----------------------------- */

    /** 返回键用：统计页开着就关掉并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context?): Boolean {
        try {
            val act = findActivity(ctx) ?: return false
            val content = act.findViewById(android.R.id.content) as ViewGroup?
            if (content == null) {
                return false
            }
            val old = content.findViewWithTag<View>(TAG_PAGE)
            if (old == null) {
                return false
            }
            UiKit.closePage(old)
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    /** 由聊天页顶栏圆圈 / 设置页入口行调用。offset 归零，永远从「今天」开始看。 */
    @JvmStatic
    fun open(ctx: Context?) {
        try {
            OFFSET = 0
            show(ctx)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
    }

    /** 整页重建（切分段 / 翻日期都走这里，不重建 Activity）。 */
    private fun show(ctx: Context?) {
        val act = findActivity(ctx) ?: return
        val content = act.findViewById(android.R.id.content) as ViewGroup?
        if (content == null) {
            return
        }
        val old = content.findViewWithTag<View>(TAG_PAGE)
        val page = buildPage(act, content)
        // 切分段 / 翻日期是同层刷新：用交叉淡入，不做位移（位移会诱导用户以为页面在横向跳）。
        UiKit.swapPage(content, page, TAG_PAGE)
    }

    private fun findActivity(ctx: Context?): Activity? {
        var c: Context? = ctx
        var i = 0
        while (i < 8 && c != null) {
            if (c is Activity) {
                return c
            }
            if (c is ContextWrapper) {
                c = c.baseContext
            } else {
                break
            }
            i++
        }
        return null
    }

    private fun buildPage(act: Activity, content: ViewGroup): View {
        val ctx: Context = act

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(UiKit.BG)
        val pad = dp(ctx, 16)
        // 【顶部不再留白】窗口未铺满时，页盒顶上还有一层容器让位，这里的 10dp 是
        //   内容与状态栏之间的额外呼吸；窗口铺满后它就直接顶在状态栏下沿，
        //   而紧随其后的 UiKit.topBar 已经自带 statusBarPad，两处叠加会多出 10dp。
        //   顶部归零，让位统一交给 topBar。
        box.setPadding(pad, 0, pad, dp(ctx, 20))

        // 顶栏：统一走 UiKit.topBar
        val bar = UiKit.topBar(ctx, "令牌消耗统计", "本机 AI 请求用量", View.OnClickListener {
            closeIfOpen(act)
        })
        box.addView(bar)

        val p = prefs(ctx)
        rollDay(p)

        // 分段：每日 / 每周 / 累计
        box.addView(segRow(ctx))

        // 日期条：累计模式没有时间维度，不显示
        if (MODE != 2) {
            box.addView(dateRow(ctx))
        }

        box.addView(buildOverviewCard(ctx, p))
        // ---- 2x2 指标网格 ----
        box.addView(buildMetricsGrid(ctx, p))
        // ---- 说明 ----
        box.addView(buildNote(ctx))
        val sc = ScrollView(ctx)
        sc.setBackgroundColor(UiKit.BG)
        sc.addView(box, ViewGroup.LayoutParams(-1, -2))
        return sc
    }

    /** 周期总览大卡：总数 / 费用 / 药丸 + 折线图 + 图下小字。 */
    private fun buildOverviewCard(ctx: Context, p: SharedPreferences): View {
        val st = stats(p, MODE, OFFSET)
        val inTok = st[0]
        val outTok = st[1]
        val req = st[2]
        val cache = st[3]
        val peak = st[4]
        val unit = price(p)
        val over = card(ctx)
        val cap = TextView(ctx)
        cap.text = if (MODE == 0) "当日总览" else (if (MODE == 1) "本周总览" else "累计总览")
        cap.setTextSize(UiKit.FS_BTN)
        cap.setTextColor(UiKit.TITLE)
        cap.typeface = Typeface.DEFAULT_BOLD
        over.addView(cap)
        val big = TextView(ctx)
        big.text = fmt(inTok + outTok) + " Token"
        big.setTextSize(30.0f)
        big.setTextColor(UiKit.TITLE)
        big.typeface = Typeface.DEFAULT_BOLD
        big.setPadding(0, dp(ctx, 10), 0, 0)
        over.addView(big)
        val sub = TextView(ctx)
        sub.text = "输入 " + fmt(inTok) + "　·　输出 " + fmt(outTok)
        sub.setTextSize(UiKit.FS_SUB)
        sub.setTextColor(UiKit.SUB)
        sub.setPadding(0, dp(ctx, 2), 0, 0)
        over.addView(sub)
        val fee = TextView(ctx)
        fee.text = "费用约 ￥" + money(cost(inTok + outTok, unit))
        fee.setTextSize(UiKit.FS_BTN)
        fee.setTextColor(UiKit.TITLE)
        fee.typeface = Typeface.DEFAULT_BOLD
        fee.setPadding(0, dp(ctx, 8), 0, 0)
        over.addView(fee)
        val chips = LinearLayout(ctx)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(0, dp(ctx, 8), 0, 0)
        chips.addView(pill(ctx, "请求 " + req + " 次", null))
        chips.addView(pill(ctx, "￥" + money(unit) + " / 百万", View.OnClickListener { v ->
            editPrice(v.context)
        }))
        over.addView(chips)
        // ---- 折线图 ----
        val tip = TextView(ctx)
        tip.setTextSize(UiKit.FS_TINY)
        tip.setTextColor(UiKit.SUB)
        tip.setPadding(0, dp(ctx, 6), 0, 0)
        val chart = MiniChart(ctx)
        val vals = series(p, MODE, OFFSET)
        val labs = seriesLabels(MODE, OFFSET, vals.size)
        chart.setPoints(vals, labs)
        val tipRef = tip
        chart.setOnPick(MiniChart.OnPickListener { index, value, label ->
            // 【硬约束】不用浮层短提示：把结果写进图表下那行已有小字里。
            tipRef.text = (if (label == null || label.length == 0) "#" + index else label) +
                    "　" + fmt(value.toLong()) + " Token"
        })
        over.addView(chart, LinearLayout.LayoutParams(-1, -2))
        over.addView(tip)
        return over
    }

    /** 2x2 指标网格：峰值 / 请求次数 / 缓存命中 / 缓存率。 */
    private fun buildMetricsGrid(ctx: Context, p: SharedPreferences): View {
        val st = stats(p, MODE, OFFSET)
        val inTok = st[0]
        val req = st[2]
        val cache = st[3]
        val peak = st[4]
        val grid = LinearLayout(ctx)
        grid.orientation = LinearLayout.VERTICAL
        val glp = LinearLayout.LayoutParams(-1, -2)
        glp.topMargin = dp(ctx, 10)
        grid.layoutParams = glp
        grid.addView(row(ctx, "峰值 Token", fmt(peak), "请求次数", req.toString() + " 次"))
        grid.addView(row(ctx, "缓存命中", fmt(cache),
                "缓存率", if (inTok > 0) Math.round(cache * 100f / inTok).toString() + "%" else "—"))
        return grid
    }

    /** 页脚说明：统计口径 / 费用估算 / 保留策略。 */
    private fun buildNote(ctx: Context): View {
        val note = TextView(ctx)
        note.setTextSize(UiKit.FS_TINY)
        note.setTextColor(UiKit.SUB)
        note.setLineSpacing(dp(ctx, 3).toFloat(), 1.0f)
        note.setPadding(dp(ctx, 2), dp(ctx, 14), dp(ctx, 2), 0)
        note.text = "只统计本机发起的 AI 请求，数据来自接口返回的 usage 字段；接口不返回就不计入，不做估算。\n" +
                "费用按「单价 × 用量」估算，点上面那枚药丸可以改单价，默认 ￥1.00 / 百万 token。\n" +
                "按天历史保留最近 " + KEEP_DAYS + " 天；累计计数只增不减，数据仅保存在这台设备上。"
        return note
    }

    /* ----------------------------- 页面零件 ----------------------------- */

    private fun segRow(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(ctx, 10)
        row.layoutParams = lp
        val names = arrayOf("每日", "每周", "累计")
        for (i in names.indices) {
            val t = if (i == MODE) UiKit.primaryChip(ctx, names[i]) else UiKit.outlineChip(ctx, names[i])
            val idx = i
            t.isClickable = true
            UiKit.press(t)
            t.setOnClickListener { v ->
                if (MODE == idx) {
                    return@setOnClickListener
                }
                MODE = idx
                OFFSET = 0
                show(v.context)
            }
            val clp = LinearLayout.LayoutParams(-2, -2)
            clp.rightMargin = dp(ctx, 8)
            t.layoutParams = clp
            row.addView(t)
        }
        return row
    }

    private fun dateRow(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(ctx, 10)
        row.layoutParams = lp

        val prev = UiKit.iconView(ctx, Icons.IC_CHEVRON_LEFT, UiKit.FS_ICON, UiKit.TITLE)
        prev.isClickable = true
        UiKit.press(prev)
        prev.setOnClickListener { v ->
            OFFSET++
            show(v.context)
        }
        row.addView(prev, LinearLayout.LayoutParams(dp(ctx, UiKit.HIT_DP), dp(ctx, UiKit.HIT_DP)))

        val label = TextView(ctx)
        label.text = periodLabel()
        label.setTextSize(UiKit.FS_BTN)
        label.setTextColor(UiKit.TITLE)
        label.typeface = Typeface.DEFAULT_BOLD
        label.gravity = Gravity.CENTER
        label.setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))

        val next = UiKit.iconView(ctx, Icons.IC_CHEVRON_RIGHT, UiKit.FS_ICON, UiKit.TITLE)
        next.isClickable = true
        UiKit.press(next)
        val canBack = OFFSET > 0
        next.isEnabled = canBack
        next.alpha = if (canBack) 1.0f else 0.3f
        next.setOnClickListener { v ->
            if (OFFSET <= 0) {
                return@setOnClickListener
            }
            OFFSET--
            show(v.context)
        }
        row.addView(next, LinearLayout.LayoutParams(dp(ctx, UiKit.HIT_DP), dp(ctx, UiKit.HIT_DP)))
        return row
    }

    private fun periodLabel(): String {
        if (MODE == 0) {
            return if (OFFSET == 0) "今天" else monthDay(OFFSET)
        }
        if (MODE == 1) {
            if (OFFSET == 0) {
                return "最近 7 天"
            }
            return monthDay(OFFSET * 7 + 6) + " – " + monthDay(OFFSET * 7)
        }
        return "全部"
    }

    /** 取某个分段的汇总：{in, out, req, cache, peak}。 */
    private fun stats(p: SharedPreferences, mode: Int, offset: Int): LongArray {
        val r = LongArray(5)
        if (mode == 0) {
            val d = readDays(p).optJSONObject(dayKey(offset))
            if (d != null) {
                r[0] = d.optInt("in", 0).toLong()
                r[1] = d.optInt("out", 0).toLong()
                r[2] = d.optInt("req", 0).toLong()
                r[3] = d.optInt("cache", 0).toLong()
                r[4] = d.optInt("peak", 0).toLong()
            }
            return r
        }
        if (mode == 1) {
            val days = readDays(p)
            val base = offset * 7
            for (i in 0 until 7) {
                val d = days.optJSONObject(dayKey(base + i)) ?: continue
                r[0] = r[0] + d.optInt("in", 0)
                r[1] = r[1] + d.optInt("out", 0)
                r[2] = r[2] + d.optInt("req", 0)
                r[3] = r[3] + d.optInt("cache", 0)
                r[4] = Math.max(r[4], d.optInt("peak", 0).toLong())
            }
            return r
        }
        r[0] = p.getInt(K_ALL_IN, 0).toLong()
        r[1] = p.getInt(K_ALL_OUT, 0).toLong()
        r[2] = p.getInt(K_ALL_REQ, 0).toLong()
        r[3] = p.getInt(K_ALL_CACHE, 0).toLong()
        r[4] = p.getInt(K_DAY_PEAK, 0).toLong()
        val days = readDays(p)
        val it = days.keys()
        while (it.hasNext()) {
            val d = days.optJSONObject(it.next())
            if (d != null) {
                r[4] = Math.max(r[4], d.optInt("peak", 0).toLong())
            }
        }
        return r
    }

    /** 折线数据：每日 = 今日 24 格；每周 = 7 天；累计 = 最近 31 天。 */
    private fun series(p: SharedPreferences, mode: Int, offset: Int): FloatArray {
        if (mode == 0) {
            val h = readHours(p)
            // 翻到过去的某天时今日小时桶不适用，改为「该天 0 格」而不是错画今天的数据。
            if (offset != 0) {
                return FloatArray(0)
            }
            val v = FloatArray(24)
            for (i in 0 until 24) {
                v[i] = h[i].toFloat()
            }
            return v
        }
        val n = if (mode == 1) 7 else KEEP_DAYS
        val base = if (mode == 1) offset * 7 else 0
        val days = readDays(p)
        val v = FloatArray(n)
        // 累计模式按「今天往前数 n 天」排，保证右端永远是今天。
        for (i in 0 until n) {
            val back = if (mode == 1) (base + (n - 1 - i)) else (n - 1 - i)
            val d = days.optJSONObject(dayKey(back))
            v[i] = if (d == null) 0f else (d.optInt("in", 0) + d.optInt("out", 0)).toFloat()
        }
        return v
    }

    private fun seriesLabels(mode: Int, offset: Int, len: Int): Array<String>? {
        if (len <= 0) {
            return null
        }
        val s = Array(len) { "" }
        if (mode == 0) {
            for (i in 0 until len) {
                s[i] = i.toString() + " 时"
            }
            return s
        }
        if (mode == 1) {
            val base = offset * 7
            for (i in 0 until len) {
                s[i] = monthDay(base + (len - 1 - i))
            }
            return s
        }
        for (i in 0 until len) {
            s[i] = monthDay(len - 1 - i)
        }
        return s
    }

    /* ----------------------------- 单价编辑 ----------------------------- */

    private fun editPrice(ctx: Context) {
        val act = findActivity(ctx) ?: return
        val p = prefs(act)
        val e = EditText(act)
        e.isSingleLine = true
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        e.setText(String.format(Locale.US, "%.2f", price(p)))
        UiKit.field(e, act)
        UiKit.showDialog(act, "单价（元 / 百万 token）", e, "保存",
                View.OnClickListener { v ->
                    try {
                        var f = e.text.toString().trim().toFloat()
                        if (f < 0f) {
                            f = 0f
                        }
                        p.edit().putFloat(K_PRICE, f).apply()
                    } catch (ignored: Throwable) {
                    }
                    show(ctx)
                },
                "取消", null)
    }

    /* ----------------------------- 小控件 ----------------------------- */

    private fun row(ctx: Context, k1: String, v1: String, k2: String, v2: String): LinearLayout {
        val cardView = card(ctx)
        cardView.orientation = LinearLayout.HORIZONTAL

        val left = LinearLayout(ctx)
        left.orientation = LinearLayout.VERTICAL
        left.addView(label(ctx, k1))
        left.addView(value(ctx, v1))
        cardView.addView(left, LinearLayout.LayoutParams(0, -2, 1.0f))

        val right = LinearLayout(ctx)
        right.orientation = LinearLayout.VERTICAL
        right.addView(label(ctx, k2))
        right.addView(value(ctx, v2))
        cardView.addView(right, LinearLayout.LayoutParams(0, -2, 1.0f))
        return cardView
    }

    private fun label(ctx: Context, s: String): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(UiKit.FS_SUB)
        t.setTextColor(UiKit.SUB)
        return t
    }

    private fun value(ctx: Context, s: String): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(18.0f)
        t.setTextColor(UiKit.TITLE)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, dp(ctx, 4), 0, 0)
        return t
    }

    private fun pill(ctx: Context, s: String, click: View.OnClickListener?): TextView {
        val t = UiKit.chip(ctx, s, UiKit.CHAT_BUBBLE_USER, UiKit.CHAT_CHIP_BG)
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.rightMargin = dp(ctx, 8)
        t.layoutParams = lp
        if (click != null) {
            t.isClickable = true
            UiKit.press(t)
            t.setOnClickListener(click)
        }
        return t
    }

    private fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = UiKit.cardBg(ctx)
        c.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(ctx, 10)
        c.layoutParams = lp
        return c
    }

    /** 1234 -> 1,234 */
    private fun fmt(n: Long): String {
        val s = Math.max(n, 0L).toString()
        val b = StringBuilder()
        var c = 0
        for (i in s.length - 1 downTo 0) {
            b.append(s[i])
            c++
            if (c % 3 == 0 && i > 0) {
                b.append(',')
            }
        }
        return b.reverse().toString()
    }

    /** 金额保留两位。 */
    private fun money(v: Float): String {
        return String.format(Locale.US, "%.2f", v)
    }

    /** 尺寸换算：统一走 UiKit，避免多处重复实现。 */
    private fun dp(ctx: Context, v: Int): Int {
        return UiKit.dp(ctx, v.toFloat())
    }
}
