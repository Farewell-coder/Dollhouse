package com.dollhouse.app.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.DollAnim
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 【职责】把内置动作**用真实渲染管线**逐帧烘成 PNG，落盘成可直接播放的帧序列。
 *
 * 【为什么必须烘，不能「实时演」】本工程的人偶是网格形变实时渲染
 *   （[PetView.buildMesh] + `drawBitmapMesh`），身上没有帧图素材。
 *   而需求要的是「点开之后能通过滑动看到每一帧的图片，并且能编辑 / 添加 / 修改 / 调速」——
 *   这要求动作必须是**一串看得见、可滑动、可增删的图片**。
 *   所以内置动画按 [DollAnim.Plan] 的时间线推进，把每一帧渲染成 PNG。
 *
 * 【为什么帧图与桌面上跑的效果一致】烘帧走的是 [PetView.bakeDraw]，
 *   它的几何变换序列与 onDraw 逐参数一致；时间线参数也照搬
 *   PetAnimator / BlinkTimeline 里的真实曲线（呼吸 1.7rad/s、眨眼 70/50/100ms…）。
 *   于是「预览里的看到的每一帧」就是「桌面上真实的那一帧」。
 *
 * 【线程模型·关键】真机实测：**PNG 编码一帧约 150ms**，一整套 152 帧要 20 秒以上。
 *   而渲染（drawBitmapMesh）每帧只要几毫秒，且**必须**在主线程（View / 绘制对象）。
 *   所以本类把两件事彻底拆开：
 *     ① **渲染**：把每一帧画进位图，全部在**同一个主线程批次**里跑完（几毫秒 × N）；
 *     ② **编码**：位图 → PNG → 文件，全部在**调用方线程**跑（几十毫秒 × N）。
 *   这样主线程只被占用「渲染」那一段（总量百毫秒级），设置页不会卡二十几秒。
 *
 * 【幂等】同一动作只要帧文件齐了就直接返回路径，不重复烘。
 */
object AnimBaker {

    private const val LOG_TAG = "DollhouseBaker"

    /**
     * 确保某个内置动作的帧图已就绪。可从任意线程调用。
     *
     * @param scaleFactor 渲染用的缩放系数（与桌宠当前比例一致；1.0 = 150dp 宽）。
     * @return 帧图路径列表（按播放顺序）；失败返回空列表。
     */
    fun ensure(ctx: Context, dollId: String, actionId: String, scaleFactor: Float): List<String> {
        val plan = DollAnim.planOf(actionId) ?: return emptyList()
        val dir = DollAnim.framesDir(ctx, dollId, actionId)
        val existing = collectFrames(dir, plan.frames)
        // 【r13·烘帧版本】幂等判据里必须带版本号，否则「修好了裁切、但盘上还是旧帧」
        //   会一直被当成「已经烘过」而跳过 —— 用户升级后看到的仍是裁掉的人偶。
        //   版本对不上就整体重烘一次（旧的 PNG 会被同名覆盖）。
        if (existing.size == plan.frames && bakeVersion(ctx, dollId, actionId) == BAKE_VERSION) {
            return existing
        }
        return try {
            val scale = if (scaleFactor > 0f) scaleFactor else 1.0f
            // ① 主线程：量尺寸 + 把每一帧渲染成位图（不落盘）
            val frames = onMain { renderAll(ctx, dollId, plan, scale) } ?: return emptyList()
            if (frames.size != plan.frames) {
                return emptyList()
            }
            // ② 调用方线程：编码 + 落盘（这一步才是慢的）
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val out = ArrayList<String>(frames.size)
            for (i in frames.indices) {
                val f = File(dir, name(i))
                if (!encode(frames[i], f)) {
                    return emptyList()
                }
                out.add(f.absolutePath)
            }
            setBakeVersion(ctx, dollId, actionId, BAKE_VERSION)
            out
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "bake failed: " + actionId, t)
            emptyList()
        }
    }

    /* ============================ 烘帧版本 ============================ */

    /**
     * 【r13】烘帧管线的版本号。**每次改动烘帧几何 / 画布口径都必须 +1。**
     *
     * 【为什么要它】帧图是缓存在盘上的：只改代码不改版本，用户升级后 [ensure] 会认为
     *   「帧已经齐了」而直接返回旧帧 —— 修好的几何永远看不到。
     *   历史：v1 = 初版（画布留白四边不等 + 包围盒漏算旋转 → 人偶被裁）。
     *        v2 = 加 [PetView.bakeBounds] 按变换链量包围盒，但矩阵误用 pre*，
     *             等价于把变换链倒过来 → 量的位置与画出的位置对不上，**仍然裁**。
     *        v3 = 矩阵改回 post*（与 Canvas 的 preConcat 语义一致），裁切才真正修好。
     */
    private const val BAKE_VERSION = 3

    private fun bakeVerKey(dollId: String, actionId: String): String {
        return "doll_bakev_" + dollId + "_" + actionId
    }

    private fun bakeVersion(ctx: Context, dollId: String, actionId: String): Int {
        return try {
            PetPrefs.get(ctx).getInt(bakeVerKey(dollId, actionId), 0)
        } catch (t: Throwable) {
            0
        }
    }

    private fun setBakeVersion(ctx: Context, dollId: String, actionId: String, v: Int) {
        try {
            PetPrefs.get(ctx).edit().putInt(bakeVerKey(dollId, actionId), v).apply()
        } catch (t: Throwable) {
        }
    }

    /**
     * 切到主线程执行并等结果。
     *
     * 【为什么要它】[PetView] 构造、`setSprite`、`buildMesh`、`Canvas.drawBitmapMesh`
     *   全都触碰 View / 绘图表，Android 要求在主线程。而烘帧整体是耗时活，
     *   调用方天然想放后台线程 —— 由本方法把两边的诉求对齐：
     *   调用方在后台等着，渲染在主线程跑。
     *
     * 【防死锁】已经在主线程时直接执行（否则 post + await 会把自己锁死）。
     * 【超时】给 60 秒兜底；超时返回 null，由 [ensure] 变成「空列表」而不是卡住页面。
     */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<Any>(1)
        Handler(Looper.getMainLooper()).post {
            try {
                box[0] = block()
            } catch (t: Throwable) {
                Logs.w(LOG_TAG, "render on main failed", t)
            } finally {
                latch.countDown()
            }
        }
        val ok = latch.await(60, TimeUnit.SECONDS)
        if (!ok) {
            Logs.w(LOG_TAG, "render timed out", null)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T?
    }

    /** 目录里已有的、且数量对得上的帧（避免半截产物被当成完整产物）。 */
    private fun collectFrames(dir: File, want: Int): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until want) {
            val f = File(dir, name(i))
            if (!f.exists() || f.length() <= 0L) {
                return emptyList()
            }
            out.add(f.absolutePath)
        }
        return out
    }

    private fun name(i: Int): String {
        return String.format("f%04d.png", i)
    }

    /**
     * 【主线程】把整套动作渲染成位图列表（不落盘）。
     *
     * 【流程】① 摆一个离屏的 PetView → ② 扫一遍所有帧的网格顶点求包围盒 →
     *   ③ 定画布尺寸（含留白）→ ④ 逐帧画进位图。
     *
     * 【为什么先扫一遍】呼吸的上下浮动、跳跃的腾空、张望的倾角都会让内容超出
     *   「静态矩形」。先量包围盒，才能保证每一帧都完整落在画布内、且**所有帧共用同一坐标系**
     *   （否则播放时会左右乱跳）。
     */
    private fun renderAll(ctx: Context, dollId: String, plan: DollAnim.Plan,
                          scaleFactor: Float): List<Bitmap> {
        val doll = DollStore.byId(ctx, dollId)
        val bmp = DollStore.spriteOf(ctx, doll) ?: return emptyList()
        // 离屏测量用的人偶：不挂窗口，只为拿到与该人偶一致的比例与度量。
        val pv = PetView(ctx)
        pv.setScaleFactor(scaleFactor)
        pv.setSprite(bmp)

        val n = plan.frames
        if (n <= 0) {
            return emptyList()
        }
        val params = Array(n) { FloatArray(12) }

        // ---------- ① 生成时间线参数 ----------
        for (i in 0 until n) {
            timeline(plan, i, n, params[i])
        }

        // ---------- ② 量包围盒（ox = oy = 0 口径） ----------
        // 【口径说明】[PetView.bakeBounds] 返回的是「把 ox / oy 视作 0 时，内容在画布上的落点范围」。
        //   真实的落点是 `rect + (ox, oy)` —— 所以后面要把 rect 平移进画布时，
        //   ox = pad - rect.left（**不是 rect.left - pad**）。这一步的符号是整件事的关键，
        //   写反了内容会被整体推出画布，表现为「左边和上面被截掉一截」。
        val rect = RectF()
        var any = false
        val expr = exprOf(plan)
        for (i in 0 until n) {
            val r = RectF()
            if (!pv.bakeBounds(params[i], expr, r)) {
                continue
            }
            if (!any) {
                rect.set(r)
                any = true
            } else {
                rect.union(r)
            }
        }
        if (!any || rect.width() <= 0f || rect.height() <= 0f) {
            return emptyList()
        }

        // ---------- ③ 逐帧渲染（含触边兜底） ----------
        // 【留白】四边等值 26dp：内容天然落在画布正中，预览用 ContentScale.Fit 铺进圆角框即居中。
        val pad = pv.dp(26.0f).toInt()
        // 【扩展系数】正常情况下第一轮就通过；只有「包围盒推导漏了某个变换」时才会扩大重来。
        var grow = 1.0f
        var result: ArrayList<Bitmap>? = null
        for (attempt in 0 until 3) {
            val w = Math.ceil((rect.width() * grow).toDouble()).toInt() + pad * 2
            val h = Math.ceil((rect.height() * grow).toDouble()).toInt() + pad * 2
            if (w <= 0 || h <= 0 || w > MAX_CANVAS || h > MAX_CANVAS) {
                return emptyList()
            }
            // 扩大时以 rect 中心为锚：新增的余量四周均分，内容不会偏。
            val growPadX = rect.width() * (grow - 1.0f) / 2.0f
            val growPadY = rect.height() * (grow - 1.0f) / 2.0f
            val originX = pad - (rect.left - growPadX)
            val originY = pad - (rect.top - growPadY)

            val frames = ArrayList<Bitmap>(n)
            var edge = false
            for (i in 0 until n) {
                val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(frame)
                // 透明底：帧图要能叠在任意背景上。
                canvas.drawColor(Color.TRANSPARENT)
                val t = if (expr == 0) 0f else i / Math.max(1, n - 1).toFloat()
                pv.bakeDraw(canvas, params[i], expr, t, originX, originY)
                // 【物理层判据】扫最外几像素有没有内容 —— 有就说明这一轮还是裁了，
                //   与「包围盒推导对不对」无关，保证交付出去的帧一定完整。
                edge = edge || touchesEdge(frame)
                frames.add(frame)
            }
            if (!edge) {
                result = frames
                break
            }
            // 触边：这一轮的位图作废，扩大画布重来（先回收，避免内存峰值翻倍）。
            for (f in frames) {
                f.recycle()
            }
            grow *= 1.45f
            Logs.w(LOG_TAG, "bake edge touched, retry with grow=" + grow + " action=" + plan.id)
        }
        return result ?: emptyList()
    }

    /** 触边检测的条带宽度（像素）：转成 px 后 2 已足够灵敏，且扫描代价可忽略。 */
    private const val EDGE_PX = 2

    /** 画布尺寸上限：异常大的比例会直接 OOM。 */
    private const val MAX_CANVAS = 2048

    /**
     * 【物理层判据】位图最外 [EDGE_PX] 圈里是否有非透明内容。
     * 【为什么不用全图扫描】只需要「有没有贴边」这一个布尔值，
     *   扫四条边带（而不是整图）就能判定，代价从「百万像素」降到「几千像素」。
     */
    private fun touchesEdge(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= EDGE_PX * 2 || h <= EDGE_PX * 2) {
            return true
        }
        val m = EDGE_PX
        val band = IntArray(w * m)
        // 上边带
        bmp.getPixels(band, 0, w, 0, 0, w, m)
        if (hasOpaque(band)) {
            return true
        }
        // 下边带
        bmp.getPixels(band, 0, w, 0, h - m, w, m)
        if (hasOpaque(band)) {
            return true
        }
        // 左边带（stride = m，因为每行只取 m 个像素）
        val side = IntArray(h * m)
        bmp.getPixels(side, 0, m, 0, 0, m, h)
        if (hasOpaque(side)) {
            return true
        }
        // 右边带
        val side2 = IntArray(h * m)
        bmp.getPixels(side2, 0, m, w - m, 0, m, h)
        return hasOpaque(side2)
    }

    /** 数组里是否存在非全透明像素（alpha != 0）。 */
    private fun hasOpaque(px: IntArray): Boolean {
        for (p in px) {
            if ((p ushr 24) != 0) {
                return true
            }
        }
        return false
    }

    /**
     * 把位图写成 PNG（**调用方线程**，通常是 IO 线程）。
     *
     * 【为什么单独抽出来】「渲染」与「编码落盘」要各自可判错：编码失败必须终止整批，
     *   否则会留下半截帧序列，播放时中间缺帧。
     */
    private fun encode(bmp: Bitmap, f: File): Boolean {
        return try {
            java.io.FileOutputStream(f).use { os ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            f.exists() && f.length() > 0L
        } catch (t: Throwable) {
            Logs.w(LOG_TAG, "encode failed: " + f.name, t)
            false
        } finally {
            try {
                bmp.recycle()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 表情动作对应的表情 id；非表情动作返回 0。 */
    private fun exprOf(plan: DollAnim.Plan): Int = when (plan.kind) {
        DollAnim.KIND_SURPRISED -> PetView.EXPR_SURPRISED
        DollAnim.KIND_HAPPY -> PetView.EXPR_HAPPY
        DollAnim.KIND_DIZZY -> PetView.EXPR_DIZZY
        else -> 0
    }

    /**
     * 生成第 [i] 帧的时间线参数（共 [n] 帧）。
     *
     * 【曲线来源】全部照搬运行时的真实系数，做到「预览 = 桌面」：
     *   - 呼吸：PetAnimator.tick 里 phase 按 1.7 rad/s 推进；
     *   - 眨眼：BlinkTimeline 的 70 / 50 / 100ms 三段；
     *   - 待机小动作：PetAnimator.tickIdle 的 sin(πt) 包络；
     *   - 跳跃：jumpT 在 0.42s 内从 0 走到 1（onDraw 用 sin(πt)·jumpAmp）；
     *   - 落地：landT 在 0.38s 内走完（onDraw 用 sin(πt)·AHOGE_RU 压扁）。
     *
     * 【r13·逐个核对语义】用户要求「认真辨利每个动作属于哪个属性」之外，
     *   还要求**每个动作烘出来的帧真的体现它的语义**（例：「迷糊」要有 ？ 冒出来，
     *   不能 11 帧长得一样）。所以下面每个 kind 都推的是**它自己的那条曲线**，
     *   而不是「都只改一下呼吸相位」。
     */
    private fun timeline(plan: DollAnim.Plan, i: Int, n: Int, v: FloatArray) {
        // 默认值：jumpT / landT = -1 表示「不在进行」，其余 0。
        for (k in v.indices) {
            v[k] = 0.0f
        }
        v[1] = -1.0f
        v[5] = -1.0f
        v[4] = 1.0f
        val t = if (n <= 1) 0.0f else i / (n - 1).toFloat()
        val sinPi = Math.sin(t * Math.PI).toFloat()
        val sin2Pi = Math.sin(t * Math.PI * 2.0).toFloat()
        val sin4Pi = Math.sin(t * Math.PI * 4.0).toFloat()
        when (plan.kind) {
            // ---------- 常驻动画 ----------
            DollAnim.KIND_BREATH -> v[0] = t * 6.2831855f
            DollAnim.KIND_BLINK -> {
                // 把 BlinkTimeline 的整轮映射到 n 帧上。
                val ms = t * BlinkTimeline.TOTAL_MS
                val p = BlinkTimeline.phaseAt(ms)
                v[9] = if (p < 0.0f) 0.0f else p
            }
            // 视线漂移：gaze 是网格里的 GAZE_AMP 横向位移，推两轮才看得出在「飘」。
            DollAnim.KIND_GAZE -> {
                v[11] = sin2Pi
                v[0] = t * 6.2831855f
            }
            // 尾巴摆动：buildMesh 里尾巴项按 phase*2.6 的 sin 摆动 ——
            //   给足相位跨度（两轮）才能看到「左右荡」。
            DollAnim.KIND_TAIL -> v[0] = t * 6.2831855f * 2.0f
            // 呆毛摆动：buildMesh 里呆毛项按 phase*3.4 的 sin 晃，比尾巴快。
            DollAnim.KIND_AHOGE -> v[0] = t * 6.2831855f * 3.0f
            // 头发波动：buildMesh 里头发项按 phase*1.6 的 sin，且是细密波 ——
            //   这里再叠一点纵向起伏，肉眼看得出「一片在动」。
            DollAnim.KIND_HAIR -> {
                v[0] = t * 6.2831855f * 3.0f
                v[4] = 1.0f + 0.012f * sin2Pi
            }

            // ---------- 待机小动作 ----------
            DollAnim.KIND_STRETCH -> {
                v[4] = 1.0f + 0.05f * sinPi
                v[3] = 1.0f * sinPi
            }
            DollAnim.KIND_LOOK -> {
                v[11] = sin2Pi
                v[9] = 0.0f
            }
            // 眯眼：idleEyeScale 直接进网格的 eyeQ（PetAnimator 的 3 号分支正是这个算法），
            //   眼睛会被压扁成一条缝 —— 语义与「眯眼」一致。
            DollAnim.KIND_SQUINT -> v[10] = 1.0f - (sinPi * PetView.EYE_V)
            DollAnim.KIND_NOD -> v[4] = 1.0f - (sin4Pi * 0.05f)
            DollAnim.KIND_HOP -> v[3] = 1.0f * sinPi

            // ---------- 交互动作 ----------
            DollAnim.KIND_JUMP -> v[1] = t
            // 拖拽：dragLift 驱动「抬高 + 整体放大」；再叠一点倾斜，像被拎着晃。
            DollAnim.KIND_DRAG -> {
                v[2] = 1.0f
                v[7] = sin2Pi * 6.0f
            }
            // 落地：landT 驱动「压扁」，onDraw 用 sin(πt)·AHOGE_RU。
            DollAnim.KIND_LAND -> v[5] = t
            // 贴边偷看：lean 是 onDraw 的整只旋转（EDGE_LEAN=25°），
            //   推成「探出去 → 停 → 缩回来」，不是整圈转。
            DollAnim.KIND_PEEK -> v[6] = PetView.EDGE_LEAN * sinPi
            // 趴姿：身体前倾压低 + 眼睛半闭，与 peek 的趴姿渲染口径一致。
            DollAnim.KIND_PERCH -> {
                v[6] = PetView.EDGE_LEAN * 0.5f * sinPi
                v[7] = 8.0f * sinPi
                v[10] = 1.0f - 0.5f * sinPi
            }

            // ---------- 表情（头顶冒符号）----------
            // 【为什么表情也要给点身体动】原来三个表情只设了 v[0]（呼吸相位），
            //   11 帧肉眼几乎一样 —— 用户报的「1-4 帧缩略图看起来完全相同」。
            //   现在每个表情都叠一个**自己特有的**身体反应，缩略图一眼能分辨。
            DollAnim.KIND_SURPRISED -> {
                // 惊讶：先弹一下（跳起来一点）再落回。
                v[0] = t * 1.2f
                v[3] = 1.0f * sinPi
                v[4] = 1.0f + 0.06f * sinPi
            }
            DollAnim.KIND_HAPPY -> {
                // 开心：连续小弹跳（两轮），像蹦跶。
                v[0] = t * 1.2f
                v[3] = Math.abs(sin2Pi) * 1.0f
            }
            DollAnim.KIND_DIZZY -> {
                // 迷糊：身体左右打晃 + 轻微倾斜，正是「晕」的样子。
                v[0] = t * 1.2f
                v[7] = sin2Pi * 9.0f
                v[6] = sin2Pi * 5.0f
            }
        }
    }
}