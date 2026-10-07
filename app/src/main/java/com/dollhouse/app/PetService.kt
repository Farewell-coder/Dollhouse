package com.dollhouse.app

import com.dollhouse.app.pet.PetActionRegistry
import com.dollhouse.app.pet.PetBus
import com.dollhouse.app.pet.PetNotifier
import com.dollhouse.app.pet.PetTalk
import com.dollhouse.app.pet.PetTalkInput
import com.dollhouse.app.pet.PetView
import com.dollhouse.app.pet.PetWindowController
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import com.dollhouse.app.MainActivity
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.device.LamdaManager
import com.dollhouse.app.ui.theme.UiKit
import java.util.Random

/**
 * 【职责】桌宠前台服务：持有悬浮窗、驱动动画与随机台词、常驻通知。
 *
 * 【交互】收到 MainActivity 的 START/STOP/REFRESH 动作；attachPet() 把 PetView 挂进 WindowManager；长按/点击手势转成聊天窗口与台词。
 *
 * 【坑】attachPet() 用 catch(Throwable) 吞掉所有异常然后 stopSelfSafely() —— 悬挂浮窗失败时会静默退出，排查「桌宠莫名消失」要看 logcat -b events 里的 am_foreground_service_stop，而不是找 crash 日志。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
class PetService : Service() {
    internal var curPivotX = 0f
    internal var curPivotY = 0f
    internal var downPivotX = 0f
    internal var downPivotY = 0f
    internal var downRawX = 0f
    internal var downRawY = 0f
    internal var lastMoveAt = 0L
    internal var lastRawX = 0f
    internal var lp: WindowManager.LayoutParams? = null
    internal var petTopY = 0
    internal var petView: PetView? = null
    internal lateinit var prefs: SharedPreferences
    internal var snapAnim: ValueAnimator? = null
    internal var touchSlop = 0
    internal lateinit var wm: WindowManager
    internal val random = Random()
    internal val ui = Handler(Looper.getMainLooper())
    private lateinit var win: PetWindowController
    internal var bubbleUp = false
    internal var added = false
    internal var peek = false
    internal var edgePeek = false
    /** 已应用到 PetView 的缩放系数；REFRESH 里用来判断「比例真的变了没」。 */
    internal var petScaleApplied = 0.0f
    internal var edgeAtLeft = false
    internal var petShown = true
    internal var lastPlaceAt = 0L
    internal var dragging = false
    internal val hideBubble = Runnable { hideBubbleNow() }
    internal val busListener: PetBus.Listener = object : PetBus.Listener {
        override fun onSay(str: String, j: Long) {
            this@PetService.ui.post { this@PetService.say(str, j) }
        }

        override fun onAffection(i: Int) {
            this@PetService.ui.post {
                this@PetService.petView?.setAffection(i)
            }
        }
    }
    internal val geom = FloatArray(4)
    /** 当前已连击次数，singleTap 到期时消费并清零。 */
    internal var tapCount = 0
    /** 迷你输入框（三击召唤）与它的逻辑层；常驻复用，不反复建窗。 */
    internal var talkInput: PetTalkInput? = null
    internal var talk: PetTalk? = null
    /** 迷你聊天开启时人偶脚底的 y：整组（人偶 + 输入框）摆位的锚点，键盘上移也基于它。 */
    internal var talkFeetY = 0
    /** 最近一次输入法高度，重排时沿用，避免被 0 冲掉。 */
    internal var talkIme = 0
    /**
     * 迷你聊天开启那一刻的人偶水平中心（屏幕坐标）。
     * 【为何要固定】进 peek 时窗口是「人偶宽」，lp.x + lp.width/2 就是人偶视觉中心；
     *   摆完框后 lp.width 变成框宽，再拿 lp.x + lp.width/2 当基准会逐次漂移（框越走越偏）。
     *   故只在首次进入 peek 时记下来，之后一直以它为基准摆框。
     */
    internal var talkAnchorX = 0
    /**
     * 本轮发送是否刚发生（一个点击周期内有效）。
     * 【为什么需要】发送后人偶立刻站起来（peek=false），同一轮触摸的 UP 落到 handleTouch
     *   时 peek 已为假，会被当成「非对话态点击」而误触单击台词。用本标志把这一下让给「看回复」。
     */
    internal var talkOnSend = false
    /**
     * 迷你输入框当前是否应该存在。
     *
     * 【为何需要】layoutMiniTalk 末尾会「没显示就 show」，而发完消息后输入框已经被
     *   hideQuiet 收掉、紧接着又会重排一次（键盘高度归零），于是它会被重新拉出来 ——
     *   与「发完只留气泡看回复」的约定相矛盾。用本标志把「摆位」与「是否拉起输入框」分开。
     */
    internal var talkInputWanted = false
    internal val singleTap = Runnable {
        val n = this@PetService.tapCount
        this@PetService.tapCount = 0
        if (n >= 3) {
            PetActionRegistry.perform("triple_tap", this@PetService)
        } else if (n == 1) {
            PetActionRegistry.perform("tap", this@PetService)
        }
        // n == 2：双击聊天已取消，不产生任何动作。
    }

    /**
     * 【无感保活】返回本地 Binder，让「服务是否活着」可被同进程探测。
     *
     * 【为什么必须改成这样】getRunningServices 自 API 26 起只能看到本进程内的服务，
     *   唯一能区分「服务活着」与「进程活着」的手段是 bindService 并看回调是否到达；
     *   探测侧用 flags=0（不创建服务），只有这里返回了 Binder，回调才会来。
     * 【影响面】本 Binder 不出进程（组件 exported=false），全工程无其它 onBind 调用方，
     *   属纯加法改动，不改变任何既有行为。
     */
    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    /**
     * 进程内 Binder 句柄：只用于存活探测，不承载业务调用。
     *
     * 【为什么是非静态内部类】静态嵌套类里没有外围实例，写 `PetService.this` 会直接
     *   编译失败（non-static variable this cannot be referenced from a static context）；
     *   而探测侧只关心「onBind 有没有被调到」，返回一个实心 Binder 就够了。
     */
    inner class LocalBinder : Binder()

    /**
     * 【lamda 自动保活】巡检任务。
     *
     * 【为什么挂在这里】lamda 是 shell 进程，被系统回收时不会有任何通知；本服务是前台服务、
     *   活着是常态，借它的心跳做巡检是最省的做法 —— 不新开 Service、不新注册常驻组件、
     *   不动 Manifest。人偶本身掉了由 keepalive 那套拉回来，这里只负责 lamda。
     * 【为什么用 ui Handler】与 onDestroy 共用同一个 Handler，销毁时会被
     *   removeCallbacksAndMessages(null) 一并清掉，无需额外注销逻辑。
     * 【开销】60 秒一次读缓存判定，几乎为零；只有探测到没活着才起线程去真拉起。
     */
    internal val lamdaGuard: Runnable = object : Runnable {
        override fun run() {
            try {
                LamdaManager.guardTick(this@PetService)
            } catch (ignored: Throwable) {
                Logs.w("Dollhouse", "ignored", ignored)
            }
            this@PetService.ui.postDelayed(this, LAMDA_GUARD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PetPrefs.get(this)
        wm = getSystemService("window") as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        win = PetWindowController(this)
        ui.postDelayed(lamdaGuard, LAMDA_GUARD_FIRST_MS)
    }

    override fun onStartCommand(intent: Intent?, i: Int, i2: Int): Int {
        val action = if (intent == null) ACTION_START else intent.action
        if (ACTION_STOP == action) {
            // 用户主动关闭：置位标志，之后被划掉 / 被系统杀 / 重启，都不再自动拉起。
            PetPrefs.setUserStopped(this, true)
            PetNotifier.stopSafely(this)
            return 2
        }
        if (ACTION_REFRESH == action) {
            updateTouchable()
            // 主题切换后刷新桌宠取色：颜色常量已被 ThemeManager 整体覆写，这里让画布重读。
            val pv = petView
            if (pv != null) {
                pv.applyTheme()
                win.applyScale()
            }
            // 【v2.10.2】顺带做一次屏幕尺寸自检：REFRESH 是 MainActivity 每次回到前台都会发的动作，
            // 拿它当「旋转后必然经过的一个点」用，比只等系统回调更稳。
            win.checkScreenChanged()
            return 1
        }
        // 走到这里说明是用户主动启动（首页按钮 / 通知栏），清除主动停止标志。
        PetPrefs.setUserStopped(this, false)
        PetNotifier.startForegroundCompat(this)
        if (!added) {
            if (!Settings.canDrawOverlays(this)) {
                PetNotifier.stopSafely(this)
                return 2
            }
            attachPet()
        }
        return 1
    }

    /**
     * 【v2.10.2】屏幕尺寸变化回调（旋转 / 折叠屏展开 / 分屏）。
     *
     * 【为什么必须自己有】Manifest 里没有声明 configChanges，所以系统在配置变化时
     *   会重建 Activity 但不会重建本前台 Service —— 悬浮窗带着旧坐标继续挂着，
     *   竖屏存的 rest_fy(屏高*0.8=1920) 在横屏(屏高1080)下直接落到屏外，表现就是「人偶消失」。
     *   这里接一下回调，交给 win 重新摆位。
     *
     * 【注意】部分 ROM 在桌面（非本 App 前台）旋转时不一定派发这个回调，
     *   所以 PetView 的帧内自检 screenTick 是同一件事的第二道保险，两者幂等可重复调。
     */
    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        win.checkScreenChanged()
    }

    /**
     * 保活：用户在「最近任务」里把本卡片划掉时回调。
     * 只有当用户没有主动点过「关闭人偶」时才自动拉起，避免和用户的意愿打架。
     * 直接 startForegroundService，不抢前台、不重建任务栈。
     */
    override fun onTaskRemoved(intent: Intent?) {
        super.onTaskRemoved(intent)
        try {
            if (PetPrefs.userStopped(this)) {
                return
            }
            val restart = Intent(applicationContext, PetService::class.java)
            restart.action = ACTION_START
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(restart)
            } else {
                startService(restart)
            }
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        snapAnim?.cancel()
        talkInput?.release()
        talk?.release()
        detachPet()
        super.onDestroy()
    }

    // 悬浮窗类型：SDK>=26 用 APPLICATION_OVERLAY，否则用 PHONE。
    // 把 PetView 挂进 WindowManager；任何失败都会静默退出服务。
    // 从 WindowManager 摘掉 PetView 并复位状态。
    fun say(str: String, j: Long) {
        val pv = petView
        if (pv == null || !added) {
            return
        }
        ui.removeCallbacks(hideBubble)
        pv.showBubble(str)
        if (peek) {
            // peek 态：人偶是趴姿，窗口高由整组布局算，这里只标「有气泡」再重排。
            bubbleUp = true
            layoutMiniTalk(talkIme)
        } else {
            expandBubble(pv.neededBubbleSpace())
        }
        ui.postDelayed(hideBubble, j)
    }

    /** 收气泡：peek 态只收气泡并重排整组（保住趴姿与输入框），非 peek 态沿用原逻辑。 */
    internal fun hideBubbleNow() {
        val pv = petView
        if (peek && pv != null) {
            pv.clearBubble()
            pv.setBubbleHeight(0)
            bubbleUp = false
            layoutMiniTalk(talkIme)
            return
        }
        collapseBubble()
    }

    /** 常驻气泡：显示后不排自动消失，相位由 PetTalk 自己管。 */
    internal fun saySticky(str: String) {
        val pv = petView
        if (pv == null || !added) {
            return
        }
        ui.removeCallbacks(hideBubble)
        pv.showBubble(str)
        if (peek) {
            bubbleUp = true
            layoutMiniTalk(talkIme)
        } else {
            expandBubble(pv.neededBubbleSpace())
        }
    }

    fun handleTouch(motionEvent: MotionEvent): Boolean {
        // 【坑】peek 态不能一律早退：否则下面的拖动分支永远不可达，
        //   输入框开着时想拖走人偶会被当成「点一下」把聊天收掉。
        //   现在 peek 态照样走拖拽判定，只有「没拖动就松手」才按点击处理（推进气泡相位）。
        val actionMasked = motionEvent.actionMasked
        if (actionMasked == 0) {
            downRawX = motionEvent.rawX
            downRawY = motionEvent.rawY
            lastRawX = downRawX
            lastMoveAt = System.currentTimeMillis()
            val lpv = lp!!
            val pv = petView!!
            downPivotX = lpv.x + pv.pivotLocalX()
            val pivotLocalY = lpv.y + pv.pivotLocalY()
            downPivotY = pivotLocalY
            curPivotX = downPivotX
            curPivotY = pivotLocalY
            dragging = false
            snapAnim?.cancel()
            return true
        }
        if (actionMasked != 1) {
            if (actionMasked == 2) {
                val rawX = motionEvent.rawX - downRawX
                val rawY = motionEvent.rawY - downRawY
                val currentTimeMillis = System.currentTimeMillis()
                val rawX2 = (motionEvent.rawX - lastRawX) / Math.max(0.008f, (currentTimeMillis - lastMoveAt) / 1000.0f)
                lastRawX = motionEvent.rawX
                lastMoveAt = currentTimeMillis
                if (!dragging && Math.hypot(rawX.toDouble(), rawY.toDouble()) > touchSlop) {
                    dragging = true
                    // 清掉在途的连击判定：否则「点一下后 350ms 内开始拖动」会在拖动途中多触发一次 tap。
                    ui.removeCallbacks(singleTap)
                    tapCount = 0
                    // 拖动人偶时先摘掉迷你聊天（约定：不让框跟着拖动变成半截），
                    // 但不动人偶位置 —— 紧接着的拖动流程会接管摆放。
                    if (peek) {
                        abortMiniTalkForDrag()
                    }
                    collapseBubble()
                    exitEdgePeek()
                    petView!!.setDragging(true)
                }
                if (dragging) {
                    curPivotX = downPivotX + rawX
                    curPivotY = downPivotY + rawY
                    applyDragPlacement()
                    petView!!.setDragVelocity(rawX2)
                    petView!!.lookAt(Math.max(-1.0f, Math.min(1.0f, rawX2 / 900.0f)))
                }
                return true
            }
            if (actionMasked != 3) {
                return false
            }
        }
        if (dragging) {
            petView!!.setDragging(false)
            placeByPivot(Math.round(curPivotX), Math.round(curPivotY))
            settle()
        } else if (motionEvent.actionMasked == 1) {
            if (peek && !talkOnSend) {
                // 对话打开中（还没发送）：点人偶 = 收框退场，不走连击计数。
                onPetTapWhileTalking()
            } else {
                // 非对话态，或已发送（人偶站起来了）看回复：走统一点击入口。
                // 【修复】旧代码在非 peek 态无条件走 onTap() 连击计数，
                //   而回复到位后人偶已站起来（peek=false），点它只计数、
                //   气泡永远不翻页也不收掉（用户报「点它没反应」）。
                onPetClicked()
            }
            talkOnSend = false
        }
        dragging = false
        return true
    }

    // 按锚点把窗口摆到指定坐标（含边界约束）。
    // 记住当前锚点位置，供下次启动恢复。
    internal fun openPanel() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = 335544320
        try {
            startActivity(intent)
            return
        } catch (unused: Throwable) {
        }
        // ColorOS 等系统会拦截后台服务直接 startActivity；改用 PendingIntent
        // 走系统允许的通道再试一次，失败则静默放弃（长按桌宠仍可重试）。
        try {
            val pending = PendingIntent.getActivity(this, 2, intent, 201326592 or 67108864)
            pending.send()
        } catch (ignored: Throwable) {
            Logs.w("Dollhouse", "ignored", ignored)
        }
    }

    /** 让桌宠原地跳一下（单/双击的共用动作）。 */
    internal fun petJump() {
        petView?.playJump()
    }

    /**
     * 【已废弃】旧的全屏聊天入口。
     * 【改动】人偶不再把人送进全屏聊天页 —— 三击改成召唤迷你输入框（openMiniTalk）。
     *   全屏页仍可从首页「打开聊天」进入，那条路径与本类无关。
     */
    internal fun toggleChat() {
        openMiniTalk()
    }

    /** 三击召唤：挂出迷你输入框，人偶趴到框顶。 */
    internal fun openMiniTalk() {
        if (petView == null || lp == null || !added) {
            return
        }
        ensureMiniTalk()
        talkInputWanted = true
        layoutMiniTalk(talkIme)
    }

    /** 首次使用时组装逻辑层与输入框（之后常驻复用，不反复建窗）。 */
    private fun ensureMiniTalk() {
        if (talk == null) {
            talk = PetTalk(this, newTalkHost())
        }
        if (talkInput == null) {
            talkInput = PetTalkInput(this, newTalkInputHost())
        }
    }

    /** PetTalk 的宿主回调：气泡分页 / 行数变化后的整组重排、气泡读回、忙碌态透传。 */
    private fun newTalkHost(): PetTalk.Host {
        return object : PetTalk.Host {
            override fun saySticky(str: String) {
                this@PetService.saySticky(str)
            }

            override fun sayTemp(str: String, j: Long) {
                this@PetService.say(str, j)
            }

            override fun clearBubble() {
                this@PetService.hideBubbleNow()
            }

            override fun showBubblePaged(str: String, i: Int): Int {
                val petView = this@PetService.petView ?: return 0
                val pages = petView.showBubblePaged(str, i)
                // 【v2.7】分页后气泡高度跟着变（一片最多 PAGE_MAX_LINES 行）：
                //   必须重排一次，否则首屏还按「思考中…」的旧窗高画，多出一行被裁。
                if (this@PetService.peek && this@PetService.bubbleUp) {
                    this@PetService.layoutMiniTalk(this@PetService.talkIme)
                } else {
                    this@PetService.expandBubble(petView.neededBubbleSpace())
                }
                return pages
            }

            override fun bubblePage(i: Int): String? {
                return this@PetService.petView?.bubblePage(i)
            }

            override fun setBubbleLines(i: Int) {
                val petView = this@PetService.petView ?: return
                petView.setBubbleMaxLines(i)
                // 行数变了气泡高度就变，必须重排整组，否则窗高对不上文字。
                if (this@PetService.peek && this@PetService.bubbleUp) {
                    this@PetService.layoutMiniTalk(this@PetService.talkIme)
                }
            }

            override fun onBusy(z: Boolean) {
                this@PetService.talkInput?.setBusy(z)
            }

            override fun bubbleHasOverflow(): Boolean {
                val petView = this@PetService.petView
                return petView != null && petView.bubbleHasOverflow()
            }
        }
    }

    /** PetTalkInput 的宿主回调：输入框收起 / 发送 / 输入法高度变化时的整组重排。 */
    private fun newTalkInputHost(): PetTalkInput.Host {
        return object : PetTalkInput.Host {
            override fun onClosed() {
                // 收起输入框：人偶站起来（含气泡复位）。
                this@PetService.exitPeek()
            }

            override fun onSend(str: String) {
                // 【约定·用户定案】发完消息：收输入框 + 人偶从趴姿站回原位，
                //   回复走头顶普通气泡展示。（旧行为只重排不退出趴姿，
                //   于是「框收了、人偶还是半个头」。）
                // 【顺序要紧】exitPeek() 内部会 clearBubble()，必须先退趴姿再发请求，
                //   反过来会把刚点亮的「思考中…」一起清掉。
                this@PetService.talkIme = 0
                this@PetService.talkInputWanted = false
                this@PetService.talkOnSend = true
                this@PetService.exitPeek()
                this@PetService.talk?.talk(str)
            }

            override fun onIme(i: Int) {
                this@PetService.talkIme = i
                if (this@PetService.peek) {
                    this@PetService.layoutMiniTalk(i)
                }
            }
        }
    }

    /**
     * 迷你聊天的整组摆位：人偶完整悬浮在框上方，输入框紧贴人偶下方。
     * 【几何 v2.7】框顶 = 人偶脚底 + PET_HOVER_GAP_DP；人偶窗口 = 完整身高 + 气泡高；
     *   输入框 = 独立 overlay。整组以人偶脚底为锥点，不得越出屏幕可用区。
     * 【键盘】可用底边扣掉输入法高度，整组不够放就整体上移，人偶与框不会分家。
     */
    /** 三击召唤 / 改人偶比例后重排：把「人偶 + 迷你输入框」整组按当前锚点重新摆位。 */
    internal fun layoutMiniTalk(imeBottom: Int) {
        val pv = petView
        val lpv = lp
        if (pv == null || lpv == null || !added) {
            return
        }
        enterPeek(pv)
        val dm = resources.displayMetrics
        // 【定案 v2.6】不再「搭接」：人偶完整悬浮在输入框上方，中间留一条固定间隙。
        //   旧值 3dp 负搭接（框顶高于脚3dp）会让框盖住人偶下沿；
        //   用户要求「小人以完整形态悬浮在对话框上面」，故改为正间隙。
        val gap = Math.round(PET_HOVER_GAP_DP * dm.density)
        val barH = UiKit.dp(this, PetTalkInput.HEIGHT_DP)
        val edge = UiKit.dp(this, 8.0f)
        // 【定案 v2.5】框宽 = 「界面最大比例」时的宽度（petMaxWidth，默认 225dp），
        //   与人偶比例无关 —— 人偶调小后输入框照样宽，好打字（用户指正）。
        //   【但】人偶本身不跟着放大：onDraw 按当前比例绘制，在窗内水平居中，
        //   两侧留出透明带（用户要的是「框保持最大比例、小人不用保持」）。
        //   趴姿高度取 perchHeight()（当前比例），与绘制共用同一基准，头才贴住框顶。
        val width = Math.max(1, pv.petMaxWidth())
        // 【居中 v2.7】框以「人偶所在位置」为基准：每次重排都把框对人偶中心居中，
        //   并双侧夹回屏内（不越出屏幕）。锚点在首次进 peek 时锁死，避免逐次漂移。
        lpv.x = Math.max(0, Math.min(dm.widthPixels - width, talkAnchorX - width / 2))
        // 【人偶高度】取完整身高：peek 态人偶整只悬在框上（v2.6 定案），
        //   与 onDraw 的绘制基准一致，脚底正好落在窗口底边。
        //   【与框宽基准不同是有意的】框宽取最大比例（好打字），高度取当前比例（人偶多大画多大）。
        var perch = pv.perchHeight()
        if (perch <= 0) {
            perch = Math.max(1, pv.fullHeight())
        }
        // 【坑】先夹 x 再量气泡：贴边态下 lp.x 可能为负，直接拿去排字会算窄，
        //   等后面夹回屏内就多出一截空白（夹 x 已提到上面，紧跟 width 计算）。
        var bubble = 0
        if (bubbleUp) {
            pv.relayoutBubble(width, lpv.x)
            bubble = pv.neededBubbleSpaceFor(width, lpv.x)
            pv.setBubbleHeight(bubble)
        }
        val availBottom = dm.heightPixels - Math.max(0, imeBottom)
        // 【v2.7】整组「人偶 + 框」以人偶脚底为锥点向上生长，不得越出可用底边。
        //   输入框底边上限 = 可用底边 - 留白；据此反推 talkY 的上限。
        val maxTalkY = availBottom - edge - barH
        var baseTalkY = talkFeetY + gap
        if (baseTalkY > maxTalkY) {
            baseTalkY = maxTalkY
        }
        var bottom = baseTalkY - gap
        val minTop = Math.round(dm.density * 4.0f)
        // 人偶太高、顶部要越出屏幕：把人偶锥回屏内（头部小幅让位），
        //   保「框在屏内可见 + 两者间隙不变」。
        if (bottom - (perch + bubble) < minTop) {
            bottom = minTop + perch + bubble
            if (bottom + gap > maxTalkY) {
                bottom = maxTalkY - gap
            }
        }
        val talkY = bottom + gap
        var top = bottom - (perch + bubble)
        if (top < 0) {
            top = 0
        }
        petTopY = top
        lpv.width = width
        lpv.height = perch + bubble
        lpv.y = top
        safeUpdate()
        placeTalkInput(talkY, width)
    }

    /** 首次进 peek：锁死锚点（脚底 Y / 水平中心），清气泡、关贴边、切趴姿。 */
    private fun enterPeek(petView: PetView) {
        val lpv = lp!!
        talkFeetY = lpv.y + lpv.height
        // 【v2.7】此刻 lp.width 还是人偶宽、人偶画在窗内居中，中心就是人偶视觉中心。
        talkAnchorX = lpv.x + Math.max(1, lpv.width) / 2
        peek = true
        ui.removeCallbacks(hideBubble)
        petView.clearBubble()
        petView.setBubbleHeight(0)
        bubbleUp = false
        petView.setEdgePeek(false, false)
        petView.setPeek(true)
    }

    /** 输入框摆放：发完消息 / 退出趴姿时只收起；否则按当前位置显示或移动。 */
    private fun placeTalkInput(talkY: Int, width: Int) {
        val input = talkInput
        if (input == null) {
            return
        }
        if (!talkInputWanted) {
            // 发完消息 / 退出趴姿：只摆人偶与气泡，不把输入框再拉出来。
            if (input.isShowing()) {
                input.hideQuiet()
            }
            return
        }
        val x = lp!!.x
        if (input.isShowing()) {
            input.moveTo(x, talkY, width)
        } else {
            input.show(x, talkY, width)
        }
    }

    /** peek（迷你聊天开着）时点人偶：先推进气泡相位，没有气泡可推再收起输入框。 */
    private fun onPetTapWhileTalking() {
        if (talk?.onBubbleTap() == true) {
            return
        }
        // 气泡没有可推进的相位：整组退场（收框 + 人偶站起）。
        talkInputWanted = false
        talkInput?.hideQuiet()
        exitPeek()
    }

    /** 拖动人偶：先把迷你聊天整组摘掉（人偶照样能被拖走）。 */
    private fun abortMiniTalkForDrag() {
        if (!peek) {
            return
        }
        peek = false
        talkInputWanted = false
        val input = talkInput
        if (input != null && input.isShowing()) {
            // 【坑】必须用 hideQuiet 而不是 release：release 会把 root / input 置空，
            //   但 lpx 里登记的窗口仍挂在 WindowManager 上 —— 之后 show() 会再 addView，
            //   同一个 View 被 add 两次，抛 IllegalStateException（try/catch 吞掉后输入框从此拉不起来）。
            input.hideQuiet()
        }
        val pv = petView
        if (pv != null) {
            pv.clearBubble()
            pv.setBubbleHeight(0)
            pv.setPeek(false)
        }
        bubbleUp = false
        talkIme = 0
    }

    /**
     * 连击计数：窗口内累计次数，停手 TAP_WINDOW_MS 后统一派发。
     * 【契约】1 击 = 原地跳；2 击 = 无动作（双击聊天已取消）；3 击 = 召唤迷你输入框。
     */
    /**
     * 单击人偶的统一点击入口（用户定案的四态）。
     *
     * 【语义】没消息→跳一下 + 随机说；有回复→翻到下一片；最后一片→收掉；思考中→不打断。
     * 【延迟】多一下点击不会被吞掉：单击动作延后一轮连击窗口再执行，
     *   期间若有第二、三下点击会自行取消（与原来 tap/triple_tap 的判别方式一致）。
     */
    internal fun onPetClicked() {
        // 1) 有气泡在播：推进分页（思考中内部直接返回 true，不打断）。
        if (talk?.onBubbleTap() == true) {
            return
        }
        // 2) 没气泡：走原来的连击计数（单击跳 + 说、三击召框）。
        onTap()
    }

    private fun onTap() {
        tapCount++
        ui.removeCallbacks(singleTap)
        ui.postDelayed(singleTap, TAP_WINDOW_MS)
    }

    // ---------- 以下为 PetWindowController 的转发薄壳，对外契约不变 ----------
    internal fun overlayType(): Int = win.overlayType()

    internal fun attachPet() {
        win.attachPet()
    }

    internal fun detachPet() {
        win.detachPet()
    }

    internal fun expandBubble(i: Int) {
        win.expandBubble(i)
    }

    fun collapseBubble() {
        win.collapseBubble()
    }

    fun safeUpdate() {
        win.safeUpdate()
    }

    internal fun placeByPivot(i: Int, i2: Int) {
        win.placeByPivot(i, i2)
    }

    internal fun rememberPivot() {
        win.rememberPivot()
    }

    internal fun clampFeetY(i: Int, i2: Int, i3: Int): Int = win.clampFeetY(i, i2, i3)

    internal fun settle() {
        win.settle()
    }

    internal fun snapToEdge(z: Boolean, i: Int) {
        win.snapToEdge(z, i)
    }

    internal fun enterEdgePeek(z: Boolean) {
        win.enterEdgePeek(z)
    }

    internal fun exitEdgePeek() {
        win.exitEdgePeek()
    }

    internal fun updateTouchable() {
        win.updateTouchable()
    }

    internal fun applyPetShown(z: Boolean) {
        win.setPetShown(z)
    }

    /**
     * 退出趴姿：把 host.peek 清掉再委托 PetWindowController 复位。
     * 【为什么覆写】迷你聊天直接改 peek 而不走 layoutPeek，win 只读 host.peek，
     *   所以必须由这里同步清位，否则 win.exitPeek() 会因 host.peek 仍为 true 而正常复位，
     *   但 applyScale 之类读 host.peek 的分支会误判成「聊天框还开着」。
     */
    fun exitPeek() {
        win.exitPeek()
        peek = false
        talkIme = 0
        talkInputWanted = false
        // 【坑】必须连输入框一起静默收掉：applyScale 等路径会直接调本方法，
        //   若只复位趴姿，输入框 overlay 会孤零零挂在屏幕上直到 15 秒自愈。
        val input = talkInput
        if (input != null && input.isShowing()) {
            input.hideQuiet()
        }
        bubbleUp = false
    }

    internal fun applyScale() {
        win.applyScale()
    }

    internal fun applyDragPlacement() {
        win.applyDragPlacement()
    }

    companion object {
        const val ACTION_REFRESH = "REFRESH"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"

        /** 点一下人偶时随机说一句（原「定时自动冒台词」已按用户要求下线，改成点一下才说）。 */
        /**
         * 单击人偶时随机蹦一句（用户定案：单击 = 跳一下 + 随机说一句）。
         * 【坑】v2.4 删 chatter 时把台词入口一并删了，导致「点击没消息弹出来」；
         *   本轮在 PetActionRegistry 的 tap 动作里重新接线。
         */
        @JvmField
        internal val LINES_TAP = arrayOf("你好呀～", "我一直都在哦。", "有什么想聊的吗？")

        internal const val SNAP_ZONE_RATIO = 0.25f

        /** 连击判定窗口：窗口内累计到 3 次才算三击（双击聊天已取消，窗口留宽一点好按）。 */
        internal const val TAP_WINDOW_MS = 350L

        /**
         * 人偶与迷你输入框之间的间隙（dp）。
         * 【定案 v2.6】曾为负搭接 3dp（框顶盖住脚），用户要求「完整形态悬浮在框上面」，改正间隙。
         */
        internal const val PET_HOVER_GAP_DP = 6.0f

        /** lamda 设备服务巡检周期（毫秒）。 */
        internal const val LAMDA_GUARD_MS = 60000L

        /** 人偶刚起时的首次巡检延迟：等服务自己摆稳再干活，别在冷启高峰期抢资源。 */
        internal const val LAMDA_GUARD_FIRST_MS = 15000L
    }
}
