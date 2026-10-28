package com.dollhouse.app

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
import android.view.WindowManager
import com.dollhouse.app.core.Logs
import com.dollhouse.app.data.PetPrefs
import com.dollhouse.app.device.LamdaManager
import com.dollhouse.app.pet.PetNotifier
import com.dollhouse.app.pet.PetWindow
import com.dollhouse.app.pet.PetWindowController
import java.util.Random

/**
 * 【职责】桌宠前台服务：服务生命周期 + 常驻通知 + lamda 巡检，并把所有悬浮窗交给 PetWindowController 管理。
 *
 * 【多实例】启动时按 DollStore.enabled() 逐个建 PetWindow（一个都没启用则兜底默认配置），
 *   每个窗口自带 PetView / LayoutParams / 坐标 / 贴边 / 气泡 / 动画取消句柄，同时挂在 WindowManager 上互不覆盖。
 *   本类不再持有任何单一窗口状态（那些字段已下沉到 PetWindow 实例）。
 *
 * 【交互】收到 MainActivity 的 START/STOP/REFRESH 动作；手势 / 台词 / 迷你聊天由各 PetWindow 处理，
 *   只有 PetActionRegistry 需要的少数入口（petJump / say / openMiniTalk）在这里转发到「当前交互窗口」。
 *
 * 【坑】ACTION_START 里 attachAll 用 isAttached() 判重，暂离中（anyAway）不重新挂载，
 *   保持既有「暂离 → 到点自己回来」的单一语义。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
class PetService : Service() {
    internal lateinit var prefs: SharedPreferences
    internal lateinit var wm: WindowManager
    internal val random = Random()
    internal val ui = Handler(Looper.getMainLooper())
    private lateinit var win: PetWindowController

    /** 主窗（首个启用人偶）：PetActionRegistry 的对外入口与迷你聊天默认落到它。 */
    internal var primary: PetWindow? = null

    /** 最近一次交互的人偶窗口：tap / 三击等手势动作落到它，避免多窗口时串到主窗。 */
    internal var activeWindow: PetWindow? = null

    /**
     * 【lamda 自动保活】巡检任务：60 秒一次读缓存判定，几乎为零开销。
     * 与 onDestroy 共用 ui Handler，销毁时会被一并清掉，无需额外注销逻辑。
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

    /**
     * 【无感保活】返回本地 Binder，让「服务是否活着」可被同进程探测（flags=0 bindService 时回调才会来）。
     */
    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    /** 进程内 Binder 句柄：只用于存活探测，不承载业务调用。 */
    inner class LocalBinder : Binder()

    override fun onCreate() {
        super.onCreate()
        prefs = PetPrefs.get(this)
        wm = getSystemService("window") as WindowManager
        win = PetWindowController(this)
        // startForegroundService 有严格时限：必须在数据种子、对账等其他初始化之前升前台。
        try {
            PetNotifier.startForegroundCompat(this)
        } catch (t: Throwable) {
            Logs.w("Dollhouse", "PetService 无法升为前台服务", t)
            PetNotifier.stopSafely(this)
            return
        }
        // 【r11 种子】默认配置的交谈 / 性格内容继承改造前的内置值；幂等，只写一次。
        com.dollhouse.app.data.DollProfile.seedDefault(this)
        // 【r12 消失对账】上一轮若没走到 onDestroy（被系统杀掉），人偶也算「消失」：
        //   给当时启用的每只补一次打点，然后清掉脏标记。
        //   【为什么放在 attach 之前】打点必须在窗口重建、消费 takeHiddenMs 之前完成。
        if (PetPrefs.serviceDirty(this)) {
            val now = System.currentTimeMillis()
            for (d in com.dollhouse.app.data.DollStore.enabled(this)) {
                PetPrefs.setHiddenAt(this, d.id, now)
            }
            PetPrefs.setServiceDirty(this, false)
        }
        ui.postDelayed(lamdaGuard, LAMDA_GUARD_FIRST_MS)
    }

    override fun onStartCommand(intent: Intent?, i: Int, i2: Int): Int {
        val action = if (intent == null) ACTION_START else intent.action
        if (ACTION_STOP == action) {
            // 用户主动关闭：置位标志，之后被划掉 / 被系统杀 / 重启，都不再自动拉起；取消暂离恢复。
            win.cancelAwayAll()
            PetPrefs.setUserStopped(this, true)
            // 【r12 用户定案】关闭 = 人偶从屏幕消失且**不会自动回来** ⇒ 算「人偶消失」，给每只打点。
            //   按 dollId 存（不再是全局一个键），多只人偶各记各的，不会互相冲掉。
            win.markAllHidden()
            // 正常收尾：清掉脏标记，避免下次 onCreate 误判成「被杀」再补打一次点。
            PetPrefs.setServiceDirty(this, false)
            PetNotifier.stopSafely(this)
            return 2
        }
        if (ACTION_RELOAD == action) {
            // 人偶集合发生变化（启用 / 停用 / 新增 / 删除 / 换形象）：整组窗口重建。
            // attachAll 内部自带 detachAll 兜底，这里无需先拆；暂离中的窗口也一并复位重建。
            win.detachAll()
            win.attachAll()
            return 1
        }
        if (ACTION_REFRESH == action) {
            // 主题切换后刷新桌宠取色；顺带做屏幕尺寸自检（拿 REFRESH 当「旋转必然经过的一个点」）。
            // 各窗口内部按 added 守卫，暂离中的窗口不会被误操作。
            win.updateTouchableAll()
            win.refreshTheme()
            win.checkScreenChanged()
            return 1
        }
        // 【r13 用户定案】编辑人偶期间不让外部启动桌宠。
        //   「只要在编辑界面 就无法打开了外面的人偶 只能通过预览打开当前卡片的人偶」——
        //   编辑页已把在跑的桌宠关掉，若此时从磁贴 / 通知 / 外部链接又拉起来，
        //   桌面上就会同时出现「桌宠」和「预览」两只人偶，语义冲突。
        //   这里与首页的拦截形成双保险（外部入口绕过首页也挡得住）。
        if (PetPrefs.isEditing(this)) {
            PetNotifier.stopSafely(this)
            return 2
        }
        // 【r12 修 bug·没选人偶也出现人偶】启动前先确认「有没有启用的人偶」。
        //   没有任何一只启用时：不挂窗口、直接停服务，并弹一条通知告诉用户为什么。
        //   需求定案：「没有选择使用人偶的情况下 就不要出现人偶 …… 必须得选择人偶 才会出现所选的人偶」。
        //   【为什么在这里兜底】外部入口（快捷磁贴 / 外部链接 / 开机广播）绕过首页直接发 ACTION_START，
        //   只在首页拦是拦不住的，服务侧必须也挡一道。
        if (!win.hasEnabledDoll()) {
            PetNotifier.notifyNoDoll(this)
            PetNotifier.stopSafely(this)
            return 2
        }
        // 【r12】置位「服务在跑」脏标记：正常收尾（STOP / onDestroy）会清掉它；
        //   若下次 onCreate 发现它还在，就说明上一轮被系统杀了 —— 那也算「人偶消失」。
        PetPrefs.setServiceDirty(this, true)
        // 走到这里说明是用户主动启动（首页按钮 / 通知栏），清除主动停止标志。
        PetPrefs.setUserStopped(this, false)
        // 【r12】消失时长改为**按人偶**在 attach 时各自取（见 PetWindow.attach）——
        //   每只人偶有自己的 returnSeconds，用一个全局时长无法区分。
        PetNotifier.startForegroundCompat(this)
        // 暂离中 onTaskRemoved 会发 ACTION_START 保活：维持前台即可，不借机重新挂载 / 隐式显示。
        if (win.anyAway()) {
            return 1
        }
        if (!win.isAttached()) {
            if (!Settings.canDrawOverlays(this)) {
                PetNotifier.stopSafely(this)
                return 2
            }
            win.attachAll()
        }
        return 1
    }

    /**
     * 屏幕尺寸变化回调（旋转 / 折叠屏展开 / 分屏）：交给 win 把每个窗口按新屏幕重新摆位。
     */
    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        win.checkScreenChanged()
    }

    /**
     * 保活：用户在「最近任务」里把本卡片划掉时回调，未主动关闭则自动拉起。
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
        win.cancelAwayAll()
        ui.removeCallbacksAndMessages(null)
        // detachAll 会逐个取消动画、释放迷你聊天、注销 PetBus 订阅并摘窗。
        win.detachAll()
        // 【r12】走到这里说明是正常销毁（STOP 收尾也会到这里）：清掉脏标记。
        //   下一次 onCreate 若发现标记还在，就说明这一轮是被系统杀的，需要补打消失点。
        PetPrefs.setServiceDirty(this, false)
        super.onDestroy()
    }

    // ---------- 以下为 PetActionRegistry / 对外入口的转发薄壳：落到「当前交互窗口」，无则主窗 ----------

    private fun target(): PetWindow? {
        return activeWindow ?: primary
    }

    internal fun petJump() {
        target()?.petJump()
    }

    /** 单击台词入口（PetActionRegistry 调用）。 */
    fun say(str: String, j: Long) {
        target()?.say(str, j)
    }

    internal fun openMiniTalk() {
        target()?.openMiniTalk()
    }

    /** 旧的全屏聊天出口：三击已改走迷你输入框。 */
    internal fun toggleChat() {
        openMiniTalk()
    }

    internal fun openPanel() {
        target()?.openPanel()
    }

    companion object {
        const val ACTION_REFRESH = "REFRESH"
        const val ACTION_RELOAD = "RELOAD"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"

        /**
         * 【r11 起只作种子】单击台词的出厂默认值，由 [com.dollhouse.app.data.DollProfile.seedDefault]
         *   写入「默认配置」人偶的「交谈内容」；运行期取的是 DollProfile.talkLines（按人偶取）。
         *   本常量不再直接参与单击行为。
         */
        @JvmField
        internal val LINES_TAP = arrayOf("你好呀～", "我一直都在哦。", "有什么想聊的吗？")

        internal const val SNAP_ZONE_RATIO = 0.25f

        /** 连击判定窗口：窗口内累计到 3 次才算三击。 */
        internal const val TAP_WINDOW_MS = 350L

        /** 人偶与迷你输入框之间的间隙（dp）。 */
        internal const val PET_HOVER_GAP_DP = 6.0f

        /** lamda 设备服务巡检周期（毫秒）。 */
        internal const val LAMDA_GUARD_MS = 60000L

        /** 人偶刚起时的首次巡检延迟。 */
        internal const val LAMDA_GUARD_FIRST_MS = 15000L
    }
}
