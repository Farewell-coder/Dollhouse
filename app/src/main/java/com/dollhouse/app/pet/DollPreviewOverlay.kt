package com.dollhouse.app.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.dollhouse.app.data.DollProfile
import com.dollhouse.app.data.DollSizeSpec
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs

/**
 * 【职责】「预览」悬浮窗（r13 重写）：**这只人偶的独立小开关**。
 *
 * ── 语义（用户 r13 定案）──────────────────────────────────────────────
 * 「那个预览也相当于一个小开关 但是只会开启那个卡片人偶 不会开启全部的人偶；
 *   点的第二下也算人偶消失 就是这个单个人偶的消失 也会触发回到位置」
 *
 *   1. **只开这一只**：点第 1 下 → 桌面上出现**这一只**人偶；
 *      不启动 [com.dollhouse.app.PetService]、不影响其它人偶、不占前台通知。
 *   2. **点第 2 下 = 这只人偶消失**：不是单纯把窗口藏起来，而是按既有的
 *      「人偶消失」判据（[PetPrefs.setHiddenAt]，按 dollId 打点）记下时刻。
 *   3. **再点开触发回到位置**：重新出现时走与桌宠同款的回归判定 ——
 *      `returnSeconds <= 0` 每次回；`> 0` 时消失超过该秒数才回。
 *
 * ── 与 r12 旧版的区别 ────────────────────────────────────────────────
 *   ① **能交互**：点击跳一下 + 说台词；按住可拖到任意处。
 *      r12 旧版装了 `setOnTouchListener { _, _ -> true }` 把触摸全吞掉，
 *      所以「只会静止不动」——除动画外没有任何响应。
 *   ② **比例实时生效**：保留**同一个 PetView 实例**，比例变了只更新缩放系数与窗口宽高。
 *      r12 旧版走「摘掉重挂」，点一下要等一整帧、看着像没反应/闪。
 *   ③ **未保存也同步**：比例 / 朝向 / 形象都是点一下即落盘，预览按需重读；
 *      编辑页各处入口改完都会主动 [refresh]。
 *
 * ── 与桌宠服务的关系 ──────────────────────────────────────────────────
 *   完全无关：不是 [com.dollhouse.app.PetService] 的窗口，不注册 [PetBus]、
 *   不参与贴边吸附、不写位置记忆（`rest_*` 一概不碰）、不占前台通知。
 *   它是「这只人偶单独试玩一下」，与桌宠的启停互不干扰。
 *
 * 【权限】TYPE_APPLICATION_OVERLAY；未授权时 [show] 返回 false，调用方需提示用户去开权限。
 */
object DollPreviewOverlay {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var petView: PetView? = null
    private var lp: WindowManager.LayoutParams? = null

    /** 当前预览的人偶 id（决定「只开哪一只」与回归判定取谁的值）。 */
    private var curDollId: String? = null

    private val ui = Handler(Looper.getMainLooper())

    /** 拖拽阈值（与 [PetWindow] 同源：系统 touchSlop）。 */
    private var touchSlop = 0

    // ---- 拖拽状态（照搬 PetWindow 的最小集：不做贴边、不写记忆）----
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false

    @JvmStatic
    fun isShowing(): Boolean = root != null

    /** 正在预览的人偶 id；没在预览返回 null。 */
    @JvmStatic
    fun currentDollId(): String? = curDollId

    /** 是否正在预览**指定**人偶（编辑页用来点亮图标）。 */
    @JvmStatic
    fun isShowing(dollId: String?): Boolean {
        return root != null && curDollId == dollId
    }

    /**
     * 打开这只人偶的小开关。返回 true 表示已显示。
     *
     * @param ctx    任意 Context。
     * @param dollId 目标人偶 id（按它的形象 / 比例 / 朝向渲染）。
     */
    @JvmStatic
    fun show(ctx: Context, dollId: String): Boolean {
        // 已经在预览同一只：只做一次刷新（把期间改过的比例 / 朝向同步上去）。
        if (root != null && curDollId == dollId) {
            refreshCurrent(ctx)
            return true
        }
        // 换了一只：先收起旧的（旧的那只按「消失」打点），再挂新的。
        if (root != null) {
            hide(ctx)
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            return false
        }
        return try {
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val pv = newPetView(ctx, dollId)
            touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
            val p = windowParams(pv)
            // 【会挡住操作】不加 FLAG_NOT_TOUCHABLE：预览窗吞掉触摸（需求定案）。
            // 【不抢焦点】加 FLAG_NOT_FOCUSABLE，否则会顶掉 Activity 的按键与输入法。
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            p.format = PixelFormat.TRANSLUCENT
            p.gravity = Gravity.CENTER
            // 【回归判定】按这只人偶的 returnSeconds 决定要不要回初始位置；
            //   与 PetWindow.attach 同一套语义（消失够久就回家）。
            applyStartPlacement(ctx, dollId, p, pv)
            pv.setOnTouchListener { _, motionEvent -> onTouch(motionEvent) }
            manager.addView(pv, p)
            pv.startAnim()
            wm = manager
            root = pv
            petView = pv
            lp = p
            curDollId = dollId
            true
        } catch (ignored: Throwable) {
            wm = null
            root = null
            petView = null
            lp = null
            curDollId = null
            false
        }
    }

    /**
     * 收起这只人偶的小开关（幂等）。
     *
     * 【r13 关键语义】收起 = **这只人偶从屏幕消失且不会自己回来** ⇒ 按既有判据
     *   给它打一个消失时刻（[PetPrefs.setHiddenAt]，按 dollId 存）。
     *   下次再点开时，[applyStartPlacement] 会据此判断要不要回初始位置。
     */
    @JvmStatic
    fun hide(ctx: Context?) {
        val view = root ?: return
        val id = curDollId
        root = null
        curDollId = null
        ui.removeCallbacksAndMessages(null)
        try {
            petView?.stopAnim()
            wm?.removeView(view)
        } catch (ignored: Throwable) {
        }
        wm = null
        petView = null
        lp = null
        // 【消失打点】按 dollId 记下时刻；有 ctx 才写（无 ctx 时仅收起窗口）。
        if (ctx != null && !id.isNullOrEmpty()) {
            PetPrefs.setHiddenAt(ctx, id, System.currentTimeMillis())
        }
    }

    /** 无参重载：给「离开编辑页」这类只求收起的调用方用（不打点，避免误判成消失）。 */
    @JvmStatic
    fun hide() {
        // 【为什么不打点】离开编辑页是「顺手清场」，不是用户「关掉这只人偶」的意图；
        //   若也打点，下次打开就会被当成一次真实消失而跳回初始位置，反而突兀。
        //   真正的「关掉」由编辑页图标那一次点击走带 ctx 的 hide(ctx)。
        val view = root ?: return
        root = null
        curDollId = null
        ui.removeCallbacksAndMessages(null)
        try {
            petView?.stopAnim()
            wm?.removeView(view)
        } catch (ignored: Throwable) {
        }
        wm = null
        petView = null
        lp = null
    }

    /**
     * 【r13】按当前配置刷新预览（比例 / 朝向 / 形象可能刚被改过）。
     *
     * 【为什么不再「摘掉重挂」】r12 的旧实现直接 hide() + show()，
     *   用户点「放大」时肉眼可见地闪一下，且窗口重新入场会从头播动画。
     *   这里改成**就地更新同一个 PetView**：比例只改缩放系数与窗口宽高，
     *   朝向只改镜像标志，形象变了才重设位图 —— 全程连续，这就是「实时变大变小」。
     */
    @JvmStatic
    fun refreshCurrent(ctx: Context) {
        val pv = petView ?: return
        val id = curDollId ?: return
        val p = lp ?: return
        val manager = wm ?: return
        try {
            // 比例：先改系数（它决定 petWidth / windowWidth），再按新尺寸更新窗口。
            // 【本轮·统一口径】预览与桌面必须用**同一个函数**算最终缩放，
            //   否则用户在编辑页看到的大小和退出后在桌面上看到的不一致
            //   （需求书第五条第 10 点明确要求两者一致）。
            pv.setSizeSpec(DollSizeSpec.spec(ctx, id))
            pv.setScaleFactor(appliedScale(ctx, id))
            pv.setSprite(
                DollStore.spriteOf(ctx, DollStore.byId(ctx, id)),
                // 与 PetWindow 同一判据：内置人偶才允许网格形变，见 PetView.deformSprite。
                (DollStore.byId(ctx, id)?.sprite ?: "").isEmpty()
            )
            pv.setFlipped(DollProfile.flip(ctx, id))
            p.width = pv.windowWidth()
            p.height = pv.baseHeight()
            // 【保持中心不动】窗口是 Gravity.CENTER，尺寸变化本身即以中心为锚，
            //   不必再手动补 x/y（补了反而会偏）。
            manager.updateViewLayout(pv, p)
            pv.invalidate()
        } catch (ignored: Throwable) {
        }
    }

    /** 带 dollId 的便捷刷新：编辑页各处改完设置后调它即可（幂等，没在预览就什么都不做）。 */
    @JvmStatic
    fun refresh(ctx: Context, dollId: String) {
        if (root == null || curDollId != dollId) {
            return
        }
        refreshCurrent(ctx)
    }

    // ============================ 内部 ============================

    /** 按人偶配置造一个 PetView（形象 / 比例 / 朝向都以它为准）。 */
    private fun newPetView(ctx: Context, dollId: String): PetView {
        val pv = PetView(ctx)
        // 【本轮·统一口径】与 refreshCurrent / PetWindow.attach 同一套算法，见 appliedScale。
        pv.setSizeSpec(DollSizeSpec.spec(ctx, dollId))
        pv.setScaleFactor(appliedScale(ctx, dollId))
        val doll = DollStore.byId(ctx, dollId)
        pv.setSprite(DollStore.spriteOf(ctx, doll), (doll?.sprite ?: "").isEmpty())
        pv.setFlipped(DollProfile.flip(ctx, dollId))
        return pv
    }

    /**
     * 最终生效的缩放 = 基础比例 × 尺寸规格。
     *
     * 【为什么预览与桌面各写一份同样的两行】两处分属不同包（pet 层互不依赖），
     *   为了不让「预览 / 桌面口径漂移」重新长出来，这里把公式与
     *   `PetWindow.appliedScale` 保持逐字一致 —— 改一处必须改两处，
     *   并在 `DollSizeSpec` 里有单测钉住「size=100 时系数不变」。
     */
    private fun appliedScale(ctx: Context, dollId: String): Float {
        return DollProfile.scale(ctx, dollId) / 100.0f *
            DollSizeSpec.scaleOf(DollSizeSpec.spec(ctx, dollId))
    }

    /** 预览窗口参数：尺寸按 PetView 自报的算。 */
    private fun windowParams(pv: PetView): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            pv.windowWidth(),
            pv.petHeight(),
            if (Build.VERSION.SDK_INT >= 26) 2038 else 2002,
            776,
            -3
        )
    }

    /**
     * 【r13 回归判定】决定这次出现落在哪儿。
     *
     * 【判据】与 [PetWindow.attach] 完全同口径：
     *   - 用户**设过**初始位置（[DollProfile.hasHomePivot]）且
     *     `returnSeconds <= 0`（每次都回）或「消失时长 ≥ 阈值」 ⇒ 落在初始位置；
     *   - 否则沿用上次记住的位置（[PetPrefs] 的 `rest_*` 是**桌宠**的位置，
     *     预览不碰它 —— 预览默认居中，只处理「回初始位置」这一种）。
     *
     * 【为什么取走消失时刻】[PetPrefs.takeHiddenMs] 读一次即清零，
     *   避免同一次消失被前后两次打开各算一遍。
     */
    private fun applyStartPlacement(
        ctx: Context,
        dollId: String,
        p: WindowManager.LayoutParams,
        pv: PetView
    ) {
        val hiddenMs = PetPrefs.takeHiddenMs(ctx, dollId)
        val ret = DollProfile.returnSeconds(ctx, dollId)
        val hasHome = DollProfile.hasHomePivot(ctx, dollId)
        val wantHome = hasHome && (if (ret <= 0) true else (hiddenMs >= 0L && hiddenMs >= ret * 1000L))
        p.gravity = Gravity.CENTER
        if (!wantHome) {
            return
        }
        val home = DollProfile.homePivot(ctx, dollId) ?: return
        val scrW = Math.max(1, ctx.resources.displayMetrics.widthPixels)
        val scrH = Math.max(1, ctx.resources.displayMetrics.heightPixels)
        // 【坐标口径】home 是「整屏归一化」的（0~1，见 DollHomeOverlay 的中心点），
        //   与桌宠一致：pivotX = rx * 屏宽，pivotY = ry * 屏高。
        //   Gravity 从 CENTER 改成 TOP|START，x/y 才按屏幕左上角计（否则是相对居中点的偏移）。
        val pivotX = Math.round(home[0] * scrW)
        val pivotY = Math.round(home[1] * scrH)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = pivotX - pv.pivotLocalX().toInt()
        p.y = pivotY - pv.pivotLocalY().toInt()
    }

    /**
     * 预览窗的触摸处理：**点击跳一下 + 说台词，按住可拖走**。
     *
     * 【为什么不做贴边吸附 / 位置记忆】预览的语义是「这只人偶的小开关 + 试玩一下」，
     *   不是「摆好它的位置」（那是「初始位置 / 屏幕范围」的职责）。
     *   吸附会把「想看它挪到哪」变成干扰，所以只做最朴素的手动拖拽。
     *
     * 【台词来源】与真实桌宠同一个键（[DollProfile.talkLines] 按 dollId 取），
     *   所以在编辑页里改完「交谈内容」，预览点一下就能立刻听到新台词。
     */
    private fun onTouch(ev: MotionEvent): Boolean {
        val pv = petView ?: return false
        val p = lp ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                downX = p.x
                downY = p.y
                dragging = false
                pv.setTouchBusy(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    dragging = true
                    pv.setDragging(true)
                }
                if (dragging) {
                    // 【拖动时改用 TOP|START】Gravity.CENTER 下的 x/y 是「居中偏移」，
                    //   拖拽语义必须按屏幕左上角算，这里切换一次即可。
                    if (p.gravity != (Gravity.TOP or Gravity.START)) {
                        val scrW = Math.max(1, pv.context.resources.displayMetrics.widthPixels)
                        val scrH = Math.max(1, pv.context.resources.displayMetrics.heightPixels)
                        downX = downX + scrW / 2
                        downY = downY + scrH / 2
                        p.gravity = Gravity.TOP or Gravity.START
                    }
                    p.x = Math.round(downX + dx)
                    p.y = Math.round(downY + dy)
                    pv.setDragVelocity(dx / 0.05f)
                    applyLayout()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                pv.setTouchBusy(false)
                if (dragging) {
                    pv.setDragging(false)
                    applyLayout()
                } else {
                    // 单击：跳一下 + 随机说一句（与真实桌宠的「点一下」同款）。
                    poke()
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pv.setTouchBusy(false)
                pv.setDragging(false)
                dragging = false
                return true
            }
        }
        return false
    }

    /** 点一下：跳 + 说台词（台词按 dollId 取，与桌宠同源）。 */
    private fun poke() {
        val pv = petView ?: return
        val ctx = pv.context
        val id = curDollId ?: return
        pv.playJump()
        val lines = DollProfile.talkLines(ctx, id)
        if (lines.isEmpty()) {
            return
        }
        // 【气泡要留出空间】直接给 PetView 显示文本并撑高窗口，否则气泡会被窗口裁掉。
        val line = lines[(Math.random() * lines.size).toInt().coerceIn(0, lines.size - 1)]
        pv.showBubblePaged(line, 2)
        pv.setBubbleHeight(pv.neededBubbleSpaceFor(pv.windowWidth(), lp?.x ?: 0))
        val p = lp ?: return
        p.height = pv.baseHeight() + pv.bubbleHeight()
        applyLayout()
        // 到点收气泡（时长与桌宠单击一致：2400ms）。
        ui.removeCallbacks(bubbleOff)
        ui.postDelayed(bubbleOff, 2400L)
    }

    /** 气泡到时收起：恢复窗口高度。 */
    private val bubbleOff = Runnable {
        val pv = petView ?: return@Runnable
        val p = lp ?: return@Runnable
        pv.clearBubble()
        pv.setBubbleHeight(0)
        p.height = pv.baseHeight()
        applyLayout()
    }

    /** 把当前 lp 应用到窗口（幂等；失败静默）。 */
    private fun applyLayout() {
        val pv = petView ?: return
        val p = lp ?: return
        val manager = wm ?: return
        try {
            manager.updateViewLayout(pv, p)
        } catch (ignored: Throwable) {
        }
    }
}