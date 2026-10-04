package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

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
public final class TokenStat {
    private static final String LOG_TAG = "Dollhouse";

    private static final String PREF = "feiyu_pet";
    private static final String K_DAY = "tk_day";
    private static final String K_DAY_IN = "tk_day_in";
    private static final String K_DAY_OUT = "tk_day_out";
    private static final String K_DAY_REQ = "tk_day_req";
    private static final String K_DAY_PEAK = "tk_day_peak";
    private static final String K_DAY_CACHE = "tk_day_cache";
    private static final String K_ALL_IN = "tk_all_in";
    private static final String K_ALL_OUT = "tk_all_out";
    private static final String K_ALL_REQ = "tk_all_req";
    private static final String K_ALL_CACHE = "tk_all_cache";
    /** 【v2.10.0】按天历史：{"2026-10-04":{"in":1,"out":2,"req":1,"cache":0,"peak":3}}，最多 KEEP_DAYS 天。 */
    private static final String K_DAYS = "tk_days";
    /** 【v2.10.0】今日 24 个整点的 token 数（JSON 数组，跨天清空），给「每日」折线图用。 */
    private static final String K_HOURS = "tk_hours";
    /** 【v2.10.0】单价：元 / 百万 token，默认 1.0（可点药丸编辑）。 */
    private static final String K_PRICE = "tk_price";

    private static final int KEEP_DAYS = 31;
    private static final float DEF_PRICE = 1.0f;
    private static final float MILLION = 1000000f;

    public static final String TAG_PAGE = "feiyu_token_page";

    /** 当前分段：0 = 每日，1 = 每周，2 = 累计。静态保存，因为整页重建。 */
    private static int MODE = 0;
    /** 往前翻的偏移：每日 = 天，每周 = 周，累计 = 不用。 */
    private static int OFFSET = 0;

    private TokenStat() {
    }

    /* ----------------------------- 记录 ----------------------------- */

    /** 由 ChatPanel 的最终回调调用；msg 为 null（出错）时忽略。 */
    public static void recordFrom(Context ctx, JSONObject msg) {
        try {
            if (ctx == null || msg == null) {
                return;
            }
            // 【v2.10.0】优先读 DeepSeekClient 挂上来的顶层 usage（键 __usage）；
            //  旧路径（message 自带 usage / data.usage）保留，兼容别家返回结构。
            JSONObject u = msg.optJSONObject("__usage");
            if (u == null) {
                u = msg.optJSONObject("usage");
            }
            if (u == null && msg.optJSONObject("data") != null) {
                u = msg.optJSONObject("data").optJSONObject("usage");
            }
            if (u == null) {
                return;
            }
            int in = u.optInt("prompt_tokens", 0);
            int out = u.optInt("completion_tokens", 0);
            if (in == 0 && out == 0) {
                int t = u.optInt("total_tokens", 0);
                if (t == 0) {
                    return;
                }
                in = t;
            }
            int cache = 0;
            JSONObject det = u.optJSONObject("prompt_tokens_details");
            if (det != null) {
                cache = det.optInt("cached_tokens", 0);
            }
            if (cache == 0) {
                cache = u.optInt("prompt_cache_hit_tokens", 0);
            }
            add(ctx, in, out, cache);
        } catch (Throwable ignored) {
            Log.w(LOG_TAG, "ignored", ignored);
        }
    }

    private static synchronized void add(Context ctx, int in, int out, int cache) {
        SharedPreferences p = prefs(ctx);
        rollDay(p);
        String today = todayKey();

        // ---- 按天历史：今天的桶累加，顺带记单次峰值 ----
        JSONObject days = readDays(p);
        JSONObject d = days.optJSONObject(today);
        if (d == null) {
            d = new JSONObject();
        }
        try {
            d.put("in", d.optInt("in", 0) + in);
            d.put("out", d.optInt("out", 0) + out);
            d.put("req", d.optInt("req", 0) + 1);
            d.put("cache", d.optInt("cache", 0) + cache);
            d.put("peak", Math.max(d.optInt("peak", 0), in + out));
            days.put(today, d);
        } catch (Throwable ignored) {
        }
        days = trimDays(days);

        // ---- 按小时：今天的第 h 格累加（跨天时 rollDay 已把整串清掉） ----
        int[] hours = readHours(p);
        int hh = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hh >= 0 && hh < 24) {
            hours[hh] += in + out;
        }

        p.edit()
                .putInt(K_DAY_IN, p.getInt(K_DAY_IN, 0) + in)
                .putInt(K_DAY_OUT, p.getInt(K_DAY_OUT, 0) + out)
                .putInt(K_DAY_REQ, p.getInt(K_DAY_REQ, 0) + 1)
                .putInt(K_DAY_CACHE, p.getInt(K_DAY_CACHE, 0) + cache)
                .putInt(K_DAY_PEAK, Math.max(p.getInt(K_DAY_PEAK, 0), in + out))
                .putInt(K_ALL_IN, p.getInt(K_ALL_IN, 0) + in)
                .putInt(K_ALL_OUT, p.getInt(K_ALL_OUT, 0) + out)
                .putInt(K_ALL_REQ, p.getInt(K_ALL_REQ, 0) + 1)
                .putInt(K_ALL_CACHE, p.getInt(K_ALL_CACHE, 0) + cache)
                .putString(K_DAYS, days.toString())
                .putString(K_HOURS, hoursToJson(hours))
                .apply();
    }

    /** 跨自然日就把今日计数与小时桶清零（按天历史不动，那是长期账）。 */
    private static void rollDay(SharedPreferences p) {
        String today = todayKey();
        if (!today.equals(p.getString(K_DAY, ""))) {
            p.edit()
                    .putString(K_DAY, today)
                    .putInt(K_DAY_IN, 0)
                    .putInt(K_DAY_OUT, 0)
                    .putInt(K_DAY_REQ, 0)
                    .putInt(K_DAY_PEAK, 0)
                    .putInt(K_DAY_CACHE, 0)
                    .putString(K_HOURS, "")
                    .apply();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /* ----------------------------- 存储工具 ----------------------------- */

    private static String todayKey() {
        return dayKey(0);
    }

    /** offset 天前的日期串（offset = 0 即今天）。 */
    private static String dayKey(int offset) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -offset);
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static String monthDay(int offset) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -offset);
        return String.format(Locale.US, "%d/%d", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static JSONObject readDays(SharedPreferences p) {
        try {
            String s = p.getString(K_DAYS, "");
            if (s == null || s.length() == 0) {
                return new JSONObject();
            }
            return new JSONObject(s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static int[] readHours(SharedPreferences p) {
        int[] h = new int[24];
        try {
            String s = p.getString(K_HOURS, "");
            if (s == null || s.length() == 0) {
                return h;
            }
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < 24 && i < a.length(); i++) {
                h[i] = a.optInt(i, 0);
            }
        } catch (Throwable ignored) {
        }
        return h;
    }

    private static String hoursToJson(int[] h) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < 24; i++) {
            a.put(h[i]);
        }
        return a.toString();
    }

    /** 只保留最近 KEEP_DAYS 天（key 是 yyyy-MM-dd，可直接字典序排）。 */
    private static JSONObject trimDays(JSONObject days) {
        try {
            if (days.length() <= KEEP_DAYS) {
                return days;
            }
            List<String> keys = new ArrayList<String>();
            Iterator<String> it = days.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            Collections.sort(keys);
            while (keys.size() > KEEP_DAYS) {
                days.remove(keys.remove(0));
            }
        } catch (Throwable ignored) {
        }
        return days;
    }

    private static float price(SharedPreferences p) {
        float f = p.getFloat(K_PRICE, DEF_PRICE);
        return f < 0f ? DEF_PRICE : f;
    }

    /** 费用估算：tokens / 百万 × 单价。 */
    private static float cost(long tokens, float price) {
        return tokens / MILLION * price;
    }

    /* ----------------------------- 统计页 ----------------------------- */

    /** 返回键用：统计页开着就关掉并返回 true。 */
    public static boolean closeIfOpen(Context ctx) {
        try {
            Activity act = findActivity(ctx);
            if (act == null) {
                return false;
            }
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null) {
                return false;
            }
            View old = content.findViewWithTag(TAG_PAGE);
            if (old == null) {
                return false;
            }
            content.removeView(old);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 由聊天页顶栏圆圈 / 设置页入口行调用。offset 归零，永远从「今天」开始看。 */
    public static void open(Context ctx) {
        try {
            OFFSET = 0;
            show(ctx);
        } catch (Throwable ignored) {
            Log.w(LOG_TAG, "ignored", ignored);
        }
    }

    /** 整页重建（切分段 / 翻日期都走这里，不重建 Activity）。 */
    private static void show(Context ctx) {
        Activity act = findActivity(ctx);
        if (act == null) {
            return;
        }
        ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        View old = content.findViewWithTag(TAG_PAGE);
        if (old != null) {
            content.removeView(old);
        }
        View page = buildPage(act, content);
        page.setTag(TAG_PAGE);
        content.addView(page, new ViewGroup.LayoutParams(-1, -1));
    }

    private static Activity findActivity(Context ctx) {
        Context c = ctx;
        for (int i = 0; i < 8 && c != null; i++) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            if (c instanceof ContextWrapper) {
                c = ((ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    private static View buildPage(final Activity act, final ViewGroup content) {
        Context ctx = act;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(UiKit.BG);
        int pad = dp(ctx, 16);
        box.setPadding(pad, dp(ctx, 10), pad, dp(ctx, 20));

        // 顶栏：统一走 UiKit.topBar
        LinearLayout bar = UiKit.topBar(ctx, "令牌消耗统计", "本机 AI 请求用量", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeIfOpen(act);
            }
        });
        box.addView(bar);

        SharedPreferences p = prefs(ctx);
        rollDay(p);

        // 分段：每日 / 每周 / 累计
        box.addView(segRow(ctx));

        // 日期条：累计模式没有时间维度，不显示
        if (MODE != 2) {
            box.addView(dateRow(ctx));
        }

        long[] st = stats(p, MODE, OFFSET);
        long in = st[0];
        long out = st[1];
        long req = st[2];
        long cache = st[3];
        long peak = st[4];
        float unit = price(p);

        // ---- 周期总览大卡 ----
        LinearLayout over = card(ctx);
        TextView cap = new TextView(ctx);
        cap.setText(MODE == 0 ? "当日总览" : (MODE == 1 ? "本周总览" : "累计总览"));
        cap.setTextSize(UiKit.FS_BTN);
        cap.setTextColor(UiKit.TITLE);
        cap.setTypeface(Typeface.DEFAULT_BOLD);
        over.addView(cap);

        TextView big = new TextView(ctx);
        big.setText(fmt(in + out) + " Token");
        big.setTextSize(30.0f);
        big.setTextColor(UiKit.TITLE);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setPadding(0, dp(ctx, 10), 0, 0);
        over.addView(big);

        TextView sub = new TextView(ctx);
        sub.setText("输入 " + fmt(in) + "　·　输出 " + fmt(out));
        sub.setTextSize(UiKit.FS_SUB);
        sub.setTextColor(UiKit.SUB);
        sub.setPadding(0, dp(ctx, 2), 0, 0);
        over.addView(sub);

        TextView fee = new TextView(ctx);
        fee.setText("费用约 ￥" + money(cost(in + out, unit)));
        fee.setTextSize(UiKit.FS_BTN);
        fee.setTextColor(UiKit.TITLE);
        fee.setTypeface(Typeface.DEFAULT_BOLD);
        fee.setPadding(0, dp(ctx, 8), 0, 0);
        over.addView(fee);

        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(ctx, 8), 0, 0);
        chips.addView(pill(ctx, "请求 " + req + " 次", null));
        chips.addView(pill(ctx, "￥" + money(unit) + " / 百万", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editPrice(v.getContext());
            }
        }));
        over.addView(chips);

        // ---- 折线图 ----
        TextView tip = new TextView(ctx);
        tip.setTextSize(UiKit.FS_TINY);
        tip.setTextColor(UiKit.SUB);
        tip.setPadding(0, dp(ctx, 6), 0, 0);

        MiniChart chart = new MiniChart(ctx);
        final float[] vals = series(p, MODE, OFFSET);
        final String[] labs = seriesLabels(MODE, OFFSET, vals.length);
        chart.setPoints(vals, labs);
        final TextView tipRef = tip;
        chart.setOnPick(new MiniChart.OnPickListener() {
            @Override
            public void onPick(int index, float value, String label) {
                // 【硬约束】不用浮层短提示：把结果写进图表下那行已有小字里。
                tipRef.setText((label == null || label.length() == 0 ? "#" + index : label)
                        + "　" + fmt((long) value) + " Token");
            }
        });
        over.addView(chart, new LinearLayout.LayoutParams(-1, -2));
        over.addView(tip);
        box.addView(over);

        // ---- 2×2 指标网格 ----
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(ctx, 10);
        grid.setLayoutParams(glp);
        grid.addView(row(ctx, "峰值 Token", fmt(peak), "请求次数", req + " 次"));
        grid.addView(row(ctx, "缓存命中", fmt(cache),
                "缓存率", in > 0 ? Math.round(cache * 100f / in) + "%" : "—"));
        box.addView(grid);

        // ---- 说明 ----
        TextView note = new TextView(ctx);
        note.setTextSize(UiKit.FS_TINY);
        note.setTextColor(UiKit.SUB);
        note.setLineSpacing(dp(ctx, 3), 1.0f);
        note.setPadding(dp(ctx, 2), dp(ctx, 14), dp(ctx, 2), 0);
        note.setText("只统计本机发起的 AI 请求，数据来自接口返回的 usage 字段；接口不返回就不计入，不做估算。\n"
                + "费用按「单价 × 用量」估算，点上面那枚药丸可以改单价，默认 ￥1.00 / 百万 token。\n"
                + "按天历史保留最近 " + KEEP_DAYS + " 天；累计计数只增不减，数据仅保存在这台设备上。");
        box.addView(note);

        ScrollView sc = new ScrollView(ctx);
        sc.setBackgroundColor(UiKit.BG);
        sc.addView(box, new ViewGroup.LayoutParams(-1, -2));
        return sc;
    }

    /* ----------------------------- 页面零件 ----------------------------- */

    private static LinearLayout segRow(final Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 10);
        row.setLayoutParams(lp);
        String[] names = {"每日", "每周", "累计"};
        for (int i = 0; i < names.length; i++) {
            TextView t = (i == MODE) ? UiKit.primaryChip(ctx, names[i]) : UiKit.outlineChip(ctx, names[i]);
            final int idx = i;
            t.setClickable(true);
            UiKit.press(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (MODE == idx) {
                        return;
                    }
                    MODE = idx;
                    OFFSET = 0;
                    show(v.getContext());
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.rightMargin = dp(ctx, 8);
            t.setLayoutParams(clp);
            row.addView(t);
        }
        return row;
    }

    private static LinearLayout dateRow(final Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 10);
        row.setLayoutParams(lp);

        TextView prev = UiKit.iconBtn(ctx, "‹", UiKit.FS_ICON, UiKit.TITLE);
        prev.setClickable(true);
        UiKit.press(prev);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OFFSET++;
                show(v.getContext());
            }
        });
        row.addView(prev, new LinearLayout.LayoutParams(-2, -2));

        TextView label = new TextView(ctx);
        label.setText(periodLabel());
        label.setTextSize(UiKit.FS_BTN);
        label.setTextColor(UiKit.TITLE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView next = UiKit.iconBtn(ctx, "›", UiKit.FS_ICON, UiKit.TITLE);
        next.setClickable(true);
        UiKit.press(next);
        boolean canBack = OFFSET > 0;
        next.setEnabled(canBack);
        next.setAlpha(canBack ? 1.0f : 0.3f);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (OFFSET <= 0) {
                    return;
                }
                OFFSET--;
                show(v.getContext());
            }
        });
        row.addView(next, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static String periodLabel() {
        if (MODE == 0) {
            return OFFSET == 0 ? "今天" : monthDay(OFFSET);
        }
        if (MODE == 1) {
            if (OFFSET == 0) {
                return "最近 7 天";
            }
            return monthDay(OFFSET * 7 + 6) + " – " + monthDay(OFFSET * 7);
        }
        return "全部";
    }

    /** 取某个分段的汇总：{in, out, req, cache, peak}。 */
    private static long[] stats(SharedPreferences p, int mode, int offset) {
        long[] r = new long[5];
        if (mode == 0) {
            JSONObject d = readDays(p).optJSONObject(dayKey(offset));
            if (d != null) {
                r[0] = d.optInt("in", 0);
                r[1] = d.optInt("out", 0);
                r[2] = d.optInt("req", 0);
                r[3] = d.optInt("cache", 0);
                r[4] = d.optInt("peak", 0);
            }
            return r;
        }
        if (mode == 1) {
            JSONObject days = readDays(p);
            int base = offset * 7;
            for (int i = 0; i < 7; i++) {
                JSONObject d = days.optJSONObject(dayKey(base + i));
                if (d == null) {
                    continue;
                }
                r[0] += d.optInt("in", 0);
                r[1] += d.optInt("out", 0);
                r[2] += d.optInt("req", 0);
                r[3] += d.optInt("cache", 0);
                r[4] = Math.max(r[4], d.optInt("peak", 0));
            }
            return r;
        }
        r[0] = p.getInt(K_ALL_IN, 0);
        r[1] = p.getInt(K_ALL_OUT, 0);
        r[2] = p.getInt(K_ALL_REQ, 0);
        r[3] = p.getInt(K_ALL_CACHE, 0);
        r[4] = p.getInt(K_DAY_PEAK, 0);
        JSONObject days = readDays(p);
        Iterator<String> it = days.keys();
        while (it.hasNext()) {
            JSONObject d = days.optJSONObject(it.next());
            if (d != null) {
                r[4] = Math.max(r[4], d.optInt("peak", 0));
            }
        }
        return r;
    }

    /** 折线数据：每日 = 今日 24 格；每周 = 7 天；累计 = 最近 31 天。 */
    private static float[] series(SharedPreferences p, int mode, int offset) {
        if (mode == 0) {
            int[] h = readHours(p);
            // 翻到过去的某天时今日小时桶不适用，改为「该天 0 格」而不是错画今天的数据。
            if (offset != 0) {
                return new float[0];
            }
            float[] v = new float[24];
            for (int i = 0; i < 24; i++) {
                v[i] = h[i];
            }
            return v;
        }
        int n = mode == 1 ? 7 : KEEP_DAYS;
        int base = mode == 1 ? offset * 7 : 0;
        JSONObject days = readDays(p);
        float[] v = new float[n];
        // 累计模式按「今天往前数 n 天」排，保证右端永远是今天。
        for (int i = 0; i < n; i++) {
            int back = mode == 1 ? (base + (n - 1 - i)) : (n - 1 - i);
            JSONObject d = days.optJSONObject(dayKey(back));
            v[i] = d == null ? 0f : (d.optInt("in", 0) + d.optInt("out", 0));
        }
        return v;
    }

    private static String[] seriesLabels(int mode, int offset, int len) {
        if (len <= 0) {
            return null;
        }
        String[] s = new String[len];
        if (mode == 0) {
            for (int i = 0; i < len; i++) {
                s[i] = i + " 时";
            }
            return s;
        }
        if (mode == 1) {
            int base = offset * 7;
            for (int i = 0; i < len; i++) {
                s[i] = monthDay(base + (len - 1 - i));
            }
            return s;
        }
        for (int i = 0; i < len; i++) {
            s[i] = monthDay(len - 1 - i);
        }
        return s;
    }

    /* ----------------------------- 单价编辑 ----------------------------- */

    private static void editPrice(final Context ctx) {
        Activity act = findActivity(ctx);
        if (act == null) {
            return;
        }
        final SharedPreferences p = prefs(act);
        final EditText e = new EditText(act);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(String.format(Locale.US, "%.2f", price(p)));
        UiKit.field(e, act);
        UiKit.showDialog(act, "单价（元 / 百万 token）", e, "保存",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            float f = Float.parseFloat(e.getText().toString().trim());
                            if (f < 0f) {
                                f = 0f;
                            }
                            p.edit().putFloat(K_PRICE, f).apply();
                        } catch (Throwable ignored) {
                        }
                        show(ctx);
                    }
                },
                "取消", null);
    }

    /* ----------------------------- 小控件 ----------------------------- */

    private static LinearLayout row(Context ctx, String k1, String v1, String k2, String v2) {
        LinearLayout card = card(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(label(ctx, k1));
        left.addView(value(ctx, v1));
        card.addView(left, new LinearLayout.LayoutParams(0, -2, 1.0f));

        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(label(ctx, k2));
        right.addView(value(ctx, v2));
        card.addView(right, new LinearLayout.LayoutParams(0, -2, 1.0f));
        return card;
    }

    private static TextView label(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(UiKit.FS_SUB);
        t.setTextColor(UiKit.SUB);
        return t;
    }

    private static TextView value(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(18.0f);
        t.setTextColor(UiKit.TITLE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(ctx, 4), 0, 0);
        return t;
    }

    private static TextView pill(Context ctx, String s, View.OnClickListener click) {
        TextView t = UiKit.chip(ctx, s, UiKit.CHAT_BUBBLE_USER, UiKit.CHAT_CHIP_BG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(ctx, 8);
        t.setLayoutParams(lp);
        if (click != null) {
            t.setClickable(true);
            UiKit.press(t);
            t.setOnClickListener(click);
        }
        return t;
    }

    private static LinearLayout card(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(UiKit.cardBg(ctx));
        c.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 10);
        c.setLayoutParams(lp);
        return c;
    }

    /** 1234 -> 1,234 */
    private static String fmt(long n) {
        String s = Long.toString(Math.max(n, 0L));
        StringBuilder b = new StringBuilder();
        int c = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            b.append(s.charAt(i));
            c++;
            if (c % 3 == 0 && i > 0) {
                b.append(',');
            }
        }
        return b.reverse().toString();
    }

    /** 金额保留两位。 */
    private static String money(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** 尺寸换算：统一走 UiKit，避免多处重复实现。 */
    private static int dp(Context ctx, int v) {
        return UiKit.dp(ctx, v);
    }
}
