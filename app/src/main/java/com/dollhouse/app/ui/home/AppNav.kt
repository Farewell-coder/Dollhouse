package com.dollhouse.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dollhouse.app.data.PetPrefs

/**
 * 【职责】主界面三个 Tab（聊天 / 人偶 / 主页）的**当前选中项**唯一真源。
 *
 * 【为什么单独抽一个对象】
 *   改造前 MainActivity 的底座是「首页 ↹ 设置页」两页互斥显隐（见 [HomeUi.TAG_HOME] /
 *   [HomeUi.TAG_SET]），没有「Tab」概念。现在底座扩成**三页**，需要回答两个问题：
 *   ① 当前在哪一页 —— 得有个能被 Compose 观察的状态，才能让底栏高亮跟着走；
 *   ② 重建后回到哪一页 —— 得落盘，否则切到聊天再旋转屏幕会被弹回主页。
 *   两件事都由本对象承担，页面自己只读写 [current]。
 *
 * 【与 PageStack 的区别】PageStack 管的是「压在主界面之上的覆盖层」（人偶列表 / 设置子页 /
 *   供应商…），本对象管的是「主界面这一层自己换哪张脸」。两者正交：
 *   从任意 Tab 都能往 PageStack 压层，压完退回来还在原来的 Tab 上。
 *
 * 【为什么用 mutableStateOf 而不是普通字段】底栏是 Compose 写的，要跟着重组。
 *   写入方（底栏点击 / 启动恢复 / 通知跳转）都在主线程，无需额外同步。
 *
 * 【线程】只在主线程读写（与 View / Compose 状态同约束）。
 */
object AppNav {

    /** Tab 聊天：会话列表，**默认首页**。 */
    const val TAB_CHAT = 0

    /** Tab 人偶：人偶管理。 */
    const val TAB_CONTACT = 1

    /** Tab 主页：原首页（启动人偶 / 设置 / 关于）。 */
    const val TAB_HOME = 2

    /** 全部 Tab 的下标集合，供底栏遍历渲染（顺序即底栏从左到右的顺序）。 */
    @JvmField
    val ORDER = intArrayOf(TAB_CHAT, TAB_CONTACT, TAB_HOME)

    /**
     * 当前 Tab。
     *
     * 【默认值】[TAB_CHAT] —— 需求原文「将软件启动后的默认页面改为聊天列表」。
     *   首启（偏好里还没有任何记录）时 [restore] 会读到哨兵值，落到本默认值。
     */
    var current by mutableStateOf(TAB_CHAT)
        private set

    /** 是否已经做过一次启动恢复（幂等闸门，避免 onResume 反复读盘）。 */
    private var restored = false

    /**
     * 切到某个 Tab（越界值会被夹回合法范围，不抛异常）。
     *
     * 【为什么立即落盘】用户切完就杀进程 / 被系统回收时，下次进来应停在他离开的那一页。
     *   `apply()` 是异步写，但 SharedPreferences 在进程内立即可读，足够。
     */
    fun select(ctx: android.content.Context?, tab: Int) {
        val t = clamp(tab)
        if (t == current) {
            return
        }
        current = t
        if (ctx != null) {
            PetPrefs.setMainTab(ctx, t)
        }
    }

    /**
     * 冷启动 / 重建后恢复上次停留的 Tab。
     *
     * 【为什么带 [restored] 闸门】本方法会被 `MainActivity.onCreate` 与界面首帧两处调用；
     *   若每次都从偏好重读，用户在设置页里切到「主页」时会被读回的旧值顶回去。
     *   只在「本次进程首次」生效一次，之后一律以内存里的 [current] 为准。
     */
    fun restore(ctx: android.content.Context?) {
        if (restored || ctx == null) {
            return
        }
        restored = true
        current = clamp(PetPrefs.mainTab(ctx))
    }

    /** 越界夹取：写入端与读取端共用同一口径，避免脏偏好值把底栏高亮搞没。 */
    private fun clamp(tab: Int): Int {
        if (tab < TAB_CHAT || tab > TAB_HOME) {
            return TAB_CHAT
        }
        return tab
    }

    /**
     * 【重建路径专用】把内存状态重置为「未恢复」，让下一次 [restore] 重新读盘。
     *
     * 【用途】Activity 真正被销毁重建（配置变更）时，内存里的 [current] 会跟着
     *   新一轮对象初始化回到默认值，但 [restored] 闸门如果留在上一轮状态，
     *   就会「不读盘」而停在默认页。这里在宿主 `onCreate` 最前面显式解锁一次。
     */
    fun unlock() {
        restored = false
    }
}
