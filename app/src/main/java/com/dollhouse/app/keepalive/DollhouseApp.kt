package com.dollhouse.app.keepalive

import com.dollhouse.app.core.CrashGuard
import com.dollhouse.app.core.Logs
import android.app.Application
import com.dollhouse.app.ui.compose.ComposeHost
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
        // 【r15·日志开关】尽早装上下文，让 Logs 能读运行期开关。
        //   放在最前：后面的初始化若有异常，日志已经可用了。
        Logs.install(this)
        // 【r15·崩溃兜底】装未捕获异常处理器：崩溃时落一份现场到 cacheDir/crash/。
        //   【为什么必须最早装】越早才能逮到越多；装在 super.onCreate 之后、其余初始化之前。
        CrashGuard.install(this)
        // **【v0.1.5·R-001 单常驻】档案迁移：把旧版允许的「多个 enabled=true」收敛成一个。**
        //   【为什么放在这里】这是进程启动的最早可用点（Logs / CrashGuard 之后），
        //   任何读取 `DollStore.enabled()` 的地方（PetService、首页状态、磁贴）都在它之后，
        //   于是它们看到的档案**必然已经是**单常驻形态，不需要各自兼容多 enabled。
        //   【为什么不是放在 PetService】PetService 可能不被启动（用户关了桌宠），
        //   那样列表页的「常驻」标记就会与迁移结果不一致。放在 Application 里一次到位。
        //   【幂等】内部按 schema_version 只跑一次。
        com.dollhouse.app.data.DollStore.migrateToSingleEnabled(this)
        // **【v0.1.5·21.1.3】清理上次遗留的导入会话临时目录。**
        //   【为什么要这一步】进程被杀 / 用户强退时 `ImportSession.discard()` 不会执行，
        //   一整套帧（几十 MB）会一直躺在 `filesDir/import/` 下，纯漏空间。
        //   【为什么只清「超过一天没动过」的】多窗口时可能有会话正在用；
        //   保守一点，误删的代价（用户得重选文件）远高于多留一天的几十 MB。
        //   【为什么在这里而不是启动后延时】它只做目录 stat + 少数几次递归删除，
        //   空目录下是常数开销，不会拖慢冷启动（N-007 要求 2 秒内可操作）。
        com.dollhouse.app.data.ImportSession.sweepStale(this)
        // 【Compose 生命周期桥】本工程 Activity 全部继承原生 `android.app.Activity`，
        //   不会自动装 `ViewTreeLifecycleOwner`；而 Compose 的帧时钟只在 Lifecycle
        //   走到 ON_START 时才恢复。这里挂一次进程级回调，把真实的前后台变化转发给
        //   ComposeHost 装的 owner —— 否则页面会停在 CREATED 白屏（已用 javap 核实
        //   WindowRecomposer 的 onStateChanged 分支）。
        ComposeHost.install(this)
        // 开关未开启时 restore 内部直接返回，零开销。
        keepAlive.restore()
    }
}