package com.dollhouse.app.pet

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 【职责】进程内的单例事件总线：把桌宠的状态变化广播给所有订阅者。
 *
 * 【交互】PetService / PetWindow / PetView 发事件，ChatPanel（全屏聊天页）与各个人偶窗口订阅。
 *
 * 【多实例】listener 从「单个静态引用」升级为「并发安全的订阅者列表」：
 *   每个已启用人偶的 PetWindow 都注册自己的 listener，事件（台词）向所有窗口广播，
 *   不再出现「后注册的把先注册的顶掉」——这是多实例展示能同时联动的前提。
 *
 * 【兼容】register / unregister 的签名与既有调用方完全一致；register 幂等（同一实例重复注册只保留一份），
 *   unregister 只摘除该实例，不影响其它订阅者。
 *
 * 【线程】订阅者列表用 CopyOnWriteArrayList，注册 / 注销与广播可跨线程并发，避免遍历时被改。
 */
object PetBus {
    private val listeners = CopyOnWriteArrayList<Listener>()

    interface Listener {
        fun onSay(str: String, j: Long)
    }

    @JvmStatic
    fun register(listener2: Listener?) {
        if (listener2 == null) {
            return
        }
        // 幂等：同一实例重复注册只保留一份（等价于旧实现里 register 覆盖自身）。
        if (!listeners.contains(listener2)) {
            listeners.add(listener2)
        }
    }

    @JvmStatic
    fun unregister(listener2: Listener?) {
        if (listener2 == null) {
            return
        }
        listeners.remove(listener2)
    }

    @JvmStatic
    fun say(str: String?, j: Long) {
        if (str == null || str.isEmpty()) {
            return
        }
        for (listener2 in listeners) {
            // 单个订阅者抛异常不能中断对其余窗口的广播。
            try {
                listener2.onSay(str, j)
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 当前订阅人数（诊断 / 测试用）。 */
    @JvmStatic
    fun listenerCount(): Int {
        return listeners.size
    }
}
