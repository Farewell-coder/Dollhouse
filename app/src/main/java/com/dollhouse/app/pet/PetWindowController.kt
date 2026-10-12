package com.dollhouse.app.pet

import android.os.Build
import com.dollhouse.app.PetService
import com.dollhouse.app.data.DollStore
import com.dollhouse.app.data.PetPrefs

/**
 * 【职责】桌宠多实例的挂载与协调：按 DollStore.enabled() 逐个建 PetWindow，全部挂上 WindowManager。
 *
 * 【入口】由 PetService 构造时创建（PetWindowController(this)）；
 *   PetService 的 onStartCommand / onConfigurationChanged / REFRESH / onDestroy 通过它统一驱动所有窗口。
 *
 * 【多实例】enabled 为空时回落到「至少展示默认配置」（DollStore.defaultDoll），保持单人偶默认行为；
 *   每个 PetWindow 自带 PetView / LayoutParams / 坐标 / 贴边 / 气泡 / 动画取消句柄，互不覆盖。
 *
 * 【主窗】首个启用的人偶记为 host.primary，承载 PetActionRegistry 的手势动作与迷你聊天入口；
 *   host.activeWindow 记录最近一次交互窗口，tap / 三击落到它身上，避免多窗口时动作串到主窗。
 *
 * 【扩展】新增窗口级状态在 PetWindow 内加字段；新增协调逻辑（如整体显隐）在本类加方法即可。
 *
 * 【坑】attachAll 用 isAttached() 判重，不能在「全部挂载失败」时把自己卡死——
 *   失败实例会在下一次 attachAll 前由 detachAll 清掉再重建。
 */
class PetWindowController(private val host: PetService) {
    private val windows = ArrayList<PetWindow>()

    /**
     * 【r12】给「当前在展示的每一只人偶」打上消失时刻。
     *
     * 【判据·用户定案】「只要人偶从屏幕消失 …… 不会自动回来 那都算人偶消失；
     *   会自动回来的就不算人偶消失，算暂时离场」。
     *   ⇒ 只有「消失且不会自己回来」才打点。暂离有定时器会回来，**不打点**。
     *
     * 【调用时机】关闭人偶（ACTION_STOP）—— 这是「消失且不会自动回来」的主路径。
     * 【为什么按窗口逐个打】窗口列表就是「此刻真的在屏幕上的那些人偶」，
     *   给它们逐一记录才符合「谁消失了给谁记」的语义。
     */
    fun markAllHidden() {
        val now = System.currentTimeMillis()
        for (w in windows) {
            PetPrefs.setHiddenAt(host, w.doll.id, now)
        }
    }

    /** 当前窗口数量（诊断用）。 */
    val count: Int
        get() = windows.size

    fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26) 2038 else 2002
    }

    /**
     * 本轮应当展示的人偶 = **已启用**的那些。
     *
     * 【r12 修 bug·没选人偶也出现人偶】去掉了这里原来的「一个都没启用时兜底展示默认配置」。
     *   需求定案：「没有选择使用人偶的情况下 就不要出现人偶 …… 必须得选择人偶 才会出现所选的人偶」。
     *   兜底会让用户在「一只都没启用」时仍然看到一只默认配置人偶，与「必须选」的语义直接冲突。
     *   现在空列表 = 什么都不挂（由 [PetService] 负责随之停服务）。
     */
    private fun dollsToShow(): List<DollStore.Doll> {
        return DollStore.enabled(host)
    }

    /** 【r12】当前有没有启用的人偶（供 PetService 判断要不要启动 / 停服务）。 */
    fun hasEnabledDoll(): Boolean {
        return DollStore.enabled(host).isNotEmpty()
    }

    /**
     * 按启用列表逐个挂载；每个窗口独立 PetView / LayoutParams / 动画句柄。
     * 已经挂着（至少一个窗口 added）时幂等返回，不重复挂。
     */
    fun attachAll() {
        if (isAttached()) {
            return
        }
        // 清掉上一轮可能残留的失败实例，避免 windows 非空但都没挂上时再也建不起来。
        detachAll()
        var first = true
        for (doll in dollsToShow()) {
            val window = PetWindow(host, doll)
            if (first) {
                host.primary = window
                first = false
            }
            windows.add(window)
            window.attach()
        }
    }

    /** 摘掉全部窗口并释放各自资源（PetBus 订阅 / 动画 / 迷你聊天 / 暂离计时）。 */
    fun detachAll() {
        for (window in windows) {
            window.detach()
        }
        windows.clear()
        host.primary = null
        host.activeWindow = null
    }

    /** 屏幕尺寸自检：逐窗口按当前屏幕复位（旋转 / 折叠屏 / 分屏）。 */
    fun checkScreenChanged() {
        for (window in windows) {
            window.checkScreenChanged()
        }
    }

    /** 主题切换后逐窗口刷新取色并重算比例。 */
    fun refreshTheme() {
        for (window in windows) {
            window.applyTheme()
            window.applyScale()
        }
    }

    fun applyScaleAll() {
        for (window in windows) {
            window.applyScale()
        }
    }

    fun updateTouchableAll() {
        for (window in windows) {
            window.updateTouchable()
        }
    }

    /** 取消全部窗口的暂离恢复（手动停止 / 销毁路径）。 */
    fun cancelAwayAll() {
        for (window in windows) {
            window.cancelAway()
        }
    }

    /** 是否至少有一个窗口挂在 WindowManager 上。 */
    fun isAttached(): Boolean {
        for (window in windows) {
            if (window.added) {
                return true
            }
        }
        return false
    }

    /** 是否有窗口处于暂离中。 */
    fun anyAway(): Boolean {
        for (window in windows) {
            if (window.away) {
                return true
            }
        }
        return false
    }
}
