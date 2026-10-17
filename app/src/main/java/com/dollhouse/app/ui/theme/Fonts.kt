package com.dollhouse.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.dollhouse.app.R

/**
 * 【职责】全局 UI 字体（LXGW WenKai 子集）的唯一提供器。
 *
 * 【单例缓存】Typeface 只加载一次、全局复用，不重复 inflate，内存占用最小。
 * 【回退】字体加载失败（ROM 裁剪字体目录 / 子集漏字）时回退系统默认，绝不影响功能。
 * 【范围】全 App 所有可见文字：标题 / 按钮 / 卡片 / 列表项 / 聊天气泡 / 输入框 / 设置页注脚。
 *   唯一例外是 [LamdaUiKit] 里刻意用 MONOSPACE 的日志块（等宽是它的语义，不是漏挂）。
 * 【完整版·非子集】打包的是 LXGW WenKai 完整字重（约 24.9MB，glyphs=46788）。
 *   历史上曾计划子集化到约 1.8MB，但因「聊天时任意生僻字都要能显示」的需求被否决 ——
 *   子集必然漏字，漏字处会掉回系统字体、观感突兀。缺字仍由系统字体自动回退，不会出豆腐块。
 */
object Fonts {

    @Volatile
    private var cached: Typeface? = null
    @Volatile
    private var cachedBold: Typeface? = null
    @Volatile
    private var tried = false

    /**
     * 【r18·启动性能】后台异步预热字体，避免首帧渲染时主线程被 25MB 字体解码阻塞。
     *
     * 【为什么需要】`ui()` 是「调用即要」的同步接口，首次调用（HomeUi 渲染首帧）
     *   会同步 `ResourcesCompat.getFont` —— 25MB 字体解码在主线程要几百 ms，首帧必掉。
     *   预热把这次解码挪到后台线程提前完成：主线程首次调用时若缓存已就绪则零阻塞；
     *   若还没好则照旧走同步路径（回退系统默认），**功能与观感零损失**（最多首帧用默认字体，
     *   下一帧缓存就绪自动换）。
     *
     * 【幂等】多次调用只触发一次真实加载（`tried` 标记 + 同步块内二次检查）。
     * 【调用时机】由 MainActivity.onCreate 在首帧渲染前调用（见调用点注释）。
     */
    @JvmStatic
    fun warmUp(ctx: Context?) {
        if (ctx == null || cached != null || tried) {
            return
        }
        val appCtx = ctx.applicationContext
        Thread {
            // 触发一次 ui() 即可把字体装进缓存；返回值不用管（缓存已写）。
            ui(appCtx)
        }.apply {
            name = "dh-font-warmup"
            isDaemon = true
            priority = Thread.MIN_PRIORITY // 不抢首帧渲染的 CPU
        }.start()
    }

    /** 全局 UI 字体；加载失败返回系统默认（Typeface.DEFAULT），调用方无需判空。 */
    @JvmStatic
    fun ui(ctx: Context?): Typeface {
        val hit = cached
        if (hit != null) {
            return hit
        }
        if (tried) {
            return Typeface.DEFAULT
        }
        synchronized(this) {
            if (cached != null) {
                return cached!!
            }
            if (tried) {
                return Typeface.DEFAULT
            }
            tried = true
            if (ctx == null) {
                return Typeface.DEFAULT
            }
            cached = try {
                ResourcesCompat.getFont(ctx.applicationContext, R.font.lxgw_wenkai)
            } catch (ignored: Throwable) {
                null
            }
            return cached ?: Typeface.DEFAULT
        }
    }

    /**
     * 全局 UI 粗体：LXGW WenKai 是单字重字体，用 [Typeface.create] 派生合成粗体（描边加粗）。
     * 加载失败回退系统默认粗体，绝不影响功能。同样只派生一次、全局复用。
     */
    @JvmStatic
    fun uiBold(ctx: Context?): Typeface {
        val hit = cachedBold
        if (hit != null) {
            return hit
        }
        synchronized(this) {
            if (cachedBold != null) {
                return cachedBold!!
            }
            val base = ui(ctx)
            cachedBold = if (base === Typeface.DEFAULT) {
                Typeface.DEFAULT_BOLD
            } else {
                try {
                    Typeface.create(base, Typeface.BOLD)
                } catch (ignored: Throwable) {
                    Typeface.DEFAULT_BOLD
                }
            }
            return cachedBold!!
        }
    }
}
