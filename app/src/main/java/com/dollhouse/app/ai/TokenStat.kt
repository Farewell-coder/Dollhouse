package com.dollhouse.app.ai

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollhouse.app.core.Logs
import com.dollhouse.app.ui.compose.ComposeHost
import com.dollhouse.app.ui.compose.DhForm
import com.dollhouse.app.ui.compose.DhKit
import com.dollhouse.app.ui.compose.DhTokens
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
 * 【入口】ChatPanel 每次收到模型响应后调 [recordFrom]；聊天页顶栏圆圈 / 设置页「查看 Token」调 [open]。
 *
 * 【交互】数据全部落在本地 SharedPreferences，不联网、不上传、不估算。
 *
 * 【坑】① 「今日」计数按自然日翻篇，跨天首次写入时才归零；
 *       ② 累计计数只增不减，想清零只能清 App 数据；
 *       ③ 按天历史最多留 [KEEP_DAYS] 天，超出丢最旧的；
 *       ④ 页面靠 [TAG_PAGE] 认领，切分段 / 翻日期在 Compose 里改为状态驱动（不再整页重建）。
 *
 * 【迁移】r7 起页面正文改为 Jetpack Compose（规格书主线），**对外契约一字未动**：
 *   仍是 `object` + `@JvmStatic recordFrom/closeIfOpen/open`，仍挂 `android.R.id.content`
 *   且打同一个 [TAG_PAGE]，仍走 `UiKit.swapPage/closePage` 的页面栈与转场。
 *   调用方（`MainActivity` / `ChatActivity` / `ChatPanel` / `SettingsScreen`）零改动。
 *
 * 【为什么页根还是 View】`UiKit.swapPage(content, page, tag)` 收的是 `View`，
 *   且 `GlobalBackground.installPage` 要把背景图铺在**页根的 background** 上。
 *   所以用 [ComposeHost.createView] 产出一个 `ComposeView` 当「页」，内部 100% Compose。
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

    /** 【v2.10.0】按天历史：{"2026-10-04":{"in":1,"out":2,"req":1,"cache":0,"peak":3}}，最多 [KEEP_DAYS] 天。 */
    private const val K_DAYS = "tk_days"

    /** 【v2.10.0】今日 24 个整点的 token 数（JSON 数组，跨天清空），给「每日」折线图用。 */
    private const val K_HOURS = "tk_hours"

    /** 【v2.10.0】单价：元 / 百万 token，默认 1.0（可点药丸编辑）。 */
    private const val K_PRICE = "tk_price"

    private const val KEEP_DAYS = 31
    private const val DEF_PRICE = 1.0f
    private const val MILLION = 1000000f

    const val TAG_PAGE = "feiyu_token_page"

    /** 当前分段：0 = 每日，1 = 每周，2 = 累计。静态保存，因为页面栈重建时要回到上次分段。 */
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

    /** 只保留最近 [KEEP_DAYS] 天（key 是 yyyy-MM-dd，可直接字典序排）。 */
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
    private fun cost(tokens: Long, unit: Float): Float {
        return tokens / MILLION * unit
    }

    /* ----------------------------- 页面栈 ----------------------------- */

    /** 返回键用：统计页开着就关掉并返回 true。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context?): Boolean {
        try {
            val act = findActivity(ctx) ?: return false
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
            val old = content.findViewWithTag<View>(TAG_PAGE) ?: return false
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

    private fun show(ctx: Context?) {
        val act = findActivity(ctx) ?: return
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        // Compose 运行所需 owner（原生 Activity 不自动装），幂等。
        ComposeHost.installForActivity(act)
        val page = ComposeHost.createView(act) { TokenContent(act) }
        // 打开统计页：走 openPage 的右侧滑入（与其它一级页一致），不是 swapPage。
        UiKit.openPage(content, page, TAG_PAGE)
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

    /* ----------------------------- Compose 页面 ----------------------------- */

    @Composable
    private fun TokenContent(act: Activity) {
        var mode by remember { mutableStateOf(MODE) }
        var offset by remember { mutableStateOf(OFFSET) }
        var revision by remember { mutableStateOf(0) }
        var editing by remember { mutableStateOf(false) }
        val p = remember(act) { prefs(act) }
        // 跨天翻篇：轻量、幂等，进页面时对齐一次。
        LaunchedEffect(act) { rollDay(p) }

        DhKit.Page(
            title = "令牌消耗统计",
            sub = "本机 AI 请求用量",
            onBack = { closeIfOpen(act) }
        ) {
            SegRow(mode = mode) { idx ->
                if (idx != mode) {
                    MODE = idx
                    OFFSET = 0
                    mode = idx
                    offset = 0
                }
            }
            if (mode != 2) {
                DateRow(mode = mode, offset = offset) { next ->
                    OFFSET = next
                    offset = next
                }
            }
            OverviewCard(p = p, mode = mode, offset = offset, revision = revision) { editing = true }
            MetricsGrid(p = p, mode = mode, offset = offset, revision = revision)
        }

        if (editing) {
            var text by remember { mutableStateOf(String.format(Locale.US, "%.2f", price(p))) }
            DhForm.Alert(
                title = "单价（元 / 百万 token）",
                onDismiss = { editing = false },
                posText = "保存",
                negText = "取消",
                onPos = {
                    try {
                        var f = text.trim().toFloat()
                        if (f < 0f) {
                            f = 0f
                        }
                        p.edit().putFloat(K_PRICE, f).apply()
                    } catch (ignored: Throwable) {
                    }
                    revision++
                }
            ) {
                DhForm.Input(
                    value = text,
                    onValueChange = { text = it },
                    hint = "0.00",
                    keyboard = KeyboardType.Decimal
                )
            }
        }
    }

    /** 分段：每日 / 每周 / 累计。选中实色、未选描边（与 View 版 primaryChip / outlineChip 同口径）。 */
    @Composable
    private fun SegRow(mode: Int, onPick: (Int) -> Unit) {
        val names = arrayOf("每日", "每周", "累计")
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in names.indices) {
                if (i == mode) {
                    DhForm.PrimaryChip(text = names[i], onClick = { onPick(i) })
                } else {
                    DhForm.OutlineChip(text = names[i], onClick = { onPick(i) })
                }
                if (i != names.size - 1) {
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }

    /** 日期条：左翻 / 周期名 / 右翻；已经在最近一段时右翻降透明度且不可点。 */
    @Composable
    private fun DateRow(mode: Int, offset: Int, onOffset: (Int) -> Unit) {
        val c = DhTokens.colors
        val canBack = offset > 0
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DhKit.IconButton(
                iconRes = Icons.IC_CHEVRON_LEFT,
                sizeDp = UiKit.FS_ICON,
                color = c.title
            ) { onOffset(offset + 1) }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = periodLabel(mode, offset),
                    color = c.title,
                    fontSize = UiKit.FS_BTN.sp,
                    fontFamily = DhTokens.fontsBold,
                    fontWeight = FontWeight.Bold
                )
            }
            if (canBack) {
                DhKit.IconButton(
                    iconRes = Icons.IC_CHEVRON_RIGHT,
                    sizeDp = UiKit.FS_ICON,
                    color = c.title
                ) { onOffset(offset - 1) }
            } else {
                Box(
                    modifier = Modifier.size(DhKit.HIT.dp).alpha(0.3f),
                    contentAlignment = Alignment.Center
                ) {
                    DhKit.Icon(iconRes = Icons.IC_CHEVRON_RIGHT, sizeDp = UiKit.FS_ICON, color = c.title)
                }
            }
        }
    }

    /** 周期总览大卡：总数 / 费用 / 药丸 + 折线图 + 图下小字。 */
    @Composable
    private fun OverviewCard(
        p: SharedPreferences,
        mode: Int,
        offset: Int,
        revision: Int,
        onEditPrice: () -> Unit
    ) {
        val c = DhTokens.colors
        val st = remember(mode, offset, revision) { stats(p, mode, offset) }
        val unit = remember(revision) { price(p) }
        val inTok = st[0]
        val outTok = st[1]
        val req = st[2]
        val cap = if (mode == 0) "当日总览" else (if (mode == 1) "本周总览" else "累计总览")
        var tip by remember(mode, offset) { mutableStateOf("") }
        val vals = remember(mode, offset, revision) { series(p, mode, offset) }
        val labs = remember(mode, offset, revision) { seriesLabels(mode, offset, vals.size) }

        DhKit.Card {
            Text(
                text = cap,
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = fmt(inTok + outTok) + " Token",
                modifier = Modifier.padding(top = 10.dp),
                color = c.title,
                fontSize = 30.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "输入 " + fmt(inTok) + "　·　输出 " + fmt(outTok),
                modifier = Modifier.padding(top = 2.dp),
                color = c.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            Text(
                text = "费用约 ￥" + money(cost(inTok + outTok, unit)),
                modifier = Modifier.padding(top = 8.dp),
                color = c.title,
                fontSize = UiKit.FS_BTN.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DhForm.Chip(text = "请求 " + req + " 次")
                Spacer(Modifier.width(8.dp))
                DhForm.Chip(text = "￥" + money(unit) + " / 百万", onClick = onEditPrice)
            }
            MiniChart(
                values = vals,
                labels = labs,
                modifier = Modifier.padding(top = 6.dp),
                onPick = { index, value, label ->
                    // 【硬约束】不用浮层短提示：把结果写进图表下那行已有小字里。
                    tip = (if (label == null || label.isEmpty()) "#" + index else label) +
                            "　" + fmt(value.toLong()) + " Token"
                }
            )
            Text(
                text = tip,
                modifier = Modifier.padding(top = 6.dp),
                color = c.sub,
                fontSize = UiKit.FS_TINY.sp,
                fontFamily = DhTokens.fonts
            )
        }
    }

    /** 2x2 指标网格：峰值 / 请求次数 / 缓存命中 / 缓存率。 */
    @Composable
    private fun MetricsGrid(p: SharedPreferences, mode: Int, offset: Int, revision: Int) {
        val st = remember(mode, offset, revision) { stats(p, mode, offset) }
        val inTok = st[0]
        val req = st[2]
        val cache = st[3]
        val peak = st[4]
        val rate = if (inTok > 0) Math.round(cache * 100f / inTok).toString() + "%" else "—"
        DhKit.Card {
            Row(modifier = Modifier.fillMaxWidth()) {
                Cell(k = "峰值 Token", v = fmt(peak), modifier = Modifier.weight(1f))
                Cell(k = "请求次数", v = req.toString() + " 次", modifier = Modifier.weight(1f))
            }
        }
        DhKit.Card {
            Row(modifier = Modifier.fillMaxWidth()) {
                Cell(k = "缓存命中", v = fmt(cache), modifier = Modifier.weight(1f))
                Cell(k = "缓存率", v = rate, modifier = Modifier.weight(1f))
            }
        }
    }

    /** 网格里的一格：上小标签 + 下大数值。← `TokenStat.label/value`。 */
    @Composable
    private fun Cell(k: String, v: String, modifier: Modifier = Modifier) {
        val c = DhTokens.colors
        Column(modifier = modifier) {
            Text(
                text = k,
                color = c.sub,
                fontSize = UiKit.FS_SUB.sp,
                fontFamily = DhTokens.fonts
            )
            Text(
                text = v,
                modifier = Modifier.padding(top = 4.dp),
                color = c.title,
                fontSize = 18.sp,
                fontFamily = DhTokens.fontsBold,
                fontWeight = FontWeight.Bold
            )
        }
    }

    private fun periodLabel(mode: Int, offset: Int): String {
        if (mode == 0) {
            return if (offset == 0) "今天" else monthDay(offset)
        }
        if (mode == 1) {
            if (offset == 0) {
                return "最近 7 天"
            }
            return monthDay(offset * 7 + 6) + " – " + monthDay(offset * 7)
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

    /** 折线数据：每日 = 今日 24 格；每周 = 7 天；累计 = 最近 [KEEP_DAYS] 天。 */
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
}