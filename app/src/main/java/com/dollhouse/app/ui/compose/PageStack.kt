package com.dollhouse.app.ui.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import com.dollhouse.app.ui.theme.GlobalBackground
import com.dollhouse.app.ui.theme.UiKit

/**
 * 【职责】页面层级栈：任意层数、统一进出场、统一返回。
 *
 * 【为什么要有它】改造前「现在在第几层」没有单一真源，是三套机制各管一段：
 *   ① 首页 ↔ 设置页靠两个常驻页的 `visibility` 互切；
 *   ② Token / 记忆 / 关于三个独立覆盖页各挂一个 tag，靠 `findViewWithTag` 反推；
 *   ③ 供应商模块自己维护一条 `MutableList<String>` 路由栈，整条栈却只渲染成一个覆盖页。
 *   三者的层序互不隶属，于是「同时开两层时只记得住一层、恢复顺序由代码写死」——
 *   用户报的「重进时记录的层级与实际显示的层级对不上」正源于此。
 *   这里把三者收成一条栈：**一层 = 一个 View = 一个 key**，层序即栈序。
 *
 * 【与旧 UiKit.openPage 的区别】`openPage` 用 `findViewWithTag` 找同 tag 旧页并**立即移除**，
 *   模型是「同一 tag 只能有一层」，天然撑不起多层。本栈每层 key 唯一、旧层原样留在下方，
 *   退层时自然露出，这才是真正的层级。
 *
 * 【按宿主隔离】层序是「某个 Activity 的层序」，不是进程全局的层序。
 *   本工程有两个会挂页的宿主：`MainActivity`（首页 / 设置页底座 + 全部覆盖页）与
 *   `ChatActivity`（桌面图标 / 通知栏直接进聊天，再点「Token 统计」）。
 *   若共用一条栈，两边会互相弹错对象 —— 例如在 ChatActivity 里备份「记忆库」，
 *   会让 MainActivity 的顶层跟着被弹掉。故栈按宿主 Activity 分桶存放。
 *   分桶键用 `System.identityHashCode(activity)`：重建后是新实例、新桶，天然不会串。
 *
 * 【动画】进场：自右滑入 + 淡入（`D_PAGE` / `EASE_DECEL`）；退场：向右滑出 + 淡出
 *   （`D_LAYER` / `EASE_ACCEL`）。方向与「前进 / 后退」一致，不会出现同向位移的误导。
 *
 * 【线程】只在主线程读写（与 View 操作同约束）。
 */
object PageStack {

    /** 栈内一层。key 唯一，view 是挂在 `android.R.id.content` 上的整页。 */
    private class Layer(@JvmField val key: String, @JvmField val view: View)

    /**
     * 宿主分桶表：键 = Activity 实例身份，值 = 该宿主的层序（自底向上）。
     * 【容量】最多保留 [MAX_HOSTS] 个宿主；超出的按插入序淘汰最老的。
     *   正常情况只有 1~2 个宿主在用法，这只是防止极端情况下（异常退出没走到 onDestroy）
     *   累积 View 引用造成泄漏的兜底；宿主正常销毁时由 [release] 显式回收。
     */
    private val HOSTS = LinkedHashMap<Int, ArrayList<Layer>>()
    private const val MAX_HOSTS = 3

    /**
     * 【r12】「页面离开」清理回调表：key → 该页被摘掉前要做的事。
     *
     * 【为什么需要】有些资源**不在页面视图树里**（最典型的是系统级悬浮窗：
     *   人偶编辑页的「预览」是一个独立的 `TYPE_APPLICATION_OVERLAY` 窗口）。
     *   页面被 `removeView` 摘掉时它们不会跟着消失，会一直留在屏幕上 ——
     *   用户按返回键离开后，人偶还赖在桌面。
     *
     * 【为什么不做成 DisposableEffect】ComposeView 的组合策略是
     *   `DisposeOnViewTreeLifecycleDestroyed`，宿主 Activity 没销毁时组合不会销毁，
     *   那条路根本不触发（与被删的「蓝色水滴」同因，见 [clearFocusDeep]）。
     *   放在栈的摘页路径上，是唯一与实际摘页时机对齐的位置。
     */
    private val ON_LEAVE = HashMap<String, () -> Unit>()

    /**
     * 登记某页的离开清理。在 [push] 之前调用，key 必须与 [push] 的 key 一致。
     * 幂等：同 key 重复登记以最后一次为准。
     */
    @JvmStatic
    fun onLeave(key: String?, action: (() -> Unit)?) {
        if (key == null || key.isEmpty() || action == null) {
            return
        }
        ON_LEAVE[key] = action
    }

    /** 执行并移除某页的离开清理（摘页路径内部调用）。 */
    private fun fireLeave(key: String?) {
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

    /** 取宿主分桶，没有就建。**只有入栈路径**该调用它 —— 读操作不应改表。 */
    private fun bucketOf(act: Activity): ArrayList<Layer> {
        val id = System.identityHashCode(act)
        var s = HOSTS[id]
        if (s == null) {
            s = ArrayList()
            HOSTS[id] = s
            trim()
        }
        return s
    }

    /**
     * 反查**已存在**的宿主分桶：拿不到 Activity（悬浮窗场景）或该宿主从未入过栈，都返回 null。
     * 【为什么读操作不能顺手建桶】建桶会触发 [trim]，把别的宿主的层序挤掉 ——
     *   存档（`keys`）、判层（`contains`）这类只读调用不该有这种副作用。
     */
    private fun bucketOrNull(ctx: Context?): ArrayList<Layer>? {
        val act = findActivity(ctx) ?: return null
        return HOSTS[System.identityHashCode(act)]
    }

    private fun trim() {
        while (HOSTS.size > MAX_HOSTS) {
            val it = HOSTS.keys.iterator()
            if (!it.hasNext()) {
                return
            }
            it.next()
            it.remove()
        }
    }

    /* ============================ 查询 ============================ */

    /** 当前层数（不含首页 / 设置页这两页底座）。 */
    @JvmStatic
    fun depth(ctx: Context?): Int {
        val s = bucketOrNull(ctx)
        return if (s == null) 0 else s.size
    }

    @JvmStatic
    fun isEmpty(ctx: Context?): Boolean {
        return depth(ctx) == 0
    }

    /** 栈顶 key；空栈或无宿主返回 null。 */
    @JvmStatic
    fun topKey(ctx: Context?): String? {
        val s = bucketOrNull(ctx)
        if (s == null || s.isEmpty()) {
            return null
        }
        return s[s.size - 1].key
    }

    @JvmStatic
    fun isTop(ctx: Context?, key: String?): Boolean {
        return key != null && key == topKey(ctx)
    }

    /** 该宿主栈内所有 key，自底向上。 */
    @JvmStatic
    fun keys(ctx: Context?): ArrayList<String> {
        val out = ArrayList<String>()
        val s = bucketOrNull(ctx) ?: return out
        for (l in s) {
            out.add(l.key)
        }
        return out
    }

    /** 按 key 取层内 View。 */
    @JvmStatic
    fun view(ctx: Context?, key: String?): View? {
        val s = bucketOrNull(ctx) ?: return null
        val i = indexOf(s, key)
        return if (i < 0) null else s[i].view
    }

    @JvmStatic
    fun contains(ctx: Context?, key: String?): Boolean {
        val s = bucketOrNull(ctx) ?: return false
        return indexOf(s, key) >= 0
    }

    /* ============================ 入栈 ============================ */

    /**
     * 压入一层：新页自右滑入。旧层**原样保留在下方**（不淡出、不移除）——
     * 它会被新页的不透明底完全遮住，保留它是「退层能原样露出」的前提。
     *
     * 【同 key】已在栈里则视为「回到该层」：先移除它之上的所有层，再原地重建它，
     *   避免同一 key 出现两层（`findViewWithTag` 只能找到第一个，会埋暗雷）。
     *
     * @param build 延迟构造新页；返回 null 视为失败，不入栈。
     */
    @JvmStatic
    fun push(ctx: Context?, key: String?, build: (() -> View?)?): Boolean {
        if (ctx == null || key == null || key.isEmpty() || build == null) {
            return false
        }
        val act = findActivity(ctx) ?: return false
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val stack = bucketOf(act)
        val exist = indexOf(stack, key)
        if (exist >= 0) {
            // 回到已有层：把它上面的层先摘掉（无动画，它们本来就被遮住），再重建本层。
            removeAbove(stack, content, exist)
            val fresh = build.invoke() ?: return true
            swapInPlace(stack, content, exist, key, fresh, false)
            return true
        }
        val page = build.invoke() ?: return false
        page.tag = key
        page.translationX = pageW(content) * 0.14f
        page.alpha = 0f
        content.addView(page, ViewGroup.LayoutParams(-1, -1))
        // 覆盖页自铺全局背景：页内不透明底遮住下层同一张图，避免叠影。
        GlobalBackground.installPage(page)
        stack.add(Layer(key, page))
        page.animate().translationX(0f).alpha(1f)
            .setDuration(UiKit.D_PAGE.toLong()).setInterpolator(UiKit.EASE_DECEL).start()
        return true
    }

    /**
     * 替换栈顶内容（切分段 / 翻页 / 保存后重建）：只交叉淡入，不做位移 ——
     * 位移会让人误以为「又前进了一层」。
     */
    @JvmStatic
    fun replaceTop(ctx: Context?, build: (() -> View?)?): Boolean {
        return swapTop(ctx, false, build)
    }

    /**
     * 替换栈顶内容，但按「前进」的方式自右滑入。
     * 【用途】供应商模块这类「一层壳 + 内部多级子路由」的页面：层深不变，但用户观感是进了新页。
     * 【为什么不新压一层】那会让每个子路由都变成独立层，返回语义与业务路由栈不再一致。
     */
    @JvmStatic
    fun replaceTopSlide(ctx: Context?, build: (() -> View?)?): Boolean {
        return swapTop(ctx, true, build)
    }

    private fun swapTop(ctx: Context?, slide: Boolean, build: (() -> View?)?): Boolean {
        if (ctx == null || build == null) {
            return false
        }
        val act = findActivity(ctx) ?: return false
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val stack = bucketOrNull(ctx) ?: return false
        if (stack.isEmpty()) {
            return false
        }
        val idx = stack.size - 1
        val fresh = build.invoke() ?: return false
        swapInPlace(stack, content, idx, stack[idx].key, fresh, slide)
        return true
    }

    /* ============================ 出栈 ============================ */

    /**
     * 弹出栈顶一层：当前页右滑淡出后移除，自然露出下层。
     *
     * @param onDone 移除完成（动画结束）后回调；栈空或 View 已不在树上时立即回调。
     */
    @JvmStatic
    fun pop(ctx: Context?, onDone: (() -> Unit)?): Boolean {
        val act = findActivity(ctx) ?: return false
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        // 先只读反查：栈空就返回 false，不要顺手建一个空桶（会触发 trim 挤掉别的宿主）。
        val stack = bucketOrNull(ctx) ?: return false
        if (stack.isEmpty()) {
            return false
        }
        val layer = stack.removeAt(stack.size - 1)
        val page = layer.view
        // 【r12】先跑该页登记的离开清理（如收起预览悬浮窗），再做视觉退场。
        fireLeave(layer.key)
        val parent = page.parent
        if (parent !is ViewGroup || content == null) {
            onDone?.invoke()
            return true
        }
        clearFocusDeep(page)
        page.animate().translationX(pageW(content) * 0.14f).alpha(0f)
            .setDuration(UiKit.D_LAYER.toLong()).setInterpolator(UiKit.EASE_ACCEL)
            .withEndAction {
                parent.removeView(page)
                onDone?.invoke()
            }.start()
        return true
    }

    /** 弹出一层，不关心结束回调。 */
    @JvmStatic
    fun pop(ctx: Context?): Boolean {
        return pop(ctx, null)
    }

    /** 栈顶就是该 key 时才弹；用于「本页开着就关掉」的返回语义。 */
    @JvmStatic
    fun popIfTop(ctx: Context?, key: String?): Boolean {
        if (!isTop(ctx, key)) {
            return false
        }
        return pop(ctx, null)
    }

    /**
     * 统一的返回键入口：有层就退一层。
     * 【注】供应商模块内部多级退栈由 [com.dollhouse.app.ui.provider.ProviderNav.handleBack]
     *   先行处理（它需要同步自己的路由栈），这里兜底其余所有层。
     */
    @JvmStatic
    fun handleBack(ctx: Context): Boolean {
        if (isEmpty(ctx)) {
            return false
        }
        return pop(ctx, null)
    }

    /**
     * 清空某宿主的栈。Activity 重建时在 `onCreate` 里先调（重建后 View 树已销毁，
     * 旧层引用全部失效）；宿主销毁时在 `onDestroy` 里再调一次，顺带回收分桶防泄漏。
     *
     * 【r10 修正·必须连 View 一起摘】原实现「只清记账，不碰 View」，前提假设是
     *   「调用时 View 已经不在任何父容器上」。这个假设在「宿主未真正销毁、只是走了一遍
     *   重建流程」的路径下不成立：View 仍挂在 `android.R.id.content` 上，而记账已被抹掉 ——
     *   于是它变成一层「谁也认不出、谁也摘不掉」的幽灵页：不参与层序、不会被 pop，
     *   却实实在在盖在最上面拦触摸（用户报的「视觉在主页、触摸落在二级页」正是这个形态）。
     *   摘 View 本身是无害的：父容器为 null 时跳过，正要被回收时 removeView 也只是空操作。
     */
    @JvmStatic
    fun reset(ctx: Context?) {
        val act = findActivity(ctx)
        if (act == null) {
            return
        }
        val stack = HOSTS.remove(System.identityHashCode(act))
        if (stack != null) {
            for (l in stack) {
                val p = l.view.parent
                if (p is ViewGroup) {
                    p.removeView(l.view)
                }
            }
        }
    }

    /**
     * 【r10 新增】记账与 View 树对账：让「栈里记的」与「树上挂的」重新一致。
     *
     * 【为什么需要】层序有两份真源：本对象的 [HOSTS]（记账）与 `android.R.id.content`
     *   的子 View 列表（事实）。正常路径下两者同进同出；但下面几条路径会让它们错位：
     *   ① `singleTask` 的宿主被系统从最近任务唤回时不会重建 View 树，而记账可能已被
     *      `onCreate` 里的 [reset] 清过一轮；
     *   ② 退场动画的 `withEndAction` 在宿主进入后台时被打断，`removeView` 没跑到；
     *   ③ 厂商 ROM 的「清理后台」只摘任务、不真正销毁进程。
     *   错位的结果就是幽灵层（拦触摸）或幽灵记账（层数虚高、返回键白按一次）。
     *
     * 【三条规则，都以「事实」为准】
     *   A. 记账里但 `parent` 已不是 `content` 的层 —— 树上已经没有它了，从记账里删掉。
     *   B. `content` 的直接子 View 里，**tag 是合法页面 key** 但不在记账里的 —— 幽灵，摘掉。
     *   C. 栈顶那层挂着却透明 —— 入场动画被打断的残局，补回可见终态。
     *
     * 【规则 B 为什么必须用 [PageRegistry] 过滤，而不是「有 tag 就摘」】
     *   `android.R.id.content` 的直接子上带 tag 的不止页面：
     *   底座宿主 `host`（tag 为 null）之外，`ChatActivity` 的聊天面板会把自己的父
     *   FrameLayout（就是 content 本身）当挂载层，而 `SheetPanel` 的遮罩、
     *   `ApiPageKit.entryRow` 的行容器都可能带 tag 出现在这棵树上。
     *   无差别摘掉会把「用户正开着的底部面板」连根拔掉。
     *   用 [PageRegistry] 判「这个 tag 认不认得」是最稳的：页面 key 全部登记在册，
     *   非页面 tag 一律不认，天然不会误伤。
     *
     * 【幂等】无错位时一个分支都不进，可在每次回前台时无条件调用。
     */
    @JvmStatic
    fun reconcile(ctx: Context?) {
        val act = findActivity(ctx) ?: return
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        val stack = HOSTS[System.identityHashCode(act)]

        // 规则 A：记账里的层若已不在 content 上，说明它其实已经被移除，只是回调没跑到。
        if (stack != null && !stack.isEmpty()) {
            var i = stack.size - 1
            while (i >= 0) {
                if (stack[i].view.parent !== content) {
                    stack.removeAt(i)
                }
                i--
            }
        }

        // 规则 B：树上有、记账里没有的「页面」= 幽灵，摘掉。
        for (i in content.childCount - 1 downTo 0) {
            val child = content.getChildAt(i)
            val tag = child.tag
            if (tag !is String || tag.isEmpty()) {
                continue
            }
            if (!PageRegistry.isPageKey(tag)) {
                continue
            }
            if (stack != null && indexOf(stack, tag) >= 0) {
                continue
            }
            content.removeViewAt(i)
        }

        // 规则 C：栈顶那层「挂着却透明」= 入场动画被打断（最典型的是入栈瞬间被切到后台）。
        //   它依然占着最上层拦触摸，视觉上却像回到了主页 —— 用户报的
        //   「视觉停在主页、触摸仍落二级页」正是这个形态。
        //   栈顶按定义必须是可见的，这里直接补回终态（cancel 掉可能还在跑的旧动画，免得又被拉回 0）。
        if (stack != null && !stack.isEmpty()) {
            val top = stack[stack.size - 1].view
            if (top.parent === content && top.alpha < 0.05f) {
                top.animate().cancel()
                top.visibility = View.VISIBLE
                top.alpha = 1f
                top.translationX = 0f
            }
        }

        // 全摘空了就把空桶也回收掉，免得 [MAX_HOSTS] 被无意义的桶占满。
        if (stack != null && stack.isEmpty()) {
            HOSTS.remove(System.identityHashCode(act))
        }
    }

    /* ============================ 内部 ============================ */

    private fun indexOf(stack: List<Layer>, key: String?): Int {
        if (key == null) {
            return -1
        }
        for (i in stack.indices) {
            if (stack[i].key == key) {
                return i
            }
        }
        return -1
    }

    /** 移除第 from 层之上的所有层（连同 View）。 */
    private fun removeAbove(stack: ArrayList<Layer>, content: ViewGroup, from: Int) {
        while (stack.size > from + 1) {
            val gone = stack.removeAt(stack.size - 1)
            // 【r12】被顶掉的层同样要跑离开清理。
            fireLeave(gone.key)
            val p = gone.view.parent
            clearFocusDeep(gone.view)
            if (p is ViewGroup) {
                p.removeView(gone.view)
            }
        }
    }

    /**
     * 【r12 修 bug·蓝色水滴残留】摘页前把这一层里的输入焦点清掉。
     *
     * 【为什么要它】页面的关闭走 `removeView`，而 ComposeView 的组合策略是
     *   `DisposeOnViewTreeLifecycleDestroyed` —— owner 挂在 decorView 上还活着，
     *   所以**组合不会随摘页销毁**。若关页那一刻输入框正聚焦着，它的文本光标把手
     *   （Compose 画在独立 Popup 里的那个蓝色水滴）就可能留在屏幕上，变成「幽灵光标」。
     *   findFocus() 递归找当前持焦的 View（Compose 的 AndroidComposeView 也在
     *   这套 View 焦点系统里），clearFocus 会让 Compose 侧同步失焦、把手收起。
     *
     * 【为什么不用 DisposableEffect】那条路要求组合真的被销毁才触发，本场景不会。
     */
    private fun clearFocusDeep(v: View?) {
        try {
            val focused = v?.findFocus()
            if (focused != null) {
                focused.clearFocus()
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 原地换掉第 idx 层的 View（淡入新页、移除旧页）；[slide] 为真时新页自右滑入。 */
    private fun swapInPlace(stack: ArrayList<Layer>, content: ViewGroup, idx: Int,
                            key: String, fresh: View, slide: Boolean) {
        val old = stack[idx].view
        fresh.tag = key
        fresh.alpha = 0f
        if (slide) {
            fresh.translationX = pageW(content) * 0.14f
        }
        content.addView(fresh, ViewGroup.LayoutParams(-1, -1))
        GlobalBackground.installPage(fresh)
        stack[idx] = Layer(key, fresh)
        val anim = fresh.animate().alpha(1f).setDuration(UiKit.D_PAGE.toLong())
            .setInterpolator(UiKit.EASE_DECEL)
        if (slide) {
            anim.translationX(0f)
        }
        anim.withEndAction {
            val p = old.parent
            if (p is ViewGroup) {
                p.removeView(old)
            }
        }.start()
    }

    /** 屏宽（拿不到布局宽度时兜底用显示宽度）。 */
    private fun pageW(content: ViewGroup): Int {
        val w = content.width
        return if (w > 0) w else content.resources.displayMetrics.widthPixels
    }

    /** 从任意 Context 向上找到宿主 Activity（悬浮窗场景无 Activity，返回 null）。 */
    private fun findActivity(ctx: Context?): Activity? {
        var c: Context? = ctx
        var i = 0
        while (i < 8 && c != null) {
            if (c is Activity) {
                return c
            }
            if (c is ContextWrapper) {
                c = c.baseContext
            } else {
                return null
            }
            i++
        }
        return null
    }
}
