package com.dollhouse.app.ui.provider

import com.dollhouse.app.ui.settings.ModelEditPage
import com.dollhouse.app.ui.settings.ModelListPage
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.dollhouse.app.ai.ApiClient
import com.dollhouse.app.ai.ApiEndpoint
import com.dollhouse.app.ai.ModelRules
import com.dollhouse.app.ai.Provider
import com.dollhouse.app.core.KeyVault
import com.dollhouse.app.data.ProviderStore
import com.dollhouse.app.ui.settings.ExperimentPage
import com.dollhouse.app.ui.settings.LamdaPage
import com.dollhouse.app.ui.theme.UiKit
import com.dollhouse.app.ui.widget.ApiPageKit
import java.net.HttpURLConnection

/**
 * 【职责】「提供商」模块的页面栈与跨页动作：前进 / 返回 / 关页，以及需要跨页复用的动作
 *        （删除确认、连通性测试）。
 *
 * 【为什么要有栈】规格书里这一块是四级结构：供应商列表 → 供应商详情 → 模型列表 → 模型编辑。
 *        原先只有一个「一级 / 二级」的静态字符串，撑不下四级；用栈才不会一按返回就退到桌面。
 *
 * 【路由】用字符串描述栈内每一层，渲染时按栈顶分派到对应页面。新增一层只加一个分支，
 *        不动已有页面。
 */
object ProviderNav {

    /** 整页挂在 android.R.id.content 上时用的 tag（沿用旧值，返回链无需改动）。 */
    const val TAG_PAGE = "feiyu_cfg_page"

    const val R_LIST = "list"
    const val R_NEW = "new"
    /** 独立思考行为页：从设置页进入，不与供应商栈混在一起。 */
    const val R_BEHAVIOR = "behavior"
    /** lamda 设备控制页：从设置页进入，同样是独立子页。 */
    const val R_LAMDA = "lamda"
    /** 实验页：从设置页「实验」卡片进入，当前仅收纳 lamda 设备控制。 */
    const val R_EXPERIMENT = "experiment"
    private const val R_EDIT = "edit:"
    private const val R_MODELS = "models:"
    private const val R_MNEW = "mnew:"
    private const val R_MEDIT = "medit:"
    /** 供应商详情页：detail:<providerId>:<tab>。tab 只有 cfg / mdl 两种。 */
    private const val R_DETAIL = "detail:"
    /**
     * 新建供应商态：同样是「详情页壳」，只是里面那层配置表单是空表。
     * 【为什么单独一条路由】它必须能与「已存在供应商的详情页」区分开（没有 id 可查库），
     *   但又要走同一个壳（底部「配置｜模型」胶囊栏），否则加号进去与点卡片进去长相不同。
     * 【为什么不用 detail: 前缀】占用了就会让 detailId() 把空 id 当成真实 id，
     *   于是新建态会掉进「供应商已不存在」的兜底分支。
     */
    private const val R_DETAIL_NEW = "detail-new"
    /** 详情页的「配置」tab：该供应商的名称 / 密钥 / 地址 / 开关。 */
    const val TAB_CFG = "cfg"
    /** 详情页的「模型」tab：该供应商已添加的模型列表。 */
    const val TAB_MDL = "mdl"

    /** 页面栈。栈底永远是 R_LIST。 */
    private val STACK: MutableList<String> = ArrayList()

    /* ------------------------------ 入口 / 出口 ------------------------------ */

    /** 设置页「提供商」入口行的落地动作。 */
    @JvmStatic
    fun open(ctx: Context) {
        openAt(ctx, R_LIST)
    }

    /** 从设置页直接进某张子页（例如「对话行为」）。栈底仍是该页，返回一次即关门。 */
    @JvmStatic
    fun openAt(ctx: Context, root: String?) {
        val act = ApiPageKit.findActivity(ctx) ?: return
        STACK.clear()
        STACK.add(if (root == null || root.isEmpty()) R_LIST else root)
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        // 已有旧页先摘掉，避免同一 tag 挂两层。
        val old = content.findViewWithTag<View>(TAG_PAGE)
        if (old != null) {
            content.removeView(old)
        }
        UiKit.openPage(content, render(act), TAG_PAGE)
    }

    /** 本页是否开着。 */
    @JvmStatic
    fun isOpen(ctx: Context): Boolean {
        return try {
            val act = ApiPageKit.findActivity(ctx) ?: return false
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
            content.findViewWithTag<View>(TAG_PAGE) != null
        } catch (ignored: Throwable) {
            false
        }
    }

    /** 返回键：栈里还有上一层就回退，到底了就关门。永远返回 true（本页开着）。 */
    @JvmStatic
    fun handleBack(ctx: Context): Boolean {
        return try {
            val act = ApiPageKit.findActivity(ctx) ?: return false
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
            val old = content.findViewWithTag<View>(TAG_PAGE) ?: return false
            if (STACK.size > 1) {
                STACK.removeAt(STACK.size - 1)
                UiKit.swapPage(content, render(act), TAG_PAGE)
            } else {
                STACK.clear()
                UiKit.closePage(old)
            }
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    /** 无论在第几层都直接关门（退出设置页 / 页面重挂时用）。 */
    @JvmStatic
    fun closeIfOpen(ctx: Context): Boolean {
        return try {
            val act = ApiPageKit.findActivity(ctx) ?: return false
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
            val old = content.findViewWithTag<View>(TAG_PAGE) ?: return false
            STACK.clear()
            UiKit.closePage(old)
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    /** 保存当前整条路由栈（全为稳定标识，跨系统重建可原样恢复）。未开页时为空表。 */
    @JvmStatic
    fun saveRoutes(): ArrayList<String> {
        return try {
            ArrayList(STACK)
        } catch (ignored: Throwable) {
            ArrayList()
        }
    }

    /**
     * 用 [saveRoutes] 存下的稳定栈列表重建当前覆盖页。
     * 【为什么认路由而不是视图】系统重建后视图全没了，只有路由字符串还认得出来。
     * 空表 / null 视为「没开过页」，原地不动，避免把首页盖住。
     */
    @JvmStatic
    fun restoreRoutes(act: Activity?, routes: List<String>?) {
        if (act == null || routes == null || routes.isEmpty()) {
            return
        }
        try {
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            STACK.clear()
            STACK.addAll(routes)
            val old = content.findViewWithTag<View>(TAG_PAGE)
            if (old != null) {
                content.removeView(old)
            }
            UiKit.openPage(content, render(act), TAG_PAGE)
        } catch (ignored: Throwable) {
        }
    }

    /* ------------------------------ 栈操作 ------------------------------ */

    /** 前进一层：新页自右侧滑入。 */
    @JvmStatic
    fun push(act: Activity?, route: String?) {
        if (act == null || route == null || route.isEmpty()) {
            return
        }
        STACK.add(route)
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        UiKit.openPage(content, render(act), TAG_PAGE)
    }

    /** 原地重建当前层（保存 / 启停 / 删除之后刷新画面）。 */
    @JvmStatic
    fun refresh(act: Activity?) {
        if (act == null) {
            return
        }
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        UiKit.swapPage(content, render(act), TAG_PAGE)
    }

    /** 子页保存 / 删除完成后的「返回上一层 + 刷新」：一步到位，避免中间态闪一下。 */
    @JvmStatic
    fun back(act: Activity?) {
        if (act == null) {
            return
        }
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (STACK.size > 1) {
            STACK.removeAt(STACK.size - 1)
        }
        UiKit.swapPage(content, render(act), TAG_PAGE)
    }

    /* ------------------------------ 渲染分派 ------------------------------ */

    private fun render(act: Activity): View {
        val top = if (STACK.isEmpty()) R_LIST else STACK[STACK.size - 1]
        if (top.startsWith(R_EDIT)) {
            return ProviderEditPage.build(act, idAfter(top, R_EDIT))
        }
        if (top.startsWith(R_DETAIL)) {
            return ProviderDetailPage.build(act, detailId(top), detailTabOf(top))
        }
        if (R_DETAIL_NEW == top) {
            // 新建态：同一个详情壳，providerId 传空串 —— 表单按「新建」渲染（标题「添加供应商」）。
            return ProviderDetailPage.build(act, "", TAB_CFG)
        }
        if (top.startsWith(R_MODELS)) {
            return ModelListPage.build(act, idAfter(top, R_MODELS))
        }
        if (top.startsWith(R_MNEW)) {
            return ModelEditPage.buildNew(act, idAfter(top, R_MNEW))
        }
        if (top.startsWith(R_MEDIT)) {
            return ModelEditPage.buildEdit(act, idAfter(top, R_MEDIT))
        }
        if (R_NEW == top) {
            return ProviderEditPage.build(act, null)
        }
        if (R_LAMDA == top) {
            return LamdaPage.build(act)
        }
        if (R_EXPERIMENT == top) {
            return ExperimentPage.build(act)
        }
        return ProviderListPage.build(act)
    }

    private fun idAfter(route: String, prefix: String): String {
        return if (route.length > prefix.length) route.substring(prefix.length) else ""
    }

    /* ------------------------------ 路由构造 ------------------------------ */

    @JvmStatic
    fun openProvider(act: Activity, providerId: String?) {
        push(act, R_EDIT + providerId)
    }

    /** 供应商列表点某一行的落地动作：进该供应商的详情页（默认停在「配置」tab）。 */
    @JvmStatic
    fun openDetail(act: Activity, providerId: String?) {
        push(act, R_DETAIL + providerId + ":" + TAB_CFG)
    }

    /**
     * 只改栈顶详情页的 tab。
     * 【为什么不走 push/refresh】两者都会重建整页，重建会把胶囊指示器的滑动动画
     *   一起清掉（页面被换掉，动画没了宿主），看上去就是「颜色啪一下变了」。
     *   这里只改路由字符串，画面由详情页自己就地切内容 + 滑指示器。
     */
    @JvmStatic
    fun setDetailTab(providerId: String?, tab: String?) {
        if (STACK.isEmpty()) {
            return
        }
        STACK[STACK.size - 1] = R_DETAIL + providerId + ":" + tabOf(tab)
    }

    /**
     * 栈顶详情页当前对应的供应商 id；取不到（新建态 / 不在详情页）时回落到 [fallback]。
     * 【为什么不能直接用进页时那个参数】新建态进页时 id 是空的，保存成功后栈顶被
     *   [replaceDetailNew] 换成了真实 id —— 页面本身不重建，闭包里那个空串也就不会更新，
     *   所以每次切 tab 都要现从栈顶解析一次。
     */
    @JvmStatic
    fun currentDetailId(fallback: String?): String {
        val byRoute = if (STACK.isEmpty()) "" else detailId(STACK[STACK.size - 1])
        if (byRoute.length > 0) {
            return byRoute
        }
        return if (fallback == null) "" else fallback
    }
    /**
     * 新建供应商保存成功后调用：把栈顶的新建态路由换成真实 id 的详情路由。
     * 【为什么不 refresh】refresh 会重建整页，把刚写进表单的「已保存，正在测试连接…」
     *   与随后的连通性测试结果一起清掉。这里只改栈顶字符串，画面原地不动。
     * 【不换会怎样】新建态路由没有 id，切到「模型」栏会查不到供应商、落到兜底页。
     */
    @JvmStatic
    fun replaceDetailNew(providerId: String?) {
        if (STACK.isEmpty()) {
            return
        }
        // 【新建态有两种栈顶写法】没切过 tab 时是 R_DETAIL_NEW；切过一次 tab 之后，
        //   详情页会把栈顶改写成「空 id 的详情路由」（detail::cfg / detail::mdl）——
        //   两种都代表「这个详情页还没保存」，都要换掉，不能只认前者。
        val top = STACK[STACK.size - 1]
        val isNewTop = R_DETAIL_NEW == top ||
            (top.startsWith(R_DETAIL) && detailId(top).isEmpty())
        if (!isNewTop) {
            return
        }
        STACK[STACK.size - 1] = R_DETAIL + providerId + ":" + TAB_CFG
    }
    /** 详情页路由里的供应商 id。 */
    @JvmStatic
    fun detailId(route: String?): String {
        if (route == null || !route.startsWith(R_DETAIL)) {
            return ""
        }
        val rest = route.substring(R_DETAIL.length)
        val cut = rest.lastIndexOf(':')
        return if (cut < 0) rest else rest.substring(0, cut)
    }

    /** 详情页路由里的 tab。非法值一律回落成「配置」，绝不把页面渲染成空白。 */
    @JvmStatic
    fun detailTabOf(route: String?): String {
        if (route == null || !route.startsWith(R_DETAIL)) {
            return TAB_CFG
        }
        val rest = route.substring(R_DETAIL.length)
        val cut = rest.lastIndexOf(':')
        return if (cut < 0) TAB_CFG else tabOf(rest.substring(cut + 1))
    }

    private fun tabOf(t: String?): String {
        return if (TAB_MDL == t) TAB_MDL else TAB_CFG
    }

    /** 栈顶详情页当前的 tab（不在详情页时返回「配置」）。点同 tab 时不重复切。 */
    @JvmStatic
    fun currentDetailTab(): String {
        return if (STACK.isEmpty()) TAB_CFG else detailTabOf(STACK[STACK.size - 1])
    }

/**
     * 新建供应商：走 R_DETAIL_NEW 路由（详情页壳 + 空表单），与点卡片进详情页同一种长相。
     * 【为什么不再走 R_NEW】R_NEW 是「光板独立页」，没有底部「配置｜模型」胶囊栏，
     *   于是「加号进的界面」与「点卡片进的界面」是两个长相 —— 用户明确要求统一。
     *   这里改成「新建态也进详情壳」，两颗 tab 都在，壳与列表页进来看起来完全一致。
     */
    @JvmStatic
    fun openNewProvider(act: Activity) {
        push(act, R_DETAIL_NEW)
    }

    @JvmStatic
    fun openModels(act: Activity, providerId: String?) {
        push(act, R_MODELS + providerId)
    }

    @JvmStatic
    fun openNewModel(act: Activity, providerId: String?) {
        push(act, R_MNEW + providerId)
    }

    @JvmStatic
    fun openModel(act: Activity, modelId: String?) {
        push(act, R_MEDIT + modelId)
    }

    /* ------------------------------ 跨页动作：连通性测试 ------------------------------ */

    /** 连通性测试回调。 */
    interface TestCallback {
        fun onDone(ok: Boolean, detail: String?)
    }

    /**
     * 保存供应商后自动跑一次连通性测试。
     * 【口径】请求 {baseUrl}/models，2xx 视为通过；失败显示状态码与响应体前 200 字。
     * 【为什么放这里】保存页与「测试连接」按钮都要用，放页里会重复一份网络代码。
     * 【隐私】密钥只在内存里流转，不进日志、不进提示文案。
     */
    @JvmStatic
    fun testConnection(ctx: Context?, pv: Provider?, cb: TestCallback) {
        if (ctx == null || pv == null) {
            return
        }
        val key = ProviderStore.keyOf(ctx, pv)
        Thread {
            var ok = false
            var detail = ""
            val url = ApiClient.modelsUrl(pv)
            if (url.isEmpty()) {
                detail = "还没填 API Base Url"
            } else if (key.isEmpty()) {
                // 【修·必须】本地没有可用密钥时根本不发请求。
                //   老版本照发，服务端回 401 Invalid token，用户只看到「密钥不对」，
                //   却查不出问题在本地（这正是不兼容别的软件能用的头号原因）。
                detail = KeyVault.problem(pv.apiKey) + "\n请求地址：" + url
            } else {
                var conn: HttpURLConnection? = null
                try {
                    val c = ApiClient.open(url, pv, key, "GET", false)
                    conn = c
                    val code = c.responseCode
                    val body = ApiClient.readBody(c, code)
                    if (code in 200..299) {
                        ok = true
                        detail = "连接正常（HTTP " + code + "）\n请求地址：" + url
                    } else {
                        detail = ApiEndpoint.explainHttpError(code, body, url)
                    }
                } catch (t: Throwable) {
                    detail = ApiEndpoint.friendlyError(t, url)
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            val fok = ok
            val fdetail = detail
            Handler(Looper.getMainLooper()).post { cb.onDone(fok, fdetail) }
        }.start()
    }

    /* ------------------------------ 跨页动作：拉取模型清单 ------------------------------ */

    /** 模型清单回调。 */
    interface ModelsCallback {
        fun onDone(models: MutableList<String>?, error: String?)
    }

    /**
     * 拉取供应商的可用模型清单（GET {baseUrl}/models）。
     * 【口径】地址与鉴权全走 ApiClient / ModelRules，业务层不写协议分支。
     * 【为什么放这里】模型列表页与编辑页的「从供应商拉取」都要用，放页里会重复一份网络代码。
     */
    @JvmStatic
    fun fetchModels(ctx: Context?, pv: Provider?, cb: ModelsCallback?) {
        if (ctx == null || pv == null || cb == null) {
            return
        }
        val key = ProviderStore.keyOf(ctx, pv)
        Thread {
            var list: MutableList<String>? = null
            var err: String? = null
            val url = ApiClient.modelsUrl(pv)
            if (url.isEmpty()) {
                err = "还没填 API Base Url"
            } else if (key.isEmpty()) {
                // 同 testConnection：没密钥就不发无效请求，先把原因说清楚。
                err = KeyVault.problem(pv.apiKey) + "\n请求地址：" + url
            } else {
                var conn: HttpURLConnection? = null
                try {
                    val c = ApiClient.open(url, pv, key, "GET", false)
                    conn = c
                    val code = c.responseCode
                    val body = ApiClient.readBody(c, code)
                    if (code in 200..299) {
                        val parsed = ModelRules.parseModels(body)
                        list = parsed
                        if (parsed.isEmpty()) {
                            err = "接口没返回模型列表"
                        }
                    } else {
                        err = ApiEndpoint.explainHttpError(code, body, url)
                    }
                } catch (t: Throwable) {
                    err = ApiEndpoint.friendlyError(t, url)
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            val fl = list
            val fe = err
            Handler(Looper.getMainLooper()).post { cb.onDone(fl, fe) }
        }.start()
    }
}
