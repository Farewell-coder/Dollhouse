package com.dollhouse.app.ui.theme

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs

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
object ThemeManager {
    /** 随系统（跟随系统深色模式开关）。 */
    const val MODE_SYSTEM = 0

    /** 强制白色。 */
    const val MODE_LIGHT = 1

    /** 强制暗色。 */
    const val MODE_DARK = 2

    /** 纯黑（OLED 省电）。 */
    const val MODE_BLACK = 3

    /** 档位显示名，顺序与 MODE_* 一致。 */
    @JvmField
    val MODE_NAMES = arrayOf("\u968f\u7cfb\u7edf", "\u767d\u8272", "\u6697\u8272", "\u7eaf\u9ed1")

    private const val LOG_TAG = "Dollhouse"

    // ---- 调色板下标：顺序与 apply() 里的赋值顺序严格一致 ----
    private const val I_ACC = 0
    private const val I_ACC2 = 1
    private const val I_CARD = 2
    private const val I_BG = 3
    private const val I_TITLE = 4
    private const val I_SUB = 5
    private const val I_LINE = 6
    private const val I_OPTION = 7
    private const val I_SOFT = 8
    private const val I_FIELD = 9
    private const val I_OK = 10
    private const val I_ERR = 11
    private const val I_ON_ACC = 12
    private const val I_CHAT_BUBBLE_USER = 13
    private const val I_CHAT_BORDER = 14
    private const val I_CHAT_CHIP_BG = 15
    private const val I_CHAT_CHIP_FG = 16
    private const val I_CHAT_CHIP_ON = 17
    private const val I_CHAT_CHIP_OFF = 18
    private const val I_CHAT_CHIP_MUTE = 19
    private const val I_CHAT_ACTION_BG = 20
    private const val I_HINT_FG = 21
    private const val I_HINT_BG = 22
    private const val I_SWITCH_OFF = 23
    private const val I_EMOTE_1 = 24
    private const val I_EMOTE_2 = 25
    private const val I_EMOTE_3 = 26
    private const val I_STROKE = 27

    /** 弹层遮罩色（抽屉 / 面板背后的压暗层）。 */
    private const val I_SCRIM = 28
    private const val PAL_SIZE = 29

    /**
     * 白色档内置调色板 —— **方向 A「安静居所」**（设计稿 `dollhouse-refresh` §6）。
     *
     * 【M1 为什么换掉原值】原值是冷调紫灰（bg #F1F2F7、card 纯白、title #22315B 偏蓝）。
     *   冷底 + 纯白卡片 + 蓝黑标题在夜间读起来像「企业后台」，与本 App「一间亮着灯的小房子」
     *   的情感定位相反。新值把基底换成**暖纸色**（bg #F7F4EE、card #FCFAF6），
     *   标题压到暖近黑 #2A2620，描边用暖灰 #E6E0D4 —— 颜色之间只差很小的色相与明度，
     *   层级由留白与发丝线承担，而不是靠五颜六色。
     *
     * 【为什么不改数组长度】`cachedMonetPalette()` 校验 length == PAL_SIZE(29)，
     *   长度一变就整体丢弃莫奈取色（历史故障：开关能开、配色不变）。29 格一位不动。
     *
     * 【顺序必须与 `UiKit.snapshotPalette()` 完全一致】就地换主题靠两张表按下标重映射，
     *   错一位就会「换主题之后某块颜色对不上」。
     */
    /*
     * 【line / 描边色值偏离设计稿：这是有意的，且已用数值验证过】
     *
     * 设计稿 dollhouse-refresh 给的三档 line 值，实测对页面底的对比度全部低于 1.3
     * （1dp 发丝线在可辨识下限之下，等于看不见）：
     *
     *              设计稿        实测对比度        实际采用      实测对比度
     *   浅色   #E6E0D4   bg 1.20 / card 1.26   #DAD2C2   bg 1.37 / card 1.44
     *   暗色   #2E2A22   bg 1.28 / card 1.20   #38332A   bg 1.46 / card 1.37
     *   纯黑   #201C15   bg 1.24 / card 1.17   #2A251C   bg 1.38 / card 1.31
     *
     * 「层级由留白与发丝线承担」是方向 A 的核心手法。线看不见，层级就塌了 ——
     * 那比「颜色和设计稿差几个色阶」严重得多。所以这里取「设计稿的色相 + 加深一档」。
     *
     * 【请勿把它们改回设计稿值】`tools/palette_contrast.py` 的门禁会立刻报红，
     * 那是对的：它在守护可辨识下限，不是在挑刺。
     */


    private val LIGHT = intArrayOf(
        // 0-5  ACC, ACC2, CARD, BG, TITLE, SUB
        0xFF6B4EE6.toInt(), 0xFF8B6EF7.toInt(), 0xFFFCFAF6.toInt(), 0xFFF7F4EE.toInt(), 0xFF2A2620.toInt(), 0xFF6F675C.toInt(),
        // 6-9  LINE, OPTION, SOFT, FIELD
        0xFFDAD2C2.toInt(), 0xFFF2EEE7.toInt(), 0xFFF4F0E9.toInt(), 0xFFF1EDE5.toInt(),
        // 10-12 OK, ERR, ON_ACC
        0xFF4B7A57.toInt(), 0xFFB0563F.toInt(), 0xFFFFFFFF.toInt(),
        // 13-16 CHAT_BUBBLE_USER, CHAT_BORDER, CHAT_CHIP_BG, CHAT_CHIP_FG
        0xFFEDE7DC.toInt(), 0xFFE2DACC.toInt(), 0xFFF1EDE4.toInt(), 0xFF6F675C.toInt(),
        // 17-20 CHAT_CHIP_ON, CHAT_CHIP_OFF, CHAT_CHIP_MUTE, CHAT_ACTION_BG
        0xFFE9E3FF.toInt(), 0xFFF0ECE3.toInt(), 0xFFDCD5C7.toInt(), 0xFFF2EEE7.toInt(),
        // 21-23 HINT_FG, HINT_BG, SWITCH_OFF
        0xFF6F5B43.toInt(), 0xFFFAF0DF.toInt(), 0xFFD8D1C3.toInt(),
        // 24-26 EMOTE_1..3（低饱和暖色，不做彩虹）
        0xFFE9DAC4.toInt(), 0xFFDCE4DA.toInt(), 0xFFE5DCE6.toInt(),
        // 27-28 STROKE, SCRIM
        0x8CDAD2C2.toInt(), 0x6B262018
    )

    /**
     * 暗色档内置调色板 —— 方向 A「暖炭」。
     *
     * 【为什么不是蓝黑】原值 bg #121317 / card #1E1F26 是冷蓝黑，与浅色档的暖纸色
     *   在色温上是两个产品。新值 bg #171510 / card #1E1B15 是**暖炭**：
     *   夜间在 OLED 上不发蓝刺眼，且与浅色档共享同一色相家族，切主题时不会「换了个人」。
     */
    private val DARK = intArrayOf(
        // 0-5  ACC, ACC2, CARD, BG, TITLE, SUB
        0xFFA78EFF.toInt(), 0xFFBEA9FF.toInt(), 0xFF1E1B15.toInt(), 0xFF171510.toInt(), 0xFFEDE7DB.toInt(), 0xFFA79D8D.toInt(),
        // 6-9  LINE, OPTION, SOFT, FIELD
        0xFF38332A.toInt(), 0xFF211E17.toInt(), 0xFF1B1914.toInt(), 0xFF201D16.toInt(),
        // 10-12 OK, ERR, ON_ACC
        0xFF7DAE88.toInt(), 0xFFE58E77.toInt(), 0xFF17131F.toInt(),
        // 13-16 CHAT_BUBBLE_USER, CHAT_BORDER, CHAT_CHIP_BG, CHAT_CHIP_FG
        0xFF2B2620.toInt(), 0xFF332E25.toInt(), 0xFF262218.toInt(), 0xFFA79D8D.toInt(),
        // 17-20 CHAT_CHIP_ON, CHAT_CHIP_OFF, CHAT_CHIP_MUTE, CHAT_ACTION_BG
        0xFF332C55.toInt(), 0xFF262218.toInt(), 0xFF3A3428.toInt(), 0xFF211E17.toInt(),
        // 21-23 HINT_FG, HINT_BG, SWITCH_OFF
        0xFFD3B78C.toInt(), 0xFF2B2518.toInt(), 0xFF3B3529.toInt(),
        // 24-26 EMOTE_1..3
        0xFF3A3226.toInt(), 0xFF2B3730.toInt(), 0xFF352C38.toInt(),
        // 27-28 STROKE, SCRIM
        0x8C38332A.toInt(), 0x8C000000.toInt()
    )

    /**
     * 纯黑档内置调色板 —— 方向 A「纯黑」。
     *
     * 【为什么卡片不是纯黑】原值 card 与 bg 同为 #000000，只能靠 #303030 描边分层，
     *   结果整屏像一堆玻璃框。新值 bg #000000（OLED 真黑省电）+ card #0A0907（几乎看不出，
     *   但足以让卡片「浮起一丝」），结构主要仍由 #201C15 描边承担。
     */
    private val BLACK = intArrayOf(
        // 0-5  ACC, ACC2, CARD, BG, TITLE, SUB
        0xFFA78EFF.toInt(), 0xFFBEA9FF.toInt(), 0xFF0A0907.toInt(), 0xFF000000.toInt(), 0xFFF0EBE0.toInt(), 0xFF9C9282.toInt(),
        // 6-9  LINE, OPTION, SOFT, FIELD
        0xFF2A251C.toInt(), 0xFF100E0A.toInt(), 0xFF0B0A08.toInt(), 0xFF110F0B.toInt(),
        // 10-12 OK, ERR, ON_ACC
        0xFF79A984.toInt(), 0xFFE0866F.toInt(), 0xFF17131F.toInt(),
        // 13-16 CHAT_BUBBLE_USER, CHAT_BORDER, CHAT_CHIP_BG, CHAT_CHIP_FG
        0xFF171410.toInt(), 0xFF2A251D.toInt(), 0xFF16130E.toInt(), 0xFF9C9282.toInt(),
        // 17-20 CHAT_CHIP_ON, CHAT_CHIP_OFF, CHAT_CHIP_MUTE, CHAT_ACTION_BG
        0xFF2A2450.toInt(), 0xFF16130E.toInt(), 0xFF2E2A21.toInt(), 0xFF100E0A.toInt(),
        // 21-23 HINT_FG, HINT_BG, SWITCH_OFF
        0xFFC9AE84.toInt(), 0xFF1F1B11.toInt(), 0xFF2E2A21.toInt(),
        // 24-26 EMOTE_1..3
        0xFF2A2419.toInt(), 0xFF1F2A24.toInt(), 0xFF28202C.toInt(),
        // 27-28 STROKE, SCRIM
        0x8C332D22.toInt(), 0xB8000000.toInt()
    )

    /** 当前主题档位；非法值一律按「随系统」。 */
    @JvmStatic
    fun mode(c: Context): Int {
        return try {
            val m = PetPrefs.themeMode(c)
            if (m < MODE_SYSTEM || m > MODE_BLACK) MODE_SYSTEM else m
        } catch (ignored: Throwable) {
            MODE_SYSTEM
        }
    }

    @JvmStatic
    fun setMode(c: Context, m: Int) {
        PetPrefs.setThemeMode(c, m)
    }

    @JvmStatic
    fun monet(c: Context): Boolean {
        return try {
            PetPrefs.themeMonet(c)
        } catch (ignored: Throwable) {
            false
        }
    }

    @JvmStatic
    fun setMonet(c: Context, on: Boolean) {
        PetPrefs.setThemeMonet(c, on)
    }

    /** 当前是否按深色渲染（纯黑也算深色）。 */
    @JvmStatic
    fun isDark(c: Context): Boolean {
        val m = mode(c)
        if (m == MODE_LIGHT) {
            return false
        }
        if (m == MODE_DARK || m == MODE_BLACK) {
            return true
        }
        return try {
            val ui = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            ui == Configuration.UI_MODE_NIGHT_YES
        } catch (ignored: Throwable) {
            false
        }
    }


    /** 把当前主题写进 UiKit 的颜色字段。任何异常都不允许影响启动。 */
    @JvmStatic
    fun apply(c: Context?) {
        try {
            if (c == null) {
                return
            }
            val m = mode(c)
            val dark = isDark(c)
            val black = m == MODE_BLACK
            var p = if (black) BLACK else if (dark) DARK else LIGHT
            if (monet(c)) {
                // 只读缓存，绝不在主线程解码壁纸：apply() 在 Activity.onCreate 里调用，
                // 一旦同步解码壁纸，冷启动就会卡住甚至被系统判为无响应。
                val mp = cachedMonetPalette(c, dark, black)
                if (mp != null) {
                    p = mp
                }
            }
            UiKit.ACC = p[I_ACC]
            UiKit.ACC2 = p[I_ACC2]
            UiKit.CARD = p[I_CARD]
            UiKit.BG = p[I_BG]
            UiKit.TITLE = p[I_TITLE]
            UiKit.SUB = p[I_SUB]
            UiKit.LINE = p[I_LINE]
            UiKit.OPTION = p[I_OPTION]
            UiKit.SOFT = p[I_SOFT]
            UiKit.FIELD = p[I_FIELD]
            UiKit.OK = p[I_OK]
            UiKit.ERR = p[I_ERR]
            UiKit.ON_ACC = p[I_ON_ACC]
            UiKit.CHAT_BUBBLE_USER = p[I_CHAT_BUBBLE_USER]
            UiKit.CHAT_BORDER = p[I_CHAT_BORDER]
            UiKit.CHAT_CHIP_BG = p[I_CHAT_CHIP_BG]
            UiKit.CHAT_CHIP_FG = p[I_CHAT_CHIP_FG]
            UiKit.CHAT_CHIP_ON = p[I_CHAT_CHIP_ON]
            UiKit.CHAT_CHIP_OFF = p[I_CHAT_CHIP_OFF]
            UiKit.CHAT_CHIP_MUTE = p[I_CHAT_CHIP_MUTE]
            UiKit.CHAT_ACTION_BG = p[I_CHAT_ACTION_BG]
            UiKit.HINT_FG = p[I_HINT_FG]
            UiKit.HINT_BG = p[I_HINT_BG]
            UiKit.SWITCH_OFF = p[I_SWITCH_OFF]
            // 【M1·单一强调色】开关开启态跟随主色。
            //   【为什么改】原实现是固定薄荷绿（#3ECFA0），理由是「与任何主色都能分辨开关状态」。
            //     但设计稿方向 A 的核心约束是「一个屏只有一个强调色」，薄荷绿会变成第二个强调色：
            //     浅色档里紫（按钮）+ 绿（开关）并排出现，看着像两个产品拼起来的。
            //   【可分辨性怎么保证】开启态靠**位置 + 滑块右移 + 轨道填满**三重信号表达，
            //     不依赖色相差异；关闭态用暖灰轨道（SWITCH_OFF），对比足够。
            //   【注意】SWITCH_ON 不占调色板的 29 格（它在数组之外），所以这里显式赋值；
            //     赋值来源是 p[I_ACC]，莫奈取色时同样自动跟随。
            UiKit.SWITCH_ON = p[I_ACC]
            UiKit.EMOTE_1 = p[I_EMOTE_1]
            UiKit.EMOTE_2 = p[I_EMOTE_2]
            UiKit.EMOTE_3 = p[I_EMOTE_3]
            UiKit.STROKE = p[I_STROKE]
            UiKit.SCRIM = p[I_SCRIM]
            // 【外观】把「卡片透明度」偏好落到 UiKit 的派生色上。放在这里是因为 apply() 是
            //   29 个颜色字段的唯一写入处，冷启动 / 换主题 / 就地换色三条路径都必经此处，
            //   落一次即可保证「卡片底 = CARD 按用户透明度合成」在全 App 一致。
            UiKit.applyCardAlpha(PetPrefs.cardAlpha(c))
            // 【r13·外观】弹窗 / 面板的「磨砂程度」。与卡片透明度同理：apply() 是颜色的
            //   唯一写入处，冷启动 / 换主题 / 就地换色三条路径都必经此处，落一次即可全 App 一致。
            UiKit.applyFrostStrength(PetPrefs.frostStrength(c))
            // 【r13·外观】用户自定义字体颜色。与卡片透明度同理：apply() 是 29 个颜色字段的
            //   唯一写入处，冷启动 / 换主题 / 就地换色三条路径都必经此处，落一次即可全 App 一致。
            UiKit.applyFontColor(PetPrefs.fontColor(c))
            // 窗口背景同步成当前主题底色：换主题走 recreate()，重建的那一瞬间会先露出
            // 窗口背景，不刷的话暗色 / 纯黑下会闪一下白。
            val act = UiKit.findActivity(c)
            val win = act?.window
            if (win != null) {
                win.setBackgroundDrawable(ColorDrawable(UiKit.BG))
            }
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "ignored", t)
        }
    }

    /** 取色缓存是否可用（只读缓存，不解码壁纸，可在主线程安全调用）。 */
    @JvmStatic
    fun hasMonetColor(c: Context): Boolean {
        return try {
            !readHueCache(c).isNaN()
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * 【r14·外观 Tab①】「从壁纸取色」的取色来源档位名。
     *
     * 【为什么只有两档】本工程实际可用的取色通道就两条（见 [extractHue]）：
     *   ① 系统调色接口 `getWallpaperColors()` —— 零权限、不解码，最稳；
     *   ② 自己解码壁纸缩略图 —— 兜底，Android 13+ 可能需要读图权限。
     *   摆出四档（锁屏 / 主屏 / 平均色…）会让用户选了才发现没区别 —— 那才是骗人。
     *   这里如实给出「自动 / 强制解码」两条，语义清晰、也确实不同。
     */
    @JvmField
    val WALL_NAMES = arrayOf("自动（推荐）", "强制解码")

    /**
     * 【r14】设置取色来源档位。
     *
     * 【档位怎么影响取色】
     *   0 = 自动：先试系统调色接口，拿不到再解码（[extractHue] 的默认行为）。
     *   1 = 强制解码：跳过系统接口直接解码 —— 用于「系统给出了灰黑色但壁纸其实很鲜艳」的情况。
     *
     * 【为什么必须写独立键】`PetPrefs.themeMonetWall` 存的是**系统壁纸 id**，
     *   只用于「缓存是否还对应同一张壁纸」的校验（见 [readHueCache]）。
     *   把档位写进那个键，会在用户换壁纸时被系统 id 冲掉 —— 选择凭空消失。
     *   故档位单开一个键 [PetPrefs.monetWallMode]。
     */
    @JvmStatic
    fun setMonetWall(c: Context?, wall: Int) {
        if (c == null) {
            return
        }
        try {
            PetPrefs.setMonetWallMode(c, Math.max(0, Math.min(WALL_NAMES.size - 1, wall)))
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "ignored", t)
        }
    }

    /**
     * 当前取色来源档位。
     * 【与缓存的区别】`PetPrefs.themeMonetWall` 历史上存的是**壁纸 id**（用于缓存校验），
     *   本方法返回的才是用户选的**档位**。两者共用一个键会互相踩：
     *   壁纸一变（用户换壁纸）就会把档位冲掉，用户回来发现「我的选择没了」。
     *   故档位单独存一个键（见下）。
     */
    @JvmStatic
    fun monetWall(c: Context?): Int {
        if (c == null) {
            return 0
        }
        return try {
            val v = PetPrefs.monetWallMode(c)
            Math.max(0, Math.min(WALL_NAMES.size - 1, v))
        } catch (ignored: Throwable) {
            0
        }
    }

    /** 后台取色回调：ok=true 表示拿到了颜色，可以刷新界面；false 表示取不到、应回退内置配色。 */
    fun interface HueCallback {
        fun onHue(ok: Boolean)
    }

    /**
     * 后台解析壁纸色相并写入缓存。
     * 主线程只负责「读缓存」，真正的解码全部丢到这里 —— 壁纸是整屏大图，
     * 在主线程解码会让 Activity 冷启动明显卡顿甚至被判无响应。
     */
    @JvmStatic
    fun monetHueAsync(c: Context?, cb: HueCallback?) {
        val app = c?.applicationContext
        if (app == null) {
            cb?.onHue(false)
            return
        }
        val main = Handler(Looper.getMainLooper())
        Thread({
            var ok = false
            try {
                ok = refreshHueCache(app)
            } catch (t: Throwable) {
                Logs.w(LOG_TAG, "ignored", t)
            }
            val result = ok
            main.post {
                cb?.onHue(result)
            }
        }, "dh-monet").start()
    }

    /** 供 apply() 使用的同步取色：只读缓存，未命中就返回 null 走内置配色。 */
    private fun cachedMonetPalette(c: Context, dark: Boolean, black: Boolean): IntArray? {
        return try {
            val hue = readHueCache(c)
            if (hue.isNaN()) {
                null
            } else {
                val p = fromHue(hue, dark, black)
                if (p.size == PAL_SIZE) p else null
            }
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "ignored", t)
            null
        }
    }

    /** 读缓存：命中返回色相，未命中返回 NaN。不触发任何解码，可在主线程调用。 */
    private fun readHueCache(c: Context): Float {
        try {
            val wallId = WallpaperManager.getInstance(c).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            val savedWall = PetPrefs.themeMonetWall(c)
            val savedHue10 = PetPrefs.themeMonetHue10(c)
            if (savedWall == wallId && savedHue10 != Int.MIN_VALUE) {
                return savedHue10 / 10.0f
            }
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        return Float.NaN
    }

    /** 解码壁纸并把色相写进缓存；成功返回 true。只在后台线程调用。 */
    private fun refreshHueCache(c: Context): Boolean {
        var wallId = Int.MIN_VALUE
        try {
            wallId = WallpaperManager.getInstance(c).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (ignored: Throwable) {
            Logs.w(LOG_TAG, "ignored", ignored)
        }
        val savedWall = PetPrefs.themeMonetWall(c)
        val savedHue10 = PetPrefs.themeMonetHue10(c)
        if (savedWall == wallId && savedHue10 != Int.MIN_VALUE) {
            return true
        }
        val hue = extractHue(c)
        if (hue.isNaN()) {
            return false
        }
        PetPrefs.setThemeMonetHue10(c, Math.round(hue * 10.0f))
        PetPrefs.setThemeMonetWall(c, wallId)
        return true
    }

    /**
     * 取壁纸主色相。优先走系统调色接口 getWallpaperColors()：颜色由系统进程算好，
     * 零权限、不解码大图，是唯一在各家 ROM 上都稳的路子。
     * 拿不到时再退回自己解码缩略图（Android 13+ 那条路要 READ_MEDIA_IMAGES，没授权必失败，只当兜底）。
     */
    private fun extractHue(c: Context): Float {
        // 【r14】尊重「从壁纸取色」的档位：强制解码时跳过系统调色接口。
        //   【为什么需要这条】部分 ROM 的 getWallpaperColors() 会给出偏灰的「代表色」，
        //   而壁纸本身其实很鲜艳 —— 那时系统给的就是错的，用户需要一条绕开它的路。
        val forced = try {
            PetPrefs.monetWallMode(c) == 1
        } catch (ignored: Throwable) {
            false
        }
        if (!forced) {
            val hue = hueFromWallpaperColors(c)
            if (!hue.isNaN()) {
                return hue
            }
        }
        return extractHueByDecode(c)
    }

    /** 走系统调色接口取主色相；拿不到、或是灰黑近白等无法代表主题色时返回 NaN。 */
    private fun hueFromWallpaperColors(c: Context): Float {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                return Float.NaN
            }
            val wc = WallpaperManager.getInstance(c)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (wc == null || wc.primaryColor == null) {
                return Float.NaN
            }
            val hsv = FloatArray(3)
            Color.colorToHSV(wc.primaryColor.toArgb(), hsv)
            if (hsv[1] < 0.12f || hsv[2] < 0.10f) {
                return Float.NaN
            }
            if (hsv[2] > 0.97f && hsv[1] < 0.10f) {
                return Float.NaN
            }
            return hsv[0]
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "ignored", t)
            return Float.NaN
        }
    }

    /** 兜底：把壁纸缩略图解码成 64×64，按色相分 12 桶、以「饱和度 × 明度」加权，取最重的桶做圆均值。 */
    private fun extractHueByDecode(c: Context): Float {
        val small = thumbFromWallpaper(c)
        if (small == null) {
            return Float.NaN
        }
        try {
            return hueFromThumb(small)
        } finally {
            small.recycle()
        }
    }

    /** 取壁纸并缩到 64×64 缩略图；取不到（无权限 / 异常）返回 null。 */
    private fun thumbFromWallpaper(c: Context): Bitmap? {
        var small: Bitmap? = null
        try {
            val wm = WallpaperManager.getInstance(c)
            var d: Drawable? = null
            try {
                d = wm.drawable
            } catch (ignored: Throwable) {
                Logs.w(LOG_TAG, "ignored", ignored)
            }
            if (d == null) {
                return null
            }
            var w = d.intrinsicWidth
            var h = d.intrinsicHeight
            if (w <= 0 || h <= 0) {
                w = 64
                h = 64
            }
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            small = bmp
            val cv = Canvas(bmp)
            cv.scale(64.0f / w, 64.0f / h)
            d.setBounds(0, 0, w, h)
            d.draw(cv)
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "ignored", t)
            small?.recycle()
            return null
        }
        return small
    }

    /** 从缩略图统计 12 段色相直方图，返回加权峰值角度；无有效色返回 NaN。 */
    private fun hueFromThumb(small: Bitmap): Float {
        val n = 64 * 64
        val px = IntArray(n)
        small.getPixels(px, 0, 64, 0, 0, 64, 64)
        val sx = DoubleArray(12)
        val sy = DoubleArray(12)
        val sw = DoubleArray(12)
        val hsv = FloatArray(3)
        for (i in 0 until n) {
            val p = px[i]
            if ((p ushr 24) < 128) {
                continue
            }
            Color.colorToHSV(p, hsv)
            // 跳过灰、黑、近白：它们不能代表主题色。
            if (hsv[1] < 0.18f || hsv[2] < 0.12f) {
                continue
            }
            if (hsv[2] > 0.96f && hsv[1] < 0.45f) {
                continue
            }
            var b = (hsv[0] / 30.0f).toInt()
            if (b < 0) {
                b = 0
            } else if (b > 11) {
                b = 11
            }
            val rad = hsv[0] * Math.PI / 180.0
            val wgt = (hsv[1] * hsv[2]).toDouble()
            sx[b] += Math.cos(rad) * wgt
            sy[b] += Math.sin(rad) * wgt
            sw[b] += wgt
        }
        var best = -1
        var bestW = 0.0
        for (i in 0 until 12) {
            if (sw[i] > bestW) {
                bestW = sw[i]
                best = i
            }
        }
        if (best < 0 || bestW < 0.5) {
            return Float.NaN
        }
        var ang = Math.atan2(sy[best], sx[best])
        if (ang < 0.0) {
            ang += Math.PI * 2.0
        }
        return (ang * 180.0 / Math.PI).toFloat()
    }

    private fun hsv(hue: Float, s: Float, v: Float): Int {
        return Color.HSVToColor(floatArrayOf(hue, s, v))
    }

    /** WCAG 相对亮度。 */
    private fun relLum(c: Int): Float {
        var r = Color.red(c) / 255.0f
        var g = Color.green(c) / 255.0f
        var b = Color.blue(c) / 255.0f
        r = if (r <= 0.03928f) r / 12.92f else Math.pow(((r + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        g = if (g <= 0.03928f) g / 12.92f else Math.pow(((g + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        b = if (b <= 0.03928f) b / 12.92f else Math.pow(((b + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** WCAG 对比度，1.0 ~ 21.0。 */
    private fun contrast(a: Int, b: Int): Float {
        val la = relLum(a)
        val lb = relLum(b)
        val hi = Math.max(la, lb)
        val lo = Math.min(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /**
     * 把同色相的颜色压暗到「在 bg 上读得清」。
     * 派生配色是按色相算的，而不同色相的亮度天差地别（黄色天生比紫色亮得多），
     * 同一个明度参数在黄/青/绿上会糊成一片。这里按对比度实测回调明度，直到达标。
     */
    private fun inkOn(hue: Float, s: Float, v: Float, bg: Int): Int {
        var vv = v
        for (i in 0 until 30) {
            val c = hsv(hue, s, vv)
            if (contrast(c, bg) >= 4.6f) {
                return c
            }
            vv -= 0.03f
        }
        return hsv(hue, s, 0.06f)
    }

    /**
     * 【莫奈统筹】把「语义色相」朝主色相靠 amount（0~1），只挪色相，不动饱和度 / 明度。
     *
     * 【为什么需要】成功绿 / 错误红 / 提示黄 / 表情色都有既定语义，色相不能直接跟着主色跑，
     *   否则「已授权 / 未授权」就分不出来了。但完全写死又会让莫奈模式下这些颜色纹丝不动 ——
     *   用户看到的正是「有些地方颜色根本没变」。折中：保留本色相为主，掺一点主色相。
     *
     * 【为什么不是直接混色】HSV 的色相是环形的，必须先算最短弧差（±180° 内），
     *   否则 350° 与 10° 会被算成差 340° 而绕远路，颜色直接跑飞。
     */
    private fun tintBy(baseHue: Float, hue: Float, amount: Float): Float {
        var d = hue - baseHue
        while (d > 180f) {
            d -= 360f
        }
        while (d < -180f) {
            d += 360f
        }
        var h = baseHue + d * amount
        if (h < 0f) {
            h += 360f
        }
        if (h >= 360f) {
            h -= 360f
        }
        return h
    }

    /** 保留 RGB，只换 alpha（0~255）。 */
    private fun withAlpha(c: Int, alpha: Int): Int {
        return (c and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }

    /** 由主色相派生整套配色；明度/饱和度逐项夹紧，保证亮底暗底上的字都读得清。 */
    private fun fromHue(hue: Float, dark: Boolean, black: Boolean): IntArray {
        if (dark) {
            // 状态色的对比度基准：与 CARD 同值，保证「绿字 / 红字」压在卡片上一定达标。
            val dCard = if (black) 0xFF000000.toInt() else hsv(hue, 0.10f, 0.13f)
            val dHint = hsv(tintBy(42f, hue, 0.22f), 0.28f, 0.20f)
            return intArrayOf(
                hsv(hue, 0.55f, 0.92f),
                hsv(hue, 0.45f, 1.00f),
                dCard,
                if (black) 0xFF000000.toInt() else hsv(hue, 0.12f, 0.07f),
                hsv(hue, 0.10f, 0.92f),
                hsv(hue, 0.10f, 0.66f),
                hsv(hue, 0.10f, 0.20f),
                if (black) 0xFF0D0D0D.toInt() else hsv(hue, 0.12f, 0.16f),
                if (black) 0xFF101010.toInt() else hsv(hue, 0.10f, 0.15f),
                hsv(hue, 0.16f, 0.18f),
                // 【莫奈统筹】成功色原写死 0xFF5FD07E、错误色原写死 0xFFFF6B60，莫奈下纹丝不动。
                //   现在色相各朝主色相靠 18%（保住「绿 = 已授权 / 红 = 未授权」），饱和度下调，
                //   明度交给 inkOn 按卡片底色实测对比度回调 —— 会变，而且更柔和。
                inkOn(tintBy(142f, hue, 0.18f), 0.50f, 0.90f, dCard),
                inkOn(tintBy(4f, hue, 0.18f), 0.58f, 0.92f, dCard),
                hsv(hue, 0.55f, 0.14f),
                hsv(hue, 0.45f, 0.85f),
                hsv(hue, 0.14f, 0.26f),
                hsv(hue, 0.18f, 0.20f),
                hsv(hue, 0.35f, 0.85f),
                hsv(hue, 0.30f, 0.30f),
                hsv(hue, 0.12f, 0.16f),
                hsv(hue, 0.10f, 0.60f),
                hsv(hue, 0.20f, 0.22f),
                // 【莫奈统筹】提示字色原写死 0xFFF0C674（底色本来就跟随，只有字色没跟）。
                inkOn(tintBy(42f, hue, 0.22f), 0.50f, 0.92f, dHint),
                dHint,
                hsv(hue, 0.08f, 0.35f),
                // 【莫奈统筹】桌宠表情三色原写死；色相语义保留（惊叹 / 开心 / 眩晕），各朝主色相靠 20%。
                hsv(tintBy(16f, hue, 0.20f), 0.58f, 0.90f),
                hsv(tintBy(335f, hue, 0.20f), 0.50f, 0.88f),
                hsv(tintBy(224f, hue, 0.20f), 0.50f, 0.88f),
                // 【莫奈统筹】描边不再是纯白，带一点主色相，暗底上更贴合。
                withAlpha(hsv(hue, 0.20f, 0.92f), if (black) 0x26 else 0x1A),
                // 【修·莫奈失效根因】末位 SCRIM 原缺失，导致本数组仅 28 个元素，
                //   cachedMonetPalette() 的 p.length == PAL_SIZE(29) 恒为 false，
                //   取色结果永远被丢弃、永远回退内置调色板（表现为开关能开、配色不变）。
                // 【莫奈统筹】同时由纯黑改成带主色相的深色压暗层。
                withAlpha(hsv(hue, 0.35f, 0.07f), if (black) 0xB3 else 0xA6)
            )
        }
        // 浅色档底色近白，字色必须压暗到对比度达标 —— 否则黄/青/绿壁纸下会白字白底、看不清。
        // 强调色（ACC/ACC2/气泡）要压暗到白字可读；正文/标签色要压暗到近白底上可读。
        val soft = hsv(hue, 0.06f, 0.96f)
        val chipBg = hsv(hue, 0.15f, 0.96f)
        // 状态色的对比度基准：白卡片。与 I_CARD 同为 0xFFFFFFFF。
        val lCard = 0xFFFFFFFF.toInt()
        val lHintBg = hsv(tintBy(42f, hue, 0.22f), 0.30f, 0.96f)
        return intArrayOf(
            inkOn(hue, 0.62f, 0.85f, 0xFFFFFFFF.toInt()),
            inkOn(hue, 0.58f, 0.95f, 0xFFFFFFFF.toInt()),
            0xFFFFFFFF.toInt(),
            hsv(hue, 0.05f, 0.98f),
            inkOn(hue, 0.30f, 0.28f, soft),
            inkOn(hue, 0.20f, 0.58f, soft),
            hsv(hue, 0.10f, 0.93f),
            hsv(hue, 0.10f, 0.98f),
            soft,
            hsv(hue, 0.12f, 0.97f),
            // 【莫奈统筹】成功色原写死 0xFF1B8A3A、错误色原写死 0xFFB3261E，莫奈下纹丝不动。
            //   色相各朝主色相靠 18%（保住「绿 = 已授权 / 红 = 未授权」），饱和度下调，
            //   明度交给 inkOn 按白卡片实测对比度回调 —— 会变，而且更柔和。
            inkOn(tintBy(142f, hue, 0.18f), 0.55f, 0.34f, lCard),
            inkOn(tintBy(4f, hue, 0.18f), 0.62f, 0.40f, lCard),
            0xFFFFFFFF.toInt(),
            inkOn(hue, 0.50f, 0.87f, 0xFFFFFFFF.toInt()),
            hsv(hue, 0.18f, 0.92f),
            chipBg,
            inkOn(hue, 0.55f, 0.78f, chipBg),
            hsv(hue, 0.22f, 0.98f),
            hsv(hue, 0.06f, 0.97f),
            inkOn(hue, 0.15f, 0.68f, chipBg),
            hsv(hue, 0.14f, 0.97f),
            // 【莫奈统筹】提示字色原写死 0xFF8A5A00、底色原写死 0xFFFFF4D6（唯一一处底色也没跟主色相走的地方）。
            inkOn(tintBy(42f, hue, 0.22f), 0.62f, 0.32f, lHintBg),
            lHintBg,
            hsv(hue, 0.08f, 0.82f),
            // 【莫奈统筹】桌宠表情三色原写死；色相语义保留（惊叹 / 开心 / 眩晕），各朝主色相靠 20%。
            hsv(tintBy(16f, hue, 0.20f), 0.62f, 0.86f),
            hsv(tintBy(335f, hue, 0.20f), 0.52f, 0.82f),
            hsv(tintBy(224f, hue, 0.20f), 0.52f, 0.78f),
            // 【莫奈统筹】描边由写死的深蓝灰 0x1422315B 改成主色相深色微透明 —— 白卡片上更贴。
            withAlpha(hsv(hue, 0.30f, 0.25f), 0x14),
            // 【修·莫奈失效根因】同上：末位 SCRIM 原缺失使数组只有 28 个元素。
            // 【莫奈统筹】压暗层由纯黑改成带主色相的深色，浮层不再「脏黑」。
            withAlpha(hsv(hue, 0.40f, 0.10f), 0x8A)
        )
    }
}
