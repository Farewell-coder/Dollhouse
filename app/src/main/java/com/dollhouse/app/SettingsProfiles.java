package com.dollhouse.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【职责】多套模型配置的数据层：配置数组读写、当前配置切换、输入即存。
 * 【入口】界面侧由 SettingsProfilePanel 调用；本类不持 View，只认 EditText 数组。
 * 【交互】配置数组存 SharedPreferences 的 cfg_profiles，当前配置名存 cfg_cur；
 *         三个活跃值 base_url / api_key / model 与选中配置保持同步。
 * 【坑】程序化改写输入框前必须置 mute，否则监听器会把中间值当用户输入写进偏好。
 */
public final class SettingsProfiles {
    private static final String LOG_TAG = "Dollhouse";

    /** 配置数组的存储键。 */
    public static final String KEY_PROFILES = "cfg_profiles";
    /** 当前选中配置名的存储键。 */
    public static final String KEY_CURRENT = "cfg_cur";
    /** 首个默认配置的名字。 */
    public static final String DEFAULT_NAME = "默认配置";

    /** 静默标志：置位期间输入框的文本变化不写回偏好。 */
    private static boolean mute = false;

    private SettingsProfiles() {
    }

    /* ------------------------------ 配置数组 ------------------------------ */

    /**
     * 读出全部配置；为空或损坏时重建一份默认配置。
     */
    public static JSONArray loadProfiles(Context ctx) {
        purgeLegacyStars(ctx);
        String raw = readPref(ctx, KEY_PROFILES);
        if (raw.length() > 0) {
            try {
                JSONArray arr = new JSONArray(raw);
                if (arr.length() > 0) {
                    return arr;
                }
            } catch (Throwable ignored) {
                Logs.w(LOG_TAG, "ignored", ignored);
            }
        }
        JSONArray arr = new JSONArray();
        arr.put(newProfile(DEFAULT_NAME, readPref(ctx, "base_url"),
                readPref(ctx, "api_key"), readPref(ctx, "model")));
        writePref(ctx, KEY_PROFILES, arr.toString());
        if (readPref(ctx, KEY_CURRENT).length() == 0) {
            writePref(ctx, KEY_CURRENT, DEFAULT_NAME);
        }
        return arr;
    }

    /** 组装一条配置记录：n=名称，u=接口端点，k=密钥，m=模型名。 */
    public static JSONObject newProfile(String name, String url, String key, String model) {
        JSONObject o = new JSONObject();
        try {
            o.put("n", name);
            o.put("u", url);
            o.put("k", key);
            o.put("m", model);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        return o;
    }

    /** 按名称找配置下标；找不到返回 -1。 */
    public static int indexOfName(JSONArray arr, String name) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject profile = arr.optJSONObject(i);
            if (profile != null && name != null && name.equals(profile.optString("n", ""))) {
                return i;
            }
        }
        return -1;
    }

    /* ------------------------------ 收藏模型（命星） ------------------------------ */

    /**
     * 旧版（全局一份）收藏键：v0.0.4 及以前所有配置共用这一个键。
     * 【现状】收藏已改为「每套配置各存一份」，此键如今只剩清理价值 ——
     *   由 purgeLegacyStars() 在首次读到配置时一次性抹掉，任何读取路径都不再碰它。
     * 【为什么不能留】留着它 + 兜底读取，就等于把「别的配置/端点下收藏过的模型」
     *   当初始值喂给当前配置，用户看到的就是「不是这套配置的下级却被列了出来」。
     */
    public static final String KEY_STARS = "model_stars";
    /** 每套配置的收藏键：model_stars|&lt;配置名&gt;。 */
    private static String starKeyOf(String profile) {
        return KEY_STARS + "|" + (profile == null ? "" : profile);
    }

    /** 一次性清理的持久标志键：置位后任何进程都不再清收藏。 */
    private static final String KEY_PURGED = "stars_purged_v004";

    /**
     * 一次性清掉全部「旧收藏」：
     *   ① 旧版全局键 model_stars（v0.0.4 及以前所有配置共用）；
     *   ② 各配置的专属键 model_stars|&lt;配置名&gt;。
     * 【为什么连专属键一起清】用户实报的串台，其脏数据可能已经落进专属键
     *   （旧版兜底读取把全局键的内容喂给当前配置后，只要用户再点过一次收藏，
     *    整份清单就被写进了专属键）。只删全局键会漏掉这一层，所以一刀切干净。
     * 【为什么标志要持久化】若只用内存标志，App 每次重启都会重清一遍，
     *   把用户重启前刚重新收藏的模型又抹掉 —— 那才是真事故。
     *   持久标志保证「一生只清一次」，清完之后收藏功能恢复正常读写。
     * 【时机】任何读配置 / 读收藏的入口都会经过这里，覆盖全部入口。
     * 【代价】升级后所有配置的收藏都视为「空的」，需要用户重新点一次 ★ ——
     *   这是唯一能彻底断开串台的路子：旧数据无法判断原本属于哪套配置。
     * 【范围】只删收藏键（KEY_STARS 及其 | 前缀派生键），配置本身、端点、密钥一律不动。
     */
    private static void purgeLegacyStars(Context ctx) {
        try {
            SharedPreferences p = PetPrefs.get(ctx);
            if (p.getBoolean(KEY_PURGED, false)) {
                return;
            }
            SharedPreferences.Editor ed = p.edit().putBoolean(KEY_PURGED, true);
            for (String k : p.getAll().keySet()) {
                if (k != null && (k.equals(KEY_STARS) || k.startsWith(KEY_STARS + "|"))) {
                    ed.remove(k);
                }
            }
            ed.apply();
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }
    /**
     * 读「当前配置」的收藏模型清单。
     */
    public static JSONArray loadStars(Context ctx) {
        return loadStars(ctx, readPref(ctx, KEY_CURRENT));
    }
    /**
     * 读某套配置的收藏模型清单。损坏一律退回空表。
     * 【归属】收藏按配置存 —— 换一套配置只看得到它自己收藏的模型，
     *   不会再出现「不是这套配置的下级却列了别人的模型」。
     * 【不放兜底】曾经这里会在「专属键为空 + 该配置恰为当前」时回退读旧版全局键，
     *   结果把别的配置/端点下收藏过的模型当初始值喂了进来（用户实报的串台）。
     *   旧键已由 purgeLegacyStars() 清掉，这里只认专属键。
     * 【用途】聊天面板圆环里「展开模型」只列这里的模型；设置页模型浮层里的每行都带一颗可点的星。
     */
    public static JSONArray loadStars(Context ctx, String profile) {
        purgeLegacyStars(ctx);
        String raw = readPref(ctx, starKeyOf(profile));
        if (raw.length() > 0) {
            try {
                JSONArray arr = new JSONArray(raw);
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (s.length() > 0 && !containsStar(out, s)) {
                        out.put(s);
                    }
                }
                return out;
            } catch (Throwable ignored) {
                Logs.w(LOG_TAG, "ignored", ignored);
            }
        }
        return new JSONArray();
    }

    /** 某个模型是否已被「当前配置」收藏。 */
    public static boolean isStarred(Context ctx, String model) {
        return isStarred(ctx, readPref(ctx, KEY_CURRENT), model);
    }
    /** 某个模型是否已被指定配置收藏。 */
    public static boolean isStarred(Context ctx, String profile, String model) {
        String m = model == null ? "" : model.trim();
        return m.length() > 0 && containsStar(loadStars(ctx, profile), m);
    }
    /**
     * 切换某个模型在「当前配置」下的收藏状态，返回切换后的结果（true = 已收藏）。
     */
    public static boolean toggleStar(Context ctx, String model) {
        return toggleStar(ctx, readPref(ctx, KEY_CURRENT), model);
    }
    /**
     * 切换某个模型在某套配置下的收藏状态，返回切换后的结果（true = 已收藏）。
     * 【归属】只写这一套配置的专属键。旧版全局键由 purgeLegacyStars() 一律清掉，
     *   这里不再往回写，也不再需要「顺手清空它」。
     */
    public static boolean toggleStar(Context ctx, String profile, String model) {
        String m = model == null ? "" : model.trim();
        if (m.length() == 0) {
            return false;
        }
        JSONArray arr = loadStars(ctx, profile);
        JSONArray out = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (m.equals(s)) {
                removed = true;
            } else {
                out.put(s);
            }
        }
        if (!removed) {
            out.put(m);
        }
        writePref(ctx, starKeyOf(profile), out.toString());
        return !removed;
    }

    private static boolean containsStar(JSONArray arr, String model) {
        for (int i = 0; i < arr.length(); i++) {
            if (model.equals(arr.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------ 偏好读写 ------------------------------ */

    /** 读一个字符串偏好；异常一律退回空串。 */
    public static String readPref(Context ctx, String key) {
        try {
            String value = PetPrefs.get(ctx).getString(key, "");
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 写一个字符串偏好。 */
    public static void writePref(Context ctx, String key, String value) {
        try {
            PetPrefs.get(ctx).edit().putString(key, value).apply();
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }

    /* ------------------------------ 文本工具 ------------------------------ */

    /** 读取输入框内容，去首尾空白。 */
    public static String textOf(EditText input) {
        CharSequence cs = input == null ? null : input.getText();
        return cs == null ? "" : cs.toString().trim();
    }

    /**
     * 把文本直接替换进输入框的 Editable，绕开 TextWatcher 的 afterTextChanged。
     * 【坑】不要用 EditText.setText()：在部分 ROM 上它的文本回调会晚于静默标志复位，
     *       导致把上一个配置的值当成用户输入写回新配置（切换配置看起来“没反应”）。
     */
    public static void replaceText(EditText input, String text) {
        if (input == null) {
            return;
        }
        String safe = text == null ? "" : text;
        Editable editable = input.getText();
        editable.replace(0, editable.length(), safe);
        input.setSelection(safe.length());
    }

    /* ------------------------------ 同步与切换 ------------------------------ */

    /** 静默标志开关。界面侧需要程序化改写输入框时，必须先 mute 再改，最后在 finally 里 unmute。 */
    public static void setMute(boolean value) {
        mute = value;
    }

    /** 把输入框当前内容写回「当前配置」那一项。 */
    public static void syncInputs(Context ctx, JSONArray profiles, EditText[] inputs) {
        if (inputs == null) {
            return;
        }
        int index = indexOfName(profiles, readPref(ctx, KEY_CURRENT));
        JSONObject profile = index < 0 ? null : profiles.optJSONObject(index);
        if (profile == null) {
            return;
        }
        try {
            profile.put("u", textOf(inputs[0]));
            profile.put("k", textOf(inputs[1]));
            profile.put("m", textOf(inputs[2]));
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        writePref(ctx, KEY_PROFILES, profiles.toString());
    }

    /** 把偏好里的活跃值回写进当前配置项，避免保存被规范化后两边不一致。 */
    public static void syncFromPrefs(Context ctx) {
        JSONArray profiles = loadProfiles(ctx);
        int index = indexOfName(profiles, readPref(ctx, KEY_CURRENT));
        JSONObject profile = index < 0 ? null : profiles.optJSONObject(index);
        if (profile == null) {
            return;
        }
        try {
            profile.put("u", readPref(ctx, "base_url"));
            profile.put("k", readPref(ctx, "api_key"));
            profile.put("m", readPref(ctx, "model"));
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        writePref(ctx, KEY_PROFILES, profiles.toString());
    }

    /**
     * 切到指定配置：先把界面上的值存回旧配置，再把新配置填进输入框并写入活跃值。
     * 【坑】填值走 replaceText（直接改 Editable），不用 setText，原因见 replaceText 注释。
     */
    public static void switchTo(Context ctx, String name, EditText[] inputs, Runnable onSwitched) {
        JSONArray profiles = loadProfiles(ctx);
        syncInputs(ctx, profiles, inputs);
        profiles = loadProfiles(ctx);
        int index = indexOfName(profiles, name);
        if (index < 0) {
            return;
        }
        JSONObject profile = profiles.optJSONObject(index);
        if (profile == null) {
            return;
        }
        writePref(ctx, KEY_CURRENT, name);
        setMute(true);
        try {
            // 【坑】inputs 可能是 null（聊天面板的配置浮层里没有设置页那三个输入框）。
            // 这里必须判空 —— 原来无条件读 inputs[0]，在浮层里点一下就 NPE 崩掉。
            if (inputs != null) {
                replaceText(inputs[0], profile.optString("u", ""));
                replaceText(inputs[1], profile.optString("k", ""));
                replaceText(inputs[2], profile.optString("m", ""));
            }
            writePref(ctx, "base_url", profile.optString("u", ""));
            writePref(ctx, "api_key", profile.optString("k", ""));
            writePref(ctx, "model", profile.optString("m", ""));
        } finally {
            setMute(false);
        }
        if (onSwitched != null) {
            onSwitched.run();
        }
    }

    /**
     * 只改某套配置里的模型名。
     * 【交互】给聊天面板的「配置 → 展开模型清单 → 选一个」用；若改的正是当前配置，
     *        顺手把活跃值 model 也更新，免得下次发请求还用旧模型。
     */
    public static void setProfileModel(Context ctx, String name, String model) {
        JSONArray profiles = loadProfiles(ctx);
        int index = indexOfName(profiles, name);
        JSONObject profile = index < 0 ? null : profiles.optJSONObject(index);
        if (profile == null) {
            return;
        }
        try {
            profile.put("m", model == null ? "" : model);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        writePref(ctx, KEY_PROFILES, profiles.toString());
        if (name.equals(readPref(ctx, KEY_CURRENT))) {
            writePref(ctx, "model", model == null ? "" : model);
        }
    }

    /** 打开设置页时把当前配置已存的值填进输入框（静默，不触发输入即存）。 */
    public static void fillFromCurrent(Context ctx, EditText[] inputs) {
        JSONArray profiles = loadProfiles(ctx);
        int index = indexOfName(profiles, readPref(ctx, KEY_CURRENT));
        JSONObject profile = index < 0 ? null : profiles.optJSONObject(index);
        if (profile == null) {
            return;
        }
        setMute(true);
        try {
            replaceText(inputs[0], profile.optString("u", ""));
            replaceText(inputs[1], profile.optString("k", ""));
            replaceText(inputs[2], profile.optString("m", ""));
        } finally {
            setMute(false);
        }
    }

    /** 输入即存：值一变就写回偏好与当前配置项，不需要「保存」按钮。 */
    public static void bindAutoSave(final Context ctx, EditText input, final String key,
                                    final EditText[] inputs) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (mute) {
                    return;
                }
                writePref(ctx, key, s == null ? "" : s.toString().trim());
                syncInputs(ctx, loadProfiles(ctx), inputs);
            }
        });
    }
}
