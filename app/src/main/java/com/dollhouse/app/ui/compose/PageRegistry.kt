package com.dollhouse.app.ui.compose

import android.view.View
import com.dollhouse.app.MainActivity
import com.dollhouse.app.ai.TokenStat
import com.dollhouse.app.ui.doll.DollActionPage
import com.dollhouse.app.ui.doll.DollAnimPage
import com.dollhouse.app.ui.doll.DollAwayPage
import com.dollhouse.app.ui.doll.DollEditScreen
import com.dollhouse.app.ui.doll.DollGesturePage
import com.dollhouse.app.ui.doll.DollHomePage
import com.dollhouse.app.ui.doll.DollListScreen
import com.dollhouse.app.ui.doll.DollPersonaPage
import com.dollhouse.app.ui.doll.DollRangePage
import com.dollhouse.app.ui.doll.DollReturnPage
import com.dollhouse.app.ui.doll.DollTalkPage
import com.dollhouse.app.ui.provider.ProviderNav
import com.dollhouse.app.ui.settings.AboutPage
import com.dollhouse.app.ui.settings.MemPage

/**
 * 【职责】页面 key 与页面构造的唯一映射表。
 *
 * 【为什么需要】系统重建后 View 树已销毁，Bundle 里能留下的只有 key；
 *   重建时要「按 key 把每一层原样造回来」，就必须有一个地方认得所有 key。
 *
 * 【为什么独立于 PageStack】`PageStack` 只管层序与动画，不认识任何具体页面；
 *   页面各自暴露 `buildPage`，本表集中登记 —— 新增页面只加一条分支，栈本身零改动。
 *
 * 【唯一真源】[isPageKey] 与 [build] 必须共用同一份清单：新增页面时两处同时改。
 */
object PageRegistry {

    /**
     * 这个 key 是不是「本表认得的页面」。
     *
     * 【用途】[PageStack.reconcile] 要在一棵混着各种 View 的树上认幽灵页，
     *   判据必须是「这个 tag 是不是页面 key」。若改成「有 tag 就算」，会把
     *   `SheetPanel` 的遮罩、`ApiPageKit` 的行容器一并误摘 —— 那些是别的模块的正常产物。
     *
     * 【实现口径】刻意写成「前缀判断 + 全等判断」的显式列表，而不是反射遍历 [build] 的 if 链：
     *   显式列表在编译器眼里是可查的，漏改一处会立刻暴露成「新页不参与对账」，
     *   比反射静默失败容易发现。
     */
    @JvmStatic
    fun isPageKey(key: String?): Boolean {
        if (key == null || key.isEmpty()) {
            return false
        }
        return DollListScreen.KEY == key
                || key.startsWith(DollEditScreen.PREFIX)
                || key.startsWith(DollActionPage.PREFIX)
                || key.startsWith(DollAnimPage.PREFIX)
                || key.startsWith(DollGesturePage.PREFIX)
                || key.startsWith(DollAwayPage.PREFIX)
                || key.startsWith(DollHomePage.PREFIX)
                || key.startsWith(DollRangePage.PREFIX)
                || key.startsWith(DollReturnPage.PREFIX)
                || key.startsWith(DollTalkPage.PREFIX)
                || key.startsWith(DollPersonaPage.PREFIX)
                || TokenStat.TAG_PAGE == key
                || AboutPage.TAG_PAGE == key
                || MemPage.TAG_PAGE == key
                || com.dollhouse.app.ui.settings.MemSettingsPage.TAG_PAGE == key
                || com.dollhouse.app.ui.settings.ChatSettingsPage.TAG_PAGE == key
                || com.dollhouse.app.ui.home.SettingsScreen.TAG_PAGE == key
                || ProviderNav.TAG_PAGE == key
    }

    /**
     * 把任意页面 key 造成对应 View；认不出的 key 返回 null（该层会被跳过）。
     *
     * 【为什么前缀要集中登记】各页面 PREFIX 都在 `doll:` 命名空间下，分派顺序必须
     *   「长的在前、短的在后」，集中在 build 里按顺序 if-return，就不会出现
     *   「`doll:gesture:x` 被 `doll:edit:` 抢走」。当前各前缀互不为前缀关系，
     *   顺序不影响正确性，但保持从上到下逐条判更易读。
     */
    @JvmStatic
    fun build(act: MainActivity, key: String?): View? {
        if (key == null || key.isEmpty()) {
            return null
        }
        if (DollListScreen.KEY == key) {
            return DollListScreen.buildPage(act)
        }
        if (key.startsWith(DollEditScreen.PREFIX)) {
            return DollEditScreen.buildPage(act, key.substring(DollEditScreen.PREFIX.length))
        }
        // 【r10】编辑页的子页：key 里都带 dollId，重建时按同一前缀切出来还原。
        if (key.startsWith(DollActionPage.PREFIX)) {
            return DollActionPage.buildPage(act, key.substring(DollActionPage.PREFIX.length))
        }
        // 【r13】动作详情页（播放器 + 帧序列编辑）。key = doll:anim:<dollId>:<actionId>。
        //   【顺序】必须排在 DollActionPage 之后：两者前缀互不为前缀，但显式排在一起更清楚。
        if (key.startsWith(DollAnimPage.PREFIX)) {
            return DollAnimPage.buildPage(act, key.substring(DollAnimPage.PREFIX.length))
        }
        if (key.startsWith(DollGesturePage.PREFIX)) {
            return DollGesturePage.buildPage(act, key.substring(DollGesturePage.PREFIX.length))
        }
        if (key.startsWith(DollAwayPage.PREFIX)) {
            return DollAwayPage.buildPage(act, key.substring(DollAwayPage.PREFIX.length))
        }
        if (key.startsWith(DollHomePage.PREFIX)) {
            return DollHomePage.buildPage(act, key.substring(DollHomePage.PREFIX.length))
        }
        if (key.startsWith(DollRangePage.PREFIX)) {
            return DollRangePage.buildPage(act, key.substring(DollRangePage.PREFIX.length))
        }
        if (key.startsWith(DollReturnPage.PREFIX)) {
            return DollReturnPage.buildPage(act, key.substring(DollReturnPage.PREFIX.length))
        }
        if (key.startsWith(DollTalkPage.PREFIX)) {
            return DollTalkPage.buildPage(act, key.substring(DollTalkPage.PREFIX.length))
        }
        if (key.startsWith(DollPersonaPage.PREFIX)) {
            return DollPersonaPage.buildPage(act, key.substring(DollPersonaPage.PREFIX.length))
        }
        if (TokenStat.TAG_PAGE == key) {
            return TokenStat.buildPage(act)
        }
        if (AboutPage.TAG_PAGE == key) {
            return AboutPage.buildPage(act)
        }
        if (MemPage.TAG_PAGE == key) {
            return MemPage.buildPage(act)
        }
        // 【r13·新记忆系统】「记忆库设置」页（设置 → 实验 → 记忆库设置）。
        if (com.dollhouse.app.ui.settings.MemSettingsPage.TAG_PAGE == key) {
            return com.dollhouse.app.ui.settings.MemSettingsPage.buildPage(act)
        }
        if (com.dollhouse.app.ui.settings.ChatSettingsPage.TAG_PAGE == key) {
            return com.dollhouse.app.ui.settings.ChatSettingsPage.buildPage(act)
        }
        // 【r13】设置页从「底座」改成「覆盖层」后，要能在重建时恢复（见 SettingsScreen.open）。
        if (com.dollhouse.app.ui.home.SettingsScreen.TAG_PAGE == key) {
            return com.dollhouse.app.ui.home.SettingsScreen.build(act)
        }
        if (ProviderNav.TAG_PAGE == key) {
            return ProviderNav.buildPage(act)
        }
        return null
    }
}
