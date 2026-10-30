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
                // 【工作台】资源导入向导页。必须与 NavKeyCodec 那条成对出现 ——
                //   少这一条的症状是「进程被回收后该层被静默跳过」。
                || key.startsWith(com.dollhouse.app.ui.doll.DollImportWizardPage.PREFIX)
                // 【v0.1.5·R-040】添加角色导入助手。必须与 NavKeyCodec 那条成对出现 ——
                //   少这一条的症状是「进程被回收后该层被静默跳过」。
                || key == com.dollhouse.app.ui.doll.ImportAssistantPage.PREFIX
                // 【v0.1.5·19.2.3】更换模型页。同样必须与 NavKeyCodec 那条成对出现。
                || key.startsWith(com.dollhouse.app.ui.doll.ReplaceModelPage.PREFIX)
                // 【v0.1.5·8.9】模型管理页。同样必须与 NavKeyCodec 那条成对出现。
                || key.startsWith(com.dollhouse.app.ui.doll.ModelManagePage.PREFIX)
                || key.startsWith(com.dollhouse.app.ui.chat.FriendProfilePage.PREFIX)
                || key.startsWith(com.dollhouse.app.ui.chat.FriendModelConfigPage.PREFIX)
                || key.startsWith(com.dollhouse.app.ui.chat.ConversationHistoryPage.PREFIX)
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
                // 【r15·补登记】识图模型 / 开发者选项两页原先漏登记 ——
                //   后果是「系统重建后这两页回不来」（栈里有 key、表里不认得，该层被跳过）。
                || com.dollhouse.app.ui.settings.VisionModelPage.TAG_PAGE == key
                || com.dollhouse.app.ui.settings.DeveloperOptionsPage.TAG_PAGE == key
                || com.dollhouse.app.ui.home.SettingsScreen.TAG_PAGE == key
                || ProviderNav.TAG_PAGE == key
                // 【r19·实验卡片下级】随机走位 / 睡觉模式设置页
                || com.dollhouse.app.ui.settings.WanderSettingsPage.TAG_PAGE == key
                || com.dollhouse.app.ui.settings.SleepSettingsPage.TAG_PAGE == key
                // 【本轮·补登记】三个「自己 open、自己入栈」的页面此前漏登记。
                //   后果与 r15 那次两个页面完全同型：栈里有 key、表里不认得 →
                //   系统回收进程后恢复时该层被跳过，用户看到「页面回来了但内容空白」
                //   或者干脆少了一层，返回键行为也跟着错位。
                //   【判据】凡是被 `PageStack.push(...)` 推进栈里的页面，都必须在这里登记。
                || com.dollhouse.app.ui.settings.MyProfilePage.KEY == key
                || com.dollhouse.app.ui.settings.AppearancePage.KEY == key
                || com.dollhouse.app.ui.doll.DollGroupPage.KEY == key
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
        // 【工作台】资源导入向导页。重建时按同一前缀切出 dollId 还原。
        if (key.startsWith(com.dollhouse.app.ui.doll.DollImportWizardPage.PREFIX)) {
            return com.dollhouse.app.ui.doll.DollImportWizardPage.buildPage(
                act, key.substring(com.dollhouse.app.ui.doll.DollImportWizardPage.PREFIX.length)
            )
        }
        // 【v0.1.5·R-040】添加角色导入助手（无参数，key 全等）。
        if (key == com.dollhouse.app.ui.doll.ImportAssistantPage.PREFIX) {
            return com.dollhouse.app.ui.doll.ImportAssistantPage.buildPage(act)
        }
        // 【v0.1.5·19.2.3】更换模型页（按前缀切出 dollId）。
        if (key.startsWith(com.dollhouse.app.ui.doll.ReplaceModelPage.PREFIX)) {
            return com.dollhouse.app.ui.doll.ReplaceModelPage.buildPage(
                act, key.substring(com.dollhouse.app.ui.doll.ReplaceModelPage.PREFIX.length)
            )
        }
        // 【v0.1.5·8.9】模型管理页（按前缀切出 dollId）。
        if (key.startsWith(com.dollhouse.app.ui.doll.ModelManagePage.PREFIX)) {
            return com.dollhouse.app.ui.doll.ModelManagePage.buildPage(
                act, key.substring(com.dollhouse.app.ui.doll.ModelManagePage.PREFIX.length)
            )
        }
        // 【r13】动作详情页（播放器 + 帧序列编辑）。key = doll:anim:<dollId>:<actionId>。
        //   【顺序】必须排在 DollActionPage 之后：两者前缀互不为前缀，但显式排在一起更清楚。
        if (key.startsWith(com.dollhouse.app.ui.chat.FriendProfilePage.PREFIX)) {
            return com.dollhouse.app.ui.chat.FriendProfilePage.buildPage(act, key.substring(com.dollhouse.app.ui.chat.FriendProfilePage.PREFIX.length))
        }
        if (key.startsWith(com.dollhouse.app.ui.chat.FriendModelConfigPage.PREFIX)) {
            return com.dollhouse.app.ui.chat.FriendModelConfigPage.buildPage(act, key.substring(com.dollhouse.app.ui.chat.FriendModelConfigPage.PREFIX.length))
        }
        if (key.startsWith(com.dollhouse.app.ui.chat.ConversationHistoryPage.PREFIX)) {
            return com.dollhouse.app.ui.chat.ConversationHistoryPage.buildPage(act, key.substring(com.dollhouse.app.ui.chat.ConversationHistoryPage.PREFIX.length))
        }
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
            // 【r17·数据不丢】记忆库页的 dollId 不在 key 里，而在 `MemPage.open` 的闭包中
            //   （`push(ctx, TAG_PAGE) { buildPage(ctx, dollId) }`）。
            //   入栈路径由 NavHost 的「构造器登记表」兜住；进程被杀后恢复时闭包已不存在，
            //   这里回落到 `buildPage(ctx)` —— 它内部读 `MemPage.currentDollId`
            //   （每次 `open(ctx, dollId)` 都会更新它），语义与用户离开时看到的那只人偶一致。
            return MemPage.buildPage(act)
        }
        // 【r13·新记忆系统】「记忆库设置」页（设置 → 实验 → 记忆库设置）。
        if (com.dollhouse.app.ui.settings.MemSettingsPage.TAG_PAGE == key) {
            return com.dollhouse.app.ui.settings.MemSettingsPage.buildPage(act)
        }
        if (com.dollhouse.app.ui.settings.ChatSettingsPage.TAG_PAGE == key) {
            return com.dollhouse.app.ui.settings.ChatSettingsPage.buildPage(act)
        }
        // 【r15·补登记】识图模型页 / 开发者选项页（设置 → 实验 → 对应入口）。
        if (com.dollhouse.app.ui.settings.VisionModelPage.TAG_PAGE == key) {
            return com.dollhouse.app.ui.settings.VisionModelPage.buildPage(act)
        }
        if (com.dollhouse.app.ui.settings.DeveloperOptionsPage.TAG_PAGE == key) {
            return com.dollhouse.app.ui.settings.DeveloperOptionsPage.buildPage(act)
        }
        // 【r13】设置页从「底座」改成「覆盖层」后，要能在重建时恢复（见 SettingsScreen.open）。
        if (com.dollhouse.app.ui.home.SettingsScreen.TAG_PAGE == key) {
            return com.dollhouse.app.ui.home.SettingsScreen.build(act)
        }
        if (ProviderNav.TAG_PAGE == key) {
            return ProviderNav.buildPage(act)
        }
        // 【r19·实验卡片下级】随机走位 / 睡觉模式设置页（含真实开关与说明文案）。
        if (com.dollhouse.app.ui.settings.WanderSettingsPage.TAG_PAGE == key) {
            return com.dollhouse.app.ui.settings.WanderSettingsPage.build(act)
        }
        if (com.dollhouse.app.ui.settings.SleepSettingsPage.TAG_PAGE == key) {
            return com.dollhouse.app.ui.settings.SleepSettingsPage.build(act)
        }
        // 【本轮·补登记】个人资料 / 外观 / 人偶分组三页（语义同 isPageKey 处的注释）。
        if (com.dollhouse.app.ui.settings.MyProfilePage.KEY == key) {
            return com.dollhouse.app.ui.settings.MyProfilePage.build(act)
        }
        if (com.dollhouse.app.ui.settings.AppearancePage.KEY == key) {
            return com.dollhouse.app.ui.settings.AppearancePage.build(act)
        }
        if (com.dollhouse.app.ui.doll.DollGroupPage.KEY == key) {
            // 【注意】`DollGroupPage.open` 登记过 onLeave 回调（用于通知列表重算分组），
            //   但那个闭包在进程被杀时已经没了 —— 恢复出来的这一层**没有** onLeave。
            //   影响面有限：分组页自身每次进入都会重读 SharedPreferences，所以最坏是
            //   「恢复后返回列表，列表里的分组归属要多切一次页才刷新」。
            //   这里不为它虚构空回调（那只会掩盖真实刷新需求），仅记录该差异。
            return com.dollhouse.app.ui.doll.DollGroupPage.build(act)
        }
        return null
    }
}
