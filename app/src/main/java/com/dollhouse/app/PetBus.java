package com.dollhouse.app;

/**
 * 【职责】进程内的单例事件总线：把桌宠的状态变化广播给聊天面板。
 *
 * 【交互】PetService / PetView 发事件，ChatPanel 订阅。
 *
 * 【坑】listener 是单个静态引用（同一时刻只允许一个订阅者），注册新监听会顶掉旧的。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public final class PetBus {
    private static volatile PetBus.Listener listener;

    public interface Listener {
        void onAffection(int i);

        void onSay(String str, long j);
    }

    private PetBus() {
    }

    public static void register(PetBus.Listener listener2) {
        listener = listener2;
    }

    public static void unregister(PetBus.Listener listener2) {
        if (listener == listener2) {
            listener = null;
        }
    }


    public static void say(String str, long j) {
        PetBus.Listener listener2 = listener;
        if (listener2 == null || str == null || str.isEmpty()) {
            return;
        }
        listener2.onSay(str, j);
    }

    public static void affection(int i) {
        PetBus.Listener listener2 = listener;
        if (listener2 != null) {
            listener2.onAffection(i);
        }
    }
}
