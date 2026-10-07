package com.dollhouse.app.pet

/**
 * 【职责】进程内的单例事件总线：把桌宠的状态变化广播给聊天面板。
 *
 * 【交互】PetService / PetView 发事件，ChatPanel 订阅。
 *
 * 【坑】listener 是单个静态引用（同一时刻只允许一个订阅者），注册新监听会顶掉旧的。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
object PetBus {
    @Volatile
    private var listener: Listener? = null

    interface Listener {
        fun onAffection(i: Int)

        fun onSay(str: String, j: Long)
    }

    @JvmStatic
    fun register(listener2: Listener?) {
        listener = listener2
    }

    @JvmStatic
    fun unregister(listener2: Listener?) {
        if (listener === listener2) {
            listener = null
        }
    }

    @JvmStatic
    fun say(str: String?, j: Long) {
        val listener2 = listener
        if (listener2 == null || str == null || str.isEmpty()) {
            return
        }
        listener2.onSay(str, j)
    }

    @JvmStatic
    fun affection(i: Int) {
        val listener2 = listener
        if (listener2 != null) {
            listener2.onAffection(i)
        }
    }
}
