package com.dollhouse.app.ui.compose

import android.app.Activity
import java.util.WeakHashMap

/**
 * 【职责】交互所有权的**唯一真源**——决定「此刻哪一层可以接收触摸」。
 *
 * ## 为什么必须集中到一个地方
 *
 * 重构前，四层各自判断自己能不能收事件：
 * · 底座 / 底栏：挂载时写死 `isClickable = false`
 * · 抽屉：`ProfileDrawer` 里一个手动镜像的布尔量（`bindHost` / `applyTouchable`）
 * · 覆盖页栈：`NavHost` 里判 `stack.size > 1`
 *
 * 三套判定互不相干、互不知情。于是「抽屉开着 + 覆盖页叠上来 + 转场进行中」
 * 这类组合**没有任何地方定义过**，谁收事件全看谁的最后一次写入更晚 ——
 * 这正是「点这里却触发了那里」的根源，也是为什么逐个打补丁必然修一个漏一个。
 *
 * ## 单一判定
 *
 * ```
 * 覆盖页栈深 > 0      → NAV     （栈顶那层）
 * 否则 抽屉打开        → DRAWER  （抽屉）
 * 否则                → BASE    （三 Tab + 底栏）
 * ```
 *
 * 「抽屉是一个界面，不是弹层」—— 所以它和覆盖页是**同级**关系，只是出现方式不同
 * （从左边滑出 vs 从右边推进）。覆盖页盖住抽屉时抽屉保留且**状态不丢**，
 * 返回即原地恢复；抽屉自己再关掉才落回主界面。
 *
 * ## 副作用面
 *
 * 只做一件事：把各层的 [LayerShield.blocking] 按当前态设一遍。
 * 不碰页面内容、不碰动画、不碰滚动与输入。
 *
 * ## 时效性
 *
 * 状态由「快照状态 + 显式通知」共同驱动：导航栈或抽屉状态一变就重算，
 * 因此**新页入栈的同一帧**旧层即被屏蔽，不等进场动画结束
 * （对应需求文档「不得出现新页面尚未建立完整交互状态时，下层页面仍接收意外点击」）。
 */
object LayerGate {

    /** 三个交互态。 */
    enum class Owner {
        /** 底座：三 Tab + 底栏。 */
        BASE,

        /** 侧边「我的资料」抽屉。 */
        DRAWER,

        /** 覆盖页栈的栈顶页。 */
        NAV
    }

    /**
     * 【纯函数】由两个输入算出当前交互层。
     *
     * 【为什么单独抽出来】这是整个框架唯一的判断逻辑，必须能被单元测试直接覆盖，
     *   而不是埋在 Android View 回调里只能靠真机试。
     *
     * @param navDepth 覆盖页栈深（**不含**栈底垫底的 HomeRoute）；0 = 无覆盖页
     * @param drawerOpened 抽屉是否打开
     */
    fun ownerOf(navDepth: Int, drawerOpened: Boolean): Owner {
        return when {
            // 覆盖页在最上：它必须独占（转场期旧页由 NavHost 侧逐 entry 屏蔽）。
            navDepth > 0 -> Owner.NAV
            // 抽屉次之：它是一个完整界面，盖住底座。
            drawerOpened -> Owner.DRAWER
            else -> Owner.BASE
        }
    }

    /** 某个 Activity 的各层闸门。全部可空：宿主可能只装了其中几层。 */
    class Layers {
        /** 底座（三 Tab 的容器）。 */
        var base: LayerShield? = null

        /** 底栏。 */
        var bar: LayerShield? = null

        /** 抽屉。 */
        var drawer: LayerShield? = null

        /** 覆盖页栈。 */
        var nav: LayerShield? = null
    }

    private val LAYERS = WeakHashMap<Activity, Layers>()

    /** 取（必要时创建）某宿主的层登记表。 */
    @JvmStatic
    fun layersOf(act: Activity): Layers {
        var l = LAYERS[act]
        if (l == null) {
            l = Layers()
            LAYERS[act] = l
        }
        return l
    }

    /**
     * 重新计算并应用交互权限。
     *
     * 【谁调用】三个时机，全部收敛到这里：
     * ① 抽屉开 / 关 / 切换；
     * ② 覆盖页入栈 / 出栈 / 原地重建（由 NavHost 调）；
     * ③ 某宿主刚装配完 / 状态归位（HomeUi.apply、onResume）。
     *
     * 【为什么可以重复调】纯幂等：同样的输入得到同样的输出，重复调用只有一次实际赋值。
     */
    @JvmStatic
    fun refresh(act: Activity?) {
        if (act == null) {
            return
        }
        val l = LAYERS[act] ?: return
        val owner = ownerOf(
            navDepth = com.dollhouse.app.ui.nav.NavHost.depth(act),
            drawerOpened = com.dollhouse.app.ui.home.ProfileDrawer.opened
        )
        apply(l, owner)
    }

    /** 把判定结果落到各层。`blocking = true` 表示「本层暂停接收触摸」。 */
    private fun apply(l: Layers, owner: Owner) {
        // 底座与底栏同属 BASE 态：底座可交互时底栏也可交互，反之一起暂停。
        l.base?.blocking = owner != Owner.BASE
        l.bar?.blocking = owner != Owner.BASE
        l.drawer?.blocking = owner != Owner.DRAWER
        // 【覆盖页整层】只要存在覆盖页，NavHost 这层就必须能收事件；
        //   层内「哪一层页面」由 NavHost 逐 entry 屏蔽（见 RouteContent）。
        l.nav?.blocking = owner != Owner.NAV
    }

    /** 宿主销毁时清理登记。 */
    @JvmStatic
    fun forget(act: Activity?) {
        if (act == null) {
            return
        }
        LAYERS.remove(act)
    }
}