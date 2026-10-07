package com.dollhouse.app.keepalive

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 【职责】本工程唯一的 Application 子类，Hilt 的注入根。
 *
 * 【为什么必须新建】存量 Java 工程没有 Application 子类，而 @HiltAndroidApp 必须挂在
 *   Application 上；这是引入 Hilt 的硬性前提（清单 android:name=".keepalive.DollhouseApp"）。
 *
 * 【只做一件事】进程一起就把已开启的保活调度恢复起来：覆盖安装（MY_PACKAGE_REPLACED
 *   不会在本进程内派发）、被系统回收后再次冷启、以及 JobScheduler 因 app 数据变化被清空，
 *   三种情况都靠这一步兜住。[KeepAliveFacade.restore] 幂等，重复调用无副作用。
 *
 * 【不做】不在此申请任何权限、不启动服务、不跳转界面，避免拖慢冷启动。
 */
@HiltAndroidApp
class DollhouseApp : Application() {

    @Inject
    lateinit var keepAlive: KeepAliveFacade

    override fun onCreate() {
        super.onCreate()
        // 开关未开启时 restore 内部直接返回，零开销。
        keepAlive.restore()
    }
}