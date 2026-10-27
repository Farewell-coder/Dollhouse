package com.dollhouse.app.ui.compose

import android.app.Activity
import android.content.Context
import android.view.View
import com.dollhouse.app.ui.nav.NavHost
import com.dollhouse.app.ui.nav.NavKeyCodec
import androidx.navigation3.runtime.NavKey

/**
 * 【职责】页面栈的**对外兼容门面**。真源已迁至 Jetpack Navigation 3（见 [NavHost]）。
 *
 * 【为什么保留这个类而不删】
 *   全工程有 84 处按字符串 key 调用本类（`PageStack.push(act, "doll:edit:$id") { … }`）。
 *   用户明确要求「不做无关重构」，且一次性改 84 处会把导航迁移变成全工程大重构。
 *   因此本类保留**完全相同的对外签名**，内部整体转发给 [NavHost]（Nav3）。
 *   调用点零改动，而路由与栈的真源已经换成 Nav3。
 *
 * 【与改造前实现的关键差异】
 *   旧实现自己管层序、自己挂 View、自己播动画、自己处理穿透与幽灵层。
 *   现在这些全部由 Nav3 的 `NavDisplay` 接管：
 *   · 层序 → `SnapshotStateList<NavKey>`（唯一真源，不存在「记账 vs View 树」两份状态）
 *   · 动画 → NavDisplay 的内置转场（含预测性返回）
 *   · 穿透 → Nav3 只让栈顶参与命中测试，从根上不存在穿透
 *   · 幽灵层 → 只有一份真源，不可能错位；[reconcile] 退化为空操作
 *
 * 【保留的能力】
 *   · [onLeave]：页面离开时的清理登记（如收起预览悬浮窗）。
 *     旧实现挂在「摘页」路径上；新实现挂在 Nav3 entry 的 `DisposableEffect` 上
 *     （Nav3 的 entry 离开栈时组合**真的会销毁**，比旧实现更可靠）。
 *   · [sealLayer]：旧实现为防穿透给页根挂 `OnTouchListener{true}`。
 *     Nav3 下不再需要，但方法保留（空实现 + 说明），以免日后有人按旧注释找过来时困惑。
 */
object PageStack {

    /* ============================ 离开清理登记表 ============================ */

    /**
     * key → 该页被摘掉前要做的事。
     *
     * 【为什么还需要它】有些资源**不在页面视图树里**（最典型的是系统级悬浮窗：
     *   人偶编辑页的「预览」是一个独立的 `TYPE_APPLICATION_OVERLAY` 窗口）。
     *   页面被摘掉时它们不会跟着消失，会一直留在屏幕上。
     *   [NavHost] 的 `DisposableEffect` 会回调 [fireLeaveFor] 来触发这些清理。
     */
    private val ON_LEAVE = HashMap<String, () -> Unit>()

    /**
     * 登记某页的离开清理。key 必须与 [push] 的 key 一致。
     * 幂等：同 key 重复登记以最后一次为准。
     */
    @JvmStatic
    fun onLeave(key: String?, action: (() -> Unit)?) {
        if (key == null || key.isEmpty() || action == null) {
            return
        }
        ON_LEAVE[key] = action
    }

    /**
     * 执行并移除某页的离开清理。
     *
     * 【供谁调用】[NavHost] 的 entry `onDispose`（字符串 key 版本），
     *   以及本类的 [pop] 路径（兜底，防止 Nav3 因异常未触发 dispose）。
     */
    @JvmStatic
    fun fireLeaveFor(key: String?) {
        if (key == null) {
            return
        }
        val a = ON_LEAVE.remove(key) ?: return
        try {
            a.invoke()
        } catch (ignored: Throwable) {
            // 清理失败不能影响摘页本身。
        }
    }

    /** [NavKey] 版本（[NavHost] 内部使用）。 */
    @JvmStatic
    fun fireLeaveFor(key: NavKey?) {
        fireLeaveFor(NavKeyCodec.toLegacy(key))
    }

    /* ============================ 查询（转发 NavHost） ============================ */

    /** 当前层数（不含底座）。 */
    @JvmStatic
    fun depth(ctx: Context?): Int = NavHost.depth(ctx)

    @JvmStatic
    fun isEmpty(ctx: Context?): Boolean = NavHost.isEmpty(ctx)

    /** 栈顶 key；空栈或无宿主返回 null。 */
    @JvmStatic
    fun topKey(ctx: Context?): String? = NavHost.topKey(ctx)

    @JvmStatic
    fun isTop(ctx: Context?, key: String?): Boolean {
        return key != null && key == topKey(ctx)
    }

    /** 该宿主栈内所有 key，自底向上（不含底座）。 */
    @JvmStatic
    fun keys(ctx: Context?): ArrayList<String> = NavHost.keys(ctx)

    /**
     * 按 key 取层内 View。
     *
     * 【为什么返回 null】Nav3 的页面由 `NavDisplay` 按需组合，不再有「预先挂在树上的整页 View」
     *   这个概念 —— 页面只在成为栈顶时被创建，且由 Nav3 管理其 View 实例。
     *   旧调用点用本方法做「找同 tag 的旧页并移除」，那套模型（同一 tag 只能有一层）
     *   在 Nav3 下已经不成立：同 key 入栈即「回到该层」，由 [NavHost.push] 处理。
     */
    @JvmStatic
    fun view(ctx: Context?, key: String?): View? = null

    @JvmStatic
    fun contains(ctx: Context?, key: String?): Boolean = NavHost.contains(ctx, key)

    /* ============================ 入栈 / 出栈 ============================ */

    /**
     * 压入一层。
     *
     * 【签名为何不变】调用方传的是「字符串 key + 构造 View 的 lambda」。
     *   新实现只用到 key（构造由 [NavHost] 通过 [PageRegistry] 完成）——
     *   页面构造逻辑本来就已经集中在 PageRegistry 里，lambda 只是历史遗留的第二条路径。
     *   【为什么不删 lambda 参数】84 处调用点都在传它；删参数 = 改 84 处 = 无关重构。
     *   保留参数但忽略其值，是最小改动。
     */
    @JvmStatic
    fun push(ctx: Context?, key: String?, build: (() -> View?)?): Boolean {
        return NavHost.push(ctx, key, build)
    }

    /**
     * 替换栈顶内容（切分段 / 翻页 / 保存后重建）。
     *
     * 【Nav3 下的语义】**栈顶 key 不变，只换该层的内容**（精确复刻旧实现）。
     *   实现分两步：
     *   ① 把新的构造 lambda 登记到当前栈顶 key 上（覆盖旧闭包，参数得以更新）；
     *   ② 让 [NavHost] 把该层的内容版本 +1，`NavDisplay` 据此重建该 entry。
     *
     * 【为什么必须登记 lambda】调用方（如 `ProviderNav`）是在内部路由变化**之后**才调本方法，
     *   新 lambda 捕获的是新路由；不登记就会拿旧闭包重建出旧页面。
     */
    @JvmStatic
    fun replaceTop(ctx: Context?, build: (() -> View?)?): Boolean {
        return NavHost.reviseTop(ctx, build)
    }

    /** 替换栈顶为指定 key（改变 key，不改变深度）。 */
    @JvmStatic
    fun replaceTop(ctx: Context?, key: String?): Boolean {
        return NavHost.replaceTop(ctx, key)
    }

    /**
     * 替换栈顶内容，使用与普通页面一致的淡入淡出。
     *
     * 【Nav3 下】转场由 `NavDisplay` 统一提供（栈顶内容变化自带交叉淡入），
     *   与 [replaceTop] 走同一条路径。旧实现区分 slide / fade 是为了营造「进新页」的观感，
     *   而这层观感现在由 Nav3 的转场统一负责，不再需要调用方指定。
     */
    @JvmStatic
    fun replaceTopSlide(ctx: Context?, build: (() -> View?)?): Boolean {
        return NavHost.reviseTop(ctx, build)
    }

    /** 替换栈顶为指定 key，带「前进」语义（新增重载）。 */
    @JvmStatic
    fun replaceTopSlide(ctx: Context?, key: String?): Boolean {
        return NavHost.replaceTop(ctx, key)
    }

    /**
     * 弹出栈顶一层。
     *
     * 【与旧实现的差异】旧实现先播 300ms 退场动画、动画结束才回调 [onDone]。
     *   新实现由 NavDisplay 播转场；列表立刻变更，[onDone] 同步回调。
     *   【为什么这是对的】调用方用 onDone 做的都是「状态收尾」（如刷新列表），
     *   越早执行越不容易出现「动画播完了但数据还没刷新」的中间态。
     */
    @JvmStatic
    fun pop(ctx: Context?, onDone: (() -> Unit)?): Boolean {
        val top = NavHost.topKey(ctx)
        val ok = NavHost.pop(ctx, onDone)
        if (ok) {
            // 【兜底清理】Nav3 的 dispose 是权威路径；这里补一次保证幂等不漏。
            fireLeaveFor(top)
        }
        return ok
    }

    @JvmStatic
    fun pop(ctx: Context?): Boolean = pop(ctx, null)

    /** 栈顶就是该 key 时才弹。 */
    @JvmStatic
    fun popIfTop(ctx: Context?, key: String?): Boolean {
        if (!isTop(ctx, key)) {
            return false
        }
        return pop(ctx, null)
    }

    /**
     * 【r21】返回到指定页面（需求文档要求的第五个原语）。
     *
     * 【语义】把 `key` 之上的所有层弹掉，`key` 本身留在栈顶。
     *   · `key` 不在栈里（或只是栈底的底座）→ 不动栈，返回 false；
     *   · `key` 已在栈顶 → 相当于什么也没做，返回 true（幂等）。
     *
     * 【为什么需要它】改造前只有 push / pop / replaceTop，「回到某一层」只能靠
     *   连弹多次，调用方得自己算次数 —— 一旦中间某层被别处弹掉，次数就对不上，
     *   于是要么弹过头、要么停在错的地方。现在由栈自己算。
     */
    @JvmStatic
    fun popTo(ctx: Context?, key: String?): Boolean {
        return NavHost.popTo(ctx, key)
    }

    /** 统一返回键入口：有层就退一层。 */
    @JvmStatic
    fun handleBack(ctx: Context): Boolean {
        if (isEmpty(ctx)) {
            return false
        }
        return pop(ctx, null)
    }

    /* ============================ 生命周期 ============================ */

    /** 清空某宿主的栈（Activity 重建 / 销毁时调用）。 */
    @JvmStatic
    fun reset(ctx: Context?) {
        NavHost.reset(ctx)
    }

    /**
     * 记账与视图树对账。
     *
     * 【为什么变成空操作】旧实现有两份状态（[PageStack] 的记账 + `android.R.id.content`
     *   的子 View 列表），两者会因「singleTask 唤回不重建 View 树」「退场动画被切后台打断」
     *   「厂商 ROM 清后台不销毁进程」等路径而错位，于是需要本方法对账。
     *   Nav3 下**只有一份真源**（`SnapshotStateList<NavKey>`），不存在错位的可能 ——
     *   这个方法失去存在理由。保留签名是为了不动 5 处既有调用点（它们本身是幂等的）。
     */
    @JvmStatic
    fun reconcile(ctx: Context?) {
        // 空实现：见上方说明。
    }

    /**
     * 把一层页「封」成触摸不透的。
     *
     * 【为什么不再是必需的】旧实现的层与层是 `android.R.id.content` 的**兄弟 View**，
     *   靠「新页盖住旧页」形成层级；Android 分发触摸时会按子 View 倒序逐个询问，
     *   没人消费就继续往下问 —— 于是空白区域的触摸会穿透到底下的旧页（用户报过的真 bug）。
     *   修法是给每层挂 `setOnTouchListener{true}` 粗暴兜底。
     *
     * Nav3 的 `NavDisplay` 只把**栈顶**那个 entry 放进组合树，非栈顶项根本不参与命中测试，
     *   穿透从根上不可能发生。故本方法不再被调用，保留仅为历史注释的可检索性。
     */
    @Deprecated("Nav3 已从根上消除点击穿透，无需再封层", level = DeprecationLevel.WARNING)
    @JvmStatic
    fun sealLayer(v: View) {
        // 空实现：见上方说明。
    }

    /** 从任意 Context 向上找到宿主 Activity。 */
    @JvmStatic
    fun activityOf(ctx: Context?): Activity? {
        var c: Context? = ctx
        var i = 0
        while (i < 8 && c != null) {
            if (c is Activity) {
                return c
            }
            if (c is android.content.ContextWrapper) {
                c = c.baseContext
            } else {
                return null
            }
            i++
        }
        return null
    }
}