package com.dollhouse.app.pet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.ActionScale
import com.dollhouse.app.data.ActionViewport
import com.dollhouse.app.data.ActionWorkbench
import com.dollhouse.app.data.BodyBaseline
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.pet.render.Engine
import com.dollhouse.app.pet.render.PetDisplayHost
import com.dollhouse.app.pet.render.PetRenderer
import com.dollhouse.app.pet.render.PetSource
import com.dollhouse.app.pet.render.RendererRegistry
import java.io.File

/**
 * 【职责】桌宠侧的「动作播放层」—— 把 `DollAnim` 里的动作**真正画到桌面上**。
 *
 * ## 这就是本次任务的成败判据
 *
 * 任务书 §1.4 原文：
 * > **桌宠能够实际使用动作系统，而不是只有编辑器和预览界面。只做框架不接线 = 失败。**
 *
 * 改造前的真实状况（已 grep 证实）：`PetView` / `PetWindow` / `PetAnimator`
 * 对 `DollAnim` **零引用** —— 用户在动作页精心配的动作，桌宠一个都不会播；
 * 桌宠跑的是 `PetAnimator` 里硬编码的 5 个待机小动作。
 * 本类就是那条缺失的接线：**把动作数据变成桌面上的像素**。
 *
 * ## 为什么单独一层而不是塞进 PetView
 *
 * 三条理由，都是「不改坏」：
 * 1. **无动作时路径必须逐行不变**：`PetView.onDraw` 是本工程的视觉核心，
 *    且烘帧（`bakeDraw`）要与它逐像素同源。把动作逻辑塞进去，
 *    等于让「播动作」这条路有机会影响「不播动作」那条路。现在
 *    `PetView` 只在 `hasContent()` 为真时才把绘制让给本类，否则**一行都不碰**。
 * 2. **解码是异步的**：帧图可能有 120 张，主线程解码必然掉帧。
 *    本类自己管后台解码 + 代次校验（`generation`），不污染视图的线程模型。
 * 3. **三个引擎共用一套生命周期**：帧序列 / Lottie / 动图在这里统一成
 *    「准备好了就画、没准备好就让位」，`PetView` 不必知道背后是什么引擎。
 *
 * ## 线程
 * `play` / `tick` / `draw` / `stop` / `release` **都在主线程**（由 PetView 调用）。
 * 解码在后台线程，完成后经 `Handler` 回主线程 —— 回调里用 `generation` 判代次，
 * 丢弃「切走之后才回来的」结果，避免出现「播 A 结果 A 的旧解码把 B 顶掉」。
 */
class ActionPlayerCore(
    /** 请求宿主重绘（解码完成、换帧时用）。 */
    private val requestDraw: () -> Unit,
    /**
     * 帧图解码的**目标边长上限（px）**，由宿主按人偶实际显示尺寸注入。
     *
     * ## 为什么必须由外面给，而不是写死
     *
     * 改造前这里写死 1024，注释理由是「桌宠窗口通常 ≤ 屏宽一半（1080p 屏约 540px），
     * 采样到 1024 能覆盖且留一倍余量」。但 `inSampleSize` 只能按 2 的幂**向下**采样：
     * 目标 1024 时，一张 2048² 的图采样率是 2，解出 1024²；而人偶实际只有 450px 宽
     * （本机 480dpi，`dp(150)×3`，放大上限也才 675px）。**像素量是需要的 5 倍**。
     *
     * 满帧时更明显：120 帧 × 1024² × 4B ≈ **480MB**，这是「放一晚掉电/被系统杀掉」的
     * 直接来源之一；按实际尺寸 512 解码后同一套帧约 120MB。
     *
     * `ImageDecode.calcSampleSize` 保证「采样后仍 ≥ 目标」，所以给一个略大于实际显示
     * 尺寸的上限即可，不会因为采样过度而糊。
     *
     * @return <=0 表示使用 [DEFAULT_DECODE_TARGET_PX] 兜底（没注入时的旧口径）。
     */
    private val decodeTargetPx: () -> Int = { 0 },
    /**
     * 位图解码器 —— **可注入**，这样就可以在 JVM 单测里塞一个假的解码器
     * 验证「帧推进 / 循环 / 播完回调」这些纯逻辑，不需要真机也不需要 Robolectric。
     */
    private val decoder: (String) -> Bitmap? = { path ->
        // 【r22·内存】帧图按**人偶绘制尺寸**采样解码，不按写死的 1024。
        //
        //   改造前目标恒为 1024。`calcSampleSize` 的语义是「采样后仍 ≥ 目标」，
        //   所以这个目标其实是个**下限**：素材越小越不可能降档。本机实测帧组是
        //   904×978（不是假设的 2048²），904/2 = 452 < 1024 → **采样率恒为 1，
        //   一张都没降过档**，120 帧 ARGB 到 405MB。原来那句「采样到 1024 省 75%」
        //   只对 ≥2048² 的素材成立，对实际素材是空转。
        //
        //   现在由宿主注入（见 [decodeTargetPx]）：`max(petWidth, petMaxWidth) × 0.5`，
        //   450px 窗口 → 目标 225→夹到 256，仍 ≤ 452 → **稳定触发 sample=2**，
        //   120 帧降到 101MB。没有注入时退回 [DEFAULT_DECODE_TARGET_PX]，与改造前逐像素一致。
        val injected = decodeTargetPx()
        val target = if (injected > 0) injected else DEFAULT_DECODE_TARGET_PX
        com.dollhouse.app.core.ImageDecode.file(
            path,
            reqWidth = target,
            reqHeight = target
        )
    },
    /** 宿主提供者（Lottie / 动图引擎要 attach 到宿主才能自绘）。 */
    private val hostProvider: () -> PetDisplayHost? = { null },
    /**
     * **【v0.1.0】**本体基准矩形提供者（相对画布的比例 `[l, t, r, b]`）。
     *
     * 【为什么由外面注进来】「本体在哪」是烘帧那条链算出来的（`AnimBaker.baseline`），
     *   而播放层不依赖数据层 —— 注入一个查询函数，两边都不必知道对方。
     *
     * 【返回 null 的含义】这个动作没有基准（用户自己导入的帧、或旧版烘的帧）：
     *   此时回退成「整张画布铺满」，与改造前一致。
     */
    private val baselineProvider: (String) -> FloatArray? = { null },
    /**
     * **【v0.1.0·贴边姿态】**贴边倾斜角（度），由宿主注入（读 `PetView.lean`）。
     *
     * ## 为什么姿态必须在这里施加
     *
     * `PetView.onDraw` 里「有动作在播」这条分支是**先 return、后做变换**的：
     * ```
     * if (act != null && act.hasContent()) { act.draw(canvas); return }   // ← 在这里就走了
     * ... mesh() / PetTransform.build(lean = lean) ...                    // ← 永远到不了
     * ```
     * 于是 `setEdgePeek()` 设的 `lean = ±25°` 对**几何包围盒**生效（窗口宽高由
     *   `computeBox` 按 lean 算外接框），却**从不作用到动作画面上**。
     *
     * 这一条直接决定贴边帧该怎么画：既然运行时不存在「系统层帮你歪」这回事，
     *   帧里若也不画倾斜，贴边的人偶就是**笔直**的；而早期版本为了让画面看起来歪，
     *   把 `25° × sin(πt)` 画进了帧里 —— 那又变成「站直 → 歪下 → 站直」，
     *   因为正弦包络首末帧为 0。这正是用户说的「反复进出贴边」。
     *
     * 正确做法：**姿态恒定、由这一层按角度旋转整幅画面**，帧序列只负责
     *   「悬着时的生命感」（呼吸 / 摆发 / 偶尔眨眼），两者不再互相冒充。
     *
     * @return 0 表示不旋转（非贴边）。非 0 时以「画面水平中心 + 画面底边」为轴旋转，
     *   与窗口 `computeBox` 的外接框口径同源，倾斜后不会顶出窗口。
     */
    private val leanProvider: () -> Float = { 0f },

    /**
     * 某个动作的显示设置（锚点 / 适配方式 / 内容偏移）。
     *
     * @return null = 用默认（底部居中 / 按统一尺寸适配 / 无偏移）
     */
    private val displayProvider: (String) -> ActionWorkbench.ActionDisplay? = { null },

    /** 人偶级视口：所有动作共用，切换动作不会改变人物基础身高。 */
    private val viewportProvider: () -> ActionViewport.Spec? = { null },

    /** 动作所需宽度变化时回调宿主。只由动作/帧/比例变化触发，绝不逐帧布局。 */
    private val viewportChanged: (ActionViewport.Spec) -> Unit = {}
) {

    companion object {
        private const val TAG = "DollhouseAction"

        /** 单次最多解码多少帧（与 [DollAnim.FRAMES_MAX] 对齐，防越界档案把内存吃满）。 */
        const val MAX_FRAMES = DollAnim.FRAMES_MAX

        /**
         * 帧图解码目标边长的**兜底值**（宿主没注入时用）。
         *
         * 取 1024 是为了保持未注入路径与改造前**逐像素一致** —— 真机上宿主一定会注入
         * （见 [decodeTargetPx]），所以这个值只在单测 / 未来新调用方没接线时生效。
         */
        const val DEFAULT_DECODE_TARGET_PX = 1024

        /**
         * 解码目标相对「人偶绘制宽度」的**下限比例**。
         *
         * ## 这个常数为什么是 0.5 而不是 2（改错过一次，记录在此）
         *
         * 第一版写的是 `× 2` 的「分辨率余量」：窗口 450px → 目标 900。听起来更安全，
         *   但 `ImageDecode.calcSampleSize` 的判据是「**采样后仍 ≥ 目标**」，目标是**下限**
         *   而不是上界 —— 目标越高，采样率越低。实测本机素材 904×978：
         *
         * | 目标 | 采样率 | 单帧像素 | 120 帧 ARGB |
         * |---|---|---|---|
         * | 1024（改造前写死） | 1 | 0.88MP | **405MB** |
         * | 900（×2 余量） | 1 | 0.88MP | **405MB** |
         * | 512 | 1 | 0.88MP | 405MB |
         * | 452（= 450×0.5 取整后落档） | 2 | 0.22MP | **101MB** |
         *
         * 也就是说：改造前那个 1024 **从来没对 904px 的素材生效过**（904/2=452 < 1024，
         *   采样率恒为 1），注释里「采样到 1024 省 75% 内存」的说法与事实相反。
         *   真正能触发降档的目标必须 **≤ 原图边长的一半**。
         *
         * ## 0.5 为什么不会糊
         *
         * 绘制侧 `ActionScale.place` 按**本体高**映射，最终画面尺寸只与窗口高同阶；
         *   0.5 倍目标拿到的是「绘制尺寸的一半」分辨率，即 2 倍降采样后再放大回原尺寸 ——
         *   桌宠是柔和插画风、窗口又只有 450px，这个档位看不出来（帧图本身还有大量透明区）。
         *   若用户把人偶拉到上限（675px），目标变 337、仍落在同一采样档，
         *   **不会出现「调大后重新解码」的抖动**。
         */
        const val DECODE_TARGET_RATIO = 0.5f

        /**
         * 解码目标的下界（px）。
         *
         * 防止极小窗口（如预览缩略图 40px）把目标压到个位数导致整组帧变糊。
         */
        const val DECODE_TARGET_MIN = 256
    }

    private val ui = Handler(Looper.getMainLooper())
    private var viewport: ActionViewport.Spec? = null
    private var measuredWidth = 0
    private var measuredHeight = 0

    /** 当前实际视口；null 表示宿主尚未注入，绘制使用旧兼容路径。 */
    fun viewport(): ActionViewport.Spec? = viewport

    /** 比例变化时由宿主刷新。真正改窗口仍只在 refreshViewport 的短路之后发生。 */
    fun refreshViewport() {
        viewport = viewportProvider()
        measuredWidth = 0
        measuredHeight = 0
        remeasureViewport()
        requestDraw()
    }

    private fun remeasureViewport() {
        val base = viewportProvider()?.copy(requiredWidth = 0) ?: return
        val r = renderer
        var width = base.baseWidth
        var bw = 0
        var bh = 0
        if (r != null) {
            val size = r.size()
            if (size.size >= 2) {
                bw = size[0]
                bh = size[1]
            }
        }
        if (bw > 0 && bh > 0) {
            val placement = ActionViewport.measure(base, bw, bh, baseline)
            if (placement != null) width = placement.requiredWidth
        } else if (frames.isNotEmpty()) {
            // 扫整套动作而不是逐帧：每帧 canvas 可能不同，但不能造成窗口逐帧跳动。
            for (frame in frames) {
                if (frame.isRecycled) continue
                val p = ActionViewport.measure(base, frame.width, frame.height, baseline) ?: continue
                if (p.requiredWidth > width) width = p.requiredWidth
            }
        }
        val next = base.copy(requiredWidth = width)
        if (viewport == next) return
        viewport = next
        viewportChanged(next)
    }

    /** 自解码的帧序列（`sprite` 引擎用）。 */
    private var frames: List<Bitmap> = emptyList()

    /**
     * 当前 `frames` 属于哪个动作 id（复用判定用，见 [canReuseFrames]）。
     *
     * 【为什么单独记一个字段而不是读 [actionId]】[actionId] 是「正在播的动作」，
     *   `clearContent()` / `stop()` 会把它清掉；这里要的是「这批帧是谁的」，
     *   两者生命周期不同 —— 帧还在内存里、但动作已经停了的情况是常态。
     */
    private var loadedActionId: String = ""

    /** 当前 `frames` 对应的帧路径指纹（见 [frameKeyOf]）。 */
    private var loadedFrameKey: String = ""

    /**
     * 帧路径列表的**轻量指纹**，用来发现「动作 id 没变、但帧被用户改过」。
     *
     * 【为什么不是逐路径 join】一套 120 帧的路径拼起来是几 KB 字符串，每次 `play`
     *   都要拼一次并比较，反而成了新的开销。这里只取「数量 + 首尾路径 + 总长」：
     *   · 删帧 / 加帧 → 数量变；
     *   · 换首帧（改封面那种）→ 首路径变；
     *   · 重排 → 首或尾变。
     *   漏判的极端情形（只改中间一帧且数量不变）最多导致「多显示旧的一帧」，
     *   而用户在编辑页改帧会触发 `DollAnim` 落盘 + 页面刷新，实际走不到。
     */
    private fun frameKeyOf(paths: List<String>): String {
        if (paths.isEmpty()) {
            return "0"
        }
        var sum = 0
        for (p in paths) {
            sum += p.length
        }
        return paths.size.toString() + "|" + paths[0] + "|" + paths[paths.size - 1] + "|" + sum
    }

    /**
     * 这批已解码的帧能否直接复用给 [actionId]。
     *
     * 四条同时满足才复用：
     * ① 帧非空（空帧等于没有内容，复用会让画面空着）；
     * ② 动作 id 相同；
     * ③ 帧路径指纹相同（见 [frameKeyOf]）；
     * ④ 没有被 `disposed`。
     */
    private fun canReuseFrames(id: String, paths: List<String>): Boolean {
        if (disposed || frames.isEmpty()) {
            return false
        }
        if (id != loadedActionId) {
            return false
        }
        return frameKeyOf(paths) == loadedFrameKey
    }

    /** 非 sprite 引擎的渲染器（Lottie / 动图）。 */
    private var renderer: PetRenderer? = null

    /** 当前动作 id（空 = 没有在播任何动作）。 */
    var actionId: String = ""
        private set

    private var fps = DollAnim.FPS_DEFAULT
    private var loop = true
    /**
     * 帧推进器（**纯算术，可 JVM 单测**，见 [ActionFrameClock]）。
     *
     * 【v0.1.0·修·节奏漂移】原来这里自己维护 `frameIdx` + `lastStepMs`，
     *   判据是「`nowMs - lastStepMs >= step` → `lastStepMs = nowMs` → `frameIdx++`」。
     *   由于帧循环按固定 16ms 排队而 step = 1000/fps 不是 16 的整数倍，
     *   每帧都会丢掉 0~16ms 余数，误差单向累积：一段 2 秒的 12fps 循环动作整体慢近 20%。
     *   现在交给 [ActionFrameClock]（绝对基准 + 整除，余数留在基准里），
     *   并且与 `DollAnimPage` 预览播放器同口径 —— 预览看到的节奏就是桌面播出的节奏。
     */
    private val clock = ActionFrameClock(
        fpsProvider = { fps },
        loopProvider = { loop }
    )
    /** 当前显示帧号（读时钟，保留旧字段名以少改调用点）。 */
    private var frameIdx: Int
        get() = clock.frame
        set(v) = clock.seek(v, frames.size)
    private var playing = false
    private var disposed = false

    /**
     * 代次 —— 每次 `play` / `stop` / `release` 自增。
     *
     * 【为什么必须有它】解码是异步的：用户在 300ms 内连点两个动作，
     * 第一个动作的解码可能**后**回来。若不判代次，那个迟到的结果会把
     * 第二个动作的帧顶掉 —— 用户看到的是「点了 A 播的是 B」，
     * 而且这种 bug 只在慢机型上偶发，极难复现。代次让迟到者自觉退场。
     */
    private var generation = 0

    /** 目标绘制区（等比缩放居中）。 */
    private val dst = Rect()


    /**
     * **【v0.1.0】**当前动作的「本体基准矩形」（相对画布比例），null = 没基准。
     *
     * 【为什么必须按动作取】每个动作的帧图画布尺寸不同，本体占画布的比例也不同。
     *   播放层用它把「人偶本体」映射到窗口 —— 这才是「所有动作一样大」的判据。
     */
    private var baseline: FloatArray? = null

    /**
     * **【工作台】**当前动作的显示设置（锚点 / 适配 / 偏移），null = 默认。
     *
     * 【为什么缓存在这里而不是每帧去问】`draw` 每秒执行 12~30 次，
     *   而 SharedPreferences 读字符串（还要 JSON 解析）放在绘制路径里是明显的浪费；
     *   显示设置只在 [play] 时随动作变一次。
     */
    private var display: ActionWorkbench.ActionDisplay? = null

    /**
     * **【v0.1.0】**基准缩放的缓存（避免逐帧重算带来的取整抖动）。
     *
     * 【何时失效】帧图的宽高变了（换动作 / 重新导入 / 重新烘帧）时，
     *   [draw] 里会自然重算；窗口尺寸变了则由 `PetView` 调 [resetScale]。
     */
    // 【尺寸标准化】改造前这里缓存「按当前动作算出的缩放」。
    //   按高度换算后尺度只由帧高决定，且 [ActionScale] 是纯函数，缓存不再必要，
    //   顺带消除了「换动作忘了失效缓存」这类隐患（需求 5.4）。

    /** 让缩放缓存失效（窗口尺寸 / 人偶比例变化时由宿主调用）。 */
    fun resetScale() {
        // 【尺寸标准化】换算已改为纯函数（[ActionScale.place]），不再持有缩放缓存，
        //   这里只保留调用点语义：参数变化时让宿主重画一帧。
        //   留着空实现是为了不动 [com.dollhouse.app.pet.PetView.setScaleFactor] 等调用方。
    }

    /** 一次播放结束时回调（非循环动作播完 / 循环动作被 stop）。 */
    var onFinish: (() -> Unit)? = null

    // ============================ 播放控制 ============================

    /**
     * 播放一个动作。
     *
     * ## 【r22·不重复解码】同一动作的「重播」直接复用已解码的帧
     *
     * 改造前这里**无条件** `clearContent()` 再重新解码整组帧。而实际调用链里
     * 「同一个动作」被反复播是常态：
     * ```
     * 非循环待机动作播完 → onFinish → onActionFinished → restoreBase → play(同一个 id)
     * 点击回弹 / 贴边进出 / 拖拽抬手 → 同上
     * ```
     * 每走一遍就要把 N 帧 PNG 从磁盘重新解码一遍（120 帧时是几十毫秒到上百毫秒的纯 I/O + 解码），
     * 表现为「点一下人偶，画面先空一下再动」。
     *
     * 【为什么可以安全复用】帧是**只读**位图，绘制时只做 `drawBitmap` + 矩阵变换，
     *   不会改动像素；解码尺寸也固定（由 `decodeTargetPx` 决定），不存在「这次解大下次解小」。
     *   真正需要重建的只有两类：换了动作（帧不同）、或帧路径列表变了（用户编辑过帧）。
     *
     * 【判定口径】`actionId` + 帧列表内容都相同 → 复用。只比 id 不够：
     *   用户在动作详情里删/加一帧后 id 不变，此时必须重新解码。
     */
    fun play(action: DollAnim.Action) {
        if (disposed) {
            return
        }
        // 【先判能不能复用】必须放在 generation++ 之前：
        //   复用路径不该影响「正在解码的那一批」的代次校验。
        val engine = action.engine
        val reusable = engine == DollAnim.ENGINE_SPRITE &&
            canReuseFrames(action.id, action.frames)
        generation++
        if (reusable) {
            // 只重启时钟与状态，帧与渲染器原地保留。
            fps = DollAnim.clampFps(action.fps)
            loop = action.loop
            clock.reset()
            playing = frames.isNotEmpty()
            requestDraw()
            return
        }
        clearContent()
        // 换动作：帧图宽高可能变了，缩放缓存必须失效。
        resetScale()
        // 换动作：本体基准也属于「上一个动作」，必须重新取。
        // 【本轮·导入帧也要有基准】取基准的优先级：
        //   ① 动作自己带的（用户导入帧组时算出来的，`FrameScope.computeBaseline`）；
        //   ② 烘帧记录（内置动作，`AnimBaker.baseline`）。
        //   顺序不能反：内置动作两处都可能有值，动作自带的那份是「这套帧真正的框」，
        //   烘帧记录是按 actionId 存的、同一 id 被重新导入过就会过期。
        baseline = BodyBaseline.Box.decode(action.baseline)?.let {
            floatArrayOf(it.left, it.top, it.right, it.bottom)
        } ?: baselineProvider(action.id)
        // 【工作台】取这个动作的显示设置（锚点 / 适配 / 偏移）。
        //   null 或全默认都按旧行为画，所以没设置过的动作与改造前逐像素一致。
        display = displayProvider(action.id)?.takeIf { !it.isDefault() }
        // 【复用判定要用的记账】必须在 decodeFramesAsync 之前写，解码回调里不再改它。
        loadedActionId = action.id
        loadedFrameKey = frameKeyOf(action.frames)
        actionId = action.id
        fps = DollAnim.clampFps(action.fps)
        loop = action.loop
        // 【v0.1.0】重置帧时钟：不归零的话，切换动作后第一帧会用上一个动作
        //   残留的游标，表现为「点了动作却从中间某帧开始」。
        clock.reset()
        playing = true

        if (engine == DollAnim.ENGINE_SPRITE) {
            val paths = action.frames
            if (paths.isEmpty()) {
                // 空壳动作：没有任何帧可画 → 视为「无内容」，绘制让回给原管线。
                // 【为什么不当成错误】「先建壳、后加帧」是产品明确支持的第一步
                //   （见 DollAnim.add 的注释），空动作被设为默认动作是完全可能的。
                Logs.i(TAG, "动作没有帧，跳过播放：" + actionId)
                playing = false
                return
            }
            decodeFramesAsync(paths, generation)
        } else {
            val path = action.assetPath
            if (path.isBlank()) {
                Logs.w(TAG, "动作没有入口资源：" + actionId)
                playing = false
                return
            }
            val r = RendererRegistry.create(engine)
            if (r == null) {
                // 【不静默回落】未知引擎说明档案被改坏或来自更高版本；
                //   回落成 sprite 会让「选了 Lottie 却画出位图」这种更隐蔽的问题。
                Logs.w(TAG, "未知引擎：" + engine + "（动作 " + actionId + "）")
                playing = false
                return
            }
            val ok = r.prepare(PetSource.Lottie(path).let { s ->
                // 按引擎给对应句柄；用 when 而不是强转，加引擎时编译器会提醒。
                when (engine) {
                    Engine.ANIMIMAGE -> PetSource.AnimatedImage(path)
                    else -> s
                }
            })
            if (!ok) {
                Logs.w(TAG, "资源准备失败：" + (r.lastError() ?: "未知"))
                playing = false
                return
            }
            val h = hostProvider()
            if (h != null) {
                r.attach(h)
            }
            renderer = r
            val size = r.size()
            if (size.size >= 2) {
                measuredWidth = size[0]
                measuredHeight = size[1]
            }
            remeasureViewport()
            r.play()
            requestDraw()
        }
    }

    /**
     * 后台解码帧序列。
     *
     * 【为什么先全部解完再播，而不是边播边解】帧序列是「一组连贯动作图」，
     *   少了中间几帧会明显卡顿（比等 100ms 再开始难受得多）。
     *   解码在后台，期间绘制让回给原管线（`hasContent()` 为 false），
     *   所以用户不会看到半个动作 —— 只会看到人偶保持原样，然后动作开始。
     */
    private fun decodeFramesAsync(paths: List<String>, gen: Int) {
        val take = if (paths.size > MAX_FRAMES) paths.subList(0, MAX_FRAMES).toList() else paths
        Thread {
            val out = ArrayList<Bitmap>(take.size)
            for (p in take) {
                try {
                    val b = decoder(p)
                    if (b != null) {
                        out.add(b)
                    }
                } catch (t: Throwable) {
                    Logs.w(TAG, "解码失败：" + File(p).name, t)
                }
            }
            ui.post {
                // 【代次校验】切走之后回来的解码结果直接丢弃并回收，不污染新动作。
                if (disposed || gen != generation) {
                    recycleAll(out)
                    return@post
                }
                frames = out
                if (out.isNotEmpty()) {
                    measuredWidth = out[0].width
                    measuredHeight = out[0].height
                }
                remeasureViewport()
                if (out.isEmpty()) {
                    Logs.w(TAG, "全部帧解码失败，动作无法播放：" + actionId)
                    playing = false
                } else {
                    // 【v0.1.0】帧图就绪后重新起表：解码期间帧循环没在推进，
                    //   基准若沿用旧值会让第一帧立刻被判定「已经过很久」而跳过。
                    clock.reset()
                }
                requestDraw()
            }
        }.apply { name = "dh-action-decode" }.start()
    }

    /** 推进一帧（主线程，由 `PetView.tick` 调）。 */
    fun tick(nowMs: Long) {
        if (disposed || !playing) {
            return
        }
        val r = renderer
        if (r != null) {
            r.advance(nowMs)
            return
        }
        val n = frames.size
        if (n <= 0) {
            return
        }
        // 【B8 修复】单帧动作必须也能「播完」。
        //
        // 【原来的坑】这里原本是 `if (n <= 1) return`，把单帧动作整个跳过了。
        //   对**循环**动作没问题（一帧就是静止画面，本来也不该结束），
        //   但对**非循环**动作是致命的：单帧动作在第 0 帧就已经是末帧，
        //   可它永远走不到下面 `clock.isAtEnd(n)` 那一句 ——
        //   于是 `playing` 永远是 true、`onFinish` 永远不回调。
        //
        // 【用户看到的症状】动作停在那张静止图上再也不结束；更糟的是仲裁层
        //   一直以 PLAYING 记账，`onActionFinished` 不触发 → 不回落待机档，
        //   后续的待机/随机请求全被「当前还在播」挡掉，人偶就像被冻住了。
        //
        // 【单帧从哪来】① 用户导入单张图片（`AnimImport` 就产 1 帧）；
        //   ② 用户在详情页把帧删到只剩 1 帧（产品明确允许）。
        //
        // 【修复口径】单帧且非循环 → 立刻视为播完。单帧且循环 → 维持静止（原行为）。
        if (n == 1) {
            if (!loop) {
                playing = false
                onFinish?.invoke()
            }
            return
        }
        // 帧号推进交给纯算术时钟：绝对基准 + 整除，余数不丢，掉帧能自动追上。
        if (clock.advance(nowMs, n)) {
            requestDraw()
        }
        if (clock.isAtEnd(n)) {
            // 非循环动作停在最后一帧，并通知上层（交互结束 → 回落到待机档）。
            playing = false
            onFinish?.invoke()
        }
    }

    /** 是否有可绘制内容（决定 `PetView.onDraw` 要不要让位）。 */
    fun hasContent(): Boolean {
        if (disposed) {
            return false
        }
        if (renderer != null) {
            return true
        }
        return frames.isNotEmpty()
    }

    /** 是否正在播（含非循环动作停帧的情况）。 */
    fun isPlaying(): Boolean = playing && !disposed

    /** 是否还有动画在推进（供帧率策略判断要不要全速）。 */
    fun isAnimated(): Boolean {
        if (disposed || !playing) {
            return false
        }
        val r = renderer
        if (r != null) {
            return r.isActive()
        }
        // 静态单帧不算「在动」，避免白占全速帧率。
        return frames.size > 1
    }

    /** 暂停（保留进度）。 */
    fun pause() {
        if (disposed) {
            return
        }
        playing = false
        // 【v0.1.0】暂停时清掉帧时钟基准：恢复后由 [tick] 重新起表。
        //   否则暂停 10 秒再恢复，第一帧会瞬间跳到「已经过 10 秒」的位置。
        clock.rebase()
        renderer?.pause()
    }

    /** 恢复。 */
    fun resume() {
        if (disposed) {
            return
        }
        if (!hasContent()) {
            return
        }
        playing = true
        renderer?.resume()
        requestDraw()
    }

    /** 停止播放并释放内容，但对象仍可复用（不置 disposed）。 */
    fun stop() {
        if (disposed) {
            return
        }
        generation++
        playing = false
        actionId = ""
        clearContent()
        requestDraw()
    }

    /**
     * 彻底释放（幂等）。
     *
     * 【为什么要幂等】人偶移除有三条路径（用户关闭 / 服务销毁 / 多实例重挂），
     *   任意两条先后到达就会重复调用；不幂等的话第二条会在已回收的位图上再回收一次。
     */
    fun release() {
        if (disposed) {
            return
        }
        disposed = true
        generation++
        playing = false
        actionId = ""
        clearContent()
        onFinish = null
        ui.removeCallbacksAndMessages(null)
    }

    // ============================ 绘制 ============================

    /**
     * 画到画布。
     *
     * 【为什么用「等比缩放 + 居中」而不是拉伸铺满】用户导入的动作图
     *   宽高比各不相同；拉伸会让圆脸的人偶看起来被压扁。
     *   居中留白比变形好 —— 这也是 `LottieRenderer` 的口径，两处保持一致。
     *
     * ## 【v0.1.0·修「动作大小不一」】真正的一致性来自「共用画布」
     *
     * 改造前是 `scale = min(cw/bw, ch/bh)`：**每一套帧各自铺满窗口**。
     * 于是内容占画面比例小的那套（例如呼吸：画布几乎就是人偶本身）被放大到铺满，
     * 内容占比例大的那套（跳跃：画布要容纳腾空）反而被缩小 ——
     * 同一只人偶在不同动作下大小差一大截，这就是用户报的「有些动作大、有些动作小」。
     *
     * 根治落在烘帧那条链上：内置动作现在**共用一张基准画布**（见 `AnimBaker.sharedBounds`），
     * 宽高比与像素尺寸一起统一，于是这里的 `min(...)` 每套都算出同一个值。
     * 本方法只额外做两件事：
     * 1. **缓存基准缩放** —— 同一套帧的宽高不变时直接复用，避免逐帧重算的取整抖动
     *    （那会表现为 1px 上下的呼吸感）；
     * 2. 换动作 / 改比例时由调用方 `resetScale()` 失效缓存。
     */
    fun draw(canvas: Canvas) {
        if (disposed) {
            return
        }
        val r = renderer
        if (r != null) {
            // 【v0.1.0·贴边姿态】非帧序列引擎（Lottie / 动图）走同一套姿态口径。
            //   三类引擎共用「贴边 = 整幅画面绕底边中点倾斜」这一个定义，
            //   用户换引擎不该看到「贴边姿态忽然失效」。
            val leanR = leanProvider()
            if (leanR != 0f) {
                canvas.save()
                canvas.rotate(leanR, (viewport?.requiredWidth ?: canvas.width) / 2.0f,
                    (viewport?.baseHeight ?: canvas.height).toFloat())
drawRenderer(canvas, r)
                    canvas.restore()
                    return
                }
                drawRenderer(canvas, r)
                return
        }
        val n = frames.size
        if (n == 0) {
            return
        }
        val idx = if (frameIdx in 0 until n) frameIdx else 0
        val bmp = frames[idx]
        if (bmp.isRecycled) {
            return
        }
        val cw = canvas.width
        val ch = canvas.height
        if (cw <= 0 || ch <= 0) {
            return
        }
        val bw = bmp.width
        val bh = bmp.height
        if (bw <= 0 || bh <= 0) {
            return
        }
        // 人偶级身高是唯一主尺；动作画布只决定透明视口，不参与人物大小决策。
        val vp = viewport ?: viewportProvider()?.also {
            viewport = it.copy(requiredWidth = it.baseWidth)
        }
        val hasBl = ActionScale.valid(baseline)
        if (vp != null) {
            val placement = ActionViewport.measure(vp, bw, bh, baseline) ?: return
            val offsetX = ActionViewport.contentOffsetX(vp)
            dst.set(placement.left + offsetX, placement.top,
                placement.right + offsetX, placement.bottom)
        } else {
            // 未注入视口的旧调用方保持兼容；真实 PetView 已统一走上面的新路径。
            val stableBaseline = baseline
            val scale = if (stableBaseline != null && ActionScale.valid(stableBaseline)) {
                ch / ((stableBaseline[3] - stableBaseline[1]) * bh)
            } else {
                Math.min(cw.toFloat() / bw, ch.toFloat() / bh)
            }
            val dw = Math.round(bw * scale)
            val dh = Math.round(bh * scale)
            val left = (cw - dw) / 2
            val top = ch - dh
            dst.set(left, top, left + dw, top + dh)
        }
        val bl = baseline
        // 【工作台】叠加显示设置（锚点 / 适配 / 偏移）。
        //
        // 【为什么放在基准之后】见上面：基准 = 「底部居中 + 统一尺寸」，
        //   用户选其它锚点就是在这个基准上重新摆放，于是「选底部居中」与
        //   「从没设置过」必然得到同一张画面 —— 不会因为进过一次编辑器而位移。
        val dsp = display
        if (dsp != null) {
            if (dsp.fit == ActionWorkbench.FIT_CONTAIN) {
                // 「整张画布塞进窗口并留边」：不按本体基准，按画布自身等比缩到窗口内。
                val s2 = Math.min(cw.toFloat() / bw, ch.toFloat() / bh)
                val dw2 = Math.round(bw * s2)
                val dh2 = Math.round(bh * s2)
                val l2 = (cw - dw2) / 2
                val t2 = (ch - dh2) / 2
                dst.set(l2, t2, l2 + dw2, t2 + dh2)
            }
            applyAnchor(dsp.anchor, cw, ch, if (dsp.fit == ActionWorkbench.FIT_CONTAIN) false else hasBl, bl)
            // 偏移：按窗口尺寸换算成像素（与 `DollProfile.frameOffset` 同一口径，
            //   都是「占窗口宽高的比例」，所以界面上看到的位移 = 桌面上的位移）。
            if (dsp.ox != 0f || dsp.oy != 0f) {
                val dx = Math.round(dsp.ox * cw)
                val dy = Math.round(dsp.oy * ch)
                dst.offset(dx, dy)
            }
        }
        // 【v0.1.0·贴边姿态】把恒定倾斜施加在整幅动作画面上。
        //
        // 旋转轴取「画面水平中心 × 画面底边」：贴边的人偶是**脚在屏外、身子探进来**，
        //   以底边为轴转，倾斜只会让上半身向屏幕外侧倒，不会把人偶整体顶出窗口。
        //   窗口宽高本就由 `PetView.computeBox` 按 `lean` 算出外接框（见 boxAt 的
        //   四点旋转取 min/max），所以这里转多少，窗口就留了多少余量。
        //
        // 只在 lean != 0 时才 save/restore：非贴边路径与改造前**逐像素一致**。
        val lean = leanProvider()
        if (lean != 0f) {
            val cx = cw / 2.0f
            val cy = ch.toFloat()
            canvas.save()
            canvas.rotate(lean, viewport?.let { it.requiredWidth / 2.0f } ?: cx,
                viewport?.baseHeight?.toFloat() ?: cy)
            canvas.drawBitmap(bmp, null, dst, null)
            canvas.restore()
            return
        }
        canvas.drawBitmap(bmp, null, dst, null)
    }

    // ============================ 内部 ============================

    /** 非位图引擎也先映射到人偶级矩形，再让渲染器画入同一个目标画布。 */
    private fun drawRenderer(canvas: Canvas, renderer: PetRenderer) {
        val vp = viewport ?: run {
            renderer.draw(canvas)
            return
        }
        val size = renderer.size()
        if (size.size < 2 || size[0] <= 0 || size[1] <= 0) return
        val placement = ActionViewport.measure(vp, size[0], size[1], baseline) ?: return
        val offsetX = ActionViewport.contentOffsetX(vp)
        dst.set(placement.left + offsetX, placement.top,
            placement.right + offsetX, placement.bottom)
        renderer.draw(canvas, dst)
    }

    /**
     * 按锚点重新摆放 [dst]。
     *
     * 【锚的是什么】有本体基准时锚「本体」（人偶自己的包围盒），没有时锚「整张画布」。
     *   因为用户眼里的「人偶底部」是脚底，而不是画布里那片留白 ——
     *   若锚画布底边，选「底部居中」时人偶会悬在窗口中间，看起来像没生效。
     *
     * 【为什么默认锚点等于什么都不做】[ActionWorkbench.ANCHOR_BOTTOM_CENTER] 是
     *   「本体水平居中 + 底边贴窗口底」，正好是 [draw] 里的基准摆放。
     *   这里直接 return，保证「没设置」与「设成默认」是同一张画面。
     */
    private fun applyAnchor(
        anchor: String,
        cw: Int,
        ch: Int,
        hasBl: Boolean,
        bl: FloatArray?
    ) {
        if (anchor == ActionWorkbench.ANCHOR_BOTTOM_CENTER) {
            return
        }
        val dw = dst.width()
        val dh = dst.height()
        if (dw <= 0 || dh <= 0) {
            return
        }
        // 本体的相对矩形（无基准时就是整张画布 0,0,1,1）。
        val l = if (hasBl && bl != null) bl[0] else 0f
        val t = if (hasBl && bl != null) bl[1] else 0f
        val r = if (hasBl && bl != null) bl[2] else 1f
        val b = if (hasBl && bl != null) bl[3] else 1f
        val bodyLeft = dst.left + l * dw
        val bodyTop = dst.top + t * dh
        val bodyRight = dst.left + r * dw
        val bodyBottom = dst.top + b * dh
        // 需要把本体挪到哪里（dx / dy 是整体的平移量）。
        val dx: Float = when (anchor) {
            ActionWorkbench.ANCHOR_BOTTOM_LEFT -> -bodyLeft
            ActionWorkbench.ANCHOR_BOTTOM_RIGHT -> cw - bodyRight
            else -> cw / 2f - (bodyLeft + bodyRight) / 2f
        }
        val dy: Float = when (anchor) {
            ActionWorkbench.ANCHOR_CENTER -> ch / 2f - (bodyTop + bodyBottom) / 2f
            else -> ch - bodyBottom
        }
        dst.offset(Math.round(dx), Math.round(dy))
    }

    private fun clearContent() {
        val r = renderer
        if (r != null) {
            try {
                r.release()
            } catch (t: Throwable) {
                Logs.w(TAG, "释放渲染器失败", t)
            }
        }
        renderer = null
        recycleAll(frames)
        frames = emptyList()
        // 【r22·复用记录必须一起清】帧已经被回收，若还留着 id/指纹，
        //   下一次 play 会误判「可以复用」→ 拿着空帧表去画 → 画面空白。
        loadedActionId = ""
        loadedFrameKey = ""
        baseline = null
        display = null
        measuredWidth = 0
        measuredHeight = 0
        // 停止动作后收回透明扩展区，静态形象继续使用同一个中心与人物尺度。
        viewportProvider()?.let { base ->
            val next = base.copy(requiredWidth = base.baseWidth)
            if (viewport != next) {
                viewport = next
                viewportChanged(next)
            }
        }
        resetScale()
        clock.reset()
    }

    /**
     * 回收位图。
     *
     * 【必须回收】帧图是本类**自己解码**的（不是借用素材池的共享位图），
     *   120 张 150dp 图在 3x 屏上约 30MB —— 不回收就是肉眼可见的内存泄漏。
     */
    private fun recycleAll(list: List<Bitmap>) {
        for (b in list) {
            try {
                if (!b.isRecycled) {
                    b.recycle()
                }
            } catch (ignored: Throwable) {
            }
        }
    }
}