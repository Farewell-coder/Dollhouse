package com.dollhouse.app.ui.home

import android.view.View
import androidx.compose.runtime.Composable
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ui.compose.ComposeHost

/**
 * 【职责】主界面底栏第三格「说说」这一页的载体。
 *
 * 【r14 的重大变更】本对象此前是「首页」（`Dollhouse` 大标题 + 三个按钮：
 *   启动/关闭人偶、设置、关于）。用户定案：
 *   「我需要头像 签名 个性 这几个是用户本身的…然后下面放我的软件主页的那几个按钮
 *     然后原来主页在的界面就清空 不需要那个主页了 那个底边栏 变成说说
 *     然后那个界面暂时 不可进入 那个说说 标注一下未开放就好 不要太过于突兀」。
 *
 *   于是：
 *   - 三个按钮**搬进「我的资料」抽屉**（见 [ProfileDrawer]）；本页**一个不留**；
 *   - `Dollhouse` 大标题与 `A Home for Every Character` 副标题**整体丢弃**（用户明确说不要）；
 *   - 这一页当时只剩「暂未开放」占位。
 *
 * 【r25 变更】占位换成真内容：`说说` 现在是**人偶近况流**（见 [SocialPage]）——
 *   把每只人偶的长期记忆按时间倒序聚合上来。数据只读、不新增任何存储。
 *   原占位页 [ComingSoonPage] 保留（它仍是通用「暂未开放」骨架，只是本页不再用）。
 *
 * 【为什么不把这个类删掉、直接把第三格渲染成内容页】
 *   底栏的路由契约是「每个 Tab 一个可缓存的 View（带 tag）」——
 *   `HomeUi.apply` 建它、`showTab` 靠 `findViewWithTag(TAG_HOME)` 显隐它。
 *   直接删掉会让 `TAG_HOME` 找不到视图、切到第三格时整片空白。
 *   保留本对象作为**这一格的视图载体**、只换内容，路由零改动。
 *
 * 【契约】对外只暴露 [build]；页面 tag 由调用方（[HomeUi.apply]）打上。
 */
object HomeScreen {

    /**
     * 搭出「说说」页根视图。
     *
     * 【为什么这个方法名与类名不改】`HomeUi.apply` 与 `MainActivity` 都在调它；
     *   改名要同步动调用点与 `TAG_HOME` 的语义，而这一格的路由位置（第三个 Tab）
     *   与视图角色（一个可缓存的 Tab 页）完全没有变化 —— 换的只是内容。
     */
    @JvmStatic
    fun build(act: MainActivity): View {
        ComposeHost.installForActivity(act)
        return ComposeHost.createView(act) { HomeContent(act) }
    }

    /** 【供 HomeUi 的单一 Compose 根复用】同 [ContactScreen.ContentForHost] 的理由。 */
    @Composable
    fun HomeContentForHost(act: MainActivity) {
        HomeContent(act)
    }

    @Composable
    private fun HomeContent(act: MainActivity) {
        // 【为什么传 act】说说页需要 Context 读记忆库 / 解码头像，也需要它做页面跳转
        //   （去聊天、去记忆库设置），所以 Activity 是真实依赖，不再是占位时期的空参数。
        SocialPage.Feed(act)
    }
}