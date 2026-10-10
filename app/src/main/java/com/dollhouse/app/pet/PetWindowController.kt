package com.dollhouse.app.pet

import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.view.WindowManager
import com.dollhouse.app.PetService
import com.dollhouse.app.R
import com.dollhouse.app.anim.Springs
import com.dollhouse.app.data.PetPrefs

/**
 * 【职责】桌宠悬浮窗的挂载与几何：attach/detach、按锚点摆位、贴边吸附动画、上滑偷看（peek）、气泡增高。
 *
 * 【入口】由 PetService 构造时创建（new PetWindowController(this)）；
 *         PetService 的 onStartCommand / onDestroy / handleTouch 通过同名转发壳调进来。
 *
 * 【交互】窗口状态字段（lp / petView / wm / prefs / added / peek / edgePeek / snapAnim 等）
 *         仍由 PetService 持有（包级可见），本类只读写不持有——这样 handleTouch / onTap / say
 *         这些留在 Service 里的逻辑一行都不用改；ui / safeUpdate 全部回调 host。
 *
 * 【扩展】新增悬浮窗状态：在 PetService 加包级字段 + 在本类加设置它的方法；
 *         新增贴边策略：只改 snapToEdge / settle 两处。
 *
 * 【坑】prefs 的 rest_px / rest_fy / edge_side 三个键是历史键名，改名会让用户位置丢失；
 *       snapToEdge 里的 ValueAnimator 必须存在 host.snapAnim 上，否则 onDestroy 取消不掉会泄漏。
 */
class PetWindowController(private val host: PetService) {
    /** 【v2.10.2】上一次已知的屏幕宽高（-1 = 尚未记录）。旋转 / 折叠屏 / 分屏靠它察觉。 */
    private var screenW = -1
    private var screenH = -1

    fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26) 2038 else 2002
    }

    /**
     * 应用新的人偶缩放：改 PetView 系数后必须重算窗口宽高并重摆，否则只变画布不变窗口。
     *
     * 【短路】REFRESH 同时被换主题 / 拖背景透明度复用，那两处不该动几何；
     *         比例没变就直接返回，避免误把贴边态解除。
     * 【聊天窗】聊天窗开着时桌宠处于 peek（只露头搭在框顶）；此时缩放必须重新 layoutPeek，
     *         若退回全身会被上层聊天框整个挡住（用户报的「聊天框挡住人物」）。
     * 【顺序】非 peek 路径：先用旧系数取屏幕锚点 → 退出贴边态 → 改系数 → 按新宽度回夹 → 重摆。
     *         pivotLocalX/Y 依赖 petWidth()，系数必须在取锚点之后再改，否则锚点漂移。
     * 【前置清理】贴边吸附动画会持续写 lp.x，必须先取消，否则动画用旧区间覆盖缩放结果。
     * 【气泡】气泡态下 lp.height 含气泡高度，须先收起再重摆，摆完按新宽度重开，避免气泡错位。
     */
    fun applyScale() {
        if (host.petView == null || host.lp == null || !host.added) {
            return
        }
        val target = PetPrefs.petScale(host) / 100.0f
        if (Math.abs(target - host.petScaleApplied) < 1.0E-4f) {
            return
        }
        host.petScaleApplied = target
        val valueAnimator = host.snapAnim
        if (valueAnimator != null && valueAnimator.isRunning) {
            valueAnimator.cancel()
        }
        // peek 态：迷你输入框开着就重排「人偶 + 框」整组，保住趴姿与人偶脚底位置。
        if (host.peek) {
            host.petView!!.setBubbleHeight(0)
            host.bubbleUp = false
            host.petView!!.setScaleFactor(target)
            host.layoutMiniTalk(host.talkIme)
            return
        }
        val wasBubbleUp = host.bubbleUp
        // 用当前窗口里实际占用的气泡高度，而不是写死的 bubbleSpace()，重开后气泡才不会跳大小。
        val bubbleSpace = if (wasBubbleUp) Math.max(0, host.lp!!.height - host.petView!!.baseHeight()) else host.petView!!.bubbleSpace()
        if (wasBubbleUp) {
            host.petView!!.setBubbleHeight(0)
            host.bubbleUp = false
        }
        var pivotX: Float = host.lp!!.x + host.petView!!.pivotLocalX()
        val pivotY = host.lp!!.y + host.petView!!.pivotLocalY()
        if (host.edgePeek) {
            exitEdgePeek()
        }
        if (host.petView == null || host.lp == null || !host.added) {
            return
        }
        host.petView!!.setScaleFactor(target)
        // 放大后靠边会顶出屏幕：按新宽度把锚点夹回可见区（沿用 attachPet 的同款算法）。
        val screenW = host.resources.displayMetrics.widthPixels
        val half = host.petView!!.petWidth() / 2
        if (!host.edgePeek) {
            pivotX = Math.max(half.toFloat(), Math.min((screenW - half).toFloat(), pivotX))
        }
        host.placeByPivot(Math.round(pivotX), Math.round(pivotY))
        host.rememberPivot()
        if (wasBubbleUp) {
            expandBubble(bubbleSpace)
        }
    }

    fun attachPet() {
        val petView = PetView(host)
        host.petView = petView
        host.petScaleApplied = PetPrefs.petScale(host) / 100.0f
        petView.setScaleFactor(host.petScaleApplied)
        petView.setSprite(BitmapFactory.decodeResource(host.resources, R.drawable.pet_front))
        petView.setAffection(PetPrefs.affection(host))
        // 【吸附=忙】直接探测弹簧是否在跑：吸附开始 / 完成 / 取消三态都体现在 isRunning 上，
        //   吸附期间帧循环强制 16ms，人偶内部动画不会停在贴边熄火档。
        petView.snapActiveCheck = { host.snapAnim?.isRunning == true }
        val layoutParams = WindowManager.LayoutParams(petView.windowWidth(), petView.petHeight(), host.overlayType(), 776, -3)
        host.lp = layoutParams
        layoutParams.gravity = 8388659
        val i = host.resources.displayMetrics.widthPixels
        val i2 = host.resources.displayMetrics.heightPixels
        val round = Math.round(host.resources.displayMetrics.density * 8.0f)
        val petWidth = petView.petWidth() / 2
        // 【v2.10.2】比例键优先：横屏下重启服务 / 重挂窗口都能落到屏内，像素键只作老档兜底。
        val rest = restorePivot()
        var i3 = if (rest != null) rest[0] else (i - round) - petWidth
        val i4 = if (rest != null) rest[1] else Math.round(i2 * 0.8f)
        val i5 = host.prefs!!.getInt("edge_side", -1)
        if (i5 >= 0) {
            host.enterEdgePeek(i5 == 0)
        } else {
            i3 = Math.max(petWidth, Math.min(i - petWidth, i3))
        }
        host.placeByPivot(i3, host.clampFeetY(i4, i2, round))
        petView.setOnTouchListener { _, motionEvent -> host.handleTouch(motionEvent) }
        try {
            host.wm!!.addView(petView, layoutParams)
            host.added = true
            // 【v2.10.2】把屏幕尺寸自检注入帧循环：系统回调不一定来（桌面旋转），
            // 但帧一直在跑，所以拿它当最可靠的那道保险。帧循环里只做一次宽高比较，开销可忽略。
            petView.screenTick = Runnable { checkScreenChanged() }
            petView.startAnim()
            PetBus.register(host.busListener)
            host.updateTouchable()
            // 【修 v0.0.1】启动时不再自动说话：气泡只在用户点击人偶后才出现。
            //   旧实现 addView 后立即随机说一句，此时窗口位置/尺寸尚未稳定，
            //   展开气泡会与吸附动画抢 lp，造成串字/溢出/穿模（用户报的贴边吸附问题）。
        } catch (unused: Throwable) {
            host.added = false
            PetNotifier.stopSafely(host)
        }
    }

    fun detachPet() {
        PetBus.unregister(host.busListener)
        host.ui.removeCallbacks(host.hideBubble)
        val petView = host.petView
        if (petView != null) {
            petView.screenTick = null
            petView.stopAnim()
            if (host.added) {
                try {
                    host.wm!!.removeView(petView)
                } catch (unused: Throwable) {
                }
            }
            host.petView = null
        }
        host.added = false
    }

    /**
     * 【暂离】把同一 PetView 从 WindowManager 摘掉，实例 / lp / PetBus / PetTalk 原样保留。
     *
     * 【为什么不用 detachPet】detachPet 会把 host.petView 置空并 unregister PetBus，PetTalk 的在途
     *   回复回来时宿主已无 View 可写（彻底丢回复）。暂离只摘窗：petView 不置空、PetBus 不动、
     *   PetTalk 照跑，回复写回同一气泡数据，恢复时同一实例重新 addView。
     * 【为什么置 added=false】防止 REFRESH / onTaskRemoved 发来的 ACTION_START 走 `!added` 分支
     *   重新 attachPet；「正在暂离」由 host.away 独占判定。added=false 同时让 safeUpdate 自然失效，
     *   暂离期间不会有任何 updateViewLayout。
     */
    fun awayHide() {
        val petView = host.petView ?: return
        if (!host.added) {
            return
        }
        petView.screenTick = null
        petView.stopAnim()
        try {
            host.wm!!.removeView(petView)
        } catch (unused: Throwable) {
        }
        host.added = false
    }

    /**
     * 【暂离恢复】把同一 PetView 重新挂回原 lp 并启动动画；成功返回 true。
     * 【边界】调用方（PetService.exitAway）已先清 host.away，这里只负责 addView 与启动帧循环。
     */
    fun awayShow(petView: PetView, layoutParams: WindowManager.LayoutParams): Boolean {
        return try {
            host.wm!!.addView(petView, layoutParams)
            host.added = true
            petView.screenTick = Runnable { checkScreenChanged() }
            petView.startAnim()
            safeUpdate()
            true
        } catch (unused: Throwable) {
            host.added = false
            false
        }
    }

    /**
     * 【v2.10.2】屏幕尺寸自检：宽高变了就按新屏幕把窗口复位回屏内。
     *
     * 【调用方】PetService.onConfigurationChanged / PetService 的 REFRESH 分支 /
     *           PetView 的帧内自检（screenTick）。三路都可能先到，方法本身幂等，重复调用无副作用。
     * 【为什么要有帧内自检这一路】个别 ROM（含 ColorOS）不给前台 Service 派发配置变化，
     *           只靠 onConfigurationChanged 会漏；帧循环最快 250ms 一轮，旋转后一秒内必定自愈。
     */
    fun checkScreenChanged() {
        if (host.petView == null || host.lp == null || !host.added) {
            return
        }
        val w = host.resources.displayMetrics.widthPixels
        val h = host.resources.displayMetrics.heightPixels
        if (w == screenW && h == screenH) {
            return
        }
        screenW = w
        screenH = h
        relayoutForScreen()
    }

    /**
     * 【v2.10.2】按当前屏幕复位悬浮窗。
     *
     * 【为什么必须做】竖屏存的 rest_fy = 屏高 × 0.8 = 1920，而横屏屏高只有 1080 ——
     *   人偶脚底直接落到屏幕外，用户看到的现象就是「横竖屏切换后人偶消失」。
     *   同理 lp.x 在宽高互换后可能越出右边界、贴边位置也会失准（用户说的「横屏别扭」）。
     *
     * 【顺序】先取消在跑的贴边动画（ValueAnimator 会持续写 lp.x，不取消会被它覆盖回去）。
     */
    private fun relayoutForScreen() {
        val anim = host.snapAnim
        if (anim != null && anim.isRunning) {
            anim.cancel()
        }
        // 聊天态（迷你输入框开着）人是趴姿、窗口跟着整组走：退掉趴姿最省事，
        // 退掉后由 layoutMiniTalk 按新屏幕重排。
        if (host.peek) {
            host.exitPeek()
            return
        }
        val pivot = restorePivot()
        if (pivot == null) {
            return
        }
        val screenW = host.resources.displayMetrics.widthPixels
        val screenH = host.resources.displayMetrics.heightPixels
        val edge = Math.round(host.resources.displayMetrics.density * 8.0f)
        if (host.edgePeek) {
            // 贴边态：贴边位置必须按新宽度重算（同 snapToEdge 的算法），不能沿用旧 x。
            val petWidth = host.petView!!.petWidth()
            val inset = host.petView!!.edgeInsetPx()
            val wPivot = if (host.edgeAtLeft) (edge - inset) + (petWidth / 2) else ((screenW - edge) + inset) - (petWidth / 2)
            host.placeByPivot(wPivot, host.clampFeetY(pivot[1], screenH, edge))
            host.rememberPivot()
            return
        }
        val half = host.petView!!.petWidth() / 2
        val x = Math.max(half, Math.min(screenW - half, pivot[0]))
        host.placeByPivot(x, host.clampFeetY(pivot[1], screenH, edge))
        host.rememberPivot()
    }

    /**
     * 【v2.10.2】还原人偶锚点 {pivotX, pivotY}；没有任何可用记录时返回 null（调用方走默认值）。
     *
     * 【为什么加比例键】像素键在竖屏单尺寸下够用，但屏幕一旋转（宽高互换）就全部失准：
     *   竖屏存下的 1920 是「屏高的 80%」，到横屏就变成了 178% —— 人偶被摆到屏幕外。
     *   所以优先用比例（占屏宽 / 屏高的百分比）换算；读不到比例键才回退像素键，
     *   老档的位置因此完全不受影响（首次启动会被 rememberPivot 自动补上比例键）。
     */
    private fun restorePivot(): IntArray? {
        val p = host.prefs!!
        val screenW = host.resources.displayMetrics.widthPixels
        val screenH = host.resources.displayMetrics.heightPixels
        val rx = p.getFloat("rest_rx", -1.0f)
        val ry = p.getFloat("rest_ry", -1.0f)
        if (rx >= 0.0f && ry >= 0.0f) {
            return intArrayOf(Math.round(rx * screenW), Math.round(ry * screenH))
        }
        if (p.contains("rest_px") || p.contains("rest_fy")) {
            return intArrayOf(p.getInt("rest_px", screenW / 2),
                    p.getInt("rest_fy", Math.round(screenH * 0.8f)))
        }
        return null
    }

    fun expandBubble(i: Int) {
        val petView = host.petView
        if (host.lp == null || petView == null) {
            return
        }
        var space = if (i <= 0) petView.bubbleSpace() else i
        // 贴边偷看时窗口有一大截在屏幕外，气泡按窗口宽排版会被切掉一半；
        // 气泡期间把窗口临时拉进屏内（人偶跟着进屏抬头说话），收起时再由 collapseBubble 还原。
        // 【动画】贴边吸附动画会持续写 lp.x，若气泡恰在动画期间弹出会被重新拖出屏外，必须先取消。
        // 【守卫】peek（聊天框顶）时窗口本就居中完整可见，且 edgePeek 只是残留标记，
        //         此时若按贴边分支摆位，气泡收起后人偶会横向跳到屏幕边缘。
        if (host.edgePeek && !host.peek) {
            val running = host.snapAnim
            if (running != null && running.isRunning) {
                running.cancel()
            }
            val screenW = host.resources.displayMetrics.widthPixels
            host.lp!!.x = Math.max(0, Math.min(Math.max(0, screenW - host.lp!!.width), host.lp!!.x))
        }
        // 顺序要紧：窗口 x 变了以后可见区才确定，必须先重排、再按重排结果定高，
        // 否则会沿用「按屏外窄宽排出的 3 行高度」，气泡比文字高一截。
        petView.relayoutBubble(host.lp!!.width, host.lp!!.x)
        space = petView.neededBubbleSpaceFor(host.lp!!.width, host.lp!!.x)
        petView.setBubbleHeight(space)
        host.lp!!.height = petView.baseHeight() + space
        host.lp!!.y = host.petTopY - space
        host.bubbleUp = true
        host.safeUpdate()
    }

    /**
     * 贴边偷看时的窗口 x（与 snapToEdge 同款算法）。
     * 【用途】气泡收起后把窗口还原回贴边位置，否则人偶会一直停在屏内。
     */
    private fun edgePeekX(): Int {
        val petView = host.petView
        if (petView == null) {
            return host.lp?.x ?: 0
        }
        val screenW = host.resources.displayMetrics.widthPixels
        val round = Math.round(host.resources.displayMetrics.density * 8.0f)
        val inset = petView.edgeInsetPx()
        val petWidth = petView.petWidth()
        val pivot = if (host.edgeAtLeft) (round - inset) + (petWidth / 2) else ((screenW - round) + inset) - (petWidth / 2)
        return Math.round(pivot - petView.pivotLocalX())
    }

    fun collapseBubble() {
        val petView = host.petView
        if (host.lp == null || petView == null || !host.bubbleUp) {
            val petView2 = host.petView
            if (petView2 != null) {
                petView2.clearBubble()
            }
            return
        }
        petView.clearBubble()
        petView.setBubbleHeight(0)
        host.lp!!.height = petView.baseHeight()
        host.lp!!.y = host.petTopY
        // expandBubble 把贴边窗口拉进了屏内，这里还原；若气泡期间用户拖过（edgePeek 已清）就不动。
        if (host.edgePeek && !host.peek) {
            host.lp!!.x = edgePeekX()
        }
        host.bubbleUp = false
        host.safeUpdate()
    }

    fun safeUpdate() {
        val petView = host.petView
        if (petView == null || !host.added) {
            return
        }
        try {
            host.wm!!.updateViewLayout(petView, host.lp!!)
        } catch (unused: Throwable) {
        }
    }

    /**
     * 气泡占用的窗口高度：有气泡、非 peek 且确有内容时为其实际高度，否则 0。
     * 【为什么不用 bubbleSpace()】那是写死的 96dp 预留值，与真实排版高度不一致；
     *   拖动 / 吸附 / 记忆锚点都必须用真实高度，气泡才不会被裁掉、人偶才不跳位。
     */
    private fun bubbleHeightIfUp(): Int {
        val petView = host.petView
        return if (host.bubbleUp && !host.peek && petView != null && petView.hasBubbleContent())
            petView.bubbleHeight() else 0
    }

    fun placeByPivot(i: Int, i2: Int) {
        val petView = host.petView
        val lp = host.lp
        if (petView == null || lp == null) {
            return
        }
        petView.geometry(host.geom)
        val round = Math.round(host.geom[0])
        val round2 = Math.round(host.geom[1])
        val round3 = Math.round(i - host.geom[2])
        val round4 = Math.round(i2 - host.geom[3])
        host.petTopY = round4
        // 【修 v0.0.5】气泡在播时（含拖动中）窗口高度必须把气泡一起算进来，
        //   否则 placeByPivot 会把高度压回首行几何、气泡被裁掉（用户报的「拖一下就看不到回复」）。
        //   宽度未变时 neededBubbleSpaceFor 命中缓存排版，不会逐帧重排，拖动几何因此不抖。
        if (host.bubbleUp && !host.peek && petView.hasBubbleContent()) {
            val space = petView.neededBubbleSpaceFor(round, round3)
            if (space > 0) {
                petView.setBubbleHeight(space)
                val h = petView.baseHeight() + space
                val y = round4 - space
                if (lp.width == round && lp.height == h && lp.x == round3 && lp.y == y) {
                    return
                }
                lp.width = round
                lp.height = h
                lp.x = round3
                lp.y = y
                host.safeUpdate()
                return
            }
        }
        if (lp.width == round && lp.height == round2 && lp.x == round3 && lp.y == round4) {
            return
        }
        lp.width = round
        lp.height = round2
        lp.x = round3
        lp.y = round4
        host.safeUpdate()
    }

    fun rememberPivot() {
        val petView = host.petView
        val lp = host.lp
        if (petView == null || lp == null) {
            return
        }
        val pivotX = lp.x + petView.pivotLocalX()
        // 【气泡·静止锚点】气泡在播时窗口整体被抬高了一个气泡高度，记忆位置要换算回静止位，
        //   否则下次启动（无气泡）人偶会整体上移一截（用户报的位置漂移）。
        val pivotY = lp.y + petView.pivotLocalY() + bubbleHeightIfUp()
        // 【v2.10.2】同时写比例键：像素键在横竖屏互换后全部失准（1920 在横屏是屏高的 178%），
        //   比例键让位置能跟着屏幕大小走。像素键继续保留 —— 老档回退与外部排查都还在读它。
        val screenW = Math.max(1, host.resources.displayMetrics.widthPixels)
        val screenH = Math.max(1, host.resources.displayMetrics.heightPixels)
        host.prefs!!.edit()
                .putInt("rest_px", Math.round(pivotX))
                .putInt("rest_fy", Math.round(pivotY))
                .putFloat("rest_rx", pivotX / screenW)
                .putFloat("rest_ry", pivotY / screenH)
                .apply()
    }

    fun clampFeetY(i: Int, i2: Int, i3: Int): Int {
        val petView = host.petView!!
        val bubbleSpace = petView.bubbleSpace() + Math.round(petView.pivotLocalY())
        return Math.max(bubbleSpace, Math.min(Math.max(bubbleSpace, i2 - i3), i))
    }

    fun settle() {
        val i = host.resources.displayMetrics.widthPixels
        val i2 = host.resources.displayMetrics.heightPixels
        val round = Math.round(host.resources.displayMetrics.density * 8.0f)
        val round2 = Math.round(host.lp!!.x + host.petView!!.pivotLocalX())
        // 【气泡·静止锚点】同上：气泡在播时锚点要换算回静止位，否则吸附 / 回夹会把人偶与气泡整体顶偏。
        val feetY = Math.round(host.lp!!.y + host.petView!!.pivotLocalY() + bubbleHeightIfUp())
        val clampFeetY = host.clampFeetY(feetY, i2, round)
        val round3 = Math.round(i * PetService.SNAP_ZONE_RATIO)
        if (round2 < round3) {
            host.snapToEdge(true, clampFeetY)
            return
        }
        if (round2 > i - round3) {
            host.snapToEdge(false, clampFeetY)
            return
        }
        host.exitEdgePeek()
        val petWidth = host.petView!!.petWidth() / 2
        host.placeByPivot(Math.max(petWidth + round, Math.min((i - petWidth) - round, round2)), clampFeetY)
        host.rememberPivot()
    }

    fun snapToEdge(z: Boolean, i: Int) {
        // 【首帧不滞后】吸附开始时 frameBusy 还看不到弹簧（snapAnim 在本方法末尾才赋值），
        //   此刻若帧循环仍停在 EDGE_IDLE_MS(250ms) 档，弹簧前 250ms 会被整帧吞掉。
        //   主动唤醒到 16ms；随后 snapActiveCheck={host.snapAnim?.isRunning==true} 接管。
        host.petView?.wakeFrame()
        val i3 = host.resources.displayMetrics.widthPixels
        val round = Math.round(host.resources.displayMetrics.density * 8.0f)
        val petView = host.petView!!
        val petWidth = petView.petWidth()
        host.enterEdgePeek(z)
        val edgeInsetPx = petView.edgeInsetPx()
        val i2 = if (z) {
            (round - edgeInsetPx) + (petWidth / 2)
        } else {
            ((i3 - round) + edgeInsetPx) - (petWidth / 2)
        }
        val round2 = Math.round(i2 - petView.pivotLocalX())
        val lp = host.lp!!
        // 【气泡·贴边】贴边吸附不得把正在播的气泡裁掉：高度与纵向位置都按实际气泡高度一起算，
        //   并用最终窗口 x 重排气泡（贴边后可见区变窄，行数/高度可能变化）。
        val space = if (host.bubbleUp && !host.peek && petView.hasBubbleContent())
            petView.neededBubbleSpaceFor(petView.windowWidth(), round2) else 0
        if (space > 0) {
            petView.setBubbleHeight(space)
        }
        lp.width = petView.windowWidth()
        lp.height = petView.baseHeight() + space
        lp.y = Math.round(i - petView.pivotLocalY() - space)
        host.petTopY = lp.y + space
        host.safeUpdate()
        // 【v2.10.2】贴边位置同样写一份比例键：否则「贴边 → 旋转」时会按上一次未贴边的比例还原。
        val screenH = host.resources.displayMetrics.heightPixels
        host.prefs!!.edit().putInt("rest_px", i2).putInt("rest_fy", i)
                .putFloat("rest_rx", i2 / i3.toFloat()).putFloat("rest_ry", i / screenH.toFloat()).apply()
        // 【弹簧】贴边吸附改 snappy（ζ=0.73）：临到边带一点过冲，落位更像「被吸住」。
        //   句柄仍存 host.snapAnim，onDestroy / 重排前的 cancel 逻辑不受影响。
        val fromX = lp.x
        host.snapAnim = Springs.drive(Springs.snappy(), object : Springs.Listener {
            override fun onUpdate(p: Float) {
                host.lp!!.x = Springs.lerpInt(fromX, round2, p)
                host.safeUpdate()
            }

            override fun onEnd() {
                host.lp!!.x = round2
                host.safeUpdate()
            }
        })
    }

    fun enterEdgePeek(z: Boolean) {
        val petView = host.petView
        if (petView == null) {
            return
        }
        host.edgePeek = true
        host.edgeAtLeft = z
        petView.setEdgePeek(true, z)
        host.prefs!!.edit().putInt("edge_side", if (!z) 1 else 0).apply()
    }

    fun exitEdgePeek() {
        val petView = host.petView
        val layoutParams = host.lp
        if (!host.edgePeek || petView == null || layoutParams == null) {
            return
        }
        host.edgePeek = false
        val pivotLocalX = layoutParams.x + petView.pivotLocalX()
        // 【气泡·静止锚点】气泡在播时窗口被抬高了，退贴边重摆前先换算回静止位，人偶不跳。
        val pivotLocalY = layoutParams.y + petView.pivotLocalY() + bubbleHeightIfUp()
        petView.setEdgePeek(false, false)
        host.placeByPivot(Math.round(pivotLocalX), Math.round(pivotLocalY))
        host.prefs!!.edit().putInt("edge_side", -1).apply()
    }

    fun layoutPeek(i: Int, i2: Int, i3: Int, i4: Int) {
        if (host.petView == null || host.lp == null || !host.added) {
            return
        }
        if (!host.peek) {
            host.peek = true
            val valueAnimator = host.snapAnim
            if (valueAnimator != null) {
                valueAnimator.cancel()
            }
            host.ui.removeCallbacks(host.hideBubble)
            host.petView!!.clearBubble()
            host.petView!!.setBubbleHeight(0)
            host.bubbleUp = false
            host.petView!!.setEdgePeek(false, false)
            host.petView!!.setPeek(true)
        }
        val f = host.resources.displayMetrics.density
        val i5 = host.resources.displayMetrics.widthPixels
        val petView = host.petView!!
        val windowWidth = petView.windowWidth()
        val baseHeight = petView.baseHeight()
        // 搭接量按比例走：写死 8dp 在放大后显得几乎不重叠，头与聊天框之间会露出缝隙。
        val round = Math.max(Math.round(8.0f * f), Math.round(petView.petWidth() * PetView.PEEK_OVERLAP_RATIO))
        host.applyPetShown(true)
        // 【不再隐藏】聊天窗顶边已被 topLimit 夹在人偶下方，正常不会再撞到屏顶；
        // 但首帧 / 键盘竞态 / topLimit 过期等瞬时仍可能越界，此时把窗口夹进屏幕内保持可见，
        // 而不是 setPetShown(false) 让它「从上方消失」（隐藏后 peek 仍为真且无自动恢复）。
        val minTop = Math.round(f * 4.0f)
        host.petTopY = Math.max(minTop, (i2 - baseHeight) + round)
        val lp = host.lp!!
        lp.width = windowWidth
        lp.height = baseHeight
        lp.x = Math.max(0, Math.min(i5 - windowWidth, i + ((i3 - windowWidth) / 2)))
        lp.y = host.petTopY
        host.safeUpdate()
    }

    fun updateTouchable() {
        val lp = host.lp
        if (host.petView == null || lp == null) {
            return
        }
        val z = host.petShown && !PetPrefs.touchThrough(host)
        val i = lp.flags
        if (z) {
            lp.flags = lp.flags and -17
        } else {
            lp.flags = lp.flags or 16
        }
        if (lp.flags != i) {
            host.safeUpdate()
        }
    }

    fun setPetShown(z: Boolean) {
        val petView = host.petView
        if (petView == null || host.lp == null || z == host.petShown) {
            return
        }
        host.petShown = z
        petView.setVisibility(if (z) 0 else 8)
        host.updateTouchable()
    }

    fun exitPeek() {
        if (host.peek) {
            host.peek = false
            host.applyPetShown(true)
            if (host.petView == null || host.lp == null || !host.added) {
                return
            }
            val f = host.resources.displayMetrics.density
            val i = host.resources.displayMetrics.widthPixels
            val i2 = host.resources.displayMetrics.heightPixels
            val round = Math.round(f * 8.0f)
            val petView = host.petView!!
            petView.setPeek(false)
            petView.clearBubble()
            petView.setBubbleHeight(0)
            petView.setEdgePeek(host.edgePeek, host.edgeAtLeft)
            host.bubbleUp = false
            val petWidth = petView.petWidth() / 2
            // 【v2.10.2】改走比例优先的读取：旧像素键在旋转后会把窗口摆到屏外。
            val rest = restorePivot()
            var i3 = if (rest != null) rest[0] else (i - round) - petWidth
            val i4 = if (rest != null) rest[1] else Math.round(i2 * 0.8f)
            if (!host.edgePeek) {
                i3 = Math.max(petWidth, Math.min(i - petWidth, i3))
            }
            host.placeByPivot(i3, host.clampFeetY(i4, i2, round))
        }
    }

    fun applyDragPlacement() {
        val currentTimeMillis = System.currentTimeMillis()
        if (currentTimeMillis - host.lastPlaceAt < 12) {
            return
        }
        host.lastPlaceAt = currentTimeMillis
        host.placeByPivot(Math.round(host.curPivotX), Math.round(host.curPivotY))
    }
}
