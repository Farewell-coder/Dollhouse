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
        // 【v0.1.0】版本号里还编进了比例与精灵图尺寸：用户调大人偶或换形象之后，
        //   盘上的旧帧是旧比例下渲的，再按新窗口铺满就会糊（或被裁）。
        val stamp = bakeStamp(ctx, dollId, scaleFactor)
        if (existing.size == plan.frames && bakeVersion(ctx, dollId, actionId) == stamp) {
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
            setBakeVersion(ctx, dollId, actionId, stamp)
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
     *        v4 = 【v0.1.0】**画布口径改为「全体内置动作共用一张基准画布」**。
     *             旧帧是按各自包围盒出的，播放时各自铺满窗口 → 小内容动作被放大、
     *             大动作被缩小（用户报的「有些动作大、有些动作小」）。必须重烘才生效。
     *        v5 = 【v0.1.0】**逐帧参数补齐 `idleEyeScale = 1`**。
     *             `timeline()` 与本体基准都漏了这一位，留 0 会让 `buildMesh` 把眼睛
     *             纵向压扁到极值（`min = min(base, 0) = 0` → `f8 = 1`），
     *             画出来的每一帧眼睛都是扁的。旧帧必须重烘才会正确。
     *        v6 = 【v0.1.0】**贴边帧不再自带倾斜**（16 帧 → 32 帧，10fps → 12fps）。
     *             姿态改由 `ActionPlayerCore.leanProvider` 按 `lean` 恒定施加；
     *             帧里带 `EDGE_LEAN × sin(πt)` 会让循环播成「站直 → 歪下 → 站直」。
     *             旧帧带着那次倾斜，必须重烘才正确。
     */
    private const val BAKE_VERSION = 6

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
     * **【v0.1.0】**这次烘帧的「写入戳」—— 版本号 + 比例 + 精灵图尺寸，一起编进一个整数。
     *
     * 【为什么不能用固定常量】幂等判断只看「帧文件齐不齐」是不够的：
     *   用户把人偶从 100% 调到 150%（或换了形象）之后，盘上那套帧还是旧比例渲的，
     *   播放时按新窗口等比铺满 → 要么发虚，要么边缘被裁。带上比例与尺寸，
     *   条件一变就自然重烘。
     * 【为什么编成一个 Int】历史键 `doll_bakev_*` 存的就是 Int，保住既有数据的可读性，
     *   不为此换存储类型。
     */
    private fun bakeStamp(ctx: Context, dollId: String, scaleFactor: Float): Int {
        val doll = DollStore.byId(ctx, dollId)
        val bmp = DollStore.spriteOf(ctx, doll)
        val w = bmp?.width ?: 0
        val h = bmp?.height ?: 0
        // 用**调用方传进来的比例**（= 这只人偶的比例），而不是全局 petScale ——
        //   两者不同源时，用全局值做戳会让「这只人偶调过大小」永远检测不到。
        val scale = Math.round((if (scaleFactor > 0f) scaleFactor else 1f) * 1000f)
        // 乘法混列：任一输入变化都会落到不同的整数上（不需要密码学强度，只要不撞）。
        var s = BAKE_VERSION
        s = s * 31 + scale
        s = s * 31 + (w and 0xFFFF)
        s = s * 31 + (h and 0xFFFF)
        return s
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
     * 【流程】① 摆一个离屏的 PetView → ② 取**全体内置动作的公共包围盒** →
     *   ③ 定画布尺寸（含留白）→ ④ 逐帧画进位图。
     *
     * ## 【v0.1.0·修「动作大小不一」】画布必须由**全体动作**共同决定
     *
     * 改造前这里是「每个动作各量自己的包围盒、各自成画布」。看起来更省像素，
     * 但播放层是「等比铺满窗口」的（`ActionPlayerCore.draw` 用 `min(cw/bw, ch/bh)`）：
     * · 呼吸的包围盒基本就是精灵本身 → 画布小 → 被放大到铺满窗口；
     * · 跳跃/伸展含腾空与抬高 → 画布高 → 同一个窗口里只能缩得更小。
     * 于是**同一只人偶在不同动作下大小忽大忽小**，用户看到的就是「比例失衡」。
     *
     * 现在改成：所有内置动作**共用一张基准画布**——由全部 Plan 的包围盒取并集，
     * 再统一加留白。这样每一张帧图的像素密度完全一致，播放时铺满窗口的缩放系数
     * 也就一致，人偶在任何动作里都是同一个大小；同时因为公共框是并集，
     * 腾空/抬高/表情这些外扩内容依然不会被裁。
     */
    private fun renderAll(ctx: Context, dollId: String, plan: DollAnim.Plan,
                          scaleFactor: Float): List<Bitmap> {
        val doll = DollStore.byId(ctx, dollId)
        val sprite = DollStore.spriteOf(ctx, doll) ?: return emptyList()
        // 离屏测量用的人偶：不挂窗口，只为拿到与该人偶一致的比例与度量。
        val pv = PetView(ctx)
        pv.setScaleFactor(scaleFactor)
        pv.setSprite(sprite)
        val n = plan.frames
        if (n <= 0) {
            return emptyList()
        }
        // ---------- ① 公共包围盒（全体内置动作的并集，带缓存） ----------
        val baseBox = sharedBounds(ctx, pv, dollId, sprite, scaleFactor)
        if (baseBox == null || baseBox.width() <= 0f || baseBox.height() <= 0f) {
            return emptyList()
        }
        val params = Array(n) { FloatArray(BakeParams.SIZE) }
        // ---------- ② 生成时间线参数 ----------
        for (i in 0 until n) {
            timeline(plan, i, n, params[i])
        }
        // ---------- ③ 逐帧渲染（含触边兜底） ----------
        // 【留白】四边等值 14dp：公共框已经包含全部动作的外扩内容，
        //   这里只留一层抗锯齿余量；留白越小，人偶在桌面上越接近原本的尺寸。
        val pad = pv.dp(14.0f).toInt()
        val rect = RectF(baseBox)
        // 【扩展系数】正常情况下第一轮就通过；只有「包围盒推导漏了某个变换」时才会扩大。
        //   【关键】扩大必须作用在**公共框**上（并写回缓存），不能只放大当前动作 ——
        //   否则前一个动作用旧框、这个用新框，大小又不一致了。
        var grow = 1.0f
        var result: ArrayList<Bitmap>? = null
        // 本体基准矩形（相对画布的比例）：只算一次，触边重来时按新画布重算。
        var baseline: FloatArray? = null
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

            // ---------- 【v0.1.0·基准本体】 ----------
            // 记录「静立不动」时人偶本体占画布的比例（0..1），播放层据此映射到窗口。
            //
            // 【为什么不能取第 0 帧】第 0 帧可能是腾空 / 抬得最高的那一帧（跳跃、伸展），
            //   拿它当基准会让人偶在其他动作里整体偏小。这里用「全零参数」= 没有跳跃、
            //   没有抬高、没有倾斜、没有形变的站立态（跳/落地用 -1 表示「不在进行」）。
            //   所有动作共用同一个「站立态」定义，于是它们的基准是同一把尺子。
            //
            // 【为什么按画布比例存】播放层拿到的帧图可能被采样过（解码尺寸变化），
            //   存像素值会失效；存比例则与分辨率无关。
            if (baseline == null) {
                // 【v0.1.0·修·基准被眼睛形变带偏】静立态不走「new FloatArray(12) 再补两位」——
                //   那样漏一位就会悄悄改变量的结果：`v[10]`(idleEyeScale) 留 0 时，
                //   `buildMesh` 里 `min = min(base, 0) = 0` → `f8 = 1` → 眼睛被压扁到极值，
                //   网格顶点整体下移，量出来的基准框与真实静立态不一致
                //   （修「动作大小不一」时反而会引入新的偏差）。
                //   现在统一由 [BakeParams.stand] 给出一份「每位都取语义中性值」的数组。
                val stand = BakeParams.stand()
                val br = RectF()
                if (pv.bakeBounds(stand, 0, br)) {
                    br.offset(originX, originY)
                    baseline = floatArrayOf(
                        br.left / w, br.top / h, br.right / w, br.bottom / h
                    )
                }
            }
            val frames = ArrayList<Bitmap>(n)
            var edge = false
            // 表情 id 与当前动作相关（惊讶/开心/迷糊会额外画头顶符号），逐动作取。
            val expr = exprOf(plan)
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
            baseline = null
            Logs.w(LOG_TAG, "bake edge touched, retry with grow=" + grow + " action=" + plan.id)
        }
        // 【关键·写回缓存】触边意味着公共框算窄了。必须把**扩大后的框**写回缓存，
        //   让后续动作也用同一张画布 —— 否则这一个动作用大画布、别的用旧画布，
        //   播放时按各自的画布铺满窗口，大小差异又回来了。
        if (result != null && grow > 1.0f) {
            val gx = rect.width() * (grow - 1.0f) / 2.0f
            val gy = rect.height() * (grow - 1.0f) / 2.0f
            val grown = RectF(
                rect.left - gx, rect.top - gy, rect.right + gx, rect.bottom + gy
            )
            writeBox(ctx, boxKey(ctx, dollId, sprite, scaleFactor), grown)
        }
        // 本体基准：按动作存（每个动作的画布不同，比例也就不同）。
        val bl = baseline
        if (result != null && bl != null) {
            try {
                PetPrefs.get(ctx).edit()
                    .putString(baselineKey(dollId, plan.id), bl.joinToString(","))
                    .apply()
            } catch (t: Throwable) {
            }
        }
        return result ?: emptyList()
    }

    /**
     * 【主线程】量「全体内置动作」的公共包围盒（并集）—— 所有内置动作共用一张画布。
     *
     * ## 为什么必须全量扫一遍
     * 画布尺寸决定「播放时等比铺满窗口的缩放系数」：同一张画布下，内容越靠里
     * 人偶越小；画布越小，人偶被放大得越多。要让所有动作看起来同样大，
     * 就必须让它们的帧图**像素密度一致**，也就是共用画布。
     *
     * ## 为什么取并集而不是取最大值
     * 并集保证任何动作的最外沿（腾空、抬高、头顶符号）都落在画布内；
     * 取「最大宽 × 最大高」这种各取一维的做法会在极端帧上裁掉内容。
     *
     * ## 缓存
     * 全体 Plan 的量测要跑近百帧（每帧一次网格构建），每次烘帧都算一遍会让
     * 首次进动作页明显变慢。键 = 人偶 + 烘帧版本 + 比例 + 精灵图尺寸：
     * 换人偶、改比例、换形象、升级烘帧版本时自然失效。
     *
     * @return 公共包围盒（ox = oy = 0 口径）；量不出来时返回 null。
     */
    private fun sharedBounds(
        ctx: Context, pv: PetView, dollId: String, sprite: Bitmap, scaleFactor: Float
    ): RectF? {
        val key = boxKey(ctx, dollId, sprite, scaleFactor)
        readBox(ctx, key)?.let { return it }
        val out = RectF()
        var any = false
        val v = FloatArray(BakeParams.SIZE)
        for (plan in DollAnim.PLANS) {
            val n = plan.frames
            if (n <= 0) {
                continue
            }
            val expr = exprOf(plan)
            for (i in 0 until n) {
                timeline(plan, i, n, v)
                val r = RectF()
                if (!pv.bakeBounds(v, expr, r)) {
                    continue
                }
                if (!any) {
                    out.set(r)
                    any = true
                } else {
                    out.union(r)
                }
            }
        }
        if (!any || out.width() <= 0f || out.height() <= 0f) {
            return null
        }
        writeBox(ctx, key, out)
        return out
    }

    /**
     * 本体基准矩形的存储键。
     *
     * 【为什么按动作存】每个动作的画布尺寸不同（公共框一样，但触边兜底可能扩大），
     *   本体占画布的比例也就不同；必须逐动作记，不能共用。
     */
    private fun baselineKey(dollId: String, actionId: String): String {
        return "doll_baseline_" + dollId + "_" + actionId
    }

    /**
     * **【v0.1.0】取某个动作的「本体基准矩形」**（相对画布的比例 `[l, t, r, b]`）。
     *
     * 【谁在用】`ActionPlayerCore.draw`：把本体映射到窗口，让所有动作里人偶一样大。
     * 【返回 null 的含义】还没烘过（或旧版本烘的）：调用方自行回退成「整张画布铺满」，
     *   与改造前行为一致。
     */
    fun baseline(ctx: Context, dollId: String, actionId: String): FloatArray? {
        val raw = try {
            PetPrefs.get(ctx).getString(baselineKey(dollId, actionId), null)
        } catch (t: Throwable) {
            null
        } ?: return null
        val parts = raw.split(',')
        if (parts.size != 4) {
            return null
        }
        return try {
            val out = FloatArray(4)
            for (i in 0 until 4) {
                out[i] = parts[i].trim().toFloat()
            }
            if (out[2] <= out[0] || out[3] <= out[1]) null else out
        } catch (t: Throwable) {
            null
        }
    }

    /** 公共包围盒缓存的键：人偶 + 烘帧版本 + 比例 + 精灵图尺寸（任一变化都应重算）。 */
    private fun boxKey(ctx: Context, dollId: String, sprite: Bitmap, scaleFactor: Float): String {
        return "doll_bakebox_" + dollId + "_" + BAKE_VERSION + "_" +
            Math.round(scaleFactor * 1000f) + "_" + sprite.width + "x" + sprite.height
    }

    /** 读缓存的公共包围盒（存成 4 个数字，不引入 JSON）。 */
    private fun readBox(ctx: Context, key: String): RectF? {
        val raw = try {
            PetPrefs.get(ctx).getString(key, null)
        } catch (t: Throwable) {
            null
        } ?: return null
        val parts = raw.split(',')
        if (parts.size != 4) {
            return null
        }
        return try {
            val r = RectF(
                parts[0].trim().toFloat(), parts[1].trim().toFloat(),
                parts[2].trim().toFloat(), parts[3].trim().toFloat()
            )
            if (r.width() <= 0f || r.height() <= 0f) null else r
        } catch (t: Throwable) {
            null
        }
    }

    /** 写缓存。失败不影响功能（下次再算一遍而已）。 */
    private fun writeBox(ctx: Context, key: String, r: RectF) {
        try {
            val s = r.left.toString() + "," + r.top + "," + r.right + "," + r.bottom
            PetPrefs.get(ctx).edit().putString(key, s).apply()
        } catch (t: Throwable) {
        }
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
     * 【实现已搬到 [BakeTimeline]】这里只做转调。原因见那个对象的类注释：
     *   这段逻辑出过两次「能编译、能播、但语义错」的事故（眼睛被压扁、
     *   贴边帧里画了倾斜），而它原先和 Android 类同处一文件、还是 private，
     *   根本没法被 JVM 单测覆盖。搬成纯对象后，`BakeTimelineTest` 能直接断言
     *   「贴边帧的 lean 恒为 0」这类契约。
     *
     * 【为什么还要留这层壳】调用点在 [renderAll] 与量基准两处，签名保持不变
     *   可以让改动面最小（不为了搬家去动烘帧主流程）。
     */
    private fun timeline(plan: DollAnim.Plan, i: Int, n: Int, v: FloatArray) {
        BakeTimeline.fill(
            kind = plan.kind,
            i = i,
            n = n,
            v = v,
            eyeV = PetView.EYE_V,
            edgeLean = PetView.EDGE_LEAN
        )
    }
}