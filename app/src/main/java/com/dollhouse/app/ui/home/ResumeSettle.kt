package com.dollhouse.app.ui.home

import android.app.Activity
import android.content.Intent
import com.dollhouse.app.PetService
import com.dollhouse.app.core.Logs
import com.dollhouse.app.pet.PetToggle
import com.dollhouse.app.ui.compose.LayerGate
import com.dollhouse.app.ui.compose.PageStack

/**
 * 【职责】回前台时的**一次性对账**：把所有「只在事件驱动时才更新」的状态拉回真值。
 *
 * ## 为什么必须有一个统一入口（K-02）
 *
 * 改造前 `MainActivity.onResume` 只做了三件事：同步首页状态、重挂背景、把 Tab 收成
 * 「只有一个可见」。但交互权限、页面栈、桌宠手势窗口这三样**回前台时完全没重算**：
 *
 * | 状态 | 平时靠什么更新 | 切后台再回来会怎样 |
 * |---|---|---|
 * | 各层交互闸门 | 入栈 / 出栈 / 抽屉开关时重算 | 期间被打断 → 权限停在旧值 → 点不动 |
 * | 页面栈可见性 | `reconcile` | 退场动画被打断 → 幽灵层 |
 * | 桌宠手势窗口 | `tapResolve` / `longPressTick` 定时器 | 定时器在回来后才触发 → 幽灵点击 |
 *
 * 三者是同一类问题：**依赖事件的缓存没有在生命周期边界上重新对齐**。
 * 所以这里也不分批补丁，而是集中成一个「回前台对账」原语，逐项幂等地对齐。
 *
 * ## 幂等性约束
 *
 * 本类所有动作都必须是**重复调用零副作用**的：
 * `onResume` 在点通知、切分屏、开权限框关掉之后都会触发，一天可能几十次。
 * 因此这里只做「读真值 → 写回」，绝不播动画、绝不加计数、绝不发一次性事件。
 */
object ResumeSettle {

    private const val TAG = "DollhouseResume"

    /**
     * 回前台对账。
     *
     * @param activity 宿主（`MainActivity` 或 `ChatActivity`）
     */
    @JvmStatic
    fun settle(activity: Activity?) {
        if (activity == null) {
            return
        }
        refreshGate(activity)
        confirmTopPage(activity)
        clearStaleGestures(activity)
    }

    /**
     * ① 重算交互闸门。
     *
     * 【为什么这一步能修「回前台点不动」】`LayerGate.refresh` 读的是**当前真实状态**
     *   （`NavHost.depth` + `ProfileDrawer.opened`），算完直接写各层的 `blocking`。
     *   它是纯幂等的，重复调用只是把同样的值再写一遍。
     */
    private fun refreshGate(activity: Activity) {
        try {
            LayerGate.settleTouches(activity)
        } catch (ignored: Throwable) {
            Logs.w(TAG, "gate refresh failed", ignored)
        }
    }

    /**
     * ② 确认当前顶层页面，并让「页面登记的返回拦截器」与栈顶对齐。
     *
     * 【要解决的问题】`PageStack.backInterceptor` 是**单槽**的（谁最后 set 谁生效）。
     *   若某页在 `DisposableEffect` 里没来得及清（进程被挂起在切换途中），
     *   回来后拦截器可能指向一个已不在栈顶的页面 —— 后果是「按返回没反应，
     *   或者返回动作落在了看不见的页面上」。
     *
     * 【做法】不主动销毁任何拦截器（那是各页面自己的生命周期职责），
     *   只做一次**一致性核对**：若拦截器登记的那一层已不在栈里，就地注销。
     */
    private fun confirmTopPage(activity: Activity) {
        try {
            val interceptor = PageStack.backInterceptor ?: return
            // 【判据用「还在不在栈里」而不是「是不是栈顶」】栈顶不符时 `MainActivity.onBackPressed`
            //   本来就会跳过它（`PageStack.isTop` 校验），无害；只有「已经不在栈里」才必须清，
            //   否则它会在**下一次**压栈后巧合命中 isTop，把别人的返回吃掉。
            val keys = PageStack.keys(activity)
            if (!keys.contains(interceptor.key)) {
                // 登记的那一层已经不在了：注销，避免它继续吃返回键。
                PageStack.backInterceptor = null
                Logs.w(TAG, "stale back interceptor dropped: " + interceptor.key)
            }
        } catch (ignored: Throwable) {
            Logs.w(TAG, "top page confirm failed", ignored)
        }
    }

    /**
     * ③ 清掉桌宠侧过期的**手势窗口**。
     *
     * 【为什么危险】`PetWindow` 的点击是用两个延时任务结算的：
     *   按下时 `postDelayed(longPressTick)`，抬手时 `postDelayed(tapResolve, TAP_WINDOW_MS)`。
     *   用户按着人偶的一瞬间被电话 / 通知切走，定时器仍在队列里；等回到前台它照样触发，
     *   于是「你没点，它自己动了」。同理，长按到点在后台触发会直接弹出聊天。
     *
     * 【为什么走 Intent 而不是直接调】桌宠跑在 `PetService` 进程侧、持有窗口，
     *   界面侧拿不到窗口句柄。保持既有 IPC 风格（与 `ACTION_REFRESH` 同一条通道）。
     *   服务没在跑时 `startService` 会抛，这里吞掉即可（没人偶就没有幽灵手势）。
     */
    private fun clearStaleGestures(activity: Activity) {
        try {
            if (!PetToggle.isRunning(activity)) {
                return
            }
            val intent = Intent(activity, PetService::class.java)
            intent.action = PetService.ACTION_RESUME_SETTLE
            activity.startService(intent)
        } catch (ignored: Throwable) {
            Logs.w(TAG, "gesture settle dispatch skipped", ignored)
        }
    }
}