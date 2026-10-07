package com.dollhouse.app.keepalive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 【职责】监听默认网络变化：网络一恢复就立刻补跑一轮保活。
 *
 * 【入口】[KeepAliveFacade.setEnabled] 开启时注册、关闭时注销。
 *
 * 【生命周期】注册/注销严格对称：本类持有一个回调字段，register 前先 unregisterOnce，
 *   保证无论调用多少次都不会遗留未注销的回调。
 *
 * 【坑】registerDefaultNetworkCallback 不需要 ACCESS_NETWORK_STATE 之外的权限（已声明），
 *   但 API 24 才可用；minSdk 24 恰好覆盖，无需版本分支。
 *
 * 【坑】回调里不得做耗时工作；这里只转交给 [KeepAliveRunner]，因为它在自己的作用域里异步跑。
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: KeepAliveRunner
) {

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 仅用于自检：记录「应当处于已注册状态」。 */
    private val registeredFlag = AtomicBoolean(false)

    private val cm: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 注册默认网络回调。重复调用不会重复注册。 */
    fun register() {
        if (callback != null) {
            return
        }
        try {
            val manager = cm ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    KeepAliveLog.d("network available")
                    // 网络恢复是「进程可能刚被拉起但服务没起」的高发时刻，立即补跑一轮。
                    runner.runOnce("network")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // 此处不动作：能力变化频率高，避免造成探测风暴。
                }

                override fun onLost(network: Network) {
                    // 网络断开不影响本应用（桌宠离线也能跑），无需处理。
                }
            }
            manager.registerDefaultNetworkCallback(cb)
            callback = cb
            registeredFlag.set(true)
            KeepAliveLeakDetector.assertCallbackSymmetry(true, true)
            KeepAliveLog.d("network callback registered")
        } catch (t: Throwable) {
            KeepAliveLog.w("register network callback failed", t)
        }
    }

    /** 注销默认网络回调。未注册时不做任何事。 */
    fun unregister() {
        val cb = callback ?: return
        callback = null
        registeredFlag.set(false)
        try {
            cm?.unregisterNetworkCallback(cb)
            KeepAliveLeakDetector.assertCallbackSymmetry(false, false)
            KeepAliveLog.d("network callback unregistered")
        } catch (t: Throwable) {
            KeepAliveLog.w("unregister network callback failed", t)
        }
    }
}
