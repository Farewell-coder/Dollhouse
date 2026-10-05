package com.dollhouse.app;

import android.app.Activity;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 【职责】整体 UI 主题：四档模式（随系统 / 白色 / 暗色 / 纯黑）+ 莫奈主题色（按壁纸动态取色）。
 *
 * 【入口】各 Activity 在 setContentView 之前调 apply()；设置页切档位后调 setMode()/setMonet() 再重建界面。
 *
 * 【交互】配色最终落到 UiKit 的可变颜色字段上；本类不持有任何 View，也不碰布局。
 *
 * 【坑】UiKit 的颜色字段被全工程数百处引用，只能在切换时改字段值，绝不能改引用点。
 *       壁纸取色在个别 OEM 上可能拿不到（getDrawable 抛异常 / 返回 null），一律回退内置配色，
 *       并把解析出的色相按「壁纸 id」缓存进偏好，避免每次启动都重新解码整张壁纸。
 */
public final class ThemeManager {
    /** 随系统（跟随系统深色模式开关）。 */
    public static final int MODE_SYSTEM = 0;
    /** 强制白色。 */
    public static final int MODE_LIGHT = 1;
    /** 强制暗色。 */
    public static final int MODE_DARK = 2;
    /** 纯黑（OLED 省电）。 */
    public static final int MODE_BLACK = 3;
    /** 档位显示名，顺序与 MODE_* 一致。 */
    public static final String[] MODE_NAMES = {
            "\u968f\u7cfb\u7edf", "\u767d\u8272", "\u6697\u8272", "\u7eaf\u9ed1"
    };

    private static final String LOG_TAG = "Dollhouse";

    // ---- 调色板下标：顺序与 apply() 里的赋值顺序严格一致 ----
    private static final int I_ACC = 0;
    private static final int I_ACC2 = 1;
    private static final int I_CARD = 2;
    private static final int I_BG = 3;
    private static final int I_TITLE = 4;
    private static final int I_SUB = 5;
    private static final int I_LINE = 6;
    private static final int I_OPTION = 7;
    private static final int I_SOFT = 8;
    private static final int I_FIELD = 9;
    private static final int I_OK = 10;
    private static final int I_ERR = 11;
    private static final int I_ON_ACC = 12;
    private static final int I_CHAT_BUBBLE_USER = 13;
    private static final int I_CHAT_BORDER = 14;
    private static final int I_CHAT_CHIP_BG = 15;
    private static final int I_CHAT_CHIP_FG = 16;
    private static final int I_CHAT_CHIP_ON = 17;
    private static final int I_CHAT_CHIP_OFF = 18;
    private static final int I_CHAT_CHIP_MUTE = 19;
    private static final int I_CHAT_ACTION_BG = 20;
    private static final int I_HINT_FG = 21;
    private static final int I_HINT_BG = 22;
    private static final int I_SWITCH_OFF = 23;
    private static final int I_EMOTE_1 = 24;
    private static final int I_EMOTE_2 = 25;
    private static final int I_EMOTE_3 = 26;
    private static final int I_STROKE = 27;
    /** 弹层遮罩色（抽屉 / 面板背后的压暗层）。 */
    private static final int I_SCRIM = 28;
    private static final int PAL_SIZE = 29;

    /** 白色档内置调色板（与改造前 UiKit 的取值一致，保证默认观感不变）。 */
    private static final int[] LIGHT = {
            0xFF6B4EE6, 0xFF8B6EF7, 0xFFFFFFFF, 0xFFF1F2F7, 0xFF22315B, 0xFF5A6B99,
            0xFFE6E7EF, 0xFFF6F4FF, 0xFFEAECF4, 0xFFF4F2FD, 0xFF1B8A3A, 0xFFB3261E,
            0xFFFFFFFF, 0xFF4C6FDE, 0xFFC9D4EE, 0xFFE4EAF8, 0xFF3A5BC7, 0xFFDCE6FF,
            0xFFEDF1FA, 0xFF7A88B0, 0xFFE6EBF8, 0xFF8A5A00, 0xFFFFF4D6, 0xFFC9CEDD,
            0xFFF2603C, 0xFFE8608F, 0xFF5A7BD8, 0x1422315B, 0x8A000000
    };

    /** 暗色档内置调色板。 */
    private static final int[] DARK = {
            0xFF9B85F0, 0xFFB39DFF, 0xFF1E1F26, 0xFF121317, 0xFFE8EAF2, 0xFF9BA3BC,
            0xFF2E3038, 0xFF23242C, 0xFF282A36, 0xFF25263A, 0xFF5FD07E, 0xFFFF6B60,
            0xFF1A1030, 0xFF5C7BE8, 0xFF3A3F52, 0xFF2A2F42, 0xFFA9BCF5, 0xFF3A4472,
            0xFF24273A, 0xFF8A93B0, 0xFF2B3048, 0xFFF0C674, 0xFF3A3320, 0xFF3E4250,
            0xFFFF8A5C, 0xFFFF8FB8, 0xFF8AA6FF, 0x1AFFFFFF, 0xA6000000
    };

    /** 纯黑档内置调色板：卡片与页面同为纯黑，靠描边与行底色分层。 */
    private static final int[] BLACK = {
            0xFF9B85F0, 0xFFB39DFF, 0xFF000000, 0xFF000000, 0xFFEDEDF2, 0xFF9A9AA5,
            0xFF303030, 0xFF0D0D0D, 0xFF151515, 0xFF1A1626, 0xFF5FD07E, 0xFFFF6B60,
            0xFF1A1030, 0xFF5C7BE8, 0xFF333333, 0xFF1A1A1A, 0xFFA9BCF5, 0xFF2E2E3E,
            0xFF141414, 0xFF8A93B0, 0xFF1C1C28, 0xFFF0C674, 0xFF2A2416, 0xFF3A3A3A,
            0xFFFF8A5C, 0xFFFF8FB8, 0xFF8AA6FF, 0x26FFFFFF, 0xB3000000
    };

    private ThemeManager() {
    }

    /** 当前主题档位；非法值一律按「随系统」。 */
    public static int mode(Context c) {
        try {
            int m = PetPrefs.themeMode(c);
            return (m < MODE_SYSTEM || m > MODE_BLACK) ? MODE_SYSTEM : m;
        } catch (Throwable ignored) {
            return MODE_SYSTEM;
        }
    }

    public static void setMode(Context c, int m) {
        PetPrefs.setThemeMode(c, m);
    }

    public static boolean monet(Context c) {
        try {
            return PetPrefs.themeMonet(c);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setMonet(Context c, boolean on) {
        PetPrefs.setThemeMonet(c, on);
    }

    /** 当前是否按深色渲染（纯黑也算深色）。 */
    public static boolean isDark(Context c) {
        int m = mode(c);
        if (m == MODE_LIGHT) {
            return false;
        }
        if (m == MODE_DARK || m == MODE_BLACK) {
            return true;
        }
        try {
            int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return ui == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 对话框样式：深色下用深色底，避免暗色主题里弹出白框。 */
    public static int dialogTheme(Context c) {
        return isDark(c) ? R.style.DollhouseDialogDark : R.style.DollhouseDialog;
    }

    /** 把当前主题写进 UiKit 的颜色字段。任何异常都不允许影响启动。 */
    public static void apply(Context c) {
        try {
            if (c == null) {
                return;
            }
            int m = mode(c);
            boolean dark = isDark(c);
            boolean black = m == MODE_BLACK;
            int[] p = black ? BLACK : (dark ? DARK : LIGHT);
            if (monet(c)) {
                // 只读缓存，绝不在主线程解码壁纸：apply() 在 Activity.onCreate 里调用，
                // 一旦同步解码壁纸，冷启动就会卡住甚至被系统判为无响应。
                int[] mp = cachedMonetPalette(c, dark, black);
                if (mp != null) {
                    p = mp;
                }
            }
            UiKit.ACC = p[I_ACC];
            UiKit.ACC2 = p[I_ACC2];
            UiKit.CARD = p[I_CARD];
            UiKit.BG = p[I_BG];
            UiKit.TITLE = p[I_TITLE];
            UiKit.SUB = p[I_SUB];
            UiKit.LINE = p[I_LINE];
            UiKit.OPTION = p[I_OPTION];
            UiKit.SOFT = p[I_SOFT];
            UiKit.FIELD = p[I_FIELD];
            UiKit.OK = p[I_OK];
            UiKit.ERR = p[I_ERR];
            UiKit.ON_ACC = p[I_ON_ACC];
            UiKit.CHAT_BUBBLE_USER = p[I_CHAT_BUBBLE_USER];
            UiKit.CHAT_BORDER = p[I_CHAT_BORDER];
            UiKit.CHAT_CHIP_BG = p[I_CHAT_CHIP_BG];
            UiKit.CHAT_CHIP_FG = p[I_CHAT_CHIP_FG];
            UiKit.CHAT_CHIP_ON = p[I_CHAT_CHIP_ON];
            UiKit.CHAT_CHIP_OFF = p[I_CHAT_CHIP_OFF];
            UiKit.CHAT_CHIP_MUTE = p[I_CHAT_CHIP_MUTE];
            UiKit.CHAT_ACTION_BG = p[I_CHAT_ACTION_BG];
            UiKit.HINT_FG = p[I_HINT_FG];
            UiKit.HINT_BG = p[I_HINT_BG];
            UiKit.SWITCH_OFF = p[I_SWITCH_OFF];
            UiKit.EMOTE_1 = p[I_EMOTE_1];
            UiKit.EMOTE_2 = p[I_EMOTE_2];
            UiKit.EMOTE_3 = p[I_EMOTE_3];
            UiKit.STROKE = p[I_STROKE];
            UiKit.SCRIM = p[I_SCRIM];
            // 窗口背景同步成当前主题底色：换主题走 recreate()，重建的那一瞬间会先露出
            // 窗口背景，不刷的话暗色 / 纯黑下会闪一下白。
            Activity act = UiKit.findActivity(c);
            if (act != null && act.getWindow() != null) {
                act.getWindow().setBackgroundDrawable(new ColorDrawable(UiKit.BG));
            }
        } catch (Throwable t) {
            Logs.w(LOG_TAG, "ignored", t);
        }
    }

    /** 取色缓存是否可用（只读缓存，不解码壁纸，可在主线程安全调用）。 */
    public static boolean hasMonetColor(Context c) {
        try {
            return !Float.isNaN(readHueCache(c));
        } catch (Throwable ignored) {
            return false;
        }
    }
    /** 后台取色回调：ok=true 表示拿到了颜色，可以刷新界面；false 表示取不到、应回退内置配色。 */
    public interface HueCallback {
        void onHue(boolean ok);
    }
    /**
     * 后台解析壁纸色相并写入缓存。
     * 主线程只负责「读缓存」，真正的解码全部丢到这里 —— 壁纸是整屏大图，
     * 在主线程解码会让 Activity 冷启动明显卡顿甚至被判无响应。
     */
    public static void monetHueAsync(Context c, final HueCallback cb) {
        final Context app = c == null ? null : c.getApplicationContext();
        if (app == null) {
            if (cb != null) {
                cb.onHue(false);
            }
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = refreshHueCache(app);
                } catch (Throwable t) {
                    Logs.w(LOG_TAG, "ignored", t);
                }
                final boolean result = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) {
                            cb.onHue(result);
                        }
                    }
                });
            }
        }, "dh-monet").start();
    }
    /** 作废取色缓存：壁纸换了或用户重新打开开关时调用，下次后台取色会重新解码。 */
    public static void invalidateHueCache(Context c) {
        try {
            PetPrefs.setThemeMonetWall(c, Integer.MIN_VALUE);
            PetPrefs.setThemeMonetHue10(c, Integer.MIN_VALUE);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
    }
    /** 供 apply() 使用的同步取色：只读缓存，未命中就返回 null 走内置配色。 */
    private static int[] cachedMonetPalette(Context c, boolean dark, boolean black) {
        try {
            float hue = readHueCache(c);
            if (Float.isNaN(hue)) {
                return null;
            }
            int[] p = fromHue(hue, dark, black);
            return p != null && p.length == PAL_SIZE ? p : null;
        } catch (Throwable t) {
            Logs.w(LOG_TAG, "ignored", t);
            return null;
        }
    }
    /** 读缓存：命中返回色相，未命中返回 NaN。不触发任何解码，可在主线程调用。 */
    private static float readHueCache(Context c) {
        try {
            int wallId = WallpaperManager.getInstance(c).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            int savedWall = PetPrefs.themeMonetWall(c);
            int savedHue10 = PetPrefs.themeMonetHue10(c);
            if (savedWall == wallId && savedHue10 != Integer.MIN_VALUE) {
                return savedHue10 / 10.0f;
            }
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        return Float.NaN;
    }
    /** 解码壁纸并把色相写进缓存；成功返回 true。只在后台线程调用。 */
    private static boolean refreshHueCache(Context c) {
        int wallId = Integer.MIN_VALUE;
        try {
            wallId = WallpaperManager.getInstance(c).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        } catch (Throwable ignored) {
            Logs.w(LOG_TAG, "ignored", ignored);
        }
        int savedWall = PetPrefs.themeMonetWall(c);
        int savedHue10 = PetPrefs.themeMonetHue10(c);
        if (savedWall == wallId && savedHue10 != Integer.MIN_VALUE) {
            return true;
        }
        float hue = extractHue(c);
        if (Float.isNaN(hue)) {
            return false;
        }
        PetPrefs.setThemeMonetHue10(c, Math.round(hue * 10.0f));
        PetPrefs.setThemeMonetWall(c, wallId);
        return true;
    }

    /**
     * 取壁纸主色相。优先走系统调色接口 getWallpaperColors()：颜色由系统进程算好，
     * 零权限、不解码大图，是唯一在各家 ROM 上都稳的路子。
     * 拿不到时再退回自己解码缩略图（Android 13+ 那条路要 READ_MEDIA_IMAGES，没授权必失败，只当兜底）。
     */
    private static float extractHue(Context c) {
        float hue = hueFromWallpaperColors(c);
        if (!Float.isNaN(hue)) {
            return hue;
        }
        return extractHueByDecode(c);
    }

    /** 走系统调色接口取主色相；拿不到、或是灰黑近白等无法代表主题色时返回 NaN。 */
    private static float hueFromWallpaperColors(Context c) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                return Float.NaN;
            }
            WallpaperColors wc = WallpaperManager.getInstance(c)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (wc == null || wc.getPrimaryColor() == null) {
                return Float.NaN;
            }
            float[] hsv = new float[3];
            Color.colorToHSV(wc.getPrimaryColor().toArgb(), hsv);
            if (hsv[1] < 0.12f || hsv[2] < 0.10f) {
                return Float.NaN;
            }
            if (hsv[2] > 0.97f && hsv[1] < 0.10f) {
                return Float.NaN;
            }
            return hsv[0];
        } catch (Throwable t) {
            Logs.w(LOG_TAG, "ignored", t);
            return Float.NaN;
        }
    }

    /** 兜底：把壁纸缩略图解码成 64×64，按色相分 12 桶、以「饱和度 × 明度」加权，取最重的桶做圆均值。 */
    private static float extractHueByDecode(Context c) {
        Bitmap small = null;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(c);
            Drawable d = null;
            try {
                d = wm.getDrawable();
            } catch (Throwable ignored) {
                Logs.w(LOG_TAG, "ignored", ignored);
            }
            if (d == null) {
                return Float.NaN;
            }
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = 64;
                h = 64;
            }
            small = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(small);
            cv.scale(64.0f / w, 64.0f / h);
            d.setBounds(0, 0, w, h);
            d.draw(cv);
        } catch (Throwable t) {
            Logs.w(LOG_TAG, "ignored", t);
            if (small != null) {
                small.recycle();
            }
            return Float.NaN;
        }
        try {
            int n = 64 * 64;
            int[] px = new int[n];
            small.getPixels(px, 0, 64, 0, 0, 64, 64);
            double[] sx = new double[12];
            double[] sy = new double[12];
            double[] sw = new double[12];
            float[] hsv = new float[3];
            for (int i = 0; i < n; i++) {
                int p = px[i];
                if ((p >>> 24) < 128) {
                    continue;
                }
                Color.colorToHSV(p, hsv);
                // 跳过灰、黑、近白：它们不能代表主题色。
                if (hsv[1] < 0.18f || hsv[2] < 0.12f) {
                    continue;
                }
                if (hsv[2] > 0.96f && hsv[1] < 0.45f) {
                    continue;
                }
                int b = (int) (hsv[0] / 30.0f);
                if (b < 0) {
                    b = 0;
                } else if (b > 11) {
                    b = 11;
                }
                double rad = hsv[0] * Math.PI / 180.0;
                double wgt = hsv[1] * hsv[2];
                sx[b] += Math.cos(rad) * wgt;
                sy[b] += Math.sin(rad) * wgt;
                sw[b] += wgt;
            }
            int best = -1;
            double bestW = 0.0;
            for (int i = 0; i < 12; i++) {
                if (sw[i] > bestW) {
                    bestW = sw[i];
                    best = i;
                }
            }
            if (best < 0 || bestW < 0.5) {
                return Float.NaN;
            }
            double ang = Math.atan2(sy[best], sx[best]);
            if (ang < 0.0) {
                ang += Math.PI * 2.0;
            }
            return (float) (ang * 180.0 / Math.PI);
        } catch (Throwable t) {
            Logs.w(LOG_TAG, "ignored", t);
            return Float.NaN;
        } finally {
            small.recycle();
        }
    }

    private static int hsv(float hue, float s, float v) {
        return Color.HSVToColor(new float[]{hue, s, v});
    }

    /** WCAG 相对亮度。 */
    private static float relLum(int c) {
        float r = Color.red(c) / 255.0f;
        float g = Color.green(c) / 255.0f;
        float b = Color.blue(c) / 255.0f;
        r = r <= 0.03928f ? r / 12.92f : (float) Math.pow((r + 0.055f) / 1.055f, 2.4);
        g = g <= 0.03928f ? g / 12.92f : (float) Math.pow((g + 0.055f) / 1.055f, 2.4);
        b = b <= 0.03928f ? b / 12.92f : (float) Math.pow((b + 0.055f) / 1.055f, 2.4);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /** WCAG 对比度，1.0 ~ 21.0。 */
    private static float contrast(int a, int b) {
        float la = relLum(a);
        float lb = relLum(b);
        float hi = Math.max(la, lb);
        float lo = Math.min(la, lb);
        return (hi + 0.05f) / (lo + 0.05f);
    }

    /**
     * 把同色相的颜色压暗到「在 bg 上读得清」。
     * 派生配色是按色相算的，而不同色相的亮度天差地别（黄色天生比紫色亮得多），
     * 同一个明度参数在黄/青/绿上会糊成一片。这里按对比度实测回调明度，直到达标。
     */
    private static int inkOn(float hue, float s, float v, int bg) {
        float vv = v;
        for (int i = 0; i < 30; i++) {
            int c = hsv(hue, s, vv);
            if (contrast(c, bg) >= 4.6f) {
                return c;
            }
            vv -= 0.03f;
        }
        return hsv(hue, s, 0.06f);
    }

    /** 由主色相派生整套配色；明度/饱和度逐项夹紧，保证亮底暗底上的字都读得清。 */
    private static int[] fromHue(float hue, boolean dark, boolean black) {
        if (dark) {
            return new int[]{
                    hsv(hue, 0.55f, 0.92f),
                    hsv(hue, 0.45f, 1.00f),
                    black ? 0xFF000000 : hsv(hue, 0.10f, 0.13f),
                    black ? 0xFF000000 : hsv(hue, 0.12f, 0.07f),
                    hsv(hue, 0.10f, 0.92f),
                    hsv(hue, 0.10f, 0.66f),
                    hsv(hue, 0.10f, 0.20f),
                    black ? 0xFF0D0D0D : hsv(hue, 0.12f, 0.16f),
                    black ? 0xFF101010 : hsv(hue, 0.10f, 0.15f),
                    hsv(hue, 0.16f, 0.18f),
                    0xFF5FD07E,
                    0xFFFF6B60,
                    hsv(hue, 0.55f, 0.14f),
                    hsv(hue, 0.45f, 0.85f),
                    hsv(hue, 0.14f, 0.26f),
                    hsv(hue, 0.18f, 0.20f),
                    hsv(hue, 0.35f, 0.85f),
                    hsv(hue, 0.30f, 0.30f),
                    hsv(hue, 0.12f, 0.16f),
                    hsv(hue, 0.10f, 0.60f),
                    hsv(hue, 0.20f, 0.22f),
                    0xFFF0C674,
                    hsv(hue, 0.30f, 0.22f),
                    hsv(hue, 0.08f, 0.35f),
                    0xFFFF8A5C, 0xFFFF8FB8, 0xFF8AA6FF,
                    black ? 0x26FFFFFF : 0x1AFFFFFF
            };
        }
        // 浅色档底色近白，字色必须压暗到对比度达标 —— 否则黄/青/绿壁纸下会白字白底、看不清。
        // 强调色（ACC/ACC2/气泡）要压暗到白字可读；正文/标签色要压暗到近白底上可读。
        int soft = hsv(hue, 0.06f, 0.96f);
        int chipBg = hsv(hue, 0.15f, 0.96f);
        return new int[]{
                inkOn(hue, 0.62f, 0.85f, 0xFFFFFFFF),
                inkOn(hue, 0.58f, 0.95f, 0xFFFFFFFF),
                0xFFFFFFFF,
                hsv(hue, 0.05f, 0.98f),
                inkOn(hue, 0.30f, 0.28f, soft),
                inkOn(hue, 0.20f, 0.58f, soft),
                hsv(hue, 0.10f, 0.93f),
                hsv(hue, 0.10f, 0.98f),
                soft,
                hsv(hue, 0.12f, 0.97f),
                0xFF1B8A3A,
                0xFFB3261E,
                0xFFFFFFFF,
                inkOn(hue, 0.50f, 0.87f, 0xFFFFFFFF),
                hsv(hue, 0.18f, 0.92f),
                chipBg,
                inkOn(hue, 0.55f, 0.78f, chipBg),
                hsv(hue, 0.22f, 0.98f),
                hsv(hue, 0.06f, 0.97f),
                inkOn(hue, 0.15f, 0.68f, chipBg),
                hsv(hue, 0.14f, 0.97f),
                0xFF8A5A00,
                0xFFFFF4D6,
                hsv(hue, 0.08f, 0.82f),
                0xFFF2603C, 0xFFE8608F, 0xFF5A7BD8,
                0x1422315B
        };
    }
}