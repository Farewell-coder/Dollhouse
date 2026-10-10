package com.dollhouse.app.ui.theme

import android.app.Activity
import android.graphics.ColorFilter
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.dollhouse.app.ui.compose.DhTheme
import java.util.WeakHashMap

/**
 * 【职责】换主题 / 系统日夜切换时「就地刷新」当前界面：不 recreate、不跳回首页。
 *
 * 【为什么需要】ThemeManager.apply() 只改 UiKit 的 29 个静态颜色字段；
 *   而已经建好的 View 里，文字色、背景 Drawable、图标 tint 都是在创建那一刻“抄”进去的快照，
 *   静态字段变了它们也不会动。所以必须递归遍历视图树，把「旧调色板色 → 新调色板色」逐点重映射。
 *
 * 【覆盖范围】
 *   1) TextView 文字色 / hint 色（按真实底色做语义对比纠偏，见 [remapForeground]）；
 *   2) 图标 tint（按 PorterDuffColorFilter 读出旧色再换）；
 *   3) 背景 Drawable（GradientDrawable 纯色 / 渐变填充、ColorDrawable、StateListDrawable、
 *      LayerDrawable、InsetDrawable）；
 *   4) 自绘控件：UiKit.Switch 需重设轨道底色（构造期写死），UiKit.Slider / 自绘 View 读字段绘制，invalidate 即可。
 *
 * 【映射口径】按 RGB 命中，保留原 alpha——这样 al(color, 0x30) 这类派生透明色也能跟着换，
 *   不会因为 alpha 不同被漏掉。
 *
 * 【用法】
 *   - 换主题：先 [applyInPlace]（内部自带快照 → apply → 对比度夹紧 → 重建 → 刷背景）。
 *   - 或在别处自己调 ThemeManager.apply() 后，用 [rebuild] 传入「apply 之前的快照」重建。
 *   - 冷启动建界面时调一次 [rememberPalette]，给后续 rebuild 一个基线。
 */
object ThemeRefresh {

    /** 上一次已知的调色板（apply 之前的“旧值”来源）。 */
    private var lastPalette: IntArray? = null

    /** 记住当前 UiKit 调色板作为基线（冷启动 / 首次构建时调用）。 */
    fun rememberPalette() {
        lastPalette = UiKit.snapshotPalette()
    }

    /** 取当前 UiKit 调色板快照（顺序与 ThemeManager 的赋值顺序一致）。 */
    fun snapshotPalette(): IntArray = UiKit.snapshotPalette()

    /**
     * 一步到位：快照旧色 → ThemeManager.apply → UiKit 对比度夹紧 → 递归重映射 → 重刷全局背景。
     * 调用方无需 recreate()。窗口背景由 ThemeManager.apply() 负责同步。
     */
    fun applyInPlace(act: Activity?) {
        if (act == null) {
            return
        }
        val old = snapshotPalette()
        ThemeManager.apply(act)
        UiKit.clampPaletteContrast()
        rebuild(act.window?.decorView, old)
        // 【Compose 侧同步】rebuild 只重映射 View 树的配色；ComposeView 内部的节点不在
        //   那棵「手写 View」树的可识别范围内（它们是 Compose 自己管理的节点），
        //   所以必须额外通知一次，让所有 Compose 页重新从 UiKit 取色（详见 DhTheme）。
        DhTheme.bump()
        GlobalBackground.refreshAll()
    }
    /**
     * 【外观】卡片透明度变更后就地生效：把已建视图上「旧卡片色」的底色换成新的半透明卡片色。
     *
     * 【为什么不复用 [rebuild]】[rebuild] 是按 RGB 命中的：卡片透明度只改 alpha、不改 RGB，
     *   旧色与新色 RGB 相同，逐点重映射会判定「没变」而原样跳过。所以这里单独做一遍
     *   「整值（ARGB 全等）命中才换」的重涂。
     *
     * 【覆盖面】View 侧手写卡片的背景 Drawable + Compose 侧（靠 [DhTheme.bump] 重新取色）。
     *   自绘控件（如桌宠气泡）由调用方另行通知服务重读。
     */
    @JvmStatic
    fun applyCardAlpha(act: Activity?, percent: Int) {
        val old = UiKit.card()
        UiKit.applyCardAlpha(percent)
        val neu = UiKit.card()
        if (act == null || old == neu) {
            return
        }
        walk(act.window?.decorView) { v -> retintCard(v.background, old, neu) }
        DhTheme.bump()
        GlobalBackground.refreshAll()
    }
    /** 把 Drawable 里等于 [old] 的底色整值换成 [neu]（保留描边 / 圆角 / 层级）。 */
    private fun retintCard(d: Drawable?, old: Int, neu: Int) {
        if (d == null) {
            return
        }
        when (d) {
            is GradientDrawable -> {
                if (drawableSolid(d) == old) {
                    try {
                        d.setColor(neu)
                    } catch (ignored: Throwable) {
                    }
                }
            }
            is ColorDrawable -> {
                if (d.color == old) {
                    d.color = neu
                }
            }
            is StateListDrawable -> {
                for (i in 0 until d.stateCount) {
                    retintCard(d.getStateDrawable(i), old, neu)
                }
            }
            is LayerDrawable -> {
                for (i in 0 until d.numberOfLayers) {
                    retintCard(d.getDrawable(i), old, neu)
                }
            }
            is InsetDrawable -> retintCard(d.drawable, old, neu)
        }
    }
    /**
     * 递归重建一棵视图树的配色。
     *
     * @param root       根视图（通常传 activity.window.decorView）。
     * @param oldPalette apply 之前的调色板快照；省略时用最近一次 [rememberPalette] / [rebuild] 的基线。
     */
    fun rebuild(root: View?, oldPalette: IntArray? = null) {
        val old = oldPalette ?: lastPalette ?: return
        val neu = snapshotPalette()
        lastPalette = neu
        remapTree(root, old, neu)
    }

    /* ------------------------- 页面 / 输入状态（系统重建时保命） -------------------------
     * 未声明 configChanges 时，系统日夜切换仍会重建 Activity。这里只保存三类「可安全复原」的东西：
     *   1) 页面路由：HomeUi 当前页 + 当前覆盖页，用「稳定路由标识」表述（见 [PageState]），
     *      由 HomeUi 侧实现并在建界面时注册，本类不关心具体页面类型；
     *   2) 滚动位置：按遍历序记录各 ScrollView 的 scrollY；
     *   3) 非敏感输入：只回填被 [markRestorable] 显式登记过的 EditText。
     * 【为什么不再全量拷输入】历史上按遍历序把所有 EditText.text 塞进 Bundle，
     *   会把供应商页里的 API Key 等密钥一起复制进系统状态（可能落盘 / 被 dump），
     *   且顺序回填在页面数变化时会错位。现在默认「不保存任何输入框」，必须显式登记才保。
     */

    /**
     * 页面状态提供方：由 HomeUi 实现并注册。
     * 【标识要求】必须是稳定常量（如 "home" / "chat" / "provider:edit:<id>"），
     *   严禁用视图下标 / 数组位置这类会随版本漂移的标识。
     */
    interface PageState {
        /** 当前 HomeUi 可见页的稳定标识（无则 null）。 */
        fun currentHomePage(): String?
        /** 当前覆盖页的稳定标识（无则 null）。 */
        fun currentOverlayRoute(): String?
        /** 按同一套稳定标识复原页面。 */
        fun restore(homePage: String?, overlayRoute: String?)
    }

    /** 由 HomeUi 在建界面时注册；未注册时只复原滚动位置。 */
    @JvmField
    var pageState: PageState? = null

    /** 被显式登记为「可安全复原」的输入框（弱键，页面回收后自动消失）。 */
    private val restorableInputs = WeakHashMap<View, Boolean>()

    /** 标记某个 EditText 为非敏感、可在系统重建后回填。含密钥 / 隐私的输入框不要调用。 */
    fun markRestorable(v: EditText?) {
        if (v != null) {
            restorableInputs[v] = true
        }
    }

    /** 采集滚动位置、非敏感输入与页面路由。返回的 Bundle 交给 onSaveInstanceState。 */
    fun saveState(root: View?): Bundle {
        val texts = ArrayList<String>()
        val scrolls = ArrayList<Int>()
        walk(root) { v ->
            if (v is EditText && restorableInputs.containsKey(v)) {
                texts.add(v.text?.toString() ?: "")
            } else if (v is ScrollView) {
                scrolls.add(v.scrollY)
            }
        }
        val b = Bundle()
        b.putStringArrayList(KEY_TEXTS, texts)
        b.putIntegerArrayList(KEY_SCROLLS, scrolls)
        val ps = pageState
        if (ps != null) {
            b.putString(KEY_HOME_PAGE, ps.currentHomePage())
            b.putString(KEY_OVERLAY_ROUTE, ps.currentOverlayRoute())
        }
        return b
    }

    /** 回填 [saveState] 采集的状态：先复原路由，再按遍历序回填滚动与已登记输入。 */
    fun restoreState(root: View?, state: Bundle?) {
        if (root == null || state == null) {
            return
        }
        pageState?.restore(state.getString(KEY_HOME_PAGE), state.getString(KEY_OVERLAY_ROUTE))
        val texts = state.getStringArrayList(KEY_TEXTS) ?: ArrayList()
        val scrolls = state.getIntegerArrayList(KEY_SCROLLS) ?: ArrayList()
        var ti = 0
        var si = 0
        walk(root) { v ->
            if (v is EditText && restorableInputs.containsKey(v)) {
                if (ti < texts.size) {
                    v.setText(texts[ti])
                    v.setSelection(v.text?.length ?: 0)
                    ti++
                }
            } else if (v is ScrollView) {
                if (si < scrolls.size) {
                    val y = scrolls[si]
                    si++
                    v.post { v.scrollTo(0, y) }
                }
            }
        }
    }

    /* ------------------------- 纯内存输入草稿（配置重建时保命，不落盘） -------------------------
     * 【为什么另起一条通道】系统日夜 / 语言 / 字号 / 旋转触发的 Activity 重建会清空在途输入。
     *   若走 Bundle（[saveState] / [restoreState] 与 markRestorable 登记）会把文本写进系统状态，
     *   可能落盘或被 dump；对 API Key、地址、模型名、聊天正文这类隐私内容不可接受。
     * 【怎么做】改用 Activity.onRetainNonConfigurationInstance：把输入按「结构路径键」采集进一个
     *   纯内存对象，随重建跨实例传递，onCreate 取回后回填；进程死亡不保留，存活时间很短。
     * 【约束】草稿只持有 结构路径键 / 文本 / 选区 三类数据，不含 View / Activity，也不重写 toString；
     *   回填后由 [discardDraft] 立即清空，缩短明文在内存中的停留。
     */

    /** 纯内存输入草稿：结构路径键 + 文本 + 选区起止。刻意不是 data class（不生成 toString）。 */
    class InputDraft internal constructor(
            internal val keys: ArrayList<String>,
            internal val texts: ArrayList<String>,
            internal val selStart: IntArray,
            internal val selEnd: IntArray)

    /**
     * 采集当前树上所有 EditText 的在途输入，返回纯内存草稿。
     * 【键】从根到控件的「结构路径」：每层记 简单类名 + 该层子序号，例如
     *   `FrameLayout/FrameLayout@1/LinearLayout@0/EditText`；同一进程同一布局重建前后恒定，
     *   不依赖数组序，避免页面数变化时错位。
     * 【隐私】返回值只交给 onRetainNonConfigurationInstance，绝不写 Bundle / 文件 / 日志。
     */
    fun captureInputs(root: View?): InputDraft? {
        if (root == null) {
            return null
        }
        val keys = ArrayList<String>()
        val texts = ArrayList<String>()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        collectInputs(root, "", keys, texts, starts, ends)
        if (keys.isEmpty()) {
            return null
        }
        return InputDraft(keys, texts, starts.toIntArray(), ends.toIntArray())
    }

    /**
     * 把纯内存草稿回填到当前树，随后立即清空草稿。
     * 【顺序】调用方须在路由 / 页面最终恢复完成后调用（覆盖页已重建，结构路径才对齐）。
     * 【回填语义】按结构路径键精确配对；键对不上的输入保持现状，不会张冠李戴。
     */
    fun restoreInputs(root: View?, draft: InputDraft?) {
        if (draft == null) {
            return
        }
        try {
            if (root != null) {
                val byKey = HashMap<String, Int>(draft.keys.size)
                for (i in draft.keys.indices) {
                    byKey[draft.keys[i]] = i
                }
                applyInputs(root, "", byKey, draft)
            }
        } finally {
            discardDraft(draft)
        }
    }

    /** 回填后清空草稿：逐项换掉文本引用并清空列表，缩短明文在内存中的停留。 */
    fun discardDraft(draft: InputDraft?) {
        if (draft == null) {
            return
        }
        for (i in draft.texts.indices) {
            draft.texts[i] = ""
        }
        draft.texts.clear()
        draft.keys.clear()
    }

    private fun collectInputs(v: View, path: String, keys: ArrayList<String>,
                              texts: ArrayList<String>, starts: ArrayList<Int>,
                              ends: ArrayList<Int>) {
        val here = if (path.isEmpty()) v.javaClass.simpleName else path + "/" + v.javaClass.simpleName
        if (v is EditText) {
            keys.add(here)
            texts.add(v.text?.toString() ?: "")
            val s = v.selectionStart
            val e = v.selectionEnd
            starts.add(if (s >= 0) s else 0)
            ends.add(if (e >= 0) e else 0)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                collectInputs(v.getChildAt(i), here + "@" + i, keys, texts, starts, ends)
            }
        }
    }

    private fun applyInputs(v: View, path: String, byKey: Map<String, Int>, draft: InputDraft) {
        val here = if (path.isEmpty()) v.javaClass.simpleName else path + "/" + v.javaClass.simpleName
        if (v is EditText) {
            val idx = byKey[here]
            if (idx != null) {
                v.setText(draft.texts[idx])
                val len = v.text?.length ?: 0
                v.setSelection(draft.selStart[idx].coerceIn(0, len),
                        draft.selEnd[idx].coerceIn(0, len))
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                applyInputs(v.getChildAt(i), here + "@" + i, byKey, draft)
            }
        }
    }

    /**
     * 路由恢复落地后的一次性「静置」：把过场 / 错峰入场动画就地收尾。
     *
     * 【为什么需要】重建后还原路由时，覆盖页由 UiKit.openPage 自右滑入（≈300ms），
     *   首页 / 设置页的 UiKit.enter 错峰入场也可能才起步；若放任播放，恢复出来的画面会先动
     *   一下再定住，观感上像「跳回主页又切回来」。
     * 【怎么收】对 [content] 的直接子 View（覆盖页 + 承载首页 / 设置页的宿主）取消动画并复位
     *   translation / alpha；已 GONE 的隐藏页保持 GONE，绝不强行显形。再递归清掉子孙里残留的
     *   入场动画——只收 [UiKit.enter] 的 alpha / translationY，不动可见性，避免把本来隐藏的元素
     *   显示出来。整个过程只「停下并归位」，不触发任何新动画。
     *
     * @param content 覆盖页与首页宿主的共同父容器，通常是 android.R.id.content。
     */
    fun settleAfterRestore(content: View?) {
        val group = content as? ViewGroup ?: return
        // 直接子 View：各覆盖页 + 首页 / 设置页宿主。
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.animate().cancel()
            if (child.visibility != View.GONE) {
                child.alpha = 1f
                child.translationX = 0f
                child.translationY = 0f
            }
        }
        // 子孙：清掉入场中途态（alpha / translationY），静止在终点。
        walk(group) { v ->
            if (v !== group) {
                v.animate().cancel()
                if (v.visibility != View.GONE) {
                    if (v.alpha < 1f) {
                        v.alpha = 1f
                    }
                    if (v.translationY != 0f) {
                        v.translationY = 0f
                    }
                }
            }
        }
    }

    /* ------------------------- 内部实现 ------------------------- */

    private const val KEY_TEXTS = "dh_theme_texts"
    private const val KEY_SCROLLS = "dh_theme_scrolls"
    private const val KEY_HOME_PAGE = "dh_home_page"
    private const val KEY_OVERLAY_ROUTE = "dh_overlay_route"

    private fun walk(v: View?, block: (View) -> Unit) {
        if (v == null) {
            return
        }
        block(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                walk(v.getChildAt(i), block)
            }
        }
    }

    private fun remapTree(v: View?, old: IntArray, neu: IntArray) {
        walk(v) { view -> remapView(view, old, neu) }
    }

    private fun remapView(view: View, old: IntArray, neu: IntArray) {
        try {
            // 【顺序】先重映射背景并读出它重映射后的真实底色，文字的对比纠偏要用到它。
            remapDrawable(view.background, old, neu)
            // 【真实底色】沿父链向上找第一个纯色底，而不是一律用默认 CARD：
            //   文字常常没有自己的背景（卡片正文、强调色按钮上的白字），若按 CARD 判底，
            //   按钮上的白字会被误判成「白底白字」而被夹成深色，字就没法看了。
            var bgv: View? = view
            var bgc: Int? = null
            while (bgv != null && bgc == null) {
                bgc = solidColorOf(bgv.background)
                val p = bgv.parent
                bgv = if (p is View) p else null
            }
            val bg = bgc ?: UiKit.CARD

            if (view is TextView) {
                val tc = view.currentTextColor
                val nt = remapForeground(tc, bg, old, neu)
                if (nt != tc) {
                    view.setTextColor(nt)
                }
                val hint = view.hintTextColors?.defaultColor
                if (hint != null) {
                    val nh = remapForeground(hint, bg, old, neu)
                    if (nh != hint) {
                        view.setHintTextColor(nh)
                    }
                }
                // 复合 drawable（如 Icons.stateIcon 挂在文字左侧的状态图标）。
                val cds = view.compoundDrawables
                if (cds != null) {
                    for (d in cds) {
                        remapDrawable(d, old, neu)
                    }
                }
            }
            if (view is ImageView) {
                remapDrawable(view.drawable, old, neu)
            }

            // 自绘控件：Switch 的轨道底色是构造期写死的，必须重设；Slider 读字段绘制，invalidate 即可。
            if (view is UiKit.Switch) {
                view.reapplyTheme()
            }
            view.invalidate()
        } catch (ignored: Throwable) {
            // 单个控件重映射失败不应中断整棵树。
        }
    }

    /**
     * 文字前景色的语义纠偏。
     *
     * 【要修的病】旧调色板里有重复 RGB —— CARD 与 ON_ACC 都是纯白，只按 RGB 命中会一律
     *   映射成 CARD 的新色，于是「强调色按钮上的白字」被换成与按钮底色相同的色，字底一色看不见。
     * 【怎么修】把所有命中同一 RGB 的候选新色都列出来，用控件真实底色选对比度最高（且优先达标）的那个；
     *   单候选且恰好与底色撞色时也按 WCAG 夹紧。
     */
    private fun remapForeground(color: Int, bg: Int, old: IntArray, neu: IntArray): Int {
        val rgb = color and 0x00FFFFFF
        val alpha = color and 0xFF000000.toInt()
        val candidates = ArrayList<Int>(2)
        for (i in old.indices) {
            if ((old[i] and 0x00FFFFFF) == rgb) {
                candidates.add((neu[i] and 0x00FFFFFF) or alpha)
            }
        }
        if (candidates.isEmpty()) {
            return color
        }
        val opaqueBg = ContrastCore.composite(bg, UiKit.CARD)
        if (candidates.size == 1) {
            // 【夹紧】普通文字一律保证 ≥4.5:1；不再因为「与底色不是同一色」就放行低对比度。
            //   强调色按钮上的白字是多候选（CARD 与 ON_ACC 同 RGB），走下面分支保留。
            return ContrastCore.ensureContrast(candidates[0], opaqueBg, ContrastCore.AA_NORMAL)
        }
        // 多候选（重复 RGB）：选对比度最高者，保证字不与底同色。
        var best = candidates[0]
        var bestR = ContrastCore.contrastRatio(best, opaqueBg)
        for (c in candidates) {
            val r = ContrastCore.contrastRatio(c, opaqueBg)
            if (r > bestR) {
                bestR = r
                best = c
            }
        }
        return if (bestR >= ContrastCore.AA_NORMAL)
            best
        else
            ContrastCore.ensureContrast(best, opaqueBg, ContrastCore.AA_NORMAL)
    }

    /** 按 RGB 命中旧色则换成新色，保留原 alpha。 */
    fun remapColor(color: Int, old: IntArray, neu: IntArray): Int {
        val rgb = color and 0x00FFFFFF
        for (i in old.indices) {
            if ((old[i] and 0x00FFFFFF) == rgb) {
                return (neu[i] and 0x00FFFFFF) or (color and 0xFF000000.toInt())
            }
        }
        return color
    }

    private fun remapDrawable(d: Drawable?, old: IntArray, neu: IntArray) {
        if (d == null) {
            return
        }
        // 1) 图标 / 复合 drawable 的 tint：读出旧色再换。
        val tint = drawableTint(d)
        if (tint != null) {
            val nt = remapColor(tint, old, neu)
            if (nt != tint) {
                try {
                    d.mutate().setTint(nt)
                } catch (ignored: Throwable) {
                }
            }
        }
        // 2) 填充 / 描边 / 状态选择器。
        when (d) {
            is GradientDrawable -> {
                val cur = drawableSolid(d)
                if (cur != null) {
                    val nc = remapColor(cur, old, neu)
                    if (nc != cur) {
                        try {
                            d.setColor(nc)
                        } catch (ignored: Throwable) {
                        }
                    }
                }
                // 渐变填充：逐色重映射（getColors 需 API24，取不到就当没有渐变）。
                try {
                    val g = d.colors
                    if (g != null) {
                        val ng = IntArray(g.size)
                        var changed = false
                        for (i in g.indices) {
                            ng[i] = remapColor(g[i], old, neu)
                            if (ng[i] != g[i]) {
                                changed = true
                            }
                        }
                        if (changed) {
                            d.colors = ng
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }

            is ColorDrawable -> {
                val nc = remapColor(d.color, old, neu)
                if (nc != d.color) {
                    d.color = nc
                }
            }

            is StateListDrawable -> {
                for (i in 0 until d.stateCount) {
                    remapDrawable(d.getStateDrawable(i), old, neu)
                }
            }

            is LayerDrawable -> {
                for (i in 0 until d.numberOfLayers) {
                    remapDrawable(d.getDrawable(i), old, neu)
                }
            }

            is InsetDrawable -> remapDrawable(d.drawable, old, neu)
        }
    }

    /** 读一个 Drawable 的不透明纯色（ColorDrawable / GradientDrawable 单色）；拿不到返回 null。 */
    private fun solidColorOf(d: Drawable?): Int? {
        return when (d) {
            is ColorDrawable -> d.color
            is GradientDrawable -> drawableSolid(d)
            is InsetDrawable -> solidColorOf(d.drawable)
            is LayerDrawable -> {
                var found: Int? = null
                for (i in 0 until d.numberOfLayers) {
                    val c = solidColorOf(d.getDrawable(i))
                    if (c != null && ContrastCore.alphaOf(c) == 255) {
                        found = c
                        break
                    }
                }
                found
            }
            else -> null
        }
    }

    /** 读 GradientDrawable 的纯色填充（渐变填充返回 null）。 */
    private fun drawableSolid(g: GradientDrawable): Int? {
        return try {
            g.color?.defaultColor
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * 读出一个 Drawable 的 tint 颜色。
     *
     * 【坑】`Drawable.setTint()` 不写 `getColorFilter()`（那是用户色滤镜），
     *   而是生成一个私有 `mTintFilter`。所以先看 getColorFilter()，拿不到再反射 mTintFilter。
     */
    private fun drawableTint(d: Drawable): Int? {
        val direct = d.colorFilter
        if (direct is PorterDuffColorFilter) {
            colorOf(direct)?.let { return it }
        }
        val tintFilter = reflectField(d, "mTintFilter") as? PorterDuffColorFilter
        if (tintFilter != null) {
            return colorOf(tintFilter)
        }
        return null
    }

    /**
     * 取 PorterDuffColorFilter 的颜色。
     *
     * 【坑】`getColor()` 在 Android SDK 里是 @hide（非公开 API），直接引用编不过；
     *   故不再按 SDK_INT 分支，全版本统一反射 `mColor`，避免 API 差异导致的 Unresolved reference。
     */
    private fun colorOf(cf: PorterDuffColorFilter): Int? {
        return reflectField(cf, "mColor") as? Int
    }

    private fun reflectField(target: Any, name: String): Any? {
        return try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.get(target)
        } catch (ignored: Throwable) {
            null
        }
    }
}
